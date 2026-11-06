package cn.com.omnimind.baselib.llm.chatgpt

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ChatGptOAuthProtocolTest {
    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptOAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertTrue(ChatGptOAuthProtocol.randomSecret().matches(Regex("[A-Za-z0-9_-]{43}")))
    }

    @Test fun firstRegistrationUsesDynamicClientAndExactLoopbackUri() {
        val url = authorization(null)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("OmniBot", url.queryParameter("agent_name_hint"))
        assertEquals("urn:uuid:host", url.queryParameter("ext_agent_host_id"))
        assertEquals(REDIRECT, url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertTrue(url.queryParameter("scope")!!.split(' ').contains("chatgpt.tokens.use.direct"))
    }

    @Test fun returningRegistrationKeepsClientAndDoesNotExposeTokensToFlutter() {
        val url = authorization(ChatGptRegistration("issued", "subject", "me@example.com",
            accessToken = "secret-access", idToken = "secret-id", refreshToken = "secret-refresh"))
        assertEquals("issued", url.queryParameter("client_id"))
        assertEquals("me@example.com", url.queryParameter("login_hint"))
        assertNull(url.queryParameter("agent_name_hint"))
        assertNull(url.queryParameter("id_token_hint"))
        assertFalse(url.toString().contains("secret"))
        assertEquals("consent", url.queryParameter("prompt"))
    }

    @Test fun callbackRejectsDuplicateFieldsWrongPathAndClientReplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptOAuthProtocol.parseCallback("/auth/callback?state=a&state=b&code=one", REDIRECT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatGptOAuthProtocol.parseCallback("/callback?state=a&code=one", REDIRECT)
        }
        assertThrows(ChatGptOAuthException::class.java) {
            ChatGptOAuthProtocol.issuedClientId("original", mapOf("client_id" to "another"))
        }
        assertThrows(ChatGptOAuthException::class.java) {
            ChatGptOAuthProtocol.issuedClientId(null, mapOf("client_id" to "dynamic_agent_client"))
        }
        assertThrows(ChatGptOAuthException::class.java) {
            ChatGptOAuthProtocol.issuedClientId(null, emptyMap())
        }
        assertEquals("original", ChatGptOAuthProtocol.issuedClientId("original", emptyMap()))
    }

    @Test fun callbackDecodesValuesAndRequiresMatchingState() {
        val callback = ChatGptOAuthProtocol.parseCallback("/auth/callback?state=opaque%2Bstate&code=a%2Fb&client_id=issued", REDIRECT)
        assertEquals("a/b", callback["code"])
        assertTrue(ChatGptOAuthProtocol.stateMatches("opaque+state", callback["state"]))
        assertFalse(ChatGptOAuthProtocol.stateMatches("different", callback["state"]))
        assertFalse(ChatGptOAuthProtocol.stateMatches("expected", null))
    }

    private fun authorization(registration: ChatGptRegistration?) = ChatGptOAuthProtocol.authorizationUrl(
        registration, "urn:uuid:host", REDIRECT, "state", "nonce", "verifier",
    ).toHttpUrl()

    companion object { private const val REDIRECT = "http://127.0.0.1:54321/auth/callback" }
}
