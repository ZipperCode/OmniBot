package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import cn.com.omnimind.bot.agent.AgentPermissionRequester
import cn.com.omnimind.bot.agent.runtime.AcpTurnGrantRegistry
import cn.com.omnimind.bot.agent.runtime.LocalAcpRuntime
import cn.com.omnimind.bot.agent.runtime.TurnPermissionScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 结构化受控设备动作执行器。
 * 承接内置 android_privileged_action 命中的设备白名单，以及受管 ACP 会话的 MCP android_system_action 工具调用。
 *
 * 严格受 ACP 会话、Turn 任务及调用者身份约束，提供前置参数校验、权限审批与执行代次核验。
 */
internal class StructuredDeviceActionExecutor(
    private val context: Context,
) {
    companion object {
        /**
         * 允许受控执行的 10 项设备动作白名单。
         */
        val deviceActions: Set<String> = setOf(
            PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_DISPLAY,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_BRIGHTNESS,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_SCREEN_TIMEOUT,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_SOUND,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_VOLUME,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_DND,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_RINGER_MODE,
        )

        private val FORBIDDEN_OVERRIDE_KEYS = setOf(
            "confirmed",
            "allow_task",
            "allowtask",
            "isfullpower",
            "is_full_power",
            "allow_system_modifications",
            "allowsystemmodifications",
            "sessionid",
            "session_id",
            "conversationid",
            "conversation_id",
            "turnid",
            "turn_id",
            "ownerid",
            "owner_id",
            "toolcallid",
            "tool_call_id",
            "userid",
            "user_id",
            "requestid",
            "request_id",
        )

        private val ALLOWED_KEYS_PER_ACTION: Map<String, Set<String>> = mapOf(
            PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES to emptySet(),
            PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE to setOf("target"),
            PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE to setOf("target", "enabled"),
            PrivilegedActionPolicy.ACTION_DEVICE_GET_DISPLAY to emptySet(),
            PrivilegedActionPolicy.ACTION_DEVICE_SET_BRIGHTNESS to setOf("percent"),
            PrivilegedActionPolicy.ACTION_DEVICE_SET_SCREEN_TIMEOUT to setOf("seconds"),
            PrivilegedActionPolicy.ACTION_DEVICE_GET_SOUND to emptySet(),
            PrivilegedActionPolicy.ACTION_DEVICE_SET_VOLUME to setOf("stream", "percent"),
            PrivilegedActionPolicy.ACTION_DEVICE_SET_DND to setOf("mode"),
            PrivilegedActionPolicy.ACTION_DEVICE_SET_RINGER_MODE to setOf("mode"),
        )
    }

    suspend fun execute(
        action: String,
        arguments: Map<String, String>,
        context: StructuredDeviceActionContext,
    ): PrivilegedResult {
        // 1. 校验会话与调用者身份完整性，禁止缺身份造 0 或临时 session
        if (context.conversationId <= 0L) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "conversation_required",
                message = "缺少有效的对话会话标识 (conversationId)。",
            )
        }
        if (context.sessionId.isBlank()) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "invalid_identity",
                message = "缺少有效的会话标识 (sessionId)。",
            )
        }
        if (context.turnId.isBlank()) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "turn_inactive",
                message = "当前没有活动的 Turn 标识 (turnId)。",
            )
        }
        if (context.ownerId.isBlank()) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "invalid_identity",
                message = "缺少调用者所有者标识 (ownerId)。",
            )
        }
        if (context.toolCallId.isBlank()) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "invalid_identity",
                message = "缺少工具调用标识 (toolCallId)。",
            )
        }
        if (!context.turnJob.isActive) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "turn_inactive",
                message = "Turn 任务已结束或未处于活动状态。",
            )
        }
        if (!context.isValid()) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = action,
                success = false,
                code = "session_unavailable",
                message = "会话或连接已失效。",
            )
        }

        // 2. 校验设备动作白名单
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in deviceActions) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = normalizedAction,
                success = false,
                code = "unsupported_action",
                message = "不支持的设备动作：$action",
            )
        }

        // 3. 校验参数，严格拒绝注入身份与权限覆盖字段，且每 action 仅允许既有合法字段
        for (key in arguments.keys) {
            if (key.lowercase() in FORBIDDEN_OVERRIDE_KEYS) {
                return PrivilegedResult(
                    requestId = UUID.randomUUID().toString(),
                    action = normalizedAction,
                    success = false,
                    code = "invalid_arguments",
                    message = "参数中包含禁止的身份或授权覆盖字段：$key",
                )
            }
        }
        val allowedKeys = ALLOWED_KEYS_PER_ACTION[normalizedAction] ?: emptySet()
        val unknownKeys = arguments.keys.filter { it !in allowedKeys }
        if (unknownKeys.isNotEmpty()) {
            return PrivilegedResult(
                requestId = UUID.randomUUID().toString(),
                action = normalizedAction,
                success = false,
                code = "invalid_arguments",
                message = "参数包含未知或非法字段：$unknownKeys",
            )
        }

        // 动作专属参数内容校验
        when (normalizedAction) {
            PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE -> {
                val target = arguments["target"]?.trim()?.lowercase()
                if (target.isNullOrEmpty() || target !in PrivilegedActionPolicy.deviceStateTargets) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "target 不能为空且必须为受支持的设备状态：$target",
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE -> {
                val target = arguments["target"]?.trim()?.lowercase()
                val enabled = arguments["enabled"]?.trim()?.lowercase()
                if (target.isNullOrEmpty() || target !in PrivilegedActionPolicy.deviceStateTargets) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "target 不能为空且必须为受支持的设备状态：$target",
                    )
                }
                if (enabled.isNullOrEmpty() || enabled !in setOf("true", "false", "1", "0", "yes", "no", "on", "off", "enable", "disable", "enabled", "disabled")) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "enabled 不能为空且必须为布尔值：$enabled",
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_BRIGHTNESS -> {
                val percent = arguments["percent"]?.toIntOrNull()
                if (percent == null || percent !in 0..100) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "percent 必须在 0 到 100 之间。",
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_SCREEN_TIMEOUT -> {
                val seconds = arguments["seconds"]?.toIntOrNull()
                if (seconds == null || seconds !in 15..1800) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "seconds 必须在 15 到 1800 秒之间。",
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_VOLUME -> {
                val stream = arguments["stream"]?.trim()?.lowercase()
                val percent = arguments["percent"]?.toIntOrNull()
                if (stream.isNullOrEmpty() || percent == null || percent !in 0..100) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "stream 不能为空且 percent 必须在 0 到 100 之间。",
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_DND -> {
                val mode = arguments["mode"]?.trim()?.lowercase()
                if (mode !in setOf("off", "priority", "silent", "alarms")) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "mode 必须为 off, priority, silent 或 alarms。",
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_RINGER_MODE -> {
                val mode = arguments["mode"]?.trim()?.lowercase()
                if (mode !in setOf("normal", "silent", "vibrate")) {
                    return PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "invalid_arguments",
                        message = "mode 必须为 normal, silent 或 vibrate。",
                    )
                }
            }
        }

        // 4. 将 Access 预留、权限审批及设备动作执行整段绑定到真实 turnJob 派生的子任务中。
        // 使用 invokeOnCompletion 建立双向取消桥，确保 caller 取消（如 HTTP 客户端断开连接）时立即终止子任务，
        // 同时父级 turnJob 取消时也能即时中断挂起的审批弹窗与后续写操作。
        val callerJob = currentCoroutineContext()[Job]
        val childJob = Job(parent = context.turnJob)
        @OptIn(InternalCoroutinesApi::class)
        val callerBridge = callerJob?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) {
                childJob.cancel(CancellationException("Caller coroutine cancelled", cause))
            }
        }

        return try {
            withContext(childJob) {
                val access = PrivilegedCapabilityManager.get(this@StructuredDeviceActionExecutor.context).reserve(
                    ownerId = context.ownerId,
                    sessionId = null,
                    acpSessionId = context.sessionId,
                )
                val status = access.status

                val requiresConfirmation = PrivilegedActionPolicy.requiresConfirmation(normalizedAction)
                val isFullPower = LocalAcpRuntime.isSessionFullPower(context.sessionId)
                val scope = extractDeviceScope(normalizedAction, arguments)
                val turnGranted = AcpTurnGrantRegistry.isGranted(context.sessionId, context.turnId, scope)

                // 5. 权限审批逻辑：若需要确认且未获全权/Turn授权，则向用户发起审批
                if (requiresConfirmation && !isFullPower && !turnGranted) {
                    // 审批前校验调用者有效性、Turn 状态及 Root/后端状态
                    if (!context.isValid()) {
                        return@withContext PrivilegedResult(
                            requestId = UUID.randomUUID().toString(),
                            action = normalizedAction,
                            success = false,
                            code = "session_unavailable",
                            message = "会话或连接已失效，无法发起授权申请。",
                        )
                    }
                    if (!context.turnJob.isActive) {
                        return@withContext PrivilegedResult(
                            requestId = UUID.randomUUID().toString(),
                            action = normalizedAction,
                            success = false,
                            code = "turn_inactive",
                            message = "Turn 任务已停止，无法发起授权申请。",
                        )
                    }
                    if (!access.isValid()) {
                        return@withContext PrivilegedResult(
                            requestId = UUID.randomUUID().toString(),
                            action = normalizedAction,
                            success = false,
                            code = "privileged_access_changed",
                            message = "权限或后端状态已变化，无法发起授权申请。",
                        )
                    }

                    val statusLabel = if (status.isGranted()) status.label else "设备控制"
                    val title = "$statusLabel：高权限设备操作"
                    val targetResource = arguments["target"] ?: arguments["stream"] ?: arguments["mode"]
                    val detail = buildString {
                        append("执行动作：$normalizedAction")
                        targetResource?.let { append("\n目标：$it") }
                        arguments.forEach { (k, v) -> append("\n$k: $v") }
                        append("\n当前尚未执行。")
                    }

                    // 发起真实权限请求，取消异常 CancellationException 必须向外传播，不得吞没
                    val allowed = try {
                        context.permissionRequester.requestPermission(
                            toolCallId = context.toolCallId,
                            title = title,
                            detail = detail,
                            action = normalizedAction,
                            targetResource = targetResource,
                            rawInput = scope.toJsonObject(),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    }

                    if (!allowed) {
                        return@withContext PrivilegedResult(
                            requestId = UUID.randomUUID().toString(),
                            action = normalizedAction,
                            success = false,
                            code = "permission_denied",
                            message = "设备操作未获得用户授权，操作未执行。",
                        )
                    }
                }

                // 6. 每次执行/写入前无条件再次强校验 context.isValid()、turnJob.isActive 与 access.isValid()
                // 覆盖审批后撤权、审批挂起期间断连、以及 FullPower/TurnGrant 免审批分支下的代次失效保护
                if (!context.isValid()) {
                    return@withContext PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "session_unavailable",
                        message = "执行前会话或连接已失效，操作未执行。",
                    )
                }
                if (!context.turnJob.isActive) {
                    return@withContext PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "turn_inactive",
                        message = "执行前 Turn 任务已停止，操作未执行。",
                    )
                }
                if (!access.isValid()) {
                    return@withContext PrivilegedResult(
                        requestId = UUID.randomUUID().toString(),
                        action = normalizedAction,
                        success = false,
                        code = "privileged_access_changed",
                        message = "执行前权限或后端代次已变化，操作未执行。",
                    )
                }

                // 仅宿主在验证与审批通过后注入 confirmed=true
                val executionArgs = arguments + ("confirmed" to "true")
                access.executeAgentAction(
                    action = normalizedAction,
                    arguments = executionArgs,
                    requiresConfirmation = requiresConfirmation,
                    allowSystemModifications = false,
                    isFullPower = isFullPower,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            callerBridge?.dispose()
            childJob.complete()
        }
    }

    private fun extractDeviceScope(action: String, arguments: Map<String, String>): TurnPermissionScope {
        return TurnPermissionScope(
            action = action,
            packageName = arguments["target"] ?: arguments["stream"] ?: arguments["mode"],
            isShell = false,
        )
    }
}

/**
 * 结构化设备动作执行上下文。
 * 必须包含完整的调用者身份、权限申请通道、有效性检查闭包及所属 Turn 的真实 Job。
 */
internal data class StructuredDeviceActionContext(
    val conversationId: Long,
    val sessionId: String,
    val turnId: String,
    val ownerId: String,
    val toolCallId: String,
    val permissionRequester: AgentPermissionRequester,
    val isValid: () -> Boolean,
    val turnJob: Job,
)
