package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ReasoningEffort
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentLlmClient
import cn.com.omnimind.bot.agent.HttpAgentLlmClient
import cn.com.omnimind.bot.agent.AgentModelOverride
import kotlinx.coroutines.CoroutineScope

internal suspend fun createOmniFlowModelClient(
    scope: CoroutineScope,
    environment: AgentExecutionEnvironment? = null,
): OmniFlowModelClient {
    val dedicatedBinding = SceneModelBindingStore.getBinding(SceneOperationConfigStore.SCENE_ID)
    val routeInfo: HttpController.ChatCompletionRouteInfo
    val selectedProfile: ModelProviderProfile
    val routeSource: String

    if (dedicatedBinding != null) {
        routeInfo = HttpController.resolveChatCompletionRouteInfo(
            modelOrScene = SceneOperationConfigStore.SCENE_ID,
        )
        check(!routeInfo.bindingProfileMissing) { "已绑定的 Provider 不存在或配置不完整，请重新选择模型提供商" }
        val profileId = routeInfo.providerProfileId
        check(!profileId.isNullOrBlank()) { "已绑定的 Provider 不存在或配置不完整，请重新选择模型提供商" }
        val resolvedProfile = ModelProviderConfigStore.getProfile(profileId)
        check(resolvedProfile != null && resolvedProfile.isConfigured()) {
            "已绑定的 Provider 不存在或配置不完整，请重新选择模型提供商"
        }
        selectedProfile = resolvedProfile
        routeSource = OmniFlowModelExecutionConfig.ROUTE_GUI_BINDING
    } else {
        val envProviderId = environment?.modelProviderProfileId?.trim()?.takeIf(String::isNotEmpty)
        val envModelId = environment?.modelId?.trim()?.takeIf(String::isNotEmpty)
        if (envProviderId != null && envModelId != null) {
            val envProfile = ModelProviderConfigStore.getProfile(envProviderId)
            check(envProfile != null && envProfile.isConfigured()) {
                "会话 Provider 不存在或未配置：$envProviderId"
            }
            routeInfo = HttpController.resolveChatCompletionRouteInfo(
                modelOrScene = SceneOperationConfigStore.SCENE_ID,
                explicitApiBase = envProfile.baseUrl,
                explicitApiKey = envProfile.apiKey,
                explicitCustomHeaders = envProfile.customHeaders,
                explicitModel = envModelId,
                explicitProtocolType = envProfile.protocolType,
                explicitWireApi = envProfile.wireApi,
                explicitProviderProfileId = if (cn.com.omnimind.baselib.llm.ChatGptOAuthProvider.isOAuth(envProfile.sourceType)) envProfile.id else null,
            )
            selectedProfile = envProfile
            routeSource = OmniFlowModelExecutionConfig.ROUTE_CONVERSATION
        } else {
            routeInfo = HttpController.resolveChatCompletionRouteInfo(
                modelOrScene = SceneOperationConfigStore.SCENE_ID,
            )
            check(!routeInfo.bindingProfileMissing) { "已绑定的 Provider 不存在或配置不完整，请重新选择模型提供商" }
            val profileId = routeInfo.providerProfileId
            check(!profileId.isNullOrBlank()) { "未配置场景模型提供商" }
            val sharedProfile = ModelProviderConfigStore.getProfile(profileId)
            check(sharedProfile != null && sharedProfile.isConfigured()) {
                "场景模型提供商不存在或未配置：$profileId"
            }
            selectedProfile = sharedProfile
            routeSource = OmniFlowModelExecutionConfig.ROUTE_SHARED_SCENE
        }
    }

    val override = AgentModelOverride.fromProviderProfile(
        profile = selectedProfile,
        modelId = routeInfo.resolvedModel,
    )
    checkNotNull(override) { "模型提供商配置无效：${selectedProfile.id} / ${routeInfo.resolvedModel}" }
    val providerProfileSha256 = OmniFlowModelExecutionConfig.sha256Hex(override.providerProfileId)

    val cachedModel = ModelProviderConfigStore.findCachedModel(override.providerProfileId, override.modelId)
    val knownImageSupport = cachedModel?.supportsImageInput
    if (knownImageSupport == false || routeInfo.providerCapabilities.supportsVisionInput == false) {
        throw IllegalStateException("gui_model_image_input_unsupported")
    }

    val conversationId = environment?.conversationId
    val storedThreshold = if (conversationId != null && conversationId > 0) {
        AgentConversationHistoryRepository(App.instance).getConversation(conversationId)?.promptTokenThreshold
    } else {
        null
    }
    val effectiveCapacity = AgentConversationContextCompactor.resolveEffectiveContextCapacity(
        storedThreshold = storedThreshold,
        modelContextLimit = cachedModel?.contextLimit,
    )
    val compactorBudget = AgentConversationContextCompactor.outputBudgetForCapacity(effectiveCapacity)
    val positiveOutputLimit = cachedModel?.outputLimit?.takeIf { it > 0 }
    val resolvedBudget = if (positiveOutputLimit != null) {
        minOf(compactorBudget, positiveOutputLimit)
    } else {
        compactorBudget
    }

    val resolvedReasoningEffort: String?
    val resolvedEnableThinking: Boolean?
    if (cachedModel?.reasoning == false) {
        resolvedReasoningEffort = null
        resolvedEnableThinking = null
    } else {
        val explicitEffort = environment?.reasoningEffort?.trim()?.takeIf(String::isNotEmpty)
        if (explicitEffort != null) {
            val normalizedExplicit = ReasoningEffort.normalize(explicitEffort)
            if (normalizedExplicit == ReasoningEffort.NONE) {
                if (cachedModel?.reasoningCanDisable == false) {
                    resolvedReasoningEffort = null
                    resolvedEnableThinking = null
                } else {
                    resolvedReasoningEffort = ReasoningEffort.NONE
                    resolvedEnableThinking = false
                }
            } else {
                val supportedLevels = cachedModel?.supportedReasoningLevels.orEmpty()
                if (supportedLevels.isNotEmpty()) {
                    if (supportedLevels.any { it.equals(normalizedExplicit ?: explicitEffort, ignoreCase = true) }) {
                        resolvedReasoningEffort = normalizedExplicit ?: explicitEffort
                        resolvedEnableThinking = true
                    } else {
                        val defaultLevel = cachedModel?.defaultReasoningLevel?.trim()?.takeIf(String::isNotEmpty)
                        if (defaultLevel != null && supportedLevels.any { it.equals(defaultLevel, ignoreCase = true) }) {
                            resolvedReasoningEffort = defaultLevel
                            resolvedEnableThinking = true
                        } else {
                            resolvedReasoningEffort = null
                            resolvedEnableThinking = null
                        }
                    }
                } else {
                    resolvedReasoningEffort = explicitEffort
                    resolvedEnableThinking = null
                }
            }
        } else if (cachedModel?.reasoning == true) {
            if (cachedModel.reasoningDefaultEnabled == false && cachedModel.reasoningCanDisable == true) {
                resolvedReasoningEffort = ReasoningEffort.NONE
                resolvedEnableThinking = false
            } else {
                val defaultLevel = cachedModel.defaultReasoningLevel?.trim()?.takeIf(String::isNotEmpty)
                if (defaultLevel != null) {
                    if (ReasoningEffort.normalize(defaultLevel) == ReasoningEffort.NONE) {
                        if (cachedModel.reasoningCanDisable == true) {
                            resolvedReasoningEffort = ReasoningEffort.NONE
                            resolvedEnableThinking = false
                        } else {
                            resolvedReasoningEffort = null
                            resolvedEnableThinking = null
                        }
                    } else {
                        val supportedLevels = cachedModel.supportedReasoningLevels.orEmpty()
                        if (supportedLevels.isEmpty() || supportedLevels.any { it.equals(defaultLevel, ignoreCase = true) }) {
                            resolvedReasoningEffort = defaultLevel
                            resolvedEnableThinking = true
                        } else {
                            resolvedReasoningEffort = null
                            resolvedEnableThinking = null
                        }
                    }
                } else {
                    resolvedReasoningEffort = null
                    resolvedEnableThinking = null
                }
            }
        } else {
            resolvedReasoningEffort = null
            resolvedEnableThinking = null
        }
    }

    val underlyingClient = HttpAgentLlmClient(
        scope = scope,
        modelOverride = override,
    )

    return object : OmniFlowModelClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
        ): OmniFlowModelTurn {
            val isGuiScene = request.model.equals(SceneOperationConfigStore.SCENE_ID, ignoreCase = true)
            val explicitCompletionTokens = request.maxCompletionTokens
            val explicitMaxTokens = request.maxTokens
            val targetMaxCompletionTokens = when {
                explicitCompletionTokens != null -> minOf(explicitCompletionTokens, resolvedBudget)
                explicitMaxTokens != null -> null
                isGuiScene -> resolvedBudget
                else -> null
            }
            val targetMaxTokens = explicitMaxTokens?.let { minOf(it, resolvedBudget) }

            val targetReasoningEffort: String?
            val targetEnableThinking: Boolean?
            val targetThinking: cn.com.omnimind.baselib.llm.ChatCompletionThinking?

            if (isGuiScene) {
                targetReasoningEffort = resolvedReasoningEffort
                targetEnableThinking = resolvedEnableThinking
                targetThinking = null
            } else {
                if (cachedModel?.reasoning == false) {
                    targetReasoningEffort = null
                    targetEnableThinking = null
                    targetThinking = null
                } else {
                    val reqEffort = request.reasoningEffort?.trim()?.takeIf(String::isNotEmpty)
                    if (reqEffort != null && ReasoningEffort.normalize(reqEffort) == ReasoningEffort.NONE) {
                        if (cachedModel?.reasoningCanDisable == false) {
                            targetReasoningEffort = null
                            targetEnableThinking = null
                            targetThinking = null
                        } else {
                            targetReasoningEffort = ReasoningEffort.NONE
                            targetEnableThinking = false
                            targetThinking = null
                        }
                    } else {
                        targetReasoningEffort = request.reasoningEffort
                        targetEnableThinking = request.enableThinking
                        targetThinking = request.thinking
                    }
                }
            }

            val needsCopy = request.maxCompletionTokens != targetMaxCompletionTokens ||
                request.maxTokens != targetMaxTokens ||
                request.reasoningEffort != targetReasoningEffort ||
                request.enableThinking != targetEnableThinking ||
                request.thinking != targetThinking

            val effectiveRequest = if (needsCopy) {
                request.copy(
                    maxCompletionTokens = targetMaxCompletionTokens,
                    maxTokens = targetMaxTokens,
                    reasoningEffort = targetReasoningEffort,
                    enableThinking = targetEnableThinking,
                    thinking = targetThinking,
                )
            } else {
                request
            }

            val turn = underlyingClient.streamTurn(effectiveRequest, onReasoningUpdate)

            val normalizedEffort = effectiveRequest.reasoningEffort?.let {
                ReasoningEffort.normalize(it)
            }
            val executionConfig = OmniFlowModelExecutionConfig(
                schemaVersion = OmniFlowModelExecutionConfig.SCHEMA_VERSION,
                providerProfileSha256 = providerProfileSha256,
                resolvedModel = override.modelId,
                routeSource = routeSource,
                contextCapacity = effectiveCapacity,
                outputBudget = resolvedBudget,
                maxCompletionTokens = effectiveRequest.maxCompletionTokens,
                maxTokens = effectiveRequest.maxTokens,
                reasoningEffort = normalizedEffort,
                enableThinking = effectiveRequest.enableThinking,
                thinkingType = effectiveRequest.thinking?.type?.trim()?.lowercase()?.takeIf {
                    it == "enabled" || it == "disabled"
                },
                reasoningSupported = cachedModel?.reasoning,
                reasoningCanDisable = cachedModel?.reasoningCanDisable,
                supportedReasoningLevels = cachedModel?.supportedReasoningLevels
                    ?.mapNotNull { ReasoningEffort.normalize(it) }
                    ?.distinct()
                    ?.takeIf { it.isNotEmpty() },
                defaultReasoningLevel = cachedModel?.defaultReasoningLevel?.let { ReasoningEffort.normalize(it) },
            )

            return OmniFlowModelTurn(
                turn = turn,
                executionConfig = executionConfig,
            )
        }
    }
}
