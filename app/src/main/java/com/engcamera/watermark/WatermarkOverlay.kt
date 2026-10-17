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

/** 相机帧快照：等比缩到 ≤maxDim 宽的自有位图。玻璃磨砂采样无需高分辨率——CPU 渲染走
 *  半分辨率卡片（480 恰好让其 1:1 采样），降采样越小后台管线每拍越轻（v168）。
 *  v230：静态图（添加水印/编辑器/确认页）折射要跟烧录一致，走全分辨率（maxDim=1080），
 *  半分辨率是相机实时帧时代的优化——CPU 路径如今只服务静态底，一次性渲染无需省。 */
private fun snapshotBackdrop(src: android.graphics.Bitmap, maxDim: Int = 480): android.graphics.Bitmap {
    val s = if (src.width > maxDim) maxDim.toFloat() / src.width else 1f
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
    // false = 不挂手势（确认标注页：水印只是冻结的展示层，手势全部让给
    // 标注/文字工具——否则水印块 down.consume() 会吞掉其覆盖区域的
    // 点按，文字工具点在水印上无法添加文字）
    interactive: Boolean = true,
    // 水印块/齿轮占位在浮层坐标系内的包围盒回调（v226）：宿主（添加水印标注态）
    // 据此做长按命中判定——按住水印块才接管拖动移动水印；不需要时传 null
    onBlockBounds: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
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
                androidx.compose.runtime.SideEffect {
                    // 零矩形=无可长按拖动的水印块（长按命中判定恒 false）
                    onBlockBounds?.invoke(androidx.compose.ui.geometry.Rect.Zero)
                }
                val iconPx = with(density) { 52.dp.toPx() }.roundToInt()
                val (gl, gt) = WatermarkRenderer.positionBlock(
                    widthPx, heightPx, iconPx, iconPx,
                    anchorSx, anchorSy, rotationDeg,
                )
                Box(
                    Modifier
                        .offset { IntOffset(gl.roundToInt(), gt.roundToInt()) }
                        .graphicsLayer { rotationZ = rotationDeg }
                        .then(
                            if (interactive) Modifier.pointerInput(rotationDeg) {                                awaitEachGesture {
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
                            } else Modifier,
                        )
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
            // 上报水印块包围盒（旋转后的视觉包围盒矩形，供宿主长按命中判定，v226）；
            // ±90° 时卡片绕中心转了 90°，视觉包围盒宽高互换（v232 修：确认页横拍
            // 冻结旋转 +90 时旧上报矩形偏向一侧，长按命中区与所见卡片错位）
            androidx.compose.runtime.SideEffect {
                val swapped = ((rotationDeg.roundToInt() % 180) + 180) % 180 == 90
                val rect = if (swapped) {
                    val l = left + (dw - dh) / 2f
                    val t = top + (dh - dw) / 2f
                    androidx.compose.ui.geometry.Rect(l, t, l + dh, t + dw)
                } else {
                    androidx.compose.ui.geometry.Rect(left, top, left + dw, top + dh)
                }
                onBlockBounds?.invoke(rect)
            }
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
                            // 圆角=占短边百分比 0~50（50%=正半圆封顶，与烧录同规则）
                            v.setCornerRadius(
                                WatermarkRenderer.glassCornerPx(st.glassCornerRadius, gw, gh),
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
                    // CPU 版玻璃卡（旋转方向/无采样源）：v230 起全分辨率渲染——本路径
                    // 只服务静态底（添加水印图片/编辑器图案/确认页照片），一次性渲染
                    // 无需省；半分辨率+480 快照是相机实时帧时代的优化，会把折射带
                    // 糊没（用户实测"预览没有扭曲衍射、成片却正确"）。取帧仍须主线程
                    // （TextureView 离开 UI 线程取不到画面——v168），降采样、逐像素
                    // 渲染、位图回收全在后台线程，成品就绪才回主线程换一次位图。
                    var glassBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    // v232：left/top 不再作为 LaunchedEffect key，经 rememberUpdatedState
                    // 读最新值——拖动水印时锚点逐帧变化，作为 key 会每帧重启整条管线
                    // （取消旧协程+起新协程+新 Channel，多个僵尸渲染并发写 glassBmp），
                    // 真机表现为拖动后玻璃卡停在占位/陈旧帧、折射色散消失。现在管线按
                    // （样式/尺寸/采样源）长存，轮询发现位置变化就在原协程里以当前
                    // left/top 重渲一张（70ms 一拍 ≈14fps，静态底完全够跟手）
                    val leftCur by androidx.compose.runtime.rememberUpdatedState(left)
                    val topCur by androidx.compose.runtime.rememberUpdatedState(top)
                    LaunchedEffect(
                        state.style, block, dw, dh, rotationDeg,
                        widthPx, heightPx, scaleByShortSide, backdropProvider,
                    ) {
                        val provider = backdropProvider ?: return@LaunchedEffect
                        val frames = Channel<android.graphics.Bitmap>(
                            1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        )
                        launch {
                            try {
                                var nullPolls = 0
                                while (true) {
                                    val fresh = runCatching { provider.invoke() }.getOrNull()
                                    if (fresh != null && !fresh.isRecycled) {
                                        if (nullPolls > 0) {
                                            android.util.Log.w("GlassDbg", "provider recovered after $nullPolls null polls")
                                        }
                                        nullPolls = 0
                                        frames.trySend(fresh)
                                    } else if (glassBmp == null) {
                                        nullPolls++
                                        if (nullPolls == 1 || nullPolls % 50 == 0) {
                                            android.util.Log.w("GlassDbg", "provider null x$nullPolls → placeholder card")
                                        }
                                        // 无采样源：占位示意（淡底+描边），有图后自动换实采样
                                        glassBmp = runCatching {
                                            WatermarkRenderer.renderGlassCard(
                                                state, scaleBasis, dw, dh, null, placeholder = true,
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
                            var lastLeft = Float.NaN
                            var lastTop = Float.NaN
                            var small: android.graphics.Bitmap? = null
                            try {
                                for (fresh in frames) {
                                    if (fresh.isRecycled) continue
                                    // 静态图（标注/编辑器/确认页）同实例只处理一次；相机帧一拍一换；
                                    // 拖动水印改 left/top 也触发重渲（采样区随卡片挪动）
                                    val posChanged = leftCur != lastLeft || topCur != lastTop
                                    if (fresh === lastFresh && !posChanged) continue
                                    lastFresh = fresh
                                    lastLeft = leftCur
                                    lastTop = topCur
                                    // v230：静态底全分辨率采样（上限 1080，与编辑器截帧同量级）。
                                    // 快照包 runCatching：提供方位图可能被宿主并发回收（确认页
                                    // 照片保存/丢弃即回收），失败跳过本拍等下一轮，不杀管线
                                    val s = runCatching { snapshotBackdrop(fresh, maxDim = 1080) }
                                        .getOrNull() ?: continue
                                    // 提供方帧一律不在这里回收：静态图归提供方所有（跨图切换时
                                    // 宿主已回收，重复回收无害但多余）；相机路径提供方自己在
                                    // 下一次采样时回收上一帧（PreviewArea backdropProvider）
                                    small?.recycle()
                                    small = s
                                    val frame = WatermarkRenderer.FrameContext(
                                        s, widthPx, heightPx, rotationDeg, leftCur, topCur,
                                    )
                                    val render = runCatching {
                                        WatermarkRenderer.renderGlassCard(
                                            state, scaleBasis, dw, dh, frame,
                                        )
                                    }
                                    if (render.isFailure) {
                                        android.util.Log.w(
                                            "GlassDbg",
                                            "renderGlassCard failed card=${dw}x$dh",
                                            render.exceptionOrNull(),
                                        )
                                    }
                                    // 玻璃卡成品不做任何手工回收（含替换与退出）：滑杆拖动会高频
                                    // 重启渲染效应，隔代回收会在主线程仍在绘制时把位图回收掉
                                    // （真机崩溃实锤 2026-10-16 "Canvas: trying to use a recycled
                                    // bitmap"）；成品位图量小，交给 GC
                                    val out = render.getOrNull()
                                    if (out != null) glassBmp = out
                                }
                            } finally {
                                small?.recycle()
                                // lastFresh 不在此回收：静态图归提供方所有，相机最后一帧交给 GC
                            }
                        }
                    }
                    DisposableEffect(Unit) {
                        onDispose { } // glassBmp 交给 GC（同上：手工回收有绘制竞态）
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
                    .then(
                        if (interactive) Modifier.pointerInput(rotationDeg) {
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
                        } else Modifier,
                    ),
            )
            }
        }
    }
}
