package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.AgentPermissionRequester
import cn.com.omnimind.bot.agent.tool.handlers.StructuredDeviceActionContext
import cn.com.omnimind.bot.mcp.McpSessionRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AcpSessionMcpLeaseStoreTest {

    @Before
    fun setUp() {
        AcpSessionMcpLeaseStore.revokeAll()
    }

    @Test
    fun prepareGeneratesValidLeaseWithRedactedToString() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare(
            agentId = "codex",
            conversationId = 123L,
            connectionOwner = connection,
        )

        assertNotNull(lease.leaseId)
        assertTrue(lease.leaseId.isNotBlank())
        assertNotNull(lease.token)
        assertTrue(lease.token.isNotBlank())
        assertEquals("codex", lease.agentId)
        assertEquals(123L, lease.conversationId)

        // Token must be URL-safe 32 bytes (43 or 44 base64 chars without padding)
        val decoded = Base64.getUrlDecoder().decode(lease.token)
        assertEquals(32, decoded.size)

        // toString must NEVER leak token
        val stringRepresentation = lease.toString()
        assertFalse(stringRepresentation.contains(lease.token))
        assertTrue(stringRepresentation.contains("[REDACTED]"))
        assertTrue(stringRepresentation.contains(lease.leaseId))
        assertTrue(stringRepresentation.contains("codex"))
    }

    @Test
    fun resolveValidatesLeaseIdAndTokenConstantTime() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)

        val resolved = AcpSessionMcpLeaseStore.resolve(lease.leaseId, lease.token)
        assertNotNull(resolved)
        assertEquals(lease.leaseId, resolved?.leaseId)
        assertEquals(lease.token, resolved?.token)

        // Wrong token
        assertNull(AcpSessionMcpLeaseStore.resolve(lease.leaseId, "invalid-token"))

        // Wrong leaseId
        assertNull(AcpSessionMcpLeaseStore.resolve("non-existent-lease", lease.token))
    }

    @Test
    fun bindBindsSessionAndAllowsRebindWithSameSession() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)

        var resolverCalled = false
        val dummyContext = createDummyContext("session-1", 1L)

        AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-1") { toolCallId ->
            resolverCalled = true
            dummyContext.copy(toolCallId = toolCallId)
        }

        // Rebinding with same sessionId succeeds
        AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-1") { toolCallId ->
            dummyContext.copy(toolCallId = toolCallId)
        }

        val context = runBlocking {
            AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-1")
        }
        assertEquals("session-1", context.sessionId)
        assertEquals("call-1", context.toolCallId)
    }

    @Test
    fun bindWithDifferentSessionRevokesLeaseAndThrows() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)
        val dummyContext = createDummyContext("session-1", 1L)

        AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-1") { dummyContext }

        try {
            AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-2") { dummyContext }
            fail("Rebinding to a different session must fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message?.contains("Cannot rebind lease") == true)
        }

        // The lease must be revoked immediately
        assertNull(AcpSessionMcpLeaseStore.resolve(lease.leaseId, lease.token))
        try {
            runBlocking { AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-1") }
            fail("Execution context on revoked lease must fail")
        } catch (error: IllegalStateException) {
            assertEquals("session_unavailable", error.message)
        }
    }

    @Test
    fun executionContextFailsBeforeBind() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)

        try {
            runBlocking { AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-1") }
            fail("executionContext before bind must fail")
        } catch (error: IllegalStateException) {
            assertTrue(error.message?.contains("is not bound to a session") == true)
        }
    }

    @Test
    fun executionContextChecksConnectionBeforeAndAfterResolution() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)
        val dummyContext = createDummyContext("session-1", 1L)

        // Test revocation during resolution
        AcpSessionMcpLeaseStore.bind(lease.leaseId, "session-1") { toolCallId ->
            // Revoke connection while resolver is executing
            AcpSessionMcpLeaseStore.revokeConnection(connection)
            dummyContext.copy(toolCallId = toolCallId)
        }

        try {
            runBlocking { AcpSessionMcpLeaseStore.executionContext(lease.leaseId, "call-1") }
            fail("Revoking connection during executionContext resolution must fail")
        } catch (error: IllegalStateException) {
            assertEquals("session_unavailable", error.message)
        }
    }

    @Test
    fun revokeLeaseRevokesOnlyTargetLease() {
        val connection = Any()
        val lease1 = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)
        val lease2 = AcpSessionMcpLeaseStore.prepare("claude", 2L, connection)

        AcpSessionMcpLeaseStore.revokeLease(lease1.leaseId)

        assertNull(AcpSessionMcpLeaseStore.resolve(lease1.leaseId, lease1.token))
        assertNotNull(AcpSessionMcpLeaseStore.resolve(lease2.leaseId, lease2.token))
    }

    @Test
    fun revokeSessionRevokesAllLeasesForThatSession() {
        val connection = Any()
        val lease1 = AcpSessionMcpLeaseStore.prepare("claude", 1L, connection)
        val lease2 = AcpSessionMcpLeaseStore.prepare("claude", 2L, connection)

        AcpSessionMcpLeaseStore.bind(lease1.leaseId, "session-target") { createDummyContext("session-target", 1L) }
        AcpSessionMcpLeaseStore.bind(lease2.leaseId, "session-other") { createDummyContext("session-other", 2L) }

        AcpSessionMcpLeaseStore.revokeSession("session-target")

        assertNull(AcpSessionMcpLeaseStore.resolve(lease1.leaseId, lease1.token))
        assertNotNull(AcpSessionMcpLeaseStore.resolve(lease2.leaseId, lease2.token))
    }

    @Test
    fun revokeConnectionRevokesAllLeasesForThatConnection() {
        val conn1 = Any()
        val conn2 = Any()
        val lease1 = AcpSessionMcpLeaseStore.prepare("claude", 1L, conn1)
        val lease2 = AcpSessionMcpLeaseStore.prepare("claude", 2L, conn2)

        AcpSessionMcpLeaseStore.revokeConnection(conn1)

        assertNull(AcpSessionMcpLeaseStore.resolve(lease1.leaseId, lease1.token))
        assertNotNull(AcpSessionMcpLeaseStore.resolve(lease2.leaseId, lease2.token))
    }

    @Test
    fun mcpSessionMappingIsIsolatedAndCleanedUpOnRevocation() {
        val connection = Any()
        val lease = AcpSessionMcpLeaseStore.prepare("codex", 1L, connection)

        // Register new MCP session
        assertTrue(AcpSessionMcpLeaseStore.registerMcpSession("mcp-session-uuid-1", lease.leaseId))
        assertEquals(lease.leaseId, AcpSessionMcpLeaseStore.leaseIdForMcpSession("mcp-session-uuid-1"))
        assertEquals(lease.leaseId, McpSessionRegistry.getTarget("mcp-session-uuid-1"))

        // Registering same MCP session with same lease succeeds idempotently
        assertTrue(AcpSessionMcpLeaseStore.registerMcpSession("mcp-session-uuid-1", lease.leaseId))

        // Registering same MCP session with different lease fails
        val leaseOther = AcpSessionMcpLeaseStore.prepare("codex", 2L, connection)
        assertFalse(AcpSessionMcpLeaseStore.registerMcpSession("mcp-session-uuid-1", leaseOther.leaseId))

        // Unregister
        AcpSessionMcpLeaseStore.unregisterMcpSession("mcp-session-uuid-1")
        assertNull(AcpSessionMcpLeaseStore.leaseIdForMcpSession("mcp-session-uuid-1"))
        assertNull(McpSessionRegistry.getTarget("mcp-session-uuid-1"))

        // Re-register and test auto-cleanup when lease is revoked
        assertTrue(AcpSessionMcpLeaseStore.registerMcpSession("mcp-session-uuid-2", lease.leaseId))
        assertEquals(lease.leaseId, McpSessionRegistry.getTarget("mcp-session-uuid-2"))
        AcpSessionMcpLeaseStore.revokeLease(lease.leaseId)
        assertNull(AcpSessionMcpLeaseStore.leaseIdForMcpSession("mcp-session-uuid-2"))
        assertNull(McpSessionRegistry.getTarget("mcp-session-uuid-2"))
    }

    private fun createDummyContext(sessionId: String, conversationId: Long): StructuredDeviceActionContext {
        return StructuredDeviceActionContext(
            conversationId = conversationId,
            sessionId = sessionId,
            turnId = "turn-1",
            ownerId = "test-agent",
            toolCallId = "call-1",
            permissionRequester = AgentPermissionRequester { _, _, _ -> true },
            isValid = { true },
            turnJob = Job(),
        )
    }
}
