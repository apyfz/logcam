package com.apyfz.logcam.camera

import android.hardware.camera2.CameraMetadata

/**
 * Everything the user can change about a take.
 *
 * A null exposure or focus value means "let the camera decide". That is a real distinction rather
 * than a sentinel for zero: auto-exposure that drifts mid-take is exactly what a log workflow is
 * trying to avoid, so the UI has to be able to say which controls have been taken over and which
 * have not.
 */
data class CaptureSettings(
    val profile: RecordingProfile = RecordingProfile.APPLE_LOG,

    /**
     * Sensor gain. Null runs auto-exposure.
     *
     * Clamped against the sensor's own range where it is applied, because a slider working in stops
     * can otherwise round past the top of the range and ask for a sensitivity the sensor does not
     * have.
     */
    val iso: Int? = null,

    /** Shutter, in nanoseconds. Null runs auto-exposure. */
    val exposureTimeNs: Long? = 20_000_000L,

    /** Focus in diopters (1/metres). Null runs continuous autofocus. 0 is infinity. */
    val focusDiopters: Float? = null,

    /**
     * White balance as a colour temperature in Kelvin, or null to hand it back to the camera.
     *
     * Fixed by default: auto white balance drifting mid-take is unfixable in the grade, because
     * every frame would need a different correction.
     */
    val kelvin: Int? = 5500,

    /** Green against magenta, -1..1. The axis a colour temperature cannot express. */
    val tint: Float = 0f,

    /**
     * Zoom ratio, which is also how the lens is chosen: on a logical multi-camera the HAL switches
     * physical lenses to satisfy the requested ratio. More robust than addressing a physical camera
     * id directly, which the HAL may refuse to stream at 4K.
     */
    val zoomRatio: Float = 1f,

    /** Sharpening. Off is the reason this app exists. */
    val edgeOff: Boolean = true,

    /**
     * Noise reduction.
     *
     * Left ON by default, unlike sharpening. Measured at matched exposure, sharpening is what adds
     * the halos — noise reduction is what keeps a dimly lit take from being unusable grain. They are
     * separate stages and only one of them is the enemy.
     */
    val noiseReductionOff: Boolean = false,

    /**
     * Electronic stabilisation. Off by default: it crops, resamples, and warps the frame, which is
     * processing of exactly the kind this app is built to avoid.
     */
    val stabilisation: Boolean = false,

    /**
     * Auto-exposure compensation in EV steps of [CameraCapabilities.exposureStep].
     *
     * Applies only while auto-exposure is running; once ISO and shutter are both pinned there is
     * nothing left for it to compensate.
     */
    val exposureCompensation: Int = 0,

    /**
     * Where to meter and focus, in sensor-relative coordinates 0..1, or null for centre-weighted.
     *
     * A log curve puts middle grey at 0.49 and leaves plenty of room above it, so what the camera
     * chooses to expose for matters more than usual — metering off a bright window is the
     * difference between a gradeable frame and a black one.
     */
    val meterPoint: Pair<Float, Float>? = null,

    val bitRateMbps: Int = 80,

    val recordAudio: Boolean = true,
) {
    /** Auto-exposure runs unless the user has pinned both halves of the exposure. */
    val autoExposure: Boolean get() = iso == null || exposureTimeNs == null

    val shutterFractionLabel: String
        get() = exposureTimeNs?.let { "1/${(1_000_000_000.0 / it).toInt()}" } ?: "AUTO"

    val isoLabel: String get() = iso?.toString() ?: "AUTO"

    val focusLabel: String
        get() = when (focusDiopters) {
            null -> "AF"
            0f -> "INF"
            else -> "%.2fm".format(java.util.Locale.US, 1f / focusDiopters)
        }

    val whiteBalanceLabel: String get() = kelvin?.let { "${it}K" } ?: "AWB"

    enum class WhiteBalance(val label: String, val mode: Int) {
        AUTO("AWB", CameraMetadata.CONTROL_AWB_MODE_AUTO),
        INCANDESCENT("2700K", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
        FLUORESCENT("4000K", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
        DAYLIGHT("5500K", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
        CLOUDY("6500K", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
        SHADE("7500K", CameraMetadata.CONTROL_AWB_MODE_SHADE),
        ;

        /**
         * The labels are the nominal correlated colour temperature each Camera2 preset targets, not
         * a measured value from this sensor. Precise Kelvin control needs COLOR_CORRECTION_GAINS
         * driven by a per-device calibration, which this app does not have and will not invent.
         */
        val isNominal: Boolean get() = this != AUTO
    }
}
