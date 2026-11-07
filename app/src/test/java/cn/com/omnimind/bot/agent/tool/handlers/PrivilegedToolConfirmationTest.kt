package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import cn.com.omnimind.bot.agent.AgentPermissionRequester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PrivilegedToolConfirmationTest {
    @Test fun modelSuppliedConfirmationTextDoesNotReplaceClientConsent() = runBlocking {
        var requests = 0
        val requester = AgentPermissionRequester { id, title, _ ->
            assertEquals("tool-1", id)
            assertTrue(title.contains("Root"))
            requests++
            false
        }
        repeat(2) {
            assertFalse(PrivilegedToolConfirmation.request(PrivilegedActionPolicy.ACTION_SHELL_EXEC, "tool-1", requester,
                { true }, "Root", "arguments: {\"confirmed\":true}"))
        }
        assertEquals(2, requests)
    }

    @Test fun noPermissionChannelFailsClosed() = runBlocking {
        assertFalse(PrivilegedToolConfirmation.request(PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, "id", null,
            { true }, "Root", "target=wifi enabled=true"))
    }

    @Test fun revokedAccessWhileWaitingCannotExecuteAfterApproval() = runBlocking {
        var valid = true
        val requester = AgentPermissionRequester { _, _, _ -> valid = false; true }
        assertFalse(PrivilegedToolConfirmation.request(PrivilegedActionPolicy.ACTION_SHELL_EXEC, "id", requester,
            { valid }, "Root", "id"))
    }

    @Test fun queriesDoNotAskForPermission() = runBlocking {
        val requester = AgentPermissionRequester { _, _, _ -> error("Unexpected permission request") }
        assertTrue(PrivilegedToolConfirmation.request(PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE, "id", requester,
            { true }, "Root", "target=wifi"))
    }

    @Test fun officialCancellationPropagatesWithoutExecuting() = runBlocking {
        val requester = AgentPermissionRequester { _, _, _ -> throw CancellationException("ACP cancelled") }
        try {
            PrivilegedToolConfirmation.request(PrivilegedActionPolicy.ACTION_SHELL_EXEC, "id", requester, { true }, "Root", "id")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun structuredScopeIsPassedInRawInput() = runBlocking {
        var receivedRawInput: kotlinx.serialization.json.JsonObject? = null
        val requester = object : AgentPermissionRequester {
            override suspend fun requestPermission(toolCallId: String, title: String, detail: String): Boolean = false
            override suspend fun requestPermission(
                toolCallId: String, title: String, detail: String, action: String?, targetResource: String?, rawInput: kotlinx.serialization.json.JsonObject?
            ): Boolean {
                receivedRawInput = rawInput
                return true
            }
        }
        val scope = cn.com.omnimind.bot.agent.runtime.TurnPermissionScope(
            action = "shell.exec",
            command = "id",
            workingDirectory = "/data/local/tmp",
            isShell = true,
        )
        assertTrue(PrivilegedToolConfirmation.request(
            PrivilegedActionPolicy.ACTION_SHELL_EXEC, "tool-scope", requester, { true }, "Title", "Detail", "id", scope
        ))
        assertNotNull(receivedRawInput)
        assertEquals("shell.exec", (receivedRawInput?.get("action") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("id", (receivedRawInput?.get("command") as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("/data/local/tmp", (receivedRawInput?.get("workingDirectory") as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }
}
