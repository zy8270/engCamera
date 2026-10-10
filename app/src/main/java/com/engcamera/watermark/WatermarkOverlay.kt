package com.engcamera.watermark

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.engcamera.data.DynamicValues
import com.engcamera.data.WatermarkState
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 预览上的水印悬浮层：与成片使用同一渲染引擎，所见即所得。
 * 点击进入水印编辑器，拖动改变位置（Compose 触摸 slop 天然区分点击与拖动）。
 */
@Composable
fun WatermarkOverlay(
    state: WatermarkState,
    dynamic: DynamicValues,
    rotationDeg: Float,
    onTap: () -> Unit,
    onDrag: (dxNorm: Float, dyNorm: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.roundToInt()
        val heightPx = with(density) { maxHeight.toPx() }.roundToInt()

        // 状态、动态文本或宽度变化时重新渲染（时间每秒变化一次）
        val block = remember(state, dynamic, widthPx) {
            if (widthPx > 0) WatermarkRenderer.render(state, widthPx, dynamic) else null
        }

        if (block != null && widthPx > 0 && heightPx > 0) {
            val (left, top) = WatermarkRenderer.positionBlock(
                widthPx, heightPx,
                block.width, block.height,
                state.anchorX, state.anchorY,
                rotationDeg,
            )
            Image(
                bitmap = block.bitmap.asImageBitmap(),
                contentDescription = "水印",
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .graphicsLayer { rotationZ = rotationDeg }
                    // 单一手势源：位移超 slop 即拖动（本地增量按旋转角转回屏幕系，跟手方向不变），
                    // 未拖动抬手视为点击进入编辑器。避免 tap/drag 双检测器在旋转层互相干扰。
                    .pointerInput(rotationDeg) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var isDrag = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (!isDrag) onTap()
                                    break
                                }
                                val deltaLocal = change.position - change.previousPosition
                                if (!isDrag && deltaLocal.getDistance() > viewConfiguration.touchSlop) {
                                    isDrag = true
                                }
                                if (isDrag) {
                                    change.consume()
                                    // 本地坐标 -> 屏幕坐标（与 graphicsLayer rotationZ 同一变换）
                                    val rad = Math.toRadians(rotationDeg.toDouble())
                                    val sx = (deltaLocal.x * cos(rad) - deltaLocal.y * sin(rad)).toFloat()
                                    val sy = (deltaLocal.x * sin(rad) + deltaLocal.y * cos(rad)).toFloat()
                                    if (widthPx > 0 && heightPx > 0) {
                                        onDrag(sx / widthPx, sy / heightPx)
                                    }
                                }
                            }
                        }
                    },
            )
        }
    }
}
