package cn.com.omnimind.androidgui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import cn.com.omnimind.accessibility.service.AssistsService
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.ActionCoordinateCodec
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.State
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
data class AndroidGuiActionResult(
    val success: Boolean,
    val message: String,
    val diagnostics: Map<String, String> = emptyMap(),
)

data class AndroidGuiScreenSnapshot(
    val packageName: String,
    val activityName: String,
    val displayWidth: Int,
    val displayHeight: Int,
    val screenshotJpeg: ByteArray?,
)

data class AndroidGuiObservation(
    val state: State,
    val stabilization: Map<String, Any?>,
    val extra: Map<String, Any?> = emptyMap(),
)

/** Raised when a physical-display GUI task can no longer safely continue. */
class AndroidGuiDisplayOffException : IllegalStateException("android_gui_display_off")

enum class AndroidGuiAccessibilityStatus {
    DISABLED,
    CONNECTING,
    READY,
}

class AndroidGuiEnvironment internal constructor(
    private val appContext: Context?,
    private val platform: AndroidGuiPlatform,
    private val ocrEngine: AndroidScreenOcrEngine? = null,
) : Closeable {
    constructor(context: Context) : this(
        appContext = context.applicationContext ?: context,
        platform = AccessibilityAndroidGuiPlatform(context.applicationContext ?: context),
        ocrEngine = createDefaultOcrEngine(context.applicationContext ?: context),
    )

    constructor(context: Context, ocrEngine: AndroidScreenOcrEngine?) : this(
        appContext = context.applicationContext ?: context,
        platform = AccessibilityAndroidGuiPlatform(context.applicationContext ?: context),
        ocrEngine = ocrEngine,
    )

    private var currentBaseline: AndroidGuiObservationBaseline? = null
    private val observationCounter = AtomicLong(0L)
    private val ocrCache = ConcurrentHashMap<OcrCacheKey, List<Map<String, Any?>>>()

    fun clearObservation() {
        currentBaseline = null
        ocrCache.clear()
    }

    override fun close() {
        clearObservation()
        (ocrEngine as? AutoCloseable)?.runCatching { close() }
    }

    fun registerObservationBaseline(baseline: AndroidGuiObservationBaseline) {
        currentBaseline = baseline
    }

    fun currentObservationBaseline(): AndroidGuiObservationBaseline? = currentBaseline
    fun accessibilityStatus(): AndroidGuiAccessibilityStatus = when {
        !platform.isAccessibilityEnabled() -> AndroidGuiAccessibilityStatus.DISABLED
        platform.isReady() -> AndroidGuiAccessibilityStatus.READY
        else -> AndroidGuiAccessibilityStatus.CONNECTING
    }

    fun isAccessibilityEnabled(): Boolean =
        accessibilityStatus() != AndroidGuiAccessibilityStatus.DISABLED

    fun isReady(): Boolean = accessibilityStatus() == AndroidGuiAccessibilityStatus.READY

    suspend fun awaitReady(
        timeoutMs: Long = ACCESSIBILITY_READY_TIMEOUT_MS,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Boolean {
        ensureDisplayInteractive(displayId)
        if (!isAccessibilityEnabled()) return false
        return withTimeoutOrNull(timeoutMs) {
            while (!platform.isReady()) delay(50L)
            true
        } ?: false
    }

    fun openAccessibilitySettings() {
        val context = checkNotNull(appContext) { "android_gui_context_required" }
        val component = ComponentName(context, AssistsService::class.java)
        val detailsIntent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val detailsActivity = detailsIntent.resolveActivityInfo(context.packageManager, 0)
        val detailsPermissionGranted = detailsActivity?.permission
            ?.let { permission ->
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            }
            ?: true
        val openedDetails = detailsActivity != null && detailsPermissionGranted &&
            runCatching { context.startActivity(detailsIntent) }.isSuccess
        if (!openedDetails) {
            context.startActivity(fallbackIntent)
        }
    }

    fun displaySize(): Pair<Int, Int> = platform.displaySize(Display.DEFAULT_DISPLAY)

    fun displaySize(displayId: Int): Pair<Int, Int> = platform.displaySize(displayId)

    fun screenshotExcludesOverlays(): Boolean = platform.screenshotExcludesOverlays()

    suspend fun observe(
        captureScreenshot: Boolean = true,
        waitToStabilize: Boolean = false,
        displayId: Int = Display.DEFAULT_DISPLAY,
        leaseOwnerId: String? = null,
        ocr: Boolean = false,
        region: AndroidGuiObservationRegion? = null,
    ): State = observeWithDiagnostics(
        captureScreenshot = captureScreenshot,
        waitToStabilize = waitToStabilize,
        displayId = displayId,
        leaseOwnerId = leaseOwnerId,
        ocr = ocr,
        region = region,
    ).state

    suspend fun observeWithDiagnostics(
        captureScreenshot: Boolean = true,
        waitToStabilize: Boolean = false,
        displayId: Int = Display.DEFAULT_DISPLAY,
        leaseOwnerId: String? = null,
        ocr: Boolean = false,
        region: AndroidGuiObservationRegion? = null,
    ): AndroidGuiObservation {
        check(awaitReady(displayId = displayId)) { "android_gui_accessibility_not_ready" }
        val context = checkNotNull(appContext) { "android_gui_context_required" }
        KeyguardStateMonitor.ensureInteractiveAndUnlocked(context, displayId, leaseOwnerId)

        val forceScreenshot = ocr || region != null
        val needScreenshot = captureScreenshot || forceScreenshot
        val observedAndStability = if (waitToStabilize) {
            observeStable(needScreenshot, displayId)
        } else {
            platform.observe(needScreenshot, displayId) to false
        }
        val observed = observedAndStability.first
        val stabilized = observedAndStability.second
        val state = State.create(
            packageName = observed.packageName,
            activityName = observed.activityName,
            displayWidth = observed.displayWidth,
            displayHeight = observed.displayHeight,
            xml = observed.xml,
        )
        val persisted = InternalRunLogStore.persistState(context, state, observed.screenshotJpeg)

        val obsId = "obs_${SystemClock.elapsedRealtime()}_${observationCounter.incrementAndGet()}"
        val frameSeq = observed.frameSequence

        // Visual regions (最多一个高清区域，包含全屏 0..1000 归一化 bounds 与 image_base64)
        val visualRegions = mutableListOf<Map<String, Any?>>()
        var decodedBitmap: Bitmap? = null
        try {
            if (region != null && observed.screenshotJpeg != null) {
                decodedBitmap = BitmapFactory.decodeByteArray(observed.screenshotJpeg, 0, observed.screenshotJpeg.size)
                if (decodedBitmap != null) {
                    val rect = region.toRect(observed.displayWidth, observed.displayHeight)
                    val cropLeft = rect.left.coerceIn(0, decodedBitmap.width - 1)
                    val cropTop = rect.top.coerceIn(0, decodedBitmap.height - 1)
                    val cropWidth = rect.width().coerceAtMost(decodedBitmap.width - cropLeft)
                    val cropHeight = rect.height().coerceAtMost(decodedBitmap.height - cropTop)
                    if (cropWidth > 0 && cropHeight > 0) {
                        val cropped = Bitmap.createBitmap(decodedBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                        try {
                            val base64 = ByteArrayOutputStream().use { out ->
                                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                Base64.getEncoder().encodeToString(out.toByteArray())
                            }
                            visualRegions.add(
                                mapOf(
                                    "bounds" to region.toNormalizedList(),
                                    "image_base64" to base64,
                                    "width" to cropWidth,
                                    "height" to cropHeight,
                                )
                            )
                        } finally {
                            if (cropped !== decodedBitmap) {
                                cropped.recycle()
                            }
                        }
                    }
                }
            }

            // OCR 逻辑：显式请求 或 区域请求，或在已有截图前提下树缺少可用文字时自动触发（普通无图观测不触发，避免无图噪声）
            val shouldRunOcr = ocr || (region != null) || (needScreenshot && AndroidGuiSafetyGuard.treeLacksUsableText(observed.xml))
            val ocrResult = if (shouldRunOcr) {
                executeOcr(
                    observed = observed,
                    decodedBitmap = decodedBitmap,
                    region = region,
                    displayId = displayId,
                    frameSequence = frameSeq,
                )
            } else {
                null
            }

            // 记录内存基线（最多仅当前一份，生命周期清理）
            val baseline = AndroidGuiObservationBaseline(
                observationId = obsId,
                displayId = displayId,
                frameSequence = frameSeq,
                stateId = persisted.stateId,
                packageName = observed.packageName,
                activityName = observed.activityName,
                displayWidth = observed.displayWidth,
                displayHeight = observed.displayHeight,
                xml = observed.xml,
                screenshotJpeg = observed.screenshotJpeg,
            )
            registerObservationBaseline(baseline)

            val extra = buildMap<String, Any?> {
                put("observation_id", obsId)
                put("display_id", displayId)
                put("frame_sequence", frameSeq)
                if (ocrResult != null) {
                    put("ocr", ocrResult)
                }
                if (visualRegions.isNotEmpty()) {
                    put("visual_regions", visualRegions)
                }
            }

            return AndroidGuiObservation(
                state = persisted,
                stabilization = linkedMapOf<String, Any?>(
                    "wait_requested" to waitToStabilize,
                    "stabilized" to if (waitToStabilize) stabilized else null,
                    "result" to when {
                        !waitToStabilize -> "not_requested"
                        stabilized -> "stable"
                        else -> "timeout"
                    },
                ).filterValues { it != null },
                extra = extra,
            )
        } finally {
            decodedBitmap?.recycle()
        }
    }

    private suspend fun executeOcr(
        observed: AndroidGuiPlatformState,
        decodedBitmap: Bitmap?,
        region: AndroidGuiObservationRegion?,
        displayId: Int,
        frameSequence: Long?,
    ): Map<String, Any?> {
        val jpeg = observed.screenshotJpeg
        if (jpeg == null || jpeg.isEmpty()) {
            return mapOf(
                "status" to "failed",
                "engine" to "paddle_ocr_v6",
                "cached" to false,
                "items" to emptyList<Map<String, Any?>>(),
                "message" to "screenshot_unavailable_for_ocr",
            )
        }

        val cacheKey = OcrCacheKey(
            displayId = displayId,
            region = region,
            imageSha256 = sha256Hex(jpeg),
        )

        val cached = ocrCache[cacheKey]
        if (cached != null) {
            return mapOf(
                "status" to "ready",
                "engine" to "paddle_ocr_v6",
                "cached" to true,
                "items" to cached,
            )
        }

        val engine = ocrEngine
        if (engine == null) {
            return mapOf(
                "status" to "unavailable",
                "engine" to "paddle_ocr_v6",
                "cached" to false,
                "items" to emptyList<Map<String, Any?>>(),
                "message" to "ocr_engine_not_configured",
            )
        }

        return withContext(Dispatchers.Default) {
            var tempBitmap: Bitmap? = null
            var croppedOcrBitmap: Bitmap? = null
            try {
                val sourceBitmap = if (decodedBitmap != null && !decodedBitmap.isRecycled) {
                    decodedBitmap
                } else {
                    tempBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    tempBitmap
                }

                if (sourceBitmap == null) {
                    return@withContext mapOf(
                        "status" to "failed",
                        "engine" to "paddle_ocr_v6",
                        "cached" to false,
                        "items" to emptyList<Map<String, Any?>>(),
                        "message" to "bitmap_decode_failed",
                    )
                }

                val (inputBitmap, offsetX, offsetY) = if (region != null) {
                    val rect = region.toRect(observed.displayWidth, observed.displayHeight)
                    val cropLeft = rect.left.coerceIn(0, sourceBitmap.width - 1)
                    val cropTop = rect.top.coerceIn(0, sourceBitmap.height - 1)
                    val cropW = rect.width().coerceAtMost(sourceBitmap.width - cropLeft)
                    val cropH = rect.height().coerceAtMost(sourceBitmap.height - cropTop)
                    if (cropW > 0 && cropH > 0) {
                        croppedOcrBitmap = Bitmap.createBitmap(sourceBitmap, cropLeft, cropTop, cropW, cropH)
                        Triple(croppedOcrBitmap, cropLeft, cropTop)
                    } else {
                        Triple(sourceBitmap, 0, 0)
                    }
                } else {
                    Triple(sourceBitmap, 0, 0)
                }

                val recognizedItems = engine.recognize(inputBitmap)
                val mappedItems = recognizedItems.map { item ->
                    mapOf(
                        "text" to item.text,
                        "bounds" to listOf(
                            item.bounds.left + offsetX,
                            item.bounds.top + offsetY,
                            item.bounds.right + offsetX,
                            item.bounds.bottom + offsetY,
                        ),
                        "confidence" to item.confidence,
                        "source" to "ocr",
                    )
                }

                if (ocrCache.size > 32) {
                    ocrCache.clear()
                }
                ocrCache[cacheKey] = mappedItems

                mapOf(
                    "status" to "ready",
                    "engine" to "paddle_ocr_v6",
                    "cached" to false,
                    "items" to mappedItems,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mapOf(
                    "status" to "failed",
                    "engine" to "paddle_ocr_v6",
                    "cached" to false,
                    "items" to emptyList<Map<String, Any?>>(),
                    "message" to (e.message ?: "ocr_recognition_failed"),
                )
            } finally {
                croppedOcrBitmap?.recycle()
                tempBitmap?.recycle()
            }
        }
    }

    private suspend fun observeStable(
        captureScreenshot: Boolean,
        displayId: Int,
    ): Pair<AndroidGuiPlatformState, Boolean> {
        var previous = platform.observe(captureScreenshot, displayId)
        var current = previous
        var stableChecks = 1
        val deadline = SystemClock.elapsedRealtime() + OBSERVE_STABILIZATION_TIMEOUT_MS
        while (stableChecks < OBSERVE_STABILIZATION_THRESHOLD) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) break
            delay(min(OBSERVE_STABILIZATION_POLL_MS, remaining))
            if (SystemClock.elapsedRealtime() >= deadline) break
            current = platform.observe(captureScreenshot, displayId)
            stableChecks = if (sameUiState(previous, current)) {
                stableChecks + 1
            } else {
                1
            }
            previous = current
        }
        return current to (stableChecks >= OBSERVE_STABILIZATION_THRESHOLD)
    }

    private fun sameUiState(
        previous: AndroidGuiPlatformState,
        current: AndroidGuiPlatformState,
    ): Boolean = previous.packageName == current.packageName &&
        previous.activityName == current.activityName &&
        previous.displayWidth == current.displayWidth &&
        previous.displayHeight == current.displayHeight &&
        previous.xml == current.xml

    /** Capture a transient preview without writing a RunLog state or image to disk. */
    suspend fun captureScreenSnapshot(
        displayId: Int = Display.DEFAULT_DISPLAY,
        leaseOwnerId: String? = null,
    ): AndroidGuiScreenSnapshot {
        check(awaitReady(displayId = displayId)) { "android_gui_accessibility_not_ready" }
        appContext?.let { ctx ->
            KeyguardStateMonitor.ensureInteractiveAndUnlocked(ctx, displayId, leaseOwnerId)
        }
        val observed = platform.observe(captureScreenshot = true, displayId = displayId)
        return AndroidGuiScreenSnapshot(
            packageName = observed.packageName,
            activityName = observed.activityName,
            displayWidth = observed.displayWidth,
            displayHeight = observed.displayHeight,
            screenshotJpeg = observed.screenshotJpeg,
        )
    }

    /**
     * Dispatches an action onto [displayId] protected by [DisplayInputCoordinator] lease.
     *
     * Ensures:
     * - Multi-step GUI runs with [leaseOwnerId] reuse their lease reentrantly without deadlocks.
     * - Screen is interactive and unlocked (via [KeyguardStateMonitor]).
     * - Coordinates are canonicalized against [displayId] resolution.
     */
    suspend fun act(
        action: Action,
        awaitStabilization: Boolean = true,
        displayId: Int = Display.DEFAULT_DISPLAY,
        leaseOwnerId: String? = null,
        expectedState: State? = null,
        expectedObservationId: String? = null,
    ): AndroidGuiActionResult = DisplayInputCoordinator.withDisplayLock(displayId, leaseOwnerId) {
        val currentJob = currentCoroutineContext().job
        leaseOwnerId?.let { owner ->
            DisplayInputCoordinator.registerActiveJob(displayId, owner, currentJob)
        }
        try {
            if (!awaitReady(displayId = displayId)) {
                return@withDisplayLock AndroidGuiActionResult(
                    success = false,
                    message = "android_gui_accessibility_not_ready",
                )
            }

            val context = appContext
            if (context != null) {
                val unlocked = KeyguardStateMonitor.ensureInteractiveAndUnlocked(context, displayId, leaseOwnerId)
                if (!unlocked) {
                    return@withDisplayLock AndroidGuiActionResult(
                        success = false,
                        message = "device_locked_awaiting_user_unlock",
                    )
                }
            }

            // 在现有 input lock 内核验当前 observed id、display、fresh tree/target 或目标 ROI 像素变化
            val targetDesc = action.args["target_description"] as? String
            val nodeId = action.args["node_id"]?.toString()
            val resId = action.args["node_resource_id"] as? String
            val hasTargetClues = !targetDesc.isNullOrBlank() || !nodeId.isNullOrBlank() || !resId.isNullOrBlank()
            val isCoordinateAction = action.tool in OobActionSchema.coordinateToolNames ||
                action.args["x"] != null || action.args["y"] != null

            val needsGuardCheck = expectedObservationId != null || expectedState != null || hasTargetClues
            val shouldObserveBeforeAction = (awaitStabilization || needsGuardCheck) && action.tool != OobActionSchema.TOOL_WAIT

            val freshObserved = if (shouldObserveBeforeAction) {
                val needsScreenshotForGuard = needsGuardCheck && currentBaseline?.screenshotJpeg != null && isCoordinateAction

                platform.observe(
                    captureScreenshot = needsScreenshotForGuard,
                    displayId = displayId,
                )
            } else {
                null
            }

            var actionToDispatch = canonicalForDisplay(action, displayId)

            if (freshObserved != null && needsGuardCheck) {
                val displaySize = platform.displaySize(displayId)
                val guardResult = AndroidGuiSafetyGuard.prepareGuardedAction(
                    action = action,
                    expectedState = expectedState,
                    expectedObservationId = expectedObservationId,
                    baseline = currentBaseline,
                    currentDisplayId = displayId,
                    currentDisplaySize = displaySize,
                    freshXml = freshObserved.xml,
                    freshPackageName = freshObserved.packageName,
                    freshScreenshotJpeg = freshObserved.screenshotJpeg,
                )

                when (guardResult) {
                    is GuardResult.Rejected -> {
                        return@withDisplayLock guardResult.failure
                    }
                    is GuardResult.Allowed -> {
                        actionToDispatch = canonicalForDisplay(guardResult.action, displayId)
                    }
                }
            }

            val stateBeforeAction = if (
                awaitStabilization && action.tool != OobActionSchema.TOOL_WAIT
            ) {
                freshObserved?.uiFingerprint()
            } else {
                null
            }

            val result = platform.dispatch(actionToDispatch, displayId = displayId)

            if (!result.success) return@withDisplayLock result
            if (!awaitStabilization) {
                return@withDisplayLock result.withStabilization("runtime_delegated")
            }
            if (action.tool == OobActionSchema.TOOL_WAIT) {
                return@withDisplayLock result.withStabilization("completed_by_action")
            }

            val stable = withTimeoutOrNull(stateStabilizationTimeoutMs(action.tool)) {
                var previous = stateBeforeAction
                var stateChanged = false
                while (true) {
                    delay(STATE_STABILIZATION_POLL_MS)
                    val fingerprint = platform.observe(
                        captureScreenshot = false,
                        displayId = displayId,
                    ).uiFingerprint()
                    if (!stateChanged) {
                        if (fingerprint != stateBeforeAction) {
                            stateChanged = true
                        }
                    } else if (fingerprint == previous) {
                        return@withTimeoutOrNull true
                    }
                    previous = fingerprint
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } == true

            result.copy(
                diagnostics = result.diagnostics + mapOf(
                    "state_stabilization" to if (stable) {
                        "host_completed"
                    } else {
                        "runtime_delegated"
                    },
                    "state_stabilization_result" to if (stable) "stable" else "timeout",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AndroidGuiActionResult(
                success = false,
                message = error.message ?: "android_gui_action_failed",
            )
        } finally {
            DisplayInputCoordinator.unregisterActiveJob(displayId, currentJob)
        }
    }

    private fun AndroidGuiPlatformState.uiFingerprint(): String = buildString {
        append(packageName)
        append('\u0000')
        append(activityName)
        append('\u0000')
        append(xml)
    }

    private fun AndroidGuiActionResult.withStabilization(result: String) = copy(
        diagnostics = diagnostics + mapOf(
            "state_stabilization" to "host_completed",
            "state_stabilization_result" to result,
        ),
    )

    suspend fun inputTarget(
        x: Float? = null,
        y: Float? = null,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): AndroidGuiInputTarget? =
        if (awaitReady(displayId = displayId)) platform.inputTarget(x, y, displayId) else null

    suspend fun installedApplications(): Map<String, String> = platform.installedApplications()

    fun inputMethodTop(): Int? = runCatching(platform::inputMethodTop).getOrNull()

    private suspend fun canonicalForDisplay(
        action: Action,
        displayId: Int = Display.DEFAULT_DISPLAY,
    ): Action {
        if (action.tool !in OobActionSchema.coordinateToolNames) return action
        val display = platform.displaySize(displayId)
        val args = ActionCoordinateCodec.toScreenPixels(
            args = action.args,
            displaySize = ActionCoordinateCodec.DisplaySize(
                width = display.first.toDouble(),
                height = display.second.toDouble(),
            ),
        )
        return action.copy(args = args)
    }

    private fun ensureDisplayInteractive(displayId: Int = Display.DEFAULT_DISPLAY) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val context = appContext ?: return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isInteractive) {
            throw AndroidGuiDisplayOffException()
        }
    }
}

private data class OcrCacheKey(
    val displayId: Int,
    val region: AndroidGuiObservationRegion?,
    val imageSha256: String,
)

private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun createDefaultOcrEngine(context: Context): AndroidScreenOcrEngine? = runCatching {
    AndroidScreenOcr(context)
}.getOrNull()

private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
    "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
internal const val ACCESSIBILITY_READY_TIMEOUT_MS = 15_000L
private const val STATE_STABILIZATION_POLL_MS = 100L
private const val STATE_STABILIZATION_TIMEOUT_MS = 1_500L
private const val OBSERVE_STABILIZATION_THRESHOLD = 3
private const val OBSERVE_STABILIZATION_POLL_MS = 500L
private const val OBSERVE_STABILIZATION_TIMEOUT_MS = 6_000L
private const val OPEN_APP_STABILIZATION_TIMEOUT_MS = 5_000L

internal fun stateStabilizationTimeoutMs(tool: String): Long =
    if (tool == OobActionSchema.TOOL_OPEN_APP) {
        OPEN_APP_STABILIZATION_TIMEOUT_MS
    } else {
        STATE_STABILIZATION_TIMEOUT_MS
    }
