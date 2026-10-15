package com.engcamera.data

import kotlinx.serialization.Serializable

/** 应用主题模式：跟随系统 / 日间 / 夜间（设置抽屉切换，DataStore 持久化） */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
    /** 是否可删除（模板定义；旧数据按类型规则迁移：仅 NOTE/CUSTOM 可删） */
    val deletable: Boolean = true,
)

/** 水印样式（对应计划书 6.2，颜色为 ARGB Long） */
@Serializable
data class WatermarkStyle(
    val titleBgColor: Long = 0xCC1D4E89,      // 半透明深蓝（用户拍板 2026-10-13，原 0xFF1D4E89 不透明）
    val titleTextColor: Long = 0xFFFFFFFF,    // 白色
    val bodyBgColor: Long = 0x99000000,       // 半透明黑（待确认 1 方案 A）
    val bodyTextColor: Long = 0xFFFFFFFF,     // 白色
    val titleFontSizeRatio: Float = 0.038f,   // 相对画面宽度
    val bodyFontSizeRatio: Float = 0.030f,    // 相对画面宽度
    val maxWidthRatio: Float = 0.576f,        // 水印块最大宽度比例（v0.2.7 收窄 2/3 后按用户反馈 ×1.2 加宽）
    val paddingRatio: Float = 0.014f,         // 内边距比例
    val lineSpacingRatio: Float = 0.006f,     // 行距比例
    val cornerRatio: Float = 0.006f,          // 圆角比例
    // ---- 0.6.0 模板系统扩展（各模板按需使用）----
    val layout: String = "LIQUID_GLASS",      // 原生布局标识（见 WatermarkRenderer；默认模板=液态玻璃）
    val accentColor: Long = 0xFF1D4E89,       // 主题强调色（线条/竖条/角标）
    val borderColor: Long = 0xFFFFFFFF,       // 边框颜色（边框卡片等）
    val bgOpacity: Int = 100,                 // 背景不透明度百分比（0-100）
    val recDot: Boolean = true,               // 记录点（经典模板标题条左侧的录制指示灯，默认开）
    val recDotColor: Long = 0xFFFFC107L,      // 记录点颜色（默认金色）
    val recDotSize: Float = 1.2f,             // 记录点大小倍数（120% 经用户拍板最美观，可调）
    val recGlow: Float = 0f,                  // 光晕大小（0=不画，1=记录点半径）
    val recDotOffset: Float = 0.02f,          // 记录点额外左边距（相对水印块宽度比例，默认 2%）
    val borderWidth: Float = 1f,              // 边框卡片：边框粗细倍数（1=标准线宽）
    val gradientColor: Long = 0xFF000000L,    // 渐变底栏：渐变基色（顶部透明→底部此色）
    val webScale: Float = 1.5f,               // 网页模板整体缩放（1=模板原始大小，默认 1.5=块宽 75% 画面宽，用户拍板 2026-10-14）
    val weatherEmoji: Boolean = true,         // 天气文本首位扁平 emoji（如 ☀️ 晴 26℃），默认开
    // ---- v156 网页透明度独立 ----
    val webBgOpacity: Int = 85,               // 网页模板面板不透明度百分比（0-100；独立于原生 bgOpacity，互不串值）
    // ---- v158 液态玻璃：参数对齐 AndroidLiquidGlassView 库（预览用库视图实时渲染，
    //      成片用同款 AGSL 着色器的 CPU 移植；dp 值按 画面宽/360 等比换算成 px 保证预览/成片一致）----
    val glassCornerRadius: Float = 15f,      // 圆角半径 dp（用户拍板默认 15，0~99）
    val glassRefractionHeight: Float = 20f,  // 折射高度 dp（库默认 20，12~50）
    val glassRefractionOffset: Float = 70f,  // 折射偏移 dp（库默认 70，20~120，渲染时取负）
    val glassTintAlpha: Float = 0f,          // 着色透明度 0~1（库默认 0）
    val glassTintRed: Float = 1f,            // 着色 RGB（库默认全 1）
    val glassTintGreen: Float = 1f,
    val glassTintBlue: Float = 1f,
    val glassDispersion: Float = 0.5f,       // 色散 0~1（库默认 0.5）
    val glassBlurRadius: Float = 0f,         // 模糊半径（库默认 0.01≈0，0~50）
    val textShadow: Float = 1f,              // 文字阴影强度倍数（液态玻璃，0=无阴影，默认 1）
    // ---- v159 位置字段经纬度开关 ----
    val showLatLng: Boolean = false,         // 位置文本显示尾部经纬度括号段（默认关，用户拍板 2026-10-16）
)

/**
 * 天气文本 → 扁平 emoji 前缀（系统 Noto Color Emoji 即扁平风格）。
 * 按词匹配，先具体后宽泛（雷阵雨先于雨、阵雨/毛毛雨先于雨、多云先于晴）；
 * 没匹配上（含"未获取天气"）不加。手动编辑的天气值同样生效。
 */
fun weatherEmojiPrefix(text: String): String = when {
    "雷" in text -> "⛈️ "
    "雪" in text || "雹" in text -> "🌨️ "
    "阵雨" in text || "毛毛雨" in text -> "🌦️ "
    "雨" in text -> "🌧️ "
    "雾" in text || "霾" in text || "沙" in text || "尘" in text -> "🌫️ "
    "多云" in text -> "⛅ "
    "阴" in text -> "☁️ "
    "晴" in text -> "☀️ "
    else -> ""
}

/**
 * 去掉位置文本尾部的经纬度括号段（"显示经纬度"开关关闭时用）。
 * 仅剥离 "(lat, lng)" 形式的尾段；纯经纬度兜底文本（无地址时的 "lat, lng"）保留不动，
 * 避免无地址场景下位置字段被清空。
 */
fun stripLatLng(text: String): String {
    val stripped = text.replace(Regex("\\s*[（(][-+0-9.,\\s]+[)）]\\s*$"), "")
    return stripped.ifBlank { text }
}

/** 水印整体状态（对应计划书 6.3） */
@Serializable
data class WatermarkState(
    val fields: List<WatermarkField> = defaultFields(),
    val style: WatermarkStyle = WatermarkStyle(),
    /** 归一化中心点 0..1；默认底部居中（默认模板=液态玻璃） */
    val anchorX: Float = 0.5f,
    val anchorY: Float = 1f,
    /** 重力跟随关闭时使用的固定旋转角 */
    val rotationDeg: Float = 0f,
    /** 重力跟随开启后按重力 90° 量化旋转水印，方向切换时自动落到预览画面右下角 */
    val followGravity: Boolean = true,
    /** 当前水印模板 id（默认=液态玻璃；内置见 TemplatePresets，用户导入的存 DataStore） */
    val templateId: String = "liquid_glass",
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

/** 默认字段（对应计划书 6.1 + 用户补充：计数；顺序用户拍板 2026-10-13：标题/时间/位置/天气/计数/备注） */
fun defaultFields(): List<WatermarkField> = listOf(
    WatermarkField(
        id = "f_project",
        type = FieldType.PROJECT_NAME,
        label = "工程名称",
        value = "工程施工记录",
        isTitle = true,
        deletable = false,
    ),
    WatermarkField(
        id = "f_time",
        type = FieldType.DATETIME,
        label = "时间",
        value = "",
        deletable = false,
    ),
    WatermarkField(
        id = "f_location",
        type = FieldType.LOCATION,
        label = "位置",
        value = "",
        deletable = false,
    ),
    WatermarkField(
        id = "f_weather",
        type = FieldType.WEATHER,
        label = "天气",
        value = "",
        deletable = false,
    ),
    WatermarkField(
        id = "f_count",
        type = FieldType.COUNT,
        label = "计数",
        value = "1",
        enabled = false, // 计数默认关闭（用户确认 2026-10-11）
        deletable = false,
    ),
    WatermarkField(
        id = "f_note",
        type = FieldType.NOTE,
        label = "备注",
        value = "三层梁板钢筋绑扎完成",
    ),
)

// ================= 水印模板（0.6.0） =================

/** 模板渲染后端：原生 Canvas（内置默认，高性能） / WebView 网页（导入与花哨内置） */
@Serializable
enum class TemplateBackend { NATIVE, WEB }

/** 模板的字段定义：切换模板时生成字段，deletable 覆盖类型默认规则 */
@Serializable
data class TemplateFieldDef(
    val type: FieldType,
    val label: String,
    val enabled: Boolean = true,
    val deletable: Boolean = false,
    val isTitle: Boolean = false,
)

/** 模板暴露的可调选项（编辑器内编辑；值写入 WatermarkStyle 对应键） */
@Serializable
data class TemplateOptionDef(
    val key: String,          // style 键：accentColor/bodyBgColor/bgOpacity/cornerRatio...
    val label: String,        // 显示名：主题色/底色/不透明度...
    val kind: String,         // "color" | "slider" | "switch"
    val min: Float = 0f,
    val max: Float = 1f,
)

/**
 * 水印模板：布局+默认样式+字段配置+可调选项。
 * NATIVE 用 style.layout 标识布局由 WatermarkRenderer 绘制；
 * WEB 用 html（占位符 {{TYPE|label=..|deletable=..}}，data-name 为模板名）由 WebView 渲染。
 */
@Serializable
data class WatermarkTemplate(
    val id: String,
    val name: String,
    val backend: TemplateBackend = TemplateBackend.NATIVE,
    val html: String? = null,
    val fields: List<TemplateFieldDef> = emptyList(),
    val style: WatermarkStyle = WatermarkStyle(),
    val options: List<TemplateOptionDef> = emptyList(),
    val builtIn: Boolean = true,
    /** 全量状态快照（带数据导出的模板）：非空时应用模板=整份状态原样还原（含字段值） */
    val snapshot: WatermarkState? = null,
    /** 模板自带默认锚点（归一化）：非空时应用模板把水印摆到该位置（如液态玻璃=底部居中 0.5/1.0） */
    val anchorX: Float? = null,
    val anchorY: Float? = null,
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


/**
 * 模板导出格式 v2（全量）：模板定义 + 当前完整状态（字段值/样式/锚点）。
 * "导出是什么样的，在另一台设备导入就是什么样的"。
 */
@Serializable
data class TemplateExport(
    val format: Int = 2,
    val name: String,
    val backend: TemplateBackend = TemplateBackend.NATIVE,
    val html: String? = null,
    val options: List<TemplateOptionDef> = emptyList(),
    val state: WatermarkState,
)
