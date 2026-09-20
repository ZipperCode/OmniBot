package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.privileged.PrivilegedAuthority
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolDefinitionsPrivilegedTest {

    @Test
    fun `android privileged action exposes shell exec`() {
        val tool = AgentToolDefinitions.androidPrivilegedActionTool(
            visibleActions = listOf("diagnostics.getprop", "shell.exec"),
            backend = PrivilegedAuthority.ADB,
            locale = PromptLocale.EN_US
        )
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val action = properties["action"] as JsonObject
        val enumValues = action["enum"] as JsonArray
        val arguments = properties["arguments"] as JsonObject
        val argumentProperties = arguments["properties"] as JsonObject

        assertTrue(enumValues.any { it.jsonPrimitive.contentOrNull == "shell.exec" })
        assertTrue("packageName" in argumentProperties)
        assertTrue("activityName" in argumentProperties)
        assertTrue("command" in argumentProperties)
        assertTrue("confirmed" !in argumentProperties)
        assertEquals(false, arguments["additionalProperties"]?.toString()?.toBoolean())
        assertEquals(false, parameters["additionalProperties"]?.toString()?.toBoolean())
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull?.contains("one-shot non-login shell") ==
                true
        )
    }

    @Test
    fun `privileged session exec requires session id and command`() {
        val tool = AgentToolDefinitions.androidPrivilegedSessionExecTool(
            backend = PrivilegedAuthority.ROOT,
            locale = PromptLocale.ZH_CN
        )
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        val required = parameters["required"] as JsonArray

        assertTrue(required.any { it.jsonPrimitive.contentOrNull == "sessionId" })
        assertTrue(required.any { it.jsonPrimitive.contentOrNull == "command" })
        assertTrue("confirmed" !in properties)
        assertEquals(false, parameters["additionalProperties"]?.toString()?.toBoolean())
    }

    @Test
    fun `privileged session start leaves approval to ACP`() {
        val tool = AgentToolDefinitions.androidPrivilegedSessionStartTool(
            backend = PrivilegedAuthority.ADB,
            locale = PromptLocale.EN_US
        )
        val function = tool["function"] as JsonObject
        val parameters = function["parameters"] as JsonObject
        val properties = parameters["properties"] as JsonObject
        assertTrue("confirmed" !in properties)
        assertEquals(false, parameters["additionalProperties"]?.toString()?.toBoolean())
        assertTrue(
            function["description"]?.jsonPrimitive?.contentOrNull
                ?.contains("ACP", ignoreCase = true) == true
        )
    }
}
