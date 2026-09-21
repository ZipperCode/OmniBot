package cn.com.omnimind.bot.agent.tool.search

import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
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

/**
 * Minimal built-in web research tools (Tavily / Exa / Firecrawl).
 *
 * Each tool resolves its API key at execution time and fails fast with a
 * deterministic, user-actionable error when the key has not been configured.
 * Network failures and non-2xx responses surface as deterministic errors too,
 * so the model never sees a silent success.
 */
class WebSearchToolHandler : ToolHandler {

    override val toolNames: Set<String> = WebSearchToolDefinitions.toolNames

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return try {
            val apiKey = WebSearchApiKeyStore.get(toolName)
                ?: return ToolExecutionResult.Error(
                    toolName,
                    missingKeyMessage(toolName)
                )

            when (toolName) {
                WebSearchToolDefinitions.TAVILY_TOOL_NAME ->
                    tavilySearch(toolName, args, apiKey)
                WebSearchToolDefinitions.EXA_TOOL_NAME ->
                    exaSearch(toolName, args, apiKey)
                WebSearchToolDefinitions.FIRECRAWL_TOOL_NAME ->
                    firecrawlScrape(toolName, args, apiKey)
                else -> ToolExecutionResult.Error(
                    toolName,
                    "Unknown web search tool: $toolName"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(toolName, e.message ?: "$toolName failed")
        }
    }

    private fun missingKeyMessage(toolName: String): String =
        "$toolName is not configured: missing API key. " +
            "Set the ${WebSearchApiKeyStore.keyId(toolName)} key in settings first."

    private suspend fun tavilySearch(
        toolName: String,
        args: JsonObject,
        apiKey: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")
        require(query.isNotEmpty()) { "query must not be empty" }
        val maxResults = args.intArg("maxResults")?.coerceIn(1, 10) ?: 5

        val body = JSONObject()
            .put("api_key", apiKey)
            .put("query", query)
            .put("max_results", maxResults)
            .toString()
        val response = executeHttp(
            Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        )
        val results = when (response) {
            is org.json.JSONArray -> response
            is JSONObject -> response.optJSONArray("results")
            else -> null
        }
        val items = buildList {
            for (i in 0 until (results?.length() ?: 0)) {
                val item = results!!.getJSONObject(i)
                add(
                    mapOf(
                        "title" to item.optString("title"),
                        "url" to item.optString("url"),
                        "content" to item.optString("content")
                    )
                )
            }
        }
        successResult(
            toolName,
            summary = "Tavily 搜索完成：$query",
            payload = mapOf("query" to query, "results" to items)
        )
    }

    private suspend fun exaSearch(
        toolName: String,
        args: JsonObject,
        apiKey: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")
        require(query.isNotEmpty()) { "query must not be empty" }
        val numResults = args.intArg("numResults")?.coerceIn(1, 10) ?: 5

        val body = JSONObject()
            .put("query", query)
            .put("numResults", numResults)
            .toString()
        val response = executeHttp(
            Request.Builder()
                .url("https://api.exa.ai/search")
                .header("x-api-key", apiKey)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        )
        val results = when (response) {
            is org.json.JSONArray -> response
            is JSONObject -> response.optJSONArray("results")
            else -> null
        }
        val items = buildList {
            for (i in 0 until (results?.length() ?: 0)) {
                val item = results!!.getJSONObject(i)
                add(
                    mapOf(
                        "title" to item.optString("title"),
                        "url" to item.optString("url"),
                        "text" to item.optString("text")
                    )
                )
            }
        }
        successResult(
            toolName,
            summary = "Exa 搜索完成：$query",
            payload = mapOf("query" to query, "results" to items)
        )
    }

    private suspend fun firecrawlScrape(
        toolName: String,
        args: JsonObject,
        apiKey: String
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args.stringArg("url")
        require(url.isNotEmpty()) { "url must not be empty" }
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "url must be an http(s) address"
        }

        val body = JSONObject()
            .put("url", url)
            .put("formats", org.json.JSONArray().put("markdown"))
            .toString()
        val response = executeHttp(
            Request.Builder()
                .url("https://api.firecrawl.dev/v1/scrape")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        )
        val responseObject = response as? JSONObject
            ?: throw IllegalArgumentException("Firecrawl response was not a JSON object")
        val data = responseObject.optJSONObject("data")
        val markdown = data?.optString("markdown").orEmpty()
        successResult(
            toolName,
            summary = "Firecrawl 抓取完成：$url",
            payload = mapOf(
                "url" to url,
                "title" to (data?.optJSONObject("metadata")?.optString("title") ?: ""),
                "markdown" to markdown
            )
        )
    }

    private fun executeHttp(request: Request): Any {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalArgumentException(
                    "HTTP ${response.code}: ${body.take(300)}"
                )
            }
            return try {
                JSONObject(body)
            } catch (_: org.json.JSONException) {
                org.json.JSONArray(body)
            }
        }
    }

    private fun successResult(
        toolName: String,
        summary: String,
        payload: Map<String, Any?>
    ): ToolExecutionResult {
        val json = JSONObject(payload).toString()
        return ToolExecutionResult.ContextResult(
            toolName = toolName,
            summaryText = summary,
            previewJson = json,
            rawResultJson = json,
            success = true
        )
    }

    private fun JsonObject.stringArg(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.intArg(name: String): Int? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
}
