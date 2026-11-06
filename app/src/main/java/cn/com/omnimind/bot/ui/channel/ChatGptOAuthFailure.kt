package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthException

/** Explicit diagnostic vocabulary: arbitrary exception strings never cross the channel. */
internal data class ChatGptAuthFailure(val phase: String, val errorCode: String) {
    fun toMap(): Map<String, String> = mapOf("phase" to phase, "errorCode" to errorCode)
}

internal fun chatGptAuthFailure(method: String, error: Exception): ChatGptAuthFailure {
    val phase = when (method) {
        "startChatGptLogin" -> "start_login"
        "getChatGptAccount" -> "read_account"
        "getChatGptLoginStatus" -> "login_status"
        "cancelChatGptLogin" -> "cancel_login"
        "logoutChatGpt" -> "logout"
        else -> "account_operation"
    }
    val code = (error as? ChatGptOAuthException)?.code?.takeIf { it in CHATGPT_SAFE_ERROR_CODES }
        ?: "internal_error"
    return ChatGptAuthFailure(phase, code)
}

private val CHATGPT_SAFE_ERROR_CODES = setOf(
    "secure_storage_unavailable", "secure_storage_invalid_format", "not_initialized",
    "callback_unavailable", "callback_failed", "invalid_profile", "invalid_attempt",
    "too_many_attempts", "network_error", "invalid_state", "authorization_declined",
    "account_mismatch", "registration_incomplete", "missing_code", "invalid_identity",
    "invalid_client", "invalid_response", "oauth_request_failed", "login_failed",
    "login_timeout", "login_cancelled", "sign_in_required", "plan_disabled", "refresh_not_ready",
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused",
)
