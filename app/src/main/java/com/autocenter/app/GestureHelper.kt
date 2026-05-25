package com.autocenter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * 手势辅助：平滑分步滑动居中
 */
object GestureHelper {

    private const val TAG = "GestureHelper"

    /**
     * 平滑滑动：将目标从当前位置移到目标位置。
     * 分步执行，每步间隔 8ms，模拟自然滑动。
     *
     * @param service 无障碍服务实例
     * @param fromX 起始 X
     * @param fromY 起始 Y
     * @param deltaX X 方向移动量
     * @param deltaY Y 方向移动量
     * @param onComplete 完成回调
     */
    fun smoothCenterSwipe(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        deltaX: Float,
        deltaY: Float,
        onComplete: () -> Unit
    ) {
        // 滑动的起始 + 终点
        val startX = fromX
        val startY = fromY
        val endX = fromX + deltaX
        val endY = fromY + deltaY

        val path = Path()
        path.moveTo(startX, startY)

        // 如果距离较远，加入插值点使滑动更平滑
        val totalDist = Math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble())
        if (totalDist > 100) {
            // 中间插值点
            val midX = (startX + endX) / 2f
            val midY = (startY + endY) / 2f
            path.lineTo(midX, midY)
        }

        path.lineTo(endX, endY)

        // 滑动时长：短距离（<100px）用 50ms，长距离按比例
        val duration = (totalDist / 5).toLong().coerceIn(50L, 300L)

        val gestureDescription = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        service.dispatchGesture(gestureDescription, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                onComplete()
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                onComplete() // 取消也算完成
            }
        }, null)
    }
}
