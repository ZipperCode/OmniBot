package cn.com.omnimind.bot.agent

import android.os.Build
import cn.com.omnimind.baselib.permission.LocalNetworkPermission
import cn.com.omnimind.bot.App

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ChatCompletionUsage
import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.OpenAiResponsesFunctionNameCodec
import cn.com.omnimind.baselib.llm.ReasoningStreamUpdatePolicy
import cn.com.omnimind.baselib.llm.contentText
import cn.com.omnimind.baselib.llm.encodeRequestToString
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

interface AgentLlmClient {
    suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)? = null,
        onContentUpdate: (suspend (String) -> Unit)? = null,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)? = null,
        // The existing logical-turn owner disables replay after earlier round output.
        allowTransportRetry: Boolean = true,
    ): ChatCompletionTurn
}

class AgentStreamRequestException(
    val statusCode: Int?,
    val reason: String,
    val responseBody: String?,
    val responseStarted: Boolean = false,
    cause: Throwable? = null,
    val retryAfterMillis: Long? = null,
) : RuntimeException(
    "chat completion stream request failed${
        statusCode?.let { "($it)" }.orEmpty()
    }: $reason", cause
)

class AgentStreamIdleTimeoutException(
    val timeoutMillis: Long,
    val responseStarted: Boolean = false,
) : RuntimeException("chat completion stream idle timeout after ${timeoutMillis}ms")

class HttpAgentLlmClient(
    private val scope: CoroutineScope,
    modelOverride: AgentModelOverride? = null,
    private val streamRequestOp: (suspend (
        model: String,
        requestBodyJson: String,
        event: EventSourceListener,
        explicitApiBase: String?,
        explicitApiKey: String?,
        explicitCustomHeaders: Map<String, String>?,
        explicitModel: String?,
        explicitProtocolType: String?,
        explicitWireApi: String?,
        forceHttp1: Boolean
    ) -> EventSource)? = null,
    private val resolveRouteInfoOp: (
        modelOrScene: String,
        explicitApiBase: String?,
        explicitApiKey: String?,
        explicitCustomHeaders: Map<String, String>?,
        explicitModel: String?,
        explicitProtocolType: String?,
        explicitWireApi: String?
    ) -> HttpController.ChatCompletionRouteInfo = { modelOrScene, explicitApiBase, explicitApiKey, explicitCustomHeaders, explicitModel, explicitProtocolType, explicitWireApi ->
        HttpController.resolveChatCompletionRouteInfo(
            modelOrScene = modelOrScene,
            explicitApiBase = explicitApiBase,
            explicitApiKey = explicitApiKey,
            explicitCustomHeaders = explicitCustomHeaders,
            explicitModel = explicitModel,
            explicitProtocolType = explicitProtocolType,
            explicitWireApi = explicitWireApi,
            explicitProviderProfileId = modelOverride?.oauthProfileId,
        )
    },
    // This is the single transport retry owner. A retry is safe only before
    // visible output exists; replaying a started stream duplicates reasoning,
    // text, and potentially tool intent.
    private val maxTransientStreamRetries: Int? = null,
    private val transientStreamRetryDelayMs: Long? = null,
    private val streamIdleTimeoutMs: Long? = null,
    private val retryDelayOp: suspend (Long) -> Unit = { delay(it) },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    },
) : AgentLlmClient {
    private val effectiveMaxTransientStreamRetries: Int
        get() = (maxTransientStreamRetries ?: 2).coerceIn(0, 2)
    private val effectiveTransientStreamRetryDelayMs: Long
        get() = (transientStreamRetryDelayMs ?: 1_000L).coerceIn(0L, MAX_RETRY_WAIT_MS)
    private val effectiveStreamIdleTimeoutMs: Long?
        get() = streamIdleTimeoutMs
    private val modelOverride: AgentModelOverride? = modelOverride?.normalized()
    private val tag = "HttpAgentLlmClient"
    private companion object {
        const val MAX_RETRY_WAIT_MS = 30_000L
        const val REASONING_UPDATE_INTERVAL_MS =
            ReasoningStreamUpdatePolicy.DEFAULT_INTERVAL_MS
        const val DEFAULT_CLOSED_STREAM_ERROR =
            "chat completion stream closed before completion signal"
        val TRANSIENT_STREAM_FAILURE_MARKERS = listOf(
            "software caused connection abort",
            "unable to resolve host",
            "connection reset",
            "connection refused",
            "failed to connect",
            "network is unreachable",
            "unexpected end of stream",
            "socket closed",
            "timeout",
            "timed out",
            "closed before completion signal",
        )
    }


    override suspend fun streamTurn(
        request: ChatCompletionRequest,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
        allowTransportRetry: Boolean,
    ): ChatCompletionTurn {
        return streamRoutedTurn(
            request = request,
            effectiveExplicitModel = modelOverride?.modelId,
            onReasoningUpdate = onReasoningUpdate,
            onContentUpdate = onContentUpdate,
            onToolCallInput = onToolCallInput,
            allowTransportRetry = allowTransportRetry,
        )
    }
    private suspend fun streamRoutedTurn(
        request: ChatCompletionRequest,
        effectiveExplicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
        allowTransportRetry: Boolean,
    ): ChatCompletionTurn {
        val routeInfo = resolveRouteInfoOp(
            request.model,
            modelOverride?.apiBase,
            modelOverride?.apiKey,
            modelOverride?.customHeaders,
            effectiveExplicitModel,
            modelOverride?.protocolType,
            modelOverride?.wireApi,
        )
        if (Build.VERSION.SDK_INT >= 37) {
            LocalNetworkPermission.requireForEndpoint(App.instance, routeInfo.apiBase.orEmpty())
        }
        val namePlan = if (OpenAiWireApi.isResponses(routeInfo.wireApi)) {
            OpenAiResponsesFunctionNameCodec.planFor(request)
        } else null
        val wireRequest = namePlan?.encodeRequest(request) ?: request
        val turn = streamTurnWithTransportRetry(
            model = request.model,
            requestJson = json.encodeToJsonElement(wireRequest).jsonObject,
            explicitModel = effectiveExplicitModel,
            allowTransportRetry = allowTransportRetry,
            onReasoningUpdate = onReasoningUpdate,
            onContentUpdate = onContentUpdate,
            onToolCallInput = { call ->
                onToolCallInput?.invoke(call.copy(function = call.function.copy(
                    name = namePlan?.restore(call.function.name) ?: call.function.name,
                )))
            },
        )
        return namePlan?.restoreTurn(turn) ?: turn
    }

    private suspend fun streamTurnWithTransportRetry(
        model: String,
        requestJson: JsonObject,
        explicitModel: String?,
        allowTransportRetry: Boolean,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
    ): ChatCompletionTurn {
        // Transient errors and HTTP/2 recovery share one bounded attempt budget.
        // Never start a second logical prompt.
        val retryCount = if (allowTransportRetry) effectiveMaxTransientStreamRetries else 0
        var forceHttp1 = false
        var retriedIncompleteToolCall = false
        repeat(retryCount + 1) { attempt ->
            var attemptProducedOutput = false
            suspend fun forward(callback: (suspend (String) -> Unit)?, value: String) {
                if (value.isNotBlank()) attemptProducedOutput = true
                callback?.invoke(value)
            }
            try {
                return doStreamTurnOnce(
                    model, requestJson, explicitModel,
                    onReasoningUpdate = { forward(onReasoningUpdate, it) },
                    onContentUpdate = { forward(onContentUpdate, it) },
                    onToolCallInput = { call -> attemptProducedOutput = true; onToolCallInput?.invoke(call) },
                    forceHttp1 = forceHttp1,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val responseStarted = when (error) {
                    is AgentStreamRequestException -> error.responseStarted
                    is AgentStreamIdleTimeoutException -> error.responseStarted
                    else -> false
                }
                if (attemptProducedOutput || responseStarted || attempt >= retryCount) throw error
                var immediateRecovery = false
                when (error) {
                    is AgentStreamRequestException -> when {
                        isHttp2ProtocolError(error) && !forceHttp1 -> {
                            forceHttp1 = true
                            immediateRecovery = true
                        }
                        !isTransientStreamFailure(error) -> throw error
                    }
                    is AgentIncompleteToolCallException -> {
                        if (retriedIncompleteToolCall) throw error
                        retriedIncompleteToolCall = true
                    }
                    is AgentStreamIdleTimeoutException -> Unit
                    else -> throw error
                }
                val retryAfterMs = (error as? AgentStreamRequestException)?.retryAfterMillis ?: 0L
                // Do not retry earlier than a long Retry-After or leave the UI
                // waiting for minutes. Surface the existing rate-limit error.
                if (retryAfterMs > MAX_RETRY_WAIT_MS) throw error
                val backoffMs = if (immediateRecovery) 0L else
                    (effectiveTransientStreamRetryDelayMs * (1L shl attempt)).coerceAtMost(MAX_RETRY_WAIT_MS)
                val delayMs = maxOf(backoffMs, retryAfterMs)
                OmniLog.w(tag, "transport retry attempt=${attempt + 1}/$retryCount delayMs=$delayMs " +
                    "status=${(error as? AgentStreamRequestException)?.statusCode ?: "none"}")
                if (delayMs > 0L) retryDelayOp(delayMs)
            }
        }
        error("unreachable transport retry state")
    }

    private fun isHttp2ProtocolError(error: AgentStreamRequestException): Boolean {
        if (error.statusCode != null && error.statusCode !in 200..299) return false
        return error.reason.contains("PROTOCOL_ERROR", ignoreCase = true)
                || error.reason.contains("stream was reset", ignoreCase = true)
    }

    private fun isTransientStreamFailure(error: AgentStreamRequestException): Boolean {
        if (AgentRuntimeErrorSupport.failureKind(error) in setOf(
                AgentRuntimeErrorSupport.PROVIDER_QUOTA_EXCEEDED,
                AgentRuntimeErrorSupport.PROVIDER_AUTHENTICATION_FAILED,
                AgentRuntimeErrorSupport.PROVIDER_MODEL_UNAVAILABLE,
                AgentRuntimeErrorSupport.PROVIDER_TLS_CERTIFICATE_FAILURE,
            )) return false
        val status = error.statusCode
        if (status in setOf(408, 425, 429, 500, 502, 503, 504, 529)) {
            return true
        }
        if (status != null && status !in 200..299) return false
        val reason = error.reason.lowercase()
        return TRANSIENT_STREAM_FAILURE_MARKERS.any(reason::contains)
    }

    private suspend fun doStreamTurnOnce(
        model: String,
        requestJson: JsonObject,
        explicitModel: String?,
        onReasoningUpdate: (suspend (String) -> Unit)?,
        onContentUpdate: (suspend (String) -> Unit)?,
        onToolCallInput: (suspend (AssistantToolCall) -> Unit)?,
        forceHttp1: Boolean
    ): ChatCompletionTurn {
        val streamDone = CompletableDeferred<ChatCompletionTurn>()
        val completed = AtomicBoolean(false)
        val startedAtMs = System.currentTimeMillis()
        var firstEventLogged = false
        var firstReasoningLogged = false
        var firstContentLogged = false
        val routeInfo = resolveRouteInfoOp(
            model,
            modelOverride?.apiBase,
            modelOverride?.apiKey,
            modelOverride?.customHeaders,
            explicitModel,
            modelOverride?.protocolType,
            modelOverride?.wireApi
        )
        val accumulator = AgentLlmStreamAccumulator(
            json = json,
            includeReasoningInAssistantMessage =
                routeInfo.providerCapabilities.requiresReasoningContentForToolCalls,
            captureAnthropicContentBlocks =
                routeInfo.providerCapabilities.requiresAnthropicThinkingReplay,
            anthropicSourceModel = routeInfo.resolvedModel
        )
        var lastReasoning = ""
        var lastReasoningEmitLength = 0
        var lastReasoningEmitAt = 0L
        var reasoningEmitJob: Job? = null
        val reasoningLock = Any()
        var lastContent = ""
        var lastToolInputEmitAt = 0L
        val lastToolInputs = mutableMapOf<String, AssistantToolCall>()
        var eventSource: EventSource? = null
        val lastStreamActivityAtMs = AtomicLong(startedAtMs)
        val emissionQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val emissionLock = Any()
        val emissionJob = scope.launch {
            for (block in emissionQueue) {
                runCatching { block.invoke() }
                    .onFailure { OmniLog.w(tag, "stream emission failed: ${it.message}") }
            }
        }
        fun enqueueEmission(block: suspend () -> Unit) {
            if (emissionQueue.isClosedForSend) {
                return
            }
            emissionQueue.trySend(block)
        }

        fun providerFailure(error: AgentProviderStreamException, rawPayload: String? = null) =
            AgentStreamRequestException(
                statusCode = error.statusCode,
                reason = error.message ?: "provider stream error",
                responseBody = rawPayload ?: JsonObject(mapOf("error" to JsonObject(
                    mapOf("code" to JsonPrimitive(error.providerCode)),
                ))).toString(),
                responseStarted = accumulator.hasAssistantPayload(),
                cause = error,
            )

        fun dispatchReasoningSnapshot(reasoning: String) {
            lastReasoning = reasoning
            if (onReasoningUpdate != null) {
                enqueueEmission {
                    onReasoningUpdate.invoke(reasoning)
                }
            }
        }

        fun collectReasoningSnapshotLocked(): String? {
            val length = accumulator.currentReasoningLength()
            if (length <= 0 || length == lastReasoningEmitLength) return null
            val reasoning = accumulator.currentReasoning()
            lastReasoningEmitLength = length
            if (reasoning.isBlank() || reasoning == lastReasoning) return null
            lastReasoning = reasoning
            lastReasoningEmitAt = System.currentTimeMillis()
            return reasoning
        }

        fun scheduleReasoningSnapshotLocked(delayMs: Long) {
            reasoningEmitJob = scope.launch {
                delay(delayMs)
                synchronized(emissionLock) {
                    val snapshot = synchronized(reasoningLock) {
                        reasoningEmitJob = null
                        collectReasoningSnapshotLocked()
                    }
                    if (snapshot != null) {
                        dispatchReasoningSnapshot(snapshot)
                    }
                }
            }
        }

        fun emitReasoning(force: Boolean = false) {
            var snapshot: String? = null
            synchronized(emissionLock) {
                synchronized(reasoningLock) {
                    val length = accumulator.currentReasoningLength()
                    if (length <= 0 || length == lastReasoningEmitLength) return
                    if (force) {
                        reasoningEmitJob?.cancel()
                        reasoningEmitJob = null
                        snapshot = collectReasoningSnapshotLocked()
                        return@synchronized
                    }
                    if (reasoningEmitJob?.isActive == true) return
                    val delayMs = ReasoningStreamUpdatePolicy.nextDelayMs(
                        hasEmittedBefore = lastReasoningEmitLength > 0,
                        lastEmitAtMs = lastReasoningEmitAt,
                        nowMs = System.currentTimeMillis(),
                        intervalMs = REASONING_UPDATE_INTERVAL_MS
                    )
                    if (delayMs <= 0L) {
                        snapshot = collectReasoningSnapshotLocked()
                    } else {
                        scheduleReasoningSnapshotLocked(delayMs)
                    }
                }
                if (snapshot != null) {
                    dispatchReasoningSnapshot(snapshot)
                }
            }
        }

        fun emitContent() {
            val content = accumulator.currentContent()
            if (content.isEmpty() || content == lastContent) return
            synchronized(emissionLock) {
                var reasoningSnapshot: String? = null
                synchronized(reasoningLock) {
                    if (accumulator.currentReasoningLength() > 0) {
                        reasoningEmitJob?.cancel()
                        reasoningEmitJob = null
                        reasoningSnapshot = collectReasoningSnapshotLocked()
                    }
                }
                if (reasoningSnapshot != null) {
                    dispatchReasoningSnapshot(reasoningSnapshot)
                }
                lastContent = content
                if (onContentUpdate != null) {
                    enqueueEmission {
                        onContentUpdate.invoke(content)
                    }
                }
            }
        }

        fun emitToolInputs(force: Boolean = false) {
            val now = System.currentTimeMillis()
            // Same presentation cadence as reasoning, without limiting the input.
            if (!force && now - lastToolInputEmitAt < REASONING_UPDATE_INTERVAL_MS) return
            val calls = accumulator.currentToolCalls()
            if (calls.isEmpty()) return
            lastToolInputEmitAt = now
            emitReasoning(force = true)
            calls.forEach { call ->
                if (lastToolInputs.put(call.id, call) != call) {
                    enqueueEmission { onToolCallInput?.invoke(call) }
                }
            }
        }

        fun completeStream(eventSource: EventSource? = null) {
            if (!completed.compareAndSet(false, true)) return
            runCatching {
                val turn = accumulator.buildTurn().copy(
                    resolvedModel = routeInfo.resolvedModel,
                )
                enforceReasoningEchoIfRequired(turn, routeInfo)
                emitReasoning(force = true)
                emitContent()
                emitToolInputs(force = true)
                turn
            }.onSuccess { turn ->
                OmniLog.i(
                    tag,
                    "ACP provider timing stage=stream_completed " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "protocol=${routeInfo.protocolType}"
                )
                streamDone.complete(turn)
            }.onFailure { error ->
                streamDone.completeExceptionally(
                    if (error is AgentProviderStreamException) providerFailure(error) else error,
                )
            }
            eventSource?.cancel()
        }

        fun failIdleStream(timeoutMs: Long) {
            if (!completed.compareAndSet(false, true)) return
            val error = AgentStreamIdleTimeoutException(timeoutMs, accumulator.hasAssistantPayload())
            OmniLog.w(
                tag,
                "ACP provider timing stage=stream_idle_timeout " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            streamDone.completeExceptionally(error)
            eventSource?.cancel()
        }

        val idleWatchdog = effectiveStreamIdleTimeoutMs?.let { timeoutMs ->
            scope.launch {
                val checkIntervalMs = timeoutMs.coerceIn(1L, 1_000L)
                while (!completed.get()) {
                    delay(checkIntervalMs)
                    if (
                        System.currentTimeMillis() - lastStreamActivityAtMs.get() >=
                        timeoutMs.coerceAtLeast(1L)
                    ) {
                        failIdleStream(timeoutMs)
                        break
                    }
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                lastStreamActivityAtMs.set(System.currentTimeMillis())
                OmniLog.i(
                    tag,
                    "ACP provider timing stage=stream_open " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "protocol=${routeInfo.protocolType}"
                )
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (completed.get()) return
                lastStreamActivityAtMs.set(System.currentTimeMillis())
                runCatching {
                    if (!firstEventLogged) {
                        firstEventLogged = true
                        OmniLog.i(
                            tag,
                            "ACP provider timing stage=first_event " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "protocol=${routeInfo.protocolType}"
                        )
                    }
                    val done = accumulator.consume(data)
                    if (!firstReasoningLogged && accumulator.currentReasoningLength() > 0) {
                        firstReasoningLogged = true
                        OmniLog.i(
                            tag,
                            "ACP provider timing stage=first_reasoning " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "protocol=${routeInfo.protocolType}"
                        )
                    }
                    if (!firstContentLogged && accumulator.currentContent().isNotEmpty()) {
                        firstContentLogged = true
                        OmniLog.i(
                            tag,
                            "ACP provider timing stage=first_content " +
                                "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                                "protocol=${routeInfo.protocolType}"
                        )
                    }
                    emitReasoning()
                    emitContent()
                    emitToolInputs()
                    if (done) {
                        completeStream(eventSource)
                    }
                }.onFailure { error ->
                    if (completed.compareAndSet(false, true)) {
                        val failure = if (error is AgentProviderStreamException) {
                            providerFailure(error, data)
                        } else IllegalStateException(
                            "invalid chat completion stream chunk: ${error.message}", error,
                        )
                        streamDone.completeExceptionally(failure)
                        eventSource.cancel()
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (completed.get()) {
                    return
                }
                lastStreamActivityAtMs.set(System.currentTimeMillis())
                if (accumulator.canFinalizeOnClosed()) {
                    completeStream()
                    return
                }
                if (completed.compareAndSet(false, true)) {
                    streamDone.completeExceptionally(
                        AgentStreamRequestException(
                            statusCode = null,
                            reason = DEFAULT_CLOSED_STREAM_ERROR,
                            responseBody = null,
                            responseStarted = accumulator.hasAssistantPayload(),
                        )
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!completed.compareAndSet(false, true)) return
                lastStreamActivityAtMs.set(System.currentTimeMillis())
                OmniLog.w(
                    tag,
                    "ACP provider timing stage=stream_failed " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                        "status=${response?.code ?: "none"} " +
                        "protocol=${routeInfo.protocolType}"
                )
                val requiresResponsesStream = routeInfo.routeTag == ChatGptOAuthProvider.SOURCE_TYPE
                // A successful HTTP admission can still fail the SSE transport's
                // header/framing checks. Reading its live body here would wait
                // for generation to end and turn response.created into an error.
                val responseBody = if (requiresResponsesStream && response?.isSuccessful == true) {
                    null
                } else extractRawResponseBody(response, bounded = requiresResponsesStream)
                parseSuccessfulNonStreamingResponsesTurn(
                    statusCode = response?.code,
                    responseBody = responseBody,
                    routeInfo = routeInfo,
                )?.let { turn ->
                    streamDone.complete(turn)
                    return
                }
                val reason = extractErrorReason(responseBody)
                    ?: sanitizeReason(t?.message)
                    ?: "unknown stream failure"
                streamDone.completeExceptionally(
                    AgentStreamRequestException(
                        statusCode = response?.code,
                        reason = reason,
                        responseBody = responseBody,
                        responseStarted = accumulator.hasAssistantPayload(),
                        cause = t,
                        retryAfterMillis = parseAgentRetryAfterMillis(response?.header("Retry-After")),
                    )
                )
            }
        }

        try {
            eventSource = streamRequestOp?.invoke(
                model,
                json.encodeRequestToString(requestJson),
                listener,
                modelOverride?.apiBase,
                modelOverride?.apiKey,
                modelOverride?.customHeaders,
                explicitModel,
                modelOverride?.protocolType,
                modelOverride?.wireApi,
                forceHttp1
            ) ?: HttpController.postChatCompletionsStreamRequest(
                model = model,
                requestBody = requestJson,
                event = listener,
                explicitApiBase = modelOverride?.apiBase,
                explicitApiKey = modelOverride?.apiKey,
                explicitCustomHeaders = modelOverride?.customHeaders,
                explicitModel = explicitModel,
                explicitProtocolType = modelOverride?.protocolType,
                explicitWireApi = modelOverride?.wireApi,
                forceHttp1 = forceHttp1,
                explicitProviderProfileId = modelOverride?.oauthProfileId,
            )
            OmniLog.i(
                tag,
                "ACP provider timing stage=request_dispatched " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs} " +
                    "protocol=${routeInfo.protocolType}"
            )
            return streamDone.await()
        } finally {
            idleWatchdog?.cancel()
            reasoningEmitJob?.cancel()
            eventSource?.cancel()
            emissionQueue.close()
            runCatching { emissionJob.join() }
        }
    }

    private fun enforceReasoningEchoIfRequired(
        turn: ChatCompletionTurn,
        routeInfo: HttpController.ChatCompletionRouteInfo
    ) {
        if (!routeInfo.providerCapabilities.requiresReasoningContentForToolCalls) {
            return
        }
        if (turn.reasoning.isBlank()) {
            return
        }
        if (!turn.message.reasoningContent.isNullOrBlank()) {
            return
        }
        throw IllegalStateException(
            "assistant turn is missing reasoning_content for route=${routeInfo.resolvedModel} " +
                "protocol=${routeInfo.protocolType} despite non-empty reasoning output"
        )
    }


    private fun extractRawResponseBody(response: Response?, bounded: Boolean): String? {
        val body = runCatching {
            if (bounded) response?.peekBody(64L * 1024)?.string() else response?.body?.string()
        }.getOrNull()?.trim().orEmpty()
        return body.takeIf { it.isNotEmpty() }
    }

    private fun parseSuccessfulNonStreamingResponsesTurn(
        statusCode: Int?,
        responseBody: String?,
        routeInfo: HttpController.ChatCompletionRouteInfo,
    ): ChatCompletionTurn? {
        if (
            statusCode == null ||
            statusCode !in 200..299 ||
            routeInfo.routeTag == ChatGptOAuthProvider.SOURCE_TYPE ||
            !OpenAiWireApi.isResponses(routeInfo.wireApi)
        ) return null
        val parsed = HttpController.parseOpenAiResponsesBody(responseBody)
        if (!parsed.success) return null
        val content = parsed.content.takeIf(String::isNotBlank)?.let(::JsonPrimitive)
        val turn = ChatCompletionTurn(
            message = ChatCompletionMessage(
                role = "assistant",
                content = content,
                toolCalls = parsed.toolCalls.takeIf { it.isNotEmpty() },
            ),
            reasoning = parsed.reasoning,
            finishReason = parsed.finishReason,
            usage = parseResponsesUsage(responseBody),
            resolvedModel = routeInfo.resolvedModel,
        )
        enforceReasoningEchoIfRequired(turn, routeInfo)
        return turn
    }

    private fun parseResponsesUsage(responseBody: String?): ChatCompletionUsage? {
        val usage = runCatching {
            json.parseToJsonElement(responseBody.orEmpty()).jsonObject["usage"]?.jsonObject
        }.getOrNull() ?: return null
        return ChatCompletionUsage(
            promptTokens = usage.tokenCount("input_tokens", "prompt_tokens"),
            completionTokens = usage.tokenCount("output_tokens", "completion_tokens"),
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }

    private fun JsonObject.tokenCount(vararg names: String): Int? = names.firstNotNullOfOrNull {
        get(it)?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }

    private fun extractErrorReason(responseBody: String?): String? {
        val raw = responseBody?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // SSE framing is diagnostic transport data, never a provider error
        // message. Preserve the actual transport exception instead.
        if (Regex("^(?:\\uFEFF)?(?:event:|data:|id:|retry:|:)").containsMatchIn(raw)) return null
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return sanitizeReason(raw)
        val errorObj = parsed["error"] as? JsonObject
        val formalErrorCode = extractJsonText(errorObj?.get("code"))
            ?: extractJsonText(parsed["code"])

        val candidates = listOf(
            extractJsonText(errorObj?.get("message")),
            extractJsonText(errorObj?.get("detail")),
            extractJsonText(parsed["message"]),
            extractJsonText(parsed["detail"]),
            extractJsonText(parsed["error_description"]),
            extractJsonText(parsed["error"])
        )
        return candidates.firstOrNull { !it.isNullOrBlank() } ?: sanitizeReason(raw)
    }

    private fun extractJsonText(element: JsonElement?): String? {
        return when (element) {
            null -> null
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> {
                extractJsonText(element["message"])
                    ?: extractJsonText(element["detail"])
                    ?: extractJsonText(element["code"])
            }

            else -> sanitizeReason(element.toString())
        }
    }

    private fun sanitizeReason(raw: String?, maxLen: Int = 240): String? {
        val normalized = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return if (normalized.length <= maxLen) normalized else "${normalized.take(maxLen)}..."
    }


    private fun isModelNotSupported(error: AgentStreamRequestException): Boolean {
        val code = error.statusCode
        if (code != 400 && code != 404) return false
        val haystack = buildString {
            append(error.reason)
            append(' ')
            append(error.responseBody.orEmpty())
        }.lowercase()
        if (!haystack.contains("model")) return false
        return haystack.contains("not supported") ||
            haystack.contains("unsupported model") ||
            haystack.contains("model_not_supported") ||
            haystack.contains("invalid model") ||
            haystack.contains("unknown model") ||
            haystack.contains("model does not exist") ||
            haystack.contains("no such model") ||
            haystack.contains("not found")
    }
}

/** Standard Retry-After seconds or HTTP date; large valid waits remain large. */
internal fun parseAgentRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.all(Char::isDigit)) {
        val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
        return if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
    }
    return runCatching {
        (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis)
            .coerceAtLeast(0L)
    }.getOrNull()
}
