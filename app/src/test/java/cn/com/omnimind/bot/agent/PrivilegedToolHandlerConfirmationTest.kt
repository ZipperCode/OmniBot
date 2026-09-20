package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.privileged.PrivilegedAuthority
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import cn.com.omnimind.baselib.privileged.PrivilegedSource
import cn.com.omnimind.baselib.privileged.PrivilegedStatus
import cn.com.omnimind.baselib.privileged.PrivilegedStatusCode
import cn.com.omnimind.bot.agent.tool.handlers.PrivilegedToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.TerminalToolHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PrivilegedToolHandlerConfirmationTest {
    @Test
    fun `malicious confirmed action is still denied before dispatch`() = runBlocking {
        val fixture = fixture(allowed = false)

        fixture.execute("android_privileged_action", actionArgs())

        assertEquals(1, fixture.permissionRequests)
        assertEquals(0, fixture.gateway.actionCalls)
    }

    @Test
    fun `approved malicious confirmed action dispatches exactly once`() = runBlocking {
        val fixture = fixture(allowed = true)

        fixture.execute("android_privileged_action", actionArgs())

        assertEquals(1, fixture.permissionRequests)
        assertEquals(1, fixture.gateway.actionCalls)
        assertEquals("true", fixture.gateway.lastActionArguments["confirmed"])
    }

    @Test
    fun `malicious confirmed session start still requests approval and dispatches once`() = runBlocking {
        val fixture = fixture(allowed = true)

        fixture.execute("android_privileged_session_start", JsonObject(mapOf("confirmed" to JsonPrimitive(true))))

        assertEquals(1, fixture.permissionRequests)
        assertEquals(1, fixture.gateway.startCalls)
    }

    @Test
    fun `denied malicious confirmed session start does not dispatch`() = runBlocking {
        val fixture = fixture(allowed = false)

        fixture.execute("android_privileged_session_start", JsonObject(mapOf("confirmed" to JsonPrimitive(true))))

        assertEquals(1, fixture.permissionRequests)
        assertEquals(0, fixture.gateway.startCalls)
    }

    @Test
    fun `malicious confirmed session exec still requests approval and dispatches once`() = runBlocking {
        val fixture = fixture(allowed = true, ownSession = true)

        fixture.execute(
            "android_privileged_session_exec",
            JsonObject(mapOf("sessionId" to JsonPrimitive("session-1"), "command" to JsonPrimitive("id"), "confirmed" to JsonPrimitive(true)))
        )

        assertEquals(1, fixture.permissionRequests)
        assertEquals(1, fixture.gateway.execCalls)
    }

    @Test
    fun `denied malicious confirmed session exec does not dispatch`() = runBlocking {
        val fixture = fixture(allowed = false, ownSession = true)

        fixture.execute(
            "android_privileged_session_exec",
            JsonObject(mapOf("sessionId" to JsonPrimitive("session-1"), "command" to JsonPrimitive("id"), "confirmed" to JsonPrimitive(true)))
        )

        assertEquals(1, fixture.permissionRequests)
        assertEquals(0, fixture.gateway.execCalls)
    }

    private fun actionArgs() = JsonObject(
        mapOf(
            "action" to JsonPrimitive("shell.exec"),
            "arguments" to JsonObject(mapOf("command" to JsonPrimitive("id"), "confirmed" to JsonPrimitive(true)))
        )
    )

    private fun fixture(allowed: Boolean, ownSession: Boolean = false): Fixture {
        val helper = mock(SharedHelper::class.java)
        `when`(helper.localized(org.mockito.ArgumentMatchers.anyString())).thenAnswer { it.arguments[0] as String }
        `when`(helper.encodeLocalizedPayload(org.mockito.ArgumentMatchers.any())).thenReturn("{}")
        `when`(helper.parseEnvironmentMap(org.mockito.ArgumentMatchers.nullable(JsonObject::class.java))).thenReturn(emptyMap())
        `when`(
            helper.jsonObjectToStringMap(
                org.mockito.ArgumentMatchers.any(JsonObject::class.java) ?: JsonObject(emptyMap()),
                org.mockito.ArgumentMatchers.anySet<String>() ?: emptySet(),
            )
        ).thenAnswer { invocation ->
            val value = invocation.arguments[0] as JsonObject
            val excluded = invocation.arguments[1] as Set<*>
            value.filterKeys { it !in excluded }.mapValues { it.value.jsonPrimitive.content }
        }
        val workspaceManager = mock(AgentWorkspaceManager::class.java)
        val terminal = mock(TerminalToolHandler::class.java)
        if (ownSession) `when`(terminal.isOwnedTerminalSession("__privileged__:workspace", "session-1")).thenReturn(true)
        val gateway = FakeGateway()
        val handler = PrivilegedToolHandler(helper, workspaceManager, terminal, gateway)
        val workspace = mock(AgentWorkspaceDescriptor::class.java)
        `when`(workspace.id).thenReturn("workspace")
        var requests = 0
        val env = mock(AgentExecutionEnvironment::class.java)
        `when`(env.workspaceDescriptor).thenReturn(workspace)
        `when`(env.permissionRequester).thenReturn(AgentPermissionRequester { _, _, _ -> requests++; allowed })
        return Fixture(handler, gateway, env) { requests }
    }

    private class Fixture(
        val handler: PrivilegedToolHandler,
        val gateway: FakeGateway,
        val env: AgentExecutionEnvironment,
        private val requests: () -> Int,
    ) {
        val permissionRequests get() = requests()

        suspend fun execute(name: String, args: JsonObject) {
            handler.execute(
                AssistantToolCall("call-1", function = AssistantToolCallFunction(name, args.toString())),
                args,
                AgentToolRegistry.RuntimeToolDescriptor(name, name, "privileged"),
                env,
                mock(AgentCallback::class.java),
                NoOpAgentRunControl.beginToolExecution(name, "call-1"),
            )
        }
    }

    private class FakeGateway : PrivilegedToolHandler.PrivilegedGateway {
        var actionCalls = 0
        var startCalls = 0
        var execCalls = 0
        var lastActionArguments: Map<String, String> = emptyMap()
        override fun getStatus() = PrivilegedStatus(PrivilegedSource.SHIZUKU, PrivilegedStatusCode.GRANTED, PrivilegedAuthority.ROOT)
        override suspend fun executeAction(action: String, arguments: Map<String, String>, requiresConfirmation: Boolean): PrivilegedResult {
            actionCalls++; lastActionArguments = arguments; return result(action)
        }
        override suspend fun executeShell(command: String, timeoutSeconds: Int?, workingDirectory: String?, environment: Map<String, String>, confirmed: Boolean): PrivilegedResult {
            actionCalls++; lastActionArguments = mapOf("confirmed" to confirmed.toString()); return result("shell.exec")
        }
        override suspend fun startSession(sessionName: String?, workingDirectory: String?, environment: Map<String, String>, confirmed: Boolean): PrivilegedResult {
            startCalls++; return result("shell.session_start")
        }
        override suspend fun execSession(sessionId: String, command: String, timeoutSeconds: Int?, confirmed: Boolean): PrivilegedResult {
            execCalls++; return result("shell.session_exec")
        }
        override suspend fun readSession(sessionId: String) = result("shell.session_read")
        override suspend fun stopSession(sessionId: String) = result("shell.session_stop")
        private fun result(action: String) = PrivilegedResult("request", action, false, "command_failed", "test")
    }
}
