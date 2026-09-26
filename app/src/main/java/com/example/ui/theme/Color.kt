package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Easapp Brutalist Color Palette
val BrutalistBlack = Color(0xFF121212)
val BrutalistWhite = Color(0xFFFFFFFF)
val BrutalistBackground = Color(0xFFF5F5F0)
val EasappBackground = BrutalistBackground
val BrutalistSurface = Color(0xFFFFFFFF)

// Signature Accents
val EasappAccent = Color(0xFFD2FF00) // Electric Acid Lime (Fluorescent Green)
val EasappAccentDark = Color(0xFFB5DE00)
val EasappHotPink = Color(0xFFFF10F0)
val EasappBrightRed = Color(0xFFFF1744)
val EasappElectricBlue = Color(0xFF00E5FF)
val EasappFluorescentYellow = Color(0xFFFFEA00)
val EasappBrightOrange = Color(0xFFFF6D00)

val EasappSecondary = Color(0xFFFF5722) // International Orange
val EasappCobalt = Color(0xFF1A3BFF) // Deep Digital Blue
val EasappCardBg = Color(0xFFFFFFFF)
val EasappBorder = Color(0xFF121212)
val EasappMuted = Color(0xFF888880)
val EasappLightGray = Color(0xFFECECE6)
val EasappDarkSurface = Color(0xFF1E1E1E)
val EasappOnlineGreen = Color(0xFF00E676)
val EasappReadBlue = Color(0xFF0066FF)

enum class EasappColorTheme(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val accentColor: Color
) {
    FLUORESCENT_GREEN(
        id = "FLUORESCENT_GREEN",
        displayName = "FLUORESCENT GREEN",
        subtitle = "NEON LIME",
        accentColor = EasappAccent
    ),
    HOT_PINK(
        id = "HOT_PINK",
        displayName = "HOT PINK",
        subtitle = "VIVID PINK",
        accentColor = EasappHotPink
    ),
    RED(
        id = "RED",
        displayName = "RED",
        subtitle = "BRIGHT RED",
        accentColor = EasappBrightRed
    ),
    ELECTRIC_BLUE(
        id = "ELECTRIC_BLUE",
        displayName = "ELECTRIC BLUE",
        subtitle = "VIVID BLUE",
        accentColor = EasappElectricBlue
    ),
    YELLOW(
        id = "YELLOW",
        displayName = "YELLOW",
        subtitle = "NEON YELLOW",
        accentColor = EasappFluorescentYellow
    ),
    ORANGE(
        id = "ORANGE",
        displayName = "ORANGE",
        subtitle = "BRIGHT ORANGE",
        accentColor = EasappBrightOrange
    );

    companion object {
        fun fromId(id: String?): EasappColorTheme {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FLUORESCENT_GREEN
        }
    }
}

// M3 Mappings
val PrimaryLight = EasappAccent
val OnPrimaryLight = BrutalistBlack
val BackgroundLight = BrutalistBackground
val SurfaceLight = BrutalistSurface
val OnSurfaceLight = BrutalistBlack
