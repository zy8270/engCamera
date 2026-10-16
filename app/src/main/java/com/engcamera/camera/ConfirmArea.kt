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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.engcamera.data.DynamicValues
import com.engcamera.data.WatermarkState
import com.engcamera.i18n.I18n
import kotlinx.coroutines.Job
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
 * → 手势层——标注盖在水印之上（与保存一致）。文字工具时手势层换为"点按加字"层
 * （在文字元素之下），文字元素可拖动/点按编辑/左上删除/右下旋转缩放。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConfirmArea(
    modifier: Modifier,
    vm: CameraViewModel,
    ui: CameraUiState,
    effective: WatermarkState,
    dynamic: DynamicValues,
) {
    val photo0 = ui.confirmBitmap
    // 照片框比例跟随真实纵横比（v210）：横拍=横向 4:3，照片不压扁；无图兜底 3:4
    val photoAr = if (photo0 != null && !photo0.isRecycled && photo0.width > 0 && photo0.height > 0) {
        photo0.width.toFloat() / photo0.height.toFloat()
    } else {
        3f / 4f
    }
    // 白框恒 3:4 满槽（v212，用户拍板）：底部按钮组固定不动；照片在框内按真实
    // 比例居中（横拍上下留空），双指放大 ≥ 框高/照高 比例时恰好填满整框
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .background(Color(0xEE101216)),
    ) {
        val photo = ui.confirmBitmap
        // 双指缩放/平移（用户需求 2026-10-16）：单指仍是标注/文字操作，双指捏合
        // 放大预览（照片+水印+标注+文字整栈一起变换，坐标恒对齐）
        var zoom by remember { mutableStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) } // 父坐标系像素
        var zoomAnimJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()
        // 冻结的水印状态/时间（快门瞬间定格，v195）
        val frozenWm = ui.confirmWatermark ?: effective
        val frozenDyn = ui.confirmDynamic ?: dynamic
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .border(3.dp, Color(0xFFC2C6CE), RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        ) {
            val frameW = constraints.maxWidth.toFloat()
            val frameH = constraints.maxHeight.toFloat()
            if (photo != null && !photo.isRecycled) {
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
                // remember 化：否则每次重组 asImageBitmap() 都新建实例 → 纹理反复重传
                val photoBmp = remember(photo) { photo.asImageBitmap() }

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
                // minOf(w,h) 一致，否则预览比成片大）
                com.engcamera.watermark.WatermarkOverlay(
                    state = frozenWm,
                    dynamic = frozenDyn,
                    rotationDeg = frozenWm.rotationDeg + ui.confirmFrameRotation,
                    onTap = { },
                    onDrag = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    backdropProvider = { photo },
                    gravityRollDeg = 0f,
                    scaleByShortSide = true,
                    // 手势全让给标注/文字工具（水印块自带手势层会吞掉其覆盖区域的点按）
                    interactive = false,
                )

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
                                } else if (isTextTool && !isPinch &&
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
                        onEdit = { editing = item },
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
                    AlertDialog(
                        onDismissRequest = { textDialogAt = null; editing = null },
                        title = { Text(I18n.t(if (target != null) "编辑文字" else "添加文字")) },
                        text = {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val at = textDialogAt
                                if (target != null) {
                                    if (input.isNotBlank()) vm.updateTextItem(target.copy(text = input))
                                } else if (at != null && input.isNotBlank()) {
                                    vm.addTextItem(input, at.x, at.y, ui.annotationColor)
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
            }
            // 右上角撤销（钉白框右上，位置恒定）；长按=放大/回正切换（放大后快速回正的隐藏入口）
            Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 20.dp)
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
        } // BoxWithConstraints（白框）结束
    }
}

/**
 * 文字标注元素（v199 重写）：正文在独立的缩放/旋转图层内；两个操作手柄在
 * 图层外（恒定 22dp 不随文字缩放），精确钉在变换后的视觉角点。拉杆用根坐标
 * 系"手指→元素中心"的角度差/距离比驱动（手柄不在缩放层内，指针坐标无反向
 * 变换，真跟手且与文字大小无关）；移动增量按当前旋转旋回父坐标。
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
    onEdit: () -> Unit,
    onSelect: () -> Unit,
) {
    // 手势块以 id 为 key 不随重组重启——rememberUpdatedState 保证读到最新
    val currentItem by androidx.compose.runtime.rememberUpdatedState(item)
    var boxRoot by remember { mutableStateOf(Offset.Zero) }
    var boxHalf by remember { mutableStateOf(Offset.Zero) } // 未变换布局半宽高
    var handleRoot by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .offset { IntOffset((item.x * parentW).toInt(), (item.y * parentH).toInt()) }
            .onGloballyPositioned { c ->
                boxRoot = c.positionInRoot()
                boxHalf = Offset(c.size.width / 2f, c.size.height / 2f)
            },
    ) {
        // 正文：与烧录 drawAnnotations 同一套 Paint/几何（textSize=0.05×宽、行高×1.25、
        // baseline=行顶−ascent、fakeBold、同比例阴影）——预览与成片同构，修位置偏移；
        // 白框/底色仅 UI 不烧录。缩放/旋转只作用于这一层，变换轴=左上角。
        val lines = item.text.split('\n')
        val base = parentW * 0.05f
        val lineH = base * 1.25f
        val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
        val metrics = remember(item.text, parentW) {
            paint.textSize = base
            paint.isFakeBoldText = true
            val wMax = lines.maxOf { paint.measureText(it) }
            val hTotal = (lines.size - 1) * lineH - paint.ascent() + paint.descent()
            Triple(wMax, hTotal, paint.ascent())
        }
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
                        DpSize(Dp(metrics.first / density), Dp(metrics.second / density))
                    },
                )
                .pointerInput(item.id, interactive) {
                    if (!interactive) return@pointerInput
                    detectTapGestures {
                        onSelect()
                        onEdit()
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
            val pad = 3.dp.toPx()
            val frame = Size(size.width + pad * 2f, size.height + pad * 2f)
            drawRect(Color(0x22000000), topLeft = Offset(-pad, -pad), size = frame)
            drawRect(Color.White, topLeft = Offset(-pad, -pad), size = frame, style = Stroke(1.dp.toPx()))
            paint.textSize = base
            paint.color = item.color.toInt()
            paint.isFakeBoldText = true
            paint.setShadowLayer(parentW * 0.012f, parentW * 0.004f, parentW * 0.004f, 0x66000000)
            val native = drawContext.canvas.nativeCanvas
            lines.forEachIndexed { i, ln ->
                native.drawText(ln, 0f, i * lineH - metrics.third, paint)
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
            // 角点=外层盒原点（枢轴不动），直接钉原点——不再是中心系镜像公式
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
                    .onGloballyPositioned { c -> handleRoot = c.positionInRoot() }
                    .pointerInput(item.id) {
                        // 视觉中心 = 锚点(外层盒原点) + R·(半宽高×scale)——锚点系
                        val center: () -> Offset = {
                            Offset(
                                boxRoot.x + (boxHalf.x * currentItem.scale) * cosR -
                                    (boxHalf.y * currentItem.scale) * sinR,
                                boxRoot.y + (boxHalf.x * currentItem.scale) * sinR +
                                    (boxHalf.y * currentItem.scale) * cosR,
                            )
                        }
                        var ang0 = 0.0
                        var dist0 = 1.0
                        var rot0 = 0f
                        var scale0 = 1f
                        detectDragGestures(
                            onDragStart = { p ->
                                val f = Offset(handleRoot.x + p.x, handleRoot.y + p.y)
                                val c = center()
                                ang0 = atan2((f.y - c.y).toDouble(), (f.x - c.x).toDouble())
                                dist0 = kotlin.math.hypot((f.x - c.x).toDouble(), (f.y - c.y).toDouble())
                                    .coerceAtLeast(1.0)
                                rot0 = currentItem.rotation
                                scale0 = currentItem.scale
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val f = Offset(
                                    handleRoot.x + change.position.x,
                                    handleRoot.y + change.position.y,
                                )
                                val c = center()
                                val ang = atan2((f.y - c.y).toDouble(), (f.x - c.x).toDouble())
                                val dist = kotlin.math.hypot(
                                    (f.x - c.x).toDouble(),
                                    (f.y - c.y).toDouble(),
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
