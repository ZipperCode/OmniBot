package cn.com.omnimind.baselib.llm.chatgpt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey

class ChatGptIdTokenValidatorTest {
    @Test fun verifiesSignatureAndAccountIdentity() {
        val identity = validate(sign(claims()))
        assertEquals("subject", identity.subject)
        assertEquals("me@example.com", identity.email)
    }

    @Test fun rejectsForgedSignatureAndAlgorithmConfusion() {
        val token = sign(claims())
        assertInvalid(token.substringBeforeLast('.') + "." + ByteArray(256).toByteString().base64Url())
        assertInvalid(sign(claims(), JsonObject().apply { addProperty("alg", "none"); addProperty("kid", "key") }))
        assertInvalid(sign(claims(), JsonObject().apply { addProperty("alg", "HS256"); addProperty("kid", "key") }))
    }

    @Test fun rejectsWrongIssuerAudienceNonceSubjectAndExpiredIdentity() {
        listOf("iss" to "https://attacker.example", "aud" to "other-client", "nonce" to "other-nonce", "sub" to "other-subject")
            .forEach { (key, value) -> assertInvalid(sign(claims().apply { addProperty(key, value) })) }
        assertInvalid(sign(claims().apply { addProperty("exp", NOW) }))
        assertInvalid(sign(claims().apply { remove("nonce") }))
        assertInvalid(sign(claims().apply { addProperty("nbf", NOW + 120) }))
    }

    @Test fun audienceArrayRequiresMatchingAuthorizedParty() {
        val claims = claims().apply { add("aud", JsonArray().apply { add("client"); add("another") }) }
        assertInvalid(sign(claims))
        claims.addProperty("azp", "client")
        assertEquals("subject", validate(sign(claims)).subject)
        claims.addProperty("azp", "another")
        assertInvalid(sign(claims))
    }

    @Test fun refreshMayOmitNonceButCannotReplaceIt() {
        val withoutNonce = claims().apply { remove("nonce") }
        assertEquals("subject", validate(sign(withoutNonce), requireNonce = false).subject)
        assertThrows(ChatGptOAuthException::class.java) {
            validate(sign(claims().apply { addProperty("nonce", "another") }), requireNonce = false)
        }
    }

    private fun validate(token: String, requireNonce: Boolean = true) = ChatGptIdTokenValidator.validate(
        token, jwks, "client", "nonce", "subject", NOW, requireNonce,
    )

    private fun assertInvalid(token: String) {
        val error = assertThrows(ChatGptOAuthException::class.java) { validate(token) }
        assertEquals("invalid_identity", error.code)
        assertFalse(error.message!!.contains(token))
    }

    private fun claims() = JsonObject().apply {
        addProperty("iss", ChatGptOAuthProtocol.ISSUER); addProperty("aud", "client")
        addProperty("sub", "subject"); addProperty("email", "me@example.com")
        addProperty("nonce", "nonce"); addProperty("exp", NOW + 3600); addProperty("iat", NOW)
    }

    private fun sign(claims: JsonObject, header: JsonObject = JsonObject().apply {
        addProperty("alg", "RS256"); addProperty("kid", "key")
    }): String {
        fun encoded(json: JsonObject) = json.toString().toByteArray().toByteString().base64Url().trimEnd('=')
        val unsigned = "${encoded(header)}.${encoded(claims)}"
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private); update(unsigned.toByteArray(Charsets.US_ASCII))
        }.sign().toByteString().base64Url().trimEnd('=')
        return "$unsigned.$signature"
    }

    companion object {
        private const val NOW = 1_800_000_000L
        private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val jwks = JsonObject().apply {
            val key = keyPair.public as RSAPublicKey
            add("keys", JsonArray().apply { add(JsonObject().apply {
                addProperty("kid", "key"); addProperty("kty", "RSA"); addProperty("alg", "RS256")
                addProperty("use", "sig")
                addProperty("n", key.modulus.toByteArray().toByteString().base64Url())
                addProperty("e", key.publicExponent.toByteArray().toByteString().base64Url())
            }) })
        }
    }
}
