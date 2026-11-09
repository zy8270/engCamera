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
        return runCatching { format(target, parsed) }.getOrDefault(raw)
    }

    /** 星期文案：与打卡模板星期行同源（中文"星期五" / 英文"Friday"） */
    private fun weekdayText(date: Date): String {
        val idx = Calendar.getInstance(Locale.CHINA).apply { time = date }.get(Calendar.DAY_OF_WEEK)
        return I18n.t(FULL_WEEKDAYS[idx])
    }

    private val FULL_WEEKDAYS = listOf(
        "", "星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六",
    )

    private fun parse(raw: String): Date? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        for (p in SOURCE_PATTERNS) {
            val d = runCatching {
                val fmt = SimpleDateFormat(p, Locale.CHINA).apply { isLenient = false }
                fmt.parse(text)
            }.getOrNull() ?: continue
            return d
        }
        return null
    }
}
