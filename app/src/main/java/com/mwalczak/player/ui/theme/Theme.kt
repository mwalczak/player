package com.mwalczak.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFFFF7A45)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF2B0E00),
    primaryContainer = Color(0xFF5A2A12),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFF9CC9FF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF1E252C),
    onSurfaceVariant = Color(0xFFB8C0C8),
    surfaceContainer = Color(0xFF171D23),
    surfaceContainerHigh = Color(0xFF1E252C),
)

/** IPTV apps are mostly used in the dark on a TV or phone in landscape, so the app is dark-only. */
@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
