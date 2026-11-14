package cn.com.omnimind.bot.omniflow

import android.app.Application
import android.content.Context
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import cn.com.omnimind.baselib.llm.ModelCapabilityOverride
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.llm.SceneOperationConfigStore
import cn.com.omnimind.bot.App
import cn.com.omnimind.bot.agent.AgentConversationContextCompactor
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import com.tencent.mmkv.MMKV
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OmniVlmModelClientTest {

    private lateinit var server: MockWebServer
    private var mockedMMKV: MockedStatic<MMKV>? = null
    private val mmkvStore = ConcurrentHashMap<String, Any?>()
    private var secretStoreField: Field? = null
    private var originalSecretStore: Any? = null
    private var previousAppInstance: App? = null
    private var originalBuiltinScenesValue: Any? = null
    private var builtinScenesValueField: Field? = null
    private val memorySecrets = ConcurrentHashMap<String, Any?>()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                    if (key != null) {
                        mmkvStore.remove(key)
                    }
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
                else -> RETURNS_DEFAULTS.answer(invocation)
            }
        })
        mockedMMKV = mockStatic(MMKV::class.java).apply {
            `when`<MMKV> { MMKV.defaultMMKV() }.thenReturn(mockMMKV)
            `when`<MMKV> { MMKV.mmkvWithID(anyString()) }.thenReturn(mockMMKV)
            `when`<MMKV> { MMKV.mmkvWithID(anyString(), anyInt()) }.thenReturn(mockMMKV)
            `when`<String> { MMKV.initialize(any(Context::class.java)) }.thenReturn("/mock/mmkv")
        }

        val app: Application = RuntimeEnvironment.getApplication()
        previousAppInstance = runCatching { App.instance }.getOrNull()
        val mockApp = mock(App::class.java)
        `when`(mockApp.applicationContext).thenReturn(app)
        App.instance = mockApp
        initModelSceneRegistryWithBuiltinScenes(app)
        ModelProviderConfigStore.initialize(app)
        val f = ModelProviderConfigStore::class.java.getDeclaredField("secretStore").apply {
            isAccessible = true
        }
        secretStoreField = f
        originalSecretStore = f.get(ModelProviderConfigStore)
        memorySecrets.clear()
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
        SceneModelBindingStore.clearBinding(SceneOperationConfigStore.SCENE_ID)
    }

    @After
    fun tearDown() {
        try {
            SceneModelBindingStore.clearBinding("scene.dispatch.model")
            SceneModelBindingStore.clearBinding(SceneOperationConfigStore.SCENE_ID)
            val app: Application = RuntimeEnvironment.getApplication()
            app.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit().clear().commit()
            mmkvStore.clear()
            memorySecrets.clear()
            scope.cancel()
            server.shutdown()
        } catch (_: Throwable) {
        } finally {
            try {
                if (previousAppInstance != null) {
                    App.instance = previousAppInstance!!
                }
            } catch (_: Throwable) {
            }
            try {
                if (secretStoreField != null) {
                    secretStoreField?.set(ModelProviderConfigStore, originalSecretStore)
                }
            } catch (_: Throwable) {
            }
            try {
                mockedMMKV?.close()
                mockedMMKV = null
            } catch (_: Throwable) {
            }
            try {
                if (originalBuiltinScenesValue != null && (originalBuiltinScenesValue as? Map<*, *>)?.isNotEmpty() == true) {
                    builtinScenesValueField?.let { field ->
                        val delegateField = ModelSceneRegistry::class.java.getDeclaredField("builtinSceneMap\$delegate").apply {
                            isAccessible = true
                        }
                        val lazyInstance = delegateField.get(null)
                        field.set(lazyInstance, originalBuiltinScenesValue)
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }
    private fun initModelSceneRegistryWithBuiltinScenes(app: Application) {
        ModelSceneRegistry.init(app)
        try {
            val jsonFile = listOf(
                File("baselib/src/main/res/raw/model_scenes_default.json"),
                File("../baselib/src/main/res/raw/model_scenes_default.json"),
                File("app/../baselib/src/main/res/raw/model_scenes_default.json"),
                File("src/main/res/raw/model_scenes_default.json"),
            ).firstOrNull { it.exists() }
            val jsonText = jsonFile?.readText()
                ?: ModelSceneRegistry::class.java.classLoader?.getResourceAsStream("model_scenes_default.json")?.bufferedReader()?.use { it.readText() }

            if (!jsonText.isNullOrBlank()) {
                val gson = com.google.gson.Gson()
                val rawMap = gson.fromJson<Map<String, Map<String, Any?>>>(
                    jsonText,
                    object : com.google.gson.reflect.TypeToken<Map<String, Map<String, Any?>>>() {}.type
                )
                val sceneMap = mutableMapOf<String, ModelSceneRegistry.SceneInfo>()
                val parseMethod = ModelSceneRegistry::class.java.getDeclaredMethod(
                    "parseSceneInfo",
                    String::class.java,
                    Map::class.java
                ).apply { isAccessible = true }

                rawMap.forEach { (sceneId, config) ->
                    val sceneInfo = parseMethod.invoke(ModelSceneRegistry, sceneId, config) as? ModelSceneRegistry.SceneInfo
                    if (sceneInfo != null) {
                        sceneMap[sceneId] = sceneInfo
                    }
                }

                if (sceneMap.isNotEmpty()) {
                    val delegateField = ModelSceneRegistry::class.java.getDeclaredField("builtinSceneMap\$delegate").apply {
                        isAccessible = true
                    }
                    val lazyInstance = delegateField.get(null)
                    val valueField = lazyInstance.javaClass.getDeclaredField("_value").apply {
                        isAccessible = true
                    }
                    builtinScenesValueField = valueField
                    if (originalBuiltinScenesValue == null) {
                        originalBuiltinScenesValue = valueField.get(lazyInstance)
                    }
                    valueField.set(lazyInstance, sceneMap)
                }
            }
        } catch (_: Throwable) {
        }
    }


    @Test
    fun `session A and session B inherit distinct models when dedicated binding is absent`() = runBlocking {
        // 无专用绑定时，会话 A 与会话 B 分别继承各自环境中的 Provider 与模型
        val profileA = saveSyntheticProfile("provider-a", "Session A Provider")
        val profileB = saveSyntheticProfile("provider-b", "Session B Provider")

        val envA = testEnv(providerProfileId = profileA.id, modelId = "model-alpha")
        val envB = testEnv(providerProfileId = profileB.id, modelId = "model-beta")

        server.enqueue(sseResponse())
        server.enqueue(sseResponse())

        val clientA = createOmniFlowModelClient(scope, envA)
        clientA.streamTurn(guiRequest())
        val recordedA = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedA)
        val requestJsonA = json.parseToJsonElement(recordedA!!.body.readUtf8()).jsonObject
        assertEquals("model-alpha", requestJsonA["model"]?.jsonPrimitive?.content)

        val clientB = createOmniFlowModelClient(scope, envB)
        clientB.streamTurn(guiRequest())
        val recordedB = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedB)
        val requestJsonB = json.parseToJsonElement(recordedB!!.body.readUtf8()).jsonObject
        assertEquals("model-beta", requestJsonB["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `dedicated GUI binding takes precedence over environment model`() = runBlocking {
        // 专用 GUI 绑定存在时，优先使用专用绑定，不继承环境模型
        val envProfile = saveSyntheticProfile("provider-env", "Env Provider")
        val guiProfile = saveSyntheticProfile("provider-gui", "GUI Provider")

        SceneModelBindingStore.saveBinding(
            sceneId = SceneOperationConfigStore.SCENE_ID,
            providerProfileId = guiProfile.id,
            modelId = "model-dedicated-gui",
        )

        val env = testEnv(providerProfileId = envProfile.id, modelId = "model-env-override")

        server.enqueue(sseResponse())

        val client = createOmniFlowModelClient(scope, env)
        client.streamTurn(guiRequest())
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val requestJson = json.parseToJsonElement(recorded!!.body.readUtf8()).jsonObject

        assertEquals("model-dedicated-gui", requestJson["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `broken dedicated GUI binding fails without fallback`() = runBlocking {
        // 失效的专用 GUI 绑定直接阻断抛出异常，绝不回退到环境模型
        val envProfile = saveSyntheticProfile("provider-env", "Env Provider")
        val env = testEnv(providerProfileId = envProfile.id, modelId = "model-env")

        SceneModelBindingStore.saveBinding(
            sceneId = SceneOperationConfigStore.SCENE_ID,
            providerProfileId = "non-existent-provider",
            modelId = "model-broken",
        )

        try {
            createOmniFlowModelClient(scope, env)
            fail("Expected exception for broken GUI binding")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message?.contains("已绑定的 Provider 不存在") == true)
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `model client freezes route and resists subsequent binding changes`() = runBlocking {
        // 客户端实例创建时冻结路由；配置变更只在下一次工厂调用生效，当前客户端保持旧模型
        val guiProfile = saveSyntheticProfile("provider-freeze", "Freeze Provider")

        SceneModelBindingStore.saveBinding(
            sceneId = SceneOperationConfigStore.SCENE_ID,
            providerProfileId = guiProfile.id,
            modelId = "model-v1",
        )

        val client1 = createOmniFlowModelClient(scope, environment = null)

        // 运行时用户修改了绑定为 model-v2
        SceneModelBindingStore.saveBinding(
            sceneId = SceneOperationConfigStore.SCENE_ID,
            providerProfileId = guiProfile.id,
            modelId = "model-v2",
        )

        server.enqueue(sseResponse())
        client1.streamTurn(guiRequest())
        val recorded1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded1)
        val json1 = json.parseToJsonElement(recorded1!!.body.readUtf8()).jsonObject
        assertEquals("model-v1", json1["model"]?.jsonPrimitive?.content)

        // 新工厂调用创建新客户端，读取新配置
        server.enqueue(sseResponse())
        val client2 = createOmniFlowModelClient(scope, environment = null)
        client2.streamTurn(guiRequest())
        val recorded2 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded2)
        val json2 = json.parseToJsonElement(recorded2!!.body.readUtf8()).jsonObject
        assertEquals("model-v2", json2["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `model client with shared scene binding freezes route and updates on next factory call`() = runBlocking {
        // 无专用绑定和环境覆盖时，工厂沿共享 scene (scene.dispatch.model) 解析模型并冻结路由；
        // 修改全局共享绑定后，已创建客户端仍请求旧模型，新工厂调用创建的新客户端使用新模型。
        val sharedProfile = saveSyntheticProfile("provider-shared-freeze", "Shared Freeze Provider")

        SceneModelBindingStore.saveBinding(
            sceneId = "scene.dispatch.model",
            providerProfileId = sharedProfile.id,
            modelId = "shared-model-v1",
        )

        val client1 = createOmniFlowModelClient(scope, environment = null)

        // 运行时用户修改了全局共享模型为 shared-model-v2
        SceneModelBindingStore.saveBinding(
            sceneId = "scene.dispatch.model",
            providerProfileId = sharedProfile.id,
            modelId = "shared-model-v2",
        )

        server.enqueue(sseResponse())
        client1.streamTurn(guiRequest())
        val recorded1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded1)
        val json1 = json.parseToJsonElement(recorded1!!.body.readUtf8()).jsonObject
        assertEquals("shared-model-v1", json1["model"]?.jsonPrimitive?.content)

        // 新工厂调用创建新客户端，读取新配置
        server.enqueue(sseResponse())
        val client2 = createOmniFlowModelClient(scope, environment = null)
        client2.streamTurn(guiRequest())
        val recorded2 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded2)
        val json2 = json.parseToJsonElement(recorded2!!.body.readUtf8()).jsonObject
        assertEquals("shared-model-v2", json2["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `model explicitly rejecting vision fails preflight with gui_model_image_input_unsupported`() = runBlocking {
        // 明确不支持图像输入的模型在网络请求前失败，避免无谓调用
        val profile = saveSyntheticProfile("provider-vision", "Vision Test Provider")
        val modelId = "text-only-model"

        ModelProviderConfigStore.saveModelCapabilityOverride(
            profileId = profile.id,
            modelId = modelId,
            override = ModelCapabilityOverride(imageInput = false),
        )

        val env = testEnv(providerProfileId = profile.id, modelId = modelId)

        try {
            createOmniFlowModelClient(scope, env)
            fail("Expected gui_model_image_input_unsupported")
        } catch (expected: IllegalStateException) {
            assertEquals("gui_model_image_input_unsupported", expected.message)
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun `capacity budget caps GUI requests and never enlarges explicit budgets`() = runBlocking {
        // 预算按 GUI 模型容量与 Compactor 助手计算，与正数 outputLimit 取较小值，不直接等于 outputLimit 也不扩大已有值
        val profile = saveSyntheticProfile("provider-budget", "Budget Provider")
        val modelId = "capacity-model"

        val app = RuntimeEnvironment.getApplication()
        // 模型 contextLimit=4096，compactor helper outputBudgetForCapacity(4096) 计算结果为 2048
        // outputLimit 设为 4096（大于 2048），证明预算是由容量计算而非直接取 outputLimit
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = modelId,
                    displayName = modelId,
                    contextLimit = 4096,
                    outputLimit = 4096,
                    supportsImageInput = true,
                )
            )
        )

        val env = testEnv(providerProfileId = profile.id, modelId = modelId)
        val client = createOmniFlowModelClient(scope, env)

        val expectedBudget = AgentConversationContextCompactor.outputBudgetForCapacity(4096)
        assertTrue("Compactor budget should be strictly smaller than outputLimit", expectedBudget < 4096)

        // 1. GUI scene 缺省 maxCompletionTokens：填补容量助手计算预算 expectedBudget（非 4096、非 512）
        server.enqueue(sseResponse())
        client.streamTurn(guiRequest(maxCompletionTokens = null))
        val recorded1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded1)
        val json1 = json.parseToJsonElement(recorded1!!.body.readUtf8()).jsonObject
        assertEquals(expectedBudget, json1["max_completion_tokens"]?.jsonPrimitive?.intOrNull)
        // 2. GUI scene 已有明确更小预算 (maxCompletionTokens=64)：保持 64，不被扩大
        server.enqueue(sseResponse())
        client.streamTurn(guiRequest(maxCompletionTokens = 64))
        val recorded2 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded2)
        val json2 = json.parseToJsonElement(recorded2!!.body.readUtf8()).jsonObject
        assertEquals(64, json2["max_completion_tokens"]?.jsonPrimitive?.intOrNull)

        // 3. GUI scene 显式指定更小 maxTokens (64)：保持 max_tokens=64，max_completion_tokens 保持 null
        server.enqueue(sseResponse())
        client.streamTurn(ChatCompletionRequest(
            model = SceneOperationConfigStore.SCENE_ID,
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("Perform GUI task"))),
            maxCompletionTokens = null,
            maxTokens = 64,
        ))
        val recordedMaxTokens = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedMaxTokens)
        val jsonMaxTokens = json.parseToJsonElement(recordedMaxTokens!!.body.readUtf8()).jsonObject
        val effectiveMaxTokens = jsonMaxTokens["max_completion_tokens"]?.jsonPrimitive?.intOrNull
            ?: jsonMaxTokens["max_tokens"]?.jsonPrimitive?.intOrNull
        assertEquals(64, effectiveMaxTokens)
        assertTrue(
            "Wire limit should never exceed 64",
            (jsonMaxTokens["max_completion_tokens"]?.jsonPrimitive?.intOrNull ?: 64) <= 64 &&
                (jsonMaxTokens["max_tokens"]?.jsonPrimitive?.intOrNull ?: 64) <= 64
        )

        // 4. GUI 模型 outputLimit (256) 小于容量预算 (2048) 时：由 outputLimit 256 封顶（非 2048、非 512）
        val smallOutputModel = "small-output-model"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = smallOutputModel,
                    displayName = smallOutputModel,
                    contextLimit = 4096,
                    outputLimit = 256,
                    supportsImageInput = true,
                )
            )
        )
        val envSmallOutput = testEnv(providerProfileId = profile.id, modelId = smallOutputModel)
        val clientSmallOutput = createOmniFlowModelClient(scope, envSmallOutput)
        server.enqueue(sseResponse())
        clientSmallOutput.streamTurn(guiRequest(maxCompletionTokens = null))
        val recSmall = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recSmall)
        val jsonSmall = json.parseToJsonElement(recSmall!!.body.readUtf8()).jsonObject
        assertEquals(256, jsonSmall["max_completion_tokens"]?.jsonPrimitive?.intOrNull)

        // 5. 非 GUI 管理请求缺省 maxCompletionTokens：保持 null，不偷补默认预算
        server.enqueue(sseResponse())
        client.streamTurn(ChatCompletionRequest(
            model = "custom-management-model",
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("list"))),
            maxCompletionTokens = null,
        ))
        val recorded3 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded3)
        val json3 = json.parseToJsonElement(recorded3!!.body.readUtf8()).jsonObject
        assertNull(json3["max_completion_tokens"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `reasoning adaptation correctly handles none, non-thinking models, and level fallback`() = runBlocking {
        // 思考意图适配矩阵：非思考模型剔除字段、不可禁用时不发关闭、默认关闭保留关闭、档位不支持时回退有效默认
        val profile = saveSyntheticProfile("provider-reasoning", "Reasoning Provider")
        val app = RuntimeEnvironment.getApplication()

        // 1. reasoning = false：剔除所有思考字段
        val nonThinkingModel = "no-thinking-model"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = nonThinkingModel,
                    displayName = nonThinkingModel,
                    supportsImageInput = true,
                    reasoning = false,
                )
            )
        )
        val envNonThinking = testEnv(providerProfileId = profile.id, modelId = nonThinkingModel, reasoningEffort = "high")
        val client1 = createOmniFlowModelClient(scope, envNonThinking)
        server.enqueue(sseResponse())
        client1.streamTurn(guiRequest(reasoningEffort = "high"))
        val rec1 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec1)
        val json1 = json.parseToJsonElement(rec1!!.body.readUtf8()).jsonObject
        val reasoningEffortVal1 = (json1["reasoning_effort"] as? JsonPrimitive)?.content
        assertNull(reasoningEffortVal1)
        assertNull(json1["enable_thinking"]?.jsonPrimitive?.booleanOrNull)

        // 2. reasoningCanDisable = false 且请求 none：不发关闭参数
        val cannotDisableModel = "cannot-disable-model"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = cannotDisableModel,
                    displayName = cannotDisableModel,
                    supportsImageInput = true,
                    reasoning = true,
                    reasoningCanDisable = false,
                    supportedReasoningLevels = listOf("low", "high"),
                    defaultReasoningLevel = "low",
                )
            )
        )
        val envCannotDisable = testEnv(providerProfileId = profile.id, modelId = cannotDisableModel, reasoningEffort = "none")
        val client2 = createOmniFlowModelClient(scope, envCannotDisable)
        server.enqueue(sseResponse())
        client2.streamTurn(guiRequest(reasoningEffort = "none"))
        val rec2 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec2)
        val json2 = json.parseToJsonElement(rec2!!.body.readUtf8()).jsonObject
        val reasoningEffortVal2 = (json2["reasoning_effort"] as? JsonPrimitive)?.content
        assertNull(reasoningEffortVal2)

        // 3. reasoningDefaultEnabled = false, reasoningCanDisable = true，未设思考意图：保留默认关闭
        val defaultDisabledModel = "default-disabled-model"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = defaultDisabledModel,
                    displayName = defaultDisabledModel,
                    supportsImageInput = true,
                    reasoning = true,
                    reasoningCanDisable = true,
                    reasoningDefaultEnabled = false,
                    supportedReasoningLevels = listOf("low", "high"),
                    defaultReasoningLevel = "low",
                )
            )
        )
        val envDefaultDisabled = testEnv(providerProfileId = profile.id, modelId = defaultDisabledModel, reasoningEffort = null)
        val client3 = createOmniFlowModelClient(scope, envDefaultDisabled)
        server.enqueue(sseResponse())
        client3.streamTurn(guiRequest())
        val rec3 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec3)
        val json3 = json.parseToJsonElement(rec3!!.body.readUtf8()).jsonObject
        assertEquals("none", (json3["reasoning_effort"] as? JsonPrimitive)?.content)
        assertEquals(false, json3["enable_thinking"]?.jsonPrimitive?.booleanOrNull)

        // 4. 支持列表 ["low", "medium"]，默认 "medium"。请求 "xhigh"：回退到有效默认 "medium"
        val fallbackModel = "fallback-level-model"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = fallbackModel,
                    displayName = fallbackModel,
                    supportsImageInput = true,
                    reasoning = true,
                    reasoningCanDisable = true,
                    supportedReasoningLevels = listOf("low", "medium"),
                    defaultReasoningLevel = "medium",
                )
            )
        )
        val envFallback = testEnv(providerProfileId = profile.id, modelId = fallbackModel, reasoningEffort = "xhigh")
        val client4 = createOmniFlowModelClient(scope, envFallback)
        server.enqueue(sseResponse())
        client4.streamTurn(guiRequest())
        val rec4 = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec4)
        val json4 = json.parseToJsonElement(rec4!!.body.readUtf8()).jsonObject
        assertEquals("medium", (json4["reasoning_effort"] as? JsonPrimitive)?.content)

        // 5a. 未知模型能力：透传用户显式思考档位 low，不推断 enable_thinking
        val unknownModelLow = "unknown-effort-model-low"
        val envUnknownLow = testEnv(providerProfileId = profile.id, modelId = unknownModelLow, reasoningEffort = "low")
        val client5a = createOmniFlowModelClient(scope, envUnknownLow)
        server.enqueue(sseResponse())
        client5a.streamTurn(guiRequest())
        val rec5a = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec5a)
        val json5a = json.parseToJsonElement(rec5a!!.body.readUtf8()).jsonObject
        assertEquals("low", (json5a["reasoning_effort"] as? JsonPrimitive)?.content)
        assertNull(json5a["enable_thinking"]?.jsonPrimitive?.booleanOrNull)

        // 5b. 未知模型能力：透传用户显式思考档位 high，不被关闭、不推断 enable_thinking
        val unknownModelHigh = "unknown-effort-model-high"
        val envUnknownHigh = testEnv(providerProfileId = profile.id, modelId = unknownModelHigh, reasoningEffort = "high")
        val client5b = createOmniFlowModelClient(scope, envUnknownHigh)
        server.enqueue(sseResponse())
        client5b.streamTurn(guiRequest())
        val rec5b = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec5b)
        val json5b = json.parseToJsonElement(rec5b!!.body.readUtf8()).jsonObject
        assertEquals("high", (json5b["reasoning_effort"] as? JsonPrimitive)?.content)
        assertNull(json5b["enable_thinking"]?.jsonPrimitive?.booleanOrNull)

        // 5c. 未知模型能力且未指定显式思考意图：完全省略思考参数，不强制开启
        val unknownModelNull = "unknown-effort-model-null"
        val envUnknownNull = testEnv(providerProfileId = profile.id, modelId = unknownModelNull, reasoningEffort = null)
        val client5c = createOmniFlowModelClient(scope, envUnknownNull)
        server.enqueue(sseResponse())
        client5c.streamTurn(guiRequest())
        val rec5c = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(rec5c)
        val json5c = json.parseToJsonElement(rec5c!!.body.readUtf8()).jsonObject
        assertNull(json5c["reasoning_effort"])
        assertNull(json5c["enable_thinking"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `execution config freezes route source and provider sha256 across binding, env, and shared`() = runBlocking {
        val profileA = saveSyntheticProfile("prof-bind", "Binding Profile")
        val profileB = saveSyntheticProfile("prof-env", "Env Profile")
        val profileShared = saveSyntheticProfile("prof-shared", "Shared Profile")
        val app = RuntimeEnvironment.getApplication()
        ModelProviderConfigStore.rememberModels(context = app, profile = profileA, models = listOf(ProviderModelOption(id = "model-bind", displayName = "Bind Model", supportsImageInput = true)))
        ModelProviderConfigStore.rememberModels(context = app, profile = profileB, models = listOf(ProviderModelOption(id = "model-env", displayName = "Env Model", supportsImageInput = true)))
        ModelProviderConfigStore.rememberModels(context = app, profile = profileShared, models = listOf(ProviderModelOption(id = "model-shared", displayName = "Shared Model", supportsImageInput = true)))

        // 1. Dedicated binding
        SceneModelBindingStore.saveBinding(SceneOperationConfigStore.SCENE_ID, profileA.id, "model-bind")
        val clientDedicated = createOmniFlowModelClient(scope, environment = null)
        server.enqueue(sseResponse(model = "model-bind"))
        val turnDedicated = clientDedicated.streamTurn(guiRequest())
        val configDedicated = turnDedicated.executionConfig
        assertNotNull(configDedicated)
        assertEquals("gui_binding", configDedicated!!.routeSource)
        assertEquals(OmniFlowModelExecutionConfig.sha256Hex(profileA.id), configDedicated.providerProfileSha256)
        assertEquals("model-bind", configDedicated.resolvedModel)
        assertFalse("Raw profile id must not leak into execution config map", configDedicated.asMap().values.contains(profileA.id))

        // 2. Conversation env override (clear dedicated binding first)
        SceneModelBindingStore.clearBinding(SceneOperationConfigStore.SCENE_ID)
        val env = testEnv(providerProfileId = profileB.id, modelId = "model-env")
        val clientEnv = createOmniFlowModelClient(scope, env)
        server.enqueue(sseResponse(model = "model-env"))
        val turnEnv = clientEnv.streamTurn(guiRequest())
        val configEnv = turnEnv.executionConfig
        assertNotNull(configEnv)
        assertEquals("conversation", configEnv!!.routeSource)
        assertEquals(OmniFlowModelExecutionConfig.sha256Hex(profileB.id), configEnv.providerProfileSha256)
        assertEquals("model-env", configEnv.resolvedModel)

        // 3. Shared scene fallback (clear binding, null env)
        SceneModelBindingStore.saveBinding(
            sceneId = "scene.dispatch.model",
            providerProfileId = profileShared.id,
            modelId = "model-shared",
        )
        val clientShared = createOmniFlowModelClient(scope, environment = null)
        server.enqueue(sseResponse(model = "model-shared"))
        val turnShared = clientShared.streamTurn(guiRequest())
        val configShared = turnShared.executionConfig
        assertNotNull(configShared)
        assertEquals("shared_scene", configShared!!.routeSource)
        assertEquals(OmniFlowModelExecutionConfig.sha256Hex(profileShared.id), configShared.providerProfileSha256)
        assertEquals("model-shared", configShared.resolvedModel)
    }

    @Test
    fun `output budget reflects capacity budget rather than response token consumption`() = runBlocking {
        val profile = saveSyntheticProfile("prof-budget", "Budget Profile")
        val modelId = "budget-model"
        val app = RuntimeEnvironment.getApplication()
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = modelId,
                    displayName = "Budget Model",
                    contextLimit = 4096,
                    outputLimit = 3000,
                    supportsImageInput = true,
                ),
            ),
        )
        val env = testEnv(providerProfileId = profile.id, modelId = modelId)
        val client = createOmniFlowModelClient(scope, env)

        val expectedBudget = AgentConversationContextCompactor.outputBudgetForCapacity(4096)
        server.enqueue(sseResponseWithUsage(content = "done", model = modelId, promptTokens = 150, completionTokens = 42))

        val turn = client.streamTurn(guiRequest())
        val config = turn.executionConfig
        assertNotNull(config)
        assertEquals(expectedBudget, config!!.outputBudget)
        assertEquals(expectedBudget, config.maxCompletionTokens)

        // The turn usage records 42 completion tokens, but output budget remains the compactor budget
        assertEquals(42, turn.turn.usage?.completionTokens)
        assertTrue(config.outputBudget > (turn.turn.usage?.completionTokens ?: 0))
    }

    @Test
    fun `omitted thinking is null and strictly distinguished from none or false`() = runBlocking {
        val profile = saveSyntheticProfile("prof-thinking-contrast", "Thinking Contrast Provider")

        val app = RuntimeEnvironment.getApplication()
        // Non-thinking model: reasoning = false
        val nonThinkingModel = "model-no-reasoning"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = nonThinkingModel,
                    displayName = "No Reasoning Model",
                    supportsImageInput = true,
                    reasoning = false,
                ),
            ),
        )
        val clientNonThinking = createOmniFlowModelClient(
            scope,
            testEnv(providerProfileId = profile.id, modelId = nonThinkingModel, reasoningEffort = null),
        )
        server.enqueue(sseResponse(model = nonThinkingModel))
        val turnOmitted = clientNonThinking.streamTurn(guiRequest())
        val configOmitted = turnOmitted.executionConfig
        assertNotNull(configOmitted)
        assertNull("Omitted reasoning effort must be null", configOmitted!!.reasoningEffort)
        assertNull("Omitted enableThinking must be null", configOmitted.enableThinking)
        assertEquals(false, configOmitted.reasoningSupported)
        assertNull(configOmitted.asMap()["reasoning_effort"])
        assertNull(configOmitted.asMap()["enable_thinking"])
        val omittedList = configOmitted.asMap()["omitted_fields"] as List<*>
        assertTrue(omittedList.contains("reasoning_effort"))
        assertTrue(omittedList.contains("enable_thinking"))
        // Thinking model with explicit effort = "none"
        val thinkingModel = "model-with-reasoning"
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(
                    id = thinkingModel,
                    displayName = "Reasoning Model",
                    supportsImageInput = true,
                    reasoning = true,
                    reasoningCanDisable = true,
                    supportedReasoningLevels = listOf("none", "low", "high"),
                ),
            ),
        )
        val clientNone = createOmniFlowModelClient(
            scope,
            testEnv(providerProfileId = profile.id, modelId = thinkingModel, reasoningEffort = "none"),
        )
        server.enqueue(sseResponse(model = thinkingModel))
        val turnNone = clientNone.streamTurn(guiRequest(reasoningEffort = "none"))
        val configNone = turnNone.executionConfig
        assertNotNull(configNone)
        assertEquals("none", configNone!!.reasoningEffort)
        assertEquals(false, configNone.enableThinking)
        assertEquals("none", configNone.asMap()["reasoning_effort"])
        assertEquals(false, configNone.asMap()["enable_thinking"])
        val noneOmittedList = configNone.asMap()["omitted_fields"] as List<*>
        assertFalse(noneOmittedList.contains("reasoning_effort"))
        assertFalse(noneOmittedList.contains("enable_thinking"))

        // Verify that omitted null is NOT equal to "none" or false
        assertTrue(configOmitted.reasoningEffort != configNone.reasoningEffort)
        assertTrue(configOmitted.enableThinking != configNone.enableThinking)
    }

    @Test
    fun `two client instances with different configs do not cross-contaminate executionConfig`() = runBlocking {
        val profileA = saveSyntheticProfile("prof-inst-a", "Profile A")
        val profileB = saveSyntheticProfile("prof-inst-b", "Profile B")

        val app = RuntimeEnvironment.getApplication()
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profileA,
            models = listOf(
                ProviderModelOption(id = "model-alpha", displayName = "Model Alpha", contextLimit = 8192, supportsImageInput = true),
            ),
        )
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profileB,
            models = listOf(
                ProviderModelOption(id = "model-beta", displayName = "Model Beta", contextLimit = 16384, supportsImageInput = true),
            ),
        )
        val clientA = createOmniFlowModelClient(scope, testEnv(providerProfileId = profileA.id, modelId = "model-alpha"))
        val clientB = createOmniFlowModelClient(scope, testEnv(providerProfileId = profileB.id, modelId = "model-beta"))
        server.enqueue(sseResponse(model = "model-alpha"))
        server.enqueue(sseResponse(model = "model-beta"))

        val turnA = clientA.streamTurn(guiRequest())
        val turnB = clientB.streamTurn(guiRequest())

        val cfgA = turnA.executionConfig
        val cfgB = turnB.executionConfig

        assertNotNull(cfgA)
        assertNotNull(cfgB)
        assertEquals("model-alpha", cfgA!!.resolvedModel)
        assertEquals("model-beta", cfgB!!.resolvedModel)
        assertEquals(OmniFlowModelExecutionConfig.sha256Hex(profileA.id), cfgA.providerProfileSha256)
        assertEquals(OmniFlowModelExecutionConfig.sha256Hex(profileB.id), cfgB.providerProfileSha256)
        assertTrue(cfgA.contextCapacity != cfgB.contextCapacity)
    }

    @Test
    fun `executionConfig map contains only allowed scalar keys and does not leak credentials or prompts`() = runBlocking {
        val profile = saveSyntheticProfile("prof-leak-guard", "Leak Guard Profile")
        val modelId = "leak-guard-model"
        val app = RuntimeEnvironment.getApplication()
        ModelProviderConfigStore.rememberModels(
            context = app,
            profile = profile,
            models = listOf(
                ProviderModelOption(id = modelId, displayName = "Leak Guard Model", contextLimit = 4096, supportsImageInput = true),
            ),
        )
        val client = createOmniFlowModelClient(scope, testEnv(providerProfileId = profile.id, modelId = modelId))
        server.enqueue(sseResponse(model = modelId))
        val promptSecret = "top-secret-gui-prompt"
        val turn = client.streamTurn(ChatCompletionRequest(
            model = SceneOperationConfigStore.SCENE_ID,
            messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive(promptSecret))),
        ))

        val map = turn.executionConfig!!.asMap()
        val allowedKeys = setOf(
            "schema_version",
            "provider_profile_sha256",
            "resolved_model",
            "route_source",
            "context_capacity",
            "output_budget",
            "max_completion_tokens",
            "max_tokens",
            "reasoning_effort",
            "enable_thinking",
            "thinking_type",
            "reasoning_supported",
            "reasoning_can_disable",
            "supported_reasoning_levels",
            "default_reasoning_level",
            "omitted_fields",
        )
        assertTrue("All map keys must be in allowedKeys", allowedKeys.containsAll(map.keys))
        assertTrue("omitted_fields must be present in map", map.containsKey("omitted_fields"))
        val omittedInMap = map["omitted_fields"] as List<*>
        assertTrue(omittedInMap.contains("max_tokens"))

        // Ensure sensitive secrets, URLs, keys, prompt values do not exist anywhere in values
        val allValuesString = map.values.joinToString(" ") { it?.toString().orEmpty() }
        assertFalse(allValuesString.contains("synthetic-test-key"))
        assertFalse(allValuesString.contains(server.url("/v1").toString()))
        assertFalse(allValuesString.contains(promptSecret))
        assertFalse(allValuesString.contains(profile.id))
    }

    private fun saveSyntheticProfile(
        id: String,
        name: String,
    ): ModelProviderProfile {
        return ModelProviderConfigStore.saveProfile(
            id = id,
            name = name,
            baseUrl = server.url("/v1").toString(),
            apiKey = "synthetic-test-key",
        )
    }

    private fun testEnv(
        providerProfileId: String? = null,
        modelId: String? = null,
        reasoningEffort: String? = null,
        conversationId: Long? = null,
    ): AgentExecutionEnvironment {
        val env = mock(AgentExecutionEnvironment::class.java)
        `when`(env.modelProviderProfileId).thenReturn(providerProfileId)
        `when`(env.modelId).thenReturn(modelId)
        `when`(env.reasoningEffort).thenReturn(reasoningEffort)
        `when`(env.conversationId).thenReturn(conversationId)
        return env
    }

    private fun guiRequest(
        maxCompletionTokens: Int? = null,
        reasoningEffort: String? = null,
    ) = ChatCompletionRequest(
        model = SceneOperationConfigStore.SCENE_ID,
        messages = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("Perform GUI task"))),
        maxCompletionTokens = maxCompletionTokens,
        reasoningEffort = reasoningEffort,
    )

    private fun sseResponse(content: String = "ok", model: String = "test-model"): MockResponse {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}],\"model\":\"$model\"}\n\n" +
            "data: [DONE]\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }

    private fun sseResponseWithUsage(
        content: String = "ok",
        model: String = "test-model",
        promptTokens: Int = 120,
        completionTokens: Int = 35,
    ): MockResponse {
        val body = "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"},\"finish_reason\":\"stop\"}],\"model\":\"$model\"}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":$promptTokens,\"completion_tokens\":$completionTokens,\"total_tokens\":${promptTokens + completionTokens}}}\n\n" +
            "data: [DONE]\n\n"
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)
    }
}
