package cn.com.omnimind.bot.agent

import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class HttpAgentResponsesFailureTest {
    private val request = Request.Builder().url("https://api.openai.com/v1/responses").build()
    private val prompt = ChatCompletionRequest(model = "fixture", stream = true,
        messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("hello"))))

    private fun response(code: Int, body: ResponseBody) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body).build()

    private suspend fun failure(response: Response, oauth: Boolean = true): AgentStreamRequestException {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        var cancelled = false
        try {
            val client = HttpAgentLlmClient(
                scope = scope, maxTransientStreamRetries = 2,
                modelOverride = AgentModelOverride("fixture", modelId = "fixture",
                    apiBase = "https://api.openai.com/v1", apiKey = "", wireApi = OpenAiWireApi.RESPONSES),
                resolveRouteInfoOp = { _, _, _, _, _, _, _ ->
                    HttpController.ChatCompletionRouteInfo(
                        requestedModel = "fixture", resolvedModel = "fixture", apiBase = null,
                        providerProfileId = "fixture", providerProfileName = "fixture",
                        routeTag = if (oauth) ChatGptOAuthProvider.SOURCE_TYPE else "custom_openai_compat",
                        bindingApplied = false, bindingProfileMissing = false, overrideApplied = true,
                        wireApi = OpenAiWireApi.RESPONSES,
                    )
                },
                streamRequestOp = { _, _, listener, _, _, _, _, _, _, _ ->
                    calls++
                    val source = object : EventSource {
                        override fun request() = request
                        override fun cancel() { cancelled = true }
                    }
                    listener.onFailure(source, IllegalStateException("Invalid content-type: application/json"), response)
                    source
                },
            )
            val error = runCatching { withTimeout(2000) { client.streamTurn(prompt) } }.exceptionOrNull()
            assertTrue(error is AgentStreamRequestException)
            assertEquals(1, calls)
            assertTrue(cancelled)
            return error as AgentStreamRequestException
        } finally { scope.cancel() }
    }

    @Test fun oauthAdmissionFailureDoesNotDrainALiveSuccessfulBody() = runBlocking {
        var reads = 0
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource {
                reads++
                error("Failure diagnostics must not read a live SSE body")
            }
        }
        val error = failure(response(200, body))
        assertEquals(0, reads)
        assertEquals(200, error.statusCode)
        assertEquals("Invalid content-type: application/json", error.reason)
        assertNull(error.responseBody)
    }

    @Test fun oauthDoesNotAcceptJsonCompletionInPlaceOfTheRequiredSseTerminalEvent() = runBlocking {
        val error = failure(response(200,
            """{"object":"response","status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}"""
                .toResponseBody("application/json".toMediaType())))
        assertEquals("Invalid content-type: application/json", error.reason)
        assertNull(error.responseBody)
    }

    @Test fun rawSseNeverOverridesTheActualTransportFailure() = runBlocking {
        val error = failure(response(200,
            "event: response.created\ndata: {\"type\":\"response.created\"}\n\n"
                .toResponseBody("application/json".toMediaType())), oauth = false)
        assertEquals("Invalid content-type: application/json", error.reason)
        assertFalse(error.reason.contains("response.created"))
    }

    @Test fun structuredProviderErrorRemainsAvailableToTheExistingErrorPolicy() = runBlocking {
        val body = """{"error":{"code":"subscription_sharing_user_not_eligible","message":"Plan access is unavailable"}}"""
        val error = failure(response(403, body.toResponseBody("application/json".toMediaType())))
        assertEquals(403, error.statusCode)
        assertEquals("Plan access is unavailable", error.reason)
        assertTrue(error.responseBody!!.contains("subscription_sharing_user_not_eligible"))
    }
}
