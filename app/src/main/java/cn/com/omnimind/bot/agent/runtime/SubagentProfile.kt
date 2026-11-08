package cn.com.omnimind.bot.agent

/**
 * A subagent profile defines optional task guidance for a spawned subagent.
 * Specialist permissions are enforced through a view of the parent catalog.
 * Profiles impose no round, token, duration, or output-size budget.
 */
data class SubagentProfile(
    val id: String,
    val displayName: String,
    val systemPrompt: String,
    val description: String = "",
    val baseProfileId: String = "general",
    val enabled: Boolean = true,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String> = emptyList(),
    val providerProfileId: String? = null,
    val modelId: String? = null
) {
    init {
        require((providerProfileId == null && modelId == null) || (providerProfileId != null && modelId != null)) {
            "providerProfileId and modelId must both be null or both non-null."
        }
        if (providerProfileId != null) {
            require(providerProfileId.isNotBlank() && modelId!!.isNotBlank()) {
                "providerProfileId and modelId must not be blank."
            }
        }
    }
}

object SubagentProfileRegistry {
    val general: SubagentProfile = SubagentProfile(
        id = "general",
        displayName = "通用子任务",
        systemPrompt = """
            你是一名通用子 Agent，由父 Agent 分派来完成一个独立的小任务。
            - 只使用本轮 tools 字段中提供的工具，参数必须符合 schema。
            - 如果不能完成，明确说明阻塞点与已尝试过的方法。
            - 完成后用一段简洁的自然语言概括结果（关键文件路径 / 决策 / 数据），便于父 Agent 聚合。
        """.trimIndent(),
        description = "通用子任务，继承父级可用工具",
        baseProfileId = "general",
        enabled = true,
        allowedTools = null,
        disallowedTools = emptyList()
    )

    val explorer: SubagentProfile = SubagentProfile(
        id = "explorer",
        displayName = "探索者",
        systemPrompt = """
            你是一名探索者子 Agent，专注于读取、搜索、归纳信息。
            - 浏览操作优先使用 browser_use 的 get_text / screenshot / navigate；避免使用 click / type 修改远端状态。
            - 在结果中先给出"核心结论"再附上"相关证据"（文件路径 / 记忆 slug / URL）。
            - 结果保持紧凑，但不要因为本地轮次假设而提前结束。
        """.trimIndent(),
        description = "专注于读取、搜索、归纳信息",
        baseProfileId = "explorer",
        enabled = true,
        allowedTools = null,
        disallowedTools = emptyList()
    )

    val memoryCurator: SubagentProfile = SubagentProfile(
        id = "memory-curator",
        displayName = "记忆管理员",
        systemPrompt = """
            你是一名记忆管理员子 Agent，负责整理 / 写入 / 沉淀 workspace 记忆。
            - 以 memory_search / memory_load 为主获取上下文；file_* 仅作为补充事实查证。
            - 写入前先检索，避免重复或冲突；过程性细节走 memory_write_daily，稳定结论走 memory_upsert_longterm。
            - 完成后简洁说明做了什么（新增 N 条短期、M 条长期、合并/跳过情况）。
        """.trimIndent(),
        description = "负责整理、写入和沉淀 workspace 记忆",
        baseProfileId = "memory-curator",
        enabled = true,
        allowedTools = null,
        disallowedTools = emptyList()
    )

    val planner: SubagentProfile = SubagentProfile(
        id = "planner",
        displayName = "规划器",
        systemPrompt = """
            你是一名规划器子 Agent，只输出一份结构化的执行计划。
            - 第一行：单句目标摘要。
            - 然后列出有序步骤，每步描述：动作、所需工具或资源、成功判据。
            - 标注潜在风险或依赖。
            - 最后用 2-3 句给出关键决策。
            - 输出后即结束，不要尝试调用工具。
        """.trimIndent(),
        description = "只输出一份结构化的执行计划，不执行工具",
        baseProfileId = "planner",
        enabled = true,
        allowedTools = null,
        disallowedTools = emptyList()
    )

    private val byId: Map<String, SubagentProfile> = listOf(
        general, explorer, memoryCurator, planner
    ).associateBy { it.id }

    val BUILT_IN_IDS: Set<String> = setOf("general", "explorer", "memory-curator", "planner")

    fun isBuiltIn(id: String): Boolean = BUILT_IN_IDS.contains(id.trim().lowercase())


    fun all(): List<SubagentProfile> = byId.values.toList()
}

/**
 * 小万 Subagent 配置快照与运行时验证。
 */
data class XiaowanSubagentConfig(
    val enabled: Boolean = true,
    val providerProfileId: String? = null,
    val modelId: String? = null,
    val reasoningEffort: String? = null,
    val profiles: List<SubagentProfile> = SubagentProfileRegistry.all()
) {
    init {
        require((providerProfileId == null && modelId == null) || (providerProfileId != null && modelId != null)) {
            "providerProfileId and modelId must both be null or both non-null."
        }
        reasoningEffort?.let {
            require(it in SUPPORTED_REASONING_EFFORTS) {
                "Unsupported reasoningEffort: $it. Must be one of $SUPPORTED_REASONING_EFFORTS."
            }
        }
    }

    /**
     * 运行时严格获取 Profile：未知或已禁用角色明确报错，不静默回退到 general。
     */
    fun requireProfile(id: String): SubagentProfile {
        val key = id.trim().lowercase()
        require(key.isNotEmpty()) { "Profile id must not be blank." }
        val found = profiles.firstOrNull { it.id.trim().lowercase() == key }
            ?: throw IllegalArgumentException("Unknown subagent profile: $id")
        require(found.enabled) {
            "Subagent profile '$id' is disabled."
        }
        return found
    }

    companion object {
        val SUPPORTED_REASONING_EFFORTS: Set<String> = setOf(
            "none", "minimal", "low", "medium", "high", "xhigh"
        )
    }
}
