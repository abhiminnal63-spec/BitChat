package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
    val isDark: Boolean,
    val colorTheme: EasappColorTheme = EasappColorTheme.FLUORESCENT_GREEN
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
    isDark = false,
    colorTheme = EasappColorTheme.FLUORESCENT_GREEN
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
    isDark = true,
    colorTheme = EasappColorTheme.FLUORESCENT_GREEN
)

val LocalBrutalistColors = staticCompositionLocalOf { LightBrutalistColors }

object BrutalistTheme {
    val colors: BrutalistColors
        @Composable
        get() = LocalBrutalistColors.current
}

@Composable
fun EasappTheme(
    darkTheme: Boolean = false,
    colorTheme: EasappColorTheme = EasappColorTheme.FLUORESCENT_GREEN,
    content: @Composable () -> Unit
) {
    val baseColors = if (darkTheme) DarkBrutalistColors else LightBrutalistColors
    val animSpec = tween<Color>(durationMillis = 260)

    val animatedAccent by animateColorAsState(
        targetValue = colorTheme.accentColor,
        animationSpec = animSpec,
        label = "theme_accent"
    )
    val animatedBackground by animateColorAsState(
        targetValue = baseColors.background,
        animationSpec = animSpec,
        label = "theme_background"
    )
    val animatedSurface by animateColorAsState(
        targetValue = baseColors.surface,
        animationSpec = animSpec,
        label = "theme_surface"
    )
    val animatedCardBg by animateColorAsState(
        targetValue = baseColors.cardBackground,
        animationSpec = animSpec,
        label = "theme_card_bg"
    )
    val animatedTextPrimary by animateColorAsState(
        targetValue = baseColors.textPrimary,
        animationSpec = animSpec,
        label = "theme_text_primary"
    )
    val animatedTextSecondary by animateColorAsState(
        targetValue = baseColors.textSecondary,
        animationSpec = animSpec,
        label = "theme_text_secondary"
    )
    val animatedBorder by animateColorAsState(
        targetValue = baseColors.border,
        animationSpec = animSpec,
        label = "theme_border"
    )
    val animatedInputBg by animateColorAsState(
        targetValue = baseColors.inputBackground,
        animationSpec = animSpec,
        label = "theme_input_bg"
    )

    val brutalistColors = baseColors.copy(
        background = animatedBackground,
        surface = animatedSurface,
        cardBackground = animatedCardBg,
        textPrimary = animatedTextPrimary,
        textSecondary = animatedTextSecondary,
        border = animatedBorder,
        accent = animatedAccent,
        accentOn = BrutalistBlack,
        inputBackground = animatedInputBg,
        colorTheme = colorTheme
    )

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = animatedAccent,
            onPrimary = BrutalistBlack,
            secondary = EasappSecondary,
            onSecondary = BrutalistWhite,
            tertiary = EasappCobalt,
            onTertiary = BrutalistWhite,
            background = animatedBackground,
            onBackground = animatedTextPrimary,
            surface = animatedCardBg,
            onSurface = animatedTextPrimary,
            surfaceVariant = animatedInputBg,
            onSurfaceVariant = animatedTextPrimary,
            outline = animatedBorder,
            outlineVariant = EasappMuted
        )
    } else {
        lightColorScheme(
            primary = animatedAccent,
            onPrimary = BrutalistBlack,
            secondary = EasappSecondary,
            onSecondary = BrutalistWhite,
            tertiary = EasappCobalt,
            onTertiary = BrutalistWhite,
            background = animatedBackground,
            onBackground = BrutalistBlack,
            surface = animatedSurface,
            onSurface = BrutalistBlack,
            surfaceVariant = animatedInputBg,
            onSurfaceVariant = BrutalistBlack,
            outline = BrutalistBlack,
            outlineVariant = EasappMuted
        )
    }

    CompositionLocalProvider(LocalBrutalistColors provides brutalistColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
