package com.engcamera.data

import kotlinx.serialization.Serializable

/** 水印字段类型 */
@Serializable
enum class FieldType {
    PROJECT_NAME, NOTE, DATETIME, LOCATION, CUSTOM, COUNT, WEATHER,
}

/** 单个水印字段（对应计划书 6.1） */
@Serializable
data class WatermarkField(
    val id: String,
    val type: FieldType,
    val label: String,
    val value: String,
    val enabled: Boolean = true,
    val isTitle: Boolean = false,
)

/** 水印样式（对应计划书 6.2，颜色为 ARGB Long） */
@Serializable
data class WatermarkStyle(
    val titleBgColor: Long = 0xFF1D4E89,      // 不透明深蓝
    val titleTextColor: Long = 0xFFFFFFFF,    // 白色
    val bodyBgColor: Long = 0x99000000,       // 半透明黑（待确认 1 方案 A）
    val bodyTextColor: Long = 0xFFFFFFFF,     // 白色
    val titleFontSizeRatio: Float = 0.038f,   // 相对画面宽度
    val bodyFontSizeRatio: Float = 0.030f,    // 相对画面宽度
    val maxWidthRatio: Float = 0.576f,        // 水印块最大宽度比例（v0.2.7 收窄 2/3 后按用户反馈 ×1.2 加宽）
    val paddingRatio: Float = 0.014f,         // 内边距比例
    val lineSpacingRatio: Float = 0.006f,     // 行距比例
    val cornerRatio: Float = 0.006f,          // 圆角比例
)

/** 水印整体状态（对应计划书 6.3） */
@Serializable
data class WatermarkState(
    val fields: List<WatermarkField> = defaultFields(),
    val style: WatermarkStyle = WatermarkStyle(),
    /** 归一化中心点 0..1；默认左下角 (0, 1)。语义为"当前方向画面内的块中心" */
    val anchorX: Float = 0f,
    val anchorY: Float = 1f,
    /** 重力跟随关闭时使用的固定旋转角 */
    val rotationDeg: Float = 0f,
    /** 重力跟随开启后按重力 90° 量化旋转水印，方向切换时自动落到预览画面右下角 */
    val followGravity: Boolean = true,
)

/** 拍照瞬间填充的动态值 */
@Serializable
data class DynamicValues(
    /** yyyy-MM-dd HH:mm:ss */
    val time: String = "",
    /** 位置文本：地址 + 经纬度，或降级后的经纬度/未获取定位 */
    val location: String = "未获取定位",
    /** 天气文本（如"晴 26℃"），跟随定位自动获取 */
    val weather: String = "",
)

/** 默认字段（对应计划书 6.1 + 用户补充：计数） */
fun defaultFields(): List<WatermarkField> = listOf(
    WatermarkField(
        id = "f_project",
        type = FieldType.PROJECT_NAME,
        label = "工程名称",
        value = "XX 工程施工记录",
        isTitle = true,
    ),
    WatermarkField(
        id = "f_note",
        type = FieldType.NOTE,
        label = "备注",
        value = "三层梁板钢筋绑扎完成",
    ),
    WatermarkField(
        id = "f_time",
        type = FieldType.DATETIME,
        label = "时间",
        value = "",
    ),
    WatermarkField(
        id = "f_location",
        type = FieldType.LOCATION,
        label = "位置",
        value = "",
    ),
    WatermarkField(
        id = "f_weather",
        type = FieldType.WEATHER,
        label = "天气",
        value = "",
    ),
    WatermarkField(
        id = "f_count",
        type = FieldType.COUNT,
        label = "计数",
        value = "1",
    ),
)

/** 工程组条目：一个工程 = 一份完整水印状态（字段/样式/位置） */
@Serializable
data class ProjectGroupEntry(
    val id: String,
    val name: String,
    val state: WatermarkState,
)

/** 工程组存储：列表 + 当前选中的工程 id */
@Serializable
data class ProjectGroups(
    val current: String = "default",
    val projects: List<ProjectGroupEntry> = listOf(
        ProjectGroupEntry("default", "默认工程", WatermarkState()),
    ),
)
