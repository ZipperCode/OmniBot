package cn.com.omnimind.bot.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.task.runtime.TaskRuntimeService

class WorkspaceScheduledTaskReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "WorkspaceTaskReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WorkspaceScheduledTaskScheduler.ACTION_SCHEDULED_TASK_TRIGGER) {
            return
        }
        val taskId = intent.getStringExtra(WorkspaceScheduledTaskScheduler.EXTRA_TASK_ID)
            ?.trim()
            .orEmpty()
        if (taskId.isEmpty()) {
            return
        }

        val appContext = context.applicationContext ?: context
        try {
            val serviceIntent = Intent(appContext, TaskRuntimeService::class.java).apply {
                action = TaskRuntimeService.ACTION_EXECUTE_SCHEDULED_TASK
                putExtra(TaskRuntimeService.EXTRA_TASK_ID, taskId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }
        } catch (t: Throwable) {
            OmniLog.e(
                TAG,
                "启动前台服务执行定时任务失败，保留原任务 taskId=$taskId message=${t.message}",
                t
            )
        }
    }
}
