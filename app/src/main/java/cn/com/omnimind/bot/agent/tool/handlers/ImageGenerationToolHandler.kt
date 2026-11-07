package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ImageGenerationToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
) : ToolHandler {
    override val toolNames: Set<String> = setOf("image_generate")

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult = executeImageGenerate(args, env, callback, toolHandle)

    private suspend fun executeImageGenerate(
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = "image_generate"
        return try {
            val workspace = env.workspaceDescriptor
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["outputPath"]?.jsonPrimitive?.contentOrNull,
            )?.let { return it }

            val prompt = requireImageGenerationPrompt(
                args["prompt"]?.jsonPrimitive?.contentOrNull,
            )
            val outputPath = args["outputPath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(outputPath.isNotEmpty()) { "outputPath cannot be empty" }

            val route = resolveRoute(args, env)
            val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "1024x1024"
            val quality = args["quality"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "auto"
            val requestedFormat = args["format"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.lowercase()
                ?.takeIf { it in SUPPORTED_OUTPUT_FORMATS }
                ?: outputPath.substringAfterLast('.', missingDelimiterValue = "")
                    .lowercase()
                    .takeIf { it in SUPPORTED_OUTPUT_FORMATS }
                ?: "png"
            val background = args["background"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: "auto"

            val file = workspaceManager.resolvePath(
                inputPath = outputPath,
                workspace = workspace,
                allowPublicStorage = true,
            )

            helper.reportToolProgress(
                callback,
                toolName,
                "Generating image",
                mapOf("model" to route.model, "outputPath" to outputPath),
                toolHandle,
            )

            val imageBytes = withContext(Dispatchers.IO) {
                requestGeneratedImage(
                    route = route,
                    prompt = prompt,
                    size = size,
                    quality = quality,
                    outputFormat = requestedFormat,
                    background = background,
                )
            }
            requireSupportedImage(imageBytes)

            file.parentFile?.mkdirs()
            file.writeBytes(imageBytes)

            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "mimeType" to workspaceManager.guessMimeType(file),
                "model" to route.model,
                "providerProfileId" to route.providerProfileId,
                "providerProfileName" to route.providerProfileName,
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("Generated image: ${file.name}"),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            helper.workspacePermissionResult(error, callback)?.let { return it }
            val safeMessage = "Image generation failed (${error.javaClass.simpleName})"
            helper.errorResult(toolName, safeMessage, "Image generation failed")
        }
    }

    private suspend fun resolveRoute(
        args: JsonObject,
        env: AgentExecutionEnvironment,
    ): ImageGenerationRoute {
        val explicitProfileId = args["providerProfileId"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: env.modelProviderProfileId?.trim()?.takeIf(String::isNotEmpty)
            ?: SceneModelBindingStore.getBinding("scene.dispatch.model")?.providerProfileId?.trim()?.takeIf(String::isNotEmpty)
        val profile = if (explicitProfileId != null) {
            ModelProviderConfigStore.getProfile(explicitProfileId)
                ?: throw IllegalStateException(
                    "Image provider profile '$explicitProfileId' not found. Check image provider configuration."
                )
        } else {
            ModelProviderConfigStore.getEditingProfile()
        }
        require(!cn.com.omnimind.baselib.llm.ChatGptOAuthProvider.isOAuth(profile.sourceType)) {
            "ChatGPT OAuth does not support image generation. Select an image API provider."
        }
        require(!profile.readOnly) {
            "The current provider is read-only and cannot generate images. Select a BYOK provider profile."
        }
        require(profile.isConfigured()) {
            "Confirm the BYOK provider destination before generating images."
        }
        val apiKey = profile.apiKey.trim()
        require(apiKey.isNotEmpty()) {
            "Image provider apiKey is empty. Configure a BYOK OpenAI-compatible provider profile."
        }
        val requestedModel = normalizeImageModelId(args["model"]?.jsonPrimitive?.contentOrNull)
        val model = requestedModel ?: DEFAULT_IMAGE_MODEL
        return ImageGenerationRoute(
            endpoint = resolveImageGenerationEndpoint(
                baseUrl = profile.baseUrl,
                apiKey = apiKey,
            ),
            apiKey = apiKey,
            customHeaders = profile.customHeaders,
            model = model,
            providerProfileId = profile.id,
            providerProfileName = profile.name,
        )
    }

    private suspend fun requestGeneratedImage(
        route: ImageGenerationRoute,
        prompt: String,
        size: String,
        quality: String,
        outputFormat: String,
        background: String,
    ): ByteArray {
        val requestJson = JSONObject().apply {
            put("model", route.model)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("quality", quality)
            put("output_format", outputFormat)
            put("background", background)
        }

        val request = buildRequest(
            endpoint = route.endpoint,
            apiKey = route.apiKey,
            customHeaders = route.customHeaders,
            requestJson = requestJson,
            allowInsecureTransport = true,
        )
        val response = OkHttpManager.sensitiveContentCall(
            client = httpClient,
            request = request,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = true,
        ).awaitResponse()

        response.use {
            val bytes = it.body?.bytes() ?: ByteArray(0)
            if (!it.isSuccessful) {
                throw IllegalStateException("image generation request failed (${it.code})")
            }
            val payload = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw IllegalStateException("image generation returned invalid JSON") }
            return extractGeneratedImage(payload)
                ?: throw IllegalStateException(
                    "image generation response did not contain b64_json or image url"
                )
        }
    }

    private fun buildRequest(
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
        val mergedHeaders = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "Authorization" to "Bearer $apiKey",
            ),
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

    private suspend fun extractGeneratedImage(payload: JSONObject): ByteArray? {
        val data = payload.optJSONArray("data")
        if (data != null && data.length() > 0) {
            val first = data.optJSONObject(0) ?: JSONObject()
            first.optString("b64_json").takeIf(String::isNotBlank)
                ?.let(::decodeBase64Image)?.let { return it }
            first.optString("url").takeIf(String::isNotBlank)
                ?.let { return downloadImage(it) }
        }
        val responseFormat = payload.optString("format").takeIf(String::isNotBlank)
        val output = payload.optJSONArray("output") ?: return null
        for (index in 0 until output.length()) {
            val item = output.optJSONObject(index) ?: continue
            extractImageFromOutputItem(item, responseFormat)?.let { return it }
            item.optString("url").takeIf(String::isNotBlank)
                ?.let { return downloadImage(it) }
        }
        return null
    }

    private fun extractImageFromOutputItem(item: JSONObject, responseFormat: String?): ByteArray? {
        item.optString("b64_json").takeIf(String::isNotBlank)?.let(::decodeBase64Image)
            ?.let { return it }
        item.optString("result").takeIf(String::isNotBlank)?.let(::decodeBase64Image)
            ?.let { return it }
        item.optString("image_base64").takeIf(String::isNotBlank)?.let(::decodeBase64Image)
            ?.let { return it }
        val content = item.optJSONArray("content") ?: return null
        for (index in 0 until content.length()) {
            val contentItem = content.optJSONObject(index) ?: continue
            contentItem.optString("b64_json").takeIf(String::isNotBlank)
                ?.let(::decodeBase64Image)?.let { return it }
            contentItem.optString("image_base64").takeIf(String::isNotBlank)
                ?.let(::decodeBase64Image)?.let { return it }
            val dataUrl = contentItem.optString("image_url")
                .takeIf { it.startsWith("data:", ignoreCase = true) }
            dataUrl?.let(::decodeBase64Image)?.let { return it }
            if (responseFormat == "b64_json") {
                contentItem.optString("result").takeIf(String::isNotBlank)
                    ?.let(::decodeBase64Image)?.let { return it }
            }
        }
        return null
    }

    private suspend fun downloadImage(url: String): ByteArray {
        val safeUrl = ContentEndpointSecurity.requireSafe(
            rawUrl = url,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = true,
        )
        val request = Request.Builder().url(safeUrl).get().build()
        return OkHttpManager.sensitiveContentCall(
            client = httpClient,
            request = request,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = true,
        ).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("image download failed (${response.code})")
            }
            (response.body?.bytes() ?: ByteArray(0)).also(::requireSupportedImage)
        }
    }

    private fun normalizeImageModelId(rawModel: String?): String? =
        rawModel?.trim()?.takeIf(String::isNotEmpty)?.replace(Regex("\\s+"), "")

    private data class ImageGenerationRoute(
        val endpoint: String,
        val apiKey: String,
        val customHeaders: Map<String, String>,
        val model: String,
        val providerProfileId: String,
        val providerProfileName: String,
    )

    companion object {
        internal const val DEFAULT_IMAGE_MODEL = "gpt-image-2"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val SUPPORTED_OUTPUT_FORMATS = setOf("png", "webp", "jpeg")
        private val IMAGE_GENERATION_ENDPOINT_SUFFIXES = listOf(
            "/v1/images/generations",
            "/images/generations",
        )

        /**
         * The configured image provider owns prompt capacity.  The runtime only
         * normalizes the required tool argument, so a local policy cannot reject
         * an otherwise valid provider request before it reaches the harness.
         */
        internal fun requireImageGenerationPrompt(rawPrompt: String?): String =
            rawPrompt?.trim().orEmpty().also { prompt ->
                require(prompt.isNotEmpty()) { "prompt cannot be empty" }
            }

        internal fun resolveImageGenerationEndpoint(baseUrl: String, apiKey: String): String {
            require(apiKey.isNotBlank()) { "Image provider apiKey is empty" }
            val raw = baseUrl.trim()
            require(raw.isNotEmpty()) { "Image provider baseUrl is empty" }
            val stripped = ModelProviderConfigStore.stripDirectRequestUrlMarker(raw).trimEnd('/')
            if (ModelProviderConfigStore.hasDirectRequestUrlMarker(raw)) {
                return stripped
            }
            if (IMAGE_GENERATION_ENDPOINT_SUFFIXES.any { stripped.endsWith(it, true) }) {
                return stripped
            }
            return if (stripped.endsWith("/v1", true)) {
                "$stripped/images/generations"
            } else {
                "$stripped/v1/images/generations"
            }
        }

        internal fun decodeBase64Image(encoded: String): ByteArray? {
            val normalized = encoded.substringAfter(',', encoded).filterNot(Char::isWhitespace)
            if (normalized.isBlank()) return null
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = runCatching { Base64.getDecoder().decode(padded) }
                .recoverCatching { Base64.getUrlDecoder().decode(padded) }
                .getOrNull()
                ?: return null
            return decoded
        }


        internal fun requireSupportedImage(bytes: ByteArray) {
            require(bytes.isNotEmpty()) { "image generation returned empty image data" }
            val png = bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            val jpeg = bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            val webp = bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
            require(png || jpeg || webp) { "image generation returned an unsupported file type" }
        }
    }
}
private suspend fun Call.awaitResponse(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isCompleted) {
                    continuation.resumeWithException(error)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCompleted) {
                    response.close()
                } else {
                    continuation.resume(response)
                }
            }
        })
    }
