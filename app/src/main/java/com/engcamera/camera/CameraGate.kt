package com.engcamera.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 相机释放闸门（用户拍板方案 2026-10-16）：进编辑器时相机照常关闭（省电），但
 * 「释放完成」由相机页在发起释放**前**注册 HAL 可用性回调来精确感知——后摄
 * onCameraAvailable 即真正释放完，编辑器此时才显示眼睛图标（避免睁眼撞上异步关闭
 * 的黑屏竞态）。注册先于释放，转换事件不会被错过；2s 上限兜底个别设备不回调/
 * 进程恢复直落编辑器的场景。主相机页自带的流看门狗仍作为返回路径的最后保险。
 */
object CameraGate {

    private val _ready = MutableStateFlow(true)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var cm: CameraManager? = null
    private var callback: CameraManager.AvailabilityCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    /**
     * 相机页离开时调用（须在引擎 release 之前）：注册回调 → 置关闭中 → 等后摄可用
     * 放行。backId 解析失败时不拦截（ready 恒 true，退化为无闸门行为）。
     */
    fun markReleasing(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
            }
        }.getOrNull() ?: return
        clear()
        cm = manager
        _ready.value = false
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) {
                if (id == backId) open()
            }
        }
        callback = cb
        runCatching { manager.registerAvailabilityCallback(cb, mainHandler) }
        timeout = Runnable { open() }.also { mainHandler.postDelayed(it, 2000) }
    }

    /** 放行（真信号或超时兜底），注销回调与定时器 */
    private fun open() {
        _ready.value = true
        clear()
    }

    private fun clear() {
        timeout?.let { mainHandler.removeCallbacks(it) }
        timeout = null
        callback?.let { cb -> cm?.let { runCatching { it.unregisterAvailabilityCallback(cb) } } }
        callback = null
    }
}
