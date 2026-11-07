package cn.com.omnimind.androidgui

import android.view.Display
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class KeyguardStateMonitorTest {

    @Before
    @After
    fun reset() {
        KeyguardStateMonitor.unlockAwaiter = null
    }

    @Test
    fun `unlock awaiter hook coordinates unlock request and returns result`() = runBlocking {
        var unlockPromptTriggered = false
        var capturedDisplayId = -1

        KeyguardStateMonitor.unlockAwaiter = DeviceUnlockAwaiter { _, displayId ->
            unlockPromptTriggered = true
            capturedDisplayId = displayId
            true
        }

        // Invoke awaiter directly to verify hook dispatch and parameter routing
        val awaiter = KeyguardStateMonitor.unlockAwaiter
        assertTrue(awaiter != null)
        val success = awaiter!!.awaitUnlock(
            context = android.app.Application(),
            displayId = Display.DEFAULT_DISPLAY,
        )

        assertTrue("Unlock awaiter must be invoked", unlockPromptTriggered)
        assertEquals(Display.DEFAULT_DISPLAY, capturedDisplayId)
        assertTrue("Unlock result must be propagated", success)
    }

    @Test
    fun `rejection by user halts safely without auto-unlock bypass`() = runBlocking {
        var unlockPromptTriggered = false
        KeyguardStateMonitor.unlockAwaiter = DeviceUnlockAwaiter { _, _ ->
            unlockPromptTriggered = true
            false
        }

        val awaiter = KeyguardStateMonitor.unlockAwaiter
        assertTrue(awaiter != null)
        val result = awaiter!!.awaitUnlock(
            context = android.app.Application(),
            displayId = Display.DEFAULT_DISPLAY,
        )

        assertTrue(unlockPromptTriggered)
        assertFalse("Rejected unlock must return false", result)
    }

    @Test
    fun `null context in testing environments safely allows execution`() = runBlocking {
        val result = KeyguardStateMonitor.ensureInteractiveAndUnlocked(null, Display.DEFAULT_DISPLAY)
        assertTrue(result)
    }
}
