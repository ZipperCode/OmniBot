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
import org.json.JSONArray
import org.json.JSONObject

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

    fun getRootTasks(displayId: Int): String

    fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String

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
        const val TRANSACTION_getRootTasks = IBinder.FIRST_CALL_TRANSACTION + 9
        const val TRANSACTION_moveRootTask = IBinder.FIRST_CALL_TRANSACTION + 10

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

    override fun getRootTasks(displayId: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(displayId)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_getRootTasks, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IPrivilegedDisplayService.DESCRIPTOR)
            data.writeInt(taskId)
            data.writeInt(sourceDisplayId)
            data.writeInt(targetDisplayId)
            remote.transact(IPrivilegedDisplayService.TRANSACTION_moveRootTask, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Server-side Binder Stub running inside UID 0 (Root) or UID 2000 (Shell) process.
 *
 * Performs real privileged VirtualDisplay creation with system flags, secondary display input
 * injection, and task migration without app permission barriers.
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

    protected open fun resolveAppUserId(): Int {
        val clientUid = allowedClientUid ?: Binder.getCallingUid()
        if (clientUid == 0 || clientUid == 2000) {
            return 0
        }
        return runCatching {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val getUserHandleForUid = userHandleClass.getMethod("getUserHandleForUid", Int::class.javaPrimitiveType)
            val handle = getUserHandleForUid.invoke(null, clientUid)
            val getIdentifier = handle.javaClass.getMethod("getIdentifier")
            getIdentifier.invoke(handle) as Int
        }.getOrNull() ?: (clientUid / 100000)
    }

    protected open fun isDisplayAllowed(displayId: Int): Boolean =
        displayId == Display.DEFAULT_DISPLAY || virtualDisplays.containsKey(displayId)

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

            IPrivilegedDisplayService.TRANSACTION_getRootTasks -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val displayId = data.readInt()
                val result = getRootTasks(displayId)
                reply?.writeNoException()
                reply?.writeString(result)
                return true
            }

            IPrivilegedDisplayService.TRANSACTION_moveRootTask -> {
                data.enforceInterface(IPrivilegedDisplayService.DESCRIPTOR)
                checkCallingUid()
                val taskId = data.readInt()
                val sourceDisplayId = data.readInt()
                val targetDisplayId = data.readInt()
                val result = moveRootTask(taskId, sourceDisplayId, targetDisplayId)
                reply?.writeNoException()
                reply?.writeString(result)
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
        // 1. 检查目标应用是否已在主屏或自建虚拟屏存在现有根任务，无损保留任务状态迁移
        val allowedDisplays = mutableSetOf(Display.DEFAULT_DISPLAY)
        allowedDisplays.addAll(virtualDisplays.keys)

        val candidateTasks = allowedDisplays.flatMap { dispId ->
            queryRootTasksInternal(dispId)
        }.filter { it.packageName == packageName }

        // 存在多个相同包名的根任务时拒绝瞎猜，返回失败
        if (candidateTasks.size > 1) {
            return false
        }

        if (candidateTasks.size == 1) {
            val existing = candidateTasks.first()
            if (existing.displayId == displayId) {
                // 已在目标屏幕，仅聚焦
                val ops = probeAtmOps()
                if (ops != null) {
                    runCatching {
                        when (ops) {
                            is AtmOps.Modern -> ops.setFocusedTask.invoke(ops.atm, existing.taskId)
                            is AtmOps.Stack -> ops.setFocusedStack.invoke(ops.atm, existing.taskId)
                        }
                    }
                }
                return true
            } else {
                // 如果已有任务位于主屏且正在前台使用（可见或聚焦），严禁迁移到后台虚拟屏，防止干扰用户当前主屏操作
                if (existing.displayId == Display.DEFAULT_DISPLAY && displayId != Display.DEFAULT_DISPLAY && (existing.visible || existing.isFocused)) {
                    return false
                }
                // 存在非主屏前台的现有根任务，无损迁移保留 taskId
                val moveResultStr = moveRootTask(existing.taskId, existing.displayId, displayId)
                return runCatching {
                    val json = JSONObject(moveResultStr)
                    json.optBoolean("verified", false)
                }.getOrDefault(false)
            }
        }

        // 2. 不存在现有任务时才执行 am start，严禁执行 force-stop 或 CLEAR_TASK
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

    data class TaskSnapshot(
        val taskId: Int,
        val taskKind: String,
        val displayId: Int,
        val userId: Int,
        val packageName: String,
        val childTaskIds: List<Int>,
        val visible: Boolean = false,
        val isFocused: Boolean = false,
    )

    sealed class AtmOps {
        data class Modern(
            val atm: Any,
            val getAllRootTaskInfosOnDisplay: Method,
            val moveRootTaskToDisplay: Method,
            val setFocusedTask: Method,
        ) : AtmOps()

        data class Stack(
            val atm: Any,
            val getAllStackInfos: Method,
            val moveStackToDisplay: Method,
            val setFocusedStack: Method,
        ) : AtmOps()
    }

    protected open fun obtainActivityTaskManager(): Any? = runCatching {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "activity_task") as? IBinder ?: return null
        val atmStub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val asInterface = atmStub.getMethod("asInterface", IBinder::class.java)
        asInterface.invoke(null, binder)
    }.getOrNull()

    protected open fun probeAtmOps(): AtmOps? {
        val atm = obtainActivityTaskManager() ?: return null
        val cls = atm.javaClass

        // 现代 API (Android 11+): getAllRootTaskInfosOnDisplay, moveRootTaskToDisplay, setFocusedTask
        val modern = runCatching {
            val getAll = cls.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
            val move = cls.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val setFocus = cls.getMethod("setFocusedTask", Int::class.javaPrimitiveType)
            AtmOps.Modern(atm, getAll, move, setFocus)
        }.getOrNull()
        if (modern != null) return modern

        // Android 10 旧接口: getAllStackInfos, moveStackToDisplay, setFocusedStack
        val stack = runCatching {
            val getAll = cls.getMethod("getAllStackInfos")
            val move = cls.getMethod("moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val setFocus = cls.getMethod("setFocusedStack", Int::class.javaPrimitiveType)
            AtmOps.Stack(atm, getAll, move, setFocus)
        }.getOrNull()
        return stack
    }

    protected open fun queryRootTasksInternal(displayId: Int): List<TaskSnapshot> {
        if (!isDisplayAllowed(displayId)) return emptyList()
        val ops = probeAtmOps() ?: return emptyList()
        val targetUserId = resolveAppUserId()

        return when (ops) {
            is AtmOps.Modern -> {
                val list = runCatching {
                    ops.getAllRootTaskInfosOnDisplay.invoke(ops.atm, displayId) as? List<*>
                }.getOrNull() ?: return emptyList()

                list.mapNotNull { item ->
                    if (item == null) return@mapNotNull null
                    val itemClass = item.javaClass

                    val taskId = runCatching {
                        itemClass.getField("taskId").getInt(item)
                    }.getOrNull() ?: runCatching {
                        itemClass.getMethod("getTaskId").invoke(item) as Int
                    }.getOrNull() ?: return@mapNotNull null

                    val itemDisplayId = runCatching {
                        itemClass.getField("displayId").getInt(item)
                    }.getOrNull() ?: displayId

                    val itemUserId = runCatching {
                        itemClass.getField("userId").getInt(item)
                    }.getOrNull() ?: 0

                    if (itemUserId != targetUserId) return@mapNotNull null

                    val pkgName = extractPackageName(item)

                    val childIds = runCatching {
                        (itemClass.getField("childTaskIds").get(item) as? IntArray)?.toList()
                    }.getOrNull() ?: runCatching {
                        var cls: Class<*>? = itemClass
                        var res: IntArray? = null
                        while (cls != null && res == null) {
                            res = runCatching {
                                val f = cls?.getDeclaredField("childTaskIds")?.apply { isAccessible = true }
                                f?.get(item) as? IntArray
                            }.getOrNull() ?: runCatching {
                                val f = cls?.getDeclaredField("taskIds")?.apply { isAccessible = true }
                                f?.get(item) as? IntArray
                            }.getOrNull()
                            cls = cls?.superclass
                        }
                        res?.toList()
                    }.getOrNull() ?: return@mapNotNull null

                    val visible = runCatching {
                        itemClass.getField("visible").getBoolean(item)
                    }.getOrNull() ?: runCatching {
                        val f = itemClass.getDeclaredField("visible").apply { isAccessible = true }
                        f.getBoolean(item)
                    }.getOrDefault(false)

                    val isFocused = runCatching {
                        itemClass.getField("isFocused").getBoolean(item)
                    }.getOrNull() ?: runCatching {
                        val f = itemClass.getDeclaredField("isFocused").apply { isAccessible = true }
                        f.getBoolean(item)
                    }.getOrNull() ?: runCatching {
                        val f = itemClass.getDeclaredField("focused").apply { isAccessible = true }
                        f.getBoolean(item)
                    }.getOrDefault(false)

                    TaskSnapshot(
                        taskId = taskId,
                        taskKind = "root_task",
                        displayId = itemDisplayId,
                        userId = itemUserId,
                        packageName = pkgName,
                        childTaskIds = childIds,
                        visible = visible,
                        isFocused = isFocused,
                    )
                }
            }
            is AtmOps.Stack -> {
                val list = runCatching {
                    ops.getAllStackInfos.invoke(ops.atm) as? List<*>
                }.getOrNull() ?: return emptyList()

                list.mapNotNull { item ->
                    if (item == null) return@mapNotNull null
                    val itemClass = item.javaClass

                    val stackId = runCatching {
                        itemClass.getField("stackId").getInt(item)
                    }.getOrNull() ?: return@mapNotNull null

                    val itemDisplayId = runCatching {
                        itemClass.getField("displayId").getInt(item)
                    }.getOrNull() ?: Display.DEFAULT_DISPLAY

                    if (itemDisplayId != displayId) return@mapNotNull null

                    val itemUserId = runCatching {
                        itemClass.getField("userId").getInt(item)
                    }.getOrNull() ?: 0

                    if (itemUserId != targetUserId) return@mapNotNull null

                    val pkgName = extractPackageName(item)

                    val childIds = runCatching {
                        (itemClass.getField("taskIds").get(item) as? IntArray)?.toList()
                    }.getOrNull() ?: runCatching {
                        var cls: Class<*>? = itemClass
                        var res: IntArray? = null
                        while (cls != null && res == null) {
                            res = runCatching {
                                val f = cls?.getDeclaredField("taskIds")?.apply { isAccessible = true }
                                f?.get(item) as? IntArray
                            }.getOrNull()
                            cls = cls?.superclass
                        }
                        res?.toList()
                    }.getOrNull() ?: return@mapNotNull null
                    val visible = runCatching {
                        itemClass.getField("visible").getBoolean(item)
                    }.getOrNull() ?: runCatching {
                        val f = itemClass.getDeclaredField("visible").apply { isAccessible = true }
                        f.getBoolean(item)
                    }.getOrDefault(false)

                    val isFocused = runCatching {
                        itemClass.getField("isFocused").getBoolean(item)
                    }.getOrNull() ?: runCatching {
                        val f = itemClass.getDeclaredField("isFocused").apply { isAccessible = true }
                        f.getBoolean(item)
                    }.getOrNull() ?: runCatching {
                        val f = itemClass.getDeclaredField("focused").apply { isAccessible = true }
                        f.getBoolean(item)
                    }.getOrDefault(false)

                    TaskSnapshot(
                        taskId = stackId,
                        taskKind = "stack",
                        displayId = itemDisplayId,
                        userId = itemUserId,
                        packageName = pkgName,
                        childTaskIds = childIds,
                        visible = visible,
                        isFocused = isFocused,
                    )
                }
            }
        }
    }

    private fun extractPackageName(item: Any): String {
        val cls = item.javaClass
        val topActivity = runCatching {
            val f = cls.getDeclaredField("topActivity").apply { isAccessible = true }
            f.get(item)
        }.getOrNull()
        if (topActivity != null) {
            val pkg = runCatching {
                topActivity.javaClass.getMethod("getPackageName").invoke(topActivity) as String
            }.getOrNull()
            if (!pkg.isNullOrBlank()) return pkg
        }

        val baseActivity = runCatching {
            val f = cls.getDeclaredField("baseActivity").apply { isAccessible = true }
            f.get(item)
        }.getOrNull()
        if (baseActivity != null) {
            val pkg = runCatching {
                baseActivity.javaClass.getMethod("getPackageName").invoke(baseActivity) as String
            }.getOrNull()
            if (!pkg.isNullOrBlank()) return pkg
        }

        val baseIntent = runCatching {
            val f = cls.getDeclaredField("baseIntent").apply { isAccessible = true }
            f.get(item)
        }.getOrNull()
        if (baseIntent != null) {
            val comp = runCatching {
                baseIntent.javaClass.getMethod("getComponent").invoke(baseIntent)
            }.getOrNull()
            if (comp != null) {
                val pkg = runCatching {
                    comp.javaClass.getMethod("getPackageName").invoke(comp) as String
                }.getOrNull()
                if (!pkg.isNullOrBlank()) return pkg
            }
        }

        val taskNames = runCatching {
            val f = cls.getDeclaredField("taskNames").apply { isAccessible = true }
            f.get(item) as? Array<*>
        }.getOrNull()
        if (!taskNames.isNullOrEmpty()) {
            val first = taskNames[0]?.toString().orEmpty()
            val pkg = first.substringBefore("/")
            if (pkg.isNotBlank()) return pkg
        }

        return ""
    }

    override fun getRootTasks(displayId: Int): String {
        val tasks = queryRootTasksInternal(displayId)
        val array = JSONArray()
        for (task in tasks) {
            val obj = JSONObject()
            obj.put("taskId", task.taskId)
            obj.put("taskKind", task.taskKind)
            obj.put("displayId", task.displayId)
            obj.put("userId", task.userId)
            obj.put("packageName", task.packageName)
            val childArray = JSONArray()
            for (cid in task.childTaskIds) {
                childArray.put(cid)
            }
            obj.put("childTaskIds", childArray)
            obj.put("visible", task.visible)
            obj.put("isFocused", task.isFocused)
            array.put(obj)
        }
        return array.toString()
    }

    override fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String {
        if (!isDisplayAllowed(sourceDisplayId) || !isDisplayAllowed(targetDisplayId)) {
            return formatMigrationJson(
                success = false,
                code = "unsupported_display",
                taskId = taskId,
                taskKind = "unknown",
                sourceDisplayId = sourceDisplayId,
                targetDisplayId = targetDisplayId,
                verified = false,
            )
        }

        val ops = probeAtmOps()
        if (ops == null) {
            return formatMigrationJson(
                success = false,
                code = "unsupported",
                taskId = taskId,
                taskKind = "unknown",
                sourceDisplayId = sourceDisplayId,
                targetDisplayId = targetDisplayId,
                verified = false,
            )
        }

        val sourceTasks = queryRootTasksInternal(sourceDisplayId)
        val targetTask = sourceTasks.firstOrNull { it.taskId == taskId }
        if (targetTask == null) {
            return formatMigrationJson(
                success = false,
                code = "task_not_found",
                taskId = taskId,
                taskKind = when (ops) { is AtmOps.Modern -> "root_task"; is AtmOps.Stack -> "stack" },
                sourceDisplayId = sourceDisplayId,
                targetDisplayId = targetDisplayId,
                verified = false,
            )
        }

        val taskKind = targetTask.taskKind
        val expectedChildIds = targetTask.childTaskIds

        val dispatched = runCatching {
            when (ops) {
                is AtmOps.Modern -> {
                    ops.moveRootTaskToDisplay.invoke(ops.atm, taskId, targetDisplayId)
                    runCatching { ops.setFocusedTask.invoke(ops.atm, taskId) }
                    true
                }
                is AtmOps.Stack -> {
                    ops.moveStackToDisplay.invoke(ops.atm, taskId, targetDisplayId)
                    runCatching { ops.setFocusedStack.invoke(ops.atm, taskId) }
                    true
                }
            }
        }.getOrDefault(false)

        if (!dispatched) {
            return formatMigrationJson(
                success = false,
                code = "dispatch_failed",
                taskId = taskId,
                taskKind = taskKind,
                sourceDisplayId = sourceDisplayId,
                targetDisplayId = targetDisplayId,
                verified = false,
            )
        }

        var verified = false
        val maxAttempts = 5
        for (attempt in 1..maxAttempts) {
            val currentTargetTasks = queryRootTasksInternal(targetDisplayId)
            val found = currentTargetTasks.firstOrNull { it.taskId == taskId }
            if (found != null) {
                val matchesChildren = if (expectedChildIds.isNotEmpty()) {
                    val foundChildIds = found.childTaskIds.toSet()
                    foundChildIds == expectedChildIds.toSet() || foundChildIds.containsAll(expectedChildIds)
                } else {
                    true
                }
                if (matchesChildren) {
                    verified = true
                    break
                }
            }
            if (attempt < maxAttempts) {
                Thread.sleep(100)
            }
        }

        return formatMigrationJson(
            success = verified,
            code = if (verified) "ok" else "state_change_unconfirmed",
            taskId = taskId,
            taskKind = taskKind,
            sourceDisplayId = sourceDisplayId,
            targetDisplayId = targetDisplayId,
            verified = verified,
        )
    }

    private fun formatMigrationJson(
        success: Boolean,
        code: String,
        taskId: Int,
        taskKind: String,
        sourceDisplayId: Int,
        targetDisplayId: Int,
        verified: Boolean,
    ): String {
        val obj = JSONObject()
        obj.put("success", success)
        obj.put("code", code)
        obj.put("taskId", taskId)
        obj.put("taskKind", taskKind)
        obj.put("sourceDisplayId", sourceDisplayId)
        obj.put("targetDisplayId", targetDisplayId)
        obj.put("verified", verified)
        return obj.toString()
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

    open fun executeRootCommand(vararg command: String): Boolean = runCatching {
        val process = ProcessBuilder(*command).start()
        process.waitFor() == 0
    }.getOrDefault(false)
}
