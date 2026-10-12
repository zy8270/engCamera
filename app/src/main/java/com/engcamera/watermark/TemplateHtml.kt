package com.engcamera.watermark

import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkState
import com.engcamera.data.WatermarkTemplate

/**
 * 网页模板 HTML 工具：占位符替换、样式注入、导入解析、导出组装。
 *
 * 导入/导出契约（用户可手写）：
 * - 根节点 data-name="模板名"（可省，省略用文件名）
 * - {{TYPE}} 占位符对应字段类型（PROJECT_NAME/NOTE/DATETIME/LOCATION/COUNT/WEATHER）
 * - 占位符可带属性：{{TYPE|label=备注|deletable=true}}
 * - {BG_A} 会被替换为背景不透明度（来自选项 bgOpacity），{ACCENT} 替换为强调色
 */
object TemplateHtml {

    /** 字段当前文本（与 WatermarkRenderer.displayText 同规则） */
    fun valueOf(type: FieldType, label: String, state: WatermarkState, dynamic: DynamicValues): String {
        val f = state.fields.firstOrNull { it.type == type }
        val stored = f?.value.orEmpty()
        return when (type) {
            FieldType.DATETIME -> stored.ifBlank { dynamic.time }
            FieldType.LOCATION -> stored.ifBlank { dynamic.location.ifBlank { "未获取定位" } }
            FieldType.COUNT -> stored.filter { it.isDigit() }.ifBlank { "1" }
            FieldType.WEATHER -> stored.ifBlank { dynamic.weather.ifBlank { "未获取天气" } }
            else -> stored.ifBlank { label }
        }
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 组装完整可渲染 HTML（自动包壳 + 注入底色/强调色） */
    fun buildDocument(template: WatermarkTemplate, state: WatermarkState, dynamic: DynamicValues): String? {
        val fragment = template.html ?: return null
        val bgAlpha = (state.style.bgOpacity.coerceIn(0, 100)) / 100.0
        val accent = String.format("#%06X", state.style.accentColor.toLong() and 0xFFFFFF)
        var body = fragment
            .replace("{BG_A}", "%.2f".format(bgAlpha))
            .replace("{ACCENT}", accent)
        for (type in FieldType.entries) {
            val label = state.fields.firstOrNull { it.type == type }?.label ?: type.name
            body = replacePlaceholder(body, type.name, escape(valueOf(type, label, state, dynamic)))
        }
        return """<!doctype html><html><head><meta charset="utf-8"><style>
            html,body{margin:0;padding:0;background:transparent}
            </style></head><body>$body</body></html>"""
    }

    private fun replacePlaceholder(html: String, type: String, value: String): String {
        // {{TYPE}} 与 {{TYPE|attr=val|...}} 两种形式都替换为值（注意 Android ICU 需转义 }）
        val plain = "{{$type}}"
        val withAttrs = Regex("\\{\\{$type(?:\\|[^}]*)?\\}\\}")
        return html.replace(plain, value).replace(withAttrs, value)
    }

    /**
     * 解析导入的 HTML：提取 data-name 与 {{TYPE|...}} 占位符的字段属性。
     * 返回 (模板名, 字段定义列表)；无任何占位符时返回 null。
     */
    fun parseImport(html: String, fallbackName: String): Pair<String, List<com.engcamera.data.TemplateFieldDef>>? {
        val name = Regex("data-name\\s*=\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: fallbackName
        val defs = mutableListOf<com.engcamera.data.TemplateFieldDef>()
        val seen = mutableSetOf<FieldType>()
        for (m in Regex("\\{\\{\\s*([A-Z_]+)((?:\\|[^}]*)?)\\s*\\}\\}").findAll(html)) {
            val type = FieldType.entries.firstOrNull { it.name == m.groupValues[1] } ?: continue
            if (!seen.add(type)) continue
            var label = when (type) {
                FieldType.PROJECT_NAME -> "工程名称"
                FieldType.DATETIME -> "时间"
                FieldType.LOCATION -> "位置"
                FieldType.WEATHER -> "天气"
                FieldType.COUNT -> "计数"
                else -> "备注"
            }
            var deletable = type == FieldType.NOTE || type == FieldType.CUSTOM
            for (attr in m.groupValues[2].split('|').drop(1)) {
                val kv = attr.split('=', limit = 2)
                if (kv.size != 2) continue
                when (kv[0].trim()) {
                    "label" -> label = kv[1].trim()
                    "deletable" -> deletable = kv[1].trim() == "true"
                }
            }
            defs += com.engcamera.data.TemplateFieldDef(
                type = type,
                label = label,
                deletable = deletable,
                isTitle = type == FieldType.PROJECT_NAME,
            )
        }
        if (defs.isEmpty()) return null
        return name to defs
    }
}
