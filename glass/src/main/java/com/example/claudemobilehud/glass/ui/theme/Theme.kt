package com.example.claudemobilehud.glass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// docs/03 §4.11.6: Material3 dynamicColor は使わず GlassColorScheme で緑トーン固定。
private val GlassColorScheme = darkColorScheme(
    primary = TextGreen,
    onPrimary = GlassBackground,
    secondary = TextGreenDim,
    onSecondary = GlassBackground,
    background = GlassBackground,
    onBackground = TextGreen,
    surface = GlassBackground,
    onSurface = TextGreen,
)

@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GlassColorScheme,
        typography = Typography,
        content = content,
    )
}
