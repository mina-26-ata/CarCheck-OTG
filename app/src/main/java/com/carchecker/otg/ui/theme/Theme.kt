package com.carchecker.otg.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object CockpitColors {
    val Background = Color(0xFF040B14)
    val BackgroundDeep = Color(0xFF02070E)
    val Surface = Color(0xFF0A1626)
    val SurfaceElevated = Color(0xFF102238)
    val SurfaceGlass = Color(0xCC0D1C30)
    val SurfaceBorder = Color(0xFF1D3554)
    val HudGlowBorder = Color(0xFF1E5F74)

    val AccentCyan = Color(0xFF00E5FF)
    val AccentTeal = Color(0xFF00E676)
    val AccentBlue = Color(0xFF38BDF8)
    val HealthyGreen = Color(0xFF00E676)
    val WarningAmber = Color(0xFFFFB300)
    val CriticalRed = Color(0xFFFF3B5C)
    val InfoBlue = Color(0xFF38BDF8)

    val TextPrimary = Color(0xFFF1F7FF)
    val TextSecondary = Color(0xFF93AFCB)
}

private val CockpitColorScheme = darkColorScheme(
    primary = CockpitColors.AccentCyan,
    onPrimary = Color(0xFF021A24),
    primaryContainer = Color(0xFF0B3547),
    onPrimaryContainer = Color(0xFFA5F3FC),
    secondary = CockpitColors.AccentTeal,
    onSecondary = Color(0xFF032312),
    secondaryContainer = Color(0xFF0A3623),
    onSecondaryContainer = Color(0xFFA7F3D0),
    background = CockpitColors.Background,
    onBackground = CockpitColors.TextPrimary,
    surface = CockpitColors.Surface,
    onSurface = CockpitColors.TextPrimary,
    surfaceVariant = CockpitColors.SurfaceElevated,
    onSurfaceVariant = CockpitColors.TextSecondary,
    outline = CockpitColors.SurfaceBorder,
    error = CockpitColors.CriticalRed,
    onError = Color.White
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 19.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
)

@Composable
fun CarCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CockpitColorScheme,
        typography = AppTypography,
        content = content
    )
}
