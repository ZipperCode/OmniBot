package cn.com.omnimind.bot.webchat

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.task.runtime.AcpTaskTerminalJournal
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * WebAgentRunBridge 真实历史投影、身份隔离与官方终态 stopReason 行为测试。
 * 验证已知 Session/Turn exact lookup、流式非终态不提前完成、旧 turn 不污染新 turn。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class WebAgentRunBridgeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var runtimeManager: AgentRuntimeManager
    private lateinit var historyRepository: AgentConversationHistoryRepository
    private lateinit var bridge: WebAgentRunBridge
    private var testConversationId: Long = 0L
    private var testDataDir: File? = null

    @Before
    fun setUp() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        com.rk.libcommons.application = app
        DatabaseHelper.init(app)

        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(anyString())).thenAnswer { call ->
            File("src/main/assets", call.getArgument<String>(0)).inputStream()
        }

        testDataDir = tempFolder.newFolder("web_bridge_data")
        val appInfo = ApplicationInfo(app.applicationInfo).apply {
            dataDir = testDataDir!!.absolutePath
        }

        context = object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = assets
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = appInfo
            override fun getFilesDir(): File = File(testDataDir, "files").apply { mkdirs() }
        }

        // 使用已有反射 fixture 实例化宿主 AgentRuntimeManager，不向生产增加测试 API
        val constructor = AgentRuntimeManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        runtimeManager = constructor.newInstance(context)

        historyRepository = AgentConversationHistoryRepository(context)
        bridge = WebAgentRunBridge(context, runtimeManager)

        val conversation = Conversation(
            title = "Web Agent Bridge Test",
            mode = "agent",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        testConversationId = DatabaseHelper.insertConversation(conversation)
    }

    @After
    fun tearDown() {
        runBlocking {
            if (testConversationId != 0L) {
                runCatching { DatabaseHelper.deleteConversationById(testConversationId) }
            }
            runCatching {
                val dbField: Field = DatabaseHelper::class.java.getDeclaredField("database")
                dbField.isAccessible = true
                val db = dbField.get(null)
                db?.javaClass?.getMethod("close")?.invoke(db)
                dbField.set(null, null)
            }
        }
        runCatching {
            val scopeField = AgentRuntimeManager::class.java.getDeclaredField("scope")
            scopeField.isAccessible = true
            (scopeField.get(runtimeManager) as? kotlinx.coroutines.CoroutineScope)?.cancel()
        }
        runCatching {
            val scopeField = WebAgentRunBridge::class.java.getDeclaredField("scope")
            scopeField.isAccessible = true
            (scopeField.get(bridge) as? kotlinx.coroutines.CoroutineScope)?.cancel()
        }
    }

    /**
     * 辅助注册活跃运行状态到 bridge 的内部映射中，通过完整 primary constructor 反射选择构造。
     */
    private fun registerActiveRun(
        taskId: String,
        conversationId: Long,
        threadId: String?,
        requestId: String,
        turnId: String? = null,
        agentId: String = "xiaowan-acp"
    ): Any {
        val stateClass = Class.forName("cn.com.omnimind.bot.webchat.WebAgentRunState")
        val primaryCtor = stateClass.declaredConstructors.first { ctor ->
            !ctor.parameterTypes.any { it.name.contains("DefaultConstructorMarker") }
        }.apply { isAccessible = true }

        val state = primaryCtor.newInstance(
            taskId,
            conversationId,
            "agent",
            System.currentTimeMillis(),
            java.util.concurrent.atomic.AtomicBoolean(false),
            kotlinx.coroutines.CompletableDeferred<Unit>(),
            java.util.concurrent.atomic.AtomicReference<Throwable?>(null),
            threadId,
            turnId,
            agentId,
            null, // agentName
            requestId,
            null, // stopReason
            0L, // sequence
            linkedMapOf<String, Any>(), // assistantEntries
            linkedMapOf<String, Any>(), // reasoningEntries
            linkedMapOf<String, Any>() // toolEntries
        )

        val runsByConvField = WebAgentRunBridge::class.java.getDeclaredField("runsByConversationId").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val runsByConv = runsByConvField.get(bridge) as ConcurrentHashMap<Long, Any>
        runsByConv[conversationId] = state

        val runsByTaskField = WebAgentRunBridge::class.java.getDeclaredField("runsByTaskId").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val runsByTask = runsByTaskField.get(bridge) as ConcurrentHashMap<String, Any>
        runsByTask[taskId] = state

        if (threadId != null) {
            val runsByThreadField = WebAgentRunBridge::class.java.getDeclaredField("runsByThreadId").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val runsByThread = runsByThreadField.get(bridge) as ConcurrentHashMap<String, Any>
            runsByThread[threadId] = state
        }

        if (turnId != null) {
            val runsByTurnField = WebAgentRunBridge::class.java.getDeclaredField("runsByTurnId").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val runsByTurn = runsByTurnField.get(bridge) as ConcurrentHashMap<String, Any>
            runsByTurn[turnId] = state
        }

        return state
    }

    /**
     * 通过 suspend 反射辅助执行 private suspend fun handleEvent，不向生产暴露 internal。
     */
    private suspend fun dispatchBridgeEvent(bridge: WebAgentRunBridge, event: Map<String, Any?>) {
        if (event["method"] == "session/prompt") {
            val sessionId = event.getValue("threadId").toString()
            val turnId = event.getValue("turnId").toString()
            val requestId = event.getValue("requestId").toString()
            val response = event["message"] as Map<*, *>
            val result = response["result"] as? Map<*, *>
            val error = (response["error"] as? Map<*, *>)?.get("message")?.toString()
            val status = result?.get("stopReason")?.toString() ?: "error"
            val profiles = AcpAgentProfileStore(context)
            profiles.bindConversation(testConversationId, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
            profiles.bindSession(sessionId, AcpAgentProfileStore.XIAOWAN_AGENT_ID)
            assertTrue(AcpTaskTerminalJournal.recordAdmission(
                context, AcpAgentProfileStore.XIAOWAN_AGENT_ID, sessionId, turnId, requestId
            ))
            assertTrue(AcpTaskTerminalJournal.recordTerminal(
                context, AcpAgentProfileStore.XIAOWAN_AGENT_ID, sessionId, turnId, status, error
            ))
        }
        val method = WebAgentRunBridge::class.java.getDeclaredMethod(
            "handleEvent",
            Map::class.java,
            kotlin.coroutines.Continuation::class.java
        ).apply { isAccessible = true }

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            try {
                val result = method.invoke(bridge, event, cont)
                if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                    cont.resumeWith(Result.success(Unit))
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                cont.resumeWith(Result.failure(e.targetException ?: e))
            } catch (t: Throwable) {
                cont.resumeWith(Result.failure(t))
            }
        }
    }

    @Test
    fun `official prompt response writes matching identity and stopReason and exact lookup succeeds`() = runBlocking {
        val sessionId = "session-alpha-1"
        val turnId = "turn-alpha-101"
        val requestId = "req-alpha-101"
        val taskId = "task-alpha-101"

        registerActiveRun(
            taskId = taskId,
            conversationId = testConversationId,
            threadId = sessionId,
            requestId = requestId,
            turnId = null // 模拟准入时尚未绑定 turnId
        )

        // 1. 流式阶段：收到 agent_message_chunk，携带宿主 turnId
        val chunkEvent = mapOf(
            "method" to "session_update",
            "conversationId" to testConversationId,
            "threadId" to sessionId,
            "turnId" to turnId,
            "requestId" to requestId,
            "params" to mapOf(
                "update" to mapOf(
                    "sessionUpdate" to "agent_message_chunk",
                    "content" to mapOf("type" to "text", "text" to "这是正在生成的回答内容")
                )
            )
        )
        dispatchBridgeEvent(bridge, chunkEvent)

        // 验证非终态 partial 不得标终态：stopReason 为 null
        val intermediateMessages = historyRepository.listConversationMessages(testConversationId, "agent")
        val partialAssistant = intermediateMessages.firstOrNull { it["user"] == 2 }
        assertNotNull("流式过程中必须已持久化 assistant 消息", partialAssistant)
        val partialMeta = partialAssistant?.get("streamMeta") as? Map<*, *>
        assertNotNull("流式元数据必须存在", partialMeta)
        assertEquals("流式阶段 sessionId 必须匹配", sessionId, partialMeta?.get("sessionId"))
        assertEquals("流式阶段 turnId 必须匹配", turnId, partialMeta?.get("turnId"))
        assertNull("非终态 partial 不得写入 stopReason", partialMeta?.get("stopReason"))

        // 2. 官方终态响应到达：session/prompt 终态事件携带 end_turn
        val terminalEvent = mapOf(
            "method" to "session/prompt",
            "conversationId" to testConversationId,
            "threadId" to sessionId,
            "turnId" to turnId,
            "requestId" to requestId,
            "id" to requestId,
            "message" to mapOf(
                "result" to mapOf(
                    "status" to "completed",
                    "stopReason" to "end_turn",
                    "completed" to true
                )
            )
        )
        dispatchBridgeEvent(bridge, terminalEvent)

        // 3. 验证真实 history repository 读回：通过已知 Session/Turn exact lookup 准确定位
        val exactText = historyRepository.findExactTurnAssistantMessage(
            conversationId = testConversationId,
            sessionId = sessionId,
            turnId = turnId
        )
        assertEquals("通过已知 Session/Turn exact lookup 必须准确读回回答内容", "这是正在生成的回答内容", exactText)

        // 4. 验证终态 streamMeta：身份一致且 stopReason 正确，且 isFinal = true
        val finalMessages = historyRepository.listConversationMessages(testConversationId, "agent")
        val finalAssistant = finalMessages.firstOrNull { it["user"] == 2 }
        assertNotNull("终态 assistant 消息必须存在", finalAssistant)
        val finalMeta = finalAssistant?.get("streamMeta") as? Map<*, *>
        assertNotNull("终态元数据必须存在", finalMeta)
        assertEquals("终态 sessionId 必须精确一致", sessionId, finalMeta?.get("sessionId"))
        assertEquals("终态 turnId 必须精确一致", turnId, finalMeta?.get("turnId"))
        assertEquals("终态 stopReason 必须为 end_turn", "end_turn", finalMeta?.get("stopReason"))
        assertTrue("终态 isFinal 必须为 true", finalMeta?.get("isFinal") == true)
    }

    @Test
    fun `cancelled and error stopReasons are properly preserved in streamMeta`() = runBlocking {
        val sessionId = "session-abnormal-1"
        val turnCancelId = "turn-cancel-01"
        val reqCancelId = "req-cancel-01"
        val taskCancelId = "task-cancel-01"

        // 1. 取消 Turn
        registerActiveRun(
            taskId = taskCancelId,
            conversationId = testConversationId,
            threadId = sessionId,
            requestId = reqCancelId,
            turnId = turnCancelId
        )
        dispatchBridgeEvent(
            bridge,
            mapOf(
                "method" to "session_update",
                "conversationId" to testConversationId,
                "threadId" to sessionId,
                "turnId" to turnCancelId,
                "requestId" to reqCancelId,
                "params" to mapOf(
                    "update" to mapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "content" to mapOf("type" to "text", "text" to "任务被取消前部分输出")
                    )
                )
            )
        )
        dispatchBridgeEvent(
            bridge,
            mapOf(
                "method" to "session/prompt",
                "conversationId" to testConversationId,
                "threadId" to sessionId,
                "turnId" to turnCancelId,
                "requestId" to reqCancelId,
                "id" to reqCancelId,
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "cancelled",
                        "stopReason" to "cancelled"
                    )
                )
            )
        )

        val messages1 = historyRepository.listConversationMessages(testConversationId, "agent")
        val cancelledAssistant = messages1.firstOrNull {
            val content = it["content"] as? Map<*, *>
            content?.get("text") == "任务被取消前部分输出"
        }
        assertNotNull("取消任务的 assistant 消息必须存在", cancelledAssistant)
        val cancelMeta = cancelledAssistant?.get("streamMeta") as? Map<*, *>
        assertEquals("取消任务 streamMeta 的 stopReason 必须为 cancelled", "cancelled", cancelMeta?.get("stopReason"))
        assertTrue("取消任务 streamMeta 的 isFinal 必须为 true", cancelMeta?.get("isFinal") == true)
        assertEquals(sessionId, cancelMeta?.get("sessionId"))
        assertEquals(turnCancelId, cancelMeta?.get("turnId"))

        // 2. 异常 Turn
        val turnErrorId = "turn-error-02"
        val reqErrorId = "req-error-02"
        val taskErrorId = "task-error-02"

        registerActiveRun(
            taskId = taskErrorId,
            conversationId = testConversationId,
            threadId = sessionId,
            requestId = reqErrorId,
            turnId = turnErrorId
        )
        dispatchBridgeEvent(
            bridge,
            mapOf(
                "method" to "session/prompt",
                "conversationId" to testConversationId,
                "threadId" to sessionId,
                "turnId" to turnErrorId,
                "requestId" to reqErrorId,
                "id" to reqErrorId,
                "message" to mapOf(
                    "error" to mapOf(
                        "message" to "执行超时失败"
                    )
                )
            )
        )

        val messages2 = historyRepository.listConversationMessages(testConversationId, "agent")
        val errorAssistant = messages2.firstOrNull {
            val content = it["content"] as? Map<*, *>
            (content?.get("text") as? String)?.contains("执行超时失败") == true
        }
        assertNotNull("异常任务的 assistant 错误消息必须存在", errorAssistant)
        val errorMeta = errorAssistant?.get("streamMeta") as? Map<*, *>
        assertEquals("异常任务 streamMeta 的 stopReason 必须为 error", "error", errorMeta?.get("stopReason"))
        assertTrue("异常任务 streamMeta 的 isFinal 必须为 true", errorMeta?.get("isFinal") == true)
        assertEquals(sessionId, errorMeta?.get("sessionId"))
        assertEquals(turnErrorId, errorMeta?.get("turnId"))
    }
}
