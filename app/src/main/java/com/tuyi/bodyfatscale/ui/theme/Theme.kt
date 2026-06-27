package com.tuyi.bodyfatscale.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF34A853),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEEAD6),
    tertiary = Color(0xFFF9AB00),
    background = Color(0xFFF8F9FA),
    onBackground = Color(0xFF202124),
    surface = Color.White,
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFE8EAED),
    error = Color(0xFFD93025),
    onError = Color.White,
)

@Composable
fun TuyiBodyFatScaleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
