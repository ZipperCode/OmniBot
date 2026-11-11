package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.http.OkHttpManager
import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.DeepSeekProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
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
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class ImageGenerationToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
) : ToolHandler {
    override val toolNames: Set<String> = setOf(TOOL_NAME)

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
        val toolName = TOOL_NAME
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
            require(outputPath.isNotEmpty()) { "输出路径不能为空" }

            val route = resolveRoute()
            val size = args["size"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
            val quality = args["quality"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
            val requestedFormat = args["format"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)
            val background = args["background"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)

            val file = workspaceManager.resolvePath(
                inputPath = outputPath,
                workspace = workspace,
                allowPublicStorage = true,
            )

            helper.reportToolProgress(
                callback,
                toolName,
                "正在生成图片",
                mapOf("model" to route.model, "outputPath" to outputPath),
                toolHandle,
            )

            val generatedImage = withContext(Dispatchers.IO) {
                requestGeneratedImage(
                    route = route,
                    prompt = prompt,
                    size = size,
                    quality = quality,
                    outputFormat = requestedFormat,
                    background = background,
                )
            }
            val finalMimeType = requireSupportedImage(generatedImage.bytes)

            file.parentFile?.mkdirs()
            file.writeBytes(generatedImage.bytes)

            val artifact = workspaceManager.buildArtifactForFile(file, toolName).copy(
                mimeType = finalMimeType,
                previewKind = "image",
            )
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "mimeType" to finalMimeType,
                "model" to route.model,
                "providerProfileId" to route.providerProfileId,
                "providerProfileName" to route.providerProfileName,
                "protocol" to route.protocol.name.lowercase(),
            )
            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已生成图片: ${file.name}"),
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
            val errorMessage = error.message?.takeIf(String::isNotBlank)
                ?: "图像生成失败 (${error.javaClass.simpleName})"
            helper.errorResult(toolName, errorMessage, "图像生成失败")
        }
    }

    internal fun resolveRoute(): ImageGenerationRoute {
        val binding = SceneModelBindingStore.getBinding(SCENE_ID)
            ?: throw IllegalStateException("未配置图像生成场景模型，请在设置中为图像生成场景绑定 Provider 和模型。")
        val providerProfileId = binding.providerProfileId.trim()
        if (providerProfileId.isEmpty()) {
            throw IllegalStateException("未配置图像生成场景 Provider，请在设置中为图像生成场景选择 Provider。")
        }
        val modelId = binding.modelId.trim()
        if (modelId.isEmpty()) {
            throw IllegalStateException("未配置图像生成场景模型，请在设置中为图像生成场景选择模型。")
        }
        val profile = ModelProviderConfigStore.getProfile(providerProfileId)
            ?: throw IllegalStateException("图像生成 Provider（ID: $providerProfileId）不存在，请在设置中重新配置图像生成场景。")

        if (ChatGptOAuthProvider.isOAuth(profile.sourceType)) {
            throw IllegalStateException("ChatGPT OAuth 不支持图像生成，请选择支持图像生成的 API Provider。")
        }
        if (profile.readOnly) {
            throw IllegalStateException("当前图像生成 Provider 为只读，无法用于图像生成，请选择自定义 Provider。")
        }
        if (!profile.isConfigured()) {
            throw IllegalStateException("图像生成 Provider 未配置 Base URL，请在设置中检查配置。")
        }
        val apiKey = profile.apiKey.trim()

        validateModelCapabilitiesIfAvailable(profile, modelId)

        val normalizedProtocol = DeepSeekProvider.normalizeProtocolType(profile.protocolType)
        val protocol = when (normalizedProtocol) {
            "openai_compatible" -> ImageGenerationProtocol.OPENAI_COMPATIBLE
            "gemini" -> ImageGenerationProtocol.GEMINI
            "anthropic" -> throw IllegalStateException("Anthropic 协议不支持图像生成，图像生成场景仅支持 OpenAI-Compatible 与 Gemini 协议。")
            "deepseek" -> throw IllegalStateException("DeepSeek 协议不支持图像生成，图像生成场景仅支持 OpenAI-Compatible 与 Gemini 协议。")
            else -> throw IllegalStateException("当前 Provider 协议（$normalizedProtocol）不支持图像生成，图像生成场景仅支持 OpenAI-Compatible 与 Gemini 协议。")
        }

        val endpoint = when (protocol) {
            ImageGenerationProtocol.OPENAI_COMPATIBLE -> resolveImageGenerationEndpoint(
                baseUrl = profile.baseUrl,
            )
            ImageGenerationProtocol.GEMINI -> GeminiImageProtocolHandler.resolveEndpoint(
                baseUrl = profile.baseUrl,
                model = modelId,
            )
        }

        return ImageGenerationRoute(
            protocol = protocol,
            endpoint = endpoint,
            apiKey = apiKey,
            customHeaders = profile.customHeaders,
            model = modelId,
            providerProfileId = profile.id,
            providerProfileName = profile.name,
        )
    }

    private fun validateModelCapabilitiesIfAvailable(
        profile: ModelProviderProfile,
        modelId: String,
    ) {
        val context = runCatching { helper.context }.getOrNull() ?: return
        val cachedModel = runCatching {
            ModelProviderConfigStore.cachedModels(context, profile)
                .firstOrNull { it.id.equals(modelId, ignoreCase = true) }
        }.getOrNull() ?: return

        // 优先明确 supportsImageOutput == false 拒绝，type=image 不能推翻否定
        val supportsImageOutput = resolveSupportsImageOutput(cachedModel)
        if (supportsImageOutput == false) {
            throw IllegalStateException("当前模型（$modelId）明确不支持图像输出，无法用于图像生成，请选择支持生图的模型。")
        }

        // 拒绝明确 embedding / tts
        val declaredModelType = cachedModel.modelType?.trim()?.lowercase()
        if (declaredModelType == "embedding" || declaredModelType == "tts") {
            throw IllegalStateException("当前模型（$modelId）类型为 $declaredModelType，不支持图像生成，请选择支持生图的模型。")
        }
    }

    private fun resolveSupportsImageOutput(option: ProviderModelOption): Boolean? {
        option.supportsImageOutput?.let { return it }

        if (option.outputModalities.isNotEmpty()) {
            return option.outputModalities.any { it.equals("image", ignoreCase = true) }
        }
        return null
    }

    private suspend fun requestGeneratedImage(
        route: ImageGenerationRoute,
        prompt: String,
        size: String?,
        quality: String?,
        outputFormat: String?,
        background: String?,
    ): GeneratedImage {
        val request = when (route.protocol) {
            ImageGenerationProtocol.OPENAI_COMPATIBLE -> {
                val requestJson = OpenAiImageProtocolHandler.buildRequestJson(
                    model = route.model,
                    prompt = prompt,
                    size = size,
                    quality = quality,
                    outputFormat = outputFormat,
                    background = background,
                )
                OpenAiImageProtocolHandler.buildHttpRequest(
                    endpoint = route.endpoint,
                    apiKey = route.apiKey,
                    customHeaders = route.customHeaders,
                    requestJson = requestJson,
                    allowInsecureTransport = true,
                )
            }
            ImageGenerationProtocol.GEMINI -> {
                val requestJson = GeminiImageProtocolHandler.buildRequestJson(prompt = prompt)
                GeminiImageProtocolHandler.buildHttpRequest(
                    endpoint = route.endpoint,
                    apiKey = route.apiKey,
                    customHeaders = route.customHeaders,
                    requestJson = requestJson,
                    allowInsecureTransport = true,
                )
            }
        }

        val response = OkHttpManager.sensitiveContentCall(
            client = httpClient,
            request = request,
            allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            allowInsecureTransport = true,
        ).awaitResponse()

        return response.use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                val bodyString = bytes.toString(Charsets.UTF_8)
                val jsonPayload = runCatching { JSONObject(bodyString) }.getOrNull()
                val apiMessage = jsonPayload?.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
                val code = resp.code
                if (apiMessage != null) {
                    throw IllegalStateException("图像生成请求失败 ($code): $apiMessage")
                } else {
                    throw IllegalStateException("图像生成请求失败 ($code)")
                }
            }
            val payload = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw IllegalStateException("图像生成响应返回了无效 JSON") }

            when (route.protocol) {
                ImageGenerationProtocol.OPENAI_COMPATIBLE -> {
                    OpenAiImageProtocolHandler.extractImage(
                        payload = payload,
                        downloadImageFn = ::downloadImage,
                        decodeBase64Fn = ::decodeBase64Image,
                    ) ?: throw IllegalStateException("OpenAI 图像生成响应未包含图片数据（b64_json 或 url）")
                }
                ImageGenerationProtocol.GEMINI -> {
                    GeminiImageProtocolHandler.extractImage(
                        payload = payload,
                        decodeBase64Fn = ::decodeBase64Image,
                    )
                }
            }
        }
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
                throw IllegalStateException("图像下载失败 (${response.code})")
            }
            (response.body?.bytes() ?: ByteArray(0)).also(::requireSupportedImage)
        }
    }

    companion object {
        const val TOOL_NAME = "image_generate"
        const val SCENE_ID = "scene.image.generate"

        /**
         * The configured image provider owns prompt capacity. The runtime only
         * normalizes the required tool argument, so a local policy cannot reject
         * an otherwise valid provider request before it reaches the harness.
         */
        internal fun requireImageGenerationPrompt(rawPrompt: String?): String =
            rawPrompt?.trim().orEmpty().also { prompt ->
                require(prompt.isNotEmpty()) { "生图提示词不能为空" }
            }

        internal fun resolveImageGenerationEndpoint(baseUrl: String): String {
            val raw = baseUrl.trim()
            require(raw.isNotEmpty()) { "图像生成 Provider Base URL 不能为空" }
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

        private val IMAGE_GENERATION_ENDPOINT_SUFFIXES = listOf(
            "/v1/images/generations",
            "/images/generations",
        )

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

        internal fun requireSupportedImage(bytes: ByteArray): String {
            require(bytes.isNotEmpty()) { "图像生成返回了空的图片数据" }
            val png = bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            val jpeg = bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            val webp = bytes.size >= 12 &&
                bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
            require(png || jpeg || webp) { "图像生成返回了不支持的文件格式" }
            return when {
                png -> "image/png"
                jpeg -> "image/jpeg"
                webp -> "image/webp"
                else -> "image/png"
            }
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
