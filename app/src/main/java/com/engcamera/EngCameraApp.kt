package com.engcamera

import android.app.Application
import com.engcamera.data.AppContainer

class EngCameraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
