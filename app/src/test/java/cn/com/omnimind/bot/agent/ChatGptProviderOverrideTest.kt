package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatGptOAuthProvider
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import org.junit.Assert.*
import org.junit.Test

class ChatGptProviderOverrideTest {
    @Test
    fun oauthOverrideCarriesProfileIdentityWithoutSnapshottingTokens() {
        val profile = ChatGptOAuthProvider.normalize(ModelProviderProfile(
            id = "my-chatgpt", name = "ChatGPT", sourceType = ChatGptOAuthProvider.SOURCE_TYPE,
        ))
        val override = requireNotNull(AgentModelOverride.fromProviderProfile(profile, "test-model"))
        assertEquals("my-chatgpt", override.oauthProfileId)
        assertEquals("", override.apiKey)
        assertTrue(override.customHeaders.isEmpty())
        assertEquals("responses", override.wireApi)
        assertEquals(override.oauthProfileId, override.normalized().oauthProfileId)
    }

    @Test
    fun apiKeyOverrideKeepsExistingSnapshotSemantics() {
        val profile = ModelProviderProfile(
            id = "my-openai", name = "OpenAI", baseUrl = ChatGptOAuthProvider.BASE_URL,
            apiKey = "api-key",
        )
        val override = requireNotNull(AgentModelOverride.fromProviderProfile(profile, "test-model"))
        assertNull(override.oauthProfileId)
        assertEquals("api-key", override.apiKey)
    }
}
