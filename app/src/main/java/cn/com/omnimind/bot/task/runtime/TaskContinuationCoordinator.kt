package cn.com.omnimind.bot.task.runtime

import android.app.KeyguardManager
import android.content.Context
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * 任务延续恢复结果。
 */
sealed interface ContinuationResumeResult {
    object NoPendingContinuation : ContinuationResumeResult
    object DirectBootLocked : ContinuationResumeResult
    object DeviceLocked : ContinuationResumeResult
    data class Resumed(val checkpointId: String, val conversationId: Long, val sessionId: String) : ContinuationResumeResult
    data class SkippedAlreadyRunning(val checkpointId: String) : ContinuationResumeResult
    data class Failed(val checkpointId: String?, val reason: String, val recoverable: Boolean) : ContinuationResumeResult
}

/**
 * 延续 Turn 派发器接口。
 * 默认直接调用官方 AgentRuntimeManager 运行时 API，绝不新建 WebAgentRunBridge 覆盖现有运行事件监听器。
 */
fun interface ContinuationDispatcher {
    suspend fun dispatch(
        context: Context,
        checkpoint: TaskContinuationCheckpoint,
        prompt: String
    ): Result<Map<String, Any?>>
}

/**
 * 任务重启检查点持久化与证据驱动恢复协调器。
 * 遵循严格契约：
 * 1. 绝不盲目重放旧 Prompt。
 * 2. 区分 RESERVED / ADMITTED / FINISHED 生命周期，受理不等于完成。
 * 3. 记录 preboot boot_id，开机恢复时先核对当前 boot_id 与 sys.boot_completed，未重启不触发。
 * 4. ADMITTED 状态严禁自动重播；开机时对 ADMITTED 任务仅通过 AcpTaskTerminalJournal 确证官方终端事实，未决则转人工等待。
 * 5. 验证会话与 Session 真实绑定，不一致时暂停自动派发并通知用户人工核验。
 * 6. 开机广播快速准入后异步交接给长任务执行环境，避免 goAsync() 超时导致 ANR。
 */
object TaskContinuationCoordinator {
    private const val TAG = "TaskContinuationCoordinator"
    private val resumeMutex = Mutex()
    private val continuationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var customDispatcher: ContinuationDispatcher? = null

    /**
     * 判断终端状态是否为官方成功终态（completed 或 end_turn）。
     * 契约：cancelled / max_tokens / error 等绝不算成功。
     */
    internal fun isSuccessfulTerminal(status: String?): Boolean {
        val s = status?.lowercase()?.trim()
        return s == "completed" || s == "end_turn"
    }

    @Volatile
    private var currentBootIdForTesting: String? = null

    internal fun setCurrentBootIdForTesting(bootId: String?) {
        currentBootIdForTesting = bootId
    }

    /**
     * 读取当前系统的 kernel boot_id（Linux / Android 内核启动生成的全局唯一 UUID）。
     */
    fun readCurrentBootId(): String? {
        currentBootIdForTesting?.let { return it }
        return runCatching {
            val file = File("/proc/sys/kernel/random/boot_id")
            if (file.exists() && file.canRead()) file.readText().trim().ifEmpty { null } else null
        }.getOrNull()
    }

    @Volatile
    private var bootCompletedOverrideForTesting: Boolean? = null

    internal fun setBootCompletedForTesting(completed: Boolean?) {
        bootCompletedOverrideForTesting = completed
    }

    /**
     * 读取 Android 系统的 sys.boot_completed 属性。
     */
    fun isSysBootCompleted(): Boolean {
        bootCompletedOverrideForTesting?.let { return it }
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", "sys.boot_completed"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output == "1"
        }.getOrDefault(false)
    }

    /**
     * 仅供单元测试注入模拟派发器。
     */
    internal fun setDispatcherForTesting(dispatcher: ContinuationDispatcher?) {
        customDispatcher = dispatcher
    }

    /**
     * 记录即将触发整机重启的长任务检查点。
     * 严格校验 conversationId 为正数且 sessionId 与 turnId 非空，绝不使用随机标识。
     */
    fun recordPendingReboot(
        context: Context,
        conversationId: Long,
        sessionId: String,
        turnId: String,
        taskTitle: String,
        taskGoal: String,
        currentProgress: String,
        pendingSideEffects: List<String> = emptyList(),
        evidenceVerificationChecklist: List<String> = emptyList(),
        acpAgentId: String? = null
    ): TaskContinuationCheckpoint? = recordCheckpoint(
        context = context,
        conversationId = conversationId,
        sessionId = sessionId,
        turnId = turnId,
        acpAgentId = acpAgentId,
        taskTitle = taskTitle,
        taskGoal = taskGoal,
        currentProgress = currentProgress,
        pendingSideEffects = pendingSideEffects,
        evidenceVerificationChecklist = evidenceVerificationChecklist
    )

    fun recordCheckpoint(
        context: Context,
        conversationId: Long,
        sessionId: String,
        turnId: String,
        taskTitle: String,
        taskGoal: String,
        currentProgress: String,
        pendingSideEffects: List<String> = emptyList(),
        evidenceVerificationChecklist: List<String> = emptyList(),
        acpAgentId: String? = null
    ): TaskContinuationCheckpoint? {
        if (conversationId <= 0L || sessionId.isBlank() || turnId.isBlank()) {
            OmniLog.w(
                TAG,
                "记录检查点失败：conversationId 必须为正数且 sessionId 与 turnId 不能为空 " +
                    "(conversationId=$conversationId, sessionId=$sessionId, turnId=$turnId)"
            )
            return null
        }

        // 真实读取绑定的 acpAgentId，杜绝伪造
        val resolvedAgentId = acpAgentId?.trim()?.ifEmpty { null }
            ?: runCatching { AcpAgentProfileStore(context).agentIdForSession(sessionId) }.getOrNull()
            ?: runCatching { AcpAgentProfileStore(context).agentIdForConversation(conversationId) }.getOrNull()

        if (resolvedAgentId.isNullOrBlank()) {
            OmniLog.w(TAG, "记录检查点失败：未找到会话或 Session 真实绑定的 acpAgentId (sessionId=$sessionId)")
            return null
        }

        val prebootBootId = readCurrentBootId()
        val checkpointId = UUID.randomUUID().toString()
        val checkpoint = TaskContinuationCheckpoint(
            checkpointId = checkpointId,
            conversationId = conversationId,
            sessionId = sessionId.trim(),
            turnId = turnId.trim(),
            acpAgentId = resolvedAgentId,
            taskTitle = taskTitle.trim(),
            taskGoal = taskGoal.trim(),
            currentProgress = currentProgress.trim(),
            pendingSideEffects = pendingSideEffects,
            evidenceVerificationChecklist = evidenceVerificationChecklist,
            prebootBootId = prebootBootId,
            state = ContinuationState.RESERVED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            attemptCount = 0
        )
        val saved = TaskContinuationStore.saveCheckpoint(context, checkpoint)
        if (saved) {
            OmniLog.i(
                TAG,
                "已记录重启延续检查点 checkpointId=$checkpointId conversationId=$conversationId " +
                    "turnId=$turnId agentId=$resolvedAgentId prebootBootId=$prebootBootId"
            )
            return checkpoint
        } else {
            OmniLog.w(TAG, "保存重启延续检查点失败 checkpointId=$checkpointId")
            return null
        }
    }

    fun readCheckpoint(context: Context, checkpointId: String): TaskContinuationCheckpoint? =
        TaskContinuationStore.getCheckpoint(context, checkpointId)

    fun getPendingCheckpoints(context: Context): List<TaskContinuationCheckpoint> =
        TaskContinuationStore.getPendingCheckpoints(context)

    fun clearCheckpoint(context: Context, checkpointId: String): Boolean =
        TaskContinuationStore.deleteCheckpoint(context, checkpointId)

    fun clearFinishedCheckpoints(context: Context) {
        TaskContinuationStore.clearFinishedCheckpoints(context)
    }

    /**
     * 将已准入并执行完的检查点显式标记为 FINISHED。
     * 严格契约：无官方证据不得绕过终态。必须在 AcpTaskTerminalJournal 中确证 completed 终态。
     */
    fun markFinished(context: Context, checkpointId: String): Boolean {
        if (!TaskContinuationStore.isStorageAccessible(context)) return false
        val cp = TaskContinuationStore.getCheckpoint(context, checkpointId) ?: return false
        val lookup = if (!cp.continuationRequestId.isNullOrBlank()) {
            AcpTaskTerminalJournal.findByRequestId(context, cp.acpAgentId, cp.sessionId, cp.continuationRequestId)
        } else if (!cp.continuationTurnId.isNullOrBlank()) {
            AcpTaskTerminalJournal.find(context, cp.acpAgentId, cp.sessionId, cp.continuationTurnId)
        } else {
            AcpTerminalLookupResult.Unknown
        }
        if (lookup !is AcpTerminalLookupResult.TerminalFound ||
            !isSuccessfulTerminal(lookup.entry.terminalStatus) ||
            (!lookup.entry.checkpointId.isNullOrBlank() && lookup.entry.checkpointId != checkpointId)
        ) {
            OmniLog.w(TAG, "markFinished 拒绝：无官方 completed/end_turn 终态证据 (checkpointId=$checkpointId)")
            return false
        }
        return TaskContinuationStore.updateCheckpointState(
            context,
            checkpointId,
            ContinuationState.FINISHED,
            continuationTurnId = lookup.entry.turnId
        )
    }

    /**
     * 对处于 ADMITTED 状态的检查点进行只读终端事实对账。
     * 严禁无脑重发，通过 AcpTaskTerminalJournal 确证官方终端状态。
     */
    suspend fun reconcileAdmittedCheckpoint(
        context: Context,
        checkpointId: String
    ): ContinuationResumeResult {
        return resumeMutex.withLock {
            val cp = TaskContinuationStore.getCheckpoint(context, checkpointId)
                ?: return@withLock ContinuationResumeResult.Failed(checkpointId, "未找到指定检查点", recoverable = false)
            if (cp.state != ContinuationState.ADMITTED) {
                return@withLock ContinuationResumeResult.Failed(checkpointId, "检查点非 ADMITTED 状态：${cp.state}", recoverable = false)
            }

            val lookup = if (!cp.continuationRequestId.isNullOrBlank()) {
                AcpTaskTerminalJournal.findByRequestId(context, cp.acpAgentId, cp.sessionId, cp.continuationRequestId)
            } else if (!cp.continuationTurnId.isNullOrBlank()) {
                AcpTaskTerminalJournal.find(context, cp.acpAgentId, cp.sessionId, cp.continuationTurnId)
            } else {
                AcpTerminalLookupResult.Unknown
            }

            when (lookup) {
                is AcpTerminalLookupResult.TerminalFound -> {
                    if (!lookup.entry.checkpointId.isNullOrBlank() && lookup.entry.checkpointId != checkpointId) {
                        OmniLog.w(TAG, "Journal checkpointId (${lookup.entry.checkpointId}) 与检查点 ($checkpointId) 不符")
                        return@withLock ContinuationResumeResult.Failed(cp.checkpointId, "终端记录与检查点不符", recoverable = true)
                    }
                    val status = lookup.entry.terminalStatus?.lowercase()
                    val turnId = lookup.entry.turnId
                    if (isSuccessfulTerminal(status)) {
                        val saved = TaskContinuationStore.updateCheckpointState(
                            context,
                            checkpointId,
                            ContinuationState.FINISHED,
                            continuationTurnId = turnId
                        )
                        if (!saved) return@withLock ContinuationResumeResult.Failed(
                            cp.checkpointId, "官方终态已确认，但检查点未能持久化", recoverable = true
                        )
                        ContinuationResumeResult.Resumed(cp.checkpointId, cp.conversationId, cp.sessionId)
                    } else {
                        val saved = TaskContinuationStore.updateCheckpointState(
                            context,
                            checkpointId,
                            ContinuationState.FAILED,
                            error = lookup.entry.terminalError ?: "非成功终态：$status",
                            continuationTurnId = turnId
                        )
                        if (!saved) return@withLock ContinuationResumeResult.Failed(
                            cp.checkpointId, "官方终态已确认，但检查点未能持久化", recoverable = true
                        )
                        ContinuationResumeResult.Failed(cp.checkpointId, "官方终端记录为失败：$status", recoverable = false)
                    }
                }
                is AcpTerminalLookupResult.AdmittedInFlight,
                is AcpTerminalLookupResult.Unknown -> {
                    ContinuationResumeResult.Failed(cp.checkpointId, "终端状态未知，契约严禁自动重发，需人工核验", recoverable = true)
                }
            }
        }
    }

    /**
     * 开机首次解锁后恢复待处理的延续任务。
     * 严格隔离：
     * - 对处于 ADMITTED 状态的检查点，仅通过 AcpTaskTerminalJournal 进行只读状态核验，未决则转人工，绝不自动重发。
     * - 对 RESERVED 检查点，核对 boot_id 与 sys.boot_completed 确认真实重启，校验 binding 一致性后生成 requestId 准入并异步交接派发。
     */
    suspend fun resumePendingContinuationIfAny(context: Context): ContinuationResumeResult {
        // 1. Direct Boot 检查：未解锁前 CE 存储不可访问
        if (!TaskContinuationStore.isStorageAccessible(context)) {
            OmniLog.i(TAG, "Direct Boot 模式：CE 存储受保护锁定，推迟任务延续至首次解锁")
            if (TaskContinuationNotifications.readPendingHint(context)) {
                TaskContinuationNotifications.showWaitingForUnlock(context)
            }
            return ContinuationResumeResult.DirectBootLocked
        }

        // 2. 锁屏状态检查
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            OmniLog.i(TAG, "设备处于锁屏状态，推迟任务延续至屏幕解锁")
            // 普通 CE 可读时检查实际待恢复和 boot 变化
            val pendingList = TaskContinuationStore.getPendingCheckpoints(context)
            val currentBootId = readCurrentBootId()
            val hasActualPending = pendingList.any { cp ->
                when (cp.state) {
                    ContinuationState.RESERVED -> {
                        // 真实发生过整机重启（bootId 变更或为空），处于等待恢复状态
                        cp.prebootBootId == null || currentBootId == null || currentBootId != cp.prebootBootId
                    }
                    ContinuationState.ADMITTED -> true
                    else -> false
                }
            }
            // 通知是否可见不改变持久提示：重启前的 RESERVED 仍须供下次开机读取。
            TaskContinuationNotifications.persistPendingHint(context, pendingList.isNotEmpty())

            if (hasActualPending) {
                // 兼容旧版本仅有凭据保护区检查点的情况。
                TaskContinuationNotifications.showWaitingForUnlock(context)
            } else {
                // 尚未重启时不显示恢复通知，但保留待恢复提示。
                TaskContinuationNotifications.cancel(context)
            }
            return ContinuationResumeResult.DeviceLocked
        }
        return resumeMutex.withLock {
            // 3. 处理已处于 ADMITTED 状态的历史检查点（通过官方 AcpTaskTerminalJournal 确证，未决转人工，严禁自动重播）
            val admittedList = TaskContinuationStore.getAdmittedCheckpoints(context)
            for (admitted in admittedList) {
                val lookupResult = if (!admitted.continuationRequestId.isNullOrBlank()) {
                    AcpTaskTerminalJournal.findByRequestId(
                        context,
                        admitted.acpAgentId,
                        admitted.sessionId,
                        admitted.continuationRequestId
                    )
                } else if (!admitted.continuationTurnId.isNullOrBlank()) {
                    AcpTaskTerminalJournal.find(
                        context,
                        admitted.acpAgentId,
                        admitted.sessionId,
                        admitted.continuationTurnId
                    )
                } else {
                    AcpTerminalLookupResult.Unknown
                }

                when (lookupResult) {
                    is AcpTerminalLookupResult.TerminalFound -> {
                        if (!lookupResult.entry.checkpointId.isNullOrBlank() && lookupResult.entry.checkpointId != admitted.checkpointId) {
                            OmniLog.w(TAG, "Journal checkpointId ${lookupResult.entry.checkpointId} 与 ${admitted.checkpointId} 不匹配")
                            continue
                        }
                        val status = lookupResult.entry.terminalStatus?.lowercase()
                        val turnId = lookupResult.entry.turnId
                        if (isSuccessfulTerminal(status)) {
                            OmniLog.i(TAG, "官方 Journal 确证已完成 checkpointId=${admitted.checkpointId} status=$status")
                            TaskContinuationStore.updateCheckpointState(
                                context,
                                admitted.checkpointId,
                                ContinuationState.FINISHED,
                                continuationTurnId = turnId
                            )
                        } else {
                            OmniLog.w(TAG, "官方 Journal 确证非成功终态 checkpointId=${admitted.checkpointId} status=$status")
                            TaskContinuationStore.updateCheckpointState(
                                context,
                                admitted.checkpointId,
                                ContinuationState.FAILED,
                                error = lookupResult.entry.terminalError ?: "非成功终态：$status",
                                continuationTurnId = turnId
                            )
                        }
                    }
                    is AcpTerminalLookupResult.AdmittedInFlight,
                    is AcpTerminalLookupResult.Unknown -> {
                        OmniLog.w(TAG, "检查点 ${admitted.checkpointId} 终端状态不明，严禁自动重发，设置人工核验等待")
                        TaskRuntime.updateWaitingStatus(
                            context,
                            admitted.conversationId,
                            TaskWaitingStatus.WaitingForManualAuth(
                                conversationId = admitted.conversationId,
                                sessionId = admitted.sessionId,
                                turnId = admitted.turnId,
                                promptMessage = "发现重启前未决任务 (${admitted.taskTitle})，请核验状态后恢复"
                            )
                        )
                    }
                }
            }

            // 4. 仅读取处于 RESERVED 状态的待恢复检查点
            val resumableList = TaskContinuationStore.getResumableCheckpoints(context)
            if (resumableList.isEmpty()) {
                if (TaskContinuationStore.getPendingCheckpoints(context).isEmpty()) {
                    TaskContinuationNotifications.cancel(context)
                }
                return@withLock ContinuationResumeResult.NoPendingContinuation
            }

            val target = resumableList.first()

            // 5. 核验真实重启证据：核对 boot_id
            val currentBootId = readCurrentBootId()
            if (target.prebootBootId != null && currentBootId != null && currentBootId == target.prebootBootId) {
                OmniLog.i(TAG, "当前 boot_id 与检查点 prebootBootId 相同 ($currentBootId)，设备尚未发生重启，跳过恢复")
                return@withLock ContinuationResumeResult.NoPendingContinuation
            }

            // 6. 核验 sys.boot_completed
            if (!isSysBootCompleted()) {
                OmniLog.i(TAG, "系统 sys.boot_completed 尚未就绪，推迟任务延续")
                return@withLock ContinuationResumeResult.DeviceLocked
            }

            // 7. 校验会话与 Session 真实绑定，防止 dispatch 不传原 session 落入新绑定
            val binding = runCatching {
                DatabaseHelper.getAgentSessionBindingByThreadId(target.sessionId)
            }.getOrNull()

            if (binding == null || binding.conversationId != target.conversationId) {
                OmniLog.w(
                    TAG,
                    "检查点会话绑定不一致：sessionId=${target.sessionId} 实际绑定=${binding?.conversationId} " +
                        "检查点记录=${target.conversationId}，暂停自动恢复并转入人工等待"
                )
                TaskRuntime.updateWaitingStatus(
                    context,
                    target.conversationId,
                    TaskWaitingStatus.WaitingForManualAuth(
                        conversationId = target.conversationId,
                        sessionId = target.sessionId,
                        turnId = target.turnId,
                        promptMessage = "检查点会话绑定不一致，请人工确认后恢复"
                    )
                )
                return@withLock ContinuationResumeResult.Failed(
                    target.checkpointId,
                    "会话绑定不一致，已转人工等待",
                    recoverable = true
                )
            }

            // 8. 去重排队：若该会话当前已有活跃任务租约，则跳过避免重复执行
            val alreadyActive = TaskRuntime.findActiveTaskBySession(target.sessionId)
            if (alreadyActive != null) {
                OmniLog.i(TAG, "检查点 ${target.checkpointId} 对应的会话已在运行中，跳过重复恢复")
                return@withLock ContinuationResumeResult.SkippedAlreadyRunning(target.checkpointId)
            }

            // 9. 状态流转：RESERVED -> ADMITTED（记录新派发 requestId，单次击穿，递增尝试计数）
            val newRequestId = "continuation_req_${target.checkpointId}_${target.attemptCount + 1}"
            val admitted = TaskContinuationStore.markAdmitted(context, target.checkpointId, newRequestId)
                ?: return@withLock ContinuationResumeResult.Failed(
                    target.checkpointId,
                    "标记检查点为 ADMITTED 失败",
                    recoverable = true
                )

            // 成功准入：锁屏等待阶段结束，撤销等待解锁通知
            TaskContinuationNotifications.cancel(context)

            // 10. 构建证据驱动的全新 Prompt（绝不盲目重发原 Prompt）
            val prompt = buildEvidenceCheckPrompt(admitted)

            OmniLog.i(
                TAG,
                "检查点 ${admitted.checkpointId} 已准入（第 ${admitted.attemptCount} 次尝试，requestId=$newRequestId），" +
                    "正在异步交接派发延续 Turn..."
            )

            val dispatcher = customDispatcher ?: defaultDispatcher

            // 异步长任务交接：绝不阻塞广播接收器，避免超时 ANR
            continuationScope.launch {
                executeContinuationTurn(context, admitted, prompt, dispatcher)
            }

            ContinuationResumeResult.Resumed(
                checkpointId = admitted.checkpointId,
                conversationId = admitted.conversationId,
                sessionId = admitted.sessionId
            )
        }
    }

    /**
     * 执行延续 Turn 并严格解析官方执行结果，避免把受理当完成。
     */
    internal suspend fun executeContinuationTurn(
        context: Context,
        checkpoint: TaskContinuationCheckpoint,
        prompt: String,
        dispatcher: ContinuationDispatcher
    ) {
        val dispatchResult = runCatching {
            dispatcher.dispatch(context, checkpoint, prompt).getOrThrow()
        }
        handleDispatchOutcome(context, checkpoint, dispatchResult)
    }

    private fun handleDispatchOutcome(
        context: Context,
        checkpoint: TaskContinuationCheckpoint,
        dispatchResult: Result<Map<String, Any?>>
    ) {
        val checkpointId = checkpoint.checkpointId
        val response = dispatchResult.getOrNull()

        // 真实提取 continuationTurnId：从 host/response 或 journal requestId 得，绝不 fallback 原 turnId
        val responseTurnId = (response?.get("turnId") ?: response?.get("continuationTurnId"))
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val journalLookup = if (!checkpoint.continuationRequestId.isNullOrBlank()) {
            AcpTaskTerminalJournal.findByRequestId(
                context,
                checkpoint.acpAgentId,
                checkpoint.sessionId,
                checkpoint.continuationRequestId
            )
        } else {
            AcpTerminalLookupResult.Unknown
        }
        val journalTurnId = when (journalLookup) {
            is AcpTerminalLookupResult.TerminalFound -> journalLookup.entry.turnId
            is AcpTerminalLookupResult.AdmittedInFlight -> journalLookup.entry.turnId
            is AcpTerminalLookupResult.Unknown -> null
        }
        val resolvedContinuationTurnId = responseTurnId ?: journalTurnId ?: checkpoint.continuationTurnId

        if (dispatchResult.isSuccess && response != null) {
            val error = response["error"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val status = response["status"]?.toString()?.lowercase()?.trim()
            val stopReason = response["stopReason"]?.toString()?.lowercase()?.trim()
            val completed = response["completed"] == true
            val responseCheckpointId = response["checkpointId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

            val checkpointMatches = responseCheckpointId == null || responseCheckpointId == checkpointId
            val journalCheckpointMatches = when (journalLookup) {
                is AcpTerminalLookupResult.TerminalFound ->
                    journalLookup.entry.checkpointId.isNullOrBlank() || journalLookup.entry.checkpointId == checkpointId
                else -> true
            }

            if (!error.isNullOrBlank() || status == "error") {
                val errorMsg = error ?: "执行错误：status=$status"
                OmniLog.e(TAG, "延续 Turn 报错 checkpointId=$checkpointId: $errorMsg")
                TaskContinuationStore.updateCheckpointState(
                    context,
                    checkpointId,
                    ContinuationState.FAILED,
                    error = errorMsg,
                    continuationTurnId = resolvedContinuationTurnId
                )
            } else if (status == "cancelled" || stopReason == "cancelled") {
                OmniLog.w(TAG, "延续 Turn 已被取消 checkpointId=$checkpointId")
                TaskContinuationStore.updateCheckpointState(
                    context,
                    checkpointId,
                    ContinuationState.FAILED,
                    error = "已取消",
                    continuationTurnId = resolvedContinuationTurnId
                )
            } else if (checkpointMatches && journalCheckpointMatches &&
                (isSuccessfulTerminal(status) || isSuccessfulTerminal(stopReason) || (completed && status != "in_progress"))
            ) {
                OmniLog.i(TAG, "延续 Turn 官方完成 checkpointId=$checkpointId continuationTurnId=$resolvedContinuationTurnId")
                TaskContinuationStore.updateCheckpointState(
                    context,
                    checkpointId,
                    ContinuationState.FINISHED,
                    continuationTurnId = resolvedContinuationTurnId
                )
            } else {
                // 结果仍未终态或不明：保持 ADMITTED 状态供核验，契约严禁盲目重播
                OmniLog.w(TAG, "延续 Turn 结果不明 checkpointId=$checkpointId status=$status，保持 ADMITTED")
                TaskContinuationStore.updateCheckpointState(
                    context,
                    checkpointId,
                    ContinuationState.ADMITTED,
                    error = "结果不明：status=$status",
                    continuationTurnId = resolvedContinuationTurnId
                )
            }
        } else {
            val t = dispatchResult.exceptionOrNull()
            OmniLog.e(TAG, "延续 Turn 派发异常 checkpointId=$checkpointId：${t?.message}", t)
            // 契约：若请求已发但结果不明，保留 ADMITTED，绝不重置为 RESERVED 盲目重发
            TaskContinuationStore.updateCheckpointState(
                context,
                checkpointId,
                ContinuationState.ADMITTED,
                error = t?.message ?: "派发异常",
                continuationTurnId = resolvedContinuationTurnId
            )
        }
    }

    /**
     * 构建结构化证据驱动提示词。
     */
    fun buildEvidenceCheckPrompt(checkpoint: TaskContinuationCheckpoint): String {
        return buildString {
            appendLine("【系统执行环境通知：设备已完成重启并解锁】")
            appendLine("此前任务在执行系统重启或环境变更操作前已记录延续检查点。")
            appendLine()
            appendLine("任务标题：${checkpoint.taskTitle}")
            appendLine("任务总体目标：${checkpoint.taskGoal}")
            appendLine("重启前已完成进度：${checkpoint.currentProgress}")
            if (checkpoint.pendingSideEffects.isNotEmpty()) {
                appendLine()
                appendLine("重启前执行的变更与副作用：")
                checkpoint.pendingSideEffects.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("【执行要求与防副作用重放约束】：")
            appendLine("1. 绝不盲目重复上述已执行的变更（切勿重复安装 APK、重复修改系统配置或再次触发重启）。")
            appendLine("2. 必须首先调用只读检查工具，收集实际生效结果的直接证据：")
            if (checkpoint.evidenceVerificationChecklist.isNotEmpty()) {
                checkpoint.evidenceVerificationChecklist.forEach { appendLine("   - $it") }
            } else {
                appendLine("   - 检查目标模块/系统服务当前实际运行状态")
                appendLine("   - 检查已安装应用包版本与系统日志")
            }
            appendLine("3. 核实证据确认有效后，再推进后续未完成步骤；若未生效，说明原因并排查。")
        }.trim()
    }

    /**
     * 默认派发器：直接调用官方 AgentRuntimeManager session/prompt 接口，传递 requestId。
     * 绝不新建 WebAgentRunBridge，避免覆盖现有 webchat supplemental listener 丢失事件。
     */
    private val defaultDispatcher = ContinuationDispatcher { context, checkpoint, prompt ->
        runCatching {
            val manager = AgentRuntimeManager.getInstance(context)
            val promptArgs = mutableMapOf<String, Any?>(
                "sessionId" to checkpoint.sessionId,
                "threadId" to checkpoint.sessionId,
                "agentId" to checkpoint.acpAgentId,
                "conversationId" to checkpoint.conversationId,
                "text" to prompt
            )
            checkpoint.continuationRequestId?.let {
                promptArgs["requestId"] = it
            }
            val result = manager.handleMethod("session/prompt", promptArgs)
            if (result is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                result.entries.associate { it.key.toString() to it.value }
            } else {
                throw IllegalStateException("session/prompt returned unexpected non-map result: $result")
            }
        }
    }
}
