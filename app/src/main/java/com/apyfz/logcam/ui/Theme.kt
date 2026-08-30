package com.apyfz.logcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Deliberately monochrome, matching Lutty so the two apps read as one toolset.
 *
 * A viewfinder is a colour judgement surface, so any hue in the surrounding interface competes with
 * the image and biases what the operator sees — doubly so when framing flat log footage. Everything
 * is neutral grey; only the record state and warnings carry colour, because those must not be
 * missed. Dynamic colour is off so the palette never follows the wallpaper.
 */

private val Dark = darkColorScheme(
    primary = Color(0xFFE8E8E9),
    onPrimary = Color(0xFF1A1A1C),
    primaryContainer = Color(0xFF303033),
    onPrimaryContainer = Color(0xFFF2F2F3),
    secondary = Color(0xFFBFBFC2),
    onSecondary = Color(0xFF1A1A1C),
    secondaryContainer = Color(0xFF2A2A2D),
    onSecondaryContainer = Color(0xFFE8E8E9),
    tertiary = Color(0xFFBFBFC2),
    onTertiary = Color(0xFF1A1A1C),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8E9),
    surface = Color(0xFF0D0D0E),
    onSurface = Color(0xFFE8E8E9),
    surfaceVariant = Color(0xFF2A2A2D),
    onSurfaceVariant = Color(0xFFA8A8AC),
    surfaceContainerLowest = Color(0xFF080809),
    surfaceContainerLow = Color(0xFF121213),
    surfaceContainer = Color(0xFF161617),
    surfaceContainerHigh = Color(0xFF1F1F21),
    surfaceContainerHighest = Color(0xFF29292B),
    outline = Color(0xFF56565A),
    outlineVariant = Color(0xFF353538),
    inverseSurface = Color(0xFFE8E8E9),
    inverseOnSurface = Color(0xFF1A1A1C),
    error = Color(0xFFE79A94),
    onError = Color(0xFF40100C),
)

@Composable
fun LogCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
