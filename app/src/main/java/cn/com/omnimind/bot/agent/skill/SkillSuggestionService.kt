package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.bot.agent.skill.SkillSuggestionRedactor
import cn.com.omnimind.bot.plugin.sandbox.XiaowanChatCompletionRequestFactory
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 技能提炼后台作业规格。
 */
data class SuggestionJobSpec(
    val candidateId: String,
    val conversationId: Long,
    val providerProfileId: String,
    val modelId: String,
    val credentialDigest: String,
    val sanitizedInput: String
)

/**
 * 自动技能建议、审核与生命周期版本管理服务。
 * 遵循计划步骤 6 规范：
 * - 仅在小万正式 end_turn 且历史提交屏障完成的合格 Turn 后异步触发；
 * - 限制单并发，内存待处理队列上限 10，繁忙记录 skipped_busy；
 * - 原 Provider / 模型安全核验，失效跳过；输入严格脱敏与凭据拦截，上限 16KiB；
 * - 完整审核、不可变版本 SHA256 快照、独立后续实际使用反馈与版本恢复。
 */
class SkillSuggestionService(
    val context: Context,
    val workspaceManager: AgentWorkspaceManager,
    val lifecycleStore: SkillLifecycleStore,
    val skillIndexService: SkillIndexService,
    val providerProfileResolver: ((String?) -> ModelProviderProfile?)? = null,
    val llmClientFactory: ((CoroutineScope, AgentModelOverride?) -> AgentLlmClient)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val contextCapacityResolver: (suspend (Long, ModelProviderProfile, String) -> Int?)? = null
) {
    private val admissions = ConcurrentHashMap<String, TurnAdmissionInfo>()
    private val queueCapacity = 10
    private val pendingJobs = Channel<SuggestionJobSpec>(capacity = queueCapacity)
    private val activeGenerationJobs = ConcurrentHashMap<String, Job>()
    private val sourceJobs = mutableMapOf<String, SuggestionJobSpec>()
    private val mutationMutex = Mutex()
    private val started = CompletableDeferred<Unit>()
    private val registeredToolNames by lazy { AgentToolDefinitions.reservedToolNames() }

    init {
        scope.launch {
            try {
                lifecycleStore.onProcessStart()
                started.complete(Unit)
            } catch (error: Throwable) {
                started.completeExceptionally(error)
                throw error
            }
            for (spec in pendingJobs) processSuggestionJob(spec)
        }
    }

    /**
     * 准入时安全捕获 Turn Provider / Model 凭据摘要（不落盘凭据明文）。
     */
    fun onPromptAdmitted(
        sessionId: String,
        turnId: String,
        requestId: String?,
        conversationId: Long?,
        providerProfileId: String?,
        modelId: String?,
        credentialDigest: String?
    ) {
        val key = "$sessionId:$turnId"
        admissions[key] = TurnAdmissionInfo(
            sessionId = sessionId,
            turnId = turnId,
            requestId = requestId,
            conversationId = conversationId,
            providerProfileId = providerProfileId,
            modelId = modelId,
            credentialDigest = credentialDigest
        )
    }

    /**
     * 小万正式 end_turn 且历史提交屏障完成后触发。
     * 绝不持有主 prompt 锁。
     */
    suspend fun onSettledAndHistoryCommitted(
        conversationId: Long,
        sessionId: String,
        turnId: String,
        agentId: String,
        stopReason: String?,
        error: Throwable?,
        toolCalls: List<TurnToolExecutionSummary>,
        userVisiblePrompt: String?,
        userVisibleAnswer: String?
    ) {
        if (agentId != cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore.XIAOWAN_AGENT_ID && agentId != "xiaowan") return
        if (error != null || stopReason != "end_turn") return
        started.await()
        mutationMutex.withLock {
            if (!lifecycleStore.getSettings().autoSuggestionsEnabled) return@withLock
            if (toolCalls.count { it.success } < 3 ||
                toolCalls.map { it.toolName }.distinct().size < 2 ||
                toolCalls.any { !it.success || it.permissionRejected }
            ) return@withLock

            val candidateId = generateCandidateId(conversationId, sessionId, turnId)
            if (lifecycleStore.getCandidate(candidateId) != null) return@withLock
            val base = SkillCandidateAsset(
                candidateId = candidateId,
                conversationId = conversationId,
                sessionId = sessionId,
                turnId = turnId,
                state = "queued",
                toolCallsSummary = toolCalls.map { tool ->
                    "${tool.toolName}(keys: ${tool.parameterKeys.sorted().joinToString(",")})"
                },
                dataCategories = listOf("user_task_prompt", "assistant_answer", "tool_names_and_keys", "available_skill_index")
            )
            suspend fun skip(reason: String, message: String) {
                saveCandidate(base.copy(state = "skipped", skipReason = reason, failureReason = message))
            }
            val admission = admissions["$sessionId:$turnId"]
            val profile = admission?.providerProfileId?.let(::resolveProfile)
            if (admission == null || admission.conversationId != conversationId ||
                admission.modelId.isNullOrBlank() || profile == null || !profile.ready ||
                !profile.isConfigured() || admission.credentialDigest == null ||
                computeSkillSuggestionCredentialDigest(profile) != admission.credentialDigest
            ) {
                skip("provider_changed", "原任务的模型或账号身份已不可用，未生成建议")
                return@withLock
            }
            val skills = skillIndexService.listInstalledSkills().joinToString("\n") { "- ${it.id}: ${it.description}" }
            val rawInput = buildPromptPayload(userVisiblePrompt.orEmpty(), userVisibleAnswer.orEmpty(), base.toolCallsSummary, skills)
            val sanitized = SkillSuggestionRedactor.sanitize(rawInput)
            if (sanitized.sensitiveDetected) {
                skip("sensitive", "任务内容命中敏感凭据模式，未生成建议")
                return@withLock
            }
            if (rawInput.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
                skip("too_large", "任务输入超过 16KiB，未截断或发送")
                return@withLock
            }
            if (sanitized.text.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
                skip("too_large", "任务输入超过 16KiB，未截断或发送")
                return@withLock
            }
            val capacity = resolveContextCapacity(conversationId, profile, admission.modelId)
            if (capacity == null || !fitsContext(sanitized.text, capacity)) {
                skip("too_large", "原模型上下文预算不可用或不足，未生成建议")
                return@withLock
            }
            val spec = SuggestionJobSpec(candidateId, conversationId, profile.id, admission.modelId, admission.credentialDigest, sanitized.text)
            saveCandidate(base)
            sourceJobs[candidateId] = spec
            enqueue(spec)
        }
    }

    // --- 宿主管理 API ---

    suspend fun listSuggestions(
        conversationId: Long? = null,
        turnId: String? = null,
        skillId: String? = null,
        offset: Int = 0,
        limit: Int = 20
    ): SkillSuggestionsListResponse {
        started.await()
        val settings = lifecycleStore.getSettings()
        val total = lifecycleStore.countCandidates(conversationId, turnId)
        val items = lifecycleStore.listCandidates(conversationId, turnId, offset, limit)

        val feedbackEligibility = if (!skillId.isNullOrBlank()) {
            getFeedbackEligibility(skillId)
        } else null

        val revisions = if (!skillId.isNullOrBlank()) {
            lifecycleStore.listRevisions(skillId)
        } else emptyList()

        return SkillSuggestionsListResponse(
            enabled = settings.autoSuggestionsEnabled,
            items = items,
            total = total,
            offset = offset,
            limit = limit,
            feedbackEligibility = feedbackEligibility,
            revisions = revisions
        )
    }

    suspend fun saveSuggestion(
        candidateId: String,
        expectedRevision: Long,
        name: String,
        description: String,
        bodyMarkdown: String
    ): SkillCandidateAsset {
        started.await()
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val current = requireCandidate(candidateId, expectedRevision, setOf("pending_review"))
                val validated = validateSkillCandidateContent(name, description, bodyMarkdown, knownTools(current))
                updateCandidate(candidateId, expectedRevision) {
                    it.copy(name = validated.name, description = validated.description, bodyMarkdown = validated.bodyMarkdown, targetSkillId = validated.name)
                }
            }
        }
    }

    suspend fun approveSuggestion(
        candidateId: String,
        expectedRevision: Long,
        targetSkillId: String,
        expectedCurrentContentHash: String?
    ): SkillApprovalResult {
        started.await()
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val result = synchronized(SKILL_INDEX_LOCK) {
                    val candidate = requireCandidateNow(candidateId, expectedRevision, setOf("pending_review"))
                    require(targetSkillId.length in 1..80 && Regex("^[a-z0-9]+(-[a-z0-9]+)*$").matches(targetSkillId)) {
                        "skill_validation_failed: 目标技能标识无效"
                    }
                    val validated = validateSkillCandidateContent(candidate.name.orEmpty(), candidate.description.orEmpty(), candidate.bodyMarkdown.orEmpty(), knownTools(candidate))
                    val content = buildReviewedSkillContent(targetSkillId, validated)
                    val revisionId = computeSha256(content)
                    if (lifecycleStore.getRevisionNow(targetSkillId, revisionId)?.wasManuallyDisabled == true) {
                        throw IllegalStateException("skill_revision_retired: 该内容版本曾被手动停用，不可重新启用")
                    }
                    val entry = skillIndexService.listSkillsForManagement().firstOrNull { it.id == targetSkillId }
                    if (entry?.source == "builtin" || entry?.source == "official") {
                        throw IllegalStateException("skill_cannot_overwrite_builtin: 不可覆盖内置或官方技能")
                    }
                    val oldSnapshot = if (expectedCurrentContentHash != null) {
                        if (entry == null || !entry.installed) throw IllegalStateException("skill_candidate_not_found: 要更新的技能未安装")
                        val bytes = File(entry.skillFilePath).readBytes()
                        val hash = computeBytesSha256(bytes)
                        if (hash != expectedCurrentContentHash) throw IllegalStateException("skill_content_conflict: 当前技能内容已改变，请重新预览")
                        SkillRevisionSnapshot(targetSkillId, hash, entry.previousRevisionId, entry.name, entry.description, bytes.toString(Charsets.UTF_8))
                    } else null
                    val snapshot = SkillRevisionSnapshot(targetSkillId, revisionId, oldSnapshot?.revisionId, targetSkillId, validated.description, content, createdFromCandidateId = candidateId)
                    val staging = File(context.cacheDir, "skill-review-${UUID.randomUUID()}")
                    check(staging.mkdir()) { "skill_asset_invalid: 无法准备技能安装目录" }
                    try {
                        File(staging, "SKILL.md").writeText(content, Charsets.UTF_8)
                        var committed: SkillCandidateAsset? = null
                        val installed = skillIndexService.withReviewedSkillInstall(
                            staging.absolutePath, targetSkillId, expectedCurrentContentHash, "trial", revisionId, oldSnapshot?.revisionId
                        ) {
                            committed = lifecycleStore.commitApprovalNow(candidateId, expectedRevision, oldSnapshot, snapshot, targetSkillId)
                        }
                        SkillApprovalResult(checkNotNull(committed), installed)
                    } finally {
                        staging.deleteRecursively()
                    }
                }
                notifyCandidateChanged(result.candidate)
                result
            }
        }
    }

    suspend fun rejectSuggestion(
        candidateId: String,
        expectedRevision: Long
    ): SkillCandidateAsset {
        started.await()
        return mutationMutex.withLock {
            requireCandidate(candidateId, expectedRevision, setOf("pending_review"))
            updateCandidate(candidateId, expectedRevision) { it.copy(state = "rejected") }
        }
    }

    suspend fun regenerateSuggestion(
        candidateId: String,
        expectedRevision: Long
    ): SkillCandidateAsset {
        started.await()
        return mutationMutex.withLock {
            requireCandidate(candidateId, expectedRevision, setOf("pending_review", "rejected", "generation_failed", "interrupted", "skipped"))
            check(lifecycleStore.getSettings().autoSuggestionsEnabled) { "skill_disabled: 请先开启自动技能建议" }
            val spec = sourceJobs[candidateId]
                ?: throw IllegalStateException("skill_source_unavailable: 原任务输入未保留，无法重新生成")
            val profile = resolveProfile(spec.providerProfileId)
            if (profile == null || !profile.ready || !profile.isConfigured() ||
                computeSkillSuggestionCredentialDigest(profile) != spec.credentialDigest
            ) {
                throw IllegalStateException("skill_provider_changed: 原任务的模型或账号身份已不可用")
            }
            val capacity = resolveContextCapacity(spec.conversationId, profile, spec.modelId)
            if (capacity == null || !fitsContext(spec.sanitizedInput, capacity)) {
                throw IllegalStateException("skill_context_unavailable: 原模型上下文预算不可用或不足")
            }
            updateCandidate(candidateId, expectedRevision) {
                it.copy(state = "queued", skipReason = null, failureReason = null)
            }
            enqueue(spec)
            checkNotNull(lifecycleStore.getCandidate(candidateId))
        }
    }

    suspend fun setAutoSuggestionsEnabled(enabled: Boolean): Boolean {
        started.await()
        return mutationMutex.withLock {
            lifecycleStore.updateSettings(enabled)
            if (!enabled) {
                activeGenerationJobs.forEach { (candidateId, job) ->
                    job.cancel()
                    interruptCandidate(candidateId, "skill_disabled")
                }
                while (true) {
                    val spec = pendingJobs.tryReceive().getOrNull() ?: break
                    interruptCandidate(spec.candidateId, "skill_disabled")
                }
            }
            notifySkillAssetsChanged(null, null, null, null, null, enabled)
            enabled
        }
    }

    /**
     * 技能试用反馈（retain 或 retire）。
     * 严格条件：必须存在后续独立的正式完成且调用过 skills_read 相同 revisionId 的记录。
     */
    suspend fun feedbackSkill(
        skillId: String,
        revisionId: String,
        conversationId: Long,
        sessionId: String,
        turnId: String,
        expectedRevision: Long,
        action: String
    ): SkillFeedbackResult {
        started.await()
        require(action == "retain" || action == "retire") { "skill_validation_failed: 未知反馈操作" }
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val usages = lifecycleStore.listUsageForSkill(skillId)
                val result = synchronized(SKILL_INDEX_LOCK) {
                    val entry = skillIndexService.findInstalledSkill(skillId)
                        ?: throw IllegalArgumentException("skill_candidate_not_found: 技能未安装")
                    val registry = skillIndexService.getSkillRegistryEntry(skillId)
                        ?: throw IllegalArgumentException("skill_candidate_not_found: 技能注册信息缺失")
                    val currentHash = computeFileSha256(File(entry.skillFilePath))
                    if (currentHash != revisionId) {
                        if (entry.verificationStatus != "unverified") {
                            skillIndexService.updateSkillLifecycle(skillId, "unverified")
                        }
                        throw IllegalStateException("skill_content_modified: 技能内容已改变，旧版本反馈已失效")
                    }
                    if (registry.revision != expectedRevision) {
                        throw IllegalStateException("skill_revision_conflict: 技能版本已改变，请重新加载")
                    }
                    val snapshot = lifecycleStore.getRevisionNow(skillId, revisionId)
                    val origin = snapshot?.createdFromCandidateId?.let(lifecycleStore::getCandidateNow)
                    val usage = usages.firstOrNull {
                        it.conversationId == conversationId && it.sessionId == sessionId && it.turnId == turnId &&
                            isEligibleUsage(it, revisionId, snapshot, origin)
                    }
                    if (!entry.enabled || entry.verificationStatus != "trial" || registry.revisionId != revisionId || usage == null) {
                        throw IllegalStateException("skill_feedback_ineligible: 尚无后续独立完成任务中读取该版本的有效记录")
                    }
                    val status = if (action == "retain") "verified" else "retired"
                    if (action == "retire") lifecycleStore.markRevisionManuallyDisabledNow(skillId, revisionId)
                    skillIndexService.updateSkillLifecycle(skillId, status, enabled = action == "retain", expectedRevision = expectedRevision)
                        ?: throw IllegalStateException("skill_candidate_not_found: 技能已移除")
                    SkillFeedbackResult(skillId, status, revisionId, action == "retain", action)
                }
                notifySkillAssetsChanged(null, null, null, null, null)
                result
            }
        }
    }

    /**
     * 恢复上一版本。
     * 若目标版本曾被手动停用/退役，严禁复活。
     */
    suspend fun restoreSkillRevision(
        skillId: String,
        revisionId: String,
        expectedRevision: Long,
        expectedCurrentContentHash: String
    ): SkillRestoreResult {
        started.await()
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val result = synchronized(SKILL_INDEX_LOCK) {
                    val entry = skillIndexService.listSkillsForManagement().firstOrNull {
                        it.id == skillId && it.installed
                    } ?: throw IllegalArgumentException("skill_candidate_not_found: 技能未安装")
                    if (entry.source == "builtin" || entry.source == "official") {
                        throw IllegalStateException("skill_cannot_overwrite_builtin: 不可恢复或修改内置及官方技能")
                    }
                    if (!entry.enabled) throw IllegalStateException("skill_revision_retired: 当前技能已停用，不可通过恢复旧版重新启用")
                    val registry = skillIndexService.getSkillRegistryEntry(skillId)
                        ?: throw IllegalArgumentException("skill_candidate_not_found: 技能注册信息缺失")
                    if (registry.revision != expectedRevision) throw IllegalStateException("skill_revision_conflict: 技能版本已改变，请重新加载")
                    val target = lifecycleStore.getRevisionNow(skillId, revisionId)
                        ?: throw IllegalArgumentException("skill_candidate_not_found: 历史版本不存在")
                    if (target.wasManuallyDisabled) throw IllegalStateException("skill_revision_retired: 目标历史版本曾被停用，不可恢复")
                    if (computeSha256(target.bodyMarkdown) != revisionId) throw IllegalStateException("skill_asset_invalid: 历史版本指纹不匹配")
                    val bytes = File(entry.skillFilePath).readBytes()
                    val hash = computeBytesSha256(bytes)
                    if (hash != expectedCurrentContentHash) throw IllegalStateException("skill_content_conflict: 当前内容已改变，请重新预览")
                    val oldSnapshot = SkillRevisionSnapshot(skillId, hash, entry.previousRevisionId, entry.name, entry.description, bytes.toString(Charsets.UTF_8))
                    val staging = File(context.cacheDir, "skill-restore-${UUID.randomUUID()}")
                    check(staging.mkdir()) { "skill_asset_invalid: 无法准备版本恢复目录" }
                    try {
                        File(staging, "SKILL.md").writeText(target.bodyMarkdown, Charsets.UTF_8)
                        skillIndexService.withReviewedSkillInstall(staging.absolutePath, skillId, hash, "trial", revisionId, hash) {
                            lifecycleStore.saveRevisionNow(oldSnapshot)
                        }
                    } finally {
                        staging.deleteRecursively()
                    }
                    SkillRestoreResult(skillId, revisionId, "trial", true)
                }
                notifySkillAssetsChanged(null, null, null, null, null)
                result
            }
        }
    }

    suspend fun listSkillRevisions(skillId: String): List<SkillRevisionSnapshot> {
        return lifecycleStore.listRevisions(skillId)
    }

    suspend fun getFeedbackEligibility(skillId: String): SkillFeedbackEligibility {
        started.await()
        return withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val revisions = lifecycleStore.listRevisions(skillId)
                val usages = lifecycleStore.listUsageForSkill(skillId)
                synchronized(SKILL_INDEX_LOCK) {
                    val entry = skillIndexService.findInstalledSkill(skillId)
                    val registry = skillIndexService.getSkillRegistryEntry(skillId)
                    val hash = entry?.let { computeFileSha256(File(it.skillFilePath)) }.orEmpty()
                    val revisionId = entry?.revisionId
                    val snapshot = revisionId?.let { lifecycleStore.getRevisionNow(skillId, it) }
                    val origin = snapshot?.createdFromCandidateId?.let(lifecycleStore::getCandidateNow)
                    val usage = if (entry?.enabled == true && entry.verificationStatus == "trial" &&
                        revisionId != null && hash == revisionId
                    ) usages.firstOrNull { isEligibleUsage(it, revisionId, snapshot, origin) } else null
                    val reason = when {
                        entry == null -> "技能未安装"
                        !entry.enabled -> "技能已停用"
                        hash != revisionId -> "技能内容已改变，原版本反馈已失效"
                        entry.verificationStatus != "trial" -> "仅试用中的技能可提交反馈"
                        usage == null -> "尚无后续独立完成任务中读取该版本的有效记录"
                        else -> null
                    }
                    SkillFeedbackEligibility(skillId, revisionId, registry?.revision ?: 0L, hash, usage != null, reason,
                        usage?.let { SkillUsageIdentity(it.conversationId, it.sessionId, it.turnId) }, revisions)
                }
            }
        }
    }

    private fun isEligibleUsage(
        usage: SkillUsageRecord,
        revisionId: String,
        snapshot: SkillRevisionSnapshot?,
        origin: SkillCandidateAsset?
    ): Boolean = snapshot != null && origin != null && usage.completed &&
        usage.revisionId == revisionId && usage.contentHash == revisionId && usage.timestamp >= snapshot.createdAt &&
        !(usage.conversationId == origin.conversationId && usage.sessionId == origin.sessionId && usage.turnId == origin.turnId)

    // --- 内部辅助方法 ---

    private fun resolveProfile(profileId: String?): ModelProviderProfile? {
        if (profileId.isNullOrBlank()) return null
        return if (providerProfileResolver != null) providerProfileResolver.invoke(profileId)
        else ModelProviderConfigStore.getProfile(profileId)
    }


    private fun generateCandidateId(conversationId: Long, sessionId: String, turnId: String): String {
        val raw = "$conversationId:$sessionId:$turnId"
        val digest = MessageDigest.getInstance("SHA-256")
        val hex = digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "cand_${hex.take(16)}"
    }

    private fun buildPromptPayload(
        userPrompt: String,
        assistantAnswer: String,
        toolsSummary: List<String>,
        skillsSummary: String
    ): String {
        return buildString {
            appendLine("# 任务与交互事实")
            appendLine("## 用户任务")
            appendLine(userPrompt)
            appendLine()
            appendLine("## 最终答复")
            appendLine(assistantAnswer)
            appendLine()
            appendLine("## 执行成功的工具调用（仅参数名称）")
            toolsSummary.forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 已安装技能清单（避免重复）")
            appendLine(skillsSummary)
        }
    }

    private fun buildSystemPrompt(): String {
        return """
            你是一个经验沉淀与技能提炼专家。
            请根据提供的用户任务、工具调用步骤与执行结果，评估该任务是否包含可被未来复用的通用技能模式。

            输出规范：
            你必须仅输出纯 JSON 格式，严禁包含任何前导或后随文字、解释或代码块标记：
            1. 如果任务是一次性对话、简单查询、或不具备通用复用价值，输出：
            {"suggestion": null}

            2. 如果任务具有通用复用价值，输出：
            {
              "suggestion": {
                "name": "全小写连字符命名（如 git-release-flow）",
                "description": "简要说明适用场景（<=240字）",
                "bodyMarkdown": "完整的 SKILL.md 正文 Markdown（<=12000字）"
              }
            }

            正文要求：
            - 正文必须包含适用条件、参数化执行步骤、结果检查方法和失败/人工接手条件；
            - 将具体的人名、机器私有路径、临时账号泛化为可配置参数；
            - 严禁包含任何 API Key、密码、Token 等私密信息；
            - 严禁引用不存在的工具或虚构的权限。
        """.trimIndent()
    }


    private suspend fun processSuggestionJob(spec: SuggestionJobSpec) {
        val candidateId = spec.candidateId
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val override = mutationMutex.withLock {
                    if (lifecycleStore.getCandidate(candidateId)?.state != "queued") return@withLock null
                    if (!lifecycleStore.getSettings().autoSuggestionsEnabled) {
                        interruptCandidate(candidateId, "skill_disabled")
                        return@withLock null
                    }
                    val profile = resolveProfile(spec.providerProfileId)
                    if (profile == null || !profile.ready || !profile.isConfigured() ||
                        computeSkillSuggestionCredentialDigest(profile) != spec.credentialDigest
                    ) {
                        updateCandidate(candidateId) { it.copy(state = "skipped", skipReason = "provider_changed", failureReason = "原任务的模型或账号身份已不可用") }
                        return@withLock null
                    }
                    val capacity = resolveContextCapacity(spec.conversationId, profile, spec.modelId)
                    if (capacity == null || !fitsContext(spec.sanitizedInput, capacity)) {
                        updateCandidate(candidateId) { it.copy(state = "skipped", skipReason = "too_large", failureReason = "原模型上下文预算不可用或不足") }
                        return@withLock null
                    }
                    val resolved = AgentModelOverride.fromProviderProfile(profile, spec.modelId, capacity)
                    if (resolved == null) {
                        updateCandidate(candidateId) { it.copy(state = "skipped", skipReason = "provider_changed", failureReason = "原任务模型配置已失效") }
                        return@withLock null
                    }
                    updateCandidate(candidateId) { it.copy(state = "generating", skipReason = null, failureReason = null) }
                    resolved
                } ?: return@launch

                val generationScope = CoroutineScope(coroutineContext)
                val client = llmClientFactory?.invoke(generationScope, override) ?: HttpAgentLlmClient(generationScope, override)
                val request = XiaowanChatCompletionRequestFactory.create(
                    messages = suggestionMessages(spec.sanitizedInput),
                    maxTokens = 2048,
                    temperature = 0.2,
                    reasoningEffort = "none"
                )
                val response = withTimeout(60_000L) { client.streamTurn(request, allowTransportRetry = false) }
                response.usage?.let { usage ->
                    withContext(NonCancellable) {
                        runCatching {
                            val promptDetails = usage.promptTokensDetails as? JsonObject
                            val completionDetails = usage.completionTokensDetails as? JsonObject
                            cn.com.omnimind.baselib.database.DatabaseHelper.insertTokenUsageRecord(
                                cn.com.omnimind.baselib.database.TokenUsageRecord(
                                    conversationId = spec.conversationId,
                                    model = response.resolvedModel ?: spec.modelId,
                                    promptTokens = usage.promptTokens ?: 0,
                                    completionTokens = usage.completionTokens ?: 0,
                                    reasoningTokens = completionDetails?.get("reasoning_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                                    textTokens = completionDetails?.get("text_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                                    cachedTokens = promptDetails?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                                    cacheCreationTokens = promptDetails?.get("cache_creation_tokens")?.jsonPrimitive?.intOrNull ?: 0,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                coroutineContext.ensureActive()
                mutationMutex.withLock {
                    coroutineContext.ensureActive()
                    if (lifecycleStore.getSettings().autoSuggestionsEnabled && lifecycleStore.getCandidate(candidateId)?.state == "generating") {
                        parseAndApplyLlmResponse(candidateId, response.message.contentText())
                    }
                }
            } catch (_: TimeoutCancellationException) {
                finishGenerationFailure(candidateId, "skill_generation_timeout")
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    mutationMutex.withLock { interruptCandidate(candidateId, "skill_interrupted") }
                }
                throw error
            } catch (_: Exception) {
                finishGenerationFailure(candidateId, "skill_generation_failed")
            } finally {
                activeGenerationJobs.remove(candidateId)
            }
        }
        val accepted = mutationMutex.withLock {
            if (!lifecycleStore.getSettings().autoSuggestionsEnabled) {
                interruptCandidate(candidateId, "skill_disabled")
                false
            } else {
                activeGenerationJobs[candidateId] = job
                true
            }
        }
        if (accepted) job.start() else job.cancel()
        job.join()
    }

    private fun notifySkillAssetsChanged(
        conversationId: Long?,
        turnId: String?,
        candidateId: String?,
        state: String?,
        revision: Long?,
        enabled: Boolean? = null
    ) {
        scope.launch {
            val payload = mutableMapOf<String, Any?>()
            if (conversationId != null) payload["conversationId"] = conversationId
            if (turnId != null) payload["turnId"] = turnId
            if (candidateId != null) payload["candidateId"] = candidateId
            if (state != null) payload["state"] = state
            if (revision != null) payload["revision"] = revision
            if (enabled != null) payload["enabled"] = enabled

            runCatching {
                cn.com.omnimind.bot.webchat.FlutterChatSyncBridge.dispatchSkillAssetsChanged(
                    conversationId = conversationId,
                    turnId = turnId,
                    candidateId = candidateId,
                    state = state,
                    revision = revision,
                    enabled = enabled
                )
            }
            runCatching {
                cn.com.omnimind.bot.webchat.RealtimeHub.publish(
                    event = "skill_assets_changed",
                    data = payload
                )
            }
        }
    }

    private suspend fun resolveContextCapacity(conversationId: Long, profile: ModelProviderProfile, modelId: String): Int? =
        (contextCapacityResolver?.invoke(conversationId, profile, modelId)
            ?: if (contextCapacityResolver == null) resolveSkillSuggestionContextCapacity(conversationId, profile, modelId) else null)
            ?.takeIf { it > 0 }

    private fun suggestionMessages(input: String): List<ChatCompletionMessage> = listOf(
        ChatCompletionMessage("system", JsonPrimitive(buildSystemPrompt())),
        ChatCompletionMessage("user", JsonPrimitive(input))
    )

    private fun fitsContext(input: String, capacity: Int): Boolean =
        suggestionMessages(input).sumOf { AgentContextBudget.messageTokens(it) } + 2048L <= capacity.toLong()

    private fun knownTools(candidate: SkillCandidateAsset): Set<String> = registeredToolNames +
        candidate.toolCallsSummary.map { it.substringBefore('(').trim() }.filter { it.isNotEmpty() }

    private fun requireCandidateNow(candidateId: String, expectedRevision: Long?, states: Set<String>): SkillCandidateAsset {
        val candidate = lifecycleStore.getCandidateNow(candidateId)
            ?: throw IllegalArgumentException("skill_candidate_not_found: 候选作业不存在")
        if (expectedRevision != null && candidate.revision != expectedRevision) throw IllegalStateException("skill_revision_conflict: 候选版本已改变，请重新加载")
        check(candidate.state in states) { "skill_candidate_state_conflict: 当前候选状态不可执行此操作" }
        return candidate
    }

    private suspend fun requireCandidate(candidateId: String, expectedRevision: Long?, states: Set<String>): SkillCandidateAsset =
        withContext(Dispatchers.IO) { requireCandidateNow(candidateId, expectedRevision, states) }

    private suspend fun saveCandidate(candidate: SkillCandidateAsset) {
        lifecycleStore.saveCandidate(candidate)
        notifyCandidateChanged(candidate)
    }

    private suspend fun updateCandidate(candidateId: String, expectedRevision: Long? = null, transform: (SkillCandidateAsset) -> SkillCandidateAsset): SkillCandidateAsset {
        val candidate = lifecycleStore.updateCandidate(candidateId, expectedRevision, transform)
        notifyCandidateChanged(candidate)
        return candidate
    }

    private fun notifyCandidateChanged(candidate: SkillCandidateAsset) = notifySkillAssetsChanged(
        candidate.conversationId, candidate.turnId, candidate.candidateId, candidate.state, candidate.revision
    )

    private suspend fun enqueue(spec: SuggestionJobSpec) {
        if (pendingJobs.trySend(spec).isFailure) {
            updateCandidate(spec.candidateId) { it.copy(state = "skipped", skipReason = "busy", failureReason = "建议队列已满，可稍后手动重新生成") }
        }
    }

    private suspend fun interruptCandidate(candidateId: String, reason: String) {
        val candidate = lifecycleStore.getCandidate(candidateId) ?: return
        if (candidate.state == "queued" || candidate.state == "generating") {
            updateCandidate(candidateId, candidate.revision) { it.copy(state = "interrupted", failureReason = reason) }
        }
    }

    private suspend fun finishGenerationFailure(candidateId: String, reason: String) {
        mutationMutex.withLock {
            val candidate = lifecycleStore.getCandidate(candidateId) ?: return@withLock
            if (candidate.state == "queued" || candidate.state == "generating") {
                updateCandidate(candidateId, candidate.revision) { it.copy(state = "generation_failed", failureReason = reason) }
            }
        }
    }

    private fun computeBytesSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun parseAndApplyLlmResponse(candidateId: String, rawJson: String) {
        val parsed = JsonParser.parseString(rawJson.trim())
        require(parsed.isJsonObject) { "skill_validation_failed: 模型输出必须为 JSON 对象" }
        val root = parsed.asJsonObject
        require(root.keySet() == setOf("suggestion")) { "skill_validation_failed: 模型输出字段无效" }
        val suggestion = root.get("suggestion")
        if (suggestion.isJsonNull) {
            updateCandidate(candidateId) { it.copy(state = "skipped", skipReason = "no_suggestion", failureReason = null) }
            return
        }
        require(suggestion.isJsonObject) { "skill_validation_failed: suggestion 必须为对象或 null" }
        val fields = suggestion.asJsonObject
        require(fields.keySet() == setOf("name", "description", "bodyMarkdown")) { "skill_validation_failed: 候选字段无效" }
        fun stringField(name: String): String {
            val value = fields.get(name)
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "skill_validation_failed: 候选字段必须为文本" }
            return value.asString
        }
        val candidate = requireCandidate(candidateId, null, setOf("generating"))
        val validated = validateSkillCandidateContent(stringField("name"), stringField("description"), stringField("bodyMarkdown"), knownTools(candidate))
        updateCandidate(candidateId, candidate.revision) {
            it.copy(state = "pending_review", name = validated.name, description = validated.description,
                bodyMarkdown = validated.bodyMarkdown, targetSkillId = validated.name, skipReason = null, failureReason = null)
        }
    }


    private fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun computeFileSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
