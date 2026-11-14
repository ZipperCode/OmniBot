package cn.com.omnimind.bot.agent.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.webchat.ConversationDomainService
import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AgentRuntimeAdmissionTest {
    private val managers = mutableListOf<AgentRuntimeManager>()

    private fun fixture(): Pair<Context, AgentRuntimeManager> {
        val app: Application = RuntimeEnvironment.getApplication()
        com.rk.libcommons.application = app
        DatabaseHelper.init(app)
        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(anyString())).thenAnswer { call ->
            File("src/main/assets", call.getArgument<String>(0)).inputStream()
        }
        val context = object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = assets
            override fun getApplicationContext(): Context = this
        }
        val constructor = AgentRuntimeManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return context to constructor.newInstance(context).also(managers::add)
    }

    @After fun closeScopes() {
        managers.forEach { (field(it, "scope") as CoroutineScope).cancel() }
    }

    private fun field(manager: AgentRuntimeManager, name: String): Any? =
        AgentRuntimeManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager)

    @Test fun directoryAndMissingIdentityNeverCreateAnAgentTransport() = runBlocking {
        val (_, manager) = fixture()
        val catalog = manager.handleMethod("agent/list", emptyMap()) as Map<*, *>
        assertFalse(catalog.containsKey("selectedAgentId"))
        assertTrue((catalog["agents"] as List<*>).isNotEmpty())
        for (method in listOf("status", "initialize", "session/new")) {
            val error = runCatching { manager.handleMethod(method, emptyMap()) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("agent_identity_required"))
        }
        assertTrue((field(manager, "localAcpRuntimes") as Map<*, *>).isEmpty())
        assertNull(field(manager, "session"))
    }

    @Test fun unknownMethodDoesNotLaunchItsExplicitAgentAndRemoteSettingsCannotRedirectLocalStatus() = runBlocking {
        val (context, manager) = fixture()
        CodexRemoteBridgeConfigStore(context).write(CodexRemoteBridgeConfig(
            enabled = true, bridgeUrl = "http://127.0.0.1:1", cwd = "/remote",
        ))
        val error = runCatching {
            manager.handleMethod("session/typo", mapOf("agentId" to "opencode-acp"))
        }.exceptionOrNull()
        assertTrue(error is UnsupportedOperationException)
        val runtime = (field(manager, "localAcpRuntimes") as Map<*, *>)["opencode-acp"] as LocalAcpRuntime
        assertFalse(runtime.isConnected)
        assertFalse(File(context.applicationInfo.dataDir, "local").exists())
        assertNull(field(manager, "session"))
        val status = manager.handleMethod("status", mapOf("agentId" to "xiaowan-acp")) as Map<*, *>
        assertEquals("xiaowan-acp", status["activeAgentId"])
        assertEquals("local", status["runtime"])
        assertNull(field(manager, "session"))
    }

    @Test fun remoteConversationCancellationDoesNotConsultAnUnrelatedLocalSessionIndex() = runBlocking {
        val (context, manager) = fixture()
        val profiles = AcpAgentProfileStore(context)
        val domain = ConversationDomainService(context)
        val local = domain.createConversation("Local", "agent", agentId = "xiaowan-acp")
        profiles.bindSession("same-session-id", "xiaowan-acp")
        AgentSessionBindingRepository(context).ensureBinding(
            "same-session-id", "xiaowan-acp", (local.getValue("id") as Number).toLong(),
        )
        val remote = domain.createConversation("Remote", "agent", agentId = REMOTE_CODEX_AGENT_ID)
        val id = (remote.getValue("id") as Number).toLong()
        val response = manager.handleMethod("session/cancel", mapOf(
            "conversationId" to id, "sessionId" to "same-session-id",
        )) as Map<*, *>
        assertEquals(false, response["cancelled"])
        assertEquals(REMOTE_CODEX_AGENT_ID, profiles.agentIdForConversation(id))
        assertEquals("xiaowan-acp", profiles.agentIdForSession("same-session-id"))
        assertTrue((field(manager, "localAcpRuntimes") as Map<*, *>).isEmpty())
        assertNull(field(manager, "session"))
    }

    @Test fun remoteExtensionsNotificationsAndAuthenticationPreserveOnlyTheirWirePayload() = runBlocking {
        val (context, manager) = fixture()
        CodexRemoteBridgeConfigStore(context).write(CodexRemoteBridgeConfig(
            enabled = true, bridgeUrl = "http://127.0.0.1:1", cwd = "/remote",
        ))
        val connection = EchoConnection()
        val transport = RemoteCodexAppServerSession(this, {}, { connection })
        transport.start("test")
        AgentRuntimeManager::class.java.getDeclaredField("session").apply { isAccessible = true }
            .set(manager, transport)
        try {
            for (params in listOf<Any?>(
                mapOf("agentId" to "vendor-id", "params" to listOf(1, 2)),
                listOf("one", false), "scalar", 7, false, null,
            )) {
                manager.handleMethod("_vendor/call", mapOf("agentId" to REMOTE_CODEX_AGENT_ID, "params" to params))
                val request = connection.writes.last { it["method"]?.asString == "_vendor/call" }
                assertEquals(Gson().toJsonTree(params), request["params"] ?: JsonNull.INSTANCE)
            }
            manager.handleMethod("notifyAcpExtension", mapOf(
                "agentId" to REMOTE_CODEX_AGENT_ID, "method" to "_vendor/changed", "params" to listOf(1, 2),
            ))
            val notification = connection.writes.last()
            assertEquals("_vendor/changed", notification["method"].asString)
            assertFalse(notification.has("id"))
            assertEquals(JsonParser.parseString("[1,2]"), notification["params"])
            for ((hostMethod, wireMethod) in listOf(
                "auth/authenticate" to "authenticate", "auth/logout" to "logout",
                "auth/providers/list" to "providers/list", "auth/providers/set" to "providers/set",
                "auth/providers/disable" to "providers/disable",
            )) {
                manager.handleMethod(hostMethod, mapOf("agentId" to REMOTE_CODEX_AGENT_ID, "methodId" to "login"))
                val request = connection.writes.last { it["method"]?.asString == wireMethod }
                assertEquals(JsonParser.parseString("""{"methodId":"login"}"""), request["params"])
            }
        } finally {
            transport.disconnect()
        }
    }

    @Test fun conversationRunBridgeIsSharedSingletonOnAgentRuntimeManager() = runBlocking {
        val (context, manager) = fixture()
        val bridge1 = manager.conversationRunBridge
        val bridge2 = manager.conversationRunBridge
        assertSame(bridge1, bridge2)
        val runService = cn.com.omnimind.bot.webchat.AgentRunService(context)
        val scheduler = cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler(context)
        assertNotNull(runService)
        assertNotNull(scheduler)
    }

    @Test fun remoteTurnOwnershipTracksActiveTurnIdAndAllowsSubsequentAdmissions() = runBlocking {
        val (_, manager) = fixture()
        val remoteTurnOwnership = field(manager, "remoteTurnOwnership") as AcpTurnOwnershipRegistry
        assertNull(remoteTurnOwnership.activeTurnId("test-session"))
        val reservation = remoteTurnOwnership.adopt("test-session", "turn-1", "req-1")
        assertTrue(reservation is AcpTurnReservation.Started)
        assertEquals("turn-1", remoteTurnOwnership.activeTurnId("test-session"))
        val finished = remoteTurnOwnership.finish("test-session", "turn-1", status = "completed")
        assertNotNull(finished)
        assertNull(remoteTurnOwnership.activeTurnId("test-session"))
        val nextReservation = remoteTurnOwnership.adopt("test-session", "turn-2", "req-2")
        assertTrue(nextReservation is AcpTurnReservation.Started)
        assertEquals("turn-2", remoteTurnOwnership.activeTurnId("test-session"))
        remoteTurnOwnership.finish("test-session", "turn-2", status = "error")
        assertNull(remoteTurnOwnership.activeTurnId("test-session"))
    }

    @Test fun remoteTurnBusyDoesNotOverwriteActiveOwner() = runBlocking {
        val (_, manager) = fixture()
        val remoteTurnOwnership = field(manager, "remoteTurnOwnership") as AcpTurnOwnershipRegistry
        val first = remoteTurnOwnership.adopt("session-busy-test", "turn-first", "req-first")
        assertTrue(first is AcpTurnReservation.Started)
        assertEquals("turn-first", remoteTurnOwnership.activeTurnId("session-busy-test"))
        val second = remoteTurnOwnership.adopt("session-busy-test", "turn-second", "req-second")
        assertTrue(second is AcpTurnReservation.Busy)
        assertEquals("turn-first", (second as AcpTurnReservation.Busy).record.turnId)
        assertEquals("turn-first", remoteTurnOwnership.activeTurnId("session-busy-test"))
        remoteTurnOwnership.finish("session-busy-test", "turn-first", status = "completed")
        assertNull(remoteTurnOwnership.activeTurnId("session-busy-test"))
    }

    private class EchoConnection : RemoteCodexAppServerConnection {
        override val isRunning = true
        val writes = mutableListOf<com.google.gson.JsonObject>()
        private lateinit var stdout: suspend (String) -> Unit
        override suspend fun start(
            onStdoutLine: suspend (String) -> Unit,
            onStderrLine: suspend (String) -> Unit,
            onExit: suspend (Int?) -> Unit,
        ) { stdout = onStdoutLine }
        override suspend fun writeLine(line: String) {
            val request = JsonParser.parseString(line).asJsonObject
            writes += request
            request["id"]?.let { stdout("""{"jsonrpc":"2.0","id":$it,"result":{}}""") }
        }
        override suspend fun close() = Unit
    }
}
