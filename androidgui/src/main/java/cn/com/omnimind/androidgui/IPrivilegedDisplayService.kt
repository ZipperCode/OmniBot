package cn.com.omnimind.androidgui

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.view.Display
import android.view.Surface
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Binder interface for privileged display operations running under UID 0 (Root) or UID 2000 (Shell).
 *
 * Implemented directly in Kotlin to avoid AGP AIDL plugin dependencies while preserving standard
 * Android Binder IPC semantics for passing Parcelable [Surface].
 */
interface IPrivilegedDisplayService : IInterface {
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Int

    fun releaseVirtualDisplay(displayId: Int)

    fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean

    fun injectTap(displayId: Int, x: Int, y: Int): Boolean

    fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    fun launchActivity(displayId: Int, packageName: String): Boolean

    fun destroy()

    fun getServiceUid(): Int

    companion object {
        const val DESCRIPTOR = "cn.com.omnimind.androidgui.IPrivilegedDisplayService"
        const val TRANSACTION_createVirtualDisplay = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_releaseVirtualDisplay = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_injectKeyEvent = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_injectTap = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_injectSwipe = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_launchActivity = IBinder.FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_destroy = IBinder.FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_getServiceUid = IBinder.FIRST_CALL_TRANSACTION + 8

        fun asInterface(binder: IBinder?): IPrivilegedDisplayService? {
            if (binder == null) return null
            val iin = binder.queryLocalInterface(DESCRIPTOR)
            if (iin is IPrivilegedDisplayService) return iin
            return PrivilegedDisplayServiceProxy(binder)
        }
    }
}

/**
 * Client-side Binder proxy for [IPrivilegedDisplayService].
 */
class PrivilegedDisplayServiceProxy(
    private val remote: IBinder,
) : IPrivilegedDisplayService {
    override fun asBinder(): IBinder = remote

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeString(name)
            data.writeInt(width)
            data.writeInt(height)
            data.writeInt(dpi)
            surface.writeToParcel(data, 0)
            data.writeInt(flags)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_createVirtualDisplay, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(displayId)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_releaseVirtualDisplay, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeInt(keyCode)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_injectKeyEvent, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeInt(x)
            data.writeInt(y)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_injectTap, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeInt(x1)
            data.writeInt(y1)
            data.writeInt(x2)
            data.writeInt(y2)
            data.writeLong(durationMs)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_injectSwipe, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun launchActivity(displayId: Int, packageName: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(displayId)
            data.writeString(packageName)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_launchActivity, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun destroy() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_destroy, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun getServiceUid(): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_getServiceUid, data, reply, 0)
            reply.readException()
            reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Server-side Binder Stub running inside UID 0 (Root) or UID 2000 (Shell) process.
 *
 * Performs real privileged VirtualDisplay creation with system flags and executes secondary display
 * input events directly as UID 0 without app permission barriers.
 */
open class PrivilegedDisplayServiceStub(
    private val context: Context? = null,
    private val allowedClientUid: Int? = null,
) : Binder(), IPrivilegedDisplayService {

    private val virtualDisplays = ConcurrentHashMap<Int, VirtualDisplay>()

    init {
        attachInterface(this, IPrivilegedDisplayService.DESCRIPTOR)
    }

    override fun asBinder(): IBinder = this
    override fun getServiceUid(): Int = Process.myUid()

    private fun checkCallingUid() {
        val callingUid = Binder.getCallingUid()
        if (callingUid != 0 && callingUid != 2000 && allowedClientUid != null && callingUid != allowedClientUid) {
            throw SecurityException("Calling UID $callingUid is not authorized to access IPrivilegedDisplayService")
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(IPrivilegedDisplayService.DESCRIPTOR)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_createVirtualDisplay -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val name = data.readString().orEmpty()
                val width = data.readInt()
                val height = data.readInt()
                val dpi = data.readInt()
                val surface = Surface.CREATOR.createFromParcel(data)
                val displayFlags = data.readInt()
                val displayId = createVirtualDisplay(name, width, height, dpi, surface, displayFlags)
                reply?.writeNoException()
                reply?.writeInt(displayId)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_releaseVirtualDisplay -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val displayId = data.readInt()
                releaseVirtualDisplay(displayId)
                reply?.writeNoException()
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_injectKeyEvent -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val displayId = data.readInt()
                val keyCode = data.readInt()
                val success = injectKeyEvent(displayId, keyCode)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_injectTap -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val displayId = data.readInt()
                val x = data.readInt()
                val y = data.readInt()
                val success = injectTap(displayId, x, y)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_injectSwipe -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val displayId = data.readInt()
                val x1 = data.readInt()
                val y1 = data.readInt()
                val x2 = data.readInt()
                val y2 = data.readInt()
                val durationMs = data.readLong()
                val success = injectSwipe(displayId, x1, y1, x2, y2, durationMs)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_launchActivity -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val displayId = data.readInt()
                val packageName = data.readString().orEmpty()
                val success = launchActivity(displayId, packageName)
                reply?.writeNoException()
                reply?.writeInt(if (success) 1 else 0)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_destroy -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                destroy()
                reply?.writeNoException()
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_getServiceUid -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                reply?.writeNoException()
                reply?.writeInt(getServiceUid())
                return true
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Int {
        val dm = obtainDisplayManager() ?: return -1
        val vd = runCatching {
            dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
        }.getOrNull() ?: return -1

        val displayId = vd.display.displayId
        virtualDisplays[displayId] = vd
        applyDisplayTweaks(displayId)
        return displayId
    }

    override fun releaseVirtualDisplay(displayId: Int) {
        // 仅释放由本 Helper 实例创建并持有的虚拟屏，不触碰系统其他物理屏或未注册屏幕
        val vd = virtualDisplays.remove(displayId)
        vd?.let { runCatching { it.release() } }
    }

    override fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean {
        return executeRootCommand("/system/bin/input", "-d", displayId.toString(), "keyevent", keyCode.toString())
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        return executeRootCommand("/system/bin/input", "-d", displayId.toString(), "tap", x.toString(), y.toString())
    }

    override fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        return executeRootCommand(
            "/system/bin/input",
            "-d",
            displayId.toString(),
            "swipe",
            x1.toString(),
            y1.toString(),
            x2.toString(),
            y2.toString(),
            durationMs.toString(),
        )
    }

    override fun launchActivity(displayId: Int, packageName: String): Boolean {
        // 先强停目标应用，防止目标应用已在主屏运行时仅被激活唤醒而不迁移至虚拟屏（IzukiJS 参考实现一致语义）
        executeRootCommand("/system/bin/am", "force-stop", packageName)
        return executeRootCommand(
            "/system/bin/am",
            "start",
            "-W",
            "--display",
            displayId.toString(),
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            "-p",
            packageName,
        )
    }
    override fun destroy() {
        virtualDisplays.values.forEach { vd ->
            runCatching { vd.release() }
        }
        virtualDisplays.clear()
        runCatching { Looper.myLooper()?.quitSafely() }
    }

    private fun obtainDisplayManager(): DisplayManager? {
        val baseContext = context ?: runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThread = runCatching {
                activityThreadClass.getMethod("currentActivityThread").invoke(null)
            }.getOrNull() ?: runCatching {
                activityThreadClass.getMethod("systemMain").invoke(null)
            }.getOrNull()
            if (currentActivityThread != null) {
                val getSystemContext = activityThreadClass.getMethod("getSystemContext")
                getSystemContext.invoke(currentActivityThread) as? Context
            } else null
        }.getOrNull()
        if (baseContext != null) {
            val uid = Process.myUid()
            val packageName = "com.android.shell"
            val wrapped = object : ContextWrapper(baseContext) {
                override fun getPackageName(): String = packageName
                override fun getOpPackageName(): String = packageName
            }
            return runCatching {
                val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
                constructor.isAccessible = true
                constructor.newInstance(wrapped) as DisplayManager
            }.getOrNull() ?: baseContext.getSystemService(DisplayManager::class.java)
        }
        return null
    }

    private fun applyDisplayTweaks(displayId: Int) {
        runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, Context.WINDOW_SERVICE) as? IBinder ?: return
            val wmClass = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = wmClass.getMethod("asInterface", IBinder::class.java)
            val wm = asInterface.invoke(null, binder) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val setDisplayImePolicy = wm.javaClass.getMethod(
                    "setDisplayImePolicy",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                setDisplayImePolicy.invoke(wm, displayId, 2 /* HIDE */)
            }

            val freezeRotation = wm.javaClass.getMethod(
                "freezeRotation",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            freezeRotation.invoke(wm, displayId, 0 /* Surface.ROTATION_0 */)
        }
    }

    private fun executeRootCommand(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).start()
        process.waitFor() == 0
    }.getOrDefault(false)
}
