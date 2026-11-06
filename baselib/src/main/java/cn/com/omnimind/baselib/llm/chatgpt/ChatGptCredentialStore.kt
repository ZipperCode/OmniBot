package cn.com.omnimind.baselib.llm.chatgpt

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.annotations.SerializedName
import okio.ByteString.Companion.toByteString
import java.util.UUID

internal data class ChatGptRegistration(
    @field:SerializedName("clientId")
    val clientId: String,
    @field:SerializedName("subject")
    val subject: String? = null,
    @field:SerializedName("email")
    val email: String? = null,
    @field:SerializedName("accessToken")
    val accessToken: String? = null,
    @field:SerializedName("refreshToken")
    val refreshToken: String? = null,
    @field:SerializedName("idToken")
    val idToken: String? = null,
    @field:SerializedName("loginNonce")
    val loginNonce: String? = null,
    @field:SerializedName("expiresAtSeconds")
    val expiresAtSeconds: Long = 0,
    @field:SerializedName("earliestRefreshAtSeconds")
    val earliestRefreshAtSeconds: Long = 0,
    @field:SerializedName("scopes")
    val scopes: Set<String> = emptySet(),
    @field:SerializedName("accountRevision")
    val accountRevision: Long = 0,
) {
    val hasTokens: Boolean get() = !accessToken.isNullOrBlank() && !subject.isNullOrBlank()
    val planEnabled: Boolean get() = ChatGptOAuthProtocol.PLAN_SCOPE in scopes && "resource.invoke" in scopes

    fun withoutTokens() = copy(
        accessToken = null, refreshToken = null, idToken = null, loginNonce = null,
        expiresAtSeconds = 0, earliestRefreshAtSeconds = 0, scopes = emptySet(),
    )

    override fun toString(): String = "ChatGptRegistration(credentials=redacted)"
}

internal interface ChatGptCredentialStore {
    fun hostId(): String
    fun read(profileId: String): ChatGptRegistration?
    fun write(profileId: String, registration: ChatGptRegistration)
    fun delete(profileId: String)
}

/** No plaintext fallback. A failed Keystore/read/write makes this store unavailable for this process. */
internal class EncryptedChatGptCredentialStore(context: Context) : ChatGptCredentialStore {
    private val applicationContext = context.applicationContext
    private var unavailable: ChatGptOAuthException? = null
    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            applicationContext, FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Synchronized
    override fun hostId(): String = safely {
        preferences.getString(HOST_KEY, null) ?: "urn:uuid:${UUID.randomUUID()}".also { id ->
            check(preferences.edit().putString(HOST_KEY, id).commit())
            check(preferences.getString(HOST_KEY, null) == id)
        }
    }

    @Synchronized
    override fun read(profileId: String): ChatGptRegistration? = safely {
        preferences.getString(key(profileId), null)?.let { raw ->
            val decoded = ChatGptRegistrationCodec.decode(raw)
            if (decoded.needsRewrite) {
                // Upgrade only a fully validated, known legacy record in one encrypted commit.
                // The host ID and every other account remain untouched.
                write(profileId, decoded.registration)
            }
            decoded.registration
        }
    }

    @Synchronized
    override fun write(profileId: String, registration: ChatGptRegistration) = safely {
        val json = ChatGptRegistrationCodec.encode(registration)
        check(preferences.edit().putString(key(profileId), json).commit())
        check(preferences.getString(key(profileId), null) == json)
    }

    @Synchronized
    override fun delete(profileId: String) = safely {
        check(preferences.edit().remove(key(profileId)).commit())
        check(!preferences.contains(key(profileId)))
    }

    private fun key(profileId: String) = "profile_${profileId.toByteArray().toByteString().base64Url()}"

    private fun <T> safely(action: () -> T): T {
        unavailable?.let { throw it }
        return try { action() } catch (error: Exception) {
            val safe = (error as? ChatGptOAuthException)
                ?.takeIf { it.code == ChatGptRegistrationCodec.INVALID_FORMAT_CODE }
                ?: unavailableError()
            unavailable = safe
            throw safe
        }
    }

    private fun unavailableError() = ChatGptOAuthException(
        "secure_storage_unavailable", "Secure ChatGPT credential storage is unavailable.",
    )

    companion object {
        // Excluded from Android cloud backup and device transfer in the app module.
        const val FILE_NAME = "omni_chatgpt_oauth_secrets"
        private const val HOST_KEY = "agent_host_id"
    }
}
