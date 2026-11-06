package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatGptAgentProviderAccessTest {
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val profile = ModelProviderProfile("chatgpt", "ChatGPT", sourceType = ChatGptOAuthProvider.SOURCE_TYPE)
    private val payload = """{"model":"gpt-test","input":[{"role":"user","content":"hello"}]}"""
    private val events = "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}\n\n" +
        "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\"}}\n\n"

    @Test fun eachRequestUsesCurrentNativeTokenAndPreservesSseIdentity() = runBlocking {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(events))
            upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events))
            var token = "native-token-one"
            ChatGptAgentProviderAccess.open(profile, { token }, upstream = upstream.url("/v1/responses")).use { access ->
                assertFalse(access.credentials.toString().contains(token))
                request(access).use {
                    assertEquals(200, it.code)
                    assertEquals("text/event-stream", it.body.contentType()?.toString())
                    assertEquals(events, it.body.string())
                }
                val first = upstream.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("Bearer native-token-one", first.getHeader("Authorization"))
                assertNull(first.getHeader("X-Untrusted"))
                val body = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
                assertEquals("false", body["store"]!!.jsonPrimitive.content)
                assertEquals("true", body["stream"]!!.jsonPrimitive.content)
                assertEquals("hello", body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
                token = "native-token-two"
                request(access).use { assertEquals(events, it.body.string()) }
                assertEquals("Bearer native-token-two", upstream.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                assertEquals(2, upstream.requestCount)
            }
        }
    }

    @Test fun grantsAreIsolatedAndRejectUnauthorizedRoutesWithoutResolvingTokens() = runBlocking {
        MockWebServer().use { upstream ->
            val resolved = AtomicInteger()
            ChatGptAgentProviderAccess.open(profile, { resolved.incrementAndGet(); "native" }, upstream = upstream.url("/v1/responses")).use { first ->
                ChatGptAgentProviderAccess.open(profile.copy(id = "second"), { "other" }, upstream = upstream.url("/v1/responses")).use { second ->
                    assertNotEquals(first.credentials.apiKey, second.credentials.apiKey)
                    request(first, bearer = second.credentials.apiKey).use { assertEquals(401, it.code) }
                    request(first, path = "/models").use { assertEquals(404, it.code) }
                    request(first, path = "/responses/compact").use { assertEquals(404, it.code) }
                    request(first, path = "/responses?url=https://example.com").use { assertEquals(400, it.code) }
                    request(first, headers = mapOf("Origin" to "https://example.com")).use { assertEquals(400, it.code) }
                    request(first, headers = mapOf("Host" to "example.com")).use { assertEquals(400, it.code) }
                    assertEquals(0, resolved.get())
                    assertEquals(0, upstream.requestCount)
                }
            }
        }
    }

    @Test fun changedProviderOrAuthorizationNeverFallsBackToAnotherCredential() = runBlocking {
        MockWebServer().use { upstream ->
            var valid = false
            ChatGptAgentProviderAccess.open(profile, { throw ChatGptOAuthException("login_cancelled", "Authorization changed.") },
                isProfileCurrent = { valid }, upstream = upstream.url("/v1/responses")).use { access ->
                request(access).use { assertEquals(401, it.code); assertTrue(it.body.string().contains("provider_changed")) }
                valid = true
                request(access).use { assertEquals(401, it.code); assertTrue(it.body.string().contains("login_cancelled")) }
                assertEquals(0, upstream.requestCount)
            }
        }
    }

    @Test fun upstreamErrorsAndRedirectsAreForwardedOnceWithoutFollowingThem() = runBlocking {
        MockWebServer().use { upstream ->
            val error = """{"error":{"code":"rate_limit_exceeded","message":"Wait"}}"""
            upstream.enqueue(MockResponse().setResponseCode(429).setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "30").setHeader("x-request-id", "req-123").setBody(error))
            upstream.enqueue(MockResponse().setResponseCode(307).setHeader("Location", upstream.url("/wrong")).setBody("redirect"))
            ChatGptAgentProviderAccess.open(profile, { "native" }, upstream = upstream.url("/v1/responses")).use { access ->
                request(access).use {
                    assertEquals(429, it.code)
                    assertEquals(error, it.body.string())
                    assertEquals("30", it.header("Retry-After"))
                    assertEquals("req-123", it.header("x-request-id"))
                }
                request(access).use { assertEquals(307, it.code); assertEquals("redirect", it.body.string()) }
                assertEquals(2, upstream.requestCount)
            }
        }
    }

    @Test fun temporaryRefreshFailuresAreNotReportedAsInvalidAuthentication() = runBlocking {
        var code = "network_error"
        ChatGptAgentProviderAccess.open(profile, { throw ChatGptOAuthException(code, "Safe authorization error.") }).use { access ->
            for ((failure, status) in mapOf(
                "network_error" to 503, "refresh_not_ready" to 503, "invalid_response" to 502,
                "plan_disabled" to 403, "sign_in_required" to 401, "secure_storage_unavailable" to 500,
            )) {
                code = failure
                request(access).use { assertEquals(failure, status, it.code) }
            }
        }
    }

    @Test fun unsupportedContextIsRejectedBeforeAcquiringAToken() = runBlocking {
        val tokens = AtomicInteger()
        ChatGptAgentProviderAccess.open(profile, { tokens.incrementAndGet(); "native" }).use { access ->
            request(access, body = """{"model":"test","input":[],"previous_response_id":"resp_old"}""").use {
                assertEquals(400, it.code)
            }
            assertEquals(0, tokens.get())
        }
    }

    @Test fun closingAccessCancelsAnUpstreamCallBlockedOnItsBody() = runBlocking {
        MockWebServer().use { upstream ->
            val cancelled = CountDownLatch(1)
            val upstreamClient = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events).setBodyDelay(30, TimeUnit.SECONDS))
            val access = ChatGptAgentProviderAccess.open(profile, { "native" }, upstream = upstream.url("/v1/responses"), client = upstreamClient)
            val downstream = client.newCall(requestBuilder(access).build())
            val worker = Thread { runCatching { downstream.execute().use { it.body.string() } } }.apply { start() }
            try {
                assertNotNull(upstream.takeRequest(3, TimeUnit.SECONDS))
                access.close()
                assertTrue(cancelled.await(3, TimeUnit.SECONDS))
                worker.join(3000)
                assertFalse(worker.isAlive)
                access.close()
            } finally { downstream.cancel(); access.close() }
        }
    }

    @Test fun downstreamDisconnectCancelsAStalledUpstreamStream() = runBlocking {
        MockWebServer().use { upstream ->
            val cancelled = CountDownLatch(1)
            val upstreamClient = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            // Send one event, then stall. The disconnected child must not keep generating/billing.
            upstream.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(events)
                .throttleBody(100, 30, TimeUnit.SECONDS))
            ChatGptAgentProviderAccess.open(profile, { "native" }, upstream = upstream.url("/v1/responses"), client = upstreamClient).use { access ->
                val url = java.net.URI(access.credentials.baseUrl)
                Socket("127.0.0.1", url.port).use { socket ->
                    socket.soTimeout = 3000
                    val bytes = payload.toByteArray()
                    socket.getOutputStream().apply {
                        write(("POST /v1/responses HTTP/1.1\r\nHost: 127.0.0.1:${url.port}\r\n" +
                            "Authorization: Bearer ${access.credentials.apiKey}\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                    assertTrue(socket.getInputStream().read() >= 0)
                }
                assertTrue("Closing the downstream socket must cancel the actual upstream call", cancelled.await(3, TimeUnit.SECONDS))
            }
        }
    }

    private fun request(
        access: AgentProviderAccess,
        bearer: String = access.credentials.apiKey,
        path: String = "/responses",
        body: String = payload,
        headers: Map<String, String> = emptyMap(),
    ) = client.newCall(requestBuilder(access, bearer, path, body).apply {
        header("X-Untrusted", "do-not-forward")
        headers.forEach { (key, value) -> header(key, value) }
    }.build()).execute()

    private fun requestBuilder(access: AgentProviderAccess, bearer: String = access.credentials.apiKey,
        path: String = "/responses", body: String = payload) = Request.Builder()
        .url(access.credentials.baseUrl + path).header("Authorization", "Bearer $bearer")
        .post(body.toRequestBody("application/json".toMediaType()))
}
