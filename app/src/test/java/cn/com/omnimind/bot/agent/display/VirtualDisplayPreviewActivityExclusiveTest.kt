package cn.com.omnimind.bot.agent.display

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.PowerManager
import android.view.Display
import android.view.View
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.androidgui.VirtualScreenFrameSource
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskWaitingStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import kotlin.concurrent.withLock

/**
 * 虚拟显示屏全屏预览页面与画面组件专属行为验证：
 * 1. 运行态 (Running)：按钮与光晕启用，支持停止与移交。
 * 2. 等待态 (Waiting)：光晕暂停，允许停止与移交。
 * 3. 留存态 (Retained)：无活跃任务时停止按钮禁用，显式移交主屏保持可用，光晕停止。
 * 4. 新轮次态 (New Turn)：新 Turn 准入后旧 Turn 凭据按钮禁用，绝不误作用新 Turn。
 * 5. 未绑定留存态准入新任务：初始未绑定进入留存后，会话产生新任务一律判定为新轮次禁操作，防止误抢占。
 * 6. 失效态 (Closed/Invalid)：屏幕或所有者失效后画布清黑停帧、几何边界清空防旧帧/光晕泄漏，禁用一切操作并禁止 PiP。
 * 7. 生命周期独立性：关闭/销毁仅关闭本地观看，不向 TaskRuntime 产生终态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VirtualDisplayPreviewActivityExclusiveTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsInteractive(true)
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        ScreenTaskOverlayHost.dismissAll()
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        ScreenTaskOverlayHost.dismissAll()
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
        DeviceDisplayManager.displayBridge = null
        val fsField = DeviceDisplayManager::class.java.getDeclaredField("frameSource").apply { isAccessible = true }
        fsField.set(DeviceDisplayManager, null)
        ShadowLooper.idleMainLooper()
    }

    private fun setVirtualDisplayForTesting(displayId: Int, ownerId: String?, source: VirtualScreenFrameSource? = null) {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        val fsField = DeviceDisplayManager::class.java.getDeclaredField("frameSource").apply { isAccessible = true }
        field.set(DeviceDisplayManager, ownerId)
        idField.setInt(DeviceDisplayManager, displayId)
        fsField.set(DeviceDisplayManager, source)
        if (ownerId != null && displayId != Display.DEFAULT_DISPLAY) {
            DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, ownerId)
            DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()
        } else {
            DeviceDisplayManager.displayBridge = null
        }
    }

    private fun publishFrameForTesting(source: VirtualScreenFrameSource, bitmap: Bitmap) {
        val lockField = VirtualScreenFrameSource::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = lockField.get(source) as java.util.concurrent.locks.ReentrantLock
        val bitmapField = VirtualScreenFrameSource::class.java.getDeclaredField("latestBitmap").apply { isAccessible = true }
        val seqField = VirtualScreenFrameSource::class.java.getDeclaredField("latestFrameSequence").apply { isAccessible = true }
        lock.withLock {
            bitmapField.set(source, bitmap)
            val current = seqField.getLong(source)
            seqField.setLong(source, current + 1)
        }
    }

    private fun renderPreview(view: View, canvas: Canvas) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 100, 100)
        view.draw(canvas)
    }

    @Test
    fun `running state enables operations and activates edge light on frames drawn`() {
        val sessionId = "session-exclusive-running"
        val displayId = 10
        val taskId = "task-run-1"
        val turnId = "turn-run-1"

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            conversationId = 101L,
            sessionId = sessionId,
            turnId = turnId,
            title = "运行任务",
            statusText = "运行中",
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId)
        val stopToken = TaskRuntime.getActiveTask(taskId)!!.stopToken

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
            taskId = taskId,
            turnId = turnId,
            stopToken = stopToken,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        try {
            val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            publishFrameForTesting(source, redBitmap)
            val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(renderBitmap)
            renderPreview(activity.getPreviewView(), canvas)
            ShadowLooper.idleMainLooper()

            assertEquals("必须成功绘制最新红帧", Color.RED, renderBitmap.getPixel(50, 50))
            assertTrue("匹配当前轮次时停止任务按钮可用", activity.getStopButton().isEnabled)
            assertTrue("匹配当前轮次时移交主屏按钮可用", activity.getPresentButton().isEnabled)
            assertTrue("运行中且真实帧绘制后光晕必须启动", activity.getPreviewEdgeLight().isRunning())
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    @Test
    fun `waiting state halts edge light but preserves explicit stop and takeover`() {
        val sessionId = "session-exclusive-waiting"
        val displayId = 11
        val taskId = "task-wait-1"
        val turnId = "turn-wait-1"
        val convId = 102L

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            conversationId = convId,
            sessionId = sessionId,
            turnId = turnId,
            title = "等待任务",
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId)
        val stopToken = TaskRuntime.getActiveTask(taskId)!!.stopToken

        val waitingStatus = TaskWaitingStatus.WaitingForManualAuth(
            conversationId = convId,
            sessionId = sessionId,
            turnId = turnId,
            promptMessage = "等待人工确认",
        )
        TaskRuntime.updateWaitingStatus(context, convId, waitingStatus)

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
            taskId = taskId,
            turnId = turnId,
            stopToken = stopToken,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()

        try {
            val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            publishFrameForTesting(source, redBitmap)
            val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(renderBitmap)
            renderPreview(activity.getPreviewView(), canvas)
            ShadowLooper.idleMainLooper()

            assertEquals("必须成功绘制最新红帧", Color.RED, renderBitmap.getPixel(50, 50))
            assertTrue("等待状态允许主动停止任务", activity.getStopButton().isEnabled)
            assertTrue("等待状态允许主动移交主屏处理", activity.getPresentButton().isEnabled)
            assertFalse("等待状态不应展示运行中动态光晕", activity.getPreviewEdgeLight().isRunning())
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    @Test
    fun `retained state without active task disables stop keeps present enabled and halts edge light`() {
        val sessionId = "session-exclusive-retained"
        val displayId = 12

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        // 无活跃 TaskRuntime，仅留存画面观看
        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()
        try {
            activity.refreshUiState()
            assertFalse("无活跃任务时停止按钮必须禁用", activity.getStopButton().isEnabled)
            assertTrue("留存画面可显式请求移交主屏", activity.getPresentButton().isEnabled)
            assertFalse("留存状态绝不展示错误运行光晕", activity.getPreviewEdgeLight().isRunning())
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    @Test
    fun `new turn state protects new turn from old turn buttons`() {
        val sessionId = "session-exclusive-newturn"
        val displayId = 13
        val initialTaskId = "task-turn-1"
        val initialTurnId = "turn-1"
        val convId = 103L

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        TaskRuntime.start(
            context = context,
            taskId = initialTaskId,
            conversationId = convId,
            sessionId = sessionId,
            turnId = initialTurnId,
            title = "旧轮次任务",
        )
        TaskRuntime.markScreenTarget(context, sessionId, initialTurnId, displayId)
        val initialStopToken = TaskRuntime.getActiveTask(initialTaskId)!!.stopToken

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
            taskId = initialTaskId,
            turnId = initialTurnId,
            stopToken = initialStopToken,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()
        try {
            // 旧轮次结束，新 Turn 2 在同一 session 启动
            TaskRuntime.finish(context, initialTaskId)
            TaskRuntime.start(
                context = context,
                taskId = "task-turn-2",
                conversationId = convId,
                sessionId = sessionId,
                turnId = "turn-2",
                title = "新轮次任务",
            )
            TaskRuntime.markScreenTarget(context, sessionId, "turn-2", displayId)
            activity.refreshUiState()

            assertFalse("旧 Turn 绑定的停止按钮不可作用于新 Turn", activity.getStopButton().isEnabled)
            assertFalse("旧 Turn 绑定的移交按钮不可作用于新 Turn", activity.getPresentButton().isEnabled)
            assertFalse("旧 Turn 不应展示运行光晕", activity.getPreviewEdgeLight().isRunning())
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    @Test
    fun `unbound retained view treats subsequent task as new turn and protects it from takeover`() {
        val sessionId = "session-exclusive-unbound-retained"
        val displayId = 14

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        // 初始无绑定（留存查看）
        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()
        try {
            activity.refreshUiState()
            assertFalse("留存状态停止禁用", activity.getStopButton().isEnabled)
            assertTrue("留存无任务状态移交允许", activity.getPresentButton().isEnabled)

            // 后来该会话被触发准入了新任务
            val convId = 104L
            TaskRuntime.start(
                context = context,
                taskId = "task-subsequent-1",
                conversationId = convId,
                sessionId = sessionId,
                turnId = "turn-subsequent-1",
                title = "后续任务",
            )
            TaskRuntime.markScreenTarget(context, sessionId, "turn-subsequent-1", displayId)
            activity.refreshUiState()

            // 契约：未绑定留存后来有 task 一律判定为 NEW_TURN，绝不将未绑定的移交/停止施加给该 task
            assertFalse("后来产生任务时停止按钮禁用", activity.getStopButton().isEnabled)
            assertFalse("后来产生任务时移交按钮禁用防止误取消新任务", activity.getPresentButton().isEnabled)
            assertFalse("新任务不应在此展示光晕", activity.getPreviewEdgeLight().isRunning())
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    @Test
    fun `closed or invalid display clears black halts frame loop disables operations and rejects pip`() {
        val sessionId = "session-exclusive-closed"
        val displayId = 15

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        val activity = controller.get()
        val frameView = activity.getPreviewView()
        ShadowLooper.idleMainLooper()

        try {
            val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            publishFrameForTesting(source, redBitmap)
            val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(renderBitmap)
            renderPreview(frameView, canvas)
            ShadowLooper.idleMainLooper()
            assertEquals("关闭前必须成功绘制最新红帧", Color.RED, renderBitmap.getPixel(50, 50))

            // 模拟虚拟屏销毁或所有者失效
            setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
            activity.refreshUiState()

            assertFalse("已关闭状态停止按钮禁用", activity.getStopButton().isEnabled)
            assertFalse("已关闭状态移交按钮禁用", activity.getPresentButton().isEnabled)

            // 停帧与清黑断言
            assertFalse("失效后 shouldDrawFrames 必须为 false", frameView.shouldDrawFrames())
            assertFalse("失效后 isDisplayOwnerValid 必须为 false", frameView.isDisplayOwnerValid())

            val outRect = RectF(10f, 10f, 100f, 100f)
            frameView.getContentRect(outRect)
            assertTrue("失效后 getContentRect 必须清空以绝不泄漏旧帧几何", outRect.isEmpty)
            assertFalse("失效时光晕必须停止", activity.getPreviewEdgeLight().isRunning())

            val canEnterMethod = VirtualDisplayPreviewActivity::class.java.getDeclaredMethod("canEnterPip").apply { isAccessible = true }
            assertFalse("虚拟屏关闭时严禁进入画中画", canEnterMethod.invoke(activity) as Boolean)
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    @Test
    fun `closing preview activity only closes viewing without terminating task or producing terminal state`() {
        val sessionId = "session-exclusive-lifecycle"
        val displayId = 16
        val taskId = "task-life-1"
        val turnId = "turn-life-1"
        val convId = 105L

        val source = VirtualScreenFrameSource(1080, 2400)
        setVirtualDisplayForTesting(displayId, sessionId, source)

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            conversationId = convId,
            sessionId = sessionId,
            turnId = turnId,
            title = "生命周期测试",
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId)
        val stopToken = TaskRuntime.getActiveTask(taskId)!!.stopToken

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
            taskId = taskId,
            turnId = turnId,
            stopToken = stopToken,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        ShadowLooper.idleMainLooper()
        try {
            // 用户点击“关闭预览”或返回键
            activity.getCloseButton().performClick()
            assertTrue("关闭预览必须触发 Activity finish", activity.isFinishing)

            // 核心契约：Activity 生命周期结束绝不停止/取消 TaskRuntime 执行租约，不产生终态
            val active = TaskRuntime.findActiveTaskBySession(sessionId)
            assertNotNull("Activity 退出后 TaskRuntime 任务必须依然保持运行", active)
            assertEquals("保持运行的任务必须为同一 taskId", taskId, active?.taskId)
        } finally {
            controller.destroy()
            ShadowLooper.idleMainLooper()
            source.close()
        }
    }

    private class FakeTestDisplayBridge : PrivilegedDisplayBridge {
        override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: android.view.Surface, flags: Int): Result<Int> =
            Result.success(4)
        override suspend fun releaseVirtualDisplay(displayId: Int) {}
        override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
        override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override suspend fun getRootTasks(displayId: Int): String =
            """[{"taskId":101,"taskKind":"root_task","displayId":$displayId,"userId":0,"packageName":"com.example.calc","childTaskIds":[101]}]"""
        override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String =
            """{"success":true,"code":"ok","taskId":$taskId,"taskKind":"root_task","sourceDisplayId":$sourceDisplayId,"targetDisplayId":$targetDisplayId,"verified":true}"""
    }
}
