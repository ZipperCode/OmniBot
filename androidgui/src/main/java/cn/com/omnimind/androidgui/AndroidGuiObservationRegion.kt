package cn.com.omnimind.androidgui

import android.graphics.Rect

/**
 * 观测区域辅助类（归一化 0..1000 全屏坐标）。
 * 成组字段必须齐全且面积大于 0，越界或格式不符严格拒绝，不推测补全。
 */
data class AndroidGuiObservationRegion(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
) {
    init {
        require(x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite()) {
            "region coordinates must be finite: x1=$x1, y1=$y1, x2=$x2, y2=$y2"
        }
        require(x1 in 0.0..1000.0 && y1 in 0.0..1000.0 && x2 in 0.0..1000.0 && y2 in 0.0..1000.0) {
            "region coordinates out of bounds [0, 1000]: x1=$x1, y1=$y1, x2=$x2, y2=$y2"
        }
        require(x2 > x1 && y2 > y1) {
            "region area must be positive: x1=$x1, y1=$y1, x2=$x2, y2=$y2"
        }
    }

    /**
     * 将 0..1000 归一化坐标转换为对应屏幕分辨率的像素 Rect。
     */
    fun toRect(displayWidth: Int, displayHeight: Int): Rect {
        require(displayWidth > 0 && displayHeight > 0) {
            "display dimensions must be positive: width=$displayWidth, height=$displayHeight"
        }
        val left = (x1 / 1000.0 * displayWidth).toInt().coerceIn(0, displayWidth - 1)
        val top = (y1 / 1000.0 * displayHeight).toInt().coerceIn(0, displayHeight - 1)
        val right = (x2 / 1000.0 * displayWidth).toInt().coerceIn(left + 1, displayWidth)
        val bottom = (y2 / 1000.0 * displayHeight).toInt().coerceIn(top + 1, displayHeight)
        return Rect(left, top, right, bottom)
    }

    fun toNormalizedList(): List<Double> = listOf(x1, y1, x2, y2)

    companion object {
        /**
         * 从 Map 构建区域对象。支持成组 {x1, y1, x2, y2} 或 bounds: [x1, y1, x2, y2]。
         * 若字段不全或类型错误则抛出异常；若入参整体为 null 或空则返回 null。
         */
        fun fromMap(value: Map<String, Any?>?): AndroidGuiObservationRegion? {
            if (value == null || value.isEmpty()) return null
            val rawBounds = value["bounds"] as? List<*>
            if (rawBounds != null) {
                require(rawBounds.size == 4) { "region bounds list must contain exactly 4 coordinates" }
                val coords = rawBounds.map { coord ->
                    (coord as? Number)?.toDouble()
                        ?: throw IllegalArgumentException("region bound coordinate must be numeric: $coord")
                }
                return AndroidGuiObservationRegion(coords[0], coords[1], coords[2], coords[3])
            }

            fun requireCoord(key: String): Double {
                val raw = value[key] ?: throw IllegalArgumentException("region coordinate '$key' is required")
                val num = (raw as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("region coordinate '$key' must be numeric: $raw")
                return num
            }

            return AndroidGuiObservationRegion(
                x1 = requireCoord("x1"),
                y1 = requireCoord("y1"),
                x2 = requireCoord("x2"),
                y2 = requireCoord("y2"),
            )
        }
    }
}
