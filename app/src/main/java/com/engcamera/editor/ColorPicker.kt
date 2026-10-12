package com.engcamera.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 自由取色对话框：RGBA 四通道滑杆 + 十六进制输入（#RRGGBB / #AARRGGBB），
 * 棋盘格预览直观展示半透明效果。项目内所有取色入口共用。
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var color by remember { mutableStateOf(initial) }
    var hexText by remember { mutableStateOf(formatHex(initial)) }

    fun setArgb(rr: Int, gg: Int, bb: Int, aa: Int) {
        color = ((aa.toLong() and 0xFF) shl 24) or
            ((rr.toLong() and 0xFF) shl 16) or
            ((gg.toLong() and 0xFF) shl 8) or
            (bb.toLong() and 0xFF)
        hexText = formatHex(color)
    }

    val a = ((color ushr 24) and 0xFF).toInt()
    val r = ((color ushr 16) and 0xFF).toInt()
    val g = ((color ushr 8) and 0xFF).toInt()
    val b = (color and 0xFF).toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            .background(Color(color)),
                    )
                }
                ChannelRow("R", r) { setArgb(it, g, b, a) }
                ChannelRow("G", g) { setArgb(r, it, b, a) }
                ChannelRow("B", b) { setArgb(r, g, it, a) }
                ChannelRow("透明度", a) { setArgb(r, g, b, it) }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { s ->
                        hexText = s.take(9)
                        parseHex(s)?.let { color = it }
                    },
                    label = { Text("#RRGGBB / #AARRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChannelRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$value",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

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

private fun formatHex(c: Long): String = "#%08X".format(c)

private fun parseHex(s: String): Long? {
    val v = s.trim().removePrefix("#")
    if (v.isEmpty() || v.length > 8) return null
    if (!v.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) return null
    val n = v.toLongOrNull(16) ?: return null
    return if (v.length == 6) 0xFF000000L or n else if (v.length == 8) n else null
}
