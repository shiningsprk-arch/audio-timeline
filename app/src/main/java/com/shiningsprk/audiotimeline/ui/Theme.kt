package com.shiningsprk.audiotimeline.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6DF6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF0A2A63),
    secondary = Color(0xFF4C5B77),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E9F5),
    onSecondaryContainer = Color(0xFF1A2537),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF12161D),
    surface = Color.White,
    onSurface = Color(0xFF12161D),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF5A6472),
    outline = Color(0xFFD6DDE8),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DBBFF),
    onPrimary = Color(0xFF08214D),
    primaryContainer = Color(0xFF1B3568),
    onPrimaryContainer = Color(0xFFD8E3FF),
    secondary = Color(0xFFB9C6DE),
    background = Color(0xFF0E1218),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF161B23),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF232A35),
    onSurfaceVariant = Color(0xFFAFB9C8),
    outline = Color(0xFF333C4A),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AudioTimelineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
