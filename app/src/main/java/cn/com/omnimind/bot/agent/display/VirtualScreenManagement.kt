package cn.com.omnimind.bot.agent.display

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.bot.agent.runtime.AgentSessionBindingRepository
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskRuntimeActionReceiver
import cn.com.omnimind.bot.task.runtime.TaskWaitingStatus
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 虚拟屏设备管理通道。
 */
internal class VirtualScreenManagement(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val packageQueryMutex = Mutex()
    @Volatile private var lastPackageQueryMs = 0L
    @Volatile private var cachedPackageName: String? = null
    @Volatile private var cachedAppLabel: String? = null
    @Volatile private var cachedDisplayId: Int = Display.DEFAULT_DISPLAY

    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        if (call.method !in METHODS) return false
        scope.launch {
            try {
                when (call.method) {
                    "getVirtualScreenState" -> handleGetVirtualScreenState(result)
                    "openVirtualScreenPreview" -> handleOpenVirtualScreenPreview(call, result)
                    "stopVirtualScreenTask" -> handleStopVirtualScreenTask(call, result)
                    "closeVirtualScreen" -> handleCloseVirtualScreen(call, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                result.error("internal_error", t.message ?: "虚拟屏操作失败", null)
            }
        }
        return true
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun handleGetVirtualScreenState(result: MethodChannel.Result) {
        val payload = withContext(Dispatchers.IO) {
            val isDisplayActive = DeviceDisplayManager.isVirtualDisplayActive()
            val activeDisplayId = DeviceDisplayManager.activeVirtualDisplayId()
            val activeOwner = DeviceDisplayManager.activeVirtualDisplayOwner()
                ?: (if (activeDisplayId != Display.DEFAULT_DISPLAY) DisplayInputCoordinator.getVirtualDisplayOwner(activeDisplayId) else null)

            val overlayAvailable = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

            if (!isDisplayActive || activeDisplayId == Display.DEFAULT_DISPLAY || activeDisplayId <= 0) {
                return@withContext mapOf<String, Any?>(
                    "state" to "absent",
                    "displayId" to null,
                    "sessionId" to null,
                    "width" to null,
                    "height" to null,
                    "dpi" to null,
                    "conversationId" to null,
                    "taskId" to null,
                    "turnId" to null,
                    "taskTitle" to null,
                    "statusText" to null,
                    "appLabel" to null,
                    "packageName" to null,
                    "overlayAvailable" to overlayAvailable,
                )
            }

            // 查询物理或逻辑真实规格，缺失时以事实维度为准，不使用默认值冒充事实
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(activeDisplayId)
            val dmMetrics = if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                } else null
            } else null

            val factualDims = DeviceDisplayManager.getVirtualDisplayDimensions(activeDisplayId)
            val resolvedDims = dmMetrics ?: factualDims
            val width = resolvedDims?.first
            val height = resolvedDims?.second
            val dpi = resolvedDims?.third

            // 短周期缓存顶层任务信息，避免高频轮询每次触发 IPC 或新进程
            val now = System.currentTimeMillis()
            val (packageName, appLabel) = if (cachedDisplayId == activeDisplayId && now - lastPackageQueryMs < 2000L && cachedPackageName != null) {
                Pair(cachedPackageName, cachedAppLabel)
            } else {
                packageQueryMutex.withLock {
                    val recheckNow = System.currentTimeMillis()
                    if (cachedDisplayId == activeDisplayId && recheckNow - lastPackageQueryMs < 2000L && cachedPackageName != null) {
                        Pair(cachedPackageName, cachedAppLabel)
                    } else {
                        val rootTasks = runCatching { DeviceDisplayManager.getRootTasks(activeDisplayId) }.getOrDefault(emptyList())
                        val topTask = rootTasks.firstOrNull { it.isFocused }
                            ?: rootTasks.firstOrNull { it.visible }
                            ?: rootTasks.firstOrNull()
                        val pkg = topTask?.packageName?.takeIf { it.isNotBlank() }
                        val label = pkg?.let { p ->
                            runCatching {
                                val appInfo = context.packageManager.getApplicationInfo(p, 0)
                                context.packageManager.getApplicationLabel(appInfo)?.toString()
                            }.getOrNull()
                        } ?: pkg
                        cachedDisplayId = activeDisplayId
                        lastPackageQueryMs = recheckNow
                        cachedPackageName = pkg
                        cachedAppLabel = label
                        Pair(pkg, label)
                    }
                }
            }

            // 查询当前活跃任务与等待状态
            val activeTask = if (!activeOwner.isNullOrBlank()) {
                TaskRuntime.findActiveTaskBySession(activeOwner)
            } else {
                TaskRuntime.getActiveTasks().firstOrNull { it.screenDisplayId == activeDisplayId }
            } ?: TaskRuntime.getActiveTasks().firstOrNull { it.screenDisplayId == activeDisplayId }

            val isLeaseHeld = DisplayInputCoordinator.isLeaseHeld(activeDisplayId)
            val isAcpTurnActive = !activeOwner.isNullOrBlank() &&
                AgentRuntimeManager.getInstance(context).isSessionTurnActive(activeOwner)

            val state = when {
                activeTask?.waitingStatus != null -> "waiting"
                activeTask != null || isLeaseHeld || isAcpTurnActive -> "running"
                else -> "retained"
            }

            val statusText = when {
                activeTask?.waitingStatus is TaskWaitingStatus.WaitingForUnlock ->
                    (activeTask.waitingStatus as TaskWaitingStatus.WaitingForUnlock).message
                activeTask?.waitingStatus is TaskWaitingStatus.WaitingForManualAuth ->
                    (activeTask.waitingStatus as TaskWaitingStatus.WaitingForManualAuth).promptMessage
                !activeTask?.statusText.isNullOrBlank() -> activeTask?.statusText
                state == "waiting" -> "等待处理"
                state == "running" -> "任务执行中"
                state == "retained" -> "画面已保留"
                else -> null
            }

            val conversationId = activeTask?.conversationId
                ?: activeOwner?.let { sid ->
                    runCatching {
                        AgentSessionBindingRepository(context).getBindingByThreadId(sid)?.conversationId
                    }.getOrNull()
                }

            mapOf<String, Any?>(
                "state" to state,
                "displayId" to activeDisplayId,
                "sessionId" to activeOwner,
                "width" to width,
                "height" to height,
                "dpi" to dpi,
                "conversationId" to conversationId,
                "taskId" to activeTask?.taskId,
                "turnId" to activeTask?.turnId,
                "taskTitle" to activeTask?.taskTitle,
                "statusText" to statusText,
                "appLabel" to appLabel,
                "packageName" to packageName,
                "overlayAvailable" to overlayAvailable,
            )
        }
        result.success(payload)
    }

    private fun handleOpenVirtualScreenPreview(call: MethodCall, result: MethodChannel.Result) {
        val displayId = call.argument<Int>("displayId")
        val sessionId = call.argument<String>("sessionId")?.trim()

        if (displayId == null || sessionId.isNullOrEmpty()) {
            result.error("invalid_argument", "缺少屏幕编号或会话标识", null)
            return
        }

        val activeDisplayId = DeviceDisplayManager.activeVirtualDisplayId()
        val isDisplayActive = DeviceDisplayManager.isVirtualDisplayActive()

        if (!isDisplayActive || activeDisplayId == Display.DEFAULT_DISPLAY || activeDisplayId <= 0) {
            result.error("screen_not_found", "虚拟显示屏不存在或已被销毁", null)
            return
        }

        if (displayId != activeDisplayId) {
            result.error("display_mismatch", "指定的虚拟显示屏与当前活跃显示屏不匹配", null)
            return
        }

        val activeOwner = DeviceDisplayManager.activeVirtualDisplayOwner()
            ?: DisplayInputCoordinator.getVirtualDisplayOwner(activeDisplayId)

        if (activeOwner == null || activeOwner != sessionId) {
            result.error("owner_mismatch", "虚拟显示屏所有者不匹配或已被更新", null)
            return
        }

        val activeTask = TaskRuntime.findActiveTaskBySession(sessionId)

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = activeDisplayId,
            taskId = activeTask?.taskId,
            turnId = activeTask?.turnId,
            stopToken = activeTask?.stopToken,
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        result.success(true)
    }

    private suspend fun handleStopVirtualScreenTask(call: MethodCall, result: MethodChannel.Result) {
        val displayId = call.argument<Int>("displayId")
        val sessionId = call.argument<String>("sessionId")?.trim()
        val taskId = call.argument<String>("taskId")?.trim()
        val turnId = call.argument<String>("turnId")?.trim()

        if (displayId == null || sessionId.isNullOrEmpty() || taskId.isNullOrEmpty() || turnId.isNullOrEmpty()) {
            result.error("invalid_argument", "缺少屏幕编号、会话标识、任务标识或轮次标识", null)
            return
        }

        val activeDisplayId = DeviceDisplayManager.activeVirtualDisplayId()
        val isDisplayActive = DeviceDisplayManager.isVirtualDisplayActive()

        if (!isDisplayActive || activeDisplayId == Display.DEFAULT_DISPLAY || activeDisplayId <= 0) {
            result.error("screen_not_found", "虚拟显示屏不存在或已被销毁", null)
            return
        }

        if (displayId != activeDisplayId) {
            result.error("display_mismatch", "指定的虚拟显示屏与当前活跃显示屏不匹配", null)
            return
        }

        val activeOwner = DeviceDisplayManager.activeVirtualDisplayOwner()
            ?: DisplayInputCoordinator.getVirtualDisplayOwner(activeDisplayId)

        if (activeOwner == null || activeOwner != sessionId) {
            result.error("owner_mismatch", "虚拟显示屏所有者与请求会话不匹配", null)
            return
        }

        val activeTask = TaskRuntime.getActiveTask(taskId)
        if (activeTask == null) {
            result.error("task_not_found", "任务已不在活跃状态或已结束", null)
            return
        }

        if (activeTask.sessionId != null && activeTask.sessionId != sessionId) {
            result.error("session_mismatch", "任务所属会话不匹配", null)
            return
        }

        if (activeTask.turnId != turnId) {
            result.error("turn_mismatch", "任务轮次不匹配，禁止取消其他轮次", null)
            return
        }

        val currentSessionTask = TaskRuntime.findActiveTaskBySession(sessionId)
        if (currentSessionTask != null && currentSessionTask.taskId != taskId) {
            result.error("task_changed", "会话已启动新任务，禁止取消新任务", null)
            return
        }

        val stopped = TaskRuntimeActionReceiver.executeStopTask(
            context = context,
            taskId = activeTask.taskId,
            sessionId = sessionId,
            turnId = activeTask.turnId,
            stopToken = activeTask.stopToken,
        )

        if (stopped) {
            result.success(true)
        } else {
            result.error("stop_failed", "停止任务执行失败", null)
        }
    }

    private suspend fun handleCloseVirtualScreen(call: MethodCall, result: MethodChannel.Result) {
        val displayId = call.argument<Int>("displayId")
        val sessionId = call.argument<String>("sessionId")?.trim()

        if (displayId == null || sessionId.isNullOrEmpty()) {
            result.error("invalid_argument", "缺少屏幕编号或会话标识", null)
            return
        }

        val activeDisplayId = DeviceDisplayManager.activeVirtualDisplayId()
        val isDisplayActive = DeviceDisplayManager.isVirtualDisplayActive()

        if (!isDisplayActive || activeDisplayId == Display.DEFAULT_DISPLAY || activeDisplayId <= 0) {
            result.error("screen_not_found", "当前没有可关闭的虚拟显示屏", null)
            return
        }

        if (displayId != activeDisplayId) {
            result.error("display_mismatch", "指定的虚拟显示屏与当前活跃显示屏不匹配", null)
            return
        }

        val activeOwner = DeviceDisplayManager.activeVirtualDisplayOwner()
            ?: DisplayInputCoordinator.getVirtualDisplayOwner(activeDisplayId)

        if (activeOwner == null || activeOwner != sessionId) {
            result.error("owner_mismatch", "虚拟显示屏所有者与请求会话不匹配", null)
            return
        }

        val activeTasks = TaskRuntime.getActiveTasks()
        val hasActiveTask = activeTasks.any { task ->
            (task.screenDisplayId != null && task.screenDisplayId == displayId) ||
            task.sessionId == sessionId
        }
        val isAcpTurnActive = AgentRuntimeManager.getInstance(context).isSessionTurnActive(sessionId)
        if (hasActiveTask || isAcpTurnActive) {
            result.error("screen_busy", "虚拟屏所属会话有正在执行或准备中的任务，拒绝关闭", null)
            return
        }

        if (DisplayInputCoordinator.isLeaseHeld(displayId)) {
            result.error("screen_busy", "虚拟屏输入租约正被占用，拒绝关闭", null)
            return
        }

        val closeResult = DeviceDisplayManager.closeVirtualDisplaySafely(
            expectedDisplayId = displayId,
            expectedOwnerId = sessionId,
            activeTaskGuard = {
                val active = TaskRuntime.getActiveTasks()
                val hasTask = active.any { task ->
                    (task.screenDisplayId != null && task.screenDisplayId == displayId) ||
                    task.sessionId == sessionId
                }
                val hasAcpTurn = AgentRuntimeManager.getInstance(context).isSessionTurnActive(sessionId)
                hasTask || hasAcpTurn
            }
        )

        closeResult.fold(
            onSuccess = {
                result.success(true)
            },
            onFailure = { error ->
                val msg = error.message.orEmpty()
                val code = when {
                    msg.contains("busy") || msg.contains("占用") -> "screen_busy"
                    msg.contains("所有者不匹配") -> "owner_mismatch"
                    else -> "screen_not_found"
                }
                result.error(code, error.message ?: "关闭虚拟显示屏失败", null)
            }
        )
    }

    companion object {
        private val METHODS = setOf(
            "getVirtualScreenState",
            "openVirtualScreenPreview",
            "stopVirtualScreenTask",
            "closeVirtualScreen",
        )
    }
}
