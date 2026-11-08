package cn.com.omnimind.bot.task.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Display
import androidx.core.app.NotificationCompat
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.R
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.bot.agent.display.VirtualDisplayPreviewActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/**
 * 用户可见长任务的前台服务宿主。
 * 本服务仅持有 Android 前台服务执行租约，ACP 协议持有 Turn 状态与持久会话，本服务绝不自建平行执行器。
 */
class TaskRuntimeService : Service() {
    companion object {
        private const val TAG = "TaskRuntimeService"
        const val ACTION_START = "cn.com.omnimind.bot.task.runtime.START"
        const val ACTION_UPDATE = "cn.com.omnimind.bot.task.runtime.UPDATE"
        const val ACTION_STOP = "cn.com.omnimind.bot.task.runtime.STOP"
        const val ACTION_EXECUTE_SCHEDULED_TASK =
            "cn.com.omnimind.bot.task.runtime.EXECUTE_SCHEDULED_TASK"
        const val EXTRA_TASK_ID = "taskId"

        const val CHANNEL_ID = "task_runtime_execution"
        const val CHANNEL_NAME = "任务执行"

        const val CHANNEL_ID_ATTENTION = "task_runtime_attention"
        const val CHANNEL_NAME_ATTENTION = "任务认证与等待"

        const val NOTIFICATION_ID = 2048110

        fun buildNotificationFromRuntime(context: Context): Notification {
            ensureNotificationChannels(context)
            val activeTasks = TaskRuntime.getActiveTasks()
            val taskWithWaiting = activeTasks.firstOrNull { it.waitingStatus != null }
            val primaryTask = taskWithWaiting ?: activeTasks.lastOrNull()

            val waiting = primaryTask?.waitingStatus
            val channelId = if (waiting != null) CHANNEL_ID_ATTENTION else CHANNEL_ID

            val title = when (waiting) {
                is TaskWaitingStatus.WaitingForManualAuth -> "小万任务需要人工验证"
                is TaskWaitingStatus.WaitingForUnlock -> "小万任务等待屏幕解锁"
                null -> primaryTask?.taskTitle ?: "小万任务执行中"
            }

            val contentText = when (waiting) {
                is TaskWaitingStatus.WaitingForManualAuth -> waiting.promptMessage
                is TaskWaitingStatus.WaitingForUnlock -> waiting.message
                null -> primaryTask?.statusText ?: "正在后台执行用户发起的任务"
            }

            // 查看/点击通知：复用 TaskCompletionNavigator 导航契约
            // 注意：data URI 赋予 Intent 唯一标识，避免 extras 被过滤或被跨会话覆盖
            val convId = primaryTask?.conversationId ?: -1L
            val viewIntent = Intent(context, TaskRuntimeActionReceiver::class.java).apply {
                action = TaskRuntimeActionReceiver.ACTION_VIEW_CONVERSATION
                data = Uri.parse("taskruntime://view/$convId")
                if (convId > 0L) {
                    putExtra(TaskRuntimeActionReceiver.EXTRA_CONVERSATION_ID, convId)
                }
            }
            val viewPendingIntent = PendingIntent.getBroadcast(
                context,
                ("view_$convId").hashCode(),
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setContentIntent(viewPendingIntent)
                .setOngoing(waiting == null)
                .setOnlyAlertOnce(waiting == null)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            if (waiting != null) {
                builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            } else {
                builder.setPriority(NotificationCompat.PRIORITY_LOW)
                builder.setCategory(NotificationCompat.CATEGORY_PROGRESS)
            }

            // 动态进度条
            val cur = primaryTask?.progressCurrent
            val max = primaryTask?.progressMax
            if (cur != null && max != null && max > 0) {
                builder.setProgress(max, cur, false)
            } else if (waiting == null && primaryTask != null) {
                builder.setProgress(0, 0, true)
            } else {
                builder.setProgress(0, 0, false)
            }

            // 操作按钮：查看
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "查看",
                    viewPendingIntent
                ).build()
            )

            // 操作按钮：已完成验证（处于人工验证等待态时展示）
            // 采用独立 data URI 绑定 conversationId 与 waitToken，杜绝跨会话覆盖
            if (waiting is TaskWaitingStatus.WaitingForManualAuth) {
                val manualVerifyIntent = Intent(context, TaskRuntimeActionReceiver::class.java).apply {
                    action = TaskRuntimeActionReceiver.ACTION_MANUAL_VERIFY_COMPLETED
                    data = Uri.parse("taskruntime://manual/${waiting.conversationId}/${waiting.waitToken}")
                    putExtra(TaskRuntimeActionReceiver.EXTRA_CONVERSATION_ID, waiting.conversationId)
                    putExtra(TaskRuntimeActionReceiver.EXTRA_SESSION_ID, waiting.sessionId)
                    putExtra(TaskRuntimeActionReceiver.EXTRA_TURN_ID, waiting.turnId)
                    putExtra(TaskRuntimeActionReceiver.EXTRA_WAIT_TOKEN, waiting.waitToken)
                }
                val manualVerifyPendingIntent = PendingIntent.getBroadcast(
                    context,
                    ("manual_${waiting.conversationId}_${waiting.waitToken}").hashCode(),
                    manualVerifyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "已完成验证",
                        manualVerifyPendingIntent
                    ).build()
                )
            }
            // 操作按钮：查看屏幕（仅在 primaryTask.sessionId 拥有活跃虚拟屏时展示）
            val primarySessionId = primaryTask?.sessionId
            val activeVdId = DeviceDisplayManager.activeVirtualDisplayId()
            val ownsVirtualDisplay = activeVdId != Display.DEFAULT_DISPLAY &&
                primarySessionId != null &&
                DisplayInputCoordinator.getVirtualDisplayOwner(activeVdId) == primarySessionId

            if (ownsVirtualDisplay && primarySessionId != null) {
                val previewIntent = VirtualDisplayPreviewActivity.createIntent(
                    context = context,
                    sessionId = primarySessionId,
                    displayId = activeVdId,
                    taskId = primaryTask.taskId,
                    turnId = primaryTask.turnId,
                    stopToken = primaryTask.stopToken,
                )
                val previewPendingIntent = PendingIntent.getActivity(
                    context,
                    ("preview_${primarySessionId}_$activeVdId").hashCode(),
                    previewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "查看屏幕",
                        previewPendingIntent
                    ).build()
                )
            }

            // 操作按钮：停止
            // 采用独立 data URI 绑定 taskId、turnId 与 stopToken，杜绝旧通知被新任务覆盖
            if (primaryTask != null) {
                val stopIntent = Intent(context, TaskRuntimeActionReceiver::class.java).apply {
                    action = TaskRuntimeActionReceiver.ACTION_STOP_TASK
                    data = Uri.parse("taskruntime://stop/${primaryTask.taskId}/${primaryTask.turnId}/${primaryTask.stopToken}")
                    putExtra(TaskRuntimeActionReceiver.EXTRA_TASK_ID, primaryTask.taskId)
                    putExtra(TaskRuntimeActionReceiver.EXTRA_SESSION_ID, primaryTask.sessionId)
                    putExtra(TaskRuntimeActionReceiver.EXTRA_TURN_ID, primaryTask.turnId)
                    putExtra(TaskRuntimeActionReceiver.EXTRA_STOP_TOKEN, primaryTask.stopToken)
                }
                val stopPendingIntent = PendingIntent.getBroadcast(
                    context,
                    ("stop_${primaryTask.taskId}_${primaryTask.turnId}_${primaryTask.stopToken}").hashCode(),
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
                )
                builder.addAction(
                    NotificationCompat.Action.Builder(
                        0,
                        "停止",
                        stopPendingIntent
                    ).build()
                )
            }

            val baseNotification = builder.build()
            if (Build.VERSION.SDK_INT >= 36 && primaryTask != null && waiting == null) {
                return applyPromotedOngoingNotification(context, baseNotification, primaryTask)
            }
            return baseNotification
        }

        fun ensureNotificationChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

            val channelLow = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "小万在后台执行用户发起的任务"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channelLow)

            val channelHigh = NotificationChannel(
                CHANNEL_ID_ATTENTION,
                CHANNEL_NAME_ATTENTION,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "小万任务等待屏幕解锁或人工验证"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channelHigh)
        }

        private fun immutableFlag(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        private fun applyPromotedOngoingNotification(
            context: Context,
            baseNotification: Notification,
            primaryTask: TaskExecutionInfo,
        ): Notification {
            return try {
                val recoverBuilder = Notification.Builder.recoverBuilder(context, baseNotification)
                recoverBuilder.setRequestPromotedOngoing(true)

                val cur = primaryTask.progressCurrent
                val max = primaryTask.progressMax
                val progressStyle = Notification.ProgressStyle()
                if (cur != null && max != null && max > 0) {
                    val clamped = cur.coerceIn(0, max)
                    progressStyle.addProgressSegment(Notification.ProgressStyle.Segment(max))
                    progressStyle.setProgress(clamped)
                    progressStyle.setProgressIndeterminate(false)
                } else {
                    progressStyle.setProgressIndeterminate(true)
                }
                recoverBuilder.setStyle(progressStyle)
                recoverBuilder.build()
            } catch (e: Throwable) {
                OmniLog.w(TAG, "Promoted ongoing notification configuration failed: ${e.message}")
                baseNotification
            }
        }
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runningScheduledTasks = 0
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
        startForeground(NOTIFICATION_ID, buildNotificationFromRuntime(this))
        OmniLog.d(TAG, "前台任务运行时服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null) {
            OmniLog.i(TAG, "进程重建后检查前台任务运行时服务状态 startId=$startId")
            checkAndStopServiceIfIdle(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START, ACTION_UPDATE -> updateNotification()
            ACTION_STOP -> {
                OmniLog.i(TAG, "通过服务动作停止前台任务运行时 startId=$startId")
                checkAndStopServiceIfIdle(startId)
            }
            ACTION_EXECUTE_SCHEDULED_TASK -> {
                handleExecuteScheduledTask(intent, startId)
            }
            else -> OmniLog.w(TAG, "忽略未知的前台任务运行时动作：${intent.action}")
        }
        return START_NOT_STICKY
    }

    private fun handleExecuteScheduledTask(intent: Intent, startId: Int) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID)?.trim()
        if (taskId.isNullOrEmpty()) {
            OmniLog.w(TAG, "收到执行定时任务请求，但 taskId 为空 startId=$startId")
            checkAndStopServiceIfIdle(startId)
            return
        }

        runningScheduledTasks++
        updateNotification()
        OmniLog.i(TAG, "开始在前台服务中执行定时任务 taskId=$taskId startId=$startId")

        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    WorkspaceScheduledTaskScheduler(applicationContext).onAlarmTriggered(taskId)
                }
            } catch (e: CancellationException) {
                OmniLog.i(TAG, "定时任务执行协程被取消 taskId=$taskId: ${e.message}")
                throw e
            } catch (t: Throwable) {
                OmniLog.e(TAG, "执行定时任务失败 taskId=$taskId: ${t.message}", t)
            } finally {
                runningScheduledTasks--
                OmniLog.d(TAG, "定时任务执行结束 taskId=$taskId，剩余调度任务数=$runningScheduledTasks")
                checkAndStopServiceIfIdle()
            }
        }
    }

    private fun checkAndStopServiceIfIdle(startId: Int? = null) {
        if (runningScheduledTasks > 0) {
            OmniLog.d(TAG, "仍有 $runningScheduledTasks 个后台调度任务在执行中，延后停止前台服务")
            return
        }
        if (TaskRuntime.hasActiveTasks()) {
            OmniLog.d(TAG, "仍有活跃的任务运行时租约，暂不停止前台服务")
            return
        }
        OmniLog.i(TAG, "所有任务租约与调度任务均已完成，停止前台任务运行时服务 (startId=$startId, lastStartId=$lastStartId)")
        if (startId != null && startId > 0) {
            stopSelf(startId)
        } else if (lastStartId > 0) {
            stopSelf(lastStartId)
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        OmniLog.d(TAG, "前台任务运行时服务已销毁")
        serviceScope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotificationFromRuntime(this))
    }
}
