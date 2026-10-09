package com.ogenrwotdaniel.wakeproof.ui.home

import android.app.Activity
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.tasks.await

/** Streak length at which the app asks for a Play review. */
const val REVIEW_STREAK_DAYS = 3

/**
 * Whether now is the moment to ask for a review: once per install, and only
 * after [REVIEW_STREAK_DAYS] days in a row of hitting the focus goal.
 *
 * Timing is the whole game with ratings. Asked on day one, people rate a promise;
 * asked straight after a rejected water photo, they rate a grudge. Three met days
 * means the app has demonstrably worked for this person, and they are looking at
 * the number that proves it.
 */
fun shouldAskForReview(streakDays: Int, alreadyAsked: Boolean): Boolean =
    !alreadyAsked && streakDays >= REVIEW_STREAK_DAYS

/**
 * Shows Google Play's in-app review sheet. Play decides whether it actually
 * appears (it is quota-limited, and never shows for a sideloaded install), so
 * failure is silent by design — there is nothing useful to tell the user.
 */
suspend fun launchPlayReview(activity: Activity) {
    runCatching {
        val manager = ReviewManagerFactory.create(activity)
        val info = manager.requestReviewFlow().await()
        manager.launchReviewFlow(activity, info).await()
    }
}
