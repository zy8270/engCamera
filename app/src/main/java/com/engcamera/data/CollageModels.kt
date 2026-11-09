package com.engcamera.data

import kotlinx.serialization.Serializable

/**
 * 拼图模板（v0.6.19，用户需求 2026-11-09）。
 * 三款内置：无模板 / 客户拜访 / 工作汇报——后两款为红头文件风格。
 * 排列顺序固定为 无模板 → 客户拜访 → 工作汇报（用户拍板）。
 */
enum class CollageTemplate(val id: String, val displayName: String) {
    NONE("none", "无模板"),
    VISIT("visit", "客户拜访"),
    REPORT("report", "工作汇报"),
    ;

    /** 红头文件风格（有红头条、分隔线、信息行、落款） */
    val isRedHeader: Boolean get() = this != NONE

    companion object {
        fun fromId(id: String?): CollageTemplate =
            entries.firstOrNull { it.id == id } ?: NONE

        /** 模板选择器展示顺序（用户拍板：无模板 → 客户拜访 → 工作汇报） */
        val ordered: List<CollageTemplate> get() = listOf(NONE, VISIT, REPORT)
    }
}

/**
 * 拼图模板的一个可配置字段。
 *
 * @param key 持久化键（跨模板唯一，勿改——改了存量设置会丢）
 * @param label 显示标签（走 I18n）
 * @param defaultEnabled 默认开关（用户拍板 2026-11-09：日期/地点默认开，其余默认关）
 * @param auto 自动取值：true=拍摄时自动填（日期=拍照日期、地点=定位地址），不显示输入框
 * @param defaultValue 默认文本（auto 字段为空）
 */
@Serializable
data class CollageFieldDef(
    val key: String,
    val label: String,
    val defaultEnabled: Boolean = false,
    val auto: Boolean = false,
    val defaultValue: String = "",
)

/**
 * 拼图设置（DataStore 持久化，下次进拼图模式沿用——用户需求）。
 *
 * @param columns 拼图列数 1~4（默认 1）
 * @param templateId 当前模板 id（默认无模板）
 * @param titles 各模板的大标题（key=模板 id），未设置时回落模板默认标题
 * @param fieldValues 各字段的手填值（key=字段 key）
 * @param fieldEnabled 各字段的开关覆盖（key=字段 key）；缺省=用模板默认
 */
@Serializable
data class CollageSettings(
    val columns: Int = 1,
    val templateId: String = CollageTemplate.NONE.id,
    val titles: Map<String, String> = emptyMap(),
    val fieldValues: Map<String, String> = emptyMap(),
    val fieldEnabled: Map<String, Boolean> = emptyMap(),
) {
    val template: CollageTemplate get() = CollageTemplate.fromId(templateId)

    /** 当前模板的大标题（未设置则回落模板默认） */
    fun titleOf(tpl: CollageTemplate = template): String =
        titles[tpl.id]?.takeIf { it.isNotBlank() } ?: defaultTitleOf(tpl)

    /** 字段是否启用（用户覆盖优先，其次模板默认） */
    fun enabledOf(def: CollageFieldDef): Boolean = fieldEnabled[def.key] ?: def.defaultEnabled

    /** 字段的手填值 */
    fun valueOf(def: CollageFieldDef): String = fieldValues[def.key] ?: def.defaultValue

    companion object {
        fun defaultTitleOf(tpl: CollageTemplate): String = when (tpl) {
            CollageTemplate.NONE -> ""
            CollageTemplate.VISIT -> "客户拜访记录"
            CollageTemplate.REPORT -> "工作汇报"
        }

        /**
         * 各模板的字段表（用户拍板 2026-11-09）：
         * 客户拜访 = 拜访日期〔开·自动〕/拜访地点〔开·自动〕/客户单位/拜访人/记录人签字/备注
         * 工作汇报 = 汇报日期〔开·自动〕/汇报单位/汇报人/备注
         * 无模板   = 备注
         * 除日期与地点外全部默认关闭、手填。
         */
        fun fieldsOf(tpl: CollageTemplate): List<CollageFieldDef> = when (tpl) {
            CollageTemplate.NONE -> listOf(
                CollageFieldDef("note", "备注"),
            )

            CollageTemplate.VISIT -> listOf(
                CollageFieldDef("date", "拜访日期", defaultEnabled = true, auto = true),
                CollageFieldDef("place", "拜访地点", defaultEnabled = true, auto = true),
                CollageFieldDef("client", "客户单位"),
                CollageFieldDef("visitor", "拜访人"),
                CollageFieldDef("signer", "记录人签字"),
                CollageFieldDef("note", "备注"),
            )

            CollageTemplate.REPORT -> listOf(
                CollageFieldDef("date", "汇报日期", defaultEnabled = true, auto = true),
                CollageFieldDef("company", "汇报单位"),
                CollageFieldDef("reporter", "汇报人"),
                CollageFieldDef("note", "备注"),
            )
        }
    }
}

/**
 * 一张已连拍的照片（只在内存里，不持久化——进程重启即丢弃）。
 *
 * 冻结拍摄瞬间的水印设置与动态值（用户拍板 2026-11-09「B：每张拍摄瞬间全冻结」）：
 * 中途改水印内容/模板只影响之后拍的照片；预览弹窗提供「同步当前水印」手动统一。
 *
 * @param file 已转正前的原始 JPEG（cacheDir/collage/ 下）
 * @param watermark 该张的快门瞬间水印状态（含模板/样式/字段值）
 * @param dynamic 该张的快门瞬间动态值（时间/位置/天气）
 * @param frameRotation 屏幕系→成片系换算角
 * @param rotationDeg 水印块旋转角（=快门瞬间的量化重力角）
 * @param uprightWidth/uprightHeight 按 EXIF 转正后的像素尺寸（布局用，不解码像素）
 */
data class CollageItem(
    val id: Long,
    val file: java.io.File,
    val watermark: WatermarkState,
    val dynamic: DynamicValues,
    val frameRotation: Int,
    val rotationDeg: Float,
    val uprightWidth: Int,
    val uprightHeight: Int,
) {
    /** 宽高比（宽/高），已按 EXIF 转正 */
    val aspect: Float
        get() = if (uprightHeight > 0) uprightWidth.toFloat() / uprightHeight else 1f
}
