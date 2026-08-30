package com.ogenrwotdaniel.wakeproof.verify.water

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Whether two photos show the same bottle, and how strongly (0..1). */
data class ContainerMatch(val same: Boolean, val score: Double)

/**
 * The cheap classic same-bottle check `docs/OFFLINE_WATER.md` asks for, ported
 * from `tools/water/waterline/detect.py`. It is not meant to be clever: the job
 * is rejecting "a different bottle" while tolerating "same bottle, moved 10 cm
 * and photographed under a different lamp".
 *
 * ### Why it compares bands rather than whole images
 *
 * Comparing the two crops wholesale is defeated by the very thing being
 * measured. Drinking converts a band of the image from water to air, so an
 * honest before/after pair of the SAME bottle scores as two different ones. But
 * the rows above the higher line are air in both photos, and the rows below the
 * lower line are water in both — those two bands are directly comparable, and
 * everything that changed lies between them. See [sharedBands].
 *
 * ### Why the colour cue is chromaticity
 *
 * A raw RGB histogram failed the case that matters. Scaling every channel by the
 * same factor is exactly what switching a lamp on does, and it moves raw RGB
 * into different bins. Measured on synthetic scenes: a 40 % brightness drop
 * between the two shots scored the same bottle at 0.53 against a 0.55 floor — a
 * rejection, at 06:00, for turning a light off. Chromaticity (`r/(r+g+b)`,
 * `g/(r+g+b)`) is invariant to it by construction, and the same case then scored
 * 0.98.
 */
object ContainerMatcher {

    /** Bins per chromaticity axis. 12 → 144 bins, plus one for near-black. */
    const val SIGNATURE_BINS = 12

    /** Channel sum (of a possible 765) below which chromaticity is meaningless. */
    const val SIGNATURE_DARK_SUM = 24.0

    const val SIGNATURE_LENGTH = SIGNATURE_BINS * SIGNATURE_BINS + 1

    /**
     * Rows dropped at each end of a shared band. The meniscus and the detected
     * line both carry a few rows of slop, and including them would compare water
     * against air.
     */
    const val BAND_MARGIN_FRACTION = 0.04

    /**
     * Weight of the colour term against the wall-profile term. Even, because
     * they fail differently: colour is defeated by a change of lighting, the
     * profile by a bottle photographed from a different distance.
     */
    const val SIGNATURE_WEIGHT = 0.5

    /**
     * Below this, a different bottle. Deliberately permissive: a false "different
     * bottle" is a rejection the user sees at 06:00, while a missed one only means
     * someone swapped bottles mid-drink — which earns them nothing, since only
     * *drops* are ever credited.
     *
     * Measured on synthetic pairs: same-bottle scores ≥ 0.99, swapped ≤ 0.63.
     * Real photographs put both bottles in one room under one lamp, so that gap
     * will narrow; the floor sits low in it on purpose.
     */
    const val FLOOR = 0.55

    /**
     * The row ranges showing the same thing in both photos: air above the higher
     * line, water below the lower one.
     */
    fun sharedBands(beforeLine: Double, afterLine: Double, height: Int): Pair<IntRange, IntRange> {
        val margin = max(1, (height * BAND_MARGIN_FRACTION).roundToInt())
        val top = (min(beforeLine, afterLine) * (height - 1)).roundToInt()
        val bottom = (max(beforeLine, afterLine) * (height - 1)).roundToInt()
        val air = 0 until max(0, top - margin)
        val water = min(height, bottom + margin) until height
        return air to water
    }

    /**
     * Normalised chromaticity histogram of one band of one image.
     *
     * Blue carries no extra information once the other two are normalised (the
     * three sum to one), so the histogram is 2-D. Near-black pixels have no
     * meaningful chromaticity and go in their own bin instead of being scattered
     * across the plane by sensor noise.
     */
    fun colourSignature(image: RgbImage, rows: IntRange, columns: IntRange): DoubleArray {
        val histogram = DoubleArray(SIGNATURE_LENGTH)
        var count = 0
        for (y in rows) {
            for (x in columns) {
                val r = image.red(x, y)
                val g = image.green(x, y)
                val total = (r + g + image.blue(x, y)).toDouble()
                val bin = if (total < SIGNATURE_DARK_SUM) {
                    SIGNATURE_BINS * SIGNATURE_BINS
                } else {
                    val ri = ((r / total) * SIGNATURE_BINS).toInt().coerceIn(0, SIGNATURE_BINS - 1)
                    val gi = ((g / total) * SIGNATURE_BINS).toInt().coerceIn(0, SIGNATURE_BINS - 1)
                    ri * SIGNATURE_BINS + gi
                }
                histogram[bin]++
                count++
            }
        }
        if (count > 0) for (i in histogram.indices) histogram[i] /= count
        return histogram
    }

    /** Histogram intersection, 0..1. Identical distributions give 1.0. */
    fun signatureSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var sum = 0.0
        for (i in a.indices) sum += min(a[i], b[i])
        return sum
    }

    /**
     * Per-column mean luminance across a band — the bottle's wall signature.
     *
     * The curved walls throw specular highlights down both edges, so this is a
     * cheap stand-in for the silhouette: it says how wide the bottle is and where
     * its edges fall, without segmenting anything.
     */
    fun wallProfile(image: RgbImage, rows: IntRange, columns: IntRange): DoubleArray {
        val count = rows.last - rows.first + 1
        if (count <= 0) return DoubleArray(0)
        val profile = DoubleArray(columns.last - columns.first + 1)
        for (y in rows) {
            for ((index, x) in columns.withIndex()) {
                profile[index] += (0.299 * image.red(x, y) +
                    0.587 * image.green(x, y) +
                    0.114 * image.blue(x, y)) / 255.0
            }
        }
        for (i in profile.indices) profile[i] /= count
        return profile
    }

    /**
     * Pearson correlation of two wall profiles, clipped to 0..1.
     *
     * Correlation rather than a distance because it is invariant to the overall
     * brightness change between two photographs of the same bottle, which is
     * exactly the nuisance variable here. Two featureless profiles correlate
     * trivially, which is no evidence either way, so that scores zero.
     */
    fun profileCorrelation(a: DoubleArray, b: DoubleArray): Double {
        if (a.size < 2 || a.size != b.size) return 0.0
        val meanA = a.average()
        val meanB = b.average()
        var covariance = 0.0
        var varianceA = 0.0
        var varianceB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            covariance += da * db
            varianceA += da * da
            varianceB += db * db
        }
        val denominator = sqrt(varianceA * varianceB)
        if (denominator < 1e-9) return 0.0
        return (covariance / denominator).coerceIn(0.0, 1.0)
    }

    /**
     * Is this the same bottle in both photos?
     *
     * A band that comes out empty — a nearly full or nearly empty bottle leaves
     * no air or no water band — drops out of the average rather than scoring
     * zero, which would convict every brim-full bottle of being a different one.
     */
    fun match(
        before: RgbImage,
        after: RgbImage,
        beforeLine: Double,
        afterLine: Double
    ): ContainerMatch {
        if (before.width != after.width || before.height != after.height) {
            return ContainerMatch(same = false, score = 0.0)
        }
        val columns = WaterLineDetector.bottleColumns(before.width)
        val (air, water) = sharedBands(beforeLine, afterLine, before.height)

        var total = 0.0
        var bands = 0
        for (band in listOf(air, water)) {
            if (band.last - band.first + 1 < 2) continue
            val colour = signatureSimilarity(
                colourSignature(before, band, columns),
                colourSignature(after, band, columns)
            )
            val profile = profileCorrelation(
                wallProfile(before, band, columns),
                wallProfile(after, band, columns)
            )
            total += SIGNATURE_WEIGHT * colour + (1.0 - SIGNATURE_WEIGHT) * profile
            bands++
        }
        // Nothing comparable at all: the two lines sit on top of each other and
        // the bottle fills the frame. Say yes — there is no evidence of a swap,
        // and the "no measurable drop" rule rejects the pair anyway.
        if (bands == 0) return ContainerMatch(same = true, score = 1.0)
        val score = total / bands
        return ContainerMatch(same = score >= FLOOR, score = score)
    }
}
