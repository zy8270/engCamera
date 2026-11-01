package com.engcamera.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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
                // 只置真不置假：init 是异步的，可能晚于本轮 notePermission(true) 落盘，
                // 写成 ?: false 会把刚记住的"曾经授权"又抹掉
                if (prefs[booleanPreferencesKey("location_ever_granted")] == true) {
                    everGrantedLocation = true
                }
                cachedLat = prefs[doublePreferencesKey("last_lat")] ?: 0.0
                cachedLon = prefs[doublePreferencesKey("last_lon")] ?: 0.0
                if (!savedText.isNullOrBlank()) {
                    hasAddress = true
                    _text.value = savedText
                    // 有历史定位文本=此前拿到过权限（升级上来的存量用户，新键还是空的）
                    everGrantedLocation = true
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

    /**
     * 系统「定位」总开关是否关闭（v0.6.14 用户反馈"已授权定位却拿不到定位"）：
     * 权限与定位开关是两件事，关开关时融合定位的 Task 会以失败返回（此前被静默丢弃），
     * 界面据此给用户可点击跳系统设置的提示。
     */
    private val _serviceDisabled = MutableStateFlow(false)
    val serviceDisabled: StateFlow<Boolean> = _serviceDisabled.asStateFlow()

    /** 定位权限缺失（"仅本次允许"离开前台后系统撤销等）：界面提示去授权 */
    private val _permissionDenied = MutableStateFlow(false)
    val permissionDenied: StateFlow<Boolean> = _permissionDenied.asStateFlow()

    /**
     * 是否曾经拿到过定位权限（持久化）。用来区分两种"没有权限"：
     * 从没同意过的用户不该被常驻红条反复打扰（启动时本来就会弹系统授权框）；
     * 而"授权过、后来被收回"的用户正是反馈里那一类，必须给提示。
     */
    @Volatile private var everGrantedLocation = false

    /**
     * 提示条是否被用户手动关掉（用户反馈 2026-11-01：红条太显眼且无法关闭）。
     * 关掉后本轮不再亮；等定位真的恢复正常（权限+开关都通）再复位，
     * 这样下次再出问题时仍会提示一次。不做持久化：它是提示不是设置项。
     */
    @Volatile private var hintDismissed = false

    /** 用户点了提示条右侧的 X */
    fun dismissLocationHint() {
        hintDismissed = true
        _serviceDisabled.value = false
        _permissionDenied.value = false
    }

    private val _weather = MutableStateFlow("")
    /** 天气文本（如"晴 26℃"），随定位更新联动获取并缓存 */
    val weather: StateFlow<String> = _weather.asStateFlow()

    // 地址缓存：定位回调很频繁（1-2 秒一次），每次都先显示坐标再等联网反查，
    // 会让中文地址"一下消失一下恢复"。缓存 60 秒或位移超 50 米才刷新文本。
    private var cachedAtMs = 0L
    private var hasAddress = false

    // 手动刷新的临时状态（v0.6.14）：记录刷新前文本用于超时回退，避免卡在"定位中…"
    private var prevTextBeforeRefresh = ""
    @Volatile private var refreshing = false
    private var refreshTimeoutJob: Job? = null

    private companion object {
        const val CACHE_TTL_MS = 60_000L
        const val CACHE_MOVE_METERS = 50f
        /** 融合定位首帧看门狗窗口：室内/冷启动正常也要几秒，8s 足够区分"慢"与"死" */
        const val WATCHDOG_MS = 8_000L
        /** 手动刷新总预算：超过就退回刷新前文本，不把用户卡在"定位中…" */
        const val REFRESH_TIMEOUT_MS = 12_000L
        /** 权限/定位开关健康巡检周期（改完系统设置回来自愈用） */
        const val HEALTH_INTERVAL_MS = 5_000L
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

    /** 首帧看门狗：融合定位注册后迟迟不给点（关开关/无 GMS/被系统压制）就降级 */
    private var watchdogJob: Job? = null
    /** 健康巡检：用户在设置里开关定位、权限被系统收回/恢复，无需重启 app 也能自愈 */
    private var healthJob: Job? = null
    /** 本次融合定位是否已收到任何结果（区分"还没热身"与"彻底没点"） */
    @Volatile private var gotAnyFix = false

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 记录权限状态：拿到权限就持久化"曾经授权"，之后即使被系统收回也能给用户提示。
     * 界面提示只在「曾经授权过但此刻没有」时亮起，首次安装没同意过的用户不受打扰。
     */
    private fun notePermission(permitted: Boolean) {
        if (permitted) {
            if (!everGrantedLocation) {
                everGrantedLocation = true
                scope.launch {
                    runCatching {
                        context.appDataStore.edit {
                            it[booleanPreferencesKey("location_ever_granted")] = true
                        }
                    }
                }
            }
            _permissionDenied.value = false
        } else {
            // 用户关掉提示条后不再复亮，等定位真恢复（onLocation）才复位
            _permissionDenied.value = everGrantedLocation && !hintDismissed
        }
    }

    /** 系统定位开关状态：同样只对"曾用过定位"的用户提示，避免打扰不用定位的人 */
    private fun noteServiceEnabled(enabled: Boolean) {
        _serviceDisabled.value = !enabled && everGrantedLocation && !hintDismissed
    }

    /** 系统定位开关（Android 9+ 有 isLocationEnabled；更低版本按 provider 探测） */
    private fun isLocationServiceEnabled(): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Throwable) {
        true // 探测失败时不要误报，交给定位回调本身
    }

    fun start() {
        // 巡检先行：即使此刻权限缺失/开关关闭也持续复查，用户改完设置回来能自愈
        startHealthMonitor()
        if (!hasLocationPermission()) {
            // 权限缺失（含"仅本次允许"被系统撤销）：曾授权过的用户要提示，首次没同意的不打扰
            notePermission(false)
            return
        }
        notePermission(true)
        // 定位总开关关闭：先标状态（界面提示跳设置），仍尝试一次——部分 ROM 关开关时
        // 仍能拿到缓存点；拿不到由下面的看门狗降级
        noteServiceEnabled(isLocationServiceEnabled())
        if (fusedCallback != null || lmListener != null) return
        gotAnyFix = false
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                .setMinUpdateIntervalMillis(1000L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let {
                        gotAnyFix = true
                        onLocation(it)
                    }
                }
            }
            fusedCallback = callback
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                // v0.6.14 修复：此前只挂 addOnSuccessListener，关定位开关时 Task 以
                // RESOLUTION_REQUIRED 失败、被静默吞掉，且 fusedCallback 已置非空导致
                // start() 后续全部早退，降级路径永不启用（用户侧表现=永远"未获取定位"）
                .addOnFailureListener {
                    if (fusedCallback === callback) {
                        dropFused(callback)
                        startFallback()
                    }
                }
            client.lastLocation
                .addOnSuccessListener { it?.let { loc -> gotAnyFix = true; onLocation(loc) } }
                .addOnFailureListener {
                    // lastLocation 失败无害（无缓存），真正的判定交给看门狗
                }
            startWatchdog()
            startHealthMonitor()
        } catch (t: Throwable) {
            // 无 GMS 服务等场景，降级系统定位
            startFallback()
        }
    }

    /**
     * 健康巡检（v0.6.14）：用户反馈的"已授权却拿不到定位"里有一类是中途变化的——
     * 在系统设置里关掉再打开定位总开关、或把权限改成"仅本次允许"后切后台被系统收回。
     * 只在 start() 判一次的话，用户改完设置回到 app 仍停在旧状态（要杀进程重开才恢复）。
     * 每 5 秒复查权限与开关，状态由不可用转可用时自动重新注册定位。
     */
    private fun startHealthMonitor() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            // 只在"由不可用转可用"的瞬间重注册：否则关开关/GMS 异常时每 5 秒重试一次，
            // 白白耗电（注册失败本身就是常态，不该被轮询放大）
            var wasBlocked = false
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                if (!isActive) break
                val permitted = hasLocationPermission()
                val enabled = isLocationServiceEnabled()
                notePermission(permitted)
                if (!permitted) {
                    wasBlocked = true
                    continue
                }
                noteServiceEnabled(enabled)
                if (!enabled) {
                    wasBlocked = true
                    continue
                }
                // 只在"由不可用转可用"的瞬间重注册一次：普通失败不轮询重试，避免耗电
                if (wasBlocked) {
                    wasBlocked = false
                    start()
                }
            }
        }
    }

    /**
     * 首帧看门狗：注册融合定位后 8 秒内一个点都没拿到，视为该通道不可用（无完整 GMS、
     * 定位被 ROM 压制、开关关闭且无缓存点），解绑并降级系统 LocationManager。
     * 旧逻辑只在同步抛异常时才降级，异步失败场景永远降不了。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(WATCHDOG_MS)
            if (!isActive) return@launch
            if (gotAnyFix) return@launch
            noteServiceEnabled(isLocationServiceEnabled())
            fusedCallback?.let { cb ->
                if (lmListener == null) {
                    dropFused(cb)
                    startFallback()
                }
            }
        }
    }

    private fun dropFused(cb: LocationCallback) {
        try {
            LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(cb)
        } catch (_: Throwable) {
        }
        if (fusedCallback === cb) fusedCallback = null
    }

    private fun startFallback() {
        if (lmListener != null) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = LocationListener { location ->
                gotAnyFix = true
                onLocation(location)
            }
            var registered = 0
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.requestLocationUpdates(provider, 2000L, 1f, listener, Looper.getMainLooper())
                        registered++
                    }
                } catch (_: SecurityException) {
                    // 无权限的 provider 直接跳过
                }
            }
            // 一个 provider 都没注册上（定位开关关闭）：不要把监听器留成非空，
            // 否则 start() 的早退判断会让"用户后来打开定位"永远不再重试
            if (registered == 0) return
            lmListener = listener
        } catch (_: Throwable) {
            // 定位完全不可用时保持“未获取定位”，不阻塞拍照
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        healthJob?.cancel()
        healthJob = null
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
        if (!hasLocationPermission()) {
            // 无权限时不要假装在定位：界面据此提示去授权（"仅本次允许"离前台会被系统撤销）
            notePermission(false)
            return
        }
        notePermission(true)
        noteServiceEnabled(isLocationServiceEnabled())
        cachedAtMs = 0L // 强制绕过缓存
        prevTextBeforeRefresh = _text.value
        refreshing = true
        _text.value = "定位中…"
        // 12 秒总预算：无论融合定位成功/失败/返回 null，到点还没点就退回刷新前文本，
        // 避免永久卡在"定位中…"（v0.6.14）
        scheduleRefreshTimeout()
        var delivered = false
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    delivered = true
                    // 成功回调但 location 为 null（无缓存点、GPS 未定位，很常见）：
                    // 旧代码 delivered=true 就吞掉降级，文本永久停在"定位中…"
                    if (location != null) onLocation(location) else startFallbackOnce()
                }
                .addOnFailureListener {
                    if (!delivered) startFallbackOnce()
                }
        } catch (t: Throwable) {
            startFallbackOnce()
        }
    }

    /** 刷新超时兜底：12 秒还没结果就退回刷新前的文本，避免永久卡在"定位中…" */
    private fun scheduleRefreshTimeout() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = scope.launch {
            delay(REFRESH_TIMEOUT_MS)
            if (!isActive) return@launch
            if (refreshing) {
                noteServiceEnabled(isLocationServiceEnabled())
                finishRefresh()
            }
        }
    }

    /** 结束刷新态：文本退回刷新前的值（它本身可能是"未获取定位"，此时保持） */
    private fun finishRefresh() {
        refreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        _text.value = prevTextBeforeRefresh.ifBlank { "未获取定位" }
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
        hintDismissed = false // 定位真恢复了：复位，下次再出问题还会提示一次
        noteServiceEnabled(true) // 能拿到点说明通道是通的，清掉提示
        notePermission(true)
        // 手动刷新拿到结果：结束刷新态，取消超时回退（否则 12s 后会把新地址覆盖回旧值）
        val wasRefreshing = refreshing
        refreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        val coords = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
        // 已有中文地址时不要先退回纯坐标（反查失败则保留旧地址），只有从未拿到过地址才先显示坐标。
        // 例外：手动刷新时文本是"定位中…"，必须先回写坐标，否则反查失败就永久停在"定位中…"
        if (!hasAddress || wasRefreshing) _text.value = coords
        scope.launch {
            val address = reverseGeocode(location)
            if (!address.isNullOrBlank()) {
                hasAddress = true
                _text.value = "$address ($coords)"
            } else if (!hasAddress || wasRefreshing) {
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

        val composed = addr?.let { a ->
            // 对齐谷歌整行组词：省 市 区 村(featureName) 路(thoroughfare) 巷号(subThoroughfare) 小区(premise)
            // 例：广东省 广州市 天河区 新塘 西约新村大街 上十巷15号 小新塘回迁房D1区（用户确认 2026-10-11）
            val place = a.featureName?.trim()
                ?.takeIf { f -> f.isNotBlank() && f != "null" && f != a.thoroughfare && f != a.subLocality }
            // 用户确认顺序：省 市 区 路(thoroughfare) 地点/小区(featureName, premises)；门牌号(subThoroughfare)不要
            buildList {
                add(a.adminArea)
                add(a.locality)
                add(a.subLocality ?: a.subAdminArea)
                add(a.thoroughfare)
                add(place)
                add(a.premises)
            }.mapNotNull { x -> x?.trim()?.takeIf { it.isNotBlank() && it != "null" } }
                .joinToString("")
        }?.takeIf { it.length > 4 }
        if (composed != null) {
            return@withContext composed
        }

        val online = onlineReverseGeocode(location)
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
