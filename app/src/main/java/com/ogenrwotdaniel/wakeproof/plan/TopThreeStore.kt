package com.ogenrwotdaniel.wakeproof.plan

import android.content.Context
import com.ogenrwotdaniel.wakeproof.distraction.BEDTIME_MINUTES
import com.ogenrwotdaniel.wakeproof.study.JsonFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Twenty minutes before bed (19:40): when tomorrow's top three are asked for. */
const val TOP_THREE_MINUTES = BEDTIME_MINUTES - 20

/** Evenings kept: enough to look back over a month. */
const val MAX_TOP_THREE_DAYS = 31

const val MAX_TOP_THREE_ITEM_LENGTH = 120

/** The most important things for the next day, written on the evening of [epochDay]. */
@Serializable
data class TopThree(
    val epochDay: Long,
    /** In order; blanks removed, at most three. */
    val items: List<String> = emptyList(),
    val atMillis: Long = 0L
)

@Serializable
data class TopThrees(val evenings: List<TopThree> = emptyList())

/** Pure: [typed] as stored: trimmed, blanks dropped, at most three. */
fun cleanTopThree(typed: List<String>): List<String> =
    typed.map { it.trim().take(MAX_TOP_THREE_ITEM_LENGTH) }.filter { it.isNotEmpty() }.take(3)

/** Pure: [all] with [evening] in place of any earlier one for its day, newest first. */
fun withTopThree(all: TopThrees, evening: TopThree): TopThrees = TopThrees(
    (all.evenings.filterNot { it.epochDay == evening.epochDay } + evening)
        .sortedByDescending { it.epochDay }
        .take(MAX_TOP_THREE_DAYS)
)

/** Pure: the evening written last (by [TopThree.atMillis]), or null. */
fun newestTopThree(all: TopThrees): TopThree? = all.evenings.maxByOrNull { it.atMillis }

/** Pure: what was written the evening before [day], or empty. */
fun topThreeFor(all: TopThrees, day: Long): List<String> =
    all.evenings.firstOrNull { it.epochDay == day - 1 }?.items.orEmpty()

/** Pure: what was written on the evening of [day] itself (for the day after), or empty. */
fun topThreeWrittenOn(all: TopThrees, day: Long): List<String> =
    all.evenings.firstOrNull { it.epochDay == day }?.items.orEmpty()

/**
 * Pure: the evening a list written now belongs to. Past midnight and before
 * the night wall's end it is still last evening's: written at 00:15 it is for
 * the day that has just begun, not the one after.
 */
fun topThreeEveningDay(today: Long, minuteOfDay: Int): Long =
    if (minuteOfDay < com.ogenrwotdaniel.wakeproof.distraction.SLEEP_END_MINUTES) today - 1 else today

/**
 * Pure: what a home screen shows on [day]: the list for today under "Today's
 * top three", and the one written this evening under "Tomorrow's top three".
 * Empty when neither is written.
 */
fun topThreeShown(all: TopThrees, day: Long): List<Pair<String, List<String>>> = listOfNotNull(
    topThreeFor(all, day).takeIf { it.isNotEmpty() }?.let { "Today's top three" to it },
    topThreeWrittenOn(all, day).takeIf { it.isNotEmpty() }?.let { "Tomorrow's top three" to it }
)

/** Pure: the evening has come: from [TOP_THREE_MINUTES] to midnight the list is asked for. */
fun topThreeEvening(minuteOfDay: Int): Boolean = minuteOfDay >= TOP_THREE_MINUTES

/**
 * Tomorrow's top three (2026-11-13, the user: "the idea of writing down 3
 * important things for the next day was great ... bring it back but place it
 * at 7:50pm, 10 minutes to bedtime"). The part of the removed evening review
 * that was kept: no judging of the day, a fixed 19:50 alarm on the ringing
 * device (`StrictAlarm`, silenced by saving at least one line) and three lines
 * that wait on Home the next day.
 *
 * Kept in this device's private storage and, with Cloud Sync on, carried to the
 * user's other devices in the shared settings document (the newest evening
 * only; `SyncEngine.syncTopThree`). Its own file name: the old
 * `day_reviews.json` is one [deleteRetiredFiles] removes.
 */
@Singleton
class TopThreeStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFile(
        file = { File(context.filesDir, "top_three.json") },
        serializer = TopThrees.serializer(),
        empty = TopThrees(),
        readable = { context.getSystemService(android.os.UserManager::class.java).isUserUnlocked }
    )

    val evenings: StateFlow<TopThrees> = store.value

    suspend fun load(): TopThrees = store.read()

    suspend fun save(evening: TopThree) {
        store.update { withTopThree(it, evening) }
    }

    /** The evening written last, on any device; null when none. What sync compares. */
    suspend fun newest(): TopThree? = newestTopThree(load())
}
