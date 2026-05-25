package com.autocenter.app

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.autocenter.app.data.MatchResult
import com.autocenter.app.ui.MainViewModel
import com.autocenter.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    /** MediaProjection 授权回调 */
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 启动捕获服务
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            viewModel.setCapturing(true)

            // 显示悬浮层
            if (Settings.canDrawOverlays(this)) {
                val svc = ScreenCaptureService()
                // 通过 Application 或 service connection 获取 overlayManager
                // 这里通过全局引用
                startOverlay()
            }
        } else {
            Toast.makeText(this, "屏幕捕获授权被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    /** 相册选图回调 */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // 打开配置页添加模板
            val intent = Intent(this, ConfigActivity::class.java).apply {
                putExtra(ConfigActivity.EXTRA_ADD_TEMPLATE_URI, uri.toString())
            }
            startActivity(intent)
        }
    }

    /** 悬浮窗权限回调 */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOverlay()
        } else {
            Toast.makeText(this, "请授权悬浮窗权限以显示瞄准框", Toast.LENGTH_LONG).show()
        }
    }

    private var serviceConnection: ScreenCaptureServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupViews()
        observeViewModel()
        checkInitialPermissions()
    }

    override fun onResume() {
        super.onResume()
        // 更新无障碍状态
        updateAccessibilityStatus()
        // 连接服务
        serviceConnection?.let {
            // 绑定到自定义 Binder，获取 ScreenCaptureService 实例
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun setupViews() {
        // 开启无障碍服务
        binding.btnOpenAccessibility.setOnClickListener {
            viewModel.openAccessibilitySettings()
        }

        // 上传目标图案
        binding.btnUploadTemplate.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        // 滑动校准
        binding.btnCalibrate.setOnClickListener {
            showCalibrateDialog()
        }

        // 测试匹配
        binding.btnTestMatch.setOnClickListener {
            toggleTestMode()
        }

        // 显示瞄准框
        binding.switchReticle.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setReticle(isChecked)
            // 更新 overlay
            ScreenCaptureServiceConnection.serviceInstance?.overlayManager?.setReticleEnabled(isChecked)
        }

        // 启用自动居中
        binding.switchAutoCenter.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCenter(isChecked)
        }

        // 配置按钮
        binding.btnConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }
    }

    private fun observeViewModel() {
        viewModel.statusText.observe(this) { text ->
            binding.tvStatus.text = text
        }

        viewModel.isReticleEnabled.observe(this) { enabled ->
            binding.switchReticle.isChecked = enabled
        }

        viewModel.isAutoCenterEnabled.observe(this) { enabled ->
            binding.switchAutoCenter.isChecked = enabled
        }

        viewModel.isCapturing.observe(this) { capturing ->
            binding.btnTestMatch.text = if (capturing) {
                getString(R.string.btn_test_match)
            } else {
                getString(R.string.btn_test_match)
            }
        }

        viewModel.isTestMode.observe(this) { testMode ->
            binding.btnTestMatch.text = if (testMode) {
                getString(R.string.btn_test_stop)
            } else {
                getString(R.string.btn_test_match)
            }
        }
    }

    private fun checkInitialPermissions() {
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this) && viewModel.isReticleEnabled.value == true) {
            requestOverlayPermission()
        }
    }

    private fun updateAccessibilityStatus() {
        if (!viewModel.isAccessibilityServiceEnabled()) {
            viewModel.updateStatus("未开启无障碍服务")
        }
    }

    // region 屏幕捕获

    private fun startScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpm.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun startOverlay() {
        // 启动/更新 overlay
        val svc = ScreenCaptureServiceConnection.serviceInstance
        if (svc != null && Settings.canDrawOverlays(this)) {
            svc.overlayManager.show()
            svc.overlayManager.setReticleEnabled(viewModel.isReticleEnabled.value ?: true)
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${packageName}")
        )
        overlayPermissionLauncher.launch(intent)
    }

    // endregion

    // region 校准

    private fun showCalibrateDialog() {
        // 检查捕获是否已启动
        val svc = ScreenCaptureServiceConnection.serviceInstance
        if (svc == null) {
            AlertDialog.Builder(this)
                .setTitle("启动屏幕捕获")
                .setMessage("请先开启屏幕捕获以开始校准")
                .setPositiveButton("开启") { _, _ -> startScreenCapture() }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (svc.calibrator.isRunning()) {
            Toast.makeText(this, "校准已在进行中", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_calibrate_title)
            .setMessage(R.string.dialog_calibrate_msg)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                // 启动校准
                val dm = resources.displayMetrics
                svc.calibrator.onStatusChanged = { status ->
                    runOnUiThread { viewModel.updateStatus(status) }
                }
                svc.calibrator.onComplete = { x, y ->
                    runOnUiThread {
                        Toast.makeText(this, "校准完成", Toast.LENGTH_SHORT).show()
                    }
                }
                // 校准需要当前检测结果，由 capture loop 触发
                viewModel.updateStatus("准备校准中…")
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion

    // region 测试模式

    private var isTestMode = false

    private fun toggleTestMode() {
        isTestMode = !isTestMode
        viewModel.setTestMode(isTestMode)

        if (isTestMode) {
            // 确保捕获已启动
            val svc = ScreenCaptureServiceConnection.serviceInstance
            if (svc == null) {
                startScreenCapture()
                viewModel.setTestMode(false) // 恢复，等启动后再试
                return
            }
            svc.overlayManager.setTestMode(true)
            Toast.makeText(this, "测试模式：绿色框表示匹配结果", Toast.LENGTH_SHORT).show()
        } else {
            ScreenCaptureServiceConnection.serviceInstance?.overlayManager?.setTestMode(false)
            Toast.makeText(this, "测试模式关闭", Toast.LENGTH_SHORT).show()
        }
    }

    // endregion
}
