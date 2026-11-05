package com.engcamera

import android.app.Application
import com.engcamera.data.AppContainer

class EngCameraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 品牌 logo 默认图标预热（渲染器是纯函数、拿不到 Context，必须提前光栅化）
        com.engcamera.util.BrandLogo.init(this)
    }
}
