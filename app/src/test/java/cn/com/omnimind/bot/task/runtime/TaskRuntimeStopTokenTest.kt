package cn.com.omnimind.bot.task.runtime

import android.app.Application
import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class TaskRuntimeStopTokenTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TaskRuntime.resetForTesting()
    }

    @After
    fun tearDown() {
        TaskRuntime.resetForTesting()
    }

    @Test
    fun `stopToken validation accepts matching token and rejects mismatch`() {
        val taskId = "agent-turn:session-1:turn-1"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            conversationId = 101L,
            sessionId = "session-1",
            turnId = "turn-1",
            title = "Compiling module"
        )

        val activeTask = TaskRuntime.getActiveTask(taskId)
        assertNotNull(activeTask)
        val token = activeTask!!.stopToken
        assertTrue(token.isNotBlank())

        // Matching token passes
        assertTrue(TaskRuntime.validateStopToken(taskId, token))

        // Mismatched token fails
        assertFalse(TaskRuntime.validateStopToken(taskId, "wrong-token"))

        // Unknown task fails
        assertFalse(TaskRuntime.validateStopToken("unknown-task", token))
    }

    @Test
    fun `late stop token from finished task does not affect subsequent new task`() {
        val taskId = "agent-turn:session-1:turn-1"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = "session-1",
            turnId = "turn-1"
        )
        val task1Token = TaskRuntime.getActiveTask(taskId)!!.stopToken

        // Turn 1 finishes
        TaskRuntime.finish(context, taskId)
        assertFalse(TaskRuntime.validateStopToken(taskId, task1Token))

        // Turn 2 starts on the same session
        val task2Id = "agent-turn:session-1:turn-2"
        TaskRuntime.start(
            context = context,
            taskId = task2Id,
            sessionId = "session-1",
            turnId = "turn-2"
        )
        val task2Token = TaskRuntime.getActiveTask(task2Id)!!.stopToken
        assertNotEquals(task1Token, task2Token)

        // Late stop with Turn 1's token is rejected on both task IDs
        assertFalse(TaskRuntime.validateStopToken(taskId, task1Token))
        assertFalse(TaskRuntime.validateStopToken(task2Id, task1Token))

        // Only Turn 2's token is valid
        assertTrue(TaskRuntime.validateStopToken(task2Id, task2Token))
    }

    @Test
    fun `dynamic progress update and waiting status reflect correctly`() {
        val taskId = "agent-turn:session-2:turn-1"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            conversationId = 202L,
            sessionId = "session-2",
            turnId = "turn-1"
        )

        TaskRuntime.updateProgress(
            context = context,
            taskId = taskId,
            statusText = "Compiling 3/5",
            progressCurrent = 3,
            progressMax = 5
        )

        val updated = TaskRuntime.getActiveTask(taskId)
        assertNotNull(updated)
        assertEquals("Compiling 3/5", updated!!.statusText)
        assertEquals(3, updated.progressCurrent)
        assertEquals(5, updated.progressMax)

        // Waiting status update
        val waitingStatus = TaskWaitingStatus.WaitingForManualAuth(
            conversationId = 202L,
            sessionId = "session-2",
            promptMessage = "Please solve captcha"
        )
        TaskRuntime.updateWaitingStatus(context, 202L, waitingStatus)

        assertEquals(waitingStatus, TaskRuntime.getWaitingStatus(202L))
        assertEquals(waitingStatus, TaskRuntime.getActiveTask(taskId)?.waitingStatus)

        // 只允许当前等待项的 waitId 清理状态。
        TaskRuntime.clearWaitingStatus(context, 202L, waitingStatus.waitId)
        assertNull(TaskRuntime.getWaitingStatus(202L))
        assertNull(TaskRuntime.getActiveTask(taskId)?.waitingStatus)
    }

    @Test
    fun `service with empty tasks stops immediately upon ACTION_STOP`() {
        val controller = Robolectric.buildService(TaskRuntimeService::class.java).create()
        val service = controller.get()
        try {
            val stopIntent = Intent(service, TaskRuntimeService::class.java).apply {
                action = TaskRuntimeService.ACTION_STOP
            }
            service.onStartCommand(stopIntent, 0, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `service does not stop prematurely upon ACTION_STOP while active task lease exists`() {
        val controller = Robolectric.buildService(TaskRuntimeService::class.java).create()
        val service = controller.get()
        try {
            TaskRuntime.start(context, "active-lease-contract")
            assertTrue(TaskRuntime.hasActiveTasks())

            val stopIntent = Intent(service, TaskRuntimeService::class.java).apply {
                action = TaskRuntimeService.ACTION_STOP
            }
            service.onStartCommand(stopIntent, 0, 1)
            // 存在活跃任务租约时，前台宿主服务绝不提前 stopSelf
            assertFalse(shadowOf(service).isStoppedBySelf)

            TaskRuntime.finish(context, "active-lease-contract")
            assertFalse(TaskRuntime.hasActiveTasks())

            service.onStartCommand(stopIntent, 0, 2)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
