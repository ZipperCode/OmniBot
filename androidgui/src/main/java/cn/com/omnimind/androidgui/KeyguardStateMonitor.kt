package cn.com.omnimind.androidgui

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.view.Display

/** Callback invoked when a GUI action requires the user to unlock the device. */
fun interface DeviceUnlockAwaiter {
    suspend fun awaitUnlock(context: Context, displayId: Int): Boolean
}

/**
 * Monitors screen interactive and keyguard locked states for display operations.
 *
 * Guarantees:
 * - Strictly respects Android secure lockscreen; never attempts to store passwords or auto-unlock.
 * - Does NOT use ALWAYS_UNLOCKED to bypass lockscreen.
 * - All GUI displays (both main and virtual) check secure keyguard first before any action.
 * - When locked, prompts and awaits explicit user unlock via lease-scoped [DeviceUnlockAwaiter].
 * - Distinguishes physical display from virtual displays: physical screen off (!isInteractive)
 *   ONLY stops physical display 0 after lockscreen checks; virtual displays continue processing when unlocked.
 * - When device remains locked or user rejects, halts safely without blind clicks.
 */
object KeyguardStateMonitor {
    @Volatile
    var unlockAwaiter: DeviceUnlockAwaiter? = null

    fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    fun isInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive != false
    }

    /**
     * Checks interactive and keyguard states for [displayId].
     *
     * @return true if the screen is ready for GUI actions; false if locked and awaiting unlock.
     * @throws AndroidGuiDisplayOffException if the physical display (display 0) is off and not securely locked.
     */
    suspend fun ensureInteractiveAndUnlocked(
        context: Context?,
        displayId: Int = Display.DEFAULT_DISPLAY,
        leaseOwnerId: String? = null,
    ): Boolean {
        if (context == null) return true

        // 1. All displays (main and virtual) must respect secure keyguard
        if (isKeyguardLocked(context)) {
            val awaiter = DisplayInputCoordinator.getLeaseUnlockAwaiter(displayId, leaseOwnerId)
                ?: unlockAwaiter
                ?: return false
            val unlocked = awaiter.awaitUnlock(context, displayId)
            if (!unlocked || isKeyguardLocked(context)) {
                return false
            }
        }

        // 2. Physical display 0 checks physical screen interactivity AFTER keyguard check
        if (displayId == Display.DEFAULT_DISPLAY) {
            if (!isInteractive(context)) {
                throw AndroidGuiDisplayOffException()
            }
        }

        return true
    }
}
