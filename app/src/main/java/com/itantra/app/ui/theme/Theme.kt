package com.itantra.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val SoftDayColorScheme = lightColorScheme(
    primary = SoftIndigo,
    onPrimary = Color.White,
    primaryContainer = SoftIndigoContainer,
    onPrimaryContainer = SoftIndigoContainerText,
    secondary = SoftSage,
    onSecondary = Color.White,
    secondaryContainer = SoftSageContainer,
    onSecondaryContainer = SoftSageContainerText,
    tertiary = SoftApricot,
    onTertiary = Color.White,
    tertiaryContainer = SoftApricotContainer,
    onTertiaryContainer = SoftApricotContainerText,
    background = DayBackground,
    onBackground = DayTextPrimary,
    surface = DaySurface,
    onSurface = DayTextPrimary,
    surfaceVariant = DaySurfaceAlt,
    onSurfaceVariant = DayTextSecondary,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = DaySurfaceAlt,
    surfaceContainerLow = DayBackground,
    surfaceContainerLowest = Color.White,
    surfaceTint = SoftIndigo,
    outline = DayOutline,
    outlineVariant = DayOutline,
    error = SoftCoral,
    onError = Color.White,
    errorContainer = SoftCoralContainer,
    onErrorContainer = SoftCoralContainerText
)

private val SoftNightColorScheme = darkColorScheme(
    primary = SoftIndigoLight,
    onPrimary = Color(0xFF15162B),
    primaryContainer = SoftIndigoContainerDark,
    onPrimaryContainer = SoftIndigoContainerDarkText,
    secondary = SoftSageLight,
    onSecondary = Color(0xFF12251B),
    secondaryContainer = SoftSageContainerDark,
    onSecondaryContainer = SoftSageContainerDarkText,
    tertiary = SoftApricotLight,
    onTertiary = Color(0xFF2C2109),
    tertiaryContainer = SoftApricotContainerDark,
    onTertiaryContainer = SoftApricotContainerDarkText,
    background = NightBackground,
    onBackground = NightTextPrimary,
    surface = NightSurface,
    onSurface = NightTextPrimary,
    surfaceVariant = NightSurfaceAlt,
    onSurfaceVariant = NightTextSecondary,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceAlt,
    surfaceContainerHighest = Color(0xFF2C3038),
    surfaceContainerLow = Color(0xFF1A1D22),
    surfaceContainerLowest = Color(0xFF121419),
    surfaceTint = SoftIndigoLight,
    outline = NightOutline,
    outlineVariant = NightOutline,
    error = SoftCoralLight,
    onError = Color(0xFF3A1512),
    errorContainer = SoftCoralContainerDark,
    onErrorContainer = SoftCoralContainerDarkText
)

private val SoftDayColors = MinimalColors(
    background = DayBackground,
    surface = DaySurface,
    textPrimary = DayTextPrimary,
    textSecondary = DayTextSecondary,
    outline = DayOutline,
    accent = SoftIndigo,
    accentContainer = SoftIndigoContainer,
    error = SoftCoral,
    errorContainer = SoftCoralContainer,
    cardSecondaryBg = DaySurfaceAlt,
    badgeMintContainer = SoftBadgeMintContainer,
    badgeMintText = SoftBadgeMintText,
    badgeBlueContainer = SoftBadgeIndigoContainer,
    badgeBlueText = SoftBadgeIndigoText,
    badgePurpleContainer = SoftBadgePurpleContainer,
    badgePurpleText = SoftBadgePurpleText,
    badgeAmberContainer = SoftBadgeAmberContainer,
    badgeAmberText = SoftBadgeAmberText,
    isDark = false,
    textTertiary = DayTextTertiary,
    outlineStrong = DayOutlineStrong,
    accentDeep = SoftIndigoDeep,
    onAccent = Color.White,
    onError = Color.White,
    accentContainerText = SoftIndigoContainerText,
    sos = SoftCoral,
    sosDeep = SoftCoralDeep,
    sosContainer = SoftCoralContainer,
    sosContainerText = SoftCoralContainerText,
    rescue = SoftApricot,
    rescueDeep = SoftApricotDeep,
    rescueContainer = SoftApricotContainer,
    rescueContainerText = SoftApricotContainerText,
    mesh = SoftSage,
    meshDeep = SoftSageDeep,
    meshContainer = SoftSageContainer,
    meshContainerText = SoftSageContainerText,
    shadowTint = DayShadow
)

private val SoftNightColors = MinimalColors(
    background = NightBackground,
    surface = NightSurface,
    textPrimary = NightTextPrimary,
    textSecondary = NightTextSecondary,
    outline = NightOutline,
    accent = SoftIndigoLight,
    accentContainer = SoftIndigoContainerDark,
    error = SoftCoralLight,
    errorContainer = SoftCoralContainerDark,
    cardSecondaryBg = NightSurfaceAlt,
    badgeMintContainer = SoftBadgeMintContainerDark,
    badgeMintText = SoftBadgeMintTextDark,
    badgeBlueContainer = SoftBadgeIndigoContainerDark,
    badgeBlueText = SoftBadgeIndigoTextDark,
    badgePurpleContainer = SoftBadgePurpleContainerDark,
    badgePurpleText = SoftBadgePurpleTextDark,
    badgeAmberContainer = SoftBadgeAmberContainerDark,
    badgeAmberText = SoftBadgeAmberTextDark,
    isDark = true,
    textTertiary = NightTextTertiary,
    outlineStrong = NightOutlineStrong,
    accentDeep = SoftIndigo,
    onAccent = Color(0xFF15162B),
    onError = Color(0xFF3A1512),
    accentContainerText = SoftIndigoContainerDarkText,
    sos = SoftCoralLight,
    sosDeep = SoftCoral,
    sosContainer = SoftCoralContainerDark,
    sosContainerText = SoftCoralContainerDarkText,
    rescue = SoftApricotLight,
    rescueDeep = SoftApricot,
    rescueContainer = SoftApricotContainerDark,
    rescueContainerText = SoftApricotContainerDarkText,
    mesh = SoftSageLight,
    meshDeep = SoftSage,
    meshContainer = SoftSageContainerDark,
    meshContainerText = SoftSageContainerDarkText,
    shadowTint = NightShadow
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false, // Default to soft light theme per user directive
    content: @Composable () -> Unit
) {
    val minimalColors = if (darkTheme) SoftNightColors else SoftDayColors
    val colorScheme = if (darkTheme) SoftNightColorScheme else SoftDayColorScheme

    CompositionLocalProvider(
        LocalMinimalColors provides minimalColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = SoftShapes,
            content = content
        )
    }
}

val MaterialTheme.minimalColors: MinimalColors
    @Composable
    @ReadOnlyComposable
    get() = LocalMinimalColors.current
