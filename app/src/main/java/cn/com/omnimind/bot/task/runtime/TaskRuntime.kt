package cn.com.omnimind.bot.task.runtime

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.uikit.loader.cat.DraggableBallInstance
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 长任务等待状态。
 * 每个等待态均持有唯一的 waitId，用于 CAS 精确状态清理，杜绝并发旧协程误删新等待项。
 */
sealed interface TaskWaitingStatus {
    val waitId: String
    val conversationId: Long
    val sessionId: String?
    val turnId: String?

    data class WaitingForUnlock(
        override val waitId: String = UUID.randomUUID().toString(),
        override val conversationId: Long,
        override val sessionId: String? = null,
        override val turnId: String? = null,
        val message: String = "设备已锁屏，请解锁屏幕以继续执行任务"
    ) : TaskWaitingStatus

    data class WaitingForManualAuth(
        override val waitId: String = UUID.randomUUID().toString(),
        override val conversationId: Long,
        override val sessionId: String? = null,
        override val turnId: String? = null,
        val waitToken: String = UUID.randomUUID().toString(),
        val promptMessage: String = "需要人工完成验证以继续执行"
    ) : TaskWaitingStatus
}

/**
 * 活跃长任务的执行租约元数据。
 */
data class TaskExecutionInfo(
    val taskId: String,
    val sessionId: String? = null,
    val turnId: String? = null,
    val conversationId: Long? = null,
    val taskTitle: String? = null,
    val statusText: String? = null,
    val progressCurrent: Int? = null,
    val progressMax: Int? = null,
    val stopToken: String = UUID.randomUUID().toString(),
    val startedAt: Long = System.currentTimeMillis(),
    val waitingStatus: TaskWaitingStatus? = null
)

/**
 * 长任务生命周期的稳定入口。
 *
 * ACP 协议持有 Agent 的 Turn 生命周期。此类仅在 Turn 运行期间持有 Android 前台服务执行租约，
 * 并负责驱动通用常驻悬浮球进度与通知状态更新。
 */
object TaskRuntime {
    private const val TAG = "TaskRuntime"

    private val activeTasks = ConcurrentHashMap<String, TaskExecutionInfo>()
    private val waitingStatusByConversation = ConcurrentHashMap<Long, TaskWaitingStatus>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeOverlayTaskId: String? = null

    /**
     * 基础启动租约（兼容已有调用方）。
     */
    fun start(context: Context, taskId: String): Boolean {
        return start(
            context = context,
            taskId = taskId,
            conversationId = null,
            sessionId = null,
            turnId = null,
            title = null,
            statusText = null
        )
    }

    /**
     * 丰富元数据启动租约（关联会话、Turn、标题与初始状态）。
     */
    fun start(
        context: Context,
        taskId: String,
        conversationId: Long? = null,
        sessionId: String? = null,
        turnId: String? = null,
        title: String? = null,
        statusText: String? = null
    ): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            OmniLog.w(TAG, "忽略空 taskId 的前台租约启动请求")
            return false
        }

        val inferredSessionId = sessionId ?: extractSessionId(normalizedTaskId)
        val inferredTurnId = turnId ?: extractTurnId(normalizedTaskId)

        val executionInfo = TaskExecutionInfo(
            taskId = normalizedTaskId,
            sessionId = inferredSessionId,
            turnId = inferredTurnId,
            conversationId = conversationId,
            taskTitle = title,
            statusText = statusText ?: "正在后台执行用户发起的任务",
            stopToken = UUID.randomUUID().toString(),
            startedAt = System.currentTimeMillis()
        )

        activeTasks[normalizedTaskId] = executionInfo

        val started = sendServiceCommand(context, TaskRuntimeService.ACTION_START)
        if (!started) {
            OmniLog.w(TAG, "前台服务启动受限或被系统拒绝，透明回退为直发通知")
            postFallbackNotification(context)
        }

        updateOverlayHint(context)
        return true
    }

    /**
     * 释放任务租约。
     */
    fun finish(context: Context, taskId: String): Boolean {
        val normalizedTaskId = taskId.trim()
        if (normalizedTaskId.isEmpty()) {
            OmniLog.w(TAG, "忽略空 taskId 的前台租约释放请求")
            return false
        }

        val removed = activeTasks.remove(normalizedTaskId)
        removed?.conversationId?.let { convId ->
            if (activeTasks.values.none { it.conversationId == convId }) {
                waitingStatusByConversation.remove(convId)
            }
        }

        // 清理常驻悬浮球提示：契约要求“仅当前 task 展示时清理，不能清别人完成提示”
        if (activeOverlayTaskId == normalizedTaskId) {
            activeOverlayTaskId = null
            val remainingPrimary = activeTasks.values.firstOrNull { it.waitingStatus != null }
                ?: activeTasks.values.lastOrNull()
            if (remainingPrimary != null) {
                updateOverlayHint(context)
            } else {
                mainHandler.post {
                    runCatching {
                        DraggableBallInstance.clearTaskCompletionHint()
                    }
                }
            }
        }

        if (activeTasks.isNotEmpty()) {
            sendServiceCommand(context, TaskRuntimeService.ACTION_UPDATE)
            return true
        }

        cancelFallbackNotification(context)

        return sendServiceCommand(context, TaskRuntimeService.ACTION_STOP)
    }

    /**
     * 更新指定任务的动态进度与状态文本。
     */
    fun updateProgress(
        context: Context,
        taskId: String,
        statusText: String,
        progressCurrent: Int? = null,
        progressMax: Int? = null
    ): Boolean {
        val normalizedTaskId = taskId.trim()
        val current = activeTasks[normalizedTaskId] ?: return false
        val updated = current.copy(
            statusText = statusText,
            progressCurrent = progressCurrent,
            progressMax = progressMax
        )
        activeTasks[normalizedTaskId] = updated
        notifyStateChanged(context)
        updateOverlayHint(context)
        return true
    }

    /**
     * 根据 sessionId 更新所有匹配任务的进度与状态。
     */
    fun updateProgressBySession(
        context: Context,
        sessionId: String,
        statusText: String,
        progressCurrent: Int? = null,
        progressMax: Int? = null
    ): Boolean {
        val normalizedSessionId = sessionId.trim()
        var updatedAny = false
        activeTasks.forEach { (taskId, info) ->
            if (info.sessionId == normalizedSessionId) {
                activeTasks[taskId] = info.copy(
                    statusText = statusText,
                    progressCurrent = progressCurrent,
                    progressMax = progressMax
                )
                updatedAny = true
            }
        }
        if (updatedAny) {
            notifyStateChanged(context)
            updateOverlayHint(context)
        }
        return updatedAny
    }

    /**
     * 更新或清除指定会话的等待状态（锁屏/人工验证）。
     */
    fun updateWaitingStatus(
        context: Context,
        conversationId: Long,
        waitingStatus: TaskWaitingStatus?
    ): Boolean {
        if (waitingStatus != null) {
            waitingStatusByConversation[conversationId] = waitingStatus
        } else {
            waitingStatusByConversation.remove(conversationId)
        }

        activeTasks.keys.forEach { taskId ->
            activeTasks.computeIfPresent(taskId) { _, info ->
                if (info.conversationId == conversationId) info.copy(waitingStatus = waitingStatus) else info
            }
        }

        notifyStateChanged(context)
        updateOverlayHint(context)
        return true
    }

    /**
     * CAS 精确清理等待状态：仅当当前等待态的 waitId 严格匹配 expectedWaitId 时才清除。
     * 杜绝新旧等待项交叠时旧协程误删新等待项。
     */
    fun clearWaitingStatus(
        context: Context,
        conversationId: Long,
        expectedWaitId: String
    ): Boolean {
        val current = waitingStatusByConversation[conversationId]
        if (current != null && current.waitId == expectedWaitId &&
            waitingStatusByConversation.remove(conversationId, current)) {
            activeTasks.keys.forEach { taskId ->
                activeTasks.computeIfPresent(taskId) { _, info ->
                    if (info.conversationId == conversationId && info.waitingStatus?.waitId == expectedWaitId) {
                        info.copy(waitingStatus = null)
                    } else info
                }
            }
            notifyStateChanged(context)
            updateOverlayHint(context)
            return true
        }
        return false
    }

    fun getWaitingStatus(conversationId: Long): TaskWaitingStatus? =
        waitingStatusByConversation[conversationId]

    fun getActiveTask(taskId: String): TaskExecutionInfo? =
        activeTasks[taskId.trim()]

    fun findActiveTaskBySession(sessionId: String): TaskExecutionInfo? {
        val norm = sessionId.trim()
        return activeTasks.values.firstOrNull { it.sessionId == norm }
    }

    fun findActiveTaskByConversation(conversationId: Long): TaskExecutionInfo? =
        activeTasks.values.firstOrNull { it.conversationId == conversationId }

    fun getActiveTasks(): List<TaskExecutionInfo> =
        activeTasks.values.toList()

    fun hasActiveTasks(): Boolean = activeTasks.isNotEmpty()

    /**
     * 校验停止凭据是否与当前活跃任务一致。
     * 防止迟到的通知 Stop Action 误停后续新启动的任务。
     */
    fun validateStopToken(taskId: String, token: String): Boolean {
        val task = activeTasks[taskId.trim()] ?: return false
        return task.stopToken == token
    }

    private fun updateOverlayHint(context: Context) {
        val activeList = activeTasks.values.toList()
        val primaryTask = activeList.firstOrNull { it.waitingStatus != null } ?: activeList.lastOrNull() ?: return
        val taskId = primaryTask.taskId
        val convId = primaryTask.conversationId

        val message = when (val waiting = primaryTask.waitingStatus) {
            is TaskWaitingStatus.WaitingForManualAuth -> "需要人工验证：${waiting.promptMessage.take(32)}"
            is TaskWaitingStatus.WaitingForUnlock -> "等待屏幕解锁以继续"
            null -> "${primaryTask.taskTitle ?: "小万任务"}: ${primaryTask.statusText ?: "执行中"}"
        }.take(64)

        activeOverlayTaskId = taskId
        mainHandler.post {
            runCatching {
                DraggableBallInstance.initialize(context.applicationContext)
                DraggableBallInstance.showTaskCompletionHint(message) {
                    TaskCompletionNavigator.navigateBackToChat(context, convId, "agent")
                }
            }
        }
    }

    private fun notifyStateChanged(context: Context) {
        val sent = sendServiceCommand(context, TaskRuntimeService.ACTION_UPDATE)
        if (!sent) {
            postFallbackNotification(context)
        }
    }

    private fun sendServiceCommand(context: Context, action: String): Boolean {
        val intent = Intent(context.applicationContext, TaskRuntimeService::class.java).apply {
            this.action = action
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
            true
        }.onFailure { error ->
            OmniLog.w(
                TAG,
                "发送前台服务命令失败 action=$action：${error.message}"
            )
        }.getOrDefault(false)
    }

    private fun extractSessionId(taskId: String): String? {
        if (!taskId.startsWith("agent-turn:")) return null
        val parts = taskId.split(":")
        return if (parts.size >= 2) parts[1].trim().ifEmpty { null } else null
    }

    private fun extractTurnId(taskId: String): String? {
        if (!taskId.startsWith("agent-turn:")) return null
        val parts = taskId.split(":")
        return if (parts.size >= 3) parts[2].trim().ifEmpty { null } else null
    }

    private fun postFallbackNotification(context: Context) {
        runCatching {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
            val notification = TaskRuntimeService.buildNotificationFromRuntime(context)
            notificationManager.notify(TaskRuntimeService.NOTIFICATION_ID, notification)
        }
    }

    private fun cancelFallbackNotification(context: Context) {
        runCatching {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
            notificationManager.cancel(TaskRuntimeService.NOTIFICATION_ID)
        }
    }

    internal fun resetForTesting() {
        activeTasks.clear()
        waitingStatusByConversation.clear()
        activeOverlayTaskId = null
    }
}
