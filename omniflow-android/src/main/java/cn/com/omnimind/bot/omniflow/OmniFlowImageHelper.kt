package cn.com.omnimind.bot.omniflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ImageDimensions(
    val width: Int,
    val height: Int,
) {
    val maxEdge: Int get() = maxOf(width, height)
    val totalPixels: Long get() = width.toLong() * height.toLong()
}

data class ScalingPlan(
    val originalDimensions: ImageDimensions,
    val targetDimensions: ImageDimensions,
    val scale: Float,
    val needsResize: Boolean,
)

object OmniFlowImageHelper {
    const val MAX_EDGE_DEFAULT = 1600
    const val MAX_PIXELS_DEFAULT = 1_600_000L
    const val JPEG_QUALITY_DEFAULT = 85
    private const val JPEG_PREFIX = "data:image/jpeg;base64,"

    /**
     * 独立 pure scale 计算：
     * - 最长边保守限制在 maxEdge（默认 1600）
     * - 总像素限制在 maxPixels（默认约 1.6MP）
     * - 无上采样：预算内图 scale 严格为 1.0f，绝不放大
     */
    fun calculateScalingPlan(
        width: Int,
        height: Int,
        maxEdge: Int = MAX_EDGE_DEFAULT,
        maxPixels: Long = MAX_PIXELS_DEFAULT,
    ): ScalingPlan {
        if (width <= 0 || height <= 0) {
            val fallback = ImageDimensions(width.coerceAtLeast(0), height.coerceAtLeast(0))
            return ScalingPlan(fallback, fallback, 1.0f, false)
        }
        val original = ImageDimensions(width, height)
        val longest = original.maxEdge
        val total = original.totalPixels

        val edgeScale = if (longest > maxEdge) maxEdge.toFloat() / longest.toFloat() else 1.0f
        val pixelScale = if (total > maxPixels) sqrt(maxPixels.toDouble() / total.toDouble()).toFloat() else 1.0f
        val targetScale = minOf(1.0f, minOf(edgeScale, pixelScale))

        if (targetScale >= 1.0f) {
            return ScalingPlan(original, original, 1.0f, false)
        }

        var targetWidth = (width * targetScale).roundToInt().coerceAtLeast(1)
        var targetHeight = (height * targetScale).roundToInt().coerceAtLeast(1)

        if (targetWidth > maxEdge) {
            targetWidth = maxEdge
            targetHeight = ((height.toDouble() * maxEdge) / width).roundToInt().coerceAtLeast(1)
        }
        if (targetHeight > maxEdge) {
            targetHeight = maxEdge
            targetWidth = ((width.toDouble() * maxEdge) / height).roundToInt().coerceAtLeast(1)
        }
        if (targetWidth.toLong() * targetHeight.toLong() > maxPixels) {
            val adjustment = sqrt(maxPixels.toDouble() / (targetWidth.toLong() * targetHeight.toLong()).toDouble())
            targetWidth = (targetWidth * adjustment).toInt().coerceAtLeast(1)
            targetHeight = (targetHeight * adjustment).toInt().coerceAtLeast(1)
        }

        val target = ImageDimensions(targetWidth, targetHeight)
        return ScalingPlan(original, target, targetScale, true)
    }

    /**
     * 处理 VLM 接收的图片数据：
     * - 用 BitmapFactory.Options(inJustDecodeBounds=true) 解析尺寸；
     * - 预算内图片（包括小字 ROI、已压缩图、所有格式）直接原样返回，不进行重编码；
     * - 超过预算的图片才 decode + scale + encode 为 JPEG 85；
     * - invalid data 无法解析时直接保留原图，不伪装成功。
     */
    fun compressVlmImage(
        value: String,
        maxEdge: Int = MAX_EDGE_DEFAULT,
        maxPixels: Long = MAX_PIXELS_DEFAULT,
        quality: Int = JPEG_QUALITY_DEFAULT,
    ): String {
        if (value.isBlank()) return value

        val separatorIndex = value.indexOf(',')
        val hasDataUriPrefix = separatorIndex >= 0 && value.startsWith("data:")
        val rawPayload = if (hasDataUriPrefix) value.substring(separatorIndex + 1) else value
        val cleanPayload = rawPayload.filterNot(Char::isWhitespace)
        if (cleanPayload.isBlank()) return value

        val bytes = try {
            Base64.decode(cleanPayload, Base64.DEFAULT)
        } catch (_: Throwable) {
            return value
        }
        if (bytes == null || bytes.isEmpty()) return value

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Throwable) {
            return value
        }

        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            return value
        }

        val plan = calculateScalingPlan(width, height, maxEdge = maxEdge, maxPixels = maxPixels)
        if (!plan.needsResize) {
            // 预算内直接原样返回（所有格式均不重编码）
            return value
        }

        // 超预算：decode + scale + encode
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } ?: return value

        val scaledBitmap = try {
            Bitmap.createScaledBitmap(
                bitmap,
                plan.targetDimensions.width,
                plan.targetDimensions.height,
                true,
            )
        } catch (_: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return value
        }

        val outputStream = ByteArrayOutputStream()
        try {
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        } finally {
            if (scaledBitmap != bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }

        val compressedBytes = outputStream.toByteArray()
        val encoded = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
        return "$JPEG_PREFIX$encoded"
    }

    fun scaleBitmap(
        bitmap: Bitmap,
        maxEdge: Int = MAX_EDGE_DEFAULT,
        maxPixels: Long = MAX_PIXELS_DEFAULT,
    ): Bitmap {
        val plan = calculateScalingPlan(bitmap.width, bitmap.height, maxEdge, maxPixels)
        if (!plan.needsResize) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(
            bitmap,
            plan.targetDimensions.width,
            plan.targetDimensions.height,
            true,
        )
    }

    fun compressBitmap(
        bitmap: Bitmap,
        maxEdge: Int = MAX_EDGE_DEFAULT,
        maxPixels: Long = MAX_PIXELS_DEFAULT,
        quality: Int = JPEG_QUALITY_DEFAULT,
    ): ByteArray {
        val plan = calculateScalingPlan(bitmap.width, bitmap.height, maxEdge, maxPixels)
        val target = if (plan.needsResize) {
            Bitmap.createScaledBitmap(
                bitmap,
                plan.targetDimensions.width,
                plan.targetDimensions.height,
                true,
            )
        } else {
            bitmap
        }
        val outputStream = ByteArrayOutputStream()
        try {
            target.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            return outputStream.toByteArray()
        } finally {
            if (target != bitmap && !target.isRecycled) {
                target.recycle()
            }
        }
    }
}
