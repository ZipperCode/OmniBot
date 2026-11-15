package cn.com.omnimind.bot.agent

import android.app.Application
import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.SubagentConfigToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SubagentToolHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SubagentConfigurationJourneyTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `同一父回合创建角色后立即执行且重新计算工具预算`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("subagent_configuration_journey", Context.MODE_PRIVATE)
        val store = XiaowanSubagentConfigStore(context, preferences)
        val originalConfig = store.read()
        val registry = AgentToolRegistry(context, subagentConfig = originalConfig)
        val helper = SharedHelper(context, json)
        val callback = RecordingCallback()
        val capturedEnvironments = mutableListOf<Pair<String?, String?>>()
        val parentRequests = mutableListOf<ChatCompletionRequest>()
        val childRequests = mutableListOf<ChatCompletionRequest>()
        val budgetObservations = mutableListOf<Pair<Int?, Int>>()
        val rolePrompt = "只返回 CHILD_PROFILE_OK。" + "专注于独立规划。".repeat(100)
        var parentRound = 0
        val llm = object : AgentLlmClient {
            override suspend fun streamTurn(
                request: ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
                onContentUpdate: (suspend (String) -> Unit)?,
                onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
                allowTransportRetry: Boolean,
            ): ChatCompletionTurn {
                if (request.messages.firstOrNull()?.content == JsonPrimitive(rolePrompt)) {
                    childRequests += request
                    assertTrue(request.tools.orEmpty().isEmpty())
                    assertEquals("验证新角色", request.messages.last().content?.jsonPrimitive?.content)
                    onContentUpdate?.invoke("CHILD_PROFILE_OK")
                    return ChatCompletionTurn(ChatCompletionMessage(role = "assistant", content = JsonPrimitive("CHILD_PROFILE_OK")))
                }
                parentRequests += request
                parentRound += 1
                val arguments = when (parentRound) {
                    1 -> """{"action":"list"}"""
                    2 -> {
                        val payload = lastToolResult(request)
                        assertEquals(0L, payload["revision"]?.jsonPrimitive?.content?.toLong())
                        val promptJson = json.encodeToString(JsonPrimitive(rolePrompt))
                        """{"action":"create","profileId":"journey-planner","expectedRevision":0,"profile":{"displayName":"旅程规划员","systemPrompt":$promptJson,"baseProfileId":"planner","allowedTools":[]}}"""
                    }
                    3 -> {
                        val dispatch = request.tools.orEmpty().single { it.function.name == "subagent_dispatch" }
                        val ids = dispatch.function.parameters.jsonObject["properties"]!!.jsonObject["defaultProfileId"]!!.jsonObject["enum"]!!.jsonArray
                        assertTrue(ids.any { it.jsonPrimitive.content == "journey-planner" })
                        assertEquals(1L, lastToolResult(request)["revision"]?.jsonPrimitive?.content?.toLong())
                        """{"defaultProfileId":"journey-planner","tasks":[{"profileId":"journey-planner","instruction":"验证新角色"}],"concurrency":1}"""
                    }
                    4 -> {
                        val child = lastToolResult(request)["results"]!!.jsonArray.single().jsonObject
                        assertEquals("completed", child["status"]?.jsonPrimitive?.content)
                        assertEquals("journey-planner", child["profileId"]?.jsonPrimitive?.content)
                        assertEquals("CHILD_PROFILE_OK", child["result"]?.jsonPrimitive?.content)
                        onContentUpdate?.invoke("父任务收到 CHILD_PROFILE_OK")
                        return ChatCompletionTurn(ChatCompletionMessage(role = "assistant", content = JsonPrimitive("父任务收到 CHILD_PROFILE_OK")))
                    }
                    else -> error("父模型出现意外重复轮次")
                }
                val call = AssistantToolCall(
                    id = "parent-call-$parentRound",
                    function = AssistantToolCallFunction(
                        if (parentRound == 3) "subagent_dispatch" else "subagent_manage",
                        arguments,
                    ),
                )
                onToolCallInput?.invoke(call)
                return ChatCompletionTurn(
                    message = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call)),
                    finishReason = "tool_calls",
                    usage = ChatCompletionUsage(promptTokens = 7, completionTokens = 1, totalTokens = 8),
                )
            }
        }
        lateinit var executor: AgentToolExecutor
        val dispatcher = SubagentDispatcher(
            llmClient = llm,
            toolExecutorProvider = { executor },
            parentCatalogProvider = { registry },
            eventAdapter = AgentEventAdapter(json),
            model = "fixture-model",
            configProvider = store::read,
        )
        val manage = SubagentConfigToolHandler(helper, store, context, registry::refreshSubagentTools)
        val dispatch = SubagentToolHandler(helper, dispatcher)
        executor = object : AgentToolExecutor {
            override suspend fun execute(
                toolCall: AssistantToolCall,
                args: JsonObject,
                runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
                env: AgentExecutionEnvironment,
                callback: AgentCallback,
                toolHandle: AgentToolExecutionHandle,
            ): ToolExecutionResult {
                capturedEnvironments += env.acpSessionId to env.acpTurnId
                val handler = if (toolCall.function.name == "subagent_manage") manage else dispatch
                return handler.execute(toolCall, args, runtimeDescriptor, env, callback, toolHandle)
            }
        }
        val compactor = object : AgentContextCompactionController {
            override suspend fun resolvePromptTokenThreshold(conversationId: Long?) = Int.MAX_VALUE
            override suspend fun compactIfNeeded(
                conversationId: Long?, conversationMode: String, promptTokens: Int?,
                messages: List<ChatCompletionMessage>, contextTokens: Int?,
                promptTokenThresholdOverride: Int?, callback: AgentCallback?,
                requestOverheadTokens: Int, force: Boolean,
            ): List<ChatCompletionMessage> {
                budgetObservations += contextTokens to requestOverheadTokens
                return messages
            }
        }
        val environment = DefaultAgentExecutionEnvironment(
            agentRunId = "parent-run",
            acpSessionId = "parent-session",
            acpTurnId = "parent-turn",
            userMessage = "创建规划子代理并立即分派验证任务",
            runtimeContextRepository = mock(AgentRuntimeContextRepository::class.java),
            workspaceDescriptor = mock(AgentWorkspaceDescriptor::class.java),
            workspaceManager = mock(AgentWorkspaceManager::class.java),
            workspaceMemoryService = mock(WorkspaceMemoryService::class.java),
            resolvedSkills = emptyList(),
            conversationMode = AgentConversationModePolicy.AGENT_MODE,
        )
        val result = AgentOrchestrator(
            llmClient = llm,
            toolRegistry = registry,
            toolRouter = executor,
            eventAdapter = AgentEventAdapter(json),
            model = "fixture-model",
        ).run(AgentOrchestrator.Input(
            callback = callback,
            initialMessages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive(environment.userMessage))),
            executionEnv = environment,
            contextCompactor = compactor,
        ))

        assertTrue(result.toString(), result is AgentResult.Success)
        assertEquals("父任务收到 CHILD_PROFILE_OK", (result as AgentResult.Success).response.content)
        assertEquals(listOf("subagent_manage", "subagent_manage", "subagent_dispatch"), callback.results.map { it.first })
        assertTrue(callback.results.all { (it.second as? ToolExecutionResult.ContextResult)?.success == true })
        assertEquals(List(3) { "parent-session" to "parent-turn" }, capturedEnvironments)
        assertEquals(1, childRequests.size)
        assertEquals(4, parentRequests.size)
        assertEquals(originalConfig.profiles, store.read().profiles.filter { it.id != "journey-planner" })
        assertEquals(rolePrompt, store.read().requireProfile("journey-planner").systemPrompt)
        assertEquals(1L, store.readPayload()["revision"])
        val postCreateBudget = budgetObservations[2]
        assertEquals(AgentContextBudget.estimate(parentRequests[2].messages, toolTokens = postCreateBudget.second), postCreateBudget.first)
        val childCatalog = inheritedSubagentCatalog(registry, store.read().requireProfile("journey-planner"))
        assertFalse(runCatching { childCatalog.validateArguments("subagent_manage", JsonObject(emptyMap())) }.isSuccess)
    }

    private fun lastToolResult(request: ChatCompletionRequest): JsonObject {
        val text = request.messages.last { it.role == "tool" }.content!!.jsonPrimitive.content
        val envelope = json.parseToJsonElement(text).jsonObject
        val raw = envelope["rawResultJson"]!!.jsonPrimitive.content
        return json.parseToJsonElement(raw).jsonObject
    }

    private class RecordingCallback : AgentCallback {
        val results = mutableListOf<Pair<String, ToolExecutionResult>>()
        override suspend fun onThinkingStart() = Unit
        override suspend fun onThinkingUpdate(thinking: String) = Unit
        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit
        override suspend fun onToolCallProgress(toolName: String, progress: String, extras: Map<String, Any?>) = Unit
        override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) { results += toolName to result }
        override suspend fun onChatMessage(message: String) = Unit
        override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit
        override suspend fun onComplete(result: AgentResult) = Unit
        override suspend fun onError(error: String) = Unit
        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }
}
