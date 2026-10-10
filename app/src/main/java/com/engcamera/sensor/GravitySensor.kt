package com.engcamera.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 重力方向：优先用加速度计（底层硬件，AOSP 符号约定被系统强制，厂商基本不会做错；
 * 部分 OEM 的 TYPE_GRAVITY 融合传感器存在符号反转，不可依赖），无加速度计时回退重力传感器。
 *
 * 直接计算相对大地的绝对滚转角（不做启动校准——横着启动也不影响）：
 * 正立 (0, +9.81, 0) → 0°；逆时针横屏 → +90；顺时针 → -90；倒置 → ±180（归一为 180）。
 * 向量级低通滤波 + 新方向持续 500ms 保持才切换（手抖不触发）。
 */
class GravitySensor(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 量化滚转角：0 正立、-90 顺时针横屏、90 逆时针横屏、180 倒立 */
    var onQuantizedRoll: ((Int) -> Unit)? = null

    private var sx = 0f
    private var sy = 0f
    private var current = 0
    private var loggedFirst = false

    // 方向切换需稳定保持：新方向持续 HOLD_MS 才生效，手抖（瞬时越过阈值）不触发
    private var candidate: Int? = null
    private var candidateSince = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val gx = event.values[0]
            val gy = event.values[1]
            if (!loggedFirst) {
                loggedFirst = true
                android.util.Log.d(
                    "GravitySensor",
                    "first reading: (${gx}, ${gy}, ${event.values[2]}), type=${event.sensor.type}",
                )
            }
            // 向量级低通（避免角度在 ±180 边界跳变）
            sx += ALPHA * (gx - sx)
            sy += ALPHA * (gy - sy)
            val magnitude = sqrt((sx * sx + sy * sy).toDouble())
            if (magnitude < MIN_MAGNITUDE) return // 平放等近零水平分量场景保持上次结果

            val angle = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()

            // 就近量化到 90° 倍数（候选），需持续 HOLD_MS 才切换
            var next = (angle / 90f).roundToInt() * 90
            when {
                next > 180 -> next -= 360
                next < -180 -> next += 360
            }
            // -180 与 180 是同一物理方向，统一归一为 180
            if (next == -180) next = 180
            val now = SystemClock.elapsedRealtime()
            if (next == current) {
                candidate = null
                return
            }
            if (candidate == next) {
                if (now - candidateSince >= HOLD_MS) {
                    candidate = null
                    current = next
                    android.util.Log.d("GravitySensor", "quantized=$next (angle=$angle)")
                    onQuantizedRoll?.invoke(next)
                }
            } else {
                candidate = next
                candidateSince = now
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        sx = 0f
        sy = 0f
        current = 0
        candidate = null
        loggedFirst = false
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        sensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }

    private companion object {
        const val ALPHA = 0.2f
        const val MIN_MAGNITUDE = 5.0
        /** 新方向需持续保持的时长，手抖不触发 */
        const val HOLD_MS = 500L
    }
}
