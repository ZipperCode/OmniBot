package cn.com.omnimind.bot.agent.vision

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/**
 * 独立的场景视觉理解服务（scene.vision.understanding）。
 *
 * 绑定是唯一配置来源，无默认 Provider/model，不隐式继承主 Agent 路由。
 * 仅用于图片内容理解与分析，不开放额外工具调用。
 */
object VisionUnderstandingService {
    const val SCENE_ID = "scene.vision.understanding"
    private const val TAG = "VisionUnderstanding"
    private const val DEFAULT_PROMPT = "请详细分析并描述这张图片的内容，包括文字、图表和关键信息。"

    /**
     * 判断视觉理解服务是否已配置且有效。
     * 绑定为唯一配置来源，必须在 SceneModelBindingStore 中显式绑定有效 Provider 和模型。
     */
    fun isConfigured(): Boolean {
        val binding = SceneModelBindingStore.getBinding(SCENE_ID) ?: return false
        if (binding.modelId.isBlank()) {
            return false
        }
        val profile = ModelProviderConfigStore.getProfile(binding.providerProfileId) ?: return false
        return profile.isConfigured()
    }

    /**
     * 分析单张图片。
     *
     * @param imageDataUrl 图片的 data URL（例如 "data:image/png;base64,..."）或 HTTP URL
     * @param prompt 针对图片的分析提问或指令
     * @param turnJob 当前 ACP turn 的协程 Job，用于取消继承
     * @return 视觉模型的文字分析结果
     * @throws IllegalStateException 当服务未配置或模型调用失败时
     * @throws CancellationException 当 turn 被取消时
     */
    suspend fun analyzeImage(
        imageDataUrl: String,
        prompt: String? = null,
        turnJob: Job? = null
    ): String = withContext(Dispatchers.IO) {
        // 取消检查：若 turnJob 已取消则立即终止
        turnJob?.ensureActive()
        coroutineContext.ensureActive()

        require(imageDataUrl.isNotBlank()) { "图片内容不能为空" }
        if (!isConfigured()) {
            throw IllegalStateException("视觉理解服务未配置，请在设置中为【视觉理解】场景配置模型提供商与模型（scene.vision.understanding）。")
        }


        val effectivePrompt = prompt?.trim()?.ifEmpty { DEFAULT_PROMPT } ?: DEFAULT_PROMPT

        // 日志中严禁输出完整图片 base64 内容、密钥或敏感上游内容，仅记录元信息长度
        OmniLog.i(
            TAG,
            "调用视觉理解服务: promptLength=${effectivePrompt.length}, imagePayloadLength=${imageDataUrl.length}"
        )

        val userContent = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", effectivePrompt)
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", imageDataUrl)
                })
            })
        }

        val request = ChatCompletionRequest(
            model = SCENE_ID,
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = userContent
                )
            ),
            tools = emptyList(), // 只分析图片，不开放额外工具
            stream = false
        )

        val response = try {
            turnJob?.ensureActive()
            HttpController.postSceneChatCompletion(request)
        } catch (e: CancellationException) {
            OmniLog.i(TAG, "视觉理解调用被取消")
            throw e
        } catch (e: Exception) {
            // 日志严禁打印上游原始异常详情，仅记录异常类型
            OmniLog.w(TAG, "视觉理解请求发生异常: ${e.javaClass.simpleName}")
            throw IllegalStateException("视觉理解请求失败: ${e.javaClass.simpleName}", e)
        }

        turnJob?.ensureActive()

        if (!response.success) {
            // 日志仅记录状态码
            OmniLog.w(TAG, "视觉理解模型调用失败: code=${response.code}")
            val errorMsg = response.message.ifBlank { "视觉理解服务返回错误 (code=${response.code})" }
            throw IllegalStateException(errorMsg)
        }

        // response.content 为空时严禁使用 response.message（常见 "success"）假造分析内容
        val resultText = response.content.trim()
        if (resultText.isBlank()) {
            OmniLog.w(TAG, "视觉理解模型返回内容为空: code=${response.code}")
            throw IllegalStateException("视觉理解服务未返回有效分析内容")
        }

        OmniLog.i(TAG, "视觉理解分析完成，结果字数=${resultText.length}")
        return@withContext resultText
    }
}
