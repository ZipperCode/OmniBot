package cn.com.omnimind.bot.task.runtime

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.AgentCapabilityToolDefinition
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * 任务控制工具处理器（用于重启检查点、状态查询、清除、只读核验对账以及人工认证等待）。
 * 严格与调用方当前环境绑定（conversationId、acpSessionId、acpTurnId），严禁跨会话越权操作。
 */
class TaskRuntimeToolHandler(
    private val context: Context
) : ToolHandler {

    companion object {
        const val TOOL_NAME = "task_control"
        private val gson = Gson()

        val definitions: List<AgentCapabilityToolDefinition> = listOf(
            AgentCapabilityToolDefinition(
                name = TOOL_NAME,
                displayName = "任务与延续控制",
                description = "管理当前会话的长任务重启延续检查点(checkpoint)、状态查询(status)、清除检查点(clear)、只读历史对账(reconcile)以及人工验证挂起等待(manual_wait)。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            put(
                                "description",
                                "操作类型: checkpoint (保存重启延续检查点), status (查询检查点与执行状态), clear (清除检查点), reconcile (只读核验卡在 ADMITTED 的检查点), manual_wait (挂起等待人工验证)"
                            )
                            putJsonArray("enum") {
                                add("checkpoint")
                                add("status")
                                add("clear")
                                add("reconcile")
                                add("manual_wait")
                            }
                        }
                        putJsonObject("taskTitle") {
                            put("type", "string")
                            put("description", "任务标题 (checkpoint 使用)")
                        }
                        putJsonObject("taskGoal") {
                            put("type", "string")
                            put("description", "总体任务目标 (checkpoint 使用)")
                        }
                        putJsonObject("currentProgress") {
                            put("type", "string")
                            put("description", "重启前已完成进度事实 (checkpoint 使用)")
                        }
                        putJsonObject("pendingSideEffects") {
                            put("type", "array")
                            put("description", "已执行或在途中变更项列表，严禁开机后盲目重放 (checkpoint 可选)")
                            putJsonObject("items") {
                                put("type", "string")
                            }
                        }
                        putJsonObject("evidenceVerificationChecklist") {
                            put("type", "array")
                            put("description", "开机解锁后必须优先核实的只读检查步骤 (checkpoint 可选)")
                            putJsonObject("items") {
                                put("type", "string")
                            }
                        }
                        putJsonObject("checkpointId") {
                            put("type", "string")
                            put("description", "指定操作的检查点 ID (clear 与 reconcile 使用)")
                        }
                        putJsonObject("promptMessage") {
                            put("type", "string")
                            put("description", "提示用户进行人工认证或接管的具体要求 (manual_wait 使用)")
                        }
                    }
                    putJsonArray("required") {
                        add("action")
                    }
                },
                toolType = "action"
            )
        )
    }

    override val toolNames: Set<String> = setOf(TOOL_NAME)

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val conversationId = env.conversationId
        val sessionId = env.acpSessionId?.trim().orEmpty()
        val acpTurnId = env.acpTurnId?.trim().orEmpty()

        if (conversationId == null || conversationId <= 0L || sessionId.isEmpty()) {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "task_control 工具要求 Agent 执行环境中必须包含正数 conversationId 与非空 acpSessionId。"
            )
        }

        return when (action.lowercase()) {
            "checkpoint" -> handleCheckpoint(args, conversationId, sessionId, acpTurnId)
            "status" -> handleStatus(conversationId, sessionId)
            "clear" -> handleClear(args, conversationId, sessionId)
            "reconcile" -> handleReconcile(args, conversationId, sessionId)
            "manual_wait" -> handleManualWait(args, conversationId, sessionId, acpTurnId.ifEmpty { null })
            else -> ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "不支持的 task_control 操作：'$action'。可选操作：checkpoint, status, clear, reconcile, manual_wait。"
            )
        }
    }

    private fun handleCheckpoint(
        args: JsonObject,
        conversationId: Long,
        sessionId: String,
        acpTurnId: String
    ): ToolExecutionResult {
        // 契约：checkpoint 必须使用官方 env.acpTurnId，缺失时不可用随机 agentRunId 伪造
        if (acpTurnId.isEmpty()) {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "保存持久检查点失败：执行环境中缺失官方 acpTurnId，不可用非正式标识伪造持久检查点。"
            )
        }

        val taskTitle = args["taskTitle"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val taskGoal = args["taskGoal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val currentProgress = args["currentProgress"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (taskTitle.isEmpty() || taskGoal.isEmpty() || currentProgress.isEmpty()) {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "缺少检查点必需字段：taskTitle、taskGoal 与 currentProgress 为必填项。"
            )
        }

        val pendingSideEffects = (args["pendingSideEffects"] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.trim()?.ifEmpty { null }
        }.orEmpty()

        val evidenceChecklist = (args["evidenceVerificationChecklist"] as? JsonArray)?.mapNotNull {
            it.jsonPrimitive.contentOrNull?.trim()?.ifEmpty { null }
        }.orEmpty()

        val checkpoint = TaskContinuationCoordinator.recordPendingReboot(
            context = context,
            conversationId = conversationId,
            sessionId = sessionId,
            turnId = acpTurnId,
            taskTitle = taskTitle,
            taskGoal = taskGoal,
            currentProgress = currentProgress,
            pendingSideEffects = pendingSideEffects,
            evidenceVerificationChecklist = evidenceChecklist
        )

        return if (checkpoint != null) {
            val json = gson.toJson(checkpoint)
            ToolExecutionResult.ContextResult(
                toolName = TOOL_NAME,
                summaryText = "已成功记录重启延续检查点：${checkpoint.checkpointId}",
                previewJson = json,
                rawResultJson = json,
                success = true
            )
        } else {
            ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "持久化检查点写盘失败。"
            )
        }
    }

    private fun handleStatus(
        conversationId: Long,
        sessionId: String
    ): ToolExecutionResult {
        val pendingCheckpoints = TaskContinuationCoordinator.getPendingCheckpoints(context).filter {
            it.conversationId == conversationId && it.sessionId == sessionId
        }

        val activeTask = TaskRuntime.findActiveTaskBySession(sessionId)
            ?: TaskRuntime.findActiveTaskByConversation(conversationId)

        val waitingStatus = TaskRuntime.getWaitingStatus(conversationId)

        val payload = mapOf(
            "conversationId" to conversationId,
            "sessionId" to sessionId,
            "hasActiveTask" to (activeTask != null),
            "activeTask" to activeTask,
            "isWaiting" to (waitingStatus != null),
            "waitingStatus" to waitingStatus?.let {
                when (it) {
                    is TaskWaitingStatus.WaitingForUnlock -> mapOf("type" to "unlock", "message" to it.message)
                    is TaskWaitingStatus.WaitingForManualAuth -> mapOf("type" to "manual_auth", "message" to it.promptMessage)
                }
            },
            "pendingCheckpoints" to pendingCheckpoints
        )

        val json = gson.toJson(payload)
        return ToolExecutionResult.ContextResult(
            toolName = TOOL_NAME,
            summaryText = "已查询当前会话任务状态，待延续检查点数: ${pendingCheckpoints.size}",
            previewJson = json,
            rawResultJson = json,
            success = true
        )
    }

    private fun handleClear(
        args: JsonObject,
        conversationId: Long,
        sessionId: String
    ): ToolExecutionResult {
        val targetCheckpointId = args["checkpointId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (targetCheckpointId.isNotEmpty()) {
            val existing = TaskContinuationCoordinator.readCheckpoint(context, targetCheckpointId)
            if (existing == null) {
                return ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "未找到指定的检查点：$targetCheckpointId"
                )
            }
            if (existing.conversationId != conversationId || existing.sessionId != sessionId) {
                return ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "权限拒绝：禁止清除属于其他会话的检查点。"
                )
            }
            val cleared = TaskContinuationCoordinator.clearCheckpoint(context, targetCheckpointId)
            if (!cleared) return ToolExecutionResult.Error(TOOL_NAME, "检查点未能持久清除：$targetCheckpointId")
            val json = gson.toJson(mapOf("clearedCheckpointId" to targetCheckpointId, "success" to true))
            return ToolExecutionResult.ContextResult(
                toolName = TOOL_NAME,
                summaryText = "已清除指定的检查点：$targetCheckpointId",
                previewJson = json,
                rawResultJson = json,
                success = true
            )
        } else {
            val toClear = TaskContinuationCoordinator.getPendingCheckpoints(context).filter {
                it.conversationId == conversationId && it.sessionId == sessionId
            }
            val clearedCount = toClear.count { TaskContinuationCoordinator.clearCheckpoint(context, it.checkpointId) }
            val clearedAll = clearedCount == toClear.size
            val json = gson.toJson(mapOf("clearedCount" to clearedCount, "success" to clearedAll))
            return ToolExecutionResult.ContextResult(
                toolName = TOOL_NAME,
                summaryText = "已清除当前会话 $clearedCount/${toClear.size} 个待延续检查点",
                previewJson = json,
                rawResultJson = json,
                success = clearedAll
            )
        }
    }

    private suspend fun handleReconcile(
        args: JsonObject,
        conversationId: Long,
        sessionId: String
    ): ToolExecutionResult {
        val checkpointId = args["checkpointId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (checkpointId.isEmpty()) {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "reconcile 操作需要指定 checkpointId。"
            )
        }
        val existing = TaskContinuationCoordinator.readCheckpoint(context, checkpointId)
            ?: return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "未找到指定的检查点：$checkpointId"
            )
        if (existing.conversationId != conversationId || existing.sessionId != sessionId) {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "权限拒绝：禁止核验属于其他会话的检查点。"
            )
        }
        val result = TaskContinuationCoordinator.reconcileAdmittedCheckpoint(context, checkpointId)
        val json = gson.toJson(result)
        return ToolExecutionResult.ContextResult(
            toolName = TOOL_NAME,
            summaryText = "已完成检查点只读历史核验：$checkpointId",
            previewJson = json,
            rawResultJson = json,
            success = result !is ContinuationResumeResult.Failed
        )
    }

    private suspend fun handleManualWait(
        args: JsonObject,
        conversationId: Long,
        sessionId: String,
        turnId: String?
    ): ToolExecutionResult {
        val promptMessage = args["promptMessage"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.ifEmpty { null } ?: "需要人工完成验证以继续执行"

        return try {
            val confirmed = TaskAuthWaitCoordinator.awaitManualVerification(
                context = context,
                conversationId = conversationId,
                sessionId = sessionId,
                promptMessage = promptMessage,
                turnId = turnId
            )

            if (confirmed) {
                val json = """{"status":"completed","conversationId":$conversationId}"""
                ToolExecutionResult.ContextResult(
                    toolName = TOOL_NAME,
                    summaryText = "人工验证已完成，继续执行后续步骤。",
                    previewJson = json,
                    rawResultJson = json,
                    success = true
                )
            } else {
                ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "人工验证被用户取消或拒绝。"
                )
            }
        } catch (e: CancellationException) {
            ToolExecutionResult.Interrupted(
                toolName = TOOL_NAME,
                summaryText = "人工验证等待已被取消或停止。"
            )
        }
    }
}
