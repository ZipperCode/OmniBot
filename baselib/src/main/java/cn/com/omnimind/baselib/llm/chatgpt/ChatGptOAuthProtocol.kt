package cn.com.omnimind.baselib.llm.chatgpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** Deliberately contains only application-controlled messages, never an OAuth response body. */
class ChatGptOAuthException(
    val code: String,
    message: String,
) : IllegalStateException(message)

internal object ChatGptOAuthProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE_URL = "$ISSUER/api/accounts/authorize"
    const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"

    private val random = SecureRandom()

    fun randomSecret(): String = ByteArray(32).also(random::nextBytes).toByteString().base64Url()
        .trimEnd('=')

    fun challenge(verifier: String): String = verifier.toByteArray(Charsets.US_ASCII)
        .toByteString().sha256().base64Url().trimEnd('=')

    fun authorizationUrl(
        registration: ChatGptRegistration?,
        hostId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        verifier: String,
    ): String {
        val clientId = registration?.clientId ?: DYNAMIC_CLIENT_ID
        return AUTHORIZE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge(verifier))
            .apply {
                if (registration == null) addQueryParameter("agent_name_hint", "OmniBot")
                registration?.email?.takeIf(String::isNotBlank)?.let {
                    addQueryParameter("login_hint", it)
                }
                // Omit optional id_token_hint: the browser URL crosses the Flutter boundary.
                // Re-consent is requested only after a validated login declined plan usage.
                if (registration?.hasTokens == true && !registration.planEnabled) {
                    addQueryParameter("prompt", "consent")
                }
            }.build().toString()
    }

    fun parseCallback(target: String, redirectUri: String): Map<String, String> {
        require(target.startsWith("$CALLBACK_PATH?")) { "Invalid callback path" }
        val redirect = redirectUri.toHttpUrl()
        val url = "${redirect.scheme}://${redirect.host}:${redirect.port}$target".toHttpUrl()
        require(url.encodedPath == CALLBACK_PATH && url.fragment == null) { "Invalid callback path" }
        val fields = listOf("state", "code", "client_id", "error")
        require(fields.none { url.queryParameterValues(it).size > 1 }) { "Ambiguous callback" }
        return fields.mapNotNull { name -> url.queryParameter(name)?.let { name to it } }.toMap()
    }

    fun stateMatches(expected: String, actual: String?): Boolean = actual != null &&
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

    fun issuedClientId(existing: String?, callback: Map<String, String>): String {
        val supplied = callback["client_id"]
        if (existing != null && supplied != null && supplied != existing) {
            throw ChatGptOAuthException("account_mismatch", "ChatGPT returned a different account registration. Please try again.")
        }
        return (existing ?: supplied)?.takeIf {
            it.isNotBlank() && it != DYNAMIC_CLIENT_ID && it.length <= 512
        } ?: throw ChatGptOAuthException("registration_incomplete", "ChatGPT registration was incomplete. Please sign in again.")
    }
}

internal data class ChatGptIdentity(val subject: String, val email: String?)

/** RS256 is the only ID-token signing algorithm currently advertised by OpenAI discovery. */
internal object ChatGptIdTokenValidator {
    fun validate(
        token: String,
        jwks: JsonObject,
        clientId: String,
        expectedNonce: String?,
        expectedSubject: String?,
        nowSeconds: Long,
        requireNonce: Boolean = true,
    ): ChatGptIdentity = try {
        require(token.length <= 64 * 1024)
        val parts = token.split('.')
        require(parts.size == 3)
        val header = jsonPart(parts[0])
        require(header.string("alg") == "RS256")
        require(!header.has("crit"))
        val kid = requireNotNull(header.string("kid"))
        val matchingKeys = jwks.getAsJsonArray("keys").filter { entry ->
            entry.isJsonObject && entry.asJsonObject.string("kid") == kid
        }
        require(matchingKeys.size == 1)
        val key = matchingKeys.single().asJsonObject
        require(key.string("kty") == "RSA")
        require(key.string("use").let { it == null || it == "sig" })
        require(key.string("alg").let { it == null || it == "RS256" })
        if (key.has("key_ops")) require(key.getAsJsonArray("key_ops").any { it.asString == "verify" })
        val modulus = BigInteger(1, decode(requireNotNull(key.string("n"))))
        require(modulus.bitLength() >= 2048)
        val exponent = BigInteger(1, decode(requireNotNull(key.string("e"))))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decode(parts[2])))

        val claims = jsonPart(parts[1])
        require(claims.string("iss") == ChatGptOAuthProtocol.ISSUER)
        val aud = claims.get("aud")
        val audiences = when {
            aud?.isJsonArray == true -> aud.asJsonArray.map { it.asString }
            aud?.isJsonPrimitive == true -> listOf(aud.asString)
            else -> emptyList()
        }
        require(clientId in audiences)
        val authorizedParty = claims.string("azp")
        require(authorizedParty == null || authorizedParty == clientId)
        if (audiences.size > 1) require(authorizedParty == clientId)
        require(claims.get("exp")?.asLong?.let { it > nowSeconds } == true)
        require(claims.get("nbf")?.asLong?.let { it <= nowSeconds + 60 } != false)
        require(claims.get("iat")?.asLong?.let { it <= nowSeconds + 60 } == true)
        val nonce = claims.string("nonce")
        if (requireNonce || nonce != null) {
            require(expectedNonce != null && ChatGptOAuthProtocol.stateMatches(expectedNonce, nonce))
        }
        val subject = requireNotNull(claims.string("sub")).also { require(it.isNotBlank()) }
        require(expectedSubject == null || subject == expectedSubject)
        ChatGptIdentity(subject, claims.string("email"))
    } catch (_: Exception) {
        throw ChatGptOAuthException("invalid_identity", "ChatGPT identity could not be verified. Please sign in again.")
    }

    private fun jsonPart(part: String): JsonObject = JsonParser.parseString(
        decode(part).toString(Charsets.UTF_8)
    ).asJsonObject

    private fun decode(value: String): ByteArray = requireNotNull(value.decodeBase64()).toByteArray()
}

internal fun JsonObject.string(name: String): String? = get(name)?.takeIf {
    it.isJsonPrimitive && it.asJsonPrimitive.isString
}?.asString
