package com.autocenter.app

/**
 * 静态引用持有者，让 MainActivity / ConfigActivity 能访问 ScreenCaptureService 实例
 * ScreenCaptureService 在 onCreate 时设置自身引用，onDestroy 时清空
 */
object ScreenCaptureServiceConnection {
    @Volatile
    var serviceInstance: ScreenCaptureService? = null
}
