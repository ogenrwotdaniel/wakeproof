package com.ogenrwotdaniel.wakeproof.wear

/**
 * A focus block timed on the watch, as it arrives over the Data Layer.
 *
 * [id] is the block's id on the watch and becomes the row's `syncId`, which is
 * what lets the same block arrive twice — a Data Layer redelivery, or the copy
 * cloud sync brings from another device — and still be one row.
 */
data class WatchFocusBlock(
    val id: String,
    val epochDay: Long,
    val minutes: Int,
    val subject: String?
)

/** Pure acceptance rules for a block from the watch; unit-tested. */
object WatchSessionImport {

    /** Longest focus phase the phone's own settings allow is far below this. */
    const val MAX_BLOCK_MINUTES = 240

    /**
     * Returns the block if it is worth recording, null if it is not.
     *
     * Rejected rather than clamped: a block with no id cannot be deduplicated,
     * so recording it risks counting it on every redelivery; zero or absurd
     * minutes is a corrupt item, not a short session. A day more than one ahead
     * of the phone's is a watch with a wrong clock (one ahead is just a
     * timezone), and recording it would credit a day that has not happened yet.
     */
    fun accept(
        id: String?,
        epochDay: Long,
        minutes: Int,
        subject: String?,
        todayEpochDay: Long
    ): WatchFocusBlock? {
        if (id.isNullOrBlank()) return null
        if (minutes !in 1..MAX_BLOCK_MINUTES) return null
        if (epochDay <= 0L || epochDay > todayEpochDay + 1) return null
        return WatchFocusBlock(id, epochDay, minutes, subject?.takeIf { it.isNotBlank() })
    }
}
