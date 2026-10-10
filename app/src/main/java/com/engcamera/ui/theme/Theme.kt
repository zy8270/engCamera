package com.engcamera.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = EngBlue,
    secondary = EngBlueLight,
    background = Color.Black,
    surface = Color(0xFF121212),
    onPrimary = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = EngBlue,
    secondary = EngBlueLight,
)

/**
 * 相机类应用：背景以黑色为主，无论系统深浅色模式，预览区都是黑的。
 */
@Composable
fun EngCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
