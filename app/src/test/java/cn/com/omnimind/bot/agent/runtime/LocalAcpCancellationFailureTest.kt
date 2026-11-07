package cn.com.omnimind.bot.agent.runtime

import com.rk.terminal.runtime.TerminalDistribution
import cn.com.omnimind.bot.agent.AgentScheduleToolBridge
import com.agentclientprotocol.agent.AgentInfo
import com.agentclientprotocol.client.ClientSession
import com.agentclientprotocol.model.AgentCapabilities
import com.agentclientprotocol.model.SessionId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.util.concurrent.ConcurrentHashMap

class LocalAcpCancellationFailureTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedCancelKeepsTheActivePrompt() = checkFailure("session/cancel")
    @Test fun failedCancelDoesNotPretendCloseSucceeded() = checkFailure("session/close")

    private fun checkFailure(method: String) = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val runtime = LocalAcpRuntime(context, scope, mock(AgentSessionBindingRepository::class.java),
            AcpAgentProfileStore(context), ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Listing must not emit lifecycle events") }, turnOwnership = ownership)
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(xiaowanAgentCapabilities())
        runtime.javaClass.getDeclaredField("agentInfo").apply { isAccessible = true }.set(runtime, info)
        @Suppress("UNCHECKED_CAST")
        val sessions = runtime.javaClass.getDeclaredField("sessions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, ClientSession>
        }
        val session = mock(ClientSession::class.java)
        `when`(session.sessionId).thenReturn(SessionId("session-one"))
        sessions["session-one"] = session
        val promptJob = Job()
        val execution = AcpPromptExecution(null).apply {
            attachPromptJob(promptJob)
            check(tryStartPrompt())
        }
        @Suppress("UNCHECKED_CAST")
        val executions = runtime.javaClass.getDeclaredField("promptExecutions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, AcpPromptExecution>
        }
        executions["session-one"] = execution
        ownership.reserve("session-one", "turn-one", "request-one")
        val failure = java.io.IOException("Cancel transport failed")
        `when`(session.cancel()).thenAnswer { throw failure }.thenAnswer { Unit }
        try {
            try {
                runtime.handleMethod(method, mapOf("sessionId" to "session-one"))
                fail("Cancel transport failure must reach caller")
            } catch (error: java.io.IOException) { assertEquals(failure.message, error.message) }
            assertSame(session, sessions["session-one"])
            assertEquals("turn-one", ownership.activeTurnId("session-one"))
            assertFalse(promptJob.isCancelled)
            verify(session, never()).close()
            // A new explicit cancellation request can be sent. Acceptance
            // still does not replace the original prompt's terminal response.
            runtime.handleMethod("session/cancel", mapOf("sessionId" to "session-one"))
            assertEquals("turn-one", ownership.activeTurnId("session-one"))
            assertFalse(promptJob.isCancelled)
            verify(session, times(2)).cancel()
            promptJob.cancel()
            Unit
        } finally { scope.cancel() }
    }

    @Test
    fun `stale expectedTurnId does not cancel active turn nor evict grants`() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val runtime = LocalAcpRuntime(context, scope, mock(AgentSessionBindingRepository::class.java),
            AcpAgentProfileStore(context), ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Listing must not emit lifecycle events") }, turnOwnership = ownership)
        val info = mock(AgentInfo::class.java)
        `when`(info.capabilities).thenReturn(xiaowanAgentCapabilities())
        runtime.javaClass.getDeclaredField("agentInfo").apply { isAccessible = true }.set(runtime, info)
        @Suppress("UNCHECKED_CAST")
        val sessions = runtime.javaClass.getDeclaredField("sessions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, ClientSession>
        }
        val session = mock(ClientSession::class.java)
        `when`(session.sessionId).thenReturn(SessionId("session-two"))
        sessions["session-two"] = session
        val promptJob = Job()
        val execution = AcpPromptExecution(null).apply {
            attachPromptJob(promptJob)
            check(tryStartPrompt())
        }
        @Suppress("UNCHECKED_CAST")
        val executions = runtime.javaClass.getDeclaredField("promptExecutions").run {
            isAccessible = true; get(runtime) as ConcurrentHashMap<String, AcpPromptExecution>
        }
        executions["session-two"] = execution
        ownership.reserve("session-two", "turn-active", "request-two")
        val grantScope = TurnPermissionScope(action = "file_control.read", path = "/data/local/tmp/test")
        AcpTurnGrantRegistry.recordGrant("session-two", "turn-active", grantScope)

        try {
            val result = runtime.handleMethod(
                "session/cancel",
                mapOf("sessionId" to "session-two", "expectedTurnId" to "stale-turn-id")
            ) as Map<*, *>

            assertEquals(true, result["ok"])
            assertEquals(true, result["stale"])
            assertEquals("turn-active", result["turnId"])
            assertEquals("stale-turn-id", result["expectedTurnId"])

            assertEquals("turn-active", ownership.activeTurnId("session-two"))
            assertFalse(promptJob.isCancelled)
            verify(session, never()).cancel()

            assertTrue(AcpTurnGrantRegistry.isGranted("session-two", "turn-active", grantScope))
        } finally {
            AcpTurnGrantRegistry.evict("session-two")
            scope.cancel()
        }
    }

    @Test
    fun `disconnect releases in-flight turn without recording fake official terminal`() = runBlocking {
        val context = acpProfileStoreTestContext(folder.root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ownership = AcpTurnOwnershipRegistry()
        val runtime = LocalAcpRuntime(context, scope, mock(AgentSessionBindingRepository::class.java),
            AcpAgentProfileStore(context), ownerAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID,
            currentDistribution = { TerminalDistribution.alpine },
            prepareLaunchEnvironment = { _, _ -> PreparedAcpLaunch(emptyMap()) },
            buildHandoffContext = { _, _ -> null },
            scheduleToolBridge = mock(AgentScheduleToolBridge::class.java),
            onMessage = { error("Listing must not emit lifecycle events") }, turnOwnership = ownership)

        ownership.reserve("session-disco", "turn-disco", "req-disco")
        cn.com.omnimind.bot.task.runtime.AcpTaskTerminalJournal.recordAdmission(
            context,
            runtime.activeAgentId(),
            "session-disco",
            "turn-disco",
            "req-disco"
        )
        val beforeDisco = cn.com.omnimind.bot.task.runtime.AcpTaskTerminalJournal.find(
            context,
            runtime.activeAgentId(),
            "session-disco",
            "turn-disco"
        )
        assertTrue(beforeDisco is cn.com.omnimind.bot.task.runtime.AcpTerminalLookupResult.AdmittedInFlight)

        try {
            runtime.disconnect()

            assertNull(ownership.activeTurnId("session-disco"))

            val afterDisco = cn.com.omnimind.bot.task.runtime.AcpTaskTerminalJournal.find(
                context,
                runtime.activeAgentId(),
                "session-disco",
                "turn-disco"
            )
            assertTrue(afterDisco is cn.com.omnimind.bot.task.runtime.AcpTerminalLookupResult.AdmittedInFlight)
        } finally {
            scope.cancel()
        }
    }
}
