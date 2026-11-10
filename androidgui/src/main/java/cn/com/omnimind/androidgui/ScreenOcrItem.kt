package cn.com.omnimind.androidgui

import android.graphics.Rect

/**
 * 屏幕 OCR 识别单项数据类。
 *
 * @property text 识别出的文本内容
 * @property bounds 原始屏幕像素坐标下的包围盒 [left, top, right, bottom]
 * @property confidence 识别置信度 (0.0 .. 1.0)
 */
data class ScreenOcrItem(
    val text: String,
    val bounds: Rect,
    val confidence: Float
)
