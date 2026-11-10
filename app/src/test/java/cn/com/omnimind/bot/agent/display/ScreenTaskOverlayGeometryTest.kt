package cn.com.omnimind.bot.agent.display

import android.app.Application
import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.view.ViewConfiguration
import cn.com.omnimind.bot.task.runtime.TaskExecutionInfo
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskWaitingStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsInteractive(true)
        ShadowSettings.setCanDrawOverlays(true)
        TaskRuntime.resetForTesting()
        ScreenTaskOverlayHost.dismissAll()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
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

        val dm = context.resources.displayMetrics
        val safeInsets = ScreenTaskOverlayHost.getSafeInsets(context)

        // 胶囊靠左侧释放：应吸附至左安全边界
        ScreenTaskOverlayHost.moveWindowBy(-3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.CAPSULE)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val posLeft = ScreenTaskOverlayHost.getWindowPosition()
        assertEquals(safeInsets.left, posLeft.first)

        // 胶囊靠右侧释放：应吸附至右安全边界
        ScreenTaskOverlayHost.moveWindowBy(3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.CAPSULE)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val posRight = ScreenTaskOverlayHost.getWindowPosition()
        val expectedRight = (dm.widthPixels - dp(context, 264) - safeInsets.right).coerceAtLeast(safeInsets.left)
        assertEquals(expectedRight, posRight.first)

        // 贴边折叠态 (DOCKED, 48dp)：左半屏吸附左侧，右半屏吸附右侧
        ScreenTaskOverlayHost.setOverlayMode(OverlayDisplayMode.DOCKED)
        ScreenTaskOverlayHost.moveWindowBy(-3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.DOCKED)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val dockedLeft = ScreenTaskOverlayHost.getWindowPosition()
        assertEquals(safeInsets.left, dockedLeft.first)

        ScreenTaskOverlayHost.moveWindowBy(3000f, 0f)
        ScreenTaskOverlayHost.snapWindow(OverlayDisplayMode.DOCKED)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val dockedRight = ScreenTaskOverlayHost.getWindowPosition()
        val expectedDockRight = dm.widthPixels - dp(context, 48) - safeInsets.right
        assertEquals(expectedDockRight, dockedRight.first)
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
}
