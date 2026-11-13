package com.ogenrwotdaniel.wakeproof.data.sync

import android.util.Log
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.ogenrwotdaniel.wakeproof.alarm.AlarmScheduler
import com.ogenrwotdaniel.wakeproof.data.SettingsRepository
import com.ogenrwotdaniel.wakeproof.data.db.AlarmDao
import com.ogenrwotdaniel.wakeproof.data.db.SYNC_BATCH
import com.ogenrwotdaniel.wakeproof.data.db.SyncableEntity
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Outcome of one sync pass. */
sealed interface SyncResult {
    /** Nothing to do: no Firebase in this build, not signed in, or sync switched off. */
    data object Skipped : SyncResult

    /** [changed] is how many local rows the download actually altered. */
    data class Success(val changed: Int) : SyncResult

    data class Failure(val message: String) : SyncResult
}

/**
 * Mirrors the Room tables to `users/{uid}/{collection}/{syncId}` in Firestore and
 * back, so a phone and a tablet signed into the same account converge.
 *
 * The model is deliberately the simplest one that is actually correct for a
 * single user's own devices: push everything flagged locally, pull everything the
 * server has stamped since this device last looked, and settle any collision by
 * last write wins. There is no merge — two devices editing the same row in the
 * same minute is not a scenario worth the machinery, whereas "my tablet is three
 * days behind" is the normal case and is handled exactly.
 *
 * Push runs before pull on purpose. A row edited here is uploaded first, so if the
 * same row also changed elsewhere the pull that follows immediately applies the
 * winner — the devices agree at the end of one pass rather than two.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val availability: CloudSyncAvailability,
    private val firestore: FirebaseFirestore?,
    private val accounts: AccountRepository,
    private val preferences: SyncPreferences,
    private val tables: List<@JvmSuppressWildcards SyncTable<out SyncableEntity>>,
    private val alarms: AlarmDao,
    private val scheduler: AlarmScheduler,
    private val alarmInitializer: com.ogenrwotdaniel.wakeproof.alarm.AlarmInitializer,
    private val settings: SettingsRepository,
    private val studyUsage: StudyUsageSync,
    private val summaryStore: com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore,
    private val topThreeStore: com.ogenrwotdaniel.wakeproof.plan.TopThreeStore
) {

    /** One pass at a time: the periodic worker and a "Sync now" tap can overlap. */
    private val mutex = Mutex()

    /**
     * Runs a full pass over every table. Never throws — sync is background work
     * that must not take down whatever triggered it, so failures come back as
     * [SyncResult.Failure] and are also recorded for the settings screen.
     */
    suspend fun sync(): SyncResult = mutex.withLock {
        val db = firestore
        val uid = accounts.currentUid()
        if (!availability.isAvailable || db == null || uid == null || !preferences.isEnabled()) {
            return@withLock SyncResult.Skipped
        }
        try {
            var changed = 0
            var alarmsChanged = false
            tables.forEach { table ->
                pushTable(db, uid, table)
                val applied = pullTable(db, uid, table)
                if (applied > 0 && table.collection == ALARMS) alarmsChanged = true
                changed += applied
            }
            // Downloading an alarm is not enough — AlarmManager holds the schedule,
            // and until it is told, an alarm added on the phone would show on the
            // tablet's list without ever ringing there.
            if (retireSeededAlarms() || alarmsChanged) reconcileAlarmSchedule()
            if (syncSharedSettings(db, uid)) changed++
            // Not a table: a per-device report of today's study-app time, so the
            // other devices' study cards can include it. Best-effort, never fails
            // the pass.
            studyUsage.publishToday()
            preferences.recordSuccess(System.currentTimeMillis())
            SyncResult.Success(changed)
        } catch (e: Exception) {
            val message = when (e) {
                is FirebaseNetworkException -> "No connection."
                else -> e.message ?: e::class.java.simpleName
            }
            Log.w(TAG, "Sync failed", e)
            preferences.recordError(message)
            SyncResult.Failure(message)
        }
    }

    /**
     * Drops the starter alarms once the account has supplied real ones.
     *
     * A second device seeds four defaults on first launch, before it can know an
     * account exists. Left alone they would sit alongside the four that arrive from
     * the phone, and the user's first impression of sync would be a duplicated
     * schedule. They are never uploaded, so removing them is purely local.
     */
    private suspend fun retireSeededAlarms(): Boolean {
        if (alarms.countRealAlarms() == 0) return false
        // Cancel first. This is a hard delete, so the moment the rows go
        // reconcileAlarmSchedule can no longer see them — and a starter alarm whose
        // row is gone but whose pending intent is still armed rings every morning
        // with nothing left in the app to switch it off.
        alarms.seededAlarms().forEach(scheduler::cancel)
        alarms.deleteSeeded()
        return true
    }

    /**
     * Points AlarmManager at whatever the alarms table now says: schedule the live
     * ones, cancel the rest. Cancelling is driven off the full table including
     * tombstones, since an alarm deleted on another device still has a pending
     * intent registered here.
     */
    private suspend fun reconcileAlarmSchedule() {
        // A pull can carry an edit or delete of the locked alarm from the
        // other device; it is written back before anything is armed.
        runCatching { alarmInitializer.enforceStrict() }
        alarms.getAllIncludingDeleted().forEach { alarm ->
            if (alarm.enabled && !alarm.deleted) scheduler.schedule(alarm)
            else scheduler.cancel(alarm)
        }
    }

    /**
     * The settings that must agree across devices, kept in one document at
     * `users/{uid}/settings/shared`. Only the daily focus goal for now: the
     * streak is scored against it, and per-device goals meant the phone showed a
     * 1-day streak while the tablet showed 0 for the same day. Everything else
     * in settings is still deliberately per-device.
     *
     * One document read per pass, resolved by [sharedSettingAction].
     *
     * @return true when the local goal changed.
     */
    private suspend fun syncSharedSettings(db: FirebaseFirestore, uid: String): Boolean {
        val doc = collection(db, uid, SETTINGS).document(SHARED_SETTINGS)
        val remote = doc.get(Source.SERVER).await()
        val studyChanged = syncStudyApps(doc, remote)
        // Its failure must not cost the goal and the study apps their pass.
        try {
            syncTopThree(doc, remote)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SyncEngine", "top three not synced", e)
        }
        return syncFocusGoal(doc, remote) || studyChanged
    }

    private suspend fun syncFocusGoal(doc: DocumentReference, remote: DocumentSnapshot): Boolean {
        val remoteGoal = remote.getLong(FOCUS_GOAL)?.toInt()
        val remoteStamp = remote.getLong(FOCUS_GOAL_UPDATED_AT)?.takeIf { remoteGoal != null }
        val (localGoal, localStamp) = settings.focusGoalStamp()

        return when (sharedSettingAction(localStamp, remoteStamp)) {
            SharedSettingAction.PUSH -> {
                // A device seeding the cloud with a never-edited goal stamps it now,
                // so it reads as a real value from here on.
                val stamp = localStamp.takeIf { it > 0 } ?: System.currentTimeMillis()
                doc.set(
                    mapOf(
                        FOCUS_GOAL to localGoal,
                        FOCUS_GOAL_UPDATED_AT to stamp,
                        SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                if (stamp != localStamp) settings.applySyncedFocusGoal(localGoal, stamp)
                false
            }

            SharedSettingAction.PULL -> {
                settings.applySyncedFocusGoal(remoteGoal!!, remoteStamp!!)
                remoteGoal != localGoal
            }

            SharedSettingAction.NONE -> false
        }
    }

    /**
     * The study apps — which are also the focus list — ride the same document
     * (2026-11-01), same newest-wins rule as the goal. A session walls every
     * device, so every device has to agree on what it lets through.
     */
    private suspend fun syncStudyApps(doc: DocumentReference, remote: DocumentSnapshot): Boolean {
        @Suppress("UNCHECKED_CAST")
        val remoteApps = (remote.get(STUDY_APPS) as? List<String>)?.toSet()
        val remoteStamp = remote.getLong(STUDY_APPS_UPDATED_AT)?.takeIf { remoteApps != null }
        val (localApps, localStamp) = settings.studyAppsStamp()

        return when (sharedSettingAction(localStamp, remoteStamp)) {
            SharedSettingAction.PUSH -> {
                val stamp = localStamp.takeIf { it > 0 } ?: System.currentTimeMillis()
                doc.set(
                    mapOf(
                        STUDY_APPS to localApps.sorted(),
                        STUDY_APPS_UPDATED_AT to stamp,
                        SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
                if (stamp != localStamp) settings.applySyncedStudyApps(localApps, stamp)
                false
            }

            SharedSettingAction.PULL -> {
                val apply = studyAppsToApply(
                    local = localApps,
                    remote = remoteApps!!,
                    // The summary after a session holds the same wall on the same
                    // list: an app added on the tablet must not open it here.
                    sessionActive = PomodoroTimerService.state.value.active || runCatching {
                        summaryStore.owed(summaryStore.load()) != null
                    }.getOrDefault(false)
                )
                if (apply == remoteApps) settings.applySyncedStudyApps(apply, remoteStamp!!)
                else settings.applyStudyAppsUnstamped(apply)
                apply != localApps
            }

            SharedSettingAction.NONE -> false
        }
    }

    /**
     * Tomorrow's top three (2026-11-13, the user: "sync tomorrow's top 3
     * between phone and tablet too"): the evening written last rides the same
     * document, newest wins by the time it was written. Only that one evening:
     * it is what Home shows, and the document stays a few fields.
     */
    private suspend fun syncTopThree(doc: DocumentReference, remote: DocumentSnapshot) {
        val remoteItems = (remote.get(TOP_THREE_ITEMS) as? List<*>)?.filterIsInstance<String>()
        val remoteDay = remote.getLong(TOP_THREE_DAY)
        val remoteStamp = remote.getLong(TOP_THREE_UPDATED_AT)?.takeIf { remoteItems != null && remoteDay != null }
        val local = topThreeStore.newest()
        when {
            local != null && local.atMillis > (remoteStamp ?: 0L) -> doc.set(
                mapOf(
                    TOP_THREE_DAY to local.epochDay,
                    TOP_THREE_ITEMS to local.items,
                    TOP_THREE_UPDATED_AT to local.atMillis,
                    SERVER_UPDATED_AT to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            remoteStamp != null && remoteStamp > (local?.atMillis ?: 0L) -> topThreeStore.save(
                com.ogenrwotdaniel.wakeproof.plan.TopThree(
                    remoteDay!!, com.ogenrwotdaniel.wakeproof.plan.cleanTopThree(remoteItems!!), remoteStamp
                )
            )
        }
    }

    private fun collection(db: FirebaseFirestore, uid: String, name: String): CollectionReference =
        db.collection(USERS).document(uid).collection(name)

    /**
     * Uploads the rows this device has changed, in one batch, then clears their
     * upload flags. The flags are cleared only after the commit is acknowledged,
     * so a failed or interrupted upload simply leaves them queued for next time.
     */
    private suspend fun <T : SyncableEntity> pushTable(
        db: FirebaseFirestore,
        uid: String,
        table: SyncTable<T>
    ) {
        val pending = table.dao.pendingUploads(SYNC_BATCH)
        if (pending.isEmpty()) return

        val target = collection(db, uid, table.collection)
        val batch = db.batch()
        pending.forEach { entity ->
            // The server stamps its own clock alongside the device's. Only the
            // server's is trusted to order reads (see the pull cursor); the device
            // clock is kept purely to decide which of two versions is newer.
            val document = table.encode(entity) + mapOf(
                SERVER_UPDATED_AT to FieldValue.serverTimestamp()
            )
            batch.set(target.document(entity.syncId), document)
        }
        batch.commit().await()

        pending.forEach { table.dao.clearPending(it.syncId, it.updatedAt) }
    }

    /**
     * Downloads everything stamped after this device's cursor, applying each row
     * and advancing the cursor as it goes. Pages until the server runs out, so a
     * device that has been offline for a week catches up in one pass rather than
     * one page per pass.
     *
     * @return how many local rows actually changed.
     */
    private suspend fun <T : SyncableEntity> pullTable(
        db: FirebaseFirestore,
        uid: String,
        table: SyncTable<T>
    ): Int {
        val target = collection(db, uid, table.collection)
        var changed = 0
        var pages = 0

        while (pages++ < MAX_PAGES) {
            val cursor = preferences.cursor(table.collection)
            var query: Query = target.orderBy(SERVER_UPDATED_AT).limit(PAGE_SIZE.toLong())
            if (cursor != null) query = query.whereGreaterThan(SERVER_UPDATED_AT, cursor)

            // SERVER, not the default: offline this throws and the pass is reported
            // as failed, which is honest. The default would quietly serve the local
            // cache and let a pass that downloaded nothing look like a success.
            val page = query.get(Source.SERVER).await()
            if (page.isEmpty) break

            var newest: Timestamp? = null
            page.documents.forEach { doc ->
                val stamp = doc.getTimestamp(SERVER_UPDATED_AT) ?: return@forEach
                if (newest == null || stamp > newest!!) newest = stamp
                if (applyRemote(table, doc)) changed++
            }
            // Only advance once the whole page is applied: a crash mid-page then
            // replays it rather than skipping the rows it never got to.
            newest?.let { preferences.setCursor(table.collection, it) }
                ?: break

            if (page.size() < PAGE_SIZE) break
        }
        return changed
    }

    /**
     * Writes one downloaded row into Room if it is genuinely newer than what is
     * here. Returns whether anything changed.
     */
    private suspend fun <T : SyncableEntity> applyRemote(
        table: SyncTable<T>,
        doc: com.google.firebase.firestore.DocumentSnapshot
    ): Boolean {
        val remote = table.decodeOrNull(doc) ?: return false
        val local = table.dao.findBySyncId(remote.syncId)

        return when (mergeDecision(local?.updatedAt, remote.updatedAt, remote.deleted)) {
            MergeDecision.INSERT -> {
                table.dao.insertSynced(remote)
                true
            }

            MergeDecision.UPDATE -> {
                table.dao.updateSynced(table.withLocalId(remote, local!!.id))
                true
            }

            MergeDecision.IGNORE -> false
        }
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val USERS = "users"
        const val ALARMS = "alarms"
        const val SETTINGS = "settings"
        const val SHARED_SETTINGS = "shared"
        const val FOCUS_GOAL = "dailyFocusGoalMinutes"
        const val FOCUS_GOAL_UPDATED_AT = "dailyFocusGoalUpdatedAt"
        const val STUDY_APPS = "studyApps"
        const val STUDY_APPS_UPDATED_AT = "studyAppsUpdatedAt"
        const val TOP_THREE_DAY = "topThreeDay"
        const val TOP_THREE_ITEMS = "topThreeItems"
        const val TOP_THREE_UPDATED_AT = "topThreeUpdatedAt"

        /** Server-clock stamp, used only to order reads. */
        const val SERVER_UPDATED_AT = "serverUpdatedAt"
        const val PAGE_SIZE = 300

        /** Backstop against a paging bug turning into an unbounded read loop. */
        const val MAX_PAGES = 50
    }
}
