package com.engcamera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.engcamera.data.DynamicValues
import com.engcamera.data.WatermarkState
import com.engcamera.watermark.WatermarkRenderer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 视频水印合成（录后离线转码，对应计划书待确认 8）：
 * - 原生模板：文字层经 BitmapOverlay.getBitmap(时间戳) 逐秒重渲——水印时间随录像实际
 *   时间流动（快照开始时刻 + 帧时间）；液态玻璃模板另挂 GlassFrameEffect 在 GPU 上
 *   逐帧折射采样视频画面（方案 B，用户拍板 2026-10-16），drawOnCanvas 跳过 CPU 玻璃卡。
 * - 网页模板：逐秒重渲 WebView 太慢，时间定格在录像开始时刻。
 * - 新管线失败整体降级为旧静态帧路径（一次性整层位图，时间不流动），
 *   降级也失败由调用方保存无水印原片。
 */
object VideoWatermarker {

    /** 返回烧好水印的临时文件；失败抛异常 */
    suspend fun burn(
        context: Context,
        input: File,
        state: WatermarkState,
        dynamic: DynamicValues,
        frameRotationDeg: Int = 0,
        startEpochMs: Long = 0L,
        rollTimeline: List<Pair<Long, Int>> = emptyList(),
    ): File {
        val (width, height) = readDisplaySize(input)
        val rotBase = state.rotationDeg + frameRotationDeg
        // 录像中旋转同步（用户拍板 2026-10-16）：视频帧方向固定为开录时刻，预览水印随
        // 重力动——按「当时 roll − 开录 roll」把重力锚点旋回视频系；文字角度只在重力
        // 跟随开启时补 delta（关闭时文字屏系固定，视频系也固定）。锚点恒重力系照转。
        val startRoll = rollTimeline.firstOrNull()?.second ?: 0
        fun rollAt(atMs: Long): Int {
            var r = startRoll
            for ((t, roll) in rollTimeline) {
                if (t <= atMs) r = roll else break
            }
            return r
        }
        val hasRotation = rollTimeline.size > 1
        fun stateAt(atMs: Long): WatermarkState {
            if (!hasRotation) return state
            val delta = ((rollAt(atMs) - startRoll) % 360 + 360) % 360
            if (delta == 0) return state
            val (ax, ay) = WatermarkRenderer.rotateAnchor(state.anchorX, state.anchorY, delta.toFloat())
            return state.copy(anchorX = ax, anchorY = ay)
        }
        fun rotAt(atMs: Long): Float =
            if (hasRotation && state.followGravity) {
                rotBase + ((rollAt(atMs) - startRoll) % 360)
            } else {
                rotBase
            }

        // 玻璃卡几何随时间线重算（按秒缓存；块尺寸/锚点/角度都随 roll 变）
        val geoCache = HashMap<Long, WatermarkRenderer.GlassGeometry?>()
        fun geometryAt(presentationTimeUs: Long): WatermarkRenderer.GlassGeometry? {
            val atMs = if (startEpochMs > 0) startEpochMs + presentationTimeUs / 1000 else 0L
            val sec = atMs / 1000
            synchronized(geoCache) {
                if (!geoCache.containsKey(sec)) {
                    if (geoCache.size > 3) geoCache.clear()
                    geoCache[sec] = WatermarkRenderer.liquidGlassGeometry(
                        stateAt(atMs), width, height, dynamic, rotAt(atMs),
                    )
                }
                return geoCache[sec]
            }
        }

        val newResult = runCatching {
            val overlay = TimedWatermarkOverlay(
                width, height, dynamic, startEpochMs,
                stateAt = ::stateAt, rotAt = ::rotAt,
            )
            val glEffects = buildList<androidx.media3.effect.GlEffect> {
                // 顺序即执行顺序：先玻璃折射（改写帧），再叠文字层
                if (geometryAt(0L) != null) add(GlassFrameEffect(::geometryAt))
                add(OverlayEffect(listOf(overlay)))
            }
            transform(context, input, File(input.parentFile, input.nameWithoutExtension + "_wm.mp4"), glEffects)
        }
        return newResult.getOrElse {
            burnLegacy(context, input, state, dynamic, frameRotationDeg)
        }
    }

    /**
     * 逐秒重渲的全帧水印层（时间流动）。OverlayEffect 对贴图是 1:1 像素合成、
     * 不做放大——层必须与成片同分辨率（v171 半分辨率层导致文字缩小一半且不落在
     * 玻璃卡上，用户实测 2026-10-16）。单张可变位图原地重绘（擦除→整层重画），
     * generationId 变化触发 BitmapOverlay 重传纹理；4K 下 33MB 一张，缓存即位图本身。
     */
    private class TimedWatermarkOverlay(
        private val frameW: Int,
        private val frameH: Int,
        private val dynamic: DynamicValues,
        private val startEpochMs: Long,
        private val stateAt: (Long) -> WatermarkState,
        private val rotAt: (Long) -> Float,
    ) : BitmapOverlay() {

        private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        private val bitmap: Bitmap =
            Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private var cacheSec = Long.MIN_VALUE

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val atMs =
                if (startEpochMs > 0) startEpochMs + presentationTimeUs / 1000
                else System.currentTimeMillis()
            val sec = atMs / 1000
            synchronized(this) {
                if (sec != cacheSec) {
                    render(atMs)
                    cacheSec = sec
                }
                return bitmap
            }
        }

        override fun release() {
            synchronized(this) {
                cacheSec = Long.MIN_VALUE
                bitmap.recycle()
            }
            super.release()
        }

        private fun render(atMs: Long) {
            canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
            val d = dynamic.copy(time = fmt.format(Date(atMs)))
            WatermarkRenderer.drawOnCanvas(
                canvas, frameW, frameH,
                stateAt(atMs), d, rotAt(atMs), 0,
                gravityAnchor = true, // 视频显示系（开录时刻）=重力系，锚点经时间线旋回
                skipGlassCard = true, // 玻璃由 GlassFrameEffect 在 GPU 上处理
            )
        }
    }

    /**
     * 导入视频（相册选中的成片）加水印：**保持源分辨率/帧率/时长**，只叠水印层。
     *
     * 与录像路径的差别（都是为了「不掉画质」）：
     * - 码率对齐源片（[readBitrate]）——media3 默认按分辨率自选码率，可能低于源片；
     * - 音轨直接拷贝（`setTransmuxAudio(true)`），不重编码；
     * - 水印时间从 [startEpochMs]（合成时刻）起流动，与照片导入「盖当前时间」一致。
     *
     * 视频本身没有重力参照系，水印与照片导入同构：锚点按图片系直接落位、
     * frameRotationDeg=0（media3 已把帧按 rotation 元数据转正后再交给效果层）。
     * 标注层（箭头/矩形/画笔/文字）整段常驻，作为独立静态贴图叠在水印之下。
     *
     * @param annotationLayer 已按显示尺寸烧好的标注层（透明底），null=无标注
     * @param onProgress 0f..1f 编码进度
     */
    suspend fun burnImported(
        context: Context,
        input: Uri,
        state: WatermarkState,
        dynamic: DynamicValues,
        annotationLayer: Bitmap? = null,
        startEpochMs: Long = System.currentTimeMillis(),
        onProgress: (Float) -> Unit = {},
    ): File {
        val (width, height) = readDisplaySize(context, input)
        val bitrate = readBitrate(context, input)
        val output = File(
            context.cacheDir,
            "import_wm_${System.currentTimeMillis()}.mp4",
        )
        val newResult = runCatching {
            val overlay = TimedWatermarkOverlay(
                width, height, dynamic, startEpochMs,
                stateAt = { state }, // 导入视频无重力时间线，水印状态全程恒定
                rotAt = { state.rotationDeg },
            )
            val glEffects = buildList<androidx.media3.effect.GlEffect> {
                // 液态玻璃：静态几何（整段不变），与录像路径同一套 GPU 折射
                val geo = WatermarkRenderer.liquidGlassGeometry(
                    state, width, height, dynamic, state.rotationDeg,
                )
                if (geo != null) add(GlassFrameEffect { geo })
                add(OverlayEffect(listOf(overlay)))
                // 标注层在水印之上（与照片保存的层级一致：标注盖水印）
                if (annotationLayer != null && !annotationLayer.isRecycled) {
                    add(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(annotationLayer))))
                }
            }
            // 音轨直拷优先；部分源音轨格式不被 MP4 接受（如某些设备的 AMR/Opus），
            // 直拷失败再退回重编码，不因为音轨拖累整个合成。
            // 取消必须原样抛出：当作失败去重试会把已经取消的任务又跑一遍
            try {
                transform(
                    context, input, output, glEffects,
                    bitrate = bitrate,
                    transmuxAudio = true,
                    onProgress = onProgress,
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w("VideoWatermarker", "audio transmux failed, re-encoding audio", t)
                output.delete()
                transform(
                    context, input, output, glEffects,
                    bitrate = bitrate,
                    transmuxAudio = false,
                    onProgress = onProgress,
                )
            }
        }
        return newResult.getOrElse { t ->
            // 取消不降级：用户主动中断，不该再跑一遍更慢的静态路径
            if (t is kotlinx.coroutines.CancellationException) {
                output.delete()
                throw t
            }
            Log.w("VideoWatermarker", "import burn failed, falling back to static layer", t)
            output.delete()
            // 降级：整层静态帧（时间不流动），同样带上标注层
            burnLegacyImported(context, input, state, dynamic, annotationLayer)
        }
    }

    /** 导入视频的降级路径：一次性静态水印层（时间定格），失败抛异常 */
    private suspend fun burnLegacyImported(
        context: Context,
        input: Uri,
        state: WatermarkState,
        dynamic: DynamicValues,
        annotationLayer: Bitmap?,
    ): File {
        val (width, height) = readDisplaySize(context, input)
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        WatermarkRenderer.drawOnCanvas(
            canvas, width, height, state, dynamic, state.rotationDeg, 0,
            gravityAnchor = true,
        )
        if (annotationLayer != null && !annotationLayer.isRecycled) {
            canvas.drawBitmap(annotationLayer, 0f, 0f, null)
        }
        val output = File(context.cacheDir, "import_wmb_${System.currentTimeMillis()}.mp4")
        val result = transform(
            context, input, output,
            listOf(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(frame)))),
            bitrate = readBitrate(context, input),
            transmuxAudio = true,
        )
        frame.recycle()
        return result
    }

    /** 旧静态帧路径（降级用）：整层一次性烧好，时间不流动，液态玻璃为无采样着色底 */
    private suspend fun burnLegacy(
        context: Context,
        input: File,
        state: WatermarkState,
        dynamic: DynamicValues,
        frameRotationDeg: Int,
    ): File {
        val (width, height) = readDisplaySize(input)
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        WatermarkRenderer.drawOnCanvas(
            canvas, width, height, state, dynamic, state.rotationDeg, frameRotationDeg,
            gravityAnchor = true,
        )
        val output = File(input.parentFile, input.nameWithoutExtension + "_wmb.mp4")
        val result = transform(context, input, output, listOf(OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(frame)))))
        frame.recycle()
        return result
    }

    /** 读视频显示尺寸（部分设备 retriever 返回未应用旋转的原始尺寸，需自行交换宽高） */
    private fun readDisplaySize(input: File): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.absolutePath)
            return displaySizeOf(retriever)
        } finally {
            retriever.release()
        }
    }

    /** 同上，来源为相册 Uri（导入视频路径） */
    private fun readDisplaySize(context: Context, input: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            return displaySizeOf(retriever)
        } finally {
            retriever.release()
        }
    }

    private fun displaySizeOf(retriever: MediaMetadataRetriever): Pair<Int, Int> {
        var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val t = w
            w = h
            h = t
        }
        if (w <= 0 || h <= 0) error("无法读取视频尺寸")
        return w to h
    }

    /** 源片码率（bit/s）：对齐源片编码，避免转码后画质肉眼可见下降；读不到返回 0（不干预） */
    private fun readBitrate(context: Context, input: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, input)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private suspend fun transform(
        context: Context,
        input: File,
        output: File,
        glEffects: List<androidx.media3.effect.GlEffect>,
    ): File = transform(context, MediaItem.fromUri(Uri.fromFile(input)), output, glEffects)

    private suspend fun transform(
        context: Context,
        input: Uri,
        output: File,
        glEffects: List<androidx.media3.effect.GlEffect>,
        bitrate: Int = 0,
        transmuxAudio: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): File = transform(
        context, MediaItem.fromUri(input), output, glEffects, bitrate, transmuxAudio, onProgress,
    )

    /**
     * 转码主链路。
     * @param bitrate >0 时按此码率编码（对齐源片）；0=让 media3 按分辨率自选
     * @param transmuxAudio true=音轨直接拷贝不重编码（导入视频保音质）
     */
    private suspend fun transform(
        context: Context,
        mediaItem: MediaItem,
        output: File,
        glEffects: List<androidx.media3.effect.GlEffect>,
        bitrate: Int = 0,
        transmuxAudio: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
    ): File = withContext(Dispatchers.Main) {
        // 进度轮询的作用域：独立 Job，finally 里统一取消——Transformer 只提供 pull 式
        // getProgress 没有回调，若不显式停会一直轮询已释放的实例
        val progressScope = kotlinx.coroutines.CoroutineScope(coroutineContext + Job())
        try {
            suspendCancellableCoroutine { cont ->
                val overlayEffects = Effects(emptyList(), glEffects)
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(overlayEffects)
                    .build()
                val builder = Transformer.Builder(context)
                if (bitrate > 0) {
                    builder.setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
                            )
                            .build(),
                    )
                }
                builder.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(output)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                val transformer = builder.build()
                // 只有需要拷贝音轨时才显式建 Composition（setTransmuxAudio 只在其 Builder 上）；
                // 其余走 EditedMediaItem 便捷重载（默认音轨也重编码）
                if (transmuxAudio) {
                    val composition = Composition.Builder(
                        EditedMediaItemSequence.Builder(listOf(edited)).build(),
                    )
                        .setTransmuxAudio(true)
                        .build()
                    transformer.start(composition, output.absolutePath)
                } else {
                    transformer.start(edited, output.absolutePath)
                }
                if (onProgress != null) {
                    val holder = ProgressHolder()
                    progressScope.launch {
                        while (isActive) {
                            if (transformer.getProgress(holder) ==
                                Transformer.PROGRESS_STATE_AVAILABLE
                            ) {
                                onProgress(holder.progress / 100f)
                            }
                            delay(300)
                        }
                    }
                }
                cont.invokeOnCancellation {
                    runCatching { transformer.cancel() }
                }
            }
        } finally {
            progressScope.coroutineContext[Job]?.cancel()
        }
    }
}
