package com.example.linienbegleiter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = HamburgTransitPalette.HamburgRed,
    onPrimary = HamburgTransitPalette.White,
    primaryContainer = HamburgTransitPalette.Sand,
    onPrimaryContainer = HamburgTransitPalette.HarborNavy,
    secondary = HamburgTransitPalette.ElbeBlue,
    onSecondary = HamburgTransitPalette.White,
    secondaryContainer = HamburgTransitPalette.ElbeBlue.copy(alpha = 0.14f),
    onSecondaryContainer = HamburgTransitPalette.HarborNavy,
    tertiary = HamburgTransitPalette.Brick,
    onTertiary = HamburgTransitPalette.White,
    tertiaryContainer = HamburgTransitPalette.Brick.copy(alpha = 0.14f),
    onTertiaryContainer = HamburgTransitPalette.Graphite,
    background = HamburgTransitPalette.LightSurface,
    onBackground = HamburgTransitPalette.Graphite,
    surface = HamburgTransitPalette.White,
    onSurface = HamburgTransitPalette.Graphite,
    surfaceVariant = HamburgTransitPalette.Sand.copy(alpha = 0.55f),
    onSurfaceVariant = HamburgTransitPalette.MutedGraphite,
    outline = HamburgTransitPalette.MutedGraphite.copy(alpha = 0.55f)
)

private val DarkColorScheme = darkColorScheme(
    primary = HamburgTransitPalette.HamburgRed,
    onPrimary = HamburgTransitPalette.White,
    primaryContainer = HamburgTransitPalette.Brick,
    onPrimaryContainer = HamburgTransitPalette.White,
    secondary = HamburgTransitPalette.ElbeBlue,
    onSecondary = HamburgTransitPalette.White,
    secondaryContainer = HamburgTransitPalette.ElbeBlue.copy(alpha = 0.35f),
    onSecondaryContainer = HamburgTransitPalette.NightText,
    tertiary = HamburgTransitPalette.Sand,
    onTertiary = HamburgTransitPalette.HarborNavy,
    tertiaryContainer = HamburgTransitPalette.Brick.copy(alpha = 0.48f),
    onTertiaryContainer = HamburgTransitPalette.NightText,
    background = HamburgTransitPalette.HarborNavy,
    onBackground = HamburgTransitPalette.NightText,
    surface = HamburgTransitPalette.DarkSurface,
    onSurface = HamburgTransitPalette.NightText,
    surfaceVariant = HamburgTransitPalette.HarborNavy,
    onSurfaceVariant = HamburgTransitPalette.NightMuted,
    outline = HamburgTransitPalette.NightMuted.copy(alpha = 0.6f)
)

@Composable
fun LinienbegleiterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
