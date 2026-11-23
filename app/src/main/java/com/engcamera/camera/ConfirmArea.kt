package com.engcamera.camera

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.engcamera.data.DynamicValues
import com.engcamera.data.WatermarkState
import com.engcamera.i18n.I18n
import com.engcamera.ui.theme.LocalAppDarkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 拍照后确认区（v193，用户拍板 2026-10-16）：绿描边外圈 + 照片缩小贴边显示，
 * 手势标注（箭头/矩形/笔刷/文字），右上角逐笔撤销；水印以快门瞬间冻结的状态
 * 叠加显示（时间/位置定格，v195）。层级（自下而上）：照片 → 水印 → 笔画 → 文字元素
 * → 手势层——标注盖在水印之上（与保存一致）。
 * v225：画框与标注栈抽为 [AnnotationFrame]，与「添加水印」模式共用。
 */
@Composable
internal fun ConfirmArea(
    modifier: Modifier,
    vm: CameraViewModel,
    ui: CameraUiState,
    effective: WatermarkState,
    dynamic: DynamicValues,
    // 标注期间进水印编辑器（用户需求 2026-11-03）：点左下角编辑按钮跳编辑器
    onOpenEditor: () -> Unit,
) {
    val photo = ui.confirmBitmap
    // 照片框比例跟随真实纵横比（v210）：横拍=横向 4:3，照片不压扁；无图兜底 3:4
    val photoAr = if (photo != null && !photo.isRecycled && photo.width > 0 && photo.height > 0) {
        photo.width.toFloat() / photo.height.toFloat()
    } else {
        3f / 4f
    }
    // 白框恒 3:4 满槽（v212，用户拍板）：底部按钮组固定不动；照片在框内按真实
    // 比例居中（横拍上下留空），双指放大 ≥ 框高/照高 比例时恰好填满整框
    // 外槽底色=主题背景（v221）：与快门按钮周围同色（日间近白/夜间深），不再是深色底
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 冻结的水印状态/时间（快门瞬间定格，v195）。v0.6.15：标注期间进过编辑器
        // 的话，内容/样式/模板跟编辑后的仓库走（mergeEditedWatermark），位置/旋转/
        // 动态值保持定格。仓库状态用 collectAsState 收集（响应式）——编辑器返回后
        // 自动重组拿到新内容
        val repoWm by vm.watermark.collectAsState()
        val frozenWm = ui.confirmWatermark
            ?.let { base -> if (ui.confirmEditWatermark) vm.mergeEditedWatermark(base, repoWm) else base }
            ?: effective
        val frozenDyn = ui.confirmDynamic ?: dynamic
        // 水印块包围盒（v232 确认页也接长按拖动：WatermarkOverlay 上报，
        // AnnotationFrame 命中判定用；拖动只改冻结态 confirmWatermark）
        var wmBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        // 采样 provider 必须实例稳定：拖动水印时 ui 逐帧更新，内联 lambda 每次重组
        // 都是新实例会让玻璃渲染管线逐帧重启（v232 同 WatermarkOverlay 管线重构）
        val photoProvider = remember(photo) { { photo } }
        AnnotationFrame(
            hasPhoto = photo != null && !photo.isRecycled,
            photoAr = photoAr,
            ui = ui,
            vm = vm,
            modifier = Modifier.fillMaxSize(),
            watermarkBounds = { wmBounds },
            onWatermarkDrag = { dx, dy -> vm.onDragConfirmWatermark(dx, dy) },
            // 标注期间进水印编辑器（v0.6.15 用户需求）：改的是快门瞬间冻结的水印。
            // 置编辑标志；返回后 ConfirmArea 显示与 confirmSave 烧录都经
            // mergeEditedWatermark 合并（内容/样式/模板跟编辑器保存后的仓库走，
            // 位置/旋转/时间动态值保持快门定格）
            onEditWatermark = {
                vm.onEnterEditWatermarkFromConfirm()
                onOpenEditor()
            },
        ) {
            if (photo != null && !photo.isRecycled) {
                // remember 化：否则每次重组 asImageBitmap() 都新建实例 → 纹理反复重传
                val photoBmp = remember(photo) { photo.asImageBitmap() }
                Image(
                    bitmap = photoBmp,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                // 水印浮层（冻结状态）：置于笔画之下（标注盖水印，与保存层级一致）。
                // 与烧录 drawOnCanvas(gravityAnchor=true) 完全同构（v210）：确认页照片
                // 正立显示（图片系=重力系），锚点直接落位（gravityRollDeg=0）；水印块
                // 旋转按快门时刻 frameRotation 补偿（横拍/倒置文字在成片里摆正，
                // 水印回到照片正下方）；缩放基准取短边（横拍成片宽=长边，与烧录
                // minOf(w,h) 一致，否则预览比成片大）。长按水印块可拖动改位（v232，
                // 保存用它烧录；拖动期间玻璃卡经轮询按新位置重渲采样）
                com.engcamera.watermark.WatermarkOverlay(
                    state = frozenWm,
                    dynamic = frozenDyn,
                    rotationDeg = frozenWm.rotationDeg + ui.confirmFrameRotation,
                    onTap = { },
                    onDrag = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    backdropProvider = photoProvider,
                    gravityRollDeg = 0f,
                    scaleByShortSide = true,
                    // 手势全让给标注/文字工具（水印块自带手势层会吞掉其覆盖区域的点按）
                    interactive = false,
                    onBlockBounds = { wmBounds = it },
                )
            }
        }
    }
}

/**
 * 确认页与「添加水印」共用的标注画框（v225 自 ConfirmArea 抽出，手势逻辑原样保留）：
 * 圆角描边框 + PS 式透明格底（v221）。hasPhoto 时框内按 photoAr 真实比例居中一个
 * 「照片盒」（位于双指缩放层内，[photoContent] 在盒内渲染照片与水印浮层），盒上依次
 * 叠：笔画层 → 文字元素层 → 手势层（单指=画标注/文字工具点按加字；双指=捏合缩放+
 * 平移），右上角逐笔撤销。空态（hasPhoto=false）只剩画框+格子，供调用方叠选图入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnnotationFrame(
    hasPhoto: Boolean,
    photoAr: Float,
    ui: CameraUiState,
    vm: CameraViewModel,
    modifier: Modifier = Modifier,
    // false=框内用主题底色（添加水印空态：+与提示文字在格子上可读性差，v226），
    // true=PS 式透明格（默认）
    showChecker: Boolean = true,
    // 空态（showChecker=false）自定义底色：添加水印空态日间 DFE3EB（用户需求
    // 2026-10-17）；null=主题背景（原行为，其他调用方不受影响）
    emptyBase: Color? = null,
    // 添加水印标注态/拍照确认页长按拖水印（v226/v232）：watermarkBounds=水印块在
    // 照片盒坐标系内的包围盒（WatermarkOverlay.onBlockBounds 上报）；
    // onWatermarkDrag=接管后的拖动回调（照片盒归一化增量）。不传（null）→
    // 完全无长按逻辑，行为不变
    watermarkBounds: () -> androidx.compose.ui.geometry.Rect? = { null },
    onWatermarkDrag: ((dxNorm: Float, dyNorm: Float) -> Unit)? = null,
    // 标注期间进水印编辑器（用户需求 2026-11-03）：非 null 时白框左下角显示编辑
    // 按钮，点击回调（拍照确认页重新冻结水印/添加水印页直接进编辑器，见调用方）。
    // null = 不显示该按钮（其他调用方行为不变）。
    // 注意：必须放在 photoContent 之前——尾随 lambda 绑参数表末位
    onEditWatermark: (() -> Unit)? = null,
    photoContent: @Composable () -> Unit = {},
) {
    // 白框内底色=PS 式透明格子（v221）：照片未铺满的留空区（横拍上下留白）
    // 不再是纯黑——纯黑会被误读成可标注区域；日间灰白格、夜间贴近主题底色的
    // 暗格。照片铺满/放大后格子被完全盖住，与"图层透明区"语义一致
    val darkTheme = LocalAppDarkTheme.current
    val checkerBase = if (darkTheme) Color(0xFF171A1F) else Color.White
    val checkerAlt = if (darkTheme) Color(0xFF22262D) else Color(0xFFCCCCCC)
    // 空态底色走主题背景（+号/提示文字对比度更好，v226）；可传 emptyBase 覆盖
    val frameBase = if (showChecker) checkerBase else (emptyBase ?: MaterialTheme.colorScheme.background)
    // 手势层闭包读最新值（rememberUpdatedState 须在组合上下文，v226）
    val wmBoundsCur by androidx.compose.runtime.rememberUpdatedState(watermarkBounds)
    val wmDragCur by androidx.compose.runtime.rememberUpdatedState(onWatermarkDrag)
    // 长按拖水印触发震动（v230）：震一下告诉用户"水印现在可以拖动"
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        modifier
            .padding(10.dp)
            // clip 先于 border 且同半径（v231 修四角溢出）：内容裁到外缘曲线，描边
            // （向内 3dp、画在上层）恰好盖住 [内缘..外缘] 环带——照片角不会露出
            // 描边内缘之外（旧序 border→clip(10dp) 会留 1dp 角缝）
            .clip(RoundedCornerShape(12.dp))
            .border(3.dp, Color(0xFFC2C6CE), RoundedCornerShape(12.dp))
            .background(frameBase),
    ) {
        // 透明格：底色之上、照片之下（被照片盖住的部分自然不可见）
        if (showChecker) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 10.dp.toPx()
                var row = 0
                var y = 0f
                while (y < size.height) {
                    var col = 0
                    var x = 0f
                    while (x < size.width) {
                        if ((row + col) % 2 == 1) {
                            drawRect(checkerAlt, Offset(x, y), Size(step, step))
                        }
                        x += step
                        col++
                    }
                    y += step
                    row++
                }
            }
        }
        if (hasPhoto) {
            val frameW = constraints.maxWidth.toFloat()
            val frameH = constraints.maxHeight.toFloat()
            // 照片盒：真实比例、容纳于白框内（一边贴满、居中）；标注/水印/
            // 文字/手势的坐标系全以此为准（归一化=照片系，与烧录一致）
            val parentW: Float
            val parentH: Float
            if (frameW / frameH > photoAr) {
                parentH = frameH
                parentW = frameH * photoAr
            } else {
                parentW = frameW
                parentH = frameW / photoAr
            }
            var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
            val isTextTool = ui.annotationTool == 3
            // 双指缩放/平移（用户需求 2026-10-16）：单指仍是标注/文字操作，双指捏合
            // 放大预览（照片+水印+标注+文字整栈一起变换，坐标恒对齐）
            var zoom by remember { mutableStateOf(1f) }
            var pan by remember { mutableStateOf(Offset.Zero) } // 父坐标系像素
            var zoomAnimJob by remember { mutableStateOf<Job?>(null) }
            val scope = rememberCoroutineScope()
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
            ) {
                // 照片盒（真实比例、白框内居中）：缩放容器满框、内容照片比例，
                // 中心重合 → 均匀缩放对两者同锚
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            with(LocalDensity.current) {
                                DpSize(Dp(parentW / density), Dp(parentH / density))
                            },
                        ),
                ) {
                    photoContent()

                    // 笔画层（已确认 + 进行中）
                    Canvas(Modifier.fillMaxSize()) {
                        // 每笔线宽=基础宽÷该笔落笔时的缩放：放大时画的笔画永久更细
                        // （显示与烧录一致），进行中的笔画用当前缩放
                        val baseW = size.width * 0.012f
                        fun c(v: Long) = Color(v.toInt())
                        fun drawAnn(tool: Int, color: Long, raw: List<Offset>, w: Float) {
                            if (raw.size < 2) return
                            val pts = raw.map { Offset(it.x * size.width, it.y * size.height) }
                            val p0 = pts.first()
                            val p1 = pts.last()
                            when (tool) {
                                0 -> {
                                    drawLine(c(color), p0, p1, strokeWidth = w)
                                    val angle = atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())
                                    val head = w * 4f
                                    for (da in listOf(PI_6, -PI_6)) {
                                        val ax = (p1.x - head * cos(angle + da)).toFloat()
                                        val ay = (p1.y - head * sin(angle + da)).toFloat()
                                        drawLine(c(color), p1, Offset(ax, ay), strokeWidth = w)
                                    }
                                }
                                1 -> {
                                    drawRect(
                                        c(color),
                                        topLeft = Offset(minOf(p0.x, p1.x), minOf(p0.y, p1.y)),
                                        size = Size(abs(p1.x - p0.x), abs(p1.y - p0.y)),
                                        style = Stroke(w),
                                    )
                                }
                                else -> {
                                    val path = Path()
                                    path.moveTo(pts.first().x, pts.first().y)
                                    pts.drop(1).forEach { path.lineTo(it.x, it.y) }
                                    drawPath(path, c(color), style = Stroke(w))
                                }
                            }
                        }
                        ui.annotations.forEach { drawAnn(it.tool, it.color, it.points, baseW / it.strokeScale.coerceAtLeast(1f)) }
                        if (live.size > 1 && !isTextTool) {
                            drawAnn(ui.annotationTool, ui.annotationColor, live, baseW / zoom.coerceAtLeast(1f))
                        }
                    }

                    // 手势层（在文字元素层之下）：Compose 重叠兄弟命中是单分支，文字
                    // 工具时元素/手柄在其上优先接事件，空区域落到本层。单指=画标注
                    // （非文字工具，越 touch slop 起笔）或点按加字（文字工具，未越
                    // slop 抬手）；双指=捏合缩放+平移（松到一指仍可平移）；捏合起手
                    // 时取消误起笔的笔画
                    var editing by remember { mutableStateOf<CameraUiState.TextItem?>(null) }
                    var textDialogAt by remember { mutableStateOf<Offset?>(null) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(ui.annotationTool, ui.annotationColor, isTextTool) {
                                // viewConfiguration.pointerTouchSlop 在此 BOM 解析不到，用 dp 近似
                                val slop = 18.dp.toPx()
                                fun centroid(ps: List<androidx.compose.ui.input.pointer.PointerInputChange>): Offset {
                                    var x = 0f; var y = 0f
                                    ps.forEach { x += it.position.x; y += it.position.y }
                                    return Offset(x / ps.size, y / ps.size)
                                }
                                fun avgDist(ps: List<androidx.compose.ui.input.pointer.PointerInputChange>): Float {
                                    var d = 0f; var n = 0
                                    for (i in 0 until ps.size - 1) for (j in i + 1 until ps.size) {
                                        d += hypot(
                                            (ps[i].position.x - ps[j].position.x).toDouble(),
                                            (ps[i].position.y - ps[j].position.y).toDouble(),
                                        ).toFloat(); n++
                                    }
                                    return if (n > 0) d / n else 1f
                                }
                                awaitEachGesture {
                                    val first = awaitFirstDown(requireUnconsumed = false)
                                    val start = first.position
                                    var drawing = false
                                    var isPinch = false
                                    if (!isTextTool) {
                                        live = listOf(
                                            Offset(
                                                (start.x / size.width).coerceIn(0f, 1f),
                                                (start.y / size.height).coerceIn(0f, 1f),
                                            ),
                                        )
                                    }
                                    // 添加水印标注态长按拖水印（v230 重写）：按住水印块且
                                    // 纹丝不动满系统长按时长才接管拖动，触发瞬间震动提示
                                    // 「水印可以拖了」；期间累计位移超阈值（慢滑也算滑动）
                                    // 立即放弃回画笔流程——修 v226 awaitLongPressOrCancellation
                                    // 对未消费移动不取消、慢滑被误判成长按的问题。确认页
                                    // watermarkBounds=null 完全跳过。计时放子协程：静止手指
                                    // 不产生事件，手势循环内测不了超时（v126 教训同源）
                                    var wmDragging = false
                                    if (wmDragCur != null && wmBoundsCur()?.contains(start) == true) {
                                        var fired = false
                                        var moved = 0f
                                        val lpCancel = 4.dp.toPx() // 取消阈值：慢滑必超，静止抖动不会
                                        val timer = scope.launch {
                                            delay(viewConfiguration.longPressTimeoutMillis)
                                            fired = true
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val ch = event.changes.firstOrNull { it.id == first.id } ?: break
                                                if (!ch.pressed || ch.isConsumed) break
                                                // positionChange() 扩展在此 BOM 部分上下文解析失败，
                                                // 用成员属性手算位移（工程坑记录）
                                                moved += hypot(
                                                    ch.position.x - ch.previousPosition.x,
                                                    ch.position.y - ch.previousPosition.y,
                                                )
                                                if (moved > lpCancel) break // 在滑=拉出箭头，放弃长按
                                                if (fired) {
                                                    wmDragging = true
                                                    live = emptyList()
                                                    drawing = false
                                                    break
                                                }
                                            }
                                        } finally {
                                            timer.cancel()
                                        }
                                    }
                                    var appliedDist = 0f // 上次已应用的指距（死区基准，v209）
                                    var cent0 = Offset.Zero
                                    var prevCount = 0
                                    var lastPos = start // 首指末位置（点按判定用）
                                    // 捏合写入限频（v206/v207）：高触控采样率设备（240Hz）
                                    // 一帧可到多个输入批次，逐批次写状态会渲染中间态（真机
                                    // 双指闪烁）；几何在局部变量累积，≥12ms 才提交一次
                                    var curZoom = 1f
                                    var curPan = Offset.Zero
                                    var lastCommit = 0L
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        pressed.firstOrNull { it.id == first.id }?.let { lastPos = it.position }
                                        if (isPinch || pressed.size >= 2) {
                                            if (!isPinch) {
                                                isPinch = true
                                                drawing = false
                                                wmDragging = false // 捏合接管，退出水印拖动
                                                live = emptyList()
                                                prevCount = 0 // 让下一帧只建基线不动状态
                                                curZoom = zoom
                                                curPan = pan
                                                zoomAnimJob?.cancel() // 捏合接管缩放，停动画
                                            }
                                            // 缩放通道（v209）：死区+低通。慢捏时指距有自然
                                            // 微颤（±1~2px）且部分设备有幽灵触点/交替批次，
                                            // 逐批比例累乘会把微颤乘进缩放=放大缩小来回闪。
                                            // 间距相对上次"已应用"间距变化 >1.2% 才应用，
                                            // 且比例低通 0.6 衰减。手指数量变化只重建基线。
                                            if (pressed.size >= 2) {
                                                val d = avgDist(pressed).coerceAtLeast(1f)
                                                if (prevCount != pressed.size) {
                                                    appliedDist = d // 指数变化：重建基线不应用
                                                } else if (appliedDist > 1f &&
                                                    abs(d - appliedDist) > maxOf(2f, appliedDist * 0.012f)
                                                ) {
                                                    val raw = d / appliedDist
                                                    val ratio = 1f + (raw - 1f) * 0.6f
                                                    val zOld = curZoom
                                                    curZoom = (curZoom * ratio).coerceIn(1f, 6f)
                                                    // 焦点锚定：质心下的内容点在缩放中钉住不动
                                                    // （dpan = (质心−中心)·(zOld−zNew)，父系像素）
                                                    val cc = centroid(pressed)
                                                    val cx = size.width / 2f
                                                    val cy = size.height / 2f
                                                    curPan = Offset(
                                                        curPan.x + (cc.x - cx) * (zOld - curZoom),
                                                        curPan.y + (cc.y - cy) * (zOld - curZoom),
                                                    )
                                                    appliedDist = d
                                                }
                                            }
                                            // 平移=双指"同向"移动才计入（点积>0）；对向
                                            // 分开/合拢是缩放不产生平移。慢捏时两指交替
                                            // 移动会让质心沿捏合轴来回摆——若平移直接跟
                                            // 质心，画面就跟着交替闪（真机实锤，方向随
                                            // 捏合轴）；逐指增量+同向判定彻底滤掉这种摆动
                                            val deltas = pressed.map { it.position - it.previousPosition }
                                            val trans = when {
                                                deltas.size >= 2 -> {
                                                    val a = deltas[0]
                                                    val b = deltas[1]
                                                    if (a.x * b.x + a.y * b.y > 0f) (a + b) / 2f else Offset.Zero
                                                }
                                                else -> deltas.firstOrNull() ?: Offset.Zero
                                            }
                                            // 填充语义钳制（v212）：缩放后照片盒边缘最多
                                            // 推到白框边缘；未填满该轴（如横拍低倍时竖向）
                                            // 钳 0=保持居中不漂移
                                            val maxX = ((curZoom * size.width - frameW) / 2f).coerceAtLeast(0f)
                                            val maxY = ((curZoom * size.height - frameH) / 2f).coerceAtLeast(0f)
                                            curPan = Offset(
                                                (curPan.x + trans.x * curZoom).coerceIn(-maxX, maxX),
                                                (curPan.y + trans.y * curZoom).coerceIn(-maxY, maxY),
                                            )
                                            cent0 = centroid(pressed)
                                            prevCount = pressed.size
                                            event.changes.forEach { it.consume() }
                                            val nowNs = System.nanoTime()
                                            if (nowNs - lastCommit >= 12_000_000L) {
                                                lastCommit = nowNs
                                                zoom = curZoom
                                                pan = curPan
                                            }
                                        } else if (wmDragging) {
                                            // 长按拖水印：消费增量并按照片盒归一化上报
                                            // （此模式锁定纵向，增量不旋回重力系）
                                            event.changes.forEach { it.consume() }
                                            val ch = pressed.first()
                                            val d = ch.position - ch.previousPosition
                                            if (d != Offset.Zero) {
                                                wmDragCur?.invoke(d.x / size.width, d.y / size.height)
                                            }
                                        } else if (!isTextTool) {
                                            val ch = pressed.first()
                                            if (!drawing &&
                                                (ch.position - start).getDistance() > slop
                                            ) {
                                                drawing = true
                                            }
                                            if (drawing) {
                                                ch.consume()
                                                live = live + Offset(
                                                    (ch.position.x / size.width).coerceIn(0f, 1f),
                                                    (ch.position.y / size.height).coerceIn(0f, 1f),
                                                )
                                            }
                                        }
                                    }
                                    // 补提交最后一段（限频窗口内的尾差不能丢）
                                    if (isPinch) {
                                        zoom = curZoom
                                        pan = curPan
                                    }
                                    if (!isTextTool && drawing && live.size > 1) {
                                        vm.addAnnotation(
                                            ui.annotationTool, ui.annotationColor, live,
                                            zoom.coerceAtLeast(1f),
                                        )
                                    } else if (isTextTool && !isPinch && !wmDragging &&
                                        (lastPos - start).getDistance() <= slop
                                    ) {
                                        // 文字工具空区域点按=加字（元素上的点按到不了本层）
                                        textDialogAt = Offset(
                                            (start.x / size.width).coerceIn(0f, 1f),
                                            (start.y / size.height).coerceIn(0f, 1f),
                                        )
                                    }
                                    live = emptyList()
                                }
                            },
                    )

                    // 文字元素层（在手势层之上：元素优先接到点按/拖动/手柄）。
                    // zoomProvider 延迟读取（lambda 不在组合期求值），手柄反向缩放用
                    ui.textItems.forEach { item ->
                        TextItemView(
                            item = item,
                            interactive = isTextTool,
                            parentW = parentW,
                            parentH = parentH,
                            zoomProvider = { zoom },
                            onChange = { vm.updateTextItem(it) },
                            onDelete = { vm.removeTextItem(item.id) },
                            // v0.6.44 修「改完颜色再点开还是旧颜色」：必须回传
                            // TextItemView 内部的 rememberUpdatedState 最新值，
                            // 不能捕获这里的 item（pointerInput 以 id 为 key 不重启，
                            // 闭包停的是首次组合时的 item 快照）
                            onEdit = { editing = it },
                            onSelect = { vm.selectTextItem(item.id) },
                        )
                    }

                    // 文字输入框：新增（textDialogAt）或编辑（editing）。放在缩放层内以
                    // 访问其作用域的状态——AlertDialog 是独立窗口，不受 layer 变换影响
                    val target = editing
                    if (textDialogAt != null || target != null) {
                        var input by remember(target, textDialogAt) {
                            mutableStateOf(target?.text ?: "")
                        }
                        // 文字模板（v0.6.43）：编辑已有文字时以该元素自身模板为初值，
                        // 新增时以记忆的模板为初值；确定时写回记忆（下次加字沿用）
                        val savedStyle by vm.textTemplate.collectAsState()
                        // key 只认 target/textDialogAt：不能再带 savedStyle——否则
                        // 「确定」写回记忆后 savedStyle 变化会把 style 重置回记忆值，
                        // 用户刚选的颜色被覆盖（v0.6.44 修）
                        var style by remember(target, textDialogAt) {
                            mutableStateOf(
                                if (target != null) {
                                    com.engcamera.data.TextTemplateStyle(
                                        templateId = target.templateId,
                                        textColor = target.color,
                                        bgColor = target.bgColor,
                                        markerColor = target.markerColor,
                                    )
                                } else {
                                    savedStyle
                                },
                            )
                        }
                        AlertDialog(
                            onDismissRequest = { textDialogAt = null; editing = null },
                            title = { Text(I18n.t(if (target != null) "编辑文字" else "添加文字")) },
                            text = {
                                Column {
                                    OutlinedTextField(
                                        value = input,
                                        onValueChange = { input = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    TextTemplatePicker(
                                        style = style,
                                        onChange = { style = it },
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val at = textDialogAt
                                    // 记忆写回：新增/编辑都记住这套模板，下次加字默认沿用
                                    vm.updateTextTemplate(style)
                                    if (target != null) {
                                        if (input.isNotBlank()) {
                                            vm.updateTextItem(
                                                target.copy(
                                                    text = input,
                                                    color = style.textColor,
                                                    templateId = style.templateId,
                                                    bgColor = style.bgColor,
                                                    markerColor = style.markerColor,
                                                ),
                                            )
                                        }
                                    } else if (at != null && input.isNotBlank()) {
                                        vm.addTextItem(
                                            input, at.x, at.y, style.textColor,
                                            templateId = style.templateId,
                                            bgColor = style.bgColor,
                                            markerColor = style.markerColor,
                                        )
                                    }
                                    textDialogAt = null
                                    editing = null
                                }) { Text(I18n.t("确定")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { textDialogAt = null; editing = null }) {
                                    Text(I18n.t("取消"))
                                }
                            },
                        )
                    }
                } // 照片盒结束
            } // 缩放层结束

            // 底部工具条（用户需求 2026-11-03）：左下=编辑水印（进水印编辑器改内容），
            // 右下=撤销（原右上角下移）。长按撤销=放大(2.5x)/回正切换（隐藏入口保留）。
            val wmEditCur by androidx.compose.runtime.rememberUpdatedState(onEditWatermark)
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (wmEditCur != null) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x88000000))
                            .clickable { wmEditCur?.invoke() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = I18n.t("编辑水印"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    // 无编辑入口的调用方：右下角撤销单钮钉右下（保持白框内右下角位置）
                    Box(Modifier.size(40.dp))
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x88000000))
                        .combinedClickable(
                            enabled = ui.annotations.isNotEmpty(),
                            onClick = { vm.annotationUndo() },
                            onLongClick = {
                                // 长按=放大(2.5x)/回正 平滑动画（回正同时归位平移）
                                val target = if (zoom > 1.01f) 1f else 2.5f
                                zoomAnimJob?.cancel()
                                zoomAnimJob = scope.launch {
                                    val z0 = zoom
                                    val px0 = pan.x
                                    val py0 = pan.y
                                    val tx = if (target == 1f) 0f else px0
                                    val ty = if (target == 1f) 0f else py0
                                    val t0 = withFrameNanos { it }
                                    val dur = 550_000_000f
                                    while (true) {
                                        val now = withFrameNanos { it }
                                        val p = ((now - t0) / dur).coerceIn(0f, 1f)
                                        val e = p * p * (3f - 2f * p) // smoothstep 缓动
                                        zoom = z0 + (target - z0) * e
                                        pan = Offset(px0 + (tx - px0) * e, py0 + (ty - py0) * e)
                                        if (p >= 1f) break
                                    }
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = I18n.t("撤销标注"),
                        tint = if (ui.annotations.isEmpty()) Color(0x66FFFFFF) else Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    } // BoxWithConstraints（白框）结束
}

/**
 * 文字标注元素（v199 重写，v0.6.43 重做手柄与模板）：正文在独立的缩放/旋转图层内；
 * 两个操作手柄在图层外（恒定 22dp 不随文字缩放），精确钉在变换后的视觉角点。
 * 拉杆用根坐标系"手指→元素中心"的角度差/距离比驱动（手柄不在缩放层内，指针坐标无反向
 * 变换，真跟手且与文字大小无关）；移动增量按当前旋转旋回父坐标。
 *
 * v0.6.43 三处修复（用户实测反馈 2026-11-23）：
 * 1. 白色标定框**不再随缩放变粗**：框画在缩放图层内，线宽按 1/scale 反向补偿，
 *    视觉上恒 1dp（框只作位置标定，不参与"变粗"）。
 * 2. 手柄**钉死视觉角点**：去掉旧版 3dp 外扩 pad（pad 会被 scale 放大，X 就离角点
 *    越来越远）——框=内容盒本身，左上角即锚点（transformOrigin 0,0），X 中心精确
 *    落在框左上角、缩放手柄中心精确落在框右下角。
 * 3. 精细调节时文字狂闪/大小抽搐：旧版每次 onDrag 都用**当前** scale 重算元素中心，
 *    而中心随 scale 移动 → 距离比反馈进 scale 形成正反馈震荡。改为 onDragStart 时
 *    快照一次中心与距离，整段手势内中心固定（缩放轴=左上角锚点，本就该固定）。
 */
@Composable
private fun TextItemView(
    item: CameraUiState.TextItem,
    interactive: Boolean,
    parentW: Float,
    parentH: Float,
    zoomProvider: () -> Float = { 1f },
    onChange: (CameraUiState.TextItem) -> Unit,
    onDelete: () -> Unit,
    /** 点按元素请求编辑：回传**最新** item（不是首次组合的快照，v0.6.44 修色值不刷新） */
    onEdit: (CameraUiState.TextItem) -> Unit,
    onSelect: () -> Unit,
) {
    // 手势块以 id 为 key 不随重组重启——rememberUpdatedState 保证读到最新
    val currentItem by androidx.compose.runtime.rememberUpdatedState(item)
    var boxRoot by remember { mutableStateOf(Offset.Zero) }
    var boxHalf by remember { mutableStateOf(Offset.Zero) } // 未变换布局半宽高
    // 缩放手柄的 LayoutCoordinates（localToRoot 含缩放/平移，见下方注释）
    var handleCoords by remember {
        mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null)
    }
    val template = com.engcamera.watermark.TextTemplate.of(item.templateId)
    // 字号/盒子尺寸与烧录同一套几何（TextAnnotationRenderer）：预览按照片盒宽取
    // textSize，烧录按成片宽取，两边等比同构
    val base = parentW * com.engcamera.watermark.TextAnnotationRenderer.TEXT_SIZE_RATIO
    val boxSize = remember(item.text, parentW, template) {
        val m = com.engcamera.watermark.TextAnnotationRenderer.metrics(item.text, base)
        com.engcamera.watermark.TextAnnotationRenderer.boxSize(template, m, base)
    }
    Box(
        Modifier
            .offset { IntOffset((item.x * parentW).toInt(), (item.y * parentH).toInt()) }
            .onGloballyPositioned { c ->
                boxRoot = c.positionInRoot()
                boxHalf = Offset(c.size.width / 2f, c.size.height / 2f)
            },
    ) {
        // 正文：与烧录 TextAnnotationRenderer 同一套几何（模板背景/描边/阴影全在这层）。
        // 缩放/旋转只作用于这一层，变换轴=左上角（=锚点）
        Canvas(
            Modifier
                .graphicsLayer {
                    rotationZ = item.rotation
                    scaleX = item.scale
                    scaleY = item.scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
                .size(
                    with(LocalDensity.current) {
                        DpSize(Dp(boxSize.first / density), Dp(boxSize.second / density))
                    },
                )
                .pointerInput(item.id, interactive) {
                    if (!interactive) return@pointerInput
                    detectTapGestures {
                        onSelect()
                        // 回传最新快照：本块以 id 为 key 不重启，直接读 currentItem
                        onEdit(currentItem)
                    }
                }
                .pointerInput(item.id, interactive) {
                    if (!interactive) return@pointerInput
                    detectDragGestures { change, amt ->
                        change.consume()
                        onSelect()
                        // 局部增量（已被图层反向变换）→ 先乘 scale 回到未变换尺寸系，
                        // 再按当前旋转角旋回父坐标系——任意角度下移动方向都正确
                        val sc = currentItem.scale.coerceAtLeast(0.05f)
                        val lx = amt.x * sc
                        val ly = amt.y * sc
                        val rad = Math.toRadians(currentItem.rotation.toDouble())
                        val cosR = cos(rad).toFloat()
                        val sinR = sin(rad).toFloat()
                        val px = lx * cosR - ly * sinR
                        val py = lx * sinR + ly * cosR
                        onChange(
                            currentItem.copy(
                                x = (currentItem.x + px / parentW).coerceIn(0f, 1f),
                                y = (currentItem.y + py / parentH).coerceIn(0f, 1f),
                            ),
                        )
                    }
                },
        ) {
            val native = drawContext.canvas.nativeCanvas
            com.engcamera.watermark.TextAnnotationRenderer.draw(
                canvas = native,
                text = item.text,
                x = 0f,
                y = 0f,
                scale = 1f, // 缩放由 graphicsLayer 承担
                rotation = 0f,
                textSize = base,
                                template = template,
                                textColor = item.color.toInt(),
                                bgColor = item.bgColor.toInt(),
                                markerColor = item.markerColor.toInt(),
                            )
            // 标定框（仅 UI 不烧录）：线宽 ÷scale 抵消图层缩放，视觉恒 1dp；
            // 内缩半线宽让描边整条落在盒内（否则外侧半条被裁掉，看起来偏细）
            if (interactive) {
                val sw = (1.dp.toPx() / item.scale.coerceAtLeast(0.01f))
                drawRect(
                    Color.White,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(
                        (size.width - sw).coerceAtLeast(0f),
                        (size.height - sw).coerceAtLeast(0f),
                    ),
                    style = Stroke(sw),
                )
            }
        }
        if (interactive) {
            // 视觉角点（锚点=左上系）= R(旋转)·(±全宽高×scale)，自外层盒原点起算；
            // 手柄恒定大小钉在角点上
            val rad = Math.toRadians(item.rotation.toDouble())
            val cosR = cos(rad).toFloat()
            val sinR = sin(rad).toFloat()
            val s = item.scale
            fun corner(dx: Float, dy: Float): Offset {
                val vx = dx * boxHalf.x * 2f * s
                val vy = dy * boxHalf.y * 2f * s
                return Offset(vx * cosR - vy * sinR, vx * sinR + vy * cosR)
            }
            val halfHandle = 11.dp
            // 手柄反向缩放：预览双指放大时手柄尺寸铆定 22dp 不随图片放大
            // （绕自身中心缩，角点定位公式不变；命中区经两层逆变换同样恒定）
            val handlePin = Modifier.graphicsLayer {
                val z = zoomProvider().coerceAtLeast(1f)
                scaleX = 1f / z
                scaleY = 1f / z
            }
            // 左上：删除（白底深色 icon，恒定大小）。transformOrigin(0,0) 下左上视觉
            // 角点=外层盒原点（枢轴不动），直接钉原点——中心精确落在框左上角
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (-halfHandle.toPx()).roundToInt(),
                            (-halfHandle.toPx()).roundToInt(),
                        )
                    }
                    .size(22.dp)
                    .then(handlePin)
                    .clip(CircleShape)
                    .background(Color(0xF2FFFFFF))
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = I18n.t("删除"),
                    tint = Color(0xB3101216),
                    modifier = Modifier.size(13.dp),
                )
            }
            // 右下：旋转/缩放手柄（根坐标角度差/距离比，真跟手）
            Box(
                Modifier
                    .offset {
                        val c = corner(1f, 1f)
                        IntOffset(
                            (c.x - halfHandle.toPx()).roundToInt(),
                            (c.y - halfHandle.toPx()).roundToInt(),
                        )
                    }
                    .size(22.dp)
                    .then(handlePin)
                    .clip(CircleShape)
                    .background(Color(0xF2FFFFFF))
                    // 存 LayoutCoordinates 而不是 positionInRoot：手柄自身带 1/zoom 反向
                    // 缩放，positionInRoot 拿到的是"缩放后原点"，与局部指针坐标混算会
                    // 在预览放大时错位；localToRoot 一次算清（含缩放/平移）
                    .onGloballyPositioned { c -> handleCoords = c }
                    .pointerInput(item.id) {
                        // v0.6.44 修「比手指快」：缩放比必须用**锚点到手指**的距离，
                        // 不能用「元素中心到手指」——中心到右下角的距离只有半个对角线，
                        // 手指挪同样一段像素时比值变化被放大约 1.4~2 倍，看起来就是
                        // "文字长得比手指快"。锚点=左上角=transformOrigin，也是唯一
                        // 不随 scale 移动的点，用它做基准比例才严格跟手。
                        var ang0 = 0.0
                        var dist0 = 1.0
                        var rot0 = 0f
                        var scale0 = 1f
                        var anchorFixed = Offset.Zero
                        // 局部坐标 → 根坐标（含外层双指缩放层与手柄自身 1/z 反向缩放）
                        fun toRoot(p: Offset): Offset {
                            val c = handleCoords ?: return p
                            return c.localToRoot(p)
                        }
                        detectDragGestures(
                            onDragStart = { p ->
                                val f = toRoot(p)
                                // 锚点=外层盒原点（transformOrigin(0,0)，旋转/缩放都不动它）
                                anchorFixed = boxRoot
                                ang0 = atan2(
                                    (f.y - anchorFixed.y).toDouble(),
                                    (f.x - anchorFixed.x).toDouble(),
                                )
                                dist0 = kotlin.math.hypot(
                                    (f.x - anchorFixed.x).toDouble(),
                                    (f.y - anchorFixed.y).toDouble(),
                                ).coerceAtLeast(1.0)
                                rot0 = currentItem.rotation
                                scale0 = currentItem.scale
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val f = toRoot(change.position)
                                val a = anchorFixed
                                val ang = atan2((f.y - a.y).toDouble(), (f.x - a.x).toDouble())
                                val dist = kotlin.math.hypot(
                                    (f.x - a.x).toDouble(),
                                    (f.y - a.y).toDouble(),
                                ).coerceAtLeast(1.0)
                                var da = Math.toDegrees(ang - ang0)
                                while (da > 180) da -= 360.0
                                while (da < -180) da += 360.0
                                onChange(
                                    currentItem.copy(
                                        rotation = rot0 + da.toFloat(),
                                        scale = (scale0 * (dist / dist0).toFloat()).coerceIn(0.35f, 8f),
                                    ),
                                )
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = I18n.t("旋转缩放"),
                    tint = Color(0xB3101216),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

private const val PI_6 = Math.PI / 6

/**
 * 文字模板选择器（v0.6.43，用户需求 2026-11-23）：横排模板卡片（用真实渲染器画
 * 小样，所见即所得）+ 背景色选色行。放在文字输入框下方。
 */
@Composable
private fun TextTemplatePicker(
    style: com.engcamera.data.TextTemplateStyle,
    onChange: (com.engcamera.data.TextTemplateStyle) -> Unit,
) {
    val templates = com.engcamera.watermark.TextTemplate.values().toList()
    // 文字色预设
    val textPresets = listOf(
        0xFFFF5252L, // 红（默认）
        0xFFFFFFFFL, // 白
        0xFF000000L, // 黑
        0xFFFFD54FL, // 黄
        0xFF81C784L, // 绿
        0xFF64B5F6L, // 蓝
    )
    // 背景色预设（含半透明白/黑，参考图那种浅底最常用）
    val bgPresets = listOf(
        0xF2FFFFFFL, // 白（参考图）
        0xCC000000L, // 黑
        0xE6FFD54FL, // 黄
        0xE681C784L, // 绿
        0xE664B5F6L, // 蓝
        0xE6FF5252L, // 红
    )
    var showFreeTextColor by remember { mutableStateOf(false) }
    var showFreeBgColor by remember { mutableStateOf(false) }
    var showFreeMarkerColor by remember { mutableStateOf(false) }
    // 标记点色预设（圆点标签用）
    val markerPresets = listOf(
        0xFFFFC107L, // 黄（参考图默认）
        0xFFFF5252L, // 红
        0xFF64B5F6L, // 蓝
        0xFF81C784L, // 绿
        0xFFFFFFFFL, // 白
        0xFF000000L, // 黑
    )
    Column {
        Text(
            I18n.t("文字模板"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(templates.size) { idx ->
                val tpl = templates[idx]
                val selected = style.templateId == tpl.id
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 76.dp, height = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onChange(style.copy(templateId = tpl.id)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 小样：用真实渲染器画，与成片同一套几何
                        Canvas(
                            Modifier
                                .fillMaxSize()
                                .padding(6.dp),
                        ) {
                            val textSize = size.height * 0.34f
                            val m = com.engcamera.watermark.TextAnnotationRenderer
                                .metrics("Aa", textSize)
                            val (bw, bh) = com.engcamera.watermark.TextAnnotationRenderer
                                .boxSize(tpl, m, textSize)
                            val s = minOf(
                                1f,
                                (size.width * 0.86f) / bw.coerceAtLeast(1f),
                                (size.height * 0.86f) / bh.coerceAtLeast(1f),
                            )
                            com.engcamera.watermark.TextAnnotationRenderer.draw(
                                canvas = drawContext.canvas.nativeCanvas,
                                text = "Aa",
                                x = (size.width - bw * s) / 2f,
                                y = (size.height - bh * s) / 2f,
                                scale = s,
                                rotation = 0f,
                                textSize = textSize,
                                template = tpl,
                                textColor = style.textColor.toInt(),
                                bgColor = style.bgColor.toInt(),
                                markerColor = style.markerColor.toInt(),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        templateName(tpl),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
        // 文字颜色（所有模板都有）
        Spacer(Modifier.height(8.dp))
        ColorRow(
            label = I18n.t("文字颜色"),
            presets = textPresets,
            current = style.textColor,
            onPick = { onChange(style.copy(textColor = it)) },
            onFree = { showFreeTextColor = true },
        )
        // 背景/描边色：无背景模板不需要
        if (style.templateId != com.engcamera.watermark.TextTemplate.NONE.id) {
            Spacer(Modifier.height(8.dp))
            ColorRow(
                label = if (style.templateId == com.engcamera.watermark.TextTemplate.OUTLINE.id) {
                    I18n.t("描边颜色")
                } else {
                    I18n.t("背景颜色")
                },
                presets = bgPresets,
                current = style.bgColor,
                onPick = { onChange(style.copy(bgColor = it)) },
                onFree = { showFreeBgColor = true },
            )
        }
        // 标记点颜色：只有「圆点标签」有
        if (style.templateId == com.engcamera.watermark.TextTemplate.DOT_TAG.id) {
            Spacer(Modifier.height(8.dp))
            ColorRow(
                label = I18n.t("标记点颜色"),
                presets = markerPresets,
                current = style.markerColor,
                onPick = { onChange(style.copy(markerColor = it)) },
                onFree = { showFreeMarkerColor = true },
            )
        }
    }
    if (showFreeTextColor) {
        com.engcamera.editor.ColorPickerDialog(
            title = I18n.t("文字颜色"),
            initial = style.textColor,
            onDismiss = { showFreeTextColor = false },
            onConfirm = { c ->
                onChange(style.copy(textColor = c))
                showFreeTextColor = false
            },
        )
    }
    if (showFreeBgColor) {
        com.engcamera.editor.ColorPickerDialog(
            title = I18n.t("背景颜色"),
            initial = style.bgColor,
            onDismiss = { showFreeBgColor = false },
            onConfirm = { c ->
                onChange(style.copy(bgColor = c))
                showFreeBgColor = false
            },
        )
    }
    if (showFreeMarkerColor) {
        com.engcamera.editor.ColorPickerDialog(
            title = I18n.t("标记点颜色"),
            initial = style.markerColor,
            onDismiss = { showFreeMarkerColor = false },
            onConfirm = { c ->
                onChange(style.copy(markerColor = c))
                showFreeMarkerColor = false
            },
        )
    }
}

/** 一行颜色预设 + 彩虹自由取色（文字模板对话框内共用） */
@Composable
private fun ColorRow(
    label: String,
    presets: List<Long>,
    current: Long,
    onPick: (Long) -> Unit,
    onFree: () -> Unit,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { c ->
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(c.toInt()))
                        .border(
                            width = if (current == c) 2.dp else 1.dp,
                            color = if (current == c) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape,
                        )
                        .clickable { onPick(c) },
                )
            }
            // 彩虹圆=自由取色（复用编辑器的取色器）
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.sweepGradient(
                            listOf(
                                Color(0xFFFF5252), Color(0xFFFFD54F), Color(0xFF81C784),
                                Color(0xFF64B5F6), Color(0xFFB388FF), Color(0xFFFF5252),
                            ),
                        ),
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onFree() },
            )
        }
    }
}

@Composable
private fun templateName(tpl: com.engcamera.watermark.TextTemplate): String = when (tpl) {
    com.engcamera.watermark.TextTemplate.NONE -> I18n.t("无背景")
    com.engcamera.watermark.TextTemplate.TAG -> I18n.t("标签条")
    com.engcamera.watermark.TextTemplate.DOT_TAG -> I18n.t("圆点标签")
    com.engcamera.watermark.TextTemplate.CARD -> I18n.t("圆角卡片")
    com.engcamera.watermark.TextTemplate.OUTLINE -> I18n.t("描边字")
}

