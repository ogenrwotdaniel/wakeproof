package com.ogenrwotdaniel.wakeproof.pomodoro

import android.content.Context

/**
 * A running session as it is written to disk: enough to pick it up again after
 * the process dies — a reboot, a force-stop, the system reclaiming memory.
 *
 * Before this a session lived only in memory, so rebooting or force-stopping
 * WakeProof was a free way out of a session that otherwise has no stop button.
 * Phase ends are WALL-CLOCK times, since elapsedRealtime restarts at a boot.
 */
data class SavedSession(
    val focusSeconds: Int,
    val shortBreakSeconds: Int,
    val longBreakSeconds: Int,
    val longBreakEvery: Int,
    val targetFocusBlocks: Int,
    val completedFocusSessions: Int,
    val phase: PomodoroPhase,
    val phaseEndsAtMillis: Long,
    val treeWithered: Boolean,
    /** The last focus block's length — shorter in an exact session ([exactSessionPlan]). */
    val lastFocusSeconds: Int = focusSeconds,
    /** Names the session on every device ([SharedSession.id]); blank in a record from before 2026-11-11. */
    val sessionId: String = "",
    /** Where the session started ([SharedSession.origin]). */
    val origin: String = SharedSession.ORIGIN_SELF
)

/** What picking a saved session back up comes to. */
data class RecoveredSession(
    /** The session to run from now; null when it has nothing left to run. */
    val resume: SavedSession?,
    /** A focus block was escaped and is logged as a withered tree. */
    val witheredBlock: Boolean
)

/**
 * Pure: picks [saved] up again at [nowMillis].
 *
 * Escaping is not free, and it is not punished twice either:
 * * Phase still running: it resumes where the clock says it is. If it is a
 *   focus block, its tree withers — the block was walked out of.
 * * Phase already over: if it was focus, that block is logged withered; the
 *   NEXT phase then starts now, at full length. One step only, like the watch:
 *   the session is not fast-forwarded past time nobody sat through, so the
 *   walls last longer for having been dodged, never shorter.
 * * The block that ended was the last one: the session is over.
 */
fun recoverSession(saved: SavedSession, nowMillis: Long): RecoveredSession {
    if (saved.phaseEndsAtMillis > nowMillis) {
        val focus = saved.phase == PomodoroPhase.FOCUS
        return RecoveredSession(
            resume = saved.copy(treeWithered = saved.treeWithered || focus),
            witheredBlock = false
        )
    }
    if (saved.phase != PomodoroPhase.FOCUS) {
        return RecoveredSession(
            resume = saved.copy(
                phase = PomodoroPhase.FOCUS,
                phaseEndsAtMillis = nowMillis + focusSecondsForBlock(
                    block = saved.completedFocusSessions + 1,
                    targetFocusBlocks = saved.targetFocusBlocks,
                    focusSeconds = saved.focusSeconds,
                    lastFocusSeconds = saved.lastFocusSeconds
                ) * 1_000L,
                treeWithered = false
            ),
            witheredBlock = false
        )
    }
    val done = saved.completedFocusSessions + 1
    if (saved.targetFocusBlocks in 1..done) return RecoveredSession(resume = null, witheredBlock = true)
    val next = if (done % saved.longBreakEvery == 0) PomodoroPhase.LONG_BREAK else PomodoroPhase.SHORT_BREAK
    val length = if (next == PomodoroPhase.LONG_BREAK) saved.longBreakSeconds else saved.shortBreakSeconds
    return RecoveredSession(
        resume = saved.copy(
            completedFocusSessions = done,
            phase = next,
            phaseEndsAtMillis = nowMillis + length * 1_000L,
            treeWithered = false
        ),
        witheredBlock = true
    )
}

/**
 * Pure: seconds left in the whole session, the running phase's [remainingSeconds]
 * included. Mirrors the service's own cadence: a break after every focus block
 * but the last, long after every [longBreakEvery]-th. Used to tell the user's
 * other devices how long the focus wall lasts.
 */
fun sessionRemainingSeconds(
    phase: PomodoroPhase,
    remainingSeconds: Int,
    completedFocusSessions: Int,
    targetFocusBlocks: Int,
    focusSeconds: Int,
    shortBreakSeconds: Int,
    longBreakSeconds: Int,
    longBreakEvery: Int,
    lastFocusSeconds: Int = focusSeconds
): Long {
    var total = remainingSeconds.toLong()
    if (targetFocusBlocks <= 0) return total
    fun breakAfter(block: Int) =
        if (block % longBreakEvery == 0) longBreakSeconds else shortBreakSeconds
    // The focus block running now (or the next one, during a break).
    val current = if (phase == PomodoroPhase.FOCUS) completedFocusSessions + 1 else completedFocusSessions
    if (phase == PomodoroPhase.FOCUS && current < targetFocusBlocks) total += breakAfter(current)
    for (block in current + 1..targetFocusBlocks) {
        total += focusSecondsForBlock(block, targetFocusBlocks, focusSeconds, lastFocusSeconds)
        if (block < targetFocusBlocks) total += breakAfter(block)
    }
    return total
}

/** [SavedSession] in SharedPreferences, written with `commit` before a process can die on it. */
object SessionStore {
    private const val PREFS = "pomodoro_session"
    private const val KEY = "session"

    fun save(context: Context, session: SavedSession) {
        prefs(context).edit().putString(KEY, encodeSession(session)).commit()
    }

    fun load(context: Context): SavedSession? = decodeSession(prefs(context).getString(KEY, null))

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun encodeSession(s: SavedSession): String = listOf(
    s.focusSeconds, s.shortBreakSeconds, s.longBreakSeconds, s.longBreakEvery,
    s.targetFocusBlocks, s.completedFocusSessions, s.phase.name, s.phaseEndsAtMillis,
    if (s.treeWithered) 1 else 0, s.lastFocusSeconds, s.sessionId, s.origin
).joinToString("|")

/** Null for anything unreadable: a corrupt record cannot be resumed honestly. */
internal fun decodeSession(stored: String?): SavedSession? {
    val p = stored?.split('|') ?: return null
    // Nine fields predate the exact session's trimmed last block (2026-11-01),
    // ten the session id and origin (2026-11-11).
    if (p.size != 9 && p.size != 10 && p.size != 12) return null
    return runCatching {
        val focus = p[0].toInt()
        SavedSession(
            focusSeconds = focus,
            shortBreakSeconds = p[1].toInt(),
            longBreakSeconds = p[2].toInt(),
            longBreakEvery = p[3].toInt().coerceAtLeast(1),
            targetFocusBlocks = p[4].toInt(),
            completedFocusSessions = p[5].toInt(),
            phase = PomodoroPhase.valueOf(p[6]),
            phaseEndsAtMillis = p[7].toLong(),
            treeWithered = p[8] == "1",
            lastFocusSeconds = p.getOrNull(9)?.toInt() ?: focus,
            sessionId = validSessionId(p.getOrNull(10)).orEmpty(),
            origin = p.getOrNull(11)?.takeIf { it.isNotBlank() } ?: SharedSession.ORIGIN_SELF
        )
    }.getOrNull()
}
