package com.example.linienbegleiter.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
private fun animateColorScheme(targetColorScheme: ColorScheme): ColorScheme {
    val animationSpec = tween<Color>(durationMillis = 700, easing = FastOutSlowInEasing)
    return ColorScheme(
        primary = animateColorAsState(targetColorScheme.primary, animationSpec, label = "primary").value,
        onPrimary = animateColorAsState(targetColorScheme.onPrimary, animationSpec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(targetColorScheme.primaryContainer, animationSpec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(targetColorScheme.onPrimaryContainer, animationSpec, label = "onPrimaryContainer").value,
        inversePrimary = animateColorAsState(targetColorScheme.inversePrimary, animationSpec, label = "inversePrimary").value,
        secondary = animateColorAsState(targetColorScheme.secondary, animationSpec, label = "secondary").value,
        onSecondary = animateColorAsState(targetColorScheme.onSecondary, animationSpec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(targetColorScheme.secondaryContainer, animationSpec, label = "secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(targetColorScheme.onSecondaryContainer, animationSpec, label = "onSecondaryContainer").value,
        tertiary = animateColorAsState(targetColorScheme.tertiary, animationSpec, label = "tertiary").value,
        onTertiary = animateColorAsState(targetColorScheme.onTertiary, animationSpec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(targetColorScheme.tertiaryContainer, animationSpec, label = "tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(targetColorScheme.onTertiaryContainer, animationSpec, label = "onTertiaryContainer").value,
        background = animateColorAsState(targetColorScheme.background, animationSpec, label = "background").value,
        onBackground = animateColorAsState(targetColorScheme.onBackground, animationSpec, label = "onBackground").value,
        surface = animateColorAsState(targetColorScheme.surface, animationSpec, label = "surface").value,
        onSurface = animateColorAsState(targetColorScheme.onSurface, animationSpec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(targetColorScheme.surfaceVariant, animationSpec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec, label = "onSurfaceVariant").value,
        surfaceTint = animateColorAsState(targetColorScheme.surfaceTint, animationSpec, label = "surfaceTint").value,
        inverseSurface = animateColorAsState(targetColorScheme.inverseSurface, animationSpec, label = "inverseSurface").value,
        inverseOnSurface = animateColorAsState(targetColorScheme.inverseOnSurface, animationSpec, label = "inverseOnSurface").value,
        error = animateColorAsState(targetColorScheme.error, animationSpec, label = "error").value,
        onError = animateColorAsState(targetColorScheme.onError, animationSpec, label = "onError").value,
        errorContainer = animateColorAsState(targetColorScheme.errorContainer, animationSpec, label = "errorContainer").value,
        onErrorContainer = animateColorAsState(targetColorScheme.onErrorContainer, animationSpec, label = "onErrorContainer").value,
        outline = animateColorAsState(targetColorScheme.outline, animationSpec, label = "outline").value,
        outlineVariant = animateColorAsState(targetColorScheme.outlineVariant, animationSpec, label = "outlineVariant").value,
        scrim = animateColorAsState(targetColorScheme.scrim, animationSpec, label = "scrim").value
    )
}

@Composable
fun LinienbegleiterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val targetColors = if (darkTheme) DarkColorScheme else LightColorScheme
    val animatedColors = animateColorScheme(targetColors)
    MaterialTheme(
        colorScheme = animatedColors,
        typography = Typography,
        content = content
    )
}
