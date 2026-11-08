package cn.com.omnimind.androidgui

import android.graphics.Canvas
import android.graphics.Rect
import android.view.Display
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
}
