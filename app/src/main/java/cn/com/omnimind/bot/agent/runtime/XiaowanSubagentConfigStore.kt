package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
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

    fun manage(
        action: String,
        profileId: String? = null,
        profilePatch: JsonObject? = null,
        settingsPatch: JsonObject? = null,
        expectedRevision: Long? = null
    ): Pair<Map<String, Any?>, XiaowanSubagentConfig> {
        val actionNorm = action.trim().lowercase()
        require(actionNorm in SUPPORTED_MANAGE_ACTIONS) {
            "Unsupported manage action '$action'. Must be one of $SUPPORTED_MANAGE_ACTIONS."
        }

        synchronized(writeLock) {
            if (actionNorm == "list") {
                require(profileId == null) { "Action 'list' does not accept profileId." }
                require(profilePatch == null) { "Action 'list' does not accept profile patch." }
                require(settingsPatch == null) { "Action 'list' does not accept settings patch." }
                return Pair(readPayload(), read())
            }

            requireNotNull(expectedRevision) {
                "Field 'expectedRevision' is required for write action '$actionNorm'."
            }

            val currentConfig = read()
            val rootObj = JsonParser.parseString(serializeConfig(currentConfig)).asJsonObject
            val profilesArray = rootObj.getAsJsonArray("profiles")

            when (actionNorm) {
                "create" -> {
                    require(settingsPatch == null) { "Action 'create' does not accept settings patch." }
                    require(!profileId.isNullOrBlank()) { "Field 'profileId' is required for create." }
                    val id = profileId.trim()
                    require(ID_REGEX.matches(id)) {
                        "Profile id '$id' is invalid. Must match pattern ${ID_REGEX.pattern}."
                    }
                    require(!SubagentProfileRegistry.isBuiltIn(id)) {
                        "Cannot create built-in profile '$id'."
                    }
                    require(currentConfig.profiles.none { it.id == id }) {
                        "Profile with id '$id' already exists."
                    }
                    requireNotNull(profilePatch) { "Field 'profile' is required for create." }
                    validateProfilePatch(profilePatch, id, isCreate = true)

                    val newProfileObj = JsonObject().apply {
                        addProperty("id", id)
                        for (entry in profilePatch.entrySet()) {
                            if (entry.key != "id") {
                                add(entry.key, entry.value)
                            }
                        }
                        if (!has("baseProfileId")) {
                            addProperty("baseProfileId", "general")
                        }
                        if (!has("enabled")) {
                            addProperty("enabled", true)
                        }
                        if (!has("disallowedTools")) {
                            add("disallowedTools", JsonArray())
                        }
                    }
                    profilesArray.add(newProfileObj)
                }
                "update" -> {
                    require(settingsPatch == null) { "Action 'update' does not accept settings patch." }
                    require(!profileId.isNullOrBlank()) { "Field 'profileId' is required for update." }
                    val id = profileId.trim()
                    requireNotNull(profilePatch) { "Field 'profile' is required for update." }
                    validateProfilePatch(profilePatch, id, isCreate = false)

                    val targetProfileObj = profilesArray.firstOrNull { elem ->
                        elem.isJsonObject && elem.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id
                    }?.asJsonObject ?: throw IllegalArgumentException("Profile with id '$id' not found in config.")

                    for (entry in profilePatch.entrySet()) {
                        if (entry.key != "id") {
                            targetProfileObj.add(entry.key, entry.value)
                        }
                    }
                }
                "delete" -> {
                    require(profilePatch == null) { "Action 'delete' does not accept profile patch." }
                    require(settingsPatch == null) { "Action 'delete' does not accept settings patch." }
                    require(!profileId.isNullOrBlank()) { "Field 'profileId' is required for delete." }
                    val id = profileId.trim()
                    require(!SubagentProfileRegistry.isBuiltIn(id)) {
                        "Built-in profile '$id' cannot be deleted."
                    }
                    require(currentConfig.profiles.any { it.id == id }) {
                        "Profile with id '$id' does not exist."
                    }
                    var removeIndex = -1
                    for (i in 0 until profilesArray.size()) {
                        val elem = profilesArray.get(i)
                        if (elem.isJsonObject && elem.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id) {
                            removeIndex = i
                            break
                        }
                    }
                    if (removeIndex >= 0) {
                        profilesArray.remove(removeIndex)
                    }
                }
                "configure" -> {
                    require(profileId == null) { "Action 'configure' does not accept profileId." }
                    require(profilePatch == null) { "Action 'configure' does not accept profile patch." }
                    requireNotNull(settingsPatch) { "Field 'settings' is required for configure." }
                    validateSettingsPatch(settingsPatch)

                    for (entry in settingsPatch.entrySet()) {
                        rootObj.add(entry.key, entry.value)
                    }
                }
                else -> throw IllegalArgumentException("Unsupported manage action '$actionNorm'.")
            }

            val payload = write(rootObj.toString(), expectedRevision)
            val newConfig = read()
            return Pair(payload, newConfig)
        }
    }

    private fun validateProfilePatch(patch: JsonObject, id: String, isCreate: Boolean) {
        for (entry in patch.entrySet()) {
            require(entry.key in ALLOWED_PROFILE_KEYS) {
                "Unknown field '${entry.key}' in profile patch."
            }
            require(!entry.value.isJsonNull || entry.key in setOf("allowedTools", "providerProfileId", "modelId")) {
                "角色字段 '${entry.key}' 不接受 null；请省略该字段以保留原值。"
            }
        }
        if (patch.has("id") && !patch.get("id").isJsonNull) {
            val patchId = requireString(patch.get("id"), "id", "profile")
            require(patchId == id) { "Field 'profile.id' ($patchId) must match profileId ($id)." }
        }
        if (isCreate) {
            require(patch.has("displayName")) { "Field 'displayName' is required for create." }
            require(patch.has("systemPrompt")) { "Field 'systemPrompt' is required for create." }
        }
        if (patch.has("displayName") && !patch.get("displayName").isJsonNull) {
            requireString(patch.get("displayName"), "displayName", "profile '$id'")
        }
        if (patch.has("systemPrompt") && !patch.get("systemPrompt").isJsonNull) {
            requireString(patch.get("systemPrompt"), "systemPrompt", "profile '$id'")
        }
        if (patch.has("description") && !patch.get("description").isJsonNull) {
            val elem = patch.get("description")
            require(elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                "Field 'description' in profile '$id' must be a string."
            }
        }
        if (patch.has("baseProfileId") && !patch.get("baseProfileId").isJsonNull) {
            val base = requireString(patch.get("baseProfileId"), "baseProfileId", "profile '$id'")
            require(SubagentProfileRegistry.isBuiltIn(base)) {
                "Invalid baseProfileId '$base'. Must be one of ${SubagentProfileRegistry.BUILT_IN_IDS}."
            }
        }
        if (patch.has("enabled") && !patch.get("enabled").isJsonNull) {
            val elem = patch.get("enabled")
            require(elem.isJsonPrimitive && elem.asJsonPrimitive.isBoolean) {
                "Field 'enabled' in profile '$id' must be a boolean."
            }
        }
        if (patch.has("allowedTools") && !patch.get("allowedTools").isJsonNull) {
            val elem = patch.get("allowedTools")
            require(elem.isJsonArray) { "Field 'allowedTools' in profile '$id' must be an array or null." }
            elem.asJsonArray.forEach { tool ->
                require(tool.isJsonPrimitive && tool.asJsonPrimitive.isString) {
                    "Tool name in allowedTools of profile '$id' must be a string."
                }
                val name = tool.asString.trim()
                require(name.isNotEmpty()) { "Tool name in allowedTools cannot be empty." }
                require(name !in FORBIDDEN_SUBAGENT_TOOLS) {
                    "Tool '$name' cannot be granted in allowedTools."
                }
            }
        }
        if (patch.has("disallowedTools") && !patch.get("disallowedTools").isJsonNull) {
            val elem = patch.get("disallowedTools")
            require(elem.isJsonArray) { "Field 'disallowedTools' in profile '$id' must be an array." }
            elem.asJsonArray.forEach { tool ->
                require(tool.isJsonPrimitive && tool.asJsonPrimitive.isString) {
                    "Tool name in disallowedTools of profile '$id' must be a string."
                }
                require(tool.asString.trim().isNotEmpty()) { "Tool name in disallowedTools cannot be empty." }
            }
        }
        val hasProvider = patch.has("providerProfileId")
        val hasModel = patch.has("modelId")
        if (hasProvider || hasModel) {
            require(hasProvider && hasModel) {
                "Field 'providerProfileId' and 'modelId' in profile '$id' must both be provided or both omitted."
            }
            val pNull = patch.get("providerProfileId").isJsonNull
            val mNull = patch.get("modelId").isJsonNull
            require(pNull == mNull) {
                "Field 'providerProfileId' and 'modelId' in profile '$id' must both be null (to clear) or both non-null (to set)."
            }
            if (!pNull) {
                requireString(patch.get("providerProfileId"), "providerProfileId", "profile '$id'")
                requireString(patch.get("modelId"), "modelId", "profile '$id'")
            }
        }
    }

    private fun validateSettingsPatch(patch: JsonObject) {
        for (entry in patch.entrySet()) {
            require(entry.key in ALLOWED_SETTINGS_KEYS) {
                "Unknown field '${entry.key}' in settings patch."
            }
            require(!entry.value.isJsonNull || entry.key in setOf("providerProfileId", "modelId", "reasoningEffort")) {
                "全局字段 '${entry.key}' 不接受 null；请省略该字段以保留原值。"
            }
        }
        if (patch.has("enabled") && !patch.get("enabled").isJsonNull) {
            val elem = patch.get("enabled")
            require(elem.isJsonPrimitive && elem.asJsonPrimitive.isBoolean) {
                "Field 'enabled' in settings must be a boolean."
            }
        }
        val hasProvider = patch.has("providerProfileId")
        val hasModel = patch.has("modelId")
        if (hasProvider || hasModel) {
            require(hasProvider && hasModel) {
                "Field 'providerProfileId' and 'modelId' in settings must both be provided or both omitted."
            }
            val pNull = patch.get("providerProfileId").isJsonNull
            val mNull = patch.get("modelId").isJsonNull
            require(pNull == mNull) {
                "Field 'providerProfileId' and 'modelId' in settings must both be null (to clear) or both non-null (to set)."
            }
            if (!pNull) {
                requireString(patch.get("providerProfileId"), "providerProfileId", "settings")
                requireString(patch.get("modelId"), "modelId", "settings")
            }
        }
        if (patch.has("reasoningEffort") && !patch.get("reasoningEffort").isJsonNull) {
            val raw = requireString(patch.get("reasoningEffort"), "reasoningEffort", "settings").lowercase()
            if (raw.isNotEmpty() && raw != "inherit") {
                require(raw in XiaowanSubagentConfig.SUPPORTED_REASONING_EFFORTS) {
                    "Unsupported reasoningEffort: $raw. Must be one of ${XiaowanSubagentConfig.SUPPORTED_REASONING_EFFORTS}."
                }
            }
        }
    }

    private fun requireString(elem: JsonElement?, fieldName: String, context: String): String {
        requireNotNull(elem) { "Field '$fieldName' in $context is required." }
        require(!elem.isJsonNull) { "Field '$fieldName' in $context cannot be null." }
        require(elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
            "Field '$fieldName' in $context must be a string."
        }
        val s = elem.asString.trim()
        require(s.isNotEmpty()) { "Field '$fieldName' in $context cannot be blank." }
        return s
    }

    private fun optionalString(elem: JsonElement?, fieldName: String, context: String): String? {
        if (elem == null || elem.isJsonNull) return null
        require(elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
            "Field '$fieldName' in $context must be a string."
        }
        return elem.asString.trim()
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

        val hasTopProvider = obj.has("providerProfileId") && !obj.get("providerProfileId").isJsonNull
        val hasTopModel = obj.has("modelId") && !obj.get("modelId").isJsonNull

        val providerProfileId = if (hasTopProvider) {
            requireString(obj.get("providerProfileId"), "providerProfileId", "settings")
        } else {
            null
        }

        val modelId = if (hasTopModel) {
            requireString(obj.get("modelId"), "modelId", "settings")
        } else {
            null
        }

        require((providerProfileId == null && modelId == null) || (providerProfileId != null && modelId != null)) {
            "providerProfileId and modelId must both be null or both non-null."
        }

        val reasoningEffort = if (obj.has("reasoningEffort") && !obj.get("reasoningEffort").isJsonNull) {
            val raw = requireString(obj.get("reasoningEffort"), "reasoningEffort", "settings").lowercase()
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
        val id = requireString(obj.get("id"), "id", "profile")
        require(ID_REGEX.matches(id)) {
            "Profile id '$id' is invalid. Must match pattern ${ID_REGEX.pattern}."
        }

        val displayName = requireString(obj.get("displayName"), "displayName", "profile '$id'")
        val systemPrompt = requireString(obj.get("systemPrompt"), "systemPrompt", "profile '$id'")
        val description = optionalString(obj.get("description"), "description", "profile '$id'").orEmpty()

        val rawBase = optionalString(obj.get("baseProfileId"), "baseProfileId", "profile '$id'")
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
                require(name !in FORBIDDEN_SUBAGENT_TOOLS) {
                    "Tool '$name' cannot be granted in allowedTools."
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
        val hasRoleProvider = obj.has("providerProfileId") && !obj.get("providerProfileId").isJsonNull
        val hasRoleModel = obj.has("modelId") && !obj.get("modelId").isJsonNull

        val providerProfileId = if (hasRoleProvider) {
            requireString(obj.get("providerProfileId"), "providerProfileId", "profile '$id'")
        } else {
            null
        }

        val modelId = if (hasRoleModel) {
            requireString(obj.get("modelId"), "modelId", "profile '$id'")
        } else {
            null
        }

        require((providerProfileId == null && modelId == null) || (providerProfileId != null && modelId != null)) {
            "Profile '$id' providerProfileId and modelId must both be provided or both omitted."
        }

        return SubagentProfile(
            id = id,
            displayName = displayName,
            systemPrompt = systemPrompt,
            description = description,
            baseProfileId = baseProfileId,
            enabled = enabled,
            allowedTools = allowedTools,
            disallowedTools = disallowedTools,
            providerProfileId = providerProfileId,
            modelId = modelId
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
            if (p.providerProfileId != null) {
                pObj.addProperty("providerProfileId", p.providerProfileId)
            } else {
                pObj.add("providerProfileId", JsonNull.INSTANCE)
            }
            if (p.modelId != null) {
                pObj.addProperty("modelId", p.modelId)
            } else {
                pObj.add("modelId", JsonNull.INSTANCE)
            }
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
                    "disallowedTools" to profile.disallowedTools,
                    "providerProfileId" to profile.providerProfileId,
                    "modelId" to profile.modelId,
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
        val FORBIDDEN_SUBAGENT_TOOLS = setOf("subagent_dispatch", "subagent_manage")
        val SUPPORTED_MANAGE_ACTIONS = setOf("list", "create", "update", "delete", "configure")
        val ALLOWED_PROFILE_KEYS = setOf(
            "id", "displayName", "systemPrompt", "description",
            "baseProfileId", "enabled", "allowedTools", "disallowedTools",
            "providerProfileId", "modelId"
        )
        val ALLOWED_SETTINGS_KEYS = setOf(
            "enabled", "providerProfileId", "modelId", "reasoningEffort"
        )
    }
}
