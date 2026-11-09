package cn.com.omnimind.bot.agent.display

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.activity.StartupThemeResolver
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import kotlin.math.min

/**
 * 屏幕任务专用主题适配器。
 * 权威对齐 ui/lib/theme/omni_theme_palette.dart 规范：
 * - exact OmniThemePalette neutrals
 * - brandPrimary 仅作为状态点缀与轻微光晕，不等于 primary action
 * - primary action 使用 accentPrimary 黑白反差与 onAccent 反色文字
 * - error 统一为 #FF6464，采用中性次级背景承托
 */
internal object ScreenTaskTheme {
    const val LIGHT_PAGE_BG = 0xFFF6F6F6.toInt()
    const val LIGHT_SURFACE_PRIMARY = 0xFFFFFFFF.toInt()
    const val LIGHT_SURFACE_SECONDARY = 0xFFF0F0F0.toInt()
    const val LIGHT_SURFACE_ELEVATED = 0xFFE7E7E7.toInt()
    const val LIGHT_BORDER_SUBTLE = 0xFFE4E4E4.toInt()
    const val LIGHT_BORDER_STRONG = 0xFFCECECE.toInt()
    const val LIGHT_TEXT_PRIMARY = 0xFF161616.toInt()
    const val LIGHT_TEXT_SECONDARY = 0xFF777777.toInt()
    const val LIGHT_TEXT_TERTIARY = 0xFF929292.toInt()
    const val LIGHT_ACCENT_PRIMARY = 0xFF161616.toInt()
    const val LIGHT_ON_ACCENT = 0xFFFFFFFF.toInt()
    const val LIGHT_BRAND_PRIMARY = 0xFF2C7FEB.toInt()
    const val LIGHT_BRAND_SURFACE = 0xFFE6EDFF.toInt()

    const val DARK_PAGE_BG = 0xFF151515.toInt()
    const val DARK_SURFACE_PRIMARY = 0xFF202020.toInt()
    const val DARK_SURFACE_SECONDARY = 0xFF292929.toInt()
    const val DARK_SURFACE_ELEVATED = 0xFF333333.toInt()
    const val DARK_BORDER_SUBTLE = 0xFF383838.toInt()
    const val DARK_BORDER_STRONG = 0xFF4A4A4A.toInt()
    const val DARK_TEXT_PRIMARY = 0xFFF3F3F3.toInt()
    const val DARK_TEXT_SECONDARY = 0xFFB0B0B0.toInt()
    const val DARK_TEXT_TERTIARY = 0xFF8E8E8E.toInt()
    const val DARK_ACCENT_PRIMARY = 0xFFF3F3F3.toInt()
    const val DARK_ON_ACCENT = 0xFF161616.toInt()
    const val DARK_BRAND_PRIMARY = 0xFF98AD90.toInt()
    const val DARK_BRAND_SURFACE = 0xFF2E382D.toInt()

    const val COLOR_ERROR = 0xFFFF6464.toInt()
    const val COLOR_WARNING = 0xFFFF9F0A.toInt()

    val LIGHT_EDGE_COLORS = intArrayOf(
        0xFF2C7FEB.toInt(),
        0xB32C7FEB.toInt(),
        0x4D2C7FEB.toInt(),
        0xCC2C7FEB.toInt(),
        0xFF2C7FEB.toInt()
    )

    val DARK_EDGE_COLORS = intArrayOf(
        0xFF98AD90.toInt(),
        0xB398AD90.toInt(),
        0x4D98AD90.toInt(),
        0xCC98AD90.toInt(),
        0xFF98AD90.toInt()
    )

    fun isNight(context: Context): Boolean {
        return when (StartupThemeResolver.readStoredThemeMode(context)) {
            StartupThemeResolver.StartupThemeMode.DARK -> true
            StartupThemeResolver.StartupThemeMode.LIGHT -> false
            StartupThemeResolver.StartupThemeMode.SYSTEM -> {
                // 系统模式沿用主页面配置，避免应用与独立窗口的夜间模式覆盖不同步。
                val uiMode = (TaskRuntimeSettings.currentActivityUiMode()
                    ?: context.resources.configuration.uiMode) and Configuration.UI_MODE_NIGHT_MASK
                uiMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun isGlassEnabled(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            prefs.getBoolean("flutter.liquid_glass_enabled", true)
        } catch (_: Throwable) {
            true
        }
    }

    fun cardSurfaceColor(context: Context, isNight: Boolean): Int {
        val glass = isGlassEnabled(context)
        return if (glass) {
            if (isNight) 0xF8202020.toInt() else 0xF8FFFFFF.toInt()
        } else {
            if (isNight) DARK_SURFACE_PRIMARY else LIGHT_SURFACE_PRIMARY
        }
    }

    fun surfacePrimary(isNight: Boolean): Int =
        if (isNight) DARK_SURFACE_PRIMARY else LIGHT_SURFACE_PRIMARY

    fun surfaceSecondary(isNight: Boolean): Int =
        if (isNight) DARK_SURFACE_SECONDARY else LIGHT_SURFACE_SECONDARY

    fun borderSubtle(isNight: Boolean): Int =
        if (isNight) DARK_BORDER_SUBTLE else LIGHT_BORDER_SUBTLE

    fun textPrimary(isNight: Boolean): Int =
        if (isNight) DARK_TEXT_PRIMARY else LIGHT_TEXT_PRIMARY

    fun textSecondary(isNight: Boolean): Int =
        if (isNight) DARK_TEXT_SECONDARY else LIGHT_TEXT_SECONDARY

    fun accentPrimary(isNight: Boolean): Int =
        if (isNight) DARK_ACCENT_PRIMARY else LIGHT_ACCENT_PRIMARY

    fun onAccent(isNight: Boolean): Int =
        if (isNight) DARK_ON_ACCENT else LIGHT_ON_ACCENT

    fun brandPrimary(isNight: Boolean): Int =
        if (isNight) DARK_BRAND_PRIMARY else LIGHT_BRAND_PRIMARY

    fun brandSurface(isNight: Boolean): Int =
        if (isNight) DARK_BRAND_SURFACE else LIGHT_BRAND_SURFACE

    fun errorColor(): Int = COLOR_ERROR

    fun warningColor(): Int = COLOR_WARNING

    fun edgeLightColors(isNight: Boolean): IntArray =
        if (isNight) DARK_EDGE_COLORS else LIGHT_EDGE_COLORS

    fun rippleColor(bgColor: Int): Int {
        val r = Color.red(bgColor)
        val g = Color.green(bgColor)
        val b = Color.blue(bgColor)
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance < 128f) 0x33FFFFFF else 0x1F000000
    }
}

internal fun dp(context: Context, value: Number): Int =
    (value.toFloat() * context.resources.displayMetrics.density + 0.5f).toInt()

internal fun dpF(context: Context, value: Number): Float =
    value.toFloat() * context.resources.displayMetrics.density

internal fun isReducedMotion(context: Context): Boolean = try {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Throwable) { false }

internal fun createRippleBackground(normalColor: Int, rippleColor: Int, cornerRadiusPx: Float): Drawable {
    val content = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerRadiusPx
        setColor(normalColor)
    }
    val mask = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerRadiusPx
        setColor(Color.WHITE)
    }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

internal fun markAsAccessibilityButton(view: View, label: String) {
    view.contentDescription = label
    view.isClickable = true
    view.isFocusable = true
    ViewCompat.setAccessibilityDelegate(view, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    })
}

internal fun mapFriendlyStatus(rawStatus: String?, isWaiting: Boolean, displayId: Int): String {
    if (isWaiting) return "等待人工确认..."
    if (rawStatus.isNullOrBlank()) {
        return if (displayId > 0) "虚拟屏协同中..." else "正在操作屏幕..."
    }
    val lower = rawStatus.lowercase()
    return when {
        lower.contains("click") || lower.contains("tap") || lower.contains("点击") -> "正在点击目标..."
        lower.contains("scroll") || lower.contains("swipe") || lower.contains("滑动") -> "正在滑动页面..."
        lower.contains("type") || lower.contains("input") || lower.contains("输入") -> "正在输入内容..."
        lower.contains("wait") || lower.contains("sleep") || lower.contains("等待") -> "等待页面响应..."
        lower.contains("open") || lower.contains("launch") || lower.contains("打开") -> "正在打开应用..."
        lower.contains("back") || lower.contains("返回") -> "正在返回上一页..."
        lower.contains("home") || lower.contains("桌面") -> "正在返回桌面..."
        displayId > 0 -> "虚拟屏协同中..."
        else -> "正在操作屏幕..."
    }
}

internal object SimpleTextFormatter {
    fun format(source: String, isNight: Boolean): CharSequence {
        if (source.isEmpty()) return ""
        val ssb = SpannableStringBuilder()
        val lines = source.split("\n")
        val codeBg = ScreenTaskTheme.surfaceSecondary(isNight)

        for ((i, rawLine) in lines.withIndex()) {
            if (i > 0) ssb.append("\n")
            val line = rawLine.trimEnd()
            when {
                line.startsWith("# ") -> {
                    val start = ssb.length
                    ssb.append(line.removePrefix("# "))
                    ssb.setSpan(RelativeSizeSpan(1.2f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("## ") -> {
                    val start = ssb.length
                    ssb.append(line.removePrefix("## "))
                    ssb.setSpan(RelativeSizeSpan(1.1f), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    ssb.append("•  ")
                    formatInline(ssb, line.substring(2), codeBg)
                }
                else -> formatInline(ssb, line, codeBg)
            }
        }
        return ssb
    }

    private fun formatInline(ssb: SpannableStringBuilder, text: String, codeBg: Int) {
        var idx = 0
        while (idx < text.length) {
            val boldStart = text.indexOf("**", idx)
            val codeStart = text.indexOf('`', idx)
            val nextSpecial = when {
                boldStart != -1 && codeStart != -1 -> minOf(boldStart, codeStart)
                boldStart != -1 -> boldStart
                codeStart != -1 -> codeStart
                else -> -1
            }
            if (nextSpecial == -1) {
                ssb.append(text.substring(idx))
                break
            }
            if (nextSpecial > idx) ssb.append(text.substring(idx, nextSpecial))

            if (nextSpecial == boldStart) {
                val boldEnd = text.indexOf("**", boldStart + 2)
                if (boldEnd != -1) {
                    val start = ssb.length
                    ssb.append(text.substring(boldStart + 2, boldEnd))
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    idx = boldEnd + 2
                } else {
                    ssb.append(text.substring(boldStart))
                    break
                }
            } else {
                val codeEnd = text.indexOf('`', codeStart + 1)
                if (codeEnd != -1) {
                    val start = ssb.length
                    ssb.append(text.substring(codeStart + 1, codeEnd))
                    ssb.setSpan(TypefaceSpan("monospace"), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(codeBg), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    idx = codeEnd + 1
                } else {
                    ssb.append(text.substring(codeStart))
                    break
                }
            }
        }
    }
}

/**
 * 屏幕边缘光晕视图。
 * 遵循同品牌冷暖色系 + 2dp细线与低alpha柔光，不再使用彩虹色；
 * onDraw 中绝不分配对象，绝不读取 Settings / SharedPreferences。
 */
class ScreenTaskEdgeLightView(context: Context) : View(context) {

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val staticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val edgePath = Path()
    private val matrixTransform = Matrix()
    private var sweepGradient: SweepGradient? = null
    private var rotationAngle = 0f
    private var rotationAnimator: ValueAnimator? = null
    private var isGlowRunning = false
    private var reducedMotion = false

    private val targetBounds = RectF()
    private var hasTargetBounds = false
    private var explicitCornerRadius: Float? = null
    private var gradientCenterX = 0f
    private var gradientCenterY = 0f
    init {
        updateReducedMotion()
        updatePaintsAndShaders()
    }

    private fun updateReducedMotion() {
        reducedMotion = isReducedMotion(context)
    }

    private fun updatePaintsAndShaders() {
        val density = resources.displayMetrics.density
        val isNight = ScreenTaskTheme.isNight(context)
        val brandColor = ScreenTaskTheme.brandPrimary(isNight)

        val rectW = if (hasTargetBounds) targetBounds.width() else width.toFloat()
        val rectH = if (hasTargetBounds) targetBounds.height() else height.toFloat()
        val minDim = minOf(rectW, rectH).coerceAtLeast(1f)

        // 自适应笔触缩放：小尺寸内容缩略缩放光晕粗细，避免遮挡内容
        val scale = if (hasTargetBounds) (minDim / (160f * density)).coerceIn(0.25f, 1f) else 1f

        haloPaint.strokeWidth = 14f * density * scale
        haloPaint.maskFilter = BlurMaskFilter((8f * density * scale).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
        haloPaint.alpha = (0.70f * 255).toInt()

        linePaint.strokeWidth = (2.5f * density * scale).coerceAtLeast(1.5f)
        linePaint.alpha = 255

        staticPaint.strokeWidth = (2.5f * density * scale).coerceAtLeast(1.5f)
        staticPaint.color = brandColor
        staticPaint.alpha = 255

        val cx = if (hasTargetBounds) targetBounds.centerX() else width / 2f
        val cy = if (hasTargetBounds) targetBounds.centerY() else height / 2f
        gradientCenterX = cx
        gradientCenterY = cy

        if (rectW > 0f && rectH > 0f) {
            val colors = ScreenTaskTheme.edgeLightColors(isNight)
            sweepGradient = SweepGradient(cx, cy, colors, null)
            haloPaint.shader = sweepGradient
            linePaint.shader = sweepGradient
        }
    }

    /**
     * 设置自定义贴合矩形与圆角半径（用于虚拟屏内容 fit-center 贴合）。
     * 仅在几何尺寸真正改变时更新 Path 与 Shader，无每帧重复分配。
     */
    fun setTargetRect(rect: RectF, cornerRadius: Float? = null) {
        if (hasTargetBounds &&
            targetBounds.left == rect.left &&
            targetBounds.top == rect.top &&
            targetBounds.right == rect.right &&
            targetBounds.bottom == rect.bottom &&
            explicitCornerRadius == cornerRadius
        ) {
            return
        }
        hasTargetBounds = true
        targetBounds.set(rect)
        explicitCornerRadius = cornerRadius
        updatePaintsAndShaders()
        rebuildPath()
        invalidate()
    }

    fun clearTargetRect() {
        if (!hasTargetBounds) return
        hasTargetBounds = false
        targetBounds.setEmpty()
        explicitCornerRadius = null
        updatePaintsAndShaders()
        rebuildPath()
        invalidate()
    }

    fun isRunning(): Boolean = isGlowRunning

    fun getTargetBounds(): RectF? = if (hasTargetBounds) RectF(targetBounds) else null

    fun start() {
        if (isGlowRunning) return
        isGlowRunning = true
        updateReducedMotion()
        if (reducedMotion) {
            rotationAngle = 0f
            invalidate()
            return
        }
        rotationAnimator?.cancel()
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                rotationAngle = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        isGlowRunning = false
        rotationAnimator?.cancel()
        rotationAnimator = null
        invalidate()
    }

    fun onConfigChanged() {
        updateReducedMotion()
        updatePaintsAndShaders()
        rebuildPath()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateReducedMotion()
        if (!hasTargetBounds) {
            start()
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            updatePaintsAndShaders()
            rebuildPath()
        }
    }

    private fun rebuildPath() {
        edgePath.reset()
        if (hasTargetBounds) {
            if (targetBounds.width() <= 0f || targetBounds.height() <= 0f) return
            val r = explicitCornerRadius ?: dpF(context, 12f)
            edgePath.addRoundRect(targetBounds, r, r, Path.Direction.CW)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val inset = dpF(context, 1.5f)
        var cornerRadius = dpF(context, 28f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            val tl = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
            val tr = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius ?: 0
            val br = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
            val bl = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            val maxR = maxOf(tl, tr, br, bl)
            if (maxR > 0) cornerRadius = maxR.toFloat()
        }

        val r = (cornerRadius - inset).coerceAtLeast(dpF(context, 4f))
        val rect = RectF(inset, inset, w - inset, h - inset)
        edgePath.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        if (!isGlowRunning) return
        val w = if (hasTargetBounds) targetBounds.width() else width.toFloat()
        val h = if (hasTargetBounds) targetBounds.height() else height.toFloat()
        if (w <= 0f || h <= 0f) return

        val saveCount = canvas.save()
        if (hasTargetBounds) canvas.clipRect(targetBounds)
        if (reducedMotion) {
            canvas.drawPath(edgePath, staticPaint)
        } else {
            val shader = sweepGradient
            if (shader != null) {
                matrixTransform.reset()
                matrixTransform.setRotate(rotationAngle, gradientCenterX, gradientCenterY)
                shader.setLocalMatrix(matrixTransform)
                canvas.drawPath(edgePath, haloPaint)
                canvas.drawPath(edgePath, linePaint)
            }
        }
        canvas.restoreToCount(saveCount)
    }
}

/**
 * 屏幕任务专用顶部控制胶囊与展开面板。
 * 遵循 48dp 触控标准、24dp 卡片与 14dp 按钮圆角。
 */
class ScreenTaskCapsuleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var boundTaskId: String = ""
    private var boundSessionId: String = ""
    private var boundTurnId: String? = null
    private var boundStopToken: String? = null
    private var boundDisplayId: Int = 0

    private var isExpandedState: Boolean = false

    private var onToggleExpandListener: ((Boolean) -> Unit)? = null
    private var onStopCallback: ((taskId: String, sessionId: String, turnId: String?, stopToken: String?) -> Unit)? = null
    private var onTakeoverCallback: ((taskId: String, sessionId: String, turnId: String?, stopToken: String?, displayId: Int) -> Unit)? = null
    private var onOpenPipCallback: ((taskId: String, sessionId: String, turnId: String?, stopToken: String?, displayId: Int) -> Unit)? = null

    private val pillContainer: LinearLayout
    private val statusIndicatorDot: View
    private val statusTextView: TextView
    private val microLensFrame: FrameLayout
    private val lensFrameView: VirtualDisplayFrameView
    private val lensEdgeLightView: ScreenTaskEdgeLightView
    private val expandHintView: ImageView
    private val tempLensRect = RectF()
    private val tempCanvasRect = RectF()

    private val stageContainer: LinearLayout
    private val stageTitleView: TextView
    private val stageHeaderBadge: TextView
    private val stageCollapseButton: FrameLayout
    private val stageCollapseIconView: ImageView
    private val canvasWrapper: FrameLayout
    private val canvasFrameView: VirtualDisplayFrameView
    private val canvasEdgeLightView: ScreenTaskEdgeLightView
    private val stopButton: FrameLayout
    private val stopIconView: ImageView
    private val stopTextView: TextView
    private val takeoverButton: FrameLayout
    private val takeoverIconView: ImageView
    private val takeoverTextView: TextView
    private val pipButton: FrameLayout
    private val pipIconView: ImageView

    init {
        // 顶部胶囊收起态（48dp 触控标准高，柔和阴影，低饱和品牌 dot）
        pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dpF(context, 3f)
            minimumHeight = dp(context, 48)
            setPadding(dp(context, 14), 0, dp(context, 10), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleExpand(true) }
        }
        markAsAccessibilityButton(pillContainer, "展开虚拟屏小窗")

        statusIndicatorDot = View(context).apply {
            val size = dp(context, 8)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(context, 8)
            }
        }

        statusTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        microLensFrame = FrameLayout(context).apply {
            val lensW = dp(context, 18)
            val lensH = dp(context, 32)
            layoutParams = LinearLayout.LayoutParams(lensW, lensH).apply {
                marginStart = dp(context, 8)
            }
        }
        lensFrameView = VirtualDisplayFrameView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        lensEdgeLightView = ScreenTaskEdgeLightView(context)
        microLensFrame.addView(lensFrameView)
        microLensFrame.addView(lensEdgeLightView)
        lensFrameView.onFrameDrawn = ::updateLensEdgeLightBounds
        lensFrameView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) {
                updateLensEdgeLightBounds()
            }
        }
        expandHintView = ImageView(context).apply {
            val size = dp(context, 14)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(context, 6)
                marginEnd = dp(context, 2)
            }
        }

        pillContainer.addView(statusIndicatorDot)
        pillContainer.addView(statusTextView)
        pillContainer.addView(microLensFrame)
        pillContainer.addView(expandHintView)

        // 展开面板外壳（24dp 卡片圆角、16dp 水平边距、柔和阴影）
        stageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            elevation = dpF(context, 4f)
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 14))
            visibility = View.GONE
        }

        // 顶栏高 48dp，标题 17sp medium + 48dp 触控收起按钮
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
        }

        val stageTitleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 2)
            }
        }

        stageTitleView = TextView(context).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            text = "虚拟屏"
        }

        stageHeaderBadge = TextView(context).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        stageTitleLayout.addView(stageTitleView)
        stageTitleLayout.addView(stageHeaderBadge)

        stageCollapseButton = FrameLayout(context).apply {
            val btnSize = dp(context, 48)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            markAsAccessibilityButton(this, "收起")
            setOnClickListener { toggleExpand(false) }
        }
        stageCollapseIconView = ImageView(context).apply {
            val iconSize = dp(context, 20)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        stageCollapseButton.addView(stageCollapseIconView)

        headerRow.addView(stageTitleLayout)
        headerRow.addView(stageCollapseButton)

        canvasWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 336)
            ).apply {
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 12)
            }
            isClickable = true
            isFocusable = true
            markAsAccessibilityButton(this, "进入全屏独立观看")
            setOnClickListener {
                onOpenPipCallback?.invoke(boundTaskId, boundSessionId, boundTurnId, boundStopToken, boundDisplayId)
            }
        }
        canvasFrameView = VirtualDisplayFrameView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            contentDescription = "虚拟屏小窗画面"
        }
        canvasEdgeLightView = ScreenTaskEdgeLightView(context)
        canvasWrapper.addView(canvasFrameView)
        canvasWrapper.addView(canvasEdgeLightView)
        canvasFrameView.onFrameDrawn = ::updateCanvasEdgeLightBounds
        canvasFrameView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (l != ol || t != ot || r != or || b != ob) {
                updateCanvasEdgeLightBounds()
            }
        }
        // 控制栏：48dp 按钮，停止为中性底+error文字，移交主屏为 accent 黑白反差
        val controlBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val (stopFrame, stopIv, stopTv) = createLabeledButton(
            text = "停止",
            weight = 1f,
            accessibilityLabel = "停止任务"
        ) {
            onStopCallback?.invoke(boundTaskId, boundSessionId, boundTurnId, boundStopToken)
        }
        stopButton = stopFrame
        stopIconView = stopIv
        stopTextView = stopTv

        val (takeoverFrame, takeoverIv, takeoverTv) = createLabeledButton(
            text = "移交主屏",
            weight = 1.6f,
            accessibilityLabel = "移交主屏"
        ) {
            onTakeoverCallback?.invoke(boundTaskId, boundSessionId, boundTurnId, boundStopToken, boundDisplayId)
        }
        takeoverButton = takeoverFrame
        takeoverIconView = takeoverIv
        takeoverTextView = takeoverTv

        pipButton = FrameLayout(context).apply {
            val size = dp(context, 48)
            layoutParams = LinearLayout.LayoutParams(size, size)
            markAsAccessibilityButton(this, "全屏独立观看")
            setOnClickListener {
                onOpenPipCallback?.invoke(boundTaskId, boundSessionId, boundTurnId, boundStopToken, boundDisplayId)
            }
        }
        pipIconView = ImageView(context).apply {
            val iconSize = dp(context, 18)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        pipButton.addView(pipIconView)

        controlBar.addView(stopButton)
        addHorizontalSpacer(controlBar, 8)
        controlBar.addView(takeoverButton)
        addHorizontalSpacer(controlBar, 8)
        controlBar.addView(pipButton)

        stageContainer.addView(headerRow)
        stageContainer.addView(canvasWrapper)
        stageContainer.addView(controlBar)

        addView(pillContainer)
        addView(stageContainer)

        applyTheme()
    }

    private fun addHorizontalSpacer(parent: LinearLayout, dpValue: Int) {
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, dpValue), 1)
        }
        parent.addView(spacer)
    }

    private data class LabeledButtonParts(
        val frame: FrameLayout,
        val iconView: ImageView,
        val textView: TextView
    )

    private fun createLabeledButton(
        text: String,
        weight: Float,
        accessibilityLabel: String,
        onClick: () -> Unit
    ): LabeledButtonParts {
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 48), weight)
            markAsAccessibilityButton(this, accessibilityLabel)
            setOnClickListener { onClick() }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val iconView = ImageView(context).apply {
            val iconSize = dp(context, 16)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(context, 4)
            }
        }

        val textView = TextView(context).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
        }

        content.addView(iconView)
        content.addView(textView)
        frame.addView(content)
        return LabeledButtonParts(frame, iconView, textView)
    }

    fun initBinding(
        taskId: String,
        sessionId: String,
        turnId: String?,
        stopToken: String?,
        displayId: Int,
        statusText: String?,
        isWaiting: Boolean,
        onToggleExpand: (Boolean) -> Unit,
        onStop: (taskId: String, sessionId: String, turnId: String?, stopToken: String?) -> Unit,
        onTakeover: (taskId: String, sessionId: String, turnId: String?, stopToken: String?, displayId: Int) -> Unit,
        onOpenPip: (taskId: String, sessionId: String, turnId: String?, stopToken: String?, displayId: Int) -> Unit
    ) {
        this.boundTaskId = taskId
        this.boundSessionId = sessionId
        this.boundTurnId = turnId
        this.boundStopToken = stopToken
        this.boundDisplayId = displayId
        this.onToggleExpandListener = onToggleExpand
        this.onStopCallback = onStop
        this.onTakeoverCallback = onTakeover
        this.onOpenPipCallback = onOpenPip

        updateState(statusText, isWaiting, displayId, sessionId)
    }

    fun setTakeoverInProgress(inProgress: Boolean) {
        takeoverButton.isEnabled = !inProgress
        takeoverButton.alpha = if (inProgress) 0.5f else 1.0f
        stopButton.isEnabled = !inProgress
        stopButton.alpha = if (inProgress) 0.5f else 1.0f
    }

    fun updateState(statusText: String?, isWaiting: Boolean, displayId: Int, sessionId: String) {
        this.boundDisplayId = displayId
        this.boundSessionId = sessionId

        statusTextView.text = mapFriendlyStatus(statusText, isWaiting, displayId)

        val isNight = ScreenTaskTheme.isNight(context)
        val dotColor = if (isWaiting) ScreenTaskTheme.warningColor() else ScreenTaskTheme.brandPrimary(isNight)
        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor)
        }
        statusIndicatorDot.background = dotDrawable

        val hasVirtualDisplay = displayId > 0
        microLensFrame.visibility = if (hasVirtualDisplay) View.VISIBLE else View.GONE
        if (hasVirtualDisplay) {
            lensFrameView.bind(displayId, sessionId)
            lensFrameView.setPreviewActive(true)

            canvasFrameView.bind(displayId, sessionId)
            canvasFrameView.setPreviewActive(true)
            canvasWrapper.visibility = View.VISIBLE
            takeoverButton.visibility = View.VISIBLE
            pipButton.visibility = View.VISIBLE
            stageTitleView.text = "虚拟屏"
            stageHeaderBadge.text = if (isWaiting) "等待人工确认" else "协同运行中"

            updateLensEdgeLightBounds()
            updateCanvasEdgeLightBounds()

            if (!isWaiting) {
                lensEdgeLightView.start()
                canvasEdgeLightView.start()
            } else {
                lensEdgeLightView.stop()
                canvasEdgeLightView.stop()
            }
        } else {
            lensFrameView.setPreviewActive(false)
            canvasFrameView.setPreviewActive(false)
            lensEdgeLightView.stop()
            canvasEdgeLightView.stop()
            canvasWrapper.visibility = View.GONE
            takeoverButton.visibility = View.GONE
            pipButton.visibility = View.GONE
            stageTitleView.text = "屏幕控制"
            stageHeaderBadge.text = if (isWaiting) "等待人工确认" else "AI 正在接管"
        }

        applyTheme()
    }

    private fun toggleExpand(expanded: Boolean) {
        if (isExpandedState == expanded) return
        isExpandedState = expanded

        if (expanded) {
            pillContainer.visibility = View.GONE
            stageContainer.visibility = View.VISIBLE
            adaptCanvasHeight()
        } else {
            stageContainer.visibility = View.GONE
            pillContainer.visibility = View.VISIBLE
        }

        onToggleExpandListener?.invoke(expanded)
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpandedState != expanded) {
            toggleExpand(expanded)
        }
    }

    private fun adaptCanvasHeight() {
        val dm = resources.displayMetrics
        val screenHeightDp = dm.heightPixels / dm.density
        val maxH = (screenHeightDp - 150f).coerceIn(120f, 336f)
        val lp = canvasWrapper.layoutParams
        lp.height = dp(context, maxH)
        canvasWrapper.layoutParams = lp
    }
    private fun updateLensEdgeLightBounds() {
        lensFrameView.getContentRect(tempLensRect)
        lensEdgeLightView.setTargetRect(tempLensRect, cornerRadius = dpF(context, 4f))
    }

    private fun updateCanvasEdgeLightBounds() {
        canvasFrameView.getContentRect(tempCanvasRect)
        canvasEdgeLightView.setTargetRect(tempCanvasRect, cornerRadius = dpF(context, 10f))
    }

    fun getLensEdgeLight(): ScreenTaskEdgeLightView = lensEdgeLightView
    fun getCanvasEdgeLight(): ScreenTaskEdgeLightView = canvasEdgeLightView
    fun getCanvasWrapper(): FrameLayout = canvasWrapper


    fun applyTheme() {
        val isNight = ScreenTaskTheme.isNight(context)
        val surfaceBg = ScreenTaskTheme.cardSurfaceColor(context, isNight)
        val borderSubtle = ScreenTaskTheme.borderSubtle(isNight)
        val textPrimary = ScreenTaskTheme.textPrimary(isNight)
        val textSecondary = ScreenTaskTheme.textSecondary(isNight)
        val accentPrimary = ScreenTaskTheme.accentPrimary(isNight)
        val onAccent = ScreenTaskTheme.onAccent(isNight)
        val surfaceSec = ScreenTaskTheme.surfaceSecondary(isNight)
        val errorColor = ScreenTaskTheme.errorColor()

        // 胶囊外壳
        val pillBg = createRippleBackground(
            surfaceBg,
            ScreenTaskTheme.rippleColor(surfaceBg),
            dpF(context, 24f)
        )
        pillContainer.background = pillBg
        statusTextView.setTextColor(textPrimary)
        expandHintView.setImageDrawable(IconDrawables.createChevron(textSecondary, false))

        // 胶囊微缩镜头
        val lensBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 6f)
            setColor(0xFF141518.toInt())
            setStroke(dp(context, 1), borderSubtle)
        }
        microLensFrame.background = lensBg
        microLensFrame.clipToOutline = true

        // 展开面板外壳
        val stageBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 24f)
            setColor(surfaceBg)
            setStroke(dp(context, 1), borderSubtle)
        }
        stageContainer.background = stageBg
        stageTitleView.setTextColor(textPrimary)
        stageHeaderBadge.setTextColor(textSecondary)

        // 收起按钮
        stageCollapseButton.background = createRippleBackground(
            Color.TRANSPARENT,
            ScreenTaskTheme.rippleColor(surfaceBg),
            dpF(context, 24f)
        )
        stageCollapseIconView.setImageDrawable(IconDrawables.createChevron(textSecondary, true))

        // 画布包装器
        val canvasBg = createRippleBackground(
            0xFF101214.toInt(),
            ScreenTaskTheme.rippleColor(0xFF101214.toInt()),
            dpF(context, 14f)
        )
        canvasWrapper.background = canvasBg
        canvasWrapper.clipToOutline = true

        // 停止按钮（中性背景 + error 图标与文字）
        stopButton.background = createRippleBackground(
            surfaceSec,
            ScreenTaskTheme.rippleColor(surfaceSec),
            dpF(context, 14f)
        )
        stopIconView.setImageDrawable(IconDrawables.createStop(errorColor))
        stopTextView.setTextColor(errorColor)

        // 移交主屏按钮（accent 黑白反差 + onAccent 文字图标）
        takeoverButton.background = createRippleBackground(
            accentPrimary,
            ScreenTaskTheme.rippleColor(accentPrimary),
            dpF(context, 14f)
        )
        takeoverIconView.setImageDrawable(IconDrawables.createHandover(onAccent))
        takeoverTextView.setTextColor(onAccent)

        // 独立预览按钮（中性背景 + textPrimary 图标）
        pipButton.background = createRippleBackground(
            surfaceSec,
            ScreenTaskTheme.rippleColor(surfaceSec),
            dpF(context, 14f)
        )
        pipIconView.setImageDrawable(IconDrawables.createPip(textPrimary))
    }

    fun onConfigChanged() {
        applyTheme()
        adaptCanvasHeight()
        lensEdgeLightView.onConfigChanged()
        canvasEdgeLightView.onConfigChanged()
        updateLensEdgeLightBounds()
        updateCanvasEdgeLightBounds()
    }

    fun onScreenOff() {
        lensFrameView.setPreviewActive(false)
        canvasFrameView.setPreviewActive(false)
        lensEdgeLightView.stop()
        canvasEdgeLightView.stop()
    }

    fun cleanup() {
        lensFrameView.setPreviewActive(false)
        canvasFrameView.setPreviewActive(false)
        lensEdgeLightView.stop()
        canvasEdgeLightView.stop()
    }

    fun showToastOrStatus(message: String) {
        post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 屏幕任务终态结果卡片。
 * 遵循 24dp 卡片圆角、17sp medium 标题、复制移至标题右侧、底部两等宽操作按钮。
 */
class ScreenTaskResultCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private var boundTaskId: String = ""
    private var boundConversationId: Long? = null

    private val cardContainer: LinearLayout
    private val dragHandle: View
    private val statusBadge: FrameLayout
    private val statusIconView: ImageView
    private val statusTextView: TextView
    private val copyButton: FrameLayout
    private val copyIconView: ImageView
    private val contentScrollView: ScrollView
    private val contentTextView: TextView
    private val fadeBottomView: View
    private val stayButton: FrameLayout
    private val stayTextView: TextView
    private val returnChatButton: FrameLayout
    private val returnChatTextView: TextView

    private var onStayCallback: ((taskId: String) -> Unit)? = null
    private var onReturnChatCallback: ((taskId: String, conversationId: Long?) -> Unit)? = null
    private var currentMessageText: String = ""
    private var lastTerminalStatus: String = "completed"

    private var dragStartY = 0f
    private var currentDragY = 0f
    private val dismissThresholdPx = dpF(context, 72f)
    private var isExiting = false

    init {
        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dpF(context, 6f)
            setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 18))
            val dm = resources.displayMetrics
            val screenWidthDp = dm.widthPixels / dm.density
            val cardWidthDp = min(screenWidthDp - 20f, 420f)
            layoutParams = LayoutParams(
                dp(context, cardWidthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(context, 10)
            }
        }

        // 顶部中性色抓手 32x4dp
        dragHandle = View(context).apply {
            val hW = dp(context, 32)
            val hH = dp(context, 4)
            layoutParams = LinearLayout.LayoutParams(hW, hH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(context, 4)
                bottomMargin = dp(context, 10)
            }
        }

        // 标题栏：左侧状态徽标与 17sp medium primaryText，右侧 48dp 复制按钮
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 10)
            }
        }

        val titleContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBadge = FrameLayout(context).apply {
            val size = dp(context, 24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(context, 8)
            }
        }

        statusIconView = ImageView(context).apply {
            val iconSize = dp(context, 18)
            layoutParams = LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        statusBadge.addView(statusIconView)

        statusTextView = TextView(context).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        titleContent.addView(statusBadge)
        titleContent.addView(statusTextView)

        copyButton = FrameLayout(context).apply {
            val btnSize = dp(context, 48)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(context, 8)
            }
            markAsAccessibilityButton(this, "复制结果")
            setOnClickListener { copyToClipboard() }
        }

        copyIconView = ImageView(context).apply {
            val iconSize = dp(context, 18)
            layoutParams = LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        copyButton.addView(copyIconView)

        titleRow.addView(titleContent)
        titleRow.addView(copyButton)

        val contentBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 保持高度动态 clamp 于 onMeasure，不强制锁定 layoutParams.height
        contentScrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val dm = resources.displayMetrics
                val maxPx = dp(context, (dm.heightPixels / dm.density - 280f).coerceIn(100f, 220f))
                val mode = MeasureSpec.getMode(heightMeasureSpec)
                val size = MeasureSpec.getSize(heightMeasureSpec)
                val clampedHeight = if (mode == MeasureSpec.UNSPECIFIED) {
                    MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
                } else {
                    MeasureSpec.makeMeasureSpec(minOf(size, maxPx), MeasureSpec.AT_MOST)
                }
                super.onMeasure(widthMeasureSpec, clampedHeight)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isVerticalScrollBarEnabled = true
        }

        contentTextView = TextView(context).apply {
            textSize = 14f
            setLineSpacing(dpF(context, 4f), 1f)
            setPadding(0, 0, 0, dp(context, 12))
        }
        contentScrollView.addView(contentTextView)

        fadeBottomView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 24),
                Gravity.BOTTOM
            )
            visibility = View.GONE
        }

        contentBox.addView(contentScrollView)
        contentBox.addView(fadeBottomView)

        // 底部两等宽操作按钮：留在当前（secondary）与 返回小万（accentPrimary 黑白反差）
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 14)
            }
        }

        val (stayFrame, stayTv) = createActionButton("留在当前", "留在当前") {
            triggerExitWithAction { onStayCallback?.invoke(boundTaskId) }
        }
        stayButton = stayFrame
        stayTextView = stayTv

        val (returnFrame, returnTv) = createActionButton("返回小万", "返回小万") {
            triggerExitWithAction { onReturnChatCallback?.invoke(boundTaskId, boundConversationId) }
        }
        returnChatButton = returnFrame
        returnChatTextView = returnTv

        actionRow.addView(stayButton)
        val middleSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 10), 1)
        }
        actionRow.addView(middleSpacer)
        actionRow.addView(returnChatButton)

        cardContainer.addView(dragHandle)
        cardContainer.addView(titleRow)
        cardContainer.addView(contentBox)
        cardContainer.addView(actionRow)

        addView(cardContainer)
        setupDragAndTouch()
        applyTheme()
    }

    private fun createActionButton(
        text: String,
        accessibilityLabel: String,
        onClick: () -> Unit
    ): Pair<FrameLayout, TextView> {
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 48), 1f)
            markAsAccessibilityButton(this, accessibilityLabel)
            setOnClickListener { onClick() }
        }
        val textView = TextView(context).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        frame.addView(textView)
        return Pair(frame, textView)
    }

    private fun setupDragAndTouch() {
        cardContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = event.rawY
                    currentDragY = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - dragStartY
                    if (deltaY > 0) {
                        currentDragY = deltaY
                        cardContainer.translationY = currentDragY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (currentDragY > dismissThresholdPx) {
                        triggerExitWithAction { onStayCallback?.invoke(boundTaskId) }
                    } else {
                        cardContainer.animate()
                            .translationY(0f)
                            .setDuration(180)
                            .setInterpolator(OvershootInterpolator(1.1f))
                            .start()
                    }
                    true
                }
                else -> false
            }
        }

        contentScrollView.viewTreeObserver.addOnScrollChangedListener {
            val canScrollDown = contentScrollView.canScrollVertically(1)
            fadeBottomView.visibility = if (canScrollDown) View.VISIBLE else View.GONE
        }
    }

    fun bindResult(
        taskId: String,
        conversationId: Long?,
        terminalStatus: String,
        message: String,
        onStay: (taskId: String) -> Unit,
        onReturnChat: (taskId: String, conversationId: Long?) -> Unit
    ) {
        this.boundTaskId = taskId
        this.boundConversationId = conversationId
        this.onStayCallback = onStay
        this.onReturnChatCallback = onReturnChat
        this.currentMessageText = message.ifBlank { "本轮屏幕操作已结束，请返回对话查看结果。" }
        this.lastTerminalStatus = terminalStatus
        this.isExiting = false

        val isNight = ScreenTaskTheme.isNight(context)
        updateStatusBadgeAndIcon(terminalStatus, isNight)
        contentTextView.text = SimpleTextFormatter.format(currentMessageText, isNight)

        contentScrollView.post {
            val canScrollDown = contentScrollView.canScrollVertically(1)
            fadeBottomView.visibility = if (canScrollDown) View.VISIBLE else View.GONE
        }

        applyTheme()
        animateEnter()
    }

    fun updateMessage(updatedMessage: String) {
        if (updatedMessage.isNotBlank()) {
            this.currentMessageText = updatedMessage
            val isNight = ScreenTaskTheme.isNight(context)
            contentTextView.text = SimpleTextFormatter.format(updatedMessage, isNight)
            contentScrollView.post {
                val canScrollDown = contentScrollView.canScrollVertically(1)
                fadeBottomView.visibility = if (canScrollDown) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateStatusBadgeAndIcon(terminalStatus: String, isNight: Boolean) {
        this.lastTerminalStatus = terminalStatus
        val brandPrimary = ScreenTaskTheme.brandPrimary(isNight)
        val brandSurface = ScreenTaskTheme.brandSurface(isNight)
        val warningColor = ScreenTaskTheme.warningColor()
        val errorColor = ScreenTaskTheme.errorColor()

        val label: String
        val badgeBg: Int
        val icon: Drawable

        when (terminalStatus.lowercase()) {
            "completed", "end_turn", "success" -> {
                label = "操作已完成"
                badgeBg = brandSurface
                icon = IconDrawables.createCheck(brandPrimary)
            }
            "stop" -> {
                label = "操作已结束"
                badgeBg = brandSurface
                icon = IconDrawables.createCheck(brandPrimary)
            }
            "max_turn_requests", "max_tokens" -> {
                label = "操作达到上限"
                badgeBg = 0x26FF9F0A.toInt()
                icon = IconDrawables.createStop(warningColor)
            }
            "cancelled", "canceled", "user_cancelled", "interrupted" -> {
                label = "操作已取消"
                badgeBg = 0x26FF9F0A.toInt()
                icon = IconDrawables.createStop(warningColor)
            }
            "refusal" -> {
                label = "操作已拒绝"
                badgeBg = 0x26FF9F0A.toInt()
                icon = IconDrawables.createClose(warningColor)
            }
            else -> {
                label = "操作未完成"
                badgeBg = 0x26FF6464.toInt()
                icon = IconDrawables.createClose(errorColor)
            }
        }

        statusTextView.text = label
        statusBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 6f)
            setColor(badgeBg)
        }
        statusIconView.setImageDrawable(icon)
    }

    private fun copyToClipboard() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("屏幕操作结果", currentMessageText))
            Toast.makeText(context, "已复制结果", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            OmniLog.w("ScreenTaskResultCard", "复制到剪贴板失败: ${t.message}")
        }
    }

    private fun animateEnter() {
        cardContainer.translationY = dpF(context, 220f)
        cardContainer.alpha = 0f
        cardContainer.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
    }

    private fun triggerExitWithAction(action: () -> Unit) {
        if (isExiting) return
        isExiting = true
        cardContainer.animate()
            .translationY(dpF(context, 260f))
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    cardContainer.animate().setListener(null)
                    action()
                }
            })
            .start()
    }

    fun applyTheme() {
        val isNight = ScreenTaskTheme.isNight(context)
        val surfaceBg = ScreenTaskTheme.cardSurfaceColor(context, isNight)
        val borderSubtle = ScreenTaskTheme.borderSubtle(isNight)
        val textPrimary = ScreenTaskTheme.textPrimary(isNight)
        val textSecondary = ScreenTaskTheme.textSecondary(isNight)
        val surfaceSec = ScreenTaskTheme.surfaceSecondary(isNight)
        val accentPrimary = ScreenTaskTheme.accentPrimary(isNight)
        val onAccent = ScreenTaskTheme.onAccent(isNight)

        // 卡片背景与圆角
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 24f)
            setColor(surfaceBg)
            setStroke(dp(context, 1), borderSubtle)
        }
        cardContainer.background = cardBg

        // 顶部抓手中性色
        val handleColor = if (isNight) 0x33FFFFFF else 0x26000000
        dragHandle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(context, 2f)
            setColor(handleColor)
        }

        // 标题文字颜色始终为 primaryText
        statusTextView.setTextColor(textPrimary)

        // 复制按钮（圆形涟漪，中性背景 + textSecondary 图标）
        copyButton.background = createRippleBackground(
            surfaceSec,
            ScreenTaskTheme.rippleColor(surfaceSec),
            dpF(context, 24f)
        )
        copyIconView.setImageDrawable(IconDrawables.createCopy(textSecondary))

        // 底部操作按钮
        // 留在当前（中性次级按钮）
        stayButton.background = createRippleBackground(
            surfaceSec,
            ScreenTaskTheme.rippleColor(surfaceSec),
            dpF(context, 14f)
        )
        stayTextView.setTextColor(textPrimary)

        // 返回小万（accentPrimary 黑白反差 + onAccent 文字）
        returnChatButton.background = createRippleBackground(
            accentPrimary,
            ScreenTaskTheme.rippleColor(accentPrimary),
            dpF(context, 14f)
        )
        returnChatTextView.setTextColor(onAccent)

        // 内容文字与代码块背景样式
        contentTextView.setTextColor(textPrimary)
        if (currentMessageText.isNotEmpty()) {
            contentTextView.text = SimpleTextFormatter.format(currentMessageText, isNight)
        }

        // 滚动渐变遮罩
        val fadeColors = intArrayOf(Color.TRANSPARENT, surfaceBg)
        fadeBottomView.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, fadeColors)

        // 状态徽标与图标
        updateStatusBadgeAndIcon(lastTerminalStatus, isNight)
    }

    fun onConfigChanged() {
        val dm = resources.displayMetrics
        val screenWidthDp = dm.widthPixels / dm.density
        val cardWidthDp = min(screenWidthDp - 20f, 420f)
        val lp = cardContainer.layoutParams as? LayoutParams
        if (lp != null) {
            lp.width = dp(context, cardWidthDp)
            cardContainer.layoutParams = lp
        }

        // 保持 WRAP_CONTENT，由 onMeasure 动态 clamp，防止出现布局空白回归
        val slp = contentScrollView.layoutParams
        if (slp != null && slp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            slp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            contentScrollView.layoutParams = slp
        }

        applyTheme()
    }

    fun cleanup() {
        cardContainer.animate().setListener(null).cancel()
        onStayCallback = null
        onReturnChatCallback = null
        isExiting = false
    }

    override fun onDetachedFromWindow() {
        cleanup()
        super.onDetachedFromWindow()
    }

    internal fun getCurrentMessage(): String = currentMessageText
    internal fun getStatusText(): String = statusTextView.text.toString()
}

/**
 * 矢量图标 Drawable 集合。
 * 遵循 Lucide 极简描边风格与正确的主题颜色应用。
 * 支持 setTint / setColorFilter 与动态颜色同步，避免在深色主题下退化为黑色。
 */
internal object IconDrawables {

    private abstract class BasePathDrawable(
        protected var color: Int,
        protected val isStroke: Boolean = true,
        protected val strokeRatio: Float = 0.11f,
        protected val fill: Boolean = false
    ) : Drawable() {
        protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = this@BasePathDrawable.color
            style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        protected val path = Path()
        protected val sharedRect = RectF()

        open fun updateColor(newColor: Int) {
            this.color = newColor
            paint.color = newColor
            invalidateSelf()
        }

        override fun setTint(tintColor: Int) {
            updateColor(tintColor)
        }

        override fun setTintList(tint: ColorStateList?) {
            if (tint != null) {
                updateColor(tint.defaultColor)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    fun createCheck(color: Int): Drawable = object : BasePathDrawable(color) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            paint.strokeWidth = w * strokeRatio
            path.reset()
            path.moveTo(b.left + w * 0.22f, b.top + h * 0.52f)
            path.lineTo(b.left + w * 0.44f, b.top + h * 0.74f)
            path.lineTo(b.left + w * 0.78f, b.top + h * 0.28f)
            canvas.drawPath(path, paint)
        }
    }

    fun createClose(color: Int): Drawable = object : BasePathDrawable(color) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val pad = w * 0.25f
            paint.strokeWidth = w * strokeRatio
            canvas.drawLine(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad, paint)
            canvas.drawLine(b.right - pad, b.top + pad, b.left + pad, b.bottom - pad, paint)
        }
    }

    fun createStop(color: Int): Drawable = object : BasePathDrawable(color, fill = true) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val pad = w * 0.24f
            val r = w * 0.10f
            sharedRect.set(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
            canvas.drawRoundRect(sharedRect, r, r, paint)
        }
    }

    fun createChevron(color: Int, isUp: Boolean): Drawable = object : BasePathDrawable(color) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            paint.strokeWidth = w * strokeRatio
            path.reset()
            if (isUp) {
                path.moveTo(b.left + w * 0.26f, b.top + h * 0.60f)
                path.lineTo(b.left + w * 0.50f, b.top + h * 0.36f)
                path.lineTo(b.left + w * 0.74f, b.top + h * 0.60f)
            } else {
                path.moveTo(b.left + w * 0.26f, b.top + h * 0.40f)
                path.lineTo(b.left + w * 0.50f, b.top + h * 0.64f)
                path.lineTo(b.left + w * 0.74f, b.top + h * 0.40f)
            }
            canvas.drawPath(path, paint)
        }
    }

    fun createCopy(color: Int): Drawable = object : BasePathDrawable(color) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val r = w * 0.08f
            paint.strokeWidth = w * 0.09f
            sharedRect.set(b.left + w * 0.32f, b.top + w * 0.18f, b.left + w * 0.82f, b.top + w * 0.68f)
            canvas.drawRoundRect(sharedRect, r, r, paint)
            sharedRect.set(b.left + w * 0.18f, b.top + w * 0.32f, b.left + w * 0.68f, b.top + w * 0.82f)
            canvas.drawRoundRect(sharedRect, r, r, paint)
        }
    }

    fun createPip(color: Int): Drawable = object : BasePathDrawable(color) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        override fun updateColor(newColor: Int) {
            super.updateColor(newColor)
            fillPaint.color = newColor
        }

        override fun setAlpha(alpha: Int) {
            super.setAlpha(alpha)
            fillPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            super.setColorFilter(colorFilter)
            fillPaint.colorFilter = colorFilter
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val r = w * 0.08f
            paint.strokeWidth = w * 0.09f
            sharedRect.set(b.left + w * 0.16f, b.top + w * 0.22f, b.right - w * 0.16f, b.bottom - w * 0.22f)
            canvas.drawRoundRect(sharedRect, r, r, paint)
            sharedRect.set(b.left + w * 0.48f, b.top + w * 0.44f, b.right - w * 0.22f, b.bottom - w * 0.28f)
            canvas.drawRoundRect(sharedRect, r * 0.6f, r * 0.6f, fillPaint)
        }
    }

    fun createHandover(color: Int): Drawable = object : BasePathDrawable(color) {
        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            paint.strokeWidth = w * 0.10f
            val r = w * 0.08f
            sharedRect.set(b.left + w * 0.18f, b.top + h * 0.20f, b.right - w * 0.18f, b.bottom - h * 0.20f)
            canvas.drawRoundRect(sharedRect, r, r, paint)
            path.reset()
            path.moveTo(b.left + w * 0.42f, b.top + h * 0.40f)
            path.lineTo(b.left + w * 0.58f, b.top + h * 0.50f)
            path.lineTo(b.left + w * 0.42f, b.top + h * 0.60f)
            canvas.drawPath(path, paint)
        }
    }
}
