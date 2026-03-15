package com.palmclaw.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF8C7851),
    onPrimary = Color(0xFFFFFFFE),
    primaryContainer = Color(0xFFD7AA96),
    onPrimaryContainer = Color(0xFF020826),
    secondary = Color(0xFF716040),
    onSecondary = Color(0xFFFFFFFE),
    secondaryContainer = Color(0xFFEADDCF),
    onSecondaryContainer = Color(0xFF020826),
    tertiary = Color(0xFFF25042),
    onTertiary = Color(0xFFFFFFFE),
    tertiaryContainer = Color(0xFFFFD9D4),
    onTertiaryContainer = Color(0xFF4E130D),
    background = Color(0xFFF9F4EF),
    onBackground = Color(0xFF020826),
    surface = Color(0xFFFFFFFE),
    onSurface = Color(0xFF020826),
    surfaceVariant = Color(0xFFEADDCF),
    onSurfaceVariant = Color(0xFF716040),
    outline = Color(0xFFCDBCA7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFE),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC0AD87),
    onPrimary = Color(0xFF332714),
    primaryContainer = Color(0xFF6B5440),
    onPrimaryContainer = Color(0xFFF9F4EF),
    secondary = Color(0xFFD4C1A5),
    onSecondary = Color(0xFF332714),
    secondaryContainer = Color(0xFF524434),
    onSecondaryContainer = Color(0xFFF1E3D5),
    tertiary = Color(0xFFFF8C7D),
    onTertiary = Color(0xFF561811),
    tertiaryContainer = Color(0xFF7A2C22),
    onTertiaryContainer = Color(0xFFFFDAD4),
    background = Color(0xFF1F1913),
    onBackground = Color(0xFFF9F4EF),
    surface = Color(0xFF231C16),
    onSurface = Color(0xFFFFFFFE),
    surfaceVariant = Color(0xFF3F3429),
    onSurfaceVariant = Color(0xFFD6C5B2),
    outline = Color(0xFFA08F7B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun PalmClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity
            val window = activity?.window
            if (window != null) {
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PalmClawTypography,
        content = content
    )
}
