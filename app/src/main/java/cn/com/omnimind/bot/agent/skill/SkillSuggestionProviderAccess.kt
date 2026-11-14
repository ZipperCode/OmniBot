package cn.com.omnimind.bot.agent

import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 将字节数组包装为大端 4 字节长度前缀并写入摘要器，避免字段拼接碰撞（length-delimited encoding）。
 */
private fun MessageDigest.putLengthPrefixedBytes(bytes: ByteArray) {
    update((bytes.size shr 24).toByte())
    update((bytes.size shr 16).toByte())
    update((bytes.size shr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

/**
 * 写入带名称与长度前缀的字符串字段。
 */
private fun MessageDigest.putField(name: String, value: String) {
    putLengthPrefixedBytes(name.toByteArray(Charsets.UTF_8))
    putLengthPrefixedBytes(value.toByteArray(Charsets.UTF_8))
}

/**
 * 写入带名称与 8 字节大端整数的 Long 字段。
 */
private fun MessageDigest.putLong(name: String, value: Long) {
    putLengthPrefixedBytes(name.toByteArray(Charsets.UTF_8))
    val buffer = ByteBuffer.allocate(8).putLong(value).array()
    putLengthPrefixedBytes(buffer)
}

/**
 * 计算模型提供商凭据与认证身份的稳定 SHA-256 摘要。
 *
 * 覆盖认证身份与配置要素：
 * - apiKey: 鉴权密钥（去除首尾空格）
 * - baseUrl: 规范化端点（去除尾部斜杠等冗余）
 * - id: 提供商唯一标识
 * - protocolType: 协议类型（openai_compatible 等）
 * - wireApi: 接口模式（chat_completions / responses 等）
 * - sourceType: 提供商来源类型（custom 等）
 * - customHeaders: 排序后的自定义请求头（键与值均做长度编码，确保顺序无关）
 * - credentialRevision: 内存运行时凭据版本
 * - revision: 持久化配置版本
 *
 * 采用长度前缀序列化，保证字段边界不可篡改，杜绝字符串直接拼接带来的碰撞风险。
 */
internal fun computeSkillSuggestionCredentialDigest(profile: ModelProviderProfile): String {
    val digest = MessageDigest.getInstance("SHA-256")

    digest.putField("id", profile.id.trim())
    val normalizedEndpoint = ModelProviderConfigStore.normalizeBaseUrl(profile.baseUrl) ?: profile.baseUrl.trim()
    digest.putField("baseUrl", normalizedEndpoint)
    digest.putField("apiKey", profile.apiKey.trim())
    digest.putField("protocolType", profile.protocolType.trim())
    digest.putField("wireApi", profile.wireApi.trim())
    digest.putField("sourceType", profile.sourceType.trim())

    // customHeaders 严格按 key、value 排序写入
    val sortedHeaders = profile.customHeaders.entries
        .sortedWith(compareBy({ it.key }, { it.value }))
    digest.putLengthPrefixedBytes("customHeaders".toByteArray(Charsets.UTF_8))
    val headerCountBuffer = ByteBuffer.allocate(4).putInt(sortedHeaders.size).array()
    digest.putLengthPrefixedBytes(headerCountBuffer)
    for (entry in sortedHeaders) {
        digest.putField("h_k", entry.key)
        digest.putField("h_v", entry.value)
    }

    digest.putLong("credentialRevision", profile.credentialRevision)
    digest.putLong("revision", profile.revision)

    val hash = digest.digest()
    return hash.joinToString("") { "%02x".format(it) }
}

/**
 * 解析技能建议任务的有效上下文容量预算（Token 数）。
 *
 * 语义与规则：
 * 1. 严格使用原任务的 Provider Profile 与 Model ID，不回退到全局/编辑配置、不回退到场景模型（scene.dispatch.model 等）；
 * 2. 严格要求 conversationId > 0 且会话必须在数据库中存在；若不存在或读取失败，明确判为不可用 null，防止因读取失败绕过会话预算；
 * 3. 禁止触发网络刷新（如 /models 查询），直接复用 ModelProviderConfigStore.findCachedModel 内部 store context；
 * 4. 会话预算仅使用实际正值 promptTokenThreshold 作为约束，若未设置（<= 0）则仅受模型容量约束；
 * 5. 从可用的正值候选（模型 contextLimit、模型 inputLimit、会话 promptTokenThreshold）中选取最小适用正容量；
 * 6. 若模型未配置（缓存中未查到对应模型）、会话不可读、或无任何有效正值容量，则明确返回 null（不回退默认 128k）。
 *
 * @param conversationId 会话 ID（必须 > 0 且有效）
 * @param profile 原任务的模型提供商配置
 * @param modelId 原任务的模型 ID
 * @return 最小可用正容量 Token 数；若不可用则返回 null
 */
internal suspend fun resolveSkillSuggestionContextCapacity(
    conversationId: Long,
    profile: ModelProviderProfile,
    modelId: String
): Int? {
    val normalizedModelId = modelId.trim()
    if (conversationId <= 0L || !profile.isConfigured() || normalizedModelId.isEmpty()) {
        return null
    }

    // 1. 严格校验会话存在性与预算：不存在或读取失败判为不可用 null，防止绕过会话预算
    val conversation = runCatching {
        DatabaseHelper.getConversationById(conversationId)
    }.getOrNull() ?: return null

    val conversationBudget = conversation.promptTokenThreshold.takeIf { it > 0 }

    // 2. 从既有本地缓存中检索模型配置，绝不触发网络刷新
    val cachedModel = ModelProviderConfigStore.findCachedModel(profile.id, normalizedModelId)
        ?: return null

    // 3. 收集模型本身声明的有效正容量上限（contextLimit / inputLimit）
    val modelLimits = listOfNotNull(
        cachedModel.contextLimit?.takeIf { it > 0 },
        cachedModel.inputLimit?.takeIf { it > 0 }
    )

    // 4. 汇总适用的正容量候选，选取最小正值（实际正 promptTokenThreshold 作为约束，未设置则仅受模型限制）
    val applicableCapacities = buildList {
        addAll(modelLimits)
        if (conversationBudget != null) {
            add(conversationBudget)
        }
    }

    if (applicableCapacities.isEmpty()) {
        return null
    }

    return applicableCapacities.minOrNull()
}
