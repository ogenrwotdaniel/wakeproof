package com.ogenrwotdaniel.wakeproof.verify.water

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * An image as packed `0xRRGGBB` pixels, row-major. Deliberately not a `Bitmap`:
 * everything below is arithmetic, so keeping the input a plain array is what
 * lets the detector be unit-tested on the JVM with no device and no camera.
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size >= width * height) {
            "pixels ${pixels.size} too small for ${width}x$height"
        }
    }

    fun red(x: Int, y: Int): Int = (pixels[y * width + x] shr 16) and 0xFF
    fun green(x: Int, y: Int): Int = (pixels[y * width + x] shr 8) and 0xFF
    fun blue(x: Int, y: Int): Int = pixels[y * width + x] and 0xFF
}

/** Where the water line is in one photo, and how sure we are. */
data class WaterLineObservation(
    /** Row of the line, normalised 0..1 down the analysed column. */
    val line: Double,
    /** `1 - line`: 0.0 empty, 1.0 full to the brim. */
    val fill: Double,
    /** Across-column coherence t at the chosen row. */
    val confidence: Double,
    /** [confidence] cleared the floor — this is what `levelReadable` carries. */
    val readable: Boolean,
    /** Signed gradient at the line; negative when the water side is darker. */
    val polarity: Double
)

/**
 * Finds the water line in a photo of a translucent bottle, on-device, with no
 * model and no network.
 *
 * A direct port of `tools/water/waterline/detect.py`, which is where this was
 * developed and where its regression tests live. **Keep the two in step**: the
 * Python harness is the only thing that can measure this against a corpus of
 * photographs, so a tuning change made here and not there is a change nothing
 * can evaluate.
 *
 * ### The method, and the assumption that failed
 *
 * `docs/OFFLINE_WATER.md` originally proposed collapsing the bottle to a row
 * profile and taking the largest step. That was measured and rejected: averaging
 * across the bottle's width destroys the one cue that identifies a water line. A
 * blotchy background seen through a clear bottle produces row-profile steps
 * *larger* than the water line's own, and real bathrooms are full of them — a
 * tiled wall meeting paint, a countertop edge, a shadow gradient.
 *
 * What separates them is geometry, not photometry: **a water line is horizontal
 * across the whole bottle, because gravity makes it so.** So the primary
 * detector asks, per row, whether the vertical gradient *agrees across the
 * columns*, as an across-column t-statistic. Switching to it cut fill error 11×
 * on the synthetic corpus (MAE 0.043 → 0.0039).
 *
 * Three cues, each covering the others' failure:
 * 1. [coherenceCurve] — is there a horizontal edge here (the gravity cue).
 * 2. [suppressBands] — an opaque label is a *band*, two opposite-signed edges a
 *    short distance apart; water is a *half-space*, one edge with water below it.
 * 3. [welchTCurve] — the global two-region split, kept as a multiplicative gate.
 *
 * Confidence is the coherence t itself, so a weak peak means *I cannot see the
 * line* — the honest answer for an opaque steel flask, and exactly what
 * `levelReadable = false` is supposed to carry.
 *
 * ### Scale matters
 *
 * [GRADIENT_SMOOTH_ROWS] and the band spans are in rows, so they only mean what
 * they were fitted to mean at the height the tuning was done: resample to
 * [ANALYSIS_HEIGHT] before calling this. [WaterImageSampler] does that.
 */
object WaterLineDetector {

    /**
     * Height every photo is resampled to before analysis. The tunables below are
     * row counts fitted at this scale; feeding in a 3000-row photograph would
     * make [GRADIENT_SMOOTH_ROWS] a tenth of the smoothing it is meant to be.
     */
    const val ANALYSIS_HEIGHT = 360

    /**
     * Fraction of the width kept when profiling. The curved walls throw specular
     * highlights that swamp the interior signal, so the outer edges are discarded
     * rather than averaged in.
     */
    const val WIDTH_KEEP = 0.70

    /**
     * A candidate row must leave this fraction of the column on each side, which
     * is what bounds the detectable range: a line within this margin of the top
     * or bottom — a brim-full or nearly empty bottle — cannot be found.
     */
    const val MIN_SIDE_FRACTION = 0.03

    /** Vertical smoothing of the gradient field; matches the meniscus scale. */
    const val GRADIENT_SMOOTH_ROWS = 3

    /** Vertical box-blur radius on each row profile, for the global gate. */
    const val PROFILE_SMOOTH_RADIUS = 2

    /** An opposite-signed edge this far away (as a fraction of height) is a label. */
    const val BAND_MIN_SPAN = 0.015
    const val BAND_MAX_SPAN = 0.30

    /** How strong that partner must be, relative to the candidate, to convict. */
    const val BAND_PARTNER_FRACTION = 0.55

    /**
     * What a band verdict costs. Damped rather than zeroed: a real line that
     * happens to sit near a label edge should lose the argmax only if something
     * better exists, not be deleted outright.
     */
    const val BAND_DAMPING = 0.25

    /**
     * Coherence t below this means no visible line. Recall on the reject class
     * matters more than regression error: a wrong *number* only miscredits some
     * millilitres against a target with a 50 ml tolerance, while a wrong
     * *readable* silently accepts noise as a drink.
     */
    const val CONFIDENCE_FLOOR = 8.0

    /** Horizontal extent to profile. Deliberately dumb — the capture UI frames it. */
    fun bottleColumns(width: Int): IntRange {
        val margin = (width * (1.0 - WIDTH_KEEP) / 2.0).roundToInt()
        val left = min(margin, max(0, width / 2 - 1))
        val right = max(width - margin, left + 1)
        return left until right
    }

    fun detect(image: RgbImage, confidenceFloor: Double = CONFIDENCE_FLOOR): WaterLineObservation {
        val columns = bottleColumns(image.width)
        val height = image.height
        val cropWidth = columns.last - columns.first + 1
        if (height < 8 || cropWidth < 2) {
            return WaterLineObservation(0.0, 0.0, 0.0, readable = false, polarity = 0.0)
        }

        val planes = features(image, columns)
        val minSide = max(1, (height * MIN_SIDE_FRACTION).roundToInt())

        // 1. Primary: coherent horizontal edges (the gravity cue).
        val signed = coherenceCurve(planes.lum, height, cropWidth, minSide)
        // 2. Damp label bands.
        val score = suppressBands(signed, height)
        // 3. Gate on the global two-region split, pooled over all three features.
        //    Raw t values are summed, NOT each normalised to its own peak: t is
        //    already scale-free, so its magnitude IS the evidence weight, and
        //    normalising first gives an uninformative feature an equal vote.
        val gate = DoubleArray(height)
        for (plane in listOf(planes.lum, planes.sat, planes.detail)) {
            val profile = boxBlurRows(rowMeans(plane, height, cropWidth), PROFILE_SMOOTH_RADIUS)
            val welch = welchTCurve(normalise(profile), minSide)
            for (y in 0 until height) gate[y] += welch[y]
        }
        val normalisedGate = normalise(gate)
        for (y in 0 until height) score[y] *= normalisedGate[y]

        var lineRow = 0
        for (y in 1 until height) if (score[y] > score[lineRow]) lineRow = y

        val line = lineRow.toDouble() / max(1, height - 1)
        // Confidence is the coherence t itself, not the gated product, so it
        // stays an interpretable statistic rather than an arbitrary score.
        val confidence = abs(signed[lineRow])
        return WaterLineObservation(
            line = line,
            // The crop IS the water column, so top = 0 and bottom = 1 by definition.
            fill = (1.0 - line).coerceIn(0.0, 1.0),
            confidence = confidence,
            readable = confidence >= confidenceFloor,
            polarity = signed[lineRow]
        )
    }

    /**
     * Three per-pixel feature planes, each roughly 0..1, chosen because they
     * fail in *different* ways.
     *
     * `lum` — water attenuates what is behind it; strongest on a clear bottle,
     * destroyed by a dark or coloured body. `sat` — survives the brightness
     * changes that break `lum`; confused by a coloured label. `detail` — the
     * water column refracts, so background detail seen *through* water is
     * smeared while detail through the air half stays sharp. That is a physical
     * asymmetry rather than a photometric accident, and it does not care whether
     * water reads darker or lighter; printed text defeats it.
     */
    internal fun features(image: RgbImage, columns: IntRange): FeaturePlanes {
        val height = image.height
        val width = columns.last - columns.first + 1
        val lum = DoubleArray(height * width)
        val sat = DoubleArray(height * width)
        val detail = DoubleArray(height * width)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val r = image.red(columns.first + x, y) / 255.0
                val g = image.green(columns.first + x, y) / 255.0
                val b = image.blue(columns.first + x, y) / 255.0
                lum[row + x] = 0.299 * r + 0.587 * g + 0.114 * b
                sat[row + x] = max(r, max(g, b)) - min(r, min(g, b))
            }
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 1 until width - 1) {
                detail[row + x] = abs(lum[row + x + 1] - lum[row + x - 1]) * 0.5
            }
            if (width > 1) {
                detail[row] = abs(lum[row + 1] - lum[row])
                detail[row + width - 1] = abs(lum[row + width - 1] - lum[row + width - 2])
            }
        }
        return FeaturePlanes(lum, sat, detail)
    }

    internal class FeaturePlanes(
        val lum: DoubleArray,
        val sat: DoubleArray,
        val detail: DoubleArray
    )

    /**
     * Signed across-column t-statistic for a coherent horizontal edge per row:
     * `t[y] = mean_x(g[y]) · √W / std_x(g[y])`.
     *
     * A water line is horizontal by gravity, so every column darkens together:
     * the mean is large and the spread small. Arbitrary background structure
     * crossing the bottle disagrees between columns, so the mean cancels against
     * a large spread and t stays small.
     *
     * The sign is preserved — it says which side is darker, which
     * [suppressBands] needs and which is a free same-bottle cue.
     */
    internal fun coherenceCurve(
        lum: DoubleArray,
        height: Int,
        width: Int,
        minSide: Int
    ): DoubleArray {
        val gradient = DoubleArray(height * width)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 0 until width) {
                gradient[row + x] = lum[(y + 1) * width + x] - lum[(y - 1) * width + x]
            }
        }
        val smoothed = boxBlurRows2d(gradient, height, width, GRADIENT_SMOOTH_ROWS)

        val t = DoubleArray(height)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0.0
            var sumSquares = 0.0
            for (x in 0 until width) {
                val value = smoothed[row + x]
                sum += value
                sumSquares += value * value
            }
            val mean = sum / width
            // Population variance, matching numpy's default ddof = 0.
            val spread = sqrt(max(0.0, sumSquares / width - mean * mean))
            t[y] = mean * sqrt(width.toDouble()) / (spread + 1e-9)
        }
        // Rows too close to either end cannot host a line: no room for a region.
        // The valid band is [minSide, height - minSide] INCLUSIVE at both ends,
        // matching welchTCurve exactly — the two are multiplied together, so a
        // mismatch would silently make one of them the real bound.
        for (y in 0 until min(minSide, height)) t[y] = 0.0
        for (y in max(0, height - minSide + 1) until height) t[y] = 0.0
        return t
    }

    /**
     * Welch t for splitting a profile above/below each row — the global gate.
     *
     * Element `k` compares rows `[0, k)` against `[k, n)`, in O(n) via prefix
     * sums. The `E[x²] − E[x]²` variance shortcut is only safe because the
     * profile is normalised to ~0..1 before it gets here; keep that invariant or
     * the subtraction loses precision.
     */
    internal fun welchTCurve(profile: DoubleArray, minSide: Int): DoubleArray {
        val n = profile.size
        val out = DoubleArray(n)
        if (n < 2 * minSide || minSide < 1) return out

        val sums = DoubleArray(n + 1)
        val squares = DoubleArray(n + 1)
        for (i in 0 until n) {
            sums[i + 1] = sums[i] + profile[i]
            squares[i + 1] = squares[i] + profile[i] * profile[i]
        }
        for (k in minSide..n - minSide) {
            val n1 = k.toDouble()
            val n2 = (n - k).toDouble()
            val m1 = sums[k] / n1
            val m2 = (sums[n] - sums[k]) / n2
            // Clamped at 0: the shortcut can go a hair negative on a constant run.
            val v1 = max(0.0, squares[k] / n1 - m1 * m1)
            val v2 = max(0.0, (squares[n] - squares[k]) / n2 - m2 * m2)
            out[k] = abs(m1 - m2) / sqrt(v1 / n1 + v2 / n2 + 1e-12)
        }
        return out
    }

    /**
     * Damps coherent edges that are one side of a band, returning magnitudes.
     *
     * An opaque label is a band: a step onto it and an opposite step off it, a
     * short distance apart. A water line is a half-space boundary — one step,
     * with water continuing to the bottle's bottom. So an edge with a comparable
     * opposite-signed partner within a label's thickness is probably a label.
     *
     * Searching BOTH directions is load-bearing. Looking only downward misses
     * the case that actually bit: locking onto a label's *bottom* edge, whose
     * partner is above it. Fixing that took the synthetic label pass-rate from
     * 80 % to 90 % at no cost to clean scenes.
     */
    internal fun suppressBands(signed: DoubleArray, height: Int): DoubleArray {
        val n = signed.size
        val out = DoubleArray(n) { abs(signed[it]) }
        val near = max(1, (height * BAND_MIN_SPAN).toInt())
        val far = max(near + 1, (height * BAND_MAX_SPAN).toInt())

        for (y in 0 until n) {
            if (out[y] <= 0.0) continue
            val wanted = BAND_PARTNER_FRACTION * abs(signed[y])
            val opposite = -sign(signed[y])
            val below = (y + near) until min(n, y + far)
            val above = max(0, y - far) until max(0, y - near)
            for (segment in listOf(below, above)) {
                var best = Double.NEGATIVE_INFINITY
                for (i in segment) best = max(best, opposite * signed[i])
                if (best >= wanted) {
                    out[y] *= BAND_DAMPING
                    break
                }
            }
        }
        return out
    }

    /** Vertical moving average over a 1-D array, with replicated edges. */
    internal fun boxBlurRows(values: DoubleArray, radius: Int): DoubleArray {
        if (radius <= 0) return values
        val n = values.size
        val out = DoubleArray(n)
        for (y in 0 until n) {
            var sum = 0.0
            for (d in -radius..radius) sum += values[(y + d).coerceIn(0, n - 1)]
            out[y] = sum / (2 * radius + 1)
        }
        return out
    }

    /** The same, per column, over a row-major 2-D array. */
    internal fun boxBlurRows2d(
        values: DoubleArray,
        height: Int,
        width: Int,
        radius: Int
    ): DoubleArray {
        if (radius <= 0) return values
        val out = DoubleArray(height * width)
        val window = 2 * radius + 1
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0.0
                for (d in -radius..radius) {
                    sum += values[(y + d).coerceIn(0, height - 1) * width + x]
                }
                out[y * width + x] = sum / window
            }
        }
        return out
    }

    internal fun rowMeans(plane: DoubleArray, height: Int, width: Int): DoubleArray {
        val out = DoubleArray(height)
        for (y in 0 until height) {
            var sum = 0.0
            val row = y * width
            for (x in 0 until width) sum += plane[row + x]
            out[y] = sum / width
        }
        return out
    }

    /** Rescale to 0..1 by observed range, so features are comparable. */
    internal fun normalise(profile: DoubleArray): DoubleArray {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (value in profile) {
            lo = min(lo, value)
            hi = max(hi, value)
        }
        if (hi - lo < 1e-6) return DoubleArray(profile.size)
        return DoubleArray(profile.size) { (profile[it] - lo) / (hi - lo) }
    }
}
