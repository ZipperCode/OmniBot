package cn.com.omnimind.bot.mcp

import java.util.concurrent.ConcurrentHashMap

/**
 * Caller identity for MCP requests across the local network boundary.
 *
 * Differentiates universal global endpoints from scoped ACP session leases.
 * Sensitive tokens are memory-only and strictly redacted in string projections.
 */
internal sealed interface McpDeviceCaller {
    data object Global : McpDeviceCaller {
        override fun toString(): String = "McpDeviceCaller.Global"
    }

    class Acp(
        val leaseId: String,
        val token: String,
    ) : McpDeviceCaller {
        override fun toString(): String =
            "McpDeviceCaller.Acp(leaseId=$leaseId, token=[REDACTED])"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Acp) return false
            return leaseId == other.leaseId && token == other.token
        }

        override fun hashCode(): Int = 31 * leaseId.hashCode() + token.hashCode()
    }
}

/**
 * In-memory registry tracking active MCP-Session-Id associations to prevent session hijacking,
 * cross-lease confusion, and improper access between global and lease-scoped endpoints.
 *
 * Single source of truth: maintains a single map of sessionId -> target (GLOBAL_TARGET or leaseId).
 */
object McpSessionRegistry {
    const val GLOBAL_TARGET: String = "__global__"
    private val sessions = ConcurrentHashMap<String, String>()

    /**
     * Atomically register a session ID to a target.
     * Returns true if newly registered or already registered to the exact same target.
     * If a collision is detected (already registered to a different target), returns false without overwriting.
     */
    fun register(sessionId: String, target: String): Boolean {
        val existing = sessions.putIfAbsent(sessionId, target)
        return existing == null || existing == target
    }

    fun isRegisteredForTarget(sessionId: String, target: String): Boolean {
        return sessions[sessionId] == target
    }

    fun getTarget(sessionId: String): String? = sessions[sessionId]

    fun unregister(sessionId: String): Boolean {
        return sessions.remove(sessionId) != null
    }

    fun revokeLease(leaseId: String) {
        sessions.entries.removeIf { it.value == leaseId }
    }

    fun clear() {
        sessions.clear()
    }
}
