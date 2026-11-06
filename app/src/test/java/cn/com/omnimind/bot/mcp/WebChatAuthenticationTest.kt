package cn.com.omnimind.bot.mcp

import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.BrowserMirrorService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.WebChatAvatarService
import cn.com.omnimind.bot.webchat.WorkspaceFileService
import com.tencent.mmkv.MMKV
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.gson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class WebChatAuthenticationTest {
    private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private lateinit var baseUrl: String
    private val client = OkHttpClient()
    private var previousToken: Any? = null
    private val tokenField = McpServerManager::class.java.getDeclaredField("cachedToken").apply {
        isAccessible = true
    }

    @Before
    fun setUp() {
        previousToken = tokenField.get(McpServerManager)
        tokenField.set(McpServerManager, TOKEN)
        // Initialize the manager's lazy preferences on this thread; no Android/JNI storage is used.
        mockStatic(MMKV::class.java).use { mmkv ->
            mmkv.`when`<MMKV> { MMKV.defaultMMKV() }.thenReturn(mock(MMKV::class.java))
            McpServerManager.currentState()
        }
        server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(ContentNegotiation) {
                json(McpJson)
                gson()
            }
            routing {
                post("/webchat/api/session/bootstrap") {
                    McpServerManager.handleWebChatSessionBootstrap(call)
                }
                with(WebChatRoutes) {
                    registerWebChatRoutes(
                        mock(ConversationDomainService::class.java),
                        mock(WorkspaceFileService::class.java),
                        mock(BrowserMirrorService::class.java),
                        mock(AgentRunService::class.java),
                        mock(WebChatAvatarService::class.java),
                    )
                }
                get("/mcp-json") {
                    call.respond(buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", 7)
                        put("result", JsonNull)
                    })
                }
            }
        }.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop(0, 1_000)
        tokenField.set(McpServerManager, previousToken)
        sessions().clear()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `settings token creates a cookie session and authorizes subsequent requests`() {
        val cookie = bootstrap("""{"token":"$TOKEN"}""").use { response ->
            assertEquals(200, response.code)
            val body = Json.parseToJsonElement(response.body.string()).jsonObject
            assertTrue(body.getValue("success").jsonPrimitive.boolean)
            val state = body.getValue("server").jsonObject
            assertEquals(TOKEN, state.getValue("token").jsonPrimitive.content)
            assertEquals(8899, state.getValue("port").jsonPrimitive.int)
            assertFalse(state.getValue("running").jsonPrimitive.boolean)
            val setCookie = response.header("Set-Cookie")!!
            assertTrue(setCookie.contains("HttpOnly", ignoreCase = true))
            setCookie.substringBefore(';')
        }
        request("/webchat/api/bootstrap", cookie = cookie).use {
            assertEquals(200, it.code)
            val body = Json.parseToJsonElement(it.body.string()).jsonObject
            assertTrue(body.getValue("capabilities").jsonObject.getValue("conversations").jsonPrimitive.boolean)
        }
        request("/webchat/api/conversations", cookie = cookie).use {
            assertEquals(200, it.code)
        }
        request("/webchat/api/conversations").use {
            assertEquals(401, it.code)
        }
        // Exercise the other Map<String, Any?> request bodies after login, too.
        request("/webchat/api/tasks/test/clarify", body = """{"reply":""}""", cookie = cookie).use {
            assertEquals(400, it.code)
            assertTrue(it.body.string().contains("EMPTY_REPLY"))
        }
    }

    @Test
    fun `wrong and missing tokens never create a session`() {
        for (body in listOf("""{"token":"wrong"}""", "{}", """{"token":""}""", "{", "null", "[]")) {
            bootstrap(body).use {
                assertEquals(HttpStatusCode.Forbidden.value, it.code)
                assertEquals(null, it.header("Set-Cookie"))
                assertTrue(it.body.string().contains("Authentication failed"))
            }
        }
        assertTrue(sessions().isEmpty())
    }

    @Test
    fun `authenticated requests still reject malformed JSON and non JSON content types`() {
        for (body in listOf("{", "null", "[]", "true")) {
            request("/webchat/api/tasks/test/clarify", body = body, bearer = TOKEN).use {
                assertEquals(400, it.code)
            }
        }
        request(
            "/webchat/api/tasks/test/clarify",
            body = """{"reply":"hello"}""",
            bearer = TOKEN,
            contentType = "text/plain",
        ).use {
            assertEquals(415, it.code)
        }
    }

    @Test
    fun `bearer authentication remains supported`() {
        bootstrap("{}", bearer = TOKEN).use {
            assertEquals(200, it.code)
            assertNotNull(it.header("Set-Cookie"))
        }
        request("/webchat/api/conversations", bearer = TOKEN).use {
            assertEquals(200, it.code)
        }
        request("/webchat/api/conversations", bearer = "wrong").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun `expired cookie is rejected`() {
        sessions()["expired"] = System.currentTimeMillis() - 1
        request("/webchat/api/conversations", cookie = "omnibot_webchat_session=expired").use {
            assertEquals(401, it.code)
        }
    }

    @Test
    fun `MCP retains its kotlinx JSON representation`() {
        request("/mcp-json").use {
            assertEquals(200, it.code)
            assertEquals(
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":7,"result":null}"""),
                Json.parseToJsonElement(it.body.string()),
            )
        }
    }

    private fun bootstrap(body: String, bearer: String? = null): Response =
        request("/webchat/api/session/bootstrap", body = body, bearer = bearer)

    private fun request(
        path: String,
        body: String? = null,
        cookie: String? = null,
        bearer: String? = null,
        contentType: String = "application/json",
    ): Response = client.newCall(Request.Builder().url(baseUrl + path).apply {
        header("Accept", "application/json")
        if (body != null) post(body.toRequestBody(contentType.toMediaType()))
        if (cookie != null) header("Cookie", cookie)
        if (bearer != null) header("Authorization", "Bearer $bearer")
    }.build()).execute()

    @Suppress("UNCHECKED_CAST")
    private fun sessions(): MutableMap<String, Long> =
        McpServerManager::class.java.getDeclaredField("webChatSessions").let {
            it.isAccessible = true
            it.get(McpServerManager) as MutableMap<String, Long>
        }

    private companion object {
        const val TOKEN = "test-local-service-token_0123456789="
    }
}
