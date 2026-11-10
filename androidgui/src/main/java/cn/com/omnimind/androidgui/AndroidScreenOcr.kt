package cn.com.omnimind.androidgui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.BitSet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 基于 PaddleOCR 与 ONNX Runtime (com.microsoft.onnxruntime:onnxruntime-android:1.31.1) 的 Android 端侧屏幕 OCR 识别引擎。
 *
 * 【架构与模型契约】
 * - 检测模型 (det.onnx)：输入 [1, 3, H, W]（BGR Planar + ImageNet 归一化，宽高需为 32 的整数倍），输出 [1, 1, H, W] 文本概率图。
 * - 识别模型 (rec.onnx)：输入 [1, 3, 48, W]（BGR Planar + [-1, 1] 归一化，高度固定 48，宽度动态对齐），输出 [1, T, 18710] CTC 概率分布。
 * - 字典 (dict.txt)：18708 行字符，索引 0 为 CTC Blank，索引 1..18708 对应字典字符，索引 18709 对应空格。
 *
 * 【线程安全与并发控制】
 * - 绝不在主线程运行，默认切换至 Dispatchers.Default 执行。
 * - 采用统一内部状态互斥锁，严防 close() 在 recognize() 推理执行期间释放会话导致 Native Use-After-Free 崩溃。
 * - 关闭后调用明确抛出 IllegalStateException，绝不返回假成功数据。
 * - 严格保留并立即重抛 CancellationException，所有 OnnxTensor 与 Result 均由 use 块确保确定性释放。
 * - 输入 Bitmap 归调用方所有，内部绝不 recycle。
 */
class AndroidScreenOcr(
    private val context: Context,
    private val detModelAsset: String = "screen-ocr/det.onnx",
    private val recModelAsset: String = "screen-ocr/rec.onnx",
    private val dictAsset: String = "screen-ocr/dict.txt"
) : AndroidScreenOcrEngine, Closeable {

    private val stateLock = Any()
    @Volatile
    private var isClosed = false

    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictionary: List<String>? = null

    // 检测与识别超参数
    var detDbThresh: Float = 0.3f
    var detDbBoxThresh: Float = 0.5f
    var detDbUnclipRatio: Float = 1.6f
    var detMinSize: Int = 3
    var detMaxSideLen: Int = 960
    var recTargetHeight: Int = 48
    var recMinConfidence: Float = 0.35f

    /**
     * 对全屏或局部 Bitmap 执行文字检测与识别。
     * 输入 Bitmap 归调用方所有，内部绝不 recycle。
     * 自动在后台分发器 (Dispatchers.Default) 执行，保证主线程安全与会话复用。
     *
     * @param bitmap 待识别的屏幕图像
     * @return 识别出的文字项列表（包含原屏幕绝对像素 bounds、文字与置信度）
     */
    override suspend fun recognize(bitmap: Bitmap): List<ScreenOcrItem> = withContext(Dispatchers.Default) {
        check(!isClosed) { "AndroidScreenOcr 实例已关闭，无法执行识别" }
        require(!bitmap.isRecycled) { "传入的 Bitmap 已被回收，无法执行识别" }
        val inferenceContext = coroutineContext

        val origW = bitmap.width
        val origH = bitmap.height
        if (origW <= 0 || origH <= 0) {
            return@withContext emptyList()
        }

        synchronized(stateLock) {
            check(!isClosed) { "AndroidScreenOcr 实例已关闭" }
            inferenceContext.ensureActive()
            ensureLoadedLocked()

            val env = ortEnv ?: throw IllegalStateException("OrtEnvironment 未就绪")
            val det = detSession ?: throw IllegalStateException("detSession 未就绪")
            val rec = recSession ?: throw IllegalStateException("recSession 未就绪")
            val dict = dictionary ?: throw IllegalStateException("dictionary 未就绪")

            try {
                // 1. 检测预处理：计算 32 对齐尺度并缩放 (BGR Planar + ImageNet 归一化)
                val (detW, detH) = OcrAlgorithms.computeDetResizeDimensions(origW, origH, detMaxSideLen)
                var detBitmap: Bitmap? = null
                val probMap: FloatArray = try {
                    detBitmap = if (detW == origW && detH == origH) {
                        bitmap
                    } else {
                        Bitmap.createScaledBitmap(bitmap, detW, detH, true)
                    }

                    val detPixels = IntArray(detW * detH)
                    detBitmap.getPixels(detPixels, 0, detW, 0, 0, detW, detH)

                    // BGR Planar: Channel 0=B, Channel 1=G, Channel 2=R
                    val detBuffer = ByteBuffer.allocateDirect(3 * detW * detH * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()

                    val inv255 = 1.0f / 255.0f
                    // PaddleOCR NormalizeImage [0.485, 0.456, 0.406] / [0.229, 0.224, 0.225] 按通道位置 (0=B, 1=G, 2=R) 直接应用
                    val mean0 = 0.485f
                    val mean1 = 0.456f
                    val mean2 = 0.406f
                    val invStd0 = 1.0f / 0.229f
                    val invStd1 = 1.0f / 0.224f
                    val invStd2 = 1.0f / 0.225f
                    val totalDetPixels = detW * detH

                    // Channel 0: B
                    for (i in 0 until totalDetPixels) {
                        val p = detPixels[i]
                        val b = (p and 0xFF) * inv255
                        detBuffer.put((b - mean0) * invStd0)
                    }
                    // Channel 1: G
                    for (i in 0 until totalDetPixels) {
                        val p = detPixels[i]
                        val g = ((p ushr 8) and 0xFF) * inv255
                        detBuffer.put((g - mean1) * invStd1)
                    }
                    // Channel 2: R
                    for (i in 0 until totalDetPixels) {
                        val p = detPixels[i]
                        val r = ((p ushr 16) and 0xFF) * inv255
                        detBuffer.put((r - mean2) * invStd2)
                    }
                    detBuffer.rewind()

                    // 2. 执行检测推理
                    val detInputName = det.inputNames.first()
                    val detShape = longArrayOf(1L, 3L, detH.toLong(), detW.toLong())
                    OnnxTensor.createTensor(env, detBuffer, detShape).use { detTensor ->
                        det.run(mapOf(detInputName to detTensor)).use { result ->
                            val outTensor = result[0] as OnnxTensor
                            val fb = outTensor.floatBuffer
                            val map = FloatArray(fb.remaining())
                            fb.get(map)
                            map
                        }
                    }
                } finally {
                    if (detBitmap != null && detBitmap !== bitmap && !detBitmap.isRecycled) {
                        detBitmap.recycle()
                    }
                }

                // 4. 检测后处理：连通域过滤、Unclip 膨胀及原图坐标回映射
                val rawBoxes = OcrAlgorithms.extractConnectedComponents(
                    probMap = probMap,
                    mapW = detW,
                    mapH = detH,
                    origW = origW,
                    origH = origH,
                    thresh = detDbThresh,
                    boxThresh = detDbBoxThresh,
                    unclipRatio = detDbUnclipRatio,
                    minSize = detMinSize
                )

                if (rawBoxes.isEmpty()) {
                    return@withContext emptyList()
                }

                val sortedBoxes = OcrAlgorithms.sortBoxesReadingOrder(rawBoxes)
                val recognizedItems = ArrayList<ScreenOcrItem>(sortedBoxes.size)

                // 5. 原分辨率 Crop 与逐行识别
                val recInputName = rec.inputNames.first()
                val inv127_5 = 1.0f / 127.5f

                for (box in sortedBoxes) {
                    inferenceContext.ensureActive()
                    val cropW = box.right - box.left
                    val cropH = box.bottom - box.top
                    if (cropW < 2 || cropH < 2) continue

                    var cropBitmap: Bitmap? = null
                    var scaledCrop: Bitmap? = null
                    try {
                        // 在原图分辨率下裁剪目标文字块（注意：若裁剪区域与原图完全一致，createBitmap 可能返回 bitmap 本身）
                        cropBitmap = Bitmap.createBitmap(bitmap, box.left, box.top, cropW, cropH)
                        val (recW, recH) = OcrAlgorithms.computeRecDimensions(cropW, cropH, recTargetHeight)

                        scaledCrop = if (cropBitmap.width == recW && cropBitmap.height == recH) {
                            cropBitmap
                        } else {
                            Bitmap.createScaledBitmap(cropBitmap, recW, recH, true)
                        }

                        val cropPixels = IntArray(recW * recH)
                        scaledCrop.getPixels(cropPixels, 0, recW, 0, 0, recW, recH)

                        // 构造识别张量：BGR Planar + [-1, 1] 归一化 (Channel 0=B, Channel 1=G, Channel 2=R)
                        val recBuffer = ByteBuffer.allocateDirect(3 * recW * recH * 4)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer()
                        val totalCropPixels = recW * recH

                        // Channel 0: B
                        for (i in 0 until totalCropPixels) {
                            val p = cropPixels[i]
                            recBuffer.put((p and 0xFF) * inv127_5 - 1.0f)
                        }
                        // Channel 1: G
                        for (i in 0 until totalCropPixels) {
                            val p = cropPixels[i]
                            recBuffer.put(((p ushr 8) and 0xFF) * inv127_5 - 1.0f)
                        }
                        // Channel 2: R
                        for (i in 0 until totalCropPixels) {
                            val p = cropPixels[i]
                            recBuffer.put(((p ushr 16) and 0xFF) * inv127_5 - 1.0f)
                        }
                        recBuffer.rewind()

                        val recShape = longArrayOf(1L, 3L, recH.toLong(), recW.toLong())
                        val (text, conf) = OnnxTensor.createTensor(env, recBuffer, recShape).use { recTensor ->
                            rec.run(mapOf(recInputName to recTensor)).use { result ->
                                val outTensor = result[0] as OnnxTensor
                                val fb = outTensor.floatBuffer
                                OcrAlgorithms.decodeCtc(
                                    outBuffer = fb,
                                    numClasses = dict.size,
                                    dictionary = dict
                                )
                            }
                        }

                        if (text.isNotBlank() && conf >= recMinConfidence) {
                            recognizedItems.add(ScreenOcrItem(text = text, bounds = box, confidence = conf))
                        }
                    } finally {
                        if (scaledCrop != null && scaledCrop !== cropBitmap && scaledCrop !== bitmap && !scaledCrop.isRecycled) {
                            scaledCrop.recycle()
                        }
                        if (cropBitmap != null && cropBitmap !== bitmap && !cropBitmap.isRecycled) {
                            cropBitmap.recycle()
                        }
                    }
                }

                recognizedItems
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("AndroidScreenOcr 识别执行异常: ${e.message}", e)
            }
        }
    }

    private fun ensureLoadedLocked() {
        check(!isClosed) { "AndroidScreenOcr 已被关闭" }
        if (detSession != null && recSession != null && dictionary != null) return

        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnv = env

            val detBytes = context.assets.open(detModelAsset).use { it.readBytes() }
            val recBytes = context.assets.open(recModelAsset).use { it.readBytes() }
            val dictLines = context.assets.open(dictAsset).bufferedReader(Charsets.UTF_8).use { it.readLines() }

            OrtSession.SessionOptions().use { sessionOptions ->
                sessionOptions.setIntraOpNumThreads(2)
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                detSession = env.createSession(detBytes, sessionOptions)
                recSession = env.createSession(recBytes, sessionOptions)
            }

            val dict = ArrayList<String>(dictLines.size + 2).apply {
                add("") // 索引 0: CTC Blank
                addAll(dictLines) // 索引 1..18708
                add(" ") // 索引 18709: 空格
            }
            dictionary = dict
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { detSession?.close() }
            runCatching { recSession?.close() }
            detSession = null
            recSession = null
            ortEnv = null
            dictionary = null
            throw IllegalStateException("加载 AndroidScreenOcr 资产模型失败 (det=$detModelAsset, rec=$recModelAsset, dict=$dictAsset): ${e.message}", e)
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (isClosed) return
            isClosed = true
            try {
                runCatching { detSession?.close() }
                runCatching { recSession?.close() }
            } finally {
                detSession = null
                recSession = null
                ortEnv = null
                dictionary = null
            }
        }
    }
}

/**
 * 屏幕 OCR 核心算法工具对象。
 * 纯算法实现，不依赖特定 Android 运行时上下文，便于算法边界覆盖与单元测试。
 */
object OcrAlgorithms {

    /**
     * 计算检测模型输入尺寸（保持宽高比，最大边限制，且宽和高均按 32 步长向上/最近取整对齐）。
     */
    fun computeDetResizeDimensions(origW: Int, origH: Int, maxSide: Int = 960): Pair<Int, Int> {
        require(origW > 0 && origH > 0) { "原图尺寸必须大于 0: origW=$origW, origH=$origH" }
        val maxDimension = max(origW, origH)
        val ratio = if (maxDimension > maxSide) {
            maxSide.toFloat() / maxDimension.toFloat()
        } else {
            1.0f
        }
        val targetW = max(32, ((origW * ratio) / 32.0f).roundToInt() * 32)
        val targetH = max(32, ((origH * ratio) / 32.0f).roundToInt() * 32)
        return Pair(targetW, targetH)
    }

    /**
     * 计算识别模型输入尺寸（高度固定 targetH=48，宽度根据长宽比缩放并对齐到 4 的倍数）。
     */
    fun computeRecDimensions(
        cropW: Int,
        cropH: Int,
        targetH: Int = 48,
        minW: Int = 32,
        maxW: Int = 960
    ): Pair<Int, Int> {
        require(cropW > 0 && cropH > 0) { "裁剪尺寸必须大于 0: cropW=$cropW, cropH=$cropH" }
        val ratio = cropW.toFloat() / cropH.toFloat()
        val rawW = (targetH.toFloat() * ratio).roundToInt()
        val alignedW = max(minW, ((rawW + 3) / 4) * 4).coerceAtMost(maxW)
        return Pair(alignedW, targetH)
    }

    /**
     * 对检测概率图进行二值化连通域提取、平均分过滤、Unclip 膨胀及原图分辨率映射。
     */
    fun extractConnectedComponents(
        probMap: FloatArray,
        mapW: Int,
        mapH: Int,
        origW: Int,
        origH: Int,
        thresh: Float = 0.3f,
        boxThresh: Float = 0.5f,
        unclipRatio: Float = 1.6f,
        minSize: Int = 3
    ): List<Rect> {
        require(probMap.size >= mapW * mapH) { "概率图大小 (${probMap.size}) 与尺寸 ($mapW x $mapH) 不匹配" }
        val visited = BitSet(mapW * mapH)
        val queue = IntArray(mapW * mapH)
        val result = mutableListOf<Rect>()

        val scaleX = origW.toDouble() / mapW.toDouble()
        val scaleY = origH.toDouble() / mapH.toDouble()

        for (y in 0 until mapH) {
            val rowOffset = y * mapW
            for (x in 0 until mapW) {
                val idx = rowOffset + x
                if (probMap[idx] < thresh || visited.get(idx)) continue

                // 开始 8 邻域 BFS
                visited.set(idx)
                var head = 0
                var tail = 0
                queue[tail++] = idx

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var sumScore = 0.0
                var count = 0

                while (head < tail) {
                    val curr = queue[head++]
                    val cy = curr / mapW
                    val cx = curr % mapW
                    sumScore += probMap[curr]
                    count++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny < 0 || ny >= mapH) continue
                        val nRow = ny * mapW
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            if (nx < 0 || nx >= mapW) continue
                            val nIdx = nRow + nx
                            if (probMap[nIdx] >= thresh && !visited.get(nIdx)) {
                                visited.set(nIdx)
                                queue[tail++] = nIdx
                            }
                        }
                    }
                }

                val avgScore = (sumScore / count).toFloat()
                if (avgScore < boxThresh) continue

                val boxW = maxX - minX + 1
                val boxH = maxY - minY + 1
                if (boxW < minSize || boxH < minSize) continue

                // Unclip 膨胀
                val unclipped = computeUnclippedBox(minX, minY, maxX, maxY, mapW, mapH, unclipRatio)

                // 映射回原图坐标
                val mappedRect = mapDetBoxToOriginal(unclipped, scaleX, scaleY, origW, origH)
                if (mappedRect.right > mappedRect.left && mappedRect.bottom > mappedRect.top) {
                    result.add(mappedRect)
                }
            }
        }

        return result
    }

    /**
     * Vatti Unclip 膨胀距离计算。
     */
    fun computeUnclippedBox(
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        mapW: Int,
        mapH: Int,
        unclipRatio: Float = 1.6f
    ): IntArray {
        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1
        val area = boxW.toDouble() * boxH.toDouble()
        val perimeter = 2.0 * (boxW + boxH)
        val distance = (area * unclipRatio) / perimeter

        val uMinX = (minX - distance).roundToInt().coerceIn(0, mapW - 1)
        val uMinY = (minY - distance).roundToInt().coerceIn(0, mapH - 1)
        val uMaxX = (maxX + distance).roundToInt().coerceIn(0, mapW - 1)
        val uMaxY = (maxY + distance).roundToInt().coerceIn(0, mapH - 1)
        return intArrayOf(uMinX, uMinY, uMaxX, uMaxY)
    }

    /**
     * 将检测特征图上的包围盒回映射至原图绝对像素坐标系。
     */
    fun mapDetBoxToOriginal(
        unclipped: IntArray,
        scaleX: Double,
        scaleY: Double,
        origW: Int,
        origH: Int
    ): Rect {
        val uMinX = unclipped[0]
        val uMinY = unclipped[1]
        val uMaxX = unclipped[2]
        val uMaxY = unclipped[3]

        val origLeft = (uMinX * scaleX).roundToInt().coerceIn(0, origW - 1)
        val origTop = (uMinY * scaleY).roundToInt().coerceIn(0, origH - 1)
        val origRight = ((uMaxX + 1) * scaleX).roundToInt().coerceIn(origLeft + 1, origW)
        val origBottom = ((uMaxY + 1) * scaleY).roundToInt().coerceIn(origTop + 1, origH)

        return Rect(origLeft, origTop, origRight, origBottom)
    }

    /**
     * CTC 贪婪解码与置信度计算。
     * - 索引 0: CTC Blank（忽略）
     * - 连续重复字符合并（除非中间被 Blank 隔开）
     * - 索引映射至 dictionary
     * - 最终置信度为非 Blank 字符概率均值
     */
    fun decodeCtc(
        outBuffer: FloatBuffer,
        numClasses: Int,
        dictionary: List<String>
    ): Pair<String, Float> {
        val totalElements = outBuffer.remaining()
        if (totalElements == 0 || numClasses <= 0) return Pair("", 0.0f)
        val timeSteps = totalElements / numClasses
        if (timeSteps <= 0) return Pair("", 0.0f)

        val resultText = StringBuilder()
        var sumConf = 0.0
        var confCount = 0
        var lastIndex = 0

        for (t in 0 until timeSteps) {
            var maxIdx = 0
            var maxProb = -Float.MAX_VALUE

            for (c in 0 until numClasses) {
                val prob = outBuffer.get()
                if (prob > maxProb) {
                    maxProb = prob
                    maxIdx = c
                }
            }

            // CTC 规则：跳过 Blank (0)；去重相同连续字符
            if (maxIdx != 0 && maxIdx != lastIndex) {
                if (maxIdx in 1 until dictionary.size) {
                    resultText.append(dictionary[maxIdx])
                    sumConf += maxProb
                    confCount++
                }
            }
            lastIndex = maxIdx
        }

        val text = resultText.toString().trim()
        val confidence = if (confCount > 0) (sumConf / confCount).toFloat() else 0.0f
        return Pair(text, confidence)
    }

    /**
     * 按屏幕自然阅读顺序（从上至下、同水平行从左至右）对包围盒进行排序。
     */
    fun sortBoxesReadingOrder(boxes: List<Rect>): List<Rect> {
        if (boxes.size <= 1) return boxes
        return boxes.sortedWith { b1, b2 ->
            val h1 = b1.bottom - b1.top
            val h2 = b2.bottom - b2.top
            val lineThreshold = max(4, min(h1, h2) / 2)
            if (kotlin.math.abs(b1.top - b2.top) <= lineThreshold) {
                b1.left.compareTo(b2.left)
            } else {
                b1.top.compareTo(b2.top)
            }
        }
    }
}
