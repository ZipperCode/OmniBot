package cn.com.omnimind.bot.agent.runtime

import android.app.Application
import android.content.Context
import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import cn.com.omnimind.bot.agent.tool.handlers.StructuredDeviceActionContext
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.mcp.McpSessionRegistry
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.Client
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.common.SessionCreationParameters
import com.agentclientprotocol.model.*
import com.rk.terminal.runtime.TerminalDistribution
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AcpSessionMcpRuntimeLifecycleTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var previousToken: Any? = null
    private var previousServer: Any? = null
    private var previousIsRunning: Boolean = false
    private var previousActiveHost: Any? = null
    private var testServer: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var mockedMMKV: MockedStatic<MMKV>? = null

    private val tokenField = McpServerManager::class.java.getDeclaredField("cachedToken").apply { isAccessible = true }
    private val isRunningField = McpServerManager::class.java.getDeclaredField("isRunning").apply { isAccessible = true }
    private val activeHostField = McpServerManager::class.java.getDeclaredField("activeHost").apply { isAccessible = true }
    private val serverField = McpServerManager::class.java.getDeclaredField("server").apply { isAccessible = true }

    @Before
    fun setUp() {
        AcpSessionMcpLeaseStore.revokeAll()

        previousToken = tokenField.get(McpServerManager)
        previousIsRunning = isRunningField.getBoolean(McpServerManager)
        previousActiveHost = activeHostField.get(McpServerManager)
        previousServer = serverField.get(McpServerManager)
        val app = RuntimeEnvironment.getApplication()
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

        // Start initial server on loopback with intentionally stale host to regression test
        // that prepareSessionMcpLease ensures running (and restarts stale server) BEFORE preparing the lease
        val s = McpServerManager.buildServer(
            context = app,
            port = 0,
            lanHost = "127.0.0.1",
        ).start(wait = false)
        testServer = s

        tokenField.set(McpServerManager, "test-mcp-token")
        isRunningField.set(McpServerManager, true)
        activeHostField.set(McpServerManager, "127.0.0.1:stale")
        serverField.set(McpServerManager, s)
    }

    @After
    fun tearDown() {
        val currentServer = serverField.get(McpServerManager) as? io.ktor.server.engine.EmbeddedServer<*, *>
        currentServer?.stop(0, 1_000)
        testServer?.stop(0, 1_000)
        testServer = null

        tokenField.set(McpServerManager, previousToken)
        isRunningField.set(McpServerManager, previousIsRunning)
        activeHostField.set(McpServerManager, previousActiveHost)
        serverField.set(McpServerManager, previousServer)

        McpSessionRegistry.clear()
        AcpSessionMcpLeaseStore.revokeAll()

        mockedMMKV?.close()
        mockedMMKV = null
    }

    private class TestHarness(
        val runtime: LocalAcpRuntime,
        val repository: AgentSessionBindingRepository,
        val store: AcpAgentProfileStore,
        val client: Client,
        val scope: CoroutineScope,
        val turnOwnership: AcpTurnOwnershipRegistry,
        val connection: AcpRuntimeConnection,
        val ownerAgentId: String,
        val bindings: ConcurrentHashMap<String, AgentSessionBinding>,
        val promptJobs: MutableList<Job> = mutableListOf(),
    ) {
        var newSessionResult: ClientSession? = null
        var capturedParameters: SessionCreationParameters? = null

        fun newPromptJob(): Job = Job().also { promptJobs += it }

        fun mockSession(sessionId: String): ClientSession {
            val session = mock(ClientSession::class.java) { invocation ->
                when (invocation.method.name.substringBefore('-')) {
                    "close" -> CloseSessionResponse()
                    "cancel" -> Unit
                    else -> RETURNS_DEFAULTS.answer(invocation)
                }
            }
            `when`(session.sessionId).thenReturn(SessionId(sessionId))
            return session
        }

        fun cleanup() {
            promptJobs.forEach { it.cancel() }
            scope.cancel()
            AcpSessionMcpLeaseStore.revokeAll()
        }
    }

    private fun createHarness(ownerAgentId: String = AcpAgentProfileStore.CODEX_AGENT_ID): TestHarness {
        val app = RuntimeEnvironment.getApplication()
        val context = acpProfileStoreTestContext(folder.root)
        `when`(context.applicationContext).thenReturn(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val profileStore = AcpAgentProfileStore(context)
        val ownership = AcpTurnOwnershipRegistry()
        val connection = mock(AcpRuntimeConnection::class.java)
        `when`(connection.exitSignal).thenReturn(CompletableDeferred())
        `when`(connection.materializesPromptAttachments).thenReturn(true)

        val bindings = ConcurrentHashMap<String, AgentSessionBinding>()
        val repository = mock(AgentSessionBindingRepository::class.java) { invocation ->
            when (invocation.method.name) {
                "getBindingByThreadId" -> bindings[invocation.arguments[0] as? String]
                "getBindingByConversationId" -> {
                    val convId = invocation.arguments[0] as? Long
                    bindings.values.firstOrNull { it.conversationId == convId }
                }
                "setArchived" -> Unit
                "detachThread" -> {
                    val threadId = invocation.arguments[0] as? String
                    bindings.remove(threadId)?.conversationId
                }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }

        val harnessRef = AtomicReference<TestHarness>()
        val client = mock(Client::class.java) { invocation ->
            when {
                invocation.method.name.startsWith("newSession") -> {
                    val params = invocation.arguments[0] as? SessionCreationParameters
                    harnessRef.get()?.capturedParameters = params
                    harnessRef.get()?.newSessionResult ?: error("newSessionResult not configured")
                }
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        }

        val runtime = LocalAcpRuntime(
            context = context,
            scope = scope,
            bindingRepository = repository,
            profileStore = profileStore,
            ownerAgentId = ownerAgentId,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = {},
            turnOwnership = ownership,
        )

        val profile = profileStore.requireProfile(ownerAgentId)
        runtime.setField("activeProfile", profile)

        val agentInfo = mock(AgentInfo::class.java)
        `when`(agentInfo.capabilities).thenReturn(
            AgentCapabilities(
                loadSession = true,
                sessionCapabilities = SessionCapabilities(
                    resume = SessionResumeCapabilities(),
                    delete = SessionDeleteCapabilities(),
                    close = SessionCloseCapabilities(),
                ),
                mcpCapabilities = McpCapabilities(http = true, sse = false),
            )
        )
        runtime.setField("agentInfo", agentInfo)
        runtime.setField("client", client)
        runtime.setField("connection", connection)

        val harness = TestHarness(
            runtime = runtime,
            repository = repository,
            store = profileStore,
            client = client,
            scope = scope,
            turnOwnership = ownership,
            connection = connection,
            ownerAgentId = ownerAgentId,
            bindings = bindings,
        )
        harnessRef.set(harness)
        return harness
    }

    @Test
    fun catalogSessionReusedWithDynamicConversationResolution() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                val session = harness.mockSession("catalog-session-1")
                harness.newSessionResult = session

                // Step 1: Catalog session created
                val catalogSession = harness.runtime.invokeMethod(
                    "ensureCatalogSession",
                    mapOf("cwd" to "/workspace")
                ) as ClientSession
                assertEquals("catalog-session-1", catalogSession.sessionId.value)

                // Verify that the catalog session creation parameters received the real local MCP server declaration
                val localMcpServer = harness.capturedParameters?.mcpServers?.filterIsInstance<McpServer.Http>()?.firstOrNull()
                assertNotNull("Catalog session parameters must declare local device MCP server", localMcpServer)
                assertTrue("Local MCP URL must match acp path pattern", localMcpServer!!.url.contains("/mcp/acp/"))

                // Reusing catalog session returns the same cached instance
                val reusedSession = harness.runtime.invokeMethod(
                    "ensureCatalogSession",
                    mapOf("cwd" to "/workspace")
                ) as ClientSession
                assertSame(catalogSession, reusedSession)

                // At this point, the catalog session was prepared with conversationId = null
                // Resolving executionContext must fail with conversation_required because repository binding is null
                val resolver = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "catalog-session-1",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)

                // Activate turn so turn_inactive does not trigger
                harness.turnOwnership.reserve("catalog-session-1", "turn-1", "req-1")
                val promptJob = harness.newPromptJob()
                val promptExecution = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["catalog-session-1"] = promptExecution

                try {
                    resolver("call-1")
                    fail("Expected conversation_required when catalog session has no binding")
                } catch (error: IllegalStateException) {
                    assertEquals("conversation_required", error.message)
                }

                // Step 2: Now bind the conversation dynamically
                harness.bindings["catalog-session-1"] = AgentSessionBinding(
                    conversationId = 999L,
                    threadId = "catalog-session-1",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                // Dynamic lookup must now succeed and return real conversationId 999L!
                val context = resolver("call-1")
                assertEquals(999L, context.conversationId)
                assertEquals("catalog-session-1", context.sessionId)
                assertEquals("turn-1", context.turnId)
                assertTrue(context.isValid())
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun turnInactiveWhenNoActiveTurnOrCancelledPromptJob() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                val resolver = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-turn-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)

                harness.bindings["session-turn-test"] = AgentSessionBinding(
                    conversationId = 100L,
                    threadId = "session-turn-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                // 1. No active turn
                try {
                    resolver("call-1")
                    fail("Expected turn_inactive when no active turn")
                } catch (error: IllegalStateException) {
                    assertEquals("turn_inactive", error.message)
                }

                // 2. Active turn reserved, but prompt execution missing
                harness.turnOwnership.reserve("session-turn-test", "turn-1", "req-1")
                try {
                    resolver("call-1")
                    fail("Expected turn_inactive when prompt execution missing")
                } catch (error: IllegalStateException) {
                    assertEquals("turn_inactive", error.message)
                }

                // 3. Prompt execution present, but promptJob cancelled
                val promptJob = harness.newPromptJob().apply { cancel() }
                val promptExecution = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["session-turn-test"] = promptExecution
                try {
                    resolver("call-1")
                    fail("Expected turn_inactive when promptJob is inactive")
                } catch (error: IllegalStateException) {
                    assertEquals("turn_inactive", error.message)
                }
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun finishTurnPreservesSessionLeaseForNextTurn() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                val lease = AcpSessionMcpLeaseStore.prepare(harness.ownerAgentId, 200L, harness.connection)
                harness.bindings["session-finish-test"] = AgentSessionBinding(
                    conversationId = 200L,
                    threadId = "session-finish-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                val resolver = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-finish-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)

                AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-finish-test", resolver)

                // Turn 1 active
                harness.turnOwnership.reserve("session-finish-test", "turn-1", "req-1")
                val promptJob1 = harness.newPromptJob()
                val promptExec1 = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob1)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["session-finish-test"] = promptExec1

                val context1 = AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-1")
                assertEquals("turn-1", context1.turnId)
                assertTrue(context1.isValid())

                // Turn 1 finishes: promptJob cancels, turn finished
                promptJob1.cancel()
                harness.runtime.promptExecutions().remove("session-finish-test")
                harness.runtime.invokeMethod(
                    "finishTurn",
                    "session-finish-test",
                    "turn-1",
                    "completed",
                    null,
                    false
                )

                // Old in-flight context isValid is now false
                assertFalse(context1.isValid())

                // Lease MUST NOT be revoked!
                assertNotNull(AcpSessionMcpLeaseStore.resolve(lease.leaseId, lease.token))

                // Between turns, executionContext throws turn_inactive
                try {
                    AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-late")
                    fail("Expected turn_inactive between turns")
                } catch (error: IllegalStateException) {
                    assertEquals("turn_inactive", error.message)
                }

                // Turn 2 begins on the SAME session: Harness uses the SAME lease token
                harness.turnOwnership.reserve("session-finish-test", "turn-2", "req-2")
                val promptJob2 = harness.newPromptJob()
                val promptExec2 = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob2)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["session-finish-test"] = promptExec2

                val context2 = AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-2")
                assertEquals("turn-2", context2.turnId)
                assertTrue(context2.isValid())
                assertSame(promptExec2.actionJob(), context2.turnJob)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun disconnectRevokesAllLeasesForConnection() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                val session = harness.mockSession("session-disconnect-test")
                harness.runtime.sessions()["session-disconnect-test"] = session
                harness.bindings["session-disconnect-test"] = AgentSessionBinding(
                    conversationId = 300L,
                    threadId = "session-disconnect-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                val lease = AcpSessionMcpLeaseStore.prepare(harness.ownerAgentId, 300L, harness.connection)
                val resolver = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-disconnect-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)
                AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-disconnect-test", resolver)
                assertNotNull(AcpSessionMcpLeaseStore.resolve(lease.leaseId, lease.token))

                harness.turnOwnership.reserve("session-disconnect-test", "turn-1", "req-1")
                val promptJob = harness.newPromptJob()
                val promptExec = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["session-disconnect-test"] = promptExec

                val context = AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-disconnect-1")
                assertTrue(context.turnJob.isActive)
                assertTrue(context.isValid())

                harness.runtime.disconnect()

                assertNull(AcpSessionMcpLeaseStore.resolve(lease.leaseId, lease.token))
                assertTrue(context.turnJob.isCancelled)
                assertFalse(context.isValid())
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun closeAndArchiveRevokeLeaseForSession() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                // 1. session/close revokes lease
                val sessionClose = harness.mockSession("session-close-test")
                harness.runtime.sessions()["session-close-test"] = sessionClose
                harness.bindings["session-close-test"] = AgentSessionBinding(
                    conversationId = 400L,
                    threadId = "session-close-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                val leaseClose = AcpSessionMcpLeaseStore.prepare(harness.ownerAgentId, 400L, harness.connection)
                val resolverClose = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-close-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)
                AcpSessionMcpLeaseStore.bind(leaseClose.leaseId, "session-close-test", resolverClose)
                assertNotNull(AcpSessionMcpLeaseStore.resolve(leaseClose.leaseId, leaseClose.token))

                harness.runtime.handleMethod("session/close", mapOf("sessionId" to "session-close-test"))
                assertNull(AcpSessionMcpLeaseStore.resolve(leaseClose.leaseId, leaseClose.token))

                // 2. session/archive revokes lease
                val sessionArchive = harness.mockSession("session-archive-test")
                harness.runtime.sessions()["session-archive-test"] = sessionArchive
                harness.bindings["session-archive-test"] = AgentSessionBinding(
                    conversationId = 401L,
                    threadId = "session-archive-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                val leaseArchive = AcpSessionMcpLeaseStore.prepare(harness.ownerAgentId, 401L, harness.connection)
                val resolverArchive = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-archive-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)
                AcpSessionMcpLeaseStore.bind(leaseArchive.leaseId, "session-archive-test", resolverArchive)
                assertNotNull(AcpSessionMcpLeaseStore.resolve(leaseArchive.leaseId, leaseArchive.token))

                harness.runtime.handleMethod("session/archive", mapOf("sessionId" to "session-archive-test"))
                assertNull(AcpSessionMcpLeaseStore.resolve(leaseArchive.leaseId, leaseArchive.token))
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun turnInterruptionCancelsActionJobEnsuringZeroWriteOnSuspendedActions() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                val session = harness.mockSession("session-cancel-test")
                harness.runtime.sessions()["session-cancel-test"] = session

                val lease = AcpSessionMcpLeaseStore.prepare(harness.ownerAgentId, 500L, harness.connection)
                harness.bindings["session-cancel-test"] = AgentSessionBinding(
                    conversationId = 500L,
                    threadId = "session-cancel-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                val resolver = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-cancel-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)

                AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-cancel-test", resolver)

                harness.turnOwnership.reserve("session-cancel-test", "turn-1", "req-1")
                val promptJob = harness.newPromptJob()
                val promptExec = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["session-cancel-test"] = promptExec

                val context = AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-cancel-1")
                val actionJob = context.turnJob
                assertTrue(actionJob.isActive)
                assertTrue(context.isValid())

                var writeExecuted = false
                val readStarted = CompletableDeferred<Unit>()
                val cancelProceed = CompletableDeferred<Unit>()

                // Simulate an action executing in the actionJob scope (e.g. StructuredDeviceActionExecutor)
                val actionCoroutine = CoroutineScope(harness.scope.coroutineContext + actionJob).launch {
                    readStarted.complete(Unit)
                    try {
                        cancelProceed.await()
                    } catch (_: CancellationException) {
                    }
                    if (isActive && context.isValid()) {
                        writeExecuted = true
                    }
                }

                readStarted.await()

                // User/host cancels turn via interruptTurn
                harness.runtime.invokeMethod(
                    "interruptTurn",
                    mapOf("sessionId" to "session-cancel-test", "turnId" to "turn-1")
                )

                assertTrue(actionJob.isCancelled)
                assertFalse(context.isValid())

                cancelProceed.complete(Unit)
                actionCoroutine.join()

                assertFalse("Mutating write must never execute after turn cancellation", writeExecuted)
            } finally {
                harness.cleanup()
            }
        }
    }

    @Test
    fun promptResponseCancelsActionJobBeforeFinishTurn() = runBlocking {
        withTimeout(10_000L) {
            val harness = createHarness()
            try {
                val session = harness.mockSession("session-prompt-end-test")
                harness.runtime.sessions()["session-prompt-end-test"] = session

                val lease = AcpSessionMcpLeaseStore.prepare(harness.ownerAgentId, 600L, harness.connection)
                harness.bindings["session-prompt-end-test"] = AgentSessionBinding(
                    conversationId = 600L,
                    threadId = "session-prompt-end-test",
                    cwd = "/workspace",
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                val resolver = harness.runtime.invokeMethod(
                    "createDeviceActionResolver",
                    "session-prompt-end-test",
                    harness.connection,
                ) as (suspend (String) -> StructuredDeviceActionContext)

                AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-prompt-end-test", resolver)

                harness.turnOwnership.reserve("session-prompt-end-test", "turn-1", "req-1")
                val promptJob = harness.newPromptJob()
                val promptExec = AcpPromptExecution(null).apply {
                    attachPromptJob(promptJob)
                    tryStartPrompt()
                }
                harness.runtime.promptExecutions()["session-prompt-end-test"] = promptExec

                val context = AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-end-1")
                assertTrue(context.turnJob.isActive)
                assertTrue(context.isValid())

                var lingeringWriteExecuted = false
                val actionStarted = CompletableDeferred<Unit>()
                val finishGate = CompletableDeferred<Unit>()

                val actionCoroutine = CoroutineScope(harness.scope.coroutineContext + context.turnJob).launch {
                    actionStarted.complete(Unit)
                    try {
                        finishGate.await()
                    } catch (_: CancellationException) {
                    }
                    if (isActive && context.isValid()) {
                        lingeringWriteExecuted = true
                    }
                }

                actionStarted.await()

                // Simulate cancelActionJob triggered by PromptResponseEvent arrival
                promptExec.cancelActionJob(CancellationException("Prompt response received"))

                assertTrue(context.turnJob.isCancelled)
                assertFalse(context.isValid())
                assertTrue(promptJob.isActive)

                finishGate.complete(Unit)
                actionCoroutine.join()

                assertFalse("Lingering action must never write after prompt response has arrived", lingeringWriteExecuted)
            } finally {
                harness.cleanup()
            }
        }
    }

    private fun LocalAcpRuntime.setField(name: String, value: Any?) =
        javaClass.getDeclaredField(name).apply { isAccessible = true }.set(this, value)

    @Suppress("UNCHECKED_CAST")
    private fun LocalAcpRuntime.sessions() = javaClass.getDeclaredField("sessions").run {
        isAccessible = true; get(this@sessions) as ConcurrentHashMap<String, ClientSession>
    }

    @Suppress("UNCHECKED_CAST")
    private fun LocalAcpRuntime.promptExecutions() = javaClass.getDeclaredField("promptExecutions").run {
        isAccessible = true; get(this@promptExecutions) as ConcurrentHashMap<String, AcpPromptExecution>
    }

    private suspend fun LocalAcpRuntime.invokeMethod(name: String, vararg args: Any?): Any? {
        val method = javaClass.declaredMethods.first {
            it.name == name && (it.parameterTypes.size == args.size || it.parameterTypes.size == args.size + 1)
        }
        method.isAccessible = true
        return if (method.parameterTypes.lastOrNull() == kotlin.coroutines.Continuation::class.java) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
                val allArgs = arrayOfNulls<Any>(args.size + 1)
                System.arraycopy(args, 0, allArgs, 0, args.size)
                allArgs[args.size] = cont
                try {
                    method.invoke(this, *allArgs)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.targetException ?: e
                }
            }
        } else {
            try {
                method.invoke(this, *args)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }
}
