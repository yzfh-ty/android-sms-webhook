package com.tyverse.smswebhook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AD8BA), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005140), onPrimaryContainer = Color(0xFF9CF2D4),
    secondary = Color(0xFFB5CCC1), secondaryContainer = Color(0xFF344B42),
    background = Color(0xFF101613), surface = Color(0xFF101613),
    surfaceVariant = Color(0xFF29352E), onSurface = Color(0xFFE0E9E2),
    onSurfaceVariant = Color(0xFFBFC9C2), outlineVariant = Color(0xFF3F4A43),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFB3F1DD), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635B), secondaryContainer = Color(0xFFD2E8DE),
    background = Color(0xFFF5F8F6), surface = Color(0xFFF5F8F6),
    surfaceVariant = Color(0xFFDFE7E1), onSurface = Color(0xFF17201C),
    onSurfaceVariant = Color(0xFF404944), outlineVariant = Color(0xFFBFC9C2),

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun SMSWebhookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
