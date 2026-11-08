package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
/**
 * Spawns and supervises real subagents.
 *
 * Each task gets:
 *  - its own AgentOrchestrator instance using the parent harness's existing
 *    tool catalog
 *  - the parent runtime owns lifecycle and cancellation; no implicit round or token cap
 *
 * Parent cancellation propagates naturally through structured concurrency:
 * if the parent's tool call is cancelled, [supervisorScope] tears down every
 * in-flight subagent's coroutine.
 *
 * NOTE: We deliberately reuse the parent's existing [AgentToolExecutor]
 * router rather than constructing a new one. The router may own terminal,
 * browser, or plugin resources, so child orchestrators are explicitly
 * non-owners and must never dispose it. The lazy provider is required because
 * SubagentToolHandler → SubagentDispatcher → router is a construction-time
 * cycle that we break with deferred lookup.
 */
class SubagentDispatcher(
    private val llmClient: AgentLlmClient,
    private val toolExecutorProvider: () -> AgentToolExecutor,
    private val parentCatalogProvider: () -> AgentToolCatalog,
    private val eventAdapter: AgentEventAdapter,
    private val model: String,
    private val toolImageContinuationPolicy: AgentToolImageContinuationPolicy =
        AgentToolImageContinuationPolicy.DEFAULT,
    private val contextCompactorFactory: (() -> AgentContextCompactionController)? = null,
    private val config: XiaowanSubagentConfig = XiaowanSubagentConfig(),
    private val configError: Throwable? = null,
    private val subagentLlmClientFactory: ((model: String, override: AgentModelOverride?) -> AgentLlmClient)? = null,
    private val providerProfileResolver: (String) -> ModelProviderProfile? = ModelProviderConfigStore::getProfile,
    private val subagentImagePolicyResolver: ((model: String, override: AgentModelOverride?) -> AgentToolImageContinuationPolicy)? = null,
    private val subagentCompactorFactory: ((model: String, override: AgentModelOverride?, reasoningEffort: String?) -> AgentContextCompactionController)? = null,
) {

    data class SubagentTaskSpec(
        val profileId: String,
        val instruction: String
    )

    data class SubagentRunResult(
        val subagentId: String,
        val profileId: String,
        val taskIndex: Int,
        val status: String,
        val finalContent: String,
        val toolCallSummaries: List<String>,
        val errorMessage: String? = null
    )

    data class SubagentProgressEvent(
        val sequence: Long,
        val createdAtMs: Long,
        val kind: String,
        val summary: String,
        val taskIndex: Int? = null,
        val subagentId: String? = null,
        val profileId: String? = null,
        val status: String = "running",
        val toolName: String? = null
    ) {
        fun toPayload(): Map<String, Any?> {
            return linkedMapOf<String, Any?>(
                "id" to "subagent-event-$sequence",
                "seq" to sequence,
                "createdAt" to createdAtMs,
                "kind" to kind,
                "summary" to summary,
                "taskIndex" to taskIndex,
                "subagentId" to subagentId,
                "profileId" to profileId,
                "status" to status,
                "toolName" to toolName
            ).filterValues { it != null }
        }
    }

    private data class ChildModelContext(
        val model: String,
        val llmClient: AgentLlmClient,
        val toolImageContinuationPolicy: AgentToolImageContinuationPolicy,
        val contextCompactorFactory: (() -> AgentContextCompactionController?)?,
        val reasoningEffort: String?,
        val modelProviderProfileId: String?,
    )
    internal data class EffectiveModelRoute(
        val providerProfileId: String? = null,
        val modelId: String? = null
    ) {
        val isCustom: Boolean get() = providerProfileId != null && modelId != null
    }

    internal companion object {
        fun resolveEffectiveModelRoute(
            profile: SubagentProfile,
            defaultConfig: XiaowanSubagentConfig
        ): EffectiveModelRoute {
            val roleProvider = profile.providerProfileId?.trim()?.ifEmpty { null }
            val roleModel = profile.modelId?.trim()?.ifEmpty { null }
            require((roleProvider == null && roleModel == null) || (roleProvider != null && roleModel != null)) {
                "子代理角色 '${profile.id}' 模型配置错误：providerProfileId 与 modelId 必须同时指定"
            }
            if (roleProvider != null && roleModel != null) {
                return EffectiveModelRoute(roleProvider, roleModel)
            }

            val defaultProvider = defaultConfig.providerProfileId?.trim()?.ifEmpty { null }
            val defaultModel = defaultConfig.modelId?.trim()?.ifEmpty { null }
            require((defaultProvider == null && defaultModel == null) || (defaultProvider != null && defaultModel != null)) {
                "子代理模型配置错误：providerProfileId 与 modelId 必须同时指定"
            }
            if (defaultProvider != null && defaultModel != null) {
                return EffectiveModelRoute(defaultProvider, defaultModel)
            }

            return EffectiveModelRoute(null, null)
        }
    }

    private fun resolveCustomModelContext(
        providerId: String,
        modelId: String,
        reasoningEffort: String?
    ): ChildModelContext {
        val providerProfile = providerProfileResolver(providerId)
            ?: throw IllegalStateException("子代理指定的 ModelProvider 不存在: $providerId")
        val childModelOverride = AgentModelOverride.fromProviderProfile(providerProfile, modelId)
            ?: throw IllegalStateException("子代理指定的 ModelProvider 未完成配置或模型无效: $providerId / $modelId")

        val childLlm = checkNotNull(subagentLlmClientFactory) {
            "子代理独立模型缺少执行器"
        }.invoke(modelId, childModelOverride)
        val childImagePolicy = checkNotNull(subagentImagePolicyResolver) {
            "子代理独立模型缺少图像能力解析器"
        }.invoke(modelId, childModelOverride)
        val childCompactor = subagentCompactorFactory?.let { factory ->
            { factory(modelId, childModelOverride, reasoningEffort) }
        }

        return ChildModelContext(
            model = modelId,
            llmClient = childLlm,
            toolImageContinuationPolicy = childImagePolicy,
            contextCompactorFactory = childCompactor,
            reasoningEffort = reasoningEffort,
            modelProviderProfileId = providerId,
        )
    }

    suspend fun dispatch(
        parentEnv: AgentExecutionEnvironment,
        tasks: List<SubagentTaskSpec>,
        concurrency: Int,
        parentToolCallId: String? = null,
        progressReporter: (suspend (SubagentProgressEvent) -> Unit)? = null
    ): List<SubagentRunResult> {
        if (configError != null) {
            throw IllegalStateException("子代理配置读取失败，请检查配置: ${configError.message}", configError)
        }
        require(config.enabled) { "子代理功能未启用" }
        if (tasks.isEmpty()) return emptyList()
        require(concurrency > 0) { "concurrency must be positive" }

        // 校验所有任务的角色均存在且已启用
        val specsWithProfiles = tasks.map { spec ->
            spec to config.requireProfile(spec.profileId)
        }

        val reasoningEffort = config.reasoningEffort?.trim()?.ifEmpty { null }
            ?: parentEnv.reasoningEffort

        // 逐角色按本次启用的 Profile 解析模型上下文：
        // 1. 角色显式覆盖优先级最高；
        // 2. 未配置角色的使用默认子代理模型配置；
        // 3. 默认子代理模型也未配置则跟随主 Agent。
        // 相同 (providerId, modelId) pair 复用同一上下文，避免重复 hydrate 凭据与构建客户端。
        // 当所有任务均有角色 override 时，无效的默认 Provider 不得提前解析，不阻断任务。
        val contextsByRoute = mutableMapOf<EffectiveModelRoute, ChildModelContext>()
        val modelContextsByProfileId = mutableMapOf<String, ChildModelContext>()

        for ((_, profile) in specsWithProfiles) {
            if (modelContextsByProfileId.containsKey(profile.id)) continue
            val route = resolveEffectiveModelRoute(profile, config)
            val context = contextsByRoute.getOrPut(route) {
                if (route.isCustom) {
                    resolveCustomModelContext(
                        providerId = route.providerProfileId!!,
                        modelId = route.modelId!!,
                        reasoningEffort = reasoningEffort
                    )
                } else {
                    val childCompactor = {
                        subagentCompactorFactory?.invoke(model, null, reasoningEffort)
                            ?: contextCompactorFactory?.invoke()
                    }
                    ChildModelContext(
                        model = model,
                        llmClient = llmClient,
                        toolImageContinuationPolicy = toolImageContinuationPolicy,
                        contextCompactorFactory = childCompactor,
                        reasoningEffort = reasoningEffort,
                        modelProviderProfileId = parentEnv.modelProviderProfileId,
                    )
                }
            }
            modelContextsByProfileId[profile.id] = context
        }

        val limit = concurrency
        val progressSequence = AtomicLong(0)
        emitProgress(
            progressReporter,
            progressSequence,
            kind = "dispatch_started",
            summary = "正在分派 ${tasks.size} 个子任务（并发 $limit）"
        )
        val semaphore = Semaphore(limit)
        return supervisorScope {
            specsWithProfiles.mapIndexed { index, (spec, profile) ->
                val modelContext = checkNotNull(modelContextsByProfileId[profile.id])
                async {
                    semaphore.withPermit {
                        runSingleSubagent(
                            parentEnv = parentEnv,
                            taskIndex = index,
                            spec = spec,
                            profile = profile,
                            parentToolCallId = parentToolCallId,
                            modelContext = modelContext,
                            progressReporter = progressReporter,
                            progressSequence = progressSequence
                        )
                    }
                }
            }.awaitAll().sortedBy { it.taskIndex }
        }
    }

    private suspend fun runSingleSubagent(
        parentEnv: AgentExecutionEnvironment,
        taskIndex: Int,
        spec: SubagentTaskSpec,
        profile: SubagentProfile,
        parentToolCallId: String?,
        modelContext: ChildModelContext,
        progressReporter: (suspend (SubagentProgressEvent) -> Unit)?,
        progressSequence: AtomicLong
    ): SubagentRunResult {
        val subagentId = "subagent-${UUID.randomUUID().toString().take(8)}"
        return try {
            emitProgress(
                progressReporter,
                progressSequence,
                kind = "subagent_started",
                taskIndex = taskIndex,
                subagentId = subagentId,
                profileId = profile.id,
                summary = "SubAgent #${taskIndex + 1} 开始：${normalizeSubagentProgressText(spec.instruction)}"
            )
            val harnessCatalog = inheritedSubagentCatalog(parentCatalogProvider(), profile)
            val systemMessage = ChatCompletionMessage(
                role = "system",
                content = JsonPrimitive(profile.systemPrompt)
            )
            val userMessage = ChatCompletionMessage(
                role = "user",
                content = JsonPrimitive(spec.instruction)
            )
            val mappedPermissionRequester = parentEnv.permissionRequester?.let { parentRequester ->
                object : AgentPermissionRequester {
                    override suspend fun requestPermission(
                        toolCallId: String,
                        title: String,
                        detail: String
                    ): Boolean = parentRequester.requestPermission(
                        toolCallId = parentToolCallId ?: toolCallId,
                        title = title,
                        detail = detail
                    )

                    override suspend fun requestPermission(
                        toolCallId: String,
                        title: String,
                        detail: String,
                        action: String?,
                        targetResource: String?
                    ): Boolean = parentRequester.requestPermission(
                        toolCallId = parentToolCallId ?: toolCallId,
                        title = title,
                        detail = detail,
                        action = action,
                        targetResource = targetResource
                    )

                    override suspend fun requestPermission(
                        toolCallId: String,
                        title: String,
                        detail: String,
                        action: String?,
                        targetResource: String?,
                        rawInput: JsonObject?
                    ): Boolean = parentRequester.requestPermission(
                        toolCallId = parentToolCallId ?: toolCallId,
                        title = title,
                        detail = detail,
                        action = action,
                        targetResource = targetResource,
                        rawInput = rawInput
                    )
                }
            }

            val subEnv = DefaultAgentExecutionEnvironment(
                acpSessionId = parentEnv.acpSessionId,
                acpTurnId = parentEnv.acpTurnId,
                agentRunId = subagentId,
                conversationId = parentEnv.conversationId,
                userMessage = spec.instruction,
                runtimeContextRepository = parentEnv.runtimeContextRepository,
                workspaceDescriptor = parentEnv.workspaceDescriptor,
                resolvedSkills = emptyList(),
                workspaceManager = parentEnv.workspaceManager,
                workspaceMemoryService = parentEnv.workspaceMemoryService,
                conversationMode = parentEnv.conversationMode,
                reasoningEffort = modelContext.reasoningEffort,
                modelProviderProfileId = modelContext.modelProviderProfileId,
                terminalEnvironment = parentEnv.terminalEnvironment,
                runControl = NoOpAgentRunControl,
                permissionRequester = mappedPermissionRequester,
                turnJob = parentEnv.turnJob,
                longTermMemoryIndex = parentEnv.longTermMemoryIndex
            )
            val silentCallback = ReportingSubagentCallback(
                taskIndex = taskIndex,
                subagentId = subagentId,
                profileId = profile.id,
                progressReporter = progressReporter,
                progressSequence = progressSequence
            )
            val orchestrator = AgentOrchestrator(
                llmClient = modelContext.llmClient,
                toolRegistry = harnessCatalog,
                toolRouter = toolExecutorProvider(),
                eventAdapter = eventAdapter,
                model = modelContext.model,
                toolImageContinuationPolicy = modelContext.toolImageContinuationPolicy,
                ownsToolRouter = false
            )
            val result = orchestrator.run(
                AgentOrchestrator.Input(
                    callback = silentCallback,
                    initialMessages = listOf(systemMessage, userMessage),
                    executionEnv = subEnv,
                    conversationId = null,
                    contextCompactor = modelContext.contextCompactorFactory?.invoke(),
                )
            )
            when (result) {
                is AgentResult.Success -> {
                    emitProgress(
                        progressReporter,
                        progressSequence,
                        kind = "subagent_completed",
                        taskIndex = taskIndex,
                        subagentId = subagentId,
                        profileId = profile.id,
                        status = "completed",
                        summary = "SubAgent #${taskIndex + 1} 得到结果：${normalizeSubagentProgressText(result.response.content)}"
                    )
                    SubagentRunResult(
                        subagentId = subagentId,
                        profileId = profile.id,
                        taskIndex = taskIndex,
                        status = "completed",
                        finalContent = result.response.content,
                        toolCallSummaries = silentCallback.toolSummaries()
                    )
                }
                is AgentResult.Error -> {
                    val cause = result.exception
                    if (cause is CancellationException) throw cause
                    emitProgress(
                        progressReporter,
                        progressSequence,
                        kind = "subagent_failed",
                        subagentId = subagentId,
                        profileId = profile.id,
                        taskIndex = taskIndex,
                        status = "failed",
                        summary = "SubAgent #${taskIndex + 1} 失败：${normalizeSubagentProgressText(result.message)}"
                    )
                    SubagentRunResult(
                        subagentId = subagentId,
                        profileId = profile.id,
                        taskIndex = taskIndex,
                        status = "failed",
                        finalContent = "",
                        toolCallSummaries = silentCallback.toolSummaries(),
                        errorMessage = result.message
                    )
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                emitProgress(
                    progressReporter,
                    progressSequence,
                    kind = "subagent_cancelled",
                    taskIndex = taskIndex,
                    subagentId = subagentId,
                    profileId = profile.id,
                    status = "cancelled",
                    summary = "SubAgent #${taskIndex + 1} 已取消"
                )
            }
            throw ce
        } catch (e: Exception) {
            emitProgress(
                progressReporter,
                progressSequence,
                kind = "subagent_failed",
                taskIndex = taskIndex,
                subagentId = subagentId,
                profileId = profile.id,
                status = "failed",
                summary = "SubAgent #${taskIndex + 1} 失败：${normalizeSubagentProgressText(e.message ?: "subagent execution failed")}"
            )
            SubagentRunResult(
                subagentId = subagentId,
                profileId = profile.id,
                taskIndex = taskIndex,
                status = "failed",
                finalContent = "",
                toolCallSummaries = emptyList(),
                errorMessage = e.message ?: "subagent execution failed"
            )
        }
    }

    private suspend fun emitProgress(
        progressReporter: (suspend (SubagentProgressEvent) -> Unit)?,
        progressSequence: AtomicLong,
        kind: String,
        summary: String,
        taskIndex: Int? = null,
        subagentId: String? = null,
        profileId: String? = null,
        status: String = "running",
        toolName: String? = null
    ) {
        if (progressReporter == null || summary.isBlank()) return
        progressReporter(
            SubagentProgressEvent(
                sequence = progressSequence.incrementAndGet(),
                createdAtMs = System.currentTimeMillis(),
                kind = kind,
                summary = summary,
                taskIndex = taskIndex,
                subagentId = subagentId,
                profileId = profileId,
                status = status,
                toolName = toolName
            )
        )
    }

}

/** Role permissions narrow the parent catalog; they never add capabilities or execution budgets. */
internal fun inheritedSubagentCatalog(
    parent: AgentToolCatalog,
    profile: SubagentProfile = SubagentProfileRegistry.general,
): AgentToolCatalog = SubagentToolCatalogView(parent, profile)

/**
 * Callback that swallows subagent streaming output (we don't want subagent
 * intermediate text leaking into the parent's chat stream) while capturing
 * tool-call names so the dispatcher can summarize what the subagent did.
 */
private class ReportingSubagentCallback(
    private val taskIndex: Int,
    private val subagentId: String,
    private val profileId: String,
    private val progressReporter: (suspend (SubagentDispatcher.SubagentProgressEvent) -> Unit)?,
    private val progressSequence: AtomicLong
) : AgentCallback {
    private val tools = mutableListOf<String>()
    private var lastThinkingSummary: String = ""

    fun toolSummaries(): List<String> = tools.toList()

    override suspend fun onThinkingStart() {
        emit(kind = "thinking_started", summary = "SubAgent #${taskIndex + 1} 开始思考")
    }

    override suspend fun onThinkingUpdate(thinking: String) {
        val summary = compactThinking(thinking)
        if (summary.isBlank() || summary == lastThinkingSummary) {
            return
        }
        if (
            lastThinkingSummary.isNotBlank() &&
            summary.startsWith(lastThinkingSummary) &&
            summary.length - lastThinkingSummary.length < 32
        ) {
            return
        }
        lastThinkingSummary = summary
        emit(
            kind = "thinking",
            summary = "SubAgent #${taskIndex + 1} 思考：$summary"
        )
    }

    override suspend fun onToolCallStart(
        toolName: String,
        arguments: kotlinx.serialization.json.JsonObject
    ) {
        tools.add(toolName)
        emit(
            kind = "tool_started",
            summary = "SubAgent #${taskIndex + 1} 调用工具：$toolName",
            toolName = toolName
        )
    }

    override suspend fun onToolCallProgress(
        toolName: String,
        progress: String,
        extras: Map<String, Any?>
    ) {
        val text = progress.trim()
        if (text.isNotEmpty()) {
            emit(
                kind = "tool_progress",
                summary = "SubAgent #${taskIndex + 1} 工具进度：$text",
                toolName = toolName
            )
        }
    }

    override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) {
        val success = result !is ToolExecutionResult.Error
        emit(
            kind = "tool_completed",
            status = if (success) "completed" else "failed",
            summary = "SubAgent #${taskIndex + 1} 工具完成：$toolName",
            toolName = toolName
        )
    }

    override suspend fun onChatMessage(message: String) {
        val text = normalizeSubagentProgressText(message)
        if (text.isNotEmpty()) {
            emit(kind = "message", summary = "SubAgent #${taskIndex + 1} 输出：$text")
        }
    }
    override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
    override suspend fun onComplete(result: AgentResult) = Unit
    override suspend fun onError(error: String) = Unit
    override suspend fun onPermissionRequired(missing: List<String>) = Unit

    private suspend fun emit(
        kind: String,
        summary: String,
        status: String = "running",
        toolName: String? = null
    ) {
        if (progressReporter == null || summary.isBlank()) return
        progressReporter(
            SubagentDispatcher.SubagentProgressEvent(
                sequence = progressSequence.incrementAndGet(),
                createdAtMs = System.currentTimeMillis(),
                kind = kind,
                summary = summary,
                taskIndex = taskIndex,
                subagentId = subagentId,
                profileId = profileId,
                status = status,
                toolName = toolName
            )
        )
    }

    private fun compactThinking(text: String): String {
        val lines = text
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val candidate = lines.lastOrNull().orEmpty()
        return normalizeSubagentProgressText(candidate)
    }
}

internal fun normalizeSubagentProgressText(text: String): String = text
    .replace(Regex("\\s+"), " ")
    .trim()
