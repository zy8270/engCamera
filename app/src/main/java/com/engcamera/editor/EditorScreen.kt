package com.engcamera.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkField
import com.engcamera.watermark.WatermarkRenderer
import kotlin.math.roundToInt

/** 水印编辑器（计划书 3.4）：字段增删、启用禁用、拖动排序、重力开关，修改即时保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    val vm: EditorViewModel = viewModel()
    val state by vm.state.collectAsState()
    val dynamic by vm.dynamic.collectAsState()
    val rotationDeg by vm.rotationDeg.collectAsState()
    val autoLocation by vm.locationText.collectAsState()
    val fieldHistory by vm.fieldHistory.collectAsState()

    var editingField by remember { mutableStateOf<WatermarkField?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showProjectGroups by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Scaffold(
        floatingActionButton = {
            // 工程组入口：屏幕右下角悬浮按钮（带阴影）
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .padding(end = 16.dp, bottom = 16.dp)
                    .shadow(8.dp, RoundedCornerShape(14.dp)),
                onClick = { showProjectGroups = true },
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProjectGroupIcon()
                    Spacer(Modifier.width(8.dp))
                    Text("工程组", color = Color.White)
                }
            }
        },
        topBar = {
            TopAppBar(
                title = { Text("水印编辑器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onBack) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部实时预览区
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.roundToInt()
                    val block = remember(state, dynamic, widthPx) {
                        WatermarkRenderer.render(state, widthPx, dynamic)
                    }
                    Image(
                        bitmap = block.bitmap.asImageBitmap(),
                        contentDescription = "水印预览",
                        modifier = Modifier.graphicsLayer { rotationZ = rotationDeg },
                    )
                }
            }
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.fields, key = { _, f -> f.id }) { _, field ->
                    val isDragging = draggingId == field.id
                    // pointerInput 的 key 稳定（不随重组重启），闭包里直接捕获的状态会停留在
                    // 首次组合的旧值——必须经 rememberUpdatedState 取最新；也不能把 fields
                    // 放进 key（拖动排序中 move 会改列表，重启手势会断拖）
                    val fieldsNow by rememberUpdatedState(state.fields)
                    FieldRow(
                        field = field,
                        offsetY = if (isDragging) dragOffset else 0f,
                        elevated = isDragging,
                        onToggle = { vm.setEnabled(field.id, !field.enabled) },
                        onEdit = { editingField = field },
                        onDelete = { vm.remove(field.id) },
                        modifier = Modifier
                            .zIndex(if (isDragging) 1f else 0f)
                            .pointerInput(field.id, state.fields.size) {
                                // 用当前行实际高度做步进交换，不依赖 listState.layoutInfo
                                // （避免 move 后布局信息尚未更新导致的位置竞态）
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = field.id
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (draggingId != field.id) return@detectDragGesturesAfterLongPress
                                        dragOffset += amount.y
                                        val from = fieldsNow.indexOfFirst { it.id == field.id }
                                        if (from < 0) return@detectDragGesturesAfterLongPress
                                        val rowH = size.height.toFloat()
                                        if (rowH <= 0) return@detectDragGesturesAfterLongPress
                                        // 拖过半行高度即与相邻行交换，offset 补偿保持视觉跟手
                                        if (dragOffset >= rowH / 2 && from < fieldsNow.size - 1) {
                                            vm.move(from, from + 1)
                                            dragOffset -= rowH
                                        } else if (dragOffset <= -rowH / 2 && from > 0) {
                                            vm.move(from, from - 1)
                                            dragOffset += rowH
                                        }
                                    },
                                    onDragEnd = {
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingId = null
                                        dragOffset = 0f
                                    },
                                )
                            },
                    )
                }

                item {
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("添加字段")
                    }
                }



                
            }
        }
    }

    editingField?.let { field ->
        EditFieldDialog(
            field = field,
            history = (fieldHistory[field.label] ?: emptyList()).reversed(),
            autoLocation = autoLocation,
            currentTime = dynamic.time,
            onRefreshLocation = { vm.refreshLocation() },
            onDismiss = { editingField = null },
            onConfirm = { label, value, isTitle ->
                vm.updateField(field.id, label, value, isTitle)
                editingField = null
            },
        )
    }

    if (showAddDialog) {
        AddFieldDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { type, label, value ->
                vm.addField(type, label, value)
                showAddDialog = false
            },
        )
    }

    ProjectGroupSelector(visible = showProjectGroups, vm = vm, onDismiss = { showProjectGroups = false })
}

/** 工程组图标：三条横线，最后一条带三角形（表示一组配置的集合/切换） */
@Composable
private fun ProjectGroupIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(width = 20.dp, height = 16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.2f
        drawLine(Color.White, Offset(0f, h * 0.15f), Offset(w * 0.85f, h * 0.15f), stroke)
        drawLine(Color.White, Offset(0f, h * 0.45f), Offset(w * 0.85f, h * 0.45f), stroke)
        val y = h * 0.78f
        drawLine(Color.White, Offset(0f, y), Offset(w * 0.55f, y), stroke)
        val tri = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.62f, y - h * 0.16f)
            lineTo(w * 0.62f, y + h * 0.16f)
            lineTo(w * 0.82f, y)
            close()
        }
        drawPath(tri, Color.White)
    }
}

/**
 * 工程组选择器：右下角弹出约半屏（下滑入+遮罩淡入，与设置抽屉同款动画）；
 * 点工程名切换，勾选后批量删除，笔重命名。
 */
@Composable
private fun ProjectGroupSelector(visible: Boolean, vm: EditorViewModel, onDismiss: () -> Unit) {
    val groups by vm.projects.collectAsState()
    var pendingDelete by remember { mutableStateOf(setOf<String>()) }
    var showCreate by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<com.engcamera.data.ProjectGroupEntry?>(null) }

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
                    .background(Color(0x88000000))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } },
            )
        }
        // 面板：右下角约半屏，圆角+阴影，从底部滑入
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.slideInVertically(
                androidx.compose.animation.core.tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) { it } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
            exit = androidx.compose.animation.slideOutVertically(
                androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            ) { it } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
        ) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.5f)
                .shadow(10.dp, RoundedCornerShape(20.dp))
                .background(Color(0xFF1E1E22), RoundedCornerShape(20.dp))
                .navigationBarsPadding()
                .padding(12.dp),
        ) {
            // 头部：工程组 | 删除工程 | 新建工程
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "工程组",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "删除工程",
                    color = if (pendingDelete.isEmpty()) Color(0xFF666666) else Color(0xFFFF6E6E),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = pendingDelete.isNotEmpty()) {
                            vm.deleteProjects(pendingDelete)
                            pendingDelete = emptySet()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "新建工程",
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showCreate = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "点工程名切换；勾选后可批量删除",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCAC4D0),
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(Modifier.weight(1f)) {
                items(groups.projects.size, key = { groups.projects[it].id }) { i ->
                    val entry = groups.projects[i]
                    val active = entry.id == groups.current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = entry.id in pendingDelete,
                            onCheckedChange = { on ->
                                pendingDelete = if (on) pendingDelete + entry.id else pendingDelete - entry.id
                            },
                        )
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    vm.selectProject(entry.id)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        ) {
                            Text(
                                entry.name + if (active) "（当前）" else "",
                                color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                        IconButton(onClick = {
                            renameTarget = entry
                            showRename = true
                        }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "重命名" + entry.name,
                                tint = Color(0xFFCAC4D0),
                            )
                        }
                    }
                }
            }
        }
        }

        if (showRename && renameTarget != null) {
            var name by remember(renameTarget) { mutableStateOf(renameTarget!!.name) }
            AlertDialog(
                onDismissRequest = { showRename = false },
                title = { Text("重命名工程") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("工程名") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.renameProject(renameTarget!!.id, name)
                        showRename = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showRename = false }) { Text("取消") }
                },
            )
        }

        if (showCreate) {
            var name by remember { mutableStateOf("工程" + (groups.projects.size + 1)) }
            AlertDialog(
                onDismissRequest = { showCreate = false },
                title = { Text("新建工程") },
                text = {
                    Column {
                        Text("将复制当前工程的水印内容，可在切换后修改。")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("工程名") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.createProject(name)
                        showCreate = false
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreate = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun FieldRow(
    field: WatermarkField,
    offsetY: Float,
    elevated: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 手势块以 field.id 为 key、不随重组重启，直接捕获 onEdit 会拿到旧闭包
    // （旧 field 快照）——编辑保存后重点行打开的是修改前的值。经 rememberUpdatedState 取最新。
    val currentOnEdit by rememberUpdatedState(onEdit)
    Surface(
        color = if (elevated) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        shadowElevation = if (elevated) 8.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = offsetY },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DragHandle, contentDescription = "排序手柄（长按拖动）")
            Switch(checked = field.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .pointerInput(field.id) { detectTapGestures { currentOnEdit() } },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, fontWeight = FontWeight.Medium)
                    if (field.isTitle) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "标题",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    field.value.ifBlank {
                        when (field.type) {
                            FieldType.DATETIME -> "拍照时自动填写时间"
                            FieldType.LOCATION -> "自动定位（可点此处手动编辑）"
                            FieldType.COUNT -> "每次拍照自动 +1"
                            else -> "未填写"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 仅备注/自定义字段可删除；时间、位置、计数为固定项（只可关闭隐藏）
            if (field.type == FieldType.NOTE || field.type == FieldType.CUSTOM) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除字段")
                }
            }
        }
    }
}

@Composable
private fun EditFieldDialog(
    field: WatermarkField,
    history: List<String>,
    autoLocation: String,
    currentTime: String,
    onRefreshLocation: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (label: String, value: String, isTitle: Boolean) -> Unit,
) {
    // key 用 field 本身（data class 结构相等），确保字段更新后重开对话框显示最新值
    var label by remember(field) { mutableStateOf(field.label) }
    // 时间/位置为空时预填当前值方便编辑（只在打开瞬间取值，不会随每秒刷新重置用户输入）
    var value by remember(field) {
        mutableStateOf(
            when {
                field.type == FieldType.DATETIME && field.value.isBlank() -> currentTime
                field.type == FieldType.LOCATION && field.value.isBlank() ->
                    autoLocation.takeIf { it.isNotBlank() && !it.startsWith("未获取") && it != "定位中…" }
                        ?: field.value
                else -> field.value
            },
        )
    }
    var isTitle by remember(field) { mutableStateOf(field.isTitle) }
    // 时间字段手动值二次确认：非空时间会定格后续所有拍照，防误触（预填值+随手点确定）
    var confirmTimeFreeze by remember(field) { mutableStateOf(false) }
    val isCount = field.type == FieldType.COUNT
    val isLocation = field.type == FieldType.LOCATION
    val isDateTime = field.type == FieldType.DATETIME

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑字段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("标签") }, singleLine = true)
                OutlinedTextField(
                    value = value,
                    onValueChange = { v ->
                        // 计数只允许纯数字
                        value = if (isCount) v.filter { it.isDigit() }.take(6) else v
                    },
                    label = {
                        Text(
                            when {
                                isCount -> "数值（纯数字，每次拍照保存后自动 +1）"
                                isDateTime -> "时间（留空 = 拍照时自动填写当前时间）"
                                isLocation -> "位置（留空 = 拍照时自动定位）"
                                else -> "内容"
                            },
                        )
                    },
                    keyboardOptions = if (isCount) {
                        KeyboardOptions(keyboardType = KeyboardType.Number)
                    } else {
                        KeyboardOptions.Default
                    },
                )
                if (isLocation) {
                    Text(
                        "自动定位：$autoLocation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRefreshLocation) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新定位")
                    }
                }
                // 历史记录快速填充（按标签，最近在前）
                if (!isCount && !isLocation && !isDateTime && history.isNotEmpty()) {
                    Text(
                        "最近使用（点击填充）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    history.forEach { item ->
                        Text(
                            item,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { value = item }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isDateTime && value.isNotBlank()) {
                    confirmTimeFreeze = true
                } else {
                    onConfirm(label, value, isTitle)
                }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    // 时间定格二次确认
    if (confirmTimeFreeze) {
        AlertDialog(
            onDismissRequest = { confirmTimeFreeze = false },
            title = { Text("时间将定格") },
            text = {
                Text("手动设置时间后，后续所有拍照都会使用这个固定时间（清空后恢复自动取当前时间）。确定要定格时间吗？")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTimeFreeze = false
                    onConfirm(label, value, isTitle)
                }) { Text("确定定格") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTimeFreeze = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AddFieldDialog(
    onDismiss: () -> Unit,
    onConfirm: (FieldType, String, String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加字段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("标签（不填默认为备注）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("内容（可不填）") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(FieldType.NOTE, label, value) }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
