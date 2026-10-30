package com.d4viddf.hyperbridge.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

// Ryx Isla modification: use a fixed warm palette instead of wallpaper-driven dynamic colors.
private val RyxLightColorScheme = lightColorScheme(
    primary = RyxHoney,
    onPrimary = Color.White,
    primaryContainer = RyxHoneyLight,
    onPrimaryContainer = Color(0xFF2A1800),
    secondary = Color(0xFF715C3B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E1BF),
    onSecondaryContainer = Color(0xFF281A05),
    background = RyxCream,
    onBackground = RyxInk,
    surface = RyxCreamSurface,
    onSurface = RyxInk,
    surfaceVariant = RyxSand,
    onSurfaceVariant = Color(0xFF5F5547),
    outline = Color(0xFF82745F),
    outlineVariant = Color(0xFFD4C7B3)
)

private val RyxDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFC45B),
    onPrimary = Color(0xFF382500),
    primaryContainer = Color(0xFF624500),
    onPrimaryContainer = Color(0xFFFFE0A7),
    secondary = Color(0xFFE0C59A),
    onSecondary = Color(0xFF3C2F19),
    secondaryContainer = Color(0xFF55452C),
    onSecondaryContainer = Color(0xFFF8E2BF),
    background = RyxDark,
    onBackground = Color(0xFFF5EFE4),
    surface = RyxDarkSurface,
    onSurface = Color(0xFFF5EFE4),
    surfaceVariant = RyxDarkElevated,
    onSurfaceVariant = Color(0xFFD1C4AE),
    outline = Color(0xFFA99A81),
    outlineVariant = Color(0xFF514738)
)

@Composable
fun HyperBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val colorScheme = if (darkTheme) RyxDarkColorScheme else RyxLightColorScheme

    // Fix: Ensure Status Bar colors update correctly when Activity recreates for language change
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb() // Or transparent if you handle edge-to-edge
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
