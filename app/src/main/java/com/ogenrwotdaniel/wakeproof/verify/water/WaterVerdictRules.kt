package com.ogenrwotdaniel.wakeproof.verify.water

import com.ogenrwotdaniel.wakeproof.data.WaterVerdict

/**
 * Turns six *observations* about a pair of bottle photos into the *verdict*:
 * how many millilitres to credit, and whether to credit them at all.
 *
 * The seam matters more than the arithmetic. Whoever produced the observations —
 * the cloud model or [OnDeviceWaterObserver] — never decides anything, so
 * swapping one for the other cannot change the policy, and the policy can be
 * unit-tested with no image, no camera and no network. It is also why going
 * offline was a small job rather than a rewrite: only one producer of six
 * numbers had to be replaced.
 */
object WaterVerdictRules {

    /** Credited amounts are floored to this, so no millilitre is ever invented. */
    const val ROUND_TO_ML = 10

    /** Below this the level difference is reading noise, not a drink. */
    const val MIN_CONSUMED_ML = 30

    /** …and the same guard in fill-fraction terms. */
    const val MIN_FILL_DROP = 0.05

    /** Guards against a nonsense capacity making the arithmetic degenerate. */
    const val MIN_BOTTLE_CAPACITY_ML = 100

    /**
     * How far the level may rise before it counts as a refill rather than
     * measurement noise. A refill is arithmetic, not perception: the level going
     * *up* is the definition of one, and it never needed a model.
     */
    const val REFILL_TOLERANCE = 0.02

    /**
     * Fills in [WaterVerdict.consumedMl] and [WaterVerdict.accepted].
     *
     * A pair is accepted when it is the same bottle, the water line is readable
     * in both shots, nothing was refilled, and the level fell far enough to be a
     * real gulp. It does NOT have to cover the whole per-alarm target — the
     * caller keeps asking for more photos until the accepted amounts add up,
     * which is what makes a single mouthful count instead of being rejected.
     */
    fun decide(observation: WaterVerdict, bottleCapacityMl: Int): WaterVerdict {
        val capacity = bottleCapacityMl.coerceAtLeast(MIN_BOTTLE_CAPACITY_ML)
        val fillDrop = observation.beforeFill.coerceIn(0.0, 1.0) -
            observation.afterFill.coerceIn(0.0, 1.0)
        val scaleDrop = observation.beforeLevelMl - observation.afterLevelMl
        // Printed marks win when legible and consistent; otherwise the drop in
        // fill fraction against the declared capacity.
        val measured =
            if (observation.scaleMlReadable && scaleDrop > 0) scaleDrop
            else (fillDrop * capacity).toInt()
        // Floor to ROUND_TO_ML: under-crediting is fairer than inventing millilitres.
        val consumed = (measured / ROUND_TO_ML * ROUND_TO_ML).coerceIn(0, capacity)
        return observation.copy(
            consumedMl = consumed,
            accepted = observation.sameContainer &&
                observation.levelReadable &&
                !observation.refilled &&
                consumed >= MIN_CONSUMED_ML &&
                (observation.scaleMlReadable && scaleDrop > 0 || fillDrop >= MIN_FILL_DROP)
        )
    }
}
