package com.ogenrwotdaniel.wakeproof.alarm

import com.ogenrwotdaniel.wakeproof.data.AlarmRepository
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton

/**
 * Brings AlarmManager in sync with the database: seeds the default alarms on
 * first launch, (re)schedules every live alarm, and drops the schedule of every
 * alarm that is no longer live. Safe to call repeatedly — rescheduling replaces
 * the existing PendingIntent. Called from app startup, from [BootReceiver], and
 * whenever the device's alarm role is switched.
 */
@Singleton
class AlarmInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AlarmRepository,
    private val scheduler: AlarmScheduler,
    private val directBootStore: DirectBootAlarmStore,
    private val devicePolicy: AlarmDevicePolicy
) {
    suspend fun initialize() {
        repository.seedDefaultAlarmsIfEmpty()
        enforceStrict()
        val plan = reconcilePlan(
            alarms = repository.getAllAlarmsIncludingDeleted(),
            mirroredIds = directBootStore.ids(),
            ringsAlarms = devicePolicy.ringsAlarms
        )
        // Arm the live alarms before dropping anything: if the process is killed
        // part-way through, the failure mode is a stale alarm rather than a
        // missed one.
        plan.toSchedule.forEach(scheduler::schedule)
        plan.toCancel.forEach(scheduler::cancel)
        plan.orphanIds.forEach(scheduler::cancelById)
    }

    /**
     * Pins every [StrictAlarm] slot on the ringing device: adopts an alarm the
     * first time ([strictAlarmCandidate], or a new one), brings it back if it
     * was deleted, and rewrites any change to it. Then, on every device,
     * deletes alarms of a retired kind ([retiredAlarms]).
     */
    suspend fun enforceStrict() {
        dropRemovedSlots()
        pinSlots()
        // Here rather than in [initialize] because a sync pull calls only this,
        // and water and day-plan alarms arrive by sync.
        retiredAlarms(repository.getAllAlarmsIncludingDeleted())
            .forEach { repository.deleteAlarm(it) }
    }

    /**
     * Deletes the rows this device locked for slots that no longer exist
     * ([StrictAlarm.DROPPED_KEYS]). Before [pinSlots], and on any device that
     * holds one, ringing or not. The keys are forgotten only once every row is
     * gone, so a delete that failed is tried again at the next reconcile.
     */
    private suspend fun dropRemovedSlots() {
        val dropped = StrictAlarm.droppedSyncIds(context)
        if (dropped.isEmpty()) return
        repository.getAllAlarmsIncludingDeleted()
            .filter { it.syncId in dropped && !it.deleted }
            .forEach { repository.deleteAlarm(it) }
        StrictAlarm.forgetDropped(context)
    }

    private suspend fun pinSlots() {
        if (!devicePolicy.ringsAlarms) return
        WearAlarmSync.pushSchedule(context, StrictAlarm.hardMinutes)
        StrictAlarm.PINNED.forEach { (index, slot) ->
            val all = repository.getAllAlarmsIncludingDeleted()
            val syncId = StrictAlarm.syncId(context, index)
            var row = all.firstOrNull { it.syncId == syncId }
            if (row == null) {
                row = strictAlarmCandidate(all, slot, taken = StrictAlarm.syncIds(context)) ?: run {
                    repository.addAlarm(slot.type, slot.hour, slot.minute, strictLabel(slot))
                    repository.getAllAlarmsIncludingDeleted().maxByOrNull { it.id }
                } ?: return@forEach
            }
            StrictAlarm.adopt(context, index, row)
            val fixed = strictAlarmFixed(row, slot, System.currentTimeMillis()) ?: return@forEach
            repository.updateAlarm(fixed)
            scheduler.schedule(fixed)
        }
    }
}

/**
 * @param toSchedule alarms that should be armed.
 * @param toCancel alarms whose row still exists but which must not ring.
 * @param orphanIds mirrored alarm ids with no row left at all.
 */
internal data class AlarmReconcilePlan(
    val toSchedule: List<Alarm>,
    val toCancel: List<Alarm>,
    val orphanIds: List<Long>
)

/**
 * Decides what AlarmManager should hold, given the whole alarms table
 * (tombstones included) and the ids in the direct-boot mirror.
 *
 * [orphanIds] is the repair path for ghost alarms. Most ways of removing an
 * alarm leave a tombstone, which keeps it visible here long enough to cancel —
 * but retiring the seeded starter alarms is a hard delete, so those rows vanish
 * while their pending intents stay armed. Nothing in the table can point at them
 * afterwards; the mirror is the only remaining record that they were ever
 * scheduled, and an id in the mirror with no row behind it is exactly that ghost.
 */
internal fun reconcilePlan(
    alarms: List<Alarm>,
    mirroredIds: Set<Long>,
    ringsAlarms: Boolean = true
): AlarmReconcilePlan {
    // A retired kind never rings, whatever its row says: there is no screen left to answer it.
    val (live, dead) = alarms.partition { it.enabled && !it.deleted && !it.type.retired }
    return AlarmReconcilePlan(
        // A companion device arms nothing and disarms everything, so switching
        // the role off is a real disarm rather than a promise not to ring: the
        // pending intents actually go, and so does the direct-boot mirror that
        // would otherwise re-register them on the next reboot.
        toSchedule = if (ringsAlarms) live else emptyList(),
        toCancel = if (ringsAlarms) dead else alarms,
        orphanIds = (mirroredIds - alarms.map { it.id }.toSet()).sorted()
    )
}
