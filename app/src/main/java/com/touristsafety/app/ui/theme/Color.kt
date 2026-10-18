package com.touristsafety.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Deliberately fixed colours rather than dynamic/Material You.
 *
 * Green/amber/red here are *safety semantics*, not branding: a tourist must be able to tell at a
 * glance whether they are inside a restricted area. Letting the wallpaper recolour those would be
 * actively harmful.
 */

// Core palette
val ForestGreen = Color(0xFF0B6E4F)
val ForestGreenDark = Color(0xFF00513A)
val MintContainer = Color(0xFFB7E4CE)
val Slate = Color(0xFF45564E)

val Surface = Color(0xFFFFFBFE)
val SurfaceVariant = Color(0xFFE3EAE4)
val Outline = Color(0xFF707973)

// Status colours
val StatusSafe = Color(0xFF1B7F4C)
val StatusSafeContainer = Color(0xFFD3F0DE)
val StatusWarning = Color(0xFF9A6400)
val StatusWarningContainer = Color(0xFFFFE7B8)
val StatusDanger = Color(0xFFB3261E)
val StatusDangerContainer = Color(0xFFFFDAD6)

// Zone styles (semi-transparent fills so overlapping zones stay readable)
val RestrictedFill = Color(0x40B3261E)
val RestrictedStroke = Color(0xFFB3261E)
val DangerousFill = Color(0x4DD84315)
val DangerousStroke = Color(0xFF8A2B00)
val SafeFill = Color(0x401B7F4C)
val SafeStroke = Color(0xFF1B7F4C)

// Drawing-mode accent
val DraftAccent = Color(0xFF1565C0)
val DraftFill = Color(0x261565C0)

// Simulation accent, so demo mode is impossible to mistake for live data
val SimulationAccent = Color(0xFF6A3EA1)
val SimulationContainer = Color(0xFFEBDDFF)

// User marker. Deliberately the conventional "my location" blue and *not* the brand green: green is
// reserved here for SAFE zones, so a green dot was invisible against a safe zone's fill (and against
// the green theme) — and every other map app has taught the user to look for a blue one.
val UserMarkerAccent = Color(0xFF1A73E8)
val UserMarkerOutline = Color(0xFF0B3C7A)
