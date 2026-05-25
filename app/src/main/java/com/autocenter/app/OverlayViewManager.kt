package com.autocenter.app

import android.content.Context
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.autocenter.app.data.MatchResult
import kotlin.math.roundToInt

/**
 * 管理 SYSTEM_ALERT_WINDOW 悬浮层，用于在屏幕上绘制红框
 * FLAG_NOT_TOUCHABLE = 触控穿透，完全不干扰下层操作
 */
class OverlayViewManager(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null

    private var isShowing = false
    private var isReticleEnabled = true
    private var isTestMode = false

    /** 当前屏幕上的匹配结果（用于画框） */
    @Volatile
    var currentMatches: List<MatchResult> = emptyList()

    fun setReticleEnabled(enabled: Boolean) {
        isReticleEnabled = enabled
        updateOverlay()
    }

    fun setTestMode(enabled: Boolean) {
        isTestMode = enabled
        updateOverlay()
    }

    /** 显示悬浮层 */
    fun show() {
        if (isShowing) return
        if (!hasOverlayPermission()) return

        val view = OverlayView(context)
        overlayView = view

        val displayMetrics = context.resources.displayMetrics
        val flags = (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            width = displayMetrics.widthPixels
            height = displayMetrics.heightPixels
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
        }

        windowManager.addView(view, params)
        isShowing = true
    }

    /** 隐藏悬浮层 */
    fun hide() {
        if (!isShowing) return
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        isShowing = false
    }

    /** 刷新红框 */
    fun updateOverlay() {
        overlayView?.invalidate()
    }

    /** 检查悬浮窗权限 */
    fun hasOverlayPermission(): Boolean {
        return android.provider.Settings.canDrawOverlays(context)
    }

    /** 自定义 View：在 Canvas 上画红框 */
    private inner class OverlayView(ctx: Context) : View(ctx) {

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(128, 255, 0, 0)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val testBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(128, 0, 255, 0)
            style = Paint.Style.FILL
        }
        private val testStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (!isReticleEnabled && !isTestMode) return
            if (currentMatches.isEmpty()) return

            val basePaint = if (isTestMode) {
                // 测试模式用绿色框
                Pair(testBoxPaint, testStrokePaint)
            } else {
                Pair(boxPaint, strokePaint)
            }

            val (fill, stroke) = basePaint

            for (match in currentMatches) {
                val left = match.left.roundToInt()
                val top = match.top.roundToInt()
                val right = (match.left + match.width).roundToInt()
                val bottom = (match.top + match.height).roundToInt()

                // 填充半透明
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), fill)
                // 边框
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), stroke)

                // 中心点
                canvas.drawCircle(match.centerX, match.centerY, 4f, centerDotPaint)

                // 十字准心
                val cx = match.centerX
                val cy = match.centerY
                canvas.drawLine(cx - 12, cy, cx + 12, cy, centerDotPaint)
                canvas.drawLine(cx, cy - 12, cx, cy + 12, centerDotPaint)
            }
        }
    }
}
