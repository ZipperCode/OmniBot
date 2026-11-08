package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

class SubagentCapabilityInheritanceTest {

    @Test
    fun `child inherits parent capabilities except subagent_dispatch recursion`() {
        val parent = FakeCatalog(
            toolNames = listOf(
                "terminal_execute",
                "file_delete",
                "schedule_task_create",
                "plugin_custom_action",
                "subagent_dispatch"
            )
        )

        val child = inheritedSubagentCatalog(parent)

        // child 必须收窄，拒绝 subagent_dispatch 递归
        val childNames = child.toolsForModel.map { it.function.name }
        assertEquals(
            listOf(
                "terminal_execute",
                "file_delete",
                "schedule_task_create",
                "plugin_custom_action"
            ),
            childNames
        )
        assertTrue("subagent_dispatch must not be exposed to child", "subagent_dispatch" !in childNames)
        assertTrue(
            "child must reject subagent_dispatch invocation",
            runCatching { child.validateArguments("subagent_dispatch", JsonObject(emptyMap())) }.isFailure
        )
        assertTrue(
            "searchTools must not return subagent_dispatch",
            child.searchTools("subagent_dispatch").none { it.name == "subagent_dispatch" }
        )
    }

    @Test
    fun `allowed and disallowed tools narrow base role but never expand permissions`() {
        val parent = FakeCatalog(listOf("file_read", "file_write", "browser_use", "terminal_execute"))

        // 白名单收窄 general
        val generalNarrowed = inheritedSubagentCatalog(
            parent,
            SubagentProfile(
                id = "narrow-general",
                displayName = "狭窄通用",
                systemPrompt = "",
                allowedTools = listOf("file_read", "file_write")
            )
        )
        assertEquals(listOf("file_read", "file_write"), generalNarrowed.toolsForModel.map { it.function.name })
        assertTrue(runCatching { generalNarrowed.validateArguments("terminal_execute", JsonObject(emptyMap())) }.isFailure)

        // 黑名单剔除
        val generalDenied = inheritedSubagentCatalog(
            parent,
            SubagentProfile(
                id = "denied-general",
                displayName = "排除写入",
                systemPrompt = "",
                disallowedTools = listOf("file_write")
            )
        )
        assertEquals(listOf("file_read", "browser_use", "terminal_execute"), generalDenied.toolsForModel.map { it.function.name })

        // 白名单不能为 explorer 扩权（explorer 基础角色不能获得 file_write）
        val explorerWidenedAttempt = inheritedSubagentCatalog(
            parent,
            SubagentProfile(
                id = "illegal-widen",
                displayName = "试图扩权",
                systemPrompt = "",
                baseProfileId = "explorer",
                allowedTools = listOf("file_read", "file_write")
            )
        )
        assertEquals(listOf("file_read"), explorerWidenedAttempt.toolsForModel.map { it.function.name })
        assertTrue(runCatching { explorerWidenedAttempt.validateArguments("file_write", JsonObject(emptyMap())) }.isFailure)

        // 空白名单表示无工具权限
        val noTools = inheritedSubagentCatalog(
            parent,
            SubagentProfile(
                id = "zero-tools",
                displayName = "零工具",
                systemPrompt = "",
                allowedTools = emptyList()
            )
        )
        assertTrue(noTools.toolsForModel.isEmpty())
        assertTrue(runCatching { noTools.validateArguments("file_read", JsonObject(emptyMap())) }.isFailure)
    }

    @Test
    fun `planner cannot invoke tools even if model invents a hidden call`() {
        val child = inheritedSubagentCatalog(FakeCatalog(listOf("file_read", "file_delete")), SubagentProfileRegistry.planner)
        assertTrue(child.toolsForModel.isEmpty())
        assertTrue(runCatching { child.validateArguments("file_delete", JsonObject(emptyMap())) }.isFailure)
        assertEquals("file_delete", child.runtimeDescriptor("file_delete").name)
    }

    @Test
    fun `explorer can read but cannot write or invoke browser script`() {
        val child = inheritedSubagentCatalog(FakeCatalog(listOf("file_read", "file_write", "browser_use")), SubagentProfileRegistry.explorer)
        assertEquals(listOf("file_read", "browser_use"), child.toolsForModel.map { it.function.name })
        child.validateArguments("file_read", JsonObject(emptyMap()))
        child.validateArguments("browser_use", JsonObject(mapOf("action" to JsonPrimitive("get_text"))))
        for (name in listOf("file_write", "terminal_execute", "plugin_custom_action")) {
            assertTrue(runCatching { child.validateArguments(name, JsonObject(emptyMap())) }.isFailure)
        }
        for (action in listOf("execute_js", "click", "type", "press_key")) {
            assertTrue(runCatching { child.validateArguments("browser_use", JsonObject(mapOf("action" to JsonPrimitive(action)))) }.isFailure)
        }
    }

    @Test
    fun `memory curator can write memory but cannot write workspace files`() {
        val child = inheritedSubagentCatalog(FakeCatalog(listOf("memory_upsert_longterm", "file_read", "file_write")), SubagentProfileRegistry.memoryCurator)
        assertEquals(listOf("memory_upsert_longterm", "file_read"), child.toolsForModel.map { it.function.name })
        child.validateArguments("memory_upsert_longterm", JsonObject(emptyMap()))
        assertTrue(runCatching { child.validateArguments("file_write", JsonObject(emptyMap())) }.isFailure)
    }

    @Test
    fun `specialist permissions never admit a plugin using a builtin name`() {
        val parent = object : AgentToolCatalog by FakeCatalog(listOf("file_read")) {
            override fun runtimeDescriptor(toolName: String) = AgentToolRegistry.RuntimeToolDescriptor(toolName, toolName, "plugin", "custom")
        }
        val child = inheritedSubagentCatalog(parent, SubagentProfileRegistry.explorer)
        assertTrue(child.toolsForModel.isEmpty())
        assertTrue(runCatching { child.validateArguments("file_read", JsonObject(emptyMap())) }.isFailure)
    }

    private class FakeCatalog(
        toolNames: List<String>
    ) : AgentToolCatalog {
        override val toolsForModel = toolNames.map { name ->
            ChatCompletionTool(function = ChatCompletionFunction(name = name))
        }

        override fun runtimeDescriptor(toolName: String): AgentToolRegistry.RuntimeToolDescriptor {
            return AgentToolRegistry.RuntimeToolDescriptor(
                name = toolName,
                displayName = toolName,
                toolType = when {
                    toolName.startsWith("file_") -> "workspace"
                    toolName.startsWith("memory_") -> "memory"
                    toolName == "browser_use" -> "browser"
                    else -> "builtin"
                }
            )
        }

        override fun validateArguments(toolName: String, arguments: JsonObject) = Unit
    }
}
