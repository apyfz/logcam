package com.apyfz.logcam.ui

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apyfz.logcam.camera.CameraCapabilities
import com.apyfz.logcam.camera.CameraSession
import com.apyfz.logcam.camera.CaptureSettings
import com.apyfz.logcam.camera.RecordingProfile
import com.apyfz.logcam.record.HevcRecorder
import com.apyfz.logcam.record.VideoOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the camera screen renders. */
data class CameraUiState(
    val capabilities: CameraCapabilities? = null,
    val settings: CaptureSettings = CaptureSettings(),
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val lastSaved: String? = null,
    val error: String? = null,
    val reportedIso: Int? = null,
    val reportedExposureNs: Long? = null,
    val tonemapConfirmed: Boolean? = null,
    val edgeConfirmed: Boolean? = null,
    val noiseReductionConfirmed: Boolean? = null,
    val colorKilledByCurve: Boolean = false,
    /** True once auto's starting values have been adopted and locked to manual. */
    val seeded: Boolean = false,
    val monitorLutName: String? = null,
    val monitorEnabled: Boolean = true,
    val monitorError: String? = null,
) {
    /** Exposure compensation expressed in stops, which is the only unit worth showing a user. */
    val exposureEv: Double
        get() = settings.exposureCompensation * (capabilities?.exposureStep ?: 0.0)

    val recordSize: Size get() = Size(3840, 2160)

    /**
     * Warns when the HAL accepted a request and did not honour it.
     *
     * Silence here is not the same as success — the fields are null until the first capture result
     * arrives — so this only speaks up when a value came back and disagreed.
     */
    val halWarnings: List<String>
        get() = buildList {
            if (tonemapConfirmed == false) {
                add("HAL ignored the custom tonemap curve — this is not a real log recording")
            }
            if (edgeConfirmed == false) add("HAL ignored EDGE_MODE_OFF — sharpening is still applied")
            if (noiseReductionConfirmed == false) add("HAL ignored NOISE_REDUCTION_MODE_OFF")
            if (colorKilledByCurve) {
                add("This phone drops all colour when a custom curve is used — recording will be black and white. Use HLG10.")
            }
        }
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val manager =
        app.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private var session: CameraSession? = null
    private var recorder: HevcRecorder? = null
    private var output: VideoOutput? = null
    private var previewSurface: Surface? = null

    /**
     * Renders the preview through an optional monitoring LUT.
     *
     * Held here rather than in the composable so it survives recomposition, and so the camera can be
     * started the moment GL hands back its surface.
     */
    val monitor = com.apyfz.logcam.gl.MonitorRenderer(onSurfaceReady = { surface ->
        viewModelScope.launch(Dispatchers.Main) { start(surface) }
    }).apply {
        diagnostics = { line ->
            runCatching {
                java.io.File(getApplication<Application>().getExternalFilesDir(null), "gl.txt")
                    .appendText(line + "\n")
            }
        }
    }

    /**
     * Attaches a preview surface, opening the camera the first time and re-pointing it afterwards.
     *
     * GL can hand back a new surface at any time — a rotation or the app returning to the
     * foreground rebuilds the context. Ignoring those leaves the camera rendering into a surface
     * nobody is showing, which looks exactly like a dead preview.
     */
    fun start(preview: Surface) {
        if (preview == previewSurface) return

        val existing = session
        if (existing != null) {
            previewSurface = preview
            existing.configure(preview, null, _state.value.settings, {}, ::fail)
            return
        }

        previewSurface = preview
        val cameraId = manager.cameraIdList.firstOrNull()
            ?: return fail("no camera found")

        val capabilities = runCatching { CameraCapabilities.read(manager, cameraId) }
            .getOrElse { return fail("could not read camera capabilities: ${it.message}") }

        // A device that cannot honour a custom curve should say so, not silently record HLG and
        // call it Apple Log.
        val settings = _state.value.settings.let {
            if (capabilities.supportsLogRecording) it
            else it.copy(profile = RecordingProfile.HLG10)
        }

        _state.update { it.copy(capabilities = capabilities, settings = settings, error = null) }

        refreshRotation(displayRotation)

        // One-off probe: list the vendor keys this camera genuinely exposes, rather than trusting
        // names read out of a dumpsys listing that covers every device the HAL was built for.
        runCatching {
            val c = manager.getCameraCharacteristics(cameraId)
            val sink = java.io.File(
                getApplication<Application>().getExternalFilesDir(null), "halstate.txt",
            )
            val interesting = Regex("contrast|saturat|sharp|tone|gamma|style|effect|bright", RegexOption.IGNORE_CASE)

            sink.appendText("CHARKEYS " + c.keys.map { it.name }
                .filter { interesting.containsMatchIn(it) } + "\n")
            sink.appendText("REQKEYS " + c.availableCaptureRequestKeys.map { it.name }
                .filter { interesting.containsMatchIn(it) } + "\n")
            sink.appendText("VENDORCHAR " + c.keys.map { it.name }
                .filter { !it.startsWith("android.") } + "\n")
            sink.appendText("VENDORREQ " + c.availableCaptureRequestKeys.map { it.name }
                .filter { !it.startsWith("android.") } + "\n")
        }.onFailure {
            java.io.File(getApplication<Application>().getExternalFilesDir(null), "halstate.txt")
                .appendText("PROBE FAILED " + it + "\n")
        }

        val camera = CameraSession(manager, capabilities)
        camera.diagnosticsSink = { line ->
            runCatching {
                java.io.File(getApplication<Application>().getExternalFilesDir(null), "halstate.txt")
                    .appendText(line + "\n")
            }
        }
        session = camera
        camera.open(
            onOpened = { camera.configure(preview, null, settings, ::onSessionReady, ::fail) },
            onError = ::fail,
        )
    }

    /**
     * Adopts whatever the camera metered, then never lets it move again.
     *
     * This is a manual app: auto-exposure hunting mid-take and auto white balance drifting between
     * shots are both unfixable in the grade. But starting from arbitrary fixed numbers would mean
     * every launch begins with a useless frame, so auto runs just long enough to produce a sane
     * starting point and is then switched off.
     */
    private fun seedManualControls() {
        val camera = session ?: return
        if (_state.value.seeded) return
        val exposure = camera.reportedExposureNs ?: return

        _state.update { it.copy(seeded = true) }
        update {
            it.copy(
                // ISO stays automatic: it is the one exposure control worth letting the camera ride,
                // because a dim room can need several stops more than a fixed value would allow and
                // the alternative is a black frame. Shutter and focus are pinned, so the look and
                // the motion blur still hold still.
                iso = null,
                exposureTimeNs = exposure,
                focusDiopters = camera.reportedFocusDiopters ?: 0f,
            )
        }
    }

    private fun onSessionReady() {
        viewModelScope.launch {
            // Let 3A settle before adopting its values; the first results are still converging.
            delay(SEED_DELAY_MS)
            seedManualControls()
        }
        viewModelScope.launch {
            // Capture results arrive continuously; polling them into the UI at a human rate keeps
            // recomposition cheap while still surfacing a HAL disagreement within a second.
            while (true) {
                val camera = session ?: break
                _state.update {
                    it.copy(
                        reportedIso = camera.reportedIso,
                        reportedExposureNs = camera.reportedExposureNs,
                        tonemapConfirmed = camera.tonemapConfirmed,
                        edgeConfirmed = camera.edgeConfirmed,
                        noiseReductionConfirmed = camera.noiseReductionConfirmed,
                        colorKilledByCurve = camera.colorKilledByCurve,
                        elapsedMs = recorder?.durationMs ?: 0,
                    )
                }
                delay(250)
            }
        }
    }

    fun update(transform: (CaptureSettings) -> CaptureSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        session?.applySettings(next)
    }

    /**
     * Changing the recording profile changes the encoder's colour tags, so it cannot take effect
     * mid-take. The UI disables the control while recording; this is the backstop.
     */
    fun setProfile(profile: RecordingProfile) {
        if (_state.value.recording) return
        update { it.copy(profile = profile) }
        session?.requestDiagnostics()
    }

    /** Meters and focuses on a point, given in preview-relative coordinates 0..1. */
    fun meterAt(x: Float, y: Float) {
        update { it.copy(meterPoint = x to y) }
    }

    /** Moves auto-exposure compensation by one step, clamped to what the device supports. */
    fun nudgeExposure(steps: Int) {
        val range = _state.value.capabilities?.exposureRange ?: return
        update {
            it.copy(
                exposureCompensation =
                    (it.exposureCompensation + steps).coerceIn(range.lower, range.upper),
            )
        }
    }

    /** Turns the monitoring LUT on or off without unloading it. */
    fun toggleMonitor() {
        val next = !_state.value.monitorEnabled
        monitor.enabled = next
        _state.update { it.copy(monitorEnabled = next) }
    }

    /**
     * Loads a .cube for preview monitoring.
     *
     * Parsed off the main thread because a 65-cube is 274k lines, and validated by parsing rather
     * than by file extension.
     */
    fun loadMonitorLut(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "LUT"
            val result = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    com.apyfz.logcam.color.CubeParser.parse(it)
                }
            }.getOrNull()

            when (result) {
                is com.apyfz.logcam.color.CubeResult.Ok -> {
                    monitor.setLut(result.lut)
                    monitor.enabled = true
                    _state.update {
                        it.copy(monitorLutName = name, monitorEnabled = true, monitorError = null)
                    }
                }
                is com.apyfz.logcam.color.CubeResult.Error -> _state.update {
                    it.copy(monitorError = "could not read LUT: ${result.message}")
                }
                null -> _state.update { it.copy(monitorError = "could not open that file") }
            }
        }
    }

    /**
     * Recomputes how far the preview must be turned to look upright.
     *
     * Must be called whenever the device rotates: the sensor is fixed to the body, so the correction
     * depends on the current display rotation, not on which way up the phone was when the camera
     * opened.
     */
    fun refreshRotation(displayRotationDegrees: Int) {
        displayRotation = displayRotationDegrees
        val sensor = _state.value.capabilities?.sensorOrientation ?: return
        monitor.rotationDegrees = (sensor - displayRotationDegrees + 360) % 360
    }

    /**
     * Current display rotation in degrees, supplied by the UI.
     *
     * Read from the view rather than from a WindowManager obtained on the application context: the
     * latter does not track the activity's display, so it kept reporting the launch orientation and
     * the preview never turned.
     */
    @Volatile
    private var displayRotation: Int = 0

    fun toggleRecording() {
        if (_state.value.recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val capabilities = _state.value.capabilities ?: return
        val preview = previewSurface ?: return
        val settings = _state.value.settings
        val size = _state.value.recordSize

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destination = VideoOutput.create(getApplication(), settings.profile)
                val encoder = HevcRecorder(
                    width = size.width,
                    height = size.height,
                    frameRate = CameraSession.FRAME_RATE,
                    bitRate = settings.bitRateMbps * 1_000_000,
                    profile = settings.profile,
                    orientationHint = orientationHint(capabilities.sensorOrientation),
                    withAudio = settings.recordAudio,
                )
                encoder.prepare(destination.fileDescriptor)

                output = destination
                recorder = encoder

                withContext(Dispatchers.Main) {
                    session?.configure(
                        preview = preview,
                        record = encoder.inputSurface,
                        settings = settings,
                        onReady = {
                            encoder.start()
                            _state.update { it.copy(recording = true, error = null) }
                        },
                        onError = { message ->
                            destination.abandon()
                            recorder = null
                            output = null
                            fail(message)
                        },
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "could not start recording", t)
                fail("could not start recording: ${t.message}")
            }
        }
    }

    private fun stopRecording() {
        val encoder = recorder ?: return
        val destination = output
        _state.update { it.copy(recording = false) }

        viewModelScope.launch(Dispatchers.IO) {
            encoder.stop()
            val frames = encoder.frameCount
            // A take with no frames means the encoder never received anything; publishing it would
            // put an unplayable file in the gallery.
            if (frames > 0) destination?.commit() else destination?.abandon()

            recorder = null
            output = null

            _state.update {
                it.copy(
                    lastSaved = if (frames > 0) "${destination?.displayName}  ·  $frames frames"
                    else null,
                    error = if (frames > 0) null else "recording produced no frames",
                    elapsedMs = 0,
                )
            }

            // Drop the encoder surface from the session so the camera is not still rendering into
            // a released surface.
            val preview = previewSurface ?: return@launch
            withContext(Dispatchers.Main) {
                session?.configure(preview, null, _state.value.settings, {}, ::fail)
            }
        }
    }

    /**
     * How far the recorded frame must be rotated to appear upright.
     *
     * The sensor is mounted at a fixed angle, so a clip is only correctly oriented if the device's
     * current rotation is subtracted from it. Without this, turning the phone to shoot vertically
     * produces a file that plays back on its side.
     */
    private fun orientationHint(sensorOrientation: Int): Int =
        (sensorOrientation - displayRotation + 360) % 360

    private fun fail(message: String) {
        Log.e(TAG, message)
        _state.update { it.copy(error = message) }
    }

    override fun onCleared() {
        recorder?.stop()
        output?.abandon()
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "CameraViewModel"
        private const val SEED_DELAY_MS = 1200L
    }
}
