package cn.com.omnimind.androidgui

import android.graphics.Bitmap

/**
 * 屏幕 OCR 引擎抽象接口。
 * 生产环境默认委托给真实端侧 PaddleOCR 引擎 [AndroidScreenOcr]，纯单元测试可通过构造直接注入。
 */
fun interface AndroidScreenOcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<ScreenOcrItem>
}
