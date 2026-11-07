package cn.com.omnimind.bot.task.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog

/**
 * Direct Boot 开机锁屏广播接收器。
 * 仅监听 Intent.ACTION_LOCKED_BOOT_COMPLETED 广播。
 * 契约：
 * 1. 运行在 :task_boot 独立进程且 directBootAware=true。
 * 2. 绝对不读取、解密或访问处于加密锁定状态的 CE 存储（TaskContinuationStore）。
 * 3. 仅从 Device Protected (DP) 存储中读取 hasPending 布尔提示。
 * 4. 仅发布泛化通知提示用户解锁，绝对不启动任务运行时或假造 Turn。
 */
class TaskContinuationBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            OmniLog.d(TAG, "忽略非 LOCKED_BOOT_COMPLETED 广播：$action")
            return
        }

        OmniLog.i(TAG, "收到 ACTION_LOCKED_BOOT_COMPLETED 广播，开始读取 DP 提示...")
        val hasPending = TaskContinuationNotifications.readPendingHint(context)
        if (hasPending) {
            OmniLog.i(TAG, "DP 提示存在待恢复任务，展示等待解锁通知")
            TaskContinuationNotifications.showWaitingForUnlock(context)
        } else {
            OmniLog.d(TAG, "DP 提示无待恢复任务，不发布通知")
        }
    }

    companion object {
        private const val TAG = "TaskContinuationBootReceiver"
    }
}
