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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 定位源：优先 FusedLocationProvider，异常时降级系统 LocationManager。
 * 文本输出：未获取定位 -> 经纬度 -> 中文地址 + 经纬度（联网反查，失败降级）。
 */
class LocationSource(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _text = MutableStateFlow("未获取定位")
    val text: StateFlow<String> = _text.asStateFlow()

    // 地址缓存：定位回调很频繁（1-2 秒一次），每次都先显示坐标再等联网反查，
    // 会让中文地址"一下消失一下恢复"。缓存 60 秒或位移超 50 米才刷新文本。
    private var cachedLat = 0.0
    private var cachedLon = 0.0
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
        }
    }

    /** 联网反地理编码，失败返回 null，保持只显示经纬度 */
    private suspend fun reverseGeocode(location: Location): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.CHINA)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()
    }
}
