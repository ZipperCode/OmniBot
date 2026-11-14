package cn.com.omnimind.bot.agent.skill

/**
 * 文本脱敏结果。
 * [text] 脱敏后的安全文本。
 * [sensitiveDetected] 是否命中凭据、密钥或私钥等严重敏感模式。
 */
data class SanitizedText(
    val text: String,
    val sensitiveDetected: Boolean
)

/**
 * 共享技能与工作区快照纯函数脱敏器。
 * 提取自 SelfImprovingSkillFailureHook 的私有正则，并补充 PEM、URL 认证凭据、邮箱与电话号码掩码。
 */
object SkillSuggestionRedactor {
    // 1. 键值形式凭据分配（含 token, password, apiKey 等）
    private val SENSITIVE_ASSIGNMENT = Regex(
        "(?i)([\\\"']?(?:(?:x[-_])?api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|secret|password|authorization|cookie|credential|private[_-]?key)[\\\"']?\\s*[:=]\\s*)([\\\"'][^\\\"'\\n]*[\\\"']|[^\\s,}]+)"
    )

    // 2. HTTP Bearer 认证令牌
    private val BEARER_TOKEN = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")

    // 3. OpenAI / Anthropic 风格凭据前缀
    private val OPENAI_STYLE_KEY = Regex("\\bsk-[A-Za-z0-9_-]{12,}\\b")

    // 4. PEM 证书与私钥块
    private val PEM_BLOCK = Regex("-----BEGIN [A-Z0-9_ -]+-----([\\s\\S]*?)-----END [A-Z0-9_ -]+-----")

    // 5. URL userinfo (例如 https://user:password@host)
    private val URL_USERINFO = Regex("(?i)([a-z][a-z0-9+.-]*://)([^\\s/:@]+:[^\\s/:@]+)@")

    // 6. URL 查询参数认证键值
    private val URL_QUERY_CREDENTIAL = Regex(
        "(?i)([?&](?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|secret|password|auth|signature|sig)=)([^&\\s]+)"
    )

    // 7. 邮箱地址掩码
    private val EMAIL_PATTERN = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")

    // 8. 电话号码掩码
    private val PHONE_PATTERN = Regex("(?:\\+?86[- ]?)?1[3-9]\\d{9}\\b|\\b\\+?\\d{1,3}[- ]?\\(?\\d{2,4}\\)?[- ]?\\d{3,4}[- ]?\\d{4}\\b")

    fun sanitize(text: String): SanitizedText {
        if (text.isBlank()) return SanitizedText(text, false)

        var detected = false
        var result = text

        // 检查并脱敏 PEM 块
        if (PEM_BLOCK.containsMatchIn(result)) {
            detected = true
            result = PEM_BLOCK.replace(result, "[REDACTED_PEM_BLOCK]")
        }

        // 检查并脱敏 URL userinfo
        if (URL_USERINFO.containsMatchIn(result)) {
            detected = true
            result = URL_USERINFO.replace(result) { match ->
                "${match.groupValues[1]}[REDACTED_USERINFO]@"
            }
        }

        // 检查并脱敏 URL query 凭据
        if (URL_QUERY_CREDENTIAL.containsMatchIn(result)) {
            detected = true
            result = URL_QUERY_CREDENTIAL.replace(result) { match ->
                "${match.groupValues[1]}[REDACTED]"
            }
        }

        // 检查并脱敏键值敏感分配
        if (SENSITIVE_ASSIGNMENT.containsMatchIn(result)) {
            detected = true
            result = SENSITIVE_ASSIGNMENT.replace(result) { match ->
                "${match.groupValues[1]}\"[REDACTED]\""
            }
        }

        // 检查并脱敏 Bearer 令牌
        if (BEARER_TOKEN.containsMatchIn(result)) {
            detected = true
            result = BEARER_TOKEN.replace(result, "Bearer [REDACTED]")
        }

        // 检查并脱敏 sk-* 凭据
        if (OPENAI_STYLE_KEY.containsMatchIn(result)) {
            detected = true
            result = OPENAI_STYLE_KEY.replace(result, "[REDACTED_KEY]")
        }

        // 替换邮箱占位符
        if (EMAIL_PATTERN.containsMatchIn(result)) {
            result = EMAIL_PATTERN.replace(result, "[EMAIL]")
        }

        // 替换电话号码占位符
        if (PHONE_PATTERN.containsMatchIn(result)) {
            result = PHONE_PATTERN.replace(result, "[PHONE]")
        }

        return SanitizedText(result, detected)
    }

    /**
     * 兼容原 SelfImprovingSkillFailureHook 签名的纯脱敏函数。
     */
    fun redact(text: String): String = sanitize(text).text
}
