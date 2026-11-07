@file:OptIn(com.agentclientprotocol.annotations.UnstableApi::class)

package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.*

/** Session-owned values exposed exclusively through the official ACP config surface. */
internal class XiaowanSessionConfig(
    private var models: List<ModelInfo>,
    initialModel: String,
    initialMode: String = DEFAULT_MODE,
) {
    var model: String = initialModel
        private set
    var effort: String = "default"
        private set
    var mode: String = initialMode
        private set
    val requestEffort: String? get() = effort.takeUnless { it == "default" }

    fun replaceModels(updated: List<ModelInfo>) {
        models = (updated + ModelInfo(ModelId(model), model)).distinctBy { it.modelId }
    }

    val options: List<SessionConfigOption>
        get() = listOf(
            select("model", "Model", SessionConfigOptionCategory.MODEL, model,
                models.map { it.modelId.value to it.name }),
            select("mode", "Mode", SessionConfigOptionCategory.MODE, mode,
                MODES.map { it.first to it.second }),
            select("reasoning_effort", "Reasoning effort", SessionConfigOptionCategory.THOUGHT_LEVEL,
                effort, listOf("default" to "Model default", "none" to "Off", "low" to "Low",
                    "medium" to "Medium", "high" to "High", "max" to "Maximum")),
        )

    fun set(id: String, value: SessionConfigOptionValue) {
        val option = options.firstOrNull { it.id.value == id } as? SessionConfigOption.Select
            ?: throw IllegalArgumentException("Unknown ACP config option: $id")
        val selected = (value as? SessionConfigOptionValue.StringValue)?.value
            ?: throw IllegalArgumentException("ACP config option $id requires a string")
        require((option.options as SessionConfigSelectOptions.Flat).options.any { it.value.value == selected }) {
            "Invalid value for ACP config option: $id"
        }
        when (id) {
            "model" -> model = selected
            "mode" -> mode = selected
            "reasoning_effort" -> effort = selected
        }
    }

    companion object {
        const val DEFAULT_MODE = "default"
        const val FULL_ACCESS_MODE = "agent_full_access"
        const val READ_ONLY_MODE = "read-only"

        val MODES = listOf(
            DEFAULT_MODE to "默认模式",
            FULL_ACCESS_MODE to "全权模式",
            READ_ONLY_MODE to "只读模式",
        )
    }

    private fun select(
        id: String, name: String, category: SessionConfigOptionCategory,
        current: String, values: List<Pair<String, String>>,
    ) = SessionConfigOption.Select(
        id = SessionConfigId(id), name = name, category = category,
        currentValue = SessionConfigValueId(current),
        options = SessionConfigSelectOptions.Flat(values.map { (value, label) ->
            SessionConfigSelectOption(value = SessionConfigValueId(value), name = label)
        }),
    )
}
