package com.apyfz.logcam.camera

import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaFormat
import com.apyfz.logcam.color.LogCurves

/** Curve shapes, not promises of calibrated camera colour spaces or sensor precision. */
enum class RecordingProfile(val label: String, val description: String) {
    APPLE_LOG("Apple-shaped", "Custom Apple Log-shaped curve. Camera gamut is not calibrated."),
    O_LOG("O-Log-shaped", "Custom O-Log-shaped curve. Not calibrated to an OPPO camera."),
    HLG10("HLG", "Native HLG HDR capture. Use an HLG-compatible workflow."),
    LINEAR("Linear", "Identity tonemap reference; other ISP processing may remain.");

    val isLog: Boolean get() = this == APPLE_LOG || this == O_LOG
    val isHdr: Boolean get() = this == HLG10
    val usesCustomCurve: Boolean get() = !isHdr

    fun tonemapCurve(points: Int): FloatArray? {
        if (!usesCustomCurve) return null
        // MagicOS11: 257 remains coloured in YUV; 258 becomes grayscale in YUV and HEVC.
        // Keep the conservative, encoded-video-tested limit even for callers outside capabilities.
        val safe = points.coerceAtMost(SAFE_CURVE_POINTS)
        require(safe >= 2) { "A custom curve requires at least two supported points" }
        return when (this) {
            APPLE_LOG -> LogCurves.appleLog(safe)
            O_LOG -> LogCurves.oLog(safe)
            LINEAR -> LogCurves.linear(safe)
            HLG10 -> null
        }
    }

    fun dynamicRangeProfile(available: Set<Long>): Long {
        if (!isHdr) return DynamicRangeProfiles.STANDARD
        require(DynamicRangeProfiles.HLG10 in available) { "Camera does not support HLG10" }
        return DynamicRangeProfiles.HLG10
    }

    // Encoder requests are labels, not gamut conversions. The final container is reconciled
    // with the encoder's actual VUI; custom-curve colour calibration remains device-specific.
    val colorStandard: Int get() = if (isHdr) MediaFormat.COLOR_STANDARD_BT2020
        else MediaFormat.COLOR_STANDARD_BT601_PAL
    val colorRange: Int get() = if (isHdr) MediaFormat.COLOR_RANGE_LIMITED
        else MediaFormat.COLOR_RANGE_FULL
    val colorTransfer: Int get() = if (isHdr) MediaFormat.COLOR_TRANSFER_HLG
        else MediaFormat.COLOR_TRANSFER_SDR_VIDEO

    companion object {
        const val SAFE_CURVE_POINTS = 256
        val fallback = listOf(HLG10)
    }
}
