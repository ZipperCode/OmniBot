package cn.com.omnimind.baselib.llm.chatgpt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** Owns credentials only; Conversation/ACP Session/Turn lifecycles are deliberately outside this class. */
internal class ChatGptOAuthCoordinator(
    private val store: ChatGptCredentialStore,
    private val remote: ChatGptOAuthRemote,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val generations = mutableMapOf<String, Long>()
    private val refreshLocks = mutableMapOf<String, Mutex>()
    private val attempts = linkedMapOf<String, Attempt>()

    internal class Attempt(
        val id: String,
        val profileId: String,
        val generation: Long,
        val registration: ChatGptRegistration?,
        val redirectUri: String,
        val state: String,
        val nonce: String,
        val verifier: String,
        val authUrl: String,
        val expiresAtMillis: Long,
    ) {
        var status = "pending"
        var error: ChatGptOAuthException? = null
        var exchanging = false
        override fun toString() = "ChatGptOAuthAttempt(status=$status)"
    }

    fun beginLogin(profileId: String, redirectUri: String): Attempt = synchronized(lock) {
        if (profileId.isBlank()) throw ChatGptOAuthException("invalid_profile", "Select a model provider before signing in.")
        val existing = store.read(profileId)
        val hostId = store.hostId()
        cancelPendingLocked(profileId)
        val state = ChatGptOAuthProtocol.randomSecret()
        val nonce = ChatGptOAuthProtocol.randomSecret()
        val verifier = ChatGptOAuthProtocol.randomSecret()
        val result = Attempt(UUID.randomUUID().toString(), profileId, generation(profileId), existing,
            redirectUri, state, nonce, verifier,
            ChatGptOAuthProtocol.authorizationUrl(existing, hostId, redirectUri, state, nonce, verifier),
            nowMillis() + LOGIN_TIMEOUT_MILLIS)
        // Completed attempts are only retained for a small polling window.
        if (attempts.size >= 32) {
            attempts.entries.removeAll { it.value.status != "pending" }
            if (attempts.size >= 32) throw ChatGptOAuthException("too_many_attempts", "Too many ChatGPT sign-in attempts are active.")
        }
        attempts[result.id] = result
        result
    }

    fun loginStatus(attemptId: String): Map<String, Any?> = synchronized(lock) {
        val attempt = attempts[attemptId] ?: return@synchronized mapOf(
            "status" to "cancelled", "error" to "This sign-in attempt is no longer active.",
        )
        if (attempt.status == "pending" && nowMillis() >= attempt.expiresAtMillis) {
            failLocked(attempt, ChatGptOAuthException("login_timeout", "ChatGPT sign-in timed out. Please try again."))
        }
        mapOf("attemptId" to attemptId, "status" to attempt.status,
            "error" to attempt.error?.message, "errorCode" to attempt.error?.code)
    }

    fun cancelLogin(attemptId: String) = synchronized(lock) {
        val attempt = attempts[attemptId] ?: return@synchronized
        if (attempt.status == "pending") {
            attempt.status = "cancelled"
        }
    }

    fun failLogin(attemptId: String, error: ChatGptOAuthException) = synchronized(lock) {
        attempts[attemptId]?.let { if (it.status == "pending") failLocked(it, error) }
    }

    suspend fun completeLogin(attemptId: String, callback: Map<String, String>) {
        val attempt = synchronized(lock) {
            val pending = attempts[attemptId] ?: throw cancelled()
            requireCurrent(pending)
            if (pending.exchanging) throw cancelled()
            if (!ChatGptOAuthProtocol.stateMatches(pending.state, callback["state"])) {
                throw ChatGptOAuthException("invalid_state", "ChatGPT sign-in response could not be verified.")
            }
            pending.exchanging = true
            pending
        }
        try {
            if (callback.containsKey("error")) {
                throw ChatGptOAuthException("authorization_declined", "ChatGPT sign-in was not approved. You can try again.")
            }
            val clientId = ChatGptOAuthProtocol.issuedClientId(attempt.registration?.clientId, callback)
            val code = callback["code"]?.takeIf { it.isNotBlank() && it.length <= 8192 }
                ?: throw ChatGptOAuthException("missing_code", "ChatGPT did not return an authorization code.")
            synchronized(lock) {
                requireCurrent(attempt)
                // Keep the issued registration even if this single-use code later fails to exchange.
                if (attempt.registration == null) store.write(attempt.profileId, ChatGptRegistration(clientId))
            }
            val tokens = remote.exchange(clientId, code, attempt.verifier, attempt.redirectUri)
            val idToken = tokens.idToken ?: throw invalidIdentity()
            val identity = remote.identity(idToken, clientId, attempt.nonce, attempt.registration?.subject, true)
            synchronized(lock) {
                requireCurrent(attempt)
                val current = store.read(attempt.profileId)
                val registration = ChatGptRegistration(
                    clientId, identity.subject, identity.email, tokens.accessToken, tokens.refreshToken,
                    idToken, attempt.nonce, expiry(tokens),
                    tokens.earliestRefreshAtSeconds, tokens.scopes ?: emptySet(), nextRevision(current),
                )
                store.write(attempt.profileId, registration)
                bumpGeneration(attempt.profileId)
                attempt.status = "completed"
            }
        } catch (error: CancellationException) {
            cancelLogin(attemptId)
            throw error
        } catch (error: Exception) {
            val safe = error as? ChatGptOAuthException
                ?: ChatGptOAuthException("login_failed", "ChatGPT sign-in could not be completed. Please try again.")
            failLogin(attemptId, safe)
        }
    }

    fun account(profileId: String): Map<String, Any?> = synchronized(lock) {
        val current = store.read(profileId)
        mapOf("connected" to (current?.hasTokens == true), "email" to current?.email,
            "planEnabled" to (current?.hasTokens == true && current.planEnabled),
            "needsReauth" to (current != null && !current.hasTokens), "registered" to (current != null),
            "revision" to (current?.accountRevision ?: 0L))
    }

    fun credentialBinding(profileId: String): ChatGptOAuthCredentialBinding = synchronized(lock) {
        val current = store.read(profileId)?.takeIf { it.hasTokens } ?: throw signInRequired()
        if (!current.planEnabled) throw planDisabled()
        ChatGptOAuthCredentialBinding(profileId, generation(profileId))
    }

    suspend fun accessToken(profileId: String, binding: ChatGptOAuthCredentialBinding? = null): String {
        val mutex = synchronized(lock) { refreshLocks.getOrPut(profileId) { Mutex() } }
        return mutex.withLock {
            val (current, version) = synchronized(lock) {
                if (binding != null && (binding.profileId != profileId || binding.generation != generation(profileId))) {
                    throw cancelled()
                }
                val value = store.read(profileId)?.takeIf { it.hasTokens } ?: throw signInRequired()
                value to generation(profileId)
            }
            if (!current.planEnabled) throw planDisabled()
            val now = nowMillis() / 1000
            if (current.expiresAtSeconds > now + REFRESH_WINDOW_SECONDS) {
                return@withLock synchronized(lock) {
                    requireUnchanged(profileId, current, version)
                    current.accessToken!!
                }
            }
            if (current.earliestRefreshAtSeconds > now) {
                if (current.expiresAtSeconds > now) return@withLock synchronized(lock) {
                    requireUnchanged(profileId, current, version)
                    current.accessToken!!
                }
                throw ChatGptOAuthException("refresh_not_ready", "ChatGPT authorization cannot be renewed yet. Please try again later.")
            }
            if (current.refreshToken.isNullOrBlank()) {
                if (current.expiresAtSeconds > now) return@withLock synchronized(lock) {
                    requireUnchanged(profileId, current, version)
                    current.accessToken!!
                }
                synchronized(lock) { clearTokensIfCurrent(profileId, current, version) }
                throw signInRequired()
            }
            // Once rotation starts, cancelling an inference request must not discard its replacement
            // refresh token. HTTP calls are bounded; logout/deletion still win the generation check.
            val refreshed = withContext(NonCancellable) {
                try {
                    val tokens = remote.refresh(current)
                    val identity = tokens.idToken?.let {
                        remote.identity(it, current.clientId, current.loginNonce, current.subject, false)
                    }
                    synchronized(lock) {
                        requireUnchanged(profileId, current, version)
                        val newScopes = tokens.scopes ?: current.scopes
                        val updated = current.copy(
                            accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
                            idToken = tokens.idToken ?: current.idToken, email = identity?.email ?: current.email,
                            expiresAtSeconds = expiry(tokens),
                            earliestRefreshAtSeconds = tokens.earliestRefreshAtSeconds,
                            scopes = newScopes,
                            accountRevision = if (newScopes != current.scopes) nextRevision(current) else current.accountRevision,
                        )
                        store.write(profileId, updated)
                        if (!updated.planEnabled) throw planDisabled()
                        updated.accessToken!!
                    }
                } catch (error: ChatGptOAuthException) {
                    if (error.code in OpenAiChatGptOAuthRemote.UNUSABLE_REFRESH_ERRORS || error.code == "invalid_identity") {
                        synchronized(lock) { clearTokensIfCurrent(profileId, current, version) }
                    }
                    throw error
                }
            }
            currentCoroutineContext().ensureActive()
            refreshed
        }
    }

    suspend fun logout(profileId: String): Map<String, Any?> {
        val (detached, refreshInFlight) = synchronized(lock) {
            invalidateLocked(profileId)
            val previous = store.read(profileId)?.also {
                store.write(profileId, it.withoutTokens().copy(accountRevision = nextRevision(it)))
            }
            previous to (refreshLocks[profileId]?.isLocked == true)
        }
        // Invalidate locally before any network suspension. A later sign-in is never erased here.
        val confirmed = detached?.let { remote.revoke(it) } ?: true
        // An already-running rotation may have replaced the detached refresh token remotely.
        return mapOf("remoteRevocationConfirmed" to (confirmed && !refreshInFlight))
    }

    fun removeProfile(profileId: String) = synchronized(lock) {
        invalidateLocked(profileId)
        store.delete(profileId)
    }

    private fun clearTokensIfCurrent(profileId: String, current: ChatGptRegistration, version: Long) {
        if (generation(profileId) == version && store.read(profileId) == current) {
            store.write(profileId, current.withoutTokens().copy(accountRevision = nextRevision(current)))
        }
    }

    private fun requireUnchanged(profileId: String, current: ChatGptRegistration, version: Long) {
        if (generation(profileId) != version || store.read(profileId) != current) throw cancelled()
    }

    private fun requireCurrent(attempt: Attempt) {
        if (attempt.status != "pending" || generation(attempt.profileId) != attempt.generation) throw cancelled()
        if (nowMillis() >= attempt.expiresAtMillis) {
            throw ChatGptOAuthException("login_timeout", "ChatGPT sign-in timed out. Please try again.")
        }
    }

    private fun failLocked(attempt: Attempt, error: ChatGptOAuthException) {
        attempt.status = "failed"
        attempt.error = error
    }

    private fun invalidateLocked(profileId: String) {
        bumpGeneration(profileId)
        cancelPendingLocked(profileId)
    }

    private fun cancelPendingLocked(profileId: String) {
        attempts.values.filter { it.profileId == profileId && it.status == "pending" }
            .forEach { it.status = "cancelled" }
    }

    private fun generation(profileId: String) = generations[profileId] ?: 0L
    private fun bumpGeneration(profileId: String) { generations[profileId] = generation(profileId) + 1 }
    private fun nextRevision(current: ChatGptRegistration?) = maxOf(nowMillis(), (current?.accountRevision ?: 0L) + 1)
    private fun expiry(tokens: ChatGptTokens) =
        (tokens.receivedAtSeconds.takeIf { it > 0 } ?: (nowMillis() / 1000)) + tokens.expiresInSeconds
    private fun cancelled() = ChatGptOAuthException("login_cancelled", "ChatGPT authorization was cancelled or changed.")
    private fun signInRequired() = ChatGptOAuthException("sign_in_required", "Please sign in with ChatGPT again.")
    private fun planDisabled() = ChatGptOAuthException("plan_disabled", "ChatGPT plan usage is not enabled. Enable it by signing in again, or select another provider.")
    private fun invalidIdentity() = ChatGptOAuthException("invalid_identity", "ChatGPT did not return a verifiable identity. Please sign in again.")

    companion object {
        const val LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1000L
        private const val REFRESH_WINDOW_SECONDS = 60
        val DISCONNECTED_ACCOUNT: Map<String, Any?> = mapOf(
            "connected" to false, "email" to null, "planEnabled" to false,
            "needsReauth" to false, "registered" to false, "revision" to 0L,
        )
    }
}
