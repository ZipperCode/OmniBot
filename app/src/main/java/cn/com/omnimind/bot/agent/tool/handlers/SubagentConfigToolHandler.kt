package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.XiaowanSubagentConfig
import cn.com.omnimind.bot.agent.XiaowanSubagentConfigStore
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `subagent_manage` — 管理小万专属子代理角色、全局配置与可用模型发现。
 *
 * 动作：
 * - list: 查询当前完整配置快照及 revision，并触发运行时配置同步。
 * - create: 新建自定义子代理角色（必须基于现有内置角色且不可删除内置角色）。
 * - update: 字段级更新已有角色（未提供字段保留原值，支持 null 覆盖清除成对模型）。
 * - delete: 删除自定义角色（内置角色不可删除）。
 * - configure: 更新顶层全局设置（总开关、默认模型、推理强度）。
 * - models: 只读查询本地缓存与手动添加的可用模型提供商及模型 ID 列表（严格安全脱敏）。
 */
class SubagentConfigToolHandler(
    private val helper: SharedHelper,
    private val configStore: XiaowanSubagentConfigStore,
    private val context: Context? = null,
    private val onConfigChanged: (XiaowanSubagentConfig) -> Unit = {},
) : ToolHandler {


    override val toolNames: Set<String> = setOf(TOOL_NAME)

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return try {
            helper.ensureRunActive()
            toolHandle.throwIfStopRequested()

            require(args.keys.all { it in ARGUMENT_NAMES }) { "子代理管理参数包含未知字段。" }
            val actionValue = args["action"] as? kotlinx.serialization.json.JsonPrimitive
            require(actionValue?.isString == true) { "action 必须是字符串。" }
            val action = actionValue.content.trim().lowercase()

            if (action == "models") {
                require(args.keys == setOf("action")) { "models 只接受 action 参数。" }
                val payload = readLocalProviderModels(context)
                val payloadJson = helper.encodeLocalizedPayload(payload)
                @Suppress("UNCHECKED_CAST")
                val providers = (payload["providers"] as? List<Map<String, Any?>>).orEmpty()
                val summary = "已查询本地可用模型提供商列表（共 ${providers.size} 个提供商）。"
                return ToolExecutionResult.ContextResult(
                    toolName = TOOL_NAME,
                    summaryText = helper.localized(summary),
                    previewJson = payloadJson,
                    rawResultJson = payloadJson,
                    success = true
                )
            }

            val revisionValue = args["expectedRevision"] as? kotlinx.serialization.json.JsonPrimitive
            val expectedRevision = revisionValue?.takeUnless { it.isString }?.longOrNull
            require(!args.containsKey("expectedRevision") || expectedRevision != null) {
                "expectedRevision 必须是整数。"
            }
            if (action != "list") {
                requireNotNull(expectedRevision) { "expectedRevision 必须在写操作（$action）中提供" }
            }

            val profileIdValue = args["profileId"] as? kotlinx.serialization.json.JsonPrimitive
            require(!args.containsKey("profileId") || profileIdValue?.isString == true) {
                "profileId 必须是字符串。"
            }
            val profileId = profileIdValue?.content?.trim()

            val profileGson = if (args.containsKey("profile")) {
                val p = args["profile"]
                require(p is JsonObject) { "profile 必须是 object" }
                JsonParser.parseString(p.toString()).asJsonObject
            } else null

            val settingsGson = if (args.containsKey("settings")) {
                val s = args["settings"]
                require(s is JsonObject) { "settings 必须是 object" }
                JsonParser.parseString(s.toString()).asJsonObject
            } else null

            toolHandle.throwIfStopRequested()

            val (payload, newConfig) = configStore.manage(
                action = action,
                profileId = profileId,
                profilePatch = profileGson,
                settingsPatch = settingsGson,
                expectedRevision = expectedRevision
            )

            // 写成功与 list 均触发回调同步最新状态
            onConfigChanged(newConfig)

            val summary = when (action) {
                "list" -> "已获取小万子代理配置（当前版本 revision: ${payload["revision"]}，共 ${newConfig.profiles.size} 个角色）。"
                "create" -> "已成功创建子代理角色 '$profileId' (版本 revision: ${payload["revision"]})。"
                "update" -> "已成功更新子代理角色 '$profileId' (版本 revision: ${payload["revision"]})。"
                "delete" -> "已成功删除子代理角色 '$profileId' (版本 revision: ${payload["revision"]})。"
                "configure" -> "已成功更新子代理全局设置 (版本 revision: ${payload["revision"]})。"
                else -> "已完成子代理配置操作 '$action'。"
            }

            val payloadJson = helper.encodeLocalizedPayload(payload)
            ToolExecutionResult.ContextResult(
                toolName = TOOL_NAME,
                summaryText = helper.localized(summary),
                previewJson = payloadJson,
                rawResultJson = payloadJson,
                success = true
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolExecutionResult.Error(
                TOOL_NAME,
                helper.localized(e.message ?: "subagent manage failed")
            )
        }
    }

    companion object {
        const val TOOL_NAME = "subagent_manage"
        private val ARGUMENT_NAMES = setOf("action", "profileId", "profile", "settings", "expectedRevision")

        fun definition(locale: PromptLocale = PromptLocale.ZH_CN): JsonObject {
            val isZh = locale == PromptLocale.ZH_CN
            return buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", TOOL_NAME)
                    put("displayName", if (isZh) "管理子代理" else "Manage Subagents")
                    put("toolType", "subagent")
                    put(
                        "description",
                        if (isZh)
                            "查询、创建、更新、删除小万子代理角色及配置全局设置，或查询本地可用模型列表。写操作必须传入当前的 expectedRevision（可先用 list 获取最新版本）。"
                        else
                            "List, create, update, delete Xiaowan subagent profiles, configure global settings, or list locally cached models. Write actions require expectedRevision."
                    )
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("action") {
                                put("type", "string")
                                putJsonArray("enum") {
                                    add("list")
                                    add("create")
                                    add("update")
                                    add("delete")
                                    add("configure")
                                    add("models")
                                }
                                put(
                                    "description",
                                    if (isZh) "要执行的管理动作：list（查询配置）、create（新建角色）、update（更新角色）、delete（删除自定义角色）、configure（全局设置）、models（查询本地可用模型列表）。"
                                    else "Action to perform: list, create, update, delete, configure, models."
                                )
                            }
                            putJsonObject("expectedRevision") {
                                put("type", "integer")
                                put(
                                    "description",
                                    if (isZh) "期望的当前配置版本号。写操作（create, update, delete, configure）必填，版本不一致时拒绝写入防止并发覆盖。list 返回当前 revision。"
                                    else "Expected current revision number. Required for write actions (create, update, delete, configure)."
                                )
                            }
                            putJsonObject("profileId") {
                                put("type", "string")
                                put(
                                    "description",
                                    if (isZh) "角色 ID（如 'code-reviewer'），必须由小写字母、数字和连字符组成。create、update、delete 时必填。内置角色（general, explorer, memory-curator, planner）不可删除。"
                                    else "Profile ID. Required for create, update, and delete. Built-in profiles cannot be deleted."
                                )
                            }
                            putJsonObject("profile") {
                                put("type", "object")
                                put(
                                    "description",
                                    "角色字段补丁；创建必填 displayName/systemPrompt。未提供字段保持原值，模型引用必须成对设置或同时传 null。权限只能收窄基础角色和父任务能力。"
                                )
                                putJsonObject("properties") {
                                    put("displayName", stringProperty("角色展示名称；创建必填，非空。"))
                                    put("systemPrompt", stringProperty("角色职责、工作方式和输出要求；创建必填，非空。"))
                                    put("description", stringProperty("供主 Agent 选择角色时参考的简短职责描述。"))
                                    putJsonObject("baseProfileId") {
                                        put("type", "string")
                                        put("description", "基础权限角色；新建默认 general，内置角色不能更改其基础角色。")
                                        putJsonArray("enum") {
                                            add("general")
                                            add("explorer")
                                            add("memory-curator")
                                            add("planner")
                                        }
                                    }
                                    putJsonObject("enabled") {
                                        put("type", "boolean")
                                        put("description", "是否允许使用此角色；general 必须启用。")
                                    }
                                    putJsonObject("allowedTools") {
                                        putJsonArray("type") { add("array"); add("null") }
                                        put("description", "null 继承基础权限，[] 禁用全部工具；指定名称仅收窄权限，不能包含 subagent_manage/subagent_dispatch。")
                                        putJsonObject("items") { put("type", "string") }
                                    }
                                    putJsonObject("disallowedTools") {
                                        put("type", "array")
                                        put("description", "额外禁止的工具名称，[] 清空禁止列表。")
                                        putJsonObject("items") { put("type", "string") }
                                    }
                                    put("providerProfileId", stringProperty("专用模型提供商 ID；先用 models 查询，和 modelId 成对设置或传 null 恢复继承。", nullable = true))
                                    put("modelId", stringProperty("专用模型 ID；和 providerProfileId 成对设置或传 null。", nullable = true))
                                }
                                put("additionalProperties", false)
                            }
                            putJsonObject("settings") {
                                put("type", "object")
                                put(
                                    "description",
                                    "configure 动作的全局设置补丁；不提供的字段保持原值，不自动开启分派。"
                                )
                                putJsonObject("properties") {
                                    putJsonObject("enabled") {
                                        put("type", "boolean")
                                        put("description", "子代理分派总开关，不影响管理工具或已启动的子任务。")
                                    }
                                    put("providerProfileId", stringProperty("默认子代理模型提供商 ID；和 modelId 成对设置或传 null 继承主 Agent。", nullable = true))
                                    put("modelId", stringProperty("默认子代理模型 ID；和 providerProfileId 成对设置或传 null。", nullable = true))
                                    putJsonObject("reasoningEffort") {
                                        putJsonArray("type") { add("string"); add("null") }
                                        put("description", "子代理思考档位；null/继承时跟随父任务。")
                                        putJsonArray("enum") {
                                            add("none"); add("minimal"); add("low"); add("medium"); add("high"); add("xhigh"); add("inherit")
                                            add(kotlinx.serialization.json.JsonNull)
                                        }
                                    }
                                }
                                put("additionalProperties", false)
                            }
                        }
                        putJsonArray("required") {
                            add("action")
                        }
                        put("additionalProperties", false)
                    }
                }
            }
        }

        private fun stringProperty(description: String, nullable: Boolean = false): JsonObject = buildJsonObject {
            if (nullable) {
                putJsonArray("type") { add("string"); add("null") }
            } else {
                put("type", "string")
            }
            put("description", description)
        }

        internal fun parseManualModelIds(rawJson: String?): Map<String, List<String>> {
            if (rawJson.isNullOrBlank()) return emptyMap()
            return runCatching {
                val root = JsonParser.parseString(rawJson).asJsonObject
                val result = mutableMapOf<String, List<String>>()
                for ((key, value) in root.entrySet()) {
                    if (value.isJsonArray) {
                        val models = value.asJsonArray.mapNotNull { elem ->
                            if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                                elem.asString.trim().ifEmpty { null }
                            } else null
                        }
                        result[key] = models
                    }
                }
                result
            }.getOrDefault(emptyMap())
        }

        internal fun readLocalProviderModels(
            ctx: Context?,
            profilesProvider: () -> List<ModelProviderProfile> = {
                ModelProviderConfigStore.listProfiles()
            },
            cachedModelsProvider: (Context, ModelProviderProfile) -> List<ProviderModelOption> = { c, p ->
                ModelProviderConfigStore.cachedModels(c, p)
            },
            manualModelsJsonProvider: () -> String? = {
                if (ctx != null) {
                    ctx.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                        .getString("flutter.manual_provider_model_ids_v2", null)
                } else null
            }
        ): Map<String, Any?> {
            val profiles = profilesProvider()
            val manualModelsByProfile = parseManualModelIds(manualModelsJsonProvider())

            val providersPayload = profiles.filter { it.isConfigured() }.map { profile ->
                val cached = if (ctx != null) {
                    cachedModelsProvider(ctx, profile).mapNotNull { it.id?.trim() }.filter { it.isNotEmpty() }
                } else emptyList()
                val manual = manualModelsByProfile[profile.id].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                val allModels = (cached + manual).distinct()

                // 严格白名单投影：仅输出 id/name/models，禁止序列化 apiKey、baseUrl、customHeaders 等敏感凭据
                linkedMapOf(
                    "id" to profile.id,
                    "name" to profile.name,
                    "models" to allModels
                )
            }

            return linkedMapOf(
                "source" to "local_cache_and_manual",
                "note" to "仅展示本地缓存与手动添加的模型列表，不进行网络请求。如需更多模型，请在应用设置中刷新提供商模型或手动添加。",
                "providers" to providersPayload
            )
        }
    }
}
