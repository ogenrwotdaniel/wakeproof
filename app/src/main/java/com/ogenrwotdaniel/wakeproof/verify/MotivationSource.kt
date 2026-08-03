package com.ogenrwotdaniel.wakeproof.verify

/**
 * What the user just proved they did. Structured rather than prose so an
 * offline generator can speak to the actual numbers instead of pattern-matching
 * a sentence someone else wrote.
 */
sealed interface Achievement {
    /** [reps] pushups counted on-device by the pose detector. */
    data class Pushups(val reps: Int) : Achievement

    /** [ml] millilitres credited across the verified before/after photo pairs. */
    data class Water(val ml: Int) : Achievement
}

/**
 * Supplies the short line shown after a completed verification. Motivation must
 * never block a dismissal, so implementations are expected to be instant and
 * always return something — there is no failure case to render.
 */
interface MotivationSource {
    suspend fun motivationalMessage(achievement: Achievement): String
}
