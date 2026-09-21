package cn.com.omnimind.bot.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.util.AppSecretStore
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.AgentRuntimeContextRepository
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.DefaultAgentExecutionEnvironment
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.ResolvedSkillContext
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.WorkspaceMemoryService
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.tool.search.WebSearchApiKeyStore
import cn.com.omnimind.bot.agent.tool.search.WebSearchToolDefinitions
import cn.com.omnimind.bot.agent.tool.search.WebSearchToolHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchProviderKeyDeviceTest {
    @Test
    fun executeKeysConfiguredByRealAppUi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("encrypted secret store must initialize", AppSecretStore.initialize(context))
        WebSearchToolDefinitions.toolNames.forEach { toolName ->
            assertNotNull("$toolName must be configured from the app UI", WebSearchApiKeyStore.get(toolName))
        }

        val workspaceManager = AgentWorkspaceManager(context)
        val workspace = workspaceManager.buildWorkspaceDescriptor(null, "device-search-test")
        val environment = DefaultAgentExecutionEnvironment(
            agentRunId = "device-search-test",
            userMessage = "verify built-in search providers",
            runtimeContextRepository = AgentRuntimeContextRepository(context),
            workspaceDescriptor = workspace,
            resolvedSkills = emptyList<ResolvedSkillContext>(),
            workspaceManager = workspaceManager,
            workspaceMemoryService = WorkspaceMemoryService(context, workspaceManager),
            conversationMode = "agent",
            runControl = NoOpAgentRunControl,
        )
        val callback = RecordingCallback()
        val handler = WebSearchToolHandler()
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = "search",
            displayName = "search",
            toolType = "search",
        )

        val tavilyResult = handler.execute(
            call("tavily_search", "{\"query\":\"OmniBot official documentation\",\"maxResults\":1}"),
            jsonObject("{\"query\":\"OmniBot official documentation\",\"maxResults\":1}"),
            descriptor,
            environment,
            callback,
            NoOpAgentRunControl.beginToolExecution("tavily_search", "device-tavily"),
        )
        assertSuccess("tavily_search", tavilyResult)

        val exaArgs = "{\"query\":\"OmniBot official documentation\",\"numResults\":1}"
        val exaResult = handler.execute(
            call("exa_search", exaArgs),
            jsonObject(exaArgs),
            descriptor,
            environment,
            callback,
            NoOpAgentRunControl.beginToolExecution("exa_search", "device-exa"),
        )
        assertSuccess("exa_search", exaResult)

        val firecrawlArgs = "{\"url\":\"https://docs.firecrawl.dev\"}"
        val firecrawlResult = handler.execute(
            call("firecrawl_scrape", firecrawlArgs),
            jsonObject(firecrawlArgs),
            descriptor,
            environment,
            callback,
            NoOpAgentRunControl.beginToolExecution("firecrawl_scrape", "device-firecrawl"),
        )
        assertSuccess("firecrawl_scrape", firecrawlResult)
    }

    private fun assertSuccess(toolName: String, result: ToolExecutionResult) {
        assertTrue(
            "$toolName failed: ${resultMessage(result)}",
            result is ToolExecutionResult.ContextResult && result.success,
        )
    }

    private fun resultMessage(result: ToolExecutionResult): String = when (result) {
        is ToolExecutionResult.Error -> result.message
        is ToolExecutionResult.ContextResult -> result.summaryText
        else -> result.toString()
    }

    private fun call(name: String, arguments: String) = AssistantToolCall(
        id = "device-$name",
        function = AssistantToolCallFunction(name = name, arguments = arguments),
    )

    private fun jsonObject(value: String): JsonObject =
        Json.parseToJsonElement(value) as JsonObject

    private fun requireArgument(args: android.os.Bundle, name: String): String =
        args.getString(name)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Missing instrumentation argument: $name")

    private class RecordingCallback : AgentCallback {
        override suspend fun onThinkingStart() = Unit
        override suspend fun onThinkingUpdate(thinking: String) = Unit
        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit
        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>,
        ) = Unit
        override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) = Unit
        override suspend fun onChatMessage(message: String) = Unit
        override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
        override suspend fun onComplete(result: AgentResult) = Unit
        override suspend fun onError(error: String) = Unit
        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }
}
