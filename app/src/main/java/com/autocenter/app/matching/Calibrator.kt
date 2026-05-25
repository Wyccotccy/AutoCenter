package com.autocenter.app.matching

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult

/**
 * 滑动自校准：通过实测滑动结果不断修正 multiplier
 *
 * 流程：
 * 1. 检测目标位置 (xt, yt)
 * 2. 计算理论向量 v = (cx-xt, cy-yt)
 * 3. 用当前乘数 multiplier × v 滑动
 * 4. 滑动后检测实际位置 (xt', yt')
 * 5. 修正 multiplier *= (cx - xt) / (cx - xt')
 * 6. 多次取 EMA 平滑
 */
class Calibrator(
    private val settings: AppSettings,
    private val accessibilityService: Lazy<AccessibilityService?>
) {

    private var isCalibrating = false
    private var calibrationStep = 0
    private val totalCalibrationSteps = 5
    private var accumulatedRatioX = 0.0
    private var accumulatedRatioY = 0.0
    private var lastTargetX = 0f
    private var lastTargetY = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    // 回调接口
    var onStatusChanged: ((String) -> Unit)? = null
    var onComplete: ((Float, Float) -> Unit)? = null

    fun isRunning(): Boolean = isCalibrating

    /**
     * 开始校准。接收当前检测到的目标列表（用于找最靠中心的目标）
     */
    fun start(
        screenWidth: Int,
        screenHeight: Int,
        matches: List<MatchResult>
    ) {
        if (isCalibrating) return
        if (matches.isEmpty()) {
            onStatusChanged?.invoke("校准失败：未检测到目标")
            return
        }

        // 找到最靠中心的目标
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val nearest = matches.minByOrNull {
            val dx = it.centerX - cx
            val dy = it.centerY - cy
            dx * dx + dy * dy
        } ?: return

        isCalibrating = true
        calibrationStep = 0
        accumulatedRatioX = 0.0
        accumulatedRatioY = 0.0
        lastTargetX = nearest.centerX
        lastTargetY = nearest.centerY

        onStatusChanged?.invoke("开始校准，共 $totalCalibrationSteps 次")
        runNextCalibration(screenWidth, screenHeight)
    }

    private fun runNextCalibration(screenWidth: Int, screenHeight: Int) {
        if (calibrationStep >= totalCalibrationSteps) {
            // 校准完成
            val ratioX = (accumulatedRatioX / totalCalibrationSteps).toFloat()
            val ratioY = (accumulatedRatioY / totalCalibrationSteps).toFloat()
            settings.calibRatioX = ratioX
            settings.calibRatioY = ratioY
            isCalibrating = false
            onStatusChanged?.invoke("校准完成：X=${"%.2f".format(ratioX)}, Y=${"%.2f".format(ratioY)}")
            onComplete?.invoke(ratioX, ratioY)
            return
        }

        // 在不同方向制造滑动
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f

        // 确保目标不在中心附近，否则偏移一个方向
        val angles = listOf(0.0, 1.57, 3.14, -1.57, 0.79) // 5个不同方向
        val angle = angles[calibrationStep % angles.size]

        val targetRadius = minOf(screenWidth, screenHeight) * 0.25f
        val offsetX = (targetRadius * Math.cos(angle)).toFloat()
        val offsetY = (targetRadius * Math.sin(angle)).toFloat()
        val startX = cx + offsetX
        val startY = cy + offsetY

        // 理论滑动向量
        val dx = cx - startX
        val dy = cy - startY

        // 当前乘数
        val multiplierX = settings.calibRatioX
        val multiplierY = settings.calibRatioY

        // 执行滑动（无障碍手势）
        val service = accessibilityService.value
        if (service == null) {
            onStatusChanged?.invoke("校准失败：无障碍服务未连接")
            isCalibrating = false
            return
        }

        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(startX + dx * multiplierX, startY + dy * multiplierY)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        onStatusChanged?.invoke("校准 ${calibrationStep + 1}/$totalCalibrationSteps")

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                // 等待 200ms 让屏幕稳定，然后等待下一帧匹配结果
                mainHandler.postDelayed({
                    // 注意：这一步的结果由外部注入（setCurrentMatchResult）
                    calibrationStep++
                    runNextCalibration(screenWidth, screenHeight)
                }, 300)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                isCalibrating = false
                onStatusChanged?.invoke("校准被取消")
            }
        }, null)
    }

    /** 由外部注入滑动后检测到的目标位置，用于修正乘数 */
    fun injectMatchResult(matches: List<MatchResult>, screenWidth: Int, screenHeight: Int) {
        if (!isCalibrating) return

        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val nearest = matches.minByOrNull {
            val dx = it.centerX - cx
            val dy = it.centerY - cy
            dx * dx + dy * dy
        } ?: return

        // 实际位移 = 理论目标偏离中心的量 - 实际目标偏离中心的量
        val dx = cx - lastTargetX
        val dy = cy - lastTargetY
        val actualDx = nearest.centerX - cx
        val actualDy = nearest.centerY - cy

        // 乘数修正：如果实际移动不足，增大 multiplier
        if (Math.abs(actualDx) > 5f) {
            accumulatedRatioX += dx.toDouble() / actualDx.toDouble()
        } else {
            accumulatedRatioX += 1.0
        }
        if (Math.abs(actualDy) > 5f) {
            accumulatedRatioY += dy.toDouble() / actualDy.toDouble()
        } else {
            accumulatedRatioY += 1.0
        }
    }

    fun stop() {
        isCalibrating = false
        calibrationStep = 0
    }

    companion object {
        private const val TAG = "Calibrator"
    }
}
