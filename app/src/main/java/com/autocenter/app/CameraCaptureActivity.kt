package com.autocenter.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult
import com.autocenter.app.matching.MatcherEngine
import com.autocenter.app.matching.TemplateManager
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * 摄像头纯识别模式
 * 使用 CameraX 预览 + ImageAnalysis 逐帧匹配
 */
class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var settings: AppSettings
    private lateinit var matcherEngine: MatcherEngine
    private lateinit var templateManager: TemplateManager

    private var cameraProvider: ProcessCameraProvider? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = AppSettings(this)
        matcherEngine = MatcherEngine(settings)
        templateManager = TemplateManager(this)

        // 加载模板
        val templates = templateManager.loadActiveRuntimeTemplates()
        matcherEngine.updateTemplates(templates)
        binding.tvCameraStatus.text = "已加载 ${templates.size} 个模板"

        binding.btnCameraBack.setOnClickListener { finish() }

        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // 预览
            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = binding.previewView.surfaceProvider }

            // 图像分析 - 使用默认 YUV_420_888 格式，转为 RGBA 再匹配
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480)) // 降低分辨率加速
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        analyzeFrame(imageProxy)
                    }
                }

            // 选择后置摄像头
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, analysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "启动摄像头失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** 分析单帧 */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val bmp = yuv420ToBitmap(imageProxy) ?: run {
            imageProxy.close()
            return
        }

        val dsPercent = settings.downsamplePercent

        val results = matcherEngine.match(bmp, bmp.width, bmp.height, dsPercent)

        runOnUiThread {
            updateStatus(results)
            val overlay = findViewById<MatchOverlay>(R.id.matchOverlay)
            overlay.currentMatches = results
            overlay.invalidate()
        }

        bmp.recycle()
        imageProxy.close()
    }

    /** YUV_420_888 → Bitmap (ARGB_8888) */
    private fun yuv420ToBitmap(proxy: ImageProxy): Bitmap? {
        val image: Image = proxy.image ?: return null
        val planes = image.planes

        // YUV_420_888 to NV21
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val width = proxy.width
        val height = proxy.height
        val ySize = yPlane.rowStride * height
        val uvSize = uPlane.rowStride * height / 2

        val nv21 = ByteArray(ySize + uvSize)

        // Copy Y plane
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride

        if (yPixelStride == 1 && yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            val row = ByteArray(yRowStride)
            for (r in 0 until height) {
                yBuffer.get(row, 0, yRowStride)
                System.arraycopy(row, 0, nv21, r * width, width)
            }
        }

        // Copy UV planes (interleaved as VU for NV21)
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var uvOffset = ySize
        val uvHeight = height / 2

        if (uvPixelStride == 2 && uvRowStride == width) {
            // Already interleaved VU
            for (r in 0 until uvHeight) {
                for (c in 0 until width step 2) {
                    val idx = r * uvRowStride + c
                    // NV21: V then U (opposite of YUV420)
                    nv21[uvOffset++] = vBuffer.get(idx).toByte()
                    nv21[uvOffset++] = uBuffer.get(idx).toByte()
                }
            }
        } else {
            val uvRow = ByteArray(uvRowStride)
            for (r in 0 until uvHeight) {
                uBuffer.get(uvRow, 0, uvRowStride)
                vBuffer.get(uvRow, 0, uvRowStride)
                for (c in 0 until width step 2) {
                    nv21[uvOffset++] = uvRow[c] // V
                    nv21[uvOffset++] = uvRow[c + 1] // U
                }
            }
        }

        // NV21 → Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    private fun updateStatus(results: List<MatchResult>) {
        if (results.isEmpty()) {
            binding.tvCameraStatus.text = "检测中…"
            return
        }
        val best = results.maxByOrNull { it.confidence }
        if (best != null) {
            val pct = (best.confidence * 100).toInt()
            binding.tvCameraStatus.text = "匹配成功(${pct}%) x${results.size}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        analysisExecutor.shutdownNow()
    }
}
