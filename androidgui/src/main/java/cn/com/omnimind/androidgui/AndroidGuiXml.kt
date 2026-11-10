package cn.com.omnimind.androidgui

import android.graphics.Rect
import android.os.Build
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import java.io.StringWriter
data class AndroidGuiWindowMetadata(
    val windowId: Int,
    val layer: Int? = null,
    val type: Int? = null,
    val bounds: Rect? = null,
    val title: String? = null,
    val isActive: Boolean? = null,
    val isFocused: Boolean? = null,
)

internal object AndroidGuiXml {
    fun serialize(
        root: AccessibilityNodeInfo?,
        windowMetadata: Map<Int, AndroidGuiWindowMetadata> = emptyMap(),
    ): String = serialize(listOfNotNull(root), windowMetadata)

    fun serialize(
        roots: List<AccessibilityNodeInfo>,
        windowMetadata: Map<Int, AndroidGuiWindowMetadata> = emptyMap(),
    ): String {
        if (roots.isEmpty()) return ""
        val output = StringWriter()
        val serializer = Xml.newSerializer().apply {
            setOutput(output)
            startDocument("UTF-8", true)
            startTag(null, "hierarchy")
        }
        var nodeId = 0

        fun attribute(name: String, value: Any?) {
            val text = sanitize(value?.toString())
            if (text.isNotEmpty()) serializer.attribute(null, name, text)
        }

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || (depth > 0 && !node.isVisibleToUser)) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val windowId = runCatching { node.windowId }.getOrDefault(0)
            val windowMeta = windowMetadata[windowId]
            serializer.startTag(null, "node")
            serializer.attribute(null, "id", (nodeId++).toString())
            serializer.attribute(null, "window-id", windowId.toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val drawingOrder = runCatching { node.drawingOrder }.getOrNull()
                if (drawingOrder != null) {
                    serializer.attribute(null, "drawing-order", drawingOrder.toString())
                }
            }
            if (windowMeta?.layer != null) {
                serializer.attribute(null, "window-layer", windowMeta.layer.toString())
            }
            if (windowMeta?.type != null) {
                serializer.attribute(null, "window-type", windowMeta.type.toString())
            }
            if (depth == 0 && windowMeta?.bounds != null) {
                val wb = windowMeta.bounds
                serializer.attribute(null, "window-bounds", "[${wb.left},${wb.top}][${wb.right},${wb.bottom}]")
            }
            attribute("text", node.text)
            attribute("content-desc", node.contentDescription)
            attribute("hint-text", node.hintText)
            attribute("resource-id", node.viewIdResourceName)
            attribute("class", node.className)
            attribute("package", node.packageName)
            attribute("clickable", node.isClickable)
            attribute("long-clickable", node.isLongClickable)
            attribute("focusable", node.isFocusable)
            attribute("focused", node.isFocused)
            attribute("scrollable", node.isScrollable)
            attribute("editable", node.isEditable)
            attribute("selected", node.isSelected)
            attribute("enabled", node.isEnabled)
            attribute("checkable", node.isCheckable)
            attribute("checked", node.isChecked)
            attribute("password", node.isPassword)
            attribute("visible-to-user", node.isVisibleToUser)
            serializer.attribute(
                null,
                "bounds",
                "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
            )
            for (index in 0 until node.childCount) {
                val child = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        node.getChild(
                            index,
                            AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
                                AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        node.getChild(index)
                    }
                }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
            serializer.endTag(null, "node")
        }

        try {
            roots.forEach { root -> visit(root, 0) }
            serializer.endTag(null, "hierarchy")
            serializer.endDocument()
        } finally {
            roots.asReversed().forEach { root ->
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
        return output.toString()
    }

    private fun sanitize(value: String?): String = value.orEmpty().replace(INVALID_XML, "")

    private const val MAX_DEPTH = 50
    private val INVALID_XML = Regex("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD]")
}
