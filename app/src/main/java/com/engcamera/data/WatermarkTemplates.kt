package com.engcamera.data

/** 模板解析桥：仓库初始化时挂上用户模板，渲染/选择器统一从这里查 */
object WatermarkTemplates {

    @Volatile
    var resolver: (String) -> WatermarkTemplate? = { TemplatePresets.find(it) }

    fun find(id: String): WatermarkTemplate? = resolver(id)
}
