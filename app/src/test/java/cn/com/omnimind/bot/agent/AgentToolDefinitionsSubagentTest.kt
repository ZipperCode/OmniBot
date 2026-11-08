package cn.com.omnimind.bot.agent

import android.app.Application
import cn.com.omnimind.baselib.i18n.PromptLocale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AgentToolDefinitionsSubagentTest {

    @Test
    fun `disabled subagent config produces empty tool list`() {
        val tools = AgentToolDefinitions.subagentTools(
            PromptLocale.ZH_CN,
            XiaowanSubagentConfig(enabled = false)
        )
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `dynamic custom profiles update enum and filter disabled profiles`() {
        val customProfile = SubagentProfile(
            id = "code-reviewer",
            displayName = "代码审查员",
            systemPrompt = "审阅代码变更",
            description = "专注代码质量检查",
            baseProfileId = "explorer",
            enabled = true,
            allowedTools = listOf("file_read", "file_list")
        )
        val disabledProfile = SubagentProfile(
            id = "disabled-curator",
            displayName = "已禁用角色",
            systemPrompt = "",
            enabled = false
        )
        val config = XiaowanSubagentConfig(
            enabled = true,
            profiles = SubagentProfileRegistry.all() + listOf(customProfile, disabledProfile)
        )

        val tool = AgentToolDefinitions.subagentTools(PromptLocale.ZH_CN, config).single()
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val tasks = properties["tasks"] as JsonObject
        val item = tasks["items"] as JsonObject
        val itemProperties = item["properties"] as JsonObject
        val profile = itemProperties["profileId"] as JsonObject
        val profileIds = (profile["enum"] as JsonArray).map { it.jsonPrimitive.contentOrNull }

        assertTrue(profileIds.contains("code-reviewer"))
        assertTrue(!profileIds.contains("disabled-curator"))

        // 通过 Registry 的 validateArguments 进行消费者强校验：有效 profileId 通过，禁用/非法 profileId 拒绝
        val context = RuntimeEnvironment.getApplication()
        val registry = AgentToolRegistry(
            context = context,
            subagentConfig = config
        )

        val validArgs = buildJsonObject {
            put("defaultProfileId", "code-reviewer")
            put("tasks", buildJsonArray {
                add(buildJsonObject {
                    put("instruction", "review code")
                    put("profileId", "code-reviewer")
                })
            })
        }
        registry.validateArguments("subagent_dispatch", validArgs)

        val disabledArgs = buildJsonObject {
            put("defaultProfileId", "disabled-curator")
            put("tasks", buildJsonArray {
                add(buildJsonObject {
                    put("instruction", "curate memory")
                    put("profileId", "disabled-curator")
                })
            })
        }
        assertTrue(runCatching { registry.validateArguments("subagent_dispatch", disabledArgs) }.isFailure)

        val unknownArgs = buildJsonObject {
            put("defaultProfileId", "non-existent")
            put("tasks", buildJsonArray {
                add(buildJsonObject {
                    put("instruction", "unknown")
                    put("profileId", "non-existent")
                })
            })
        }
        assertTrue(runCatching { registry.validateArguments("subagent_dispatch", unknownArgs) }.isFailure)
    }
}
