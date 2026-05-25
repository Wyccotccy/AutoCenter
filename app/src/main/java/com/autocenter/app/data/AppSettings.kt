package com.autocenter.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有持久化设置的统一管理
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("autocenter_prefs", Context.MODE_PRIVATE)

    // region 开关状态
    var isAutoCenterEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CENTER, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_CENTER, v).apply()

    var isReticleEnabled: Boolean
        get() = prefs.getBoolean(KEY_RETICLE, true)
        set(v) = prefs.edit().putBoolean(KEY_RETICLE, v).apply()
    // endregion

    // region 匹配参数
    var matchSensitivity: Int
        get() = prefs.getInt(KEY_SENSITIVITY, 75)
        set(v) = prefs.edit().putInt(KEY_SENSITIVITY, v.coerceIn(0, 100)).apply()

    var downsamplePercent: Int
        get() = prefs.getInt(KEY_DOWNSAMPLE, 50)
        set(v) = prefs.edit().putInt(KEY_DOWNSAMPLE, v.coerceIn(25, 100)).apply()

    var fps: Int
        get() = prefs.getInt(KEY_FPS, 10)
        set(v) = prefs.edit().putInt(KEY_FPS, v.coerceIn(5, 30)).apply()

    var maxActiveTemplates: Int
        get() = prefs.getInt(KEY_MAX_ACTIVE, 5)
        set(v) = prefs.edit().putInt(KEY_MAX_ACTIVE, v.coerceIn(1, 15)).apply()

    var algorithm: String
        get() = prefs.getString(KEY_ALGORITHM, "ORB") ?: "ORB"
        set(v) = prefs.edit().putString(KEY_ALGORITHM, v).apply()
    // endregion

    // region 校准参数
    var calibRatioX: Float
        get() = prefs.getFloat(KEY_CALIB_X, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_CALIB_X, v).apply()

    var calibRatioY: Float
        get() = prefs.getFloat(KEY_CALIB_Y, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_CALIB_Y, v).apply()
    // endregion

    // region 模板列表
    fun getTemplates(): List<TemplateInfo> {
        val json = prefs.getString(KEY_TEMPLATES, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            TemplateInfo(
                id = obj.getString("id"),
                name = obj.getString("name"),
                fileName = obj.getString("fileName"),
                isEnabled = obj.optBoolean("isEnabled", true),
                isDefault = obj.optBoolean("isDefault", false),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }

    fun saveTemplates(templates: List<TemplateInfo>) {
        val arr = JSONArray()
        templates.forEach { t ->
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("name", t.name)
            obj.put("fileName", t.fileName)
            obj.put("isEnabled", t.isEnabled)
            obj.put("isDefault", t.isDefault)
            obj.put("timestamp", t.timestamp)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_TEMPLATES, arr.toString()).apply()
    }
    // endregion

    companion object {
        private const val KEY_AUTO_CENTER = "auto_center"
        private const val KEY_RETICLE = "reticle"
        private const val KEY_SENSITIVITY = "match_sensitivity"
        private const val KEY_DOWNSAMPLE = "downsample"
        private const val KEY_FPS = "fps"
        private const val KEY_MAX_ACTIVE = "max_active"
        private const val KEY_ALGORITHM = "algorithm"
        private const val KEY_CALIB_X = "calib_x"
        private const val KEY_CALIB_Y = "calib_y"
        private const val KEY_TEMPLATES = "templates"
    }
}
