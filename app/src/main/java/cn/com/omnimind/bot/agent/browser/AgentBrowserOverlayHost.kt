package cn.com.omnimind.bot.agent.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.LiveAgentBrowserSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * System-level browser surface for the live Agent browser session.
 *
 * Two presentation states, both hosted in `TYPE_APPLICATION_OVERLAY` windows:
 * - a draggable globe ball;
 * - an expanded full-screen browser page (address bar, page view, tool panels).
 *
 * Invariants:
 * - The WebView instance stays owned by [LiveAgentBrowserSessionManager]; this
 *   host only re-parents it into its own container, exactly like a platform
 *   view would, so the Agent and the user share one session.
 * - Every command and snapshot is addressed by the host's own `workspaceId`
 *   through the workspace-scoped manager API. The legacy current-session API is
 *   never used, so a stale "current" session from another conversation can
 *   never receive this host's input.
 * - Minimising keeps the window attached with a deceptive measure pass, so the
 *   live page keeps its real viewport instead of relayouting into 1x1.
 */
object AgentBrowserOverlayHost {
    private const val TAG = "AgentBrowserOverlayHost"

    private const val ACCENT = 0xFF2C7FEB.toInt()
    private const val ACCENT_SOFT = 0xFFF2F6FC.toInt()
    private const val SURFACE = 0xFFFFFFFF.toInt()
    private const val SURFACE_MUTED = 0xFFF8FBFF.toInt()
    private const val PAGE_BACKDROP = 0xFFF4F7FB.toInt()
    private const val TEXT_PRIMARY = 0xFF1B2A41.toInt()
    private const val TEXT_MUTED = 0xFF617390.toInt()
    private const val DANGER = 0xFFB42318.toInt()
    private const val DANGER_SOFT = 0xFFFFF1F3.toInt()
    private const val WARNING = 0xFF92400E.toInt()
    private const val WARNING_SOFT = 0xFFFFF7ED.toInt()
    private const val BALL_SIZE_DP = 56
    private const val BALL_MARGIN_DP = 12
    private const val TOUCH_SLOP_DP = 6

    private const val PANEL_TOOLS = "tools"
    private const val PANEL_TABS = "tabs"
    private const val PANEL_BOOKMARKS = "bookmarks"
    private const val PANEL_HISTORY = "history"
    private const val PANEL_DOWNLOADS = "downloads"
    private const val PANEL_USERSCRIPTS = "userscripts"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var hostContext: Context? = null

    private var ballView: GlobeBallView? = null
    private var ballAttached = false

    private var browserRoot: MinimizedPreservingLayout? = null
    private var browserParams: WindowManager.LayoutParams? = null
    private var browserAttached = false
    private var browserColumn: LinearLayout? = null
    private var webViewContainer: FrameLayout? = null
    private var panelHost: FrameLayout? = null
    private var activePanel: View? = null
    private var activePanelTag: String? = null

    private var addressField: EditText? = null
    private var titleView: TextView? = null
    private var progressBar: View? = null
    private var bannerBox: LinearLayout? = null
    private var promptBox: LinearLayout? = null
    private var tabLabel: TextView? = null
    private var backButton: TextView? = null
    private var forwardButton: TextView? = null
    private var webViewHint: TextView? = null

    private var boundWorkspaceId: String? = null
    private var expanded = false
    private var lastPromptRequestId: String? = null
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null

    /**
     * Bumped on every binding switch and full teardown so long-running async
     * work (session close, dispatched commands) can detect it went stale and
     * must not act on a different workspace's UI.
     */
    private var uiGeneration = 0

    /**
     * Last observed `available` flag for the bound workspace. Only a real
     * true-then-false edge means the last tab was closed; `false` snapshots
     * without a previously seen `true` (initial acquisition, in-flight tool
     * operations) must never collapse the surface.
     */
    private var lastAvailableSeen = false

    private var savedFocusView: View? = null
    private var savedFocusWasEditing = false

    private val snapshotListener: (Map<String, Any?>) -> Unit = { snapshot ->
        mainHandler.post { applySnapshot(snapshot) }
    }

    // ------------------------------------------------------------ public API

    fun isOverlayPermitted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { OmniLog.e(TAG, "requestOverlayPermission failed: ${it.message}") }
    }

    fun isSessionAttached(workspaceId: String): Boolean = boundWorkspaceId == workspaceId

    fun isExpanded(): Boolean = expanded

    /**
     * Binds this host to a live session. Called from the session owner, so the
     * ball appears exactly when a real browser session exists.
     */
    fun onSessionAvailable(context: Context, workspaceId: String) {
        if (workspaceId.isBlank()) return
        mainHandler.post {
            if (!isOverlayPermitted(context)) {
                OmniLog.w(TAG, "overlay permission missing; system browser ball not shown")
                return@post
            }
            bindLocked(context, workspaceId)
            // The ball appears via applySnapshot once the workspace reports a
            // live tab, never for a bare pre-tab acquisition.
            applySnapshot(LiveAgentBrowserSessionManager.snapshotFor(workspaceId))
        }
    }

    /**
     * Opens the system browser surface for an existing session.
     *
     * @return false when the workspace has no live session, so callers can tell
     * the user instead of showing an empty surface.
     */
    fun openForWorkspace(
        context: Context,
        workspaceId: String,
        expandImmediately: Boolean = false
    ): Boolean {
        if (workspaceId.isBlank()) return false
        if (!LiveAgentBrowserSessionManager.hasSession(workspaceId)) {
            return false
        }
        if (!isOverlayPermitted(context)) {
            return false
        }
        mainHandler.post {
            bindLocked(context, workspaceId)
            if (expandImmediately) {
                expandInternal()
            } else {
                // Non-expanded opens surface the ball only when the snapshot
                // shows a live tab (applySnapshot -> ensureBall).
                applySnapshot(LiveAgentBrowserSessionManager.snapshotFor(workspaceId))
            }
        }
        return true
    }

    fun expand() {
        mainHandler.post { expandInternal() }
    }

    fun minimize() {
        mainHandler.post { minimizeInternal() }
    }

    /** Releases every overlay window. The browser session itself is untouched. */
    fun detach() {
        mainHandler.post {
            uiGeneration++
            LiveAgentBrowserSessionManager.removeSnapshotListener(snapshotListener)
            val workspaceId = boundWorkspaceId
            if (workspaceId != null) {
                LiveAgentBrowserSessionManager.detachTabFrom(workspaceId, webViewContainer)
            }
            removePanels()
            removeBrowserWindow()
            removeBall()
            boundWorkspaceId = null
            expanded = false
            lastAvailableSeen = false
            savedFocusView = null
            savedFocusWasEditing = false
        }
    }

    // -------------------------------------------------------------- binding

    private fun bindLocked(context: Context, workspaceId: String) {
        hostContext = context.applicationContext
        if (boundWorkspaceId == workspaceId) {
            return
        }
        val previous = boundWorkspaceId
        uiGeneration++
        // A panel built for the previous conversation must never linger.
        removePanels()
        if (previous != null && expanded) {
            // Rebinding to another conversation must not leave the old WebView
            // parented into this host's container.
            LiveAgentBrowserSessionManager.detachTabFrom(previous, webViewContainer)
        }
        boundWorkspaceId = workspaceId
        lastAvailableSeen = false
        lastPromptRequestId = null
        savedFocusView = null
        savedFocusWasEditing = false
        LiveAgentBrowserSessionManager.removeSnapshotListener(snapshotListener)
        LiveAgentBrowserSessionManager.addSnapshotListener(snapshotListener)
        if (expanded) {
            // While expanded, hand the surface to the new workspace or fold
            // coherently when it has nothing to show.
            val container = webViewContainer
            val attached = container != null &&
                LiveAgentBrowserSessionManager.attachTabTo(workspaceId, container, context)
            webViewHint?.visibility = if (attached) View.GONE else View.VISIBLE
            if (!attached) {
                minimizeInternal()
            }
        }
    }

    // ---------------------------------------------------------------- ball

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureBall() {
        if (ballAttached || expanded) {
            return
        }
        val context = hostContext ?: return
        val manager = manager(context) ?: return
        val size = dp(context, BALL_SIZE_DP)
        val margin = dp(context, BALL_MARGIN_DP)
        val view = GlobeBallView(context).apply {
            contentDescription = "浏览器悬浮球"
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(size, size)
        }
        val metrics = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size - margin).coerceAtLeast(margin)
            y = (metrics.heightPixels / 3).coerceAtLeast(margin)
        }
        // Accessibility activation goes through performClick(), so the real
        // action must live in an OnClickListener; the touch listener below
        // only adds dragging and forwards taps to it.
        view.setOnClickListener { expandInternal() }
        attachBallTouches(context, view, params)
        runCatching { manager.addView(view, params) }
            .onSuccess {
                ballView = view
                ballAttached = true
            }
            .onFailure { OmniLog.e(TAG, "addView(ball) failed: ${it.message}") }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachBallTouches(
        context: Context,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val slop = dp(context, TOUCH_SLOP_DP)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging &&
                        (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        val metrics = context.resources.displayMetrics
                        params.x = (startX + dx.toInt())
                            .coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt())
                            .coerceIn(0, (metrics.heightPixels - params.height).coerceAtLeast(0))
                        runCatching { windowManager?.updateViewLayout(touched, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapBallToEdge(context, touched, params)
                    } else {
                        // performClick triggers the OnClickListener above,
                        // which expands; calling expand twice is not needed.
                        touched.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBallToEdge(
        context: Context,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val manager = windowManager ?: return
        val metrics = context.resources.displayMetrics
        val margin = dp(context, BALL_MARGIN_DP)
        params.x = if (params.x + params.width / 2 < metrics.widthPixels / 2) {
            margin
        } else {
            (metrics.widthPixels - params.width - margin).coerceAtLeast(margin)
        }
        params.y = params.y.coerceIn(
            margin,
            (metrics.heightPixels - params.height - margin).coerceAtLeast(margin)
        )
        runCatching { manager.updateViewLayout(view, params) }
    }

    private fun removeBall() {
        val view = ballView
        if (view != null && ballAttached) {
            runCatching { windowManager?.removeView(view) }
        }
        ballView = null
        ballAttached = false
    }

    // ------------------------------------------------------------- browser

    private fun expandInternal() {
        val context = hostContext ?: return
        val workspaceId = boundWorkspaceId ?: return
        if (expanded) {
            return
        }
        ensureBrowserWindow(context)
        val root = browserRoot ?: return
        val container = webViewContainer ?: return
        root.setMinimized(false)
        val manager = windowManager ?: return
        val params = browserParams ?: return
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.gravity = Gravity.TOP or Gravity.START
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        runCatching { manager.updateViewLayout(root, params) }

        expanded = true
        removeBall()
        val attached = LiveAgentBrowserSessionManager.attachTabTo(
            workspaceId = workspaceId,
            container = container,
            hostContext = context
        )
        webViewHint?.visibility = if (attached) View.GONE else View.VISIBLE
        if (attached) {
            restoreFocusAfterExpand(root)
        }
        applySnapshot(LiveAgentBrowserSessionManager.snapshotFor(workspaceId))
    }

    /**
     * Reopening must not steal window focus globally: only restore a saved
     * editing field (keyboard follows the restored focus) or focus the root
     * when nothing is focused at all, so key events still have a target.
     */
    private fun restoreFocusAfterExpand(root: ViewGroup) {
        val saved = savedFocusView
        if (savedFocusWasEditing &&
            saved is EditText &&
            saved.windowToken != null &&
            saved.isAttachedToWindow
        ) {
            saved.requestFocus()
            return
        }
        if (root.findFocus() == null) {
            // Plain requestFocus honors FOCUS_AFTER_DESCENDANTS and lands on
            // the first focusable-in-touch-mode descendant: the address
            // EditText on a fresh open. That hidden focus made applySnapshot
            // skip the URL sync (blank address) and let the next BACK vanish
            // into clearFocus instead of navigating or minimizing. Focus the
            // root itself by briefly blocking descendant focus.
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.requestFocus()
            root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
    }

    /**
     * Moves focus off a text field onto the window root. A bare clearFocus
     * lets the touch-mode default-focus search run, which hands focus back
     * to the first focusable-in-touch-mode descendant — the address field
     * itself. That kept the field focused after IME commits, trapped every
     * later BACK in the keyboard-drop branch, and blocked URL sync on tab
     * switches. Blocking descendants while clearing parks focus on the
     * root, so the field holds focus only while truly editing.
     */
    private fun clearFieldFocus(field: View) {
        val root = browserRoot
        if (root == null) {
            field.clearFocus()
            return
        }
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        field.clearFocus()
        root.requestFocus()
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun minimizeInternal() {
        val context = hostContext ?: return
        if (!expanded) {
            ensureBall()
            return
        }
        expanded = false
        removePanels()
        val root = browserRoot ?: return
        val focused = root.findFocus()
        savedFocusView = focused
        savedFocusWasEditing = focused is EditText
        hideKeyboard()
        val manager = windowManager ?: return
        val params = browserParams ?: return
        // Keep the window attached at 1x1 but measure children against the
        // last actual viewport (not the total display), so the live page
        // keeps exactly the geometry the user saw instead of relayouting.
        val metrics = context.resources.displayMetrics
        val viewportWidth = root.width.takeIf { it > 1 } ?: metrics.widthPixels
        val viewportHeight = root.height.takeIf { it > 1 } ?: metrics.heightPixels
        root.setMinimized(true, viewportWidth, viewportHeight)
        params.width = 1
        params.height = 1
        params.gravity = Gravity.TOP or Gravity.START
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        runCatching { manager.updateViewLayout(root, params) }
        ensureBall()
    }

    /**
     * Closes every real tab of this workspace, and only then releases the
     * overlay. The workspace id and UI generation are captured up front, so a
     * rebind or manual detach in between can never tear down a newer surface,
     * and a failed close leaves the host usable with a visible error.
     */
    private fun closeBrowserSession() {
        val workspaceId = boundWorkspaceId ?: return
        val generation = uiGeneration
        scope.launch {
            var failures = 0
            val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
            val tabs = snapshot["tabs"] as? List<*>
            tabs?.forEach { item ->
                val tabId = (item as? Map<*, *>)?.get("tabId")
                    ?.toString()?.toIntOrNull()
                if (tabId != null) {
                    try {
                        LiveAgentBrowserSessionManager.handleCall(
                            workspaceId = workspaceId,
                            method = "closeTab",
                            arguments = mapOf("tabId" to tabId)
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failures++
                        OmniLog.e(TAG, "closeTab failed: ${error.message}")
                    }
                }
            }
            if (uiGeneration != generation || boundWorkspaceId != workspaceId) {
                return@launch
            }
            if (failures > 0) {
                showUserError("关闭会话未完成，请重试或稍后再关")
                applySnapshot(LiveAgentBrowserSessionManager.snapshotFor(workspaceId))
            } else {
                detach()
            }
        }
    }

    private fun ensureBrowserWindow(context: Context) {
        if (browserAttached) {
            return
        }
        val manager = manager(context) ?: return
        val root = buildBrowserRoot(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        runCatching { manager.addView(root, params) }
            .onSuccess {
                browserRoot = root
                browserParams = params
                browserAttached = true
                root.post {
                    if (browserRoot === root && root.isAttachedToWindow) {
                        registerBackInvokedCallback(root)
                    }
                }
            }
            .onFailure { OmniLog.e(TAG, "addView(browser) failed: ${it.message}") }
    }

    private fun removeBrowserWindow() {
        unregisterBackInvokedCallback()
        val root = browserRoot
        if (root != null && browserAttached) {
            runCatching { windowManager?.removeView(root) }
        }
        browserRoot = null
        browserParams = null
        browserColumn = null
        webViewContainer = null
        panelHost = null
        activePanel = null
        activePanelTag = null
        addressField = null
        titleView = null
        progressBar = null
        bannerBox = null
        promptBox = null
        tabLabel = null
        backButton = null
        forwardButton = null
        webViewHint = null
        browserAttached = false
        lastPromptRequestId = null
    }

    /**
     * Android 13+ predictive back: the app opts in via the manifest, so a
     * back press on this overlay window is delivered through the window's
     * OnBackInvokedDispatcher, not as KEYCODE_BACK. The root's key
     * arbitration stays as the pre-T / fallback path; once a callback is
     * registered the system no longer routes back presses to the window as
     * key events, so the two paths cannot double-fire.
     */
    private fun registerBackInvokedCallback(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        unregisterBackInvokedCallback()
        val dispatcher = runCatching { root.findOnBackInvokedDispatcher() }
            .getOrNull() ?: return
        val callback = OnBackInvokedCallback { handleSystemBack() }
        runCatching {
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }.onSuccess {
            backInvokedDispatcher = dispatcher
            backInvokedCallback = callback
        }
    }

    private fun unregisterBackInvokedCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        if (dispatcher != null && callback != null) {
            runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        backInvokedDispatcher = null
        backInvokedCallback = null
    }
    private fun buildBrowserRoot(context: Context): MinimizedPreservingLayout {
        val root = MinimizedPreservingLayout(context).apply {
            setBackgroundColor(SURFACE)

            isFocusableInTouchMode = true
            // BACK is arbitrated at the window root (see
            // MinimizedPreservingLayout.dispatchKeyEvent) so no descendant —
            // WebView, address field, panel — can swallow the press before
            // the shared panel -> keyboard -> history -> minimize chain runs.
            onSystemBack = { handleSystemBack() }
            // System-bar / cutout / IME safe area: pad the root so the
            // address bar clears the status bar and the footer clears the
            // navigation bar and keyboard.
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    val ime = insets.getInsets(WindowInsets.Type.ime())
                    view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                    WindowInsets.CONSUMED
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                    insets.consumeSystemWindowInsets()
                }
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(buildChrome(context))
        column.addView(progressRow(context))
        column.addView(buildBannerBox(context))
        column.addView(buildPromptBox(context))

        val container = FrameLayout(context).apply {
            setBackgroundColor(PAGE_BACKDROP)
        }
        val pageHost = FrameLayout(context)
        pageHost.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webViewHint = TextView(context).apply {
            text = "页面视图正显示在应用内，关闭应用内视图后此处会自动接管"
            textSize = 13f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 24))
            visibility = View.GONE
        }
        pageHost.addView(
            webViewHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                webViewHint?.visibility = View.GONE
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {
                if (container.childCount == 0 && expanded) {
                    webViewHint?.visibility = View.VISIBLE
                }
            }
        })
        column.addView(
            pageHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        column.addView(buildFooter(context))
        webViewContainer = container
        browserColumn = column

        val panelHost = FrameLayout(context)
        panelHost.visibility = View.GONE
        this.panelHost = panelHost

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            panelHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    private fun buildChrome(context: Context): LinearLayout {
        val chrome = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }
        // Row 1 keeps the address/title column dominant: on a 411dp phone the
        // three 48dp affordances leave well over 200dp of address width.
        val addressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4), dp(context, 4), dp(context, 4), 0)
        }
        addressRow.addView(
            buildAddressColumn(context),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        addressRow.addView(iconButton(context, "\u22EE", "工具菜单") { toggleToolsPanel() })
        addressRow.addView(iconButton(context, "\u2014", "最小化为悬浮球") { minimize() })
        addressRow.addView(iconButton(context, "\u2716", "关闭浏览器会话") { closeBrowserSession() })
        // Row 2 carries the page actions; navigation pairs stay grouped.
        val navRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 4), 0, dp(context, 4), dp(context, 4))
        }
        backButton = iconButton(context, "\u25C0", "后退") { dispatch("goBack") }
        forwardButton = iconButton(context, "\u25B6", "前进") { dispatch("goForward") }
        navRow.addView(backButton)
        navRow.addView(forwardButton)
        navRow.addView(iconButton(context, "\u27F3", "刷新") { dispatch("reload") })
        navRow.addView(iconButton(context, "\u2715", "停止加载") { dispatch("stopLoading") })
        chrome.addView(addressRow)
        chrome.addView(navRow)
        return chrome
    }

    private fun buildAddressColumn(context: Context): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
        }
        addressField = EditText(context).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
            hint = "输入网址"
            contentDescription = "地址栏"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, dp(context, 4), 0, dp(context, 4))
            setOnEditorActionListener { field, actionId, _ ->
                val url = field.text?.toString()?.trim().orEmpty()
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE
                ) {
                    if (url.isNotEmpty()) {
                        dispatch("navigate", mapOf("url" to normalizeUrl(url)))
                    }
                    clearFieldFocus(field)
                    hideKeyboard()
                    true
                } else {
                    false
                }
            }
        }
        titleView = TextView(context).apply {
            textSize = 11f
            setTextColor(TEXT_MUTED)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(addressField)
        column.addView(titleView)
        return column
    }

    private fun progressRow(context: Context): View {
        val bar = View(context).apply {
            setBackgroundColor(ACCENT)
            visibility = View.GONE
            // Without explicit LayoutParams the plain View measures to the
            // full remaining column height; 2dp is the intended indicator.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 2)
            )
        }
        progressBar = bar
        return bar
    }

    private fun buildBannerBox(context: Context): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        bannerBox = box
        return box
    }

    private fun buildPromptBox(context: Context): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        promptBox = box
        return box
    }

    private fun buildFooter(context: Context): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
            setBackgroundColor(SURFACE_MUTED)
        }
        tabLabel = TextView(context).apply {
            textSize = 12f
            setTextColor(TEXT_MUTED)
        }
        bar.addView(
            tabLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(textAction(context, "新标签") { dispatch("newTab") })
        bar.addView(textAction(context, "工具") { toggleToolsPanel() })
        return bar
    }

    // -------------------------------------------------------------- panels

    private fun removePanels() {
        activePanel?.let { panel ->
            runCatching { panelHost?.removeView(panel) }
        }
        activePanel = null
        activePanelTag = null
        panelHost?.visibility = View.GONE
    }

    private fun showPanel(panel: View, tag: String) {
        val host = panelHost ?: return
        removePanels()
        val scroller = ScrollView(host.context).apply {
            setBackgroundColor(SURFACE)
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        activePanel = scroller
        activePanelTag = tag
        host.addView(
            scroller,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM
            ).apply {
                height = (host.context.resources.displayMetrics.heightPixels * 0.6f).toInt()
            }
        )
        host.visibility = View.VISIBLE
    }

    private fun toggleToolsPanel() {
        if (activePanelTag == PANEL_TOOLS) {
            removePanels()
        } else {
            showToolsMenu()
        }
    }

    private fun showToolsMenu() {
        val context = panelHost?.context ?: return
        if (boundWorkspaceId == null) return
        val panel = panelScaffold(context, "工具")
        panel.addView(textAction(context, "标签页") { showTabPanel() })
        panel.addView(divider(context))
        panel.addView(textAction(context, "桌面版 / 手机版切换") {
            dispatch("toggleDesktopMode")
            removePanels()
        })
        panel.addView(divider(context))
        panel.addView(textAction(context, "书签") { showBookmarksPanel() })
        panel.addView(divider(context))
        panel.addView(textAction(context, "历史记录") { showHistoryPanel() })
        panel.addView(divider(context))
        panel.addView(textAction(context, "下载") { showDownloadsPanel() })
        panel.addView(divider(context))
        panel.addView(textAction(context, "用户脚本") { showUserscriptsPanel() })
        showPanel(panel, PANEL_TOOLS)
    }

    private fun panelScaffold(
        context: Context,
        title: String
    ): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            setBackgroundColor(SURFACE)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = title
                textSize = 15f
                setTextColor(TEXT_PRIMARY)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(textAction(context, "关闭") { removePanels() })
        panel.addView(header)
        panel.addView(divider(context))
        return panel
    }

    private fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(0x11000000)
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1)
        )
    }

    private fun emptyRow(context: Context, message: String): TextView =
        TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(context, 16), 0, dp(context, 16))
        }

    private fun panelRow(
        context: Context,
        title: String,
        subtitle: String?,
        vararg actions: Pair<String, () -> Unit>
    ): LinearLayout {
        // Vertical layout plus wrapping action chips: a download task can
        // legally offer six actions, and they must never push the text out
        // or overflow the screen.
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(
            TextView(context).apply {
                text = title
                textSize = 13f
                setTextColor(TEXT_PRIMARY)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        if (!subtitle.isNullOrBlank()) {
            textColumn.addView(
                TextView(context).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(TEXT_MUTED)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )
        }
        row.addView(
            textColumn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        if (actions.isNotEmpty()) {
            val actionChips = WrapLayout(context)
            actions.forEach { (label, action) ->
                actionChips.addView(textAction(context, label, action))
            }
            row.addView(
                actionChips,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return row
    }

    private fun showTabPanel() {
        val context = panelHost?.context ?: return
        val workspaceId = boundWorkspaceId ?: return
        val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
        val panel = panelScaffold(context, "标签页")
        val tabs = snapshot["tabs"] as? List<*>
        val activeId = (snapshot["activeTabId"] as? Number)?.toInt()
        if (tabs.isNullOrEmpty()) {
            panel.addView(emptyRow(context, "还没有打开任何标签页"))
        } else {
            tabs.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                val tabId = (map["tabId"] as? Number)?.toInt() ?: return@forEach
                val title = map["title"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: map["url"]?.toString().orEmpty().ifBlank { "新标签" }
                val isActive = tabId == activeId
                panel.addView(
                    panelRow(
                        context,
                        if (isActive) "\u2713 $title" else title,
                        map["url"]?.toString(),
                        "切换" to {
                            dispatch("selectTab", mapOf("tabId" to tabId))
                            removePanels()
                        },
                        "关闭" to {
                            // The panel refreshes itself after closeTab
                            // finishes, not from the stale snapshot.
                            dispatch(
                                "closeTab",
                                mapOf("tabId" to tabId),
                                refreshPanel = PANEL_TABS
                            )
                        }
                    )
                )
            }
        }
        panel.addView(textAction(context, "新建标签页") {
            dispatch("newTab", refreshPanel = PANEL_TABS)
        })
        showPanel(panel, PANEL_TABS)
    }

    private fun showBookmarksPanel() {
        val context = panelHost?.context ?: return
        val workspaceId = boundWorkspaceId ?: return
        val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
        val panel = panelScaffold(context, "书签")
        val bookmarks = snapshot["bookmarks"] as? List<*>
        if (bookmarks.isNullOrEmpty()) {
            panel.addView(emptyRow(context, "还没有书签"))
        } else {
            bookmarks.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                val url = map["url"]?.toString().orEmpty()
                val title = map["title"]?.toString()?.takeIf { it.isNotBlank() } ?: url
                panel.addView(
                    panelRow(
                        context,
                        title,
                        url,
                        "打开" to {
                            dispatch("openHistoryEntry", mapOf("url" to url))
                            removePanels()
                        },
                        "删除" to {
                            dispatch(
                                "removeBookmark",
                                mapOf("url" to url),
                                refreshPanel = PANEL_BOOKMARKS
                            )
                        }
                    )
                )
            }
        }
        panel.addView(textAction(context, "收藏当前页") {
            dispatch("toggleBookmark", refreshPanel = PANEL_BOOKMARKS)
        })
        showPanel(panel, PANEL_BOOKMARKS)
    }

    private fun showHistoryPanel() {
        val context = panelHost?.context ?: return
        val workspaceId = boundWorkspaceId ?: return
        val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
        val panel = panelScaffold(context, "历史记录")
        val history = snapshot["history"] as? List<*>
        if (history.isNullOrEmpty()) {
            panel.addView(emptyRow(context, "还没有历史记录"))
        } else {
            history.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                val url = map["url"]?.toString().orEmpty()
                val title = map["title"]?.toString()?.takeIf { it.isNotBlank() } ?: url
                panel.addView(
                    panelRow(
                        context,
                        title,
                        url,
                        "打开" to {
                            dispatch("openHistoryEntry", mapOf("url" to url))
                            removePanels()
                        }
                    )
                )
            }
        }
        panel.addView(textAction(context, "清空历史") {
            dispatch("clearHistory", refreshPanel = PANEL_HISTORY)
        })
        showPanel(panel, PANEL_HISTORY)
    }

    private fun showDownloadsPanel() {
        val context = panelHost?.context ?: return
        val workspaceId = boundWorkspaceId ?: return
        val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
        val panel = panelScaffold(context, "下载")
        val downloads = snapshot["downloads"] as? List<*>
        if (downloads.isNullOrEmpty()) {
            panel.addView(emptyRow(context, "还没有下载任务"))
        } else {
            downloads.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                val taskId = map["id"]?.toString().orEmpty()
                if (taskId.isBlank()) return@forEach
                val fileName = map["fileName"]?.toString().orEmpty().ifBlank { taskId }
                val status = map["status"]?.toString().orEmpty()
                val progress = (map["progress"] as? Number)?.toDouble()
                val subtitle = buildString {
                    append(status)
                    if (progress != null) {
                        append("  ")
                        append((progress * 100).toInt())
                        append('%')
                    }
                }
                val actions = mutableListOf<Pair<String, () -> Unit>>()
                fun addDownloadAction(flag: String, label: String, method: String) {
                    if (map[flag] == true) {
                        actions += label to {
                            // Refresh comes from completion, not the stale
                            // snapshot captured when the panel was built.
                            dispatch(
                                method,
                                mapOf("taskId" to taskId),
                                refreshPanel = PANEL_DOWNLOADS
                            )
                        }
                    }
                }
                addDownloadAction("canPause", "暂停", "pauseDownload")
                addDownloadAction("canResume", "继续", "resumeDownload")
                addDownloadAction("canRetry", "重试", "retryDownload")
                addDownloadAction("canCancel", "取消", "cancelDownload")
                if (map["canOpenFile"] == true) {
                    actions += "打开文件" to {
                        dispatch("openDownloadedFile", mapOf("taskId" to taskId))
                    }
                }
                if (map["canOpenLocation"] == true) {
                    actions += "所在目录" to {
                        dispatch("openDownloadLocation", mapOf("taskId" to taskId))
                    }
                }
                if (map["canDelete"] == true) {
                    actions += "删除" to {
                        dispatch(
                            "deleteDownload",
                            mapOf("taskId" to taskId, "deleteFile" to false),
                            refreshPanel = PANEL_DOWNLOADS
                        )
                    }
                }
                panel.addView(panelRow(context, fileName, subtitle, *actions.toTypedArray()))
            }
        }
        showPanel(panel, PANEL_DOWNLOADS)
    }

    private fun showUserscriptsPanel() {
        val context = panelHost?.context ?: return
        val workspaceId = boundWorkspaceId ?: return
        val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
        val panel = panelScaffold(context, "用户脚本")
        val summary = snapshot["userscriptSummary"] as? Map<*, *>
        val pending = summary?.get("pendingInstall") as? Map<*, *>
        if (pending != null) {
            val name = pending["name"]?.toString().orEmpty().ifBlank { "未命名脚本" }
            panel.addView(
                panelRow(
                    context,
                    "待确认安装：$name",
                    pending["description"]?.toString(),
                    "安装" to {
                        dispatch(
                            "confirmUserscriptInstall",
                            refreshPanel = PANEL_USERSCRIPTS
                        )
                    },
                    "取消" to {
                        dispatch(
                            "cancelUserscriptInstall",
                            refreshPanel = PANEL_USERSCRIPTS
                        )
                    }
                )
            )
            panel.addView(divider(context))
        }
        val installed = summary?.get("installedScripts") as? List<*>
        if (installed.isNullOrEmpty()) {
            panel.addView(emptyRow(context, "还没有安装用户脚本"))
        } else {
            installed.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                val scriptId = (map["id"] as? Number)?.toLong() ?: return@forEach
                val name = map["name"]?.toString().orEmpty().ifBlank { "#$scriptId" }
                val enabled = map["enabled"] == true
                panel.addView(
                    panelRow(
                        context,
                        name,
                        "v${map["version"]?.toString().orEmpty()}",
                        (if (enabled) "停用" else "启用") to {
                            dispatch(
                                "setUserscriptEnabled",
                                mapOf("scriptId" to scriptId, "enabled" to !enabled),
                                refreshPanel = PANEL_USERSCRIPTS
                            )
                        },
                        "更新" to {
                            dispatch(
                                "checkUserscriptUpdate",
                                mapOf("scriptId" to scriptId),
                                refreshPanel = PANEL_USERSCRIPTS
                            )
                        },
                        "删除" to {
                            dispatch(
                                "deleteUserscript",
                                mapOf("scriptId" to scriptId),
                                refreshPanel = PANEL_USERSCRIPTS
                            )
                        }
                    )
                )
            }
        }
        val menuCommands = summary?.get("currentPageMenuCommands") as? List<*>
        if (!menuCommands.isNullOrEmpty()) {
            panel.addView(divider(context))
            panel.addView(
                TextView(context).apply {
                    text = "当前页面脚本菜单"
                    textSize = 12f
                    setTextColor(TEXT_MUTED)
                    setPadding(0, dp(context, 8), 0, dp(context, 4))
                }
            )
            menuCommands.forEach { item ->
                val map = item as? Map<*, *> ?: return@forEach
                val commandId = map["commandId"]?.toString().orEmpty()
                if (commandId.isBlank()) return@forEach
                panel.addView(
                    panelRow(
                        context,
                        map["title"]?.toString().orEmpty().ifBlank { commandId },
                        null,
                        "执行" to {
                            dispatch(
                                "invokeUserscriptMenuCommand",
                                mapOf("commandId" to commandId)
                            )
                            removePanels()
                        }
                    )
                )
            }
        }
        panel.addView(divider(context))
        panel.addView(buildUserscriptInstallSection(context))
        showPanel(panel, PANEL_USERSCRIPTS)
    }

    /** URL install plus a plain source editor feeding importUserscriptSource. */
    private fun buildUserscriptInstallSection(context: Context): LinearLayout {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        section.addView(
            TextView(context).apply {
                text = "安装用户脚本"
                textSize = 12f
                setTextColor(TEXT_MUTED)
                setPadding(0, 0, 0, dp(context, 4))
            }
        )
        val urlField = EditText(context).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            hint = "从 URL 安装 .user.js"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        val urlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlRow.addView(
            urlField,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        urlRow.addView(textAction(context, "安装") {
            val url = urlField.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                urlField.setText("")
                dispatch(
                    "installUserscriptFromUrl",
                    mapOf("url" to url),
                    refreshPanel = PANEL_USERSCRIPTS
                )
            }
        })
        section.addView(urlRow)
        section.addView(divider(context))
        val nameField = EditText(context).apply {
            setSingleLine(true)
            textSize = 13f
            setTextColor(TEXT_PRIMARY)
            hint = "脚本名称（可选）"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val sourceField = EditText(context).apply {
            textSize = 12f
            setTextColor(TEXT_PRIMARY)
            hint = "粘贴 .user.js 源码"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            setPadding(0, dp(context, 6), 0, dp(context, 6))
        }
        section.addView(
            nameField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        section.addView(
            sourceField,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        section.addView(textAction(context, "从源码导入") {
            val source = sourceField.text?.toString()?.trim().orEmpty()
            if (source.isNotEmpty()) {
                val name = nameField.text?.toString()?.trim().orEmpty()
                sourceField.setText("")
                nameField.setText("")
                dispatch(
                    "importUserscriptSource",
                    mapOf(
                        "source" to source,
                        "sourceName" to name.ifBlank { null }
                    ),
                    refreshPanel = PANEL_USERSCRIPTS
                )
            } else {
                showUserError("请先粘贴脚本源码")
            }
        })
        return section
    }

    // ------------------------------------------------------------ snapshot

    private fun applySnapshot(snapshot: Map<String, Any?>) {
        val workspaceId = boundWorkspaceId ?: return
        if (!LiveAgentBrowserSessionManager.hasSession(workspaceId)) {
            // Session entry itself is gone: drop every window, including a
            // ball-only state, instead of leaving a ghost globe behind.
            detach()
            return
        }
        val snapshotWorkspaceId = snapshot["workspaceId"]?.toString().orEmpty()
        if (snapshotWorkspaceId.isNotEmpty() && snapshotWorkspaceId != workspaceId) {
            return
        }
        if (snapshot["available"] != true) {
            // Only a real true-then-false edge means the last tab closed.
            // Bare `false` snapshots (session acquisition before any tab
            // exists, in-flight tool operations like get_text) must never
            // collapse an expanded surface or flash a ghost ball.
            if (lastAvailableSeen) {
                lastAvailableSeen = false
                detach()
            }
            return
        }
        lastAvailableSeen = true
        if (!expanded) {
            // Session exists with a live tab: the ball must be visible even
            // when the surface was never opened from this process yet.
            ensureBall()
        }
        val isLoading = snapshot["isLoading"] == true
        ballView?.setLoading(isLoading)
        progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
        val url = snapshot["currentUrl"]?.toString().orEmpty()
        titleView?.text = snapshot["title"]?.toString().orEmpty()
        addressField?.let { field ->
            if (!field.hasFocus() && field.text?.toString() != url) {
                field.setText(url)
            }
        }
        backButton?.alpha = if (snapshot["canGoBack"] == true) 1f else 0.35f
        forwardButton?.alpha = if (snapshot["canGoForward"] == true) 1f else 0.35f
        val tabs = snapshot["tabs"] as? List<*>
        val activeIndex = (snapshot["activeTabId"] as? Number)?.toInt()
        tabLabel?.text = if (tabs.isNullOrEmpty()) {
            "没有标签页"
        } else {
            val position = tabs.indexOfFirst {
                ((it as? Map<*, *>)?.get("tabId") as? Number)?.toInt() == activeIndex
            }
            val label = if (position >= 0) "${position + 1}/${tabs.size}" else "${tabs.size} 个标签"
            val activeTitle = tabs.firstOrNull {
                ((it as? Map<*, *>)?.get("tabId") as? Number)?.toInt() == activeIndex
            }?.let { (it as Map<*, *>)["title"]?.toString().orEmpty() }.orEmpty()
            if (activeTitle.isBlank()) label else "$label  $activeTitle"
        }
        renderBanners(snapshot)
        renderPrompt(snapshot)
    }

    private fun renderBanners(snapshot: Map<String, Any?>) {
        val box = bannerBox ?: return
        val context = box.context
        box.removeAllViews()
        var count = 0
        fun addBanner(
            message: String,
            color: Int,
            background: Int,
            actions: List<Pair<String, () -> Unit>> = emptyList()
        ) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                setBackgroundColor(background)
            }
            row.addView(
                TextView(context).apply {
                    text = message
                    textSize = 12f
                    setTextColor(color)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            actions.forEach { (label, action) -> row.addView(textAction(context, label, action)) }
            box.addView(row)
            count++
        }
        if (snapshot["hasSslError"] == true) {
            addBanner("此页面存在 SSL 错误，请谨慎操作", DANGER, DANGER_SOFT)
        }
        if (snapshot["riskChallengeDetected"] == true) {
            val kind = snapshot["riskChallengeKind"]?.toString().orEmpty()
            addBanner(
                "检测到风险验证${if (kind.isBlank()) "" else "（$kind）"}，请人工确认页面状态",
                WARNING,
                WARNING_SOFT
            )
        }
        val permission = snapshot["permissionPrompt"] as? Map<*, *>
        if (permission != null) {
            val requestId = permission["requestId"]?.toString().orEmpty()
            val kind = permission["kind"]?.toString().orEmpty()
            val origin = permission["origin"]?.toString().orEmpty()
            addBanner(
                "页面请求权限：$kind（$origin）",
                WARNING,
                WARNING_SOFT,
                listOf(
                    "允许" to { dispatch("grantPermission", mapOf("requestId" to requestId)) },
                    "拒绝" to { dispatch("denyPermission", mapOf("requestId" to requestId)) }
                )
            )
        }
        val externalOpen = snapshot["externalOpenPrompt"] as? Map<*, *>
        if (externalOpen != null) {
            val requestId = externalOpen["requestId"]?.toString().orEmpty()
            val target = externalOpen["target"]?.toString().orEmpty()
            addBanner(
                "请求在外部打开：$target",
                TEXT_MUTED,
                ACCENT_SOFT,
                listOf(
                    "打开" to {
                        dispatch("confirmExternalOpen", mapOf("requestId" to requestId))
                    },
                    "取消" to {
                        dispatch("cancelExternalOpen", mapOf("requestId" to requestId))
                    }
                )
            )
        }
        box.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    /**
     * JS dialogs block the page until they are resolved, so the native surface
     * must answer them; otherwise expanding here would freeze such pages.
     */
    private fun renderPrompt(snapshot: Map<String, Any?>) {
        val box = promptBox ?: return
        val dialog = snapshot["pendingDialog"] as? Map<*, *>
        if (dialog == null) {
            box.removeAllViews()
            box.visibility = View.GONE
            lastPromptRequestId = null
            return
        }
        val requestId = dialog["requestId"]?.toString().orEmpty()
        if (requestId == lastPromptRequestId) {
            return
        }
        lastPromptRequestId = requestId
        val context = box.context
        box.removeAllViews()
        val type = dialog["type"]?.toString().orEmpty()
        val message = dialog["message"]?.toString().orEmpty()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            setBackgroundColor(ACCENT_SOFT)
        }
        column.addView(
            TextView(context).apply {
                text = if (type == "alert") "页面提醒：$message" else "页面对话框：$message"
                textSize = 12f
                setTextColor(TEXT_PRIMARY)
            }
        )
        val input = if (type == "prompt") {
            EditText(context).apply {
                setSingleLine(true)
                textSize = 13f
                setText(dialog["defaultValue"]?.toString().orEmpty())
            }.also { column.addView(it) }
        } else {
            null
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        if (type != "alert") {
            actions.addView(textAction(context, "取消") {
                dispatch(
                    "resolveDialog",
                    mapOf("requestId" to requestId, "accept" to false)
                )
            })
        }
        actions.addView(textAction(context, "确定") {
            dispatch(
                "resolveDialog",
                mapOf(
                    "requestId" to requestId,
                    "accept" to true,
                    "promptValue" to input?.text?.toString().orEmpty()
                )
            )
        })
        column.addView(actions)
        box.addView(column)
        box.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------- helpers

    /**
     * Routes one command to this host's own workspace session. The workspace
     * is captured at call time; nothing after the await acts unless the host
     * is still bound to it, so a rebind can never route a result into a
     * different conversation's surface. [refreshPanel] re-opens that panel
     * from the fresh snapshot once the command completes.
     */
    private fun dispatch(
        method: String,
        arguments: Map<String, Any?> = emptyMap(),
        refreshPanel: String? = null
    ) {
        val workspaceId = boundWorkspaceId ?: return
        scope.launch {
            try {
                LiveAgentBrowserSessionManager.handleCall(
                    workspaceId = workspaceId,
                    method = method,
                    arguments = arguments
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OmniLog.e(TAG, "host call $method failed: ${error.message}")
                showUserError("操作未能完成，请重试")
            }
            if (boundWorkspaceId != workspaceId) {
                return@launch
            }
            applySnapshot(LiveAgentBrowserSessionManager.snapshotFor(workspaceId))
            if (refreshPanel != null && activePanelTag == refreshPanel && expanded) {
                reopenPanel(refreshPanel)
            }
        }
    }

    private fun reopenPanel(tag: String) {
        when (tag) {
            PANEL_TABS -> showTabPanel()
            PANEL_BOOKMARKS -> showBookmarksPanel()
            PANEL_HISTORY -> showHistoryPanel()
            PANEL_DOWNLOADS -> showDownloadsPanel()
            PANEL_USERSCRIPTS -> showUserscriptsPanel()
            PANEL_TOOLS -> showToolsMenu()
        }
    }

    /** Actionable, payload-free error surface; details stay in OmniLog. */
    private fun showUserError(message: String) {
        val context = hostContext ?: return
        mainHandler.post {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Back pressed anywhere on the surface (root, WebView, address field):
     * close an open panel, then drop the keyboard, then go back a page,
     * then minimize. Returns whether the event was consumed.
     */
    private fun handleSystemBack(): Boolean {
        if (activePanel != null) {
            removePanels()
            return true
        }
        val root = browserRoot
        val focused = root?.findFocus()
        if (focused is EditText) {
            clearFieldFocus(focused)
            hideKeyboard()
            return true
        }
        val workspaceId = boundWorkspaceId ?: return false
        val snapshot = LiveAgentBrowserSessionManager.snapshotFor(workspaceId)
        return if (snapshot["canGoBack"] == true) {
            dispatch("goBack")
            true
        } else {
            minimizeInternal()
            true
        }
    }

    private fun manager(context: Context): WindowManager? {
        windowManager = windowManager ?: runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }.getOrNull()
        return windowManager
    }

    private fun hideKeyboard() {
        val view = browserRoot ?: return
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager ?: return
        runCatching { imm.hideSoftInputFromWindow(view.windowToken, 0) }
    }

    private fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("omnibot://")
        ) {
            return trimmed
        }
        return "https://$trimmed"
    }

    private fun iconButton(
        context: Context,
        glyph: String,
        description: String,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = glyph
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(TEXT_PRIMARY)
        contentDescription = description
        val padding = dp(context, 10)
        setPadding(padding, dp(context, 6), padding, dp(context, 6))
        // 48dp minimum touch target on both axes for accessibility.
        minWidth = dp(context, 48)
        minHeight = dp(context, 48)
        setOnClickListener { onClick() }
    }

    private fun textAction(
        context: Context,
        label: String,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 12f
        setTextColor(ACCENT)
        setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6))
        minHeight = dp(context, 48)
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * Overlay window root that can collapse to 1x1 while still measuring children
 * against the real display, so a live page keeps its viewport when the surface
 * is minimised instead of relayouting into a single pixel.
 */
private class MinimizedPreservingLayout(context: Context) : FrameLayout(context) {
    private var minimized = false
    private var fakeWidth = 0
    private var fakeHeight = 0

    /**
     * Resolves KEYCODE_BACK for the whole window before the focused
     * descendant (WebView, address field, panel) can consume it. A root
     * OnKeyListener only fires while the root itself holds focus, and an
     * unhandled-key listener only fires on a delayed pass after the
     * hierarchy declined the event — both let a focused address field
     * swallow the press, so a second BACK at a no-history page never
     * minimized the surface. dispatchKeyEvent runs for every key the window
     * receives, before any routing to the focused child.
     */
    var onSystemBack: (() -> Boolean)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (onSystemBack != null && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // DOWN (with repeats) is absorbed so no descendant starts acting
            // on the press; the action runs exactly once on a real UP.
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                onSystemBack?.invoke()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun setMinimized(enabled: Boolean, width: Int = 0, height: Int = 0) {
        minimized = enabled
        fakeWidth = width
        fakeHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!minimized || fakeWidth <= 0 || fakeHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Children keep the page geometry the user was just looking at, while
        // the window itself reports its real (collapsed) size, so the surface
        // disappears instead of staying painted over the desktop.
        val widthSpec = MeasureSpec.makeMeasureSpec(fakeWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(fakeHeight, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            getChildAt(index).measure(widthSpec, heightSpec)
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!minimized || fakeWidth <= 0 || fakeHeight <= 0) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        // Lay children out at the preserved page size: window clipping, not a
        // relayout, is what hides the surface.
        for (index in 0 until childCount) {
            getChildAt(index).layout(0, 0, fakeWidth, fakeHeight)
        }
    }
}

/** Floating globe indicator; the entry point of the system browser surface. */
private class GlobeBallView(context: Context) : View(context) {
    private val background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xFF2C7FEB.toInt())
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFD166.toInt()
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    @Volatile
    private var loading = false

    fun setLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        postInvalidateOnAnimation()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return
        val padding = size * 0.16f
        val bounds = RectF(padding, padding, width - padding, height - padding)
        background.setBounds(0, 0, width, height)
        background.draw(canvas)

        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val radius = bounds.width() / 2f
        canvas.drawCircle(cx, cy, radius, stroke)
        canvas.drawOval(
            RectF(cx - radius * 0.45f, cy - radius, cx + radius * 0.45f, cy + radius),
            stroke
        )
        canvas.drawLine(cx - radius, cy, cx + radius, cy, stroke)
        if (loading) {
            val arcBounds = RectF(bounds)
            arcBounds.inset(-padding * 0.35f, -padding * 0.35f)
            canvas.drawArc(arcBounds, -90f, 110f, false, arc)
        }
    }
}

/**
 * Minimal horizontal wrapping layout for panel action chips: a download task
 * can offer six actions, and they must wrap to the next line instead of
 * pushing the row off-screen. Children keep their own measured sizes.
 */
private class WrapLayout(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val maxLineWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }
        var lineWidth = 0
        var lineHeight = 0
        var totalHeight = 0
        var widest = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            if (lineWidth > 0 && lineWidth + child.measuredWidth > maxLineWidth) {
                totalHeight += lineHeight
                widest = maxOf(widest, lineWidth)
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += child.measuredWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
        totalHeight += lineHeight
        widest = maxOf(widest, lineWidth)
        val resolvedWidth = when (widthMode) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthMeasureSpec)
            else -> maxOf(widest + paddingLeft + paddingRight, suggestedMinimumWidth)
        }
        setMeasuredDimension(
            resolvedWidth,
            resolveSize(
                totalHeight + paddingTop + paddingBottom,
                heightMeasureSpec
            )
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentRight = right - left - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            if (x > paddingLeft && x + child.measuredWidth > contentRight) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight)
        }
    }
}
