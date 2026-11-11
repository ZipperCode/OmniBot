package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore

data class AgentToolImageContinuationPolicy(
    val supportsToolImageContinuation: Boolean,
    val routeLabel: String = "unknown"
) {
    companion object {
        val DEFAULT = AgentToolImageContinuationPolicy(
            supportsToolImageContinuation = true
        )
    }
}

object AgentToolImageContinuationPolicyResolver {
    fun resolve(
        routeInfo: HttpController.ChatCompletionRouteInfo?,
        providerProfileId: String? = null,
    ): AgentToolImageContinuationPolicy {
        if (routeInfo == null) {
            return AgentToolImageContinuationPolicy.DEFAULT
        }
        val routeLabel = buildRouteLabel(routeInfo)
        // 显式请求只携带传输参数时，仍使用已选 Provider 的能力声明。
        val visionSupport = if (!providerProfileId.isNullOrBlank() && providerProfileId != routeInfo.providerProfileId) {
            ModelProviderConfigStore.resolveRouteVisionInputSupport(
                profileId = providerProfileId,
                modelId = routeInfo.resolvedModel,
                protocolType = routeInfo.protocolType,
                apiBase = routeInfo.apiBase,
            )
        } else {
            routeInfo.providerCapabilities.supportsVisionInput
        }
        return AgentToolImageContinuationPolicy(
            // The resolved Provider capability is the only source of truth.
            // Model/vendor-name guesses made this adapter disagree with the
            // actual route and silently removed valid image continuations.
            supportsToolImageContinuation = visionSupport != false,
            routeLabel = routeLabel
        )
    }

    private fun buildRouteLabel(
        routeInfo: HttpController.ChatCompletionRouteInfo
    ): String {
        return buildList {
            add("model=${routeInfo.resolvedModel}")
            add("protocol=${routeInfo.protocolType}")
            routeInfo.providerProfileId?.takeIf { it.isNotBlank() }?.let {
                add("profile=$it")
            }
            routeInfo.routeTag?.takeIf { it.isNotBlank() }?.let {
                add("route=$it")
            }
        }.joinToString(separator = ",")
    }
}
