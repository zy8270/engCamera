package com.engcamera.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 右下角标识区（v0.6.31，Issue #5 用户需求 2026-11-15）：
 * 「品牌角标」+「防伪码」共用一块画面右下角，**同一个旋转矩阵**一起绘制。
 *
 * 为什么必须共用一处：
 * 这块区域是图片坐标系里固定的右下角，不跟随水印锚点/旋转，但**要跟随重力角**
 * （横拍时仍贴在物理右下角）。若角标与防伪条各画各的，就得把「重力系角点 → 屏幕系」
 * 的换算复制两遍，一旦某处漏改，预览与成片就会错位。
 *
 * 角标内容（用户拍板 2026-11-15 = 方案 A 纯文字两行，不带图形）：
 *   第一行「工程水印」大字
 *   第二行「相机」+ 浅色圆角块「真实可验」
 *   下面紧接「防伪 <码>」行
 *
 * 全部尺寸按**墨迹（ink）**定位而不是 fontMetrics：
 * CJK 字体的 ascent/descent 含大量行距留白，按它排版会把三行挤在一起。
 * 目标墨迹尺寸取自 imgcache/今日-打卡.jpg 实测（1920×2560 基准）：
 *   L1 墨高 58px / L2 墨高 44px / 圆角块高 54px / 块内文字墨高 43px
 *   块内左右内边距各 9px / L1→L2 间距 22px / 块底→防伪行 19px
 */
object BottomMarks {

    /** 品牌名第一行默认值 */
    const val DEFAULT_LINE1 = "工程水印"

    /** 品牌名第二行默认值（与圆角块同行） */
    const val DEFAULT_LINE2 = "相机"

    /** 圆角块默认文案 */
    const val DEFAULT_PILL = "真实可验"

    // ---- 目标尺寸（占画面宽比例，实测自 1920px 宽参考图）----
    /**
     * 两行共同的目标墨迹宽度（参考图 L1 285px / L2 286px，几乎完全相等）。
     * **两行必须等宽且右对齐**——用户需求 2026-11-15：像今日-打卡.jpg 那样两排对齐。
     */
    private const val BADGE_W_RATIO = 0.1484f

    /**
     * L1 字间距（em）。
     * 参考图 L1 字距约 19.5px/67px ≈ 0.29em，但那款字体单字宽本身不等
     * （今60 日49 水61 印57）；我们的 Noto 是等宽 CJK（每字=字号），
     * 照搬 0.29em 会明显显松散。0.08em 时反解出的字号 = 67.2px，
     * 与参考图字号 67px 几乎相同，视觉密度最接近。
     */
    private const val L1_SPACING = 0.08f

    /** 圆角块高度 / L2 字号（参考图 54 / 45.3 ≈ 1.19；块与「相机」同字号，视觉才齐） */
    private const val PILL_H_PER_TEXT = 1.25f

    /** 块内左右内边距 / 块高（参考图 9/54 ≈ 0.167） */
    private const val PILL_PAD_PER_H = 0.167f

    /** 块内文字字号 / L2 字号（参考图块内字与「相机」同字号，略小一点点） */
    private const val PILL_TEXT_PER_L2 = 0.98f

    /** L1 墨底 → L2 墨顶 22/1920 */
    private const val L1_L2_GAP_RATIO = 0.0115f
    /** 角标块底 → 防伪行墨顶 19/1920 */
    private const val BADGE_CODE_GAP_RATIO = 0.0099f
    /** 「相机」墨迹右缘 → 圆角块左缘 13/1920 */
    private const val LINE2_PILL_GAP_RATIO = 0.0068f
    /**
     * 圆角块圆角半径占块高比例：参考图实测约 5/54 ≈ 0.10，
     * 是**小圆角矩形**不是全胶囊（0.5）——这点很容易做错，做错会明显不像。
     */
    private const val PILL_RADIUS_RATIO = 0.10f

    /**
     * 圆角块底色：**半透明**（用户需求 2026-11-15「不能是全白的」）。
     * 参考图从灰度反解出 alpha ≈ 0.80，色相偏浅蓝 —— 背景要能透出来，
     * 实心浅色块在照片上会像贴了张白纸。
     */
    private const val PILL_BG = 0xCCDCE6F5.toInt()

    /** 圆角块文字色（参考图实测深墨 0x484B4F） */
    private const val PILL_FG = 0xFF484B4F.toInt()

    /**
     * 导入图片的最大宽度占画面宽比例。
     * 默认角标块宽 = BADGE_W_RATIO，导入图按同一量级略放宽，
     * 给大了会糊掉小半张照片（实测 0.20W 已偏大）。
     */
    private const val IMAGE_MAX_W_RATIO = 0.16f

    /** 导入图片的最大高度占画面高比例（约等于默认角标块高 0.085H 的两倍余量） */
    private const val IMAGE_MAX_H_RATIO = 0.09f

    /** 墨迹量尺（复用，避免每次绘制分配） */
    private val inkBounds = Rect()

    /**
     * 画右下角整块（防伪条 + 品牌角标）。
     *
     * @param code 防伪码；null/空 = 不画防伪条
     * @param badgeImage 外部导入的角标图片；null = 画内置默认文字角标
     * @param rollDeg 重力角（0/±90/180）：把「重力系右下角」换算到屏幕系
     * @param line1 角标第一行文字（空 = 默认「工程水印」）
     * @param line2 角标第二行文字（空 = 默认「相机」）
     * @param pill 圆角块文字（空 = 默认「真实可验」）
     *
     * 整块**不跟随**水印整体透明度（与防伪条原规则一致，底部标识恒清晰）。
     */
    fun drawBottomCorner(
        canvas: Canvas,
        canvasW: Int,
        canvasH: Int,
        code: String?,
        badgeImage: Bitmap?,
        rollDeg: Float = 0f,
        line1: String = "",
        line2: String = "",
        pill: String = "",
    ) {
        val hasCode = !code.isNullOrBlank()
        val w = canvasW.coerceAtLeast(1).toFloat()
        val h = canvasH.coerceAtLeast(1).toFloat()
        val norm = ((rollDeg.roundToInt() % 360) + 360) % 360

        // 重力系里的右下角落点（画面坐标系）。与 AntiCounterfeit 同一套留白：
        // 把「整块」的右下角当锚点，块内所有元素相对它向左上排布。
        val right = w - AntiCounterfeit.stripRightInset(w)
        val bottom = h - AntiCounterfeit.stripBottomInset(h)

        canvas.save()
        // 排布基准点：norm==0 时就是图片坐标里的右下角；非 0 时要先换算到屏幕系，
        // 并以换算后的点为基准（画布已绕该点旋转，局部坐标与重力系同构）。
        // 若这里继续用 right/bottom 排布，画布旋转会把整块搬到错误位置。
        val anchorX: Float
        val anchorY: Float
        if (norm != 0) {
            val rad = Math.toRadians(norm.toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            val vx = right / w - 0.5f
            val vy = bottom / h - 0.5f
            anchorX = (0.5f + (vx * cos - vy * sin)) * w
            anchorY = (0.5f + (vx * sin + vy * cos)) * h
            canvas.rotate(norm.toFloat(), anchorX, anchorY)
        } else {
            anchorX = right
            anchorY = bottom
        }

        if (badgeImage != null) {
            drawImageBadge(canvas, anchorX, anchorY, badgeImage, code.orEmpty(), w, h)
        } else {
            drawDefaultBadge(canvas, anchorX, anchorY, code.orEmpty(), w, line1, line2, pill)
        }
        if (hasCode) {
            drawCode(canvas, anchorX, anchorY, code!!, w)
        }
        canvas.restore()
    }

    /**
     * 反解 L1 字号，使「文字自然宽 + 字间距」正好撑满 [targetW]。
     *
     * 为什么用字间距而不是纯字号：等宽 CJK 下 4 字自然宽 = 4×size，
     * 只靠字号撑满会让字过大；只靠字距又显松散。固定一个小字距（[L1_SPACING]）
     * 再解字号，得到的字号（≈67px）与参考图完全吻合，两行也自然等宽。
     */
    /** 字号的绝对上限（占画面宽比例）：防止极短文案把字撑成巨无霸 */
    private const val MAX_SIZE_RATIO = 0.040f

    /** 字号的绝对下限（占画面宽比例）：防止超长文案把字缩成蚂蚁 */
    private const val MIN_SIZE_RATIO = 0.012f

    /**
     * 反解 L1 字号，使「文字自然宽 + 字间距」正好撑满 [targetW]，并钳在合理区间。
     *
     * 为什么要钳：用户可自定义文案，1 个字时反解出的字号会大到离谱（实测「建」
     * 单字被撑到整块宽度，药丸也跟着变巨），10 个字时又会小到看不清。
     *
     * 为什么用字间距而不是纯字号：等宽 CJK 下 4 字自然宽 = 4×size，
     * 只靠字号撑满会让字过大；只靠字距又显松散。固定一个小字距（[L1_SPACING]）
     * 再解字号，得到的字号（≈67px）与参考图完全吻合。
     */
    private fun fitLine1Size(text: String, targetW: Float, canvasW: Float): Float {
        val n = text.length
        if (n < 1) return 0f
        val p = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 100f
            typeface = badgeFont()
            letterSpacing = 0f
        }
        val naturalAt100 = p.measureText(text)
        if (naturalAt100 <= 0f) return 0f
        val k = if (n < 2) {
            // 单字：不靠字间距，直接按自然宽撑满
            targetW / naturalAt100
        } else {
            // 方程：naturalAt100*k + (100k)*L1_SPACING*(n-1) = targetW
            targetW / (naturalAt100 + 100f * L1_SPACING * (n - 1))
        }
        return (100f * k).coerceIn(canvasW * MIN_SIZE_RATIO, canvasW * MAX_SIZE_RATIO)
    }

    /**
     * 画内置默认角标：L1 大字 + L2 + 半透明圆角块，两行等宽右对齐。
     * [line1]/[line2]/[pill] 为自定义文案，空串回退默认值。
     */
    private fun drawDefaultBadge(
        canvas: Canvas,
        right: Float,
        bottom: Float,
        code: String,
        w: Float,
        line1: String,
        line2: String,
        pill: String,
    ) {
        val targetW = w * BADGE_W_RATIO
        // 空串回退默认文案（用户在编辑器里清空 = 用默认）
        val text1 = line1.ifBlank { DEFAULT_LINE1 }
        val text2 = line2.ifBlank { DEFAULT_LINE2 }
        val pillText = pill.ifBlank { DEFAULT_PILL }

        // ---- L1：字号按「加固定小字距正好撑满 targetW」反解（比 L2 大，满足"放大"要求）----
        val l1Size = fitLine1Size(text1, targetW, w)
        val p1 = textPaint(l1Size, w).apply { letterSpacing = L1_SPACING }

        // ---- L2：字号反推，使「第二行 + 间距 + 药丸」正好等于 targetW ----
        // 药丸内字与块高都按 L2 字号成比例 → 整式对 L2 字号线性，一次求解
        val probe = 100f
        val p2Probe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = probe
            typeface = badgeFont()
        }
        val pillTextProbe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = probe * PILL_TEXT_PER_L2
            typeface = badgeFont()
        }
        val pillHPprobe = probe * PILL_H_PER_TEXT
        // 除固定间距外，其余各项都与 L2 字号成正比 → 整式对字号线性，一次求解即可
        val unitW = paintMeasure(p2Probe, text2) +
            paintMeasure(pillTextProbe, pillText) +
            pillHPprobe * PILL_PAD_PER_H * 2
        val gapAbs = w * LINE2_PILL_GAP_RATIO
        // 同样要钳区间：自定义文案过长/过短时避免字号失控（见 fitLine1Size 注释）
        val l2Size = ((targetW - gapAbs) / unitW * probe)
            .coerceIn(w * MIN_SIZE_RATIO, w * MAX_SIZE_RATIO)
        val pillSize = l2Size * PILL_TEXT_PER_L2
        val pillH = l2Size * PILL_H_PER_TEXT
        val pillPad = pillH * PILL_PAD_PER_H

        val p2 = textPaint(l2Size, w)
        val pillTextPaint = textPaint(pillSize, w)

        // ---- 自下而上算三层的 y（全部按墨迹边界）----
        // 防伪行基线就在 bottom，其墨顶 = bottom - 墨迹高（用真实码量，别用空串）
        val codePaint = AntiCounterfeit.stripPaint(w)
        val codeInkTop = if (code.isNotBlank()) {
            bottom - inkHeight(codePaint, AntiCounterfeit.stripText(code))
        } else {
            bottom
        }
        // 角标块底 = 防伪行墨顶再往上留间距
        val badgeBottom = codeInkTop - w * BADGE_CODE_GAP_RATIO
        // L2 行（圆角块）：块底 = 角标块底
        val pillBottom = badgeBottom
        val pillTop = pillBottom - pillH
        // L1 墨底 = L2 墨顶往上留间距；L2 墨迹在圆角块里垂直居中
        val l2InkH = inkHeight(p2, text2)
        val l2InkTop = pillTop + (pillH - l2InkH) / 2f
        val l1InkBottom = l2InkTop - w * L1_L2_GAP_RATIO

        // ---- L1（右对齐，撑满 targetW）----
        val l1InkH = inkHeight(p1, text1)
        drawTextInkRight(canvas, p1, text1, right, l1InkBottom - l1InkH, l1InkH)

        // ---- 半透明圆角块（右端贴右留白）----
        val pillW = paintMeasure(pillTextPaint, pillText) + pillPad * 2
        val pillRect = RectF(right - pillW, pillTop, right, pillBottom)
        val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PILL_BG
            setShadowLayer(w * 0.004f, w * 0.001f, w * 0.0015f, 0x99000000.toInt())
        }
        val radius = pillH * PILL_RADIUS_RATIO
        canvas.drawRoundRect(pillRect, radius, radius, pillBg)
        // 块内文字：无阴影、深墨色，垂直居中
        val pillFg = Paint(pillTextPaint).apply {
            color = PILL_FG
            clearShadowLayer()
        }
        val pillInkH = inkHeight(pillFg, pillText)
        drawTextInkLeft(canvas, pillFg, pillText, pillRect.left + pillPad, pillTop + (pillH - pillInkH) / 2f, pillInkH)

        // ---- L2：右对齐到圆角块左侧，与块垂直居中 ----
        val line2Right = pillRect.left - gapAbs
        drawTextInkRight(canvas, p2, text2, line2Right, l2InkTop, l2InkH)
    }

    /**
     * 画外部导入的角标图片：右对齐。
     * 图片底边同样要给防伪行让位（否则两者重叠），与默认角标的 [badgeBottom] 规则一致。
     */
    private fun drawImageBadge(
        canvas: Canvas,
        right: Float,
        bottom: Float,
        bmp: Bitmap,
        code: String,
        w: Float,
        h: Float,
    ) {
        val maxW = w * IMAGE_MAX_W_RATIO
        val maxH = h * IMAGE_MAX_H_RATIO
        val scale = min(maxW / bmp.width, maxH / bmp.height).coerceAtMost(1f)
        val iw = bmp.width * scale
        val ih = bmp.height * scale
        // 与默认角标同一套「给防伪行让位」规则
        val codeInkTop = if (code.isNotBlank()) {
            bottom - inkHeight(AntiCounterfeit.stripPaint(w), AntiCounterfeit.stripText(code))
        } else {
            bottom
        }
        val badgeBottom = codeInkTop - if (code.isNotBlank()) w * BADGE_CODE_GAP_RATIO else 0f
        val dst = RectF(right - iw, badgeBottom - ih, right, badgeBottom)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            setShadowLayer(w * 0.004f, w * 0.001f, w * 0.0015f, 0x99000000.toInt())
        }
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    /** 画防伪码行（沿用 AntiCounterfeit 的画笔与文案，保证与旧版完全一致） */
    private fun drawCode(canvas: Canvas, right: Float, bottom: Float, code: String, w: Float) {
        val paint = AntiCounterfeit.stripPaint(w)
        val text = AntiCounterfeit.stripText(code)
        canvas.drawText(text, right - paint.measureText(text), bottom, paint)
    }

    // ---------- 按墨迹排版的辅助 ----------

    /** 量出文字墨迹高度 */
    private fun inkHeight(paint: Paint, text: String): Float {
        paint.getTextBounds(text, 0, text.length, inkBounds)
        return inkBounds.height().toFloat().coerceAtLeast(1f)
    }

    private fun paintMeasure(paint: Paint, text: String): Float = paint.measureText(text)

    /**
     * 右对齐画文字，且让**墨迹顶**落在 [inkTop]。
     * 基线 = 墨迹顶 + 墨迹在基线上方的高度（-bounds.top）。
     * 右端用 measureText 计算；有 letterSpacing 时 measureText 已含除末字外的字距，
     * 而末字后不会绘制字距，所以 measureText 正是「可见墨迹宽」，右对齐准确。
     */
    private fun drawTextInkRight(canvas: Canvas, paint: Paint, text: String, right: Float, inkTop: Float, inkH: Float) {
        paint.getTextBounds(text, 0, text.length, inkBounds)
        val baseline = inkTop - inkBounds.top
        canvas.drawText(text, right - paint.measureText(text), baseline, paint)
    }

    /** 左对齐画文字，且让**墨迹顶**落在 [inkTop] */
    private fun drawTextInkLeft(canvas: Canvas, paint: Paint, text: String, left: Float, inkTop: Float, inkH: Float) {
        paint.getTextBounds(text, 0, text.length, inkBounds)
        val baseline = inkTop - inkBounds.top
        canvas.drawText(text, left, baseline, paint)
    }

    /** 白字 + 轻阴影（与防伪条同一套参数，整块观感统一） */
    /**
     * 白字 + 轻阴影（与防伪条同一套参数，整块观感统一）。
     * 返回 TextPaint：L1 需要 letterSpacing 撑满行宽，那是 TextPaint 才有的属性。
     */
    private fun textPaint(size: Float, canvasW: Float): android.text.TextPaint =
        android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size
            typeface = badgeFont()
            setShadowLayer(canvasW * 0.004f, canvasW * 0.001f, canvasW * 0.0015f, 0x99000000.toInt())
        }

    /** 角标专用 CJK 粗体（Application.onCreate 预热；缺失回退 DEFAULT_BOLD 不崩） */
    @Volatile private var badgeTypeface: Typeface? = null

    fun ensureFonts(context: android.content.Context) {
        if (badgeTypeface == null) {
            badgeTypeface = runCatching {
                Typeface.createFromAsset(context.assets, "fonts/noto_sc_badge.ttf")
            }.getOrNull()
        }
    }

    private fun badgeFont(): Typeface = badgeTypeface ?: Typeface.DEFAULT_BOLD
}
