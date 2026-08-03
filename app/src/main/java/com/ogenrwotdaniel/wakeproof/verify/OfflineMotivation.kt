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
            is Achievement.Water -> when {
                achievement.ml < 300 -> WATER_SIP
                achievement.ml < 600 -> WATER_SOLID
                else -> WATER_STRONG
            }
        }
        // floorMod, so a negative epoch day (a device clock set before 1970)
        // still lands inside the pool instead of throwing.
        val line = pool[Math.floorMod(rotation, pool.size.toLong()).toInt()]
        val amount = when (achievement) {
            is Achievement.Pushups -> achievement.reps.toString()
            is Achievement.Water -> achievement.ml.toString()
        }
        return line.replace(AMOUNT, amount)
    }

    private companion object {
        /** Placeholder the pools use for the rep count / millilitres. */
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

        val WATER_SIP = listOf(
            "$AMOUNT ml down. Your brain runs on that.",
            "$AMOUNT ml in. Small sip, real start.",
            "$AMOUNT ml verified. Keep the bottle within reach.",
            "$AMOUNT ml before anything else. Good instinct.",
            "That's $AMOUNT ml your body was never going to get from coffee.",
            "$AMOUNT ml done. Hydration is the cheapest win there is."
        )

        val WATER_SOLID = listOf(
            "$AMOUNT ml before the day started. That's a proper opening.",
            "$AMOUNT ml in and verified. Your head will thank you by 10 AM.",
            "$AMOUNT ml done — most of the morning's job handled.",
            "$AMOUNT ml down before breakfast. Well played.",
            "$AMOUNT ml, camera-verified. No shortcuts taken.",
            "$AMOUNT ml in. Solid."
        )

        val WATER_STRONG = listOf(
            "$AMOUNT ml. That's a serious amount before sunrise.",
            "$AMOUNT ml before the day started — the whole morning target, gone.",
            "$AMOUNT ml verified. You didn't just meet it, you cleared it.",
            "$AMOUNT ml in. That is the strongest possible start.",
            "$AMOUNT ml done. Nothing half-hearted about that.",
            "$AMOUNT ml, all verified. Take the win and go."
        )
    }
}
