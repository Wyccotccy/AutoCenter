package com.autocenter.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.autocenter.app.ScreenCaptureService
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)

    // 开关
    val isAutoCenterEnabled = MutableLiveData(settings.isAutoCenterEnabled)
    val isReticleEnabled = MutableLiveData(settings.isReticleEnabled)

    // 状态文字
    private val _statusText = MutableLiveData("未检测")
    val statusText: LiveData<String> = _statusText

    // 是否捕获运行中
    private val _isCapturing = MutableLiveData(false)
    val isCapturing: LiveData<Boolean> = _isCapturing

    // 测试模式
    private val _isTestMode = MutableLiveData(false)
    val isTestMode: LiveData<Boolean> = _isTestMode

    init {
        // 加载上次开关状态
        isAutoCenterEnabled.value = settings.isAutoCenterEnabled
        isReticleEnabled.value = settings.isReticleEnabled
    }

    fun setAutoCenter(enabled: Boolean) {
        settings.isAutoCenterEnabled = enabled
        isAutoCenterEnabled.value = enabled
    }

    fun setReticle(enabled: Boolean) {
        settings.isReticleEnabled = enabled
        isReticleEnabled.value = enabled
    }

    /** 开启无障碍服务设置页面 */
    fun openAccessibilitySettings() {
        val ctx = getApplication<Application>()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    }

    /** 检查无障碍服务是否已开启 */
    fun isAccessibilityServiceEnabled(): Boolean {
        val ctx = getApplication<Application>()
        val cn = ComponentName(ctx, com.autocenter.app.MyAccessibilityService::class.java)
        val flat = cn.flattenToString()
        return try {
            if (Settings.Secure.getInt(
                    ctx.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED
                ) != 1) return false
            val services = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            // 用 split+any 替代 contains，避免部分匹配问题
            services.split(":").any { it.trim() == flat || it.trim() == cn.className }
        } catch (e: Exception) {
            false
        }
    }

    /** 获取基础的状态文字 */
    fun evaluateStatus(): String {
        val ctx = getApplication<Application>()
        return when {
            !isAccessibilityServiceEnabled() -> "未开启无障碍服务"
            !android.provider.Settings.canDrawOverlays(ctx) -> "未授权悬浮窗权限"
            isCapturing.value != true -> "请授权屏幕捕获"
            else -> "检测中…"
        }
    }

    /** 请求悬浮窗权限 */
    fun requestOverlayPermission() {
        val ctx = getApplication<Application>()
        if (!Settings.canDrawOverlays(ctx)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        }
    }

    fun updateStatus(text: String) {
        _statusText.postValue(text)
    }

    fun setCapturing(capturing: Boolean) {
        _isCapturing.postValue(capturing)
    }

    fun setTestMode(mode: Boolean) {
        _isTestMode.postValue(mode)
    }
}
