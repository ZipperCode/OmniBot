package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.bot.agent.tool.handlers.StructuredDeviceActionContext
import cn.com.omnimind.bot.mcp.McpSessionRegistry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Ephemeral in-memory MCP session lease scoped to an ACP session and connection.
 *
 * Token is strictly redacted in toString to prevent accidental credential leakage in logs.
 */
internal data class AcpSessionMcpLease(
    val leaseId: String,
    val token: String,
    val agentId: String,
    val conversationId: Long?,
) {
    override fun toString(): String =
        "AcpSessionMcpLease(leaseId=$leaseId, agentId=$agentId, conversationId=$conversationId, token=[REDACTED])"
}

internal object AcpSessionMcpLeaseStore {
    private val lock = Any()
    private val secureRandom = SecureRandom()

    private class LeaseEntry(
        val lease: AcpSessionMcpLease,
        val connectionOwner: Any,
        var sessionId: String? = null,
        var resolver: (suspend (String) -> StructuredDeviceActionContext)? = null,
        var revoked: Boolean = false,
    )

    private val leases = mutableMapOf<String, LeaseEntry>()

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun prepare(agentId: String, conversationId: Long?, connectionOwner: Any): AcpSessionMcpLease {
        val leaseId = UUID.randomUUID().toString()
        val token = generateSecureToken()
        val lease = AcpSessionMcpLease(
            leaseId = leaseId,
            token = token,
            agentId = agentId,
            conversationId = conversationId,
        )
        val entry = LeaseEntry(
            lease = lease,
            connectionOwner = connectionOwner,
        )
        synchronized(lock) {
            leases[leaseId] = entry
        }
        return lease
    }

    fun bind(
        leaseId: String,
        sessionId: String,
        resolveContext: suspend (String) -> StructuredDeviceActionContext,
    ) {
        synchronized(lock) {
            val entry = leases[leaseId]
            if (entry == null || entry.revoked) {
                throw IllegalStateException("Lease $leaseId is not valid or has been revoked.")
            }
            if (entry.sessionId != null && entry.sessionId != sessionId) {
                entry.revoked = true
                leases.remove(leaseId)
                cleanMcpSessionsForLeaseLocked(leaseId)
                throw IllegalStateException(
                    "Cannot rebind lease $leaseId from session ${entry.sessionId} to $sessionId"
                )
            }
            entry.sessionId = sessionId
            entry.resolver = resolveContext
        }
    }

    fun resolve(leaseId: String, token: String): AcpSessionMcpLease? {
        synchronized(lock) {
            val entry = leases[leaseId] ?: return null
            if (entry.revoked) return null
            val expectedBytes = entry.lease.token.toByteArray(StandardCharsets.UTF_8)
            val actualBytes = token.toByteArray(StandardCharsets.UTF_8)
            if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
                return null
            }
            return entry.lease
        }
    }

    suspend fun executionContext(leaseId: String, toolCallId: String): StructuredDeviceActionContext {
        val (resolver, expectedConnection, expectedSessionId) = synchronized(lock) {
            val entry = leases[leaseId]
                ?: throw IllegalStateException("session_unavailable")
            if (entry.revoked) {
                throw IllegalStateException("session_unavailable")
            }
            val boundSessionId = entry.sessionId
                ?: throw IllegalStateException("Lease $leaseId is not bound to a session.")
            val boundResolver = entry.resolver
                ?: throw IllegalStateException("Lease $leaseId is not bound to a resolver.")
            Triple(boundResolver, entry.connectionOwner, boundSessionId)
        }

        val context = resolver(toolCallId)

        synchronized(lock) {
            val entry = leases[leaseId]
                ?: throw IllegalStateException("session_unavailable")
            if (entry.revoked || entry.connectionOwner !== expectedConnection || entry.sessionId != expectedSessionId) {
                throw IllegalStateException("session_unavailable")
            }
        }
        return context
    }

    fun revokeLease(leaseId: String) {
        synchronized(lock) {
            val entry = leases.remove(leaseId)
            entry?.revoked = true
            cleanMcpSessionsForLeaseLocked(leaseId)
        }
    }

    fun revokeSession(sessionId: String) {
        synchronized(lock) {
            val iterator = leases.entries.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (next.value.sessionId == sessionId) {
                    next.value.revoked = true
                    cleanMcpSessionsForLeaseLocked(next.key)
                    iterator.remove()
                }
            }
        }
    }

    fun revokeConnection(connectionOwner: Any) {
        synchronized(lock) {
            val iterator = leases.entries.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (next.value.connectionOwner === connectionOwner) {
                    next.value.revoked = true
                    cleanMcpSessionsForLeaseLocked(next.key)
                    iterator.remove()
                }
            }
        }
    }

    fun registerMcpSession(mcpSessionId: String, leaseId: String): Boolean {
        synchronized(lock) {
            val entry = leases[leaseId]
            if (entry == null || entry.revoked) return false
        }
        return McpSessionRegistry.register(mcpSessionId, leaseId)
    }

    fun leaseIdForMcpSession(mcpSessionId: String): String? {
        val target = McpSessionRegistry.getTarget(mcpSessionId) ?: return null
        if (target == McpSessionRegistry.GLOBAL_TARGET) return null
        synchronized(lock) {
            val entry = leases[target]
            if (entry == null || entry.revoked) {
                McpSessionRegistry.unregister(mcpSessionId)
                return null
            }
        }
        return target
    }

    fun unregisterMcpSession(mcpSessionId: String) {
        McpSessionRegistry.unregister(mcpSessionId)
    }

    fun revokeAll() {
        synchronized(lock) {
            leases.values.forEach { it.revoked = true }
            leases.clear()
        }
        McpSessionRegistry.clear()
    }

    private fun cleanMcpSessionsForLeaseLocked(leaseId: String) {
        McpSessionRegistry.revokeLease(leaseId)
    }
}
