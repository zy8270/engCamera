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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val name = text?.let {
                vm.importTemplate(it, uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "导入模板")
            }
            android.widget.Toast.makeText(
                context,
                if (name != null) "已导入：$name" else "导入失败：未找到 {{字段}} 占位符",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val data = if (uri != null) vm.exportTemplateHtml() else null
        if (uri != null && data != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(data.second.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "已导出：${data.first}", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure {
                android.widget.Toast.makeText(context, "导出失败", android.widget.Toast.LENGTH_SHORT).show()
            }
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
                        TextButton(onClick = {
                            importLauncher.launch(arrayOf("text/*", "application/*"))
                        }) { Text("导入") }
                        TextButton(onClick = {
                            val data = vm.exportTemplateHtml()
                            if (data == null) {
                                android.widget.Toast.makeText(context, "内置原生模板暂不支持导出", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                exportLauncher.launch(data.first)
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
            WatermarkState(
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
            val tick by WebTemplateRenderer.tick.collectAsState()
            val key = WebTemplateRenderer.cacheKey(fakeState, template, SAMPLE_DYNAMIC, widthPx)
            val bmp = remember(key, tick) { WebTemplateRenderer.current(key) }
            LaunchedEffect(key) {
                if (WebTemplateRenderer.current(key) == null) {
                    WebTemplateRenderer.warmAsync(context, key, template, fakeState, SAMPLE_DYNAMIC, widthPx)
                }
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
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

/** 统一预设色板：黑 白 蓝 黄 绿 红 紫（点选保留当前透明度）；彩虹圆圈进入自由取色（RGBA） */
private val PRESET_COLORS = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFF1D4E89L, 0xFFFFB300L, 0xFF43A047L, 0xFFE53935L, 0xFF7E57C2L,
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
    else -> 0xFF000000L
}

/** 模板选项编辑卡（默认折叠，点头展开）：颜色=统一色板+彩虹自由取色，数值=滑杆 */
@Composable
fun TemplateOptionsCard(
    template: WatermarkTemplate,
    style: WatermarkStyle,
    onColor: (String, Long) -> Unit,
    onScalar: (String, Float) -> Unit,
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
                    template.options.forEach { opt ->
                        when (opt.kind) {
                            "color" -> ColorOptionRow(
                                label = opt.label,
                                current = styleColor(opt.key, style),
                                onColor = { onColor(opt.key, it) },
                            )
                            else -> {
                                val current = when (opt.key) {
                                    "bgOpacity" -> style.bgOpacity.toFloat()
                                    "cornerRatio" -> style.cornerRatio
                                    else -> opt.min
                                }
                                Text(
                                    "${opt.label} ${current.roundToInt()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    value = current.coerceIn(opt.min, opt.max),
                                    onValueChange = { onScalar(opt.key, it) },
                                    valueRange = opt.min..opt.max,
                                )
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

/** 颜色选项行：五个预设圆圈（点选保留当前透明度）+ 彩虹圆圈（自由取色 RGBA） */
@Composable
private fun ColorOptionRow(label: String, current: Long, onColor: (Long) -> Unit) {
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
                            // 预设按原色应用（不保留当前透明度）——保留透明度会形成
                            // "透明度为 0 后点任何预设都不见效"的陷阱；半透明请走彩虹圈调透明度
                            onColor(c)
                        },
                )
            }
            val custom = PRESET_COLORS.none { (it and 0x00FFFFFF) == (current and 0x00FFFFFF) }
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
