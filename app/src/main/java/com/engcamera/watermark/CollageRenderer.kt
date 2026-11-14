package com.engcamera.watermark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import com.engcamera.data.CellFill
import com.engcamera.data.CollageFieldDef
import com.engcamera.data.CollageItem
import com.engcamera.data.CollageSettings
import com.engcamera.data.CollageTemplate
import com.engcamera.data.assembledLocation
import com.engcamera.data.stripLatLng
import com.engcamera.i18n.I18n
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 拼图渲染器：版式计算 + 整页装饰绘制。
 *
 * 职责边界：本类只管**页面骨架**（页眉 / 分隔线 / 白缝网格位置 / 备注 / 落款），
 * 照片的解码、逐张烧水印、贴图由调用方（CameraViewModel.saveCollage）负责——因为那部分
 * 要按每张照片冻结的 WatermarkState 调 WatermarkRenderer.drawOnCanvas，逐张回收位图控内存。
 *
 * 两套版式家族：
 * - **旧版**（无模板 / 客户拜访 / 工作汇报）：原尺寸并排、零白边、红头公文页眉。
 * - **新版**（v0.6.26，蓝色工作 / 红色客户 / 红色工作）：固定 3:4 等格，照片等比内嵌、
 *   格子内留白用浅灰 #F0F0F0 或照片自身模糊铺满。逐项数值取自 imgcache 三张参考图
 *   的像素实测（见各常量注释里的「实测」）。
 *
 * 版式数值全部按**页面宽度比例**表达，因此超内存预算整体等比缩放时文字自动跟随，
 * 不需要另一套字号表。
 */
object CollageRenderer {

    /** 白缝宽度 = 页宽 × 此比例（旧版；最小 8px，与 mock 一致） */
    private const val GAP_RATIO = 0.016f

    /** 页面外边距 = 白缝 × 此倍数（旧版） */
    private const val MARGIN_GAP_MULT = 1.7f

    /** 红头带高度 = 页宽 × 此比例（旧版） */
    private const val BAND_RATIO = 0.082f

    // ---- 配色（旧版，与 mock 逐项对齐）----
    const val RED = 0xFFC0272D.toInt()
    const val INK = 0xFF1C1C1E.toInt()
    const val LINE = 0xFFAAACB2.toInt()
    private const val BAND_DATE = 0xFFFFE4E4.toInt()
    private const val WHITE = Color.WHITE

    // ================= 新版：3:4 固定等格（v0.6.26）=================

    /** 格子宽高比（3:4 竖格；参考图实测 901/1208 = 0.7459，取标准 3:4） */
    private const val CELL_ASPECT = 0.75f

    /** 格子留白浅灰底（今日系参考图实测 RGB 240,240,240） */
    const val FILL_GRAY = 0xFFF0F0F0.toInt()

    // ---- 蓝色工作（马克-拼图-工作.jpg，2520×3557）----
    private const val BLUE_MARGIN_R = 0.0139f // 实测左右边距 35/2520
    private const val BLUE_GAP_R = 0.0135f // 实测缝 34/2520
    private const val BLUE_TITLE_INK_R = 0.0516f // 实测标题墨高 130/2520
    private const val BLUE_TITLE_TOP_R = 0.0068f // 实测标题墨顶 17/2520
    private const val BLUE_TITLE_TO_RULE_R = 0.0167f // 实测 42/2520
    private const val BLUE_RULE1_R = 0.00476f // 实测细线 12/2520
    private const val BLUE_RULE_GAP_R = 0.00437f // 实测 11/2520
    private const val BLUE_RULE2_R = 0.00873f // 实测粗线 22/2520
    private const val BLUE_RULE_TO_GRID_R = 0.0147f // 实测 37/2520
    const val BLUE = 0xFF2E5BFF.toInt() // 实测标题/线条同色

    // ---- 今日系公共（2000 宽参考图）----
    private const val RED_MARGIN_R = 0.044f // 实测左右边距 88/2000
    private const val RED_GAP_R = 0.0105f // 实测缝 21/2000
    private const val RED_RULE_R = 0.0055f // 实测分隔线 11/2000
    const val CRIMSON = 0xFFD0011B.toInt() // 实测标题/线条同色
    const val DATE_INK = 0xFF222222.toInt() // 实测日期字色

    // ---- 红色客户（今日-拼图-客户.jpg，2000×3308）----
    private const val RC_TITLE_TOP_R = 0.1445f // 实测标题墨顶 289/2000
    private const val RC_TITLE_INK_R = 0.066f // 实测 132/2000
    private const val RC_TITLE_TO_DATE_R = 0.0685f // 实测 137/2000
    private const val RC_DATE_INK_R = 0.040f // 实测 80/2000
    private const val RC_DATE_TO_RULE_R = 0.0415f // 实测 83/2000
    private const val RC_RULE_TO_GRID_R = 0.0315f // 实测 63/2000

    // ---- 红色工作（今日-拼图-工作.jpg，2000×2985）----
    private const val RW_FRAME_TOP_R = 0.044f // 实测边框上沿 88/2000
    private const val RW_FRAME_RULE_R = 0.0035f // 实测顶线 7/2000
    private const val RW_FRAME_SIDE_R = 0.0030f // 实测侧边线宽 6/2000
    /**
     * 肩部曲线：对参考图外轮廓 61 个采样点做三次贝塞尔最小二乘拟合
     * （最大残差 1.9px）。参考图的肩部是**内凹曲线**（两端接近直线、中段弯），
     * 用圆或直线斜切都对不上，必须用贝塞尔。
     * 四个控制点均以「左边线 / 顶线交点」为原点、按页宽取比例。
     */
    private const val RW_SHOULDER_P0_DX_R = 0.02400f // 起点（顶线内收点）横向
    private const val RW_SHOULDER_P0_DY_R = 0.00350f // 起点纵向（=顶线厚度一半附近）
    private const val RW_SHOULDER_P1_DX_R = 0.02225f
    private const val RW_SHOULDER_P1_DY_R = 0.01643f
    private const val RW_SHOULDER_P2_DX_R = 0.01102f
    private const val RW_SHOULDER_P2_DY_R = 0.02701f
    private const val RW_SHOULDER_P3_DX_R = 0.00000f // 终点（左边线顶点）
    private const val RW_SHOULDER_P3_DY_R = 0.03350f
    private const val RW_FRAME_TO_TITLE_R = 0.0265f // 实测 53/2000
    private const val RW_TITLE_INK_R = 0.0685f // 实测 137/2000
    private const val RW_TITLE_TO_BOT_R = 0.038f // 实测 76/2000
    private const val RW_BOT_THICK_R = 0.0070f // 实测 14/2000
    private const val RW_BOT_GAP_R = 0.0060f // 实测 12/2000
    private const val RW_BOT_THIN_R = 0.0030f // 实测 6/2000
    private const val RW_BOT_TO_GRID_R = 0.0095f // 实测 20/2000
    private const val RW_TITLE_TRACKING_R = 0.010f // 实测字距 20/2000（参考图明显疏排）
    /** 红色工作可选日期行（参考图无日期，开启后画在标题与底线之间） */
    private const val RW_DATE_INK_R = 0.030f
    private const val RW_DATE_GAP_R = 0.014f

    /** CJK 字形墨高 ≈ 字号 × 此系数（Noto CJK 系字面实测 0.88） */
    private const val INK_TO_TEXT_SIZE = 0.88f

    /**
     * 一次算好的版式。所有尺寸都是像素，`scale` 为相对原生分辨率的缩放比
     * （1.0 = 完全原生，< 1 表示因内存预算整体等比缩小）。
     *
     * 旧版用「行内等高填满」（justified rows）布局：每行照片高度相同、宽度按各自宽高比
     * 分配。新版用「3:4 固定等格」布局：格子尺寸全页统一，照片等比内嵌，留白填充。
     */
    data class Layout(
        val pageW: Int,
        val pageH: Int,
        val scale: Float,
        /** true = 因内存预算做了整体等比缩小 */
        val clamped: Boolean,
        val cols: Int,
        val rows: Int,
        val gap: Int,
        val margin: Int,
        val bandH: Int,
        val headerH: Int,
        val footerH: Int,
        val gridTop: Int,
        /** 每张照片在页面上的目标矩形（顺序与输入 items 一致） */
        val cells: List<Rect>,
        // ---- 已排好版的文字内容（页眉信息行 / 备注折行 / 落款）----
        val title: String,
        val bandDate: String,
        val infoRows: List<Pair<String, String>>,
        val noteLabel: String,
        val noteLines: List<String>,
        val signText: String,
        /** 新版固定等格布局（true 时 cells 是格子矩形，照片需等比内嵌 + 留白填充） */
        val fixedGrid: Boolean = false,
        /** 格子留白填充方式（仅 fixedGrid 有效） */
        val fill: CellFill = CellFill.NONE,
    ) {
        /** 第 index 张照片的矩形 */
        fun cellRect(index: Int): Rect = cells.getOrElse(index) { Rect(0, 0, 1, 1) }

        /** 网格区总高（最底一张的下沿 - 网格顶部） */
        val gridH: Int get() = cells.maxOfOrNull { it.bottom }?.minus(gridTop) ?: 0
    }

    // ---------- 字体 ----------

    private val titleTypeface: Typeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }
    private val bodyTypeface: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }
    /** 今日系标题：衬线（宋体观感，参考图为宋/明体） */
    private val serifTypeface: Typeface by lazy { Typeface.create("serif", Typeface.BOLD) }

    private fun titlePaint(size: Float, color: Int = WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = titleTypeface
        textSize = size
        this.color = color
    }

    private fun serifTitlePaint(size: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = serifTypeface
        textSize = size
        this.color = color
    }

    private fun bodyPaint(size: Float, color: Int = INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bodyTypeface
        textSize = size
        this.color = color
    }

    // ---------- 版式 ----------

    /**
     * 计算整页版式。按模板家族分派：旧版走 justified rows，新版走 3:4 固定等格。
     *
     * @param items 连拍照片（按用户排序后的顺序），至少 1 张
     * @param cols 列数 1~4
     * @param settings 拼图设置（决定模板与信息行内容）
     * @param maxPixels 画布像素预算；超了整体等比缩小
     */
    fun layout(
        items: List<CollageItem>,
        cols: Int,
        settings: CollageSettings,
        maxPixels: Long,
    ): Layout {
        val tpl = settings.template
        return if (tpl.isFixedGrid) layoutFixed(items, cols, settings, maxPixels)
        else layoutJustified(items, cols, settings, maxPixels)
    }

    // ---------------- 新版：3:4 固定等格 ----------------

    /** 模板家族的水平几何（边距/缝比例） */
    private fun marginRatioOf(tpl: CollageTemplate): Float =
        if (tpl == CollageTemplate.BLUE_WORK) BLUE_MARGIN_R else RED_MARGIN_R

    private fun gapRatioOf(tpl: CollageTemplate): Float =
        if (tpl == CollageTemplate.BLUE_WORK) BLUE_GAP_R else RED_GAP_R

    /**
     * 页眉总高（从页面顶到网格顶），按页宽比例算。数值来自参考图像素实测，
     * 每项含义见常量注释。[dateOn] 只影响红色工作（可选日期行）。
     */
    private fun fixedHeaderH(tpl: CollageTemplate, pageW: Int, dateOn: Boolean): Int {
        val w = pageW.toFloat()
        return when (tpl) {
            CollageTemplate.BLUE_WORK ->
                (w * (BLUE_TITLE_TOP_R + BLUE_TITLE_INK_R + BLUE_TITLE_TO_RULE_R +
                    BLUE_RULE1_R + BLUE_RULE_GAP_R + BLUE_RULE2_R + BLUE_RULE_TO_GRID_R)).roundToInt()

            CollageTemplate.RED_CLIENT -> {
                val dateBlock = if (dateOn) w * (RC_TITLE_TO_DATE_R + RC_DATE_INK_R) else 0f
                (w * (RC_TITLE_TOP_R + RC_TITLE_INK_R + RC_DATE_TO_RULE_R +
                    RED_RULE_R + RC_RULE_TO_GRID_R) + dateBlock).roundToInt()
            }

            CollageTemplate.RED_WORK -> {
                val dateBlock = if (dateOn) w * (RW_DATE_GAP_R + RW_DATE_INK_R) else 0f
                // 肩部竖向跨度用贝塞尔终点 P3 的 dy（拟合值），不是旧的外扩常量
                (w * (RW_FRAME_TOP_R + RW_SHOULDER_P3_DY_R + RW_FRAME_TO_TITLE_R +
                    RW_TITLE_INK_R + RW_TITLE_TO_BOT_R + RW_BOT_THICK_R + RW_BOT_GAP_R +
                    RW_BOT_THIN_R + RW_BOT_TO_GRID_R) + dateBlock).roundToInt()
            }

            else -> 0
        }
    }

    /** 该模板的页眉日期是否启用（红色客户默认开、红色工作默认关） */
    private fun headerDateOn(tpl: CollageTemplate, settings: CollageSettings): Boolean {
        val def = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == "date" } ?: return false
        return settings.enabledOf(def)
    }

    /**
     * 新版布局：格子宽高比固定 3:4，页宽由「不放大任一照片」反推，再按像素预算整体缩。
     *
     * 页宽闭式解：contentW = pageW·(1-2·marginR)，cellW = (contentW - gap·(c-1))/c，
     * 代入 gap = pageW·gapR 得 pageW = cellW·c / (1 - 2·marginR - gapR·(c-1))。
     */
    private fun layoutFixed(
        items: List<CollageItem>,
        cols: Int,
        settings: CollageSettings,
        maxPixels: Long,
    ): Layout {
        val c = cols.coerceIn(1, 4)
        val tpl = settings.template
        val n = items.size
        val rows = max(1, (n + c - 1) / c)
        val marginR = marginRatioOf(tpl)
        val gapR = gapRatioOf(tpl)

        /** cellW → pageW 的闭式反解 */
        fun pageWidthFor(cellW: Float): Int {
            val denom = (1f - 2 * marginR - gapR * (c - 1)).coerceAtLeast(0.1f)
            return (cellW * c / denom).roundToInt().coerceAtLeast(c * 8 + 16)
        }

        // 不放大任一照片的最大格子宽：横图受宽度限制、竖图受高度（=cellW/CELL_ASPECT）限制
        val noUpscaleCellW = items.minOf { it2 ->
            val a = it2.aspect.coerceAtLeast(0.01f)
            val w = it2.uprightWidth.toFloat()
            if (a >= CELL_ASPECT) w else w * CELL_ASPECT / a
        }.coerceAtLeast(1f)

        // 先按原生尺寸估总像素，超预算求整体缩放比（迭代收敛：页眉页脚按页宽比例自动跟随）
        var scale = 1f
        repeat(6) {
            val pw = pageWidthFor(noUpscaleCellW * scale)
            val gap = max(8, (pw * gapR).roundToInt())
            val margin = (pw * marginR).roundToInt()
            val cellW = ((pw - 2 * margin - gap * (c - 1)).toFloat() / c).coerceAtLeast(1f)
            val cellH = cellW / CELL_ASPECT
            val gridH = rows * cellH + (rows - 1) * gap
            val ph = fixedHeaderH(tpl, pw, headerDateOn(tpl, settings)) + gridH + gap * 2 +
                fixedFooterH(tpl, pw, settings) + margin
            val total = pw.toDouble() * ph
            if (total <= maxPixels) return@repeat
            val next = scale * sqrt(maxPixels.toDouble() / total).toFloat() * 0.98f
            if (next >= scale) return@repeat
            scale = next.coerceAtLeast(0.05f)
        }

        val pageW = pageWidthFor(noUpscaleCellW * scale)
        val gap = max(8, (pageW * gapR).roundToInt())
        val margin = (pageW * marginR).roundToInt()
        val contentW = (pageW - 2 * margin).coerceAtLeast(1)
        val cellW = ((contentW - gap * (c - 1)).toFloat() / c).coerceAtLeast(1f)
        val cellH = cellW / CELL_ASPECT
        val headerH = fixedHeaderH(tpl, pageW, headerDateOn(tpl, settings))

        // ---- 文字内容 ----
        val noteDef = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == "note" }
        val noteOn = noteDef != null && settings.enabledOf(noteDef)
        val noteLabel = if (noteOn) "${I18n.t("备注")}：" else ""
        val noteText = if (noteOn) settings.valueOf(noteDef!!) else ""
        val notePaint = bodyPaint(pageW * 0.0235f)
        val noteMaxW = (pageW - 2 * margin - notePaint.measureText(noteLabel)).coerceAtLeast(pageW * 0.2f)
        val noteLines = if (noteOn) wrapText(notePaint, noteText, noteMaxW) else emptyList()
        val noteLH = (pageW * 0.0235f * 1.75f).roundToInt()

        val headerDate = fixedHeaderDate(tpl, settings, items)

        val gridTop = headerH
        val gridH = (rows * cellH).roundToInt() + (rows - 1) * gap
        val footerH = noteLines.size * noteLH
        val pageH = gridTop + gridH + gap * 2 + footerH + margin

        // ---- 逐格落矩形（全页统一尺寸，左对齐 + 末行居中）----
        val cells = ArrayList<Rect>(n)
        for (r in 0 until rows) {
            val from = r * c
            val to = min((r + 1) * c, n)
            if (to <= from) break
            val count = to - from
            val usedW = count * cellW + (count - 1) * gap
            var x = margin + ((contentW - usedW) / 2f)
            val y = gridTop + r * (cellH + gap)
            for (i in from until to) {
                cells.add(
                    Rect(
                        x.roundToInt(), y.roundToInt(),
                        (x + cellW).roundToInt(), (y + cellH).roundToInt(),
                    ),
                )
                x += cellW + gap
            }
        }

        return Layout(
            pageW = pageW,
            pageH = pageH,
            scale = scale,
            clamped = scale < 1f,
            cols = c,
            rows = rows,
            gap = gap,
            margin = margin,
            bandH = 0,
            headerH = headerH,
            footerH = footerH,
            gridTop = gridTop,
            cells = cells,
            title = settings.titleOf(tpl),
            bandDate = headerDate,
            infoRows = emptyList(),
            noteLabel = noteLabel,
            noteLines = noteLines,
            signText = "",
            fixedGrid = true,
            fill = tpl.fill,
        )
    }

    /** 新版页脚高（只有备注折行，无落款） */
    private fun fixedFooterH(tpl: CollageTemplate, pageW: Int, settings: CollageSettings): Int {
        val noteDef = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == "note" } ?: return 0
        if (!settings.enabledOf(noteDef)) return 0
        val noteLH = (pageW * 0.0235f * 1.75f).roundToInt()
        val est = max(1, (settings.valueOf(noteDef).length + 27) / 28)
        return est * noteLH
    }

    /** 新版页眉里的日期文本（红色客户/红色工作支持；关闭或蓝版返回空） */
    private fun fixedHeaderDate(
        tpl: CollageTemplate,
        settings: CollageSettings,
        items: List<CollageItem>,
    ): String {
        val def = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == "date" } ?: return ""
        if (!settings.enabledOf(def)) return ""
        // 参考图版式：2026年11月15日（中文模板）；英文环境退回 ISO 短日期
        val iso = autoValueOf(def, items)
        if (iso.length < 10) return iso
        if (!I18n.isZh()) return iso
        val y = iso.substring(0, 4)
        val mo = iso.substring(5, 7).trimStart('0')
        val d = iso.substring(8, 10).trimStart('0')
        return "${y}年${mo}月${d}日"
    }

    // ---------------- 旧版：原尺寸并排（justified rows）----------------

    private fun layoutJustified(
        items: List<CollageItem>,
        cols: Int,
        settings: CollageSettings,
        maxPixels: Long,
    ): Layout {
        val c = cols.coerceIn(1, 4)
        val tpl = settings.template
        val n = items.size
        val rows = max(1, (n + c - 1) / c)

        // 每张照片的宽高比（宽/高），已按 EXIF 转正
        val aspects = items.map { it.aspect.coerceAtLeast(0.01f) }

        // 页宽 = 内容宽 + 两条页边距；缝与边距都按页宽比例，闭式解出
        //   pageW = contentW + 2*margin，margin = gap*MULT，gap = GAP_RATIO*pageW
        fun pageWidthFor(contentW: Float) =
            (contentW / (1f - 2 * MARGIN_GAP_MULT * GAP_RATIO)).roundToInt()
                .coerceAtLeast(c * 8 + 16)

        /**
         * 行布局：**每张照片保持原生尺寸**（完全不缩放、不裁剪，用户拍板 2026-11-10），
         * 行内按原生宽并排，行高 = 该行最高照片的原生高。
         */
        fun computeRows(sc: Float = 1f): List<RowLayout> {
            return (0 until rows).map { r ->
                val from = r * c
                val to = min((r + 1) * c, n)
                if (to <= from) return@map RowLayout(emptyList(), 0)
                val widths = (from until to).map {
                    (items[it].uprightWidth * sc).roundToInt().coerceAtLeast(1)
                }
                val rowH = (from until to).maxOf {
                    (items[it].uprightHeight * sc).roundToInt()
                }.coerceAtLeast(1)
                RowLayout(widths, rowH)
            }
        }

        // 内容宽 = 最宽一行的原生宽和 + 行内缝。页宽反推迭代两次：
        // gap 依赖 pageW、pageW 依赖 gap（gap 是页宽的比例），先粗估再收敛
        var contentWBase = (0 until rows).maxOf { r ->
            val from = r * c
            val to = min((r + 1) * c, n)
            val k = (to - from).coerceAtLeast(1)
            (from until to).sumOf { items[it].uprightWidth } + 8 * (k - 1)
        }.coerceAtLeast(1).toFloat()
        repeat(3) {
            val pw = pageWidthFor(contentWBase)
            val g = max(8, (pw * GAP_RATIO).roundToInt())
            // 用真实 gap 重算最宽行
            contentWBase = (0 until rows).maxOf { r ->
                val from = r * c
                val to = min((r + 1) * c, n)
                val k = (to - from).coerceAtLeast(1)
                (from until to).sumOf { items[it].uprightWidth } + g * (k - 1)
            }.coerceAtLeast(1).toFloat()
        }

        // 先用原生尺寸估总像素，超预算求整体缩放比（迭代收敛：页眉页脚不随格宽缩放）
        var scale = 1f
        repeat(6) {
            val pw = pageWidthFor(contentWBase * scale)
            val rl = computeRows(scale)
            val ph = estimatePageHFromRows(pw, tpl, settings, rl)
            val total = pw.toDouble() * ph
            if (total <= maxPixels) return@repeat
            val next = scale * sqrt(maxPixels.toDouble() / total).toFloat() * 0.98f
            if (next >= scale) return@repeat
            scale = next.coerceAtLeast(0.05f)
        }

        val pageW = pageWidthFor(contentWBase * scale)
        val gap = max(8, (pageW * GAP_RATIO).roundToInt())
        val margin = (gap * MARGIN_GAP_MULT).roundToInt()
        val contentW = (pageW - 2 * margin).coerceAtLeast(1)
        val rowLayouts = computeRows(scale)

        // ---- 文字内容（先排版，页高依赖折行结果）----
        val infoRows = buildInfoRows(tpl, settings, items)
        val noteDef = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == "note" }
        val noteOn = noteDef != null && settings.enabledOf(noteDef)
        val noteLabel = if (noteOn) "${I18n.t("备注")}：" else ""
        val noteText = if (noteOn) settings.valueOf(noteDef!!) else ""
        val notePaint = bodyPaint(pageW * 0.0235f)
        val noteMaxW = (pageW - 2 * margin - notePaint.measureText(noteLabel)).coerceAtLeast(pageW * 0.2f)
        val noteLines = if (noteOn) wrapText(notePaint, noteText, noteMaxW) else emptyList()

        val signText = buildSignText(tpl, settings)

        // ---- 各段高度 ----
        val bandH = if (tpl.isRedHeader) (pageW * BAND_RATIO).roundToInt() else 0
        val infoLH = (pageW * 0.0245f * 2.4f).roundToInt()
        val noteLH = (pageW * 0.0235f * 1.75f).roundToInt()
        val signH = if (signText.isNotEmpty()) (pageW * 0.019f * 2.6f).roundToInt() else 0

        val headerH = if (!tpl.isRedHeader) 0 else {
            bandH + dividerH(tpl, pageW) + (pageW * 0.020f).roundToInt() +
                infoRows.size * infoLH + (pageW * 0.012f).roundToInt()
        }
        val footerH = noteLines.size * noteLH + signH

        val gridH = rowLayouts.sumOf { it.height } + (rows - 1) * gap
        val gridTop = margin + headerH
        val pageH = gridTop + gridH + gap * 2 + footerH + margin

        // ---- 逐格落矩形 ----
        val cells = ArrayList<Rect>(n)
        var y = gridTop
        rowLayouts.forEachIndexed { r, row ->
            var x = margin
            row.widths.forEachIndexed { ci, w ->
                val idx = r * c + ci
                if (idx < n) {
                    // 该行实际总宽可能略小于内容宽（因 min 到原生高 + 取整），居中补偿
                    cells.add(Rect(x, y, x + w, y + row.height))
                    x += w + gap
                }
            }
            y += row.height + gap
        }
        // 行宽不足时整行水平居中（避免右侧留大空白显得偏左）
        val shift = ArrayList<Rect>(cells.size)
        var i = 0
        rowLayouts.forEach { row ->
            val usedW = row.widths.sum() + gap * (row.widths.size - 1).coerceAtLeast(0)
            val off = ((contentW - usedW) / 2).coerceAtLeast(0)
            row.widths.forEach { w ->
                if (i < cells.size) {
                    val rr = cells[i]
                    shift.add(Rect(rr.left + off, rr.top, rr.right + off, rr.bottom))
                    i++
                }
            }
        }

        return Layout(
            pageW = pageW,
            pageH = pageH,
            scale = scale,
            clamped = scale < 1f,
            cols = c,
            rows = rows,
            gap = gap,
            margin = margin,
            bandH = bandH,
            headerH = headerH,
            footerH = footerH,
            gridTop = gridTop,
            cells = shift,
            title = if (tpl.isRedHeader) settings.titleOf(tpl) else "",
            bandDate = if (tpl == CollageTemplate.VISIT) bandDateOf(settings, items) else "",
            infoRows = infoRows,
            noteLabel = noteLabel,
            noteLines = noteLines,
            signText = signText,
        )
    }

    /** 一行里各张照片的显示宽度 + 该行高度 */
    private data class RowLayout(val widths: List<Int>, val height: Int)

    /** 估算页高（只为求缩放比，不需要精确文字折行——用字数估行数） */
    private fun estimatePageHFromRows(
        pageW: Int,
        tpl: CollageTemplate,
        settings: CollageSettings,
        rowLayouts: List<RowLayout>,
    ): Int {
        val gap = max(8, (pageW * GAP_RATIO).roundToInt())
        val margin = (gap * MARGIN_GAP_MULT).roundToInt()
        val bandH = if (tpl.isRedHeader) (pageW * BAND_RATIO).roundToInt() else 0
        val infoLH = (pageW * 0.0245f * 2.4f).roundToInt()
        val noteLH = (pageW * 0.0235f * 1.75f).roundToInt()
        val infoCount = buildInfoRows(tpl, settings, emptyList()).size
        val noteDef = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == "note" }
        val noteOn = noteDef != null && settings.enabledOf(noteDef)
        // 备注按 28 字一行粗估（够用了：估算偏大只会让缩放略保守一点）
        val noteLineEst = if (noteOn) max(1, (settings.valueOf(noteDef!!).length + 27) / 28) else 0
        val headerH = if (!tpl.isRedHeader) 0 else {
            bandH + dividerH(tpl, pageW) + (pageW * 0.020f).roundToInt() +
                infoCount * infoLH + (pageW * 0.012f).roundToInt()
        }
        val signH = if (buildSignText(tpl, settings).isNotEmpty()) (pageW * 0.019f * 2.6f).roundToInt() else 0
        val gridH = rowLayouts.sumOf { it.height } +
            (rowLayouts.size - 1).coerceAtLeast(0) * gap
        return margin + headerH + gridH + gap * 2 + noteLineEst * noteLH + signH + margin
    }

    /** 分隔线总高（客户拜访=粗线+间隙+细线，工作汇报=单细线），绘制与算高共用同一组数值 */
    private fun dividerH(tpl: CollageTemplate, pageW: Int): Int = if (tpl == CollageTemplate.VISIT) {
        (pageW * 0.004f).roundToInt().coerceAtLeast(2) +
            (pageW * 0.0032f).roundToInt().coerceAtLeast(2) +
            (pageW * 0.0016f).roundToInt().coerceAtLeast(1)
    } else {
        (pageW * 0.0016f).roundToInt().coerceAtLeast(2)
    }

    /**
     * 页眉信息行。分类规则：
     * - `note` 走页脚正文；`signer`/`reporter` 走右下落款；其余进信息行
     * - 客户拜访的 `date` 显示在红头带右侧（mock 版式），因此不重复进信息行
     */
    private fun buildInfoRows(
        tpl: CollageTemplate,
        settings: CollageSettings,
        items: List<CollageItem>,
    ): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        for (def in CollageSettings.fieldsOf(tpl)) {
            if (def.key == "note" || def.key == "signer" || def.key == "reporter") continue
            if (def.key == "date" && tpl == CollageTemplate.VISIT) continue
            if (!settings.enabledOf(def)) continue
            val value = if (def.auto) autoValueOf(def, items) else settings.valueOf(def)
            rows.add(I18n.t(def.label) + "：" to value)
        }
        return rows
    }

    /** 自动字段取值：日期=首张拍摄日期、地点=首张定位地址（剥掉尾部经纬度括号） */
    private fun autoValueOf(def: CollageFieldDef, items: List<CollageItem>): String {
        val first = items.firstOrNull() ?: return ""
        return when (def.key) {
            "date" -> first.dynamic.time.take(10)
            // 位置（v0.6.21）：优先按该张冻结的水印样式拼装结构化地址（与成片水印同规则）；
            // 拼图没有自己的经纬度开关，恒不显示坐标，故 suppressCoords = true
            "place" -> assembledLocation(first.dynamic, first.watermark.style, suppressCoords = true)
                ?: stripLatLng(first.dynamic.location).takeIf { it.isNotBlank() } ?: ""
            else -> ""
        }
    }

    /** 红头带右侧的日期（客户拜访版式；字段关闭时留空） */
    private fun bandDateOf(settings: CollageSettings, items: List<CollageItem>): String {
        val def = CollageSettings.fieldsOf(CollageTemplate.VISIT).first { it.key == "date" }
        return if (settings.enabledOf(def)) autoValueOf(def, items) else ""
    }

    /** 右下落款：客户拜访=记录人签字、工作汇报=汇报人；字段关闭则不画 */
    private fun buildSignText(tpl: CollageTemplate, settings: CollageSettings): String {
        val key = when (tpl) {
            CollageTemplate.VISIT -> "signer"
            CollageTemplate.REPORT -> "reporter"
            else -> return ""
        }
        val def = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == key } ?: return ""
        if (!settings.enabledOf(def)) return ""
        val v = settings.valueOf(def)
        return if (v.isBlank()) "${I18n.t(def.label)}：____________" else "${I18n.t(def.label)}：$v"
    }

    // ---------- 绘制：页眉 ----------

    /** 画页眉。无模板什么都不画；旧版红头/新版各自版式。 */
    fun drawHeader(canvas: Canvas, L: Layout, tpl: CollageTemplate) {
        if (L.fixedGrid) {
            drawFixedHeader(canvas, L, tpl)
            return
        }
        if (!tpl.isRedHeader) return
        val m = L.margin.toFloat()
        val innerW = (L.pageW - 2 * L.margin).toFloat()
        val top = L.margin.toFloat()

        // 红头带
        canvas.drawRect(m, top, m + innerW, top + L.bandH, Paint().apply { color = RED })

        val titleSize = L.bandH * 0.46f
        val tp = titlePaint(titleSize)
        val fm = tp.fontMetrics
        val baseline = top + (L.bandH - (fm.descent - fm.ascent)) / 2f - fm.ascent

        if (tpl == CollageTemplate.VISIT) {
            // 左对齐大标题 + 右侧日期
            val pad = L.pageW * 0.026f
            canvas.drawText(L.title, m + pad, baseline, tp)
            if (L.bandDate.isNotEmpty()) {
                val dp = bodyPaint(L.pageW * 0.019f, BAND_DATE)
                canvas.drawText(
                    L.bandDate,
                    m + innerW - pad - dp.measureText(L.bandDate),
                    top + L.bandH - L.bandH * 0.20f,
                    dp,
                )
            }
        } else {
            // 居中大标题 + 字距拉开（公文式）
            val spacing = L.bandH * 0.16f
            val widths = L.title.map { tp.measureText(it.toString()) }
            val total = widths.sum() + spacing * (L.title.length - 1).coerceAtLeast(0)
            var x = m + (innerW - total) / 2f
            L.title.forEachIndexed { i, ch ->
                canvas.drawText(ch.toString(), x, baseline, tp)
                x += widths[i] + spacing
            }
        }

        var y = top + L.bandH

        // 分隔线：客户拜访=粗+细双线，工作汇报=单细线
        val redPaint = Paint().apply { color = RED }
        if (tpl == CollageTemplate.VISIT) {
            val thick = (L.pageW * 0.004f).roundToInt().coerceAtLeast(2)
            val gapLine = (L.pageW * 0.0032f).roundToInt().coerceAtLeast(2)
            val thin = (L.pageW * 0.0016f).roundToInt().coerceAtLeast(1)
            canvas.drawRect(m, y, m + innerW, y + thick, redPaint)
            y += thick + gapLine
            canvas.drawRect(m, y, m + innerW, y + thin, redPaint)
            y += thin
        } else {
            val thin = (L.pageW * 0.0016f).roundToInt().coerceAtLeast(2)
            canvas.drawRect(m, y, m + innerW, y + thin, redPaint)
            y += thin
        }
        y += L.pageW * 0.020f

        // 信息行：标签实字 + 右侧下划线（下划线到页面右边界，mock 同款）
        val infoSize = L.pageW * 0.0245f
        val ip = bodyPaint(infoSize)
        val labelPaint = bodyPaint(infoSize)
        val linePaint = Paint().apply {
            color = LINE
            strokeWidth = max(1f, L.pageW * 0.0016f)
        }
        val infoLH = L.pageW * 0.0245f * 2.4f
        // 文字在行内垂直居中：baseline = 行顶 + (行高 - 字高)/2 - ascent
        val textH = ip.fontMetrics.descent - ip.fontMetrics.ascent
        for ((label, value) in L.infoRows) {
            val textTop = y + (infoLH - textH) / 2f
            val baselineY = textTop - ip.fontMetrics.ascent
            canvas.drawText(label, m, baselineY, labelPaint)
            val lx = m + labelPaint.measureText(label) + L.pageW * 0.008f
            if (value.isNotBlank()) {
                canvas.drawText(value, lx + L.pageW * 0.004f, baselineY, ip)
            }
            // 下划线落在行底略上方（文字的 descent 之后）
            val ly = textTop + textH + infoSize * 0.12f
            canvas.drawLine(lx, ly, m + innerW, ly, linePaint)
            y += infoLH
        }
    }

    /**
     * 新版页眉（三种版式）：
     * - 蓝色工作：居中粗黑体标题 + 蓝色细线 + 粗线（双线）
     * - 红色客户：居中宋体标题 + 日期 + 单红细线
     * - 红色工作：公文装饰边框（外扩角 + 双侧边线 + 底部粗细双线）+ 疏排宋体标题
     */
    private fun drawFixedHeader(canvas: Canvas, L: Layout, tpl: CollageTemplate) {
        val w = L.pageW.toFloat()
        val m = L.margin.toFloat()
        val innerW = L.pageW - 2 * L.margin.toFloat()

        when (tpl) {
            CollageTemplate.BLUE_WORK -> {
                val titleInk = w * BLUE_TITLE_INK_R
                val tp = titlePaint(titleInk / INK_TO_TEXT_SIZE, BLUE)
                val bandTop = w * BLUE_TITLE_TOP_R
                // 墨顶对齐：baseline = 墨顶 + 墨高
                val baseline = bandTop + titleInk
                val tw = tp.measureText(L.title)
                canvas.drawText(L.title, (L.pageW - tw) / 2f, baseline, tp)

                val bluePaint = Paint().apply { color = BLUE }
                var y = bandTop + titleInk + w * BLUE_TITLE_TO_RULE_R
                val rule1 = w * BLUE_RULE1_R
                canvas.drawRect(m, y, m + innerW, y + rule1, bluePaint)
                y += rule1 + w * BLUE_RULE_GAP_R
                val rule2 = w * BLUE_RULE2_R
                canvas.drawRect(m, y, m + innerW, y + rule2, bluePaint)
            }

            CollageTemplate.RED_CLIENT -> {
                val titleInk = w * RC_TITLE_INK_R
                val tp = serifTitlePaint(titleInk / INK_TO_TEXT_SIZE, CRIMSON)
                val bandTop = w * RC_TITLE_TOP_R
                val baseline = bandTop + titleInk
                val tw = tp.measureText(L.title)
                canvas.drawText(L.title, (L.pageW - tw) / 2f, baseline, tp)

                var y = bandTop + titleInk + w * RC_TITLE_TO_DATE_R
                if (L.bandDate.isNotEmpty()) {
                    val dateInk = w * RC_DATE_INK_R
                    val dp = bodyPaint(dateInk / INK_TO_TEXT_SIZE, DATE_INK)
                    val dw = dp.measureText(L.bandDate)
                    canvas.drawText(L.bandDate, (L.pageW - dw) / 2f, y + dateInk, dp)
                    y += dateInk
                }
                y += w * RC_DATE_TO_RULE_R
                val rule = w * RED_RULE_R
                canvas.drawRect(m, y, m + innerW, y + rule, Paint().apply { color = CRIMSON })
            }

            CollageTemplate.RED_WORK -> {
                val frameTop = w * RW_FRAME_TOP_R
                val ruleH = w * RW_FRAME_RULE_R
                val shoulderDy = w * RW_SHOULDER_P3_DY_R
                val sideW = w * RW_FRAME_SIDE_R
                val titleInk = w * RW_TITLE_INK_R
                val thick = w * RW_BOT_THICK_R
                val botGap = w * RW_BOT_GAP_R
                val thin = w * RW_BOT_THIN_R

                // 标题基线（先算，边框底要接在标题之后）
                val titleTop = frameTop + ruleH + shoulderDy + w * RW_FRAME_TO_TITLE_R
                val titleBaseline = titleTop + titleInk
                // 可选日期行（默认关；开启后占位，参考图无此行）
                val hasDate = L.bandDate.isNotEmpty()
                val dateInk = w * RW_DATE_INK_R
                val dateBlock = if (hasDate) w * RW_DATE_GAP_R + dateInk else 0f
                // 边框底（粗线顶）
                val botThickTop = titleTop + titleInk + dateBlock + w * RW_TITLE_TO_BOT_R
                val frameBottom = botThickTop + thick + botGap + thin

                val redPaint = Paint().apply {
                    color = CRIMSON
                    style = Paint.Style.STROKE
                    strokeWidth = ruleH
                    isAntiAlias = true
                    strokeCap = Paint.Cap.BUTT
                    strokeJoin = Paint.Join.MITER
                }
                // 左右两条肩部曲线：三次贝塞尔（对参考图轮廓最小二乘拟合，残差 1.9px）。
                // 参考图的肩部是内凹曲线，直线斜切与圆弧都对不上形状
                val leftSide = m + sideW / 2f
                val rightSide = m + innerW - sideW / 2f
                // 曲线原点 = 左边线/顶线交点（右上角镜像用右边界）
                val ox = leftSide
                val oy = frameTop + ruleH / 2f
                val p0x = ox + w * RW_SHOULDER_P0_DX_R
                val p0y = oy + w * RW_SHOULDER_P0_DY_R
                val p1x = ox + w * RW_SHOULDER_P1_DX_R
                val p1y = oy + w * RW_SHOULDER_P1_DY_R
                val p2x = ox + w * RW_SHOULDER_P2_DX_R
                val p2y = oy + w * RW_SHOULDER_P2_DY_R
                val p3x = ox + w * RW_SHOULDER_P3_DX_R
                val p3y = oy + w * RW_SHOULDER_P3_DY_R
                val shoulderBot = p3y

                val path = Path()
                // 顶线：两端内收到肩部起点
                path.moveTo(p0x, oy)
                path.lineTo(2 * (m + innerW / 2f) - p0x, oy)

                // 左肩：起点(p0) → 控制点(p1,p2) → 左边线顶点(p3)
                path.moveTo(p0x, p0y)
                path.cubicTo(p1x, p1y, p2x, p2y, p3x, p3y)

                // 右肩（以页面中轴对称镜像）
                val mirror = { v: Float -> 2 * (m + innerW / 2f) - v }
                path.moveTo(mirror(p0x), p0y)
                path.cubicTo(mirror(p1x), p1y, mirror(p2x), p2y, mirror(p3x), p3y)

                // 双侧竖线（肩部曲线落点 → 边框底）
                path.moveTo(p3x, p3y)
                path.lineTo(p3x, frameBottom)
                path.moveTo(mirror(p3x), p3y)
                path.lineTo(mirror(p3x), frameBottom)
                canvas.drawPath(path, redPaint)

                // 底部粗线 + 细线
                val solid = Paint().apply { color = CRIMSON }
                canvas.drawRect(m, botThickTop, m + innerW, botThickTop + thick, solid)
                canvas.drawRect(m, botThickTop + thick + botGap, m + innerW, frameBottom, solid)

                // 标题（宋体 + 疏排）
                val tp = serifTitlePaint(titleInk / INK_TO_TEXT_SIZE, CRIMSON)
                val tracking = w * RW_TITLE_TRACKING_R
                val widths = L.title.map { tp.measureText(it.toString()) }
                val total = widths.sum() + tracking * (L.title.length - 1).coerceAtLeast(0)
                var x = (L.pageW - total) / 2f
                L.title.forEachIndexed { i, ch ->
                    canvas.drawText(ch.toString(), x, titleBaseline, tp)
                    x += widths[i] + tracking
                }

                // 可选日期（居中，画在标题下方）
                if (hasDate) {
                    val dp = bodyPaint(dateInk / INK_TO_TEXT_SIZE, DATE_INK)
                    val dw = dp.measureText(L.bandDate)
                    canvas.drawText(
                        L.bandDate,
                        (L.pageW - dw) / 2f,
                        titleTop + titleInk + w * RW_DATE_GAP_R + dateInk,
                        dp,
                    )
                }
            }

            else -> Unit
        }
    }

    // ---------- 绘制：页脚 ----------

    /** 画页脚（备注折行 + 旧版右下落款） */
    fun drawFooter(canvas: Canvas, L: Layout, tpl: CollageTemplate) {
        val m = L.margin.toFloat()
        val innerW = (L.pageW - 2 * L.margin).toFloat()
        var y = (L.gridTop + L.gridH + L.gap * 2).toFloat()

        val noteSize = L.pageW * 0.0235f
        val noteLH = noteSize * 1.75f
        if (L.noteLabel.isNotEmpty() || L.noteLines.isNotEmpty()) {
            val np = bodyPaint(noteSize)
            val baselineShift = noteSize * 0.85f
            if (L.noteLabel.isNotEmpty()) canvas.drawText(L.noteLabel, m, y + baselineShift, np)
            val textX = m + np.measureText(L.noteLabel)
            L.noteLines.forEachIndexed { i, line ->
                canvas.drawText(line, textX, y + i * noteLH + baselineShift, np)
            }
            y += L.noteLines.size * noteLH
        }

        // 落款：右下红小字（仅旧版红头模板）
        if (L.signText.isNotEmpty() && tpl.isRedHeader) {
            val sp = bodyPaint(L.pageW * 0.019f, RED)
            val sy = L.pageH - L.margin - sp.fontMetrics.descent
            canvas.drawText(L.signText, m + innerW - sp.measureText(L.signText), sy, sp)
        }
    }

    /** 按字符折行（CJK 任意位置可断；单字超宽也强制放行避免死循环） */
    fun wrapText(paint: Paint, text: String, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                out.add(cur.toString())
                cur.setLength(0)
                continue
            }
            if (cur.isNotEmpty() && paint.measureText(cur.toString() + ch) > maxWidth) {
                out.add(cur.toString())
                cur.setLength(0)
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
