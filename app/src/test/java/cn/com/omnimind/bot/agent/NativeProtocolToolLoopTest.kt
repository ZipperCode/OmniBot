package cn.com.omnimind.bot.agent

import android.app.Application
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证 Gemini 与 Anthropic 原生协议工具调用循环（Native Protocol Tool Loop）的真实回归测试。
 *
 * 核心验证范围：
 * 1. 真实端到端链路：真实 AgentOrchestrator + 真实 HttpAgentLlmClient + 本机 ServerSocket HTTP/SSE 传输 + 真实本地文件读写副作用。
 * 2. 多轮多工具交互：
 *    - 首轮模型 HTTP SSE 响应下发 2 个自带 id 的工具调用（write_temp_file 写入临时文件，capture_screen_error 模拟错误并携带 1 像素 PNG 图片）。
 *    - Orchestrator 真实执行本地工具副作用并投影第二轮请求。
 *    - 服务端严格校验两工具的结果 id、name、error 标记、输出内容、Gemini 3+/Anthropic 图片结构、以及签名思考块（thoughtSignature / signature）的原样回传。
 *    - 服务端第二轮下发 read_temp_file 工具调用，Orchestrator 真实从本地读取文件内容并回传，验证文件真实写入而非单纯 mock echo。
 *    - 服务端第三轮返回最终回答，断言最终结果为 AgentResult.Success，且多轮交互角色严格交替（无重复连续 user 角色）。
 * 3. 截断保护（MAX_TOKENS / max_tokens）：
 *    - 当模型在包含工具调用时因达到 token 上限返回 MAX_TOKENS 时，Orchestrator 严格禁止触发任何本地工具副作用（文件不生成，toolExecutor.execute 不被调用）。
 * 4. 流式传输中途取消：
 *    - 当 ServerSocket 开始输出流式内容（思考/文本）后，取消外部 Orchestrator 协程，验证请求及时中止断开，向外抛出 CancellationException 且不产生 Success 结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class NativeProtocolToolLoopTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    companion object {
        // 1 像素透明 PNG 的标准 Base64 编码，用于验证工具返回图片的完整保留与投影
        private const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    }

    // ==================== 1. Gemini 原生工具循环回归 ====================

    @Test
    fun `gemini real tool loop preserves native headers, signed thinking, file side effects, tool images, and distinct event ids`() {
        runRealToolLoopTest(
            protocol = "gemini",
            model = "gemini-3-flash"
        )
    }

    // ==================== 2. Anthropic 原生工具循环回归 ====================

    @Test
    fun `anthropic real tool loop preserves native headers, signed thinking, file side effects, tool images, and distinct event ids`() {
        runRealToolLoopTest(
            protocol = "anthropic",
            model = "claude-3-7-sonnet"
        )
    }

    // ==================== 3. Gemini MAX_TOKENS 不触发本地副作用 ====================

    @Test
    fun `gemini tool call capped by MAX_TOKENS does not execute local side effects`() {
        runMaxTokensNoSideEffectsTest(
            protocol = "gemini",
            model = "gemini-3-flash"
        )
    }

    // ==================== 4. Anthropic max_tokens 不触发本地副作用 ====================

    @Test
    fun `anthropic tool call capped by max_tokens does not execute local side effects`() {
        runMaxTokensNoSideEffectsTest(
            protocol = "anthropic",
            model = "claude-3-7-sonnet"
        )
    }

    // ==================== 5. Gemini 流式中途取消 ====================

    @Test
    fun `gemini cancellation during sse stream throws CancellationException and aborts request`() {
        runCancellationDuringSseStreamTest(
            protocol = "gemini",
            model = "gemini-3-flash"
        )
    }

    // ==================== 6. Anthropic 流式中途取消 ====================

    @Test
    fun `anthropic cancellation during sse stream throws CancellationException and aborts request`() {
        runCancellationDuringSseStreamTest(
            protocol = "anthropic",
            model = "claude-3-7-sonnet"
        )
    }

    // ==================== 核心通用执行逻辑 ====================

    /**
     * 运行三轮原生工具循环测试：
     * Turn 1: 用户输入 -> 模型返回两工具 (写临时文件 + 报错并带图) 以及 signed thinking。
     * Turn 2: Orchestrator 执行本地副作用 -> 服务端验证签名保留、工具结果匹配、图片结构 -> 模型返回读文件工具。
     * Turn 3: Orchestrator 真实读文件 -> 服务端验证读回内容 -> 模型返回最终文字回复。
     */
    private fun runRealToolLoopTest(
        protocol: String,
        model: String
    ) = runBlocking {
        val tempFile = File(temporaryFolder.root, "loop_test_${protocol}_${System.currentTimeMillis()}.txt")
        if (tempFile.exists()) tempFile.delete()
        assertFalse("测试前临时文件不得存在", tempFile.exists())

        val testContent = "Hello native tool loop for $protocol! Timestamp=${System.currentTimeMillis()}"
        val toolCatalog = FixtureToolCatalog(tempFile.absolutePath)
        val toolExecutor = FixtureToolExecutor()

        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val apiKey = "test-api-key-$protocol"
        val serverError = AtomicReference<Throwable?>()

        val serverThread = thread(name = "test-native-server-$protocol") {
            try {
                serverSocket.use { socketServer ->
                    // ---------------- Turn 1: 接收首轮用户消息，返回两工具调用 + 签名思考 ----------------
                    val socket1 = socketServer.accept()
                    socket1.use { client ->
                        val (headers1, body1) = readHttpRequest(client)
                        assertRequestHeaderAndPath(protocol, headers1, model, apiKey)
                        assertTrue("Turn 1 请求体必须包含初始用户 prompt", body1.contains("Please execute native tool loop"))

                        val sseTurn1Events = if (protocol == "gemini") {
                            buildGeminiTurn1Sse(tempFile.absolutePath, testContent)
                        } else {
                            buildAnthropicTurn1Sse(tempFile.absolutePath, testContent)
                        }
                        writeSseResponse(client, sseTurn1Events)
                    }

                    // ---------------- Turn 2: 验证两个工具执行结果、签名思考、图片结构，并下发读文件工具 ----------------
                    val socket2 = socketServer.accept()
                    socket2.use { client ->
                        val (headers2, body2) = readHttpRequest(client)
                        assertRequestHeaderAndPath(protocol, headers2, model, apiKey)
                        assertTurn2ToolResultsAndThinking(protocol, body2)

                        val sseTurn2Events = if (protocol == "gemini") {
                            buildGeminiTurn2Sse(tempFile.absolutePath)
                        } else {
                            buildAnthropicTurn2Sse(tempFile.absolutePath)
                        }
                        writeSseResponse(client, sseTurn2Events)
                    }

                    // ---------------- Turn 3: 验证真实文件内容读回，返回最终总结文本 ----------------
                    val socket3 = socketServer.accept()
                    socket3.use { client ->
                        val (headers3, body3) = readHttpRequest(client)
                        assertRequestHeaderAndPath(protocol, headers3, model, apiKey)
                        assertTurn3FileContentVerified(protocol, body3, testContent)

                        val sseTurn3Events = if (protocol == "gemini") {
                            buildGeminiTurn3Sse(testContent)
                        } else {
                            buildAnthropicTurn3Sse(testContent)
                        }
                        writeSseResponse(client, sseTurn3Events)
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val llmClient = HttpAgentLlmClient(
                scope = scope,
                modelOverride = AgentModelOverride(
                    providerProfileId = "test-profile-$protocol",
                    modelId = model,
                    apiBase = "http://127.0.0.1:$port",
                    apiKey = apiKey,
                    protocolType = protocol
                )
            )

            val orchestrator = AgentOrchestrator(
                llmClient = llmClient,
                toolRegistry = toolCatalog,
                toolRouter = toolExecutor,
                eventAdapter = AgentEventAdapter(testJson),
                model = model
            )

            val callback = FixtureAgentCallback()
            val env = buildTestEnvironment("Please execute native tool loop")
            val input = AgentOrchestrator.Input(
                callback = callback,
                initialMessages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive("Please execute native tool loop")
                    )
                ),
                executionEnv = env
            )

            withTimeout(15_000L) {
                val result = orchestrator.run(input)
                serverThread.join(5_000L)

                val err = serverError.get()
                if (err != null) {
                    throw AssertionError("Mock server 断言失败: ${err.message}", err)
                }

                // 判据 1：最终结果必须为 AgentResult.Success，且包含最终文本
                assertTrue("最终执行结果必须为 AgentResult.Success", result is AgentResult.Success)
                val successResult = result as AgentResult.Success
                assertTrue(
                    "最终内容必须包含读回文件的确认文字: ${successResult.response.content}",
                    successResult.response.content.contains("File verification complete: $testContent")
                )

                // 判据 2：本地真实文件确实被写入，且内容与预期严格一致
                assertTrue("本地文件必须被真实创建在文件系统中", tempFile.exists())
                assertEquals(
                    "本地文件内容必须与写入内容完全一致",
                    testContent,
                    tempFile.readText(StandardCharsets.UTF_8)
                )

                // 判据 3：真实工具调用的 distinct event id 完整触发
                val startIds = callback.toolStarts.map { it.id }
                val completeIds = callback.toolCompletes.map { it.id }
                assertEquals("总计应触发 3 次工具调用开始", 3, startIds.size)
                assertEquals("所有工具调用 ID 必须全局唯一", 3, startIds.distinct().size)
                assertEquals(
                    "工具调用开始序列 ID 必须匹配",
                    listOf("call_write_1", "call_error_2", "call_read_3"),
                    startIds
                )
                assertEquals(
                    "工具调用完成序列 ID 必须匹配",
                    listOf("call_write_1", "call_error_2", "call_read_3"),
                    completeIds
                )

                // 判据 4：tool 结果状态必须正确反映成功与失败
                val writeComplete = callback.toolCompletes[0]
                assertTrue("写文件工具结果必须为成功", writeComplete.result is ToolExecutionResult.ContextResult && writeComplete.result.success)

                val errorComplete = callback.toolCompletes[1]
                assertTrue("截图错误工具结果必须为失败", errorComplete.result is ToolExecutionResult.ContextResult && !errorComplete.result.success)

                val readComplete = callback.toolCompletes[2]
                assertTrue("读文件工具结果必须为成功", readComplete.result is ToolExecutionResult.ContextResult && readComplete.result.success)
            }
        } finally {
            scope.cancel()
            serverSocket.close()
        }
    }

    /**
     * 运行截断保护测试：当模型因 MAX_TOKENS / max_tokens 终止并夹带工具调用时，
     * Orchestrator 严格禁止执行任何本地副作用。
     */
    private fun runMaxTokensNoSideEffectsTest(
        protocol: String,
        model: String
    ) = runBlocking {
        val tempFile = File(temporaryFolder.root, "never_created_${protocol}_${System.currentTimeMillis()}.txt")
        if (tempFile.exists()) tempFile.delete()
        assertFalse("测试前文件不得存在", tempFile.exists())

        val toolCatalog = FixtureToolCatalog(tempFile.absolutePath)
        val toolExecutor = FixtureToolExecutor()

        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val apiKey = "test-api-key-$protocol"
        val serverError = AtomicReference<Throwable?>()

        val serverThread = thread(name = "test-maxtokens-server-$protocol") {
            try {
                serverSocket.use { socketServer ->
                    // Turn 1: 客户端发起请求，服务端返回含工具调用但 finishReason 为 MAX_TOKENS / max_tokens 的响应
                    val socket1 = socketServer.accept()
                    socket1.use { client ->
                        val (headers1, _) = readHttpRequest(client)
                        assertRequestHeaderAndPath(protocol, headers1, model, apiKey)

                        val sseTurn1Events = if (protocol == "gemini") {
                            listOf(
                                "data: " + JSONObject().apply {
                                    put("candidates", JSONArray().put(JSONObject().apply {
                                        put("content", JSONObject().apply {
                                            put("role", "model")
                                            put("parts", JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("functionCall", JSONObject().apply {
                                                        put("name", "write_temp_file")
                                                        put("id", "call_write_blocked")
                                                        put("args", JSONObject().apply {
                                                            put("path", tempFile.absolutePath)
                                                            put("content", "illegal side effect content")
                                                        })
                                                    })
                                                })
                                            })
                                        })
                                        put("finishReason", "MAX_TOKENS")
                                        put("index", 0)
                                    }))
                                }.toString() + "\n\n",
                                "data: [DONE]\n\n"
                            )
                        } else {
                            listOf(
                                "event: message_start\ndata: " + JSONObject().apply {
                                    put("type", "message_start")
                                    put("message", JSONObject().apply {
                                        put("usage", JSONObject().apply {
                                            put("input_tokens", 20)
                                            put("output_tokens", 5)
                                        })
                                    })
                                }.toString() + "\n\n",
                                "event: content_block_start\ndata: " + JSONObject().apply {
                                    put("type", "content_block_start")
                                    put("index", 0)
                                    put("content_block", JSONObject().apply {
                                        put("type", "tool_use")
                                        put("id", "call_write_blocked")
                                        put("name", "write_temp_file")
                                        put("input", JSONObject())
                                    })
                                }.toString() + "\n\n",
                                "event: content_block_delta\ndata: " + JSONObject().apply {
                                    put("type", "content_block_delta")
                                    put("index", 0)
                                    put("delta", JSONObject().apply {
                                        put("type", "input_json_delta")
                                        put("partial_json", JSONObject().apply {
                                            put("path", tempFile.absolutePath)
                                            put("content", "illegal side effect content")
                                        }.toString())
                                    })
                                }.toString() + "\n\n",
                                "event: content_block_stop\ndata: " + JSONObject().apply {
                                    put("type", "content_block_stop")
                                    put("index", 0)
                                }.toString() + "\n\n",
                                "event: message_delta\ndata: " + JSONObject().apply {
                                    put("type", "message_delta")
                                    put("delta", JSONObject().apply {
                                        put("stop_reason", "max_tokens")
                                    })
                                    put("usage", JSONObject().apply {
                                        put("output_tokens", 5)
                                    })
                                }.toString() + "\n\n",
                                "event: message_stop\ndata: " + JSONObject().apply {
                                    put("type", "message_stop")
                                }.toString() + "\n\n"
                            )
                        }
                        writeSseResponse(client, sseTurn1Events)
                    }

                    // Turn 2: Orchestrator 拦截截断工具，发送合成错误结果，服务端验证错误信息并收尾
                    val socket2 = socketServer.accept()
                    socket2.use { client ->
                        val (_, body2) = readHttpRequest(client)
                        assertTrue(
                            "Turn 2 请求体中必须包含工具因长度上限未执行的提示: $body2",
                            body2.contains("长度上限") || body2.contains("length limit") ||
                                body2.contains("max_tokens") || body2.contains("未执行")
                        )

                        val sseTurn2Events = if (protocol == "gemini") {
                            listOf(
                                "data: " + JSONObject().apply {
                                    put("candidates", JSONArray().put(JSONObject().apply {
                                        put("content", JSONObject().apply {
                                            put("role", "model")
                                            put("parts", JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("text", "Execution halted due to token length limit.")
                                                })
                                            })
                                        })
                                        put("finishReason", "STOP")
                                        put("index", 0)
                                    }))
                                }.toString() + "\n\n",
                                "data: [DONE]\n\n"
                            )
                        } else {
                            listOf(
                                "event: message_start\ndata: " + JSONObject().apply {
                                    put("type", "message_start")
                                    put("message", JSONObject().apply {
                                        put("usage", JSONObject().apply {
                                            put("input_tokens", 35)
                                            put("output_tokens", 6)
                                        })
                                    })
                                }.toString() + "\n\n",
                                "event: content_block_start\ndata: " + JSONObject().apply {
                                    put("type", "content_block_start")
                                    put("index", 0)
                                    put("content_block", JSONObject().apply {
                                        put("type", "text")
                                        put("text", "Execution halted due to token length limit.")
                                    })
                                }.toString() + "\n\n",
                                "event: content_block_stop\ndata: " + JSONObject().apply {
                                    put("type", "content_block_stop")
                                    put("index", 0)
                                }.toString() + "\n\n",
                                "event: message_delta\ndata: " + JSONObject().apply {
                                    put("type", "message_delta")
                                    put("delta", JSONObject().apply {
                                        put("stop_reason", "end_turn")
                                    })
                                    put("usage", JSONObject().apply {
                                        put("output_tokens", 6)
                                    })
                                }.toString() + "\n\n",
                                "event: message_stop\ndata: " + JSONObject().apply {
                                    put("type", "message_stop")
                                }.toString() + "\n\n"
                            )
                        }
                        writeSseResponse(client, sseTurn2Events)
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val llmClient = HttpAgentLlmClient(
                scope = scope,
                modelOverride = AgentModelOverride(
                    providerProfileId = "test-profile-$protocol",
                    modelId = model,
                    apiBase = "http://127.0.0.1:$port",
                    apiKey = apiKey,
                    protocolType = protocol
                )
            )

            val orchestrator = AgentOrchestrator(
                llmClient = llmClient,
                toolRegistry = toolCatalog,
                toolRouter = toolExecutor,
                eventAdapter = AgentEventAdapter(testJson),
                model = model
            )

            val callback = FixtureAgentCallback()
            val env = buildTestEnvironment("Write never_created file")
            val input = AgentOrchestrator.Input(
                callback = callback,
                initialMessages = listOf(
                    ChatCompletionMessage(
                        role = "user",
                        content = JsonPrimitive("Write never_created file")
                    )
                ),
                executionEnv = env
            )

            withTimeout(15_000L) {
                val result = orchestrator.run(input)
                serverThread.join(5_000L)

                val err = serverError.get()
                if (err != null) {
                    throw AssertionError("Mock server 断言失败: ${err.message}", err)
                }

                // 核心安全判据：本地副作用绝不应执行
                assertFalse("在达到 MAX_TOKENS 时，文件绝不能被写入到磁盘", tempFile.exists())
                assertTrue("在达到 MAX_TOKENS 时，toolExecutor.execute 绝不能被调用", toolExecutor.executedCalls.isEmpty())

                // Orchestrator 正常安全完成
                assertTrue("Orchestrator 结果必须为 Success", result is AgentResult.Success)
                assertEquals("应该记录 1 次截断跳过的工具回调", 1, callback.toolCompletes.size)
                val completeRecord = callback.toolCompletes.single()
                assertEquals("call_write_blocked", completeRecord.id)
                assertTrue("被拦截的工具结果类型必须为 Error", completeRecord.result is ToolExecutionResult.Error)
                val errorMsg = (completeRecord.result as ToolExecutionResult.Error).message
                assertTrue(
                    "错误信息必须包含长度上限提示: $errorMsg",
                    errorMsg.contains("长度上限") || errorMsg.contains("length limit") || errorMsg.contains("截断")
                )
            }
        } finally {
            scope.cancel()
            serverSocket.close()
        }
    }

    /**
     * 运行流式中途取消测试：当真实 ServerSocket 开始向客户端推送 SSE 流式事件时，
     * 取消外部 Orchestrator 协程，观察请求被即时取消断开，向外抛出 CancellationException 且不产生 Success。
     */
    private fun runCancellationDuringSseStreamTest(
        protocol: String,
        model: String
    ) = runBlocking {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val apiKey = "test-key-cancel-$protocol"
        val serverCompleted = CompletableDeferred<Unit>()
        val serverError = AtomicReference<Throwable?>()
        val serverThread = thread(name = "test-cancel-server-$protocol") {
            try {
                serverSocket.use { socketServer ->
                    val socket = socketServer.accept()
                    socket.use { client ->
                        val (headers, _) = readHttpRequest(client)
                        assertRequestHeaderAndPath(protocol, headers, model, apiKey)

                        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
                        writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                        writer.flush()

                        // 发送首个思考/内容事件
                        if (protocol == "gemini") {
                            val firstEvent = JSONObject().apply {
                                put("candidates", JSONArray().put(JSONObject().apply {
                                    put("content", JSONObject().apply {
                                        put("role", "model")
                                        put("parts", JSONArray().apply {
                                            put(JSONObject().apply {
                                                put("thought", true)
                                                put("text", "Gemini 思考流已启动...")
                                            })
                                        })
                                    })
                                }))
                            }.toString()
                            writer.write("data: $firstEvent\n\n")
                            writer.flush()
                        } else {
                            writer.write("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n")
                            writer.flush()
                            writer.write("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n")
                            writer.flush()
                            writer.write("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Anthropic 思考流已启动...\"}}\n\n")
                            writer.flush()
                        }
                        // 保持连接并等待客户端 cancel 主动关闭/重置 socket
                        try {
                            val input = client.getInputStream()
                            val dummy = ByteArray(128)
                            while (input.read(dummy) != -1) {
                                // 等待客户端关闭
                            }
                        } catch (_: Exception) {
                            // 客户端 cancel 导致连接断开，符合预期
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            } finally {
                serverCompleted.complete(Unit)
            }
        }

        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val llmClient = HttpAgentLlmClient(
                scope = scope,
                modelOverride = AgentModelOverride(
                    providerProfileId = "test-profile-cancel-$protocol",
                    modelId = model,
                    apiBase = "http://127.0.0.1:$port",
                    apiKey = apiKey,
                    protocolType = protocol
                )
            )

            val toolCatalog = FixtureToolCatalog("/tmp/unused.txt")
            val toolExecutor = FixtureToolExecutor()

            val orchestrator = AgentOrchestrator(
                llmClient = llmClient,
                toolRegistry = toolCatalog,
                toolRouter = toolExecutor,
                eventAdapter = AgentEventAdapter(testJson),
                model = model
            )

            val callback = FixtureAgentCallback()
            val env = buildTestEnvironment("Cancel stream test")
            val input = AgentOrchestrator.Input(
                callback = callback,
                initialMessages = listOf(
                    ChatCompletionMessage(role = "user", content = JsonPrimitive("Cancel stream test"))
                ),
                executionEnv = env
            )

            withTimeout(10_000L) {
                // 在异步子任务中启动 Orchestrator
                val orchestratorJob = scope.async {
                    orchestrator.run(input)
                }

                // 核心同步点：必须等待客户端实际接收并解析到首个思考/文本事件后，才触发取消
                callback.firstClientEventObserved.await()

                // 外部触发取消（中文取消文案）
                orchestratorJob.cancel(CancellationException("用户主动取消当前轮次执行"))

                // 观察：协程必须抛出 CancellationException
                var threwCancellation = false
                try {
                    orchestratorJob.await()
                } catch (_: CancellationException) {
                    threwCancellation = true
                }
                assertTrue("Orchestrator 协程取消后必须向外抛出 CancellationException", threwCancellation)

                // 核心断言：未成功完成，绝不能产生 AgentResult.Success
                assertNull("取消后 callback.completedResult 绝不能为 Success", callback.completedResult)

                // 验证服务端完成
                serverCompleted.await()
                val err = serverError.get()
                if (err != null) throw AssertionError("Mock server 异常: ${err.message}", err)
            }
        } finally {
            scope.cancel()
            serverSocket.close()
        }
    }

    // ==================== 服务端断言辅助函数 ====================

    private fun assertRequestHeaderAndPath(
        protocol: String,
        headers: List<String>,
        model: String,
        apiKey: String
    ) {
        val requestLine = headers.firstOrNull().orEmpty()
        if (protocol == "gemini") {
            assertTrue(
                "Gemini 请求路径必须符合 streamGenerateContent 规范: $requestLine",
                requestLine.contains("/v1beta/models/$model:streamGenerateContent") ||
                    requestLine.contains("/models/$model:streamGenerateContent")
            )
            val apiKeyHeader = headers.firstOrNull { it.startsWith("x-goog-api-key:", ignoreCase = true) }
            assertNotNull("Gemini 必须携带 x-goog-api-key 请求头", apiKeyHeader)
            assertEquals("x-goog-api-key 内容必须匹配", apiKey, apiKeyHeader!!.substringAfter(":").trim())
        } else {
            assertTrue(
                "Anthropic 请求路径必须符合 /v1/messages 规范: $requestLine",
                requestLine.contains("/v1/messages")
            )
            val apiKeyHeader = headers.firstOrNull { it.startsWith("x-api-key:", ignoreCase = true) }
            assertNotNull("Anthropic 必须携带 x-api-key 请求头", apiKeyHeader)
            assertEquals("x-api-key 内容必须匹配", apiKey, apiKeyHeader!!.substringAfter(":").trim())

            val versionHeader = headers.firstOrNull { it.startsWith("anthropic-version:", ignoreCase = true) }
            assertNotNull("Anthropic 必须携带 anthropic-version 请求头", versionHeader)
            assertEquals("anthropic-version 必须为 2023-06-01", "2023-06-01", versionHeader!!.substringAfter(":").trim())
        }
    }

    private fun assertTurn2ToolResultsAndThinking(protocol: String, body: String) {
        if (protocol == "gemini") {
            val root = JSONObject(body)
            val contents = root.getJSONArray("contents")
            // 校验没有重复连续的 user 角色
            for (i in 0 until contents.length() - 1) {
                val r1 = contents.getJSONObject(i).getString("role")
                val r2 = contents.getJSONObject(i + 1).getString("role")
                assertFalse("Gemini 多轮次角色必须严格交替，不得有重复连续角色: $r1 at index $i", r1 == r2)
            }

            // 1. 验证 model 轮次保留 signed thinking 和 parts
            val modelTurn = contents.getJSONObject(1)
            assertEquals("model", modelTurn.getString("role"))
            val modelParts = modelTurn.getJSONArray("parts")
            var foundThoughtSig = false
            var foundWriteSig = false
            var foundErrorSig = false
            for (i in 0 until modelParts.length()) {
                val part = modelParts.getJSONObject(i)
                val sig = part.optString("thoughtSignature")
                if (sig == "gemini-thought-sig-1") foundThoughtSig = true
                if (sig == "gemini-write-sig-1") foundWriteSig = true
                if (sig == "gemini-error-sig-2") foundErrorSig = true
                val fnCall = part.optJSONObject("functionCall")
                if (fnCall != null) {
                    val fnSig = fnCall.optString("thoughtSignature")
                    if (fnSig == "gemini-write-sig-1") foundWriteSig = true
                    if (fnSig == "gemini-error-sig-2") foundErrorSig = true
                }
            }
            assertTrue("Gemini 必须完整保留思考块签名 gemini-thought-sig-1", foundThoughtSig)
            assertTrue("Gemini 必须完整保留写工具签名 gemini-write-sig-1", foundWriteSig)
            assertTrue("Gemini 必须完整保留错误工具签名 gemini-error-sig-2", foundErrorSig)

            // 2. 验证工具响应轮次
            val userToolTurn = contents.getJSONObject(2)
            assertEquals("user", userToolTurn.getString("role"))
            val userParts = userToolTurn.getJSONArray("parts")
            var foundWriteResp = false
            var foundErrorResp = false
            var foundGeminiImage = false

            for (i in 0 until userParts.length()) {
                val part = userParts.getJSONObject(i)
                val fnResp = part.optJSONObject("functionResponse")
                if (fnResp != null) {
                    val name = fnResp.optString("name")
                    val id = fnResp.optString("id")
                    if (name == "write_temp_file" || id == "call_write_1") {
                        foundWriteResp = true
                        val resp = fnResp.optJSONObject("response")
                        assertNotNull("write_temp_file 响应体必须存在", resp)
                        assertTrue("write_temp_file 响应体必须包含成功信息", resp!!.has("output") || resp.toString().contains("ok"))
                    }
                    if (name == "capture_screen_error" || id == "call_error_2") {
                        foundErrorResp = true
                        val resp = fnResp.optJSONObject("response")
                        assertNotNull("capture_screen_error 响应体必须存在", resp)
                        assertTrue("capture_screen_error 响应体必须包含 error 字段: $resp", resp!!.has("error") || resp.toString().contains("error"))

                        // Gemini 3+ 契约：图片内嵌在 functionResponse.parts 数组中
                        val fnParts = fnResp.optJSONArray("parts")
                        if (fnParts != null) {
                            for (j in 0 until fnParts.length()) {
                                val inlineData = fnParts.getJSONObject(j).optJSONObject("inlineData")
                                if (inlineData != null && inlineData.optString("data") == ONE_PIXEL_PNG_BASE64) {
                                    foundGeminiImage = true
                                }
                            }
                        }
                    }
                }
                // 兼容低于 Gemini 3 版本的紧随其后的独立 inlineData part
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null && inlineData.optString("data") == ONE_PIXEL_PNG_BASE64) {
                    foundGeminiImage = true
                }
            }
            assertTrue("Gemini 工具响应中必须包含 write_temp_file", foundWriteResp)
            assertTrue("Gemini 工具响应中必须包含 capture_screen_error", foundErrorResp)

            // 3. 验证图片数据未丢失（内嵌 parts 或请求体保留）
            assertTrue("Gemini 第二轮请求中必须包含工具图片数据", foundGeminiImage || body.contains(ONE_PIXEL_PNG_BASE64))
        } else {
            val root = JSONObject(body)
            val messages = root.getJSONArray("messages")
            // 校验没有重复连续的 user 角色
            for (i in 0 until messages.length() - 1) {
                val r1 = messages.getJSONObject(i).getString("role")
                val r2 = messages.getJSONObject(i + 1).getString("role")
                assertFalse("Anthropic 多轮次角色必须严格交替，不得有重复连续角色: $r1 at index $i", r1 == r2)
            }

            // 1. 验证 assistant 轮次保留 signed thinking
            val assistantTurn = messages.getJSONObject(1)
            assertEquals("assistant", assistantTurn.getString("role"))
            val assistantBlocks = assistantTurn.getJSONArray("content")
            var foundThoughtSig = false
            for (i in 0 until assistantBlocks.length()) {
                val block = assistantBlocks.getJSONObject(i)
                if (block.optString("type") == "thinking" && block.optString("signature") == "anthropic-sig-turn1") {
                    foundThoughtSig = true
                }
            }
            assertTrue("Anthropic 必须完整保留思考块签名 anthropic-sig-turn1", foundThoughtSig)

            // 2. 验证 tool_result
            val userToolTurn = messages.getJSONObject(2)
            assertEquals("user", userToolTurn.getString("role"))
            val userBlocks = userToolTurn.getJSONArray("content")
            var foundWriteResult = false
            var foundErrorResult = false
            var foundAnthropicImageBlock = false

            for (i in 0 until userBlocks.length()) {
                val block = userBlocks.getJSONObject(i)
                if (block.optString("type") == "tool_result") {
                    val toolUseId = block.optString("tool_use_id")
                    if (toolUseId == "call_write_1") {
                        foundWriteResult = true
                        assertFalse("write_temp_file 的 is_error 必须为 false", block.optBoolean("is_error", false))
                    }
                    if (toolUseId == "call_error_2") {
                        foundErrorResult = true
                        assertTrue("capture_screen_error 的 is_error 必须为 true", block.optBoolean("is_error", false))
                    }
                }
                // Anthropic 契约：错误工具的图片剥离后置为当前 user 消息 content 数组中的 image block
                if (block.optString("type") == "image") {
                    val source = block.optJSONObject("source")
                    if (source != null && source.optString("data") == ONE_PIXEL_PNG_BASE64) {
                        foundAnthropicImageBlock = true
                    }
                }
            }
            assertTrue("Anthropic 用户轮次中必须包含 call_write_1 tool_result", foundWriteResult)
            assertTrue("Anthropic 用户轮次中必须包含 call_error_2 tool_result", foundErrorResult)

            // 3. 验证图片数据未丢失
            assertTrue("Anthropic 第二轮请求中必须包含后置 image block 或有效图片数据", foundAnthropicImageBlock || body.contains(ONE_PIXEL_PNG_BASE64))
        }
    }

    private fun assertTurn3FileContentVerified(protocol: String, body: String, expectedContent: String) {
        if (protocol == "gemini") {
            val root = JSONObject(body)
            val contents = root.getJSONArray("contents")
            for (i in 0 until contents.length() - 1) {
                val r1 = contents.getJSONObject(i).getString("role")
                val r2 = contents.getJSONObject(i + 1).getString("role")
                assertFalse("Gemini Turn 3 角色必须严格交替: $r1 at index $i", r1 == r2)
            }
            val lastTurn = contents.getJSONObject(contents.length() - 1)
            assertEquals("user", lastTurn.getString("role"))
            val parts = lastTurn.getJSONArray("parts")
            var readContentMatched = false
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val fnResp = part.optJSONObject("functionResponse")
                if (fnResp != null && (fnResp.optString("name") == "read_temp_file" || fnResp.optString("id") == "call_read_3")) {
                    val resp = fnResp.optJSONObject("response")
                    if (resp != null && resp.toString().contains(expectedContent)) {
                        readContentMatched = true
                    }
                }
            }
            assertTrue("Gemini 第三轮请求必须包含读取到的真实文件内容", readContentMatched)
        } else {
            val root = JSONObject(body)
            val messages = root.getJSONArray("messages")
            for (i in 0 until messages.length() - 1) {
                val r1 = messages.getJSONObject(i).getString("role")
                val r2 = messages.getJSONObject(i + 1).getString("role")
                assertFalse("Anthropic Turn 3 角色必须严格交替: $r1 at index $i", r1 == r2)
            }
            val lastMsg = messages.getJSONObject(messages.length() - 1)
            assertEquals("user", lastMsg.getString("role"))
            val blocks = lastMsg.getJSONArray("content")
            var readContentMatched = false
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                if (block.optString("type") == "tool_result" && block.optString("tool_use_id") == "call_read_3") {
                    val content = block.opt("content").toString()
                    if (content.contains(expectedContent)) {
                        readContentMatched = true
                    }
                }
            }
            assertTrue("Anthropic 第三轮请求必须包含读取到的真实文件内容", readContentMatched)
        }
    }

    // ==================== SSE 构建辅助函数 ====================

    private fun buildGeminiTurn1Sse(tempFilePath: String, testContent: String): List<String> {
        val candidate = JSONObject().apply {
            put("content", JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("thought", true)
                        put("text", "Plan: write file and verify error with screenshot")
                        put("thoughtSignature", "gemini-thought-sig-1")
                    })
                    put(JSONObject().apply {
                        put("functionCall", JSONObject().apply {
                            put("name", "write_temp_file")
                            put("id", "call_write_1")
                            put("args", JSONObject().apply {
                                put("path", tempFilePath)
                                put("content", testContent)
                            })
                        })
                        put("thoughtSignature", "gemini-write-sig-1")
                    })
                    put(JSONObject().apply {
                        put("functionCall", JSONObject().apply {
                            put("name", "capture_screen_error")
                            put("id", "call_error_2")
                            put("args", JSONObject().apply {
                                put("reason", "verify_ui")
                            })
                        })
                        put("thoughtSignature", "gemini-error-sig-2")
                    })
                })
            })
            put("finishReason", "STOP")
            put("index", 0)
        }
        val sse = JSONObject().apply {
            put("candidates", JSONArray().put(candidate))
        }.toString()
        return listOf("data: $sse\n\n", "data: [DONE]\n\n")
    }

    private fun buildGeminiTurn2Sse(tempFilePath: String): List<String> {
        val candidate = JSONObject().apply {
            put("content", JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionCall", JSONObject().apply {
                            put("name", "read_temp_file")
                            put("id", "call_read_3")
                            put("args", JSONObject().apply {
                                put("path", tempFilePath)
                            })
                        })
                    })
                })
            })
            put("finishReason", "STOP")
            put("index", 0)
        }
        val sse = JSONObject().apply {
            put("candidates", JSONArray().put(candidate))
        }.toString()
        return listOf("data: $sse\n\n", "data: [DONE]\n\n")
    }

    private fun buildGeminiTurn3Sse(testContent: String): List<String> {
        val candidate = JSONObject().apply {
            put("content", JSONObject().apply {
                put("role", "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", "File verification complete: $testContent")
                    })
                })
            })
            put("finishReason", "STOP")
            put("index", 0)
        }
        val sse = JSONObject().apply {
            put("candidates", JSONArray().put(candidate))
        }.toString()
        return listOf("data: $sse\n\n", "data: [DONE]\n\n")
    }

    private fun buildAnthropicTurn1Sse(tempFilePath: String, testContent: String): List<String> {
        return listOf(
            "event: message_start\ndata: " + JSONObject().apply {
                put("type", "message_start")
                put("message", JSONObject().apply {
                    put("usage", JSONObject().apply {
                        put("input_tokens", 25)
                        put("output_tokens", 1)
                    })
                })
            }.toString() + "\n\n",

            "event: content_block_start\ndata: " + JSONObject().apply {
                put("type", "content_block_start")
                put("index", 0)
                put("content_block", JSONObject().apply {
                    put("type", "thinking")
                    put("thinking", "")
                })
            }.toString() + "\n\n",

            "event: content_block_delta\ndata: " + JSONObject().apply {
                put("type", "content_block_delta")
                put("index", 0)
                put("delta", JSONObject().apply {
                    put("type", "thinking_delta")
                    put("thinking", "Plan: write file and verify error with screenshot")
                })
            }.toString() + "\n\n",

            "event: content_block_delta\ndata: " + JSONObject().apply {
                put("type", "content_block_delta")
                put("index", 0)
                put("delta", JSONObject().apply {
                    put("type", "signature_delta")
                    put("signature", "anthropic-sig-turn1")
                })
            }.toString() + "\n\n",

            "event: content_block_stop\ndata: " + JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 0)
            }.toString() + "\n\n",

            "event: content_block_start\ndata: " + JSONObject().apply {
                put("type", "content_block_start")
                put("index", 1)
                put("content_block", JSONObject().apply {
                    put("type", "tool_use")
                    put("id", "call_write_1")
                    put("name", "write_temp_file")
                    put("input", JSONObject())
                })
            }.toString() + "\n\n",

            "event: content_block_delta\ndata: " + JSONObject().apply {
                put("type", "content_block_delta")
                put("index", 1)
                put("delta", JSONObject().apply {
                    put("type", "input_json_delta")
                    put("partial_json", JSONObject().apply {
                        put("path", tempFilePath)
                        put("content", testContent)
                    }.toString())
                })
            }.toString() + "\n\n",

            "event: content_block_stop\ndata: " + JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 1)
            }.toString() + "\n\n",

            "event: content_block_start\ndata: " + JSONObject().apply {
                put("type", "content_block_start")
                put("index", 2)
                put("content_block", JSONObject().apply {
                    put("type", "tool_use")
                    put("id", "call_error_2")
                    put("name", "capture_screen_error")
                    put("input", JSONObject())
                })
            }.toString() + "\n\n",

            "event: content_block_delta\ndata: " + JSONObject().apply {
                put("type", "content_block_delta")
                put("index", 2)
                put("delta", JSONObject().apply {
                    put("type", "input_json_delta")
                    put("partial_json", JSONObject().apply {
                        put("reason", "verify_ui")
                    }.toString())
                })
            }.toString() + "\n\n",

            "event: content_block_stop\ndata: " + JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 2)
            }.toString() + "\n\n",

            "event: message_delta\ndata: " + JSONObject().apply {
                put("type", "message_delta")
                put("delta", JSONObject().apply {
                    put("stop_reason", "tool_use")
                })
                put("usage", JSONObject().apply {
                    put("output_tokens", 15)
                })
            }.toString() + "\n\n",

            "event: message_stop\ndata: " + JSONObject().apply {
                put("type", "message_stop")
            }.toString() + "\n\n"
        )
    }

    private fun buildAnthropicTurn2Sse(tempFilePath: String): List<String> {
        return listOf(
            "event: message_start\ndata: " + JSONObject().apply {
                put("type", "message_start")
                put("message", JSONObject().apply {
                    put("usage", JSONObject().apply {
                        put("input_tokens", 40)
                        put("output_tokens", 1)
                    })
                })
            }.toString() + "\n\n",

            "event: content_block_start\ndata: " + JSONObject().apply {
                put("type", "content_block_start")
                put("index", 0)
                put("content_block", JSONObject().apply {
                    put("type", "tool_use")
                    put("id", "call_read_3")
                    put("name", "read_temp_file")
                    put("input", JSONObject())
                })
            }.toString() + "\n\n",

            "event: content_block_delta\ndata: " + JSONObject().apply {
                put("type", "content_block_delta")
                put("index", 0)
                put("delta", JSONObject().apply {
                    put("type", "input_json_delta")
                    put("partial_json", JSONObject().apply {
                        put("path", tempFilePath)
                    }.toString())
                })
            }.toString() + "\n\n",

            "event: content_block_stop\ndata: " + JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 0)
            }.toString() + "\n\n",

            "event: message_delta\ndata: " + JSONObject().apply {
                put("type", "message_delta")
                put("delta", JSONObject().apply {
                    put("stop_reason", "tool_use")
                })
                put("usage", JSONObject().apply {
                    put("output_tokens", 10)
                })
            }.toString() + "\n\n",

            "event: message_stop\ndata: " + JSONObject().apply {
                put("type", "message_stop")
            }.toString() + "\n\n"
        )
    }

    private fun buildAnthropicTurn3Sse(testContent: String): List<String> {
        return listOf(
            "event: message_start\ndata: " + JSONObject().apply {
                put("type", "message_start")
                put("message", JSONObject().apply {
                    put("usage", JSONObject().apply {
                        put("input_tokens", 60)
                        put("output_tokens", 1)
                    })
                })
            }.toString() + "\n\n",

            "event: content_block_start\ndata: " + JSONObject().apply {
                put("type", "content_block_start")
                put("index", 0)
                put("content_block", JSONObject().apply {
                    put("type", "text")
                    put("text", "File verification complete: $testContent")
                })
            }.toString() + "\n\n",

            "event: content_block_stop\ndata: " + JSONObject().apply {
                put("type", "content_block_stop")
                put("index", 0)
            }.toString() + "\n\n",

            "event: message_delta\ndata: " + JSONObject().apply {
                put("type", "message_delta")
                put("delta", JSONObject().apply {
                    put("stop_reason", "end_turn")
                })
                put("usage", JSONObject().apply {
                    put("output_tokens", 12)
                })
            }.toString() + "\n\n",

            "event: message_stop\ndata: " + JSONObject().apply {
                put("type", "message_stop")
            }.toString() + "\n\n"
        )
    }

    // ==================== HTTP 工具函数 ====================

    private fun readHttpRequest(socket: Socket): Pair<List<String>, String> {
        val input = socket.getInputStream()
        val headerLines = mutableListOf<String>()
        val lineBuf = java.io.ByteArrayOutputStream()
        var contentLength = 0

        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val line = lineBuf.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
                lineBuf.reset()
                if (line.isEmpty()) break
                headerLines.add(line)
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            } else {
                lineBuf.write(b)
            }
        }

        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = input.read(bodyBytes, read, contentLength - read)
                if (r == -1) break
                read += r
            }
            String(bodyBytes, 0, read, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return headerLines to body
    }

    private fun writeSseResponse(socket: Socket, events: List<String>) {
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/event-stream\r\n")
        writer.write("Cache-Control: no-cache\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        for (event in events) {
            writer.write(event)
            writer.flush()
        }
    }

    private fun buildTestEnvironment(userMessage: String): AgentExecutionEnvironment =
        DefaultAgentExecutionEnvironment(
            agentRunId = "test-run-${UUID.randomUUID()}",
            userMessage = userMessage,
            runtimeContextRepository = Mockito.mock(AgentRuntimeContextRepository::class.java, Mockito.RETURNS_DEFAULTS),
            workspaceDescriptor = Mockito.mock(AgentWorkspaceDescriptor::class.java, Mockito.RETURNS_DEFAULTS),
            resolvedSkills = emptyList(),
            workspaceManager = Mockito.mock(AgentWorkspaceManager::class.java, Mockito.RETURNS_DEFAULTS),
            workspaceMemoryService = Mockito.mock(WorkspaceMemoryService::class.java, Mockito.RETURNS_DEFAULTS),
            conversationMode = "normal",
            runControl = NoOpAgentRunControl
        )

    // ==================== Fixture 实现 ====================

    private class FixtureToolCatalog(
        private val tempFilePath: String
    ) : AgentToolCatalog {
        override val toolsForModel: List<ChatCompletionTool> = listOf(
            ChatCompletionTool(
                type = "function",
                function = ChatCompletionFunction(
                    name = "write_temp_file",
                    description = "Writes text content to a local temporary file",
                    parameters = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {
                            put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                            put("content", buildJsonObject { put("type", JsonPrimitive("string")) })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("path"))
                            add(JsonPrimitive("content"))
                        })
                    }
                )
            ),
            ChatCompletionTool(
                type = "function",
                function = ChatCompletionFunction(
                    name = "capture_screen_error",
                    description = "Captures screen and simulates an inspection failure with an image",
                    parameters = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {
                            put("reason", buildJsonObject { put("type", JsonPrimitive("string")) })
                        })
                    }
                )
            ),
            ChatCompletionTool(
                type = "function",
                function = ChatCompletionFunction(
                    name = "read_temp_file",
                    description = "Reads content from a local temporary file",
                    parameters = buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put("properties", buildJsonObject {
                            put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                        })
                        put("required", buildJsonArray {
                            add(JsonPrimitive("path"))
                        })
                    }
                )
            )
        )

        override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor =
            AgentToolRegistry.RuntimeToolDescriptor(
                name = toolName,
                displayName = toolName,
                toolType = "builtin"
            )

        override fun validateArguments(toolName: String, arguments: JsonObject) {
            when (toolName) {
                "write_temp_file" -> {
                    require(arguments.containsKey("path") && arguments.containsKey("content")) {
                        "write_temp_file requires 'path' and 'content'"
                    }
                }
                "read_temp_file" -> {
                    require(arguments.containsKey("path")) {
                        "read_temp_file requires 'path'"
                    }
                }
                "capture_screen_error" -> Unit
            }
        }
    }

    private class FixtureToolExecutor : AgentToolExecutor {
        val executedCalls = Collections.synchronizedList(mutableListOf<String>())
        val executedArguments = Collections.synchronizedList(mutableListOf<JsonObject>())

        override suspend fun execute(
            toolCall: AssistantToolCall,
            args: JsonObject,
            runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
            env: AgentExecutionEnvironment,
            callback: AgentCallback,
            toolHandle: AgentToolExecutionHandle
        ): ToolExecutionResult {
            val toolName = toolCall.function.name
            executedCalls.add(toolName)
            executedArguments.add(args)

            return when (toolName) {
                "write_temp_file" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'path' parameter")
                    val content = args["content"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'content' parameter")
                    val file = File(path)
                    file.parentFile?.mkdirs()
                    file.writeText(content, StandardCharsets.UTF_8)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = "File written successfully: $path",
                        previewJson = "{\"status\":\"ok\",\"bytes\":${content.toByteArray(StandardCharsets.UTF_8).size}}",
                        rawResultJson = "{\"status\":\"ok\",\"path\":\"$path\"}",
                        success = true
                    )
                }
                "capture_screen_error" -> {
                    val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = "UI inspection failure: $reason",
                        previewJson = "{\"error\":\"UI inspection failed\",\"reason\":\"$reason\"}",
                        rawResultJson = "{\"error\":\"UI inspection failed\",\"reason\":\"$reason\"}",
                        success = false,
                        imageDataUrl = "data:image/png;base64,$ONE_PIXEL_PNG_BASE64"
                    )
                }
                "read_temp_file" -> {
                    val path = args["path"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing 'path' parameter")
                    val file = File(path)
                    require(file.exists()) { "File does not exist: $path" }
                    val content = file.readText(StandardCharsets.UTF_8)
                    ToolExecutionResult.ContextResult(
                        toolName = toolName,
                        summaryText = content,
                        previewJson = "{\"content\":\"$content\"}",
                        rawResultJson = "{\"content\":\"$content\"}",
                        success = true
                    )
                }
                else -> ToolExecutionResult.Error(toolName, "Unknown tool: $toolName")
            }
        }
    }

    private class FixtureAgentCallback : AgentCallback {
        data class ToolStartRecord(
            val id: String,
            val name: String,
            val arguments: JsonObject,
            val toolType: String?
        )
        data class ToolCompleteRecord(
            val id: String,
            val name: String,
            val result: ToolExecutionResult
        )

        val toolStarts = Collections.synchronizedList(mutableListOf<ToolStartRecord>())
        val toolCompletes = Collections.synchronizedList(mutableListOf<ToolCompleteRecord>())
        val chatMessages = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        var completedResult: AgentResult? = null
        val firstClientEventObserved = CompletableDeferred<Unit>()

        override suspend fun onThinkingStart() = Unit
        override suspend fun onThinkingUpdate(thinking: String) {
            firstClientEventObserved.complete(Unit)
        }
        override suspend fun onToolCallStart(toolName: String, arguments: JsonObject) = Unit

        override suspend fun onToolCallStart(
            toolCallId: String,
            toolName: String,
            arguments: JsonObject,
            toolType: String?
        ) {
            toolStarts.add(ToolStartRecord(toolCallId, toolName, arguments, toolType))
        }

        override suspend fun onToolCallProgress(
            toolName: String,
            progress: String,
            extras: Map<String, Any?>
        ) = Unit

        override suspend fun onToolCallComplete(toolName: String, result: ToolExecutionResult) = Unit

        override suspend fun onToolCallComplete(
            toolCallId: String,
            toolName: String,
            result: ToolExecutionResult
        ) {
            toolCompletes.add(ToolCompleteRecord(toolCallId, toolName, result))
        }

        override suspend fun onChatMessage(message: String) {
            firstClientEventObserved.complete(Unit)
            chatMessages.add(message to true)
        }

        override suspend fun onChatMessage(message: String, isFinal: Boolean) {
            firstClientEventObserved.complete(Unit)
            chatMessages.add(message to isFinal)
        }

        override suspend fun onClarifyRequired(question: String, missingFields: List<String>?) = Unit

        override suspend fun onComplete(result: AgentResult) {
            completedResult = result
        }

        override suspend fun onError(error: String) {
            errors.add(error)
        }

        override suspend fun onPermissionRequired(missing: List<String>) = Unit
    }
}
