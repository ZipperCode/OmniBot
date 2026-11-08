package cn.com.omnimind.bot.agent

import android.app.Application
import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.SubagentToolHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SubagentRuntimeExecutionTest {

    private lateinit var context: Context
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var helper: SharedHelper

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        helper = SharedHelper(context, json)
    }

    @Test
    fun `disabled subagent config blocks dispatch with descriptive error`() = runBlocking {
        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig(enabled = false)
        )

        val env = FakeExecutionEnvironment()
        val result = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("general", "do work")),
                concurrency = 1
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("子代理功能未启用") == true)
    }

    @Test
    fun `corrupted config error blocks dispatch with explicit error rather than silent default fallback`() = runBlocking {
        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig(enabled = false),
            configError = IllegalStateException("Corrupt JSON at line 1")
        )

        val env = FakeExecutionEnvironment()
        val result = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("general", "do work")),
                concurrency = 1
            )
        }
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("子代理配置读取失败"))
        assertTrue(message.contains("Corrupt JSON"))
    }

    @Test
    fun `subagent tool handler catches dispatcher error and returns ToolExecutionResult Error`() = runBlocking {
        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig(enabled = false)
        )
        val handler = SubagentToolHandler(helper, dispatcher)

        val args = buildJsonObject {
            put("tasks", buildJsonArray {
                add(buildJsonObject { put("instruction", "test task") })
            })
        }
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("subagent_dispatch", "分派子任务", "subagent")
        val handle = NoOpAgentRunControl.beginToolExecution("subagent_dispatch", "call-parent-dispatch-1")

        val result = handler.execute(
            toolCall = AssistantToolCall(
                id = "call-parent-dispatch-1",
                function = AssistantToolCallFunction("subagent_dispatch", args.toString())
            ),
            args = args,
            runtimeDescriptor = descriptor,
            env = FakeExecutionEnvironment(),
            callback = FakeAgentCallback(),
            toolHandle = handle
        )

        assertTrue(result is ToolExecutionResult.Error)
        val error = result as ToolExecutionResult.Error
        assertTrue(error.message.contains("子代理功能未启用"))
    }

    @Test
    fun `subagent dispatch requireProfile rejects unknown profile`() = runBlocking {
        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig(enabled = true)
        )

        val env = FakeExecutionEnvironment()
        val result = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("unknown-role", "do task")),
                concurrency = 1
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unknown subagent profile") == true)
    }

    @Test
    fun `explicit invalid model provider profile throws on dispatch without crashing setup`() = runBlocking {
        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig(
                enabled = true,
                providerProfileId = "non-existent-provider",
                modelId = "custom-model"
            ),
            providerProfileResolver = { null }
        )

        val env = FakeExecutionEnvironment()
        val result = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("general", "do task")),
                concurrency = 1
            )
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    private class FakeExecutionEnvironment : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val conversationId: Long = 12345L
        override val acpSessionId: String = "acp-session-1"
        override val acpTurnId: String = "acp-turn-1"
        override val userMessage: String = "test"
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
        override val runtimeContextRepository: AgentRuntimeContextRepository
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceDescriptor: AgentWorkspaceDescriptor
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceManager: AgentWorkspaceManager
            get() = throw UnsupportedOperationException("unused in test")
        override val workspaceMemoryService: WorkspaceMemoryService
            get() = throw UnsupportedOperationException("unused in test")
        override val conversationMode: String = AgentConversationModePolicy.AGENT_MODE
        override val reasoningEffort: String = "medium"
        override val modelProviderProfileId: String = "test-provider"
        override val terminalEnvironment: Map<String, String> = emptyMap()
        override val runControl: AgentRunControl = NoOpAgentRunControl
        override val permissionRequester: AgentPermissionRequester? = null
        override val turnJob = null
        override val longTermMemoryIndex = null
    }

    private class FakeLlmClient : AgentLlmClient {
        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
            allowTransportRetry: Boolean,
        ): ChatCompletionTurn {
            throw UnsupportedOperationException("unused in test")
        }
    }

    private class FakeToolExecutor : AgentToolExecutor {
        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            throw UnsupportedOperationException("unused in test")
        }
    }

    private class FakeCatalog : AgentToolCatalog {
        override val toolsForModel: List<cn.com.omnimind.baselib.llm.ChatCompletionTool> = emptyList()
        override fun runtimeDescriptor(toolName: String) =
            AgentToolRegistry.RuntimeToolDescriptor(toolName, toolName, "builtin")
        override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
    }

    private class FakeAgentCallback : AgentCallback {
        override suspend fun onThinkingStart() = Unit
        override suspend fun onThinkingUpdate(thinking: String) = Unit
        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit
        override suspend fun onToolCallProgress(toolName: String, progress: String, extras: Map<String, Any?>) = Unit
        override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) = Unit
        override suspend fun onChatMessage(message: String) = Unit
        override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
        override suspend fun onComplete(result: AgentResult) = Unit
        override suspend fun onError(error: String) = Unit
        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }
}
