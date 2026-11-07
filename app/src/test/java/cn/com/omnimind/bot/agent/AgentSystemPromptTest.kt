package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import com.rk.terminal.runtime.TerminalDistribution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSystemPromptTest {

    @Test
    fun buildCachedSystemPromptContentUsesStandardTextContent() {
        val content = OmniAgentExecutor.buildCachedSystemPromptContent("system prompt")
        assertEquals(JsonPrimitive("system prompt"), content)
    }

    @Test
    fun exactTimeIsExposedAsAZeroArgumentTool() {
        val function = AgentToolDefinitions.contextTimeNowTool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject

        assertEquals("context_time_now", function["name"]?.jsonPrimitive?.content)
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertTrue((parameters["properties"] as JsonObject).isEmpty())
    }


    @Test
    fun buildUsesOnlySelectedUbuntuNameInModelFacingEnvironmentText() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-ubuntu",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace/demo",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace/demo",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "dynamic-skill",
                    name = "dynamic-skill",
                    description = "Runs in {{OMNIBOT_TERMINAL_DISTRIBUTION}}.",
                    rootPath = "/workspace/.omnibot/skills/dynamic-skill",
                    shellRootPath = "/workspace/.omnibot/skills/dynamic-skill",
                    skillFilePath = "/workspace/.omnibot/skills/dynamic-skill/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/dynamic-skill/SKILL.md",
                    hasScripts = false,
                    hasReferences = false,
                    hasAssets = false,
                    hasEvals = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.EN_US,
            terminalDistribution = TerminalDistribution.ubuntu
        )

        assertTrue(prompt.contains("inside the Ubuntu environment"))
        assertTrue(prompt.contains("description=Runs in Ubuntu."))
        assertTrue(!prompt.contains("Alpine"))
        assertTrue(!prompt.contains("{{OMNIBOT_TERMINAL_DISTRIBUTION}}"))
    }

    @Test
    fun buildKeepsCompleteInstalledSkillDescriptionsAndDoesNotRequirePrivateToolTitles() {
        val longDescription = buildString {
            repeat(220) { append(('a'.code + it % 26).toChar()) }
            append(" COMPLETE_SKILL_DESCRIPTION")
        }
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-skill-description",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                SkillIndexEntry(
                    id = "complete-description",
                    name = "complete-description",
                    description = longDescription,
                    rootPath = "/workspace/.omnibot/skills/complete-description",
                    shellRootPath = "/workspace/.omnibot/skills/complete-description",
                    skillFilePath = "/workspace/.omnibot/skills/complete-description/SKILL.md",
                    shellSkillFilePath = "/workspace/.omnibot/skills/complete-description/SKILL.md",
                    hasScripts = false,
                    hasReferences = false,
                    hasAssets = false,
                    hasEvals = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null,
            locale = PromptLocale.EN_US,
            terminalDistribution = TerminalDistribution.alpine
        )

        assertTrue(prompt.contains(longDescription))
        assertTrue(!prompt.contains("tool_title"))
        assertTrue(!prompt.contains("4-12 word"))
    }

    @Test
    fun buildKeepsTurnMemoryAndSkillBodiesOutOfCachedSystemPrompt() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-harness",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = emptyList(),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = listOf(
                ResolvedSkillContext(
                    skillId = "turn-only",
                    frontmatter = mapOf("name" to "turn-only"),
                    bodyMarkdown = "TURN_ONLY_SKILL_BODY",
                    triggerReason = "test"
                )
            ),
            memoryContext = WorkspaceMemoryPromptContext(
                soul = "SOUL_STAYS_STABLE",
                longTermMemory = "VOLATILE_LONG_TERM_MEMORY",
                todayShortMemory = "VOLATILE_DAILY_MEMORY",
            ),
            locale = PromptLocale.EN_US,
            terminalDistribution = TerminalDistribution.alpine
        )

        assertTrue(prompt.contains("SOUL_STAYS_STABLE"))
        assertTrue(prompt.contains("Before answering about prior work, decisions, preferences, or pending tasks"))
        assertTrue(!prompt.contains("Use a listed memory capability only when"))
        assertTrue(!prompt.contains("tools_search"))
        assertTrue(prompt.contains("memory write or modification capabilities"))
        assertTrue(!prompt.contains("skills_read"))
        assertTrue(!prompt.contains("memory_search"))
        assertTrue(!prompt.contains("memory_load"))
        assertTrue(!prompt.contains("[skills.loaded]"))
        assertTrue(!prompt.contains("[memory.context]"))
        assertTrue(!prompt.contains("TURN_ONLY_SKILL_BODY"))
        assertTrue(!prompt.contains("VOLATILE_LONG_TERM_MEMORY"))
        assertTrue(!prompt.contains("VOLATILE_DAILY_MEMORY"))
    }
}
