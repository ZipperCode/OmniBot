package cn.com.omnimind.baselib.llm.chatgpt

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ChatGptRegistrationCodecTest {
    @Test fun persistedFieldNamesStayCanonicalEvenWithRuntimeFieldRenaming() {
        val registration = connectedRegistration()
        val renamedFieldsGson = GsonBuilder()
            .setFieldNamingStrategy { field -> "obfuscated_${field.name}" }
            .create()
        val json = JsonParser.parseString(renamedFieldsGson.toJson(registration)).asJsonObject
        assertEquals(CANONICAL_FIELDS, json.keySet())
        val encoded = ChatGptRegistrationCodec.encode(registration)
        assertEquals(json, JsonParser.parseString(encoded))
        val decoded = ChatGptRegistrationCodec.decode(encoded)
        assertEquals(registration, decoded.registration)
        assertFalse(decoded.needsRewrite)
    }

    @Test fun readsKnownReleasePartialRegistrationAndRewritesOnlyCanonicalKeys() {
        // First callback persists this record before token exchange. Gson omits nullable fields.
        val decoded = ChatGptRegistrationCodec.decode("""{"a":"issued","h":0,"i":0,"j":[],"k":0}""")
        assertEquals(ChatGptRegistration("issued"), decoded.registration)
        assertTrue(decoded.needsRewrite)
        val encoded = ChatGptRegistrationCodec.encode(decoded.registration)
        val json = JsonParser.parseString(encoded).asJsonObject
        assertEquals(setOf("clientId", "expiresAtSeconds", "earliestRefreshAtSeconds", "scopes", "accountRevision"), json.keySet())
        assertFalse(json.has("a"))
        assertFalse(json.has("j"))
        assertFalse(ChatGptRegistrationCodec.decode(encoded).needsRewrite)
    }

    @Test fun readsKnownReleaseConnectedRecordWithoutChangingIdentityOrTokens() {
        val decoded = ChatGptRegistrationCodec.decode(LEGACY_CONNECTED)
        assertTrue(decoded.needsRewrite)
        assertEquals(connectedRegistration(), decoded.registration)
        val canonical = ChatGptRegistrationCodec.encode(decoded.registration)
        assertEquals(connectedRegistration(), ChatGptRegistrationCodec.decode(canonical).registration)
        assertEquals(CANONICAL_FIELDS, JsonParser.parseString(canonical).asJsonObject.keySet())
    }

    @Test fun signedOutLegacyRecordKeepsRegistrationAndIdentityWithoutInventingTokens() {
        val decoded = ChatGptRegistrationCodec.decode(
            """{"a":"issued","b":"subject","c":"me@example.com","h":0,"i":0,"j":[],"k":123}"""
        ).registration
        assertEquals("issued", decoded.clientId)
        assertEquals("subject", decoded.subject)
        assertEquals("me@example.com", decoded.email)
        assertNull(decoded.accessToken)
        assertNull(decoded.refreshToken)
        assertFalse(decoded.hasTokens)
    }

    @Test fun validConnectedRecordMayOmitOfflineAccessRefreshToken() {
        val registration = connectedRegistration().copy(refreshToken = null)
        val encoded = ChatGptRegistrationCodec.encode(registration)
        assertFalse(JsonParser.parseString(encoded).asJsonObject.has("refreshToken"))
        assertEquals(registration, ChatGptRegistrationCodec.decode(encoded).registration)
    }

    @Test fun mixedOrUnknownMappingsFailClosedRatherThanGuessingFields() {
        val mixed = JsonParser.parseString(LEGACY_CONNECTED).asJsonObject.apply {
            addProperty("clientId", "another-client")
        }
        assertInvalid(mixed.toString())
        val unknown = JsonParser.parseString(LEGACY_CONNECTED).asJsonObject.apply {
            addProperty("l", "unknown-field")
        }
        assertInvalid(unknown.toString())
        val canonicalUnknown = JsonParser.parseString(ChatGptRegistrationCodec.encode(connectedRegistration())).asJsonObject.apply {
            addProperty("unknownField", "unknown-value")
        }
        assertInvalid(canonicalUnknown.toString())
    }

    @Test fun malformedShapeAndInconsistentCredentialsFailWithoutLeakingTheirContent() {
        val original = JsonParser.parseString(LEGACY_CONNECTED).asJsonObject
        listOf("a", "h", "i", "j", "k", "b", "f", "g").forEach { missing ->
            assertInvalid(original.deepCopy().apply { remove(missing) }.toString())
        }
        assertInvalid(original.deepCopy().apply { addProperty("e", 123) }.toString())
        assertInvalid(original.deepCopy().apply { addProperty("h", 123.5) }.toString())
        assertInvalid(original.deepCopy().apply { addProperty("k", -1) }.toString())
        assertInvalid(original.deepCopy().apply { addProperty("j", "openid") }.toString())
        assertInvalid(original.deepCopy().apply { remove("d") }.toString())
        assertInvalid(original.deepCopy().apply { addProperty("d", "secret with whitespace") }.toString())
        assertInvalid("""{"a":"dynamic_agent_client","h":0,"i":0,"j":[],"k":0}""")
        assertInvalid("{not-json: secret-access}")
    }

    private fun assertInvalid(raw: String) {
        val error = assertThrows(ChatGptOAuthException::class.java) { ChatGptRegistrationCodec.decode(raw) }
        assertEquals("secure_storage_invalid_format", error.code)
        assertFalse(error.message!!.contains("secret"))
        assertFalse(error.message!!.contains("me@example.com"))
        assertNull(error.cause)
    }

    private fun connectedRegistration() = ChatGptRegistration(
        "issued", "subject", "me@example.com", "secret-access", "secret-refresh", "secret-id",
        "nonce", 1_800_003_600, 1_800_000_000, setOf("openid", "resource.invoke", "chatgpt.tokens.use.direct"),
        1_800_000_000_000,
    )

    companion object {
        private val CANONICAL_FIELDS = setOf(
            "clientId", "subject", "email", "accessToken", "refreshToken", "idToken", "loginNonce",
            "expiresAtSeconds", "earliestRefreshAtSeconds", "scopes", "accountRevision",
        )
        private const val LEGACY_CONNECTED = """{
            "a":"issued", "b":"subject", "c":"me@example.com", "d":"secret-access",
            "e":"secret-refresh", "f":"secret-id", "g":"nonce", "h":1800003600,
            "i":1800000000, "j":["openid","resource.invoke","chatgpt.tokens.use.direct"],
            "k":1800000000000
        }"""
    }
}
