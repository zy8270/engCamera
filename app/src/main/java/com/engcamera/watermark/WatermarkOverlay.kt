package com.engcamera.watermark

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.engcamera.data.DynamicValues
import com.engcamera.data.WatermarkState
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 预览上的水印悬浮层：与成片使用同一渲染引擎，所见即所得。
 * 点击进入水印编辑器，拖动改变位置（Compose 触摸 slop 天然区分点击与拖动）。
 * scaleByShortSide：烧录缩放基准是画面短边（WatermarkRenderer.drawOnCanvas），
 * 横向图片（宽=长边）的预览也须按短边缩放，否则预览比成片大。
 */
@Composable
fun WatermarkOverlay(
    state: WatermarkState,
    dynamic: DynamicValues,
    rotationDeg: Float,
    onTap: () -> Unit,
    onDrag: (dxNorm: Float, dyNorm: Float) -> Unit,
    modifier: Modifier = Modifier,
    scaleByShortSide: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.roundToInt()
        val heightPx = with(density) { maxHeight.toPx() }.roundToInt()
        val context = androidx.compose.ui.platform.LocalContext.current
        // 网页模板渲染完成会 bump tick，触发重组取新缓存位图
        val webTick by WebTemplateRenderer.tick.collectAsState()
        // 网页模板预览用"时间截断到分钟"的动态值（缓存键稳定，不每秒重渲 WebView）；
        // 成片烧录走真实时间，不受影响
        val isWeb = com.engcamera.data.WatermarkTemplates.find(state.templateId)
            ?.backend == com.engcamera.data.TemplateBackend.WEB
        val effDynamic = remember(dynamic, isWeb) {
            if (isWeb) WebTemplateRenderer.previewDynamic(dynamic) else dynamic
        }

        // 渲染结果只依赖字段/样式/动态文本与尺寸——与锚点无关（锚点每帧拖动会变，
        // 若作为 key 会导致每帧重建位图，拖动卡顿）
        val block = remember(state.fields, state.style, effDynamic, widthPx, heightPx, scaleByShortSide, webTick) {
            if (widthPx > 0) {
                val scaleBasis = if (scaleByShortSide) minOf(widthPx, heightPx) else widthPx
                WatermarkRenderer.render(state, scaleBasis, effDynamic)
            } else {
                null
            }
        }

        // 网页模板：缓存未命中时触发后台渲染（完成经 tick 回来重组）
        LaunchedEffect(state.fields, state.style, effDynamic, widthPx, heightPx) {
            if (widthPx > 0) {
                val key = WatermarkRenderer.webCacheKey(state, effDynamic, widthPx)
                if (key != null && WebTemplateRenderer.current(key) == null) {
                    com.engcamera.data.WatermarkTemplates.find(state.templateId)?.let { tpl ->
                        WebTemplateRenderer.warmAsync(context, key, tpl, state, effDynamic, widthPx)
                    }
                }
            }
        }

        if (widthPx > 0 && heightPx > 0) {
            // 齿轮占位两用：全字段关闭，或网页模板尚未渲染出图（可拖动=改锚点，点击=进编辑器）
            val blockReady = block != null && block.width > 1
            if (state.fields.none { it.enabled } || !blockReady) {
                // 全部字段关闭：齿轮占位图标替代水印块（仅预览不烧录），
                // 位置=水印锚点（重新启用字段后水印出现在这里），点击进编辑器、可拖动
                val iconPx = with(density) { 52.dp.toPx() }.roundToInt()
                val (gl, gt) = WatermarkRenderer.positionBlock(
                    widthPx, heightPx, iconPx, iconPx,
                    state.anchorX, state.anchorY, rotationDeg,
                )
                Box(
                    Modifier
                        .offset { IntOffset(gl.roundToInt(), gt.roundToInt()) }
                        .graphicsLayer { rotationZ = rotationDeg }
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
                                        val rad = Math.toRadians(rotationDeg.toDouble())
                                        val sx = (deltaLocal.x * cos(rad) - deltaLocal.y * sin(rad)).toFloat()
                                        val sy = (deltaLocal.x * sin(rad) + deltaLocal.y * cos(rad)).toFloat()
                                        if (widthPx > 0 && heightPx > 0) {
                                            onDrag(sx / widthPx, sy / heightPx)
                                        }
                                    }
                                }
                            }
                        }
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "水印设置",
                        tint = Color(0xB3FFFFFF),
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else if (block != null) {
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
                            android.util.Log.d("WmTap", "gesture down")
                            var isDrag = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    android.util.Log.d("WmTap", "up isDrag=$isDrag onTap=${onTap !== null}")
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
}
