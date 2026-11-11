package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentImageAttachmentSupport
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.AgentCapabilityToolDefinition
import cn.com.omnimind.bot.agent.vision.VisionUnderstandingService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale

/**
 * 独立的视觉理解工具处理器（image_analyze）。
 *
 * 注册于 AgentCapabilityModule definitions，不修改 AgentToolDefinitions.kt。
 * 文件读取复用 workspace 权限检查与 resolvePath。
 */
class ImageAnalyzeToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {

    override val toolNames: Set<String> = setOf(TOOL_NAME)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim()
            val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim()

            if (path.isNullOrEmpty()) {
                return ToolExecutionResult.Error(TOOL_NAME, "请提供要分析的图片路径（path）")
            }

            // 复用工作区与公开存储权限检查
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }

            val file = workspaceManager.resolvePath(
                inputPath = path,
                workspace = env.workspaceDescriptor,
                allowPublicStorage = true
            )
            helper.requirePublicStorageAccessIfNeeded(callback, file.canonicalPath)?.let { return it }
            if (!file.exists()) {
                return ToolExecutionResult.Error(TOOL_NAME, "图片文件不存在：$path")
            }
            if (!file.isFile) {
                return ToolExecutionResult.Error(TOOL_NAME, "目标不是文件：$path")
            }

            val mimeType = workspaceManager.guessMimeType(file)
            if (!isImageFile(file, mimeType)) {
                return ToolExecutionResult.Error(TOOL_NAME, "目标文件不是受支持的图片格式：${file.name}")
            }

            val dataUrl = AgentImageAttachmentSupport.resolveImageAttachmentUrl(
                mapOf("path" to file.absolutePath, "mimeType" to mimeType)
            )
            if (dataUrl.isBlank()) {
                return ToolExecutionResult.Error(TOOL_NAME, "无法读取图片文件内容：${file.name}")
            }

            if (!VisionUnderstandingService.isConfigured()) {
                return ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "视觉理解服务未配置，请在设置中为【视觉理解】场景配置模型提供商与模型（scene.vision.understanding）。"
                )
            }

            val analysisResult = VisionUnderstandingService.analyzeImage(
                imageDataUrl = dataUrl,
                prompt = prompt,
                turnJob = env.turnJob
            )

            val shellPath = workspaceManager.shellPathForAndroid(file) ?: file.absolutePath
            val payload = mapOf(
                "path" to shellPath,
                "analysis" to analysisResult
            )
            val encodedPayload = helper.encodeLocalizedPayload(payload)
            val artifacts = listOf(workspaceManager.buildArtifactForFile(file, TOOL_NAME))

            ToolExecutionResult.ContextResult(
                toolName = TOOL_NAME,
                summaryText = helper.localized("图片分析完成：${file.name}"),
                previewJson = encodedPayload,
                rawResultJson = encodedPayload,
                success = true,
                artifacts = artifacts,
                workspaceId = env.workspaceDescriptor.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(TOOL_NAME, "图片分析失败：${e.message}")
        }
    }

    private fun isImageFile(file: File, mimeType: String): Boolean {
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }
        return when (file.extension.lowercase(Locale.US)) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> true
            else -> false
        }
    }

    companion object {
        const val TOOL_NAME = "image_analyze"

        val definitions: List<AgentCapabilityToolDefinition> = listOf(
            AgentCapabilityToolDefinition(
                name = TOOL_NAME,
                displayName = "图片分析与理解",
                description = "使用独立的视觉理解模型（scene.vision.understanding）分析指定图片的内容，提取文字、图表、物体信息或回答针对图片的提问。只分析图片，不开放额外工具。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") {
                            put("type", "string")
                            put("description", "要分析的图片文件路径（支持 Workspace 相对路径或设备存储路径）")
                        }
                        putJsonObject("prompt") {
                            put("type", "string")
                            put("description", "可选：针对图片的具体分析问题或要求（如提取文字、识别图表等）")
                        }
                    }
                    put("required", buildJsonArray {
                        add(JsonPrimitive("path"))
                    })
                },
                toolType = "tool"
            )
        )
    }
}
