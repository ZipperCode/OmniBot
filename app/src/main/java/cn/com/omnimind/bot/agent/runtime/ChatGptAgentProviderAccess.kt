package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.assists.controller.http.OpenAiResponsesSseBody
import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthException
import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthManager
import cn.com.omnimind.baselib.llm.chatgpt.ChatGptResponsesRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.HttpRequestLifecycle
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One external process's revocable access to its selected ChatGPT Provider.
 *
 * The child receives a loopback URL and random local bearer, never OAuth tokens. The existing
 * credential owner resolves each upstream request. This is an HTTP boundary only: Responses
 * events pass through unchanged and ACP remains the sole session/turn lifecycle.
 */
internal class ChatGptAgentProviderAccess private constructor(
    override val profile: ModelProviderProfile,
    private val resolveAccessToken: suspend () -> String,
    private val isProfileCurrent: () -> Boolean,
    private val upstream: HttpUrl,
    private val client: OkHttpClient,
) : AgentProviderAccess {
    private val lock = Any()
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val localBearer = ByteArray(32).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private var port = 0
    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        install(HttpRequestLifecycle) { cancelCallOnClose = true }
        routing { post("/v1/responses") { forward(call) } }
    }

    override val credentials: AgentProviderCredentials
        get() = AgentProviderCredentials(
            baseUrl = "http://127.0.0.1:$port/v1",
            apiKey = localBearer,
            wireApi = OpenAiWireApi.RESPONSES,
            supportsNamespaceTools = true,
            authentication = AgentProviderAuthentication.HOST_MANAGED,
        )

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            calls.toList().also { calls.clear() }
        }
        pending.forEach(Call::cancel)
        server.stop(0, 1_000)
    }

    private suspend fun forward(call: ApplicationCall) {
        val authorization = call.request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()
        val authorized = authorization?.startsWith("Bearer ") == true && MessageDigest.isEqual(
            authorization.removePrefix("Bearer ").toByteArray(), localBearer.toByteArray(),
        )
        if (!authorized || synchronized(lock) { closed }) {
            call.error(HttpStatusCode.Unauthorized, "invalid_api_key", "Local Provider access has expired.")
            return
        }
        if (call.request.headers[HttpHeaders.Host] != "127.0.0.1:$port" ||
            call.request.headers[HttpHeaders.Origin] != null || call.request.queryParameters.names().isNotEmpty()) {
            call.error(HttpStatusCode.BadRequest, "invalid_request", "Invalid local Provider request.")
            return
        }
        try {
            val bytes = call.receiveChannel().readRemaining(MAX_REQUEST_BYTES + 1L).readByteArray()
            if (bytes.size > MAX_REQUEST_BYTES) {
                call.error(HttpStatusCode.PayloadTooLarge, "request_too_large", "Responses request is too large.")
                return
            }
            val request = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: throw IllegalArgumentException("Responses request must be a JSON object.")
            val payload = try {
                ChatGptResponsesRequest.normalize(request)
            } catch (error: IllegalArgumentException) {
                // The shared policy emits fixed, actionable messages without request contents.
                call.error(HttpStatusCode.BadRequest, "unsupported_request", error.message ?: "Unsupported Responses request.")
                return
            }
            if (!isProfileCurrent()) {
                call.error(HttpStatusCode.Unauthorized, "provider_changed", "The selected ChatGPT Provider has changed.")
                return
            }
            val token = resolveAccessToken()
            val httpCall = client.newCall(Request.Builder()
                .url(upstream)
                .header(HttpHeaders.Authorization, "Bearer $token")
                .header(HttpHeaders.Accept, "text/event-stream")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build())
            synchronized(lock) {
                if (closed) throw IOException("Local Provider access has expired.")
                calls += httpCall
            }
            try {
                stream(call, httpCall)
            } finally {
                httpCall.cancel()
                synchronized(lock) { calls -= httpCall }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ChatGptOAuthException) {
            if (call.response.isCommitted) throw error
            call.error(oauthErrorStatus(error.code), error.code, error.message ?: "ChatGPT authorization is unavailable.")
        } catch (_: IllegalArgumentException) {
            if (call.response.isCommitted) throw IOException("Invalid upstream response.")
            call.error(HttpStatusCode.BadRequest, "invalid_request", "Use complete Responses input and supported ChatGPT tools and parameters.")
        } catch (error: Exception) {
            // A broken stream ends at the transport. Never append a fabricated terminal event,
            // retry inference, or expose an exception that might contain authorization data.
            if (call.response.isCommitted) throw IOException("ChatGPT response stream interrupted.")
            call.error(HttpStatusCode.BadGateway, "provider_unavailable", "The ChatGPT Provider request could not be completed.")
        }
    }

    private suspend fun stream(downstream: ApplicationCall, upstreamCall: Call) = coroutineScope {
        // Cancellation must reach the socket even while an OkHttp read is blocked. Merely using
        // withContext(IO) would keep the upstream request alive after the child disconnects.
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { upstreamCall.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                upstreamCall.execute().use { response ->
                    val normalized = OpenAiResponsesSseBody.normalize(response)
                    for (header in RESPONSE_HEADERS) {
                        response.header(header)?.let { downstream.response.header(header, it) }
                    }
                    downstream.response.header(HttpHeaders.CacheControl, "no-store")
                    downstream.respondBytesWriter(
                        contentType = normalized.body.contentType()?.toString()?.let(ContentType::parse)
                            ?: ContentType.Application.OctetStream,
                        status = HttpStatusCode.fromValue(response.code),
                    ) {
                        val source = normalized.body.byteStream()
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val count = withContext(Dispatchers.IO) { source.read(buffer) }
                            if (count < 0) break
                            writeFully(buffer, 0, count)
                            flush()
                        }
                    }
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String, message: String) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respondText(buildJsonObject {
            put("error", buildJsonObject {
                put("type", if (status.value >= 500) "api_error" else "invalid_request_error")
                put("code", code)
                put("message", message)
            })
        }.toString(), ContentType.Application.Json, status)
    }

    companion object {
        private const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
        private val RESPONSE_HEADERS = listOf("x-request-id", "retry-after", "openai-processing-ms")
        private fun oauthErrorStatus(code: String): HttpStatusCode = when (code) {
            "sign_in_required", "login_cancelled", "invalid_identity", "invalid_client",
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused" -> HttpStatusCode.Unauthorized
            "plan_disabled" -> HttpStatusCode.Forbidden
            "network_error", "oauth_request_failed", "refresh_not_ready" -> HttpStatusCode.ServiceUnavailable
            "invalid_response" -> HttpStatusCode.BadGateway
            else -> HttpStatusCode.InternalServerError
        }
        private val httpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()

        suspend fun open(profile: ModelProviderProfile): ChatGptAgentProviderAccess {
            val binding = ChatGptOAuthManager.credentialBinding(profile.id)
            return open(
                profile = profile,
                resolveAccessToken = { ChatGptOAuthManager.accessToken(binding) },
                isProfileCurrent = {
                    ChatGptOAuthProvider.isOAuth(ModelProviderConfigStore.getProfile(profile.id)?.sourceType)
                },
            )
        }

        /** Injectable HTTP/credential boundary for tests; production always uses the fixed endpoint. */
        internal suspend fun open(
            profile: ModelProviderProfile,
            resolveAccessToken: suspend () -> String,
            isProfileCurrent: () -> Boolean = { true },
            upstream: HttpUrl = "${ChatGptOAuthProvider.BASE_URL}/responses".toHttpUrl(),
            client: OkHttpClient = httpClient,
        ): ChatGptAgentProviderAccess {
            val access = ChatGptAgentProviderAccess(profile, resolveAccessToken, isProfileCurrent, upstream, client)
            try {
                access.server.start(wait = false)
                access.port = access.server.engine.resolvedConnectors().single().port
                return access
            } catch (error: Throwable) {
                access.close()
                throw error
            }
        }
    }
}
