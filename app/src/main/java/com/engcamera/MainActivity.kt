package com.engcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.engcamera.camera.CameraScreen
import com.engcamera.editor.EditorScreen
import com.engcamera.gallery.GalleryScreen
import com.engcamera.ui.theme.EngCameraTheme

object Routes {
    const val CAMERA = "camera"
    const val GALLERY = "gallery"
    const val EDITOR = "editor"
}

class MainActivity : ComponentActivity() {

    /** 首次组合完成标记（Compose 内容真正上屏）；熄屏/后台冷启动时组合可能被挂起 */
    private var firstCompositionRan = false

    /** 自愈重建次数上限（防重建循环） */
    private var recreateAttempts = 0
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 组合看门狗：屏幕亮着且窗口可见但 Compose 还没上屏（黑屏竞态）→ 重建 Activity 自愈 */
    private val compositionWatchdog = object : Runnable {
        override fun run() {
            if (firstCompositionRan) return
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isInteractive == true && recreateAttempts < 3) {
                recreateAttempts++
                recreate()
            } else {
                uiHandler.postDelayed(this, 1500)
            }
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
        val container = (application as EngCameraApp).container
        setContent {
            EngCameraTheme {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    firstCompositionRan = true
                    recreateAttempts = 0
                }
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = Routes.CAMERA) {
                    composable(Routes.CAMERA) {
                        CameraScreen(
                            onOpenGallery = { navController.navigate(Routes.GALLERY) },
                            onOpenEditor = { navController.navigate(Routes.EDITOR) },
                        )
                    }
                    composable(Routes.GALLERY) {
                        GalleryScreen(
                            onBack = { navController.popBackStack() },
                            onGoShoot = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.EDITOR) {
                        EditorScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.postDelayed(compositionWatchdog, 1500)
    }

    override fun onPause() {
        uiHandler.removeCallbacks(compositionWatchdog)
        super.onPause()
    }
}
