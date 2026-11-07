package cn.com.omnimind.bot.task.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 前台任务通知栏用户操作广播接收器。
 * 负责任务停止(Stop)、人工验证完成(Manual Verify Completed)以及会话跳转查看(View Conversation)的分发。
 */
class TaskRuntimeActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TaskRuntimeActionReceiver"

        const val ACTION_STOP_TASK = "cn.com.omnimind.bot.task.runtime.ACTION_STOP_TASK"
        const val ACTION_MANUAL_VERIFY_COMPLETED =
            "cn.com.omnimind.bot.task.runtime.ACTION_MANUAL_VERIFY_COMPLETED"
        const val ACTION_VIEW_CONVERSATION =
            "cn.com.omnimind.bot.task.runtime.ACTION_VIEW_CONVERSATION"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_TURN_ID = "extra_turn_id"
        const val EXTRA_STOP_TOKEN = "extra_stop_token"
        const val EXTRA_WAIT_TOKEN = "extra_wait_token"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action.orEmpty()
        when (action) {
            ACTION_STOP_TASK -> handleStopTask(context, intent)
            ACTION_MANUAL_VERIFY_COMPLETED -> handleManualVerifyCompleted(context, intent)
            ACTION_VIEW_CONVERSATION -> handleViewConversation(context, intent)
            else -> OmniLog.w(TAG, "忽略未处理的动作：$action")
        }
    }

    private fun handleViewConversation(context: Context, intent: Intent) {
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L).takeIf { it > 0L }
        OmniLog.i(TAG, "通过 TaskCompletionNavigator 返回聊天页面 conversationId=$conversationId")
        TaskCompletionNavigator.navigateBackToChat(context, conversationId, "agent")
    }

    private fun handleStopTask(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty().trim()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty().trim()
        val turnId = intent.getStringExtra(EXTRA_TURN_ID).orEmpty().trim()
        val stopToken = intent.getStringExtra(EXTRA_STOP_TOKEN).orEmpty().trim()

        if (taskId.isEmpty()) {
            OmniLog.w(TAG, "停止任务操作被拒绝：taskId 为空")
            return
        }

        // 校验凭据：迟到的通知 Stop Action 绝不取消后续新任务或已轮替的任务
        // 注意：stopToken 属于能力凭据，绝不打入日志
        if (!TaskRuntime.validateStopToken(taskId, stopToken)) {
            OmniLog.w(TAG, "停止任务操作被忽略：凭据不匹配或任务已不在活跃状态 taskId=$taskId")
            return
        }

        if (sessionId.isEmpty()) {
            OmniLog.w(TAG, "停止任务操作：taskId=$taskId 无关联的 sessionId，仅释放本地执行租约")
            TaskRuntime.finish(context, taskId)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 携带 expectedTurnId 与 turnId 进行原子校验，防止旧 Turn 刚结束、新 Turn 启动时的误杀
                val cancelArgs = mutableMapOf<String, Any?>("sessionId" to sessionId)
                if (turnId.isNotEmpty()) {
                    cancelArgs["expectedTurnId"] = turnId
                    cancelArgs["turnId"] = turnId
                }
                OmniLog.i(TAG, "正在通过官方 session/cancel 取消会话 sessionId=$sessionId expectedTurnId=$turnId")
                AgentRuntimeManager.getInstance(context).handleMethod(
                    "session/cancel",
                    cancelArgs
                )
            } catch (t: Throwable) {
                OmniLog.e(TAG, "通过 runtime manager 取消会话失败 sessionId=$sessionId：${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleManualVerifyCompleted(context: Context, intent: Intent) {
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        val waitToken = intent.getStringExtra(EXTRA_WAIT_TOKEN)?.trim()?.ifEmpty { null }
        val turnId = intent.getStringExtra(EXTRA_TURN_ID)?.trim()?.ifEmpty { null }

        if (conversationId <= 0L) {
            OmniLog.w(TAG, "人工验证完成操作被拒绝：非法的 conversationId=$conversationId")
            return
        }
        OmniLog.i(TAG, "收到人工验证完成广播 conversationId=$conversationId turnId=$turnId")
        TaskAuthWaitCoordinator.notifyManualVerificationCompleted(
            conversationId = conversationId,
            waitToken = waitToken,
            expectedTurnId = turnId
        )
    }
}
