package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
class DeviceDisplayToolHandlerPresentTest {

    private lateinit var context: Context
    private lateinit var handler: DeviceDisplayToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        handler = DeviceDisplayToolHandler(context)
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
    }

    @After
    fun tearDown() {
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
        DeviceDisplayManager.displayBridge = null
    }

    private fun setVirtualDisplayForTesting(displayId: Int, ownerId: String?) {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        field.set(DeviceDisplayManager, ownerId)
        idField.setInt(DeviceDisplayManager, displayId)
        if (ownerId != null && displayId != Display.DEFAULT_DISPLAY) {
            DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, ownerId)
        }
    }

    private fun mockEnv(sessionId: String, turnId: String = "turn-1"): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(1L)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn("run-1")
        return env
    }

    @Test
    fun `present_to_user via agent tool handler is rejected with background_display_required`() = runBlocking {
        val env = mockEnv("session-test")
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            DeviceDisplayToolHandler.TOOL_NAME,
            DeviceDisplayToolHandler.TOOL_NAME,
            ""
        )
        val toolCall = AssistantToolCall("call-1", "function", AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}"))

        val result = handler.execute(
            toolCall = toolCall,
            args = buildJsonObject {
                put("action", "present_to_user")
                put("packageName", "com.example.app")
            },
            runtimeDescriptor = descriptor,
            env = env,
            callback = callback,
            toolHandle = handle,
        )

        assertTrue("模型调用 present_to_user 必须被拒: $result", result is ToolExecutionResult.Error)
        val err = result as ToolExecutionResult.Error
        assertTrue("错误应包含 background_display_required", err.message.contains("background_display_required"))
    }

    @Test
    fun `native presentToUser failure preserves virtual display`() = runBlocking {
        val sessionId = "session-fail"
        setVirtualDisplayForTesting(3, sessionId)

        // Fake bridge: tasks exist, but moveRootTask returns verified: false
        val fakeBridge = FakePresentDisplayBridge(
            rootTasksJson = """[{"taskId":88,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.app","childTaskIds":[88]}]""",
            moveResultJson = """{"success":false,"code":"state_change_unconfirmed","taskId":88,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":false}""",
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.app",
            taskId = 88,
            sessionOwnerId = sessionId,
            leaseOwnerId = "$sessionId:turn-1:call-2",
        )

        assertFalse("迁移失败时 success 必须为 false", result.success)
        assertEquals("移交失败时虚拟屏必须保留", 3, DeviceDisplayManager.activeVirtualDisplayId())
        assertEquals("88", result.diagnostics["taskId"])
        assertEquals("3", result.diagnostics["sourceDisplayId"])
        assertEquals("0", result.diagnostics["targetDisplayId"])
        assertEquals("false", result.diagnostics["verified"])
    }

    @Test
    fun `native presentToUser success switches session display target to DEFAULT_DISPLAY`() = runBlocking {
        val sessionId = "session-ok"
        setVirtualDisplayForTesting(3, sessionId)

        val fakeBridge = FakePresentDisplayBridge(
            rootTasksJson = """[{"taskId":99,"taskKind":"root_task","displayId":3,"userId":0,"packageName":"com.example.calc","childTaskIds":[99]}]""",
            moveResultJson = """{"success":true,"code":"ok","taskId":99,"taskKind":"root_task","sourceDisplayId":3,"targetDisplayId":0,"verified":true}""",
        )
        DeviceDisplayManager.displayBridge = fakeBridge

        val result = DeviceDisplayManager.presentToUser(
            context = context,
            packageName = "com.example.calc",
            taskId = 99,
            sessionOwnerId = sessionId,
            leaseOwnerId = "$sessionId:turn-1:call-3",
        )

        assertTrue("迁移成功时 success 必须为 true", result.success)
        assertEquals("99", result.diagnostics["taskId"])
        assertEquals("3", result.diagnostics["sourceDisplayId"])
        assertEquals("0", result.diagnostics["targetDisplayId"])
        assertEquals("ok", result.diagnostics["code"])
        assertEquals("true", result.diagnostics["verified"])

        // 成功移交后也不立即销毁虚拟屏
        assertEquals("成功移交后暂不立即销毁虚拟屏", 3, DeviceDisplayManager.activeVirtualDisplayId())
        assertEquals("成功移交后会话目标为主屏", Display.DEFAULT_DISPLAY, DeviceDisplayManager.getSessionDisplayTarget(sessionId))
    }

    private class FakePresentDisplayBridge(
        var rootTasksJson: String,
        var moveResultJson: String,
    ) : PrivilegedDisplayBridge {
        override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: android.view.Surface, flags: Int): Result<Int> =
            Result.success(3)
        override suspend fun releaseVirtualDisplay(displayId: Int) {}
        override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
        override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override suspend fun getRootTasks(displayId: Int): String = rootTasksJson
        override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String = moveResultJson
    }
}
