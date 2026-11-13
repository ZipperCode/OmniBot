package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OmniFlowModelHostTest {
    @Test
    fun `json completion accepts object content when provider omits tool call`() = runBlocking {
        val host = OmniFlowModelHost(
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): OmniFlowModelTurn = OmniFlowModelTurn(
                    turn = ChatCompletionTurn(
                        message = ChatCompletionMessage(
                            role = "assistant",
                            content = JsonPrimitive("```json\n{\"parameters\":[]}\n```")
                        ),
                        finishReason = "stop",
                    ),
                )
            },
        )

        assertEquals(
            "{\"parameters\":[]}",
            host.completeJson(mapOf("prompt" to "Enhance this Function"))["content"],
        )
    }

    @Test
    fun `json completion uses streamed native submit json tool call`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        val host = OmniFlowModelHost(
            modelClient = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): OmniFlowModelTurn {
                    receivedRequest = request
                    return OmniFlowModelTurn(
                        turn = ChatCompletionTurn(
                            message = ChatCompletionMessage(
                                role = "assistant",
                                toolCalls = listOf(
                                    AssistantToolCall(
                                        id = "call-submit-json",
                                        function = AssistantToolCallFunction(
                                            name = "submit_json",
                                            arguments = """{"parameters":[]}""",
                                        ),
                                    ),
                                ),
                            ),
                            finishReason = "tool_calls",
                        ),
                    )
                }
            },
        )
        val result = host.completeJson(
            payload = mapOf(
                "prompt" to "Enhance this Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
            modelOverride = OmniVlmPlugin.MODEL_SCENE,
        )

        assertEquals("""{"parameters":[]}""", result["content"])
        assertEquals(OmniVlmPlugin.MODEL_SCENE, receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals("required", receivedRequest?.toolChoice?.jsonPrimitive?.content)
        assertEquals("submit_json", receivedRequest?.tools?.single()?.function?.name)
    }

    @Test
    fun `json completion can use the bound VLM scene through the platform parser`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        OmniFlow.configure(
            object : OmniFlowPlatform {
                override suspend fun startProcess(
                    context: Context,
                    command: String,
                    environment: Map<String, String>,
                ): Process = error("process_not_expected")

                override suspend fun ensurePython(context: Context, expectedVersion: String) = Unit

                override suspend fun resolveRuntimeSkill(
                    context: Context,
                    refresh: Boolean,
                ): OmniFlowSkillLocation = OmniFlowSkillLocation(File("."), "/workspace", "test")

                override suspend fun bootstrapRuntimeSkill(
                    context: Context,
                    location: OmniFlowSkillLocation,
                ): OmniFlowSkillLocation = location

                override suspend fun reclaimRuntimeSkill(context: Context) = Unit

                override suspend fun completeJson(request: ChatCompletionRequest): String {
                    receivedRequest = request
                    return """{"parameters":[]}"""
                }
            },
        )

        val result = OmniFlowModelHost.completeJson(
            payload = mapOf(
                "model" to "scene.dispatch.model",
                "prompt" to "Enhance this Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
            modelOverride = OmniVlmPlugin.MODEL_SCENE,
        )

        assertEquals("""{"parameters":[]}""", result["content"])
        assertEquals(OmniVlmPlugin.MODEL_SCENE, receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals("required", receivedRequest?.toolChoice?.jsonPrimitive?.content)
        assertEquals("submit_json", receivedRequest?.tools?.single()?.function?.name)
        assertEquals(null, receivedRequest?.responseFormat)
    }

    @Test
    fun `run metrics include terminal model turn tokens and latency`() {
        val metrics = ModelRunLogMetrics()
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "gpt-5.6-sol",
                "usage" to mapOf(
                    "prompt_tokens" to 100,
                    "completion_tokens" to 20,
                    "total_tokens" to 120,
                ),
            ),
            durationMs = 900,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "gpt-5.6-sol",
                "usage" to mapOf(
                    "prompt_tokens" to 80,
                    "completion_tokens" to 10,
                    "total_tokens" to 90,
                ),
            ),
            durationMs = 600,
        )

        val diagnostics = metrics.diagnostics()
        val usage = diagnostics["token_usage"] as Map<*, *>
        assertEquals(2, usage["call_count"])
        assertEquals(180L, usage["prompt_tokens"])
        assertEquals(30L, usage["completion_tokens"])
        assertEquals(210L, usage["total_tokens"])
        assertEquals(1500L, diagnostics["model_duration_ms"])
        assertEquals(2, (diagnostics["token_usage_by_call"] as List<*>).size)
    }


    @Test
    fun `model turn preserves raw pixel schema and native tool call`() = runBlocking {
        val reasoningUpdates = mutableListOf<String>()
        val client = object : OmniFlowModelClient {
            override suspend fun streamTurn(
                request: ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
            ): OmniFlowModelTurn {
                assertEquals(OmniVlmPlugin.MODEL_SCENE, request.model)
                assertEquals("required", (request.toolChoice as JsonPrimitive).content)
                assertFalse(requireNotNull(request.parallelToolCalls))
                val click = request.tools.single().function
                assertEquals("click", click.name)
                assertEquals(
                    listOf("summary", "x", "y"),
                    click.parameters["required"]?.jsonArray?.map { it.jsonPrimitive.content },
                )
                val properties = click.parameters["properties"]?.jsonObject.orEmpty()
                assertEquals(0.0, properties["x"]?.jsonObject?.get("minimum")?.jsonPrimitive?.double)
                assertEquals(1080.0, properties["x"]?.jsonObject?.get("maximum")?.jsonPrimitive?.double)
                assertEquals(2400.0, properties["y"]?.jsonObject?.get("maximum")?.jsonPrimitive?.double)
                onReasoningUpdate?.invoke("search is visible")
                return OmniFlowModelTurn(
                    turn = ChatCompletionTurn(
                        message = ChatCompletionMessage(
                            role = "assistant",
                            toolCalls = listOf(
                                AssistantToolCall(
                                    id = "call-1",
                                    function = AssistantToolCallFunction(
                                        name = "click",
                                        arguments = """{"summary":"点击搜索","x":540,"y":1200}""",
                                    ),
                                ),
                            ),
                        ),
                        reasoning = "search is visible",
                        finishReason = "tool_calls",
                        resolvedModel = "Qwen3-VL-235B-A22B-Instruct",
                        usage = ChatCompletionUsage(
                            promptTokens = 20,
                            completionTokens = 5,
                            totalTokens = 25,
                            promptTokensDetails = buildJsonObject {
                                put("cached_tokens", JsonPrimitive(7))
                                put("image_tokens", JsonPrimitive(8))
                            },
                            completionTokensDetails = buildJsonObject {
                                put("reasoning_tokens", JsonPrimitive(3))
                                put("text_tokens", JsonPrimitive(2))
                            },
                        ),
                    ),
                    executionConfig = OmniFlowModelExecutionConfig(
                        providerProfileSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        resolvedModel = "Qwen3-VL-235B-A22B-Instruct",
                        routeSource = "gui_binding",
                        contextCapacity = 32768,
                        outputBudget = 2048,
                        maxCompletionTokens = 2048,
                        reasoningEffort = "none",
                        enableThinking = false,
                    ),
                )
            }
        }
        val host = OmniFlowModelHost(
            modelClient = client,
            onReasoningUpdate = reasoningUpdates::add,
        )

        val result = host.modelTurn(
            mapOf(
                "model" to OmniVlmPlugin.MODEL_SCENE,
                "request" to mapOf(
                    "model" to OmniVlmPlugin.MODEL_SCENE,
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to "tap search"),
                    ),
                    "tools" to listOf(
                        mapOf(
                            "type" to "function",
                            "function" to mapOf(
                                "name" to "click",
                                "description" to "Tap one point",
                                "strict" to true,
                                "parameters" to mapOf(
                                    "type" to "object",
                                    "additionalProperties" to false,
                                    "required" to listOf("summary", "x", "y"),
                                    "properties" to mapOf(
                                        "summary" to mapOf("type" to "string"),
                                        "x" to mapOf(
                                            "type" to "number",
                                            "minimum" to 0,
                                            "maximum" to 1080,
                                        ),
                                        "y" to mapOf(
                                            "type" to "number",
                                            "minimum" to 0,
                                            "maximum" to 2400,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    "tool_choice" to "required",
                    "parallel_tool_calls" to false,
                    "stream" to true,
                ),
            ),
        )

        assertEquals(listOf("search is visible"), reasoningUpdates)
        assertEquals(OmniVlmPlugin.MODEL_SCENE, result["requested_model"])
        assertEquals("Qwen3-VL-235B-A22B-Instruct", result["resolved_model"])
        val toolCall = (result["tool_calls"] as List<*>).single() as Map<*, *>
        val function = toolCall["function"] as Map<*, *>
        assertEquals("click", function["name"])
        assertEquals("""{"summary":"点击搜索","x":540,"y":1200}""", function["arguments"])
        val usage = result["usage"] as Map<*, *>
        assertEquals(25, usage["total_tokens"])
        assertEquals(3, usage["reasoning_tokens"])
        assertEquals(2, usage["text_tokens"])
        assertEquals(8, usage["image_tokens"])
        assertEquals(7, usage["cached_tokens"])
        val execConfig = result["execution_config"] as Map<*, *>
        assertEquals("omniflow.model-execution-config.v1", execConfig["schema_version"])
        assertEquals("Qwen3-VL-235B-A22B-Instruct", execConfig["resolved_model"])
        assertEquals("gui_binding", execConfig["route_source"])
        assertEquals(2048, execConfig["output_budget"])
        assertEquals(2048, execConfig["max_completion_tokens"])
        assertEquals("none", execConfig["reasoning_effort"])
        assertEquals(false, execConfig["enable_thinking"])
        val omitted = execConfig["omitted_fields"] as List<*>
        org.junit.Assert.assertTrue(omitted.contains("max_tokens"))
        assertFalse(omitted.contains("reasoning_effort"))
    }

    @Test
    fun `model turn does not retry stalled rejected action and calls streamTurn only once`() =
        runBlocking {
            val requests = mutableListOf<ChatCompletionRequest>()
            var callIndex = 0
            val client = object : OmniFlowModelClient {
                override suspend fun streamTurn(
                    request: ChatCompletionRequest,
                    onReasoningUpdate: (suspend (String) -> Unit)?,
                ): OmniFlowModelTurn {
                    requests += request
                    callIndex += 1
                    val arguments = if (callIndex == 1) {
                        """{"summary":"再次点保存","x":540,"y":2070}"""
                    } else {
                        """{"summary":"先选无需餐具","x":720,"y":1570}"""
                    }
                    return OmniFlowModelTurn(
                        turn = ChatCompletionTurn(
                            message = ChatCompletionMessage(
                                role = "assistant",
                                toolCalls = listOf(
                                    AssistantToolCall(
                                        id = "call-$callIndex",
                                        function = AssistantToolCallFunction(
                                            name = "click",
                                            arguments = arguments,
                                        ),
                                    ),
                                ),
                            ),
                            finishReason = "tool_calls",
                        ),
                    )
                }
            }
            val host = OmniFlowModelHost(modelClient = client)
            val result = host.modelTurn(
                mapOf(
                    "model" to OmniVlmPlugin.MODEL_SCENE,
                    "state" to mapOf(
                        "display" to mapOf("width" to 1080, "height" to 2376),
                        "extra" to mapOf(
                            "previous_action_error" to
                                "action_completed_without_state_change",
                            "previous_action" to mapOf(
                                "tool" to "click",
                                "args" to mapOf(
                                    "x" to 500.0,
                                    "y" to 871.6329966329967,
                                ),
                            ),
                        ),
                    ),
                    "request" to mapOf(
                        "model" to OmniVlmPlugin.MODEL_SCENE,
                        "messages" to listOf(
                            mapOf("role" to "user", "content" to "inspect dialog"),
                        ),
                        "tools" to emptyList<Map<String, Any?>>(),
                        "tool_choice" to "required",
                        "parallel_tool_calls" to false,
                        "stream" to true,
                    ),
                ),
            )

            assertEquals(1, requests.size)
            val toolCall = (result["tool_calls"] as List<*>).single() as Map<*, *>
            val function = toolCall["function"] as Map<*, *>
            assertEquals("click", function["name"])
            assertEquals("""{"summary":"再次点保存","x":540,"y":2070}""", function["arguments"])
            assertEquals(1, (result["usage"] as Map<*, *>)["model_calls"])
        }

    @Test
    fun `json completion crosses only the configured platform boundary`() = runBlocking {
        var receivedRequest: ChatCompletionRequest? = null
        OmniFlow.configure(
            object : OmniFlowPlatform {
                override suspend fun startProcess(
                    context: Context,
                    command: String,
                    environment: Map<String, String>,
                ): Process = error("process_not_expected")

                override suspend fun ensurePython(context: Context, expectedVersion: String) = Unit

                override suspend fun resolveRuntimeSkill(
                    context: Context,
                    refresh: Boolean,
                ): OmniFlowSkillLocation = OmniFlowSkillLocation(File("."), "/workspace", "test")

                override suspend fun bootstrapRuntimeSkill(
                    context: Context,
                    location: OmniFlowSkillLocation,
                ): OmniFlowSkillLocation = location

                override suspend fun reclaimRuntimeSkill(context: Context) = Unit

                override suspend fun completeJson(request: ChatCompletionRequest): String {
                    receivedRequest = request
                    return """{"function_id":"none"}"""
                }
            },
        )

        val result = OmniFlowModelHost.completeJson(
            mapOf(
                "model" to "scene.dispatch.model",
                "prompt" to "Select a Function",
                "max_tokens" to 321,
                "temperature" to 0.0,
            ),
        )

        assertEquals("""{"function_id":"none"}""", result["content"])
        assertEquals("scene.dispatch.model", receivedRequest?.model)
        assertEquals(321, receivedRequest?.maxCompletionTokens)
        assertEquals(0.0, receivedRequest?.temperature)
        assertEquals("required", receivedRequest?.toolChoice?.jsonPrimitive?.content)
        assertEquals("submit_json", receivedRequest?.tools?.single()?.function?.name)
        assertEquals(null, receivedRequest?.responseFormat)
    }

    @Test
    fun `model metrics records execution_config per call and top-level when all calls are consistent`() {
        val metrics = ModelRunLogMetrics()
        val config = linkedMapOf<String, Any?>(
            "schema_version" to "omniflow.model-execution-config.v1",
            "provider_profile_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "resolved_model" to "gpt-5.6-sol",
            "route_source" to "conversation",
            "context_capacity" to 65536,
            "output_budget" to 4096,
            "max_completion_tokens" to 4096,
            "max_tokens" to null,
            "reasoning_effort" to "low",
            "enable_thinking" to true,
            "thinking_type" to null,
            "reasoning_supported" to true,
            "reasoning_can_disable" to true,
            "supported_reasoning_levels" to listOf("low", "medium", "high"),
            "default_reasoning_level" to "low",
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "gpt-5.6-sol",
                "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 20, "total_tokens" to 120),
                "execution_config" to config,
            ),
            durationMs = 800,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "gpt-5.6-sol",
                "usage" to mapOf("prompt_tokens" to 80, "completion_tokens" to 10, "total_tokens" to 90),
                "execution_config" to config,
            ),
            durationMs = 400,
        )

        val diag = metrics.diagnostics()
        val calls = diag["token_usage_by_call"] as List<*>
        assertEquals(2, calls.size)
        val call1 = calls[0] as Map<*, *>
        val call2 = calls[1] as Map<*, *>
        assertEquals("gpt-5.6-sol", (call1["execution_config"] as Map<*, *>)["resolved_model"])
        assertEquals("gpt-5.6-sol", (call2["execution_config"] as Map<*, *>)["resolved_model"])

        val topConfig = diag["model_execution_config"] as Map<*, *>
        assertEquals("omniflow.model-execution-config.v1", topConfig["schema_version"])
        assertEquals("gpt-5.6-sol", topConfig["resolved_model"])
        assertEquals("conversation", topConfig["route_source"])
        assertEquals(4096, topConfig["output_budget"])
    }

    @Test
    fun `model metrics omits top-level model_execution_config when calls have divergent configs`() {
        val metrics = ModelRunLogMetrics()
        val configA = linkedMapOf<String, Any?>(
            "schema_version" to "omniflow.model-execution-config.v1",
            "provider_profile_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "resolved_model" to "model-a",
            "route_source" to "gui_binding",
            "context_capacity" to 32768,
            "output_budget" to 2048,
        )
        val configB = linkedMapOf<String, Any?>(
            "schema_version" to "omniflow.model-execution-config.v1",
            "provider_profile_sha256" to "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            "resolved_model" to "model-b",
            "route_source" to "shared_scene",
            "context_capacity" to 16384,
            "output_budget" to 1024,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "model-a",
                "usage" to mapOf("prompt_tokens" to 50, "completion_tokens" to 10, "total_tokens" to 60),
                "execution_config" to configA,
            ),
            durationMs = 500,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "model-b",
                "usage" to mapOf("prompt_tokens" to 40, "completion_tokens" to 10, "total_tokens" to 50),
                "execution_config" to configB,
            ),
            durationMs = 400,
        )

        val diag = metrics.diagnostics()
        assertEquals(null, diag["model_execution_config"])
        val calls = diag["token_usage_by_call"] as List<*>
        assertEquals(2, calls.size)
        assertEquals("model-a", ((calls[0] as Map<*, *>)["execution_config"] as Map<*, *>)["resolved_model"])
        assertEquals("model-b", ((calls[1] as Map<*, *>)["execution_config"] as Map<*, *>)["resolved_model"])
    }

    @Test
    fun `model metrics omits top-level model_execution_config when any successful call lacks executionConfig`() {
        val metrics = ModelRunLogMetrics()
        val configA = linkedMapOf<String, Any?>(
            "schema_version" to "omniflow.model-execution-config.v1",
            "provider_profile_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "resolved_model" to "model-a",
            "route_source" to "gui_binding",
            "context_capacity" to 32768,
            "output_budget" to 2048,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "model-a",
                "usage" to mapOf("prompt_tokens" to 50, "completion_tokens" to 10, "total_tokens" to 60),
                "execution_config" to configA,
            ),
            durationMs = 500,
        )
        metrics.recordSuccess(
            result = mapOf(
                "requested_model" to OmniVlmPlugin.MODEL_SCENE,
                "resolved_model" to "model-a",
                "usage" to mapOf("prompt_tokens" to 40, "completion_tokens" to 10, "total_tokens" to 50),
            ),
            durationMs = 400,
        )

        val diag = metrics.diagnostics()
        assertEquals(null, diag["model_execution_config"])
    }

    @Test
    fun `sanitizeModelExecutionConfig enforces legal reasoning enums, url and bounds rejection`() {
        val base = mapOf(
            "schema_version" to "omniflow.model-execution-config.v1",
            "provider_profile_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "resolved_model" to "gpt-4o",
            "route_source" to "gui_binding",
            "context_capacity" to 32768,
            "output_budget" to 2048,
            "reasoning_effort" to "arbitrary-injection-string",
            "default_reasoning_level" to "off",
            "supported_reasoning_levels" to listOf("med", "invalid-custom-level", "high"),
            "max_completion_tokens" to 5000,
        )
        val sanitized = sanitizeModelExecutionConfig(base)
        org.junit.Assert.assertNotNull(sanitized)
        assertEquals(null, sanitized!!["reasoning_effort"])
        assertEquals("none", sanitized["default_reasoning_level"])
        assertEquals(listOf("medium", "high"), sanitized["supported_reasoning_levels"])
        assertEquals(null, sanitized["max_completion_tokens"])

        val urlModel = base + ("resolved_model" to "https://api.openai.com/v1/models/gpt-4o")
        assertEquals(null, sanitizeModelExecutionConfig(urlModel))

        val controlModel = base + ("resolved_model" to "gpt-4o\nmalicious")
        assertEquals(null, sanitizeModelExecutionConfig(controlModel))

        val decimalCap = base + ("context_capacity" to 32768.5)
        assertEquals(null, sanitizeModelExecutionConfig(decimalCap))

        val negBudget = base + ("output_budget" to -10)
        assertEquals(null, sanitizeModelExecutionConfig(negBudget))

        val overBudget = base + mapOf("context_capacity" to 1000, "output_budget" to 2000)
        assertEquals(null, sanitizeModelExecutionConfig(overBudget))

        val withOmitted = base + mapOf(
            "omitted_fields" to listOf("max_tokens", "thinking_type", "reasoning_effort", "max_completion_tokens"),
        )
        val sanitizedWithOmitted = sanitizeModelExecutionConfig(withOmitted)
        org.junit.Assert.assertNotNull(sanitizedWithOmitted)
        val sanitizedOmitted = sanitizedWithOmitted!!["omitted_fields"] as List<*>
        org.junit.Assert.assertTrue(sanitizedOmitted.contains("max_tokens"))
        org.junit.Assert.assertTrue(sanitizedOmitted.contains("thinking_type"))
        assertFalse("Illegal reasoning_effort must NOT be marked in omitted_fields", sanitizedOmitted.contains("reasoning_effort"))
        assertFalse("Illegal max_completion_tokens must NOT be marked in omitted_fields", sanitizedOmitted.contains("max_completion_tokens"))
    }
    @Test
    fun `sanitizeRuntimeProvenance validates schema and allowed module map`() {
        val raw = mapOf(
            "schema_version" to "omniflow.runtime-provenance.v1",
            "runtime_version" to "2.1.9",
            "protocol_version" to "2025-11-25",
            "source" to "packaged",
            "manifest_source_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "contract_sha256" to "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
            "loaded_modules" to mapOf(
                "bridge" to mapOf(
                    "source_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "code_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
                "unknown.hack" to mapOf(
                    "source_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    "code_sha256" to "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ),
            ),
            "sensitive_secret" to "should_be_stripped",
        )

        val sanitized = sanitizeRuntimeProvenance(raw)
        org.junit.Assert.assertNotNull(sanitized)
        assertEquals("omniflow.runtime-provenance.v1", sanitized!!["schema_version"])
        assertEquals("packaged", sanitized["source"])
        assertFalse(sanitized.containsKey("sensitive_secret"))
        val loaded = sanitized["loaded_modules"] as Map<*, *>
        org.junit.Assert.assertTrue(loaded.containsKey("bridge"))
        assertFalse(loaded.containsKey("unknown.hack"))
    }
}
