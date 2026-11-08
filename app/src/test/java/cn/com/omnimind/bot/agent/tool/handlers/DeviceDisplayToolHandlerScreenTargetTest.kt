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
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DeviceDisplayToolHandlerScreenTargetTest {

    private lateinit var context: Context
    private lateinit var handler: DeviceDisplayToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        handler = DeviceDisplayToolHandler(context)
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
    }

    @After
    fun tearDown() {
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
    }

    private fun mockEnv(sessionId: String, turnId: String): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(1L)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn("run-1")
        return env
    }

    @Test
    fun `list_displays does not mark screen target on TaskRuntime`() = runBlocking {
        val sessionId = "session-list-test"
        val turnId = "turn-list-1"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = turnId,
            conversationId = 1L,
        )

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            DeviceDisplayToolHandler.TOOL_NAME,
            DeviceDisplayToolHandler.TOOL_NAME,
            ""
        )
        val toolCall = AssistantToolCall(
            id = "call-1",
            type = "function",
            function = AssistantToolCallFunction("device_display_action", "{}")
        )

        val args = buildJsonObject {
            put("action", "list_displays")
        }

        handler.execute(toolCall, args, descriptor, env, callback, handle)

        // list_displays 不视为 AI 接管，screenDisplayId 应保持 null
        assertNull(
            "list_displays 不应标记 screenDisplayId",
            TaskRuntime.getActiveTask(taskId)?.screenDisplayId
        )
    }

    @Test
    fun `switch_display marks screen target on TaskRuntime`() = runBlocking {
        val sessionId = "session-switch-test"
        val turnId = "turn-switch-1"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = turnId,
            conversationId = 1L,
        )

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            DeviceDisplayToolHandler.TOOL_NAME,
            DeviceDisplayToolHandler.TOOL_NAME,
            ""
        )
        val toolCall = AssistantToolCall(
            id = "call-2",
            type = "function",
            function = AssistantToolCallFunction("device_display_action", "{}")
        )

        val args = buildJsonObject {
            put("action", "switch_display")
            put("display", "0")
        }

        handler.execute(toolCall, args, descriptor, env, callback, handle)

        assertEquals(
            0,
            TaskRuntime.getActiveTask(taskId)?.screenDisplayId
        )
    }
}
