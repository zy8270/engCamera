package com.engcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/**
 * 工程组品牌图标：三条横线，最下面一条末端带三角（表示一组配置的集合/切换）。
 * 编辑器悬浮按钮与相机页快捷按钮共用；线条粗细随尺寸等比缩放。
 */
@Composable
fun ProjectGroupGlyph(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        drawLine(color, Offset(0f, h * 0.15f), Offset(w * 0.85f, h * 0.15f), stroke)
        drawLine(color, Offset(0f, h * 0.45f), Offset(w * 0.85f, h * 0.45f), stroke)
        val y = h * 0.78f
        drawLine(color, Offset(0f, y), Offset(w * 0.55f, y), stroke)
        val tri = Path().apply {
            moveTo(w * 0.62f, y - h * 0.16f)
            lineTo(w * 0.62f, y + h * 0.16f)
            lineTo(w * 0.82f, y)
            close()
        }
        drawPath(tri, color)
    }
}
