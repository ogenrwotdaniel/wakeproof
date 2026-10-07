package com.ogenrwotdaniel.wakeproof.pomodoro

/**
 * The tree a focus block grows, Forest-style: a seedling at the start that
 * fills out as the block runs, and withers if the user leaves for a distracting
 * app (see `PomodoroTimerService.guardTree`).
 *
 * Emoji rather than artwork: the app ships no illustration set, and these read
 * at any size in both themes.
 */
object FocusTree {
    const val WITHERED = "🥀"

    /** The tree while a block is running; [progress] is 0..1 through the block. */
    fun growing(progress: Float, withered: Boolean): String = when {
        withered -> WITHERED
        progress < 0.2f -> "🌱"
        progress < 0.55f -> "🌿"
        progress < 0.9f -> "🪴"
        else -> "🌳"
    }

    /**
     * The tree a finished block leaves in the forest. Longer blocks grow bigger
     * trees, so a day of 50-minute blocks looks different from a day of 15s.
     */
    fun grown(minutes: Int, withered: Boolean): String = when {
        withered -> WITHERED
        minutes < 20 -> "🌿"
        minutes < 45 -> "🌳"
        else -> "🌲"
    }
}
