package com.opticalcenter.spatialnav.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark gray surfaces with a blue accent. The app is always dark, to match the dark map. */
object Palette {
    val Background = Color(0xFF111315)
    val Surface = Color(0xFF1A1D21)
    val SurfaceRaised = Color(0xFF22262B)
    val SurfaceHigh = Color(0xFF2A2F35)
    val Outline = Color(0xFF3A4049)
    val OnSurface = Color(0xFFE6E8EB)
    val OnSurfaceMuted = Color(0xFFA3ABB5)

    val Blue = Color(0xFF4C8DFF)
    val BlueDeep = Color(0xFF1E5BD8)
    val BlueContainer = Color(0xFF1B3561)
    val OnBlueContainer = Color(0xFFD6E4FF)

    val Good = Color(0xFF51CF66)
    val Warn = Color(0xFFFFB454)
    val Bad = Color(0xFFFF6B6B)
}

private val DarkBlue = darkColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    primaryContainer = Palette.BlueContainer,
    onPrimaryContainer = Palette.OnBlueContainer,
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF0B1A33),
    secondaryContainer = Palette.SurfaceHigh,
    onSecondaryContainer = Palette.OnSurface,
    tertiary = Color(0xFF7DD3FC),
    background = Palette.Background,
    onBackground = Palette.OnSurface,
    surface = Palette.Surface,
    onSurface = Palette.OnSurface,
    surfaceVariant = Palette.SurfaceRaised,
    onSurfaceVariant = Palette.OnSurfaceMuted,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.SurfaceRaised,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF32383F),
    outline = Palette.Outline,
    outlineVariant = Color(0xFF2E333A),
    error = Palette.Bad,
    onError = Color(0xFF2D0A0A),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkBlue, content = content)
}
