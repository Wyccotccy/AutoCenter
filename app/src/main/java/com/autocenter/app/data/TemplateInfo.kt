package com.autocenter.app.data

import android.graphics.Bitmap
import org.opencv.core.Mat

/**
 * 单个模板的信息，持久化存入 SharedPreferences（精简版）
 * Mat 特征不持久化，启动时从文件重新提取
 */
data class TemplateInfo(
    val id: String,                    // UUID
    val name: String,                  // 用户自定义名称
    val fileName: String,              // filesDir 中的文件名
    val isEnabled: Boolean = true,     // 是否参与匹配
    val isDefault: Boolean = false,    // 是否为默认模板
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /** 新模板默认名称前缀 */
        const val DEFAULT_NAME_PREFIX = "模板"

        /** 默认 max 活跃数 */
        const val DEFAULT_MAX_ACTIVE = 5

        /** 模板数量上限 */
        const val MAX_TEMPLATES = 15
    }
}

/**
 * 运行时模板（含特征数据）
 */
data class RuntimeTemplate(
    val info: TemplateInfo,
    val bitmap: Bitmap,
    val featureMat: Mat,        // ORB/AKAZE descriptors
    val keypoints: List<org.opencv.core.KeyPoint>
)

/**
 * 单次匹配结果
 */
data class MatchResult(
    val centerX: Float,
    val centerY: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val confidence: Float,      // 0-1f
    val templateId: String,
    val templateName: String,
    val matchCount: Int          // 匹配到的特征点数量
)
