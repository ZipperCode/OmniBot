package cn.com.omnimind.bot.agent.display

import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.KeyguardStateMonitor
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.runtime.AgentRuntimeManager
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskRuntimeActionReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

/**
 * 虚拟显示屏实时小窗与无损接管预览页面。
 *
 * 核心交互与安全契约：
 * 1. 严格所有权校验：仅绑定并信任真实 primaryTask 的 sessionId 与 displayId，非匹配或已销毁显示立即拒绝并退出。
 * 2. 纯净画面绘制：通过 DeviceDisplayManager.drawVirtualFrame 委托底层安全绘制，UI 层不接触、不持有 Bitmap 引用，不进行后台编码。
 * 3. 节流与状态自适应：最高 10fps 调度刷新，页面不可见、锁屏或虚拟屏销毁时立即停帧，系统默认画中画 (PiP) 模式下仅展示画布。
 * 4. 无损接管状态机：接管前先发起官方 Turn 取消，等待同一 Turn 确认停止且无新 Turn 准入后，再原子获取双屏租约执行任务迁移；
 *    若超时则保留画面并提示“任务尚未停止”，绝不抢占输入或误停下一 Turn。
 */
class VirtualDisplayPreviewActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VirtualDisplayPreview"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_DISPLAY_ID = "extra_display_id"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TURN_ID = "extra_turn_id"
        const val EXTRA_STOP_TOKEN = "extra_stop_token"

        private const val MAX_FPS = 10
        private const val FRAME_INTERVAL_MS = 1000L / MAX_FPS // 100ms
        private const val TAKEOVER_WAIT_TIMEOUT_MS = 5000L

        fun createIntent(
            context: Context,
            sessionId: String,
            displayId: Int,
            taskId: String? = null,
            turnId: String? = null,
            stopToken: String? = null,
        ): Intent = Intent(context, VirtualDisplayPreviewActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("omnibot://display/preview/$sessionId/$displayId")
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_DISPLAY_ID, displayId)
            if (!taskId.isNullOrBlank()) putExtra(EXTRA_TASK_ID, taskId)
            if (!turnId.isNullOrBlank()) putExtra(EXTRA_TURN_ID, turnId)
            if (!stopToken.isNullOrBlank()) putExtra(EXTRA_STOP_TOKEN, stopToken)
        }
    }

    private var sessionId: String = ""
    private var displayId: Int = Display.DEFAULT_DISPLAY
    private var initialTaskId: String? = null
    private var initialTurnId: String? = null

    private var initialStopToken: String? = null
    private var takeoverJob: Job? = null
    private var lastBarsLeft = 0
    private var lastBarsTop = 0
    private var lastBarsRight = 0
    private var lastBarsBottom = 0
    private var isScreenLockReceiverRegistered = false
    @Volatile
    private var minRequiredFrameSequence: Long = -1L
    private var requireNewFrameAfterUnlock: Boolean = false

    private val screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 屏幕关闭/息屏：标记需要等待解锁后新帧、停帧、画布强制清黑、禁用画中画自动进入
                    requireNewFrameAfterUnlock = true
                    stopFrameLoop()
                    if (::previewView.isInitialized) {
                        previewView.invalidate()
                    }
                    updatePipParams()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 屏幕开启但仍可能处于安全锁屏：更新 PiP 状态，未解锁前保持黑屏且不恢复定时重绘
                    updatePipParams()
                    if (shouldDrawFrames()) {
                        startFrameLoop()
                    } else if (::previewView.isInitialized) {
                        previewView.invalidate()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // 用户完成解锁：在安全条件满足时，以当前最新帧序号作为水位（丢弃锁屏期间产生的残留旧帧）
                    if (requireNewFrameAfterUnlock) {
                        minRequiredFrameSequence = DeviceDisplayManager.virtualFrameSequence(displayId, sessionId) ?: -1L
                        requireNewFrameAfterUnlock = false
                    }
                    updatePipParams()
                    if (shouldDrawFrames()) {
                        startFrameLoop()
                    }
                }
            }
        }
    }


    private lateinit var rootContainer: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var sessionTagView: TextView
    private lateinit var displayTagView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: VirtualDisplayPreviewView
    private lateinit var bottomBar: LinearLayout
    private lateinit var stopButton: Button
    private lateinit var presentButton: Button
    private lateinit var closeButton: Button

    private val frameHandler = Handler(Looper.getMainLooper())
    private var isFrameLoopRunning = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isFrameLoopRunning) return
            if (shouldDrawFrames()) {
                previewView.invalidate()
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
            } else {
                isFrameLoopRunning = false
                requireNewFrameAfterUnlock = true
                previewView.invalidate()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        setTheme(
            if (dark) android.R.style.Theme_Material_NoActionBar
            else android.R.style.Theme_Material_Light_NoActionBar
        )
        super.onCreate(savedInstanceState)

        handlePreviewIntent(intent)
        if (isFinishing) return

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenLockReceiver, filter)
        isScreenLockReceiverRegistered = true

        setupViews(dark)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreviewIntent(intent)
    }

    private fun handlePreviewIntent(intent: Intent) {
        val newSessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty().trim()
        val newDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
        val newTaskId = intent.getStringExtra(EXTRA_TASK_ID)?.trim()?.ifEmpty { null }
        val newTurnId = intent.getStringExtra(EXTRA_TURN_ID)?.trim()?.ifEmpty { null }
        val newStopToken = intent.getStringExtra(EXTRA_STOP_TOKEN)?.trim()?.ifEmpty { null }

        if (newSessionId.isEmpty() || newDisplayId == Display.DEFAULT_DISPLAY) {
            OmniLog.w(TAG, "拒绝非法的预览启动请求：sessionId 为空或 displayId 为主屏")
            Toast.makeText(this, "无效的显示屏预览参数", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 验证 displayId 属于当前活跃虚拟屏且所有者匹配，不信任外部伪造参数
        val activeVd = DeviceDisplayManager.activeVirtualDisplayId()
        val vdOwner = DisplayInputCoordinator.getVirtualDisplayOwner(newDisplayId)
        if (newDisplayId != activeVd || vdOwner == null || vdOwner != newSessionId) {
            OmniLog.w(
                TAG,
                "拒绝未授权的虚拟屏访问：displayId=$newDisplayId, activeVd=$activeVd, vdOwner=$vdOwner"
            )
            Toast.makeText(this, "未授权的虚拟屏访问", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val isBindingChanged = newSessionId != sessionId ||
            newDisplayId != displayId ||
            newTaskId != initialTaskId ||
            newTurnId != initialTurnId ||
            newStopToken != initialStopToken

        if (isBindingChanged) {
            takeoverJob?.cancel()
            takeoverJob = null
        }

        val isDifferentSessionOrDisplay = newSessionId != sessionId || newDisplayId != displayId

        sessionId = newSessionId
        displayId = newDisplayId
        initialTaskId = newTaskId
        initialTurnId = newTurnId
        initialStopToken = newStopToken

        if (::sessionTagView.isInitialized) {
            sessionTagView.text = "会话: $sessionId"
        }
        if (::displayTagView.isInitialized) {
            displayTagView.text = "虚拟屏 ($displayId)"
        }
        if (::statusTextView.isInitialized) {
            statusTextView.text = "任务执行中"
        }
        if (::stopButton.isInitialized) {
            stopButton.isEnabled = true
        }
        if (::presentButton.isInitialized) {
            presentButton.isEnabled = true
        }
        if (::previewView.isInitialized) {
            previewView.targetDisplayId = displayId
            previewView.targetSessionId = sessionId
        }

        if (isDifferentSessionOrDisplay) {
            minRequiredFrameSequence = -1L
            requireNewFrameAfterUnlock = false
        }

        updatePipParams()
        if (shouldDrawFrames()) {
            startFrameLoop()
        } else {
            stopFrameLoop()
            if (::previewView.isInitialized) {
                previewView.invalidate()
            }
        }
    }

    private fun setupViews(dark: Boolean) {
        val dp = resources.displayMetrics.density
        fun dpToPx(value: Float): Int = (value * dp + 0.5f).toInt()

        val bgColor = if (dark) Color.rgb(18, 18, 18) else Color.rgb(245, 245, 247)
        val cardBg = if (dark) Color.rgb(28, 28, 30) else Color.WHITE
        val primaryText = if (dark) Color.rgb(240, 240, 240) else Color.rgb(30, 30, 30)
        val secondaryText = if (dark) Color.rgb(160, 160, 160) else Color.rgb(110, 110, 110)
        val accentColor = Color.rgb(0, 122, 255)
        val dangerColor = Color.rgb(255, 69, 58)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            lastBarsLeft = bars.left
            lastBarsTop = bars.top
            lastBarsRight = bars.right
            lastBarsBottom = bars.bottom
            if (isInPipModeCompat()) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }


        // 顶部信息栏：会话标识与屏幕标签
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16f), dpToPx(12f), dpToPx(16f), dpToPx(8f))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        sessionTagView = TextView(this).apply {
            text = "会话: $sessionId"
            setTextColor(secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerBar.addView(sessionTagView)

        displayTagView = TextView(this).apply {
            text = "虚拟屏 ($displayId)"
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerBar.addView(displayTagView)
        rootContainer.addView(headerBar)

        // 状态提示文本
        statusTextView = TextView(this).apply {
            text = "任务执行中"
            setTextColor(secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(16f), 0, dpToPx(16f), dpToPx(8f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootContainer.addView(statusTextView)

        // 中间画面预览容器
        previewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        previewView = VirtualDisplayPreviewView(this).apply {
            this.targetDisplayId = displayId
            this.targetSessionId = sessionId
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    updatePipParams()
                }
            }
        }
        previewContainer.addView(previewView)
        rootContainer.addView(previewContainer)

        // 底部操作按钮栏（符合 Android 48dp 最小触控目标）
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(cardBg)
            setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnMinHeight = dpToPx(48f)

        // 停止任务按钮
        stopButton = Button(this).apply {
            text = "停止任务"
            setTextColor(dangerColor)
            minHeight = btnMinHeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            layoutParams = params
            setOnClickListener { handleStopTask() }
        }
        bottomBar.addView(stopButton)

        // 移交主屏按钮
        presentButton = Button(this).apply {
            text = "移交主屏"
            setTextColor(accentColor)
            minHeight = btnMinHeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            layoutParams = params
            setOnClickListener { handlePresentToMainDisplay() }
        }
        bottomBar.addView(presentButton)

        // 关闭预览按钮
        closeButton = Button(this).apply {
            text = "关闭预览"
            setTextColor(primaryText)
            minHeight = btnMinHeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            layoutParams = params
            setOnClickListener {
                // 点击关闭预览不取消任务，不关闭会话，不销毁虚拟屏
                finish()
            }
        }
        bottomBar.addView(closeButton)

        rootContainer.addView(bottomBar)
        setContentView(rootContainer)
    }

    override fun onStart() {
        super.onStart()
        startFrameLoop()
        updatePipParams()
    }

    override fun onResume() {
        super.onResume()
        startFrameLoop()
        updatePipParams()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipModeCompat()) {
            stopFrameLoop()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipModeCompat()) {
            stopFrameLoop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        takeoverJob?.cancel()
        takeoverJob = null
        if (isScreenLockReceiverRegistered) {
            runCatching { unregisterReceiver(screenLockReceiver) }
            isScreenLockReceiverRegistered = false
        }
        stopFrameLoop()
    }
    override fun finish() {
        disableAutoEnterPip()
        takeoverJob?.cancel()
        takeoverJob = null
        super.finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
            if (canEnterPip()) {
                val params = buildPipParams(autoEnter = false)
                if (params != null) {
                    try {
                        enterPictureInPictureMode(params)
                    } catch (e: Exception) {
                        OmniLog.w(TAG, "进入画中画模式失败", e)
                    }
                }
            }
        }
    }


    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            headerBar.visibility = View.GONE
            statusTextView.visibility = View.GONE
            bottomBar.visibility = View.GONE
            rootContainer.setPadding(0, 0, 0, 0)
            startFrameLoop()
        } else {
            headerBar.visibility = View.VISIBLE
            statusTextView.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            rootContainer.setPadding(lastBarsLeft, lastBarsTop, lastBarsRight, lastBarsBottom)
            ViewCompat.requestApplyInsets(rootContainer)
            updatePipParams()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                startFrameLoop()
            } else {
                stopFrameLoop()
            }
        }
    }

    private fun canEnterPip(): Boolean {
        if (isFinishing || isDestroyed) return false
        if (isInPipModeCompat()) return false
        if (!KeyguardStateMonitor.isInteractive(this)) return false
        if (KeyguardStateMonitor.isKeyguardLocked(this)) return false
        if (!DeviceDisplayManager.isVirtualDisplayActive()) return false
        if (displayId != DeviceDisplayManager.activeVirtualDisplayId()) return false
        if (DisplayInputCoordinator.getVirtualDisplayOwner(displayId) != sessionId) return false
        return true
    }

    private fun calculatePipRational(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(9, 16)
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio < 0.419f -> Rational(419, 1000)
            ratio > 2.38f -> Rational(238, 100)
            else -> Rational(width, height)
        }
    }

    private fun buildPipParams(autoEnter: Boolean = false): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder()
        val previewRect = Rect()
        if (::previewView.isInitialized && previewView.getGlobalVisibleRect(previewRect) && previewRect.width() > 0 && previewRect.height() > 0) {
            builder.setSourceRectHint(previewRect)
            builder.setAspectRatio(calculatePipRational(previewRect.width(), previewRect.height()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val autoEnter = canEnterPip()
            val params = buildPipParams(autoEnter = autoEnter)
            if (params != null) {
                try {
                    setPictureInPictureParams(params)
                } catch (e: Exception) {
                    OmniLog.w(TAG, "更新 PictureInPictureParams 失败", e)
                }
            }
        }
    }

    private fun disableAutoEnterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false)
                    .build()
                setPictureInPictureParams(params)
            } catch (e: Exception) {
                OmniLog.w(TAG, "禁用自动画中画失败", e)
            }
        }
    }

    private fun isInPipModeCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            isInPictureInPictureMode
        } else {
            false
        }

    private fun startFrameLoop() {
        if (isFrameLoopRunning) return
        isFrameLoopRunning = true
        scheduleNextFrame()
    }

    private fun stopFrameLoop() {
        isFrameLoopRunning = false
        frameHandler.removeCallbacks(frameRunnable)
    }

    private fun scheduleNextFrame() {
        frameHandler.removeCallbacks(frameRunnable)
        if (shouldDrawFrames()) {
            previewView.invalidate()
            frameHandler.postDelayed(frameRunnable, FRAME_INTERVAL_MS)
        } else {
            isFrameLoopRunning = false
            requireNewFrameAfterUnlock = true
            previewView.invalidate()
        }
    }

    private fun shouldDrawFrames(): Boolean {
        val isStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val inPip = isInPipModeCompat()
        if (!isStarted && !inPip) return false
        if (!KeyguardStateMonitor.isInteractive(this)) return false
        if (KeyguardStateMonitor.isKeyguardLocked(this)) return false
        if (!DeviceDisplayManager.isVirtualDisplayActive()) return false
        if (displayId != DeviceDisplayManager.activeVirtualDisplayId()) return false
        if (DisplayInputCoordinator.getVirtualDisplayOwner(displayId) != sessionId) return false
        return true
    }

    /**
     * 停止任务：仅触发当前 ACP Turn 取消，释放执行租约，不销毁后台虚拟显示屏。
     */
    private fun handleStopTask() {
        val targetSessionId = sessionId
        val targetTaskId = initialTaskId
        val targetTurnId = initialTurnId
        val targetStopToken = initialStopToken

        lifecycleScope.launch {
            statusTextView.text = "正在停止任务..."
            stopButton.isEnabled = false

            val currentTask = TaskRuntime.findActiveTaskBySession(targetSessionId)
            if (currentTask != null && !targetTurnId.isNullOrBlank() && currentTask.turnId != targetTurnId) {
                statusTextView.text = "新任务轮次已开始，无法停止"
                stopButton.isEnabled = true
                return@launch
            }

            if (!targetTaskId.isNullOrBlank() && !targetStopToken.isNullOrBlank()) {
                val stopped = TaskRuntimeActionReceiver.executeStopTask(
                    context = this@VirtualDisplayPreviewActivity,
                    taskId = targetTaskId,
                    sessionId = targetSessionId,
                    turnId = targetTurnId,
                    stopToken = targetStopToken,
                )
                statusTextView.text = if (stopped) "已请求停止任务" else "任务已结束或凭据无效"
            } else {
                statusTextView.text = "任务凭据无效，无法停止"
            }
            stopButton.isEnabled = true
        }
    }

    /**
     * 移交主屏：停止该 Turn 后无损迁移到主屏操作。
     *
     * 迁移合同：
     * 1. 请求官方取消所属 session 的 prompt/Turn。
     * 2. 轮询等待同一 Turn 确认停止或失活。
     * 3. 校验期间无新 Turn 准入（若准入则判定 busy，中断迁移并不取消新 Turn）。
     * 4. 超时保留画面并提示“任务尚未停止”，不抢输入、不再取消别的 Turn。
     * 5. 获得双屏原子租约执行 moveRootTask，失败保留虚拟屏，成功则完成接管并退出预览。
     */
    private fun handlePresentToMainDisplay() {
        takeoverJob?.cancel()
        val targetSessionId = sessionId
        val targetDisplayId = displayId
        val targetTaskId = initialTaskId
        val targetTurnId = initialTurnId
        val targetStopToken = initialStopToken

        if (targetSessionId.isEmpty() || targetDisplayId == Display.DEFAULT_DISPLAY) {
            return
        }

        val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]

            fun isTakeoverValid(): Boolean =
                coroutineContext.isActive &&
                    takeoverJob === currentJob &&
                    sessionId == targetSessionId &&
                    displayId == targetDisplayId &&
                    initialTaskId == targetTaskId &&
                    initialTurnId == targetTurnId &&
                    initialStopToken == targetStopToken

            statusTextView.text = "正在停止任务并准备移交..."
            presentButton.isEnabled = false
            stopButton.isEnabled = false

            try {
                val currentTask = TaskRuntime.findActiveTaskBySession(targetSessionId)
                if (currentTask != null && !targetTurnId.isNullOrBlank() && currentTask.turnId != targetTurnId) {
                    if (isTakeoverValid()) {
                        statusTextView.text = "新任务轮次已开始，无法移交"
                    }
                    return@launch
                }

                // 复用 TaskRuntimeActionReceiver 官方取消通道仅对初始绑定的 Turn/Task/StopToken 发起取消
                if (!targetTaskId.isNullOrBlank() && !targetStopToken.isNullOrBlank()) {
                    TaskRuntimeActionReceiver.executeStopTask(
                        context = this@VirtualDisplayPreviewActivity,
                        taskId = targetTaskId,
                        sessionId = targetSessionId,
                        turnId = targetTurnId,
                        stopToken = targetStopToken,
                    )
                }
                val runtimeManager = AgentRuntimeManager.getInstance(this@VirtualDisplayPreviewActivity)
                val startWait = android.os.SystemClock.elapsedRealtime()
                var turnStopped = false
                var newTurnBusy = false

                while (android.os.SystemClock.elapsedRealtime() - startWait < TAKEOVER_WAIT_TIMEOUT_MS) {
                    if (!isTakeoverValid()) throw CancellationException("接管已被取代或取消")
                    val checkTask = TaskRuntime.findActiveTaskBySession(targetSessionId)
                    val hostActive = runtimeManager.isSessionTurnActive(targetSessionId)

                    if (checkTask != null && !checkTask.turnId.isNullOrBlank() && !targetTurnId.isNullOrBlank() && checkTask.turnId != targetTurnId) {
                        // 新 Turn 已准入，判定为忙碌
                        newTurnBusy = true
                        break
                    }

                    if ((checkTask == null || checkTask.turnId.isNullOrBlank()) && !hostActive) {
                        turnStopped = true
                        break
                    }
                    delay(100)
                    if (!isTakeoverValid()) throw CancellationException("接管已被取代或取消")
                }

                if (!isTakeoverValid()) throw CancellationException("接管已被取代或取消")
                if (newTurnBusy || runtimeManager.isSessionTurnActive(targetSessionId)) {
                    statusTextView.text = "新任务轮次已开始，无法移交"
                    return@launch
                }

                if (!turnStopped) {
                    // 超时保留画面并显示“任务尚未停止”，不抢输入、不再取消别的 Turn
                    statusTextView.text = "任务尚未停止"
                    return@launch
                }

                // Turn 停止且无新 Turn，获取双屏 lease 执行迁移
                statusTextView.text = "正在移交至主屏..."
                val leaseOwnerId = "takeover:$targetSessionId:${System.currentTimeMillis()}"

                if (!isTakeoverValid()) throw CancellationException("接管已被取代或取消")

                val rootTasks = try {
                    DeviceDisplayManager.getRootTasks(targetDisplayId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList()
                }

                if (!isTakeoverValid()) throw CancellationException("接管已被取代或取消")
                if (rootTasks.isEmpty()) {
                    statusTextView.text = "虚拟屏上未检测到运行中的应用任务"
                    return@launch
                }

                if (rootTasks.size > 1) {
                    statusTextView.text = "虚拟屏存在多个根任务，移交目标歧义"
                    return@launch
                }

                val targetTask = rootTasks.first()

                val result = DeviceDisplayManager.presentToUser(
                    context = this@VirtualDisplayPreviewActivity,
                    packageName = targetTask.packageName,
                    taskId = targetTask.taskId,
                    sessionOwnerId = targetSessionId,
                    leaseOwnerId = leaseOwnerId,
                    canPresent = {
                        isTakeoverValid() && !runtimeManager.isSessionTurnActive(targetSessionId)
                    },
                )

                if (!isTakeoverValid()) throw CancellationException("接管已被取代或取消")
                if (result.success) {
                    statusTextView.text = "已成功移交至主屏"
                    Toast.makeText(this@VirtualDisplayPreviewActivity, "任务已成功移交至主屏", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    val failMsg = if (result.message == "display_busy" || result.diagnostics["code"] == "display_busy") {
                        "新任务轮次已开始，无法移交"
                    } else {
                        "移交主屏失败: ${result.message}"
                    }
                    statusTextView.text = failMsg
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isTakeoverValid()) {
                    OmniLog.w(TAG, "移交主屏失败", e)
                    statusTextView.text = "移交主屏失败: ${e.message}"
                }
            } finally {
                val canResetUi = currentJob != null &&
                    !currentJob.isCancelled &&
                    takeoverJob === currentJob &&
                    !isFinishing &&
                    !isDestroyed &&
                    sessionId == targetSessionId &&
                    displayId == targetDisplayId &&
                    initialTaskId == targetTaskId &&
                    initialTurnId == targetTurnId &&
                    initialStopToken == targetStopToken
                if (canResetUi) {
                    presentButton.isEnabled = true
                    stopButton.isEnabled = true
                }
                if (takeoverJob === currentJob) {
                    takeoverJob = null
                }
            }
        }
        takeoverJob = job
        job.start()
    }

    /**
     * 虚拟屏画面渲染画布组件。
     */
    inner class VirtualDisplayPreviewView(context: Context) : View(context) {
        var targetDisplayId: Int = Display.DEFAULT_DISPLAY
        var targetSessionId: String = ""

        private val destRect = Rect()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.BLACK)
            if (targetDisplayId == Display.DEFAULT_DISPLAY || targetSessionId.isEmpty()) return
            if (!shouldDrawFrames()) return
            destRect.set(0, 0, width, height)
            DeviceDisplayManager.drawVirtualFrame(
                targetDisplayId,
                targetSessionId,
                canvas,
                destRect,
                minRequiredFrameSequence,
            )
        }
    }
}
