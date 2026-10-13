package com.engcamera.data

/**
 * 内置水印模板库（0.6.0）。
 * 原生 7 款：WatermarkRenderer 按 style.layout 绘制，拍照零额外开销；
 * 网页 3 款：完整 HTML/CSS，WebView 离线渲染成位图（导入的用户模板同机制）。
 */
object TemplatePresets {

    private val stdFields = listOf(
        TemplateFieldDef(FieldType.PROJECT_NAME, "工程名称", isTitle = true),
        TemplateFieldDef(FieldType.DATETIME, "时间"),
        TemplateFieldDef(FieldType.LOCATION, "位置"),
        TemplateFieldDef(FieldType.WEATHER, "天气"),
        // 计数入标准字段（与 defaultFields 一致）：此前缺失导致切换模板后计数丢失
        TemplateFieldDef(FieldType.COUNT, "计数", enabled = false),
        TemplateFieldDef(FieldType.NOTE, "备注", deletable = true),
    )

    private fun style(layout: String, build: WatermarkStyle.() -> WatermarkStyle = { this }): WatermarkStyle =
        build(WatermarkStyle(layout = layout))

    // ---------- 网页模板源（须先于 all 声明） ----------
    // 契约见 TemplateHtml：data-f="TYPE" 行级开关（字段关闭整行移除）、
    // data-extra 额外字段注入点、{BG_A}/{ACCENT} 样式注入。
    // 场记条标题背景属模板固有设计：data-f 只标在文字 span 上，关标题仅隐藏文字。

    private val FILM_HTML = """
        <div data-name="胶片场记" style="font-family:'monospace',monospace;background:rgba(8,8,10,{BG_A});border:1px solid #e8e8e8;border-radius:8px;overflow:hidden;max-width:430px">
          <div style="background:repeating-linear-gradient(45deg,#141414 0 12px,#e8e8e8 12px 24px);padding:5px 12px">
            <span style="background:#fff;color:#111;font-weight:700;padding:1px 8px;border-radius:3px">SCENE</span>
            <span data-f="PROJECT_NAME" style="color:#fff;font-weight:700;margin-left:6px">{{PROJECT_NAME}}</span>
          </div>
          <div style="padding:9px 14px;color:#f2f2f2;font-size:13px;line-height:1.75">
            <div data-f="NOTE">NOTE · {{NOTE}}</div>
            <div data-f="DATETIME">TAKE · {{DATETIME}}</div>
            <div data-f="LOCATION">LOC · {{LOCATION}}</div>
            <div data-f="WEATHER">WX &nbsp;· {{WEATHER}}</div>
            <div data-extra></div>
          </div>
        </div>
    """.trimIndent()

    private val GLASS_HTML = """
        <div data-name="玻璃拟态" style="max-width:430px;background:rgba(255,255,255,{BG_A});border:1px solid rgba(255,255,255,.4);border-radius:18px;padding:11px 16px;font-family:'sans-serif',sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.28)">
          <div data-f="PROJECT_NAME" style="font-weight:700;font-size:16px;color:#fff;text-shadow:0 1px 4px rgba(0,0,0,.55)">{{PROJECT_NAME}}</div>
          <div data-f="PROJECT_NAME" style="height:1px;background:rgba(255,255,255,.45);margin:6px 0"></div>
          <div style="color:#fff;font-size:12.5px;line-height:1.85;opacity:.96;text-shadow:0 1px 3px rgba(0,0,0,.45)">
            <div data-f="NOTE">◆ {{NOTE}}</div>
            <div data-f="DATETIME">◆ {{DATETIME}}</div>
            <div data-f="LOCATION">◆ {{LOCATION}}</div>
            <div data-f="WEATHER">◆ {{WEATHER}}</div>
            <div data-extra></div>
          </div>
        </div>
    """.trimIndent()

    private val MAGAZINE_HTML = """
        <div data-name="杂志卡片" style="max-width:440px;background:linear-gradient(135deg,#101418 0%,#1d2530 100%);border-left:3px solid {ACCENT};border-radius:6px;padding:11px 16px;font-family:'serif',serif">
          <div data-f="PROJECT_NAME" style="font-size:17px;font-weight:700;color:#f5efe4;letter-spacing:2px">{{PROJECT_NAME}}</div>
          <div style="margin-top:6px;color:#cfd6df;font-size:12.5px;line-height:1.95">
            <div data-f="NOTE" style="border-bottom:1px dotted rgba(255,255,255,.28);padding:2px 0">备注 · {{NOTE}}</div>
            <div data-f="DATETIME" style="border-bottom:1px dotted rgba(255,255,255,.28);padding:2px 0">时间 · {{DATETIME}}</div>
            <div data-f="LOCATION" style="border-bottom:1px dotted rgba(255,255,255,.28);padding:2px 0">地点 · {{LOCATION}}</div>
            <div data-f="WEATHER" style="padding:2px 0">天气 · {{WEATHER}}</div>
            <div data-extra></div>
          </div>
        </div>
    """.trimIndent()

    val all: List<WatermarkTemplate> = listOf(
        // ---------- 原生 ----------
        WatermarkTemplate(
            id = "classic",
            name = "经典工程蓝",
            style = style("CLASSIC") { copy(recDot = true, recDotColor = 0xFFFFC107L) },
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("recDot", "记录点", "switch"),
                TemplateOptionDef("recDotSize", "记录点大小", "slider", 0.5f, 2f),
                TemplateOptionDef("recDotOffset", "记录点左边距", "slider", 0f, 0.2f),
                TemplateOptionDef("recGlow", "光晕大小", "slider", 0f, 1.5f),
                TemplateOptionDef("recDotColor", "记录点颜色", "color"),
                TemplateOptionDef("maxWidthRatio", "水印宽度", "slider", 0.3f, 0.9f),
                TemplateOptionDef("titleBgColor", "标题底色", "color"),
                TemplateOptionDef("titleTextColor", "标题文字颜色", "color"),
                TemplateOptionDef("bodyBgColor", "正文底色", "color"),
                TemplateOptionDef("bodyTextColor", "正文文字颜色", "color"),
            ),
        ),
        WatermarkTemplate(
            id = "minimal",
            name = "极简白影",
            style = style("MINIMAL") { copy(bodyBgColor = 0x00000000, titleBgColor = 0x00000000) },
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("maxWidthRatio", "水印宽度", "slider", 0.3f, 0.9f),
                TemplateOptionDef("accentColor", "强调色", "color"),
                TemplateOptionDef("bodyTextColor", "文字颜色", "color"),
            ),
        ),
        WatermarkTemplate(
            id = "border",
            name = "边框卡片",
            style = style("BORDER_CARD") { copy(bodyBgColor = 0x66000000) },
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("maxWidthRatio", "水印宽度", "slider", 0.3f, 0.9f),
                TemplateOptionDef("borderWidth", "边框粗细", "slider", 0.5f, 4f),
                TemplateOptionDef("borderColor", "边框颜色", "color"),
                TemplateOptionDef("accentColor", "标题颜色", "color"),
                TemplateOptionDef("bodyTextColor", "正文文字颜色", "color"),
                TemplateOptionDef("bodyBgColor", "背景颜色", "color"),
                TemplateOptionDef("bgOpacity", "底色不透明度", "slider", 0f, 100f),
            ),
        ),
        WatermarkTemplate(
            id = "singleline",
            name = "单行横排",
            style = style("SINGLE_LINE") { copy(bodyBgColor = 0x88000000, cornerRatio = 0.5f) },
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("maxWidthRatio", "水印宽度", "slider", 0.3f, 0.9f),
                TemplateOptionDef("cornerRatio", "圆角", "slider", 0f, 0.5f),
                TemplateOptionDef("bgOpacity", "底色不透明度", "slider", 0f, 100f),
                TemplateOptionDef("bodyTextColor", "文字颜色", "color"),
            ),
        ),
        WatermarkTemplate(
            id = "gradient",
            name = "渐变底栏",
            style = style("GRADIENT") { copy(titleBgColor = 0x00000000, bodyBgColor = 0xFF000000) },
            fields = stdFields,
            options = listOf(
                // 最大 1.0 = 拉满整张照片宽度（横屏成片按长边铺满，见 WatermarkRenderer.isFullWidthBar）
                TemplateOptionDef("maxWidthRatio", "水印宽度", "slider", 0.3f, 1f),
                TemplateOptionDef("bgOpacity", "渐变深度", "slider", 20f, 100f),
                TemplateOptionDef("gradientColor", "背景颜色", "color"),
                TemplateOptionDef("titleTextColor", "标题颜色", "color"),
                TemplateOptionDef("bodyTextColor", "文字颜色", "color"),
            ),
        ),
        WatermarkTemplate(
            id = "notecard",
            name = "便签卡",
            style = style("NOTE_CARD") {
                copy(
                    bodyBgColor = 0xF2FFFFFF,
                    titleTextColor = 0xFF1A1C1F,
                    bodyTextColor = 0xFF1A1C1F,
                )
            },
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("maxWidthRatio", "水印宽度", "slider", 0.3f, 0.9f),
                TemplateOptionDef("accentColor", "竖条颜色", "color"),
                TemplateOptionDef("titleTextColor", "标题颜色", "color"),
                TemplateOptionDef("bodyTextColor", "文字颜色", "color"),
                TemplateOptionDef("bodyBgColor", "背景颜色", "color"),
                TemplateOptionDef("bgOpacity", "卡片不透明度", "slider", 40f, 100f),
            ),
        ),
        // ---------- 网页（WebView 渲染） ----------
        WatermarkTemplate(
            id = "film",
            name = "胶片场记",
            backend = TemplateBackend.WEB,
            html = FILM_HTML,
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("webScale", "整体缩放", "slider", 0.5f, 1.5f),
                TemplateOptionDef("bgOpacity", "面板不透明度", "slider", 30f, 100f),
            ),
        ),
        WatermarkTemplate(
            id = "glass",
            name = "玻璃拟态",
            backend = TemplateBackend.WEB,
            html = GLASS_HTML,
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("webScale", "整体缩放", "slider", 0.5f, 1.5f),
                TemplateOptionDef("bgOpacity", "玻璃不透明度", "slider", 5f, 60f),
            ),
        ),
        WatermarkTemplate(
            id = "magazine",
            name = "杂志卡片",
            backend = TemplateBackend.WEB,
            html = MAGAZINE_HTML,
            fields = stdFields,
            options = listOf(
                TemplateOptionDef("webScale", "整体缩放", "slider", 0.5f, 1.5f),
                TemplateOptionDef("accentColor", "点缀色", "color"),
            ),
        ),
    )

    fun find(id: String): WatermarkTemplate? = all.firstOrNull { it.id == id }
}
