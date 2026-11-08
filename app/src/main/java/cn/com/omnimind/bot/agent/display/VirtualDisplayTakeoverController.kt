package cn.com.omnimind.bot.agent.display

import android.content.Context
import android.os.SystemClock
import android.view.Display
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskRuntimeActionReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * 虚拟显示屏无损接管至主屏控制器。
 *
 * 核心接管契约：
 * 1. 移交前先发起官方 Turn 取消，等待同一 Turn 确认停止且无新 Turn 准入。
 * 2. 期间若有新 Turn 准入，判定为忙碌（busy），中断迁移且绝不误杀新 Turn。
 * 3. 超时保留画面并提示“任务尚未停止”，不抢占输入、不取消别的 Turn。
 * 4. 检查虚拟屏仅存在唯一根任务（rootTask），避免目标歧义。
 * 5. 获取双屏原子租约执行 presentToUser / moveRootTask；失败保留虚拟屏，成功返回 true。
 * 6. 协程被取消时严密抛出 CancellationException，调用方控制 Job 状态并防止旧身份污染新窗口。
 */
class VirtualDisplayTakeoverController(
    private val context: Context,
) {

    companion object {
        private const val TAG = "VirtualDisplayTakeover"
        const val TAKEOVER_WAIT_TIMEOUT_MS = 5000L
    }

    /**
     * 执行虚拟屏任务移交至主屏。
     *
     * @param sessionId 绑定的会话标识
     * @param displayId 绑定的虚拟显示屏 ID
     * @param taskId 绑定的任务 ID
     * @param turnId 绑定的任务轮次 ID
     * @param stopToken 任务停止凭据
     * @param isBindingCurrent 校验当前调用上下文是否仍为当前活跃绑定，防止竞态
     * @param onStatus 状态提示文案回调
     * @return 移交是否成功
     */
    suspend fun present(
        sessionId: String,
        displayId: Int,
        taskId: String?,
        turnId: String?,
        stopToken: String?,
        isBindingCurrent: () -> Boolean,
        onStatus: (String) -> Unit,
    ): Boolean {
        if (sessionId.isEmpty() || displayId == Display.DEFAULT_DISPLAY) {
            OmniLog.w(TAG, "拒绝非法的移交请求：sessionId 为空或 displayId 为主屏")
            return false
        }
        val callingContext = currentCoroutineContext()
        fun checkValid() {
            callingContext.ensureActive()
            if (!isBindingCurrent()) {
                throw CancellationException("接管已被取代或取消")
            }
        }

        checkValid()
        onStatus("正在停止任务并准备移交...")

        try {
            val currentTask = TaskRuntime.findActiveTaskBySession(sessionId)
            if (currentTask != null && !turnId.isNullOrBlank() && currentTask.turnId != turnId) {
                if (isBindingCurrent()) {
                    onStatus("新任务轮次已开始，无法移交")
                }
                return false
            }

            // 用户明确接管导致取消时，抑制弹出 cancelled 结果弹窗挡住移交过程
            if (!taskId.isNullOrBlank()) {
                TaskRuntime.suppressResultPresentation(taskId)
            } else if (sessionId.isNotEmpty()) {
                TaskRuntime.suppressResultPresentationForSession(sessionId)
            }

            // 复用 TaskRuntimeActionReceiver 官方取消通道仅对初始绑定的 Turn/Task/StopToken 发起取消
            if (!taskId.isNullOrBlank() && !stopToken.isNullOrBlank()) {
                TaskRuntimeActionReceiver.executeStopTask(
                    context = context,
                    taskId = taskId,
                    sessionId = sessionId,
                    turnId = turnId,
                    stopToken = stopToken,
                )
            }

            val runtimeManager = AgentRuntimeManager.getInstance(context)
            val startWait = SystemClock.elapsedRealtime()
            var turnStopped = false
            var newTurnBusy = false

            while (SystemClock.elapsedRealtime() - startWait < TAKEOVER_WAIT_TIMEOUT_MS) {
                if (!currentCoroutineContext().isActive) throw CancellationException("接管协程已取消")
                checkValid()

                val checkTask = TaskRuntime.findActiveTaskBySession(sessionId)
                val hostActive = runtimeManager.isSessionTurnActive(sessionId)

                if (checkTask != null && !checkTask.turnId.isNullOrBlank() && !turnId.isNullOrBlank() && checkTask.turnId != turnId) {
                    // 新 Turn 已准入，判定为忙碌
                    newTurnBusy = true
                    break
                }

                if ((checkTask == null || checkTask.turnId.isNullOrBlank()) && !hostActive) {
                    turnStopped = true
                    break
                }

                delay(100)
                if (!currentCoroutineContext().isActive) throw CancellationException("接管协程已取消")
                checkValid()
            }

            if (!currentCoroutineContext().isActive) throw CancellationException("接管协程已取消")
            checkValid()

            if (newTurnBusy || runtimeManager.isSessionTurnActive(sessionId)) {
                if (isBindingCurrent()) {
                    onStatus("新任务轮次已开始，无法移交")
                }
                return false
            }

            if (!turnStopped) {
                // 超时保留画面并显示“任务尚未停止”，不抢输入、不再取消别的 Turn
                if (isBindingCurrent()) {
                    onStatus("任务尚未停止")
                }
                return false
            }

            // Turn 停止且无新 Turn，获取双屏 lease 执行迁移
            if (isBindingCurrent()) {
                onStatus("正在移交至主屏...")
            }
            val leaseOwnerId = "takeover:$sessionId:${System.currentTimeMillis()}"

            if (!currentCoroutineContext().isActive) throw CancellationException("接管协程已取消")
            checkValid()

            val rootTasks = try {
                DeviceDisplayManager.getRootTasks(displayId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }

            if (!currentCoroutineContext().isActive) throw CancellationException("接管协程已取消")
            checkValid()

            if (rootTasks.isEmpty()) {
                if (isBindingCurrent()) {
                    onStatus("虚拟屏上未检测到运行中的应用任务")
                }
                return false
            }

            if (rootTasks.size > 1) {
                if (isBindingCurrent()) {
                    onStatus("虚拟屏存在多个根任务，移交目标歧义")
                }
                return false
            }

            val targetTask = rootTasks.first()

            val result = DeviceDisplayManager.presentToUser(
                context = context,
                packageName = targetTask.packageName,
                taskId = targetTask.taskId,
                sessionOwnerId = sessionId,
                leaseOwnerId = leaseOwnerId,
                canPresent = {
                    isBindingCurrent() && !runtimeManager.isSessionTurnActive(sessionId)
                },
            )

            if (!currentCoroutineContext().isActive) throw CancellationException("接管协程已取消")
            checkValid()

            if (result.success) {
                if (isBindingCurrent()) {
                    onStatus("已成功移交至主屏")
                }
                return true
            } else {
                val failMsg = if (result.message == "display_busy" || result.diagnostics["code"] == "display_busy") {
                    "新任务轮次已开始，无法移交"
                } else {
                    "移交主屏失败: ${result.message}"
                }
                if (isBindingCurrent()) {
                    onStatus(failMsg)
                }
                return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isBindingCurrent()) {
                OmniLog.w(TAG, "移交主屏失败", e)
                onStatus("移交主屏失败: ${e.message}")
            }
            return false
        }
    }
}
