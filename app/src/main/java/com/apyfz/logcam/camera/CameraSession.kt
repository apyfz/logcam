package com.apyfz.logcam.camera

import android.annotation.SuppressLint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.apyfz.logcam.color.WhiteBalanceMath
import java.util.concurrent.Executor

/**
 * Drives Camera2 for a single logical camera: opens it, builds the capture session, and translates
 * [CaptureSettings] into the request keys that actually change the picture.
 *
 * The interesting work is in [applySettings]. Everything else is lifecycle.
 */
class CameraSession(
    private val manager: CameraManager,
    private val capabilities: CameraCapabilities,
) {

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var previewSurface: Surface? = null
    private var recordSurface: Surface? = null
    private var settings: CaptureSettings = CaptureSettings()

    /** Last values the HAL reported back, so the UI can show what auto-exposure actually chose. */
    @Volatile
    var reportedIso: Int? = null
        private set

    @Volatile
    var reportedExposureNs: Long? = null
        private set

    /** Where autofocus settled, so manual focus can start from something sensible. */
    @Volatile
    var reportedFocusDiopters: Float? = null
        private set

    /**
     * Whether the HAL confirmed it applied the tonemap curve we sent.
     *
     * It is legal for a HAL to accept CONTRAST_CURVE and quietly do something else. Reading the
     * mode back out of the capture result checks request acceptance. Pixels and calibration
     * are still needed to establish the actual transfer response.
     */
    @Volatile
    var tonemapConfirmed: Boolean? = null
        private set

    @Volatile
    var edgeConfirmed: Boolean? = null
        private set

    @Volatile
    var noiseReductionConfirmed: Boolean? = null
        private set

    /**
     * True when the HAL has replaced the colour matrix with a greyscale one because a custom
     * tonemap curve is active, which makes the recording black and white.
     *
     * Confirmed behaviour on the Honor Magic 8 Pro. Detected rather than assumed, so a device
     * without the fault is not penalised for it.
     */
    @Volatile
    var colorKilledByCurve: Boolean = false
        private set

    /**
     * The colour correction the HAL computed for itself under auto white balance.
     *
     * Retained only for manual white balance. MagicOS11's tested custom256 path works with
     * automatic colour correction; forcing a harvested transform there is unnecessary.
     */
    @Volatile
    private var halColorTransform: android.hardware.camera2.params.ColorSpaceTransform? = null

    @Volatile
    private var halColorGains: android.hardware.camera2.params.RggbChannelVector? = null

    /** Set once the colour values above have been fed back, so we re-issue the request only once. */
    @Volatile
    private var colorFedBack = false

    /** Whether the most recent request actually carried a tonemap curve. */
    @Volatile
    private var curveSent = false

    private val executor = Executor { handler?.post(it) ?: it.run() }

    @SuppressLint("MissingPermission")
    fun open(onOpened: () -> Unit, onError: (String) -> Unit) {
        thread = HandlerThread("camera-session").also { it.start() }
        handler = Handler(thread!!.looper)

        manager.openCamera(
            capabilities.cameraId,
            executor,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    onOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onError("camera error $error")
                }
            },
        )
    }

    /**
     * Builds the capture session.
     *
     * [record] is the encoder's input surface, present only while recording. Reconfiguring a
     * session is expensive, so the recorder's surface is attached up front for a take rather than
     * being added mid-flight.
     */
    fun configure(
        preview: Surface,
        record: Surface?,
        settings: CaptureSettings,
        onReady: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val camera = device ?: return onError("camera not open")
        previewSurface = preview
        recordSurface = record
        this.settings = settings

        val dynamicRange = settings.profile.dynamicRangeProfile(capabilities.tenBitProfiles)

        val outputs = buildList {
            add(OutputConfiguration(preview))
            record?.let {
                add(
                    OutputConfiguration(it).apply {
                        // Only the encoder output carries the 10-bit profile. The preview surface
                        // is an ordinary 8-bit SurfaceView; asking it for HLG10 would fail the
                        // whole session for a stream nothing is graded from.
                        dynamicRangeProfile = dynamicRange
                    },
                )
            }
        }

        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    applySettings(settings)
                    onReady()
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    onError("capture session configuration failed")
                }
            },
        )

        camera.createCaptureSession(configuration)
    }

    /** Pushes a new repeating request. Cheap; safe to call on every slider move. */
    fun applySettings(settings: CaptureSettings) {
        if (settings.kelvin != this.settings.kelvin || settings.tint != this.settings.tint) {
            // A new profile or white balance means the previous colour matrix no longer describes
            // what the HAL would have chosen, so let it re-derive one.
            colorFedBack = false
            halColorTransform = null
            halColorGains = null
        }
        this.settings = settings
        val camera = device ?: return
        val active = session ?: return

        val template = if (recordSurface != null) CameraDevice.TEMPLATE_RECORD
        else CameraDevice.TEMPLATE_PREVIEW

        val request = camera.createCaptureRequest(template).apply {
            previewSurface?.let { addTarget(it) }
            recordSurface?.let { addTarget(it) }
            configure(this, settings)
        }

        runCatching {
            active.setRepeatingRequest(request.build(), resultCallback, handler)
        }.onFailure { Log.e(TAG, "failed to set repeating request", it) }
    }

    /**
     * Translates settings into capture request keys.
     *
     * Every key is gated on the capability that permits it. Sending an unsupported key is not an
     * error the HAL reports — it is simply ignored, which would leave the app claiming a log
     * recording it did not make.
     */
    private fun configure(request: CaptureRequest.Builder, s: CaptureSettings) {
        request.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        request.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(FRAME_RATE, FRAME_RATE))
        request.set(CaptureRequest.CONTROL_ZOOM_RATIO, s.zoomRatio)

        // ---- Sharpening and noise reduction ----------------------------------
        // These standard controls request reduced processing. Vendor sharpening/NR may remain.
        if (capabilities.supportsEdgeOff) {
            request.set(
                CaptureRequest.EDGE_MODE,
                if (s.edgeOff) CameraMetadata.EDGE_MODE_OFF else CameraMetadata.EDGE_MODE_HIGH_QUALITY,
            )
        }
        if (capabilities.supportsNoiseReductionOff) {
            request.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                if (s.noiseReductionOff) CameraMetadata.NOISE_REDUCTION_MODE_OFF
                else CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            )
        }

        // ---- Transfer curve --------------------------------------------------
        // Manual white balance needs a starting transform. Automatic WB follows the tested
        // custom256 request directly, without substituting a harvested matrix.
        val harvesting = s.kelvin != null && s.profile.usesCustomCurve &&
            capabilities.supportsColorTransform &&
            halColorTransform == null

        val curve = if (harvesting) null else s.profile.tonemapCurve(capabilities.maxCurvePoints)
        curveSent = curve != null && capabilities.supportsContrastCurve
        if (curve != null && capabilities.supportsContrastCurve) {
            request.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            // The same curve on all three channels: a transfer function shapes luminance response
            // and must not introduce a colour cast of its own.
            request.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(curve, curve, curve))
        } else {
            // FAST and HIGH_QUALITY measured indistinguishable on this HAL (luma spread 179.0 vs
            // 182.7), so there is nothing to gain from offering both.
            request.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
        }

        // ---- Colour correction ----------------------------------------------
        // Only needed on the manual-tonemap path; under HIGH_QUALITY tonemap the HAL manages this
        // itself and overriding it would discard its tuning for no gain.
        if (s.kelvin != null && curve != null && capabilities.supportsColorTransform) {
            val transform = halColorTransform
            val gains = halColorGains
            if (transform != null && gains != null) {
                request.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
                )
                request.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
                request.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            } else {
                request.set(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY,
                )
            }
        }

        // ---- Exposure --------------------------------------------------------
        if (!s.autoExposure && capabilities.supportsManualSensor) {
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            request.set(
                CaptureRequest.SENSOR_SENSITIVITY,
                capabilities.isoRange?.let { s.iso?.coerceIn(it.lower, it.upper) } ?: s.iso,
            )
            request.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s.exposureTimeNs)
            // Frame duration must be at least the exposure time or the HAL silently shortens the
            // exposure to fit the frame, which would make the shutter setting a lie.
            request.set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(s.exposureTimeNs ?: 0L, 1_000_000_000L / FRAME_RATE),
            )
        } else {
            request.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            // Compensation only means anything while the camera is choosing the exposure.
            capabilities.exposureRange?.let { range ->
                request.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    s.exposureCompensation.coerceIn(range.lower, range.upper),
                )
            }
        }

        // ---- Metering and focus point ----------------------------------------
        // A tapped point drives both auto-exposure and autofocus, which is what every camera app
        // does and what makes "expose for that" a single gesture.
        val point = s.meterPoint
        val array = capabilities.activeArray
        if (point != null && array != null && capabilities.maxMeteringRegions > 0) {
            val region = meteringRegion(point, array)
            request.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            if (s.focusDiopters == null) {
                request.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
        }

        // ---- White balance ---------------------------------------------------
        val kelvin = s.kelvin
        val d65 = capabilities.colorTransformD65
        val stdA = capabilities.colorTransformStdA
        if (kelvin != null && d65 != null && stdA != null && capabilities.supportsColorTransform) {
            // Continuous Kelvin, computed from the sensor's own calibration rather than picked from
            // Camera2's handful of named presets. Auto white balance has to be off entirely, or the
            // HAL keeps overwriting the gains.
            request.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            val g = WhiteBalanceMath.gainsForKelvin(kelvin.toDouble(), s.tint.toDouble(), d65, stdA)
            request.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                android.hardware.camera2.params.RggbChannelVector(
                    g[0].toFloat(), g[1].toFloat(), g[1].toFloat(), g[2].toFloat(),
                ),
            )
        } else {
            request.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            request.set(CaptureRequest.CONTROL_AWB_LOCK, false)
        }

        // ---- Focus -----------------------------------------------------------
        val focus = s.focusDiopters
        if (focus != null) {
            request.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            request.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        } else {
            request.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
        }

        // ---- Stabilisation ---------------------------------------------------
        request.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (s.stabilisation) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        )
    }

    /**
     * Reads back what the HAL says it did, rather than what we asked for.
     *
     * This is the check that catches a HAL accepting a request and ignoring it — the failure that
     * would otherwise look like a working log mode producing subtly processed footage.
     */
    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            reportedIso = result.get(TotalCaptureResult.SENSOR_SENSITIVITY)
            reportedExposureNs = result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)
            reportedFocusDiopters = result.get(TotalCaptureResult.LENS_FOCUS_DISTANCE)

            // Only a curve that was actually sent can be confirmed. During the colour-harvest pass
            // no curve has gone out yet, and reporting that as a failure would cry wolf.
            val requestHasCurve = request.get(CaptureRequest.TONEMAP_CURVE) != null
            tonemapConfirmed = if (!requestHasCurve) null else
                result.get(TotalCaptureResult.TONEMAP_MODE) ==
                    CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE

            edgeConfirmed = if (!settings.edgeOff) null else
                result.get(TotalCaptureResult.EDGE_MODE) == CameraMetadata.EDGE_MODE_OFF

            noiseReductionConfirmed = if (!settings.noiseReductionOff) null else
                result.get(TotalCaptureResult.NOISE_REDUCTION_MODE) ==
                    CameraMetadata.NOISE_REDUCTION_MODE_OFF

            captureColorCorrection(result, requestHasCurve)
            // Requests already in flight when the settings changed still carry the old values, so
            // the first frames after a change describe the previous profile. Skip them.
            if (debugSkip > 0) debugSkip-- else if (debugFrames-- > 0) dumpResult(result)
        }
    }

    /** Counts down so the diagnostic dump does not flood logcat at 30fps. */
    @Volatile
    private var debugFrames = 0

    @Volatile
    private var debugSkip = 0

    /** Dumps result metadata once the pipeline has settled onto the current request. */
    fun requestDiagnostics() {
        debugSkip = 20
        debugFrames = 2
    }

    private fun dumpResult(result: TotalCaptureResult) {
        fun <T> show(key: android.hardware.camera2.CaptureResult.Key<T>) =
            "${key.name.substringAfterLast('.')}=${result.get(key)}"

        val line = ("HALSTATE profile=${settings.profile.name} points=${capabilities.maxCurvePoints} " +
                show(TotalCaptureResult.TONEMAP_MODE) + " " +
                show(TotalCaptureResult.EDGE_MODE) + " " +
                show(TotalCaptureResult.NOISE_REDUCTION_MODE) + " " +
                show(TotalCaptureResult.COLOR_CORRECTION_MODE) + " " +
                show(TotalCaptureResult.COLOR_CORRECTION_GAINS) + " " +
                show(TotalCaptureResult.CONTROL_AWB_MODE) + " " +
                show(TotalCaptureResult.CONTROL_MODE) + " " +
                "transform=" + result.get(TotalCaptureResult.COLOR_CORRECTION_TRANSFORM))

        Log.i(TAG, line)
        // MagicOS filters third-party logcat, so the diagnostic goes to a file that adb can pull.
        diagnosticsSink?.invoke(line)
    }

    /** Where [dumpResult] writes, since logcat is not reliably readable on this device. */
    var diagnosticsSink: ((String) -> Unit)? = null

    /**
     * Picks up the HAL's colour matrix and re-issues the request the first time it appears.
     *
     * Only fires on the manual-tonemap path, and only once per settings change, because
     * re-submitting a repeating request on every frame would thrash the pipeline.
     */
    private fun captureColorCorrection(result: TotalCaptureResult, requestHasCurve: Boolean) {
        val transform = result.get(TotalCaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return
        val gains = result.get(TotalCaptureResult.COLOR_CORRECTION_GAINS) ?: return

        // Only latch a matrix the HAL derived for itself. On the manual-tonemap path it returns a
        // greyscale matrix — three identical rows — and latching that would feed the monochrome
        // back in permanently.
        // A greyscale matrix arriving while our curve is active is the HAL overriding colour. It is
        // detectable here, so the app can say so instead of quietly recording black and white.
        colorKilledByCurve = requestHasCurve && isGreyscale(transform)

        if (isDerived(result) && !isGreyscale(transform)) {
            halColorTransform = transform
            halColorGains = gains
        }

        val needsFeedback = settings.kelvin != null && settings.profile.usesCustomCurve && capabilities.supportsColorTransform
        if (needsFeedback && !colorFedBack && halColorTransform != null) {
            colorFedBack = true
            handler?.post { applySettings(settings) }
        }
    }

    /**
     * A small metering box around a tapped point, in sensor coordinates.
     *
     * Sized as a fraction of the sensor rather than a fixed pixel count so it covers the same part
     * of the picture whichever lens or crop is active.
     */
    private fun meteringRegion(
        point: Pair<Float, Float>,
        array: android.graphics.Rect,
    ): android.hardware.camera2.params.MeteringRectangle {
        val halfW = (array.width() * METER_FRACTION / 2).toInt()
        val halfH = (array.height() * METER_FRACTION / 2).toInt()
        val cx = array.left + (point.first.coerceIn(0f, 1f) * array.width()).toInt()
        val cy = array.top + (point.second.coerceIn(0f, 1f) * array.height()).toInt()
        return android.hardware.camera2.params.MeteringRectangle(
            (cx - halfW).coerceIn(array.left, array.right - 1),
            (cy - halfH).coerceIn(array.top, array.bottom - 1),
            (halfW * 2).coerceAtLeast(1),
            (halfH * 2).coerceAtLeast(1),
            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }

    /** True when the HAL computed this colour correction rather than being handed one. */
    private fun isDerived(result: TotalCaptureResult): Boolean =
        result.get(TotalCaptureResult.COLOR_CORRECTION_MODE) !=
            CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX

    /**
     * A colour matrix whose rows are all equal maps every channel to the same value, which is a
     * greyscale conversion rather than a colour correction.
     */
    private fun isGreyscale(
        transform: android.hardware.camera2.params.ColorSpaceTransform,
    ): Boolean {
        fun element(row: Int, column: Int): Double {
            val numerator = transform.getElement(column, row).numerator.toDouble()
            val denominator = transform.getElement(column, row).denominator.toDouble()
            return if (denominator == 0.0) 0.0 else numerator / denominator
        }
        return (0..2).all { column ->
            val first = element(0, column)
            (1..2).all { row -> kotlin.math.abs(element(row, column) - first) < 1e-6 }
        }
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    companion object {
        private const val TAG = "CameraSession"
        const val FRAME_RATE = 30
        /** Metering box edge, as a fraction of the sensor. */
        private const val METER_FRACTION = 0.15f
    }
}
