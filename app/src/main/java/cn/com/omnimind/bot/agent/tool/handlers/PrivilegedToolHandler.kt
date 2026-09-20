package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.PrivilegedStatus
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class PrivilegedToolHandler internal constructor(
    private val helper: SharedHelper,
    private val workspaceManager: cn.com.omnimind.bot.agent.AgentWorkspaceManager,
    private val terminalToolHandler: TerminalToolHandler,
    private val privilegedGateway: PrivilegedGateway = CapabilityManagerGateway(helper)
) : ToolHandler {
    internal interface PrivilegedGateway {
        fun getStatus(): PrivilegedStatus
        suspend fun executeAction(action: String, arguments: Map<String, String>, requiresConfirmation: Boolean): PrivilegedResult
        suspend fun executeShell(command: String, timeoutSeconds: Int?, workingDirectory: String?, environment: Map<String, String>, confirmed: Boolean): PrivilegedResult
        suspend fun startSession(sessionName: String?, workingDirectory: String?, environment: Map<String, String>, confirmed: Boolean): PrivilegedResult
        suspend fun execSession(sessionId: String, command: String, timeoutSeconds: Int?, confirmed: Boolean): PrivilegedResult
        suspend fun readSession(sessionId: String): PrivilegedResult
        suspend fun stopSession(sessionId: String): PrivilegedResult
    }

    private class CapabilityManagerGateway(helper: SharedHelper) : PrivilegedGateway {
        private val manager = PrivilegedCapabilityManager.get(helper.context)
        override fun getStatus() = manager.getStatus()
        override suspend fun executeAction(action: String, arguments: Map<String, String>, requiresConfirmation: Boolean) = manager.executeAgentAction(action, arguments, requiresConfirmation)
        override suspend fun executeShell(command: String, timeoutSeconds: Int?, workingDirectory: String?, environment: Map<String, String>, confirmed: Boolean) = manager.executeRawShell(command, timeoutSeconds, workingDirectory, environment, confirmed)
        override suspend fun startSession(sessionName: String?, workingDirectory: String?, environment: Map<String, String>, confirmed: Boolean) = manager.startPrivilegedSession(sessionName = sessionName, workingDirectory = workingDirectory, environment = environment, confirmed = confirmed)
        override suspend fun execSession(sessionId: String, command: String, timeoutSeconds: Int?, confirmed: Boolean) = manager.execPrivilegedSession(sessionId, command, timeoutSeconds, confirmed = confirmed)
        override suspend fun readSession(sessionId: String) = manager.readPrivilegedSession(sessionId)
        override suspend fun stopSession(sessionId: String) = manager.stopPrivilegedSession(sessionId)
    }
    override val toolNames: Set<String> = setOf(
        "android_privileged_action",
        "android_privileged_session_start",
        "android_privileged_session_exec",
        "android_privileged_session_read",
        "android_privileged_session_stop"
    )

    data class AndroidPrivilegedArgs(
        val action: String,
        val arguments: Map<String, String>,
        val command: String?,
        val timeoutSeconds: Int?,
        val workingDirectory: String?,
        val environment: Map<String, String>
    )

    data class PrivilegedSessionStartArgs(
        val sessionName: String?,
        val workingDirectory: String?,
        val environment: Map<String, String>
    )

    data class PrivilegedSessionExecArgs(
        val sessionId: String,
        val command: String,
        val timeoutSeconds: Int?
    )

    data class PrivilegedSessionReadArgs(
        val sessionId: String
    )

    data class PrivilegedSessionStopArgs(
        val sessionId: String
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "android_privileged_action" -> executeAndroidPrivilegedAction(args, callback, env, toolHandle.toolCallId)
            "android_privileged_session_start" -> executeAndroidPrivilegedSessionStart(args, env.workspaceDescriptor, callback, env, toolHandle.toolCallId)
            "android_privileged_session_exec" -> executeAndroidPrivilegedSessionExec(args, env.workspaceDescriptor, callback, toolHandle, env)
            "android_privileged_session_read" -> executeAndroidPrivilegedSessionRead(
                args,
                env.workspaceDescriptor,
                callback,
            )
            "android_privileged_session_stop" -> executeAndroidPrivilegedSessionStop(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown privileged tool")
        }
    }

    private fun privilegedSessionWorkspaceId(workspaceId: String): String {
        return "${SharedHelper.PRIVILEGED_SESSION_WORKSPACE_PREFIX}$workspaceId"
    }

    private fun rememberOwnedPrivilegedSession(workspaceId: String, sessionId: String, sessionName: String?) {
        terminalToolHandler.rememberOwnedTerminalSession(privilegedSessionWorkspaceId(workspaceId), sessionId, sessionName)
    }

    private fun isOwnedPrivilegedSession(workspaceId: String, sessionId: String): Boolean {
        return terminalToolHandler.isOwnedTerminalSession(privilegedSessionWorkspaceId(workspaceId), sessionId)
    }

    private fun forgetOwnedPrivilegedSession(sessionId: String) {
        terminalToolHandler.forgetOwnedTerminalSession(sessionId)
    }

    private fun privilegedSessionDirectory(workspace: AgentWorkspaceDescriptor, sessionId: String): File {
        return File(File(workspaceManager.offloadsDirectory(workspace.id), "privileged_sessions"), sessionId)
    }

    private fun persistPrivilegedSessionTranscript(workspace: AgentWorkspaceDescriptor, sessionId: String, transcript: String, sourceTool: String): ArtifactRef {
        val logFile = File(privilegedSessionDirectory(workspace, sessionId), "latest.log")
        logFile.parentFile?.mkdirs()
        logFile.writeText(transcript)
        return workspaceManager.buildArtifactForFile(logFile, sourceTool)
    }

    private suspend fun executeAndroidPrivilegedAction(
        args: JsonObject,
        callback: AgentCallback,
        env: AgentExecutionEnvironment,
        toolCallId: String,
    ): ToolExecutionResult {
        val toolName = "android_privileged_action"
        return try {
            val parsed = parseAndroidPrivilegedArgs(args)
            val status = privilegedGateway.getStatus()
            if (!status.granted) {
                return helper.permissionRequiredResult(callback, listOf("高权限来源"))
            }
            if (!PrivilegedActionPolicy.isSupported(parsed.action, status.authority, arguments = parsed.arguments)) {
                return ToolExecutionResult.Error(toolName, helper.localized("当前实测权限不支持该动作：${parsed.action}"))
            }
            // 确认只由 ACP 权限请求持有；模型参数永远不能绕过该门禁。
            var effectiveArgs = parsed
            if (PrivilegedActionPolicy.requiresConfirmation(parsed.action)) {
                PrivilegedActionPolicy.blockedCommandReason(parsed.command)?.let { reason ->
                    return ToolExecutionResult.Error(toolName, helper.localized(reason))
                }
                val confirmation = requestPrivilegedConfirmation(
                    action = parsed.action,
                    command = parsed.command,
                    env = env,
                    toolCallId = toolCallId,
                    toolName = toolName,
                )
                if (confirmation != null) return confirmation
                effectiveArgs = parsed.copy(
                    arguments = parsed.arguments + ("confirmed" to "true")
                )
            }
            helper.reportToolProgress(
                callback, toolName, "正在执行高级动作",
                mapOf("action" to parsed.action, "authority" to status.authority.name, "command" to parsed.command, "availableActions" to status.availableActions)
            )
            val result = if (parsed.action == PrivilegedActionPolicy.ACTION_SHELL_EXEC) {
                privilegedGateway.executeShell(
                    command = effectiveArgs.command.orEmpty(),
                    timeoutSeconds = effectiveArgs.timeoutSeconds,
                    workingDirectory = effectiveArgs.workingDirectory,
                    environment = effectiveArgs.environment,
                    confirmed = effectiveArgs.arguments["confirmed"] == "true"
                )
            } else {
                privilegedGateway.executeAction(
                    action = effectiveArgs.action,
                    arguments = effectiveArgs.arguments,
                    requiresConfirmation = PrivilegedActionPolicy.requiresConfirmation(effectiveArgs.action)
                )
            }
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                // Confirmation is owned by the ACP permission request above.
                // A backend asking for it again means the gate and backend
                // disagree; do not create a second Clarify lifecycle.
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("高级动作确认状态不一致，命令未执行。"),
                )
            }
            val payload = result.toMap().toMutableMap().apply { this["message"] = localizedPrivilegedMessage(result) }
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (result.success) "已执行高级动作：${result.action}" else "高级动作执行失败：${result.action}"),
                previewJson = payloadJson, rawResultJson = payloadJson, success = result.success
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ToolExecutionResult.Error(toolName, helper.localized(e.message ?: "高级动作执行失败")) }
    }

    private suspend fun executeAndroidPrivilegedSessionStart(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
        env: AgentExecutionEnvironment,
        toolCallId: String,
    ): ToolExecutionResult {
        val toolName = "android_privileged_session_start"
        return try {
            val parsed = parsePrivilegedSessionStartArgs(args)
            val status = privilegedGateway.getStatus()
            if (!status.granted) { return helper.permissionRequiredResult(callback, listOf("高权限来源")) }
            val confirmation = requestPrivilegedConfirmation(
                action = SharedHelper.PRIVILEGED_SESSION_START_ACTION,
                env = env,
                toolCallId = toolCallId,
                toolName = toolName,
            )
            if (confirmation != null) return confirmation
            helper.reportToolProgress(callback, toolName, "正在启动高权限会话", mapOf("authority" to status.authority.name, "workingDirectory" to parsed.workingDirectory))
            val result = privilegedGateway.startSession(sessionName = parsed.sessionName, workingDirectory = parsed.workingDirectory, environment = parsed.environment, confirmed = true)
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("高权限会话确认状态不一致，会话未启动。"),
                )
            }
            val artifacts = mutableListOf<ArtifactRef>()
            if (result.success) {
                result.sessionId?.takeIf { it.isNotBlank() }?.let { sessionId ->
                    rememberOwnedPrivilegedSession(workspaceId = workspace.id, sessionId = sessionId, sessionName = parsed.sessionName)
                    artifacts += persistPrivilegedSessionTranscript(workspace = workspace, sessionId = sessionId, transcript = result.transcript, sourceTool = toolName)
                }
            }
            val payload = result.toMap().toMutableMap().apply {
                this["message"] = localizedPrivilegedMessage(result)
                this["sessionName"] = parsed.sessionName
                artifacts.firstOrNull()?.let { artifact -> this["logPath"] = artifact.workspacePath; this["androidLogPath"] = artifact.androidPath; this["logUri"] = artifact.uri }
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (result.success) helper.localized("高权限会话已启动：${result.sessionId.orEmpty()}") else localizedPrivilegedMessage(result),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, artifacts = artifacts, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "高权限会话启动失败") }
    }

    private suspend fun executeAndroidPrivilegedSessionExec(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
        env: AgentExecutionEnvironment,
    ): ToolExecutionResult {
        val toolName = "android_privileged_session_exec"
        return try {
            val parsed = parsePrivilegedSessionExecArgs(args)
            require(isOwnedPrivilegedSession(workspace.id, parsed.sessionId)) { "高权限会话不存在或不属于当前 workspace：${parsed.sessionId}" }
            val status = privilegedGateway.getStatus()
            if (!status.granted) { return helper.permissionRequiredResult(callback, listOf("高权限来源")) }
            PrivilegedActionPolicy.blockedCommandReason(parsed.command)?.let { reason ->
                return ToolExecutionResult.Error(toolName, helper.localized(reason))
            }
            val confirmation = requestPrivilegedConfirmation(
                action = SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION,
                command = parsed.command,
                env = env,
                toolCallId = toolHandle.toolCallId,
                toolName = toolName,
            )
            if (confirmation != null) return confirmation
            helper.reportToolProgress(callback, toolName, "正在执行高权限命令", mapOf("authority" to status.authority.name, "sessionId" to parsed.sessionId, "command" to parsed.command), toolHandle = toolHandle)
            toolHandle.bindStopAction { privilegedGateway.stopSession(parsed.sessionId) }
            val result = privilegedGateway.execSession(sessionId = parsed.sessionId, command = parsed.command, timeoutSeconds = parsed.timeoutSeconds, confirmed = true)
            if (result.requiresConfirmation || result.code == "confirmation_required") {
                return ToolExecutionResult.Error(
                    toolName,
                    helper.localized("高权限会话确认状态不一致，命令未执行。"),
                )
            }
            if (result.code == "session_not_found") { forgetOwnedPrivilegedSession(parsed.sessionId) }
            val transcript = result.transcript.ifBlank { result.output }
            val artifacts = mutableListOf<ArtifactRef>()
            if (transcript.isNotBlank()) { artifacts += persistPrivilegedSessionTranscript(workspace = workspace, sessionId = parsed.sessionId, transcript = transcript, sourceTool = toolName) }
            val payload = result.toMap().toMutableMap().apply {
                this["message"] = localizedPrivilegedMessage(result)
                artifacts.firstOrNull()?.let { artifact -> this["logPath"] = artifact.workspacePath; this["androidLogPath"] = artifact.androidPath; this["logUri"] = artifact.uri }
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (result.success) helper.localized("高权限命令执行完成") else localizedPrivilegedMessage(result),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, artifacts = artifacts, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "高权限会话命令执行失败") }
    }

    private suspend fun executeAndroidPrivilegedSessionRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback,
    ): ToolExecutionResult {
        val toolName = "android_privileged_session_read"
        return try {
            val parsed = parsePrivilegedSessionReadArgs(args)
            require(isOwnedPrivilegedSession(workspace.id, parsed.sessionId)) { "高权限会话不存在或不属于当前 workspace：${parsed.sessionId}" }
            val status = privilegedGateway.getStatus()
            if (!status.granted) { return helper.permissionRequiredResult(callback, listOf("高权限来源")) }
            val result = privilegedGateway.readSession(sessionId = parsed.sessionId)
            if (result.code == "session_not_found") { forgetOwnedPrivilegedSession(parsed.sessionId) }
            val transcript = result.transcript.ifBlank { result.output }
            val artifacts = mutableListOf<ArtifactRef>()
            if (transcript.isNotBlank()) { artifacts += persistPrivilegedSessionTranscript(workspace = workspace, sessionId = parsed.sessionId, transcript = transcript, sourceTool = toolName) }
            val payload = result.toMap().toMutableMap().apply {
                this["message"] = localizedPrivilegedMessage(result)
                this["content"] = transcript; this["contentLength"] = transcript.length
                artifacts.firstOrNull()?.let { artifact -> this["logPath"] = artifact.workspacePath; this["androidLogPath"] = artifact.androidPath; this["logUri"] = artifact.uri }
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (!result.success) localizedPrivilegedMessage(result) else if (transcript.isBlank()) helper.localized("高权限会话暂无输出") else helper.localized("已读取高权限会话输出"),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, artifacts = artifacts, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "读取高权限会话失败") }
    }

    private suspend fun executeAndroidPrivilegedSessionStop(args: JsonObject, workspace: AgentWorkspaceDescriptor, callback: AgentCallback): ToolExecutionResult {
        val toolName = "android_privileged_session_stop"
        return try {
            helper.reportToolProgress(callback, toolName, "正在结束高权限会话")
            val parsed = parsePrivilegedSessionStopArgs(args)
            require(isOwnedPrivilegedSession(workspace.id, parsed.sessionId)) { "高权限会话不存在或不属于当前 workspace：${parsed.sessionId}" }
            val result = privilegedGateway.stopSession(parsed.sessionId)
            forgetOwnedPrivilegedSession(parsed.sessionId)
            val payload = result.toMap().toMutableMap().apply { this["message"] = localizedPrivilegedMessage(result) }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = if (result.success) helper.localized("高权限会话已结束：${parsed.sessionId}") else localizedPrivilegedMessage(result),
                previewJson = helper.encodeLocalizedPayload(payload), rawResultJson = helper.encodeLocalizedPayload(payload),
                success = result.success, workspaceId = workspace.id
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { helper.errorResult(toolName, e.message, "结束高权限会话失败") }
    }

    private fun parseAndroidPrivilegedArgs(
        args: JsonObject,
    ): AndroidPrivilegedArgs {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        require(action.isNotEmpty()) { "action 不能为空" }
        val rawArguments = args["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        // 即使调用方绕过 JSON Schema，也丢弃模型伪造的确认位。
        val trustedArguments = JsonObject(rawArguments.filterKeys { it != "confirmed" })
        return AndroidPrivilegedArgs(
            action = action,
            arguments = helper.jsonObjectToStringMap(trustedArguments, excludedKeys = setOf("environment")),
            command = trustedArguments["command"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            timeoutSeconds = trustedArguments["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            workingDirectory = trustedArguments["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            environment = helper.parseEnvironmentMap(trustedArguments["environment"] as? JsonObject)
        )
    }

    private fun parsePrivilegedSessionStartArgs(args: JsonObject): PrivilegedSessionStartArgs {
        require(args.keys.all { it in SESSION_START_INPUT_KEYS }) { "高权限会话启动参数包含不支持的字段" }
        return PrivilegedSessionStartArgs(
            sessionName = args["sessionName"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            workingDirectory = args["workingDirectory"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            environment = helper.parseEnvironmentMap(args["environment"] as? JsonObject)
        )
    }

    private fun parsePrivilegedSessionExecArgs(
        args: JsonObject,
    ): PrivilegedSessionExecArgs {
        require(args.keys.all { it in SESSION_EXEC_INPUT_KEYS }) { "高权限会话命令参数包含不支持的字段" }
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        val command = args["command"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        require(command.isNotEmpty()) { "缺少 command" }
        return PrivilegedSessionExecArgs(
            sessionId = sessionId, command = command,
            timeoutSeconds = args["timeoutSeconds"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        )
    }

    private fun parsePrivilegedSessionReadArgs(
        args: JsonObject,
    ): PrivilegedSessionReadArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        return PrivilegedSessionReadArgs(
            sessionId = sessionId,
        )
    }

    private fun parsePrivilegedSessionStopArgs(args: JsonObject): PrivilegedSessionStopArgs {
        val sessionId = args["sessionId"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(sessionId.isNotEmpty()) { "缺少 sessionId" }
        return PrivilegedSessionStopArgs(sessionId = sessionId)
    }

    private fun privilegedConfirmationQuestion(action: String, command: String? = null): String {
        val preview = command?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length <= 120) it else it.take(120) + "..." }
        val target = preview ?: action
        val suffix = if (helper.isEnglishLocale) {
            " It has not run yet. Choose an allow or reject option in this permission request."
        } else {
            "当前尚未执行。请在本次授权请求中选择允许或拒绝。"
        }
        return when (PrivilegedActionPolicy.normalizeAction(action)) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC -> if (helper.isEnglishLocale) "The privileged shell command `$target` requires your confirmation.$suffix" else "高权限 shell 命令“$target”需要你的确认。$suffix"
            SharedHelper.PRIVILEGED_SESSION_START_ACTION -> if (helper.isEnglishLocale) "Starting a persistent privileged shell session requires your confirmation.$suffix" else "启动持久化高权限 shell 会话需要你的确认。$suffix"
            SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION -> if (helper.isEnglishLocale) "The privileged session command `$target` requires your confirmation.$suffix" else "高权限会话命令“$target”需要你的确认。$suffix"
            else -> if (helper.isEnglishLocale) "The privileged action `$target` requires your confirmation.$suffix" else "高权限动作“$target”需要你的确认。$suffix"
        }
    }

    private suspend fun requestPrivilegedConfirmation(
        action: String,
        command: String? = null,
        env: AgentExecutionEnvironment,
        toolCallId: String,
        toolName: String,
    ): ToolExecutionResult? {
        val question = privilegedConfirmationQuestion(action, command)
        val requester = env.permissionRequester
        if (requester != null) {
            val allowed = requester.requestPermission(
                toolCallId = toolCallId,
                title = privilegedPermissionTitle(action),
                detail = question,
            )
            return if (allowed) {
                null
            } else {
                ToolExecutionResult.Error(
                    toolName,
                    helper.localized("用户拒绝了高权限操作，命令未执行。"),
                )
            }
        }
        return ToolExecutionResult.Error(
            toolName,
            helper.localized("高权限操作需要授权，但当前未提供授权通道，操作已停止。"),
        )
    }

    private fun privilegedPermissionTitle(action: String): String {
        return when (PrivilegedActionPolicy.normalizeAction(action)) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC ->
                if (helper.isEnglishLocale) "Run privileged shell command" else "执行高权限 Shell 命令"
            SharedHelper.PRIVILEGED_SESSION_START_ACTION ->
                if (helper.isEnglishLocale) "Start privileged shell session" else "启动高权限 Shell 会话"
            SharedHelper.PRIVILEGED_SESSION_EXEC_ACTION ->
                if (helper.isEnglishLocale) "Run privileged session command" else "执行高权限会话命令"
            else ->
                if (helper.isEnglishLocale) "Run privileged action" else "执行高权限动作"
        }
    }

    private fun localizedPrivilegedMessage(result: PrivilegedResult): String {
        return when (result.code) {
            "ok" -> if (helper.isEnglishLocale) "Privileged action executed successfully." else "高级动作执行成功。"
            "confirmation_required" -> if (helper.isEnglishLocale) "This privileged action requires explicit confirmation." else "该高级动作需要用户明确确认。"
            "unsupported_action" -> if (helper.isEnglishLocale) "The measured authority does not support this action." else "当前实测权限不支持该动作。"
            "permission_required" -> if (helper.isEnglishLocale) "The selected privileged source requires authorization." else "所选高权限来源尚未授权。"
            "permission_denied" -> if (helper.isEnglishLocale) "The privileged source authorization was denied or revoked." else "高权限来源授权被拒绝或已撤销。"
            "process_start_failed" -> if (helper.isEnglishLocale) "Failed to start the selected privileged source process." else "无法启动所选高权限来源进程。"
            "authority_unexpected" -> if (helper.isEnglishLocale) "The privileged process returned an unexpected identity." else "高权限进程返回了非预期身份。"
            "service_error" -> if (helper.isEnglishLocale) "The privileged service failed to process the request." else "高权限服务处理请求失败。"
            "service_bind_failed" -> if (helper.isEnglishLocale) "Failed to bind the Shizuku user service." else "绑定 Shizuku 用户服务失败。"
            "service_call_failed" -> if (helper.isEnglishLocale) "Failed to call the Shizuku user service." else "调用 Shizuku 用户服务失败。"
            "service_timeout" -> if (helper.isEnglishLocale) "Timed out waiting for the Shizuku user service result." else "等待 Shizuku 用户服务结果超时。"
            "invalid_arguments" -> if (helper.isEnglishLocale) "The privileged action arguments are invalid." else "高级动作参数不合法。"
            "command_failed" -> if (helper.isEnglishLocale) "The privileged action command failed." else "高级动作执行失败。"
            "timeout" -> if (helper.isEnglishLocale) "The privileged action timed out." else "高级动作执行超时。"
            "blocked_by_policy" -> if (helper.isEnglishLocale) "This privileged command was blocked by the local safety policy." else "该高权限命令已被本地安全策略拦截。"
            "session_not_found" -> if (helper.isEnglishLocale) "The privileged shell session does not exist or is no longer alive." else "高权限 shell 会话不存在或已失效。"
            "session_busy" -> if (helper.isEnglishLocale) "Another command is still running in the privileged shell session." else "高权限 shell 会话中仍有命令在执行。"
            "session_start_failed" -> if (helper.isEnglishLocale) "Failed to start the privileged shell session." else "启动高权限 shell 会话失败。"
            "session_write_failed" -> if (helper.isEnglishLocale) "Failed to write to the privileged shell session." else "向高权限 shell 会话写入命令失败。"
            "session_exists" -> if (helper.isEnglishLocale) "A privileged shell session with the same id already exists." else "同名的高权限 shell 会话已存在。"
            "session_source_mismatch" -> if (helper.isEnglishLocale) "The privileged shell session belongs to another source." else "高权限 shell 会话属于其他执行来源。"
            else -> result.message
        }
    }

    private companion object {
        // confirmed 只为识别并忽略恶意旧输入，不会传入能力层。
        val SESSION_START_INPUT_KEYS = setOf("sessionName", "workingDirectory", "environment", "confirmed")
        val SESSION_EXEC_INPUT_KEYS = setOf("sessionId", "command", "timeoutSeconds", "confirmed")
    }
}
