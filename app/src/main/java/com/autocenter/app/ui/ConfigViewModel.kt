package com.autocenter.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.TemplateInfo
import com.autocenter.app.matching.TemplateManager

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    val settings = AppSettings(application)
    val templateManager = TemplateManager(application)

    // 模板列表
    private val _templates = MutableLiveData<List<TemplateInfo>>()
    val templates: LiveData<List<TemplateInfo>> = _templates

    // 参数
    val matchSensitivity = MutableLiveData(settings.matchSensitivity)
    val downsamplePercent = MutableLiveData(settings.downsamplePercent)
    val fps = MutableLiveData(settings.fps)
    val maxActive = MutableLiveData(settings.maxActiveTemplates)
    val algorithm = MutableLiveData(settings.algorithm)

    // 校准值
    val calibX = MutableLiveData(settings.calibRatioX)
    val calibY = MutableLiveData(settings.calibRatioY)

    init {
        refreshTemplates()
    }

    fun refreshTemplates() {
        _templates.value = settings.getTemplates()
    }

    /** 添加模板 */
    fun addTemplate(uri: Uri): Boolean {
        val current = settings.getTemplates()
        if (current.size >= TemplateInfo.MAX_TEMPLATES) {
            return false // 已达上限
        }
        val count = current.size + 1
        val info = templateManager.saveImageFromUri(uri, "模板$count")
        refreshTemplates()
        return true
    }

    /** 删除模板 */
    fun deleteTemplate(info: TemplateInfo) {
        templateManager.deleteTemplate(info)
        refreshTemplates()
    }

    /** 更新模板 */
    fun updateTemplate(info: TemplateInfo) {
        templateManager.updateTemplate(info)
        refreshTemplates()
    }

    /** 切换启用状态 */
    fun toggleEnabled(info: TemplateInfo) {
        updateTemplate(info.copy(isEnabled = !info.isEnabled))
    }

    /** 设为默认 */
    fun setAsDefault(info: TemplateInfo) {
        val all = settings.getTemplates().map { t ->
            if (t.id == info.id) t.copy(isDefault = true)
            else t.copy(isDefault = false)
        }
        settings.saveTemplates(all)
        refreshTemplates()
    }

    /** 更新名称 */
    fun renameTemplate(info: TemplateInfo, newName: String) {
        updateTemplate(info.copy(name = newName))
    }

    // region 参数保存

    fun saveSensitivity(v: Int) {
        settings.matchSensitivity = v
        matchSensitivity.value = v
    }

    fun saveDownsample(v: Int) {
        settings.downsamplePercent = v
        downsamplePercent.value = v
    }

    fun saveFps(v: Int) {
        settings.fps = v
        fps.value = v
    }

    fun saveMaxActive(v: Int) {
        settings.maxActiveTemplates = v
        maxActive.value = v
    }

    fun saveAlgorithm(v: String) {
        settings.algorithm = v
        algorithm.value = v
    }

    // endregion

    // region 校准

    fun resetCalibration() {
        settings.calibRatioX = 1.0f
        settings.calibRatioY = 1.0f
        calibX.value = 1.0f
        calibY.value = 1.0f
    }

    fun updateCalibrationDisplay() {
        calibX.value = settings.calibRatioX
        calibY.value = settings.calibRatioY
    }

    // endregion
}
