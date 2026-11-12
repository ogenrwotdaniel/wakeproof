package com.ogenrwotdaniel.wakeproof.plan

import android.content.Context
import com.ogenrwotdaniel.wakeproof.study.JsonFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** How much of the morning's plan got done, as the user judged it that evening. */
@Serializable
enum class PlanDone(val label: String) {
    ALL("All of it"), MOST("Most"), SOME("Some"), NONE("None")
}

/** One evening's shutdown review: the day judged, and tomorrow's top three. */
@Serializable
data class DayReview(
    val epochDay: Long,
    val done: PlanDone,
    /** Tomorrow's most important things, in order; blanks removed, at most three. */
    val topThree: List<String> = emptyList(),
    val atMillis: Long = 0L
)

@Serializable
data class DayReviews(val reviews: List<DayReview> = emptyList())

/** Reviews kept; a year and a bit. */
const val MAX_DAY_REVIEWS = 400

/** Pure: [typed] as stored: trimmed, blanks dropped, at most three. */
fun cleanTopThree(typed: List<String>): List<String> =
    typed.map { it.trim() }.filter { it.isNotEmpty() }.take(3)

/** Pure: [all] with [review] in place of any earlier one for its day, newest day first. */
fun withReview(all: DayReviews, review: DayReview): DayReviews = DayReviews(
    (all.reviews.filterNot { it.epochDay == review.epochDay } + review)
        .sortedByDescending { it.epochDay }
        .take(MAX_DAY_REVIEWS)
)

/**
 * Pure: [all] with a backup's [restored] reviews merged in. A day in both takes
 * the backup's, as a restored database row does; days only here are left alone.
 */
fun withRestoredReviews(all: DayReviews, restored: List<DayReview>): DayReviews =
    restored.fold(all) { merged, review -> withReview(merged, review) }

/** Pure: the top three written the evening before [day], or empty. */
fun topThreeFor(all: DayReviews, day: Long): List<String> =
    all.reviews.firstOrNull { it.epochDay == day - 1 }?.topThree.orEmpty()

/**
 * The evening shutdown reviews (2026-11-12, the user's request: a 19:30 alarm
 * that shows the morning's plan, asks what got done and takes tomorrow's top
 * three). Kept beside the plan photos, on this device only.
 */
@Singleton
class DayReviewStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = JsonFile(
        file = { File(context.filesDir, "day_reviews.json") },
        serializer = DayReviews.serializer(),
        empty = DayReviews(),
        readable = { context.getSystemService(android.os.UserManager::class.java).isUserUnlocked }
    )

    val reviews: StateFlow<DayReviews> = store.value

    suspend fun load(): DayReviews = store.read()

    suspend fun save(review: DayReview) {
        store.update { withReview(it, review) }
    }

    /** Merges the reviews of a JSON backup into this device's. */
    suspend fun restore(reviews: List<DayReview>) {
        if (reviews.isNotEmpty()) store.update { withRestoredReviews(it, reviews) }
    }
}
