package com.autocenter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult
import com.autocenter.app.matching.Calibrator
import com.autocenter.app.matching.MatcherEngine
import com.autocenter.app.matching.TemplateManager
import java.nio.ByteBuffer

/**
 * 前台服务：MediaProjection 持续捕获屏幕帧
 *
 * 流程：
 * ImageReader (fps) → onImageAvailable → Image → Bitmap
 * → 降采样 → MatcherEngine.match() → 结果回调
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    lateinit var settings: AppSettings
    lateinit var matcherEngine: MatcherEngine
    lateinit var templateManager: TemplateManager
    lateinit var calibrator: Calibrator
    lateinit var overlayManager: OverlayViewManager

    private var isRunning = false
    private var isSwiping = false
    private var frameCount = 0L

    // 回调
    var onStatusChanged: ((String) -> Unit)? = null
    var onMatchResults: ((List<MatchResult>) -> Unit)? = null
    var onFrameBitmap: ((Bitmap) -> Unit)? = null  // 测试预览用

    private val mpm: MediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private val wm: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    companion object {
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RESULT_CODE = "result_code"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture"
        private const val TAG = "ScreenCaptureService"

        /** 启动服务 */
        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val intent = Intent(ctx, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 停止服务 */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ScreenCaptureService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ScreenCaptureServiceConnection.serviceInstance = this
        settings = AppSettings(this)
        matcherEngine = MatcherEngine(settings)
        templateManager = TemplateManager(this)
        overlayManager = OverlayViewManager(this)

        calibrator = Calibrator(settings, lazy {
            // 从已注册的服务获取 MyAccessibilityService 实例
            MyAccessibilityService.instance
        })

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        // 兼容 API 26-33: getParcelableExtra(String, Class) 需要 API 33+
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }

        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Android 12+ 可能抛出 ForegroundServiceStartNotAllowedException
            e.printStackTrace()
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture(resultCode, data)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        ScreenCaptureServiceConnection.serviceInstance = null
        super.onDestroy()
    }

    // region 捕获管理

    private fun startCapture(resultCode: Int, data: Intent) {
        if (isRunning) return

        mediaProjection = mpm.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            onStatusChanged?.invoke("屏幕捕获授权失败")
            return
        }

        isRunning = true
        captureThread = HandlerThread("capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        // 加载模板
        val templates = templateManager.loadActiveRuntimeTemplates()
        matcherEngine.updateTemplates(templates)
        onStatusChanged?.invoke("已加载 ${templates.size} 个活跃模板")

        // 开始捕获循环
        captureHandler?.post(captureRunnable)
    }

    private fun stopCapture() {
        isRunning = false
        captureHandler?.removeCallbacks(captureRunnable)
        captureThread?.quitSafely()
        imageReader?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()

        imageReader = null
        virtualDisplay = null
        mediaProjection = null
        captureThread = null
        captureHandler = null

        // matcherEngine handles per-frame cleanup internally
    }

    /** 帧捕获循环 */
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            captureFrame()
            captureHandler?.postDelayed(this, (1000 / settings.fps).toLong())
        }
    }

    private var lastDisplayMetrics: DisplayMetrics? = null

    private fun captureFrame() {
        if (isSwiping) return // 滑动中暂停

        try {
            // 获取当前屏幕信息
            val displayMetrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(displayMetrics)
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val density = displayMetrics.densityDpi

            // 降采样
            val dsPercent = settings.downsamplePercent
            val capWidth = (screenWidth * dsPercent / 100).coerceAtLeast(320)
            val capHeight = (screenHeight * dsPercent / 100).coerceAtLeast(240)

            // 如果尺寸或密度变化，重建 ImageReader + VirtualDisplay
            if (lastDisplayMetrics?.let {
                    it.widthPixels != screenWidth || it.heightPixels != screenHeight
                } != false) {
                recreateCapture(capWidth, capHeight, density)
            }
            lastDisplayMetrics = displayMetrics

            // ImageReader 取最新帧
            val reader = imageReader ?: return
            var image: Image?
            do {
                image = reader.acquireLatestImage()
                val prev = image
                if (image != null && reader.acquireLatestImage() != null) {
                    // 跳过旧帧
                }
            } while (false)

            if (image == null) return

            // 转为 Bitmap
            val bmp = imageToBitmap(image, capWidth, capHeight)
            image.close()

            if (bmp == null) return

            frameCount++

            // 匹配
            val results = matcherEngine.match(bmp, screenWidth, screenHeight, dsPercent)

            // 更新 overlay
            overlayManager.currentMatches = results
            overlayManager.updateOverlay()

            // 回调
            onMatchResults?.invoke(results)
            onFrameBitmap?.let { it(bmp) }

            // 自动居中（开关 ON + 非滑动中 + 非校准中）
            if (settings.isAutoCenterEnabled && !isSwiping && !calibrator.isRunning()) {
                performAutoCenter(results, screenWidth, screenHeight)
            }

            // 校准注入
            if (calibrator.isRunning()) {
                calibrator.injectMatchResult(results, screenWidth, screenHeight)
            }

            // 更新状态
            updateStatus(results)

            bmp.recycle()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun recreateCapture(width: Int, height: Int, densityDpi: Int) {
        imageReader?.close()
        virtualDisplay?.release()

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(null, null)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoCenterCapture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    /** Image → Bitmap（零复制转换） */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        if (rowPadding == 0) return bitmap

        // 如有 row padding，裁剪
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    // endregion

    // region 自动居中

    private fun performAutoCenter(results: List<MatchResult>, screenWidth: Int, screenHeight: Int) {
        if (results.isEmpty()) return

        // 找最靠近屏幕中心的目标
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val target = results.minByOrNull {
            val dx = it.centerX - cx
            val dy = it.centerY - cy
            dx * dx + dy * dy
        } ?: return

        val service = MyAccessibilityService.instance
        if (service == null) {
            onStatusChanged?.invoke("无障碍服务未连接")
            return
        }

        // 计算滑动向量（已乘校准系数）
        val ratioX = settings.calibRatioX
        val ratioY = settings.calibRatioY
        val dx = (cx - target.centerX) * ratioX
        val dy = (cy - target.centerY) * ratioY

        // 如果目标已经在中心附近（<10px），不滑动
        if (Math.abs(dx) < 10f && Math.abs(dy) < 10f) return

        isSwiping = true
        onStatusChanged?.invoke("居中滑动中…")

        GestureHelper.smoothCenterSwipe(service, target.centerX, target.centerY, dx, dy) {
            isSwiping = false
            onStatusChanged?.invoke("居中完成")
        }
    }

    // endregion

    // region 状态更新

    private fun updateStatus(results: List<MatchResult>) {
        if (results.isEmpty()) {
            if (!calibrator.isRunning()) {
                onStatusChanged?.invoke("检测中…")
            }
            return
        }

        // 找最高置信度
        val best = results.maxByOrNull { it.confidence }
        if (best != null) {
            val pct = (best.confidence * 100).toInt()
            val count = results.size
            if (count > 1) {
                onStatusChanged?.invoke("匹配成功(${pct}%) x$count")
            } else {
                onStatusChanged?.invoke("匹配成功(${pct}%)")
            }
        }
    }

    // endregion

    // region 通知

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // 最低优先级，不打扰
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setSound(null, null)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    // endregion
}
