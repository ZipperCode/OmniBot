package cn.com.omnimind.bot.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.task.runtime.TaskContinuationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WorkspaceScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_USER_PRESENT
        ) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (
                    action == Intent.ACTION_BOOT_COMPLETED ||
                    action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                    action == Intent.ACTION_TIMEZONE_CHANGED ||
                    action == Intent.ACTION_TIME_CHANGED
                ) {
                    runCatching {
                        WorkspaceMemoryRollupScheduler(context).ensureScheduledIfEnabled()
                        WorkspaceScheduledTaskScheduler(context).rescheduleAllEnabled()
                        AgentAlarmToolService(context).reschedulePersistedExactRemindersIfPermitted()
                    }.onFailure {
                        OmniLog.e("WorkspaceScheduleBootReceiver", "reschedule failed: ${it.message}")
                    }
                }

                // Check and resume pending continuation if any
                if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_USER_PRESENT) {
                    TaskContinuationCoordinator.resumePendingContinuationIfAny(context)
                }
            } catch (t: Throwable) {
                OmniLog.e("WorkspaceScheduleBootReceiver", "boot continuation execution error: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
