package com.autocenter.app

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * 应用 Application 类
 * 负责在应用启动时初始化 OpenCV 原生库
 */
class AutoCenterApp : Application() {

    companion object {
        private const val TAG = "AutoCenterApp"
        var isOpenCVInitialized = false
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // 全局未捕获异常处理器（防止 Java UnsatisfiedLinkError 直接崩溃）
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}: ${throwable.message}", throwable)
            // 不重新抛出，让系统默认处理器处理
        }

        initOpenCV()
    }

    private fun initOpenCV() {
        try {
            isOpenCVInitialized = OpenCVLoader.initDebug()
            if (isOpenCVInitialized) {
                Log.i(TAG, "OpenCV initialized successfully")
            } else {
                Log.e(TAG, "OpenCV initialization returned false")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "OpenCV initialization failed: ${e.message}", e)
            isOpenCVInitialized = false
        }
    }
}
