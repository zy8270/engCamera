package com.engcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.engcamera.camera.CameraScreen
import com.engcamera.editor.EditorScreen
import com.engcamera.gallery.GalleryScreen
import com.engcamera.ui.theme.EngCameraTheme
import com.engcamera.ui.theme.LocalAppDarkTheme
import com.engcamera.ui.theme.resolveDarkTheme

object Routes {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val EDITOR = "editor"
}

class MainActivity : ComponentActivity() {

    /**
     * 组合心跳：组合活着时每秒自增。组合被永久挂起（熄屏冷启动竞态、
     * 快速导航导致的组合搁置等）会停跳，由 heartbeatWatchdog 检测并重建自愈。
     */
    private val heartbeat = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastHeartbeat = 0
    private var stalledChecks = 0

    /** 自愈重建次数上限（防重建循环；组合恢复后清零） */
    private var recreateAttempts = 0
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 最近一次窗口绘制时间；窗口停止绘制（整屏黑/冻结）时不再更新 */
    private var lastDrawUptime = android.os.SystemClock.uptimeMillis()

    /** 综合看门狗：前台且亮屏时，组合心跳停跳 或 窗口长时间无绘制 → 重建 Activity 自愈 */
    private val heartbeatWatchdog = object : Runnable {
        override fun run() {
            val pm = getSystemService(android.os.PowerManager::class.java)
            val resumed = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            val interactive = pm?.isInteractive == true
            val current = heartbeat.get()
            val now = android.os.SystemClock.uptimeMillis()
            val noDrawMs = now - lastDrawUptime
            // 窗口还活着就持续请求重绘，保持 lastDrawUptime 更新
            window.decorView.invalidate()
            val compDead = current == lastHeartbeat
            val drawDead = noDrawMs > 8000
            if (resumed && interactive && (compDead || drawDead)) {
                stalledChecks++
                if (stalledChecks >= 2 && recreateAttempts < 3) {
                    recreateAttempts++
                    stalledChecks = 0
                    android.util.Log.w("Heartbeat", "self-healing recreate (compDead=$compDead drawDead=$drawDead)")
                    recreate()
                    return
                }
            } else {
                stalledChecks = 0
            }
            lastHeartbeat = current
            uiHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式：状态栏与导航栏全透明，内容延伸到系统栏下方
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // 禁用系统对比度强制（MIUI 等会在透明导航栏上叠白色横条）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.viewTreeObserver.addOnDrawListener {
            lastDrawUptime = android.os.SystemClock.uptimeMillis()
        }
        val container = (application as EngCameraApp).container
        setContent {
            // 外观主题：跟随系统 / 日间 / 夜间（设置抽屉切换，DataStore 持久化）
            val themeMode by container.repository.themeMode.collectAsState()
            val darkTheme = resolveDarkTheme(themeMode, isSystemInDarkTheme())
            // 系统栏图标对比度跟随主题（透明底上日间深图标/夜间浅图标）；
            // 相机页自行强制浅色图标（取景器恒深色），离开时由相册/编辑器按主题恢复
            LaunchedEffect(darkTheme) {
                val statusStyle =
                    if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = statusStyle, navigationBarStyle = statusStyle)
            }
            CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
                EngCameraTheme(themeMode = themeMode) {
                // 组合心跳：组合存活期间每秒跳一次（delay 挂在组合作用域，组合冻结/销毁即停）
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    recreateAttempts = 0
                    while (true) {
                        heartbeat.incrementAndGet()
                        kotlinx.coroutines.delay(1000)
                    }
                }
                val navController = rememberNavController()
                // 连点返回会把根目的地也弹掉导致 Activity finish（真机复现：编辑器双击保存=整屏黑，
                // 且 finish 后看门狗随之消亡无法自愈）。双保险：400ms 防抖 + 根页面绝不弹。
                var lastPopAt = 0L
                fun debouncedPop() {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastPopAt > 400 && navController.previousBackStackEntry != null) {
                        lastPopAt = now
                        navController.popBackStack()
                    }
                }
                NavHost(navController = navController, startDestination = Routes.CAMERA) {
                    composable(Routes.CAMERA) {
                        CameraScreen(
                            onOpenGallery = { navController.navigate(Routes.GALLERY) },
                            onOpenEditor = { navController.navigate(Routes.EDITOR) },
                        )
                    }
                    composable(Routes.GALLERY) {
                        GalleryScreen(
                            onBack = { debouncedPop() },
                            onGoShoot = { debouncedPop() },
                        )
                    }
                    composable(Routes.EDITOR) {
                        EditorScreen(onBack = { debouncedPop() })
                    }
                }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // 音量键拍照（设置开启且相机页在场时消费，不调节音量）
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val c = (application as EngCameraApp).container
            if (c.repository.volumeShutter.value) {
                c.volumeKeyHandler?.let { handler ->
                    if (handler()) return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        lastHeartbeat = heartbeat.get()
        lastDrawUptime = android.os.SystemClock.uptimeMillis()
        stalledChecks = 0
        uiHandler.postDelayed(heartbeatWatchdog, 3000)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(heartbeatWatchdog)
        super.onPause()
    }
}
