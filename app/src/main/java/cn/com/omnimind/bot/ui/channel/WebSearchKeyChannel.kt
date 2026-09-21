package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.bot.agent.tool.search.WebSearchApiKeyStore
import cn.com.omnimind.bot.agent.tool.search.WebSearchToolDefinitions
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 联网搜索 API Key 配置通道
 *
 * 供设置页读写 Tavily / Exa / Firecrawl 的 API Key。
 * 密钥仅保存在原生 AppSecretStore（Keystore 加密），不向 Flutter 返回明文；
 * Flutter 侧只能查询“是否已配置”、写入与删除。
 */
class WebSearchKeyChannel {
    companion object {
        private const val CHANNEL_NAME = "web_search_key"
    }

    private var channel: MethodChannel? = null

    fun setChannel(flutterEngine: FlutterEngine) {
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler { call, result ->
            val toolName = call.argument<String>("toolName").orEmpty()
            when (call.method) {
                "hasApiKey" -> result.success(
                    WebSearchToolDefinitions.toolNames.contains(toolName) &&
                        WebSearchApiKeyStore.get(toolName) != null
                )
                "putApiKey" -> {
                    if (!WebSearchToolDefinitions.toolNames.contains(toolName)) {
                        result.error("INVALID_TOOL", "Unknown web search tool: $toolName", null)
                        return@setMethodCallHandler
                    }
                    val apiKey = call.argument<String>("apiKey").orEmpty()
                    result.success(WebSearchApiKeyStore.put(toolName, apiKey))
                }
                "deleteApiKey" -> {
                    if (!WebSearchToolDefinitions.toolNames.contains(toolName)) {
                        result.error("INVALID_TOOL", "Unknown web search tool: $toolName", null)
                        return@setMethodCallHandler
                    }
                    result.success(WebSearchApiKeyStore.delete(toolName))
                }
                else -> result.notImplemented()
            }
        }
    }

    fun clear() {
        channel?.setMethodCallHandler(null)
        channel = null
    }
}
