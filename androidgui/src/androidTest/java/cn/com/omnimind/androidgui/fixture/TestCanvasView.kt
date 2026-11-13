package cn.com.omnimind.androidgui.fixture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 纯像素/颜色/数字绘制的 Canvas View
 * 严格不导出任何可读子节点或辅助功能树文本，专门用于验证视觉感知与无障碍盲区检测。
 */
class TestCanvasView(context: Context) : View(context) {

    private val colors = intArrayOf(
        Color.rgb(0x21, 0x96, 0xF3), // 蓝色
        Color.rgb(0x4C, 0xAF, 0x50), // 绿色
        Color.rgb(0xFF, 0x98, 0x00), // 橙色
        Color.rgb(0x9C, 0x27, 0xB0), // 紫色
        Color.rgb(0xE9, 0x1E, 0x63), // 粉红
    )

    private val numbers = intArrayOf(10, 42, 88, 128, 256)

    var stateIndex: Int = 0
        private set

    var onStateChangedListener: ((Int) -> Unit)? = null

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 64f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener {
            stateIndex = (stateIndex + 1) % colors.size
            invalidate()
            onStateChangedListener?.invoke(stateIndex)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        backgroundPaint.color = colors[stateIndex]
        canvas.drawRoundRect(0f, 0f, w, h, 24f, 24f, backgroundPaint)

        val displayText = numbers[stateIndex].toString()
        val fontMetrics = textPaint.fontMetrics
        val baseline = (h - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(displayText, w / 2f, baseline, textPaint)
    }
}
