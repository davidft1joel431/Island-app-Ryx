package com.d4viddf.hyperbridge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Ryx Isla modification: a fixed black/yellow/green palette instead of
// wallpaper-driven dynamic colors, so the app matches its island and logo.
private val RyxLightColorScheme = lightColorScheme(
    primary = RyxYellow,
    onPrimary = Color(0xFF231B00),
    primaryContainer = Color(0xFFFFE9AE),
    onPrimaryContainer = Color(0xFF3A2C00),
    secondary = Color(0xFF0F7A45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F5D8),
    onSecondaryContainer = Color(0xFF00391B),
    tertiary = Color(0xFF3F5F8A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E4FF),
    onTertiaryContainer = Color(0xFF001B3A),
    background = RyxIvory,
    onBackground = RyxInk,
    surface = RyxIvorySurface,
    onSurface = RyxInk,
    surfaceVariant = RyxSand,
    onSurfaceVariant = Color(0xFF5B5446),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF6EC),
    surfaceContainer = Color(0xFFF6F0E4),
    surfaceContainerHigh = Color(0xFFF0E9DB),
    surfaceContainerHighest = Color(0xFFEAE2D2),
    outline = Color(0xFF877A62),
    outlineVariant = Color(0xFFD8CDB8),
    inverseSurface = Color(0xFF25231D),
    inverseOnSurface = Color(0xFFF7F0E3),
    inversePrimary = RyxYellowSoft,
    surfaceTint = RyxYellow,
    scrim = Color.Black
)

private val RyxDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFD24A),
    onPrimary = Color(0xFF251C00),
    primaryContainer = Color(0xFF4A3900),
    onPrimaryContainer = Color(0xFFFFE8A8),
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF00391A),
    secondaryContainer = Color(0xFF0E4A2A),
    onSecondaryContainer = RyxGreenSoft,
    tertiary = Color(0xFF9CCBFF),
    onTertiary = Color(0xFF00325A),
    tertiaryContainer = Color(0xFF17466F),
    onTertiaryContainer = Color(0xFFD2E7FF),
    background = RyxDark,
    onBackground = Color(0xFFF6F0E6),
    surface = RyxDarkSurface,
    onSurface = Color(0xFFF6F0E6),
    surfaceVariant = RyxDarkElevated,
    onSurfaceVariant = Color(0xFFC9C2B4),
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Color(0xFF111113),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = RyxDarkHigh,
    surfaceContainerHighest = RyxDarkHighest,
    outline = Color(0xFF8E887C),
    outlineVariant = Color(0xFF3A3830),
    inverseSurface = Color(0xFFF6F0E6),
    inverseOnSurface = Color(0xFF25231D),
    inversePrimary = Color(0xFF6B4E00),
    surfaceTint = Color(0xFFFFD24A),
    scrim = Color.Black
)

@Composable
fun HyperBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) RyxDarkColorScheme else RyxLightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
