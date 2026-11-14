package cn.com.omnimind.bot.agent

import kotlinx.coroutines.CancellationException

internal class SkillAssetManagementException(
    val code: String,
    message: String,
) : IllegalStateException(message)

private val candidateIdPattern = Regex("^cand_[a-f0-9]{16}$")
private val skillIdPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
private val revisionIdPattern = Regex("^[a-f0-9]{64}$")
private val skillAssetErrors = setOf(
    "skill_asset_invalid", "skill_candidate_not_found", "skill_revision_conflict",
    "skill_name_conflict", "skill_cannot_overwrite_builtin", "skill_content_conflict",
    "skill_content_modified", "skill_feedback_ineligible", "skill_revision_retired",
    "skill_validation_failed", "skill_provider_changed", "skill_generation_busy",
    "skill_disabled", "skill_source_unavailable", "skill_context_unavailable",
)

/** 两端只做调用适配；资格、CAS 和文件变更仍由资产服务负责。 */
internal suspend fun SkillSuggestionService.manageAssets(
    operation: String,
    arguments: Map<String, Any?>,
): Map<String, Any?> {
    fun text(key: String): String = (arguments[key] as? String)
        ?.takeIf(String::isNotBlank)
        ?: throw SkillAssetManagementException("skill_validation_failed", "缺少字段：$key")
    fun id(key: String, pattern: Regex): String = text(key).also {
        if (!pattern.matches(it)) {
            throw SkillAssetManagementException("skill_validation_failed", "标识格式无效：$key")
        }
    }
    fun long(key: String): Long {
        val number = arguments[key] as? Number
            ?: throw SkillAssetManagementException("skill_validation_failed", "缺少数值字段：$key")
        val value = number.toDouble()
        if (!value.isFinite() || value < 1 || value >= Long.MAX_VALUE.toDouble() || value % 1.0 != 0.0) {
            throw SkillAssetManagementException("skill_validation_failed", "数值字段无效：$key")
        }
        return number.toLong()
    }
    fun page(key: String, default: Int, max: Int): Int {
        val number = arguments[key] ?: return default
        val value = (number as? Number)?.toDouble()
            ?: throw SkillAssetManagementException("skill_validation_failed", "分页字段无效：$key")
        val minimum = if (key == "limit") 1 else 0
        if (!value.isFinite() || value < minimum || value > max || value % 1.0 != 0.0) {
            throw SkillAssetManagementException("skill_validation_failed", "分页字段无效：$key")
        }
        return value.toInt()
    }
    return try {
        when (operation) {
            "list" -> listSuggestions(
                conversationId = arguments["conversationId"]?.let { long("conversationId") },
                turnId = arguments["turnId"]?.let { text("turnId") },
                skillId = arguments["skillId"]?.let { id("skillId", skillIdPattern) },
                offset = page("offset", 0, Int.MAX_VALUE),
                limit = page("limit", 20, 50),
            ).toMap()
            "save" -> saveSuggestion(
                id("candidateId", candidateIdPattern), long("expectedRevision"),
                text("name"), text("description"), text("bodyMarkdown"),
            ).toMap()
            "approve" -> approveSuggestion(
                id("candidateId", candidateIdPattern), long("expectedRevision"),
                id("targetSkillId", skillIdPattern),
                arguments["expectedCurrentContentHash"]?.let {
                    id("expectedCurrentContentHash", revisionIdPattern)
                },
            ).toMap()
            "reject" -> rejectSuggestion(
                id("candidateId", candidateIdPattern), long("expectedRevision"),
            ).toMap()
            "regenerate" -> regenerateSuggestion(
                id("candidateId", candidateIdPattern), long("expectedRevision"),
            ).toMap()
            "feedback" -> feedbackSkill(
                skillId = id("skillId", skillIdPattern),
                revisionId = id("revisionId", revisionIdPattern),
                conversationId = long("conversationId"),
                sessionId = text("sessionId"),
                turnId = text("turnId"),
                expectedRevision = long("expectedRevision"),
                action = text("action"),
            ).toMap()
            "restore" -> restoreSkillRevision(
                skillId = id("skillId", skillIdPattern),
                revisionId = id("revisionId", revisionIdPattern),
                expectedRevision = long("expectedRevision"),
                expectedCurrentContentHash = id("expectedCurrentContentHash", revisionIdPattern),
            ).toMap()
            "set_enabled" -> {
                val enabled = arguments["enabled"] as? Boolean
                    ?: throw SkillAssetManagementException("skill_validation_failed", "enabled 必须为布尔值")
                mapOf("enabled" to setAutoSuggestionsEnabled(enabled))
            }
            else -> throw SkillAssetManagementException("skill_validation_failed", "未知技能管理操作")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: SkillAssetManagementException) {
        throw error
    } catch (error: Exception) {
        val code = error.message?.substringBefore(':')?.takeIf(skillAssetErrors::contains)
        if (code != null) {
            throw SkillAssetManagementException(code, error.message!!.substringAfter(':').trim())
        }
        throw SkillAssetManagementException("skill_asset_invalid", "技能资产读取或保存失败")
    }
}
