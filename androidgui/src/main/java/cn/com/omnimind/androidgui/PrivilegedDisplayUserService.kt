package cn.com.omnimind.androidgui

import android.content.Context
import androidx.annotation.Keep

/**
 * Privileged Display User Service running inside UID 0 (Root) or UID 2000 (Shell) process.
 *
 * Bound via Shizuku / UserService mechanism to execute privileged VirtualDisplay creation
 * with system flags and handle secondary screen input injection.
 */
@Keep
class PrivilegedDisplayUserService(context: Context? = null) : PrivilegedDisplayServiceStub(context)
