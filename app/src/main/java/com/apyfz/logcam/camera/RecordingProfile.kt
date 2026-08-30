package com.apyfz.logcam.camera

import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaFormat
import com.apyfz.logcam.color.LogCurves

/**
 * A complete description of how a take should be encoded: which transfer curve the ISP writes, and
 * which colour tags the resulting file carries.
 *
 * The tags matter as much as the pixels. A downstream editor decides how to interpret a file from
 * its CICP tags, so a file whose tags disagree with its contents grades wrongly even though the
 * samples are correct.
 */
enum class RecordingProfile(
    val label: String,
    val description: String,
) {

    /**
     * Apple Log 1, written by loading its transfer function into the ISP's tonemap curve.
     *
     * Apple Log 1 is defined on BT.2020 primaries, which is what the BT.2020 request delivers, so
     * this is the real transfer function on the right gamut rather than an approximation of it.
     *
     * There is no CICP transfer code for Apple Log — Blackmagic writes "unspecified" for the same
     * reason. See [colorTransfer] for what this file is tagged with and why.
     */
    APPLE_LOG(
        label = "Apple Log",
        description = "Apple Log 1 on BT.2020. SDR 10-bit. Grade with an Apple Log LUT.",
    ),

    /**
     * O-Log, so footage cuts with a Find X8 Ultra without a colour space transform between them.
     */
    O_LOG(
        label = "O-Log",
        description = "OPPO O-Log. SDR 10-bit. Matches Find X8 Ultra footage.",
    ),

    /**
     * The HAL's own HLG10 rendering: its contrast curve, its saturation, its HDR transfer.
     *
     * Kept only as the fallback for a device that cannot honour a custom curve, and because it is
     * the reference for what the stock camera does. It is genuinely HDR and genuinely processed —
     * everything the log profiles exist to avoid.
     */
    HLG10(
        label = "HLG",
        description = "Flat HLG curve, 10-bit, not tagged HDR. Grade as Rec.2020 HLG.",
    ),

    /**
     * Identity tonemap: the sensor's linear signal, uncurved.
     *
     * Not a shooting mode. Linear in 10 bits spends most of its code values on the top two stops,
     * so this looks black and grades badly. It exists as the measurement reference — the only way
     * to prove what the other curves did to the signal.
     */
    LINEAR(
        label = "Linear",
        description = "Uncurved reference for measurement. Not for shooting.",
    );

    val isLog: Boolean get() = this == APPLE_LOG || this == O_LOG

    /** True only for the profiles that produce an HDR-tagged file. */
    val isHdr: Boolean get() = this == HLG10

    /** Whether this profile drives the tonemap itself rather than leaving it to the HAL. */
    val usesCustomCurve: Boolean get() = !isHdr

    /**
     * The transfer curve to load into TONEMAP_CURVE, or null when the HAL supplies its own.
     *
     * [points] should be TONEMAP_MAX_CURVE_POINTS for the camera in use.
     */
    fun tonemapCurve(points: Int): FloatArray? = when (this) {
        HLG10 -> null
        APPLE_LOG -> LogCurves.appleLog(points)
        O_LOG -> LogCurves.oLog(points)
        LINEAR -> LogCurves.linear(points)
    }

    /**
     * The camera-side dynamic range profile.
     *
     * All profiles need the 10-bit pipeline — that is what carries the log signal without banding —
     * so all of them request HLG10 here. This is a pixel format decision inside the capture
     * pipeline, not a statement that the footage is HDR; what the file claims to be is [colorTransfer].
     */
    fun dynamicRangeProfile(available: Set<Long>): Long =
        if (DynamicRangeProfiles.HLG10 in available) DynamicRangeProfiles.HLG10
        else available.firstOrNull() ?: DynamicRangeProfiles.STANDARD

    /** BT.2020 throughout: it is HLG's container gamut and Apple Log 1's defined gamut. */
    val colorStandard: Int get() = MediaFormat.COLOR_STANDARD_BT2020

    /**
     * Full range for the log profiles, limited for HLG10.
     *
     * A log curve already occupies a deliberate, narrow slice of the code range — Apple Log's black
     * sits at 0.150 and O-Log's at 0.063 — so squeezing it again into 64..940 discards precision
     * for nothing. HLG10 stays limited because that is what the HLG standard specifies and what
     * players expect.
     */
    val colorRange: Int
        get() = if (isHdr) MediaFormat.COLOR_RANGE_LIMITED else MediaFormat.COLOR_RANGE_FULL

    /**
     * The transfer characteristic requested of the encoder.
     *
     * Largely advisory: the encoder derives its tags from the camera surface's HDR dynamic range
     * profile and overrides whatever is asked for here. What actually decides the finished file is
     * [com.apyfz.logcam.record.ColrPatcher], which rewrites the tags after muxing so no clip
     * ever reaches the gallery claiming to be HDR.
     *
     * Original reasoning, kept because it explains the shape of the problem:
     *
     * There is no CICP code point for Apple Log or O-Log, so a log file has to be tagged with
     * something it is not. Tagging HLG would keep players on their 10-bit path, but it also tells
     * every one of them the file is HDR, and they respond by tone-mapping it — the punchy, clipped,
     * over-contrasty rendering this app exists to avoid. A log file that arrives pre-tone-mapped is
     * not gradeable.
     *
     * So the log profiles are tagged SDR BT.709 transfer on BT.2020 primaries, which is exactly
     * what the Find X8 Ultra and Blackmagic Camera do with their own log footage. Nothing
     * tone-maps it, the 10 bits survive in the bitstream regardless of the tag, and the input
     * transform is set once in Resolve — which is true of log footage from any camera.
     */
    val colorTransfer: Int
        get() = if (isHdr) MediaFormat.COLOR_TRANSFER_HLG
        else MediaFormat.COLOR_TRANSFER_SDR_VIDEO

    companion object {
        /** Profiles worth offering when the HAL cannot honour a custom curve. */
        val fallback = listOf(HLG10)
    }
}
