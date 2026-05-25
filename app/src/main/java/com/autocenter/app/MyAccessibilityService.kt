package com.autocenter.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：专门用于执行 GestureDescription 滑动
 *
 * 不拦截任何事件，不读取窗口内容，
 * 只用于 dispatchGesture（滑动居中）
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        /** 当前实例，供 ScreenCaptureService 获取 */
        @Volatile
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件，我们只用于手势
    }

    override fun onInterrupt() {
        // 服务被中断
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
