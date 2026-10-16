package com.engcamera.watermark

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
import com.engcamera.data.WatermarkStyle
import com.engcamera.data.WatermarkTemplates
import com.engcamera.data.stripLatLng
import com.engcamera.data.weatherEmojiPrefix
import kotlin.math.max
import kotlin.math.min
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
     * 液态玻璃的取景信息：成片/标注图位图 + 卡片在画面中的位置（未旋转块坐标）与最终旋转角。
     * renderGlassCard 据此采样卡片背后的画面做折射（CPU 移植 AndroidLiquidGlassView 的 AGSL）。
     */
    class FrameContext(
        val backdrop: Bitmap,
        val frameW: Int,
        val frameH: Int,
        val rotationDeg: Float,
        val cardLeft: Float,
        val cardTop: Float,
    )

    /**
     * 按目标画面宽度渲染整块水印。预览传预览区宽度，成片传照片宽度，
     * 同一份 state 会得到同比例的水印。
     * 网页模板：同步只取缓存（未就绪返回 1px 占位），预览层负责 warmAsync 触发渲染；
     * 烧录路径走 drawOnCanvas 内的 await（后台线程可阻塞）。
     * 液态玻璃模板：这里只出"纯文本块"（透明底）；玻璃卡由 LiquidGlassView（预览）
     * 或 renderGlassCard（成片/占位）单独渲染。
     */
    fun render(
        state: WatermarkState,
        canvasWidth: Int,
        dynamic: DynamicValues,
        blockWidthOverride: Int? = null,
    ): Block {
        val tpl = WatermarkTemplates.find(state.templateId)
        if (tpl != null && tpl.backend == TemplateBackend.WEB && tpl.html != null) {
            val key = WebTemplateRenderer.cacheKey(state, tpl, dynamic, canvasWidth)
            val bmp = WebTemplateRenderer.current(key)
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return Block(bmp)
        }
        return renderNative(state, canvasWidth, dynamic, blockWidthOverride)
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

    private fun renderNative(
        state: WatermarkState,
        canvasWidth: Int,
        dynamic: DynamicValues,
        blockWidthOverride: Int? = null,
    ): Block =
        when (state.style.layout) {
            "MINIMAL" -> renderMinimal(state, canvasWidth, dynamic)
            "BORDER_CARD" -> renderBorderCard(state, canvasWidth, dynamic)
            "SINGLE_LINE" -> renderSingleLine(state, canvasWidth, dynamic)
            "GRADIENT" -> renderGradient(state, canvasWidth, dynamic, blockWidthOverride)
            "NOTE_CARD" -> renderNoteCard(state, canvasWidth, dynamic)
            "RECORDER" -> renderRecorder(state, canvasWidth, dynamic)
            "LIQUID_GLASS" -> renderLiquidGlass(state, canvasWidth, dynamic)
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

    private fun measureTexts(
        state: WatermarkState,
        canvasWidth: Int,
        dynamic: DynamicValues,
        titleCenter: Boolean,
        fontScale: Float = 1f,
        blockWidthOverride: Int? = null,
    ): Texts {
        val style = state.style
        val width = canvasWidth.coerceAtLeast(1)
        // blockWidthOverride：拉满宽度的渐变底栏按目标画布方向的实际宽度排版（字号仍按短边基准）
        val blockWidth = blockWidthOverride?.coerceAtLeast(1)
            ?: (width * style.maxWidthRatio).toInt().coerceAtLeast(1)
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
            ?.let { newLayout(displayText(it, dynamic, style), titlePaint, textWidth, if (titleCenter) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL) }
        val rows = enabled.filter { !it.isTitle }.map { f ->
            val text = if (f.label.isBlank()) displayText(f, dynamic, style) else "${f.label}：${displayText(f, dynamic, style)}"
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
                val dotX = t.pad + dotR * 1.4f + t.blockWidth * style.recDotOffset
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
        // 标题+正文都加投影（用户反馈 2026-10-13：无底色白字在亮背景上要看清）
        val shadowBlur = canvasWidth * 0.010f
        val shadowOff = canvasWidth * 0.0025f
        t.titlePaint.setShadowLayer(shadowBlur, shadowOff, shadowOff, 0xCC000000.toInt())
        t.bodyPaint.setShadowLayer(shadowBlur, shadowOff, shadowOff, 0xCC000000.toInt())
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
            // 粗细可调（borderWidth 为相对标准线宽的倍数）
            strokeWidth = ((canvasWidth * 0.0035f).coerceAtLeast(2f)) * style.borderWidth.coerceAtLeast(0.25f)
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
            val v = displayText(f, dynamic, style)
            if (f.label.isBlank()) v else "${f.label} $v"
        }
        val textWidth = (blockWidth - width * style.paddingRatio * 2).toInt().coerceAtLeast(1)
        val layout = newLayout(text, paint, textWidth)
        val padH = width * style.paddingRatio
        // 上下留白收紧（此前 0.45 倍行高显得空旷，用户反馈 2026-10-13）
        val padV = layout.height * 0.15f
        val blockHeight = (layout.height + padV * 2).toInt().coerceAtLeast(1)
        // 圆角可调：cornerRatio 0..0.5，0.5=胶囊（原样式）
        val corner = (blockHeight * style.cornerRatio).coerceAtMost(blockHeight / 2f)
        val bitmap = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, blockWidth.toFloat(), blockHeight.toFloat()), corner, corner, bg)
        canvas.save()
        canvas.translate(padH, padV)
        layout.draw(canvas)
        canvas.restore()
        return Block(bitmap)
    }

    /** 渐变底栏：自上而下渐变带（基色可调）+ 文字；blockWidthOverride 用于拉满整张照片宽度 */
    private fun renderGradient(
        state: WatermarkState,
        canvasWidth: Int,
        dynamic: DynamicValues,
        blockWidthOverride: Int? = null,
    ): Block {
        val style = state.style
        val t = measureTexts(state, canvasWidth, dynamic, titleCenter = false, blockWidthOverride = blockWidthOverride)
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        val barWidth = blockWidthOverride?.coerceAtLeast(t.blockWidth) ?: t.blockWidth
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val pad = t.pad * 1.1f
        val blockHeight = (titleHeight + bodyHeight + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(barWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val maxA = (style.bgOpacity.coerceIn(0, 100)) / 100f
        // 渐变基色可调：顶部全透明 → 底部基色（默认黑，即原效果）
        val base = (style.gradientColor and 0x00FFFFFFL).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, blockHeight.toFloat(),
                base,
                ((255 * maxA).toInt() shl 24) or base,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, barWidth.toFloat(), blockHeight.toFloat(), paint)
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

    // ================= 液态玻璃（v158：接入 AndroidLiquidGlassView 库） =================
    // 预览：LiquidGlassView（库视图，GPU 实时）渲染玻璃卡 + 本渲染器的纯文本块叠加；
    // 成片/标注：renderGlassCard 用与库 AGSL（liquidglass_effect.agsl，MIT © QmDeve）同公式的
    // CPU 移植采样成片画面，保证保存效果与预览一致。
    // 库 dp 参数统一按 canvasWidth/360 等比换算成 px（≈设备密度在预览宽下的比例），
    // 预览与成片占比天然一致，适配横竖屏与不同分辨率。

    /**
     * 液态玻璃的文本块（透明底）：标题居中 + 分隔线 + 字段行，全部带投影。
     * 玻璃底不在此渲染（见 renderGlassCard / LiquidGlassView）。
     */
    private fun renderLiquidGlass(
        state: WatermarkState,
        canvasWidth: Int,
        dynamic: DynamicValues,
    ): Block {
        val style = state.style
        val width = canvasWidth.coerceAtLeast(1)
        val blockWidth = (width * style.maxWidthRatio).toInt().coerceAtLeast(1)
        val cornerPx = glassDpToPx(style.glassCornerRadius, width)
        // 基础边距（垂直+水平）固定；大圆角时按内切几何对首尾行额外水平内缩
        val baseInset = width * style.paddingRatio * 1.6f
        val t = measureTexts(
            state, width, dynamic, titleCenter = true,
            blockWidthOverride = (blockWidth - baseInset * 2).toInt().coerceAtLeast(1),
        )
        if (t.title == null && t.rows.isEmpty()) return emptyBlock()
        val titleHeight = t.title?.height ?: 0
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val sep = if (titleHeight > 0 && t.rows.isNotEmpty()) (width * 0.014f).coerceAtLeast(4f) else 0f
        val blockHeight = (titleHeight + sep + bodyHeight + baseInset * 2).toInt().coerceAtLeast(1)
        val w = blockWidth.toFloat()
        // 圆角钳制在长宽短边的 4/5：拉太大把文字拉出水印边缘（用户拍板 2026-10-16）
        val cornerClamped = cornerPx.coerceIn(1f, min(blockWidth, blockHeight) * 0.8f)

        // 圆角内切几何的行水平内缩：inset = r − √(r² − (r−y)²)，
        // y = 该行（含行高）距最近圆角边缘的垂直距离；y ≥ r（不在圆弧影响区）时为 0——
        // 首尾行自动避开圆弧，中间行只留基础边距；标题不参与（恒居中，用户拍板 2026-10-16）
        fun lineInset(y0: Float, lineH: Float): Float {
            val y = min(y0, blockHeight - (y0 + lineH)).coerceAtLeast(0f)
            if (y >= cornerClamped || cornerClamped <= 0f) return 0f
            val d = (cornerClamped - y).coerceIn(0f, cornerClamped)
            return cornerClamped - kotlin.math.sqrt(cornerClamped * cornerClamped - d * d)
        }

        val bitmap = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 阴影大小可调（0=无阴影）：手动双遍绘制（阴影遍 + 正文遍）——
        // Paint.setShadowLayer 在该路径用户实测无效（2026-10-16），BlurMaskFilter 必现
        val shadowBlur = if (style.textShadow > 0f) width * 0.014f * style.textShadow else 0f
        val shadowDy = if (style.textShadow > 0f) width * 0.004f * style.textShadow else 0f
        fun drawWithShadow(layout: StaticLayout, x: Float, y: Float) {
            if (shadowBlur > 0f) {
                val p = layout.paint as TextPaint
                val keepColor = p.color
                val keepFilter = p.maskFilter
                p.color = 0xB4000000.toInt()
                p.maskFilter = BlurMaskFilter(shadowBlur, BlurMaskFilter.Blur.NORMAL)
                canvas.save(); canvas.translate(x, y + shadowDy); layout.draw(canvas); canvas.restore()
                p.color = keepColor
                p.maskFilter = keepFilter
            }
            canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
        }
        var y = baseInset
        // 记录点（默认关，可开）：标题行左端，与经典模板同款光晕/大小/颜色参数
        if (style.recDot && titleHeight > 0) {
            val dotR = width * style.titleFontSizeRatio * 0.16f * style.recDotSize
            val dotY = y + titleHeight / 2f
            val dotX = baseInset + dotR * 1.4f + t.blockWidth * style.recDotOffset
            val rec = style.recDotColor.toInt()
            if (style.recGlow > 0f) {
                canvas.drawCircle(
                    dotX, dotY, dotR * (1f + style.recGlow),
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ((rec and 0x00FFFFFF) or (0x55 shl 24)) },
                )
            }
            canvas.drawCircle(dotX, dotY, dotR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rec })
        }
        t.title?.let {
            drawWithShadow(it, baseInset, y)
            y += it.height + sep * 0.5f
        }
        if (sep > 0f) {
            val li = lineInset(y + sep * 0.5f, 1f)
            canvas.drawLine(
                baseInset + li, y + sep * 0.5f, w - baseInset - li, y + sep * 0.5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    strokeWidth = (width * 0.0012f).coerceAtLeast(1f)
                    shader = LinearGradient(
                        baseInset + li, 0f, w - baseInset - li, 0f,
                        intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                },
            )
            y += sep
        }
        t.rows.forEach { layout ->
            drawWithShadow(layout, baseInset + lineInset(y, layout.height.toFloat()), y)
            y += layout.height
        }
        return Block(bitmap)
    }

    /** 库 dp 参数 → 当前画布 px */
    private fun glassDpToPx(dp: Float, canvasWidth: Int): Float = dp * canvasWidth / 360f

    /**
     * 渲染液态玻璃卡（成片烧录/标注预览/占位示意）。公式逐行对齐库的
     * liquidglass_effect.agsl：圆角矩形 SDF → 边缘折射带（circleMap×refractionOffset
     * 沿梯度内拉）→ 7 通道色散采样 → 着色；模糊与库一致先施加在采样内容上。
     * frame=null：无采样源——placeholder=true 画淡底+描边示意（编辑器/缩略图），
     * 否则仅着色（视频合成路径，默认 tintAlpha=0 即纯透明）。
     */
    fun renderGlassCard(
        state: WatermarkState,
        canvasWidth: Int,
        cardW: Int,
        cardH: Int,
        frame: FrameContext?,
        placeholder: Boolean = false,
    ): Bitmap {
        val style = state.style
        val width = canvasWidth.coerceAtLeast(1)
        // 圆角钳制在长宽短边的 4/5（与文本块同一规则，避免拉太大文字出水印边缘）
        val corner = glassDpToPx(style.glassCornerRadius, width)
            .coerceIn(1f, min(cardW, cardH) * 0.8f)
        val tintA = style.glassTintAlpha.coerceIn(0f, 1f)
        val tintR = style.glassTintRed.coerceIn(0f, 1f)
        val tintG = style.glassTintGreen.coerceIn(0f, 1f)
        val tintB = style.glassTintBlue.coerceIn(0f, 1f)
        val out = Bitmap.createBitmap(cardW, cardH, Bitmap.Config.ARGB_8888)
        if (frame == null) {
            val rect = RectF(0f, 0f, cardW.toFloat(), cardH.toFloat())
            val canvas = Canvas(out)
            val a = if (placeholder) maxOf(tintA, 0.10f) else tintA
            if (a > 0f) {
                canvas.drawRoundRect(rect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (((a * 255f).roundToInt()) shl 24) or
                        (((tintR * 255f).roundToInt()) shl 16) or
                        (((tintG * 255f).roundToInt()) shl 8) or
                        ((tintB * 255f).roundToInt())
                })
            }
            if (placeholder) {
                canvas.drawRoundRect(rect, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = (width * 0.002f).coerceAtLeast(1.2f)
                    color = 0x66FFFFFF
                })
            }
            return out
        }

        // ---- 实采样（CPU 版 AGSL） ----
        val f = frame
        val backdrop = f.backdrop
        if (backdrop.isRecycled || backdrop.width <= 0 || backdrop.height <= 0) return out
        val rh = glassDpToPx(style.glassRefractionHeight, width).coerceAtLeast(1f)
        val ra = -glassDpToPx(style.glassRefractionOffset, width) // 库内取负：向内折射
        val dispersion = style.glassDispersion.coerceIn(0f, 1f)
        val blurPx = glassDpToPx(style.glassBlurRadius, width)
        val rad = Math.toRadians(f.rotationDeg.toDouble())
        val cosR = kotlin.math.cos(rad).toFloat()
        val sinR = kotlin.math.sin(rad).toFloat()
        // 卡片旋转包围盒（±90° 宽高互换）+ 模糊余量 → backdrop 采样区（frame 坐标）
        val rot = ((f.rotationDeg.roundToInt() % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val bboxW = (if (swapped) cardH else cardW).toFloat()
        val bboxH = (if (swapped) cardW else cardH).toFloat()
        val margin = blurPx * 2f + 8f
        val cx0 = f.cardLeft + cardW / 2f
        val cy0 = f.cardTop + cardH / 2f
        val sx = backdrop.width.toFloat() / f.frameW
        val sy = backdrop.height.toFloat() / f.frameH
        val px0 = ((cx0 - bboxW / 2f - margin) * sx).toInt().coerceIn(0, backdrop.width - 2)
        val py0 = ((cy0 - bboxH / 2f - margin) * sy).toInt().coerceIn(0, backdrop.height - 2)
        val px1 = ((cx0 + bboxW / 2f + margin) * sx).toInt().coerceIn(px0 + 2, backdrop.width)
        val py1 = ((cy0 + bboxH / 2f + margin) * sy).toInt().coerceIn(py0 + 2, backdrop.height)
        val cw = px1 - px0
        val ch = py1 - py0
        // 直接按区域读取源像素（不走中间 Bitmap：子集位图可能共享像素缓冲，
        // 其像素契约在真机上踩出整卡纯黑的烧录结果——vivo 复现 2026-10-16）
        val contentPx = IntArray(cw * ch)
        runCatching { backdrop.getPixels(contentPx, 0, cw, px0, py0, cw, ch) }
            .onFailure { return out }
        // 模糊与库一致先施加在采样内容上（盒式模糊直接在像素数组上做）
        if (blurPx >= 1f) {
            val blurR = blurPx.roundToInt().coerceIn(1, (minOf(cw, ch) / 2).coerceAtLeast(1))
            val tmp = IntArray(cw * ch)
            repeat(2) {
                boxBlur(contentPx, tmp, cw, ch, blurR, horizontal = true)
                boxBlur(tmp, contentPx, cw, ch, blurR, horizontal = false)
            }
        }
        val halfW = cardW / 2f
        val halfH = cardH / 2f
        val outPx = IntArray(cardW * cardH)
        val depthEffect = 0.3f // 库 Config.DEPTH_EFFECT 常量
        // 卡片局部坐标 → 采样内容像素坐标（块随 rot 旋转绘制，背后画面按同一旋转取位）
        fun mapU(lx: Float, ly: Float): Float =
            ((cx0 + (lx - halfW) * cosR - (ly - halfH) * sinR) * sx) - px0
        fun mapV(lx: Float, ly: Float): Float =
            ((cy0 + (lx - halfW) * sinR + (ly - halfH) * cosR) * sy) - py0
        val rgba = FloatArray(4)
        for (y in 0 until cardH) {
            val cy = y + 0.5f - halfH
            for (x in 0 until cardW) {
                val cx = x + 0.5f - halfW
                val sd = sdRoundedRect(cx, cy, halfW, halfH, corner)
                if (sd > 0f) continue // 圆角外不渲染（对齐库视图的 clipPath）
                val idx = y * cardW + x
                if (-sd >= rh) {
                    // 内部区：直采内容 + 着色
                    unb(sampleBilinear(contentPx, cw, ch, mapU(x.toFloat(), y.toFloat()), mapV(x.toFloat(), y.toFloat())), rgba)
                    if (tintA > 0f) {
                        rgba[0] += (tintR - rgba[0]) * tintA
                        rgba[1] += (tintG - rgba[1]) * tintA
                        rgba[2] += (tintB - rgba[2]) * tintA
                    }
                    outPx[idx] = toOpaque(rgba)
                    continue
                }
                // 边缘折射带（对齐 shader：d = circleMap(1-(-sd)/rh) × refractionAmount，沿梯度）
                val distIn = -min(sd, 0f)
                val d = circleMap(1f - distIn / rh) * ra
                val smoothRadius = max(corner * 1.5f, 30f)
                val gradRadius = min(smoothRadius, min(halfW, halfH))
                val shape = gradSdRoundedRect(cx, cy, halfW, halfH, gradRadius)
                val depthLen = kotlin.math.sqrt(cx * cx + cy * cy)
                var gx: Float
                var gy: Float
                if (depthLen > 0.001f) {
                    gx = shape[0] + depthEffect * (cx / depthLen)
                    gy = shape[1] + depthEffect * (cy / depthLen)
                } else {
                    gx = shape[0]; gy = shape[1]
                }
                val gLen = kotlin.math.sqrt(gx * gx + gy * gy)
                if (gLen > 0.001f) { gx /= gLen; gy /= gLen }
                val rx = x + d * gx
                val ry = y + d * gy
                // 色散：折射偏移按角点系数缩放（对齐 shader 的 (cx*cy)/(halfW*halfH)）
                val intensity = dispersion * ((cx * cy) / (halfW * halfH))
                val ddx = d * gx * intensity
                val ddy = d * gy * intensity
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                // 7 通道采样（红/橙/黄/绿/青/蓝/紫，权重对齐 shader）
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx + ddx, ry + ddy), mapV(rx + ddx, ry + ddy)), rgba)
                r += rgba[0] / 3.5f; a += rgba[3] / 7f
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx + ddx * (2f / 3f), ry + ddy * (2f / 3f)), mapV(rx + ddx * (2f / 3f), ry + ddy * (2f / 3f))), rgba)
                r += rgba[0] / 3.5f; g += rgba[1] / 7f; a += rgba[3] / 7f
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx + ddx / 3f, ry + ddy / 3f), mapV(rx + ddx / 3f, ry + ddy / 3f)), rgba)
                r += rgba[0] / 3.5f; g += rgba[1] / 3.5f; a += rgba[3] / 7f
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx, ry), mapV(rx, ry)), rgba)
                g += rgba[1] / 3.5f; a += rgba[3] / 7f
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx - ddx / 3f, ry - ddy / 3f), mapV(rx - ddx / 3f, ry - ddy / 3f)), rgba)
                g += rgba[1] / 3.5f; b += rgba[2] / 3f; a += rgba[3] / 7f
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx - ddx * (2f / 3f), ry - ddy * (2f / 3f)), mapV(rx - ddx * (2f / 3f), ry - ddy * (2f / 3f))), rgba)
                b += rgba[2] / 3f; a += rgba[3] / 7f
                unb(sampleBilinear(contentPx, cw, ch, mapU(rx - ddx, ry - ddy), mapV(rx - ddx, ry - ddy)), rgba)
                r += rgba[0] / 7f; b += rgba[2] / 3f; a += rgba[3] / 7f
                if (tintA > 0f) {
                    r += (tintR - r) * tintA
                    g += (tintG - g) * tintA
                    b += (tintB - b) * tintA
                }
                rgba[0] = r; rgba[1] = g; rgba[2] = b; rgba[3] = a
                outPx[idx] = toOpaque(rgba)
            }
        }
        out.setPixels(outPx, 0, cardW, 0, 0, cardW, cardH)
        return out
    }

    // ---- AGSL 数学移植辅助（对齐 liquidglass_effect.agsl） ----

    private fun circleMap(x: Float): Float =
        1f - kotlin.math.sqrt((1f - x * x).coerceAtLeast(0f))

    private fun sdRoundedRect(cx: Float, cy: Float, halfW: Float, halfH: Float, r: Float): Float {
        val qx = kotlin.math.abs(cx) - (halfW - r)
        val qy = kotlin.math.abs(cy) - (halfH - r)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return kotlin.math.sqrt(ox * ox + oy * oy) - r + min(max(qx, qy), 0f)
    }

    private fun gradSdRoundedRect(cx: Float, cy: Float, halfW: Float, halfH: Float, r: Float): FloatArray {
        val qx = kotlin.math.abs(cx) - (halfW - r)
        val qy = kotlin.math.abs(cy) - (halfH - r)
        if (qx >= 0f || qy >= 0f) {
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            val len = kotlin.math.sqrt(ox * ox + oy * oy)
            if (len > 0.001f) {
                return floatArrayOf(kotlin.math.sign(cx) * ox / len, kotlin.math.sign(cy) * oy / len)
            }
            val useX = if (qy <= qx) 1f else 0f
            return floatArrayOf(useX * kotlin.math.sign(cx), (1f - useX) * kotlin.math.sign(cy))
        }
        val gradX = if (qy <= qx) 1f else 0f
        return floatArrayOf(kotlin.math.sign(cx) * gradX, kotlin.math.sign(cy) * (1f - gradX))
    }

    /** 双线性采样（premultiplied 像素直接插值，越界钳边） */
    private fun sampleBilinear(px: IntArray, w: Int, h: Int, u: Float, v: Float): Int {
        val x0f = u.toInt()
        val y0f = v.toInt()
        val fx = u - x0f
        val fy = v - y0f
        val xa = x0f.coerceIn(0, w - 1)
        val xb = (x0f + 1).coerceIn(0, w - 1)
        val ya = y0f.coerceIn(0, h - 1)
        val yb = (y0f + 1).coerceIn(0, h - 1)
        val p00 = px[ya * w + xa]; val p10 = px[ya * w + xb]
        val p01 = px[yb * w + xa]; val p11 = px[yb * w + xb]
        fun ch(q: Int, shift: Int): Int = (q shr shift) and 0xFF
        fun mix(a: Int, b: Int, t: Float): Int = a + ((b - a) * t).toInt()
        val av = mix(mix(p00 ushr 24, p10 ushr 24, fx), mix(p01 ushr 24, p11 ushr 24, fx), fy)
        val rv = mix(mix(ch(p00, 16), ch(p10, 16), fx), mix(ch(p01, 16), ch(p11, 16), fx), fy)
        val gv = mix(mix(ch(p00, 8), ch(p10, 8), fx), mix(ch(p01, 8), ch(p11, 8), fx), fy)
        val bv = mix(mix(ch(p00, 0), ch(p10, 0), fx), mix(ch(p01, 0), ch(p11, 0), fx), fy)
        return (av.coerceIn(0, 255) shl 24) or (rv.coerceIn(0, 255) shl 16) or
            (gv.coerceIn(0, 255) shl 8) or bv.coerceIn(0, 255)
    }

    /** premultiplied ARGB → 直 rgba (0..1) */
    private fun unb(p: Int, out: FloatArray) {
        val a = p ushr 24
        when {
            a == 0 -> { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f }
            a == 255 -> {
                out[0] = ((p shr 16) and 0xFF) / 255f
                out[1] = ((p shr 8) and 0xFF) / 255f
                out[2] = (p and 0xFF) / 255f
                out[3] = 1f
            }
            else -> {
                val f = 255f / a
                out[0] = (((p shr 16) and 0xFF) * f) / 255f
                out[1] = (((p shr 8) and 0xFF) * f) / 255f
                out[2] = ((p and 0xFF) * f) / 255f
                out[3] = a / 255f
            }
        }
    }

    /** 直 rgba (0..1) → 不透明 ARGB（玻璃卡内容恒不透明：规避 premultiplied 语义歧义） */
    private fun toOpaque(c: FloatArray): Int =
        (0xFF shl 24) or
            ((c[0].coerceIn(0f, 1f) * 255f).roundToInt() shl 16) or
            ((c[1].coerceIn(0f, 1f) * 255f).roundToInt() shl 8) or
            (c[2].coerceIn(0f, 1f) * 255f).roundToInt()

    /** 快速盒式模糊两轮近似高斯；小图上做，毫秒级 */
    private fun stackBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = radius.coerceIn(1, (minOf(w, h) / 2).coerceAtLeast(1))
        val cur = IntArray(w * h)
        val tmp = IntArray(w * h)
        src.getPixels(cur, 0, w, 0, 0, w, h)
        repeat(2) {
            boxBlur(cur, tmp, w, h, r, horizontal = true)
            boxBlur(tmp, cur, w, h, r, horizontal = false)
        }
        out.setPixels(cur, 0, w, 0, 0, w, h)
        return out
    }

    /** 单向盒式模糊（滑动窗口求和） */
    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val div = r * 2 + 1
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            var sa = 0
            var sb = 0
            var sg = 0
            var sr = 0
            for (i in -r..r) {
                val p = src[
                    if (horizontal) o * w + i.coerceIn(0, inner - 1)
                    else i.coerceIn(0, inner - 1) * w + o,
                ]
                sa += p ushr 24
                sb += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sr += p and 0xFF
            }
            for (j in 0 until inner) {
                // 先写本像素（当前窗口 [j-r..j+r] 的均值），再滑动窗口——
                // 反序会把整个结果偏移 1px（水平垂直各过一遍=斜向漂移）
                dst[
                    if (horizontal) o * w + j
                    else j * w + o,
                ] = ((sa / div) shl 24) or ((sb / div) shl 16) or ((sg / div) shl 8) or (sr / div)
                val addIdx = if (horizontal) o * w + (j + r).coerceIn(0, inner - 1)
                else (j + r).coerceIn(0, inner - 1) * w + o
                val subIdx = if (horizontal) o * w + (j - r - 1).coerceIn(0, inner - 1)
                else (j - r - 1).coerceIn(0, inner - 1) * w + o
                val add = src[addIdx]
                val sub = src[subIdx]
                sa += (add ushr 24) - (sub ushr 24)
                sb += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sr += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    /**
     * 锚点参照系换算：绕画面中心旋转 rollDeg（归一化 0..1 坐标，y-down，正角=顺时针）。
     * 重力参照系 = 画面按重力方向摆正后的坐标系——「下方」恒为物理重力方向，
     * 即成片按方向元数据正确显示出来时的坐标系。滚转 +90（逆时针横屏）时
     * 重力下方落在屏幕左缘：R(+90)·(0,+0.5) = (−0.5, 0)；反变换传 −rollDeg。
     */
    fun rotateAnchor(ax: Float, ay: Float, rollDeg: Float): Pair<Float, Float> {
        val norm = ((rollDeg.roundToInt() % 360) + 360) % 360
        if (norm == 0) return ax to ay
        val rad = Math.toRadians(norm.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val vx = ax - 0.5f
        val vy = ay - 0.5f
        return (0.5f + (vx * cos - vy * sin)) to (0.5f + (vx * sin + vy * cos))
    }

    /**
     * 根据锚点（归一化中心点）计算旋转后仍完整在画面内的块左上角（未旋转坐标系）。
     * edgeToEdge：拉满宽度的渐变底栏等，不做边缘安全边距（贴边铺满）。
     * extraBottomMarginPx：液态玻璃底部安全边距——下边界额外内收（画面 px）。
     * 锚点必须在目标画布自己的参照系里（成片=重力系；预览=先经 rotateAnchor 转屏幕系）。
     */
    fun positionBlock(
        canvasW: Int,
        canvasH: Int,
        blockW: Int,
        blockH: Int,
        anchorX: Float,
        anchorY: Float,
        rotationDeg: Float,
        edgeToEdge: Boolean = false,
        extraBottomMarginPx: Float = 0f,
    ): Pair<Float, Float> {
        // ±90° 时旋转后包围盒宽高互换；0/180° 不变
        val swapped = ((rotationDeg.roundToInt() % 180 + 180) % 180) == 90
        val bw = (if (swapped) blockH else blockW).coerceAtMost(canvasW)
        val bh = (if (swapped) blockW else blockH).coerceAtMost(canvasH)
        // 画面边缘留安全边距，水印不贴边（用户确认 2026-10-10）；拉满宽度模式贴边
        val margin = if (edgeToEdge) 0 else (canvasW * EDGE_MARGIN_RATIO).toInt().coerceAtLeast(4)
        val availW = (canvasW - margin * 2).coerceAtLeast(bw)
        val availH = (canvasH - margin * 2 - extraBottomMarginPx).coerceAtLeast(bh.toFloat())
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
     * backdrop：液态玻璃模板的实时采样背景（成片=照片本身/标注图本身），其他模板忽略。
     *
     * @param rotationDeg 水印旋转角（预览/屏幕坐标系）
     * @param frameRotationDeg 成片相对屏幕坐标系的旋转角（EXIF 方向/视频旋转元数据）
     * @param gravityAnchor 锚点参照系：true=state.anchorX/Y 为重力参照系（成片显示系，
     *      「下方」=物理重力方向，直接使用不旋转）；false=屏幕参照系（按 frameRotation 旋进成片系）
     */
    fun drawOnCanvas(
        canvas: Canvas,
        canvasW: Int,
        canvasH: Int,
        state: WatermarkState,
        dynamic: DynamicValues,
        rotationDeg: Float,
        frameRotationDeg: Int = 0,
        backdrop: Bitmap? = null,
        gravityAnchor: Boolean = false,
    ) {
        val tpl = WatermarkTemplates.find(state.templateId)
        val isWeb = tpl?.backend == TemplateBackend.WEB && tpl.html != null
        var renderState = state
        if (isWeb) {
            val key = webCacheKey(state, dynamic, minOf(canvasW, canvasH))
            if (key != null) {
                // 预览没渲过的尺寸（预览宽≠成片宽）在此补渲：烧录线程阻塞等待主线程 WebView 出图
                if (WebTemplateRenderer.current(key) == null) {
                    tpl?.let { WebTemplateRenderer.warmBlocking(key, it, state, dynamic, minOf(canvasW, canvasH)) }
                }
                WebTemplateRenderer.await(key)
            }
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
        // 锚点：重力参照系=成片显示系，直接使用；屏幕参照系按 frameRotation 旋进成片系。
        // 水印块旋转角仍需 +e（重力跟随开启时 rotationDeg=roll，roll+e≡0，成片里文字摆正）
        val vx = renderState.anchorX - 0.5f
        val vy = renderState.anchorY - 0.5f
        val ax = if (gravityAnchor) renderState.anchorX else 0.5f + (vx * cos - vy * sin)
        val ay = if (gravityAnchor) renderState.anchorY else 0.5f + (vx * sin + vy * cos)
        val rot = rotationDeg + e
        // 缩放基准取短边：预览恒为竖向 3:4（宽=短边），横拍成片显示为横向（宽=长边）。
        // 若按长边缩放，横屏水印会比预览大 4/3 倍；短边在两种方向下都对应预览宽度维度。
        val basis = minOf(canvasW, canvasH)
        // 渐变底栏拉满模式：块宽覆盖成片（按当前旋转后的长轴方向）整边，定位不做安全边距
        val fullBar = isFullWidthBar(renderState)
        // 液态玻璃：render 只出纯文本块，玻璃卡在定位后单独叠加（采样成片画面）
        val isLiquid = renderState.style.layout == "LIQUID_GLASS"
        val block = if (fullBar) {
            val vertical = ((rot.roundToInt() % 180 + 180) % 180) == 90
            render(renderState, basis, dynamic, blockWidthOverride = if (vertical) canvasH else canvasW)
        } else {
            render(renderState, basis, dynamic)
        }
        if (block.width <= 1) return
        // 网页位图按固定宽渲染（WebTemplateRenderer.RENDER_WIDTH），绘制时缩放到
        // 画面宽 × webScale 比例——预览与成片占比一致（native 位图 1:1 绘制）
        val webScaled = isWeb && renderState === state
        val drawW: Float
        val drawH: Float
        if (webScaled) {
            drawW = WebTemplateRenderer.webDrawFraction(renderState) * basis
            drawH = block.height * drawW / block.width
        } else {
            drawW = block.width.toFloat()
            drawH = block.height.toFloat()
        }
        val (left, top) = positionBlock(
            canvasW, canvasH, drawW.roundToInt(), drawH.roundToInt(), ax, ay, rot, edgeToEdge = fullBar,
        )
        // 液态玻璃：先画玻璃卡（CPU 版 AGSL 采样成片画面，公式与库一致），再画文本块
        if (isLiquid) {
            val glassFrame = if (backdrop != null && !backdrop.isRecycled) {
                FrameContext(backdrop, canvasW, canvasH, rot, left, top)
            } else {
                null
            }
            if (glassFrame != null || renderState.style.glassTintAlpha > 0f) {
                val glass = renderGlassCard(renderState, basis, drawW.roundToInt(), drawH.roundToInt(), glassFrame)
                canvas.save()
                canvas.rotate(rot, left + drawW / 2f, top + drawH / 2f)
                canvas.drawBitmap(glass, left, top, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                canvas.restore()
                glass.recycle()
            }
        }
        canvas.save()
        canvas.rotate(rot, left + drawW / 2f, top + drawH / 2f)
        if (webScaled) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(block.bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
        } else {
            canvas.drawBitmap(block.bitmap, left, top, null)
        }
        canvas.restore()
        // 网页模板的位图归 WebTemplateRenderer 缓存所有，不能回收
        if (!isWeb || renderState !== state) block.bitmap.recycle()
    }

    /** 渐变底栏"拉满整张照片宽度"模式：水印宽度滑杆拉到最大（>=99.9%） */
    fun isFullWidthBar(state: WatermarkState): Boolean =
        state.style.layout == "GRADIENT" && state.style.maxWidthRatio >= 0.999f


/** 记录点光晕色：同 RGB、35% 透明 */
private fun recColorGlow(rec: Int): Int = (rec and 0x00FFFFFF) or (0x59 shl 24)

    /** 字段当前显示文本：位置/时间标签有值（手动编辑）则优先生效，空则用拍照瞬间动态值。 */
    private fun displayText(f: WatermarkField, dynamic: DynamicValues, style: WatermarkStyle): String {
        val text = when (f.type) {
            FieldType.DATETIME -> f.value.ifBlank { dynamic.time }
            FieldType.LOCATION -> f.value.ifBlank {
                dynamic.location.ifBlank { "未获取定位" }
            }
            FieldType.COUNT -> f.value.filter { it.isDigit() }.ifBlank { "1" }
            FieldType.WEATHER -> f.value.ifBlank { dynamic.weather.ifBlank { "未获取天气" } }
            else -> f.value
        }
        // 位置文本经纬度开关：关闭时剥离尾部 "(lat, lng)" 段（手动值/自动值同规则）
        val located = if (f.type == FieldType.LOCATION && !style.showLatLng) stripLatLng(text) else text
        // 天气 emoji 开关：命中天气词时在文本首位加扁平 emoji（原生/网页模板同规则）
        return if (f.type == FieldType.WEATHER && style.weatherEmoji) {
            weatherEmojiPrefix(located) + located
        } else {
            located
        }
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
