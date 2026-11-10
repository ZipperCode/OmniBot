package cn.com.omnimind.bot.omniflow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OmniFlowImageBehaviorTest {

    @Test
    fun `portrait screenshot scales conservatively respecting max edge 1600 and pixel budget`() {
        val plan = OmniFlowImageHelper.calculateScalingPlan(
            width = 1080,
            height = 2400,
            maxEdge = 1600,
            maxPixels = 1_600_000L,
        )

        assertTrue("超过预算必须触发缩放", plan.needsResize)
        assertTrue("缩放比例必须严格小于1", plan.scale < 1.0f)
        assertEquals(1600, plan.targetDimensions.height)
        assertEquals(720, plan.targetDimensions.width)
        assertTrue("最长边不得超过1600", plan.targetDimensions.maxEdge <= 1600)
        assertTrue("总像素不得超过1.6MP", plan.targetDimensions.totalPixels <= 1_600_000L)
    }

    @Test
    fun `landscape screenshot scales conservatively respecting max edge 1600 and pixel budget`() {
        val plan = OmniFlowImageHelper.calculateScalingPlan(
            width = 2400,
            height = 1080,
            maxEdge = 1600,
            maxPixels = 1_600_000L,
        )

        assertTrue("横屏超过预算必须触发缩放", plan.needsResize)
        assertEquals(1600, plan.targetDimensions.width)
        assertEquals(720, plan.targetDimensions.height)
        assertTrue("最长边不得超过1600", plan.targetDimensions.maxEdge <= 1600)
        assertTrue("总像素不得超过1.6MP", plan.targetDimensions.totalPixels <= 1_600_000L)
    }

    @Test
    fun `square screenshot within max edge but exceeding pixel budget scales by pixel budget`() {
        val plan = OmniFlowImageHelper.calculateScalingPlan(
            width = 1500,
            height = 1500,
            maxEdge = 1600,
            maxPixels = 1_600_000L,
        )

        assertTrue("超过总像素预算必须缩放", plan.needsResize)
        assertTrue("总像素严格控制在 1.6MP 以内", plan.targetDimensions.totalPixels <= 1_600_000L)
        assertTrue("最长边在 1600 以内", plan.targetDimensions.maxEdge <= 1600)
        assertEquals(plan.targetDimensions.width, plan.targetDimensions.height)
    }

    @Test
    fun `small text ROI crop preserves original dimensions without downscaling`() {
        val crop400x300 = OmniFlowImageHelper.calculateScalingPlan(
            width = 400,
            height = 300,
            maxEdge = 1600,
            maxPixels = 1_600_000L,
        )
        assertFalse("小字 ROI 图无需缩放", crop400x300.needsResize)
        assertEquals(1.0f, crop400x300.scale, 0.0001f)
        assertEquals(400, crop400x300.targetDimensions.width)
        assertEquals(300, crop400x300.targetDimensions.height)

        val crop200x100 = OmniFlowImageHelper.calculateScalingPlan(
            width = 200,
            height = 100,
            maxEdge = 1600,
            maxPixels = 1_600_000L,
        )
        assertFalse(crop200x100.needsResize)
        assertEquals(1.0f, crop200x100.scale, 0.0001f)
        assertEquals(200, crop200x100.targetDimensions.width)
        assertEquals(100, crop200x100.targetDimensions.height)
    }

    @Test
    fun `no upsampling boundary preserves small and low-resolution images`() {
        val plan = OmniFlowImageHelper.calculateScalingPlan(
            width = 80,
            height = 80,
            maxEdge = 1600,
            maxPixels = 1_600_000L,
        )
        assertFalse("小图严禁上采样", plan.needsResize)
        assertEquals(1.0f, plan.scale, 0.0001f)
        assertEquals(80, plan.targetDimensions.width)
        assertEquals(80, plan.targetDimensions.height)
    }

    @Test
    fun `zero or negative dimensions safely return identity plan without crash`() {
        val zeroPlan = OmniFlowImageHelper.calculateScalingPlan(0, 0)
        assertFalse(zeroPlan.needsResize)
        assertEquals(1.0f, zeroPlan.scale, 0.0001f)

        val negativePlan = OmniFlowImageHelper.calculateScalingPlan(-100, 200)
        assertFalse(negativePlan.needsResize)
        assertEquals(1.0f, negativePlan.scale, 0.0001f)
    }

    @Test
    fun `real bitmap scaling and compression scales oversized bitmap to exact budget`() {
        val sourceBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        try {
            val scaled = OmniFlowImageHelper.scaleBitmap(sourceBitmap)
            assertEquals(720, scaled.width)
            assertEquals(1600, scaled.height)
            if (scaled != sourceBitmap) scaled.recycle()

            val compressedBytes = OmniFlowImageHelper.compressBitmap(sourceBitmap)
            val decoded = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
            assertNotNull(decoded)
            assertEquals(720, decoded.width)
            assertEquals(1600, decoded.height)
            decoded.recycle()
        } finally {
            sourceBitmap.recycle()
        }
    }

    @Test
    fun `real bitmap within budget avoids scaling`() {
        val roiBitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        try {
            val scaled = OmniFlowImageHelper.scaleBitmap(roiBitmap)
            assertEquals(roiBitmap, scaled)
            assertEquals(400, scaled.width)
            assertEquals(300, scaled.height)
        } finally {
            roiBitmap.recycle()
        }
    }

    @Test
    fun `oversized base64 image is decoded, scaled and re-encoded to JPEG 85`() {
        val sourceBitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val baos = ByteArrayOutputStream()
        sourceBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        sourceBitmap.recycle()

        val originalUri = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val compressedUri = OmniFlowImageHelper.compressVlmImage(originalUri)

        assertTrue(compressedUri.startsWith("data:image/jpeg;base64,"))
        val rawBase64 = compressedUri.substringAfter(",")
        val decodedBytes = Base64.decode(rawBase64, Base64.DEFAULT)
        val decodedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        assertNotNull(decodedBitmap)
        assertEquals(720, decodedBitmap.width)
        assertEquals(1600, decodedBitmap.height)
        decodedBitmap.recycle()
    }

    @Test
    fun `image within budget is preserved as-is without re-encoding`() {
        val roiBitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.YELLOW)
        }
        val baos = ByteArrayOutputStream()
        roiBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        roiBitmap.recycle()

        val originalUri = "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val resultUri = OmniFlowImageHelper.compressVlmImage(originalUri)

        assertEquals("预算内图片直接原样保留，跳过重编码", originalUri, resultUri)
    }

    @Test
    fun `invalid base64 or non-image data safely retains original string`() {
        val invalidBase64 = "data:image/jpeg;base64,???ILLEGAL_BASE64!!!"
        assertEquals(invalidBase64, OmniFlowImageHelper.compressVlmImage(invalidBase64))

        val textBytes = "plain text not an image".toByteArray(Charsets.UTF_8)
        val textUri = "data:image/png;base64," + Base64.encodeToString(textBytes, Base64.NO_WRAP)
        assertEquals(textUri, OmniFlowImageHelper.compressVlmImage(textUri))

        assertEquals("", OmniFlowImageHelper.compressVlmImage(""))
        assertEquals("   ", OmniFlowImageHelper.compressVlmImage("   "))
    }
}
