package cn.com.omnimind.bot.agent.conversation

import android.content.Context
import android.net.Uri
import cn.com.omnimind.baselib.database.Conversation
import cn.com.omnimind.baselib.database.ConversationOutboxDao
import cn.com.omnimind.baselib.database.ConversationOutboxEntry
import cn.com.omnimind.baselib.database.ConversationOutboxMeta
import cn.com.omnimind.baselib.database.ConversationOutboxMetaDao
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.AgentConversationModePolicy
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.REMOTE_CODEX_AGENT_ID
import cn.com.omnimind.bot.task.runtime.AcpTaskTerminalJournal
import cn.com.omnimind.bot.task.runtime.AcpTerminalLookupResult
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.FlutterChatSyncBridge
import cn.com.omnimind.bot.webchat.RealtimeHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class OutboxItem(
    val id: String,
    val conversationId: Long,
    val agentId: String,
    val state: String,
    val revision: Long,
    val sequence: Long,
    val requestId: String,
    val userMessageId: String,
    val blockedBySessionId: String? = null,
    val blockedByTurnId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pauseReason: String? = null,
    val payload: Map<String, Any?>? = null
) {
    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("conversationId", conversationId)
        put("agentId", agentId)
        put("state", state)
        put("revision", revision)
        put("sequence", sequence)
        put("requestId", requestId)
        put("userMessageId", userMessageId)
        blockedBySessionId?.let { put("blockedBySessionId", it) }
        blockedByTurnId?.let { put("blockedByTurnId", it) }
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        pauseReason?.let { put("pauseReason", it) }
        payload?.let { put("payload", it) }
    }
}

data class OutboxSnapshot(
    val revision: Long,
    val items: List<OutboxItem>
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "revision" to revision,
        "items" to items.map { it.toMap() }
    )
}

data class OutboxMutation(
    val id: String? = null,
    val expectedRevision: Long? = null,
    val payload: Map<String, Any?>? = null,
    val expectedSessionId: String? = null,
    val expectedTurnId: String? = null
)

class OutboxException(
    val errorCode: String,
    message: String,
    val snapshot: OutboxSnapshot? = null
) : Exception(message)

class ConversationOutboxService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val agentRuntimeManager: AgentRuntimeManager,
    private val conversationDomainService: ConversationDomainService = ConversationDomainService(context),
    private val promptDispatcher: suspend (method: String, args: Map<String, Any?>) -> Any? = { method, args ->
        if (method == "session/prompt") {
            agentRuntimeManager.executeOutboxPrompt(args)
        } else {
            agentRuntimeManager.handleMethod(method, args)
        }
    },
    private val userMessageAppender: suspend (
        conversationId: Long,
        conversationMode: String,
        entryId: String,
        text: String,
        attachments: List<Map<String, Any?>>,
        createdAt: Long
    ) -> Unit = { conversationId, conversationMode, entryId, text, attachments, createdAt ->
        conversationDomainService.appendUserMessage(
            conversationId = conversationId,
            conversationMode = conversationMode,
            entryId = entryId,
            text = text,
            attachments = attachments,
            createdAt = createdAt
        )
    }
) {
    private val appContext = context.applicationContext

    private data class ActiveTurn(
        val sessionId: String,
        val turnId: String,
        val requestId: String?
    )

    private data class TurnFactKey(
        val conversationId: Long,
        val sessionId: String,
        val turnId: String,
        val requestId: String
    )

    private data class SettledTurn(
        val conversationId: Long,
        val sessionId: String,
        val turnId: String,
        val requestId: String,
        val stopReason: String?,
        val error: Throwable?,
        val settledAt: Long
    )

    private val conversationLocks = ConcurrentHashMap<Long, Mutex>()
    private val activeTurns = ConcurrentHashMap<Long, ActiveTurn>()
    private val settledTurns = ConcurrentHashMap<TurnFactKey, SettledTurn>()
    private val historyCommittedTurns = ConcurrentHashMap<TurnFactKey, Boolean>()
    private val lastSettledTurnByConversation = ConcurrentHashMap<Long, SettledTurn>()
    private data class StopAndSendAuthorization(
        val targetId: String,
        val expectedSessionId: String,
        val expectedTurnId: String
    )

    private val waitingStopAndSendTarget = ConcurrentHashMap<Long, StopAndSendAuthorization>()

    private fun isCancellationError(error: Throwable?): Boolean {
        if (error == null) return true
        if (error is kotlinx.coroutines.TimeoutCancellationException) return false
        return error is java.util.concurrent.CancellationException || error is kotlinx.coroutines.CancellationException
    }

    private suspend fun getPersistentOwner(conversationId: Long): String? {
        val store = AcpAgentProfileStore(appContext)
        val conversationOwner = store.agentIdForConversation(conversationId)
        if (conversationOwner != null) return conversationOwner
        val binding = DatabaseHelper.getAgentSessionBindingByConversationId(conversationId)
        return binding?.threadId?.let { store.agentIdForSession(it) }
    }

    private suspend fun pauseDispatchingItem(conversationId: Long, itemId: String, reason: String) {
        val lock = getConversationMutex(conversationId)
        var newRevToNotify: Long? = null
        lock.withLock {
            val cur = outboxDao.getById(itemId)
            if (cur != null && cur.state == ConversationOutboxEntry.STATE_DISPATCHING) {
                val now = System.currentTimeMillis()
                val newRev = DatabaseHelper.withTransaction {
                    val rev = metaDao.nextRevision(conversationId) ?: (cur.revision + 1)
                    val paused = cur.copy(
                        state = ConversationOutboxEntry.STATE_PAUSED,
                        pauseReason = reason,
                        revision = rev,
                        updatedAt = now
                    )
                    outboxDao.update(paused)
                    rev
                }
                newRevToNotify = newRev
            }
        }
        dispatchingItemByConversation.remove(conversationId, itemId)
        newRevToNotify?.let { notifyChanged(conversationId, it) }
    }
    private val dispatchingItemByConversation = ConcurrentHashMap<Long, String>()

    private val isReconciled = AtomicBoolean(false)
    private val readinessDeferred = CompletableDeferred<Unit>()

    fun isReady(): Boolean = isReconciled.get()
    suspend fun awaitReady() = readinessDeferred.await()
    fun markReady() {
        isReconciled.set(true)
        if (!readinessDeferred.isCompleted) {
            readinessDeferred.complete(Unit)
        }
    }

    init {
        scope.launch {
            reconcileOnStartup()
        }
    }

    suspend fun ensureReady() {
        if (!isReconciled.get()) {
            try {
                readinessDeferred.await()
            } catch (_: Exception) {}
        }
    }

    private fun getConversationMutex(conversationId: Long): Mutex =
        conversationLocks.computeIfAbsent(conversationId) { Mutex() }

    private val outboxDao: ConversationOutboxDao
        get() = DatabaseHelper.getConversationOutboxDao()

    private val metaDao: ConversationOutboxMetaDao
        get() = DatabaseHelper.getConversationOutboxMetaDao()

    suspend fun list(conversationId: Long): OutboxSnapshot {
        ensureReady()
        val lock = getConversationMutex(conversationId)
        return lock.withLock {
            getSnapshotLocked(conversationId)
        }
    }

    suspend fun mutate(
        conversationId: Long,
        operation: String,
        request: OutboxMutation
    ): OutboxSnapshot {
        ensureReady()
        val lock = getConversationMutex(conversationId)
        var shouldDispatch = false
        val snapshot = lock.withLock {
            when (operation) {
                OPERATION_ENQUEUE -> {
                    val res = mutateEnqueueLocked(conversationId, request)
                    shouldDispatch = res.second
                    res.first
                }
                OPERATION_UPDATE -> mutateUpdateLocked(conversationId, request)
                OPERATION_REMOVE -> mutateRemoveLocked(conversationId, request)
                OPERATION_RESUME -> {
                    val res = mutateResumeLocked(conversationId, request)
                    shouldDispatch = res.second
                    res.first
                }
                OPERATION_STOP_AND_SEND -> mutateStopAndSendLocked(conversationId, request)
                else -> throw OutboxException(
                    "outbox_invalid_operation",
                    "未知的操作类型: $operation",
                    getSnapshotLocked(conversationId)
                )
            }
        }
        if (shouldDispatch) {
            checkAndDispatchNext(conversationId)
        }
        return snapshot
    }

    private suspend fun mutateEnqueueLocked(
        conversationId: Long,
        request: OutboxMutation
    ): Pair<OutboxSnapshot, Boolean> {
        val conversation = DatabaseHelper.getConversationById(conversationId)
            ?: throw OutboxException("conversation_not_found", "会话不存在: $conversationId")

        val itemId = request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val existingById = outboxDao.getById(itemId)
        if (existingById != null) {
            if (existingById.conversationId == conversationId) {
                // 重复ID仅同conversation：同一会话重复提交幂等返回现有快照
                return Pair(getSnapshotLocked(conversationId), false)
            } else {
                // 跨会话同ID：拒绝冲突
                throw OutboxException(
                    "outbox_id_conflict",
                    "条目 ID 已在其他会话中存在: $itemId",
                    getSnapshotLocked(conversationId)
                )
            }
        }

        val rawPayload = request.payload ?: emptyMap()
        val text = rawPayload["text"]?.toString().orEmpty()
        val attachments = normalizeListOfMaps(rawPayload["attachments"])

        // 先核重复后失效附件
        validateTextAndAttachments(text, attachments)

        val activeCount = outboxDao.countActiveByConversationId(conversationId)
        if (activeCount >= MAX_ACTIVE_ITEMS) {
            throw OutboxException(
                "outbox_limit_exceeded",
                "待发箱已达上限 ($MAX_ACTIVE_ITEMS 条)",
                getSnapshotLocked(conversationId)
            )
        }

        val meta = metaDao.getOrCreate(conversationId)
        if (request.expectedRevision != null && meta.revision != request.expectedRevision) {
            throw OutboxException(
                "outbox_revision_conflict",
                "版本冲突: 当前版本 ${meta.revision}, 期望版本 ${request.expectedRevision}",
                getSnapshotLocked(conversationId)
            )
        }

        val safePayload = sanitizePayload(conversationId, rawPayload, conversation)
        val payloadJson = safeMapToJson(safePayload)
        val now = System.currentTimeMillis()

        val activeTurn = activeTurns[conversationId]
        val initialBlockedSessionId = activeTurn?.sessionId
        val initialBlockedTurnId = activeTurn?.turnId

        // 确定初始状态：
        // 1. 若有 activeTurn：处于 pending 状态，阻塞于该 activeTurn
        // 2. 若空闲（activeTurn == null）：
        //    核对上一结算与历史屏障。若存在未正常完成或历史缺失的前序 Turn，置 paused
        //    若队首已有 paused 条目，后置项亦 paused（paused 队首不可被越过）
        val (initialState, initialPauseReason) = if (activeTurn != null) {
            ConversationOutboxEntry.STATE_PENDING to null
        } else {
            val lastSettled = lastSettledTurnByConversation[conversationId]
            val pausedEntries = outboxDao.getByConversationIdAndState(
                conversationId,
                ConversationOutboxEntry.STATE_PAUSED
            )
            if (pausedEntries.isNotEmpty()) {
                ConversationOutboxEntry.STATE_PAUSED to "队首任务已暂停，后续任务排队暂停"
            } else if (lastSettled != null) {
                val lastKey = TurnFactKey(lastSettled.conversationId, lastSettled.sessionId, lastSettled.turnId, lastSettled.requestId)
                val isNormal = lastSettled.stopReason == "end_turn" && lastSettled.error == null
                val isHistoryCommitted = historyCommittedTurns.containsKey(lastKey)
                if (!isNormal) {
                    ConversationOutboxEntry.STATE_PAUSED to "前序任务未正常结束: ${lastSettled.stopReason ?: "异常"}"
                } else if (!isHistoryCommitted) {
                    ConversationOutboxEntry.STATE_PAUSED to "等待历史保存"
                } else {
                    ConversationOutboxEntry.STATE_PENDING to null
                }
            } else {
                // 空闲且无可证明正常完成的前序 Turn，置 paused 等待用户确认发送
                ConversationOutboxEntry.STATE_PAUSED to "空闲无前序完成事实，等待确认发送"
            }
        }

        val requestId = (rawPayload["requestId"] as? String)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val userMessageId = (rawPayload["userMessageId"] as? String)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        val requestedAgent = (rawPayload["agentId"] as? String)?.takeIf { it.isNotBlank() }
        val store = AcpAgentProfileStore(appContext)
        val conversationOwner = store.agentIdForConversation(conversationId)
        val binding = DatabaseHelper.getAgentSessionBindingByConversationId(conversationId)
        val sessionOwner = binding?.threadId?.let { store.agentIdForSession(it) }

        val resolution = try {
            AgentConversationModePolicy.resolveHarness(
                conversationMode = conversation.mode,
                requestedAgentId = requestedAgent,
                conversationAgentId = conversationOwner,
                sessionAgentId = sessionOwner,
                xiaowanAgentId = AcpAgentProfileStore.XIAOWAN_AGENT_ID
            )
        } catch (e: Exception) {
            throw OutboxException(
                "agent_owner_conflict",
                "所有者校验失败: ${e.message}",
                getSnapshotLocked(conversationId)
            )
        }

        if (resolution.hasConflict) {
            throw OutboxException(
                "agent_owner_conflict",
                "会话已有持久化所有者 ${resolution.conflictWithAgentId}，禁止通过待发载荷切换所有者",
                getSnapshotLocked(conversationId)
            )
        }

        val agentId = resolution.agentId

        val newRevision = DatabaseHelper.withTransaction {
            val allocation = metaDao.allocateSequence(conversationId, request.expectedRevision)
                ?: throw OutboxException(
                    "outbox_revision_conflict",
                    "版本冲突: 分配序号失败",
                    getSnapshotLocked(conversationId)
                )

            val entry = ConversationOutboxEntry(
                id = itemId,
                conversationId = conversationId,
                agentId = agentId,
                payloadJson = payloadJson,
                state = initialState,
                revision = allocation.newRevision,
                sequence = allocation.allocatedSequence,
                requestId = requestId,
                userMessageId = userMessageId,
                blockedBySessionId = initialBlockedSessionId,
                blockedByTurnId = initialBlockedTurnId,
                createdAt = now,
                updatedAt = now,
                pauseReason = initialPauseReason
            )
            outboxDao.insert(entry)
            allocation.newRevision
        }

        notifyChanged(conversationId, newRevision)

        val shouldDispatch = initialState == ConversationOutboxEntry.STATE_PENDING && activeTurn == null
        return Pair(getSnapshotLocked(conversationId), shouldDispatch)
    }

    private suspend fun mutateUpdateLocked(
        conversationId: Long,
        request: OutboxMutation
    ): OutboxSnapshot {
        val itemId = request.id ?: throw OutboxException(
            "outbox_invalid_operation",
            "更新操作必须提供 id",
            getSnapshotLocked(conversationId)
        )
        val existing = outboxDao.getById(itemId)
            ?: throw OutboxException(
                "outbox_item_not_found",
                "待发条目未找到: $itemId",
                getSnapshotLocked(conversationId)
            )

        if (existing.conversationId != conversationId) {
            throw OutboxException(
                "outbox_item_not_found",
                "条目不属于当前会话",
                getSnapshotLocked(conversationId)
            )
        }

        if (existing.state == ConversationOutboxEntry.STATE_SUBMITTED ||
            existing.state == ConversationOutboxEntry.STATE_DISPATCHING) {
            throw OutboxException(
                "outbox_immutable_state",
                "已投递或正在发送中的条目不可修改",
                getSnapshotLocked(conversationId)
            )
        }

        val meta = metaDao.getOrCreate(conversationId)
        if (request.expectedRevision != null && meta.revision != request.expectedRevision) {
            throw OutboxException(
                "outbox_revision_conflict",
                "版本冲突: 当前版本 ${meta.revision}, 期望版本 ${request.expectedRevision}",
                getSnapshotLocked(conversationId)
            )
        }

        val rawPayload = request.payload ?: emptyMap()
        val text = rawPayload["text"]?.toString().orEmpty()
        val attachments = normalizeListOfMaps(rawPayload["attachments"])
        validateTextAndAttachments(text, attachments)

        val conversation = DatabaseHelper.getConversationById(conversationId)
            ?: throw OutboxException("conversation_not_found", "会话不存在: $conversationId")
        val safePayload = sanitizePayload(conversationId, rawPayload, conversation)
        val payloadJson = safeMapToJson(safePayload)

        val newRev = DatabaseHelper.withTransaction {
            val rev = metaDao.nextRevision(conversationId, request.expectedRevision)
                ?: throw OutboxException(
                    "outbox_revision_conflict",
                    "版本冲突: 更新版本失败",
                    getSnapshotLocked(conversationId)
                )

            val updated = existing.copy(
                payloadJson = payloadJson,
                revision = rev,
                updatedAt = System.currentTimeMillis()
            )
            outboxDao.update(updated)
            rev
        }

        notifyChanged(conversationId, newRev)

        return getSnapshotLocked(conversationId)
    }

    private suspend fun mutateRemoveLocked(
        conversationId: Long,
        request: OutboxMutation
    ): OutboxSnapshot {
        val itemId = request.id ?: throw OutboxException(
            "outbox_invalid_operation",
            "移除操作必须提供 id",
            getSnapshotLocked(conversationId)
        )
        val existing = outboxDao.getById(itemId)
            ?: throw OutboxException(
                "outbox_item_not_found",
                "待发条目未找到: $itemId",
                getSnapshotLocked(conversationId)
            )

        if (existing.conversationId != conversationId) {
            throw OutboxException(
                "outbox_item_not_found",
                "条目不属于当前会话",
                getSnapshotLocked(conversationId)
            )
        }

        if (existing.state == ConversationOutboxEntry.STATE_SUBMITTED ||
            existing.state == ConversationOutboxEntry.STATE_DISPATCHING) {
            throw OutboxException(
                "outbox_immutable_state",
                "已投递或正在发送中的条目不可删除",
                getSnapshotLocked(conversationId)
            )
        }

        val meta = metaDao.getOrCreate(conversationId)
        if (request.expectedRevision != null && meta.revision != request.expectedRevision) {
            throw OutboxException(
                "outbox_revision_conflict",
                "版本冲突: 当前版本 ${meta.revision}, 期望版本 ${request.expectedRevision}",
                getSnapshotLocked(conversationId)
            )
        }

        val newRev = DatabaseHelper.withTransaction {
            val rev = metaDao.nextRevision(conversationId, request.expectedRevision)
                ?: throw OutboxException(
                    "outbox_revision_conflict",
                    "版本冲突: 更新版本失败",
                    getSnapshotLocked(conversationId)
                )

            outboxDao.delete(existing)
            if (waitingStopAndSendTarget[conversationId]?.targetId == itemId) {
                waitingStopAndSendTarget.remove(conversationId)
            }
            rev
        }

        notifyChanged(conversationId, newRev)

        return getSnapshotLocked(conversationId)
    }

    private suspend fun mutateResumeLocked(
        conversationId: Long,
        request: OutboxMutation
    ): Pair<OutboxSnapshot, Boolean> {
        val meta = metaDao.getOrCreate(conversationId)
        if (request.expectedRevision != null && meta.revision != request.expectedRevision) {
            throw OutboxException(
                "outbox_revision_conflict",
                "版本冲突: 当前版本 ${meta.revision}, 期望版本 ${request.expectedRevision}",
                getSnapshotLocked(conversationId)
            )
        }

        val lastSettled = lastSettledTurnByConversation[conversationId]
        if (lastSettled != null &&
            lastSettled.stopReason == "end_turn" &&
            lastSettled.error == null) {
            val lastKey = TurnFactKey(lastSettled.conversationId, lastSettled.sessionId, lastSettled.turnId, lastSettled.requestId)
            if (!historyCommittedTurns.containsKey(lastKey)) {
                throw OutboxException(
                    "outbox_history_uncommitted",
                    "等待前序任务历史保存后才能继续发送",
                    getSnapshotLocked(conversationId)
                )
            }
        }

        val pausedItems = outboxDao.getByConversationIdAndState(
            conversationId,
            ConversationOutboxEntry.STATE_PAUSED
        )
        if (pausedItems.isEmpty()) {
            return Pair(getSnapshotLocked(conversationId), false)
        }
        val currentActive = activeTurns[conversationId]
        val sortedPaused = pausedItems.sortedBy { it.sequence }
        val now = System.currentTimeMillis()

        val newRev = DatabaseHelper.withTransaction {
            val rev = metaDao.nextRevision(conversationId, request.expectedRevision)
                ?: throw OutboxException(
                    "outbox_revision_conflict",
                    "版本冲突: 更新版本失败",
                    getSnapshotLocked(conversationId)
                )

            if (currentActive != null) {
                // 当前有运行中任务：原子将恢复项绑定至当前 activeTurn，进入 pending 排队
                for (item in sortedPaused) {
                    outboxDao.update(
                        item.copy(
                            state = ConversationOutboxEntry.STATE_PENDING,
                            blockedBySessionId = currentActive.sessionId,
                            blockedByTurnId = currentActive.turnId,
                            pauseReason = null,
                            revision = rev,
                            updatedAt = now
                        )
                    )
                }
            } else {
                // 空闲状态：清除陈旧阻塞标记，恢复为 pending 排队
                for (item in sortedPaused) {
                    outboxDao.update(
                        item.copy(
                            state = ConversationOutboxEntry.STATE_PENDING,
                            blockedBySessionId = null,
                            blockedByTurnId = null,
                            pauseReason = null,
                            revision = rev,
                            updatedAt = now
                        )
                    )
                }
            }
            rev
        }

        notifyChanged(conversationId, newRev)
        val shouldDispatch = currentActive == null
        return Pair(getSnapshotLocked(conversationId), shouldDispatch)
    }

    private suspend fun mutateStopAndSendLocked(
        conversationId: Long,
        request: OutboxMutation
    ): OutboxSnapshot {
        val targetId = request.id ?: throw OutboxException(
            "outbox_invalid_operation",
            "stop_and_send 必须指定目标项 id",
            getSnapshotLocked(conversationId)
        )
        val target = outboxDao.getById(targetId)
            ?: throw OutboxException(
                "outbox_item_not_found",
                "目标条目不存在: $targetId",
                getSnapshotLocked(conversationId)
            )

        if (target.conversationId != conversationId) {
            throw OutboxException(
                "outbox_item_not_found",
                "目标条目不属于当前会话",
                getSnapshotLocked(conversationId)
            )
        }

        if (target.state != ConversationOutboxEntry.STATE_PENDING &&
            target.state != ConversationOutboxEntry.STATE_PAUSED) {
            throw OutboxException(
                "outbox_immutable_state",
                "仅 pending 或 paused 状态的条目可执行 stop_and_send",
                getSnapshotLocked(conversationId)
            )
        }

        val expectedSession = request.expectedSessionId
        val expectedTurn = request.expectedTurnId
        if (expectedSession.isNullOrBlank() || expectedTurn.isNullOrBlank()) {
            throw OutboxException(
                "outbox_invalid_operation",
                "stop_and_send 必须提供非空的 expectedSessionId 与 expectedTurnId",
                getSnapshotLocked(conversationId)
            )
        }

        val active = activeTurns[conversationId]
        if (active == null || active.sessionId != expectedSession || active.turnId != expectedTurn) {
            throw OutboxException(
                "outbox_target_changed",
                "当前活跃任务已发生变化或已结束",
                getSnapshotLocked(conversationId)
            )
        }

        val meta = metaDao.getOrCreate(conversationId)
        if (request.expectedRevision != null && meta.revision != request.expectedRevision) {
            throw OutboxException(
                "outbox_revision_conflict",
                "版本冲突: 当前版本 ${meta.revision}, 期望版本 ${request.expectedRevision}",
                getSnapshotLocked(conversationId)
            )
        }

        // dispatching/submitted不可改删重排，避免负数/重复sequence
        val allEntries = outboxDao.getByConversationId(conversationId)
        val activeQueueItems = allEntries.filter {
            it.state == ConversationOutboxEntry.STATE_PENDING || it.state == ConversationOutboxEntry.STATE_PAUSED
        }.sortedBy { it.sequence }

        val firstActiveSeq = activeQueueItems.minOfOrNull { it.sequence } ?: 1L
        val now = System.currentTimeMillis()

        val newRev = DatabaseHelper.withTransaction {
            val rev = metaDao.nextRevision(conversationId, request.expectedRevision)
                ?: throw OutboxException(
                    "outbox_revision_conflict",
                    "版本冲突: 更新版本失败",
                    getSnapshotLocked(conversationId)
                )

            // 目标项提至活跃队首，明确重绑至核验过的当前 active session/turn
            outboxDao.update(
                target.copy(
                    sequence = firstActiveSeq,
                    state = ConversationOutboxEntry.STATE_PENDING,
                    blockedBySessionId = active.sessionId,
                    blockedByTurnId = active.turnId,
                    pauseReason = "等待当前任务停止",
                    revision = rev,
                    updatedAt = now
                )
            )

            // 其余兄弟项原序保持，序号顺延，状态置 paused
            var nextSeq = firstActiveSeq + 1L
            for (sibling in activeQueueItems) {
                if (sibling.id != targetId) {
                    outboxDao.update(
                        sibling.copy(
                            sequence = nextSeq++,
                            state = ConversationOutboxEntry.STATE_PAUSED,
                            blockedBySessionId = active.sessionId,
                            blockedByTurnId = active.turnId,
                            pauseReason = "暂停以优先发送后续任务",
                            revision = rev,
                            updatedAt = now
                        )
                    )
                }
            }

            // 若调整后的最大序号达到或超过 nextSequence，同步推进 nextSequence 避免未来重复
            val maxSeq = nextSeq
            val curMeta = metaDao.getByConversationId(conversationId)
            if (curMeta != null && maxSeq >= curMeta.nextSequence) {
                metaDao.update(curMeta.copy(nextSequence = maxSeq + 1L))
            }

            rev
        }

        waitingStopAndSendTarget[conversationId] = StopAndSendAuthorization(targetId, expectedSession, expectedTurn)
        notifyChanged(conversationId, newRev)

        // 异步请求取消，不持锁同步等待，取消回执不代表任务已结束
        scope.launch {
            try {
                promptDispatcher(
                    "session/cancel",
                    mapOf(
                        "sessionId" to active.sessionId,
                        "turnId" to active.turnId,
                        "conversationId" to conversationId
                    )
                )
            } catch (e: Exception) {
                OmniLog.w(TAG, "stop_and_send 取消当前 Turn 异常: ${e.message}")
            }
        }

        return getSnapshotLocked(conversationId)
    }

    private suspend fun rebindDependentItemsLocked(
        conversationId: Long,
        sessionId: String,
        turnId: String,
        excludeId: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        // 1. 重绑 STATE_PENDING 项
        val pendingItems = outboxDao.getByConversationIdAndState(
            conversationId,
            ConversationOutboxEntry.STATE_PENDING
        )
        for (p in pendingItems) {
            if (p.id != excludeId && (p.blockedBySessionId != sessionId || p.blockedByTurnId != turnId)) {
                outboxDao.update(
                    p.copy(
                        blockedBySessionId = sessionId,
                        blockedByTurnId = turnId,
                        pauseReason = if (p.pauseReason == "等待当前任务停止") null else p.pauseReason,
                        updatedAt = now
                    )
                )
            }
        }
        // 2. 重绑因等待历史而处于 STATE_PAUSED 的项（保持 pauseReason barrier）
        val pausedItems = outboxDao.getByConversationIdAndState(
            conversationId,
            ConversationOutboxEntry.STATE_PAUSED
        )
        for (p in pausedItems) {
            if (p.id != excludeId && p.pauseReason == "等待历史保存" &&
                (p.blockedBySessionId != sessionId || p.blockedByTurnId != turnId)) {
                outboxDao.update(
                    p.copy(
                        blockedBySessionId = sessionId,
                        blockedByTurnId = turnId,
                        updatedAt = now
                    )
                )
            }
        }
        // 3. 将被其他并发抢先的非自身 STATE_DISPATCHING 项原子 CAS 回 PENDING
        val dispatchingItems = outboxDao.getByConversationIdAndState(
            conversationId,
            ConversationOutboxEntry.STATE_DISPATCHING
        )
        for (d in dispatchingItems) {
            if (d.id != excludeId) {
                outboxDao.update(
                    d.copy(
                        state = ConversationOutboxEntry.STATE_PENDING,
                        blockedBySessionId = sessionId,
                        blockedByTurnId = turnId,
                        pauseReason = null,
                        updatedAt = now
                    )
                )
                dispatchingItemByConversation.remove(conversationId, d.id)
            }
        }
    }

    internal suspend fun onPromptAdmitted(
        sessionId: String,
        turnId: String,
        requestId: String?,
        conversationId: Long?,
        userMessageId: String?
    ) {
        OmniLog.d(TAG, "onPromptAdmitted sessionId=$sessionId turnId=$turnId requestId=$requestId conversationId=$conversationId userMessageId=$userMessageId")
        if (conversationId == null || conversationId <= 0L) {
            return
        }

        val lock = getConversationMutex(conversationId)
        var revToNotify: Long? = null
        var shouldAppendEntry: ConversationOutboxEntry? = null
        var admittedAt = 0L

        lock.withLock {
            val newActive = ActiveTurn(sessionId, turnId, requestId)
            activeTurns[conversationId] = newActive

            // 普通新 Turn 抢先 admit 应使旧 stop 目标失效
            val auth = waitingStopAndSendTarget[conversationId]
            if (auth != null && auth.expectedTurnId != turnId) {
                OmniLog.d(TAG, "新 Turn 抢先准入，使旧 stop_and_send 目标失效: ${auth.targetId}")
                waitingStopAndSendTarget.remove(conversationId)
            }

            val now = System.currentTimeMillis()

            if (requestId.isNullOrBlank()) {
                // 普通外部请求缺 requestId，更新活跃归属并重绑现有 pending、waiting-history paused 和未准入 dispatching
                DatabaseHelper.withTransaction {
                    rebindDependentItemsLocked(conversationId, sessionId, turnId, null, now)
                }
                return@withLock
            }

            val entry = outboxDao.getByRequestId(requestId)
            if (entry != null && entry.conversationId == conversationId) {
                val current = outboxDao.getById(entry.id) ?: return@withLock
                val isDispatching = current.state == ConversationOutboxEntry.STATE_DISPATCHING
                val requestMatches = (current.requestId == requestId)
                val userMessageMatches = (userMessageId == null || userMessageId.isBlank() || current.userMessageId == userMessageId)

                if (isDispatching && requestMatches && userMessageMatches) {
                    val newRev = DatabaseHelper.withTransaction {
                        val rev = metaDao.nextRevision(conversationId) ?: (current.revision + 1)
                        val submitted = current.copy(
                            state = ConversationOutboxEntry.STATE_SUBMITTED,
                            revision = rev,
                            blockedBySessionId = sessionId,
                            blockedByTurnId = turnId,
                            updatedAt = now,
                            pauseReason = null
                        )
                        outboxDao.update(submitted)

                        // 重绑剩余项至当前活跃 Turn
                        rebindDependentItemsLocked(conversationId, sessionId, turnId, current.id, now)
                        rev
                    }
                    dispatchingItemByConversation.remove(conversationId, current.id)
                    revToNotify = newRev
                    shouldAppendEntry = current
                    admittedAt = now
                } else {
                    OmniLog.w(TAG, "onPromptAdmitted 条件不符未标 submitted: isDispatching=$isDispatching, reqMatches=$requestMatches, userMsgMatches=$userMessageMatches")
                }
            } else {
                // 普通外部请求带 requestId 但不属于待发箱
                DatabaseHelper.withTransaction {
                    rebindDependentItemsLocked(conversationId, sessionId, turnId, null, now)
                }
            }
        }
        revToNotify?.let { notifyChanged(conversationId, it) }

        if (shouldAppendEntry != null) {
            val current = shouldAppendEntry
            val payload = parsePayloadJson(current.payloadJson)
            val text = payload["text"]?.toString().orEmpty()
            val attachments = normalizeListOfMaps(payload["attachments"])
            val conversation = DatabaseHelper.getConversationById(conversationId)
            val conversationMode = conversation?.mode ?: "agent"
            try {
                userMessageAppender(
                    conversationId,
                    conversationMode,
                    current.userMessageId,
                    text,
                    attachments,
                    admittedAt
                )
            } catch (e: Throwable) {
                OmniLog.e(TAG, "userMessageAppender 失败: ${e.message}", e)
            }
        }
    }

    internal suspend fun onPromptSettled(
        conversationId: Long,
        sessionId: String,
        turnId: String,
        requestId: String,
        stopReason: String?,
        error: Throwable?
    ) {
        OmniLog.d(TAG, "onPromptSettled conversationId=$conversationId turnId=$turnId stopReason=$stopReason error=${error?.message}")
        val factKey = TurnFactKey(conversationId, sessionId, turnId, requestId)
        val lock = getConversationMutex(conversationId)
        var revToNotify: Long? = null
        var shouldDispatchNext = false

        lock.withLock {
            val settled = SettledTurn(
                conversationId = conversationId,
                sessionId = sessionId,
                turnId = turnId,
                requestId = requestId,
                stopReason = stopReason,
                error = error,
                settledAt = System.currentTimeMillis()
            )
            settledTurns[factKey] = settled
            lastSettledTurnByConversation[conversationId] = settled

            val active = activeTurns[conversationId]
            if (active?.turnId == turnId) {
                activeTurns.remove(conversationId, active)
            }

            val isNormalEndTurn = stopReason == "end_turn" && error == null
            val auth = waitingStopAndSendTarget[conversationId]

            if (auth != null && auth.expectedSessionId == sessionId && auth.expectedTurnId == turnId) {
                val isCancelledAuthorized = stopReason == "cancelled" && isCancellationError(error)
                if (isCancelledAuthorized || isNormalEndTurn) {
                    // 正式 cancelled 或 end_turn 且历史提交后才投递
                    if (historyCommittedTurns.containsKey(factKey)) {
                        OmniLog.d(TAG, "stop_and_send 目标任务前序已正式停止且历史已提交，准备投递: ${auth.targetId}")
                        shouldDispatchNext = true
                    }
                    return@withLock
                }
            }

            val pending = outboxDao.getByConversationIdAndState(
                conversationId,
                ConversationOutboxEntry.STATE_PENDING
            )

            if (!isNormalEndTurn) {
                val pauseReasonText = if (stopReason == "cancelled" && isCancellationError(error)) {
                    "前序任务已取消"
                } else if (error != null) {
                    error.message ?: "任务执行异常"
                } else when (stopReason) {
                    "max_tokens" -> "达到 token 上限"
                    "refusal" -> "模型拒绝执行"
                    "cancelled" -> "前序任务已取消"
                    else -> "前序任务未正常结束: $stopReason"
                }

                val now = System.currentTimeMillis()
                val newRev = DatabaseHelper.withTransaction {
                    var latestRev = metaDao.getOrCreate(conversationId).revision
                    for (item in pending) {
                        if ((item.blockedBySessionId == sessionId && item.blockedByTurnId == turnId) || item.blockedByTurnId == null) {
                            val rev = metaDao.nextRevision(conversationId) ?: (latestRev + 1)
                            latestRev = rev
                            outboxDao.update(
                                item.copy(
                                    state = ConversationOutboxEntry.STATE_PAUSED,
                                    pauseReason = pauseReasonText,
                                    blockedBySessionId = sessionId,
                                    blockedByTurnId = turnId,
                                    revision = rev,
                                    updatedAt = now
                                )
                            )
                        }
                    }
                    val pausedItems = outboxDao.getByConversationIdAndState(
                        conversationId,
                        ConversationOutboxEntry.STATE_PAUSED
                    )
                    for (item in pausedItems) {
                        if (item.pauseReason == "等待历史保存" &&
                            ((item.blockedBySessionId == sessionId && item.blockedByTurnId == turnId) || item.blockedByTurnId == null)) {
                            val rev = metaDao.nextRevision(conversationId) ?: (latestRev + 1)
                            latestRev = rev
                            outboxDao.update(
                                item.copy(
                                    pauseReason = pauseReasonText,
                                    blockedBySessionId = sessionId,
                                    blockedByTurnId = turnId,
                                    revision = rev,
                                    updatedAt = now
                                )
                            )
                        }
                    }
                    latestRev
                }
                revToNotify = newRev
            } else {
                if (historyCommittedTurns.containsKey(factKey)) {
                    shouldDispatchNext = true
                } else {
                    val now = System.currentTimeMillis()
                    val newRev = DatabaseHelper.withTransaction {
                        var latestRev = metaDao.getOrCreate(conversationId).revision
                        for (item in pending) {
                            if ((item.blockedBySessionId == sessionId && item.blockedByTurnId == turnId) || item.blockedByTurnId == null) {
                                val rev = metaDao.nextRevision(conversationId) ?: (latestRev + 1)
                                latestRev = rev
                                outboxDao.update(
                                    item.copy(
                                        state = ConversationOutboxEntry.STATE_PAUSED,
                                        pauseReason = "等待历史保存",
                                        blockedBySessionId = sessionId,
                                        blockedByTurnId = turnId,
                                        revision = rev,
                                        updatedAt = now
                                    )
                                )
                            }
                        }
                        latestRev
                    }
                    revToNotify = newRev
                }
            }
        }

        revToNotify?.let { notifyChanged(conversationId, it) }
        if (shouldDispatchNext) {
            checkAndDispatchNext(conversationId)
        }
    }

    internal suspend fun onHistoryCommitted(
        conversationId: Long,
        sessionId: String,
        turnId: String,
        requestId: String
    ): Boolean {
        OmniLog.d(TAG, "onHistoryCommitted conversationId=$conversationId turnId=$turnId requestId=$requestId")
        val factKey = TurnFactKey(conversationId, sessionId, turnId, requestId)
        val lock = getConversationMutex(conversationId)
        var shouldDispatchNext = false
        var revToNotify: Long? = null

        val confirmed = lock.withLock {
            val entry = outboxDao.getByRequestId(requestId)
            val persistentOwner = getPersistentOwner(conversationId) ?: AcpAgentProfileStore.XIAOWAN_AGENT_ID

            // 同 turn 不同 cid/session/request 不接受，避免跨 opaque session 污染
            if (entry != null) {
                if (entry.conversationId != conversationId) {
                    OmniLog.w(TAG, "onHistoryCommitted conversationId 不匹配: entry.cid=${entry.conversationId}, req=$conversationId")
                    return@withLock false
                }
                if (entry.blockedBySessionId != null && entry.blockedBySessionId != sessionId) {
                    OmniLog.w(TAG, "onHistoryCommitted sessionId 不匹配: entry.session=${entry.blockedBySessionId}, req=$sessionId")
                    return@withLock false
                }
                // 持久 owner 必须匹配 entry agent
                if (entry.agentId != persistentOwner) {
                    OmniLog.w(TAG, "onHistoryCommitted 持久 owner 不匹配: entry.agent=${entry.agentId}, persistent=$persistentOwner")
                    return@withLock false
                }
            }

            val agentId = entry?.agentId ?: persistentOwner

            // 确证 AcpTaskTerminalJournal 官方终态事实：必须单个 terminal 条目完整匹配 agentId/sessionId/turnId/requestId
            val lookupByReq = if (agentId.isNotBlank() && sessionId.isNotBlank() && requestId.isNotBlank()) {
                AcpTaskTerminalJournal.findByRequestId(appContext, agentId, sessionId, requestId)
            } else AcpTerminalLookupResult.Unknown

            val terminalVerified = lookupByReq is AcpTerminalLookupResult.TerminalFound &&
                    lookupByReq.entry.agentId == agentId &&
                    lookupByReq.entry.sessionId == sessionId &&
                    lookupByReq.entry.turnId == turnId &&
                    lookupByReq.entry.requestId == requestId &&
                    lookupByReq.entry.isTerminal

            if (!terminalVerified) {
                OmniLog.w(TAG, "onHistoryCommitted 未在 AcpTaskTerminalJournal 中确证完整匹配的官方终态事实")
                return@withLock false
            }

            historyCommittedTurns[factKey] = true

            // receipt 清 submitted payload 幂等
            if (entry != null && entry.state == ConversationOutboxEntry.STATE_SUBMITTED) {
                if (entry.payloadJson != "{}") {
                    DatabaseHelper.withTransaction {
                        outboxDao.update(
                            entry.copy(
                                payloadJson = "{}",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            // pendingWithWaitingHistory 转 paused 后 receipt 成功可恢复该特定 pauseReason，用户手停 paused 不能自动恢复
            val pausedItems = outboxDao.getByConversationIdAndState(conversationId, ConversationOutboxEntry.STATE_PAUSED)
            val waitingHistoryItems = pausedItems.filter {
                it.pauseReason == "等待历史保存" &&
                    it.blockedBySessionId == sessionId &&
                    it.blockedByTurnId == turnId
            }
            if (waitingHistoryItems.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val newRev = DatabaseHelper.withTransaction {
                    var latestRev = metaDao.getOrCreate(conversationId).revision
                    for (item in waitingHistoryItems) {
                        val rev = metaDao.nextRevision(conversationId) ?: (latestRev + 1)
                        latestRev = rev
                        outboxDao.update(
                            item.copy(
                                state = ConversationOutboxEntry.STATE_PENDING,
                                pauseReason = null,
                                revision = rev,
                                updatedAt = now
                            )
                        )
                    }
                    latestRev
                }
                revToNotify = newRev
            }

            val auth = waitingStopAndSendTarget[conversationId]
            val settled = settledTurns[factKey]

            if (auth != null && auth.expectedSessionId == sessionId && auth.expectedTurnId == turnId) {
                val isCancelledAuthorized = settled != null &&
                    settled.stopReason == "cancelled" &&
                    isCancellationError(settled.error)
                val isEndTurnAuthorized = settled != null &&
                    settled.stopReason == "end_turn" &&
                    settled.error == null

                if (isCancelledAuthorized || isEndTurnAuthorized) {
                    shouldDispatchNext = true
                    return@withLock true
                }
            }

            val isNormalEndTurn = settled != null &&
                settled.stopReason == "end_turn" &&
                settled.error == null
            val isIdle = activeTurns[conversationId] == null

            // 只有当属于当前 receipt 且成功解除"等待历史保存"的项时才允许 shouldDispatchNext，移除 old receipt auto authorization
            if (isNormalEndTurn && isIdle && waitingHistoryItems.isNotEmpty()) {
                shouldDispatchNext = true
            }

            true
        }

        revToNotify?.let { notifyChanged(conversationId, it) }
        if (shouldDispatchNext) {
            checkAndDispatchNext(conversationId)
        }
        return confirmed
    }

    private suspend fun checkAndDispatchNext(conversationId: Long) {
        val lock = getConversationMutex(conversationId)
        var revToNotify: Long? = null
        val nextItemToDispatch = lock.withLock {
            if (activeTurns[conversationId] != null) return@withLock null
            if (dispatchingItemByConversation.containsKey(conversationId)) return@withLock null

            val existingDispatching = outboxDao.getByConversationIdAndState(
                conversationId,
                ConversationOutboxEntry.STATE_DISPATCHING
            )
            if (existingDispatching.isNotEmpty()) return@withLock null

            val activeEntries = outboxDao.getActiveByConversationId(conversationId)
            if (activeEntries.isEmpty()) return@withLock null

            // paused 队首不可被后 pending 越过
            val headItem = activeEntries.minByOrNull { it.sequence } ?: return@withLock null
            if (headItem.state != ConversationOutboxEntry.STATE_PENDING) {
                return@withLock null
            }

            // 重新检查 blocked 对应正式 settled + history 证据，避免 enqueue/resume 竞态越过
            val blockedTurnId = headItem.blockedByTurnId
            val blockedSessionId = headItem.blockedBySessionId
            if (blockedTurnId != null && blockedSessionId != null) {
                val auth = waitingStopAndSendTarget[conversationId]
                val isStopAndSendAuthorized = auth != null &&
                    auth.targetId == headItem.id &&
                    auth.expectedSessionId == blockedSessionId &&
                    auth.expectedTurnId == blockedTurnId

                val hasProof = settledTurns.entries.any { (k, v) ->
                    k.conversationId == conversationId &&
                        k.sessionId == blockedSessionId &&
                        k.turnId == blockedTurnId &&
                        historyCommittedTurns.containsKey(k) &&
                        ((v.stopReason == "end_turn" && v.error == null) ||
                            (isStopAndSendAuthorized && v.stopReason == "cancelled" && isCancellationError(v.error)))
                }
                if (!hasProof) {
                    return@withLock null
                }
            }

            val now = System.currentTimeMillis()
            val (dispatching, newRev) = DatabaseHelper.withTransaction {
                val rev = metaDao.nextRevision(conversationId) ?: (headItem.revision + 1)
                val item = headItem.copy(
                    state = ConversationOutboxEntry.STATE_DISPATCHING,
                    revision = rev,
                    updatedAt = now,
                    pauseReason = null
                )
                outboxDao.update(item)
                item to rev
            }

            if (waitingStopAndSendTarget[conversationId]?.targetId == headItem.id) {
                waitingStopAndSendTarget.remove(conversationId)
            }
            dispatchingItemByConversation[conversationId] = headItem.id
            revToNotify = newRev
            dispatching
        }
        revToNotify?.let { notifyChanged(conversationId, it) }

        if (nextItemToDispatch != null) {
            scope.launch {
                dispatchItem(nextItemToDispatch)
            }
        }
    }

    private suspend fun dispatchItem(readyEntry: ConversationOutboxEntry) {
        val cid = readyEntry.conversationId
        val lock = getConversationMutex(cid)
        var shouldProceed = false
        var notifyRev: Long? = null
        lock.withLock {
            val cur = outboxDao.getById(readyEntry.id)
            if (cur == null || cur.state != ConversationOutboxEntry.STATE_DISPATCHING) {
                // 已被抢先准入重绑或已被取消/暂停，直接退出
                return@withLock
            }
            val active = activeTurns[cid]
            if (active != null) {
                // 另一个 Turn 抢先准入！CAS 回 PENDING 并重绑至 active，不写历史、不暂停
                val now = System.currentTimeMillis()
                val rev = DatabaseHelper.withTransaction {
                    val r = metaDao.nextRevision(cid) ?: (cur.revision + 1)
                    outboxDao.update(
                        cur.copy(
                            state = ConversationOutboxEntry.STATE_PENDING,
                            blockedBySessionId = active.sessionId,
                            blockedByTurnId = active.turnId,
                            pauseReason = null,
                            revision = r,
                            updatedAt = now
                        )
                    )
                    r
                }
                dispatchingItemByConversation.remove(cid, readyEntry.id)
                notifyRev = rev
                return@withLock
            }
            shouldProceed = true
        }
        notifyRev?.let { notifyChanged(cid, it) }
        if (!shouldProceed) {
            return
        }

        try {
            val payload = parsePayloadJson(readyEntry.payloadJson)
            val text = payload["text"]?.toString().orEmpty()
            val attachments = normalizeListOfMaps(payload["attachments"])

            // 附件发送前复查
            validateTextAndAttachments(text, attachments)

            val conversation = DatabaseHelper.getConversationById(cid)
                ?: throw IllegalStateException("Conversation $cid not found")
            val conversationMode = conversation.mode

            val currentOwner = getPersistentOwner(cid) ?: AcpAgentProfileStore.XIAOWAN_AGENT_ID
            if (currentOwner != readyEntry.agentId) {
                pauseDispatchingItem(cid, readyEntry.id, "所有者绑定已变更/失效: 期望 ${readyEntry.agentId}, 当前 $currentOwner")
                return
            }

            if (readyEntry.agentId != REMOTE_CODEX_AGENT_ID) {
                val store = AcpAgentProfileStore(appContext)
                val profile = runCatching { store.requireProfile(readyEntry.agentId) }.getOrNull()
                if (profile == null || !profile.enabled) {
                    pauseDispatchingItem(cid, readyEntry.id, "Agent 账户或绑定已失效: ${readyEntry.agentId}")
                    return
                }
            }

            val persistentBinding = DatabaseHelper.getAgentSessionBindingByConversationId(cid)
            val sessionId = persistentBinding?.threadId
            val cwd = persistentBinding?.cwd?.takeIf { it.isNotBlank() } ?: payload["cwd"]?.toString()

            val promptArgs = linkedMapOf<String, Any?>(
                "conversationId" to cid,
                "conversationMode" to conversationMode,
                "requestId" to readyEntry.requestId,
                "userMessageId" to readyEntry.userMessageId,
                "text" to text,
                "attachments" to attachments,
                "agentId" to readyEntry.agentId
            )
            sessionId?.let { promptArgs["sessionId"] = it }
            payload["model"]?.let { promptArgs["model"] = it }
            payload["effort"]?.let { promptArgs["effort"] = it }
            cwd?.let { promptArgs["cwd"] = it }

            promptDispatcher("session/prompt", promptArgs)
        } catch (e: Exception) {
            dispatchingItemByConversation.remove(cid, readyEntry.id)
            var newRevToNotify: Long? = null
            lock.withLock {
                val cur = outboxDao.getById(readyEntry.id)
                if (cur != null && cur.state == ConversationOutboxEntry.STATE_DISPATCHING) {
                    val now = System.currentTimeMillis()
                    val active = activeTurns[cid]
                    val newRev = DatabaseHelper.withTransaction {
                        val rev = metaDao.nextRevision(cid) ?: (cur.revision + 1)
                        if (active != null) {
                            // 抢先并发导致 Busy 拒绝：保持未准入、CAS 回 PENDING 重绑至 active，不写历史、不暂停！
                            val rebound = cur.copy(
                                state = ConversationOutboxEntry.STATE_PENDING,
                                blockedBySessionId = active.sessionId,
                                blockedByTurnId = active.turnId,
                                pauseReason = null,
                                revision = rev,
                                updatedAt = now
                            )
                            outboxDao.update(rebound)
                        } else {
                            val paused = cur.copy(
                                state = ConversationOutboxEntry.STATE_PAUSED,
                                pauseReason = e.message ?: "投递失败",
                                revision = rev,
                                updatedAt = now
                            )
                            outboxDao.update(paused)
                        }
                        rev
                    }
                    newRevToNotify = newRev
                }
            }
            newRevToNotify?.let { notifyChanged(cid, it) }
        } finally {
            // 按 request 所有者删除，不误删后继令牌
            dispatchingItemByConversation.remove(cid, readyEntry.id)
        }
    }

    suspend fun reconcileOnStartup() {
        val startupTimestamp = System.currentTimeMillis()
        try {
            val allConversations = DatabaseHelper.getAllConversations()
            for (conv in allConversations) {
                val cid = conv.id
                val lock = getConversationMutex(cid)
                var revToNotify: Long? = null
                lock.withLock {
                    val entries = outboxDao.getByConversationId(cid)
                    var revChanged = false
                    val now = System.currentTimeMillis()

                    val newRev = DatabaseHelper.withTransaction {
                        var latestRev = metaDao.getOrCreate(cid).revision
                        for (entry in entries) {
                            if (entry.createdAt > startupTimestamp) continue

                            when (entry.state) {
                                ConversationOutboxEntry.STATE_PENDING -> {
                                    val rev = metaDao.nextRevision(cid) ?: (latestRev + 1)
                                    latestRev = rev
                                    revChanged = true
                                    outboxDao.update(
                                        entry.copy(
                                            state = ConversationOutboxEntry.STATE_PAUSED,
                                            pauseReason = "应用重启，暂停发送",
                                            revision = rev,
                                            updatedAt = now
                                        )
                                    )
                                }
                                ConversationOutboxEntry.STATE_DISPATCHING -> {
                                    val binding = DatabaseHelper.getAgentSessionBindingByConversationId(cid)
                                    val sessionId = entry.blockedBySessionId?.takeIf { it.isNotBlank() } ?: binding?.threadId
                                    val lookup = if (entry.agentId.isNotBlank() && !sessionId.isNullOrBlank() && entry.requestId.isNotBlank()) {
                                        AcpTaskTerminalJournal.findByRequestId(
                                            appContext,
                                            entry.agentId,
                                            sessionId,
                                            entry.requestId
                                        )
                                    } else {
                                        AcpTerminalLookupResult.Unknown
                                    }

                                    val (newState, reason) = when (lookup) {
                                        is AcpTerminalLookupResult.TerminalFound,
                                        is AcpTerminalLookupResult.AdmittedInFlight -> {
                                            ConversationOutboxEntry.STATE_SUBMITTED to null
                                        }
                                        is AcpTerminalLookupResult.Unknown -> {
                                            ConversationOutboxEntry.STATE_PAUSED to "投递状态待核对"
                                        }
                                    }

                                    val rev = metaDao.nextRevision(cid) ?: (latestRev + 1)
                                    latestRev = rev
                                    revChanged = true
                                    outboxDao.update(
                                        entry.copy(
                                            state = newState,
                                            pauseReason = reason,
                                            revision = rev,
                                            updatedAt = now
                                        )
                                    )
                                }
                            }
                        }
                        latestRev
                    }

                    if (revChanged) {
                        revToNotify = newRev
                    }
                }
                revToNotify?.let { notifyChanged(cid, it) }
            }
        } catch (e: Exception) {
            OmniLog.w(TAG, "进程启动待发箱对账异常: ${e.message}", e)
        } finally {
            markReady()
        }
    }

    suspend fun pausePendingItems(conversationId: Long, reason: String) {
        val lock = getConversationMutex(conversationId)
        var revToNotify: Long? = null
        lock.withLock {
            val pending = outboxDao.getByConversationIdAndState(
                conversationId,
                ConversationOutboxEntry.STATE_PENDING
            )
            if (pending.isEmpty()) return@withLock
            val now = System.currentTimeMillis()
            val newRev = DatabaseHelper.withTransaction {
                val rev = metaDao.nextRevision(conversationId) ?: (metaDao.getOrCreate(conversationId).revision + 1)
                for (item in pending) {
                    outboxDao.update(
                        item.copy(
                            state = ConversationOutboxEntry.STATE_PAUSED,
                            pauseReason = reason,
                            revision = rev,
                            updatedAt = now
                        )
                    )
                }
                rev
            }
            revToNotify = newRev
        }
        revToNotify?.let { notifyChanged(conversationId, it) }
    }

    suspend fun clearQueueForConversation(conversationId: Long) {
        val lock = getConversationMutex(conversationId)
        lock.withLock {
            DatabaseHelper.withTransaction {
                outboxDao.deleteByConversationId(conversationId)
                metaDao.deleteByConversationId(conversationId)
            }
            activeTurns.remove(conversationId)
            waitingStopAndSendTarget.remove(conversationId)
            dispatchingItemByConversation.remove(conversationId)
            lastSettledTurnByConversation.remove(conversationId)
        }
        notifyChanged(conversationId, 0L)
    }

    private suspend fun getSnapshotLocked(conversationId: Long): OutboxSnapshot {
        val meta = metaDao.getOrCreate(conversationId)
        val entries = outboxDao.getByConversationId(conversationId)
        val lastSettled = lastSettledTurnByConversation[conversationId]
        val isWaitingHistory = if (lastSettled != null) {
            val lastKey = TurnFactKey(lastSettled.conversationId, lastSettled.sessionId, lastSettled.turnId, lastSettled.requestId)
            lastSettled.stopReason == "end_turn" &&
                lastSettled.error == null &&
                !historyCommittedTurns.containsKey(lastKey)
        } else false

        val items = entries.map { entry ->
            val payload = if (entry.state == ConversationOutboxEntry.STATE_SUBMITTED) {
                null
            } else {
                parsePayloadJson(entry.payloadJson)
            }
            val pauseReason = if (entry.state == ConversationOutboxEntry.STATE_PENDING && isWaitingHistory) {
                entry.pauseReason ?: "等待历史保存"
            } else {
                entry.pauseReason
            }
            OutboxItem(
                id = entry.id,
                conversationId = entry.conversationId,
                agentId = entry.agentId,
                state = entry.state,
                revision = entry.revision,
                sequence = entry.sequence,
                requestId = entry.requestId,
                userMessageId = entry.userMessageId,
                blockedBySessionId = entry.blockedBySessionId,
                blockedByTurnId = entry.blockedByTurnId,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt,
                pauseReason = pauseReason,
                payload = payload
            )
        }
        return OutboxSnapshot(
            revision = meta.revision,
            items = items
        )
    }

    private fun validateTextAndAttachments(
        text: String,
        attachments: List<Map<String, Any?>>
    ) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8).size
        if (textBytes > MAX_TEXT_BYTES) {
            throw OutboxException(
                "outbox_payload_too_large",
                "文本大小超过 64KiB 限制 (当前 ${textBytes} 字节)"
            )
        }

        if (text.trim().isEmpty() && attachments.isEmpty()) {
            throw OutboxException("outbox_empty", "内容不能为空")
        }
        val attachmentsDir = try {
            AgentWorkspaceManager(appContext).attachmentsDirectory().canonicalFile
        } catch (e: Exception) {
            throw OutboxException(
                "outbox_attachment_unavailable",
                "附件工作区初始化失败: ${e.message}"
            )
        }

        for (att in attachments) {
            val name = att["name"]?.toString() ?: "unknown"
            val rawPath = (att["path"] as? String)
                ?: (att["filePath"] as? String)
                ?: (att["uri"] as? String)

            if (rawPath.isNullOrBlank()) {
                throw OutboxException(
                    "outbox_attachment_unavailable",
                    "附件缺少有效的持久化路径引用: $name"
                )
            }

            if (rawPath.startsWith("data:", ignoreCase = true)) {
                throw OutboxException(
                    "outbox_attachment_unavailable",
                    "不支持未导入的 dataUrl 附件引用: $name"
                )
            }

            if (rawPath.startsWith("http://", ignoreCase = true) || rawPath.startsWith("https://", ignoreCase = true)) {
                throw OutboxException(
                    "outbox_attachment_unavailable",
                    "不支持未导入的远程 URL 附件引用: $name"
                )
            }

            if (rawPath.startsWith("content://", ignoreCase = true)) {
                throw OutboxException(
                    "outbox_attachment_unavailable",
                    "附件必须已持久化导入至受管附件目录: $name"
                )
            }

            val filePath = if (rawPath.startsWith("file://", ignoreCase = true)) {
                rawPath.removePrefix("file://")
            } else {
                rawPath
            }

            val file = try {
                File(filePath).canonicalFile
            } catch (e: Exception) {
                throw OutboxException(
                    "outbox_attachment_unavailable",
                    "附件路径非法: $filePath"
                )
            }

            val rootPath = attachmentsDir.canonicalPath
            val filePathCanonical = file.canonicalPath
            val isUnderRoot = filePathCanonical == rootPath || filePathCanonical.startsWith(rootPath + File.separator)
            if (!isUnderRoot || !file.isFile || !file.canRead()) {
                throw OutboxException(
                    "outbox_attachment_unavailable",
                    "附件必须位于受管附件目录且普通可读: $filePath"
                )
            }
        }
    }

    private suspend fun sanitizePayload(
        conversationId: Long,
        rawPayload: Map<String, Any?>,
        conversation: Conversation
    ): Map<String, Any?> {
        val safe = linkedMapOf<String, Any?>()
        rawPayload["text"]?.let { safe["text"] = it }
        rawPayload["attachments"]?.let { safe["attachments"] = it }

        // cwd 持久 binding：优先使用当前会话持久绑定的 cwd
        val binding = DatabaseHelper.getAgentSessionBindingByConversationId(conversationId)
        val persistentCwd = binding?.cwd?.takeIf { it.isNotBlank() } ?: (rawPayload["cwd"] as? String)
        persistentCwd?.let { safe["cwd"] = it }

        rawPayload["model"]?.let { safe["model"] = it }
        rawPayload["effort"]?.let { safe["effort"] = it }

        // conversationMode 严格取持久 Conversation 的 mode，客户端不得更改 owner/mode
        safe["conversationMode"] = conversation.mode
        return safe
    }

    private fun safeMapToJson(map: Map<String, Any?>): String {
        return JSONObject(map).toString()
    }

    private fun parsePayloadJson(json: String): Map<String, Any?> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = linkedMapOf<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                result[key] = jsonValueToJava(value)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun jsonValueToJava(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val map = linkedMapOf<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = jsonValueToJava(value.opt(k))
                }
                map
            }
            is JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until value.length()) {
                    list.add(jsonValueToJava(value.opt(i)))
                }
                list
            }
            else -> value
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalizeListOfMaps(raw: Any?): List<Map<String, Any?>> {
        val rawList = raw as? List<*> ?: return emptyList()
        return rawList.mapNotNull { it as? Map<String, Any?> }
    }

    private fun notifyChanged(conversationId: Long, revision: Long) {
        try {
            FlutterChatSyncBridge.dispatchConversationOutboxChanged(conversationId, revision)
        } catch (e: Exception) {
            OmniLog.w(TAG, "dispatchConversationOutboxChanged error: ${e.message}")
        }
        try {
            RealtimeHub.publish(
                event = "conversation_outbox_changed",
                data = mapOf(
                    "conversationId" to conversationId,
                    "revision" to revision
                )
            )
        } catch (e: Exception) {
            OmniLog.w(TAG, "RealtimeHub.publish error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "[ConversationOutboxService]"
        const val MAX_ACTIVE_ITEMS = 20
        const val MAX_TEXT_BYTES = 64 * 1024

        const val OPERATION_ENQUEUE = "enqueue"
        const val OPERATION_UPDATE = "update"
        const val OPERATION_REMOVE = "remove"
        const val OPERATION_RESUME = "resume"
        const val OPERATION_STOP_AND_SEND = "stop_and_send"
    }
}
