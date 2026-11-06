package cn.com.omnimind.baselib.llm.chatgpt

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID

/** Actual Android Keystore/prefs and loopback tests; never contacts an OpenAI endpoint. */
class EncryptedChatGptCredentialStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun profileId() = "oauth-instrumentation-${UUID.randomUUID()}"

    private fun encryptedPreferences() = EncryptedSharedPreferences.create(
        context, EncryptedChatGptCredentialStore.FILE_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Test fun encryptedRecordSurvivesStoreRecreationWithoutPlaintextTokens() {
        val id = profileId()
        val store = EncryptedChatGptCredentialStore(context)
        val record = ChatGptRegistration(
            clientId = "oaiapp_instrumentation", subject = "test-subject", email = "test@example.invalid",
            accessToken = "synthetic-access-not-a-real-token", refreshToken = "synthetic-refresh-not-a-real-token",
            idToken = "synthetic-id-token", loginNonce = "synthetic-nonce",
            expiresAtSeconds = 1900000000, scopes = setOf("resource.invoke", ChatGptOAuthProtocol.PLAN_SCOPE),
            accountRevision = 42,
        )
        try {
            val hostId = store.hostId()
            store.write(id, record)
            val reopened = EncryptedChatGptCredentialStore(context)
            assertEquals(record, reopened.read(id))
            assertEquals(hostId, reopened.hostId())
            val disk = File(context.applicationInfo.dataDir,
                "shared_prefs/${EncryptedChatGptCredentialStore.FILE_NAME}.xml").readText()
            assertFalse(disk.contains(record.accessToken!!))
            assertFalse(disk.contains(record.refreshToken!!))
            assertFalse(disk.contains(record.email!!))
        } finally { store.delete(id) }
    }

    @Test fun oldMinifiedPartialRegistrationIsMigratedWithoutNewClientOrHost() {
        val id = profileId()
        val store = EncryptedChatGptCredentialStore(context)
        val key = "profile_${id.toByteArray().toByteString().base64Url()}"
        val prefs = encryptedPreferences()
        try {
            val hostId = store.hostId()
            assertTrue(prefs.edit().putString(key,
                """{"a":"oaiapp_legacy_instrumentation","h":0,"i":0,"j":[],"k":0}""").commit())
            val restored = requireNotNull(store.read(id))
            assertEquals("oaiapp_legacy_instrumentation", restored.clientId)
            assertFalse(restored.hasTokens)
            assertEquals(hostId, store.hostId())
            val migrated = JsonParser.parseString(prefs.getString(key, null)).asJsonObject
            assertTrue(migrated.has("scopes"))
            assertTrue(migrated.has("clientId"))
            assertFalse(migrated.has("a"))
            assertFalse(migrated.has("j"))
            assertEquals(restored, EncryptedChatGptCredentialStore(context).read(id))
        } finally { store.delete(id) }
    }

    @Test fun localCallbackStartsAndHandlesDeclineWithoutCallingOpenAi() = runBlocking {
        val id = profileId()
        ChatGptOAuthManager.initialize(context)
        try {
            val attempt = ChatGptOAuthManager.startLogin(id)
            val attemptId = attempt["attemptId"] as String
            val authorize = (attempt["authUrl"] as String).toHttpUrl()
            assertEquals("auth.openai.com", authorize.host)
            val callback = requireNotNull(authorize.queryParameter("redirect_uri")).toHttpUrl()
            assertEquals("127.0.0.1", callback.host)
            assertEquals("/auth/callback", callback.encodedPath)
            val state = requireNotNull(authorize.queryParameter("state"))
            Socket(callback.host, callback.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().write((
                    "GET /auth/callback?error=access_denied&state=$state HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${callback.port}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("200"))
            }
            withTimeout(5000) {
                while (ChatGptOAuthManager.loginStatus(attemptId)["status"] == "pending") delay(20)
            }
            assertEquals("authorization_declined", ChatGptOAuthManager.loginStatus(attemptId)["errorCode"])
            val retry = ChatGptOAuthManager.startLogin(id)
            ChatGptOAuthManager.cancelLogin(retry["attemptId"] as String)
            assertEquals("cancelled", ChatGptOAuthManager.loginStatus(retry["attemptId"] as String)["status"])
            assertEquals(false, ChatGptOAuthManager.account(id)["connected"])
        } finally { ChatGptOAuthManager.removeProfile(id) }
    }
}
