package cn.com.omnimind.bot.agent.display

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Display
import android.view.View
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.KeyguardStateMonitor
import cn.com.omnimind.baselib.util.OmniLog

/**
 * 虚拟显示屏实时画面渲染组件。
 *
 * 核心契约：
 * 1. 唯一 10fps 调度上限，内部通过 Handler 节流调度重绘。
 * 2. 仅在附着、可见、交互中、已解锁且虚拟屏所有者严格匹配时才执行重绘。
 * 3. 锁屏强制清黑，解绑、重新可见、屏幕解锁等状态切换时记录新帧门槛，丢弃残留旧帧。
 * 4. onDetachedFromWindow 时自动停止帧循环并安全解绑广播接收器。
 * 5. 缩略镜（微缩胶囊）与展开画布共享本组件，按比例 fit-center 居中渲染，onDraw 严禁分配 Bitmap 或执行后台编码。
 */
class VirtualDisplayFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "VirtualDisplayFrameView"
        const val MAX_FPS = 10
        const val FRAME_INTERVAL_MS = 1000L / MAX_FPS // 100ms
    }

    var targetDisplayId: Int = Display.DEFAULT_DISPLAY
        private set

    var targetSessionId: String = ""
        private set

    val displayId: Int get() = targetDisplayId
    val sessionId: String get() = targetSessionId

    var previewActive: Boolean = true
        private set

    @Volatile
    var minRequiredFrameSequence: Long = -1L
        internal set

    var requireNewFrameAfterUnlock: Boolean = false
        internal set

    var isFrameLoopRunning: Boolean = false
        private set

    var isScreenLockReceiverRegistered: Boolean = false
        private set
    private var hasWindowBeenVisible: Boolean = false


    var onKeyguardChanged: (() -> Unit)? = null
    var onFrameDrawn: (() -> Unit)? = null
    val lastDrawnContentRect = Rect()

    /**
     * 计算当前虚拟屏画面在组件内的实际绘制矩形。
     * 直接使用底层 VirtualScreenFrameSource 真实帧绘制的物理矩形（完全同源）；
     * 首帧前、清黑或停帧后为空边界（outRect.setEmpty()），不显示光晕比猜测更安全。
     */
    fun getContentRect(outRect: RectF) {
        if (lastDrawnContentRect.width() > 0 && lastDrawnContentRect.height() > 0) {
            outRect.set(
                lastDrawnContentRect.left.toFloat(),
                lastDrawnContentRect.top.toFloat(),
                lastDrawnContentRect.right.toFloat(),
                lastDrawnContentRect.bottom.toFloat()
            )
        } else {
            outRect.setEmpty()
        }
    }

    private fun resolveDisplayAspectRatio(): Float {
        if (targetDisplayId > 0) {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(targetDisplayId)
            if (display != null) {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                    return metrics.widthPixels.toFloat() / metrics.heightPixels.toFloat()
                }
            }
        }
        // 默认虚拟屏规格：1080 x 2400 (纵向全面屏)
        return 1080f / 2400f
    }


    private val destRect = Rect()
    private val frameHandler = Handler(Looper.getMainLooper())

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isFrameLoopRunning) return
            if (shouldDrawFrames()) {
                invalidate()
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
            } else {
                isFrameLoopRunning = false
                invalidate()
            }
        }
    }

    private fun ensureWatermarkOnInteractiveUnlocked() {
        if (requireNewFrameAfterUnlock) {
            val currentSeq = DeviceDisplayManager.virtualFrameSequence(targetDisplayId, targetSessionId) ?: -1L
            minRequiredFrameSequence = currentSeq
            requireNewFrameAfterUnlock = false
        }
    }

    private val screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏幕关闭/息屏：标记需要等待解锁后新帧、停帧、画布强制清黑与几何清空
                    requireNewFrameAfterUnlock = true
                    lastDrawnContentRect.setEmpty()
                    stopFrameLoop()
                    invalidate()
                    onKeyguardChanged?.invoke()
                }
                Intent.ACTION_SCREEN_ON -> {
                    onKeyguardChanged?.invoke()
                    if (shouldDrawFrames()) {
                        startFrameLoop()
                    } else {
                        invalidate()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    ensureWatermarkOnInteractiveUnlocked()
                    onKeyguardChanged?.invoke()
                    if (shouldDrawFrames()) {
                        startFrameLoop()
                    } else {
                        invalidate()
                    }
                }
            }
        }
    }

    /**
     * 绑定目标虚拟显示屏及所属会话。
     */
    fun bind(displayId: Int, sessionId: String) {
        val changed = this.targetDisplayId != displayId || this.targetSessionId != sessionId
        this.targetDisplayId = displayId
        this.targetSessionId = sessionId
        if (changed) {
            minRequiredFrameSequence = -1L
            requireNewFrameAfterUnlock = false
            hasWindowBeenVisible = (isAttachedToWindow && windowVisibility == VISIBLE)
            lastDrawnContentRect.setEmpty()
        }
        updateLoopState()
    }

    /**
     * 控制预览激活状态。非激活状态下停止帧循环并重绘清黑。
     */
    fun setPreviewActive(active: Boolean) {
        if (this.previewActive == active) return
        this.previewActive = active
        if (!active) {
            lastDrawnContentRect.setEmpty()
            if (hasWindowBeenVisible) {
                requireNewFrameAfterUnlock = true
            }
        }
        updateLoopState()
    }

    /**
     * 检查当前状态是否允许绘制画面帧。
     */
    fun shouldDrawFrames(): Boolean {
        if (!previewActive) return false
        if (targetDisplayId == Display.DEFAULT_DISPLAY || targetSessionId.isEmpty()) return false
        if (!isAttachedToWindow) return false
        if (visibility != VISIBLE) return false
        if (!isShown) return false
        if (windowVisibility != VISIBLE) return false
        val interactive = KeyguardStateMonitor.isInteractive(context)
        val locked = KeyguardStateMonitor.isKeyguardLocked(context)

        if (!interactive || locked) {
            requireNewFrameAfterUnlock = true
            return false
        }

        // 从息屏/不可交互/锁屏状态恢复到允许状态：同步建立当前帧水位门槛，拒绝锁屏期间残留旧帧
        ensureWatermarkOnInteractiveUnlocked()

        if (!DeviceDisplayManager.isVirtualDisplayActive()) return false
        if (targetDisplayId != DeviceDisplayManager.activeVirtualDisplayId()) return false
        if (DisplayInputCoordinator.getVirtualDisplayOwner(targetDisplayId) != targetSessionId) return false
        return true
    }

    internal fun startFrameLoop() {
        if (isFrameLoopRunning) return
        isFrameLoopRunning = true
        scheduleNextFrame()
    }

    internal fun stopFrameLoop() {
        isFrameLoopRunning = false
        frameHandler.removeCallbacks(frameRunnable)
        lastDrawnContentRect.setEmpty()
    }

    private fun scheduleNextFrame() {
        frameHandler.removeCallbacks(frameRunnable)
        if (shouldDrawFrames()) {
            invalidate()
            frameHandler.postDelayed(frameRunnable, FRAME_INTERVAL_MS)
        } else {
            isFrameLoopRunning = false
            invalidate()
        }
    }

    private fun updateLoopState() {
        if (previewActive && shouldDrawFrames()) {
            startFrameLoop()
        } else {
            stopFrameLoop()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerScreenLockReceiver()
        updateLoopState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (hasWindowBeenVisible) {
            requireNewFrameAfterUnlock = true
        }
        lastDrawnContentRect.setEmpty()
        stopFrameLoop()
        unregisterScreenLockReceiver()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lastDrawnContentRect.setEmpty()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown || this.visibility != VISIBLE) {
            lastDrawnContentRect.setEmpty()
            stopFrameLoop()
            invalidate()
        } else {
            updateLoopState()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            hasWindowBeenVisible = true
            updateLoopState()
        } else {
            lastDrawnContentRect.setEmpty()
            if (hasWindowBeenVisible) {
                requireNewFrameAfterUnlock = true
            }
            stopFrameLoop()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0 || !shouldDrawFrames()) {
            lastDrawnContentRect.setEmpty()
            return
        }
        destRect.set(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
        if (destRect.width() <= 0 || destRect.height() <= 0) {
            lastDrawnContentRect.setEmpty()
            return
        }
        val drawn = DeviceDisplayManager.drawVirtualFrame(
            targetDisplayId,
            targetSessionId,
            canvas,
            destRect,
            minRequiredFrameSequence,
            lastDrawnContentRect,
        )
        if (drawn && lastDrawnContentRect.width() > 0 && lastDrawnContentRect.height() > 0) {
            onFrameDrawn?.invoke()
        } else {
            lastDrawnContentRect.setEmpty()
        }
    }

    private fun registerScreenLockReceiver() {
        if (isScreenLockReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            context.registerReceiver(screenLockReceiver, filter)
            isScreenLockReceiverRegistered = true
        }.onFailure { e ->
            OmniLog.w(TAG, "注册锁屏广播接收器失败", e)
        }
    }

    private fun unregisterScreenLockReceiver() {
        if (!isScreenLockReceiverRegistered) return
        runCatching {
            context.unregisterReceiver(screenLockReceiver)
        }.onFailure { e ->
            OmniLog.w(TAG, "解绑锁屏广播接收器失败", e)
        }
        isScreenLockReceiverRegistered = false
    }
}
