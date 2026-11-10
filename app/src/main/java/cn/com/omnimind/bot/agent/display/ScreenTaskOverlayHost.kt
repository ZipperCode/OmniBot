package cn.com.omnimind.bot.agent.display
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import cn.com.omnimind.androidgui.AndroidGuiOverlayHost
import cn.com.omnimind.androidgui.KeyguardStateMonitor
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.task.runtime.TaskExecutionInfo
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskRuntimeActionReceiver
import cn.com.omnimind.bot.util.TaskCompletionNavigator
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch


/**
 * 屏幕任务浮层几何状态。
 * 单一任务生命周期内保持，新任务时重置。
 */
internal data class TaskGeometry(
    var mode: OverlayDisplayMode = OverlayDisplayMode.CAPSULE,
    var capsuleX: Int = -1,
    var capsuleY: Int = -1,
    var stageX: Int = -1,
    var stageY: Int = -1,
    var stageWidthDp: Int = 296,
    var stageCanvasHeightDp: Int = 336,
    var dockedX: Int = -1,
    var dockedY: Int = -1
)
/**
 * 屏幕任务专用悬浮窗宿主。
 * 遵循单一窗口拥有者、主线程切回、同身份原地更新与主屏移交保护契约。
 */
object ScreenTaskOverlayHost {

    private const val TAG = "ScreenTaskOverlayHost"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 活跃任务身份绑定
    @Volatile
    private var activeTaskId: String? = null
    @Volatile
    private var activeSessionId: String? = null
    @Volatile
    private var activeTurnId: String? = null
    @Volatile
    private var activeStopToken: String? = null
    @Volatile
    private var activeDisplayId: Int? = null
    @Volatile
    private var isExpanded: Boolean = false
    @Volatile
    private var overlayGeometry = TaskGeometry()
    private var snapAnimator: ValueAnimator? = null

    // 独立全屏预览 Activity 前台展示标记（前台时不遮挡全屏画面）
    @Volatile
    private var isPreviewActivityForeground: Boolean = false

    // 移交主屏防中断与防误杀保护
    @Volatile
    private var activeTakeoverTaskId: String? = null
    private var takeoverJob: Job? = null

    // 终态卡片防复活与屏幕任务历史追踪
    @Volatile
    private var showingResultTaskId: String? = null
    private val dismissedResultTaskIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val seenScreenTaskIds: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // 锁屏与待处理状态缓存
    private var lastSyncedTasks: List<TaskExecutionInfo> = emptyList()
    private var pendingResultInfo: Triple<TaskExecutionInfo, String, String?>? = null

    // WindowManager 与视图
    private var windowManager: WindowManager? = null
    private var windowContext: Context? = null
    private var windowType: Int = 0
    private var isTrustedOverlay: Boolean = false

    private var topOverlayView: ScreenTaskCapsuleView? = null
    private var edgeLightOverlayView: ScreenTaskEdgeLightView? = null
    private var resultCardOverlayView: ScreenTaskResultCardView? = null

    private var lifecycleReceiver: BroadcastReceiver? = null
    private var sharedPrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var flutterSharedPrefs: SharedPreferences? = null

    /**
     * 同步活跃任务列表并更新浮层状态。
     */
    fun sync(context: Context, tasks: List<TaskExecutionInfo>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sync(context, tasks) }
            return
        }

        val appContext = context.applicationContext ?: context
        lastSyncedTasks = tasks

        // 记录曾经使用过屏幕的任务
        for (task in tasks) {
            if (task.screenDisplayId != null) {
                seenScreenTaskIds.add(task.taskId)
            }
        }

        // 锁屏或非交互时不展示浮层，但保留当前任务绑定和接收器以便解锁恢复
        if (KeyguardStateMonitor.isKeyguardLocked(appContext) || !KeyguardStateMonitor.isInteractive(appContext)) {
            OmniLog.i(TAG, "设备处于锁定或非交互状态，暂缓展示浮层")
            topOverlayView?.visibility = View.GONE
            topOverlayView?.onScreenOff()
            edgeLightOverlayView?.stop()
            resultCardOverlayView?.visibility = View.GONE
            ensureLifecycleReceiverRegistered(appContext)
            ensureSharedPrefsListener(appContext)
            return
        }

        if (isPreviewActivityForeground) {
            topOverlayView?.visibility = View.GONE
        } else {
            topOverlayView?.visibility = View.VISIBLE
        }

        // 主线程消费时以 TaskRuntime.getActiveTasks 实时事实为准，防止旧 snapshot 排队复活已结束任务
        val liveActiveTasks = TaskRuntime.getActiveTasks()
        val screenTasks = liveActiveTasks.filter { it.screenDisplayId != null }
        if (screenTasks.isEmpty()) {
            // 主屏移交进行中绝不收回预览与身份绑定
            if (activeTakeoverTaskId != null) {
                OmniLog.i(TAG, "主屏移交进行中，保留当前虚拟屏视窗与绑定 activeTakeoverTaskId=$activeTakeoverTaskId")
                return
            }
            if (activeTaskId != null) {
                OmniLog.i(TAG, "无活跃屏幕任务，收回屏幕浮层 activeTaskId=$activeTaskId")
                dismissActiveOverlay(animate = true)
            }
            return
        }

        val targetTask = screenTasks.sortedWith(
            compareByDescending<TaskExecutionInfo> { it.waitingStatus != null }
                .thenByDescending { it.startedAt }
        ).first()

        // 校验目标任务在 TaskRuntime 中确实活跃且凭据匹配，防止消费旧 snapshot 复活已结束任务
        if (!TaskRuntime.validateStopToken(targetTask.taskId, targetTask.stopToken)) {
            OmniLog.w(TAG, "任务已不再活跃或凭据已失效，忽略过期的 sync 快照 taskId=${targetTask.taskId}")
            if (activeTakeoverTaskId == null && activeTaskId == targetTask.taskId) {
                dismissActiveOverlay(animate = true)
            }
            return
        }

        val targetTaskId = targetTask.taskId
        val targetSessionId = targetTask.sessionId.orEmpty()
        val targetTurnId = targetTask.turnId
        val targetStopToken = targetTask.stopToken
        val targetDisplayId = targetTask.screenDisplayId ?: 0
        val isWaiting = targetTask.waitingStatus != null
        val statusText = targetTask.statusText
        // 同身份原地更新，不重建窗口；同身份持续 sync 绝不每tick覆盖用户拖动/缩放/贴边位置
        if (activeTaskId == targetTaskId && topOverlayView != null) {
            activeSessionId = targetSessionId
            activeTurnId = targetTurnId
            activeStopToken = targetStopToken
            activeDisplayId = targetDisplayId

            topOverlayView?.updateState(
                statusText = statusText,
                isWaiting = isWaiting,
                displayId = targetDisplayId,
                sessionId = targetSessionId
            )

            if (targetDisplayId == 0 && !isWaiting) {
                ensureEdgeLightAttached(appContext)
            } else {
                removeEdgeLight()
            }
            // 注意：同身份持续 sync 绝不覆盖用户的当前位置/形态/尺寸！
            return
        }

        // 新任务到达，重置几何状态
        overlayGeometry = TaskGeometry()
        snapAnimator?.cancel()
        snapAnimator = null
        isExpanded = false
        dismissActiveOverlay(animate = false)

        val wm = ensureWindowManager(appContext) ?: return
        val capsuleView = ScreenTaskCapsuleView(windowContext ?: appContext).apply {
            initBinding(
                taskId = targetTaskId,
                sessionId = targetSessionId,
                turnId = targetTurnId,
                stopToken = targetStopToken,
                displayId = targetDisplayId,
                statusText = statusText,
                isWaiting = isWaiting,
                onToggleExpand = { expanded ->
                    handleCapsuleExpandToggle(expanded)
                },
                onStop = { tId, sId, turn, token ->
                    if (activeTaskId == tId && activeStopToken == token) {
                        overlayScope.launch(Dispatchers.IO) {
                            OmniLog.i(TAG, "执行官方任务取消 taskId=$tId")
                            TaskRuntimeActionReceiver.executeStopTask(
                                context = appContext,
                                taskId = tId,
                                sessionId = sId,
                                turnId = turn,
                                stopToken = token.orEmpty()
                            )
                        }
                    }
                },
                onTakeover = { tId, sId, turn, token, dId ->
                    if (activeTaskId == tId && activeStopToken == token) {
                        val resolvedDisplayId = if (dId > 0) dId else (activeDisplayId ?: dId)
                        handleTakeoverToMainDisplay(appContext, tId, sId, turn, token, resolvedDisplayId)
                    }
                },
                onOpenPip = { tId, sId, turn, token, dId ->
                    handleOpenPip(appContext, tId, sId, turn, token, dId)
                },
                hostCallbacks = object : ScreenTaskOverlayHostCallbacks {
                    override fun getSafeInsets(): Rect = this@ScreenTaskOverlayHost.getSafeInsets(appContext, topOverlayView)
                    override fun getWindowPosition(): Pair<Int, Int> = this@ScreenTaskOverlayHost.getWindowPosition()
                    override fun moveWindowBy(dx: Float, dy: Float) = this@ScreenTaskOverlayHost.moveWindowBy(dx, dy)
                    override fun snapWindow(mode: OverlayDisplayMode) = this@ScreenTaskOverlayHost.snapWindow(mode)
                    override fun resizeStage(newWidthPx: Int, newCanvasHeightPx: Int) = this@ScreenTaskOverlayHost.resizeStage(newWidthPx, newCanvasHeightPx)
                    override fun requestModeChange(mode: OverlayDisplayMode) = this@ScreenTaskOverlayHost.setOverlayMode(mode)
                    override fun getSavedCanvasHeightDp(): Int = this@ScreenTaskOverlayHost.getSavedCanvasHeightDp()
                }
            )
        }

        val topParams = createTopLayoutParams(hasMicroLens = targetDisplayId > 0, mode = overlayGeometry.mode)
        var addSuccess = false
        runCatching {
            wm.addView(capsuleView, topParams)
            addSuccess = true
        }.onFailure { e ->
            OmniLog.e(TAG, "添加顶部胶囊浮层失败: ${e.message}", e)
        }

        if (!addSuccess) {
            capsuleView.cleanup()
            topOverlayView = null
            activeTaskId = null
            activeSessionId = null
            activeTurnId = null
            activeStopToken = null
            activeDisplayId = null
            windowManager = null
            windowContext = null
            return
        }

        topOverlayView = capsuleView
        activeTaskId = targetTaskId
        activeSessionId = targetSessionId
        activeTurnId = targetTurnId
        activeStopToken = targetStopToken
        activeDisplayId = targetDisplayId
        isExpanded = false
        if (isPreviewActivityForeground) {
            topOverlayView?.visibility = View.GONE
        }

        if (targetDisplayId == 0 && !isWaiting) {
            ensureEdgeLightAttached(appContext)
        }

        ensureLifecycleReceiverRegistered(appContext)
        ensureSharedPrefsListener(appContext)
    }

    /**
     * 展示任务终态结果卡片。
     */
    fun showResult(
        context: Context,
        task: TaskExecutionInfo,
        terminalStatus: String,
        message: String?
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showResult(context, task, terminalStatus, message) }
            return
        }

        val appContext = context.applicationContext ?: context
        val taskId = task.taskId

        // 仅对真实使用过屏幕的任务生效
        val wasScreenTask = task.screenDisplayId != null || taskId == activeTaskId || seenScreenTaskIds.contains(taskId)
        if (!wasScreenTask) {
            OmniLog.i(TAG, "非屏幕任务，忽略结果卡片展示 taskId=$taskId")
            return
        }

        // 移交主屏中屏蔽 cancelled 终态，保护接管状态机
        if (taskId == activeTakeoverTaskId && isCancelledStatus(terminalStatus)) {
            OmniLog.i(TAG, "移交主屏进行中，屏蔽此取消终态结果卡片 taskId=$taskId")
            return
        }

        // 锁屏或非可交互状态下绝不弹敏感结果卡片，暂存至解锁后展示
        if (KeyguardStateMonitor.isKeyguardLocked(appContext) || !KeyguardStateMonitor.isInteractive(appContext)) {
            OmniLog.i(TAG, "设备锁定中，暂存结果卡片待解锁展示 taskId=$taskId")
            pendingResultInfo = Triple(task, terminalStatus, message)
            ensureLifecycleReceiverRegistered(appContext)
            ensureSharedPrefsListener(appContext)
            return
        }

        // 若当前正在展示此任务的活跃胶囊/边光且不在移交中，予以收起
        if (activeTaskId == taskId && activeTakeoverTaskId != taskId) {
            dismissActiveOverlay(animate = true)
        }

        // 用户已手动关闭过该任务结果，绝不重新复活
        if (dismissedResultTaskIds.contains(taskId)) {
            OmniLog.i(TAG, "结果卡片已被用户关闭，忽略晚到正文更新 taskId=$taskId")
            return
        }
        val fallbackText = "本轮屏幕操作已结束，请返回对话查看结果。"
        val isIncomingFallback = message.isNullOrBlank() || message.trim() == fallbackText
        val displayText = if (!message.isNullOrBlank()) {
            message.trim()
        } else {
            fallbackText
        }

        // 同身份原位平滑更新正文，不闪烁
        if (showingResultTaskId == taskId && resultCardOverlayView != null) {
            val currentMsg = resultCardOverlayView?.getCurrentMessage().orEmpty()
            if (isIncomingFallback && currentMsg.isNotBlank() && currentMsg != fallbackText) {
                OmniLog.i(TAG, "当前已展示真实正文，忽略降级的 fallback 文案 taskId=$taskId")
                return
            }
            OmniLog.i(TAG, "同身份原位平滑更新结果卡片正文 taskId=$taskId")
            resultCardOverlayView?.updateMessage(displayText)
            return
        }

        val wm = ensureWindowManager(appContext) ?: return
        dismissResultCard()

        showingResultTaskId = taskId
        val resultCard = ScreenTaskResultCardView(windowContext ?: appContext).apply {
            bindResult(
                taskId = taskId,
                conversationId = task.conversationId,
                terminalStatus = terminalStatus,
                message = displayText,
                onStay = { tId ->
                    dismissedResultTaskIds.add(tId)
                    dismissResultCard()
                },
                onReturnChat = { tId, conversationId ->
                    dismissedResultTaskIds.add(tId)
                    TaskCompletionNavigator.navigateBackToChat(appContext, conversationId, "agent")
                    dismissResultCard()
                }
            )
        }

        val resultParams = createResultLayoutParams()
        var addSuccess = false
        runCatching {
            wm.addView(resultCard, resultParams)
            addSuccess = true
        }.onFailure { e ->
            OmniLog.e(TAG, "添加底部结果卡片失败: ${e.message}", e)
        }

        if (addSuccess) {
            resultCardOverlayView = resultCard
            showingResultTaskId = taskId
            ensureLifecycleReceiverRegistered(appContext)
            ensureSharedPrefsListener(appContext)
        } else {
            resultCard.cleanup()
            resultCardOverlayView = null
            showingResultTaskId = null
            windowManager = null
            windowContext = null
        }

        ensureLifecycleReceiverRegistered(appContext)
        ensureSharedPrefsListener(appContext)
    }

    /**
     * 精确关闭指定任务的悬浮窗与光晕。
     */
    fun dismissTask(taskId: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissTask(taskId) }
            return
        }

        // 移交主屏中保持视窗与绑定，直至移交协程显式完成
        if (taskId == activeTakeoverTaskId) {
            OmniLog.i(TAG, "任务正在移交主屏中，暂缓执行 dismissTask taskId=$taskId")
            return
        }

        if (activeTaskId == taskId) {
            dismissActiveOverlay(animate = true)
        }
        if (showingResultTaskId == taskId) {
            dismissedResultTaskIds.add(taskId)
            dismissResultCard()
        }
        if (pendingResultInfo?.first?.taskId == taskId) {
            pendingResultInfo = null
        }
    }

    /**
     * 关闭所有浮层窗口并释放资源。
     */
    fun dismissAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissAll() }
            return
        }
        takeoverJob?.cancel()
        takeoverJob = null
        activeTakeoverTaskId = null
        pendingResultInfo = null
        overlayGeometry = TaskGeometry()
        snapAnimator?.cancel()
        snapAnimator = null
        dismissActiveOverlay(animate = false)
        dismissResultCard()
        dismissedResultTaskIds.clear()
        seenScreenTaskIds.clear()
        checkUnregisterLifecycleReceiver()
    }

    private fun isCancelledStatus(status: String): Boolean {
        return when (status.lowercase()) {
            "cancelled", "canceled", "user_cancelled", "interrupted" -> true
            else -> false
        }
    }

    private fun handleTakeoverToMainDisplay(
        context: Context,
        taskId: String,
        sessionId: String,
        turnId: String?,
        stopToken: String?,
        displayId: Int
    ) {
        if (sessionId.isEmpty() || displayId <= 0) {
            topOverlayView?.showToastOrStatus("当前屏幕不支持移交")
            return
        }

        if (takeoverJob?.isActive == true || activeTakeoverTaskId != null) {
            OmniLog.w(TAG, "主屏移交已在执行中，忽略重复触发 taskId=$taskId")
            return
        }

        activeTakeoverTaskId = taskId
        topOverlayView?.setTakeoverInProgress(true)
        topOverlayView?.showToastOrStatus("正在准备移交主屏...")

        val currentJob = overlayScope.launch {
            try {
                val controller = VirtualDisplayTakeoverController(context)
                val success = controller.present(
                    sessionId = sessionId,
                    displayId = displayId,
                    taskId = taskId,
                    turnId = turnId,
                    stopToken = stopToken,
                    isBindingCurrent = {
                        activeTaskId == taskId && activeTakeoverTaskId == taskId
                    },
                    onStatus = { status ->
                        if (takeoverJob === coroutineContext[Job]) {
                            topOverlayView?.showToastOrStatus(status)
                        }
                    }
                )

                if (takeoverJob === coroutineContext[Job] && activeTakeoverTaskId == taskId) {
                    if (success) {
                        OmniLog.i(TAG, "主屏移交成功，关闭自身浮层 taskId=$taskId")
                        activeTakeoverTaskId = null
                        dismissActiveOverlay(animate = true)
                    } else {
                        OmniLog.w(TAG, "主屏移交未成功完成，保留预览视窗 taskId=$taskId")
                        topOverlayView?.setTakeoverInProgress(false)
                    }
                }
            } catch (ce: CancellationException) {
                OmniLog.i(TAG, "主屏移交协程被取消: ${ce.message}")
                if (takeoverJob === coroutineContext[Job]) {
                    topOverlayView?.setTakeoverInProgress(false)
                }
            } catch (t: Throwable) {
                OmniLog.e(TAG, "主屏移交异常: ${t.message}", t)
                if (takeoverJob === coroutineContext[Job]) {
                    topOverlayView?.showToastOrStatus("移交失败: ${t.message}")
                    topOverlayView?.setTakeoverInProgress(false)
                }
            } finally {
                if (takeoverJob === coroutineContext[Job]) {
                    takeoverJob = null
                    if (activeTakeoverTaskId == taskId) {
                        activeTakeoverTaskId = null
                    }
                }
            }
        }
        takeoverJob = currentJob
    }

    private fun handleOpenPip(
        context: Context,
        taskId: String,
        sessionId: String,
        turnId: String?,
        stopToken: String?,
        displayId: Int
    ) {
        try {
            val intent = VirtualDisplayPreviewActivity.createIntent(
                context = context,
                sessionId = sessionId,
                displayId = displayId,
                taskId = taskId,
                turnId = turnId,
                stopToken = stopToken
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            handleCapsuleExpandToggle(false)
        } catch (t: Throwable) {
            OmniLog.e(TAG, "打开画中画预览失败: ${t.message}", t)
        }
    }

    private fun handleCapsuleExpandToggle(expanded: Boolean) {
        setOverlayMode(if (expanded) OverlayDisplayMode.EXPANDED else OverlayDisplayMode.CAPSULE)
    }

    private fun updateTopWindowDimensions(hasMicroLens: Boolean) {
        val view = topOverlayView ?: return
        val ctx = windowContext ?: view.context
        recheckAndClampGeometry(ctx)
    }

    private fun ensureEdgeLightAttached(context: Context) {
        if (edgeLightOverlayView != null) {
            edgeLightOverlayView?.start()
            return
        }
        val wm = ensureWindowManager(context) ?: return
        val edgeView = ScreenTaskEdgeLightView(windowContext ?: context)
        edgeLightOverlayView = edgeView
        val edgeParams = createEdgeLightLayoutParams()
        var addSuccess = false
        runCatching {
            wm.addView(edgeView, edgeParams)
            addSuccess = true
        }.onFailure { e ->
            OmniLog.e(TAG, "添加屏幕边缘流光失败: ${e.message}", e)
        }

        if (addSuccess) {
            edgeLightOverlayView = edgeView
            edgeView.start()
        } else {
            edgeView.stop()
            edgeLightOverlayView = null
            windowManager = null
            windowContext = null
        }
    }

    private fun removeEdgeLight() {
        val edgeView = edgeLightOverlayView ?: return
        edgeLightOverlayView = null
        edgeView.stop()
        runCatching {
            windowManager?.removeView(edgeView)
        }
    }

    private fun dismissActiveOverlay(animate: Boolean) {
        val topView = topOverlayView
        topOverlayView = null
        activeTaskId = null
        activeSessionId = null
        activeTurnId = null
        activeStopToken = null
        activeDisplayId = null
        isExpanded = false
        overlayGeometry = TaskGeometry()
        snapAnimator?.cancel()
        snapAnimator = null

        topView?.cleanup()
        if (topView != null) {
            runCatching { windowManager?.removeView(topView) }
        }

        removeEdgeLight()
        checkUnregisterLifecycleReceiver()
    }

    private fun dismissResultCard() {
        val resultView = resultCardOverlayView ?: return
        val taskId = showingResultTaskId
        resultCardOverlayView = null
        showingResultTaskId = null
        resultView.cleanup()
        runCatching { windowManager?.removeView(resultView) }
        checkUnregisterLifecycleReceiver()
        if (!taskId.isNullOrBlank()) {
            runCatching { TaskRuntime.clearResultPresentation(taskId) }
        }
    }

    internal fun getShowingResultTaskId(): String? = showingResultTaskId
    internal fun getResultCardView(): ScreenTaskResultCardView? = resultCardOverlayView
    internal fun getTopOverlayView(): ScreenTaskCapsuleView? = topOverlayView
    internal fun getEdgeLightOverlayView(): ScreenTaskEdgeLightView? = edgeLightOverlayView
    internal fun getActiveDisplayId(): Int? = activeDisplayId
    internal fun getActiveTaskId(): String? = activeTaskId
    internal fun isCapsuleExpanded(): Boolean = isExpanded

    fun setPreviewActivityForeground(foreground: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setPreviewActivityForeground(foreground) }
            return
        }
        isPreviewActivityForeground = foreground
        if (foreground) {
            topOverlayView?.visibility = View.GONE
            topOverlayView?.onScreenOff()
        } else {
            val ctx = windowContext
            if (activeTaskId != null && ctx != null && !KeyguardStateMonitor.isKeyguardLocked(ctx)) {
                topOverlayView?.visibility = View.VISIBLE
                recheckAndClampGeometry(ctx)
                topOverlayView?.setOverlayMode(overlayGeometry.mode)
            }
        }
    }

    private fun ensureWindowManager(context: Context): WindowManager? {
        if (windowManager != null && windowContext != null) {
            return windowManager
        }

        val appContext = context.applicationContext ?: context
        // 稳定主路径：已具备系统悬浮窗权限时，明确优先使用 Application Overlay，与 AgentBrowserOverlayHost 保持一致
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)) {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                windowManager = wm
                windowContext = appContext
                windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                isTrustedOverlay = false
                return wm
            }
        }

        // 次级路径：未授权系统悬浮窗时，尝试复用已有可信无障碍宿主
        val handle = AndroidGuiOverlayHost.resolve(appContext)
        if (handle.trusted) {
            val wm = handle.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                windowManager = wm
                windowContext = handle.context
                windowType = handle.windowType
                isTrustedOverlay = true
                return wm
            }
        }

        OmniLog.w(TAG, "既无系统悬浮窗权限亦无可信无障碍宿主，跳过浮层挂载并保持通知入口")
        return null
    }
    fun getSafeInsets(context: Context, anchorView: View? = topOverlayView): Rect {
        val margin = dp(context, 8)
        var left = margin
        var top = getStatusBarHeight(context) + margin
        var right = margin
        var bottom = getNavigationBarHeight(context) + margin

        val insets = anchorView?.rootWindowInsets
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                left = maxOf(left, systemBars.left + margin)
                top = maxOf(top, systemBars.top + margin)
                right = maxOf(right, systemBars.right + margin)
                bottom = maxOf(bottom, systemBars.bottom + margin)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val cutout = insets.displayCutout
                if (cutout != null) {
                    left = maxOf(left, cutout.safeInsetLeft + margin)
                    top = maxOf(top, cutout.safeInsetTop + margin)
                    right = maxOf(right, cutout.safeInsetRight + margin)
                    bottom = maxOf(bottom, cutout.safeInsetBottom + margin)
                }
            }
        }
        return Rect(left, top, right, bottom)
    }

    private fun createTopLayoutParams(hasMicroLens: Boolean, mode: OverlayDisplayMode): WindowManager.LayoutParams {
        val ctx = windowContext ?: return WindowManager.LayoutParams()
        val dm = ctx.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val screenWidthDp = screenWidth / dm.density
        val safeInsets = getSafeInsets(ctx)
        val maxWidthAvailableDp = ((screenWidth - safeInsets.left - safeInsets.right) / dm.density).toInt().coerceAtLeast(1)
        val maxAllowedWidthDp = (screenWidthDp - 16f).toInt().coerceAtLeast(1)

        val targetWidth: Int
        val targetHeight: Int
        val initialX: Int
        val initialY: Int

        when (mode) {
            OverlayDisplayMode.CAPSULE -> {
                val defaultWidthDp = if (hasMicroLens) 264 else 248
                targetWidth = dp(ctx, minOf(defaultWidthDp, maxAllowedWidthDp))
                targetHeight = WindowManager.LayoutParams.WRAP_CONTENT
                initialX = if (overlayGeometry.capsuleX >= 0) overlayGeometry.capsuleX else (screenWidth - targetWidth) / 2
                initialY = if (overlayGeometry.capsuleY >= 0) overlayGeometry.capsuleY else safeInsets.top
            }
            OverlayDisplayMode.EXPANDED -> {
                val minWidthDp = minOf(296, maxWidthAvailableDp)
                val stageWidthDp = if (maxWidthAvailableDp <= minWidthDp) {
                    maxWidthAvailableDp
                } else {
                    overlayGeometry.stageWidthDp.coerceIn(minWidthDp, maxWidthAvailableDp)
                }
                targetWidth = dp(ctx, stageWidthDp)
                targetHeight = WindowManager.LayoutParams.WRAP_CONTENT
                initialX = if (overlayGeometry.stageX >= 0) overlayGeometry.stageX else (screenWidth - targetWidth) / 2
                initialY = if (overlayGeometry.stageY >= 0) overlayGeometry.stageY else safeInsets.top
            }
            OverlayDisplayMode.DOCKED -> {
                targetWidth = dp(ctx, 48)
                targetHeight = dp(ctx, 48)
                initialX = if (overlayGeometry.dockedX >= 0) overlayGeometry.dockedX else (screenWidth - targetWidth - safeInsets.right)
                initialY = if (overlayGeometry.dockedY >= 0) overlayGeometry.dockedY else safeInsets.top + dp(ctx, 60)
            }
        }

        val estimatedHeight = when (mode) {
            OverlayDisplayMode.EXPANDED -> dp(ctx, 136 + overlayGeometry.stageCanvasHeightDp)
            OverlayDisplayMode.CAPSULE -> dp(ctx, 48)
            OverlayDisplayMode.DOCKED -> dp(ctx, 48)
        }
        val minX = safeInsets.left
        val maxX = (screenWidth - targetWidth - safeInsets.right).coerceAtLeast(minX)
        val minY = safeInsets.top
        val maxY = (screenHeight - estimatedHeight - safeInsets.bottom).coerceAtLeast(minY)

        val clampedX = initialX.coerceIn(minX, maxX)
        val clampedY = initialY.coerceIn(minY, maxY)

        return WindowManager.LayoutParams().apply {
            type = windowType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            gravity = Gravity.TOP or Gravity.START
            x = clampedX
            y = clampedY
            width = targetWidth
            height = targetHeight
        }
    }

    private fun createTopLayoutParams(hasMicroLens: Boolean, expanded: Boolean): WindowManager.LayoutParams {
        return createTopLayoutParams(hasMicroLens, if (expanded) OverlayDisplayMode.EXPANDED else OverlayDisplayMode.CAPSULE)
    }

    fun setOverlayMode(mode: OverlayDisplayMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { setOverlayMode(mode) }
            return
        }
        overlayGeometry.mode = mode
        isExpanded = (mode == OverlayDisplayMode.EXPANDED)

        val view = topOverlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val ctx = windowContext ?: view.context
        val dm = ctx.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val safeInsets = getSafeInsets(ctx, view)
        val maxWidthAvailableDp = ((screenWidth - safeInsets.left - safeInsets.right) / dm.density).toInt().coerceAtLeast(1)
        val maxAllowedWidthDp = ((screenWidth / dm.density) - 16f).toInt().coerceAtLeast(1)

        val targetWidth: Int
        val targetHeight: Int
        var targetX: Int
        var targetY: Int

        when (mode) {
            OverlayDisplayMode.CAPSULE -> {
                val hasMicroLens = (activeDisplayId ?: 0) > 0
                val defaultWidthDp = if (hasMicroLens) 264 else 248
                targetWidth = dp(ctx, minOf(defaultWidthDp, maxAllowedWidthDp))
                targetHeight = WindowManager.LayoutParams.WRAP_CONTENT
                targetX = if (overlayGeometry.capsuleX >= 0) overlayGeometry.capsuleX else (screenWidth - targetWidth) / 2
                targetY = if (overlayGeometry.capsuleY >= 0) overlayGeometry.capsuleY else safeInsets.top
            }
            OverlayDisplayMode.EXPANDED -> {
                val minWidthDp = minOf(296, maxWidthAvailableDp)
                val stageWidthDp = if (maxWidthAvailableDp <= minWidthDp) {
                    maxWidthAvailableDp
                } else {
                    overlayGeometry.stageWidthDp.coerceIn(minWidthDp, maxWidthAvailableDp)
                }
                targetWidth = dp(ctx, stageWidthDp)
                targetHeight = WindowManager.LayoutParams.WRAP_CONTENT
                targetX = if (overlayGeometry.stageX >= 0) overlayGeometry.stageX else (screenWidth - targetWidth) / 2
                targetY = if (overlayGeometry.stageY >= 0) overlayGeometry.stageY else safeInsets.top
            }
            OverlayDisplayMode.DOCKED -> {
                targetWidth = dp(ctx, 48)
                targetHeight = dp(ctx, 48)
                targetX = if (overlayGeometry.dockedX >= 0) overlayGeometry.dockedX else (screenWidth - targetWidth - safeInsets.right)
                targetY = if (overlayGeometry.dockedY >= 0) overlayGeometry.dockedY else safeInsets.top + dp(ctx, 60)
            }
        }

        val estimatedHeight = when (mode) {
            OverlayDisplayMode.EXPANDED -> dp(ctx, 136 + overlayGeometry.stageCanvasHeightDp)
            OverlayDisplayMode.CAPSULE -> dp(ctx, 48)
            OverlayDisplayMode.DOCKED -> dp(ctx, 48)
        }
        val minX = safeInsets.left
        val maxX = (screenWidth - targetWidth - safeInsets.right).coerceAtLeast(minX)
        val minY = safeInsets.top
        val maxY = (screenHeight - estimatedHeight - safeInsets.bottom).coerceAtLeast(minY)

        targetX = targetX.coerceIn(minX, maxX)
        targetY = targetY.coerceIn(minY, maxY)

        params.width = targetWidth
        params.height = targetHeight
        params.x = targetX
        params.y = targetY

        view.setOverlayMode(mode)
        runCatching { wm.updateViewLayout(view, params) }

        // 单次 post 测量实际高度进行 clamp，避免底部展开越界
        view.post {
            val measuredH = view.height
            if (measuredH > 0 && topOverlayView === view) {
                val currentParams = view.layoutParams as? WindowManager.LayoutParams ?: return@post
                val currentSafeInsets = getSafeInsets(view.context, view)
                val currentMinY = currentSafeInsets.top
                val currentMaxY = (dm.heightPixels - measuredH - currentSafeInsets.bottom).coerceAtLeast(currentMinY)
                if (currentParams.y > currentMaxY) {
                    currentParams.y = currentMaxY
                    if (overlayGeometry.mode == OverlayDisplayMode.EXPANDED) {
                        overlayGeometry.stageY = currentMaxY
                    } else if (overlayGeometry.mode == OverlayDisplayMode.CAPSULE) {
                        overlayGeometry.capsuleY = currentMaxY
                    }
                    runCatching { wm.updateViewLayout(view, currentParams) }
                }
            }
        }
    }

    fun getWindowPosition(): Pair<Int, Int> {
        val params = topOverlayView?.layoutParams as? WindowManager.LayoutParams
        return Pair(params?.x ?: 0, params?.y ?: 0)
    }

    fun getSavedCanvasHeightDp(): Int = overlayGeometry.stageCanvasHeightDp

    fun moveWindowBy(dx: Float, dy: Float) {
        snapAnimator?.cancel()
        val view = topOverlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val ctx = windowContext ?: view.context
        val dm = ctx.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val safeInsets = getSafeInsets(ctx)

        val viewWidth = params.width
        val viewHeight = view.measuredHeight.takeIf { it > 0 } ?: params.height
        val minX = safeInsets.left
        val maxX = (screenWidth - viewWidth - safeInsets.right).coerceAtLeast(minX)
        val minY = safeInsets.top
        val maxY = (screenHeight - viewHeight - safeInsets.bottom).coerceAtLeast(minY)

        params.x = (params.x + dx).toInt().coerceIn(minX, maxX)
        params.y = (params.y + dy).toInt().coerceIn(minY, maxY)

        when (overlayGeometry.mode) {
            OverlayDisplayMode.CAPSULE -> {
                overlayGeometry.capsuleX = params.x
                overlayGeometry.capsuleY = params.y
            }
            OverlayDisplayMode.EXPANDED -> {
                overlayGeometry.stageX = params.x
                overlayGeometry.stageY = params.y
            }
            OverlayDisplayMode.DOCKED -> {
                overlayGeometry.dockedX = params.x
                overlayGeometry.dockedY = params.y
            }
        }

        runCatching { wm.updateViewLayout(view, params) }
    }

    fun snapWindow(mode: OverlayDisplayMode) {
        val view = topOverlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val ctx = windowContext ?: view.context
        val dm = ctx.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val safeInsets = getSafeInsets(ctx)

        val currentX = params.x
        val currentY = params.y
        val viewWidth = params.width
        val viewHeight = view.measuredHeight.takeIf { it > 0 } ?: params.height

        val minX = safeInsets.left
        val maxX = (screenWidth - viewWidth - safeInsets.right).coerceAtLeast(minX)
        val minY = safeInsets.top
        val maxY = (screenHeight - viewHeight - safeInsets.bottom).coerceAtLeast(minY)

        val targetX: Int
        val targetY: Int = currentY.coerceIn(minY, maxY)

        when (mode) {
            OverlayDisplayMode.CAPSULE -> {
                val travelRange = maxX - minX
                val centerX = currentX + viewWidth / 2
                targetX = if (travelRange > 0) {
                    val ratio = (currentX - minX).toFloat() / travelRange
                    when {
                        ratio < 0.35f || centerX < screenWidth * 0.35f -> minX
                        ratio > 0.65f || centerX > screenWidth * 0.65f -> maxX
                        else -> ((screenWidth - viewWidth) / 2).coerceIn(minX, maxX)
                    }
                } else {
                    minX
                }
                overlayGeometry.capsuleX = targetX
                overlayGeometry.capsuleY = targetY
            }
            OverlayDisplayMode.EXPANDED -> {
                val snapMargin = dp(ctx, 16)
                targetX = when {
                    currentX - minX < snapMargin -> minX
                    maxX - currentX < snapMargin -> maxX
                    else -> currentX.coerceIn(minX, maxX)
                }
                overlayGeometry.stageX = targetX
                overlayGeometry.stageY = targetY
            }
            OverlayDisplayMode.DOCKED -> {
                val centerX = currentX + viewWidth / 2
                targetX = if (centerX < screenWidth / 2) minX else maxX
                overlayGeometry.dockedX = targetX
                overlayGeometry.dockedY = targetY
            }
        }

        val startX = currentX
        val startY = currentY
        if (isReducedMotion(ctx) || (startX == targetX && startY == targetY)) {
            params.x = targetX
            params.y = targetY
            runCatching { wm.updateViewLayout(view, params) }
        } else {
            snapAnimator?.cancel()
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180L
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { va ->
                    val fraction = va.animatedFraction
                    params.x = (startX + (targetX - startX) * fraction).toInt()
                    params.y = (startY + (targetY - startY) * fraction).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }
            snapAnimator = animator
            animator.start()
        }
    }

    fun resizeStage(newWidthPx: Int, newCanvasHeightPx: Int) {
        snapAnimator?.cancel()
        val view = topOverlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val ctx = windowContext ?: view.context
        val dm = ctx.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val safeInsets = getSafeInsets(ctx, view)

        val maxWidthPx = (screenWidth - safeInsets.left - safeInsets.right).coerceAtLeast(1)
        val minWidthPx = minOf(dp(ctx, 296), maxWidthPx)
        val clampedWidth = newWidthPx.coerceIn(minWidthPx, maxWidthPx)

        params.width = clampedWidth
        val maxX = (screenWidth - clampedWidth - safeInsets.right).coerceAtLeast(safeInsets.left)
        if (params.x > maxX) {
            params.x = maxX
        }

        val density = dm.density
        overlayGeometry.stageWidthDp = (clampedWidth / density).toInt()
        overlayGeometry.stageCanvasHeightDp = (newCanvasHeightPx / density).toInt()
        overlayGeometry.stageX = params.x

        runCatching { wm.updateViewLayout(view, params) }
    }

    fun recheckAndClampGeometry(context: Context) {
        val view = topOverlayView ?: return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = context.resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels
        val safeInsets = getSafeInsets(context, view)

        val screenWidthDp = screenWidth / dm.density
        val maxWidthAvailableDp = ((screenWidth - safeInsets.left - safeInsets.right) / dm.density).toInt().coerceAtLeast(1)
        val maxAllowedWidthDp = (screenWidthDp - 16f).toInt().coerceAtLeast(1)
        val minWidthDp = minOf(296, maxWidthAvailableDp)
        val stageWidthDp = if (maxWidthAvailableDp <= minWidthDp) {
            maxWidthAvailableDp
        } else {
            overlayGeometry.stageWidthDp.coerceIn(minWidthDp, maxWidthAvailableDp)
        }
        overlayGeometry.stageWidthDp = stageWidthDp

        val mode = overlayGeometry.mode
        val targetWidth = when (mode) {
            OverlayDisplayMode.CAPSULE -> {
                val hasMicroLens = (activeDisplayId ?: 0) > 0
                val defaultWidthDp = if (hasMicroLens) 264 else 248
                dp(context, minOf(defaultWidthDp, maxAllowedWidthDp))
            }
            OverlayDisplayMode.EXPANDED -> dp(context, stageWidthDp)
            OverlayDisplayMode.DOCKED -> dp(context, 48)
        }
        val targetHeight = if (mode == OverlayDisplayMode.DOCKED) dp(context, 48) else WindowManager.LayoutParams.WRAP_CONTENT

        if (mode == OverlayDisplayMode.EXPANDED) {
            view.adaptCanvasHeight()
        }

        params.width = targetWidth
        params.height = targetHeight

        val minX = safeInsets.left
        val maxX = (screenWidth - targetWidth - safeInsets.right).coerceAtLeast(minX)
        val minY = safeInsets.top
        val measuredHeight = view.measuredHeight.takeIf { it > 0 } ?: dp(context, 60)
        val maxY = (screenHeight - (if (targetHeight > 0) targetHeight else measuredHeight) - safeInsets.bottom).coerceAtLeast(minY)

        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)
        when (mode) {
            OverlayDisplayMode.CAPSULE -> {
                overlayGeometry.capsuleX = params.x
                overlayGeometry.capsuleY = params.y
            }
            OverlayDisplayMode.EXPANDED -> {
                overlayGeometry.stageX = params.x
                overlayGeometry.stageY = params.y
            }
            OverlayDisplayMode.DOCKED -> {
                overlayGeometry.dockedX = params.x
                overlayGeometry.dockedY = params.y
            }
        }

        runCatching { wm.updateViewLayout(view, params) }
    }

    internal fun getOverlayMode(): OverlayDisplayMode = overlayGeometry.mode
    internal fun getTaskGeometry(): TaskGeometry = overlayGeometry

    private fun createEdgeLightLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams().apply {
            type = windowType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (!isTrustedOverlay) {
                // 非可信 overlay 严格限制透明度 <= 0.6，避免触发 Android 遮挡触摸阻断
                alpha = 0.55f
            }
            gravity = Gravity.FILL
            x = 0
            y = 0
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
    }

    private fun createResultLayoutParams(): WindowManager.LayoutParams {
        val ctx = windowContext ?: return WindowManager.LayoutParams()
        return WindowManager.LayoutParams().apply {
            type = windowType
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = getNavigationBarHeight(ctx) + dp(ctx, 10)
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(context, 24)
    }

    private fun getNavigationBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(context, 16)
    }

    private fun ensureLifecycleReceiverRegistered(context: Context) {
        if (lifecycleReceiver != null) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val appContext = ctx?.applicationContext ?: return
                when (action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        OmniLog.i(TAG, "屏幕关闭，收回悬浮窗并暂停渲染")
                        topOverlayView?.visibility = View.GONE
                        topOverlayView?.onScreenOff()
                        edgeLightOverlayView?.stop()
                        resultCardOverlayView?.visibility = View.GONE
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        OmniLog.i(TAG, "用户解锁屏幕，恢复浮层状态")
                        topOverlayView?.visibility = View.VISIBLE
                        resultCardOverlayView?.visibility = View.VISIBLE
                        val pending = pendingResultInfo
                        if (pending != null) {
                            pendingResultInfo = null
                            showResult(appContext, pending.first, pending.second, pending.third)
                        } else {
                            sync(appContext, lastSyncedTasks)
                        }
                        recheckAndClampGeometry(appContext)
                        topOverlayView?.setOverlayMode(overlayGeometry.mode)
                    }
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        OmniLog.i(TAG, "屏幕配置变更，自适应更新浮层布局与主题")
                        topOverlayView?.onConfigChanged()
                        edgeLightOverlayView?.onConfigChanged()
                        resultCardOverlayView?.onConfigChanged()
                        recheckAndClampGeometry(appContext)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }

        try {
            context.applicationContext.registerReceiver(receiver, filter)
            lifecycleReceiver = receiver
        } catch (t: Throwable) {
            OmniLog.w(TAG, "注册生命周期广播接收器失败: ${t.message}")
        }
    }

    private fun ensureSharedPrefsListener(context: Context) {
        if (sharedPrefsListener != null) return
        try {
            val prefs = context.applicationContext.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            flutterSharedPrefs = prefs
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == "flutter.theme_option" || key == "flutter.liquid_glass_enabled") {
                    mainHandler.post {
                        OmniLog.i(TAG, "主题或玻璃效果配置变更 key=$key，刷新浮层视图与按钮")
                        topOverlayView?.onConfigChanged()
                        edgeLightOverlayView?.onConfigChanged()
                        resultCardOverlayView?.onConfigChanged()
                    }
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            sharedPrefsListener = listener
        } catch (t: Throwable) {
            OmniLog.w(TAG, "注册主题配置监听失败: ${t.message}")
        }
    }

    private fun checkUnregisterSharedPrefsListener() {
        if (topOverlayView == null && edgeLightOverlayView == null && resultCardOverlayView == null && pendingResultInfo == null) {
            val listener = sharedPrefsListener ?: return
            sharedPrefsListener = null
            try {
                flutterSharedPrefs?.unregisterOnSharedPreferenceChangeListener(listener)
            } catch (t: Throwable) {
                OmniLog.w(TAG, "注销主题配置监听异常: ${t.message}")
            }
            flutterSharedPrefs = null
        }
    }

    private fun checkUnregisterLifecycleReceiver() {
        if (topOverlayView == null && edgeLightOverlayView == null && resultCardOverlayView == null && pendingResultInfo == null) {
            val receiver = lifecycleReceiver
            if (receiver != null) {
                lifecycleReceiver = null
                try {
                    windowContext?.applicationContext?.unregisterReceiver(receiver)
                } catch (t: Throwable) {
                    OmniLog.w(TAG, "注销生命周期广播接收器异常: ${t.message}")
                }
            }
            checkUnregisterSharedPrefsListener()
        }
    }
}
