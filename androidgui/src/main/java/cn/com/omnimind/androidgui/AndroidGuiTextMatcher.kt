package cn.com.omnimind.androidgui

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import android.icu.lang.UCharacter
import java.text.Normalizer

/**
 * Android GUI 文字与可见性规范化匹配辅助。
 *
 * 规范化流程：
 * 1. NFKC 形式兼容分解与重组。
 * 2. 连续空白字符归一化为单个半角空格并去除首尾空白。
 * 3. 语言无关 full casefold 转换（android.icu.lang.UCharacter.foldCase）。
 */
internal object AndroidGuiTextMatcher {

    private val WHITESPACE_REGEX = Regex("[\\s\\p{Z}\\u0085]+")
    private val PARSER_FACTORY: XmlPullParserFactory by lazy {
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }
    }

    /**
     * 对文本进行 NFKC 规范化、Unicode 空白归一化与 casefold 折叠。
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val whitespaceNormalized = nfkc.replace(WHITESPACE_REGEX, " ").trim()
        return UCharacter.foldCase(whitespaceNormalized, true)
    }

    /**
     * 在 XML 树中匹配目标文本。
     *
     * 匹配规则：
     * - 仅遍历可见且非密码子树：若任一祖先节点 visible-to-user/visible/displayed="false" 或 password="true"，整棵子树均被遮蔽。
     * - 匹配 node 的 text 或 content-desc。
     * - 完整解析到 END_DOCUMENT，遇到 XML 格式错误返回 null。
     *
     * @return true 找到目标；false XML有效但未找到；null XML格式非法、空树或完全无可见有效节点
     */
    fun checkTextPresence(xml: String, targetDescription: String): Boolean? {
        if (xml.isBlank()) return null
        val normalizedTarget = normalize(targetDescription)
        if (normalizedTarget.isEmpty()) return null

        var hasValidNode = false
        var targetFound = false

        try {
            val parser = PARSER_FACTORY.newPullParser()
            parser.setInput(StringReader(xml))

            // 维护当前祖先链的可见性与密码状态栈
            val visibilityStack = ArrayDeque<Boolean>()
            val passwordStack = ArrayDeque<Boolean>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            val parentVisible = visibilityStack.lastOrNull() ?: true
                            val parentPassword = passwordStack.lastOrNull() ?: false

                            val visibleAttr = parser.getAttributeValue(null, "visible-to-user") != "false" &&
                                parser.getAttributeValue(null, "visible") != "false" &&
                                parser.getAttributeValue(null, "displayed") != "false"
                            val passwordAttr = parser.getAttributeValue(null, "password") == "true"

                            val isVisible = parentVisible && visibleAttr
                            val isPassword = parentPassword || passwordAttr

                            visibilityStack.addLast(isVisible)
                            passwordStack.addLast(isPassword)

                            val text = parser.getAttributeValue(null, "text")
                            val contentDesc = parser.getAttributeValue(null, "content-desc")
                            val resId = parser.getAttributeValue(null, "resource-id")

                            if (isVisible && !isPassword && (!text.isNullOrBlank() || !contentDesc.isNullOrBlank() || !resId.isNullOrBlank())) {
                                hasValidNode = true
                            }

                            if (isVisible && !isPassword) {
                                if (text != null && normalize(text) == normalizedTarget) {
                                    targetFound = true
                                } else if (contentDesc != null && normalize(contentDesc) == normalizedTarget) {
                                    targetFound = true
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "node") {
                            if (visibilityStack.isNotEmpty()) visibilityStack.removeLast()
                            if (passwordStack.isNotEmpty()) passwordStack.removeLast()
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            return null
        }

        if (!hasValidNode) return null
        return targetFound
    }

    /**
     * 判断 XML 树是否包含有效且可见的非密码节点（具备文字、描述或 resource-id）。
     * 完整解析至 END_DOCUMENT，若后段存在 malformed XML 语法错误将捕获并判定为 false（无效树）。
     */
    fun hasUsableVisibleNodes(xml: String): Boolean {
        if (xml.isBlank()) return false
        return try {
            val parser = PARSER_FACTORY.newPullParser()
            parser.setInput(StringReader(xml))
            val visibilityStack = ArrayDeque<Boolean>()
            val passwordStack = ArrayDeque<Boolean>()
            var eventType = parser.eventType
            var found = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "node") {
                            val parentVisible = visibilityStack.lastOrNull() ?: true
                            val parentPassword = passwordStack.lastOrNull() ?: false

                            val visibleAttr = parser.getAttributeValue(null, "visible-to-user") != "false" &&
                                parser.getAttributeValue(null, "visible") != "false" &&
                                parser.getAttributeValue(null, "displayed") != "false"
                            val passwordAttr = parser.getAttributeValue(null, "password") == "true"

                            val isVisible = parentVisible && visibleAttr
                            val isPassword = parentPassword || passwordAttr

                            visibilityStack.addLast(isVisible)
                            passwordStack.addLast(isPassword)

                            val text = parser.getAttributeValue(null, "text")
                            val contentDesc = parser.getAttributeValue(null, "content-desc")
                            val resId = parser.getAttributeValue(null, "resource-id")
                            if (isVisible && !isPassword && (!text.isNullOrBlank() || !contentDesc.isNullOrBlank() || !resId.isNullOrBlank())) {
                                found = true
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "node") {
                            if (visibilityStack.isNotEmpty()) visibilityStack.removeLast()
                            if (passwordStack.isNotEmpty()) passwordStack.removeLast()
                        }
                    }
                }
                eventType = parser.next()
            }
            found
        } catch (_: Exception) {
            false
        }
    }
}
