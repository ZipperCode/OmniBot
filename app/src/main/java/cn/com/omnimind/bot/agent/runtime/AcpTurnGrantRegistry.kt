package cn.com.omnimind.bot.agent.runtime

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class TurnPermissionScope(
    val action: String? = null,
    val packageName: String? = null,
    val path: String? = null,
    val moduleId: String? = null,
    val command: String? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val isShell: Boolean = false,
) {
    fun isProvableScope(): Boolean =
        !packageName.isNullOrBlank() || !path.isNullOrBlank() || !moduleId.isNullOrBlank() || !command.isNullOrBlank()

    fun toJsonObject(): JsonObject = buildJsonObject {
        action?.let { put("action", JsonPrimitive(it)) }
        packageName?.let { put("packageName", JsonPrimitive(it)) }
        path?.let { put("path", JsonPrimitive(it)) }
        moduleId?.let { put("moduleId", JsonPrimitive(it)) }
        command?.let { put("command", JsonPrimitive(it)) }
        workingDirectory?.let { put("workingDirectory", JsonPrimitive(it)) }
        if (environment.isNotEmpty()) {
            put("environment", buildJsonObject {
                environment.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        }
        if (isShell) {
            put("isShell", JsonPrimitive(true))
        }
    }
}

/**
 * An indivisible granted scope entry. Represents an explicit authorization
 * granted for a specific action, its bound resources, and exact execution context.
 * Never decomposes into independent sets (prevents Cartesian product privilege escalation).
 */
data class AuthorizedScopeEntry(
    val action: String,
    val packageName: String? = null,
    val canonicalPath: String? = null,
    val moduleId: String? = null,
    val command: String? = null,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val isShell: Boolean = false,
    val allowSystemModifications: Boolean = false,
) {
    fun matches(incoming: TurnPermissionScope): Boolean {
        // 1. Action MUST match
        val incomingAction = incoming.action?.trim()?.lowercase() ?: return false
        if (action != incomingAction) {
            return false
        }

        // 2. Package matching: if this grant bounded a package, incoming must match it
        if (!packageName.isNullOrBlank()) {
            if (incoming.packageName?.trim() != packageName) return false
        } else if (!incoming.packageName.isNullOrBlank()) {
            // Entry without package cannot authorize a specific incoming package
            return false
        }

        // 3. Path matching: canonical path boundary check
        if (!canonicalPath.isNullOrBlank()) {
            val incomingPathNorm = TurnPermissionGrant.normalizeCanonicalPath(incoming.path) ?: return false
            if (!isPathBounded(incomingPathNorm, canonicalPath)) return false
        } else if (!incoming.path.isNullOrBlank()) {
            return false
        }

        // 4. Root module matching
        if (!moduleId.isNullOrBlank()) {
            if (incoming.moduleId?.trim() != moduleId) return false
        } else if (!incoming.moduleId.isNullOrBlank()) {
            return false
        }

        // 5. Shell command matching: exact command, cwd, and environment must match
        if (isShell || incoming.isShell) {
            if (!isShell || !incoming.isShell) return false
            if (command?.trim() != incoming.command?.trim()) return false
            if (workingDirectory?.trim() != incoming.workingDirectory?.trim()) return false
            if (environment != incoming.environment) return false
        }

        return true
    }

    private fun isPathBounded(target: String, allowed: String): Boolean {
        if (target == allowed) return true
        val prefix = if (allowed.endsWith("/")) allowed else "$allowed/"
        return target.startsWith(prefix)
    }
}

data class TurnPermissionGrant(
    val sessionId: String,
    val turnId: String,
    val entries: Set<AuthorizedScopeEntry> = emptySet(),
) {
    fun covers(scope: TurnPermissionScope): Boolean =
        entries.any { it.matches(scope) }

    fun allowsSystemModifications(scope: TurnPermissionScope? = null): Boolean {
        if (scope == null) {
            return entries.any { it.allowSystemModifications }
        }
        return entries.any { it.matches(scope) && it.allowSystemModifications }
    }

    companion object {
        fun normalizeCanonicalPath(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.contains("\u0000")) return null
            return try {
                File(trimmed).canonicalPath.replace('\\', '/')
            } catch (_: Exception) {
                null
            }
        }
    }
}

object AcpTurnGrantRegistry {
    private val activeGrants = ConcurrentHashMap<String, TurnPermissionGrant>()

    fun recordGrant(
        sessionId: String,
        turnId: String,
        scope: TurnPermissionScope?,
        allowSystemModifications: Boolean = false,
    ) {
        if (sessionId.isBlank() || turnId.isBlank() || scope == null) return
        val action = scope.action?.trim()?.lowercase() ?: return
        val canonical = TurnPermissionGrant.normalizeCanonicalPath(scope.path)

        val newEntry = AuthorizedScopeEntry(
            action = action,
            packageName = scope.packageName?.trim(),
            canonicalPath = canonical,
            moduleId = scope.moduleId?.trim(),
            command = scope.command?.trim(),
            workingDirectory = scope.workingDirectory?.trim(),
            environment = scope.environment,
            isShell = scope.isShell,
            allowSystemModifications = allowSystemModifications,
        )

        activeGrants.compute(sessionId) { _, existing ->
            if (existing != null && existing.turnId == turnId) {
                existing.copy(entries = existing.entries + newEntry)
            } else {
                TurnPermissionGrant(sessionId = sessionId, turnId = turnId, entries = setOf(newEntry))
            }
        }
    }

    fun isGranted(sessionId: String?, turnId: String?, scope: TurnPermissionScope): Boolean {
        if (sessionId.isNullOrBlank() || turnId.isNullOrBlank()) return false
        val grant = activeGrants[sessionId] ?: return false
        if (grant.turnId != turnId) return false
        return grant.covers(scope)
    }

    fun allowsSystemModifications(sessionId: String?, turnId: String? = null, scope: TurnPermissionScope? = null): Boolean {
        if (sessionId.isNullOrBlank()) return false
        val grant = activeGrants[sessionId] ?: return false
        if (turnId != null && grant.turnId != turnId) return false
        return grant.allowsSystemModifications(scope)
    }

    fun activeTurnId(sessionId: String): String? = activeGrants[sessionId]?.turnId

    fun evict(sessionId: String?, expectedTurnId: String? = null) {
        if (sessionId.isNullOrBlank()) return
        if (expectedTurnId == null) {
            activeGrants.remove(sessionId)
        } else {
            activeGrants.compute(sessionId) { _, current ->
                if (current?.turnId == expectedTurnId) null else current
            }
        }
    }

    fun clear() {
        activeGrants.clear()
    }
}
