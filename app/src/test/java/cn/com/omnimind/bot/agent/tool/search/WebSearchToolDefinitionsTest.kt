package cn.com.omnimind.bot.agent.tool.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchToolDefinitionsTest {

    private fun JsonObject.functionBody(): JsonObject =
        this["function"] as? JsonObject ?: error("missing function body")

    private fun JsonObject.functionName(): String =
        functionBody()["name"]?.jsonPrimitive?.contentOrNull
            ?: error("missing function name")

    private fun JsonObject.requiredParams(): List<String> =
        ((functionBody()["parameters"] as? JsonObject)
            ?.get("required") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()

    @Test
    fun `tool names are stable and match the exported set`() {
        assertEquals(
            setOf("tavily_search", "exa_search", "firecrawl_scrape"),
            WebSearchToolDefinitions.toolNames
        )
        assertEquals(
            setOf(
                WebSearchToolDefinitions.TAVILY_TOOL_NAME,
                WebSearchToolDefinitions.EXA_TOOL_NAME,
                WebSearchToolDefinitions.FIRECRAWL_TOOL_NAME
            ),
            WebSearchToolDefinitions.toolNames
        )
    }

    @Test
    fun `webSearchTools exposes exactly the three search tools`() {
        val tools = WebSearchToolDefinitions.webSearchTools()
        assertEquals(3, tools.size)
        assertEquals(WebSearchToolDefinitions.toolNames, tools.map { it.functionName() }.toSet())
        tools.forEach { assertTrue(it["type"]?.jsonPrimitive?.contentOrNull == "function") }
    }

    @Test
    fun `tavily search requires query only`() {
        val required = WebSearchToolDefinitions.tavilySearchTool().requiredParams()
        assertEquals(listOf("query"), required)
    }

    @Test
    fun `exa search requires query only`() {
        val required = WebSearchToolDefinitions.exaSearchTool().requiredParams()
        assertEquals(listOf("query"), required)
    }

    @Test
    fun `firecrawl scrape requires url only`() {
        val required = WebSearchToolDefinitions.firecrawlScrapeTool().requiredParams()
        assertEquals(listOf("url"), required)
    }

    @Test
    fun `api key read is null and deterministic when store is not initialized`() {
        // On the JVM AppSecretStore is never initialized, so every read fails
        // closed: WebSearchApiKeyStore.get returns null for all tool names.
        WebSearchToolDefinitions.toolNames.forEach { toolName ->
            assertEquals(null, WebSearchApiKeyStore.get(toolName))
        }
    }
}
