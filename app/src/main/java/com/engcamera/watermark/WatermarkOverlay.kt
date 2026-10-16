package com.engcamera.watermark

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 相机帧快照：等比缩到 ≤480 宽的自有位图。玻璃磨砂采样无需高分辨率——CPU 渲染走
 *  半分辨率卡片（480 恰好让其 1:1 采样），降采样越小后台管线每拍越轻（v168） */
private fun snapshotBackdrop(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val s = if (src.width > 480) 480f / src.width else 1f
    if (s < 1f) {
        return android.graphics.Bitmap.createScaledBitmap(
            src,
            (src.width * s).roundToInt().coerceAtLeast(1),
            (src.height * s).roundToInt().coerceAtLeast(1),
            true,
        )
    }
    return src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * 预览上的水印悬浮层：与成片使用同一渲染引擎，所见即所得。
 * 点击进入水印编辑器，拖动改变位置（Compose 触摸 slop 天然区分点击与拖动）。
 * scaleByShortSide：烧录缩放基准是画面短边（WatermarkRenderer.drawOnCanvas），
 * 横向图片（宽=长边）的预览也须按短边缩放，否则预览比成片大。
 * backdropProvider：液态玻璃模板的实时画面来源（标注模式静态图；每次调用返回当前画面
 * 位图，生命周期归实现方管理，本层只读不回收；返回同一实例视为画面未变化）。
 * glassSource：液态玻璃的实时采样源 ViewGroup（相机预览 PreviewView）——非空且
 * API 33+ 时用库的 LiquidGlassView GPU 实时渲染玻璃卡；否则回退 CPU 版/占位。
 * gravityRollDeg：当前量化重力角（0/±90/180）。锚点是重力参照系（「下方」=物理重力方向），
 * 预览画在屏幕坐标系，先绕中心转到屏幕系（WYSIWYG：横拍时锚点落到预览的物理下方侧）；
 * 画面本身就摆正的场景（添加水印模式的图片、编辑器）传 0，换算恒等。
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
    backdropProvider: (() -> android.graphics.Bitmap?)? = null,
    glassSource: android.view.ViewGroup? = null,
    gravityRollDeg: Float = 0f,
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
        val isLiquid = state.style.layout == "LIQUID_GLASS"
        // 玻璃卡 GPU 路径（库 LiquidGlassView 实时采样）。库的采样不感知视图旋转
        // （LiquidGlassimpl.record() 按窗口坐标差裁剪，旋转视图会错向/裁出源外），
        // 但玻璃卡是纯折射效果：透过内容=背后画面（与卡朝向无关）、形状=屏幕区域，
        // 「旋转 90° 倍数的卡」≡「未旋转、宽高互换、同中心的卡」——±90/180 用
        // 未旋转等价卡走 GPU（与竖屏同性能，用户拍板 2026-10-16），仅任意角度的
        // 手动旋转（非 90° 倍数，等价性不成立）回退 CPU 后台渲染路径
        val rotNorm = ((rotationDeg.roundToInt() % 360) + 360) % 360
        val useRealGlassView = isLiquid && glassSource != null &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            (rotNorm == 0 || rotNorm == 90 || rotNorm == 180 || rotNorm == 270)

        // 液态玻璃 CPU 回退路径不在组合期取帧——整条管线（取帧→降采样→渲染）在下方
        // 分支内的后台协程里跑，主线程只在成品就绪时替换一次位图（v168 性能重构）

        // 渲染结果只依赖字段/样式/动态文本与尺寸——与锚点无关（锚点每帧拖动会变，
        // 若作为 key 会导致每帧重建位图，拖动卡顿）。液态玻璃这里只出纯文本块。
        val scaleBasis = if (scaleByShortSide) minOf(widthPx, heightPx) else widthPx
        val block = remember(state.fields, state.style, effDynamic, widthPx, heightPx, scaleByShortSide, rotationDeg, webTick) {
            if (widthPx > 0) {
                // 渐变底栏拉满模式：块宽覆盖当前旋转方向上的画面整边（横屏=高边）
                val fullBar = WatermarkRenderer.isFullWidthBar(state)
                val override = if (fullBar) {
                    val vertical = ((rotationDeg.roundToInt() % 180 + 180) % 180) == 90
                    if (vertical) heightPx else widthPx
                } else {
                    null
                }
                WatermarkRenderer.render(state, scaleBasis, effDynamic, blockWidthOverride = override)
            } else {
                null
            }
        }

        // 网页模板：缓存未命中时触发后台渲染（完成经 tick 回来重组）
        // key 必须含 templateId：网页模板间切换时 fields/style 结构相等，效应不重启会漏渲（同 EditorScreen 预览 2026-10-14）
        LaunchedEffect(state.templateId, state.fields, state.style, effDynamic, widthPx, heightPx) {
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
            // 锚点：重力参照系 → 本浮层所在的屏幕参照系（见参数注释）
            val (anchorSx, anchorSy) = WatermarkRenderer.rotateAnchor(state.anchorX, state.anchorY, gravityRollDeg)
            // 齿轮占位两用：全字段关闭，或网页模板尚未渲染出图（可拖动=改锚点，点击=进编辑器）
            val blockReady = block != null && block.width > 1
            if (state.fields.none { it.enabled } || !blockReady) {                // 全部字段关闭：齿轮占位图标替代水印块（仅预览不烧录），
                // 位置=水印锚点（重新启用字段后水印出现在这里），点击进编辑器、可拖动
                val iconPx = with(density) { 52.dp.toPx() }.roundToInt()
                val (gl, gt) = WatermarkRenderer.positionBlock(
                    widthPx, heightPx, iconPx, iconPx,
                    anchorSx, anchorSy, rotationDeg,
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
            // 网页位图按固定宽渲染，这里缩放到画面宽 × webScale 显示（与成片一致）
            var dw = block.width
            var dh = block.height
            if (isWeb && block.width > 1 && scaleBasis > 0) {
                val f = WebTemplateRenderer.webDrawFraction(state) * scaleBasis / block.width
                dw = (block.width * f).roundToInt().coerceAtLeast(1)
                dh = (block.height * f).roundToInt().coerceAtLeast(1)
            }
            val (left, top) = WatermarkRenderer.positionBlock(
                widthPx, heightPx,
                dw, dh,
                anchorSx, anchorSy,
                rotationDeg,
                edgeToEdge = WatermarkRenderer.isFullWidthBar(state),
            )
            // 液态玻璃：文本块下面先铺玻璃卡——相机预览用库 LiquidGlassView（GPU 实时折射），
            // 标注模式/无源场景用 CPU 版（与库同公式）；编辑器缩略图等无采样时画占位示意
            if (isLiquid && dw > 1) {
                if (useRealGlassView) {
                    val gs = glassSource!!
                    val st = state.style
                    val glassScale = widthPx / 360f
                    // ±90°：未旋转等价卡——宽高互换、与旋转卡同中心（positionBlock 的旋转
                    // 包围盒即等价卡矩形，钳制后必在画面内）；不转（rotNorm 0/180 同样不转，
                    // 180° 的圆角矩形点对称，形状与采样均等价）
                    val swap = rotNorm == 90 || rotNorm == 270
                    val gw = (if (swap) dh else dw).coerceAtLeast(1)
                    val gh = (if (swap) dw else dh).coerceAtLeast(1)
                    val gl = if (swap) left + (dw - dh) / 2f else left
                    val gt = if (swap) top + (dh - dw) / 2f else top
                    androidx.compose.ui.viewinterop.AndroidView(
                        // 内置修复版（上游 updateConfig 竞态 NPE，启动即崩——见类注释）
                        factory = { ctx -> LiquidGlassViewSafe(ctx) },
                        update = { v ->
                            v.bind(gs)
                            // 圆角钳短边 4/5（与烧录同规则）
                            v.setCornerRadius(
                                (st.glassCornerRadius * glassScale).coerceAtMost(minOf(gw, gh) * 0.8f),
                            )
                            v.setRefractionHeight(st.glassRefractionHeight * glassScale)
                            v.setRefractionOffset(st.glassRefractionOffset * glassScale)
                            v.setDispersion(st.glassDispersion)
                            v.setBlurRadius(st.glassBlurRadius.coerceAtLeast(0.01f))
                            v.setTintAlpha(st.glassTintAlpha)
                            v.setTintColorRed(st.glassTintRed)
                            v.setTintColorGreen(st.glassTintGreen)
                            v.setTintColorBlue(st.glassTintBlue)
                        },
                        modifier = Modifier
                            .offset { IntOffset(gl.roundToInt(), gt.roundToInt()) }
                            .requiredSize(with(density) { gw.toDp() }, with(density) { gh.toDp() }),
                    )
                } else {
                    // CPU 版玻璃卡（旋转方向/无采样源）：取帧必须在主线程（TextureView 离开
                    // UI 线程取不到画面——v168 实测全黑，v167 在主线程取帧时玻璃正常渲染），
                    // 主线程只做取帧一件事；降采样、逐像素渲染、位图回收全在后台线程，
                    // 成品就绪才回主线程换一次位图。半分辨率渲染：磨砂无损观感、像素量 1/4。
                    var glassBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(
                        state.style, dw, dh, rotationDeg, left, top,
                        widthPx, heightPx, scaleByShortSide, backdropProvider,
                    ) {
                        val provider = backdropProvider ?: return@LaunchedEffect
                        val frames = Channel<android.graphics.Bitmap>(
                            1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        )
                        launch {
                            try {
                                while (true) {
                                    val fresh = runCatching { provider.invoke() }.getOrNull()
                                    if (fresh != null && !fresh.isRecycled) {
                                        frames.trySend(fresh)
                                    } else if (glassBmp == null) {
                                        // 无采样源：占位示意（淡底+描边），有图后自动换实采样
                                        glassBmp = runCatching {
                                            WatermarkRenderer.renderGlassCard(
                                                state, scaleBasis / 2, dw / 2, dh / 2, null, placeholder = true,
                                            )
                                        }.getOrNull()
                                    }
                                    delay(70)
                                }
                            } finally {
                                frames.close()
                            }
                        }
                        withContext(kotlinx.coroutines.Dispatchers.Default) {
                            var lastFresh: android.graphics.Bitmap? = null
                            var small: android.graphics.Bitmap? = null
                            var laggedOut: android.graphics.Bitmap? = null
                            try {
                                for (fresh in frames) {
                                    // 静态图（标注模式）同实例只处理一次；相机帧一拍一换
                                    if (fresh.isRecycled || fresh === lastFresh) continue
                                    val prev = lastFresh
                                    lastFresh = fresh
                                    val s = snapshotBackdrop(fresh)
                                    prev?.recycle() // 相机上一帧取完即还（~6MB/拍，不回收会 GC 风暴）
                                    small?.recycle()
                                    small = s
                                    val frame = WatermarkRenderer.FrameContext(
                                        s, widthPx / 2, heightPx / 2, rotationDeg, left / 2, top / 2,
                                    )
                                    val out = runCatching {
                                        WatermarkRenderer.renderGlassCard(
                                            state, scaleBasis / 2, dw / 2, dh / 2, frame,
                                        )
                                    }.getOrNull()
                                    if (out != null) {
                                        val oldOut = glassBmp
                                        glassBmp = out
                                        // 隔一代回收：主线程可能还在画上一张成品
                                        laggedOut?.recycle()
                                        laggedOut = oldOut
                                    }
                                }
                            } finally {
                                small?.recycle()
                                laggedOut?.recycle()
                                // lastFresh 不在此回收：标注模式的静态图归提供方所有，相机最后一帧交给 GC
                            }
                        }
                    }
                    DisposableEffect(Unit) {
                        onDispose { glassBmp?.recycle() }
                    }
                    glassBmp?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                                .requiredSize(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                                .graphicsLayer { rotationZ = rotationDeg },
                        )
                    }
                }
            }
            Image(
                bitmap = block.bitmap.asImageBitmap(),
                contentDescription = "水印",
                modifier = Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .requiredSize(with(density) { dw.toDp() }, with(density) { dh.toDp() })
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
}
