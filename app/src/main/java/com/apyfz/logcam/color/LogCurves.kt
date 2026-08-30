package com.apyfz.logcam.color

/**
 * Builds Camera2 TONEMAP_CURVE point arrays.
 *
 * Camera2's tonemap stage takes scene-linear input normalised so that 1.0 is sensor clipping, and
 * emits the code value that lands in the encoded frame. A curve is a flat array of (Pin, Pout)
 * pairs, monotonically increasing in Pin, at most [android.hardware.camera2.CameraCharacteristics
 * .TONEMAP_MAX_CURVE_POINTS] pairs long. The Honor Magic 8 Pro reports 512.
 *
 * The headroom constant is the whole game. Apple Log's encode function saturates at code 1.0 when
 * scene reflectance reaches
 *
 *     R = 2^((1 - delta) / gamma) - beta = 12.0
 *
 * so mapping tonemap input 1.0 to R = 12.0 puts sensor clipping exactly at Apple Log code 1.0 and
 * 18% grey at input 0.015. That is not a low number by mistake: six stops of highlight headroom
 * above middle grey is what places middle grey at 0.18/12 in a scene-linear signal.
 */
object LogCurves {

    /** Scene reflectance at which Apple Log encode reaches 1.0. Derived, not chosen. */
    const val APPLE_LOG_CLIP_REFLECTANCE: Double = 12.0

    /** Scene reflectance at which O-Log encode reaches 1.0, per the white paper (section 3.1). */
    const val O_LOG_CLIP_REFLECTANCE: Double = 16.0

    /**
     * Apple Log 1 transfer curve.
     *
     * Apple Log 1 uses BT.2020 primaries, which is also what the camera's BT2020 colour space
     * profile delivers, so pairing this curve with a BT.2020 request needs no gamut matrix. The
     * output is Apple Log in the strict sense, not an approximation of it.
     */
    fun appleLog(points: Int): FloatArray = curve(points) { linear ->
        ColorProfiles.appleLogEncode(linear * APPLE_LOG_CLIP_REFLECTANCE)
    }

    /**
     * O-Log transfer curve, for footage that should cut with the Find X8 Ultra.
     *
     * O-Log's floor is not zero: encode(0) is 0.0631, which is 10-bit code 64. That lifted black is
     * the documented behaviour of the curve, and is how an O-Log file is told apart from a Rec.709
     * one.
     */
    fun oLog(points: Int): FloatArray = curve(points) { linear ->
        ColorProfiles.oLogEncode(linear * O_LOG_CLIP_REFLECTANCE)
    }

    /**
     * Identity ramp. Encodes the sensor's linear signal with no curve at all.
     *
     * Useless for viewing and close to useless for grading — linear in 10 bits spends almost every
     * code value on the top two stops — but it is the reference that makes it possible to measure
     * what the other curves actually did.
     */
    fun linear(points: Int): FloatArray = curve(points) { it }

    /**
     * Samples [transfer] over scene-linear input and returns the flat (Pin, Pout) pair array
     * Camera2 expects.
     *
     * Sampling is uniform in input because that is the domain Camera2 interpolates across; a curve
     * that is dense where the signal is not simply wastes points. Output is clamped to 0..1 and
     * forced non-decreasing, because the HAL is entitled to reject a curve that backtracks and a
     * float rounding step at the top end should not be what causes that.
     */
    /*
     * Do NOT sample these curves non-uniformly.
     *
     * Camera2's TonemapCurve is documented as (Pin, Pout) pairs, which suggests Pin can be placed
     * wherever the curve needs the resolution — densely near black for a log curve, sparsely in the
     * highlights where it is nearly straight. On paper that cuts the worst-case interpolation error
     * at 256 points from 29.3 code values of 1023 to 0.01.
     *
     * The Honor Magic 8 Pro's HAL ignores the Pin values and treats the array as Pout samples on a
     * uniform grid. Measured on device: with a scene whose peak tonemap input is 0.3457, the curve
     * predicts an Apple Log output of 0.869. Uniform Pin produced 0.866; output-uniform Pin produced
     * 0.452 — a visibly dark, badly compressed image.
     *
     * So uniform input sampling is not a naive choice here, it is the only one the hardware honours,
     * and the 29-code worst-case error in the deep shadows is the real cost of the 256-point ceiling.
     */

    /** Smallest gap that keeps Pin strictly increasing in 32-bit float. */
    private const val MIN_STEP = 1e-6

    fun curve(points: Int, transfer: (Double) -> Double): FloatArray {
        require(points >= 2) { "a curve needs at least two points, got $points" }
        val out = FloatArray(points * 2)
        var previous = 0f
        for (i in 0 until points) {
            val pin = i.toDouble() / (points - 1)
            val pout = transfer(pin).coerceIn(0.0, 1.0).toFloat().coerceAtLeast(previous)
            out[i * 2] = pin.toFloat()
            out[i * 2 + 1] = pout
            previous = pout
        }
        return out
    }
}
