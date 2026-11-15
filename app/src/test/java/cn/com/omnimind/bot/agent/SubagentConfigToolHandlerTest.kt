package cn.com.omnimind.bot.agent

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.SubagentConfigToolHandler
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive as GsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * 针对 SubagentConfigToolHandler 与 XiaowanSubagentConfigStore 的安全 patch 操作、
 * 原子并发控制、字段保留、严格类型校验、错层拦截与防凭据泄露边界的聚焦消费者测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SubagentConfigToolHandlerTest {

    private fun createTestPreferences(): SharedPreferences {
        val storage = ConcurrentHashMap<String, Any>()
        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { invocation ->
            storage[invocation.getArgument<String>(0)] as? String ?: invocation.getArgument<String?>(1)
        }
        `when`(prefs.getLong(anyString(), anyLong())).thenAnswer { invocation ->
            (storage[invocation.getArgument<String>(0)] as? Number)?.toLong() ?: invocation.getArgument<Long>(1)
        }
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { invocation ->
            storage[invocation.getArgument<String>(0)] as? Boolean ?: invocation.getArgument<Boolean>(1)
        }
        `when`(prefs.contains(anyString())).thenAnswer { invocation ->
            storage.containsKey(invocation.getArgument<String>(0))
        }
        `when`(prefs.edit()).thenAnswer {
            val pending = mutableMapOf<String, Any?>()
            val editor = mock(SharedPreferences.Editor::class.java)
            `when`(editor.putString(anyString(), nullable(String::class.java))).thenAnswer {
                pending[it.getArgument(0)] = it.getArgument<String?>(1)
                editor
            }
            `when`(editor.putLong(anyString(), anyLong())).thenAnswer {
                pending[it.getArgument(0)] = it.getArgument<Long>(1)
                editor
            }
            `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer {
                pending[it.getArgument(0)] = it.getArgument<Boolean>(1)
                editor
            }
            `when`(editor.commit()).thenAnswer {
                pending.forEach { (k, v) ->
                    if (v == null) storage.remove(k) else storage[k] = v
                }
                true
            }
            `when`(editor.apply()).thenAnswer {
                pending.forEach { (k, v) ->
                    if (v == null) storage.remove(k) else storage[k] = v
                }
                Unit
            }
            editor
        }
        return prefs
    }

    private fun createTestContext(prefs: SharedPreferences = createTestPreferences()): Context {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        return context
    }

    @Test
    fun create_preserves_other_profiles_and_applies_defaults() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val patch = JsonObject().apply {
            addProperty("displayName", "代码审查员")
            addProperty("systemPrompt", "你专注于审查代码质量与安全风险。")
        }

        val (payload, newConfig) = store.manage(
            action = "create",
            profileId = "code-reviewer",
            profilePatch = patch,
            expectedRevision = 0L
        )

        assertEquals(1L, payload["revision"])
        assertEquals(5, newConfig.profiles.size)

        // 默认内置角色完整保留
        assertTrue(newConfig.profiles.any { it.id == "general" })
        assertTrue(newConfig.profiles.any { it.id == "explorer" })
        assertTrue(newConfig.profiles.any { it.id == "memory-curator" })
        assertTrue(newConfig.profiles.any { it.id == "planner" })

        // 新建角色应用默认值
        val created = newConfig.profiles.first { it.id == "code-reviewer" }
        assertEquals("代码审查员", created.displayName)
        assertEquals("general", created.baseProfileId)
        assertTrue(created.enabled)
        assertNull(created.allowedTools)
        assertTrue(created.disallowedTools.isEmpty())
        assertNull(created.providerProfileId)
        assertNull(created.modelId)
    }

    @Test
    fun update_preserves_unprovided_fields() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val createPatch = JsonObject().apply {
            addProperty("displayName", "初始名称")
            addProperty("systemPrompt", "初始提示词")
            addProperty("description", "初始描述")
            addProperty("baseProfileId", "explorer")
        }
        store.manage("create", "custom-role", createPatch, expectedRevision = 0L)

        // 仅修改 displayName
        val updatePatch = JsonObject().apply {
            addProperty("displayName", "已更新名称")
        }
        val (_, updatedConfig) = store.manage(
            action = "update",
            profileId = "custom-role",
            profilePatch = updatePatch,
            expectedRevision = 1L
        )

        val updated = updatedConfig.profiles.first { it.id == "custom-role" }
        assertEquals("已更新名称", updated.displayName)
        assertEquals("初始提示词", updated.systemPrompt)
        assertEquals("初始描述", updated.description)
        assertEquals("explorer", updated.baseProfileId)
    }

    @Test
    fun update_and_configure_clears_models_and_allowed_tools_when_null_passed() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val createPatch = JsonObject().apply {
            addProperty("displayName", "专用模型角色")
            addProperty("systemPrompt", "专属提示")
            addProperty("providerProfileId", "deepseek-provider")
            addProperty("modelId", "deepseek-coder")
            val tools = JsonArray().apply {
                add("file_read")
                add("memory_load")
            }
            add("allowedTools", tools)
        }
        store.manage("create", "dedicated-agent", createPatch, expectedRevision = 0L)

        // 使用 null 清除 allowedTools 和 provider/model 成对字段
        val clearPatch = JsonObject().apply {
            add("allowedTools", JsonNull.INSTANCE)
            add("providerProfileId", JsonNull.INSTANCE)
            add("modelId", JsonNull.INSTANCE)
        }
        val (_, clearedConfig) = store.manage(
            action = "update",
            profileId = "dedicated-agent",
            profilePatch = clearPatch,
            expectedRevision = 1L
        )

        val cleared = clearedConfig.profiles.first { it.id == "dedicated-agent" }
        assertNull(cleared.allowedTools)
        assertNull(cleared.providerProfileId)
        assertNull(cleared.modelId)

        // configure 顶层同样支持成对清除
        val topPatch = JsonObject().apply {
            addProperty("providerProfileId", "top-p")
            addProperty("modelId", "top-m")
        }
        store.manage("configure", settingsPatch = topPatch, expectedRevision = 2L)

        val clearTopPatch = JsonObject().apply {
            add("providerProfileId", JsonNull.INSTANCE)
            add("modelId", JsonNull.INSTANCE)
        }
        val (_, topClearedConfig) = store.manage("configure", settingsPatch = clearTopPatch, expectedRevision = 3L)
        assertNull(topClearedConfig.providerProfileId)
        assertNull(topClearedConfig.modelId)
    }

    @Test
    fun reject_duplicate_profile_id_on_create() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val patch = JsonObject().apply {
            addProperty("displayName", "重复通用角色")
            addProperty("systemPrompt", "提示词")
        }

        val result = runCatching {
            store.manage("create", "general", patch, expectedRevision = 0L)
        }
        assertTrue(result.isFailure)
        assertEquals(0L, (store.readPayload()["revision"] as Number).toLong())
    }

    @Test
    fun reject_delete_built_in_profiles() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        for (builtInId in listOf("general", "explorer", "memory-curator", "planner")) {
            val result = runCatching {
                store.manage("delete", profileId = builtInId, expectedRevision = 0L)
            }
            assertTrue("Should reject deleting $builtInId", result.isFailure)
        }
        assertEquals(0L, (store.readPayload()["revision"] as Number).toLong())
    }

    @Test
    fun reject_unknown_profile_on_update_or_delete() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val updateResult = runCatching {
            val patch = JsonObject().apply { addProperty("displayName", "未知角色") }
            store.manage("update", "non-existent-agent", patch, expectedRevision = 0L)
        }
        assertTrue(updateResult.isFailure)

        val deleteResult = runCatching {
            store.manage("delete", "non-existent-agent", expectedRevision = 0L)
        }
        assertTrue(deleteResult.isFailure)
        assertEquals(0L, (store.readPayload()["revision"] as Number).toLong())
    }

    @Test
    fun reject_stale_expected_revision_atomically() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val patch = JsonObject().apply {
            addProperty("displayName", "新角色")
            addProperty("systemPrompt", "提示词")
        }

        val staleResult = runCatching {
            store.manage("create", "agent-x", patch, expectedRevision = 99L)
        }
        assertTrue(staleResult.isFailure)
        assertTrue(staleResult.exceptionOrNull()?.message?.contains("concurrently") == true)
        assertEquals(0L, (store.readPayload()["revision"] as Number).toLong())
        assertEquals(4, store.read().profiles.size)
    }

    @Test
    fun reject_invalid_patches_atomically() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 1. 非法 profileId
        val invalidIdResult = runCatching {
            val patch = JsonObject().apply {
                addProperty("displayName", "非法ID")
                addProperty("systemPrompt", "提示")
            }
            store.manage("create", "Invalid_Id!", patch, expectedRevision = 0L)
        }
        assertTrue(invalidIdResult.isFailure)

        // 2. 非法 baseProfileId
        val invalidBaseResult = runCatching {
            val patch = JsonObject().apply {
                addProperty("displayName", "非法基底")
                addProperty("systemPrompt", "提示")
                addProperty("baseProfileId", "unknown-base")
            }
            store.manage("create", "bad-base-agent", patch, expectedRevision = 0L)
        }
        assertTrue(invalidBaseResult.isFailure)

        // 3. 模型单字段失配（仅传 providerProfileId 无 modelId）
        val invalidModelPairResult = runCatching {
            val patch = JsonObject().apply {
                addProperty("displayName", "失配模型")
                addProperty("systemPrompt", "提示")
                addProperty("providerProfileId", "my-provider")
            }
            store.manage("create", "mismatched-model-agent", patch, expectedRevision = 0L)
        }
        assertTrue(invalidModelPairResult.isFailure)

        // 4. 禁止在 allowedTools 中授予 subagent_dispatch 或 subagent_manage
        for (forbiddenTool in listOf("subagent_dispatch", "subagent_manage")) {
            val forbiddenResult = runCatching {
                val patch = JsonObject().apply {
                    addProperty("displayName", "违规提权角色")
                    addProperty("systemPrompt", "提示")
                    val tools = JsonArray().apply { add(forbiddenTool) }
                    add("allowedTools", tools)
                }
                store.manage("create", "privilege-escalation-agent", patch, expectedRevision = 0L)
            }
            assertTrue("Should reject granting $forbiddenTool", forbiddenResult.isFailure)
        }

        // 5. 补丁包含未知字段
        val unknownFieldResult = runCatching {
            val patch = JsonObject().apply {
                addProperty("displayName", "未知字段角色")
                addProperty("systemPrompt", "提示")
                addProperty("hackKey", "dangerous-value")
            }
            store.manage("create", "hack-agent", patch, expectedRevision = 0L)
        }
        assertTrue(unknownFieldResult.isFailure)

        assertEquals(0L, (store.readPayload()["revision"] as Number).toLong())
    }

    @Test
    fun reject_numbers_or_booleans_posing_as_strings() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 1. displayName 传入数字 42
        val numberDisplayNameResult = runCatching {
            val patch = JsonObject().apply {
                add("displayName", GsonPrimitive(42))
                addProperty("systemPrompt", "提示词")
            }
            store.manage("create", "num-agent", patch, expectedRevision = 0L)
        }
        assertTrue("Must reject number as displayName", numberDisplayNameResult.isFailure)

        // 2. systemPrompt 传入布尔值 true
        val booleanPromptResult = runCatching {
            val patch = JsonObject().apply {
                addProperty("displayName", "有效名称")
                add("systemPrompt", GsonPrimitive(true))
            }
            store.manage("create", "bool-agent", patch, expectedRevision = 0L)
        }
        assertTrue("Must reject boolean as systemPrompt", booleanPromptResult.isFailure)

        // 3. update 传入数字 displayName
        val createPatch = JsonObject().apply {
            addProperty("displayName", "初始名称")
            addProperty("systemPrompt", "初始提示")
        }
        store.manage("create", "test-agent", createPatch, expectedRevision = 0L)

        val updateNumberResult = runCatching {
            val patch = JsonObject().apply {
                add("displayName", GsonPrimitive(12345))
            }
            store.manage("update", "test-agent", patch, expectedRevision = 1L)
        }
        assertTrue("Must reject number in update displayName", updateNumberResult.isFailure)

        // 4. configure 传入布尔值 reasoningEffort
        val booleanReasoningResult = runCatching {
            val patch = JsonObject().apply {
                add("reasoningEffort", GsonPrimitive(false))
            }
            store.manage("configure", settingsPatch = patch, expectedRevision = 1L)
        }
        assertTrue("Must reject boolean as reasoningEffort", booleanReasoningResult.isFailure)

        assertEquals(1L, (store.readPayload()["revision"] as Number).toLong())
    }

    @Test
    fun `空值不能重新启用分派或扩大基础角色权限`() {
        val store = XiaowanSubagentConfigStore(createTestContext())
        store.manage("configure", settingsPatch = JsonObject().apply { addProperty("enabled", false) }, expectedRevision = 0L)
        store.manage("create", "read-only-worker", JsonObject().apply {
            addProperty("displayName", "只读角色")
            addProperty("systemPrompt", "只读检查")
            addProperty("baseProfileId", "explorer")
            addProperty("enabled", false)
        }, expectedRevision = 1L)
        val before = store.read()
        val nullPatch = JsonObject().apply { add("enabled", JsonNull.INSTANCE) }
        assertTrue(runCatching { store.manage("configure", settingsPatch = nullPatch, expectedRevision = 2L) }.isFailure)
        for (field in listOf("enabled", "baseProfileId", "displayName", "systemPrompt", "description", "disallowedTools")) {
            val patch = JsonObject().apply { add(field, JsonNull.INSTANCE) }
            assertTrue(field, runCatching { store.manage("update", "read-only-worker", patch, expectedRevision = 2L) }.isFailure)
        }
        assertEquals(before, store.read())
        assertEquals(2L, store.readPayload()["revision"])
    }

    @Test
    fun reject_misplaced_layer_fields_and_arguments() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 1. list 拒绝包含 profileId / profilePatch / settingsPatch
        assertTrue(runCatching {
            store.manage("list", profileId = "some-id")
        }.isFailure)
        assertTrue(runCatching {
            store.manage("list", profilePatch = JsonObject())
        }.isFailure)
        assertTrue(runCatching {
            store.manage("list", settingsPatch = JsonObject())
        }.isFailure)

        // 2. create / update 拒绝 settingsPatch 错层
        val validProfilePatch = JsonObject().apply {
            addProperty("displayName", "角色")
            addProperty("systemPrompt", "提示")
        }
        assertTrue(runCatching {
            store.manage("create", "r1", validProfilePatch, settingsPatch = JsonObject(), expectedRevision = 0L)
        }.isFailure)
        assertTrue(runCatching {
            store.manage("update", "general", validProfilePatch, settingsPatch = JsonObject(), expectedRevision = 0L)
        }.isFailure)

        // 3. delete 拒绝包含 profilePatch 或 settingsPatch
        assertTrue(runCatching {
            store.manage("delete", "r1", profilePatch = validProfilePatch, expectedRevision = 0L)
        }.isFailure)
        assertTrue(runCatching {
            store.manage("delete", "r1", settingsPatch = JsonObject(), expectedRevision = 0L)
        }.isFailure)

        // 4. configure 拒绝 profileId 或 profilePatch 错层
        assertTrue(runCatching {
            store.manage("configure", profileId = "r1", settingsPatch = JsonObject(), expectedRevision = 0L)
        }.isFailure)
        assertTrue(runCatching {
            store.manage("configure", profilePatch = validProfilePatch, settingsPatch = JsonObject(), expectedRevision = 0L)
        }.isFailure)
    }

    @Test
    fun configure_updates_settings_without_auto_enabling_or_modifying_profiles() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 先把总开关显式设为 false
        val disablePatch = JsonObject().apply { addProperty("enabled", false) }
        store.manage("configure", settingsPatch = disablePatch, expectedRevision = 0L)
        assertFalse(store.read().enabled)

        // 配置 reasoningEffort，保持 enabled 依旧为 false，不自动启用
        val reasoningPatch = JsonObject().apply { addProperty("reasoningEffort", "low") }
        val (_, afterConfig) = store.manage("configure", settingsPatch = reasoningPatch, expectedRevision = 1L)

        assertFalse("Total switch must remain false", afterConfig.enabled)
        assertEquals("low", afterConfig.reasoningEffort)
        assertEquals(4, afterConfig.profiles.size)
    }

    @Test
    fun models_action_strict_credential_leak_boundary() {
        val roboContext = RuntimeEnvironment.getApplication()
        val fakeProfile = ModelProviderProfile(
            id = "custom-provider-fixture",
            name = "Test Provider Official",
            apiKey = "mock-secret-key-fixture-12345",
            baseUrl = "https://mock.provider.local/v1",
            customHeaders = mapOf("Authorization" to "Bearer mock-secret-key-fixture-12345")
        )

        val manualModelsJson = """
            {
                "custom-provider-fixture": ["test-model-chat", "test-model-coder"]
            }
        """.trimIndent()

        val payload = SubagentConfigToolHandler.readLocalProviderModels(
            ctx = roboContext,
            profilesProvider = { listOf(fakeProfile) },
            cachedModelsProvider = { _, _ ->
                listOf(ProviderModelOption(id = "test-model-reasoner"))
            },
            manualModelsJsonProvider = { manualModelsJson }
        )

        @Suppress("UNCHECKED_CAST")
        val providers = payload["providers"] as List<Map<String, Any?>>
        assertEquals(1, providers.size)

        val provider = providers[0]
        assertEquals("custom-provider-fixture", provider["id"])
        assertEquals("Test Provider Official", provider["name"])

        @Suppress("UNCHECKED_CAST")
        val models = provider["models"] as List<String>
        assertEquals(listOf("test-model-reasoner", "test-model-chat", "test-model-coder"), models)

        // 严格安全边界验证：绝不暴露 apiKey、baseUrl、customHeaders
        val fullSerialized = payload.toString()
        assertFalse("Cannot contain apiKey", fullSerialized.contains("apiKey"))
        assertFalse("Cannot contain secret key", fullSerialized.contains("mock-secret-key-fixture-12345"))
        assertFalse("Cannot contain baseUrl", fullSerialized.contains("baseUrl"))
        assertFalse("Cannot contain sensitive host", fullSerialized.contains("mock.provider.local"))
        assertFalse("Cannot contain customHeaders", fullSerialized.contains("customHeaders"))
        assertFalse("Cannot contain Authorization", fullSerialized.contains("Authorization"))
    }

    @Test
    fun handler_execution_cancels_and_notifies_config_changed() = runBlocking {
        val roboContext = RuntimeEnvironment.getApplication()
        val prefs = createTestPreferences()
        val store = XiaowanSubagentConfigStore(roboContext, prefs)
        val json = Json { ignoreUnknownKeys = true }
        val helper = SharedHelper(roboContext, json)

        var latestNotifiedConfig: XiaowanSubagentConfig? = null
        val handler = SubagentConfigToolHandler(
            helper = helper,
            configStore = store,
            context = roboContext,
            onConfigChanged = { latestNotifiedConfig = it }
        )

        val toolCall = AssistantToolCall(
            id = "call-1",
            type = "function",
            function = AssistantToolCallFunction(name = "subagent_manage", arguments = "{}")
        )
        val runtimeDescriptor = AgentToolRegistry.RuntimeToolDescriptor(
            name = "subagent_manage",
            displayName = "管理子代理",
            toolType = "subagent"
        )
        val env = mock(AgentExecutionEnvironment::class.java)
        val callback = mock(AgentCallback::class.java)
        val handle = mock(AgentToolExecutionHandle::class.java)

        // 1. list 成功执行并触发 onConfigChanged
        val listArgs = buildJsonObject { put("action", "list") }
        val listResult = handler.execute(toolCall, listArgs, runtimeDescriptor, env, callback, handle)
        assertTrue(listResult is ToolExecutionResult.ContextResult)
        assertNotNull(latestNotifiedConfig)
        assertEquals(0L, (store.readPayload()["revision"] as Number).toLong())

        // 2. create 执行并触发 onConfigChanged
        val createArgs = buildJsonObject {
            put("action", "create")
            put("profileId", "qa-tester")
            put("expectedRevision", 0)
            putJsonObject("profile") {
                put("displayName", "QA测试")
                put("systemPrompt", "执行测试")
            }
        }
        val createResult = handler.execute(toolCall, createArgs, runtimeDescriptor, env, callback, handle)
        assertTrue(createResult is ToolExecutionResult.ContextResult)
        assertEquals(5, latestNotifiedConfig?.profiles?.size)
        assertTrue(latestNotifiedConfig?.profiles?.any { it.id == "qa-tester" } == true)

        // 3. 错误调用返回 ToolExecutionResult.Error 而非崩溃
        val badArgs = buildJsonObject {
            put("action", "delete")
            put("profileId", "general")
            put("expectedRevision", 1)
        }
        val errResult = handler.execute(toolCall, badArgs, runtimeDescriptor, env, callback, handle)
        assertTrue(errResult is ToolExecutionResult.Error)

        // 4. throwIfStopRequested 抛出 CancellationException 时必须如实向上传播
        `when`(handle.throwIfStopRequested()).thenThrow(CancellationException("Task cancelled"))
        var cancellationCaught = false
        try {
            handler.execute(toolCall, listArgs, runtimeDescriptor, env, callback, handle)
        } catch (e: CancellationException) {
            cancellationCaught = true
        }
        assertTrue("CancellationException must be preserved", cancellationCaught)
    }
}
