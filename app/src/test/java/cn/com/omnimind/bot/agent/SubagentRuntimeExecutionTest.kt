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
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.mockito.Mockito.mock
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

    @Test
    fun `same dispatch runs multiple roles with different models and reuses model client`() = runBlocking {
        val providerAlpha = ModelProviderProfile(
            id = "prov-alpha",
            name = "Alpha",
            baseUrl = "https://api.alpha.com/v1",
            apiKey = "alpha-key"
        )
        val providerBeta = ModelProviderProfile(
            id = "prov-beta",
            name = "Beta",
            baseUrl = "https://api.beta.com/v1",
            apiKey = "beta-key"
        )
        val providers = mapOf("prov-alpha" to providerAlpha, "prov-beta" to providerBeta)

        val explorerProfile = SubagentProfile(
            id = "explorer",
            displayName = "探索者",
            systemPrompt = "探索系统提示词",
            providerProfileId = "prov-alpha",
            modelId = "deepseek-v4-pro"
        )
        val plannerProfile = SubagentProfile(
            id = "planner",
            displayName = "规划器",
            systemPrompt = "规划系统提示词",
            providerProfileId = "prov-beta",
            modelId = "deepseek-v4-flash"
        )

        val config = XiaowanSubagentConfig(
            enabled = true,
            providerProfileId = null,
            modelId = null,
            profiles = listOf(SubagentProfileRegistry.general, explorerProfile, plannerProfile)
        )

        val clientAlpha = RecordingTurnLlmClient("result-alpha")
        val clientBeta = RecordingTurnLlmClient("result-beta")

        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "parent-model",
            config = config,
            providerProfileResolver = { providers[it] },
            subagentLlmClientFactory = { model, _ ->
                if (model == "deepseek-v4-pro") clientAlpha else clientBeta
            },
            subagentImagePolicyResolver = { _, _ -> AgentToolImageContinuationPolicy.DEFAULT }
        )

        val env = FakeExecutionEnvironment()
        val results = dispatcher.dispatch(
            parentEnv = env,
            tasks = listOf(
                SubagentDispatcher.SubagentTaskSpec("explorer", "task-alpha-1"),
                SubagentDispatcher.SubagentTaskSpec("planner", "task-beta"),
                SubagentDispatcher.SubagentTaskSpec("explorer", "task-alpha-2")
            ),
            concurrency = 3
        )

        assertEquals(3, results.size)
        assertEquals("completed", results[0].status)
        assertEquals("completed", results[1].status)
        assertEquals("completed", results[2].status)

        // 验证各角色输出隔离且由对应独立 client 完成，绝无 request model 字符串回声
        assertEquals("result-alpha", results[0].finalContent)
        assertEquals("result-beta", results[1].finalContent)
        assertEquals("result-alpha", results[2].finalContent)

        // 对 LLM 实际接收到的 request.model 进行行为断言
        assertEquals(2, clientAlpha.requests.size)
        assertEquals("deepseek-v4-pro", clientAlpha.requests[0].model)
        assertEquals("deepseek-v4-pro", clientAlpha.requests[1].model)
        assertEquals(1, clientBeta.requests.size)
        assertEquals("deepseek-v4-flash", clientBeta.requests[0].model)
    }

    @Test
    fun `default inheritance switches when config default changes and falls back to parent when unconfigured`() = runBlocking {
        val providerAlpha = ModelProviderProfile(
            id = "prov-alpha",
            name = "Alpha",
            baseUrl = "https://api.alpha.com/v1",
            apiKey = "alpha-key"
        )
        val providerBeta = ModelProviderProfile(
            id = "prov-beta",
            name = "Beta",
            baseUrl = "https://api.beta.com/v1",
            apiKey = "beta-key"
        )
        val providers = mapOf("prov-alpha" to providerAlpha, "prov-beta" to providerBeta)

        val explorerProfile = SubagentProfile(
            id = "explorer",
            displayName = "探索者",
            systemPrompt = "探索",
            providerProfileId = "prov-alpha",
            modelId = "deepseek-v4-pro"
        )

        val parentClient = RecordingTurnLlmClient("result-parent")
        val alphaClient = RecordingTurnLlmClient("result-alpha")
        val betaClient = RecordingTurnLlmClient("result-beta")

        fun createDispatcher(cfg: XiaowanSubagentConfig) = SubagentDispatcher(
            llmClient = parentClient,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "parent-model-gpt",
            config = cfg,
            providerProfileResolver = { providers[it] },
            subagentLlmClientFactory = { model, _ ->
                if (model == "deepseek-v4-pro") alphaClient else betaClient
            },
            subagentImagePolicyResolver = { _, _ -> AgentToolImageContinuationPolicy.DEFAULT }
        )

        val env = FakeExecutionEnvironment(modelProviderProfileId = "parent-provider-id")

        // 1. 同一批次：角色 A (explorer) 显式配置为 Alpha；角色 B (general) 未覆盖，继承默认 Alpha
        val config1 = XiaowanSubagentConfig(
            enabled = true,
            providerProfileId = "prov-alpha",
            modelId = "deepseek-v4-pro",
            profiles = listOf(SubagentProfileRegistry.general, explorerProfile)
        )
        val result1 = createDispatcher(config1).dispatch(
            parentEnv = env,
            tasks = listOf(
                SubagentDispatcher.SubagentTaskSpec("explorer", "task A-1"),
                SubagentDispatcher.SubagentTaskSpec("general", "task B-1")
            ),
            concurrency = 2
        )
        assertEquals(2, result1.size)
        assertEquals("result-alpha", result1[0].finalContent)
        assertEquals("result-alpha", result1[1].finalContent)
        assertEquals("deepseek-v4-pro", alphaClient.requests[alphaClient.requests.size - 2].model)
        assertEquals("deepseek-v4-pro", alphaClient.requests[alphaClient.requests.size - 1].model)

        // 2. 修改默认子代理模型配置为 Beta：角色 B (general) 随之切换为 Beta，而角色 A (explorer) 依然保留显式配置 Alpha
        val config2 = XiaowanSubagentConfig(
            enabled = true,
            providerProfileId = "prov-beta",
            modelId = "deepseek-v4-flash",
            profiles = listOf(SubagentProfileRegistry.general, explorerProfile)
        )
        val result2 = createDispatcher(config2).dispatch(
            parentEnv = env,
            tasks = listOf(
                SubagentDispatcher.SubagentTaskSpec("explorer", "task A-2"),
                SubagentDispatcher.SubagentTaskSpec("general", "task B-2")
            ),
            concurrency = 2
        )
        assertEquals(2, result2.size)
        assertEquals("result-alpha", result2[0].finalContent)
        assertEquals("result-beta", result2[1].finalContent)
        assertEquals("deepseek-v4-pro", alphaClient.requests.last().model)
        assertEquals("deepseek-v4-flash", betaClient.requests.last().model)

        // 3. 默认子代理模型未配置（两者皆空）：角色 B 跟随父级模型与父级 Client，而角色 A 依然保留在 Alpha
        val config3 = XiaowanSubagentConfig(
            enabled = true,
            providerProfileId = null,
            modelId = null,
            profiles = listOf(SubagentProfileRegistry.general, explorerProfile)
        )
        val result3 = createDispatcher(config3).dispatch(
            parentEnv = env,
            tasks = listOf(
                SubagentDispatcher.SubagentTaskSpec("explorer", "task A-3"),
                SubagentDispatcher.SubagentTaskSpec("general", "task B-3")
            ),
            concurrency = 2
        )
        assertEquals(2, result3.size)
        assertEquals("result-alpha", result3[0].finalContent)
        assertEquals("result-parent", result3[1].finalContent)
        assertEquals("deepseek-v4-pro", alphaClient.requests.last().model)
        assertEquals("parent-model-gpt", parentClient.requests.last().model)
    }

    @Test
    fun `full role overrides succeed even with broken default provider but role bad provider throws explicitly`() = runBlocking {
        val providerAlpha = ModelProviderProfile(
            id = "prov-alpha",
            name = "Alpha",
            baseUrl = "https://api.alpha.com/v1",
            apiKey = "alpha-key"
        )

        val explorerProfile = SubagentProfile(
            id = "explorer",
            displayName = "探索者",
            systemPrompt = "探索",
            providerProfileId = "prov-alpha",
            modelId = "deepseek-v4-pro"
        )
        val badRoleProfile = SubagentProfile(
            id = "bad-role",
            displayName = "错误角色",
            systemPrompt = "错误",
            providerProfileId = "broken-role-provider",
            modelId = "bad-model"
        )

        // 默认子模型指向一个不存在/无效的 Provider
        val config = XiaowanSubagentConfig(
            enabled = true,
            providerProfileId = "broken-default-provider",
            modelId = "broken-default-model",
            profiles = listOf(SubagentProfileRegistry.general, explorerProfile, badRoleProfile)
        )

        val alphaClient = RecordingTurnLlmClient("alpha-ok")
        val dispatcher = SubagentDispatcher(
            llmClient = FakeLlmClient(),
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "parent-model",
            config = config,
            providerProfileResolver = { id ->
                if (id == "prov-alpha") providerAlpha else null
            },
            subagentLlmClientFactory = { _, _ -> alphaClient },
            subagentImagePolicyResolver = { _, _ -> AgentToolImageContinuationPolicy.DEFAULT }
        )

        val env = FakeExecutionEnvironment()

        // 1. 全部任务均有有效 role override：无效的默认 Provider 不得阻断执行
        val overrideResults = dispatcher.dispatch(
            parentEnv = env,
            tasks = listOf(SubagentDispatcher.SubagentTaskSpec("explorer", "explore task")),
            concurrency = 1
        )
        assertEquals(1, overrideResults.size)
        assertEquals("completed", overrideResults.single().status)
        assertEquals("alpha-ok", overrideResults.single().finalContent)
        assertEquals("deepseek-v4-pro", alphaClient.requests.last().model)

        // 2. 角色自身配置了无效 Provider：显式报错 IllegalStateException，绝不静默 fallback
        val badRoleRun = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("bad-role", "bad role task")),
                concurrency = 1
            )
        }
        assertTrue(badRoleRun.isFailure)
        val badRoleException = badRoleRun.exceptionOrNull()
        assertTrue(badRoleException is IllegalStateException)
        assertTrue(badRoleException?.message?.contains("broken-role-provider") == true)

        // 3. 混合批次中若有任务依赖默认子模型（如 general 未覆盖），则在 admission 校验阶段阻断全部子任务
        val mixedRun = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(
                    SubagentDispatcher.SubagentTaskSpec("explorer", "valid task"),
                    SubagentDispatcher.SubagentTaskSpec("general", "needs broken default")
                ),
                concurrency = 2
            )
        }
        assertTrue(mixedRun.isFailure)
        val mixedException = mixedRun.exceptionOrNull()
        assertTrue(mixedException is IllegalStateException)
        assertTrue(mixedException?.message?.contains("broken-default-provider") == true)
    }

    private class FakeExecutionEnvironment(
        override val runtimeContextRepository: AgentRuntimeContextRepository = mock(AgentRuntimeContextRepository::class.java),
        override val workspaceDescriptor: AgentWorkspaceDescriptor = mock(AgentWorkspaceDescriptor::class.java),
        override val workspaceManager: AgentWorkspaceManager = mock(AgentWorkspaceManager::class.java),
        override val workspaceMemoryService: WorkspaceMemoryService = mock(WorkspaceMemoryService::class.java),
        override val modelProviderProfileId: String = "test-provider",
        override val reasoningEffort: String = "medium"
    ) : AgentExecutionEnvironment {
        override val agentRunId: String = "test-run"
        override val conversationId: Long = 12345L
        override val acpSessionId: String = "acp-session-1"
        override val acpTurnId: String = "acp-turn-1"
        override val userMessage: String = "test"
        override val resolvedSkills: List<ResolvedSkillContext> = emptyList()
        override val conversationMode: String = AgentConversationModePolicy.AGENT_MODE
        override val terminalEnvironment: Map<String, String> = emptyMap()
        override val runControl: AgentRunControl = NoOpAgentRunControl
        override val permissionRequester: AgentPermissionRequester? = null
        override val turnJob = null
        override val longTermMemoryIndex = null
    }

    private class RecordingTurnLlmClient(
        private val fixedResponse: String = "subagent execution done"
    ) : AgentLlmClient {
        val requests = mutableListOf<ChatCompletionRequest>()

        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
            allowTransportRetry: Boolean,
        ): ChatCompletionTurn {
            requests.add(request)
            onContentUpdate?.invoke(fixedResponse)
            return ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive(fixedResponse)
                )
            )
        }
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
