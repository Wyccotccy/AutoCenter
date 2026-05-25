package com.autocenter.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult

/**
 * 摄像头预览上的匹配框重叠层
 * 按归一化比例绘制
 */
class MatchOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentMatches: List<MatchResult> = emptyList()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 0, 0)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val settings = AppSettings(context)
        if (!settings.isReticleEnabled) return
        if (currentMatches.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        for (match in currentMatches) {
            // match coordinates are in original frame resolution (from MatcherEngine)
            // We can't perfectly map without knowing the camera frame resolution,
            // so we use a proportional estimate based on the match position

            val left = (match.left / 1920f) * viewW
            val top = (match.top / 1080f) * viewH
            val right = ((match.left + match.width) / 1920f) * viewW
            val bottom = ((match.top + match.height) / 1080f) * viewH
            val cx = (match.centerX / 1920f) * viewW
            val cy = (match.centerY / 1080f) * viewH

            canvas.drawRect(left, top, right, bottom, fillPaint)
            canvas.drawRect(left, top, right, bottom, strokePaint)
            canvas.drawCircle(cx, cy, 5f, centerPaint)
            canvas.drawLine(cx - 15, cy, cx + 15, cy, centerPaint)
            canvas.drawLine(cx, cy - 15, cx, cy + 15, centerPaint)
        }
    }
}
