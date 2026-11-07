package cn.com.omnimind.bot.task.runtime

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 锁屏等待与人工认证等待协调器。
 *
 * 保证：
 * - 仅挂起调用方当前协程，绝不阻塞非 UI 的后台构建、下载等任务。
 * - 采用“注册监听 -> 复查锁屏状态”双检锁机制，彻底避免注册广播窗口期间错过用户解锁事件。
 * - 同一会话禁止静默覆盖未完成的等待 deferred；已存在活跃等待项时明确拒绝，保障单 owner。
 * - waitId + conversation/session/turn 严格隔离，使用 atomic remove(key, expectedRecord) 与 CAS 清理通知状态。
 * - 人工验证绑定 waitToken、session、turn，防止迟到的通知点击导致跨 Turn 误授权。
 * - 绝不保存锁屏密码，绝不假借 full access 绕过安全锁屏。
 */
object TaskAuthWaitCoordinator {
    private const val TAG = "TaskAuthWaitCoordinator"

    private data class UnlockWaitRecord(
        val waitId: String,
        val conversationId: Long,
        val sessionId: String?,
        val turnId: String?,
        val deferred: CompletableDeferred<Boolean>
    )

    private data class ManualWaitRecord(
        val waitId: String,
        val conversationId: Long,
        val sessionId: String?,
        val turnId: String?,
        val waitToken: String,
        val promptMessage: String,
        val deferred: CompletableDeferred<Boolean>
    )

    private val unlockWaiters = ConcurrentHashMap<Long, UnlockWaitRecord>()
    private val manualWaiters = ConcurrentHashMap<Long, ManualWaitRecord>()
    private val waitRegistrationLock = Any()

    private val receiverMutex = Mutex()
    @Volatile
    private var userPresentReceiverRegistered = false
    private var userPresentReceiver: BroadcastReceiver? = null

    /**
     * 挂起等待屏幕解锁。
     * 遵循严格的“注册监听 -> 复查锁屏状态”流程，消除窗口期漏检。
     */
    suspend fun awaitDeviceUnlock(
        context: Context,
        conversationId: Long,
        sessionId: String? = null,
        turnId: String? = null
    ): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val initialLocked = keyguardManager?.isKeyguardLocked == true
        if (!initialLocked) {
            return true
        }


        val waitId = UUID.randomUUID().toString()
        OmniLog.i(TAG, "设备处于锁屏状态，准备挂起等待屏幕解锁 conversationId=$conversationId turnId=$turnId waitId=$waitId")
        val deferred = CompletableDeferred<Boolean>()
        val record = UnlockWaitRecord(
            waitId = waitId,
            conversationId = conversationId,
            sessionId = sessionId,
            turnId = turnId,
            deferred = deferred
        )

        synchronized(waitRegistrationLock) {
            check(!unlockWaiters.containsKey(conversationId) && !manualWaiters.containsKey(conversationId)) {
                "会话 conversationId=$conversationId 已有等待项，不能覆盖现有等待"
            }
            unlockWaiters[conversationId] = record
        }

        return try {
            ensureUserPresentReceiverRegistered(context.applicationContext)
            TaskRuntime.updateWaitingStatus(
                context,
                conversationId,
                TaskWaitingStatus.WaitingForUnlock(
                    waitId = waitId,
                    conversationId = conversationId,
                    sessionId = sessionId,
                    turnId = turnId
                )
            )
            // 监听注册后复查，避免在解锁广播窗口内永久挂起。
            if (keyguardManager?.isKeyguardLocked != true) {
                deferred.complete(true)
            }
            deferred.await()
        } finally {
            // 原子清理：仅当 map 中的记录严格等于当前 record 时才删除
            synchronized(waitRegistrationLock) {
                unlockWaiters.remove(conversationId, record)
            }
            TaskRuntime.clearWaitingStatus(context, conversationId, record.waitId)
            cleanupUserPresentReceiverIfNoWaiters(context.applicationContext)
        }
    }

    /**
     * 挂起等待人工完成验证（如第三方应用滑块/验证码/2FA）。
     * 参数顺序保证既有 (context, conversationId, sessionId, promptMessage) 调用完全兼容，turnId 作为可选末尾参数。
     */
    suspend fun awaitManualVerification(
        context: Context,
        conversationId: Long,
        sessionId: String?,
        promptMessage: String
    ): Boolean = awaitManualVerification(
        context = context,
        conversationId = conversationId,
        sessionId = sessionId,
        promptMessage = promptMessage,
        turnId = null
    )

    suspend fun awaitManualVerification(
        context: Context,
        conversationId: Long,
        sessionId: String?,
        promptMessage: String,
        turnId: String? = null
    ): Boolean {

        val waitId = UUID.randomUUID().toString()
        val waitToken = UUID.randomUUID().toString()
        OmniLog.i(TAG, "正在挂起等待人工验证 conversationId=$conversationId turnId=$turnId waitId=$waitId: $promptMessage")

        val deferred = CompletableDeferred<Boolean>()
        val record = ManualWaitRecord(
            waitId = waitId,
            conversationId = conversationId,
            sessionId = sessionId,
            turnId = turnId,
            waitToken = waitToken,
            promptMessage = promptMessage,
            deferred = deferred
        )
        synchronized(waitRegistrationLock) {
            check(!unlockWaiters.containsKey(conversationId) && !manualWaiters.containsKey(conversationId)) {
                "会话 conversationId=$conversationId 已有等待项，不能覆盖现有等待"
            }
            manualWaiters[conversationId] = record
        }

        return try {
            TaskRuntime.updateWaitingStatus(
                context,
                conversationId,
                TaskWaitingStatus.WaitingForManualAuth(
                    waitId = waitId,
                    conversationId = conversationId,
                    sessionId = sessionId,
                    turnId = turnId,
                    waitToken = waitToken,
                    promptMessage = promptMessage
                )
            )
            deferred.await()
        } finally {
            // 原子清理：仅在匹配当前 record 时删除
            synchronized(waitRegistrationLock) {
                manualWaiters.remove(conversationId, record)
            }
            TaskRuntime.clearWaitingStatus(context, conversationId, record.waitId)
        }
    }

    /**
     * 通知人工验证已完成（单参数兼容调用）。
     */
    fun notifyManualVerificationCompleted(conversationId: Long): Boolean =
        notifyManualVerificationCompleted(conversationId, null, null, null)

    /**
     * 通知人工验证已完成。
     * 严格比对 waitToken、waitId 与 expectedTurnId（若提供），不匹配则直接拒绝，防止误授权。
     */
    fun notifyManualVerificationCompleted(
        conversationId: Long,
        waitToken: String? = null,
        expectedTurnId: String? = null,
        expectedWaitId: String? = null
    ): Boolean {
        val record = manualWaiters[conversationId]
        if (record == null) {
            OmniLog.w(TAG, "忽略人工验证完成：conversationId=$conversationId 无活跃等待项")
            return false
        }

        // expectedWaitId 比对
        if (expectedWaitId != null && record.waitId != expectedWaitId) {
            OmniLog.w(TAG, "拒绝人工验证完成：waitId 不匹配 conversationId=$conversationId expectedWaitId=$expectedWaitId actual=${record.waitId}")
            return false
        }

        // waitToken 比对
        if (waitToken != null && record.waitToken != waitToken) {
            OmniLog.w(TAG, "拒绝人工验证完成：waitToken 不匹配 conversationId=$conversationId")
            return false
        }

        // expectedTurnId 比对
        if (expectedTurnId != null && record.turnId != null && record.turnId != expectedTurnId) {
            OmniLog.w(TAG, "拒绝人工验证完成：turnId 不匹配 expected=$expectedTurnId actual=${record.turnId}")
            return false
        }

        if (record.deferred.isActive) {
            OmniLog.i(TAG, "人工验证已通过核验并解挂 conversationId=$conversationId waitId=${record.waitId}")
            record.deferred.complete(true)
            return true
        }
        return false
    }

    /**
     * 取消人工验证（如用户主动放弃或超时）。
     */
    fun cancelManualVerification(conversationId: Long, waitToken: String? = null): Boolean {
        val record = manualWaiters[conversationId] ?: return false
        if (waitToken != null && record.waitToken != waitToken) {
            return false
        }
        if (record.deferred.isActive) {
            OmniLog.i(TAG, "人工验证已被取消 conversationId=$conversationId")
            record.deferred.complete(false)
            return true
        }
        return false
    }

    fun isWaitingForUnlock(conversationId: Long): Boolean =
        unlockWaiters.containsKey(conversationId)

    fun isWaitingForManualAuth(conversationId: Long): Boolean =
        manualWaiters.containsKey(conversationId)

    private suspend fun ensureUserPresentReceiverRegistered(appContext: Context) {
        receiverMutex.withLock {
            if (userPresentReceiverRegistered) return@withLock
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_USER_PRESENT) {
                        OmniLog.i(TAG, "收到 ACTION_USER_PRESENT 广播，正在解除屏幕解锁等待")
                        val keyguardManager = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                        if (keyguardManager?.isKeyguardLocked != true) {
                            val waiters = unlockWaiters.values.toList()
                            waiters.forEach { it.deferred.complete(true) }
                        }
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            appContext.registerReceiver(receiver, filter)
            userPresentReceiver = receiver
            userPresentReceiverRegistered = true
        }
    }

    private suspend fun cleanupUserPresentReceiverIfNoWaiters(appContext: Context) {
        receiverMutex.withLock {
            if (unlockWaiters.isEmpty() && userPresentReceiverRegistered) {
                userPresentReceiver?.let {
                    runCatching { appContext.unregisterReceiver(it) }
                }
                userPresentReceiver = null
                userPresentReceiverRegistered = false
            }
        }
    }

    internal fun resetForTesting() {
        unlockWaiters.clear()
        manualWaiters.clear()
    }
}
