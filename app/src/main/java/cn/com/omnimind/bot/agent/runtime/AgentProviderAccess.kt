package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One external process's Provider capability. Credentials may contain an opaque
 * local bearer grant, but never the account's OAuth access or refresh token.
 * Closing must synchronously revoke access before any asynchronous server cleanup.
 */
internal interface AgentProviderAccess : AutoCloseable {
    val profile: ModelProviderProfile
    val credentials: AgentProviderCredentials
    override fun close()
}

/** A real process must own its capability; a configuration probe may borrow an existing one. */
internal enum class AcpLaunchPreparation { PROCESS, PROBE }

/**
 * Resources prepared for one actual process launch. The connect attempt owns
 * these until initialization succeeds; its runtime then owns them until teardown.
 * A configuration-only operation may borrow existing credentials by leaving
 * [providerAccess] null, so closing it never revokes another process's grant.
 */
internal class PreparedAcpLaunch(
    environment: Map<String, String>,
    val providerAccess: AgentProviderAccess? = null,
) : AutoCloseable {
    val environment: Map<String, String> = environment.toMap()
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) providerAccess?.close()
    }

    override fun toString(): String = "PreparedAcpLaunch(credentials=redacted, closed=$isClosed)"
}
