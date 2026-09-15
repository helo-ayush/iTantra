package com.itantra.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// =========================================================================
// iTantra "Soft Minimalism" Design System
// Warm & airy surfaces, soft indigo accent, muted semantic colors.
// Light mode: "Soft Day"  ·  Dark mode: "Soft Night"
// Manrope typography · 22dp cards · hairline outlines · soft shadows
// =========================================================================

// ---- Accent · Soft Indigo ------------------------------------------------
val SoftIndigo = Color(0xFF7C7FE6)
val SoftIndigoDeep = Color(0xFF5F62D1)
val SoftIndigoLight = Color(0xFF9BA0EE)
val SoftIndigoContainer = Color(0xFFEEEFFB)
val SoftIndigoContainerText = Color(0xFF4A4C9E)
val SoftIndigoContainerDark = Color(0xFF2B2D47)
val SoftIndigoContainerDarkText = Color(0xFFC6C9F5)

// ---- SOS · Muted Coral ---------------------------------------------------
val SoftCoral = Color(0xFFEC6A5C)
val SoftCoralDeep = Color(0xFFD14F3D)
val SoftCoralLight = Color(0xFFF08A7E)
val SoftCoralContainer = Color(0xFFFCE8E4)
val SoftCoralContainerText = Color(0xFFB04A3C)
val SoftCoralContainerDark = Color(0xFF46302C)
val SoftCoralContainerDarkText = Color(0xFFF5B3A9)

// ---- Rescue · Soft Apricot ------------------------------------------------
val SoftApricot = Color(0xFFE8A33D)
val SoftApricotDeep = Color(0xFFC98526)
val SoftApricotLight = Color(0xFFEDB465)
val SoftApricotContainer = Color(0xFFFBF0DC)
val SoftApricotContainerText = Color(0xFF9A6E1F)
val SoftApricotContainerDark = Color(0xFF443519)
val SoftApricotContainerDarkText = Color(0xFFF3D4A0)

// ---- Mesh · Sage -----------------------------------------------------------
val SoftSage = Color(0xFF5FAE87)
val SoftSageDeep = Color(0xFF478F6C)
val SoftSageLight = Color(0xFF85C4A6)
val SoftSageContainer = Color(0xFFE6F2EB)
val SoftSageContainerText = Color(0xFF3E7D5C)
val SoftSageContainerDark = Color(0xFF2A3B33)
val SoftSageContainerDarkText = Color(0xFFB7DECB)

// ---- Soft Day (light) surfaces ---------------------------------------------
val DayBackground = Color(0xFFFAF9F6)
val DaySurface = Color(0xFFFFFFFF)
val DaySurfaceAlt = Color(0xFFF3F1EC)
val DayTextPrimary = Color(0xFF211F1C)
val DayTextSecondary = Color(0xFF837D73)
val DayTextTertiary = Color(0xFFA9A398)
val DayOutline = Color(0xFFEAE6DE)
val DayOutlineStrong = Color(0xFFDBD6CB)
val DayShadow = Color(0x14211F1C)

// ---- Soft Night (dark) surfaces --------------------------------------------
val NightBackground = Color(0xFF16181D)
val NightSurface = Color(0xFF1E2127)
val NightSurfaceAlt = Color(0xFF262A31)
val NightTextPrimary = Color(0xFFEDEBE8)
val NightTextSecondary = Color(0xFF9C978D)
val NightTextTertiary = Color(0xFF6F6B63)
val NightOutline = Color(0xFF2E323A)
val NightOutlineStrong = Color(0xFF3B404A)
val NightShadow = Color(0x33000000)

// ---- Badge tints ------------------------------------------------------------
// Light
val SoftBadgeMintContainer = Color(0xFFE3F2E8)
val SoftBadgeMintText = Color(0xFF3E7D5C)
val SoftBadgeIndigoContainer = Color(0xFFE8ECFB)
val SoftBadgeIndigoText = Color(0xFF5761B8)
val SoftBadgePurpleContainer = Color(0xFFEDE8F7)
val SoftBadgePurpleText = Color(0xFF775BA6)
val SoftBadgeAmberContainer = Color(0xFFF8EED9)
val SoftBadgeAmberText = Color(0xFF96712A)
// Dark
val SoftBadgeMintContainerDark = Color(0xFF28362F)
val SoftBadgeMintTextDark = Color(0xFF9CCDB0)
val SoftBadgeIndigoContainerDark = Color(0xFF2B2F48)
val SoftBadgeIndigoTextDark = Color(0xFFAAB4E8)
val SoftBadgePurpleContainerDark = Color(0xFF343048)
val SoftBadgePurpleTextDark = Color(0xFFC3B1E2)
val SoftBadgeAmberContainerDark = Color(0xFF3E321D)
val SoftBadgeAmberTextDark = Color(0xFFE8CD9A)

// =========================================================================
// Theme contract consumed by every screen.
// =========================================================================
@Immutable
data class MinimalColors(
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val outline: Color,
    val accent: Color,
    val accentContainer: Color,
    val error: Color,
    val errorContainer: Color,
    val cardSecondaryBg: Color,
    val badgeMintContainer: Color,
    val badgeMintText: Color,
    val badgeBlueContainer: Color,
    val badgeBlueText: Color,
    val badgePurpleContainer: Color,
    val badgePurpleText: Color,
    val badgeAmberContainer: Color,
    val badgeAmberText: Color,
    val isDark: Boolean,
    // ---- Soft Minimalism extensions ----
    val textTertiary: Color = textSecondary,
    val outlineStrong: Color = outline,
    val accentDeep: Color = accent,
    val onAccent: Color = Color.White,
    val onError: Color = Color.White,
    val accentContainerText: Color = accent,
    val sos: Color = error,
    val sosDeep: Color = error,
    val sosContainer: Color = errorContainer,
    val sosContainerText: Color = error,
    val rescue: Color = SoftApricot,
    val rescueDeep: Color = SoftApricotDeep,
    val rescueContainer: Color = SoftApricotContainer,
    val rescueContainerText: Color = SoftApricotContainerText,
    val mesh: Color = SoftSage,
    val meshDeep: Color = SoftSageDeep,
    val meshContainer: Color = SoftSageContainer,
    val meshContainerText: Color = SoftSageContainerText,
    val shadowTint: Color = DayShadow
)

val LocalMinimalColors = staticCompositionLocalOf {
    MinimalColors(
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
        isDark = false
    )
}

val MinimalColorsInstance: MinimalColors
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalMinimalColors.current
