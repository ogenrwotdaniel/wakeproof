package com.ogenrwotdaniel.wakeproof.pomodoro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One focus session, as every one of the user's devices can see it
 * (2026-11-11, the user's request: "once I start on watch I can just tap and
 * continue on phone"). Until then each device ran its own timer and the others
 * only walled themselves until it ended.
 *
 * A session cannot be paused or stopped, so where it is now and how it is
 * built is all a second device needs to run the very same countdown: there is
 * no "host" to hand over. A device that joins runs the session too, from the
 * same phase end, and every device that runs it records each finished block
 * under the same id ([sharedBlockId]), so two devices finishing one block make
 * one row and never two.
 *
 * Carried on three paths, all kept in step by hand: the watch's Data Layer
 * item and cloud document (`wear` `WatchSessionContract`, `CloudContract`), the
 * phone's timer item for the watch (`WearContract.KEY_TIMER_*`), and
 * `users/{uid}/live/focusSession` between phone and tablet.
 *
 * Times are wall clock: the devices agree to whatever network time leaves
 * between them, a second or so.
 */
data class SharedSession(
    /** Minted where the session started; names its blocks on every device. */
    val id: String,
    /** Where it started: [ORIGIN_WATCH] or a phone or tablet's install id. */
    val origin: String,
    val phase: PomodoroPhase,
    val phaseEndsAtMillis: Long,
    val completedFocus: Int,
    val targetFocusBlocks: Int,
    val focusSeconds: Int,
    val shortBreakSeconds: Int,
    val longBreakSeconds: Int,
    val longBreakEvery: Int,
    val lastFocusSeconds: Int = focusSeconds,
    val subject: String? = null,
    /**
     * This copy was written by the device the session started on. Only such a
     * copy moves a follower's countdown ([realignTo]): two followers correcting
     * each other would chase one another's rounding for the whole session.
     */
    val fromOrigin: Boolean = false
) {
    /** Where in the session this is: 0 for the first focus block, 1 its break, 2 the next block. */
    val position: Int get() = sessionPosition(phase, completedFocus)

    fun toSaved(): SavedSession = SavedSession(
        focusSeconds = focusSeconds,
        shortBreakSeconds = shortBreakSeconds,
        longBreakSeconds = longBreakSeconds,
        longBreakEvery = longBreakEvery,
        targetFocusBlocks = targetFocusBlocks,
        completedFocusSessions = completedFocus,
        phase = phase,
        phaseEndsAtMillis = phaseEndsAtMillis,
        treeWithered = false,
        lastFocusSeconds = lastFocusSeconds,
        sessionId = id,
        origin = origin
    )

    /** When the whole session ends: the same sum the focus wall is published with. */
    fun endsAtMillis(): Long = phaseEndsAtMillis + 1_000L * sessionRemainingSeconds(
        phase = phase,
        remainingSeconds = 0,
        completedFocusSessions = completedFocus,
        targetFocusBlocks = targetFocusBlocks,
        focusSeconds = focusSeconds,
        shortBreakSeconds = shortBreakSeconds,
        longBreakSeconds = longBreakSeconds,
        longBreakEvery = longBreakEvery,
        lastFocusSeconds = lastFocusSeconds
    )

    companion object {
        const val ORIGIN_WATCH = "watch"

        /** A session started on this very device, before its install id is put in its place. */
        const val ORIGIN_SELF = "self"
    }
}

/** Pure: see [SharedSession.position]. */
fun sessionPosition(phase: PomodoroPhase, completedFocus: Int): Int =
    if (phase == PomodoroPhase.FOCUS) completedFocus * 2 else completedFocus * 2 - 1

/**
 * Pure: [id] if it can name a session, else null. Letters, digits and dashes
 * only: it is stored between `|` separators and becomes part of a row's sync id.
 */
fun validSessionId(id: String?): String? = id?.takeIf { SESSION_ID.matches(it) }

private val SESSION_ID = Regex("[A-Za-z0-9-]{8,64}")

/**
 * Pure: the sync id of focus block [block] (1-based) of session [sessionId], on
 * every device that ran it. MUST match the watch's `WatchTimer.blockId`.
 */
fun sharedBlockId(sessionId: String, block: Int): String = "$sessionId-b$block"

/**
 * Pure: a session a device may join at [now]: well formed, with a focus block
 * still to finish and enough of its phase left to be worth starting a timer for.
 * A copy this close to its phase end is about to be replaced by the next one.
 */
fun SharedSession.joinableAt(now: Long): Boolean =
    validSessionId(id) != null &&
        targetFocusBlocks in 1..MAX_SHARED_BLOCKS &&
        completedFocus in 0 until targetFocusBlocks &&
        focusSeconds in 60..MAX_SHARED_PHASE_SECONDS &&
        lastFocusSeconds in 60..MAX_SHARED_PHASE_SECONDS &&
        shortBreakSeconds in 60..MAX_SHARED_PHASE_SECONDS &&
        longBreakSeconds in 60..MAX_SHARED_PHASE_SECONDS &&
        longBreakEvery >= 1 &&
        phaseEndsAtMillis - now in JOIN_MARGIN_MILLIS..MAX_SHARED_PHASE_SECONDS * 1_000L

/** A phase nobody set: the settings stop far below four hours. */
private const val MAX_SHARED_PHASE_SECONDS = 4 * 60 * 60
private const val MAX_SHARED_BLOCKS = 48

/** Less than this left in a phase and a join waits for the next one. */
const val JOIN_MARGIN_MILLIS = 5_000L

/** Two countdowns of one session this far apart are brought together. */
const val REALIGN_TOLERANCE_MILLIS = 5_000L

/**
 * Pure: whether a device running [shared]'s session at [localPosition], its
 * phase ending at [localPhaseEndsAt], should move its countdown to [shared]'s.
 * Only to the origin's own copy, only forwards or within the same phase (a
 * stale copy from a moment ago must not replay a block's end), and only for a
 * real difference: a skipped break on the watch, or an alarm that fired late.
 */
fun realignTo(
    shared: SharedSession,
    localSessionId: String,
    localPosition: Int,
    localPhaseEndsAt: Long,
    now: Long
): Boolean =
    shared.fromOrigin && shared.id == localSessionId && shared.joinableAt(now) &&
        (shared.position > localPosition ||
            (shared.position == localPosition &&
                kotlin.math.abs(shared.phaseEndsAtMillis - localPhaseEndsAt) > REALIGN_TOLERANCE_MILLIS))

private const val FIELD_SEPARATOR = "|"

/** [SharedSession] as one string, for an intent extra. */
fun encodeShared(s: SharedSession): String = listOf(
    s.id, s.origin, s.phase.name, s.phaseEndsAtMillis, s.completedFocus, s.targetFocusBlocks,
    s.focusSeconds, s.shortBreakSeconds, s.longBreakSeconds, s.longBreakEvery, s.lastFocusSeconds,
    if (s.fromOrigin) 1 else 0,
    // Last: a subject is free text and may hold the separator.
    s.subject.orEmpty()
).joinToString(FIELD_SEPARATOR)

/** Null for anything unreadable. */
fun decodeShared(stored: String?): SharedSession? {
    val p = stored?.split(FIELD_SEPARATOR, limit = 13) ?: return null
    if (p.size != 13) return null
    return runCatching {
        SharedSession(
            id = validSessionId(p[0]) ?: return null,
            origin = p[1],
            phase = PomodoroPhase.valueOf(p[2]),
            phaseEndsAtMillis = p[3].toLong(),
            completedFocus = p[4].toInt(),
            targetFocusBlocks = p[5].toInt(),
            focusSeconds = p[6].toInt(),
            shortBreakSeconds = p[7].toInt(),
            longBreakSeconds = p[8].toInt(),
            longBreakEvery = p[9].toInt().coerceAtLeast(1),
            lastFocusSeconds = p[10].toInt(),
            fromOrigin = p[11] == "1",
            subject = p[12].takeIf { it.isNotBlank() }
        )
    }.getOrNull()
}

/** [SharedSession] as document or Data Layer fields; see [sharedFromFields]. */
fun sharedFields(s: SharedSession): Map<String, Any?> = mapOf(
    SharedKeys.ID to s.id,
    SharedKeys.ORIGIN to s.origin,
    SharedKeys.PHASE to s.phase.name,
    SharedKeys.PHASE_ENDS_AT to s.phaseEndsAtMillis,
    SharedKeys.COMPLETED to s.completedFocus,
    SharedKeys.TARGET to s.targetFocusBlocks,
    SharedKeys.FOCUS_SECONDS to s.focusSeconds,
    SharedKeys.SHORT_SECONDS to s.shortBreakSeconds,
    SharedKeys.LONG_SECONDS to s.longBreakSeconds,
    SharedKeys.LONG_EVERY to s.longBreakEvery,
    SharedKeys.LAST_FOCUS_SECONDS to s.lastFocusSeconds,
    SharedKeys.SUBJECT to s.subject
)

/**
 * Pure: the session in [fields], or null when there is none or this build
 * cannot read it. [writer] is who wrote the copy, which decides [SharedSession.fromOrigin].
 */
fun sharedFromFields(fields: Map<String, Any?>, writer: String): SharedSession? {
    val id = validSessionId(fields[SharedKeys.ID] as? String) ?: return null
    val phase = PomodoroPhase.entries.firstOrNull { it.name == fields[SharedKeys.PHASE] } ?: return null
    fun int(key: String): Int? = (fields[key] as? Number)?.toInt()
    val focus = int(SharedKeys.FOCUS_SECONDS) ?: return null
    val origin = (fields[SharedKeys.ORIGIN] as? String)?.takeIf { it.isNotBlank() } ?: writer
    return SharedSession(
        id = id,
        origin = origin,
        phase = phase,
        phaseEndsAtMillis = (fields[SharedKeys.PHASE_ENDS_AT] as? Number)?.toLong() ?: return null,
        completedFocus = int(SharedKeys.COMPLETED) ?: return null,
        targetFocusBlocks = int(SharedKeys.TARGET) ?: return null,
        focusSeconds = focus,
        shortBreakSeconds = int(SharedKeys.SHORT_SECONDS) ?: return null,
        longBreakSeconds = int(SharedKeys.LONG_SECONDS) ?: return null,
        longBreakEvery = (int(SharedKeys.LONG_EVERY) ?: return null).coerceAtLeast(1),
        lastFocusSeconds = int(SharedKeys.LAST_FOCUS_SECONDS) ?: focus,
        subject = (fields[SharedKeys.SUBJECT] as? String)?.takeIf { it.isNotBlank() },
        fromOrigin = origin == writer
    )
}

/**
 * Field names of a shared session in `live/focusSession`. The watch's cloud
 * document and Data Layer items carry the same values under their own keys
 * (`LiveWatchTimer`, `WearContract`).
 */
object SharedKeys {
    const val ID = "sessionId"
    const val ORIGIN = "origin"
    const val PHASE = "phase"
    const val PHASE_ENDS_AT = "phaseEndsAt"
    const val COMPLETED = "completedFocus"
    const val TARGET = "targetFocusBlocks"
    const val FOCUS_SECONDS = "focusSeconds"
    const val SHORT_SECONDS = "shortBreakSeconds"
    const val LONG_SECONDS = "longBreakSeconds"
    const val LONG_EVERY = "longBreakEvery"
    const val LAST_FOCUS_SECONDS = "lastFocusSeconds"
    const val SUBJECT = "subject"
}

/**
 * The session running on another of the user's devices, as this one last heard
 * of it: from the watch (Bluetooth or its cloud document) or from a phone or
 * tablet (`live/focusSession`). Kept by `AppBlockService`, which is always
 * running and already follows all three; read by the timer, Home and the timer
 * screen.
 */
object PeerSessions {
    private val _current = MutableStateFlow<SharedSession?>(null)

    /** Null when no other device runs a session this build can join. */
    val current: StateFlow<SharedSession?> = _current.asStateFlow()

    /**
     * Records what is known now. The origin's own copy is believed first; of two
     * relayed copies, the one further on.
     */
    fun update(watch: SharedSession?, peer: SharedSession?) {
        _current.value = pickShared(watch, peer)
    }

    /** The session to join at [now], if any. */
    fun joinable(now: Long = System.currentTimeMillis()): SharedSession? =
        _current.value?.takeIf { it.joinableAt(now) }
}

/** Pure: see [PeerSessions.update]. */
fun pickShared(a: SharedSession?, b: SharedSession?): SharedSession? {
    if (a == null) return b
    if (b == null) return a
    if (a.fromOrigin != b.fromOrigin) return if (a.fromOrigin) a else b
    return if (b.position > a.position) b else a
}
