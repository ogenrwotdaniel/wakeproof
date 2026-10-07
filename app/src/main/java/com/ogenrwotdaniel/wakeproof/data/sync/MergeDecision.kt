package com.ogenrwotdaniel.wakeproof.data.sync

/** What to do with one downloaded row. */
enum class MergeDecision {
    /** This device has never seen the row; write it. */
    INSERT,

    /** The row is here and the download is newer; overwrite it. */
    UPDATE,

    /** Nothing to do. */
    IGNORE
}

/**
 * The whole conflict-resolution rule, in one pure function so it can be reasoned
 * about and tested without Firestore or Room.
 *
 * Last write wins, with two deliberate refinements:
 *
 * - A tombstone for a row this device never had is ignored rather than inserted.
 *   Writing it would create a row whose only purpose is to be filtered out of
 *   every query, and every device would keep one forever.
 * - Equal timestamps keep the local copy. A device re-reads its own uploads on the
 *   next pull, and those come back with exactly the timestamp they were sent with;
 *   treating that as a win would rewrite every row this device just pushed.
 *
 * @param localUpdatedAt null when the row is not present locally.
 */
fun mergeDecision(
    localUpdatedAt: Long?,
    remoteUpdatedAt: Long,
    remoteDeleted: Boolean
): MergeDecision = when {
    localUpdatedAt == null && remoteDeleted -> MergeDecision.IGNORE
    localUpdatedAt == null -> MergeDecision.INSERT
    remoteUpdatedAt > localUpdatedAt -> MergeDecision.UPDATE
    else -> MergeDecision.IGNORE
}

/** What to do with one setting shared across devices (see [sharedSettingAction]). */
enum class SharedSettingAction {
    /** Upload this device's value. */
    PUSH,

    /** Adopt the cloud's value. */
    PULL,

    /** Already in agreement. */
    NONE
}

/**
 * Last write wins for a single shared setting — today only the daily focus goal.
 *
 * Settings predate sync, so every existing install starts with a timestamp of 0
 * ("never changed here since sync began"). That case PULLS whenever the cloud
 * has anything, so a second device adopts the first one's goal instead of the
 * two sitting at 6 h and 9 h forever with equal, meaningless timestamps. The
 * first device to sync with nothing in the cloud seeds it.
 *
 * @param remoteUpdatedAt null when the cloud holds no value yet.
 */
fun sharedSettingAction(localUpdatedAt: Long, remoteUpdatedAt: Long?): SharedSettingAction = when {
    remoteUpdatedAt == null -> SharedSettingAction.PUSH
    localUpdatedAt == 0L -> SharedSettingAction.PULL
    remoteUpdatedAt > localUpdatedAt -> SharedSettingAction.PULL
    localUpdatedAt > remoteUpdatedAt -> SharedSettingAction.PUSH
    else -> SharedSettingAction.NONE
}
