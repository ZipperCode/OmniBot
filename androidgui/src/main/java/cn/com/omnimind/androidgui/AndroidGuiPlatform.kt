package cn.com.omnimind.androidgui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.OobActionSchema
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AndroidGuiPlatformState(
    val packageName: String,
    val activityName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val xml: String,
    val screenshotJpeg: ByteArray? = null,
)

data class AndroidGuiInputTarget(
    val description: String,
    val x: Float,
    val y: Float,
    val nodeResourceId: String,
    val password: Boolean,
)

internal interface AndroidGuiPlatform {
    fun isAccessibilityEnabled(): Boolean

    fun isReady(): Boolean

    fun displaySize(): Pair<Int, Int> = displaySize(Display.DEFAULT_DISPLAY)

    fun displaySize(displayId: Int): Pair<Int, Int> = displaySize()

    fun screenshotExcludesOverlays(): Boolean

    suspend fun observe(captureScreenshot: Boolean): AndroidGuiPlatformState =
        observe(captureScreenshot, Display.DEFAULT_DISPLAY)

    suspend fun observe(
        captureScreenshot: Boolean,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiPlatformState

    suspend fun dispatch(action: Action): AndroidGuiActionResult =
        dispatch(action, Display.DEFAULT_DISPLAY)

    suspend fun dispatch(
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiActionResult

    suspend fun inputTarget(x: Float? = null, y: Float? = null): AndroidGuiInputTarget? =
        inputTarget(x, y, Display.DEFAULT_DISPLAY)

    suspend fun inputTarget(
        x: Float? = null,
        y: Float? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiInputTarget?

    suspend fun installedApplications(): Map<String, String>

    fun inputMethodTop(): Int?
}

internal class AccessibilityAndroidGuiPlatform(
    private val context: Context,
) : AndroidGuiPlatform {
    override fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(context, AssistsService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices
            .split(':')
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull(ComponentName::unflattenFromString)
            .any { component -> component == expected }
    }

    override fun isReady(): Boolean = AssistsService.isReady()

    override fun displaySize(): Pair<Int, Int> = displaySize(Display.DEFAULT_DISPLAY)

    override fun displaySize(displayId: Int): Pair<Int, Int> {
        if (displayId != Display.DEFAULT_DISPLAY) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(displayId)
            if (display != null) {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                return metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
            }
        }
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Point().also(manager.defaultDisplay::getRealSize).let {
                Rect(0, 0, it.x, it.y)
            }
        }
        return bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
    }

    override fun screenshotExcludesOverlays(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override suspend fun observe(
        captureScreenshot: Boolean,
        displayId: Int,
    ): AndroidGuiPlatformState = coroutineScope {
        val service = awaitService()
        val display = displaySize(displayId)
        val (roots, observedPackage) = withContext(Dispatchers.Main.immediate) {
            val seenWindowIds = mutableSetOf<Int>()
            val displayWindows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val allDisplays = runCatching { service.windowsOnAllDisplays }.getOrNull()
                allDisplays?.get(displayId)?.takeIf { it.isNotEmpty() }
                    ?: if (displayId == Display.DEFAULT_DISPLAY) service.windows else emptyList()
            } else {
                if (displayId == Display.DEFAULT_DISPLAY) service.windows else emptyList()
            }

            val collectedRoots = buildList {
                fun addRoot(root: AccessibilityNodeInfo?) {
                    if (root != null && seenWindowIds.add(root.windowId)) add(root)
                }
                displayWindows.forEach { window -> addRoot(window.root) }
                if (displayId == Display.DEFAULT_DISPLAY) {
                    addRoot(service.rootInActiveWindow)
                    service.windows.forEach { window -> addRoot(window.root) }
                }
            }

            val resolvedPackage = resolveDisplayPackage(service, displayId, displayWindows)
            collectedRoots to resolvedPackage
        }
        val xmlDeferred = async(Dispatchers.Default) { AndroidGuiXml.serialize(roots) }
        val screenshotDeferred = if (captureScreenshot) {
            async { captureScreenshot(service, displayId) }
        } else {
            null
        }
        val xml = xmlDeferred.await()
        val activityName = if (displayId == Display.DEFAULT_DISPLAY && observedPackage.isNotEmpty() && observedPackage == service.lastPackageName) {
            service.lastActivityName
        } else {
            ""
        }
        AndroidGuiPlatformState(
            packageName = observedPackage,
            activityName = activityName,
            displayWidth = display.first,
            displayHeight = display.second,
            xml = xml,
            screenshotJpeg = screenshotDeferred?.await(),
        )
    }

    override suspend fun dispatch(action: Action, displayId: Int): AndroidGuiActionResult {
        val service = awaitService()
        return when (action.tool) {
            OobActionSchema.TOOL_CLICK -> gesture(
                service = service,
                x1 = number(action, OobActionSchema.ARG_X),
                y1 = number(action, OobActionSchema.ARG_Y),
                durationMs = CLICK_GESTURE_DURATION_MS,
                displayId = displayId,
            )

            OobActionSchema.TOOL_LONG_PRESS -> gesture(
                service = service,
                x1 = number(action, OobActionSchema.ARG_X),
                y1 = number(action, OobActionSchema.ARG_Y),
                durationMs = long(action, OobActionSchema.ARG_DURATION_MS, 800L),
                displayId = displayId,
            )

            OobActionSchema.TOOL_SWIPE -> gesture(
                service = service,
                x1 = number(action, OobActionSchema.ARG_X1),
                y1 = number(action, OobActionSchema.ARG_Y1),
                x2 = number(action, OobActionSchema.ARG_X2),
                y2 = number(action, OobActionSchema.ARG_Y2),
                durationMs = long(action, OobActionSchema.ARG_DURATION_MS, 300L),
                displayId = displayId,
            )

            OobActionSchema.TOOL_INPUT_TEXT -> inputText(action, displayId)
            OobActionSchema.TOOL_PRESS_KEY -> pressKey(service, action, displayId)
            OobActionSchema.TOOL_OPEN_APP -> openApp(action, displayId)
            OobActionSchema.TOOL_WAIT -> wait(action)
            else -> AndroidGuiActionResult(false, "unsupported_android_gui_action:${action.tool}")
        }
    }

    override suspend fun inputTarget(
        x: Float?,
        y: Float?,
        displayId: Int,
    ): AndroidGuiInputTarget? =
        withNodes(displayId) { nodes ->
            selectInputNode(
                nodes = nodes,
                x = x,
                y = y,
                lookup = InputNodeLookup.CLICK_TARGET,
            )?.toInputTarget()
        }

    override suspend fun installedApplications(): Map<String, String> = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        manager.getInstalledApplications(0)
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.packageName?.trim().orEmpty()
                if (packageName.isEmpty() || manager.getLaunchIntentForPackage(packageName) == null) {
                    return@mapNotNull null
                }
                val label = runCatching { manager.getApplicationLabel(info).toString().trim() }
                    .getOrDefault("")
                label.ifBlank { packageName } to packageName
            }
            .toMap(linkedMapOf())
    }

    override fun inputMethodTop(): Int? {
        val service = AssistsService.readyInstance() ?: return null
        val window = service.windows
            .firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?: return null
        val bounds = Rect().also(window::getBoundsInScreen)
        return bounds.top.takeIf { it > 0 }
    }

    private suspend fun inputText(
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiActionResult {
        val text = action.args[OobActionSchema.ARG_TEXT]?.toString()
            ?: return AndroidGuiActionResult(false, "input_text_required")
        var latest = AndroidGuiActionResult(false, "input_target_not_found")
        repeat(INPUT_TEXT_ATTEMPTS) { attempt ->
            latest = withNodes(displayId) { nodes ->
                val node = selectInputNode(
                    nodes = nodes,
                    x = optionalNumber(action, OobActionSchema.ARG_X),
                    y = optionalNumber(action, OobActionSchema.ARG_Y),
                    resourceId = action.args[OobActionSchema.ARG_NODE_RESOURCE_ID]
                        ?.toString().orEmpty(),
                    lookup = InputNodeLookup.INPUT_ACTION,
                ) ?: return@withNodes AndroidGuiActionResult(false, "input_target_not_found")
                if (node.isPassword) {
                    return@withNodes AndroidGuiActionResult(
                        false,
                        "password_input_not_supported",
                    )
                }
                val arguments = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text,
                    )
                }
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                AndroidGuiActionResult(
                    success,
                    if (success) "input_text_dispatched" else "input_text_failed",
                )
            }
            if (latest.success || latest.message == "password_input_not_supported") return latest
            if (attempt < INPUT_TEXT_ATTEMPTS - 1) delay(INPUT_TEXT_RETRY_DELAY_MS)
        }
        return latest
    }

    private suspend fun pressKey(
        service: AssistsService,
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiActionResult {
        val key = action.args[OobActionSchema.ARG_KEY]?.toString()?.trim()?.lowercase().orEmpty()
        val success = when (key) {
            "back" -> if (displayId == Display.DEFAULT_DISPLAY) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            } else {
                dispatchKeyEventToDisplay(displayId, 4 /* KEYCODE_BACK */)
            }
            "home" -> if (displayId == Display.DEFAULT_DISPLAY) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } else {
                dispatchKeyEventToDisplay(displayId, 3 /* KEYCODE_HOME */)
            }
            "enter" -> pressEnter(action, displayId)
            "select_all" -> performFocusedTextAction(selectAll = true, displayId = displayId)
            "copy" -> performFocusedTextAction(selectAll = false, displayId = displayId)
            else -> return AndroidGuiActionResult(false, "press_key_invalid:$key")
        }
        return AndroidGuiActionResult(success, if (success) "press_key_dispatched" else "press_key_failed")
    }

    private fun dispatchKeyEventToDisplay(displayId: Int, keyCode: Int): Boolean =
        DeviceDisplayManager.injectKeyEvent(displayId, keyCode)

    private suspend fun performFocusedTextAction(
        selectAll: Boolean,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Boolean {
        var success = false
        repeat(PRESS_KEY_ATTEMPTS) { attempt ->
            success = withNodes(displayId) { nodes ->
                val node = selectInputNode(
                    nodes = nodes,
                    x = null,
                    y = null,
                    lookup = InputNodeLookup.INPUT_ACTION,
                ) ?: return@withNodes false
                if (selectAll) {
                    val arguments = Bundle().apply {
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                            0,
                        )
                        putInt(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                            node.text?.length ?: 0,
                        )
                    }
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                        arguments,
                    )
                } else {
                    node.performAction(AccessibilityNodeInfo.ACTION_COPY)
                }
            }
            if (success || attempt == PRESS_KEY_ATTEMPTS - 1) return success
            delay(PRESS_KEY_RETRY_DELAY_MS)
        }
        return success
    }

    private suspend fun pressEnter(
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Boolean {
        val x = optionalNumber(action, OobActionSchema.ARG_X)
        val y = optionalNumber(action, OobActionSchema.ARG_Y)
        val resourceId = action.args[OobActionSchema.ARG_NODE_RESOURCE_ID]
            ?.toString()
            .orEmpty()
        var success = false
        repeat(PRESS_KEY_ATTEMPTS) { attempt ->
            success = withNodes(displayId) { nodes ->
                val node = selectInputNode(
                    nodes = nodes,
                    x = x,
                    y = y,
                    resourceId = resourceId,
                    lookup = InputNodeLookup.INPUT_ACTION,
                ) ?: return@withNodes false
                if (!node.isFocused) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (success || attempt == PRESS_KEY_ATTEMPTS - 1) return success
            delay(PRESS_KEY_RETRY_DELAY_MS)
        }
        return success
    }

    private fun openApp(
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiActionResult {
        val packageName = action.args[OobActionSchema.ARG_PACKAGE_NAME]?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) return AndroidGuiActionResult(false, "open_app_package_required")
        return if (displayId == Display.DEFAULT_DISPLAY) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return AndroidGuiActionResult(false, "open_app_not_found:$packageName")
            intent.addFlags(OPEN_APP_INTENT_FLAGS)
            context.startActivity(intent)
            AndroidGuiActionResult(true, "open_app_dispatched")
        } else {
            // For secondary/virtual displays, delegate to DeviceDisplayManager
            kotlinx.coroutines.runBlocking {
                DeviceDisplayManager.launchAppOnDisplay(context, packageName, displayId)
            }
        }
    }

    private suspend fun wait(action: Action): AndroidGuiActionResult {
        val durationMs = long(action, OobActionSchema.ARG_DURATION_MS, -1L)
        if (durationMs < 0L) return AndroidGuiActionResult(false, "wait_duration_ms_required")
        delay(durationMs.coerceIn(0L, MAX_WAIT_MS))
        return AndroidGuiActionResult(true, "wait_completed")
    }

    private suspend fun gesture(
        service: AssistsService,
        x1: Float,
        y1: Float,
        x2: Float = x1,
        y2: Float = y1,
        durationMs: Long,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiActionResult = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setDisplayId(displayId)
        }
        val gesture = builder.build()
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) {
                        continuation.resume(AndroidGuiActionResult(true, "gesture_dispatched"))
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) {
                        continuation.resume(AndroidGuiActionResult(false, "gesture_cancelled"))
                    }
                }
            },
            null,
        )
        if (!accepted && continuation.isActive) {
            if (displayId != Display.DEFAULT_DISPLAY) {
                val injected = if (x1 == x2 && y1 == y2) {
                    DeviceDisplayManager.injectTap(displayId, x1.toInt(), y1.toInt())
                } else {
                    DeviceDisplayManager.injectSwipe(displayId, x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), durationMs)
                }
                continuation.resume(
                    AndroidGuiActionResult(
                        injected,
                        if (injected) "gesture_dispatched_via_root" else "gesture_rejected",
                    ),
                )
            } else {
                continuation.resume(AndroidGuiActionResult(false, "gesture_rejected"))
            }
        }
    }
    private suspend fun captureScreenshot(
        service: AssistsService,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): ByteArray? {
        if (displayId != Display.DEFAULT_DISPLAY) {
            val virtualBytes = DeviceDisplayManager.captureVirtualScreenshot(displayId)
            if (virtualBytes != null) return virtualBytes
        }
        return suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    val bytes = bitmap?.let { image ->
                        ByteArrayOutputStream().use { output ->
                            image.compress(Bitmap.CompressFormat.JPEG, 88, output)
                            output.toByteArray()
                        }.also { image.recycle() }
                    }
                    if (continuation.isActive) continuation.resume(bytes)
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            service.takeScreenshot(
                displayId,
                service.mainExecutor,
                callback,
            )
        }
    }

    private suspend fun <T> withNodes(
        displayId: Int = Display.DEFAULT_DISPLAY,
        block: (List<AccessibilityNodeInfo>) -> T,
    ): T {
        val service = awaitService()
        val roots = withContext(Dispatchers.Main.immediate) {
            val seenWindowIds = mutableSetOf<Int>()
            buildList {
                fun addRoot(root: AccessibilityNodeInfo) {
                    if (seenWindowIds.add(root.windowId)) add(root)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val allDisplays = runCatching { service.windowsOnAllDisplays }.getOrNull()
                    val displayWindows = allDisplays?.get(displayId)
                    if (displayWindows != null && displayWindows.isNotEmpty()) {
                        displayWindows.forEach { window ->
                            window.root?.let(::addRoot)
                        }
                        return@buildList
                    }
                }
                if (displayId == Display.DEFAULT_DISPLAY) {
                    service.rootInActiveWindow?.let(::addRoot)
                    service.windows.forEach { window ->
                        window.root?.let(::addRoot)
                    }
                }
            }
        }
        if (roots.isEmpty()) return block(emptyList())
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_NODE_DEPTH) return
            nodes += node
            for (index in 0 until node.childCount) {
                @Suppress("DEPRECATION")
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                collect(child, depth + 1)
            }
        }
        roots.forEach { root -> collect(root, 0) }
        return try {
            block(nodes)
        } finally {
            nodes.asReversed().forEach { node ->
                @Suppress("DEPRECATION")
                runCatching { node.recycle() }
            }
        }
    }

    private fun selectInputNode(
        nodes: List<AccessibilityNodeInfo>,
        x: Float?,
        y: Float?,
        resourceId: String = "",
        lookup: InputNodeLookup,
    ): AccessibilityNodeInfo? {
        val editable = nodes.filter { it.isEditable && it.isEnabled && it.isVisibleToUser }
        if (resourceId.isNotBlank()) {
            editable.firstOrNull { node ->
                node.viewIdResourceName == resourceId && x != null && y != null &&
                    Rect().also(node::getBoundsInScreen).contains(x.toInt(), y.toInt())
            }?.let { return it }
        }
        if (x != null && y != null) {
            editable.firstOrNull { node ->
                Rect().also(node::getBoundsInScreen).contains(x.toInt(), y.toInt())
            }?.let { return it }
            if (!lookup.allowFallbackAfterCoordinateMiss) return null
        }
        if (resourceId.isNotBlank()) {
            editable.firstOrNull { it.viewIdResourceName == resourceId && it.isFocused }
                ?.let { return it }
            editable.firstOrNull { it.viewIdResourceName == resourceId }?.let { return it }
        }
        return editable.firstOrNull(AccessibilityNodeInfo::isFocused) ?: editable.firstOrNull()
    }

    private fun AccessibilityNodeInfo.toInputTarget(): AndroidGuiInputTarget {
        val bounds = Rect().also(::getBoundsInScreen)
        return AndroidGuiInputTarget(
            description = listOfNotNull(
                hintText?.toString(),
                contentDescription?.toString(),
                text?.toString(),
                viewIdResourceName,
            ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "输入框" },
            x = bounds.exactCenterX(),
            y = bounds.exactCenterY(),
            nodeResourceId = viewIdResourceName.orEmpty(),
            password = isPassword,
        )
    }

    private suspend fun awaitService(): AssistsService {
        AssistsService.readyInstance()?.let { return it }
        return withTimeoutOrNull(ACCESSIBILITY_RECONNECT_TIMEOUT_MS) {
            while (!AssistsService.isReady()) delay(50L)
            checkNotNull(AssistsService.readyInstance())
        } ?: error("android_gui_accessibility_not_ready")
    }

    private fun resolveDisplayPackage(
        service: AssistsService,
        displayId: Int,
        displayWindows: List<AccessibilityWindowInfo>,
    ): String {
        val isLocked = runCatching { KeyguardStateMonitor.isKeyguardLocked(context) }.getOrDefault(false)
        if (displayId == Display.DEFAULT_DISPLAY && isLocked) {
            val lockscreenWindow = displayWindows.firstOrNull { it.isFocused }
                ?: displayWindows.firstOrNull { it.isActive }
            val lockPkg = lockscreenWindow?.let(::windowPackage).orEmpty()
            if (lockPkg.isNotEmpty()) return lockPkg
            val activePkg = runCatching { service.rootInActiveWindow?.packageName?.toString()?.trim() }.getOrNull().orEmpty()
            if (activePkg.isNotEmpty()) return activePkg
            return "com.android.systemui"
        }

        val focusedAppPkg = displayWindows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
            ?.let(::windowPackage)
            .orEmpty()
        if (focusedAppPkg.isNotEmpty()) return focusedAppPkg

        val activeAppPkg = displayWindows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.let(::windowPackage)
            .orEmpty()
        if (activeAppPkg.isNotEmpty()) return activeAppPkg

        val anyAppPkg = displayWindows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.let(::windowPackage)
            .orEmpty()
        if (anyAppPkg.isNotEmpty()) return anyAppPkg

        val focusedWindowPkg = (displayWindows.firstOrNull { it.isFocused }
            ?: displayWindows.firstOrNull { it.isActive })
            ?.let(::windowPackage)
            .orEmpty()
        if (focusedWindowPkg.isNotEmpty()) return focusedWindowPkg

        if (displayId == Display.DEFAULT_DISPLAY) {
            val activePkg = runCatching { service.rootInActiveWindow?.packageName?.toString()?.trim() }.getOrNull().orEmpty()
            if (activePkg.isNotEmpty()) return activePkg
            return service.lastPackageName
        }

        return ""
    }

    private fun windowPackage(window: AccessibilityWindowInfo): String =
        runCatching { window.root?.packageName?.toString()?.trim() }.getOrNull().orEmpty()

    private fun number(action: Action, key: String): Float =
        optionalNumber(action, key) ?: error("${action.tool}_${key}_required")

    private fun optionalNumber(action: Action, key: String): Float? = when (val value = action.args[key]) {
        is Number -> value.toFloat()
        is String -> value.toFloatOrNull()
        else -> null
    }

    private fun long(action: Action, key: String, default: Long): Long = when (val value = action.args[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: default
        else -> default
    }

    private companion object {
        const val MAX_WAIT_MS = 60_000L
        const val ACCESSIBILITY_RECONNECT_TIMEOUT_MS = ACCESSIBILITY_READY_TIMEOUT_MS
        const val MAX_NODE_DEPTH = 50
        const val INPUT_TEXT_ATTEMPTS = 6
        const val INPUT_TEXT_RETRY_DELAY_MS = 120L
        const val PRESS_KEY_ATTEMPTS = 6
        const val PRESS_KEY_RETRY_DELAY_MS = 120L
    }
}

internal const val CLICK_GESTURE_DURATION_MS = 100L

internal enum class InputNodeLookup(
    val allowFallbackAfterCoordinateMiss: Boolean,
) {
    CLICK_TARGET(false),
    INPUT_ACTION(true),
}

internal val OPEN_APP_INTENT_FLAGS: Int =
    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
