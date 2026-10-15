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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.engcamera.data.ThemeMode
import com.engcamera.ui.theme.ChromeBadge
import com.engcamera.ui.theme.ChromeBrand
import com.engcamera.ui.theme.ChromeDanger
import com.engcamera.ui.theme.ChromeIcon
import com.engcamera.ui.theme.ChromeNeutral
import com.engcamera.ui.ProjectGroupGlyph
import com.engcamera.ui.theme.ChromeScrim
import com.engcamera.ui.theme.LocalAppDarkTheme
import com.engcamera.watermark.WatermarkOverlay

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
    val dynamic by vm.dynamic.collectAsState()
    val ui by vm.ui.collectAsState()

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
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    // Toast 提示
    ui.toast?.let { toast ->
        LaunchedEffect(toast) {
            android.widget.Toast.makeText(context, toast, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!cameraGranted) {
            PermissionGate(onOpenSettings = ::openAppSettings)
        } else {
            var showSettings by remember { mutableStateOf(false) }
            var showProjectGroups by remember { mutableStateOf(false) }
            // 抽屉展开时返回键收起抽屉，而不是退出应用
            androidx.activity.compose.BackHandler(enabled = showSettings) { showSettings = false }
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
                vm.rebind = {
                    engine.bind(previewView, vm.barcodeAnalyzer())
                    vm.onEngineBound()
                }
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
                        vm.showToast("相机初始化失败：${t.message ?: "未知错误"}")
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
                        }

                        Lifecycle.Event.ON_PAUSE -> vm.stopEverything()
                        else -> Unit
                    }
                }
                navLifecycle.lifecycle.addObserver(observer)
                onDispose {
                    navLifecycle.lifecycle.removeObserver(observer)
                    previewView.removeOnLayoutChangeListener(layoutListener)
                    vm.stopEverything()
                    vm.rebind = null
                    vm.detachEngine()
                }
            }

            val rot = ui.uiRotation

            Column(Modifier.fillMaxSize()) {
                // 顶部控制条：闪光灯左、设置右；图标角度随重力，位置不变
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.cycleFlash() }) {
                        Icon(
                            imageVector = when (ui.flashIndex) {
                                1 -> Icons.Default.FlashOn
                                2 -> Icons.Default.FlashAuto
                                else -> Icons.Default.FlashOff
                            },
                            contentDescription = "闪光灯",
                            tint = if (ui.flashIndex == 0) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.rotate(rot.toFloat()),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.rotate(rot.toFloat()),
                        )
                    }
                }

                // 预览区：3:4 填满宽度；添加水印模式下换成黑屏选图/图片预览
                if (ui.annotating) {
                    AnnotateArea(
                        modifier = Modifier.fillMaxWidth(),
                        vm = vm,
                        effective = effective,
                        dynamic = dynamic,
                        ui = ui,
                        onOpenEditor = onOpenEditor,
                        onPick = {
                            annotatePicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
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
                        onOpenEditor = onOpenEditor,
                    )
                }

                // 底部控制条紧贴预览下方，不推到屏幕底部
                BottomBar(rot, ui, vm, ::startRecordingWithPermission, onOpenGallery, { showProjectGroups = true })
            }

            // 音量键→快门（设置开启时 Activity 转发；仅相机页组合期间注册）
            val appContainer = (context.applicationContext as com.engcamera.EngCameraApp).container
            DisposableEffect(navLifecycle, vm) {
                appContainer.volumeKeyHandler = {
                    val st = vm.ui.value
                    when {
                        st.annotating -> { vm.confirmAnnotate(); true }
                        st.videoMode -> {
                            if (st.recording) vm.stopRecording() else startRecordingWithPermission(false)
                            true
                        }
                        else -> { vm.takePhoto(); true }
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

            // 右上角设置：右侧滑出抽屉（约 3/4 屏宽）
            SettingsDrawer(
                visible = showSettings,
                vm = vm,
                onDismiss = { showSettings = false },
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
) {
    val context = LocalContext.current
    val saveOriginal by vm.saveOriginal.collectAsState()
    val createTitleFromName by vm.createTitleFromName.collectAsState()
    val shutterSound by vm.shutterSound.collectAsState()
    val volumeShutter by vm.volumeShutter.collectAsState()
    val watermark by vm.watermark.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    val saveTree by vm.saveTree.collectAsState()
    val showSavePathDialog = remember { mutableStateOf(false) }
    val saveTreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val path = android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':', "所选目录")
            vm.setSaveTree(uri, path)
            android.widget.Toast.makeText(context, "保存路径已设为 $path", android.widget.Toast.LENGTH_SHORT).show()
            showSavePathDialog.value = false
        }
    }

    if (showSavePathDialog.value) {
        AlertDialog(
            onDismissRequest = { showSavePathDialog.value = false },
            title = { Text("文件保存路径") },
            text = {
                Text(
                    "当前：" + (saveTree?.second ?: "DCIM/Camera（默认）") +
                        "\n\n选择文件夹后，照片和录像将保存到所选目录（系统将请求您授权访问该文件夹）。",
                )
            },
            confirmButton = {
                TextButton(onClick = { saveTreeLauncher.launch(null) }) { Text("选择文件夹") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.setSaveTree(null, null)
                    showSavePathDialog.value = false
                    android.widget.Toast.makeText(context, "已恢复默认 DCIM/Camera", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("恢复默认") }
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
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(20.dp))

                // 外观：跟随系统 / 日间 / 夜间（0.6.0 新增，DataStore 持久化）
                Text(
                    "外观",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "日间/夜间模式，或跟随系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ThemeModeSelector(
                    selected = themeMode,
                    onSelect = { vm.setThemeMode(it) },
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "保存原图原视频",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "开启后拍照/录像同时保存无水印原片与水印成片（相册里每次出两个文件）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = saveOriginal, onCheckedChange = { vm.setSaveOriginal(it) })
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "快门声音",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "拍照时播放系统快门音（部分地区的法律要求开启）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = shutterSound, onCheckedChange = { vm.setShutterSound(it) })
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "音量键拍照",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "主界面按音量键视同按下快门",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = volumeShutter, onCheckedChange = { vm.setVolumeShutter(it) })
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "水印跟随重力",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "开启后水印按重力 90° 旋转（跟随照片方向），并自动重置到预览画面右下角；关闭后使用固定角度",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = watermark.followGravity, onCheckedChange = { vm.setFollowGravity(it) })
                }
                if (!watermark.followGravity) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "固定旋转角",
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
                Spacer(Modifier.height(16.dp))

                // 新建工程标题联动（用户拍板 2026-10-13：放文件保存路径上方，默认开）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "新建工程标题联动",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "新建工程组时，输入的工程名自动填入该工程水印的标题",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = createTitleFromName, onCheckedChange = { vm.setCreateTitleFromName(it) })
                }
                Spacer(Modifier.height(16.dp))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showSavePathDialog.value = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "文件保存路径",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            saveTree?.second ?: "DCIM/Camera（默认）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                }

                // 署名固定置底（不随设置内容滚动；发布日期随版本更新）
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                // versionCode 运行时读取（发布日期随版本手更）
                val versionCode = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
                }.getOrDefault(0L)
                Text(
                    "Rnzhy 2026.10.16 $versionCode engCamera MIT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zy8270/engCamera")),
                                )
                            }
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

/** 3:4 预览区（与成片比例一致），水印浮层所见即所得；焦段/计时角标只转角度不挪位置 */
@Composable
private fun PreviewArea(
    modifier: Modifier,
    previewView: PreviewView,
    vm: CameraViewModel,
    effective: com.engcamera.data.WatermarkState,
    dynamic: com.engcamera.data.DynamicValues,
    ui: CameraUiState,
    rot: Int,
    onOpenEditor: () -> Unit,
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
        )
        focusPoint?.let { pos -> FocusIndicator(pos) }
        // 后置焦段（右上角）：单击轮询下一档；长按向下展开半透明面板列出全部焦段供点选
        // （不同设备焦段数不同；面板展开时点面板外关闭，用户拍板 2026-10-13）
        if (!ui.frontCamera && ui.lensLabels.size > 1) {
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
                    .padding(top = 56.dp, end = 8.dp),
            ) {
                // 纵向半透明焦段面板（按钮正下方展开；不随重力旋转，数字横排好读）
                AnimatedVisibility(
                    visible = lensMenuOpen,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 38.dp),
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
                    }
                }
                // 当前焦段胶囊：单击轮询下一档，长按展开/收起面板（▾ 提示可展开）
                Text(
                    text = ui.lensLabels.getOrElse(ui.lensIndex) { "1×" } + " ▾",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .rotate(rot.toFloat())
                        .clip(CircleShape)
                        .background(Color.White)
                        .combinedClickable(
                            onClick = { vm.selectLens((ui.lensIndex + 1) % ui.lensLabels.size) },
                            onLongClick = { lensMenuOpen = !lensMenuOpen },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        // 录像中角标（左上角，位置固定）
        if (ui.recording) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .rotate(rot.toFloat())
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
        // 电子变焦倍数徽标：点击在 1×/2× 间快速切换（右上角；镜头胶囊下移让位）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x66000000))
                .clickable { vm.toggleZoom() }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                String.format(Locale.US, "%.1f×", ui.zoomRatio),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
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
    effective: com.engcamera.data.WatermarkState,
    dynamic: com.engcamera.data.DynamicValues,
    ui: CameraUiState,
    onOpenEditor: () -> Unit,
    onPick: () -> Unit,
) {
    val context = LocalContext.current
    Box(modifier.aspectRatio(3f / 4f).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val item = ui.annotateQueue.getOrNull(ui.annotateIndex)
        if (item == null) {
            // 空状态：素色屏 + 大圆圈 +，点击打开图片选择器
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
                    Text(
                        "+",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "点击选择要加水印的图片",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            }
        } else {
            // 图片按原比例居中（等比缩放），水印浮层铺在图片实际显示区上（所见即所得）
            // 液态玻璃采样源：当前图片的 EXIF 转正降采样位图（与烧录基准一致）
            var annotateBackdrop by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(item.uri) {
                val bmp = loadAnnotateBackdrop(context, item.uri)
                val old = annotateBackdrop
                annotateBackdrop = bmp
                old?.recycle()
            }
            val backdropProvider = remember { { annotateBackdrop } }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxSize()
                    .padding(vertical = 40.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .aspectRatio(item.aspect),
                ) {
                    coil.compose.AsyncImage(
                        model = item.uri,
                        contentDescription = "待加水印图片",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    WatermarkOverlay(
                        state = effective,
                        dynamic = dynamic,
                        rotationDeg = effective.rotationDeg,
                        onTap = onOpenEditor,
                        onDrag = { dx, dy -> vm.onDragWatermark(dx, dy) },
                        modifier = Modifier.fillMaxSize(),
                        scaleByShortSide = true, // 烧录按图片短边缩放，横图预览须一致
                        backdropProvider = backdropProvider,
                    )
                }
            }
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
        // 退出添加水印模式：点底部的笔或拍照/录像按钮
    }
}

/** 标注图降采样位图（EXIF 转正，≤720 边长）：液态玻璃采样用，与烧录基准同一方向 */
private fun loadAnnotateBackdrop(context: android.content.Context, uri: Uri): android.graphics.Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ?: return@runCatching null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 720 || bounds.outHeight / (sample * 2) >= 720) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val src = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return@runCatching null
    val deg = context.contentResolver.openInputStream(uri)?.use {
        androidx.exifinterface.media.ExifInterface(it).rotationDegrees
    } ?: 0
    if (deg == 0) {
        src
    } else {
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
            if (it != src) src.recycle()
        }
    }
}.getOrNull()

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
) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 扫码结果气泡（快门键上方；文字保持横向便于阅读）
        if (ui.bubble != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = ChromeBrand,
            ) {
                Text(
                    "识别到二维码：${ui.bubble}",
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
                .padding(horizontal = 24.dp),
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
            // 编辑（笔）/拍照/录像：同一胶囊容器、统一样式——未激活 onSurfaceVariant、激活主色
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    IconButton(
                        onClick = { vm.toggleAnnotate() },
                        enabled = !ui.recording,
                    ) {
                        Icon(
                            Icons.Default.Draw,
                            contentDescription = "给图片添加水印",
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
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "拍照模式",
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
                    ) {
                        Icon(
                            Icons.Default.Videocam,
                            contentDescription = "录像模式",
                            tint = if (!ui.annotating && ui.videoMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.rotate(rot.toFloat()),
                        )
                    }
                }
            }
            // 画质选择（靠右；录像/照片档位互斥显示，仅一档时无可选不显示）。入口=胶片+HD 图标
            data class QOption(val label: String, val spec: String)
            val qualityList = if (ui.videoMode) {
                ui.videoQualities.map { QOption(it.label, it.spec) }
            } else {
                ui.photoQualities.map { QOption(it.label, it.spec) }
            }
            val qualityIndex = if (ui.videoMode) ui.videoQualityIndex else ui.photoQualityIndex
            if (!ui.annotating && qualityList.size > 1) {
                Box(Modifier.align(Alignment.CenterEnd)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.toggleQualityMenu() }
                            .padding(8.dp),
                    ) {
                        QualityGlyph(
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(rot.toFloat()),
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
                        contentDescription = "最近照片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = "相册",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }

            // 快门键（中）
            Shutter(
                ui = ui,
                vm = vm,
                startRecordingWithPermission = startRecordingWithPermission,
                modifier = Modifier.align(Alignment.Center),
            )

            // 前后摄切换（右）：容器风格与工程组/画质按钮统一（surfaceVariant 圆角块）
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.flipCamera() }
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Cameraswitch,
                    contentDescription = "前后摄切换",
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
) {
    val quickRecording = remember { mutableStateOf(false) }
    // 保存处理中：快门缩小表示"正在处理"，完成恢复（自定义目录保存有扫描等待，需要可见反馈）
    val shutterScale by animateFloatAsState(
        targetValue = if (ui.capturing) 0.82f else 1f,
        animationSpec = tween(150),
        label = "shutterScale",
    )
    Box(
        modifier
            .size(78.dp)
            .scale(shutterScale)
            .clip(CircleShape)
            .background(if (ui.recording) Color(0x33FF5252) else Color.Transparent)
            .border(
                width = 5.dp,
                // 快门随主题：日间深色圈/芯、夜间浅色（用户拍板 2026-10-12）
                color = if (ui.recording) ChromeDanger else MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .pointerInput(ui.videoMode, ui.recording, ui.annotating) {
                detectTapGestures(
                    onTap = {
                        when {
                            ui.annotating -> vm.confirmAnnotate()
                            ui.videoMode -> {
                                if (ui.recording) {
                                    vm.stopRecording()
                                } else {
                                    startRecordingWithPermission(false)
                                }
                            }
                            !ui.recording -> vm.takePhoto()
                        }
                    },
                    onLongPress = {
                        if (!ui.videoMode && !ui.recording && !ui.annotating) {
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
        if (ui.annotating) {
            // 添加水印模式：快门变成"确认"
            Text("确认", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        } else if (!ui.recording) {
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
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
            "需要相机权限才能拍照\n请在系统设置中授权",
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
                "去设置",
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
        ThemeMode.SYSTEM to "跟随系统",
        ThemeMode.LIGHT to "日间",
        ThemeMode.DARK to "夜间",
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
