package com.engcamera.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 视频水印合成（录像结束后的离线转码方案，对应计划书待确认 8）：
 * 按视频显示尺寸生成整帧透明水印位图（与照片烧录共用 WatermarkRenderer，所见即所得），
 * 用 Media3 Transformer 转码烧入。失败由调用方降级保存无水印原片。
 */
object VideoWatermarker {

    /** 返回烧好水印的临时文件；失败抛异常 */
    suspend fun burn(
        context: Context,
        input: File,
        state: WatermarkState,
        dynamic: DynamicValues,
        frameRotationDeg: Int = 0,
    ): File {
        // 1) 读视频显示尺寸。部分设备的 MediaMetadataRetriever 返回未应用旋转的原始尺寸，
        //    需按旋转元数据自行交换宽高，否则水印位图横竖颠倒导致位置/比例错位
        val retriever = MediaMetadataRetriever()
        val width: Int
        val height: Int
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
            width = w
            height = h
            android.util.Log.d("VideoWM", "input ${width}x${height}, rotation=$rotation")
        } finally {
            retriever.release()
        }
        if (width <= 0 || height <= 0) error("无法读取视频尺寸")

        // 2) 整帧透明位图上画水印。frameRotationDeg 为屏幕系→成片系换算角（只由持机方向决定）
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        WatermarkRenderer.drawOnCanvas(canvas, width, height, state, dynamic, state.rotationDeg, frameRotationDeg)

        // 3) 转码烧入
        val output = File(input.parentFile, input.nameWithoutExtension + "_wm.mp4")
        val result = try {
            transform(context, input, output, frame)
        } finally {
            frame.recycle()
        }
        // 记录输出尺寸便于核对方向一致性
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(result.absolutePath)
                val ow = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val oh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val or = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                android.util.Log.d("VideoWM", "output ${ow}x${oh}, rotation=$or")
            } finally {
                r.release()
            }
        }
        return result
    }

    private suspend fun transform(context: Context, input: File, output: File, watermark: Bitmap): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val overlay = BitmapOverlay.createStaticBitmapOverlay(watermark)
                val effects = Effects(emptyList(), listOf(OverlayEffect(listOf(overlay))))
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                    .setEffects(effects)
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
