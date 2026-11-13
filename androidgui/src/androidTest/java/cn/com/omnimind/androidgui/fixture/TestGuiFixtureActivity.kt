package cn.com.omnimind.androidgui.fixture

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import cn.com.omnimind.androidgui.test.R

/**
 * 独立 androidTest APK 的真实 GUI 验收夹具 Activity
 * 提供首页菜单与六大核心场景，保证后台虚拟屏可操作、可验证、无副作用。
 */
class TestGuiFixtureActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: LinearLayout
    private lateinit var headerLayout: LinearLayout
    private lateinit var contentFrame: FrameLayout

    private lateinit var textTitle: TextView
    private lateinit var textDisplayId: TextView
    private lateinit var textCurrentMarker: TextView
    private lateinit var btnBack: Button
    private lateinit var btnReset: Button

    private var loadingPending: Boolean = false
    private var loadingDeadlineMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FixtureStateHolder.updateDisplayId(this)

        savedInstanceState?.let { bundle ->
            val savedMarker = bundle.getString("marker", "")
            if (savedMarker.isNotBlank() && FixtureStateHolder.isValidMarker(savedMarker)) {
                FixtureStateHolder.setMarker(savedMarker)
            }
            FixtureStateHolder.scenario = bundle.getString("scenario", "home")
        }

        buildRootView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            rootContainer.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val padding = dp(12)
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        setContentView(rootContainer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) rootContainer.requestApplyInsets()
        renderScenario(FixtureStateHolder.scenario)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        FixtureStateHolder.updateDisplayId(this)
        updateHeaderInfo()
    }

    override fun onResume() {
        super.onResume()
        FixtureStateHolder.updateDisplayId(this)
        updateHeaderInfo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("marker", FixtureStateHolder.marker)
        outState.putString("scenario", FixtureStateHolder.scenario)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        FixtureStateHolder.updateDisplayId(this)
        updateHeaderInfo()
        renderScenario(FixtureStateHolder.scenario)
    }

    override fun onBackPressed() {
        if (FixtureStateHolder.scenario != "home") {
            switchScenario("home")
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }

    private fun buildRootView() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(8))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        textTitle = TextView(this).apply {
            id = R.id.text_header_title
            text = "GUI验收夹具"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnBack = Button(this).apply {
            id = R.id.btn_back
            text = "返回首页"
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener {
                switchScenario("home")
            }
        }

        btnReset = Button(this).apply {
            id = R.id.btn_reset
            text = "重置状态"
            minHeight = dp(48)
            setOnClickListener {
                mainHandler.removeCallbacksAndMessages(null)
                loadingPending = false
                loadingDeadlineMs = 0L
                FixtureStateHolder.reset()
                updateHeaderInfo()
                renderScenario(FixtureStateHolder.scenario)
            }
        }

        titleRow.addView(textTitle)
        titleRow.addView(btnBack)
        titleRow.addView(btnReset)

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(4), 0, dp(4))
        }

        textDisplayId = TextView(this).apply {
            id = R.id.text_display_id
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        textCurrentMarker = TextView(this).apply {
            id = R.id.text_current_marker
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
        }

        infoRow.addView(textDisplayId)
        infoRow.addView(textCurrentMarker)

        headerLayout.addView(titleRow)
        headerLayout.addView(infoRow)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                setMargins(0, dp(4), 0, dp(8))
            }
            setBackgroundColor(Color.LTGRAY)
        }
        headerLayout.addView(divider)

        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rootContainer.addView(headerLayout)
        rootContainer.addView(contentFrame)
    }

    private fun updateHeaderInfo() {
        val dispId = FixtureStateHolder.displayId
        textDisplayId.text = "Display: ${if (dispId >= 0) dispId else "未知"}"
        val currentMarker = FixtureStateHolder.marker
        textCurrentMarker.text = "Marker: ${if (currentMarker.isNotBlank()) currentMarker else "[未设置]"}"
        btnBack.visibility = if (FixtureStateHolder.scenario != "home") View.VISIBLE else View.GONE
    }

    private fun switchScenario(newScenario: String) {
        mainHandler.removeCallbacksAndMessages(null)
        FixtureStateHolder.scenario = newScenario
        updateHeaderInfo()
        renderScenario(newScenario)
    }

    private fun renderScenario(scenario: String) {
        contentFrame.removeAllViews()
        when (scenario) {
            "long_press" -> contentFrame.addView(buildLongPressLayout())
            "loading" -> contentFrame.addView(buildLoadingLayout())
            "dynamic" -> contentFrame.addView(buildDynamicLayout())
            "collection" -> contentFrame.addView(buildCollectionLayout())
            "form" -> contentFrame.addView(buildFormLayout())
            "canvas" -> contentFrame.addView(buildCanvasLayout())
            else -> contentFrame.addView(buildHomeLayout())
        }
    }

    // ==========================================
    // 首页菜单
    // ==========================================
    private fun buildHomeLayout(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(8), 0, dp(16))
        }

        val markerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.argb(20, 33, 150, 243))
        }

        val labelMarker = TextView(this).apply {
            text = "合成验收标记 (Marker)"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }

        val inputMarker = EditText(this).apply {
            id = R.id.input_marker
            hint = "输入 OOB_LIVE_GUI_... 合成标记"
            textSize = 14f
            minHeight = dp(48)
            setSingleLine(true)
            setText(FixtureStateHolder.marker)
        }

        val textMarkerHint = TextView(this).apply {
            text = "规则：必须为 OOB_LIVE_GUI_ 前缀的 ASCII 字符串"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(2), 0, dp(8))
        }

        val btnSetMarker = Button(this).apply {
            id = R.id.btn_set_marker
            text = "设置标记并清空状态"
            minHeight = dp(48)
            setOnClickListener {
                val candidate = inputMarker.text.toString().trim()
                if (FixtureStateHolder.setMarker(candidate)) {
                    mainHandler.removeCallbacksAndMessages(null)
                    loadingPending = false
                    loadingDeadlineMs = 0L
                    updateHeaderInfo()
                    textMarkerHint.text = "设置成功：$candidate"
                    textMarkerHint.setTextColor(Color.rgb(0x2E, 0x7D, 0x32))
                } else {
                    textMarkerHint.text = "设置失败：格式必须符合 OOB_LIVE_GUI_[A-Za-z0-9_.-]+"
                    textMarkerHint.setTextColor(Color.RED)
                }
            }
        }

        markerCard.addView(labelMarker)
        markerCard.addView(inputMarker)
        markerCard.addView(textMarkerHint)
        markerCard.addView(btnSetMarker)
        layout.addView(markerCard)

        val labelMenu = TextView(this).apply {
            text = "功能验收场景菜单"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        }
        layout.addView(labelMenu)

        fun addMenuButton(idRes: Int, textStr: String, scenarioTarget: String) {
            val btn = Button(this).apply {
                id = idRes
                text = textStr
                minHeight = dp(48)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(4), 0, dp(4))
                }
                setOnClickListener {
                    switchScenario(scenarioTarget)
                }
            }
            layout.addView(btn)
        }

        addMenuButton(R.id.btn_menu_long_press, "1. 长按验收场景", "long_press")
        addMenuButton(R.id.btn_menu_loading, "2. 加载等待场景", "loading")
        addMenuButton(R.id.btn_menu_dynamic, "3. 动态标签与消歧", "dynamic")
        addMenuButton(R.id.btn_menu_collection, "4. 多屏长列表采集", "collection")
        addMenuButton(R.id.btn_menu_form, "5. 表单提交场景", "form")
        addMenuButton(R.id.btn_menu_canvas, "6. Canvas绘图场景", "canvas")

        scroll.addView(layout)
        return scroll
    }

    // ==========================================
    // 场景 1：长按验收 (long_press)
    // ==========================================
    private fun buildLongPressLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, dp(16), 0, dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "场景：长按验收"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        val desc = TextView(this).apply {
            text = "持续长按目标按键 >= 800ms 触发成功。短按仅计为 tap，手势取消不触发迟到长按。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(24))
            gravity = Gravity.CENTER
        }

        val textStatus = TextView(this).apply {
            id = R.id.text_long_press_status
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(24))
            text = if (FixtureStateHolder.statusFlags.contains("LONG_PRESS_OK")) {
                "LONG_PRESS_OK"
            } else {
                "未触发"
            }
        }

        val btnTarget = Button(this).apply {
            id = R.id.btn_target
            text = "长按目标"
            textSize = 16f
            minHeight = dp(64)
            minWidth = dp(200)
        }

        setupLongPressTouch(btnTarget, textStatus)

        layout.addView(title)
        layout.addView(desc)
        layout.addView(textStatus)
        layout.addView(btnTarget)
        return layout
    }

    private fun setupLongPressTouch(btn: View, statusText: TextView) {
        val slop = ViewConfiguration.get(btn.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        var isLongPressed = false
        var moved = false

        val longPressRunnable = Runnable {
            if (!isLongPressed) {
                isLongPressed = true
                FixtureStateHolder.incrementCount("long_press")
                FixtureStateHolder.addStatusFlag("LONG_PRESS_OK")
                statusText.text = "LONG_PRESS_OK"
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        btn.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressed = false
                    moved = false
                    downTime = event.eventTime
                    downX = event.x
                    downY = event.y
                    mainHandler.postDelayed(longPressRunnable, 800L)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = Math.abs(event.x - downX)
                    val dy = Math.abs(event.y - downY)
                    if (dx > slop || dy > slop) {
                        moved = true
                        mainHandler.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    moved = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    val pressDuration = event.eventTime - downTime
                    if (!isLongPressed && !moved && pressDuration >= 800L) {
                        longPressRunnable.run()
                    } else if (!isLongPressed) {
                        FixtureStateHolder.incrementCount("tap")
                        if (!FixtureStateHolder.statusFlags.contains("LONG_PRESS_OK")) {
                            statusText.text = "短按触发 (tap+1)"
                        }
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    // ==========================================
    // 场景 2：加载等待与确认 (loading)
    // ==========================================
    private fun buildLoadingLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, dp(16), 0, dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "场景：加载等待与确认"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        val desc = TextView(this).apply {
            text = "单次点击“触发加载”，2500ms 后显示“可确认 READY_TO_CONFIRM”并启用确认按键。持续加载开启时确认按键始终不可用。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
            gravity = Gravity.CENTER
        }

        val switchPersistent = Switch(this).apply {
            id = R.id.switch_persistent_loading
            text = "持续加载开关 (Persistent Loading)"
            minHeight = dp(48)
            isChecked = FixtureStateHolder.persistentLoading
        }

        val textStatus = TextView(this).apply {
            id = R.id.text_load_status
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(24))
            text = when {
                FixtureStateHolder.statusFlags.contains("LOAD_OK") -> "LOAD_OK"
                loadingPending -> "加载中..."
                FixtureStateHolder.persistentLoading -> "持续加载中..."
                else -> "未加载"
            }
        }

        val btnConfirm = Button(this).apply {
            id = R.id.btn_confirm
            text = "确认"
            textSize = 16f
            minHeight = dp(48)
            minWidth = dp(180)
            isEnabled = false
        }

        switchPersistent.setOnCheckedChangeListener { _, isChecked ->
            FixtureStateHolder.persistentLoading = isChecked
            if (isChecked) {
                loadingPending = false
                loadingDeadlineMs = 0L
                FixtureStateHolder.generation++
                mainHandler.removeCallbacksAndMessages(null)
                btnConfirm.isEnabled = false
                textStatus.text = "持续加载中 (确认已禁用)"
            } else {
                if (!FixtureStateHolder.statusFlags.contains("LOAD_OK")) {
                    btnConfirm.isEnabled = false
                    textStatus.text = "未加载"
                }
            }
        }

        val btnTrigger = Button(this).apply {
            id = R.id.btn_load_trigger
            text = "触发加载"
            textSize = 16f
            minHeight = dp(48)
            minWidth = dp(180)
            setOnClickListener {
                FixtureStateHolder.incrementCount("load")
                if (FixtureStateHolder.persistentLoading) {
                    btnConfirm.isEnabled = false
                    textStatus.text = "持续加载中 (确认已禁用)"
                } else {
                    btnConfirm.isEnabled = false
                    textStatus.text = "加载中..."
                    loadingPending = true
                    loadingDeadlineMs = SystemClock.elapsedRealtime() + 2500L
                    val currentGen = ++FixtureStateHolder.generation

                    mainHandler.postDelayed({
                        if (currentGen == FixtureStateHolder.generation && !FixtureStateHolder.persistentLoading) {
                            loadingPending = false
                            btnConfirm.isEnabled = true
                            textStatus.text = "READY_TO_CONFIRM"
                        }
                    }, 2500L)
                }
            }
        }

        btnConfirm.setOnClickListener {
            if (btnConfirm.isEnabled) {
                FixtureStateHolder.incrementCount("confirm")
                FixtureStateHolder.addStatusFlag("LOAD_OK")
                textStatus.text = "LOAD_OK"
            }
        }

        // 恢复已有状态
        if (FixtureStateHolder.statusFlags.contains("LOAD_OK")) {
            btnConfirm.isEnabled = true
            textStatus.text = "LOAD_OK"
        } else if (loadingPending && !FixtureStateHolder.persistentLoading) {
            val remaining = loadingDeadlineMs - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                loadingPending = false
                btnConfirm.isEnabled = true
                textStatus.text = "READY_TO_CONFIRM"
            } else {
                val currentGen = FixtureStateHolder.generation
                mainHandler.postDelayed({
                    if (currentGen == FixtureStateHolder.generation && !FixtureStateHolder.persistentLoading) {
                        loadingPending = false
                        btnConfirm.isEnabled = true
                        textStatus.text = "READY_TO_CONFIRM"
                    }
                }, remaining)
            }
        }

        layout.addView(title)
        layout.addView(desc)
        layout.addView(switchPersistent)
        layout.addView(textStatus)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        btnRow.addView(btnTrigger)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), 1)
        }
        btnRow.addView(spacer)
        btnRow.addView(btnConfirm)

        layout.addView(btnRow)
        return layout
    }

    // ==========================================
    // 场景 3：动态标签与消歧 (dynamic)
    // ==========================================
    private fun buildDynamicLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, dp(16), 0, dp(16))
        }

        val title = TextView(this).apply {
            text = "场景：动态标签与消歧"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val desc = TextView(this).apply {
            text = "列表中包含两行同文案“去结算(3)”按钮；底部总计栏为唯一目标结算按键 (id=checkout)。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        }

        val textStatus = TextView(this).apply {
            id = R.id.text_checkout_status
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(16))
            text = if (FixtureStateHolder.statusFlags.contains("CHECKOUT_OK")) {
                "CHECKOUT_OK"
            } else {
                "未结算"
            }
        }

        layout.addView(title)
        layout.addView(desc)
        layout.addView(textStatus)

        fun createDisambiguationRow(titleText: String, btnId: Int): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dp(4), 0, dp(4))
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setBackgroundColor(Color.argb(10, 0, 0, 0))

                val rowTitle = TextView(context).apply {
                    text = titleText
                    textSize = 15f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val rowBtn = Button(context).apply {
                    id = btnId
                    text = "去结算(3)"
                    minHeight = dp(48)
                    setOnClickListener {
                        FixtureStateHolder.incrementCount("tap")
                        if (!FixtureStateHolder.statusFlags.contains("CHECKOUT_OK")) {
                            textStatus.text = "点击了列表项消歧按钮（非主目标）"
                        }
                    }
                }

                addView(rowTitle)
                addView(rowBtn)
            }
        }

        layout.addView(createDisambiguationRow("商品A (特惠专区)", R.id.btn_disambiguate_1))
        layout.addView(createDisambiguationRow("商品B (推荐专区)", R.id.btn_disambiguate_2))

        val footerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(24), 0, 0)
            }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.argb(20, 76, 175, 80))

            val totalText = TextView(context).apply {
                text = "购物车合计: ￥388.00"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val checkoutBtn = Button(context).apply {
                id = R.id.checkout
                text = "去结算(3)"
                textSize = 16f
                minHeight = dp(48)
                setOnClickListener {
                    FixtureStateHolder.incrementCount("checkout")
                    FixtureStateHolder.addStatusFlag("CHECKOUT_OK")
                    textStatus.text = "CHECKOUT_OK"
                }
            }

            addView(totalText)
            addView(checkoutBtn)
        }

        layout.addView(footerCard)
        return layout
    }

    // ==========================================
    // 场景 4：多屏长列表采集 (collection)
    // ==========================================
    private fun buildCollectionLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val headerText = TextView(this).apply {
            id = R.id.text_collection_header
            text = "多屏长列表采集 (50项 + 2重复项)"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(headerText)

        val scroll = ScrollView(this).apply {
            id = R.id.scroll_collection
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val itemIds = intArrayOf(
            R.id.item_01, R.id.item_02, R.id.item_03, R.id.item_04, R.id.item_05,
            R.id.item_06, R.id.item_07, R.id.item_08, R.id.item_09, R.id.item_10,
            R.id.item_11, R.id.item_12, R.id.item_13, R.id.item_14, R.id.item_15,
            R.id.item_16, R.id.item_17, R.id.item_18, R.id.item_19, R.id.item_20,
            R.id.item_21, R.id.item_22, R.id.item_23, R.id.item_24, R.id.item_25,
            R.id.item_26, R.id.item_27, R.id.item_28, R.id.item_29, R.id.item_30,
            R.id.item_31, R.id.item_32, R.id.item_33, R.id.item_34, R.id.item_35,
            R.id.item_36, R.id.item_37, R.id.item_38, R.id.item_39, R.id.item_40,
            R.id.item_41, R.id.item_42, R.id.item_43, R.id.item_44, R.id.item_45,
            R.id.item_46, R.id.item_47, R.id.item_48, R.id.item_49, R.id.item_50
        )

        fun createItemRow(resId: Int, rowText: String, isDuplicate: Boolean = false): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(52)
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(4))
                if (isDuplicate) {
                    setBackgroundColor(Color.argb(15, 255, 152, 0))
                }

                val primary = TextView(context).apply {
                    id = resId
                    text = rowText
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                addView(primary)
            }
        }

        // 添加 1 到 10 项
        for (i in 1..10) {
            val name = String.format("ITEM_%02d", i)
            listContainer.addView(createItemRow(itemIds[i - 1], name))
        }

        // 插入第 1 个重复文字行 (在第10项后)
        listContainer.addView(createItemRow(R.id.item_duplicate_1, "DUPLICATE_ROW", isDuplicate = true))

        // 添加 11 到 25 项
        for (i in 11..25) {
            val name = String.format("ITEM_%02d", i)
            listContainer.addView(createItemRow(itemIds[i - 1], name))
        }

        // 插入第 2 个重复文字行 (在第25项后)
        listContainer.addView(createItemRow(R.id.item_duplicate_2, "DUPLICATE_ROW", isDuplicate = true))

        // 添加 26 到 50 项
        for (i in 26..50) {
            val name = String.format("ITEM_%02d", i)
            listContainer.addView(createItemRow(itemIds[i - 1], name))
        }

        scroll.addView(listContainer)
        root.addView(scroll)
        return root
    }

    // ==========================================
    // 场景 5：表单提交 (form)
    // ==========================================
    private fun buildFormLayout(): View {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(16), 0, dp(16))
        }

        val title = TextView(this).apply {
            text = "场景：表单提交"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        val textStatus = TextView(this).apply {
            id = R.id.text_form_status
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(16))
            val currentFirst = FixtureStateHolder.formValues["first"] ?: ""
            val currentSecond = FixtureStateHolder.formValues["second"] ?: ""
            text = when {
                FixtureStateHolder.statusFlags.contains("FORM_OK") -> {
                    contentDescription = "FORM_OK"
                    "FORM_OK"
                }
                FixtureStateHolder.entryDisabled -> "入口已失效"
                else -> "未提交"
            }
        }

        val switchDisabled = Switch(this).apply {
            id = R.id.switch_entry_disabled
            text = "入口失效开关 (Entry Disabled)"
            minHeight = dp(48)
            isChecked = FixtureStateHolder.entryDisabled
            setOnCheckedChangeListener { _, isChecked ->
                FixtureStateHolder.entryDisabled = isChecked
                textStatus.text = if (isChecked) "入口已失效" else "未提交"
            }
        }

        val labelFirst = TextView(this).apply {
            text = "第一项合成输入 (First Value)"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        }

        val inputFirst = EditText(this).apply {
            id = R.id.input_first
            hint = "输入 SYNTHETIC_ 或 OOB_LIVE_GUI_ 值"
            textSize = 14f
            minHeight = dp(48)
            setSingleLine(true)
            setText(FixtureStateHolder.formValues["first"])
        }

        val labelSecond = TextView(this).apply {
            text = "第二项合成输入 (Second Value)"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(4))
        }

        val inputSecond = EditText(this).apply {
            id = R.id.input_second
            hint = "输入 SYNTHETIC_ 或 OOB_LIVE_GUI_ 值"
            textSize = 14f
            minHeight = dp(48)
            setSingleLine(true)
            setText(FixtureStateHolder.formValues["second"])
        }

        val textHint = TextView(this).apply {
            id = R.id.text_form_hint
            text = "安全约定：输入仅接受 SYNTHETIC_ 或 OOB_LIVE_GUI_ 前缀的 ASCII 合成值"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(16))
        }

        val btnSubmit = Button(this).apply {
            id = R.id.btn_form_submit
            text = "提交"
            textSize = 16f
            minHeight = dp(48)
            setOnClickListener {
                // 当入口失效开启时，点击控件保持可点击但界面与状态均无任何变化，供无进展检测失败归因
                if (FixtureStateHolder.entryDisabled) {
                    return@setOnClickListener
                }

                val firstVal = inputFirst.text.toString().trim()
                val secondVal = inputSecond.text.toString().trim()

                if (FixtureStateHolder.setFormInput(firstVal, secondVal)) {
                    FixtureStateHolder.incrementCount("submit")
                    FixtureStateHolder.addStatusFlag("FORM_OK")
                    textStatus.contentDescription = "FORM_OK"
                    textStatus.text = "FORM_OK"
                } else {
                    textStatus.text = "输入格式错误：必须为 SYNTHETIC_ 或 OOB_LIVE_GUI_ 前缀"
                }
            }
        }

        layout.addView(title)
        layout.addView(textStatus)
        layout.addView(switchDisabled)
        layout.addView(labelFirst)
        layout.addView(inputFirst)
        layout.addView(labelSecond)
        layout.addView(inputSecond)
        layout.addView(textHint)
        layout.addView(btnSubmit)

        scroll.addView(layout)
        return scroll
    }

    // ==========================================
    // 场景 6：Canvas 绘图 (canvas)
    // ==========================================
    private fun buildCanvasLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, dp(16), 0, dp(16))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "场景：Canvas 绘图"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }

        val desc = TextView(this).apply {
            text = "Canvas 仅绘制纯颜色与数字，无任何辅助功能文本子节点。点击触发图形与颜色切换后保持静止。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
            gravity = Gravity.CENTER
        }

        val textStatus = TextView(this).apply {
            id = R.id.text_canvas_status
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
            text = "Canvas 区域仅支持纯视觉像素感知"
        }

        val canvasView = TestCanvasView(this).apply {
            id = R.id.view_canvas
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220))
            onStateChangedListener = { _ ->
                FixtureStateHolder.incrementCount("canvas")
                FixtureStateHolder.addStatusFlag("CANVAS_CHANGED")
            }
        }

        layout.addView(title)
        layout.addView(desc)
        layout.addView(textStatus)
        layout.addView(canvasView)
        return layout
    }
}
