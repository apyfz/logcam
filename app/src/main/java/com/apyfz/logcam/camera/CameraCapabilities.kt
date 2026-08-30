package com.apyfz.logcam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.util.Range
import android.util.Size

/**
 * What one camera can actually do, read from the HAL rather than assumed.
 *
 * Nothing in the app hardcodes a device. Every control the recorder sets is gated on the matching
 * flag here, so an unsupported key is disabled in the UI instead of being sent and silently ignored
 * — the failure mode that makes a "log" mode that is not log.
 */
data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: Int,
    val physicalIds: List<String>,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsEdgeOff: Boolean,
    val supportsNoiseReductionOff: Boolean,
    val supportsContrastCurve: Boolean,
    val maxCurvePoints: Int,
    val supportsColorTransform: Boolean,
    val tenBitProfiles: Set<Long>,
    val recommendedTenBitProfile: Long?,
    val isoRange: Range<Int>?,
    val maxAnalogIso: Int?,
    val exposureTimeRange: Range<Long>?,
    val maxRecordSize: Size?,
    val maxFrameRate: Int,
    val sensorOrientation: Int,
    val exposureRange: Range<Int>?,
    val exposureStep: Double,
    val activeArray: android.graphics.Rect?,
    val maxMeteringRegions: Int,
    /** Sensor XYZ-to-camera matrices at its two reference illuminants, row-major. */
    val colorTransformD65: DoubleArray?,
    val colorTransformStdA: DoubleArray?,
) {
    val isLevel3: Boolean
        get() = hardwareLevel == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3

    /** True when a genuine log recording is possible: custom curve, no sharpening, 10-bit. */
    val supportsLogRecording: Boolean
        get() = supportsContrastCurve && supportsManualPostProcessing && tenBitProfiles.isNotEmpty()

    /**
     * Why log recording is unavailable, or null when it is. Shown to the user verbatim; a vague
     * "not supported" would leave no way to tell a missing curve from a missing 10-bit path.
     */
    fun logUnavailableReason(): String? = when {
        !supportsManualPostProcessing -> "HAL does not advertise MANUAL_POST_PROCESSING"
        !supportsContrastCurve -> "HAL does not offer TONEMAP_MODE_CONTRAST_CURVE"
        tenBitProfiles.isEmpty() -> "HAL exposes no 10-bit dynamic range profile"
        else -> null
    }

    companion object {

        fun read(manager: CameraManager, cameraId: String): CameraCapabilities {
            val c = manager.getCameraCharacteristics(cameraId)

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
            val edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toSet().orEmpty()
            val nrModes =
                c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                    ?.toSet().orEmpty()
            val tonemapModes =
                c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toSet().orEmpty()
            val supportsColorTransform =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)
                        ?.contains(CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) == true
                } else {
                    // Before API 36 there is no available-modes key. MANUAL_POST_PROCESSING is the
                    // Camera2 contract that guarantees manual colour-correction controls.
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
                }

            val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
            val tenBit = profiles?.supportedProfiles
                ?.filter { it != DynamicRangeProfiles.STANDARD }
                ?.toSet()
                .orEmpty()

            val configs = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val recordSizes = configs?.getOutputSizes(android.media.MediaCodec::class.java)

            val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

            return CameraCapabilities(
                cameraId = cameraId,
                hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
                physicalIds = c.physicalCameraIds.sorted(),
                supportsManualSensor = CameraMetadata
                    .REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
                supportsManualPostProcessing = CameraMetadata
                    .REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps,
                supportsEdgeOff = CameraMetadata.EDGE_MODE_OFF in edgeModes,
                supportsNoiseReductionOff = CameraMetadata.NOISE_REDUCTION_MODE_OFF in nrModes,
                supportsContrastCurve =
                    CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tonemapModes,
                maxCurvePoints = usableCurvePoints(
                    c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0,
                ),
                supportsColorTransform = supportsColorTransform,
                tenBitProfiles = tenBit,
                recommendedTenBitProfile =
                    c.get(CameraCharacteristics.REQUEST_RECOMMENDED_TEN_BIT_DYNAMIC_RANGE_PROFILE),
                isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
                maxAnalogIso = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
                exposureTimeRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
                maxRecordSize = recordSizes?.maxByOrNull { it.width.toLong() * it.height },
                maxFrameRate = fpsRanges?.maxOfOrNull { it.upper } ?: 30,
                sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                exposureRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE),
                exposureStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    ?.let { it.numerator.toDouble() / it.denominator } ?: 0.0,
                activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                maxMeteringRegions = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
                colorTransformD65 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toMatrix(),
                colorTransformStdA = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toMatrix(),
            )
        }

        /**
         * The largest tonemap curve the hardware will actually accept, which is not what it says.
         *
         * The Honor Magic 8 Pro advertises TONEMAP_MAX_CURVE_POINTS = 512 and accepts a 512-point
         * curve without error, reporting TONEMAP_MODE=0 back. But measured across a sweep, 256
         * points records in full colour and 260 records pure greyscale — the tonemap LUT overruns
         * into the colour-correction registers, which is why the reported COLOR_CORRECTION_TRANSFORM
         * turns into a degenerate all-rows-equal matrix that no request can override.
         *
         * 256 points costs nothing worth having: sampled against the analytic Apple Log curve the
         * worst interpolation error is under half a 10-bit code value.
         */
        private const val SAFE_CURVE_POINTS = 256

        private fun usableCurvePoints(advertised: Int): Int =
            if (advertised <= 0) 0 else minOf(advertised, SAFE_CURVE_POINTS)

        /** Camera2 hands these back as rationals; the maths wants plain doubles. */
        private fun android.hardware.camera2.params.ColorSpaceTransform.toMatrix() =
            DoubleArray(9) { i ->
                val e = getElement(i % 3, i / 3)
                if (e.denominator == 0) 0.0 else e.numerator.toDouble() / e.denominator
            }

        /** Focal length of a physical lens, used to label the lens picker. */
        fun focalLength(manager: CameraManager, physicalId: String): Float? =
            runCatching {
                manager.getCameraCharacteristics(physicalId)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
            }.getOrNull()
    }
}
