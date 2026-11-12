package com.ogenrwotdaniel.wakeproof.study

import android.content.Context
import com.ogenrwotdaniel.wakeproof.plan.PagePhoto
import com.ogenrwotdaniel.wakeproof.plan.PhotoShelf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A finished session whose summary page has not been photographed yet. */
@Serializable
data class PendingSummary(
    val sessionKey: String,
    val subject: String,
    val askedAtMillis: Long,
    /** [SteadyClock] at the ask, for the writing time; -1 when not known. */
    val askedElapsedMillis: Long = -1L,
    val bootCount: Int = -1
)

/**
 * A clock the user cannot set: time since boot, and which boot. The ten
 * minutes of writing are counted on it, or setting the phone's clock ahead
 * would open the photo button at once.
 */
data class SteadyClock(val elapsedMillis: Long, val bootCount: Int)

/** This moment on the [SteadyClock]. */
fun steadyClock(context: Context): SteadyClock = SteadyClock(
    android.os.SystemClock.elapsedRealtime(),
    runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)
)

@Serializable
data class SummaryState(
    val pending: PendingSummary? = null,
    /** The session last asked about, so a session recovered twice does not ask twice. */
    val lastKey: String = ""
)

/** The "summarize this session" notification, posted by the timer and removed here once answered. */
const val SUMMARY_NOTIFICATION_ID = 6

/** The least time spent writing the summary: the photo cannot be taken before it has passed. */
const val SUMMARY_WRITE_MILLIS = 10 * 60_000L

/**
 * The longest the summary holds the focus wall, counted from the session's
 * end. Past it the wall lifts by itself: a page that never gets photographed
 * (no paper, a call, a dead camera nobody noticed) must not lock the phone.
 */
const val SUMMARY_HOLD_MILLIS = 30 * 60_000L

/**
 * Pure: the summary still owed at [now], or null. While it is owed the focus
 * wall stays up. A clock stepped back a little (a network correction, a zone
 * change) still owes it; one set back further owes nothing, so the wall can
 * never be held for ever.
 */
fun summaryDue(state: SummaryState, now: Long, steady: SteadyClock? = null): PendingSummary? =
    state.pending?.takeIf { pending ->
        // On [steady] while the phone has not restarted since the ask: the
        // phone's clock set half an hour ahead must not lift the wall.
        if (sameBoot(pending, steady)) steady!!.elapsedMillis - pending.askedElapsedMillis <= SUMMARY_HOLD_MILLIS
        else now - pending.askedAtMillis in -SUMMARY_HOLD_MILLIS..SUMMARY_HOLD_MILLIS
    }

/** Pure: [steady] can be compared with the reading stored in [pending]. */
private fun sameBoot(pending: PendingSummary, steady: SteadyClock?): Boolean =
    steady != null && steady.bootCount >= 0 && steady.bootCount == pending.bootCount &&
        pending.askedElapsedMillis >= 0 && steady.elapsedMillis >= pending.askedElapsedMillis

/**
 * Pure: another device's summary, owed until [untilMillis], still holds this
 * one's session starts at [now]. An end further off than the hold can last is
 * a wrong clock on one side and holds nothing, so no device is shut out for long.
 */
fun peerSummaryHolds(untilMillis: Long, now: Long): Boolean =
    untilMillis - now in 1..SUMMARY_HOLD_MILLIS

/**
 * Pure: how much of the ten minutes of writing is left; 0 once the photo may
 * be taken. Counted on [steady] while the phone has not restarted since the
 * ask, on the wall clock ([now]) otherwise.
 */
fun summaryWriteLeftMillis(pending: PendingSummary, now: Long, steady: SteadyClock? = null): Long {
    val left = if (sameBoot(pending, steady)) pending.askedElapsedMillis + SUMMARY_WRITE_MILLIS - steady!!.elapsedMillis
    else pending.askedAtMillis + SUMMARY_WRITE_MILLIS - now
    return left.coerceIn(0L, SUMMARY_WRITE_MILLIS)
}

/** Pure: [state] owing session [key]'s summary; unchanged when that session was asked already. */
fun withSummaryAsked(
    state: SummaryState,
    key: String,
    subject: String,
    now: Long,
    /** The [SteadyClock] reading that goes with [now]; null when [now] lies before this boot. */
    steady: SteadyClock? = null
): SummaryState =
    if (key == state.lastKey) state
    else SummaryState(
        pending = PendingSummary(key, subject, now, steady?.elapsedMillis ?: -1L, steady?.bootCount ?: -1),
        lastKey = key
    )

/**
 * Pure: [state] after a summary photo was kept (or let go for a dead camera) at [now]. It settles what is
 * owed only once the writing time has passed, so a page photographed from the
 * library in the first minute does not end the ten minutes early.
 */
fun withSummaryKept(state: SummaryState, now: Long, steady: SteadyClock? = null): SummaryState {
    val pending = state.pending ?: return state
    return if (summaryWriteLeftMillis(pending, now, steady) > 0) state else state.copy(pending = null)
}

/**
 * The summary after a focus session (2026-11-13, the user's request): once the
 * last pomodoro (or the calendar event) has fully ended, the phone asks for at
 * least ten more minutes writing down everything the session taught, and then
 * a photo of the page. Mandatory: the focus wall stays up until the photo is
 * kept, for [SUMMARY_HOLD_MILLIS] at most. The photos are kept like the day
 * plans: on this device only, nothing reads them.
 */
@Singleton
class SessionSummaryStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val store = JsonFile(
        // Device-protected storage, readable before the first unlock: a session
        // found over at a restart owes its summary at once, and an ask held
        // only in memory until the unlock would be lost with the process.
        // Only what is owed lives here; the photos stay in the locked storage.
        file = { File(context.createDeviceProtectedStorageContext().filesDir, "session_summary.json") },
        serializer = SummaryState.serializer(),
        empty = SummaryState(),
        readable = { true }
    )
    private val shelf = PhotoShelf({ File(context.filesDir, "session_summaries") }, "summary")

    val state: StateFlow<SummaryState> = store.value

    /** Bumped on every save and delete, so the library reads again. */
    val revision: StateFlow<Int> = shelf.revision

    suspend fun load(): SummaryState = store.read()

    /** The summary still owed now, on the clock that cannot be set ([summaryDue]); null when none. */
    fun owed(state: SummaryState = store.value.value): PendingSummary? {
        // Asked on every tick of the wall service: nothing to look up when nothing is pending.
        if (state.pending == null) return null
        return summaryDue(
            state, System.currentTimeMillis(),
            SteadyClock(android.os.SystemClock.elapsedRealtime(), bootCount)
        )
    }

    /** Cannot change while the process lives, so the settings lookup is made once. */
    private val bootCount by lazy { steadyClock(context).bootCount }

    /** Session [key] just ended: its summary is owed. False when that session was asked already. */
    suspend fun ask(key: String, subject: String, now: Long = System.currentTimeMillis()): Boolean {
        var asked = false
        // [now] may be the end of a session found over at start-up: the steady
        // reading is moved back with it, and dropped when that is before this boot.
        val current = steadyClock(context)
        val elapsedAtAsk = current.elapsedMillis - (System.currentTimeMillis() - now).coerceAtLeast(0L)
        val steady = current.copy(elapsedMillis = elapsedAtAsk).takeIf { elapsedAtAsk >= 0 }
        store.update { state ->
            withSummaryAsked(state, key, subject, now, steady).also { asked = it !== state }
        }
        return asked
    }

    /** Keeps [jpeg] as a summary. Throws when storage cannot be written. */
    suspend fun submit(jpeg: ByteArray, now: Long = System.currentTimeMillis()): PagePhoto {
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val saved = shelf.save(jpeg, day, now)
        settle { withSummaryKept(it, now, steadyClock(context)) }
        return saved
    }

    /**
     * Lets the summary go without a photo: the way out when the camera cannot
     * take one. Not before the writing time has passed, or refusing the camera
     * permission would be a skip button.
     */
    suspend fun release(now: Long = System.currentTimeMillis()): Boolean =
        settle { withSummaryKept(it, now, steadyClock(context)) }

    /** True when nothing is owed afterwards. */
    private suspend fun settle(change: (SummaryState) -> SummaryState): Boolean {
        val after = store.update(change)
        // Settled: the notification must not go on asking.
        if (after.pending == null) runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).cancel(SUMMARY_NOTIFICATION_ID)
        }
        return after.pending == null
    }

    suspend fun list(): List<PagePhoto> = shelf.list()

    suspend fun delete(photo: PagePhoto) = shelf.delete(photo)
}
