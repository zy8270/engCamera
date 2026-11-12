package com.engcamera.watermark

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
 * 拼图渲染器（v0.6.19，用户需求 2026-11-09）：版式计算 + 整页装饰绘制。
 *
 * 职责边界：本类只管**页面骨架**（红头带 / 分隔线 / 信息行 / 白缝网格位置 / 备注 / 落款），
 * 照片的解码、逐张烧水印、贴图由调用方（CameraViewModel.saveCollage）负责——因为那部分
 * 要按每张照片冻结的 WatermarkState 调 WatermarkRenderer.drawOnCanvas，逐张回收位图控内存。
 *
 * 版式数值全部按**页面宽度比例**表达，因此超内存预算整体等比缩放时文字自动跟随，
 * 不需要另一套字号表。基准 mock 见 imgcache/_analysis/collage_out/。
 */
object CollageRenderer {

    /** 白缝宽度 = 页宽 × 此比例（最小 8px，与 mock 一致） */
    private const val GAP_RATIO = 0.016f

    /** 页面外边距 = 白缝 × 此倍数 */
    private const val MARGIN_GAP_MULT = 1.7f

    /** 红头带高度 = 页宽 × 此比例 */
    private const val BAND_RATIO = 0.082f

    // ---- 配色（与 mock 逐项对齐）----
    const val RED = 0xFFC0272D.toInt()
    const val INK = 0xFF1C1C1E.toInt()
    const val LINE = 0xFFAAACB2.toInt()
    private const val BAND_DATE = 0xFFFFE4E4.toInt()
    private const val WHITE = Color.WHITE

    /**
     * 一次算好的版式。所有尺寸都是像素，`scale` 为相对原生分辨率的缩放比
     * （1.0 = 完全原生，< 1 表示因内存预算整体等比缩小）。
     *
     * v0.6.19 起用「行内等高填满」（justified rows）布局：每行照片高度相同、
     * 宽度按各自宽高比分配，让这一行刚好填满内容宽——**零白边、零裁切、不放大**。
     * 代价是列边界上下不对齐（Google 相册同款观感）。
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
    ) {
        /** 第 index 张照片的矩形 */
        fun cellRect(index: Int): Rect = cells.getOrElse(index) { Rect(0, 0, 1, 1) }

        /** 网格区总高（最底一张的下沿 - 网格顶部） */
        val gridH: Int get() = cells.maxOfOrNull { it.bottom }?.minus(gridTop) ?: 0
    }

    // ---------- 字体 ----------

    private val titleTypeface: Typeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }
    private val bodyTypeface: Typeface by lazy { Typeface.create("sans-serif", Typeface.NORMAL) }

    private fun titlePaint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = titleTypeface
        textSize = size
        color = WHITE
    }

    private fun bodyPaint(size: Float, color: Int = INK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = bodyTypeface
        textSize = size
        this.color = color
    }

    /**
     * 计算整页版式（行内等高填满，justified rows）。
     *
     * 算法：把照片按 `cols` 切成行。对每一行，求一个统一的行高 h 使该行所有照片
     * 按各自宽高比缩放后、宽度之和 + 缝刚好等于内容宽：
     *     h = (contentW - gap*(k-1)) / Σ(1/aspect_i)
     * 其中 k = 该行照片数。这样**零白边、零裁切、不放大**（h 只会 ≤ 各照片原生高，
     * 因为 Σ(1/aspect) ≥ k/aspect_max 恒成立……实际实现里对 h 取 min 与原生高，
     * 保证不会把小图放大到糊）。
     *
     * 页宽先按「目标照片宽度」定：取所有照片里最大的原生宽（1 列 1 张时即 1:1），
     * 再据页宽反推内容宽，逐行求 h。超内存预算时整体等比缩 scale。
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
         *
         * 这样同方向的照片（相机输出恒 3:4）行内严丝合缝、零白边；混方向时白边
         * 只出现在较矮那张的上下（面积最小），而不会像"等宽格子"那样让竖图两侧
         * 各留一大条——那才是用户抱怨的"无意义白边"。
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
            CollageTemplate.NONE -> return ""
        }
        val def = CollageSettings.fieldsOf(tpl).firstOrNull { it.key == key } ?: return ""
        if (!settings.enabledOf(def)) return ""
        val v = settings.valueOf(def)
        return if (v.isBlank()) "${I18n.t(def.label)}：____________" else "${I18n.t(def.label)}：$v"
    }

    // ---------- 绘制 ----------

    /** 画页眉（红头带 + 分隔线 + 信息行）。无模板时什么都不画。 */
    fun drawHeader(canvas: Canvas, L: Layout, tpl: CollageTemplate) {
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
        // （旧写法 y + ascent + 0.55*infoSize 在 ascent 绝对值大时会算到行顶之上，
        //   实测把「拜访地点」压进了红头带——v0.6.19 截图时发现）
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

    /** 画页脚（备注折行 + 右下落款） */
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

        // 落款：右下红小字
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
