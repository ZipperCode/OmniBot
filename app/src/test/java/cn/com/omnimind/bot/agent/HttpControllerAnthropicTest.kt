package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.AnthropicMessageProtocolState
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionProtocolState
import cn.com.omnimind.baselib.llm.contentText
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class HttpControllerAnthropicTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `automatic anthropic cache control is added for regular payloads`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "hello"}]
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertFalse(root.containsKey("cache_control"))
        val messageContent = root["messages"]
            ?.let { it as kotlinx.serialization.json.JsonArray }
            ?.single()
            ?.jsonObject
            ?.get("content") as kotlinx.serialization.json.JsonArray
        assertEquals(
            "ephemeral",
            messageContent.single().jsonObject["cache_control"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `automatic anthropic cache control is skipped when breakpoint slots are full`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "system": [
                    {"type": "text", "text": "system-a", "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": "system-b", "cache_control": {"type": "ephemeral"}}
                  ],
                  "messages": [
                    {
                      "role": "user",
                      "content": [{"type": "text", "text": "user-a", "cache_control": {"type": "ephemeral"}}]
                    },
                    {
                      "role": "assistant",
                      "content": [{"type": "text", "text": "assistant-a", "cache_control": {"type": "ephemeral"}}]
                    }
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        assertFalse(root.containsKey("cache_control"))
    }

    @Test
    fun `automatic anthropic cache control uses stable system tools and transcript breakpoints`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "applyAnthropicAutomaticCacheControl",
            String::class.java
        )
        method.isAccessible = true
        val payload = method.invoke(
            HttpController,
            """
                {
                  "model": "claude-sonnet",
                  "system": [
                    {"type": "text", "text": "stable", "cache_control": {"type": "ephemeral"}},
                    {"type": "text", "text": "coarse-time"}
                  ],
                  "tools": [
                    {"name": "a", "input_schema": {"type": "object"}},
                    {"name": "z", "input_schema": {"type": "object"}}
                  ],
                  "messages": [
                    {"role": "user", "content": [{"type": "text", "text": "hello"}]}
                  ]
                }
            """.trimIndent()
        ) as String

        val root = json.parseToJsonElement(payload).jsonObject
        val system = root["system"] as kotlinx.serialization.json.JsonArray
        val tools = root["tools"] as kotlinx.serialization.json.JsonArray
        val messages = root["messages"] as kotlinx.serialization.json.JsonArray

        assertTrue(system.all { it.jsonObject.containsKey("cache_control") })
        assertFalse(tools.first().jsonObject.containsKey("cache_control"))
        assertEquals(
            "ephemeral",
            tools.last().jsonObject["cache_control"]?.jsonObject
                ?.get("type")?.jsonPrimitive?.content
        )
        assertEquals(
            "ephemeral",
            messages.last().jsonObject["content"]
                ?.let { it as kotlinx.serialization.json.JsonArray }
                ?.last()
                ?.jsonObject
                ?.get("cache_control")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content
        )
    }

    @Test
    fun `anthropic stream adapter preserves cache hit usage`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "message_start",
            """
                {
                  "type": "message_start",
                  "message": {
                    "usage": {
                      "input_tokens": 12,
                      "cache_creation_input_tokens": 0,
                      "cache_read_input_tokens": 4096,
                      "output_tokens": 1
                    }
                  }
                }
            """.trimIndent()
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("Hello", turn.message.contentText())
        assertEquals("end_turn", turn.finishReason)
        assertEquals(4108, turn.usage?.promptTokens)
        assertEquals(5, turn.usage?.completionTokens)
        assertEquals(4113, turn.usage?.totalTokens)
        assertEquals(
            "4096",
            turn.usage?.promptTokensDetails?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `anthropic non stream usage counts cache writes as processed input`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "normalizeAnthropicUsageResponse",
            String::class.java
        )
        method.isAccessible = true
        val usage = method.invoke(
            HttpController,
            """
                {
                  "usage": {
                    "input_tokens": 7,
                    "cache_creation_input_tokens": 1024,
                    "cache_read_input_tokens": 0,
                    "output_tokens": 2
                  }
                }
            """.trimIndent()
        ) as String
        val root = json.parseToJsonElement(usage).jsonObject

        assertEquals("1031", root["prompt_tokens"]?.jsonPrimitive?.content)
        assertEquals("2", root["completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("1033", root["total_tokens"]?.jsonPrimitive?.content)
        assertEquals(
            "1024",
            root["prompt_tokens_details"]?.jsonObject
                ?.get("cache_creation_tokens")?.jsonPrimitive?.content
        )
        assertEquals(
            "0",
            root["prompt_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `anthropic logged stream usage merges initial input with final output`() {
        val method = HttpController::class.java.getDeclaredMethod(
            "normalizeAnthropicUsageResponse",
            String::class.java
        )
        method.isAccessible = true
        val usage = method.invoke(
            HttpController,
            """
                [
                  {
                    "type": "message_start",
                    "message": {
                      "usage": {
                        "input_tokens": 9,
                        "cache_creation_input_tokens": 0,
                        "cache_read_input_tokens": 2048,
                        "output_tokens": 1
                      }
                    }
                  },
                  {
                    "type": "message_delta",
                    "usage": {"output_tokens": 6}
                  }
                ]
            """.trimIndent()
        ) as String
        val root = json.parseToJsonElement(usage).jsonObject

        assertEquals("2057", root["prompt_tokens"]?.jsonPrimitive?.content)
        assertEquals("6", root["completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("2063", root["total_tokens"]?.jsonPrimitive?.content)
        assertEquals(
            "2048",
            root["prompt_tokens_details"]?.jsonObject
                ?.get("cached_tokens")?.jsonPrimitive?.content
        )
    }

    @Test
    fun `anthropic stream adapter still converts tool calls`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "message_start",
            """{"type":"message_start","message":{"usage":{"input_tokens":20,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_7","name":"get_weather","input":{}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"Shanghai\"}"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":4}}"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("tool_calls", turn.finishReason)
        assertEquals("tool_7", turn.message.toolCalls?.single()?.id)
        assertEquals("get_weather", turn.message.toolCalls?.single()?.function?.name)
        assertEquals(
            """{"city":"Shanghai"}""",
            turn.message.toolCalls?.single()?.function?.arguments
        )
        assertEquals(20, turn.usage?.promptTokens)
        assertEquals(4, turn.usage?.completionTokens)
    }

    @Test
    fun `anthropic tool loop replays signed thinking blocks unchanged`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    chunks += data
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need inspect."}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque-signature"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":0}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"Running command."}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":1}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool_9","name":"terminal_execute","input":{}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"pwd\"}"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":2}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureAnthropicContentBlocks = true,
            anthropicSourceModel = "deepseek-v4-flash-vision-exp"
        )
        chunks.forEach(accumulator::consume)
        val assistant = accumulator.buildTurn().message
        val replayBlocks = assistant.protocolState?.anthropic?.contentBlocks
        assertNotNull(replayBlocks)
        assertEquals("thinking", replayBlocks!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            "opaque-signature",
            replayBlocks[0].jsonObject["signature"]?.jsonPrimitive?.content
        )
        assertEquals("Running command.", assistant.contentText())

        val toolResult = ChatCompletionMessage(
            role = "tool",
            toolCallId = "tool_9",
            content = JsonPrimitive("permission denied"),
            protocolState = ChatCompletionProtocolState(
                anthropic = AnthropicMessageProtocolState(toolResultIsError = true)
            )
        )
        val replayMethod = HttpController::class.java.getDeclaredMethod(
            "resolveAnthropicReplayContentBlocks",
            ChatCompletionMessage::class.java,
            String::class.java
        )
        replayMethod.isAccessible = true
        val replayedAssistantBlocks = replayMethod.invoke(
            HttpController,
            assistant,
            "deepseek-v4-flash-vision-exp"
        )
        assertEquals(replayBlocks, replayedAssistantBlocks)
        assertEquals(
            null,
            replayMethod.invoke(HttpController, assistant, "claude-sonnet-other")
        )

        val errorMethod = HttpController::class.java.getDeclaredMethod(
            "anthropicToolResultIsError",
            ChatCompletionMessage::class.java
        )
        errorMethod.isAccessible = true
        assertEquals(true, errorMethod.invoke(HttpController, toolResult))
    }

    @Test
    fun `fetchProviderModels supports anthropic models endpoint`() = runBlocking {
        val requestLines = mutableListOf<String>()
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            serverSocket.use { socketServer ->
                val socket = socketServer.accept()
                socket.use { client ->
                    val reader = BufferedReader(
                        InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
                    )
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) {
                            break
                        }
                        requestLines += line
                    }

                    val body = """
                        {
                          "data": [
                            {
                              "id": "claude-sonnet-4-5",
                              "display_name": "Claude Sonnet 4.5",
                              "type": "model",
                              "context_limit": 1000000,
                              "output_limit": 64000,
                              "capabilities": {
                                "reasoning": true,
                                "tool_call": true,
                                "vision": true
                              }
                            },
                            {"id": "claude-haiku-4-5", "display_name": "Claude Haiku 4.5", "type": "model"}
                          ]
                        }
                    """.trimIndent()
                    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
                    val writer = BufferedWriter(
                        OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8)
                    )
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: application/json\r\n")
                    writer.write("Content-Length: ${bodyBytes.size}\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.write(body)
                    writer.flush()
                }
            }
        }

        try {
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:${serverSocket.localPort}",
                apiKey = "sk-ant-test",
                protocolType = "anthropic"
            )
            serverThread.join()

            assertEquals(listOf("claude-haiku-4-5", "claude-sonnet-4-5"), models.map { it.id })
            assertEquals(
                listOf("Claude Haiku 4.5", "Claude Sonnet 4.5"),
                models.map { it.displayName }
            )
            assertEquals(1000000, models.last().contextLimit)
            assertEquals(64000, models.last().outputLimit)
            assertTrue(models.last().reasoning == true)
            assertTrue(models.last().toolCall == true)
            assertTrue(models.last().attachment == true)
            assertEquals("GET /v1/models HTTP/1.1", requestLines.first())
            assertEquals(
                "x-api-key: sk-ant-test",
                requestLines.firstOrNull { it.startsWith("x-api-key:", ignoreCase = true) }
            )
            assertEquals(
                "anthropic-version: 2023-06-01",
                requestLines.firstOrNull { it.startsWith("anthropic-version:", ignoreCase = true) }
            )
            assertNotNull(models.first().ownedBy)
        } finally {
            serverSocket.close()
        }
    }
    @Test
    fun `tool definition normalizes schema and ensures object type and properties`() {
        val request = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("Run tool"))),
            tools = listOf(
                ChatCompletionTool(
                    function = ChatCompletionFunction(
                        name = "get_user_info",
                        description = "Test tool",
                        parameters = JsonObject(emptyMap())
                    )
                ),
                ChatCompletionTool(
                    function = ChatCompletionFunction(
                        name = "calculate",
                        parameters = buildJsonObject {
                            put("properties", buildJsonObject {
                                put("expr", buildJsonObject { put("type", JsonPrimitive("string")) })
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("expr")) })
                        }
                    )
                )
            )
        )
        val wireJson = HttpController.convertToAnthropicRequestJson(request)
        val root = json.parseToJsonElement(wireJson).jsonObject
        val tools = root["tools"]!!.jsonArray

        assertEquals(2, tools.size)
        val tool1 = tools[0].jsonObject
        assertEquals("get_user_info", tool1["name"]?.jsonPrimitive?.content)
        assertEquals("Test tool", tool1["description"]?.jsonPrimitive?.content)
        val schema1 = tool1["input_schema"]!!.jsonObject
        assertEquals("object", schema1["type"]?.jsonPrimitive?.content)
        assertNotNull(schema1["properties"])

        val tool2 = tools[1].jsonObject
        assertEquals("calculate", tool2["name"]?.jsonPrimitive?.content)
        val schema2 = tool2["input_schema"]!!.jsonObject
        assertEquals("object", schema2["type"]?.jsonPrimitive?.content)
        assertTrue(schema2["properties"]!!.jsonObject.containsKey("expr"))
        assertEquals("expr", schema2["required"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `tool choice and parallel disable policies match Anthropic Messages API`() {
        val noneRequest = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hello"))),
            tools = listOf(ChatCompletionTool(function = ChatCompletionFunction(name = "get_weather"))),
            toolChoice = JsonPrimitive("none")
        )
        val noneWire = json.parseToJsonElement(HttpController.convertToAnthropicRequestJson(noneRequest)).jsonObject
        assertTrue(noneWire.containsKey("tools"))
        assertEquals("none", noneWire["tool_choice"]!!.jsonObject["type"]?.jsonPrimitive?.content)

        val requiredRequest = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hello"))),
            tools = listOf(ChatCompletionTool(function = ChatCompletionFunction(name = "get_weather"))),
            toolChoice = JsonPrimitive("required"),
            parallelToolCalls = false
        )
        val requiredWire = json.parseToJsonElement(HttpController.convertToAnthropicRequestJson(requiredRequest)).jsonObject
        val toolChoiceObj = requiredWire["tool_choice"]!!.jsonObject
        assertEquals("any", toolChoiceObj["type"]?.jsonPrimitive?.content)
        assertEquals(true, toolChoiceObj["disable_parallel_tool_use"]?.jsonPrimitive?.content?.toBoolean())

        val specificRequest = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hello"))),
            tools = listOf(ChatCompletionTool(function = ChatCompletionFunction(name = "get_weather"))),
            toolChoice = buildJsonObject {
                put("type", JsonPrimitive("function"))
                put("function", buildJsonObject { put("name", JsonPrimitive("get_weather")) })
            },
            parallelToolCalls = false
        )
        val specificWire = json.parseToJsonElement(HttpController.convertToAnthropicRequestJson(specificRequest)).jsonObject
        val specificChoice = specificWire["tool_choice"]!!.jsonObject
        assertEquals("tool", specificChoice["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", specificChoice["name"]?.jsonPrimitive?.content)
        assertEquals(true, specificChoice["disable_parallel_tool_use"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `multiple input_json_delta chunks are accumulated and tool call parsed`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(source, null, "message_start", """{"type":"message_start","message":{"usage":{"input_tokens":15,"output_tokens":0}}}""")
        wrapped.onEvent(source, null, "content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call_batch_1","name":"exec_cmd","input":{}}}""")
        wrapped.onEvent(source, null, "content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":"}}""")
        wrapped.onEvent(source, null, "content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"ls -l\","}}""")
        wrapped.onEvent(source, null, "content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"timeout\":30}"}}""")
        wrapped.onEvent(source, null, "content_block_stop", """{"type":"content_block_stop","index":0}""")
        wrapped.onEvent(source, null, "message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}""")
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureAnthropicContentBlocks = true,
            anthropicSourceModel = "claude-3-7-sonnet"
        )
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals("tool_calls", turn.finishReason)
        assertEquals("call_batch_1", turn.message.toolCalls?.single()?.id)
        assertEquals("exec_cmd", turn.message.toolCalls?.single()?.function?.name)
        val args = json.parseToJsonElement(turn.message.toolCalls?.single()?.function?.arguments!!).jsonObject
        assertEquals("ls -l", args["cmd"]?.jsonPrimitive?.content)
        assertEquals(30, args["timeout"]?.jsonPrimitive?.content?.toInt())

        val replayBlock = turn.message.protocolState?.anthropic?.contentBlocks?.single()?.jsonObject
        assertNotNull(replayBlock)
        assertEquals("tool_use", replayBlock!!["type"]?.jsonPrimitive?.content)
        assertEquals("call_batch_1", replayBlock["id"]?.jsonPrimitive?.content)
        val replayInput = replayBlock["input"]!!.jsonObject
        assertEquals("ls -l", replayInput["cmd"]?.jsonPrimitive?.content)
        assertEquals(30, replayInput["timeout"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `redacted thinking and thinking with signature replay in multi round request`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(source, null, "content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque_enc_data_123"}}""")
        wrapped.onEvent(source, null, "content_block_stop", """{"type":"content_block_stop","index":0}""")
        wrapped.onEvent(source, null, "content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"Analyzing command"}}""")
        wrapped.onEvent(source, null, "content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"signature_delta","signature":"sig_abc_456"}}""")
        wrapped.onEvent(source, null, "content_block_stop", """{"type":"content_block_stop","index":1}""")
        wrapped.onEvent(source, null, "content_block_start", """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"call_99","name":"read_file","input":{"path":"/etc/hosts"}}}""")
        wrapped.onEvent(source, null, "content_block_stop", """{"type":"content_block_stop","index":2}""")
        wrapped.onEvent(source, null, "message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureAnthropicContentBlocks = true,
            anthropicSourceModel = "claude-3-7-sonnet"
        )
        chunks.forEach(accumulator::consume)
        val assistantMessage = accumulator.buildTurn().message

        val toolResult = ChatCompletionMessage(
            role = "tool",
            toolCallId = "call_99",
            content = JsonPrimitive("127.0.0.1 localhost")
        )
        val nextRequest = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("Check hosts")),
                assistantMessage,
                toolResult
            )
        )
        val wireJson = HttpController.convertToAnthropicRequestJson(nextRequest)
        val wire = json.parseToJsonElement(wireJson).jsonObject
        val wireMessages = wire["messages"]!!.jsonArray

        assertEquals(3, wireMessages.size)
        val assistantContent = wireMessages[1].jsonObject["content"]!!.jsonArray
        assertEquals(3, assistantContent.size)
        assertEquals("redacted_thinking", assistantContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("opaque_enc_data_123", assistantContent[0].jsonObject["data"]?.jsonPrimitive?.content)

        assertEquals("thinking", assistantContent[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Analyzing command", assistantContent[1].jsonObject["thinking"]?.jsonPrimitive?.content)
        assertEquals("sig_abc_456", assistantContent[1].jsonObject["signature"]?.jsonPrimitive?.content)

        assertEquals("tool_use", assistantContent[2].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_99", assistantContent[2].jsonObject["id"]?.jsonPrimitive?.content)

        val toolContent = wireMessages[2].jsonObject["content"]!!.jsonArray
        assertEquals("tool_result", toolContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("call_99", toolContent[0].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("127.0.0.1 localhost", toolContent[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown or different source model drops signed thinking without losing text or tool ids`() {
        val model = "claude-3-7-sonnet"
        val assistant = ChatCompletionMessage(
            role = "assistant",
            content = JsonPrimitive("正在读取文件"),
            toolCalls = listOf(AssistantToolCall(
                id = "call_read",
                function = AssistantToolCallFunction(name = "read_file", arguments = "{}")
            )),
            reasoningContent = "不应成为可见正文的内部思考"
        )
        val blocks = json.parseToJsonElement("""[
            {"type":"thinking","thinking":"内部思考","signature":"opaque-signature"},
            {"type":"redacted_thinking","data":"opaque-data"},
            {"type":"text","text":"正在读取文件"},
            {"type":"tool_use","id":"call_read","name":"read_file","input":{}}
        ]""").jsonArray
        listOf(null, "", "claude-other", model.uppercase()).forEach { source ->
            val message = assistant.copy(protocolState = ChatCompletionProtocolState(
                anthropic = AnthropicMessageProtocolState(sourceModel = source, contentBlocks = blocks)
            ))
            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(
                    ChatCompletionMessage(role = "user", content = JsonPrimitive("读取文件")),
                    message,
                    ChatCompletionMessage(role = "tool", toolCallId = "call_read", content = JsonPrimitive("文件内容"))
                )
            )
            val wire = json.parseToJsonElement(HttpController.convertToAnthropicRequestJson(request)).jsonObject
            val content = wire["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
            assertEquals(listOf("text", "tool_use"), content.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            assertEquals("正在读取文件", content[0].jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("call_read", content[1].jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `consecutive tool results merge and split error images after all tool results`() {
        val tool1Success = ChatCompletionMessage(
            role = "tool",
            toolCallId = "call_tool_1",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("Screenshot captured successfully"))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject {
                        put("url", JsonPrimitive("data:image/png;base64,iVBORw0KGgoAAASUhEUg=="))
                    })
                })
            }
        )
        val tool2Error = ChatCompletionMessage(
            role = "tool",
            toolCallId = "call_tool_2",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("Failed to click element"))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject {
                        put("url", JsonPrimitive("data:image/jpeg;base64,/9j/4AAQSkZJRg=="))
                    })
                })
            },
            protocolState = ChatCompletionProtocolState(
                anthropic = AnthropicMessageProtocolState(toolResultIsError = true)
            )
        )

        val request = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("Perform actions")),
                ChatCompletionMessage(
                    role = "assistant",
                    toolCalls = listOf(
                        AssistantToolCall(id = "call_tool_1", function = AssistantToolCallFunction(name = "screenshot", arguments = "{}")),
                        AssistantToolCall(id = "call_tool_2", function = AssistantToolCallFunction(name = "click", arguments = "{}"))
                    )
                ),
                tool1Success,
                tool2Error
            )
        )

        val wireJson = HttpController.convertToAnthropicRequestJson(request)
        val wire = json.parseToJsonElement(wireJson).jsonObject
        val messages = wire["messages"]!!.jsonArray

        assertEquals(3, messages.size)
        val userTurnBlocks = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(3, userTurnBlocks.size)

        val tr1 = userTurnBlocks[0].jsonObject
        assertEquals("tool_result", tr1["type"]?.jsonPrimitive?.content)
        assertEquals("call_tool_1", tr1["tool_use_id"]?.jsonPrimitive?.content)
        assertFalse(tr1.containsKey("is_error"))
        val tr1Content = tr1["content"]!!.jsonArray
        assertEquals("text", tr1Content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Screenshot captured successfully", tr1Content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("image", tr1Content[1].jsonObject["type"]?.jsonPrimitive?.content)
        val src1 = tr1Content[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", src1["type"]?.jsonPrimitive?.content)
        assertEquals("image/png", src1["media_type"]?.jsonPrimitive?.content)
        assertEquals("iVBORw0KGgoAAASUhEUg==", src1["data"]?.jsonPrimitive?.content)

        val tr2 = userTurnBlocks[1].jsonObject
        assertEquals("tool_result", tr2["type"]?.jsonPrimitive?.content)
        assertEquals("call_tool_2", tr2["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals(true, tr2["is_error"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(tr2["content"]?.jsonPrimitive?.content.orEmpty().contains("Failed to click element"))
        assertTrue(tr2["content"]?.jsonPrimitive?.content.orEmpty().contains("(image attached after the tool results)"))

        val detachedImg = userTurnBlocks[2].jsonObject
        assertEquals("image", detachedImg["type"]?.jsonPrimitive?.content)
        val src2 = detachedImg["source"]!!.jsonObject
        assertEquals("base64", src2["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", src2["media_type"]?.jsonPrimitive?.content)
        assertEquals("/9j/4AAQSkZJRg==", src2["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `user multimodal message converts openai image_url to anthropic image block`() {
        val request = ChatCompletionRequest(
            model = "claude-3-7-sonnet",
            messages = listOf(
                ChatCompletionMessage(
                    role = "user",
                    content = buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive("Analyze this screenshot"))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject {
                                put("url", JsonPrimitive("data:image/webp;base64,UklGRkAAAABXRUJQVlA4WAoAAAAQAAAAAQAA"))
                            })
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("image_url"))
                            put("image_url", buildJsonObject {
                                put("url", JsonPrimitive("https://example.com/remote.png"))
                            })
                        })
                    }
                )
            )
        )
        val wireJson = HttpController.convertToAnthropicRequestJson(request)
        val wire = json.parseToJsonElement(wireJson).jsonObject
        val userContent = wire["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals(3, userContent.size)
        assertEquals("text", userContent[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Analyze this screenshot", userContent[0].jsonObject["text"]?.jsonPrimitive?.content)

        assertEquals("image", userContent[1].jsonObject["type"]?.jsonPrimitive?.content)
        val src1 = userContent[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", src1["type"]?.jsonPrimitive?.content)
        assertEquals("image/webp", src1["media_type"]?.jsonPrimitive?.content)
        assertEquals("UklGRkAAAABXRUJQVlA4WAoAAAAQAAAAAQAA", src1["data"]?.jsonPrimitive?.content)

        assertEquals("image", userContent[2].jsonObject["type"]?.jsonPrimitive?.content)
        val src2 = userContent[2].jsonObject["source"]!!.jsonObject
        assertEquals("url", src2["type"]?.jsonPrimitive?.content)
        assertEquals("https://example.com/remote.png", src2["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `anthropic stream adapter accumulates thinking tokens details and handles stream error`() {
        val chunks = mutableListOf<String>()
        var failureOccurred: Throwable? = null
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    failureOccurred = t
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            "message_start",
            """{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_creation_input_tokens":50,"cache_read_input_tokens":200,"output_tokens":1}}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Hello"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}"""
        )
        wrapped.onEvent(
            source,
            null,
            "content_block_stop",
            """{"type":"content_block_stop","index":0}"""
        )
        wrapped.onEvent(
            source,
            null,
            "message_delta",
            """{
                "type":"message_delta",
                "delta":{"stop_reason":"end_turn"},
                "usage":{
                    "output_tokens":45,
                    "output_tokens_details":{"thinking_tokens":30}
                }
            }"""
        )
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")

        val accumulator = AgentLlmStreamAccumulator(json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        assertEquals(350, turn.usage?.promptTokens)
        assertEquals(45, turn.usage?.completionTokens)
        assertEquals(395, turn.usage?.totalTokens)
        assertEquals("200", turn.usage?.promptTokensDetails?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.content)
        assertEquals("30", turn.usage?.completionTokensDetails?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.content)

        wrapped.onEvent(
            source,
            null,
            "error",
            """{"type":"error","error":{"type":"overloaded_error","message":"Anthropic servers overloaded"}}"""
        )
        assertNotNull(failureOccurred)
        assertTrue(failureOccurred?.message.orEmpty().contains("Anthropic servers overloaded"))
    }
    @Test
    fun `anthropic stream closes before message_stop triggers terminal io exception`() {
        var failureOccurred: Throwable? = null
        var closedCalled = false
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onClosed(eventSource: EventSource) {
                    closedCalled = true
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    failureOccurred = t
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(source, null, "message_start", """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""")
        wrapped.onEvent(source, null, "content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Partial"}}""")
        wrapped.onClosed(source)

        assertFalse(closedCalled)
        assertNotNull(failureOccurred)
        assertTrue(failureOccurred is java.io.IOException)
    }

    @Test
    fun `anthropic stream error event blocks subsequent message_stop and done`() {
        val chunks = mutableListOf<String>()
        var failureOccurred: Throwable? = null
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    failureOccurred = t
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(source, null, "message_start", """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""")
        wrapped.onEvent(source, null, "error", """{"type":"error","error":{"type":"api_error","message":"rate limit exceeded"}}""")

        assertNotNull(failureOccurred)
        assertTrue(failureOccurred?.message.orEmpty().contains("rate limit exceeded"))

        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")
        wrapped.onEvent(source, null, null, "[DONE]")

        assertFalse(chunks.contains("[DONE]"))
    }

    @Test
    fun `truncated tool input json fails at content block stop`() {
        var failureOccurred: Throwable? = null
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapAnthropicListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    failureOccurred = t
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(source, null, "message_start", """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0}}}""")
        wrapped.onEvent(source, null, "content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_trunc","name":"bash","input":{}}}""")
        wrapped.onEvent(source, null, "content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"rm -rf"}}""")
        wrapped.onEvent(source, null, "content_block_stop", """{"type":"content_block_stop","index":0}""")

        assertNotNull(failureOccurred)
        assertTrue(failureOccurred is IllegalStateException)
        wrapped.onEvent(source, null, "message_stop", """{"type":"message_stop"}""")
        assertFalse(chunks.contains("[DONE]"))
    }

    @Test
    fun `parseAnthropicResponse extracts text reasoning tool calls and normalized finish reason`() {
        val responseBody = """
            {
              "id": "msg_01",
              "type": "message",
              "role": "assistant",
              "model": "claude-3-7-sonnet",
              "content": [
                {
                  "type": "thinking",
                  "thinking": "We should check the current directory contents."
                },
                {
                  "type": "text",
                  "text": "Checking files:"
                },
                {
                  "type": "tool_use",
                  "id": "toolu_01_ls",
                  "name": "bash",
                  "input": {"command": "ls -la"}
                }
              ],
              "stop_reason": "tool_use"
            }
        """.trimIndent()

        val parsed = HttpController.parseAnthropicResponse(
            body = responseBody,
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            routeTag = "anthropic.primary"
        )

        assertTrue(parsed.success)
        assertEquals("Checking files:", parsed.content)
        assertEquals("We should check the current directory contents.", parsed.reasoning)
        assertEquals("tool_calls", parsed.finishReason)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("toolu_01_ls", parsed.toolCalls[0].id)
        assertEquals("bash", parsed.toolCalls[0].function.name)
        val args = json.parseToJsonElement(parsed.toolCalls[0].function.arguments).jsonObject
        assertEquals("ls -la", args["command"]?.jsonPrimitive?.content)
    }

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request =
                Request.Builder().url("https://example.com").build()

            override fun cancel() = Unit
        }
    }

}
