package cn.com.omnimind.assists.controller.http

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionProtocolMetadata
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.GeminiMessageProtocolState
import cn.com.omnimind.assists.controller.http.HttpController.ModelAvailabilityCheckResult
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.ProviderModelCapabilities
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.contentText
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini 原生协议转换与适配器。
 *
 * Copyright (c) 2025-2026 OmniBot contributors.
 * Ported from @earendil-works/pi-ai 1.4.0 (MIT License):
 *   - tools/pi-acp/node_modules/@earendil-works/pi-ai/dist/api/google-generative-ai.js
 *   - tools/pi-acp/node_modules/@earendil-works/pi-ai/dist/api/google-shared.js
 */
object GeminiProtocolAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    private val REGEX_BASE64_SIGNATURE = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val REGEX_GEMINI_3_SERIES = Regex("gemini-3(?:\\.\\d+)?-(?:pro|flash)", RegexOption.IGNORE_CASE)
    private val REGEX_GEMINI_4_PLUS = Regex("gemini-(?:[4-9]|[1-9]\\d)", RegexOption.IGNORE_CASE)
    private val REGEX_GEMMA_4 = Regex("gemma-?4", RegexOption.IGNORE_CASE)
    private val REGEX_GEMINI_MAJOR = Regex("^gemini(?:-live)?-(\\d+)", RegexOption.IGNORE_CASE)
    private val REGEX_ACTION_COLON = Regex(":[a-zA-Z]+.*")

    private val BLOCKED_FINISH_REASONS = setOf(
        "SAFETY",
        "BLOCKLIST",
        "PROHIBITED_CONTENT",
        "SPII",
        "IMAGE_SAFETY",
        "IMAGE_PROHIBITED_CONTENT",
        "IMAGE_RECITATION",
        "IMAGE_OTHER",
        "RECITATION",
        "MALFORMED_FUNCTION_CALL",
        "UNEXPECTED_TOOL_CALL",
        "TOO_MANY_TOOL_CALLS",
        "NO_IMAGE",
        "OTHER"
    )


    // ---- URL 与 Request 构建 ----

    /**
     * 若输入 URL 包含具名动作与模型路径（如 /models/id:generateContent、/models 等且未以 # 结尾），
     * 将其剥离回 base URL，与 Flutter 层保持一致。
     */
    internal fun stripGeminiActionAndModelSuffix(rawUrl: String): String {
        var url = rawUrl.trimEnd('/')
        if (url.contains('?')) {
            url = url.substringBefore('?')
        }
        val colonIdx = url.lastIndexOf(':')
        if (colonIdx != -1) {
            val slashAfter = url.indexOf('/', colonIdx)
            if (slashAfter == -1 && REGEX_ACTION_COLON.matches(url.substring(colonIdx))) {
                url = url.substring(0, colonIdx).trimEnd('/')
            }
        }
        val modelsIdx = url.lastIndexOf("/models")
        if (modelsIdx != -1) {
            val afterModels = url.substring(modelsIdx + "/models".length)
            if (afterModels.isEmpty() || afterModels.startsWith('/')) {
                url = url.substring(0, modelsIdx).trimEnd('/')
            }
        }
        val tunedIdx = url.lastIndexOf("/tunedModels")
        if (tunedIdx != -1) {
            val afterTuned = url.substring(tunedIdx + "/tunedModels".length)
            if (afterTuned.isEmpty() || afterTuned.startsWith('/')) {
                url = url.substring(0, tunedIdx).trimEnd('/')
            }
        }
        return url
    }

    /**
     * 解析 Gemini URL root：
     * - 无 path root -> 追加 /v1beta；
     * - 显式 /v1 或 /v1beta 保留；
     * - 非空自定义 proxy path 保留而不追加 /v1beta（与 SDK apiVersion="" 一致）；
     * - 若带 # 则保留原语义直通。
     */
    fun resolveGeminiUrlRoot(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase).trimEnd('/')
        val stripped = stripGeminiActionAndModelSuffix(base)
        val uri = runCatching { URI(stripped) }.getOrNull()
        val path = uri?.path?.trimEnd('/').orEmpty()
        return if (path.isEmpty()) {
            "$stripped/v1beta"
        } else {
            stripped
        }
    }

    /**
     * 构建 Gemini 推理端点 URL：
     * - stream: streamGenerateContent?alt=sse
     * - non-stream: generateContent
     * 模型 ID 允许 models/ 或 tunedModels/ 前缀但不双写且正确 URL encode。
     */
    fun buildGeminiInferenceUrl(apiBase: String, model: String, stream: Boolean): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase).trimEnd('/')
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        val root = resolveGeminiUrlRoot(apiBase)
        val trimmedModel = model.trim()
        val isTuned = trimmedModel.startsWith("tunedModels/")
        val prefix = if (isTuned) "tunedModels/" else "models/"
        val rawModelName = trimmedModel.removePrefix("models/").removePrefix("tunedModels/")
        val encodedModel = URLEncoder.encode(rawModelName, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        return "$root/$prefix$encodedModel:$action"
    }

    /**
     * 构建 Gemini /models 列表端点 URL。
     */
    fun buildGeminiModelsUrl(apiBase: String): String {
        val base = ModelProviderConfigStore.stripDirectRequestUrlMarker(apiBase).trimEnd('/')
        if (ModelProviderConfigStore.hasDirectRequestUrlMarker(apiBase)) {
            return base
        }
        val root = resolveGeminiUrlRoot(apiBase)
        return "$root/models"
    }

    /**
     * 构建请求构造器，默认添加 x-goog-api-key 与 Content-Type，支持 customHeaders 覆盖。
     */
    fun buildGeminiRequestBuilder(
        url: String,
        requestBody: okhttp3.RequestBody? = null,
        apiKey: String? = null,
        customHeaders: Map<String, String> = emptyMap()
    ): Request.Builder {
        val headers = linkedMapOf(
            "Content-Type" to "application/json"
        ).apply {
            if (!apiKey.isNullOrBlank()) {
                put("x-goog-api-key", apiKey.trim())
            }
        }
        val merged = ProviderCustomHeaderUtils.mergeHeaders(headers, customHeaders)
        val builder = Request.Builder().url(url)
        merged.forEach { (key, value) ->
            builder.header(key, value)
        }
        if (requestBody != null) {
            builder.post(requestBody)
        }
        return builder
    }

    // ---- 思考与预算策略 (来自 upstream google-shared.js / google-generative-ai.js) ----

    internal fun usesGoogleThinkingLevel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return REGEX_GEMINI_3_SERIES.containsMatchIn(id) ||
            REGEX_GEMINI_4_PLUS.containsMatchIn(id) ||
            id == "gemini-flash-latest" ||
            id == "gemini-flash-lite-latest" ||
            REGEX_GEMMA_4.containsMatchIn(id)
    }

    internal fun getGoogleBudget(modelId: String, level: String): Int {
        val id = modelId.lowercase()
        return when {
            id.contains("2.5-pro") -> when (level) {
                "minimal" -> 128
                "low" -> 2048
                "medium" -> 8192
                "high", "max", "xhigh" -> 32768
                else -> 8192
            }
            id.contains("2.5-flash-lite") -> when (level) {
                "minimal" -> 512
                "low" -> 2048
                "medium" -> 8192
                "high", "max", "xhigh" -> 24576
                else -> 8192
            }
            id.contains("2.5-flash") -> when (level) {
                "minimal" -> 128
                "low" -> 2048
                "medium" -> 8192
                "high", "max", "xhigh" -> 24576
                else -> 8192
            }
            else -> when (level) {
                "minimal" -> 128
                "low" -> 2048
                "medium" -> 8192
                "high", "max", "xhigh" -> 24576
                else -> -1
            }
        }
    }

    internal fun buildThinkingConfig(
        modelId: String,
        enableThinking: Boolean?,
        thinking: ChatCompletionThinking?,
        reasoningEffort: String?
    ): JSONObject? {
        val disabled = enableThinking == false ||
            thinking?.type == "disabled" ||
            reasoningEffort == "off" ||
            reasoningEffort == "none"
        if (disabled) {
            return JSONObject().apply {
                put("thinkingBudget", 0)
            }
        }

        val enabled = enableThinking == true ||
            thinking?.type == "enabled" ||
            !reasoningEffort.isNullOrBlank()
        if (!enabled) return null

        val effort = reasoningEffort?.lowercase() ?: "medium"
        val config = JSONObject().apply {
            put("includeThoughts", true)
        }
        if (usesGoogleThinkingLevel(modelId)) {
            val level = when (effort) {
                "minimal" -> "MINIMAL"
                "low" -> "LOW"
                "medium" -> "MEDIUM"
                "high", "max", "xhigh" -> "HIGH"
                else -> "MEDIUM"
            }
            config.put("thinkingLevel", level)
        } else {
            val budget = getGoogleBudget(modelId, effort)
            if (budget > 0) {
                config.put("thinkingBudget", budget)
            }
        }
        return config
    }

    // ---- 工具与模型版本兼容 ----

    private fun getGeminiMajorVersion(modelId: String): Int? {
        val match = REGEX_GEMINI_MAJOR.find(modelId.trim()) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    internal fun requiresToolCallId(modelId: String): Boolean {
        val trimmed = modelId.trim().lowercase()
        val major = getGeminiMajorVersion(trimmed)
        return trimmed.startsWith("claude-") ||
            trimmed.startsWith("gpt-oss-") ||
            (major != null && major >= 3)
    }

    internal fun supportsMultimodalFunctionResponse(modelId: String): Boolean {
        val major = getGeminiMajorVersion(modelId.trim().lowercase())
        return if (major != null) major >= 3 else true
    }

    internal fun supportsGoogleStrictToolSampling(modelId: String): Boolean {
        val major = getGeminiMajorVersion(modelId.trim().lowercase())
        return major != null && major >= 3
    }

    class UnsupportedStrictJsonSchemaError(message: String) : IllegalArgumentException(message)

    private val UNSUPPORTED_STRICT_SCHEMA_KEYS = listOf(
        "\$ref",
        "\$defs",
        "definitions",
        "allOf",
        "oneOf",
        "patternProperties",
        "dependentSchemas",
        "dependencies",
        "unevaluatedProperties",
        "propertyNames",
        "contains",
        "prefixItems",
        "not",
        "if",
        "then",
        "else"
    )

    private fun isStructuredSchema(schema: Any?): Boolean {
        if (schema !is JSONObject) return false
        val typeStr = schema.optString("type", "")
        val typeArr = schema.optJSONArray("type")
        val types = when {
            typeStr.isNotEmpty() -> listOf(typeStr)
            typeArr != null -> (0 until typeArr.length()).map { typeArr.optString(it) }
            else -> emptyList()
        }
        return types.contains("object") ||
            types.contains("array") ||
            schema.has("properties") ||
            schema.has("items")
    }

    private fun schemaAllowsNull(schema: Any?): Boolean {
        if (schema !is JSONObject) return false
        val typeStr = schema.optString("type", "")
        if (typeStr == "null") return true
        val typeArr = schema.optJSONArray("type")
        if (typeArr != null) {
            for (i in 0 until typeArr.length()) {
                if (typeArr.optString(i) == "null") return true
            }
        }
        if (schema.has("const") && schema.isNull("const")) return true
        val enumArr = schema.optJSONArray("enum")
        if (enumArr != null) {
            for (i in 0 until enumArr.length()) {
                if (enumArr.isNull(i)) return true
            }
        }
        val anyOfArr = schema.optJSONArray("anyOf")
        if (anyOfArr != null) {
            for (i in 0 until anyOfArr.length()) {
                val variant = anyOfArr.opt(i)
                if (schemaAllowsNull(variant)) return true
            }
        }
        return false
    }

    private fun makeJsonSchemaNodeStrict(schema: Any?) {
        if (schema !is JSONObject) {
            throw UnsupportedStrictJsonSchemaError("严格 Schema 不支持布尔型或非对象 schema")
        }
        for (key in UNSUPPORTED_STRICT_SCHEMA_KEYS) {
            if (schema.has(key)) {
                throw UnsupportedStrictJsonSchemaError("严格 Schema 不支持关键字: $key")
            }
        }
        if (schema.has("anyOf")) {
            val anyOfArr = schema.optJSONArray("anyOf")
            if (anyOfArr == null || anyOfArr.length() == 0) {
                throw UnsupportedStrictJsonSchemaError("anyOf 必须包含至少一个子 schema")
            }
            for (i in 0 until anyOfArr.length()) {
                val variant = anyOfArr.get(i)
                if (isStructuredSchema(variant)) {
                    throw UnsupportedStrictJsonSchemaError("严格 Schema 不支持对象与数组联合 (anyOf)")
                }
                makeJsonSchemaNodeStrict(variant)
            }
        }
        if (schema.has("items")) {
            val items = schema.get("items")
            if (items is JSONArray) {
                throw UnsupportedStrictJsonSchemaError("严格 Schema 不支持元组 (tuple items)")
            }
            makeJsonSchemaNodeStrict(items)
        }
        val isObjectSchema = schema.optString("type") == "object"
        if (schema.has("properties") && !isObjectSchema) {
            throw UnsupportedStrictJsonSchemaError("声明 properties 的节点必须指定 type 为 object")
        }
        if (!isObjectSchema) return

        if (schema.has("additionalProperties") && schema.optBoolean("additionalProperties", true)) {
            throw UnsupportedStrictJsonSchemaError("严格 Schema 的 additionalProperties 必须为 false，不支持 true 或 schema 对象")
        }
        if (schema.has("properties") && schema.optJSONObject("properties") == null) {
            throw UnsupportedStrictJsonSchemaError("严格 Schema 的 properties 必须为对象映射")
        }
        if (schema.has("required")) {
            val reqArr = schema.optJSONArray("required")
            if (reqArr == null) {
                throw UnsupportedStrictJsonSchemaError("严格 Schema 的 required 必须为字符串数组")
            }
            for (i in 0 until reqArr.length()) {
                if (reqArr.opt(i) !is String) {
                    throw UnsupportedStrictJsonSchemaError("严格 Schema 的 required 必须为字符串数组")
                }
            }
        }

        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val propNames = mutableListOf<String>()
        val keys = properties.keys()
        while (keys.hasNext()) {
            propNames.add(keys.next())
        }
        val origRequiredSet = mutableSetOf<String>()
        val origReqArr = schema.optJSONArray("required")
        if (origReqArr != null) {
            for (i in 0 until origReqArr.length()) {
                origRequiredSet.add(origReqArr.getString(i))
            }
        }
        for (reqKey in origRequiredSet) {
            if (!propNames.contains(reqKey)) {
                throw UnsupportedStrictJsonSchemaError("required 包含了未在 properties 中定义的未知属性: $reqKey")
            }
        }

        for (propName in propNames) {
            val prop = properties.get(propName)
            makeJsonSchemaNodeStrict(prop)
            if (!origRequiredSet.contains(propName) && !schemaAllowsNull(prop)) {
                val anyOf = JSONArray().apply {
                    put(prop)
                    put(JSONObject().apply { put("type", "null") })
                }
                properties.put(propName, JSONObject().apply {
                    put("anyOf", anyOf)
                })
            }
        }
        schema.put("required", JSONArray(propNames))
        schema.put("additionalProperties", false)
    }

    /**
     * 将工具 schema 转换为 Gemini 3+ VALIDATED 模式所需的严格子集（完全复刻 Pi SDK makeStrictJsonSchema）：
     * - 校验并拒绝 UNSUPPORTED_STRICT_SCHEMA_KEYS（如 $ref, $defs, oneOf 等）
     * - 校验 boolean schema / tuple items / object-array union / unknown required 属性等
     * - additionalProperties 必须为 false，若显式设为 true 或 schema 对象则抛出 UnsupportedStrictJsonSchemaError
     * - 原 optional 字段若不可为 null，添加 { anyOf: [property, { type: "null" }] }
     * - 所有属性均列入 required
     */
    internal fun makeStrictJsonSchema(schema: JSONObject): JSONObject {
        val cloned = JSONObject(schema.toString())
        makeJsonSchemaNodeStrict(cloned)
        if (cloned.optString("type") != "object") {
            throw UnsupportedStrictJsonSchemaError("严格 Schema 根节点的 type 必须为 object")
        }
        return cloned
    }

    internal fun sanitizeSurrogates(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch.isHighSurrogate()) {
                if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    sb.append(ch)
                    sb.append(text[i + 1])
                    i += 2
                    continue
                }
                i++ // 单独 high surrogate，丢弃
            } else if (ch.isLowSurrogate()) {
                i++ // 单独 low surrogate，丢弃
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    internal fun isValidThoughtSignature(signature: String?): Boolean {
        if (signature.isNullOrEmpty()) return false
        if (signature.length % 4 != 0) return false
        return REGEX_BASE64_SIGNATURE.matches(signature)
    }

    internal fun retainThoughtSignature(existing: String?, incoming: String?): String? {
        return if (!incoming.isNullOrEmpty()) incoming else existing
    }


    internal fun resolveGoogleFunctionCallingMode(
        tools: List<cn.com.omnimind.baselib.llm.ChatCompletionTool>,
        toolChoice: JsonElement?,
        modelId: String
    ): String {
        val allowedNames = extractAllowedFunctionNames(toolChoice)
        if (allowedNames.isNotEmpty()) {
            return "ANY"
        }
        val choiceStr = when (toolChoice) {
            is JsonPrimitive -> toolChoice.contentOrNull?.lowercase()
            is JsonObject -> {
                val mode = toolChoice["mode"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val type = toolChoice["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                when {
                    mode != null -> mode
                    type == "function" -> "any"
                    else -> null
                }
            }
            else -> null
        }
        if (choiceStr == "none") return "NONE"
        if (choiceStr == "required" || choiceStr == "any") return "ANY"

        val supportsStrict = supportsGoogleStrictToolSampling(modelId)
        val useStrictMode = supportsStrict && tools.any { it.function.strict == true }
        if (useStrictMode) {
            return "VALIDATED"
        }
        return "AUTO"
    }

    internal fun resolveFunctionCallingMode(toolChoice: JsonElement?): String =
        resolveGoogleFunctionCallingMode(emptyList(), toolChoice, "")

    private fun extractAllowedFunctionNames(toolChoice: JsonElement?): List<String> {
        val obj = toolChoice as? JsonObject ?: return emptyList()
        val fnObj = obj["function"] as? JsonObject ?: return emptyList()
        val name = fnObj["name"]?.jsonPrimitive?.contentOrNull?.trim()
        return if (!name.isNullOrEmpty()) listOf(name) else emptyList()
    }

    // ---- 请求转换 (ChatCompletionRequest -> Gemini GenerateContent JSON) ----

    fun convertToGeminiRequestJson(request: ChatCompletionRequest): String {
        val root = JSONObject()

        // 1. systemInstruction 提取
        val systemMessages = request.messages.filter { it.role == "system" }
        if (systemMessages.isNotEmpty()) {
            val systemText = systemMessages.mapNotNull { it.contentText().takeIf { t -> t.isNotEmpty() } }
                .joinToString("\n\n")
            if (systemText.isNotEmpty()) {
                val instructionObj = JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", sanitizeSurrogates(systemText))
                    }))
                }
                root.put("systemInstruction", instructionObj)
            }
        }

        // 2. contents 转换
        val conversationMessages = request.messages.filter { it.role != "system" }
        val contents = JSONArray()
        var currentToolResponseTurn: JSONObject? = null

        conversationMessages.forEach { msg ->
            when (msg.role) {
                "user" -> {
                    currentToolResponseTurn = null
                    val parts = buildUserParts(msg.content)
                    if (parts.length() > 0) {
                        contents.put(JSONObject().apply {
                            put("role", "user")
                            put("parts", parts)
                        })
                    }
                }
                "assistant" -> {
                    currentToolResponseTurn = null
                    val parts = buildAssistantParts(msg, request.model)
                    if (parts.length() > 0) {
                        contents.put(JSONObject().apply {
                            put("role", "model")
                            put("parts", parts)
                        })
                    }
                }
                "tool" -> {
                    val buildResult = buildFunctionResponse(msg, request.messages, request.model)
                    if (currentToolResponseTurn != null) {
                        currentToolResponseTurn!!.getJSONArray("parts").put(buildResult.functionResponsePart)
                    } else {
                        val turn = JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(buildResult.functionResponsePart))
                        }
                        currentToolResponseTurn = turn
                        contents.put(turn)
                    }
                    if (buildResult.fallbackUserImageTurn != null) {
                        currentToolResponseTurn = null
                        contents.put(buildResult.fallbackUserImageTurn)
                    }
                }
                else -> {
                    currentToolResponseTurn = null
                    val text = msg.contentText()
                    if (text.isNotEmpty()) {
                        contents.put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().apply {
                                put("text", sanitizeSurrogates(text))
                            }))
                        })
                    }
                }
            }
        }
        root.put("contents", contents)

        // 3. tools 与 toolConfig
        if (request.tools.isNotEmpty()) {
            val fnDeclarations = JSONArray()
            val supportsStrict = supportsGoogleStrictToolSampling(request.model)
            request.tools.forEach { tool ->
                val fnObj = JSONObject().apply {
                    put("name", tool.function.name)
                    if (tool.function.description.isNotEmpty()) {
                        put("description", tool.function.description)
                    }
                    val paramsJson = JSONObject(tool.function.parameters.toString())
                    val effectiveParams = if (supportsStrict && tool.function.strict == true) {
                        makeStrictJsonSchema(paramsJson)
                    } else {
                        paramsJson
                    }
                    put("parametersJsonSchema", effectiveParams)
                }
                fnDeclarations.put(fnObj)
            }
            root.put("tools", JSONArray().put(JSONObject().apply {
                put("functionDeclarations", fnDeclarations)
            }))

            val mode = resolveGoogleFunctionCallingMode(request.tools, request.toolChoice, request.model)
            val allowedNames = extractAllowedFunctionNames(request.toolChoice)
            val fnCallingConfig = JSONObject().apply {
                put("mode", mode)
                if (allowedNames.isNotEmpty()) {
                    put("allowedFunctionNames", JSONArray(allowedNames))
                }
            }
            root.put("toolConfig", JSONObject().apply {
                put("functionCallingConfig", fnCallingConfig)
            })
        }

        // 4. generationConfig
        val genConfig = JSONObject()
        request.temperature?.let { genConfig.put("temperature", it) }
        request.topP?.let { genConfig.put("topP", it) }
        val maxTokens = request.maxTokens ?: request.maxCompletionTokens
        maxTokens?.let { genConfig.put("maxOutputTokens", it) }

        // response_format
        val responseFormat = request.responseFormat
        if (responseFormat != null) {
            val type = responseFormat["type"]?.jsonPrimitive?.contentOrNull
            if (type == "json_object" || type == "json_schema") {
                genConfig.put("responseMimeType", "application/json")
                val schema = responseFormat["json_schema"] as? JsonObject
                val schemaObj = schema?.get("schema") as? JsonObject
                if (schemaObj != null) {
                    val schemaJson = JSONObject(schemaObj.toString())
                    genConfig.put("responseJsonSchema", schemaJson)
                }
            }
        }

        // thinkingConfig
        buildThinkingConfig(
            modelId = request.model,
            enableThinking = request.enableThinking,
            thinking = request.thinking,
            reasoningEffort = request.reasoningEffort
        )?.let {
            genConfig.put("thinkingConfig", it)
        }

        if (genConfig.length() > 0) {
            root.put("generationConfig", genConfig)
        }

        return root.toString()
    }

    private fun buildUserParts(content: JsonElement?): JSONArray {
        val parts = JSONArray()
        when (content) {
            null -> Unit
            is JsonPrimitive -> {
                val text = content.contentOrNull.orEmpty()
                if (text.isNotEmpty()) {
                    parts.put(JSONObject().apply {
                        put("text", sanitizeSurrogates(text))
                    })
                }
            }
            is JsonArray -> {
                content.forEach { block ->
                    val blockObj = block as? JsonObject ?: return@forEach
                    val type = blockObj["type"]?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "text" -> {
                            val text = blockObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (text.isNotEmpty()) {
                                parts.put(JSONObject().apply {
                                    put("text", sanitizeSurrogates(text))
                                })
                            }
                        }
                        "image_url" -> {
                            val imageUrlObj = blockObj["image_url"] as? JsonObject
                            val url = imageUrlObj?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                            appendImagePart(parts, url)
                        }
                        "inline_data", "inlineData" -> {
                            parts.put(JSONObject(blockObj.toString()))
                        }
                        "file_data", "fileData" -> {
                            parts.put(JSONObject(blockObj.toString()))
                        }
                    }
                }
            }
            is JsonObject -> {
                val text = content["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (text.isNotEmpty()) {
                    parts.put(JSONObject().apply {
                        put("text", sanitizeSurrogates(text))
                    })
                }
            }
        }
        return parts
    }

    private fun appendImagePart(parts: JSONArray, url: String) {
        if (url.startsWith("data:")) {
            val commaIdx = url.indexOf(',')
            if (commaIdx != -1) {
                val header = url.substring(5, commaIdx)
                val mimeType = header.substringBefore(';')
                val base64Data = url.substring(commaIdx + 1)
                parts.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", mimeType)
                        put("data", base64Data)
                    })
                })
                return
            }
        }
        parts.put(JSONObject().apply {
            put("fileData", JSONObject().apply {
                put("fileUri", url)
                put("mimeType", "image/jpeg")
            })
        })
    }

    private fun buildAssistantParts(message: ChatCompletionMessage, targetModel: String): JSONArray {
        // 若存在同模型保存的 Gemini 原生 parts，直接按原样复用（保留签名）
        val replayParts = resolveGeminiReplayParts(message, targetModel)
        if (replayParts != null) {
            return JSONArray(replayParts.toString())
        }

        // 跨模型或来源未知：根据 transformMessages 规范过滤掉跨模型 thinking（绝不转入普通正文，避免模型模仿或视为用户可见回复），仅保留正文与工具调用
        val parts = JSONArray()
        val text = message.contentText()
        if (text.isNotEmpty()) {
            parts.put(JSONObject().apply {
                put("text", sanitizeSurrogates(text))
            })
        }
        message.toolCalls.orEmpty().forEach { toolCall ->
            val fnObj = JSONObject().apply {
                put("name", toolCall.function.name)
                val argsObj = runCatching { JSONObject(toolCall.function.arguments) }
                    .getOrElse { JSONObject() }
                put("args", argsObj)
                if (requiresToolCallId(targetModel) && toolCall.id.isNotEmpty()) {
                    put("id", toolCall.id)
                }
            }
            parts.put(JSONObject().apply {
                put("functionCall", fnObj)
            })
        }
        return parts
    }

    internal fun resolveGeminiReplayParts(message: ChatCompletionMessage, targetModel: String): JsonArray? {
        val geminiState = message.protocolState?.gemini ?: return null
        val sourceModel = geminiState.sourceModel?.trim()
        if (sourceModel.isNullOrEmpty() || sourceModel != targetModel.trim()) {
            return null
        }
        return geminiState.parts?.takeIf { it.isNotEmpty() }
    }

    private data class ToolResultExtraction(
        val text: String,
        val imageParts: List<JSONObject>
    )

    private fun extractToolResultContent(content: JsonElement?): ToolResultExtraction {
        if (content == null) return ToolResultExtraction("", emptyList())
        val textList = mutableListOf<String>()
        val imageParts = mutableListOf<JSONObject>()

        when (content) {
            is JsonPrimitive -> {
                content.contentOrNull?.let { textList.add(it) }
            }
            is JsonArray -> {
                content.forEach { element ->
                    when (element) {
                        is JsonPrimitive -> element.contentOrNull?.let { textList.add(it) }
                        is JsonObject -> {
                            val type = element["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
                            when (type) {
                                "text" -> {
                                    element["text"]?.jsonPrimitive?.contentOrNull?.let { textList.add(it) }
                                }
                                "image_url" -> {
                                    val imageUrlObj = element["image_url"] as? JsonObject
                                    val url = imageUrlObj?.get("url")?.jsonPrimitive?.contentOrNull
                                        ?: element["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if (url.isNotEmpty()) {
                                        buildImagePartObject(url)?.let { imageParts.add(it) }
                                    }
                                }
                                "image" -> {
                                    val data = element["data"]?.jsonPrimitive?.contentOrNull
                                    val mimeType = element["mimeType"]?.jsonPrimitive?.contentOrNull
                                        ?: element["mime_type"]?.jsonPrimitive?.contentOrNull
                                        ?: "image/png"
                                    if (!data.isNullOrEmpty()) {
                                        imageParts.add(JSONObject().apply {
                                            put("inlineData", JSONObject().apply {
                                                put("mimeType", mimeType)
                                                put("data", data)
                                            })
                                        })
                                    } else {
                                        val url = element["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                        if (url.isNotEmpty()) {
                                            buildImagePartObject(url)?.let { imageParts.add(it) }
                                        }
                                    }
                                }
                                "inline_data", "inlinedata" -> {
                                    imageParts.add(JSONObject(element.toString()))
                                }
                                "file_data", "filedata" -> {
                                    imageParts.add(JSONObject(element.toString()))
                                }
                                else -> {
                                    if (element.containsKey("image_url")) {
                                        val url = (element["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                                        if (url.isNotEmpty()) {
                                            buildImagePartObject(url)?.let { imageParts.add(it) }
                                        }
                                    } else if (element.containsKey("inlineData")) {
                                        imageParts.add(JSONObject(element.toString()))
                                    } else if (element.containsKey("text")) {
                                        element["text"]?.jsonPrimitive?.contentOrNull?.let { textList.add(it) }
                                    }
                                }
                            }
                        }
                        is JsonArray -> Unit
                    }
                }
            }
            is JsonObject -> {
                val text = content["text"]?.jsonPrimitive?.contentOrNull
                if (text != null) {
                    textList.add(text)
                } else if (content.containsKey("image_url")) {
                    val url = (content["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (url.isNotEmpty()) {
                        buildImagePartObject(url)?.let { imageParts.add(it) }
                    }
                }
            }
        }

        return ToolResultExtraction(
            text = textList.joinToString("\n"),
            imageParts = imageParts
        )
    }

    private fun buildImagePartObject(url: String): JSONObject? {
        if (url.startsWith("data:")) {
            val commaIdx = url.indexOf(',')
            if (commaIdx != -1) {
                val header = url.substring(5, commaIdx)
                val mimeType = header.substringBefore(';')
                val base64Data = url.substring(commaIdx + 1)
                return JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", mimeType)
                        put("data", base64Data)
                    })
                }
            }
        }
        return JSONObject().apply {
            put("fileData", JSONObject().apply {
                put("fileUri", url)
                put("mimeType", "image/jpeg")
            })
        }
    }

    private data class FunctionResponseBuildResult(
        val functionResponsePart: JSONObject,
        val fallbackUserImageTurn: JSONObject?
    )

    private fun buildFunctionResponse(
        msg: ChatCompletionMessage,
        allMessages: List<ChatCompletionMessage>,
        targetModel: String
    ): FunctionResponseBuildResult {
        val toolName = msg.name?.takeIf { it.isNotEmpty() }
            ?: findToolNameForId(allMessages, msg.toolCallId)
            ?: "unknown_function"

        val isError = msg.protocolState?.gemini?.toolResultIsError == true ||
            msg.protocolState?.anthropic?.toolResultIsError == true

        val extraction = extractToolResultContent(msg.content)
        val textResult = extraction.text.ifEmpty { msg.contentText() }
        val imageParts = extraction.imageParts
        val hasText = textResult.trim().isNotEmpty()
        val hasImages = imageParts.isNotEmpty()

        // 遵循 Pi SDK 语义：保留纯文本字符串作为 output/error，不将 JSON 字符串破坏性反序列化为 JSONObject；无文本有图使用 "(see attached image)"
        val responseValue = when {
            hasText -> sanitizeSurrogates(textResult)
            hasImages -> "(see attached image)"
            else -> ""
        }

        val responsePayload = JSONObject().apply {
            if (isError) {
                put("error", responseValue)
            } else {
                put("output", responseValue)
            }
        }

        val modelSupportsMultimodal = supportsMultimodalFunctionResponse(targetModel)
        val fnResp = JSONObject().apply {
            put("name", toolName)
            put("response", responsePayload)
            if (hasImages && modelSupportsMultimodal) {
                put("parts", JSONArray(imageParts))
            }
            if (requiresToolCallId(targetModel) && !msg.toolCallId.isNullOrEmpty()) {
                put("id", msg.toolCallId)
            }
        }

        val toolPart = JSONObject().apply {
            put("functionResponse", fnResp)
        }

        // Gemini < 3 需将工具图片放在后续单独的 user 轮次中
        val fallbackImageTurn = if (hasImages && !modelSupportsMultimodal) {
            val userParts = JSONArray().apply {
                put(JSONObject().apply { put("text", "Tool result image:") })
                imageParts.forEach { put(it) }
            }
            JSONObject().apply {
                put("role", "user")
                put("parts", userParts)
            }
        } else {
            null
        }

        return FunctionResponseBuildResult(
            functionResponsePart = toolPart,
            fallbackUserImageTurn = fallbackImageTurn
        )
    }

    internal fun buildFunctionResponsePart(
        msg: ChatCompletionMessage,
        allMessages: List<ChatCompletionMessage>,
        targetModel: String
    ): JSONObject = buildFunctionResponse(msg, allMessages, targetModel).functionResponsePart

    private fun findToolNameForId(messages: List<ChatCompletionMessage>, toolCallId: String?): String? {
        if (toolCallId.isNullOrBlank()) return null
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            val matched = msg.toolCalls?.firstOrNull { it.id == toolCallId }
            if (matched != null) {
                return matched.function.name
            }
        }
        return null
    }

    // ---- 非流式响应解析 ----

    fun parseGeminiResponse(
        body: String?,
        parser: ModelSceneRegistry.ResponseParser,
        routeTag: String?
    ): SceneChatCompletionResponse {
        return try {
            val json = JSONObject(body ?: "{}")
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message", "Gemini 请求异常") ?: "Gemini 请求异常"
                val errCode = errObj?.optString("code", "400") ?: "400"
                return SceneChatCompletionResponse(
                    success = false,
                    code = errCode,
                    message = errMsg,
                    parser = parser,
                    route = routeTag,
                    rawResponseBody = body
                )
            }

            val promptFeedback = json.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason", "")?.takeIf { it.isNotEmpty() }
            if (blockReason != null) {
                return SceneChatCompletionResponse(
                    success = false,
                    code = "400",
                    message = "Prompt 被安全策略阻断: $blockReason",
                    parser = parser,
                    route = routeTag,
                    rawResponseBody = body
                )
            }

            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return SceneChatCompletionResponse(
                    success = false,
                    code = "500",
                    message = "Gemini 响应未包含有效候选",
                    parser = parser,
                    route = routeTag,
                    rawResponseBody = body
                )
            }

            val candidate = candidates.getJSONObject(0)
            val rawFinishReason = candidate.optString("finishReason", "").uppercase()
            if (rawFinishReason in BLOCKED_FINISH_REASONS) {
                return SceneChatCompletionResponse(
                    success = false,
                    code = "400",
                    message = "Gemini 候选因安全策略或阻断原因终止: $rawFinishReason",
                    parser = parser,
                    route = routeTag,
                    rawResponseBody = body
                )
            }

            val contentObj = candidate.optJSONObject("content")
            val partsArray = contentObj?.optJSONArray("parts") ?: JSONArray()
            val textBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCalls = mutableListOf<AssistantToolCall>()
            val seenToolCallIds = mutableSetOf<String>()
            var toolCallIndex = 0

            for (i in 0 until partsArray.length()) {
                val part = partsArray.optJSONObject(i) ?: continue
                if (part.optBoolean("thought") == true) {
                    if (part.has("text")) {
                        reasoningBuilder.append(part.optString("text", ""))
                    }
                    continue // reasoning 不作为正文输出
                }
                if (part.has("text")) {
                    textBuilder.append(part.optString("text", ""))
                }
                if (part.has("functionCall")) {
                    val fnCall = part.getJSONObject("functionCall")
                    val name = fnCall.optString("name", "")
                    val args = fnCall.optJSONObject("args")?.toString() ?: "{}"
                    val rawId = fnCall.optString("id").takeIf { it.isNotEmpty() }
                    val id = if (rawId == null || !seenToolCallIds.add(rawId)) {
                        val gen = "${name}_${System.currentTimeMillis()}_${toolCallIndex++}"
                        seenToolCallIds.add(gen)
                        gen
                    } else {
                        toolCallIndex++
                        rawId
                    }
                    toolCalls.add(AssistantToolCall(
                        id = id,
                        type = "function",
                        function = AssistantToolCallFunction(name = name, arguments = args)
                    ))
                }
            }
            val mappedFinishReason = when (rawFinishReason) {
                "STOP" -> if (toolCalls.isNotEmpty()) "tool_calls" else "stop"
                "MAX_TOKENS" -> "length"
                else -> {
                    return SceneChatCompletionResponse(
                        success = false,
                        code = "400",
                        message = "Gemini 候选因异常原因终止: ${rawFinishReason.ifEmpty { "缺少完成原因" }}",
                        parser = parser,
                        route = routeTag,
                        rawResponseBody = body
                    )
                }
            }

            SceneChatCompletionResponse(
                success = true,
                code = "200",
                message = "success",
                parser = parser,
                route = routeTag,
                content = textBuilder.toString(),
                reasoning = reasoningBuilder.toString(),
                finishReason = mappedFinishReason,
                toolCalls = toolCalls,
                rawResponseBody = body
            )
        } catch (e: Exception) {
            SceneChatCompletionResponse(
                success = false,
                code = "500",
                message = "Gemini 响应解析失败: ${e.message}",
                parser = parser,
                route = routeTag,
                rawResponseBody = body
            )
        }
    }

    // ---- SSE 流式响应转换 ----

    private class GeminiStreamPartAccumulator(
        val type: String, // "text", "thinking", "functionCall"
        var text: StringBuilder = StringBuilder(),
        var thoughtSignature: String? = null,
        var fnName: String? = null,
        var fnArgs: String? = null,
        var fnId: String? = null
    ) {
        fun buildPartJson(): JsonObject {
            return buildJsonObject {
                when (type) {
                    "thinking" -> {
                        put("thought", JsonPrimitive(true))
                        put("text", JsonPrimitive(text.toString()))
                        thoughtSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                    }
                    "text" -> {
                        put("text", JsonPrimitive(text.toString()))
                        thoughtSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                    }
                    "functionCall" -> {
                        put("functionCall", buildJsonObject {
                            put("name", JsonPrimitive(fnName.orEmpty()))
                            val argsElement = runCatching {
                                json.parseToJsonElement(fnArgs ?: "{}")
                            }.getOrElse { JsonObject(emptyMap()) }
                            put("args", argsElement)
                            fnId?.let { put("id", JsonPrimitive(it)) }
                        })
                        thoughtSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                    }
                }
            }
        }
    }

    fun wrapGeminiListener(outer: EventSourceListener): EventSourceListener {
        return object : EventSourceListener() {
            private var sawFinishReason = false
            private var sawError = false
            private var sawFunctionCall = false
            private var toolCallCounter = 0
            private val seenToolCallIds = mutableSetOf<String>()
            private var partCounter = 0
            private var currentPartBuilder: GeminiStreamPartAccumulator? = null

            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                outer.onOpen(eventSource, response)
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (sawError) return

                if (data.trim() == "[DONE]") {
                    handleDone(eventSource, id, type)
                    return
                }

                val root = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    sawError = true
                    outer.onFailure(eventSource, IllegalStateException("Gemini 流解析异常: ${e.message}"), null)
                    return
                }

                if (root.has("error")) {
                    sawError = true
                    val err = root.optJSONObject("error")
                    val msg = err?.optString("message", "Gemini 流式异常") ?: "Gemini 流式异常"
                    outer.onFailure(eventSource, IllegalStateException("Gemini 流式异常: $msg"), null)
                    return
                }

                val promptFeedback = root.optJSONObject("promptFeedback")
                val blockReason = promptFeedback?.optString("blockReason", "")?.takeIf { it.isNotEmpty() }
                if (blockReason != null) {
                    sawError = true
                    outer.onFailure(eventSource, IllegalStateException("Prompt 被安全策略阻断: $blockReason"), null)
                    return
                }

                // 处理 usageMetadata (遵循 Pi SDK: completion = candidates + thoughts)
                val usageObj = root.optJSONObject("usageMetadata")
                if (usageObj != null) {
                    val promptTokens = usageObj.optInt("promptTokenCount", 0)
                    val candidatesTokens = usageObj.optInt("candidatesTokenCount", 0)
                    val thoughtsTokens = usageObj.optInt("thoughtsTokenCount", 0)
                    val totalTokens = usageObj.optInt("totalTokenCount", 0)
                    val cachedTokens = usageObj.optInt("cachedContentTokenCount", 0)
                    val completionTokens = candidatesTokens + thoughtsTokens

                    val usageChunk = buildJsonObject {
                        put("usage", buildJsonObject {
                            put("prompt_tokens", JsonPrimitive(promptTokens))
                            put("completion_tokens", JsonPrimitive(completionTokens))
                            put("total_tokens", JsonPrimitive(totalTokens))
                            if (cachedTokens > 0) {
                                put("prompt_tokens_details", buildJsonObject {
                                    put("cached_tokens", JsonPrimitive(cachedTokens))
                                })
                            }
                            if (thoughtsTokens > 0) {
                                put("completion_tokens_details", buildJsonObject {
                                    put("reasoning_tokens", JsonPrimitive(thoughtsTokens))
                                })
                            }
                        })
                    }.toString()
                    outer.onEvent(eventSource, id, type, usageChunk)
                }

                val candidates = root.optJSONArray("candidates") ?: return
                if (candidates.length() == 0) return
                val candidate = candidates.getJSONObject(0)

                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val thoughtSig = part.optString("thoughtSignature", "").takeIf { it.isNotEmpty() }
                        val isThinking = part.optBoolean("thought") == true
                        val hasText = part.has("text")
                        val hasFnCall = part.has("functionCall")

                        if (!hasText && !hasFnCall && thoughtSig != null) {
                            // 独立带签名空 part：必须先 flush 之前的 text/thinking 累加器（保留前序 part 自身的 signature），
                            // 绝不可将新 signature 覆盖或合并至前序 part！
                            flushPartBuilder(eventSource, id, type)
                            val emptyAccumulator = GeminiStreamPartAccumulator(
                                type = if (isThinking) "thinking" else "text",
                                thoughtSignature = thoughtSig
                            )
                            emitGeminiPart(eventSource, id, type, emptyAccumulator)
                        }

                        if (hasText) {
                            val textDelta = part.optString("text", "")
                            if (isThinking) {
                                if (currentPartBuilder != null && currentPartBuilder!!.type != "thinking") {
                                    flushPartBuilder(eventSource, id, type)
                                }
                                if (currentPartBuilder == null) {
                                    currentPartBuilder = GeminiStreamPartAccumulator(type = "thinking")
                                }
                                currentPartBuilder!!.text.append(textDelta)
                                currentPartBuilder!!.thoughtSignature = retainThoughtSignature(
                                    currentPartBuilder!!.thoughtSignature, thoughtSig
                                )

                                outer.onEvent(eventSource, id, type, buildOpenAIChunk(
                                    deltaJson = buildJsonObject {
                                        put("reasoning_content", JsonPrimitive(textDelta))
                                    },
                                    finishReason = null
                                ))
                            } else {
                                if (currentPartBuilder != null && currentPartBuilder!!.type != "text") {
                                    flushPartBuilder(eventSource, id, type)
                                }
                                if (currentPartBuilder == null) {
                                    currentPartBuilder = GeminiStreamPartAccumulator(type = "text")
                                }
                                currentPartBuilder!!.text.append(textDelta)
                                currentPartBuilder!!.thoughtSignature = retainThoughtSignature(
                                    currentPartBuilder!!.thoughtSignature, thoughtSig
                                )

                                outer.onEvent(eventSource, id, type, buildOpenAIChunk(
                                    deltaJson = buildJsonObject {
                                        put("content", JsonPrimitive(textDelta))
                                    },
                                    finishReason = null
                                ))
                            }
                        }

                        if (hasFnCall) {
                            flushPartBuilder(eventSource, id, type)
                            sawFunctionCall = true
                            val fnCall = part.getJSONObject("functionCall")
                            val name = fnCall.optString("name", "")
                            val args = fnCall.optJSONObject("args")?.toString() ?: "{}"
                            val toolIndex = toolCallCounter++
                            val rawId = fnCall.optString("id").takeIf { it.isNotEmpty() }
                            val callId = if (rawId == null || !seenToolCallIds.add(rawId)) {
                                val gen = "${name}_${System.currentTimeMillis()}_${toolIndex + 1}"
                                seenToolCallIds.add(gen)
                                gen
                            } else {
                                rawId
                            }

                            val fnAccumulator = GeminiStreamPartAccumulator(
                                type = "functionCall",
                                thoughtSignature = thoughtSig,
                                fnName = name,
                                fnArgs = args,
                                fnId = callId
                            )
                            emitGeminiPart(eventSource, id, type, fnAccumulator)

                            outer.onEvent(eventSource, id, type, buildOpenAIChunk(
                                deltaJson = buildJsonObject {
                                    put("tool_calls", buildJsonArray {
                                        add(buildJsonObject {
                                            put("index", JsonPrimitive(toolIndex))
                                            put("id", JsonPrimitive(callId))
                                            put("type", JsonPrimitive("function"))
                                            put("function", buildJsonObject {
                                                put("name", JsonPrimitive(name))
                                                put("arguments", JsonPrimitive(args))
                                            })
                                        })
                                    })
                                },
                                finishReason = null
                            ))
                        }
                    }
                }
                val rawFinish = candidate.optString("finishReason", "").uppercase()
                if (rawFinish.isNotEmpty()) {
                    if (rawFinish == "STOP" || rawFinish == "MAX_TOKENS") {
                        sawFinishReason = true
                        flushPartBuilder(eventSource, id, type)

                        val mappedFinish = when (rawFinish) {
                            "MAX_TOKENS" -> "length"
                            "STOP" -> if (sawFunctionCall) "tool_calls" else "stop"
                            else -> "stop"
                        }

                        outer.onEvent(eventSource, id, type, buildOpenAIChunk(
                            deltaJson = buildJsonObject {},
                            finishReason = mappedFinish
                        ))
                    } else {
                        sawError = true
                        outer.onFailure(
                            eventSource,
                            IllegalStateException("Gemini 流因异常或阻断原因终止: $rawFinish"),
                            null
                        )
                        return
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (sawError) {
                    outer.onClosed(eventSource)
                    return
                }
                flushPartBuilder(eventSource, null, null)
                if (!sawFinishReason) {
                    sawError = true
                    outer.onFailure(
                        eventSource,
                        IllegalStateException("Gemini 流结束但未包含完成原因"),
                        null
                    )
                    return
                }
                outer.onEvent(eventSource, null, null, "[DONE]")
                outer.onClosed(eventSource)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                sawError = true
                outer.onFailure(eventSource, t, response)
            }

            private fun flushPartBuilder(eventSource: EventSource, id: String?, type: String?) {
                val builder = currentPartBuilder ?: return
                currentPartBuilder = null
                emitGeminiPart(eventSource, id, type, builder)
            }

            private fun emitGeminiPart(
                eventSource: EventSource,
                id: String?,
                type: String?,
                builder: GeminiStreamPartAccumulator
            ) {
                val index = partCounter++
                val partJson = builder.buildPartJson()
                outer.onEvent(
                    eventSource,
                    id,
                    type,
                    buildJsonObject {
                        put(
                            ChatCompletionProtocolMetadata.GEMINI_STREAM_PART_FIELD,
                            buildJsonObject {
                                put("index", JsonPrimitive(index))
                                put("part", partJson)
                            }
                        )
                    }.toString()
                )
            }

            private fun handleDone(eventSource: EventSource, id: String?, type: String?) {
                flushPartBuilder(eventSource, id, type)
                if (!sawFinishReason) {
                    sawError = true
                    outer.onFailure(
                        eventSource,
                        IllegalStateException("Gemini 流结束但未包含完成原因"),
                        null
                    )
                    return
                }
                outer.onEvent(eventSource, id, type, "[DONE]")
            }

            private fun buildOpenAIChunk(
                deltaJson: JsonObject,
                finishReason: String?,
                usageJson: JsonObject? = null
            ): String {
                return buildJsonObject {
                    put(
                        "choices",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("delta", deltaJson)
                                    finishReason?.let {
                                        put("finish_reason", JsonPrimitive(it))
                                    }
                                }
                            )
                        }
                    )
                    usageJson?.let { put("usage", it) }
                }.toString()
            }
        }
    }

    // ---- 模型列表分页获取与过滤 ----

    data class GeminiModelsPage(
        val models: List<ProviderModelOption>,
        val nextPageToken: String?
    )

    fun parseGeminiModelsPage(responseBody: String?, apiBase: String? = null): GeminiModelsPage {
        val root = runCatching { JSONObject(responseBody ?: "{}") }.getOrNull()
            ?: return GeminiModelsPage(emptyList(), null)
        val nextPageToken = root.optString("nextPageToken", "").takeIf { it.isNotEmpty() }
        val modelsArray = root.optJSONArray("models") ?: return GeminiModelsPage(emptyList(), nextPageToken)

        val models = mutableListOf<ProviderModelOption>()
        for (i in 0 until modelsArray.length()) {
            val item = modelsArray.optJSONObject(i) ?: continue
            val rawName = item.optString("name", "").trim()
            if (rawName.isEmpty()) continue

            // 检查 supportedGenerationMethods：仅保留包含 generateContent 的模型（过滤 embedding）
            val methodsArray = item.optJSONArray("supportedGenerationMethods")
            val methods = if (methodsArray != null) {
                (0 until methodsArray.length()).map { methodsArray.optString(it) }
            } else {
                emptyList()
            }
            if (methods.isNotEmpty() && methods.none { it.equals("generateContent", ignoreCase = true) }) {
                continue
            }

            val cleanId = rawName.removePrefix("models/")
            if (methods.isEmpty() && (cleanId.contains("embedding", ignoreCase = true) || cleanId.contains("embed-", ignoreCase = true))) {
                continue
            }

            val displayName = item.optString("displayName", "").trim()
                .ifEmpty { item.optString("display_name", "").trim() }
                .ifEmpty { cleanId }
            val inputLimit = item.optInt("inputTokenLimit", 0).takeIf { it > 0 }
                ?: item.optInt("input_limit", 0).takeIf { it > 0 }
            val outputLimit = item.optInt("outputTokenLimit", 0).takeIf { it > 0 }
                ?: item.optInt("output_limit", 0).takeIf { it > 0 }

            val metadataKx = runCatching {
                json.parseToJsonElement(item.toString()) as? JsonObject
            }.getOrNull() ?: JsonObject(emptyMap())

            val option = ProviderModelOption(
                id = cleanId,
                displayName = displayName,
                ownedBy = "Google",
                contextLimit = inputLimit,
                inputLimit = inputLimit,
                outputLimit = outputLimit,
                attachment = true,
                toolCall = true
            )
            models.add(
                ProviderModelCapabilities.applyDeclared(
                    model = option,
                    metadata = metadataKx,
                    apiBase = apiBase
                )
            )
        }
        return GeminiModelsPage(models, nextPageToken)
    }

    suspend fun fetchGeminiModels(
        client: OkHttpClient,
        apiBase: String,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap()
    ): List<ProviderModelOption> = withContext(Dispatchers.IO) {
        val baseUrl = buildGeminiModelsUrl(apiBase)
        val resultModels = mutableListOf<ProviderModelOption>()
        var pageToken: String? = null

        do {
            val url = if (pageToken.isNullOrBlank()) {
                if (baseUrl.contains("?")) "$baseUrl&pageSize=100" else "$baseUrl?pageSize=100"
            } else {
                val encodedToken = URLEncoder.encode(pageToken, StandardCharsets.UTF_8.name())
                if (baseUrl.contains("?")) "$baseUrl&pageToken=$encodedToken&pageSize=100"
                else "$baseUrl?pageToken=$encodedToken&pageSize=100"
            }
            val request = buildGeminiRequestBuilder(
                url = url,
                apiKey = apiKey,
                customHeaders = customHeaders
            ).get().build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "获取 Gemini 模型列表失败 (${response.code})：${extractAvailabilityMessage(responseBody)}"
                )
            }

            val page = parseGeminiModelsPage(responseBody, apiBase)
            resultModels.addAll(page.models)
            pageToken = page.nextPageToken?.takeIf { it.isNotBlank() }
        } while (pageToken != null)

        resultModels.sortedBy { it.id.lowercase() }
    }

    // ---- 模型可用性检查 ----

    suspend fun checkGeminiModelAvailability(
        model: String,
        apiBase: String,
        apiKey: String?,
        customHeaders: Map<String, String> = emptyMap()
    ): ModelAvailabilityCheckResult = withContext(Dispatchers.IO) {
        val normalizedModel = model.trim()
        if (normalizedModel.isEmpty()) {
            return@withContext ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = "模型名不能为空"
            )
        }

        val normalizedApiBase = ModelProviderConfigStore.normalizeBaseUrl(apiBase)
            ?: return@withContext ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = "URL 非法，请输入 http(s) 地址"
            )

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "ping")
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 1)
            })
        }.toString()

        val url = buildGeminiInferenceUrl(normalizedApiBase, normalizedModel, stream = false)
        val request = buildGeminiRequestBuilder(
            url = url,
            requestBody = requestJson.toRequestBody("application/json".toMediaType()),
            apiKey = apiKey,
            customHeaders = customHeaders
        ).build()

        return@withContext try {
            val response = OkHttpClient().newCall(request).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful) {
                return@withContext ModelAvailabilityCheckResult(
                    available = false,
                    code = response.code,
                    message = extractAvailabilityMessage(responseBody)
                )
            }

            val hasExpectedShape = try {
                val json = JSONObject(responseBody ?: "{}")
                json.has("candidates") || json.has("promptFeedback")
            } catch (_: Exception) {
                false
            }

            ModelAvailabilityCheckResult(
                available = hasExpectedShape,
                code = response.code,
                message = if (hasExpectedShape) "OK" else "响应不符合 Gemini 结构"
            )
        } catch (e: Exception) {
            ModelAvailabilityCheckResult(
                available = false,
                code = null,
                message = e.message ?: "请求异常"
            )
        }
    }

    private fun extractAvailabilityMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) return "请求失败"
        return try {
            val json = JSONObject(responseBody)
            val errorObj = json.optJSONObject("error")
            val errorMsg = errorObj?.optString("message", "")?.takeIf { it.isNotBlank() }
            val topMsg = json.optString("message", "").takeIf { it.isNotBlank() }
            errorMsg ?: topMsg ?: responseBody
        } catch (_: Exception) {
            responseBody
        }
    }
}
