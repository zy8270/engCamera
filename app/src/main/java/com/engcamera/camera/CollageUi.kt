package com.engcamera.camera

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.engcamera.data.CollageFieldDef
import com.engcamera.data.CollageItem
import com.engcamera.data.CollageSettings
import com.engcamera.data.CollageTemplate
import com.engcamera.i18n.I18n

/**
 * 拼图模板设置弹窗（v0.6.19，用户需求 2026-11-09）。
 *
 * 内容：列数步进器（1~4，默认 1）+ 模板三选一（无模板/客户拜访/工作汇报，带迷你版式预览）
 * + 大标题输入（两款红头模板）+ 字段列表（开关 + 输入框，日期/地点为自动取值不可编辑）。
 * 所有改动即时落仓库（DataStore 持久化，下次进拼图模式沿用）。
 */
@Composable
fun CollageSettingsDialog(
    settings: CollageSettings,
    onDismiss: () -> Unit,
    onChange: (CollageSettings) -> Unit,
) {
    val tpl = settings.template
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("拼图模板设置")) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // ---- 列数 ----
                Text(I18n.t("拼图列数"), fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (settings.columns > 1) onChange(settings.copy(columns = settings.columns - 1))
                        },
                        enabled = settings.columns > 1,
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = I18n.t("减少列数"))
                    }
                    Text(
                        "${settings.columns}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 40.dp),
                    )
                    IconButton(
                        onClick = {
                            if (settings.columns < 4) onChange(settings.copy(columns = settings.columns + 1))
                        },
                        enabled = settings.columns < 4,
                    ) {
                        Icon(Icons.Default.Add, contentDescription = I18n.t("增加列数"))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        I18n.t("最多 4 列"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---- 模板三选一 ----
                Text(I18n.t("拼图模板"), fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollageTemplate.ordered.forEach { t ->
                        TemplateCard(
                            template = t,
                            selected = t == tpl,
                            modifier = Modifier.weight(1f),
                            onClick = { onChange(settings.copy(templateId = t.id)) },
                        )
                    }
                }

                // ---- 大标题（仅红头模板）----
                if (tpl.isRedHeader) {
                    Text(I18n.t("大标题"), fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = settings.titleOf(tpl),
                        onValueChange = { v ->
                            onChange(settings.copy(titles = settings.titles + (tpl.id to v)))
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ---- 字段列表 ----
                Text(I18n.t("显示字段"), fontWeight = FontWeight.Medium)
                CollageSettings.fieldsOf(tpl).forEach { def ->
                    CollageFieldRow(
                        def = def,
                        settings = settings,
                        onChange = onChange,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("完成")) }
        },
    )
}

/** 模板卡片：迷你版式缩略图 + 名称 */
@Composable
private fun TemplateCard(
    template: CollageTemplate,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TemplateMiniPreview(template, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        Spacer(Modifier.height(4.dp))
        Text(
            I18n.t(template.displayName),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 模板迷你版式预览：纯 Compose 画的小样（不用真照片，靠色块表达版式）。
 * 红头=顶部红条 + 信息行横线 + 白缝网格；无模板=纯网格。
 */
@Composable
private fun TemplateMiniPreview(template: CollageTemplate, modifier: Modifier = Modifier) {
    val red = Color(0xFFC0272D)
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val cell = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Box(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Color.White)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
    ) {
        Column(Modifier.fillMaxSize().padding(3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (template.isRedHeader) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(9.dp)
                        .background(red),
                )
                // 信息行
                repeat(if (template == CollageTemplate.VISIT) 2 else 1) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(2.dp)
                            .background(line),
                    )
                }
            }
            // 2×2 照片网格
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(2) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(0.9f)
                                    .background(cell),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一个可配置字段：开关 + （非自动时）输入框 */
@Composable
private fun CollageFieldRow(
    def: CollageFieldDef,
    settings: CollageSettings,
    onChange: (CollageSettings) -> Unit,
) {
    val enabled = settings.enabledOf(def)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    onChange(settings.copy(fieldEnabled = settings.fieldEnabled + (def.key to on)))
                },
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(I18n.t(def.label), style = MaterialTheme.typography.bodyMedium)
                if (def.auto) {
                    Text(
                        I18n.t("自动获取"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (enabled && !def.auto) {
            OutlinedTextField(
                value = settings.valueOf(def),
                onValueChange = { v ->
                    onChange(settings.copy(fieldValues = settings.fieldValues + (def.key to v)))
                },
                singleLine = def.key != "note",
                placeholder = { Text(I18n.t("选填")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * 连拍预览覆盖层（v0.6.19，用户需求 2026-11-09）：屏幕 80% 宽 × 80% 高的居中面板。
 * - 每格右上角垃圾桶删除（二次确认由调用方弹框）
 * - 长按拖动换位：拖动格放大跟手 + 浮起，其它格平滑让位（安卓桌面图标观感）
 * - 底部三按钮：模板设置 / 返回 / 保存拼图；顶部「同步当前水印」
 *
 * 用面板而非 AlertDialog：拖动跟手需要精确指针坐标，Dialog 会引入额外窗口换算。
 * 尺寸取 80%（用户反馈 2026-11-09：满屏时顶部文字被通知栏盖住）。
 */
@Composable
fun CollagePreviewOverlay(
    items: List<CollageItem>,
    columns: Int,
    saving: Boolean,
    progress: String?,
    onClose: () -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Int, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onSave: () -> Unit,
    onSyncWatermark: () -> Unit,
) {
    // 拖动状态：正在拖的项 id + 累计位移（px）
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val currentItems by rememberUpdatedState(items)
    val density = LocalDensity.current
    // 屏幕宽度像素：单列时格子宽上限 = 屏幕宽 1/3（用户需求 2026-11-10）
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    val screenWidthPx = with(density) { screenWidthDp.dp.toPx() }

    // 遮罩：点外部不关闭（避免误触丢操作），只做视觉压暗
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
            // ---- 顶栏 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    I18n.t("已连拍 {n} 张").replace("{n}", "${items.size}"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSyncWatermark, enabled = items.isNotEmpty() && !saving) {
                    Text(I18n.t("同步当前水印"))
                }
            }

            // ---- 网格 ----
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                val cols = columns.coerceIn(1, 4)
                val rows = (items.size + cols - 1) / cols
                // 用绝对坐标手排（不用 LazyGrid）：拖动换位需要精确的格宽/格高换算位移
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    val gap = 8.dp
                    val gapPx = with(density) { gap.toPx() }
                    // 格子尺寸**固定**，不随张数缩小（用户反馈 2026-11-10：拍多了会越挤越小）：
                    // 单列时上限 = 屏幕宽 1/3（否则一张图占满太夸张）；
                    // 多列时按可用宽等分撑满（列数越多自然越小），保证不浪费横向空间
                    val maxCellWpx = if (cols == 1) screenWidthPx / 3f else Float.MAX_VALUE
                    val availWpx = with(density) { maxWidth.toPx() }
                    val fitWpx = (availWpx - gapPx * (cols - 1)) / cols
                    val cellWpx = minOf(maxCellWpx, fitWpx).coerceAtLeast(1f)
                    val cellHpx = cellWpx * 4f / 3f
                    val finalCw = with(density) { cellWpx.toDp() }
                    val finalCh = with(density) { cellHpx.toDp() }
                    val cwPx = cellWpx
                    val chPx = cellHpx
                    // 内容总高是否装得下（决定要不要滚动、以及不满一屏时垂直居中）
                    val contentHpx = rows * cellHpx + (rows - 1).coerceAtLeast(0) * gapPx
                    val availHpx = with(density) { maxHeight.toPx() }
                    val contentFits = contentHpx <= availHpx
                    val scrollState = rememberScrollState()
                    // 是否还有可滚动余量（渐变阴影据此显示/隐藏）
                    val canScrollUp by remember {
                        androidx.compose.runtime.derivedStateOf { scrollState.value > 0 }
                    }
                    val canScrollDown by remember {
                        androidx.compose.runtime.derivedStateOf {
                            scrollState.value < scrollState.maxValue
                        }
                    }
                    // 拖动中的目标下标：其它格据此播放"躲避"位移
                    val fromIdx = items.indexOfFirst { it.id == draggingId }
                    val targetIdx = if (fromIdx >= 0) {
                        val dCol = (dragOffset.x / (cwPx + gapPx)).toInt()
                        val dRow = (dragOffset.y / (chPx + gapPx)).toInt()
                        (fromIdx + dRow * cols + dCol).coerceIn(0, items.size - 1)
                    } else {
                        -1
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            // 拖动中禁用用户滚动（userScrollEnabled 而非摘掉 verticalScroll：
                            // 后者会让滚动位置归零跳回顶部）。长按拖动与滚动会抢手势，
                            // 拖动期间关掉滚动闸门即可（用户需求 2026-11-10）
                            .verticalScroll(scrollState, enabled = draggingId == null),
                        verticalArrangement = if (contentFits) {
                            Arrangement.spacedBy(gap, Alignment.CenterVertically)
                        } else {
                            Arrangement.spacedBy(gap)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        repeat(rows) { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                repeat(cols) { c ->
                                    val index = r * cols + c
                                    val item = items.getOrNull(index)
                                    if (item == null) {
                                        // 空格占位，保持网格对齐
                                        Box(Modifier.width(finalCw).height(finalCh))
                                    } else {
                                        // 显式 key：按 item 身份组合而非位置。换位后 Compose
                                        // 会把 composable 连同手势闭包一起搬到新位置，避免
                                        // "长按 A 浮起 B"（用户实测 2026-11-09）
                                        androidx.compose.runtime.key(item.id) {
                                        val isDragging = draggingId == item.id
                                        // 躲避动画（安卓桌面图标观感）：被跨过的格子让位一格，弹簧落位。
                                        // 位移 = 目标槽位 - 当前槽位，按行列分别换算成像素：
                                        // 单列时 x 分量恒为 0，自然变成纯垂直让位
                                        // （用户反馈 2026-11-10：单列横移反直觉）
                                        val stepX = (cwPx + gapPx).toInt()
                                        val stepY = (chPx + gapPx).toInt()
                                        // 该格在本次拖动中要挪到的槽位（-1 = 不动）
                                        val newIndex = when {
                                            fromIdx < 0 || targetIdx < 0 || isDragging -> -1
                                            // 往后移：被跨过的格各前移一格
                                            targetIdx > fromIdx && index in (fromIdx + 1)..targetIdx -> index - 1
                                            // 往前移：被跨过的格各后移一格
                                            targetIdx < fromIdx && index in targetIdx until fromIdx -> index + 1
                                            else -> -1
                                        }
                                        val shift = if (newIndex >= 0) {
                                            val dx = (newIndex % cols - index % cols) * stepX
                                            val dy = (newIndex / cols - index / cols) * stepY
                                            androidx.compose.ui.unit.IntOffset(dx, dy)
                                        } else {
                                            androidx.compose.ui.unit.IntOffset.Zero
                                        }
                                        val animatedShift by androidx.compose.animation.core.animateIntOffsetAsState(
                                            targetValue = shift,
                                            animationSpec = androidx.compose.animation.core.spring(
                                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                            ),
                                            label = "collageShift",
                                        )
                                        CollageCell(
                                            item = item,
                                            modifier = Modifier
                                                .width(finalCw)
                                                .height(finalCh)
                                                .offset { animatedShift }
                                                .zIndex(if (isDragging) 1f else 0f)
                                                // 拖动格：跟手位移 + 放大浮起
                                                .then(
                                                    if (isDragging) {
                                                        Modifier
                                                            .offset {
                                                                androidx.compose.ui.unit.IntOffset(
                                                                    dragOffset.x.toInt(),
                                                                    dragOffset.y.toInt(),
                                                                )
                                                            }
                                                            .scale(1.08f)
                                                            .shadow(
                                                                6.dp,
                                                                RoundedCornerShape(8.dp),
                                                                clip = false,
                                                            )
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                                .pointerInputDrag(
                                                    // 手势闭包必须按 item.id 绑定：这些 cell 是按位置组合的
                                                    // （无 key），而 pointerInput(Unit) 永不重启——换位后
                                                    // 位置上的 item 变了，闭包却还指着旧 item，导致
                                                    // "长按 A 却浮起 B"（用户实测 2026-11-09）
                                                    key = item.id,
                                                    onDragStart = {
                                                        draggingId = item.id
                                                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                                    },
                                                    onDrag = { delta ->
                                                        dragOffset += delta
                                                    },
                                                    onDragEnd = {
                                                        // 松手才真正换位（拖动过程中只做视觉让位，
                                                        // 避免列表频繁重排导致跟手中断）
                                                        val curFrom = currentItems.indexOfFirst { it.id == item.id }
                                                        if (curFrom >= 0) {
                                                            val dCol = (dragOffset.x / (cwPx + gapPx)).toInt()
                                                            val dRow = (dragOffset.y / (chPx + gapPx)).toInt()
                                                            val t = (curFrom + dRow * cols + dCol)
                                                                .coerceIn(0, currentItems.size - 1)
                                                            if (t != curFrom) onMove(curFrom, t)
                                                        }
                                                        draggingId = null
                                                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                                    },
                                                ),
                                            onDelete = { onDelete(item.id) },
                                        )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(gap))
                        }
                    }
                    // 滚动提示渐变（用户需求 2026-11-10）：还有内容可看时在上下边缘
                    // 渐隐一层背景色，暗示可以继续滑。用背景色而非黑色——面板本身是
                    // 主题底，渐变到底色才自然
                    val bg = MaterialTheme.colorScheme.background
                    if (canScrollUp) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(bg, bg.copy(alpha = 0f)),
                                    ),
                                ),
                        )
                    }
                    if (canScrollDown) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(32.dp)
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(bg.copy(alpha = 0f), bg),
                                    ),
                                ),
                        )
                    }
                }
            }

            // ---- 底部三按钮 ----
            // 保存中不再显示内嵌进度条：进度由独立的 CollageProgressDialog 承担
            // （用户需求 2026-11-10，弹窗带圆环+百分比+取消），此处按钮保持可见但禁用
            // 按钮宽度自适应内容、两端对齐（用户反馈 2026-11-10：均分会让
            // 「保存拼图」被压成两行）。主按钮靠右，两个次按钮靠左
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenSettings, enabled = !saving) {
                    Text(I18n.t("模板设置"), maxLines = 1)
                }
                TextButton(onClick = onClose, enabled = !saving) {
                    Text(I18n.t("返回"), maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSave,
                    enabled = items.isNotEmpty() && !saving,
                ) {
                    Text(I18n.t("保存拼图"), maxLines = 1)
                }
            }
            }
        }
    }
}

/**
 * 拼图模式的连拍入口按钮（替换原相册缩略图位置，v0.6.19 用户需求 2026-11-09）：
 * 背景=最后一张连拍缩略图，「N张」白字黑描边**压在缩略图正中央**。
 * 描边用 Canvas 双绘（先 STROKE 后 FILL）——minSdk 24 的 Compose Text 不支持 drawStyle=Stroke。
 */
@Composable
fun CollageBurstThumb(
    items: List<CollageItem>,
    rot: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(52.dp)
                .rotate(rot.toFloat())
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    1.5.dp,
                    if (items.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    RoundedCornerShape(10.dp),
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            val last = items.lastOrNull()
            if (last != null) {
                AsyncImage(
                    model = last.file,
                    contentDescription = I18n.t("连拍预览"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = I18n.t("连拍预览"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        // 张数角标：白字 + 黑描边，**压在缩略图正中央**（z 轴叠加，用户拍板 2026-11-09）。
        // 叠加后照片主体被遮，所以描边必须够粗才可读
        if (items.isNotEmpty()) {
            val countText = I18n.t("{n}张").replace("{n}", "${items.size}")
            androidx.compose.foundation.Canvas(
                Modifier
                    .align(Alignment.Center)
                    .size(52.dp)
                    .rotate(rot.toFloat()),
            ) {
                val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size.height * 0.30f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                    color = android.graphics.Color.WHITE
                }
                val stroke = android.graphics.Paint(fill).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = size.height * 0.075f
                    color = android.graphics.Color.BLACK
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                val tx = size.width / 2f
                // 垂直居中：baseline = 中心 + (ascent+descent 的一半)
                val fm = fill.fontMetrics
                val ty = size.height / 2f - (fm.ascent + fm.descent) / 2f
                drawContext.canvas.nativeCanvas.apply {
                    drawText(countText, tx, ty, stroke)
                    drawText(countText, tx, ty, fill)
                }
            }
        }
    }
}

/**
 * 拼图保存进度弹窗（用户需求 2026-11-10）：张数多时合成耗时很长，需要明确的
 * 进度反馈 + 可取消。圆环进度 + 百分比 + 当前步骤文字 + 取消按钮。
 *
 * 不设 onDismissRequest 关闭途径：合成中误触外部不应该中断，只认取消按钮。
 */
@Composable
fun CollageProgressDialog(
    progress: String?,
    fraction: Float,
    onCancel: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { /* 合成中不允许点外部关闭，只认取消按钮 */ },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.size(72.dp),
                        strokeWidth = 6.dp,
                    )
                    Text(
                        "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    progress ?: I18n.t("正在生成拼图…"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                TextButton(onClick = onCancel) {
                    // 取消是中断性操作，用危险红（用户需求 2026-11-10）
                    Text(
                        I18n.t("取消保存"),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 连拍一格：缩略图 + 右上角删除按钮 */
@Composable
private fun CollageCell(
    item: CollageItem,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                RoundedCornerShape(8.dp),
            ),
    ) {
        AsyncImage(
            model = item.file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 删除按钮（右上角）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0xAA000000))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = I18n.t("删除"),
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 长按拖动换位的手势封装（参照 EditorScreen 字段拖动模式：
 * 长按起手 → 拖动累计 → 松手归位）。
 * key 必须传 item.id：cell 按位置组合，key=Unit 会让闭包停在被换位前的旧 item 上。
 */
/**
 * 长按拖动换位的手势封装。
 *
 * key 必须传 item.id：cell 按位置组合，key=Unit 会让闭包停在被换位前的旧 item 上。
 *
 * 网格外层永远挂着 verticalScroll（用户需求 2026-11-10 固定尺寸+滚动），
 * 长按与滚动会抢手势：这里在 **Initial pass** 先吃掉 down 事件，让滚动拿不到起手，
 * 从而保证长按拖动可靠；未长按而直接滑动时 down 已被消费，滚动仍能通过
 * 后续 move 事件接管（Compose 滚动的 nested scroll 走 Main pass）。
 */
private fun Modifier.pointerInputDrag(
    key: Any,
    onDragStart: () -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { onDragStart() },
            onDrag = { change, delta ->
                change.consume()
                onDrag(delta)
            },
            onDragEnd = { onDragEnd() },
            onDragCancel = { onDragEnd() },
        )
    },
)
