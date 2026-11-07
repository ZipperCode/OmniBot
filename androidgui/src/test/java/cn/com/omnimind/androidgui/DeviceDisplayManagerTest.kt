package cn.com.omnimind.androidgui

import android.view.Display
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

}
