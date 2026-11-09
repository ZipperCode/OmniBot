package cn.com.omnimind.bot.agent

import android.app.Application
import android.content.Context
import com.tencent.mmkv.MMKV
import java.lang.reflect.Proxy
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.stubbing.Answer
import cn.com.omnimind.assists.controller.http.GeminiProtocolAdapter
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.controller.http.HttpController.ModelAvailabilityCheckResult
import cn.com.omnimind.assists.controller.http.SceneChatCompletionResponse
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionThinking
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.llm.ChatCompletionProtocolState
import cn.com.omnimind.baselib.llm.GeminiMessageProtocolState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class HttpControllerGeminiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `gemini request json conversion maps system instruction, contents, generationConfig, and tools`() {
        val request = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(
                ChatCompletionMessage(role = "system", content = JsonPrimitive("You are a helpful assistant.")),
                ChatCompletionMessage(role = "user", content = JsonPrimitive("Hello Gemini")),
            ),
            temperature = 0.7,
            topP = 0.9,
            maxTokens = 2048,
            thinking = ChatCompletionThinking(type = "enabled"),
            reasoningEffort = "low",
            tools = listOf(
                ChatCompletionTool(
                    type = "function",
                    function = ChatCompletionFunction(
                        name = "get_weather",
                        description = "Get current weather",
                        parameters = buildJsonObject {
                            put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
                            put("type", JsonPrimitive("object"))
                            put("properties", buildJsonObject {
                                put("location", buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("City name"))
                                })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("location"))
                            })
                        }
                    )
                )
            ),
            responseFormat = buildJsonObject {
                put("type", JsonPrimitive("json_object"))
                put("json_schema", buildJsonObject {
                    put("name", JsonPrimitive("custom_output"))
                    put("schema", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {
                            put("result", buildJsonObject { put("type", JsonPrimitive("string")) })
                        })
                    })
                })
            }
        )

        val jsonStr = HttpController.convertToGeminiRequestJson(request)
        val root = json.parseToJsonElement(jsonStr).jsonObject

        // 1. systemInstruction
        val systemInstruction = root["systemInstruction"]?.jsonObject
        assertNotNull(systemInstruction)
        val sysPart = systemInstruction!!["parts"]?.jsonArray?.firstOrNull()?.jsonObject
        assertEquals("You are a helpful assistant.", sysPart?.get("text")?.jsonPrimitive?.content)

        // 2. contents
        val contents = root["contents"]?.jsonArray
        assertNotNull(contents)
        assertEquals(1, contents!!.size)
        val userTurn = contents[0].jsonObject
        assertEquals("user", userTurn["role"]?.jsonPrimitive?.content)
        val userPart = userTurn["parts"]?.jsonArray?.firstOrNull()?.jsonObject
        assertEquals("Hello Gemini", userPart?.get("text")?.jsonPrimitive?.content)

        // 3. generationConfig
        val genConfig = root["generationConfig"]?.jsonObject
        assertNotNull(genConfig)
        assertEquals("0.7", genConfig!!["temperature"]?.jsonPrimitive?.content)
        assertEquals("0.9", genConfig["topP"]?.jsonPrimitive?.content)
        assertEquals("2048", genConfig["maxOutputTokens"]?.jsonPrimitive?.content)
        val thinkingConfig = genConfig["thinkingConfig"]?.jsonObject
        assertNotNull(thinkingConfig)
        assertEquals("true", thinkingConfig!!["includeThoughts"]?.jsonPrimitive?.content)
        assertEquals("2048", thinkingConfig["thinkingBudget"]?.jsonPrimitive?.content)
        assertEquals("application/json", genConfig["responseMimeType"]?.jsonPrimitive?.content)
        val responseJsonSchema = genConfig["responseJsonSchema"]?.jsonObject
        assertNotNull(responseJsonSchema)
        assertEquals("object", responseJsonSchema!!["type"]?.jsonPrimitive?.content)
        assertFalse("不应包含互斥的 legacy responseSchema 字段", genConfig.containsKey("responseSchema"))
        // 4. tools (遵循 Pi 上游，采用 parametersJsonSchema 保留完整 schema 包括 $schema 与 $defs)
        val tools = root["tools"]?.jsonArray
        assertNotNull(tools)
        val fnDeclarations = tools!![0].jsonObject["functionDeclarations"]?.jsonArray
        assertNotNull(fnDeclarations)
        val fn = fnDeclarations!![0].jsonObject
        assertEquals("get_weather", fn["name"]?.jsonPrimitive?.content)
        assertEquals("Get current weather", fn["description"]?.jsonPrimitive?.content)
        val parameters = fn["parametersJsonSchema"]?.jsonObject
        assertNotNull(parameters)
        assertTrue(parameters!!.containsKey("\$schema"))
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `public postSceneChatCompletion executes gemini nonstream request against local HttpServer`() = runBlocking {
        val requestLines = mutableListOf<String>()
        var requestBodyText = ""
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            try {
                serverSocket.use { socketServer ->
                    val socket = socketServer.accept()
                    socket.use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                        var contentLength = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            requestLines += line
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                            }
                        }
                        if (contentLength > 0) {
                            val buf = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val r = reader.read(buf, read, contentLength - read)
                                if (r == -1) break
                                read += r
                            }
                            requestBodyText = String(buf, 0, read)
                        }

                        val responsePayload = """
                            {
                              "candidates": [
                                {
                                  "content": {
                                    "parts": [
                                      {"text": "Hello from Gemini nonstream!"}
                                    ],
                                    "role": "model"
                                  },
                                  "finishReason": "STOP",
                                  "index": 0
                                }
                              ],
                              "usageMetadata": {
                                "promptTokenCount": 12,
                                "candidatesTokenCount": 7,
                                "totalTokenCount": 19
                              }
                            }
                        """.trimIndent()
                        val bytes = responsePayload.toByteArray(StandardCharsets.UTF_8)
                        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                        writer.write("HTTP/1.1 200 OK\r\n")
                        writer.write("Content-Type: application/json\r\n")
                        writer.write("Content-Length: ${bytes.size}\r\n")
                        writer.write("Connection: close\r\n\r\n")
                        writer.write(responsePayload)
                        writer.flush()
                    }
                }
            } catch (_: Throwable) {
                // Ignore socket closure on test teardown
            }
        }

        var staticMock: MockedStatic<MMKV>? = null
        var secretStoreField: java.lang.reflect.Field? = null
        var originalSecretStore: Any? = null
        try {
            val mmkvStorage = mutableMapOf<String, Any?>()
            mmkvStorage["model_provider_scene_config_flattened_v3"] = true

            val mockMMKV = mock(MMKV::class.java, Answer<Any?> { invocation ->
                val args = invocation.arguments
                when (invocation.method.name) {
                    "decodeString" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        val value = mmkvStorage[key]
                        if (value != null) value.toString() else if (args.size > 1) args[1] as? String else null
                    }
                    "decodeBool" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        val value = mmkvStorage[key]
                        when (value) {
                            is Boolean -> value
                            is String -> value.toBoolean()
                            else -> if (args.size > 1) args[1] as? Boolean ?: false else false
                        }
                    }
                    "decodeInt" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        val value = mmkvStorage[key]
                        when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: 0
                            else -> if (args.size > 1) args[1] as? Int ?: 0 else 0
                        }
                    }
                    "decodeLong" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        val value = mmkvStorage[key]
                        when (value) {
                            is Number -> value.toLong()
                            is String -> value.toLongOrNull() ?: 0L
                            else -> if (args.size > 1) args[1] as? Long ?: 0L else 0L
                        }
                    }
                    "encode" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        val value = args.getOrNull(1)
                        if (value == null) {
                            mmkvStorage.remove(key)
                        } else {
                            mmkvStorage[key] = value
                        }
                        true
                    }
                    "removeValueForKey" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        mmkvStorage.remove(key)
                        null
                    }
                    "removeValuesForKeys" -> {
                        val keys = args.getOrNull(0) as? Array<*>
                        keys?.forEach { mmkvStorage.remove(it as? String) }
                        null
                    }
                    "clearAll" -> {
                        mmkvStorage.clear()
                        null
                    }
                    "containsKey" -> {
                        val key = args.getOrNull(0) as? String ?: ""
                        mmkvStorage.containsKey(key)
                    }
                    "allKeys" -> mmkvStorage.keys.toTypedArray()
                    else -> RETURNS_DEFAULTS.answer(invocation)
                }
            })

            val sm = mockStatic(MMKV::class.java)
            staticMock = sm
            sm.`when`<MMKV> { MMKV.defaultMMKV() }.thenReturn(mockMMKV)
            sm.`when`<MMKV> { MMKV.mmkvWithID(anyString()) }.thenReturn(mockMMKV)
            sm.`when`<MMKV> { MMKV.mmkvWithID(anyString(), anyInt()) }.thenReturn(mockMMKV)
            sm.`when`<String> { MMKV.initialize(any(Context::class.java)) }.thenReturn("/mock/mmkv")
            sm.`when`<String> { MMKV.initialize(any(Context::class.java), anyString()) }.thenReturn("/mock/mmkv")
            sm.`when`<String> { MMKV.initialize(anyString()) }.thenReturn("/mock/mmkv")

            val f = ModelProviderConfigStore::class.java.getDeclaredField("secretStore").apply {
                isAccessible = true
            }
            secretStoreField = f
            originalSecretStore = f.get(ModelProviderConfigStore)
            val memorySecrets = mutableMapOf<String, Any?>()
            val proxySecretStore = Proxy.newProxyInstance(
                f.type.classLoader,
                arrayOf(f.type)
            ) { proxy, method, args ->
                when (method.name) {
                    "isAvailable" -> true
                    "readProfile" -> {
                        val profileId = args?.getOrNull(0) as? String ?: ""
                        memorySecrets[profileId]
                    }
                    "writeProfile" -> {
                        val profileId = args?.getOrNull(0) as? String ?: ""
                        val secrets = args?.getOrNull(1)
                        memorySecrets[profileId] = secrets
                        null
                    }
                    "deleteProfile" -> {
                        val profileId = args?.getOrNull(0) as? String ?: ""
                        memorySecrets.remove(profileId)
                        null
                    }
                    "deleteProfilesExcept" -> {
                        val ids = (args?.getOrNull(0) as? Set<*>) ?: emptySet<Any?>()
                        memorySecrets.keys.retainAll(ids)
                        null
                    }
                    "readLegacy" -> null
                    "writeLegacy", "deleteLegacy" -> null
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args?.getOrNull(0) === proxy
                    "toString" -> "TestMemorySecretStore"
                    else -> null
                }
            }
            f.set(ModelProviderConfigStore, proxySecretStore)

            val savedProfile = ModelProviderConfigStore.saveProfile(
                name = "GeminiTestServer",
                baseUrl = "http://127.0.0.1:${serverSocket.localPort}",
                apiKey = "test-gemini-key",
                protocolType = "gemini",
                customHeaders = mapOf("x-test-header" to "gemini-val")
            )
            ModelProviderConfigStore.setEditingProfile(savedProfile.id)

            val sceneResponse = HttpController.postSceneChatCompletion(
                ChatCompletionRequest(
                    model = "gemini-2.5-flash",
                    messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("ping")))
                )
            )

            serverThread.join(5000)

            assertTrue(sceneResponse.success)
            assertEquals("200", sceneResponse.code)
            assertEquals("Hello from Gemini nonstream!", sceneResponse.content)
            assertEquals("stop", sceneResponse.finishReason)

            assertEquals(
                "POST /v1beta/models/gemini-2.5-flash:generateContent HTTP/1.1",
                requestLines.first()
            )
            assertEquals(
                "x-goog-api-key: test-gemini-key",
                requestLines.firstOrNull { it.startsWith("x-goog-api-key:", ignoreCase = true) }
            )
            assertEquals(
                "x-test-header: gemini-val",
                requestLines.firstOrNull { it.startsWith("x-test-header:", ignoreCase = true) }
            )
            assertTrue(requestBodyText.contains("ping"))
        } finally {
            try {
                ModelProviderConfigStore.clearConfig()
            } catch (_: Throwable) {
            }
            try {
                if (secretStoreField != null) {
                    secretStoreField.set(ModelProviderConfigStore, originalSecretStore)
                }
            } catch (_: Throwable) {
            }
            try {
                staticMock?.close()
            } catch (_: Throwable) {
            }
            try {
                serverSocket.close()
            } catch (_: Throwable) {
            }
            try {
                serverThread.join(2000)
            } catch (_: Throwable) {
            }
        }
    }

    @Test
    fun `public stream request runs gemini SSE and aggregates thoughts and cache tokens`() = runBlocking {
        val requestLines = mutableListOf<String>()
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            serverSocket.use { socketServer ->
                val socket = socketServer.accept()
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        requestLines += line
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val r = reader.read(buf, read, contentLength - read)
                            if (r == -1) break
                            read += r
                        }
                    }

                    val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                    writer.write("HTTP/1.1 200 OK\r\n")
                    writer.write("Content-Type: text/event-stream\r\n")
                    writer.write("Connection: close\r\n\r\n")

                    // Chunk 1: Thinking part
                    val chunk1 = """{"candidates":[{"content":{"parts":[{"thought":true,"text":"Analyzing input.","thoughtSignature":"sig-123"}],"role":"model"},"index":0}]}"""
                    writer.write("data: $chunk1\n\n")
                    writer.flush()

                    // Chunk 2: Content part with STOP + usageMetadata (含 cached tokens 与 thoughts tokens)
                    val chunk2 = """{"candidates":[{"content":{"parts":[{"text":"Final answer."}],"role":"model"},"finishReason":"STOP","index":0}],"usageMetadata":{"promptTokenCount":15,"candidatesTokenCount":7,"totalTokenCount":25,"cachedContentTokenCount":5,"thoughtsTokenCount":3}}"""
                    writer.write("data: $chunk2\n\n")
                    writer.flush()
                }
            }
        }

        try {
            val chunks = mutableListOf<String>()
            val latch = CountDownLatch(1)

            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                    if (data == "[DONE]") {
                        latch.countDown()
                    }
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    latch.countDown()
                }

                override fun onClosed(eventSource: EventSource) {
                    latch.countDown()
                }
            }

            val eventSource = HttpController.postChatCompletionsStreamRequest(
                model = "gemini-2.5-flash",
                requestBodyJson = json.encodeToString(
                    ChatCompletionRequest.serializer(),
                    ChatCompletionRequest(
                        model = "gemini-2.5-flash",
                        messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("ping")))
                    )
                ),
                event = listener,
                explicitApiBase = "http://127.0.0.1:${serverSocket.localPort}",
                explicitApiKey = "test-gemini-key",
                explicitCustomHeaders = mapOf("Authorization" to "Bearer custom-override"),
                explicitProtocolType = "gemini"
            )

            latch.await(5, TimeUnit.SECONDS)
            serverThread.join()
            eventSource.cancel()

            assertEquals(
                "POST /v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse HTTP/1.1",
                requestLines.first()
            )
            assertEquals(
                "x-goog-api-key: test-gemini-key",
                requestLines.firstOrNull { it.startsWith("x-goog-api-key:", ignoreCase = true) }
            )
            assertEquals(
                "Authorization: Bearer custom-override",
                requestLines.firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
            )

            // 验证累加器输出：output 包含 candidates(7) + thoughts(3) = 10，cached 记录为 5
            val accumulator = AgentLlmStreamAccumulator(
                json = json,
                captureGeminiParts = true,
                geminiSourceModel = "gemini-2.5-flash"
            )
            chunks.forEach(accumulator::consume)
            val turn = accumulator.buildTurn()

            assertEquals("Final answer.", turn.message.contentText())
            assertEquals("Analyzing input.", turn.reasoning)
            assertEquals("stop", turn.finishReason)
            assertEquals(15, turn.usage?.promptTokens)
            assertEquals(10, turn.usage?.completionTokens) // 7 + 3
            assertEquals(25, turn.usage?.totalTokens)
            assertEquals(
                "5",
                turn.usage?.promptTokensDetails?.jsonObject?.get("cached_tokens")?.jsonPrimitive?.content
            )
            assertEquals(
                "3",
                turn.usage?.completionTokensDetails?.jsonObject?.get("reasoning_tokens")?.jsonPrimitive?.content
            )
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `stream chunks with multiple provider tool call ids preserve distinct indexes and signatures`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapGeminiListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }
            }
        )

        val source = dummyEventSource()
        // 同一 chunk 内包含两个均自带 provider id 的 tool call
        wrapped.onEvent(
            source,
            null,
            null,
            """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"thought": true, "text": "Parallel tools.", "thoughtSignature": "thought-sig"},
                          {"functionCall": {"name": "read_file", "args": {"path": "a.txt"}, "id": "call_p1"}, "thoughtSignature": "call-sig-1"},
                          {"functionCall": {"name": "write_file", "args": {"path": "b.txt"}, "id": "call_p2"}, "thoughtSignature": "call-sig-2"}
                        ],
                        "role": "model"
                      },
                      "finishReason": "STOP",
                      "index": 0
                    }
                  ]
                }
            """.trimIndent()
        )
        wrapped.onClosed(source)

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureGeminiParts = true,
            geminiSourceModel = "gemini-2.5-flash"
        )
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        // 断言保留两个独立的工具调用，且 id 与 name 正确未被覆盖
        val toolCalls = turn.message.toolCalls
        assertNotNull(toolCalls)
        assertEquals(2, toolCalls!!.size)
        assertEquals("call_p1", toolCalls[0].id)
        assertEquals("read_file", toolCalls[0].function.name)
        assertEquals("call_p2", toolCalls[1].id)
        assertEquals("write_file", toolCalls[1].function.name)

        // 断言 protocolState 保存了两个独立 functionCall 的签名
        val parts = turn.message.protocolState?.gemini?.parts
        assertNotNull(parts)
        assertEquals(3, parts!!.size) // 1 thinking + 2 calls
        assertEquals("call-sig-1", parts[1].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("call-sig-2", parts[2].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini tool call capped by MAX_TOKENS preserves length for the consumer`() {
        // 1. 流式路径：包含 functionCall 但 finishReason 为 MAX_TOKENS
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapGeminiListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }
            }
        )
        val source = dummyEventSource()
        wrapped.onEvent(
            source,
            null,
            null,
            """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"functionCall": {"name": "dangerous_action", "args": {"confirmed": true}, "id": "call_danger"}}
                        ],
                        "role": "model"
                      },
                      "finishReason": "MAX_TOKENS",
                      "index": 0
                    }
                  ]
                }
            """.trimIndent()
        )
        wrapped.onClosed(source)

        val accumulator = AgentLlmStreamAccumulator(json = json)
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        // 必须优先映射为 length，绝不能因含 toolCall 就误映射为 tool_calls
        assertEquals("length", turn.finishReason)
        assertNotNull(turn.message.toolCalls)


        // 2. 非流式路径同样断言
        val nonstreamPayload = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"functionCall": {"name": "dangerous_action", "args": {"confirmed": true}, "id": "call_danger"}}
                    ],
                    "role": "model"
                  },
                  "finishReason": "MAX_TOKENS"
                }
              ]
            }
        """.trimIndent()
        val nonstreamResponse = HttpController.parseGeminiResponse(nonstreamPayload)
        assertTrue(nonstreamResponse.success)
        assertEquals("length", nonstreamResponse.finishReason)
        assertEquals(1, nonstreamResponse.toolCalls.size)
    }

    @Test
    fun `real AgentLlmClient executes two-turn tool conversation and preserves gemini thoughtSignature`() = runBlocking {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        var turn2RequestBody = ""

        val serverThread = thread {
            serverSocket.use { socketServer ->
                // Turn 1: 客户端首轮提问，服务端返回 functionCall + thoughtSignature
                val socket1 = socketServer.accept()
                socket1.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                    val sseTurn1 = """{"candidates":[{"content":{"parts":[{"thought":true,"text":"Need weather.","thoughtSignature":"turn1-think-sig"},{"functionCall":{"name":"get_weather","args":{"city":"Tokyo"},"id":"call_tokyo"},"thoughtSignature":"turn1-tool-sig"}],"role":"model"},"finishReason":"STOP","index":0}]}"""
                    writer.write("data: $sseTurn1\n\n")
                    writer.flush()
                }

                // Turn 2: 客户端带工具结果再请求，服务端记录收到的请求体并返回最终文本
                val socket2 = socketServer.accept()
                socket2.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val r = reader.read(buf, read, contentLength - read)
                            if (r == -1) break
                            read += r
                        }
                        turn2RequestBody = String(buf, 0, read)
                    }
                    val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                    val sseTurn2 = """{"candidates":[{"content":{"parts":[{"text":"Tokyo is 22C and sunny."}],"role":"model"},"finishReason":"STOP","index":0}]}"""
                    writer.write("data: $sseTurn2\n\n")
                    writer.flush()
                }
            }
        }

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val agentLlmClient = HttpAgentLlmClient(
                scope = scope,
                modelOverride = AgentModelOverride(
                    providerProfileId = "gemini-profile",
                    modelId = "gemini-2.5-flash",
                    apiBase = "http://127.0.0.1:${serverSocket.localPort}",
                    apiKey = "test-key",
                    protocolType = "gemini"
                )
            )

            // Turn 1
            val userMsg = ChatCompletionMessage(role = "user", content = JsonPrimitive("What is the weather in Tokyo?"))
            val turn1 = agentLlmClient.streamTurn(
                ChatCompletionRequest(
                    model = "gemini-2.5-flash",
                    messages = listOf(userMsg)
                )
            )

            assertNotNull(turn1.message.toolCalls)
            assertEquals("call_tokyo", turn1.message.toolCalls!!.single().id)
            val geminiState = turn1.message.protocolState?.gemini
            assertNotNull(geminiState)
            assertEquals("gemini-2.5-flash", geminiState!!.sourceModel)
            assertEquals(2, geminiState.parts!!.size)

            // Turn 2: 注入工具结果，发起第二轮真实 streamTurn
            val toolMsg = ChatCompletionMessage(
                role = "tool",
                toolCallId = "call_tokyo",
                name = "get_weather",
                content = JsonPrimitive("{\"temp\": \"22C\", \"condition\": \"sunny\"}")
            )
            val turn2 = agentLlmClient.streamTurn(
                ChatCompletionRequest(
                    model = "gemini-2.5-flash",
                    messages = listOf(userMsg, turn1.message, toolMsg)
                )
            )

            serverThread.join()

            assertEquals("Tokyo is 22C and sunny.", turn2.message.contentText())

            // 核心断言：Turn 2 发给服务端的网络请求中，assistant(model) turn 原样保留了 turn1-tool-sig 签名
            val turn2Json = json.parseToJsonElement(turn2RequestBody).jsonObject
            val contents = turn2Json["contents"]?.jsonArray!!
            val modelTurn = contents[1].jsonObject
            assertEquals("model", modelTurn["role"]?.jsonPrimitive?.content)
            val modelParts = modelTurn["parts"]?.jsonArray!!
            assertEquals("turn1-tool-sig", modelParts[1].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)

            // 并且 tool 轮次正确序列化为 user 的 functionResponse
            val toolTurn = contents[2].jsonObject
            assertEquals("user", toolTurn["role"]?.jsonPrimitive?.content)
            val fnResp = toolTurn["parts"]?.jsonArray!![0].jsonObject["functionResponse"]?.jsonObject!!
            assertEquals("get_weather", fnResp["name"]?.jsonPrimitive?.content)
        } finally {
            scope.cancel()
            serverSocket.close()
        }
    }

    @Test
    fun `gemini model list supports pagination and filters out embedding models`() = runBlocking {
        val requestUrls = mutableListOf<String>()
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            serverSocket.use { socketServer ->
                // Page 1
                val socket1 = socketServer.accept()
                socket1.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val firstLine = reader.readLine().orEmpty()
                    requestUrls += firstLine
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val page1 = """
                        {
                          "models": [
                            {
                              "name": "models/gemini-2.5-flash",
                              "displayName": "Gemini 2.5 Flash",
                              "inputTokenLimit": 1048576,
                              "outputTokenLimit": 8192,
                              "supportedGenerationMethods": ["generateContent", "countTokens"]
                            },
                            {
                              "name": "models/text-embedding-004",
                              "displayName": "Text Embedding 004",
                              "inputTokenLimit": 2048,
                              "outputTokenLimit": 1,
                              "supportedGenerationMethods": ["embedContent"]
                            }
                          ],
                          "nextPageToken": "page2_token"
                        }
                    """.trimIndent()
                    val bytes = page1.toByteArray(StandardCharsets.UTF_8)
                    val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    writer.write(page1)
                    writer.flush()
                }

                // Page 2
                val socket2 = socketServer.accept()
                socket2.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    val firstLine = reader.readLine().orEmpty()
                    requestUrls += firstLine
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val page2 = """
                        {
                          "models": [
                            {
                              "name": "models/gemini-2.5-pro",
                              "displayName": "Gemini 2.5 Pro",
                              "inputTokenLimit": 2097152,
                              "outputTokenLimit": 8192,
                              "supportedGenerationMethods": ["generateContent"]
                            }
                          ]
                        }
                    """.trimIndent()
                    val bytes = page2.toByteArray(StandardCharsets.UTF_8)
                    val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    writer.write(page2)
                    writer.flush()
                }
            }
        }

        try {
            val models = HttpController.fetchProviderModels(
                apiBase = "http://127.0.0.1:${serverSocket.localPort}",
                apiKey = "test-key",
                protocolType = "gemini"
            )
            serverThread.join()

            // 验证分页合并与 embedding 过滤
            assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), models.map { it.id })
            assertEquals(listOf("Gemini 2.5 Flash", "Gemini 2.5 Pro"), models.map { it.displayName })
            assertEquals(1048576, models[0].contextLimit)
            assertEquals(8192, models[0].outputLimit)
            assertEquals(2097152, models[1].contextLimit)

            // 验证请求中带 pageSize 与 pageToken
            assertTrue(requestUrls[0].contains("pageSize=100"))
            assertTrue(requestUrls[1].contains("pageToken=page2_token"))
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `gemini stream fails cleanly on non-terminal finish reasons, prompt block, or missing finish`() {
        // 1. FINISH_REASON_UNSPECIFIED 不可作为成功终止
        var lastFailure: Throwable? = null
        val listener1 = HttpController.wrapGeminiListener(object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                lastFailure = t
            }
        })
        val source = dummyEventSource()
        listener1.onEvent(
            source,
            null,
            null,
            """{"candidates":[{"content":{"parts":[{"text":"Incomplete text"}],"role":"model"},"finishReason":"FINISH_REASON_UNSPECIFIED","index":0}]}"""
        )
        assertNotNull(lastFailure)
        assertTrue(lastFailure!!.message!!.contains("FINISH_REASON_UNSPECIFIED"))

        // 2. SAFETY 阻断原因
        lastFailure = null
        val listener2 = HttpController.wrapGeminiListener(object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                lastFailure = t
            }
        })
        listener2.onEvent(
            source,
            null,
            null,
            """{"candidates":[{"content":{"parts":[{"text":"Safety issue."}],"role":"model"},"finishReason":"SAFETY","index":0}]}"""
        )
        assertNotNull(lastFailure)
        assertTrue(lastFailure!!.message!!.contains("SAFETY"))

        // 3. 流结束未收到完成原因
        lastFailure = null
        val listener3 = HttpController.wrapGeminiListener(object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                lastFailure = t
            }
        })
        listener3.onEvent(
            source,
            null,
            null,
            """{"candidates":[{"content":{"parts":[{"text":"Part text"}],"role":"model"},"index":0}]}"""
        )
        listener3.onClosed(source)
        assertNotNull(lastFailure)
        assertTrue(lastFailure!!.message!!.contains("未包含完成原因"))

        // 4. Prompt 安全阻断
        lastFailure = null
        val listener4 = HttpController.wrapGeminiListener(object : EventSourceListener() {
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                lastFailure = t
            }
        })
        listener4.onEvent(
            source,
            null,
            null,
            """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}"""
        )
        assertNotNull(lastFailure)
        assertTrue(lastFailure!!.message!!.contains("PROHIBITED_CONTENT"))
    }

    @Test
    fun `gemini url root resolution and stripping follow cross-layer specifications`() {
        // 1. base URL 无 path root -> 默认 /v1beta
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            GeminiProtocolAdapter.resolveGeminiUrlRoot("https://generativelanguage.googleapis.com")
        )
        assertEquals(
            "http://127.0.0.1:8080/v1beta",
            GeminiProtocolAdapter.resolveGeminiUrlRoot("http://127.0.0.1:8080")
        )

        // 2. 显式 /v1 或 /v1beta 保留
        assertEquals(
            "https://generativelanguage.googleapis.com/v1",
            GeminiProtocolAdapter.resolveGeminiUrlRoot("https://generativelanguage.googleapis.com/v1")
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            GeminiProtocolAdapter.resolveGeminiUrlRoot("https://generativelanguage.googleapis.com/v1beta")
        )

        // 3. 非空自定义 proxy path 保留而不追加 /v1beta (匹配 SDK apiVersion="")
        assertEquals(
            "https://proxy.example.com/custom-gemini",
            GeminiProtocolAdapter.resolveGeminiUrlRoot("https://proxy.example.com/custom-gemini")
        )
        assertEquals(
            "https://proxy.example.com/prefix/v1beta",
            GeminiProtocolAdapter.resolveGeminiUrlRoot("https://proxy.example.com/prefix/v1beta")
        )

        // 4. 输入已具名 /models/id:generateContent（无 #）剥离到 base
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            GeminiProtocolAdapter.resolveGeminiUrlRoot(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            )
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
            GeminiProtocolAdapter.buildGeminiInferenceUrl(
                apiBase = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
                model = "gemini-2.5-flash",
                stream = false
            )
        )
        assertEquals(
            "https://proxy.example.com/custom-gemini/models/gemini-pro:streamGenerateContent?alt=sse",
            GeminiProtocolAdapter.buildGeminiInferenceUrl(
                apiBase = "https://proxy.example.com/custom-gemini/models/gemini-pro:generateContent",
                model = "models/gemini-pro",
                stream = true
            )
        )

        // 5. 直通 # 保留原有约定
        val directUrl = "http://127.0.0.1:8080/my-custom-proxy#"
        assertEquals(
            "http://127.0.0.1:8080/my-custom-proxy",
            GeminiProtocolAdapter.buildGeminiInferenceUrl(directUrl, "gemini-2.5-flash", false)
        )
        assertEquals(
            "http://127.0.0.1:8080/my-custom-proxy",
            GeminiProtocolAdapter.buildGeminiInferenceUrl(directUrl, "gemini-2.5-flash", true)
        )
        assertEquals(
            "http://127.0.0.1:8080/my-custom-proxy",
            GeminiProtocolAdapter.buildGeminiModelsUrl(directUrl)
        )
    }

    @Test
    fun `checkProviderModelAvailability checks gemini ping endpoint`() = runBlocking {
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            serverSocket.use { socketServer ->
                val socket = socketServer.accept()
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val body = """
                        {
                          "candidates": [
                            {
                              "content": {"parts": [{"text": "pong"}], "role": "model"},
                              "finishReason": "STOP"
                            }
                          ]
                        }
                    """.trimIndent()
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                    writer.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    writer.write(body)
                    writer.flush()
                }
            }
        }

        try {
            val result = HttpController.checkProviderModelAvailability(
                model = "gemini-2.5-flash",
                apiBase = "http://127.0.0.1:${serverSocket.localPort}",
                apiKey = "test-key",
                protocolType = "gemini"
            )
            serverThread.join()

            assertTrue(result.available)
            assertEquals("OK", result.message)
            assertEquals(200, result.code)
        } finally {
            serverSocket.close()
        }
    }

    @Test
    fun `gemini tool choice auto, none, required, and named function mappings follow upstream rules`() {
        val dummyTool = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "search_tool",
                description = "Search the web"
            )
        )
        val strictTool = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "strict_tool",
                description = "Strict schema tool",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {})
                },
                strict = true
            )
        )
        fun extractMode(model: String, tools: List<ChatCompletionTool>, toolChoice: JsonElement?): String? {
            val req = ChatCompletionRequest(
                model = model,
                messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hi"))),
                tools = tools,
                toolChoice = toolChoice
            )
            val root = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(req)).jsonObject
            return root["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject?.get("mode")?.jsonPrimitive?.content
        }

        // 1. auto -> AUTO
        assertEquals("AUTO", extractMode("gemini-2.5-flash", listOf(dummyTool), JsonPrimitive("auto")))

        // 2. none -> NONE
        assertEquals("NONE", extractMode("gemini-2.5-flash", listOf(dummyTool), JsonPrimitive("none")))

        // 3. required -> ANY (绝不能降为 AUTO)
        assertEquals("ANY", extractMode("gemini-2.5-flash", listOf(dummyTool), JsonPrimitive("required")))

        // 4. OpenAI named function -> ANY (指定函数绝不能降为 AUTO)
        val namedChoice = buildJsonObject {
            put("type", JsonPrimitive("function"))
            put("function", buildJsonObject {
                put("name", JsonPrimitive("search_tool"))
            })
        }
        assertEquals("ANY", extractMode("gemini-2.5-flash", listOf(dummyTool), namedChoice))

        // 5. Gemini 3+ 带 strict tool 在无显式强制时映射为 VALIDATED
        assertEquals("VALIDATED", extractMode("gemini-3-flash", listOf(strictTool), null))
        assertEquals("VALIDATED", extractMode("gemini-3-flash", listOf(strictTool), JsonPrimitive("auto")))

        // 6. Gemini 3+ 显式 namedChoice 仍保持 ANY 强制调用
        assertEquals("ANY", extractMode("gemini-3-flash", listOf(strictTool), namedChoice))
        // 7. 序列化成完整的 request JSON 验证 toolConfig
        val req = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hi"))),
            tools = listOf(dummyTool),
            toolChoice = namedChoice
        )
        val root = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(req)).jsonObject
        val fnConfig = root["toolConfig"]?.jsonObject?.get("functionCallingConfig")?.jsonObject
        assertNotNull(fnConfig)
        assertEquals("ANY", fnConfig!!["mode"]?.jsonPrimitive?.content)
        assertEquals("search_tool", fnConfig["allowedFunctionNames"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini tool multimodal result preserves image and text without json objectification`() {
        val toolMessageWithImage = ChatCompletionMessage(
            role = "tool",
            toolCallId = "call_snap_1",
            name = "take_screenshot",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("{\"status\":\"ok\",\"pixels\":100}"))
                })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject {
                        put("url", JsonPrimitive("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
                    })
                })
            }
        )

        // 1. Gemini 3+ 模型：图像作为 inlineData 内嵌在 functionResponse.parts 内部，且 output 保留原始字符串不被转为 JSONObject
        val reqGemini3 = ChatCompletionRequest(
            model = "gemini-3-flash",
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("Take a screenshot")),
                toolMessageWithImage
            )
        )
        val rootGemini3 = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqGemini3)).jsonObject
        val contentsGemini3 = rootGemini3["contents"]?.jsonArray
        assertNotNull(contentsGemini3)
        assertEquals(2, contentsGemini3!!.size)
        val toolTurn3 = contentsGemini3[1].jsonObject
        assertEquals("user", toolTurn3["role"]?.jsonPrimitive?.content)
        val fnResp3 = toolTurn3["parts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("functionResponse")?.jsonObject
        assertNotNull(fnResp3)
        assertEquals("take_screenshot", fnResp3!!["name"]?.jsonPrimitive?.content)
        assertEquals("call_snap_1", fnResp3["id"]?.jsonPrimitive?.content)
        // output 必须保留为字符串，不能变成 {"output": {"status": "ok"}}
        assertEquals("{\"status\":\"ok\",\"pixels\":100}", fnResp3["response"]?.jsonObject?.get("output")?.jsonPrimitive?.content)
        // 内嵌 parts 包含图片
        val embeddedParts3 = fnResp3["parts"]?.jsonArray
        assertNotNull(embeddedParts3)
        assertEquals(1, embeddedParts3!!.size)
        val inlineData3 = embeddedParts3[0].jsonObject["inlineData"]?.jsonObject
        assertNotNull(inlineData3)
        assertEquals("image/png", inlineData3!!["mimeType"]?.jsonPrimitive?.content)
        assertTrue(inlineData3["data"]?.jsonPrimitive?.content?.startsWith("iVBORw0KGgo") == true)

        // 2. Gemini < 3 模型 (如 gemini-2.5-flash)：functionResponse 不含 parts，图片紧跟在后续独立的 user 轮次
        val reqGemini2 = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(
                ChatCompletionMessage(role = "user", content = JsonPrimitive("Take a screenshot")),
                toolMessageWithImage
            )
        )
        val rootGemini2 = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqGemini2)).jsonObject
        val contentsGemini2 = rootGemini2["contents"]?.jsonArray
        assertNotNull(contentsGemini2)
        assertEquals(3, contentsGemini2!!.size) // user -> functionResponse user -> fallback image user
        val toolTurn2 = contentsGemini2[1].jsonObject
        val fnResp2 = toolTurn2["parts"]?.jsonArray?.firstOrNull()?.jsonObject?.get("functionResponse")?.jsonObject
        assertNotNull(fnResp2)
        assertFalse("Gemini < 3 的 functionResponse 不应内嵌 parts", fnResp2!!.containsKey("parts"))
        assertEquals("{\"status\":\"ok\",\"pixels\":100}", fnResp2["response"]?.jsonObject?.get("output")?.jsonPrimitive?.content)

        val imageTurn2 = contentsGemini2[2].jsonObject
        assertEquals("user", imageTurn2["role"]?.jsonPrimitive?.content)
        val imageParts2 = imageTurn2["parts"]?.jsonArray
        assertNotNull(imageParts2)
        assertEquals(2, imageParts2!!.size)
        assertEquals("Tool result image:", imageParts2[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("image/png", imageParts2[1].jsonObject["inlineData"]?.jsonObject?.get("mimeType")?.jsonPrimitive?.content)

        // 3. 纯图片 tool result：output 值为 "(see attached image)"
        val imageOnlyMsg = ChatCompletionMessage(
            role = "tool",
            toolCallId = "call_snap_2",
            name = "take_screenshot",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject {
                        put("url", JsonPrimitive("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
                    })
                })
            }
        )
        val reqImageOnly = ChatCompletionRequest(
            model = "gemini-3-flash",
            messages = listOf(imageOnlyMsg)
        )
        val rootImageOnly = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqImageOnly)).jsonObject
        val fnRespImageOnly = rootImageOnly["contents"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject?.get("functionResponse")?.jsonObject
        assertNotNull(fnRespImageOnly)
        assertEquals("(see attached image)", fnRespImageOnly!!["response"]?.jsonObject?.get("output")?.jsonPrimitive?.content)
    }

    @Test
    fun `cross model assistant reasoning converted to plain text without thought true while same model replays thoughtSignature`() {
        val sameModelParts = buildJsonArray {
            add(buildJsonObject {
                put("thought", JsonPrimitive(true))
                put("text", JsonPrimitive("Step 1 analysis"))
                put("thoughtSignature", JsonPrimitive("sig-same-model"))
            })
            add(buildJsonObject {
                put("text", JsonPrimitive("Final response text"))
            })
        }

        // 1. 同模型精确匹配 (gemini-2.5-flash -> gemini-2.5-flash)：保留原生 parts 与 thoughtSignature
        val sameModelMsg = ChatCompletionMessage(
            role = "assistant",
            content = JsonPrimitive("Final response text"),
            reasoningContent = "Step 1 analysis",
            protocolState = ChatCompletionProtocolState(
                gemini = GeminiMessageProtocolState(
                    sourceModel = "gemini-2.5-flash",
                    parts = sameModelParts
                )
            )
        )
        val reqSame = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(sameModelMsg)
        )
        val rootSame = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqSame)).jsonObject
        val modelPartsSame = rootSame["contents"]?.jsonArray?.firstOrNull()?.jsonObject?.get("parts")?.jsonArray
        assertNotNull(modelPartsSame)
        assertEquals(2, modelPartsSame!!.size)
        assertEquals("sig-same-model", modelPartsSame[0].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals(true, modelPartsSame[0].jsonObject["thought"]?.jsonPrimitive?.content?.toBoolean())

        // 2. 跨模型 (源为 deepseek-r1，目标为 gemini-2.5-flash)：根据 transformMessages 必须过滤 thinking，仅保留 visible text
        val crossModelMsg = ChatCompletionMessage(
            role = "assistant",
            content = JsonPrimitive("Final response text"),
            reasoningContent = "Cross-model thought process",
            protocolState = ChatCompletionProtocolState(
                gemini = GeminiMessageProtocolState(
                    sourceModel = "deepseek-r1",
                    parts = sameModelParts
                )
            )
        )
        val reqCross = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(crossModelMsg)
        )
        val rootCross = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqCross)).jsonObject
        val modelPartsCross = rootCross["contents"]?.jsonArray?.firstOrNull()?.jsonObject?.get("parts")?.jsonArray
        assertNotNull(modelPartsCross)
        // 思考过程必须被完全过滤丢弃，仅保留正文，绝不能发进正文导致模型模仿或泄露为可见回复
        assertEquals(1, modelPartsCross!!.size)
        assertEquals("Final response text", modelPartsCross[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertFalse("不应带有 thought 字段", modelPartsCross[0].jsonObject.containsKey("thought"))
        // 3. 空 sourceModel 也绝不允许误回放签名或 thinking
        val emptySourceMsg = ChatCompletionMessage(
            role = "assistant",
            content = JsonPrimitive("Hello"),
            reasoningContent = "Untracked thought",
            protocolState = ChatCompletionProtocolState(
                gemini = GeminiMessageProtocolState(
                    sourceModel = "",
                    parts = sameModelParts
                )
            )
        )
        val reqEmpty = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(emptySourceMsg)
        )
        val rootEmpty = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqEmpty)).jsonObject
        val emptyParts = rootEmpty["contents"]?.jsonArray?.firstOrNull()?.jsonObject?.get("parts")?.jsonArray
        assertNotNull(emptyParts)
        assertEquals(1, emptyParts!!.size)
        assertEquals("Hello", emptyParts[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertFalse("空 sourceModel 绝不能回放签名", emptyParts.any { it.jsonObject.containsKey("thoughtSignature") })
        assertFalse("空 sourceModel 绝不能带有 thought: true", emptyParts.any { it.jsonObject["thought"]?.jsonPrimitive?.content == "true" })
    }

    @Test
    fun `stream and nonstream preserve multiple tool call ids and parse nonstream reasoning`() {
        // 1. 非流式响应解析：同时解析 thought 思考与保留多个工具调用 ID
        val nonstreamPayload = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {"thought": true, "text": "Analyzing query."},
                      {"text": "Executing parallel actions."},
                      {"functionCall": {"name": "action_one", "args": {"p": 1}, "id": "custom_id_1"}},
                      {"functionCall": {"name": "action_two", "args": {"p": 2}, "id": "custom_id_2"}}
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP",
                  "index": 0
                }
              ]
            }
        """.trimIndent()
        val nonstreamResp = GeminiProtocolAdapter.parseGeminiResponse(
            body = nonstreamPayload,
            parser = ModelSceneRegistry.ResponseParser.TEXT_CONTENT,
            routeTag = "gemini"
        )
        assertTrue(nonstreamResp.success)
        assertEquals("Executing parallel actions.", nonstreamResp.content)
        assertEquals("Analyzing query.", nonstreamResp.reasoning)
        assertEquals("tool_calls", nonstreamResp.finishReason)
        assertEquals(2, nonstreamResp.toolCalls.size)
        assertEquals("custom_id_1", nonstreamResp.toolCalls[0].id)
        assertEquals("custom_id_2", nonstreamResp.toolCalls[1].id)

        // 2. 流式响应：独立空 signature part 能够正确保留
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapGeminiListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }
            }
        )
        val source = dummyEventSource()
        // Chunk 1: 带签名的空 thinking part
        wrapped.onEvent(
            source,
            null,
            null,
            """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"thought": true, "thoughtSignature": "empty-sig-123"}
                        ],
                        "role": "model"
                      },
                      "index": 0
                    }
                  ]
                }
            """.trimIndent()
        )
        // Chunk 2: 正文并结束
        wrapped.onEvent(
            source,
            null,
            null,
            """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "Hello world"}
                        ],
                        "role": "model"
                      },
                      "finishReason": "STOP",
                      "index": 0
                    }
                  ]
                }
            """.trimIndent()
        )
        wrapped.onClosed(source)

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureGeminiParts = true,
            geminiSourceModel = "gemini-2.5-flash"
        )
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        val parts = turn.message.protocolState?.gemini?.parts
        assertNotNull(parts)
        assertTrue(parts!!.isNotEmpty())
        assertEquals("empty-sig-123", parts[0].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `stream preserves separate signed parts without overwriting preceding signature`() {
        val chunks = mutableListOf<String>()
        val wrapped = HttpController.wrapGeminiListener(
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    chunks += data
                }
            }
        )
        val source = dummyEventSource()
        // Chunk 1: 正文带签名 A
        wrapped.onEvent(
            source,
            null,
            null,
            """{"candidates":[{"content":{"parts":[{"text":"Thinking done.","thoughtSignature":"sigA"}],"role":"model"},"index":0}]}"""
        )
        // Chunk 2: 独立签名 part (thought: true, thoughtSignature: sigB)
        wrapped.onEvent(
            source,
            null,
            null,
            """{"candidates":[{"content":{"parts":[{"thought":true,"thoughtSignature":"sigB"}],"role":"model"},"finishReason":"STOP","index":0}]}"""
        )
        wrapped.onClosed(source)

        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            captureGeminiParts = true,
            geminiSourceModel = "gemini-2.5-flash"
        )
        chunks.forEach(accumulator::consume)
        val turn = accumulator.buildTurn()

        val parts = turn.message.protocolState?.gemini?.parts
        assertNotNull(parts)
        assertEquals(2, parts!!.size)
        // Part 0 必须保留自身的 sigA，绝不能被 sigB 覆盖
        assertEquals("sigA", parts[0].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals("Thinking done.", parts[0].jsonObject["text"]?.jsonPrimitive?.content)
        // Part 1 是独立带签名的空 thinking part，保留 sigB
        assertEquals("sigB", parts[1].jsonObject["thoughtSignature"]?.jsonPrimitive?.content)
        assertEquals(true, parts[1].jsonObject["thought"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `explicit strict true on Gemini 3 transforms parametersJsonSchema to strict subset`() {
        val toolWithOptional = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "lookup_db",
                description = "DB lookup tool",
                strict = true,
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("req_field", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("opt_field", buildJsonObject { put("type", JsonPrimitive("number")) })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("req_field")) })
                }
            )
        )
        val toolWithoutStrict = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "loose_tool",
                description = "Loose tool",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("opt_field", buildJsonObject { put("type", JsonPrimitive("number")) })
                    })
                }
            )
        )

        // 1. Gemini 3+ 模型下 strict = true 的工具：additionalProperties 必须为 false，opt_field 变为 nullable anyOf，所有字段进入 required
        val reqGemini3 = ChatCompletionRequest(
            model = "gemini-3-flash",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("query"))),
            tools = listOf(toolWithOptional, toolWithoutStrict)
        )
        val root3 = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqGemini3)).jsonObject
        val decls3 = root3["tools"]?.jsonArray?.firstOrNull()?.jsonObject?.get("functionDeclarations")?.jsonArray
        assertNotNull(decls3)
        val paramsStrict = decls3!![0].jsonObject["parametersJsonSchema"]?.jsonObject
        assertNotNull(paramsStrict)
        assertEquals(false, paramsStrict!!["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
        val requiredList = paramsStrict["required"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertNotNull(requiredList)
        assertTrue(requiredList!!.contains("req_field"))
        assertTrue(requiredList.contains("opt_field"))
        val optFieldSchema = paramsStrict["properties"]?.jsonObject?.get("opt_field")?.jsonObject
        assertNotNull(optFieldSchema)
        assertTrue(optFieldSchema!!.containsKey("anyOf"))
        // nullable 包装不要复制 description 到外层
        assertFalse(optFieldSchema.containsKey("description"))

        // 省略 strict 的工具 parametersJsonSchema 保持不变，不默认 strict
        val paramsLoose = decls3[1].jsonObject["parametersJsonSchema"]?.jsonObject
        assertNotNull(paramsLoose)
        assertFalse(paramsLoose!!.containsKey("additionalProperties"))

        // 2. Gemini < 3 模型 (如 gemini-2.5-flash)：即便写了 strict = true 也不转为 strict schema
        val reqGemini2 = ChatCompletionRequest(
            model = "gemini-2.5-flash",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("query"))),
            tools = listOf(toolWithOptional)
        )
        val root2 = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(reqGemini2)).jsonObject
        val decls2 = root2["tools"]?.jsonArray?.firstOrNull()?.jsonObject?.get("functionDeclarations")?.jsonArray
        val paramsGemini2 = decls2!![0].jsonObject["parametersJsonSchema"]?.jsonObject
        assertFalse("Gemini < 3 不应应用 makeStrictJsonSchema", paramsGemini2!!.containsKey("additionalProperties"))

        // 3. 严格 schema 必须拒绝不支持的关键字集合与非法配置（显式 strict = true 本地失败，不静默改语义）
        val toolWithDefs = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "bad_tool",
                strict = true,
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("\$defs", buildJsonObject {})
                }
            )
        )
        var failedWithDefs = false
        try {
            GeminiProtocolAdapter.convertToGeminiRequestJson(ChatCompletionRequest(
                model = "gemini-3-flash",
                messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hi"))),
                tools = listOf(toolWithDefs)
            ))
        } catch (e: IllegalArgumentException) {
            failedWithDefs = true
            assertTrue(e.message!!.contains("\$defs"))
        }
        assertTrue("显式 strict=true 包含 \$defs 必须本地失败", failedWithDefs)

        val toolWithAddPropsTrue = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "bad_tool2",
                strict = true,
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", JsonPrimitive(true))
                }
            )
        )
        var failedWithAddProps = false
        try {
            GeminiProtocolAdapter.convertToGeminiRequestJson(ChatCompletionRequest(
                model = "gemini-3-flash",
                messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hi"))),
                tools = listOf(toolWithAddPropsTrue)
            ))
        } catch (e: IllegalArgumentException) {
            failedWithAddProps = true
            assertTrue(e.message!!.contains("additionalProperties"))
        }
        assertTrue("显式 strict=true 包含 additionalProperties:true 必须本地失败", failedWithAddProps)

        // 4. 非 strict 工具允许包含 $defs，保持完整 JSON Schema 原样不受限
        val toolWithDefsNonStrict = ChatCompletionTool(
            type = "function",
            function = ChatCompletionFunction(
                name = "loose_defs_tool",
                strict = false,
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("\$defs", buildJsonObject {})
                }
            )
        )
        val rootNonStrict = json.parseToJsonElement(GeminiProtocolAdapter.convertToGeminiRequestJson(ChatCompletionRequest(
            model = "gemini-3-flash",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hi"))),
            tools = listOf(toolWithDefsNonStrict)
        ))).jsonObject
        val nonStrictParams = rootNonStrict["tools"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("functionDeclarations")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("parametersJsonSchema")?.jsonObject
        assertNotNull(nonStrictParams)
        assertTrue(nonStrictParams!!.containsKey("\$defs"))
    }

    private fun dummyEventSource(): EventSource {
        return object : EventSource {
            override fun request(): Request = Request.Builder().url("https://example.com").build()
            override fun cancel() = Unit
        }
    }
}
