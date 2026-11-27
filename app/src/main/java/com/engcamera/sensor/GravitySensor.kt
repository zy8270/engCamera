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

    /**
     * 连续滚转角（v0.6.48，用户需求 2026-11-27）：水平仪用，与量化角同符号
     * （0 正立、+90 逆时针横屏）。只在变化超过 DEAD_BAND 时回调，避免每帧刷 UI；
     * 未挂回调时不做任何额外计算（水平仪默认关，不耗电）。
     */
    var onRawRoll: ((Float) -> Unit)? = null

    private var sx = 0f
    private var sy = 0f
    private var current = 0
    /** start() 后尚未回传过首个方向：首读强制回调（否则与重置后的 current 相同就永远不通知，
     *  锁屏重启后 UI 残留锁屏前方向而照片是竖屏的——用户复现 2026-10-15） */
    private var emitInitial = false

    // 方向切换需稳定保持：新方向持续 HOLD_MS 才生效，手抖（瞬时越过阈值）不触发
    private var candidate: Int? = null
    private var candidateSince = 0L

    // 连续滚转角（水平仪）：上次回传值，只在超过死区时更新
    private var lastRawRoll = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < 3) return
            val gx = event.values[0]
            val gy = event.values[1]
            // 向量级低通（避免角度在 ±180 边界跳变）
            sx += ALPHA * (gx - sx)
            sy += ALPHA * (gy - sy)
            val magnitude = sqrt((sx * sx + sy * sy).toDouble())
            if (magnitude < MIN_MAGNITUDE) return // 平放等近零水平分量场景保持上次结果

            val angle = Math.toDegrees(atan2(sx.toDouble(), sy.toDouble())).toFloat()

            // 水平仪：连续角（与量化角同符号），死区内不回调
            onRawRoll?.let { cb ->
                if (kotlin.math.abs(angle - lastRawRoll) >= RAW_DEAD_BAND) {
                    lastRawRoll = angle
                    cb(angle)
                }
            }

            // 就近量化到 90° 倍数（候选），需持续 HOLD_MS 才切换
            var next = (angle / 90f).roundToInt() * 90
            when {
                next > 180 -> next -= 360
                next < -180 -> next += 360
            }
            // -180 与 180 是同一物理方向，统一归一为 180
            if (next == -180) next = 180
            if (emitInitial) {
                // 启动首读：跳过保持窗，直接同步当前物理方向（VM 的 lastRoll 仍在，
                // 方向真变了会照常触发锚点重置）
                emitInitial = false
                candidate = null
                current = next
                onQuantizedRoll?.invoke(next)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (next == current) {
                candidate = null
                return
            }
            if (candidate == next) {
                if (now - candidateSince >= HOLD_MS) {
                    candidate = null
                    current = next
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
        lastRawRoll = 0f
        emitInitial = true
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
        /** 水平仪回传死区（度）：小于该变化不回调，避免每帧刷 UI */
        const val RAW_DEAD_BAND = 0.2f
    }
}
