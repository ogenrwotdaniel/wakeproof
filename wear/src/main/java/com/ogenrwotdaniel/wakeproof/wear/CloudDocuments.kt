package com.ogenrwotdaniel.wakeproof.wear

/**
 * The watch's half of the Firestore layout. MUST match the phone: the
 * `completion_logs` fields in app `data/sync/SyncTable.kt`, the sync columns in
 * app `data/db/SyncableEntity.kt` (`SyncColumns`), `SERVER_UPDATED_AT` in
 * `SyncEngine`, and the live-timer keys in app `data/sync/LiveWatchTimer.kt`.
 * Duplicated by hand like the four Data Layer paths — there is no shared module.
 */
internal object CloudContract {
    const val USERS = "users"
    const val COMPLETION_LOGS = "completion_logs"

    // SyncColumns + the server-clock read cursor.
    const val SYNC_ID = "syncId"
    const val UPDATED_AT = "updatedAt"
    const val DELETED = "deleted"
    const val SERVER_UPDATED_AT = "serverUpdatedAt"

    // completion_logs fields.
    const val EPOCH_DAY = "epochDay"
    const val KIND = "kind"
    const val STATUS = "status"
    const val AMOUNT = "amount"
    const val SUBJECT = "subject"
    const val KIND_FOCUS = "FOCUS"
    const val STATUS_VERIFIED = "VERIFIED"

    /** `users/{uid}/live/watchTimer`: one overwritten document, not a synced table. */
    const val LIVE = "live"
    const val WATCH_TIMER = "watchTimer"
    const val T_ACTIVE = "active"
    const val T_RUNNING = "running"
    const val T_PHASE = "phase"
    const val T_PHASE_ENDS_AT = "phaseEndsAt"
    const val T_REMAINING = "remainingMillis"
    const val T_COMPLETED = "completedFocus"
    const val T_TARGET = "targetFocusBlocks"
    const val T_FOCUS_MINUTES = "focusMinutes"
    const val T_SUBJECT = "subject"

    /**
     * Phone -> watch: a fresh Google ID token for the phone's own account, so the
     * watch can sign in without a picker on a 45 mm screen. MessageClient, since
     * a token is an event, and one that must not linger on the Data Layer.
     * MUST match app `WearContract.AUTH_PATH`.
     */
    const val AUTH_PATH = "/wakeproof/auth"
}

/** Pure document bodies for the watch's two cloud writes; unit-tested. */
internal object CloudDocuments {

    /**
     * A finished block as the phone's sync would have written it: an ordinary
     * VERIFIED FOCUS completion log keyed by the block id. Because the id is also
     * the `syncId` the phone gives the block when it imports it off the Data
     * Layer, the two copies are one row everywhere.
     *
     * `updatedAt` is when the block ended. The phone's import is later, so if the
     * phone ever uploads its copy, that copy wins — with identical content.
     * The server stamp is added by the caller (it is a Firestore sentinel).
     */
    fun block(block: FocusBlock): Map<String, Any?> = mapOf(
        CloudContract.EPOCH_DAY to block.epochDay,
        CloudContract.KIND to CloudContract.KIND_FOCUS,
        CloudContract.STATUS to CloudContract.STATUS_VERIFIED,
        CloudContract.AMOUNT to block.minutes,
        CloudContract.SUBJECT to block.subject,
        CloudContract.SYNC_ID to block.id,
        CloudContract.UPDATED_AT to block.endedAt,
        CloudContract.DELETED to false
    )

    /**
     * The watch timer as the tablet needs it to draw a countdown. Wall-clock
     * times, like the state itself; the reader's clock and this one differ only
     * by whatever NTP leaves between them.
     */
    fun liveTimer(state: WatchTimerState, now: Long): Map<String, Any?> = mapOf(
        CloudContract.T_ACTIVE to state.active,
        CloudContract.T_RUNNING to state.running,
        CloudContract.T_PHASE to state.phase.name,
        CloudContract.T_PHASE_ENDS_AT to state.phaseEndsAt,
        CloudContract.T_REMAINING to state.remainingMillisAt(now),
        CloudContract.T_COMPLETED to state.completedFocus,
        CloudContract.T_TARGET to state.targetFocusBlocks,
        CloudContract.T_FOCUS_MINUTES to state.durations.focusMinutes,
        CloudContract.T_SUBJECT to state.subject,
        CloudContract.UPDATED_AT to now
    )

    /**
     * What decides whether a live write is worth sending: everything except the
     * write time and the paused remainder's drift. Two identical states in a row
     * (a sweep that changed nothing) cost one comparison, not a network write.
     */
    fun liveTimerKey(state: WatchTimerState): String =
        if (!state.active) "idle"
        else listOf(
            state.running, state.phase.name, state.phaseEndsAt, state.remainingMillis,
            state.completedFocus, state.targetFocusBlocks, state.subject.orEmpty()
        ).joinToString("|")
}
