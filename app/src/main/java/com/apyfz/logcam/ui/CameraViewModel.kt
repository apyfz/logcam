package com.apyfz.logcam.ui

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
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
import java.util.concurrent.atomic.AtomicReference

/** Everything the camera screen renders. */
data class CameraUiState(
    val capabilities: CameraCapabilities? = null,
    val settings: CaptureSettings = CaptureSettings(),
    val recording: Boolean = false,
    val recordingTransitioning: Boolean = false,
    val elapsedMs: Long = 0,
    val lastSaved: String? = null,
    val error: String? = null,
    val reportedIso: Int? = null,
    val reportedAfState: Int? = null,
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
                add("Camera did not confirm the custom curve")
            }
            if (edgeConfirmed == false) add("HAL ignored EDGE_MODE_OFF — sharpening is still applied")
            if (noiseReductionConfirmed == false) add("HAL ignored NOISE_REDUCTION_MODE_OFF")
            if (colorKilledByCurve) {
                add("Camera reports a grayscale colour transform. Stop and check the recorded clip.")
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
    private var foreground = true
    private var statusJob: kotlinx.coroutines.Job? = null
    private var seedJob: kotlinx.coroutines.Job? = null

    /** Fixed launch preset for a controlled stock-camera comparison; recording remains manual. */
    fun prepareComparisonCapture(iso: Int = 100) {
        if (getApplication<Application>().applicationInfo.flags and
            ApplicationInfo.FLAG_DEBUGGABLE == 0 || session != null
        ) return
        _state.update {
            it.copy(
                settings = it.settings.copy(
                    profile = RecordingProfile.APPLE_LOG,
                    iso = iso.takeIf { value -> value in 50..12800 } ?: 100,
                    exposureTimeNs = 6_250_000L,
                    focusDiopters = null,
                    kelvin = null,
                    edgeOff = true,
                    noiseReductionOff = false,
                    stabilisation = false,
                ),
                seeded = true,
            )
        }
    }

    fun pausePreview() {
        foreground = false
        if (recordingPhase.get() == RecordingPhase.RECORDING) stopRecording()
        if (recordingPhase.get() == RecordingPhase.IDLE) releaseCamera()
    }

    fun resumePreview() {
        foreground = true
        previewSurface?.let { start(it) }
    }

    private fun releaseCamera() {
        statusJob?.cancel()
        seedJob?.cancel()
        session?.close()
        session = null
    }

    private val recordingPhase = AtomicReference(RecordingPhase.IDLE)

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
        if (!foreground) { previewSurface = preview; return }
        if (preview == previewSurface && session != null) return

        val existing = session
        if (existing != null) {
            previewSurface = preview
            val recordSurface = when (recordingPhase.get()) {
                RecordingPhase.STARTING, RecordingPhase.RECORDING ->
                    recorder?.inputSurface ?: return
                RecordingPhase.STOPPING -> return
                RecordingPhase.IDLE -> null
            }
            existing.configure(preview, recordSurface, _state.value.settings, {}, ::fail)
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
        seedJob?.cancel()
        statusJob?.cancel()
        seedJob = viewModelScope.launch {
            // Let 3A settle before adopting its values; the first results are still converging.
            delay(SEED_DELAY_MS)
            seedManualControls()
        }
        statusJob = viewModelScope.launch {
            // Capture results arrive continuously; polling them into the UI at a human rate keeps
            // recomposition cheap while still surfacing a HAL disagreement within a second.
            while (true) {
                val camera = session ?: break
                _state.update {
                    it.copy(
                        reportedIso = camera.reportedIso,
                        reportedAfState = camera.reportedAfState,
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

    /** A tap explicitly hands focus back to AF without changing manual exposure. */
    fun meterAt(x: Float, y: Float) {
        val next = _state.value.settings.copy(meterPoint = x to y, focusDiopters = null)
        _state.update { it.copy(settings = next, seeded = true, reportedAfState = null) }
        session?.applySettings(next, triggerFocus = true)
    }

    /** Auto exposure uses compensation; manual exposure changes ISO and preserves shutter. */
    fun nudgeExposure(direction: Int) {
        val caps = _state.value.capabilities ?: return
        update { s ->
            if (s.autoExposure) {
                val range = caps.exposureRange
                if (range == null) s else s.copy(exposureCompensation =
                    (s.exposureCompensation + direction).coerceIn(range.lower, range.upper))
            } else {
                val range = caps.isoRange
                if (range == null) s else s.copy(iso =
                    kotlin.math.round((s.iso ?: range.lower) * Math.pow(2.0, direction / 3.0))
                        .toInt().coerceIn(range.lower, range.upper))
            }
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
    init {
        val saved = getApplication<Application>().getSharedPreferences("monitor", Context.MODE_PRIVATE)
            .getString("uri", null)
        if (saved != null) loadMonitorLut(android.net.Uri.parse(saved))
    }

    fun loadMonitorLut(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: "Monitoring LUT"
            val result = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    com.apyfz.logcam.color.CubeParser.parse(it)
                }
            }.getOrNull()

            when (result) {
                is com.apyfz.logcam.color.CubeResult.Ok -> {
                    runCatching {
                        resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }.onSuccess {
                        getApplication<Application>().getSharedPreferences("monitor", Context.MODE_PRIVATE)
                            .edit().putString("uri", uri.toString()).apply()
                    }
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
     * Display.getRotation() is counter-clockwise from the user's point of view. For the back
     * camera that direction is reversed when mapping the sensor buffer to the screen, so the two
     * clockwise rotations add. This is Android's Camera2 relative-rotation formula with sign=-1.
     */
    fun refreshRotation(displayRotationDegrees: Int) {
        displayRotation = displayRotationDegrees
        monitor.displayRotationDegrees = displayRotationDegrees
        val sensor = _state.value.capabilities?.sensorOrientation ?: return
        monitor.rotationDegrees = (sensor + displayRotationDegrees) % 360
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
        when (recordingPhase.get()) {
            RecordingPhase.IDLE -> startRecording()
            RecordingPhase.RECORDING -> stopRecording()
            RecordingPhase.STARTING, RecordingPhase.STOPPING -> Unit
        }
    }

    private fun startRecording() {
        val capabilities = _state.value.capabilities ?: return
        if (session == null) return
        if (!recordingPhase.compareAndSet(RecordingPhase.IDLE, RecordingPhase.STARTING)) return
        if (previewSurface == null) {
            recordingPhase.set(RecordingPhase.IDLE)
            return
        }
        val settings = _state.value.settings
        val size = _state.value.recordSize
        _state.update { it.copy(recordingTransitioning = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            var destination: VideoOutput? = null
            var encoder: HevcRecorder? = null
            try {
                destination = VideoOutput.create(getApplication(), settings.profile)
                encoder = HevcRecorder(
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
                    val preview = previewSurface
                    val camera = session
                    if (preview == null || camera == null) {
                        recordingPhase.set(RecordingPhase.IDLE)
                        encoder.stop()
                        destination.abandon()
                        recorder = null
                        output = null
                        _state.update { it.copy(recordingTransitioning = false) }
                        return@withContext
                    }
                    camera.configure(
                        preview = preview,
                        record = encoder.inputSurface,
                        settings = settings,
                        onReady = {
                            if (recordingPhase.compareAndSet(
                                    RecordingPhase.STARTING,
                                    RecordingPhase.RECORDING,
                                )
                            ) {
                                encoder.start()
                                _state.update {
                                    it.copy(
                                        recording = true,
                                        recordingTransitioning = false,
                                        error = null,
                                    )
                                }
                                if (!foreground) stopRecording()
                            }
                        },
                        onError = { message ->
                            if (!recordingPhase.compareAndSet(
                                    RecordingPhase.STARTING,
                                    RecordingPhase.IDLE,
                                )
                            ) return@configure
                            encoder.stop()
                            destination.abandon()
                            if (recorder === encoder) recorder = null
                            if (output === destination) output = null
                            _state.update { it.copy(recordingTransitioning = false) }
                            fail(message)
                        },
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "could not start recording", t)
                encoder?.stop()
                destination?.abandon()
                if (recorder === encoder) recorder = null
                if (output === destination) output = null
                recordingPhase.compareAndSet(RecordingPhase.STARTING, RecordingPhase.IDLE)
                _state.update { it.copy(recordingTransitioning = false) }
                fail("could not start recording: ${t.message}")
            }
        }
    }

    private fun stopRecording() {
        if (!recordingPhase.compareAndSet(RecordingPhase.RECORDING, RecordingPhase.STOPPING)) return
        val encoder = recorder ?: run {
            recordingPhase.set(RecordingPhase.IDLE)
            _state.update { it.copy(recording = false, recordingTransitioning = false) }
            return
        }
        val destination = output
        _state.update { it.copy(recording = false, recordingTransitioning = true) }

        viewModelScope.launch(Dispatchers.IO) {
            encoder.stop()
            val frames = encoder.frameCount
            // A take with no frames means the encoder never received anything; publishing it would
            // put an unplayable file in the gallery.
            if (frames > 0) destination?.commit() else destination?.abandon()

            if (recorder === encoder) recorder = null
            if (output === destination) output = null
            recordingPhase.set(RecordingPhase.IDLE)

            _state.update {
                it.copy(
                    lastSaved = if (frames > 0) "${destination?.displayName}  ·  $frames frames"
                    else null,
                    error = if (frames > 0) null else "recording produced no frames",
                    elapsedMs = 0,
                    recordingTransitioning = false,
                )
            }

            // Drop the encoder surface from the session so the camera is not still rendering into
            // a released surface.
            val preview = previewSurface ?: return@launch
            withContext(Dispatchers.Main) {
                if (foreground) session?.configure(preview, null, _state.value.settings, {}, ::fail)
                else releaseCamera()
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
        recordingPhase.set(RecordingPhase.STOPPING)
        recorder?.stop()
        output?.abandon()
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "CameraViewModel"
        private const val SEED_DELAY_MS = 1200L
    }

    private enum class RecordingPhase { IDLE, STARTING, RECORDING, STOPPING }
}
