package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.RootAccessSnapshot
import cn.com.omnimind.baselib.privileged.RootAccessStatus
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.runtime.AcpTurnGrantRegistry
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PrivilegedToolHandler package_control.launch_activity 真实默认后台执行与防回退主屏行为测试。
 *
 * 验收真实行为：
 * 1. 默认走后台屏幕：未指定 display 时不触碰主屏，虚拟屏不可用时直接报错，绝不隐式回退主屏执行。
 * 2. 缺少包名时先验拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class PrivilegedToolHandlerLaunchActivityTest {

    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }
    private var previousRootSnapshot: RootAccessSnapshot? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        DeviceDisplayManager.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        TaskRuntime.resetForTesting()
        AcpTurnGrantRegistry.clear()
        val manager = PrivilegedCapabilityManager.get(context)
        previousRootSnapshot = manager.rootStatus()
        grantRootForTest()
    }

    @After
    fun tearDown() {
        DeviceDisplayManager.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        TaskRuntime.resetForTesting()
        AcpTurnGrantRegistry.clear()
        previousRootSnapshot?.let { restoreRootForTest(it) }
    }

    private fun grantRootForTest() {
        val manager = PrivilegedCapabilityManager.get(context)
        val rootField = PrivilegedCapabilityManager::class.java.getDeclaredField("root").apply { isAccessible = true }
        val rootController = rootField.get(manager)
        val stateField = rootController.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(rootController) as MutableStateFlow<RootAccessSnapshot>
        stateFlow.value = RootAccessSnapshot(enabled = true, status = RootAccessStatus.GRANTED, generation = 1L)
    }

    private fun restoreRootForTest(snapshot: RootAccessSnapshot) {
        val manager = PrivilegedCapabilityManager.get(context)
        val rootField = PrivilegedCapabilityManager::class.java.getDeclaredField("root").apply { isAccessible = true }
        val rootController = rootField.get(manager)
        val stateField = rootController.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(rootController) as MutableStateFlow<RootAccessSnapshot>
        stateFlow.value = snapshot
    }

    private fun mockEnv(sessionId: String = "session-test", turnId: String = "turn-test"): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(101L)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn("run-test")
        val wsDescriptor = AgentWorkspaceManager(context).buildWorkspaceDescriptor(null, "test-ws")
        Mockito.`when`(env.workspaceDescriptor).thenReturn(wsDescriptor)
        val permissionRequester = AgentPermissionRequester { _, _, _ -> true }
        Mockito.`when`(env.permissionRequester).thenReturn(permissionRequester)
        return env
    }

    @Test
    fun `privileged launch_activity defaults to background and fails closed without falling back to main`() = runBlocking {
        // 使用真实真实依赖，不 stub displayHandler
        val helper = SharedHelper(context, json)
        val workspaceManager = AgentWorkspaceManager(context)
        val handler = PrivilegedToolHandler(helper, workspaceManager)

        val call = AssistantToolCall(
            id = "call-launch-real",
            type = "function",
            function = AssistantToolCallFunction(
                name = "android_privileged_action",
                arguments = "{\"action\":\"package_control.launch_activity\",\"arguments\":{\"packageName\":\"com.example.app\"}}"
            )
        )
        val args = json.parseToJsonElement(call.function.arguments) as JsonObject
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = "android_privileged_action",
            displayName = "Privileged Action",
            toolType = "system",
        )
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val toolHandle = NoOpAgentRunControl.beginToolExecution("android_privileged_action", "call-launch-real")

        val result = handler.execute(call, args, descriptor, env, callback, toolHandle)

        // 默认路由后台虚拟屏；在无真实虚拟屏底层时严格失败闭环，绝对不隐式回退主屏 (Display.DEFAULT_DISPLAY)
        val isFailure = (result is ToolExecutionResult.Error) || (result is ToolExecutionResult.ContextResult && !result.success)
        assertTrue("必须失败闭环，绝对不成功在主屏执行", isFailure)
        val toolName = when (result) {
            is ToolExecutionResult.Error -> result.toolName
            is ToolExecutionResult.ContextResult -> result.toolName
            else -> fail("Unexpected result type: $result")
        }
        assertEquals("android_privileged_action", toolName)
    }

    @Test
    fun `privileged launch_activity without package name fails closed`() = runBlocking {
        val helper = SharedHelper(context, json)
        val workspaceManager = AgentWorkspaceManager(context)
        val handler = PrivilegedToolHandler(helper, workspaceManager)

        val call = AssistantToolCall(
            id = "call-launch-no-pkg",
            type = "function",
            function = AssistantToolCallFunction(
                name = "android_privileged_action",
                arguments = "{\"action\":\"package_control.launch_activity\",\"arguments\":{}}"
            )
        )
        val args = json.parseToJsonElement(call.function.arguments) as JsonObject
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = "android_privileged_action",
            displayName = "Privileged Action",
            toolType = "system",
        )
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val toolHandle = NoOpAgentRunControl.beginToolExecution("android_privileged_action", "call-launch-no-pkg")

        val result = handler.execute(call, args, descriptor, env, callback, toolHandle)

        assertTrue(result is ToolExecutionResult.Error)
        val errorResult = result as ToolExecutionResult.Error
        assertEquals("android_privileged_action", errorResult.toolName)
        assertTrue(errorResult.message.contains("package_name is required"))
    }
}
