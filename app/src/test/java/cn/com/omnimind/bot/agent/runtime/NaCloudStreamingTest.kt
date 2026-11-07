package cn.com.omnimind.bot.agent.runtime

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class NaCloudStreamingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun promptUsesOneSubmissionAndSseChunksThroughTheExistingAcpBoundary() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        `when`(context.cacheDir).thenReturn(folder.root)
        val bindings = mock(AgentSessionBindingRepository::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seen = mutableListOf<Map<String, Any?>>()
        val runtime = NaCloudRuntime(context, scope, bindings, AcpAgentProfileStore(context)) { seen += it }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"capabilities":["idempotent_admission"]}"""))
                server.enqueue(MockResponse().setBody("""{"runId":"run"}"""))
                server.enqueue(MockResponse().setBody("""{"eventCursor":1,"run":{"id":"run","status":"running","items":[]}}"""))
                val events = listOf(
                    """{"id":2,"type":"item.updated","runId":"run","data":{"item":{"id":"reply","type":"agent_message","text":"first "}}}""",
                    """{"id":3,"type":"item.updated","runId":"run","data":{"item":{"id":"reply","type":"agent_message","text":"first second"}}}""",
                    """{"id":4,"type":"run.completed","runId":"run","data":{"run":{"id":"run","status":"completed","items":[{"id":"reply","type":"agent_message","text":"first second"}]}}}""",
                ).joinToString("") { "event: na\ndata: $it\n\n" }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setChunkedBody(events, 17))
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val session = connection.prefix + "conversation"
                `when`(bindings.ensureBinding(session, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                withContext(connection) {
                    val args = mapOf("sessionId" to session, "requestId" to "same-turn", "text" to "hello")
                    val result = runtime.callPrompt(args) as Map<*, *>
                    assertEquals("end_turn", result["stopReason"])
                    assertEquals(result, runtime.callPrompt(args))
                }
                assertEquals(4, server.requestCount)
                assertEquals("/api/status", server.takeRequest().path)
                val admission = server.takeRequest()
                assertEquals("POST", admission.method)
                val payload = com.google.gson.JsonParser.parseString(admission.body.readUtf8()).asJsonObject
                val origin = payload["clientOriginId"].asString
                assertTrue(origin.isNotBlank())
                assertEquals("$origin:same-turn", payload["clientMessageId"].asString)
                assertEquals("/api/runs/run", server.takeRequest().path)
                assertEquals("/api/events?after=1", server.takeRequest().path)
                assertEquals(listOf("first ", "second"), seen.map {
                    val update = (it["params"] as Map<*, *>)["update"] as Map<*, *>
                    assertEquals("agent_message_chunk", update["sessionUpdate"])
                    assertEquals("reply", update["messageId"])
                    (update["content"] as Map<*, *>)["text"]
                })
                assertTrue(seen.all { it["method"] == "session/update" && it["sessionId"] == session && it["turnId"] == "same-turn" })
            }
        } finally { scope.cancel() }
    }

    @Test fun admittedPromptWithPartialOutputFailsWithIdentityWrapper() = runBlocking {
        // 已准入 Na 请求在产生部分流式输出后若发生 SSE 压缩/网络断开异常，必须抛出携带请求自身身份的 NaAdmittedPromptException
        val context = acpProfileStoreTestContext(folder.root)
        `when`(context.cacheDir).thenReturn(folder.root)
        val bindings = mock(AgentSessionBindingRepository::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seen = mutableListOf<Map<String, Any?>>()
        val runtime = NaCloudRuntime(context, scope, bindings, AcpAgentProfileStore(context)) { seen += it }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"capabilities":["idempotent_admission"]}"""))
                server.enqueue(MockResponse().setBody("""{"runId":"run-admitted"}"""))
                server.enqueue(MockResponse().setBody("""{"eventCursor":1,"run":{"id":"run-admitted","status":"running","items":[]}}"""))
                val events = listOf(
                    """{"id":2,"type":"item.updated","runId":"run-admitted","data":{"item":{"id":"reply","type":"agent_message","text":"partial output"}}}""",
                ).joinToString("") { "event: na\ndata: $it\n\n" }
                server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setChunkedBody(events, 17))
                server.enqueue(MockResponse().setResponseCode(409).setBody("Compacted"))
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val session = connection.prefix + "conversation"
                `when`(bindings.ensureBinding(session, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                val failure = withContext(connection) {
                    val args = mapOf("sessionId" to session, "requestId" to "admitted-turn", "text" to "hello")
                    runCatching { runtime.callPrompt(args) }.exceptionOrNull()
                }
                assertNotNull("已准入请求执行异常不应为 null", failure)
                assertTrue("已准入执行失败必须包装为 NaAdmittedPromptException", failure is NaAdmittedPromptException)
                val admitted = failure as NaAdmittedPromptException
                assertEquals("turnId 必须为本次请求自身的 reservation", "admitted-turn", admitted.turnId)
                assertEquals("sessionId 必须与会话匹配", session, admitted.sessionId)
                assertEquals("conversationId 必须正确绑定", 42L, admitted.conversationId)
                assertEquals("requestId 必须属于本次请求自身", "admitted-turn", admitted.requestId)
                assertEquals("部分流式输出必须已正常发射", 1, seen.size)
                assertEquals("session/update", seen[0]["method"])
                assertEquals("admitted-turn", seen[0]["turnId"])
            }
        } finally { scope.cancel() }
    }

    @Test fun preflightFailureThrowsWithoutIdentityWrapperAndWithoutEmittingUpdates() = runBlocking {
        // 未准入 preflight 阶段异常（如 403 准入拒绝）绝不能包装已准入包装器，且不发射任何 update 事件
        val context = acpProfileStoreTestContext(folder.root)
        `when`(context.cacheDir).thenReturn(folder.root)
        val bindings = mock(AgentSessionBindingRepository::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val seen = mutableListOf<Map<String, Any?>>()
        val runtime = NaCloudRuntime(context, scope, bindings, AcpAgentProfileStore(context)) { seen += it }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"capabilities":["idempotent_admission"]}"""))
                server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
                server.start()
                val connection = NaCloudConnection(server.url("/").toString().trimEnd('/'))
                val session = connection.prefix + "conversation"
                `when`(bindings.ensureBinding(session, NA_AGENT_ID, cwd = "na-cloud")).thenReturn(42L)
                val failure = withContext(connection) {
                    val args = mapOf("sessionId" to session, "requestId" to "preflight-turn", "text" to "hello")
                    runCatching { runtime.callPrompt(args) }.exceptionOrNull()
                }
                assertNotNull("未准入异常不应为 null", failure)
                assertFalse("未准入异常绝不能被包装为 NaAdmittedPromptException", failure is NaAdmittedPromptException)
                assertTrue("未准入异常必须保持原始 NaRuntimeFailure 类型", failure is NaRuntimeFailure)
                assertEquals("na_permission_denied", (failure as NaRuntimeFailure).failureKind)
                assertTrue("未准入请求不应产生任何 update 事件", seen.isEmpty())
            }
        } finally { scope.cancel() }
    }

    private suspend fun NaCloudRuntime.callPrompt(args: Map<String, Any?>): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            javaClass.getDeclaredMethod("handleConnected", String::class.java, Map::class.java, Continuation::class.java)
                .apply { isAccessible = true }.invoke(this, "session/prompt", args, continuation)
        }
}
