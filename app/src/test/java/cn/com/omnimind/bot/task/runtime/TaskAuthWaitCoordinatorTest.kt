package cn.com.omnimind.bot.task.runtime

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class TaskAuthWaitCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TaskAuthWaitCoordinator.resetForTesting()
        TaskRuntime.resetForTesting()
    }

    @After
    fun tearDown() {
        TaskAuthWaitCoordinator.resetForTesting()
        TaskRuntime.resetForTesting()
    }

    @Test
    fun `awaitDeviceUnlock returns true immediately if screen is not locked`() = runBlocking {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val shadowKeyguard = shadowOf(keyguardManager)
        shadowKeyguard.setKeyguardLocked(false)

        val result = TaskAuthWaitCoordinator.awaitDeviceUnlock(
            context = context,
            conversationId = 123L,
            sessionId = "session-123"
        )

        assertTrue(result)
        assertFalse(TaskAuthWaitCoordinator.isWaitingForUnlock(123L))
        assertNull(TaskRuntime.getWaitingStatus(123L))
    }

    @Test
    fun `awaitManualVerification suspends until completed and cleans up state`() = runBlocking {
        val conversationId = 456L
        var completedResult: Boolean? = null

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            completedResult = TaskAuthWaitCoordinator.awaitManualVerification(
                context = context,
                conversationId = conversationId,
                sessionId = "session-456",
                turnId = "turn-1",
                promptMessage = "Please solve slider captcha in app"
            )
        }

        assertTrue(TaskAuthWaitCoordinator.isWaitingForManualAuth(conversationId))
        val status = TaskRuntime.getWaitingStatus(conversationId)
        assertTrue(status is TaskWaitingStatus.WaitingForManualAuth)
        val waiting = status as TaskWaitingStatus.WaitingForManualAuth
        assertEquals("Please solve slider captcha in app", waiting.promptMessage)

        // Mismatched waitToken is rejected
        val wrongTokenRejected = TaskAuthWaitCoordinator.notifyManualVerificationCompleted(
            conversationId = conversationId,
            waitToken = "wrong-token",
            expectedTurnId = "turn-1"
        )
        assertFalse(wrongTokenRejected)
        assertTrue(TaskAuthWaitCoordinator.isWaitingForManualAuth(conversationId))

        // Mismatched expectedTurnId is rejected
        val wrongTurnRejected = TaskAuthWaitCoordinator.notifyManualVerificationCompleted(
            conversationId = conversationId,
            waitToken = waiting.waitToken,
            expectedTurnId = "turn-999"
        )
        assertFalse(wrongTurnRejected)
        assertTrue(TaskAuthWaitCoordinator.isWaitingForManualAuth(conversationId))

        // Correct waitToken and turnId succeeds
        val accepted = TaskAuthWaitCoordinator.notifyManualVerificationCompleted(
            conversationId = conversationId,
            waitToken = waiting.waitToken,
            expectedTurnId = "turn-1",
            expectedWaitId = waiting.waitId
        )
        assertTrue(accepted)
        job.join()

        assertEquals(true, completedResult)
        assertFalse(TaskAuthWaitCoordinator.isWaitingForManualAuth(conversationId))
        assertNull(TaskRuntime.getWaitingStatus(conversationId))
    }

    @Test
    fun `cancellation of awaitManualVerification cleans up state and does not leak`() = runBlocking {
        val conversationId = 789L

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            TaskAuthWaitCoordinator.awaitManualVerification(
                context = context,
                conversationId = conversationId,
                sessionId = "session-789",
                turnId = "turn-2",
                promptMessage = "Needs biometric"
            )
        }

        assertTrue(TaskAuthWaitCoordinator.isWaitingForManualAuth(conversationId))

        job.cancel(CancellationException("Task cancelled by user"))
        job.join()

        assertFalse(TaskAuthWaitCoordinator.isWaitingForManualAuth(conversationId))
        assertNull(TaskRuntime.getWaitingStatus(conversationId))
    }

    @Test
    fun `overlapping waits admit one owner without replacing its notification`() = runBlocking {
        supervisorScope {
            val conversationId = 912L
            val admitted = CompletableDeferred<TaskWaitingStatus.WaitingForManualAuth>()
            val contenders = (1..2).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        TaskAuthWaitCoordinator.awaitManualVerification(
                            context, conversationId, "session-912", "请完成认证", "turn-$index"
                        )
                    }.also { result ->
                        if (result.exceptionOrNull() is IllegalStateException) {
                            admitted.complete(TaskRuntime.getWaitingStatus(conversationId) as TaskWaitingStatus.WaitingForManualAuth)
                        }
                    }
                }
            }
            val waiting = admitted.await()
            assertTrue(TaskAuthWaitCoordinator.notifyManualVerificationCompleted(
                conversationId, waiting.waitToken, waiting.turnId, waiting.waitId
            ))
            val results = contenders.awaitAll()
            assertEquals(1, results.count { it.getOrNull() == true })
            assertEquals(1, results.count { it.exceptionOrNull() is IllegalStateException })
            assertNull(TaskRuntime.getWaitingStatus(conversationId))
        }
    }

    @Test
    fun `lockscreen wait cannot replace active manual verification`() = runBlocking {
        val conversationId = 913L
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            TaskAuthWaitCoordinator.awaitManualVerification(
                context, conversationId, "session-913", "请完成认证", "turn-1"
            )
        }
        try {
            val waiting = TaskRuntime.getWaitingStatus(conversationId) as TaskWaitingStatus.WaitingForManualAuth
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            shadowOf(keyguardManager).setKeyguardLocked(true)
            val result = runCatching {
                TaskAuthWaitCoordinator.awaitDeviceUnlock(context, conversationId, "session-913", "turn-1")
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(waiting, TaskRuntime.getWaitingStatus(conversationId))
            assertTrue(TaskAuthWaitCoordinator.notifyManualVerificationCompleted(
                conversationId, waiting.waitToken, waiting.turnId, waiting.waitId
            ))
            job.join()
            assertNull(TaskRuntime.getWaitingStatus(conversationId))
        } finally {
            job.cancel()
            job.join()
        }
    }
}
