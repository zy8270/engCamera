package com.engcamera.data

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 结构化地址（v0.6.21，Issue #3 用户需求 2026-11-12）。
 *
 * 此前位置文本是"拼完即弃"的一整串：系统 Geocoder 的 `getAddressLine(0)` 原样返回，
 * 于是巴西地址自带「邮政编码: 69100-000」、`Unnamed Road` 被译成「未命名的道路」，
 * 用户既关不掉邮编也控制不了任何层级。现在把各级拆开保存，由 [assembleAddress]
 * 按用户的顺序/开关重新拼装。
 *
 * 所有字段为空串时表示"没有结构化地址"（此时回退 [rawLine]）。
 */
@Serializable
data class AddressParts(
    /** 国家（如「巴西」「中国」） */
    val country: String = "",
    /** 省/州（Address.adminArea，巴西场景=Amazonas） */
    val province: String = "",
    /** 市（Address.locality） */
    val city: String = "",
    /** 区/街道（subLocality，缺则 subAdminArea） */
    val district: String = "",
    /** 路（thoroughfare） */
    val street: String = "",
    /** 地点/小区（featureName 或 premises） */
    val place: String = "",
    /** 邮政编码 */
    val postcode: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** 结构化字段太稀疏时的整行兜底（系统 Geocoder 原文，可能含邮编等杂质） */
    val rawLine: String = "",
) {

    /** 除 rawLine / 经纬度外，是否有任何可显示的层级 */
    fun hasStructured(): Boolean = listOf(
        country, province, city, district, street, place, postcode,
    ).any { it.isNotBlank() }

    /** 是否有有效坐标（0,0 视为无——几内亚湾海面不会有人拍工程照） */
    fun hasCoords(): Boolean = lat != 0.0 || lng != 0.0

    companion object {
        /** 层级 key（拼装顺序用逗号分隔串持久化，避免多一层嵌套结构） */
        const val KEY_COUNTRY = "country"
        const val KEY_PROVINCE = "province"
        const val KEY_CITY = "city"
        const val KEY_DISTRICT = "district"
        const val KEY_STREET = "street"
        const val KEY_PLACE = "place"
        const val KEY_COORDS = "coords"
        const val KEY_POSTCODE = "postcode"

        /** 全部层级（编辑器按此渲染列表，顺序以 style.addressOrder 为准） */
        val ALL_KEYS = listOf(
            KEY_COUNTRY, KEY_PROVINCE, KEY_CITY, KEY_DISTRICT,
            KEY_STREET, KEY_PLACE, KEY_COORDS, KEY_POSTCODE,
        )

        /**
         * 默认顺序 = 历史行为：省 市 区 路 地点，最后才是经纬度。
         * 经纬度排在末尾，使「显示经纬度」开关打开时坐标仍在地址后面（与 v159 一致）。
         */
        const val DEFAULT_ORDER = "province,city,district,street,place,coords,country,postcode"

        /**
         * 默认隐藏 = 国家 + 邮编。
         * 这两项正是历史输出里从不出现的（结构化拼接刻意不读 countryName/postalCode），
         * 所以默认状态下地址文本与升级前**完全一致**，不会有用户觉得被改了。
         */
        const val DEFAULT_HIDDEN = "country,postcode"
    }
}

/** 层级的本地化标签（编辑器列表与拼装时前缀用） */
fun addressKeyLabel(key: String): String = when (key) {
    AddressParts.KEY_COUNTRY -> com.engcamera.i18n.I18n.t("国家")
    AddressParts.KEY_PROVINCE -> com.engcamera.i18n.I18n.t("省·州")
    AddressParts.KEY_CITY -> com.engcamera.i18n.I18n.t("市")
    AddressParts.KEY_DISTRICT -> com.engcamera.i18n.I18n.t("区·街道")
    AddressParts.KEY_STREET -> com.engcamera.i18n.I18n.t("路")
    AddressParts.KEY_PLACE -> com.engcamera.i18n.I18n.t("地点·小区")
    AddressParts.KEY_COORDS -> com.engcamera.i18n.I18n.t("经纬度")
    AddressParts.KEY_POSTCODE -> com.engcamera.i18n.I18n.t("邮编")
    else -> key
}

/** 逗号分隔串 → key 列表（过滤未知项，保持持久化数据向前兼容） */
fun parseAddressKeys(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it in AddressParts.ALL_KEYS }.distinct()

/**
 * 按用户设定的顺序与开关拼装地址文本。**唯一**拼装入口：
 * LocationSource（"自动定位：…"提示）与三条渲染路径共用，保证各处文本一致。
 *
 * @param order  层级顺序（逗号分隔，见 [AddressParts.DEFAULT_ORDER]）
 * @param hidden 隐藏的层级（逗号分隔，见 [AddressParts.DEFAULT_HIDDEN]）
 * @param showCoords 经纬度是否显示（复用 WatermarkStyle.showLatLng，单一真相源）
 */
fun assembleAddress(
    parts: AddressParts,
    order: String,
    hidden: String,
    showCoords: Boolean = false,
): String {
    if (!parts.hasStructured()) {
        // 没有任何文字层级：整行原文优先（可能带邮编，但这是唯一能显示的地址）；
        // 没有整行才退回纯坐标（历史兜底格式，无括号）
        if (parts.rawLine.isNotBlank()) return parts.rawLine
        return if (showCoords && parts.hasCoords()) formatCoords(parts.lat, parts.lng) else ""
    }
    val hiddenKeys = parseAddressKeys(hidden).toSet()
    // order 里缺失的层级补在末尾，避免新增层级在老数据里永远不显示
    val ordered = (parseAddressKeys(order) + AddressParts.ALL_KEYS)
        .distinct()

    // 先把"文字层级"按顺序收集起来（经纬度单独处理：历史上它带括号）
    val texts = mutableListOf<String>()
    var coordsIndex = -1
    for (key in ordered) {
        if (key == AddressParts.KEY_COORDS) {
            if (showCoords && parts.hasCoords()) coordsIndex = texts.size
            continue
        }
        if (key in hiddenKeys) continue
        val text = when (key) {
            AddressParts.KEY_COUNTRY -> parts.country
            AddressParts.KEY_PROVINCE -> parts.province
            AddressParts.KEY_CITY -> parts.city
            AddressParts.KEY_DISTRICT -> parts.district
            AddressParts.KEY_STREET -> parts.street
            AddressParts.KEY_PLACE -> parts.place
            AddressParts.KEY_POSTCODE -> parts.postcode
            else -> ""
        }.trim()
        if (text.isNotEmpty() && text != "null") texts += text
    }

    // 只有坐标没有地址：沿用历史兜底格式（纯 "lat, lng"，无括号）
    if (texts.isEmpty()) {
        return if (showCoords && parts.hasCoords()) formatCoords(parts.lat, parts.lng) else ""
    }

    // 分隔符：全 CJK 沿用无分隔（历史中文地址「广东省广州市天河区…」的观感）；
    // 只要混入非 CJK 就用 ", "，否则「AmazonasItacoatiara」会粘成一坨
    val joiner = if (texts.all { it.isMostlyCjk() }) "" else ", "
    if (coordsIndex < 0) return texts.joinToString(joiner)

    // 经纬度按历史观感用括号包起来，与相邻层级之间留一个空格
    // （历史格式：`广东省广州市天河区 (21.148223, 110.317905)`）
    val coords = "(" + formatCoords(parts.lat, parts.lng) + ")"
    val head = texts.take(coordsIndex.coerceAtMost(texts.size))
    val tail = texts.drop(coordsIndex.coerceAtMost(texts.size))
    return buildString {
        if (head.isNotEmpty()) {
            append(head.joinToString(joiner))
            append(' ')
        }
        append(coords)
        if (tail.isNotEmpty()) {
            append(' ')
            append(tail.joinToString(joiner))
        }
    }
}

/** 经纬度格式化（与历史括号段同精度：6 位小数） */
private fun formatCoords(lat: Double, lng: Double): String =
    String.format(Locale.US, "%.6f, %.6f", lat, lng)

/** 该串是否以中日韩字符为主（决定拼装分隔符） */
private fun String.isMostlyCjk(): Boolean {
    if (isEmpty()) return true
    val cjk = count { c ->
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF ||
            c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF
    }
    return cjk * 2 >= length
}

/**
 * 三条渲染路径（原生 WatermarkRenderer / 网页 TemplateHtml / 拼图 CollageRenderer）
 * 共用的位置取值：有结构化地址就用用户设定拼装，否则返回 null 让调用方回退整串。
 *
 * 放在 data 层是为了让渲染器与 LocationSource 用**同一套**规则，避免"提示文本与水印不一致"。
 *
 * @param suppressCoords 工程白模板把经纬度渲染成独立行，此时地址串里不能再带坐标（否则重复）
 */
fun assembledLocation(
    dynamic: DynamicValues,
    style: WatermarkStyle,
    suppressCoords: Boolean = false,
): String? {
    val parts = dynamic.addressParts
    // 完全没有结构化数据 → null，让调用方回退整串（旧数据/离线场景）
    if (!parts.hasStructured() && !parts.hasCoords()) return null
    // 有结构化数据就完全由用户配置决定：即使把所有层级都关掉（结果为空串）也**不**
    // 回退整串——否则用户关掉邮编反而会看到原始整行里的「邮政编码: 69100-000」
    return assembleAddress(
        parts = parts,
        order = style.addressOrder,
        hidden = style.addressHidden,
        showCoords = style.showLatLng && !suppressCoords,
    )
}
