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
import com.engcamera.data.AddressParts
import com.engcamera.data.appDataStore
import com.engcamera.data.assembleAddress
import com.engcamera.i18n.I18n
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
     * 结构化地址（v0.6.21，Issue #3）：各级拆开保存，供水印按用户设定的
     * 顺序/开关拼装。此前只留拼好的一整串，导致系统 Geocoder 塞进来的
     * 「邮政编码: 69100-000」无法关闭。空 = 还没有任何地址。
     */
    private val _addressParts = MutableStateFlow(AddressParts())
    val addressParts: StateFlow<AddressParts> = _addressParts.asStateFlow()

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

    /**
     * 定位是否正在手动刷新（v0.6.25，用户反馈 2026-11-12）。
     *
     * 原来只在 text 里塞"定位中…"，但水印的地址改由结构化字段拼装后，拼装函数不会
     * 产出"定位中…"，UI 就完全看不到加载过程——用户以为"毫秒级就拿到了"。
     * 现在用独立状态位驱动界面转圈，不依赖文本内容。
     */
    private val _refreshingLocation = MutableStateFlow(false)
    val refreshingLocation: StateFlow<Boolean> = _refreshingLocation.asStateFlow()

    /**
     * 天气是否正在刷新（v0.6.25）。天气走 Open-Meteo 网络请求，必然有数百毫秒延迟，
     * 此前完全没有加载态，用户点「刷新天气」看不出任何反馈。
     */
    private val _refreshingWeather = MutableStateFlow(false)
    val refreshingWeather: StateFlow<Boolean> = _refreshingWeather.asStateFlow()

    /**
     * 定位失败诊断（v0.6.35，用户需求 2026-11-16：「位置和天气经常出问题，需要显示日志了」）。
     *
     * 此前所有失败都被 `runCatching` 静默吞掉，用户只能看到「未获取定位」，无法区分
     * 是权限、开关、室内无星还是超时。这里把**错误码 + 超时原因**带出来，界面直接
     * 追加在「自动定位：…」那一行（红字）。空串 = 当前无错误。
     */
    private val _locationError = MutableStateFlow("")
    val locationError: StateFlow<String> = _locationError.asStateFlow()

    /** 天气失败诊断（同上）：超时毫秒数、DNS/TLS/HTTP 状态码等 */
    private val _weatherError = MutableStateFlow("")
    val weatherError: StateFlow<String> = _weatherError.asStateFlow()

    // 地址缓存：定位回调很频繁（1-2 秒一次），每次都先显示坐标再等联网反查，
    // 会让中文地址"一下消失一下恢复"。缓存 60 秒或位移超 50 米才刷新文本。
    private var cachedAtMs = 0L
    private var hasAddress = false

    // 手动刷新的临时状态（v0.6.14）：记录刷新前文本用于超时回退，避免卡在"定位中…"
    private var prevTextBeforeRefresh = ""
    @Volatile private var refreshing = false
    private var refreshTimeoutJob: Job? = null
    /**
     * 刷新期间临时挂上的持续 provider 流（v0.6.38，Issue #6 修复）。
     * 红米 K80/澎湃OS 对 GMS 强电池优化，`getCurrentLocation` 可能一直不回调、
     * `requestSingleUpdate` 也不被遵守——上一版"慢但能定位"靠的就是持续 2s 轮询流。
     * 所以刷新期间临时把网络/GPS provider 挂成持续流，拿到点就拆，别依赖一次性通道。
     */
    private val refreshFallbackProviders = mutableSetOf<String>()
    private var refreshFallbackListener: LocationListener? = null

    companion object {
        /** 定位会话持有者标识（v0.6.33）：相机页与编辑器各持一份，最后一个离开才解绑 */
        const val OWNER_CAMERA = "camera"
        const val OWNER_EDITOR = "editor"

        const val CACHE_TTL_MS = 60_000L
        const val CACHE_MOVE_METERS = 50f
        /** 融合定位首帧看门狗窗口：室内/冷启动正常也要几秒，8s 足够区分"慢"与"死" */
        const val WATCHDOG_MS = 8_000L
        /** 手动刷新总预算：超过就退回刷新前文本，不把用户卡在"定位中…" */
        const val REFRESH_TIMEOUT_MS = 12_000L
        /** 刷新期间临时持续 provider 流的取点间隔（v0.6.38）：复刻旧版"慢但能定位"的 2s 轮询 */
        const val REFRESH_FALLBACK_INTERVAL_MS = 2_000L
        /** 权限/定位开关健康巡检周期（改完系统设置回来自愈用） */
        const val HEALTH_INTERVAL_MS = 5_000L

        /**
         * 启动阶段的高精度窗口（v0.6.35，用户需求 2026-11-16：「启动 app 时要确保拿到位置，
         * 才进入轮询」）。
         *
         * 冷启动没有缓存点时用 HIGH_ACCURACY + 2 秒一次，快速把首点拿到；一旦拿到**新鲜点**
         * 就自动降到 [IDLE_INTERVAL_MS] 的 BALANCED 低频档——高频档只是"启动抢首点"，
         * 不是常态。此窗口内没拿到点也不死等，到点就降频（否则室内永远高频耗电）。
         */
        const val ACQUIRE_INTERVAL_MS = 2_000L

        /**
         * 常态轮询间隔（v0.6.35）。原先是 HIGH_ACCURACY + 2 秒一直跑，`dumpsys location`
         * 实测 gps/network 两个 provider 都在 2 秒一次，通知栏定位标志全程长亮
         * （电池实测 gnss 1.21mAh/1h25m，占 app 总耗电约 6%）。
         * 30 秒 + BALANCED 档（只走 Wi-Fi/基站，不启 GPS 芯片）足够写水印地址，
         * 点刷新/进编辑器/按快门时再单独做一次高精度单次取点。
         */
        const val IDLE_INTERVAL_MS = 30_000L

        /** 天气请求超时（v0.6.35）：实测 api.open-meteo.com 单次可达 5.3s（TLS 握手 5.0s），
         *  原来 5s 必然误判超时——这正是「天气时好时坏」的机制 */
        const val WEATHER_CONNECT_TIMEOUT_MS = 8_000
        const val WEATHER_READ_TIMEOUT_MS = 8_000
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
    /** 系统 LocationManager 已注册的 provider（网络通道常驻 + GPS 按需，去重用） */
    private val lmProviders = mutableSetOf<String>()

    /** 首帧看门狗：融合定位注册后迟迟不给点（关开关/无 GMS/被系统压制）就降级 */
    private var watchdogJob: Job? = null
    /** 常态低频单次取点循环（v0.6.35）：拿到首点后每 30 秒取一次，不留常驻注册 */
    private var idleJob: Job? = null
    /** 健康巡检：用户在设置里开关定位、权限被系统收回/恢复，无需重启 app 也能自愈 */
    private var healthJob: Job? = null

    /**
     * 本次会话是否已收到**新鲜**定位（v0.6.33 修）。
     *
     * 关键：只由定位回调置真，**不能**由 `client.lastLocation` 置真——那只是系统缓存的
     * 旧点（用户实测 2026-11-16：室内 GPS 一颗星都没有，融合定位只注册了 gps provider，
     * 迟迟不出点，但 lastLocation 成功回调把看门狗骗过去，降级路径永远不启用）。
     */
    @Volatile private var gotFreshFix = false

    /**
     * 是否处于"启动抢首点"阶段（v0.6.35）。true = 高精度高频（[ACQUIRE_INTERVAL_MS]），
     * 拿到新鲜点或看门狗超时后转 false，走 [IDLE_INTERVAL_MS] 低频档。
     */
    @Volatile private var acquiring = false

    /**
     * 定位会话持有者（v0.6.33）。相机页与水印编辑器都要定位，而 NavHost 切页时
     * **新页先组合、旧页后 dispose**——若各自 start/stop，编辑器刚启动就被相机页的
     * stop 拆掉（用户实测：编辑器里定位图标闪一下就灭）。改成按名字登记持有者，
     * 最后一个离开时才真正解绑。
     */
    private val sessionOwners = mutableSetOf<String>()

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

    /**
     * 请求开始定位（v0.6.33 会话化）。
     *
     * 相机页与水印编辑器都需要持续定位，而 NavHost 切页时新页先组合、旧页后 dispose：
     * 各自 start/stop 会让编辑器刚启动就被相机页的 stop 拆掉（用户实测 2026-11-16：
     * 编辑器里系统定位图标闪一下就灭，而地图软件长亮）。这里按 owner 登记，
     * 只有最后一个持有者离开才真正解绑。
     *
     * @param owner 持有者标识（"camera" / "editor"），同一 owner 重复调用无副作用
     */
    fun start(owner: String = OWNER_CAMERA) {
        sessionOwners += owner
        startLocked()
    }

    /**
     * 释放某个持有者（v0.6.33）：还有其他持有者时不真的停，避免切页期间
     * "新页先组合、旧页后 dispose" 把正在用的定位会话拆掉。
     */
    fun stop(owner: String = OWNER_CAMERA) {
        sessionOwners -= owner
        if (sessionOwners.isNotEmpty()) return
        stopLocked()
    }

    /** 真正建立定位会话（幂等：已在跑就直接返回） */
    private fun startLocked() {
        // 巡检先行：即使此刻权限缺失/开关关闭也持续复查，用户改完设置回来能自愈
        startHealthMonitor()
        if (!hasLocationPermission()) {
            // 权限缺失（含"仅本次允许"被系统撤销）：曾授权过的用户要提示，首次没同意的不打扰
            notePermission(false)
            _locationError.value = I18n.t("无定位权限（PERM_DENIED）")
            return
        }
        notePermission(true)
        // 定位总开关关闭：先标状态（界面提示跳设置），仍尝试一次——部分 ROM 关开关时
        // 仍能拿到缓存点；拿不到由下面的看门狗降级
        val serviceOn = isLocationServiceEnabled()
        noteServiceEnabled(serviceOn)
        if (!serviceOn) {
            _locationError.value = I18n.t("系统定位开关关闭（SVC_OFF）")
        }
        if (fusedCallback != null || lmListener != null) return
        // 已在低频轮询模式（v0.6.35）：只补一次高精度单次取点，不要重开抢首点会话——
        // 否则每次进页面都会把 GPS 芯片拉起来跑满 8 秒
        if (idleJob?.isActive == true) {
            singleShot(Priority.PRIORITY_HIGH_ACCURACY)
            return
        }
        gotFreshFix = false
        acquiring = true
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            // 启动抢首点用高精度（v0.6.35）：用户明确要求"启动 app 时要确保拿到位置，
            // 才进入轮询"。拿到新鲜点后 requestAcquire→requestIdle 自动降频
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ACQUIRE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(1_000L)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let {
                        gotFreshFix = true
                        onLocation(it)
                        // 首点到手：立刻降到低频档，别让高精度会话一直挂着耗电
                        enterIdleMode()
                    }
                }
            }
            fusedCallback = callback
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                // v0.6.14 修复：此前只挂 addOnSuccessListener，关定位开关时 Task 以
                // RESOLUTION_REQUIRED 失败、被静默吞掉，且 fusedCallback 已置非空导致
                // start() 后续全部早退，降级路径永不启用（用户侧表现=永远"未获取定位"）
                .addOnFailureListener { t ->
                    if (fusedCallback === callback) {
                        dropFused(callback)
                        // 错误码带出来（v0.6.35）：RESOLUTION_REQUIRED=定位开关未开，
                        // 其余多为 GMS 缺失/被 ROM 压制
                        _locationError.value = I18n.t("融合定位注册失败（{msg}）")
                            .replace("{msg}", locationFailureReason(t))
                        startFallback()
                    }
                }
            client.lastLocation
                .addOnSuccessListener { loc ->
                    // v0.6.33：lastLocation 只是系统缓存的**旧点**，可以拿来先显示，
                    // 但**不能**置 gotFreshFix——否则看门狗被它骗过去，室内（GPS 无星、
                    // 融合定位只注册了 gps provider）永远不降级网络通道（用户实测 2026-11-16）
                    loc?.let { onLocation(it) }
                }
                .addOnFailureListener {
                    // lastLocation 失败无害（无缓存），真正的判定交给看门狗
                }
            // 网络通道常驻（v0.6.33）：融合定位在室内常常只挂 GPS、迟迟不出点，
            // 而系统 network provider 一直有新鲜点（Wi-Fi/基站，精度足够写水印地址）。
            // 与融合定位并行挂上，谁先给点用谁——不依赖看门狗超时，地址能立刻出来
            startNetworkFallback()
            startWatchdog()
            startHealthMonitor()
        } catch (t: Throwable) {
            // 无 GMS 服务等场景，降级系统定位。
            // 也要起看门狗（v0.6.35）：否则 acquiring 永远为真，provider 注册会一直挂着、
            // 定位标志长亮——8 秒后由看门狗转进低频单次取点模式
            _locationError.value = I18n.t("融合定位不可用（{msg}）")
                .replace("{msg}", locationFailureReason(t))
            startFallback()
            startWatchdog()
        }
    }

    /** 真正拆掉定位会话（所有持有者都离开了） */
    private fun stopLocked() {
        watchdogJob?.cancel()
        watchdogJob = null
        idleJob?.cancel()
        idleJob = null
        healthJob?.cancel()
        healthJob = null
        acquiring = false
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
        lmProviders.clear()
        stopRefreshFallback()
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
                    // 必须走 startLocked 而不是 start()（v0.6.35 修）：start() 默认会登记
                    // OWNER_CAMERA，在只有编辑器持有时会凭空多一个持有者，
                    // 之后编辑器 stop 也解绑不掉——定位会话永久泄漏、通知栏长亮
                    startLocked()
                }
            }
        }
    }

    /**
     * 首帧看门狗：注册融合定位后 8 秒内一个**新鲜**点都没拿到，视为该通道不可用
     * （无完整 GMS、定位被 ROM 压制、开关关闭且无缓存点）。
     * 旧逻辑只在同步抛异常时才降级，异步失败场景永远降不了。
     *
     * v0.6.33：判据从 gotAnyFix 改成 gotFreshFix——`client.lastLocation` 成功回调
     * 只是系统缓存旧点，此前把它当"拿到点"，室内 GPS 无星时看门狗被永久骗过。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(WATCHDOG_MS)
            if (!isActive) return@launch
            if (gotFreshFix) return@launch
            noteServiceEnabled(isLocationServiceEnabled())
            if (_locationError.value.isBlank()) {
                _locationError.value = I18n.t("8 秒内未拿到定位（TIMEOUT_8S）")
            }
            // 8 秒还没首点（室内 GPS 无星就是这个场景）：不再死等高精度，
            // 转低频单次取点模式，别让 GPS 芯片一直醒着
            enterIdleMode()
        }
    }

    /**
     * 进入常态低频模式（v0.6.35，用户需求 2026-11-16）。
     *
     * 关键设计：**拆掉所有持续注册**，改由 [idleJob] 每 [IDLE_INTERVAL_MS] 做一次
     * 单次取点。持续注册会让通知栏定位标志全程长亮（`dumpsys location` 实测原先
     * gps/network 两个 provider 都是 2 秒一次）；单次取点只在取的那一两秒亮一下。
     *
     * 用户要求「启动时确保拿到位置，才进入轮询」——抢首点阶段是 [beginAcquire]，
     * 拿到点（或看门狗超时）才走到这里。
     */
    private fun enterIdleMode() {
        // 幂等（v0.6.35）：首点可能由融合定位与系统 provider 同时送来，
        // 重复进入会把刚建好的 idleJob 反复拆建
        if (!acquiring && idleJob?.isActive == true) return
        acquiring = false
        watchdogJob?.cancel()
        watchdogJob = null
        // 拆掉持续注册（这一步才让定位标志熄灭）
        fusedCallback?.let { dropFused(it) }
        removeLmProviders()
        if (idleJob?.isActive == true) return
        idleJob = scope.launch {
            while (isActive) {
                delay(IDLE_INTERVAL_MS)
                if (!isActive) break
                if (sessionOwners.isEmpty()) break
                // BALANCED：只走 Wi-Fi/基站，不启 GPS 芯片
                singleShot(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            }
        }
    }

    /**
     * 单次取点（v0.6.35）：`setMaxUpdates(1)` 让系统拿到一个点就自动停，
     * 不会留下常驻注册（定位标志自动熄灭）。
     */
    private fun singleShot(priority: Int) {
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val request = LocationRequest.Builder(priority, 1_000L)
                .setMaxUpdates(1)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { loc ->
                        gotFreshFix = true
                        onLocation(loc)
                    }
                    // 显式解绑，避免个别 ROM 不遵守 maxUpdates
                    runCatching { client.removeLocationUpdates(this) }
                }
            }
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: Throwable) {
            // 单次取点失败不报错：下一轮 30 秒后还会再试，避免刷屏
        }
    }

    /**
     * 按需取一次新鲜点（v0.6.35）：拍照前、进编辑器、点刷新时调用。
     * 与 idle 的单次取点区别是**高精度**——这些时刻用户马上要写地址进水印。
     */
    fun requestFreshFix() {
        if (!hasLocationPermission()) return
        // 已在抢首点阶段：那边本来就在高精度高频跑，不必叠加
        if (acquiring) return
        singleShot(Priority.PRIORITY_HIGH_ACCURACY)
    }

    /**
     * 把定位异常翻成用户能看懂的短标签（v0.6.35 用户需求「显示错误代码」）。
     * 不直接抛原始异常 message（长且中英混杂），保留可搜索的英文代号。
     */
    private fun locationFailureReason(t: Throwable): String = when {
        t is SecurityException -> "SEC"
        t.message?.contains("RESOLUTION_REQUIRED", true) == true -> "RESOLUTION_REQUIRED"
        t.message?.contains("TIMEOUT", true) == true -> "TIMEOUT"
        t.message?.contains("unavailable", true) == true -> "UNAVAILABLE"
        else -> t.javaClass.simpleName.ifBlank { "UNKNOWN" }
    }

    private fun dropFused(cb: LocationCallback) {
        try {
            LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(cb)
        } catch (_: Throwable) {
        }
        if (fusedCallback === cb) fusedCallback = null
    }

    /**
     * 系统 LocationManager 通道（v0.6.33）。与融合定位并行常驻，而不是等看门狗超时——
     * 用户实测 2026-11-16：室内融合定位只注册了 gps provider（一颗星都没有），
     * 系统 network provider 却一直有新鲜点（Wi-Fi/基站，hAcc≈30m，写水印地址绰绰有余）。
     */
    private fun startFallback() {
        startNetworkFallback()
        startGpsFallback()
    }

    /** 网络通道：一直挂着（室内唯一可靠的来源），按 provider 去重 */
    private fun startNetworkFallback() {
        registerLmProvider(LocationManager.NETWORK_PROVIDER)
    }

    /** GPS 通道：室外精度更好，与网络通道并行，谁先给点用谁 */
    private fun startGpsFallback() {
        registerLmProvider(LocationManager.GPS_PROVIDER)
    }

    /** 拆掉所有系统 provider 注册（v0.6.35：进低频模式时用，让定位标志熄灭） */
    private fun removeLmProviders() {
        if (lmProviders.isEmpty()) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lmListener?.let { lm.removeUpdates(it) }
        } catch (_: Throwable) {
        }
        lmProviders.clear()
        // 必须清空监听器引用（v0.6.35）：startLocked 的早退判据是 `lmListener != null`，
        // 留着旧引用会让下次进页面直接早退、连单次取点都不做
        lmListener = null
    }

    private fun registerLmProvider(provider: String) {
        if (provider in lmProviders) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isProviderEnabled(provider)) {
                _locationError.value = I18n.t("provider {p} 未启用（PROVIDER_OFF）")
                    .replace("{p}", provider)
                return
            }
            val listener = lmListener ?: LocationListener { location ->
                gotFreshFix = true
                onLocation(location)
                // 系统 provider 拿到点也算首点到手，转低频档（v0.6.35）
                enterIdleMode()
            }.also { lmListener = it }
            // 抢首点期间 2 秒一次（快速拿点），之后由 enterIdleMode 全部拆掉改单次取点。
            // 原先无条件 2 秒常驻是通知栏定位标志长亮的直接原因（v0.6.35）
            lm.requestLocationUpdates(
                provider,
                if (acquiring) ACQUIRE_INTERVAL_MS else IDLE_INTERVAL_MS,
                1f, listener, Looper.getMainLooper(),
            )
            lmProviders += provider
        } catch (_: SecurityException) {
            // 无权限的 provider 直接跳过
        } catch (_: Throwable) {
            // 定位完全不可用时保持"未获取定位"，不阻塞拍照
        }
    }

    /** 手动刷新：立即请求一次当前位置（编辑器"刷新定位"按钮用），结果通过 text 流通知 */
    fun refreshNow() {
        if (!hasLocationPermission()) {
            // 无权限时不要假装在定位：界面据此提示去授权（"仅本次允许"离前台会被系统撤销）
            notePermission(false)
            _locationError.value = I18n.t("无定位权限（PERM_DENIED）")
            return
        }
        notePermission(true)
        val serviceOn = isLocationServiceEnabled()
        noteServiceEnabled(serviceOn)
        if (!serviceOn) _locationError.value = I18n.t("系统定位开关关闭（SVC_OFF）")
        cachedAtMs = 0L // 强制绕过缓存
        prevTextBeforeRefresh = _text.value
        refreshing = true
        _refreshingLocation.value = true
        _text.value = "定位中…"
        // 12 秒总预算：无论融合定位成功/失败/返回 null，到点还没点就退回刷新前文本，
        // 避免永久卡在"定位中…"（v0.6.14）
        scheduleRefreshTimeout()
        // 刷新期间临时挂持续 provider 流（v0.6.38，Issue #6）：红米 K80/澎湃OS 对
        // getCurrentLocation 强电池优化，可能既不成功也不失败地挂起，requestSingleUpdate
        // 也不被遵守——必须有一条"上一版那样"的持续 2s 轮询流兜底，拿到点即拆
        startRefreshFallback()
        var delivered = false
        try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    delivered = true
                    // 成功回调但 location 为 null（无缓存点、GPS 未定位，很常见）：
                    // 旧代码 delivered=true 就吞掉降级，文本永久停在"定位中…"
                    if (location != null) onLocation(location) else {
                        _locationError.value = I18n.t("单次取点返回空（NULL_FIX）")
                        startFallbackOnce()
                    }
                }
                .addOnFailureListener { t ->
                    _locationError.value = I18n.t("单次取点失败（{msg}）")
                        .replace("{msg}", locationFailureReason(t))
                    if (!delivered) startFallbackOnce()
                }
        } catch (t: Throwable) {
            _locationError.value = I18n.t("单次取点异常（{msg}）")
                .replace("{msg}", locationFailureReason(t))
            startFallbackOnce()
        }
    }

    /**
     * 刷新期间临时挂持续 provider 流（v0.6.38，Issue #6 修复）。
     *
     * v0.6.35 的耗电治理把常驻注册全拆掉后，手动刷新只剩 `getCurrentLocation` 和
     * `requestSingleUpdate` 两条一次性通道。红米 K80/澎湃OS 对 GMS 强电池优化，
     * 前者可能一直不回调（Task 挂起，不算成功也不算失败），后者不被遵守——两条通道
     * 都哑火，12s 预算耗尽 → REFRESH_TIMEOUT（用户：上一版慢但能定位，这一版直接不能）。
     * 这里复刻旧版的持续 2s 轮询流，只在刷新期间挂着，拿到点立即拆，不影响常态省电。
     */
    private fun startRefreshFallback() {
        if (refreshFallbackProviderListening()) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = refreshFallbackListener ?: LocationListener { location ->
                onLocation(location)
            }.also { refreshFallbackListener = it }
            for (provider in listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
            )) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue
                    lm.requestLocationUpdates(
                        provider,
                        REFRESH_FALLBACK_INTERVAL_MS,
                        0f,
                        listener,
                        Looper.getMainLooper(),
                    )
                    refreshFallbackProviders += provider
                } catch (_: SecurityException) {
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun refreshFallbackProviderListening(): Boolean =
        refreshFallbackProviders.isNotEmpty() && refreshFallbackListener != null

    /** 拆掉刷新期间的临时 provider 流（拿到点 / 超时 / 取消都走这里） */
    private fun stopRefreshFallback() {
        if (refreshFallbackProviders.isEmpty() && refreshFallbackListener == null) return
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            refreshFallbackListener?.let { lm.removeUpdates(it) }
        } catch (_: Throwable) {
        }
        refreshFallbackProviders.clear()
        refreshFallbackListener = null
    }

    /** 刷新超时兜底：12 秒还没结果就退回刷新前的文本，避免永久卡在"定位中…" */
    private fun scheduleRefreshTimeout() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = scope.launch {
            delay(REFRESH_TIMEOUT_MS)
            if (!isActive) return@launch
            if (refreshing) {
                noteServiceEnabled(isLocationServiceEnabled())
                // 超时原因写进诊断（v0.6.35）：这是"点了刷新一直转圈"的实情
                _locationError.value = I18n.t("刷新超时 {s}s（REFRESH_TIMEOUT）")
                    .replace("{s}", (REFRESH_TIMEOUT_MS / 1000).toString())
                finishRefresh()
            }
        }
    }

    /** 结束刷新态：文本退回刷新前的值（它本身可能是"未获取定位"，此时保持） */
    private fun finishRefresh() {
        refreshing = false
        _refreshingLocation.value = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        stopRefreshFallback()
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
        if (cacheValid(location)) {
            // 缓存有效：文本保持稳定不闪动。但手动刷新必须收掉转圈，
            // 否则用户点了刷新、结果落在缓存范围内，转圈会一直转（v0.6.25）
            if (refreshing) {
                refreshing = false
                _refreshingLocation.value = false
                refreshTimeoutJob?.cancel()
                refreshTimeoutJob = null
                stopRefreshFallback()
                _text.value = prevTextBeforeRefresh.ifBlank { _text.value }
            }
            return
        }
        cachedLat = location.latitude
        cachedLon = location.longitude
        lastLocation = location
        cachedAtMs = System.currentTimeMillis()
        hintDismissed = false // 定位真恢复了：复位，下次再出问题还会提示一次
        noteServiceEnabled(true) // 能拿到点说明通道是通的，清掉提示
        notePermission(true)
        _locationError.value = "" // 拿到点即清错误（v0.6.35）
        // 手动刷新拿到结果：结束刷新态，取消超时回退（否则 12s 后会把新地址覆盖回旧值）
        val wasRefreshing = refreshing
        refreshing = false
        _refreshingLocation.value = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        stopRefreshFallback()
        val coords = String.format(Locale.US, "%.6f, %.6f", location.latitude, location.longitude)
        // 已有中文地址时不要先退回纯坐标（反查失败则保留旧地址），只有从未拿到过地址才先显示坐标。
        // 例外：手动刷新时文本是"定位中…"，必须先回写坐标，否则反查失败就永久停在"定位中…"
        if (!hasAddress || wasRefreshing) _text.value = coords
        scope.launch {
            val parts = reverseGeocode(location)
            if (parts != null) {
                hasAddress = true
                // 结构化地址发布给水印渲染（各级可开关/排序，v0.6.21）
                _addressParts.value = parts
                // "自动定位：…"提示文本与水印默认输出同源（默认隐藏国家+邮编，
                // 所以这里也不会再出现「邮政编码: 69100-000」）
                val line = assembleAddress(
                    parts,
                    AddressParts.DEFAULT_ORDER,
                    AddressParts.DEFAULT_HIDDEN,
                )
                _text.value = if (line.isNotBlank()) "$line ($coords)" else coords
            } else if (!hasAddress || wasRefreshing) {
                _text.value = coords
            }
            fetchWeather(location)
            persistCache()
        }
    }

    /** Open-Meteo 当前天气（免费无 key），WMO 代码映射中文；失败把原因写进 [weatherError] */
    private suspend fun fetchWeather(location: Location) = withContext(Dispatchers.IO) {
        // 超时从 5s 放宽到 8s（v0.6.35）：实机连测 api.open-meteo.com 单次可达 5.36s
        // （TLS 握手 5.04s），5s 必然误判——这就是「天气时好时坏」的机制
        val urlText = "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}" +
            "&longitude=${location.longitude}&current_weather=true"
        var lastReason = ""
        // 一次重试：慢多半发生在 DNS/TLS 握手，第二个请求通常走已建好的连接池
        for (attempt in 1..2) {
            val result = runCatching {
                val conn = java.net.URL(urlText).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = WEATHER_CONNECT_TIMEOUT_MS
                conn.readTimeout = WEATHER_READ_TIMEOUT_MS
                try {
                    val code = conn.responseCode
                    if (code != 200) {
                        lastReason = "HTTP_$code"
                        return@runCatching null
                    }
                    val json = conn.inputStream.bufferedReader().use { it.readText() }
                    val temp = Regex("\"temperature\":(-?[0-9.]+)").find(json)?.groupValues?.get(1)
                    val wmo = Regex("\"weathercode\":([0-9]+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
                    if (temp == null || wmo == null) {
                        lastReason = "PARSE"
                        null
                    } else {
                        "${wmoText(wmo)} ${temp.trimEnd('0').trimEnd('.')}℃"
                    }
                } finally {
                    runCatching { conn.disconnect() }
                }
            }
            val text = result.getOrNull()
            if (text != null) {
                _weather.value = text
                _weatherError.value = ""
                persistCache()
                return@withContext
            }
            // 异常路径：把异常类型/超时翻成可读代号
            result.exceptionOrNull()?.let { lastReason = weatherFailureReason(it) }
            if (attempt == 1) delay(600)
        }
        _weatherError.value = lastReason.ifBlank { "UNKNOWN" }
    }

    /** 天气请求失败原因（v0.6.35）：超时/DNS/TLS/HTTP 各自可辨认 */
    private fun weatherFailureReason(t: Throwable): String = when {
        t is java.net.SocketTimeoutException -> "TIMEOUT_${WEATHER_CONNECT_TIMEOUT_MS}ms"
        t is java.net.UnknownHostException -> "DNS_FAIL"
        t is javax.net.ssl.SSLException -> "TLS_FAIL"
        t is java.net.ConnectException -> "CONNECT_FAIL"
        t is java.io.IOException && t.message?.contains("timeout", true) == true ->
            "TIMEOUT_${WEATHER_CONNECT_TIMEOUT_MS}ms"
        else -> t.javaClass.simpleName.ifBlank { "UNKNOWN" }
    }

    /** 手动刷新天气：用最近一次定位坐标强制重取（编辑器"刷新天气"按钮用） */
    fun refreshWeather() {
        val loc = lastLocation ?: Location("cache").apply {
            latitude = cachedLat
            longitude = cachedLon
        }
        if (loc.latitude == 0.0 && loc.longitude == 0.0) {
            // 没有坐标就没法问天气：明确告诉用户先解决定位（v0.6.35）
            _weatherError.value = I18n.t("无坐标（NO_COORD）")
            return
        }
        _refreshingWeather.value = true
        scope.launch {
            try {
                // 总预算兜底（v0.6.25）：connect+read 各 8s，且失败会重试一次，
                // 最坏 16s+ 才结束——预算必须比内部两轮加起来更长，否则
                // withTimeout 会抢在 fetchWeather 之前掐断，转圈停了但错误没写进诊断
                kotlinx.coroutines.withTimeout(20_000L) { fetchWeather(loc) }
            } catch (_: Throwable) {
                // 超时：写诊断，不把用户卡在转圈上（旧值保留）
                if (_weatherError.value.isBlank()) {
                    _weatherError.value = "TIMEOUT_20s"
                }
            } finally {
                // 无论成功失败都要收掉转圈
                _refreshingWeather.value = false
            }
        }
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
     * 反地理编码：优先系统 Geocoder 的结构化字段；太粗（仅国家级，常见于 vivo 等无 GMS
     * 机型，系统 Geocoder 后端只给"中国"）时降级在线反查（Photon 免 key）。
     *
     * v0.6.21（Issue #3）改为返回 [AddressParts] 而非拼好的整串：此前系统
     * `getAddressLine(0)` 原样返回，巴西场景自带「邮政编码: 69100-000」、`Unnamed Road`
     * 被译成「未命名的道路」，用户无法关闭。现在各级拆开，由 [assembleAddress] 按用户
     * 设定的顺序/开关拼装——默认隐藏国家与邮编，输出与升级前一致。
     *
     * 都失败返回 null，保持只显示经纬度。
     */
    private suspend fun reverseGeocode(location: Location): AddressParts? = withContext(Dispatchers.IO) {
        val addr = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.CHINA)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
        }.getOrNull()

        val parts = addr?.let { a ->
            val place = a.featureName?.trim()
                ?.takeIf { f -> f.isNotBlank() && f != "null" && f != a.thoroughfare && f != a.subLocality }
            AddressParts(
                country = a.countryName.orEmpty().clean(),
                province = a.adminArea.orEmpty().clean(),
                city = a.locality.orEmpty().clean(),
                district = (a.subLocality ?: a.subAdminArea).orEmpty().clean(),
                street = a.thoroughfare.orEmpty().clean(),
                // 地点/小区：featureName 优先，退回 premises（与历史拼接规则一致）
                place = (place ?: a.premises).orEmpty().clean(),
                postcode = a.postalCode.orEmpty().clean(),
                lat = location.latitude,
                lng = location.longitude,
                rawLine = a.getAddressLine(0).orEmpty().trim(),
            )
        }

        // 判定"够不够详细"沿用历史标准（v0.6.14 起）：只有国家一项 = 太粗
        // （vivo 等无 GMS 机型的系统 Geocoder 后端只给"中国"），此时必须降级在线反查。
        // 不能用 hasStructured() 判——它只要国家有值就为真，会让那类机型永远停在"中国"。
        val detailed = parts != null && listOf(
            parts.province, parts.city, parts.district, parts.street, parts.place,
        ).any { it.isNotBlank() }
        if (detailed) return@withContext parts

        val online = onlineReverseGeocode(location)
        if (online != null) return@withContext online

        // 最后兜底：整行原文（可能含邮编等杂质，但这是唯一能显示的地址）
        parts?.takeIf { it.rawLine.length > 4 }
    }

    /** 去空白与 Geocoder 偶发的字面 "null" */
    private fun String.clean(): String = trim().takeIf { it.isNotBlank() && it != "null" }.orEmpty()


    /**
     * 在线反查：Photon(Komoot/OSM)——真坐标、简体中文、免 key、可到街道级，不含国家级前缀。
     * （弃用：Nominatim 国内直连不通；BigDataCloud 免费档实为 IP 定位会答错城市——实测广州坐标回湖北）
     */
    private fun onlineReverseGeocode(location: Location): AddressParts? = runCatching {
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
            (props[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.clean()?.ifBlank { null }
        // v0.6.21：改为结构化返回（原先只拼 state+city+district 一整串）。
        // Photon 的 key 映射：state=省/州、city=市、district/county=区、street=路、
        // name=地点、postcode=邮编、country=国家
        AddressParts(
            country = f("country").orEmpty(),
            province = f("state").orEmpty(),
            city = f("city").orEmpty(),
            district = (f("district") ?: f("county") ?: f("locality")).orEmpty(),
            street = f("street").orEmpty(),
            place = f("name").orEmpty(),
            postcode = f("postcode").orEmpty(),
            lat = location.latitude,
            lng = location.longitude,
        ).takeIf { it.hasStructured() }
    }.getOrNull()
}
