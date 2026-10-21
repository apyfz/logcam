package com.apyfz.logcam.ui

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Tap the picture to focus; drag vertically or use the buttons to adjust real exposure. */
@Composable
fun TouchFocusOverlay(viewModel: CameraViewModel, state: CameraUiState) {
    var target by remember { mutableStateOf<Offset?>(null) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    val currentState by rememberUpdatedState(state)
    val density = LocalDensity.current
    val dragStep = with(density) { 12.dp.toPx() }
    val markerRadius = with(density) { 22.dp.toPx() }
    val controlsWidth = with(density) { 116.dp.toPx() }
    val controlsHeight = with(density) { 156.dp.toPx() }
    LaunchedEffect(state.settings.focusDiopters) {
        if (state.settings.focusDiopters != null) target = null
    }
    val focusSupported = CameraMetadata.CONTROL_AF_MODE_AUTO in state.capabilities?.autoFocusModes.orEmpty()
    val status = if (!focusSupported) "Metering" else when (state.reportedAfState) {
        CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> "Focused"
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "Try again"
        CameraMetadata.CONTROL_AF_STATE_ACTIVE_SCAN -> "Focusing…"
        else -> "Focus requested"
    }
    val tint = when (state.reportedAfState) {
        CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED -> Color(0xFF9DE0AE)
        CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> Color(0xFFFFC477)
        else -> Color.White
    }
    Box(Modifier.fillMaxSize()
        .onSizeChanged { if (bounds != it) target = null; bounds = it }
        .pointerInput(Unit) {
            detectTapGestures { position ->
                if (!currentState.recordingTransitioning) {
                    viewModel.monitor.sensorPoint(position.x, position.y)?.let { (x, y) ->
                        target = position
                        viewModel.meterAt(x, y)
                    }
                }
            }
        }
        .pointerInput(dragStep) {
            var accumulated = 0f
            detectDragGestures(
                onDragStart = { accumulated = 0f },
                onDrag = { change, amount ->
                    if (target != null && !currentState.recordingTransitioning) {
                        change.consume()
                        accumulated -= amount.y
                        while (kotlin.math.abs(accumulated) >= dragStep) {
                            val direction = if (accumulated > 0) 1 else -1
                            viewModel.nudgeExposure(direction)
                            accumulated -= direction * dragStep
                        }
                    }
                },
            )
        },
    ) {
        target?.let { point ->
            Canvas(Modifier.fillMaxSize()) {
                drawRect(tint, Offset(point.x - markerRadius, point.y - markerRadius),
                    Size(markerRadius * 2, markerRadius * 2), style = Stroke(1.5.dp.toPx()))
            }
            val proposedX = if (point.x + markerRadius + controlsWidth + 12 < bounds.width)
                point.x + markerRadius + 12 else point.x - markerRadius - controlsWidth - 12
            val x = proposedX.coerceIn(0f, (bounds.width - controlsWidth).coerceAtLeast(0f))
            val y = (point.y - controlsHeight / 2).coerceIn(0f, (bounds.height - controlsHeight).coerceAtLeast(0f))
            Column(
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.width(116.dp)
                    .background(Color.Black.copy(alpha = .75f), RoundedCornerShape(8.dp)).padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(status, color = tint, style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { viewModel.nudgeExposure(1) }) {
                    Icon(Icons.Default.Add, "Increase exposure", tint = Color.White)
                }
                Text(
                    if (state.settings.autoExposure) "%+.1f EV".format(java.util.Locale.US, state.exposureEv)
                    else "ISO ${state.settings.iso}",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = { viewModel.nudgeExposure(-1) }) {
                    Icon(Icons.Default.Remove, "Decrease exposure", tint = Color.White)
                }
            }
        }
    }
}
