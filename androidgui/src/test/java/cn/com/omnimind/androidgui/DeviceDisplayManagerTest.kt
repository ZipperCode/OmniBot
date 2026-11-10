package cn.com.omnimind.androidgui

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Display
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviceDisplayManagerTest {

    @Before
    @After
    fun reset() {
        DeviceDisplayManager.destroyVirtualDisplay()
        DeviceDisplayManager.displayBridge = null
        DeviceDisplayManager.privilegedService = null
        DisplayInputCoordinator.resetForTesting()
    }
    private fun setVirtualDisplayForTesting(displayId: Int, ownerId: String?) {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        field.set(DeviceDisplayManager, ownerId)
        idField.setInt(DeviceDisplayManager, displayId)
        if (ownerId != null && displayId != Display.DEFAULT_DISPLAY) {
            DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, ownerId)
            DeviceDisplayManager.setSessionDisplayTarget(ownerId, displayId)
        }
    }


    @Test
    fun `resolving virtual target when no virtual display active throws without fallback`() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceDisplayManager.resolveDisplayId(android.app.Application(), "virtual")
        }
    }

    @Test
    fun `resolving disconnected display id throws without fallback`() {
        val context = object : android.app.Application() {
            override fun getSystemService(name: String): Any? = null
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceDisplayManager.resolveDisplayId(context, "999")
        }
    }

    @Test
    fun `destroyVirtualDisplay with different owner preserves active display`() {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        try {
            field.set(DeviceDisplayManager, "owner-A")
            idField.setInt(DeviceDisplayManager, 3)

            // 跨 owner 销毁被阻止，不误清理其他会话持有的虚拟屏
            DeviceDisplayManager.destroyVirtualDisplay(3, ownerId = "owner-B")
            assertEquals(3, DeviceDisplayManager.activeVirtualDisplayId())

            // 匹配 owner 正常执行清理
            DeviceDisplayManager.destroyVirtualDisplay(3, ownerId = "owner-A")
            assertEquals(Display.DEFAULT_DISPLAY, DeviceDisplayManager.activeVirtualDisplayId())
        } finally {
            field.set(DeviceDisplayManager, null)
            idField.setInt(DeviceDisplayManager, Display.DEFAULT_DISPLAY)
        }
    }

    @Test
    fun `presentToUser rejects cross-session call when virtual display owned by different session`() = runBlocking {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        try {
            field.set(DeviceDisplayManager, "session-A")
            idField.setInt(DeviceDisplayManager, 3)

            val context = object : android.app.Application() {
                override fun getSystemService(name: String): Any? = null
            }

            val result = DeviceDisplayManager.presentToUser(
                context = context,
                packageName = "com.example.app",
                taskId = null,
                sessionOwnerId = "session-B",
                leaseOwnerId = "lease-B",
            )

            assertFalse(result.success)
            assertEquals("cross_session_forbidden", result.diagnostics["code"])
            assertEquals("3", result.diagnostics["sourceDisplayId"])
            assertEquals(3, DeviceDisplayManager.activeVirtualDisplayId())
        } finally {
            field.set(DeviceDisplayManager, null)
            idField.setInt(DeviceDisplayManager, Display.DEFAULT_DISPLAY)
        }
    }

    @Test
    fun `drawVirtualFrame rejects cross-session call and mismatched display`() {
        val field = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        val idField = DeviceDisplayManager::class.java.getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        try {
            field.set(DeviceDisplayManager, "session-A")
            idField.setInt(DeviceDisplayManager, 3)

            val canvas = Canvas()
            val rect = Rect(0, 0, 100, 100)

            // Non matching owner
            val drawnWrongOwner = DeviceDisplayManager.drawVirtualFrame(3, "session-B", canvas, rect)
            assertFalse(drawnWrongOwner)

            // Non matching displayId
            val drawnWrongDisplay = DeviceDisplayManager.drawVirtualFrame(2, "session-A", canvas, rect)
            assertFalse(drawnWrongDisplay)
        } finally {
            field.set(DeviceDisplayManager, null)
            idField.setInt(DeviceDisplayManager, Display.DEFAULT_DISPLAY)
        }
    }

    @Test
    fun `createVirtualDisplay fails when no privileged service or bridge available`() {
        val context = object : android.app.Application() {
            override fun getSystemService(name: String): Any? = null
        }
        val result = DeviceDisplayManager.createVirtualDisplay(context, ownerId = "test-session")
        assertTrue(result.isFailure)
        assertEquals(Display.DEFAULT_DISPLAY, DeviceDisplayManager.activeVirtualDisplayId())
    }

    @Test
    fun `closeVirtualDisplaySafely rejects mismatched owner or replaced display`() = runBlocking {
        setVirtualDisplayForTesting(5, "session-A")
        try {
            // 所有者不匹配时拒绝
            val failOwner = DeviceDisplayManager.closeVirtualDisplaySafely(5, "session-B")
            assertTrue(failOwner.isFailure)
            assertEquals(5, DeviceDisplayManager.activeVirtualDisplayId())

            // 屏幕编号不匹配时拒绝
            val failDisplay = DeviceDisplayManager.closeVirtualDisplaySafely(6, "session-A")
            assertTrue(failDisplay.isFailure)
            assertEquals(5, DeviceDisplayManager.activeVirtualDisplayId())

            // 匹配所有者与屏幕编号时正常释放
            val success = DeviceDisplayManager.closeVirtualDisplaySafely(5, "session-A")
            assertTrue(success.isSuccess)
            assertEquals(Display.DEFAULT_DISPLAY, DeviceDisplayManager.activeVirtualDisplayId())
        } finally {
            DeviceDisplayManager.resetForTesting()
        }
    }

    @Test
    fun `closePrivilegedService preserves service when helper owned by different session`() = runBlocking {
        val fakeService = RecordingDisplayService()
        val ownerField = DeviceDisplayManager::class.java.getDeclaredField("privilegedServiceOwner").apply { isAccessible = true }
        ownerField.set(DeviceDisplayManager, "session-B")
        DeviceDisplayManager.privilegedService = fakeService
        try {
            // 跨会话尝试关闭被拒绝，不触碰其他会话特权服务
            DeviceDisplayManager.closePrivilegedService(ownerId = "session-A")
            assertEquals(0, fakeService.destroyCalls)
            assertEquals(fakeService, DeviceDisplayManager.privilegedService)

            // 归属会话正常释放特权服务
            DeviceDisplayManager.closePrivilegedService(ownerId = "session-B")
            assertEquals(1, fakeService.destroyCalls)
            assertNull(DeviceDisplayManager.privilegedService)
        } finally {
            DeviceDisplayManager.resetForTesting()
        }
    }

    @Test
    fun `closePrivilegedService preserves service when virtual display owned by different session`() = runBlocking {
        val fakeService = RecordingDisplayService()
        setVirtualDisplayForTesting(5, "session-B")
        DeviceDisplayManager.privilegedService = fakeService
        try {
            // 虚拟屏归属其他会话时拒绝关闭，不触碰屏幕与特权服务
            DeviceDisplayManager.closePrivilegedService(ownerId = "session-A")
            assertEquals(0, fakeService.destroyCalls)
            assertEquals(fakeService, DeviceDisplayManager.privilegedService)
            assertEquals(5, DeviceDisplayManager.activeVirtualDisplayId())

            // 归属会话正常释放虚拟屏与特权服务
            DeviceDisplayManager.closePrivilegedService(ownerId = "session-B")
            assertEquals(1, fakeService.destroyCalls)
            assertNull(DeviceDisplayManager.privilegedService)
            assertEquals(Display.DEFAULT_DISPLAY, DeviceDisplayManager.activeVirtualDisplayId())
        } finally {
            DeviceDisplayManager.resetForTesting()
        }
    }

    private class RecordingDisplayService : IPrivilegedDisplayService {
        var destroyCalls = 0
            private set

        override fun asBinder(): android.os.IBinder = error("本用例不使用 Binder 传输")
        override fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: android.view.Surface,
            flags: Int,
        ): Int = error("本用例不创建显示屏")
        override fun releaseVirtualDisplay(displayId: Int) = Unit
        override fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = false
        override fun injectTap(displayId: Int, x: Int, y: Int): Boolean = false
        override fun injectSwipe(
            displayId: Int,
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            durationMs: Long,
        ): Boolean = false
        override fun launchActivity(displayId: Int, packageName: String): Boolean = false
        override fun destroy() {
            destroyCalls++
        }
        override fun getServiceUid(): Int = 2000
        override fun getRootTasks(displayId: Int): String = "[]"
        override fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String =
            error("本用例不迁移任务")
    }
}
