package com.engcamera.watermark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * 标注文字背景模板（v0.6.43，用户需求 2026-11-23；v0.6.44 增删）：
 * 点预览窗添加的文字可选一套背景样式，背景色随模板记忆。
 */
enum class TextTemplate(val id: String) {
    /** 无背景：纯色文字 + 阴影（旧行为） */
    NONE("none"),

    /** 箭头标签条：圆角白条 + 左侧**直角**尖角 */
    TAG("tag"),

    /** 圆点标签（v0.6.44，用户需求 2026-11-23）：对齐参考图——左侧一个实心圆点，
     *  再往右是**钝角**尖头（三个钝角收出来的箭头，不是直角转尖角），可单独配标记点颜色 */
    DOT_TAG("dot_tag"),

    /** 圆角卡片：整块实色/半透明圆角底 */
    CARD("card"),

    /** 描边文字：无底，文字加一圈对比色描边（花哨照片上最好读） */
    OUTLINE("outline"),
    ;

    companion object {
        /**
         * id → 模板。已下线的模板（v0.6.44 删除「色条 strip」「荧光笔 highlight」）
         * 回落到 CARD，存量标注不至于突然变成无背景。
         */
        fun of(id: String?): TextTemplate = when (id) {
            "strip", "highlight" -> CARD
            else -> values().firstOrNull { it.id == id } ?: NONE
        }
    }
}

/**
 * 标注文字渲染器（预览与烧录**共用同一套几何**，保证所见即所得）：
 * 锚点 = 背景盒左上角（无背景模板=文字左上角）；缩放/旋转都以锚点为轴。
 * 所有尺寸都是 textSize 的比例——预览按照片盒宽、烧录按成片宽取 textSize，
 * 两边等比，同一份数据在预览与成片上完全同构。
 */
object TextAnnotationRenderer {
    /** 字号 / 参照宽度（沿用 v195 的 0.05×照片宽） */
    const val TEXT_SIZE_RATIO = 0.05f

    private const val LINE_H_RATIO = 1.25f
    private const val PAD_RATIO = 0.34f
    /** 无背景模板的墨迹呼吸边（框贴着文字时线条会切字，留一圈窄边） */
    private const val BARE_PAD_RATIO = 0.12f
    private const val RADIUS_RATIO = 0.28f
    private const val TAIL_RATIO = 0.62f
    private const val TAIL_HALF_RATIO = 0.40f
    /** 圆点标签：左侧圆点 + 直角尖头占据的总宽（= 三角底边 x），文字从这里再往里 */
    private const val DOT_ZONE_RATIO = 1.02f
    /** 圆点半径 / 圆点中心 x / 外边框线宽（边框色=背景色，参考图那种白圈黄点） */
    private const val DOT_R_RATIO = 0.19f
    private const val DOT_CX_RATIO = 0.22f
    private const val DOT_RING_RATIO = 0.05f
    /** 尖头顶端 x（三角底边 x 用 DOT_ZONE_RATIO）——两者差=三角长度 */
    private const val DOT_TIP_RATIO = 0.50f
    /** 尖头**尖端圆角**半径（参考图尖端是圆的，不是尖的） */
    private const val DOT_TIP_RADIUS_RATIO = 0.12f
    private const val OUTLINE_RATIO = 0.11f

    /** 文字排版度量（局部未缩放坐标） */
    class Metrics(
        val width: Float,
        val height: Float,
        val lineH: Float,
        val ascent: Float,
        val lines: List<String>,
    )

    private fun textPaint(textSize: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        isFakeBoldText = true
        typeface = Typeface.DEFAULT_BOLD
    }

    fun metrics(text: String, textSize: Float): Metrics {
        val p = textPaint(textSize)
        val lines = if (text.isEmpty()) listOf("") else text.split('\n')
        val w = lines.maxOf { p.measureText(it) }
        val lineH = textSize * LINE_H_RATIO
        val h = (lines.size - 1) * lineH - p.ascent() + p.descent()
        return Metrics(w, h, lineH, p.ascent(), lines)
    }

    /** 背景盒尺寸（=元素布局尺寸；无背景模板时是文字墨迹 + 一圈窄呼吸边） */
    fun boxSize(template: TextTemplate, m: Metrics, textSize: Float): Pair<Float, Float> {
        val pad = textSize * PAD_RATIO
        val bare = textSize * BARE_PAD_RATIO
        return when (template) {
            TextTemplate.NONE, TextTemplate.OUTLINE ->
                (m.width + bare * 2f) to (m.height + bare * 2f)
            TextTemplate.TAG ->
                (m.width + pad * 2f + textSize * TAIL_RATIO) to (m.height + pad * 2f)
            // 圆点区比直角尖角更宽（要容下圆点 + 钝角头）
            TextTemplate.DOT_TAG ->
                (m.width + pad * 2f + textSize * DOT_ZONE_RATIO) to (m.height + pad * 2f)
            else -> (m.width + pad * 2f) to (m.height + pad * 2f)
        }
    }

    /** 文字块左上角相对锚点的偏移（标签条要给左侧尖角/圆点让位） */
    private fun contentOffset(template: TextTemplate, textSize: Float): Pair<Float, Float> {
        val pad = textSize * PAD_RATIO
        return when (template) {
            TextTemplate.NONE, TextTemplate.OUTLINE -> {
                val bare = textSize * BARE_PAD_RATIO
                bare to bare
            }
            TextTemplate.TAG -> (textSize * TAIL_RATIO + pad) to pad
            TextTemplate.DOT_TAG -> (textSize * DOT_ZONE_RATIO + pad) to pad
            else -> pad to pad
        }
    }

    /**
     * 绘制一条文字标注：锚点(0,0)=背景盒左上角，内容全部落在 [0,boxW]×[0,boxH]。
     * @param textSize 未缩放字号（px）；预览=照片盒宽×0.05，烧录=成片宽×0.05
     * @param markerColor 标记点颜色（只有 DOT_TAG 用）
     */
    fun draw(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        scale: Float,
        rotation: Float,
        textSize: Float,
        template: TextTemplate,
        textColor: Int,
        bgColor: Int,
        markerColor: Int = 0xFFFFC107.toInt(),
    ) {
        if (text.isEmpty()) return
        val m = metrics(text, textSize)
        val (boxW, boxH) = boxSize(template, m, textSize)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(rotation)
        canvas.scale(scale, scale)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        when (template) {
            TextTemplate.NONE, TextTemplate.OUTLINE -> Unit
            TextTemplate.TAG -> canvas.drawPath(tagPath(boxW, boxH, textSize), fill)
            TextTemplate.DOT_TAG -> {
                // 条体左端是**直线等腰三角形**尖头（尖端带圆角），参考图形状
                canvas.drawPath(dotTagPath(boxW, boxH, textSize), fill)
                // 实心圆点 + 与背景同色的外圈边框（参考图：黄点套一圈白边）
                val dotR = textSize * DOT_R_RATIO
                val dotCx = textSize * DOT_CX_RATIO
                val dotCy = boxH / 2f
                val ring = textSize * DOT_RING_RATIO
                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = bgColor
                    style = Paint.Style.STROKE
                    strokeWidth = ring * 2f
                }
                canvas.drawCircle(dotCx, dotCy, dotR + ring, ringPaint)
                val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = markerColor
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(dotCx, dotCy, dotR, marker)
            }
            TextTemplate.CARD -> {
                val r = textSize * RADIUS_RATIO
                canvas.drawRoundRect(0f, 0f, boxW, boxH, r, r, fill)
            }
        }

        val (ox, oy) = contentOffset(template, textSize)
        // 描边（OUTLINE：先粗描边再填充，描边色=背景色配置项）
        if (template == TextTemplate.OUTLINE) {
            val sp = textPaint(textSize).apply {
                color = bgColor
                style = Paint.Style.STROKE
                strokeWidth = textSize * OUTLINE_RATIO
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            m.lines.forEachIndexed { i, ln ->
                canvas.drawText(ln, ox, oy + i * m.lineH - m.ascent, sp)
            }
        }
        // 文字：有实底的模板不需要阴影；无底靠阴影保证可读
        val tp = textPaint(textSize).apply {
            color = textColor
            if (template == TextTemplate.NONE) {
                setShadowLayer(
                    textSize * 0.24f,
                    textSize * 0.08f,
                    textSize * 0.08f,
                    0x66000000,
                )
            }
        }
        m.lines.forEachIndexed { i, ln ->
            canvas.drawText(ln, ox, oy + i * m.lineH - m.ascent, tp)
        }
        canvas.restore()
    }

    /** 直角尖角标签条：左侧居中一个三角尖（两条直角边） */
    private fun tagPath(boxW: Float, boxH: Float, textSize: Float): Path {
        val tail = textSize * TAIL_RATIO
        val half = textSize * TAIL_HALF_RATIO
        val r = textSize * RADIUS_RATIO
        val left = tail
        val right = boxW
        val top = 0f
        val bottom = boxH
        val cy = boxH / 2f
        val p = Path()
        p.moveTo(left + r, top)
        p.lineTo(right - r, top)
        p.quadTo(right, top, right, top + r)
        p.lineTo(right, bottom - r)
        p.quadTo(right, bottom, right - r, bottom)
        p.lineTo(left + r, bottom)
        p.quadTo(left, bottom, left, bottom - r)
        p.lineTo(left, cy + half)
        p.lineTo(0f, cy)
        p.lineTo(left, cy - half)
        p.lineTo(left, top + r)
        p.quadTo(left, top, left + r, top)
        p.close()
        return p
    }

    /**
     * 圆点标签：条体左端是**直线等腰三角形**尖头（参考图），
     * 两条斜边是直线，只有**尖端**一处收成圆角。
     */
    private fun dotTagPath(boxW: Float, boxH: Float, textSize: Float): Path {
        val r = textSize * RADIUS_RATIO
        val right = boxW
        val top = 0f
        val bottom = boxH
        val cy = boxH / 2f
        // 三角底边所在竖线（= 条体左缘）与尖端
        val baseX = textSize * DOT_ZONE_RATIO
        val tipX = textSize * DOT_TIP_RATIO
        val tipR = textSize * DOT_TIP_RADIUS_RATIO
        // 尖端圆角切点：沿两条斜边各退 tipR
        val dLx = baseX - tipX
        val dLy = bottom - cy
        val lenL = kotlin.math.hypot(dLx.toDouble(), dLy.toDouble()).toFloat().coerceAtLeast(1f)
        val uLx = dLx / lenL
        val uLy = dLy / lenL
        val cutAx = tipX + uLx * tipR
        val cutAy = cy + uLy * tipR
        val cutBx = tipX + uLx * tipR
        val cutBy = cy - uLy * tipR
        val p = Path()
        // 顶边（从三角上底角开始往右）
        p.moveTo(baseX, top)
        p.lineTo(right - r, top)
        p.quadTo(right, top, right, top + r)
        p.lineTo(right, bottom - r)
        p.quadTo(right, bottom, right - r, bottom)
        // 底边回到三角下底角
        p.lineTo(baseX, bottom)
        // 下斜边 → 尖端圆角 → 上斜边（两条都是直线）
        p.lineTo(cutAx, cutAy)
        p.quadTo(tipX, cy, cutBx, cutBy)
        p.lineTo(baseX, top)
        p.close()
        return p
    }
}
