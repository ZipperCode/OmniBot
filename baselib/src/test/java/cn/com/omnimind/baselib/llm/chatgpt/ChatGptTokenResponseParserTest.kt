package cn.com.omnimind.baselib.llm.chatgpt

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ChatGptTokenResponseParserTest {
    @Test fun codeExchangeWithoutOfflineAccessRetainsIdentityAndExactGrantedScopes() {
        val parsed = ChatGptTokenResponseParser.parse(response(), false, NOW)
        assertNull(parsed.refreshToken)
        assertEquals("signed-identity", parsed.idToken)
        assertEquals(setOf("openid", "email"), parsed.scopes)
        assertEquals(NOW, parsed.receivedAtSeconds)
    }

    @Test fun refreshRequiresReplacementRatherThanSilentlyReusingRotatedToken() {
        val error = assertThrows(ChatGptOAuthException::class.java) {
            ChatGptTokenResponseParser.parse(response(), true, NOW)
        }
        assertEquals("invalid_response", error.code)
        assertFalse(error.message!!.contains("secret-access"))
        val parsed = ChatGptTokenResponseParser.parse(response().apply {
            addProperty("refresh_token", "replacement-refresh")
        }, true, NOW)
        assertEquals("replacement-refresh", parsed.refreshToken)
    }

    @Test fun omittedScopeDoesNotInventRequestedPermissions() {
        val parsed = ChatGptTokenResponseParser.parse(response().apply { remove("scope") }, false, NOW)
        assertNull(parsed.scopes)
    }

    @Test fun optionalTokenMustBeValidWhenPresent() {
        listOf("", "token with spaces", "secret\nheader").forEach { invalid ->
            assertThrows(ChatGptOAuthException::class.java) {
                ChatGptTokenResponseParser.parse(response().apply { addProperty("refresh_token", invalid) }, false, NOW)
            }
        }
        assertThrows(ChatGptOAuthException::class.java) {
            ChatGptTokenResponseParser.parse(response().apply { addProperty("refresh_token", 123) }, false, NOW)
        }
    }

    private fun response() = JsonObject().apply {
        addProperty("token_type", "Bearer")
        addProperty("access_token", "secret-access")
        addProperty("id_token", "signed-identity")
        addProperty("expires_in", 3600)
        addProperty("scope", "openid email")
    }

    companion object { private const val NOW = 1_800_000_000L }
}
