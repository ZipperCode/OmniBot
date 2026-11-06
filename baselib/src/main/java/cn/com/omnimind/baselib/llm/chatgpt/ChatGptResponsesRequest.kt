package cn.com.omnimind.baselib.llm.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The ChatGPT plan's HTTP Responses contract, shared by native and external clients.
 * This only normalizes request syntax; history, item identity and execution stay with the caller.
 * https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
 */
object ChatGptResponsesRequest {
    private val omittedFields = setOf(
        "background", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
        "multi_agent", "prompt_cache_retention", "safety_identifier", "temperature",
        "top_logprobs", "top_p", "truncation", "user", "previous_response_id", "conversation", "prompt",
    )

    fun normalize(request: JsonObject): JsonObject {
        // Removing a server-side context reference would silently discard conversation history.
        for (field in listOf("previous_response_id", "conversation", "prompt")) {
            require(request[field] == null || request[field] == JsonNull) {
                "ChatGPT requires complete context in input; $field is not supported over HTTP."
            }
        }
        val input = when (val value = request["input"]) {
            is JsonArray -> value
            is JsonPrimitive -> {
                require(value.isString) { "Responses input must be an array or a string." }
                JsonArray(listOf(buildJsonObject {
                    put("role", "user")
                    put("content", value)
                }))
            }
            else -> throw IllegalArgumentException("Responses input is required.")
        }
        val flatTools = mutableListOf<JsonElement>()
        val topLevelTools = mutableListOf<JsonElement>()
        val tools = request["tools"]?.takeUnless { it == JsonNull }?.let {
            require(it is JsonArray) { "Responses tools must be an array." }
            it
        }.orEmpty()
        for (tool in tools) {
            val type = ((tool as? JsonObject)?.get("type") as? JsonPrimitive)?.content
            when (type) {
                "function", "custom" -> flatTools += tool
                "namespace", "web_search", "web_search_preview" -> topLevelTools += tool
                else -> throw IllegalArgumentException("This Responses tool type is not supported by ChatGPT plan usage.")
            }
        }
        return buildJsonObject {
            request.forEach { (key, value) ->
                if (key !in omittedFields && key !in setOf("input", "tools", "store", "stream")) put(key, value)
            }
            put("store", false)
            put("stream", true)
            if (topLevelTools.isNotEmpty()) put("tools", JsonArray(topLevelTools))
            put("input", buildJsonArray {
                if (flatTools.isNotEmpty()) add(buildJsonObject {
                    put("type", "additional_tools")
                    put("role", "developer")
                    put("tools", JsonArray(flatTools))
                })
                input.forEach { item ->
                    // System messages are developer instructions on this route. All other items,
                    // including signed reasoning and call/result ids, remain byte-for-byte values.
                    val message = item as? JsonObject
                    if ((message?.get("role") as? JsonPrimitive)?.content == "system") {
                        add(JsonObject(message + ("role" to JsonPrimitive("developer"))))
                    } else add(item)
                }
            })
        }
    }
}
