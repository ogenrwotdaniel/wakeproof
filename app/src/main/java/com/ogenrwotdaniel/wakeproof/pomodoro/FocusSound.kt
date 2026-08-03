package com.ogenrwotdaniel.wakeproof.pomodoro

import androidx.annotation.RawRes
import com.ogenrwotdaniel.wakeproof.R

/**
 * Bundled ambient study sounds (generated assets, seamless loops). [gain]
 * compensates for the different crest factors of the loops so switching
 * sounds keeps a consistent perceived loudness.
 */
enum class FocusSound(
    val id: String,
    val label: String,
    @param:RawRes val res: Int,
    val gain: Float = 1f
) {
    AMBIENT("focus_ambient", "Ambient tones", R.raw.focus_ambient),
    RAIN("night_rain", "Night rain", R.raw.night_rain, gain = 1.5f),
    FIREPLACE("fireplace", "Cozy fireplace", R.raw.fireplace, gain = 1.3f),
    OCEAN("ocean_waves", "Ocean waves", R.raw.ocean_waves, gain = 1.6f),
    DEEP("deep_noise", "Deep noise", R.raw.deep_noise, gain = 1.2f);

    companion object {
        /**
         * Sentinel sound id meaning "Spotify is the study sound". Stored in the
         * same setting as the bundled loops so the choice is exclusive: picking
         * Spotify silences the loops instead of stacking a second track on top.
         */
        const val SPOTIFY_ID = "spotify"

        /** null when [id] selects Spotify (an outside player) over a bundled loop. */
        fun fromId(id: String?): FocusSound? =
            if (id == SPOTIFY_ID) null else entries.firstOrNull { it.id == id } ?: AMBIENT
    }
}
