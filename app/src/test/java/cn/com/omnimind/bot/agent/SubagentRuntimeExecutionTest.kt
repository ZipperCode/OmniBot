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
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.bot.agent.runtime.SubagentResourceGate
import org.junit.After
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
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
    @After
    fun tearDown() {
        SubagentResourceGate.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
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
    fun `configProvider dynamically provides config across dispatches and reports error on provider failure`() = runBlocking {
        var currentConfig: XiaowanSubagentConfig = XiaowanSubagentConfig(enabled = false)
        var shouldThrow = false
        val llmClient = RecordingTurnLlmClient("done")

        val dispatcher = SubagentDispatcher(
            llmClient = llmClient,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig(enabled = true),
            configProvider = {
                if (shouldThrow) error("I/O error reading config")
                currentConfig
            }
        )

        val env = FakeExecutionEnvironment()

        // 1. 初始为 disabled -> 失败抛出子代理功能未启用
        val result1 = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("general", "task 1")),
                concurrency = 1
            )
        }
        assertTrue(result1.isFailure)
        assertTrue(result1.exceptionOrNull()?.message?.contains("子代理功能未启用") == true)

        // 2. 动态启用并新增自定义角色 -> 下一次 dispatch 立即生效
        val customProfile = SubagentProfile(
            id = "dynamic-worker",
            displayName = "动态角色",
            systemPrompt = "sys",
            baseProfileId = "general",
            enabled = true
        )
        currentConfig = XiaowanSubagentConfig(
            enabled = true,
            profiles = SubagentProfileRegistry.all() + customProfile
        )

        val result2 = dispatcher.dispatch(
            parentEnv = env,
            tasks = listOf(SubagentDispatcher.SubagentTaskSpec("dynamic-worker", "task 2")),
            concurrency = 1
        )
        assertEquals(1, result2.size)
        assertEquals("completed", result2[0].status)
        assertEquals("dynamic-worker", result2[0].profileId)

        // 3. configProvider 读取抛出异常 -> 显式抛出配置读取失败
        shouldThrow = true
        val result3 = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("dynamic-worker", "task 3")),
                concurrency = 1
            )
        }
        assertTrue(result3.isFailure)
        val msg = result3.exceptionOrNull()?.message.orEmpty()
        assertTrue(msg.contains("子代理配置读取失败"))
        assertTrue(msg.contains("I/O error reading config"))
    }

    @Test
    fun `running dispatch captures config snapshot unaffected by subsequent config changes`() = runBlocking {
        var currentConfig = XiaowanSubagentConfig(
            enabled = true,
            profiles = SubagentProfileRegistry.all(),
            reasoningEffort = "low"
        )
        val slowLlm = object : AgentLlmClient {
            val observedEfforts = mutableListOf<String?>()
            override suspend fun streamTurn(
                request: ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
                onContentUpdate: (suspend (String) -> Unit)?,
                onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
                allowTransportRetry: Boolean
            ): ChatCompletionTurn {
                observedEfforts.add(request.reasoningEffort)
                // 模拟子任务执行中途外部修改了配置
                currentConfig = XiaowanSubagentConfig(
                    enabled = false,
                    reasoningEffort = "high"
                )
                onContentUpdate?.invoke("subagent completed")
                return ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("subagent completed")
                    )
                )
            }
        }

        val dispatcher = SubagentDispatcher(
            llmClient = slowLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            configProvider = { currentConfig }
        )

        val env = FakeExecutionEnvironment()
        val results = dispatcher.dispatch(
            parentEnv = env,
            tasks = listOf(
                SubagentDispatcher.SubagentTaskSpec("general", "task 1"),
                SubagentDispatcher.SubagentTaskSpec("general", "task 2")
            ),
            concurrency = 1
        )

        assertEquals(2, results.size)
        // 两次调用均捕获了启动时的 reasoningEffort = "low"，未被中途修改的 "high" 干扰
        assertEquals(listOf("low", "low"), slowLlm.observedEfforts)

        // 但下一次 dispatch 时，读取已修改的 currentConfig（enabled = false），会被拦截
        val nextResult = runCatching {
            dispatcher.dispatch(
                parentEnv = env,
                tasks = listOf(SubagentDispatcher.SubagentTaskSpec("general", "task 3")),
                concurrency = 1
            )
        }
        assertTrue(nextResult.isFailure)
        assertTrue(nextResult.exceptionOrNull()?.message?.contains("子代理功能未启用") == true)
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

    @Test
    fun `subagent tool execution captures custom child modelId and inherited parent modelId`() = runBlocking {
        val providerCustom = ModelProviderProfile(
            id = "prov-custom",
            name = "Custom Provider",
            baseUrl = "https://api.custom.invalid/v1",
            apiKey = "custom-key"
        )
        val providers = mapOf("prov-custom" to providerCustom)

        val customProfile = SubagentProfile(
            id = "custom-worker",
            displayName = "Custom Worker",
            systemPrompt = "Custom worker prompt",
            providerProfileId = "prov-custom",
            modelId = "custom-child-model"
        )

        val config = XiaowanSubagentConfig(
            enabled = true,
            providerProfileId = null,
            modelId = null,
            profiles = listOf(SubagentProfileRegistry.general, customProfile)
        )

        val toolExecutor = CapturingToolExecutor()
        val customClient = TwoTurnToolLlmClient()
        val parentClient = TwoTurnToolLlmClient()

        val catalog = object : AgentToolCatalog {
            override val toolsForModel: List<cn.com.omnimind.baselib.llm.ChatCompletionTool> = listOf(
                cn.com.omnimind.baselib.llm.ChatCompletionTool(
                    type = "function",
                    function = cn.com.omnimind.baselib.llm.ChatCompletionFunction(
                        name = "test_tool",
                        description = "Test tool"
                    )
                )
            )
            override fun runtimeDescriptor(toolName: String) =
                AgentToolRegistry.RuntimeToolDescriptor(toolName, toolName, "builtin")
            override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
        }

        val dispatcher = SubagentDispatcher(
            llmClient = parentClient,
            toolExecutorProvider = { toolExecutor },
            parentCatalogProvider = { catalog },
            eventAdapter = AgentEventAdapter(json),
            model = "scene.dispatch.model",
            config = config,
            providerProfileResolver = { providers[it] },
            subagentLlmClientFactory = { _, _ -> customClient },
            subagentImagePolicyResolver = { _, _ -> AgentToolImageContinuationPolicy.DEFAULT }
        )

        val parentEnv = FakeExecutionEnvironment(
            modelProviderProfileId = "prov-parent",
            modelId = "parent-model-real"
        )

        val results = dispatcher.dispatch(
            parentEnv = parentEnv,
            tasks = listOf(
                SubagentDispatcher.SubagentTaskSpec("custom-worker", "task for custom worker"),
                SubagentDispatcher.SubagentTaskSpec("general", "task for general worker")
            ),
            concurrency = 1
        )

        assertEquals(2, results.size)
        assertEquals("completed", results[0].status)
        assertEquals("completed", results[1].status)

        assertEquals(2, toolExecutor.capturedEnvs.size)

        val customEnv = toolExecutor.capturedEnvs[0]
        assertEquals("prov-custom", customEnv.modelProviderProfileId)
        assertEquals("custom-child-model", customEnv.modelId)

        val inheritedEnv = toolExecutor.capturedEnvs[1]
        assertEquals("prov-parent", inheritedEnv.modelProviderProfileId)
        assertEquals("parent-model-real", inheritedEnv.modelId)
        assertNotEquals("scene.dispatch.model", inheritedEnv.modelId)
    }
    @Test
    fun `6 read tasks with default concurrency run with peak concurrency at most 2`() = runBlocking {
        val trackingLlm = TrackingLlmClient(durationMs = 50L)
        val dispatcher = SubagentDispatcher(
            llmClient = trackingLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        val tasks = (1..6).map { i ->
            SubagentDispatcher.SubagentTaskSpec("planner", "read-task-$i")
        }

        val results = dispatcher.dispatch(
            parentEnv = FakeExecutionEnvironment(),
            tasks = tasks,
            concurrency = 2
        )

        assertEquals(6, results.size)
        assertTrue(results.all { it.status == "completed" })
        assertTrue("Peak concurrency must be <= 2, was ${trackingLlm.maxActiveCount.get()}",
            trackingLlm.maxActiveCount.get() <= 2)
    }

    @Test
    fun `6 read tasks with explicit concurrency 4 run with peak concurrency at most 4`() = runBlocking {
        val trackingLlm = TrackingLlmClient(durationMs = 60L)
        val dispatcher = SubagentDispatcher(
            llmClient = trackingLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        val tasks = (1..6).map { i ->
            SubagentDispatcher.SubagentTaskSpec("planner", "read-task-$i")
        }

        val results = dispatcher.dispatch(
            parentEnv = FakeExecutionEnvironment(),
            tasks = tasks,
            concurrency = 4
        )

        assertEquals(6, results.size)
        assertTrue(results.all { it.status == "completed" })
        assertTrue("Peak concurrency must be <= 4, was ${trackingLlm.maxActiveCount.get()}",
            trackingLlm.maxActiveCount.get() <= 4)
        assertTrue("Should achieve concurrency > 1, was ${trackingLlm.maxActiveCount.get()}",
            trackingLlm.maxActiveCount.get() >= 2)
    }

    @Test
    fun `write task executes with exclusive workspace lease across entire run lifecycle in FIFO order`() = runBlocking {
        val trackingLlm = TrackingLlmClient(durationMs = 40L)
        val catalog = CustomCatalog(listOf("file_read", "file_write"))
        val dispatcher = SubagentDispatcher(
            llmClient = trackingLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { catalog },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        // planner = read-only, general (with file_write) = write task
        val tasks = listOf(
            SubagentDispatcher.SubagentTaskSpec("planner", "read-task-1"),
            SubagentDispatcher.SubagentTaskSpec("general", "write-task-2"),
            SubagentDispatcher.SubagentTaskSpec("planner", "read-task-3")
        )

        val results = dispatcher.dispatch(
            parentEnv = FakeExecutionEnvironment(),
            tasks = tasks,
            concurrency = 2
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.status == "completed" })

        val log = trackingLlm.executionLog
        val r1End = log.indexOfFirst { it.startsWith("end:") && it.contains("read-task-1") }
        val w2Start = log.indexOfFirst { it.startsWith("start:") && it.contains("write-task-2") }
        val w2End = log.indexOfFirst { it.startsWith("end:") && it.contains("write-task-2") }
        val r3Start = log.indexOfFirst { it.startsWith("start:") && it.contains("read-task-3") }

        assertTrue("Read-1 must end before Write-2 starts in FIFO", w2Start > r1End)
        assertTrue("Write-2 must end before Read-3 starts in FIFO", r3Start > w2End)
    }

    @Test
    fun `task with browser_use or unknown tool is treated as write task`() = runBlocking {
        val catalog = CustomCatalog(listOf("file_read", "browser_use", "mcp__unknown_tool"))
        val trackingLlm = TrackingLlmClient(durationMs = 40L)
        val dispatcher = SubagentDispatcher(
            llmClient = trackingLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { catalog },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        // browser_use has side effects (navigate/scroll) -> exclusive write lease
        val tasks = listOf(
            SubagentDispatcher.SubagentTaskSpec("general", "browser-task"),
            SubagentDispatcher.SubagentTaskSpec("planner", "read-task")
        )

        val results = dispatcher.dispatch(
            parentEnv = FakeExecutionEnvironment(),
            tasks = tasks,
            concurrency = 2
        )

        assertEquals(2, results.size)
        val log = trackingLlm.executionLog
        val browserEnd = log.indexOfFirst { it.startsWith("end:") && it.contains("browser-task") }
        val readStart = log.indexOfFirst { it.startsWith("start:") && it.contains("read-task") }
        assertTrue("browser_use write task must finish before subsequent read starts", readStart > browserEnd)
    }

    @Test
    fun `cancelled waiting tasks do not run and release resources`() = runBlocking {
        val blockSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val task2Started = AtomicBoolean(false)

        val blockingLlm = object : AgentLlmClient {
            override suspend fun streamTurn(
                request: ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
                onContentUpdate: (suspend (String) -> Unit)?,
                onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
                allowTransportRetry: Boolean
            ): ChatCompletionTurn {
                val msg = request.messages.lastOrNull()?.content?.toString().orEmpty()
                if (msg.contains("task-1")) {
                    blockSignal.await()
                } else if (msg.contains("task-2")) {
                    task2Started.set(true)
                }
                onContentUpdate?.invoke("ok")
                return ChatCompletionTurn(message = ChatCompletionMessage(role = "assistant", content = JsonPrimitive("ok")))
            }
        }

        val dispatcher = SubagentDispatcher(
            llmClient = blockingLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { FakeCatalog() },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        val job = launch {
            dispatcher.dispatch(
                parentEnv = FakeExecutionEnvironment(),
                tasks = listOf(
                    SubagentDispatcher.SubagentTaskSpec("general", "task-1"),
                    SubagentDispatcher.SubagentTaskSpec("general", "task-2")
                ),
                concurrency = 1
            )
        }

        delay(30L) // Task-1 is blocked at blockSignal, Task-2 is waiting
        job.cancel() // Cancel the parent job
        blockSignal.complete(Unit)
        job.join()

        assertFalse("Waiting Task-2 must not have run after cancellation", task2Started.get())
        assertFalse("Resource gate must not have lingering locks after cancellation", SubagentResourceGate.isHeld(""))
    }

    @Test
    fun `different workspaces run in parallel without mutual blocking`() = runBlocking {
        val trackingLlm = TrackingLlmClient(durationMs = 60L)
        val catalog = CustomCatalog(listOf("file_write"))
        val dispatcher = SubagentDispatcher(
            llmClient = trackingLlm,
            toolExecutorProvider = { FakeToolExecutor() },
            parentCatalogProvider = { catalog },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        val wsDescriptorA = mock(AgentWorkspaceDescriptor::class.java).apply {
            org.mockito.Mockito.`when`(rootPath).thenReturn("/workspace")
            org.mockito.Mockito.`when`(androidRootPath).thenReturn("build/tmp/parallel-ws-a")
        }
        val wsDescriptorB = mock(AgentWorkspaceDescriptor::class.java).apply {
            org.mockito.Mockito.`when`(rootPath).thenReturn("/workspace")
            org.mockito.Mockito.`when`(androidRootPath).thenReturn("build/tmp/parallel-ws-b")
        }

        val envA = FakeExecutionEnvironment(workspaceDescriptor = wsDescriptorA)
        val envB = FakeExecutionEnvironment(workspaceDescriptor = wsDescriptorB)

        val jobA = async {
            dispatcher.dispatch(envA, listOf(SubagentDispatcher.SubagentTaskSpec("general", "write-ws-a")), 1)
        }
        val jobB = async {
            dispatcher.dispatch(envB, listOf(SubagentDispatcher.SubagentTaskSpec("general", "write-ws-b")), 1)
        }

        val resultsA = jobA.await()
        val resultsB = jobB.await()

        assertEquals(1, resultsA.size)
        assertEquals(1, resultsB.size)
        assertEquals("Different workspaces should execute write tasks in parallel",
            2, trackingLlm.maxActiveCount.get())
    }

    @Test
    fun `subagent acquires workspace lease before tool acquires display lease adhering to strict lock hierarchy`() = runBlocking {
        val leaseOrderLog = java.util.concurrent.CopyOnWriteArrayList<String>()

        val customToolExecutor = object : AgentToolExecutor {
            override suspend fun execute(
                toolCall: AssistantToolCall,
                args: JsonObject,
                runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
                env: AgentExecutionEnvironment,
                callback: AgentCallback,
                toolHandle: AgentToolExecutionHandle
            ): ToolExecutionResult {
                // 工具执行阶段，工作区租约已被子代理持有
                leaseOrderLog.add("workspace_held:${SubagentResourceGate.isHeld("")}")
                return DisplayInputCoordinator.withDisplayLease(0, "vlm-run") {
                    leaseOrderLog.add("display_held:${DisplayInputCoordinator.isLeaseHeld(0)}")
                    ToolExecutionResult.ContextResult(
                        toolName = toolCall.function.name,
                        summaryText = "ok",
                        previewJson = "{}",
                        rawResultJson = "{}",
                        success = true
                    )
                }
            }
        }

        val customLlm = TwoTurnToolLlmClient("vlm_tool")
        val catalog = CustomCatalog(listOf("vlm_tool"))

        val dispatcher = SubagentDispatcher(
            llmClient = customLlm,
            toolExecutorProvider = { customToolExecutor },
            parentCatalogProvider = { catalog },
            eventAdapter = AgentEventAdapter(json),
            model = "test-model",
            config = XiaowanSubagentConfig()
        )

        val results = dispatcher.dispatch(
            parentEnv = FakeExecutionEnvironment(),
            tasks = listOf(SubagentDispatcher.SubagentTaskSpec("general", "task with vlm")),
            concurrency = 1
        )

        assertEquals(1, results.size)
        assertEquals("completed", results[0].status)
        assertTrue(leaseOrderLog.contains("workspace_held:true"))
        assertTrue(leaseOrderLog.contains("display_held:true"))
    }

    private class FakeExecutionEnvironment(
        override val runtimeContextRepository: AgentRuntimeContextRepository = mock(AgentRuntimeContextRepository::class.java),
        override val workspaceDescriptor: AgentWorkspaceDescriptor = mock(AgentWorkspaceDescriptor::class.java),
        override val workspaceManager: AgentWorkspaceManager = mock(AgentWorkspaceManager::class.java),
        override val workspaceMemoryService: WorkspaceMemoryService = mock(WorkspaceMemoryService::class.java),
        override val modelProviderProfileId: String = "test-provider",
        override val modelId: String? = null,
        override val reasoningEffort: String = "medium",
        override val acpSessionId: String = "acp-session-1",
        override val agentRunId: String = "test-run"
    ) : AgentExecutionEnvironment {
        override val conversationId: Long = 12345L
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

    private class CapturingToolExecutor : AgentToolExecutor {
        val capturedEnvs = mutableListOf<AgentExecutionEnvironment>()

        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            capturedEnvs.add(env)
            return ToolExecutionResult.ContextResult(
                toolName = toolCall.function.name,
                summaryText = "ok",
                previewJson = "{}",
                rawResultJson = "{}",
                success = true
            )
        }
    }

    private class TwoTurnToolLlmClient(
        private val toolName: String = "test_tool"
    ) : AgentLlmClient {
        private var turn = 0

        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
            allowTransportRetry: Boolean,
        ): ChatCompletionTurn {
            turn++
            return if (turn == 1) {
                val call = AssistantToolCall(
                    id = "call-$turn",
                    type = "function",
                    function = AssistantToolCallFunction(
                        name = toolName,
                        arguments = "{}"
                    )
                )
                onToolCallInput?.invoke(call)
                ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        toolCalls = listOf(call)
                    )
                )
            } else {
                onContentUpdate?.invoke("done")
                ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = JsonPrimitive("done")
                    )
                )
            }
        }
    }

    private class TrackingLlmClient(
        private val durationMs: Long = 40L,
        private val onRunStart: ((String) -> Unit)? = null
    ) : AgentLlmClient {
        val activeCount = AtomicInteger(0)
        val maxActiveCount = AtomicInteger(0)
        val executionLog = CopyOnWriteArrayList<String>()

        override suspend fun streamTurn(
            request: ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
            allowTransportRetry: Boolean
        ): ChatCompletionTurn {
            val prompt = request.messages.lastOrNull()?.content?.toString().orEmpty()
            onRunStart?.invoke(prompt)
            val active = activeCount.incrementAndGet()
            maxActiveCount.updateAndGet { current -> maxOf(current, active) }
            executionLog.add("start:$prompt")
            try {
                if (durationMs > 0) {
                    delay(durationMs)
                }
            } finally {
                activeCount.decrementAndGet()
                executionLog.add("end:$prompt")
            }
            onContentUpdate?.invoke("completed: $prompt")
            return ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = JsonPrimitive("completed: $prompt")
                )
            )
        }
    }

    private class CustomCatalog(
        private val toolNames: List<String>
    ) : AgentToolCatalog {
        override val toolsForModel: List<cn.com.omnimind.baselib.llm.ChatCompletionTool> = toolNames.map { name ->
            cn.com.omnimind.baselib.llm.ChatCompletionTool(
                type = "function",
                function = cn.com.omnimind.baselib.llm.ChatCompletionFunction(
                    name = name,
                    description = "Tool $name",
                    parameters = buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {}
                    }
                )
            )
        }

        override fun runtimeDescriptor(toolName: String) =
            AgentToolRegistry.RuntimeToolDescriptor(toolName, toolName, if (toolName == "browser_use") "browser" else "workspace")

        override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
    }
}
