package cn.com.omnimind.androidgui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 屏幕 OCR 算法与真实图形环境回归测试（基于 Robolectric Native Graphics）。
 * 覆盖：尺寸步长对齐、连通域提取、Unclip 膨胀、真实 Rect 坐标映射、CTC 解码、Bitmap 生命周期与身份保护、close() 会话释放。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidScreenOcrRobolectricTest {

    // ==========================================
    // 1. 检测输入尺度对齐回归 (32 步长对齐与长宽比约束)
    // ==========================================

    @Test
    fun `computeDetResizeDimensions keeps aspect ratio and aligns to multiples of 32`() {
        // 典型 1080x2400 全面屏：maxSide 限制在 960，高变为 960，宽对齐 32 步长为 448
        val (detW, detH) = OcrAlgorithms.computeDetResizeDimensions(1080, 2400, maxSide = 960)
        assertEquals(448, detW)
        assertEquals(960, detH)
        assertEquals(0, detW % 32)
        assertEquals(0, detH % 32)

        // 横屏 2400x1080
        val (landscapeW, landscapeH) = OcrAlgorithms.computeDetResizeDimensions(2400, 1080, maxSide = 960)
        assertEquals(960, landscapeW)
        assertEquals(448, landscapeH)
        assertEquals(0, landscapeW % 32)
        assertEquals(0, landscapeH % 32)

        // 小于 maxSide 的图像
        val (smallW, smallH) = OcrAlgorithms.computeDetResizeDimensions(320, 240, maxSide = 960)
        assertEquals(320, smallW)
        assertEquals(256, smallH)
        assertEquals(0, smallW % 32)
        assertEquals(0, smallH % 32)

        // 极小尺寸兜底
        val (tinyW, tinyH) = OcrAlgorithms.computeDetResizeDimensions(10, 15, maxSide = 960)
        assertEquals(32, tinyW)
        assertEquals(32, tinyH)
    }

    @Test
    fun `computeDetResizeDimensions throws for non-positive dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrAlgorithms.computeDetResizeDimensions(0, 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrAlgorithms.computeDetResizeDimensions(100, -5)
        }
    }

    // ==========================================
    // 2. 识别输入尺度计算回归 (高度固定 48，宽度对齐 4 的倍数)
    // ==========================================

    @Test
    fun `computeRecDimensions fixes height at 48 and aligns width to multiple of 4`() {
        val (recW1, recH1) = OcrAlgorithms.computeRecDimensions(200, 50, targetH = 48)
        assertEquals(48, recH1)
        assertEquals(192, recW1)
        assertEquals(0, recW1 % 4)

        val (recW2, recH2) = OcrAlgorithms.computeRecDimensions(103, 50, targetH = 48)
        assertEquals(48, recH2)
        assertEquals(100, recW2)
        assertEquals(0, recW2 % 4)

        val (minW, minH) = OcrAlgorithms.computeRecDimensions(10, 100, targetH = 48, minW = 32)
        assertEquals(48, minH)
        assertEquals(32, minW)

        val (maxW, maxH) = OcrAlgorithms.computeRecDimensions(3000, 50, targetH = 48, maxW = 960)
        assertEquals(48, maxH)
        assertEquals(960, maxW)
    }

    // ==========================================
    // 3. Unclip 膨胀与真实 Rect 坐标回映射算法
    // ==========================================

    @Test
    fun `computeUnclippedBox correctly expands according to Vatti formula and clamps bounds`() {
        val mapW = 100
        val mapH = 100
        val unclipped = OcrAlgorithms.computeUnclippedBox(
            minX = 20,
            minY = 30,
            maxX = 40,
            maxY = 40,
            mapW = mapW,
            mapH = mapH,
            unclipRatio = 1.6f
        )
        assertEquals(14, unclipped[0])
        assertEquals(24, unclipped[1])
        assertEquals(46, unclipped[2])
        assertEquals(46, unclipped[3])

        // 边界 Clamp
        val borderUnclipped = OcrAlgorithms.computeUnclippedBox(
            minX = 1,
            minY = 1,
            maxX = 98,
            maxY = 98,
            mapW = mapW,
            mapH = mapH,
            unclipRatio = 2.0f
        )
        assertEquals(0, borderUnclipped[0])
        assertEquals(0, borderUnclipped[1])
        assertEquals(99, borderUnclipped[2])
        assertEquals(99, borderUnclipped[3])
    }

    @Test
    fun `mapDetBoxToOriginal accurately maps coordinates back to full screen resolution in real Rect`() {
        val detW = 448
        val detH = 960
        val origW = 1080
        val origH = 2400

        val scaleX = origW.toDouble() / detW.toDouble()
        val scaleY = origH.toDouble() / detH.toDouble()

        val fullBox = intArrayOf(0, 0, detW - 1, detH - 1)
        val fullMapped = OcrAlgorithms.mapDetBoxToOriginal(fullBox, scaleX, scaleY, origW, origH)
        assertEquals(0, fullMapped.left)
        assertEquals(0, fullMapped.top)
        assertEquals(origW, fullMapped.right)
        assertEquals(origH, fullMapped.bottom)

        val centerBox = intArrayOf(100, 200, 200, 400)
        val centerMapped = OcrAlgorithms.mapDetBoxToOriginal(centerBox, scaleX, scaleY, origW, origH)
        assertTrue(centerMapped.left in 240..242)
        assertTrue(centerMapped.top in 500..501)
        assertTrue(centerMapped.right > centerMapped.left)
        assertTrue(centerMapped.bottom > centerMapped.top)
    }

    // ==========================================
    // 4. 连通域提取与后处理过滤回归
    // ==========================================

    @Test
    fun `extractConnectedComponents detects valid text box and filters noise and low scores`() {
        val mapW = 50
        val mapH = 50
        val probMap = FloatArray(mapW * mapH)

        // 高置信度区域
        for (y in 10..20) {
            for (x in 10..30) {
                probMap[y * mapW + x] = 0.85f
            }
        }

        // 单点噪点
        probMap[40 * mapW + 40] = 0.70f

        // 低平均分连通域
        for (y in 30..35) {
            for (x in 30..35) {
                probMap[y * mapW + x] = 0.35f
            }
        }

        val boxes = OcrAlgorithms.extractConnectedComponents(
            probMap = probMap,
            mapW = mapW,
            mapH = mapH,
            origW = 500,
            origH = 500,
            thresh = 0.3f,
            boxThresh = 0.5f,
            unclipRatio = 1.6f,
            minSize = 3
        )

        assertEquals(1, boxes.size)
        val box = boxes.first()
        assertTrue(box.left < 100)
        assertTrue(box.top < 100)
        assertTrue(box.right > 300)
        assertTrue(box.bottom > 200)
    }

    // ==========================================
    // 5. CTC 解码与置信度计算回归
    // ==========================================

    @Test
    fun `decodeCtc collapses repeated characters unless separated by blank and computes average confidence`() {
        val dict = listOf("", "A", "B", "C", " ")
        val numClasses = dict.size

        // 重复字符折叠
        val stepA = listOf(1 to 0.90f, 1 to 0.95f, 1 to 0.92f)
        val (textA, confA) = OcrAlgorithms.decodeCtc(createFloatBuffer(stepA, numClasses), numClasses, dict)
        assertEquals("A", textA)
        assertEquals(0.90f, confA, 0.001f)

        // Blank 隔开相同字符不折叠
        val stepB = listOf(1 to 0.90f, 0 to 0.99f, 1 to 0.94f)
        val (textB, confB) = OcrAlgorithms.decodeCtc(createFloatBuffer(stepB, numClasses), numClasses, dict)
        assertEquals("AA", textB)
        assertEquals((0.90f + 0.94f) / 2.0f, confB, 0.001f)

        // 多字符与空格
        val stepC = listOf(
            0 to 0.99f,
            1 to 0.90f,
            1 to 0.92f,
            0 to 0.85f,
            2 to 0.88f,
            0 to 0.95f,
            4 to 0.80f,
            3 to 0.94f,
            3 to 0.91f
        )
        val (textC, confC) = OcrAlgorithms.decodeCtc(createFloatBuffer(stepC, numClasses), numClasses, dict)
        assertEquals("AB C", textC)
        val expectedConf = (0.90f + 0.88f + 0.80f + 0.94f) / 4.0f
        assertEquals(expectedConf, confC, 0.001f)

        // 全 Blank
        val stepD = listOf(0 to 0.99f, 0 to 0.98f, 0 to 0.95f)
        val (textD, confD) = OcrAlgorithms.decodeCtc(createFloatBuffer(stepD, numClasses), numClasses, dict)
        assertEquals("", textD)
        assertEquals(0.0f, confD, 0.0001f)

        // 空缓冲区
        val emptyBuffer = ByteBuffer.allocateDirect(0).asFloatBuffer()
        val (textE, confE) = OcrAlgorithms.decodeCtc(emptyBuffer, numClasses, dict)
        assertEquals("", textE)
        assertEquals(0.0f, confE, 0.0001f)
    }

    // ==========================================
    // 6. 阅读序排序回归
    // ==========================================

    @Test
    fun `sortBoxesReadingOrder sorts multiline and same-line elements in reading order`() {
        val b1 = Rect(100, 10, 200, 40)
        val b2 = Rect(10, 12, 90, 42)
        val b3 = Rect(10, 80, 150, 110)

        val sorted = OcrAlgorithms.sortBoxesReadingOrder(listOf(b1, b3, b2))
        assertEquals(3, sorted.size)
        assertEquals(b2, sorted[0])
        assertEquals(b1, sorted[1])
        assertEquals(b3, sorted[2])
    }

    // ==========================================
    // 7. close 资源释放与调用失败断言
    // ==========================================

    @Test
    fun `calling recognize after close throws IllegalStateException`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val ocr = AndroidScreenOcr(context)
            ocr.close()

            val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        ocr.recognize(bitmap)
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun createFloatBuffer(
        steps: List<Pair<Int, Float>>,
        numClasses: Int
    ): java.nio.FloatBuffer {
        val totalFloats = steps.size * numClasses
        val buffer = ByteBuffer.allocateDirect(totalFloats * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        for ((targetClass, prob) in steps) {
            val otherProb = (1.0f - prob) / (numClasses - 1).coerceAtLeast(1)
            for (c in 0 until numClasses) {
                if (c == targetClass) {
                    buffer.put(prob)
                } else {
                    buffer.put(otherProb)
                }
            }
        }
        buffer.rewind()
        return buffer
    }
}
