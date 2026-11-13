package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import cn.com.omnimind.androidgui.AndroidGuiActionResult
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DeviceDisplayToolHandlerWaitTest {

    private lateinit var context: Context
    private lateinit var mockEnvironment: AndroidGuiEnvironment
    private lateinit var handler: DeviceDisplayToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockEnvironment = Mockito.mock(AndroidGuiEnvironment::class.java)
        handler = DeviceDisplayToolHandler(context, mockEnvironment)
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.displayBridge = FakeWaitDisplayBridge(2)
        val vfField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        val voField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        vfField.setInt(DeviceDisplayManager, 2)
        voField.set(DeviceDisplayManager, "session-wait-test")
        DisplayInputCoordinator.registerVirtualDisplayOwnership(2, "session-wait-test")
    }

    @After
    fun tearDown() {
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.displayBridge = null
        val vfField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        val voField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        vfField.setInt(DeviceDisplayManager, 0)
        voField.set(DeviceDisplayManager, null)
    }
    private fun anyAction(): Action {
        Mockito.any(Action::class.java)
        return Action("wait", emptyMap())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNullable(): T? {
        Mockito.any<T>()
        return null
    }


    @Test
    fun handleWaitDispatchesConditionAndFailsOnConditionTimeout() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        Mockito.`when`(
            mockEnvironment.act(
                action = anyAction(),
                awaitStabilization = Mockito.anyBoolean(),
                displayId = Mockito.eq(2),
                leaseOwnerId = anyNullable(),
                expectedState = anyNullable(),
                expectedObservationId = anyNullable(),
            )
        ).thenReturn(
            AndroidGuiActionResult(
                success = false,
                message = "wait_condition_timeout",
                diagnostics = mapOf(
                    "wait_condition" to "text_present",
                    "condition_met" to "false",
                    "elapsed_ms" to "2500",
                ),
            )
        )

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("duration_ms", 2500)
            put("condition", "text_present")
            put("target_description", "去结算")
        }

        val result = handler.execute(mockToolCall("call-wait-1"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult

        // 验证失败返回边界：超时不得假报成功，诊断与错误信息必须保留
        assertFalse("条件等待超时必须返回失败", contextResult.success)
        assertTrue("错误信息应包含超时原因", contextResult.summaryText.contains("wait_condition_timeout"))
        val parsed = Json.parseToJsonElement(contextResult.rawResultJson).jsonObject
        assertEquals("false", parsed["success"]?.jsonPrimitive?.content)
        assertEquals("wait_condition_timeout", parsed["error"]?.jsonPrimitive?.content)
        assertEquals("text_present", parsed["wait_condition"]?.jsonPrimitive?.content)
        assertEquals("false", parsed["condition_met"]?.jsonPrimitive?.content)
        assertEquals("2500", parsed["elapsed_ms"]?.jsonPrimitive?.content)
    }

    @Test
    fun conditionWaitMissingDurationFailsWithoutDispatch() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("condition", "text_present")
            put("target_description", "去结算")
        }

        val result = handler.execute(mockToolCall("call-wait-missing-duration"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse(contextResult.success)
        assertEquals("invalid_wait_duration", contextResult.summaryText)
        Mockito.verifyNoInteractions(mockEnvironment)
    }

    @Test
    fun conditionWaitWithFloatDurationFailsWithoutDispatch() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("duration_ms", 1500.5)
            put("condition", "text_present")
            put("target_description", "去结算")
        }

        val result = handler.execute(mockToolCall("call-wait-float-duration"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse(contextResult.success)
        assertEquals("invalid_wait_duration", contextResult.summaryText)
        Mockito.verifyNoInteractions(mockEnvironment)
    }

    @Test
    fun conditionWaitWithStringDurationFailsWithoutDispatch() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("duration_ms", "2500")
            put("condition", "text_present")
            put("target_description", "去结算")
        }

        val result = handler.execute(mockToolCall("call-wait-string-duration"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse(contextResult.success)
        assertEquals("invalid_wait_duration", contextResult.summaryText)
        Mockito.verifyNoInteractions(mockEnvironment)
    }

    @Test
    fun unconditionalWaitWithStringDurationFailsWithoutDispatch() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("duration_ms", "1000")
        }

        val result = handler.execute(mockToolCall("call-wait-uncond-string"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse(contextResult.success)
        assertEquals("invalid_wait_duration", contextResult.summaryText)
        Mockito.verifyNoInteractions(mockEnvironment)
    }

    @Test
    fun unconditionalWaitMissingDurationDefaultsTo1000() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        Mockito.`when`(
            mockEnvironment.act(
                action = anyAction(),
                awaitStabilization = Mockito.anyBoolean(),
                displayId = Mockito.eq(2),
                leaseOwnerId = anyNullable(),
                expectedState = anyNullable(),
                expectedObservationId = anyNullable(),
            )
        ).thenReturn(
            AndroidGuiActionResult(
                success = true,
                message = "wait_completed",
                diagnostics = mapOf("elapsed_ms" to "1000"),
            )
        )

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
        }

        val result = handler.execute(mockToolCall("call-wait-default"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertTrue(contextResult.success)
        assertTrue(contextResult.summaryText.contains("1000ms"))
        val parsed = Json.parseToJsonElement(contextResult.rawResultJson).jsonObject
        assertEquals("1000", parsed["duration_ms"]?.jsonPrimitive?.content)
    }

    @Test
    fun unconditionalWaitWithFloatDurationFailsWithoutDispatch() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("duration_ms", 12.34)
        }

        val result = handler.execute(mockToolCall("call-wait-uncond-float"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse(contextResult.success)
        assertEquals("invalid_wait_duration", contextResult.summaryText)
        Mockito.verifyNoInteractions(mockEnvironment)
    }

    @Test
    fun conditionWithNonStringFailsWithoutDispatch() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        val args = buildJsonObject {
            put("action", "wait")
            put("display", "virtual")
            put("condition", 123)
            put("target_description", "去结算")
        }

        val result = handler.execute(mockToolCall("call-wait-non-string"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse(contextResult.success)
        assertEquals("invalid_wait_condition", contextResult.summaryText)
        Mockito.verifyNoInteractions(mockEnvironment)
    }
    private fun mockEnv(sessionId: String = "session-wait-test", turnId: String = "turn-wait-1"): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(100L)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn("run-$sessionId")
        return env
    }

    private fun mockDescriptor() = AgentToolRegistry.RuntimeToolDescriptor(
        DeviceDisplayToolHandler.TOOL_NAME,
        DeviceDisplayToolHandler.TOOL_NAME,
        "",
    )

    private fun mockToolCall(id: String = "call-wait") = AssistantToolCall(
        id = id,
        type = "function",
        function = AssistantToolCallFunction(
            name = DeviceDisplayToolHandler.TOOL_NAME,
            arguments = "{}",
        ),
    )

    private class FakeWaitDisplayBridge(val displayId: Int = 2) : PrivilegedDisplayBridge {
        override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: android.view.Surface, flags: Int): Result<Int> = Result.success(displayId)
        override suspend fun releaseVirtualDisplay(displayId: Int) {}
        override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
        override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override suspend fun getRootTasks(displayId: Int): String = "[]"
        override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String = "{}"
    }
}
