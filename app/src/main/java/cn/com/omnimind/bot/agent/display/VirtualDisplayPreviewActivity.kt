package cn.com.omnimind.bot.agent.display

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.WindowManager
import android.widget.ImageView
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.KeyguardStateMonitor
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import cn.com.omnimind.bot.task.runtime.TaskRuntimeActionReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 虚拟显示屏实时小窗与无损接管预览页面。
 *
 * 核心交互与安全契约：
 * 1. 严格所有权校验：仅绑定并信任真实 primaryTask 的 sessionId 与 displayId，非匹配或已销毁显示立即拒绝并退出。
 * 2. 纯净画面绘制：通过共享的 VirtualDisplayFrameView 渲染画面，Activity 不持有 Bitmap 引用，不进行后台编码。
 * 3. 节流与状态自适应：最高 10fps 调度刷新，页面不可见、锁屏或虚拟屏销毁时立即停帧，系统默认画中画 (PiP) 模式下仅展示画布。
 * 4. 无损接管状态机：通过 VirtualDisplayTakeoverController 在接管前先发起官方 Turn 取消，等待同一 Turn 确认停止且无新 Turn 准入后，
 *    再原子获取双屏租约执行任务迁移；若超时则保留画面并提示“任务尚未停止”，绝不抢占输入或误停下一 Turn。
 */
class VirtualDisplayPreviewActivity : ComponentActivity() {

    companion object {
        private const val TAG = "VirtualDisplayPreview"

        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_DISPLAY_ID = "extra_display_id"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_TURN_ID = "extra_turn_id"
        const val EXTRA_STOP_TOKEN = "extra_stop_token"

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
    private val takeoverController by lazy { VirtualDisplayTakeoverController(this) }

    private var lastBarsLeft = 0
    private var lastBarsTop = 0
    private var lastBarsRight = 0
    private var lastBarsBottom = 0


    private lateinit var rootContainer: FrameLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var sessionTagView: TextView
    private lateinit var displayTagView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: VirtualDisplayFrameView
    private lateinit var previewEdgeLightView: ScreenTaskEdgeLightView
    private lateinit var bottomBar: LinearLayout
    private lateinit var stopButton: Button
    private lateinit var presentButton: Button
    private lateinit var closeButton: Button
    private val tempContentRect = RectF()
    override fun onCreate(savedInstanceState: Bundle?) {
        val dark = ScreenTaskTheme.isNight(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }

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
            previewView.bind(displayId, sessionId)
            updateEdgeLightBounds()
            updateEdgeLightState()
        }
        updatePipParams()
    }

    private fun setupViews(dark: Boolean) {
        val dp = resources.displayMetrics.density
        fun dpToPx(value: Float): Int = (value * dp + 0.5f).toInt()

        val scrimBg = if (dark) 0xD018181A.toInt() else 0xD0F5F5F7.toInt()
        val primaryText = ScreenTaskTheme.textPrimary(dark)
        val secondaryText = ScreenTaskTheme.textSecondary(dark)
        val accentColor = ScreenTaskTheme.brandPrimary(dark)
        val dangerColor = ScreenTaskTheme.errorColor()

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 全面屏底层：画面预览与贴合光晕（不被安全区 padding 裁剪）
        previewContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleControls() }
        }

        previewView = VirtualDisplayFrameView(this).apply {
            bind(this@VirtualDisplayPreviewActivity.displayId, this@VirtualDisplayPreviewActivity.sessionId)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            contentDescription = "虚拟屏画面"
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleControls() }
            onKeyguardChanged = {
                updatePipParams()
                updateEdgeLightState()
            }
            onFrameDrawn = {
                updateEdgeLightState()
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    updatePipParams()
                    updateEdgeLightBounds()
                    updateEdgeLightState()
                }
            }
        }
        previewContainer.addView(previewView)

        previewEdgeLightView = ScreenTaskEdgeLightView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewContainer.addView(previewEdgeLightView)
        rootContainer.addView(previewContainer)

        // 浮层顶栏：返回按钮、会话与屏幕标识、PiP 按钮与状态文本
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scrimBg)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48f)
            )
        }

        val backButton = FrameLayout(this).apply {
            val sz = dpToPx(48f)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            markAsAccessibilityButton(this, "返回")
            setOnClickListener { finish() }
            val icon = ImageView(this@VirtualDisplayPreviewActivity).apply {
                val iconSz = dpToPx(20f)
                layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER)
                setImageDrawable(IconDrawables.createChevron(primaryText, isUp = false))
                rotation = 90f
            }
            addView(icon)
        }
        headerRow.addView(backButton)

        sessionTagView = TextView(this).apply {
            text = "会话: $sessionId"
            setTextColor(primaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(4f)
            }
            layoutParams = lp
        }
        headerRow.addView(sessionTagView)

        displayTagView = TextView(this).apply {
            text = "虚拟屏 ($displayId)"
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(8f)
            }
            layoutParams = lp
        }
        headerRow.addView(displayTagView)

        val pipButton = FrameLayout(this).apply {
            val sz = dpToPx(48f)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            markAsAccessibilityButton(this, "画中画")
            setOnClickListener { handleEnterPip() }
            val icon = ImageView(this@VirtualDisplayPreviewActivity).apply {
                val iconSz = dpToPx(18f)
                layoutParams = FrameLayout.LayoutParams(iconSz, iconSz, Gravity.CENTER)
                setImageDrawable(IconDrawables.createPip(primaryText))
            }
            addView(icon)
        }
        headerRow.addView(pipButton)
        headerBar.addView(headerRow)

        statusTextView = TextView(this).apply {
            text = "任务执行中"
            setTextColor(secondaryText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dpToPx(16f), 0, dpToPx(16f), dpToPx(6f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerBar.addView(statusTextView)
        rootContainer.addView(headerBar)

        // 浮层底栏：操作按钮（停止任务、移交主屏、关闭预览）
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(scrimBg)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val btnMinHeight = dpToPx(48f)

        stopButton = Button(this).apply {
            text = "停止任务"
            contentDescription = "停止任务"
            setTextColor(dangerColor)
            minHeight = btnMinHeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            }
            layoutParams = params
            setOnClickListener { handleStopTask() }
        }
        bottomBar.addView(stopButton)

        presentButton = Button(this).apply {
            text = "移交主屏"
            contentDescription = "移交主屏"
            setTextColor(accentColor)
            minHeight = btnMinHeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            }
            layoutParams = params
            setOnClickListener { handlePresentToMainDisplay() }
        }
        bottomBar.addView(presentButton)

        closeButton = Button(this).apply {
            text = "关闭预览"
            contentDescription = "关闭预览"
            setTextColor(primaryText)
            minHeight = btnMinHeight
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dpToPx(4f), 0, dpToPx(4f), 0)
            }
            layoutParams = params
            setOnClickListener { finish() }
        }
        bottomBar.addView(closeButton)
        rootContainer.addView(bottomBar)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            lastBarsLeft = bars.left
            lastBarsTop = bars.top
            lastBarsRight = bars.right
            lastBarsBottom = bars.bottom
            if (isInPipModeCompat()) {
                headerBar.visibility = View.GONE
                statusTextView.visibility = View.GONE
                bottomBar.visibility = View.GONE
                rootContainer.setPadding(0, 0, 0, 0)
                previewEdgeLightView.stop()
            } else {
                headerBar.visibility = if (areControlsVisible) View.VISIBLE else View.GONE
                statusTextView.visibility = View.VISIBLE
                bottomBar.visibility = if (areControlsVisible) View.VISIBLE else View.GONE
                rootContainer.setPadding(0, 0, 0, 0)
                headerBar.setPadding(
                    bars.left + dpToPx(8f),
                    bars.top + dpToPx(4f),
                    bars.right + dpToPx(8f),
                    dpToPx(4f)
                )
                bottomBar.setPadding(
                    bars.left + dpToPx(8f),
                    dpToPx(8f),
                    bars.right + dpToPx(8f),
                    bars.bottom + dpToPx(8f)
                )
                updateEdgeLightBounds()
                updateEdgeLightState()
            }
            insets
        }

        setContentView(rootContainer)
        hideControlsHandler.postDelayed(hideControlsRunnable, 4000L)
    }

    private fun handleEnterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private var areControlsVisible: Boolean = true
    private val hideControlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false, animate = true) }

    private fun setControlsVisible(visible: Boolean, animate: Boolean = true) {
        if (isInPipModeCompat()) {
            headerBar.visibility = View.GONE
            bottomBar.visibility = View.GONE
            return
        }
        areControlsVisible = visible
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (visible) show(WindowInsetsCompat.Type.systemBars())
            else hide(WindowInsetsCompat.Type.systemBars())
        }
        if (visible) {
            headerBar.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            if (animate) {
                headerBar.animate().alpha(1f).setDuration(200).start()
                bottomBar.animate().alpha(1f).setDuration(200).start()
            } else {
                headerBar.alpha = 1f
                bottomBar.alpha = 1f
            }
            hideControlsHandler.postDelayed(hideControlsRunnable, 4000L)
        } else {
            if (animate) {
                headerBar.animate().alpha(0f).setDuration(200).withEndAction {
                    if (!areControlsVisible) {
                        headerBar.visibility = View.GONE
                    }
                }.start()
                bottomBar.animate().alpha(0f).setDuration(200).withEndAction {
                    if (!areControlsVisible) {
                        bottomBar.visibility = View.GONE
                    }
                }.start()
            } else {
                headerBar.alpha = 0f
                bottomBar.alpha = 0f
                headerBar.visibility = View.GONE
                bottomBar.visibility = View.GONE
            }
        }
    }

    private fun toggleControls() {
        setControlsVisible(!areControlsVisible, animate = true)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (areControlsVisible && !isInPipModeCompat()) {
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, 4000L)
        }
    }

    private fun isTaskRunningForCurrentTurn(): Boolean {
        val sid = sessionId.trim()
        if (sid.isEmpty()) return false
        val task = TaskRuntime.findActiveTaskBySession(sid) ?: return false
        if (!initialTurnId.isNullOrBlank() && task.turnId != initialTurnId) {
            return false
        }
        if (task.waitingStatus != null) {
            return false
        }
        if (task.screenDisplayId != null && task.screenDisplayId != displayId) {
            return false
        }
        return true
    }

    private fun updateEdgeLightState() {
        if (!::previewEdgeLightView.isInitialized || !::previewView.isInitialized) return
        if (isInPipModeCompat()) {
            previewEdgeLightView.stop()
            return
        }
        val isRunning = isTaskRunningForCurrentTurn()
        if (isRunning && previewView.shouldDrawFrames()) {
            updateEdgeLightBounds()
            previewEdgeLightView.start()
        } else {
            previewEdgeLightView.stop()
        }
    }

    private fun updateEdgeLightBounds() {
        if (!::previewView.isInitialized || !::previewEdgeLightView.isInitialized) return
        previewView.getContentRect(tempContentRect)
        previewEdgeLightView.setTargetRect(tempContentRect, cornerRadius = 0f)
    }

    fun getPreviewEdgeLight(): ScreenTaskEdgeLightView = previewEdgeLightView
    fun getPreviewView(): VirtualDisplayFrameView = previewView


    override fun onStart() {
        super.onStart()
        if (::previewView.isInitialized) {
            previewView.setPreviewActive(true)
        }
        updatePipParams()
        updateEdgeLightBounds()
        updateEdgeLightState()
    }

    override fun onResume() {
        super.onResume()
        if (::previewView.isInitialized) {
            previewView.setPreviewActive(true)
        }
        updatePipParams()
        ScreenTaskOverlayHost.setPreviewActivityForeground(!isInPipModeCompat())
        updateEdgeLightBounds()
        updateEdgeLightState()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipModeCompat() && ::previewView.isInitialized) {
            previewView.setPreviewActive(false)
        }
        ScreenTaskOverlayHost.setPreviewActivityForeground(false)
        if (::previewEdgeLightView.isInitialized) {
            previewEdgeLightView.stop()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipModeCompat() && ::previewView.isInitialized) {
            previewView.setPreviewActive(false)
        }
        ScreenTaskOverlayHost.setPreviewActivityForeground(false)
        if (::previewEdgeLightView.isInitialized) {
            previewEdgeLightView.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        takeoverJob?.cancel()
        takeoverJob = null
        if (::previewView.isInitialized) {
            previewView.setPreviewActive(false)
        }
        ScreenTaskOverlayHost.setPreviewActivityForeground(false)
        if (::previewEdgeLightView.isInitialized) {
            previewEdgeLightView.stop()
        }
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
            ScreenTaskOverlayHost.setPreviewActivityForeground(false)
            if (::previewEdgeLightView.isInitialized) {
                previewEdgeLightView.stop()
            }
            if (::previewView.isInitialized) {
                previewView.setPreviewActive(true)
            }
        } else {
            headerBar.visibility = View.VISIBLE
            statusTextView.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            rootContainer.setPadding(0, 0, 0, 0)
            ScreenTaskOverlayHost.setPreviewActivityForeground(true)
            ViewCompat.requestApplyInsets(rootContainer)
            updatePipParams()
            if (::previewView.isInitialized) {
                previewView.setPreviewActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            }
            updateEdgeLightBounds()
            updateEdgeLightState()
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
     * 移交主屏：委托 VirtualDisplayTakeoverController 执行安全取消、等待、新Turn防护与无损迁移。
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

            fun isBindingCurrent(): Boolean =
                coroutineContext.isActive &&
                    takeoverJob === currentJob &&
                    sessionId == targetSessionId &&
                    displayId == targetDisplayId &&
                    initialTaskId == targetTaskId &&
                    initialTurnId == targetTurnId &&
                    initialStopToken == targetStopToken

            presentButton.isEnabled = false
            stopButton.isEnabled = false

            try {
                val success = takeoverController.present(
                    sessionId = targetSessionId,
                    displayId = targetDisplayId,
                    taskId = targetTaskId,
                    turnId = targetTurnId,
                    stopToken = targetStopToken,
                    isBindingCurrent = ::isBindingCurrent,
                    onStatus = { msg ->
                        if (isBindingCurrent()) {
                            statusTextView.text = msg
                        }
                    },
                )
                if (success && isBindingCurrent()) {
                    Toast.makeText(this@VirtualDisplayPreviewActivity, "任务已成功移交至主屏", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isBindingCurrent()) {
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
}
