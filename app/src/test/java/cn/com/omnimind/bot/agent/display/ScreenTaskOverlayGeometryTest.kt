package cn.com.omnimind.bot.agent.display

import android.app.Application
import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.provider.Settings
import android.view.ViewConfiguration
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import cn.com.omnimind.bot.task.runtime.TaskExecutionInfo
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskWaitingStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.TimeUnit

/**
 * 屏幕任务悬浮窗几何、手势与形态行为测试。
 * 纯测几何约束、吸附、比例缩放、形态切换与可见帧源激活，不绑定文案。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE, qualifiers = "w412dp-h915dp")
class ScreenTaskOverlayGeometryTest {

    private lateinit var context: Context
    private var originalAnimationScale = 1f
    private val touchActivities = mutableListOf<ActivityController<Activity>>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        originalAnimationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsInteractive(true)
        ShadowSettings.setCanDrawOverlays(true)
        TaskRuntime.resetForTesting()
        ScreenTaskOverlayHost.dismissAll()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, originalAnimationScale)
        touchActivities.forEach { it.pause().stop().destroy() }
        touchActivities.clear()
        ScreenTaskOverlayHost.dismissAll()
        TaskRuntime.resetForTesting()
        ShadowLooper.idleMainLooper()
    }

    private fun createTaskInfo(
        taskId: String,
        sessionId: String = "session-test",
        displayId: Int = 1,
        isWaiting: Boolean = false
    ): TaskExecutionInfo {
        val convId = 1L
        return TaskExecutionInfo(
            taskId = taskId,
            sessionId = sessionId,
            turnId = "turn-1",
            conversationId = convId,
            startedAt = System.currentTimeMillis(),
            statusText = "执行中",
            screenDisplayId = displayId,
            stopToken = "token-test",
            waitingStatus = if (isWaiting) {
                TaskWaitingStatus.WaitingForManualAuth(conversationId = convId, sessionId = sessionId)
            } else {
                null
            }
        )
    }

    @Test
    fun `new task resets geometry state while same task preserves user geometry`() {
        val task1 = createTaskInfo("task-geo-1")
        TaskRuntime.start(
            context = context,
            taskId = task1.taskId,
            conversationId = task1.conversationId,
            sessionId = task1.sessionId,
            turnId = task1.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task1.sessionId.orEmpty(),
            turnId = task1.turnId,
            displayId = task1.screenDisplayId ?: 0,
        )

        // 1. 同步初始任务
        ScreenTaskOverlayHost.sync(context, listOf(task1))
        ShadowLooper.idleMainLooper()

        assertEquals("初始形态应为胶囊态", OverlayDisplayMode.CAPSULE, ScreenTaskOverlayHost.getOverlayMode())
        val initialStageWidthDp = ScreenTaskOverlayHost.getTaskGeometry().stageWidthDp

        // 2. 模拟用户交互：展开面板、拖动位置并缩放尺寸
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.EXPANDED)
        ScreenTaskOverlayHost.moveWindowBy(40f, 60f)
        ScreenTaskOverlayHost.resizeStage(newWidthPx = dp(context, 340), newCanvasHeightPx = dp(context, 380))

        val movedPosition = ScreenTaskOverlayHost.getWindowPosition()
        val resizedStageWidthDp = ScreenTaskOverlayHost.getTaskGeometry().stageWidthDp
        assertEquals(OverlayDisplayMode.EXPANDED, ScreenTaskOverlayHost.getOverlayMode())
        assertTrue("调整尺寸必须改变面板宽度", resizedStageWidthDp > initialStageWidthDp)

        // 3. 同一任务多 tick sync：不得覆盖用户的拖拽与缩放状态
        ScreenTaskOverlayHost.sync(context, listOf(task1))
        ShadowLooper.idleMainLooper()

        assertEquals("同身份持续 sync 必须保留展开态", OverlayDisplayMode.EXPANDED, ScreenTaskOverlayHost.getOverlayMode())
        assertEquals("同身份持续 sync 必须保留位置", movedPosition, ScreenTaskOverlayHost.getWindowPosition())
        assertEquals("同身份持续 sync 必须保留缩放尺寸", resizedStageWidthDp, ScreenTaskOverlayHost.getTaskGeometry().stageWidthDp)

        // 4. 新任务到达：几何状态必须重置回初始默认
        TaskRuntime.finish(context, task1.taskId)
        val task2 = createTaskInfo("task-geo-2")
        TaskRuntime.start(
            context = context,
            taskId = task2.taskId,
            conversationId = task2.conversationId,
            sessionId = task2.sessionId,
            turnId = task2.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task2.sessionId.orEmpty(),
            turnId = task2.turnId,
            displayId = task2.screenDisplayId ?: 0,
        )

        ScreenTaskOverlayHost.sync(context, listOf(task2))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        assertEquals("新任务必须重置为默认胶囊态", OverlayDisplayMode.CAPSULE, ScreenTaskOverlayHost.getOverlayMode())
        val resetGeometry = ScreenTaskOverlayHost.getTaskGeometry()
        assertEquals("新任务必须重置回初始默认宽度", initialStageWidthDp, resetGeometry.stageWidthDp)
        assertNotEquals("新任务绝不得继承用户缩放后的宽度", resizedStageWidthDp, resetGeometry.stageWidthDp)
        val newPosition = ScreenTaskOverlayHost.getWindowPosition()
        assertNotEquals("新任务绝不得继承旧任务的移动位置", movedPosition, newPosition)
    }

    @Test
    fun `safe insets clamping prevents window from moving offscreen`() {
        val task = createTaskInfo("task-safe-insets")
        TaskRuntime.start(
            context = context,
            taskId = task.taskId,
            conversationId = task.conversationId,
            sessionId = task.sessionId,
            turnId = task.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task.sessionId.orEmpty(),
            turnId = task.turnId,
            displayId = task.screenDisplayId ?: 0,
        )
        ScreenTaskOverlayHost.sync(context, listOf(task))
        ShadowLooper.idleMainLooper()

        val safeInsets = ScreenTaskOverlayHost.getSafeInsets(context)
        val dm = context.resources.displayMetrics

        // 尝试拖出左边界和上边界
        ScreenTaskOverlayHost.moveWindowBy(-5000f, -5000f)
        val posMin = ScreenTaskOverlayHost.getWindowPosition()
        assertTrue("左边界不得小于安全边距", posMin.first >= safeInsets.left)
        assertTrue("上边界不得小于安全边距", posMin.second >= safeInsets.top)

        // 尝试拖出右边界和下边界
        ScreenTaskOverlayHost.moveWindowBy(5000f, 5000f)
        val posMax = ScreenTaskOverlayHost.getWindowPosition()
        assertTrue("右侧不得越过屏幕边界", posMax.first <= dm.widthPixels - safeInsets.right)
        assertTrue("底部不得越过屏幕边界", posMax.second <= dm.heightPixels - safeInsets.bottom)
    }

    @Test
    fun `snapping behavior anchors capsule and docked handle correctly`() {
        val task = createTaskInfo("task-snap")
        TaskRuntime.start(
            context = context,
            taskId = task.taskId,
            conversationId = task.conversationId,
            sessionId = task.sessionId,
            turnId = task.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task.sessionId.orEmpty(),
            turnId = task.turnId,
            displayId = task.screenDisplayId ?: 0,
        )
        ScreenTaskOverlayHost.sync(context, listOf(task))
        ShadowLooper.idleMainLooper()

        val topView = ScreenTaskOverlayHost.getTopOverlayView()!!
        val topLp = topView.layoutParams as android.view.WindowManager.LayoutParams
        val viewContext = topView.context
        val viewMetrics = viewContext.resources.displayMetrics
        val viewSafeInsets = ScreenTaskOverlayHost.getSafeInsets(viewContext, topView)

        // 胶囊靠左侧释放：应吸附至左安全边界
        ScreenTaskOverlayHost.moveWindowBy(-3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.CAPSULE)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val posLeft = ScreenTaskOverlayHost.getWindowPosition()
        assertEquals(viewSafeInsets.left, posLeft.first)

        // 胶囊靠右侧释放：应吸附至右安全边界
        ScreenTaskOverlayHost.moveWindowBy(3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.CAPSULE)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val posRight = ScreenTaskOverlayHost.getWindowPosition()
        val expectedRight = (viewMetrics.widthPixels - topLp.width - viewSafeInsets.right).coerceAtLeast(viewSafeInsets.left)
        assertEquals("胶囊右侧吸附应达到右安全边界 (metrics: W=${viewMetrics.widthPixels}, lpW=${topLp.width}, insets=$viewSafeInsets)", expectedRight, posRight.first)

        // 贴边折叠态 (DOCKED, 48dp)：左半屏吸附左侧，右半屏吸附右侧
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.DOCKED)
        val dockedLp = topView.layoutParams as android.view.WindowManager.LayoutParams
        val dockedSafeInsets = ScreenTaskOverlayHost.getSafeInsets(viewContext, topView)

        ScreenTaskOverlayHost.moveWindowBy(-3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.DOCKED)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val dockedLeft = ScreenTaskOverlayHost.getWindowPosition()
        assertEquals(dockedSafeInsets.left, dockedLeft.first)

        ScreenTaskOverlayHost.moveWindowBy(3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.DOCKED)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val dockedRight = ScreenTaskOverlayHost.getWindowPosition()
        val expectedDockRight = (viewMetrics.widthPixels - dockedLp.width - dockedSafeInsets.right).coerceAtLeast(dockedSafeInsets.left)
        assertEquals("侧边入口右侧吸附应达到右安全边界", expectedDockRight, dockedRight.first)
    }
    @Test
    fun `frame views activate only when visible and edge lights stop on waiting`() {
        val task = createTaskInfo("task-visibility", displayId = 2, isWaiting = false)
        TaskRuntime.start(
            context = context,
            taskId = task.taskId,
            conversationId = task.conversationId,
            sessionId = task.sessionId,
            turnId = task.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task.sessionId.orEmpty(),
            turnId = task.turnId,
            displayId = task.screenDisplayId ?: 0,
        )
        ScreenTaskOverlayHost.sync(context, listOf(task))
        ShadowLooper.idleMainLooper()

        val topView = ScreenTaskOverlayHost.getTopOverlayView()
        assertNotNull(topView)

        val lensEdgeLight = topView!!.getLensEdgeLight()
        val canvasEdgeLight = topView.getCanvasEdgeLight()

        // 1. 胶囊态：仅微缩镜头帧源激活，且运行光晕
        assertEquals(OverlayDisplayMode.CAPSULE, topView.getOverlayMode())
        assertTrue("胶囊态下微缩镜头光晕应当启动", lensEdgeLight.isRunning())
        assertFalse("胶囊态下画布光晕不应启动", canvasEdgeLight.isRunning())

        // 2. 展开态：仅画布帧源激活，微缩镜头光晕停止，画布光晕启动
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.EXPANDED)
        ShadowLooper.idleMainLooper()
        assertFalse("展开态下微缩镜头光晕应当停止", lensEdgeLight.isRunning())
        assertTrue("展开态下画布光晕应当启动", canvasEdgeLight.isRunning())

        // 3. 贴边折叠态：两者均停止帧绘制与光晕
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.DOCKED)
        ShadowLooper.idleMainLooper()
        assertFalse("贴边态下微缩镜头光晕应当停止", lensEdgeLight.isRunning())
        assertFalse("贴边态下画布光晕应当停止", canvasEdgeLight.isRunning())

        // 4. 等待确认态：所有光晕必须显式停止
        val waitingTask = createTaskInfo("task-visibility", displayId = 2, isWaiting = true)
        TaskRuntime.updateWaitingStatus(
            context = context,
            conversationId = waitingTask.conversationId ?: 1L,
            waitingStatus = waitingTask.waitingStatus
        )
        ScreenTaskOverlayHost.sync(context, listOf(waitingTask))
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.EXPANDED)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse("等待确认态下画布光晕必须停止", canvasEdgeLight.isRunning())
        assertFalse("等待确认态下镜头光晕必须停止", lensEdgeLight.isRunning())
    }

    @Test
    fun `canvas performClick triggers pip and long click does not trigger stop or takeover`() {
        var pipOpened = false
        var takenOver = false
        var taskStopped = false

        val capsule = ScreenTaskCapsuleView(context).apply {
            initBinding(
                taskId = "task-canvas-gestures",
                sessionId = "sess-g",
                turnId = "t1",
                stopToken = "token-g",
                displayId = 5,
                statusText = "协同中",
                isWaiting = false,
                onToggleExpand = {},
                onStop = { _, _, _, _ -> taskStopped = true },
                onTakeover = { _, _, _, _, _ -> takenOver = true },
                onOpenPip = { _, _, _, _, _ -> pipOpened = true }
            )
            setOverlayMode(OverlayDisplayMode.EXPANDED)
        }

        val canvas = capsule.getCanvasWrapper()
        assertTrue("画布必须支持点击", canvas.isClickable)
        assertFalse("画布不应设为长按触发移交以防误杀Turn", canvas.isLongClickable)

        // 1. 点按画布：独立全屏观看
        canvas.performClick()
        assertTrue("点按应当触发全屏预览", pipOpened)
        assertFalse("点按绝不触发移交主屏", takenOver)
        assertFalse("点按绝不触发任务停止", taskStopped)

        // 2. 长按画布：绝不触发移交主屏或任务停止
        pipOpened = false
        canvas.performLongClick()
        assertFalse("画布长按绝不可触发移交主屏", takenOver)
        assertFalse("画布长按绝不可触发任务取消", taskStopped)
    }

    @Test
    fun `docked handle and resize handle offer accessibility and toggle without gesture conflict`() {
        val capsule = ScreenTaskCapsuleView(context).apply {
            initBinding(
                taskId = "task-accessibility",
                sessionId = "sess-a",
                turnId = "t1",
                stopToken = "token-a",
                displayId = 3,
                statusText = "执行中",
                isWaiting = false
            )
            setOverlayMode(OverlayDisplayMode.EXPANDED)
        }

        val dockedContainer = capsule.getDockedContainer()
        val resizeHandle = capsule.getResizeHandle()

        assertNotNull(dockedContainer)
        assertNotNull(resizeHandle)

        assertTrue("侧边入口必须可点击", dockedContainer.isClickable)
        assertTrue("缩放手柄必须具备点击切换可访问性", resizeHandle.isClickable)
        assertNotNull("侧边入口必须包含可访问描述", dockedContainer.contentDescription)
        assertNotNull("缩放手柄必须包含可访问描述", resizeHandle.contentDescription)
    }

    @Test
    fun `multi-pointer cancels gesture without jumping or triggering click`() {
        val task = createTaskInfo("task-multi-touch")
        TaskRuntime.start(
            context = context,
            taskId = task.taskId,
            conversationId = task.conversationId,
            sessionId = task.sessionId,
            turnId = task.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task.sessionId.orEmpty(),
            turnId = task.turnId,
            displayId = task.screenDisplayId ?: 0,
        )
        ScreenTaskOverlayHost.sync(context, listOf(task))
        ShadowLooper.idleMainLooper()

        val initialPos = ScreenTaskOverlayHost.getWindowPosition()
        assertEquals(OverlayDisplayMode.CAPSULE, ScreenTaskOverlayHost.getOverlayMode())

        val view = ScreenTaskOverlayHost.getTopOverlayView()!!
        val downTime = 1000L

        // 1. 首指按下
        val downEvent = android.view.MotionEvent.obtain(
            downTime, downTime, android.view.MotionEvent.ACTION_DOWN,
            100f, 100f, 0
        )
        view.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        // 2. 第二指按下 (ACTION_POINTER_DOWN)
        val pointerDownEvent = android.view.MotionEvent.obtain(
            downTime, downTime + 10, android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f, 200f, 0
        )
        view.dispatchTouchEvent(pointerDownEvent)
        pointerDownEvent.recycle()

        // 3. 抬手
        val upEvent = android.view.MotionEvent.obtain(
            downTime, downTime + 20, android.view.MotionEvent.ACTION_UP,
            100f, 100f, 0
        )
        view.dispatchTouchEvent(upEvent)
        upEvent.recycle()
        ShadowLooper.idleMainLooper()

        // 验证：多指触发取消，既不跳动窗口，亦不误触展开
        assertEquals("多指触控下严禁误触展开", OverlayDisplayMode.CAPSULE, ScreenTaskOverlayHost.getOverlayMode())
        assertEquals("多指触控下严禁跳跃窗口坐标", initialPos, ScreenTaskOverlayHost.getWindowPosition())
    }

    @Test
    fun `collapse and reopen preserves user resized canvas height`() {
        val task = createTaskInfo("task-resize-height")
        TaskRuntime.start(
            context = context,
            taskId = task.taskId,
            conversationId = task.conversationId,
            sessionId = task.sessionId,
            turnId = task.turnId,
        )
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = task.sessionId.orEmpty(),
            turnId = task.turnId,
            displayId = task.screenDisplayId ?: 0,
        )
        ScreenTaskOverlayHost.sync(context, listOf(task))
        ShadowLooper.idleMainLooper()

        // 1. 展开并自定义缩放高度
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.EXPANDED)
        ShadowLooper.idleMainLooper()
        ScreenTaskOverlayHost.resizeStage(newWidthPx = dp(context, 320), newCanvasHeightPx = dp(context, 280))
        assertEquals(280, ScreenTaskOverlayHost.getSavedCanvasHeightDp())

        // 2. 收起至胶囊
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.CAPSULE)
        ShadowLooper.idleMainLooper()
        assertEquals(OverlayDisplayMode.CAPSULE, ScreenTaskOverlayHost.getOverlayMode())

        // 3. 重新展开，验证保存的高度被正确读取并保留，未无条件复位为336
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.EXPANDED)
        ShadowLooper.idleMainLooper()
        assertEquals(280, ScreenTaskOverlayHost.getSavedCanvasHeightDp())
    }

    @Test
    fun `view reopening from capsule and docked restores expanded position and canvas height`() {
        val task = createTaskInfo("task-reopen-position")
        TaskRuntime.start(context, task.taskId, task.conversationId, task.sessionId, task.turnId)
        TaskRuntime.markScreenTarget(context, task.sessionId.orEmpty(), task.turnId, task.screenDisplayId ?: 0)
        ScreenTaskOverlayHost.sync(context, listOf(task))
        ShadowLooper.idleMainLooper()
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.EXPANDED)
        ScreenTaskOverlayHost.resizeStage(dp(context, 200), dp(context, 180))
        ScreenTaskOverlayHost.moveWindowBy(0f, dp(context, 120).toFloat())
        ShadowLooper.idleMainLooper()
        val expectedPosition = ScreenTaskOverlayHost.getWindowPosition()
        val view = ScreenTaskOverlayHost.getTopOverlayView()!!
        for (collapsedMode in listOf(OverlayDisplayMode.CAPSULE, OverlayDisplayMode.DOCKED)) {
            view.requestModeTransition(collapsedMode)
            ShadowLooper.idleMainLooper()
            assertNotEquals(expectedPosition, ScreenTaskOverlayHost.getWindowPosition())
            view.requestModeTransition(OverlayDisplayMode.EXPANDED)
            ShadowLooper.idleMainLooper()
            assertEquals("重新展开必须恢复该小窗位置", expectedPosition, ScreenTaskOverlayHost.getWindowPosition())
            assertEquals("工具栏不得累加到保存画布高度", 180, ScreenTaskOverlayHost.getSavedCanvasHeightDp())
            assertEquals(dp(context, 180), view.getCanvasWrapper().layoutParams.height)
        }
    }

    @Test
    fun `takeover button in expanded monitor requires explicit confirmation overlay before taking over`() {
        var takeoverCallbackCount = 0

        val capsule = ScreenTaskCapsuleView(context).apply {
            initBinding(
                taskId = "task-takeover-confirm",
                sessionId = "sess-tc",
                turnId = "t1",
                stopToken = "token-tc",
                displayId = 5,
                statusText = "执行中",
                isWaiting = false,
                onTakeover = { _, _, _, _, _ -> takeoverCallbackCount++ }
            )
            setOverlayMode(OverlayDisplayMode.EXPANDED)
        }

        val takeoverBtn = capsule.getTakeoverButton()
        val confirmOverlay = capsule.getTakeoverConfirmOverlay()

        assertEquals(android.view.View.GONE, confirmOverlay.visibility)
        assertEquals(0, takeoverCallbackCount)

        takeoverBtn.performClick()

        // 验证：点击并未直接触发移交回调，而是展示二次确认浮层
        assertEquals("未显式确认前严禁触发移交回调", 0, takeoverCallbackCount)
        assertEquals("点击移交按钮必须展示二次确认浮层", android.view.View.VISIBLE, confirmOverlay.visibility)

        // 显式确认后才触发移交，且仅触发1次
        capsule.confirmTakeover()
        assertEquals("显式确认后必须仅触发1次移交回调", 1, takeoverCallbackCount)
        assertEquals(android.view.View.GONE, confirmOverlay.visibility)
    }

    @Test
    fun `portrait dynamic default width calculates to approximately 34 percent and 367px on 1080x2400`() {
        val bounds = ScreenTaskOverlayHost.calculateStageWidthBounds(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            density = 2.625f,
            safeInsets = Rect(21, 84, 21, 63),
            backgroundAspect = 1080f / 2400f
        )
        assertFalse("竖屏背景不应识别为横屏", bounds.isLandscape)
        assertEquals("最小宽必须为120dp", 120, bounds.minWidthDp)
        assertTrue("默认宽度像素应约为367px", bounds.defaultWidthPx in 365..370)
        val widthRatio = bounds.defaultWidthPx.toFloat() / 1080f
        assertTrue("默认宽度应约占屏宽34%", widthRatio in 0.33f..0.35f)
        assertEquals("默认宽度转dp应约140dp", 140, bounds.defaultWidthDp)
    }

    @Test
    fun `landscape background dynamic default width calculates to approximately 76 percent of short edge`() {
        val bounds = ScreenTaskOverlayHost.calculateStageWidthBounds(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            density = 2.625f,
            safeInsets = Rect(21, 84, 21, 63),
            backgroundAspect = 2400f / 1080f
        )
        assertTrue("宽高比大于1的背景必须识别为横屏", bounds.isLandscape)
        val shortEdgeRatio = bounds.defaultWidthPx.toFloat() / 1080f
        assertTrue("横屏默认宽应约为短边的76%", shortEdgeRatio in 0.75f..0.77f)
        assertTrue("横屏默认宽像素应约821px", bounds.defaultWidthPx in 818..825)
    }

    @Test
    fun `width clamp bounds enforce minimum 120dp and safe insets constraint`() {
        val bounds = ScreenTaskOverlayHost.calculateStageWidthBounds(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            density = 2.625f,
            safeInsets = Rect(21, 84, 21, 63),
            backgroundAspect = 1080f / 2400f
        )
        val clampedUnder = ScreenTaskOverlayHost.resolveStageWidthDp(currentStageWidthDp = 80, bounds = bounds)
        assertEquals("低于120dp的尺寸必须被clamp至最小宽", 120, clampedUnder)

        val clampedOver = ScreenTaskOverlayHost.resolveStageWidthDp(currentStageWidthDp = 600, bounds = bounds)
        assertEquals("超过最大允许宽度的尺寸必须被clamp至最大宽", bounds.maxWidthDp, clampedOver)
    }

    @Test
    fun `unspecified geometry resolves to dynamic default width while user custom sizes are preserved`() {
        val bounds = ScreenTaskOverlayHost.calculateStageWidthBounds(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            density = 2.625f,
            safeInsets = Rect(21, 84, 21, 63),
            backgroundAspect = 1080f / 2400f
        )
        val defaultFromUnspecified = ScreenTaskOverlayHost.resolveStageWidthDp(currentStageWidthDp = -1, bounds = bounds)
        assertEquals("未指定(-1)必须解析为动态默认宽度", bounds.defaultWidthDp, defaultFromUnspecified)

        val defaultFromZero = ScreenTaskOverlayHost.resolveStageWidthDp(currentStageWidthDp = 0, bounds = bounds)
        assertEquals("0必须解析为动态默认宽度", bounds.defaultWidthDp, defaultFromZero)

        val custom296 = ScreenTaskOverlayHost.resolveStageWidthDp(currentStageWidthDp = 296, bounds = bounds)
        assertEquals("用户明确缩放至296dp必须在合法区间内保留，禁止凭数字抹除", 296, custom296)

        val custom200 = ScreenTaskOverlayHost.resolveStageWidthDp(currentStageWidthDp = 200, bounds = bounds)
        assertEquals("用户自定义200dp必须保留", 200, custom200)
    }


    private fun layoutCapsule(root: View, width: Int = root.width, height: Int = root.height) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
    }

    private fun boundsInRoot(root: ViewGroup, target: View): Rect = Rect().also {
        target.getDrawingRect(it)
        root.offsetDescendantRectToMyCoords(target, it)
    }

    private fun dispatchTouchClick(root: ViewGroup, target: View) {
        if (!root.isAttachedToWindow) {
            val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
            touchActivities.add(controller)
            controller.get().setContentView(root, ViewGroup.LayoutParams(root.width, root.height))
            controller.visible()
            ShadowLooper.idleMainLooper()
        }
        layoutCapsule(root)
        var ancestor = target.parent
        while (ancestor is ViewGroup && ancestor !== root) {
            val rect = Rect()
            target.getDrawingRect(rect)
            ancestor.offsetDescendantRectToMyCoords(target, rect)
            if (ancestor is android.widget.ScrollView) {
                val delta = when {
                    rect.bottom > ancestor.height -> rect.bottom - ancestor.height
                    rect.top < 0 -> rect.top
                    else -> 0
                }
                ancestor.scrollBy(0, delta)
            } else if (ancestor is android.widget.HorizontalScrollView) {
                val delta = when {
                    rect.right > ancestor.width -> rect.right - ancestor.width
                    rect.left < 0 -> rect.left
                    else -> 0
                }
                ancestor.scrollBy(delta, 0)
            }
            ancestor = ancestor.parent
        }
        val targetRect = boundsInRoot(root, target)
        val visibleRect = Rect(targetRect)
        visibleRect.intersect(0, 0, root.width, root.height)
        ancestor = target.parent
        while (ancestor is ViewGroup && ancestor !== root) {
            visibleRect.intersect(boundsInRoot(root, ancestor))
            ancestor = ancestor.parent
        }
        assertTrue("触控目标必须完整可见：$targetRect / $visibleRect", visibleRect == targetRect && !visibleRect.isEmpty)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, targetRect.exactCenterX(), targetRect.exactCenterY(), 0)
        val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, targetRect.exactCenterX(), targetRect.exactCenterY(), 0)
        try {
            assertTrue("根视图必须接收按下", root.dispatchTouchEvent(down))
            assertTrue("根视图必须接收抬起", root.dispatchTouchEvent(up))
        } finally {
            down.recycle()
            up.recycle()
        }
        ShadowLooper.idleMainLooper()
        layoutCapsule(root)
    }

    @Test
    fun `takeover confirmation card in 120dp narrow window maintains 48dp buttons and touch dispatches correctly`() {
        var takeoverCallbackCount = 0
        val capsule = ScreenTaskCapsuleView(context).apply {
            initBinding(
                taskId = "task-takeover-narrow",
                sessionId = "sess-tn",
                turnId = "t1",
                stopToken = "token-tn",
                displayId = 5,
                statusText = "执行中",
                isWaiting = false,
                onTakeover = { _, _, _, _, _ -> takeoverCallbackCount++ }
            )
            setOverlayMode(OverlayDisplayMode.EXPANDED)
        }

        capsule.showTakeoverConfirmOverlay()
        val overlay = capsule.getTakeoverConfirmOverlay()
        val confirmBtn = capsule.getTakeoverConfirmButton()
        val cancelBtn = capsule.getTakeoverCancelButton()

        // 模拟窄窗 120dp 测量与布局
        val widthSpec = View.MeasureSpec.makeMeasureSpec(dp(context, 120), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(dp(context, 144), View.MeasureSpec.EXACTLY)
        capsule.measure(widthSpec, heightSpec)
        capsule.layout(0, 0, dp(context, 120), dp(context, 144))

        assertTrue("确认按钮宽度在窄窗下必须至少48dp", confirmBtn.measuredWidth >= dp(context, 48))
        assertTrue("确认按钮高度必须至少48dp触控标准", confirmBtn.measuredHeight >= dp(context, 48))
        assertTrue("取消按钮宽度在窄窗下必须至少48dp", cancelBtn.measuredWidth >= dp(context, 48))
        assertTrue("取消按钮高度必须至少48dp触控标准", cancelBtn.measuredHeight >= dp(context, 48))

        // 真实触控分发点击取消按钮
        dispatchTouchClick(capsule, cancelBtn)
        assertEquals("点击取消按钮必须关闭浮层且不触发移交", 0, takeoverCallbackCount)
        assertEquals("取消后浮层关闭", View.GONE, overlay.visibility)

        // 重新展示并触控分发点击确认按钮
        capsule.showTakeoverConfirmOverlay()
        assertEquals(View.VISIBLE, overlay.visibility)
        dispatchTouchClick(capsule, confirmBtn)
        assertEquals("触控分发点击确认按钮必须触发移交回调", 1, takeoverCallbackCount)
        assertEquals("移交后浮层必须关闭", View.GONE, overlay.visibility)
    }

    @Test
    fun `compact controls stay separate and receive real touches at narrow and landscape sizes`() {
        for ((widthDp, heightDp) in listOf(120 to 237, 140 to 408, 313 to 237, 120 to 108)) {
            var stopCount = 0
            var takeoverCount = 0
            var fullScreenCount = 0
            val capsule = ScreenTaskCapsuleView(context).apply {
                initBinding(
                    taskId = "task-controls",
                    sessionId = "session-controls",
                    turnId = "turn-controls",
                    stopToken = "token-controls",
                    displayId = 5,
                    statusText = "执行中",
                    isWaiting = false,
                    onStop = { _, _, _, _ -> stopCount++ },
                    onTakeover = { _, _, _, _, _ -> takeoverCount++ },
                    onOpenPip = { _, _, _, _, _ -> fullScreenCount++ },
                )
                setOverlayMode(OverlayDisplayMode.EXPANDED, dp(context, widthDp))
            }
            layoutCapsule(capsule, dp(context, widthDp), dp(context, heightDp))
            val controls = listOf(capsule.getStageMoreButton(), capsule.getStageCollapseButton(), capsule.getStopButton(), capsule.getResizeHandle())
            for ((index, control) in controls.withIndex()) {
                assertTrue("控件触控区域不得缩小", control.width >= dp(context, 48) && control.height >= dp(context, 48))
                val rect = boundsInRoot(capsule, control)
                for (other in controls.drop(index + 1)) {
                    assertFalse("不同控件在根视图内不得重叠", Rect.intersects(rect, boundsInRoot(capsule, other)))
                }
            }
            assertEquals(View.GONE, capsule.getSideToolbarScroll().visibility)
            capsule.updateState("执行中", false, 5, "session-controls")
            assertEquals("状态刷新不得擅自打开工具行", View.GONE, capsule.getSideToolbarScroll().visibility)
            dispatchTouchClick(capsule, capsule.getStageMoreButton())
            assertEquals(View.VISIBLE, capsule.getSideToolbarScroll().visibility)
            dispatchTouchClick(capsule, capsule.getTakeoverButton())
            assertEquals(View.VISIBLE, capsule.getTakeoverConfirmOverlay().visibility)
            assertEquals(0, takeoverCount)
            assertEquals(0, stopCount)
            dispatchTouchClick(capsule, capsule.getTakeoverCancelButton())
            assertEquals(View.GONE, capsule.getTakeoverConfirmOverlay().visibility)
            dispatchTouchClick(capsule, capsule.getStageMoreButton())
            dispatchTouchClick(capsule, capsule.getPipButton())
            assertEquals(1, fullScreenCount)
            assertEquals(View.GONE, capsule.getSideToolbarScroll().visibility)
            val previousCanvasHeight = capsule.getCanvasWrapper().layoutParams.height
            dispatchTouchClick(capsule, capsule.getResizeHandle())
            assertTrue("点击缩放手柄必须改变预览尺寸", capsule.getCanvasWrapper().layoutParams.height != previousCanvasHeight)
            assertEquals(0, stopCount)
            assertEquals(0, takeoverCount)
            assertEquals(View.GONE, capsule.getSideToolbarScroll().visibility)
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h160dp")
    fun `short safe height keeps exact bounds and scrollable controls without invalid clamp`() {
        val capsule = ScreenTaskCapsuleView(context).apply {
            initBinding("short-task", "short-session", "short-turn", "short-token", 5, "执行中", false)
            setOverlayMode(OverlayDisplayMode.EXPANDED, dp(context, 120))
        }
        layoutCapsule(capsule, dp(context, 120), dp(context, 104))
        assertEquals(dp(context, 104), capsule.measuredHeight)
        dispatchTouchClick(capsule, capsule.getStageMoreButton())
        assertEquals(View.VISIBLE, capsule.getSideToolbarScroll().visibility)
        dispatchTouchClick(capsule, capsule.getTakeoverButton())
        dispatchTouchClick(capsule, capsule.getTakeoverCancelButton())
        assertEquals(View.GONE, capsule.getTakeoverConfirmOverlay().visibility)
        assertEquals(dp(context, 104), capsule.measuredHeight)
    }
}
