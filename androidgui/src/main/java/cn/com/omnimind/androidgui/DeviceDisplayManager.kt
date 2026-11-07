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

/**
 * Frame source backed by [ImageReader].
 *
 * Adapted from IzukiJS (GPL-3.0), commit eb995dff24849fed215a75a136d4b2d076f69c69.
 * Provides a Surface for VirtualDisplay rendering and captures frames without accessibility screenshot overhead.
 */
class VirtualScreenFrameSource(
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private val thread = HandlerThread("omni-vd-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = ReentrantLock()

    @Volatile
    private var reader: ImageReader? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    @Volatile
    private var closed = false

    val surface: Surface
        get() = checkNotNull(reader?.surface) { "Frame source not started" }

    fun start() {
        if (reader != null || closed) return
        val created = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = created
        created.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
                if (bitmap != null) {
                    lock.withLock {
                        latestBitmap?.recycle()
                        latestBitmap = bitmap
                    }
                }
            } finally {
                runCatching { image.close() }
            }
        }, handler)
    }

    fun captureScreenshotJpeg(quality: Int = 85): ByteArray? = lock.withLock {
        val bitmap = latestBitmap ?: return null
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle()
            cropped
        }
    }

    override fun close() {
        closed = true
        lock.withLock {
            latestBitmap?.recycle()
            latestBitmap = null
        }
        reader?.close()
        reader = null
        thread.quitSafely()
    }
}

/**
 * Bridge for privileged display and input operations executed under UID 0 (Root) or UID 2000 (Shell).
 */
interface PrivilegedDisplayBridge {
    suspend fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Result<Int>

    suspend fun releaseVirtualDisplay(displayId: Int)

    suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean

    suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean

    suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean

    suspend fun launchActivity(displayId: Int, packageName: String): Boolean
}

class BinderPrivilegedDisplayBridge(
    private val service: IPrivilegedDisplayService,
) : PrivilegedDisplayBridge {
    override suspend fun createVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): Result<Int> = runCatching {
        val id = service.createVirtualDisplay("OmniVirtualDisplay", width, height, dpi, surface, flags)
        if (id >= 0) id else error("createVirtualDisplay failed in privileged service: $id")
    }

    override suspend fun releaseVirtualDisplay(displayId: Int) {
        runCatching { service.releaseVirtualDisplay(displayId) }
    }

    override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean =
        runCatching { service.injectKeyEvent(displayId, keyCode) }.getOrDefault(false)

    override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        runCatching { service.injectTap(displayId, x, y) }.getOrDefault(false)

    override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        runCatching { service.injectSwipe(displayId, x1, y1, x2, y2, durationMs) }.getOrDefault(false)

    override suspend fun launchActivity(displayId: Int, packageName: String): Boolean =
        runCatching { service.launchActivity(displayId, packageName) }.getOrDefault(false)
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
                    return Result.success(VirtualDisplayInfo(createdId, width, height, dpi))
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
                    return Result.success(VirtualDisplayInfo(createdId, width, height, dpi))
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
        }
    }

    fun captureVirtualScreenshot(displayId: Int): ByteArray? {
        if (displayId == activeVirtualDisplayId) {
            return frameSource?.captureScreenshotJpeg()
        }
        return null
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
