package cn.com.omnimind.baselib.llm.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Stable encrypted-record format, independent of Kotlin/R8 field names. */
internal object ChatGptRegistrationCodec {
    const val INVALID_FORMAT_CODE = "secure_storage_invalid_format"
    private val gson = Gson()

    // Verified against the shipped developStandardRelease 0.6.3.3 mapping (2026-10-17):
    // mapping.txt SHA-256 ac884c7520d572e4d15fbb2a9c9dcc1af7165c7a78dac1d43d6361bf788d906f.
    // This is a single known migration, not a guess based on field types or positions.
    private val legacyFields = linkedMapOf(
        "a" to "clientId", "b" to "subject", "c" to "email", "d" to "accessToken",
        "e" to "refreshToken", "f" to "idToken", "g" to "loginNonce",
        "h" to "expiresAtSeconds", "i" to "earliestRefreshAtSeconds",
        "j" to "scopes", "k" to "accountRevision",
    )
    private val canonicalFields = legacyFields.values.toSet()
    private val numericFields = setOf("expiresAtSeconds", "earliestRefreshAtSeconds", "accountRevision")
    private val optionalStrings = setOf("subject", "email", "accessToken", "refreshToken", "idToken", "loginNonce")

    internal data class Decoded(val registration: ChatGptRegistration, val needsRewrite: Boolean)

    fun encode(registration: ChatGptRegistration): String {
        val raw = gson.toJson(registration)
        // Validate before writing so a broken future serializer cannot poison the encrypted file.
        val decoded = decode(raw)
        if (decoded.needsRewrite || decoded.registration != registration) throw invalidFormat()
        return raw
    }

    fun decode(raw: String): Decoded = try {
        require(raw.length <= 256 * 1024)
        val input = JsonParser.parseString(raw).asJsonObject
        val names = input.keySet()
        val isLegacy = names.any(legacyFields::containsKey)
        val json = if (isLegacy) {
            // Reject mixed/unknown records instead of silently combining mismatched identities.
            require(names.all(legacyFields::containsKey))
            JsonObject().apply {
                input.entrySet().forEach { (name, value) -> add(legacyFields.getValue(name), value) }
            }
        } else {
            require(names.all(canonicalFields::contains))
            input
        }
        val clientId = requireNotNull(json.string("clientId"))
        require(clientId.isNotBlank() && clientId.length <= 512 && clientId.none(Char::isWhitespace))
        require(clientId != ChatGptOAuthProtocol.DYNAMIC_CLIENT_ID)
        optionalStrings.forEach { name ->
            val field = json.get(name)
            require(field == null || field.isJsonNull || (field.isJsonPrimitive && field.asJsonPrimitive.isString))
        }
        numericFields.forEach { name ->
            val value = requireNotNull(json.get(name))
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            require(value.asString.toLongOrNull()?.let { it >= 0 } == true)
        }
        val scopes = requireNotNull(json.get("scopes"))
        require(scopes.isJsonArray && scopes.asJsonArray.all {
            it.isJsonPrimitive && it.asJsonPrimitive.isString &&
                it.asString.isNotEmpty() && it.asString.none(Char::isWhitespace)
        })
        val registration = requireNotNull(gson.fromJson(json, ChatGptRegistration::class.java))
        val tokenFields = listOf(registration.accessToken, registration.refreshToken, registration.idToken, registration.loginNonce)
        require(tokenFields.all { token ->
            token == null || (token.isNotBlank() && token.length <= 64 * 1024 && token.none(Char::isWhitespace))
        })
        if (registration.accessToken != null) {
            require(!registration.subject.isNullOrBlank() && registration.idToken != null && registration.loginNonce != null)
            require(registration.expiresAtSeconds > 0)
        } else {
            require(registration.refreshToken == null && registration.idToken == null && registration.loginNonce == null)
            require(registration.expiresAtSeconds == 0L && registration.earliestRefreshAtSeconds == 0L && registration.scopes.isEmpty())
        }
        Decoded(registration, isLegacy)
    } catch (_: Exception) {
        throw invalidFormat()
    }

    private fun invalidFormat() = ChatGptOAuthException(
        INVALID_FORMAT_CODE, "Stored ChatGPT credentials have an unsupported or invalid format. Existing data was preserved.",
    )
}
