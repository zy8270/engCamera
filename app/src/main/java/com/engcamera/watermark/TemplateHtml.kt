package com.engcamera.watermark

import com.engcamera.data.DynamicValues
import com.engcamera.data.FieldType
import com.engcamera.data.WatermarkField
import com.engcamera.data.WatermarkState
import com.engcamera.data.WatermarkTemplate
import com.engcamera.data.weatherEmojiPrefix

/**
 * 网页模板 HTML 工具：占位符替换、样式注入、导入解析、导出组装。
 *
 * 导入/导出契约（用户可手写）：
 * - 根节点 data-name="模板名"（可省，省略用文件名）
 * - {{TYPE}} 占位符对应字段类型（PROJECT_NAME/NOTE/DATETIME/LOCATION/COUNT/WEATHER）
 * - 占位符可带属性：{{TYPE|label=备注|deletable=true}}
 * - 行级开关标记：data-f="TYPE"（如 <div data-f="DATETIME">TAKE · {{DATETIME}}</div>），
 *   该字段被关闭时整行移除；无标记的占位符关闭时仅替换为空串
 * - 额外字段注入点：<div data-extra></div>，模板占位符未覆盖的启用字段（含用户新加的）
 *   会以"label：值"行注入此处；模板没有该标记时追加到末尾
 * - {BG_A} 会被替换为背景不透明度（来自选项 bgOpacity），{ACCENT} 替换为强调色
 */
object TemplateHtml {

    /** 额外字段注入点标记 */
    private const val EXTRA_MARKER = "<div data-extra></div>"

    /** 单字段当前显示文本（与 WatermarkRenderer.displayText 同规则，不回退标签） */
    private fun fieldValue(f: WatermarkField, dynamic: DynamicValues, emoji: Boolean): String {
        val text = when (f.type) {
            FieldType.DATETIME -> f.value.ifBlank { dynamic.time }
            FieldType.LOCATION -> f.value.ifBlank { dynamic.location.ifBlank { "未获取定位" } }
            FieldType.COUNT -> f.value.filter { it.isDigit() }.ifBlank { "1" }
            FieldType.WEATHER -> f.value.ifBlank { dynamic.weather.ifBlank { "未获取天气" } }
            else -> f.value
        }
        // 天气 emoji 开关：文本值随开关变化，cacheKey 的 values 部分自动失效触发重渲
        return if (f.type == FieldType.WEATHER && emoji) weatherEmojiPrefix(text) + text else text
    }

    /** 字段当前文本（取该类型第一个"启用"的字段；关闭的字段显示标签兜底） */
    fun valueOf(type: FieldType, label: String, state: WatermarkState, dynamic: DynamicValues): String {
        val f = state.fields.firstOrNull { it.type == type && it.enabled } ?: return label
        return fieldValue(f, dynamic, state.style.weatherEmoji).ifBlank { label }
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 组装完整可渲染 HTML（自动包壳 + 注入底色/强调色/字段开关）。
     *  整体缩放不在 HTML 里做（CSS zoom 语义随 WebView 版本不稳），
     *  位图按固定宽渲染、由 WatermarkRenderer 绘制时按 webScale 等比缩放。 */
    fun buildDocument(template: WatermarkTemplate, state: WatermarkState, dynamic: DynamicValues): String? {
        val fragment = template.html ?: return null
        val bgAlpha = (state.style.bgOpacity.coerceIn(0, 100)) / 100.0
        val accent = String.format("#%06X", state.style.accentColor.toLong() and 0xFFFFFF)
        var body = fragment
            .replace("{BG_A}", "%.2f".format(bgAlpha))
            .replace("{ACCENT}", accent)
        val enabledTypes = state.fields.filter { it.enabled }.map { it.type }.toSet()
        for (type in FieldType.entries) {
            if (type in enabledTypes) {
                val label = state.fields.firstOrNull { it.type == type && it.enabled }?.label ?: type.name
                body = replacePlaceholder(body, type.name, escape(valueOf(type, label, state, dynamic)))
            } else {
                // 字段关闭：先移除 data-f 标记的专属行/块，再把残留占位符替换为空
                body = removeFieldBlocks(body, type.name)
                body = replacePlaceholder(body, type.name, "")
            }
        }
        body = injectExtraFields(body, fragment, state, dynamic)
        return """<!doctype html><html><head><meta charset="utf-8"><style>
            html,body{margin:0;padding:0;background:transparent}
            </style></head><body>$body</body></html>"""
    }

    /** 移除标记为某字段专属的行/块：<tag ... data-f="TYPE" ...>...</tag>（含内容整体删除） */
    private fun removeFieldBlocks(html: String, type: String): String =
        Regex("""<([a-zA-Z][a-zA-Z0-9]*)\b[^>]*\bdata-f="$type"[^>]*>[\s\S]*?</\1>""")
            .replace(html, "")

    /**
     * 把模板占位符未覆盖的启用字段（用户后加的备注/自定义项等）注入 data-extra 标记处；
     * 模板没有标记时追加到文档末尾（保证"添加字段"在任何模板下都生效）。
     * 占位符类型的第一个启用字段由占位符负责渲染，不重复注入。
     */
    private fun injectExtraFields(
        body: String,
        fragment: String,
        state: WatermarkState,
        dynamic: DynamicValues,
    ): String {
        val placeholderTypes = FieldType.entries
            .filter { Regex("\\{\\{${it.name}\\b").containsMatchIn(fragment) }
            .toSet()
        val firstEnabledByType = HashMap<FieldType, WatermarkField>()
        for (f in state.fields) {
            if (f.enabled && f.type !in firstEnabledByType) firstEnabledByType[f.type] = f
        }
        val extra = state.fields.filter { f ->
            f.enabled && !f.isTitle &&
                !(f.type in placeholderTypes && firstEnabledByType[f.type]?.id == f.id)
        }
        if (extra.isEmpty()) return body.replace(EXTRA_MARKER, "")
        val rows = extra.joinToString("") { f ->
            val text = escape(fieldValue(f, dynamic, state.style.weatherEmoji))
            if (f.label.isBlank()) "<div>$text</div>" else "<div>${escape(f.label)}：$text</div>"
        }
        return if (body.contains(EXTRA_MARKER)) body.replace(EXTRA_MARKER, rows) else body + rows
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
