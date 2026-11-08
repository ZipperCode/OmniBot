package cn.com.omnimind.bot.assistant

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import java.nio.charset.StandardCharsets

/**
 * 屏幕辅助结构（AssistStructure）提取器。
 * 规则：
 * 1. 遍历可见节点最多 2000 个；
 * 2. 最大深度限制 32；
 * 3. UTF-8 文本大小上限 32 KiB（32768 字节），超出显式截断标记；
 * 4. 密码与受保护节点直接跳过整个子树（不提取密码及敏感信息），不绕过 FLAG_SECURE；
 * 5. 无可见有效文本时返回“当前应用未提供屏幕内容”。
 */
object AssistStructureExtractor {

    const val MAX_NODES = 2000
    const val MAX_DEPTH = 32
    const val MAX_UTF8_BYTES = 32 * 1024 // 32 KiB
    const val NO_CONTENT_TEXT = "当前应用未提供屏幕内容"
    const val TRUNCATION_MARKER_NODES = "\n[已达到最大节点限制(2000)，内容已截断]"
    const val TRUNCATION_MARKER_BYTES = "\n[已达到最大文本大小限制(32KiB)，内容已截断]"
    const val DEPTH_LIMIT_MARKER = "[达到最大深度限制(32)]"

    data class ExtractionResult(
        val text: String,
        val nodeCount: Int,
        val hasContent: Boolean,
        val isTruncated: Boolean,
    )

    interface AssistNode {
        val className: String?
        val text: CharSequence?
        val contentDescription: CharSequence?
        val hint: CharSequence?
        val isVisible: Boolean
        val isPassword: Boolean
        val isAssistBlocked: Boolean
        val childCount: Int
        fun getChildAt(index: Int): AssistNode?
    }

    class ViewNodeAssistNode(private val viewNode: AssistStructure.ViewNode) : AssistNode {
        override val className: String? get() = viewNode.className
        override val text: CharSequence? get() = viewNode.text
        override val contentDescription: CharSequence? get() = viewNode.contentDescription
        override val hint: CharSequence? get() = viewNode.hint
        override val isVisible: Boolean get() = viewNode.visibility == View.VISIBLE
        override val isAssistBlocked: Boolean get() = viewNode.isAssistBlocked
        override val isPassword: Boolean
            get() = isPasswordInputType(viewNode.inputType) || hasPasswordAutofillHint(viewNode.autofillHints)

        override val childCount: Int get() = viewNode.childCount
        override fun getChildAt(index: Int): AssistNode? {
            val child = viewNode.getChildAt(index) ?: return null
            return ViewNodeAssistNode(child)
        }
    }

    fun extract(structure: AssistStructure?): ExtractionResult {
        if (structure == null || structure.windowNodeCount == 0) {
            return ExtractionResult(
                text = NO_CONTENT_TEXT,
                nodeCount = 0,
                hasContent = false,
                isTruncated = false,
            )
        }

        val roots = mutableListOf<AssistNode>()
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i) ?: continue
            val root = window.rootViewNode ?: continue
            roots.add(ViewNodeAssistNode(root))
        }

        return extractFromRoots(roots)
    }

    fun extractFromRoots(roots: List<AssistNode>): ExtractionResult {
        var totalNodes = 0
        var isTruncated = false
        val buffer = StringBuilder()
        val truncationBytesReserved = TRUNCATION_MARKER_BYTES.toByteArray(StandardCharsets.UTF_8).size
        var currentBytes = 0

        fun appendLineChecked(line: String): Boolean {
            val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size
            if (currentBytes + lineBytes > MAX_UTF8_BYTES - truncationBytesReserved) {
                isTruncated = true
                return false
            }
            buffer.append(line)
            currentBytes += lineBytes
            return true
        }

        fun traverse(node: AssistNode, depth: Int): Boolean {
            if (!node.isVisible || node.isPassword || node.isAssistBlocked) {
                return true
            }

            totalNodes++
            if (totalNodes > MAX_NODES) {
                isTruncated = true
                return false
            }

            val indent = "  ".repeat(depth.coerceAtMost(MAX_DEPTH))
            if (depth > MAX_DEPTH) {
                val depthLine = "$indent$DEPTH_LIMIT_MARKER\n"
                if (!appendLineChecked(depthLine)) {
                    return false
                }
                return true
            }

            val classSimpleName = node.className?.substringAfterLast('.')?.ifBlank { null } ?: "View"
            val textStr = node.text?.toString()?.trim()?.ifBlank { null }
            val descStr = node.contentDescription?.toString()?.trim()?.ifBlank { null }
            val hintStr = node.hint?.toString()?.trim()?.ifBlank { null }

            val hasMeaningfulContent = textStr != null || descStr != null || hintStr != null
            if (hasMeaningfulContent) {
                val nodeLine = buildString {
                    append(indent)
                    append("[$classSimpleName]")
                    if (descStr != null) {
                        append(" desc=\"").append(descStr).append("\"")
                    }
                    if (hintStr != null) {
                        append(" hint=\"").append(hintStr).append("\"")
                    }
                    if (textStr != null) {
                        append(" text=\"").append(textStr).append("\"")
                    }
                    append("\n")
                }
                if (!appendLineChecked(nodeLine)) {
                    return false
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) ?: continue
                val continueTraverse = traverse(child, depth + 1)
                if (!continueTraverse) {
                    return false
                }
            }
            return true
        }

        for (root in roots) {
            val continueTraverse = traverse(root, 0)
            if (!continueTraverse) {
                break
            }
        }

        if (totalNodes > MAX_NODES) {
            buffer.append(TRUNCATION_MARKER_NODES)
            isTruncated = true
        } else if (isTruncated) {
            buffer.append(TRUNCATION_MARKER_BYTES)
        }

        val resultText = buffer.toString().trim()
        val hasContent = resultText.isNotBlank() && resultText != NO_CONTENT_TEXT

        return ExtractionResult(
            text = if (hasContent) resultText else NO_CONTENT_TEXT,
            nodeCount = totalNodes,
            hasContent = hasContent,
            isTruncated = isTruncated,
        )
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val classMask = inputType and InputType.TYPE_MASK_CLASS
        val variationMask = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        val isTextPassword = classMask == InputType.TYPE_CLASS_TEXT && (
            variationMask == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) ||
            variationMask == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            variationMask == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        )
        val isNumberPassword = classMask == InputType.TYPE_CLASS_NUMBER && (
            variationMask == (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        )
        return isTextPassword || isNumberPassword
    }

    private fun hasPasswordAutofillHint(hints: Array<String>?): Boolean {
        if (hints == null) return false
        return hints.any { hint ->
            hint.equals("password", ignoreCase = true) ||
            hint.contains("password", ignoreCase = true) ||
            hint.contains("pin", ignoreCase = true)
        }
    }
}
