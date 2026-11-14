package cn.com.omnimind.bot.webchat

import cn.com.omnimind.bot.agent.runtime.acpProfileStoreTestContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebConversationCreationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `history projection preserves the durable owner even when an old session hint differs`() {
        assertEquals(
            "deepseek-harness-acp",
            resolveConversationHarnessOwner(
                storedMode = "agent",
                sessionAgentId = "xiaowan-acp",
                conversationAgentId = "deepseek-harness-acp",
            ),
        )
    }

    @Test
    fun `explicit Xiaowan product modes retain their owner`() {
        assertEquals(
            "xiaowan-acp",
            resolveConversationHarnessOwner(
                storedMode = "normal",
                sessionAgentId = null,
                conversationAgentId = null,
            ),
        )
        assertEquals(
            "xiaowan-acp",
            resolveConversationHarnessOwner(
                storedMode = "chat_only",
                sessionAgentId = null,
                conversationAgentId = null,
            ),
        )
    }

    @Test
    fun `unbound canonical agent conversation cannot inherit selected harness`() {
        for (mode in listOf(null, "", "agent", "codex", "acp", "coding")) {
            assertNull(resolveConversationHarnessOwner(
                storedMode = mode,
                sessionAgentId = null,
                conversationAgentId = null,
            ))
        }
    }

    @Test
    fun `new agent conversations fail before insertion when no owner is supplied`() = runBlocking {
        val service = ConversationDomainService(acpProfileStoreTestContext(temporaryFolder.root))
        for (mode in listOf("agent", "codex", "acp", "coding", "subagent", "")) {
            val failure = runCatching {
                service.createConversation(title = "Missing owner", mode = mode)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().startsWith("agent_identity_required:"))
        }
    }

    @Test
    fun `creation validates explicit owners without consulting a selected Agent`() {
        val service = ConversationDomainService(acpProfileStoreTestContext(temporaryFolder.root))
        assertEquals("opencode-acp", service.validateRequestedAgentId("agent", " opencode-acp "))
        assertEquals("codex-remote", service.validateRequestedAgentId("agent", " codex-remote "))
        assertEquals("xiaowan-acp", service.validateRequestedAgentId("normal", null))
        assertEquals("xiaowan-acp", service.validateRequestedAgentId("chat_only", null))
        assertEquals("xiaowan-acp", service.validateRequestedAgentId("subagent", "xiaowan-acp"))
        assertThrows(IllegalArgumentException::class.java) {
            service.validateRequestedAgentId("normal", "opencode-acp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.validateRequestedAgentId("chat_only", "codex-remote")
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.validateRequestedAgentId("agent", "missing-agent")
        }
    }

    @Test
    fun `stored conversation mode wins over a stale Agent request fallback`() {
        assertEquals(
            "agent",
            resolveWebConversationMode(
                storedMode = "codex",
                requestedMode = "normal"
            )
        )
        assertEquals(
            "chat_only",
            resolveWebConversationMode(
                storedMode = "chat_only",
                requestedMode = null
            )
        )
    }

    @Test
    fun `each stored mode selects its own runtime`() {
        assertEquals(
            WebConversationRunKind.AGENT,
            resolveWebConversationRunKind("normal")
        )
        assertEquals(
            WebConversationRunKind.AGENT,
            resolveWebConversationRunKind("codex")
        )
        assertEquals(
            "agent",
            resolveWebConversationMode("codex", "normal")
        )
        assertEquals(
            WebConversationRunKind.CHAT_ONLY,
            resolveWebConversationRunKind("chat_only")
        )
    }

    @Test
    fun `agent stream events are mapped to web updates`() {
        val assistantUpdate = parseWebAgentEvent(
            mapOf(
                "method" to "item/agentMessage/delta",
                "turnId" to "turn-1",
                "params" to mapOf(
                    "itemId" to "item-1",
                    "delta" to "hello"
                )
            )
        )
        assertEquals("hello", assistantUpdate.assistantDelta)
        assertEquals("item-1-agent-message", assistantUpdate.assistantEntryId)
        assertEquals("turn-1", assistantUpdate.parentTaskId)

        assertEquals(
            "thinking",
            parseWebAgentEvent(
                mapOf(
                    "method" to "item/reasoning/delta",
                    "turnId" to "turn-1",
                    "params" to mapOf(
                        "itemId" to "reasoning-1",
                        "delta" to "thinking"
                    )
                )
            ).reasoningDelta
        )
        assertNull(
            parseWebAgentEvent(
                mapOf("method" to "turn/completed")
            ).terminalKind
        )
    }

    @Test
    fun `official ACP session updates are mapped to web updates`() {
        val message = parseWebAgentEvent(
            mapOf(
                "method" to "session/update",
                "turnId" to "turn-acp-1",
                "params" to mapOf(
                    "sessionId" to "session-1",
                    "update" to mapOf(
                        "sessionUpdate" to "agent_message_chunk",
                        "content" to mapOf("text" to "hello")
                    )
                )
            )
        )
        assertEquals("hello", message.assistantDelta)
        assertEquals("turn-acp-1", message.parentTaskId)

        val tool = parseWebAgentEvent(
            mapOf(
                "method" to "session/update",
                "turnId" to "turn-acp-1",
                "params" to mapOf(
                    "update" to mapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "tool-1",
                        "title" to "Read file",
                        "status" to "completed"
                    )
                )
            )
        ).tool
        assertEquals("tool-1-agent-file", tool?.entryId)
        assertEquals("success", tool?.status)
    }
    @Test
    fun `official ACP session prompt result and error are mapped to web terminal updates`() {
        val completedEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-1",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "completed",
                        "stopReason" to "end_turn",
                        "completed" to true
                    )
                )
            )
        )
        assertEquals("completed", completedEvent.terminalKind)

        val cancelledEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-2",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "cancelled",
                        "stopReason" to "cancelled"
                    )
                )
            )
        )
        assertEquals("cancelled", cancelledEvent.terminalKind)

        val maxTokensEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-3",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "completed",
                        "stopReason" to "max_tokens"
                    )
                )
            )
        )
        assertEquals("max_tokens", maxTokensEvent.terminalKind)

        val refusalEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-4",
                "message" to mapOf(
                    "result" to mapOf(
                        "stopReason" to "refusal"
                    )
                )
            )
        )
        assertEquals("refusal", refusalEvent.terminalKind)

        val errorEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-5",
                "message" to mapOf(
                    "error" to mapOf(
                        "code" to -32603,
                        "message" to "模型调用失败"
                    )
                )
            )
        )
        assertEquals("error", errorEvent.terminalKind)
        assertEquals("模型调用失败", errorEvent.errorMessage)

        // P2 regression: {status: error, completed: true} without error message must be error, never success
        val statusErrorEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-6",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "error",
                        "completed" to true
                    )
                )
            )
        )
        assertEquals("error", statusErrorEvent.terminalKind)

        // P2 regression: status-only max_tokens and refusal without stopReason must never upgrade to completed
        val statusMaxTokensEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-7",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "max_tokens",
                        "completed" to true
                    )
                )
            )
        )
        assertEquals("max_tokens", statusMaxTokensEvent.terminalKind)

        val statusRefusalEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-8",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "refusal",
                        "completed" to true
                    )
                )
            )
        )
        assertEquals("refusal", statusRefusalEvent.terminalKind)

        // P1 regression: max_turn_requests must map to max_turn_requests interruption, never completed
        val maxTurnRequestsEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-max-req",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "completed",
                        "stopReason" to "max_turn_requests",
                        "completed" to true
                    )
                )
            )
        )
        assertEquals("max_turn_requests", maxTurnRequestsEvent.terminalKind)

        // P2 regression: unknown reasons must not upgrade to success
        val unknownStatusEvent = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-terminal-9",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "unknown_interrupted_code",
                        "completed" to true
                    )
                )
            )
        )
        assertNull(unknownStatusEvent.terminalKind)
    }

    @Test
    fun `legacy terminal notification before official session prompt response remains projection only`() {
        val legacyTurnCompleted = parseWebAgentEvent(
            mapOf(
                "method" to "turn_completed",
                "turnId" to "turn-legacy-ordering",
                "threadId" to "thread-1"
            )
        )
        assertNull(legacyTurnCompleted.terminalKind)

        val codexTaskComplete = parseWebAgentEvent(
            mapOf(
                "method" to "codex_event",
                "turnId" to "turn-legacy-ordering",
                "params" to mapOf(
                    "msg" to mapOf(
                        "type" to "task_complete"
                    )
                )
            )
        )
        assertNull(codexTaskComplete.terminalKind)

        val officialPromptResponse = parseWebAgentEvent(
            mapOf(
                "method" to "session/prompt",
                "turnId" to "turn-legacy-ordering",
                "message" to mapOf(
                    "result" to mapOf(
                        "status" to "completed",
                        "stopReason" to "end_turn",
                        "completed" to true
                    )
                )
            )
        )
        assertEquals("completed", officialPromptResponse.terminalKind)
    }



    @Test
    fun `web agent runs explicitly request full access`() {
        val arguments = buildWebAgentTurnArguments(
            conversationId = 42L,
            userMessage = "检查权限",
            attachments = emptyList(),
            cwd = " /workspace ",
            agentId = " claude-code-acp ",
            model = "deepseek-v4-pro",
            effort = "high",
            conversationMode = "chat_only"
        )

        assertEquals(42L, arguments["conversationId"])
        assertEquals("检查权限", arguments["text"])
        assertEquals("never", arguments["approvalPolicy"])
        assertEquals("user", arguments["approvalsReviewer"])
        assertEquals(
            mapOf("type" to "dangerFullAccess"),
            arguments["sandboxPolicy"]
        )
        assertEquals("/workspace", arguments["cwd"])
        assertEquals("claude-code-acp", arguments["agentId"])
        assertEquals("deepseek-v4-pro", arguments["model"])
        assertEquals("high", arguments["effort"])
        assertEquals("chat_only", arguments["conversationMode"])
    }

    @Test
    fun `web agent selection prefers the stored conversation binding`() {
        assertEquals(
            "opencode-acp",
            resolveWebAgentId(
                storedAgentId = "opencode-acp",
                requestedAgentId = null
            )
        )
        assertEquals(
            "claude-code-acp",
            resolveWebAgentId(
                storedAgentId = null,
                requestedAgentId = " claude-code-acp "
            )
        )
        assertEquals(
            "codex-remote",
            resolveWebAgentId(storedAgentId = "codex-remote", requestedAgentId = null)
        )
    }

    @Test
    fun `web agent requests without a durable or explicit owner are rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolveWebAgentId(storedAgentId = null, requestedAgentId = null, conversationMode = "agent")
        }
        assertTrue(error.message.orEmpty().startsWith("agent_identity_required:"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `web agent selection rejects a conflicting request`() {
        resolveWebAgentId(
            storedAgentId = "codex-acp",
            requestedAgentId = "opencode-acp"
        )
    }

    @Test
    fun `normal web conversation always resolves to Xiaowan`() {
        assertEquals(
            "xiaowan-acp",
            resolveWebAgentId(
                storedAgentId = null,
                requestedAgentId = null,
                conversationMode = "normal",
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normal web conversation rejects another Harness`() {
        resolveWebAgentId(
            storedAgentId = null,
            requestedAgentId = "dsh",
            conversationMode = "normal",
        )
    }

    @Test
    fun `agent tool lifecycle keeps a stable card id and terminal status`() {
        val started = parseWebAgentEvent(
            mapOf(
                "method" to "item/started",
                "turnId" to "turn-2",
                "params" to mapOf(
                    "item" to mapOf(
                        "id" to "command-1",
                        "type" to "commandExecution",
                        "command" to "pwd",
                        "status" to "running"
                    )
                )
            )
        ).tool
        val completed = parseWebAgentEvent(
            mapOf(
                "method" to "item/completed",
                "turnId" to "turn-2",
                "params" to mapOf(
                    "item" to mapOf(
                        "id" to "command-1",
                        "type" to "commandExecution",
                        "command" to "pwd",
                        "status" to "completed"
                    )
                )
            )
        ).tool

        assertEquals("command-1-agent-command", started?.entryId)
        assertEquals("running", started?.status)
        assertEquals(started?.entryId, completed?.entryId)
        assertEquals("success", completed?.status)
        assertEquals("turn-2", completed?.parentTaskId)
    }

    @Test
    fun `web ACP uses pending status over nested raw output`() {
        val tool = parseWebAgentEvent(
            mapOf(
                "method" to "session/update",
                "turnId" to "turn-actionable",
                "params" to mapOf(
                    "update" to mapOf(
                        "sessionUpdate" to "tool_call_update",
                        "toolCallId" to "tool-actionable",
                        "title" to "高权限操作",
                        "status" to "pending",
                        "success" to false,
                        "rawOutput" to mapOf(
                            "success" to false,
                            "question" to "请确认执行高权限操作"
                        )
                    )
                )
            )
        ).tool

        assertEquals("pending", tool?.status)
    }

    @Test
    fun `first user message becomes the conversation title like Flutter`() {
        assertEquals("帮我分析这个项目", deriveWebConversationTitle("  帮我分析这个项目  "))
        assertEquals(
            "12345678901234567890...",
            deriveWebConversationTitle("123456789012345678901234")
        )
        assertNull(deriveWebConversationTitle("   "))
    }
}
