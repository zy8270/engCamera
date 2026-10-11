package com.engcamera.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.engcamera.data.appDataStore
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 定位源：优先 FusedLocationProvider，异常时降级系统 LocationManager。
 * 文本输出：未获取定位 -> 经纬度 -> 中文地址 + 经纬度（联网反查，失败降级）。
 */
class LocationSource(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 持久化缓存：上次定位文本/天气/坐标。弱网或未定位时启动直接用缓存，不清空
    private var cachedLat = 0.0
    private var cachedLon = 0.0
    private var lastLocation: Location? = null

    init {
        scope.launch {
            runCatching {
                val prefs = context.appDataStore.data.first()
                val savedText = prefs[stringPreferencesKey("last_location_text")]
                val savedWeather = prefs[stringPreferencesKey("last_weather")]
                cachedLat = prefs[doublePreferencesKey("last_lat")] ?: 0.0
                cachedLon = prefs[doublePreferencesKey("last_lon")] ?: 0.0
                if (!savedText.isNullOrBlank()) {
                    hasAddress = true
                    _text.value = savedText
                }
                if (!savedWeather.isNullOrBlank()) _weather.value = savedWeather
            }
        }
    }

    private fun persistCache() {
        scope.launch {
            runCatching {
                context.appDataStore.edit {
                    it[stringPreferencesKey("last_location_text")] = _text.value
                    it[stringPreferencesKey("last_weather")] = _weather.value
                    if (cachedLat != 0.0 || cachedLon != 0.0) {
                        it[doublePreferencesKey("last_lat")] = cachedLat
                        it[doublePreferencesKey("last_lon")] = cachedLon
                    }
                }
            }
        }
    }

    private val _text = MutableStateFlow("未获取定位")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _weather = MutableStateFlow("")
    /** 天气文本（如"晴 26℃"），随定位更新联动获取并缓存 */
    val weather: StateFlow<String> = _weather.asStateFlow()

    // 地址缓存：定位回调很频繁（1-2 秒一次），每次都先显示坐标再等联网反查，
    // 会让中文地址"一下消失一下恢复"。缓存 60 秒或位移超 50 米才刷新文本。
    private var cachedAtMs = 0L
    private var hasAddress = false

    private companion object {
        const val CACHE_TTL_MS = 60_000L
        const val CACHE_MOVE_METERS = 50f
    }

    private fun cacheValid(location: Location): Boolean {
        if (cachedAtMs == 0L) return false
        if (System.currentTimeMillis() - cachedAtMs > CACHE_TTL_MS) return false
        val dist = FloatArray(1)
        Location.distanceBetween(cachedLat, cachedLon, location.latitude, location.longitude, dist)
        return dist[0] <= CACHE_MOVE_METERS
    }

    private var fusedCallback: LocationCallback? = null
    private var lmListener: LocationListener? = null

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!hasLocationPermission()) return
        if (fusedCallback != null || lmListener != null) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let(::onLocation)
                }
            }
            fusedCallback = callback
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { it?.let(::onLocation) }
        } catch (t: Throwable) {
            // 无 GMS 服务等场景，降级系统定位
            startFallback()
        }
    }

    private fun startFallback() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { location -> onLocation(location) }
            lmListener = listener
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 2000L, 1f, listener, Looper.getMainLooper())
                    }
                } catch (_: SecurityException) {
                    // 无权限的 provider 直接跳过
                }
            }
        } catch (_: Throwable) {
            // 定位完全不可用时保持“未获取定位”，不阻塞拍照
        }
    }

    fun stop() {
        try {
            fusedCallback?.let { LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(it) }
        } catch (_: Throwable) {
        }
        fusedCallback = null
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lmListener?.let { lm.removeUpdates(it) }
        } catch (_: Throwable) {
        }
        lmListener = null
    }

    /** 手动刷新：立即请求一次当前位置（编辑器"刷新定位"按钮用），结果通过 text 流通知 */
    fun refreshNow() {
        if (!hasLocationPermission()) return
        cachedAtMs = 0L // 强制绕过缓存
        _text.value = "定位中…"
        var delivered = false
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    delivered = true
                    location?.let(::onLocation)
                }
                .addOnFailureListener {
                    if (!delivered) startFallbackOnce()
                }
        } catch (t: Throwable) {
            startFallbackOnce()
        }
    }

    /** 无 GMS 等场景的单次降级定位 */
    private fun startFallbackOnce() {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { location ->
                onLocation(location)
            }
            for (provider in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                    }
                } catch (_: SecurityException) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun onLocation(location: Location) {
        if (cacheValid(location)) return // 缓存有效：文本保持稳定不闪动
        cachedLat = location.latitude
        cachedLon = location.longitude
        lastLocation = location
        cachedAtMs = System.currentTimeMillis()
        val coords = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
        // 已有中文地址时不要先退回纯坐标（反查失败则保留旧地址），只有从未拿到过地址才先显示坐标
        if (!hasAddress) _text.value = coords
        scope.launch {
            val address = reverseGeocode(location)
            if (!address.isNullOrBlank()) {
                hasAddress = true
                _text.value = "$address ($coords)"
            } else if (!hasAddress) {
                _text.value = coords
            }
            fetchWeather(location)
            persistCache()
        }
    }

    /** Open-Meteo 当前天气（免费无 key），WMO 代码映射中文；失败静默保留旧值 */
    private suspend fun fetchWeather(location: Location) = withContext(Dispatchers.IO) {
        runCatching {
            val url = java.net.URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
                    "&longitude=${location.longitude}&current_weather=true",
            )
            val json = url.openConnection().let { it as java.net.HttpURLConnection }.apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream.bufferedReader().use { it.readText() }
            val temp = Regex("\"temperature\":(-?[0-9.]+)").find(json)?.groupValues?.get(1)
            val code = Regex("\"weathercode\":([0-9]+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
            if (temp != null && code != null) {
                _weather.value = "${wmoText(code)} ${temp.trimEnd('0').trimEnd('.')}℃"
                persistCache()
            }
        }
    }

    /** 手动刷新天气：用最近一次定位坐标强制重取（编辑器"刷新天气"按钮用） */
    fun refreshWeather() {
        val loc = lastLocation ?: Location("cache").apply {
            latitude = cachedLat
            longitude = cachedLon
        }
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        scope.launch { fetchWeather(loc) }
    }

    private fun wmoText(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        in 51..57 -> "毛毛雨"
        in 61..67 -> "雨"
        in 71..77 -> "雪"
        in 80..82 -> "阵雨"
        85, 86 -> "阵雪"
        in 95..99 -> "雷阵雨"
        else -> "未知"
    }

    /**
     * 反地理编码：优先系统 Geocoder；结果太粗（仅国家级，常见于 vivo 等无 GMS 机型，
     * 系统 Geocoder 后端只给"中国"）时降级在线反查（BigDataCloud 免 key，中文）。
     * 都失败返回 null，保持只显示经纬度。
     */
    private suspend fun reverseGeocode(location: Location): String? = withContext(Dispatchers.IO) {
        val addr = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.CHINA)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
        }.getOrNull()

        // 优先拼好的整行（多数机型正常）；部分机型（vivo 等）字段齐全但 getAddressLine(0)
        // 只拼到国名——实测同机竞品读结构化字段能出完整地址，故弱结果时先试字段拼接，再走在线
        val sysLine = addr?.getAddressLine(0)?.trim()
        val weak = sysLine.isNullOrBlank() || sysLine.length <= 4
        if (!weak) return@withContext sysLine

        val composed = addr?.let {
            listOfNotNull(
                it.adminArea,                      // 省
                it.locality,                       // 市
                it.subLocality ?: it.subAdminArea, // 区/县
                it.thoroughfare,                   // 镇/乡/街道/路
                it.subThoroughfare,                // 门牌号（竞品显示到村门牌即在此）
            ).map { a -> a.trim() }.filter { a -> a.isNotBlank() && a != "null" }
                .joinToString("")
        }?.takeIf { it.length > 4 }
        if (composed != null) {
            android.util.Log.d("LocationSrc", "line weak ('$sysLine') -> fields composed: '$composed'")
            return@withContext composed
        }

        val online = onlineReverseGeocode(location)
        android.util.Log.d("LocationSrc", "fields empty ('$sysLine') -> online: '$online'")
        online
    }

    /**
     * 在线反查：Photon(Komoot/OSM)——真坐标、简体中文、免 key、可到街道级，不含国家级前缀。
     * （弃用：Nominatim 国内直连不通；BigDataCloud 免费档实为 IP 定位会答错城市——实测广州坐标回湖北）
     */
    private fun onlineReverseGeocode(location: Location): String? = runCatching {
        val url = java.net.URL(
            "https://photon.komoot.io/reverse?lat=${location.latitude}&lon=${location.longitude}&lang=default",
        )
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val json = conn.inputStream.bufferedReader().use { it.readText() }
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
        val feats = root?.get("features") as? kotlinx.serialization.json.JsonArray
        val props = (feats?.firstOrNull() as? kotlinx.serialization.json.JsonObject)
            ?.get("properties") as? kotlinx.serialization.json.JsonObject ?: return@runCatching null
        fun f(name: String): String? =
            (props[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
        // 例：state=广东省 city=广州市 district=冼村街道（缺 district 时用 locality）
        listOfNotNull(
            f("state"),
            f("city"),
            f("district") ?: f("locality"),
        ).filter { it.isNotBlank() }.joinToString("").ifBlank { null }
    }.getOrNull()
}
