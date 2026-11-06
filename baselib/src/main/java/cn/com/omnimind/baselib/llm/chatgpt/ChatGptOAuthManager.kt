package cn.com.omnimind.baselib.llm.chatgpt

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Native-only OAuth credential boundary. Flutter receives a browser URL and account metadata;
 * only a native Provider request may obtain an access token through [accessToken].
 */
object ChatGptOAuthManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerLock = Any()
    private val listeners = mutableMapOf<String, Listener>()
    @Volatile private var coordinator: ChatGptOAuthCoordinator? = null

    @Synchronized
    fun initialize(context: Context) {
        if (coordinator == null) {
            coordinator = ChatGptOAuthCoordinator(
                EncryptedChatGptCredentialStore(context.applicationContext), OpenAiChatGptOAuthRemote(),
            )
        }
    }

    fun startLogin(profileId: String): Map<String, Any?> {
        val owner = requireCoordinator()
        closeListenersForProfile(profileId)
        val server = try {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        } catch (_: Exception) {
            throw ChatGptOAuthException("callback_unavailable", "Could not open the local ChatGPT sign-in callback. Please try again.")
        }
        val attempt = try {
            owner.beginLogin(profileId, "http://127.0.0.1:${server.localPort}${ChatGptOAuthProtocol.CALLBACK_PATH}")
        } catch (error: Exception) {
            server.close()
            throw error
        }
        val listener = Listener(profileId, server)
        synchronized(listenerLock) { listeners[attempt.id] = listener }
        listener.job = scope.launch {
            try {
                listen(owner, attempt, server)
            } catch (error: CancellationException) {
                owner.cancelLogin(attempt.id)
                throw error
            } catch (_: Exception) {
                owner.failLogin(attempt.id, ChatGptOAuthException(
                    "callback_failed", "The local ChatGPT sign-in callback was interrupted. Please try again.",
                ))
            } finally {
                runCatching { server.close() }
                synchronized(listenerLock) { listeners.remove(attempt.id) }
            }
        }
        return mapOf("attemptId" to attempt.id, "authUrl" to attempt.authUrl)
    }

    fun loginStatus(attemptId: String): Map<String, Any?> = coordinator?.loginStatus(attemptId)
        ?: mapOf("status" to "cancelled", "error" to "This sign-in attempt is no longer active.")

    fun cancelLogin(attemptId: String) {
        coordinator?.cancelLogin(attemptId)
        synchronized(listenerLock) { listeners.remove(attemptId) }?.close()
    }

    fun account(profileId: String): Map<String, Any?> = try {
        coordinator?.account(profileId) ?: ChatGptOAuthCoordinator.DISCONNECTED_ACCOUNT
    } catch (error: ChatGptOAuthException) {
        if (error.code != "secure_storage_unavailable" && error.code != ChatGptRegistrationCodec.INVALID_FORMAT_CODE) throw error
        ChatGptOAuthCoordinator.DISCONNECTED_ACCOUNT + mapOf(
            "secureStorageAvailable" to false, "error" to error.message, "errorCode" to error.code,
        )
    }

    suspend fun accessToken(profileId: String): String = requireCoordinator().accessToken(profileId)

    /** A native process may reuse refreshes, but never a later login to this Provider. */
    fun credentialBinding(profileId: String): ChatGptOAuthCredentialBinding =
        requireCoordinator().credentialBinding(profileId)

    suspend fun accessToken(binding: ChatGptOAuthCredentialBinding): String =
        requireCoordinator().accessToken(binding.profileId, binding)

    suspend fun logout(profileId: String): Map<String, Any?> {
        closeListenersForProfile(profileId)
        return requireCoordinator().logout(profileId)
    }

    /** Used when a Provider is removed or changes authentication type, including import/replace. */
    fun removeProfile(profileId: String) {
        closeListenersForProfile(profileId)
        coordinator?.removeProfile(profileId)
    }

    private fun requireCoordinator() = coordinator ?: throw ChatGptOAuthException(
        "not_initialized", "ChatGPT sign-in is not available yet. Please reopen the app.",
    )

    private fun closeListenersForProfile(profileId: String) {
        val matching = synchronized(listenerLock) {
            listeners.filterValues { it.profileId == profileId }.toList().also { values ->
                values.forEach { listeners.remove(it.first) }
            }
        }
        matching.forEach { (id, listener) -> coordinator?.cancelLogin(id); listener.close() }
    }

    private suspend fun listen(
        owner: ChatGptOAuthCoordinator, attempt: ChatGptOAuthCoordinator.Attempt, server: ServerSocket,
    ) {
        while (owner.loginStatus(attempt.id)["status"] == "pending") {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            val callback = socket.use { connection ->
                connection.soTimeout = 2000
                val fields = try {
                    val target = readTarget(connection, server.localPort)
                    ChatGptOAuthProtocol.parseCallback(target, attempt.redirectUri)
                } catch (_: Exception) { null }
                if (fields == null || !ChatGptOAuthProtocol.stateMatches(attempt.state, fields["state"])) {
                    runCatching { reply(connection, false) }
                    null
                } else {
                    // Do not hold the browser socket open during token exchange and signature validation.
                    runCatching { reply(connection, true) }
                    fields
                }
            }
            if (callback != null) {
                server.close()
                owner.completeLogin(attempt.id, callback)
                return
            }
        }
    }

    private fun readTarget(socket: Socket, port: Int): String {
        val input = socket.getInputStream()
        var bytesRead = 0
        fun line(): String {
            val output = ByteArrayOutputStream()
            while (true) {
                require(++bytesRead <= 16 * 1024)
                val value = input.read()
                require(value != -1)
                if (value == '\n'.code) return output.toString("US-ASCII").removeSuffix("\r")
                output.write(value)
            }
        }
        val request = line().split(' ')
        require(request.size == 3 && request[0] == "GET" && request[2] in setOf("HTTP/1.1", "HTTP/1.0"))
        var host: String? = null
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val name = header.substringBefore(':')
            if (name.equals("Host", ignoreCase = true)) {
                require(host == null)
                host = header.substringAfter(':').trim()
            }
        }
        require(host == "127.0.0.1:$port")
        return request[1]
    }

    private fun reply(socket: Socket, accepted: Boolean) {
        val status = if (accepted) "200 OK" else "400 Bad Request"
        val message = if (accepted) "Sign-in response received. Return to OmniBot to finish." else
            "This sign-in response could not be verified. Return to OmniBot and try again."
        val html = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>OmniBot</title><body><p>$message</p></body></html>"
        val bytes = html.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
            "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(Charsets.US_ASCII)); write(bytes); flush()
        }
    }

    private class Listener(val profileId: String, val server: ServerSocket, var job: Job? = null) {
        fun close() { runCatching { server.close() }; job?.cancel() }
    }
}
