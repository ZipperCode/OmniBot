package cn.com.omnimind.bot.task.runtime

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.UserManager
import cn.com.omnimind.baselib.database.AgentSessionBinding
import cn.com.omnimind.baselib.database.DatabaseHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class TaskContinuationCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(false)
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager != null) {
            shadowOf(userManager).setUserUnlocked(true)
        }
        context.getSharedPreferences("task_continuation_checkpoints", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("acp_task_terminal_journal", Context.MODE_PRIVATE).edit().clear().commit()
        TaskContinuationNotifications.persistPendingHint(context, false)
        TaskContinuationNotifications.cancel(context)
        TaskRuntime.resetForTesting()
        TaskContinuationCoordinator.setDispatcherForTesting(null)
        TaskContinuationCoordinator.setBootCompletedForTesting(true)
        TaskContinuationCoordinator.setCurrentBootIdForTesting(null)
    }

    @After
    fun tearDown() {
        TaskContinuationCoordinator.setDispatcherForTesting(null)
        TaskContinuationCoordinator.setBootCompletedForTesting(null)
        TaskContinuationCoordinator.setCurrentBootIdForTesting(null)
        TaskRuntime.resetForTesting()
        TaskContinuationNotifications.persistPendingHint(context, false)
        TaskContinuationNotifications.cancel(context)
        context.getSharedPreferences("task_continuation_checkpoints", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("acp_task_terminal_journal", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test
    fun `recordPendingReboot creates reserved checkpoint with facts`() {
        val checkpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1001L,
                sessionId = "session-1001",
                turnId = "turn-1",
                acpAgentId = "xiaowan",
                taskTitle = "LSPosed Hook Implementation",
                taskGoal = "Hook target app to disable telemetry",
                currentProgress = "Compiled APK, installed module, enabled scope",
                pendingSideEffects = listOf("Installed APK /data/local/tmp/mod.apk", "Rebooted device"),
                evidenceVerificationChecklist = listOf("Check if module is loaded", "Check lsposed log")
            )
        )

        assertEquals(ContinuationState.RESERVED, checkpoint.state)
        assertEquals(1001L, checkpoint.conversationId)
        assertEquals("session-1001", checkpoint.sessionId)
        assertEquals("turn-1", checkpoint.turnId)
        assertEquals("xiaowan", checkpoint.acpAgentId)

        val readBack = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, checkpoint.checkpointId))
        assertEquals(checkpoint.checkpointId, readBack.checkpointId)
        assertEquals(ContinuationState.RESERVED, readBack.state)
    }

    @Test
    fun `resumePendingContinuation validates binding and marks ADMITTED`() = runBlocking {
        DatabaseHelper.init(context)
        DatabaseHelper.upsertAgentSessionBinding(
            AgentSessionBinding(
                conversationId = 1002L,
                threadId = "session-1002",
                cwd = "/data/local/tmp",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        val checkpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1002L,
                sessionId = "session-1002",
                turnId = "turn-2",
                acpAgentId = "xiaowan",
                taskTitle = "LSPosed Hook Verification",
                taskGoal = "Verify hook output",
                currentProgress = "Installed module"
            )
        )

        val mockDispatcher = ContinuationDispatcher { _, _, _ ->
            Result.success(
                mapOf(
                    "status" to "completed",
                    "completed" to true,
                    "stopReason" to "end_turn",
                    "turnId" to "turn-new-1002"
                )
            )
        }
        TaskContinuationCoordinator.setDispatcherForTesting(mockDispatcher)

        val result = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertTrue(result is ContinuationResumeResult.Resumed)
        val resumed = result as ContinuationResumeResult.Resumed
        assertEquals(checkpoint.checkpointId, resumed.checkpointId)

        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = checkpoint,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(checkpoint),
            dispatcher = mockDispatcher
        )

        val updated = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, checkpoint.checkpointId))
        assertEquals(ContinuationState.FINISHED, updated.state)
        assertEquals("turn-new-1002", updated.continuationTurnId)
        assertEquals("turn-2", updated.turnId)
    }

    @Test
    fun `resumePendingContinuation deduplicates and skips if task is already active`() = runBlocking {
        DatabaseHelper.init(context)
        DatabaseHelper.upsertAgentSessionBinding(
            AgentSessionBinding(
                conversationId = 1003L,
                threadId = "session-1003",
                cwd = "/data/local/tmp",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        val checkpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1003L,
                sessionId = "session-1003",
                turnId = "turn-3",
                acpAgentId = "xiaowan",
                taskTitle = "Background task",
                taskGoal = "Compile module",
                currentProgress = "Started build"
            )
        )

        TaskRuntime.start(
            context = context,
            taskId = "agent-turn:session-1003:turn-4",
            conversationId = 1003L,
            sessionId = "session-1003",
            turnId = "turn-4"
        )

        var dispatchCalled = false
        TaskContinuationCoordinator.setDispatcherForTesting { _, _, _ ->
            dispatchCalled = true
            Result.success(emptyMap())
        }

        val result = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertTrue(result is ContinuationResumeResult.SkippedAlreadyRunning)
        assertFalse(dispatchCalled)

        val readBack = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, checkpoint.checkpointId))
        assertEquals(ContinuationState.RESERVED, readBack.state)
    }

    @Test
    fun `preboot turn journal entry is not overwritten by recovery`() = runBlocking {
        DatabaseHelper.init(context)
        DatabaseHelper.upsertAgentSessionBinding(
            AgentSessionBinding(
                conversationId = 1005L,
                threadId = "session-1005",
                cwd = "/data/local/tmp",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        val prebootTurnId = "turn-preboot-1005"
        val prebootRequestId = "req-preboot-1005"
        assertTrue(
            AcpTaskTerminalJournal.recordAdmission(
                context = context,
                agentId = "xiaowan",
                sessionId = "session-1005",
                turnId = prebootTurnId,
                requestId = prebootRequestId
            )
        )
        assertTrue(
            AcpTaskTerminalJournal.recordTerminal(
                context = context,
                agentId = "xiaowan",
                sessionId = "session-1005",
                turnId = prebootTurnId,
                status = "completed"
            )
        )

        val checkpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1005L,
                sessionId = "session-1005",
                turnId = prebootTurnId,
                acpAgentId = "xiaowan",
                taskTitle = "Preserve journal entry",
                taskGoal = "Ensure preboot turn not overwritten",
                currentProgress = "Completed before reboot"
            )
        )

        val newTurnId = "turn-new-continuation-1005"
        val mockDispatcher = ContinuationDispatcher { _, _, _ ->
            Result.success(
                mapOf(
                    "status" to "completed",
                    "completed" to true,
                    "turnId" to newTurnId
                )
            )
        }
        TaskContinuationCoordinator.setDispatcherForTesting(mockDispatcher)

        val resumeResult = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertTrue(resumeResult is ContinuationResumeResult.Resumed)

        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = checkpoint,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(checkpoint),
            dispatcher = mockDispatcher
        )

        // Verify preboot turn journal was untouched
        val prebootLookup = AcpTaskTerminalJournal.find(context, "xiaowan", "session-1005", prebootTurnId)
        assertTrue(prebootLookup is AcpTerminalLookupResult.TerminalFound)
        val entry = (prebootLookup as AcpTerminalLookupResult.TerminalFound).entry
        assertEquals(prebootRequestId, entry.requestId)
        assertEquals("completed", entry.terminalStatus)
        assertEquals(prebootTurnId, entry.turnId)

        // Verify checkpoint holds the new continuation turn id without falling back to original turn
        val updated = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, checkpoint.checkpointId))
        assertEquals(ContinuationState.FINISHED, updated.state)
        assertEquals(newTurnId, updated.continuationTurnId)
        assertEquals(prebootTurnId, updated.turnId)
    }

    @Test
    fun `unknown dispatch outcome preserves ADMITTED and is never blindly replayed`() = runBlocking {
        DatabaseHelper.init(context)
        DatabaseHelper.upsertAgentSessionBinding(
            AgentSessionBinding(
                conversationId = 1006L,
                threadId = "session-1006",
                cwd = "/data/local/tmp",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        val checkpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1006L,
                sessionId = "session-1006",
                turnId = "turn-6",
                acpAgentId = "xiaowan",
                taskTitle = "Unknown outcome test",
                taskGoal = "Preserve ADMITTED",
                currentProgress = "Testing"
            )
        )

        val resumeResult = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertTrue(resumeResult is ContinuationResumeResult.Resumed)

        val unknownDispatcher = ContinuationDispatcher { _, _, _ ->
            Result.success(mapOf("status" to "unknown_state", "turnId" to "turn-unknown-1006"))
        }
        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = checkpoint,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(checkpoint),
            dispatcher = unknownDispatcher
        )

        val readBack = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, checkpoint.checkpointId))
        assertEquals(ContinuationState.ADMITTED, readBack.state)
        assertEquals("turn-unknown-1006", readBack.continuationTurnId)

        val resumable = TaskContinuationStore.getResumableCheckpoints(context)
        assertFalse(resumable.any { it.checkpointId == checkpoint.checkpointId })

        val nextResume = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertTrue(nextResume is ContinuationResumeResult.NoPendingContinuation)
    }

    @Test
    fun `cancelled and error outcomes result in FAILED not FINISHED`() = runBlocking {
        val errorCheckpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1007L,
                sessionId = "session-1007",
                turnId = "turn-7",
                acpAgentId = "xiaowan",
                taskTitle = "Error outcome test",
                taskGoal = "Test failure",
                currentProgress = "Testing"
            )
        )

        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = errorCheckpoint,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(errorCheckpoint),
            dispatcher = { _, _, _ ->
                Result.success(mapOf("status" to "error", "error" to "Module execution failed"))
            }
        )

        val errorUpdated = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, errorCheckpoint.checkpointId))
        assertEquals(ContinuationState.FAILED, errorUpdated.state)

        val cancelCheckpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1008L,
                sessionId = "session-1008",
                turnId = "turn-8",
                acpAgentId = "xiaowan",
                taskTitle = "Cancel outcome test",
                taskGoal = "Test cancel",
                currentProgress = "Testing"
            )
        )

        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = cancelCheckpoint,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(cancelCheckpoint),
            dispatcher = { _, _, _ ->
                Result.success(mapOf("status" to "cancelled", "stopReason" to "cancelled"))
            }
        )

        val cancelUpdated = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, cancelCheckpoint.checkpointId))
        assertEquals(ContinuationState.FAILED, cancelUpdated.state)
    }

    @Test
    fun `official success only concludes matching checkpoint and markFinished requires evidence`() = runBlocking {
        val cpA = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1009L,
                sessionId = "session-1009",
                turnId = "turn-9",
                acpAgentId = "xiaowan",
                taskTitle = "Checkpoint A",
                taskGoal = "Goal A",
                currentProgress = "Progress A"
            )
        )
        val cpB = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1010L,
                sessionId = "session-1010",
                turnId = "turn-10",
                acpAgentId = "xiaowan",
                taskTitle = "Checkpoint B",
                taskGoal = "Goal B",
                currentProgress = "Progress B"
            )
        )

        // Calling markFinished without official terminal evidence fails
        assertFalse(TaskContinuationCoordinator.markFinished(context, cpA.checkpointId))
        val readBackA = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, cpA.checkpointId))
        assertEquals(ContinuationState.RESERVED, readBackA.state)

        // Dispatch outcome with mismatched checkpointId does not conclude cpA
        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = cpA,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(cpA),
            dispatcher = { _, _, _ ->
                Result.success(
                    mapOf(
                        "status" to "completed",
                        "completed" to true,
                        "checkpointId" to cpB.checkpointId,
                        "turnId" to "turn-mismatch"
                    )
                )
            }
        )

        val afterMismatch = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, cpA.checkpointId))
        assertEquals(ContinuationState.ADMITTED, afterMismatch.state)

        // Dispatch with matching checkpointId concludes cpA
        TaskContinuationCoordinator.executeContinuationTurn(
            context = context,
            checkpoint = cpA,
            prompt = TaskContinuationCoordinator.buildEvidenceCheckPrompt(cpA),
            dispatcher = { _, _, _ ->
                Result.success(
                    mapOf(
                        "status" to "completed",
                        "completed" to true,
                        "checkpointId" to cpA.checkpointId,
                        "turnId" to "turn-match"
                    )
                )
            }
        )

        val afterMatch = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, cpA.checkpointId))
        assertEquals(ContinuationState.FINISHED, afterMatch.state)
        assertEquals("turn-match", afterMatch.continuationTurnId)

        // cpB remains RESERVED
        val cpBReadBack = checkNotNull(TaskContinuationCoordinator.readCheckpoint(context, cpB.checkpointId))
        assertEquals(ContinuationState.RESERVED, cpBReadBack.state)
    }

    @Test
    fun `markAdmitted transitions from RESERVED once only`() {
        val checkpoint = checkNotNull(
            TaskContinuationCoordinator.recordPendingReboot(
                context = context,
                conversationId = 1011L,
                sessionId = "session-1011",
                turnId = "turn-11",
                acpAgentId = "xiaowan",
                taskTitle = "Single admission test",
                taskGoal = "Test atomic transition",
                currentProgress = "Testing"
            )
        )

        val firstAdmit = TaskContinuationStore.markAdmitted(context, checkpoint.checkpointId, "req-1")
        assertNotNull(firstAdmit)
        val admittedCp = checkNotNull(firstAdmit)
        assertEquals(ContinuationState.ADMITTED, admittedCp.state)
        assertEquals("req-1", admittedCp.continuationRequestId)
        assertEquals(1, admittedCp.attemptCount)

        // Subsequent call on already ADMITTED checkpoint returns null
        val secondAdmit = TaskContinuationStore.markAdmitted(context, checkpoint.checkpointId, "req-2")
        assertNull(secondAdmit)

        // Checkpoint in store is still the first admitted one
        val current = checkNotNull(TaskContinuationStore.getCheckpoint(context, checkpoint.checkpointId))
        assertEquals(ContinuationState.ADMITTED, current.state)
        assertEquals("req-1", current.continuationRequestId)
        assertEquals(1, current.attemptCount)
    }

    @Test
    fun `corrupted storage is preserved and never overwritten by subsequent save`() {
        val prefs = context.getSharedPreferences("task_continuation_checkpoints", Context.MODE_PRIVATE)
        val brokenJson = "{\"cp-broken\": { invalid json structure ... "
        prefs.edit().putString("checkpoints_json", brokenJson).commit()

        val newCheckpoint = TaskContinuationCheckpoint(
            checkpointId = "cp-safe-1",
            conversationId = 1012L,
            sessionId = "session-1012",
            turnId = "turn-12",
            acpAgentId = "xiaowan",
            taskTitle = "Corrupt guard test",
            taskGoal = "Guard against overwrite",
            currentProgress = "Testing"
        )

        // Mutations must fail and refuse to overwrite
        assertFalse(TaskContinuationStore.saveCheckpoint(context, newCheckpoint))
        assertFalse(TaskContinuationStore.updateCheckpointState(context, "cp-safe-1", ContinuationState.FINISHED))
        assertNull(TaskContinuationStore.markAdmitted(context, "cp-safe-1", "req-corrupt"))
        assertFalse(TaskContinuationStore.deleteCheckpoint(context, "cp-safe-1"))
        assertNull(TaskContinuationStore.getCheckpoint(context, "cp-safe-1"))
        assertTrue(TaskContinuationStore.getAllCheckpoints(context).isEmpty())

        // The raw broken JSON is preserved verbatim
        assertEquals(brokenJson, prefs.getString("checkpoints_json", null))
    }

    @Test
    fun `isSysBootCompleted returns false when unready without defaulting to true`() {
        TaskContinuationCoordinator.setBootCompletedForTesting(false)
        assertFalse(TaskContinuationCoordinator.isSysBootCompleted())

        TaskContinuationCoordinator.setBootCompletedForTesting(true)
        assertTrue(TaskContinuationCoordinator.isSysBootCompleted())
    }

    @Test
    fun `locked with pending shows notification without runtime dispatch`() = runBlocking {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)

        val cp = TaskContinuationCheckpoint(
            checkpointId = "cp-locked-pending-1",
            conversationId = 2001L,
            sessionId = "session-2001",
            turnId = "turn-2001",
            acpAgentId = "xiaowan",
            taskTitle = "Pending reboot task",
            taskGoal = "Wait for user unlock",
            currentProgress = "Rebooted",
            prebootBootId = "old-boot-id-1234",
            state = ContinuationState.RESERVED
        )
        assertTrue(TaskContinuationStore.saveCheckpoint(context, cp))

        var dispatchCalled = false
        TaskContinuationCoordinator.setDispatcherForTesting { _, _, _ ->
            dispatchCalled = true
            Result.success(mapOf("status" to "completed"))
        }

        val result = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertEquals(ContinuationResumeResult.DeviceLocked, result)
        assertFalse(dispatchCalled)

        // 持久通知提示必须同步到设备保护区。
        assertTrue(TaskContinuationNotifications.readPendingHint(context))

        // 锁屏时通知可见，但不能派发执行。
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val shadowNm = shadowOf(nm)
        val notif = shadowNm.getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK)
        assertNotNull(notif)
    }
    @Test
    fun `locking before reboot preserves durable hint without premature notification`() = runBlocking {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)
        TaskContinuationCoordinator.setCurrentBootIdForTesting("same-boot")
        val checkpoint = TaskContinuationCheckpoint(
            checkpointId = "cp-before-reboot-lock",
            conversationId = 2010L,
            sessionId = "session-before-reboot-lock",
            turnId = "turn-before-reboot-lock",
            acpAgentId = "xiaowan",
            taskTitle = "重启前锁屏",
            taskGoal = "保留下一次开机的等待通知",
            currentProgress = "检查点已刷盘，尚未重启",
            prebootBootId = "same-boot",
        )
        assertTrue(TaskContinuationStore.saveCheckpoint(context, checkpoint))
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        TaskContinuationCoordinator.resumePendingContinuationIfAny(context)

        assertTrue(TaskContinuationNotifications.readPendingHint(context))
        assertNull(shadowOf(notificationManager).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))
        assertEquals(0, TaskContinuationStore.getCheckpoint(context, checkpoint.checkpointId)?.attemptCount)

        TaskContinuationCoordinator.setCurrentBootIdForTesting("next-boot")
        TaskContinuationCoordinator.resumePendingContinuationIfAny(context)

        assertNotNull(shadowOf(notificationManager).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))
        assertTrue(TaskContinuationNotifications.readPendingHint(context))
        assertEquals(ContinuationState.RESERVED, TaskContinuationStore.getCheckpoint(context, checkpoint.checkpointId)?.state)
    }

    @Test
    fun `locked without pending does not show notification and cancels existing`() = runBlocking {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        shadowOf(keyguardManager).setKeyguardLocked(true)

        // 模拟旧通知与已过期的通知提示。
        TaskContinuationNotifications.showWaitingForUnlock(context)
        TaskContinuationNotifications.persistPendingHint(context, true)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))

        var dispatchCalled = false
        TaskContinuationCoordinator.setDispatcherForTesting { _, _, _ ->
            dispatchCalled = true
            Result.success(emptyMap())
        }

        val result = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertEquals(ContinuationResumeResult.DeviceLocked, result)
        assertFalse(dispatchCalled)

        // 没有待恢复项时清理提示和通知。
        assertFalse(TaskContinuationNotifications.readPendingHint(context))
        assertNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))
    }

    @Test
    fun `direct boot locked checks DP hint and does not read CE`() = runBlocking {
        val userManager = context.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(false)
        assertFalse(TaskContinuationStore.isStorageAccessible(context))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var credentialStorageReads = 0
        val lockedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                credentialStorageReads++
                error("首次解锁前禁止读取凭据保护存储")
            }
        }

        // 仅凭设备保护区提示发通知，不能访问凭据保护区。
        TaskContinuationNotifications.persistPendingHint(context, true)
        val resTrue = TaskContinuationCoordinator.resumePendingContinuationIfAny(lockedContext)
        assertEquals(ContinuationResumeResult.DirectBootLocked, resTrue)
        assertNotNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))

        assertEquals(0, credentialStorageReads)

        // 无待恢复提示时不发通知。
        TaskContinuationNotifications.cancel(context)
        TaskContinuationNotifications.persistPendingHint(context, false)
        val resFalse = TaskContinuationCoordinator.resumePendingContinuationIfAny(lockedContext)
        assertEquals(ContinuationResumeResult.DirectBootLocked, resFalse)
        assertNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))
        assertEquals(0, credentialStorageReads)
    }

    @Test
    fun `unlock achieves single-pass admission and cancels waiting notification`() = runBlocking {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        DatabaseHelper.init(context)
        DatabaseHelper.upsertAgentSessionBinding(
            AgentSessionBinding(
                conversationId = 2002L,
                threadId = "session-2002",
                cwd = "/data/local/tmp",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        val cp = TaskContinuationCheckpoint(
            checkpointId = "cp-unlock-1",
            conversationId = 2002L,
            sessionId = "session-2002",
            turnId = "turn-2002",
            acpAgentId = "xiaowan",
            taskTitle = "Unlock single-pass task",
            taskGoal = "Admit once and dismiss notification",
            currentProgress = "Ready",
            prebootBootId = "old-boot-pre",
            state = ContinuationState.RESERVED
        )
        assertTrue(TaskContinuationStore.saveCheckpoint(context, cp))

        // 锁屏时显示等待通知。
        shadowOf(keyguardManager).setKeyguardLocked(true)
        val lockedRes = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertEquals(ContinuationResumeResult.DeviceLocked, lockedRes)
        assertNotNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))

        var dispatchCount = 0
        val firstDispatch = CompletableDeferred<Unit>()
        TaskContinuationCoordinator.setDispatcherForTesting { _, _, _ ->
            dispatchCount++
            firstDispatch.complete(Unit)
            Result.success(mapOf("status" to "in_progress"))
        }

        // 用户手动解锁后只准入一次。
        shadowOf(keyguardManager).setKeyguardLocked(false)
        val unlockedRes = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertTrue(unlockedRes is ContinuationResumeResult.Resumed)
        assertEquals("cp-unlock-1", (unlockedRes as ContinuationResumeResult.Resumed).checkpointId)
        withTimeout(5_000) { firstDispatch.await() }

        // 准入后撤销等待通知。
        assertNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))

        // 已准入检查点不退回待派发状态。
        val admittedCp = checkNotNull(TaskContinuationStore.getCheckpoint(context, "cp-unlock-1"))
        assertEquals(ContinuationState.ADMITTED, admittedCp.state)

        // 后续恢复只对账，不能再次派发。
        val secondRes = TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
        assertEquals(ContinuationResumeResult.NoPendingContinuation, secondRes)
        assertEquals(1, dispatchCount)
    }

    @Test
    fun `terminal state clears DP flag and cancels notification`() {
        val cp = TaskContinuationCheckpoint(
            checkpointId = "cp-terminal-dp-1",
            conversationId = 2003L,
            sessionId = "session-2003",
            turnId = "turn-2003",
            acpAgentId = "xiaowan",
            taskTitle = "Terminal DP flag clear",
            taskGoal = "Clear flag on completion",
            currentProgress = "Progressing",
            state = ContinuationState.RESERVED
        )
        assertTrue(TaskContinuationStore.saveCheckpoint(context, cp))
        assertTrue(TaskContinuationNotifications.readPendingHint(context))

        // 正式完成后清理通知投影。
        assertTrue(
            TaskContinuationStore.updateCheckpointState(
                context,
                "cp-terminal-dp-1",
                ContinuationState.FINISHED
            )
        )
        assertFalse(TaskContinuationNotifications.readPendingHint(context))

        // 删除待恢复项也必须清理通知投影。
        val cp2 = cp.copy(checkpointId = "cp-terminal-dp-2")
        assertTrue(TaskContinuationStore.saveCheckpoint(context, cp2))
        assertTrue(TaskContinuationNotifications.readPendingHint(context))

        assertTrue(TaskContinuationStore.deleteCheckpoint(context, "cp-terminal-dp-2"))
        assertFalse(TaskContinuationNotifications.readPendingHint(context))
    }

    @Test
    fun `boot receiver handles LOCKED_BOOT_COMPLETED reading only DP hint`() {
        val receiver = TaskContinuationBootReceiver()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 存在提示时，开机接收器发布通知。
        TaskContinuationNotifications.persistPendingHint(context, true)
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNotNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))

        // 无提示时不发布通知。
        TaskContinuationNotifications.cancel(context)
        TaskContinuationNotifications.persistPendingHint(context, false)
        receiver.onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))

        // 不接受非锁定开机广播。
        TaskContinuationNotifications.persistPendingHint(context, true)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(nm).getNotification(TaskContinuationNotifications.NOTIFICATION_ID_WAITING_FOR_UNLOCK))
    }

    @Test
    fun `reconcileAdmittedCheckpoint treats end_turn journal as FINISHED`() = runBlocking {
        val prebootTurnId = "turn-reconcile-end-turn"
        val checkpointId = "cp-reconcile-end-turn"
        val sessionId = "session-reconcile-end-turn"
        val requestId = "req-reconcile-end-turn"

        val cp = TaskContinuationCheckpoint(
            checkpointId = checkpointId,
            conversationId = 2004L,
            sessionId = sessionId,
            turnId = prebootTurnId,
            acpAgentId = "xiaowan",
            taskTitle = "Reconcile end_turn test",
            taskGoal = "Verify end_turn maps to FINISHED",
            currentProgress = "Done turn",
            continuationRequestId = requestId,
            state = ContinuationState.ADMITTED
        )
        assertTrue(TaskContinuationStore.saveCheckpoint(context, cp))

        // 保留官方 journal 的 end_turn 原值，不发明另一种终态。
        assertTrue(
            AcpTaskTerminalJournal.recordAdmission(
                context = context,
                agentId = "xiaowan",
                sessionId = sessionId,
                turnId = "turn-continuation-1",
                requestId = requestId,
                checkpointId = checkpointId
            )
        )
        assertTrue(
            AcpTaskTerminalJournal.recordTerminal(
                context = context,
                agentId = "xiaowan",
                sessionId = sessionId,
                turnId = "turn-continuation-1",
                status = "end_turn"
            )
        )

        var dispatchCalled = false
        TaskContinuationCoordinator.setDispatcherForTesting { _, _, _ ->
            dispatchCalled = true
            Result.success(emptyMap())
        }

        val result = TaskContinuationCoordinator.reconcileAdmittedCheckpoint(context, checkpointId)
        assertTrue(result is ContinuationResumeResult.Resumed)
        assertFalse(dispatchCalled)

        val updated = checkNotNull(TaskContinuationStore.getCheckpoint(context, checkpointId))
        assertEquals(ContinuationState.FINISHED, updated.state)
        assertEquals("turn-continuation-1", updated.continuationTurnId)

        // 无未决检查点后清理设备保护区提示。
        assertFalse(TaskContinuationNotifications.readPendingHint(context))
    }
}
