package com.ogenrwotdaniel.wakeproof.verify.water

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Kotlin twin of `tools/water/waterline/portable_synth.py`.
 *
 * These two renderers must produce **the same pixels** from the same seed, which
 * is what lets [WaterLineDetectorTest] assert that the Kotlin detector computes
 * the same numbers as the Python original. Property tests alone would not: a
 * porting slip — a population variance turned into a sample variance, a band
 * search that lost its upward half, an off-by-one at the min-side boundary —
 * leaves every property intact on easy scenes and quietly costs accuracy on hard
 * ones. Running the *same pixels* through both is the only test that catches it.
 *
 * Everything here is integer arithmetic and an explicit LCG for exactly that
 * reason. If you change one renderer, change the other, and regenerate the
 * expected constants with `python -m waterline.portable_synth`.
 */
internal object PortableScene {

    private const val LCG_MULTIPLIER = 1103515245L
    private const val LCG_INCREMENT = 12345L
    private const val LCG_MASK = 0x7FFFFFFFL

    /** A linear congruential generator: multiply, add, mask — identical everywhere. */
    private class Lcg(seed: Int) {
        private var state: Long = seed.toLong() and LCG_MASK

        fun nextInt(): Long {
            state = (state * LCG_MULTIPLIER + LCG_INCREMENT) and LCG_MASK
            return state
        }

        /** A double in [0, 1), from the top bits — an LCG's low bits are weak. */
        fun unit(): Double = (nextInt() shr 7).toDouble() / (1 shl 24).toDouble()
    }

    fun render(
        fill: Double,
        height: Int = 360,
        width: Int = 180,
        seed: Int = 1,
        withLabel: Boolean = false,
        opaque: Boolean = false,
        brightness: Double = 1.0,
        tint: Triple<Double, Double, Double> = Triple(1.0, 1.0, 1.0)
    ): RgbImage {
        val rng = Lcg(seed)
        val lineRow = ((1.0 - fill.coerceIn(0.0, 1.0)) * (height - 1)).roundToInt()

        // Three channel planes, kept separate so the tint can pull them apart.
        val r = DoubleArray(height * width)
        val g = DoubleArray(height * width)
        val b = DoubleArray(height * width)

        // --- blotchy background, upsampled from a coarse grid ---------------
        val coarseH = max(2, height / 24)
        val coarseW = max(2, width / 24)
        val coarse = DoubleArray(coarseH * coarseW)
        for (i in coarse.indices) coarse[i] = rng.unit()
        for (y in 0 until height) {
            val cy = min(coarseH - 1, y * coarseH / height)
            for (x in 0 until width) {
                val cx = min(coarseW - 1, x * coarseW / width)
                val value = 0.45 + 0.40 * coarse[cy * coarseW + cx]
                val i = y * width + x
                r[i] = value
                g[i] = value
                b[i] = value
            }
        }

        // --- water column: darker, and horizontally smeared ------------------
        if (lineRow < height - 1) {
            for (y in lineRow until height) {
                val row = y * width
                // Copy first: the smear must read the ORIGINAL row, or each
                // pixel blurs into the one already blurred beside it.
                val sourceR = DoubleArray(width) { r[row + it] }
                val sourceG = DoubleArray(width) { g[row + it] }
                val sourceB = DoubleArray(width) { b[row + it] }
                for (x in 0 until width) {
                    val lo = max(0, x - 3)
                    val hi = min(width - 1, x + 3)
                    val count = (hi - lo + 1).toDouble()
                    var sumR = 0.0
                    var sumG = 0.0
                    var sumB = 0.0
                    for (k in lo..hi) {
                        sumR += sourceR[k]
                        sumG += sourceG[k]
                        sumB += sourceB[k]
                    }
                    r[row + x] = sumR / count * 0.72
                    g[row + x] = sumG / count * 0.72
                    b[row + x] = sumB / count * 0.72
                }
            }
        }

        // --- meniscus: a thin high-contrast band exactly at the line ---------
        val band = max(1, height / 110)
        if (lineRow in 1 until height - 1) {
            for (y in max(0, lineRow - band) until lineRow) scale(r, g, b, y, width, 1.35)
            for (y in lineRow until min(height, lineRow + band + 1)) scale(r, g, b, y, width, 0.755)
        }

        // --- bottle walls: specular highlights down both curved edges --------
        val edge = max(2, (width * 0.10).toInt())
        for (x in 0 until edge) {
            val ramp = 0.30 * (1.0 - x / edge.toDouble())
            for (y in 0 until height) {
                val left = y * width + x
                val right = y * width + (width - 1 - x)
                r[left] += ramp; g[left] += ramp; b[left] += ramp
                r[right] += ramp; g[right] += ramp; b[right] += ramp
            }
        }

        for (i in r.indices) {
            r[i] *= tint.first
            g[i] *= tint.second
            b[i] *= tint.third
        }

        // --- opaque label: the adversarial case ------------------------------
        if (withLabel) {
            val top = (0.30 * height).toInt()
            val bottom = (0.46 * height).toInt()
            for (y in top until bottom) {
                for (x in 0 until width) set(r, g, b, y * width + x, 0.90)
            }
            var y = top + 2
            while (y < bottom - 2) {
                for (x in 0 until width) {
                    if (rng.unit() < 0.42) {
                        set(r, g, b, y * width + x, 0.10)
                        set(r, g, b, (y + 1) * width + x, 0.10)
                    }
                }
                y += 5
            }
        }

        // --- opaque body: no line exists at any price ------------------------
        if (opaque) {
            for (i in r.indices) set(r, g, b, i, 0.42)
            for (x in 0 until edge) {
                val ramp = 0.30 * (1.0 - x / edge.toDouble())
                for (y in 0 until height) {
                    val left = y * width + x
                    val right = y * width + (width - 1 - x)
                    r[left] += ramp; g[left] += ramp; b[left] += ramp
                    r[right] += ramp; g[right] += ramp; b[right] += ramp
                }
            }
        }

        for (i in r.indices) {
            r[i] *= brightness
            g[i] *= brightness
            b[i] *= brightness
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val noise = (rng.unit() - 0.5) * 0.024
                val i = y * width + x
                r[i] += noise
                g[i] += noise
                b[i] += noise
            }
        }

        val pixels = IntArray(height * width)
        for (i in pixels.indices) {
            pixels[i] = (byteOf(r[i]) shl 16) or (byteOf(g[i]) shl 8) or byteOf(b[i])
        }
        return RgbImage(width, height, pixels)
    }

    private fun scale(
        r: DoubleArray,
        g: DoubleArray,
        b: DoubleArray,
        y: Int,
        width: Int,
        factor: Double
    ) {
        for (x in 0 until width) {
            val i = y * width + x
            r[i] *= factor
            g[i] *= factor
            b[i] *= factor
        }
    }

    private fun set(r: DoubleArray, g: DoubleArray, b: DoubleArray, i: Int, value: Double) {
        r[i] = value
        g[i] = value
        b[i] = value
    }

    /** `clip(v * 255 + 0.5, 0, 255)` truncated — numpy's `astype(uint8)` rounds this way. */
    private fun byteOf(value: Double): Int =
        (value * 255.0 + 0.5).coerceIn(0.0, 255.0).toInt()
}
