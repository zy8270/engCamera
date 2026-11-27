package com.engcamera.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Grid3x3
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.ceil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engcamera.data.ThemeMode
import com.engcamera.ui.theme.ChromeAmber
import com.engcamera.ui.theme.ChromeBadge
import com.engcamera.ui.theme.ChromeBrand
import com.engcamera.ui.theme.ChromeDanger
import com.engcamera.ui.theme.ChromeIcon
import com.engcamera.ui.theme.ChromeNeutral
import com.engcamera.ui.ProjectGroupGlyph
import com.engcamera.ui.theme.ChromeScrim
import com.engcamera.ui.theme.LocalAppDarkTheme
import com.engcamera.watermark.WatermarkOverlay
import com.engcamera.i18n.I18n

/** 模式切换胶囊高度：与两侧「工程组 / 画质入口」按钮对齐（Icon 22dp + padding 8dp×2）。
 *  用户反馈 2026-11-10：胶囊原本用 IconButton 默认 48dp，比两侧按钮高 10dp，顶到取景框 */
private val CapsuleHeight = 38.dp

/**
 * 相机主页（计划书 3.2）。
 * 重力响应规则（用户确认 2026-10-10）：布局与控件位置完全固定不变，
 * 仅每个图标/控件自身的角度随重力旋转 90°（0/±90/180）。
 */
@Composable
fun CameraScreen(
    onOpenGallery: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val vm: CameraViewModel = viewModel()
    val context = LocalContext.current
    val effective by vm.effectiveWatermark.collectAsState()
    // 原始水印状态（v226）：添加水印模式锁定纵向专用——不受量化重力角替换
    val rawWatermark by vm.watermark.collectAsState()
    val dynamic by vm.dynamic.collectAsState()
    val ui by vm.ui.collectAsState()
    // 每日更新检查结果：非空时设置入口带红点、设置页底部显示更新提示
    val updateInfo by vm.updateInfo.collectAsState()
    // 拼图设置（v0.6.19）：列数/模板供小地图与预览弹窗使用
    val collageSettings by vm.collageSettings.collectAsState()

    // 返回本页后的短暂触摸屏蔽（v0.6.24 修复"从编辑器返回后 0.1 秒内点对焦会闪现编辑字段窗口"）：
    // 导航 pop 后旧目的地要等新目的地组合完成才 dispose，这段时间旧页（含 AlertDialog
    // 的独立窗口）仍参与命中测试。屏蔽 260ms 覆盖"旧页 dispose + 过渡动画"的窗口期，
    // 让落点稳定落在相机页自己身上。
    var touchBlocked by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(260)
        touchBlocked = false
    }

    // 主界面跟随主题（0.6.0 用户拍板：日间浅色背景+深色快门按键）；系统栏图标随主题。
    // 仅取景画面上的小浮层（镜头胶囊/录像角标/对焦圈/工程组快捷钮）保持深色半透明，
    // 因为它们压在不可预测的实时画面上
    val darkTheme = LocalAppDarkTheme.current
    DisposableEffect(darkTheme) {
        val style =
            if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = style,
            navigationBarStyle = style,
        )
        onDispose { }
    }

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingQuickRecord by remember { mutableStateOf(false) }

    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** 打开系统「位置信息」总开关设置（权限与开关是两件事，用户反馈 2026-11-01） */
    fun openLocationSettings() {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // 定位权限单独的再申请通道：首次申请被拒后系统不会再弹窗，只能引导用户点按钮
    // 重新申请（用户仍选"拒绝"则只有去应用设置一条路）
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            vm.startSensors()
        } else {
            openAppSettings()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        cameraGranted = grants[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            vm.rebind?.invoke()
            vm.startSensors()
            vm.refreshLatest()
        }
    }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.startRecording(withAudio = granted, quick = pendingQuickRecord)
        pendingQuickRecord = false
    }

    // 添加水印模式的图片选择器（系统照片选择器，支持多选）
    val annotatePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30),
    ) { uris ->
        vm.onAnnotatePicked(uris)
    }

    // 拼图模式：从相册加图（v0.6.32，用户需求 2026-11-15）。支持多选，
    // 可与连拍混排（相册图不烧水印，进来什么样就什么样）
    val collagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50),
    ) { uris ->
        vm.onCollagePicked(uris)
    }

    fun startRecordingWithPermission(quick: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.startRecording(withAudio = granted, quick = quick)
        } else {
            pendingQuickRecord = quick
            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        // 旧权限模型（安卓9及以下）：存储读/写都要运行时授权——READ 供启动「最新」
        // 缩略图查询、WRITE 供 MediaStore 保存成片/录像；安卓10+ 分区存储不需要。
        // 无授权时 queryMedia 返回空、保存走「保存失败」toast，均不闪退
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (android.os.Build.VERSION.SDK_INT <= 28) {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // Toast 提示
    ui.toast?.let { toast ->
        LaunchedEffect(toast) {
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    // 录像期间保持屏幕常亮（用户反馈 2026-11-22：录像久了系统自动锁屏，录像被中断）。
    // 只在录像中挂 FLAG_KEEP_SCREEN_ON：拍照浏览等场景仍允许系统正常息屏。
    val activity = context as? ComponentActivity
    DisposableEffect(ui.recording) {
        val w = activity?.window
        if (ui.recording) {
            w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (ui.recording) {
                w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // 屏保期间隐藏状态栏/导航栏，让整屏真的只有一行字（防烧屏 + 省电）
    DisposableEffect(ui.screenSaver) {
        val w = activity?.window
        val controller = w?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (ui.screenSaver) {
            controller?.let {
                it.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        } else {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 用户操作观测（只观察不消费，Initial 阶段父级先拿到事件）：
            // 录像屏保据此重置空闲计时，任意触摸即退出屏保
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        vm.notifyUserInteraction()
                    }
                }
            },
    ) {
        if (!cameraGranted) {
            PermissionGate(onOpenSettings = ::openAppSettings)
        } else {
            var showSettings by remember { mutableStateOf(false) }
            var showProjectGroups by remember { mutableStateOf(false) }
            // 拼图（v0.6.19）：模板设置弹窗、退出/生成二次确认、待删项确认
            var showCollageSettings by remember { mutableStateOf(false) }
            var confirmExitCollage by remember { mutableStateOf(false) }
            var confirmSaveCollage by remember { mutableStateOf(false) }
            var confirmSyncCollage by remember { mutableStateOf(false) }
            // 退出拼图（X / 系统返回）：有照片才二次确认（用户拍板 2026-11-09）
            fun requestExitCollage() {
                if (ui.collageItems.isEmpty()) vm.exitCollage() else confirmExitCollage = true
            }
            // 确认生成拼图（✓）：0 张=直接退出；有照片先二次确认
            fun requestConfirmCollage() {
                if (ui.collageItems.isEmpty()) vm.exitCollage() else confirmSaveCollage = true
            }
            // 抽屉展开时返回键收起抽屉，而不是退出应用
            androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
            // 延时拍照设置弹窗（v0.6.47）：独立 AlertDialog 窗口，返回键由它自己关掉；
            // 这里再挂一条更高优先级的 BackHandler 兜底，确保不会漏到 NavHost 退出应用
            var showDelayDialog by remember { mutableStateOf(false) }
            androidx.activity.compose.BackHandler(enabled = showDelayDialog) { showDelayDialog = false }

            // ---------- 延时拍照倒计时（v0.6.47，用户需求 2026-11-27） ----------
            // 只在「按快门后要等」的动作上生效（拍照 / 开录 / 拼图连拍）；
            // 停止录像与保存不受影响。倒计时期间再按一次快门=取消（用户拍板）。
            val delaySeconds by vm.delaySeconds.collectAsState()
            // 取景辅助（v0.6.48，用户需求 2026-11-27）：构图线 / 水平仪，默认关、带记忆
            val gridLines by vm.gridLines.collectAsState()
            val levelGauge by vm.levelGauge.collectAsState()
            // 水平仪角度：**不要**用 by 解构——那会在组合里读值，每 0.2° 都重组相机页。
            // 只把 State 对象传下去，由 Canvas 的绘制 lambda 读取：读取发生在绘制阶段，
            // 传感器更新只触发重绘
            val levelRollState = vm.levelRoll.collectAsState()
            val delayScope = rememberCoroutineScope()
            var delayJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            var delayRemaining by remember { mutableIntStateOf(0) }
            // 进度用独立的 float 状态：只被快门的 Canvas 在绘制阶段读取，
            // 每帧变化只触发重绘、不触发整页重组（相机页重组代价大）
            val delayProgress = remember { mutableFloatStateOf(0f) }
            var delayPending by remember { mutableStateOf<(() -> Unit)?>(null) }
            val cancelDelay: () -> Unit = {
                delayJob?.cancel()
                delayJob = null
                delayRemaining = 0
                delayProgress.floatValue = 0f
                delayPending = null
            }
            val triggerWithDelay: (() -> Unit) -> Unit = { action ->
                when {
                    // 倒计时进行中：再按一次 = 取消本次拍摄
                    delayRemaining > 0 -> cancelDelay()
                    delaySeconds > 0 -> {
                        val total = delaySeconds
                        delayPending = action
                        delayRemaining = total
                        delayProgress.floatValue = 0f
                        delayJob = delayScope.launch {
                            val totalMs = total * 1000L
                            val start = android.os.SystemClock.uptimeMillis()
                            while (true) {
                                val elapsed = android.os.SystemClock.uptimeMillis() - start
                                val p = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
                                delayProgress.floatValue = p
                                val remain = ceil((totalMs - elapsed) / 1000.0)
                                    .toInt().coerceAtLeast(0)
                                if (remain != delayRemaining) delayRemaining = remain
                                if (p >= 1f) break
                                // 逐帧刷新进度环；被取消（再按快门/切模式/离开页面）即中断
                                withFrameNanos { }
                            }
                            delayRemaining = 0
                            delayProgress.floatValue = 0f
                            val a = delayPending
                            delayPending = null
                            delayJob = null
                            a?.invoke()
                        }
                    }
                    else -> action()
                }
            }
            // 离开相机页：scope 随组合销毁自动取消，倒计时不会在别处触发。
            // 模式/入口变化（进确认页、添加水印、拼图、屏保）也作废倒计时，
            // 否则会在一张不相干的界面上突然触发拍摄
            LaunchedEffect(ui.confirming, ui.annotating, ui.collagePreviewOpen, ui.screenSaver) {
                if (ui.confirming || ui.annotating || ui.collagePreviewOpen || ui.screenSaver) {
                    cancelDelay()
                }
            }
            // 延时过程中切模式（录像↔拍照、进出拼图）：直接停掉延时任务（用户反馈 2026-11-27）。
            // 原实现会让计时结束仍按旧动作开录，而切到拍照后的快门不再管录像 → 录上了停不掉
            LaunchedEffect(ui.videoMode, ui.collageMode) {
                cancelDelay()
            }
            // 连拍预览打开时，返回键 = 关预览回拍照界面（不是退出拼图模式，用户反馈 2026-11-09）。
            // 必须排在退出拼图的 BackHandler 之前——Compose 的 BackHandler 后注册者优先，
            // 所以这条要写在下面那条之后才会先执行；这里用互斥 enabled 条件区分：
            // 预览开着时退出拼图那条不生效
            androidx.activity.compose.BackHandler(enabled = ui.collageMode && !showSettings) {
                if (ui.collagePreviewOpen) vm.setCollagePreviewOpen(false) else requestExitCollage()
            }
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // TextureView 模式：避免 SurfaceView 在快速导航销毁时撕裂窗口
                    // 导致整个 Activity 停止绘制（真机复现：编辑器快速连点保存后整屏黑）
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            val navLifecycle = LocalLifecycleOwner.current

            DisposableEffect(navLifecycle) {
                val engine = CameraEngine(context, navLifecycle)
                vm.attachEngine(engine)
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                // 帧看门狗：快速进出编辑器等场景下引擎 release→重绑会撞上异步相机关闭
                // （绑成功但 HAL 忙、无帧黑屏，用户 100% 复现 2026-10-16）——绑完每 0.5s
                // 查一次流状态，1.5s（3 查）仍无流才强制重绑（force 跳过同键去重），
                // 最多再试 2 次；间隔足够宽不搅 HAL（vivo 热身窗口内密集重绑会卡死）
                fun rebindWithWatchdog(attempt: Int) {
                    // 相机挂起中（确认标注/添加水印）：一切重绑请求（init 回调/
                    // 布局监听/模式切换）都不许把相机打开；恢复走 resumeCameraForShooting
                    if (vm.cameraPaused) return
                    engine.bind(previewView, vm.barcodeAnalyzer(), force = attempt > 0)
                    vm.onEngineBound()
                    var checks = 0
                    fun check() {
                        mainHandler.postDelayed(
                            {
                                if (!navLifecycle.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@postDelayed
                                if (previewView.previewStreamState.value ==
                                    androidx.camera.view.PreviewView.StreamState.STREAMING
                                ) {
                                    return@postDelayed
                                }
                                checks++
                                if (checks >= 3) {
                                    if (attempt < 2) rebindWithWatchdog(attempt + 1)
                                } else {
                                    check()
                                }
                            },
                            500,
                        )
                    }
                    check()
                }
                vm.rebind = { rebindWithWatchdog(0) }
                // 确认标注返回拍摄用的强制重绑通道（attempt=1 首绑即 force，
                // 绕过 bindKey 去重——unbind 后相机字段未清，普通 rebind 会被跳过）
                vm.rebindForce = { rebindWithWatchdog(1) }
                // 预览控件完成测量后重绑一次，让 ViewPort 拿到真实宽高（录像比例与预览一致的关键）
                val layoutListener = android.view.View.OnLayoutChangeListener { v, _, _, _, _, oldL, oldT, oldR, oldB ->
                    if (v.width > 0 && v.height > 0 &&
                        (oldR - oldL != v.width || oldB - oldT != v.height)
                    ) {
                        vm.rebind?.invoke()
                    }
                }
                previewView.addOnLayoutChangeListener(layoutListener)
                engine.init(
                    onReady = { vm.rebind?.invoke() },
                    onError = { t ->
                        t.printStackTrace()
                        vm.engine = null
                        vm.rebind = null
                        vm.showToast(I18n.t("相机初始化失败：{msg}").replace("{msg}", t.message ?: I18n.t("未知错误")))
                    },
                )
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            // 相机由 bindToLifecycle 随生命周期自动恢复，这里只启动传感器与刷新缩略图
                            if (cameraGranted) {
                                vm.startSensors()
                            }
                            vm.refreshLatest()
                            // 确认标注/添加水印中从后台回来：bindToLifecycle 会自动
                            // 重绑把相机打开（常亮闪光灯随要点亮），这两种界面仍不
                            // 需要相机——再关一次（cameraPaused 本就为 true）
                            if (vm.ui.value.confirming || vm.ui.value.annotating) {
                                vm.pauseCameraForAnnotation()
                            }
                            // 拼图预览覆盖层同样不用相机（v0.6.19）：从后台回来再关一次
                            if (vm.ui.value.collagePreviewOpen) {
                                vm.pauseCameraForAnnotation()
                            }
                        }

                        Lifecycle.Event.ON_PAUSE -> vm.stopEverything()
                        else -> Unit
                    }
                }
                navLifecycle.lifecycle.addObserver(observer)
                onDispose {
                    mainHandler.removeCallbacksAndMessages(null)
                    navLifecycle.lifecycle.removeObserver(observer)
                    previewView.removeOnLayoutChangeListener(layoutListener)
                    vm.stopEverything()
                    vm.rebind = null
                    vm.rebindForce = null
                    // 进编辑器前截取取景器一帧作静态预览背景（低配所见即所得，用户拍板 2026-10-16）
                    EditorBackdrop.capture(previewView)
                    // 释放闸门：先注册可用性回调再关相机——编辑器据此在真正释放完后
                    // 才显示眼睛（用户拍板 2026-10-16）
                    CameraGate.markReleasing(context)
                    vm.detachEngine()
                }
            }

            val rot = ui.uiRotation

            // 拍照确认标注 / 添加水印（相册选图）两种界面都临时关闭相机（用户需求
            // 2026-10-17）：界面上用不到相机，挂着既耗电又让「常亮」闪光灯整段亮着。
            // 进态先关（unbindAll 即关相机设备，torch 随之熄灭，本页此刻是唯一绑定
            // 方），退态（保存/丢弃/切模式）再强制重绑——onEngineBound 的 setFlashMode
            // 按用户设定恢复常亮。wasIdle 只认「真⇄假」跳变：首次组合不凭空触发；
            // 两态互换（理论不可能）不重绑。cameraPaused 挂起标志让 rebindWithWatchdog
            // 拒绝一切重绑，堵住「从编辑器返回时 annotating 仍为 true，init 回调把
            // 相机重开」的竞态
            // 拼图连拍预览覆盖层同样临时关相机（v0.6.19，用户拍板 2026-11-09）：
            // 该界面完全不用相机，且本页是唯一绑定方（与水印编辑器内"眼睛"实时预览
            // 的场景不同，那里会有东西抢相机），unbind 后关闭是安全的
            val previewIdle = ui.confirming || ui.annotating || ui.collagePreviewOpen
            var wasPreviewIdle by remember { mutableStateOf(false) }
            LaunchedEffect(previewIdle) {
                when {
                    previewIdle && !wasPreviewIdle -> {
                        wasPreviewIdle = true
                        vm.pauseCameraForAnnotation()
                    }

                    !previewIdle && wasPreviewIdle -> {
                        wasPreviewIdle = false
                        vm.resumeCameraForShooting()
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // 顶部控制条：闪光灯左、设置右；图标角度随重力，位置不变
                Box(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.cycleFlash() }) {
                            Icon(
                                imageVector = when (ui.flashIndex) {
                                    1 -> Icons.Default.FlashOn
                                    2 -> Icons.Default.FlashAuto
                                    else -> Icons.Default.FlashOff
                                },
                                contentDescription = I18n.t("闪光灯"),
                                tint = if (ui.flashIndex == 0) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.rotate(rot.toFloat()),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // 延时拍照快捷入口（v0.6.48，用户需求 2026-11-27）：已设延时（>0 秒）时
                        // 出现在设置齿轮左边，点按直接调延时设置弹窗——方便快速改秒数。
                        // 未启用时不占位（设置里的卡片负责开启）
                        if (delaySeconds > 0) {
                            IconButton(onClick = { showDelayDialog = true }) {
                                Icon(
                                    Icons.Default.AvTimer,
                                    contentDescription = I18n.t("延时拍照设置"),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.rotate(rot.toFloat()),
                                )
                            }
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = I18n.t("设置"),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.rotate(rot.toFloat()),
                            )
                        }
                    }
                    // 居中标题（用户需求 2026-11-10）：按当前状态/模式显示，让用户随时知道
                    // 自己在哪个界面。优先级从「更特殊的态」到「常规态」：
                    // 确认&标注 > 添加水印 > 连拍拼图 > 录像中/录像 > 拍照
                    val modeTitle = when {
                        ui.confirming || (ui.annotating && ui.annotateQueue.isNotEmpty()) -> "确认&标注"
                        ui.annotating -> "添加水印"
                        ui.collageMode -> "连拍拼图"
                        ui.recording -> "录像中"
                        ui.videoMode -> "录像"
                        else -> "拍照"
                    }
                    // 标题 / 文字工具提示（v0.6.45，用户反馈 2026-11-23）：
                    // 上一版把提示放在「标题底 + 2dp」，标题底到画布白框只有约 25dp，
                    // 提示一撑大下半截就被白框压住。这里改为**顶格替换标题**：
                    // 文字工具激活时，标题位显示操作提示（"确认&标注" 此刻没有信息量，
                    // 用户更需要知道点哪加字）。字号恢复原样（labelMedium），
                    // 位置=标题原本那一行，整块完全落在控制条内，永远不会被白框压住。
                    val showTextHint = (ui.confirming ||
                        (ui.annotating && ui.annotateQueue.isNotEmpty())) &&
                        ui.annotationTool == 3
                    if (showTextHint) {
                        Text(
                            I18n.t("点击图片任意位置添加文字"),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x88000000))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    } else {
                        Text(
                            I18n.t(modeTitle),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            // 标题不随重力旋转（用户反馈 2026-11-10）：它是界面标签不是
                            // 取景画面上的浮层，横持时应保持正立可读
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }

                // 预览区：3:4 填满宽度；添加水印模式下换成黑屏选图/图片预览
                // 选图入口（空态 + 号与下载键共用，v232）
                val launchAnnotatePicker = {
                    annotatePicker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                }
                if (ui.annotating) {
                    AnnotateArea(
                        modifier = Modifier.fillMaxWidth(),
                        vm = vm,
                        watermark = rawWatermark,
                        dynamic = dynamic,
                        ui = ui,
                        onOpenEditor = {
                            // 相机在添加水印模式已关（v223），截帧只会拿到空/陈旧画面——
                            // 清掉旧帧让编辑器走几何图案背景，液态玻璃采样图案位图（v226）
                            EditorBackdrop.clear()
                            onOpenEditor()
                        },
                        onPick = launchAnnotatePicker,
                    )
                } else if (ui.confirming) {
                    ConfirmArea(
                        modifier = Modifier.fillMaxWidth(),
                        vm = vm,
                        ui = ui,
                        effective = effective,
                        dynamic = dynamic,
                        // 标注期间进水印编辑器：确认态相机已关，截帧只会拿到空/陈旧
                        // 画面——清掉旧帧让编辑器走几何图案背景（与添加水印同款，v226）
                        onOpenEditor = {
                            EditorBackdrop.clear()
                            onOpenEditor()
                        },
                    )
                } else {
                    PreviewArea(
                        modifier = Modifier.fillMaxWidth(),
                        previewView = previewView,
                        vm = vm,
                        effective = effective,
                        dynamic = dynamic,
                        ui = ui,
                        rot = rot,
                        onOpenEditor = {
                            // 同上：跳转动作时截帧（dispose 里截太晚）
                            EditorBackdrop.capture(previewView)
                            onOpenEditor()
                        },
                        collageColumns = collageSettings.columns,
                        collageTemplate = collageSettings.template,
                        delayCountdown = delayRemaining,
                        gridLines = gridLines,
                        levelGauge = levelGauge,
                        levelRoll = { levelRollState.value },
                    )
                }

                // 定位异常提示条：权限与"定位总开关"是两件事，用户只授权了权限仍可能
                // 拿不到点（用户反馈 2026-11-01）。这里给一句话+一键跳对应系统设置页
                val locDisabled by vm.locationServiceDisabled.collectAsState()
                val locDenied by vm.locationPermissionDenied.collectAsState()
                if (!ui.confirming && !ui.annotating && (locDisabled || locDenied)) {
                    LocationHintBar(
                        permissionDenied = locDenied,
                        onOpenLocationSettings = ::openLocationSettings,
                        onRequestPermission = {
                            locationPermLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        onOpenAppSettings = ::openAppSettings,
                        onDismiss = vm::dismissLocationHint,
                    )
                }

                // 底部控制条紧贴预览下方，不推到屏幕底部
                BottomBar(
                    rot, ui, vm, ::startRecordingWithPermission, onOpenGallery,
                    { showProjectGroups = true },
                    onPickAnnotateImage = launchAnnotatePicker,
                    onOpenCollageSettings = { showCollageSettings = true },
                    onRequestExitCollage = ::requestExitCollage,
                    onConfirmCollage = ::requestConfirmCollage,
                    onEnterCollage = { vm.enterCollage() },
                    showDelayIcon = delaySeconds > 0,
                    onDelayableShutter = triggerWithDelay,
                    delayProgress = { delayProgress.floatValue },
                    delayRunning = delayRemaining > 0,
                    onAddFromGallery = {
                        // 先 Toast 说明用途，再跳系统照片选择器（用户需求 2026-11-15）。
                        // 直接弹系统 Toast 而不是走 ui.toast：走状态的话组合会在
                        // 启动选择器 Activity 时暂停，Toast 要等回到本页才出现
                        android.widget.Toast.makeText(
                            context,
                            I18n.t("可从相册选取图片添加到拼图里"),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        collagePicker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                )
            }

            // 触摸屏蔽层（v0.6.24）：返回本页后的极短时间内吞掉所有触摸，
            // 防止落到上一页残留的 UI（含 AlertDialog 独立窗口）上。
            // 用 pointerInput 吞掉**全部**事件（点击/滑动/长按），clickable 只拦点击不够
            if (touchBlocked) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    e.changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
            }

            // 音量键→快门（设置开启时 Activity 转发；仅相机页组合期间注册）
            // v0.6.47：与屏幕快门一致走延时包装（倒计时中再按=取消，用户拍板）
            val appContainer = (context.applicationContext as com.engcamera.EngCameraApp).container
            val delayableRef = rememberUpdatedState(triggerWithDelay)
            DisposableEffect(navLifecycle, vm) {
                appContainer.volumeKeyHandler = {
                    val st = vm.ui.value
                    when {
                        // 拼图预览覆盖层盖住快门时不响应（用户拍板 2026-11-09）
                        st.collagePreviewOpen -> true
                        st.collageMode -> { delayableRef.value { vm.takeCollagePhoto() }; true }
                        st.annotating -> { vm.confirmAnnotate(); true }
                        st.videoMode -> {
                            if (st.recording) {
                                vm.stopRecording()
                            } else {
                                delayableRef.value { startRecordingWithPermission(false) }
                            }
                            true
                        }
                        else -> { delayableRef.value { vm.takePhoto() }; true }
                    }
                }
                onDispose { appContainer.volumeKeyHandler = null }
            }

            // 工程组快捷切换选择器（与编辑器共用）
            com.engcamera.projectgroup.ProjectGroupSelector(
                visible = showProjectGroups,
                handler = vm,
                onDismiss = { showProjectGroups = false },
            )

            // ---------- 拼图（v0.6.19，用户需求 2026-11-09） ----------
            // 模板设置弹窗
            if (showCollageSettings) {
                CollageSettingsDialog(
                    settings = collageSettings,
                    onDismiss = { showCollageSettings = false },
                    onChange = { vm.setCollageSettings(it) },
                )
            }
            // 退出拼图确认（X / 系统返回；有照片才弹）
            if (confirmExitCollage) {
                AlertDialog(
                    onDismissRequest = { confirmExitCollage = false },
                    title = { Text(I18n.t("退出拼图模式？")) },
                    text = {
                        Text(
                            I18n.t("已拍摄的 {n} 张照片将被丢弃，确定退出？")
                                .replace("{n}", "${ui.collageItems.size}"),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmExitCollage = false
                            vm.exitCollage()
                        }) { Text(I18n.t("退出")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmExitCollage = false }) { Text(I18n.t("取消")) }
                    },
                )
            }
            // 生成拼图确认（✓；有照片才弹）
            if (confirmSaveCollage) {
                AlertDialog(
                    onDismissRequest = { confirmSaveCollage = false },
                    title = { Text(I18n.t("生成拼图？")) },
                    text = {
                        Text(
                            I18n.t("将把 {n} 张照片按 {c} 列拼接成一张，保存到相册。")
                                .replace("{n}", "${ui.collageItems.size}")
                                .replace("{c}", "${collageSettings.columns}"),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmSaveCollage = false
                            vm.saveCollage()
                        }) { Text(I18n.t("生成")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmSaveCollage = false }) { Text(I18n.t("取消")) }
                    },
                )
            }
            // 保存进度弹窗（用户需求 2026-11-10）：张数多时合成很久，给圆环进度 +
            // 百分比 + 取消按钮，避免用户以为卡死
            if (ui.collageSaving) {
                CollageProgressDialog(
                    progress = ui.collageProgress,
                    fraction = ui.collageProgressFraction,
                    onCancel = { vm.cancelCollageSave() },
                )
            }
            // 连拍预览覆盖层（打开期间相机已由 previewIdle 暂停）
            if (ui.collagePreviewOpen) {
                CollagePreviewOverlay(
                    items = ui.collageItems,
                    columns = collageSettings.columns,
                    saving = ui.collageSaving,
                    progress = ui.collageProgress,
                    onClose = { vm.setCollagePreviewOpen(false) },
                    onDelete = { id -> vm.deleteCollageItem(id) },
                    onMove = { from, to -> vm.moveCollageItem(from, to) },
                    onOpenSettings = { showCollageSettings = true },
                    onSave = { confirmSaveCollage = true },
                    onSyncWatermark = { confirmSyncCollage = true },
                )
            }
            // 同步当前水印确认（会覆盖已拍照片的冻结状态）
            if (confirmSyncCollage) {
                AlertDialog(
                    onDismissRequest = { confirmSyncCollage = false },
                    title = { Text(I18n.t("同步当前水印？")) },
                    text = {
                        Text(
                            // 只数连拍的照片：相册导入的图不烧水印，同步对它们无效
                            I18n.t("将把当前水印设置覆盖到已拍的 {n} 张照片上，覆盖后无法撤销。")
                                .replace("{n}", "${ui.collageItems.count { !it.fromGallery }}"),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmSyncCollage = false
                            vm.syncCollageWatermark()
                        }) { Text(I18n.t("同步")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmSyncCollage = false }) { Text(I18n.t("取消")) }
                    },
                )
            }

            // 右上角设置：右侧滑出抽屉（约 3/4 屏宽）
            SettingsDrawer(
                visible = showSettings,
                vm = vm,
                onDismiss = { showSettings = false },
                // 抽屉里点「延时时间」：先收起抽屉再弹窗，避免两层浮层叠着
                onOpenDelayDialog = {
                    showSettings = false
                    showDelayDialog = true
                },
            )

            // 延时拍照设置弹窗（抽屉里点「延时时间」或右上角秒表进入）
            if (showDelayDialog) {
                DelayShutterDialog(
                    current = delaySeconds,
                    onDismiss = { showDelayDialog = false },
                    onConfirm = {
                        vm.setDelaySeconds(it)
                        showDelayDialog = false
                    },
                )
            }
        }

        // 录像屏保（v0.6.39，用户需求 2026-11-22）：整屏黑 + 一行「红录像机 录像中：hh:mm:ss」，
        // 任意触摸退出（唤醒由根节点 pointerInput 统一处理）。盖在最上层，
        // 屏保期间自己吞掉全部触摸，避免误触快门等按钮
        if (ui.screenSaver && ui.recording) {
            RecordingScreenSaver(ui.recordingSeconds)
        }
    }
}

/**
 * 录像屏保画面：全屏黑底，中间一行红色录像机图标 + 「录像中：hh:mm:ss」。
 * 防烧屏：这行字在屏幕垂直中线上下 1/8 屏高（合计覆盖中间 1/4 区域）按顺序
 * 四档循环移动，每 20 秒换一档，切换时 0.9s 平滑过渡。
 */
@Composable
private fun RecordingScreenSaver(seconds: Int) {
    // 档位：中线 → 上 1/8 → 中线 → 下 1/8（"上下按顺序移动"）
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            step = (step + 1) % 4
        }
    }
    val hh = seconds / 3600
    val mm = (seconds % 3600) / 60
    val ss = seconds % 60
    val time = String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 屏保期间吞掉全部触摸（Initial pass），唤醒交给根节点
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        e.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        val bandPx = constraints.maxHeight / 4
        val offsets = remember(bandPx) {
            listOf(-bandPx / 2, 0, bandPx / 2, 0)
        }
        val target = IntOffset(0, offsets.getOrElse(step) { 0 })
        val animated by androidx.compose.animation.core.animateIntOffsetAsState(
            targetValue = target,
            animationSpec = tween(900),
            label = "saverOffset",
        )
        Row(
            Modifier
                .align(Alignment.Center)
                .offset { animated },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Videocam,
                contentDescription = I18n.t("录像中"),
                tint = ChromeDanger,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // 时间部分用等宽数字，避免每秒跳动时整行宽度变化
                I18n.t("录像中：{t}").replace("{t}", time),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 设置抽屉：从右侧滑出（约 4/5 屏宽），收纳与拍摄行为相关的设置项 */
@Composable
private fun SettingsDrawer(
    visible: Boolean,
    vm: CameraViewModel,
    onDismiss: () -> Unit,
    onOpenDelayDialog: () -> Unit = {},
) {
    val context = LocalContext.current
    val saveOriginal by vm.saveOriginal.collectAsState()
    val createTitleFromName by vm.createTitleFromName.collectAsState()
    val shutterSound by vm.shutterSound.collectAsState()
    val volumeShutter by vm.volumeShutter.collectAsState()
    val delaySeconds by vm.delaySeconds.collectAsState()
    // 取景器辅助（v0.6.48，用户需求 2026-11-27）：顶部卡片区显示激活态
    val gridLines by vm.gridLines.collectAsState()
    val levelGauge by vm.levelGauge.collectAsState()
    val watermark by vm.watermark.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val languageMode by vm.languageMode.collectAsState()
    val updateInfo by vm.updateInfo.collectAsState()
    val showLangPicker = remember { mutableStateOf(false) }
    val saveTree by vm.saveTree.collectAsState()
    val showSavePathDialog = remember { mutableStateOf(false) }
    // 分组内用到的状态（v0.6.18：折叠后只在展开时组合，但仍需在此取，保持单一数据源）
    val photoConfirm by vm.photoConfirm.collectAsState()
    val editorBackdrop by vm.editorBackdrop.collectAsState()
    val defaultZoom by vm.defaultZoom.collectAsState()
    val restoreLastZoom by vm.restoreLastZoom.collectAsState()
    val qualityUi by vm.qualityUi.collectAsState()
    // 画质单选对话框：0=照片 1=视频（null=关闭）
    var qualityDialog by remember { mutableStateOf<Int?>(null) }
    val saveTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val path = android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':', I18n.t("所选目录"))
            vm.setSaveTree(uri, path)
            android.widget.Toast.makeText(context, I18n.t("保存路径已设为 {p}").replace("{p}", path), android.widget.Toast.LENGTH_SHORT).show()
            showSavePathDialog.value = false
        }
    }

    if (showLangPicker.value) {
        AlertDialog(
            onDismissRequest = { showLangPicker.value = false },
            title = { Text("语言 / Language") },
            text = {
                Column {
                    listOf(
                        com.engcamera.i18n.AppLanguageMode.SYSTEM to I18n.t("跟随系统语言"),
                        com.engcamera.i18n.AppLanguageMode.ZH to "中文",
                        com.engcamera.i18n.AppLanguageMode.EN to "English",
                    ).forEach { (mode, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setLanguageMode(mode)
                                    showLangPicker.value = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = languageMode == mode,
                                onClick = {
                                    vm.setLanguageMode(mode)
                                    showLangPicker.value = false
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLangPicker.value = false }) { Text(I18n.t("确定")) }
            },
        )
    }

    if (showSavePathDialog.value) {
        AlertDialog(
            onDismissRequest = { showSavePathDialog.value = false },
            title = { Text(I18n.t("文件保存路径")) },
            text = {
                Text(
                    I18n.t("当前：") + (saveTree?.second ?: I18n.t("DCIM/Camera（默认）")) +
                        I18n.t("\n\n选择文件夹后，照片和录像将保存到所选目录（系统将请求您授权访问该文件夹）。"),
                )
            },
            confirmButton = {
                TextButton(onClick = { saveTreeLauncher.launch(null) }) { Text(I18n.t("选择文件夹")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setSaveTree(null, null)
                    showSavePathDialog.value = false
                    android.widget.Toast.makeText(context, I18n.t("已恢复默认 DCIM/Camera"), android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(I18n.t("恢复默认")) }
            },
        )
    }

    // 画质单选列表（用户需求 2026-11-09）：当前项高亮 + 右侧圆形勾选，点选立即生效并关闭
    val qualityDialogIndex = qualityDialog
    if (qualityDialogIndex != null) {
        val isVideo = qualityDialogIndex == 1
        val options = if (isVideo) {
            qualityUi.videoQualities.map { it.label to it.spec }
        } else {
            qualityUi.photoQualities.map { it.label to it.spec }
        }
        val selectedIdx = if (isVideo) qualityUi.videoQualityIndex else qualityUi.photoQualityIndex
        AlertDialog(
            onDismissRequest = { qualityDialog = null },
            title = { Text(if (isVideo) I18n.t("视频画质") else I18n.t("照片画质")) },
            text = {
                Column {
                    if (options.isEmpty()) {
                        Text(I18n.t("相机未就绪"))
                    }
                    options.forEachIndexed { idx, (label, spec) ->
                        val selected = idx == selectedIdx
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isVideo) vm.selectVideoQuality(idx) else vm.selectPhotoQuality(idx)
                                    qualityDialog = null
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                )
                                Text(
                                    spec,
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
                TextButton(onClick = { qualityDialog = null }) { Text(I18n.t("取消")) }
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        // 遮罩独立渐变（不随抽屉位移），由明到暗淡入
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(240)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                    .background(ChromeScrim),
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it },
            exit = slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.8f)
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                Text(
                    I18n.t("设置"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))

                // ---------- 常用功能卡片（v0.6.48，用户需求 2026-11-27） ----------
                // 九宫格：目前只做第一排（构图线 / 水平仪 / 延时拍照）。
                // 卡片底色表达激活态：启用=深一档容器色，未启用=贴近背景的浅色（暗色模式反之）。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsFeatureCard(
                        icon = Icons.Default.Grid3x3,
                        label = I18n.t("构图线"),
                        active = gridLines,
                        onClick = { vm.setGridLines(!gridLines) },
                        modifier = Modifier.weight(1f),
                    )
                    SettingsFeatureCard(
                        icon = Icons.Default.Balance,
                        label = I18n.t("水平仪"),
                        active = levelGauge,
                        onClick = { vm.setLevelGauge(!levelGauge) },
                        modifier = Modifier.weight(1f),
                    )
                    // 延时：未启用显示「延时拍照」，启用后显示「延时N秒」；点按弹延时设置
                    SettingsFeatureCard(
                        icon = Icons.Default.AvTimer,
                        label = if (delaySeconds > 0) {
                            I18n.t("延时{n}秒").replace("{n}", "$delaySeconds")
                        } else {
                            I18n.t("延时拍照")
                        },
                        active = delaySeconds > 0,
                        onClick = onOpenDelayDialog,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))

                // 手风琴分组（v0.6.18 用户需求 2026-11-09）：同级仅一个展开、走文档流
                // （后续分组随之下移），默认全部收起
                var expandedGroup by remember { mutableStateOf<SettingsGroup?>(null) }
                fun toggle(g: SettingsGroup) { expandedGroup = if (expandedGroup == g) null else g }

                // ---------- 平铺的单项（v0.6.48 用户需求 2026-11-27：散碎单项放上面，
                // 所有折叠分组统一排到下面） ----------
                SettingsSwitchRow(
                    title = I18n.t("拍照后确认"),
                    desc = I18n.t("拍完先预览，可标注后再决定是否保存"),
                    checked = photoConfirm,
                    onChange = { vm.setPhotoConfirm(it) },
                )
                SettingsSwitchRow(
                    title = I18n.t("音量键拍照"),
                    desc = I18n.t("按音量键即可拍照"),
                    checked = volumeShutter,
                    onChange = { vm.setVolumeShutter(it) },
                )

                // ---------- 界面&语言EN（v0.6.48，用户需求 2026-11-27：外观 / 界面语言 /
                // 快门声音 三项收进本组，位置在水印设置上方——所有折叠项都排在单项下面） ----------
                SettingsGroupHeader(I18n.t("界面&语言EN"), expandedGroup == SettingsGroup.UI_LANGUAGE) {
                    toggle(SettingsGroup.UI_LANGUAGE)
                }
                SettingsGroupBody(expandedGroup == SettingsGroup.UI_LANGUAGE) {
                    // 外观：跟随系统 / 日间 / 夜间（0.6.0 新增，DataStore 持久化）
                    Text(
                        I18n.t("外观"),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        I18n.t("日间/夜间模式，或跟随系统"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    ThemeModeSelector(
                        selected = themeMode,
                        onSelect = { vm.setThemeMode(it) },
                    )
                    Spacer(Modifier.height(12.dp))

                    // 界面语言（v191：跟随系统/中文/English，用户拍板全量中英）
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showLangPicker.value = true }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "语言 / Language",
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                when (languageMode) {
                                    com.engcamera.i18n.AppLanguageMode.SYSTEM -> I18n.t("跟随系统语言")
                                    com.engcamera.i18n.AppLanguageMode.ZH -> "中文"
                                    com.engcamera.i18n.AppLanguageMode.EN -> "English"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SettingsSwitchRow(
                        title = I18n.t("快门声音"),
                        desc = I18n.t("拍照时播放快门音（部分地区法规强制）"),
                        checked = shutterSound,
                        onChange = { vm.setShutterSound(it) },
                    )
                }

                // ---------- 水印设置（用户需求 2026-11-09：水印跟随重力 + 取景器截帧背景收进本组） ----------
                SettingsGroupHeader(I18n.t("水印设置"), expandedGroup == SettingsGroup.WATERMARK) {
                    toggle(SettingsGroup.WATERMARK)
                }
                SettingsGroupBody(expandedGroup == SettingsGroup.WATERMARK) {
                    SettingsSwitchRow(
                        title = I18n.t("水印跟随重力"),
                        desc = I18n.t("横拍、倒置时水印文字随重力保持正向；关闭则使用固定角度"),
                        checked = watermark.followGravity,
                        onChange = { vm.setFollowGravity(it) },
                    )
                    if (!watermark.followGravity) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                I18n.t("固定旋转角"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(12.dp))
                            Slider(
                                value = watermark.rotationDeg,
                                onValueChange = { vm.setRotationDeg(it) },
                                valueRange = -180f..180f,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${watermark.rotationDeg.roundToInt()}°",
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    // 取景器截帧背景（用户拍板 2026-10-16，默认开）
                    SettingsSwitchRow(
                        title = I18n.t("取景器截帧背景"),
                        desc = I18n.t("编辑器预览背景用取景器画面，关闭则用几何图案"),
                        checked = editorBackdrop,
                        onChange = { vm.setEditorBackdrop(it) },
                    )
                }

                // ---------- 缩放倍率（用户需求 2026-11-09） ----------
                SettingsGroupHeader(I18n.t("缩放倍率"), expandedGroup == SettingsGroup.ZOOM) {
                    toggle(SettingsGroup.ZOOM)
                }
                SettingsGroupBody(expandedGroup == SettingsGroup.ZOOM) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            I18n.t("默认倍率"),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            String.format(Locale.US, "%.1f×", defaultZoom),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = defaultZoom,
                        // 连续拖动、按 0.1× 取整（不用 steps：74 个刻度会把轨道画成一排点）
                        onValueChange = { vm.setDefaultZoom((it * 10f).roundToInt() / 10f) },
                        valueRange = 0.5f..8f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        I18n.t("相机启动时自动缩放到该倍率"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SettingsSwitchRow(
                        title = I18n.t("恢复上次倍率"),
                        desc = I18n.t("开启后，相机启动时自动使用上次退出前的缩放倍率；关闭时使用默认倍率。"),
                        checked = restoreLastZoom,
                        onChange = { vm.setRestoreLastZoom(it) },
                    )
                }

                // ---------- 画质设置（用户需求 2026-11-09：从首页画质选择器迁移） ----------
                SettingsGroupHeader(I18n.t("画质设置"), expandedGroup == SettingsGroup.QUALITY) {
                    toggle(SettingsGroup.QUALITY)
                }
                SettingsGroupBody(expandedGroup == SettingsGroup.QUALITY) {
                    // 两个子项：点进去以单选列表展示可选画质（当前项高亮+圆形勾选）
                    SettingsValueRow(
                        title = I18n.t("照片画质"),
                        value = qualityUi.photoQualities.getOrNull(qualityUi.photoQualityIndex)?.let {
                            it.label + " · " + it.spec
                        } ?: I18n.t("相机未就绪"),
                        onClick = { qualityDialog = 0 },
                    )
                    SettingsValueRow(
                        title = I18n.t("视频画质"),
                        value = qualityUi.videoQualities.getOrNull(qualityUi.videoQualityIndex)?.let {
                            it.label + " · " + it.spec
                        } ?: I18n.t("相机未就绪"),
                        onClick = { qualityDialog = 1 },
                    )
                }

                // ---------- 存储设置（用户需求 2026-11-09，置底） ----------
                SettingsGroupHeader(I18n.t("存储设置"), expandedGroup == SettingsGroup.STORAGE) {
                    toggle(SettingsGroup.STORAGE)
                }
                SettingsGroupBody(expandedGroup == SettingsGroup.STORAGE) {
                    SettingsSwitchRow(
                        title = I18n.t("同时保存原片"),
                        desc = I18n.t("拍照/录像时额外保留一份无水印原片"),
                        checked = saveOriginal,
                        onChange = { vm.setSaveOriginal(it) },
                    )
                    // 新建工程标题联动（用户拍板 2026-10-13，默认开）
                    SettingsSwitchRow(
                        title = I18n.t("工程名作水印标题"),
                        desc = I18n.t("新建工程时，工程名自动填入水印标题"),
                        checked = createTitleFromName,
                        onChange = { vm.setCreateTitleFromName(it) },
                    )
                    SettingsValueRow(
                        title = I18n.t("文件保存路径"),
                        value = saveTree?.second ?: I18n.t("DCIM/Camera（默认）"),
                        onClick = { showSavePathDialog.value = true },
                    )
                }
                Spacer(Modifier.height(8.dp))
                }

                // 署名固定置底（不随设置内容滚动；发布日期随版本更新）；
                // 有新版本时此处改为更新提示（带红点，点击打开 Release 页，用户需求 2026-10-22）
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val update = updateInfo
                if (update != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                                }
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(ChromeDanger),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            I18n.t("发现新版本 {v}，点击查看更新").replace("{v}", update.version),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // versionCode 运行时读取（发布日期随版本手更）
                    val versionCode = runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                    }.getOrDefault(0L)
                    Text(
                        "Rnzhy 2026.11.27 $versionCode engCamera MIT",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                // 署名点击直达本项目的 Release 页（用户需求 2026-11-18：
                                // 原来是官网首页，用户要的是直接看版本/下载）
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/zy8270/engCamera/releases"),
                                        ),
                                    )
                                }
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/** 设置抽屉的可折叠分组（v0.6.18，用户需求 2026-11-09）：同级手风琴，仅一个展开。
 *  v0.6.47 追加 VOLUME_DELAY；v0.6.48 该组拆解（音量键放回平铺、延时入口移到顶部卡片），
 *  新增 UI_LANGUAGE（外观/语言/快门相关，排第一）。 */
private enum class SettingsGroup { UI_LANGUAGE, WATERMARK, ZOOM, QUALITY, STORAGE }

/** 分组标题：整行可点，右侧展开/收起状态图标（箭头随状态翻转） */
@Composable
private fun SettingsGroupHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) {
                I18n.t("收起 {t}").replace("{t}", title)
            } else {
                I18n.t("展开 {t}").replace("{t}", title)
            },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 分组内容容器：文档流内展开/收起（AnimatedVisibility 参与布局，后续分组随之下移/上移，
 *  不做浮层覆盖）。左侧缩进体现从属关系。 */
@Composable
private fun SettingsGroupBody(expanded: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(200)),
        exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, bottom = 6.dp),
        ) { content() }
    }
}

/**
 * 常用功能卡片（v0.6.48，用户需求 2026-11-27）：图标 + 文字，九宫格第一排。
 * 激活态靠底色深浅表达（不是描边）：启用=主色实底（亮色模式变深、暗色模式变浅，
 * 正好是用户说的「暗色模式反之」），未启用=贴近背景的容器色。
 */
@Composable
private fun SettingsFeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalAppDarkTheme.current
    // 未启用：几乎与背景同色（亮色用 high、暗色用 lowest），只有很淡的存在感
    val inactiveBg = if (dark) {
        MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val bg = if (active) MaterialTheme.colorScheme.primary else inactiveBg
    val fg = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 分组内的开关行（标题+说明+右侧 Switch） */
@Composable
private fun SettingsSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 分组内的「标题 + 当前值」行（点击进入二级界面，如语言、保存路径） */
@Composable
private fun SettingsValueRow(title: String, value: String, onClick: () -> Unit) {    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 延时拍照设置弹窗（v0.6.47，用户需求 2026-11-27）。
 * 首行开关「启用延时拍照」与输入框双向联动：关→0 秒，开→5 秒（若当前为 0），
 * 手改输入框为 0 时开关自动关。输入框限三位纯数字，预设胶囊一键填值。
 * 返回键=关弹窗（Dialog 自己处理），不会退出应用。
 */
@Composable
private fun DelayShutterDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    // 弹窗内草稿：只有点「确定」才落库（点取消/返回=不生效）。
    // 用 TextFieldValue 而不是 String：String 重载在首次聚焦时选区落在 TextRange.Zero，
    // 光标会跑到数字前面（用户反馈 2026-11-27）；这里显式把光标放到内容末尾，
    // 与「接着改已有秒数」的直觉一致。程序改值（开关/预设胶囊）同样把光标推到末尾。
    var textTfv by remember(current) {
        val init = if (current > 0) "$current" else "0"
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                init,
                androidx.compose.ui.text.TextRange(init.length),
            ),
        )
    }
    val text = textTfv.text
    fun setText(t: String) {
        textTfv = androidx.compose.ui.text.input.TextFieldValue(
            t,
            androidx.compose.ui.text.TextRange(t.length),
        )
    }
    val enabled = (text.toIntOrNull() ?: 0) > 0
    val focus = remember { FocusRequester() }
    // 自动激活输入法：弹窗展开后请求焦点（延一帧，等 Dialog 窗口挂上）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { focus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("延时拍照")) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        I18n.t("启用延时拍照"),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            // 开：从 0 起步时给 5 秒默认值；关：清零
                            setText(if (on) "5" else "0")
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = textTfv,
                    onValueChange = { v ->
                        // 只留数字、最多三位（正整数；空串允许，提交时按 0 处理）。
                        // 过滤后文本可能变短，光标要夹回合法范围并保持在末尾附近
                        val digits = v.text.filter { it.isDigit() }.take(3)
                        textTfv = v.copy(
                            text = digits,
                            selection = androidx.compose.ui.text.TextRange(
                                v.selection.end.coerceIn(0, digits.length),
                            ),
                        )
                    },
                    label = { Text(I18n.t("延时时间（秒）")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Spacer(Modifier.height(12.dp))
                // 预设：胶囊按钮自动均分一排（5/10/20/30 秒）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(5, 10, 20, 30).forEach { preset ->
                        val selected = text.toIntOrNull() == preset
                        Box(
                            Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                )
                                .clickable { setText("$preset") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                I18n.t("{n}秒").replace("{n}", "$preset"),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm((text.toIntOrNull() ?: 0).coerceIn(0, 999))
            }) { Text(I18n.t("确定")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("取消")) }
        },
    )
}

/** 徽标随重力旋转的布局修饰符：0/180° 原地旋转（旋转后包围盒不变，不越界）；
 *  ±90° 按旋转后的可视尺寸参与布局——测量约束宽高互换、绘制期中心补偿旋转，
 *  胶囊横竖排都严丝合缝落在原边距内、点击区=可视区
 *  （v167 纯 rotate 原地旋转，横屏下竖排胶囊越出预览边界，用户反馈 2026-10-16） */
private fun Modifier.rotateWithLayout(deg: Float): Modifier {
    val norm = ((deg.roundToInt() % 360) + 360) % 360
    if (norm != 90 && norm != 270) return graphicsLayer { rotationZ = deg }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(
            Constraints(
                minWidth = 0,
                minHeight = 0,
                maxWidth = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity,
                maxHeight = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity,
            ),
        )
        val w = placeable.height
        val h = placeable.width
        layout(w, h) {
            placeable.placeWithLayer((w - placeable.width) / 2, (h - placeable.height) / 2) {
                rotationZ = deg
            }
        }
    }
}

/**
 * 拼图小地图（v0.6.19，用户需求 2026-11-09）：取景框正中，显示当前列数 × 行数的格子。
 * 行数 = max(2, ceil(已拍/列数))——1列→1×2、2列→2×2、3列→3×2、4列→4×2，拍多了自动加行。
 * 已拍格填黄 #FFC107 @60%，未拍格透明+浅描边；红头模板时网格上方加一条红色小横条。
 * 不拦触摸：外部 Box 已有点按对焦/捏合缩放，这里只做视觉。
 */
@Composable
private fun CollageMinimap(
    columns: Int,
    taken: Int,
    redHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val cols = columns.coerceIn(1, 4)
    val rows = maxOf(2, (taken + cols - 1) / cols)
    // 格子边长恒为「4 列档」的尺寸（26dp），不随列数变化（用户反馈 2026-11-09：
    // 1 列时格子撑得过大）。列数少时整体更窄，保持真实版式比例感
    val cellDp = 26.dp
    val gapDp = 4.dp
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x66101418))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (redHeader) {
            // 红头模板标识：一条红色小横条
            Box(
                Modifier
                    .width(cellDp * cols + gapDp * (cols - 1))
                    .height(4.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color(0xFFC0272D)),
            )
            Spacer(Modifier.height(4.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(gapDp)) {
            repeat(rows) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(gapDp)) {
                    repeat(cols) { c ->
                        val index = r * cols + c
                        val filled = index < taken
                        Box(
                            Modifier
                                .size(cellDp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (filled) Color(0x99FFC107) else Color(0x14FFFFFF),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (filled) Color(0xCCFFC107) else Color(0x40FFFFFF),
                                    shape = RoundedCornerShape(2.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 取景辅助层（v0.6.48，用户需求 2026-11-27）：构图线 + 水平仪，参照系统相机实现。
 * - 构图线：九宫格（1/3、2/3 处两横两竖），与取景框对齐、不随重力旋转
 * - 水平仪：过中心的横线随持机倾斜转动；接近水平（±1°）时缩短并变黄
 * roll 用 provider 传入：读状态发生在**绘制阶段**，每 0.2° 的传感器更新只触发重绘，
 * 不触发相机页重组（相机页重组代价大）。
 */
@Composable
private fun ViewfinderGuides(
    grid: Boolean,
    level: Boolean,
    rollDeg: () -> Float,
    modifier: Modifier = Modifier,
) {
    if (!grid && !level) return
    Canvas(modifier) {
        if (grid) {
            val lineColor = Color(0x59FFFFFF)
            val stroke = 1.dp.toPx()
            val tx = size.width / 3f
            val ty = size.height / 3f
            listOf(tx, tx * 2).forEach { x ->
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
            }
            listOf(ty, ty * 2).forEach { y ->
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
            }
        }
        if (level) {
            // v0.6.48 用户反馈 2026-11-27：原来按 180° 周期归一判水平，只有正立/倒置高亮，
            // 横屏（±90）永远不激活。现在拆成两件事：
            // 1) 线的**方向**跟真实地平线走（180° 周期归一：0 与 180 同一方向、±90 竖直），
            //    横屏时地平线在屏幕上是竖的，线就该是竖的；
            // 2) 线的**水平判定**按 90° 周期归一——0/90/180/270 四个姿态都算「正」，
            //    这样横屏和倒置一样能激活高亮。
            val raw = rollDeg()
            val dir = ((raw + 90f) % 180f + 180f) % 180f - 90f
            val dev = ((raw + 45f) % 90f + 90f) % 90f - 45f
            val isLevel = kotlin.math.abs(dev) <= 1f
            // 已水平就把线锁到最近的 90° 整数倍（去掉 ±1° 内的抖动，视觉上是「咔哒」对齐）
            val angle = if (isLevel) (raw / 90f).roundToInt() * 90f else dir
            val center = Offset(size.width / 2f, size.height / 2f)
            val half = (if (isLevel) 26.dp else 88.dp).toPx()
            val color = if (isLevel) ChromeAmber else Color(0xCCFFFFFF)
            // 先画一层深色底衬，保证在浅色画面上也看得见
            rotate(angle, center) {
                drawLine(
                    Color(0x59000000),
                    Offset(center.x - half, center.y),
                    Offset(center.x + half, center.y),
                    strokeWidth = 3.dp.toPx(),
                )
                drawLine(
                    color,
                    Offset(center.x - half, center.y),
                    Offset(center.x + half, center.y),
                    strokeWidth = 1.5.dp.toPx(),
                )
                // 两端小竖线：像水平仪的刻度端头
                val tick = 4.dp.toPx()
                listOf(-half, half).forEach { dx ->
                    drawLine(
                        color,
                        Offset(center.x + dx, center.y - tick),
                        Offset(center.x + dx, center.y + tick),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
            }
            // 水平时中心点实心圆点：一眼确认「已经正了」
            if (isLevel) {
                drawCircle(ChromeAmber, radius = 2.dp.toPx(), center = center)
            }
        }
    }
}

/** 3:4 预览区（与成片比例一致），水印浮层所见即所得；焦段/计时角标只转角度不挪位置 */@Composable
private fun PreviewArea(
    modifier: Modifier,
    previewView: PreviewView,
    vm: CameraViewModel,
    effective: com.engcamera.data.WatermarkState,
    dynamic: com.engcamera.data.DynamicValues,
    ui: CameraUiState,
    rot: Int,
    onOpenEditor: () -> Unit,
    collageColumns: Int = 1,
    collageTemplate: com.engcamera.data.CollageTemplate = com.engcamera.data.CollageTemplate.NONE,
    // 延时拍照倒计时（v0.6.47）：>0 时在预览正中叠一个大号半透明数字
    delayCountdown: Int = 0,
    // 取景辅助（v0.6.48）：构图线 + 水平仪；roll 走 lambda 供绘制阶段读取
    gridLines: Boolean = false,
    levelGauge: Boolean = false,
    levelRoll: () -> Float = { 0f },
) {
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier
            .aspectRatio(3f / 4f)
            // 空白预览区点击对焦测光；水印块自身的点击（进编辑器）在其手势层被消费，不会到这里
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (vm.focusAt(pos.x, pos.y)) focusPoint = pos
                }
            }
            // 双指捏合电子变焦（Initial pass 拦截：捏合优先于水印拖动/对焦点击）
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) {
                            vm.onPinchZoom(zoom)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // 取景辅助：构图线 + 水平仪（v0.6.48，用户需求 2026-11-27）。画在预览之上、
        // 水印之下：它们只辅助取景，绝不能盖住水印预览的可见性
        ViewfinderGuides(
            grid = gridLines,
            level = levelGauge,
            rollDeg = levelRoll,
            modifier = Modifier.matchParentSize(),
        )
        // 液态玻璃实时采样源：PreviewView 当前帧（TextureView 模式可 getBitmap）。
        // 上一次帧在下次采样时回收（本 lambda 串行调用，覆盖层拿到的位图只读不回收）
        val backdropProvider = remember(previewView) {
            var last: android.graphics.Bitmap? = null
            {
                last?.recycle()
                val fresh = runCatching { previewView.bitmap }.getOrNull()
                last = fresh
                fresh
            }
        }
        WatermarkOverlay(
            state = effective,
            dynamic = dynamic,
            rotationDeg = effective.rotationDeg,
            onTap = onOpenEditor,
            onDrag = { dx, dy -> vm.onDragWatermark(dx, dy) },
            modifier = Modifier.fillMaxSize(),
            backdropProvider = backdropProvider,
            glassSource = previewView, // 液态玻璃：库 LiquidGlassView 直接采样相机预览
            // 锚点为重力参照系：预览按当前持机方向转到屏幕系（横拍时「下方」落在预览物理下方侧）
            gravityRollDeg = rot.toFloat(),
        )
        // 快门闪黑：拍照成功（文件已写入）瞬间的机械快门式强反馈——立即压暗、短暂停留、
        // 快速抬起；纯 UI 动画不阻塞烧录保存，连拍时新计数直接重启本轮动画
        val shutterTick = ui.shutterTick
        val flashAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(shutterTick) {
            if (shutterTick > 0) {
                flashAlpha.snapTo(0.9f)
                kotlinx.coroutines.delay(60)
                flashAlpha.animateTo(0f, tween(180))
            }
        }
        if (flashAlpha.value > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = flashAlpha.value)),
            )
        }
        focusPoint?.let { pos -> FocusIndicator(pos) }
        // 右上角合并胶囊（v0.6.21，用户需求 2026-11-12）：此前「电子变焦倍数」(1.0×) 与
        // 「后置焦段」(2.9× ▾) 是两个上下叠在一起的胶囊，用户看截图以为重复了。
        // 现在合成一个：左段=电子变焦（点按 1×/2× 快切），右段=物理镜头
        // （点按轮询下一档，长按展开面板）。两者语义不同，所以用分隔线分成两段而不是合并数值。
        val hasLensChoice = !ui.frontCamera && ui.lensLabels.size > 1
        val lensLocked by vm.lensLocked.collectAsState()
        var lensMenuOpen by remember { mutableStateOf(false) }
        if (lensMenuOpen) {
            // 全屏透明拦截层：点面板外任意处收起面板
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { lensMenuOpen = false },
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            // 纵向半透明焦段面板（胶囊正下方展开；**不随重力旋转**，数字横排好读）
            if (hasLensChoice) {
                AnimatedVisibility(
                    visible = lensMenuOpen,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        // ±90° 时胶囊横躺占高更大，面板相应下移避让
                        .padding(top = if (rot == 90 || rot == -90) 96.dp else 40.dp),
                ) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xB3141416))
                            .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        ui.lensLabels.forEachIndexed { index, label ->
                            val selected = index == ui.lensIndex
                            Text(
                                text = label,
                                color = if (selected) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(vertical = 1.dp)
                                    .clip(CircleShape)
                                    .background(if (selected) Color.White else Color.Transparent)
                                    .clickable {
                                        vm.selectLens(index)
                                        lensMenuOpen = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        // 镜头锁定（v0.6.39，Issue #3 用户需求 2026-11-22）：选好镜头后
                        // 在这里锁定，之后双指缩放只在该镜头内做数码变焦，不会自动切镜头
                        HorizontalDivider(
                            Modifier.padding(vertical = 4.dp, horizontal = 6.dp),
                            color = Color(0x33FFFFFF),
                        )
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.setLensLocked(!lensLocked) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (lensLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                tint = if (lensLocked) Color(0xFFFFC107) else Color.White,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                I18n.t("锁定镜头"),
                                color = if (lensLocked) Color(0xFFFFC107) else Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            // 合并胶囊本体
            Row(
                Modifier
                    .rotateWithLayout(rot.toFloat())
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x66000000)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左段：电子变焦倍数（点按 1×/2× 快切）
                Text(
                    String.format(Locale.US, "%.1f×", ui.zoomRatio),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { vm.toggleZoom() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                if (hasLensChoice) {
                    // 分隔线：明确这是两个独立维度（数字变焦 vs 物理镜头）
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(12.dp)
                            .background(Color(0x66FFFFFF)),
                    )
                    // 右段：当前物理焦段（点按轮询下一档，长按展开面板）
                    // 锁定时数字前加一个小锁标（用户一眼看出当前是固定镜头模式）
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { vm.selectLens((ui.lensIndex + 1) % ui.lensLabels.size) },
                                onLongClick = { lensMenuOpen = !lensMenuOpen },
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        if (lensLocked) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = I18n.t("已锁定镜头"),
                                tint = Color(0xFFFFC107),
                                modifier = Modifier.size(10.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            text = ui.lensLabels.getOrElse(ui.lensIndex) { "1×" } + " ▾",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        // 拼图小地图（v0.6.19，用户需求 2026-11-09）：取景框正中显示当前列数×行数的格子，
        // 已拍格标黄、未拍格透明。纯视觉不拦触摸（否则挡住点水印进编辑器）。
        // 放在镜头胶囊之前，保证胶囊等操作控件在最上层
        if (ui.collageMode && !ui.collagePreviewOpen) {
            CollageMinimap(
                columns = collageColumns,
                taken = ui.collageItems.size,
                redHeader = collageTemplate.hasHeader,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 录像中角标（左上角，位置固定；±90° 按旋转后尺寸布局防越界）
        if (ui.recording) {            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .rotateWithLayout(rot.toFloat())
                    .clip(RoundedCornerShape(8.dp))
                    .background(ChromeBadge)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(ChromeDanger),
                )
                Spacer(Modifier.width(6.dp))
                Text("${ui.recordingSeconds}s", color = Color.White)
            }
        }
        // 延时拍照倒计时（v0.6.47，用户需求 2026-11-27）：预览正中一个大号浅白半透明数字，
        // 数字宽度约屏宽 1/3；每秒换数字时从完全透明渐显（不压暗画面，用户拍板）
        if (delayCountdown > 0) {
            DelayCountdownOverlay(delayCountdown)
        }
        // 注：电子变焦徽标已并入右上角的合并胶囊（v0.6.21 左段），此处不再单独渲染
    }
}

/** 倒计时数字：宽约屏宽 1/3（按位数反算字号），每秒从透明→浅白半透明并轻微缩入 */
@Composable
private fun DelayCountdownOverlay(count: Int) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val digits = count.toString().length
        // 单字符 advance 约 0.6em：要让整串宽度≈1/3 屏宽，字号≈(W/3)/(0.6×位数)
        val fontSize = with(density) {
            (maxWidth / (3f * 0.6f * digits)).toSp()
        }
        val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
        val scale = remember { androidx.compose.animation.core.Animatable(1.12f) }
        LaunchedEffect(count) {
            alpha.snapTo(0f)
            scale.snapTo(1.12f)
            launch { scale.animateTo(1f, tween(280)) }
            alpha.animateTo(1f, tween(280))
        }
        Text(
            text = "$count",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = fontSize,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}

/** 点击对焦指示圈：在点击位置短暂显示后淡出 */
@Composable
private fun FocusIndicator(center: Offset) {
    var alphaTarget by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(center) {
        alphaTarget = 1f
        delay(700)
        alphaTarget = 0f
    }
    val alpha by animateFloatAsState(alphaTarget, label = "focusAlpha")
    if (alpha > 0.01f) {
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (center.x - 27.dp.toPx()).roundToInt(),
                        (center.y - 27.dp.toPx()).roundToInt(),
                    )
                }
                .size(54.dp)
                .alpha(alpha)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

/**
 * 添加水印模式预览区（与相机预览同为 3:4 外框）：
 * 未选图 = 黑屏 + 大圆圈"+"（点击选图）；已选图 = 图片按原比例居中显示，
 * 水印浮层与相机一致（可拖动/点击进编辑器），顶部显示进度 x/y。
 */
@Composable
private fun AnnotateArea(
    modifier: Modifier,
    vm: CameraViewModel,
    // 原始水印状态（v226 锁定纵向）：本模式不受重力影响——rotationDeg 不随量化
    // 重力角替换（effectiveWatermark 会替换），以手机屏幕底部为底渲染与烧录
    watermark: com.engcamera.data.WatermarkState,
    dynamic: com.engcamera.data.DynamicValues,
    ui: CameraUiState,
    onOpenEditor: () -> Unit,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    val item = ui.annotateQueue.getOrNull(ui.annotateIndex)
    // 3:4 外框实际像素（空态玻璃采样底按内缩后尺寸合成，v232）
    var frameWpx by remember { mutableStateOf(0) }
    var frameHpx by remember { mutableStateOf(0) }
    // 空态框内底色（用户需求 2026-10-17）：圆角边线范围内日间主题浅底（F8FAFD，
    // 截图取色 F7F9FB）改 DFE3EB 更沉的灰蓝；框外一圈仍走主题背景（同快门区），
    // 夜间框内也保持主题深底——只改本预览区不动全局主题
    val emptyBase = if (com.engcamera.ui.theme.LocalAppDarkTheme.current) {
        MaterialTheme.colorScheme.background
    } else {
        Color(0xFFDFE3EB)
    }
    // v225 与拍照确认共用 AnnotationFrame；v226 空态=主题底+可编辑水印浮层
    Box(
        modifier
            .aspectRatio(3f / 4f)
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { frameWpx = it.width; frameHpx = it.height },
    ) {
        // 液态玻璃采样源：当前图片的 EXIF 转正降采样位图（与烧录基准一致）。
        // 解码走 IO 线程（大图主线程解码会卡帧）；结果带日志便于排查玻璃不显（v229）
        var annotateBackdrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(item?.uri) {
            val bmp = item?.let { it2 ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    loadAnnotateBackdrop(context, it2.uri)
                }
            }
            val old = annotateBackdrop
            annotateBackdrop = bmp
            old?.recycle()
        }
        val backdropProvider = remember { { annotateBackdrop } }
        // 水印块包围盒（WatermarkOverlay 上报，长按命中判定用）
        var wmBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        AnnotationFrame(
            hasPhoto = item != null,
            photoAr = item?.aspect ?: (3f / 4f),
            ui = ui,
            vm = vm,
            modifier = Modifier.fillMaxSize(),
            // 选图前 DFE3EB 沉底（+号/提示文字在浅灰蓝上可读性更好，用户需求
            // 2026-10-17），选图后换透明格
            showChecker = item != null,
            emptyBase = emptyBase,
            watermarkBounds = { wmBounds },
            onWatermarkDrag = { dx, dy -> vm.onDragWatermarkLocked(dx, dy) },
            // 标注期间进水印编辑器（用户需求 2026-11-03）：添加水印页读的是实时
            // watermark（锁定纵向预览），编辑器保存=仓库更新，返回本页浮层自动生效，
            // 无需重新冻结——批量多张时每张都能单独进去改水印
            onEditWatermark = onOpenEditor,
        ) {
            val cur = item
            if (cur != null) {
                coil.compose.AsyncImage(
                    model = cur.uri,
                    contentDescription = I18n.t("待加水印图片"),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                WatermarkOverlay(
                    state = watermark,
                    dynamic = dynamic,
                    rotationDeg = watermark.rotationDeg,
                    onTap = { },
                    onDrag = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    scaleByShortSide = true, // 烧录按图片短边缩放，横图预览须一致
                    backdropProvider = backdropProvider,
                    // gravityRollDeg 保持默认 0：图片摆正显示且本模式锁定纵向，锚点直接落位；
                    // 手势全让给标注层（长按水印块拖动走 AnnotationFrame 的接管通道）
                    interactive = false,
                    onBlockBounds = { wmBounds = it },
                )
            }
        }
        if (item == null) {
            // 空状态：主题底画框中央大圆圈 +，点击打开图片选择器
            Column(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onPick() } },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    // 重心居中（v0.6.43，用户实测反馈 2026-11-23）：「+」按文本框
                    // 居中时，字形墨迹（笔画）并不在文本框正中——「+」的墨迹在字体
                    // 基线以上偏上，视觉上就偏了。这里用 getTextBounds 量出**墨迹**
                    // 包围盒，再按其中心反推绘制位置（重心居中）
                    val plusColor = MaterialTheme.colorScheme.onSurface.toArgb()
                    Canvas(Modifier.fillMaxSize()) {
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = plusColor
                            textSize = 44.sp.toPx()
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            textAlign = android.graphics.Paint.Align.LEFT
                        }
                        val bounds = android.graphics.Rect()
                        paint.getTextBounds("+", 0, 1, bounds)
                        // 墨迹中心相对绘制原点的偏移（x=左缘, y=基线）
                        val dx = size.width / 2f - bounds.exactCenterX()
                        val dy = size.height / 2f - bounds.exactCenterY()
                        drawContext.canvas.nativeCanvas.drawText("+", dx, dy, paint)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    I18n.t("点击选择要加水印的图片"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
            // 空态玻璃采样底（v232）：把空态可见内容（主题底+圆圈+/提示文字）复刻成
            // 一张位图供液态玻璃采样——旧版空态采样源恒 null，玻璃只剩占位卡（拖到
            // 中央盖住加号/文字时"折射色散消失"的直接原因）。内缩 26dp 与浮层
            // padding(13.dp) 同几何，内容居中关系一致（对称内缩中心不变）
            val density = LocalDensity.current
            val insetPx = with(density) { 26.dp.toPx() }.roundToInt()
            val bgArgb = emptyBase.toArgb()
            val fgArgb = MaterialTheme.colorScheme.onSurface.toArgb()
            val subArgb = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
            val hint = I18n.t("点击选择要加水印的图片")
            val emptyBackdrop = remember(
                frameWpx, frameHpx, insetPx, bgArgb, fgArgb, subArgb, hint,
            ) {
                val w = frameWpx - insetPx
                val h = frameHpx - insetPx
                if (w > 0 && h > 0) {
                    runCatching {
                        renderAnnotateEmptyBackdrop(
                            w, h, density.density, bgArgb, fgArgb, subArgb, hint,
                        )
                    }.getOrNull()
                } else {
                    null
                }
            }
            // provider 实例稳定（v232）：内联 lambda 每次重组都是新实例，会让玻璃
            // 管线把 backdropProvider 当 key 逐帧重启（拖动期间重组频繁）
            val emptyBackdropProvider = remember(emptyBackdrop) { { emptyBackdrop } }
            // 实时水印浮层（声明在「+」之后=上层：块内点按进编辑器/拖动改位，
            // 其余区域落到「+」的选图层）。同步锁定纵向，拖动不旋回重力系。
            // 内缩与灰框一致（10dp padding+3dp 边框）：底部锚点的水印随框缩小
            // 落回框内，不压边框（用户反馈 2026-10-17）
            WatermarkOverlay(
                state = watermark,
                dynamic = dynamic,
                rotationDeg = watermark.rotationDeg,
                onTap = onOpenEditor,
                onDrag = { dx, dy -> vm.onDragWatermarkLocked(dx, dy) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(13.dp),
                scaleByShortSide = true,
                backdropProvider = emptyBackdropProvider,
            )
        } else if (ui.annotateQueue.size > 1) {
            // 顶部进度 x/y
            Text(
                "${ui.annotateIndex + 1}/${ui.annotateQueue.size}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ChromeBadge)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** 标注图降采样位图（EXIF 转正，≤720 边）：液态玻璃采样用，与烧录基准同一方向。
 *  v231 改走 Coil：直连 BitmapFactory 在部分设备（vivo 实测 GlassDbg loaded=false）
 *  对相册选择器 URI 加载失败，而 Coil 显示同一张图正常——采样与显示统一走同一
 *  加载器（软件位图供玻璃 getPixels 采样，EXIF 方向自动转正） */
private suspend fun loadAnnotateBackdrop(context: android.content.Context, uri: Uri): android.graphics.Bitmap? =
    runCatching {
        val req = coil.request.ImageRequest.Builder(context)
            .data(uri)
            .allowHardware(false) // 玻璃采样要读像素，禁硬件位图
            .size(720, 720) // 等比压到最长边 ≤720（与旧解码上限一致）
            .build()
        val dr = coil.Coil.imageLoader(context).execute(req).drawable
        (dr as? android.graphics.drawable.BitmapDrawable)?.bitmap
    }.getOrNull()

/** 添加水印空态的玻璃采样底（v232）：复刻空态可见内容（主题底+圆圈+/提示文字）
 *  画进一张与浮层同尺寸的位图——液态玻璃盖在 +/文字上时有真实折射/色散。
 *  只求视觉一致（内容静态简单），不与真实 UI 逐像素对齐；sp 按 dp 近似。
 *  几何与真实布局同构：圆圈中心=画面中心（浮层对称内缩中心不变），文字在
 *  圆下 14dp 处垂直居中 */
private fun renderAnnotateEmptyBackdrop(
    w: Int,
    h: Int,
    density: Float,
    bgArgb: Int,
    fgArgb: Int,
    subArgb: Int,
    hint: String,
): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(bgArgb)
    fun dpx(v: Float) = v * density
    val cx = w / 2f
    val cy = h / 2f
    val r = dpx(48f) // 圆圈 96dp 的半径
    val circle = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = dpx(3f)
        color = fgArgb
    }
    canvas.drawCircle(cx, cy, r, circle)
    val fmi = android.graphics.Paint.FontMetricsInt()
    val plus = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = fgArgb
        textSize = dpx(44f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.LEFT
    }
    // 「+」按墨迹包围盒中心居中（v0.6.43，与真实 UI 同款重心算法）：fontMetrics
    // 居中是文本框居中，字形墨迹会偏上
    val inkBounds = android.graphics.Rect()
    plus.getTextBounds("+", 0, 1, inkBounds)
    canvas.drawText(
        "+",
        cx - inkBounds.exactCenterX(),
        cy - inkBounds.exactCenterY(),
        plus,
    )
    val hintPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = subArgb
        textSize = dpx(13f)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    hintPaint.getFontMetricsInt(fmi)
    canvas.drawText(hint, cx, cy + r + dpx(14f) - (fmi.ascent + fmi.descent) / 2f, hintPaint)
    return bmp
}

/**
 * 拼图入口图标（v0.6.19，用户需求 2026-11-09：替换原画质选择器位置）：
 * 2×2 方格 + 其中一格为"照片"实心块，隐喻多张照片拼成一张。
 * 单色线条风格与 [QualityGlyph] 对齐。
 */
@Composable
private fun CollageGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val pad = sw / 2f
        val gap = w * 0.09f
        val cellW = (w - sw - gap) / 2f
        val cellH = (h - sw - gap) / 2f
        val r = androidx.compose.ui.geometry.CornerRadius(w * 0.05f, w * 0.05f)
        // 四格：左上/右上/左下为描边空框，右下为实心（"已放入照片"）
        listOf(
            Offset(pad, pad) to false,
            Offset(pad + cellW + gap, pad) to false,
            Offset(pad, pad + cellH + gap) to false,
            Offset(pad + cellW + gap, pad + cellH + gap) to true,
        ).forEach { (tl, filled) ->
            if (filled) {
                drawRoundRect(
                    color = tint,
                    topLeft = tl,
                    size = Size(cellW, cellH),
                    cornerRadius = r,
                )
            } else {
                drawRoundRect(
                    color = tint,
                    topLeft = tl,
                    size = Size(cellW, cellH),
                    cornerRadius = r,
                    style = Stroke(sw),
                )
            }
        }
    }
}

/** 画质入口图标：方格胶片（圆角外框+四角齿孔）中央叠 HD 字样，单色线条风格对齐 material 图标 */@Composable
private fun QualityGlyph(tint: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = size.minDimension * 0.085f
            drawRoundRect(
                color = tint,
                topLeft = Offset(sw / 2, sw / 2),
                size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius(size.minDimension * 0.18f, size.minDimension * 0.18f),
                style = Stroke(sw),
            )
            // 四角齿孔（胶片隐喻）
            val hole = size.minDimension * 0.11f
            val m = size.minDimension * 0.13f
            listOf(
                Offset(m, m),
                Offset(size.width - m - hole, m),
                Offset(m, size.height - m - hole),
                Offset(size.width - m - hole, size.height - m - hole),
            ).forEach {
                drawRoundRect(
                    color = tint,
                    topLeft = it,
                    size = Size(hole, hole),
                    cornerRadius = CornerRadius(hole * 0.3f, hole * 0.3f),
                )
            }
        }
        Text(
            "HD",
            color = tint,
            fontSize = 7.sp,
            lineHeight = 7.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

/** 竖屏底部控制条：上行 = 工程组+模式切换+画质选择；下行 = 缩略图+快门+前后摄 */
@Composable
private fun BottomBar(
    rot: Int,
    ui: CameraUiState,
    vm: CameraViewModel,
    startRecordingWithPermission: (Boolean) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenProjectGroups: () -> Unit,
    onPickAnnotateImage: () -> Unit = {},
    onOpenCollageSettings: () -> Unit = {},
    onRequestExitCollage: () -> Unit = {},
    onConfirmCollage: () -> Unit = {},
    onEnterCollage: () -> Unit = {},
    onAddFromGallery: () -> Unit = {},
    // 延时拍照（v0.6.47）：快门上的沙漏标记 + 需要延时的动作包装 + 读秒进度环
    showDelayIcon: Boolean = false,
    onDelayableShutter: (() -> Unit) -> Unit = { it() },
    delayProgress: () -> Float = { 0f },
    delayRunning: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标注态（添加水印模式已选图）：底栏与拍照确认同一套——工具/颜色/X/蓝色下载
        val annotatingPhoto = ui.annotating && ui.annotateQueue.isNotEmpty()
        // 扫码结果气泡（快门键上方；文字保持横向便于阅读）
        if (ui.bubble != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ChromeBrand,
            ) {
                Text(
                    I18n.t("识别到二维码：{c}").replace("{c}", ui.bubble),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable { vm.copyBubble() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // ===== 上行：工程组 | 模式切换胶囊（恒居中）| 画质选择（靠右，位于前后摄切换按钮上方） =====
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                // 与取景框之间保留间隙（用户需求 2026-11-10）：胶囊从 48dp 收到 38dp
                // 后行高变矮，原本由"胶囊比两侧高"撑出的空白消失了，这里显式补回
                .padding(top = 5.dp),
        ) {
            // 工程组快捷入口（左）：保留经典三横线带三角图案（祖宗之法），容器风格与模式切换统一
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onOpenProjectGroups() }
                    .padding(8.dp),
            ) {
                com.engcamera.ui.ProjectGroupGlyph(
                    Modifier
                        .size(22.dp)
                        .rotate(rot.toFloat()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 编辑（笔）/拍照/录像：同一胶囊容器、统一样式——未激活 onSurfaceVariant、激活主色。
            // 确认态与添加水印标注态切换为标注工具（箭头/矩形/笔刷/文字），带过渡动画
            // （用户拍板 2026-10-16；v225 扩到添加水印）
            androidx.compose.animation.AnimatedContent(
                targetState = when {
                    ui.confirming || annotatingPhoto -> 1
                    ui.collageMode -> 2
                    else -> 0
                },
                // v0.6.45（用户反馈 2026-11-23）：过渡动画改为**从中心缩放淡入**——
                // 之前只给进入加了 scaleIn、退出是纯 fadeOut，scaleIn 默认 transformOrigin
                // 是 Center 但配合 AnimatedContent 默认的 sizeTransform/对齐会看起来
                // 从左上或右下飞。这里进出都用 scale + fade，并显式给 sizeTransform
                // null（不让容器尺寸动画带偏移），保证缩放轴恒在中心
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = fadeIn(tween(200)) +
                            scaleIn(initialScale = 0.7f, animationSpec = tween(200)),
                        initialContentExit = fadeOut(tween(150)) +
                            scaleOut(targetScale = 0.7f, animationSpec = tween(150)),
                        sizeTransform = null,
                    )
                },
                // 内容对齐中心（默认 TopStart，宽度不同的胶囊切换时会从左上角飞）
                contentAlignment = Alignment.Center,
                label = "modeTools",
                modifier = Modifier.align(Alignment.Center),
            ) { modeState ->
                val annotUi = modeState == 1
                if (modeState == 2) {
                    // 拼图模式（v0.6.19，用户需求 2026-11-09）：模板设置 + X 退出 两个图标。
                    // v0.6.32 中间加「相册加图」（圆圈+号）：边拍边从相册加、边拼
                    Row(
                        Modifier
                            .height(CapsuleHeight)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        IconButton(onClick = onOpenCollageSettings, modifier = Modifier.size(CapsuleHeight)) {
                            Icon(
                                Icons.Default.GridView,
                                contentDescription = I18n.t("拼图模板设置"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(rot.toFloat()),
                            )
                        }
                        IconButton(onClick = onAddFromGallery, modifier = Modifier.size(CapsuleHeight)) {
                            Icon(
                                Icons.Default.AddCircle,
                                contentDescription = I18n.t("从相册添加图片"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(rot.toFloat()),
                            )
                        }
                        IconButton(onClick = onRequestExitCollage, modifier = Modifier.size(CapsuleHeight)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = I18n.t("退出拼图"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(rot.toFloat()),
                            )
                        }
                    }
                } else if (annotUi) {
                    Row(
                        Modifier
                            .height(CapsuleHeight)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        listOf(0, 1, 2, 3).forEach { tool ->
                            IconButton(onClick = { vm.setAnnotationTool(tool) }, modifier = Modifier.size(CapsuleHeight)) {
                                val tint = if (ui.annotationTool == tool) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                when (tool) {
                                    0 -> Icon(Icons.Default.NorthEast, contentDescription = I18n.t("箭头"), tint = tint)
                                    1 -> Icon(Icons.Default.CheckBoxOutlineBlank, contentDescription = I18n.t("矩形"), tint = tint)
                                    2 -> Icon(Icons.Default.Brush, contentDescription = I18n.t("画笔"), tint = tint)
                                    else -> androidx.compose.foundation.Canvas(
                                        Modifier.size(24.dp),
                                    ) {
                                        // 描边风格字母 A（对齐 Material outlined 图标：
                                        // 24dp 画布、圆头描边 ~2dp，顶点+两脚+横杠）
                                        val w = size.width
                                        val sw = w * 0.085f
                                        val apex = Offset(w * 0.50f, w * 0.17f)
                                        val left = Offset(w * 0.26f, w * 0.83f)
                                        val right = Offset(w * 0.74f, w * 0.83f)
                                        drawLine(tint, apex, left, strokeWidth = sw)
                                        drawLine(tint, apex, right, strokeWidth = sw)
                                        drawLine(
                                            tint,
                                            Offset(w * 0.36f, w * 0.62f),
                                            Offset(w * 0.64f, w * 0.62f),
                                            strokeWidth = sw,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                Row(
                    Modifier
                        .height(CapsuleHeight)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    IconButton(
                        onClick = { vm.toggleAnnotate() },
                        enabled = !ui.recording,
                        modifier = Modifier.size(CapsuleHeight),
                    ) {
                        Icon(
                            Icons.Default.Draw,
                            contentDescription = I18n.t("给图片添加水印"),
                            tint = if (ui.annotating) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.rotate(rot.toFloat()),
                        )
                    }
                    IconButton(
                        onClick = { vm.setVideoMode(false) },
                        enabled = !ui.recording,
                        modifier = Modifier.size(CapsuleHeight),
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = I18n.t("拍照模式"),
                            tint = if (!ui.annotating && !ui.videoMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.rotate(rot.toFloat()),
                        )
                    }
                    IconButton(
                        onClick = { vm.setVideoMode(true) },
                        enabled = !ui.recording,
                        modifier = Modifier.size(CapsuleHeight),
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = I18n.t("录像模式"),
                            tint = if (!ui.annotating && ui.videoMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.rotate(rot.toFloat()),
                        )
                    }
                }
                } // else（模式胶囊）
            } // AnimatedContent
            // 画质选择（靠右；录像/照片档位互斥显示，仅一档时无可选不显示）。入口=胶片+HD 图标
            // 确认态/添加水印标注态切换为标注颜色选择器（默认红，用户拍板 2026-10-16）
            data class QOption(val label: String, val spec: String)
            val qualityList = if (ui.videoMode) {
                ui.videoQualities.map { QOption(it.label, it.spec) }
            } else {
                ui.photoQualities.map { QOption(it.label, it.spec) }
            }
            val qualityIndex = if (ui.videoMode) ui.videoQualityIndex else ui.photoQualityIndex
            var colorMenuOpen by remember { mutableStateOf(false) }
            var showFreeColor by remember { mutableStateOf(false) }
            // 拼图模式（v0.6.19）：原画质入口位置换成「✓ 确认生成拼图」——入口图标本身
            // 已在拼图模式下变成打勾方形按钮（用户需求 2026-11-09）
            if (ui.collageMode) {
                Box(Modifier.align(Alignment.CenterEnd)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onConfirmCollage() }
                            .padding(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = I18n.t("生成拼图"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(rot.toFloat()),
                        )
                    }
                }
            } else if (ui.collageMode || qualityList.size > 1 || ui.confirming || annotatingPhoto) {
                Box(Modifier.align(Alignment.CenterEnd)) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = ui.confirming || annotatingPhoto,
                        // v0.6.45：与模式胶囊一致——从中心缩放淡入/向中心缩放淡出
                        transitionSpec = {
                            ContentTransform(
                                targetContentEnter = fadeIn(tween(200)) +
                                    scaleIn(initialScale = 0.7f, animationSpec = tween(200)),
                                initialContentExit = fadeOut(tween(150)) +
                                    scaleOut(targetScale = 0.7f, animationSpec = tween(150)),
                                sizeTransform = null,
                            )
                        },
                        // 内容对齐中心（默认 TopStart，不同尺寸的图标切换会从左上角飞）
                        contentAlignment = Alignment.Center,
                        label = "qualityColor",
                    ) { annotUi ->
                        if (annotUi) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { colorMenuOpen = !colorMenuOpen }
                                    .padding(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color(ui.annotationColor.toInt()))
                                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                                )
                            }
                        } else {
                            // 原画质入口已迁入设置抽屉（v0.6.18），此处改为拼图模式入口
                            // （v0.6.19，用户需求 2026-11-09）。不受画质档位数量限制——
                            // 只有一档画质时拼图入口也必须存在
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onEnterCollage() }
                                    .padding(8.dp),
                            ) {
                                CollageGlyph(
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .rotate(rot.toFloat()),
                                )
                            }
                        }
                    }
                    // 标注颜色面板（默认红）。v0.6.43 修：添加水印标注态也要能开
                    // （原来只认 ui.confirming，导致该态点色块无反应）
                    androidx.compose.material3.DropdownMenu(
                        expanded = colorMenuOpen && (ui.confirming || annotatingPhoto),
                        onDismissRequest = { colorMenuOpen = false },
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            listOf(
                                0xFFFF5252L, // 红（默认）
                                0xFFFFD54FL, // 黄
                                0xFF81C784L, // 绿
                                0xFF64B5F6L, // 蓝
                                0xFFFFFFFFL, // 白
                                0xFF000000L, // 黑
                            ).forEach { c ->
                                Box(
                                    Modifier
                                        .padding(2.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(c.toInt()))
                                        .border(
                                            1.5.dp,
                                            if (ui.annotationColor == c) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        )
                                        .clickable {
                                            vm.setAnnotationColor(c)
                                            colorMenuOpen = false
                                        },
                                )
                            }
                            // 彩虹圆=自由选色（与水印编辑器同款取色器，用户拍板 2026-10-16）
                            Box(
                                Modifier
                                    .padding(2.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        androidx.compose.ui.graphics.Brush.sweepGradient(
                                            listOf(
                                                Color(0xFFFF5252), Color(0xFFFFD54F), Color(0xFF81C784),
                                                Color(0xFF64B5F6), Color(0xFFB388FF), Color(0xFFFF5252),
                                            ),
                                        ),
                                    )
                                    .border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { showFreeColor = true },
                            )
                        }
                    }
                    if (showFreeColor) {
                        com.engcamera.editor.ColorPickerDialog(
                            title = I18n.t("标注颜色"),
                            initial = ui.annotationColor,
                            onDismiss = { showFreeColor = false },
                            onConfirm = { c ->
                                vm.setAnnotationColor(c)
                                showFreeColor = false
                                colorMenuOpen = false
                            },
                        )
                    }
                    // 展开的画质列表（向下弹出）
                    androidx.compose.material3.DropdownMenu(
                        expanded = ui.showQualityMenu,
                        onDismissRequest = { vm.toggleQualityMenu() },
                    ) {
                        qualityList.forEachIndexed { idx, q ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    // 实际分辨率 + 规格（照片=像素量级与比例；视频=录制规格/帧率）
                                    Column {
                                        Text(
                                            q.label,
                                            fontWeight = FontWeight.Medium,
                                            color = if (idx == qualityIndex) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        Text(
                                            q.spec,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    if (ui.videoMode) vm.selectVideoQuality(idx) else vm.selectPhotoQuality(idx)
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 下行：缩略图 + 快门 + 前后摄（同一水平线） =====
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp),
        ) {
            // 最近照片缩略图（左）
            // 拼图模式下（v0.6.19）：变成「已连拍 N 张」预览入口——背景=最后一张缩略图，
            // 上方白字黑描边张数（用户需求 2026-11-09）
            if (ui.collageMode) {
                CollageBurstThumb(
                    items = ui.collageItems,
                    rot = rot,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp),
                    onClick = { vm.setCollagePreviewOpen(true) },
                )
            } else {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                        .size(52.dp)
                        .rotate(rot.toFloat())
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(10.dp))
                        .clickable { onOpenGallery() },
                    contentAlignment = Alignment.Center,
                ) {
                    val latest = ui.latestMedia
                    if (latest != null) {
                        AsyncImage(
                            model = latest.uri,
                            contentDescription = I18n.t("最近照片"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = I18n.t("相册"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 快门键（中）
            Shutter(
                ui = ui,
                vm = vm,
                startRecordingWithPermission = startRecordingWithPermission,
                onPickAnnotateImage = onPickAnnotateImage,
                modifier = Modifier.align(Alignment.Center),
                onDelayableShutter = onDelayableShutter,
                showDelayIcon = showDelayIcon,
                delayProgress = delayProgress,
                delayRunning = delayRunning,
            )

            // 前后摄切换（右）：确认态=X 丢弃不保存；添加水印标注态=X 返回选图态；
            // 拍摄态=前后摄切换（用户拍板 2026-10-16，v225 扩）
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        when {
                            ui.confirming -> vm.confirmDiscard()
                            annotatingPhoto -> vm.clearAnnotateSelection()
                            else -> vm.flipCamera()
                        }
                    }
                    .padding(8.dp),
            ) {
                val annotX = ui.confirming || annotatingPhoto
                Icon(
                    if (annotX) Icons.Default.Close else Icons.Default.Cameraswitch,
                    contentDescription = when {
                        ui.confirming -> I18n.t("丢弃")
                        annotatingPhoto -> I18n.t("返回")
                        else -> I18n.t("前后摄切换")
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(rot.toFloat()),
                )
            }
        }
    }
}

/** 快门键：点击拍照；长按快捷录像；录像模式下点击开始/停止 */
@Composable
private fun Shutter(
    ui: CameraUiState,
    vm: CameraViewModel,
    startRecordingWithPermission: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    // 添加水印空态点下载=发起选图（等于点 +，用户需求 2026-10-17）
    onPickAnnotateImage: () -> Unit = {},
    // 延时拍照（v0.6.47）：拍照/开录/拼图连拍这三类「按快门后要等」的动作
    // 走这个包装（内部决定立即执行还是先倒计时）；保存/停止录像不受延时影响
    onDelayableShutter: (() -> Unit) -> Unit = { it() },
    // 已设置延时：快门中心叠一个沙漏图标（用户需求 2026-11-27）
    showDelayIcon: Boolean = false,
    // 倒计时进度 0..1（读在绘制阶段：每帧只重绘进度环，不触发整页重组）
    delayProgress: () -> Float = { 0f },
    // 倒计时进行中（>0）：快门中间空隙显示进度环
    delayRunning: Boolean = false,
) {
    val quickRecording = remember { mutableStateOf(false) }
    // 保存处理中：快门缩小表示"正在处理"，完成恢复（自定义目录保存有扫描等待，需要可见反馈）
    val shutterScale by animateFloatAsState(
        targetValue = if (ui.capturing) 0.82f else 1f,
        animationSpec = tween(150),
        label = "shutterScale",
    )
    // 快门形态（v0.6.44，用户需求 2026-11-23）：
    // 0=拍照（主题实心圆） 1=录像待录（纯红圆） 2=录像中（红色圆角小方块）
    // 3=确认/添加水印（蓝底下载键）
    val mode = when {
        ui.confirming || ui.annotating -> 3
        ui.recording -> 2
        ui.videoMode -> 1
        else -> 0
    }
    // 点击行为按 mode 分派（AnimatedContent 里不能各自持有手势，统一在外面）
    val onClick: () -> Unit = {
        when {
            ui.confirming -> vm.confirmSave()
            ui.annotating ->
                if (ui.annotateQueue.isEmpty()) onPickAnnotateImage() else vm.confirmAnnotate()
            ui.collageMode -> onDelayableShutter { vm.takeCollagePhoto() }
            ui.videoMode ->
                if (ui.recording) vm.stopRecording() else onDelayableShutter { startRecordingWithPermission(false) }
            else -> if (!ui.recording) onDelayableShutter { vm.takePhoto() }
        }
    }
    // 手势闭包里必须读**最新**的 onClick：倒计时中再按一次要能取消，
    // 而 pointerInput 的 key 不含倒计时状态，闭包会停在按下时的旧值
    val latestOnClick = rememberUpdatedState(onClick)
    // 三种形态共用同一套「外圈描边 + 中间实心芯」结构（v0.6.47 修订，用户需求 2026-11-27）：
    // 拍照/录像芯=实心圆，保存/标注芯=主色实心圆 + 白色下载图标，视觉重心统一。
    // 外圈底色只有录像两态是淡红填充，其余透明
    val ringTint = when (mode) {
        1, 2 -> ChromeDanger
        3 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .size(78.dp)
            .scale(shutterScale)
            .clip(CircleShape)
            .background(if (mode == 1 || mode == 2) Color(0x33FF5252) else Color.Transparent)
            .border(width = 5.dp, color = ringTint, shape = CircleShape)
            .pointerInput(ui.videoMode, ui.recording, ui.annotating, ui.confirming, ui.collageMode) {
                detectTapGestures(
                    onTap = { latestOnClick.value() },
                    onLongPress = {
                        // 拼图模式禁用长按快捷录像（用户需求 2026-11-09）
                        if (mode == 0) {
                            quickRecording.value = true
                            startRecordingWithPermission(true)
                        }
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (quickRecording.value) {
                            quickRecording.value = false
                            vm.stopRecording()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 延时读秒进度环（v0.6.47 修订，用户需求 2026-11-27）：
        // 画在「5dp 外圈描边内缘(半径 34dp)」到「58dp 实心芯(半径 29dp)」之间的空隙里，
        // 描边 5.2dp 刚好铺满整条带、不留缝（多出的 0.1dp 被后画的实心芯盖住）。
        // **倒计时：按下瞬间是满环，随秒数流逝走到空**；progress 的读取放在绘制
        // lambda 里，每帧只失效这一层重绘，不触发整页重组。
        if (delayRunning) {
            // 录像两态：外圈是 FF5252 全红、外圈填充是淡红 0x33FF5252（≈F9D8DB），
            // 进度环夹在两者之间用中间红 0xFC9597 做一点点区分（用户需求 2026-11-27）
            val ringColor = if (mode == 1 || mode == 2) Color(0xFFFC9597)
            else MaterialTheme.colorScheme.primary
            Canvas(Modifier.matchParentSize()) {
                val radius = 31.5.dp.toPx() // 空隙带中线
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    // 从满到空：剩余比例
                    sweepAngle = 360f * (1f - delayProgress().coerceIn(0f, 1f)),
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = 5.2.dp.toPx()),
                )
            }
        }
        // 中心芯：三种模式之间淡入淡出 + 缩放过渡（用户需求 2026-11-23「闪成对应样式」）
        // v0.6.46：**必须显式 contentAlignment = Center**——AnimatedContent 的
        // contentAlignment 默认是 TopStart，而上一版把 sizeTransform 置 null 后
        // 容器立即变成目标尺寸：58dp 圆 ↔ 34dp 下载图标时，小的一方按左上角对齐，
        // 看起来就是"从左上角飞进中心"。改成 Center 后新旧内容都绕同一个中心点缩放
        androidx.compose.animation.AnimatedContent(
            targetState = mode,
            transitionSpec = {
                ContentTransform(
                    targetContentEnter = androidx.compose.animation.fadeIn(tween(220)) +
                        androidx.compose.animation.scaleIn(
                            initialScale = 0.55f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ),
                    initialContentExit = androidx.compose.animation.fadeOut(tween(140)) +
                        androidx.compose.animation.scaleOut(
                            targetScale = 0.55f,
                            animationSpec = tween(140),
                        ),
                    sizeTransform = null,
                )
            },
            // 关键：内容对齐到中心（默认 TopStart 会导致从左上角飞入）
            contentAlignment = Alignment.Center,
            label = "shutterCore",
        ) { m ->
            when (m) {
                // 拍照：主题实心圆
                0 -> Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
                // 录像待录（v0.6.44）：中心**纯红**圆，与拍照区分
                1 -> Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(ChromeDanger),
                )
                // 录像中：缩小成圆角小方块（停止键）
                2 -> Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(ChromeDanger),
                )
                // 确认/添加水印（v0.6.47 修订）：主色实心圆 + 白色下载图标，
                // 与拍照/录像同构（外圈描边由父 Box 统一画）
                else -> Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = I18n.t("保存照片"),
                        tint = Color.White,
                        // 芯内图标略小，四周留白，与实心圆芯的视觉重量对齐
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        // 已设置延时拍照：快门芯中心叠一个秒表（用户拍板 2026-11-27：用 AV 秒表）。
        // 只在「会走倒计时」的两个形态（拍照 / 录像待录）显示，录像中与保存态不显示
        if (showDelayIcon && (mode == 0 || mode == 1)) {
            Icon(
                Icons.Default.AvTimer,
                contentDescription = I18n.t("已启用延时拍照"),
                // 与快门芯反色：拍照芯=onSurface（日间深/夜间浅），图标取 surface；
                // 录像待录芯=纯红，图标取白。括号不可省——否则 copy 只作用于 else 分支
                tint = (if (mode == 0) MaterialTheme.colorScheme.surface else Color.White)
                    .copy(alpha = 0.92f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 定位异常提示条（用户反馈 2026-11-01）：权限已授权但仍拿不到定位时，告诉用户到底
 * 缺哪一步——"定位总开关没开"和"权限被系统收回"是两件完全不同的事，前者只需跳
 * 系统位置设置，后者要重新授权/去应用设置。
 */
@Composable
private fun LocationHintBar(
    permissionDenied: Boolean,
    onOpenLocationSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val text = if (permissionDenied) {
        I18n.t("未获得定位权限，水印位置将无法自动填写")
    } else {
        I18n.t("系统定位服务未开启，水印位置将无法自动填写")
    }
    val action = if (permissionDenied) I18n.t("去授权") else I18n.t("去开启")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f))
            .clickable {
                when {
                    // 权限被系统收回：先重新申请；系统不再弹窗时用户可长按/点"去授权"
                    // 反复无果，故申请回调里被拒就跳应用设置
                    permissionDenied -> onRequestPermission()
                    else -> onOpenLocationSettings()
                }
            }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
        Text(
            action,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    // 权限已"授权过但失效"时系统不再弹窗，这里给一条直达应用设置的退路
                    if (permissionDenied) onOpenAppSettings() else onOpenLocationSettings()
                }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        // 关闭按钮：提示条会一直占着预览下方一条（用户反馈太显眼），给 X 随时收起
        Box(
            Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = I18n.t("关闭"),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 相机权限被拒：占位页 + 去设置 */
@Composable
private fun PermissionGate(onOpenSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            I18n.t("需要相机权限才能拍照\n请在系统设置中授权"),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable { onOpenSettings() },
        ) {
            Text(
                I18n.t("去设置"),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}

/** 外观切换：三段式分段控件（跟随系统/日间/夜间），选中段用主色浮起 */
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to I18n.t("跟随系统"),
        ThemeMode.LIGHT to I18n.t("日间"),
        ThemeMode.DARK to I18n.t("夜间"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (mode, label) ->
            val active = mode == selected
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}
