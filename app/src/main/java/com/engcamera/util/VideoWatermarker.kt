package com.engcamera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
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
        } finally {
            retriever.release()
        }
    }

    private suspend fun transform(
        context: Context,
        input: File,
        output: File,
        glEffects: List<androidx.media3.effect.GlEffect>,
    ): File = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val overlayEffects = Effects(emptyList(), glEffects)
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(overlayEffects)
                .build()
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
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
                .build()
            transformer.start(edited, output.absolutePath)
            cont.invokeOnCancellation {
                runCatching { transformer.cancel() }
            }
        }
    }
}
