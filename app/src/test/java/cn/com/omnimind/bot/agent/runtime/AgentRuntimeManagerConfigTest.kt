package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRuntimeManagerConfigTest {
    @Test
    fun `only commands from the currently bound owned session admit without a turn`() {
        fun admits(session: String? = "current", bound: String? = "current",
                   update: String = "available_commands_update", owns: Boolean = true,
                   turn: String? = null, method: String = "session/update") =
            admitsBoundAcpCommands(method, update, session, bound, turn, owns)
        assertEquals(true, admits())
        assertEquals(false, admits(session = "old"))
        assertEquals(false, admits(bound = null))
        assertEquals(false, admits(session = null, bound = null))
        assertEquals(false, admits(owns = false))
        assertEquals(false, admits(turn = "arbitrary-turn"))
        assertEquals(false, admits(update = "agent_message_chunk"))
        assertEquals(false, admits(method = "other"))
    }

    @Test
    fun `Dispatch Provider falls back to the editing profile without a scene binding`() {
        val editingProfile = ModelProviderProfile(
            id = "editing-provider",
            name = "Editing Provider",
            baseUrl = "https://gateway.example/v1",
        )

        assertEquals(
            editingProfile,
            resolveDispatchAgentProviderProfile(
                boundProviderProfileId = null,
                configuredProfile = null,
                editingProfile = editingProfile,
            ),
        )
    }

    @Test
    fun `explicit missing mismatched or unconfigured binding never falls back to editing profile`() {
        val editingProfile = ModelProviderProfile(
            id = "editing-provider",
            name = "Editing Provider",
            baseUrl = "https://gateway.example/v1",
        )
        val validProfile = ModelProviderProfile(
            id = "bound-provider",
            name = "Bound Provider",
            baseUrl = "https://bound.example/v1",
            apiKey = "key-123",
        )
        val unconfiguredProfile = ModelProviderProfile(
            id = "bound-provider",
            name = "Bound Provider",
            baseUrl = "",
        )

        // Missing binding (e.g. retired official provider) must not fall back
        assertNull(
            resolveDispatchAgentProviderProfile(
                boundProviderProfileId = "retired-official-provider",
                configuredProfile = null,
                editingProfile = editingProfile,
            ),
        )

        // Mismatched provider ID must not fall back
        assertNull(
            resolveDispatchAgentProviderProfile(
                boundProviderProfileId = "bound-provider",
                configuredProfile = ModelProviderProfile(
                    id = "other-provider",
                    name = "Other",
                    baseUrl = "https://other.example/v1",
                ),
                editingProfile = editingProfile,
            ),
        )

        // Unconfigured bound provider must not fall back
        assertNull(
            resolveDispatchAgentProviderProfile(
                boundProviderProfileId = "bound-provider",
                configuredProfile = unconfiguredProfile,
                editingProfile = editingProfile,
            ),
        )

        // Explicit matching and configured binding resolves successfully
        assertEquals(
            validProfile,
            resolveDispatchAgentProviderProfile(
                boundProviderProfileId = "bound-provider",
                configuredProfile = validProfile,
                editingProfile = editingProfile,
            ),
        )
    }

    @Test
    fun `Dispatch binding fallback can create an ephemeral model selection`() {
        val editingProfile = ModelProviderProfile(
            id = "editing-provider",
            name = "Editing Provider",
            baseUrl = "https://gateway.example/v1",
        )

        assertEquals(
            "dispatch-model",
            resolveSharedAgentProviderBinding(
                currentBinding = null,
                editingProfile = editingProfile,
                availableModels = listOf(ProviderModelOption(id = "dispatch-model")),
            )?.modelId,
        )
    }

    @Test
    fun `custom provider keeps its configured api key`() {
        val customProfile = ModelProviderProfile(
            id = "custom-provider",
            name = "Custom",
            baseUrl = "https://provider.example",
            apiKey = "configured-key",
        )

        assertEquals(
            "configured-key",
            resolveAgentProviderApiKey(customProfile),
        )
    }

    @Test
    fun `persisted model remains usable when provider catalog is offline`() {
        assertEquals(
            "glm-5.1",
            resolveAcpLaunchModelWithBindingFallback(
                providerModelIds = emptyList(),
                boundModel = "glm-5.1",
            )
        )
    }

    @Test
    fun `OpenCode provider sync preserves user MCP configuration`() {
        val config = buildOpenCodeConfigJson(
            model = "omnibot/gpt-5",
            baseUrl = "https://provider.example/v1",
            existingConfigJson = """
                {
                  "mcp": {
                    "filesystem": {
                      "type": "local",
                      "command": ["filesystem-server"]
                    }
                  },
                  "agent": { "custom": { "description": "keep me" } }
                }
            """.trimIndent(),
        )

        val root = JsonParser.parseString(config).asJsonObject
        assertNotNull(root.getAsJsonObject("mcp").getAsJsonObject("filesystem"))
        assertEquals(
            "keep me",
            root.getAsJsonObject("agent").getAsJsonObject("custom")
                .get("description").asString,
        )
        assertEquals("omnibot/gpt-5", root.get("model").asString)
        assertEquals(
            "https://provider.example/v1",
            root.getAsJsonObject("providers").getAsJsonObject("omnibot")
                .getAsJsonObject("settings").get("baseURL").asString,
        )
    }

    @Test
    fun `stale explicit ACP session is not reused after conversation switch`() {
        assertEquals(
            false,
            explicitThreadMatchesConversation(
                explicitThreadId = "old-session",
                requestedConversationId = 41L,
                boundConversationId = 40L,
            )
        )
    }

    @Test
    fun `current conversation keeps its ACP session and session-only calls stay compatible`() {
        assertEquals(
            true,
            explicitThreadMatchesConversation(
                explicitThreadId = "current-session",
                requestedConversationId = 41L,
                boundConversationId = 41L,
            )
        )
        assertEquals(
            true,
            explicitThreadMatchesConversation(
                explicitThreadId = "session-only",
                requestedConversationId = null,
                boundConversationId = null,
            )
        )
    }
}
