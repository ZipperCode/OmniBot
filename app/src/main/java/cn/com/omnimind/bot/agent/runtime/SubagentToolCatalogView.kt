package cn.com.omnimind.bot.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 在现有执行前校验边界落实角色权限。 */
internal class SubagentToolCatalogView(
    private val parent: AgentToolCatalog,
    private val profile: SubagentProfile,
) : AgentToolCatalog {
    private val allowedToolsSet = profile.allowedTools?.toSet()
    private val disallowedToolsSet = profile.disallowedTools.toSet()
    private val baseProfileIdNormalized = profile.baseProfileId.lowercase().trim()

    private val allowedToolNames: Set<String> = buildSet {
        for (tool in parent.toolsForModel) {
            val name = tool.function.name
            if (name == "subagent_dispatch") continue
            if (name in disallowedToolsSet) continue
            if (allowedToolsSet != null && name !in allowedToolsSet) continue

            val expectedType = when (baseProfileIdNormalized) {
                "general" -> null
                "explorer" -> READABLE_TOOL_TYPES[name] ?: continue
                "memory-curator" -> CURATOR_TOOL_TYPES[name] ?: continue
                "planner" -> continue
                else -> continue
            }

            if (expectedType != null) {
                val descriptor = parent.runtimeDescriptor(name)
                if (descriptor.toolType != expectedType || descriptor.serverName != null) {
                    continue
                }
            }
            add(name)
        }
    }

    override val toolsForModel: List<ChatCompletionTool> =
        parent.toolsForModel.filter { it.function.name in allowedToolNames }

    // 保留被拒调用的元信息，以便配对错误结果。
    override fun runtimeDescriptor(toolName: String) = parent.runtimeDescriptor(toolName)

    override fun validateArguments(toolName: String, arguments: JsonObject) {
        require(toolName != "subagent_dispatch") {
            "Tool 'subagent_dispatch' is outside the ${profile.id} role permissions"
        }
        require(toolName in allowedToolNames) {
            "Tool '$toolName' is outside the ${profile.id} role permissions"
        }
        if (baseProfileIdNormalized == "explorer" && toolName == "browser_use") {
            val action = arguments["action"]?.jsonPrimitive?.contentOrNull
            require(action in OBSERVATION_ACTIONS) {
                "Browser action '$action' is outside explorer observation permissions"
            }
        }
        parent.validateArguments(toolName, arguments)
    }

    override fun searchTools(query: String, limit: Int?): List<AgentToolSearchEntry> =
        parent.searchTools(query, null).filter { it.name in allowedToolNames }.let { matches ->
            if (limit == null) matches else matches.take(limit.coerceAtLeast(0))
        }

    companion object {
        private val OBSERVATION_ACTIONS = setOf(
            "navigate", "screenshot", "get_text", "get_page_info", "find_elements",
            "get_readable", "get_backbone", "list_tabs", "scroll", "go_back",
            "go_forward", "wait_for_selector",
        )
        private val READABLE_TOOL_TYPES = mapOf(
            "file_read" to "workspace", "file_list" to "workspace",
            "file_search" to "workspace", "file_stat" to "workspace",
            "memory_search" to "memory", "memory_load" to "memory",
            "skills_list" to "skill", "skills_read" to "skill",
            "context_apps_query" to "builtin", "context_time_now" to "builtin",
            "browser_use" to "browser",
        )
        private val MEMORY_TOOLS = mapOf(
            "memory_write_daily" to "memory", "memory_upsert_longterm" to "memory",
            "memory_rollup_day" to "memory",
        )
        private val CURATOR_TOOL_TYPES = READABLE_TOOL_TYPES.filterValues {
            it == "workspace" || it == "memory"
        } + MEMORY_TOOLS
    }
}
