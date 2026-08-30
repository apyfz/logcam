package com.apyfz.logcam.color

import kotlin.math.pow

/**
 * Converts a colour temperature into the per-channel sensor gains Camera2 expects.
 *
 * Camera2 offers only a handful of named white balance presets, which is useless for matching shots
 * or dialling a look. Continuous Kelvin control means computing what the sensor's raw channels must
 * be scaled by for a given illuminant to come out neutral, which is exactly the calculation the DNG
 * specification defines and the same data a raw converter uses.
 *
 * The device supplies two colour matrices measured under two reference illuminants — here D65 and
 * Standard Illuminant A — and the matrix for any other temperature is interpolated between them.
 */
object WhiteBalanceMath {

    /** Correlated colour temperature of the CIE standard illuminants the sensor is calibrated at. */
    const val D65_KELVIN = 6504.0
    const val STANDARD_A_KELVIN = 2856.0

    /**
     * The range worth offering: tungsten at one end, overcast daylight at the other.
     *
     * The Planckian fit stays usable far beyond this, but nothing between 6500K and 10000K
     * corresponds to light anyone shoots under, and a wider slider only makes the useful part
     * harder to hit.
     */
    const val MIN_KELVIN = 2700
    const val MAX_KELVIN = 6500

    /**
     * Chromaticity of a Planckian radiator, using Kim et al.'s cubic approximation.
     *
     * Accurate from roughly 1667K to 25000K, which comfortably covers anything worth shooting.
     */
    fun planckianXy(kelvin: Double): Pair<Double, Double> {
        val t = kelvin.coerceIn(1667.0, 25000.0)
        val invT = 1000.0 / t

        val x = if (t <= 4000.0) {
            -0.2661239 * invT.pow(3) - 0.2343589 * invT.pow(2) + 0.8776956 * invT + 0.179910
        } else {
            -3.0258469 * invT.pow(3) + 2.1070379 * invT.pow(2) + 0.2226347 * invT + 0.240390
        }

        val y = when {
            t <= 2222.0 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /**
     * Per-channel gains for [kelvin], as (red, green, blue), normalised so the smallest is 1.
     *
     * [transformD65] and [transformStdA] are the sensor's XYZ-to-camera matrices at its two
     * reference illuminants, row-major. [tint] shifts green against magenta, in the range -1..1,
     * for the part of white balance a colour temperature cannot express.
     *
     * Gains below 1 would darken a channel rather than balance it, so the result is normalised
     * upward — the exposure lost that way is recovered by the sensor gain, not by clipping.
     */
    fun gainsForKelvin(
        kelvin: Double,
        tint: Double,
        transformD65: DoubleArray,
        transformStdA: DoubleArray,
    ): DoubleArray {
        // The DNG specification interpolates between calibration illuminants in inverse colour
        // temperature, because that is the axis along which the locus behaves linearly.
        val invTarget = 1.0 / kelvin.coerceIn(MIN_KELVIN.toDouble(), MAX_KELVIN.toDouble())
        val invD65 = 1.0 / D65_KELVIN
        val invStdA = 1.0 / STANDARD_A_KELVIN
        val g = ((invTarget - invD65) / (invStdA - invD65)).coerceIn(0.0, 1.0)

        val matrix = DoubleArray(9) { transformD65[it] * (1 - g) + transformStdA[it] * g }

        val (x, y) = planckianXy(kelvin)
        // xyY to XYZ at unit luminance.
        val xyz = doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)

        val neutral = ColorProfiles.apply3x3(matrix, xyz)

        // The camera must scale each raw channel by the reciprocal of what a neutral surface
        // produces in it, or that surface will not come out neutral.
        val gains = DoubleArray(3) { if (neutral[it] > 1e-9) 1.0 / neutral[it] else 1.0 }

        // Tint trades green against red and blue, the perpendicular axis to temperature.
        val greenScale = 1.0 + 0.5 * tint
        gains[1] *= greenScale

        val smallest = gains.min()
        return DoubleArray(3) { gains[it] / smallest }
    }
}
