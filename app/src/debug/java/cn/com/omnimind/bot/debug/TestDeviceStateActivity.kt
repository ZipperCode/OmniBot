package cn.com.omnimind.bot.debug

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 非敏感调试验收 Activity (Debug Fixture)：
 * 1. 显示固定的非敏感屏幕标记 [EXPECTED_ASSIST_MARKER] ("OMNI_ASSIST_42")，供系统数字助理唤醒与屏幕内容识别验收。
 * 2. 提供真实可交互输入框 [inputField]，初始内容为 [INITIAL_UNCOMMITTED_TEXT] ("KEEP_84")，通过 Android 标准 [savedInstanceState]
 *    机制保存与恢复未提交文本，用于验证虚拟屏向主屏移交（present_to_user）及常规 Activity 重建时的状态保留。
 *
 * 规范与约束：
 * - 纯单机测试页面，无任何网络请求、无屏幕录制/截屏采集逻辑、无发送或 Agent 交互代码。
 * - 默认非 secure（clearFlags FLAG_SECURE），确保系统数字助理与虚拟屏预览管线能够正常获取画面内容；支持显式传入 [EXTRA_SECURE_WINDOW]=true 开启 FLAG_SECURE 用于系统安全窗口对照验收。
 * - 采用常规 Task（不配置 singleTask/configChanges 等掩盖重建的标记），完整经受系统生命周期重建检验。
 * - Manifest 中默认 android:enabled="false"，仅在验收测试期间通过 pm enable 临时启用，使用完毕后恢复 disabled。
 */
class TestDeviceStateActivity : Activity() {

    companion object {
        /** 供系统数字助理单次屏幕识别的固定非敏感文本 */
        const val EXPECTED_ASSIST_MARKER = "OMNI_ASSIST_42"

        /** 供虚拟屏移交与生命周期重建验证的初始未提交文本 */
        const val INITIAL_UNCOMMITTED_TEXT = "KEEP_84"

        /** 供系统安全窗口负向对照验收的 Intent Extra (boolean)，默认 false */
        const val EXTRA_SECURE_WINDOW = "fixture_secure_window"

        /** savedInstanceState 中保存未提交文字的键名 */
        const val KEY_UNCOMMITTED_TEXT = "fixture_uncommitted_text"

        /** savedInstanceState 中保存重建次数的键名 */
        const val KEY_RESTORATION_COUNT = "fixture_restoration_count"

        /** 最近一次实例引用（仅供测试进程轻量观察，禁止生产依赖） */
        @Volatile
        var lastInstance: TestDeviceStateActivity? = null

        /** 最近一次通过 savedInstanceState 成功恢复的文本 */
        @Volatile
        var lastRestoredText: String? = null

        /** 累计状态恢复次数 */
        @Volatile
        var totalRestorationCount: Int = 0

        /** 重置测试静态观察状态 */
        fun resetState() {
            lastInstance = null
            lastRestoredText = null
            totalRestorationCount = 0
        }
    }

    private var inputField: EditText? = null
    private var statusTextView: TextView? = null

    private var currentText: String = INITIAL_UNCOMMITTED_TEXT
    private var instanceRestorationCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 默认非 secure；显式传入 EXTRA_SECURE_WINDOW=true 时开启 FLAG_SECURE 用于安全窗口负向对照验收
        val isSecureWindow = intent?.getBooleanExtra(EXTRA_SECURE_WINDOW, false) == true
        if (isSecureWindow) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        lastInstance = this

        if (savedInstanceState != null) {
            instanceRestorationCount = savedInstanceState.getInt(KEY_RESTORATION_COUNT, 0) + 1
            totalRestorationCount = instanceRestorationCount
            val restored = savedInstanceState.getString(KEY_UNCOMMITTED_TEXT)
            if (restored != null) {
                currentText = restored
                lastRestoredText = restored
            }
        } else {
            currentText = INITIAL_UNCOMMITTED_TEXT
        }

        buildContentView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val textToSave = inputField?.text?.toString() ?: currentText
        currentText = textToSave
        outState.putString(KEY_UNCOMMITTED_TEXT, textToSave)
        outState.putInt(KEY_RESTORATION_COUNT, instanceRestorationCount)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val restored = savedInstanceState.getString(KEY_UNCOMMITTED_TEXT)
        if (restored != null) {
            currentText = restored
            lastRestoredText = restored
            inputField?.setText(restored)
            inputField?.setSelection(restored.length)
            statusTextView?.text = buildStatusText()
        }
    }

    override fun onDestroy() {
        if (lastInstance === this) {
            lastInstance = null
        }
        super.onDestroy()
    }

    /** 读取当前未提交文本，供测试检查 */
    fun getUncommittedText(): String {
        return inputField?.text?.toString() ?: currentText
    }

    /** 读取当前 Activity 重建恢复次数 */
    fun getRestorationCount(): Int {
        return instanceRestorationCount
    }

    private fun dp(dpValue: Int): Int {
        return (dpValue * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun buildStatusText(): String {
        return if (instanceRestorationCount > 0) {
            "当前状态：已通过 savedInstanceState 恢复 (重建次数: $instanceRestorationCount)\n当前保留文本: $currentText"
        } else {
            "当前状态：首次创建 (未触发重建)\n初始未提交文本: $currentText"
        }
    }

    private fun buildContentView() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#F9F9FB"))
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
        }

        // 页面标题
        val titleView = TextView(this).apply {
            text = "系统增强与任务状态保留验收页面"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#212121"))
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(titleView)

        // 概要说明
        val summaryView = TextView(this).apply {
            text = "本页面为非敏感 Debug Fixture，用于端到端验证系统数字助理的屏幕上下文识别，以及虚拟屏向主屏移交时的任务状态与未提交输入保留能力。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#616161"))
            setPadding(0, 0, 0, dp(20))
        }
        rootLayout.addView(summaryView)

        // 分割区块 1：助理屏幕识别标记
        val section1Title = TextView(this).apply {
            text = "一、系统数字助理屏幕识别标记"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 0, 0, dp(6))
        }
        rootLayout.addView(section1Title)

        val section1Desc = TextView(this).apply {
            text = "使用系统助理手势唤醒并附带当前屏幕时，解析的屏幕上下文应当包含以下固定非敏感标记："
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(section1Desc)

        // 固定标记文本视图
        val assistMarkerView = TextView(this).apply {
            id = View.generateViewId()
            text = EXPECTED_ASSIST_MARKER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            val boxPad = dp(16)
            setPadding(boxPad, boxPad, boxPad, boxPad)
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setTextIsSelectable(true)
            contentDescription = EXPECTED_ASSIST_MARKER
        }
        val markerLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(24)
        }
        rootLayout.addView(assistMarkerView, markerLp)

        // 分割区块 2：虚拟屏任务移交与状态保留
        val section2Title = TextView(this).apply {
            text = "二、虚拟屏移交与未提交文本保留"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 0, 0, dp(6))
        }
        rootLayout.addView(section2Title)

        val section2Desc = TextView(this).apply {
            text = "以下为真实可交互输入框，初始填入 KEEP_84。在虚拟屏向主屏移交或系统重建 Activity 时，未提交的输入内容将通过 Android savedInstanceState 保存与恢复："
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, 0, 0, dp(8))
        }
        rootLayout.addView(section2Desc)

        // 真实 EditText
        val edit = EditText(this).apply {
            id = View.generateViewId()
            hint = "请输入测试文字"
            setText(currentText)
            setSelection(currentText.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#212121"))
            setTypeface(Typeface.MONOSPACE)
            val editPad = dp(14)
            setPadding(editPad, editPad, editPad, editPad)
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            tag = "fixture_edit_text"
        }
        inputField = edit
        val editLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(24)
        }
        rootLayout.addView(edit, editLp)

        // 分割区块 3：生命周期与重建状态指示
        val section3Title = TextView(this).apply {
            text = "三、当前生命周期状态"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 0, 0, dp(6))
        }
        rootLayout.addView(section3Title)

        val statusView = TextView(this).apply {
            id = View.generateViewId()
            text = buildStatusText()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#455A64"))
            val statusPad = dp(12)
            setPadding(statusPad, statusPad, statusPad, statusPad)
            setBackgroundColor(Color.parseColor("#ECEFF1"))
        }
        statusTextView = statusView
        rootLayout.addView(statusView)

        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }
}
