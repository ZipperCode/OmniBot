package cn.com.omnimind.bot.agent.tool.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Minimal built-in web research tools backed by external search APIs.
 *
 * Schemas only: each tool resolves its API key at execution time from
 * [cn.com.omnimind.bot.agent.tool.search.WebSearchApiKeyStore] and fails with
 * a deterministic error when the key is not configured.
 */
object WebSearchToolDefinitions {

    const val TAVILY_TOOL_NAME = "tavily_search"
    const val EXA_TOOL_NAME = "exa_search"
    const val FIRECRAWL_TOOL_NAME = "firecrawl_scrape"

    /** Tool names are also the MMKV key ids used by WebSearchApiKeyStore. */
    val toolNames: Set<String> = setOf(TAVILY_TOOL_NAME, EXA_TOOL_NAME, FIRECRAWL_TOOL_NAME)

    fun tavilySearchTool(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", TAVILY_TOOL_NAME)
            put("displayName", "Tavily 搜索")
            put("toolType", "search")
            put(
                "description",
                "调用 Tavily Search API 进行联网搜索并返回结果列表。" +
                    "需要已配置 Tavily API Key，否则该调用会返回确定性的配置错误。"
            )
            put("postToolRule", "读取结果后再决定是否继续搜索或改用其他工具。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "搜索关键词。")
                    }
                    putJsonObject("maxResults") {
                        put("type", "integer")
                        put("description", "可选，返回结果条数上限，1-10，默认 5。")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("query"))
                }
            }
        }
    }

    fun exaSearchTool(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", EXA_TOOL_NAME)
            put("displayName", "Exa 搜索")
            put("toolType", "search")
            put(
                "description",
                "调用 Exa Search API 进行联网搜索并返回结果列表。" +
                    "需要已配置 Exa API Key，否则该调用会返回确定性的配置错误。"
            )
            put("postToolRule", "读取结果后再决定是否继续搜索或改用其他工具。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "搜索关键词。")
                    }
                    putJsonObject("numResults") {
                        put("type", "integer")
                        put("description", "可选，返回结果条数上限，1-10，默认 5。")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("query"))
                }
            }
        }
    }

    fun firecrawlScrapeTool(): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FIRECRAWL_TOOL_NAME)
            put("displayName", "Firecrawl 抓取")
            put("toolType", "search")
            put(
                "description",
                "调用 Firecrawl Scrape API 抓取单个网页并返回 Markdown 正文。" +
                    "需要已配置 Firecrawl API Key，否则该调用会返回确定性的配置错误。"
            )
            put("postToolRule", "读取结果后再决定是否需要抓取更多页面。")
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("url") {
                        put("type", "string")
                        put("description", "要抓取的 http(s) 网页地址。")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("url"))
                }
            }
        }
    }

    fun webSearchTools(): List<JsonObject> = listOf(
        tavilySearchTool(),
        exaSearchTool(),
        firecrawlScrapeTool()
    )
}
