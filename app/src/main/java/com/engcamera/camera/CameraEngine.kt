package com.engcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.util.Log
import android.util.Rational
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.CameraInfo
import androidx.camera.core.ZoomState
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 相机引擎：预览/拍照/录像/扫码常绑 + ViewPort 统一比例，
 * 录像与预览比例一致；全量绑定失败时逐级回退。
 * 拍照水印走 Bitmap 烧录；录像水印由 VideoWatermarker 录后转码烧录。
 */
class CameraEngine(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    /** 后置镜头（焦段切换用） */
    data class BackLens(val cameraId: String, val label: String)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysisExecutor: ExecutorService? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var lastBindKey: String? = null

    /** 点击对焦测光用：绑定时的 PreviewView 坐标换算工厂（屏幕点→传感器测光区域） */
    private var meteringFactory: MeteringPointFactory? = null

    /** 成片目标旋转（跟随持机方向；倒置时 180，照片/视频按听筒朝下生成） */
    @Volatile
    var targetRotation: Int = android.view.Surface.ROTATION_0
        private set

    /** 持机方向变化时更新拍照/录像的目标旋转 */
    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    /** 画质选项：label=实际分辨率，spec=录制规格简称（0.6.0 弃用"高清/超清"模糊描述） */
    data class VideoQuality(val label: String, val spec: String, val quality: Int)

    /** 当前选中的录像画质（CameraX Quality 常量） */
    @Volatile
    var videoQuality: VideoQuality = VideoQuality("1280×720", "720p", 1) // 1=HD
        set(value) {
            field = value
        }

    /** 照片画质选项：label=实际成片尺寸（传感器系 4:3），spec=像素量级；width/height 供分辨率策略 */
    data class PhotoQuality(val label: String, val spec: String, val width: Int, val height: Int)

    /** 当前选中的照片画质；null = 不限制（引擎挑 4:3 最大） */
    @Volatile
    var photoQuality: PhotoQuality? = null

    /**
     * 可选画质列表：按当前镜头真实能力生成（档位来自 CameraX，尺寸来自编码器支持列表，
     * 再按 ViewPort 裁切算出成片实际尺寸），全失败退静态四档。
     */
    fun getSupportedVideoQualities(): List<VideoQuality> {
        val info = selectedLensCameraInfo()
        val supported = info?.let {
            runCatching { QualitySelector.getSupportedQualities(it).toSet() }.getOrNull()
        }
        // CamcorderProfile 只有 int cameraId 的 API（String 形式无），转数字；非常规 id 兜底 0 号机
        val legacyId = info
            ?.let { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() }
            ?.toIntOrNull() ?: 0
        // 编码器真实支持的输入尺寸（传感器横向系）；CameraX 各档按流高度就近选档
        val encoderSizes = info?.let {
            runCatching {
                Camera2CameraInfo.from(it)
                    .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(MediaRecorder::class.java)
                    ?.toList()
            }.getOrNull()
        } ?: emptyList()
        val list = mutableListOf<VideoQuality>()
        for (tier in qualityTiers) {
            if (supported != null && tier.cameraX !in supported) continue
            // 流尺寸：编码器同高最大 → 高度±16 兜底（如 1088）→ CamcorderProfile 申报值 → 标准目标值
            val stream = encoderSizes.filter { it.height == tier.streamHeight }.maxByOrNull { it.width * it.height }
                ?: encoderSizes.filter { abs(it.height - tier.streamHeight) <= 16 }.maxByOrNull { it.width * it.height }
                ?: profileSpec(legacyId, tier.profileQuality)?.first
                ?: Size(tier.fallbackW, tier.fallbackH)
            val fps = profileSpec(legacyId, tier.profileQuality)?.second
            // ViewPort 把所有流裁到预览比例（3:4 竖屏 = 传感器系 4:3），菜单必须标裁后成片尺寸
            // （真机实测：SD 档 720×480 录出 640×480，即裁切所致）
            val out = cropToViewport(stream)
            list += VideoQuality(
                label = "${out.width}×${out.height}",
                spec = tier.specName + (fps?.let { " · ${it}fps" } ?: ""),
                quality = tier.id,
            )
        }
        return list.ifEmpty { defaultQualities() }
    }

    /** CamcorderProfile 设备申报规格 → (尺寸, 帧率)；不可得返回 null */
    private fun profileSpec(legacyId: Int, quality: Int): Pair<Size, Int>? {
        if (!CamcorderProfile.hasProfile(legacyId, quality)) return null
        val p = runCatching { CamcorderProfile.get(legacyId, quality) }.getOrNull() ?: return null
        return Size(p.videoFrameWidth, p.videoFrameHeight) to p.videoFrameRate
    }

    /** 裁到传感器系 4:3（预览恒 3:4 竖屏）内的成片尺寸；宽高各取裁后偶数值 */
    private fun cropToViewport(size: Size): Size {
        val target = 4f / 3f
        val w = size.width
        val h = size.height
        return if (w.toFloat() / h > target) {
            Size((h * target).roundToInt().coerceAtMost(w), h)
        } else {
            Size(w, (w / target).roundToInt().coerceAtMost(h))
        }
    }

    private fun defaultQualities(): List<VideoQuality> = listOf(
        VideoQuality("3840×2160", "2160p", 4),
        VideoQuality("1920×1080", "1080p", 3),
        VideoQuality("1280×720", "720p", 1),
        VideoQuality("720×480", "480p", 2),
    )

    /**
     * 可选照片画质：按当前镜头真实能力生成——JPEG 输出尺寸里取 4:3 系（拍照固定 4:3，
     * 旋转后即预览 3:4），按像素降序取代表档：相邻两档短边比 ≥1.4（约 2 倍像素），
     * 至多 4 档；短边 <480 的玩具尺寸不收。
     */
    fun getSupportedPhotoQualities(): List<PhotoQuality> {
        val info = selectedLensCameraInfo() ?: return emptyList()
        val sizes = runCatching {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                ?.toList()
        }.getOrNull() ?: emptyList()
        val ratio43 = sizes
            .filter { abs(it.width.toFloat() / it.height - 4f / 3f) < 0.02f && it.height >= 480 }
            .sortedByDescending { it.width.toLong() * it.height }
        val picked = mutableListOf<Size>()
        for (s in ratio43) {
            if (picked.isEmpty() || s.height < picked.last().height * 0.7f) picked += s
            if (picked.size >= 4) break
        }
        val list = picked.map { s ->
            val mp = s.width.toLong() * s.height / 1_000_000.0
            PhotoQuality(
                label = "${s.width}×${s.height}",
                spec = "约 %.1fMP · 4:3".format(mp),
                width = s.width,
                height = s.height,
            )
        }
        Log.d(TAG, "photo qualities: jpegSizes=${sizes.size}, 43=${ratio43.size}, tiers=${list.map { it.label }}")
        return list
    }

    /** 画质档位定义：内部 id ↔ CameraX Quality ↔ CamcorderProfile 常量 + 流高度（编码器选档基准）+ 兜底尺寸 */
    private class QualityTier(
        val id: Int,
        val cameraX: Quality,
        val profileQuality: Int,
        val streamHeight: Int,
        val fallbackW: Int,
        val fallbackH: Int,
        val specName: String,
    )

    private val qualityTiers = listOf(
        QualityTier(4, Quality.UHD, CamcorderProfile.QUALITY_2160P, 2160, 3840, 2160, "2160p"),
        QualityTier(3, Quality.FHD, CamcorderProfile.QUALITY_1080P, 1080, 1920, 1080, "1080p"),
        QualityTier(1, Quality.HD, CamcorderProfile.QUALITY_720P, 720, 1280, 720, "720p"),
        QualityTier(2, Quality.SD, CamcorderProfile.QUALITY_480P, 480, 720, 480, "480p"),
    )

    /** 当前镜头的 CameraInfo（随前后摄/焦段切换）；拿不到返回 null */
    private fun selectedLensCameraInfo(): CameraInfo? {
        val p = provider ?: return null
        return runCatching {
            p.availableCameraInfos.firstOrNull { info ->
                if (frontCamera) {
                    info.lensFacing == CameraSelector.LENS_FACING_FRONT
                } else {
                    val id = selectedLensId
                    if (id == null) {
                        info.lensFacing == CameraSelector.LENS_FACING_BACK
                    } else {
                        runCatching { Camera2CameraInfo.from(info).cameraId == id }.getOrDefault(false)
                    }
                }
            }
        }.getOrNull()
    }

    /** 将画质索引映射为 CameraX Quality 常量 */
    private fun qualityConst(q: VideoQuality): androidx.camera.video.Quality = when (q.quality) {
        4 -> androidx.camera.video.Quality.UHD
        3 -> androidx.camera.video.Quality.FHD
        2 -> androidx.camera.video.Quality.SD
        else -> androidx.camera.video.Quality.HD
    }

    // ---------- 电子变焦 ----------

    /** 当前变焦倍数（zoomState 回流，预览徽标显示用） */
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    /** 当前镜头支持的变焦范围（捏合夹取用） */
    private val _zoomMin = MutableStateFlow(1f)
    val zoomMin: StateFlow<Float> = _zoomMin.asStateFlow()
    private val _zoomMax = MutableStateFlow(1f)
    val zoomMax: StateFlow<Float> = _zoomMax.asStateFlow()

    private var zoomObserver: Observer<ZoomState>? = null
    private var zoomObservedInfo: CameraInfo? = null

    /** 设置电子变焦倍数（自动夹取到当前镜头支持范围） */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val zs = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(zs.minZoomRatio, zs.maxZoomRatio))
    }

    /** 绑定成功后挂 zoomState 监听（换镜头/重绑时切换到新相机实例，旧监听移除） */
    private fun observeZoom() {
        val info = camera?.cameraInfo ?: return
        if (info == zoomObservedInfo) return
        zoomObservedInfo?.let { old -> zoomObserver?.let { old.zoomState.removeObserver(it) } }
        var zoomLogged = false
        val obs = Observer { zs: ZoomState ->
            if (!zoomLogged) {
                zoomLogged = true
                Log.d(TAG, "zoom range=${zs.minZoomRatio}..${zs.maxZoomRatio}")
            }
            _zoomRatio.value = zs.zoomRatio
            _zoomMin.value = zs.minZoomRatio
            _zoomMax.value = zs.maxZoomRatio
        }
        zoomObserver = obs
        zoomObservedInfo = info
        info.zoomState.observe(lifecycleOwner, obs)
    }

    val backLenses = mutableListOf<BackLens>()

    /** null 表示使用 CameraX 默认后摄 */
    var selectedLensId: String? = null
    var frontCamera: Boolean = false

    fun init(onReady: () -> Unit, onError: (Throwable) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    provider = future.get()
                    enumerateBackLenses()
                    onReady()
                } catch (t: Throwable) {
                    onError(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** 枚举后置镜头并标注焦段（0.5×/1×/2×...），默认选最接近 1× 的镜头；失败则降级单镜头 */
    private fun enumerateBackLenses() {
        backLenses.clear()
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // 诊断：全量相机清单（逻辑相机聚合多物理头时系统只暴露一颗后置）
            runCatching {
                Log.d(
                    TAG,
                    "allCameras=" + cm.cameraIdList.map { id ->
                        val chars = cm.getCameraCharacteristics(id)
                        val f = chars.get(CameraCharacteristics.LENS_FACING)
                        // API 30+：逻辑相机包含的物理头 id（超广角是否聚合进来的关键证据）
                        val phys = if (android.os.Build.VERSION.SDK_INT >= 30) {
                            chars.physicalCameraIds.joinToString(",")
                        } else {
                            "?"
                        }
                        "$id:" + when (f) {
                            CameraMetadata.LENS_FACING_BACK -> "BACK"
                            CameraMetadata.LENS_FACING_FRONT -> "FRONT"
                            else -> "EXT"
                        } + "(phys=$phys)"
                    },
                )
            }
            data class Raw(val id: String, val focal: Float)
            val raws = cm.cameraIdList.mapNotNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) {
                    return@mapNotNull null
                }
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.minOrNull() ?: return@mapNotNull null
                Raw(id, focal)
            }.sortedBy { it.focal }
            if (raws.isEmpty()) return

            val widest = raws.first().focal
            val ratios = raws.map { it.focal / widest }
            var bestIdx = 0
            var bestDiff = Float.MAX_VALUE
            ratios.forEachIndexed { i, r ->
                val d = abs(r - 1f)
                if (d < bestDiff) {
                    bestDiff = d
                    bestIdx = i
                }
            }
            raws.forEachIndexed { i, raw ->
                val ratio = (ratios[i] * 10).toInt() / 10f
                val label = if (ratio % 1f == 0f) "${ratio.toInt()}×" else "${ratio}×"
                backLenses += BackLens(raw.id, label)
            }
            selectedLensId = raws[bestIdx].id
            Log.d(TAG, "enumerateBackLenses: ${backLenses.map { "${it.label}(id=${it.cameraId})" }}, best=$bestIdx")
        } catch (t: Throwable) {
            Log.w(TAG, "enumerateBackLenses failed", t)
            // 枚举失败：不显示焦段按钮，走默认后摄
        }
    }

    fun selectLens(cameraId: String?) {
        selectedLensId = cameraId
    }

    private fun selector(): CameraSelector {
        if (frontCamera) return CameraSelector.DEFAULT_FRONT_CAMERA
        val id = selectedLensId ?: return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder()
            .requireLensFacing(CameraMetadata.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                infos.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId == id }.getOrDefault(false)
                }.ifEmpty { infos }
            }
            .build()
    }

    private fun resolution4x3(): ResolutionSelector =
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

    /**
     * 绑定用例：预览+拍照+录像+扫码常绑，切换模式不重绑。
     * 挂 ViewPort（预览控件实际宽高 + FILL_CENTER）统一裁剪所有流的比例，
     * 保证录像/拍照与预览所见比例一致。
     * 绑定参数（前后摄/焦段/画质/预览尺寸）未变时跳过（防止快速重绑导致预览白屏）。
     * 全量绑失败逐级回退：去扫码 → 去录像 → 去 ViewPort，保证相机始终可用。
     */
    @SuppressLint("RestrictedApi")
    fun bind(previewView: PreviewView, analyzer: ImageAnalysis.Analyzer?) {
        val p = provider ?: return
        val vw = previewView.width
        val vh = previewView.height
        val bindKey = "$frontCamera|$selectedLensId|${videoQuality.label}|${photoQuality?.label}|$vw|$vh"
        if (bindKey == lastBindKey && camera != null) return
        lastBindKey = bindKey
        meteringFactory = previewView.meteringPointFactory

        val preview = Preview.Builder().setResolutionSelector(resolution4x3()).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // 照片画质档：选档时用分辨率策略就近匹配（仍限 4:3），默认不限制挑最大
        val photoSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .apply {
                photoQuality?.let {
                    setResolutionStrategy(
                        ResolutionStrategy(
                            Size(it.width, it.height),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                }
            }
            .build()
        val capture = ImageCapture.Builder()
            .setResolutionSelector(photoSelector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        capture.targetRotation = targetRotation

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    qualityConst(videoQuality),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .build()
        val video = VideoCapture.withOutput(recorder)
        video.targetRotation = targetRotation

        val analysis = analyzer?.let { a ->
            val exec = analysisExecutor
                ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
            ImageAnalysis.Builder()
                .setResolutionSelector(resolution4x3())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(exec, a) }
        }

        fun attempt(withVideo: Boolean, withAnalysis: Boolean, withViewport: Boolean): Camera? =
            try {
                p.unbindAll()
                val group = androidx.camera.core.UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                if (withVideo) group.addUseCase(video)
                if (withAnalysis) analysis?.let(group::addUseCase)
                if (withViewport && vw > 0 && vh > 0) {
                    group.setViewPort(
                        ViewPort.Builder(Rational(vw, vh), targetRotation)
                            .setScaleType(ViewPort.FILL_CENTER)
                            .build(),
                    )
                }
                p.bindToLifecycle(lifecycleOwner, selector(), group.build())
            } catch (t: Throwable) {
                Log.w(TAG, "bind failed (video=$withVideo, analysis=$withAnalysis, viewport=$withViewport)", t)
                null
            }

        imageCapture = capture
        videoCapture = video
        camera = attempt(withVideo = true, withAnalysis = true, withViewport = true)
        observeZoom()
            ?: attempt(withVideo = true, withAnalysis = false, withViewport = true)
            ?: attempt(withVideo = false, withAnalysis = true, withViewport = true)
            ?: attempt(withVideo = false, withAnalysis = true, withViewport = false)
                .also { if (it != null) videoCapture = null }
        if (camera == null) videoCapture = null
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
        camera?.cameraControl?.enableTorch(mode == ImageCapture.FLASH_MODE_ON)
    }

    /**
     * 点击对焦测光：x/y 为预览控件内像素坐标，经 meteringPointFactory 换算成
     * 传感器测光区域，同时下发 AF/AE/AWB，4 秒后自动取消回连续模式。
     * 返回是否已下发（相机未就绪或设备不支持该组合时 false）。
     */
    fun focusMeter(x: Float, y: Float): Boolean {
        val cam = camera ?: return false
        val factory = meteringFactory ?: return false
        return try {
            val action = FocusMeteringAction.Builder(
                factory.createPoint(x, y),
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB,
            )
                .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            if (cam.cameraInfo.isFocusMeteringSupported(action)) {
                cam.cameraControl.startFocusAndMetering(action)
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "focusMeter failed", t)
            false
        }
    }

    fun takePhoto(file: File, onResult: (Boolean) -> Unit) {
        val cap = imageCapture
        if (cap == null) {
            onResult(false)
            return
        }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        cap.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onResult(true)
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    onResult(false)
                }
            },
        )
    }

    fun startRecording(file: File, withAudio: Boolean, onEvent: (VideoRecordEvent) -> Unit): Recording? {
        val vc = videoCapture
        Log.d(TAG, "startRecording: videoCapture=${vc != null}, audio=$withAudio")
        if (vc == null) return null
        return try {
            val pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            if (withAudio) pending.withAudioEnabled()
            val rec = pending.start(ContextCompat.getMainExecutor(context), onEvent)
            Log.d(TAG, "startRecording: started=${rec != null}")
            rec
        } catch (t: Throwable) {
            Log.w(TAG, "startRecording failed", t)
            null
        }
    }

    fun unbind() {
        provider?.unbindAll()
    }

    fun release() {
        zoomObservedInfo?.let { old -> zoomObserver?.let { old.zoomState.removeObserver(it) } }
        unbind()
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    private companion object {
        const val TAG = "CameraEngine"
    }
}
