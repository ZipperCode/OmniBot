package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.AgentConversationEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import cn.com.omnimind.baselib.llm.*
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.junit.Test

class AgentConversationHistoryRepositoryTest {
    @Test
    fun historyRemovalRequiresExplicitDisjointMessageIdentities() {
        val retained = listOf(mapOf<String, Any?>("id" to "retained"))
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationHistoryRepository.explicitHistoryRemovalIds(retained, true, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationHistoryRepository.explicitHistoryRemovalIds(retained, true, listOf("retained"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationHistoryRepository.explicitHistoryRemovalIds(retained, false, listOf("old-answer"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentConversationHistoryRepository.explicitHistoryRemovalIds(retained, true, listOf(" "))
        }
        assertEquals(listOf("old-answer"), AgentConversationHistoryRepository.explicitHistoryRemovalIds(
            retained, true, listOf("old-answer", "old-answer"),
        ))
        assertEquals(emptyList<String>(), AgentConversationHistoryRepository.explicitHistoryRemovalIds(
            retained, false, null,
        ))
    }

    @Test
    fun emptyPresentationPlaceholdersDoNotEraseLaterCanonicalToolMessages() {
        val keys = listOf("toolCallId", "sessionId", "turnId", "modelToolCallId",
            "modelAssistantMessageJson", "modelToolResultMessageJson")
        for (placeholder in listOf("", "  ")) {
            val existing = keys.associateWith { placeholder }
            val canonical = keys.associateWith { "canonical-$it" }
            assertEquals(canonical, AgentConversationHistoryRepository.preserveFullToolPayload(existing, canonical))
            assertEquals(canonical, AgentConversationHistoryRepository.preserveFullToolPayload(canonical, existing))
        }
    }

    @Test
    fun displaySnapshotCannotReplaceFullToolHistoryButLiveResultCanUpdateIt() {
        val body = "x".repeat(600_000)
        val original = mapOf<String, Any?>("toolCallId" to "call", "rawResultJson" to body, "status" to "success")
        val display = mapOf<String, Any?>("payloadCompacted" to true, "rawResultJson" to "preview")
        assertEquals(original, AgentConversationHistoryRepository.preserveFullToolPayload(original, display))
        val live = mapOf<String, Any?>("rawResultJson" to "new result", "status" to "success")
        val updated = AgentConversationHistoryRepository.preserveFullToolPayload(original, live)
        assertEquals("new result", updated["rawResultJson"])
        assertEquals("call", updated["toolCallId"])
    }

    @Test
    fun sharedAcpCardIdentitySurvivesPersistenceAndRestoredReplay() {
        val payload = AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(mapOf(
            "id" to "audit-card", "type" to 2, "streamMeta" to mapOf("sessionId" to "session-1", "turnId" to "turn-1"),
            "content" to mapOf("cardData" to mapOf("type" to "agent_tool_summary",
                "cardId" to "audit-card", "toolName" to "file_read", "toolCallId" to "acp-call",
                "status" to "success", "resultPreviewJson" to "{}"))))!!
        assertEquals("acp-call", payload["toolCallId"])
        assertEquals("session-1", payload["sessionId"])
        assertEquals("turn-1", payload["turnId"])
        val row = entry("audit-card", "agent", "tool_event", 100, 7).copy(payloadJson = com.google.gson.Gson().toJson(payload))
        val displayed = AgentConversationHistorySupport.buildDisplaySafeToolCardData(row, payload)
        val resaved = AgentConversationHistorySupport.restoreToolPayloadFromUiMessage(mapOf(
            "type" to 2, "content" to mapOf("cardData" to displayed)))!!
        assertEquals("acp-call", resaved["toolCallId"])
        assertEquals("session-1", resaved["sessionId"])
        assertEquals("turn-1", resaved["turnId"])
        val replay = AgentConversationHistorySupport.buildPromptSeedFromEntries(listOf(row), null, null).historyMessages
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(row), replay, 0))
        val call = AssistantToolCall(id = "acp-call", type = "function", function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))
        val live = listOf(ChatCompletionMessage(role = "assistant", toolCalls = listOf(call)),
            ChatCompletionMessage(role = "tool", toolCallId = "acp-call", content = JsonPrimitive("file reference")))
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(row), live, 0))
    }

    @Test
    fun offloadingResultMustNotLoseItsDurableCheckpointIdentity() = kotlinx.coroutines.runBlocking {
        val codec = Json { encodeDefaults = true }
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(
            AssistantToolCall(id = "audit-call", type = "function",
                function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))))
        val result = ChatCompletionMessage(role = "tool", toolCallId = "audit-call",
            content = JsonPrimitive("x".repeat(600_000)))
        val rows = listOf(entry("audit-tool", "agent", "tool_event", 100, 7).copy(payloadJson = buildJsonObject {
            put("taskId", "audit-turn")
            put("modelToolCallId", result.toolCallId)
            put("modelAssistantMessageJson", codec.encodeToString(assistant))
            put("modelToolResultMessageJson", codec.encodeToString(result))
        }.toString()))
        val original = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("read")), assistant, result)
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows, original, 0))
        val compactor = AgentConversationContextCompactor(
            org.mockito.Mockito.mock(AgentConversationHistoryRepository::class.java),
            offloadToolOutput = { "/workspace/offloads/audit.txt" })
        val bounded = compactor.compactIfNeeded(null, "agent", null, original, null, 128000, null)
        assertFalse(bounded == original)
        // A later compaction sees this bounded context, while durable history
        // correctly retains the original result. Identity must survive that change.
        assertEquals(7L, AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows, bounded, 0))
    }

    @Test
    fun compactionCheckpointRequiresCompleteUniqueCanonicalGroupAndRestoresOnlySuffix() {
        val codec = Json { encodeDefaults = true }
        fun call(id: String) = AssistantToolCall(id = id, type = "function",
            function = AssistantToolCallFunction(name = "file_read", arguments = "{}"))
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call("a"), call("b")))
        val results = listOf("a", "b").map { ChatCompletionMessage(role = "tool", toolCallId = it, content = JsonPrimitive("result-$it")) }
        val keptAssistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call("c")))
        val keptResult = ChatCompletionMessage(role = "tool", toolCallId = "c", content = JsonPrimitive("recent"))
        fun row(id: Long, turn: String, assistantMessage: ChatCompletionMessage, result: ChatCompletionMessage): AgentConversationEntry =
            entry("$turn-${result.toolCallId}", "agent", "tool_event", id * 100, id).copy(
                payloadJson = buildJsonObject {
                    put("taskId", turn)
                    put("modelToolCallId", result.toolCallId)
                    put("modelAssistantMessageJson", codec.encodeToString(assistantMessage))
                    put("modelToolResultMessageJson", codec.encodeToString(result))
                }.toString())
        val prefix = listOf(ChatCompletionMessage(role = "user", content = JsonPrimitive("inspect")), assistant) + results
        val rows = listOf(row(2, "turn-1", assistant, results[0]), row(3, "turn-1", assistant, results[1]),
            row(4, "turn-1", keptAssistant, keptResult))
        assertNull(AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows.take(1), prefix, 0))
        val cutoff = AgentConversationHistoryRepository.resolveCompactionToolCutoff(rows, prefix, 0)
        assertEquals(3L, cutoff)
        val restored = AgentConversationHistorySupport.buildPromptSeedFromEntries(rows, "Task inspect; a/b completed", cutoff).historyMessages
        assertEquals(listOf("c"), restored.filter { it.role == "tool" }.map { it.toolCallId })
        val ambiguous = rows + listOf(row(5, "turn-2", assistant, results[0]), row(6, "turn-2", assistant, results[1]))
        assertNull(AgentConversationHistoryRepository.resolveCompactionToolCutoff(ambiguous, prefix, 0))
        assertEquals(3, rows.size) // Checkpoint never mutates/deletes original journal entries.
    }

    @Test
    fun `real and synthetic tool group commits compaction cutoff on max real card id`() {
        val codec = Json { encodeDefaults = true }
        val callReal = AssistantToolCall(id = "real_call", type = "function", function = AssistantToolCallFunction("read", "{}"))
        val callSkip = AssistantToolCall(id = "skip_call", type = "function", function = AssistantToolCallFunction("write", "{}"))
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(callReal, callSkip))
        val realResult = ChatCompletionMessage(role = "tool", toolCallId = "real_call", content = JsonPrimitive("read done"))
        val skipResult = ChatCompletionMessage(role = "tool", toolCallId = "skip_call", content = JsonPrimitive("skipped"))

        val syntheticArrayJson = codec.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ChatCompletionMessage.serializer()),
            listOf(skipResult)
        )

        // 数据库中只有 1 个真实工具卡行（id=15），其挂载包含 skip_call 的 modelToolResultMessagesJson
        val rowReal = entry("turn-real", "agent", "tool_event", 1500, 15).copy(
            status = "success",
            payloadJson = buildJsonObject {
                put("taskId", "turn-mix")
                put("modelToolCallId", "real_call")
                put("modelAssistantMessageJson", codec.encodeToString(assistant))
                put("modelToolResultMessageJson", codec.encodeToString(realResult))
                put("modelToolResultMessagesJson", syntheticArrayJson)
            }.toString()
        )

        val compacted = listOf(
            ChatCompletionMessage(role = "user", content = JsonPrimitive("start")),
            assistant,
            realResult,
            skipResult
        )

        val cutoff = AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(rowReal), compacted, 0)
        assertEquals(15L, cutoff)
    }

    @Test
    fun `cross round acp id alias matches compaction cutoff by model provider id`() {
        val codec = Json { encodeDefaults = true }
        val call = AssistantToolCall(id = "orig_provider_call", type = "function", function = AssistantToolCallFunction("fetch", "{}"))
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call))
        val result = ChatCompletionMessage(role = "tool", toolCallId = "orig_provider_call", content = JsonPrimitive("data"))

        // 真实卡上的 toolCallId 带有跨 round ACP 别名 "orig_provider_call-round-2"，而 modelToolCallId 为原 provider id
        val rowAliased = entry("turn-alias", "agent", "tool_event", 2000, 20).copy(
            status = "success",
            payloadJson = buildJsonObject {
                put("taskId", "turn-alias")
                put("toolCallId", "orig_provider_call-round-2")
                put("modelToolCallId", "orig_provider_call")
                put("modelAssistantMessageJson", codec.encodeToString(assistant))
                put("modelToolResultMessageJson", codec.encodeToString(result))
            }.toString()
        )

        val compacted = listOf(assistant, result)
        val cutoff = AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(rowAliased), compacted, 0)
        assertEquals(20L, cutoff)
    }

    @Test
    fun `pending tool card in group waits and does not commit cutoff prematurely`() {
        val codec = Json { encodeDefaults = true }
        val call = AssistantToolCall(id = "pending_call", type = "function", function = AssistantToolCallFunction("wait_input", "{}"))
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call))
        val result = ChatCompletionMessage(role = "tool", toolCallId = "pending_call", content = JsonPrimitive("done"))

        val rowPending = entry("turn-pending", "agent", "tool_event", 3000, 30).copy(
            status = "pending",
            payloadJson = buildJsonObject {
                put("taskId", "turn-pending")
                put("modelToolCallId", "pending_call")
                put("status", "pending")
                put("modelAssistantMessageJson", codec.encodeToString(assistant))
                put("modelToolResultMessageJson", codec.encodeToString(result))
            }.toString()
        )

        val compacted = listOf(assistant, result)
        val cutoff = AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(rowPending), compacted, 0)
        assertNull(cutoff)
    }

    @Test
    fun `two rounds with duplicate model tool id in same turn scope reject ambiguous cutoff`() {
        val codec = Json { encodeDefaults = true }
        val call = AssistantToolCall(id = "shared_id", type = "function", function = AssistantToolCallFunction("fetch", "{}"))
        val assistant = ChatCompletionMessage(role = "assistant", toolCalls = listOf(call))
        val result1 = ChatCompletionMessage(role = "tool", toolCallId = "shared_id", content = JsonPrimitive("r1 data"))

        // 同一个 turn 内（相同 sessionId, turnId, taskId），两轮复用相同 modelToolCallId "shared_id"
        val rowRound1 = entry("turn-dup-r1", "agent", "tool_event", 1000, 10).copy(
            status = "success",
            payloadJson = buildJsonObject {
                put("taskId", "turn-dup-scope")
                put("sessionId", "session-dup")
                put("turnId", "turn-dup-scope")
                put("toolCallId", "shared_id")
                put("modelToolCallId", "shared_id")
                put("modelAssistantMessageJson", codec.encodeToString(assistant))
                put("modelToolResultMessageJson", codec.encodeToString(result1))
            }.toString()
        )
        val rowRound2 = entry("turn-dup-r2", "agent", "tool_event", 2000, 20).copy(
            status = "success",
            payloadJson = buildJsonObject {
                put("taskId", "turn-dup-scope")
                put("sessionId", "session-dup")
                put("turnId", "turn-dup-scope")
                put("toolCallId", "shared_id-round-2")
                put("modelToolCallId", "shared_id")
                put("modelAssistantMessageJson", codec.encodeToString(assistant))
                put("modelToolResultMessageJson", codec.encodeToString(result1))
            }.toString()
        )

        // compacted prefix 仅包含第一轮的 assistant 和结果
        val prefix = listOf(assistant, result1)
        val cutoff = AgentConversationHistoryRepository.resolveCompactionToolCutoff(listOf(rowRound1, rowRound2), prefix, 0)

        // 候选组内存在重复的 real modelToolCallId，必须保守拒绝（返回 null），绝不能误截断到第二轮的 20L
        assertNull(cutoff)
    }

    @Test
    fun `fork snapshot prefers canonical rows and keeps chronological visible cards`() {
        val snapshot = AgentConversationHistoryRepository.entriesForFork(
            listOf(
                entry("normal-user", "normal", "user_message", 100, 1),
                entry("assistant", "agent", "assistant_message", 200, 2),
                // This is the stale pre-ACP copy of the canonical assistant.
                entry("assistant", "normal", "assistant_message", 200, 3),
                entry("hidden", "agent", "stream_event", 300, 4),
                entry("tool", "agent", "tool_event", 400, 5),
            )
        )

        assertEquals(listOf("normal-user", "assistant", "tool"), snapshot.map { it.entryId })
        assertEquals("agent", snapshot[1].conversationMode)
        assertEquals(3, snapshot.size)
    }

    @Test
    fun `paged merged history advances beyond duplicate compatibility rows`() {
        val canonical = (1..100).map { index ->
            entry(
                entryId = "shared-$index",
                mode = "agent",
                type = "assistant_message",
                createdAt = 1_000L - index,
                id = index.toLong(),
            )
        }
        val legacyCopies = canonical.map { entry ->
            entry.copy(conversationMode = "normal", id = entry.id + 1_000L)
        }
        val legacyOnly = entry(
            entryId = "legacy-only",
            mode = "normal",
            type = "user_message",
            createdAt = 1L,
            id = 2_000L,
        )

        val (page, hasMore) = AgentConversationHistoryRepository.pageConversationEntries(
            entries = canonical + legacyCopies + legacyOnly,
            limit = 50,
            offset = 100,
        )

        assertEquals(listOf("legacy-only"), page.map { it.entryId })
        assertFalse(hasMore)
    }

    private fun entry(
        entryId: String,
        mode: String,
        type: String,
        createdAt: Long,
        id: Long,
    ) = AgentConversationEntry(
        id = id,
        conversationId = 1,
        conversationMode = mode,
        entryId = entryId,
        entryType = type,
        status = AgentConversationHistoryRepository.STATUS_SUCCESS,
        summary = entryId,
        payloadJson = "{}",
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
