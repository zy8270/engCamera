package com.engcamera.editor

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkField
import com.engcamera.ui.theme.LocalAppDarkTheme
import com.engcamera.watermark.WatermarkRenderer
import kotlin.math.roundToInt
import com.engcamera.i18n.I18n

/** 水印编辑器（计划书 3.4）：字段增删、启用禁用、拖动排序、重力开关，修改即时保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(onBack: () -> Unit) {
    val vm: EditorViewModel = viewModel()
    val context = LocalContext.current
    // 系统栏图标对比度按当前主题（相机页强制浅色图标，回到本页恢复）
    val dark = LocalAppDarkTheme.current
    DisposableEffect(dark) {
        val style =
            if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
    val state by vm.state.collectAsState()
    val dynamic by vm.dynamic.collectAsState()
    val rotationDeg by vm.rotationDeg.collectAsState()
    val autoLocation by vm.locationText.collectAsState()
    val weatherText by vm.weatherText.collectAsState()
    val fieldHistory by vm.fieldHistory.collectAsState()
    val groups by vm.projects.collectAsState()

    var editingField by remember { mutableStateOf<WatermarkField?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showProjectGroups by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // ---------- 眼睛开关：预览背景换相机实时画面（所见即所得参考调整，默认闭眼） ----------
    var eyeOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val hasCameraPermission = remember {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val cameraPreviewView = remember {
        androidx.camera.view.PreviewView(context).apply {
            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
            implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    // 释放闸门：主相机真正释放完（HAL 报后摄可用或 2s 兜底）才显示眼睛——睁眼时相机
    // 必已空闲，直接绑定即可，无需任何等待/看门狗（用户拍板方案 2026-10-16）
    val cameraReady by com.engcamera.camera.CameraGate.ready.collectAsState()
    val cameraBound = remember { mutableStateOf(false) }
    DisposableEffect(eyeOpen, lifecycleOwner) {
        if (!eyeOpen || !hasCameraPermission) {
            cameraBound.value = false
            onDispose { }
        } else {
            var disposed = false
            var boundPreview: androidx.camera.core.Preview? = null
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            var cm: android.hardware.camera2.CameraManager? = null
            var availabilityCb: android.hardware.camera2.CameraManager.AvailabilityCallback? = null

            fun cleanupAvailability() {
                availabilityCb?.let { cb ->
                    cm?.let { runCatching { it.unregisterAvailabilityCallback(cb) } }
                }
                availabilityCb = null
            }

            // 绑定+流验证：闸门（可用信号）不保证绑定必出流（逻辑相机关了子设备可能
            // 还在收尾、个别设备信号来早）——绑完 1s 查一次，没流就解绑、等下一个
            // 可用信号再绑，≤3 轮自愈（快速进编辑器点眼睛的黑屏根治，2026-10-16）
            fun bindOnce(attempt: Int) {
                if (disposed || attempt > 3) return
                runCatching {
                    val provider = androidx.camera.lifecycle.ProcessCameraProvider
                        .getInstance(context).get()
                    boundPreview?.let { provider.unbind(it) }
                    boundPreview = null
                    cameraBound.value = false
                    provider.unbindAll()
                    val preview = androidx.camera.core.Preview.Builder().build()
                    preview.setSurfaceProvider(cameraPreviewView.surfaceProvider)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )
                    boundPreview = preview
                    cameraBound.value = true
                    mainHandler.postDelayed(
                        {
                            if (disposed || boundPreview == null) return@postDelayed
                            if (cameraPreviewView.previewStreamState.value ==
                                androidx.camera.view.PreviewView.StreamState.STREAMING
                            ) {
                                return@postDelayed // 出流，收工
                            }
                            // 静默无流：解绑，等下一个「可用」信号（真释放）再试；
                            // 800ms 也补一次防信号丢失
                            boundPreview?.let { provider.unbind(it) }
                            boundPreview = null
                            cameraBound.value = false
                            val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                                as android.hardware.camera2.CameraManager
                            cm = manager
                            cleanupAvailability()
                            val nextAttempt = attempt + 1
                            val cb = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
                                override fun onCameraAvailable(id: String) {
                                    if (!disposed && boundPreview == null) {
                                        cleanupAvailability()
                                        bindOnce(nextAttempt)
                                    }
                                }
                            }
                            availabilityCb = cb
                            runCatching { manager.registerAvailabilityCallback(cb, mainHandler) }
                            mainHandler.postDelayed(
                                { if (!disposed && boundPreview == null) bindOnce(nextAttempt) },
                                800,
                            )
                        },
                        1000,
                    )
                }.onFailure {
                    mainHandler.postDelayed({ bindOnce(attempt + 1) }, 500)
                }
            }
            bindOnce(0)
            onDispose {
                disposed = true
                mainHandler.removeCallbacksAndMessages(null)
                cleanupAvailability()
                cameraBound.value = false
                runCatching {
                    boundPreview?.let {
                        androidx.camera.lifecycle.ProcessCameraProvider
                            .getInstance(context).get().unbind(it)
                    }
                }
            }
        }
    }

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
                    Text(I18n.t("工程组"), color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    val current = groups.projects.firstOrNull { it.id == groups.current }?.name
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(I18n.t("水印编辑器"))
                        if (current != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                current,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.t("返回"))
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = I18n.t("重置当前模板数据"))
                    }
                    TextButton(onClick = onBack) { Text(I18n.t("保存")) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 顶部实时预览区：点整块预览 = 打开水印模板选择器；右下角魔法棒同入口（保留备用）
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { showTemplates = true },
            ) {
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                ) {
                    val previewWpx = with(LocalDensity.current) { maxWidth.toPx() }.roundToInt()
                    val previewHpx = with(LocalDensity.current) { maxHeight.toPx() }.roundToInt()
                    if (eyeOpen) {
                        // 相机实时背景 + 水印浮层：液态玻璃实时折射（glassSource=PreviewView，
                        // API33+ 走库 GPU）。占满整个预览框不留边不留圆角（用户拍板 2026-10-16）；
                        // 水印恒居中展示（不吃九宫格锚点、禁止拖动——只做效果参考，用户拍板）
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.ui.viewinterop.AndroidView(
                                factory = { cameraPreviewView },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (cameraBound.value) {
                                com.engcamera.watermark.WatermarkOverlay(
                                    state = state.copy(anchorX = 0.5f, anchorY = 0.5f),
                                    dynamic = dynamic,
                                    rotationDeg = rotationDeg,
                                    onTap = { showTemplates = true },
                                    onDrag = { _, _ -> }, // 禁止拖动：居中纯展示
                                    modifier = Modifier.fillMaxSize(),
                                    glassSource = cameraPreviewView,
                                )
                            }
                        }
                    } else {
                        // 静态预览：背景与水印浮层都铺满整个预览框（无内边距）——显示与
                        // 玻璃采样同几何，折射位置才对得上（用户反馈 2026-10-16）
                        val useBackdrop by vm.editorBackdrop.collectAsState()
                        // 截帧中心裁剪到预览框宽高比：背景显示(FillBounds)与玻璃采样共用同一张
                        val rawFrame = com.engcamera.camera.EditorBackdrop.frame
                        val frame = remember(rawFrame, previewWpx, previewHpx) {
                            rawFrame?.takeIf { !it.isRecycled }?.let {
                                com.engcamera.camera.EditorBackdrop.cropToAspect(it, previewWpx.toFloat(), previewHpx.toFloat())
                            }
                        }
                        if (useBackdrop && frame != null) {
                            Image(
                                bitmap = frame.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            com.engcamera.ui.theme.GeometricPatternBackground(Modifier.fillMaxSize())
                        }
                        // 图案模式也给玻璃同几何的采样源（山形位图版），玻璃才有折射
                        val patternDark = com.engcamera.ui.theme.LocalAppDarkTheme.current
                        val patternProvider: () -> android.graphics.Bitmap? =
                            remember(patternDark, previewWpx, previewHpx) {
                                val w = previewWpx.coerceAtMost(480)
                                val bmp = com.engcamera.ui.theme.renderPatternBitmap(
                                    patternDark, w,
                                    (w * previewHpx / previewWpx).coerceAtLeast(1),
                                )
                                val provider: () -> android.graphics.Bitmap? = { bmp }
                                provider
                            }
                        com.engcamera.watermark.WatermarkOverlay(
                            state = state.copy(anchorX = 0.5f, anchorY = 0.5f),
                            dynamic = dynamic,
                            rotationDeg = rotationDeg,
                            onTap = { showTemplates = true },
                            onDrag = { _, _ -> }, // 静态预览纯展示，禁止拖动
                            modifier = Modifier.fillMaxSize(),
                            backdropProvider = if (useBackdrop && frame != null) {
                                // 采样源必须跟随实际显示的背景（v226）：截帧不可用时
                                // 背景已回落几何图案，采样仍取 null 会让玻璃只剩占位底
                                { frame }
                            } else {
                                patternProvider
                            },
                        )
                    } // else（静态预览）结束
                    // 魔棒：预览窗左下角=打开模板选择器（用户拍板 2026-10-16：要让用户
                    // 看到魔棒图标才知道它是切模板的入口；右下角是眼睛=相机预览开关）
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(com.engcamera.ui.theme.ChromeNeutral)
                            .clickable { showTemplates = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.AutoFixHigh,
                            contentDescription = I18n.t("水印模板"),
                            tint = com.engcamera.ui.theme.ChromeIcon,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    // 眼睛开关：主相机释放完才显示（CameraGate）；闭眼=静态预览；
                    // 睁眼=相机实时背景（水印居中纯展示，效果所见即所得）
                    if (cameraReady) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(com.engcamera.ui.theme.ChromeNeutral)
                                .clickable {
                                    if (hasCameraPermission) {
                                        eyeOpen = !eyeOpen
                                    } else {
                                        android.widget.Toast.makeText(
                                            context, I18n.t("无相机权限，请先到相机页授权"), android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (eyeOpen) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (eyeOpen) I18n.t("关闭相机预览") else I18n.t("打开相机预览"),
                                tint = com.engcamera.ui.theme.ChromeIcon,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                // 模板选项置顶（当前模板暴露的可调样式）
                val currentTemplate = com.engcamera.data.WatermarkTemplates.find(state.templateId)
                if (currentTemplate != null && currentTemplate.options.isNotEmpty()) {
                    item {
                        TemplateOptionsCard(
                            template = currentTemplate,
                            style = state.style,
                            anchorX = state.anchorX,
                            anchorY = state.anchorY,
                            onAnchor = { ax, ay -> vm.setAnchor(ax, ay) },
                            onColor = vm::setTemplateColor,
                            onScalar = vm::setTemplateScalar,
                            onSwitch = vm::setTemplateSwitch,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
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
                    Text(I18n.t("添加字段"))
                }
            }

            // 底部占位：字段多时可多滑一段，避免右下角两枚悬浮按钮盖住最后一行的删除按钮
            item { Spacer(Modifier.height(132.dp)) }



                
            }
        }
    }

    editingField?.let { field ->
        EditFieldDialog(
            field = field,
            history = (fieldHistory[field.label] ?: emptyList()).reversed(),
            autoLocation = autoLocation,
            autoWeather = weatherText,
            currentTime = dynamic.time,
            weatherEmojiOn = state.style.weatherEmoji,
            onWeatherEmojiChange = { vm.setTemplateSwitch("weatherEmoji", it) },
            showLatLngOn = state.style.showLatLng,
            onLatLngChange = { vm.setTemplateSwitch("showLatLng", it) },
            onRefreshLocation = { vm.refreshLocation() },
            onRefreshWeather = { vm.refreshWeather() },
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

    if (showResetConfirm) {
        val tplName = com.engcamera.data.WatermarkTemplates.find(state.templateId)?.name ?: I18n.t("当前模板")
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(I18n.t("重置当前模板")) },
            text = {
                Text(
                    I18n.t("将把字段值恢复为默认（标题/计数/备注等回默认文字），并把模板样式（颜色、记录点等选项）") +
                        I18n.t("还原为「{t}」的初始状态；不会切换模板，水印位置与重力设置保留。").replace("{t}", tplName),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetToTemplate()
                    showResetConfirm = false
                }) { Text(I18n.t("确认重置")) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(I18n.t("取消")) }
            },
        )
    }

    com.engcamera.projectgroup.ProjectGroupSelector(
        visible = showProjectGroups,
        handler = vm,
        onDismiss = { showProjectGroups = false },
    )

    TemplateSelector(
        visible = showTemplates,
        vm = vm,
        onDismiss = { showTemplates = false },
    )
}

/** 工程组图标：三条横线，最后一条带三角形；颜色跟随 onPrimary（编辑器 FAB 用） */
@Composable
private fun ProjectGroupIcon(modifier: Modifier = Modifier) {
    com.engcamera.ui.ProjectGroupGlyph(
        modifier.size(width = 20.dp, height = 16.dp),
        color = MaterialTheme.colorScheme.onPrimary,
    )
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
            Icon(Icons.Default.DragHandle, contentDescription = I18n.t("排序手柄（长按拖动）"))
            Switch(checked = field.enabled, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .pointerInput(field.id) { detectTapGestures { currentOnEdit() } },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(I18n.t(field.label), fontWeight = FontWeight.Medium)
                    if (field.isTitle) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            I18n.t("标题"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 时间/位置/天气被手动定格（值非空）时提示：不会自动刷新
                    if ((field.type == FieldType.DATETIME || field.type == FieldType.LOCATION ||
                            field.type == FieldType.WEATHER) && field.value.isNotBlank()
                    ) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            I18n.t("自定义"),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFB300),
                        )
                    }
                }
                Text(
                    field.value.ifBlank {
                        when (field.type) {
                            FieldType.DATETIME -> I18n.t("拍照时自动填写时间")
                            FieldType.LOCATION -> I18n.t("自动定位（可点此处手动编辑）")
                            FieldType.COUNT -> I18n.t("每次拍照自动 +1")
                            FieldType.WEATHER -> I18n.t("自动获取天气（可手动填写）")
                            else -> I18n.t("未填写")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 仅可删除字段（deletable 由模板定义；旧数据已按类型规则迁移）显示删除按钮
            if (field.deletable) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = I18n.t("删除字段"))
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
    autoWeather: String,
    currentTime: String,
    weatherEmojiOn: Boolean,
    onWeatherEmojiChange: (Boolean) -> Unit,
    showLatLngOn: Boolean,
    onLatLngChange: (Boolean) -> Unit,
    onRefreshLocation: () -> Unit,
    onRefreshWeather: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (label: String, value: String, isTitle: Boolean) -> Unit,
) {
    // key 用 field 本身（data class 结构相等），确保字段更新后重开对话框显示最新值
    var label by remember(field) { mutableStateOf(field.label) }
    // 时间/位置为空时预填当前值方便编辑（只在打开瞬间取值，不会随每秒刷新重置用户输入）
    val initialValue = remember(field) {
        when {
            field.type == FieldType.DATETIME && field.value.isBlank() -> currentTime
            field.type == FieldType.LOCATION && field.value.isBlank() ->
                autoLocation.takeIf { it.isNotBlank() && !it.startsWith(I18n.t("未获取")) && it != I18n.t("定位中…") }
                    ?: field.value
            field.type == FieldType.WEATHER && field.value.isBlank() ->
                autoWeather.takeIf { it.isNotBlank() } ?: field.value
            else -> field.value
        }
    }
    var value by remember(field) { mutableStateOf(initialValue) }
    // 内容框用 TextFieldValue：光标/选区可控——自动聚焦时光标落在文字末尾而非最前
    // （用户反馈 2026-10-16）；程序赋值（跟随刷新）也始终把光标放到末尾
    var valueTfv by remember(field) {
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                initialValue,
                androidx.compose.ui.text.TextRange(initialValue.length),
            ),
        )
    }
    fun setValueText(t: String) {
        value = t
        valueTfv = androidx.compose.ui.text.input.TextFieldValue(
            t,
            androidx.compose.ui.text.TextRange(t.length),
        )
    }
    var isTitle by remember(field) { mutableStateOf(field.isTitle) }
    // 时间字段手动值二次确认：非空时间会定格后续所有拍照，防误触（预填值+随手点确定）
    var confirmTimeFreeze by remember(field) { mutableStateOf(false) }
    // 刷新定位/天气后，输入框跟随最新自动值——仅对话框本地状态：
    // 点"确定"才写入字段（进入自定义），点"取消"不留任何痕迹；一打字即停止跟随
    var followLocation by remember(field) { mutableStateOf(false) }
    var followWeather by remember(field) { mutableStateOf(false) }
    LaunchedEffect(followLocation, autoLocation) {
        if (followLocation) {
            autoLocation.takeIf {
                it.isNotBlank() && !it.startsWith(I18n.t("未获取")) && it != I18n.t("定位中…")
            }?.let { setValueText(it) }
        }
    }
    LaunchedEffect(followWeather, autoWeather) {
        if (followWeather && autoWeather.isNotBlank()) {
            setValueText(autoWeather)
        }
    }
    val isCount = field.type == FieldType.COUNT
    val isLocation = field.type == FieldType.LOCATION
    val isDateTime = field.type == FieldType.DATETIME
    val isWeather = field.type == FieldType.WEATHER

    // 打开对话框即聚焦"内容"输入框并调起输入法（用户拍板 2026-10-13）：
    // requestFocus 之外必须把对话框窗口 softInputMode 设为 ALWAYS_VISIBLE，否则部分系统不弹键盘
    val valueFocus = remember { FocusRequester() }
    val dialogView = LocalView.current
    LaunchedEffect(field) {
        valueFocus.requestFocus()
        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("编辑字段")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(I18n.t("标签")) }, singleLine = true)
                OutlinedTextField(
                    value = valueTfv,
                    onValueChange = { v ->
                        // 计数只允许纯数字；手动输入即停止跟随刷新值
                        valueTfv = v
                        value = if (isCount) v.text.filter { it.isDigit() }.take(6) else v.text
                        followLocation = false
                        followWeather = false
                    },
                    modifier = Modifier.focusRequester(valueFocus),
                    label = {
                        Text(
                            when {
                                isCount -> I18n.t("数值（纯数字，每次拍照保存后自动 +1）")
                                isDateTime -> I18n.t("时间（留空 = 拍照时自动填写当前时间）")
                                isLocation -> I18n.t("位置（留空 = 拍照时自动定位）")
                                isWeather -> I18n.t("天气（留空 = 拍照时自动获取）")
                                else -> I18n.t("内容")
                            },
                        )
                    },
                    keyboardOptions = if (isCount) {
                        KeyboardOptions(keyboardType = KeyboardType.Number)
                    } else {
                        KeyboardOptions.Default
                    },
                )
                if (isWeather) {
                    // 天气 emoji 开关：挂在天气字段编辑内（用户拍板 2026-10-13，不进模板选项卡），即时生效
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            I18n.t("天气 Emoji（如 ☀️ 晴 26℃）"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = weatherEmojiOn,
                            onCheckedChange = onWeatherEmojiChange,
                        )
                    }
                    Text(
                        I18n.t("自动天气：{w}").replace("{w}", if (autoWeather.isBlank()) I18n.t("未获取") else autoWeather),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        followWeather = true
                        onRefreshWeather()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("刷新天气"))
                    }
                }
                if (isLocation) {
                    // 显示经纬度开关：挂在位置字段编辑内（默认关，用户拍板 2026-10-16），即时生效
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            I18n.t("显示经纬度（地址后括号段）"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = showLatLngOn,
                            onCheckedChange = onLatLngChange,
                        )
                    }
                    Text(
                        I18n.t("自动定位：{loc}").replace("{loc}", autoLocation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        followLocation = true
                        onRefreshLocation()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("刷新定位"))
                    }
                }
                // 历史记录快速填充（按标签，最近在前）：最多显示 5 行可上下滚动，右侧滚动条
                if (!isCount && !isLocation && !isDateTime && !isWeather && history.isNotEmpty()) {
                    Text(
                        I18n.t("最近使用（点击填充）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val historyState = rememberLazyListState()
                    // 每行约 35dp；高度随条数动态（最多 5 行），条数少不撑空白
                    val historyRows = history.size.coerceAtMost(5)
                    Box(Modifier.height((historyRows * 35).dp)) {
                        LazyColumn(
                            state = historyState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp),
                        ) {
                            items(history.size) { idx ->
                                val item = history[idx]
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
                        // 滚动条：滑块位置在绘制阶段更新（graphicsLayer 读派生状态），
                        // 不随滚动逐帧重组，避免抖动
                        val trackDp = (historyRows * 35).dp
                        val infoForSize = historyState.layoutInfo
                        val totalItems = infoForSize.totalItemsCount
                        val visibleItems = infoForSize.visibleItemsInfo.size
                        if (totalItems > visibleItems && visibleItems > 0) {
                            val thumbDp = (trackDp * visibleItems / totalItems).coerceAtLeast(24.dp)
                            val fraction by remember {
                                androidx.compose.runtime.derivedStateOf {
                                    val info = historyState.layoutInfo
                                    val total = info.totalItemsCount
                                    val first = info.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
                                    val itemPx = first.size
                                    val viewportPx = info.viewportEndOffset - info.viewportStartOffset
                                    val contentPx = itemPx * total
                                    if (contentPx <= viewportPx) return@derivedStateOf 0f
                                    val scrolledPx = first.index * itemPx - first.offset
                                    (scrolledPx.toFloat() / (contentPx - viewportPx)).coerceIn(0f, 1f)
                                }
                            }
                            val density = LocalDensity.current
                            val trackPx = with(density) { trackDp.toPx() }
                            val thumbPx = with(density) { thumbDp.toPx() }
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .width(3.dp)
                                    .height(thumbDp)
                                    .graphicsLayer {
                                        translationY = fraction * (trackPx - thumbPx)
                                    }
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.40f)),
                            )
                        }
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
            }) { Text(I18n.t("确定")) }
        },
        dismissButton = {
            Row {
                // 时间/位置/天气：清空手动值恢复自动获取；计数：重置为 1。均关闭对话框
                if (isDateTime || isLocation || isWeather) {
                    TextButton(onClick = { onConfirm(label, "", isTitle) }) { Text(I18n.t("重置")) }
                } else if (isCount) {
                    TextButton(onClick = { onConfirm(label, "1", isTitle) }) { Text(I18n.t("重置")) }
                }
                TextButton(onClick = onDismiss) { Text(I18n.t("取消")) }
            }
        },
    )

    // 时间定格二次确认
    if (confirmTimeFreeze) {
        AlertDialog(
            onDismissRequest = { confirmTimeFreeze = false },
            title = { Text(I18n.t("时间将定格")) },
            text = {
                Text(I18n.t("手动设置时间后，后续所有拍照都会使用这个固定时间（清空后恢复自动取当前时间）。确定要定格时间吗？"))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTimeFreeze = false
                    onConfirm(label, value, isTitle)
                }) { Text(I18n.t("确定定格")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTimeFreeze = false }) { Text(I18n.t("取消")) }
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
        title = { Text(I18n.t("添加字段")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(I18n.t("标签（不填默认为备注）")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(I18n.t("内容（可不填）")) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(FieldType.NOTE, label, value) }) { Text(I18n.t("添加")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("取消")) }
        },
    )
}
