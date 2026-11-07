package cn.com.omnimind.bot.task.runtime

import android.app.Application
import android.content.Context
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class TaskRuntimeToolHandlerTest {

    private lateinit var context: Context
    private lateinit var handler: TaskRuntimeToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        handler = TaskRuntimeToolHandler(context)
        TaskRuntime.resetForTesting()
        TaskAuthWaitCoordinator.resetForTesting()
        context.getSharedPreferences("task_continuation_checkpoints", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        TaskRuntime.resetForTesting()
        TaskAuthWaitCoordinator.resetForTesting()
        context.getSharedPreferences("task_continuation_checkpoints", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun mockEnv(conversationId: Long?, sessionId: String?, turnId: String? = "turn-1"): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(conversationId)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn(turnId ?: "run-1")
        return env
    }


    @Test
    fun `task_control fails when conversationId or acpSessionId is absent in environment`() = runBlocking {
        val envNoConv = mockEnv(conversationId = null, sessionId = "sess-1")
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("task_control", "task_control", "")
        val toolCall = AssistantToolCall("call-0", "function", AssistantToolCallFunction("task_control", ""))

        val resultNoConv = handler.execute(
            toolCall = toolCall,
            args = buildJsonObject { put("action", "status") },
            runtimeDescriptor = descriptor,
            env = envNoConv,
            callback = callback,
            toolHandle = handle
        )
        assertTrue(resultNoConv is ToolExecutionResult.Error)

        val envNoSess = mockEnv(conversationId = 100L, sessionId = null)
        val resultNoSess = handler.execute(
            toolCall = toolCall,
            args = buildJsonObject { put("action", "status") },
            runtimeDescriptor = descriptor,
            env = envNoSess,
            callback = callback,
            toolHandle = handle
        )
        assertTrue(resultNoSess is ToolExecutionResult.Error)
    }

    @Test
    fun `checkpoint fails when acpTurnId is absent in environment`() = runBlocking {
        val envNoTurn = mockEnv(conversationId = 101L, sessionId = "sess-1", turnId = null)
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("task_control", "task_control", "")
        val toolCall = AssistantToolCall("call-chk", "function", AssistantToolCallFunction("task_control", ""))

        val checkpointArgs = buildJsonObject {
            put("action", "checkpoint")
            put("taskTitle", "Title")
            put("taskGoal", "Goal")
            put("currentProgress", "Progress")
        }

        val result = handler.execute(
            toolCall = toolCall,
            args = checkpointArgs,
            runtimeDescriptor = descriptor,
            env = envNoTurn,
            callback = callback,
            toolHandle = handle
        )
        assertTrue(result is ToolExecutionResult.Error)
    }

    @Test
    fun `checkpoint creates record bound to current env and status reads it back`() = runBlocking {
        val env = mockEnv(conversationId = 3001L, sessionId = "sess-3001", turnId = "turn-1")
        AcpAgentProfileStore(context).bindSession("sess-3001", "xiaowan")
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("task_control", "task_control", "")

        val checkpointArgs = buildJsonObject {
            put("action", "checkpoint")
            put("taskTitle", "Compile LSPosed Module")
            put("taskGoal", "Build hook for target")
            put("currentProgress", "Generated Java code")
            put("pendingSideEffects", buildJsonArray {
                add("Wrote file")
            })
            put("evidenceVerificationChecklist", buildJsonArray {
                add("Verify file exists")
            })
        }

        val toolCall = AssistantToolCall(
            id = "call-1",
            type = "function",
            function = AssistantToolCallFunction(name = "task_control", arguments = "")
        )

        val checkpointResult = handler.execute(
            toolCall = toolCall,
            args = checkpointArgs,
            runtimeDescriptor = descriptor,
            env = env,
            callback = callback,
            toolHandle = handle
        )

        assertTrue(checkpointResult is ToolExecutionResult.ContextResult)
        val contextRes = checkpointResult as ToolExecutionResult.ContextResult
        assertTrue(contextRes.success)
        assertTrue(contextRes.rawResultJson.contains("Compile LSPosed Module"))

        // Query status
        val statusArgs = buildJsonObject {
            put("action", "status")
        }
        val statusResult = handler.execute(
            toolCall = toolCall,
            args = statusArgs,
            runtimeDescriptor = descriptor,
            env = env,
            callback = callback,
            toolHandle = handle
        )
        assertTrue(statusResult is ToolExecutionResult.ContextResult)
        assertTrue((statusResult as ToolExecutionResult.ContextResult).rawResultJson.contains("sess-3001"))
    }

    @Test
    fun `clear rejects foreign checkpoint and clears own checkpoint`() = runBlocking {
        val env1 = mockEnv(conversationId = 3002L, sessionId = "sess-3002", turnId = "turn-1")
        val env2 = mockEnv(conversationId = 3003L, sessionId = "sess-3003", turnId = "turn-1")
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("task_control", "task_control", "")
        val toolCall = AssistantToolCall("call-2", "function", AssistantToolCallFunction("task_control", ""))

        // 检查点必须关联真实的 Agent 会话绑定。
        val cp = requireNotNull(TaskContinuationCoordinator.recordPendingReboot(
            context = context,
            acpAgentId = "xiaowan",
            conversationId = 3002L,
            sessionId = "sess-3002",
            turnId = "turn-1",
            taskTitle = "T1",
            taskGoal = "G1",
            currentProgress = "P1"
        ))

        // 其他会话不能清理检查点。
        val clearAttemptArgs = buildJsonObject {
            put("action", "clear")
            put("checkpointId", cp.checkpointId)
        }
        val foreignResult = handler.execute(
            toolCall = toolCall,
            args = clearAttemptArgs,
            runtimeDescriptor = descriptor,
            env = env2,
            callback = callback,
            toolHandle = handle
        )
        assertTrue(foreignResult is ToolExecutionResult.Error)
        assertNotNull(TaskContinuationCoordinator.readCheckpoint(context, cp.checkpointId))

        // 所属会话可以清理自己的检查点。
        val ownResult = handler.execute(
            toolCall = toolCall,
            args = clearAttemptArgs,
            runtimeDescriptor = descriptor,
            env = env1,
            callback = callback,
            toolHandle = handle
        )
        assertTrue(ownResult is ToolExecutionResult.ContextResult)
        assertNull(TaskContinuationCoordinator.readCheckpoint(context, cp.checkpointId))
    }

    @Test
    fun `manual_wait suspends and completes on notifyManualVerificationCompleted`() = runBlocking {
        val env = mockEnv(conversationId = 3004L, sessionId = "sess-3004", turnId = "turn-1")
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)
        val descriptor = AgentToolRegistry.RuntimeToolDescriptor("task_control", "task_control", "")
        val toolCall = AssistantToolCall("call-3", "function", AssistantToolCallFunction("task_control", ""))

        val waitArgs = buildJsonObject {
            put("action", "manual_wait")
            put("promptMessage", "Please solve puzzle in app")
        }

        var executionResult: ToolExecutionResult? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            executionResult = handler.execute(
                toolCall = toolCall,
                args = waitArgs,
                runtimeDescriptor = descriptor,
                env = env,
                callback = callback,
                toolHandle = handle
            )
        }

        assertTrue(TaskAuthWaitCoordinator.isWaitingForManualAuth(3004L))

        // Notify manual verification completed with conversationId
        TaskAuthWaitCoordinator.notifyManualVerificationCompleted(3004L)
        job.join()

        assertNotNull(executionResult)
        assertTrue(executionResult is ToolExecutionResult.ContextResult)
        assertTrue((executionResult as ToolExecutionResult.ContextResult).success)
        assertFalse(TaskAuthWaitCoordinator.isWaitingForManualAuth(3004L))
    }
}
