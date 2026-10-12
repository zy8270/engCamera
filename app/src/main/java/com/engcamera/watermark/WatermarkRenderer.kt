package com.engcamera.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.TemplateBackend
import com.engcamera.data.WatermarkField
import com.engcamera.data.WatermarkState
import com.engcamera.data.WatermarkTemplates
import kotlin.math.roundToInt

/** 水印边界安全边距比例（画面宽度的约 1.5%，至少 4px） */
private const val EDGE_MARGIN_RATIO = 0.015f

/**
 * 水印渲染引擎：预览浮层与成片烧录共用同一套布局规则，
 * 字号、宽度均按画面宽度比例计算（对应计划书 5.2）。
 * 0.6.0 起按模板分发：原生模板各布局直接绘制；网页模板经 WebTemplateRenderer 缓存出图。
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
     * 网页模板：同步只取缓存（未就绪返回 1px 占位），预览层负责 warmAsync 触发渲染；
     * 烧录路径走 drawOnCanvas 内的 await（后台线程可阻塞）。
     */
    fun render(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val tpl = WatermarkTemplates.find(state.templateId)
        if (tpl != null && tpl.backend == TemplateBackend.WEB && tpl.html != null) {
            val key = WebTemplateRenderer.cacheKey(state, tpl, dynamic, canvasWidth)
            val bmp = WebTemplateRenderer.current(key)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return Block(bmp)
        }
        return renderNative(state, canvasWidth, dynamic)
    }

    /** 网页模板当前是否已就绪（供预览层判断是否需要 warm） */
    fun isWebReady(state: WatermarkState, dynamic: DynamicValues, canvasWidth: Int): Boolean {
        val tpl = WatermarkTemplates.find(state.templateId) ?: return true
        if (tpl.backend != TemplateBackend.WEB || tpl.html == null) return true
        val key = WebTemplateRenderer.cacheKey(state, tpl, dynamic, canvasWidth)
        return WebTemplateRenderer.current(key) != null
    }

    fun webCacheKey(state: WatermarkState, dynamic: DynamicValues, canvasWidth: Int): String? {
        val tpl = WatermarkTemplates.find(state.templateId) ?: return null
        if (tpl.backend != TemplateBackend.WEB || tpl.html == null) return null
        return WebTemplateRenderer.cacheKey(state, tpl, dynamic, canvasWidth)
    }

    // ================= 原生布局 =================

    private fun renderNative(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block =
        when (state.style.layout) {
            "MINIMAL" -> renderMinimal(state, canvasWidth, dynamic)
            "BORDER_CARD" -> renderBorderCard(state, canvasWidth, dynamic)
            "SINGLE_LINE" -> renderSingleLine(state, canvasWidth, dynamic)
            "GRADIENT" -> renderGradient(state, canvasWidth, dynamic)
            "NOTE_CARD" -> renderNoteCard(state, canvasWidth, dynamic)
            "RECORDER" -> renderRecorder(state, canvasWidth, dynamic)
            else -> renderClassic(state, canvasWidth, dynamic)
        }

    private data class Texts(
        val title: StaticLayout?,
        val rows: List<StaticLayout>,
        val titlePaint: TextPaint,
        val bodyPaint: TextPaint,
        val pad: Float,
        val blockWidth: Int,
        val corner: Float,
    )

    private fun measureTexts(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues, titleCenter: Boolean, fontScale: Float = 1f): Texts {
        val style = state.style
        val width = canvasWidth.coerceAtLeast(1)
        val blockWidth = (width * style.maxWidthRatio).toInt().coerceAtLeast(1)
        val pad = width * style.paddingRatio
        val corner = width * style.cornerRatio
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.titleTextColor.toInt()
            textSize = width * style.titleFontSizeRatio * fontScale
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = width * style.bodyFontSizeRatio * fontScale
        }
        val textWidth = (blockWidth - pad * 2).toInt().coerceAtLeast(1)
        val enabled = state.fields.filter { it.enabled }
        val title = enabled.firstOrNull { it.isTitle }
            ?.let { newLayout(displayText(it, dynamic), titlePaint, textWidth, if (titleCenter) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL) }
        val rows = enabled.filter { !it.isTitle }.map { f ->
            val text = if (f.label.isBlank()) displayText(f, dynamic) else "${f.label}：${displayText(f, dynamic)}"
            newLayout(text, bodyPaint, textWidth)
        }
        return Texts(title, rows, titlePaint, bodyPaint, pad, blockWidth, corner)
    }

    private fun emptyBlock(): Block = Block(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    private fun withOpacity(color: Long, pct: Int): Long {
        val a = ((color ushr 24) and 0xFF).toInt() * pct.coerceIn(0, 100) / 100
        return ((a.toLong() and 0xFF) shl 24) or (color and 0x00FFFFFF)
    }

    /** 经典工程蓝：标题色条 + 正文黑块连续背景（原始样式） */
    private fun renderClassic(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = true)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val blockHeight = if (titleHeight > 0) {
            (titleHeight + bodyHeight + t.pad * 2).toInt().coerceAtLeast(1)
        } else {
            (bodyHeight + t.pad).toInt().coerceAtLeast(1)
        }
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(roundPath(t.blockWidth.toFloat(), blockHeight.toFloat(), t.corner))
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (titleHeight > 0) {
            bgPaint.color = style.titleBgColor.toInt()
            canvas.drawRect(0f, 0f, t.blockWidth.toFloat(), titleHeight + t.pad, bgPaint)
            // 记录点（录制指示灯）：标题条左端，金色光晕+实心圆（用户拍板 2026-10-12 默认模板自带）
            if (style.recDot) {
                val dotR = canvasWidth * style.titleFontSizeRatio * 0.16f * style.recDotSize
                val dotY = (titleHeight + t.pad) / 2f
                val dotX = t.pad + dotR * 1.4f
                val rec = style.recDotColor.toInt()
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rec }
                // 光晕（可调，0=不画）
                if (style.recGlow > 0f) {
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ((rec and 0x00FFFFFF) or (0x55 shl 24))
                    }
                    canvas.drawCircle(dotX, dotY, dotR * (1f + style.recGlow), glowPaint)
                }
                canvas.drawCircle(dotX, dotY, dotR, dotPaint)
            }
        }
        if (bodyHeight > 0) {
            bgPaint.color = style.bodyBgColor.toInt()
            canvas.drawRect(
                0f,
                (if (titleHeight > 0) titleHeight + t.pad else 0).toFloat(),
                t.blockWidth.toFloat(),
                blockHeight.toFloat(),
                bgPaint,
            )
        }
        var y = t.pad / 2f
        t.title?.let {
            canvas.save()
            canvas.translate(t.pad, (titleHeight + t.pad - it.height) / 2f)
            it.draw(canvas)
            canvas.restore()
            y += it.height + t.pad
        }
        t.rows.forEach { layout ->
            canvas.save()
            canvas.translate(t.pad, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
        }
        canvas.restore()
        return Block(bitmap)
    }

    /** 极简白影：无底色，标题强调色，白字投影，行间细分隔线 */
    private fun renderMinimal(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = false)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        t.titlePaint.color = style.accentColor.toInt()
        t.bodyPaint.setShadowLayer(canvasWidth * 0.008f, canvasWidth * 0.002f, canvasWidth * 0.002f, 0xB3000000)
        val titleHeight = t.title?.height ?: 0
        val sep = (canvasWidth * 0.004f).coerceAtLeast(2f)
        val pad = t.pad * 0.8f
        val blockHeight = (titleHeight + t.rows.sumOf { it.height.toDouble() } + sep * (t.rows.size).coerceAtLeast(0) + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        var y = pad
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ((0x66L shl 24) or (style.accentColor and 0x00FFFFFF)).toInt()
            strokeWidth = sep * 0.5f
        }
        t.title?.let {
            canvas.save(); canvas.translate(t.pad * 0.4f, y); it.draw(canvas); canvas.restore()
            y += it.height + sep
        }
        t.rows.forEachIndexed { i, layout ->
            canvas.save(); canvas.translate(t.pad * 0.4f, y); layout.draw(canvas); canvas.restore()
            y += layout.height
            if (i < t.rows.size - 1) {
                canvas.drawLine(t.pad * 0.4f, y + sep / 2f, t.blockWidth - t.pad * 0.4f, y + sep / 2f, linePaint)
                y += sep
            }
        }
        return Block(bitmap)
    }

    /** 边框卡片：半透明底 + 细边框圆角，标题强调色 */
    private fun renderBorderCard(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = false)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        t.titlePaint.color = style.accentColor.toInt()
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val gap = t.pad * 0.4f
        val blockHeight = (titleHeight + bodyHeight + gap * (if (titleHeight > 0) 3 else 2)).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(0f, 0f, t.blockWidth.toFloat(), blockHeight.toFloat())
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt() }
        canvas.drawRoundRect(rect, t.corner * 1.6f, t.corner * 1.6f, bg)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = (canvasWidth * 0.0035f).coerceAtLeast(2f)
            color = style.borderColor.toInt()
        }
        canvas.drawRoundRect(rect, t.corner * 1.6f, t.corner * 1.6f, border)
        var y = gap
        t.title?.let {
            canvas.save(); canvas.translate(t.pad, y); it.draw(canvas); canvas.restore()
            y += it.height + gap
        }
        t.rows.forEach { layout ->
            canvas.save(); canvas.translate(t.pad, y); layout.draw(canvas); canvas.restore()
            y += layout.height
        }
        return Block(bitmap)
    }

    /** 单行横排：所有字段一行小字，胶囊底，居中 */
    private fun renderSingleLine(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val width = canvasWidth.coerceAtLeast(1)
        val blockWidth = (width * style.maxWidthRatio).toInt().coerceAtLeast(1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = width * style.bodyFontSizeRatio * 0.85f
            typeface = Typeface.DEFAULT
        }
        val enabled = state.fields.filter { it.enabled }
        if (enabled.isEmpty()) return emptyBlock()
        val text = enabled.joinToString("  ·  ") { f ->
            val v = displayText(f, dynamic)
            if (f.label.isBlank()) v else "${f.label} $v"
        }
        val textWidth = (blockWidth - width * style.paddingRatio * 2).toInt().coerceAtLeast(1)
        val layout = newLayout(text, paint, textWidth)
        val padH = width * style.paddingRatio
        val padV = layout.height * 0.45f
        val blockHeight = (layout.height + padV * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, blockWidth.toFloat(), blockHeight.toFloat()), blockHeight / 2f, blockHeight / 2f, bg)
        canvas.save()
        canvas.translate(padH, padV)
        layout.draw(canvas)
        canvas.restore()
        return Block(bitmap)
    }

    /** 渐变底栏：自上而下黑色渐变带 + 白字（视频字幕感） */
    private fun renderGradient(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = false)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val pad = t.pad * 1.1f
        val blockHeight = (titleHeight + bodyHeight + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val maxA = (style.bgOpacity.coerceIn(0, 100)) / 100f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, blockHeight.toFloat(),
                ((0x00 * maxA).toInt() shl 24) or 0x000000,
                (((255 * maxA).toInt()) shl 24) or 0x000000,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, t.blockWidth.toFloat(), blockHeight.toFloat(), paint)
        var y = pad
        t.title?.let {
            canvas.save(); canvas.translate(t.pad, y); it.draw(canvas); canvas.restore()
            y += it.height + t.pad * 0.5f
        }
        t.rows.forEach { layout ->
            canvas.save(); canvas.translate(t.pad, y); layout.draw(canvas); canvas.restore()
            y += layout.height
        }
        return Block(bitmap)
    }

    /** 便签卡：白底黑字 + 左侧强调色竖条 */
    private fun renderNoteCard(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = false)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val barW = t.pad * 1.1f
        val pad = t.pad
        val blockHeight = (titleHeight + bodyHeight + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(roundPath(t.blockWidth.toFloat(), blockHeight.toFloat(), t.corner * 1.5f))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt() }
        canvas.drawRect(0f, 0f, t.blockWidth.toFloat(), blockHeight.toFloat(), bg)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accentColor.toInt() }
        canvas.drawRect(0f, 0f, barW, blockHeight.toFloat(), bar)
        var y = pad
        t.title?.let {
            canvas.save(); canvas.translate(barW + pad, y); it.draw(canvas); canvas.restore()
            y += it.height + pad * 0.5f
        }
        t.rows.forEach { layout ->
            canvas.save(); canvas.translate(barW + pad, y); layout.draw(canvas); canvas.restore()
            y += layout.height
        }
        canvas.restore()
        return Block(bitmap)
    }

    /** 记录仪：黑底等宽字体，红点 REC 标记，细边框 */
    private fun renderRecorder(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = false)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        t.titlePaint.typeface = Typeface.MONOSPACE
        t.bodyPaint.typeface = Typeface.MONOSPACE
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val pad = t.pad * 1.1f
        val dotR = canvasWidth * 0.007f
        val blockHeight = (titleHeight + bodyHeight + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, t.blockWidth.toFloat(), blockHeight.toFloat()), t.corner, t.corner, bg)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = ((0x55L shl 24) or (style.accentColor and 0x00FFFFFF)).toInt()
        }
        canvas.drawRoundRect(RectF(0f, 0f, t.blockWidth.toFloat(), blockHeight.toFloat()), t.corner, t.corner, border)
        var y = pad
        t.title?.let {
            val dotY = y + it.height / 2f
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accentColor.toInt() }
            canvas.drawCircle(t.pad + dotR, dotY, dotR, dot)
            canvas.save(); canvas.translate(t.pad + dotR * 3, y); it.draw(canvas); canvas.restore()
            y += it.height + pad * 0.5f
        }
        t.rows.forEach { layout ->
            canvas.save(); canvas.translate(t.pad, y); layout.draw(canvas); canvas.restore()
            y += layout.height
        }
        return Block(bitmap)
    }

    private fun roundPath(w: Float, h: Float, corner: Float): Path = Path().apply {
        addRoundRect(RectF(0f, 0f, w, h), corner, corner, Path.Direction.CW)
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
     * 网页模板：本函数运行在烧录线程（后台），可阻塞等待 WebView 渲染完成（超时回退经典布局）。
     *
     * @param rotationDeg 水印旋转角（预览/屏幕坐标系）
     * @param frameRotationDeg 成片相对屏幕坐标系的旋转角（EXIF 方向/视频旋转元数据）
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
        val tpl = WatermarkTemplates.find(state.templateId)
        val isWeb = tpl?.backend == TemplateBackend.WEB && tpl.html != null
        var renderState = state
        if (isWeb) {
            val key = webCacheKey(state, dynamic, minOf(canvasW, canvasH))
            if (key != null) WebTemplateRenderer.await(key)
            if (WebTemplateRenderer.current(key ?: "") == null) {
                // 超时兜底：回退经典布局，保证成片必有水印
                renderState = state.copy(
                    templateId = "classic",
                    style = state.style.copy(layout = "CLASSIC"),
                )
            }
        }
        val e = ((frameRotationDeg % 360) + 360) % 360
        val rad = Math.toRadians(e.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val vx = renderState.anchorX - 0.5f
        val vy = renderState.anchorY - 0.5f
        val ax = 0.5f + (vx * cos - vy * sin)
        val ay = 0.5f + (vx * sin + vy * cos)
        val rot = rotationDeg + e
        // 缩放基准取短边：预览恒为竖向 3:4（宽=短边），横拍成片显示为横向（宽=长边）。
        // 若按长边缩放，横屏水印会比预览大 4/3 倍；短边在两种方向下都对应预览宽度维度。
        val block = render(renderState, minOf(canvasW, canvasH), dynamic)
        if (block.width <= 1) return
        val (left, top) = positionBlock(canvasW, canvasH, block.width, block.height, ax, ay, rot)
        canvas.save()
        canvas.rotate(rot, left + block.width / 2f, top + block.height / 2f)
        canvas.drawBitmap(block.bitmap, left, top, null)
        canvas.restore()
        // 网页模板的位图归 WebTemplateRenderer 缓存所有，不能回收
        if (!isWeb || renderState !== state) block.bitmap.recycle()
    }


/** 记录点光晕色：同 RGB、35% 透明 */
private fun recColorGlow(rec: Int): Int = (rec and 0x00FFFFFF) or (0x59 shl 24)

    /** 字段当前显示文本：位置/时间标签有值（手动编辑）则优先生效，空则用拍照瞬间动态值。 */
    private fun displayText(f: WatermarkField, dynamic: DynamicValues): String = when (f.type) {
        FieldType.DATETIME -> f.value.ifBlank { dynamic.time }
        FieldType.LOCATION -> f.value.ifBlank { dynamic.location.ifBlank { "未获取定位" } }
        FieldType.COUNT -> f.value.filter { it.isDigit() }.ifBlank { "1" }
        FieldType.WEATHER -> f.value.ifBlank { dynamic.weather.ifBlank { "未获取天气" } }
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
