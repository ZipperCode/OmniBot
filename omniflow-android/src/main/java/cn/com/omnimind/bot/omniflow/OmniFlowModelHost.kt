package cn.com.omnimind.bot.omniflow

import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionStreamOptions
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.contentText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

data class OmniFlowModelTurn(
    val turn: ChatCompletionTurn,
    val executionConfig: OmniFlowModelExecutionConfig? = null,
)

data class OmniFlowModelExecutionConfig(
    val schemaVersion: String = SCHEMA_VERSION,
    val providerProfileSha256: String,
    val resolvedModel: String,
    val routeSource: String,
    val contextCapacity: Int,
    val outputBudget: Int,
    val maxCompletionTokens: Int? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    val enableThinking: Boolean? = null,
    val thinkingType: String? = null,
    val reasoningSupported: Boolean? = null,
    val reasoningCanDisable: Boolean? = null,
    val supportedReasoningLevels: List<String>? = null,
    val defaultReasoningLevel: String? = null,
    val omittedFields: List<String> = buildList {
        if (maxCompletionTokens == null) add("max_completion_tokens")
        if (maxTokens == null) add("max_tokens")
        if (reasoningEffort == null) add("reasoning_effort")
        if (enableThinking == null) add("enable_thinking")
        if (thinkingType == null) add("thinking_type")
        if (reasoningSupported == null) add("reasoning_supported")
        if (reasoningCanDisable == null) add("reasoning_can_disable")
        if (supportedReasoningLevels == null) add("supported_reasoning_levels")
        if (defaultReasoningLevel == null) add("default_reasoning_level")
    },
) {
    companion object {
        const val SCHEMA_VERSION = "omniflow.model-execution-config.v1"
        const val ROUTE_GUI_BINDING = "gui_binding"
        const val ROUTE_CONVERSATION = "conversation"
        const val ROUTE_SHARED_SCENE = "shared_scene"

        val KNOWN_NULLABLE_FIELDS: Set<String> = linkedSetOf(
            "max_completion_tokens",
            "max_tokens",
            "reasoning_effort",
            "enable_thinking",
            "thinking_type",
            "reasoning_supported",
            "reasoning_can_disable",
            "supported_reasoning_levels",
            "default_reasoning_level",
        )

        fun sha256Hex(value: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    fun asMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "schema_version" to schemaVersion,
        "provider_profile_sha256" to providerProfileSha256,
        "resolved_model" to resolvedModel,
        "route_source" to routeSource,
        "context_capacity" to contextCapacity,
        "output_budget" to outputBudget,
        "max_completion_tokens" to maxCompletionTokens,
        "max_tokens" to maxTokens,
        "reasoning_effort" to reasoningEffort,
        "enable_thinking" to enableThinking,
        "thinking_type" to thinkingType,
        "reasoning_supported" to reasoningSupported,
        "reasoning_can_disable" to reasoningCanDisable,
        "supported_reasoning_levels" to supportedReasoningLevels,
        "default_reasoning_level" to defaultReasoningLevel,
        "omitted_fields" to omittedFields,
    ).filterValues { it != null }
}

interface OmniFlowModelClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
    ): OmniFlowModelTurn
}

class OmniFlowModelHost(
    private val modelClient: OmniFlowModelClient,
    private val imageCompressor: (String) -> String = OmniFlowImageHelper::compressVlmImage,
    private val onReasoningUpdate: suspend (String) -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    suspend fun modelTurn(payload: Map<String, Any?>): Map<String, Any?> {
        val requestedModel = firstText(payload["model"])
        require(requestedModel.isNotEmpty()) { "model_turn_model_required" }
        val request = json.decodeFromJsonElement<ChatCompletionRequest>(
            jsonValue(mapValue(payload["request"])),
        )
        require(request.model == requestedModel) { "model_turn_request_model_mismatch" }
        val activeRequest = request.copy(
            messages = request.messages.map { message ->
                message.copy(content = compressImages(message.content))
            },
        )
        val modelTurn = modelClient.streamTurn(
            request = activeRequest,
            onReasoningUpdate = { thinking ->
                thinking.trim().takeIf(String::isNotEmpty)?.let {
                    onReasoningUpdate(it)
                }
            },
        )
        val turn = modelTurn.turn
        val resolvedModel = turn.resolvedModel?.trim().orEmpty().ifBlank { requestedModel }
        return linkedMapOf<String, Any?>(
            "requested_model" to requestedModel,
            "resolved_model" to resolvedModel,
            "tool_calls" to turn.message.toolCalls.orEmpty().map { toolCall ->
                linkedMapOf(
                    "id" to toolCall.id,
                    "type" to toolCall.type,
                    "function" to linkedMapOf(
                        "name" to toolCall.function.name,
                        "arguments" to toolCall.function.arguments,
                    ),
                )
            },
            "reasoning" to turn.reasoning.trim().takeIf(String::isNotEmpty),
            "finish_reason" to turn.finishReason,
            "usage" to usage(turn),
            "execution_config" to modelTurn.executionConfig?.asMap(),
        ).filterValues { it != null }
    }

    suspend fun completeJson(
        payload: Map<String, Any?>,
        modelOverride: String? = null,
    ): Map<String, Any?> {
        val request = jsonCompletionRequest(
            payload = payload,
            model = modelOverride ?: firstText(payload["model"], "scene.dispatch.model"),
        )
        // The provider/ACP stream owns its lifetime.  Do not synthesize a
        // failure merely because a model is slow before its next chunk.
        val modelTurn = modelClient.streamTurn(request)
        val content = submitJsonArguments(modelTurn.turn)
        return mapOf("content" to content)
    }

    private fun usage(turn: ChatCompletionTurn): Map<String, Any?>? {
        val usage = turn.usage
        val promptDetails = usage?.promptTokensDetails as? JsonObject
        val completionDetails = usage?.completionTokensDetails as? JsonObject
        val hasUsage = usage != null
        return linkedMapOf<String, Any?>(
            "model_calls" to 1,
            "responses_with_usage" to if (hasUsage) 1 else 0,
            "responses_without_usage" to if (hasUsage) 0 else 1,
            "prompt_tokens" to usage?.promptTokens,
            "completion_tokens" to usage?.completionTokens,
            "total_tokens" to usage?.totalTokens,
            "reasoning_tokens" to completionDetails.intValue("reasoning_tokens"),
            "text_tokens" to completionDetails.intValue("text_tokens"),
            "image_tokens" to promptDetails.intValue("image_tokens"),
            "cached_tokens" to promptDetails.intValue("cached_tokens"),
            "prefill_tokens_per_second" to usage?.prefillTokensPerSecond,
            "decode_tokens_per_second" to usage?.decodeTokensPerSecond,
        ).filterValues { value ->
            when (value) {
                null -> false
                is Double -> value > 0.0
                is Float -> value > 0.0f
                is Number -> value.toLong() != 0L
                else -> true
            }
        }.takeIf(Map<String, Any?>::isNotEmpty)
    }

    private fun JsonObject?.intValue(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun compressImages(content: JsonElement?): JsonElement? {
        val blocks = content as? JsonArray ?: return content
        return JsonArray(
            blocks.map { item ->
                val block = item as? JsonObject ?: return@map item
                if (block["type"]?.jsonPrimitive?.contentOrNull != "image_url") {
                    return@map item
                }
                val imageUrl = block["image_url"] as? JsonObject ?: return@map item
                val url = imageUrl["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!url.startsWith("data:image/")) return@map item
                JsonObject(
                    block + (
                        "image_url" to JsonObject(
                            imageUrl + ("url" to JsonPrimitive(imageCompressor(url))),
                        )
                    ),
                )
            },
        )
    }

    companion object {

        fun compressVlmImage(value: String): String = OmniFlowImageHelper.compressVlmImage(value)

        suspend fun completeJson(
            payload: Map<String, Any?>,
            modelOverride: String? = null,
        ): Map<String, Any?> {
            val request = jsonCompletionRequest(
                payload = payload,
                model = modelOverride ?: firstText(payload["model"], "scene.dispatch.model"),
            )
            // JSON completion is still cancelled by the owning coroutine or
            // process shutdown, but has no host-imposed wall-clock deadline.
            val content = OmniFlowPythonRuntime.completeJson(request)
            check(content.isNotBlank()) { "model_completion_empty" }
            return mapOf("content" to content)
        }

        private fun jsonCompletionRequest(
            payload: Map<String, Any?>,
            model: String,
        ): ChatCompletionRequest =
            ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive(firstText(payload["prompt"])),
                    ),
                ),
                maxCompletionTokens = (payload["max_tokens"] as? Number)?.toInt(),
                temperature = (payload["temperature"] as? Number)?.toDouble() ?: 0.1,
                stream = true,
                streamOptions = ChatCompletionStreamOptions(),
                tools = listOf(
                    ChatCompletionTool(
                        function = ChatCompletionFunction(
                            name = "submit_json",
                            description = "Submit the requested JSON object.",
                            parameters = buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put("additionalProperties", JsonPrimitive(true))
                            },
                        ),
                    ),
                ),
                toolChoice = JsonPrimitive("required"),
                parallelToolCalls = false,
            )

        private fun submitJsonArguments(turn: ChatCompletionTurn): String {
            val toolCall = turn.message.toolCalls.orEmpty().singleOrNull {
                it.function.name == "submit_json"
            }
            if (toolCall != null) {
                return toolCall.function.arguments.trim().ifBlank {
                    error("model_completion_submit_json_empty")
                }
            }
            // Some OpenAI-compatible providers ignore tool_choice=required and
            // return the requested object as ordinary assistant content. Keep
            // the structured tool path preferred, but accept a JSON object so
            // offline Function enhancement remains usable with those providers.
            val content = turn.message.contentText().trim()
            val candidate = content
                .removePrefix("```")
                .removePrefix("json")
                .removeSuffix("```")
                .trim()
            if (candidate.startsWith("{") && candidate.endsWith("}")) {
                return candidate
            }
            error("model_completion_submit_json_required")
        }
    }
}
