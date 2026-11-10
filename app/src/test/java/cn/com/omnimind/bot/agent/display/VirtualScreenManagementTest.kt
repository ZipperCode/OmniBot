package cn.com.omnimind.bot.agent.display

import android.app.Application
import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.androidgui.VirtualDisplayInfo
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskWaitingStatus
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class VirtualScreenManagementTest {

    private lateinit var context: Context
    private lateinit var management: VirtualScreenManagement
    private lateinit var fakeBridge: FakeTestDisplayBridge

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

    private class TestResult : MethodChannel.Result {
        @Volatile var successResult: Any? = null
        @Volatile var errorCode: String? = null
        @Volatile var errorMessage: String? = null
        @Volatile var errorDetails: Any? = null
        @Volatile var notImplementedCalled = false
        @Volatile var completed = false

        override fun success(result: Any?) {
            successResult = result
            completed = true
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            this.errorCode = errorCode
            this.errorMessage = errorMessage
            this.errorDetails = errorDetails
            completed = true
        }

        override fun notImplemented() {
            notImplementedCalled = true
            completed = true
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.resetForTesting()
        DeviceDisplayManager.displayBridge = null
        fakeBridge = FakeTestDisplayBridge()
        management = VirtualScreenManagement(context)
    }

    @After
    fun tearDown() {
        management.close()
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        TaskRuntime.resetForTesting()
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.resetForTesting()
        DeviceDisplayManager.displayBridge = null
    }

    private fun setVirtualDisplayForTesting(
        displayId: Int,
        ownerId: String?,
        width: Int? = null,
        height: Int? = null,
        dpi: Int? = null,
    ) {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        val infoField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayInfo").apply { isAccessible = true }
        field.set(DeviceDisplayManager, ownerId)
        idField.setInt(DeviceDisplayManager, displayId)
        val info = if (displayId != Display.DEFAULT_DISPLAY && width != null && height != null && dpi != null) {
            VirtualDisplayInfo(displayId, width, height, dpi)
        } else null
        infoField.set(DeviceDisplayManager, info)
        if (ownerId != null && displayId != Display.DEFAULT_DISPLAY) {
            DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, ownerId)
            DeviceDisplayManager.setSessionDisplayTarget(ownerId, displayId)
            DeviceDisplayManager.displayBridge = fakeBridge
        } else {
            DeviceDisplayManager.displayBridge = null
        }
    }

    private fun callMethod(name: String, args: Map<String, Any?> = emptyMap(), timeoutMs: Long = 5000L): TestResult {
        val call = MethodCall(name, args)
        val result = TestResult()
        val handled = management.handle(call, result)
        assertTrue("方法 $name 必须被处理", handled)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!result.completed && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertTrue("方法 $name 响应超时", result.completed)
        return result
    }

    @Test
    fun `getVirtualScreenState returns absent when no virtual display active and never leaks stopToken`() {
        val result = callMethod("getVirtualScreenState")
        assertNull(result.errorCode)
        val map = result.successResult as? Map<*, *>
        assertNotNull(map)
        assertEquals("absent", map!!["state"])
        assertNull(map["displayId"])
        assertNull(map["sessionId"])
        assertNull(map["width"])
        assertNull(map["height"])
        assertNull(map["dpi"])
        assertNull(map["taskId"])
        assertNull(map["turnId"])
        assertFalse("绝不能向前端暴露 stopToken", map.containsKey("stopToken"))
    }

    @Test
    fun `getVirtualScreenState returns null dimensions when factual specs are unavailable`() {
        val displayId = 4
        val sessionId = "session-no-dims"
        // 仅设置屏幕与所有者，不注入尺寸
        setVirtualDisplayForTesting(displayId, sessionId)

        val result = callMethod("getVirtualScreenState")
        assertNull(result.errorCode)
        val map = result.successResult as? Map<*, *>
        assertNotNull(map)
        assertEquals("retained", map!!["state"])
        assertEquals(displayId, map["displayId"])
        assertEquals(sessionId, map["sessionId"])
        assertNull("无事实规格时禁止编造默认尺寸", map["width"])
        assertNull("无事实规格时禁止编造默认尺寸", map["height"])
        assertNull("无事实规格时禁止编造默认尺寸", map["dpi"])
    }

    @Test
    fun `getVirtualScreenState returns running with factual dimensions and active task`() {
        val displayId = 5
        val sessionId = "session-test-running"
        val taskId = "task-turn-1"
        val turnId = "turn-1"

        setVirtualDisplayForTesting(displayId, sessionId, width = 1080, height = 2400, dpi = 420)
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = turnId,
            title = "Running Task",
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId)

        val result = callMethod("getVirtualScreenState")
        assertNull(result.errorCode)
        val map = result.successResult as? Map<*, *>
        assertNotNull(map)
        assertEquals("running", map!!["state"])
        assertEquals(displayId, map["displayId"])
        assertEquals(sessionId, map["sessionId"])
        assertEquals(1080, map["width"])
        assertEquals(2400, map["height"])
        assertEquals(420, map["dpi"])
        assertEquals(taskId, map["taskId"])
        assertEquals(turnId, map["turnId"])
        assertEquals("Running Task", map["taskTitle"])
        assertFalse("绝不能向前端暴露 stopToken", map.containsKey("stopToken"))
    }

    @Test
    fun `getVirtualScreenState returns waiting when active task has waiting status`() {
        val displayId = 6
        val sessionId = "session-test-waiting"
        val taskId = "task-turn-wait"
        val turnId = "turn-wait"

        setVirtualDisplayForTesting(displayId, sessionId)
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            conversationId = 1001L,
            sessionId = sessionId,
            turnId = turnId,
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId)
        TaskRuntime.updateWaitingStatus(
            context = context,
            conversationId = 1001L,
            waitingStatus = TaskWaitingStatus.WaitingForUnlock(
                conversationId = 1001L,
                turnId = turnId,
                message = "请解锁屏幕以继续",
            ),
        )

        val result = callMethod("getVirtualScreenState")
        assertNull(result.errorCode)
        val map = result.successResult as? Map<*, *>
        assertNotNull(map)
        assertEquals("waiting", map!!["state"])
        assertFalse(map.containsKey("stopToken"))
    }

    @Test
    fun `getVirtualScreenState returns retained when display active without running task`() {
        val displayId = 7
        val sessionId = "session-test-retained"

        setVirtualDisplayForTesting(displayId, sessionId)

        val result = callMethod("getVirtualScreenState")
        assertNull(result.errorCode)
        val map = result.successResult as? Map<*, *>
        assertNotNull(map)
        assertEquals("retained", map!!["state"])
        assertEquals(displayId, map["displayId"])
        assertEquals(sessionId, map["sessionId"])
        assertNull(map["taskId"])
        assertNull(map["turnId"])
        assertEquals("画面已保留", map["statusText"])
        assertFalse(map.containsKey("stopToken"))
    }

    @Test
    fun `openVirtualScreenPreview rejects missing arguments or mismatched session`() {
        val displayId = 8
        val sessionId = "session-open-check"
        setVirtualDisplayForTesting(displayId, sessionId)

        // 缺参数拒绝
        val noArgs = callMethod("openVirtualScreenPreview", emptyMap())
        assertEquals("invalid_argument", noArgs.errorCode)

        // 跨会话拒绝
        val wrongSession = callMethod("openVirtualScreenPreview", mapOf("displayId" to displayId, "sessionId" to "session-other"))
        assertEquals("owner_mismatch", wrongSession.errorCode)

        // 屏幕编号不匹配拒绝
        val wrongDisplay = callMethod("openVirtualScreenPreview", mapOf("displayId" to 999, "sessionId" to sessionId))
        assertEquals("display_mismatch", wrongDisplay.errorCode)
    }


    @Test
    fun `stopVirtualScreenTask rejects missing arguments or mismatched turnId`() {
        val displayId = 10
        val sessionId = "session-stop-turn"
        val taskId = "task-active"
        val realTurnId = "turn-new"
        val oldSnapshotTurnId = "turn-old"

        setVirtualDisplayForTesting(displayId, sessionId)
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = realTurnId,
        )
        TaskRuntime.markScreenTarget(context, sessionId, realTurnId, displayId)

        // 缺参数拒绝
        val missingTurn = callMethod("stopVirtualScreenTask", mapOf("displayId" to displayId, "sessionId" to sessionId, "taskId" to taskId))
        assertEquals("invalid_argument", missingTurn.errorCode)

        // 旧 Turn 凭据拒绝
        val oldTurn = callMethod(
            "stopVirtualScreenTask",
            mapOf(
                "displayId" to displayId,
                "sessionId" to sessionId,
                "taskId" to taskId,
                "turnId" to oldSnapshotTurnId,
            ),
        )
        assertEquals("turn_mismatch", oldTurn.errorCode)
        assertTrue("旧 Turn 快照禁止取消新 Turn", TaskRuntime.hasActiveTasks())

        // 跨会话拒绝
        val wrongSession = callMethod(
            "stopVirtualScreenTask",
            mapOf(
                "displayId" to displayId,
                "sessionId" to "session-other",
                "taskId" to taskId,
                "turnId" to realTurnId,
            ),
        )
        assertEquals("owner_mismatch", wrongSession.errorCode)
    }

    @Test
    fun `closeVirtualScreen rejects missing arguments or running tasks or held lease`() = runBlocking {
        val displayId = 11
        val sessionId = "session-close-guard"

        setVirtualDisplayForTesting(displayId, sessionId)

        // 缺参数拒绝
        val missingArgs = callMethod("closeVirtualScreen", emptyMap())
        assertEquals("invalid_argument", missingArgs.errorCode)

        // 运行中任务拒绝
        val taskId = "task-closing-busy"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
        )
        TaskRuntime.markScreenTarget(context, sessionId, null, displayId)
        val busyResult = callMethod("closeVirtualScreen", mapOf("displayId" to displayId, "sessionId" to sessionId))
        assertEquals("screen_busy", busyResult.errorCode)
        assertEquals(displayId, DeviceDisplayManager.activeVirtualDisplayId())

        TaskRuntime.finish(context, taskId, "end_turn")

        // 持有输入租约时拒绝
        DisplayInputCoordinator.withDisplayLease(displayId, "lease-holder-job") {
            val leaseBusyResult = callMethod("closeVirtualScreen", mapOf("displayId" to displayId, "sessionId" to sessionId))
            assertEquals("screen_busy", leaseBusyResult.errorCode)
            assertEquals(displayId, DeviceDisplayManager.activeVirtualDisplayId())
        }

        // 跨会话拒绝
        val wrongSession = callMethod("closeVirtualScreen", mapOf("displayId" to displayId, "sessionId" to "session-other"))
        assertEquals("owner_mismatch", wrongSession.errorCode)

        // 无任务且无租约时安全释放
        val cleanResult = callMethod("closeVirtualScreen", mapOf("displayId" to displayId, "sessionId" to sessionId))
        assertNull(cleanResult.errorCode)
        assertEquals(true, cleanResult.successResult)
        assertEquals(Display.DEFAULT_DISPLAY, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `closeSessionVirtualDisplays cleans up session screen without affecting other sessions`() {
        val displayId = 12
        val sessionA = "session-owner-A"
        val sessionB = "session-owner-B"

        setVirtualDisplayForTesting(displayId, sessionA)

        // 关闭不同会话 sessionB，sessionA 的虚拟屏保留
        DisplayInputCoordinator.closeSessionVirtualDisplays(sessionB)
        assertEquals(displayId, DeviceDisplayManager.activeVirtualDisplayId())

        // 关闭所有者会话 sessionA，虚拟屏被销毁
        DisplayInputCoordinator.closeSessionVirtualDisplays(sessionA)
        assertEquals(Display.DEFAULT_DISPLAY, DeviceDisplayManager.activeVirtualDisplayId())
    }
}
