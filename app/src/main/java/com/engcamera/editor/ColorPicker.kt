package com.engcamera.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 自由取色对话框（可视化）：色相条 + 饱和度/明度二维窗口 + 透明度拉杆 + 颜色代码输入，
 * 棋盘格预览直观展示半透明。项目内所有取色入口共用。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    // HSV + Alpha 状态（hue 0..360，sat/val/alpha 0..1）
    val initHsv = FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toInt(), it) }
    var hue by remember { mutableFloatStateOf(initHsv[0]) }
    var sat by remember { mutableFloatStateOf(initHsv[1]) }
    var value by remember { mutableFloatStateOf(initHsv[2]) }
    var alpha by remember { mutableFloatStateOf(((initial ushr 24) and 0xFF) / 255f) }
    var hexText by remember { mutableStateOf(formatHex(initial)) }

    fun currentColor(): Color = Color.hsv(hue, sat, value, alpha)
    fun syncHex() {
        val argb = currentColor().toArgb()
        hexText = formatHex(argb.toLong() and 0xFFFFFFFFL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 半透明预览：棋盘格上叠当前色
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Checkerboard(Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(currentColor()),
                    )
                }
                // 饱和度/明度二维窗口（背景 = 当前色相从白到黑的全谱）
                SvWindow(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onChange = { s, v ->
                        sat = s
                        value = v
                        syncHex()
                    },
                )
                // 色相条
                HueBar(
                    hue = hue,
                    onChange = { hue = it; syncHex() },
                )
                // 透明度拉杆（棋盘格上从全透明拉到当前色相纯色）
                AlphaBar(
                    hue = hue,
                    sat = sat,
                    value = value,
                    alpha = alpha,
                    onChange = {
                        alpha = it
                        syncHex()
                    },
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { s ->
                        hexText = s.take(9)
                        parseHex(s)?.let { argb ->
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(argb.toInt(), hsv)
                            hue = hsv[0]
                            sat = hsv[1]
                            value = hsv[2]
                            alpha = ((argb ushr 24) and 0xFF) / 255f
                        }
                    },
                    label = { Text("#AARRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { s ->
                        hexText = s.take(9)
                        parseHex(s)?.let { argb ->
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(argb.toInt(), hsv)
                            hue = hsv[0]
                            sat = hsv[1]
                            value = hsv[2]
                            alpha = ((argb ushr 24) and 0xFF) / 255f
                        }
                    },
                    label = { Text("#AARRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(currentColor().toArgb().toLong() and 0xFFFFFFFFL)
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatHex(c: Long): String = "#%08X".format(c)

private fun parseHex(s: String): Long? {
    val v = s.trim().removePrefix("#")
    if (v.isEmpty() || v.length > 8) return null
    if (!v.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) return null
    val n = v.toLongOrNull(16) ?: return null
    return if (v.length == 6) 0xFF000000L or n else if (v.length == 8) n else null
}

/** 棋盘格（透明底视觉参照） */
@Composable
private fun Checkerboard(modifier: Modifier) {
    Canvas(modifier) {
        val cell = 8.dp.toPx()
        val nx = (size.width / cell).toInt() + 1
        val ny = (size.height / cell).toInt() + 1
        for (y in 0..ny) {
            for (x in 0..nx) {
                drawRect(
                    color = if ((x + y) % 2 == 0) Color(0xFFD8D8DC) else Color(0xFFF4F4F6),
                    topLeft = Offset(x * cell, y * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

/** 色相/透明度条上拖动取值：横条统一处理（y 固定取条中线） */

/** 饱和度/明度二维窗口：背景 = 当前色相的白→纯色→黑全谱，圆圈光标 */
@Composable
private fun SvWindow(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (s: Float, v: Float) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(pos: Offset) {
                        if (size.width <= 0) return
                        val s = (pos.x / size.width).coerceIn(0f, 1f)
                        val v = 1f - (pos.y / size.height).coerceIn(0f, 1f)
                        onChange(s, v)
                    }
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pure = Color.hsv(hue, 1f, 1f)
            drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        }
        // 光标：白圈+黑描边
        val cx = size.width * sat
        val cy = size.height * (1f - value)
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(cx, cy))
            drawCircle(Color.Black, radius = 7.dp.toPx(), center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
    }
}

/** 色相条：全谱渐变，光标为竖线 */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(pos: Offset) {
                        if (size.width <= 0) return
                        onChange((pos.x / size.width * 360f).coerceIn(0f, 360f))
                    }
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(
                        Color.Red,
                        Color(0xFFFFFF00),
                        Color.Green,
                        Color(0xFF00FFFF),
                        Color.Blue,
                        Color(0xFFFF00FF),
                        Color.Red,
                    ),
                ),
            )
            val x = (hue / 360f) * size.width
            drawRect(
                Color.Transparent,
                topLeft = Offset(x - 2f, 0f),
                size = Size(4f, size.height.toFloat()),
            )
            drawLine(
                Color.Black,
                start = Offset(x, 0f),
                end = Offset(x, size.height.toFloat()),
                strokeWidth = 1.5f,
            )
        }
    }
}

/** 透明度拉杆：棋盘格上从全透明到当前色相纯色的渐变，光标为竖线 */
@Composable
private fun AlphaBar(
    hue: Float,
    sat: Float,
    value: Float,
    alpha: Float,
    onChange: (Float) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .onSizeChanged { size = it }
            .pointerInput(hue, sat, value) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun update(pos: Offset) {
                        if (size.width <= 0) return
                        onChange((pos.x / size.width).coerceIn(0f, 1f))
                    }
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            CheckerboardDraw(this)
            val pure = Color.hsv(hue, 1f, 1f)
            drawRect(
                Brush.horizontalGradient(
                    listOf(pure.copy(alpha = 0f), pure),
                ),
            )
            val x = alpha * size.width
            drawLine(
                Color.Black,
                start = Offset(x, 0f),
                end = Offset(x, size.height.toFloat()),
                strokeWidth = 1.5f,
            )
        }
    }
}

private fun CheckerboardDraw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    val px = with(scope) { 6.dp.toPx() }
    val nx = (scope.size.width / px).toInt() + 1
    val ny = (scope.size.height / px).toInt() + 1
    for (y in 0..ny) {
        for (x in 0..nx) {
            scope.drawRect(
                color = if ((x + y) % 2 == 0) Color(0xFFCCCCCC) else Color(0xFFF0F0F0),
                topLeft = Offset(x * px, y * px),
                size = androidx.compose.ui.geometry.Size(px, px),
            )
        }
    }
}
