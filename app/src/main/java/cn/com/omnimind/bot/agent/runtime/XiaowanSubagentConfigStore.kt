package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.WeakHashMap

/**
 * 小万 Subagent 配置持久化存储与原子并发比较。
 * 使用 SharedPreferences 存储配置快照与 revision，仅存储 Provider/Model 引用，不存储凭据。
 * 显式维护 committed snapshot 缓存，防止 Android SharedPreferences commit 失败时脏内存状态被 read 接纳。
 */
class XiaowanSubagentConfigStore(
    context: Context,
    preferences: SharedPreferences? = null
) {
    private val appContext = context.applicationContext
    private val prefs = preferences ?: appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun read(): XiaowanSubagentConfig {
        synchronized(writeLock) {
            val cached = committedSnapshots[prefs]
            if (cached != null) {
                return cached.config
            }
            val rawJson = prefs.getString(KEY_CONFIG_JSON, null)
            val revision = prefs.getLong(KEY_REVISION, 0L)
            if (!prefs.contains(KEY_CONFIG_JSON)) {
                val defaultConfig = XiaowanSubagentConfig()
                committedSnapshots[prefs] = CommittedSnapshot(revision, "", defaultConfig)
                return defaultConfig
            }
            // 已存空白或损坏配置必须报错，绝不重新启用默认权限。
            require(!rawJson.isNullOrBlank()) { "已存的子代理配置为空，请检查配置。" }
            val config = parseConfigJson(rawJson)
            committedSnapshots[prefs] = CommittedSnapshot(revision, rawJson, config)
            return config
        }
    }

    fun readPayload(): Map<String, Any?> {
        synchronized(writeLock) {
            val config = read()
            val currentRevision = committedSnapshots[prefs]?.revision ?: prefs.getLong(KEY_REVISION, 0L)
            return mapOf(
                "agentId" to XIAOWAN_AGENT_ID,
                "kind" to XIAOWAN_KIND,
                "revision" to currentRevision,
                "subagent" to subagentConfigToMap(config)
            )
        }
    }

    fun write(content: String, expectedRevision: Long?): Map<String, Any?> {
        synchronized(writeLock) {
            read()
            val currentSnapshot = checkNotNull(committedSnapshots[prefs]) {
                "Committed snapshot state should be loaded before write."
            }
            val currentRevision = currentSnapshot.revision

            val validatedConfig = parseAndValidate(content)

            if (expectedRevision != null && expectedRevision != currentRevision) {
                throw IllegalArgumentException(
                    "Agent config changed concurrently (expected revision $expectedRevision, current $currentRevision)."
                )
            }

            val nextRevision = currentRevision + 1L
            val serialized = serializeConfig(validatedConfig)

            val committed = prefs.edit()
                .putString(KEY_CONFIG_JSON, serialized)
                .putLong(KEY_REVISION, nextRevision)
                .commit()
            check(committed) { "Failed to persist Xiaowan subagent configuration." }

            val newSnapshot = CommittedSnapshot(nextRevision, serialized, validatedConfig)
            committedSnapshots[prefs] = newSnapshot

            return mapOf(
                "agentId" to XIAOWAN_AGENT_ID,
                "kind" to XIAOWAN_KIND,
                "revision" to nextRevision,
                "subagent" to subagentConfigToMap(validatedConfig)
            )
        }
    }

    private fun parseAndValidate(content: String): XiaowanSubagentConfig {
        require(content.isNotBlank()) { "Configuration content cannot be empty." }
        val root = try {
            JsonParser.parseString(content)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid JSON content: ${e.message}", e)
        }
        require(root.isJsonObject) { "Configuration content must be a JSON object." }
        val rootObj = root.asJsonObject

        // 若外层包装了 subagent 对象且顶层无 profiles，则解包 subagent
        val targetObj = if (rootObj.has("subagent") && rootObj.get("subagent").isJsonObject && !rootObj.has("profiles")) {
            rootObj.getAsJsonObject("subagent")
        } else {
            rootObj
        }

        return parseConfigJsonObject(targetObj)
    }

    private fun parseConfigJson(rawJson: String): XiaowanSubagentConfig {
        val root = JsonParser.parseString(rawJson).asJsonObject
        return parseConfigJsonObject(root)
    }

    private fun parseConfigJsonObject(obj: JsonObject): XiaowanSubagentConfig {
        val enabled = if (obj.has("enabled") && !obj.get("enabled").isJsonNull) {
            val elem = obj.get("enabled")
            require(elem.isJsonPrimitive && elem.asJsonPrimitive.isBoolean) {
                "Field 'enabled' must be a boolean."
            }
            elem.asBoolean
        } else {
            true
        }

        val providerProfileId = if (obj.has("providerProfileId") && !obj.get("providerProfileId").isJsonNull) {
            obj.get("providerProfileId").asString.trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val modelId = if (obj.has("modelId") && !obj.get("modelId").isJsonNull) {
            obj.get("modelId").asString.trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }

        require((providerProfileId == null && modelId == null) || (providerProfileId != null && modelId != null)) {
            "providerProfileId and modelId must both be null or both non-null."
        }

        val reasoningEffort = if (obj.has("reasoningEffort") && !obj.get("reasoningEffort").isJsonNull) {
            val raw = obj.get("reasoningEffort").asString.trim().lowercase()
            if (raw.isEmpty() || raw == "inherit") {
                null
            } else {
                require(raw in XiaowanSubagentConfig.SUPPORTED_REASONING_EFFORTS) {
                    "Unsupported reasoningEffort: $raw. Must be one of ${XiaowanSubagentConfig.SUPPORTED_REASONING_EFFORTS}."
                }
                raw
            }
        } else {
            null
        }

        val profiles = if (obj.has("profiles") && !obj.get("profiles").isJsonNull) {
            val array = obj.getAsJsonArray("profiles")
            array.mapIndexed { index, elem ->
                require(elem.isJsonObject) { "Profile at index $index must be an object." }
                parseProfile(elem.asJsonObject)
            }
        } else {
            SubagentProfileRegistry.all()
        }

        validateProfiles(profiles)

        return XiaowanSubagentConfig(
            enabled = enabled,
            providerProfileId = providerProfileId,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
            profiles = profiles
        )
    }

    private fun parseProfile(obj: JsonObject): SubagentProfile {
        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        require(id.isNotEmpty()) { "Profile id cannot be empty." }
        require(ID_REGEX.matches(id)) {
            "Profile id '$id' is invalid. Must match pattern ${ID_REGEX.pattern}."
        }

        val displayName = obj.get("displayName")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        require(displayName.isNotEmpty()) { "displayName cannot be empty for profile '$id'." }

        val systemPrompt = obj.get("systemPrompt")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        require(systemPrompt.isNotEmpty()) { "systemPrompt cannot be empty for profile '$id'." }

        val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()

        val rawBase = obj.get("baseProfileId")?.takeIf { !it.isJsonNull }?.asString?.trim()
        val baseProfileId = if (rawBase.isNullOrEmpty()) {
            if (SubagentProfileRegistry.isBuiltIn(id)) id else "general"
        } else {
            rawBase
        }

        val enabled = if (obj.has("enabled") && !obj.get("enabled").isJsonNull) {
            val elem = obj.get("enabled")
            require(elem.isJsonPrimitive && elem.asJsonPrimitive.isBoolean) {
                "Field 'enabled' in profile '$id' must be a boolean."
            }
            elem.asBoolean
        } else {
            true
        }

        val allowedTools = if (obj.has("allowedTools") && !obj.get("allowedTools").isJsonNull) {
            obj.getAsJsonArray("allowedTools").map { elem ->
                val name = elem.asString.trim()
                require(name.isNotEmpty()) { "Tool name in allowedTools cannot be empty." }
                require(name != "subagent_dispatch") {
                    "subagent_dispatch recursion is forbidden in allowedTools."
                }
                name
            }
        } else {
            null
        }

        val disallowedTools = if (obj.has("disallowedTools") && !obj.get("disallowedTools").isJsonNull) {
            obj.getAsJsonArray("disallowedTools").map { elem ->
                val name = elem.asString.trim()
                require(name.isNotEmpty()) { "Tool name in disallowedTools cannot be empty." }
                name
            }
        } else {
            emptyList()
        }

        return SubagentProfile(
            id = id,
            displayName = displayName,
            systemPrompt = systemPrompt,
            description = description,
            baseProfileId = baseProfileId,
            enabled = enabled,
            allowedTools = allowedTools,
            disallowedTools = disallowedTools
        )
    }

    private fun validateProfiles(profiles: List<SubagentProfile>) {
        val ids = profiles.map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate profile IDs detected: $duplicates" }

        // 保留默认内置角色，不允许删除
        for (builtInId in SubagentProfileRegistry.BUILT_IN_IDS) {
            require(ids.contains(builtInId)) {
                "Built-in profile '$builtInId' cannot be deleted."
            }
        }

        // 内置角色不可更改为更宽权限；自定义角色的 baseProfileId 必须为合法的内置角色
        for (profile in profiles) {
            if (SubagentProfileRegistry.isBuiltIn(profile.id)) {
                require(profile.baseProfileId == profile.id) {
                    "Built-in profile '${profile.id}' cannot change baseProfileId (must be '${profile.id}', was '${profile.baseProfileId}')."
                }
            } else {
                require(SubagentProfileRegistry.isBuiltIn(profile.baseProfileId)) {
                    "Custom profile '${profile.id}' has invalid baseProfileId '${profile.baseProfileId}'. Must be one of ${SubagentProfileRegistry.BUILT_IN_IDS}."
                }
            }
        }

        // 至少一个 enabled profile，且 general 必须存在且 enabled
        val general = profiles.firstOrNull { it.id == "general" }
        require(general != null && general.enabled) {
            "The 'general' profile must exist and remain enabled."
        }
        require(profiles.any { it.enabled }) {
            "At least one profile must be enabled."
        }
    }

    private fun serializeConfig(config: XiaowanSubagentConfig): String {
        val obj = JsonObject()
        obj.addProperty("enabled", config.enabled)
        config.providerProfileId?.let { obj.addProperty("providerProfileId", it) }
        config.modelId?.let { obj.addProperty("modelId", it) }
        config.reasoningEffort?.let { obj.addProperty("reasoningEffort", it) }

        val profilesArray = JsonArray()
        for (p in config.profiles) {
            val pObj = JsonObject()
            pObj.addProperty("id", p.id)
            pObj.addProperty("displayName", p.displayName)
            pObj.addProperty("systemPrompt", p.systemPrompt)
            pObj.addProperty("description", p.description)
            pObj.addProperty("baseProfileId", p.baseProfileId)
            pObj.addProperty("enabled", p.enabled)
            if (p.allowedTools != null) {
                val tools = JsonArray()
                p.allowedTools.forEach { tools.add(it) }
                pObj.add("allowedTools", tools)
            } else {
                pObj.add("allowedTools", JsonNull.INSTANCE)
            }
            val disallowed = JsonArray()
            p.disallowedTools.forEach { disallowed.add(it) }
            pObj.add("disallowedTools", disallowed)
            profilesArray.add(pObj)
        }
        obj.add("profiles", profilesArray)
        return gson.toJson(obj)
    }

    private fun subagentConfigToMap(config: XiaowanSubagentConfig): Map<String, Any?> {
        return linkedMapOf(
            "enabled" to config.enabled,
            "providerProfileId" to config.providerProfileId,
            "modelId" to config.modelId,
            "reasoningEffort" to config.reasoningEffort,
            "profiles" to config.profiles.map { profile ->
                linkedMapOf(
                    "id" to profile.id,
                    "displayName" to profile.displayName,
                    "systemPrompt" to profile.systemPrompt,
                    "description" to profile.description,
                    "baseProfileId" to profile.baseProfileId,
                    "enabled" to profile.enabled,
                    "allowedTools" to profile.allowedTools,
                    "disallowedTools" to profile.disallowedTools
                )
            }
        )
    }

    private data class CommittedSnapshot(
        val revision: Long,
        val rawJson: String,
        val config: XiaowanSubagentConfig
    )

    companion object {
        const val PREFS_NAME = "xiaowan_subagent_config"
        private val writeLock = Any()
        private val committedSnapshots = WeakHashMap<SharedPreferences, CommittedSnapshot>()
        const val KEY_CONFIG_JSON = "config_json"
        const val KEY_REVISION = "revision"
        const val XIAOWAN_AGENT_ID = "xiaowan-acp"
        const val XIAOWAN_KIND = "xiaowan"
        val ID_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    }
}
