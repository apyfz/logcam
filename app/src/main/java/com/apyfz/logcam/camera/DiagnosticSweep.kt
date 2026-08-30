package com.apyfz.logcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.apyfz.logcam.color.LogCurves
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finds out why a custom tonemap curve makes this camera record in black and white.
 *
 * The Honor Magic 8 Pro advertises MANUAL_POST_PROCESSING, accepts a CONTRAST_CURVE, confirms
 * TONEMAP_MODE=0 in its capture results, applies the curve correctly to luma — and outputs zero
 * chroma. Poking at that one setting at a time through the UI is slow and easy to get wrong, so this
 * runs every hypothesis as a scripted sweep against a small YUV stream and measures the actual
 * chroma of each configuration.
 *
 * The measurement is the point: `CONTROL_*` metadata can say whatever it likes, but mean |U-128| and
 * |V-128| over a real frame cannot lie about whether colour survived.
 */
class DiagnosticSweep(private val context: Context) {

    private lateinit var manager: CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private lateinit var reader: ImageReader
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val lines = StringBuilder()

    /** One configuration to try, and what it is testing. */
    private class Case(
        val name: String,
        val hypothesis: String,
        val apply: (CaptureRequest.Builder, CameraCapabilities) -> Unit,
    )

    @SuppressLint("MissingPermission")
    fun run(report: File) {
        manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull() ?: return
        val capabilities = CameraCapabilities.read(manager, cameraId)
        val characteristics = manager.getCameraCharacteristics(cameraId)

        say("SWEEP on ${android.os.Build.MODEL}, camera $cameraId")
        say("available effects: " +
            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.joinToString())
        say("max curve points: ${capabilities.maxCurvePoints}")
        say("")

        thread = HandlerThread("sweep").also { it.start() }
        handler = Handler(thread!!.looper)

        // Small YUV output: chroma is what is being measured, resolution is irrelevant, and a small
        // stream keeps every configuration change fast.
        reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4)

        val opened = CountDownLatch(1)
        manager.openCamera(cameraId, { handler?.post(it) }, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); opened.countDown() }
            override fun onError(camera: CameraDevice, error: Int) {
                say("camera open error $error"); camera.close(); opened.countDown()
            }
        })
        if (!opened.await(5, TimeUnit.SECONDS) || device == null) {
            say("could not open camera"); finish(report); return
        }

        if (!configureSession()) { say("session configuration failed"); finish(report); return }

        for (case in cases(capabilities)) {
            val result = measure(case, capabilities)
            say(result)
        }

        finish(report)
    }

    private fun configureSession(): Boolean {
        val camera = device ?: return false
        val ready = CountDownLatch(1)
        var ok = false
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface)),
                { handler?.post(it) },
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s; ok = true; ready.countDown()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { ready.countDown() }
                },
            ),
        )
        ready.await(5, TimeUnit.SECONDS)
        return ok
    }

    /**
     * Applies one configuration, lets the pipeline settle, then measures chroma in a real frame.
     */
    private fun measure(case: Case, capabilities: CameraCapabilities): String {
        val camera = device ?: return "${case.name}: no device"
        val active = session ?: return "${case.name}: no session"

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(reader.surface)
        // Common baseline for every case, so only the variable under test differs.
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        case.apply(builder, capabilities)

        val seen = CountDownLatch(SETTLE_FRAMES)
        var lastResult: TotalCaptureResult? = null
        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                lastResult = result
                seen.countDown()
            }
        }

        // Drain anything the previous case left queued, so the frame measured belongs to this one.
        while (reader.acquireLatestImage()?.also { it.close() } != null) Unit

        runCatching { active.setRepeatingRequest(builder.build(), callback, handler) }
            .onFailure { return "${case.name}: REQUEST REJECTED (${it.javaClass.simpleName})" }

        seen.await(4, TimeUnit.SECONDS)
        Thread.sleep(300)

        val chroma = readChroma() ?: return "${case.name}: no frame"
        val r = lastResult

        fun mode(key: android.hardware.camera2.CaptureResult.Key<Int>) =
            r?.get(key)?.toString() ?: "-"

        val verdict = if (chroma > COLOUR_THRESHOLD) "COLOUR" else "GREY"
        val gains = r?.get(TotalCaptureResult.COLOR_CORRECTION_GAINS)
        fun f(v: Float?) = v?.let { "%.2f".format(java.util.Locale.US, it) } ?: "-"

        return ("%-24s %-6s chroma=%4.1f tm=%s iso=%-5s exp=%-6s focus=%-5s zoom=%-4s " +
            "edge=%s nr=%s stab=%s R/B=%-5s | %s").format(
            java.util.Locale.US,
            case.name,
            verdict,
            chroma,
            mode(TotalCaptureResult.TONEMAP_MODE),
            r?.get(TotalCaptureResult.SENSOR_SENSITIVITY)?.toString() ?: "-",
            r?.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)?.let { "1/${1_000_000_000L / it}" } ?: "-",
            f(r?.get(TotalCaptureResult.LENS_FOCUS_DISTANCE)),
            f(r?.get(TotalCaptureResult.CONTROL_ZOOM_RATIO)),
            mode(TotalCaptureResult.EDGE_MODE),
            mode(TotalCaptureResult.NOISE_REDUCTION_MODE),
            mode(TotalCaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
            gains?.let { f(it.red / it.blue) } ?: "-",
            case.hypothesis,
        )
    }

    /**
     * Mean chroma deviation from neutral in the latest frame.
     *
     * In YUV_420_888 a perfectly grey pixel has U and V at 128, so the average distance from 128 is
     * a direct measure of how much colour survived, independent of exposure or scene brightness.
     */
    private fun readChroma(): Double? {
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val u = image.planes[1]
            val v = image.planes[2]
            var total = 0.0
            var count = 0
            val ub = u.buffer
            val vb = v.buffer
            val step = maxOf(1, ub.remaining() / 4096)
            var i = 0
            while (i < ub.remaining() && i < vb.remaining()) {
                total += kotlin.math.abs((ub.get(i).toInt() and 0xFF) - 128)
                total += kotlin.math.abs((vb.get(i).toInt() and 0xFF) - 128)
                count += 2
                i += step
            }
            if (count == 0) null else total / count
        } finally {
            image.close()
        }
    }

    private fun cases(capabilities: CameraCapabilities): List<Case> {
        val n = capabilities.maxCurvePoints.coerceAtLeast(2)
        val apple = LogCurves.appleLog(n)

        /** Apple Log on two channels and a hair different on the third. */
        val skewed = LogCurves.curve(n) { linear ->
            (com.apyfz.logcam.color.ColorProfiles
                .appleLogEncode(linear * LogCurves.APPLE_LOG_CLIP_REFLECTANCE) * 0.999)
        }

        @Suppress("UNUSED_VARIABLE")
        val unusedSkew = skewed
        val knownGoodTransform = ColorSpaceTransform(
            intArrayOf(
                212, 128, -55, 128, -29, 128,
                -26, 128, 170, 128, -16, 128,
                -2, 128, -110, 128, 240, 128,
            ),
        )
        val knownGoodGains = RggbChannelVector(1.4385f, 1f, 1f, 2.5361f)

        fun curve(b: CaptureRequest.Builder, c: FloatArray) {
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(c, c, c))
        }

        // Feature audit: set each control to a known value and read back what the HAL did with it.
        // A control that is accepted and ignored looks identical to one that works, from the UI.
        val knownIso = (capabilities.isoRange?.lower ?: 100) * 4
        val knownExposure = 1_000_000_000L / 60

        return listOf(
            Case("audit-profile-applelog", "Apple Log curve applied, colour intact") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
            },
            Case("audit-profile-olog", "O-Log curve applied, colour intact") { b, c ->
                curve(b, LogCurves.oLog(c.maxCurvePoints))
            },
            Case("audit-profile-hlg", "HAL tonemap, colour intact") { b, _ ->
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)
            },
            Case("audit-iso-manual", "manual ISO honoured") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, knownIso)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, knownExposure)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
            },
            Case("audit-focus-manual", "manual focus honoured") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 2.0f)
            },
            Case("audit-zoom", "zoom ratio honoured") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 3.4f)
            },
            Case("audit-wb-2500k", "Kelvin gains honoured, cool") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                applyKelvin(b, c, 2500.0)
            },
            Case("audit-wb-9000k", "Kelvin gains honoured, warm") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                applyKelvin(b, c, 9000.0)
            },
            Case("audit-edge-nr-off", "sharpening and NR off honoured") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
            },
            Case("audit-edge-nr-on", "sharpening and NR on honoured") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
                b.set(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                )
            },
            Case("audit-stabilisation-on", "video stabilisation honoured") { b, c ->
                curve(b, LogCurves.appleLog(c.maxCurvePoints))
                b.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                )
            },
        )
    }

    private fun applyKelvin(
        b: CaptureRequest.Builder,
        c: CameraCapabilities,
        kelvin: Double,
    ) {
        val d65 = c.colorTransformD65 ?: return
        val stdA = c.colorTransformStdA ?: return
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        val g = com.apyfz.logcam.color.WhiteBalanceMath
            .gainsForKelvin(kelvin, 0.0, d65, stdA)
        b.set(
            CaptureRequest.COLOR_CORRECTION_GAINS,
            RggbChannelVector(g[0].toFloat(), g[1].toFloat(), g[1].toFloat(), g[2].toFloat()),
        )
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        lines.append(line).append('\n')
    }

    private fun finish(report: File) {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader.close() }
        thread?.quitSafely()
        report.writeText(lines.toString())
    }

    companion object {
        private const val TAG = "DiagnosticSweep"
        private const val SETTLE_FRAMES = 12
        /** Below this the frame is neutral enough to call monochrome. */
        private const val COLOUR_THRESHOLD = 1.5
    }
}
