package com.example.claudemobilehud.glass.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Rokid Glass HUD は単色緑モノクロ。Material 3 の dynamicColor は使わず、
 * 全体を緑トーンの ColorScheme に固定する (POC 踏襲)。
 */
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
