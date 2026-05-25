package com.autocenter.app.matching

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.RuntimeTemplate
import com.autocenter.app.data.TemplateInfo
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.AKAZE
import org.opencv.features2d.ORB
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 模板图片文件管理 + ORB/AKAZE 特征提取
 */
class TemplateManager(private val context: Context) {

    private val settings = AppSettings(context)
    private val templatesDir: File
        get() = File(context.filesDir, "templates").also { it.mkdirs() }

    // region 文件管理

    /** 保存用户从相册选中的图片为模板 */
    fun saveImageFromUri(uri: Uri, name: String): TemplateInfo {
        val id = UUID.randomUUID().toString()
        val fileName = "$id.png"
        val destFile = File(templatesDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取图片")

        val templates = settings.getTemplates().toMutableList()
        val info = TemplateInfo(
            id = id,
            name = name,
            fileName = fileName,
            isDefault = templates.isEmpty(),
            timestamp = System.currentTimeMillis()
        )
        templates.add(info)
        settings.saveTemplates(templates)
        return info
    }

    /** 删除模板文件及记录 */
    fun deleteTemplate(info: TemplateInfo) {
        val file = File(templatesDir, info.fileName)
        if (file.exists()) file.delete()

        val templates = settings.getTemplates().toMutableList()
        templates.removeAll { it.id == info.id }
        // 如果删掉的是默认模板，把第一个设为默认
        if (templates.isNotEmpty() && templates.none { it.isDefault }) {
            val t = templates.first().copy(isDefault = true)
            templates[0] = t
        }
        settings.saveTemplates(templates)
    }

    /** 更新模板信息（名称、启用状态、默认） */
    fun updateTemplate(info: TemplateInfo) {
        val templates = settings.getTemplates().toMutableList()
        val idx = templates.indexOfFirst { it.id == info.id }
        if (idx >= 0) {
            templates[idx] = info
            settings.saveTemplates(templates)
        }
    }

    /** 获取模板的 Bitmap */
    fun loadBitmap(info: TemplateInfo): Bitmap? {
        val file = File(templatesDir, info.fileName)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    // endregion

    // region 特征提取

    /** 加载所有启用的运行时模板（提取特征） */
    fun loadActiveRuntimeTemplates(): List<RuntimeTemplate> {
        val templates = settings.getTemplates()
        val maxActive = settings.maxActiveTemplates
        val active = templates.filter { it.isEnabled }.take(maxActive)

        return active.mapNotNull { info ->
            val bmp = loadBitmap(info) ?: return@mapNotNull null
            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            val gray = Mat()
            org.opencv.imgproc.Imgproc.cvtColor(mat, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)

            val (kp, desc) = extractFeatures(gray)
            mat.release()

            if (desc == null) {
                gray.release()
                return@mapNotNull null
            }

            RuntimeTemplate(
                info = info,
                bitmap = bmp,
                featureMat = desc,
                keypoints = kp.toList()
            )
        }
    }

    data class FeatureResult(
        val keypoints: MatOfKeyPoint,
        val descriptors: Mat?
    )

    fun extractFeatures(gray: Mat): Pair<MatOfKeyPoint, Mat?> {
        val kp = MatOfKeyPoint()
        val desc = Mat()
        val algorithm = settings.algorithm

        try {
            when (algorithm) {
                "AKAZE" -> {
                    val detector = AKAZE.create()
                    detector.detectAndCompute(gray, Mat(), kp, desc)
                }
                else -> { // ORB (default)
                    val detector = ORB.create(
                        500,
                        1.2f,
                        8,
                        31,
                        0,
                        2,
                        ORB.HARRIS_SCORE,
                        31,
                        20
                    )
                    detector.detectAndCompute(gray, Mat(), kp, desc)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            kp.release()
            desc.release()
            return Pair(kp, null)
        }

        return Pair(kp, desc)
    }

    // endregion

    companion object {
        private const val TAG = "TemplateManager"
    }
}
