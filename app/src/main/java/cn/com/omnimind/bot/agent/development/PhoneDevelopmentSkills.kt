package cn.com.omnimind.bot.agent.development

import android.content.Context
import java.io.File
import java.io.InputStreamReader

/**
 * 手机开发闭环技能与 Agent 引导文本加载器。
 * 供父层 BuiltInAgentCapabilityModule / 系统提示词动态注入与读取。
 */
object PhoneDevelopmentSkills {

    private const val SKILL_ASSET_PREFIX = "phone-development/skills"

    data class SkillDescriptor(
        val id: String,
        val name: String,
        val description: String,
        val assetPath: String
    )

    val supportedSkills: List<SkillDescriptor> = listOf(
        SkillDescriptor(
            id = "phone-development",
            name = "Phone Development",
            description = "手机端 LSPosed 模块开发与验证闭环指南（F10-F16, F18），指导 Agent 完成从环境检查、APK分析、运行时探测、代码开发、本机构建签名、作用域部署到真实行为验收的全流程。",
            assetPath = "$SKILL_ASSET_PREFIX/phone-development/SKILL.md"
        ),
        SkillDescriptor(
            id = "lsposed-develop",
            name = "LSPosed Development",
            description = "Xposed / LSPosed 模块开发模式库与最佳实践指南，涵盖方法拦截、构造器 Hook、私有成员读写、Context 获取、多重载匹配及防检测模式。",
            assetPath = "$SKILL_ASSET_PREFIX/lsposed-develop/SKILL.md"
        )
    )

    fun loadSkillText(context: Context, skillId: String): String? {
        val descriptor = supportedSkills.firstOrNull { it.id == skillId } ?: return null
        return runCatching {
            context.assets.open(descriptor.assetPath).use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).readText()
            }
        }.getOrNull()
    }

    fun getGuidancePrompt(context: Context): String {
        return buildString {
            append("### Phone Development (LSPosed 开发闭环)\n")
            append("当前设备支持手机端本地开发、APK 多 DEX 静态分析、运行时探测、无 Gradle / Gradle 构建与 LSPosed 部署。\n")
            append("详细开发流程与 Hook 模式库请在需要时调用 phone_development(action=\"guidance\", skillId=\"phone-development\" 或 \"lsposed-develop\") 显式读取。")
        }.trim()
    }
}
