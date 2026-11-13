package cn.com.omnimind.androidgui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import java.security.MessageDigest

/**
 * 屏幕纯视觉 32x32 RGB 像素签名与变化判别辅助。
 *
 * 依据已批准设计：
 * - 同尺寸截图下采样为 32x32 RGB 网格。
 * - 平均每通道绝对差 <= 2/255 判为相同稳定，> 2/255 判为变化。
 * - Bitmap 即用即回收，避免无障碍轮询造成图形内存泄漏。
 */
internal object AndroidGuiPixelSignature {

    const val GRID_SIZE = 32
    private const val CHANNEL_COUNT = 3
    private const val TOTAL_CHANNELS = GRID_SIZE * GRID_SIZE * CHANNEL_COUNT
    private const val MAX_SAME_DIFF_PER_CHANNEL = 2.0

    /**
     * 纯视觉 32x32 RGB 签名快照，包含原图真实宽高与 RGB 网格字节。
     */
    internal data class Signature(
        val width: Int,
        val height: Int,
        val rgbGrid: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Signature) return false
            return width == other.width && height == other.height && rgbGrid.contentEquals(other.rgbGrid)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + rgbGrid.contentHashCode()
            return result
        }
    }

    /**
     * 计算 JPEG 截图的 32x32 RGB 签名（包含原图尺寸）。
     * 若提供 expectedWidth/expectedHeight 且与截图尺寸不符，返回 null（避免不可观测时错误推断）。
     * 采集失败或解码失败返回 null。
     */
    fun computeSignature(
        screenshotJpeg: ByteArray?,
        expectedWidth: Int? = null,
        expectedHeight: Int? = null,
    ): Signature? {
        if (screenshotJpeg == null || screenshotJpeg.isEmpty()) return null
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(screenshotJpeg, 0, screenshotJpeg.size)
        }.getOrNull() ?: return null

        try {
            val width = bitmap.width
            val height = bitmap.height
            if (expectedWidth != null && expectedWidth > 0 && width != expectedWidth) return null
            if (expectedHeight != null && expectedHeight > 0 && height != expectedHeight) return null

            val scaled = if (width == GRID_SIZE && height == GRID_SIZE) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, GRID_SIZE, GRID_SIZE, true)
            }
            try {
                val pixels = IntArray(GRID_SIZE * GRID_SIZE)
                scaled.getPixels(pixels, 0, GRID_SIZE, 0, 0, GRID_SIZE, GRID_SIZE)
                val signature = ByteArray(TOTAL_CHANNELS)
                var offset = 0
                for (pixel in pixels) {
                    signature[offset++] = ((pixel shr 16) and 0xFF).toByte() // R
                    signature[offset++] = ((pixel shr 8) and 0xFF).toByte()  // G
                    signature[offset++] = (pixel and 0xFF).toByte()         // B
                }
                return Signature(width, height, signature)
            } finally {
                if (scaled !== bitmap) {
                    scaled.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 比对两个 32x32 RGB 签名。
     * 若任一为空，返回 null。
     * 若原图尺寸不一致，直接返回 false（同尺寸图要求，避免不同分辨率/旋转拉伸判为相同）。
     * 若平均通道差异 <= 2/255 返回 true（相同）；否则返回 false（发生视觉变化）。
     */
    fun areVisuallyEqual(sig1: Signature?, sig2: Signature?): Boolean? {
        if (sig1 == null || sig2 == null) return null
        if (sig1.width != sig2.width || sig1.height != sig2.height) return false
        if (sig1.rgbGrid.size != TOTAL_CHANNELS || sig2.rgbGrid.size != TOTAL_CHANNELS) return null

        var totalAbsDiff = 0L
        for (i in 0 until TOTAL_CHANNELS) {
            val b1 = sig1.rgbGrid[i].toInt() and 0xFF
            val b2 = sig2.rgbGrid[i].toInt() and 0xFF
            totalAbsDiff += abs(b1 - b2)
        }
        val avgDiff = totalAbsDiff.toDouble() / TOTAL_CHANNELS
        return avgDiff <= MAX_SAME_DIFF_PER_CHANNEL
    }

    /**
     * 计算签名的安全哈希指纹（SHA-256），用于参与 State 视觉身份。
     * 若签名为空返回 null。
     */
    fun visualFingerprint(signature: Signature?): String? {
        if (signature == null) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.rgbGrid)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
