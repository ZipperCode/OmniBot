package cn.com.omnimind.androidgui

import android.view.Display
import cn.com.omnimind.baselib.runlog.Action
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGuiEnvironmentTest {
    @Test
    fun `input popup movement keeps click detection strict and input execution recoverable`() {
        assertEquals(false, InputNodeLookup.CLICK_TARGET.allowFallbackAfterCoordinateMiss)
        assertEquals(true, InputNodeLookup.INPUT_ACTION.allowFallbackAfterCoordinateMiss)
    }

    @Test
    fun `accessibility reconnect window tolerates slow OEM rebinding`() {
        assertEquals(15_000L, ACCESSIBILITY_READY_TIMEOUT_MS)
    }

    @Test
    fun `open app gets an extended stabilization window`() {
        assertTrue(
            stateStabilizationTimeoutMs("open_app") >
                stateStabilizationTimeoutMs("click"),
        )
    }

    @Test
    fun `click gesture keeps a real tap duration`() {
        assertEquals(100L, CLICK_GESTURE_DURATION_MS)
    }

    @Test
    fun `screen capture waits for transient accessibility reconnect`() = runBlocking {
        val platform = ReconnectingPlatform()
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)
        launch {
            delay(100L)
            platform.ready = true
        }

        val snapshot = environment.captureScreenSnapshot()

        assertEquals("com.android.settings", snapshot.packageName)
        assertEquals(1, platform.observeCalls)
    }

    @Test
    fun `action waits for transient accessibility reconnect instead of restarting task`() = runBlocking {
        val platform = ReconnectingPlatform()
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)
        launch {
            delay(100L)
            platform.ready = true
        }

        val result = environment.act(Action(tool = "wait", args = mapOf("duration_ms" to 0)))

        assertTrue(result.success)
        assertEquals(1, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
        assertEquals("host_completed", result.diagnostics["state_stabilization"])
    }

    @Test
    fun `manual action can dispatch without waiting for page stabilization`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        val result = environment.act(
            Action(tool = "click", args = mapOf("x" to 10, "y" to 20)),
            awaitStabilization = false,
        )

        assertTrue(result.success)
        assertEquals(1, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
        assertEquals("runtime_delegated", result.diagnostics["state_stabilization_result"])
    }

    @Test
    fun `conditional wait rejects invalid duration with invalid_wait_duration and zero dispatch`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        val zeroDuration = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 0,
                    "condition" to "text_present",
                    "target_description" to "确认",
                ),
            ),
        )
        assertFalse(zeroDuration.success)
        assertEquals("invalid_wait_duration", zeroDuration.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)

        val excessDuration = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 15000,
                    "condition" to "text_present",
                    "target_description" to "确认",
                ),
            ),
        )
        assertFalse(excessDuration.success)
        assertEquals("invalid_wait_duration", excessDuration.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
    }

    @Test
    fun `conditional wait rejects invalid condition combinations with invalid_wait_condition and zero dispatch`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        // text_present without target_description
        val noTarget = environment.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 2000, "condition" to "text_present"),
            ),
        )
        assertFalse(noTarget.success)
        assertEquals("invalid_wait_condition", noTarget.message)
        assertEquals(0, platform.dispatchCalls)

        // screen_changed with target_description
        val screenWithTarget = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "condition" to "screen_changed",
                    "target_description" to "不该有的描述",
                ),
            ),
        )
        assertFalse(screenWithTarget.success)
        assertEquals("invalid_wait_condition", screenWithTarget.message)
        assertEquals(0, platform.dispatchCalls)

        // unconditioned wait with target_description
        val unconditionedWithTarget = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "target_description" to "不该有的描述",
                ),
            ),
        )
        assertFalse(unconditionedWithTarget.success)
        assertEquals("invalid_wait_condition", unconditionedWithTarget.message)
        assertEquals(0, platform.dispatchCalls)

        // unknown condition
        val unknownCondition = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "condition" to "unknown_condition",
                    "target_description" to "目标",
                ),
            ),
        )
        assertFalse(unknownCondition.success)
        assertEquals("invalid_wait_condition", unknownCondition.message)
        assertEquals(0, platform.dispatchCalls)
    }

    @Test
    fun `conditional wait rejects fractional and string duration`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        val floatDuration = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 1.5,
                    "condition" to "text_present",
                    "target_description" to "确认",
                ),
            ),
        )
        assertFalse(floatDuration.success)
        assertEquals("invalid_wait_duration", floatDuration.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)

        val stringDuration = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to "2500",
                    "condition" to "text_present",
                    "target_description" to "确认",
                ),
            ),
        )
        assertFalse(stringDuration.success)
        assertEquals("invalid_wait_duration", stringDuration.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
    }

    @Test
    fun `wait rejects non-string condition and empty target description presence`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        // condition is boolean true
        val boolCondition = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "condition" to true,
                    "target_description" to "确认",
                ),
            ),
        )
        assertFalse(boolCondition.success)
        assertEquals("invalid_wait_condition", boolCondition.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)

        // screen_changed with target_description = ""
        val screenEmptyTarget = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "condition" to "screen_changed",
                    "target_description" to "",
                ),
            ),
        )
        assertFalse(screenEmptyTarget.success)
        assertEquals("invalid_wait_condition", screenEmptyTarget.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)

        // unconditional wait with target_description = ""
        val unconditionedEmptyTarget = environment.act(
            Action(
                tool = "wait",
                args = mapOf(
                    "duration_ms" to 2000,
                    "target_description" to "",
                ),
            ),
        )
        assertFalse(unconditionedEmptyTarget.success)
        assertEquals("invalid_wait_condition", unconditionedEmptyTarget.message)
        assertEquals(0, platform.dispatchCalls)
        assertEquals(0, platform.observeCalls)
    }

    @Test
    fun `unconditional wait preserves delay and dispatches to platform`() = runBlocking {
        val platform = ReconnectingPlatform().apply { ready = true }
        val environment = AndroidGuiEnvironment(appContext = null, platform = platform)

        val result = environment.act(
            Action(
                tool = "wait",
                args = mapOf("duration_ms" to 50),
            ),
        )

        assertTrue(result.success)
        assertEquals("ok", result.message)
        assertEquals(1, platform.dispatchCalls)
        assertEquals(50, (platform.dispatchedActions.first().args["duration_ms"] as Number).toInt())
    }
    private class ReconnectingPlatform : AndroidGuiPlatform {
        @Volatile
        var ready: Boolean = false
        var observeCalls: Int = 0
        var dispatchCalls: Int = 0
        val dispatchedActions = mutableListOf<Action>()
        val observedStates = ArrayDeque<AndroidGuiPlatformState>()
        var dispatchResult: AndroidGuiActionResult? = null

        override fun isAccessibilityEnabled(): Boolean = true

        override fun isReady(): Boolean = ready

        override fun displaySize(): Pair<Int, Int> = 1080 to 2400

        override fun displaySize(displayId: Int): Pair<Int, Int> = 1080 to 2400

        override fun screenshotExcludesOverlays(): Boolean = true

        override suspend fun observe(captureScreenshot: Boolean): AndroidGuiPlatformState =
            observe(captureScreenshot, Display.DEFAULT_DISPLAY)

        override suspend fun observe(
            captureScreenshot: Boolean,
            displayId: Int,
        ): AndroidGuiPlatformState {
            check(ready) { "android_gui_accessibility_not_ready" }
            observeCalls += 1
            return observedStates.removeFirstOrNull() ?: state()
        }

        override suspend fun dispatch(action: Action): AndroidGuiActionResult =
            dispatch(action, Display.DEFAULT_DISPLAY)

        override suspend fun dispatch(
            action: Action,
            displayId: Int,
        ): AndroidGuiActionResult {
            check(ready) { "android_gui_accessibility_not_ready" }
            dispatchCalls += 1
            dispatchedActions += action
            return dispatchResult ?: AndroidGuiActionResult(success = true, message = "ok")
        }

        override suspend fun inputTarget(
            x: Float?,
            y: Float?,
        ): AndroidGuiInputTarget? = null

        override suspend fun inputTarget(
            x: Float?,
            y: Float?,
            displayId: Int,
        ): AndroidGuiInputTarget? = null
        override suspend fun installedApplications(): Map<String, String> = emptyMap()

        override fun inputMethodTop(): Int? = null
    }

    private companion object {
        fun state(
            xml: String = "<hierarchy />",
            packageName: String = "com.android.settings",
            activityName: String = "Settings",
            screenshotJpeg: ByteArray? = null,
            displayId: Int = Display.DEFAULT_DISPLAY,
        ) = AndroidGuiPlatformState(
            packageName = packageName,
            activityName = activityName,
            displayWidth = 1080,
            displayHeight = 2400,
            xml = xml,
            screenshotJpeg = screenshotJpeg,
            displayId = displayId,
        )
    }
}
