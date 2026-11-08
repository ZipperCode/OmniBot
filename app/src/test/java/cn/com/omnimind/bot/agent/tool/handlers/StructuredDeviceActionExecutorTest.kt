package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.RootAccessSnapshot
import cn.com.omnimind.baselib.privileged.RootAccessStatus
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.runtime.AcpTurnGrantRegistry
import cn.com.omnimind.bot.agent.runtime.TurnPermissionScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 结构化受控设备动作执行器 (StructuredDeviceActionExecutor) 行为测试。
 *
 * 验收目标：
 * 1. 严格身份校验：无完整身份（conversationId/sessionId/turnId/ownerId/toolCallId/turnJob）明确错误，不造 0 / 临时 session。
 * 2. 设备白名单校验：仅承接 10 项固定设备白名单，非白名单拒绝。
 * 3. 恶意参数与权限覆盖拒绝：拒绝 confirmed / allow_task / isFullPower / 身份字段等非法覆盖，拒绝未知额外字段。
 * 4. 动作参数强校验：get_state / set_state / set_brightness / set_screen_timeout / set_volume / set_dnd / set_ringer_mode 字段与范围校验。
 * 5. 用户授权拦截：拒绝操作零写、审批中取消 CancellationException 向外传播零写。
 * 6. 审批后撤权保护：授权后核验失效（代次或连接失效）零写。
 * 7. 同 Turn 执行：执行作为 turnJob 子任务，支持取消且不自等待死锁。
 * 8. 读操作免授权：无 grant 读操作不弹权限申请，无 Root 保持现状不隐式 su。
 * 9. PrivilegedToolHandler 路由与无身份零降级集成测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class StructuredDeviceActionExecutorTest {

    private lateinit var context: Context
    private lateinit var executor: StructuredDeviceActionExecutor
    private val json = Json { ignoreUnknownKeys = true }
    private var previousRootSnapshot: RootAccessSnapshot? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        executor = StructuredDeviceActionExecutor(context)
        AcpTurnGrantRegistry.clear()
        val manager = PrivilegedCapabilityManager.get(context)
        previousRootSnapshot = manager.rootStatus()
        grantRootForTest(generation = 1L)
    }

    @After
    fun tearDown() {
        AcpTurnGrantRegistry.clear()
        previousRootSnapshot?.let { restoreRootForTest(it) }
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

    private fun grantRootForTest(generation: Long = 1L) {
        val manager = PrivilegedCapabilityManager.get(context)
        val rootField = PrivilegedCapabilityManager::class.java.getDeclaredField("root").apply { isAccessible = true }
        val rootController = rootField.get(manager)
        val stateField = rootController.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(rootController) as MutableStateFlow<RootAccessSnapshot>
        stateFlow.value = RootAccessSnapshot(enabled = true, status = RootAccessStatus.GRANTED, generation = generation)
    }

    private fun revokeRootForTest(newGeneration: Long = 2L) {
        val manager = PrivilegedCapabilityManager.get(context)
        val rootField = PrivilegedCapabilityManager::class.java.getDeclaredField("root").apply { isAccessible = true }
        val rootController = rootField.get(manager)
        val stateField = rootController.javaClass.getDeclaredField("mutableState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stateFlow = stateField.get(rootController) as MutableStateFlow<RootAccessSnapshot>
        stateFlow.value = RootAccessSnapshot(enabled = false, status = RootAccessStatus.DISABLED, generation = newGeneration)
    }

    private fun createContext(
        conversationId: Long = 1001L,
        sessionId: String = "session-test-01",
        turnId: String = "turn-test-01",
        ownerId: String = "owner-test-01",
        toolCallId: String = "tool-call-test-01",
        permissionRequester: AgentPermissionRequester = AgentPermissionRequester { _, _, _ -> true },
        isValid: () -> Boolean = { true },
        turnJob: Job = Job(),
    ): StructuredDeviceActionContext {
        return StructuredDeviceActionContext(
            conversationId = conversationId,
            sessionId = sessionId,
            turnId = turnId,
            ownerId = ownerId,
            toolCallId = toolCallId,
            permissionRequester = permissionRequester,
            isValid = isValid,
            turnJob = turnJob,
        )
    }

    // ==================== 1. 身份与生命周期完整性校验 ====================

    @Test
    fun rejectsMissingOrInvalidConversationId() = runBlocking {
        val actionContext = createContext(conversationId = 0L)
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("conversation_required", result.code)
        assertTrue(result.message.contains("conversationId"))
    }

    @Test
    fun rejectsBlankSessionId() = runBlocking {
        val actionContext = createContext(sessionId = "   ")
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("invalid_identity", result.code)
        assertTrue(result.message.contains("sessionId"))
    }

    @Test
    fun rejectsBlankTurnId() = runBlocking {
        val actionContext = createContext(turnId = "")
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("turn_inactive", result.code)
        assertTrue(result.message.contains("turnId"))
    }

    @Test
    fun rejectsBlankOwnerId() = runBlocking {
        val actionContext = createContext(ownerId = "")
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("invalid_identity", result.code)
        assertTrue(result.message.contains("ownerId"))
    }

    @Test
    fun rejectsBlankToolCallId() = runBlocking {
        val actionContext = createContext(toolCallId = "  ")
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("invalid_identity", result.code)
        assertTrue(result.message.contains("toolCallId"))
    }

    @Test
    fun rejectsInactiveOrCancelledTurnJob() = runBlocking {
        val cancelledJob = Job().apply { cancel() }
        val actionContext = createContext(turnJob = cancelledJob)
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("turn_inactive", result.code)
    }

    @Test
    fun rejectsInvalidatedConnectionContext() = runBlocking {
        val actionContext = createContext(isValid = { false })
        val result = executor.execute("device_control.get_display", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("session_unavailable", result.code)
    }

    // ==================== 2. 白名单动作校验 ====================

    @Test
    fun companionDeviceActionsContainsExactlyTenItems() {
        assertEquals(10, StructuredDeviceActionExecutor.deviceActions.size)
        val expected = setOf(
            "device_control.capabilities",
            "device_control.get_state",
            "device_control.set_state",
            "device_control.get_display",
            "device_control.set_brightness",
            "device_control.set_screen_timeout",
            "device_control.get_sound",
            "device_control.set_volume",
            "device_control.set_do_not_disturb",
            "device_control.set_ringer_mode",
        )
        assertEquals(expected, StructuredDeviceActionExecutor.deviceActions)
    }

    @Test
    fun rejectsNonAllowlistAction() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute("shell.exec", mapOf("command" to "id"), actionContext)
        assertFalse(result.success)
        assertEquals("unsupported_action", result.code)
        assertTrue(result.message.contains("不支持的设备动作"))
    }

    // ==================== 3. 恶意参数与权限覆盖字段拒绝 ====================

    @Test
    fun rejectsModelSuppliedConfirmedParameter() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_state",
            mapOf("target" to "wifi", "enabled" to "true", "confirmed" to "true"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
        assertTrue(result.message.contains("禁止的身份或授权覆盖字段"))
    }

    @Test
    fun rejectsModelSuppliedAllowTaskParameter() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_state",
            mapOf("target" to "wifi", "enabled" to "true", "allow_task" to "true"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
        assertTrue(result.message.contains("禁止的身份或授权覆盖字段"))
    }

    @Test
    fun rejectsModelSuppliedIsFullPowerParameter() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_state",
            mapOf("target" to "wifi", "enabled" to "true", "isFullPower" to "true"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsIdentityOverrideParameters() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_state",
            mapOf("target" to "wifi", "enabled" to "true", "sessionId" to "other-session"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsUnexpectedUnknownKeysForAction() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.get_display",
            mapOf("extraKey" to "evilValue"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
        assertTrue(result.message.contains("未知或非法字段"))
    }

    // ==================== 4. 动作专属参数内容强校验 ====================

    @Test
    fun rejectsInvalidTargetInGetState() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.get_state",
            mapOf("target" to "not_a_valid_hardware_target"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsInvalidEnabledInSetState() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_state",
            mapOf("target" to "wifi", "enabled" to "not_boolean"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsInvalidPercentInSetBrightness() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_brightness",
            mapOf("percent" to "150"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsOutOfRangeSecondsInSetScreenTimeout() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_screen_timeout",
            mapOf("seconds" to "5"), // minimum is 15
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsInvalidVolumeParameters() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_volume",
            mapOf("stream" to "media", "percent" to "-1"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsInvalidDndMode() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_do_not_disturb",
            mapOf("mode" to "unknown_mode"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    @Test
    fun rejectsInvalidRingerMode() = runBlocking {
        val actionContext = createContext()
        val result = executor.execute(
            "device_control.set_ringer_mode",
            mapOf("mode" to "loud"),
            actionContext
        )
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
    }

    // ==================== 5. 权限审批逻辑：拒绝 / 取消 / 批准后撤权 ====================

    @Test
    fun userRejectionReturnsPermissionDeniedWithZeroWrite() = runBlocking {
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)!!
        val beforeLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var requested = false
        val requester = AgentPermissionRequester { _, _, _ ->
            requested = true
            false // user rejects
        }
        val actionContext = createContext(permissionRequester = requester)

        val result = executor.execute(
            "device_control.set_volume",
            mapOf("stream" to "media", "percent" to "100"),
            actionContext
        )

        assertTrue("必须向用户发起权限请求", requested)
        assertFalse(result.success)
        assertEquals("permission_denied", result.code)
        assertTrue(result.message.contains("未获得用户授权"))
        val afterLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        assertEquals("必须证明零写入，音量等级保持不变", beforeLevel, afterLevel)
    }

    @Test
    fun cancellationDuringApprovalPropagatesWithoutExecution() = runBlocking {
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)!!
        val beforeLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var requested = false
        val requester = AgentPermissionRequester { _, _, _ ->
            requested = true
            throw CancellationException("Turn cancelled by user")
        }
        val actionContext = createContext(permissionRequester = requester)

        try {
            executor.execute(
                "device_control.set_volume",
                mapOf("stream" to "media", "percent" to "100"),
                actionContext
            )
            fail("必须向外抛出 CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Turn cancelled by user", e.message)
        }
        assertTrue(requested)
        val afterLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        assertEquals("必须证明零写入，音量等级保持不变", beforeLevel, afterLevel)
    }

    @Test
    fun revokedContextAfterApprovalReturnsSessionUnavailableWithZeroWrite() = runBlocking {
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)!!
        val beforeLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var valid = true
        var requested = false
        val requester = AgentPermissionRequester { _, _, _ ->
            requested = true
            valid = false // simulate session disconnect or token invalidation while waiting for user consent
            true // user approved
        }
        val actionContext = createContext(
            permissionRequester = requester,
            isValid = { valid },
        )

        val result = executor.execute(
            "device_control.set_volume",
            mapOf("stream" to "media", "percent" to "100"),
            actionContext
        )

        assertTrue(requested)
        assertFalse(result.success)
        assertEquals("session_unavailable", result.code)
        assertTrue(result.message.contains("会话或连接已失效"))
        val afterLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        assertEquals("必须证明零写入，音量等级保持不变", beforeLevel, afterLevel)
    }

    @Test
    fun revokedRootAccessAfterApprovalReturnsPrivilegedAccessChangedWithZeroWrite() = runBlocking {
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)!!
        val beforeLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        var requested = false
        val requester = AgentPermissionRequester { _, _, _ ->
            requested = true
            revokeRootForTest(newGeneration = 2L) // Root generation bumped/revoked while user was looking at approval dialog
            true // user approved
        }
        val actionContext = createContext(permissionRequester = requester)

        val result = executor.execute(
            "device_control.set_volume",
            mapOf("stream" to "media", "percent" to "100"),
            actionContext
        )

        assertTrue(requested)
        assertFalse(result.success)
        assertEquals("privileged_access_changed", result.code)
        assertTrue(result.message.contains("权限或后端代次已变化"))
        val afterLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        assertEquals("必须证明零写入，音量等级保持不变", beforeLevel, afterLevel)
    }

    @Test
    fun turnCancellationWhileSuspendedInApprovalCancelsExecutionAndJoinsWithinTimeout() = runBlocking {
        withTimeout(5000) {
            val audioManager = context.getSystemService(android.media.AudioManager::class.java)!!
            val beforeLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val parentTurnJob = Job()
            val requested = CompletableDeferred<Unit>()
            val approvalCompleted = AtomicBoolean(false)
            val requester = AgentPermissionRequester { _, _, _ ->
                requested.complete(Unit)
                delay(60_000) // simulates waiting for dialog that is never closed
                approvalCompleted.set(true)
                true
            }
            val actionContext = createContext(
                turnJob = parentTurnJob,
                permissionRequester = requester,
            )

            val runner = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                executor.execute(
                    "device_control.set_volume",
                    mapOf("stream" to "media", "percent" to "100"),
                    actionContext,
                )
            }

            withTimeout(3000) {
                requested.await()
            }
            assertFalse("Approval must still be pending", approvalCompleted.get())

            // Cancel turnJob while approval is suspended
            parentTurnJob.cancel(CancellationException("Turn cancelled during approval"))

            // Bounded join: must finish immediately without waiting 60s
            withTimeout(3000) {
                runner.join()
            }

            assertTrue("Runner must be cancelled when turnJob is cancelled", runner.isCancelled)
            assertFalse("Approval must never have completed", approvalCompleted.get())
            val afterLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            assertEquals("必须证明零写入，音量等级保持不变", beforeLevel, afterLevel)
        }
    }

    @Test
    fun callerCancellationCancelsChildJobViaBridgeAndJoinsWithinTimeout() = runBlocking {
        withTimeout(5000) {
            val audioManager = context.getSystemService(android.media.AudioManager::class.java)!!
            val beforeLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val parentTurnJob = Job()
            val requested = CompletableDeferred<Unit>()
            val approvalCompleted = AtomicBoolean(false)
            val requester = AgentPermissionRequester { _, _, _ ->
                requested.complete(Unit)
                delay(60_000) // simulates waiting for dialog that is never closed
                approvalCompleted.set(true)
                true
            }
            val actionContext = createContext(
                turnJob = parentTurnJob,
                permissionRequester = requester,
            )

            val callerJob = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                executor.execute(
                    "device_control.set_volume",
                    mapOf("stream" to "media", "percent" to "100"),
                    actionContext,
                )
            }

            withTimeout(3000) {
                requested.await()
            }
            assertFalse("Approval must still be pending", approvalCompleted.get())

            // HTTP caller cancels
            callerJob.cancel(CancellationException("HTTP caller disconnected"))

            // Bounded join: with onCancelling=true and invokeImmediately=true, caller cancels and joins immediately
            withTimeout(3000) {
                callerJob.join()
            }

            assertTrue("Caller job must be cancelled", callerJob.isCancelled)
            assertFalse("Approval must never have completed (zero writes)", approvalCompleted.get())
            val afterLevel = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            assertEquals("必须证明零写入，音量等级保持不变", beforeLevel, afterLevel)
        }
    }

    @Test
    fun turnGrantPreWriteCheckRejectsWhenAccessInvalidated() = runBlocking {
        var valid = true
        val sessionId = "session-granted-precheck"
        val turnId = "turn-granted-precheck"
        val scope = TurnPermissionScope(action = "device_control.set_volume", packageName = "media", isShell = false)
        AcpTurnGrantRegistry.recordGrant(
            sessionId = sessionId,
            turnId = turnId,
            scope = scope,
        )
        val actionContext = createContext(
            sessionId = sessionId,
            turnId = turnId,
            isValid = { valid },
        )

        valid = false // invalidate session before write
        val result = executor.execute("device_control.set_volume", mapOf("stream" to "media", "percent" to "50"), actionContext)
        assertFalse(result.success)
        assertEquals("session_unavailable", result.code)
    }

    // ==================== 6. 读操作与免审批测试 ====================

    @Test
    fun readActionsDoNotPromptForPermission() = runBlocking {
        val requested = AtomicBoolean(false)
        val requester = AgentPermissionRequester { _, _, _ ->
            requested.set(true)
            error("Queries must not ask for user consent")
        }
        val actionContext = createContext(permissionRequester = requester)

        // device_control.capabilities 是只读操作
        val result = executor.execute("device_control.capabilities", emptyMap(), actionContext)
        assertFalse("只读操作不得向用户申请权限", requested.get())
        // 在无 root 测试环境下应正常返回 capabilities 查询结果
        assertEquals("ok", result.code)
        assertTrue(result.success)
    }

    @Test
    fun turnGrantBypassesConfirmationPrompt() = runBlocking {
        val requested = AtomicBoolean(false)
        val requester = AgentPermissionRequester { _, _, _ ->
            requested.set(true)
            false
        }
        val sessionId = "session-granted-01"
        val turnId = "turn-granted-01"
        val scope = TurnPermissionScope(action = "device_control.set_volume", packageName = "media", isShell = false)

        // 注册已授权的 Scope
        AcpTurnGrantRegistry.recordGrant(
            sessionId = sessionId,
            turnId = turnId,
            scope = scope,
        )

        val actionContext = createContext(
            sessionId = sessionId,
            turnId = turnId,
            permissionRequester = requester,
        )

        val result = executor.execute("device_control.set_volume", mapOf("stream" to "media", "percent" to "50"), actionContext)

        assertFalse("已获得 TurnGrant 的操作不应再次弹窗申请权限", requested.get())
        assertTrue("已授权的 set_volume 应执行成功", result.success)
    }

    // ==================== 7. 同 Turn 执行作为子任务 ====================

    @Test
    fun executionBindsToTurnJobAndPropagatesCancellation() = runBlocking {
        val parentTurnJob = Job()
        val actionContext = createContext(
            turnJob = parentTurnJob,
            permissionRequester = { _, _, _ -> true },
        )

        // 取消父级 turnJob
        parentTurnJob.cancel(CancellationException("Host turn cancelled"))

        val result = executor.execute("device_control.capabilities", emptyMap(), actionContext)
        assertFalse(result.success)
        assertEquals("turn_inactive", result.code)
    }

    // ==================== 8. PrivilegedToolHandler 路由与完整身份校验集成 ====================

    @Test
    fun privilegedToolHandlerRejectsDeviceActionWhenIdentityIncomplete() = runBlocking {
        val helper = SharedHelper(context, json)
        val workspaceManager = AgentWorkspaceManager(context)
        val handler = PrivilegedToolHandler(helper, workspaceManager, executor)

        val call = AssistantToolCall(
            id = "call-1",
            type = "function",
            function = AssistantToolCallFunction(
                name = "android_privileged_action",
                arguments = "{\"action\":\"device_control.get_state\",\"arguments\":{\"target\":\"wifi\"}}"
            )
        )
        val args = json.parseToJsonElement(call.function.arguments) as JsonObject
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = "android_privileged_action",
            displayName = "Device Action",
            toolType = "system",
        )
        // 缺 conversationId 与 sessionId 的环境
        val incompleteEnv = DefaultAgentExecutionEnvironment(
            agentRunId = "run-01",
            conversationId = null, // missing!
            userMessage = "test",
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            workspaceDescriptor = workspaceManager.buildWorkspaceDescriptor(null, "test-ws"),
            resolvedSkills = emptyList(),
            workspaceManager = workspaceManager,
            workspaceMemoryService = org.mockito.Mockito.mock(WorkspaceMemoryService::class.java),
            conversationMode = "normal",
            terminalEnvironment = emptyMap(),
            acpSessionId = null, // missing!
            acpTurnId = null, // missing!
            permissionRequester = null,
        )

        val toolHandle = NoOpAgentRunControl.beginToolExecution("android_privileged_action", "tool-1")
        val callback = org.mockito.Mockito.mock(AgentCallback::class.java)

        val executionResult = handler.execute(call, args, descriptor, incompleteEnv, callback, toolHandle)
        assertTrue("缺少完整身份必须返回 Error", executionResult is ToolExecutionResult.Error)
    }
}
