package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import cn.com.omnimind.baselib.privileged.management.PrivilegedManagementActions
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.runtime.AcpTurnGrantRegistry
import cn.com.omnimind.bot.agent.runtime.TurnPermissionScope
import cn.com.omnimind.bot.agent.runtime.isAcpFullAccessMode
import cn.com.omnimind.bot.task.runtime.TaskContinuationCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PrivilegedToolHandler internal constructor(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
    private val deviceActionExecutor: StructuredDeviceActionExecutor,
) : ToolHandler {
    constructor(
        helper: SharedHelper,
        workspaceManager: AgentWorkspaceManager,
    ) : this(helper, workspaceManager, StructuredDeviceActionExecutor(helper.context))
    override val toolNames: Set<String> = setOf(
        "android_privileged_action", "android_privileged_session_start", "android_privileged_session_exec",
        "android_privileged_session_read", "android_privileged_session_stop",
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val name = toolCall.function.name
        return try {
            val parsed = parse(name, args)
            if (name == "android_privileged_action" && parsed.action in StructuredDeviceActionExecutor.deviceActions) {
                val conversationId = env.conversationId
                val sessionId = env.acpSessionId
                val turnId = env.acpTurnId ?: env.acpSessionId?.let { AcpTurnGrantRegistry.activeTurnId(it) }
                val permissionRequester = env.permissionRequester
                val realTurnJob = env.turnJob

                if (conversationId == null || conversationId <= 0L ||
                    sessionId.isNullOrBlank() ||
                    turnId.isNullOrBlank() ||
                    permissionRequester == null ||
                    realTurnJob == null ||
                    !realTurnJob.isActive
                ) {
                    return ToolExecutionResult.Error(name, helper.localized("缺少有效会话、Turn 或授权通道，无法执行受控设备动作。"))
                }

                val actionContext = StructuredDeviceActionContext(
                    conversationId = conversationId,
                    sessionId = sessionId,
                    turnId = turnId,
                    ownerId = env.workspaceDescriptor.id,
                    toolCallId = toolHandle.toolCallId,
                    permissionRequester = permissionRequester,
                    isValid = { !toolHandle.isManualStopRequested() && (env.acpSessionId == sessionId) && realTurnJob.isActive },
                    turnJob = realTurnJob,
                )

                helper.reportToolProgress(
                    callback,
                    name,
                    if (helper.isEnglishLocale) "Executing device action" else "正在执行设备控制动作",
                    mapOf("action" to parsed.action),
                    toolHandle = toolHandle,
                )

                val result = deviceActionExecutor.execute(parsed.action, parsed.arguments, actionContext)
                if (!result.success && result.code in setOf(
                    "permission_denied", "privileged_access_changed", "invalid_identity",
                    "conversation_required", "turn_inactive", "session_unavailable",
                    "invalid_arguments", "unsupported_action"
                )) {
                    return ToolExecutionResult.Error(name, localizedMessage(result))
                }
                return projectResult(name, parsed, result, env.workspaceDescriptor)
            }
            val access = PrivilegedCapabilityManager.get(helper.context)
                .reserve(env.workspaceDescriptor.id, parsed.sessionId, env.acpSessionId)
            val status = access.status
            if (!status.isGranted()) return ToolExecutionResult.Error(name, helper.localized(
                if (parsed.sessionId == null) "高权限能力不可用，请在权限设置中启用 Root 或授权 Shizuku。"
                else "高权限 shell 会话已失效或不属于当前对话。"
            ))
            if (name == "android_privileged_action" &&
                !PrivilegedActionPolicy.isSupported(parsed.action, status.backend, arguments = parsed.arguments)) {
                return ToolExecutionResult.Error(name, helper.localized("当前后端不支持该动作：${parsed.action}"))
            }
            val isFullPower = env.acpSessionId?.let { cn.com.omnimind.bot.agent.runtime.LocalAcpRuntime.isSessionFullPower(it) } == true
            val scope = extractScope(parsed)
            val activeTurnId = env.acpTurnId ?: env.acpSessionId?.let { AcpTurnGrantRegistry.activeTurnId(it) }
            val turnGranted = activeTurnId != null && AcpTurnGrantRegistry.isGranted(env.acpSessionId, activeTurnId, scope)

            // 仅对绝对不可逾越的自身宿主保护强制先验拦截，系统级操作（reboot/分区/挂载）不作先验静态拒绝，交由用户授权
            if (parsed.command != null && PrivilegedActionPolicy.isHostProtectionViolation(parsed.command)) {
                return ToolExecutionResult.Error(name, helper.localized("Force-stopping the host app would disable its accessibility service."))
            }
            val targetResource = if (scope.isShell) {
                scope.command ?: scope.workingDirectory
            } else {
                scope.packageName ?: scope.path ?: scope.moduleId
            }
            val approved = if (isFullPower || turnGranted) {
                access.isValid()
            } else {
                PrivilegedToolConfirmation.request(
                    action = parsed.action,
                    toolCallId = toolHandle.toolCallId,
                    requester = env.permissionRequester,
                    valid = access::isValid,
                    title = if (helper.isEnglishLocale) "${status.label}: privileged operation" else "${status.label}：高权限操作",
                    detail = buildString {
                        append(if (helper.isEnglishLocale) "Backend: " else "执行后端：")
                        append(status.label)
                        append("\n${parsed.action}")
                        targetResource?.let { append("\nResource: $it") }
                        parsed.sessionId?.let { append("\nsessionId: $it") }
                        parsed.command?.let { append("\n$it") }
                        if (parsed.arguments.isNotEmpty()) append("\n${helper.encodeLocalizedPayload(parsed.arguments)}")
                        parsed.workingDirectory?.let { append("\nworkingDirectory: $it") }
                        if (parsed.environment.isNotEmpty()) append("\nenvironment: ${helper.encodeLocalizedPayload(parsed.environment)}")
                        append(if (helper.isEnglishLocale) "\nThis operation has not run yet." else "\n当前尚未执行。")
                    },
                    targetResource = targetResource,
                    scope = scope,
                )
            }
            if (!approved) return ToolExecutionResult.Error(name, helper.localized(
                if (access.isValid()) "高权限操作未获得用户授权，命令未执行。" else "权限或后端已变化，操作未执行。"
            ))
            toolHandle.throwIfStopRequested()

            val isSysModAction = parsed.action == PrivilegedManagementActions.ACTION_DEVICE_REBOOT ||
                parsed.action == PrivilegedManagementActions.ACTION_SYSTEM_REBOOT ||
                PrivilegedActionPolicy.isSystemModificationCommand(parsed.command)

            val allowSystemModifications = isFullPower ||
                (turnGranted && env.acpSessionId?.let { AcpTurnGrantRegistry.allowsSystemModifications(it, activeTurnId, scope) } == true) ||
                (approved && isSysModAction)
            if (parsed.action == PrivilegedManagementActions.ACTION_DEVICE_REBOOT ||
                parsed.action == PrivilegedManagementActions.ACTION_SYSTEM_REBOOT
            ) {
                val conversationId = env.conversationId
                val turnId = env.acpTurnId ?: activeTurnId
                if (conversationId == null || conversationId <= 0L || turnId.isNullOrBlank()) {
                    return ToolExecutionResult.Error(name, helper.localized("缺少有效会话或 Turn 标识，无法安全持久化重启检查点。"))
                }
                val checkpoint = TaskContinuationCoordinator.recordPendingReboot(
                    context = helper.context,
                    conversationId = conversationId,
                    sessionId = env.acpSessionId.orEmpty(),
                    turnId = turnId,
                    taskTitle = env.userMessage.take(40).ifBlank { "System Reboot Task" },
                    taskGoal = env.userMessage.ifBlank { "Reboot device and verify continuation" },
                    currentProgress = "准备执行系统重启动作: ${parsed.action}",
                    pendingSideEffects = listOf("Triggering system reboot (${parsed.action})"),
                    evidenceVerificationChecklist = listOf("Verify device reboot completed", "Verify system state after boot")
                )
                if (checkpoint == null) {
                    return ToolExecutionResult.Error(name, helper.localized("保存重启前任务检查点失败，已取消重启。"))
                }
            }

            helper.reportToolProgress(callback, name, "正在执行高权限操作", mapOf(
                "action" to parsed.action, "backend" to status.backend.name, "provider" to status.provider.name,
                "sessionId" to parsed.sessionId, "command" to parsed.command,
            ), toolHandle = toolHandle)
            val result = when (name) {
                "android_privileged_action" -> if (parsed.action == PrivilegedActionPolicy.ACTION_SHELL_EXEC) {
                    access.executeRawShell(
                        parsed.command.orEmpty(),
                        parsed.timeoutSeconds,
                        parsed.workingDirectory,
                        parsed.environment,
                        confirmed = true,
                        allowSystemModifications = allowSystemModifications,
                        isFullPower = isFullPower,
                    )
                } else access.executeAgentAction(
                    parsed.action,
                    parsed.arguments + ("confirmed" to "true"),
                    PrivilegedActionPolicy.requiresConfirmation(parsed.action),
                    allowSystemModifications = allowSystemModifications,
                    isFullPower = isFullPower,
                )
                "android_privileged_session_start" -> access.startPrivilegedSession(
                    parsed.sessionName, parsed.workingDirectory, parsed.environment, confirmed = true,
                )
                "android_privileged_session_exec" -> {
                    toolHandle.bindStopAction { access.stopPrivilegedSession(parsed.sessionId!!) }
                    access.execPrivilegedSession(
                        parsed.sessionId!!,
                        parsed.command!!,
                        parsed.timeoutSeconds,
                        confirmed = true,
                        allowSystemModifications = allowSystemModifications,
                        isFullPower = isFullPower,
                    )
                }
                "android_privileged_session_read" -> access.readPrivilegedSession(parsed.sessionId!!)
                "android_privileged_session_stop" -> access.stopPrivilegedSession(parsed.sessionId!!)
                else -> error("Unknown privileged tool")
            }
            projectResult(name, parsed, result, env.workspaceDescriptor)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { helper.errorResult(name, error.message, "高权限操作执行失败") }
    }

    private fun projectResult(name: String, parsed: Operation, result: PrivilegedResult, workspace: AgentWorkspaceDescriptor): ToolExecutionResult {
        if (result.requiresConfirmation || result.code == "confirmation_required") {
            return ToolExecutionResult.Error(name, helper.localized("高级动作确认状态不一致，命令未执行。"))
        }
        val artifacts = mutableListOf<ArtifactRef>()
        val sessionId = result.sessionId
        val transcript = result.transcript.ifBlank { result.output }
        if (sessionId != null && transcript.isNotBlank()) {
            val directory = File(File(workspaceManager.offloadsDirectory(workspace.id), "privileged_sessions"), sessionId)
            val logFile = File(directory, "latest.log")
            directory.mkdirs()
            logFile.writeText(transcript)
            artifacts += workspaceManager.buildArtifactForFile(logFile, name)
        }
        val message = localizedMessage(result)
        val payload = result.toMap().toMutableMap().apply {
            this["message"] = message
            parsed.sessionName?.let { this["sessionName"] = it }
            if (name == "android_privileged_session_read") {
                this["content"] = transcript
                this["contentLength"] = transcript.length
            }
            artifacts.firstOrNull()?.let {
                this["logPath"] = it.workspacePath; this["androidLogPath"] = it.androidPath; this["logUri"] = it.uri
            }
        }
        val json = helper.encodeLocalizedPayload(payload)
        return ToolExecutionResult.ContextResult(
            toolName = name, summaryText = message, previewJson = json, rawResultJson = json,
            success = result.success, artifacts = artifacts, workspaceId = workspace.id,
        )
    }

    private fun localizedMessage(result: PrivilegedResult): String {
        if (helper.isEnglishLocale) return result.message
        return when (result.code) {
            "ok" -> if (result.observation?.containsKey("verified") == true) "设备状态修改已验证。" else "高权限操作已完成。"
            "state_change_unconfirmed" -> "修改请求已提交，但尚未确认目标状态；请先查询状态，不要直接重复操作。"
            "state_unavailable" -> "无法读取当前设备状态。"
            "privileged_access_changed" -> "权限或后端已变化，本次操作已停止。"
            "root_denied" -> "系统拒绝了此操作，请检查 Root 或 Shizuku 授权。"
            "permission_denied" -> if (result.message.isNotBlank()) helper.localized(result.message) else "系统拒绝了此操作，请检查 Root 或 Shizuku 授权。"
            "invalid_arguments" -> if (result.message.isNotBlank()) helper.localized(result.message) else "动作参数无效。"
            "invalid_identity", "conversation_required", "turn_inactive", "session_unavailable" -> helper.localized(result.message)
            "root_unavailable" -> "设备上的直接 Root 不可用。"
            "unsupported_action" -> "当前设备、系统版本或执行后端不支持该操作。"
            "capability_probe_failed" -> "系统命令能力检查失败，尚无法确认是否支持；请查看具体错误。"
            "capability_probe_incomplete" -> "系统命令帮助输出不完整，尚无法确认是否支持该操作。"
            "timeout", "service_timeout" -> "高权限操作超时，执行已停止；请先检查设备状态。"
            "session_not_found" -> "高权限 shell 会话不存在或已失效。"
            "session_busy" -> "高权限 shell 会话仍有命令在执行。"
            "service_call_failed" -> "高权限服务连接失败；本次操作未自动重试，请先检查设备状态。"
            else -> helper.localized(result.message)
        }
    }

    private data class Operation(
        val action: String, val arguments: Map<String, String> = emptyMap(), val command: String? = null,
        val timeoutSeconds: Int? = null, val workingDirectory: String? = null,
        val environment: Map<String, String> = emptyMap(), val sessionId: String? = null, val sessionName: String? = null,
    )

    private fun parse(name: String, args: JsonObject): Operation {
        val body = if (name == "android_privileged_action") args["arguments"] as? JsonObject ?: JsonObject(emptyMap()) else args
        fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val action = when (name) {
            "android_privileged_action" -> requireNotNull(args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) { "action is required" }
            "android_privileged_session_start" -> SharedHelper.PRIVILEGED_SESSION_START_ACTION
            "android_privileged_session_exec" -> SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION
            "android_privileged_session_read" -> "shell.session_read"
            "android_privileged_session_stop" -> "shell.session_stop"
            else -> error("Unknown privileged tool")
        }
        val sessionId = if (name in setOf("android_privileged_session_exec", "android_privileged_session_read", "android_privileged_session_stop")) {
            requireNotNull(text("sessionId")) { "sessionId is required" }
        } else null
        val command = text("command")
        if (action == PrivilegedActionPolicy.ACTION_SHELL_EXEC || name == "android_privileged_session_exec") require(!command.isNullOrEmpty()) { "command is required" }
        return Operation(
            action = action,
            arguments = if (name == "android_privileged_action") helper.jsonObjectToStringMap(
                body,
                excludedKeys = setOf("environment", "confirmed", "allow_task", "isFullPower", "allow_system_modifications")
            ) else emptyMap(),
            command = command,
            timeoutSeconds = body["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.also { require(it > 0) { "timeoutSeconds must be positive" } },
            workingDirectory = text("workingDirectory"), environment = helper.parseEnvironmentMap(body["environment"] as? JsonObject),
            sessionId = sessionId, sessionName = text("sessionName"),
        )
    }

    private fun extractScope(parsed: Operation): TurnPermissionScope {
        val isShell = parsed.action == PrivilegedActionPolicy.ACTION_SHELL_EXEC ||
            parsed.action == SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION ||
            parsed.action == SharedHelper.PRIVILEGED_SESSION_START_ACTION ||
            parsed.action.startsWith("shell.session")
        val packageName = parsed.arguments["packageName"]
            ?: parsed.arguments["package_name"]
            ?: parsed.arguments["package"]
            ?: parsed.arguments["pkg_name"]
            ?: parsed.arguments["target_package"]
            ?: parsed.arguments["targetPackage"]
            ?: parsed.arguments["module_pkg_name"]
        val path = parsed.arguments["path"]
            ?: parsed.arguments["file_path"]
            ?: parsed.arguments["filePath"]
            ?: parsed.arguments["apk_path"]
            ?: parsed.arguments["zip_path"]
            ?: parsed.arguments["file"]
            ?: parsed.arguments["target"]
        val moduleId = parsed.arguments["moduleId"]
            ?: parsed.arguments["module_id"]
            ?: parsed.arguments["module"]
            ?: parsed.arguments["id"]
        return TurnPermissionScope(
            action = parsed.action,
            packageName = packageName?.trim()?.ifEmpty { null },
            path = path?.trim()?.ifEmpty { null },
            moduleId = moduleId?.trim()?.ifEmpty { null },
            command = parsed.command?.trim()?.ifEmpty { null },
            workingDirectory = parsed.workingDirectory?.trim()?.ifEmpty { null },
            environment = parsed.environment,
            isShell = isShell,
        )
    }
}

internal object PrivilegedToolConfirmation {
    suspend fun request(
        action: String,
        toolCallId: String,
        requester: AgentPermissionRequester?,
        valid: () -> Boolean,
        title: String,
        detail: String,
        targetResource: String? = null,
        scope: TurnPermissionScope? = null,
    ): Boolean {
        if (!valid()) return false
        if (!PrivilegedActionPolicy.requiresConfirmation(action)) return true
        val rawInput = scope?.toJsonObject()
        val allowed = requester?.requestPermission(toolCallId, title, detail, action, targetResource, rawInput) == true
        return allowed && valid()
    }
}
