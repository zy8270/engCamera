package com.engcamera.util

import com.engcamera.i18n.I18n
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 时间格式（v0.6.18，用户需求 2026-11-09）：水印编辑器里时间字段可选显示格式。
 *
 * 只有「把时间当整串单行文本渲染」的模板才支持自定义格式。打卡/巡逻这几款把时间
 * 拆成「日期行 + 星期 + 大号时分」分别绘制（大数字时间是版式的一部分），改了格式
 * 也不会生效，编辑器里对它们隐藏选择器（见 [FIXED_LAYOUTS]）。
 * 马克·工程/马克·工程白虽然也带星期/点分日期，但都是单行文字，因此**支持**，
 * 并把这两种写法作为可选格式收录，模板自身默认用对应那条。
 *
 * 字段值为空（自动取当前时间）时按所选格式显示；手动填写的时间若能解析成时间，
 * 也按所选格式重排（解析不了就原样显示，不吞用户输入）。
 */
object TimeFormat {

    /** 默认格式（与改造前行为一致） */
    const val DEFAULT = "yyyy-MM-dd HH:mm:ss"

    /** 马克·工程默认格式：日期 + 星期 + 时分（与参考图一致） */
    const val WEEKDAY = "yyyy-MM-dd EEEE HH:mm"

    /** 马克·工程白默认格式：点分日期 + 时分（与参考图一致） */
    const val DOT_DATE = "yyyy.MM.dd HH:mm"

    /** 固定版式模板（时间被拆成多个部件分别绘制，自定义格式无效） */
    val FIXED_LAYOUTS = setOf(
        "TODAY_CLOCK",      // 今日·打卡
        "TODAY_CLOCK1",     // 今日·打卡1
        "MARK_CLOCK",       // 马克·打卡
        "PATROL",           // 巡逻
    )

    /** 模板默认格式（新套用该模板时用；未列出的模板用 [DEFAULT]） */
    fun defaultFor(layout: String): String = when (layout) {
        "MARK_CARD" -> WEEKDAY
        "MARK_CARD_LIGHT" -> DOT_DATE
        else -> DEFAULT
    }

    /**
     * 可选格式（选择器按此顺序展示，首项为默认）。
     * 含 EEEE 的走本地化星期（见 [apply]）；`yyyy.MM.dd` 为点分日期。
     */
    val PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        WEEKDAY,
        "yyyy-MM-dd",
        DOT_DATE,
        "HH:mm:ss",
        "HH:mm",
        "MM-dd HH:mm",
        "yyyy年MM月dd日 HH:mm:ss",
        "yyyy年MM月dd日",
    )

    /** 解析源格式：覆盖可选格式 + 历史数据里出现过的写法（点分/斜杠/不补零） */
    private val SOURCE_PATTERNS = (PATTERNS + listOf(
        "yyyy/M/d HH:mm:ss", "yyyy/M/d HH:mm", "yyyy/M/d H:mm", "yyyy/M/d",
        "yyyy-MM-dd H:mm:ss", "yyyy-MM-dd H:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss",
        "yyyy年M月d日 HH:mm:ss", "yyyy年M月d日 HH:mm", "yyyy年M月d日",
        "HH:mm:ss", "HH:mm", "H:mm",
        "yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyyMMdd",
    )).distinct()

    /** 模板是否支持自定义时间格式 */
    fun supported(layout: String): Boolean = layout !in FIXED_LAYOUTS

    /** 格式串里是否含星期令牌（EEEE/EEE）——编辑器预览与示例都走同一套渲染 */
    fun hasWeekday(pattern: String): Boolean = pattern.contains("EEE")

    /** 按格式串格式化一个时刻（含星期令牌时按界面语言出"星期五"/"Friday"） */
    fun format(pattern: String, date: Date): String {
        val target = pattern.ifBlank { DEFAULT }
        if (!hasWeekday(target)) {
            return SimpleDateFormat(target, Locale.CHINA).format(date)
        }
        // 星期令牌单独处理：SimpleDateFormat 的 EEEE 在中文/英文下的形态与项目
        // 既有文案（打卡模板星期行）不完全一致，统一走 I18n，水印文案随界面语言
        val parts = target.split("EEEE")
        val head = if (parts[0].isEmpty()) "" else SimpleDateFormat(parts[0], Locale.CHINA).format(date)
        val tail = if (parts.size > 1 && parts[1].isNotEmpty()) {
            SimpleDateFormat(parts[1], Locale.CHINA).format(date)
        } else {
            ""
        }
        return head + weekdayText(date) + tail
    }

    /** 把已渲染的时间文本按目标格式重排；解析失败原样返回（不丢用户输入） */
    fun apply(raw: String, pattern: String): String {
        if (raw.isBlank()) return raw
        val target = pattern.ifBlank { DEFAULT }
        val parsed = parse(raw) ?: return raw
        // 源文本只有时间（如「14:30」）而目标要日期时，SimpleDateFormat 会补成 1970-01-01——
        // 补今天的日期更符合直觉（用户切到「yyyy-MM-dd HH:mm」看到 1970 会以为坏了）
        val date = if (parsed.hasDate) {
            parsed.date
        } else {
            Calendar.getInstance(Locale.CHINA).apply {
                time = parsed.date
                val now = Calendar.getInstance(Locale.CHINA)
                set(Calendar.YEAR, now.get(Calendar.YEAR))
                set(Calendar.MONTH, now.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, now.get(Calendar.DAY_OF_MONTH))
            }.time
        }
        return runCatching { format(target, date) }.getOrDefault(raw)
    }

    /** 星期文案：与打卡模板星期行同源（中文"星期五" / 英文"Friday"） */
    private fun weekdayText(date: Date): String {
        val idx = Calendar.getInstance(Locale.CHINA).apply { time = date }.get(Calendar.DAY_OF_WEEK)
        return I18n.t(FULL_WEEKDAYS[idx])
    }

    private val FULL_WEEKDAYS = listOf(
        "", "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六",
    )

    private fun parse(raw: String): Parsed? {
        val text = stripWeekday(raw.trim())
        if (text.isEmpty()) return null
        for (p in SOURCE_PATTERNS) {
            val d = runCatching {
                val fmt = SimpleDateFormat(p, Locale.CHINA).apply { isLenient = false }
                fmt.parse(text)
            }.getOrNull() ?: continue
            // 模式里有没有日期令牌决定解析结果是否可信（纯时间模式解析出的日期恒 1970-01-01）
            return Parsed(d, hasDateToken(p))
        }
        return null
    }

    private class Parsed(val date: Date, val hasDate: Boolean)

    /** 格式串是否含日期令牌（y/M/d）；HH:mm 这类纯时间格式返回 false */
    private fun hasDateToken(pattern: String): Boolean =
        pattern.any { it == 'y' || it == 'M' || it == 'd' }

    /**
     * 去掉文本里的星期片段（用户需求 2026-11-18）：带 EEEE 的格式（如马克·工程默认
     * `yyyy-MM-dd EEEE HH:mm`）产出的文字里夹着「星期二/Friday」，而解析模式表里没有
     * 星期令牌——不剥掉就永远解析失败，切换格式时输入框内容看着"没变"。
     * 中英文星期都按界面语言的实际译文匹配（[weekdayText] 同源）。
     */
    private fun stripWeekday(text: String): String {
        var out = text
        FULL_WEEKDAYS.forEach { zh ->
            val en = EN_DICT_WEEKDAYS[zh] ?: return@forEach
            // 中文形如「星期二」；英文是「Tuesday」，直接替换后压缩多余空格
            out = out.replace(zh, " ").replace(en, " ")
        }
        return out.replace(Regex("\\s+"), " ").trim()
    }

    /** 英文星期文案（与 I18n.kt 的 EN_DICT 同源；两种语言的写法都要能剥掉，
     *  因为字段值可能是切换界面语言前存下来的） */
    private val EN_DICT_WEEKDAYS: Map<String, String> = mapOf(
        "星期日" to "Sunday",
        "星期一" to "Monday",
        "星期二" to "Tuesday",
        "星期三" to "Wednesday",
        "星期四" to "Thursday",
        "星期五" to "Friday",
        "星期六" to "Saturday",
    )
}
