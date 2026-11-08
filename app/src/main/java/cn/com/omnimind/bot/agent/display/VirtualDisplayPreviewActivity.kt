package cn.com.omnimind.bot.agent.display

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
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


    private lateinit var rootContainer: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var sessionTagView: TextView
    private lateinit var displayTagView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: VirtualDisplayFrameView
    private lateinit var bottomBar: LinearLayout
    private lateinit var stopButton: Button
    private lateinit var presentButton: Button
    private lateinit var closeButton: Button

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
        }

        updatePipParams()
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

        previewView = VirtualDisplayFrameView(this).apply {
            bind(this@VirtualDisplayPreviewActivity.displayId, this@VirtualDisplayPreviewActivity.sessionId)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            onKeyguardChanged = {
                updatePipParams()
            }
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
        if (::previewView.isInitialized) {
            previewView.setPreviewActive(true)
        }
        updatePipParams()
    }

    override fun onResume() {
        super.onResume()
        if (::previewView.isInitialized) {
            previewView.setPreviewActive(true)
        }
        updatePipParams()
    }

    override fun onPause() {
        super.onPause()
        if (!isInPipModeCompat() && ::previewView.isInitialized) {
            previewView.setPreviewActive(false)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipModeCompat() && ::previewView.isInitialized) {
            previewView.setPreviewActive(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        takeoverJob?.cancel()
        takeoverJob = null
        if (::previewView.isInitialized) {
            previewView.setPreviewActive(false)
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
            if (::previewView.isInitialized) {
                previewView.setPreviewActive(true)
            }
        } else {
            headerBar.visibility = View.VISIBLE
            statusTextView.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE
            rootContainer.setPadding(lastBarsLeft, lastBarsTop, lastBarsRight, lastBarsBottom)
            ViewCompat.requestApplyInsets(rootContainer)
            updatePipParams()
            if (::previewView.isInitialized) {
                previewView.setPreviewActive(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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
