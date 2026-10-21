package com.apyfz.logcam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apyfz.logcam.camera.RecordingProfile
import com.apyfz.logcam.color.WhiteBalanceMath
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A stable viewfinder with a measured bottom deck in portrait and a compact side deck in landscape.
 * Recording and tool selection stay visible while the selected tool scrolls independently.
 */
@Composable
fun CameraScreen(viewModel: CameraViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.NONE) }

    // ".cube" has no registered MIME type, so the picker has to be opened wide and the file
    // validated by parsing it rather than by trusting its type.
    val pickLut = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.loadMonitorLut(it) } }

    // Landscape puts the deck beside the picture rather than under it: stacking it below would
    // squeeze the viewfinder into a letterbox slot barely taller than the controls.
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // The sensor does not turn with the phone, so the preview's correction follows the actual
    // display rotation. Configuration.orientation only distinguishes portrait from landscape and
    // misses 0↔180 and 90↔270 changes.
    val view = androidx.compose.ui.platform.LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(view, viewModel) {
        fun refreshRotation() {
            val degrees = when (view.display?.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
            viewModel.refreshRotation(degrees)
        }

        val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE)
            as android.hardware.display.DisplayManager
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == view.display?.displayId) refreshRotation()
            }
        }
        refreshRotation()
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    val density = LocalDensity.current
    var deckHeight by remember { mutableStateOf(200.dp) }

    val viewfinder = @Composable { modifier: Modifier ->
        Box(modifier) {
            Viewfinder(viewModel, state, landscape)
            StatusOverlay(
                state,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
            )
        }
    }
    val deck = @Composable { modifier: Modifier ->
        ControlDeck(
            state = state,
            viewModel = viewModel,
            panel = panel,
            onPanel = { panel = if (panel == it) Panel.NONE else it },
            onPickLut = { pickLut.launch(arrayOf("*/*")) },
            landscape = landscape,
            modifier = modifier,
        )
    }

    // Keep the GLSurfaceView in the same composition slot across rotations so its camera
    // surface survives. Only the deck placement and viewfinder padding change.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        viewfinder(
            Modifier
                .fillMaxSize()
                .padding(
                    end = if (landscape) DECK_WIDTH else 0.dp,
                    bottom = if (landscape) 0.dp else deckHeight,
                ),
        )
        deck(
            if (landscape) {
                Modifier.align(Alignment.CenterEnd).width(DECK_WIDTH).fillMaxHeight()
            } else {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {
                    deckHeight = with(density) { it.height.toDp() }
                }
            },
        )


    }
}

private val DECK_WIDTH = 280.dp

private enum class Panel { NONE, PROFILE, LENS, EXPOSURE, ISO, SHUTTER, FOCUS, WB, LUT, QUALITY }

@Composable
private fun Viewfinder(viewModel: CameraViewModel, state: CameraUiState, landscape: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // The capture stream is 16:9 whichever way the phone is held, so the preview keeps that
        // ratio and letterboxes. Filling the area instead stretches the picture, which makes framing
        // and focus judgements wrong. Which way round 16:9 sits depends on how the phone is held.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                android.opengl.GLSurfaceView(context).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(viewModel.monitor)
                    renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
        )

        TouchFocusOverlay(viewModel, state)

        if (state.settings.exposureCompensation != 0 && state.settings.autoExposure) {
            Mono(
                "EV %+.1f".format(java.util.Locale.US, state.exposureEv),
                Color(0xFFFFD24A),
                Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}

@Composable
private fun StatusOverlay(state: CameraUiState, modifier: Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val iso = state.reportedIso?.toString() ?: "—"
        val shutter = state.reportedExposureNs
            ?.let { "1/${(1_000_000_000.0 / it).roundToInt()}" } ?: "—"
        Pill("ISO $iso   ${shutter}   ${state.settings.profile.label}")

        if (state.recording) {
            val seconds = state.elapsedMs / 1000
            Mono("REC  %02d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60), Color(0xFFFF4438))
        }

        state.halWarnings.forEach { Mono(it, Color(0xFFFFB300)) }
        state.error?.let { Mono(it, Color(0xFFFF4438)) }
        var showSaved by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(state.lastSaved) {
            showSaved = state.lastSaved != null
            if (showSaved) { kotlinx.coroutines.delay(3000); showSaved = false }
        }
        if (showSaved && !state.recording) Mono("Saved to Movies / LogCam", Color.White)
    }
}

/**
 * Floating readout, matching Lutty's scrubber pill.
 *
 * Translucent and fully rounded rather than a hard black box: the overlay sits on the picture, so
 * it should read as glass over the image rather than a panel punched into it.
 */
@Composable
private fun Pill(text: String, color: Color = Color.White, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Mono(text: String, color: Color = Color.White, modifier: Modifier = Modifier) =
    Pill(text, color, modifier)

@Composable
private fun ControlDeck(
    state: CameraUiState,
    viewModel: CameraViewModel,
    panel: Panel,
    onPanel: (Panel) -> Unit,
    onPickLut: () -> Unit,
    landscape: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("4K / 30", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.recordingTransitioning) "Processing…"
                        else if (state.recording) "Recording" else "Ready to record",
                        color = if (state.recording) Color(0xFFFF4438) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                RecordButton(state.recording, !state.recordingTransitioning, viewModel::toggleRecording)
            }
            if (landscape) {
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    if (panel != Panel.NONE) PanelBody(panel, state, viewModel, onPickLut)
                    else LutSummary(state, onClick = { onPanel(Panel.LUT) })
                }
            } else if (panel != Panel.NONE) {
                Box(Modifier.fillMaxWidth().heightIn(max = 184.dp).verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    PanelBody(panel, state, viewModel, onPickLut)
                }
            }
            TabRow(state, panel, onPanel, landscape)
        }
    }
}

@Composable
private fun LutSummary(state: CameraUiState, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Text("MONITOR LUT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.monitorLutName?.removeSuffix(".cube") ?: "Choose a LUT",
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (state.monitorLutName != null && state.monitorEnabled) "Preview on · recording stays ungraded" else "Preview off",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TabRow(
    state: CameraUiState,
    panel: Panel,
    onPanel: (Panel) -> Unit,
    landscape: Boolean,
) {
    val tabs = listOf(
        Triple(Panel.ISO, "ISO", state.settings.isoLabel),
        Triple(Panel.SHUTTER, "Shutter", state.settings.shutterFractionLabel),
        Triple(Panel.WB, "WB", state.settings.whiteBalanceLabel),
        Triple(Panel.FOCUS, "Focus", state.settings.focusLabel),
        Triple(Panel.LENS, "Zoom", "%.1fx".format(java.util.Locale.US, state.settings.zoomRatio)),
        Triple(Panel.EXPOSURE, "Exposure", "%+.1f EV".format(java.util.Locale.US, state.exposureEv)),
        Triple(Panel.PROFILE, "Curve", state.settings.profile.label),
        Triple(Panel.LUT, "LUT", if (state.monitorLutName != null && state.monitorEnabled) "On" else "Off"),
        Triple(Panel.QUALITY, "Settings", "${state.settings.bitRateMbps} Mbps"),
    )
    val columns = if (landscape) 3 else 5
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (id, label, value) ->
                    val selected = panel == id
                    Column(
                        Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                            .clickable(role = Role.Button, onClick = { onPanel(id) })
                            .padding(horizontal = 4.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Lutty's labelled row: name on the left, current value on the right, hairline slider beneath. */
@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                readout,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SlimSlider(value = value, range = range, onChange = { if (enabled) onChange(it) })
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PanelBody(
    panel: Panel,
    state: CameraUiState,
    viewModel: CameraViewModel,
    onPickLut: () -> Unit,
) {
    val capabilities = state.capabilities
    val settings = state.settings
    val minIso = (capabilities?.isoRange?.lower ?: 100).toFloat()
    // The full sensor range, not just the analog part. Above maximum analog gain the sensor is only
    // amplifying digitally, which adds noise without adding information — but auto-exposure reaches
    // up there in a dim room, so a slider that stopped at the analog ceiling could not even
    // represent the camera's own starting point.
    val maxIso = (capabilities?.isoRange?.upper ?: 3200).toFloat()
    val analogCeiling = capabilities?.maxAnalogIso

    when (panel) {
        Panel.NONE -> Unit

        Panel.PROFILE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val offered =
                    if (capabilities?.supportsLogRecording == true) RecordingProfile.entries
                    else RecordingProfile.fallback
                offered.forEach { profile ->
                    FilterChip(
                        selected = settings.profile == profile,
                        onClick = { viewModel.setProfile(profile) },
                        enabled = !state.recording && !state.recordingTransitioning,
                        label = { Text(profile.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text(
                settings.profile.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Panel.LENS -> ValueSlider(
            label = "Zoom",
            value = settings.zoomRatio,
            range = 0.5f..10f,
            readout = "%.1fx".format(java.util.Locale.US, settings.zoomRatio),
        ) { v -> viewModel.update { it.copy(zoomRatio = (v * 10f).roundToInt() / 10f) } }

        Panel.EXPOSURE -> if (!settings.autoExposure) {
            // With ISO and shutter both pinned there is no auto-exposure left to compensate, so an
            // EV control has to move something real. It moves ISO, leaving the shutter — and so the
            // motion blur — exactly where it was set.
            val iso = (settings.iso ?: minIso.toInt()).toFloat()
            ValueSlider(
                label = "Exposure (ISO)",
                value = log2(iso / minIso),
                range = 0f..log2(maxIso / minIso),
                readout = "ISO ${iso.roundToInt()}",
            ) { stops -> viewModel.update { it.copy(iso = (minIso * 2f.pow(stops)).roundToInt().coerceAtMost(maxIso.toInt())) } }
        } else {
            val range = capabilities?.exposureRange
            ValueSlider(
                label = "Exposure",
                value = settings.exposureCompensation.toFloat(),
                range = (range?.lower ?: -24).toFloat()..(range?.upper ?: 24).toFloat(),
                readout = "%+.1f EV".format(java.util.Locale.US, state.exposureEv),
            ) { v -> viewModel.update { it.copy(exposureCompensation = v.roundToInt()) } }
        }

        Panel.ISO -> AutoSlider(
            label = "ISO",
            readout = settings.isoLabel +
                (if (analogCeiling != null && (settings.iso ?: 0) > analogCeiling) " (digital)" else ""),
            isAuto = settings.iso == null,
            onToggleAuto = {
                viewModel.update { it.copy(iso = if (it.iso == null) 400 else null) }
            },
            // Stops, not raw sensitivity: 100..25500 is eight stops and a linear track buries every
            // usable value in its leftmost few percent.
            value = log2((settings.iso ?: minIso.toInt()).toFloat() / minIso),
            range = 0f..log2(maxIso / minIso),
        ) { stops -> viewModel.update { it.copy(iso = (minIso * 2f.pow(stops)).roundToInt().coerceAtMost(maxIso.toInt())) } }

        Panel.SHUTTER -> AutoSlider(
            label = "Shutter",
            readout = settings.shutterFractionLabel,
            isAuto = settings.exposureTimeNs == null,
            onToggleAuto = {
                viewModel.update {
                    it.copy(exposureTimeNs = if (it.exposureTimeNs == null) 20_000_000L else null)
                }
            },
            value = settings.exposureTimeNs?.let { log2(1_000_000_000f / it) } ?: 5.6f,
            range = 3.9f..11f,
        ) { stops ->
            viewModel.update {
                it.copy(exposureTimeNs = (1_000_000_000.0 / 2.0.pow(stops.toDouble())).toLong())
            }
        }

        Panel.FOCUS -> AutoSlider(
            label = "Focus",
            readout = settings.focusLabel,
            isAuto = settings.focusDiopters == null,
            onToggleAuto = {
                viewModel.update { it.copy(focusDiopters = if (it.focusDiopters == null) 0f else null, meterPoint = null) }
            },
            value = settings.focusDiopters ?: 0f,
            range = 0f..10f,
        ) { v -> viewModel.update { it.copy(focusDiopters = v) } }

        Panel.WB -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AutoSlider(
                label = "White balance",
                readout = settings.whiteBalanceLabel,
                isAuto = settings.kelvin == null,
                onToggleAuto = {
                    viewModel.update { it.copy(kelvin = if (it.kelvin == null) 5500 else null) }
                },
                value = (settings.kelvin ?: 5500).toFloat(),
                range = WhiteBalanceMath.MIN_KELVIN.toFloat()..WhiteBalanceMath.MAX_KELVIN.toFloat(),
                // 50K steps: finer than anyone can judge by eye, coarse enough that the readout
                // does not flicker through meaningless digits.
            ) { v -> viewModel.update { it.copy(kelvin = (v / 50f).roundToInt() * 50) } }

            ValueSlider(
                label = "Tint",
                value = settings.tint,
                range = -1f..1f,
                readout = "%+.2f".format(java.util.Locale.US, settings.tint),
                enabled = settings.kelvin != null,
            ) { v -> viewModel.update { it.copy(tint = v) } }
        }

        Panel.LUT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Preview only · your recording stays ungraded.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.monitorLutName != null && state.monitorEnabled,
                    onClick = { viewModel.toggleMonitor() },
                    enabled = state.monitorLutName != null,
                    label = {
                        Text(
                            if (state.monitorEnabled) "On" else "Off",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                FilterChip(
                    selected = false,
                    onClick = onPickLut,
                    enabled = !state.recording && !state.recordingTransitioning,
                    label = { Text("Load .cube", style = MaterialTheme.typography.labelSmall) },
                )
            }
            Text(
                state.monitorLutName?.removeSuffix(".cube") ?: "No LUT selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.monitorError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Panel.QUALITY -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Toggle("Sharpening off", settings.edgeOff, capabilities?.supportsEdgeOff == true) {
                viewModel.update { s -> s.copy(edgeOff = it) }
            }
            Toggle(
                "Noise reduction off",
                settings.noiseReductionOff,
                capabilities?.supportsNoiseReductionOff == true,
            ) { viewModel.update { s -> s.copy(noiseReductionOff = it) } }
            Toggle("Stabilisation", settings.stabilisation) {
                viewModel.update { s -> s.copy(stabilisation = it) }
            }
            Toggle("Record audio", settings.recordAudio, !state.recording) {
                viewModel.update { s -> s.copy(recordAudio = it) }
            }
            ValueSlider(
                label = "Bitrate",
                value = settings.bitRateMbps.toFloat(),
                // The hardware encoder declares a 100 Mbps ceiling; offering more would be a
                // setting the silicon quietly ignores.
                range = 20f..100f,
                readout = "${settings.bitRateMbps} Mbps",
                enabled = !state.recording,
            ) { v -> viewModel.update { it.copy(bitRateMbps = v.roundToInt()) } }
        }
    }
}

/** A [ValueSlider] that can also be handed back to the camera. */
@Composable
private fun AutoSlider(
    label: String,
    readout: String,
    isAuto: Boolean,
    onToggleAuto: () -> Unit,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(10.dp))
            Text(
                readout,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = if (isAuto) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            // Auto and manual are not two ends of one scale, so the automatic state gets its own
            // control rather than being smuggled in as the minimum value. It sits last because it
            // is an action, while the value belongs beside the thing it describes.
            FilterChip(
                selected = isAuto,
                onClick = onToggleAuto,
                label = { Text("AUTO", style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        SlimSlider(value = value, range = range, onChange = { if (!isAuto) onChange(it) })
    }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(62.dp)
            .clip(CircleShape)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, radius = size.minDimension / 2 - 2f, style = Stroke(3f))
        }
        Box(
            Modifier
                .size(if (recording) 24.dp else 48.dp)
                .background(
                    if (enabled) Color(0xFFFF4438) else Color(0xFF70312D),
                    if (recording) RoundedCornerShape(6.dp) else CircleShape,
                ),
        )
    }
}

@Composable
private fun AutoManualSlider(
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Auto and manual are not two ends of one scale, so the null state gets its own control
        // rather than being smuggled in as the minimum value.
        //
        // A chip plus a full Material slider is wider than the deck, which pushed the right end of
        // the track off screen. The chip is compact and the slider is the hairline one, matching
        // the rest of the app.
        FilterChip(
            selected = value == null,
            onClick = { onChange(if (value == null) range.start else null) },
            label = { Text("AUTO", style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        Spacer(Modifier.width(10.dp))
        SlimSlider(
            value = value ?: range.start,
            range = range,
            onChange = { onChange(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun log2(x: Float): Float = ln(x) / ln(2f)

/**
 * Zoom ratios that land on a physical lens.
 *
 * The logical camera reports a 0.5–10.0 range; 0.5 and 1.0 sit on the ultrawide and main, and 3.4
 * is where the 22.48mm periscope takes over from the 6.55mm main. Anything between is a crop.
 */
/** A supported 16:9 preview size, given in sensor orientation. */
private const val PREVIEW_WIDTH = 1920
private const val PREVIEW_HEIGHT = 1080

private val LENS_PRESETS = listOf(
    "0.5x" to 0.5f,
    "1x" to 1.0f,
    "3.4x" to 3.4f,
)
