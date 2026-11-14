package cn.com.omnimind.bot.agent.workspace

/**
 * 纯函数标准 Unified Diff 生成器。
 *
 * 保证：
 * - 截取单个变化区并附带 3 行上下文。
 * - 格式与 git/patch 标准 unified diff 兼容。
 * - 超过 5000 行或 256KiB 时显式截断（标注 truncated），仅用于 UI 展示，绝不用于文件恢复。
 */
object WorkspaceUnifiedDiff {
    private const val MAX_DIFF_LINES = 5000
    private const val MAX_DIFF_BYTES = 256 * 1024 // 256 KiB
    private const val CONTEXT_LINES = 3

    fun generate(before: String, after: String, relativePath: String): String {
        if (before == after) return ""

        val relPath = relativePath.trim().replace('\\', '/')
        val beforeLines = if (before.isEmpty()) emptyList() else splitLines(before)
        val afterLines = if (after.isEmpty()) emptyList() else splitLines(after)

        val beforeCount = beforeLines.size
        val afterCount = afterLines.size

        // 1. 计算共同前缀行数
        var prefix = 0
        while (prefix < beforeCount && prefix < afterCount && beforeLines[prefix] == afterLines[prefix]) {
            prefix++
        }

        // 2. 计算共同后缀行数（不与前缀重叠）
        var suffix = 0
        while (suffix < (beforeCount - prefix) && suffix < (afterCount - prefix) &&
            beforeLines[beforeCount - 1 - suffix] == afterLines[afterCount - 1 - suffix]
        ) {
            suffix++
        }

        val beforeChangeStart = prefix
        val beforeChangeEnd = beforeCount - suffix
        val afterChangeStart = prefix
        val afterChangeEnd = afterCount - suffix

        val contextBeforeStart = maxOf(0, prefix - CONTEXT_LINES)
        val contextBeforeCount = prefix - contextBeforeStart

        val contextAfterCount = minOf(CONTEXT_LINES, suffix)
        val beforeContextAfterEnd = beforeChangeEnd + contextAfterCount
        val afterContextAfterEnd = afterChangeEnd + contextAfterCount

        val hunkBeforeStartLine = if (beforeCount == 0) 0 else contextBeforeStart + 1
        val hunkBeforeLineCount = (beforeChangeEnd - contextBeforeStart) + contextAfterCount

        val hunkAfterStartLine = if (afterCount == 0) 0 else contextBeforeStart + 1
        val hunkAfterLineCount = (afterChangeEnd - contextBeforeStart) + contextAfterCount

        val headerBefore = if (beforeCount == 0) "/dev/null" else "a/$relPath"
        val headerAfter = if (afterCount == 0) "/dev/null" else "b/$relPath"

        val sb = StringBuilder()
        sb.append("--- ").append(headerBefore).append('\n')
        sb.append("+++ ").append(headerAfter).append('\n')
        sb.append("@@ -").append(hunkBeforeStartLine).append(',').append(hunkBeforeLineCount)
            .append(" +").append(hunkAfterStartLine).append(',').append(hunkAfterLineCount)
            .append(" @@\n")

        var totalLines = 3 // 头部 3 行

        // 前导上下文
        for (i in contextBeforeStart until prefix) {
            if (checkTruncation(sb, totalLines)) return finalizeTruncated(sb)
            sb.append(' ').append(beforeLines[i]).append('\n')
            totalLines++
        }

        // 删除行
        for (i in beforeChangeStart until beforeChangeEnd) {
            if (checkTruncation(sb, totalLines)) return finalizeTruncated(sb)
            sb.append('-').append(beforeLines[i]).append('\n')
            totalLines++
        }

        // 新增行
        for (i in afterChangeStart until afterChangeEnd) {
            if (checkTruncation(sb, totalLines)) return finalizeTruncated(sb)
            sb.append('+').append(afterLines[i]).append('\n')
            totalLines++
        }

        // 后续上下文
        for (i in 0 until contextAfterCount) {
            if (checkTruncation(sb, totalLines)) return finalizeTruncated(sb)
            val lineIndex = beforeChangeEnd + i
            if (lineIndex < beforeCount) {
                sb.append(' ').append(beforeLines[lineIndex]).append('\n')
                totalLines++
            }
        }

        return sb.toString().trimEnd()
    }

    private fun splitLines(text: String): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        val len = text.length
        while (start < len) {
            var end = start
            while (end < len && text[end] != '\n' && text[end] != '\r') {
                end++
            }
            lines.add(text.substring(start, end))
            if (end < len && text[end] == '\r') {
                end++
            }
            if (end < len && text[end] == '\n') {
                end++
            }
            start = end
        }
        return lines
    }

    private fun checkTruncation(sb: StringBuilder, currentLines: Int): Boolean {
        return currentLines >= MAX_DIFF_LINES || sb.length >= MAX_DIFF_BYTES
    }

    private fun finalizeTruncated(sb: StringBuilder): String {
        sb.append("\n... [diff truncated: exceeds display limit]")
        return sb.toString()
    }
}
