package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import android.view.Display
import android.view.Surface
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceDisplayRoutingRegressionTest {

    private lateinit var context: Context
    private lateinit var handler: DeviceDisplayToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        handler = DeviceDisplayToolHandler(context)
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

    private class RecordingFakeBridge(
        val createdDisplayId: Int = 5,
        var createFailure: Throwable? = null,
        var rootTasksResponse: String = "[]",
    ) : PrivilegedDisplayBridge {
        var createCount = 0
        var releaseCount = 0

        override suspend fun createVirtualDisplay(
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
            flags: Int,
        ): Result<Int> {
            createCount++
            createFailure?.let { return Result.failure(it) }
            return Result.success(createdDisplayId)
        }

        override suspend fun releaseVirtualDisplay(displayId: Int) {
            releaseCount++
        }

        override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
        override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override suspend fun getRootTasks(displayId: Int): String = rootTasksResponse
        override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String =
            """{"success":true,"code":"ok","taskId":1,"taskKind":"root_task","sourceDisplayId":$sourceDisplayId,"targetDisplayId":$targetDisplayId,"verified":true}"""
    }

    @Test
    fun `omitted display action routes to non-zero virtual screen and marks screen target`() = runBlocking {
        val sessionId = "session-omit-display"
        val turnId = "turn-1"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = turnId,
            conversationId = 100L,
        )

        val fakeBridge = RecordingFakeBridge(createdDisplayId = 5)
        DeviceDisplayManager.displayBridge = fakeBridge

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            DeviceDisplayToolHandler.TOOL_NAME,
            DeviceDisplayToolHandler.TOOL_NAME,
            ""
        )
        val toolCall = AssistantToolCall("call-1", "function", AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}"))

        // 省略 display 参数执行 click
        val args = buildJsonObject {
            put("action", "click")
            put("x", 500)
            put("y", 500)
        }

        val result = handler.execute(toolCall, args, descriptor, env, callback, handle)

        // 验证执行成功或正常产出 ContextResult
        assertTrue("执行结果应为 ContextResult: $result", result is ToolExecutionResult.ContextResult)

        // 验证目标屏幕被标记为非零虚拟屏 5，绝非主屏 0
        val markedDisplayId = TaskRuntime.getActiveTask(taskId)?.screenDisplayId
        assertNotNull("必须标记 screenDisplayId", markedDisplayId)
        assertEquals("默认屏幕操作必须投到非零虚拟屏", 5, markedDisplayId)
        assertEquals("会话目标必须更新为虚拟屏 5", 5, DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }

    @Test
    fun `consecutive actions in same session reuse virtual display without recreation`() = runBlocking {
        val sessionId = "session-consecutive"
        val turnId = "turn-1"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(context = context, taskId = taskId, sessionId = sessionId, turnId = turnId, conversationId = 100L)

        val fakeBridge = RecordingFakeBridge(createdDisplayId = 7)
        DeviceDisplayManager.displayBridge = fakeBridge

        val env = mockEnv(sessionId, turnId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(DeviceDisplayToolHandler.TOOL_NAME, DeviceDisplayToolHandler.TOOL_NAME, "")

        // 第 1 次操作
        val toolCall1 = AssistantToolCall("call-1", "function", AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}"))
        val args1 = buildJsonObject {
            put("action", "click")
            put("x", 100)
            put("y", 100)
        }
        handler.execute(toolCall1, args1, descriptor, env, callback, handle)
        assertEquals("首次操作应创建虚拟屏 1 次", 1, fakeBridge.createCount)
        assertEquals(7, DeviceDisplayManager.activeVirtualDisplayId())

        // 第 2 次操作（同一会话）
        val toolCall2 = AssistantToolCall("call-2", "function", AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}"))
        val args2 = buildJsonObject {
            put("action", "click")
            put("x", 200)
            put("y", 200)
        }
        handler.execute(toolCall2, args2, descriptor, env, callback, handle)

        // 验证没有重复调用 createVirtualDisplay
        assertEquals("连续操作必须复用现有虚拟屏，不得重复创建", 1, fakeBridge.createCount)
        assertEquals("虚拟屏 ID 保持一致", 7, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `two tools in same session share consistent display target and target virtual overrides main`() = runBlocking {
        val sessionId = "session-shared-target"
        val turnId = "turn-1"
        val fakeBridge = RecordingFakeBridge(createdDisplayId = 8)
        DeviceDisplayManager.displayBridge = fakeBridge

        // 1. DeviceDisplayToolHandler 默认准备虚拟屏
        val resolvedDisplayId = DeviceDisplayManager.resolveOrPrepareDisplayId(
            context = context,
            target = null,
            sessionKey = sessionId,
        )
        assertEquals(8, resolvedDisplayId)
        assertEquals(8, DeviceDisplayManager.getSessionDisplayTarget(sessionId))

        // 2. VlmToolHandler 在同会话中解析目标，必须一致得到 8
        val vlmResolved = DeviceDisplayManager.resolveOrPrepareDisplayId(
            context = context,
            target = null,
            sessionKey = sessionId,
        )
        assertEquals("VLM 工具在同会话中解析目标必须一致", 8, vlmResolved)

        // 3. 执行 switch_display 切换至主屏 0
        DeviceDisplayManager.setSessionDisplayTarget(sessionId, Display.DEFAULT_DISPLAY)
        val afterSwitch = DeviceDisplayManager.resolveOrPrepareDisplayId(
            context = context,
            target = null,
            sessionKey = sessionId,
        )
        assertEquals("切换后两者同会话默认均为主屏", Display.DEFAULT_DISPLAY, afterSwitch)

        // 4. 显式传入 virtual 时，必须准备/复用虚拟屏并覆盖此前 main 的 session target
        val virtualOverride = DeviceDisplayManager.resolveOrPrepareDisplayId(
            context = context,
            target = "virtual",
            sessionKey = sessionId,
        )
        assertEquals(8, virtualOverride)
        assertEquals("target virtual 必须覆盖此前 main 的 session target", 8, DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }

    @Test
    fun `virtual display held by another session rejects new session and does not fallback to main`() = runBlocking {
        val sessionA = "session-owner-A"
        val sessionB = "session-stranger-B"

        val fakeBridge = RecordingFakeBridge(createdDisplayId = 9)
        DeviceDisplayManager.displayBridge = fakeBridge

        // 会话 A 成功持有虚拟屏 9
        val resA = DeviceDisplayManager.ensureSessionVirtualDisplay(context, sessionA)
        assertTrue(resA.isSuccess)
        assertEquals(9, DeviceDisplayManager.activeVirtualDisplayId())

        // 会话 B 尝试执行屏幕操作
        val taskIdB = "agent-turn:$sessionB:turn-1"
        TaskRuntime.start(context = context, taskId = taskIdB, sessionId = sessionB, turnId = "turn-1", conversationId = 101L)

        val envB = mockEnv(sessionB)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(DeviceDisplayToolHandler.TOOL_NAME, DeviceDisplayToolHandler.TOOL_NAME, "")
        val toolCall = AssistantToolCall("call-b", "function", AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}"))

        val args = buildJsonObject {
            put("action", "click")
            put("x", 100)
            put("y", 100)
        }

        val result = handler.execute(toolCall, args, descriptor, envB, callback, handle)

        // 必须返回错误，绝不能回退到主屏 0
        assertTrue("其他会话占用时必须报错: $result", result is ToolExecutionResult.Error)
        val error = result as ToolExecutionResult.Error
        assertTrue("错误信息应明确提示被占用: ${error.message}", error.message.contains("already held") || error.message.contains("属于其他会话"))

        // TaskRuntime 上会话 B 的任务不得被标记为主屏 0
        val markedB = TaskRuntime.getActiveTask(taskIdB)?.screenDisplayId
        assertNotEquals("严禁暗中回退到主屏 0", Display.DEFAULT_DISPLAY, markedB)
    }

    @Test
    fun `creation failure returns error and does not execute on main screen`() = runBlocking {
        val sessionId = "session-fail-create"
        val taskId = "agent-turn:$sessionId:turn-1"
        TaskRuntime.start(context = context, taskId = taskId, sessionId = sessionId, turnId = "turn-1", conversationId = 102L)

        val fakeBridge = RecordingFakeBridge(
            createdDisplayId = -1,
            createFailure = RuntimeException("Virtual display allocation failed: out of memory"),
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val env = mockEnv(sessionId)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(DeviceDisplayToolHandler.TOOL_NAME, DeviceDisplayToolHandler.TOOL_NAME, "")
        val toolCall = AssistantToolCall("call-fail", "function", AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}"))

        val args = buildJsonObject {
            put("action", "click")
            put("x", 500)
            put("y", 500)
        }

        val result = handler.execute(toolCall, args, descriptor, env, callback, handle)

        assertTrue("创建失败必须返回 Error: $result", result is ToolExecutionResult.Error)
        assertNull("创建失败会话目标不得写 main", DeviceDisplayManager.getSessionDisplayTarget(sessionId))
        assertNull("TaskRuntime 不得标记主屏 0", TaskRuntime.getActiveTask(taskId)?.screenDisplayId)
    }

    @Test
    fun `explicit main and present_to_user and close lifecycle state verification`() = runBlocking {
        val sessionId = "session-lifecycle"

        // 1. 显式 main / 0 解析为主屏
        val mainResolved = DeviceDisplayManager.resolveOrPrepareDisplayId(
            context = context,
            target = "main",
            sessionKey = sessionId,
        )
        assertEquals(Display.DEFAULT_DISPLAY, mainResolved)

        val zeroResolved = DeviceDisplayManager.resolveOrPrepareDisplayId(
            context = context,
            target = "0",
            sessionKey = sessionId,
        )
        assertEquals(Display.DEFAULT_DISPLAY, zeroResolved)

        // 2. 建立虚拟屏
        val fakeBridge = RecordingFakeBridge(
            createdDisplayId = 6,
            rootTasksResponse = """[{"taskId":1,"taskKind":"root_task","displayId":6,"userId":0,"packageName":"com.example.app","childTaskIds":[1]}]""",
        )
        DeviceDisplayManager.displayBridge = fakeBridge
        DeviceDisplayManager.ensureSessionVirtualDisplay(context, sessionId)
        assertEquals(6, DeviceDisplayManager.getSessionDisplayTarget(sessionId))

        // 3. presentToUser 成功移交后更新目标为主屏
        val presentResult = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.app",
            taskId = 1,
            sessionOwnerId = sessionId,
            leaseOwnerId = "$sessionId:turn-1:call-1",
        )
        assertTrue("presentToUser 应成功", presentResult.success)
        assertEquals("present 成功后会话目标更新为主屏", Display.DEFAULT_DISPLAY, DeviceDisplayManager.getSessionDisplayTarget(sessionId))

        // 4. closeSessionVirtualDisplays 彻底清理会话目标
        DisplayInputCoordinator.closeSessionVirtualDisplays(sessionId)
        assertNull("closeSessionVirtualDisplays 后会话目标应被清理", DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }
}
