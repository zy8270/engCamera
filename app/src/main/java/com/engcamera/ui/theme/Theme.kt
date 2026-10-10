package com.engcamera.ui.theme

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
    surfaceVariant = Color(0xFF1E1E22),
    onPrimary = Color.White,
    onSurface = Color(0xFFE6E1E5),
    onBackground = Color(0xFFE6E1E5),
    onSurfaceVariant = Color(0xFFCAC4D0),
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
    content: @Composable () -> Unit,
) {
    // 相机类应用恒定暗色主题（用户确认 2026-10-11）：不随系统深浅色切换
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
