package com.engcamera.camera

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engcamera.EngCameraApp
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.ThemeMode
import com.engcamera.data.WatermarkState
import com.engcamera.scan.BarcodeAnalyzer
import com.engcamera.sensor.GravitySensor
import com.engcamera.util.MediaSaver
import com.engcamera.util.VideoWatermarker
import com.engcamera.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.engcamera.i18n.I18n

data class CameraUiState(
    /** 0 关 / 1 开 / 2 自动 */
    val flashIndex: Int = 0,
    val frontCamera: Boolean = false,
    val videoMode: Boolean = false,
    val recording: Boolean = false,
    val recordingSeconds: Int = 0,
    val bubble: String? = null,
    val capturing: Boolean = false,
    val toast: String? = null,
    /** 拍照成功计数：预览层据此触发一次快门闪黑反馈（文件已写入临时文件时递增） */
    val shutterTick: Int = 0,
    val lensLabels: List<String> = emptyList(),
    val lensIndex: Int = 0,
    val latestMedia: MediaSaver.MediaEntry? = null,
    /** 相机 UI 控件旋转角（重力 90° 量化）：0 / 90 / -90 / 180 */
    val uiRotation: Int = 0,
    /** 可选画质列表与当前选中 */
    val videoQualities: List<CameraEngine.VideoQuality> = emptyList(),
    val videoQualityIndex: Int = 0,
    /** 可选照片画质与当前选中（拍照模式显示） */
    val photoQualities: List<CameraEngine.PhotoQuality> = emptyList(),
    val photoQualityIndex: Int = 0,
    /** 画质选择菜单展开 */
    val showQualityMenu: Boolean = false,
    /** 电子变焦倍数（zoomState 回流）与当前镜头支持范围 */
    val zoomRatio: Float = 1f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 8f,
    /** 添加水印模式（给相册已有图片加水印）：待处理队列（uri + 宽高比）与当前索引 */
    val annotating: Boolean = false,
    val annotateQueue: List<AnnotateItem> = emptyList(),
    val annotateIndex: Int = 0,
    /** 正在烧录保存当前图片（防重复点击快门） */
    val annotateWorking: Boolean = false,
    // ---------- 拍照后确认（v193，用户拍板 2026-10-16） ----------
    /** 确认预览态：照片拍完先进预览框（绿描边+标注工具），手动点保存才落盘 */
    val confirming: Boolean = false,
    val confirmBitmap: Bitmap? = null,
    val annotations: List<PhotoAnnotation> = emptyList(),
    /** 0=箭头 1=矩形 2=笔刷 3=文字 */
    val annotationTool: Int = 0,
    val annotationColor: Long = 0xFFFF5252L, // 默认红
    /** 文字标注元素（归一化中心坐标 + 缩放/旋转，v195） */
    val textItems: List<TextItem> = emptyList(),
    /** 当前选中的文字元素（点按/拖动即选中；改颜色立即作用其上，v200） */
    val selectedTextId: Long? = null,
    /** 确认态冻结的水印状态与时间（按下快门定格，预览与保存共用；水印位置不再变） */
    val confirmWatermark: WatermarkState? = null,
    val confirmDynamic: DynamicValues? = null,
    /** 快门时刻的重力角（确认态水印锚点锁定用，转动手机不再变） */
    val confirmRoll: Int = 0,
    /** 快门时刻的屏幕系→成片系换算角（确认态水印块旋转补偿=与烧录同构，v210） */
    val confirmFrameRotation: Int = 0,
) {
    /** 添加水印模式的一个待处理图片 */
    data class AnnotateItem(val uri: android.net.Uri, val aspect: Float)

    /** 文字标注元素 */
    data class TextItem(
        val id: Long,
        val text: String,
        val x: Float, // 归一化（照片显示区）
        val y: Float,
        val scale: Float = 1f,
        val rotation: Float = 0f,
        val color: Long,
    )

    /** 一笔标注：点位归一化（0..1，相对照片显示区），保存时按全分辨率缩放绘制；
     * strokeScale=落笔时的预览缩放倍数——放大时画的笔画更细（精细标注），
     * 显示与烧录都按它除宽，笔画粗细定格在落笔瞬间（v204） */
    data class PhotoAnnotation(
        val tool: Int,
        val color: Long,
        val points: List<androidx.compose.ui.geometry.Offset>,
        val strokeScale: Float = 1f,
    )
}

class CameraViewModel(app: Application) : AndroidViewModel(app),
    com.engcamera.projectgroup.ProjectGroupHandler {

    private val container = (app as EngCameraApp).container
    private val context = app

    val watermark: StateFlow<WatermarkState> = container.repository.state

    private val _dynamic = MutableStateFlow(DynamicValues())
    val dynamic: StateFlow<DynamicValues> = _dynamic.asStateFlow()

    val gravitySensor = GravitySensor(app)
    val locationSource = container.locationSource

    private val quantizedRoll = MutableStateFlow(0)

    /**
     * 实际生效的水印状态（预览与成片共用）：
     * 重力跟随开启时旋转角取量化重力角。锚点为重力参照系（「下方」=物理重力方向）：
     * 预览层负责转到屏幕系显示，成片显示系=重力系直接落位——九宫格选「下方」，
     * 竖屏横屏倒置都稳定烧在成片的物理下方（用户拍板 2026-10-16）。
     */
    val effectiveWatermark: StateFlow<WatermarkState> =
        combine(watermark, quantizedRoll) { w, roll ->
            if (w.followGravity) w.copy(rotationDeg = roll.toFloat()) else w
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), watermark.value)

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    private val _mediaVersion = MutableStateFlow(0)
    val mediaVersion: StateFlow<Int> = _mediaVersion.asStateFlow()

    var engine: CameraEngine? = null
    var rebind: (() -> Unit)? = null

    private val analyzer = BarcodeAnalyzer { content -> onBarcode(content) }
    fun barcodeAnalyzer(): BarcodeAnalyzer = analyzer

    private var activeRecording: Recording? = null

    /** 确认态持有的临时照片文件与其成片换算角（保存/丢弃时消费） */
    private var confirmTempFile: File? = null
    private var confirmFrameRotation: Int = 0
    private var textIdGen = 0L
    private var pendingVideoFile: File? = null
    private var timerJob: Job? = null
    private var zoomCollectJob: Job? = null

    /**
     * 录像开始瞬间的水印快照 + 成片坐标系换算角 + 开始时刻 + 重力时间线（合成时使用，
     * 所见即所得）：startEpochMs 用于水印时间随录像实际时间流动（帧时间戳换算回墙钟）；
     * rollTimeline 记录录像期间的重力方向变化（epochMs → 量化 roll），烧录时按
     * 「当时 roll − 开录 roll」把锚点/文字角度旋回视频系——视频帧方向固定为开录时刻，
     * 预览水印随重力动，成片同步跟随（用户拍板 2026-10-16）
     */
    private class VideoSnapshot(
        val state: WatermarkState,
        val dynamic: DynamicValues,
        val frameRotation: Int,
        val startEpochMs: Long,
        val rollTimeline: List<Pair<Long, Int>>,
    )

    private var videoWatermarkSnapshot: VideoSnapshot? = null
    private var rollTimelineJob: Job? = null

    init {
        // 动态时间/位置每秒刷新（位置用于【位置】标签值为空时的拍照瞬间自动填充）
        viewModelScope.launch {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            while (isActive) {
                _dynamic.value = _dynamic.value.copy(
                    time = fmt.format(Date()),
                    location = locationSource.text.value,
                    weather = locationSource.weather.value,
                )
                delay(1000)
            }
        }
        viewModelScope.launch {
            locationSource.weather.collect { w ->
                _dynamic.value = _dynamic.value.copy(weather = w)
            }
        }

        gravitySensor.onQuantizedRoll = { deg ->
            quantizedRoll.value = deg
            // 成片方向跟随持机（对齐系统相机）：逆时针横屏(+90)→ROTATION_90，
            // 顺时针横屏(-90)→ROTATION_270，倒置(180)→ROTATION_180（用户四方向实测推导）。
            // 锚点不再随重力重置：水印位置由"水印位置"9 宫格选项/拖动决定（用户拍板 2026-10-16）
            engine?.setTargetRotation(
                when (deg) {
                    90 -> android.view.Surface.ROTATION_90
                    -90 -> android.view.Surface.ROTATION_270
                    180, -180 -> android.view.Surface.ROTATION_180
                    else -> android.view.Surface.ROTATION_0
                },
            )
        }
        viewModelScope.launch {
            quantizedRoll.collect { deg -> _ui.update { it.copy(uiRotation = deg) } }
        }
        // 拍照/录像后刷新缩略图
        viewModelScope.launch {
            _mediaVersion.collect { refreshLatest() }
        }
    }

    fun attachEngine(e: CameraEngine) {
        engine = e
    }

    fun detachEngine() {
        engine?.release()
        engine = null
    }

    fun onEngineBound() {
        val e = engine ?: return
        val qualities = e.getSupportedVideoQualities()
        val currentIdx = qualities.indexOfFirst { it.quality == e.videoQuality.quality }.coerceAtLeast(0)
        // 切镜头后所选画质可能不被新镜头支持（如前摄无 4K）：落到该镜头支持的最高档
        e.videoQuality = qualities[currentIdx]
        // 照片画质：按新镜头重算；之前选中的档位在新列表中存在则保持，否则落最高档
        val photoQs = e.getSupportedPhotoQualities()
        val photoIdx = photoQs.indexOfFirst { it.label == e.photoQuality?.label }
            .let { if (it < 0) 0 else it }
        e.photoQuality = photoQs.getOrNull(photoIdx)
        // 电子变焦：收集 zoomState 回流（重绑换相机实例时重挂收集）
        zoomCollectJob?.cancel()
        zoomCollectJob = viewModelScope.launch {
            e.zoomRatio.collect { z -> _ui.update { it.copy(zoomRatio = z) } }
        }
        zoomCollectJob = viewModelScope.launch {
            e.zoomMin.collect { m -> _ui.update { it.copy(zoomMin = m) } }
        }
        zoomCollectJob = viewModelScope.launch {
            e.zoomMax.collect { m -> _ui.update { it.copy(zoomMax = m) } }
        }
        _ui.update { s ->
            s.copy(
                lensLabels = e.backLenses.map { it.label },
                lensIndex = e.backLenses.indexOfFirst { it.cameraId == e.selectedLensId }.coerceAtLeast(0),
                frontCamera = e.frontCamera,
                videoQualities = qualities,
                videoQualityIndex = currentIdx,
                photoQualities = photoQs,
                photoQualityIndex = photoIdx,
            )
        }
        setFlashMode(_ui.value.flashIndex)
    }

    /** 选择录像画质并重绑 */
    fun selectVideoQuality(index: Int) {
        val e = engine ?: return
        val qualities = _ui.value.videoQualities
        if (index !in qualities.indices) return
        e.videoQuality = qualities[index]
        _ui.update { it.copy(videoQualityIndex = index, showQualityMenu = false) }
        rebind?.invoke()
    }

    /** 选择照片画质并重绑（ImageCapture 按分辨率策略就近匹配） */
    fun selectPhotoQuality(index: Int) {
        val e = engine ?: return
        val qualities = _ui.value.photoQualities
        if (index !in qualities.indices) return
        e.photoQuality = qualities[index]
        _ui.update { it.copy(photoQualityIndex = index, showQualityMenu = false) }
        rebind?.invoke()
    }

    fun toggleQualityMenu() {
        _ui.update { it.copy(showQualityMenu = !it.showQualityMenu) }
    }

    /** 上次"缩小跨镜头"时刻：一次捏合手势会连发多帧增量，去抖 600ms 防止连跳多级镜头 */
    private var lastWiderSwitchAt = 0L

    /** 双指捏合电子变焦：factor 为本次手势增量，目标值夹取到镜头支持范围（数字不再低于 1×）。
     *  已在当前镜头最广端仍继续缩小：自动切到更广一级镜头（如 1×→0.6× 超广角，用户拍板 2026-10-13）；
     *  放大方向不跨镜头（长焦切换点易抖动，用户未要求）。 */
    fun onPinchZoom(factor: Float) {
        val e = engine
        if (e != null && factor < 1f && _ui.value.zoomRatio <= _ui.value.zoomMin + 0.01f && !e.frontCamera) {
            val idx = e.backLenses.indexOfFirst { it.cameraId == e.selectedLensId }
            val now = android.os.SystemClock.elapsedRealtime()
            if (idx > 0 && now - lastWiderSwitchAt > 600) {
                lastWiderSwitchAt = now
                selectLens(idx - 1)
                return
            }
        }
        val target = (_ui.value.zoomRatio * factor)
            .coerceIn(_ui.value.zoomMin, _ui.value.zoomMax)
        engine?.setZoomRatio(target)
        _ui.update { it.copy(zoomRatio = target) }
    }

    /** 变焦徽标点击：1× 与 2× 之间快速切换（同样夹取范围） */
    fun toggleZoom() {
        val target = if (_ui.value.zoomRatio > 1.5f) 1f else 2f
        engine?.setZoomRatio(target)
        _ui.update { it.copy(zoomRatio = target) }
    }

    /** 自定义文件保存目录（null = 默认 DCIM/Camera） */
    val saveTree: StateFlow<Pair<Uri, String>?> = container.repository.saveTree

    fun setSaveTree(uri: Uri?, name: String?) = container.repository.setSaveTree(uri, name)

    /** 保存调用统一走这里取自定义目录 */
    private suspend fun saveTreeUri(): Uri? = container.repository.saveTree.value?.first

    /** 点击对焦测光（预览区像素坐标），返回是否已下发 */
    fun focusAt(x: Float, y: Float): Boolean = engine?.focusMeter(x, y) ?: false

    // ---------- 工程组（相机页快捷切换） ----------
    override val projects: StateFlow<com.engcamera.data.ProjectGroups> = container.repository.projects

    override fun selectProject(id: String) = container.repository.selectProject(id)

    override fun createProject(name: String, nameEdited: Boolean) = container.repository.createProject(name, nameEdited)

    override fun deleteProjects(ids: Set<String>) = container.repository.deleteProjects(ids)

    override fun renameProject(id: String, name: String) = container.repository.renameProject(id, name)

    override fun resetProjects(ids: Set<String>) = container.repository.resetProjects(ids)

    // ---------- 设置抽屉 ----------
    val saveOriginal: StateFlow<Boolean> = container.repository.saveOriginal

    /** 新建工程标题联动：新建工程组输入的工程名自动写入该工程水印标题（默认开） */
    val createTitleFromName: StateFlow<Boolean> = container.repository.createTitleFromName

    fun setCreateTitleFromName(value: Boolean) = container.repository.setCreateTitleFromName(value)

    /** 取景器截帧作编辑器预览背景（默认开） */
    val editorBackdrop: StateFlow<Boolean> = container.repository.editorBackdrop

    /** 拍照后确认（默认开）：拍摄后先预览标注，手动保存 */
    val photoConfirm: StateFlow<Boolean> = container.repository.photoConfirm

    fun setPhotoConfirm(value: Boolean) = container.repository.setPhotoConfirm(value)

    /** 界面语言 */
    val languageMode: StateFlow<com.engcamera.i18n.AppLanguageMode> = container.repository.languageMode

    fun setLanguageMode(mode: com.engcamera.i18n.AppLanguageMode) =
        container.repository.setLanguageMode(mode)

    fun setEditorBackdrop(value: Boolean) = container.repository.setEditorBackdrop(value)

    private var shutterPlayer: MediaActionSound? = null

    val shutterSound: StateFlow<Boolean> = container.repository.shutterSound

    fun setShutterSound(value: Boolean) = container.repository.setShutterSound(value)

    val volumeShutter: StateFlow<Boolean> = container.repository.volumeShutter

    fun setVolumeShutter(value: Boolean) = container.repository.setVolumeShutter(value)

    /** 外观主题：跟随系统 / 日间 / 夜间 */
    val themeMode: StateFlow<ThemeMode> = container.repository.themeMode

    fun setThemeMode(mode: ThemeMode) = container.repository.setThemeMode(mode)

    /** 播放系统快门音：仅设置开启时（用户实测 MIUI 上 mustPlayShutterSound 恒真，强制逻辑已移除） */
    private fun playShutterIfNeeded() {
        if (!container.repository.shutterSound.value) return
        runCatching {
            (shutterPlayer ?: MediaActionSound().also { shutterPlayer = it })
                .play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    fun setSaveOriginal(value: Boolean) = container.repository.setSaveOriginal(value)

    fun setFollowGravity(enabled: Boolean) =
        container.repository.update { it.copy(followGravity = enabled) }

    fun setRotationDeg(deg: Float) =
        container.repository.update { it.copy(rotationDeg = deg) }

    // ---------- 添加水印模式（给相册已有图片加水印） ----------

    /** 进入/退出添加水印模式；录像或拍照处理中不允许进入 */
    fun toggleAnnotate() {
        val st = _ui.value
        if (st.annotating) {
            _ui.update { it.copy(annotating = false, annotateQueue = emptyList(), annotateIndex = 0) }
            return
        }
        if (st.recording || st.capturing) return
        _ui.update { it.copy(annotating = true, annotateQueue = emptyList(), annotateIndex = 0) }
    }

    /** 图片选择完成：读宽高比（含 EXIF 旋转换算）入队 */
    fun onAnnotatePicked(uris: List<Uri>) {
        if (!_ui.value.annotating || uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val items = uris.mapNotNull { u ->
                annotateAspect(u)?.let { CameraUiState.AnnotateItem(u, it) }
            }
            if (items.isEmpty()) {
                _ui.update { it.copy(toast = I18n.t("图片读取失败")) }
                return@launch
            }
            _ui.update { it.copy(annotateQueue = items, annotateIndex = 0) }
        }
    }

    /** 确认当前图片：烧录水印保存到相册，多张时切下一张，全部完成后回到空选择状态 */
    fun confirmAnnotate() {
        val st = _ui.value
        if (!st.annotating || st.annotateWorking) return
        val item = st.annotateQueue.getOrNull(st.annotateIndex) ?: return
        _ui.update { it.copy(annotateWorking = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                burnAnnotateAndSave(item.uri)
                val nextIdx = st.annotateIndex + 1
                _ui.update { s ->
                    if (nextIdx >= s.annotateQueue.size) {
                        s.copy(
                            annotateQueue = emptyList(),
                            annotateIndex = 0,
                            annotateWorking = false,
                            toast = I18n.t("已保存 {n} 张").replace("{n}", "$nextIdx"),
                        )
                    } else {
                        s.copy(annotateIndex = nextIdx, annotateWorking = false)
                    }
                }
                _mediaVersion.value += 1
            } catch (t: Throwable) {
                t.printStackTrace()
                _ui.update { it.copy(annotateWorking = false, toast = I18n.t("保存失败：{msg}").replace("{msg}", t.message ?: I18n.t("未知错误"))) }
            }
        }
    }

    /** 读取图片转正后的宽高比（宽/高） */
    private fun annotateAspect(uri: Uri): Float? = runCatching {
        val deg = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return@runCatching null
        if (deg == 90 || deg == 270) opts.outHeight.toFloat() / opts.outWidth
        else opts.outWidth.toFloat() / opts.outHeight
    }.getOrNull()

    /** 给相册图片烧录水印：解码（按上限采样）→ EXIF 转正 → 烧录 → 存 DCIM/Camera */
    private suspend fun burnAnnotateAndSave(uri: Uri) = withContext(Dispatchers.IO) {
        val deg = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error(I18n.t("图片解码失败"))
        val upright = if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
                if (it != src) src.recycle()
            }
        } else {
            src
        }
        val out = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(upright, 0f, 0f, null)
        val effective = effectiveWatermark.value
        WatermarkRenderer.drawOnCanvas(
            canvas, upright.width, upright.height,
            effective, dynamic.value, effective.rotationDeg, 0,
            backdrop = upright,
            gravityAnchor = true, // 待标注图片本身摆正显示，图片系=重力系，锚点直接落位
        )
        upright.recycle()
        MediaSaver.saveBitmap(context, out, treeUri = saveTreeUri())
        out.recycle()
    }

    fun refreshLatest() {
        viewModelScope.launch(Dispatchers.IO) {
            val latest = MediaSaver.queryOwnMedia(context).firstOrNull()
            _ui.update { it.copy(latestMedia = latest) }
        }
    }

    fun startSensors() {
        gravitySensor.start()
        locationSource.start()
    }

    fun stopEverything() {
        // 拖动防抖未落盘的锚点在此兜底写入
        if (anchorPersistJob?.isActive == true) {
            anchorPersistJob = null
            viewModelScope.launch { container.repository.persistStateToDisk() }
        }
        // 暂停时只停录像与传感器；相机用例交给生命周期自动停启，
        // 主动 unbind 会导致恢复时需要整体重绑（白屏诱因之一）
        if (_ui.value.recording) stopRecording()
        gravitySensor.stop()
        locationSource.stop()
    }

    /** 拖动落盘防抖：拖动期间只改内存，停手 400ms 后写一次盘 */
    private var anchorPersistJob: kotlinx.coroutines.Job? = null

    // ---------- 水印拖动（任何方向下均生效；锚点为重力参照系） ----------
    fun onDragWatermark(dx: Float, dy: Float) {
        // 浮层传来的是屏幕系归一化增量，先转到重力系再写锚点（S→G = R(−roll)）：
        // 手指往物理下方拖，锚点就往「下方」方向走，任何持机方向都跟手
        val rad = Math.toRadians(-quantizedRoll.value.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val gx = dx * cos - dy * sin
        val gy = dx * sin + dy * cos
        // 每帧只改内存（update persist=false），停手 400ms 后落盘一次——逐帧写盘是拖动卡顿根源
        container.repository.update(persist = false) { st ->
            st.copy(
                anchorX = (st.anchorX + gx).coerceIn(0f, 1f),
                anchorY = (st.anchorY + gy).coerceIn(0f, 1f),
            )
        }
        anchorPersistJob?.cancel()
        anchorPersistJob = viewModelScope.launch {
            delay(400)
            container.repository.persistStateToDisk()
        }
    }

    // ---------- 闪光灯 ----------
    fun cycleFlash() {
        val next = (_ui.value.flashIndex + 1) % 3
        _ui.update { it.copy(flashIndex = next) }
        setFlashMode(next)
    }

    private fun setFlashMode(index: Int) {
        val mode = when (index) {
            1 -> androidx.camera.core.ImageCapture.FLASH_MODE_ON
            2 -> androidx.camera.core.ImageCapture.FLASH_MODE_AUTO
            else -> androidx.camera.core.ImageCapture.FLASH_MODE_OFF
        }
        engine?.setFlashMode(mode)
    }

    // ---------- 前后摄 ----------
    fun flipCamera() {
        val e = engine ?: return
        e.frontCamera = !e.frontCamera
        e.selectLens(null)
        _ui.update { it.copy(frontCamera = e.frontCamera) }
        rebind?.invoke()
    }

    // ---------- 焦段 ----------
    fun selectLens(index: Int) {
        val e = engine ?: return
        if (index !in e.backLenses.indices) return
        e.selectLens(e.backLenses[index].cameraId)
        _ui.update { it.copy(lensIndex = index) }
        rebind?.invoke()
    }

    // ---------- 模式（切换会重绑用例） ----------
    fun setVideoMode(video: Boolean) {
        val st = _ui.value
        if (st.recording) return
        // 点拍照/录像按钮同时退出添加水印模式（用户用它们切回相机）
        if (st.videoMode == video && !st.annotating) return
        _ui.update {
            it.copy(
                videoMode = video,
                annotating = false,
                annotateQueue = emptyList(),
                annotateIndex = 0,
            )
        }
        rebind?.invoke()
    }

    // ---------- 扫码气泡（仅拍照模式） ----------
    private var lastBubbleContent: String? = null
    private var lastBubbleAt = 0L
    private var bubbleHideJob: Job? = null

    private fun onBarcode(content: String) {
        val now = SystemClock.elapsedRealtime()
        if (content == lastBubbleContent && now - lastBubbleAt < COOLDOWN_MS) return
        lastBubbleContent = content
        lastBubbleAt = now
        _ui.update { it.copy(bubble = content) }
        bubbleHideJob?.cancel()
        bubbleHideJob = viewModelScope.launch {
            delay(BUBBLE_AUTO_HIDE_MS)
            if (_ui.value.bubble == content) _ui.update { it.copy(bubble = null) }
        }
    }

    fun copyBubble() {
        val text = _ui.value.bubble ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("qrcode", text))
        _ui.update { it.copy(bubble = null, toast = I18n.t("已复制")) }
    }

    /**
     * 持机方向 → 烧录坐标系换算角（屏幕系→成片系；与 targetRotation 互补，由四方向实测推导）。
     * 锚点改重力参照系后不再用于旋转锚点，只用于水印块旋转补偿（rotationDeg + 角 ≡ 摆正）。
     */
    private fun frameRotationFor(deg: Int): Int = when (deg) {
        90 -> 270
        -90 -> 90
        180, -180 -> 180
        else -> 0
    }

    // ---------- 拍照 ----------
    fun takePhoto() {
        val st = _ui.value
        if (st.capturing || st.recording) return
        val e = engine
        if (e == null || st.videoMode) {
            _ui.update { it.copy(toast = if (st.videoMode) I18n.t("录像模式下请录像或切回拍照") else I18n.t("相机未就绪")) }
            return
        }
        _ui.update { it.copy(capturing = true) }
        playShutterIfNeeded()
        val frameRotation = frameRotationFor(quantizedRoll.value)
        val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        e.takePhoto(file) { ok ->
            if (!ok) {
                file.delete()
                _ui.update { it.copy(capturing = false, toast = I18n.t("拍照失败")) }
                return@takePhoto
            }
            if (container.repository.photoConfirm.value) {
                // 拍照后确认（用户拍板 2026-10-16）：不闪黑、不落盘——解码转正进预览框，
                // 手动点保存（快门变下载图标）才写相册；X 丢弃
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        val upright = decodeUpright(file)
                        confirmTempFile = file
                        confirmFrameRotation = frameRotation
                        // 按下快门定格：水印状态/时间/位置冻结（用户拍板 2026-10-16），
                        // 工具跨照片记忆不重置
                        _ui.update {
                            it.copy(
                                confirming = true,
                                confirmBitmap = upright,
                                annotations = emptyList(),
                                textItems = emptyList(),
                                annotationColor = 0xFFFF5252L,
                                confirmWatermark = effectiveWatermark.value,
                                confirmDynamic = dynamic.value,
                                confirmRoll = quantizedRoll.value,
                                confirmFrameRotation = frameRotation,
                                capturing = false,
                            )
                        }
                    }.onFailure { t ->
                        t.printStackTrace()
                        file.delete()
                        _ui.update { it.copy(capturing = false, toast = I18n.t("照片解码失败")) }
                    }
                }
                return@takePhoto
            }
            // 文件已写入：递增闪黑计数，预览层播一次机械快门式反馈（纯 UI 动画不阻塞保存）
            _ui.update { it.copy(shutterTick = it.shutterTick + 1) }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    burnAndSave(file, frameRotation)
                    incrementCount()
                    _ui.update { it.copy(capturing = false) }
                    _mediaVersion.value += 1
                } catch (t: Throwable) {
                    t.printStackTrace()
                    file.delete()
                    _ui.update { it.copy(capturing = false, toast = I18n.t("保存失败：{msg}").replace("{msg}", t.message ?: "")) }
                }
            }
        }
    }

    /** 解码临时照片并按 EXIF 转正（上限采样防 OOM），burnAndSave 与确认态共用 */
    private fun decodeUpright(tempFile: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeFile(tempFile.absolutePath, opts)
            ?: error(I18n.t("照片解码失败"))
        val deg = MediaSaver.exifDegrees(tempFile.absolutePath)
        return if (deg != 0) {
            val m = Matrix().apply { postRotate(deg.toFloat()) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
                if (it != src) src.recycle()
            }
        } else {
            src
        }
    }

    // ---------- 拍照后确认：标注与保存 ----------

    /** 添加一笔标注（点位为照片显示区归一化坐标，UI 层手势结束时调用） */
    fun addAnnotation(
        tool: Int,
        color: Long,
        points: List<androidx.compose.ui.geometry.Offset>,
        strokeScale: Float = 1f,
    ) {
        if (points.isEmpty()) return
        _ui.update {
            it.copy(annotations = it.annotations + CameraUiState.PhotoAnnotation(tool, color, points, strokeScale))
        }
    }

    // ---------- 文字标注（v195） ----------
    private fun nextTextId() = System.currentTimeMillis().also { textIdGen = it }

    fun addTextItem(text: String, x: Float, y: Float, color: Long) {
        if (text.isBlank()) return
        val id = textIdGen++
        _ui.update {
            it.copy(
                textItems = it.textItems + CameraUiState.TextItem(id, text, x, y, 1f, 0f, color),
                selectedTextId = id, // 新增即选中：随后改色立即生效（用户拍板 2026-10-16）
            )
        }
    }

    /** 选中文字元素（点按/拖动触发）：同步把当前颜色设为该元素颜色（取色圆点跟随） */
    fun selectTextItem(id: Long) = _ui.update { st ->
        val item = st.textItems.firstOrNull { it.id == id } ?: return@update st
        st.copy(selectedTextId = id, annotationColor = item.color)
    }

    fun updateTextItem(item: CameraUiState.TextItem) = _ui.update {
        it.copy(textItems = it.textItems.map { t -> if (t.id == item.id) item else t })
    }

    fun removeTextItem(id: Long) = _ui.update {
        it.copy(textItems = it.textItems.filterNot { t -> t.id == id })
    }

    /** 撤销最近一笔标注（逐笔，用户拍板 2026-10-16） */
    fun annotationUndo() = _ui.update { if (it.annotations.isEmpty()) it else it.copy(annotations = it.annotations.dropLast(1)) }

    fun setAnnotationTool(tool: Int) {
        _ui.update { it.copy(annotationTool = tool) }
        container.repository.setAnnotationTool(tool)
    }

    fun setAnnotationColor(color: Long) = _ui.update {
        // 有选中的文字元素：立即改它的颜色；同时更新默认色（新文字用）
        val sel = it.selectedTextId
        it.copy(
            annotationColor = color,
            textItems = if (sel != null) {
                it.textItems.map { t -> if (t.id == sel) t.copy(color = color) else t }
            } else {
                it.textItems
            },
        )
    }

    /** 丢弃确认态照片：删临时文件回拍摄模式（不计数） */
    fun confirmDiscard() {
        val bmp = _ui.value.confirmBitmap
        _ui.update {
            it.copy(confirming = false, confirmBitmap = null, annotations = emptyList(), textItems = emptyList())
        }
        bmp?.recycle()
        confirmTempFile?.delete()
        confirmTempFile = null
    }

    /** 确认保存：标注画上照片 → 标注+水印一张、水印一张、（可选）原图一张（用户拍板语义） */
    fun confirmSave() {
        val st = _ui.value
        if (!st.confirming || st.capturing) return
        val photo = st.confirmBitmap ?: return
        val file = confirmTempFile ?: return
        val frameRotation = confirmFrameRotation
        confirmTempFile = null
        _ui.update {
            it.copy(
                confirming = false, confirmBitmap = null, annotations = emptyList(),
                textItems = emptyList(), confirmWatermark = null, confirmDynamic = null,
                capturing = true,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            var annotated: Bitmap = photo
            try {
                annotated = drawAnnotations(photo, st.annotations, st.textItems)
                saveConfirmed(
                    file, photo, annotated, frameRotation,
                    st.confirmWatermark ?: effectiveWatermark.value,
                    st.confirmDynamic ?: dynamic.value,
                    st.annotations, st.textItems,
                )
                incrementCount()
                _ui.update { it.copy(capturing = false) }
                _mediaVersion.value += 1
            } catch (t: Throwable) {
                t.printStackTrace()
                file.delete()
                _ui.update { it.copy(capturing = false, toast = I18n.t("保存失败：{msg}").replace("{msg}", t.message ?: "")) }
            } finally {
                if (annotated !== photo) annotated.recycle()
                photo.recycle()
            }
        }
    }

    /** 把标注（笔画+文字元素）按全分辨率绘制到照片副本上（无标注返回原件不拷贝） */
    private fun drawAnnotations(
        photo: Bitmap,
        annotations: List<CameraUiState.PhotoAnnotation>,
        textItems: List<CameraUiState.TextItem> = emptyList(),
    ): Bitmap {
        if (annotations.isEmpty() && textItems.isEmpty()) return photo
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val strokeW = w * 0.008f
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeW
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        annotations.forEach { ann ->
            // 每笔自己的线宽：放大时画的更细（strokeScale=落笔时缩放），箭头头随线宽
            val sw = strokeW / ann.strokeScale.coerceAtLeast(1f)
            paint.strokeWidth = sw
            paint.color = ann.color.toInt()
            val pts = ann.points.map { androidx.compose.ui.geometry.Offset(it.x * w, it.y * h) }
            when (ann.tool) {
                0 -> { // 箭头：起点→终点直线 + 终点箭头两翼
                    if (pts.size < 2) return@forEach
                    val a = pts.first()
                    val b = pts.last()
                    canvas.drawLine(a.x, a.y, b.x, b.y, paint)
                    val angle = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                    val head = sw * 4f
                    for (da in listOf(Math.PI / 6, -Math.PI / 6)) {
                        val ax = (b.x - head * kotlin.math.cos(angle + da)).toFloat()
                        val ay = (b.y - head * kotlin.math.sin(angle + da)).toFloat()
                        canvas.drawLine(b.x, b.y, ax, ay, paint)
                    }
                }
                1 -> { // 矩形框
                    if (pts.size < 2) return@forEach
                    val a = pts.first()
                    val b = pts.last()
                    canvas.drawRect(
                        minOf(a.x, b.x), minOf(a.y, b.y),
                        maxOf(a.x, b.x), maxOf(a.y, b.y),
                        paint,
                    )
                }
                else -> { // 笔刷：路径连线
                    if (pts.size < 2) return@forEach
                    val path = android.graphics.Path()
                    path.moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { path.lineTo(it.x, it.y) }
                    canvas.drawPath(path, paint)
                }
            }
        }
        // 文字元素：颜色文字（白细框仅 UI 展示不烧录），锚点=左上角、绕锚点旋转缩放
        // ——与预览的 transformOrigin(0,0) 完全同构（v200 修位置偏移）；多行逐行绘制
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        textItems.forEach { t ->
            val px = t.x * w
            val py = t.y * h
            val textSize = w * 0.05f * t.scale
            textPaint.textSize = textSize
            textPaint.color = t.color.toInt()
            textPaint.isFakeBoldText = true
            // 文字阴影（与预览一致，v199）
            textPaint.setShadowLayer(w * 0.012f, w * 0.004f, w * 0.004f, 0x66000000)
            canvas.save()
            canvas.rotate(t.rotation, px, py)
            val fm = textPaint.fontMetrics
            val lineH = textSize * 1.25f
            t.text.split('\n').forEachIndexed { i, line ->
                val baseline = py + i * lineH - fm.ascent
                canvas.drawText(line, px, baseline, textPaint)
            }
            canvas.restore()
        }
        return out
    }

    /**
     * 确认保存落盘（用户拍板语义）：标注+水印一张、水印一张；「保存原图」开着再加原图
     * 共 2~3 张。时间序：原图 ≤ 水印 ≤ 标注+水印（标注版最新=相册最前）。
     */
    private suspend fun saveConfirmed(
        tempFile: File,
        photo: Bitmap,
        annotated: Bitmap,
        frameRotation: Int,
        wmState: WatermarkState,
        dyn: DynamicValues,
        anns: List<CameraUiState.PhotoAnnotation>,
        texts: List<CameraUiState.TextItem>,
    ) {
        val noWatermark = wmState.fields.none { it.enabled }
        val takenAt = System.currentTimeMillis()
        fun burn(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(src, 0f, 0f, null)
            WatermarkRenderer.drawOnCanvas(
                canvas, src.width, src.height,
                wmState, dyn, wmState.rotationDeg, frameRotation,
                backdrop = src,
                gravityAnchor = true,
            )
            return out
        }
        val saveOriginal = container.repository.saveOriginal.value
        if (saveOriginal) {
            runCatching { MediaSaver.saveBitmap(context, photo, takenAt, treeUri = saveTreeUri()) }
        }
        if (noWatermark) {
            // 水印全关：成片=画面本身；存标注版一张（+可选原图）
            MediaSaver.saveBitmap(context, annotated, takenAt + 1000, treeUri = saveTreeUri())
            tempFile.delete()
            return
        }
        val wmOnPhoto = burn(photo)
        MediaSaver.saveBitmap(context, wmOnPhoto, takenAt + 1000, treeUri = saveTreeUri())
        wmOnPhoto.recycle()
        // 标注+水印版：先烧水印，再在**水印之上**画标注（与确认预览层级一致，用户拍板 2026-10-16）
        if (anns.isNotEmpty() || texts.isNotEmpty()) {
            val wmBase = burn(photo)
            val overlay = drawAnnotations(wmBase, anns, texts)
            MediaSaver.saveBitmap(context, overlay, takenAt + 2000, treeUri = saveTreeUri())
            overlay.recycle()
            if (wmBase !== overlay) wmBase.recycle()
        }
        tempFile.delete()
    }

    /**
     * 拍照合成流程（计划书 5.1）：采样解码 -> EXIF 转正 -> 烧录水印 -> 存 MediaStore -> 删临时。
     * frameRotation 为屏幕系→成片系的换算角（只由持机方向决定，不能用 EXIF——EXIF 含传感器基准角）。
     */
    private suspend fun burnAndSave(tempFile: File, frameRotation: Int) {
        val upright = decodeUpright(tempFile)
        // 水印全关时成片=原图：只保存一张（即使"保存原图"开着也不重复存两张）
        val noWatermark = effectiveWatermark.value.fields.none { it.enabled }
        if (noWatermark) {
            MediaSaver.saveBitmap(context, upright, System.currentTimeMillis(), treeUri = saveTreeUri())
            upright.recycle()
            tempFile.delete()
            return
        }
        // 水印版先存（占主文件名），开启"保存原图"时原图后存（同秒插入自动得 (1) 后缀），
        // 保证系统相册与 app 相册里水印版都排在原图前（用户确认 2026-10-11）
        val out = Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(upright, 0f, 0f, null)
        val effective = effectiveWatermark.value
        WatermarkRenderer.drawOnCanvas(
            canvas, upright.width, upright.height,
            effective, dynamic.value, effective.rotationDeg, frameRotation,
            backdrop = upright,
            // 锚点为重力参照系：成片按方向元数据显示出来即重力系，直接落位不旋转
            // （九宫格「下方」=成片物理下方，与持机方向无关）；水印块旋转仍按 frameRotation 补偿
            gravityAnchor = true,
        )
        // 双张保存（用户确认 2026-10-11 最终语义：水印图=最新=相册第一张，原图第二张）：
        // ① 原图先插入占主文件名（系统相册实测把 (1) 排最前，维持该行为）
        // ② 水印版 DATE_TAKEN 晚 1 秒（MIUI 未覆盖时间时按时间排也是水印在前）
        val takenAt = System.currentTimeMillis()
        if (container.repository.saveOriginal.value) {
            runCatching { MediaSaver.saveBitmap(context, upright, takenAt, treeUri = saveTreeUri()) }
        }
        MediaSaver.saveBitmap(context, out, takenAt + 1000, treeUri = saveTreeUri())
        out.recycle()
        upright.recycle()
        tempFile.delete()
    }

    /** 拍照保存成功后计数 +1（本次成片显示拍照时的值，下一次拍摄递增） */
    private fun incrementCount() = container.repository.update { st ->
        st.copy(
            fields = st.fields.map { f ->
                if (f.type == FieldType.COUNT && f.enabled) {
                    f.copy(value = ((f.value.filter { it.isDigit() }.toIntOrNull() ?: 0) + 1).toString())
                } else {
                    f
                }
            },
        )
    }

    // ---------- 录像 ----------
    /**
     * 开始录像。拍照模式下长按快捷录像会先切到录像模式并重绑，再启动录制。
     */
    fun startRecording(withAudio: Boolean, quick: Boolean) {
        val st = _ui.value
        if (st.recording || st.capturing) return
        val e = engine
        if (e == null) {
            _ui.update { it.copy(toast = I18n.t("相机未就绪")) }
            return
        }
        if (quick && !st.videoMode) {
            // 快捷录像：先切模式重绑，再开始
            _ui.update { it.copy(videoMode = true) }
            rebind?.invoke()
        }
        val file = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
        pendingVideoFile = file
        val startAt = System.currentTimeMillis()
        val timeline = mutableListOf(startAt to quantizedRoll.value)
        videoWatermarkSnapshot = VideoSnapshot(
            effectiveWatermark.value,
            dynamic.value,
            frameRotationFor(quantizedRoll.value),
            startAt,
            timeline,
        )
        // 录像期间记录重力方向变化（量化跳变才追加），供烧录时与预览同步
        rollTimelineJob?.cancel()
        rollTimelineJob = viewModelScope.launch {
            quantizedRoll.collect { roll ->
                if (roll != timeline.last().second) timeline.add(System.currentTimeMillis() to roll)
            }
        }
        _ui.update { it.copy(capturing = true) }
        val rec = e.startRecording(file, withAudio) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _ui.update { it.copy(recording = true) }
                    startTimer()
                }

                is VideoRecordEvent.Finalize -> {
                    stopTimer()
                    rollTimelineJob?.cancel()
                    _ui.update { it.copy(recording = false, capturing = false) }
                    val f = pendingVideoFile
                    pendingVideoFile = null
                    activeRecording = null
                    viewModelScope.launch(Dispatchers.IO) {
                        if (event.hasError()) {
                            f?.delete()
                            _ui.update { it.copy(toast = I18n.t("录像失败（错误码 {code}）").replace("{code}", "${event.error}")) }
                        } else if (f != null) {
                            saveVideoWithWatermark(f)
                        }
                    }
                }

                else -> Unit
            }
        }
        if (rec == null) {
            pendingVideoFile = null
            _ui.update { it.copy(capturing = false, toast = I18n.t("录像启动失败")) }
        } else {
            activeRecording = rec
        }
    }

    fun stopRecording() {
        activeRecording?.stop()
    }

    /**
     * 录像落盘：优先离线转码烧入水印（与预览同一渲染引擎），合成失败降级保存无水印原片。
     */
    private suspend fun saveVideoWithWatermark(source: File) {
        val snapshot = videoWatermarkSnapshot
        videoWatermarkSnapshot = null
        val hasWatermark = snapshot?.state?.fields?.any { it.enabled } == true
        var saved: android.net.Uri? = null
        var watermarked = false

        if (hasWatermark && snapshot != null) {
            _ui.update { it.copy(toast = I18n.t("正在为视频合成水印…")) }
            val burned = runCatching {
                VideoWatermarker.burn(
                    context, source, snapshot.state, snapshot.dynamic,
                    snapshot.frameRotation, snapshot.startEpochMs, snapshot.rollTimeline,
                )
            }.onFailure { t -> Log.w("CameraVM", "watermark burn failed", t) }
                .getOrNull()
            if (burned != null) {
                val takenAt = System.currentTimeMillis()
                // 原图先存（主文件名）；水印版 DATE_TAKEN 晚 1 秒（水印=最新，与照片语义一致）
                var originalSaved = false
                if (container.repository.saveOriginal.value) {
                    originalSaved = runCatching {
                        MediaSaver.saveVideoFile(context, source, takenAt, treeUri = saveTreeUri())
                    }.isSuccess
                }
                saved = runCatching { MediaSaver.saveVideoFile(context, burned, takenAt + 1000, treeUri = saveTreeUri()) }.getOrNull()
                watermarked = saved != null
                if (saved == null && originalSaved) {
                    _ui.update { it.copy(toast = I18n.t("水印视频保存失败，已保留原片")) }
                }
            }
            if (saved == null) {
                _ui.update { it.copy(toast = I18n.t("水印合成失败，已保存无水印视频")) }
            }
        }
        if (saved == null) {
            saved = runCatching { MediaSaver.saveVideoFile(context, source, treeUri = saveTreeUri()) }.getOrNull()
        }

        if (saved != null) {
            _mediaVersion.value += 1
            if (watermarked) {
                _ui.update { it.copy(toast = I18n.t("水印视频已保存")) }
            }
        } else {
            _ui.update { it.copy(toast = I18n.t("保存失败")) }
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        _ui.update { it.copy(recordingSeconds = 0) }
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _ui.update { it.copy(recordingSeconds = it.recordingSeconds + 1) }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun consumeToast() {
        _ui.update { it.copy(toast = null) }
    }

    fun showToast(message: String?) {
        _ui.update { it.copy(toast = message) }
    }

    override fun onCleared() {
        if (anchorPersistJob?.isActive == true) {
            runCatching {
                kotlinx.coroutines.runBlocking { container.repository.persistStateToDisk() }
            }
        }
        analyzer.release()
        runCatching { shutterPlayer?.release() }
        shutterPlayer = null
        super.onCleared()
    }

    private companion object {
        /** 限制成片最大边长，防 OOM（计划书 9） */
        const val MAX_SIDE = 4096
        const val COOLDOWN_MS = 3_000L
        const val BUBBLE_AUTO_HIDE_MS = 8_000L
    }
}
