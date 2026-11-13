package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.llm.ProviderModelOption
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.SceneModelBindingEntry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.AgentFinalResponse
import cn.com.omnimind.bot.agent.AgentResult
import cn.com.omnimind.bot.agent.runtime.buildXiaowanPromptParts
import com.agentclientprotocol.model.MessageId
import com.agentclientprotocol.model.PromptResponse
import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.EmbeddedResourceResource
import com.agentclientprotocol.model.SessionUpdate
import com.agentclientprotocol.model.StopReason
import com.agentclientprotocol.model.ToolKind
import com.agentclientprotocol.model.ToolCallStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class XiaowanAcpPresentationBridgeTest {
    @Test
    fun `tool titles follow the context language through completion`() = runBlocking {
        for ((locale, title) in listOf(
            PromptLocale.ZH_CN to "写入文件",
            PromptLocale.EN_US to "Write File",
        )) {
            val updates = mutableListOf<SessionUpdate>()
            val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(locale)) { updates += it }
            bridge.onToolCallStart("write", "file_write", JsonObject(emptyMap()), "file")
            bridge.onToolCallComplete("write", "file_write", ToolExecutionResult.Error("file_write", "denied"))

            assertEquals(title, (updates.first() as SessionUpdate.ToolCall).title)
            assertEquals(title, (updates.last() as SessionUpdate.ToolCallUpdate).title)
        }
    }

    @Test
    fun `tool progress is output and never replaces model input`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), updates::add)
        val input = Json.parseToJsonElement("""{"action":"shell.exec","arguments":{"command":"id","confirmed":false}}""").jsonObject
        bridge.onToolCallStart("permission-tool", "android_privileged_action", input, "command")
        repeat(3) { index ->
            bridge.onToolCallProgress("permission-tool", "android_privileged_action", "running",
                mapOf("backend" to "ROOT", "terminalOutputDelta" to "line-$index"))
        }
        assertEquals(input, (updates.first() as SessionUpdate.ToolCall).rawInput)
        updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().forEachIndexed { index, update ->
            assertTrue(update.rawInput == null || update.rawInput == kotlinx.serialization.json.JsonNull)
            assertEquals("line-$index", update.rawOutput!!.jsonObject["terminalOutputDelta"]!!.jsonPrimitive.content)
            assertEquals(ToolCallStatus.IN_PROGRESS, update.status)
        }
    }

    @Test
    fun `streamed tool input updates one official card without claiming execution`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }
        val call = cn.com.omnimind.bot.agent.AssistantToolCall(
            id = "html-stream",
            function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("file_write", "{\"content\":\"<html>"),
        )
        bridge.onToolCallInput(call, "file")
        bridge.onToolCallInput(call.copy(function = call.function.copy(arguments = "{\"content\":\"<html>body")), "file")
        assertEquals(ToolCallStatus.PENDING, (updates.first() as SessionUpdate.ToolCall).status)
        val inputUpdate = updates.last() as SessionUpdate.ToolCallUpdate
        assertEquals(null, inputUpdate.status)
        assertEquals("{\"content\":\"<html>body", inputUpdate.rawInput?.jsonPrimitive?.content)
        val args = JsonObject(mapOf("content" to JsonPrimitive("<html>body</html>")))
        bridge.onToolCallStart(call.id, "file_write", args, "file")
        bridge.onToolCallStart(call.id, "file_write", args, "file")
        bridge.onToolCallComplete(call.id, "file_write", ToolExecutionResult.Error("file_write", "Permission denied"))
        val size = updates.size
        bridge.onToolCallInput(call, "file")
        bridge.onToolCallStart(call.id, "file_write", args, "file")
        assertEquals(size, updates.size)
        assertEquals(1, updates.filterIsInstance<SessionUpdate.ToolCall>().size)
        assertEquals(ToolCallStatus.FAILED, (updates.last() as SessionUpdate.ToolCallUpdate).status)
        assertTrue(updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().all { it.toolCallId.value == call.id })
    }

    @Test
    fun `image prompt keeps a readable path and enables inline provider input`() {
        val prompt = buildXiaowanPromptParts(
            listOf(
                ContentBlock.Image(
                    data = "AAAA",
                    mimeType = "image/png",
                    uri = "file:///workspace/.omnibot/attachments/task/image.png",
                )
            )
        )

        assertEquals(
            "file:///workspace/.omnibot/attachments/task/image.png",
            prompt.attachments.single()["url"],
        )
        assertEquals(
            "file:///workspace/.omnibot/attachments/task/image.png",
            prompt.attachments.single()["path"],
        )
        assertEquals(true, prompt.attachments.single()["sendToModel"])
    }

    @Test
    fun `resource link image enables inline provider input`() {
        val prompt = buildXiaowanPromptParts(
            listOf(
                ContentBlock.ResourceLink(
                    name = "photo.png",
                    uri = "content://com.example.provider/photo",
                    mimeType = "image/png",
                    size = 12,
                )
            )
        )

        assertEquals(true, prompt.attachments.single()["sendToModel"])
        assertEquals("content://com.example.provider/photo", prompt.attachments.single()["path"])
    }

    @Test
    fun `embedded image enables inline provider input`() {
        val prompt = buildXiaowanPromptParts(
            listOf(
                ContentBlock.Resource(
                    EmbeddedResourceResource.BlobResourceContents(
                        uri = "embedded://photo",
                        mimeType = "image/png",
                        blob = "AAAA",
                    )
                )
            )
        )

        assertEquals(true, prompt.attachments.single()["sendToModel"])
    }

    @Test
    fun `resource link remains raw until the single workspace adapter`() {
        val prompt = buildXiaowanPromptParts(
            listOf(
                ContentBlock.ResourceLink(
                    name = "notes.txt",
                    uri = "content://com.example.provider/notes",
                    mimeType = "text/plain",
                    size = 12,
                )
            )
        )

        val attachment = prompt.attachments.single()
        assertEquals("content://com.example.provider/notes", attachment["path"])
        assertFalse(attachment.containsKey("promptPath"))
        assertFalse(attachment.containsKey("workspacePath"))
    }

    @Test
    fun `permission outcome requires an explicit allow option`() {
        val json = Json { ignoreUnknownKeys = true }
        assertTrue(
            isAllowedAcpPermissionOutcome(
                json.parseToJsonElement(
                    """{"outcome":{"outcome":"selected","optionId":"allow_once"}}"""
                )
            )
        )
        assertFalse(
            isAllowedAcpPermissionOutcome(
                json.parseToJsonElement(
                    """{"outcome":{"outcome":"selected","optionId":"reject_once"}}"""
                )
            )
        )
        assertFalse(
            isAllowedAcpPermissionOutcome(
                json.parseToJsonElement(
                    """{"outcome":{"outcome":"selected"}}"""
                )
            )
        )
    }


    @Test
    fun `provider finish reasons map to ACP stop reasons`() {
        assertEquals(StopReason.END_TURN, acpStopReasonForFinishReason("stop"))
        assertEquals(StopReason.MAX_TOKENS, acpStopReasonForFinishReason("length"))
        assertEquals(StopReason.CANCELLED, acpStopReasonForFinishReason("cancelled"))
        assertEquals(StopReason.REFUSAL, acpStopReasonForFinishReason("content_filter"))
        assertEquals(StopReason.MAX_TURN_REQUESTS, acpStopReasonForFinishReason("turn_limit"))
    }

    @Test
    fun `ordinary clarification is emitted as text without clarification metadata`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onClarifyRequired("需要确认目标文件", listOf("path"))

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("需要确认目标文件", (message.content as ContentBlock.Text).text)
        assertTrue(message._meta is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `declared runtime tool type is used for ACP kind`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart(
            "call-custom",
            "vendor_action_with_no_standard_name",
            JsonObject(emptyMap()),
            "terminal",
        )

        val tool = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
        assertEquals(ToolKind.EXECUTE, tool.kind)
    }

    @Test
    fun `legacy tool start without an ACP id is ignored`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("android_privileged_action", JsonObject(emptyMap()))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `replayed tool start with the same ACP id is idempotent`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("call-1", "android_privileged_action", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-1", "android_privileged_action", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-1",
            "android_privileged_action",
            ToolExecutionResult.Error("android_privileged_action", "done"),
        )
        bridge.onToolCallStart("call-1", "android_privileged_action", JsonObject(emptyMap()))

        assertEquals(1, updates.filterIsInstance<SessionUpdate.ToolCall>().size)
    }

    @Test
    fun `orphan tool completion does not invent an ACP call id`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallComplete("android_privileged_action", ToolExecutionResult.Error("android_privileged_action", "missing"))

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `orphan tool progress does not invent an ACP call id`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallProgress("android_privileged_action", "执行中")

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `permission waiting updates the existing ACP tool call`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart(
            "call-privileged",
            "android_privileged_action",
            JsonObject(emptyMap()),
        )
        bridge.emitToolPending(
            toolCallId = "call-privileged",
            title = "需要确认高权限操作",
            detail = "命令尚未执行，请确认。",
        )

        val toolUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
        assertEquals(1, toolUpdates.size)
        assertEquals("call-privileged", toolUpdates.single().toolCallId.value)
        assertEquals(ToolCallStatus.PENDING, toolUpdates.single().status)
    }

    @Test
    fun `xiaowan exposes the bound model plus provider catalog`() {
        val models = requireNotNull(
            buildXiaowanModelsFromBinding(
                SceneModelBindingEntry("scene", "provider", "selected"),
                listOf(
                    ProviderModelOption(id = "selected", displayName = "Selected"),
                    ProviderModelOption(id = "other", displayName = "Other"),
                ),
            )
        )

        assertEquals("selected,other", models.available.joinToString(",") { it.modelId.value })
        assertEquals("selected", models.configuredModelId)
    }

    @Test
    fun `ACP prompt metadata restores terminal environment for Xiaowan tools`() {
        val environment = xiaowanTerminalEnvironmentFromMeta(
            JsonObject(
                mapOf(
                    "terminalEnvironment" to JsonObject(
                        mapOf(
                            "API_ENDPOINT" to JsonPrimitive("https://example.test"),
                            "EMPTY_VALUE" to JsonPrimitive(""),
                        )
                    )
                )
            )
        )

        assertEquals(
            mapOf(
                "API_ENDPOINT" to "https://example.test",
                "EMPTY_VALUE" to "",
            ),
            environment,
        )
    }

    @Test
    fun `thinking start emits an empty ACP thought chunk for the existing card`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingStart()

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        assertEquals("", (thought.content as ContentBlock.Text).text)
        val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val reasoning = namespace["reasoning"] as JsonObject
        assertEquals("thinking", reasoning["stage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning after a tool starts a new ACP thought segment`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("先分析")
        bridge.onToolCallStart("call-1", "terminal", JsonObject(emptyMap()))
        bridge.onThinkingUpdate("工具结果返回后继续分析")

        val thoughts = updates
            .filterIsInstance<SessionUpdate.AgentThoughtChunk>()
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
        assertEquals(2, thoughts.size)
        assertEquals(2, thoughts.map { it.messageId }.distinct().size)
        assertEquals("先分析", (thoughts[0].content as ContentBlock.Text).text)
        assertEquals(
            "工具结果返回后继续分析",
            (thoughts[1].content as ContentBlock.Text).text,
        )
        val segments = thoughts.map { thought ->
            val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            val reasoning = namespace["reasoning"] as JsonObject
            reasoning["segmentIndex"]?.jsonPrimitive?.content?.toInt()
        }
        assertEquals(listOf(0, 1), segments)
    }

    @Test
    fun `tool boundary does not create an empty thought card before later reasoning`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("工具前的思考")
        bridge.onToolCallStart("call-1", "terminal", JsonObject(emptyMap()))
        // AgentOrchestrator starts another model round after the tool. The
        // next round may produce another tool without producing reasoning.
        bridge.onThinkingStart()
        bridge.onThinkingUpdate("工具后的思考")

        val thoughts = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
        assertEquals(1, thoughts.count { (it.content as ContentBlock.Text).text.isEmpty() })
        assertEquals(
            listOf("工具前的思考", "工具后的思考"),
            thoughts
                .map { (it.content as ContentBlock.Text).text }
                .filter(String::isNotEmpty),
        )
        val nonEmptyIds = thoughts
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
            .map { it.messageId }
        assertEquals(2, nonEmptyIds.distinct().size)
    }

    @Test
    fun `provider snapshot reset starts a new reasoning segment`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("第一次请求的思考")
        // This is what an internal HTTP retry looks like to the callback: the
        // retry is transparent, so there is no separate onRetrying event.
        bridge.onThinkingUpdate("重试请求的思考")

        val thoughts = updates
            .filterIsInstance<SessionUpdate.AgentThoughtChunk>()
            .filter { (it.content as ContentBlock.Text).text.isNotEmpty() }
        assertEquals(2, thoughts.size)
        assertEquals(2, thoughts.map { it.messageId }.distinct().size)
        val segments = thoughts.map { thought ->
            val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            (namespace["reasoning"] as JsonObject)["segmentIndex"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("0", "1"), segments)
    }

    @Test
    fun `provider snapshot reset does not fabricate a connection retry card`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onChatMessage("第一代答案", isFinal = false)
        bridge.onChatMessage("新一代答案", isFinal = true)

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(2, messages.size)
        assertTrue(
            messages.none { message ->
                val namespace = (message._meta as? JsonObject)
                    ?.get("cn.com.omnimind.agent") as? JsonObject
                namespace?.containsKey("retry") == true
            }
        )
    }

    @Test
    fun `legacy progress without tool id cannot cross-wire parallel same-name tools`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("call-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-2", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallProgress("file_read", "读取中", emptyMap())

        val progressIds = updates
            .filterIsInstance<SessionUpdate.ToolCallUpdate>()
            .filter { it.status == com.agentclientprotocol.model.ToolCallStatus.IN_PROGRESS }
            .map { it.toolCallId.value }
        assertEquals(listOf("call-1", "call-2"), progressIds)
    }

    @Test
    fun `legacy completion without tool id does not complete an ambiguous call`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("call-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-2", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "file_read",
            ToolExecutionResult.ChatMessage("不应猜测归属"),
        )

        val completed = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
            .filter { it.status == com.agentclientprotocol.model.ToolCallStatus.COMPLETED }
        assertEquals(0, completed.size)
    }

    @Test
    fun `Xiaowan tool names are projected to official ACP kinds`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("read-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolCallStart("edit-1", "apply_patch", JsonObject(emptyMap()))
        bridge.onToolCallStart("exec-1", "terminal_exec", JsonObject(emptyMap()))
        bridge.onToolCallStart("search-1", "workspace_search", JsonObject(emptyMap()))

        val kinds = updates.filterIsInstance<SessionUpdate.ToolCall>().associate {
            it.toolCallId.value to it.kind
        }
        assertEquals(ToolKind.READ, kinds["read-1"])
        assertEquals(ToolKind.EDIT, kinds["edit-1"])
        assertEquals(ToolKind.EXECUTE, kinds["exec-1"])
        assertEquals(ToolKind.SEARCH, kinds["search-1"])
    }

    @Test
    fun `error recovery state is carried by the ACP assistant update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onError("网络连接中断", retryable = true)

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("网络连接中断", (message.content as ContentBlock.Text).text)
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val recovery = namespace["recovery"] as JsonObject
        assertEquals("true", recovery["retryable"]?.jsonPrimitive?.content)
        assertEquals(null, recovery["continueable"])
    }

    @Test
    fun `partial error does not advertise approximate ACP continuation`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onChatMessage("半截答案", isFinal = false)
        bridge.onError("连接中断", retryable = true)

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(2, messages.size)
        assertEquals(messages[0].messageId, messages[1].messageId)
        assertEquals("", (messages[1].content as ContentBlock.Text).text)
        val namespace = (messages[1]._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val recovery = namespace["recovery"] as JsonObject
        assertEquals("true", recovery["retryable"]?.jsonPrimitive?.content)
        assertEquals(null, recovery["continueable"])
        assertEquals("true", recovery["persistAsError"]?.jsonPrimitive?.content)
        assertEquals(null, recovery["continueResumeMode"])
    }

    @Test
    fun `ordinary clarification stays plain assistant text`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onClarifyRequired("是否继续执行？", listOf("arguments.confirmed"))

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("是否继续执行？", (message.content as ContentBlock.Text).text)
        assertEquals(kotlinx.serialization.json.JsonNull, message._meta)
    }

    @Test
    fun `automatic context compaction does not create a private ACP update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onContextCompactionStateChanged(
            isCompacting = true,
            latestPromptTokens = 126000,
            promptTokenThreshold = 128000,
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `completion preserves the old turn usage footer data in ACP metadata`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onChatMessage("已完成", isFinal = false)
        bridge.onComplete(
            AgentResult.Success(
                response = AgentFinalResponse(content = "已完成"),
                latestPromptTokens = 100,
                promptTokenThreshold = 128000,
                completionTokens = 20,
                cachedTokens = 10,
                cacheCreationTokens = 3,
                totalTokens = 120,
            )
        )

        val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
        assertEquals(2, messages.size)
        val namespace = (messages.last()._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val usage = namespace["usage"] as JsonObject
        val turnUsage = usage["turnUsage"] as JsonObject
        assertEquals(100, turnUsage["ctx"]?.jsonPrimitive?.content?.toInt())
        assertEquals(100, turnUsage["in"]?.jsonPrimitive?.content?.toInt())
        assertEquals(20, turnUsage["out"]?.jsonPrimitive?.content?.toInt())
        assertEquals(10, turnUsage["cache"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `ACP prompt response does not count cached input twice`() {
        val success = AgentResult.Success(
            response = AgentFinalResponse(content = "已完成"),
            latestPromptTokens = 2_057,
            completionTokens = 5,
            cachedTokens = 2_048,
            cacheCreationTokens = 3,
        )

        val usage = requireNotNull(success.toAcpUsage())
        assertEquals(6L, usage.inputTokens)
        assertEquals(2_048L, usage.cachedReadTokens)
        assertEquals(3L, usage.cachedWriteTokens)

        val turnUsage = PromptResponse(
            stopReason = StopReason.END_TURN,
            usage = usage,
        ).toAcpTurnUsageUpdate(messageId = MessageId("msg_usage"))
            ?.get("_meta")
            ?.let { it as Map<*, *> }
            ?.get("cn.com.omnimind.agent")
            ?.let { it as Map<*, *> }
            ?.get("usage")
            ?.let { it as Map<*, *> }
            ?.get("turnUsage")
            ?.let { it as Map<*, *> }

        assertEquals(2_057L, turnUsage?.get("ctx"))
        assertEquals(2_057L, turnUsage?.get("in"))
        assertEquals(2_048L, turnUsage?.get("cache"))
        assertEquals(6L, turnUsage?.get("uncachedInputTokens"))
    }

    @Test
    fun `completion keeps output-only usage when prompt tokens are unavailable`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onComplete(
            AgentResult.Success(
                response = AgentFinalResponse(content = "已完成"),
                completionTokens = 20,
            )
        )

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val usage = namespace["usage"] as JsonObject
        val turnUsage = usage["turnUsage"] as JsonObject
        assertEquals("0", turnUsage["ctx"]?.jsonPrimitive?.content)
        assertEquals("20", turnUsage["out"]?.jsonPrimitive?.content)
    }

    @Test
    fun `prompt token usage is emitted as the standard ACP usage update`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onPromptTokenUsageChanged(100, 128_000)

        val usage = updates.filterIsInstance<SessionUpdate.UsageUpdate>().single()
        assertEquals(100L, usage.used)
        assertEquals(128_000L, usage.size)
        assertEquals(kotlinx.serialization.json.JsonNull, usage._meta)
    }

    @Test
    fun `completion projects legacy output state and restores empty output fallback`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onComplete(
            AgentResult.Success(
                response = AgentFinalResponse(content = ""),
                outputKind = "none",
                hasUserVisibleOutput = false,
            )
        )

        val message = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>().single()
        assertEquals("暂时无法生成回复，请重试。", (message.content as ContentBlock.Text).text)
        val namespace = (message._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val completion = namespace["completion"] as JsonObject
        assertEquals("none", completion["outputKind"]?.jsonPrimitive?.content)
        assertEquals("false", completion["hasUserVisibleOutput"]?.jsonPrimitive?.content)
    }

    @Test
    fun `final performance metadata survives deduplication of an identical text snapshot`() =
        runBlocking {
            val updates = mutableListOf<SessionUpdate>()
            val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

            bridge.onChatMessage("最终回答", isFinal = false)
            bridge.onChatMessage(
                "最终回答",
                isFinal = true,
                prefillTokensPerSecond = 36.6,
                decodeTokensPerSecond = 12.4,
            )

            val messages = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
            assertEquals(2, messages.size)
            assertEquals(messages.first().messageId, messages.last().messageId)
            assertEquals("", (messages.last().content as ContentBlock.Text).text)
            val namespace = (messages.last()._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
            val usage = namespace["usage"] as JsonObject
            assertEquals(36.6, usage["prefillTokensPerSecond"]?.jsonPrimitive?.double ?: 0.0, 0.0)
            assertEquals(12.4, usage["decodeTokensPerSecond"]?.jsonPrimitive?.double ?: 0.0, 0.0)
        }

    @Test
    fun `structured thinking emits a display delta instead of raw JSON`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingUpdate(
            """{"task_description":"检查统一卡片","sub_tasks":["保留工具结果"],"preparation":"确认 ACP 流"}"""
        )

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        val text = (thought.content as ContentBlock.Text).text
        assertEquals("检查统一卡片\n\n- 保留工具结果\n\n确认 ACP 流", text)
    }

    @Test
    fun `legacy thinking fields stay inside the official ACP thought chunk`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingUpdate(
            """{"task_description":"恢复旧能力","summary":"已完成分析","stage":"planning","phase":"prepare"}"""
        )

        val thought = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>().single()
        val namespace = (thought._meta as JsonObject)["cn.com.omnimind.agent"] as JsonObject
        val reasoning = namespace["reasoning"] as JsonObject
        assertEquals("已完成分析", reasoning["summary"]?.jsonPrimitive?.content)
        assertEquals("planning", reasoning["stage"]?.jsonPrimitive?.content)
        assertEquals("prepare", reasoning["phase"]?.jsonPrimitive?.content)
    }

    @Test
    fun `partial structured thinking streams readable deltas without raw json`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onThinkingUpdate("""{"task_description":"检查统一""")
        bridge.onThinkingUpdate(
            """{"task_description":"检查统一卡片","sub_tasks":["保留工具"""
        )
        bridge.onThinkingUpdate(
            """{"task_description":"检查统一卡片","sub_tasks":["保留工具结果"],"preparation":"确认 ACP 流"}"""
        )

        val thoughts = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
        val combined = thoughts.joinToString("") { (it.content as ContentBlock.Text).text }
        assertEquals("检查统一卡片\n\n- 保留工具结果\n\n确认 ACP 流", combined)
        assertEquals(false, combined.contains('{'))
        assertEquals(1, thoughts.map { it.messageId }.distinct().size)
    }

    @Test
    fun `tool completion keeps structured terminal result in ACP raw output`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("call-1", "terminal", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-1",
            "terminal",
            ToolExecutionResult.TerminalResult(
                toolName = "terminal",
                summaryText = "Command completed",
                previewJson = "{\"exitCode\":0}",
                rawResultJson = "{\"stdout\":\"hello\"}",
                terminalOutput = "hello",
                terminalSessionId = "shell-1",
            ),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("terminal", rawOutput["toolType"]?.jsonPrimitive?.content)
        assertEquals("Command completed", rawOutput["summary"]?.jsonPrimitive?.content)
        assertEquals("hello", rawOutput["terminalOutput"]?.jsonPrimitive?.content)
        assertEquals("shell-1", rawOutput["terminalSessionId"]?.jsonPrimitive?.content)
        assertEquals(null, rawOutput["previewJson"])
        assertEquals("{\"stdout\":\"hello\"}", rawOutput["rawResultJson"]?.jsonPrimitive?.content)
        assertEquals("0", (rawOutput["result"] as JsonObject)["exitCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `large tool result reaches the ACP update without a host truncation`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }
        val stdout = "payload-" + "x".repeat(128 * 1024)
        val rawResult = JsonObject(mapOf("stdout" to JsonPrimitive(stdout))).toString()

        bridge.onToolCallStart("call-large-output", "terminal", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-large-output",
            "terminal",
            ToolExecutionResult.TerminalResult(
                toolName = "terminal",
                summaryText = "Command completed",
                previewJson = "{\"exitCode\":0}",
                rawResultJson = rawResult,
                terminalOutput = stdout,
            ),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals(rawResult, rawOutput["rawResultJson"]?.jsonPrimitive?.content)
        assertEquals(
            stdout,
            Json.parseToJsonElement(rawOutput["rawResultJson"]!!.jsonPrimitive.content)
                .jsonObject["stdout"]?.jsonPrimitive?.content,
        )
        assertEquals(stdout, rawOutput["terminalOutput"]?.jsonPrimitive?.content)
    }

    @Test
    fun `permission tool result keeps the existing permission card payload`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("call-permission", "vlm_task", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-permission",
            "vlm_task",
            ToolExecutionResult.PermissionRequired(listOf("无障碍权限")),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("permission_section", rawOutput["type"]?.jsonPrimitive?.content)
        assertEquals(
            "无障碍权限",
            (rawOutput["missing"] as JsonArray).single().jsonPrimitive.content,
        )
    }

    @Test
    fun `clarification tool result is terminal failure without a request channel`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext()) { updates += it }

        bridge.onToolCallStart("call-confirm", "android_privileged_action", JsonObject(emptyMap()))
        bridge.onToolCallComplete(
            "call-confirm",
            "android_privileged_action",
            ToolExecutionResult.Clarify(
                question = "确认执行高权限 shell 命令？",
                missingFields = listOf("arguments.confirmed"),
            ),
        )

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        assertEquals(ToolCallStatus.FAILED, completion.status)
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("确认执行高权限 shell 命令？", rawOutput["question"]?.jsonPrimitive?.content)
    }

    @Test
    fun `onToolReplayReady binds canonical payloads to tool completion rawOutput`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-1", "turn-1") { updates += it }
        val assistant = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(
                cn.com.omnimind.bot.agent.AssistantToolCall(
                    id = "call-1",
                    function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("file_read", """{"path":"/tmp/a.txt"}""")
                )
            )
        )
        val toolResult = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "tool",
            toolCallId = "call-1",
            content = JsonPrimitive("hello world")
        )

        bridge.onToolCallStart("call-1", "file_read", JsonObject(emptyMap()))
        bridge.onToolReplayReady("call-1", assistant, toolResult, isSynthetic = false)
        bridge.onToolCallComplete("call-1", "file_read", ToolExecutionResult.ContextResult("file_read", summaryText = "hello world", previewJson = "{}", rawResultJson = "{}"))

        val completion = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().last()
        assertEquals(ToolCallStatus.COMPLETED, completion.status)
        val rawOutput = completion.rawOutput as JsonObject
        assertEquals("call-1", rawOutput["modelToolCallId"]?.jsonPrimitive?.content)
        assertTrue(rawOutput["modelAssistantMessageJson"]?.jsonPrimitive?.content?.contains("file_read") == true)
        assertTrue(rawOutput["modelToolResultMessageJson"]?.jsonPrimitive?.content?.contains("hello world") == true)
        assertEquals(null, rawOutput["modelToolResultMessagesJson"])
    }

    @Test
    fun `synthetic skipped tool results update existing real tool without creating phantom cards`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-2", "turn-2") { updates += it }
        val assistant = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(
                cn.com.omnimind.bot.agent.AssistantToolCall(
                    id = "call-exec",
                    function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("file_read", """{"path":"/tmp/a.txt"}""")
                ),
                cn.com.omnimind.bot.agent.AssistantToolCall(
                    id = "call-skip",
                    function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("file_write", """{"path":"/tmp/b.txt"}""")
                )
            )
        )
        val realResult = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "tool",
            toolCallId = "call-exec",
            content = JsonPrimitive("read ok")
        )
        val syntheticResult = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "tool",
            toolCallId = "call-skip",
            content = JsonPrimitive("skipped due to error")
        )

        // 1. First tool executed
        bridge.onToolCallStart("call-exec", "file_read", JsonObject(emptyMap()))
        bridge.onToolReplayReady("call-exec", assistant, realResult, isSynthetic = false)
        bridge.onToolCallComplete("call-exec", "file_read", ToolExecutionResult.ContextResult("file_read", summaryText = "read ok", previewJson = "{}", rawResultJson = "{}"))

        // 2. Second tool skipped: onToolReplayReady called with isSynthetic = true
        bridge.onToolReplayReady("call-skip", assistant, syntheticResult, isSynthetic = true)

        // Verify: No tool_call started for call-skip
        val startedCalls = updates.filterIsInstance<SessionUpdate.ToolCall>()
        assertEquals(1, startedCalls.size)
        assertEquals("call-exec", startedCalls.single().toolCallId.value)

        // Verify: Last update is on call-exec carrying synthetic result in modelToolResultMessagesJson
        val toolUpdates = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
        val finalUpdate = toolUpdates.last()
        assertEquals("call-exec", finalUpdate.toolCallId.value)
        val rawOutput = finalUpdate.rawOutput as JsonObject
        val groupJson = rawOutput["modelToolResultMessagesJson"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(groupJson.contains("call-skip"))
        assertTrue(groupJson.contains("skipped due to error"))
        // Real tool is NOT duplicated inside synthetic-only group array
        assertFalse(groupJson.contains("call-exec"))
    }

    @Test
    fun `parallel multi-tool replay preserves protocol state and image content`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-3", "turn-3") { updates += it }
        val assistant = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(
                cn.com.omnimind.bot.agent.AssistantToolCall(
                    id = "call-img",
                    function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("take_screenshot", "{}")
                ),
                cn.com.omnimind.bot.agent.AssistantToolCall(
                    id = "call-cmd",
                    function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("run_cmd", """{"cmd":"ls"}""")
                )
            )
        )
        val imageContent = kotlinx.serialization.json.buildJsonArray {
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("screenshot error: display lost"))
            })
            add(kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("image_url"))
                put("image_url", kotlinx.serialization.json.buildJsonObject {
                    put("url", JsonPrimitive("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="))
                })
            })
        }
        val imgResult = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "tool",
            toolCallId = "call-img",
            content = imageContent,
            protocolState = cn.com.omnimind.baselib.llm.ChatCompletionProtocolState(
                anthropic = cn.com.omnimind.baselib.llm.AnthropicMessageProtocolState(
                    toolResultIsError = true
                )
            )
        )
        val cmdResult = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "tool",
            toolCallId = "call-cmd",
            content = JsonPrimitive("file1.txt\nfile2.txt")
        )

        bridge.onToolCallStart("call-img", "take_screenshot", JsonObject(emptyMap()))
        bridge.onToolCallStart("call-cmd", "run_cmd", JsonObject(emptyMap()))
        bridge.onToolReplayReady("call-img", assistant, imgResult, isSynthetic = false)
        bridge.onToolCallComplete("call-img", "take_screenshot", ToolExecutionResult.Error("take_screenshot", "display lost"))
        bridge.onToolReplayReady("call-cmd", assistant, cmdResult, isSynthetic = false)
        bridge.onToolCallComplete("call-cmd", "run_cmd", ToolExecutionResult.ContextResult("run_cmd", summaryText = "file1.txt\nfile2.txt", previewJson = "{}", rawResultJson = "{}"))

        val completions = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().filter { it.status != null }
        assertEquals(2, completions.size)

        val imgCompletion = completions.first()
        assertEquals("call-img", imgCompletion.toolCallId.value)
        val imgRaw = imgCompletion.rawOutput as JsonObject
        val imgResultJson = imgRaw["modelToolResultMessageJson"]?.jsonPrimitive?.content.orEmpty()

        // Protocol state with tool_result_is_error and data:image/png must be intact in individual canonical result
        assertTrue(imgResultJson.contains("data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="))
        assertTrue(imgResultJson.contains("tool_result_is_error"))
        assertTrue(imgResultJson.contains("true"))
    }

    @Test
    fun `three round tool id collision in same turn isolates acp identities and preserves canonical provider ids`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-turn", "turn-col") { updates += it }

        // Round 1: provider returns raw id "call_shared"
        bridge.onThinkingStart()
        val assistant1 = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(cn.com.omnimind.bot.agent.AssistantToolCall(id = "call_shared", function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("run", "{}")))
        )
        val result1 = cn.com.omnimind.baselib.llm.ChatCompletionMessage(role = "tool", toolCallId = "call_shared", content = JsonPrimitive("round 1 done"))
        bridge.onToolCallStart("call_shared", "run", JsonObject(emptyMap()))
        bridge.onToolCallProgress("call_shared", "run", "progress 1", emptyMap())
        bridge.emitToolPending("call_shared", "title 1", "pending 1")
        bridge.onToolReplayReady("call_shared", assistant1, result1, isSynthetic = false)
        bridge.onToolCallComplete("call_shared", "run", ToolExecutionResult.ContextResult("run", summaryText = "round 1 done", previewJson = "{}", rawResultJson = "{}"))

        // Round 2: model reuses same raw id "call_shared"
        bridge.onThinkingStart()
        val assistant2 = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(cn.com.omnimind.bot.agent.AssistantToolCall(id = "call_shared", function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("run", "{}")))
        )
        val result2 = cn.com.omnimind.baselib.llm.ChatCompletionMessage(role = "tool", toolCallId = "call_shared", content = JsonPrimitive("round 2 done"))
        bridge.onToolCallStart("call_shared", "run", JsonObject(emptyMap()))
        bridge.onToolCallProgress("call_shared", "run", "progress 2", emptyMap())
        bridge.emitToolPending("call_shared", "title 2", "pending 2")
        bridge.onToolReplayReady("call_shared", assistant2, result2, isSynthetic = false)
        bridge.onToolCallComplete("call_shared", "run", ToolExecutionResult.ContextResult("run", summaryText = "round 2 done", previewJson = "{}", rawResultJson = "{}"))

        // Round 3: model returns raw id "call_shared-round-2" (attempted collision with generated id)
        bridge.onThinkingStart()
        val assistant3 = cn.com.omnimind.baselib.llm.ChatCompletionMessage(
            role = "assistant",
            toolCalls = listOf(cn.com.omnimind.bot.agent.AssistantToolCall(id = "call_shared-round-2", function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("run", "{}")))
        )
        val result3 = cn.com.omnimind.baselib.llm.ChatCompletionMessage(role = "tool", toolCallId = "call_shared-round-2", content = JsonPrimitive("round 3 done"))
        bridge.onToolCallStart("call_shared-round-2", "run", JsonObject(emptyMap()))
        bridge.onToolCallProgress("call_shared-round-2", "run", "progress 3", emptyMap())
        bridge.emitToolPending("call_shared-round-2", "title 3", "pending 3")
        bridge.onToolReplayReady("call_shared-round-2", assistant3, result3, isSynthetic = false)
        bridge.onToolCallComplete("call_shared-round-2", "run", ToolExecutionResult.ContextResult("run", summaryText = "round 3 done", previewJson = "{}", rawResultJson = "{}"))

        // Assert 3 distinct ToolCall start events
        val startEvents = updates.filterIsInstance<SessionUpdate.ToolCall>()
        assertEquals(3, startEvents.size)
        val acpId1 = startEvents[0].toolCallId.value
        val acpId2 = startEvents[1].toolCallId.value
        val acpId3 = startEvents[2].toolCallId.value

        assertEquals("call_shared", acpId1)
        assertEquals("call_shared-round-2", acpId2)
        assertEquals("call_shared-round-2-round-3", acpId3)

        // Assert 3 completions preserve original signed provider IDs
        val completions = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>().filter { it.status == ToolCallStatus.COMPLETED }
        assertEquals(3, completions.size)

        val rawOut1 = completions[0].rawOutput as JsonObject
        val rawOut2 = completions[1].rawOutput as JsonObject
        val rawOut3 = completions[2].rawOutput as JsonObject

        assertEquals("call_shared", rawOut1["modelToolCallId"]?.jsonPrimitive?.content)
        assertEquals("call_shared", rawOut2["modelToolCallId"]?.jsonPrimitive?.content)
        assertEquals("call_shared-round-2", rawOut3["modelToolCallId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `official ACP consumer retains actual tool name and progress action from rawOutput across input start and progress`() = runBlocking {
        for (toolName in listOf("vlm_task", "save_function")) {
            val updates = mutableListOf<SessionUpdate>()
            val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-1", "turn-1") { updates += it }

            val callId = "call-$toolName-1"
            val rawArgs = if (toolName == "vlm_task") {
                """{"action":"wait","duration_ms":10000}"""
            } else {
                """{"run_id":"gui-run-1","enhance":true}"""
            }
            val toolCall = cn.com.omnimind.bot.agent.AssistantToolCall(
                id = callId,
                function = cn.com.omnimind.bot.agent.AssistantToolCallFunction(toolName, rawArgs),
            )

            // 1. onToolCallInput emits initial ToolCall with PENDING
            bridge.onToolCallInput(toolCall, "builtin")
            val inputUpdate = updates.last() as SessionUpdate.ToolCall
            assertEquals(callId, inputUpdate.toolCallId.value)
            assertEquals(ToolCallStatus.PENDING, inputUpdate.status)
            assertEquals(JsonPrimitive(rawArgs), inputUpdate.rawInput)
            assertFalse((inputUpdate.rawInput as? JsonObject)?.containsKey("toolName") ?: false)

            val inputRawOutput = inputUpdate.rawOutput as? JsonObject
            assertNotNull("input rawOutput must be present", inputRawOutput)
            assertEquals(toolName, inputRawOutput?.get("toolName")?.jsonPrimitive?.content)

            val inputNotification = inputUpdate.toAcpSessionNotification("sess-1")
            assertNotNull(inputNotification)
            val inputConsumedRawOutput = inputNotification!!.update["rawOutput"] as? Map<*, *>
            assertEquals(toolName, inputConsumedRawOutput?.get("toolName"))
            assertEquals(callId, inputNotification.update["toolCallId"])

            // 2. onToolCallStart emits ToolCallUpdate with IN_PROGRESS
            val parsedArgs = Json.parseToJsonElement(rawArgs).jsonObject
            bridge.onToolCallStart(callId, toolName, parsedArgs, "builtin")
            val startUpdate = updates.last() as SessionUpdate.ToolCallUpdate
            assertEquals(callId, startUpdate.toolCallId.value)
            assertEquals(ToolCallStatus.IN_PROGRESS, startUpdate.status)
            assertEquals(parsedArgs, startUpdate.rawInput)
            assertFalse((startUpdate.rawInput as? JsonObject)?.containsKey("toolName") ?: false)

            val startRawOutput = startUpdate.rawOutput as? JsonObject
            assertNotNull("start rawOutput must be present", startRawOutput)
            assertEquals(toolName, startRawOutput?.get("toolName")?.jsonPrimitive?.content)

            val startNotification = startUpdate.toAcpSessionNotification("sess-1")
            assertNotNull(startNotification)
            val startConsumedRawOutput = startNotification!!.update["rawOutput"] as? Map<*, *>
            assertEquals(toolName, startConsumedRawOutput?.get("toolName"))
            assertEquals(callId, startNotification.update["toolCallId"])

            // 3. onToolCallProgress emits ToolCallUpdate with progress content, action in extras, and preserves actual toolName
            val progressExtras = if (toolName == "vlm_task") {
                mapOf(
                    "run_id" to "gui-run-1",
                    "display_id" to 47,
                    "action" to mapOf("tool" to "wait", "args" to mapOf("duration_ms" to 10000)),
                    "toolName" to "unauthorized_override",
                )
            } else {
                mapOf(
                    "run_id" to "gui-run-1",
                    "action" to mapOf("step" to "extract_form_function"),
                    "toolName" to "unauthorized_override",
                )
            }
            bridge.onToolCallProgress(callId, toolName, "waiting condition", progressExtras)
            val progressUpdate = updates.last() as SessionUpdate.ToolCallUpdate
            assertEquals(callId, progressUpdate.toolCallId.value)
            assertEquals(ToolCallStatus.IN_PROGRESS, progressUpdate.status)
            assertTrue(progressUpdate.rawInput == null || progressUpdate.rawInput == kotlinx.serialization.json.JsonNull)

            val progressRawOutput = progressUpdate.rawOutput as? JsonObject
            assertNotNull("progress rawOutput must be present", progressRawOutput)
            // Known tool name must not be overwritten by progress extras
            assertEquals(toolName, progressRawOutput?.get("toolName")?.jsonPrimitive?.content)
            assertEquals("gui-run-1", progressRawOutput?.get("run_id")?.jsonPrimitive?.content)
            assertNotNull(progressRawOutput?.get("action"))

            val progressNotification = progressUpdate.toAcpSessionNotification("sess-1")
            assertNotNull(progressNotification)
            val progressConsumedRawOutput = progressNotification!!.update["rawOutput"] as? Map<*, *>
            assertEquals(toolName, progressConsumedRawOutput?.get("toolName"))
            assertEquals(callId, progressNotification.update["toolCallId"])
            assertNotNull(progressConsumedRawOutput?.get("action"))
        }
    }

    @Test
    fun `direct tool start without prior input emits rawOutput carrying actual toolName`() = runBlocking {
        for (toolName in listOf("vlm_task", "save_function")) {
            val updates = mutableListOf<SessionUpdate>()
            val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-1", "turn-1") { updates += it }
            val callId = "call-direct-$toolName"
            val args = JsonObject(mapOf("run_id" to JsonPrimitive("gui-run-direct")))

            bridge.onToolCallStart(callId, toolName, args, "builtin")
            val startUpdate = updates.last() as SessionUpdate.ToolCall
            assertEquals(callId, startUpdate.toolCallId.value)
            assertEquals(ToolCallStatus.IN_PROGRESS, startUpdate.status)
            assertEquals(args, startUpdate.rawInput)

            val rawOutput = startUpdate.rawOutput as? JsonObject
            assertNotNull("direct start rawOutput must be present", rawOutput)
            assertEquals(toolName, rawOutput?.get("toolName")?.jsonPrimitive?.content)

            val notification = startUpdate.toAcpSessionNotification("sess-1")
            assertNotNull(notification)
            val consumedRawOutput = notification!!.update["rawOutput"] as? Map<*, *>
            assertEquals(toolName, consumedRawOutput?.get("toolName"))
            assertEquals(callId, notification.update["toolCallId"])
        }
    }

    @Test
    fun `streaming tool input chunks preserve actual toolName in rawOutput`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge(acpEventBridgeTestContext(), "sess-1", "turn-1") { updates += it }
        val callId = "call-stream-vlm"
        val chunk1 = cn.com.omnimind.bot.agent.AssistantToolCall(
            id = callId,
            function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("vlm_task", "{\"prompt\":\"w"),
        )
        val chunk2 = cn.com.omnimind.bot.agent.AssistantToolCall(
            id = callId,
            function = cn.com.omnimind.bot.agent.AssistantToolCallFunction("vlm_task", "{\"prompt\":\"wait\"}"),
        )

        bridge.onToolCallInput(chunk1, "builtin")
        val update1 = updates.last() as SessionUpdate.ToolCall
        assertEquals(callId, update1.toolCallId.value)
        val rawOut1 = update1.rawOutput as? JsonObject
        assertNotNull("chunk1 rawOutput must be present", rawOut1)
        assertEquals("vlm_task", rawOut1?.get("toolName")?.jsonPrimitive?.content)

        bridge.onToolCallInput(chunk2, "builtin")
        val update2 = updates.last() as SessionUpdate.ToolCallUpdate
        assertEquals(callId, update2.toolCallId.value)
        val rawOut2 = update2.rawOutput as? JsonObject
        assertNotNull("chunk2 rawOutput must be present", rawOut2)
        assertEquals("vlm_task", rawOut2?.get("toolName")?.jsonPrimitive?.content)
    }
}
