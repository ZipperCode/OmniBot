package cn.com.omnimind.bot.agent.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class RemoteAcpTurnLifecycleConcurrencyTest {

    private val managers = mutableListOf<AgentRuntimeManager>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val connections = mutableListOf<ControlledConnection>()

    @Before
    fun setUp() {
        TaskRuntime.resetForTesting()
    }

    @After
    fun tearDown() {
        TaskRuntime.resetForTesting()
        scopes.forEach { it.cancel() }
        managers.forEach { manager ->
            val scopeField = manager.javaClass.getDeclaredField("scope").apply { isAccessible = true }
            (scopeField.get(manager) as? CoroutineScope)?.cancel()
        }
        connections.forEach { connection ->
            runBlocking { connection.close() }
        }
        connections.clear()
        managers.clear()
        scopes.clear()
    }

    private fun createFixture(): Pair<Context, AgentRuntimeManager> {
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
        CodexRemoteBridgeConfigStore(context).write(
            CodexRemoteBridgeConfig(
                enabled = true,
                bridgeUrl = "http://127.0.0.1:1",
                cwd = "/remote",
            )
        )
        val constructor = AgentRuntimeManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        val manager = constructor.newInstance(context)
        managers.add(manager)
        return context to manager
    }

    private fun attachMockRemoteSession(
        manager: AgentRuntimeManager,
        connection: ControlledConnection,
        scope: CoroutineScope
    ): RemoteCodexAppServerSession {
        connections.add(connection)
        val transport = RemoteCodexAppServerSession(scope, {}, { connection })
        val sessionField = AgentRuntimeManager::class.java.getDeclaredField("session").apply {
            isAccessible = true
        }
        sessionField.set(manager, transport)
        return transport
    }

    @Test
    fun `remote turn admits into TaskRuntime with conversationId sessionId and turnId metadata`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val conversationId = 4242L
                val sessionId = "session-remote-admission"
                val gate = CompletableDeferred<Unit>()
                connection.promptGate = gate

                val promptDeferred = async(Dispatchers.Default) {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to sessionId,
                            "conversationId" to conversationId,
                            "text" to "hello remote",
                        )
                    ) as Map<*, *>
                }

                withTimeout(5000) {
                    while (TaskRuntime.getActiveTasks().none { it.sessionId == sessionId }) {
                        delay(10)
                    }
                }

                val activeTask = TaskRuntime.getActiveTasks().first { it.sessionId == sessionId }
                assertEquals(sessionId, activeTask.sessionId)
                assertEquals(conversationId, activeTask.conversationId)
                assertNotNull(activeTask.turnId)
                assertTrue(activeTask.turnId!!.isNotBlank())
                assertEquals("Codex 远程任务", activeTask.taskTitle)

                gate.complete(Unit)
                val result = promptDeferred.await()
                assertEquals(true, result["completed"])
                assertEquals(activeTask.turnId, result["turnId"])

                assertNull(TaskRuntime.getActiveTask(activeTask.taskId))
            }
        }
    }

    @Test
    fun `late cancel with expectedTurnId for finished turn is rejected as stale and does not cancel new turn`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val conversationId = 5001L
                val sessionId = "session-stale-cancel"

                // Turn 1 runs to completion
                val turn1Result = manager.handleMethod(
                    "session/prompt",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "conversationId" to conversationId,
                        "text" to "turn 1 prompt",
                    )
                ) as Map<*, *>
                assertEquals(true, turn1Result["completed"])
                val turn1Id = turn1Result["turnId"] as String
                assertNotNull(turn1Id)

                // Turn 2 starts and suspends on gate
                val gateTurn2 = CompletableDeferred<Unit>()
                connection.promptGate = gateTurn2

                val turn2Deferred = async(Dispatchers.Default) {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to sessionId,
                            "conversationId" to conversationId,
                            "text" to "turn 2 prompt",
                        )
                    ) as Map<*, *>
                }

                withTimeout(5000) {
                    while (TaskRuntime.getActiveTasks().none { it.sessionId == sessionId }) {
                        delay(10)
                    }
                }

                val turn2Task = TaskRuntime.getActiveTasks().first { it.sessionId == sessionId }
                val turn2Id = turn2Task.turnId
                assertNotNull(turn2Id)

                val writesBeforeStaleCancel = connection.writes.size

                // Late stop arriving with Turn 1's expectedTurnId
                val staleCancelResult = manager.handleMethod(
                    "session/cancel",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "expectedTurnId" to turn1Id,
                    )
                ) as Map<*, *>

                assertEquals(false, staleCancelResult["cancelled"])
                assertEquals(true, staleCancelResult["stale"])
                assertEquals(turn2Id, staleCancelResult["turnId"])
                assertEquals(turn1Id, staleCancelResult["expectedTurnId"])

                // Wire did NOT send session/cancel for the stale request
                val cancelWritesAfterStale = connection.writes.drop(writesBeforeStaleCancel)
                    .filter { it["method"]?.asString == "session/cancel" }
                assertTrue(cancelWritesAfterStale.isEmpty())

                // Turn 2 is STILL active in TaskRuntime
                assertNotNull(TaskRuntime.getActiveTask(turn2Task.taskId))

                // Now cancel Turn 2 with matching expectedTurnId
                val turn2CancelResult = manager.handleMethod(
                    "session/cancel",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "expectedTurnId" to turn2Id,
                    )
                ) as Map<*, *>

                assertEquals(true, turn2CancelResult["cancelled"])
                assertEquals(turn2Id, turn2CancelResult["turnId"])

                // Wire notification sent for Turn 2 cancellation
                val cancelNotification = connection.writes.lastOrNull { it["method"]?.asString == "session/cancel" }
                assertNotNull(cancelNotification)
                val params = cancelNotification!!.getAsJsonObject("params")
                assertNotNull(params)
                assertEquals(sessionId, params["sessionId"]?.asString)
                // Wire must NOT carry expectedTurnId
                assertNull(params["expectedTurnId"])

                gateTurn2.complete(Unit)
                turn2Deferred.await()
            }
        }
    }

    @Test
    fun `session cancel without expectedTurnId preserves ordinary session cancel semantics`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val conversationId = 6001L
                val sessionId = "session-ordinary-cancel"

                val gate = CompletableDeferred<Unit>()
                connection.promptGate = gate

                val promptDeferred = async(Dispatchers.Default) {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to sessionId,
                            "conversationId" to conversationId,
                            "text" to "active prompt",
                        )
                    ) as Map<*, *>
                }

                withTimeout(5000) {
                    while (TaskRuntime.getActiveTasks().none { it.sessionId == sessionId } ||
                        connection.writes.none { it["method"]?.asString == "session/prompt" }) {
                        delay(10)
                    }
                }

                val activeTask = TaskRuntime.getActiveTasks().first { it.sessionId == sessionId }

                val cancelResult = manager.handleMethod(
                    "session/cancel",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                    )
                ) as Map<*, *>

                assertEquals(true, cancelResult["cancelled"])
                assertEquals(activeTask.turnId, cancelResult["turnId"])

                val cancelWrite = connection.writes.lastOrNull { it["method"]?.asString == "session/cancel" }
                assertNotNull(cancelWrite)
                assertEquals(sessionId, cancelWrite!!.getAsJsonObject("params")["sessionId"]?.asString)

                gate.complete(Unit)
                promptDeferred.await()
            }
        }
    }

    @Test
    fun `late cancel when session is idle reports stale without wire notification`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val sessionId = "session-idle-cancel"
                val cancelResult = manager.handleMethod(
                    "session/cancel",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "expectedTurnId" to "past-turn-id",
                    )
                ) as Map<*, *>

                assertEquals(false, cancelResult["cancelled"])
                assertEquals(true, cancelResult["stale"])
                assertNull(cancelResult["turnId"])
                assertEquals("past-turn-id", cancelResult["expectedTurnId"])

                val wireCancels = connection.writes.filter { it["method"]?.asString == "session/cancel" }
                assertTrue(wireCancels.isEmpty())
            }
        }
    }
    @Test
    fun `prompt without Flutter awaiters still projects official response event to end UI and releases lease`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val emittedEvents = mutableListOf<Map<String, Any?>>()
                manager.setEventListener { event ->
                    synchronized(emittedEvents) { emittedEvents += event }
                }

                val conversationId = 7701L
                val sessionId = "session-no-awaiter"
                val requestId = "req-no-awaiter"

                val promptResult = manager.handleMethod(
                    "session/prompt",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "conversationId" to conversationId,
                        "conversationMode" to "agent",
                        "requestId" to requestId,
                        "text" to "run background task",
                    )
                ) as Map<*, *>

                assertEquals(true, promptResult["completed"])

                ShadowLooper.idleMainLooper()
                val promptEvent = synchronized(emittedEvents) {
                    emittedEvents.firstOrNull { it["method"] == "session/prompt" }
                }
                assertNotNull(promptEvent)
                assertEquals("session/prompt", promptEvent!!["method"])
                assertEquals(requestId, promptEvent["id"])
                assertEquals(sessionId, promptEvent["sessionId"])
                assertEquals(conversationId, promptEvent["conversationId"])
                assertEquals("agent", promptEvent["conversationMode"])
                assertEquals(REMOTE_CODEX_AGENT_ID, promptEvent["agentId"])
                assertEquals(true, promptEvent["allowImplicitTurnAdmission"])

                val message = promptEvent["message"] as? Map<*, *>
                assertNotNull(message)
                assertEquals("2.0", message!!["jsonrpc"])
                assertEquals(requestId, message["id"])
                val result = message["result"] as? Map<*, *>
                assertNotNull(result)
                assertEquals("completed", result!!["status"])
                assertEquals(true, result["completed"])

                val activeTurnId = promptResult["turnId"]?.toString()
                assertNotNull(activeTurnId)
                val runtimeTaskId = "agent_turn_${sessionId}_$activeTurnId"
                assertNull(TaskRuntime.getActiveTask(runtimeTaskId))
            }
        }
    }

    @Test
    fun `duplicate inflight prompt request does not emit fake terminal event`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val emittedEvents = mutableListOf<Map<String, Any?>>()
                manager.setEventListener { event ->
                    synchronized(emittedEvents) { emittedEvents += event }
                }

                val gate = CompletableDeferred<Unit>()
                connection.promptGate = gate

                val sessionId = "session-dup"
                val requestId = "req-dup-inflight"

                val prompt1 = async(Dispatchers.Default) {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to sessionId,
                            "conversationId" to 301L,
                            "requestId" to requestId,
                            "text" to "first call",
                        )
                    ) as Map<*, *>
                }

                withTimeout(3000) {
                    while (connection.writes.none { it["method"]?.asString == "session/prompt" }) {
                        delay(20)
                    }
                }

                val dupResult = manager.handleMethod(
                    "session/prompt",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "conversationId" to 301L,
                        "requestId" to requestId,
                        "text" to "duplicate call",
                    )
                ) as Map<*, *>

                assertEquals(true, dupResult["deduplicated"])
                assertEquals(false, dupResult["completed"])

                ShadowLooper.idleMainLooper()
                val eventsDuringInflight = synchronized(emittedEvents) {
                    emittedEvents.filter { it["method"] == "session/prompt" }
                }
                assertTrue(eventsDuringInflight.isEmpty())

                gate.complete(Unit)
                val realResult = prompt1.await()
                assertEquals(true, realResult["completed"])

                ShadowLooper.idleMainLooper()
                val finalEvents = synchronized(emittedEvents) {
                    emittedEvents.filter { it["method"] == "session/prompt" }
                }
                assertEquals(1, finalEvents.size)
                assertEquals(requestId, finalEvents[0]["id"])
                val message = finalEvents[0]["message"] as Map<*, *>
                val result = message["result"] as Map<*, *>
                assertEquals(true, result["completed"])
            }
        }
    }

    @Test
    fun `prompt preflight error without admission does not emit terminal response event`() {
        runBlocking {
            withTimeout(15_000L) {
                val (context, manager) = createFixture()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(scopes::add)
                val connection = ControlledConnection()
                val transport = attachMockRemoteSession(manager, connection, scope)
                transport.start("test-client")

                val emittedEvents = mutableListOf<Map<String, Any?>>()
                manager.setEventListener { event ->
                    synchronized(emittedEvents) { emittedEvents += event }
                }

                val gate = CompletableDeferred<Unit>()
                connection.promptGate = gate

                val prompt1 = async(Dispatchers.Default) {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to "session-active",
                            "conversationId" to 101L,
                            "requestId" to "req-1",
                            "text" to "first running turn",
                        )
                    ) as Map<*, *>
                }

                withTimeout(3000) {
                    while (TaskRuntime.getActiveTasks().none { it.sessionId == "session-active" }) {
                        delay(20)
                    }
                }

                val failedReqId = "req-failed-admission"
                val error = runCatching {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to "session-active",
                            "conversationId" to 101L,
                            "requestId" to failedReqId,
                            "text" to "second conflicting turn",
                        )
                    )
                }.exceptionOrNull()
                assertNotNull(error)

                ShadowLooper.idleMainLooper()
                val errorEvent = synchronized(emittedEvents) {
                    emittedEvents.firstOrNull { it["id"] == failedReqId }
                }
                // 尚未准入无 turn，不发终态响应事件，避免借用或终结当前 active turn
                assertNull(errorEvent)

                gate.complete(Unit)
                prompt1.await()
            }
        }
    }

    private class ControlledConnection : RemoteCodexAppServerConnection {
        override val isRunning = true
        val writes = CopyOnWriteArrayList<JsonObject>()
        private lateinit var stdout: suspend (String) -> Unit
        var promptGate: CompletableDeferred<Unit>? = null
        private val launchedJobs = CopyOnWriteArrayList<Job>()

        override suspend fun start(
            onStdoutLine: suspend (String) -> Unit,
            onStderrLine: suspend (String) -> Unit,
            onExit: suspend (Int?) -> Unit,
        ) {
            stdout = onStdoutLine
        }

        override suspend fun writeLine(line: String) {
            val request = JsonParser.parseString(line).asJsonObject
            writes += request
            val id = request["id"]
            if (id != null) {
                val method = request["method"]?.asString
                if (method == "session/prompt") {
                    val gate = promptGate
                    if (gate != null) {
                        val job = CoroutineScope(Dispatchers.Default).launch {
                            gate.await()
                            stdout("""{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"completed"}}""")
                        }
                        launchedJobs += job
                     } else {
                        stdout("""{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"completed"}}""")
                    }
                } else {
                    stdout("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                }
            }
        }

        override suspend fun close() {
            launchedJobs.forEach { it.cancel() }
            launchedJobs.clear()
        }
    }
}
