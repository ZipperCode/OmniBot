package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.skill.SkillSuggestionRedactor

/**
 * 校验通过的技能候选内容数据载体。
 */
internal data class ValidatedSkillCandidate(
    val name: String,
    val description: String,
    val bodyMarkdown: String
)

private val KEBAB_CASE_NAME_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")

private val FORBIDDEN_PRIVILEGE_PATTERNS = Regex(
    "(?i)\\b(root_all|grant_all|bypass_permission|skip_approval|root-all|grant-all|bypass-permission|skip-approval|skip_permissions?|skip-permissions?|bypass_permissions?|bypass-permissions?|grant_permissions?|grant-permissions?|bypass_approval|bypass-approval|grant_all_permissions|grant-all-permissions|root_permissions?|root-permissions?)\\b"
)

private val FORBIDDEN_FRONTMATTER_KEYS = setOf(
    "root_all", "root-all", "grant_all", "grant-all",
    "bypass_permission", "bypass-permission", "bypass_permissions", "bypass-permissions",
    "skip_approval", "skip-approval", "bypass_approval", "bypass-approval",
    "skip_permission", "skip-permission", "skip_permissions", "skip-permissions",
    "grant_permission", "grant-permission", "grant_permissions", "grant-permissions",
    "grant_all_permissions", "grant-all-permissions",
    "permission", "permissions", "privilege", "privileges", "privileged",
    "install_state", "install-state", "installstate",
    "source",
    "verification_status", "verification-status", "verificationstatus",
    "revision_id", "revision-id", "revisionid",
    "previous_revision_id", "previous-revision-id", "previousrevisionid",
    "identity", "install_identity", "install-identity", "installidentity"
)

private val EXPLICIT_TOOL_FIELD_REGEX = Regex(
    "(?i)\\b(?:tool|tool_name|toolName)\\b\\s*[:=]\\s*[\"']?([a-zA-Z0-9_-]+)[\"']?"
)

private val EXPLICIT_TOOL_CALL_REGEX = Regex(
    "(?i)\\b(?:call_tool|execute_tool|invoke_tool|run_tool)\\s*\\(\\s*[\"']?([a-zA-Z0-9_-]+)[\"']?"
)

private val TOOL_CALL_TAG_REGEX = Regex(
    "(?i)<tool_call>\\s*(?:\\{[^}]*\"name\"\\s*:\\s*\"([a-zA-Z0-9_-]+)\"|([a-zA-Z0-9_-]+))"
)

private val BACKTICK_TOOL_PREFIX_REGEX = Regex(
    "(?i)(?:\\b(?:use|call|run|execute|invoke)?\\s*tools?\\s*[:：]?|(?:使用|调用|运行|执行)?\\s*工具\\s*[:：]?)\\s*[`]\\s*([a-zA-Z0-9_-]+)\\s*[`]"
)

private val BACKTICK_TOOL_SUFFIX_REGEX = Regex(
    "(?i)[`]\\s*([a-zA-Z0-9_-]+)\\s*[`]\\s*(?:tools?\\b|工具)"
)

private val ALLOWED_TOOLS_DECLARATION_REGEX = Regex(
    "(?i)\\ballowed[-_]tools?\\b[^\\S\\r\\n]*[:=][^\\S\\r\\n]*(?:\\[([^\\]]+)\\]|([^\\n\\r]+))"
)

private fun isIgnoredToolLiteral(candidate: String): Boolean {
    val lower = candidate.lowercase()
    return lower == "none" || lower == "null" || lower == "true" || lower == "false" || lower == "empty"
}

private fun extractExplicitTools(text: String): Set<String> {
    val result = linkedSetOf<String>()

    EXPLICIT_TOOL_FIELD_REGEX.findAll(text).forEach { match ->
        val candidate = match.groupValues[1].trim('`')
        if (candidate.isNotEmpty() && !isIgnoredToolLiteral(candidate)) {
            result.add(candidate)
        }
    }

    EXPLICIT_TOOL_CALL_REGEX.findAll(text).forEach { match ->
        val candidate = match.groupValues[1].trim('`')
        if (candidate.isNotEmpty() && !isIgnoredToolLiteral(candidate)) {
            result.add(candidate)
        }
    }

    TOOL_CALL_TAG_REGEX.findAll(text).forEach { match ->
        val candidate = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim('`')
        if (candidate.isNotEmpty() && !isIgnoredToolLiteral(candidate)) {
            result.add(candidate)
        }
    }

    BACKTICK_TOOL_PREFIX_REGEX.findAll(text).forEach { match ->
        val candidate = match.groupValues[1]
        if (candidate.isNotEmpty() && !isIgnoredToolLiteral(candidate)) {
            result.add(candidate)
        }
    }

    BACKTICK_TOOL_SUFFIX_REGEX.findAll(text).forEach { match ->
        val candidate = match.groupValues[1]
        if (candidate.isNotEmpty() && !isIgnoredToolLiteral(candidate)) {
            result.add(candidate)
        }
    }

    ALLOWED_TOOLS_DECLARATION_REGEX.findAll(text).forEach { match ->
        val listContent = match.groupValues[1].ifEmpty { match.groupValues[2] }
        Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*").findAll(listContent).forEach { itemMatch ->
            val candidate = itemMatch.value.trim('`', '-', ' ', '"', '\'')
            if (candidate.isNotEmpty() && candidate.any { it.isLetterOrDigit() } && !isIgnoredToolLiteral(candidate)) {
                result.add(candidate)
            }
        }
    }

    return result
}

private fun isPrivilegeOrIdentityKey(key: String): Boolean {
    val lower = key.lowercase()
    return lower in FORBIDDEN_FRONTMATTER_KEYS ||
        lower.contains("root") ||
        lower.contains("permission") ||
        lower.contains("privilege") ||
        lower.contains("approval") ||
        lower.contains("install") ||
        lower.contains("verification") ||
        lower.contains("revision")
}

/**
 * 校验技能候选内容（适用于提炼解析、手动保存、审核启用等入口）。
 *
 * @param name 技能名称，必须为全小写 kebab-case，长度 <= 80，不自动转小写。
 * @param description 技能描述，单行文本，修剪后长度 1..240。
 * @param bodyMarkdown 技能正文 Markdown，修剪后长度 1..12000。
 * @param knownToolNames 已注册/已知有效工具名集合（来自 AgentToolDefinitions.reservedToolNames 及原任务成功调用的工具）。
 * @throws IllegalArgumentException 当校验不通过时抛出携带 safe 错误说明的异常。
 */
internal fun validateSkillCandidateContent(
    name: String,
    description: String,
    bodyMarkdown: String,
    knownToolNames: Set<String>
): ValidatedSkillCandidate {
    // 1. 技能名称校验：必须严格为小写 kebab-case，不可静默 lowercasing，长度 <= 80
    if (name.length !in 1..80 || !KEBAB_CASE_NAME_REGEX.matches(name)) {
        throw IllegalArgumentException("skill_validation_failed: 技能名称必须严格为全小写 kebab-case 连字符命名且长度在 1..80 字符内: $name")
    }

    // 2. 技能描述校验：trimmed 1..240，必须为单行
    val trimmedDesc = description.trim()
    if (trimmedDesc.isEmpty() || trimmedDesc.length > 240) {
        throw IllegalArgumentException("skill_validation_failed: 技能描述长度必须在 1..240 字符内，当前长度为 ${trimmedDesc.length}")
    }
    if (trimmedDesc.contains('\n') || trimmedDesc.contains('\r')) {
        throw IllegalArgumentException("skill_validation_failed: 技能描述必须为单行文本，不可包含换行符")
    }

    // 3. 技能正文校验：trimmed 1..12000
    val trimmedBody = bodyMarkdown.trim()
    if (trimmedBody.isEmpty() || trimmedBody.length > 12000) {
        throw IllegalArgumentException("skill_validation_failed: 技能正文长度必须在 1..12000 字符内，当前长度为 ${trimmedBody.length}")
    }

    // 4. 敏感凭据脱敏拦截与邮箱/手机号占位掩码
    val combinedAllFields = "$name\n$trimmedDesc\n$trimmedBody"
    val combinedSanitized = SkillSuggestionRedactor.sanitize(combinedAllFields)
    if (combinedSanitized.sensitiveDetected) {
        throw IllegalArgumentException("skill_validation_failed: 内容中检测到敏感凭据、密钥或私钥信息，已被安全拦截")
    }

    val descSanitized = SkillSuggestionRedactor.sanitize(trimmedDesc)
    val bodySanitized = SkillSuggestionRedactor.sanitize(trimmedBody)
    if (descSanitized.sensitiveDetected || bodySanitized.sensitiveDetected) {
        throw IllegalArgumentException("skill_validation_failed: 内容中检测到敏感凭据、密钥或私钥信息，已被安全拦截")
    }

    val cleanDesc = descSanitized.text.trim()
    val cleanBody = bodySanitized.text.trim()

    if (cleanDesc.isEmpty() || cleanDesc.length > 240) {
        throw IllegalArgumentException("skill_validation_failed: 技能描述脱敏后长度必须在 1..240 字符内")
    }
    if (cleanDesc.contains('\n') || cleanDesc.contains('\r')) {
        throw IllegalArgumentException("skill_validation_failed: 技能描述脱敏后必须为单行文本")
    }
    if (cleanBody.isEmpty() || cleanBody.length > 12000) {
        throw IllegalArgumentException("skill_validation_failed: 技能正文脱敏后长度必须在 1..12000 字符内")
    }

    // 5. 虚假特权与跳过审批声明拦截（校验显式声明而非任意自然语言臆断）
    if (FORBIDDEN_PRIVILEGE_PATTERNS.containsMatchIn(cleanDesc)) {
        throw IllegalArgumentException("skill_validation_failed: 技能描述包含未授权的特权或跳过权限审批声明")
    }
    if (FORBIDDEN_PRIVILEGE_PATTERNS.containsMatchIn(cleanBody)) {
        throw IllegalArgumentException("skill_validation_failed: 技能正文包含未授权的特权或跳过权限审批声明")
    }

    // 6. Frontmatter 结构校验与已有解析器对接
    var parsedFrontmatter: Map<String, String> = emptyMap()
    if (cleanBody.startsWith("---")) {
        val closingIndex = cleanBody.indexOf("\n---", startIndex = 3)
        if (closingIndex < 0) {
            throw IllegalArgumentException("skill_validation_failed: frontmatter 格式错误，缺少闭合标记 ---")
        }

        val afterClosing = cleanBody.substring(closingIndex + 4)
        val firstNewline = afterClosing.indexOf('\n')
        val closingLineRest = if (firstNewline >= 0) afterClosing.substring(0, firstNewline) else afterClosing
        if (closingLineRest.trim().isNotEmpty()) {
            throw IllegalArgumentException("skill_validation_failed: frontmatter 闭合标记格式无效")
        }

        val frontmatterText = cleanBody.substring(3, closingIndex).trim('\n', '\r')
        for (line in frontmatterText.lines()) {
            val trimmedLine = line.trimEnd()
            if (trimmedLine.isBlank()) continue
            if (line.startsWith(" ") || line.startsWith("\t")) continue
            if (!Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").matches(trimmedLine)) {
                throw IllegalArgumentException("skill_validation_failed: frontmatter 包含格式错误的声明行: ${trimmedLine.take(40)}")
            }
        }

        val parsed = parseSkillContent(cleanBody)
            ?: throw IllegalArgumentException("skill_validation_failed: 无法解析技能 frontmatter")
        parsedFrontmatter = parsed.frontmatter

        val contentBody = parsed.body.trim()
        if (contentBody.isEmpty()) {
            throw IllegalArgumentException("skill_validation_failed: 技能正文不能为空")
        }

        // 校验禁止特权及安装身份字段
        for (key in parsedFrontmatter.keys) {
            if (isPrivilegeOrIdentityKey(key)) {
                throw IllegalArgumentException("skill_validation_failed: frontmatter 不得包含权限或安装身份声明: $key")
            }
        }

        // 校验名称不一致
        val fmName = parsedFrontmatter["name"]?.trim()?.trim('"', '\'')
        if (fmName != null && fmName != name) {
            throw IllegalArgumentException("skill_validation_failed: frontmatter 中的 name ($fmName) 与技能名称 ($name) 不一致")
        }

        val fmId = (parsedFrontmatter["id"] ?: parsedFrontmatter["target_skill_id"])?.trim()?.trim('"', '\'')
        if (fmId != null && fmId != name) {
            throw IllegalArgumentException("skill_validation_failed: frontmatter 中的 ID ($fmId) 与技能名称 ($name) 不一致")
        }

        // 校验描述不一致
        val fmDesc = parsedFrontmatter["description"]?.trim()?.trim('"', '\'')
        if (fmDesc != null && fmDesc != cleanDesc) {
            throw IllegalArgumentException("skill_validation_failed: frontmatter 中的 description 与技能描述不一致")
        }
    }

    // 7. 工具合法性校验：使用外部提供的 knownToolNames，严禁硬编码第二份工具表
    // 7a. frontmatter 中声明的工具
    val frontmatterTools = mutableListOf<String>()
    for ((key, value) in parsedFrontmatter) {
        if (key.lowercase() in setOf("allowed-tools", "allowed_tools", "tools", "tool")) {
            Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*").findAll(value).forEach { m ->
                val tool = m.value.trim('`', '-', ' ', '"', '\'')
                if (tool.isNotEmpty() && tool.any { it.isLetterOrDigit() } && !isIgnoredToolLiteral(tool)) {
                    frontmatterTools.add(tool)
                }
            }
        }
    }
    for (tool in frontmatterTools) {
        if (tool !in knownToolNames) {
            throw IllegalArgumentException("skill_validation_failed: frontmatter 声明了未知工具: $tool")
        }
    }

    // 7b. 正文/描述中显式声明的工具
    val explicitContentTools = extractExplicitTools(cleanDesc) + extractExplicitTools(cleanBody)
    for (tool in explicitContentTools) {
        if (tool !in knownToolNames) {
            throw IllegalArgumentException("skill_validation_failed: 检测到未注册的工具引用: $tool")
        }
    }

    return ValidatedSkillCandidate(
        name = name,
        description = cleanDesc,
        bodyMarkdown = cleanBody
    )
}

/**
 * 组装审核通过后的规范技能文件内容。
 * 使用目标 [skillId] 作为规范名称，[candidate.description] 作为规范描述，保留良性 frontmatter，过滤特权/身份注入字段。
 *
 * @param skillId 目标技能 ID（必须为小写 kebab-case，长度 <= 80）。
 * @param candidate 已通过纯函数校验的技能候选内容。
 * @return 组装完成的 SKILL.md 完整文本内容。
 */
internal fun buildReviewedSkillContent(
    skillId: String,
    candidate: ValidatedSkillCandidate
): String {
    if (skillId.length !in 1..80 || !KEBAB_CASE_NAME_REGEX.matches(skillId)) {
        throw IllegalArgumentException("skill_validation_failed: 目标技能 ID 必须严格为小写 kebab-case 且长度不超过 80: $skillId")
    }

    val parsed = parseSkillContent(candidate.bodyMarkdown)
    val existingFm = parsed?.frontmatter ?: emptyMap()
    val bodyText = parsed?.body?.trim() ?: candidate.bodyMarkdown.trim()

    val benignEntries = existingFm.filterKeys { key ->
        val lower = key.lowercase()
        lower != "name" &&
        lower != "description" &&
        lower != "id" &&
        lower != "target_skill_id" &&
        lower != "targetskillid" &&
        !isPrivilegeOrIdentityKey(lower)
    }

    return buildString {
        appendLine("---")
        appendLine("name: $skillId")
        appendLine("description: ${candidate.description}")
        for ((key, value) in benignEntries) {
            if (value.contains('\n')) {
                appendLine("$key:")
                for (line in value.lines()) {
                    appendLine("  $line")
                }
            } else {
                appendLine("$key: $value")
            }
        }
        appendLine("---")
        appendLine()
        append(bodyText)
    }
}
