package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.omniflow.OmniVlmPlugin
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class VlmToolHandlerDisplayAdmissionTest {

    private lateinit var context: Context
    private lateinit var handler: VlmToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        handler = VlmToolHandler(context)
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.resetForTesting()
    }

    @After
    fun tearDown() {
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.resetForTesting()
    }

    private fun mockEnv(sessionId: String, turnId: String = "turn-1"): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(100L)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn("run-$sessionId")
        return env
    }

    @Test
    fun `vlm_task with display main is rejected with background_display_required without marking task`() = runBlocking {
        val sessionId = "session-vlm-main"
        val turnId = "turn-vlm-1"
        val taskId = "agent-turn:$sessionId:$turnId"
        TaskRuntime.start(context = context, taskId = taskId, sessionId = sessionId, turnId = turnId, conversationId = 100L)

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(OmniVlmPlugin.RUN_LOG_TOOL, OmniVlmPlugin.RUN_LOG_TOOL, "")
        val toolCall = AssistantToolCall("call-vlm-main", "function", AssistantToolCallFunction(OmniVlmPlugin.RUN_LOG_TOOL, "{}"))

        val args = buildJsonObject {
            put("goal", "打开系统设置")
            put("display", "main")
        }

        val result = handler.execute(toolCall, args, descriptor, env, callback, handle)
        assertTrue("显式 main 必须返回失败结果: $result", result is ToolExecutionResult.ContextResult)
        val ctxResult = result as ToolExecutionResult.ContextResult
        assertTrue("结果应为失败", !ctxResult.success)
        assertTrue("应包含 background_display_required", ctxResult.summaryText.contains("background_display_required"))

        assertNull("TaskRuntime 不得被标记为主屏", TaskRuntime.getActiveTask(taskId)?.screenDisplayId)
        assertNull("会话目标不得被污染为主屏", DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }

    @Test
    fun `vlm_task with display 0 is rejected with background_display_required`() = runBlocking {
        val sessionId = "session-vlm-zero"
        val turnId = "turn-vlm-2"
        val taskId = "agent-turn:$sessionId:$turnId"
        TaskRuntime.start(context = context, taskId = taskId, sessionId = sessionId, turnId = turnId, conversationId = 100L)

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(OmniVlmPlugin.RUN_LOG_TOOL, OmniVlmPlugin.RUN_LOG_TOOL, "")
        val toolCall = AssistantToolCall("call-vlm-zero", "function", AssistantToolCallFunction(OmniVlmPlugin.RUN_LOG_TOOL, "{}"))

        val args = buildJsonObject {
            put("goal", "打开日历")
            put("display", "0")
        }

        val result = handler.execute(toolCall, args, descriptor, env, callback, handle)
        assertTrue("显式 0 必须返回失败结果: $result", result is ToolExecutionResult.ContextResult)
        val ctxResult = result as ToolExecutionResult.ContextResult
        assertTrue(!ctxResult.success)
        assertTrue(ctxResult.summaryText.contains("background_display_required"))

        assertNull("TaskRuntime 不得被标记为主屏", TaskRuntime.getActiveTask(taskId)?.screenDisplayId)
        assertNull("会话目标不得被污染为主屏", DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }

    @Test
    fun `vlm_task with numeric unknown display is rejected with display_forbidden`() = runBlocking {
        val sessionId = "session-vlm-unknown"
        val turnId = "turn-vlm-3"
        val taskId = "agent-turn:$sessionId:$turnId"
        TaskRuntime.start(context = context, taskId = taskId, sessionId = sessionId, turnId = turnId, conversationId = 100L)

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(OmniVlmPlugin.RUN_LOG_TOOL, OmniVlmPlugin.RUN_LOG_TOOL, "")
        val toolCall = AssistantToolCall("call-vlm-unknown", "function", AssistantToolCallFunction(OmniVlmPlugin.RUN_LOG_TOOL, "{}"))

        val args = buildJsonObject {
            put("goal", "打开日历")
            put("display", "88")
        }

        val result = handler.execute(toolCall, args, descriptor, env, callback, handle)
        assertTrue("未登记 numeric 显示屏必须返回失败: $result", result is ToolExecutionResult.ContextResult)
        val ctxResult = result as ToolExecutionResult.ContextResult
        assertTrue(!ctxResult.success)
        assertNull("TaskRuntime 不得被标记为主屏或未登记屏", TaskRuntime.getActiveTask(taskId)?.screenDisplayId)
        assertNull("会话目标不得被污染", DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }
}
