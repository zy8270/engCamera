package com.engcamera.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.TemplateBackend
import com.engcamera.data.TemplateOptionDef
import com.engcamera.data.TemplatePresets
import com.engcamera.data.WatermarkField
import com.engcamera.data.WatermarkState
import com.engcamera.data.WatermarkStyle
import com.engcamera.data.WatermarkTemplate
import com.engcamera.ui.theme.ChromeScrim
import com.engcamera.watermark.WatermarkRenderer
import com.engcamera.watermark.WebTemplateRenderer
import kotlin.math.roundToInt

/** 模板缩略图/编辑预览共用的示例动态值（虚构，无隐私） */
val SAMPLE_DYNAMIC = DynamicValues(
    time = "2026-10-12 16:30:00",
    location = "广东省广州市天河区示例路 88 号 (23.1291,113.2644)",
    weather = "晴 26℃",
)

/**
 * 水印模板选择器：屏幕居中面板，三列网格展示全部模板（原生 7 + 网页 3 + 用户导入）。
 * 点卡片即应用；长按用户导入的模板删除；头部可导入/导出 HTML 模板。
 */
@Composable
fun TemplateSelector(
    visible: Boolean,
    vm: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val userTpls by vm.userTemplates.collectAsState()
    val templates = remember(userTpls) { TemplatePresets.all + userTpls }
    var deleteTarget by remember { mutableStateOf<WatermarkTemplate?>(null) }

    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    fun importSucceeded(name: String?) {
        android.widget.Toast.makeText(
            context,
            if (name != null) "已导入：$name" else "导入失败：无法识别的模板文本",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        if (name != null) {
            showImport = false
            importText = ""
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val name = text?.let {
                vm.importTemplateText(it, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "导入模板")
            }
            importSucceeded(name)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：独立淡入淡出
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(ChromeScrim)
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
            exit = androidx.compose.animation.slideOutVertically(
                androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.75f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 12.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    // 头部：标题 | 导入 导出 关闭
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "水印模板",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "点卡片应用 · 长按导入模板可删除",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showImport = true }) { Text("导入") }
                        TextButton(onClick = {
                            val data = vm.exportTemplateText()
                            if (data == null) {
                                android.widget.Toast.makeText(context, "当前没有可导出的模板", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("engCamera模板", data.second))
                                android.widget.Toast.makeText(
                                    context,
                                    "已复制到剪贴板（${data.first}），发给他人粘贴导入即可",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }) { Text("导出") }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(templates, key = { it.id }) { tpl ->
                            TemplateCard(
                                template = tpl,
                                selected = tpl.id == state.templateId,
                                onApply = {
                                    vm.applyTemplate(tpl.id)
                                },
                                onLongPress = {
                                    if (!tpl.builtIn) deleteTarget = tpl
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showImport) {
            AlertDialog(
                onDismissRequest = { showImport = false },
                title = { Text("导入模板") },
                text = {
                    Column {
                        Text(
                            "粘贴模板文本（JSON 或网页 HTML），也可从文件选择。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            placeholder = { Text("粘贴到这里") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = {
                            importLauncher.launch(arrayOf("text/*", "application/*"))
                        }) { Text("从文件选择…") }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = importText.isNotBlank(),
                        onClick = {
                            val name = vm.importTemplateText(importText, "导入模板")
                            importSucceeded(name)
                        },
                    ) { Text("导入") }
                },
                dismissButton = {
                    TextButton(onClick = { showImport = false }) { Text("取消") }
                },
            )
        }

        if (deleteTarget != null) {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除模板") },
                text = { Text("确定删除导入的模板《${deleteTarget?.name}》？") },
                confirmButton = {
                    TextButton(onClick = {
                        vm.deleteUserTemplate(deleteTarget!!.id)
                        deleteTarget = null
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                },
            )
        }
    }
}

private const val Toast_SHORT = 0

/** 三列网格里的单个模板卡片：预览缩略图 + 名称 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TemplateCard(
    template: WatermarkTemplate,
    selected: Boolean,
    onApply: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(12.dp),
                )
                .combinedClickable(onClick = onApply, onLongClick = onLongPress)
                .padding(5.dp),
        ) {
            TemplateThumb(template, Modifier.fillMaxSize())
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "当前模板",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            template.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 模板缩略图：原生直接绘制；网页走 WebView 缓存（miss 时触发渲染，tick 回来重组） */
@Composable
private fun TemplateThumb(template: WatermarkTemplate, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val context = LocalContext.current
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.roundToInt().coerceAtLeast(1)
        val fakeState = remember(template) {
            // 全量快照模板（导入的）：直接用快照状态（含字段值），缩略图=导出时的样子
            template.snapshot ?: WatermarkState(
                templateId = template.id,
                fields = template.fields.map {
                    WatermarkField(
                        id = "f_${it.type.name.lowercase()}",
                        type = it.type,
                        label = it.label,
                        value = if (it.type == FieldType.PROJECT_NAME) "示例工程" else "",
                        enabled = it.enabled,
                        isTitle = it.isTitle,
                        deletable = it.deletable,
                    )
                },
                style = template.style.copy(maxWidthRatio = 1f),
            )
        }
        if (template.backend == TemplateBackend.WEB) {
            // 网页模板按成片参考宽渲染再等比缩放：用缩略图实际宽度会让
            // max-width 内容挤成窄竖条（用户反馈"预览宽度窄过了"）
            val webWidth = 1080
            val tick by WebTemplateRenderer.tick.collectAsState()
            val key = WebTemplateRenderer.cacheKey(fakeState, template, SAMPLE_DYNAMIC, webWidth)
            // 内存缓存优先；miss 时先显磁盘缓存的上次渲染结果（秒开，避免选择器开一片空白），
            // 后台渲染完成后 tick 重组自动换新图并覆盖写盘
            val bmp = remember(key, tick) { WebTemplateRenderer.current(key) }
            val diskBmp = remember(template.id) { WebTemplateRenderer.loadThumb(context, template.id) }
            LaunchedEffect(key) {
                if (WebTemplateRenderer.current(key) == null) {
                    WebTemplateRenderer.warmAsync(
                        context, key, template, fakeState, SAMPLE_DYNAMIC, webWidth,
                        diskThumbId = template.id,
                    )
                }
            }
            val shown = bmp ?: diskBmp
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .alpha(0.4f),
                    )
                }
            }
        } else {
            val block = remember(fakeState, widthPx) {
                WatermarkRenderer.render(fakeState, widthPx, SAMPLE_DYNAMIC)
            }
            Image(
                bitmap = block.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 统一预设色板：黑 白 蓝 黄 绿 红（点选：背景 50% 半透明/文字不透明）；首格为模板原始颜色重置；彩虹圆圈自由取色 */
private val PRESET_COLORS = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFF1D4E89L, 0xFFFFB300L, 0xFF43A047L, 0xFFE53935L,
)

private val RAINBOW = listOf(
    0xFFFF5252L, 0xFFFFD740L, 0xFF69F0AEL, 0xFF40C4FFL, 0xFF7C4DFFL, 0xFFFF4081L, 0xFFFF5252L,
)

private fun styleColor(key: String, style: WatermarkStyle): Long = when (key) {
    "accentColor" -> style.accentColor
    "titleBgColor" -> style.titleBgColor
    "titleTextColor" -> style.titleTextColor
    "bodyBgColor" -> style.bodyBgColor
    "bodyTextColor" -> style.bodyTextColor
    "borderColor" -> style.borderColor
    "recDotColor" -> style.recDotColor
    "gradientColor" -> style.gradientColor
    else -> 0xFF000000L
}

/** 模板选项编辑卡（默认折叠，点头展开）：颜色=统一色板+彩虹自由取色，数值=滑杆 */
@Composable
fun TemplateOptionsCard(
    template: WatermarkTemplate,
    style: WatermarkStyle,
    onColor: (String, Long) -> Unit,
    onScalar: (String, Float) -> Unit,
    onSwitch: (String, Boolean) -> Unit,
) {
    var expanded by remember(template.id) { mutableStateOf(false) }
    var pickingKey by remember { mutableStateOf<String?>(null) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "模板选项 · ${template.name}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    // 记录点四个子选项（大小/左边距/光晕/颜色）不单独平铺，收进"记录点"组内嵌套渲染
                    val recSubKeys = setOf("recDotSize", "recDotOffset", "recGlow", "recDotColor")
                    var recGroupOpen by remember(template.id) { mutableStateOf(false) }
                    template.options.forEach { opt ->
                        when {
                            opt.key in recSubKeys -> Unit
                            opt.key == "recDot" -> {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 点标签/箭头展开收起子设置；开关独立在右侧只管开关
                                    Row(
                                        Modifier
                                            .weight(1f)
                                            .clickable { recGroupOpen = !recGroupOpen }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            opt.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Icon(
                                            if (recGroupOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = if (recGroupOpen) "收起记录点设置" else "展开记录点设置",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Switch(
                                        checked = style.recDot,
                                        onCheckedChange = { onSwitch(opt.key, it) },
                                    )
                                }
                                androidx.compose.animation.AnimatedVisibility(visible = recGroupOpen) {
                                    Column(Modifier.padding(start = 12.dp)) {
                                        template.options.firstOrNull { it.key == "recDotSize" }
                                            ?.let { SliderOptionRow(it, style, onScalar) }
                                        template.options.firstOrNull { it.key == "recDotOffset" }
                                            ?.let { SliderOptionRow(it, style, onScalar) }
                                        template.options.firstOrNull { it.key == "recGlow" }
                                            ?.let { SliderOptionRow(it, style, onScalar) }
                                        template.options.firstOrNull { it.key == "recDotColor" }
                                            ?.let { colorOpt ->
                                                ColorOptionRow(
                                                    label = colorOpt.label,
                                                    current = styleColor(colorOpt.key, style),
                                                    original = styleColor(colorOpt.key, template.style),
                                                    isBackground = false,
                                                    onColor = { onColor(colorOpt.key, it) },
                                                )
                                            }
                                    }
                                }
                            }
                            else -> when (opt.kind) {
                                "switch" -> {
                                    val current = when (opt.key) {
                                        "recDot" -> style.recDot
                                        else -> false
                                    }
                                    Row(
                                        Modifier
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            opt.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Switch(
                                            checked = current,
                                            onCheckedChange = { onSwitch(opt.key, it) },
                                        )
                                    }
                                }
                                "color" -> ColorOptionRow(
                                    label = opt.label,
                                    current = styleColor(opt.key, style),
                                    original = styleColor(opt.key, template.style),
                                    // 背景色预设点选默认 50% 半透明；文字色预设不透明（用户拍板 2026-10-12）
                                    isBackground = opt.key.endsWith("BgColor"),
                                    onColor = { onColor(opt.key, it) },
                                )
                                else -> SliderOptionRow(opt, style, onScalar)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
        pickingKey?.let { key ->
            ColorPickerDialog(
                title = "选择${template.options.firstOrNull { it.key == key }?.label ?: "颜色"}",
                initial = styleColor(key, style),
                onDismiss = { pickingKey = null },
                onConfirm = { c ->
                    onColor(key, c)
                    pickingKey = null
                },
            )
        }
    }
}

/** 数值选项行：标签+百分比值 + 滑杆（值映射自 WatermarkStyle 对应字段） */
@Composable
private fun SliderOptionRow(
    opt: TemplateOptionDef,
    style: WatermarkStyle,
    onScalar: (String, Float) -> Unit,
) {
    val current = when (opt.key) {
        "bgOpacity" -> style.bgOpacity.toFloat()
        "cornerRatio" -> style.cornerRatio
        "maxWidthRatio" -> style.maxWidthRatio
        "recDotSize" -> style.recDotSize
        "recDotOffset" -> style.recDotOffset
        "recGlow" -> style.recGlow
        "borderWidth" -> style.borderWidth
        "webScale" -> style.webScale
        else -> opt.min
    }
    val display = when (opt.key) {
        "bgOpacity" -> "${current.roundToInt()}"
        // 倍率类（边框粗细/记录点大小/网页整体缩放）按 ×N 显示
        "borderWidth", "recDotSize", "webScale" -> "×" + "%.1f".format(current)
        else -> "${(current * 100).roundToInt()}%"
    }
    Text(
        "${opt.label} $display",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
        value = current.coerceIn(opt.min, opt.max),
        onValueChange = { onScalar(opt.key, it) },
        valueRange = opt.min..opt.max,
    )
}

/** 颜色选项行：首格=模板原始颜色重置（背景填原始色+刷新图标）；预设圆圈（背景 50% 半透明/文字不透明）+ 彩虹圆圈（自由取色 RGBA） */
@Composable
private fun ColorOptionRow(
    label: String,
    current: Long,
    original: Long,
    isBackground: Boolean,
    onColor: (Long) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 原始颜色圆圈：背景填模板原始色，叠加刷新图标，点击重置到原始颜色（含原始透明度）
            val originalSelected = current == original
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(original))
                    .border(
                        width = if (originalSelected) 2.dp else 1.dp,
                        color = if (originalSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { onColor(original) },
                contentAlignment = Alignment.Center,
            ) {
                // 图标颜色随原始色明暗自适应（Compose 分量是 0..1 浮点：
                // 有效明度按浅色卡片底混合半透明色后判级，浅色底黑图标/深色底白图标）
                val c = Color(original)
                val effLum = (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue) * c.alpha +
                    0.94f * (1f - c.alpha)
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "重置到模板原始颜色",
                    tint = if (effLum > 0.6f) {
                        Color.Black.copy(alpha = 0.75f)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    modifier = Modifier.size(15.dp),
                )
            }
            PRESET_COLORS.forEach { c ->
                val selected = (current and 0x00FFFFFF) == (c and 0x00FFFFFF)
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        )
                        .clickable {
                            // 预设按原色应用：背景色固定 50% 半透明、文字色不透明（可预期的透明度，
                            // 避免 v96 保留透明度导致"透明度 0 时预设隐形"的陷阱）
                            val alpha = if (isBackground) 0x80L shl 24 else 0xFFL shl 24
                            onColor((c and 0x00FFFFFFL) or alpha)
                        },
                )
            }
            val custom = current != original &&
                PRESET_COLORS.none { (it and 0x00FFFFFF) == (current and 0x00FFFFFF) }
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Brush.sweepGradient(RAINBOW.map { Color(it) }))
                    .border(
                        width = if (custom) 2.dp else 1.dp,
                        color = if (custom) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    )
                    .clickable { showPicker = true },
            )
        }
        if (showPicker) {
            ColorPickerDialog(
                title = "选择$label",
                initial = current,
                onDismiss = { showPicker = false },
                onConfirm = { c ->
                    onColor(c)
                    showPicker = false
                },
            )
        }
    }
}
