package com.autocenter.app.matching

import android.graphics.Bitmap
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult
import com.autocenter.app.data.RuntimeTemplate
import org.opencv.core.*
import org.opencv.features2d.FLANNBASED
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.Features2d
import org.opencv.imgproc.Imgproc

/**
 * ORB/AKAZE 特征匹配引擎
 * 支持多模板 + 同一模板多实例检测
 */
class MatcherEngine(private val settings: AppSettings) {

    private var activeTemplates: List<RuntimeTemplate> = emptyList()
    private var currentFrameGray: Mat? = null
    private var currentFrameKp: MatOfKeyPoint? = null
    private var currentFrameDesc: Mat? = null

    private val templateManager: TemplateManager? = null // lazy-loaded externally

    /** 更新活跃模板列表 */
    fun updateTemplates(templates: List<RuntimeTemplate>) {
        releaseCurrentFrame()
        activeTemplates.forEach { it.featureMat.release() }
        activeTemplates = templates
    }

    /** 处理一帧，返回所有匹配结果 */
    fun match(
        frameBmp: Bitmap,
        origFrameWidth: Int,
        origFrameHeight: Int,
        downsamplePercent: Int
    ): List<MatchResult> {
        if (activeTemplates.isEmpty() || !frameBmp.isRecycled) {
            // 准备帧灰度图（已降采样）
            val frameMat = Mat()
            org.opencv.android.Utils.bitmapToMat(frameBmp, frameMat)

            val gray = Mat()
            Imgproc.cvtColor(frameMat, gray, Imgproc.COLOR_RGBA2GRAY)
            frameMat.release()

            // 提取帧特征
            val kp = MatOfKeyPoint()
            val desc = Mat()
            val algorithm = settings.algorithm

            try {
                when (algorithm) {
                    "AKAZE" -> {
                        val detector = org.opencv.features2d.AKAZE.create()
                        detector.detectAndCompute(gray, Mat(), kp, desc)
                    }
                    else -> {
                        val detector = org.opencv.features2d.ORB.create(
                            maxFeatures = 500,
                            scoreType = org.opencv.features2d.ORB.HARRIS_SCORE
                        )
                        detector.detectAndCompute(gray, Mat(), kp, desc)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                gray.release()
                kp.release()
                desc.release()
                return emptyList()
            }

            // 缓存帧特征
            releaseCurrentFrame()
            currentFrameGray = gray
            currentFrameKp = kp
            currentFrameDesc = desc
        }

        val fDesc = currentFrameDesc ?: return emptyList()
        val fKp = currentFrameKp ?: return emptyList()

        val allResults = mutableListOf<MatchResult>()
        val scale = downsamplePercent / 100f

        for (template in activeTemplates) {
            val tDesc = template.featureMat
            if (tDesc.rows() < 4) continue

            try {
                // FLANN 匹配
                val matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED)
                val knnMatches = mutableListOf<MatOfDMatch>()
                matcher.knnMatch(tDesc, fDesc, knnMatches, 2)

                // Lowe's ratio test
                val goodMatches = mutableListOf<DMatch>()
                for (matchPair in knnMatches) {
                    val arr = matchPair.toArray()
                    if (arr.size >= 2 && arr[0].distance < 0.75f * arr[1].distance) {
                        goodMatches.add(arr[0])
                    }
                    matchPair.release()
                }
                matcher.clear()

                if (goodMatches.size < 4) continue

                // 空间聚类：找到多个实例
                val minSamples = maxOf(4, (goodMatches.size * 0.15).toInt())
                val clusters = clusterMatches(
                    goodMatches,
                    fKp,
                    minSamples,
                    distanceThreshold = 80.0 / scale  // 聚类距离阈值，降采样后缩放
                )

                for ((cluster, centerX, centerY, minX, minY, maxX, maxY) in clusters) {
                    val confidence = cluster.size.toFloat() / tDesc.rows().toFloat()
                    val sensitivityThreshold = (settings.matchSensitivity / 100f) * 0.6f

                    if (confidence < sensitivityThreshold) continue

                    // 将降采样坐标映射回原始坐标
                    val origLeft = (minX / scale).toFloat()
                    val origTop = (minY / scale).toFloat()
                    val origRight = (maxX / scale).toFloat()
                    val origBottom = (maxY / scale).toFloat()
                    val origCx = (centerX / scale).toFloat()
                    val origCy = (centerY / scale).toFloat()

                    allResults.add(
                        MatchResult(
                            centerX = origCx,
                            centerY = origCy,
                            left = origLeft,
                            top = origTop,
                            width = (origRight - origLeft),
                            height = (origBottom - origTop),
                            confidence = confidence,
                            templateId = template.info.id,
                            templateName = template.info.name,
                            matchCount = cluster.size
                        )
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return allResults
    }

    /** 释放缓存的帧特征 */
    fun releaseCurrentFrame() {
        currentFrameGray?.release()
        currentFrameKp?.release()
        currentFrameDesc?.release()
        currentFrameGray = null
        currentFrameKp = null
        currentFrameDesc = null
    }

    // region 空间聚类

    /**
     * 对匹配点进行空间聚类，找到多个目标实例。
     * 匹配点中属于同一目标的关键点在空间上会聚集在一起。
     */
    private data class Cluster(
        val matches: List<DMatch>,
        val centerX: Double,
        val centerY: Double,
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double
    )

    private fun clusterMatches(
        matches: List<DMatch>,
        frameKp: MatOfKeyPoint,
        minSamples: Int,
        distanceThreshold: Double
    ): List<Cluster> {
        if (matches.isEmpty()) return emptyList()

        val kpArr = frameKp.toArray()
        val points = matches.map { match ->
            val pt = kpArr[match.trainIdx].pt
            match to pt
        }

        // DBSCAN-like 聚类
        val assigned = BooleanArray(points.size)
        val clusters = mutableListOf<MutableList<Int>>()

        for (i in points.indices) {
            if (assigned[i]) continue

            val cluster = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(i)
            assigned[i] = true

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                cluster.add(idx)
                val pt = points[idx].second

                for (j in points.indices) {
                    if (assigned[j]) continue
                    val dist = hypot(pt.x - points[j].second.x, pt.y - points[j].second.y)
                    if (dist < distanceThreshold) {
                        assigned[j] = true
                        queue.add(j)
                    }
                }
            }

            if (cluster.size >= minSamples) {
                clusters.add(cluster)
            }
        }

        // 每个聚类计算中心
        return clusters.map { cluster ->
            val clusterMatches = cluster.map { points[it].first }
            val xs = cluster.map { points[it].second.x }
            val ys = cluster.map { points[it].second.y }
            Cluster(
                matches = clusterMatches,
                centerX = xs.average(),
                centerY = ys.average(),
                minX = xs.min(),
                minY = ys.min(),
                maxX = xs.max(),
                maxY = ys.max()
            )
        }
    }

    private fun hypot(a: Double, b: Double): Double = Math.sqrt(a * a + b * b)

    // endregion

    companion object {
        private const val TAG = "MatcherEngine"
    }
}
