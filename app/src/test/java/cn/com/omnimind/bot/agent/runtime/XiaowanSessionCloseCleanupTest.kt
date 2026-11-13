package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.agent.AgentSession
import com.agentclientprotocol.model.SessionId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class XiaowanSessionCloseCleanupTest {
    @Test
    fun cancellingCloseStillReleasesSessionResources() = checkClose(cancelOwner = true)

    @Test
    fun concurrentCloseWaitsForTheSameCleanup() = checkClose(cancelOwner = false)

    @Test
    fun failedMcpCleanupCanBeRetriedWithoutAcknowledgingAClosedSession() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        val mcp = mock(XiaowanMcpSession::class.java)
        val failure = IllegalStateException("MCP cleanup failed")
        `when`(mcp.close()).thenAnswer { throw failure }.thenReturn(Unit)
        val closed = AtomicBoolean(false)
        var released = 0
        set("closed", closed)
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        set("sessionRunControl", StandardAgentRunControl())
        set("mcpSession", mcp)
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "retry-close" else SessionId("retry-close"))
        set("onClosed", { _: String -> released++; Unit })
        try { session.close(null); fail("Cleanup failure must reach caller") }
        catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
        assertTrue("Partially closed session must reject new prompts", closed.get())
        assertEquals(0, released)
        session.close(null)
        verify(mcp, times(2)).close()
        assertEquals(1, released)
        session.close(null)
        verify(mcp, times(2)).close()
        assertEquals(1, released)
    }

    @Test
    fun mcpCleanupAttemptsAllConnectionsAndReportsFailure() = runBlocking {
        val session = mock(XiaowanMcpSession::class.java, CALLS_REAL_METHODS)
        val first = mock(XiaowanMcpServerConnection::class.java)
        val second = mock(XiaowanMcpServerConnection::class.java)
        val failure = IllegalStateException("first cleanup failed")
        `when`(first.close()).thenAnswer { throw failure }.thenReturn(Unit)
        XiaowanMcpSession::class.java.getDeclaredField("connections").apply {
            isAccessible = true; set(session, listOf(first, second))
        }
        try { session.close(); fail("MCP cleanup must not hide failure") }
        catch (error: IllegalStateException) { assertEquals(failure.message, error.message) }
        verify(first).close()
        verify(second).close()
        session.close()
        verify(first, times(2)).close()
        verify(second, times(2)).close()
    }

    private fun checkClose(cancelOwner: Boolean) = runBlocking {
        // Exercise the actual private session close/cancel methods without constructing
        // unrelated Android model/tool dependencies. No alternate lifecycle is modeled.
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        val mcp = mock(XiaowanMcpSession::class.java)
        val closed = AtomicBoolean(false)
        var released = 0
        set("closed", closed)
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        set("sessionRunControl", StandardAgentRunControl())
        set("mcpSession", mcp)
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "close-test" else SessionId("close-test"))
        set("onClosed", { _: String -> released++; Unit })
        val stopping = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val prompt = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { stopping.complete(Unit); finish.await() } }
        }
        set("activePromptJob", prompt)
        val closing = launch { session.close(null) }
        var duplicate: Job? = null
        try {
            withTimeout(5000) { stopping.await() }
            if (cancelOwner) {
                closing.cancel()
            } else {
                duplicate = launch(start = CoroutineStart.UNDISPATCHED) { session.close(null) }
                assertFalse("Duplicate close returned before resource cleanup", duplicate.isCompleted)
            }
            finish.complete(Unit)
            withTimeout(5000) { closing.join(); duplicate?.join(); prompt.join() }
            assertTrue(closed.get())
            verify(mcp, times(1)).close()
            assertEquals(1, released)
            session.close(null)
            verify(mcp, times(1)).close()
            assertEquals(1, released)
        } finally {
            finish.complete(Unit)
            closing.cancelAndJoin()
            duplicate?.cancelAndJoin()
            prompt.cancelAndJoin()
        }
    }

    @Test
    fun sessionCancelIssuesPromptCancellationBeforeStopAllAndDoesNotHarmNextPrompt() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }

        val runControl = StandardAgentRunControl()
        set("sessionRunControl", runControl)
        val closed = AtomicBoolean(false)
        set("closed", closed)
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "cancel-test" else SessionId("cancel-test"))

        val prompt1Started = CompletableDeferred<Unit>()
        val prompt1CancelledBeforeStopAction = CompletableDeferred<Boolean>()
        val prompt1Finished = CompletableDeferred<Unit>()
        val prompt1 = launch(start = CoroutineStart.UNDISPATCHED) {
            prompt1Started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    prompt1Finished.complete(Unit)
                }
            }
        }
        prompt1Started.await()
        set("activePromptJob", prompt1)

        val handle = runControl.beginToolExecution("vlm_task", "call-cancel-1")
        val stopActionExecuted = CompletableDeferred<Unit>()
        handle.bindStopAction {
            delay(20)
            prompt1CancelledBeforeStopAction.complete(prompt1.isCancelled)
            stopActionExecuted.complete(Unit)
        }

        session.cancel()

        assertTrue("stopAll 执行时 promptJob 必须已接收取消信号", prompt1CancelledBeforeStopAction.await())
        assertTrue("stopAction 必须已执行完毕", stopActionExecuted.isCompleted)
        assertTrue("Prompt1 必须已完成", prompt1Finished.isCompleted)
        assertTrue("Prompt1 必须已是 cancelled", prompt1.isCancelled)

        // 模拟后续新 prompt 启动：验证不会被旧 cancel/join 清理误伤
        val prompt2Started = CompletableDeferred<Unit>()
        val prompt2Finished = CompletableDeferred<Unit>()
        val prompt2 = launch(start = CoroutineStart.UNDISPATCHED) {
            prompt2Started.complete(Unit)
            delay(30)
            prompt2Finished.complete(Unit)
        }
        prompt2Started.await()
        set("activePromptJob", prompt2)

        prompt2Finished.await()
        assertTrue("后续新 prompt 正常执行完成，未被前次 cancel 误伤", prompt2Finished.isCompleted)
        assertFalse("后续新 prompt 未被取消", prompt2.isCancelled)
        prompt2.join()
    }

    @Test
    fun sessionCloseReusesCancelOrderingAndReleasesAllResources() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }

        val runControl = StandardAgentRunControl()
        set("sessionRunControl", runControl)
        val mcp = mock(XiaowanMcpSession::class.java)
        set("mcpSession", mcp)
        val closed = AtomicBoolean(false)
        var released = 0
        set("closed", closed)
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "close-test" else SessionId("close-test"))
        set("onClosed", { _: String -> released++; Unit })

        val promptStarted = CompletableDeferred<Unit>()
        val promptCancelledBeforeStopAction = CompletableDeferred<Boolean>()
        val prompt = launch(start = CoroutineStart.UNDISPATCHED) {
            promptStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(10)
                }
            }
        }
        promptStarted.await()
        set("activePromptJob", prompt)

        val handle = runControl.beginToolExecution("vlm_task", "call-close")
        handle.bindStopAction {
            promptCancelledBeforeStopAction.complete(prompt.isCancelled)
        }

        session.close(null)

        assertTrue(closed.get())
        assertTrue("close 过程中 promptJob 必须先于 stopAll 被取消", promptCancelledBeforeStopAction.await())
        assertTrue("Prompt 必须已取消并结束", prompt.isCompleted && prompt.isCancelled)
        verify(mcp, times(1)).close()
        assertEquals(1, released)
    }

    @Test
    fun stopActionExecutesEvenWhenToolHandleCompleteRunsBeforeStopActionInvocation() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }

        val runControl = StandardAgentRunControl()
        set("sessionRunControl", runControl)
        set("closed", AtomicBoolean(false))
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "complete-before-stop" else SessionId("complete-before-stop"))

        val stopActionInvoked = CompletableDeferred<Unit>()
        val handle = runControl.beginToolExecution("vlm_task", "call-race-1")
        handle.bindStopAction {
            delay(10)
            stopActionInvoked.complete(Unit)
        }

        val promptStarted = CompletableDeferred<Unit>()
        val prompt = launch(start = CoroutineStart.UNDISPATCHED) {
            promptStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                // 当 prompt 被 cancel 时，模拟工具 finally 立即调用 complete()
                // 这会将 handle 内部字段置空，并从 activeHandles 移除
                handle.complete()
            }
        }
        promptStarted.await()
        set("activePromptJob", prompt)

        session.cancel()

        // 验证：即使 handle.complete() 在 stopAction 执行前已经将 handle 内部置空，
        // 快照捕获的 stopAction 仍然被确定性执行！
        assertTrue("即使 toolHandle.complete() 先运行，stopAction 必须依然被确定性调用", stopActionInvoked.isCompleted)
        prompt.join()
    }

    @Test
    fun nextPromptToolIsNotHarmedWhilePriorCleanupIsSuspending() = runBlocking {
        @Suppress("UNCHECKED_CAST")
        val type = Class.forName("cn.com.omnimind.bot.agent.runtime.XiaowanAgentSession") as Class<AgentSession>
        val session = mock(type, CALLS_REAL_METHODS)
        fun set(name: String, value: Any?) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }

        val runControl = StandardAgentRunControl()
        set("sessionRunControl", runControl)
        set("closed", AtomicBoolean(false))
        set("closeMutex", kotlinx.coroutines.sync.Mutex())
        val idField = type.getDeclaredField("sessionId").apply { isAccessible = true }
        idField.set(session, if (idField.type == String::class.java) "next-prompt-tool" else SessionId("next-prompt-tool"))

        val cleanupSuspended = CompletableDeferred<Unit>()
        val allowCleanupFinish = CompletableDeferred<Unit>()
        val handle1 = runControl.beginToolExecution("vlm_task", "call-prompt1")
        handle1.bindStopAction {
            cleanupSuspended.complete(Unit)
            allowCleanupFinish.await()
        }

        val prompt1Started = CompletableDeferred<Unit>()
        val prompt1 = launch(start = CoroutineStart.UNDISPATCHED) {
            prompt1Started.complete(Unit)
            awaitCancellation()
        }
        prompt1Started.await()
        set("activePromptJob", prompt1)

        val cancelJob = launch {
            session.cancel()
        }

        // 等待旧 cancel 进入其 stopAction 挂起阶段
        cleanupSuspended.await()

        // 在旧 cancel 依然挂起清理时，新 prompt 启动并注册新工具句柄
        var prompt2ToolStopInvoked = false
        val prompt2ToolJob = Job()
        val handle2 = runControl.beginToolExecution("vlm_task", "call-prompt2")
        handle2.bindExecutionJob(prompt2ToolJob)
        handle2.bindStopAction {
            prompt2ToolStopInvoked = true
        }

        // 释放旧 cancel 的 stopAction，等待旧 cancel 彻底结束
        allowCleanupFinish.complete(Unit)
        cancelJob.join()

        // 验证：新 prompt 的工具绝未被旧 cancel 误伤
        assertFalse("新 prompt 的工具句柄绝不能被旧 cancel 的 manualStop 标记", handle2.isManualStopRequested())
        assertFalse("新 prompt 的工具 job 绝不能被旧 cancel 取消", prompt2ToolJob.isCancelled)
        assertFalse("新 prompt 的 stopAction 绝不能被旧 cancel 误执行", prompt2ToolStopInvoked)
    }

    @Test
    fun snapshotStopActionHasNoSideEffectsUntilClosureIsExecuted() = runBlocking {
        val runControl = StandardAgentRunControl()
        val handle = runControl.beginToolExecution("vlm_task", "call-snapshot-no-side-effect")
        var stopActionInvoked = false
        val toolJob = Job()
        handle.bindExecutionJob(toolJob)
        handle.bindStopAction {
            stopActionInvoked = true
        }

        // 1. 同步取 snapshot
        val stopActions = runControl.snapshotStopActions()
        assertEquals(1, stopActions.size)

        // 2. 确定性断言：快照仅提取闭包，未执行前 handle 绝无停止副作用
        assertFalse("提取快照后 handle 绝不能被置为 manualStop", handle.isManualStopRequested())
        assertTrue("提取快照后 toolJob 仍应处于活跃状态", toolJob.isActive)
        assertFalse("提取快照后 stopAction 绝不能被提前执行", stopActionInvoked)
        // 关键行为断言：消费者调用 throwIfStopRequested() 绝不抛异常
        handle.throwIfStopRequested()

        // 3. 模拟 owner 取消后执行闭包
        val stopClosure = stopActions.single()
        stopClosure.invoke()

        // 4. 执行闭包后，才真正产生 manualStop、触发 stopAction 与取消 toolJob
        assertTrue("闭包执行后 handle 必须被置为 manualStop", handle.isManualStopRequested())
        assertTrue("闭包执行后 toolJob 必须被取消", toolJob.isCancelled)
        assertTrue("闭包执行后 stopAction 必须被调用", stopActionInvoked)
        try {
            handle.throwIfStopRequested()
            fail("闭包执行后调用 throwIfStopRequested 必须抛出异常")
        } catch (e: CancellationException) {
            assertTrue("必须抛出 ManualToolStopCancellationException", e is cn.com.omnimind.bot.agent.ManualToolStopCancellationException)
        }
    }
}
