package cn.com.omnimind.bot.debug

import android.app.Application
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.bot.App
import com.tencent.mmkv.MMKV
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 验证 DebugModelProviderConfigReceiver 的安全绑定查询与精确恢复能力：
 * 1. query_bindings 仅返回纯 IDs (sceneId, providerProfileId, modelId)，不调用 queryState()，不泄露 profiles 或 baseUrl；
 * 2. query_bindings 支持 sceneIds 白名单精确过滤（如 scene.vlm.operation.primary）；
 * 3. bind_existing 返回纯 ID 绑定回执，不返回 profiles，不触碰凭据或 SceneOperationConfig。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DebugModelProviderConfigReceiverTest {

    private val mmkvStore = mutableMapOf<String, Any>()
    private val memorySecrets = mutableMapOf<String, Any?>()
    private var mockedMMKV: MockedStatic<MMKV>? = null
    private var secretStoreField: Field? = null
    private var originalSecretStore: Any? = null

    @Before
    fun setUp() {
        mmkvStore.clear()
        memorySecrets.clear()

        val mockMMKV = mock(MMKV::class.java, Answer<Any?> { invocation ->
            val args = invocation.arguments
            val key = args.firstOrNull() as? String
            when (invocation.method.name) {
                "decodeString" -> key?.let { mmkvStore[it] as? String }
                    ?: if (args.size > 1) args[1] as? String else null
                "decodeBool" -> key?.let { mmkvStore[it] as? Boolean }
                    ?: if (args.size > 1) args[1] as? Boolean ?: false else false
                "decodeInt" -> key?.let { mmkvStore[it] as? Int }
                    ?: if (args.size > 1) args[1] as? Int ?: 0 else 0
                "decodeLong" -> key?.let { mmkvStore[it] as? Long }
                    ?: if (args.size > 1) args[1] as? Long ?: 0L else 0L
                "encode" -> {
                    val k = args[0] as String
                    val v = args[1]
                    if (v == null) mmkvStore.remove(k) else mmkvStore[k] = v
                    true
                }
                "removeValueForKey" -> {
                    if (key != null) mmkvStore.remove(key)
                    null
                }
                "removeValuesForKeys" -> {
                    val keys = args.getOrNull(0) as? Array<*>
                    keys?.forEach { mmkvStore.remove(it as? String) }
                    null
                }
                "clearAll" -> {
                    mmkvStore.clear()
                    null
                }
                "containsKey" -> {
                    if (key != null) mmkvStore.containsKey(key) else false
                }
                "allKeys" -> mmkvStore.keys.toTypedArray()
                else -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })

        mockedMMKV = mockStatic(MMKV::class.java).apply {
            `when`<MMKV> { MMKV.defaultMMKV() }.thenReturn(mockMMKV)
            `when`<MMKV> { MMKV.mmkvWithID(anyString()) }.thenReturn(mockMMKV)
            `when`<MMKV> { MMKV.mmkvWithID(anyString(), anyInt()) }.thenReturn(mockMMKV)
            `when`<String> { MMKV.initialize(any(Context::class.java)) }.thenReturn("/mock/mmkv")
        }

        val app: Application = RuntimeEnvironment.getApplication()
        val mockApp = mock(App::class.java)
        `when`(mockApp.applicationContext).thenReturn(app)
        App.instance = mockApp

        ModelSceneRegistry.init(app)
        ModelProviderConfigStore.initialize(app)

        val f = ModelProviderConfigStore::class.java.getDeclaredField("secretStore").apply {
            isAccessible = true
        }
        secretStoreField = f
        originalSecretStore = f.get(ModelProviderConfigStore)

        val proxySecretStore = Proxy.newProxyInstance(
            f.type.classLoader,
            arrayOf(f.type)
        ) { proxy, method, args ->
            when (method.name) {
                "isAvailable" -> true
                "readProfile" -> {
                    val profileId = args?.getOrNull(0) as? String ?: ""
                    memorySecrets[profileId]
                }
                "writeProfile" -> {
                    val profileId = args?.getOrNull(0) as? String ?: ""
                    val secrets = args?.getOrNull(1)
                    if (secrets != null) {
                        memorySecrets[profileId] = secrets
                    } else {
                        memorySecrets.remove(profileId)
                    }
                    null
                }
                "deleteProfile" -> {
                    val profileId = args?.getOrNull(0) as? String ?: ""
                    memorySecrets.remove(profileId)
                    null
                }
                "deleteProfilesExcept" -> {
                    val ids = (args?.getOrNull(0) as? Set<*>) ?: emptySet<Any?>()
                    memorySecrets.keys.retainAll(ids)
                    null
                }
                "readLegacy" -> null
                "writeLegacy", "deleteLegacy" -> null
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.getOrNull(0) === proxy
                "toString" -> "TestMemorySecretStore"
                else -> null
            }
        }
        f.set(ModelProviderConfigStore, proxySecretStore)

        SceneModelBindingStore.clearBinding("scene.dispatch.model")
        SceneModelBindingStore.clearBinding("scene.vlm.operation.primary")
    }

    @After
    fun tearDown() {
        try {
            SceneModelBindingStore.clearBinding("scene.dispatch.model")
            SceneModelBindingStore.clearBinding("scene.vlm.operation.primary")
            val app: Application = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit().clear().commit()
            mmkvStore.clear()
            memorySecrets.clear()
            secretStoreField?.set(ModelProviderConfigStore, originalSecretStore)
        } catch (_: Throwable) {
        } finally {
            mockedMMKV?.close()
            mockedMMKV = null
        }
    }

    private fun invokeOperation(methodName: String, intent: Intent): Map<String, Any?> {
        val receiver = DebugModelProviderConfigReceiver()
        val method = DebugModelProviderConfigReceiver::class.java
            .getDeclaredMethod(methodName, Intent::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(receiver, intent) as Map<String, Any?>
    }

    @Test
    fun queryBindingsReturnsPureIdsWithoutCallingQueryState() {

        // 预设绑定
        SceneModelBindingStore.saveBinding(
            sceneId = "scene.dispatch.model",
            providerProfileId = "test-provider-p1",
            modelId = "test-model-m1",
        )
        SceneModelBindingStore.saveBinding(
            sceneId = "scene.vlm.operation.primary",
            providerProfileId = "test-provider-vlm",
            modelId = "deepseek-v4-pro",
        )

        val intent = Intent().apply {
            putExtra("operation", "query_bindings")
        }
        val result = invokeOperation("queryBindings", intent)
        assertEquals(true, result["success"])

        // 严格断言：只包含 sceneBindings，严禁包含 profiles、baseUrl 或 editingProfileId
        assertNull("profiles must not be returned in query_bindings", result["profiles"])
        assertNull("baseUrl must not be returned in query_bindings", result["baseUrl"])
        assertNull("editingProfileId must not be returned in query_bindings", result["editingProfileId"])

        val bindings = result["sceneBindings"] as? List<Map<String, Any?>>
        assertNotNull(bindings)
        assertTrue(bindings!!.isNotEmpty())

        val vlmBinding = bindings.firstOrNull { it["sceneId"] == "scene.vlm.operation.primary" }
        assertNotNull(vlmBinding)
        assertEquals("test-provider-vlm", vlmBinding!!["providerProfileId"])
        assertEquals("deepseek-v4-pro", vlmBinding["modelId"])
    }

    @Test
    fun queryBindingsFiltersBySceneIdsWhitelist() {

        SceneModelBindingStore.saveBinding(
            sceneId = "scene.dispatch.model",
            providerProfileId = "test-provider-p1",
            modelId = "test-model-m1",
        )
        SceneModelBindingStore.saveBinding(
            sceneId = "scene.vlm.operation.primary",
            providerProfileId = "test-provider-vlm",
            modelId = "deepseek-v4-pro",
        )

        val intent = Intent().apply {
            putExtra("operation", "query_bindings")
            putExtra("sceneIds", "scene.vlm.operation.primary")
        }
        val result = invokeOperation("queryBindings", intent)
        assertEquals(true, result["success"])

        val bindings = result["sceneBindings"] as? List<Map<String, Any?>>
        assertNotNull(bindings)
        assertEquals(1, bindings!!.size)
        assertEquals("scene.vlm.operation.primary", bindings[0]["sceneId"])
        assertEquals("test-provider-vlm", bindings[0]["providerProfileId"])
        assertEquals("deepseek-v4-pro", bindings[0]["modelId"])
    }

    @Test
    fun restoreBindingUpdatesBindingAndReturnsPureIdsWithoutProfiles() {

        // 预设一个已配置的 provider profile
        ModelProviderConfigStore.saveProfile(
            id = "restore-target-profile",
            name = "Restore Target Profile",
            baseUrl = "https://api.example.com/v1",
            apiKey = "secret-key",
        )

        val intent = Intent().apply {
            putExtra("operation", "bind_existing")
            putExtra("profileId", "restore-target-profile")
            putExtra("modelId", "deepseek-v4-pro")
            putExtra("sceneId", "scene.vlm.operation.primary")
        }
        val result = invokeOperation("bindExisting", intent)
        assertEquals(true, result["success"])
        assertEquals("restore-target-profile", result["boundExistingProfileId"])
        assertEquals("deepseek-v4-pro", result["boundExistingModelId"])

        // 验证返回结果不包含 profiles 或 baseUrl
        assertNull(result["profiles"])
        assertNull(result["baseUrl"])

        // 验证底层 Store 真实被更新
        val updated = SceneModelBindingStore.getBinding("scene.vlm.operation.primary")
        assertNotNull(updated)
        assertEquals("restore-target-profile", updated!!.providerProfileId)
        assertEquals("deepseek-v4-pro", updated.modelId)
    }

}
