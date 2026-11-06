package cn.com.omnimind.bot.ui.channel

import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthManager
import cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthException
import cn.com.omnimind.baselib.util.OmniLog
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings-only authentication bridge. Never exposes OAuth credentials. */
internal class ChatGptOAuthChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        if (call.method !in METHODS) return false
        scope.launch {
            try {
                val response: Any? = when (call.method) {
                    // Serialize admission with profile deletion/conversion. The
                    // coordinator then owns cancellation of any late callback.
                    "startChatGptLogin" -> synchronized(ModelProviderConfigStore) {
                        ChatGptOAuthManager.startLogin(profileId(call))
                    }
                    "getChatGptAccount" -> ChatGptOAuthManager.account(profileId(call))
                    "logoutChatGpt" -> ChatGptOAuthManager.logout(profileId(call))
                    "getChatGptLoginStatus" -> ChatGptOAuthManager.loginStatus(attemptId(call))
                    "cancelChatGptLogin" -> {
                        ChatGptOAuthManager.cancelLogin(attemptId(call))
                        true
                    }
                    else -> error("Unsupported ChatGPT account operation")
                }
                withContext(Dispatchers.Main) { result.success(response) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = chatGptAuthFailure(call.method, error)
                // Never log the Throwable or its message: IO/auth exceptions may
                // contain callback codes, credential payloads or account details.
                OmniLog.e("ChatGptOAuth", "phase=${failure.phase} code=${failure.errorCode}")
                withContext(Dispatchers.Main) {
                    result.error("CHATGPT_AUTH_ERROR", "ChatGPT account operation failed.", failure.toMap())
                }
            }
        }
        return true
    }

    private fun profileId(call: MethodCall): String {
        val id = call.argument<String>("profileId")?.trim().orEmpty()
        val profile = ModelProviderConfigStore.getProfile(id)
        if (profile == null || !ChatGptOAuthProvider.isOAuth(profile.sourceType)) {
            throw ChatGptOAuthException("invalid_profile", "Select a ChatGPT OAuth provider first.")
        }
        return id
    }

    private fun attemptId(call: MethodCall): String =
        call.argument<String>("attemptId")?.takeIf(String::isNotBlank)
            ?: throw ChatGptOAuthException("invalid_attempt", "Missing sign-in attempt.")

    private companion object {
        val METHODS = setOf(
            "startChatGptLogin", "getChatGptLoginStatus", "cancelChatGptLogin",
            "getChatGptAccount", "logoutChatGpt",
        )
    }
}
