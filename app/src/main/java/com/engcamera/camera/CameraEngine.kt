package com.engcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 相机引擎，按模式绑定用例以最大化设备兼容性：
 * - 拍照模式：预览 + 拍照 + 扫码分析
 * - 录像模式：预览 + 录像（不叠其他用例）
 * 拍照水印走 Bitmap 烧录；录像水印因 camera-effects OverlayEffect 在真机/模拟器
 * 均内部崩溃（"Frame is already empty"）暂不烧录，见计划书待确认 8。
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

    /** 画质选项 */
    data class VideoQuality(val label: String, val quality: Int)

    /** 当前选中的录像画质（CameraX Quality 常量） */
    @Volatile
    var videoQuality: VideoQuality = VideoQuality("高清", 1) // 1=HD
        set(value) {
            field = value
        }

    /** 可选画质列表（标准档位，CameraX 自动匹配设备支持的最佳分辨率） */
    fun getSupportedVideoQualities(): List<VideoQuality> = listOf(
        VideoQuality("最高", 0),   // HIGHEST
        VideoQuality("超清", 4),   // UHD 4K
        VideoQuality("全高清", 3), // FHD 1080p
        VideoQuality("高清", 1),   // HD 720p
        VideoQuality("标清", 2),   // SD
    )

    /** 将画质索引映射为 CameraX Quality 常量 */
    private fun qualityConst(q: VideoQuality): androidx.camera.video.Quality = when (q.quality) {
        0 -> androidx.camera.video.Quality.HIGHEST
        4 -> androidx.camera.video.Quality.UHD
        3 -> androidx.camera.video.Quality.FHD
        2 -> androidx.camera.video.Quality.SD
        else -> androidx.camera.video.Quality.HD
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
        } catch (_: Throwable) {
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
     * 绑定用例。videoMode=true 只绑 预览+录像（兼容性最好，扫码气泡仅拍照模式需要）；
     * videoMode=false 绑 预览+拍照+扫码分析。
     * 绑定参数未变化时跳过（防止启动/回调多次快速重绑导致预览白屏）。
     */
    @SuppressLint("RestrictedApi")
    fun bind(previewView: PreviewView, analyzer: ImageAnalysis.Analyzer?, videoMode: Boolean) {
        val p = provider ?: return
        val bindKey = "$frontCamera|${selectedLensId}|$videoMode"
        if (bindKey == lastBindKey && camera != null) return
        lastBindKey = bindKey
        p.unbindAll()

        val preview = Preview.Builder().setResolutionSelector(resolution4x3()).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        val group = androidx.camera.core.UseCaseGroup.Builder().addUseCase(preview)

        try {
            if (videoMode) {
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
                videoCapture = video
                imageCapture = null
                group.addUseCase(video)
            } else {
                val capture = ImageCapture.Builder()
                    .setResolutionSelector(resolution4x3())
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                capture.targetRotation = targetRotation
                imageCapture = capture
                videoCapture = null
                group.addUseCase(capture)
                if (analyzer != null) {
                    val exec = analysisExecutor
                        ?: Executors.newSingleThreadExecutor().also { analysisExecutor = it }
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolution4x3())
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(exec, analyzer)
                    group.addUseCase(analysis)
                }
            }
            camera = p.bindToLifecycle(lifecycleOwner, selector(), group.build())
        } catch (t: Throwable) {
            Log.w(TAG, "bind failed", t)
        }
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
        camera?.cameraControl?.enableTorch(mode == ImageCapture.FLASH_MODE_ON)
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
        unbind()
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    private companion object {
        const val TAG = "CameraEngine"
    }
}
