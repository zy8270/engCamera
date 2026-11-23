package com.engcamera.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.engcamera.data.AddressParts
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkStyle
import com.engcamera.data.addressKeyLabel
import com.engcamera.data.assembleAddress
import com.engcamera.data.assembledLocation
import com.engcamera.data.parseAddressKeys
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
    // 退出编辑器时先关掉所有本地对话框再导航（v0.6.24 修复）。
    // AlertDialog 是**独立窗口**，不归 NavHost 的层级管：pop 后 Camera 页已经组合完成、
    // 但编辑器这一帧还没 dispose 时，弹窗窗口仍可被触摸命中——用户实测"返回主界面后
    // 0.1 秒内点对焦，会闪现一下编辑字段窗口"。所以退出路径必须先清本地状态。
    var closeAllDialogs by remember { mutableStateOf(false) }
    val exitEditor: () -> Unit = {
        closeAllDialogs = true
        onBack()
    }
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
    val refreshingLocation by vm.refreshingLocation.collectAsState()
    val refreshingWeather by vm.refreshingWeather.collectAsState()
    // 失败诊断（v0.6.35）：错误码/超时原因，追加在自动定位/自动天气后面红字显示
    val locationError by vm.locationError.collectAsState()
    val weatherError by vm.weatherError.collectAsState()
    val fieldHistory by vm.fieldHistory.collectAsState()
    val groups by vm.projects.collectAsState()
    // 定位异常（与相机页同一份状态：用户在相机页点过提示条的 X，这里也不再提示）
    val locDenied by vm.locationPermissionDenied.collectAsState()
    val locDisabled by vm.locationServiceDisabled.collectAsState()

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // 位置字段编辑里的"去授权"：先重新申请（首次拒绝后系统不再弹窗），仍拒绝就跳应用设置
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) vm.refreshLocation() else openAppSettings()
    }

    // 位置字段编辑对话框里的定位提示：与相机页提示条同一套判断，能定位就不显示
    val locationHintText: String? = when {
        locDenied -> I18n.t("未获得定位权限，水印位置将无法自动填写")
        locDisabled -> I18n.t("系统定位服务未开启，水印位置将无法自动填写")
        // 权限与开关都正常但拿不到点（室内/无 GMS/系统限制）：也给一行提示，
        // 否则"自动定位：未获取定位"看起来像没做自动定位。
        // 刷新中的"定位中…"不算失败（马上就有结果，别闪一下提示）
        isLocationUnavailable(autoLocation) -> I18n.t("暂时无法获取定位，可点下方「刷新定位」重试")
        else -> null
    }
    val locationHintAction: String? = when {
        locDenied -> I18n.t("去授权")
        locDisabled -> I18n.t("去开启")
        else -> null
    }
    val onLocationHintAction: () -> Unit = {
        when {
            locDenied -> locationPermLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            locDisabled -> openLocationSettings()
        }
    }

    // 编辑器内的定位会话（v0.6.33，用户反馈 2026-11-16）：相机页 onDispose 会把定位解绑，
    // 而 NavHost 切页是"新页先组合、旧页后 dispose"——不在这里持有一份，编辑器里就没有
    // 任何持续定位注册，点刷新只能拿到一次性结果（系统定位图标闪一下就灭），
    // 「自动定位：…」与水印预览也不会自己更新。
    //
    // v0.6.35 补：编辑器同样要按生命周期释放。此前只有 onDispose（离开页面）才解绑，
    // 用户按 Home 退到后台时注册仍在——加上相机页的持有者，通知栏定位标志会一直亮。
    // 这里挂 ON_PAUSE/ON_RESUME，退后台立刻让出定位（编辑器后台没有实时预览的需求）。
    val editorLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(editorLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.startLocationSession()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.stopLocationSession()
                else -> Unit
            }
        }
        editorLifecycle.lifecycle.addObserver(observer)
        vm.startLocationSession()
        onDispose {
            editorLifecycle.lifecycle.removeObserver(observer)
            vm.stopLocationSession()
        }
    }

    var editingField by remember { mutableStateOf<WatermarkField?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showProjectGroups by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showAntiFakeDialog by remember { mutableStateOf(false) }

    // 退出时把所有弹窗状态一次性清掉（见 exitEditor 注释）：
    // 单靠导航 pop 无法及时销毁 Dialog 的独立窗口，残留窗口会被下一帧的触摸命中
    LaunchedEffect(closeAllDialogs) {
        if (closeAllDialogs) {
            editingField = null
            showAddDialog = false
            showProjectGroups = false
            showTemplates = false
            showResetConfirm = false
            showAntiFakeDialog = false
        }
    }

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
                    IconButton(onClick = exitEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.t("返回"))
                    }
                },
                actions = {
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = I18n.t("重置当前模板数据"))
                    }
                    TextButton(onClick = exitEditor) { Text(I18n.t("保存")) }
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
                    Column {
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
                    // 防伪码入口（v0.6.16，用户需求）：紧贴在「计数」字段下方。
                    // 没有计数字段的模板（打卡系列）在列表末尾兜底补一行
                    if (field.type == FieldType.COUNT) {
                        AntiFakeRow(
                            enabled = state.antiFake,
                            manualCode = state.antiFakeCode,
                            badgeEnabled = state.brandBadge,
                            watermarkTooHigh = state.anchorY < 0.75f,
                            onToggle = vm::setAntiFake,
                            onOpen = { showAntiFakeDialog = true },
                        )
                    }
                    }
                }

            // 兜底：模板没有「计数」字段时，防伪码入口放列表末尾（否则无处可放）
            if (state.fields.none { it.type == FieldType.COUNT }) {
                item {
                    AntiFakeRow(
                        enabled = state.antiFake,
                        manualCode = state.antiFakeCode,
                        badgeEnabled = state.brandBadge,
                        watermarkTooHigh = state.anchorY < 0.75f,
                        onToggle = vm::setAntiFake,
                        onOpen = { showAntiFakeDialog = true },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // 添加字段按钮恒在所有字段（含防伪码行）的最下面（用户需求 2026-11-06）
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
            refreshingLocation = refreshingLocation,
            refreshingWeather = refreshingWeather,
            // 失败诊断（v0.6.35）：有错误时追加在原值后面红字显示，旧值仍可见
            locationError = locationError,
            weatherError = weatherError,
            currentTime = dynamic.time,
            weatherEmojiOn = state.style.weatherEmoji,
            onWeatherEmojiChange = { vm.setTemplateSwitch("weatherEmoji", it) },
            showLatLngOn = state.style.showLatLng,
            onLatLngChange = { vm.setTemplateSwitch("showLatLng", it) },
            // 地址选项（v0.6.21，Issue #3）：层级顺序与隐藏项，同步到所有模板的水印
            addressOrder = state.style.addressOrder,
            addressHidden = state.style.addressHidden,
            addressParts = dynamic.addressParts,
            onAddressOrderChange = { vm.setAddressOrder(it) },
            onAddressHiddenChange = { vm.setAddressHidden(it) },
            onRefreshLocation = { vm.refreshLocation() },
            onRefreshWeather = { vm.refreshWeather() },
            locationHint = locationHintText,
            locationHintAction = locationHintAction,
            onLocationHintAction = onLocationHintAction,
            // 时间格式（v0.6.18）：打卡/巡逻/马克工程等固定版式模板不显示选择器
            timeFormatSupported = com.engcamera.util.TimeFormat.supported(state.style.layout),
            timeFormat = state.style.timeFormat,
            onTimeFormatChange = { vm.setTimeFormat(it) },
            // 品牌 logo（v0.6.16）：仅 4 款工程模板的标题字段显示三元素（开关/选择/重置）
            logoSupported = state.style.layout in LOGO_LAYOUTS,
            logoEnabled = state.logoEnabled,
            logoFile = state.logoFile,
            logoSize = state.style.logoSize,
            onLogoEnabledChange = { vm.setLogoEnabled(it) },
            onPickLogo = { uri -> vm.setLogo(uri) },
            onResetLogo = { vm.resetLogo() },
            onLogoSizeChange = { vm.setTemplateScalar("logoSize", it) },
            onDismiss = { editingField = null },
            onConfirm = { label, value, isTitle ->
                vm.updateField(field.id, label, value, isTitle)
                editingField = null
            },
            onConfirmAndSave = { label, value, isTitle ->
                vm.updateField(field.id, label, value, isTitle)
                editingField = null
                exitEditor()
            },
            onResetField = { label, value, isTitle ->
                // 重置不算"主动编辑"：不自动打开字段开关（用户需求 2026-11-04）
                vm.updateField(field.id, label, value, isTitle, enable = false)
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

    if (showAntiFakeDialog) {
        AntiFakeDialog(
            manualCode = state.antiFakeCode,
            badgeEnabled = state.brandBadge,
            badgeFile = state.brandBadgeFile,
            badgeLine1 = state.brandBadgeLine1,
            badgeLine2 = state.brandBadgeLine2,
            badgePill = state.brandBadgePill,
            badgeScale = state.brandBadgeScale,
            onConfirm = { code ->
                vm.setAntiFakeCode(code)
                vm.setAntiFake(true)
                showAntiFakeDialog = false
            },
            // 自动生成（标题右侧）：清空手动码 = 回到按画面内容计算
            onAutoGenerate = {
                vm.setAntiFakeCode("")
                vm.setAntiFake(true)
            },
            // 品牌角标：即时生效，不走「确定」提交（与 LogoRow 同款交互）
            onBadgeEnabledChange = { vm.setBrandBadge(it) },
            onPickBadge = { uri -> vm.setBrandBadgeImage(uri) },
            onResetBadge = { vm.resetBrandBadgeImage() },
            onLine1Change = { vm.setBrandBadgeLine1(it) },
            onLine2Change = { vm.setBrandBadgeLine2(it) },
            onPillChange = { vm.setBrandBadgePill(it) },
            onResetBadgeText = { vm.resetBrandBadgeText() },
            onBadgeScaleChange = { vm.setBrandBadgeScale(it) },
            onDismiss = { showAntiFakeDialog = false },
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

/**
 * 防伪码 & 品牌行（v0.6.16 / v0.6.31）：字段列表末尾，标题+开关，副行点开编辑对话框。
 * 行开关**只管防伪码**（角标有自己独立的开关，在对话框里）。
 * 水印锚点高于画面 3/4 时副行转橙提示（指纹采样区是上部 62.5%，水印压进来会污染）。
 */
@Composable
private fun AntiFakeRow(
    enabled: Boolean,
    manualCode: String,
    badgeEnabled: Boolean,
    watermarkTooHigh: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与 FieldRow 的拖动/开关列对齐（无拖动柄，用等宽占位）
            Spacer(Modifier.width(24.dp))
            Switch(checked = enabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(8.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpen),
            ) {
                Text(I18n.t("防伪码&品牌"), fontWeight = FontWeight.Medium)
                Text(
                    when {
                        enabled && manualCode.isNotBlank() -> I18n.t("手动：{c}").replace("{c}", manualCode)
                        enabled && watermarkTooHigh -> I18n.t("水印位置过高，防伪校验会失真")
                        enabled -> I18n.t("自动：按画面内容计算")
                        // 防伪码关但角标开着：说明右下角仍有东西，别让用户以为整块没启用
                        badgeEnabled -> I18n.t("仅品牌角标")
                        else -> I18n.t("未启用")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled && watermarkTooHigh && manualCode.isBlank()) Color(0xFFFFB300)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 防伪码 & 品牌编辑对话框（v0.6.31）：
 * 上段=防伪码（14 位输入 + 三行边界说明），「自动生成」移到**标题右侧**
 * （用户需求：像位置/天气那样，不占正文空间）；
 * 下段=品牌角标（开关 / 选图 / 编辑文字 / 重置），全部即时生效。
 * 「编辑文字」展开抽屉（对话框随之加高），三个输入框改三行文案。
 */
@Composable
private fun AntiFakeDialog(
    manualCode: String,
    badgeEnabled: Boolean,
    badgeFile: String,
    badgeLine1: String,
    badgeLine2: String,
    badgePill: String,
    badgeScale: Float,
    onConfirm: (String) -> Unit,
    onAutoGenerate: () -> Unit,
    onBadgeEnabledChange: (Boolean) -> Unit,
    onPickBadge: (android.net.Uri) -> Unit,
    onResetBadge: () -> Unit,
    onLine1Change: (String) -> Unit,
    onLine2Change: (String) -> Unit,
    onPillChange: (String) -> Unit,
    onResetBadgeText: () -> Unit,
    onBadgeScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(manualCode) }
    // 文字编辑抽屉展开状态（用户需求：点击展开把窗口加高）
    var textExpanded by remember { mutableStateOf(false) }
    val badgePickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPickBadge(uri) }
    // 位图随 badgeFile 变化重建（选图后立刻换缩略图）
    val badgeBmp = remember(badgeFile) {
        com.engcamera.util.BrandBadgeImage.current(badgeFile)
    }
    // 选了图片就把文字抽屉收起来（v0.6.42）：「编辑文字」按钮同时隐藏，
    // 否则状态残留会在之后「重置」时突然又弹出一个展开的输入区
    LaunchedEffect(badgeFile) {
        if (badgeFile.isNotBlank()) textExpanded = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(I18n.t("防伪码&品牌"))
                // 「自定义」tag（v0.6.34，用户需求 2026-11-16）：防伪码也是"自动生成"类——
                // 默认按画面内容算，用户填了手动码才叫自定义；点「自动生成」清空后 tag 立刻消失
                if (text.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        I18n.t("自定义"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB300),
                    )
                }
                Spacer(Modifier.weight(1f))
                // 「自动生成」移到标题右侧（用户需求 2026-11-15，与刷新定位/天气同款位置）
                TextButton(
                    onClick = {
                        // 同时清掉对话框本地输入框（v0.6.34）：只改 VM 的话输入框还留着
                        // 刚才的手动码，「自定义」tag 也不会消失——用户会以为按钮没生效
                        text = ""
                        onAutoGenerate()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("自动生成"), style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        text = {
            // 抽屉展开后内容可能超高 → 允许滚动（对话框本身不滚动）
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ---------- 上段：防伪码 ----------
                Text(I18n.t("防伪码"), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = com.engcamera.util.AntiCounterfeit.sanitizeInput(it)
                    },
                    label = { Text(I18n.t("防伪码内容（14 位大写字母+数字，留空=自动）")) },
                    singleLine = true,
                )
                Text(
                    I18n.t("手动填写的码不参与内容校验，只影响图上显示的文字"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    I18n.t("带批注的成片不参与校验（防伪码只对无批注那张有效）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // ---------- 下段：品牌角标 ----------
                Text(I18n.t("品牌角标（右下角，可导入图片替换）"), fontWeight = FontWeight.Medium)
                // 按钮行：**强制单行不换行**（用户明确要求），元素超宽时压缩。
                // 顺序（v0.6.42 用户需求 2026-11-23）：开关 → 编辑文字 → 选择图片 → 重置；
                // 选了图片时「编辑文字」隐藏（此时图片整块替代文字，改文字没有可看的效果），
                // 变成：开关 → 选择图片 → 重置。
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Switch(checked = badgeEnabled, onCheckedChange = onBadgeEnabledChange)
                    // 编辑文字（带展开 V 标）：仅未使用自定义图片时出现
                    if (badgeFile.isBlank()) {
                        TextButton(
                            onClick = { textExpanded = !textExpanded },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                I18n.t("编辑文字"),
                                maxLines = 1,
                                softWrap = false,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Icon(
                                // 展开时 V 标翻转（与模板选项折叠组同款交互）
                                if (textExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    // 选图
                    TextButton(
                        onClick = {
                            badgePickLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        // 有自定义图就显示缩略图，否则只显示按钮文字
                        if (badgeBmp != null && !badgeBmp.isRecycled) {
                            Image(
                                bitmap = badgeBmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            I18n.t("选择图片"),
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 重置（同时清图片与文字，用户需求「点击重置也…」）
                    TextButton(
                        onClick = {
                            onResetBadge()
                            onResetBadgeText()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text(
                            I18n.t("重置"),
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Text(
                    when {
                        // 开关关闭 = 右下角什么都不画（v0.6.42 用户明确：关掉后文字角标也不显示）
                        !badgeEnabled -> I18n.t("未启用")
                        badgeFile.isNotBlank() -> I18n.t("已使用自定义图片")
                        badgeLine1.isNotBlank() || badgeLine2.isNotBlank() || badgePill.isNotBlank() ->
                            I18n.t("已使用自定义文字")
                        else -> I18n.t("使用默认角标")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 角标大小拉杆（v0.6.40，用户需求 2026-11-22）：只有导入了自定义图片才出现——
                // 文字角标的大小由版面反解（撑满固定宽度），调它没有意义
                if (badgeFile.isNotBlank()) {
                    SizeSliderRow(
                        label = I18n.t("角标大小"),
                        value = badgeScale,
                        range = 0.4f..2f,
                        onValueChange = onBadgeScaleChange,
                    )
                }
                // ---------- 文字编辑抽屉 ----------
                // 只在未使用自定义图片时展开（v0.6.42）：选了图片后「编辑文字」按钮已隐藏，
                // 若抽屉还开着就会出现"没有入口却显示着输入框"的错乱状态
                AnimatedVisibility(visible = textExpanded && badgeFile.isBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = badgeLine1,
                            onValueChange = onLine1Change,
                            label = { Text(I18n.t("第一行（默认：{d}）").replace("{d}", com.engcamera.util.BottomMarks.DEFAULT_LINE1)) },
                            placeholder = { Text(com.engcamera.util.BottomMarks.DEFAULT_LINE1) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = badgeLine2,
                            onValueChange = onLine2Change,
                            label = { Text(I18n.t("第二行（默认：{d}）").replace("{d}", com.engcamera.util.BottomMarks.DEFAULT_LINE2)) },
                            placeholder = { Text(com.engcamera.util.BottomMarks.DEFAULT_LINE2) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = badgePill,
                            onValueChange = onPillChange,
                            label = { Text(I18n.t("角标文字（默认：{d}）").replace("{d}", com.engcamera.util.BottomMarks.DEFAULT_PILL)) },
                            placeholder = { Text(com.engcamera.util.BottomMarks.DEFAULT_PILL) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogActionText(I18n.t("取消"), onDismiss)
                DialogActionText(I18n.t("确定")) { onConfirm(text) }
            }
        },
    )
}

/**
 * 品牌 logo 三元素行（v0.6.16，替换标题字段的标签输入框）：
 * ① 开关 ② 选图按钮（图标=当前生效 logo，文字「选择logo」）③ 重置（回默认 app 图标 + 关开关）。
 * 选图用 PickVisualMedia（系统相册单图），授权是临时的 → ViewModel 里复制进 filesDir。
 */
@Composable
private fun LogoRow(
    logoEnabled: Boolean,
    logoFile: String,
    logoSize: Float,
    onEnabledChange: (Boolean) -> Unit,
    onPick: (android.net.Uri) -> Unit,
    onReset: () -> Unit,
    onSizeChange: (Float) -> Unit,
) {
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onPick(uri) }
    // 位图随 logoFile 变化重建（选图后立刻换缩略图）
    val bmp = remember(logoFile) {
        com.engcamera.util.BrandLogo.current(logoFile)
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Switch(checked = logoEnabled, onCheckedChange = onEnabledChange)
        Spacer(Modifier.width(2.dp))
        // 选图按钮：左侧图标就是当前 logo（白底小方块，与标题栏容器观感一致）
        TextButton(
            onClick = {
                pickLauncher.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    ),
                )
            },
        ) {
            if (bmp != null && !bmp.isRecycled) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(I18n.t("选择logo"))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(I18n.t("重置"))
        }
    }
    Text(
        if (logoFile.isBlank()) I18n.t("使用默认 app 图标") else I18n.t("已使用自定义 logo"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // logo 大小拉杆（v0.6.40，用户需求 2026-11-22：从「模板选项」搬到工程名称字段编辑内）。
    // 只在开关打开时出现——关着调大小没有可看的效果
    if (logoEnabled) {
        SizeSliderRow(
            label = I18n.t("logo大小"),
            value = logoSize,
            range = 0.08f..0.5f,
            onValueChange = onSizeChange,
        )
    }
}

/**
 * 尺寸拉杆（v0.6.40）：标签 + 百分比 + Slider。
 * logo 大小与导入角标大小共用，保证两处观感一致（比例值按 % 显示，用户看不懂 0.18）。
 */
@Composable
private fun SizeSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(
            label + " " + "${(value * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
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
                            // 盾牌验证行（v0.6.42）：留空即用默认文案，别显示「未填写」
                            FieldType.VERIFY -> I18n.t("默认：已验证照片真实性")
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

/**
 * 定位自动文本是否为"占位符"（未获取/定位中）：这类值不该预填进字段。
 * 水印渲染侧写入的是 I18n.t("未获取定位")，所以必须按当前语言的译文比对，
 * 只比中文原文在英文模式下永远不命中（v0.6.14 修）。
 */
private fun isLocationPlaceholder(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return true
    val unavailable = I18n.t("未获取定位")
    return t.startsWith(unavailable) || t == I18n.t("定位中…") || t.startsWith("未获取定位")
}

/**
 * 定位真的拿不到（区别于"定位中…"：刷新正在进行，马上可能有结果，不该闪提示）。
 */
private fun isLocationUnavailable(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return true
    return t.startsWith(I18n.t("未获取定位")) || t.startsWith("未获取定位")
}

/**
 * 位置字段编辑对话框里的定位提示条（与相机页 LocationHintBar 同一套文案与动作）：
 * 权限缺失/定位开关关闭时顶掉"自动定位：…"这一行，能定位时不显示。
 */
@Composable
private fun LocationHintInline(
    text: String,
    action: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f))
            .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.LocationOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * 失败诊断行（v0.6.35，用户需求 2026-11-16：「把错误代码和超时原因显式展示」）。
 *
 * 红字高亮、小号、可换行——错误码（TIMEOUT_8000ms / DNS_FAIL / NULL_FIX 等）可能较长，
 * 必须允许折行而不是省略号截断，否则用户看不到真正的原因。
 */
@Composable
private fun ErrorLine(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 支持品牌 logo 的布局（用户需求 2026-11-04：仅 4 款工程类模板） */
private val LOGO_LAYOUTS = setOf("CLASSIC", "TODAY_CARD", "MARK_CARD", "MARK_CARD_LIGHT")

/**
 * 「会自动生成内容」的字段类型（v0.6.34，用户需求 2026-11-16）。
 *
 * 只有这几类字段才存在"自定义"语义：内容本来由系统按拍照时刻自动填写，
 * 用户手动改写才叫定格。备注/标题/施工单位等本来就是用户自己填的，不显示「自定义」。
 *
 * 防伪码不在这份清单里——它不是 WatermarkField，是独立的开关+手动码
 * （见 [AntiFakeRow] 与防伪码对话框的标题 tag）。
 */
private val AUTO_FIELD_TYPES = setOf(FieldType.DATETIME, FieldType.LOCATION, FieldType.WEATHER)

@Composable
private fun EditFieldDialog(
    field: WatermarkField,
    history: List<String>,
    autoLocation: String,
    autoWeather: String,
    // v0.6.25 刷新加载态（用户反馈 2026-11-12：点刷新后看不到任何反馈）
    refreshingLocation: Boolean,
    refreshingWeather: Boolean,
    // v0.6.35 失败诊断（用户需求 2026-11-16）：错误码 + 超时原因，追加在原值后红字显示
    locationError: String,
    weatherError: String,
    currentTime: String,
    weatherEmojiOn: Boolean,
    onWeatherEmojiChange: (Boolean) -> Unit,
    showLatLngOn: Boolean,
    onLatLngChange: (Boolean) -> Unit,
    // v0.6.21 地址选项（Issue #3）：层级顺序与隐藏项
    addressOrder: String,
    addressHidden: String,
    addressParts: AddressParts,
    onAddressOrderChange: (String) -> Unit,
    onAddressHiddenChange: (String) -> Unit,
    onRefreshLocation: () -> Unit,
    onRefreshWeather: () -> Unit,
    locationHint: String?,
    locationHintAction: String?,
    onLocationHintAction: () -> Unit,
    // v0.6.18 时间格式（仅"时间整串渲染"的模板显示选择器）
    timeFormatSupported: Boolean,
    timeFormat: String,
    onTimeFormatChange: (String) -> Unit,
    // v0.6.16 品牌 logo（仅工程类模板的标题字段显示）
    logoSupported: Boolean,
    logoEnabled: Boolean,
    logoFile: String,
    logoSize: Float,
    onLogoEnabledChange: (Boolean) -> Unit,
    onPickLogo: (android.net.Uri) -> Unit,
    onResetLogo: () -> Unit,
    onLogoSizeChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (label: String, value: String, isTitle: Boolean) -> Unit,
    onConfirmAndSave: (label: String, value: String, isTitle: Boolean) -> Unit,
    /** 重置/清空（时间/位置/天气回自动、计数回 1）：不自动打开字段开关 */
    onResetField: (label: String, value: String, isTitle: Boolean) -> Unit,
) {
    // key 用 field.id（稳定标识），避免 field 对象在重组时被重建（如上层重组传了结构相等但不同实例）
    var label by remember(field.id) { mutableStateOf(field.label) }
    // 时间/位置为空时预填当前值方便编辑（只在打开瞬间取值，不会随每秒刷新重置用户输入）
    val initialValue = remember(field.id) {
        when {
            field.type == FieldType.DATETIME && field.value.isBlank() -> currentTime
            // 位置（用户需求 2026-11-12）：预填**当前正在应用的地址**（按地址选项的开关/顺序
            // 拼装过的结果），而不是自动定位的完整原文——开关的优先级高于完整地址信息。
            // 没拿到结构化数据（老数据/离线）才退回自动定位原文。
            field.type == FieldType.LOCATION && field.value.isBlank() ->
                assembledLocation(
                    DynamicValues(location = autoLocation, addressParts = addressParts),
                    WatermarkStyle(addressOrder = addressOrder, addressHidden = addressHidden, showLatLng = showLatLngOn),
                ) ?: autoLocation.takeIf { !isLocationPlaceholder(it) } ?: field.value
            field.type == FieldType.WEATHER && field.value.isBlank() ->
                autoWeather.takeIf { it.isNotBlank() } ?: field.value
            else -> field.value
        }
    }
    var value by remember(field.id) { mutableStateOf(initialValue) }
    // 内容框用 TextFieldValue：光标/选区可控——自动聚焦时光标落在文字末尾而非最前
    // （用户反馈 2026-10-16）；程序赋值（跟随刷新）也始终把光标放到末尾
    var valueTfv by remember(field.id) {
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
    /**
     * 用户是否真正编辑过 input 内容（用户需求 2026-11-12，v0.6.33 修订）。
     *
     * **只有用户自己打字**才算自定义（写入非空 value = 定格、不再跟随自动刷新）。
     */
    var inputEdited by remember(field.id) { mutableStateOf(false) }
    /**
     * 本次对话框里是否有过**程序回填**（地址选项开关/排序、刷新定位/天气后的跟随）。
     *
     * v0.6.33 修复（用户反馈 2026-11-16）：此前程序回填只改文本、不动 [inputEdited]，
     * 于是"打字 → 点地址选项 → 确定"会把自定义状态留在 true，字段被锁在用户早先打的
     * 那几个字上，而输入框里明明显示的是自动拼装的地址——不符合直觉。
     * 现在按用户拍板的语义：只要 input 被**程序**改写过，就清掉自定义，
     * 提交时写空 = 继续跟随自动定位，水印就是输入框里看到的内容。
     */
    var autoFilled by remember(field.id) { mutableStateOf(false) }
    /** 用户输入（打字、点历史记录）：进入自定义 */
    fun markUserEdit(t: String) {
        setValueText(t)
        inputEdited = true
        autoFilled = false
    }
    /** 程序回填（地址选项预览、刷新跟随）：改文本并清除自定义 */
    fun applyAutoText(t: String) {
        setValueText(t)
        inputEdited = false
        autoFilled = true
    }
    /**
     * 提交时决定 value（v0.6.33 三态）：
     * - 用户打过字 → 写入输入框内容（进入"自定义"，定格不再跟随自动刷新）
     * - 只被程序回填过 → 写空（清掉自定义，继续跟随自动；水印与输入框所见一致）
     * - 什么都没动 → 保持字段原值（打开看一眼再点确定不该改变任何东西）
     */
    fun effectiveValue(): String = when {
        inputEdited -> value
        autoFilled -> ""
        else -> field.value
    }
    /**
     * 提交后这个字段是否会处于"自定义"状态（对话框标题右侧标签用）。
     *
     * v0.6.34（用户反馈 2026-11-16）：**只有会自动生成的字段**才有「自定义」这个概念——
     * 时间/位置/天气本来由系统自动填写，用户改写它才叫"自定义"；备注/标题这类本来就是
     * 用户自己填的字段，永远谈不上自定义，标题处不该冒出这个 tag。
     * 所以这里必须同时满足：字段类型是自动类型 + 确实被用户改过（或原本就有手动值）。
     */
    fun willBeCustom(): Boolean = field.type in AUTO_FIELD_TYPES &&
        (inputEdited || (!autoFilled && field.value.isNotBlank()))
    var isTitle by remember(field.id) { mutableStateOf(field.isTitle) }
    // 时间字段手动值二次确认：非空时间会定格后续所有拍照，防误触（预填值+随手点确定）
    var confirmTimeFreeze by remember(field.id) { mutableStateOf(false) }
    // 定格确认后是否要保存并返回相机页（"确定并保存"入口）
    var saveAndBackAfterFreeze by remember(field.id) { mutableStateOf(false) }
    // 刷新定位/天气后，输入框跟随最新自动值——仅对话框本地状态：
    // 点"确定"才写入字段（进入自定义），点"取消"不留任何痕迹；一打字即停止跟随
    var followLocation by remember(field.id) { mutableStateOf(false) }
    var followWeather by remember(field.id) { mutableStateOf(false) }
    // 时间格式选择器弹出状态（v0.6.18）
    var showTimeFormatPicker by remember(field.id) { mutableStateOf(false) }
    LaunchedEffect(followLocation, autoLocation) {
        if (followLocation) {
            // 刷新后回填也用"开关处理后的地址"（与预填同源），不能回填完整原文——
            // 否则用户关掉的层级会在刷新后又冒出来
            val shown = assembledLocation(
                DynamicValues(location = autoLocation, addressParts = addressParts),
                WatermarkStyle(
                    addressOrder = addressOrder,
                    addressHidden = addressHidden,
                    showLatLng = showLatLngOn,
                ),
            ) ?: autoLocation.takeIf { !isLocationPlaceholder(it) }
            // 程序回填：同时清掉自定义（v0.6.33，用户拍板"用户驱动 app 编辑输入框都要清自定义"）
            shown?.let { applyAutoText(it) }
        }
    }
    LaunchedEffect(followWeather, autoWeather) {
        if (followWeather && autoWeather.isNotBlank()) {
            applyAutoText(autoWeather)
        }
    }
    val isCount = field.type == FieldType.COUNT
    val isLocation = field.type == FieldType.LOCATION
    val isDateTime = field.type == FieldType.DATETIME
    val isWeather = field.type == FieldType.WEATHER
    val isVerify = field.type == FieldType.VERIFY

    // 打开对话框即聚焦"内容"输入框并调起输入法（用户拍板 2026-10-13）：
    // requestFocus 之外必须把对话框窗口 softInputMode 设为 ALWAYS_VISIBLE，否则部分系统不弹键盘
    val valueFocus = remember { FocusRequester() }
    val dialogView = LocalView.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(field.id) {
        valueFocus.requestFocus()
        (dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            ?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // 标题右侧放「刷新定位 / 刷新天气」（用户需求 2026-11-12）：原来按钮在内容区底部，
        // 点完还要往下滚才看得到结果；移到标题栏与标题同高，一眼可见且不占正文空间
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(I18n.t("编辑字段"))
                // 「自定义」标签（v0.6.33，用户需求 2026-11-16）：与字段列表里的 tag 同语义，
                // 只是挪到对话框标题右边——用户改完就能在标题处确认这个字段现在是不是被定格了。
                // 跟着输入实时变化（打字亮、地址选项/刷新清掉后灭）。
                if (willBeCustom()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        I18n.t("自定义"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFFB300),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isLocation || isWeather) {
                    val busy = if (isLocation) refreshingLocation else refreshingWeather
                    TextButton(
                        onClick = {
                            if (isLocation) {
                                followLocation = true
                                onRefreshLocation()
                            } else {
                                followWeather = true
                                onRefreshWeather()
                            }
                        },
                        // 刷新中禁用，避免连点堆积请求
                        enabled = !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        if (busy) {
                            // 刷新中显示转圈（v0.6.25）：定位要等系统给点、天气要等网络，
                            // 都不可能是毫秒级，必须给可见反馈
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            when {
                                isLocation && busy -> I18n.t("定位中…")
                                !isLocation && busy -> I18n.t("获取中…")
                                isLocation -> I18n.t("刷新定位")
                                else -> I18n.t("刷新天气")
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (field.isTitle && logoSupported) {
                    // 品牌 logo（v0.6.16，用户拍板位置）：标题字段的「标签」输入框换成三元素——
                    // 标题的字段名本来就不显示在水印上，这个输入框没有意义。
                    // ① 开关 ② 选图按钮（图标=当前 logo） ③ 重置（回默认 app 图标并关开关）
                    LogoRow(
                        logoEnabled = logoEnabled,
                        logoFile = logoFile,
                        logoSize = logoSize,
                        onEnabledChange = onLogoEnabledChange,
                        onPick = onPickLogo,
                        onReset = onResetLogo,
                        onSizeChange = onLogoSizeChange,
                    )
                } else {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(I18n.t("标签")) }, singleLine = true)
                }
                OutlinedTextField(
                    value = valueTfv,
                    onValueChange = { v ->
                        // 计数只允许纯数字；手动输入即停止跟随刷新值
                        valueTfv = v
                        value = if (isCount) v.text.filter { it.isDigit() }.take(6) else v.text
                        followLocation = false
                        followWeather = false
                        // 真正动过 input 才算自定义（见 inputEdited 注释）
                        inputEdited = true
                        autoFilled = false
                    },
                    modifier = Modifier.focusRequester(valueFocus),
                    label = {
                        Text(
                            when {
                                isCount -> I18n.t("数值（纯数字，每次拍照保存后自动 +1）")
                                isDateTime -> I18n.t("时间（留空 = 拍照时自动填写当前时间）")
                                isLocation -> I18n.t("位置（留空 = 拍照时自动定位）")
                                isWeather -> I18n.t("天气（留空 = 拍照时自动获取）")
                                isVerify -> I18n.t("验证文字（留空 = 已验证照片真实性）")
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
                // 时间格式选择器（v0.6.18，用户需求 2026-11-09）：紧贴时间输入框下方。
                // 只对"时间整串渲染"的模板显示——打卡/巡逻等固定版式模板改了也不生效，隐藏
                if (isDateTime && timeFormatSupported) {
                    TimeFormatRow(
                        current = timeFormat,
                        onClick = { showTimeFormatPicker = true },
                    )
                }
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
                    // 点击「自动天气：…」= 刷新天气（与位置文字同款交互，用户需求 2026-11-12）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (refreshingWeather) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            I18n.t("自动天气：{w}（点击刷新）")
                                .replace("{w}", if (autoWeather.isBlank()) I18n.t("未获取") else autoWeather),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = !refreshingWeather) {
                                    followWeather = true
                                    onRefreshWeather()
                                }
                                .padding(vertical = 4.dp),
                        )
                    }
                    // 失败原因（v0.6.35，用户需求 2026-11-16）：追加在「自动天气：…」下方红字，
                    // 旧值仍保留在上面——用户能同时看到"上次的值"和"这次为什么没更新"
                    if (weatherError.isNotBlank()) {
                        ErrorLine(text = I18n.t("上次失败：{e}").replace("{e}", weatherError))
                    }
                }
                if (isLocation) {
                    if (locationHint != null) {
                        // 定位拿不到时用提示顶掉"自动定位：…"（用户反馈 2026-11-01），
                        // 能定位就不显示，恢复原来的自动定位文本
                        LocationHintInline(
                            text = locationHint,
                            action = locationHintAction,
                            onAction = onLocationHintAction,
                        )
                    } else {
                        // 点击「自动定位：…」= 刷新定位（用户需求 2026-11-12 澄清：是触发刷新，
                        // 不是把内容重填进 input）。
                        // 这里恒显示**完整地址**（全部层级打开、含坐标），作为"这里有什么可用"
                        // 的预览；受开关控制的只有 input 内的文字（用户需求 2026-11-12）
                        val full = if (addressParts.hasStructured() || addressParts.hasCoords()) {
                            assembleAddress(
                                parts = addressParts,
                                order = AddressParts.DEFAULT_ORDER,
                                hidden = "",
                                showCoords = true,
                            )
                        } else {
                            autoLocation
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (refreshingLocation) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                I18n.t("自动定位：{loc}（点击刷新）").replace("{loc}", full),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(enabled = !refreshingLocation) {
                                        followLocation = true
                                        onRefreshLocation()
                                    }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                    // 定位失败原因（v0.6.35）：权限/开关类已经由上方 LocationHintInline 表达，
                    // 这里只补"有权限也拿不到点"的技术原因（超时/空点/provider 未启用）
                    if (locationError.isNotBlank() && locationHint == null) {
                        ErrorLine(text = I18n.t("上次失败：{e}").replace("{e}", locationError))
                    }
                    // 地址选项（v0.6.21，Issue #3）：原先这里只有一个「显示经纬度」开关，
                    // 现在经纬度并入可排序列表（开关状态仍写 style.showLatLng，单一真相源），
                    // 其余层级（国家/省/市/区/路/地点/邮编）都可自由开关与排序
                    AddressOptionsSection(
                        order = addressOrder,
                        hidden = addressHidden,
                        showCoords = showLatLngOn,
                        addressParts = addressParts,
                        onOrderChange = onAddressOrderChange,
                        onHiddenChange = onAddressHiddenChange,
                        onShowCoordsChange = onLatLngChange,
                        onAssembledChange = { t -> applyAutoText(t) },
                    )
                }
                // 历史记录快速填充（按标签，最近在前）：最多显示 5 行可上下滚动，右侧滚动条
                if (!isCount && !isLocation && !isDateTime && !isWeather && history.isNotEmpty()) {
                    Text(
                        I18n.t("最近使用（点击填充）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val scrollState = rememberScrollState()
                    // 每行约 35dp；高度随条数动态（最多 5 行），条数少不撑空白
                    val historyRows = history.size.coerceAtMost(5)
                    val trackDp = (historyRows * 35).dp
                    Box(Modifier.height(trackDp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 12.dp)
                                .verticalScroll(scrollState),
                        ) {
                            history.forEach { item ->
                                Text(
                                    item,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            focusManager.clearFocus()
                                            keyboard?.hide()
                                            // 用户主动挑选历史值 = 自定义（与打字同语义）
                                            markUserEdit(item)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                        // 滚动条：滑块位置在绘制阶段更新（graphicsLayer 读派生状态），
                        // 不随滚动逐帧重组，避免抖动
                        val fraction by remember {
                            androidx.compose.runtime.derivedStateOf {
                                val max = scrollState.maxValue
                                if (max <= 0) 0f else scrollState.value.toFloat() / max
                            }
                        }
                        val density = LocalDensity.current
                        val trackPx = with(density) { trackDp.toPx() }
                        val thumbDp = (trackDp * historyRows / history.size).coerceAtLeast(24.dp)
                        val thumbPx = with(density) { thumbDp.toPx() }
                        if (history.size > historyRows) {
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
            // 四个按钮统一放进同一个 Row（用户拍板 2026-10-22）：AlertDialog 原生把
            // dismiss/confirm 两组分别塞进 FlowRow，空间稍紧就折成两排；统一为一排后
            // 用等内边距的文字按钮（TextButton 有 58dp 最小宽度，短文字会被撑开导致
            // 文字间距不等），顺序固定：重置 → 取消 → 确定 → 确定并保存
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 时间/位置/天气：清空手动值恢复自动获取；计数：重置为 1。
                // 盾牌验证行（v0.6.42）：清空 = 回默认文案「已验证照片真实性」
                if (isDateTime || isLocation || isWeather || isVerify) {
                    DialogActionText(I18n.t("重置")) { onResetField(label, "", isTitle) }
                } else if (isCount) {
                    DialogActionText(I18n.t("重置")) { onResetField(label, "1", isTitle) }
                }
                DialogActionText(I18n.t("取消"), onDismiss)
                DialogActionText(I18n.t("确定")) {
                    if (isDateTime && value.isNotBlank() && inputEdited) {
                        saveAndBackAfterFreeze = false
                        confirmTimeFreeze = true
                    } else {
                        onConfirm(label, effectiveValue(), isTitle)
                    }
                }
                // 确定并保存：保存字段后直接回相机拍照页，便于快速拍下一张
                DialogActionText(I18n.t("确定并保存")) {
                    if (isDateTime && value.isNotBlank() && inputEdited) {
                        saveAndBackAfterFreeze = true
                        confirmTimeFreeze = true
                    } else {
                        onConfirmAndSave(label, effectiveValue(), isTitle)
                    }
                }
            }
        },
    )

    // 时间格式选择（v0.6.18）：选中即时写入（预览立即可见），不阻塞字段本身的确定/取消
    if (showTimeFormatPicker) {
        TimeFormatPickerDialog(
            current = timeFormat,
            onDismiss = { showTimeFormatPicker = false },
            onSelect = { pattern ->
                onTimeFormatChange(pattern)
                // 输入框内容跟着新格式一起变（用户需求 2026-11-18）：原来只改 style.timeFormat，
                // 输入框里还留着旧格式的文字，看起来像没生效。这里用同一套解析把可见文字重排；
                // 只动显示、不动 inputEdited/autoFilled——换写法不是用户自定义输入
                // （解析不了的自定义文字原样保留，不吞用户内容）
                val reformatted = com.engcamera.util.TimeFormat.apply(value, pattern)
                if (reformatted != value) setValueText(reformatted)
                showTimeFormatPicker = false
            },
        )
    }

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
                    if (saveAndBackAfterFreeze) {
                        saveAndBackAfterFreeze = false
                        onConfirmAndSave(label, effectiveValue(), isTitle)
                    } else {
                        onConfirm(label, effectiveValue(), isTitle)
                    }
                }) { Text(I18n.t("确定定格")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmTimeFreeze = false; saveAndBackAfterFreeze = false }) {
                    Text(I18n.t("取消"))
                }
            },
        )
    }
}

@Composable
private fun DialogActionText(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/**
 * 「地址选项」胶囊按钮组（v0.6.21 引入，v0.6.33 改版，用户需求 2026-11-16）。
 *
 * 背景：此前位置文本是系统 Geocoder 整行原文，巴西场景自带「邮政编码: 69100-000」，
 * 用户既关不掉邮编也控制不了任何层级。这里把各级拆开供自由开关与排序。
 *
 * 改版原因（用户 2026-11-16 提的）：原来是一个「地址选项 >」可展开区块，收起时
 * **完全看不到当前哪些层级是开的**——用户实测水印「位置：」整行为空，却不知道
 * 是因为 5 个层级全被关掉了，只能在展开后才看得见。
 *
 * 现在：一排胶囊按钮常驻可见，每个层级一个。
 * - **实心填色 = 显示**，**空心描边 = 隐藏**，一眼看清当前状态
 * - 点按切换显隐
 * - **长按拖动调整顺序**（用户明确要的方式；比 ↑/↓ 箭头快，也不用展开）
 *
 * 为什么用自动换行而不是硬塞成一排：8 个按钮（国家/省·州/市/区·街道/路/地点·小区/
 * 经纬度/邮编）在手机对话框宽度下放不进一行，硬塞只能横向滚动，而横向滚动会和
 * 长按拖动抢手势。自动换行后全部一眼可见，拖动也不冲突。
 *
 * 「经纬度」的开关仍写回 style.showLatLng（与历史开关同一真相源）。
 *
 * 每次开关/排序都会把最新拼装结果回填到位置输入框（[onAssembledChange]）；
 * 回填会清掉「自定义」状态（v0.6.33）——见对话框里 `applyAutoText` 的说明。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddressOptionsSection(
    order: String,
    hidden: String,
    showCoords: Boolean,
    addressParts: AddressParts,
    onOrderChange: (String) -> Unit,
    onHiddenChange: (String) -> Unit,
    onShowCoordsChange: (Boolean) -> Unit,
    onAssembledChange: (String) -> Unit,
) {
    // 直接算，不用 remember/derivedStateOf：解析 8 项逗号串的开销可忽略，而
    // remember(无 key) + derivedStateOf 会永久捕获首次组合的 order（参数变化读不到旧值），
    // 那才是"开关改了列表不动"的真凶
    val orderedKeys = parseAddressKeys(order)
        .let { keys -> keys + AddressParts.ALL_KEYS.filterNot { it in keys } }
    val hiddenSet = parseAddressKeys(hidden).toSet()

    /** 把当前选项应用到输入框（改开关要实时看到结果） */
    fun preview(orderedNow: List<String>, hiddenNow: Set<String>, coordsOn: Boolean) {
        if (!addressParts.hasStructured() && !addressParts.hasCoords()) return
        onAssembledChange(
            assembleAddress(
                parts = addressParts,
                order = orderedNow.joinToString(","),
                hidden = hiddenNow.joinToString(","),
                showCoords = coordsOn,
            ),
        )
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 长按计时要跑在协程里（静止手指不产生事件，手势循环内测不了超时）
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 拖动中/点击时都要用**最新**状态。AddressChip 的 pointerInput 不会因这些参数变化
    // 而重启，闭包会停在首次组合的值——这正是 v0.6.33「邮编关掉后再点打不开」的根因
    // （第二次点击用的还是第一次点击前的 hiddenSet，于是"关闭"又执行了一遍）。
    val currentOrderedKeys by rememberUpdatedState(orderedKeys)
    val currentHiddenSet by rememberUpdatedState(hiddenSet)
    val currentShowCoords by rememberUpdatedState(showCoords)
    val currentOnOrderChange by rememberUpdatedState(onOrderChange)
    val currentOnHiddenChange by rememberUpdatedState(onHiddenChange)
    val currentOnShowCoordsChange by rememberUpdatedState(onShowCoordsChange)
    val currentPreview by rememberUpdatedState<(List<String>, Set<String>, Boolean) -> Unit>(::preview)

    /** 点按切换某一层级的显隐（经纬度走独立字段，与历史开关同一真相源） */
    fun toggleKey(key: String) {
        if (key == AddressParts.KEY_COORDS) {
            val next = !currentShowCoords
            currentOnShowCoordsChange(next)
            currentPreview(currentOrderedKeys, currentHiddenSet, next)
            return
        }
        val wasOn = key !in currentHiddenSet
        val next = currentHiddenSet.toMutableSet()
        if (wasOn) next.add(key) else next.remove(key)
        currentOnHiddenChange(next.joinToString(","))
        currentPreview(currentOrderedKeys, next, currentShowCoords)
    }

    /** 把 key 移到 targetIndex（拖动中实时换位用） */
    fun moveToIndex(key: String, targetIndex: Int) {
        val list = currentOrderedKeys.toMutableList()
        val from = list.indexOf(key)
        if (from < 0) return
        val to = targetIndex.coerceIn(0, list.lastIndex)
        if (from == to) return
        list.removeAt(from)
        list.add(to, key)
        currentOnOrderChange(list.joinToString(","))
        currentPreview(list, currentHiddenSet, currentShowCoords)
    }

    // ---------- 自布局 + 让位动画 ----------
    // 不用 FlowRow：FlowRow 换位时胶囊会"瞬移"，做不到用户要的「其他开关让出空位带逃离
    // 动画」（用户需求 2026-11-16）。这里自己算每个胶囊的槽位坐标，配合
    // animateIntOffsetAsState 让非拖动的胶囊平滑滑向新位置。
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val chipTextStyle = MaterialTheme.typography.labelLarge
    val padH = 12.dp
    val padV = 7.dp
    val gap = 6.dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxW = with(density) { maxWidth.toPx() }
        val padHPx = with(density) { padH.toPx() }
        val padVPx = with(density) { padV.toPx() }
        val gapPx = with(density) { gap.toPx() }
        // 量一遍所有标签的宽度（8 个短标签，开销可忽略；随语言变化自动重算）
        val chipSizes = remember(orderedKeys, chipTextStyle, maxW, padHPx, padVPx) {
            orderedKeys.associateWith { key ->
                val label = addressKeyLabel(key)
                val t = measurer.measure(
                    androidx.compose.ui.text.AnnotatedString(label),
                    style = chipTextStyle,
                )
                androidx.compose.ui.geometry.Size(
                    t.size.width + padHPx * 2,
                    t.size.height + padVPx * 2,
                )
            }
        }
        // 按顺序折行算出每个 key 的槽位左上角
        val slotOf = remember(chipSizes, orderedKeys, maxW, gapPx) {
            val out = linkedMapOf<String, Offset>()
            var x = 0f
            var y = 0f
            var rowH = 0f
            orderedKeys.forEach { key ->
                val s = chipSizes.getValue(key)
                if (x > 0f && x + s.width > maxW) {
                    x = 0f
                    y += rowH + gapPx
                    rowH = 0f
                }
                out[key] = Offset(x, y)
                x += s.width + gapPx
                rowH = maxOf(rowH, s.height)
            }
            out
        }
        val totalH = remember(slotOf, chipSizes) {
            slotOf.entries.maxOfOrNull { (k, p) -> p.y + chipSizes.getValue(k).height } ?: 0f
        }

        var draggingKey by remember { mutableStateOf<String?>(null) }
        /** 拖动中胶囊左上角在**容器**内的坐标（容器不移动，坐标稳定） */
        var dragPos by remember { mutableStateOf(Offset.Zero) }
        /** 抓取偏移：按下点相对胶囊左上角的位移，拖动中保持它不变才算"跟手" */
        var grabOffset by remember { mutableStateOf(Offset.Zero) }
        /**
         * 拖动开始那一刻的槽位网格（v0.6.35）。
         *
         * 为什么必须冻结：若用"实时槽位 + 最近中心"算插入位，换位后被拖胶囊自己的
         * 槽位也会跟着挪，判定目标跟着变 → 它一路追到末尾，"拖出去就回不来"
         * （用户反馈 2026-11-16）。冻结后网格固定：手指回到原槽位 = 回到原下标，
         * 中途反悔完全可行。
         */
        var frozenSlots by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
        var frozenOrder by remember { mutableStateOf<List<String>>(emptyList()) }
        // pointerInput(Unit) 手势期间不重启，块内直接读 orderedKeys/slotOf 拿到的是
        // 手势开始时的旧值，统一经 rememberUpdatedState 取最新（命中判定用）
        val liveOrder by rememberUpdatedState(orderedKeys)
        val liveSlots by rememberUpdatedState(slotOf)
        val liveSizes by rememberUpdatedState(chipSizes)

        Column(Modifier.fillMaxWidth()) {
            Text(
                I18n.t("点按开关显示，长按拖动调整顺序"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { totalH.toDp() })
                    // 手势挂在**容器**上（v0.6.35 重写）：此前挂在每个胶囊上，而胶囊
                    // 拖动时自身会被 offset 搬走，指针坐标是"胶囊本地系"的——胶囊跟着
                    // 手指移动，本地位移反而互相抵消，位置算不准（用户反馈 2026-11-16
                    // 「按钮不在手指戳中的坐标」）。容器不动，坐标就是稳定的容器系。
                    .pointerInput(Unit) {
                        val slop = with(density) { 4.dp.toPx() }
                        val longPressMs = viewConfiguration.longPressTimeoutMillis
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // 命中哪个胶囊：用当前槽位直接判（容器系坐标）
                            val hit = liveOrder.firstOrNull { k ->
                                val s = liveSlots[k] ?: return@firstOrNull false
                                val sz = liveSizes.getValue(k)
                                down.position.x >= s.x && down.position.x <= s.x + sz.width &&
                                    down.position.y >= s.y && down.position.y <= s.y + sz.height
                            } ?: return@awaitEachGesture
                            val startSlot = liveSlots.getValue(hit)
                            val chipSize = liveSizes.getValue(hit)

                            var fired = false
                            var moved = 0f
                            // 长按计时放子协程：静止手指不产生事件，循环内测不了超时
                            val timer = scope.launch {
                                delay(longPressMs)
                                fired = true
                                draggingKey = hit
                                grabOffset = down.position - startSlot
                                dragPos = startSlot
                                // 冻结槽位网格：整个拖动期间的插入位都以它为基准，
                                // 手指回到原槽位就回到原下标（可反悔）
                                frozenSlots = liveSlots.toMap()
                                frozenOrder = liveOrder
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    if (fired) {
                                        ch.consume()
                                        // 手指在容器内的位置 − 抓取偏移 = 胶囊左上角
                                        dragPos = ch.position - grabOffset
                                        // 插入位：拿胶囊中心跟**冻结网格**里每个槽位中心比，
                                        // 取最近的。网格是拖动开始那一刻的，不随换位变化——
                                        // 手指拖回原槽位就回到原下标，可以随时反悔。
                                        val dc = Offset(
                                            dragPos.x + chipSize.width / 2,
                                            dragPos.y + chipSize.height / 2,
                                        )
                                        var bestIdx = 0
                                        var bestDist = Float.MAX_VALUE
                                        frozenOrder.forEachIndexed { i, k ->
                                            val s = frozenSlots[k] ?: return@forEachIndexed
                                            val sz = liveSizes.getValue(k)
                                            val sc = Offset(s.x + sz.width / 2, s.y + sz.height / 2)
                                            val dx = dc.x - sc.x
                                            val dy = dc.y - sc.y
                                            val d = dx * dx + dy * dy
                                            if (d < bestDist) {
                                                bestDist = d
                                                bestIdx = i
                                            }
                                        }
                                        val curIdx = liveOrder.indexOf(hit)
                                        if (curIdx >= 0 && bestIdx != curIdx) moveToIndex(hit, bestIdx)
                                    } else {
                                        moved += (ch.position - ch.previousPosition).getDistance()
                                        // 长按没到就滑开 → 让给外层滚动，不接管
                                        if (moved > slop) break
                                    }
                                }
                            } finally {
                                timer.cancel()
                                if (fired) {
                                    draggingKey = null
                                } else if (moved <= slop) {
                                    // 没动也没长按 = 点按，切换显隐
                                    toggleKey(hit)
                                }
                            }
                        }
                    },
            ) {
                orderedKeys.forEach { key ->
                    val isCoords = key == AddressParts.KEY_COORDS
                    val on = if (isCoords) showCoords else key !in hiddenSet
                    val isDragging = draggingKey == key
                    val size = chipSizes.getValue(key)
                    val target = slotOf.getValue(key)
                    // 非拖动胶囊：槽位一变就带动画滑过去（"让位/逃离"效果）
                    val animated by androidx.compose.animation.core.animateIntOffsetAsState(
                        targetValue = androidx.compose.ui.unit.IntOffset(
                            target.x.roundToInt(),
                            target.y.roundToInt(),
                        ),
                        animationSpec = androidx.compose.animation.core.tween(180),
                        label = "chipSlot",
                    )
                    key(key) {
                        AddressChip(
                            label = addressKeyLabel(key),
                            on = on,
                            dragging = isDragging,
                            widthPx = size.width,
                            heightPx = size.height,
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .offset {
                                    if (isDragging) {
                                        androidx.compose.ui.unit.IntOffset(
                                            dragPos.x.roundToInt(), dragPos.y.roundToInt(),
                                        )
                                    } else {
                                        animated
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个地址层级胶囊（v0.6.33 引入，v0.6.35 改为纯展示）。
 *
 * 实心 = 该层级会出现在水印里，空心描边 = 被隐藏。
 *
 * 手势**不在这里**（v0.6.35，用户反馈 2026-11-16「按钮不在手指戳中的坐标」）：
 * 胶囊拖动时自身被 offset 搬走，挂在它身上的 pointerInput 拿到的坐标是"跟着自己
 * 一起动"的本地系，算出来的位置必然偏。手势统一交给外层不动的容器（见
 * [AddressOptionsSection]），这里只负责画。
 */
@Composable
private fun AddressChip(
    label: String,
    on: Boolean,
    dragging: Boolean,
    widthPx: Float,
    heightPx: Float,
    modifier: Modifier = Modifier,
) {
    val fill = if (on) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val border = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val elevation = if (dragging) 6.dp else 0.dp
    val density = LocalDensity.current
    Box(
        modifier
            .shadow(elevation, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(50))
            .size(
                with(density) { widthPx.toDp() },
                with(density) { heightPx.toDp() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
        )
    }
}

/** 「时间格式」入口行（v0.6.18，用户需求 2026-11-09）：时间输入框正下方，点开格式选择器 */
@Composable
private fun TimeFormatRow(current: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            I18n.t("时间格式"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            current,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = I18n.t("选择时间格式"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 时间格式单选对话框：当前项高亮 + 右侧圆形勾选，点选立即生效（含示例文本预览） */
@Composable
private fun TimeFormatPickerDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("时间格式")) },
        text = {
            Column {
                // 示例时刻固定在打开瞬间，避免每次重组都跳秒
                val sample = remember { java.util.Date() }
                com.engcamera.util.TimeFormat.PATTERNS.forEach { pattern ->
                    val selected = pattern == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(pattern) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                pattern,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            )
                            Text(
                                // 示例：用当前时刻按该格式渲染，让用户直观看到效果
                                com.engcamera.util.TimeFormat.format(pattern, sample),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (selected) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = I18n.t("已选中"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("取消")) }
        },
    )
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
