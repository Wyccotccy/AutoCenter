package com.autocenter.app.matching

import android.graphics.Bitmap
import com.autocenter.app.data.AppSettings
import com.autocenter.app.data.MatchResult
import com.autocenter.app.data.RuntimeTemplate
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.imgproc.Imgproc

/**
 * ORB/AKAZE 特征匹配引擎
 * 支持多模板 + 同一模板多实例检测（空间聚类）
 *
 * 每帧独立处理，不做缓存（10fps 下性能足够）
 */
class MatcherEngine(private val settings: AppSettings) {

    private var activeTemplates: List<RuntimeTemplate> = emptyList()

    /** 更新活跃模板列表（释放旧模板特征 Mat） */
    fun updateTemplates(templates: List<RuntimeTemplate>) {
        activeTemplates.forEach { it.featureMat.release() }
        activeTemplates = templates
    }

    /** 处理一帧降采样后的 Bitmap，返回所有模板的匹配结果 */
    fun match(
        frameBmp: Bitmap,
        origFrameWidth: Int,
        origFrameHeight: Int,
        downsamplePercent: Int
    ): List<MatchResult> {
        if (activeTemplates.isEmpty() || frameBmp.isRecycled) return emptyList()

        // 1. Bitmap → Mat → 灰度
        val frameMat = Mat()
        val gray = Mat()
        try {
            Utils.bitmapToMat(frameBmp, frameMat)
            Imgproc.cvtColor(frameMat, gray, Imgproc.COLOR_RGBA2GRAY)
        } catch (e: Throwable) {
            frameMat.release()
            gray.release()
            return emptyList()
        } finally {
            frameMat.release()
        }

        // 2. 提取帧特征
        val frameKp = MatOfKeyPoint()
        val frameDesc = Mat()

        try {
            val algorithm = settings.algorithm
            when (algorithm) {
                "AKAZE" -> {
                    val detector = org.opencv.features2d.AKAZE.create()
                    detector.detectAndCompute(gray, Mat(), frameKp, frameDesc)
                }
                else -> { // ORB (default)
                    val detector = org.opencv.features2d.ORB.create(
                        500,
                        1.2f,
                        8,
                        31,
                        0,
                        2,
                        org.opencv.features2d.ORB.HARRIS_SCORE,
                        31,
                        20
                    )
                    detector.detectAndCompute(gray, Mat(), frameKp, frameDesc)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            gray.release()
            frameKp.release()
            frameDesc.release()
            return emptyList()
        } finally {
            gray.release()
        }

        if (frameDesc.rows() < 4) {
            frameKp.release()
            frameDesc.release()
            return emptyList()
        }

        // 3. 逐模板匹配
        val allResults = mutableListOf<MatchResult>()
        val scale = downsamplePercent / 100f
        val kpArr = frameKp.toArray()
        val sensitivityThreshold = (settings.matchSensitivity / 100f) * 0.6f

        for (template in activeTemplates) {
            val tDesc = template.featureMat
            if (tDesc.rows() < 4) continue

            try {
                // FLANN knnMatch (k=2 for Lowe's test)
                val matcher = DescriptorMatcher.create(DescriptorMatcher.FLANNBASED)
                val knnMatches = mutableListOf<MatOfDMatch>()
                matcher.knnMatch(tDesc, frameDesc, knnMatches, 2)

                // Lowe's ratio test: 最佳匹配明显优于次佳
                val goodMatches = mutableListOf<DMatch>()
                for (pair in knnMatches) {
                    val arr = pair.toArray()
                    if (arr.size >= 2 && arr[0].distance < 0.75f * arr[1].distance) {
                        goodMatches.add(arr[0])
                    }
                    pair.release()
                }
                matcher.clear()

                if (goodMatches.size < 4) continue

                // 空间聚类：同一目标的匹配点会聚集在一起
                val minCluster = maxOf(4, (goodMatches.size * 0.15).toInt())
                val clusters = clusterMatches(
                    goodMatches, kpArr,
                    minSamples = minCluster,
                    distanceThreshold = 80.0 / scale
                )

                // 每个聚类 = 一个目标实例
                for ((centerX, centerY, minX, minY, maxX, maxY, count) in clusters) {
                    val confidence = count.toFloat() / tDesc.rows().toFloat()
                    if (confidence < sensitivityThreshold) continue

                    val origLeft = (minX / scale).toFloat()
                    val origTop = (minY / scale).toFloat()
                    val origRight = (maxX / scale).toFloat()
                    val origBottom = (maxY / scale).toFloat()

                    allResults.add(
                        MatchResult(
                            centerX = (centerX / scale).toFloat(),
                            centerY = (centerY / scale).toFloat(),
                            left = origLeft,
                            top = origTop,
                            width = (origRight - origLeft).coerceAtLeast(1f),
                            height = (origBottom - origTop).coerceAtLeast(1f),
                            confidence = confidence.coerceIn(0f, 1f),
                            templateId = template.info.id,
                            templateName = template.info.name,
                            matchCount = count
                        )
                    )
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        // 释放帧特征
        frameKp.release()
        frameDesc.release()

        return allResults
    }

    // region 空间聚类 (DBSCAN-like)

    private data class ClusterInfo(
        val centerX: Double,
        val centerY: Double,
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
        val pointCount: Int
    )

    /**
     * 将特征匹配点按空间距离聚类。
     * 每个聚类代表一个目标实例。
     */
    private fun clusterMatches(
        matches: List<DMatch>,
        frameKp: Array<org.opencv.core.KeyPoint>,
        minSamples: Int,
        distanceThreshold: Double
    ): List<ClusterInfo> {
        if (matches.isEmpty()) return emptyList()

        // 提取帧侧坐标
        val pts = matches.map { match ->
            val pt = frameKp[match.trainIdx].pt
            pt.x to pt.y
        }

        // DBSCAN 密度聚类
        val n = pts.size
        val assigned = BooleanArray(n)
        val clusters = mutableListOf<MutableList<Int>>()

        for (i in 0 until n) {
            if (assigned[i]) continue

            val cluster = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.addLast(i)
            assigned[i] = true

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                cluster.add(idx)
                val (xi, yi) = pts[idx]

                for (j in 0 until n) {
                    if (assigned[j]) continue
                    val dx = xi - pts[j].first
                    val dy = yi - pts[j].second
                    if (Math.sqrt(dx * dx + dy * dy) < distanceThreshold) {
                        assigned[j] = true
                        queue.addLast(j)
                    }
                }
            }

            if (cluster.size >= minSamples) {
                clusters.add(cluster)
            }
        }

        return clusters.map { cluster ->
            val xs = cluster.map { pts[it].first }
            val ys = cluster.map { pts[it].second }
            ClusterInfo(
                centerX = xs.average(),
                centerY = ys.average(),
                minX = xs.min(),
                minY = ys.min(),
                maxX = xs.max(),
                maxY = ys.max(),
                pointCount = cluster.size
            )
        }
    }

    // endregion
}
