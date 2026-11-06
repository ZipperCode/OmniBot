package cn.com.omnimind.baselib.llm

import org.junit.Assert.*
import org.junit.Test

class ChatGptOAuthProviderTest {
    private fun profile() = ModelProviderProfile(
        id = "chatgpt-account",
        name = "ChatGPT",
        sourceType = ChatGptOAuthProvider.SOURCE_TYPE,
        baseUrl = "https://untrusted.example/v1",
        apiKey = "must-not-be-used",
        customHeaders = mapOf("Authorization" to "Bearer must-not-be-used"),
        protocolType = "anthropic",
        wireApi = OpenAiWireApi.CHAT_COMPLETIONS,
    )

    @Test
    fun oauthProfilePinsTransportAndDiscardsStaticCredentials() {
        val normalized = ChatGptOAuthProvider.normalize(profile())
        assertEquals(ChatGptOAuthProvider.BASE_URL, normalized.baseUrl)
        assertEquals("openai_compatible", normalized.protocolType)
        assertEquals(OpenAiWireApi.RESPONSES, normalized.wireApi)
        assertEquals("", normalized.apiKey)
        assertTrue(normalized.customHeaders.isEmpty())
    }

    @Test
    fun hydrationCannotAttachApiKeyOrHeadersToOAuthProfile() {
        val hydrated = ModelProviderConfigStore.mergeProfileSecrets(
            profile(), ModelProviderSecrets("stale-key", mapOf("X-Key" to "stale-header")),
        )
        assertEquals(ChatGptOAuthProvider.BASE_URL, hydrated.baseUrl)
        assertTrue(hydrated.apiKey.isEmpty())
        assertTrue(hydrated.customHeaders.isEmpty())
    }

    @Test
    fun metadataRoundTripPreservesExplicitAuthenticationWithoutSecrets() {
        val raw = ModelProviderConfigStore.encodeProfilesMetadataJson(
            listOf(ChatGptOAuthProvider.normalize(profile())),
        )
        assertFalse(raw.contains("must-not-be-used"))
        val decoded = ModelProviderConfigStore.decodeProfilesJson(raw).single()
        assertEquals(ChatGptOAuthProvider.SOURCE_TYPE, decoded.sourceType)
        assertEquals(OpenAiWireApi.RESPONSES, decoded.wireApi)
    }

    @Test
    fun openAiEndpointDoesNotOptApiKeyUsersIntoOAuth() {
        assertEquals("custom", OfficialProviderRegistry.normalizeSourceType(
            "custom", "my-openai", ChatGptOAuthProvider.BASE_URL,
        ))
        val apiKeyProfile = profile().copy(sourceType = "custom")
        assertEquals(apiKeyProfile, ChatGptOAuthProvider.normalize(apiKeyProfile))
    }

    @Test
    fun accountChangeInvalidatesCatalogEvenWhenMetadataRevisionIsLarger() {
        val metadata = ChatGptOAuthProvider.normalize(profile()).copy(revision = 500)
        val loggedIn = ChatGptOAuthProvider.withAccountRevision(metadata, 100)
        val loggedOut = ChatGptOAuthProvider.withAccountRevision(loggedIn, 101)
        assertEquals(600L, loggedIn.revision)
        assertEquals(601L, loggedOut.revision)
        assertEquals(loggedOut, ChatGptOAuthProvider.withAccountRevision(loggedOut, 101))
        val stored = ModelProviderConfigStore.decodeProfilesJson(
            ModelProviderConfigStore.encodeProfilesMetadataJson(listOf(loggedOut)),
        ).single()
        assertEquals(500L, stored.revision)
        assertEquals(0L, stored.credentialRevision)
        assertEquals(601L, ChatGptOAuthProvider.withAccountRevision(stored, 101).revision)
    }
}
