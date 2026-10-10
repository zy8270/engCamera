package com.engcamera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkField
import com.engcamera.data.WatermarkState
import kotlin.math.roundToInt

/** 水印边界安全边距比例（画面宽度的约 1.5%，至少 4px） */
private const val EDGE_MARGIN_RATIO = 0.015f

/**
 * 水印渲染引擎：预览浮层与成片烧录共用同一套布局规则，
 * 字号、宽度均按画面宽度比例计算（对应计划书 5.2）。
 */
object WatermarkRenderer {

    /** 渲染好的整块水印 */
    class Block(val bitmap: Bitmap) {
        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height
    }

    /**
     * 按目标画面宽度渲染整块水印。预览传预览区宽度，成片传照片宽度，
     * 同一份 state 会得到同比例的水印。
     * 样式（用户确认 2026-10-10）：标题深蓝条与正文黑块紧贴、正文行间零空隙，
     * 整块连续背景（外层统一圆角），文字按内容自动换行、不限行数。
     */
    fun render(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val width = canvasWidth.coerceAtLeast(1)
        val blockWidth = (width * style.maxWidthRatio).toInt().coerceAtLeast(1)
        val pad = width * style.paddingRatio
        val corner = width * style.cornerRatio

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.titleTextColor.toInt()
            textSize = width * style.titleFontSizeRatio
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = width * style.bodyFontSizeRatio
        }
        val textWidth = (blockWidth - pad * 2).toInt().coerceAtLeast(1)

        val enabled = state.fields.filter { it.enabled }
        val titleLayout = enabled.firstOrNull { it.isTitle }
            ?.let { newLayout(displayText(it, dynamic), titlePaint, textWidth, Layout.Alignment.ALIGN_CENTER) }
        val bodyLayouts = enabled.filter { !it.isTitle }.map { f ->
            val text = if (f.label.isBlank()) displayText(f, dynamic) else "${f.label}：${displayText(f, dynamic)}"
            newLayout(text, bodyPaint, textWidth)
        }
        if (titleLayout == null && bodyLayouts.isEmpty()) {
            return Block(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }

        val titleHeight = titleLayout?.height ?: 0
        val bodyHeight = bodyLayouts.sumOf { it.height.toDouble() }.toInt()
        val blockHeight = (titleHeight + bodyHeight + pad * 2).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 整块裁剪为圆角，内部连续背景
        canvas.save()
        canvas.clipPath(
            android.graphics.Path().apply {
                addRoundRect(
                    android.graphics.RectF(0f, 0f, blockWidth.toFloat(), blockHeight.toFloat()),
                    corner, corner, android.graphics.Path.Direction.CW,
                )
            },
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (titleHeight > 0) {
            bgPaint.color = style.titleBgColor.toInt()
            canvas.drawRect(0f, 0f, blockWidth.toFloat(), titleHeight + pad.toFloat(), bgPaint)
        }
        if (bodyHeight > 0) {
            bgPaint.color = style.bodyBgColor.toInt()
            canvas.drawRect(
                0f,
                (if (titleHeight > 0) titleHeight + pad else 0).toFloat(),
                blockWidth.toFloat(),
                blockHeight.toFloat(),
                bgPaint,
            )
        }
        var y = pad
        titleLayout?.let {
            canvas.save()
            // 标题在蓝条内水平居中（ALIGN_CENTER）+ 垂直居中（上下各留一半内边距）
            canvas.translate(pad, (titleHeight + pad - it.height) / 2f)
            it.draw(canvas)
            canvas.restore()
            y += it.height + pad
        }
        bodyLayouts.forEach { layout ->
            canvas.save()
            canvas.translate(pad, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
        }
        canvas.restore()
        return Block(bitmap)
    }

    /** 根据锚点（归一化中心点）计算旋转后仍完整在画面内的块左上角（未旋转坐标系）。 */
    fun positionBlock(
        canvasW: Int,
        canvasH: Int,
        blockW: Int,
        blockH: Int,
        anchorX: Float,
        anchorY: Float,
        rotationDeg: Float,
    ): Pair<Float, Float> {
        // ±90° 时旋转后包围盒宽高互换；0/180° 不变
        val swapped = ((rotationDeg.roundToInt() % 180 + 180) % 180) == 90
        val bw = (if (swapped) blockH else blockW).coerceAtMost(canvasW)
        val bh = (if (swapped) blockW else blockH).coerceAtMost(canvasH)
        // 画面边缘留安全边距，水印不贴边（用户确认 2026-10-10）
        val margin = (canvasW * EDGE_MARGIN_RATIO).toInt().coerceAtLeast(4)
        val availW = (canvasW - margin * 2).coerceAtLeast(bw)
        val availH = (canvasH - margin * 2).coerceAtLeast(bh)
        val cx = anchorX.coerceIn(0f, 1f) * canvasW
        val cy = anchorY.coerceIn(0f, 1f) * canvasH
        val bboxLeft = (cx - bw / 2f).coerceIn(margin.toFloat(), (availW - bw + margin).toFloat())
        val bboxTop = (cy - bh / 2f).coerceIn(margin.toFloat(), (availH - bh + margin).toFloat())
        // 块围绕自身中心旋转，中心即包围盒中心
        return (bboxLeft + (bw - blockW) / 2f) to (bboxTop + (bh - blockH) / 2f)
    }

    /**
     * 把水印画进成片（含旋转），与预览规则一致。
     *
     * @param rotationDeg 水印旋转角（预览/屏幕坐标系）
     * @param frameRotationDeg 成片相对屏幕坐标系的旋转角（EXIF 方向/视频旋转元数据）。
     *        拍照方向跟随持机方向（如倒置拍 targetRotation=180）时，成片"上方向"与屏幕不同，
     *        锚点与旋转角需换算到成片坐标系，保证成片里水印在用户视角的同一相对位置。
     */
    fun drawOnCanvas(
        canvas: Canvas,
        canvasW: Int,
        canvasH: Int,
        state: WatermarkState,
        dynamic: DynamicValues,
        rotationDeg: Float,
        frameRotationDeg: Int = 0,
    ) {
        val e = ((frameRotationDeg % 360) + 360) % 360
        val rad = Math.toRadians(e.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val vx = state.anchorX - 0.5f
        val vy = state.anchorY - 0.5f
        val ax = 0.5f + (vx * cos - vy * sin)
        val ay = 0.5f + (vx * sin + vy * cos)
        val rot = rotationDeg + e
        // 缩放基准取短边：预览恒为竖向 3:4（宽=短边），横拍成片显示为横向（宽=长边）。
        // 若按长边缩放，横屏水印会比预览大 4/3 倍；短边在两种方向下都对应预览宽度维度。
        val block = render(state, minOf(canvasW, canvasH), dynamic)
        val (left, top) = positionBlock(canvasW, canvasH, block.width, block.height, ax, ay, rot)
        canvas.save()
        canvas.rotate(rot, left + block.width / 2f, top + block.height / 2f)
        canvas.drawBitmap(block.bitmap, left, top, null)
        canvas.restore()
        block.bitmap.recycle()
    }

    /** 字段当前显示文本：位置/时间标签有值（手动编辑）则优先生效，空则用拍照瞬间动态值。 */
    private fun displayText(f: WatermarkField, dynamic: DynamicValues): String = when (f.type) {
        FieldType.DATETIME -> f.value.ifBlank { dynamic.time }
        FieldType.LOCATION -> f.value.ifBlank { dynamic.location.ifBlank { "未获取定位" } }
        FieldType.COUNT -> f.value.filter { it.isDigit() }.ifBlank { "1" }
        else -> f.value
    }

    private fun newLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()
}
