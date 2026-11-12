package com.ogenrwotdaniel.wakeproof.verify

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the post-verification line on-device, with no key, no network and no
 * wait. A language model adds nothing here — the whole message is one sentence
 * about a number the app already knows — while costing a round trip at 5 AM on
 * whatever connection the bedroom has.
 *
 * Lines are graded by size of the effort, so a 5-rep morning and a 40-rep
 * morning don't get the same congratulation, and rotate by date rather than at
 * random: the same day always reads the same, and a given line cannot come back
 * until the whole tier has been through, which is what stops canned text
 * feeling canned.
 */
@Singleton
class OfflineMotivation @Inject constructor() : MotivationSource {

    override suspend fun motivationalMessage(achievement: Achievement): String =
        lineFor(achievement, LocalDate.now().toEpochDay())

    /**
     * Pure form for tests: [rotation] is any steadily-increasing counter (the
     * caller passes the epoch day) and selects which line of the matching tier
     * to use.
     */
    fun lineFor(achievement: Achievement, rotation: Long): String {
        val pool = when (achievement) {
            is Achievement.Pushups -> when {
                achievement.reps < 10 -> PUSHUPS_FEW
                achievement.reps < 25 -> PUSHUPS_SOLID
                else -> PUSHUPS_STRONG
            }
        }
        // floorMod, so a negative epoch day (a device clock set before 1970)
        // still lands inside the pool instead of throwing.
        val line = pool[Math.floorMod(rotation, pool.size.toLong()).toInt()]
        val amount = when (achievement) {
            is Achievement.Pushups -> achievement.reps.toString()
        }
        return line.replace(AMOUNT, amount)
    }

    private companion object {
        /** Placeholder the pools use for the rep count. */
        const val AMOUNT = "{n}"

        val PUSHUPS_FEW = listOf(
            "$AMOUNT done before the day even started. That's the hard part behind you.",
            "$AMOUNT reps, banked. A small number still beats snoozing.",
            "You got up and did $AMOUNT. The streak counts that you showed up.",
            "$AMOUNT in the bag. Momentum starts exactly here.",
            "$AMOUNT reps done cold. The alarm lost.",
            "That's $AMOUNT more than not getting up would have managed."
        )

        val PUSHUPS_SOLID = listOf(
            "$AMOUNT before sunrise. Most people are still negotiating with their alarm.",
            "$AMOUNT reps, done and verified. Solid start.",
            "That's $AMOUNT. You've already won something today.",
            "$AMOUNT done cold, first thing. That's real discipline.",
            "$AMOUNT in. The rest of the day is downhill from here.",
            "$AMOUNT reps before coffee. Respect."
        )

        val PUSHUPS_STRONG = listOf(
            "$AMOUNT. That's not a warm-up, that's a workout.",
            "$AMOUNT reps before the day started. Genuinely strong.",
            "$AMOUNT done. You're not just awake, you're ahead.",
            "$AMOUNT at this hour is serious. Take the win.",
            "$AMOUNT reps, verified. That's a day most people won't have by noon.",
            "$AMOUNT. Whatever comes next, you've already outworked yesterday."
        )
    }
}
