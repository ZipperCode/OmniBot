package cn.com.omnimind.bot.mcp

import android.app.Application
import android.content.Context
import cn.com.omnimind.bot.agent.runtime.AcpSessionMcpLeaseStore
import cn.com.omnimind.bot.agent.tool.handlers.StructuredDeviceActionContext
import com.tencent.mmkv.MMKV
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class McpHttpBoundaryInteropTest {
    private lateinit var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    private lateinit var baseUrl: String
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var previousToken: Any? = null
    private var previousServer: Any? = null
    private var previousIsRunning: Boolean = false
    private var previousActiveHost: Any? = null
    private var mockedMMKV: MockedStatic<MMKV>? = null

    private val tokenField = McpServerManager::class.java.getDeclaredField("cachedToken").apply { isAccessible = true }
    private val isRunningField = McpServerManager::class.java.getDeclaredField("isRunning").apply { isAccessible = true }
    private val activeHostField = McpServerManager::class.java.getDeclaredField("activeHost").apply { isAccessible = true }
    private val serverField = McpServerManager::class.java.getDeclaredField("server").apply { isAccessible = true }

    companion object {
        private const val GLOBAL_TOKEN = "global-secret-test-token-32bytes-long"
    }

    @Before
    fun setUp() {
        previousToken = tokenField.get(McpServerManager)
        previousIsRunning = isRunningField.getBoolean(McpServerManager)
        previousActiveHost = activeHostField.get(McpServerManager)
        previousServer = serverField.get(McpServerManager)
        tokenField.set(McpServerManager, GLOBAL_TOKEN)

        val mockMMKV = mock(MMKV::class.java, Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "decodeString" -> if (invocation.arguments.size > 1) invocation.arguments[1] as? String else null
                "decodeBool" -> if (invocation.arguments.size > 1) invocation.arguments[1] as? Boolean ?: false else false
                "decodeInt" -> if (invocation.arguments.size > 1) invocation.arguments[1] as? Int ?: 0 else 0
                "encode" -> true
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val staticMock = mockStatic(MMKV::class.java)
        staticMock.`when`<MMKV> { MMKV.defaultMMKV() }.thenReturn(mockMMKV)
        staticMock.`when`<MMKV> { MMKV.mmkvWithID(anyString()) }.thenReturn(mockMMKV)
        staticMock.`when`<MMKV> { MMKV.mmkvWithID(anyString(), anyInt()) }.thenReturn(mockMMKV)
        staticMock.`when`<String> { MMKV.initialize(any(Context::class.java)) }.thenReturn("/mock/mmkv")
        mockedMMKV = staticMock

        val app: Application = RuntimeEnvironment.getApplication()
        if (app.applicationInfo.dataDir == null) {
            app.applicationInfo.dataDir = app.filesDir.parentFile?.absolutePath ?: "/data/data/${app.packageName}"
        }

        server = McpServerManager.buildServer(
            context = app,
            port = 0,
            lanHost = "127.0.0.1",
        ).start(wait = false)

        val port = runBlocking { server.engine.resolvedConnectors().single().port }
        baseUrl = "http://127.0.0.1:$port"
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            server.stop(0, 1_000)
        }
        tokenField.set(McpServerManager, previousToken)
        isRunningField.set(McpServerManager, previousIsRunning)
        activeHostField.set(McpServerManager, previousActiveHost)
        serverField.set(McpServerManager, previousServer)
        McpSessionRegistry.clear()
        AcpSessionMcpLeaseStore.revokeAll()
        mockedMMKV?.close()
        mockedMMKV = null
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `cross bearer tokens between lease A and B are rejected with 401`() {
        val ownerA = Any()
        val ownerB = Any()
        val leaseA = AcpSessionMcpLeaseStore.prepare("agentA", 101L, ownerA)
        val leaseB = AcpSessionMcpLeaseStore.prepare("agentB", 102L, ownerB)

        // Lease A with Token A -> 200
        val requestA = Request.Builder()
            .url("$baseUrl/mcp/acp/${leaseA.leaseId}")
            .addHeader("Authorization", "Bearer ${leaseA.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(requestA).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Cross bearer: Lease A endpoint with Token B -> 401
        val crossRequestA = Request.Builder()
            .url("$baseUrl/mcp/acp/${leaseA.leaseId}")
            .addHeader("Authorization", "Bearer ${leaseB.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(crossRequestA).execute().use { response ->
            assertEquals(401, response.code)
        }

        // Cross bearer: Lease B endpoint with Token A -> 401
        val crossRequestB = Request.Builder()
            .url("$baseUrl/mcp/acp/${leaseB.leaseId}")
            .addHeader("Authorization", "Bearer ${leaseA.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(crossRequestB).execute().use { response ->
            assertEquals(401, response.code)
        }
    }

    @Test
    fun `global token cannot access ACP lease endpoint and lease token cannot access global endpoint`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentLease", 201L, owner)

        // Global token on /mcp/acp/{leaseId} -> 401
        val requestGlobalOnLease = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(requestGlobalOnLease).execute().use { response ->
            assertEquals(401, response.code)
        }

        // Lease token on /mcp -> 401
        val requestLeaseOnGlobal = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(requestLeaseOnGlobal).execute().use { response ->
            assertEquals(401, response.code)
        }

        // Global token on /mcp -> 200
        val requestGlobalOnGlobal = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(requestGlobalOnGlobal).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `unknown Mcp-Session-Id is rejected with 403 on both endpoints`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentUnknown", 301L, owner)
        val unknownSessionId = UUID.randomUUID().toString()

        // Unknown session ID on lease endpoint -> 403
        val requestLease = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("Mcp-Session-Id", unknownSessionId)
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(requestLease).execute().use { response ->
            assertEquals(403, response.code)
        }

        // Unknown session ID on global endpoint -> 403
        val requestGlobal = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .addHeader("Mcp-Session-Id", unknownSessionId)
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(requestGlobal).execute().use { response ->
            assertEquals(403, response.code)
        }
    }

    @Test
    fun `session ID generated on lease endpoint cannot be cross-used on another lease or global endpoint`() {
        val ownerA = Any()
        val ownerB = Any()
        val leaseA = AcpSessionMcpLeaseStore.prepare("agentA", 401L, ownerA)
        val leaseB = AcpSessionMcpLeaseStore.prepare("agentB", 402L, ownerB)

        // Initialize lease A to generate Mcp-Session-Id
        val initRequestA = Request.Builder()
            .url("$baseUrl/mcp/acp/${leaseA.leaseId}")
            .addHeader("Authorization", "Bearer ${leaseA.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()

        val sessionIdA: String
        client.newCall(initRequestA).execute().use { response ->
            assertEquals(200, response.code)
            sessionIdA = response.header("Mcp-Session-Id")!!
        }
        assertNotNull(sessionIdA)
        assertEquals(leaseA.leaseId, McpSessionRegistry.getTarget(sessionIdA))

        // Same session ID on Lease A -> 200
        val followUpA = Request.Builder()
            .url("$baseUrl/mcp/acp/${leaseA.leaseId}")
            .addHeader("Authorization", "Bearer ${leaseA.token}")
            .addHeader("Mcp-Session-Id", sessionIdA)
            .post(createPingBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(followUpA).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Cross-use session ID on Lease B -> 403
        val crossLeaseB = Request.Builder()
            .url("$baseUrl/mcp/acp/${leaseB.leaseId}")
            .addHeader("Authorization", "Bearer ${leaseB.token}")
            .addHeader("Mcp-Session-Id", sessionIdA)
            .post(createPingBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(crossLeaseB).execute().use { response ->
            assertEquals(403, response.code)
        }

        // Cross-use session ID on Global /mcp -> 403
        val crossGlobal = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .addHeader("Mcp-Session-Id", sessionIdA)
            .post(createPingBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(crossGlobal).execute().use { response ->
            assertEquals(403, response.code)
        }
    }

    @Test
    fun `global session ID cannot be used on ACP lease endpoint`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentGlobalCheck", 501L, owner)

        // Initialize on /mcp to obtain global session ID
        val initGlobal = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()

        val globalSessionId: String
        client.newCall(initGlobal).execute().use { response ->
            assertEquals(200, response.code)
            globalSessionId = response.header("Mcp-Session-Id")!!
        }
        assertEquals(McpSessionRegistry.GLOBAL_TARGET, McpSessionRegistry.getTarget(globalSessionId))

        // Use global session ID on ACP lease endpoint -> 403
        val crossOnLease = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("Mcp-Session-Id", globalSessionId)
            .post(createPingBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(crossOnLease).execute().use { response ->
            assertEquals(403, response.code)
        }
    }

    @Test
    fun `DELETE cleans up Mcp-Session-Id and prevents subsequent calls`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentDelete", 601L, owner)

        val initRequest = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()

        val sessionId: String
        client.newCall(initRequest).execute().use { response ->
            assertEquals(200, response.code)
            sessionId = response.header("Mcp-Session-Id")!!
        }

        // DELETE request to terminate the session
        val deleteRequest = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("Mcp-Session-Id", sessionId)
            .delete()
            .build()
        client.newCall(deleteRequest).execute().use { response ->
            assertTrue(response.isSuccessful)
        }

        // Verify sessionId is unregistered
        assertFalse(McpSessionRegistry.isRegisteredForTarget(sessionId, lease.leaseId))

        // Subsequent call with the deleted sessionId -> 403
        val postAfterDelete = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("Mcp-Session-Id", sessionId)
            .post(createPingBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(postAfterDelete).execute().use { response ->
            assertEquals(403, response.code)
        }
    }

    @Test
    fun `physical socket remoteHost check rejects non-local addresses and ignores spoofed Forwarded headers`() {
        // Assert isLocalSocket logic directly
        assertTrue(McpServerManager.isLocalSocket("127.0.0.1"))
        assertTrue(McpServerManager.isLocalSocket("::1"))
        assertTrue(McpServerManager.isLocalSocket("[::1]"))
        assertTrue(McpServerManager.isLocalSocket("0:0:0:0:0:0:0:1"))

        assertFalse(McpServerManager.isLocalSocket("localhost"))
        assertFalse(McpServerManager.isLocalSocket("192.168.1.100"))
        assertFalse(McpServerManager.isLocalSocket("10.0.0.1"))
        assertFalse(McpServerManager.isLocalSocket("172.16.0.1"))
        assertFalse(McpServerManager.isLocalSocket("example.com"))
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentSpoof", 701L, owner)

        // Attempting to spoof local address via headers over real local connection:
        // Even with X-Forwarded-For: 192.168.1.1, real socket is local, and Host must be allowed
        val spoofRequest = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("X-Forwarded-For", "192.168.1.1")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(spoofRequest).execute().use { response ->
            assertEquals(200, response.code)
        }
    }

    @Test
    fun `catalog lease without session binding cannot execute android_system_action`() {
        val owner = Any()
        // Catalog lease has conversationId = null and is not bound to a turn session
        val catalogLease = AcpSessionMcpLeaseStore.prepare("catalogAgent", null, owner)

        // Modern MCP tools/call to execute android_system_action
        val modernCallBody = """
            {
              "jsonrpc": "2.0",
              "id": "req-1",
              "method": "tools/call",
              "params": {
                "name": "android_system_action",
                "arguments": {
                  "action": "device_control.get_state",
                  "arguments": {
                    "target": "flashlight"
                  }
                },
                "_meta": {
                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                  "io.modelcontextprotocol/clientCapabilities": { "tools": {} }
                }
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$baseUrl/mcp/acp/${catalogLease.leaseId}")
            .addHeader("Authorization", "Bearer ${catalogLease.token}")
            .addHeader("MCP-Protocol-Version", "2026-07-28")
            .addHeader("Mcp-Method", "tools/call")
            .addHeader("Mcp-Name", "android_system_action")
            .post(modernCallBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            val body = Json.parseToJsonElement(response.body.string()).jsonObject
            val result = body.getValue("result").jsonObject
            assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `revoking lease rejects subsequent calls with 401`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentRevoke", 801L, owner)

        // First call succeeds
        val request1 = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request1).execute().use { response ->
            assertEquals(200, response.code)
        }

        // Revoke the lease
        AcpSessionMcpLeaseStore.revokeLease(lease.leaseId)

        // Subsequent call is rejected with 401
        val request2 = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request2).execute().use { response ->
            assertEquals(401, response.code)
        }
    }

    @Test
    fun `modern MCP protocol tools list exposes android_system_action on ACP lease but not on global`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentToolsList", 901L, owner)

        val modernListBody = """
            {
              "jsonrpc": "2.0",
              "id": "list-1",
              "method": "tools/list",
              "params": {
                "_meta": {
                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                  "io.modelcontextprotocol/clientCapabilities": { "tools": {} }
                }
              }
            }
        """.trimIndent()

        // Tools list on ACP lease endpoint -> contains android_system_action
        val leaseListRequest = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("MCP-Protocol-Version", "2026-07-28")
            .addHeader("Mcp-Method", "tools/list")
            .post(modernListBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(leaseListRequest).execute().use { response ->
            assertEquals(200, response.code)
            val body = Json.parseToJsonElement(response.body.string()).jsonObject
            val tools = body.getValue("result").jsonObject.getValue("tools").jsonArray
            assertTrue(tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "android_system_action" })
            assertTrue(tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "system_clock_action" })
        }

        // Tools list on global /mcp -> does NOT contain android_system_action
        val globalListRequest = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .addHeader("MCP-Protocol-Version", "2026-07-28")
            .addHeader("Mcp-Method", "tools/list")
            .post(modernListBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(globalListRequest).execute().use { response ->
            assertEquals(200, response.code)
            val body = Json.parseToJsonElement(response.body.string()).jsonObject
            val tools = body.getValue("result").jsonObject.getValue("tools").jsonArray
            assertFalse(tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "android_system_action" })
            assertTrue(tools.any { it.jsonObject.getValue("name").jsonPrimitive.content == "system_clock_action" })
        }
    }

    @Test
    fun `android_system_action with forbidden override parameter is rejected`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("agentParamCheck", 1001L, owner)

        val forbiddenBody = """
            {
              "jsonrpc": "2.0",
              "id": "call-forbidden",
              "method": "tools/call",
              "params": {
                "name": "android_system_action",
                "arguments": {
                  "action": "device_control.get_state",
                  "arguments": {
                    "target": "flashlight",
                    "confirmed": "true"
                  }
                },
                "_meta": {
                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                  "io.modelcontextprotocol/clientCapabilities": { "tools": {} }
                }
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("MCP-Protocol-Version", "2026-07-28")
            .addHeader("Mcp-Method", "tools/call")
            .addHeader("Mcp-Name", "android_system_action")
            .post(forbiddenBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            val body = Json.parseToJsonElement(response.body.string()).jsonObject
            val result = body.getValue("result").jsonObject
            assertTrue(result.getValue("isError").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `registry collision detection does not overwrite existing mapping and lease revocation cleans registry`() {
        val sid = "test-session-${UUID.randomUUID()}"
        // Register to Target A
        assertTrue(McpSessionRegistry.register(sid, "lease-A"))
        assertEquals("lease-A", McpSessionRegistry.getTarget(sid))

        // Registering same session to Target A again succeeds idempotently
        assertTrue(McpSessionRegistry.register(sid, "lease-A"))
        assertEquals("lease-A", McpSessionRegistry.getTarget(sid))

        // Collision: registering same session to Target B fails and does NOT overwrite Target A
        assertFalse(McpSessionRegistry.register(sid, "lease-B"))
        assertEquals("lease-A", McpSessionRegistry.getTarget(sid))

        // Revoking lease-A cleans up the session mapping
        McpSessionRegistry.revokeLease("lease-A")
        assertNull(McpSessionRegistry.getTarget(sid))
    }

    @Test
    fun `immediate concurrent follow-up request with generated session ID succeeds without registration race`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("raceAgent", 2001L, owner)

        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val futures = (1..10).map {
            executor.submit(java.util.concurrent.Callable {
                val initReq = Request.Builder()
                    .url("$baseUrl/mcp/acp/${lease.leaseId}")
                    .addHeader("Authorization", "Bearer ${lease.token}")
                    .post(createInitializeBody().toRequestBody(jsonMediaType))
                    .build()

                val sessionId: String
                client.newCall(initReq).execute().use { resp ->
                    assertEquals(200, resp.code)
                    sessionId = resp.header("Mcp-Session-Id")!!
                }

                val followUpReq = Request.Builder()
                    .url("$baseUrl/mcp/acp/${lease.leaseId}")
                    .addHeader("Authorization", "Bearer ${lease.token}")
                    .addHeader("Mcp-Session-Id", sessionId)
                    .post(createPingBody().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(followUpReq).execute().use { resp ->
                    assertEquals("Race condition: session ID was not registered in time!", 200, resp.code)
                }
            })
        }

        futures.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
        executor.shutdown()
    }

    @Test
    fun `Modern MCP rejects calendar_event_batch when raw arguments exceed 256KiB via Unicode escape or whitespace`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("calendarOverLimitAgentModern", 3001L, owner)

        // Construct padding of 270KiB of escaped spaces "\u0020"
        val padding = "\\u0020".repeat(46_000) // 46000 * 6 = 276,000 bytes > 256KiB
        val rawBody = """
            {
              "jsonrpc": "2.0",
              "id": "cal-over-modern",
              "method": "tools/call",
              "params": {
                "name": "calendar_event_batch",
                "arguments": {
                  "operations": [],
                  "padding": "$padding"
                },
                "_meta": {
                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                  "io.modelcontextprotocol/clientCapabilities": { "tools": {} }
                }
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("MCP-Protocol-Version", "2026-07-28")
            .addHeader("Mcp-Method", "tools/call")
            .addHeader("Mcp-Name", "calendar_event_batch")
            .post(rawBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()
            // Returns 400 with -32602 error message
            assertEquals(400, response.code)
            assertTrue(bodyStr.contains("日历批量请求数据过大") || bodyStr.contains("-32602"))
        }
    }

    @Test
    fun `SDK Streamable HTTP rejects calendar_event_batch when raw arguments exceed 256KiB via Unicode escape or whitespace`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("calendarOverLimitAgentSdk", 3002L, owner)

        // Initialize session first
        val initReq = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()

        val sessionId: String
        client.newCall(initReq).execute().use { resp ->
            assertEquals(200, resp.code)
            sessionId = resp.header("Mcp-Session-Id")!!
        }

        // Send standard JSON-RPC tools/call through SDK with > 256KiB raw arguments
        val padding = "\\u0020".repeat(46_000) // > 256KiB
        val sdkCallBody = """
            {
              "jsonrpc": "2.0",
              "id": 999,
              "method": "tools/call",
              "params": {
                "name": "calendar_event_batch",
                "arguments": {
                  "operations": [],
                  "padding": "$padding"
                }
              }
            }
        """.trimIndent()

        val sdkRequest = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .addHeader("Mcp-Session-Id", sessionId)
            .post(sdkCallBody.toRequestBody(jsonMediaType))
            .build()

        client.newCall(sdkRequest).execute().use { response ->
            // The receivePipeline intercepts and rejects before dispatch
            assertFalse("Request with > 256KiB raw arguments must not succeed", response.isSuccessful && !response.body.string().contains("error"))
        }
    }

    @Test
    fun `real buildServer startup handles both bare global and acp lease initialize smoke and rejects invalid tails with 404`() {
        val owner = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("startupSmokeAgent", 4001L, owner)

        // 1. Bare /mcp initialize smoke
        val globalInitReq = Request.Builder()
            .url("$baseUrl/mcp")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        val globalSessionId: String
        client.newCall(globalInitReq).execute().use { resp ->
            assertEquals(200, resp.code)
            globalSessionId = resp.header("Mcp-Session-Id")!!
        }
        assertEquals(McpSessionRegistry.GLOBAL_TARGET, McpSessionRegistry.getTarget(globalSessionId))

        // 2. /mcp/acp/{leaseId} initialize smoke
        val acpInitReq = Request.Builder()
            .url("$baseUrl/mcp/acp/${lease.leaseId}")
            .addHeader("Authorization", "Bearer ${lease.token}")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        val acpSessionId: String
        client.newCall(acpInitReq).execute().use { resp ->
            assertEquals(200, resp.code)
            acpSessionId = resp.header("Mcp-Session-Id")!!
        }
        assertEquals(lease.leaseId, McpSessionRegistry.getTarget(acpSessionId))

        // 3. Invalid tail /mcp/invalid/tail returns 404
        val invalidTailReq = Request.Builder()
            .url("$baseUrl/mcp/invalid/tail")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(invalidTailReq).execute().use { resp ->
            assertEquals(404, resp.code)
        }

        // 4. Invalid tail /mcp/acp/ returns 404
        val emptyLeaseReq = Request.Builder()
            .url("$baseUrl/mcp/acp/")
            .addHeader("Authorization", "Bearer $GLOBAL_TOKEN")
            .post(createInitializeBody().toRequestBody(jsonMediaType))
            .build()
        client.newCall(emptyLeaseReq).execute().use { resp ->
            assertEquals(404, resp.code)
        }
    }

    private fun createInitializeBody(): String = """
        {
          "jsonrpc": "2.0",
          "id": 1,
          "method": "initialize",
          "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {
              "name": "test-client",
              "version": "1.0.0"
            }
          }
        }
    """.trimIndent()

    private fun createPingBody(): String = """
        {
          "jsonrpc": "2.0",
          "id": 2,
          "method": "ping"
        }
    """.trimIndent()
}
