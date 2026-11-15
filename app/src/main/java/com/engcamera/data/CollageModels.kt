package com.engcamera.data

import kotlinx.serialization.Serializable

/** 拼图格内留白（照片比例与格子不符时）的填充方式 */
enum class CellFill {
    /** 不填充（旧版原尺寸并排布局，无固定格子） */
    NONE,

    /** 浅灰实色 #F0F0F0（今日系参考图） */
    GRAY,

    /** 照片自身放大模糊铺满（马克系参考图） */
    BLUR,
}

/**
 * 拼图模板。
 *
 * 两套版式家族（v0.6.26）：
 * - **旧版**（NONE / VISIT / REPORT）：原尺寸并排、零白边、红头公文页眉，无固定格子。
 * - **新版**（BLUE_WORK / RED_CLIENT / RED_WORK）：固定 3:4 格子 + 照片等比内嵌 +
 *   留白填充（灰底或模糊底）。参照 imgcache 下 马克-拼图-工作 / 今日-拼图-客户 /
 *   今日-拼图-工作 三张图：横竖混排时照片按宽度内嵌、上下留白，竖图正好填满格子。
 *
 * 排列顺序固定（用户拍板：旧三款在前，新三款在后）。
 */
enum class CollageTemplate(val id: String, val displayName: String) {
    NONE("none", "无模板"),
    VISIT("visit", "客户拜访"),
    REPORT("report", "工作汇报"),
    BLUE_WORK("blue_work", "马克-工作"),
    RED_CLIENT("red_client", "今日-客户"),
    RED_WORK("red_work", "今日-工作"),
    ;

    /** 红头文件风格（旧版两款：红头条、分隔线、信息行、落款） */
    val isRedHeader: Boolean get() = this == VISIT || this == REPORT

    /** 新版固定 3:4 等格布局（照片等比内嵌 + 留白填充） */
    val isFixedGrid: Boolean get() = this == BLUE_WORK || this == RED_CLIENT || this == RED_WORK

    /** 是否有页眉（标题/分隔线） */
    val hasHeader: Boolean get() = this != NONE

    /** 格子留白填充方式 */
    val fill: CellFill get() = when (this) {
        BLUE_WORK -> CellFill.BLUR
        RED_CLIENT, RED_WORK -> CellFill.GRAY
        else -> CellFill.NONE
    }

    companion object {
        fun fromId(id: String?): CollageTemplate =
            entries.firstOrNull { it.id == id } ?: NONE

        /** 模板选择器展示顺序（旧三款 → 新三款） */
        val ordered: List<CollageTemplate>
            get() = listOf(NONE, VISIT, REPORT, BLUE_WORK, RED_CLIENT, RED_WORK)
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
            CollageTemplate.BLUE_WORK -> "工作汇报"
            CollageTemplate.RED_CLIENT -> "客户拜访"
            CollageTemplate.RED_WORK -> "工作汇报"
        }

        /**
         * 各模板的字段表（用户拍板 2026-11-09；新三款 v0.6.26 按参考图精简）。
         * 客户拜访 = 拜访日期〔开·自动〕/拜访地点〔开·自动〕/客户单位/拜访人/记录人签字/备注
         * 工作汇报 = 汇报日期〔开·自动〕/汇报单位/汇报人/备注
         * 无模板   = 备注
         * 蓝色工作 = 大标题/备注（参考图只有标题，无日期无信息行）
         * 红色客户 = 大标题/日期〔开·自动〕/备注
         * 红色工作 = 大标题/日期〔关·自动〕/备注（参考图无日期，需要可打开）
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

            CollageTemplate.BLUE_WORK -> listOf(
                CollageFieldDef("note", "备注"),
            )

            CollageTemplate.RED_CLIENT -> listOf(
                CollageFieldDef("date", "拜访日期", defaultEnabled = true, auto = true),
                CollageFieldDef("note", "备注"),
            )

            CollageTemplate.RED_WORK -> listOf(
                CollageFieldDef("date", "汇报日期", defaultEnabled = false, auto = true),
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
 * @param fromGallery 来自相册选图（v0.6.32，用户需求 2026-11-15）：
 *   **不烧水印**——相册里是什么样，拼图里就是什么样。水印字段仅为占位，不参与渲染
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
    val fromGallery: Boolean = false,
) {
    /** 宽高比（宽/高），已按 EXIF 转正 */
    val aspect: Float
        get() = if (uprightHeight > 0) uprightWidth.toFloat() / uprightHeight else 1f
}
