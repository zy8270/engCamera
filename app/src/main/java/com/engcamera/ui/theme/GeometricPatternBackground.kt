package com.engcamera.ui.theme

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb

/**
 * 编辑器预览的静态几何图案背景（用户拍板 2026-10-16）：扁平风"两座山"——
 * 两个大三角形堆叠，深/亮两套配色，替代纯色底。无动画（一次性绘制）。
 * renderPatternBitmap 为同几何的位图版，作液态玻璃的采样源（显示与折射一致）。
 */
@Composable
fun GeometricPatternBackground(modifier: Modifier = Modifier) {
    val dark = LocalAppDarkTheme.current
    Canvas(modifier) { drawMountains(dark) }
}

/** 山形三色（底/后山/前山） */
private fun mountains(dark: Boolean): Triple<Color, Color, Color> =
    if (dark) {
        Triple(Color(0xFF171A21), Color(0xFF232B3B), Color(0xFF2E3950))
    } else {
        Triple(Color(0xFFEFF2F8), Color(0xFFD8E2F0), Color(0xFFC2D1E7))
    }

private fun DrawScope.drawMountains(dark: Boolean) {
    val (base, back, front) = mountains(dark)
    drawRect(base)
    drawMountain(
        color = back,
        peakX = size.width * 0.70f,
        peakY = size.height * 0.22f,
        baseLeftX = size.width * 0.08f,
        baseRightX = size.width * 1.34f,
        baseY = size.height * 1.12f,
    )
    drawMountain(
        color = front,
        peakX = size.width * 0.28f,
        peakY = size.height * 0.52f,
        baseLeftX = -size.width * 0.18f,
        baseRightX = size.width * 0.96f,
        baseY = size.height * 1.18f,
    )
}

private fun DrawScope.drawMountain(
    color: Color,
    peakX: Float,
    peakY: Float,
    baseLeftX: Float,
    baseRightX: Float,
    baseY: Float,
) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(peakX, peakY)
        lineTo(baseRightX, baseY)
        lineTo(baseLeftX, baseY)
        close()
    }
    drawPath(path, color)
}

/** 位图版山形图案（液态玻璃采样源；调用方持有与回收） */
fun renderPatternBitmap(dark: Boolean, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val (base, back, front) = mountains(dark)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(base.toArgb())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun tri(color: Color, px: Float, py: Float, lx: Float, rx: Float, by: Float) {
        val path = Path().apply {
            moveTo(px, py)
            lineTo(rx, by)
            lineTo(lx, by)
            close()
        }
        paint.color = color.toArgb()
        canvas.drawPath(path, paint)
    }
    val wF = bmp.width.toFloat()
    val hF = bmp.height.toFloat()
    tri(back, wF * 0.70f, hF * 0.22f, wF * 0.08f, wF * 1.34f, hF * 1.12f)
    tri(front, wF * 0.28f, hF * 0.52f, -wF * 0.18f, wF * 0.96f, hF * 1.18f)
    return bmp
}
