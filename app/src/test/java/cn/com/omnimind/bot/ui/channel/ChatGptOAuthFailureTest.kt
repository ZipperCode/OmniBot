package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthException
import org.junit.Assert.*
import org.junit.Test

class ChatGptOAuthFailureTest {
    @Test fun storageErrorKeepsSafeCodeAndStageWithoutExceptionPayload() {
        val result = chatGptAuthFailure("startChatGptLogin", ChatGptOAuthException(
            "secure_storage_invalid_format", "access_token=private",
        )).toMap()
        assertEquals(mapOf("phase" to "start_login", "errorCode" to "secure_storage_invalid_format"), result)
        assertFalse(result.toString().contains("private"))
    }

    @Test fun unrecognizedExceptionNeverExposesItsMessageOrCause() {
        val result = chatGptAuthFailure("getChatGptAccount", IllegalStateException(
            "https://auth.openai.com/callback?code=private", RuntimeException("refresh_token=private"),
        )).toMap()
        assertEquals(mapOf("phase" to "read_account", "errorCode" to "internal_error"), result)
    }

    @Test fun evenStructuredErrorCodesMustBeAllowlisted() {
        val result = chatGptAuthFailure("unknown-private-method", ChatGptOAuthException("private", "private"))
        assertEquals(ChatGptAuthFailure("account_operation", "internal_error"), result)
    }

    @Test fun loopbackFailureIsDistinctFromNetworkFailure() {
        assertEquals("callback_unavailable", chatGptAuthFailure("startChatGptLogin",
            ChatGptOAuthException("callback_unavailable", "safe")).errorCode)
        assertEquals("network_error", chatGptAuthFailure("getChatGptLoginStatus",
            ChatGptOAuthException("network_error", "safe")).errorCode)
    }
}
