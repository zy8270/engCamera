package com.engcamera.camera

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 取景器截帧（用户拍板 2026-10-16）：进编辑器时以取景器一帧作静态预览背景
 * （低配所见即所得）——液态玻璃采样同一帧。
 * **取帧时机=点开编辑器的瞬间**（相机页完全在场；onDispose 时编辑器已组合完毕，
 * 那里只留兜底）；frame 为 Compose 状态，晚到的截帧也能触发编辑器重组。
 */
object EditorBackdrop {

    var frame by mutableStateOf<Bitmap?>(null)
        private set

    fun capture(view: PreviewView) {
        runCatching {
            val bmp = view.bitmap ?: return
            // 缩到 ≤1080 宽的自有副本（view.bitmap 每次新分配，原件可回收）
            val s = if (bmp.width > 1080) 1080f / bmp.width else 1f
            val scaled = if (s < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * s).toInt().coerceAtLeast(1),
                    (bmp.height * s).toInt().coerceAtLeast(1),
                    true,
                ).also { bmp.recycle() }
            } else {
                bmp
            }
            frame?.recycle()
            frame = scaled
        }
    }

    /**
     * 清空截帧（v226）：添加水印模式进编辑器时相机已关，截帧只会拿到空/陈旧
     * 画面——显式清掉旧帧，编辑器回落几何图案背景，液态玻璃采样同步图案位图。
     */
    fun clear() {
        frame?.recycle()
        frame = null
    }

    /**
     * 中心裁剪到目标宽高比（新位图，canvas 拷贝——不用子集位图，其共享像素缓冲的
     * 契约在真机踩过坑）：让"背景显示"与"液态玻璃采样"用同一几何，折射位置才对得上
     */
    fun cropToAspect(src: Bitmap, aspectW: Float, aspectH: Float): Bitmap? = runCatching {
        val srcAspect = src.width.toFloat() / src.height
        val target = aspectW / aspectH
        val cropW: Int
        val cropH: Int
        if (srcAspect > target) {
            cropH = src.height
            cropW = (src.height * target).toInt().coerceAtLeast(1)
        } else {
            cropW = src.width
            cropH = (src.width / target).toInt().coerceAtLeast(1)
        }
        val x = (src.width - cropW) / 2
        val y = (src.height - cropH) / 2
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val srcRect = android.graphics.Rect(x, y, x + cropW, y + cropH)
        val dstRect = android.graphics.RectF(0f, 0f, cropW.toFloat(), cropH.toFloat())
        val paint = android.graphics.Paint()
        paint.isFilterBitmap = true
        canvas.drawBitmap(src, srcRect, dstRect, paint)
        out
    }.getOrNull()
}
