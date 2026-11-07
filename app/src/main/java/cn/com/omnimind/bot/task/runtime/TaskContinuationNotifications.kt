package cn.com.omnimind.bot.task.runtime

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.activity.MainActivity
import cn.com.omnimind.bot.R

/**
 * 任务重启延续等待解锁通知管理器。
 * 遵循严格契约：
 * 1. 仅在 Device-Protected (DP) 存储中保存 has_pending 标志，不存任何会话/标题/目标/凭据信息。
 * 2. 复用既有 TaskRuntimeService.CHANNEL_ID_ATTENTION 渠道与 ensureNotificationChannels。
 * 3. 使用独立通知 ID，不启动前台服务 (FGS)，不伪造 Turn 或活跃任务。
 * 4. 锁屏泛化公开版本（publicVersion），不泄露任务隐私。
 * 5. contentIntent 直达 MainActivity 触发正常解锁恢复流。
 */
object TaskContinuationNotifications {
    private const val TAG = "TaskContinuationNotif"

    /** 供父层 App 在 onCreate 中识别 directBoot 广播进程并 early branch */
    const val BOOT_NOTIFICATION_PROCESS_SUFFIX = ":task_boot"

    /** 独立通知 ID，严禁占用或影响前台服务与其它通知 */
    const val NOTIFICATION_ID_WAITING_FOR_UNLOCK = 2048119

    private const val DP_PREFS_NAME = "task_continuation_dp_hint"
    private const val KEY_HAS_PENDING = "has_pending"

    private fun getDpContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (context.isDeviceProtectedStorage) {
                context
            } else {
                context.createDeviceProtectedStorageContext()
            }
        } else {
            context
        }
    }

    /**
     * 同步写盘更新 DP 存储中的待恢复提示布尔值。
     * commit() 保证同步刷入，更新失败必须返回 false。
     */
    fun persistPendingHint(context: Context, hasPending: Boolean): Boolean {
        return runCatching {
            val dpContext = getDpContext(context)
            val prefs = dpContext.getSharedPreferences(DP_PREFS_NAME, Context.MODE_PRIVATE)
            val success = prefs.edit().putBoolean(KEY_HAS_PENDING, hasPending).commit()
            if (success) {
                OmniLog.i(TAG, "已同步更新 DP hint: hasPending=$hasPending")
            } else {
                OmniLog.e(TAG, "同步更新 DP hint 失败: hasPending=$hasPending")
            }
            success
        }.onFailure {
            OmniLog.e(TAG, "更新 DP hint 异常：${it.message}", it)
        }.getOrDefault(false)
    }

    /**
     * 读取 DP 存储中的待恢复提示布尔值（Direct Boot 模式下可安全读取）。
     */
    fun readPendingHint(context: Context): Boolean {
        return runCatching {
            val dpContext = getDpContext(context)
            val prefs = dpContext.getSharedPreferences(DP_PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(KEY_HAS_PENDING, false)
        }.onFailure {
            OmniLog.e(TAG, "读取 DP hint 异常：${it.message}", it)
        }.getOrDefault(false)
    }

    /**
     * 发布等待用户解锁屏幕的泛化提示通知。
     * 不泄露任何任务隐私/会话/标题/目标，避免重复警报。
     */
    fun showWaitingForUnlock(context: Context) {
        runCatching {
            TaskRuntimeService.ensureNotificationChannels(context)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_WAITING_FOR_UNLOCK,
                launchIntent,
                flags
            )

            val iconRes = R.drawable.ic_notification

            // 泛化公共锁屏版本：隐藏任务详情
            val publicNotification = NotificationCompat.Builder(context, TaskRuntimeService.CHANNEL_ID_ATTENTION)
                .setSmallIcon(iconRes)
                .setContentTitle("小万任务等待中")
                .setContentText("请解锁屏幕以继续执行任务")
                .setContentIntent(contentPendingIntent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()

            val notification = NotificationCompat.Builder(context, TaskRuntimeService.CHANNEL_ID_ATTENTION)
                .setSmallIcon(iconRes)
                .setContentTitle("小万任务等待解锁")
                .setContentText("设备已重启，请解锁屏幕以恢复任务执行")
                .setContentIntent(contentPendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicNotification)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID_WAITING_FOR_UNLOCK, notification)
            OmniLog.i(TAG, "已发布等待解锁通知 notificationId=$NOTIFICATION_ID_WAITING_FOR_UNLOCK")
        }.onFailure {
            OmniLog.e(TAG, "发布等待解锁通知失败：${it.message}", it)
        }
    }

    /**
     * 撤销等待解锁通知（仅针对本模块独立 notificationId，绝不清除其它通知）。
     */
    fun cancel(context: Context) {
        runCatching {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID_WAITING_FOR_UNLOCK)
            OmniLog.i(TAG, "已撤销等待解锁通知 notificationId=$NOTIFICATION_ID_WAITING_FOR_UNLOCK")
        }.onFailure {
            OmniLog.e(TAG, "撤销等待解锁通知异常：${it.message}", it)
        }
    }
}
