package com.ogenrwotdaniel.wakeproof.pomodoro

import androidx.annotation.RawRes
import com.ogenrwotdaniel.wakeproof.R

/**
 * The bundled ambient study loops.
 *
 * Generated assets — `tools/sounds/generate.py` renders them and that script,
 * not this file, is where a sound is changed. Two things about them matter here:
 *
 * * [id] is simultaneously the raw resource name and the value stored in the
 *   `focusSoundId` setting, so a typo falls back to [AMBIENT] silently rather
 *   than failing loudly.
 * * There is deliberately **no per-sound gain**. The generator levels every
 *   loop to the same K-weighted loudness. The five hand-tuned gains that used
 *   to live here were levelling by peak and missed by up to 5 dB, which is why
 *   switching sound used to be a jump in level.
 *
 * [description] is what the picker shows under the name; these are noise beds,
 * and "Brown noise" tells most people nothing about whether they want it.
 */
enum class FocusSound(
    val id: String,
    val label: String,
    val description: String,
    @param:RawRes val res: Int
) {
    AMBIENT(
        "focus_ambient",
        "Ambient tones",
        "A slow, wide chord with nowhere to resolve to",
        R.raw.focus_ambient
    ),
    RAIN(
        "night_rain",
        "Night rain",
        "Steady rain from indoors, with the odd close drop",
        R.raw.night_rain
    ),
    FIREPLACE(
        "fireplace",
        "Fireplace",
        "Low roar and crackles, a couple of metres away",
        R.raw.fireplace
    ),
    OCEAN(
        "ocean_waves",
        "Ocean waves",
        "Swells that brighten as they break, then withdraw",
        R.raw.ocean_waves
    ),
    WIND(
        "forest_wind",
        "Wind in the pines",
        "Gusts hissing through needles — movement without events",
        R.raw.forest_wind
    ),
    DEEP(
        "deep_noise",
        "Deep noise",
        "Dark brown noise. The most neutral thing to work under",
        R.raw.deep_noise
    ),
    STATIC(
        "soft_static",
        "Soft static",
        "Pink noise with the fizz taken off. Good against chatter",
        R.raw.soft_static
    );

    companion object {
        /**
         * Sentinel sound id meaning "the user's own music is the study sound".
         * Stored in the same setting as the bundled loops so the choice is
         * exclusive: picking it silences the loops instead of stacking a second
         * track on top.
         */
        const val LIBRARY_ID = "library"

        /**
         * Spotify's old sentinel. Spotify was replaced by the in-app library
         * player in 1.5, and someone who had picked an outside music source
         * wanted music, not a rain loop — so it reads as [LIBRARY_ID].
         */
        private const val LEGACY_SPOTIFY_ID = "spotify"

        fun isLibrary(id: String?): Boolean = id == LIBRARY_ID || id == LEGACY_SPOTIFY_ID

        /** null when [id] selects the user's music library over a bundled loop. */
        fun fromId(id: String?): FocusSound? =
            if (isLibrary(id)) null else entries.firstOrNull { it.id == id } ?: AMBIENT
    }
}
