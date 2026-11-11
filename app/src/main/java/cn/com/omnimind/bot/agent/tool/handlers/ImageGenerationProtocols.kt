package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.assists.controller.http.GeminiProtocolAdapter
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class ImageGenerationProtocol {
    OPENAI_COMPATIBLE,
    GEMINI
}

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String? = null,
)

internal data class ImageGenerationRoute(
    val protocol: ImageGenerationProtocol,
    val endpoint: String,
    val apiKey: String,
    val customHeaders: Map<String, String>,
    val model: String,
    val providerProfileId: String,
    val providerProfileName: String,
)

internal object OpenAiImageProtocolHandler {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun buildRequestJson(
        model: String,
        prompt: String,
        size: String?,
        quality: String?,
        outputFormat: String?,
        background: String?,
    ): JSONObject {
        val isDallE2 = model.contains("dall-e-2", ignoreCase = true)
        val isDallE3 = model.contains("dall-e-3", ignoreCase = true)
        val isDallE = isDallE2 || isDallE3

        return JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)

            // 仅 DALL-E 添加 response_format
            if (isDallE) {
                put("response_format", "b64_json")
            }

            val normalizedSize = size?.trim()?.takeIf(String::isNotEmpty)
            if (isDallE) {
                val dSize = when {
                    normalizedSize == null || normalizedSize.equals("auto", ignoreCase = true) -> "1024x1024"
                    isDallE3 -> when (normalizedSize) {
                        "1024x1536" -> "1024x1792"
                        "1536x1024" -> "1792x1024"
                        else -> normalizedSize
                    }
                    else -> normalizedSize
                }
                put("size", dSize)
            } else if (normalizedSize != null && !normalizedSize.equals("auto", ignoreCase = true)) {
                put("size", normalizedSize)
            }

            val normalizedQuality = quality?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedQuality != null && !normalizedQuality.equals("auto", ignoreCase = true)) {
                if (isDallE3) {
                    val d3Quality = when (normalizedQuality.lowercase()) {
                        "hd", "high" -> "hd"
                        "standard", "low", "medium" -> "standard"
                        else -> normalizedQuality
                    }
                    put("quality", d3Quality)
                } else if (!isDallE2) {
                    put("quality", normalizedQuality)
                }
            }

            if (!isDallE) {
                val normalizedBackground = background?.trim()?.takeIf(String::isNotEmpty)
                if (normalizedBackground != null && !normalizedBackground.equals("auto", ignoreCase = true)) {
                    put("background", normalizedBackground)
                }
                val normalizedFormat = outputFormat?.trim()?.takeIf(String::isNotEmpty)
                if (normalizedFormat != null && !normalizedFormat.equals("auto", ignoreCase = true)) {
                    put("output_format", normalizedFormat)
                }
            }
        }
    }

    fun buildHttpRequest(
        endpoint: String,
        apiKey: String,
        customHeaders: Map<String, String>,
        requestJson: JSONObject,
        allowInsecureTransport: Boolean = false,
    ): Request {
        val safeEndpoint = ContentEndpointSecurity.requireSafe(
            rawUrl = endpoint,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = allowInsecureTransport,
        )
        val builtIn = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ).apply {
            if (apiKey.isNotBlank()) {
                put("Authorization", "Bearer ${apiKey.trim()}")
            }
        }
        val mergedHeaders = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = builtIn,
            custom = customHeaders,
        )
        return Request.Builder()
            .url(safeEndpoint)
            .apply {
                mergedHeaders.forEach { (key, value) -> header(key, value) }
            }
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    suspend fun extractImage(
        payload: JSONObject,
        downloadImageFn: suspend (String) -> ByteArray,
        decodeBase64Fn: (String) -> ByteArray?,
    ): GeneratedImage? {
        val data = payload.optJSONArray("data")
        if (data != null && data.length() > 0) {
            val first = data.optJSONObject(0) ?: JSONObject()
            first.optString("b64_json").takeIf(String::isNotBlank)
                ?.let(decodeBase64Fn)?.let { return GeneratedImage(it) }
            first.optString("url").takeIf(String::isNotBlank)
                ?.let { return GeneratedImage(downloadImageFn(it)) }
        }
        val responseFormat = payload.optString("format").takeIf(String::isNotBlank)
        val output = payload.optJSONArray("output") ?: return null
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            extractImageFromOutputItem(item, responseFormat, decodeBase64Fn)?.let { return GeneratedImage(it) }
            item.optString("url").takeIf(String::isNotBlank)
                ?.let { return GeneratedImage(downloadImageFn(it)) }
        }
        return null
    }

    private fun extractImageFromOutputItem(
        item: JSONObject,
        responseFormat: String?,
        decodeBase64Fn: (String) -> ByteArray?,
    ): ByteArray? {
        item.optString("b64_json").takeIf(String::isNotBlank)?.let(decodeBase64Fn)?.let { return it }
        item.optString("result").takeIf(String::isNotBlank)?.let(decodeBase64Fn)?.let { return it }
        item.optString("image_base64").takeIf(String::isNotBlank)?.let(decodeBase64Fn)?.let { return it }
        val content = item.optJSONArray("content") ?: return null
        for (index in 0 until content.length()) {
            val contentItem = content.optJSONObject(index) ?: continue
            contentItem.optString("b64_json").takeIf(String::isNotBlank)?.let(decodeBase64Fn)?.let { return it }
            contentItem.optString("image_base64").takeIf(String::isNotBlank)?.let(decodeBase64Fn)?.let { return it }
            val dataUrl = contentItem.optString("image_url")
                .takeIf { it.startsWith("data:", ignoreCase = true) }
            dataUrl?.let(decodeBase64Fn)?.let { return it }
            if (responseFormat == "b64_json") {
                contentItem.optString("result").takeIf(String::isNotBlank)?.let(decodeBase64Fn)?.let { return it }
            }
        }
        return null
    }
}

internal object GeminiImageProtocolHandler {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val BLOCKED_FINISH_REASONS = setOf(
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
        "OTHER",
    )

    fun resolveEndpoint(baseUrl: String, model: String): String {
        return GeminiProtocolAdapter.buildGeminiInferenceUrl(
            apiBase = baseUrl,
            model = model,
            stream = false,
        )
    }

    fun buildRequestJson(prompt: String): JSONObject {
        return JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("IMAGE")
                    put("TEXT")
                })
            })
        }
    }

    fun buildHttpRequest(
        endpoint: String,
        apiKey: String,
        customHeaders: Map<String, String>,
        requestJson: JSONObject,
        allowInsecureTransport: Boolean = false,
    ): Request {
        val safeEndpoint = ContentEndpointSecurity.requireSafe(
            rawUrl = endpoint,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = allowInsecureTransport,
        )
        val builtIn = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        ).apply {
            if (apiKey.isNotBlank()) {
                put("x-goog-api-key", apiKey.trim())
            }
        }
        val mergedHeaders = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = builtIn,
            custom = customHeaders,
        )
        return Request.Builder()
            .url(safeEndpoint)
            .apply {
                mergedHeaders.forEach { (key, value) -> header(key, value) }
            }
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    fun extractImage(payload: JSONObject, decodeBase64Fn: (String) -> ByteArray?): GeneratedImage {
        val promptFeedback = payload.optJSONObject("promptFeedback")
        val blockReason = promptFeedback?.optString("blockReason")?.takeIf(String::isNotBlank)
        if (blockReason != null) {
            throw IllegalStateException("Gemini 提示词因安全策略被拒绝: $blockReason")
        }

        val candidates = payload.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw IllegalStateException("Gemini 响应未包含有效生成候选")
        }

        val candidate = candidates.getJSONObject(0)
        val finishReason = candidate.optString("finishReason", "").trim().uppercase()
        if (finishReason in BLOCKED_FINISH_REASONS) {
            if (finishReason == "NO_IMAGE") {
                throw IllegalStateException("Gemini 未能生成图片（NO_IMAGE）")
            }
            throw IllegalStateException("Gemini 图像生成因安全策略或阻断原因被拒绝: $finishReason")
        }

        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: JSONArray()
        var imageBytes: ByteArray? = null
        var imageMimeType: String? = null
        val textPieces = mutableListOf<String>()

        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
            if (inlineData != null && imageBytes == null) {
                val mime = inlineData.optString("mimeType").takeIf(String::isNotBlank)
                    ?: inlineData.optString("mime_type").takeIf(String::isNotBlank)
                // 仅当明确属于 image/ 模态时才提取，避免取到 audio 等非图片 inlineData
                if (mime?.startsWith("image/", ignoreCase = true) == true) {
                    val b64 = inlineData.optString("data").takeIf(String::isNotBlank)
                        ?: inlineData.optString("b64_json").takeIf(String::isNotBlank)
                    if (b64 != null) {
                        val decoded = decodeBase64Fn(b64)
                        if (decoded != null) {
                            imageBytes = decoded
                            imageMimeType = mime
                        }
                    }
                }
            }
            val text = part.optString("text").trim()
            if (text.isNotEmpty() && part.optBoolean("thought") != true) {
                textPieces.add(text)
            }
        }

        if (imageBytes == null) {
            if (textPieces.isNotEmpty()) {
                throw IllegalStateException("Gemini 未能生成图片，模型返回: ${textPieces.joinToString(" ")}")
            } else {
                throw IllegalStateException("Gemini 响应未包含图片数据")
            }
        }

        return GeneratedImage(
            bytes = imageBytes,
            mimeType = imageMimeType,
        )
    }
}
