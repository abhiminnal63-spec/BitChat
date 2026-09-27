package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// High-Contrast Brutalist Black-and-White Core Palette
val BrutalistPureBlack = Color(0xFF000000)
val BrutalistPureWhite = Color(0xFFFFFFFF)
val BrutalistBlack = Color(0xFF000000)
val BrutalistWhite = Color(0xFFFFFFFF)
val BrutalistBackground = Color(0xFFF7F7F5)
val EasappBackground = BrutalistBackground
val BrutalistSurface = Color(0xFFFFFFFF)
val BrutalistDarkBackground = Color(0xFF050505)
val BrutalistDarkCard = Color(0xFF0F0F0F)
val BrutalistStarkGrayLight = Color(0xFFEAEAE4)
val BrutalistStarkGrayDark = Color(0xFF1A1A1A)
val BrutalistContrastInk = Color(0xFF222222)
val BrutalistContrastSilver = Color(0xFFD8D8D8)

// Signature Brutalist Accents
val EasappAccent = Color(0xFFD2FF00) // Electric Acid Lime (Fluorescent Green)
val EasappAccentDark = Color(0xFFB5DE00)
val EasappHotPink = Color(0xFFFF10F0)
val EasappBrightRed = Color(0xFFFF1744)
val EasappElectricBlue = Color(0xFF00E5FF)
val EasappFluorescentYellow = Color(0xFFFFEA00)
val EasappBrightOrange = Color(0xFFFF6D00)
val EasappMonochromeWhite = Color(0xFFFFFFFF)

val EasappSecondary = Color(0xFFFF2A00) // High-Contrast Signal Red-Orange
val EasappCobalt = Color(0xFF0033FF) // High-Contrast Architectural Blue
val EasappCardBg = Color(0xFFFFFFFF)
val EasappBorder = Color(0xFF000000)
val EasappMuted = Color(0xFF52524E)
val EasappLightGray = Color(0xFFEAEAE4)
val EasappDarkSurface = Color(0xFF121212)
val EasappOnlineGreen = Color(0xFF00E676)
val EasappReadBlue = Color(0xFF0055FF)

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
    MONOCHROME_BW(
        id = "MONOCHROME_BW",
        displayName = "MONOCHROME B&W",
        subtitle = "STARK CONTRAST",
        accentColor = EasappMonochromeWhite
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

// High-Contrast M3 Mappings
val PrimaryLight = BrutalistPureBlack
val OnPrimaryLight = BrutalistPureWhite
val BackgroundLight = BrutalistBackground
val SurfaceLight = BrutalistPureWhite
val OnSurfaceLight = BrutalistPureBlack
val PrimaryDark = BrutalistPureWhite
val OnPrimaryDark = BrutalistPureBlack
val BackgroundDark = BrutalistDarkBackground
val SurfaceDark = BrutalistDarkCard
val OnSurfaceDark = BrutalistPureWhite

