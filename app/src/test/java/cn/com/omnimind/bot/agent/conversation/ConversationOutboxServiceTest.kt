package cn.com.omnimind.bot.agent.conversation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.ConversationOutboxDao
import cn.com.omnimind.baselib.database.ConversationOutboxEntry
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.task.runtime.AcpTaskTerminalJournal
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.ConversationSnapshotOrdering
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class ConversationOutboxServiceTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var runtimeManager: AgentRuntimeManager
    private lateinit var conversationDomainService: ConversationDomainService
    private lateinit var outboxService: ConversationOutboxService

    private val dispatchedPrompts = CopyOnWriteArrayList<Map<String, Any?>>()
    private val appendedMessages = CopyOnWriteArrayList<Map<String, Any?>>()
    private val promptDispatchedSignals = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any?>>>()
    private val promptDispatcherHooks = ConcurrentHashMap<String, suspend (Map<String, Any?>) -> Unit>()

    private var testConversationId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        com.rk.libcommons.application = app
        DatabaseHelper.init(app)

        val assets = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assets.open(anyString())).thenAnswer { call ->
            File("src/main/assets", call.getArgument<String>(0)).inputStream()
        }
        context = object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = assets
            override fun getApplicationContext(): Context = this
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val constructor = AgentRuntimeManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        runtimeManager = constructor.newInstance(context)

        conversationDomainService = ConversationDomainService(context)

        dispatchedPrompts.clear()
        appendedMessages.clear()
        promptDispatchedSignals.clear()
        promptDispatcherHooks.clear()

        outboxService = ConversationOutboxService(
            context = context,
            scope = scope,
            agentRuntimeManager = runtimeManager,
            conversationDomainService = conversationDomainService,
            promptDispatcher = { method, args ->
                val reqId = args["requestId"]?.toString()
                if (reqId != null) {
                    promptDispatcherHooks[reqId]?.invoke(args)
                }
                val record = mapOf("method" to method, "args" to args)
                dispatchedPrompts.add(record)
                if (reqId != null) {
                    val deferred = promptDispatchedSignals.computeIfAbsent(reqId) {
                        CompletableDeferred()
                    }
                    deferred.complete(record)
                }
                mapOf("status" to "ok")
            },
            userMessageAppender = { conversationId, conversationMode, entryId, text, attachments, createdAt ->
                appendedMessages.add(
                    mapOf(
                        "conversationId" to conversationId,
                        "conversationMode" to conversationMode,
                        "entryId" to entryId,
                        "text" to text,
                        "attachments" to attachments,
                        "createdAt" to createdAt
                    )
                )
            }
        )

        val conversation = Conversation(
            title = "Test Chat",
            mode = "agent",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        testConversationId = DatabaseHelper.insertConversation(conversation)
        AcpAgentProfileStore(context).bindConversation(testConversationId, "xiaowan-acp")
        establishPriorTurnEvidence(testConversationId)
    }

    private fun establishPriorTurnEvidence(
        conversationId: Long,
        sessionId: String = "session-setup",
        turnId: String = "turn-setup",
        requestId: String = "req-setup",
        service: ConversationOutboxService = outboxService
    ) = runBlocking {
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", sessionId, turnId, requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", sessionId, turnId, "end_turn")
        service.onPromptSettled(conversationId, sessionId, turnId, requestId, "end_turn", null)
        service.onHistoryCommitted(conversationId, sessionId, turnId, requestId)
        dispatchedPrompts.clear()
        appendedMessages.clear()
        promptDispatchedSignals.clear()
        promptDispatcherHooks.clear()
    }
    @After
    fun tearDown() {
        scope.cancel()
        promptDispatchedSignals.clear()
        promptDispatcherHooks.clear()
    }

    private fun preparePromptDispatchSignal(requestId: String): CompletableDeferred<Map<String, Any?>> {
        val deferred = CompletableDeferred<Map<String, Any?>>()
        promptDispatchedSignals[requestId] = deferred
        return deferred
    }

    private suspend fun awaitPromptDispatched(requestId: String, timeoutMs: Long = 2000L): Map<String, Any?> {
        val deferred = promptDispatchedSignals.computeIfAbsent(requestId) {
            CompletableDeferred()
        }
        return withTimeout(timeoutMs) {
            deferred.await()
        }
    }

    @Test
    fun `enqueue validates empty content and throws outbox_empty`() = runBlocking {
        try {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    payload = mapOf(
                        "text" to "   ",
                        "attachments" to emptyList<Map<String, Any?>>()
                    )
                )
            )
            fail("Expected outbox_empty exception")
        } catch (e: OutboxException) {
            assertEquals("outbox_empty", e.errorCode)
        }
    }

    @Test
    fun `enqueue validates attachments and rejects dataUrl, outside path, or unreadable file`() = runBlocking {
        try {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    payload = mapOf(
                        "text" to "Hello",
                        "attachments" to listOf(
                            mapOf("uri" to "data:image/png;base64,iVBORw0KGgo=")
                        )
                    )
                )
            )
            fail("Expected outbox_attachment_unavailable for dataUrl")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }

        try {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    payload = mapOf(
                        "text" to "Hello",
                        "attachments" to listOf(
                            mapOf("path" to "/non/existent/path/attachment.png")
                        )
                    )
                )
            )
            fail("Expected outbox_attachment_unavailable for missing file")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }

        // Outside file (not in attachmentsDirectory) must be rejected
        val outsideFile = File(context.cacheDir, "outside.png").apply { writeText("outside") }
        try {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    payload = mapOf(
                        "text" to "Hello",
                        "attachments" to listOf(
                            mapOf("path" to outsideFile.absolutePath)
                        )
                    )
                )
            )
            fail("Expected outbox_attachment_unavailable for outside file")
        } catch (e: OutboxException) {
            assertEquals("outbox_attachment_unavailable", e.errorCode)
        }

        // Valid file inside attachmentsDirectory must succeed
        val attachmentsDir = AgentWorkspaceManager(context).attachmentsDirectory().apply { mkdirs() }
        val validFile = File(attachmentsDir, "valid-att.png").apply { writeText("valid content") }
        val snapshot = outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(
                id = "item-valid-att",
                payload = mapOf(
                    "text" to "Valid with attachment",
                    "attachments" to listOf(
                        mapOf("path" to validFile.absolutePath, "name" to "valid-att.png")
                    )
                )
            )
        )
        assertEquals("item-valid-att", snapshot.items.first().id)
    }

    @Test
    fun `enqueue creates pending item and allocates sequence and revision`() = runBlocking {
        val snapshot = outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(
                id = "item-1",
                payload = mapOf(
                    "text" to "Hello OmniBot",
                    "attachments" to emptyList<Map<String, Any?>>()
                )
            )
        )
        assertEquals(1L, snapshot.revision)
        assertEquals(1, snapshot.items.size)

        val item = snapshot.items.first()
        assertEquals("item-1", item.id)
        assertEquals("Hello OmniBot", item.payload?.get("text"))
        assertEquals(1L, item.sequence)
    }

    @Test
    fun `enqueue is idempotent for duplicate item id in same conversation`() = runBlocking {
        outboxService.onPromptAdmitted(
            sessionId = "session-dup",
            turnId = "turn-dup",
            requestId = "req-dup",
            conversationId = testConversationId,
            userMessageId = "msg-dup"
        )
        val mutation = OutboxMutation(
            id = "item-dup",
            payload = mapOf(
                "text" to "Hello Once",
                "attachments" to emptyList<Map<String, Any?>>()
            )
        )
        val first = outboxService.mutate(testConversationId, "enqueue", mutation)
        val second = outboxService.mutate(testConversationId, "enqueue", mutation)

        assertEquals(first.revision, second.revision)
        assertEquals(1, second.items.size)
        assertEquals("item-dup", second.items.first().id)
    }

    @Test
    fun `enqueue rejects duplicate item id across different conversations`() = runBlocking {
        val conv2 = Conversation(
            title = "Test Chat 2",
            mode = "agent",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val convId2 = DatabaseHelper.insertConversation(conv2)
        AcpAgentProfileStore(context).bindConversation(convId2, "xiaowan-acp")
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(
                id = "item-cross-dup",
                payload = mapOf("text" to "Msg in Conv 1")
            )
        )

        try {
            outboxService.mutate(
                convId2,
                "enqueue",
                OutboxMutation(
                    id = "item-cross-dup",
                    payload = mapOf("text" to "Msg in Conv 2")
                )
            )
            fail("Expected outbox_id_conflict")
        } catch (e: OutboxException) {
            assertEquals("outbox_id_conflict", e.errorCode)
        }
    }

    @Test
    fun `CAS conflict check on expectedRevision rejects stale mutation`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(
                id = "item-cas-1",
                payload = mapOf("text" to "First msg")
            )
        )

        try {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    id = "item-cas-2",
                    expectedRevision = 0L,
                    payload = mapOf("text" to "Stale msg")
                )
            )
            fail("Expected outbox_revision_conflict")
        } catch (e: OutboxException) {
            assertEquals("outbox_revision_conflict", e.errorCode)
            assertNotNull(e.snapshot)
            assertTrue((e.snapshot?.revision ?: 0L) >= 1L)
        }
    }

    @Test
    fun `enqueue respects 20 active items limit`() = runBlocking {
        for (i in 1..20) {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    id = "item-limit-$i",
                    payload = mapOf("text" to "Message $i")
                )
            )
        }
        val current = outboxService.list(testConversationId)
        assertEquals(20, current.items.size)

        try {
            outboxService.mutate(
                testConversationId,
                "enqueue",
                OutboxMutation(
                    id = "item-limit-21",
                    payload = mapOf("text" to "Exceeding message")
                )
            )
            fail("Expected outbox_limit_exceeded")
        } catch (e: OutboxException) {
            assertEquals("outbox_limit_exceeded", e.errorCode)
        }
    }

    @Test
    fun `update and remove work on pending items and fail on submitted`() = runBlocking {
        outboxService.onPromptAdmitted(
            sessionId = "session-pre-mut",
            turnId = "turn-pre-mut",
            requestId = "req-pre-mut",
            conversationId = testConversationId,
            userMessageId = "msg-pre-mut"
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(
                id = "item-mut",
                payload = mapOf("text" to "Original text")
            )
        )

        val meta = DatabaseHelper.getConversationOutboxMeta(testConversationId)!!
        val updatedSnapshot = outboxService.mutate(
            testConversationId,
            "update",
            OutboxMutation(
                id = "item-mut",
                expectedRevision = meta.revision,
                payload = mapOf("text" to "Updated text")
            )
        )
        assertEquals("Updated text", updatedSnapshot.items.first { it.id == "item-mut" }.payload?.get("text"))

        // Also test remove on pending item
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(
                id = "item-to-remove",
                payload = mapOf("text" to "Will be removed")
            )
        )
        val afterRemoveSnapshot = outboxService.mutate(
            testConversationId,
            "remove",
            OutboxMutation(id = "item-to-remove")
        )
        assertTrue(afterRemoveSnapshot.items.none { it.id == "item-to-remove" })

        // Preceding turn completes and commits history, triggering item-mut dispatch
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-pre-mut", "turn-pre-mut", "req-pre-mut")
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-pre-mut", "turn-pre-mut", "end_turn")
        outboxService.onPromptSettled(testConversationId, "session-pre-mut", "turn-pre-mut", "req-pre-mut", "end_turn", null)
        outboxService.onHistoryCommitted(testConversationId, "session-pre-mut", "turn-pre-mut", "req-pre-mut")

        val entry = DatabaseHelper.getConversationOutboxDao().getById("item-mut")!!
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, entry.state)

        outboxService.onPromptAdmitted(
            sessionId = "session-1",
            turnId = "turn-1",
            requestId = entry.requestId,
            conversationId = testConversationId,
            userMessageId = entry.userMessageId
        )

        val submittedItem = outboxService.list(testConversationId).items.first { it.id == "item-mut" }
        assertEquals("submitted", submittedItem.state)

        try {
            outboxService.mutate(
                testConversationId,
                "update",
                OutboxMutation(
                    id = "item-mut",
                    payload = mapOf("text" to "Cannot edit submitted")
                )
            )
            fail("Expected outbox_immutable_state on update")
        } catch (e: OutboxException) {
            assertEquals("outbox_immutable_state", e.errorCode)
        }

        try {
            outboxService.mutate(
                testConversationId,
                "remove",
                OutboxMutation(id = "item-mut")
            )
            fail("Expected outbox_immutable_state on remove")
        } catch (e: OutboxException) {
            assertEquals("outbox_immutable_state", e.errorCode)
        }
    }

    @Test
    fun `FIFO sequential dispatch - settled then history committed triggers next item`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-seq-1", payload = mapOf("text" to "First in line"))
        )
        val entry1 = DatabaseHelper.getConversationOutboxDao().getById("item-seq-1")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-seq",
            turnId = "turn-seq-1",
            requestId = entry1.requestId,
            conversationId = testConversationId,
            userMessageId = entry1.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-seq-2", payload = mapOf("text" to "Second in line"))
        )

        val promptsBeforeSettle = dispatchedPrompts.size

        // Signal 1: Prompt settled normally
        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-seq",
            turnId = "turn-seq-1",
            requestId = entry1.requestId,
            stopReason = "end_turn",
            error = null
        )

        // Missing history barrier: item-seq-2 must not be dispatched yet!
        assertEquals(promptsBeforeSettle, dispatchedPrompts.size)
        val pendingSnap = outboxService.list(testConversationId)
        val item2Pending = pendingSnap.items.first { it.id == "item-seq-2" }
        assertEquals("等待历史保存", item2Pending.pauseReason)

        // Record terminal fact in journal for receipt verification
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-seq", "turn-seq-1", entry1.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-seq", "turn-seq-1", "end_turn")

        val entry2 = DatabaseHelper.getConversationOutboxDao().getById("item-seq-2")!!

        // Signal 2: History committed
        outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-seq",
            turnId = "turn-seq-1",
            requestId = entry1.requestId
        )

        // Now item-seq-2 should be dispatched
        awaitPromptDispatched(entry2.requestId)
        assertTrue(dispatchedPrompts.size > promptsBeforeSettle)
        val entry2After = DatabaseHelper.getConversationOutboxDao().getById("item-seq-2")!!
        assertEquals("dispatching", entry2After.state)
    }

    @Test
    fun `FIFO dual signals - history committed first then prompt settled triggers dispatch`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-rev-1", payload = mapOf("text" to "First msg"))
        )
        val entry1 = DatabaseHelper.getConversationOutboxDao().getById("item-rev-1")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-rev",
            turnId = "turn-rev-1",
            requestId = entry1.requestId,
            conversationId = testConversationId,
            userMessageId = entry1.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-rev-2", payload = mapOf("text" to "Second msg"))
        )

        val promptsBefore = dispatchedPrompts.size

        // Record terminal facts in journal for receipt verification
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-rev", "turn-rev-1", entry1.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-rev", "turn-rev-1", "end_turn")

        // Signal 1 arrives first: History committed
        val confirmed = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-rev",
            turnId = "turn-rev-1",
            requestId = entry1.requestId
        )
        assertTrue(confirmed)
        assertEquals(promptsBefore, dispatchedPrompts.size)

        // Signal 2 arrives: Prompt settled
        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-rev",
            turnId = "turn-rev-1",
            requestId = entry1.requestId,
            stopReason = "end_turn",
            error = null
        )

        // With both signals, dispatch proceeds
        val entry2 = DatabaseHelper.getConversationOutboxDao().getById("item-rev-2")!!
        awaitPromptDispatched(entry2.requestId)
        assertTrue(dispatchedPrompts.size > promptsBefore)
        val entry2After = DatabaseHelper.getConversationOutboxDao().getById("item-rev-2")!!
        assertEquals("dispatching", entry2After.state)
    }

    @Test
    fun `prompt error or non-end_turn pauses pending items and does not dispatch`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-err-1", payload = mapOf("text" to "First"))
        )
        val entry1 = DatabaseHelper.getConversationOutboxDao().getById("item-err-1")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-err",
            turnId = "turn-err-1",
            requestId = entry1.requestId,
            conversationId = testConversationId,
            userMessageId = entry1.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-err-2", payload = mapOf("text" to "Second"))
        )

        val promptsBefore = dispatchedPrompts.size

        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-err",
            turnId = "turn-err-1",
            requestId = entry1.requestId,
            stopReason = "max_tokens",
            error = null
        )

        val current = outboxService.list(testConversationId)
        val secondItem = current.items.first { it.id == "item-err-2" }
        assertEquals("paused", secondItem.state)
        assertEquals("达到 token 上限", secondItem.pauseReason)
        assertEquals(promptsBefore, dispatchedPrompts.size)
    }

    @Test
    fun `stopReason completed is treated as non-end_turn and pauses pending items`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-comp-1", payload = mapOf("text" to "First"))
        )
        val entry1 = DatabaseHelper.getConversationOutboxDao().getById("item-comp-1")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-comp",
            turnId = "turn-comp-1",
            requestId = entry1.requestId,
            conversationId = testConversationId,
            userMessageId = entry1.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-comp-2", payload = mapOf("text" to "Second"))
        )

        val promptsBefore = dispatchedPrompts.size

        // Settle with "completed" (not "end_turn"): must be treated as non-end_turn
        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-comp",
            turnId = "turn-comp-1",
            requestId = entry1.requestId,
            stopReason = "completed",
            error = null
        )

        val current = outboxService.list(testConversationId)
        val secondItem = current.items.first { it.id == "item-comp-2" }
        assertEquals("paused", secondItem.state)
        assertEquals("前序任务未正常结束: completed", secondItem.pauseReason)
        assertEquals(promptsBefore, dispatchedPrompts.size)
    }

    @Test
    fun `stop_and_send validates expectedTurnId and prioritizes target while pausing siblings`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-act", payload = mapOf("text" to "Active turn msg"))
        )
        val entryAct = DatabaseHelper.getConversationOutboxDao().getById("item-act")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-stop",
            turnId = "turn-stop-current",
            requestId = entryAct.requestId,
            conversationId = testConversationId,
            userMessageId = entryAct.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-sibling-1", payload = mapOf("text" to "Sibling 1"))
        )
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-target", payload = mapOf("text" to "Target stop_and_send"))
        )

        // Invalid: missing expected IDs
        try {
            outboxService.mutate(
                testConversationId,
                "stop_and_send",
                OutboxMutation(id = "item-target")
            )
            fail("Expected outbox_invalid_operation for missing expected IDs")
        } catch (e: OutboxException) {
            assertEquals("outbox_invalid_operation", e.errorCode)
        }

        // Invalid: stale expectedTurnId
        try {
            outboxService.mutate(
                testConversationId,
                "stop_and_send",
                OutboxMutation(
                    id = "item-target",
                    expectedSessionId = "session-stop",
                    expectedTurnId = "turn-stale-old"
                )
            )
            fail("Expected outbox_target_changed")
        } catch (e: OutboxException) {
            assertEquals("outbox_target_changed", e.errorCode)
        }

        // Valid stop_and_send
        val snapshot = outboxService.mutate(
            testConversationId,
            "stop_and_send",
            OutboxMutation(
                id = "item-target",
                expectedSessionId = "session-stop",
                expectedTurnId = "turn-stop-current"
            )
        )

        val targetItem = snapshot.items.first { it.id == "item-target" }
        val siblingItem = snapshot.items.first { it.id == "item-sibling-1" }
        assertEquals("pending", targetItem.state)
        assertEquals("paused", siblingItem.state)
        assertTrue("Target sequence must be <= sibling sequence", targetItem.sequence < siblingItem.sequence)
        assertTrue("Sequence must be positive", targetItem.sequence > 0L)

        // Cancellation RPC receipt is not terminal: must wait for official prompt settled + history committed
        val promptsBeforeTerminal = dispatchedPrompts.size

        // Record terminal fact in journal for receipt verification
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-stop", "turn-stop-current", entryAct.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-stop", "turn-stop-current", "cancelled")

        // Official turn cancelled + history committed
        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-stop",
            turnId = "turn-stop-current",
            requestId = entryAct.requestId,
            stopReason = "cancelled",
            error = null
        )
        val confirmed = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-stop",
            turnId = "turn-stop-current",
            requestId = entryAct.requestId
        )
        assertTrue(confirmed)

        val targetInDbPre = DatabaseHelper.getConversationOutboxDao().getById("item-target")!!
        awaitPromptDispatched(targetInDbPre.requestId)

        // Only target is dispatched, sibling remains paused
        val promptsAfterTarget = dispatchedPrompts.size
        assertTrue(promptsAfterTarget > promptsBeforeTerminal)
        val targetInDb = DatabaseHelper.getConversationOutboxDao().getById("item-target")!!
        val siblingInDb = DatabaseHelper.getConversationOutboxDao().getById("item-sibling-1")!!
        assertEquals("dispatching", targetInDb.state)
        assertEquals("paused", siblingInDb.state)

        // Sibling was blocked by the cancelled turn and lacks stop_and_send authorization:
        // unpausing sibling directly must NOT allow it to dispatch on a cancelled turn
        DatabaseHelper.getConversationOutboxDao().update(siblingInDb.copy(state = ConversationOutboxEntry.STATE_PENDING, pauseReason = null))
        outboxService.onPromptAdmitted(
            sessionId = "session-stop",
            turnId = "turn-stop-target",
            requestId = targetInDb.requestId,
            conversationId = testConversationId,
            userMessageId = targetInDb.userMessageId
        )
        // Target is now active/submitted; sibling cannot bypass
        assertEquals(promptsAfterTarget, dispatchedPrompts.size)
    }
    @Test
    fun `reconcileOnStartup pauses pending items and reads journal for dispatching without auto replay`() = runBlocking {
        val convStartup = Conversation(
            title = "Startup Reconcile Test",
            mode = "agent",
            createdAt = System.currentTimeMillis() - 5000L,
            updatedAt = System.currentTimeMillis() - 5000L
        )
        val cid = DatabaseHelper.insertConversation(convStartup)
        AcpAgentProfileStore(context).bindConversation(cid, "xiaowan-acp")
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. Pending item from before startup
        val pendingEntry = ConversationOutboxEntry(
            id = "item-start-pending",
            conversationId = cid,
            agentId = "xiaowan-acp",
            payloadJson = "{\"text\":\"Old pending\"}",
            state = ConversationOutboxEntry.STATE_PENDING,
            revision = 1L,
            sequence = 1L,
            requestId = "req-start-pend",
            userMessageId = "msg-start-pend",
            createdAt = System.currentTimeMillis() - 4000L,
            updatedAt = System.currentTimeMillis() - 4000L
        )
        dao.insert(pendingEntry)

        // 2. Dispatching item with unknown journal fact
        val dispatchingUnknown = ConversationOutboxEntry(
            id = "item-start-unknown",
            conversationId = cid,
            agentId = "xiaowan-acp",
            payloadJson = "{\"text\":\"Old dispatching unknown\"}",
            state = ConversationOutboxEntry.STATE_DISPATCHING,
            revision = 2L,
            sequence = 2L,
            requestId = "req-start-unknown",
            userMessageId = "msg-start-unknown",
            blockedBySessionId = "session-start",
            createdAt = System.currentTimeMillis() - 3000L,
            updatedAt = System.currentTimeMillis() - 3000L
        )
        dao.insert(dispatchingUnknown)

        // 3. Dispatching item with confirmed journal fact
        val dispatchingConfirmed = ConversationOutboxEntry(
            id = "item-start-confirmed",
            conversationId = cid,
            agentId = "xiaowan-acp",
            payloadJson = "{\"text\":\"Old dispatching confirmed\"}",
            state = ConversationOutboxEntry.STATE_DISPATCHING,
            revision = 3L,
            sequence = 3L,
            requestId = "req-start-confirmed",
            userMessageId = "msg-start-confirmed",
            blockedBySessionId = "session-start",
            createdAt = System.currentTimeMillis() - 2000L,
            updatedAt = System.currentTimeMillis() - 2000L
        )
        dao.insert(dispatchingConfirmed)

        AcpTaskTerminalJournal.recordAdmission(
            context = context,
            agentId = "xiaowan-acp",
            sessionId = "session-start",
            turnId = "turn-start-conf",
            requestId = "req-start-confirmed"
        )

        val promptsBefore = dispatchedPrompts.size

        // Run startup reconciliation
        outboxService.reconcileOnStartup()

        // Verify: No auto replay
        assertEquals(promptsBefore, dispatchedPrompts.size)

        // Verify item states
        val updatedPending = dao.getById("item-start-pending")!!
        assertEquals("paused", updatedPending.state)
        assertEquals("应用重启，暂停发送", updatedPending.pauseReason)

        val updatedUnknown = dao.getById("item-start-unknown")!!
        assertEquals("paused", updatedUnknown.state)
        assertEquals("投递状态待核对", updatedUnknown.pauseReason)

        val updatedConfirmed = dao.getById("item-start-confirmed")!!
        assertEquals("submitted", updatedConfirmed.state)
    }

    @Test
    fun `history receipt idempotently clears submitted payload`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-receipt", payload = mapOf("text" to "Receipt test msg"))
        )
        val entry = DatabaseHelper.getConversationOutboxDao().getById("item-receipt")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-rec",
            turnId = "turn-rec",
            requestId = entry.requestId,
            conversationId = testConversationId,
            userMessageId = entry.userMessageId
        )
        // Record terminal fact in journal
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-rec", "turn-rec", entry.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-rec", "turn-rec", "end_turn")

        // First receipt commit
        val confirmedFirst = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-rec",
            turnId = "turn-rec",
            requestId = entry.requestId
        )
        assertTrue(confirmedFirst)

        val inDbFirst = DatabaseHelper.getConversationOutboxDao().getById("item-receipt")!!
        assertEquals("{}", inDbFirst.payloadJson)

        // Second receipt commit (idempotent, no error)
        val confirmedSecond = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-rec",
            turnId = "turn-rec",
            requestId = entry.requestId
        )
        assertTrue(confirmedSecond)
        val inDbSecond = DatabaseHelper.getConversationOutboxDao().getById("item-receipt")!!
        assertEquals("{}", inDbSecond.payloadJson)
    }

    @Test
    fun `paused head prevents natural dispatch of subsequent pending items`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()
        // Force insert a paused head item
        dao.insert(
            ConversationOutboxEntry(
                id = "item-head-paused",
                conversationId = testConversationId,
                agentId = "xiaowan-acp",
                payloadJson = "{\"text\":\"Paused head\"}",
                state = ConversationOutboxEntry.STATE_PAUSED,
                revision = 1L,
                sequence = 1L,
                requestId = "req-h-paused",
                userMessageId = "msg-h-paused",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                pauseReason = "Manual paused"
            )
        )

        val promptsBefore = dispatchedPrompts.size

        // Enqueue next item
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-after-paused", payload = mapOf("text" to "Should not jump"))
        )

        // Ensure no prompt was dispatched because head item is paused
        assertEquals(promptsBefore, dispatchedPrompts.size)
        val afterPausedInDb = dao.getById("item-after-paused")!!
        assertEquals("paused", afterPausedInDb.state)
    }

    @Test
    fun `onHistoryCommitted rejects mismatched session or missing journal terminal fact`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-reject-rec", payload = mapOf("text" to "Reject test"))
        )
        val entry = DatabaseHelper.getConversationOutboxDao().getById("item-reject-rec")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-bound",
            turnId = "turn-rej",
            requestId = entry.requestId,
            conversationId = testConversationId,
            userMessageId = entry.userMessageId
        )

        // 1. Missing journal terminal fact -> returns false
        val rejectedNoFact = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-bound",
            turnId = "turn-rej",
            requestId = entry.requestId
        )
        org.junit.Assert.assertFalse(rejectedNoFact)

        // Record journal fact under session-bound
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-bound", "turn-rej", entry.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-bound", "turn-rej", "end_turn")

        // 2. Mismatched session (cross session pollution attempt) -> returns false
        val rejectedWrongSession = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-other",
            turnId = "turn-rej",
            requestId = entry.requestId
        )
        org.junit.Assert.assertFalse(rejectedWrongSession)

        // 3. Mismatched conversationId -> returns false
        val rejectedWrongCid = outboxService.onHistoryCommitted(
            conversationId = testConversationId + 999L,
            sessionId = "session-bound",
            turnId = "turn-rej",
            requestId = entry.requestId
        )
        org.junit.Assert.assertFalse(rejectedWrongCid)
    }

    @Test
    fun `onHistoryCommitted unpauses waiting-history items but retains manually paused items`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-unpause-1", payload = mapOf("text" to "First msg"))
        )
        val entry1 = dao.getById("item-unpause-1")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-unp",
            turnId = "turn-unp-1",
            requestId = entry1.requestId,
            conversationId = testConversationId,
            userMessageId = entry1.userMessageId
        )

        // Enqueue next item
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-unpause-2", payload = mapOf("text" to "Second msg"))
        )

        // Enqueue third item and manually pause it with custom reason
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-user-paused", payload = mapOf("text" to "Third msg"))
        )
        val item3 = dao.getById("item-user-paused")!!
        dao.update(item3.copy(state = ConversationOutboxEntry.STATE_PAUSED, pauseReason = "用户手动暂停"))

        // Settle turn-unp-1 normally: item-unpause-2 transitions to paused with "等待历史保存"
        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-unp",
            turnId = "turn-unp-1",
            requestId = entry1.requestId,
            stopReason = "end_turn",
            error = null
        )

        val item2AfterSettle = dao.getById("item-unpause-2")!!
        assertEquals("paused", item2AfterSettle.state)
        assertEquals("等待历史保存", item2AfterSettle.pauseReason)

        // Record journal terminal fact
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-unp", "turn-unp-1", entry1.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-unp", "turn-unp-1", "end_turn")

        // Commit history
        val confirmed = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-unp",
            turnId = "turn-unp-1",
            requestId = entry1.requestId
        )
        assertTrue(confirmed)

        // item-unpause-2 must be unpaused and dispatched (state == dispatching)
        val item2AfterReceipt = dao.getById("item-unpause-2")!!
        assertEquals("dispatching", item2AfterReceipt.state)

        // item-user-paused must stay paused! (Manual pause not automatically restored)
        val item3AfterReceipt = dao.getById("item-user-paused")!!
        assertEquals("paused", item3AfterReceipt.state)
        assertEquals("用户手动暂停", item3AfterReceipt.pauseReason)
    }

    @Test
    fun `onHistoryCommitted with mismatched request or turn is rejected, payload not cleared and not dispatched`() = runBlocking {
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-mismatch-1", payload = mapOf("text" to "Original payload text"))
        )
        val entry = DatabaseHelper.getConversationOutboxDao().getById("item-mismatch-1")!!

        outboxService.onPromptAdmitted(
            sessionId = "session-mismatch",
            turnId = "turn-real-1",
            requestId = entry.requestId,
            conversationId = testConversationId,
            userMessageId = entry.userMessageId
        )

        // Enqueue second item waiting in queue
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-mismatch-2", payload = mapOf("text" to "Second payload text"))
        )

        val promptsBefore = dispatchedPrompts.size

        // Record terminal fact in journal for turn-real-1 and entry.requestId
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-mismatch", "turn-real-1", entry.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-mismatch", "turn-real-1", "end_turn")

        // Settle prompt
        outboxService.onPromptSettled(
            conversationId = testConversationId,
            sessionId = "session-mismatch",
            turnId = "turn-real-1",
            requestId = entry.requestId,
            stopReason = "end_turn",
            error = null
        )

        // 1. Call onHistoryCommitted with WRONG turnId:
        val confirmedWrongTurn = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-mismatch",
            turnId = "turn-wrong-other",
            requestId = entry.requestId
        )
        org.junit.Assert.assertFalse(confirmedWrongTurn)

        // Verify payload NOT cleared
        val inDbAfterWrongTurn = DatabaseHelper.getConversationOutboxDao().getById("item-mismatch-1")!!
        org.junit.Assert.assertNotEquals("{}", inDbAfterWrongTurn.payloadJson)
        // Verify next item NOT dispatched
        assertEquals(promptsBefore, dispatchedPrompts.size)

        // 2. Call onHistoryCommitted with WRONG requestId:
        val confirmedWrongReq = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-mismatch",
            turnId = "turn-real-1",
            requestId = "req-wrong-other"
        )
        org.junit.Assert.assertFalse(confirmedWrongReq)

        // Verify payload NOT cleared
        val inDbAfterWrongReq = DatabaseHelper.getConversationOutboxDao().getById("item-mismatch-1")!!
        org.junit.Assert.assertNotEquals("{}", inDbAfterWrongReq.payloadJson)
        // Verify next item NOT dispatched
        assertEquals(promptsBefore, dispatchedPrompts.size)

        // 3. Now call onHistoryCommitted with exact matching IDs:
        val confirmedCorrect = outboxService.onHistoryCommitted(
            conversationId = testConversationId,
            sessionId = "session-mismatch",
            turnId = "turn-real-1",
            requestId = entry.requestId
        )
        assertTrue(confirmedCorrect)

        val entry2 = DatabaseHelper.getConversationOutboxDao().getById("item-mismatch-2")!!
        awaitPromptDispatched(entry2.requestId)

        // Verify payload IS cleared
        val inDbAfterCorrect = DatabaseHelper.getConversationOutboxDao().getById("item-mismatch-1")!!
        assertEquals("{}", inDbAfterCorrect.payloadJson)
        // Verify next item IS dispatched
        assertTrue(dispatchedPrompts.size > promptsBefore)
        val entry2After = DatabaseHelper.getConversationOutboxDao().getById("item-mismatch-2")!!
        assertEquals("dispatching", entry2After.state)
    }

    @Test
    fun `enqueue in idle conversation without prior turn evidence enters paused and dispatches on resume`() = runBlocking {
        val freshConv = Conversation(
            title = "Fresh Idle Chat",
            mode = "agent",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val freshId = DatabaseHelper.insertConversation(freshConv)
        AcpAgentProfileStore(context).bindConversation(freshId, "xiaowan-acp")

        val snap = outboxService.mutate(
            freshId,
            "enqueue",
            OutboxMutation(id = "item-fresh-idle", payload = mapOf("text" to "Fresh idle msg"))
        )
        val item = snap.items.first()
        assertEquals("item-fresh-idle", item.id)
        assertEquals("paused", item.state)
        assertEquals("空闲无前序完成事实，等待确认发送", item.pauseReason)

        // Resuming the item will transition it to pending and dispatch it
        val resumedSnap = outboxService.mutate(
            freshId,
            "resume",
            OutboxMutation(id = "item-fresh-idle")
        )
        val resumedItem = resumedSnap.items.first()
        assertEquals("item-fresh-idle", resumedItem.id)
        val inDb = DatabaseHelper.getConversationOutboxDao().getById("item-fresh-idle")!!
        awaitPromptDispatched(inDb.requestId)
        val inDbAfter = DatabaseHelper.getConversationOutboxDao().getById("item-fresh-idle")!!
        assertEquals("dispatching", inDbAfter.state)
    }

    @Test
    fun `enqueue rejects agentId payload that conflicts with persistent conversation owner`() = runBlocking {
        val ownerConv = Conversation(
            title = "Owner Conflict Chat",
            mode = "agent",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val convId = DatabaseHelper.insertConversation(ownerConv)
        AcpAgentProfileStore(context).bindConversation(convId, "codex-acp")

        try {
            outboxService.mutate(
                convId,
                "enqueue",
                OutboxMutation(
                    id = "item-conflict-owner",
                    payload = mapOf(
                        "text" to "Hello",
                        "agentId" to "opencode-acp"
                    )
                )
            )
            fail("Expected agent_owner_conflict")
        } catch (e: OutboxException) {
            assertEquals("agent_owner_conflict", e.errorCode)
        }
    }

    @Test
    fun `resume during running active turn binds paused items to running turn and does not dispatch until turn completes`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. Start Turn A (running active turn)
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-run-a", payload = mapOf("text" to "Turn A prompt"))
        )
        val entryA = dao.getById("item-run-a")!!
        awaitPromptDispatched(entryA.requestId)
        outboxService.onPromptAdmitted(
            sessionId = "session-active-run",
            turnId = "turn-active-run",
            requestId = entryA.requestId,
            conversationId = testConversationId,
            userMessageId = entryA.userMessageId
        )

        // 2. Insert a paused item B (simulating an item that was paused)
        val itemB = ConversationOutboxEntry(
            id = "item-run-b",
            conversationId = testConversationId,
            agentId = "xiaowan-acp",
            payloadJson = "{\"text\":\"Turn B prompt\"}",
            state = ConversationOutboxEntry.STATE_PAUSED,
            revision = 2L,
            sequence = 2L,
            requestId = "req-run-b",
            userMessageId = "msg-run-b",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            pauseReason = "Manual pause"
        )
        dao.insert(itemB)

        val promptsBefore = dispatchedPrompts.size

        // 3. User resumes item B while Turn A is still running
        outboxService.mutate(
            testConversationId,
            "resume",
            OutboxMutation(id = "item-run-b")
        )

        // Verify: item B is now pending, atomically bound to Turn A, and NOT dispatched
        val itemBResumed = dao.getById("item-run-b")!!
        assertEquals(ConversationOutboxEntry.STATE_PENDING, itemBResumed.state)
        assertEquals("session-active-run", itemBResumed.blockedBySessionId)
        assertEquals("turn-active-run", itemBResumed.blockedByTurnId)
        assertNull(itemBResumed.pauseReason)
        assertEquals(promptsBefore, dispatchedPrompts.size)

        // 4. Turn A settles normally + history committed
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-active-run", "turn-active-run", entryA.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-active-run", "turn-active-run", "end_turn")
        outboxService.onPromptSettled(testConversationId, "session-active-run", "turn-active-run", entryA.requestId, "end_turn", null)
        val confirmed = outboxService.onHistoryCommitted(testConversationId, "session-active-run", "turn-active-run", entryA.requestId)
        assertTrue(confirmed)

        // Now Turn B naturally dispatches!
        awaitPromptDispatched("req-run-b")
        assertTrue(dispatchedPrompts.size > promptsBefore)
        val itemBDispatched = dao.getById("item-run-b")!!
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, itemBDispatched.state)
    }

    @Test
    fun `normal new turn admission replaces active turn and rebinds queued items while invalidating old stop target`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. Turn 1 active
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-t1", payload = mapOf("text" to "T1 prompt"))
        )
        val entryT1 = dao.getById("item-t1")!!
        outboxService.onPromptAdmitted(
            sessionId = "session-1",
            turnId = "turn-1",
            requestId = entryT1.requestId,
            conversationId = testConversationId,
            userMessageId = entryT1.userMessageId
        )

        // Enqueue item T2 and issue stop_and_send waiting for Turn 1
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-t2", payload = mapOf("text" to "T2 prompt"))
        )
        outboxService.mutate(
            testConversationId,
            "stop_and_send",
            OutboxMutation(id = "item-t2", expectedSessionId = "session-1", expectedTurnId = "turn-1")
        )

        val itemT2Waiting = dao.getById("item-t2")!!
        assertEquals("等待当前任务停止", itemT2Waiting.pauseReason)

        // 2. Normal external prompt admits Turn 2 preempting the conversation
        outboxService.onPromptAdmitted(
            sessionId = "session-2",
            turnId = "turn-2",
            requestId = null,
            conversationId = testConversationId,
            userMessageId = null
        )

        // Verify: item T2 is rebound to Turn 2 and pauseReason cleared
        val itemT2Rebound = dao.getById("item-t2")!!
        assertEquals(ConversationOutboxEntry.STATE_PENDING, itemT2Rebound.state)
        assertEquals("session-2", itemT2Rebound.blockedBySessionId)
        assertEquals("turn-2", itemT2Rebound.blockedByTurnId)
        assertNull(itemT2Rebound.pauseReason)

        val promptsBefore = dispatchedPrompts.size

        // 3. Old Turn 1 completes/cancelled: should NOT dispatch T2 because T2 is blocked by Turn 2!
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-1", "turn-1", entryT1.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-1", "turn-1", "cancelled")
        outboxService.onPromptSettled(testConversationId, "session-1", "turn-1", entryT1.requestId, "cancelled", null)
        outboxService.onHistoryCommitted(testConversationId, "session-1", "turn-1", entryT1.requestId)

        // T2 must NOT be dispatched on Turn 1's cancellation!
        assertEquals(promptsBefore, dispatchedPrompts.size)
        assertEquals(ConversationOutboxEntry.STATE_PENDING, dao.getById("item-t2")!!.state)
    }

    @Test
    fun `stop_and_send on paused item from old turn rebinds blocker to current active turn and dispatches after cancellation`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. Insert a paused item that was blocked by a historical old turn
        val oldPausedItem = ConversationOutboxEntry(
            id = "item-paused-old-turn",
            conversationId = testConversationId,
            agentId = "xiaowan-acp",
            payloadJson = "{\"text\":\"Old paused payload\"}",
            state = ConversationOutboxEntry.STATE_PAUSED,
            revision = 10L,
            sequence = 10L,
            requestId = "req-paused-old",
            userMessageId = "msg-paused-old",
            blockedBySessionId = "session-historical-dead",
            blockedByTurnId = "turn-historical-dead",
            createdAt = System.currentTimeMillis() - 10000L,
            updatedAt = System.currentTimeMillis() - 10000L,
            pauseReason = "历史任务失败暂停"
        )
        dao.insert(oldPausedItem)

        // 2. A new Turn is now active in the conversation
        val activeReqId = "req-current-active"
        outboxService.onPromptAdmitted(
            sessionId = "session-current-active",
            turnId = "turn-current-active",
            requestId = activeReqId,
            conversationId = testConversationId,
            userMessageId = "msg-current-active"
        )

        // 3. User targets the old paused item for stop_and_send against the current active turn
        outboxService.mutate(
            testConversationId,
            "stop_and_send",
            OutboxMutation(
                id = "item-paused-old-turn",
                expectedSessionId = "session-current-active",
                expectedTurnId = "turn-current-active"
            )
        )

        // Verify: target blocker is atomically rebound from dead turn to current active turn
        val targetAfterStop = dao.getById("item-paused-old-turn")!!
        assertEquals(ConversationOutboxEntry.STATE_PENDING, targetAfterStop.state)
        assertEquals("session-current-active", targetAfterStop.blockedBySessionId)
        assertEquals("turn-current-active", targetAfterStop.blockedByTurnId)
        assertEquals("等待当前任务停止", targetAfterStop.pauseReason)

        val promptsBefore = dispatchedPrompts.size

        // 4. Current active turn cancels and commits history
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-current-active", "turn-current-active", activeReqId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-current-active", "turn-current-active", "cancelled")
        outboxService.onPromptSettled(testConversationId, "session-current-active", "turn-current-active", activeReqId, "cancelled", null)
        val confirmed = outboxService.onHistoryCommitted(testConversationId, "session-current-active", "turn-current-active", activeReqId)
        assertTrue(confirmed)

        // 5. Target item proof matches and dispatches successfully!
        awaitPromptDispatched("req-paused-old")
        assertTrue(dispatchedPrompts.size > promptsBefore)
        val targetDispatched = dao.getById("item-paused-old-turn")!!
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, targetDispatched.state)
    }

    @Test
    fun `interleaved admission of new turn and settle of old turn preserves new active turn and prevents false idle dispatch`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. Turn A is admitted
        val reqA = "req-turn-a"
        outboxService.onPromptAdmitted(
            sessionId = "session-a",
            turnId = "turn-a",
            requestId = reqA,
            conversationId = testConversationId,
            userMessageId = "msg-a"
        )

        // 2. Turn B is admitted (interleaved before Turn A settles)
        val reqB = "req-turn-b"
        outboxService.onPromptAdmitted(
            sessionId = "session-b",
            turnId = "turn-b",
            requestId = reqB,
            conversationId = testConversationId,
            userMessageId = "msg-b"
        )

        // 3. Enqueue item C (which becomes pending and blocked by Turn B)
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-interleave-c", payload = mapOf("text" to "Item C text"))
        )
        val itemC = dao.getById("item-interleave-c")!!
        assertEquals("turn-b", itemC.blockedByTurnId)

        val promptsBefore = dispatchedPrompts.size

        // 4. Turn A settles: must NOT remove Turn B from activeTurns!
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-a", "turn-a", reqA)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-a", "turn-a", "end_turn")
        outboxService.onPromptSettled(testConversationId, "session-a", "turn-a", reqA, "end_turn", null)
        outboxService.onHistoryCommitted(testConversationId, "session-a", "turn-a", reqA)

        // Conversation is NOT falsely idle because Turn B is still active!
        // Item C is blocked by Turn B and must NOT be dispatched on Turn A's completion!
        assertEquals(promptsBefore, dispatchedPrompts.size)
        assertEquals(ConversationOutboxEntry.STATE_PENDING, dao.getById("item-interleave-c")!!.state)

        // 5. Turn B settles and commits: now Item C naturally dispatches!
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-b", "turn-b", reqB)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-b", "turn-b", "end_turn")
        outboxService.onPromptSettled(testConversationId, "session-b", "turn-b", reqB, "end_turn", null)
        val confirmedB = outboxService.onHistoryCommitted(testConversationId, "session-b", "turn-b", reqB)
        assertTrue(confirmedB)
        awaitPromptDispatched(itemC.requestId)
        assertTrue(dispatchedPrompts.size > promptsBefore)
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, dao.getById("item-interleave-c")!!.state)
    }

    @Test
    fun `enqueue and resume do not deadlock on conversation mutex and execute dispatch outside lock`() = runBlocking {
        // Enqueue into idle conversation with prior turn evidence dispatches without deadlocking
        val snap = outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-deadlock-check", payload = mapOf("text" to "No deadlock enqueue"))
        )
        assertNotNull(snap)
        val item = DatabaseHelper.getConversationOutboxDao().getById("item-deadlock-check")!!
        awaitPromptDispatched(item.requestId)
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, item.state)
    }

    @Test
    fun `stop_and_send with CancellationException error in onPromptSettled is allowed and dispatches target`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-canc-active", payload = mapOf("text" to "Active prompt"))
        )
        val entryActive = dao.getById("item-canc-active")!!
        outboxService.onPromptAdmitted(
            sessionId = "session-canc",
            turnId = "turn-canc",
            requestId = entryActive.requestId,
            conversationId = testConversationId,
            userMessageId = entryActive.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-canc-target", payload = mapOf("text" to "Target prompt"))
        )
        outboxService.mutate(
            testConversationId,
            "stop_and_send",
            OutboxMutation(id = "item-canc-target", expectedSessionId = "session-canc", expectedTurnId = "turn-canc")
        )

        val promptsBefore = dispatchedPrompts.size

        // Settles with cancelled and CancellationException
        val cancelError = kotlinx.coroutines.CancellationException("Client cancelled")
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-canc", "turn-canc", entryActive.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-canc", "turn-canc", "cancelled")
        outboxService.onPromptSettled(testConversationId, "session-canc", "turn-canc", entryActive.requestId, "cancelled", cancelError)
        val confirmed = outboxService.onHistoryCommitted(testConversationId, "session-canc", "turn-canc", entryActive.requestId)
        assertTrue(confirmed)

        // Target should be dispatched
        val targetBefore = dao.getById("item-canc-target")!!
        awaitPromptDispatched(targetBefore.requestId)
        assertTrue(dispatchedPrompts.size > promptsBefore)
        val targetInDb = dao.getById("item-canc-target")!!
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, targetInDb.state)
    }

    @Test
    fun `stop_and_send with TimeoutCancellationException error is rejected and does not dispatch`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-timeout-active", payload = mapOf("text" to "Active prompt"))
        )
        val entryActive = dao.getById("item-timeout-active")!!
        outboxService.onPromptAdmitted(
            sessionId = "session-timeout",
            turnId = "turn-timeout",
            requestId = entryActive.requestId,
            conversationId = testConversationId,
            userMessageId = entryActive.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-timeout-target", payload = mapOf("text" to "Target prompt"))
        )
        outboxService.mutate(
            testConversationId,
            "stop_and_send",
            OutboxMutation(id = "item-timeout-target", expectedSessionId = "session-timeout", expectedTurnId = "turn-timeout")
        )

        val promptsBefore = dispatchedPrompts.size

        // Produce a real TimeoutCancellationException
        val timeoutError = try {
            kotlinx.coroutines.withTimeout(1L) {
                kotlinx.coroutines.delay(50L)
            }
            null
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            e
        }
        assertNotNull(timeoutError)

        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-timeout", "turn-timeout", entryActive.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-timeout", "turn-timeout", "cancelled")
        outboxService.onPromptSettled(testConversationId, "session-timeout", "turn-timeout", entryActive.requestId, "cancelled", timeoutError)
        outboxService.onHistoryCommitted(testConversationId, "session-timeout", "turn-timeout", entryActive.requestId)

        // Target must NOT be dispatched on TimeoutCancellationException!
        assertEquals(promptsBefore, dispatchedPrompts.size)
        val targetInDb = dao.getById("item-timeout-target")!!
        org.junit.Assert.assertNotEquals(ConversationOutboxEntry.STATE_DISPATCHING, targetInDb.state)
    }

    @Test
    fun `stop_and_send with arbitrary non-cancellation error is rejected and does not dispatch`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-arb-active", payload = mapOf("text" to "Active prompt"))
        )
        val entryActive = dao.getById("item-arb-active")!!
        outboxService.onPromptAdmitted(
            sessionId = "session-arb",
            turnId = "turn-arb",
            requestId = entryActive.requestId,
            conversationId = testConversationId,
            userMessageId = entryActive.userMessageId
        )

        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-arb-target", payload = mapOf("text" to "Target prompt"))
        )
        outboxService.mutate(
            testConversationId,
            "stop_and_send",
            OutboxMutation(id = "item-arb-target", expectedSessionId = "session-arb", expectedTurnId = "turn-arb")
        )

        val promptsBefore = dispatchedPrompts.size

        val arbitraryError = RuntimeException("Arbitrary IO Failure")
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-arb", "turn-arb", entryActive.requestId)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-arb", "turn-arb", "cancelled")
        outboxService.onPromptSettled(testConversationId, "session-arb", "turn-arb", entryActive.requestId, "cancelled", arbitraryError)
        outboxService.onHistoryCommitted(testConversationId, "session-arb", "turn-arb", entryActive.requestId)

        // Target must NOT be dispatched on arbitrary non-cancellation error!
        assertEquals(promptsBefore, dispatchedPrompts.size)
        val targetInDb = dao.getById("item-arb-target")!!
        org.junit.Assert.assertNotEquals(ConversationOutboxEntry.STATE_DISPATCHING, targetInDb.state)
    }

    @Test
    fun `dispatching item B overtaken by concurrent D admission rebinds to D without duplicate user append and dispatches after D`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        var bAppendedCount = 0
        val testService = ConversationOutboxService(
            context = context,
            scope = scope,
            agentRuntimeManager = runtimeManager,
            conversationDomainService = conversationDomainService,
            promptDispatcher = { method, args ->
                val reqId = args["requestId"]?.toString()
                val record = mapOf("method" to method, "args" to args)
                dispatchedPrompts.add(record)
                if (reqId != null) {
                    promptDispatchedSignals.computeIfAbsent(reqId) { CompletableDeferred() }.complete(record)
                }
                mapOf("status" to "ok")
            },
            userMessageAppender = { conversationId, conversationMode, entryId, text, attachments, createdAt ->
                bAppendedCount++
                appendedMessages.add(
                    mapOf(
                        "conversationId" to conversationId,
                        "conversationMode" to conversationMode,
                        "entryId" to entryId,
                        "text" to text
                    )
                )
            }
        )

        establishPriorTurnEvidence(testConversationId, service = testService)
        testService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-b-race", payload = mapOf("text" to "Prompt B"))
        )
        val entryB = dao.getById("item-b-race")!!

        // D is admitted concurrently before B is admitted
        testService.onPromptAdmitted(
            sessionId = "session-d-race",
            turnId = "turn-d-race",
            requestId = "req-d-race",
            conversationId = testConversationId,
            userMessageId = "user-msg-d-race"
        )

        // Verify B has been rebound to D in PENDING state, and has NOT appended any user message
        val bAfterDAdmission = dao.getById("item-b-race")!!
        assertEquals(ConversationOutboxEntry.STATE_PENDING, bAfterDAdmission.state)
        assertEquals("session-d-race", bAfterDAdmission.blockedBySessionId)
        assertEquals("turn-d-race", bAfterDAdmission.blockedByTurnId)
        assertEquals(0, bAppendedCount)

        // Now D finishes with end_turn and receipt
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-d-race", "turn-d-race", "req-d-race")
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-d-race", "turn-d-race", "end_turn")
        testService.onPromptSettled(testConversationId, "session-d-race", "turn-d-race", "req-d-race", "end_turn", null)
        testService.onHistoryCommitted(testConversationId, "session-d-race", "turn-d-race", "req-d-race")
        awaitPromptDispatched(entryB.requestId)
        // Now B is admitted
        testService.onPromptAdmitted(
            sessionId = "session-b-race",
            turnId = "turn-b-race",
            requestId = entryB.requestId,
            conversationId = testConversationId,
            userMessageId = entryB.userMessageId
        )

        // Verify B's user message was appended exactly ONCE
        assertEquals(1, bAppendedCount)
        val bFinal = dao.getById("item-b-race")!!
        assertEquals(ConversationOutboxEntry.STATE_SUBMITTED, bFinal.state)
    }

    @Test
    fun `delayed receipt of A does not awaken paused items if subsequent turn D was cancelled`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. A starts and is admitted
        outboxService.onPromptAdmitted(
            sessionId = "session-a-delayed",
            turnId = "turn-a-delayed",
            requestId = "req-a-delayed",
            conversationId = testConversationId,
            userMessageId = "user-a-delayed"
        )

        // 2. Enqueue B and C (blocked by A)
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-b-delayed", payload = mapOf("text" to "Prompt B"))
        )
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-c-delayed", payload = mapOf("text" to "Prompt C"))
        )

        // 3. A ends with end_turn, but receipt is delayed!
        // onPromptSettled pauses B and C waiting for history preservation ("等待历史保存")
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-a-delayed", "turn-a-delayed", "req-a-delayed")
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-a-delayed", "turn-a-delayed", "end_turn")
        outboxService.onPromptSettled(testConversationId, "session-a-delayed", "turn-a-delayed", "req-a-delayed", "end_turn", null)

        val bPaused = dao.getById("item-b-delayed")!!
        assertEquals(ConversationOutboxEntry.STATE_PAUSED, bPaused.state)
        assertEquals("等待历史保存", bPaused.pauseReason)

        // 4. Normal prompt D arrives and is admitted before A's receipt!
        outboxService.onPromptAdmitted(
            sessionId = "session-d-delayed",
            turnId = "turn-d-delayed",
            requestId = "req-d-delayed",
            conversationId = testConversationId,
            userMessageId = "user-d-delayed"
        )

        // B and C should be rebound to D while keeping pauseReason == "等待历史保存"
        val bReboundToD = dao.getById("item-b-delayed")!!
        assertEquals(ConversationOutboxEntry.STATE_PAUSED, bReboundToD.state)
        assertEquals("session-d-delayed", bReboundToD.blockedBySessionId)
        assertEquals("turn-d-delayed", bReboundToD.blockedByTurnId)
        assertEquals("等待历史保存", bReboundToD.pauseReason)

        // 5. User cancels/stops D!
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-d-delayed", "turn-d-delayed", "req-d-delayed")
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-d-delayed", "turn-d-delayed", "cancelled")
        val cancelEx = kotlinx.coroutines.CancellationException("User stopped task")
        outboxService.onPromptSettled(testConversationId, "session-d-delayed", "turn-d-delayed", "req-d-delayed", "cancelled", cancelEx)

        // B and C are now paused with "前序任务已取消" blocked by D
        val bCancelledD = dao.getById("item-b-delayed")!!
        assertEquals(ConversationOutboxEntry.STATE_PAUSED, bCancelledD.state)
        assertEquals("前序任务已取消", bCancelledD.pauseReason)
        assertEquals("turn-d-delayed", bCancelledD.blockedByTurnId)

        val promptsBeforeLateReceipt = dispatchedPrompts.size

        // 6. Now A's delayed receipt FINALLY arrives!
        outboxService.onHistoryCommitted(testConversationId, "session-a-delayed", "turn-a-delayed", "req-a-delayed")

        // Assert: B and C MUST REMAIN PAUSED, MUST NOT BE DISPATCHED!
        val bAfterLateReceipt = dao.getById("item-b-delayed")!!
        val cAfterLateReceipt = dao.getById("item-c-delayed")!!
        assertEquals(ConversationOutboxEntry.STATE_PAUSED, bAfterLateReceipt.state)
        assertEquals(ConversationOutboxEntry.STATE_PAUSED, cAfterLateReceipt.state)
        assertEquals("前序任务已取消", bAfterLateReceipt.pauseReason)
        assertEquals("前序任务已取消", cAfterLateReceipt.pauseReason)
        assertEquals(promptsBeforeLateReceipt, dispatchedPrompts.size)
    }

    @Test
    fun `asynchronous dispatch does not block onHistoryCommitted and subsequent turn runs in detached scope`() = runBlocking {
        val dao = DatabaseHelper.getConversationOutboxDao()

        // 1. Admit Turn A
        val reqA = "req-async-a"
        outboxService.onPromptAdmitted(
            sessionId = "session-async",
            turnId = "turn-async-a",
            requestId = reqA,
            conversationId = testConversationId,
            userMessageId = "msg-async-a"
        )

        // 2. Enqueue Turn B
        outboxService.mutate(
            testConversationId,
            "enqueue",
            OutboxMutation(id = "item-async-b", payload = mapOf("text" to "Async turn B prompt"))
        )
        val entryB = dao.getById("item-async-b")!!

        // 3. Configure hook on Turn B: simulate long-running LLM call suspended until released
        val bStarted = CompletableDeferred<Unit>()
        val bRelease = CompletableDeferred<Unit>()
        promptDispatcherHooks[entryB.requestId] = {
            bStarted.complete(Unit)
            bRelease.await() // Suspends inside Turn B dispatcher
        }

        // 4. Turn A settles
        AcpTaskTerminalJournal.recordAdmission(context, "xiaowan-acp", "session-async", "turn-async-a", reqA)
        AcpTaskTerminalJournal.recordTerminal(context, "xiaowan-acp", "session-async", "turn-async-a", "end_turn")
        outboxService.onPromptSettled(testConversationId, "session-async", "turn-async-a", reqA, "end_turn", null)

        // 5. Signal 2: History committed for Turn A
        // MUST return immediately (true) without being blocked by Turn B's suspension!
        val historyCommitConfirmed = withTimeout(1000L) {
            outboxService.onHistoryCommitted(testConversationId, "session-async", "turn-async-a", reqA)
        }
        assertTrue("onHistoryCommitted must return true immediately without blocking", historyCommitConfirmed)

        // 6. Turn B started asynchronously in detached scope
        withTimeout(1000L) {
            bStarted.await()
        }

        // 7. Release Turn B and verify clean completion
        bRelease.complete(Unit)
        awaitPromptDispatched(entryB.requestId)
        val entryBAfter = dao.getById("item-async-b")!!
        assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, entryBAfter.state)
    }

    @Test
    fun `queued user history follows predecessor completion and duplicate admission stays idempotent`() = runBlocking {
        val service = ConversationOutboxService(
            context = context,
            scope = scope,
            agentRuntimeManager = runtimeManager,
            conversationDomainService = conversationDomainService,
            promptDispatcher = { _, _ -> mapOf("status" to "ok") }
        )
        service.awaitReady()
        val dao = DatabaseHelper.getConversationOutboxDao()
        val queuedAt = System.currentTimeMillis() - 10_000L
        val predecessorFinishedAt = queuedAt + 5_000L
        val entry = ConversationOutboxEntry(
            id = "item-ordered-history",
            conversationId = testConversationId,
            agentId = "xiaowan-acp",
            payloadJson = "{\"text\":\"排队后的用户请求\"}",
            state = ConversationOutboxEntry.STATE_DISPATCHING,
            revision = 1L,
            sequence = 1L,
            requestId = "req-ordered-history",
            userMessageId = "msg-ordered-history",
            createdAt = queuedAt,
            updatedAt = queuedAt
        )
        dao.insert(entry)
        val history = AgentConversationHistoryRepository(context)
        history.upsertAssistantMessage(
            conversationId = testConversationId,
            conversationMode = "agent",
            entryId = "predecessor-answer",
            text = "前序任务已完成",
            createdAt = predecessorFinishedAt
        )

        repeat(2) {
            service.onPromptAdmitted(
                sessionId = "session-ordered-history",
                turnId = "turn-ordered-history",
                requestId = entry.requestId,
                conversationId = testConversationId,
                userMessageId = entry.userMessageId
            )
        }

        val messages = history.listConversationMessages(testConversationId, "agent")
        assertEquals(listOf(entry.userMessageId, "predecessor-answer"), messages.map { it["id"] })
        val userMessage = messages.single { it["id"] == entry.userMessageId }
        assertTrue(ConversationSnapshotOrdering.resolveCreatedAtMillis(userMessage)!! > predecessorFinishedAt)
        assertEquals(queuedAt, dao.getById(entry.id)!!.createdAt)
    }
}
