package cn.com.omnimind.bot.agent.display

import android.app.Application
import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.bot.task.runtime.TaskExecutionInfo
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.androidgui.VirtualScreenFrameSource
import cn.com.omnimind.bot.task.runtime.TaskRuntimeActionReceiver
import cn.com.omnimind.bot.task.runtime.TaskRuntimeService
import cn.com.omnimind.bot.task.runtime.TaskWaitingStatus
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.agent.runtime.CodexRemoteBridgeConfig
import cn.com.omnimind.bot.agent.runtime.CodexRemoteBridgeConfigStore
import cn.com.omnimind.bot.agent.runtime.AcpAgentProfileStore
import cn.com.omnimind.bot.agent.runtime.REMOTE_CODEX_AGENT_ID
import cn.com.omnimind.bot.agent.runtime.RemoteCodexAppServerConnection
import cn.com.omnimind.bot.agent.runtime.RemoteCodexAppServerSession
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.mockito.Mockito
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import kotlin.concurrent.withLock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class VirtualDisplayTakeoverAndNotificationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsInteractive(true)
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
    }

    @After
    fun tearDown() {
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
        DeviceDisplayManager.displayBridge = null
        val fsField = DeviceDisplayManager::class.java.getDeclaredField("frameSource").apply { isAccessible = true }
        fsField.set(DeviceDisplayManager, null)
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
    fun `unauthorized display id or mismatched session is rejected on create`() {
        setVirtualDisplayForTesting(3, "session-authorized")

        // 尝试以未授权的 sessionId 打开虚拟屏 3
        val unauthorizedIntent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = "session-attacker",
            displayId = 3,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, unauthorizedIntent).create()
        val activity = controller.get()
        try {
            assertTrue("未授权的 session 打开虚拟屏必须立即 finish", activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `close preview button finishes activity without cancelling task or destroying display`() {
        val sessionId = "session-normal"
        val taskId = "task-turn-1"
        setVirtualDisplayForTesting(5, sessionId)

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = "turn-1",
            title = "后台运行任务",
        )

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 5,
            taskId = taskId,
            turnId = "turn-1",
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val closeBtn = findButtonWithText(activity, "关闭预览")
            assertNotNull(closeBtn)
            closeBtn!!.performClick()

            assertTrue("点击关闭预览必须 finish Activity", activity.isFinishing)
            assertTrue("关闭预览不应取消任务", TaskRuntime.hasActiveTasks())
            assertEquals("关闭预览不应销毁虚拟显示屏", 5, DeviceDisplayManager.activeVirtualDisplayId())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `stale takeover times out when turn does not stop and preserves screen with message`() {
        val sessionId = "session-takeover"
        val taskId = "task-slow-1"
        setVirtualDisplayForTesting(4, sessionId)

        // 模拟一个长期处于活跃状态且不退出的 Turn
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = "turn-slow",
            title = "耗时计算任务",
        )

        val fakeBridge = FakeTestDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 4,
            taskId = taskId,
            turnId = "turn-slow",
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val presentBtn = findButtonWithText(activity, "移交主屏")
            assertNotNull(presentBtn)
            presentBtn!!.performClick()

            // 推进主线程 Looper 与 SystemClock 虚拟单调时间（5000ms+）
            org.robolectric.shadows.ShadowSystemClock.advanceBy(6000, java.util.concurrent.TimeUnit.MILLISECONDS)
            ShadowLooper.idleMainLooper()

            val statusView = findTextViewWithStatus(activity)
            assertNotNull(statusView)
            assertEquals("超时未能停止 Turn 时必须显示任务尚未停止", "任务尚未停止", statusView.text.toString())
            assertEquals("超时迁移绝不调用底层 moveRootTask", 0, fakeBridge.moveCalls.size)
            assertEquals("超时绝不销毁虚拟显示屏", 4, DeviceDisplayManager.activeVirtualDisplayId())
            assertTrue("移交按钮在超时后重新可用", presentBtn.isEnabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `new turn busy interrupts takeover and preserves screen`() {
        val sessionId = "session-busy"
        val taskId = "task-busy-1"
        setVirtualDisplayForTesting(4, sessionId)

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = "turn-1",
            title = "首轮任务",
        )

        val fakeBridge = FakeTestDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 4,
            taskId = taskId,
            turnId = "turn-1",
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val presentBtn = findButtonWithText(activity, "移交主屏")
            assertNotNull(presentBtn)
            presentBtn!!.performClick()

            // 模拟在等待原 Turn 停止期间，新 Turn-2 准入启动
            TaskRuntime.finish(context, taskId)
            TaskRuntime.start(
                context = context,
                taskId = "task-busy-2",
                sessionId = sessionId,
                turnId = "turn-2",
                title = "新轮次任务",
            )

            // 步进一个轮询周期
            ShadowLooper.idleMainLooper(200, java.util.concurrent.TimeUnit.MILLISECONDS)

            val statusView = findTextViewWithStatus(activity)
            assertNotNull(statusView)
            assertEquals("新轮次准入时必须报告新任务轮次已开始", "新任务轮次已开始，无法移交", statusView!!.text.toString())
            assertEquals("新轮次冲突时不执行迁移", 0, fakeBridge.moveCalls.size)
            assertEquals("新轮次冲突时不销毁虚拟显示屏", 4, DeviceDisplayManager.activeVirtualDisplayId())
            assertTrue("移交按钮在冲突后重新可用", presentBtn.isEnabled)
        } finally {
            controller.destroy()
        }
    }
    @Test
    fun `old preview activity does not cancel or interfere with new subsequent turn`() {
        val sessionId = "session-stale-preview"
        val task1Id = "task-turn-1"
        val task2Id = "task-turn-2"
        setVirtualDisplayForTesting(4, sessionId)

        // Turn 1 启动
        TaskRuntime.start(
            context = context,
            taskId = task1Id,
            sessionId = sessionId,
            turnId = "turn-1",
            title = "第一轮任务",
        )
        val task1Token = TaskRuntime.getActiveTask(task1Id)!!.stopToken

        val fakeBridge = FakeTestDisplayBridge()
        DeviceDisplayManager.displayBridge = fakeBridge

        // 旧预览 Activity 绑定了 Turn 1 的 snapshot
        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 4,
            taskId = task1Id,
            turnId = "turn-1",
            stopToken = task1Token,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            // Turn 1 已正常结束，新 Turn 2 在同一会话准入启动
            TaskRuntime.finish(context, task1Id)
            TaskRuntime.start(
                context = context,
                taskId = task2Id,
                sessionId = sessionId,
                turnId = "turn-2",
                title = "第二轮新任务",
            )
            val task2Token = TaskRuntime.getActiveTask(task2Id)!!.stopToken

            // 1. 用户点击旧预览页面的「停止任务」按钮
            val stopBtn = findButtonWithText(activity, "停止任务")
            assertNotNull(stopBtn)
            stopBtn!!.performClick()
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)

            val statusView = findTextViewWithStatus(activity)
            assertNotNull(statusView)
            assertEquals("旧预览点击停止任务必须拒绝新 Turn", "新任务轮次已开始，无法停止", statusView!!.text.toString())
            assertTrue("新 Turn 2 绝不应被误杀取消", TaskRuntime.validateStopToken(task2Id, task2Token))
            assertNotNull("新 Turn 2 必须依然处于活跃执行状态", TaskRuntime.getActiveTask(task2Id))

            // 2. 用户点击旧预览页面的「移交主屏」按钮
            val presentBtn = findButtonWithText(activity, "移交主屏")
            assertNotNull(presentBtn)
            presentBtn!!.performClick()
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)

            assertEquals("旧预览点击移交主屏必须拒绝新 Turn", "新任务轮次已开始，无法移交", statusView.text.toString())
            assertTrue("新 Turn 2 仍绝不应被误杀取消", TaskRuntime.validateStopToken(task2Id, task2Token))
            assertNotNull("新 Turn 2 必须仍活跃", TaskRuntime.getActiveTask(task2Id))
            assertEquals("绝不向底层执行迁移", 0, fakeBridge.moveCalls.size)
        } finally {
            controller.destroy()
        }
    }
    @Test
    fun `ambiguous multiple root tasks reject present without guessing first`() {
        val sessionId = "session-ambiguous"
        val taskId = "task-ambiguous-1"
        setVirtualDisplayForTesting(4, sessionId)

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = "turn-1",
            title = "多任务测试",
        )
        val token = TaskRuntime.getActiveTask(taskId)!!.stopToken

        val fakeBridge = FakeTestDisplayBridge()
        // 返回两个根任务，产生歧义
        fakeBridge.customRootTasksJson = """[
            {"taskId":101,"taskKind":"root_task","displayId":4,"userId":0,"packageName":"com.example.calc","childTaskIds":[101]},
            {"taskId":102,"taskKind":"root_task","displayId":4,"userId":0,"packageName":"com.example.notes","childTaskIds":[102]}
        ]"""
        DeviceDisplayManager.displayBridge = fakeBridge

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 4,
            taskId = taskId,
            turnId = "turn-1",
            stopToken = token,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val presentBtn = findButtonWithText(activity, "移交主屏")
            assertNotNull(presentBtn)
            val statusView = findTextViewWithStatus(activity)
            assertNotNull(statusView)
            presentBtn!!.performClick()

            // 结束原任务以便推进到迁移阶段
            TaskRuntime.finish(context, taskId)
            val waitDeadline = System.currentTimeMillis() + 5000L
            while (statusView.text.toString() == "正在停止任务并准备移交..." && System.currentTimeMillis() < waitDeadline) {
                ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                Thread.sleep(20)
            }

            assertEquals("存在多个运行根任务时必须提示歧义，禁止暗选第一个", "虚拟屏存在多个根任务，移交目标歧义", statusView.text.toString())
            assertEquals("歧义时绝不执行 moveRootTask", 0, fakeBridge.moveCalls.size)
            assertTrue("移交按钮在歧义后重新可用", presentBtn.isEnabled)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `notification prioritizes waiting task and uses attention channel without promoted ongoing`() {
        val convId = 301L
        val taskNormal = "task-normal-301"
        val taskWaiting = "task-waiting-301"

        TaskRuntime.start(
            context = context,
            taskId = taskNormal,
            conversationId = convId,
            sessionId = "session-301",
            turnId = "turn-1",
            title = "普通执行任务",
        )

        val waitingStatus = TaskWaitingStatus.WaitingForManualAuth(
            conversationId = convId,
            sessionId = "session-301",
            turnId = "turn-2",
            promptMessage = "请完成滑块验证",
        )

        TaskRuntime.start(
            context = context,
            taskId = taskWaiting,
            conversationId = convId,
            sessionId = "session-301",
            turnId = "turn-2",
            title = "等待验证任务",
        )
        TaskRuntime.updateWaitingStatus(context, convId, waitingStatus)

        val notification = TaskRuntimeService.buildNotificationFromRuntime(context)
        assertEquals("等待态必须优先使用 attention 通道", TaskRuntimeService.CHANNEL_ID_ATTENTION, notification.channelId)

        // 验证通知包含“已完成验证”动作按钮
        val actionTitles = notification.actions?.map { it.title.toString() }.orEmpty()
        assertTrue("等待态必须包含'已完成验证'按钮", actionTitles.contains("已完成验证"))
    }

    @Test
    fun `notification on API under 36 builds standard ongoing notification without crashing`() {
        val taskId = "task-api34"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = "session-api34",
            turnId = "turn-1",
            title = "低版本通知测试",
            statusText = "执行中",
        )
        TaskRuntime.updateProgress(context, taskId, "进度进行中", progressCurrent = 50, progressMax = 100)

        val notification = TaskRuntimeService.buildNotificationFromRuntime(context)
        assertEquals("非等待态使用默认执行通道", TaskRuntimeService.CHANNEL_ID, notification.channelId)
        assertTrue("非等待态通知必须是 ongoing", (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun `notification shows preview button only when primary task owns active virtual display`() {
        val primarySession = "session-owner"
        val taskId = "task-with-vd"

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = primarySession,
            turnId = "turn-1",
            title = "带虚拟屏任务",
        )

        // 1. 无虚拟屏时，通知中无“查看屏幕”按钮
        var notification = TaskRuntimeService.buildNotificationFromRuntime(context)
        var actionTitles = notification.actions?.map { it.title.toString() }.orEmpty()
        assertFalse("无虚拟屏时不应展示'查看屏幕'", actionTitles.contains("查看屏幕"))

        // 2. 虚拟屏属于其他会话时，通知中无“查看屏幕”按钮
        setVirtualDisplayForTesting(7, "session-other")
        notification = TaskRuntimeService.buildNotificationFromRuntime(context)
        actionTitles = notification.actions?.map { it.title.toString() }.orEmpty()
        assertFalse("虚拟屏归属其他会话时不应展示'查看屏幕'", actionTitles.contains("查看屏幕"))

        // 3. 虚拟屏属于当前 primaryTask 会话时，展示“查看屏幕”按钮
        setVirtualDisplayForTesting(7, primarySession)
        notification = TaskRuntimeService.buildNotificationFromRuntime(context)
        actionTitles = notification.actions?.map { it.title.toString() }.orEmpty()
        assertTrue("虚拟屏属于 primaryTask 时必须展示'查看屏幕'", actionTitles.contains("查看屏幕"))
    }

    @Test
    fun `action receiver ignores stop broadcast with mismatched or stale stopToken`() {
        val taskId = "task-token-test"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = "session-token",
            turnId = "turn-1",
        )
        val realToken = TaskRuntime.getActiveTask(taskId)!!.stopToken

        val receiver = TaskRuntimeActionReceiver()
        val mockPendingResult = Mockito.mock(BroadcastReceiver.PendingResult::class.java)
        ReflectionHelpers.setField(receiver, "mPendingResult", mockPendingResult)
        // 错误 token 的广播
        val wrongIntent = Intent(TaskRuntimeActionReceiver.ACTION_STOP_TASK).apply {
            putExtra(TaskRuntimeActionReceiver.EXTRA_TASK_ID, taskId)
            putExtra(TaskRuntimeActionReceiver.EXTRA_SESSION_ID, "session-token")
            putExtra(TaskRuntimeActionReceiver.EXTRA_STOP_TOKEN, "wrong-token")
        }
        receiver.onReceive(context, wrongIntent)
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("凭据不匹配时任务必须依然存活", TaskRuntime.hasActiveTasks())
        // 正确 token 的校验
        assertTrue("合法凭据验证必须通过", TaskRuntime.validateStopToken(taskId, realToken))
    }

    @Test
    fun `pip mode hides chrome bars and clears root padding`() {
        val sessionId = "session-pip-ui"
        setVirtualDisplayForTesting(6, sessionId)

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 6,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val root = ReflectionHelpers.getField<LinearLayout>(activity, "rootContainer")
            val header = ReflectionHelpers.getField<LinearLayout>(activity, "headerBar")
            val bottom = ReflectionHelpers.getField<LinearLayout>(activity, "bottomBar")
            val status = ReflectionHelpers.getField<TextView>(activity, "statusTextView")

            // 模拟进入 PiP
            activity.onPictureInPictureModeChanged(true, activity.resources.configuration)
            assertEquals("PiP 模式下 headerBar 必须隐藏", View.GONE, header.visibility)
            assertEquals("PiP 模式下 statusTextView 必须隐藏", View.GONE, status.visibility)
            assertEquals("PiP 模式下 bottomBar 必须隐藏", View.GONE, bottom.visibility)
            assertEquals("PiP 模式下 rootContainer paddingTop 必须清零避免双重边距", 0, root.paddingTop)
            assertEquals("PiP 模式下 rootContainer paddingBottom 必须清零", 0, root.paddingBottom)

            // 模拟退出 PiP 恢复全屏
            activity.onPictureInPictureModeChanged(false, activity.resources.configuration)
            assertEquals("退出 PiP 后 headerBar 恢复可见", View.VISIBLE, header.visibility)
            assertEquals("退出 PiP 后 statusTextView 恢复可见", View.VISIBLE, status.visibility)
            assertEquals("退出 PiP 后 bottomBar 恢复可见", View.VISIBLE, bottom.visibility)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `finish disables pip auto enter and close button does not enter pip`() {
        val sessionId = "session-pip-finish"
        setVirtualDisplayForTesting(6, sessionId)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()
        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 6,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val closeBtn = findButtonWithText(activity, "关闭预览")
            assertNotNull(closeBtn)
            closeBtn!!.performClick()

            assertTrue("点击关闭预览必须 finish Activity", activity.isFinishing)
            assertFalse("关闭预览绝不得进入画中画", activity.isInPictureInPictureMode)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `pip is forbidden when keyguard is locked or virtual display is inactive`() {
        val sessionId = "session-pip-guard"
        setVirtualDisplayForTesting(6, sessionId)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()
        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = 6,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        try {
            val canEnterMethod = VirtualDisplayPreviewActivity::class.java.getDeclaredMethod("canEnterPip").apply { isAccessible = true }
            assertTrue("正常活跃状态允许进入 PiP", canEnterMethod.invoke(activity) as Boolean)

            // 1. 锁屏状态下禁止 PiP
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            Shadows.shadowOf(km).setKeyguardLocked(true)
            assertFalse("锁屏状态下必须禁止 PiP", canEnterMethod.invoke(activity) as Boolean)
            Shadows.shadowOf(km).setKeyguardLocked(false)
            assertTrue(canEnterMethod.invoke(activity) as Boolean)

            // 1. 虚拟屏失效时禁止 PiP
            setVirtualDisplayForTesting(Display.DEFAULT_DISPLAY, null)
            assertFalse("虚拟屏失效时必须禁止 PiP", canEnterMethod.invoke(activity) as Boolean)

            // 恢复虚拟屏
            setVirtualDisplayForTesting(6, sessionId)
            assertTrue(canEnterMethod.invoke(activity) as Boolean)

            // 2. 虚拟屏所有者不匹配时禁止 PiP
            setVirtualDisplayForTesting(6, "other-session")
            assertFalse("虚拟屏归属不匹配时必须禁止 PiP", canEnterMethod.invoke(activity) as Boolean)

            // 3. finish 中禁止 PiP
            setVirtualDisplayForTesting(6, sessionId)
            activity.finish()
            assertFalse("finish 状态下必须禁止 PiP", canEnterMethod.invoke(activity) as Boolean)
        } finally {
            controller.destroy()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `frame freshness and lock transitions guard canvas pixels and pip loop`() {
        val sessionId = "session-canvas-pixels"
        val displayId = 8
        val frameSource = VirtualScreenFrameSource(100, 100)
        setVirtualDisplayForTesting(displayId, sessionId, frameSource)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()

        val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        publishFrameForTesting(frameSource, redBitmap)

        val intent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionId,
            displayId = displayId,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intent).create().start().resume().visible()
        ShadowLooper.idleMainLooper()
        val activity = controller.get()
        try {
            val previewView = ReflectionHelpers.getField<View>(activity, "previewView")
            previewView.measure(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            )
            previewView.layout(0, 0, 100, 100)

            val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val renderCanvas = Canvas(renderBitmap)
            // 1. 正常状态：成功绘制最新红帧
            renderBitmap.eraseColor(Color.TRANSPARENT)
            renderPreview(previewView, renderCanvas)
            assertEquals("初始正常状态必须绘制当前红帧", Color.RED, renderBitmap.getPixel(50, 50))

            // 2. 锁屏/息屏状态：强制清黑且 drawguard 拒绝绘制旧红帧
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            Shadows.shadowOf(km).setKeyguardLocked(true)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            ShadowLooper.idleMainLooper()

            renderBitmap.eraseColor(Color.TRANSPARENT)
            renderPreview(previewView, renderCanvas)
            assertEquals("锁屏时画布必须为全黑，严禁显示上一帧残影", Color.BLACK, renderBitmap.getPixel(50, 50))

            // 3. 用户解锁（ACTION_USER_PRESENT）：采集当前红帧序号作为水位，旧红帧继续被拒绝
            Shadows.shadowOf(km).setKeyguardLocked(false)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            ShadowLooper.idleMainLooper()

            renderBitmap.eraseColor(Color.TRANSPARENT)
            renderPreview(previewView, renderCanvas)
            assertEquals("解锁瞬间因尚未产生新帧，旧红帧必须被水位拒绝，保持全黑", Color.BLACK, renderBitmap.getPixel(50, 50))

            // 4. 发布全新蓝帧（序号严格大于水位）：成功绘制新蓝帧
            val blueBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            publishFrameForTesting(frameSource, blueBitmap)

            renderBitmap.eraseColor(Color.TRANSPARENT)
            renderPreview(previewView, renderCanvas)
            assertEquals("发布新帧后必须成功绘制全新蓝帧", Color.BLUE, renderBitmap.getPixel(50, 50))
            // 5. 验证在非 RESUMED 的 PiP 模式下，锁屏停止循环且解锁恢复循环
            activity.onPictureInPictureModeChanged(true, activity.resources.configuration)
            val frameView = previewView as VirtualDisplayFrameView

            Shadows.shadowOf(km).setKeyguardLocked(true)
            context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
            ShadowLooper.idleMainLooper()
            assertFalse("锁屏后组件帧循环必须停止", frameView.isFrameLoopRunning)

            Shadows.shadowOf(km).setKeyguardLocked(false)
            context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            ShadowLooper.idleMainLooper()
            assertTrue("非 RESUMED 的 PiP 模式下组件帧循环必须恢复", frameView.isFrameLoopRunning)

            // 6. 验证 activity destroy 时广播接收器解绑
            assertTrue("运行中组件 receiver 处于注册状态", frameView.isScreenLockReceiverRegistered)

            controller.destroy()
            assertFalse("Activity 销毁时组件 receiver 必须安全解绑", frameView.isScreenLockReceiverRegistered)
        } finally {
            frameSource.close()
        }
    }
    @Test
    fun `singleTask onNewIntent updates credentials for subsequent turn without cancelling wrong turn`() {
        runBlocking {
            withTimeout(15_000L) {
                val sessionId = "session-new-intent"
                val displayId = 6
                setVirtualDisplayForTesting(displayId, sessionId)
                DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()

                val app: Application = RuntimeEnvironment.getApplication()
                val previousLibApp = com.rk.libcommons.application
                com.rk.libcommons.application = app
                DatabaseHelper.init(app)

                val assets = Mockito.mock(AssetManager::class.java)
                Mockito.`when`(assets.open(Mockito.anyString())).thenAnswer { call ->
                    File("src/main/assets", call.getArgument<String>(0)).inputStream()
                }
                val wrappedContext = object : ContextWrapper(app) {
                    override fun getAssets(): AssetManager = assets
                    override fun getApplicationContext(): Context = this
                }

                val configStore = CodexRemoteBridgeConfigStore(wrappedContext)
                val previousConfig = configStore.read()
                configStore.write(
                    CodexRemoteBridgeConfig(
                        enabled = true,
                        bridgeUrl = "http://127.0.0.1:1",
                        cwd = "/remote",
                    )
                )

                val instanceField = AgentRuntimeManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
                val previousInstance = instanceField.get(null) as? AgentRuntimeManager
                val constructor = AgentRuntimeManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                val manager = constructor.newInstance(wrappedContext)
                instanceField.set(null, manager)
                val profileStore = AcpAgentProfileStore(wrappedContext)
                profileStore.bindSession(sessionId, REMOTE_CODEX_AGENT_ID)

                val connection = ControlledConnection()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val transport = RemoteCodexAppServerSession(scope, {}, { connection })
                val sessionField = AgentRuntimeManager::class.java.getDeclaredField("session").apply { isAccessible = true }
                sessionField.set(manager, transport)
                transport.start("test-client")

                // 1. Turn 1 运行并正常结束
                val turn1Result = manager.handleMethod(
                    "session/prompt",
                    mapOf(
                        "agentId" to REMOTE_CODEX_AGENT_ID,
                        "sessionId" to sessionId,
                        "text" to "turn 1 prompt",
                    )
                ) as Map<*, *>
                val turn1Id = turn1Result["turnId"] as String
                val task1Id = "agent_turn_${sessionId}_$turn1Id"

                val initialIntent = VirtualDisplayPreviewActivity.createIntent(
                    context = wrappedContext,
                    sessionId = sessionId,
                    displayId = displayId,
                    taskId = task1Id,
                    turnId = turn1Id,
                    stopToken = "stale-turn1-token",
                )

                val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, initialIntent).create().start().resume()
                val activity = controller.get()
                val gateTurn2 = CompletableDeferred<Unit>()
                connection.promptGate = gateTurn2

                try {
                    // 2. Turn 2 启动并在后台挂起执行
                    val turn2Deferred = scope.async(Dispatchers.Default) {
                        manager.handleMethod(
                            "session/prompt",
                            mapOf(
                                "agentId" to REMOTE_CODEX_AGENT_ID,
                                "sessionId" to sessionId,
                                "text" to "turn 2 prompt",
                            )
                        ) as Map<*, *>
                    }

                    withTimeout(5000) {
                        while (TaskRuntime.getActiveTasks().none { it.sessionId == sessionId }) {
                            delay(10)
                        }
                    }

                    val turn2Task = TaskRuntime.getActiveTasks().first { it.sessionId == sessionId }
                    val turn2Id = turn2Task.turnId!!
                    val turn2Token = turn2Task.stopToken
                    val turn2TaskId = turn2Task.taskId

                    val stopBtn = findButtonWithText(activity, "停止任务")
                    assertNotNull(stopBtn)
                    val statusView = findTextViewWithStatus(activity)
                    assertNotNull(statusView)

                    // 3. 在未接收新 Intent 前点击停止任务：必须因轮次不匹配而拒绝，保护新 Turn 2 不被误杀
                    stopBtn!!.performClick()
                    val step3Deadline = System.currentTimeMillis() + 3000L
                    while (statusView.text.toString() == "正在停止任务..." && System.currentTimeMillis() < step3Deadline) {
                        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                        Thread.sleep(20)
                    }
                    assertEquals("持有旧 Intent 凭据时必须拒绝停止新 Turn", "新任务轮次已开始，无法停止", statusView.text.toString())
                    assertNotNull("新 Turn 2 绝不应被旧凭据误杀，TaskRuntime 中依然存活", TaskRuntime.getActiveTask(turn2TaskId))
                    assertTrue("持旧凭据点击 stop 绝不得向线缆发送 session/cancel", connection.writes.none { it["method"]?.asString == "session/cancel" })

                    // 4. 用户从通知栏再次点击“查看屏幕”，单例 Activity 接收 onNewIntent
                    val newTurnIntent = VirtualDisplayPreviewActivity.createIntent(
                        context = wrappedContext,
                        sessionId = sessionId,
                        displayId = displayId,
                        taskId = turn2TaskId,
                        turnId = turn2Id,
                        stopToken = turn2Token,
                    )
                    controller.newIntent(newTurnIntent)
                    assertEquals("更新身份后状态文案复位为任务执行中", "任务执行中", statusView.text.toString())

                    // 5. 再次点击停止任务：此时凭据与当前轮次匹配，触发官方 session/cancel
                    val writesBeforeStop = connection.writes.size
                    stopBtn.performClick()
                    val stopDeadline = System.currentTimeMillis() + 5000L
                    while ((connection.writes.size == writesBeforeStop || statusView.text.toString() == "正在停止任务...") && System.currentTimeMillis() < stopDeadline) {
                        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                        Thread.sleep(20)
                    }
                    assertEquals("点击停止任务后更新文案", "已请求停止任务", statusView.text.toString())

                    val cancelWrites = connection.writes.drop(writesBeforeStop).filter { it["method"]?.asString == "session/cancel" }
                    assertEquals("更新凭据后点击 stop 必须向线缆发出唯一 session/cancel 通知", 1, cancelWrites.size)
                    assertEquals(sessionId, cancelWrites[0].getAsJsonObject("params")["sessionId"]?.asString)

                    // 6. 远端响应后，Turn 2 正常被取消，且 TaskRuntime 租约真实释放
                    gateTurn2.complete(Unit)
                    val turn2Result = turn2Deferred.await()
                    assertEquals(true, turn2Result["completed"])
                    assertEquals("cancelled", turn2Result["status"])

                    assertNull("官方取消后 TaskRuntime 租约必须已真实释放", TaskRuntime.getActiveTask(turn2TaskId))
                    assertFalse("会话中不再存在任何活跃任务", TaskRuntime.hasActiveTasks())
                } finally {
                    gateTurn2.complete(Unit)
                    scope.cancel()
                    runBlocking { connection.close() }
                    val scopeField = AgentRuntimeManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    (scopeField.get(manager) as? CoroutineScope)?.cancel()
                    controller.destroy()
                    configStore.write(previousConfig)
                    profileStore.unbindSession(sessionId)
                    instanceField.set(null, previousInstance)
                    com.rk.libcommons.application = previousLibApp
                    ShadowLooper.idleMainLooper()
                }
            }
        }
    }

    @Test
    fun `onNewIntent rejects unauthorized or mismatched session and finishes activity`() {
        val sessionA = "session-A"
        val sessionB = "session-B"
        val displayId = 7
        setVirtualDisplayForTesting(displayId, sessionA)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()

        val initialIntent = VirtualDisplayPreviewActivity.createIntent(
            context = context,
            sessionId = sessionA,
            displayId = displayId,
        )

        val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, initialIntent).create().start().resume()
        val activity = controller.get()
        try {
            assertFalse("初始合法 Intent 不应 finish", activity.isFinishing)

            // 外部试图通过 onNewIntent 将显示屏指向未授权的 sessionB
            val unauthorizedIntent = VirtualDisplayPreviewActivity.createIntent(
                context = context,
                sessionId = sessionB,
                displayId = displayId,
            )
            controller.newIntent(unauthorizedIntent)

            assertTrue("接收到未授权或不归属的 session onNewIntent 时必须立即 finish", activity.isFinishing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `rebind to new session and display cancels old takeover wait without moving or cancelling new task`() {
        runBlocking {
            withTimeout(15_000L) {
                val sessionA = "session-race-A"
                val displayA = 4
                val sessionB = "session-race-B"
                val displayB = 8
                val taskB = "task-race-B"
                val turnB = "turn-race-B"

                setVirtualDisplayForTesting(displayA, sessionA)

                val fakeBridge = FakeTestDisplayBridge()
                DeviceDisplayManager.displayBridge = fakeBridge

                val app: Application = RuntimeEnvironment.getApplication()
                val previousLibApp = com.rk.libcommons.application
                com.rk.libcommons.application = app
                DatabaseHelper.init(app)

                val assets = Mockito.mock(AssetManager::class.java)
                Mockito.`when`(assets.open(Mockito.anyString())).thenAnswer { call ->
                    File("src/main/assets", call.getArgument<String>(0)).inputStream()
                }
                val wrappedContext = object : ContextWrapper(app) {
                    override fun getAssets(): AssetManager = assets
                    override fun getApplicationContext(): Context = this
                }

                val configStore = CodexRemoteBridgeConfigStore(wrappedContext)
                val previousConfig = configStore.read()
                configStore.write(
                    CodexRemoteBridgeConfig(
                        enabled = true,
                        bridgeUrl = "http://127.0.0.1:1",
                        cwd = "/remote",
                    )
                )

                val instanceField = AgentRuntimeManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
                val previousInstance = instanceField.get(null) as? AgentRuntimeManager
                val constructor = AgentRuntimeManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
                val manager = constructor.newInstance(wrappedContext)
                instanceField.set(null, manager)
                val profileStore = AcpAgentProfileStore(wrappedContext)
                profileStore.bindSession(sessionA, REMOTE_CODEX_AGENT_ID)
                profileStore.bindSession(sessionB, REMOTE_CODEX_AGENT_ID)

                val connection = ControlledConnection()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val transport = RemoteCodexAppServerSession(scope, {}, { connection })
                val sessionField = AgentRuntimeManager::class.java.getDeclaredField("session").apply { isAccessible = true }
                sessionField.set(manager, transport)
                transport.start("test-client")

                val gateA = CompletableDeferred<Unit>()
                connection.promptGate = gateA

                var taskAId = ""
                var taskBId = taskB

                // 1. 启动会话 A 的活跃远程任务并挂起在 gateA 上
                val turnADeferred = scope.async(Dispatchers.Default) {
                    manager.handleMethod(
                        "session/prompt",
                        mapOf(
                            "agentId" to REMOTE_CODEX_AGENT_ID,
                            "sessionId" to sessionA,
                            "text" to "A prompt",
                        )
                    ) as Map<*, *>
                }

                withTimeout(5000) {
                    while (TaskRuntime.getActiveTasks().none { it.sessionId == sessionA }) {
                        delay(10)
                    }
                }

                val taskA = TaskRuntime.getActiveTasks().first { it.sessionId == sessionA }
                taskAId = taskA.taskId
                val turnAId = taskA.turnId!!
                val tokenA = taskA.stopToken

                val intentA = VirtualDisplayPreviewActivity.createIntent(
                    context = wrappedContext,
                    sessionId = sessionA,
                    displayId = displayA,
                    taskId = taskAId,
                    turnId = turnAId,
                    stopToken = tokenA,
                )

                val controller = Robolectric.buildActivity(VirtualDisplayPreviewActivity::class.java, intentA).create().start().resume()
                val activity = controller.get()
                try {
                    val presentBtn = findButtonWithText(activity, "移交主屏")
                    assertNotNull(presentBtn)
                    val statusView = findTextViewWithStatus(activity)
                    assertNotNull(statusView)

                    // A 点击「移交主屏」，由于 sessionA 远端任务活跃，进入有界等待轮询
                    presentBtn!!.performClick()
                    val waitPresentDeadline = System.currentTimeMillis() + 3000L
                    while (presentBtn.isEnabled && System.currentTimeMillis() < waitPresentDeadline) {
                        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                        Thread.sleep(20)
                    }
                    assertEquals("正在停止任务并准备移交...", statusView.text.toString())
                    assertFalse("A 接管进行中时移交按钮应被禁用", presentBtn.isEnabled)

                    // 2. 模拟新会话 B 及新虚拟屏启动
                    setVirtualDisplayForTesting(displayB, sessionB)
                    TaskRuntime.start(
                        context = wrappedContext,
                        taskId = taskBId,
                        sessionId = sessionB,
                        turnId = turnB,
                        title = "B 任务",
                    )
                    val tokenB = TaskRuntime.getActiveTask(taskBId)!!.stopToken

                    val intentB = VirtualDisplayPreviewActivity.createIntent(
                        context = wrappedContext,
                        sessionId = sessionB,
                        displayId = displayB,
                        taskId = taskBId,
                        turnId = turnB,
                        stopToken = tokenB,
                    )
                    controller.newIntent(intentB)

                    // 重绑定后 UI 应立即更新为 B 的执行状态，且移交按钮重新可用
                    assertEquals("任务执行中", statusView.text.toString())
                    assertTrue("重入 B 后移交按钮应立即可用", presentBtn.isEnabled)

                    // 3. 解除旧 A 的阻塞（释放 gateA 并结束 taskA）
                    gateA.complete(Unit)
                    turnADeferred.await()
                    TaskRuntime.finish(wrappedContext, taskAId)

                    val waitOldDeadline = System.currentTimeMillis() + 1000L
                    while (System.currentTimeMillis() < waitOldDeadline) {
                        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                        Thread.sleep(20)
                    }

                    // 4. 验证核心不变式：B 任务不 move、不 cancel
                    assertNotNull("B 任务绝不应被旧 A 的协程取消", TaskRuntime.getActiveTask(taskBId))
                    assertTrue("B 任务凭据必须保持有效", TaskRuntime.validateStopToken(taskBId, tokenB))
                    assertEquals("旧 A 协程绝不应向底层发出任何 moveRootTask 迁移", 0, fakeBridge.moveCalls.size)
                    assertEquals("状态文本不得被旧 A 的超时或异常覆盖", "任务执行中", statusView.text.toString())

                    // 5. 只有在当前绑定下显式对 B 点击接管，B 才可执行动作
                    presentBtn.performClick()
                    assertEquals("正在停止任务并准备移交...", statusView.text.toString())

                    // 结束 B 任务，使 B 的移交条件满足
                    TaskRuntime.finish(wrappedContext, taskBId)
                    val waitMoveDeadline = System.currentTimeMillis() + 5000L
                    while (fakeBridge.moveCalls.isEmpty() && System.currentTimeMillis() < waitMoveDeadline) {
                        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
                        Thread.sleep(20)
                    }

                    assertEquals("显式接管 B 成功后必须触发一次底层迁移", 1, fakeBridge.moveCalls.size)
                    val moveCall = fakeBridge.moveCalls.first()
                    assertEquals("迁移根任务必须对应 mock task", 101, moveCall.first)
                    assertEquals("源屏幕必须为 B 的虚拟屏", displayB, moveCall.second)
                    assertEquals("目标屏幕必须为主屏", Display.DEFAULT_DISPLAY, moveCall.third)
                    assertTrue("显式移交 B 成功后页面应正常 finish", activity.isFinishing)
                } finally {
                    gateA.complete(Unit)
                    scope.cancel()
                    runBlocking { connection.close() }
                    val scopeField = AgentRuntimeManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    (scopeField.get(manager) as? CoroutineScope)?.cancel()
                    controller.destroy()
                    if (taskAId.isNotEmpty()) TaskRuntime.finish(wrappedContext, taskAId)
                    TaskRuntime.finish(wrappedContext, taskBId)
                    configStore.write(previousConfig)
                    profileStore.unbindSession(sessionA)
                    profileStore.unbindSession(sessionB)
                    instanceField.set(null, previousInstance)
                    com.rk.libcommons.application = previousLibApp
                    ShadowLooper.idleMainLooper()
                }
            }
        }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `virtual display frame view respects binding previewActive and lock screen gates`() {
        val sessionId = "session-frame-view-test"
        val displayId = 12
        val frameSource = VirtualScreenFrameSource(100, 100)
        setVirtualDisplayForTesting(displayId, sessionId, frameSource)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()

        val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        publishFrameForTesting(frameSource, redBitmap)

        val frameView = VirtualDisplayFrameView(context)
        frameView.bind(displayId, sessionId)
        frameView.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        frameView.layout(0, 0, 100, 100)

        val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(renderBitmap)

        // 1. 未附着时不绘图，保持全黑
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals(Color.BLACK, renderBitmap.getPixel(50, 50))
        // 2. 真实挂载到 Activity Window 并 visible，驱动 looper idle
        val activityController = Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume()
        val testActivity = activityController.get()
        testActivity.setContentView(frameView)
        activityController.visible()
        ShadowLooper.idleMainLooper()
        assertTrue("附着后 receiver 已注册", frameView.isScreenLockReceiverRegistered)
        assertTrue("满足条件时帧循环开启", frameView.isFrameLoopRunning)

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("附着后成功绘制最新红帧", Color.RED, renderBitmap.getPixel(50, 50))

        // 3. setPreviewActive(false) 时停帧并清黑，置新帧门槛
        frameView.setPreviewActive(false)
        assertFalse(frameView.isFrameLoopRunning)
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals(Color.BLACK, renderBitmap.getPixel(50, 50))

        // 恢复 previewActive(true)，因置门槛，未发布新帧前拦截旧红帧保持全黑
        frameView.setPreviewActive(true)
        assertTrue(frameView.isFrameLoopRunning)
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("恢复 previewActive 时若无新帧，保持全黑拦截旧红帧", Color.BLACK, renderBitmap.getPixel(50, 50))

        val yellowBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        publishFrameForTesting(frameSource, yellowBitmap)
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("发布新黄帧后成功绘制", Color.YELLOW, renderBitmap.getPixel(50, 50))
        // 4. 模拟锁屏与解锁门槛
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(km).setKeyguardLocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()
        assertFalse("锁屏后帧循环停止", frameView.isFrameLoopRunning)

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("锁屏时画布强制全黑", Color.BLACK, renderBitmap.getPixel(50, 50))

        // 解锁（此时无新帧产生，保持全黑）
        Shadows.shadowOf(km).setKeyguardLocked(false)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        ShadowLooper.idleMainLooper()
        assertTrue("解锁后恢复帧循环", frameView.isFrameLoopRunning)

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("解锁后未产生新帧前保持全黑", Color.BLACK, renderBitmap.getPixel(50, 50))

        // 发布新蓝帧后成功渲染
        val blueBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        publishFrameForTesting(frameSource, blueBitmap)

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("发布新帧后成功绘制蓝帧", Color.BLUE, renderBitmap.getPixel(50, 50))

        // 5. 销毁 Activity 解绑
        activityController.destroy()
        ShadowLooper.idleMainLooper()
        assertFalse(frameView.isFrameLoopRunning)
        assertFalse(frameView.isScreenLockReceiverRegistered)
        frameSource.close()
    }

    @Test
    fun `takeover controller handles cancellation timeout and successful presentation`() {
        runBlocking {
            withTimeout(10_000L) {
                val sessionId = "session-controller-test"
                val displayId = 14
                setVirtualDisplayForTesting(displayId, sessionId)
                val fakeBridge = FakeTestDisplayBridge()
                DeviceDisplayManager.displayBridge = fakeBridge

                val controller = VirtualDisplayTakeoverController(context)
                var lastStatus = ""

                // 1. isBindingCurrent 为 false 时抛出 CancellationException
                try {
                    controller.present(
                        sessionId = sessionId,
                        displayId = displayId,
                        taskId = null,
                        turnId = null,
                        stopToken = null,
                        isBindingCurrent = { false },
                        onStatus = { lastStatus = it },
                    )
                    fail("Binding 失效时必须抛出 CancellationException")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    assertEquals("接管已被取代或取消", e.message)
                }

                // 2. 存在多根任务时拒绝移交并报告歧义
                fakeBridge.customRootTasksJson = """[{"taskId":1,"taskKind":"root_task","displayId":$displayId,"userId":0,"packageName":"p1","childTaskIds":[1]},{"taskId":2,"taskKind":"root_task","displayId":$displayId,"userId":0,"packageName":"p2","childTaskIds":[2]}]"""
                val multiResult = controller.present(
                    sessionId = sessionId,
                    displayId = displayId,
                    taskId = null,
                    turnId = null,
                    stopToken = null,
                    isBindingCurrent = { true },
                    onStatus = { lastStatus = it },
                )
                assertFalse("存在多个根任务时必须移交失败", multiResult)
                assertEquals("虚拟屏存在多个根任务，移交目标歧义", lastStatus)

                // 3. 正常单根任务且无活跃 Turn，成功移交主屏
                fakeBridge.customRootTasksJson = null
                val successResult = controller.present(
                    sessionId = sessionId,
                    displayId = displayId,
                    taskId = null,
                    turnId = null,
                    stopToken = null,
                    isBindingCurrent = { true },
                    onStatus = { lastStatus = it },
                )
                assertTrue("单根任务正常移交必须返回 true", successResult)
                assertEquals("已成功移交至主屏", lastStatus)
                assertEquals(1, fakeBridge.moveCalls.size)
                assertEquals(101, fakeBridge.moveCalls[0].first)
                assertEquals(displayId, fakeBridge.moveCalls[0].second)
                assertEquals(Display.DEFAULT_DISPLAY, fakeBridge.moveCalls[0].third)
            }
        }
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `parent container hidden stops loop and clears canvas to black with zero drawing`() {
        val sessionId = "session-parent-hidden"
        val displayId = 20
        val frameSource = VirtualScreenFrameSource(100, 100)
        setVirtualDisplayForTesting(displayId, sessionId, frameSource)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()
        publishFrameForTesting(frameSource, Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
        val activityController = Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume()
        val testActivity = activityController.get()

        val parentContainer = android.widget.FrameLayout(testActivity).apply {
            visibility = View.VISIBLE
        }
        val frameView = VirtualDisplayFrameView(testActivity).apply {
            visibility = View.VISIBLE
        }
        parentContainer.addView(frameView)
        testActivity.setContentView(parentContainer)
        activityController.visible()
        ShadowLooper.idleMainLooper()

        frameView.bind(displayId, sessionId)
        frameView.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        frameView.layout(0, 0, 100, 100)

        val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(renderBitmap)

        // 父容器与 View 均为 VISIBLE，且真实 mount 到 Window，正常渲染
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("父容器可见时必须绘制红帧", Color.RED, renderBitmap.getPixel(50, 50))
        assertTrue("满足条件时帧循环处于运行状态", frameView.isFrameLoopRunning)

        // 父容器隐藏 (GONE)，触发框架原生 onVisibilityChanged
        parentContainer.visibility = View.GONE
        ShadowLooper.idleMainLooper()
        assertFalse("父容器隐藏时帧循环必须立即停止", frameView.isFrameLoopRunning)
        assertFalse("父容器隐藏时 shouldDrawFrames 必须为 false", frameView.shouldDrawFrames())

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("父容器隐藏时画布必须全黑零绘制", Color.BLACK, renderBitmap.getPixel(50, 50))

        // 重新恢复父容器可见
        parentContainer.visibility = View.VISIBLE
        ShadowLooper.idleMainLooper()
        assertTrue("父容器重新可见时帧循环恢复", frameView.isFrameLoopRunning)
        assertTrue("父容器重新可见时 shouldDrawFrames 恢复", frameView.shouldDrawFrames())

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("父容器恢复后能够正常绘制当前帧", Color.RED, renderBitmap.getPixel(50, 50))

        // 验证真正从 Window 移除 detach 后，必须置 fresh gate，旧红帧被拦截，唯有全新绿帧发布才再显示
        parentContainer.removeView(frameView)
        ShadowLooper.idleMainLooper()
        parentContainer.addView(frameView)
        ShadowLooper.idleMainLooper()

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("重新附着时门槛拦截旧红帧保持全黑", Color.BLACK, renderBitmap.getPixel(50, 50))

        val greenBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        publishFrameForTesting(frameSource, greenBitmap)
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("重新附着后发布新绿帧成功绘制", Color.GREEN, renderBitmap.getPixel(50, 50))

        activityController.destroy()
        ShadowLooper.idleMainLooper()
        frameSource.close()
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `screen on while locked forbids old frame and requires new frame after unlock`() {
        val sessionId = "session-screen-on-gate"
        val displayId = 21
        val frameSource = VirtualScreenFrameSource(100, 100)
        setVirtualDisplayForTesting(displayId, sessionId, frameSource)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()

        val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        publishFrameForTesting(frameSource, redBitmap)

        val activityController = Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume()
        val testActivity = activityController.get()
        val frameView = VirtualDisplayFrameView(testActivity)
        testActivity.setContentView(frameView)
        activityController.visible()
        ShadowLooper.idleMainLooper()

        frameView.bind(displayId, sessionId)
        frameView.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        frameView.layout(0, 0, 100, 100)
        val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(renderBitmap)

        // 正常状态
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("初始状态正常绘制红帧", Color.RED, renderBitmap.getPixel(50, 50))

        // 息屏并锁屏
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(km).setKeyguardLocked(true)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        ShadowLooper.idleMainLooper()

        // 亮屏但仍处于锁屏（未解锁 ACTION_USER_PRESENT）
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        ShadowLooper.idleMainLooper()

        assertFalse("锁屏期间帧循环必须停止", frameView.isFrameLoopRunning)
        assertFalse("锁屏期间严禁绘图", frameView.shouldDrawFrames())
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("亮屏未解锁时画布必须强制全黑，严禁偷放旧红帧", Color.BLACK, renderBitmap.getPixel(50, 50))

        // 用户解锁（USER_PRESENT）
        Shadows.shadowOf(km).setKeyguardLocked(false)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        ShadowLooper.idleMainLooper()

        // 解锁后若尚无新帧，旧红帧依然被水位门槛拦截，继续保持全黑
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("解锁瞬间无新帧时依然全黑", Color.BLACK, renderBitmap.getPixel(50, 50))

        // 全新蓝帧到达后正常显示
        val blueBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        publishFrameForTesting(frameSource, blueBitmap)

        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("新蓝帧到达后成功绘制", Color.BLUE, renderBitmap.getPixel(50, 50))

        activityController.destroy()
        ShadowLooper.idleMainLooper()
        frameSource.close()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `manual state detection sets watermark without broadcast and controller aborts early if cancelled`() {
        val sessionId = "session-manual-gate"
        val displayId = 22
        val frameSource = VirtualScreenFrameSource(100, 100)
        setVirtualDisplayForTesting(displayId, sessionId, frameSource)
        DeviceDisplayManager.displayBridge = FakeTestDisplayBridge()

        val redBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        publishFrameForTesting(frameSource, redBitmap)
        val activityController = Robolectric.buildActivity(android.app.Activity::class.java).create().start().resume()
        val testActivity = activityController.get()
        val frameView = VirtualDisplayFrameView(testActivity)
        testActivity.setContentView(frameView)
        activityController.visible()
        ShadowLooper.idleMainLooper()

        frameView.bind(displayId, sessionId)
        frameView.measure(
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        frameView.layout(0, 0, 100, 100)
        // 模拟锁屏：手工标记锁屏状态，未通过广播
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(km).setKeyguardLocked(true)
        assertFalse("通过 KeyguardStateMonitor 检测到锁屏，shouldDrawFrames 返回 false", frameView.shouldDrawFrames())
        assertTrue("检测到锁屏后自动置位 requireNewFrameAfterUnlock", frameView.requireNewFrameAfterUnlock)

        // 模拟解锁：手工解除锁屏，不发送 ACTION_USER_PRESENT 广播
        Shadows.shadowOf(km).setKeyguardLocked(false)
        val renderBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val renderCanvas = Canvas(renderBitmap)

        // shouldDrawFrames 首次在解锁状态下执行，自动建立当前序号水位并清除 requireNewFrameAfterUnlock
        assertTrue("锁解除后 shouldDrawFrames 恢复 true", frameView.shouldDrawFrames())
        assertFalse("执行后 requireNewFrameAfterUnlock 被清除", frameView.requireNewFrameAfterUnlock)
        assertTrue("水位序号已成功建立为当前红帧序号", frameView.minRequiredFrameSequence >= 1L)

        // 绘制旧红帧被水位拒绝，保持全黑
        renderBitmap.eraseColor(Color.TRANSPARENT)
        renderPreview(frameView, renderCanvas)
        assertEquals("手工解锁无新帧前旧红帧被水位门槛拦截保持全黑", Color.BLACK, renderBitmap.getPixel(50, 50))

        // Controller 测试：首次调用前已处于取消状态的协程立即抛出异常且不执行停止任务
        runBlocking {
            val controller = VirtualDisplayTakeoverController(context)
            val parentJob = Job()
            parentJob.cancel()
            val deferred = async(parentJob) {
                controller.present(
                    sessionId = sessionId,
                    displayId = displayId,
                    taskId = "task-cancelled-early",
                    turnId = "turn-cancelled-early",
                    stopToken = "token-cancelled-early",
                    isBindingCurrent = { true },
                    onStatus = {},
                )
            }
            try {
                deferred.await()
                fail("已取消协程必须抛出 CancellationException")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 预期行为
            }
            assertNull("早期取消的协程绝不得在 TaskRuntime 中遗留停止任务", TaskRuntime.getActiveTask("task-cancelled-early"))
        }

        activityController.destroy()
        ShadowLooper.idleMainLooper()
        frameSource.close()
    }

    private fun findButtonWithText(activity: VirtualDisplayPreviewActivity, text: String): Button? {
        val decor = activity.window.decorView as? android.view.ViewGroup ?: return null
        return findViewRecursively(decor) { it is Button && it.text.toString() == text } as? Button
    }

    private fun findTextViewWithStatus(activity: VirtualDisplayPreviewActivity): TextView =
        ReflectionHelpers.getField<TextView>(activity, "statusTextView")

    private fun findViewRecursively(parent: android.view.ViewGroup, predicate: (android.view.View) -> Boolean): android.view.View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (predicate(child)) return child
            if (child is android.view.ViewGroup) {
                val found = findViewRecursively(child, predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private class FakeTestDisplayBridge : PrivilegedDisplayBridge {
        val moveCalls = mutableListOf<Triple<Int, Int, Int>>()
        var customRootTasksJson: String? = null

        override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: android.view.Surface, flags: Int): Result<Int> =
            Result.success(4)
        override suspend fun releaseVirtualDisplay(displayId: Int) {}
        override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
        override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override suspend fun getRootTasks(displayId: Int): String =
            customRootTasksJson ?: """[{"taskId":101,"taskKind":"root_task","displayId":$displayId,"userId":0,"packageName":"com.example.calc","childTaskIds":[101]}]"""
        override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String {
            moveCalls.add(Triple(taskId, sourceDisplayId, targetDisplayId))
            return """{"success":true,"code":"ok","taskId":$taskId,"taskKind":"root_task","sourceDisplayId":$sourceDisplayId,"targetDisplayId":$targetDisplayId,"verified":true}"""
        }
    }

    private class ControlledConnection : RemoteCodexAppServerConnection {
        override val isRunning = true
        val writes = CopyOnWriteArrayList<JsonObject>()
        private lateinit var stdout: suspend (String) -> Unit
        var promptGate: CompletableDeferred<Unit>? = null
        private val launchedJobs = CopyOnWriteArrayList<Job>()

        override suspend fun start(
            onStdoutLine: suspend (String) -> Unit,
            onStderrLine: suspend (String) -> Unit,
            onExit: suspend (Int?) -> Unit,
        ) {
            stdout = onStdoutLine
        }

        override suspend fun writeLine(line: String) {
            val request = JsonParser.parseString(line).asJsonObject
            writes += request
            val id = request["id"]
            if (id != null) {
                val method = request["method"]?.asString
                if (method == "session/prompt") {
                    val gate = promptGate
                    if (gate != null) {
                        val job = CoroutineScope(Dispatchers.Default).launch {
                            gate.await()
                            val isCancelled = writes.any { it["method"]?.asString == "session/cancel" }
                            val stopReason = if (isCancelled) "cancelled" else "completed"
                            stdout("""{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"$stopReason"}}""")
                        }
                        launchedJobs += job
                    } else {
                        stdout("""{"jsonrpc":"2.0","id":$id,"result":{"stopReason":"completed"}}""")
                    }
                } else {
                    stdout("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
                }
            }
        }

        override suspend fun close() {
            launchedJobs.forEach { it.cancel() }
            launchedJobs.clear()
        }
    }
}
