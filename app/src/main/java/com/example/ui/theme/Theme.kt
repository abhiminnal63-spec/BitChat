package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val BrutalistShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

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
    background = BrutalistBackground,
    surface = BrutalistPureWhite,
    cardBackground = BrutalistPureWhite,
    textPrimary = BrutalistPureBlack,
    textSecondary = BrutalistContrastInk,
    border = BrutalistPureBlack,
    shadow = BrutalistPureBlack,
    accent = EasappAccent,
    accentOn = BrutalistPureBlack,
    secondary = EasappSecondary,
    inputBackground = BrutalistStarkGrayLight,
    isDark = false,
    colorTheme = EasappColorTheme.FLUORESCENT_GREEN
)

val DarkBrutalistColors = BrutalistColors(
    background = BrutalistDarkBackground,
    surface = BrutalistDarkCard,
    cardBackground = BrutalistDarkCard,
    textPrimary = BrutalistPureWhite,
    textSecondary = BrutalistContrastSilver,
    border = BrutalistPureWhite,
    shadow = BrutalistPureWhite,
    accent = EasappAccent,
    accentOn = BrutalistPureBlack,
    secondary = EasappSecondary,
    inputBackground = BrutalistStarkGrayDark,
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
    val animSpec = tween<Color>(durationMillis = 220)

    val targetAccent = if (!darkTheme && colorTheme == EasappColorTheme.MONOCHROME_BW) {
        BrutalistPureBlack
    } else {
        colorTheme.accentColor
    }
    val targetAccentOn = if (!darkTheme && colorTheme == EasappColorTheme.MONOCHROME_BW) {
        BrutalistPureWhite
    } else {
        BrutalistPureBlack
    }

    val animatedAccent by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = animSpec,
        label = "theme_accent"
    )
    val animatedAccentOn by animateColorAsState(
        targetValue = targetAccentOn,
        animationSpec = animSpec,
        label = "theme_accent_on"
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
        accentOn = animatedAccentOn,
        inputBackground = animatedInputBg,
        colorTheme = colorTheme
    )

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = animatedAccent,
            onPrimary = animatedAccentOn,
            primaryContainer = BrutalistPureWhite,
            onPrimaryContainer = BrutalistPureBlack,
            secondary = BrutalistPureWhite,
            onSecondary = BrutalistPureBlack,
            secondaryContainer = BrutalistStarkGrayDark,
            onSecondaryContainer = BrutalistPureWhite,
            tertiary = EasappCobalt,
            onTertiary = BrutalistPureWhite,
            background = animatedBackground,
            onBackground = BrutalistPureWhite,
            surface = animatedCardBg,
            onSurface = BrutalistPureWhite,
            surfaceVariant = animatedInputBg,
            onSurfaceVariant = BrutalistContrastSilver,
            inverseSurface = BrutalistPureWhite,
            inverseOnSurface = BrutalistPureBlack,
            error = EasappSecondary,
            onError = BrutalistPureWhite,
            outline = BrutalistPureWhite,
            outlineVariant = BrutalistContrastSilver
        )
    } else {
        lightColorScheme(
            primary = animatedAccent,
            onPrimary = animatedAccentOn,
            primaryContainer = BrutalistPureBlack,
            onPrimaryContainer = BrutalistPureWhite,
            secondary = BrutalistPureBlack,
            onSecondary = BrutalistPureWhite,
            secondaryContainer = BrutalistStarkGrayLight,
            onSecondaryContainer = BrutalistPureBlack,
            tertiary = EasappCobalt,
            onTertiary = BrutalistPureWhite,
            background = animatedBackground,
            onBackground = BrutalistPureBlack,
            surface = animatedSurface,
            onSurface = BrutalistPureBlack,
            surfaceVariant = animatedInputBg,
            onSurfaceVariant = BrutalistContrastInk,
            inverseSurface = BrutalistPureBlack,
            inverseOnSurface = BrutalistPureWhite,
            error = EasappSecondary,
            onError = BrutalistPureWhite,
            outline = BrutalistPureBlack,
            outlineVariant = BrutalistContrastInk
        )
    }

    CompositionLocalProvider(LocalBrutalistColors provides brutalistColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BrutalistTypography,
            shapes = BrutalistShapes,
            content = content
        )
    }
}

