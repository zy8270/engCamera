package com.engcamera.watermark

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
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
import com.engcamera.data.assembledLocation
import com.engcamera.data.stripLatLng
import com.engcamera.data.localizeDynamicPlaceholder
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

    /** 巡逻模板大号时间字体 Bahnschrift SemiBold（DIN 系数字，用户拍板 2026-11-07；
     *  v276 静态 SemiBold 子集（wght=600 实例化，仅数字+冒号 3KB）。
     *  【不可对 Bahnschrift 用 fakeBold】：可变字体加 fakeBold 会换更宽的粗体实例渲染，
     *  measureText 却按 Regular 度量——动态拉伸全乱（实测渲染比度量宽 22%） */
    @Volatile private var patrolTimeFont: Typeface? = null

    /** 巡逻固定标题「执勤巡逻」专用 Noto Sans SC Bold 700 子集（v269，仅四字 2.5KB）：
     *  DEFAULT_BOLD 对 CJK 无真 Bold 字重（多数 ROM 回退细体）。v272 起白字与阴影
     *  同用这一个字重（同字重纯放大才有均匀硬边环，Black900/fakeBold 均被推翻）。
     *  子集出自 Windows 自带 NotoSansSC-VF.ttf 实例化 */
    @Volatile private var patrolTitleBoldFont: Typeface? = null

    fun ensureFonts(context: android.content.Context) {
        if (patrolTimeFont == null) {
            patrolTimeFont = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/bahnschrift_semi.ttf")
            }.getOrNull()
        }
        if (patrolTitleBoldFont == null) {
            patrolTitleBoldFont = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/noto_sc_bold.ttf")
            }.getOrNull()
        }
    }

    /** 渲染好的整块水印。带阴影外沿的布局（今日·打卡/打卡1）：位图四周含透明外沿，
     *  offsetX/offsetY=内容区左上角在位图中的位置（两侧对称）——定位/钳位按内容区
     *  算（contentW/H），阴影允许越出定位包围盒（v249：外沿参与定位会把水印顶离屏幕边） */
    class Block(val bitmap: Bitmap, val offsetX: Int = 0, val offsetY: Int = 0) {
        val width: Int get() = bitmap.width
        val height: Int get() = bitmap.height
        val contentW: Int get() = bitmap.width - offsetX * 2
        val contentH: Int get() = bitmap.height - offsetY * 2
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
            "TODAY_CARD" -> renderTodayCard(state, canvasWidth, dynamic)
            "TODAY_CLOCK" -> renderTodayClock(state, canvasWidth, dynamic)
            "TODAY_CLOCK1" -> renderTodayClock1(state, canvasWidth, dynamic)
            "MARK_CARD" -> renderMarkCard(state, canvasWidth, dynamic, lightBody = false)
            "MARK_CARD_LIGHT" -> renderMarkCard(state, canvasWidth, dynamic, lightBody = true)
            "MARK_CLOCK" -> renderMarkClock(state, canvasWidth, dynamic)
            "PATROL" -> renderPatrol(state, canvasWidth, dynamic)
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
        // v0.6.16 品牌 logo：标题让出左侧容器宽（0=无 logo，行为不变）
        logoInset: Float = 0f,
        titleMaxLines: Int = 0,
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
            ?.let {
                newLayout(
                    displayText(it, dynamic, style), titlePaint,
                    (textWidth - logoInset).toInt().coerceAtLeast(1),
                    if (titleCenter) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL,
                    maxLines = titleMaxLines,
                )
            }
        val rows = enabled.filter { !it.isTitle }.map { f ->
            // 字段标签随界面语言（显示点翻译，存量数据不动；自定义标签词典未命中回退原文）
            val labelTxt = if (f.label.isBlank()) "" else com.engcamera.i18n.I18n.t(f.label)
            val text = if (labelTxt.isBlank()) displayText(f, dynamic, style) else "$labelTxt：${displayText(f, dynamic, style)}"
            newLayout(text, bodyPaint, textWidth)
        }
        return Texts(title, rows, titlePaint, bodyPaint, pad, blockWidth, corner)
    }

    /**
     * logo 容器边长（v0.6.16）：水印块宽 × style.logoSize，钳到 ≤半宽
     * （超半宽标题没地方，用户需求「最大不能超过水印宽度的 50%」）；无 logo 时 0。
     */
    private fun logoBoxSize(style: WatermarkStyle, blockW: Int, logo: Bitmap?): Float =
        if (logo == null || logo.isRecycled || logo.width <= 0) 0f
        else (blockW.toFloat() * style.logoSize).coerceIn(1f, blockW.toFloat() * 0.5f)

    /**
     * 画 logo 容器：白底正方形贴标题栏最左、垂直居中；logo 按原比例 fit 进容器
     * （内缩 8% 留白，用户需求「非正方形按原比例缩小到刚好放进容器」，不拉伸）。
     * 标题栏在调用方已 clipPath(圆角)，容器的外侧角自动被模板圆角裁切。
     */
    private fun drawLogoBox(canvas: Canvas, box: Float, barH: Float, logo: Bitmap) {
        if (box <= 1f) return
        val top = (barH - box) / 2f
        canvas.drawRect(
            0f, top, box, top + box,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() },
        )
        val avail = box * 0.84f
        val scale = minOf(avail / logo.width, avail / logo.height)
        val dw = logo.width * scale
        val dh = logo.height * scale
        val dx = (box - dw) / 2f
        val dy = top + (box - dh) / 2f
        canvas.drawBitmap(
            logo, null, RectF(dx, dy, dx + dw, dy + dh),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
    }

    private fun emptyBlock(): Block = Block(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

    /** 字段全关但 logo 开时的退化块：只有一条标题色带 + logo 容器（v0.6.16） */
    private fun logoOnlyBlock(blockW: Int, corner: Float, box: Float, logo: Bitmap, barColor: Long): Block {
        val h = (box + blockW.toFloat() * 0.02f).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(blockW, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.save()
        canvas.clipPath(roundPath(blockW.toFloat(), h.toFloat(), corner))
        canvas.drawRect(
            0f, 0f, blockW.toFloat(), h.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor.toInt() },
        )
        drawLogoBox(canvas, box, h.toFloat(), logo)
        canvas.restore()
        return Block(bmp)
    }

    private fun withOpacity(color: Long, pct: Int): Long {
        val a = ((color ushr 24) and 0xFF).toInt() * pct.coerceIn(0, 100) / 100
        return ((a.toLong() and 0xFF) shl 24) or (color and 0x00FFFFFF)
    }

    /** 经典工程蓝：标题色条 + 正文黑块连续背景（原始样式） */
    private fun renderClassic(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        // 品牌 logo（v0.6.16）：容器贴标题条最左，标题让出容器宽（先换行再省略）
        val logo = if (state.logoEnabled) com.engcamera.util.BrandLogo.current(state.logoFile) else null
        val blockW = (canvasWidth.coerceAtLeast(1) * style.maxWidthRatio).toInt().coerceAtLeast(1)
        val box = logoBoxSize(style, blockW, logo)
        val logoInset = if (box > 0f) box + canvasWidth * style.paddingRatio * 0.5f else 0f
        val t = measureTexts(
            state, canvasWidth, dynamic, titleCenter = true,
            logoInset = logoInset, titleMaxLines = if (box > 0f) 2 else 0,
        )
        if (t.title == null && t.rows.isEmpty()) {
            // 字段全关但 logo 开：只出 logo 标题条（用户选了 logo 就该看得见）
            if (box > 0f && logo != null) return logoOnlyBlock(t.blockWidth, t.corner, box, logo, style.titleBgColor)
            return emptyBlock()
        }
        val titleHeight = t.title?.height ?: 0
        // 标题条高：原高度，但有 logo 时至少容下容器（用户需求：把标题行顶高）
        val barH = if (titleHeight > 0) maxOf(titleHeight + t.pad, box) else box
        val bodyHeight = t.rows.sumOf { it.height.toDouble() }.toInt()
        val blockHeight = if (barH > 0f) {
            (barH + bodyHeight + t.pad).toInt().coerceAtLeast(1)
        } else {
            (bodyHeight + t.pad).toInt().coerceAtLeast(1)
        }
        val bitmap = Bitmap.createBitmap(t.blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(roundPath(t.blockWidth.toFloat(), blockHeight.toFloat(), t.corner))
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (barH > 0f) {
            bgPaint.color = style.titleBgColor.toInt()
            canvas.drawRect(0f, 0f, t.blockWidth.toFloat(), barH, bgPaint)
            if (box > 0f && logo != null) drawLogoBox(canvas, box, barH, logo)
            // 记录点（录制指示灯）：标题条左端，金色光晕+实心圆（用户拍板 2026-10-12 默认模板自带）
            // 有 logo 时让位（左侧已被容器占满）
            if (style.recDot && box <= 0f) {
                val dotR = canvasWidth * style.titleFontSizeRatio * 0.16f * style.recDotSize
                val dotY = barH / 2f
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
                barH,
                t.blockWidth.toFloat(),
                blockHeight.toFloat(),
                bgPaint,
            )
        }
        var y = t.pad / 2f
        t.title?.let {
            canvas.save()
            canvas.translate(t.pad + logoInset, (barH - it.height) / 2f)
            it.draw(canvas)
            canvas.restore()
            y = barH + t.pad / 2f
        }
        // 标题关但有 logo：正文行也必须让开 logo 标题条（否则压在 logo 上）
        if (t.title == null && box > 0f) y = barH + t.pad / 2f
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
        val padH = width * style.paddingRatio
        // 圆角内切行内缩（液态玻璃同款文字排列算法，用户需求 2026-10-17）：大圆角/
        // 胶囊时行按其与圆弧的垂直距离水平内缩，文字不压两端弧线。v235 修右端溢出：
        // v234 只把行向右平移了内缩量，测量宽度未变——文字填满时右端正好超出内缩
        // 量压出弧线；现按最紧行的内缩量把测量宽度左右两侧收窄后重排（长文本宁可
        // 换行不出界），几何随换行结果重算，最多迭代 3 轮防极端抖动
        val baseTextWidth = (blockWidth - padH * 2).toInt().coerceAtLeast(1)
        var measured = baseTextWidth
        var layout = newLayout(text, paint, measured)
        var padV = 0f
        var blockHeight = 1
        // 圆角可调：cornerRatio 0..0.5，0.5=胶囊（原样式）
        var corner = 1f
        var insets = FloatArray(1)
        var iter = 0
        while (true) {
            // 上下留白收紧（此前 0.45 倍行高显得空旷，用户反馈 2026-10-13）
            padV = layout.height * 0.15f
            blockHeight = (layout.height + padV * 2).toInt().coerceAtLeast(1)
            corner = (blockHeight * style.cornerRatio).coerceAtMost(blockHeight / 2f)
            var worst = 0f
            var maxLineW = 0f
            insets = FloatArray(layout.lineCount)
            for (i in insets.indices) {
                val top = layout.getLineTop(i)
                val h = (layout.getLineBottom(i) - top).toFloat()
                insets[i] = cornerLineInset(padV + top, h, blockHeight.toFloat(), corner)
                if (insets[i] > worst) worst = insets[i]
                maxLineW = maxOf(maxLineW, layout.getLineWidth(i))
            }
            val avail = (baseTextWidth - worst * 2).toInt().coerceAtLeast(1)
            if (maxLineW <= avail || ++iter >= 3) break
            measured = avail
            layout = newLayout(text, paint, measured)
        }
        val bitmap = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt() }
        canvas.drawRoundRect(RectF(0f, 0f, blockWidth.toFloat(), blockHeight.toFloat()), corner, corner, bg)
        // 逐行绘制（各行按各自与圆弧的垂直距离内缩），条带 clip 只留本行
        for (i in 0 until layout.lineCount) {
            val top = layout.getLineTop(i)
            val h = (layout.getLineBottom(i) - top).toFloat()
            canvas.save()
            canvas.translate(padH + insets[i], padV)
            canvas.clipRect(0f, top.toFloat(), blockWidth.toFloat(), top + h + 1f)
            layout.draw(canvas)
            canvas.restore()
        }
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

    // ================= 打卡/工程记录系列（2026-10-30：像素级仿参考图五款） =================
    // 几何/配色常量 = 1920 宽参考图实测 px ÷ 1920；字体保持应用默认（不仿字体），
    // 字号取参考字宽/字高估计中值。标签两端对齐（2~4 字 CJK 均布在固定字宽格）。

    /** 正文行描述：label=已翻译标签（CJK 才走两端对齐）；value=值列布局（悬挂缩进在值列）；
     *  natural=非 CJK 标签时"标签：值"整行自然布局 */
    private data class LRow(val label: String, val value: StaticLayout, val natural: StaticLayout?)

    /** 马克卡片条目：普通文字行或底部输入条（互斥） */
    private data class MEntry(val row: LRow?, val noteBar: Boolean = false)

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        "yyyy/M/d HH:mm:ss", "yyyy/M/d HH:mm", "yyyy/M/d H:mm", "yyyy/M/d",
    )

    /** 解析出的时间部件（dateDash=2026-10-30 / dateDot=2026.10.30 / dateSlash=2026/10/30） */
    private class TimeParts(
        val dateDash: String,
        val dateDot: String,
        val dateSlash: String,
        val hm: String,
        val weekday: String,
    )

    private fun parseTimeParts(raw: String): TimeParts? {
        if (raw.isBlank()) return null
        for (pattern in TIME_PATTERNS) {
            try {
                val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA)
                fmt.isLenient = false
                val d = fmt.parse(raw.trim()) ?: continue
                val cal = java.util.Calendar.getInstance(java.util.Locale.CHINA).apply { time = d }
                val year = cal.get(java.util.Calendar.YEAR)
                val month = cal.get(java.util.Calendar.MONTH) + 1
                val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = cal.get(java.util.Calendar.MINUTE)
                val wd = when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                    java.util.Calendar.SUNDAY -> "星期日"
                    java.util.Calendar.MONDAY -> "星期一"
                    java.util.Calendar.TUESDAY -> "星期二"
                    java.util.Calendar.WEDNESDAY -> "星期三"
                    java.util.Calendar.THURSDAY -> "星期四"
                    java.util.Calendar.FRIDAY -> "星期五"
                    else -> "星期六"
                }
                return TimeParts(
                    dateDash = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day),
                    dateDot = String.format(java.util.Locale.US, "%04d.%02d.%02d", year, month, day),
                    dateSlash = String.format(java.util.Locale.US, "%04d/%02d/%02d", year, month, day),
                    hm = String.format(java.util.Locale.US, "%02d:%02d", hour, minute),
                    weekday = wd,
                )
            } catch (_: Throwable) {
                // 试下一个格式
            }
        }
        return null
    }

    /** DATETIME 字段时间部件：手动值优先，空则拍照动态值；解析不了返回 null */
    private fun timePartsOf(state: WatermarkState, dynamic: DynamicValues): TimeParts? {
        val f = state.fields.firstOrNull { it.type == FieldType.DATETIME }
        val raw = f?.value?.ifBlank { dynamic.time } ?: dynamic.time
        return parseTimeParts(raw)
    }

    /** DATETIME 字段原文本（手动值优先，空则动态值） */
    private fun timeRawOf(state: WatermarkState, dynamic: DynamicValues): String {
        val f = state.fields.firstOrNull { it.type == FieldType.DATETIME }
        return f?.value?.ifBlank { dynamic.time } ?: dynamic.time
    }

    /** 可两端对齐的标签：纯 CJK（可含数字，如"备注1"——用户反馈 2026-10-31 带数字字段也要
     *  两端对齐）且 ≤4 字；其余（拉丁字母等）走自然排版 */
    private fun isCjkLabel(label: String): Boolean =
        label.isNotEmpty() && label.length <= 4 &&
            label.all { it.code in 0x4E00..0x9FFF || it in '0'..'9' }

    /** 标签两端对齐：2~4 字 CJK 在 cellW 格内均匀分布；不适合返回 false（调用方走自然排版） */
    private fun drawJustifiedLabel(
        canvas: Canvas,
        label: String,
        paint: TextPaint,
        left: Float,
        baseline: Float,
        cellW: Float,
    ): Boolean {
        if (!isCjkLabel(label) || label.length > 4) return false
        val chars = label.toCharArray()
        val widths = FloatArray(chars.size)
        var ink = 0f
        for (i in chars.indices) {
            // index/count 语义：第三个参数是个数不是结束索引
            widths[i] = paint.measureText(chars, i, 1)
            ink += widths[i]
        }
        val extra = cellW - ink
        if (extra < 0f) return false
        val gap = if (chars.size > 1) extra / (chars.size - 1) else 0f
        var x = left
        for (i in chars.indices) {
            // 注意：index/count 语义——第三个参数是个数不是结束索引
            canvas.drawText(chars, i, 1, x, baseline, paint)
            x += widths[i] + gap
        }
        return true
    }

    /** 位置文本（"地址 (21.148223, 110.317905)" 或纯坐标）→ "21.148223°N,110.317905°E"；无坐标 null */
    private fun coordsText(location: String): String? {
        val m = Regex("([+-]?\\d+(?:\\.\\d+)?)\\s*,\\s*([+-]?\\d+(?:\\.\\d+)?)").find(location)
            ?: return null
        val latS = m.groupValues[1]
        val lngS = m.groupValues[2]
        val lat = latS.toFloatOrNull() ?: return null
        val lng = lngS.toFloatOrNull() ?: return null
        // 误命中防御：地址里普通数字对（无小数且数值小）不算坐标
        fun plausible(s: String, v: Float) = s.contains('.') || kotlin.math.abs(v) >= 10f
        if (!plausible(latS, lat) || !plausible(lngS, lng)) return null
        if (kotlin.math.abs(lat) > 90f || kotlin.math.abs(lng) > 180f) return null
        val ns = if (lat >= 0f) "N" else "S"
        val ew = if (lng >= 0f) "E" else "W"
        return String.format(
            java.util.Locale.US, "%.6f°%s,%.6f°%s",
            kotlin.math.abs(lat), ns, kotlin.math.abs(lng), ew,
        )
    }

    /** 白字投影（无底色布局在亮背景上要保持可读） */
    private fun TextPaint.textShadow(w: Float, alpha: Int = 0x99) {
        setShadowLayer(w * 0.008f, w * 0.002f, w * 0.002f, (alpha shl 24) or 0x000000)
    }

    /** 水印内容背后的椭圆放射阴影（今日·打卡/今日·打卡1，模板选项拉杆可调 v249）：
     *  长短轴半径=内容对应边×softShadowW/H，中心深浅=softShadowAlpha、中间档停靠
     *  softShadowSpread，到半径处完全透明；marginX/marginY=位图四周预留外沿
     *  （=(倍率-0.5)×内容边，由调用方按当前倍率预留）。
     *  渐变中心必须挂平移后的原点(0,0)：挂未变换坐标会被画布矩阵再乘一次推到右下角 */
    private fun drawSoftShadow(
        canvas: Canvas,
        blockW: Float,
        blockH: Float,
        marginX: Float,
        marginY: Float,
        style: WatermarkStyle,
    ) {
        val cw = blockW - marginX * 2f
        val ch = blockH - marginY * 2f
        if (cw <= 0f || ch <= 0f) return
        val rx = cw * style.softShadowW.coerceIn(0.3f, 1.5f)
        val ry = ch * style.softShadowH.coerceIn(0.3f, 1.5f)
        val a = (style.softShadowAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val mid = style.softShadowSpread.coerceIn(0.2f, 0.9f)
        val rgb = (style.softShadowColor and 0x00FFFFFFL).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(
                    (a shl 24) or rgb,
                    ((a * 0.31f).roundToInt() shl 24) or rgb,
                    0,
                ),
                floatArrayOf(0f, mid, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.save()
        canvas.translate(blockW / 2f, blockH / 2f)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    /** 小人图标（实心头+肩，马克·打卡"拍摄人"行） */
    private fun drawPersonIcon(canvas: Canvas, left: Float, top: Float, height: Float, tint: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
        val cx = left + height * 0.5f
        canvas.drawCircle(cx, top + height * 0.26f, height * 0.21f, paint)
        canvas.drawArc(
            RectF(cx - height * 0.44f, top + height * 0.52f, cx + height * 0.44f, top + height * 1.16f),
            180f, 180f, true, paint,
        )
    }

    /** 今日·工程：蓝标题条（左圆点+居中标题）+ 半透明浅色正文、深色文字、标签两端对齐 */
    private fun renderTodayCard(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        val blockW = (w * style.maxWidthRatio).toInt().coerceAtLeast(1)
        // 实测（1920）：卡 1008、标题条 115、圆角 26、标题字 60、正文字 48、行距 86、左内边距 36
        val barH = w * 0.0599f
        val corner = (w * style.cornerRatio).coerceAtLeast(1f)
        val padL = w * 0.0188f
        val padR = w * 0.0188f
        val padV = w * 0.0104f
        val labelCell = w * 0.131f
        val rowGap = w * 0.0146f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.titleTextColor.toInt()
            textSize = w * 0.0323f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = w * 0.026f
        }
        val enabled = state.fields.filter { it.enabled }
        val titleText = enabled.firstOrNull { it.isTitle }?.let { displayText(it, dynamic, style) } ?: ""
        val hasBar = titleText.isNotBlank()
        // 品牌 logo（v0.6.16）：容器贴标题条最左，标题区=剩余宽度，居中（先换行再省略）
        val logo = if (state.logoEnabled) com.engcamera.util.BrandLogo.current(state.logoFile) else null
        val box = logoBoxSize(style, blockW, logo)
        val titleW = if (box > 0f) (blockW - box - w * 0.006f).coerceAtLeast(blockW * 0.3f)
        else blockW.toFloat()
        val title = if (hasBar) {
            newLayout(
                titleText, titlePaint, titleW.toInt().coerceAtLeast(1),
                Layout.Alignment.ALIGN_CENTER, maxLines = if (box > 0f) 2 else 0,
            )
        } else null
        val colonW = bodyPaint.measureText("：")
        val valueX = padL + labelCell + colonW
        val valueW = (blockW - valueX - padR).toInt().coerceAtLeast(1)
        val naturalW = (blockW - padL - padR).toInt().coerceAtLeast(1)
        val rows = enabled.filter { !it.isTitle }.map { f ->
            val labelTxt = if (f.label.isBlank()) "" else com.engcamera.i18n.I18n.t(f.label)
            val value = newLayout(displayText(f, dynamic, style), bodyPaint, valueW)
            val natural = if (labelTxt.isNotEmpty() && !isCjkLabel(labelTxt)) {
                newLayout("${labelTxt}：${displayText(f, dynamic, style)}", bodyPaint, naturalW)
            } else null
            LRow(labelTxt, value, natural)
        }
        if (title == null && rows.isEmpty()) {
            if (box > 0f && logo != null) return logoOnlyBlock(blockW, corner, box, logo, style.titleBgColor)
            return emptyBlock()
        }
        // 标题条高：原高度；有 logo 时至少容下容器，且两行标题不能溢出条外
        val actualBarH = if (hasBar) maxOf(barH, box, title!!.height + w * 0.008f) else box
        val bodyH = padV * 2 + rows.sumOf { (it.natural ?: it.value).height } +
            rowGap * (rows.size - 1).coerceAtLeast(0)
        val blockH = (actualBarH + bodyH).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(roundPath(blockW.toFloat(), blockH.toFloat(), corner))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        var top = 0f
        if (actualBarH > 0f) {
            bg.color = style.titleBgColor.toInt()
            canvas.drawRect(0f, 0f, blockW.toFloat(), actualBarH, bg)
            if (box > 0f && logo != null) {
                drawLogoBox(canvas, box, actualBarH, logo)
            } else if (style.recDot) {
                canvas.drawCircle(
                    w * 0.0286f, actualBarH / 2f, w * 0.0078f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.recDotColor.toInt() },
                )
            }
            title?.let { tl ->
                canvas.save()
                canvas.translate(if (box > 0f) box + w * 0.006f else 0f, (actualBarH - tl.height) / 2f)
                tl.draw(canvas)
                canvas.restore()
            }
            top = actualBarH
        }
        bg.color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt()
        canvas.drawRect(0f, top, blockW.toFloat(), blockH.toFloat(), bg)
        var y = top + padV
        rows.forEachIndexed { i, row ->
            when {
                row.natural != null -> {
                    canvas.save(); canvas.translate(padL, y); row.natural.draw(canvas); canvas.restore()
                    y += row.natural.height
                }
                row.label.isEmpty() -> {
                    canvas.save(); canvas.translate(padL, y); row.value.draw(canvas); canvas.restore()
                    y += row.value.height
                }
                else -> {
                    val baseline = y + row.value.getLineBaseline(0)
                    if (!drawJustifiedLabel(canvas, row.label, bodyPaint, padL, baseline, labelCell)) {
                        canvas.drawText(row.label, padL, baseline, bodyPaint)
                    }
                    canvas.drawText("：", padL + labelCell, baseline, bodyPaint)
                    canvas.save(); canvas.translate(valueX, y); row.value.draw(canvas); canvas.restore()
                    y += row.value.height
                }
            }
            if (i < rows.lastIndex) y += rowGap
        }
        canvas.restore()
        return Block(bitmap)
    }

    /** 今日·打卡：无底色白字投影——大号时间 + 强调色竖条 + 日期/星期两行 + 地址 +
     *  备注渐变条（白→透明底）；水印块背后浅色软阴影（参考图 2026-10-31） */
    private fun renderTodayClock(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        val leftPad = w * 0.011f
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = w * 0.096f
            typeface = Typeface.DEFAULT_BOLD
            textShadow(w)
        }
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = w * 0.0365f
            typeface = Typeface.DEFAULT_BOLD
            textShadow(w)
        }
        val addrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = w * 0.0323f
            textShadow(w)
        }
        val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = w * 0.0345f
            typeface = Typeface.DEFAULT_BOLD
            textShadow(w)
        }
        val parts = timePartsOf(state, dynamic)
        val timeText = parts?.hm ?: timeRawOf(state, dynamic)
        val timeW = timePaint.measureText(timeText).coerceAtLeast(1f)
        val timeSpacing = timePaint.fontSpacing
        // 右列（日期/星期）：实测条高 168 ≈ 日期+星期两行盒高，行盒间隙 17；
        // 天气字段（默认关，用户需求 2026-10-31）开启时追加在星期行后（参考图"星期六 晴 30℃"）
        val dateText = parts?.dateDash
        val weatherText = state.fields
            .firstOrNull { it.type == FieldType.WEATHER && it.enabled }
            ?.let { displayText(it, dynamic, style) }
            .orEmpty()
        val weekdayText = parts?.weekday?.let { com.engcamera.i18n.I18n.t(it) }?.let { wd ->
            if (weatherText.isBlank()) wd else "$wd $weatherText"
        }
        val hasCol = dateText != null
        val colGap = w * 0.009f
        val colSpacing = datePaint.fontSpacing
        val dateW = if (hasCol) datePaint.measureText(dateText!!) else 0f
        val weekW = if (weekdayText != null) datePaint.measureText(weekdayText) else 0f
        val colW = maxOf(dateW, weekW)
        val colH = if (hasCol) colSpacing * 2 + colGap else 0f
        val barW = w * 0.0073f
        val gapTimeBar = w * 0.023f
        val gapBarDate = w * 0.022f
        val row1W = leftPad + timeW + gapTimeBar + (if (hasCol) barW + gapBarDate + colW else 0f)
        val row1H = maxOf(timeSpacing, colH)
        val locField = state.fields.firstOrNull { it.type == FieldType.LOCATION && it.enabled }
        val addrText = locField?.let { displayText(it, dynamic, style) } ?: ""
        val hasAddr = addrText.isNotBlank()
        // 地址宽度封顶 0.5W（过长换行，不撑爆画面）
        val addrWanted = if (hasAddr) minOf(addrPaint.measureText(addrText), w * 0.5f - leftPad) else 0f
        // 备注行：每个启用的备注字段一行「标签：值」，多条全展示（用户反馈 2026-10-31）；
        // 共享一条 左→右「透白→透明」横向渐变圆角底（不再上→下，且整体更透），值空整行隐藏
        val noteCapW = (w * 0.5f - leftPad).toInt().coerceAtLeast(1)
        val noteLayouts = state.fields.filter { it.enabled && it.type == FieldType.NOTE }.mapNotNull { f ->
            val v = displayText(f, dynamic, style)
            if (v.isBlank()) return@mapNotNull null
            val label = if (f.label.isBlank()) "" else com.engcamera.i18n.I18n.t(f.label)
            val text = if (label.isEmpty()) v else "$label：$v"
            val probe = newLayout(text, notePaint, noteCapW)
            val needed = (0 until probe.lineCount).maxOf { probe.getLineWidth(it) }.toInt() + 2
            newLayout(text, notePaint, needed.coerceAtMost(noteCapW))
        }
        val noteMaxW = noteLayouts.maxOfOrNull { it.width } ?: 0
        val noteLineGap = w * 0.004f
        val notesH = noteLayouts.sumOf { it.height } + noteLineGap * (noteLayouts.size - 1).coerceAtLeast(0)
        val notePadH = w * 0.009f
        val notePadTop = w * 0.007f
        val notePadBottom = w * 0.016f
        val blockWContent = maxOf(
            row1W,
            if (hasAddr) leftPad + addrWanted else 0f,
            if (noteLayouts.isNotEmpty()) leftPad + noteMaxW + notePadH * 2 else 0f,
        )
        val gapAddr = w * 0.02f
        val gapNote = w * 0.016f
        val addrLayout = if (hasAddr) {
            newLayout(addrText, addrPaint, (blockWContent - leftPad).toInt().coerceAtLeast(1))
        } else {
            null
        }
        val blockHContent = row1H +
            (if (addrLayout != null) gapAddr + addrLayout.height else 0f) +
            (if (noteLayouts.isNotEmpty()) gapNote + notePadTop + notesH + notePadBottom else 0f)
        // 阴影外沿：椭圆半径=内容边×倍率（模板选项可调），超出内容 (倍率-0.5)×边长
        val mx = (blockWContent * (style.softShadowW.coerceIn(0.3f, 1.5f) - 0.5f))
            .coerceAtLeast(w * 0.005f)
        val my = (blockHContent * (style.softShadowH.coerceIn(0.3f, 1.5f) - 0.5f))
            .coerceAtLeast(w * 0.005f)
        val blockW = (blockWContent + mx * 2).toInt().coerceAtLeast(1)
        val blockH = (blockHContent + my * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawSoftShadow(canvas, blockW.toFloat(), blockH.toFloat(), mx, my, style)
        canvas.save()
        canvas.translate(mx, my)
        val row1Center = row1H / 2f
        // 时间（盒内垂直居中）
        val timeFm = timePaint.fontMetrics
        canvas.drawText(timeText, leftPad, row1Center + (timeFm.descent - timeFm.ascent) / 2f - timeFm.descent, timePaint)
        if (hasCol) {
            val barX = leftPad + timeW + gapTimeBar
            val barTop = row1Center - colH / 2f
            canvas.drawRect(barX, barTop, barX + barW, barTop + colH, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = style.accentColor.toInt()
            })
            val colX = barX + barW + gapBarDate
            val colTop = row1Center - colH / 2f
            val dateFm = datePaint.fontMetrics
            canvas.drawText(dateText!!, colX, colTop - dateFm.ascent, datePaint)
            if (weekdayText != null) {
                canvas.drawText(weekdayText, colX, colTop + colSpacing + colGap - dateFm.ascent, datePaint)
            }
        }
        var y = row1H
        if (addrLayout != null) {
            canvas.save()
            canvas.translate(leftPad, y + gapAddr)
            addrLayout.draw(canvas)
            canvas.restore()
            y += gapAddr + addrLayout.height
        }
        if (noteLayouts.isNotEmpty()) {
            // 左→右「透白→透明」横向渐变圆角底（方向/透明度按用户反馈 2026-10-31 调整）
            val bgTop = y + gapNote
            val bgW = noteMaxW + notePadH * 2
            val bgH = notePadTop + notesH + notePadBottom
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    leftPad, 0f, leftPad + bgW, 0f,
                    0x59FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(
                RectF(leftPad, bgTop, leftPad + bgW, bgTop + bgH),
                w * 0.008f, w * 0.008f, bgPaint,
            )
            var ny = bgTop + notePadTop
            noteLayouts.forEach { l ->
                canvas.save()
                canvas.translate(leftPad + notePadH, ny)
                l.draw(canvas)
                canvas.restore()
                ny += l.height + noteLineGap
            }
        }
        canvas.restore()
        // 携带内容区偏移：定位/钳位按内容区算，阴影允许越出（v249 修"水印被顶离屏幕边"）
        return Block(bitmap, mx.roundToInt(), my.roundToInt())
    }

    /**
     * 今日·打卡1（参考图 2026-10-31）：纹章纹卡片「打卡」黄徽标+蓝黑渐变外发光时间、
     * 强调色竖条+地点/日期组、透白渐变字段卡（拍摄人/备注/工作单位，空值整行隐藏、
     * 全空收卡）、盾牌验证行；水印块背后椭圆放射阴影。
     */
    private fun renderTodayClock1(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        val leftPad = w * 0.011f
        val accent = style.accentColor.toInt()
        val white = style.bodyTextColor.toInt()
        val timeBlue = 0xFF1E6FDB.toInt()
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = timeBlue
            textSize = w * 0.042f
            typeface = Typeface.DEFAULT_BOLD
            // 外发光（用户反馈 2026-10-31）：偏移 0 的浅蓝光晕；蓝→黑渐变在绘制处按文字盒再挂 shader
            setShadowLayer(w * 0.012f, 0f, 0f, 0x7A82B4F0.toInt())
        }
        val badgeText = com.engcamera.i18n.I18n.t("打卡")
        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF202328.toInt()
            textSize = w * 0.030f
            typeface = Typeface.DEFAULT_BOLD
        }
        val groupPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = w * 0.0325f
            typeface = Typeface.DEFAULT_BOLD
            textShadow(w)
        }
        // 字段行无阴影（用户反馈 2026-10-31：参考图整体是干净的透白渐变底，无阴影）
        val cardPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = w * 0.030f
        }
        val verifyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (((0xBFL shl 24) or (style.bodyTextColor and 0x00FFFFFFL))).toInt()
            textSize = w * 0.030f
            textShadow(w)
        }
        // ---- 头部纹章卡：黄徽标 + 蓝时间（证件团花简化为浅蓝波浪细纹） ----
        val parts = timePartsOf(state, dynamic)
        val timeText = parts?.hm ?: timeRawOf(state, dynamic)
        val badgePadH = w * 0.013f
        val badgeH = w * 0.044f
        val badgeR = w * 0.008f
        val badgeW = badgePadH * 2 + badgePaint.measureText(badgeText)
        val timeGap = w * 0.016f
        val timeW = timePaint.measureText(timeText).coerceAtLeast(1f)
        val cardPadL = w * 0.012f
        val cardPadR = w * 0.016f
        val cardH = w * 0.066f
        val cardR = w * 0.010f
        val headerW = cardPadL + badgeW + timeGap + timeW + cardPadR
        // ---- 地点/日期组（左侧强调色竖条贯组） ----
        val barW = w * 0.0065f
        val gapBarText = w * 0.013f
        val textX = leftPad + barW + gapBarText
        val locField = state.fields.firstOrNull { it.type == FieldType.LOCATION && it.enabled }
        val locText = locField?.let { displayText(it, dynamic, style) } ?: ""
        val locCapW = (w * 0.55f - textX).toInt().coerceAtLeast(1)
        val locLayout = if (locText.isNotBlank()) {
            val wanted = minOf(groupPaint.measureText(locText).toInt() + 2, locCapW)
            newLayout(locText, groupPaint, wanted)
        } else {
            null
        }
        // 天气字段（默认关，用户需求 2026-10-31 v248）：开启时追加在星期行后
        val weatherText = state.fields
            .firstOrNull { it.type == FieldType.WEATHER && it.enabled }
            ?.let { displayText(it, dynamic, style) }
            .orEmpty()
        val dateText = parts?.let {
            val wd = com.engcamera.i18n.I18n.t(it.weekday)
            "${it.dateDot}$wd" + (if (weatherText.isBlank()) "" else " $weatherText")
        } ?: timeRawOf(state, dynamic)
        val gapLocDate = w * 0.010f
        val groupH = (locLayout?.height ?: 0) + (if (locLayout != null) gapLocDate else 0f) +
            groupPaint.fontSpacing
        // ---- 字段卡行：拍摄人/工作单位=「标签：值」，备注=纯值；值空整行隐藏 ----
        val cardCapW = (w * 0.5f).toInt().coerceAtLeast(1)
        fun cardRowLayout(text: String, paint: TextPaint): StaticLayout {
            // 收缩到实际行宽（卡片贴内容，不留大片空底）
            val probe = newLayout(text, paint, cardCapW)
            val needed = (0 until probe.lineCount).maxOf { probe.getLineWidth(it) }.toInt() + 2
            return newLayout(text, paint, needed.coerceAtMost(cardCapW))
        }
        val cardRows = mutableListOf<StaticLayout>()
        state.fields.filter { it.enabled }.forEach { f ->
            when (f.type) {
                // 天气已追加在日期行（v248），不再进字段卡（否则开关一开出现两份天气）
                FieldType.DATETIME, FieldType.LOCATION, FieldType.PROJECT_NAME, FieldType.WEATHER -> Unit
                FieldType.NOTE -> {
                    val v = displayText(f, dynamic, style)
                    if (v.isNotBlank()) cardRows += cardRowLayout(v, cardPaint)
                }
                else -> {
                    val v = displayText(f, dynamic, style)
                    if (v.isNotBlank()) {
                        val label = if (f.label.isBlank()) "" else com.engcamera.i18n.I18n.t(f.label)
                        cardRows += cardRowLayout(if (label.isEmpty()) v else "$label：$v", cardPaint)
                    }
                }
            }
        }
        val cardPadH = w * 0.018f
        val cardPadV = w * 0.012f
        val cardRowGap = w * 0.010f
        val cardRowsW = cardRows.maxOfOrNull { it.width } ?: 0
        val fieldCardW = if (cardRows.isEmpty()) 0f else cardRowsW + cardPadH * 2
        val fieldCardH =
            if (cardRows.isEmpty()) 0f else cardPadV * 2 + cardRows.sumOf { it.height } + cardRowGap * (cardRows.size - 1)
        // ---- 盾牌验证行 ----
        val verifyText = com.engcamera.i18n.I18n.t("已验证照片真实性")
        val shieldW = w * 0.028f
        val shieldH = w * 0.031f
        val gapShieldText = w * 0.023f
        val verifyW = leftPad + shieldW + gapShieldText + verifyPaint.measureText(verifyText)
        val gapHeaderGroup = w * 0.016f
        val gapGroupCard = w * 0.016f
        val gapCardVerify = w * 0.016f
        val contentW = maxOf(
            headerW,
            if (locLayout != null) textX + locLayout.width else textX + groupPaint.measureText(dateText),
            fieldCardW,
            verifyW,
        )
        val contentH = cardH + gapHeaderGroup + groupH +
            (if (fieldCardH > 0f) gapGroupCard + fieldCardH else 0f) +
            gapCardVerify + shieldH + w * 0.006f
        // 阴影外沿：椭圆半径=内容边×倍率（模板选项可调），超出内容 (倍率-0.5)×边长
        val mx = (contentW * (style.softShadowW.coerceIn(0.3f, 1.5f) - 0.5f))
            .coerceAtLeast(w * 0.005f)
        val my = (contentH * (style.softShadowH.coerceIn(0.3f, 1.5f) - 0.5f))
            .coerceAtLeast(w * 0.005f)
        val blockW = (contentW + mx * 2).toInt().coerceAtLeast(1)
        val blockH = (contentH + my * 2).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawSoftShadow(canvas, blockW.toFloat(), blockH.toFloat(), mx, my, style)
        canvas.save()
        canvas.translate(mx, my)
        // 头部卡：白~白红~白蓝细微横向渐变底（用户反馈 2026-10-31）+ 波浪细纹（clip 圆角内画正弦线）
        canvas.save()
        canvas.clipPath(roundPath(headerW, cardH, cardR))
        canvas.drawRect(
            0f, 0f, headerW, cardH,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, headerW, 0f,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFF1EE.toInt(), 0xFFEDF2FF.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x667EB3E8.toInt()
            this.style = Paint.Style.STROKE
            strokeWidth = w * 0.0016f
        }
        val waves = 4
        val amp = cardH * 0.16f
        for (i in 0 until waves) {
            val baseY = cardH * (i + 1f) / (waves + 1f)
            val path = Path()
            path.moveTo(0f, baseY)
            var x = 0f
            var up = true
            while (x <= headerW) {
                val seg = w * 0.028f
                path.quadTo(
                    x + seg / 2f, baseY + (if (up) -amp else amp),
                    x + seg, baseY,
                )
                x += seg
                up = !up
            }
            canvas.drawPath(path, wave)
        }
        canvas.restore()
        // 灰色外边框
        canvas.drawRoundRect(
            RectF(0f, 0f, headerW, cardH), cardR, cardR,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = w * 0.0016f
                color = 0x668A909B.toInt()
            },
        )
        // 黄徽标 + 深色「打卡」
        val badgeLeft = cardPadL
        val badgeCy = cardH / 2f
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeCy - badgeH / 2f, badgeLeft + badgeW, badgeCy + badgeH / 2f),
            badgeR, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent },
        )
        val badgeFm = badgePaint.fontMetrics
        canvas.drawText(
            badgeText, badgeLeft + badgePadH,
            badgeCy + (badgeFm.descent - badgeFm.ascent) / 2f - badgeFm.descent, badgePaint,
        )
        // 时间：粗体 蓝→黑 上→下渐变（外发光在 paint 创建处已挂）
        val timeFm = timePaint.fontMetrics
        val timeX = badgeLeft + badgeW + timeGap
        timePaint.shader = LinearGradient(
            timeX, badgeCy + timeFm.ascent, timeX, badgeCy + timeFm.descent,
            0xFF2E7BE8.toInt(), 0xFF14161C.toInt(), Shader.TileMode.CLAMP,
        )
        canvas.drawText(
            timeText, timeX,
            badgeCy + (timeFm.descent - timeFm.ascent) / 2f - timeFm.descent, timePaint,
        )
        // 地点/日期组（竖条贯组）
        var y = cardH + gapHeaderGroup
        val groupBottom = y + groupH
        canvas.drawRect(leftPad, y, leftPad + barW, groupBottom, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val groupFm = groupPaint.fontMetrics
        if (locLayout != null) {
            canvas.save()
            canvas.translate(textX, y)
            locLayout.draw(canvas)
            canvas.restore()
            y += locLayout.height + gapLocDate
        }
        canvas.drawText(dateText, textX, y - groupFm.ascent, groupPaint)
        y = groupBottom
        // 半透明字段卡
        if (cardRows.isNotEmpty()) {
            val cardTop = y + gapGroupCard
            // 卡底：左→右「透白→完全透明」横向渐变（同今日·打卡备注底，再透一档；
            // 用户反馈 2026-10-31 v247：不再是灰色半透明卡）
            canvas.drawRoundRect(
                RectF(leftPad, cardTop, leftPad + fieldCardW, cardTop + fieldCardH),
                w * 0.010f, w * 0.010f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        leftPad, 0f, leftPad + fieldCardW, 0f,
                        0x59FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP,
                    )
                },
            )
            var cy = cardTop + cardPadV
            cardRows.forEachIndexed { i, row ->
                canvas.save()
                canvas.translate(leftPad + cardPadH, cy)
                row.draw(canvas)
                canvas.restore()
                cy += row.height + (if (i < cardRows.lastIndex) cardRowGap else 0f)
            }
            y = cardTop + fieldCardH
        }
        // 盾牌验证行
        val verifyTop = y + gapCardVerify
        val verifyBaseline = verifyTop - verifyPaint.fontMetrics.ascent
        val shieldCy = verifyBaseline - verifyPaint.textSize * 0.28f
        val sy = shieldCy - shieldH / 2f
        val shield = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            this.style = Paint.Style.STROKE
            strokeWidth = w * 0.004f
            strokeJoin = Paint.Join.ROUND
        }
        val shieldPath = Path().apply {
            moveTo(leftPad, sy + shieldH * 0.1f)
            quadTo(leftPad + shieldW / 2f, sy - shieldH * 0.04f, leftPad + shieldW, sy + shieldH * 0.1f)
            lineTo(leftPad + shieldW, sy + shieldH * 0.52f)
            quadTo(leftPad + shieldW, sy + shieldH * 0.84f, leftPad + shieldW / 2f, sy + shieldH)
            quadTo(leftPad, sy + shieldH * 0.84f, leftPad, sy + shieldH * 0.52f)
            close()
        }
        canvas.drawPath(shieldPath, shield)
        canvas.drawPath(
            Path().apply {
                moveTo(leftPad + shieldW * 0.28f, shieldCy - shieldW * 0.02f)
                lineTo(leftPad + shieldW * 0.44f, shieldCy + shieldW * 0.14f)
                lineTo(leftPad + shieldW * 0.74f, shieldCy - shieldW * 0.18f)
            },
            Paint(shield).apply { strokeWidth = w * 0.0035f },
        )
        canvas.drawText(verifyText, leftPad + shieldW + gapShieldText, verifyBaseline, verifyPaint)
        canvas.restore()
        return Block(out, mx.roundToInt(), my.roundToInt())
    }

    /**
     * 马克·工程 / 马克·工程白：蓝标题条 + 标签字段行（两端对齐、值列悬挂换行）。
     * dark（工程）= 深色半透明正文白字 + 底部通栏"施工单位"输入条（accent 20%、空白显示占位）；
     * light（工程白）= 白色半透明正文黑字 + 标题左侧橙方块 + 末尾经纬度行。
     */
    private fun renderMarkCard(
        state: WatermarkState,
        canvasWidth: Int,
        dynamic: DynamicValues,
        lightBody: Boolean,
    ): Block {
        val style = state.style
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        val blockW = (w * style.maxWidthRatio).toInt().coerceAtLeast(1)
        // 实测（1920）：卡 1066、标题条 148、圆角 22、标题字 65、正文字 62、行距 100、左内边距 45
        val barH = w * 0.0771f
        val corner = (w * style.cornerRatio).coerceAtLeast(1f)
        val padL = w * 0.0234f
        val padR = w * 0.0234f
        val padTop = w * 0.0135f
        val labelCell = w * 0.168f
        val rowGap = w * 0.0155f
        val square = w * 0.0214f
        val squareX = w * 0.0255f
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.titleTextColor.toInt()
            textSize = w * 0.0339f
            typeface = Typeface.DEFAULT_BOLD
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.bodyTextColor.toInt()
            textSize = w * 0.0325f
            if (!lightBody) textShadow(w, alpha = 0x66)
        }
        val enabled = state.fields.filter { it.enabled }
        val titleText = enabled.firstOrNull { it.isTitle }?.let { displayText(it, dynamic, style) } ?: ""
        val hasBar = titleText.isNotBlank()
        // 品牌 logo（v0.6.16）：容器贴标题条最左，标题区=剩余宽度，居中（先换行再省略）
        val logo = if (state.logoEnabled) com.engcamera.util.BrandLogo.current(state.logoFile) else null
        val box = logoBoxSize(style, blockW, logo)
        // 有 logo：容器右侧起排；无 logo：保持原内缩（工程白给强调方块让位，其余满宽居中）
        val titleX = if (box > 0f) box + w * 0.006f
        else if (lightBody) squareX + square else 0f
        val titleW = if (box > 0f) (blockW - titleX).coerceAtLeast(blockW * 0.3f)
        else if (lightBody) (blockW - titleX).toFloat()
        else blockW.toFloat()
        val title = if (hasBar) {
            newLayout(
                titleText, titlePaint, titleW.toInt().coerceAtLeast(1),
                Layout.Alignment.ALIGN_CENTER, maxLines = if (box > 0f) 2 else 0,
            )
        } else null
        // 标题条高：原高度；有 logo 时至少容下容器，两行标题也不能溢出条外
        val actualBarH = if (hasBar) maxOf(barH, box, title!!.height + w * 0.010f) else box
        val colonW = bodyPaint.measureText("：")
        val valueX = padL + labelCell + colonW
        val valueW = (blockW - valueX - padR).toInt().coerceAtLeast(1)
        val naturalW = (blockW - padL - padR).toInt().coerceAtLeast(1)
        val entries = mutableListOf<MEntry>()
        enabled.filter { !it.isTitle }.forEach { f ->
            when {
                f.type == FieldType.DATETIME -> {
                    // 时间走用户所选格式（v0.6.18 用户需求）：马克·工程默认带星期、
                    // 工程白默认点分日期，两者都是单行文字，可在编辑器里改；
                    // 解析不出时间时退回原文本（不吞用户输入）
                    val text = displayText(f, dynamic, style)
                    val labelTxt = if (lightBody && f.label.isNotBlank()) {
                        com.engcamera.i18n.I18n.t(f.label)
                    } else ""
                    if (labelTxt.isNotEmpty() && !isCjkLabel(labelTxt)) {
                        entries += MEntry(LRow("", newLayout("${labelTxt}：$text", bodyPaint, naturalW), null))
                    } else {
                        entries += MEntry(
                            LRow(
                                labelTxt,
                                newLayout(text, bodyPaint, if (labelTxt.isEmpty()) naturalW else valueW),
                                null,
                            ),
                        )
                    }
                }
                f.type == FieldType.CUSTOM && !lightBody -> entries += MEntry(null, noteBar = true)
                else -> {
                    val labelTxt = if (f.label.isBlank()) "" else com.engcamera.i18n.I18n.t(f.label)
                    // 工程白：经纬度独立成行，地址文本恒不带括号段（开关只控制独立行显隐）
                    val disp = if (lightBody && f.type == FieldType.LOCATION) {
                        // 结构化路径下坐标由下面的独立「经纬度」行承担，地址串里抑制掉
                        f.value.ifBlank {
                            assembledLocation(dynamic, style, suppressCoords = true)
                                ?: dynamic.location.ifBlank { com.engcamera.i18n.I18n.t("未获取定位") }
                        }.let { stripLatLng(it) }
                    } else {
                        displayText(f, dynamic, style)
                    }
                    val value = newLayout(disp, bodyPaint, valueW)
                    val natural = if (labelTxt.isNotEmpty() && !isCjkLabel(labelTxt)) {
                        newLayout("${labelTxt}：$disp", bodyPaint, naturalW)
                    } else null
                    entries += MEntry(LRow(labelTxt, value, natural))
                    // 工程白：位置字段后追加经纬度行（「显示经纬度」开关开才加行，
                    // 从位置文本提取坐标，无坐标不加行）
                    if (lightBody && f.type == FieldType.LOCATION && style.showLatLng) {
                        // 结构化地址优先（v0.6.21）：坐标就在 parts 里，不必从文本正则反解
                        val c = dynamic.addressParts
                            .takeIf { it.hasCoords() && f.value.isBlank() }
                            ?.let {
                                String.format(
                                    java.util.Locale.US, "%.6f°%s,%.6f°%s",
                                    kotlin.math.abs(it.lat), if (it.lat >= 0) "N" else "S",
                                    kotlin.math.abs(it.lng), if (it.lng >= 0) "E" else "W",
                                )
                            }
                            ?: coordsText(f.value.ifBlank { dynamic.location })
                        c?.let { cc ->
                            entries += MEntry(
                                LRow(
                                    com.engcamera.i18n.I18n.t("经纬度"),
                                    newLayout(cc, bodyPaint, valueW),
                                    null,
                                ),
                            )
                        }
                    }
                }
            }
        }
        // 施工单位输入条永远置底（字段被用户调序也不上浮）
        val ordered = entries.filterNot { it.noteBar } + entries.filter { it.noteBar }
        val rows = ordered.mapNotNull { it.row }
        if (title == null && rows.isEmpty() && ordered.none { it.noteBar }) {
            if (box > 0f && logo != null) return logoOnlyBlock(blockW, corner, box, logo, style.titleBgColor)
            return emptyBlock()
        }
        val noteBarH = w * 0.0505f
        val gapNoteBar = w * 0.008f
        val hasNoteBar = ordered.any { it.noteBar }
        // 输入条通栏贴卡片底（参考实测）；无输入条时末行下留 24/1920 底部内边距
        val bodyH = padTop + rows.sumOf { (it.natural ?: it.value).height } +
            rowGap * (rows.size - 1).coerceAtLeast(0) +
            (if (hasNoteBar) gapNoteBar + noteBarH else w * 0.0125f)
        val blockH = (actualBarH + bodyH).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(roundPath(blockW.toFloat(), blockH.toFloat(), corner))
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        var top = 0f
        if (actualBarH > 0f) {
            bg.color = style.titleBgColor.toInt()
            canvas.drawRect(0f, 0f, blockW.toFloat(), actualBarH, bg)
            // 有 logo 时：白底容器贴最左（工程白的强调方块让位，避免压住容器）
            if (box > 0f && logo != null) {
                drawLogoBox(canvas, box, actualBarH, logo)
            } else if (lightBody) {
                canvas.drawRect(
                    squareX, (actualBarH - square) / 2f, squareX + square, (actualBarH + square) / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accentColor.toInt() },
                )
            }
            title?.let { tl ->
                canvas.save()
                canvas.translate(titleX, (actualBarH - tl.height) / 2f)
                tl.draw(canvas)
                canvas.restore()
            }
            top = actualBarH
        }
        bg.color = withOpacity(style.bodyBgColor, style.bgOpacity).toInt()
        canvas.drawRect(0f, top, blockW.toFloat(), blockH.toFloat(), bg)
        var y = top + padTop
        var rowIdx = 0
        ordered.forEach { entry ->
            val row = entry.row
            when {
                entry.noteBar -> {
                    // 通栏输入条：accent 20% 底、标签两端对齐、空白显示占位文本（白 65%）
                    val barTop = y + gapNoteBar
                    val accent = style.accentColor
                    canvas.drawRect(
                        0f, barTop, blockW.toFloat(), barTop + noteBarH,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = ((0x33L shl 24) or (accent and 0x00FFFFFFL)).toInt()
                        },
                    )
                    val noteField = enabled.firstOrNull { it.type == FieldType.CUSTOM }
                    val noteValue = noteField?.value.orEmpty()
                    val placeholder = noteValue.isBlank()
                    val textPaint = TextPaint(bodyPaint)
                    if (placeholder) {
                        textPaint.color = ((0xA6L shl 24) or (style.bodyTextColor and 0x00FFFFFFL)).toInt()
                    }
                    val text = if (placeholder) com.engcamera.i18n.I18n.t("请输入施工单位") else noteValue
                    val noteLayout = newLayout(text, textPaint, valueW)
                    val labelTxt = noteField?.label?.let { l -> if (l.isBlank()) "" else com.engcamera.i18n.I18n.t(l) } ?: ""
                    val baseline = barTop + noteBarH / 2f +
                        (textPaint.fontMetrics.descent - textPaint.fontMetrics.ascent) / 2f -
                        textPaint.fontMetrics.descent
                    if (labelTxt.isNotEmpty()) {
                        if (!drawJustifiedLabel(canvas, labelTxt, textPaint, padL, baseline, labelCell)) {
                            canvas.drawText(labelTxt, padL, baseline, textPaint)
                        }
                        canvas.drawText("：", padL + labelCell, baseline, textPaint)
                    }
                    canvas.save()
                    canvas.translate(valueX, baseline - (-textPaint.fontMetrics.ascent))
                    noteLayout.draw(canvas)
                    canvas.restore()
                    y = barTop + noteBarH
                }
                row != null && row.natural != null -> {
                    val layout = row.natural!!
                    canvas.save(); canvas.translate(padL, y); layout.draw(canvas); canvas.restore()
                    y += layout.height + (if (rowIdx < rows.lastIndex) rowGap else 0f)
                    rowIdx++
                }
                row != null -> {
                    val baseline = y + row.value.getLineBaseline(0)
                    if (row.label.isNotEmpty()) {
                        if (!drawJustifiedLabel(canvas, row.label, bodyPaint, padL, baseline, labelCell)) {
                            canvas.drawText(row.label, padL, baseline, bodyPaint)
                        }
                        canvas.drawText("：", padL + labelCell, baseline, bodyPaint)
                        canvas.save(); canvas.translate(valueX, y); row.value.draw(canvas); canvas.restore()
                    } else {
                        // 无标签行（日期行）从字段位（左缘）起排，不是值列（用户反馈 2026-10-31）
                        canvas.save(); canvas.translate(padL, y); row.value.draw(canvas); canvas.restore()
                    }
                    y += row.value.height + (if (rowIdx < rows.lastIndex) rowGap else 0f)
                    rowIdx++
                }
            }
        }
        canvas.restore()
        return Block(bitmap)
    }

    /** 马克·打卡：白底圆角卡（绿徽标「✓打卡」+ 绿色时间）、白字日期/星期、
     *  圆点地址/备注行、小人拍摄人行、盾牌验证行（参考图 2026-10-31） */
    private fun renderMarkClock(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        val leftPad = w * 0.011f
        val accent = style.accentColor.toInt()
        val white = style.bodyTextColor.toInt()
        val badgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = w * 0.030f
            typeface = Typeface.DEFAULT_BOLD
            textShadow(w)
        }
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = w * 0.039f
            typeface = Typeface.DEFAULT_BOLD
        }
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = w * 0.0344f
            typeface = Typeface.DEFAULT_BOLD
            textShadow(w)
        }
        val addrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = w * 0.0323f
            textShadow(w)
        }
        val verifyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (((0xBFL shl 24) or (style.bodyTextColor and 0x00FFFFFFL))).toInt()
            textSize = w * 0.030f
            textShadow(w)
        }
        val parts = timePartsOf(state, dynamic)
        val timeText = parts?.hm ?: timeRawOf(state, dynamic)
        val badgeText = com.engcamera.i18n.I18n.t("打卡")
        val checkW = w * 0.026f
        val badgePadL = w * 0.0115f
        val badgePadR = w * 0.0125f
        val badgeH = w * 0.0495f
        val badgeR = w * 0.0073f
        val badgeW = badgePadL + checkW + w * 0.014f + badgePaint.measureText(badgeText) + badgePadR
        val timeW = timePaint.measureText(timeText).coerceAtLeast(1f)
        // 打卡时间部分：白色圆角卡包住徽标+时间（参考图 2026-10-31）
        val cardLPad = w * 0.007f
        val cardRPad = w * 0.014f
        val cardVPad = w * 0.0085f
        val cardR = w * 0.009f
        val headerW = cardLPad + badgeW + w * 0.014f + timeW + cardRPad
        val headerH = badgeH + cardVPad * 2
        val row1W = leftPad + headerW
        // 日期行：2026/10/30 星期五
        val dateText = parts?.let { "${it.dateSlash} ${com.engcamera.i18n.I18n.t(it.weekday)}" }
            ?: timeRawOf(state, dynamic)
        // 图标行：圆点=地点/备注，小人=拍摄人；值空整行隐藏
        val bulletCX = leftPad + w * 0.0161f
        val bulletR = w * 0.0052f
        val addrTextX = leftPad + w * 0.0458f
        val addrMaxW = (w * 0.55f - addrTextX).toInt().coerceAtLeast(1)
        fun iconRow(text: String, person: Boolean): Pair<StaticLayout, Boolean>? {
            if (text.isBlank()) return null
            val wd = minOf(addrPaint.measureText(text), addrMaxW.toFloat()).toInt().coerceAtLeast(1)
            return newLayout(text, addrPaint, wd) to person
        }
        val locField = state.fields.firstOrNull { it.type == FieldType.LOCATION && it.enabled }
        val iconRows = buildList {
            locField?.let { f -> iconRow(displayText(f, dynamic, style), false)?.let { add(it) } }
            state.fields.filter { it.enabled && it.type == FieldType.NOTE }.forEach { f ->
                iconRow(displayText(f, dynamic, style), false)?.let { add(it) }
            }
            state.fields.filter { it.enabled && it.type == FieldType.CUSTOM }.forEach { f ->
                iconRow(displayText(f, dynamic, style), true)?.let { add(it) }
            }
        }
        val verifyText = com.engcamera.i18n.I18n.t("已验证照片真实性")
        val shieldW = w * 0.028f
        val shieldH = w * 0.031f
        val gapShieldText = w * 0.023f
        val verifyW = leftPad + shieldW + gapShieldText + verifyPaint.measureText(verifyText)
        val rowsW = iconRows.maxOfOrNull { addrTextX + it.first.width } ?: 0f
        val blockW = maxOf(row1W, rowsW, verifyW).toInt().coerceAtLeast(1)

        val dateFm = datePaint.fontMetrics
        val verifyFm = verifyPaint.fontMetrics
        // 日期行上下边距放宽（用户反馈 2026-10-31 v254：卡片上移 8px、日期行上移 4px
        // ——贴底钳位下，卡片段与日期行、日期行与下方字段各加 4px 空隙即为此效果）
        val gapDate = w * 0.006f
        val gapAfterDate = w * 0.034f
        val gapRow = w * 0.011f
        // 验证行与上方字段行再收紧（用户反馈 2026-10-31 v253：上移 5 像素）
        val gapVerify = w * 0.006f
        // 底部内边距收紧（v251：水印整体下移，底部视觉留白与左侧一致）
        val bottomPad = w * 0.001f
        val rowsH = iconRows.sumOf { it.first.height } + gapRow * (iconRows.size - 1).coerceAtLeast(0)
        val blockH = (headerH + gapDate + (dateFm.descent - dateFm.ascent) +
            (if (iconRows.isNotEmpty()) gapAfterDate + rowsH else 0f) + gapVerify +
            (verifyFm.descent - verifyFm.ascent) + bottomPad)
            .toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // 白色圆角卡
        canvas.drawRoundRect(
            RectF(leftPad, 0f, leftPad + headerW, headerH), cardR, cardR,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() },
        )
        // 徽标行（卡内垂直居中）
        val badgeTop = (headerH - badgeH) / 2f
        val badgeCy = badgeTop + badgeH / 2f
        val badgeLeft = leftPad + cardLPad
        canvas.drawRoundRect(
            RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH), badgeR, badgeR,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent },
        )
        // 白色对勾
        val check = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            this.style = Paint.Style.STROKE
            strokeWidth = w * 0.0045f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val cx0 = badgeLeft + badgePadL
        val checkPath = Path().apply {
            moveTo(cx0 + checkW * 0.08f, badgeCy + checkW * 0.02f)
            lineTo(cx0 + checkW * 0.4f, badgeCy + checkW * 0.3f)
            lineTo(cx0 + checkW * 0.98f, badgeCy - checkW * 0.34f)
        }
        canvas.drawPath(checkPath, check)
        val badgeFm = badgePaint.fontMetrics
        canvas.drawText(
            badgeText, cx0 + checkW + w * 0.014f,
            badgeCy + (badgeFm.descent - badgeFm.ascent) / 2f - badgeFm.descent, badgePaint,
        )
        val timeFm = timePaint.fontMetrics
        canvas.drawText(
            timeText, badgeLeft + badgeW + w * 0.014f,
            badgeCy + (timeFm.descent - timeFm.ascent) / 2f - timeFm.descent, timePaint,
        )
        // 日期行
        val dateBaseline = headerH + gapDate - dateFm.ascent
        canvas.drawText(dateText, leftPad, dateBaseline, datePaint)
        // 图标行（圆点/小人 + 悬挂缩进）
        var rowTop = headerH + gapDate + dateFm.descent + gapAfterDate
        iconRows.forEach { (layout, person) ->
            val baseline = rowTop - addrPaint.fontMetrics.ascent
            if (person) {
                val iconH = w * 0.030f
                drawPersonIcon(canvas, bulletCX - iconH / 2f, baseline - iconH * 0.88f, iconH, white)
            } else {
                canvas.drawCircle(
                    bulletCX, baseline - addrPaint.textSize * 0.32f, bulletR,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = white },
                )
            }
            canvas.save()
            canvas.translate(addrTextX, rowTop)
            layout.draw(canvas)
            canvas.restore()
            rowTop += layout.height + gapRow
        }
        // 验证行（盾牌 + 已验证照片真实性）
        val verifyTop = rowTop - gapRow + gapVerify
        val verifyBaseline = verifyTop - verifyFm.ascent
        val shieldCy = verifyBaseline - verifyPaint.textSize * 0.28f
        val sy = shieldCy - shieldH / 2f
        val shield = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            this.style = Paint.Style.STROKE
            strokeWidth = w * 0.004f
            strokeJoin = Paint.Join.ROUND
        }
        val shieldPath = Path().apply {
            moveTo(leftPad, sy + shieldH * 0.1f)
            quadTo(leftPad + shieldW / 2f, sy - shieldH * 0.04f, leftPad + shieldW, sy + shieldH * 0.1f)
            lineTo(leftPad + shieldW, sy + shieldH * 0.52f)
            quadTo(leftPad + shieldW, sy + shieldH * 0.84f, leftPad + shieldW / 2f, sy + shieldH)
            quadTo(leftPad, sy + shieldH * 0.84f, leftPad, sy + shieldH * 0.52f)
            close()
        }
        canvas.drawPath(shieldPath, shield)
        val check2 = Paint(check).apply { strokeWidth = w * 0.0035f }
        canvas.drawPath(
            Path().apply {
                moveTo(leftPad + shieldW * 0.28f, shieldCy - shieldW * 0.02f)
                lineTo(leftPad + shieldW * 0.44f, shieldCy + shieldW * 0.14f)
                lineTo(leftPad + shieldW * 0.74f, shieldCy - shieldW * 0.18f)
            },
            check2,
        )
        canvas.drawText(verifyText, leftPad + shieldW + gapShieldText, verifyBaseline, verifyPaint)
        return Block(out)
    }

    /**
     * 巡逻（用户需求 2026-11-06，参考图 imgcache/巡逻（1）.jpg + 巡逻.jpg）：
     * 半透明蓝卡 + 黄色标题块（固定「执勤巡逻」双层字 v2：黑字层字号放大垫底不描边保持尖角，
     * 白字层对齐盖上）+ 可编辑标题「工作记录」+ 尖角箭头带（BUTT+MITER）
     * + Bahnschrift 字体大号时间（白字掩膜+SRC_ATOP 波浪纹，蓝→红→蓝横向渐变）
     * + 红方块地址行（对第一行垂直居中）。
     * 全部几何按卡宽 blockW 的比例复刻参考图；卡宽跟「水印宽度」选项走（默认 0.33 画面宽）。
     */
    private fun renderPatrol(state: WatermarkState, canvasWidth: Int, dynamic: DynamicValues): Block {
        val style = state.style
        val w = canvasWidth.coerceAtLeast(1).toFloat()
        // 卡宽跟「水印宽度」选项走（v265 加回滑杆；v267 用户拍板默认 0.50）；
        // applyTemplate 只在目标模板也暴露该选项时才继承旧值，不会被别的模板宽度污染
        val bw = (w * style.maxWidthRatio.coerceIn(0.2f, 0.7f)).coerceAtLeast(1f)
        // 模板选项三色（v263 用户需求）：文字色/标题块色/卡片色，默认=参考图解出色；
        // 命名沿用旧称呼（white=文字、yellow=标题块），与 1003 行同款模式
        val white = style.bodyTextColor.toInt()
        val yellow = style.titleBgColor.toInt()

        // ---- 文案 ----
        // 黄块里「执勤巡逻」=固定文案（用户需求 2026-11-06：不许改，不走字段）；
        // 右侧「工作记录」才是标题字段（PROJECT_NAME，值空显示标签）
        val titleText = "执勤巡逻"
        val headText = state.fields.firstOrNull { it.isTitle && it.enabled }
            ?.let { displayText(it, dynamic, style).ifBlank { com.engcamera.i18n.I18n.t(it.label) } }
            ?: state.fields.firstOrNull { it.type == FieldType.PROJECT_NAME && it.enabled }
                ?.let { displayText(it, dynamic, style) }
            ?: ""
        val parts = timePartsOf(state, dynamic)
        val timeText = parts?.hm ?: timeRawOf(state, dynamic)
        val dateText = parts?.dateDash?.split("-")?.takeIf { it.size == 3 }
            ?.let { "${it[0]}年${it[1]}月${it[2]}日" } ?: ""
        val locText = state.fields.firstOrNull { it.type == FieldType.LOCATION && it.enabled }
            ?.let { displayText(it, dynamic, style) } ?: ""
        val noteTexts = state.fields.filter { it.enabled && it.type == FieldType.NOTE }
            .map { displayText(it, dynamic, style) }.filter { it.isNotBlank() }

        // ---- 字号（参考图为窄体：字高定 textSize，宽度用 textScaleX 压窄；
        //      数值由 clean 参考图墨迹实测反推——标题墨高 0.0738/宽 0.2831、
        //      工作记录 0.0636/0.2477、时间 0.1109/0.3047、日期 0.0648/0.5081、
        //      地址 0.0648/单字 0.0619）----
        val titleSize = bw * 0.0800f
        val headSize = bw * 0.0755f
        val timeSize = bw * 0.1510f
        val dateSize = bw * 0.0770f
        val addrSize = bw * 0.0762f

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = titleSize
            textScaleX = 0.885f
            // Noto Sans SC Bold 子集（v269）：CJK 无真粗体回退细体的根治
            typeface = patrolTitleBoldFont ?: Typeface.DEFAULT_BOLD
        }
        val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = headSize
            textScaleX = 0.821f
            typeface = Typeface.DEFAULT_BOLD
        }
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = timeSize
            // scaleX 占位——常量块后按 v271 几何（黄框左缘→逻右缘）动态解出
            textScaleX = 0.97f
            // Bahnschrift SemiBold 静态子集（v276）：真字重替代 fakeBold（见字体字段注释）
            typeface = patrolTimeFont ?: Typeface.DEFAULT_BOLD
        }
        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = dateSize
            textScaleX = 0.920f
            typeface = Typeface.DEFAULT_BOLD
        }
        val addrPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            textSize = addrSize
            textScaleX = 0.820f
        }

        // ---- 版式常量（参考图实测，均相对卡宽）----
        val boxL = bw * 0.0246f
        val boxR = bw * 0.3792f
        val boxT = bw * 0.0228f
        val boxB = bw * 0.1794f
        // 黄色贯通下划线：v268 用户要求加粗 25%（0.0084→0.0105 卡宽，参考图实测 0.0072
        // 但用户观感偏细——以观感为准）；底边与黄框底对齐不动，往上长
        val ulT = bw * 0.1689f
        val ulR = bw * 0.9556f
        val chevT = bw * 0.1973f
        val chevB = bw * 0.2195f
        val chevL = bw * 0.0324f
        val chevR = bw * 0.9640f
        val titleBaseline = bw * 0.1370f
        val titleX = boxL + bw * 0.0348f
        val headX = bw * 0.4368f
        val headBaseline = bw * 0.1370f
        // 时间基线（左右位置由 timeDrawX 承担，见常量块后的墨迹对齐补偿）
        val timeBaseline = bw * 0.4013f
        val dateBaseline = bw * 0.3845f
        val redX = bw * 0.0606f
        val redW = bw * 0.0444f
        val redH = bw * 0.0289f
        val addrX = bw * 0.1668f
        val addrTop = bw * 0.4865f
        // 红方块对地址第一行垂直居中（用户需求 2026-11-07；行高含 newLayout 的行距 extra）
        val redT = addrTop + (addrPaint.fontSpacing + bw * 0.011f - redH) / 2f
        val addrW = (bw * 0.782f).toInt().coerceAtLeast(1)

        // 时间宽度（v274 回 v271 几何）：scaleX=目标宽/自然宽 动态解出（黄框左缘→逻右缘），
        // 并把【墨迹左缘】（而非原点，Bahnschrift 数字有左侧留白）对齐黄框左缘
        if (timeText.isNotBlank()) {
            val natural = TextPaint(timePaint).apply { textScaleX = 1f }
            val naturalW = natural.measureText(timeText)
            val targetW = titleX + titlePaint.measureText(titleText) - boxL
            if (naturalW > 0f && targetW > 0f) {
                timePaint.textScaleX = (targetW / naturalW).coerceIn(0.5f, 2f)
            }
        }
        val inkBounds = android.graphics.Rect()
        TextPaint(timePaint).apply { textScaleX = 1f }
            .getTextBounds(timeText, 0, timeText.length, inkBounds)
        // 墨迹左缘 = 原点 + bounds.left×scaleX → 反向补偿让墨迹正好落在黄框左缘
        val timeDrawX = boxL - inkBounds.left * timePaint.textScaleX

        val addrLayouts = buildList {
            if (locText.isNotBlank()) add(newLayout(locText, addrPaint, addrW, lineSpacingExtra = bw * 0.011f))
            noteTexts.forEach { add(newLayout(it, addrPaint, addrW, lineSpacingExtra = bw * 0.011f)) }
        }
        val addrH = addrLayouts.sumOf { it.height.toDouble() }.toFloat()
        val baseH = bw * 0.6848f
        val blockH = maxOf(baseH, addrTop + addrH + bw * 0.040f).toInt().coerceAtLeast(1)
        val blockW = bw.toInt().coerceAtLeast(1)

        val out = Bitmap.createBitmap(blockW, blockH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val corner = bw * 0.012f
        canvas.save()
        canvas.clipPath(roundPath(blockW.toFloat(), blockH.toFloat(), corner))

        // 半透明饱和蓝卡：默认 0x86102BF2=基色(16,43,242)@52%（参考图两张自洽解方程——
        // 叠近黑底得 (8,22,127)、叠浅色墙面 (145,136,119) 得 (73,87,187)）；
        // v263 起走模板选项「卡片颜色」，透明度随所选颜色自带
        canvas.drawRect(
            0f, 0f, blockW.toFloat(), blockH.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.bodyBgColor.toInt() },
        )
        // 黄色标题块 + 贯通下划线（同色，参考图两处黄色完全一致）
        canvas.drawRect(boxL, boxT, boxR, boxB, Paint().apply { color = yellow })
        canvas.drawRect(boxL, ulT, ulR, boxB, Paint().apply { color = yellow })

        // 淡黄箭头带（同色黄 55% 透明；尖角 ">"：平头收尾+尖角转折，
        // 用户需求 2026-11-06——参考图是利落的尖角非圆角）
        val chevPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = yellow
            alpha = 0x8C
            this.style = Paint.Style.STROKE
            strokeWidth = (chevB - chevT) * 0.22f
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
        }
        val chevPitch = bw * 0.0273f
        val chevInset = (chevB - chevT) * 0.06f
        var cx = chevL
        while (cx < chevR - chevPitch) {
            val p = Path().apply {
                moveTo(cx, chevT + chevInset)
                lineTo(cx + chevPitch * 0.62f, (chevT + chevB) / 2f)
                lineTo(cx, chevB - chevInset)
            }
            canvas.drawPath(p, chevPaint)
            cx += chevPitch
        }

        // 固定标题「执勤巡逻」：白字+硬边阴影 v5（v279 像素对比闭环定版）。
        // 参考图黑墨外框与白字只差 ~8px、但黑像素是「放大拷贝」的 2.1 倍且填满笔画间隙
        // ——本质=【形态学膨胀】，即描边！v262 方向没错，错在太细（半径 5.8px vs 实测
        // 6px@1667 卡宽）+ ROUND join 柔角。FILL_AND_STROKE（半径=strokeWidth/2）
        // + MITER 保持尖角（尖角由 join 决定，不是不能用描边）。同位绘制即可，
        // 膨胀对每个字形自身对称，无需逐字缩放（v2~v278 的逐字/各向异性方案全部退役）
        if (titleText.isNotBlank()) {
            val black = TextPaint(titlePaint).apply {
                color = 0xFF000000.toInt()
                this.style = Paint.Style.FILL_AND_STROKE
                strokeWidth = bw * 0.0072f
                strokeJoin = Paint.Join.MITER
            }
            canvas.drawText(titleText, titleX, titleBaseline, black)
            canvas.drawText(titleText, titleX, titleBaseline, titlePaint)
        }
        if (headText.isNotBlank()) {
            canvas.drawText(headText, headX, headBaseline, headPaint)
        }

        // 大号时间：钱币纹理（白字掩膜垫底 + 波浪细纹 SRC_ATOP 画进字内；
        // 线色沿横向 LinearGradient 白蓝→白红→白蓝 渐变，比今日-打卡更密。
        // 用户需求 2026-11-06。旧实现把纹画在白底上再 SRC_IN 叠掩膜——
        // SRC_IN 输出=掩膜色×底alpha(恒1)，纹理全被丢掉只剩纯白字）
        if (timeText.isNotBlank()) {
            val tfm = timePaint.fontMetrics
            val texW = (timePaint.measureText(timeText) + timeSize * 0.3f).toInt().coerceAtLeast(1)
            val texH = (timeSize * 1.35f).toInt().coerceAtLeast(1)
            val tex = Bitmap.createBitmap(texW, texH, Bitmap.Config.ARGB_8888)
            val tc = Canvas(tex)
            // 掩膜画刷必须带同款 textScaleX（0.72），否则字比量宽时宽 1/0.72 倍
            // → 位图右端装不下 → 「23:07」只剩「23:0」（v262 截断 bug 根因）
            tc.drawText(
                timeText, timeSize * 0.08f, -tfm.ascent,
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = white
                    textSize = timeSize
                    // 掩膜画刷必须与 timePaint 几何完全同款（字体/动态缩放），否则量宽错位裁字
                    textScaleX = timePaint.textScaleX
                    typeface = timePaint.typeface
                },
            )
            val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                // v272 PIL 复刻卡与参考图并排调参定版：线距 0.0074/波长 0.030/振幅 0.003/线宽 0.0018，
                // 淡彩白蓝 #B4D4F0 ↔ 白红 #F0C4D2（参考图实测色度 ~20 的基础上略提一档，
                // 抵消真机上 1px 线抗锯齿的冲淡）
                strokeWidth = max(1f, bw * 0.0018f)
                shader = LinearGradient(
                    0f, 0f, texW.toFloat(), 0f,
                    intArrayOf(0xFFB4D4F0.toInt(), 0xFFF0C4D2.toInt(), 0xFFB4D4F0.toInt()),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            }
            val period = bw * 0.030f
            // v272 按参考图回调：振幅 0.003（参考图实测 ~0.002，留一点余量）、行距 0.0074（实测值）
            val amp = bw * 0.003f
            val gapY = bw * 0.0074f
            var yy = 0f
            while (yy < texH) {
                val p = Path().apply {
                    moveTo(-period, yy)
                    var x = -period
                    while (x < texW + period) {
                        // 控制点 ±2×amp：二次贝塞尔弧中点纵坐标=控制点一半，波峰才是真 amp；
                        // v268 周期 0.024→0.048（用户要求峰到峰距离拉长一倍=一个波峰横跨原峰+谷）
                        quadTo(x + period * 0.25f, yy - amp * 2f, x + period * 0.5f, yy)
                        quadTo(x + period * 0.75f, yy + amp * 2f, x + period, yy)
                        x += period
                    }
                }
                tc.drawPath(p, hair)
                yy += gapY
            }
            canvas.drawBitmap(tex, timeDrawX, timeBaseline + tfm.ascent, null)
            tex.recycle()
        }

        // 日期（与时间同一行，底边略高于时间）
        if (dateText.isNotBlank()) {
            val dateX = timeDrawX + timePaint.measureText(timeText) + bw * 0.060f
            canvas.drawText(dateText, dateX, dateBaseline, datePaint)
        }

        // 红方块 + 地址/备注（两行同左缩进）
        canvas.drawRect(
            redX, redT, redX + redW, redT + redH,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFB2C18.toInt() },
        )
        var ly = addrTop
        addrLayouts.forEach { layout ->
            canvas.save()
            canvas.translate(addrX, ly)
            layout.draw(canvas)
            canvas.restore()
            ly += layout.height
        }

        canvas.restore()
        return Block(out)
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
        // 圆角=占短边百分比 0~50（50%=正半圆封顶，v238；超半圆 SDF 退化变尖）
        val cornerClamped = glassCornerPx(style.glassCornerRadius, blockWidth, blockHeight)

        val bitmap = Bitmap.createBitmap(blockWidth, blockHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 阴影四参数可调（0=无阴影）：强度倍数×扩散（模糊半径）×透明度×垂直偏移
        // （v171 拆分，默认值=旧硬编码效果：blur=0.014W、dy=0.004W、alpha≈0xB4）
        val shadowBlur = if (style.textShadow > 0f) width * 0.014f * style.textShadow * style.shadowSpread else 0f
        val shadowDy = if (style.textShadow > 0f) width * 0.004f * style.textShadow * style.shadowOffsetY else 0f
        val shadowColor =
            ((style.shadowAlpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x000000
        fun drawWithShadow(layout: StaticLayout, x: Float, y: Float) {
            if (shadowBlur > 0f) {
                val p = layout.paint as TextPaint
                val keepColor = p.color
                val keepFilter = p.maskFilter
                p.color = shadowColor
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
            val li = cornerLineInset(y + sep * 0.5f, 1f, blockHeight.toFloat(), cornerClamped)
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
            drawWithShadow(
                layout,
                baseInset + cornerLineInset(y, layout.height.toFloat(), blockHeight.toFloat(), cornerClamped),
                y,
            )
            y += layout.height
        }
        return Block(bitmap)
    }

    /** 圆角内切几何的行水平内缩（液态玻璃/单行横排共用文字排列算法）：
     *  inset = r − √(r² − (r−y)²)，y = 该行（含行高）距最近圆角边缘的垂直距离；
     *  y ≥ r（不在圆弧影响区）时 0——首尾行自动避开圆弧，中间行不受影响。
     *  液态玻璃里标题不参与（恒居中，用户拍板 2026-10-16） */
    private fun cornerLineInset(y0: Float, lineH: Float, blockH: Float, r: Float): Float {
        val y = min(y0, blockH - (y0 + lineH)).coerceAtLeast(0f)
        if (y >= r || r <= 0f) return 0f
        val d = (r - y).coerceIn(0f, r)
        return r - kotlin.math.sqrt(r * r - d * d)
    }

    /** 库 dp 参数 → 当前画布 px */
    private fun glassDpToPx(dp: Float, canvasWidth: Int): Float = dp * canvasWidth / 360f

    /** 液态玻璃圆角：占卡片短边的百分比，0~50（50%=正半圆/胶囊封顶，再大 SDF
     *  退化变尖——v238 用户拍板：百分比即占短边比例、上限 50%、默认 15%；
     *  与短边比例恒定、分辨率无关） */
    fun glassCornerPx(pct: Float, cardW: Int, cardH: Int): Float =
        (min(cardW, cardH) * pct.coerceIn(0f, 50f) / 100f).coerceAtLeast(1f)

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
        // 圆角：占短边百分比 0~50（50%=正半圆封顶，v238；v236 起从 dp 改百分比）
        val corner = glassCornerPx(style.glassCornerRadius, cardW, cardH)
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

    /** 液态玻璃卡在成片中的几何与参数（视频 GPU 折射路径用；与烧录同一套定位/换算） */
    class GlassGeometry(
        val centerX: Float, val centerY: Float,    // 卡中心（成片 px，图像系 y 向下）
        val width: Float, val height: Float,       // 未旋转卡宽高 px
        val rotationDeg: Float,                    // 卡旋转角（成片系，烧录同款）
        val cornerPx: Float,
        val refractionHeightPx: Float,
        val refractionOffsetPx: Float,             // 负=向内（与库/CPU 版一致）
        val dispersion: Float,
        val tintAlpha: Float, val tintR: Float, val tintG: Float, val tintB: Float,
        val blurPx: Float,
    )

    /**
     * 计算液态玻璃卡在成片（显示系=重力系）中的几何。非液态模板或空块返回 null。
     * 视频合成用它在 GPU 上做折射，几何必须与 drawOnCanvas 的文本块定位完全一致。
     */
    fun liquidGlassGeometry(
        state: WatermarkState,
        canvasW: Int,
        canvasH: Int,
        dynamic: DynamicValues,
        rotationDeg: Float,
    ): GlassGeometry? {
        if (state.style.layout != "LIQUID_GLASS") return null
        val basis = minOf(canvasW, canvasH)
        val block = render(state, basis, dynamic)
        if (block.width <= 1) return null
        val (left, top) = positionBlock(
            canvasW, canvasH, block.contentW, block.contentH,
            state.anchorX, state.anchorY, rotationDeg,
        )
        val st = state.style
        return GlassGeometry(
            centerX = left + block.contentW / 2f,
            centerY = top + block.contentH / 2f,
            width = block.contentW.toFloat(),
            height = block.contentH.toFloat(),
            rotationDeg = rotationDeg,
            cornerPx = glassCornerPx(st.glassCornerRadius, block.width, block.height),
            refractionHeightPx = glassDpToPx(st.glassRefractionHeight, basis).coerceAtLeast(1f),
            refractionOffsetPx = -glassDpToPx(st.glassRefractionOffset, basis),
            dispersion = st.glassDispersion.coerceIn(0f, 1f),
            tintAlpha = st.glassTintAlpha.coerceIn(0f, 1f),
            tintR = st.glassTintRed.coerceIn(0f, 1f),
            tintG = st.glassTintGreen.coerceIn(0f, 1f),
            tintB = st.glassTintBlue.coerceIn(0f, 1f),
            blurPx = glassDpToPx(st.glassBlurRadius, basis),
        )
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
     * @param skipGlassCard 液态玻璃只画文本块、跳过玻璃卡（视频合成：玻璃由 GPU 折射效果
     *      单独处理，文字层经本函数逐秒重渲实现时间流动）
     * @param antiFakeCode 防伪码（v0.6.16）：非空且 state.antiFake 开时画到图片右下角。
     *      传 null = 不画（视频/预览占位走 WatermarkOverlay，不在本函数）
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
        skipGlassCard: Boolean = false,
        antiFakeCode: String? = null,
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
        // 水印整体透明度（v0.6.17）：块位图绘制时统一乘 alpha（防伪码条不跟随，恒清晰）
        val blockAlpha = (renderState.style.overallOpacity.coerceIn(10, 100) * 255 / 100)
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
        if (block.width <= 1) {
            // 水印全关（无字段）时防伪码照样要画
            drawAntiFake(canvas, canvasW, canvasH, state, antiFakeCode)
            return
        }
        // 网页位图按固定宽渲染（WebTemplateRenderer.RENDER_WIDTH），绘制时缩放到
        // 画面宽 × webScale 比例——预览与成片占比一致（native 位图 1:1 绘制）
        val webScaled = isWeb && renderState === state
        val drawW: Float
        val drawH: Float
        if (webScaled) {
            drawW = WebTemplateRenderer.webDrawFraction(renderState) * basis
            drawH = block.height * drawW / block.width
        } else {
            // 定位按内容区（带阴影外沿的布局外沿不参与钳位，v249）
            drawW = block.contentW.toFloat()
            drawH = block.contentH.toFloat()
        }
        val (left, top) = positionBlock(
            canvasW, canvasH, drawW.roundToInt(), drawH.roundToInt(), ax, ay, rot, edgeToEdge = fullBar,
            extraBottomMarginPx = -bottomMarginAllowance(renderState, canvasW),
        )
        // 液态玻璃：先画玻璃卡（CPU 版 AGSL 采样成片画面，公式与库一致），再画文本块
        // （视频 GPU 折射路径跳过玻璃卡，只出文字层）
        if (isLiquid && !skipGlassCard) {
            val glassFrame = if (backdrop != null && !backdrop.isRecycled) {
                FrameContext(backdrop, canvasW, canvasH, rot, left, top)
            } else {
                null
            }
            if (glassFrame != null || renderState.style.glassTintAlpha > 0f) {
                val glass = renderGlassCard(renderState, basis, drawW.roundToInt(), drawH.roundToInt(), glassFrame)
                canvas.save()
                canvas.rotate(rot, left + drawW / 2f, top + drawH / 2f)
                canvas.drawBitmap(
                    glass, left, top,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = blockAlpha },
                )
                canvas.restore()
                glass.recycle()
            }
        }
        canvas.save()
        canvas.rotate(rot, left + drawW / 2f, top + drawH / 2f)
        if (webScaled) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = blockAlpha }
            canvas.drawBitmap(block.bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
        } else {
            // 位图整体（含阴影外沿）回退内容区左上角绘制：left/top 是内容区的定位点
            canvas.drawBitmap(
                block.bitmap, left - block.offsetX, top - block.offsetY,
                Paint().apply { alpha = blockAlpha },
            )
        }
        canvas.restore()
        // 网页模板的位图归 WebTemplateRenderer 缓存所有，不能回收
        if (!isWeb || renderState !== state) block.bitmap.recycle()
        // 防伪码最后画：水印块拖到右下角时码仍在最上层可见
        drawAntiFake(canvas, canvasW, canvasH, state, antiFakeCode)
    }

    /** 防伪码条：图片坐标系右下角，不随水印锚点/旋转移动（v0.6.16） */
    private fun drawAntiFake(canvas: Canvas, canvasW: Int, canvasH: Int, state: WatermarkState, code: String?) {
        if (!state.antiFake || code.isNullOrBlank()) return
        com.engcamera.util.AntiCounterfeit.drawStrip(canvas, canvasW, canvasH, code)
    }

    /** 渐变底栏"拉满整张照片宽度"模式：水印宽度滑杆拉到最大（>=99.9%） */
    fun isFullWidthBar(state: WatermarkState): Boolean =
        state.style.layout == "GRADIENT" && state.style.maxWidthRatio >= 0.999f

    /** 布局级底部安全边距放宽（v252 用户反馈：马克·打卡下安全边距视觉偏高；
     *  v253 再加 10 像素）：该布局允许整体额外下沉的画面宽比例，预览/烧录共用。其他布局 0 */
    fun bottomMarginAllowance(state: WatermarkState, canvasWidth: Int): Float =
        if (state.style.layout == "MARK_CLOCK") canvasWidth * 0.020f else 0f


/** 记录点光晕色：同 RGB、35% 透明 */
private fun recColorGlow(rec: Int): Int = (rec and 0x00FFFFFF) or (0x59 shl 24)

    /** 字段当前显示文本：位置/时间标签有值（手动编辑）则优先生效，空则用拍照瞬间动态值。 */
    private fun displayText(f: WatermarkField, dynamic: DynamicValues, style: WatermarkStyle): String {
        val text = when (f.type) {
            // 时间格式（v0.6.18）：整串渲染的模板按用户所选格式显示；解析不了的时间原样保留
            FieldType.DATETIME -> com.engcamera.util.TimeFormat.apply(
                f.value.ifBlank { dynamic.time },
                style.timeFormat,
            )
            // 位置（v0.6.21）：手动值优先；否则结构化地址按用户顺序/开关拼装
            // （默认隐藏国家+邮编，输出与升级前一致）；都没有才回退整串
            FieldType.LOCATION -> f.value.ifBlank {
                assembledLocation(dynamic, style)
                    ?: dynamic.location.ifBlank { com.engcamera.i18n.I18n.t("未获取定位") }
            }
            FieldType.COUNT -> f.value.filter { it.isDigit() }.ifBlank { "1" }
            FieldType.WEATHER -> f.value.ifBlank {
                dynamic.weather.ifBlank { com.engcamera.i18n.I18n.t("未获取天气") }
            }
            else -> f.value
        }
        // LocationSource 的初始值/回退值是中文原文（如"未获取定位"），英文模式下要按当前语言显示
        val localized = localizeDynamicPlaceholder(text)
        // 位置文本经纬度开关：关闭时剥离尾部 "(lat, lng)" 段（手动值/自动值同规则）
        val located = if (f.type == FieldType.LOCATION && !style.showLatLng) stripLatLng(localized) else localized
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
        // 0=不限行（历史行为：正文/多行值照常换行）；>0 才钳行数并末尾省略
        maxLines: Int = 0,
        lineSpacingExtra: Float = 0f,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(lineSpacingExtra, 1f)
            .apply {
                if (maxLines > 0) {
                    setMaxLines(maxLines)
                    // 品牌 logo 挤占标题宽后：先换行，仍放不下则末尾省略（用户需求 2026-11-04）
                    setEllipsize(android.text.TextUtils.TruncateAt.END)
                }
            }
            .build()
}
