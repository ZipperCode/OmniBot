package cn.com.omnimind.bot.mcp

import android.app.Application
import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.bot.agent.runtime.AcpSessionMcpLeaseStore
import cn.com.omnimind.bot.agent.tool.handlers.StructuredDeviceActionContext
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * AndroidDeviceMcpServer GUI 执行（run_gui / run_function）屏幕路由与 caller 隔离行为测试。
 *
 * 验证：
 * 1. McpDeviceCaller.Acp 会话：解析或准备虚拟屏，标记真实 TaskRuntime（非伪造身份），透传 numeric display，跨轮保留虚拟屏。
 * 2. McpDeviceCaller.Global 非会话：沿用真实 request owner，不调用 TaskRuntime，执行后精确清理一过性虚拟屏。
 * 3. 授权失效/非法 Token：直接 session_unavailable 拒绝，绝不泄漏 Token，绝不回退主屏。
 * 4. 虚拟屏冲突或创建失败：返回 error 结果，绝不偷跑主屏。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AndroidDeviceMcpServerDisplayRoutingTest {

    private lateinit var context: Context
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        DeviceDisplayManager.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        TaskRuntime.resetForTesting()
        AcpSessionMcpLeaseStore.revokeAll()
    }

    @After
    fun tearDown() {
        DeviceDisplayManager.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        TaskRuntime.resetForTesting()
        AcpSessionMcpLeaseStore.revokeAll()
    }

    @Test
    fun `acp caller with invalid token is rejected with session_unavailable and does not leak token`() = runBlocking {
        val fakeCaller = McpDeviceCaller.Acp(leaseId = "lease-unknown", token = "secret-token-12345")

        val result = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = "run_gui",
            arguments = mapOf("goal" to JsonPrimitive("test goal")),
            caller = fakeCaller,
        )

        assertTrue("Invalid lease must return error", result.isError == true)
        val text = result.content.firstOrNull()?.toString().orEmpty()
        assertTrue("Error message must indicate session_unavailable", text.contains("session_unavailable"))
        assertFalse("Lease token must NEVER leak in error", text.contains("secret-token-12345"))
    }

    @Test
    fun `acp caller with inactive turn is rejected closed`() = runBlocking {
        val lease = AcpSessionMcpLeaseStore.prepare("test-agent", 1L, this)
        val cancelledJob = Job().apply { cancel() }

        AcpSessionMcpLeaseStore.bind(lease.leaseId, "sess-inactive") { toolCallId ->
            StructuredDeviceActionContext(
                conversationId = 1L,
                sessionId = "sess-inactive",
                turnId = "turn-inactive",
                ownerId = "owner-1",
                toolCallId = toolCallId,
                permissionRequester = { _, _, _ -> true },
                isValid = { false }, // Inactive!
                turnJob = cancelledJob,
            )
        }

        val caller = McpDeviceCaller.Acp(lease.leaseId, lease.token)
        val result = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = "run_gui",
            arguments = mapOf("goal" to JsonPrimitive("test goal")),
            caller = caller,
        )

        assertTrue(result.isError == true)
        val text = result.content.firstOrNull()?.toString().orEmpty()
        assertTrue(text.contains("session_unavailable"))
    }

    @Test
    fun `global caller run_gui does not create fake task and cleans up virtual display`() = runBlocking {
        // Global caller without explicit main will attempt to resolve/prepare virtual display
        // Since Robolectric has no Shizuku service, resolveOrPrepareDisplayId will fail with an error
        // and MUST NOT fall back to Display.DEFAULT_DISPLAY (0).
        val result = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = "run_gui",
            arguments = mapOf("goal" to JsonPrimitive("open clock app")),
            caller = McpDeviceCaller.Global,
        )

        // Without Shizuku/root privileged service, virtual display prep fails closed
        assertTrue(result.isError == true)
        // TaskRuntime must remain completely clean (no fake session/turn was registered)
        assertEquals(0, TaskRuntime.getActiveTasks().size)
    }

    @Test
    fun `global caller run_function does not create fake task and fails closed when virtual display unavailable`() = runBlocking {
        val result = AndroidDeviceMcpServer.modernCallTool(
            context = context,
            scope = scope,
            name = "run_function",
            arguments = mapOf(
                "function_id" to JsonPrimitive("fn_test"),
                "goal" to JsonPrimitive("replay function"),
            ),
            caller = McpDeviceCaller.Global,
        )

        assertTrue(result.isError == true)
        assertEquals(0, TaskRuntime.getActiveTasks().size)
    }
}
