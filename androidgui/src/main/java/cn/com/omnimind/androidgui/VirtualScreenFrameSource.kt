package cn.com.omnimind.androidgui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Frame source backed by [ImageReader].
 *
 * Adapted from IzukiJS (GPL-3.0), commit eb995dff24849fed215a75a136d4b2d076f69c69.
 * Provides a Surface for VirtualDisplay rendering and captures frames without accessibility screenshot overhead.
 */
class VirtualScreenFrameSource(
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private val thread = HandlerThread("omni-vd-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = ReentrantLock()

    @Volatile
    private var reader: ImageReader? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var closed = false

    private var latestFrameSequence: Long = 0L

    private val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val drawRect = Rect()

    val surface: Surface
        get() = checkNotNull(reader?.surface) { "Frame source not started" }

    fun start() {
        if (reader != null || closed) return
        val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = created
        created.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
                if (bitmap != null) {
                    if (closed) {
                        bitmap.recycle()
                        return@setOnImageAvailableListener
                    }
                    lock.withLock {
                        if (closed) {
                            bitmap.recycle()
                            return@withLock
                        }
                        latestBitmap?.recycle()
                        latestBitmap = bitmap
                        latestFrameSequence++
                    }
                }
            } finally {
                runCatching { image.close() }
            }
        }, handler)
    }

    fun captureScreenshotJpeg(quality: Int = 85): ByteArray? = lock.withLock {
        val bitmap = latestBitmap ?: return null
        if (bitmap.isRecycled) return null
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
    }

    /**
     * Returns the current monotonically incrementing frame sequence number inside [lock].
     */
    fun currentFrameSequence(): Long = lock.withLock {
        latestFrameSequence
    }

    /**
     * Draws the latest captured virtual frame into [canvas] within [destination].
     *
     * Guarantees:
     * - Executed strictly inside [lock], preventing concurrent bitmap recycling while drawing.
     * - Bitmap ownership remains internal to [VirtualScreenFrameSource]; Bitmap is never exposed to UI callers.
     * - Maintains aspect ratio with centered letterboxing.
     * - Returns true if a frame was successfully drawn; false if no valid frame is available or recycled.
     */
    fun drawLatestFrame(
        canvas: Canvas,
        destination: Rect,
        afterSequence: Long = -1L,
        outDrawRect: Rect? = null,
    ): Boolean = lock.withLock {
        outDrawRect?.setEmpty()
        if (closed) return false
        if (latestFrameSequence <= afterSequence) return false
        val bitmap = latestBitmap ?: return false
        if (bitmap.isRecycled) return false
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height
        if (srcWidth <= 0 || srcHeight <= 0 || destination.width() <= 0 || destination.height() <= 0) return false

        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
        val dstAspect = destination.width().toFloat() / destination.height().toFloat()

        if (srcAspect > dstAspect) {
            val drawHeight = (destination.width() / srcAspect).toInt()
            val top = destination.top + (destination.height() - drawHeight) / 2
            drawRect.set(destination.left, top, destination.right, top + drawHeight)
        } else {
            val drawWidth = (destination.height() * srcAspect).toInt()
            val left = destination.left + (destination.width() - drawWidth) / 2
            drawRect.set(left, destination.top, left + drawWidth, destination.bottom)
        }

        srcRect.set(0, 0, srcWidth, srcHeight)
        canvas.drawBitmap(bitmap, srcRect, drawRect, drawPaint)
        outDrawRect?.set(drawRect)
        true
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            latestBitmap?.recycle()
            latestBitmap = null
        }
        reader?.close()
        reader = null
        thread.quitSafely()
    }
}
