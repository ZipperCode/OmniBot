package cn.com.omnimind.bot.agent

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.io.File
import java.util.UUID

/**
 * 自动技能建议候选状态资产。
 */
data class SkillCandidateAsset(
    val candidateId: String,
    val conversationId: Long,
    val sessionId: String,
    val turnId: String,
    val revision: Long = 1L,
    val state: String, // queued | generating | pending_review | approved | rejected | generation_failed | interrupted | skipped
    val skipReason: String? = null, // ineligible | busy | sensitive | too_large | provider_changed | no_suggestion
    val name: String? = null,
    val description: String? = null,
    val bodyMarkdown: String? = null,
    val targetSkillId: String? = null,
    val failureReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val toolCallsSummary: List<String> = emptyList(),
    val dataCategories: List<String> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "candidateId" to candidateId,
        "revision" to revision,
        "conversationId" to conversationId,
        "sessionId" to sessionId,
        "turnId" to turnId,
        "state" to state,
        "skipReason" to skipReason,
        "name" to name,
        "description" to description,
        "bodyMarkdown" to bodyMarkdown,
        "targetSkillId" to targetSkillId,
        "failureReason" to failureReason,
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
        "toolCallsSummary" to toolCallsSummary,
        "dataCategories" to dataCategories
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 不可变版本快照，用于恢复历史版本。
 */
data class SkillRevisionSnapshot(
    val skillId: String,
    val revisionId: String, // SKILL.md 内容 SHA256
    val previousRevisionId: String? = null,
    val name: String,
    val description: String,
    val bodyMarkdown: String,
    val createdAt: Long = System.currentTimeMillis(),
    val createdFromCandidateId: String? = null,
    val wasManuallyDisabled: Boolean = false // 若曾被用户手动停用/退役，禁止复活
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "skillId" to skillId,
        "revisionId" to revisionId,
        "previousRevisionId" to previousRevisionId,
        "name" to name,
        "description" to description,
        "bodyMarkdown" to bodyMarkdown,
        "createdAt" to createdAt,
        "wasManuallyDisabled" to wasManuallyDisabled,
        "canRestore" to !wasManuallyDisabled
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 技能使用记录（仅由 skills_read 触发并记录精确会话 Turn 与内容指纹）。
 */
data class SkillUsageRecord(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: Long,
    val sessionId: String,
    val turnId: String,
    val skillId: String,
    val revisionId: String?,
    val contentHash: String,
    val timestamp: Long = System.currentTimeMillis(),
    val completed: Boolean = false // 由 onSettledAndHistoryCommitted 在 Turn 正式完成时核验确认
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "conversationId" to conversationId,
        "sessionId" to sessionId,
        "turnId" to turnId,
        "skillId" to skillId,
        "revisionId" to revisionId,
        "contentHash" to contentHash,
        "timestamp" to timestamp,
        "completed" to completed
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 技能使用身份 DTO。
 */
data class SkillUsageIdentity(
    val conversationId: Long,
    val sessionId: String,
    val turnId: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "conversationId" to conversationId,
        "sessionId" to sessionId,
        "turnId" to turnId
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 技能试用反馈资格 DTO。
 */
data class SkillFeedbackEligibility(
    val skillId: String,
    val revisionId: String?,
    val revision: Long,
    val currentContentHash: String,
    val eligible: Boolean,
    val reason: String?,
    val usage: SkillUsageIdentity?,
    val revisions: List<SkillRevisionSnapshot> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "skillId" to skillId,
        "revisionId" to revisionId,
        "revision" to revision,
        "currentContentHash" to currentContentHash,
        "eligible" to eligible,
        "reason" to reason,
        "usage" to usage?.toMap(),
        "revisions" to revisions.map { it.toMap() }
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 候选建议列表响应 DTO。
 */
data class SkillSuggestionsListResponse(
    val enabled: Boolean,
    val items: List<SkillCandidateAsset>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val feedbackEligibility: SkillFeedbackEligibility? = null,
    val revisions: List<SkillRevisionSnapshot> = emptyList()
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "enabled" to enabled,
        "items" to items.map { it.toMap() },
        "total" to total,
        "offset" to offset,
        "limit" to limit,
        "feedbackEligibility" to feedbackEligibility?.toMap(),
        "revisions" to revisions.map { it.toMap() }
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 审核批准返回 DTO。
 */
data class SkillApprovalResult(
    val candidate: SkillCandidateAsset,
    val skill: SkillIndexEntry
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "candidate" to candidate.toMap(),
        "skill" to mapOf(
            "id" to skill.id,
            "name" to skill.name,
            "description" to skill.description,
            "verificationStatus" to skill.verificationStatus,
            "revisionId" to skill.revisionId,
            "previousRevisionId" to skill.previousRevisionId,
            "enabled" to skill.enabled,
            "source" to skill.source
        )
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 试用反馈返回 DTO。
 */
data class SkillFeedbackResult(
    val skillId: String,
    val verificationStatus: String,
    val revisionId: String?,
    val enabled: Boolean,
    val actionApplied: String
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "skillId" to skillId,
        "verificationStatus" to verificationStatus,
        "revisionId" to revisionId,
        "enabled" to enabled,
        "actionApplied" to actionApplied
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 版本恢复返回 DTO。
 */
data class SkillRestoreResult(
    val skillId: String,
    val restoredRevisionId: String,
    val verificationStatus: String,
    val enabled: Boolean
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "skillId" to skillId,
        "restoredRevisionId" to restoredRevisionId,
        "verificationStatus" to verificationStatus,
        "enabled" to enabled
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * 建议全局配置。
 */
data class SkillSuggestionSettings(
    val autoSuggestionsEnabled: Boolean = true
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "autoSuggestionsEnabled" to autoSuggestionsEnabled
    )
    fun toPayload(): Map<String, Any?> = toMap()
}

/**
 * Turn 工具调用摘要（用于自动建议准入评估）。
 */
data class TurnToolExecutionSummary(
    val toolName: String,
    val success: Boolean,
    val parameterKeys: Set<String> = emptySet(),
    val permissionRejected: Boolean = false
)

/**
 * 准入时安全捕获的 Turn Provider / Model 凭据摘要。
 */
data class TurnAdmissionInfo(
    val sessionId: String,
    val turnId: String,
    val requestId: String?,
    val conversationId: Long?,
    val providerProfileId: String?,
    val modelId: String?,
    val credentialDigest: String?,
    val admittedAt: Long = System.currentTimeMillis()
)

/**
 * 候选与版本元数据生命周期存储。
 * 存放在 noBackupFilesDir/skill-suggestions/ 下；不放 skillsRoot，不用 GUI 应用经验存储。
 * 采用原子临时文件 + rename 写入与 Mutex 保护；损坏时显式抛出 skill_asset_invalid，禁止静默空覆盖。
 */
class SkillLifecycleStore(
    private val context: Context,
    baseDirOverride: File? = null
) {
    companion object {
        @Volatile
        private var instance: SkillLifecycleStore? = null
        private val lock = Any()

        fun getInstance(context: Context, baseDirOverride: File? = null): SkillLifecycleStore {
            return instance ?: synchronized(lock) {
                instance ?: SkillLifecycleStore(context.applicationContext, baseDirOverride).also { instance = it }
            }
        }
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val rootDir: File = run {
        val target = baseDirOverride ?: File(context.noBackupFilesDir, "skill-suggestions")
        target.mkdirs()
        target.toPath().toRealPath().toFile()
    }
    private val candidatesDir: File = File(rootDir, "candidates")
    private val revisionsDir: File = File(rootDir, "revisions")
    private val usageDir: File = File(rootDir, "usage")
    private val settingsFile: File = File(rootDir, "settings.json")

    private val storeLock = Any()
    init {
        rootDir.mkdirs()
        candidatesDir.mkdirs()
        revisionsDir.mkdirs()
        usageDir.mkdirs()
    }

    private fun sanitizeFileName(id: String, paramName: String): String {
        val trimmed = id.trim()
        if (trimmed.isEmpty() ||
            trimmed.contains("..") ||
            trimmed.contains("/") ||
            trimmed.contains("\\") ||
            trimmed.contains("\u0000") ||
            trimmed.contains(":")
        ) {
            throw IllegalArgumentException("skill_validation_failed: 非法参数或路径逃逸：$paramName")
        }
        return trimmed
    }

    private fun verifyPathWithinRoot(file: File) {
        val rootPath = rootDir.toPath().toAbsolutePath().normalize()
        val filePath = file.toPath()
        if (Files.isSymbolicLink(filePath)) {
            throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${file.path}")
        }
        val targetNormalized = filePath.toAbsolutePath().normalize()
        if (!targetNormalized.startsWith(rootPath)) {
            throw IllegalArgumentException("skill_validation_failed: 拒绝访问工作区外路径：${file.path}")
        }
        if (file.exists()) {
            val realPath = filePath.toRealPath()
            if (Files.isSymbolicLink(filePath) || !realPath.startsWith(rootPath)) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问工作区外路径或符号链接：${file.path}")
            }
        } else {
            var parent = file.parentFile
            while (parent != null && !parent.exists()) {
                parent = parent.parentFile
            }
            if (parent != null) {
                val realParent = parent.toPath().toRealPath()
                if (Files.isSymbolicLink(parent.toPath()) || !realParent.startsWith(rootPath)) {
                    throw IllegalArgumentException("skill_validation_failed: 拒绝访问工作区外路径或符号链接：${file.path}")
                }
            }
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * 进程启动时调用：将残留的 queued 或 generating 候选置为 interrupted，不自动重跑。
     */
    suspend fun onProcessStart() = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val files = candidatesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            for (file in files) {
                val candidate = readCandidateFile(file) ?: continue
                if (candidate.state == "queued" || candidate.state == "generating") {
                    val updated = candidate.copy(
                        state = "interrupted",
                        failureReason = "任务在生成过程中随进程重启中断",
                        revision = candidate.revision + 1L,
                        updatedAt = System.currentTimeMillis()
                    )
                    writeCandidateFile(file, updated)
                }
            }
        }
    }

    internal fun getCandidateNow(candidateId: String): SkillCandidateAsset? = synchronized(storeLock) {
        val safeId = sanitizeFileName(candidateId, "candidateId")
        val file = File(candidatesDir, "$safeId.json")
        verifyPathWithinRoot(file)
        if (!file.exists()) return@synchronized null
        readCandidateFile(file)
    }

    suspend fun getCandidate(candidateId: String): SkillCandidateAsset? = withContext(Dispatchers.IO) {
        getCandidateNow(candidateId)
    }

    suspend fun saveCandidate(candidate: SkillCandidateAsset) = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val safeId = sanitizeFileName(candidate.candidateId, "candidateId")
            val file = File(candidatesDir, "$safeId.json")
            verifyPathWithinRoot(file)
            writeCandidateFile(file, candidate)
        }
    }

    /**
     * 同步 CAS 原子更新候选作业，供持有更高层锁（如 mutationMutex / SKILL_INDEX_LOCK）时安全调用。
     */
    internal fun mutateCandidateNow(
        candidateId: String,
        expectedRevision: Long?,
        transform: (SkillCandidateAsset) -> SkillCandidateAsset
    ): SkillCandidateAsset = synchronized(storeLock) {
        val safeId = sanitizeFileName(candidateId, "candidateId")
        val file = File(candidatesDir, "$safeId.json")
        verifyPathWithinRoot(file)
        if (!file.exists()) {
            throw IllegalArgumentException("skill_candidate_not_found: 未找到候选作业：$candidateId")
        }
        val current = readCandidateFile(file)
            ?: throw IllegalStateException("skill_asset_invalid: 候选文件解析为空：$candidateId")

        if (expectedRevision != null && current.revision != expectedRevision) {
            throw IllegalStateException("skill_revision_conflict: 候选版本冲突，期望 revision $expectedRevision，当前为 ${current.revision}")
        }

        val transformed = transform(current)
        val updated = transformed.copy(
            revision = current.revision + 1L,
            updatedAt = System.currentTimeMillis()
        )
        writeCandidateFile(file, updated)
        updated
    }

    /**
     * CAS 原子更新候选作业（协程包装）。
     */
    suspend fun updateCandidate(
        candidateId: String,
        expectedRevision: Long?,
        transform: (SkillCandidateAsset) -> SkillCandidateAsset
    ): SkillCandidateAsset = withContext(Dispatchers.IO) {
        mutateCandidateNow(candidateId, expectedRevision, transform)
    }
    /**
     * 候选审批原子提交事务：
     * 1. 在 storeLock 下先核验候选状态必须为 pending_review 且满足 CAS expectedRevision；
     * 2. 持久化旧版本与新版本不可变快照；
     * 3. 原子更新候选状态为 approved，记录 targetSkillId 并递增 revision；
     * 4. 出现任何 IO/校验异常立即回滚本次所写快照与候选状态，保证零脏数据残留。
     */
    internal fun commitApprovalNow(
        candidateId: String,
        expectedRevision: Long?,
        oldSnapshot: SkillRevisionSnapshot?,
        newSnapshot: SkillRevisionSnapshot,
        targetSkillId: String
    ): SkillCandidateAsset = synchronized(storeLock) {
        val safeId = sanitizeFileName(candidateId, "candidateId")
        val candidateFile = File(candidatesDir, "$safeId.json")
        verifyPathWithinRoot(candidateFile)
        if (!candidateFile.exists()) {
            throw IllegalArgumentException("skill_candidate_not_found: 未找到候选作业：$candidateId")
        }
        val current = readCandidateFile(candidateFile)
            ?: throw IllegalStateException("skill_asset_invalid: 候选文件解析为空：$candidateId")

        if (current.state != "pending_review") {
            throw IllegalStateException("skill_validation_failed: 候选状态必须为 pending_review，当前为 ${current.state}")
        }

        if (expectedRevision != null && current.revision != expectedRevision) {
            throw IllegalStateException("skill_revision_conflict: 候选版本冲突，期望 revision $expectedRevision，当前为 ${current.revision}")
        }

        val safeSkillId = sanitizeFileName(targetSkillId, "targetSkillId")

        if (newSnapshot.skillId != targetSkillId) {
            throw IllegalArgumentException("skill_validation_failed: newSnapshot.skillId (${newSnapshot.skillId}) 与 targetSkillId ($targetSkillId) 不匹配")
        }
        if (oldSnapshot != null && oldSnapshot.skillId != targetSkillId) {
            throw IllegalArgumentException("skill_validation_failed: oldSnapshot.skillId (${oldSnapshot.skillId}) 与 targetSkillId ($targetSkillId) 不匹配")
        }

        val newActualHash = computeSha256(newSnapshot.bodyMarkdown.toByteArray(Charsets.UTF_8))
        if (!newActualHash.equals(newSnapshot.revisionId, ignoreCase = true)) {
            throw IllegalArgumentException("skill_validation_failed: newSnapshot.revisionId 与内容 SHA-256 不匹配: expected $newActualHash, got ${newSnapshot.revisionId}")
        }
        if (oldSnapshot != null) {
            val oldActualHash = computeSha256(oldSnapshot.bodyMarkdown.toByteArray(Charsets.UTF_8))
            if (!oldActualHash.equals(oldSnapshot.revisionId, ignoreCase = true)) {
                throw IllegalArgumentException("skill_validation_failed: oldSnapshot.revisionId 与内容 SHA-256 不匹配: expected $oldActualHash, got ${oldSnapshot.revisionId}")
            }
        }

        val skillRevDir = File(revisionsDir, safeSkillId)
        verifyPathWithinRoot(skillRevDir)
        skillRevDir.mkdirs()

        val safeNewRevId = sanitizeFileName(newSnapshot.revisionId, "newRevisionId")
        val newFile = File(skillRevDir, "$safeNewRevId.json")
        verifyPathWithinRoot(newFile)
        val newTombstoneFile = File(skillRevDir, "$safeNewRevId.tombstone")
        verifyPathWithinRoot(newTombstoneFile)

        val isSameRevision = oldSnapshot != null && oldSnapshot.revisionId.equals(newSnapshot.revisionId, ignoreCase = true)

        var oldFile: File? = null
        var oldTombstoneFile: File? = null
        if (oldSnapshot != null && !isSameRevision) {
            val safeOldRevId = sanitizeFileName(oldSnapshot.revisionId, "oldRevisionId")
            oldFile = File(skillRevDir, "$safeOldRevId.json")
            verifyPathWithinRoot(oldFile)
            oldTombstoneFile = File(skillRevDir, "$safeOldRevId.tombstone")
            verifyPathWithinRoot(oldTombstoneFile)
        }

        val candidateOldBytes = candidateFile.readBytes()
        val newFileExisted = newFile.exists()
        val newFileBytes = if (newFileExisted) newFile.readBytes() else null
        val newTombstoneExisted = newTombstoneFile.exists()

        val oldFileExisted = oldFile?.exists() ?: false
        val oldFileBytes = if (oldFileExisted) oldFile?.readBytes() else null
        val oldTombstoneExisted = oldTombstoneFile?.exists() ?: false

        try {
            if (oldSnapshot != null && !isSameRevision) {
                saveRevisionNow(oldSnapshot)
            }
            saveRevisionNow(newSnapshot)

            val updated = current.copy(
                state = "approved",
                targetSkillId = targetSkillId,
                revision = current.revision + 1L,
                updatedAt = System.currentTimeMillis()
            )
            writeCandidateFile(candidateFile, updated)
            updated
        } catch (e: Throwable) {
            val rollbackErrors = mutableListOf<Throwable>()
            runCatching {
                writeAtomicBytes(candidateFile, candidateOldBytes)
            }.onFailure { rollbackErrors.add(it) }

            if (oldFile != null) {
                runCatching {
                    if (oldFileExisted && oldFileBytes != null) {
                        writeAtomicBytes(oldFile, oldFileBytes)
                    } else if (!oldFileExisted && oldFile.exists()) {
                        oldFile.delete()
                    }
                    if (!oldTombstoneExisted && oldTombstoneFile?.exists() == true) {
                        oldTombstoneFile.delete()
                    }
                }.onFailure { rollbackErrors.add(it) }
            }

            runCatching {
                if (newFileExisted && newFileBytes != null) {
                    writeAtomicBytes(newFile, newFileBytes)
                } else if (!newFileExisted && newFile.exists()) {
                    newFile.delete()
                }
                if (!newTombstoneExisted && newTombstoneFile.exists()) {
                    newTombstoneFile.delete()
                }
            }.onFailure { rollbackErrors.add(it) }

            if (rollbackErrors.isNotEmpty()) {
                for (err in rollbackErrors) {
                    e.addSuppressed(err)
                }
            }
            throw e
        }
    }

    suspend fun commitApproval(
        candidateId: String,
        expectedRevision: Long?,
        oldSnapshot: SkillRevisionSnapshot?,
        newSnapshot: SkillRevisionSnapshot,
        targetSkillId: String
    ): SkillCandidateAsset = withContext(Dispatchers.IO) {
        commitApprovalNow(candidateId, expectedRevision, oldSnapshot, newSnapshot, targetSkillId)
    }

    suspend fun listCandidates(
        conversationId: Long? = null,
        turnId: String? = null,
        offset: Int = 0,
        limit: Int = 20
    ): List<SkillCandidateAsset> = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val files = candidatesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            val list = mutableListOf<SkillCandidateAsset>()
            for (file in files) {
                val item = readCandidateFile(file) ?: continue
                if (conversationId != null && item.conversationId != conversationId) continue
                if (turnId != null && item.turnId != turnId) continue
                list.add(item)
            }
            val safeOffset = offset.coerceAtLeast(0)
            val safeLimit = limit.coerceIn(1, 50)
            list.sortedByDescending { it.createdAt }
                .drop(safeOffset)
                .take(safeLimit)
        }
    }

    suspend fun countCandidates(
        conversationId: Long? = null,
        turnId: String? = null
    ): Int = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val files = candidatesDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            var count = 0
            for (file in files) {
                val item = readCandidateFile(file) ?: continue
                if (conversationId != null && item.conversationId != conversationId) continue
                if (turnId != null && item.turnId != turnId) continue
                count++
            }
            count
        }
    }

    // --- 版本快照管理 ---

    internal fun saveRevisionNow(snapshot: SkillRevisionSnapshot) = synchronized(storeLock) {
        val actualHash = computeSha256(snapshot.bodyMarkdown.toByteArray(Charsets.UTF_8))
        if (!actualHash.equals(snapshot.revisionId, ignoreCase = true)) {
            throw IllegalArgumentException("skill_validation_failed: revisionId 必须严格等于 bodyMarkdown 的 SHA-256 哈希: expected $actualHash, got ${snapshot.revisionId}")
        }

        val safeSkillId = sanitizeFileName(snapshot.skillId, "skillId")
        val safeRevId = sanitizeFileName(snapshot.revisionId, "revisionId")
        val skillRevDir = File(revisionsDir, safeSkillId)
        skillRevDir.mkdirs()
        val file = File(skillRevDir, "$safeRevId.json")
        verifyPathWithinRoot(file)
        val tombstoneFile = File(skillRevDir, "$safeRevId.tombstone")
        verifyPathWithinRoot(tombstoneFile)

        val existing = if (file.exists()) readAtomicJson<SkillRevisionSnapshot>(file) else null
        val isTombstoned = tombstoneFile.exists() || (existing?.wasManuallyDisabled == true) || snapshot.wasManuallyDisabled

        val toWrite = if (existing != null) {
            // 同 (skillId, revisionId) 存在时内容不可变，不覆盖 createdFromCandidateId/previousRevisionId/createdAt
            // 停用标记只能 false -> true
            existing.copy(
                createdFromCandidateId = existing.createdFromCandidateId ?: snapshot.createdFromCandidateId,
                previousRevisionId = existing.previousRevisionId ?: snapshot.previousRevisionId,
                wasManuallyDisabled = isTombstoned
            )
        } else {
            snapshot.copy(
                wasManuallyDisabled = isTombstoned
            )
        }

        if (isTombstoned && !tombstoneFile.exists()) {
            writeAtomicText(tombstoneFile, "retired\n")
        }
        writeAtomicJson(file, toWrite)
    }

    suspend fun saveRevision(snapshot: SkillRevisionSnapshot) = withContext(Dispatchers.IO) {
        saveRevisionNow(snapshot)
    }

    internal fun getRevisionNow(skillId: String, revisionId: String): SkillRevisionSnapshot? = synchronized(storeLock) {
        val safeSkillId = sanitizeFileName(skillId, "skillId")
        val safeRevId = sanitizeFileName(revisionId, "revisionId")
        val skillRevDir = File(revisionsDir, safeSkillId)
        val file = File(skillRevDir, "$safeRevId.json")
        verifyPathWithinRoot(file)
        if (!file.exists()) return null
        val snapshot = readAtomicJson<SkillRevisionSnapshot>(file) ?: return null
        val tombstoneFile = File(skillRevDir, "$safeRevId.tombstone")
        val isTombstoned = tombstoneFile.exists() || snapshot.wasManuallyDisabled
        if (isTombstoned && !snapshot.wasManuallyDisabled) {
            snapshot.copy(wasManuallyDisabled = true)
        } else {
            snapshot
        }
    }

    suspend fun getRevision(skillId: String, revisionId: String): SkillRevisionSnapshot? = withContext(Dispatchers.IO) {
        getRevisionNow(skillId, revisionId)
    }

    suspend fun listRevisions(skillId: String): List<SkillRevisionSnapshot> = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val safeSkillId = sanitizeFileName(skillId, "skillId")
            val skillRevDir = File(revisionsDir, safeSkillId)
            verifyPathWithinRoot(skillRevDir)
            if (!skillRevDir.exists() || !skillRevDir.isDirectory) return@synchronized emptyList()
            val files = skillRevDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            val list = mutableListOf<SkillRevisionSnapshot>()
            for (file in files) {
                val rev = readAtomicJson<SkillRevisionSnapshot>(file) ?: continue
                val tombstoneFile = File(skillRevDir, "${rev.revisionId}.tombstone")
                val isTombstoned = tombstoneFile.exists() || rev.wasManuallyDisabled
                list.add(if (isTombstoned && !rev.wasManuallyDisabled) rev.copy(wasManuallyDisabled = true) else rev)
            }
            list.sortedByDescending { it.createdAt }
        }
    }

    internal fun markRevisionManuallyDisabledNow(skillId: String, revisionId: String) = synchronized(storeLock) {
        val safeSkillId = sanitizeFileName(skillId, "skillId")
        val safeRevId = sanitizeFileName(revisionId, "revisionId")
        val skillRevDir = File(revisionsDir, safeSkillId)
        skillRevDir.mkdirs()
        val tombstoneFile = File(skillRevDir, "$safeRevId.tombstone")
        verifyPathWithinRoot(tombstoneFile)
        if (!tombstoneFile.exists()) {
            writeAtomicText(tombstoneFile, "retired\n")
        }
        val file = File(skillRevDir, "$safeRevId.json")
        verifyPathWithinRoot(file)
        if (file.exists()) {
            val snapshot = readAtomicJson<SkillRevisionSnapshot>(file)
            if (snapshot != null && !snapshot.wasManuallyDisabled) {
                writeAtomicJson(file, snapshot.copy(wasManuallyDisabled = true))
            }
        }
    }

    suspend fun markRevisionManuallyDisabled(skillId: String, revisionId: String) = withContext(Dispatchers.IO) {
        markRevisionManuallyDisabledNow(skillId, revisionId)
    }

    // --- 使用记录管理（仅 skills_read） ---

    suspend fun recordUsage(record: SkillUsageRecord) = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val safeSkillId = sanitizeFileName(record.skillId, "skillId")
            val skillUsageDir = File(usageDir, safeSkillId)
            skillUsageDir.mkdirs()
            val file = File(skillUsageDir, "${record.id}.json")
            verifyPathWithinRoot(file)
            writeAtomicJson(file, record)
        }
    }

    suspend fun listUsageForSkill(skillId: String): List<SkillUsageRecord> = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val safeSkillId = sanitizeFileName(skillId, "skillId")
            val skillUsageDir = File(usageDir, safeSkillId)
            verifyPathWithinRoot(skillUsageDir)
            if (!skillUsageDir.exists() || !skillUsageDir.isDirectory) return@synchronized emptyList()
            val files = skillUsageDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            val list = mutableListOf<SkillUsageRecord>()
            for (file in files) {
                val item = readAtomicJson<SkillUsageRecord>(file) ?: continue
                list.add(item)
            }
            list.sortedByDescending { it.timestamp }
        }
    }

    /**
     * 在 Turn 提交屏障完成时，标记该 Turn 内所有 skills_read 为 completed = true。
     */
    suspend fun markTurnUsageCompleted(
        conversationId: Long,
        sessionId: String,
        turnId: String
    ) = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val skillDirs = usageDir.listFiles { f -> f.isDirectory }.orEmpty()
            for (dir in skillDirs) {
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
                for (file in files) {
                    val record = readAtomicJson<SkillUsageRecord>(file) ?: continue
                    if (record.conversationId == conversationId &&
                        record.sessionId == sessionId &&
                        record.turnId == turnId &&
                        !record.completed
                    ) {
                        writeAtomicJson(file, record.copy(completed = true))
                    }
                }
            }
        }
    }

    suspend fun findCompletedUsageForTurn(
        skillId: String,
        revisionId: String?,
        conversationId: Long,
        sessionId: String,
        turnId: String
    ): SkillUsageRecord? = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            val safeSkillId = sanitizeFileName(skillId, "skillId")
            val skillUsageDir = File(usageDir, safeSkillId)
            verifyPathWithinRoot(skillUsageDir)
            if (!skillUsageDir.exists() || !skillUsageDir.isDirectory) return@synchronized null
            val files = skillUsageDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            for (file in files) {
                val item = readAtomicJson<SkillUsageRecord>(file) ?: continue
                if (item.completed &&
                    item.conversationId == conversationId &&
                    item.sessionId == sessionId &&
                    item.turnId == turnId &&
                    item.skillId == skillId &&
                    item.revisionId != null &&
                    item.contentHash.equals(item.revisionId, ignoreCase = true) &&
                    (revisionId == null || (item.revisionId.equals(revisionId, ignoreCase = true) && item.contentHash.equals(revisionId, ignoreCase = true)))
                ) {
                    return@synchronized item
                }
            }
            null
        }
    }

    // --- 设置管理 ---

    suspend fun getSettings(): SkillSuggestionSettings = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            verifyPathWithinRoot(settingsFile)
            if (!settingsFile.exists()) {
                return@synchronized SkillSuggestionSettings(autoSuggestionsEnabled = true)
            }
            readAtomicJson<SkillSuggestionSettings>(settingsFile)
                ?: throw IllegalStateException("skill_asset_invalid: 配置文件解析为 null: ${settingsFile.absolutePath}")
        }
    }

    suspend fun updateSettings(enabled: Boolean): SkillSuggestionSettings = withContext(Dispatchers.IO) {
        synchronized(storeLock) {
            verifyPathWithinRoot(settingsFile)
            val settings = SkillSuggestionSettings(autoSuggestionsEnabled = enabled)
            writeAtomicJson(settingsFile, settings)
            settings
        }
    }

    // --- 文件原子读写工具 ---

    private fun readCandidateFile(file: File): SkillCandidateAsset? {
        return readAtomicJson<SkillCandidateAsset>(file)
    }

    private fun writeCandidateFile(file: File, asset: SkillCandidateAsset) {
        writeAtomicJson(file, asset)
    }

    private inline fun <reified T> readAtomicJson(file: File): T? {
        verifyPathWithinRoot(file)
        if (!file.exists()) return null
        val text = file.readText()
        if (text.isBlank()) {
            throw IllegalStateException("skill_asset_invalid: 资产文件内容为空: ${file.absolutePath}")
        }
        val result = try {
            gson.fromJson<T>(text, object : TypeToken<T>() {}.type)
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.startsWith("skill_asset_invalid") == true) {
                throw e
            }
            throw IllegalStateException("skill_asset_invalid: 资产文件损坏: ${file.absolutePath}", e)
        }
        return result ?: throw IllegalStateException("skill_asset_invalid: 资产文件解析为 null: ${file.absolutePath}")
    }

    private fun writeAtomicJson(file: File, data: Any) {
        verifyPathWithinRoot(file)
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
        val json = gson.toJson(data)
        tempFile.writeText(json)
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw IllegalStateException("skill_asset_invalid: 写入资产原子重命名失败: ${file.absolutePath}", e)
        }
    }

    private fun writeAtomicText(file: File, text: String) {
        verifyPathWithinRoot(file)
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
        tempFile.writeText(text)
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw IllegalStateException("skill_asset_invalid: 写入资产原子重命名失败: ${file.absolutePath}", e)
        }
    }

    private fun writeAtomicBytes(file: File, bytes: ByteArray) {
        verifyPathWithinRoot(file)
        file.parentFile?.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp.${System.nanoTime()}")
        tempFile.writeBytes(bytes)
        try {
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw IllegalStateException("skill_asset_invalid: 写入资产原子重命名失败: ${file.absolutePath}", e)
        }
    }
}
