package cn.com.omnimind.androidgui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.view.Display
import cn.com.omnimind.baselib.runlog.Action
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE, shadows = [AndroidGuiPerceptionRobolectricTest.ShadowA11yServiceForGesture::class])
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

    private fun createTestBitmap(
        width: Int = 1080,
        height: Int = 2400,
        color: Int = Color.RED,
        fillScreen: Boolean = false,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (fillScreen) {
            canvas.drawColor(color)
        } else {
            val paint = Paint().apply { this.color = color }
            canvas.drawRect(Rect(100, 500, 400, 600), paint)
        }
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
        val ctor = clazz.declaredConstructors.first { it.parameterCount == 8 }
        ctor.isAccessible = true
        return ctor.newInstance(
            "com.test.app",
            "MainActivity",
            1080,
            2400,
            xml,
            screenshotJpeg,
            displayId,
            frameSequence,
        )
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

    private class RecordingDisplayService : IPrivilegedDisplayService {
        var injectTapCalls = 0
        var injectSwipeCalls = 0
        var lastSwipeDurationMs = -1L
        var lastDisplayId = -1
        override fun asBinder(): android.os.IBinder = error("not needed")
        override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, surface: android.view.Surface, flags: Int): Int = 0
        override fun releaseVirtualDisplay(displayId: Int) = Unit
        override fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
            injectTapCalls++
            lastDisplayId = displayId
            return true
        }
        override fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            injectSwipeCalls++
            lastSwipeDurationMs = durationMs
            lastDisplayId = displayId
            return true
        }
        override fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override fun destroy() = Unit
        override fun getServiceUid(): Int = 2000
        override fun getRootTasks(displayId: Int): String = "[]"
        override fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String = "{}"
    }

    @Implements(AccessibilityService::class)
    class ShadowA11yServiceForGesture {
        companion object {
            var dispatchGestureCalls = 0
            var acceptGesture = false
            var lastCallback: AccessibilityService.GestureResultCallback? = null

            fun reset() {
                dispatchGestureCalls = 0
                acceptGesture = false
                lastCallback = null
            }
        }

        @Implementation
        fun dispatchGesture(
            gesture: GestureDescription,
            callback: AccessibilityService.GestureResultCallback?,
            handler: Handler?,
        ): Boolean {
            dispatchGestureCalls++
            lastCallback = callback
            return acceptGesture
        }
    }

    private fun installTestAssistsService(acceptGesture: Boolean = false) {
        ShadowA11yServiceForGesture.reset()
        ShadowA11yServiceForGesture.acceptGesture = acceptGesture

        val assistsClass = Class.forName("cn.com.omnimind.accessibility.service.AssistsService")
        val serviceInstance = assistsClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        val instanceField = runCatching {
            assistsClass.getDeclaredField("instance")
        }.getOrElse {
            val companionClass = Class.forName("cn.com.omnimind.accessibility.service.AssistsService\$Companion")
            companionClass.getDeclaredField("instance")
        }.apply { isAccessible = true }

        val connectedField = assistsClass.getDeclaredField("connected").apply { isAccessible = true }
        connectedField.set(serviceInstance, true)

        if (java.lang.reflect.Modifier.isStatic(instanceField.modifiers)) {
            instanceField.set(null, serviceInstance)
        } else {
            val companionField = assistsClass.getDeclaredField("Companion").apply { isAccessible = true }
            val companionObj = companionField.get(null)
            instanceField.set(companionObj, serviceInstance)
        }
    }

    private fun uninstallTestAssistsService() {
        ShadowA11yServiceForGesture.reset()
        val assistsClass = Class.forName("cn.com.omnimind.accessibility.service.AssistsService")
        val instanceField = runCatching {
            assistsClass.getDeclaredField("instance")
        }.getOrElse {
            val companionClass = Class.forName("cn.com.omnimind.accessibility.service.AssistsService\$Companion")
            companionClass.getDeclaredField("instance")
        }.apply { isAccessible = true }

        if (java.lang.reflect.Modifier.isStatic(instanceField.modifiers)) {
            instanceField.set(null, null)
        } else {
            val companionField = assistsClass.getDeclaredField("Companion").apply { isAccessible = true }
            val companionObj = companionField.get(null)
            instanceField.set(companionObj, null)
        }
    }

    private fun createAccessibilityPlatform(ctx: Context): Any {
        val clazz = Class.forName("cn.com.omnimind.androidgui.AccessibilityAndroidGuiPlatform")
        val ctor = clazz.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        return ctor.newInstance(ctx)
    }

    private suspend fun invokeAccessibilityDispatch(
        platform: Any,
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiActionResult = suspendCancellableCoroutine { continuation ->
        val method = platform.javaClass.methods.first { it.name == "dispatch" && it.parameterCount == 3 }.apply {
            isAccessible = true
        }
        val result = try {
            method.invoke(platform, action, displayId, continuation)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.targetException ?: e.cause ?: e
            if (cause is kotlinx.coroutines.CancellationException) {
                continuation.cancel(cause)
            }
            throw cause
        }
        if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED && continuation.isActive) {
            @Suppress("UNCHECKED_CAST")
            continuation.resume(result as AndroidGuiActionResult)
        }
    }
    @Test
    fun testLongPressRejectedOnVirtualDisplayFallsBackToInjectSwipePreservingDuration() = runBlocking {
        installTestAssistsService(acceptGesture = false)
        val recordingService = RecordingDisplayService()
        DeviceDisplayManager.privilegedService = recordingService
        try {
            val platform = createAccessibilityPlatform(context)
            val result = invokeAccessibilityDispatch(
                platform = platform,
                action = Action(
                    tool = "long_press",
                    args = mapOf("x" to 150.0, "y" to 300.0, "duration_ms" to 700L),
                ),
                displayId = 2,
            )

            assertTrue(result.success)
            assertEquals("gesture_dispatched_via_root", result.message)
            assertEquals(1, ShadowA11yServiceForGesture.dispatchGestureCalls)
            assertEquals(0, recordingService.injectTapCalls)
            assertEquals(1, recordingService.injectSwipeCalls)
            assertEquals(700L, recordingService.lastSwipeDurationMs)
            assertEquals(2, recordingService.lastDisplayId)
        } finally {
            DeviceDisplayManager.resetForTesting()
            uninstallTestAssistsService()
        }
    }

    @Test
    fun testClickRejectedOnVirtualDisplayFallsBackToInjectTap() = runBlocking {
        installTestAssistsService(acceptGesture = false)
        val recordingService = RecordingDisplayService()
        DeviceDisplayManager.privilegedService = recordingService
        try {
            val platform = createAccessibilityPlatform(context)
            val result = invokeAccessibilityDispatch(
                platform = platform,
                action = Action(
                    tool = "click",
                    args = mapOf("x" to 150.0, "y" to 300.0),
                ),
                displayId = 2,
            )

            assertTrue(result.success)
            assertEquals("gesture_dispatched_via_root", result.message)
            assertEquals(1, ShadowA11yServiceForGesture.dispatchGestureCalls)
            assertEquals(1, recordingService.injectTapCalls)
            assertEquals(0, recordingService.injectSwipeCalls)
            assertEquals(2, recordingService.lastDisplayId)
        } finally {
            DeviceDisplayManager.resetForTesting()
            uninstallTestAssistsService()
        }
    }

    @Test
    fun testAcceptedGestureDoesNotReplayOnRootWhenTimedOut() = runBlocking {
        installTestAssistsService(acceptGesture = true)
        val recordingService = RecordingDisplayService()
        DeviceDisplayManager.privilegedService = recordingService
        try {
            val platform = createAccessibilityPlatform(context)
            val result = withTimeoutOrNull(50L) {
                invokeAccessibilityDispatch(
                    platform = platform,
                    action = Action(
                        tool = "long_press",
                        args = mapOf("x" to 150.0, "y" to 300.0, "duration_ms" to 700L),
                    ),
                    displayId = 2,
                )
            }

            assertNull(result)
            assertEquals(1, ShadowA11yServiceForGesture.dispatchGestureCalls)
            assertEquals(0, recordingService.injectTapCalls)
            assertEquals(0, recordingService.injectSwipeCalls)
        } finally {
            DeviceDisplayManager.resetForTesting()
            uninstallTestAssistsService()
        }
    }

    @Test
    fun testGestureCancelledBeforeDispatchDoesNotInject() = runBlocking {
        installTestAssistsService(acceptGesture = false)
        val recordingService = RecordingDisplayService()
        DeviceDisplayManager.privilegedService = recordingService
        try {
            val platform = createAccessibilityPlatform(context)
            var caughtCancellation = false
            val job = launch {
                coroutineContext.cancel()
                try {
                    invokeAccessibilityDispatch(
                        platform = platform,
                        action = Action(
                            tool = "long_press",
                            args = mapOf("x" to 150.0, "y" to 300.0, "duration_ms" to 700L),
                        ),
                        displayId = 2,
                    )
                } catch (e: Throwable) {
                    val actual = if (e is java.lang.reflect.InvocationTargetException) (e.targetException ?: e.cause ?: e) else e
                    if (actual is kotlinx.coroutines.CancellationException) {
                        caughtCancellation = true
                    }
                    throw actual
                }
            }
            job.join()
            assertTrue(caughtCancellation || job.isCancelled)
            assertEquals(0, recordingService.injectTapCalls)
            assertEquals(0, recordingService.injectSwipeCalls)
        } finally {
            DeviceDisplayManager.resetForTesting()
            uninstallTestAssistsService()
        }
    }

    @Test
    fun testScreenChangedVisualPixelComparisonRequiresTwoConsecutiveObservations() = runBlocking {
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        val blueBytes = createTestBitmap(color = Color.BLUE, fillScreen = true)

        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = "<hierarchy />", screenshotJpeg = redBytes)
        states += createPlatformState(xml = "<hierarchy />", screenshotJpeg = blueBytes)
        states += createPlatformState(xml = "<hierarchy />", screenshotJpeg = blueBytes)

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = "<hierarchy />", screenshotJpeg = blueBytes)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 2000, "condition" to "screen_changed"),
            ),
        )

        assertTrue(result.success)
        assertEquals("wait_condition_met", result.message)
        assertEquals("screen_changed", result.diagnostics["wait_condition"])
        assertEquals("true", result.diagnostics["condition_met"])
    }

    @Test
    fun testScreenChangedFailsWhenScreenshotCaptureFailsOnNoNodeTree() = runBlocking {
        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = "<hierarchy />", screenshotJpeg = null)
        states += createPlatformState(xml = "<hierarchy />", screenshotJpeg = null)

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = "<hierarchy />", screenshotJpeg = null)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 300, "condition" to "screen_changed"),
            ),
        )

        assertFalse(result.success)
        assertEquals("wait_condition_timeout", result.message)
    }

    @Test
    fun testConditionalWaitTextPresentSucceedsWhenTargetAppears() = runBlocking {
        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = """<hierarchy><node id="1" text="加载中..." bounds="[0,0][100,100]" /></hierarchy>""")
        states += createPlatformState(xml = """<hierarchy><node id="2" text="确认" bounds="[0,0][100,100]" /></hierarchy>""")

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = """<hierarchy><node id="2" text="确认" bounds="[0,0][100,100]" /></hierarchy>""")
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "condition" to "text_present",
                    "target_description" to "确认",
                ),
            ),
        )

        assertTrue(result.success)
        assertEquals("wait_condition_met", result.message)
        assertEquals("text_present", result.diagnostics["wait_condition"])
        assertEquals("true", result.diagnostics["condition_met"])
        assertNotNull(result.diagnostics["elapsed_ms"])
    }

    @Test
    fun testConditionalWaitTextPresentTimesOutWhenTargetNeverAppears() = runBlocking {
        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = """<hierarchy><node id="1" text="其他内容" bounds="[0,0][100,100]" /></hierarchy>""")

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = """<hierarchy><node id="1" text="其他内容" bounds="[0,0][100,100]" /></hierarchy>""")
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 300,
                    "condition" to "text_present",
                    "target_description" to "确认",
                ),
            ),
        )

        assertFalse(result.success)
        assertEquals("wait_condition_timeout", result.message)
        assertEquals("text_present", result.diagnostics["wait_condition"])
        assertEquals("false", result.diagnostics["condition_met"])
    }

    @Test
    fun testConditionalWaitTextAbsentSucceedsWhenTargetDisappears() = runBlocking {
        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = """<hierarchy><node id="1" text="加载中" bounds="[0,0][100,100]" /></hierarchy>""")
        states += createPlatformState(xml = """<hierarchy><node id="2" text="完成" bounds="[0,0][100,100]" /></hierarchy>""")

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = """<hierarchy><node id="2" text="完成" bounds="[0,0][100,100]" /></hierarchy>""")
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "condition" to "text_absent",
                    "target_description" to "加载中",
                ),
            ),
        )

        assertTrue(result.success)
        assertEquals("wait_condition_met", result.message)
        assertEquals("text_absent", result.diagnostics["wait_condition"])
        assertEquals("true", result.diagnostics["condition_met"])
    }

    @Test
    fun testConditionalWaitTextAbsentReturnsUnobservableWhenTargetNotInBaseline() = runBlocking {
        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = """<hierarchy><node id="1" text="完成" bounds="[0,0][100,100]" /></hierarchy>""")

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = """<hierarchy><node id="1" text="完成" bounds="[0,0][100,100]" /></hierarchy>""")
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 1000,
                    "condition" to "text_absent",
                    "target_description" to "从未出现的加载",
                ),
            ),
        )

        assertFalse(result.success)
        assertEquals("wait_condition_unobservable", result.message)
        assertEquals("text_absent", result.diagnostics["wait_condition"])
        assertEquals("false", result.diagnostics["condition_met"])
    }

    @Test
    fun testConditionalWaitTextMatchingNormalizesNfkcWhitespaceAndCasefold() = runBlocking {
        val states = ArrayDeque<Any>()
        states += createPlatformState(
            xml = """
            <hierarchy>
              <node id="1" text="ＰＡＳＳＷＯＲＤ" password="true" bounds="[0,0][100,50]" />
              <node id="2" text="去&#x85;&#xA0;&#x2003;&#x2028;结算 (３)" bounds="[0,50][100,100]" />
            </hierarchy>
            """.trimIndent(),
        )

        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.first()
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 1000,
                    "condition" to "text_present",
                    "target_description" to "去 结算 (3)",
                ),
            ),
        )
        assertTrue(result.success)
        assertEquals("wait_condition_met", result.message)

        val passwordResult = env.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 250,
                    "condition" to "text_present",
                    "target_description" to "password",
                ),
            ),
        )
        assertFalse(passwordResult.success)
        assertEquals("wait_condition_timeout", passwordResult.message)
    }

    @Test
    fun testClickStabilizesAsUnchangedWhenScreenDoesNotChange() = runBlocking {
        val staticXml = """<hierarchy><node id="1" text="不变的内容" bounds="[0,0][1080,2400]" /></hierarchy>"""
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                createPlatformState(xml = staticXml)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 100, "y" to 200)),
        )

        assertTrue(result.success)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
        assertEquals("stable_unchanged", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testClickStabilizesAsChangedWhenScreenChanges() = runBlocking {
        val beforeXml = """<hierarchy><node id="1" text="加载前" bounds="[0,0][1080,2400]" /></hierarchy>"""
        val afterXml = """<hierarchy><node id="2" text="加载后" bounds="[0,0][1080,2400]" /></hierarchy>"""
        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = beforeXml)
                } else {
                    createPlatformState(xml = afterXml)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 100, "y" to 200)),
        )

        assertTrue(result.success)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
        assertEquals("stable_changed", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testPureVisualStabilizationOnCanvasScreen() = runBlocking {
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        val blueBytes = createTestBitmap(color = Color.BLUE, fillScreen = true)
        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = redBytes)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = blueBytes)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 100, "y" to 200)),
        )

        assertTrue(result.success)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
        assertEquals("stable_changed", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testPureVisualStabilizationTimesOutWhenScreenshotCaptureFails() = runBlocking {
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = redBytes)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = null)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 100, "y" to 200)),
        )

        assertTrue(result.success)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization"])
        assertEquals("timeout", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testPureVisualStabilizationRejectsDifferentDimensions() = runBlocking {
        val normalBytes = createTestBitmap(width = 1080, height = 2400, color = Color.RED, fillScreen = true)
        val resizedBytes = createTestBitmap(width = 540, height = 1200, color = Color.RED, fillScreen = true)
        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = normalBytes)
                } else {
                    createPlatformState(xml = "<hierarchy />", screenshotJpeg = resizedBytes)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 100, "y" to 200)),
        )

        assertTrue(result.success)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization"])
        assertEquals("timeout", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testStabilizationTimesOutWhenPostActionDisplayMismatchesTarget() = runBlocking {
        val staticXml = """<hierarchy><node id="1" text="跨屏测试" bounds="[0,0][1080,2400]" /></hierarchy>"""
        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = staticXml, displayId = Display.DEFAULT_DISPLAY)
                } else {
                    // 后续观测错误返回了其他 displayId (例如 99)
                    createPlatformState(xml = staticXml, displayId = 99)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 100, "y" to 200)),
            displayId = Display.DEFAULT_DISPLAY,
        )

        assertTrue(result.success)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization"])
        assertEquals("timeout", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testActStabilizationDetectsVisualChangeWithUsableXmlNodesAndMovingPixelsResetStability() = runBlocking {
        // 同非空 XML，画布区域内部发生像素绘制变化（红 -> 蓝 -> 橙）
        val canvasXml = """<hierarchy><node id="1" text="Canvas画布" bounds="[0,0][1080,200]" visible-to-user="true" /><node id="2" resource-id="cn.com.omnimind.androidgui.test:id/view_canvas" bounds="[100,500][900,1300]" visible-to-user="true" /></hierarchy>"""
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        val blueBytes = createTestBitmap(color = Color.BLUE, fillScreen = true)
        val orangeBytes = createTestBitmap(color = Color.rgb(255, 165, 0), fillScreen = true)

        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { captureScreenshot, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                val jpeg = when (observeCalls) {
                    1 -> redBytes // 动作前基线 (红)
                    2 -> blueBytes // 动作后首样 (蓝，发生视觉变化)
                    3 -> orangeBytes // 像素仍在移动/改变 (橙，重置稳定计数)
                    4 -> orangeBytes // 橙色稳定第 2 次
                    else -> orangeBytes // 橙色稳定第 3 次及后续
                }
                createPlatformState(xml = canvasXml, screenshotJpeg = if (captureScreenshot) jpeg else null)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 540, "y" to 900)),
        )

        assertTrue(result.success)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
        assertEquals("stable_changed", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testWaitScreenChangedFulfillsWhenVisualPixelsChangeWithUsableXmlNodes() = runBlocking {
        // 同非空 XML，纯内部像素改变（红 -> 蓝）时，wait screen_changed 仍能确证并成功返回
        val canvasXml = """<hierarchy><node id="1" text="Canvas场景" bounds="[0,0][1080,200]" visible-to-user="true" /><node id="2" resource-id="cn.com.omnimind.androidgui.test:id/view_canvas" bounds="[100,500][900,1300]" visible-to-user="true" /></hierarchy>"""
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        val blueBytes = createTestBitmap(color = Color.BLUE, fillScreen = true)

        val states = ArrayDeque<Any>()
        states += createPlatformState(xml = canvasXml, screenshotJpeg = redBytes)
        states += createPlatformState(xml = canvasXml, screenshotJpeg = blueBytes)
        states += createPlatformState(xml = canvasXml, screenshotJpeg = blueBytes)

        val fakePlatform = createFakePlatform(
            onObserve = { captureScreenshot, _ ->
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                states.removeFirstOrNull() ?: createPlatformState(xml = canvasXml, screenshotJpeg = blueBytes)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 2000, "condition" to "screen_changed"),
            ),
        )

        assertTrue(result.success)
        assertEquals("wait_condition_met", result.message)
        assertEquals("screen_changed", result.diagnostics["wait_condition"])
        assertEquals("true", result.diagnostics["condition_met"])
    }

    @Test
    fun testActStabilizationTimesOutWhenScreenshotCaptureFailsGivenBaselineImageAndUsableXml() = runBlocking {
        // 基线有有效截图与非空 XML，动作后截图采集失败 (null) 时，不能以仅 XML 相同判为假 stable_unchanged，必须超时
        val canvasXml = """<hierarchy><node id="1" text="Canvas场景" bounds="[0,0][1080,200]" visible-to-user="true" /></hierarchy>"""
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)

        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { captureScreenshot, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = canvasXml, screenshotJpeg = if (captureScreenshot) redBytes else null)
                } else {
                    createPlatformState(xml = canvasXml, screenshotJpeg = null)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(tool = "click", args = mapOf("x" to 540, "y" to 900)),
        )

        assertTrue(result.success)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization"])
        assertEquals("timeout", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun testObservePersistsDistinctStatesAndImagesWhenOnlyVisualPixelsChange() = runBlocking {
        // 同非空 XML，视觉像素绘制不同（红 vs 橙）时，State 身份不同且 InternalRunLogStore 持久化两份不同截图文件，不可覆盖
        val canvasXml = """<hierarchy><node id="1" text="Canvas画布" bounds="[0,0][1080,200]" visible-to-user="true" /><node id="2" resource-id="cn.com.omnimind.androidgui.test:id/view_canvas" bounds="[100,500][900,1300]" visible-to-user="true" /></hierarchy>"""
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        val orangeBytes = createTestBitmap(color = Color.rgb(255, 165, 0), fillScreen = true)

        var currentJpeg = redBytes
        val fakePlatform = createFakePlatform(
            onObserve = { _, _ ->
                createPlatformState(xml = canvasXml, screenshotJpeg = currentJpeg)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val obs1 = env.observeWithDiagnostics(captureScreenshot = true, waitToStabilize = false)
        currentJpeg = orangeBytes
        val obs2 = env.observeWithDiagnostics(captureScreenshot = true, waitToStabilize = false)

        assertNotEquals(obs1.state.stateId, obs2.state.stateId)
        assertNotNull(obs1.state.screenshotPath)
        assertNotNull(obs2.state.screenshotPath)
        assertNotEquals(obs1.state.screenshotPath, obs2.state.screenshotPath)

        val file1 = File(obs1.state.screenshotPath!!)
        val file2 = File(obs2.state.screenshotPath!!)
        assertTrue(file1.exists())
        assertTrue(file2.exists())
        assertFalse(file1.readBytes().contentEquals(file2.readBytes()))
    }

    @Test
    fun testWaitScreenChangedTimesOutWhenSampleScreenshotCaptureFailsGivenBaselineImage() = runBlocking {
        // 基线有有效截图与非空 XML，等待过程中截图采集失败 (null) 时，不能误判为 screen_changed，必须超时
        val canvasXml = """<hierarchy><node id="1" text="Canvas场景" bounds="[0,0][1080,200]" visible-to-user="true" /></hierarchy>"""
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)

        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { captureScreenshot, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                if (observeCalls == 1) {
                    createPlatformState(xml = canvasXml, screenshotJpeg = if (captureScreenshot) redBytes else null)
                } else {
                    createPlatformState(xml = canvasXml, screenshotJpeg = null)
                }
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 400, "condition" to "screen_changed"),
            ),
        )

        assertFalse(result.success)
        assertEquals("wait_condition_timeout", result.message)
    }

    @Test
    fun testWaitScreenChangedResetsStabilityOnMovingPixelsBeforeReachingTwoSamples() = runBlocking {
        // 同非空 XML，内部像素在变化中持续移动（红 -> 蓝 -> 橙 -> 橙），连续两次相同才满足条件
        val canvasXml = """<hierarchy><node id="1" text="Canvas场景" bounds="[0,0][1080,200]" visible-to-user="true" /></hierarchy>"""
        val redBytes = createTestBitmap(color = Color.RED, fillScreen = true)
        val blueBytes = createTestBitmap(color = Color.BLUE, fillScreen = true)
        val orangeBytes = createTestBitmap(color = Color.rgb(255, 165, 0), fillScreen = true)

        var observeCalls = 0
        val fakePlatform = createFakePlatform(
            onObserve = { captureScreenshot, _ ->
                observeCalls++
                ShadowSystemClock.advanceBy(Duration.ofMillis(200))
                val jpeg = when (observeCalls) {
                    1 -> redBytes // 基线 (红)
                    2 -> blueBytes // 首个变化 (蓝，consecutive = 1)
                    3 -> orangeBytes // 像素在移动 (橙，重置 consecutive = 1)
                    4 -> orangeBytes // 稳定第 2 次 (橙，consecutive = 2 -> 满足条件)
                    else -> orangeBytes
                }
                createPlatformState(xml = canvasXml, screenshotJpeg = if (captureScreenshot) jpeg else null)
            },
            onDispatch = { _, _ -> AndroidGuiActionResult(true, "ok") },
        )
        val env = createEnvironment(context, fakePlatform)

        val result = env.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 2000, "condition" to "screen_changed"),
            ),
        )

        assertTrue(result.success)
        assertEquals("wait_condition_met", result.message)
        assertEquals("true", result.diagnostics["condition_met"])
        assertTrue(observeCalls >= 4)
    }

}
