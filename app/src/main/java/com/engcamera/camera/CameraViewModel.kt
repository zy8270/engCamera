package com.engcamera.camera

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engcamera.EngCameraApp
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val lensLabels: List<String> = emptyList(),
    val lensIndex: Int = 0,
    val latestMedia: MediaSaver.MediaEntry? = null,
    /** 相机 UI 控件旋转角（重力 90° 量化）：0 / 90 / -90 / 180 */
    val uiRotation: Int = 0,
    /** 可选画质列表与当前选中 */
    val videoQualities: List<CameraEngine.VideoQuality> = emptyList(),
    val videoQualityIndex: Int = 0,
    /** 画质选择菜单展开 */
    val showQualityMenu: Boolean = false,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as EngCameraApp).container
    private val context = app

    val watermark: StateFlow<WatermarkState> = container.repository.state

    private val _dynamic = MutableStateFlow(DynamicValues())
    val dynamic: StateFlow<DynamicValues> = _dynamic.asStateFlow()

    val gravitySensor = GravitySensor(app)
    val locationSource = container.locationSource

    private val quantizedRoll = MutableStateFlow(0)
    private var lastRoll: Int? = null

    /**
     * 实际生效的水印状态（预览与成片共用）：
     * 重力跟随开启时旋转角取量化重力角；锚点即"当前预览画面内的块中心"，
     * 拖动在任何方向下都直接生效，方向切换时由 VM 重置到预览画面右下角。
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
    private var pendingVideoFile: File? = null
    private var timerJob: Job? = null

    /** 录像开始瞬间的水印快照 + 成片坐标系换算角（合成时使用，所见即所得） */
    private var videoWatermarkSnapshot: Triple<WatermarkState, DynamicValues, Int>? = null

    init {
        // 动态时间/位置每秒刷新（位置用于【位置】标签值为空时的拍照瞬间自动填充）
        viewModelScope.launch {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            while (isActive) {
                _dynamic.value = _dynamic.value.copy(
                    time = fmt.format(Date()),
                    location = locationSource.text.value,
                )
                delay(1000)
            }
        }
        gravitySensor.onQuantizedRoll = { deg ->
            val prev = lastRoll
            lastRoll = deg
            quantizedRoll.value = deg
            // 成片方向跟随持机（对齐系统相机）：逆时针横屏(+90)→ROTATION_90，
            // 顺时针横屏(-90)→ROTATION_270，倒置(180)→ROTATION_180（用户四方向实测推导）
            engine?.setTargetRotation(
                when (deg) {
                    90 -> android.view.Surface.ROTATION_90
                    -90 -> android.view.Surface.ROTATION_270
                    180, -180 -> android.view.Surface.ROTATION_180
                    else -> android.view.Surface.ROTATION_0
                },
            )
            // 方向切换时锚点重置（屏幕系，成片恒为视觉左下角）：
            // 正立→左下；倒置(180)→屏幕右上；逆时针(+90)→屏幕左上；顺时针(-90)→屏幕右下
            if (prev != null && prev != deg) {
                val (ax, ay) = when (deg) {
                    90 -> 0f to 0f
                    -90 -> 1f to 1f
                    180, -180 -> 1f to 0f
                    else -> 0f to 1f
                }
                container.repository.update { it.copy(anchorX = ax, anchorY = ay) }
            }
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
        val currentIdx = qualities.indexOfFirst { it.label == e.videoQuality.label }.coerceAtLeast(0)
        _ui.update { s ->
            s.copy(
                lensLabels = e.backLenses.map { it.label },
                lensIndex = e.backLenses.indexOfFirst { it.cameraId == e.selectedLensId }.coerceAtLeast(0),
                frontCamera = e.frontCamera,
                videoQualities = qualities,
                videoQualityIndex = currentIdx,
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

    fun toggleQualityMenu() {
        _ui.update { it.copy(showQualityMenu = !it.showQualityMenu) }
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
        // 暂停时只停录像与传感器；相机用例交给生命周期自动停启，
        // 主动 unbind 会导致恢复时需要整体重绑（白屏诱因之一）
        if (_ui.value.recording) stopRecording()
        gravitySensor.stop()
        locationSource.stop()
    }

    // ---------- 水印拖动（任何方向下均生效，锚点为当前画面内的块中心） ----------
    fun onDragWatermark(dx: Float, dy: Float) {
        android.util.Log.d("CameraVM", "onDragWatermark dx=$dx dy=$dy")
        container.repository.update { st ->
            st.copy(
                anchorX = (st.anchorX + dx).coerceIn(0f, 1f),
                anchorY = (st.anchorY + dy).coerceIn(0f, 1f),
            )
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
        if (_ui.value.recording || _ui.value.videoMode == video) return
        _ui.update { it.copy(videoMode = video) }
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
        _ui.update { it.copy(bubble = null, toast = "已复制") }
    }

    /** 持机方向 → 烧录坐标系换算角（屏幕系→成片系；与 targetRotation 互补，由四方向实测推导） */
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
            _ui.update { it.copy(toast = if (st.videoMode) "录像模式下请录像或切回拍照" else "相机未就绪") }
            return
        }
        _ui.update { it.copy(capturing = true) }
        val frameRotation = frameRotationFor(quantizedRoll.value)
        val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        e.takePhoto(file) { ok ->
            if (!ok) {
                file.delete()
                _ui.update { it.copy(capturing = false, toast = "拍照失败") }
                return@takePhoto
            }
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    burnAndSave(file, frameRotation)
                    incrementCount()
                    _ui.update { it.copy(capturing = false) }
                    _mediaVersion.value += 1
                } catch (t: Throwable) {
                    t.printStackTrace()
                    file.delete()
                    _ui.update { it.copy(capturing = false, toast = "保存失败：${t.message}") }
                }
            }
        }
    }

    /**
     * 拍照合成流程（计划书 5.1）：采样解码 -> EXIF 转正 -> 烧录水印 -> 存 MediaStore -> 删临时。
     * frameRotation 为屏幕系→成片系的换算角（只由持机方向决定，不能用 EXIF——EXIF 含传感器基准角）。
     */
    private suspend fun burnAndSave(tempFile: File, frameRotation: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = BitmapFactory.decodeFile(tempFile.absolutePath, opts)
            ?: error("照片解码失败")
        val deg = MediaSaver.exifDegrees(tempFile.absolutePath)
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
            effective, dynamic.value, effective.rotationDeg, frameRotation,
        )
        upright.recycle()
        MediaSaver.saveBitmap(context, out)
        out.recycle()
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
            _ui.update { it.copy(toast = "相机未就绪") }
            return
        }
        if (quick && !st.videoMode) {
            // 快捷录像：先切模式重绑，再开始
            _ui.update { it.copy(videoMode = true) }
            rebind?.invoke()
        }
        val file = File(context.cacheDir, "video_${System.currentTimeMillis()}.mp4")
        pendingVideoFile = file
        videoWatermarkSnapshot = Triple(
            effectiveWatermark.value,
            dynamic.value,
            frameRotationFor(quantizedRoll.value),
        )
        _ui.update { it.copy(capturing = true) }
        val rec = e.startRecording(file, withAudio) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _ui.update { it.copy(recording = true) }
                    startTimer()
                }

                is VideoRecordEvent.Finalize -> {
                    stopTimer()
                    Log.d("CameraVM", "Finalize: hasError=${event.hasError()}, error=${event.error}")
                    _ui.update { it.copy(recording = false, capturing = false) }
                    val f = pendingVideoFile
                    pendingVideoFile = null
                    activeRecording = null
                    viewModelScope.launch(Dispatchers.IO) {
                        if (event.hasError()) {
                            f?.delete()
                            _ui.update { it.copy(toast = "录像失败（错误码 ${event.error}）") }
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
            _ui.update { it.copy(capturing = false, toast = "录像启动失败") }
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
        val hasWatermark = snapshot?.first?.fields?.any { it.enabled } == true
        var saved: android.net.Uri? = null
        var watermarked = false
        Log.d("CameraVM", "saveVideo: hasWatermark=$hasWatermark, size=${source.length()}")

        if (hasWatermark && snapshot != null) {
            _ui.update { it.copy(toast = "正在为视频合成水印…") }
            val burned = runCatching {
                VideoWatermarker.burn(context, source, snapshot.first, snapshot.second, snapshot.third)
            }.onFailure { t -> Log.w("CameraVM", "watermark burn failed", t) }
                .getOrNull()
            if (burned != null) {
                Log.d("CameraVM", "burn ok: ${burned.length()} bytes")
                saved = runCatching { MediaSaver.saveVideoFile(context, burned) }.getOrNull()
                watermarked = saved != null
            }
            if (saved == null) {
                _ui.update { it.copy(toast = "水印合成失败，已保存无水印视频") }
            }
        }
        if (saved == null) {
            saved = runCatching { MediaSaver.saveVideoFile(context, source) }.getOrNull()
        }

        if (saved != null) {
            _mediaVersion.value += 1
            if (watermarked) {
                _ui.update { it.copy(toast = "水印视频已保存") }
            }
        } else {
            _ui.update { it.copy(toast = "保存失败") }
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
        analyzer.release()
        super.onCleared()
    }

    private companion object {
        /** 限制成片最大边长，防 OOM（计划书 9） */
        const val MAX_SIDE = 4096
        const val COOLDOWN_MS = 3_000L
        const val BUBBLE_AUTO_HIDE_MS = 8_000L
    }
}
