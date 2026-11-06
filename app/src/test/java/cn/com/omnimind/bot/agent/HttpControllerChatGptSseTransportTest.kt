package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.assists.controller.http.OpenAiResponsesSseBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpControllerChatGptSseTransportTest {
    @Test
    fun `mislabelled SSE reproduces HTTP 200 admission failure before any event`() {
        listOf("application/json", "text/plain", null).forEach { contentType ->
            exchange(reply(successfulStream(), contentType), normalize = false) { probe, server ->
                assertTrue(probe.events.isEmpty())
                assertEquals(200, probe.status.get())
                assertTrue(probe.failure.get()!!.message!!.contains("Invalid content-type"))
                assertTrue(probe.failureBody.get()!!.startsWith("event: response.created"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `OAuth SSE body is parsed once across correct missing and incorrect media types`() {
        listOf("text/event-stream", "application/json", "text/plain", null).forEach { contentType ->
            exchange(reply(successfulStream(), contentType)) { probe, server ->
                assertEquals(null, probe.failure.get())
                assertEquals(1, probe.events.count { it == "[DONE]" })
                assertEquals(1, probe.events.count { it.contains("你好") })
                assertEquals(contentType, probe.originalContentType.get())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `SSE BOM comments and data only first event retain every event`() {
        val first = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n"
        listOf("text/event-stream", "application/json").forEach { contentType ->
            exchange(reply("\uFEFF: keepalive\n\n" + first + completed(), contentType)) { probe, server ->
                assertEquals(null, probe.failure.get())
                assertEquals(1, probe.events.count { it.contains("你好") })
                assertEquals(1, probe.events.count { it == "[DONE]" })
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `ordinary JSON and HTML success bodies are never reinterpreted as streams`() {
        listOf(
            """{"status":"completed","output_text":"not an SSE response"}""",
            "<html>event: response.completed</html>",
            "x".repeat(1100),
        ).forEach { body ->
            exchange(reply(body, "application/json")) { probe, server ->
                assertNotNull(probe.failure.get())
                assertTrue(probe.events.isEmpty())
                assertEquals(200, probe.status.get())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `authentication and permission failures retain HTTP status and body`() {
        listOf(401, 403).forEach { status ->
            exchange(reply(successfulStream(), "application/json").setResponseCode(status)) { probe, server ->
                assertEquals(status, probe.status.get())
                assertTrue(probe.events.isEmpty())
                assertTrue(probe.failureBody.get()!!.startsWith("event: response.created"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `real Responses failed and incomplete events remain failures after MIME normalization`() {
        listOf("response.failed", "response.incomplete").forEach { type ->
            val terminal = "event: $type\ndata: {\"type\":\"$type\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"Usage limit reached\"}}}\n\n"
            exchange(reply(created() + terminal, "text/plain")) { probe, server ->
                assertNotNull(probe.failure.get())
                assertTrue(probe.failure.get()!!.message!!.contains(type))
                assertTrue(probe.failureBody.get()!!.contains("subscription_sharing_usage_limit_exceeded"))
                assertFalse(probe.events.contains("[DONE]"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `created event EOF and malformed completion are never accepted as success`() {
        listOf(
            created(),
            created() + "event: response.completed\ndata: {broken}\n\n",
            created() + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"in_progress\"}}\n\n",
        ).forEach { body ->
            exchange(reply(body, "application/json")) { probe, server ->
                assertNotNull(probe.failure.get())
                assertFalse(probe.events.contains("[DONE]"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `first SSE event line opens stream without waiting for a large sniff buffer`() {
        val firstLine = "event: response.created\n"
        val probe = Probe()
        val client = client()
        MockWebServer().use { server ->
            server.enqueue(reply(successfulStream(), "application/json")
                .throttleBody(firstLine.toByteArray().size.toLong(), 2, TimeUnit.SECONDS))
            val source = EventSources.createFactory(client).newEventSource(request(server),
                HttpController.wrapResponsesListener(probe, requireCompleted = true))
            try {
                assertTrue("The first event line should admit the stream immediately", probe.opened.await(1, TimeUnit.SECONDS))
                assertEquals(1, server.requestCount)
            } finally {
                source.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `cancellation interrupts prefix inspection on the original call`() {
        val inspecting = CountDownLatch(1)
        val probe = Probe()
        val client = client(inspecting)
        MockWebServer().use { server ->
            server.enqueue(reply(":" + " ".repeat(1100) + "\n", "text/plain")
                .throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val source = EventSources.createFactory(client).newEventSource(request(server),
                HttpController.wrapResponsesListener(probe, requireCompleted = true))
            try {
                assertTrue(inspecting.await(2, TimeUnit.SECONDS))
                source.cancel()
                assertTrue(probe.finished.await(2, TimeUnit.SECONDS))
                assertNotNull(probe.failure.get())
                assertTrue(probe.events.isEmpty())
                assertEquals(1, server.requestCount)
            } finally {
                source.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun exchange(
        response: MockResponse,
        normalize: Boolean = true,
        verify: (Probe, MockWebServer) -> Unit,
    ) {
        val client = if (normalize) client() else OkHttpClient()
        MockWebServer().use { server ->
            server.enqueue(response)
            val probe = Probe()
            val source = EventSources.createFactory(client).newEventSource(request(server),
                HttpController.wrapResponsesListener(probe, requireCompleted = true))
            try {
                assertTrue("Expected terminal callback", probe.finished.await(5, TimeUnit.SECONDS))
                verify(probe, server)
            } finally {
                source.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun client(inspecting: CountDownLatch? = null) = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(0, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            inspecting?.countDown()
            OpenAiResponsesSseBody.normalize(response)
        }.build()

    private fun request(server: MockWebServer) = Request.Builder().url(server.url("/v1/responses"))
        .post("{}".toRequestBody()).build()
    private fun reply(body: String, contentType: String?) = MockResponse().setBody(body).apply {
        if (contentType != null) setHeader("Content-Type", contentType)
    }
    private fun created() = "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\"}}\n\n"
    private fun completed() = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"
    private fun successfulStream() = created() +
        "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}\n\n" + completed()

    private class Probe : EventSourceListener() {
        val events = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        val status = AtomicReference<Int?>()
        val failureBody = AtomicReference<String?>()
        val originalContentType = AtomicReference<String?>()
        val opened = CountDownLatch(1)
        val finished = CountDownLatch(1)
        override fun onOpen(eventSource: EventSource, response: Response) {
            originalContentType.set(response.header("Content-Type"))
            opened.countDown()
        }
        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { events += data }
        override fun onClosed(eventSource: EventSource) { finished.countDown() }
        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            failure.set(t)
            status.set(response?.code)
            failureBody.set(runCatching { response?.peekBody(2048)?.string() }.getOrNull())
            finished.countDown()
        }
    }
}
