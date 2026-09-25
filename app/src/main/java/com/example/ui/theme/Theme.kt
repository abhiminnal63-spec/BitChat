package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class BrutalistColors(
    val background: Color,
    val surface: Color,
    val cardBackground: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val shadow: Color,
    val accent: Color,
    val accentOn: Color,
    val secondary: Color,
    val inputBackground: Color,
    val isDark: Boolean
)

val LightBrutalistColors = BrutalistColors(
    background = Color(0xFFF5F5F0),
    surface = Color(0xFFFFFFFF),
    cardBackground = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF121212),
    textSecondary = Color(0xFF555555),
    border = Color(0xFF121212),
    shadow = Color(0xFF121212),
    accent = Color(0xFFD2FF00),
    accentOn = Color(0xFF121212),
    secondary = Color(0xFFFF5722),
    inputBackground = Color(0xFFECECE6),
    isDark = false
)

val DarkBrutalistColors = BrutalistColors(
    background = Color(0xFF101010),
    surface = Color(0xFF181818),
    cardBackground = Color(0xFF1E1E1E),
    textPrimary = Color(0xFFF0F0F0),
    textSecondary = Color(0xFFA8A8A8),
    border = Color(0xFFECECEC),
    shadow = Color(0xFF000000),
    accent = Color(0xFFD2FF00),
    accentOn = Color(0xFF121212),
    secondary = Color(0xFFFF5722),
    inputBackground = Color(0xFF262626),
    isDark = true
)

val LocalBrutalistColors = staticCompositionLocalOf { LightBrutalistColors }

object BrutalistTheme {
    val colors: BrutalistColors
        @Composable
        get() = LocalBrutalistColors.current
}

private val LightColorScheme = lightColorScheme(
    primary = EasappAccent,
    onPrimary = BrutalistBlack,
    secondary = EasappSecondary,
    onSecondary = BrutalistWhite,
    tertiary = EasappCobalt,
    onTertiary = BrutalistWhite,
    background = Color(0xFFF5F5F0),
    onBackground = BrutalistBlack,
    surface = Color(0xFFFFFFFF),
    onSurface = BrutalistBlack,
    surfaceVariant = Color(0xFFECECE6),
    onSurfaceVariant = BrutalistBlack,
    outline = BrutalistBlack,
    outlineVariant = EasappMuted
)

private val DarkColorScheme = darkColorScheme(
    primary = EasappAccent,
    onPrimary = BrutalistBlack,
    secondary = EasappSecondary,
    onSecondary = BrutalistWhite,
    tertiary = EasappCobalt,
    onTertiary = BrutalistWhite,
    background = Color(0xFF101010),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFF0F0F0),
    outline = Color(0xFFECECEC),
    outlineVariant = EasappMuted
)

@Composable
fun EasappTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val brutalistColors = if (darkTheme) DarkBrutalistColors else LightBrutalistColors

    CompositionLocalProvider(LocalBrutalistColors provides brutalistColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
