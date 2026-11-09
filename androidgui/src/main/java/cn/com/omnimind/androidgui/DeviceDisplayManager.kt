package cn.com.omnimind.androidgui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.UUID
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val isVirtual: Boolean,
    val isDefault: Boolean,
)

data class VirtualDisplayInfo(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
)

data class PrivilegedRootTaskInfo(
    val taskId: Int,
    val taskKind: String,
    val displayId: Int,
    val userId: Int,
    val packageName: String,
    val childTaskIds: List<Int> = emptyList(),
    val visible: Boolean = false,
    val isFocused: Boolean = false,
)

data class TaskMigrationResult(
    val success: Boolean,
    val code: String,
    val taskId: Int,
    val taskKind: String,
    val sourceDisplayId: Int,
    val targetDisplayId: Int,
    val verified: Boolean,
)

private fun parseRootTasksJson(jsonStr: String): List<PrivilegedRootTaskInfo> = runCatching {
    val array = JSONArray(jsonStr)
    val list = ArrayList<PrivilegedRootTaskInfo>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val taskId = obj.optInt("taskId", -1)
        if (taskId < 0) continue
        val taskKind = obj.optString("taskKind", "")
        val displayId = obj.optInt("displayId", 0)
        val userId = obj.optInt("userId", 0)
        val packageName = obj.optString("packageName", "")
        val childArray = obj.optJSONArray("childTaskIds")
        val childTaskIds = if (childArray != null) {
            val children = ArrayList<Int>(childArray.length())
            for (j in 0 until childArray.length()) {
                children.add(childArray.getInt(j))
            }
            children
        } else {
            emptyList()
        }
        val visible = obj.optBoolean("visible", false)
        val isFocused = obj.optBoolean("isFocused", false)
        list.add(
            PrivilegedRootTaskInfo(
                taskId = taskId,
                taskKind = taskKind,
                displayId = displayId,
                userId = userId,
                packageName = packageName,
                childTaskIds = childTaskIds,
                visible = visible,
                isFocused = isFocused,
            )
        )
    }
    list
}.getOrDefault(emptyList())

private fun parseMigrationResultJson(jsonStr: String): TaskMigrationResult = runCatching {
    val obj = JSONObject(jsonStr)
    TaskMigrationResult(
        success = obj.optBoolean("success", false),
        code = obj.optString("code", ""),
        taskId = obj.optInt("taskId", 0),
        taskKind = obj.optString("taskKind", ""),
        sourceDisplayId = obj.optInt("sourceDisplayId", 0),
        targetDisplayId = obj.optInt("targetDisplayId", 0),
        verified = obj.optBoolean("verified", false),
    )
}.getOrElse {
    TaskMigrationResult(
        success = false,
        code = "parse_error",
        taskId = 0,
        taskKind = "unknown",
        sourceDisplayId = 0,
        targetDisplayId = 0,
        verified = false,
    )
}
/**
 * Manages physical and virtual screen targets, virtual display lifecycle, and app routing.
 *
 * References:
 * - IzukiJS PrivilegedVirtualDisplay & VirtualScreenManager (GPL-3.0, commit eb995dff24849fed215a75a136d4b2d076f69c69).
 * - Removed FLAG_ALWAYS_UNLOCKED to enforce system lockscreen policies.
 * - Enforces factual errors if an app cannot run on a virtual display without secret fallback.
 * - Strict lease ownership: virtual display creation and destruction are bound to the task lease owner,
 *   preventing cross-session destruction or unauthorized state reuse.
 */
object DeviceDisplayManager {
    const val DISPLAY_MAIN = "main"
    const val DISPLAY_VIRTUAL = "virtual"
    const val DEFAULT_VIRTUAL_WIDTH = 1080
    const val DEFAULT_VIRTUAL_HEIGHT = 2400
    const val DEFAULT_VIRTUAL_DPI = 420

    private const val VD_NAME = "OmniVirtualDisplay"
    private const val APP_LAUNCH_TIMEOUT_MS = 3_000L
    private const val APP_POLL_INTERVAL_MS = 150L

    // System flags adapted from IzukiJS PrivilegedVirtualDisplay
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var frameSource: VirtualScreenFrameSource? = null

    @Volatile
    private var activeVirtualDisplayId: Int = Display.DEFAULT_DISPLAY

    @Volatile
    private var activeVirtualDisplayOwner: String? = null

    @Volatile
    var displayBridge: PrivilegedDisplayBridge? = null

    @Volatile
    var privilegedService: IPrivilegedDisplayService? = null

    @Volatile
    private var privilegedSessionId: String? = null
    @Volatile
    private var privilegedServiceOwner: String? = null

    private val sessionDisplayTargets = ConcurrentHashMap<String, Int>()
    private val virtualDisplayPrepMutex = Mutex()

    fun getSessionDisplayTarget(sessionKey: String): Int? = sessionDisplayTargets[sessionKey]

    fun setSessionDisplayTarget(sessionKey: String, displayId: Int) {
        sessionDisplayTargets[sessionKey] = displayId
    }

    fun clearSessionDisplayTarget(sessionKey: String) {
        sessionDisplayTargets.remove(sessionKey)
    }

    suspend fun ensureSessionVirtualDisplay(
        context: Context,
        sessionKey: String,
        acpSessionId: String? = null,
        width: Int = DEFAULT_VIRTUAL_WIDTH,
        height: Int = DEFAULT_VIRTUAL_HEIGHT,
        dpi: Int = DEFAULT_VIRTUAL_DPI,
    ): Result<Int> = virtualDisplayPrepMutex.withLock {
        val currentOwner = activeVirtualDisplayOwner
        val currentId = activeVirtualDisplayId

        // 1. 若当前活跃虚拟屏被其他会话占用，严禁接管或篡改
        if (currentOwner != null && currentOwner != sessionKey && isVirtualDisplayActive()) {
            return Result.failure(
                IllegalStateException("A virtual display (id=$currentId) is already held by another active task ($currentOwner).")
            )
        }

        // 2. 若当前会话已持有有效活跃虚拟屏且底层仍然可用，直接复用，绝不重复销毁与重建
        if (currentOwner == sessionKey && isVirtualDisplayActive() && hasDisplay(context, currentId)) {
            setSessionDisplayTarget(sessionKey, currentId)
            return Result.success(currentId)
        }

        // 3. 准备 privileged 服务（Shizuku 或 KernelSU Root）
        ensurePrivilegedService(
            context = context,
            ownerId = sessionKey,
            acpSessionId = acpSessionId,
        )

        // 4. 创建虚拟屏
        val result = createVirtualDisplay(
            context = context,
            width = width,
            height = height,
            dpi = dpi,
            ownerId = sessionKey,
        )

        return result.fold(
            onSuccess = { info ->
                setSessionDisplayTarget(sessionKey, info.displayId)
                Result.success(info.displayId)
            },
            onFailure = { error ->
                Result.failure(error)
            }
        )
    }

    suspend fun resolveOrPrepareDisplayId(
        context: Context,
        target: String?,
        sessionKey: String,
        acpSessionId: String? = null,
    ): Int {
        val normalized = target?.trim()?.lowercase().orEmpty()
        return when {
            // 显式指定主屏
            normalized == DISPLAY_MAIN || normalized == "0" -> Display.DEFAULT_DISPLAY

            // 显式指定具体编号（例如物理屏或已建虚拟屏 ID）
            normalized.isNotEmpty() && normalized != DISPLAY_VIRTUAL -> {
                val parsed = normalized.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid display target: '$target'. Expected 'main', 'virtual', or display integer ID.")
                if (parsed == Display.DEFAULT_DISPLAY) {
                    Display.DEFAULT_DISPLAY
                } else {
                    if (!hasDisplay(context, parsed)) {
                        throw IllegalArgumentException("Display id $parsed is not connected or available.")
                    }
                    val owner = DisplayInputCoordinator.getVirtualDisplayOwner(parsed)
                    if (owner != null && owner != sessionKey) {
                        throw IllegalStateException("虚拟显示屏 $parsed 属于其他会话 ($owner)，当前会话 ($sessionKey) 无权操作。")
                    }
                    parsed
                }
            }

            // 显式指定 virtual：必须安全准备或复用虚拟屏
            normalized == DISPLAY_VIRTUAL -> {
                ensureSessionVirtualDisplay(context, sessionKey, acpSessionId).getOrThrow()
            }

            // 省略（null 或空）：按会话默认路由
            else -> {
                val currentTarget = getSessionDisplayTarget(sessionKey)
                if (currentTarget != null) {
                    if (currentTarget == Display.DEFAULT_DISPLAY) {
                        Display.DEFAULT_DISPLAY
                    } else if (activeVirtualDisplayOwner == sessionKey && isVirtualDisplayActive() && hasDisplay(context, currentTarget)) {
                        currentTarget
                    } else {
                        // 记录的虚拟屏已失效，自动准备新虚拟屏
                        ensureSessionVirtualDisplay(context, sessionKey, acpSessionId).getOrThrow()
                    }
                } else {
                    // 全新会话默认：必须走后台虚拟屏，绝不占用前台主屏
                    ensureSessionVirtualDisplay(context, sessionKey, acpSessionId).getOrThrow()
                }
            }
        }
    }

    fun resetForTesting() {
        sessionDisplayTargets.clear()
        activeVirtualDisplayId = Display.DEFAULT_DISPLAY
        activeVirtualDisplayOwner = null
        virtualDisplay = null
        frameSource = null
        lastError = null
        privilegedService = null
        displayBridge = null
        privilegedSessionId = null
        privilegedServiceOwner = null
    }
    const val ACTION_ROOT_DISPLAY_BINDER = "cn.com.omnimind.androidgui.ACTION_ROOT_DISPLAY_BINDER"
    const val EXTRA_NONCE = "nonce"
    const val EXTRA_BINDER_BUNDLE = "binder_bundle"
    const val KEY_BINDER = "binder"
    const val EXTRA_SERVICE_PID = "service_pid"
    @Volatile
    var lastError: String? = null
        private set

    fun injectKeyEvent(displayId: Int, keyCode: Int, ownerId: String? = null): Boolean {
        val leaseExecutor = DisplayInputCoordinator.getLeaseExecutor(displayId, ownerId)
        if (leaseExecutor != null) {
            return runCatching {
                leaseExecutor(listOf("/system/bin/input", "-d", displayId.toString(), "keyevent", keyCode.toString()))
            }.getOrDefault(false)
        }
        val bridge = displayBridge
        if (bridge != null) {
            return runCatching { kotlinx.coroutines.runBlocking { bridge.injectKeyEvent(displayId, keyCode) } }.getOrDefault(false)
        }
        val service = privilegedService
        if (service != null) {
            return runCatching { service.injectKeyEvent(displayId, keyCode) }.getOrDefault(false)
        }
        return false
    }

    fun injectTap(displayId: Int, x: Int, y: Int, ownerId: String? = null): Boolean {
        val leaseExecutor = DisplayInputCoordinator.getLeaseExecutor(displayId, ownerId)
        if (leaseExecutor != null) {
            return runCatching {
                leaseExecutor(listOf("/system/bin/input", "-d", displayId.toString(), "tap", x.toString(), y.toString()))
            }.getOrDefault(false)
        }
        val bridge = displayBridge
        if (bridge != null) {
            return runCatching { kotlinx.coroutines.runBlocking { bridge.injectTap(displayId, x, y) } }.getOrDefault(false)
        }
        val service = privilegedService
        if (service != null) {
            return runCatching { service.injectTap(displayId, x, y) }.getOrDefault(false)
        }
        return false
    }

    fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, ownerId: String? = null): Boolean {
        val leaseExecutor = DisplayInputCoordinator.getLeaseExecutor(displayId, ownerId)
        if (leaseExecutor != null) {
            return runCatching {
                leaseExecutor(listOf("/system/bin/input", "-d", displayId.toString(), "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString()))
            }.getOrDefault(false)
        }
        val bridge = displayBridge
        if (bridge != null) {
            return runCatching { kotlinx.coroutines.runBlocking { bridge.injectSwipe(displayId, x1, y1, x2, y2, durationMs) } }.getOrDefault(false)
        }
        val service = privilegedService
        if (service != null) {
            return runCatching { service.injectSwipe(displayId, x1, y1, x2, y2, durationMs) }.getOrDefault(false)
        }
        return false
    }

    fun connectShizukuUserService(context: Context): Boolean = runCatching {
        val shizukuClass = Class.forName("rikka.shizuku.Shizuku")
        val pingMethod = shizukuClass.getMethod("pingBinder")
        if (pingMethod.invoke(null) != true) return@runCatching false

        val argsClass = Class.forName("rikka.shizuku.Shizuku\$UserServiceArgs")
        val constructor = argsClass.getConstructor(ComponentName::class.java)
        val args = constructor.newInstance(ComponentName(context.packageName, PrivilegedDisplayUserService::class.java.name))
        argsClass.getMethod("daemon", Boolean::class.javaPrimitiveType).invoke(args, false)
        argsClass.getMethod("processNameSuffix", String::class.java).invoke(args, "privileged_display")
        argsClass.getMethod("tag", String::class.java).invoke(args, "OmniPrivilegedDisplay")
        argsClass.getMethod("version", Int::class.javaPrimitiveType).invoke(args, 1)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                privilegedService = IPrivilegedDisplayService.asInterface(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                privilegedService = null
            }
        }
        shizukuClass.getMethod("bindUserService", argsClass, ServiceConnection::class.java).invoke(null, args, conn)
        true
    }.getOrDefault(false)

    suspend fun ensurePrivilegedService(
        context: Context,
        ownerId: String,
        acpSessionId: String? = null,
    ): IPrivilegedDisplayService? {
        privilegedService?.let { service ->
            val binder = service.asBinder()
            if (binder.isBinderAlive && binder.pingBinder()) {
                return service
            }
        }

        // 1. 若 Shizuku 处于活动状态，优先尝试连接 Shizuku
        if (connectShizukuUserService(context)) {
            privilegedService?.let { return it }
        }

        // 2. 真实 KernelSU Root bootstrap：复用 PrivilegedCapabilityManager 租约与受控 session/runner
        val privilegedManager = cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager.get(context)
        val access = privilegedManager.reserve(ownerId = ownerId, acpSessionId = acpSessionId)
        if (access.isValid()) {
            privilegedSessionId?.let { oldSessionId ->
                runCatching { access.stopPrivilegedSession(oldSessionId) }
            }
            privilegedSessionId = null
            privilegedService = null

            val startRes = access.startPrivilegedSession(
                sessionName = "omni_root_display",
                confirmed = true,
            )
            if (!startRes.success || startRes.sessionId == null) {
                return null
            }
            val sessionId = startRes.sessionId ?: return null
            privilegedSessionId = sessionId

            val nonce = UUID.randomUUID().toString()
            val binderDeferred = CompletableDeferred<IBinder>()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(recvContext: Context, intent: Intent) {
                    if (intent.action != ACTION_ROOT_DISPLAY_BINDER) return
                    val recvNonce = intent.getStringExtra(EXTRA_NONCE)
                    if (recvNonce != nonce) {
                        return
                    }
                    val bundle = intent.getBundleExtra(EXTRA_BINDER_BUNDLE)
                    val binder = bundle?.getBinder(KEY_BINDER)
                    if (binder != null) {
                        binderDeferred.complete(binder)
                    }
                }
            }

            val filter = IntentFilter(ACTION_ROOT_DISPLAY_BINDER)
            val registered = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                true
            }.getOrDefault(false)

            if (!registered) {
                access.stopPrivilegedSession(sessionId)
                privilegedSessionId = null
                return null
            }

            try {
                val targetPackage = context.packageName
                val targetUid = Process.myUid()
                val apkPath = context.packageCodePath
                val launchCmd = "(export CLASSPATH=$apkPath && exec /system/bin/app_process /system/bin cn.com.omnimind.androidgui.PrivilegedDisplayHelper $targetPackage $nonce $targetUid) &"

                val execRes = access.execPrivilegedSession(
                    sessionId = sessionId,
                    command = launchCmd,
                    timeoutSeconds = 5,
                    confirmed = true,
                    allowSystemModifications = true,
                )

                if (!execRes.success) {
                    access.stopPrivilegedSession(sessionId)
                    privilegedSessionId = null
                    return null
                }

                val binder = withTimeoutOrNull(5000L) {
                    binderDeferred.await()
                }

                if (binder != null && binder.isBinderAlive && binder.pingBinder()) {
                    val proxy = IPrivilegedDisplayService.asInterface(binder)
                    if (proxy != null) {
                        val remoteUid = runCatching { proxy.getServiceUid() }.getOrDefault(-1)
                        if (remoteUid == 0 || remoteUid == 2000) {
                            val deathRecipient = IBinder.DeathRecipient {
                                synchronized(this@DeviceDisplayManager) {
                                    if (privilegedService?.asBinder() == binder) {
                                        privilegedService = null
                                        privilegedServiceOwner = null
                                        privilegedSessionId = null
                                        activeVirtualDisplayId = Display.DEFAULT_DISPLAY
                                    }
                                }
                            }
                            runCatching { binder.linkToDeath(deathRecipient, 0) }

                            privilegedServiceOwner = ownerId
                            privilegedService = proxy
                            return proxy
                        } else {
                            runCatching { proxy.destroy() }
                        }
                    }
                }

                access.stopPrivilegedSession(sessionId)
                privilegedSessionId = null
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        return privilegedService
    }

    suspend fun closePrivilegedService(
        context: Context? = null,
        ownerId: String? = null,
        acpSessionId: String? = null,
    ) {
        val helperOwner = privilegedServiceOwner
        if (ownerId != null && helperOwner != null && ownerId != helperOwner) {
            return
        }
        destroyVirtualDisplay(activeVirtualDisplayId, ownerId)
        privilegedServiceOwner = null
        privilegedService?.let {
            runCatching { it.destroy() }
        }
        privilegedService = null
        val sid = privilegedSessionId
        privilegedSessionId = null
        if (sid != null && context != null && ownerId != null) {
            runCatching {
                val privilegedManager = cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager.get(context)
                val access = privilegedManager.reserve(ownerId = ownerId, acpSessionId = acpSessionId)
                access.stopPrivilegedSession(sid)
            }
        }
        ownerId?.let { clearSessionDisplayTarget(it) }
        acpSessionId?.let { clearSessionDisplayTarget(it) }
    }

    /**
     * Resolves user-facing display selector ("main", "virtual", displayId string/int) to numeric display ID.
     */
    fun resolveDisplayId(context: Context, target: String?): Int {
        val normalized = target?.trim()?.lowercase().orEmpty()
        return when {
            normalized.isEmpty() || normalized == DISPLAY_MAIN || normalized == "0" -> Display.DEFAULT_DISPLAY
            normalized == DISPLAY_VIRTUAL -> {
                val current = activeVirtualDisplayId
                if (current != Display.DEFAULT_DISPLAY && hasDisplay(context, current)) {
                    current
                } else {
                    throw IllegalArgumentException("No active virtual display found. Please create or start a virtual display first.")
                }
            }
            else -> {
                val parsed = normalized.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid display target: '$target'. Expected 'main', 'virtual', or display integer ID.")
                if (!hasDisplay(context, parsed)) {
                    throw IllegalArgumentException("Display id $parsed is not connected or available.")
                }
                parsed
            }
        }
    }

    fun hasDisplay(context: Context, displayId: Int): Boolean {
        if (displayId != Display.DEFAULT_DISPLAY && displayId == activeVirtualDisplayId && isVirtualDisplayActive()) {
            return true
        }
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return false
        return dm.getDisplay(displayId) != null
    }

    fun listDisplays(context: Context): List<DisplayInfo> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return emptyList()
        return dm.displays.map { display ->
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            val isVirtual = display.displayId != Display.DEFAULT_DISPLAY &&
                (display.displayId == activeVirtualDisplayId || display.name.contains("virtual", ignoreCase = true))
            DisplayInfo(
                displayId = display.displayId,
                name = display.name,
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                dpi = metrics.densityDpi,
                isVirtual = isVirtual,
                isDefault = display.displayId == Display.DEFAULT_DISPLAY,
            )
        }
    }

    fun isVirtualDisplayActive(): Boolean =
        activeVirtualDisplayId != Display.DEFAULT_DISPLAY && (virtualDisplay != null || privilegedService != null || displayBridge != null)

    fun activeVirtualDisplayId(): Int = activeVirtualDisplayId

    @SuppressLint("WrongConstant")
    @Synchronized
    fun createVirtualDisplay(
        context: Context,
        width: Int = DEFAULT_VIRTUAL_WIDTH,
        height: Int = DEFAULT_VIRTUAL_HEIGHT,
        dpi: Int = DEFAULT_VIRTUAL_DPI,
        showSystemDecorations: Boolean = false,
        ownerId: String? = null,
    ): Result<VirtualDisplayInfo> {
        val currentOwner = activeVirtualDisplayOwner
        if (currentOwner != null && ownerId != null && currentOwner != ownerId && isVirtualDisplayActive()) {
            return fail("A virtual display (id=$activeVirtualDisplayId) is already held by another active task ($currentOwner).")
        }

        destroyVirtualDisplay(activeVirtualDisplayId, currentOwner)
        lastError = null

        val source = VirtualScreenFrameSource(width, height)
        try {
            source.start()
            frameSource = source

            val preferredFlags = buildPreferredDisplayFlags(showSystemDecorations)

            // 1. 优先使用 UID 0 (Root) / UID 2000 (Shell) 特权桥接或服务创建公共虚拟屏
            val bridge = displayBridge
            if (bridge != null) {
                val createdResult = kotlinx.coroutines.runBlocking {
                    bridge.createVirtualDisplay(width, height, dpi, source.surface, preferredFlags)
                }
                if (createdResult.isSuccess) {
                    val createdId = createdResult.getOrThrow()
                    activeVirtualDisplayId = createdId
                    activeVirtualDisplayOwner = ownerId
                    ownerId?.let { DisplayInputCoordinator.registerVirtualDisplayOwnership(createdId, it) }
                    ownerId?.let { setSessionDisplayTarget(it, createdId) }
                    return Result.success(VirtualDisplayInfo(createdId, width, height, dpi))
                } else {
                    return failWithCleanup(
                        source,
                        createdResult.exceptionOrNull()?.message ?: "Privileged bridge failed to create virtual display",
                    )
                }
            }

            val service = privilegedService
            if (service != null) {
                val createdId = runCatching {
                    service.createVirtualDisplay(VD_NAME, width, height, dpi, source.surface, preferredFlags)
                }.getOrDefault(-1)
                if (createdId >= 0) {
                    activeVirtualDisplayId = createdId
                    activeVirtualDisplayOwner = ownerId
                    ownerId?.let { DisplayInputCoordinator.registerVirtualDisplayOwnership(createdId, it) }
                    ownerId?.let { setSessionDisplayTarget(it, createdId) }
                    return Result.success(VirtualDisplayInfo(createdId, width, height, dpi))
                } else {
                    return failWithCleanup(source, "Privileged service failed to create virtual display")
                }
            }

            // 2. 非特权回退路径（仅限无特权 flag 时的演示模式）
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return failWithCleanup(source, "DisplayManager unavailable")

            val standardPresentationFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val standardAttempt = runCatching {
                dm.createVirtualDisplay(VD_NAME, width, height, dpi, source.surface, standardPresentationFlags)
            }
            val vd = standardAttempt.getOrNull()
            if (vd != null) {
                virtualDisplay = vd
                val displayId = vd.display.displayId
                activeVirtualDisplayId = displayId
                activeVirtualDisplayOwner = ownerId
                applyDisplayTweaks(displayId)
                ownerId?.let { DisplayInputCoordinator.registerVirtualDisplayOwnership(displayId, it) }
                ownerId?.let { setSessionDisplayTarget(it, displayId) }
                return Result.success(VirtualDisplayInfo(displayId, width, height, dpi))
            }

            return failWithCleanup(
                source,
                "Creating a privileged virtual display with touch & display-group flags requires a privileged Root or Shizuku service. Please start Shizuku or authorize Root User Service.",
            )
        } catch (t: Throwable) {
            return failWithCleanup(source, "Failed to create virtual display: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun <T> failWithCleanup(source: VirtualScreenFrameSource, message: String): Result<T> {
        runCatching { source.close() }
        if (frameSource === source) {
            frameSource = null
        }
        return fail(message)
    }

    @Synchronized
    fun destroyVirtualDisplay(
        displayId: Int = Display.DEFAULT_DISPLAY,
        ownerId: String? = null,
    ) {
        val targetId = if (displayId == Display.DEFAULT_DISPLAY) activeVirtualDisplayId else displayId
        val currentOwner = activeVirtualDisplayOwner
        if (ownerId != null && currentOwner != null && ownerId != currentOwner) {
            return
        }

        displayBridge?.let {
            runCatching { kotlinx.coroutines.runBlocking { it.releaseVirtualDisplay(targetId) } }
        }
        privilegedService?.let {
            runCatching { it.releaseVirtualDisplay(targetId) }
        }
        if (targetId == activeVirtualDisplayId || targetId == Display.DEFAULT_DISPLAY) {
            virtualDisplay?.let { runCatching { it.release() } }
            virtualDisplay = null
            frameSource?.let { runCatching { it.close() } }
            frameSource = null
            activeVirtualDisplayId = Display.DEFAULT_DISPLAY
            activeVirtualDisplayOwner = null
            DisplayInputCoordinator.releaseVirtualDisplayOwnership(targetId, ownerId)
            if (ownerId != null) {
                sessionDisplayTargets.remove(ownerId)
            }
            sessionDisplayTargets.entries.removeIf { it.value == targetId }
        }
    }

    fun captureVirtualScreenshot(displayId: Int): ByteArray? {
        if (displayId == activeVirtualDisplayId) {
            return frameSource?.captureScreenshotJpeg()
        }
        return null
    }

    private fun validateVirtualFrameSource(displayId: Int, ownerId: String): VirtualScreenFrameSource? {
        if (displayId == Display.DEFAULT_DISPLAY || displayId != activeVirtualDisplayId) {
            return null
        }
        val currentOwner = activeVirtualDisplayOwner
        val registeredOwner = DisplayInputCoordinator.getVirtualDisplayOwner(displayId)
        if (ownerId != currentOwner && ownerId != registeredOwner) {
            return null
        }
        return frameSource
    }

    /**
     * Returns the current frame sequence for [displayId] if active, owned by [ownerId],
     * and backed by an active [VirtualScreenFrameSource]; otherwise null.
     */
    fun virtualFrameSequence(displayId: Int, ownerId: String): Long? {
        val source = validateVirtualFrameSource(displayId, ownerId) ?: return null
        return source.currentFrameSequence()
    }

    /**
     * Draws the latest virtual frame for [displayId] into [canvas] within [destination].
     *
     * Validates that [displayId] matches [activeVirtualDisplayId] and [ownerId] matches
     * the active virtual display owner or registered session owner before delegating to
     * [VirtualScreenFrameSource.drawLatestFrame].
     *
     * Only renders when the latest frame sequence is strictly greater than [afterSequence].
     */
    fun drawVirtualFrame(
        displayId: Int,
        ownerId: String,
        canvas: Canvas,
        destination: Rect,
        afterSequence: Long = -1L,
        outDrawRect: Rect? = null,
    ): Boolean {
        val source = validateVirtualFrameSource(displayId, ownerId) ?: return false
        return source.drawLatestFrame(canvas, destination, afterSequence, outDrawRect)
    }

    /**
     * Launches an app on [displayId].
     *
     * If launching on a secondary/virtual display, verifies that the activity actually appears on [displayId].
     * If the app fails to appear on [displayId], returns a factual error without secretly falling back to the main display.
     */
    suspend fun launchAppOnDisplay(
        context: Context,
        packageName: String,
        displayId: Int,
        ownerId: String? = null,
    ): AndroidGuiActionResult {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: return AndroidGuiActionResult(false, "open_app_not_found:$packageName")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (displayId == Display.DEFAULT_DISPLAY) {
            context.startActivity(intent)
            return AndroidGuiActionResult(true, "open_app_dispatched")
        }

        val options = ActivityOptions.makeBasic().apply {
            launchDisplayId = displayId
        }

        val activeTasks = (listDisplays(context).map { it.displayId } + displayId).distinct()
            .flatMap { getRootTasks(it) }
            .filter { it.packageName == packageName }
        if (activeTasks.size > 1) {
            return AndroidGuiActionResult(
                success = false,
                message = "ambiguous_task",
                diagnostics = mapOf(
                    "success" to "false",
                    "code" to "ambiguous_task",
                    "packageName" to packageName,
                    "targetDisplayId" to displayId.toString(),
                    "candidateTaskIds" to activeTasks.map { it.taskId }.joinToString(","),
                ),
            )
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            val foregroundOnMain = activeTasks.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY && (it.visible || it.isFocused) }
            if (foregroundOnMain != null) {
                return AndroidGuiActionResult(
                    success = false,
                    message = "app_in_foreground_on_main_display:$packageName. 应用当前正在主屏前台使用，无法在后台虚拟屏打开，避免干扰用户当前操作。",
                    diagnostics = mapOf(
                        "success" to "false",
                        "code" to "app_in_foreground_on_main_display",
                        "packageName" to packageName,
                        "taskId" to foregroundOnMain.taskId.toString(),
                        "targetDisplayId" to displayId.toString(),
                    ),
                )
            }
        }

        val bridge = displayBridge
        val privilegedLaunched = if (bridge != null) {
            bridge.launchActivity(displayId, packageName)
        } else {
            val service = privilegedService
            if (service != null) {
                runCatching { service.launchActivity(displayId, packageName) }.getOrDefault(false)
            } else {
                null
            }
        }

        if (privilegedLaunched == false) {
            return AndroidGuiActionResult(
                success = false,
                message = "open_app_failed_on_display:$displayId via privileged bridge/service. Application may not support multi-display. Please switch to main display.",
            )
        }

        if (privilegedLaunched == null) {
            try {
                context.startActivity(intent, options.toBundle())
            } catch (e: Exception) {
                return AndroidGuiActionResult(
                    success = false,
                    message = "open_app_failed_on_display:$displayId error=${e.message}. Application may not support multi-display. Please switch to main display.",
                )
            }
        }

        val appeared = awaitPackageOnDisplay(context, packageName, displayId, APP_LAUNCH_TIMEOUT_MS)
        if (!appeared) {
            return AndroidGuiActionResult(
                success = false,
                message = "app_not_supported_on_virtual_display:$packageName. Application was not detected on display $displayId after launch. Please switch to main display.",
            )
        }

        return AndroidGuiActionResult(true, "open_app_dispatched_on_display:$displayId")
    }

    suspend fun getRootTasks(displayId: Int): List<PrivilegedRootTaskInfo> {
        val bridge = displayBridge
        if (bridge != null) {
            val jsonStr = bridge.getRootTasks(displayId)
            return parseRootTasksJson(jsonStr)
        }
        val service = privilegedService
        if (service != null) {
            val jsonStr = runCatching { service.getRootTasks(displayId) }.getOrDefault("[]")
            return parseRootTasksJson(jsonStr)
        }
        return emptyList()
    }

    suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): TaskMigrationResult {
        val bridge = displayBridge
        if (bridge != null) {
            val jsonStr = bridge.moveRootTask(taskId, sourceDisplayId, targetDisplayId)
            return parseMigrationResultJson(jsonStr)
        }
        val service = privilegedService
        if (service != null) {
            val jsonStr = runCatching { service.moveRootTask(taskId, sourceDisplayId, targetDisplayId) }.getOrNull()
            if (jsonStr != null) {
                return parseMigrationResultJson(jsonStr)
            }
        }
        return TaskMigrationResult(
            success = false,
            code = "privileged_service_unavailable",
            taskId = taskId,
            taskKind = "unknown",
            sourceDisplayId = sourceDisplayId,
            targetDisplayId = targetDisplayId,
            verified = false,
        )
    }

    /**
     * Moves a task running on the caller's active virtual display to [Display.DEFAULT_DISPLAY]
     * without killing or recreating the task.
     *
     * Guarantees:
     * - Source display must be the active virtual display belonging to [sessionOwnerId].
     * - Target display is fixed to [Display.DEFAULT_DISPLAY].
     * - Coordinates locks using [DisplayInputCoordinator.withDisplayLeases] (sorted, non-deadlocking).
     * - Preserves lockscreen security via [KeyguardStateMonitor.ensureInteractiveAndUnlocked].
     * - On failure, preserves the virtual display, frame source, and running task (never destroys).
     * - On success, does NOT destroy the helper/display immediately (retained for session lifecycle).
     */
    suspend fun presentToUser(
        context: Context,
        packageName: String,
        taskId: Int? = null,
        sessionOwnerId: String,
        leaseOwnerId: String,
        canPresent: (() -> Boolean)? = null,
    ): AndroidGuiActionResult {
        val sourceDisplayId = activeVirtualDisplayId
        if (sourceDisplayId == Display.DEFAULT_DISPLAY) {
            return AndroidGuiActionResult(
                success = false,
                message = "no_active_virtual_display",
                diagnostics = mapOf(
                    "success" to "false",
                    "code" to "source_display_unavailable",
                    "taskId" to (taskId?.toString() ?: "0"),
                    "taskKind" to "unknown",
                    "sourceDisplayId" to sourceDisplayId.toString(),
                    "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                    "verified" to "false",
                ),
            )
        }

        val currentOwner = activeVirtualDisplayOwner
        val registeredOwner = DisplayInputCoordinator.getVirtualDisplayOwner(sourceDisplayId)
        if (sessionOwnerId != currentOwner && sessionOwnerId != registeredOwner) {
            return AndroidGuiActionResult(
                success = false,
                message = "virtual_display_owner_mismatch",
                diagnostics = mapOf(
                    "success" to "false",
                    "code" to "cross_session_forbidden",
                    "taskId" to (taskId?.toString() ?: "0"),
                    "taskKind" to "unknown",
                    "sourceDisplayId" to sourceDisplayId.toString(),
                    "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                    "verified" to "false",
                ),
            )
        }

        val unlocked = KeyguardStateMonitor.ensureInteractiveAndUnlocked(context, Display.DEFAULT_DISPLAY, leaseOwnerId)
        if (!unlocked) {
            return AndroidGuiActionResult(
                success = false,
                message = "device_locked_awaiting_user_unlock",
                diagnostics = mapOf(
                    "success" to "false",
                    "code" to "device_locked",
                    "taskId" to (taskId?.toString() ?: "0"),
                    "taskKind" to "unknown",
                    "sourceDisplayId" to sourceDisplayId.toString(),
                    "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                    "verified" to "false",
                ),
            )
        }

        return try {
            DisplayInputCoordinator.withDisplayLeases(
                listOf(sourceDisplayId, Display.DEFAULT_DISPLAY),
                leaseOwnerId,
            ) {
                if (canPresent != null && !canPresent()) {
                    return@withDisplayLeases AndroidGuiActionResult(
                        success = false,
                        message = "display_busy",
                        diagnostics = mapOf(
                            "success" to "false",
                            "code" to "display_busy",
                            "taskId" to (taskId?.toString() ?: "0"),
                            "taskKind" to "unknown",
                            "sourceDisplayId" to sourceDisplayId.toString(),
                            "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                            "verified" to "false",
                        ),
                    )
                }

                val tasks = getRootTasks(sourceDisplayId)
                val targetTask = if (taskId != null) {
                    tasks.firstOrNull { it.taskId == taskId }
                } else {
                    val matching = tasks.filter { it.packageName == packageName }
                    if (matching.size > 1) {
                        return@withDisplayLeases AndroidGuiActionResult(
                            success = false,
                            message = "ambiguous_task",
                            diagnostics = mapOf(
                                "success" to "false",
                                "code" to "ambiguous_task",
                                "taskId" to "0",
                                "taskKind" to "unknown",
                                "sourceDisplayId" to sourceDisplayId.toString(),
                                "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                                "verified" to "false",
                            ),
                        )
                    }
                    matching.firstOrNull()
                }

                if (targetTask == null) {
                    return@withDisplayLeases AndroidGuiActionResult(
                        success = false,
                        message = "task_not_found",
                        diagnostics = mapOf(
                            "success" to "false",
                            "code" to "task_not_found",
                            "taskId" to (taskId?.toString() ?: "0"),
                            "taskKind" to "unknown",
                            "sourceDisplayId" to sourceDisplayId.toString(),
                            "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                            "verified" to "false",
                        ),
                    )
                }

                val migration = moveRootTask(targetTask.taskId, sourceDisplayId, Display.DEFAULT_DISPLAY)
                val diagnostics = mapOf(
                    "success" to migration.success.toString(),
                    "code" to migration.code,
                    "taskId" to migration.taskId.toString(),
                    "taskKind" to migration.taskKind,
                    "sourceDisplayId" to migration.sourceDisplayId.toString(),
                    "targetDisplayId" to migration.targetDisplayId.toString(),
                    "verified" to migration.verified.toString(),
                )

                if (migration.verified) {
                    setSessionDisplayTarget(sessionOwnerId, Display.DEFAULT_DISPLAY)
                    AndroidGuiActionResult(
                        success = true,
                        message = "task_presented_to_user",
                        diagnostics = diagnostics,
                    )
                } else {
                    AndroidGuiActionResult(
                        success = false,
                        message = "present_to_user_failed:${migration.code}",
                        diagnostics = diagnostics,
                    )
                }
            }
        } catch (e: IllegalStateException) {
            val code = if (e.message == "display_busy") "display_busy" else "error"
            AndroidGuiActionResult(
                success = false,
                message = e.message ?: "present_to_user_failed",
                diagnostics = mapOf(
                    "success" to "false",
                    "code" to code,
                    "taskId" to (taskId?.toString() ?: "0"),
                    "taskKind" to "unknown",
                    "sourceDisplayId" to sourceDisplayId.toString(),
                    "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                    "verified" to "false",
                ),
            )
        } catch (e: Exception) {
            AndroidGuiActionResult(
                success = false,
                message = e.message ?: "present_to_user_error",
                diagnostics = mapOf(
                    "success" to "false",
                    "code" to "error",
                    "taskId" to (taskId?.toString() ?: "0"),
                    "taskKind" to "unknown",
                    "sourceDisplayId" to sourceDisplayId.toString(),
                    "targetDisplayId" to Display.DEFAULT_DISPLAY.toString(),
                    "verified" to "false",
                ),
            )
        }
    }

    private suspend fun awaitPackageOnDisplay(
        context: Context,
        packageName: String,
        displayId: Int,
        timeoutMs: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isPackageOnDisplay(context, packageName, displayId)) return true
            delay(APP_POLL_INTERVAL_MS)
        }
        return isPackageOnDisplay(context, packageName, displayId)
    }

    private fun isPackageOnDisplay(
        context: Context,
        packageName: String,
        displayId: Int,
    ): Boolean {
        // 1. 优先通过无障碍服务获取多屏窗口（公共虚拟屏在系统无障碍树中立即可见）
        val accessibilityWindows = runCatching {
            cn.com.omnimind.accessibility.service.AssistsService.readyInstance()?.windowsOnAllDisplays?.get(displayId)
        }.getOrNull()
        if (accessibilityWindows != null && accessibilityWindows.isNotEmpty()) {
            val found = accessibilityWindows.any { window ->
                val rootPkg = window.root?.packageName?.toString()
                rootPkg == packageName
            }
            if (found) return true
        }

        // 2. 检查 ActivityManager 运行任务
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            @Suppress("DEPRECATION")
            val tasks = runCatching { am.getRunningTasks(50) }.getOrNull()
            if (tasks != null && tasks.isNotEmpty()) {
                val found = tasks.any { task ->
                    val pkg = task.topActivity?.packageName ?: task.baseActivity?.packageName
                    if (pkg != packageName) return@any false
                    val taskDisplay = taskDisplayId(task)
                    taskDisplay == null || taskDisplay == displayId
                }
                if (found) return true
            }
        }

        return false
    }

    private fun taskDisplayId(task: ActivityManager.RunningTaskInfo): Int? {
        var cls: Class<*>? = task.javaClass
        while (cls != null) {
            val field = runCatching {
                cls?.getDeclaredField("displayId")?.apply { isAccessible = true }
            }.getOrNull()
            if (field != null) {
                return runCatching { field.getInt(task) }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }

    /**
     * 构建特权虚拟屏标志位集合。
     *
     * 系统语义说明：
     * 1. [DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC]：
     *    核心修复项。若缺少此标志，Android DisplayManagerService 会将虚拟屏打上 [Display.FLAG_PRIVATE]，
     *    由于特权 Helper 运行在 UID 0 (Root) / 2000 (Shell) 下，私有屏幕根据系统安全策略仅创建者 UID 可见，
     *    导致运行在普通应用 UID 下的主进程 App DisplayManager 无法通过 getDisplay / displays 枚举与解析该屏幕。
     *    启用 PUBLIC 后，屏幕在系统显示拓扑中对全系统进程及无障碍服务公开可见，彻底打通生命周期。
     * 2. [DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION] 与 [DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY]：
     *    独立展示内容，禁止镜像主屏。
     * 3. [FLAG_SUPPORTS_TOUCH] 与 [FLAG_DESTROY_CONTENT_ON_REMOVAL]：
     *    支持独立触摸事件分发，虚拟屏销毁时自动清理其上运行的 Activity 栈。
     * 4. [FLAG_TRUSTED] 与 [FLAG_OWN_DISPLAY_GROUP] (Android 13+)：
     *    分配独立 DisplayGroup，窗口与焦点与主屏完全隔离，主屏界面不被抢焦。
     * 5. [FLAG_OWN_FOCUS] 与 [FLAG_STEAL_TOP_FOCUS_DISABLED] (Android 14+)：
     *    虚拟屏享有独立输入焦点且禁止剥夺主屏顶层焦点，保证主屏不被切换。
     * 6. 严禁添加 [FLAG_ALWAYS_UNLOCKED]，保持系统安全锁屏策略有效。
     */
    private fun buildPreferredDisplayFlags(showSystemDecorations: Boolean): Int {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (showSystemDecorations) flags = flags or FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or
                FLAG_TRUSTED or
                FLAG_OWN_DISPLAY_GROUP or
                FLAG_TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or
                    FLAG_OWN_FOCUS or
                    FLAG_STEAL_TOP_FOCUS_DISABLED
            }
        }
        return flags
    }

    private fun applyDisplayTweaks(displayId: Int) {
        runCatching {
            val wmClass = Class.forName("android.view.IWindowManager\$Stub")
            val smClass = Class.forName("android.os.ServiceManager")
            val getService: Method = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, Context.WINDOW_SERVICE)
            val asInterface: Method = wmClass.getMethod("asInterface", IBinder::class.java)
            val wm = asInterface.invoke(null, binder) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val setDisplayImePolicy = wm.javaClass.getMethod(
                    "setDisplayImePolicy",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                setDisplayImePolicy.invoke(wm, displayId, 2 /* DISPLAY_IME_POLICY_HIDE */)
            }
            val freezeRotation = wm.javaClass.getMethod(
                "freezeRotation",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            freezeRotation.invoke(wm, displayId, Surface.ROTATION_0)
        }
    }

    private fun <T> fail(message: String): Result<T> {
        lastError = message
        return Result.failure(IllegalStateException(message))
    }
}
