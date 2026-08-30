package com.apyfz.logcam.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCurvesTest {

    private fun pairs(curve: FloatArray): List<Pair<Float, Float>> =
        curve.toList().chunked(2).map { it[0] to it[1] }

    @Test
    fun `input points are uniformly spaced, because the HAL ignores Pin`() {
        // Measured on device: non-uniform Pin produces a badly compressed picture because this HAL
        // reads the array as Pout on a uniform grid. See the note in LogCurves.
        val curve = LogCurves.appleLog(256)
        val step = 1f / 255
        for (i in 0 until 256) {
            assertEquals(i * step, curve[i * 2], 1e-5f)
        }
    }

    @Test
    fun `curve emits the requested number of pairs spanning the full input range`() {
        val curve = LogCurves.appleLog(512)
        assertEquals(1024, curve.size)
        assertEquals(0f, curve.first(), 0f)
        assertEquals(1f, curve[curve.size - 2], 1e-6f)
    }

    @Test
    fun `every curve is monotonically non-decreasing in both axes`() {
        for (curve in listOf(LogCurves.appleLog(512), LogCurves.oLog(512), LogCurves.linear(512))) {
            var lastIn = -1f
            var lastOut = -1f
            for ((pin, pout) in pairs(curve)) {
                assertTrue("input went backwards at $pin", pin > lastIn)
                assertTrue("output went backwards at $pin", pout >= lastOut)
                lastIn = pin
                lastOut = pout
            }
        }
    }

    @Test
    fun `apple log clip reflectance is the point where the encode reaches unity`() {
        // Not a chosen constant: it is where appleLogEncode saturates. If it were wrong, sensor
        // clipping would land somewhere other than code 1.0 and the highlights would be wasted.
        assertEquals(1.0, ColorProfiles.appleLogEncode(LogCurves.APPLE_LOG_CLIP_REFLECTANCE), 1e-6)
    }

    @Test
    fun `apple log places 18 percent grey where apple places it`() {
        // Middle grey sits at scene-linear 0.18 / 12.0 in the tonemap's input domain.
        val curve = LogCurves.appleLog(512)
        val greyIn = (0.18 / LogCurves.APPLE_LOG_CLIP_REFLECTANCE).toFloat()
        val sampled = sample(curve, greyIn)
        val expected = ColorProfiles.appleLogEncode(0.18).toFloat()

        // The spec's verified chain lands 18% grey at 0.4882725 in Apple Log; this is the same
        // number arrived at from the encode function alone.
        assertEquals(0.4882725f, expected, 1e-5f)
        assertEquals(expected, sampled, 2e-3f)
    }

    @Test
    fun `apple log black is lifted to the published toe value`() {
        // Apple Log's toe is quadratic, so encode(0) is not 0. A curve that started at 0 would mean
        // the toe segment had been dropped.
        assertEquals(0.15046f, LogCurves.appleLog(512)[1], 1e-4f)
    }

    @Test
    fun `o log black is lifted to 10-bit code 64`() {
        // The white paper fixes O-Log's floor at code 64 of 1023. This is the signature that tells
        // an O-Log file apart from a Rec.709 one, so it has to survive into the curve.
        val floor = LogCurves.oLog(512)[1]
        assertEquals(0.0631f, floor, 1e-4f)
        // 0.063099 * 1023 = 64.55, which quantises down to code 64 as published. Asserting the
        // truncation rather than the raw product is the claim the white paper actually makes.
        assertEquals(64, (floor * 1023f).toInt())
    }

    @Test
    fun `o log clips at the reflectance the white paper documents`() {
        assertEquals(1.0, ColorProfiles.oLogEncode(LogCurves.O_LOG_CLIP_REFLECTANCE), 1e-3)
    }

    @Test
    fun `linear curve is the identity`() {
        for ((pin, pout) in pairs(LogCurves.linear(512))) {
            assertEquals(pin, pout, 1e-6f)
        }
    }

    @Test
    fun `log curves spend far more code values on shadows than linear does`() {
        // The entire reason to shoot log. Below one stop under middle grey, the linear encoding has
        // almost nothing left; Apple Log still has usable separation.
        val shadow = (0.09 / LogCurves.APPLE_LOG_CLIP_REFLECTANCE).toFloat()
        val logCode = sample(LogCurves.appleLog(512), shadow)
        val linearCode = sample(LogCurves.linear(512), shadow)
        assertTrue("log $logCode should far exceed linear $linearCode", logCode > linearCode * 30)
    }

    @Test
    fun `rejects a curve too short to interpolate`() {
        val error = runCatching { LogCurves.appleLog(1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    /** Linear interpolation across the curve, the way the HAL reads it. */
    private fun sample(curve: FloatArray, at: Float): Float {
        val points = pairs(curve)
        val upper = points.indexOfFirst { it.first >= at }.coerceAtLeast(1)
        val (x0, y0) = points[upper - 1]
        val (x1, y1) = points[upper]
        val t = if (x1 == x0) 0f else (at - x0) / (x1 - x0)
        return y0 + t * (y1 - y0)
    }
}
