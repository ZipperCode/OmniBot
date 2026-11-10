package cn.com.omnimind.androidgui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Display
import cn.com.omnimind.baselib.runlog.Action
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidGuiPerceptionRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private class TestOcrEngine : AndroidScreenOcrEngine, Closeable {
        val closed = AtomicBoolean(false)
        var callCount = 0

        override suspend fun recognize(bitmap: Bitmap): List<ScreenOcrItem> {
            callCount++
            return listOf(
                ScreenOcrItem(text = "测试文本", bounds = Rect(10, 20, 200, 80), confidence = 0.95f)
            )
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun createTestBitmap(width: Int = 1080, height: Int = 2400): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.RED }
        canvas.drawRect(Rect(100, 500, 400, 600), paint)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private fun createPlatformState(
        xml: String,
        screenshotJpeg: ByteArray? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
        frameSequence: Long? = 10L,
    ): Any {
        val clazz = Class.forName("cn.com.omnimind.androidgui.AndroidGuiPlatformState")
        val ctor = clazz.declaredConstructors.first { it.parameterCount >= 6 }
        ctor.isAccessible = true
        return if (ctor.parameterCount == 8) {
            ctor.newInstance(
                "com.test.app",
                "MainActivity",
                1080,
                2400,
                xml,
                screenshotJpeg,
                displayId,
                frameSequence,
            )
        } else {
            ctor.newInstance(
                "com.test.app",
                "MainActivity",
                1080,
                2400,
                xml,
                screenshotJpeg,
            )
        }
    }

    private fun createFakePlatform(
        onObserve: (captureScreenshot: Boolean, displayId: Int) -> Any,
        onDispatch: (action: Action, displayId: Int) -> AndroidGuiActionResult,
    ): Any {
        val platformClass = Class.forName("cn.com.omnimind.androidgui.AndroidGuiPlatform")
        return Proxy.newProxyInstance(
            platformClass.classLoader,
            arrayOf(platformClass),
        ) { _, method, args ->
            when (method.name) {
                "isAccessibilityEnabled" -> true
                "isReady" -> true
                "displaySize" -> 1080 to 2400
                "screenshotExcludesOverlays" -> true
                "observe" -> {
                    val captureScreenshot = args?.getOrNull(0) as? Boolean ?: true
                    val displayId = args?.getOrNull(1) as? Int ?: Display.DEFAULT_DISPLAY
                    onObserve(captureScreenshot, displayId)
                }
                "dispatch" -> {
                    val action = args[0] as Action
                    val displayId = args.getOrNull(1) as? Int ?: Display.DEFAULT_DISPLAY
                    onDispatch(action, displayId)
                }
                "installedApplications" -> emptyMap<String, String>()
                "inputTarget" -> null
                "inputMethodTop" -> null
                "toString" -> "FakePlatform"
                "hashCode" -> 1
                "equals" -> false
                else -> null
            }
        }
    }

    private fun createEnvironment(
        appContext: Context?,
        platform: Any,
        ocrEngine: AndroidScreenOcrEngine? = null,
    ): AndroidGuiEnvironment {
        val ctor = AndroidGuiEnvironment::class.java.declaredConstructors
            .first { it.parameterCount == 3 }
        ctor.isAccessible = true
        return ctor.newInstance(appContext, platform, ocrEngine) as AndroidGuiEnvironment
    }

    @Test
    fun testObservationExtraStructureAndOnDemandOcr() = runBlocking {
        var observedCount = 0
        val testJpeg = createTestBitmap()
        val xmlWithText = """
            <hierarchy>
              <node id="0" window-id="1" bounds="[0,0][1080,2400]" visible-to-user="true">
                <node id="1" text="登录" bounds="[100,500][400,600]" visible-to-user="true" enabled="true" />
              </node>
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                observedCount++
                createPlatformState(xml = xmlWithText, screenshotJpeg = testJpeg, displayId = displayId)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )

        val ocrEngine = TestOcrEngine()
        val env = createEnvironment(context, fakePlatform, ocrEngine)

        // 1. 无 OCR 请求且页面有文本 -> 不执行 OCR
        val obs1 = env.observeWithDiagnostics(captureScreenshot = true, ocr = false)
        assertNotNull(obs1.extra["observation_id"])
        assertEquals(Display.DEFAULT_DISPLAY, obs1.extra["display_id"])
        assertNull(obs1.extra["ocr"])
        assertEquals(0, ocrEngine.callCount)

        // 2. 显式请求 OCR -> 执行 OCR 并返回结果
        val obs2 = env.observeWithDiagnostics(captureScreenshot = true, ocr = true)
        val ocrData = obs2.extra["ocr"] as? Map<*, *>
        assertNotNull(ocrData)
        assertEquals("paddle_ocr_v6", ocrData!!["engine"])
        assertEquals("ready", ocrData["status"])
        assertEquals(false, ocrData["cached"])
        val items = ocrData["items"] as? List<*>
        assertNotNull(items)
        assertTrue(items!!.isNotEmpty())
        assertEquals(1, ocrEngine.callCount)
    }

    @Test
    fun testObservationRegionExtractsVisualRegionAndMapsOcr() = runBlocking {
        val testJpeg = createTestBitmap()
        val xmlWithText = "<hierarchy><node id=\"0\" bounds=\"[0,0][1080,2400]\" visible-to-user=\"true\" text=\"首页\" /></hierarchy>"

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = xmlWithText, screenshotJpeg = testJpeg, displayId = displayId)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )

        val ocrEngine = TestOcrEngine()
        val env = createEnvironment(context, fakePlatform, ocrEngine)

        val region = AndroidGuiObservationRegion(x1 = 100.0, y1 = 200.0, x2 = 500.0, y2 = 600.0)
        val obs = env.observeWithDiagnostics(captureScreenshot = true, region = region)

        val visualRegions = obs.extra["visual_regions"] as? List<*>
        assertNotNull(visualRegions)
        assertEquals(1, visualRegions!!.size)
        val regionEntry = visualRegions[0] as Map<*, *>
        assertEquals(listOf(100.0, 200.0, 500.0, 600.0), regionEntry["bounds"])
        assertNotNull(regionEntry["image_base64"])
        assertTrue((regionEntry["width"] as Int) > 0)
        assertTrue((regionEntry["height"] as Int) > 0)
    }

    @Test
    fun testActRejectsExpiredObservationAndPreventsDispatch() = runBlocking {
        var dispatchCount = 0
        val xml = "<hierarchy><node id=\"0\" bounds=\"[0,0][1080,2400]\" visible-to-user=\"true\"><node id=\"1\" text=\"支付\" bounds=\"[100,500][400,600]\" visible-to-user=\"true\" enabled=\"true\" /></node></hierarchy>"

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = xml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        val obs = env.observeWithDiagnostics()
        val currentObsId = obs.extra["observation_id"] as String

        // 1. 使用过期的观测 ID 调用 act
        val staleResult = env.act(
            action = Action("click", mapOf("target_description" to "支付", "x" to 200, "y" to 230)),
            expectedObservationId = "stale_obs_id_from_earlier_turn",
        )

        assertFalse(staleResult.success)
        assertEquals("action_observation_expired", staleResult.message)
        assertEquals(0, dispatchCount) // 严格未派发

        // 2. 使用当前的观测 ID 调用 act
        val freshResult = env.act(
            action = Action("click", mapOf("target_description" to "支付", "x" to 200, "y" to 230)),
            expectedObservationId = currentObsId,
            awaitStabilization = false,
        )

        assertTrue(freshResult.success)
        assertEquals(1, dispatchCount) // 正常派发
    }

    @Test
    fun testActRejectsTargetOcclusionAndPreventsDispatch() = runBlocking {
        var dispatchCount = 0
        // 实时页面上有一层全屏弹窗 (windowLayer = 50) 覆盖了底层登录按钮 (windowLayer = 0)
        val occludedXml = """
            <hierarchy>
              <node id="btn" text="登录" window-id="1" window-layer="0" bounds="[100,500][400,600]" visible-to-user="true" enabled="true" />
              <node id="dialog" text="系统维护公告" window-id="2" window-layer="50" bounds="[0,0][1080,2400]" visible-to-user="true" enabled="true" />
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = occludedXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        val result = env.act(
            action = Action("click", mapOf("target_description" to "登录", "x" to 250, "y" to 229)),
        )

        assertFalse(result.success)
        assertEquals("action_target_occluded", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun testActRejectsMovedTargetAndPreventsDispatch() = runBlocking {
        var dispatchCount = 0
        // 目标按钮移动到了远离预期坐标的位置
        val movedXml = """
            <hierarchy>
              <node id="btn" text="确定" bounds="[100,1800][400,1900]" visible-to-user="true" enabled="true" />
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = movedXml, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        // 预期点击位置在顶部 (100, 200)，对应像素 (108, 480)，但按钮在 (100, 1800)
        val result = env.act(
            action = Action("click", mapOf("target_description" to "确定", "x" to 100.0, "y" to 200.0)),
        )

        assertFalse(result.success)
        assertEquals("action_target_moved", result.message)
        assertEquals(0, dispatchCount)
    }

    @Test
    fun testActAllowsDispatchWhenClockChangesElsewhere() = runBlocking {
        var dispatchCount = 0
        val xmlWithClockTick = """
            <hierarchy>
              <node id="clock" text="10:30:05" bounds="[800,10][1000,50]" visible-to-user="true" />
              <node id="btn" text="提交" bounds="[100,500][400,600]" visible-to-user="true" enabled="true" />
            </hierarchy>
        """.trimIndent()

        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = xmlWithClockTick, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics()

        // 点击位于目标内部 (250, 550) 对应归一化坐标 x=231.48, y=229.17
        val result = env.act(
            action = Action("click", mapOf("target_description" to "提交", "x" to 231.48, "y" to 229.17)),
            awaitStabilization = false,
        )
        assertTrue(result.success)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun testStaticScreenRedrawReusesOcrCacheViaSha256() = runBlocking {
        val testJpeg = createTestBitmap()
        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = "<hierarchy />", screenshotJpeg = testJpeg, displayId = displayId)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )

        val ocrEngine = TestOcrEngine()
        val env = createEnvironment(context, fakePlatform, ocrEngine)

        // 第一次观测：未命中缓存
        val obs1 = env.observeWithDiagnostics(captureScreenshot = true, ocr = true, displayId = Display.DEFAULT_DISPLAY)
        val ocr1 = obs1.extra["ocr"] as Map<*, *>
        assertEquals("ready", ocr1["status"])
        assertEquals(false, ocr1["cached"])
        assertEquals(1, ocrEngine.callCount)

        // 第二次观测：画面像素未变，SHA-256 匹配，成功命中缓存，不重复调用引擎
        val obs2 = env.observeWithDiagnostics(captureScreenshot = true, ocr = true, displayId = Display.DEFAULT_DISPLAY)
        val ocr2 = obs2.extra["ocr"] as Map<*, *>
        assertEquals("ready", ocr2["status"])
        assertEquals(true, ocr2["cached"])
        assertEquals(1, ocrEngine.callCount)

        // 第三次观测：跨 display 调用，即使像素一致也不复用缓存
        val obs3 = env.observeWithDiagnostics(captureScreenshot = true, ocr = true, displayId = 2)
        val ocr3 = obs3.extra["ocr"] as Map<*, *>
        assertEquals(false, ocr3["cached"])
        assertEquals(2, ocrEngine.callCount)
    }

    @Test
    fun testFullFrameCropDoesNotPrematurelyRecycleBitmap() = runBlocking {
        val testJpeg = createTestBitmap()
        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                createPlatformState(xml = "<hierarchy />", screenshotJpeg = testJpeg, displayId = displayId)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )

        val ocrEngine = TestOcrEngine()
        val env = createEnvironment(context, fakePlatform, ocrEngine)

        // 100% 全屏尺寸的 region
        val fullRegion = AndroidGuiObservationRegion(0.0, 0.0, 1000.0, 1000.0)
        val obs = env.observeWithDiagnostics(captureScreenshot = true, region = fullRegion, ocr = true)

        val visualRegions = obs.extra["visual_regions"] as? List<*>
        assertNotNull(visualRegions)
        assertEquals(1, visualRegions!!.size)
        val ocr = obs.extra["ocr"] as? Map<*, *>
        assertNotNull(ocr)
        assertEquals("ready", ocr!!["status"])
    }

    @Test
    fun testPureCoordinateOcrActionDispatchesWithoutA11yNode() = runBlocking {
        var dispatchCount = 0
        val testJpeg = createTestBitmap()
        val fakePlatform = createFakePlatform(
            onObserve = { _, displayId ->
                // 树中无任何目标节点（如纯 OCR 图像界面）
                createPlatformState(xml = "<hierarchy />", screenshotJpeg = testJpeg, displayId = displayId)
            },
            onDispatch = { _, _ ->
                dispatchCount++
                AndroidGuiActionResult(true, "ok")
            },
        )

        val env = createEnvironment(context, fakePlatform)
        env.observeWithDiagnostics(captureScreenshot = true)

        // Python 端根据 OCR 定位仅派发了相对坐标 (x=200, y=300)，无 node_id / resource_id
        val result = env.act(
            action = Action("click", mapOf("x" to 200.0, "y" to 300.0)),
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun testObservationRegionToRectMapsScreenPixels() {
        val region = AndroidGuiObservationRegion(x1 = 100.0, y1 = 200.0, x2 = 500.0, y2 = 800.0)
        val rect = region.toRect(displayWidth = 1080, displayHeight = 2400)
        assertEquals(108, rect.left)
        assertEquals(480, rect.top)
        assertEquals(540, rect.right)
        assertEquals(1920, rect.bottom)
    }
}
