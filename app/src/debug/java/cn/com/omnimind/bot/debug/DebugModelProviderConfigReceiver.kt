package cn.com.omnimind.bot.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.OpenAiWireApi
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfig
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.GsonBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class DebugModelProviderConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val operation = intent.stringExtra("operation").ifBlank { OPERATION_CONFIGURE }

        scope.launch {
            try {
                val result = runCatching {
                    when (operation) {
                        OPERATION_QUERY -> queryState()
                        OPERATION_QUERY_BINDINGS -> queryBindings(intent)
                        "verify_bound_provider" -> verifyBoundProvider(intent)
                        OPERATION_CONFIGURE -> configure(appContext, intent)
                        OPERATION_BIND_EXISTING -> bindExisting(intent)
                        else -> error("unsupported operation: $operation")
                    }
                }.getOrElse { error ->
                    linkedMapOf<String, Any?>(
                        "success" to false,
                        "phase" to "exception",
                        "error_message" to error.message.orEmpty(),
                        "error_type" to error.javaClass.name,
                    )
                }
                val json = gson.toJson(result)
                File(appContext.filesDir, RESULT_FILE).writeText(json)
                // 针对只读绑定查询与恢复绑定，仅记录安全摘要，绝不打印任何 Provider URL、密钥或敏感凭据
                if (operation in setOf(OPERATION_QUERY_BINDINGS, OPERATION_BIND_EXISTING)) {
                    val count = (result["sceneBindings"] as? List<*>)?.size ?: 0
                    OmniLog.i(TAG, "operation=$operation success=${result["success"]} bindings_count=$count")
                } else {
                    OmniLog.i(TAG, "operation=$operation success=${result["success"]}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun configure(context: Context, intent: Intent?): Map<String, Any?> {
        val baseUrl = intent.stringExtra("baseUrl", "base_url")
        val apiKey = intent.stringExtra("apiKey", "api_key")
        val modelId = intent.stringExtra("modelId", "model_id")
        val profileId = intent.stringExtra("profileId", "profile_id")
            .ifBlank { DEFAULT_PROFILE_ID }
        val name = intent.stringExtra("name").ifBlank { DEFAULT_PROFILE_NAME }
        val protocolType = intent.stringExtra("protocolType", "protocol_type")
            .ifBlank { "openai_compatible" }
        val wireApi = intent.stringExtra("wireApi", "wire_api")
            .ifBlank { OpenAiWireApi.CHAT_COMPLETIONS }
        val sceneIds = parseSceneIds(intent.stringExtra("sceneIds", "scene_ids"))

        require(baseUrl.isNotBlank()) { "baseUrl is empty" }
        require(apiKey.isNotBlank()) { "apiKey is empty" }
        require(modelId.isNotBlank()) { "modelId is empty" }
        require(sceneIds.isNotEmpty()) { "sceneIds is empty" }
        require(protocolType == "openai_compatible") {
            "debug provider must use openai_compatible"
        }
        require(wireApi in setOf(OpenAiWireApi.CHAT_COMPLETIONS, OpenAiWireApi.RESPONSES)) {
            "debug provider must use chat_completions or responses"
        }

        val profile = ModelProviderConfigStore.saveProfile(
            id = profileId,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            sourceType = "custom",
            protocolType = protocolType,
            wireApi = wireApi,
        )
        ModelProviderConfigStore.setEditingProfile(profile.id)
        sceneIds.forEach { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profile.id,
                modelId = modelId,
            )
        }
        if (SceneOperationConfigStore.SCENE_ID in sceneIds) {
            SceneOperationConfigStore.saveConfig(
                SceneOperationConfig(useOfficialService = false)
            )
        }
        seedFlutterManualModelId(context, profile.id, modelId)

        return queryState() + mapOf(
            "configuredProfileId" to profile.id,
            "configuredModelId" to modelId,
            "configuredSceneIds" to sceneIds,
        )
    }

    /**
     * 安全绑定/精准恢复场景模型配置。
     * 仅修改 SceneModelBindingStore，返回纯 ID 绑定结果，不调用 queryState()，
     * 不返回或泄露任何 profiles、baseUrl 或凭据，亦不修改 Provider 结构或 SceneOperationConfig。
     */
    private fun bindExisting(intent: Intent?): Map<String, Any?> {
        val profileId = intent.stringExtra(
            "profileId", "profile_id", "providerProfileId", "provider_profile_id"
        )
        val modelId = intent.stringExtra("modelId", "model_id")
        val sceneIds = parseSceneIds(
            intent.stringExtra("sceneIds", "scene_ids", "sceneId", "scene_id")
        )
        require(profileId.isNotBlank()) { "profileId is empty" }
        require(modelId.isNotBlank()) { "modelId is empty" }
        val profile = ModelProviderConfigStore.getProfile(profileId)
            ?: error("provider is not configured: $profileId")
        require(profile.isConfigured()) { "provider is not configured: $profileId" }

        val boundEntries = sceneIds.map { sceneId ->
            SceneModelBindingStore.saveBinding(
                sceneId = sceneId,
                providerProfileId = profileId,
                modelId = modelId,
            )
            linkedMapOf(
                "sceneId" to sceneId,
                "providerProfileId" to profileId,
                "modelId" to modelId,
            )
        }

        return linkedMapOf(
            "success" to true,
            "boundExistingProfileId" to profileId,
            "boundExistingModelId" to modelId,
            "boundExistingSceneIds" to sceneIds,
            "sceneBindings" to boundEntries,
        )
    }

    /**
     * 安全只读查询场景模型绑定关系（sceneId, providerProfileId, modelId）。
     * 不调用 queryState()，不读取或打印任何 Provider 配置、BaseUrl 或凭据。
     * 支持通过 `sceneIds` 或 `sceneId` 传入白名单（例如 "scene.vlm.operation.primary"）进行精准过滤。
     */
    private fun queryBindings(intent: Intent?): Map<String, Any?> {
        val rawSceneIds = intent.stringExtra("sceneIds", "scene_ids", "sceneId", "scene_id")
        val requestedSceneIds = if (rawSceneIds.isNotBlank()) {
            rawSceneIds
                .split(',', ';', '\n')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        } else {
            emptySet()
        }

        val allBindings = SceneModelBindingStore.getBindingEntries()
        val filteredBindings = if (requestedSceneIds.isNotEmpty()) {
            allBindings.filter { it.sceneId in requestedSceneIds }
        } else {
            allBindings
        }

        return linkedMapOf(
            "success" to true,
            "sceneBindings" to filteredBindings.map { it.toPayload() },
        )
    }

    private fun queryState(): Map<String, Any?> = linkedMapOf(
        "success" to true,
        "editingProfileId" to ModelProviderConfigStore.getEditingProfileId(),
        "profiles" to ModelProviderConfigStore.listProfiles().map { it.toSafePayload() },
        "sceneBindings" to SceneModelBindingStore.getBindingEntries().map { it.toPayload() },
    )

    /** Uses the existing binding and credentials in-process; never exports a key. */
    private suspend fun verifyBoundProvider(intent: Intent?): Map<String, Any?> {
        val binding = SceneModelBindingStore.getBinding("scene.dispatch.model")
        val profileId = intent.stringExtra("profileId").ifBlank { binding?.providerProfileId.orEmpty() }
        val modelId = intent.stringExtra("modelId").ifBlank { binding?.modelId.orEmpty() }
        require(profileId.isNotBlank() && modelId.isNotBlank()) { "Provider and model required" }
        val profile = checkNotNull(ModelProviderConfigStore.getProfile(profileId))
        val results = listOf("chat_completions", "responses", "anthropic").map { wire ->
            val base = if (wire == "anthropic")
                cn.com.omnimind.bot.agent.runtime.normalizeClaudeCodeBaseUrl(profile.baseUrl)
                else profile.baseUrl
            val result = cn.com.omnimind.assists.controller.http.HttpController.checkProviderModelAvailability(
                model = modelId, apiBase = base, apiKey = profile.apiKey,
                customHeaders = profile.customHeaders,
                protocolType = if (wire == "anthropic") "anthropic" else "openai_compatible",
                wireApi = wire,
            )
            mapOf("wire" to wire, "available" to result.available, "code" to result.code,
                "message" to cn.com.omnimind.bot.agent.AgentRuntimeErrorSupport.safeDiagnosticMessage(
                    IllegalStateException(result.message)))
        }
        return mapOf("success" to true, "profileId" to profile.id,
            "modelId" to modelId, "checks" to results,
            "harnessAcceptance" to false)
    }

    private fun seedFlutterManualModelId(context: Context, profileId: String, modelId: String) {
        val preferences = context.getSharedPreferences(
            FLUTTER_PREFERENCES,
            Context.MODE_PRIVATE,
        )
        val current = runCatching {
            JSONObject(preferences.getString(FLUTTER_MANUAL_MODEL_IDS_KEY, null).orEmpty())
        }.getOrElse { JSONObject() }
        val modelIds = current.optJSONArray(profileId) ?: JSONArray()
        val exists = (0 until modelIds.length()).any { index ->
            modelIds.optString(index).trim() == modelId
        }
        if (!exists) modelIds.put(modelId)
        current.put(profileId, modelIds)
        preferences.edit().putString(FLUTTER_MANUAL_MODEL_IDS_KEY, current.toString()).apply()
    }

    private fun Intent?.stringExtra(vararg names: String): String {
        if (this == null) return ""
        names.forEach { name ->
            getStringExtra(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun parseSceneIds(raw: String): List<String> = raw
        .split(',', ';', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .ifEmpty { DEFAULT_SCENE_IDS }

    private fun ModelProviderProfile.toSafePayload(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "name" to name,
        "baseUrl" to baseUrl,
        "sourceType" to sourceType,
        "protocolType" to protocolType,
        "wireApi" to wireApi,
        "apiKeyConfigured" to apiKey.isNotBlank(),
        "configured" to isConfigured(),
    )

    private fun SceneModelBindingEntry.toPayload(): Map<String, Any?> = linkedMapOf(
        "sceneId" to sceneId,
        "providerProfileId" to providerProfileId,
        "modelId" to modelId,
    )

    companion object {
        private const val TAG = "DebugModelProviderConfigReceiver"
        private const val RESULT_FILE = "debug-model-provider-config-result.json"
        private const val OPERATION_CONFIGURE = "configure"
        private const val OPERATION_BIND_EXISTING = "bind_existing"
        private const val OPERATION_QUERY = "query"
        private const val OPERATION_QUERY_BINDINGS = "query_bindings"
        private const val DEFAULT_PROFILE_ID = "debug-runtime-provider"
        private const val DEFAULT_PROFILE_NAME = "OmniMind GPT 5.6 (Debug)"
        private const val FLUTTER_PREFERENCES = "FlutterSharedPreferences"
        private const val FLUTTER_MANUAL_MODEL_IDS_KEY = "flutter.manual_provider_model_ids_v2"
        private val DEFAULT_SCENE_IDS = listOf(
            "scene.dispatch.model",
            "scene.vlm.operation.primary",
            "scene.compactor.context.chat",
        )
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
