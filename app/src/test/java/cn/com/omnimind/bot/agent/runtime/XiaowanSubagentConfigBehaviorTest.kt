package cn.com.omnimind.bot.agent.runtime

import android.content.Context
import android.content.SharedPreferences
import cn.com.omnimind.bot.agent.SubagentProfile
import cn.com.omnimind.bot.agent.SubagentProfileRegistry
import cn.com.omnimind.bot.agent.XiaowanSubagentConfig
import cn.com.omnimind.bot.agent.XiaowanSubagentConfigStore
import cn.com.omnimind.baselib.llm.ChatCompletionFunction
import cn.com.omnimind.baselib.llm.ChatCompletionTool
import cn.com.omnimind.bot.agent.AgentToolCatalog
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.inheritedSubagentCatalog
import com.google.gson.Gson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.nullable
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.ConcurrentHashMap

/**
 * 针对小万 Subagent 配置持久化、原子并发控制、严格验证与防扩权行为的消费者测试。
 */
class XiaowanSubagentConfigBehaviorTest {

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
    fun `write valid configuration advances revision and persists snapshot`() {
        val context = createTestContext()
        val store1 = XiaowanSubagentConfigStore(context)

        val content = """
            {
              "enabled": true,
              "providerProfileId": "provider-custom",
              "modelId": "model-xyz",
              "reasoningEffort": "high",
              "profiles": [
                {
                  "id": "general",
                  "displayName": "定制通用角色",
                  "systemPrompt": "通用提示词",
                  "description": "通用描述",
                  "baseProfileId": "general",
                  "enabled": true
                },
                {
                  "id": "explorer",
                  "displayName": "探索者",
                  "systemPrompt": "探索提示词",
                  "baseProfileId": "explorer",
                  "enabled": false
                },
                {
                  "id": "memory-curator",
                  "displayName": "记忆管理",
                  "systemPrompt": "记忆提示词",
                  "baseProfileId": "memory-curator",
                  "enabled": true
                },
                {
                  "id": "planner",
                  "displayName": "规划器",
                  "systemPrompt": "规划提示词",
                  "baseProfileId": "planner",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val writeResult = store1.write(content, expectedRevision = 0L)
        assertEquals("xiaowan-acp", writeResult["agentId"])
        assertEquals("xiaowan", writeResult["kind"])
        assertEquals(1L, writeResult["revision"])

        // 新建 store 实例回读，验证持久化生效
        val store2 = XiaowanSubagentConfigStore(context)
        val reloaded = store2.read()
        assertTrue(reloaded.enabled)
        assertEquals("provider-custom", reloaded.providerProfileId)
        assertEquals("model-xyz", reloaded.modelId)
        assertEquals("high", reloaded.reasoningEffort)

        val general = reloaded.requireProfile("general")
        assertEquals("定制通用角色", general.displayName)
        assertEquals("通用提示词", general.systemPrompt)

        // 验证 explorer 已禁用，requireProfile 拒绝访问
        assertTrue(runCatching { reloaded.requireProfile("explorer") }.isFailure)
    }

    @Test
    fun `committed configuration reloads without a process snapshot cache`() {
        val persisted = createTestPreferences()
        val store = XiaowanSubagentConfigStore(createTestContext(persisted), persisted)
        store.write("""{"enabled":false,"providerProfileId":"saved-provider","modelId":"saved-model"}""", 0L)

        val restartedPreferences = createTestPreferences()
        restartedPreferences.edit()
            .putString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON,
                persisted.getString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, null))
            .putLong(XiaowanSubagentConfigStore.KEY_REVISION,
                persisted.getLong(XiaowanSubagentConfigStore.KEY_REVISION, 0L))
            .commit()
        val restarted = XiaowanSubagentConfigStore(createTestContext(restartedPreferences), restartedPreferences)

        assertEquals(false, restarted.read().enabled)
        assertEquals("saved-provider", restarted.read().providerProfileId)
        assertEquals("saved-model", restarted.read().modelId)
        assertEquals(1L, restarted.readPayload()["revision"])
    }

    @Test
    fun `stale revision across distinct store instances rejects write without losing existing configuration`() {
        val context = createTestContext()
        val managerStore = XiaowanSubagentConfigStore(context)
        val executorStore = XiaowanSubagentConfigStore(context)

        val initialContent = """
            {
              "enabled": true,
              "providerProfileId": "p1",
              "modelId": "m1",
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()

        managerStore.write(initialContent, expectedRevision = 0L)
        assertEquals(1L, managerStore.readPayload()["revision"])

        // executorStore 传入旧版本 0L 尝试写入，必须被拒绝
        val concurrentContent = """
            {
              "enabled": false,
              "profiles": [
                { "id": "general", "displayName": "Changed", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()

        val failure = runCatching { executorStore.write(concurrentContent, expectedRevision = 0L) }
        assertTrue(failure.isFailure)

        // 原配置未受破坏，版本仍保持 1L
        val preserved = managerStore.read()
        assertTrue(preserved.enabled)
        assertEquals("p1", preserved.providerProfileId)
        assertEquals(1L, managerStore.readPayload()["revision"])
    }

    @Test
    fun `built-in anti-privilege escalation prevents modifying baseProfileId or deleting built-ins`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 尝试将 planner 的权限基底变更为 general（扩权尝试），必须被拦截
        val escalateContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true }
              ]
            }
        """.trimIndent()

        assertTrue(runCatching { store.write(escalateContent, expectedRevision = 0L) }.isFailure)

        // 尝试删除内置角色 explorer，必须被拦截
        val deleteBuiltInContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()

        assertTrue(runCatching { store.write(deleteBuiltInContent, expectedRevision = 0L) }.isFailure)

        // 尝试禁用 general 角色，必须被拦截
        val disableGeneralContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": false },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()

        assertTrue(runCatching { store.write(disableGeneralContent, expectedRevision = 0L) }.isFailure)

        // 存储仍然保持原始默认配置
        assertEquals(0L, store.readPayload()["revision"])
    }

    @Test
    fun `custom profile validation and tool recursion check`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 1. 自定义 profile 允许在合法 baseProfileId 下创建
        val validCustomContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true },
                {
                  "id": "code-inspector",
                  "displayName": "代码审查员",
                  "systemPrompt": "审查代码安全",
                  "baseProfileId": "explorer",
                  "enabled": true,
                  "allowedTools": ["file_read"],
                  "disallowedTools": ["browser_use"]
                }
              ]
            }
        """.trimIndent()

        val result = store.write(validCustomContent, expectedRevision = 0L)
        assertEquals(1L, result["revision"])
        val loaded = store.read()
        val custom = loaded.requireProfile("code-inspector")
        assertEquals("explorer", custom.baseProfileId)
        assertEquals(listOf("file_read"), custom.allowedTools)
        assertEquals(listOf("browser_use"), custom.disallowedTools)

        // 2. 自定义 profile baseProfileId 非法，拒绝
        val invalidBaseContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true },
                {
                  "id": "bad-base",
                  "displayName": "非法基底",
                  "systemPrompt": "SP",
                  "baseProfileId": "root-super",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        assertTrue(runCatching { store.write(invalidBaseContent, expectedRevision = 1L) }.isFailure)

        // 3. 递归工具 subagent_dispatch 显式加入 allowedTools，拒绝
        val recursionContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true },
                {
                  "id": "recursive-child",
                  "displayName": "递归调用",
                  "systemPrompt": "SP",
                  "baseProfileId": "general",
                  "enabled": true,
                  "allowedTools": ["subagent_dispatch"]
                }
              ]
            }
        """.trimIndent()

        assertTrue(runCatching { store.write(recursionContent, expectedRevision = 1L) }.isFailure)

        // 验证失败未丢失旧配置（依然是 revision 1L）
        assertEquals(1L, store.readPayload()["revision"])
        assertNotNull(store.read().requireProfile("code-inspector"))
    }

    @Test
    fun `id pattern and duplicate checks protect configuration`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 非法 ID 格式（大写、下划线、空字符）
        for (badId in listOf("InvalidId", "my_role", "-leading", "trailing-", "")) {
            val content = """
                {
                  "profiles": [
                    { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                    { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                    { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                    { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true },
                    { "id": "$badId", "displayName": "B", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true }
                  ]
                }
            """.trimIndent()
            assertTrue("ID $badId should fail", runCatching { store.write(content, expectedRevision = 0L) }.isFailure)
        }

        // 重复 ID
        val duplicateContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true },
                { "id": "general", "displayName": "G2", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true }
              ]
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(duplicateContent, expectedRevision = 0L) }.isFailure)
    }

    @Test
    fun `provider and model pair validation ensures references only`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 仅提供 providerProfileId 无 modelId
        val missingModelContent = """
            {
              "providerProfileId": "provider-only",
              "modelId": null
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(missingModelContent, expectedRevision = 0L) }.isFailure)

        // 仅提供 modelId 无 providerProfileId
        val missingProviderContent = """
            {
              "providerProfileId": null,
              "modelId": "model-only"
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(missingProviderContent, expectedRevision = 0L) }.isFailure)

        // 两者都空表示继承主 Agent，合法
        val nullPairContent = """
            {
              "providerProfileId": null,
              "modelId": null
            }
        """.trimIndent()
        val writeResult = store.write(nullPairContent, expectedRevision = 0L)
        assertEquals(1L, writeResult["revision"])
        assertNull(store.read().providerProfileId)
        assertNull(store.read().modelId)
    }

    @Test
    fun `allowedTools null vs empty list semantic roundtrip`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        val content = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true, "allowedTools": null },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true, "allowedTools": [] },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()

        store.write(content, expectedRevision = 0L)
        val readConfig = store.read()
        // null 表示继承基础角色可用工具
        assertNull(readConfig.requireProfile("general").allowedTools)
        // emptyList 表示明确无可用工具
        assertEquals(emptyList<String>(), readConfig.requireProfile("explorer").allowedTools)
    }

    @Test
    fun `corrupt stored configuration causes read to throw instead of silently falling back to defaults`() {
        val prefs = createTestPreferences()
        prefs.edit()
            .putString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, "{ invalid json structure")
            .putLong(XiaowanSubagentConfigStore.KEY_REVISION, 3L)
            .commit()

        val context = createTestContext(prefs)
        val store = XiaowanSubagentConfigStore(context, prefs)

        // 已存配置损坏时必须抛出异常，绝不能静默重置为默认并重新启用
        assertTrue(runCatching { store.read() }.isFailure)
        assertTrue(runCatching { store.readPayload() }.isFailure)
    }

    @Test
    fun `blank persisted configuration is corruption rather than missing configuration`() {
        for (raw in listOf("", "   \n")) {
            val prefs = createTestPreferences()
            prefs.edit()
                .putString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, raw)
                .putLong(XiaowanSubagentConfigStore.KEY_REVISION, 3L)
                .commit()
            val store = XiaowanSubagentConfigStore(createTestContext(prefs), prefs)

            assertTrue(runCatching { store.read() }.isFailure)
            assertTrue(runCatching { store.readPayload() }.isFailure)
            assertEquals(raw, prefs.getString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, null))
            assertEquals(3L, prefs.getLong(XiaowanSubagentConfigStore.KEY_REVISION, 0L))
        }
    }

    @Test
    fun `strict boolean validation rejects string or numeric values for enabled flags`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 顶层 enabled 为字符串 "false"
        val stringEnabledContent = """
            {
              "enabled": "false"
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(stringEnabledContent, expectedRevision = 0L) }.isFailure)

        // 顶层 enabled 为数字 1
        val numberEnabledContent = """
            {
              "enabled": 1
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(numberEnabledContent, expectedRevision = 0L) }.isFailure)

        // profile 内的 enabled 为字符串 "true"
        val profileStringEnabledContent = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": "true" },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(profileStringEnabledContent, expectedRevision = 0L) }.isFailure)
    }

    @Test
    fun `failed SharedPreferences commit with mutated memory does not contaminate committed snapshot`() {
        val storage = ConcurrentHashMap<String, Any>()
        var shouldCommitFail = false

        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.getString(anyString(), nullable(String::class.java))).thenAnswer { invocation ->
            storage[invocation.getArgument<String>(0)] as? String ?: invocation.getArgument<String?>(1)
        }
        `when`(prefs.getLong(anyString(), anyLong())).thenAnswer { invocation ->
            (storage[invocation.getArgument<String>(0)] as? Number)?.toLong() ?: invocation.getArgument<Long>(1)
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
            `when`(editor.commit()).thenAnswer {
                // 模拟 Android 内存改变先发生，但持久化提交返回 false
                pending.forEach { (k, v) ->
                    if (v == null) storage.remove(k) else storage[k] = v
                }
                if (shouldCommitFail) false else true
            }
            editor
        }

        val context = createTestContext(prefs)
        val store = XiaowanSubagentConfigStore(context, prefs)

        // 1. 成功提交初始版本 1
        val initialContent = """
            {
              "enabled": true,
              "providerProfileId": "p-initial",
              "modelId": "m-initial"
            }
        """.trimIndent()
        store.write(initialContent, expectedRevision = 0L)
        assertEquals("p-initial", store.read().providerProfileId)
        assertEquals(1L, store.readPayload()["revision"])

        // 2. 模拟 commit 失败（返回 false，但 storage 内存已被污染）
        shouldCommitFail = true
        val dirtyContent = """
            {
              "enabled": false,
              "providerProfileId": "p-dirty",
              "modelId": "m-dirty"
            }
        """.trimIndent()

        val failure = runCatching { store.write(dirtyContent, expectedRevision = 1L) }
        assertTrue(failure.isFailure)

        // 3. 验证 store.read() 和 readPayload() 坚决不接纳脏内存状态，依然返回上一个已提交的 snapshot
        val safeConfig = store.read()
        assertTrue(safeConfig.enabled)
        assertEquals("p-initial", safeConfig.providerProfileId)
        assertEquals("m-initial", safeConfig.modelId)
        assertEquals(1L, store.readPayload()["revision"])
        val secondStore = XiaowanSubagentConfigStore(context, prefs)
        assertEquals(safeConfig, secondStore.read())
        assertEquals(1L, secondStore.readPayload()["revision"])
    }

    @Test
    fun `profile provider and model pair persistence roundtrip and clear`() {
        val prefs = createTestPreferences()
        val context = createTestContext(prefs)
        val store = XiaowanSubagentConfigStore(context, prefs)

        // 1. 保存包含角色显式模型与 disallowedTools 的配置
        val withModelContent = """
            {
              "enabled": true,
              "providerProfileId": "default-provider",
              "modelId": "default-model",
              "profiles": [
                {
                  "id": "general",
                  "displayName": "通用角色",
                  "systemPrompt": "通用提示词",
                  "baseProfileId": "general",
                  "enabled": true,
                  "providerProfileId": "role-provider-1",
                  "modelId": "role-model-1",
                  "disallowedTools": ["file_write"]
                },
                {
                  "id": "explorer",
                  "displayName": "探索者",
                  "systemPrompt": "探索提示词",
                  "baseProfileId": "explorer",
                  "enabled": true
                },
                {
                  "id": "memory-curator",
                  "displayName": "记忆管理",
                  "systemPrompt": "记忆提示词",
                  "baseProfileId": "memory-curator",
                  "enabled": true
                },
                {
                  "id": "planner",
                  "displayName": "规划器",
                  "systemPrompt": "规划提示词",
                  "baseProfileId": "planner",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        store.write(withModelContent, expectedRevision = 0L)
        val read1 = store.read()
        val general = read1.requireProfile("general")
        assertEquals("role-provider-1", general.providerProfileId)
        assertEquals("role-model-1", general.modelId)
        assertEquals(listOf("file_write"), general.disallowedTools)

        val explorer = read1.requireProfile("explorer")
        assertNull(explorer.providerProfileId)
        assertNull(explorer.modelId)

        // 2. 经过 readPayload 验证数据字典包含模型 pair 与 disallowedTools，并重新序列化保存
        val payload1 = store.readPayload()
        val profilesPayload1 = payload1["subagent"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val profilesList1 = profilesPayload1["profiles"] as List<Map<String, Any?>>
        val generalPayload = profilesList1.first { it["id"] == "general" }
        assertEquals("role-provider-1", generalPayload["providerProfileId"])
        assertEquals("role-model-1", generalPayload["modelId"])
        assertEquals(listOf("file_write"), generalPayload["disallowedTools"])

        // 将 payload 写回，版本推进到 2L
        val payloadJson = Gson().toJson(payload1)
        val write2Result = store.write(payloadJson, expectedRevision = 1L)
        assertEquals(2L, write2Result["revision"])

        // 3. 独立 preferences 实例冷读，验证非内存快照回声，并通过 catalog 验证 disallowedTools 真实生效
        val coldPrefs = createTestPreferences()
        coldPrefs.edit()
            .putString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, prefs.getString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, null))
            .putLong(XiaowanSubagentConfigStore.KEY_REVISION, prefs.getLong(XiaowanSubagentConfigStore.KEY_REVISION, 0L))
            .commit()
        val coldStore = XiaowanSubagentConfigStore(createTestContext(coldPrefs), coldPrefs)
        val coldGeneral = coldStore.read().requireProfile("general")
        assertEquals("role-provider-1", coldGeneral.providerProfileId)
        assertEquals("role-model-1", coldGeneral.modelId)
        assertEquals(listOf("file_write"), coldGeneral.disallowedTools)

        val parentCatalog = TestCatalog(listOf("file_read", "file_write", "browser_use"))
        val catalogView1 = inheritedSubagentCatalog(parentCatalog, coldGeneral)
        assertEquals(listOf("file_read", "browser_use"), catalogView1.toolsForModel.map { it.function.name })
        assertTrue(runCatching { catalogView1.validateArguments("file_write", JsonObject(emptyMap())) }.isFailure)
        catalogView1.validateArguments("file_read", JsonObject(emptyMap()))

        // 4. Clear：清空角色模型（恢复 null/null），经过 payload 重新写入
        @Suppress("UNCHECKED_CAST")
        val clearedProfiles = profilesList1.map { profileMap ->
            if (profileMap["id"] == "general") {
                val updated = LinkedHashMap<String, Any?>(profileMap)
                updated["providerProfileId"] = null
                updated["modelId"] = null
                updated
            } else {
                profileMap
            }
        }
        @Suppress("UNCHECKED_CAST")
        val subagentMap = LinkedHashMap<String, Any?>(payload1["subagent"] as Map<String, Any?>)
        subagentMap["profiles"] = clearedProfiles
        val clearedPayload = LinkedHashMap<String, Any?>(payload1)
        clearedPayload["subagent"] = subagentMap
        val clearedResult = store.write(Gson().toJson(clearedPayload), expectedRevision = 2L)
        assertEquals(3L, clearedResult["revision"])

        // 5. 清空后再进行独立 preferences 冷读，验证模型恢复 null/null 且 disallowedTools 与权限收窄依然生效
        val coldClearedPrefs = createTestPreferences()
        coldClearedPrefs.edit()
            .putString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, prefs.getString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, null))
            .putLong(XiaowanSubagentConfigStore.KEY_REVISION, prefs.getLong(XiaowanSubagentConfigStore.KEY_REVISION, 0L))
            .commit()
        val coldClearedStore = XiaowanSubagentConfigStore(createTestContext(coldClearedPrefs), coldClearedPrefs)
        val coldClearedGeneral = coldClearedStore.read().requireProfile("general")
        assertNull(coldClearedGeneral.providerProfileId)
        assertNull(coldClearedGeneral.modelId)
        assertEquals(listOf("file_write"), coldClearedGeneral.disallowedTools)

        val catalogView2 = inheritedSubagentCatalog(parentCatalog, coldClearedGeneral)
        assertEquals(listOf("file_read", "browser_use"), catalogView2.toolsForModel.map { it.function.name })
        assertTrue(runCatching { catalogView2.validateArguments("file_write", JsonObject(emptyMap())) }.isFailure)
        catalogView2.validateArguments("file_read", JsonObject(emptyMap()))
    }

    @Test
    fun `profile half-pair or blank pair fails validation without losing stored configuration`() {
        val context = createTestContext()
        val store = XiaowanSubagentConfigStore(context)

        // 写入初始合法配置
        val initialContent = """
            {
              "enabled": true,
              "providerProfileId": "p-init",
              "modelId": "m-init",
              "profiles": [
                {
                  "id": "general",
                  "displayName": "通用",
                  "systemPrompt": "提示",
                  "baseProfileId": "general",
                  "enabled": true,
                  "providerProfileId": "role-p",
                  "modelId": "role-m"
                },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()
        store.write(initialContent, expectedRevision = 0L)
        assertEquals(1L, store.readPayload()["revision"])

        // 1. 仅提供 providerProfileId 无 modelId
        val missingModel = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true, "providerProfileId": "p-only" },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(missingModel, expectedRevision = 1L) }.isFailure)

        // 2. 仅提供 modelId 无 providerProfileId
        val missingProvider = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true, "modelId": "m-only" },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(missingProvider, expectedRevision = 1L) }.isFailure)

        // 3. 空白字符串半配置
        val blankModel = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true, "providerProfileId": "p-valid", "modelId": "   " },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(blankModel, expectedRevision = 1L) }.isFailure)

        val blankBoth = """
            {
              "profiles": [
                { "id": "general", "displayName": "G", "systemPrompt": "SP", "baseProfileId": "general", "enabled": true, "providerProfileId": "", "modelId": "" },
                { "id": "explorer", "displayName": "E", "systemPrompt": "SP", "baseProfileId": "explorer", "enabled": true },
                { "id": "memory-curator", "displayName": "M", "systemPrompt": "SP", "baseProfileId": "memory-curator", "enabled": true },
                { "id": "planner", "displayName": "P", "systemPrompt": "SP", "baseProfileId": "planner", "enabled": true }
              ]
            }
        """.trimIndent()
        assertTrue(runCatching { store.write(blankBoth, expectedRevision = 1L) }.isFailure)

        // 验证旧配置完全未丢，版本仍为 1L
        val preserved = store.read()
        assertEquals("p-init", preserved.providerProfileId)
        assertEquals("role-p", preserved.requireProfile("general").providerProfileId)
        assertEquals(1L, store.readPayload()["revision"])
    }

    @Test
    fun `legacy config without profile model fields loads gracefully without migration`() {
        val legacyJson = """
            {
              "enabled": true,
              "providerProfileId": "legacy-provider",
              "modelId": "legacy-model",
              "profiles": [
                {
                  "id": "general",
                  "displayName": "通用",
                  "systemPrompt": "提示",
                  "baseProfileId": "general",
                  "enabled": true
                },
                {
                  "id": "explorer",
                  "displayName": "探索者",
                  "systemPrompt": "探索",
                  "baseProfileId": "explorer",
                  "enabled": true
                },
                {
                  "id": "memory-curator",
                  "displayName": "记忆",
                  "systemPrompt": "记忆",
                  "baseProfileId": "memory-curator",
                  "enabled": true
                },
                {
                  "id": "planner",
                  "displayName": "规划",
                  "systemPrompt": "规划",
                  "baseProfileId": "planner",
                  "enabled": true
                }
              ]
            }
        """.trimIndent()

        val prefs = createTestPreferences()
        prefs.edit()
            .putString(XiaowanSubagentConfigStore.KEY_CONFIG_JSON, legacyJson)
            .putLong(XiaowanSubagentConfigStore.KEY_REVISION, 5L)
            .commit()

        val store = XiaowanSubagentConfigStore(createTestContext(prefs), prefs)
        val loaded = store.read()
        assertEquals(5L, store.readPayload()["revision"])
        assertEquals("legacy-provider", loaded.providerProfileId)
        assertEquals("legacy-model", loaded.modelId)
        for (profile in loaded.profiles) {
            assertNull(profile.providerProfileId)
            assertNull(profile.modelId)
        }
    }

    private class TestCatalog(
        toolNames: List<String>
    ) : AgentToolCatalog {
        override val toolsForModel: List<ChatCompletionTool> = toolNames.map { name ->
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
