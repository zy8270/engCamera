package com.engcamera.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.engcamera.i18n.I18n
import kotlin.math.roundToInt

/**
 * 防伪码（v0.6.16，用户需求 2026-11-04）：
 * 从**未经水印/批注**的成片画面算出 14 位「大写字母+数字」码，烧录到图片右下角，
 * 配合项目根目录 verify_anticounterfeit.py 校验照片是否被改过（报偏差百分比）。
 *
 * 算法（Salt-Fingerprint-14，脚本侧逐行对齐，改动必须两边同步）：
 *  1. 采样区 = 画面上部 10/16（62.5%）：水印锚点默认在画面下方，防伪条也在下方，
 *     这一条规则同时把它们排除，不需要任何掩膜。
 *  2. 采样区切成 14×10 单元网格（单元 = W/14 × H/16），每单元取灰度面积平均。
 *     直接用全分辨率 getPixels 逐单元求和：一次性大比例 createScaledBitmap 是 2×2
 *     双线性采样（混叠），与脚本的精确面积平均对不上。
 *  3. 14 个区域 = 7 列 × 2 行（每区域 2×5 = 10 单元），每区域取 20% 截尾均值
 *     （去最高 2、最低 2）——对局部遮挡/噪点稳健。
 *  4. d[j] = v[j] - 14 区域均值：抵消整体亮度漂移（平台提亮/美颜/自动曝光）。
 *  5. level[j] = clamp(round(16 + d[j] * 16 / 64), 0, 31)，5 位（每级 ≈ 4 灰度）留容差。
 *  6. 码位 = (level[j] + salt[j]) & 31，salt[j] = ord(SALT[j]) & 31；
 *     字母表 Crockford Base32（剔除易混的 I/L/O/U）。
 *
 * 能抗什么：JPEG 重压缩、平台降噪、等比缩放、整体调色（残留偏差即脚本报出的百分比）。
 * 不能抗什么：裁剪/旋转/透视变形（网格错位）；画面下部 37.5% 的改动（采样区之外，
 * 脚本的 --original 模式可覆盖）；已知算法者可重算伪造——14 位装不下密码学签名，
 * 这是内容指纹而非防伪签名，边界写进脚本 docstring。
 */
object AntiCounterfeit {

    /** 盐（用户指定）：14 位，同时用作预览占位符 */
    const val SALT = "EngCameraRnzhy"

    /** 预览占位码（真实成片用画面内容算出来的码；正好 14 位） */
    const val PLACEHOLDER = SALT

    /** 码长 */
    const val LENGTH = 14

    /** Crockford Base32：纯大写字母+数字，无 I/L/O/U */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 网格：列数 / 总行数 / 实际采样行数（上部 10 行 = 62.5%） */
    private const val COLS = 14
    private const val ROWS = 16
    private const val SAMPLE_ROWS = 10

    /** 区域：7 列 × 2 行，每区域 2 单元宽 × 5 单元高 */
    private const val REGION_COLS = 7
    private const val REGION_ROWS = 2
    private const val CELLS_PER_REGION = 10
    private const val TRIM = 2 // 截尾均值去掉两端各 2 个

    /** 量化：中心 16、满量程 64 灰度 → ±16 级 */
    private const val CENTER = 16f
    private const val SCALE = 64f

    /** 开发期逐值比对开关（与脚本 --verbose 对齐用；发版保持 false） */
    private const val DEBUG_VALUES = false

    /** 按画面内容算 14 位防伪码（src 必须是未经水印/批注的位图） */
    fun compute(src: Bitmap): String {
        val levels = levelsOf(src) ?: return PLACEHOLDER
        return encode(levels)
    }

    /** 14 个区域的量化等级（0..31）；位图不可用时返回 null */
    fun levelsOf(src: Bitmap): IntArray? {
        if (src.isRecycled || src.width < COLS || src.height < ROWS) return null
        val w = src.width
        val h = src.height
        // 单元灰度：只采上部 SAMPLE_ROWS 行（下部留给水印/防伪条）
        val cellGray = FloatArray(COLS * SAMPLE_ROWS)
        val px = IntArray(0)
        var buffer = px
        for (r in 0 until SAMPLE_ROWS) {
            val y0 = (r * h) / ROWS
            val y1 = ((r + 1) * h) / ROWS
            val cellH = (y1 - y0).coerceAtLeast(1)
            for (c in 0 until COLS) {
                val x0 = (c * w) / COLS
                val x1 = ((c + 1) * w) / COLS
                val cellW = (x1 - x0).coerceAtLeast(1)
                val need = cellW * cellH
                if (buffer.size < need) buffer = IntArray(need)
                src.getPixels(buffer, 0, cellW, x0, y0, cellW, cellH)
                var sum = 0L
                for (i in 0 until need) {
                    val p = buffer[i]
                    // ITU-R BT.601 权重，逐像素取整后再累加：与脚本侧 PIL convert("L") 的
                    // 整数公式 (299R+587G+114B+500)/1000 完全等价（累加未取整值会差到 ±1 级）
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    sum += (299 * r + 587 * g + 114 * b + 500) / 1000
                }
                cellGray[r * COLS + c] = sum.toFloat() / need
            }
        }
        // 14 个区域：7 列 × 2 行，每区域 2 单元宽 × 5 单元高 → 20% 截尾均值
        val region = FloatArray(REGION_COLS * REGION_ROWS)
        val cells = FloatArray(CELLS_PER_REGION)
        for (rr in 0 until REGION_ROWS) {
            for (rc in 0 until REGION_COLS) {
                var n = 0
                for (dr in 0 until 5) {
                    for (dc in 0 until 2) {
                        cells[n++] = cellGray[(rr * 5 + dr) * COLS + (rc * 2 + dc)]
                    }
                }
                cells.sort()
                var s = 0f
                for (i in TRIM until CELLS_PER_REGION - TRIM) s += cells[i]
                region[rr * REGION_COLS + rc] = s / (CELLS_PER_REGION - TRIM * 2)
            }
        }
        // 减全局均值 + 量化到 5 位。取整规则必须与脚本一致：floor(x + 0.5)，
        // 不能用 toInt()（向零截断，负值会比 round 少 1 级——实测两边码差 8 位）
        var mean = 0f
        for (v in region) mean += v
        mean /= region.size
        val levels = IntArray(LENGTH)
        for (j in 0 until LENGTH) {
            val x = CENTER + (region[j] - mean) * CENTER / SCALE
            levels[j] = kotlin.math.floor(x + 0.5f).toInt().coerceIn(0, 31)
        }
        if (DEBUG_VALUES) {
            android.util.Log.w(
                "AntiFake",
                "region=" + region.joinToString(",") { "%.1f".format(it) } +
                    " levels=" + levels.joinToString(","),
            )
        }
        return levels
    }

    /** 等级 → 14 位码（加盐混淆映射） */
    fun encode(levels: IntArray): String {
        val sb = StringBuilder(LENGTH)
        for (j in 0 until LENGTH) {
            val idx = (levels[j] + saltAt(j)) and 31
            sb.append(ALPHABET[idx])
        }
        return sb.toString()
    }

    /** 码 → 等级（脚本侧同款反解；非法字符返回 null） */
    fun decodeLevels(code: String): IntArray? {
        val t = code.trim().uppercase()
        if (t.length != LENGTH) return null
        val levels = IntArray(LENGTH)
        for (j in 0 until LENGTH) {
            val idx = ALPHABET.indexOf(t[j])
            if (idx < 0) return null
            levels[j] = (idx - saltAt(j)) and 31
        }
        return levels
    }

    private fun saltAt(j: Int): Int = SALT[j].code and 31

    /** 校验输入：只留字母数字、转大写、截到 14 位 */
    fun sanitizeInput(raw: String): String =
        raw.uppercase().filter { it in '0'..'9' || it in 'A'..'Z' }.take(LENGTH)

    /** 条内文字：`I18n.t("防伪") + " " + code`（预览与烧录共用，保证文字完全一致） */
    fun stripText(code: String): String = I18n.t("防伪") + " " + code

    /** 字号 = 画面宽 1.9%（预览/烧录共用，避免两套字号基准） */
    fun stripTextSize(canvasW: Float): Float = canvasW * 0.019f

    /** 右留白 / 底留白（相对画面宽、高），预览与烧录共用 */
    fun stripRightInset(canvasW: Float): Float = canvasW * 0.012f
    fun stripBottomInset(canvasH: Float): Float = canvasH * 0.010f

    /** 创建条文字画笔（白字 + 轻阴影，与参考图 imgcache/马克-打卡.jpg 一致） */
    fun stripPaint(canvasW: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = stripTextSize(canvasW)
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(canvasW * 0.004f, canvasW * 0.001f, canvasW * 0.001f, 0x99000000.toInt())
    }

    /**
     * 画到成片右下角（图片坐标系）。
     * gravityRollDeg=0 即烧录路径（成片显示系=重力系）；非 0 供预览把「重力右下角」
     * 换算到屏幕系——同一函数、同一画笔、同一留白，保证所见即所得。
     */
    fun drawStrip(canvas: Canvas, canvasW: Int, canvasH: Int, code: String, gravityRollDeg: Float = 0f) {
        if (code.isBlank()) return
        drawStripRotated(canvas, canvasW, canvasH, code, gravityRollDeg)
    }

    /**
     * 按重力角把条文字旋转后贴到「重力方向的右下角」。
     *
     * 换算方式与 WatermarkRenderer.drawOnCanvas 完全同构：把画面中心视为原点，
     * 目标角点先在画面坐标系里求出（右下角），再按 roll 反向旋回屏幕系。
     * 文字本身也要跟着转 roll（重力系里的「正着写」在屏幕上就是斜的/倒的）。
     */
    fun drawStripRotated(canvas: Canvas, canvasW: Int, canvasH: Int, code: String, rollDeg: Float) {
        if (code.isBlank()) return
        val w = canvasW.coerceAtLeast(1).toFloat()
        val h = canvasH.coerceAtLeast(1).toFloat()
        val paint = stripPaint(w)
        val text = stripText(code)
        val norm = ((rollDeg.roundToInt() % 360) + 360) % 360
        // 文字右下角在「重力系」里的落点（画面坐标系）
        val right = w - stripRightInset(w)
        val bottom = h - stripBottomInset(h)
        if (norm == 0) {
            canvas.drawText(text, right - paint.measureText(text), bottom, paint)
            return
        }
        // 重力系角点 → 屏幕系（与 rotateAnchor 同一套矩阵）；然后绕该角点转 +roll，
        // 让文字在重力系里保持正着写（屏幕上看起来是跟着持机方向转的）
        val rad = Math.toRadians(norm.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val vx = right / w - 0.5f
        val vy = bottom / h - 0.5f
        val sx = (0.5f + (vx * cos - vy * sin)) * w
        val sy = (0.5f + (vx * sin + vy * cos)) * h
        // 绕角点转 +roll 后，「向左上写」的文本在屏幕里仍朝向重力系的右下方——
        // 画布转了，所以这里仍按未旋转的 (sx - 文本宽, sy) 起笔即可（rotate 已包含基线方向）
        canvas.save()
        canvas.rotate(norm.toFloat(), sx, sy)
        canvas.drawText(text, sx - paint.measureText(text), sy, paint)
        canvas.restore()
    }
}
