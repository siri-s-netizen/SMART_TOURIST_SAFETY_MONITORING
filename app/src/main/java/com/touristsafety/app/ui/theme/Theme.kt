package com.touristsafety.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = ForestGreen,
    onPrimary = Color.White,
    primaryContainer = MintContainer,
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Slate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8DA),
    onSecondaryContainer = Color(0xFF062018),
    tertiary = StatusWarning,
    onTertiary = Color.White,
    error = StatusDanger,
    onError = Color.White,
    errorContainer = StatusDangerContainer,
    onErrorContainer = Color(0xFF410002),
    background = Surface,
    onBackground = Color(0xFF1A1C1A),
    surface = Surface,
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFF414942),
    outline = Outline,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD3AC),
    onPrimary = Color(0xFF003824),
    primaryContainer = ForestGreenDark,
    onPrimaryContainer = MintContainer,
    secondary = Color(0xFFB2CCBD),
    onSecondary = Color(0xFF1D352B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3E0),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3E0),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC1C9C1),
    outline = Color(0xFF8B938C),
)

@Composable
fun TouristSafetyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TouristSafetyTypography,
        content = content,
    )
}
