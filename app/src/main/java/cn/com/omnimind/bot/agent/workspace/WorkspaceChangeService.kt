package cn.com.omnimind.bot.agent.workspace

import android.content.Context
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.database.WorkspaceChangeDao
import cn.com.omnimind.baselib.database.WorkspaceChangeEntry
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.runtime.SubagentResourceGate
import cn.com.omnimind.bot.agent.skill.SkillSuggestionRedactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * 工作区受管变更异常，携带安全错误码及可选快照。
 */
class WorkspaceChangeException(
    val errorCode: String,
    override val message: String,
    val snapshot: Map<String, Any?>? = null
) : Exception(message)

data class WorkspaceChangePreview(
    val changeId: String,
    val conversationId: Long,
    val turnId: String,
    val workspaceRoot: String,
    val operation: String,
    val relativePath: String,
    val sourceRelativePath: String?,
    val targetRelativePath: String?,
    val beforeSha256: String?,
    val afterSha256: String?,
    val beforeExists: Boolean,
    val afterExists: Boolean,
    val snapshotStatus: String,
    val revision: Long,
    val createdAt: Long,
    val diffText: String,
    val isBinary: Boolean,
    val canRestore: Boolean,
    val unavailableReason: String?,
    val expectedAfterHashes: Map<String, String?>
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "changeId" to changeId,
        "conversationId" to conversationId,
        "turnId" to turnId,
        "workspaceRoot" to workspaceRoot,
        "operation" to operation,
        "relativePath" to relativePath,
        "sourceRelativePath" to sourceRelativePath,
        "targetRelativePath" to targetRelativePath,
        "beforeSha256" to beforeSha256,
        "afterSha256" to afterSha256,
        "beforeExists" to beforeExists,
        "afterExists" to afterExists,
        "snapshotStatus" to snapshotStatus,
        "revision" to revision,
        "createdAt" to createdAt,
        "diffText" to diffText,
        "isBinary" to isBinary,
        "canRestore" to canRestore,
        "unavailableReason" to unavailableReason,
        "expectedAfterHashes" to expectedAfterHashes
    )
}

data class WorkspaceChangeRestoreResult(
    val success: Boolean,
    val status: String, // "restored", "workspace_change_busy", "workspace_change_conflict", "error"
    val message: String,
    val changeId: String,
    val revision: Long?,
    val restoredFiles: List<String> = emptyList(),
    val journal: List<Map<String, Any?>> = emptyList()
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "success" to success,
        "status" to status,
        "message" to message,
        "changeId" to changeId,
        "revision" to revision,
        "restoredFiles" to restoredFiles,
        "journal" to journal
    )
}

data class RestoreReconciliationResult(
    val changeId: String,
    val status: String, // "consistent_restored" | "consistent_unrestored" | "partially_restored" | "conflict"
    val message: String,
    val details: Map<String, Any?>
)

fun WorkspaceChangeEntry.toSummaryMap(): Map<String, Any?> = linkedMapOf(
    "changeId" to changeId,
    "conversationId" to conversationId,
    "sessionId" to sessionId,
    "turnId" to turnId,
    "toolCallId" to toolCallId,
    "workspaceRoot" to workspaceRoot,
    "operation" to operation,
    "sourceRelativePath" to sourceRelativePath,
    "targetRelativePath" to targetRelativePath,
    "beforeSha256" to beforeSha256,
    "afterSha256" to afterSha256,
    "beforeExists" to beforeExists,
    "afterExists" to afterExists,
    "snapshotStatus" to snapshotStatus,
    "revision" to revision,
    "createdAt" to createdAt,
    "snapshotBytes" to snapshotBytes,
    "unavailableReason" to unavailableReason
)

/**
 * 工作区变更受管服务。
 *
 * 职责：
 * 1. 拦截 FileToolHandler 的 write、edit、move 操作，捕获 before/after 快照与状态。
 * 2. 操作完成后进行二次敏感检查与字节核验，持久化元数据至 Room workspace_changes。
 * 3. 提供列表查询、单项预览（含 expectedAfterHashes）与 Unified Diff 生成。
 * 4. 落实明确确认恢复（受管写租约独占 tryAcquire、WorkspacePathLock 共享锁、expectedAfterHashes 严格核验、逐项对账）。
 */
class WorkspaceChangeService(
    private val changeDao: WorkspaceChangeDao,
    private val changeStore: WorkspaceChangeStore,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    companion object {
        @Volatile
        private var instance: WorkspaceChangeService? = null

        fun getInstance(context: Context): WorkspaceChangeService {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    val changeDao = DatabaseHelper.getWorkspaceChangeDao()
                    val baseDir = File(appContext.noBackupFilesDir, "workspace-changes")
                    val store = WorkspaceChangeStore(baseDir)
                    val svc = WorkspaceChangeService(changeDao, store)
                    instance = svc
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        runCatching { svc.onProcessStart() }
                    }
                    svc
                }
            }
        }

        fun resetInstanceForTesting(testInstance: WorkspaceChangeService? = null) {
            instance = testInstance
        }
    }
    @Volatile
    internal var onBeforeTargetRestoreForTesting: (() -> Unit)? = null


    data class PreparedChange(
        val changeId: String,
        val workspaceRoot: File,
        val canonicalRoot: String,
        val operation: String,
        val sourceFile: File,
        val targetFile: File?,
        val sourceRelPath: String,
        val targetRelPath: String?,
        val beforeExists: Boolean,
        val beforeSha256: String?,
        val beforeBytes: Long,
        val beforeSnapshotName: String?,
        val targetBeforeExists: Boolean,
        val targetBeforeSha256: String?,
        val targetBeforeSnapshotName: String?,
        val eligibility: WorkspaceChangeStore.Eligibility,
        val beforeContentText: String?
    )

    /**
     * 在文件操作前执行准备：检查资格、读取原始状态、预先保存 before 快照。
     */
    suspend fun prepareWriteOrEdit(
        file: File,
        workspaceRoot: File,
        operation: String
    ): PreparedChange {
        val changeId = UUID.randomUUID().toString()
        val canonicalRoot = SubagentResourceGate.canonicalizeRoot(workspaceRoot.absolutePath)
        val relPath = runCatching {
            workspaceRoot.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
        }.getOrDefault(file.name)

        val currentTotalBytes = changeDao.getTotalSnapshotBytes()
        val eligibility = changeStore.checkEligibility(file, workspaceRoot, currentTotalBytes)

        val beforeExists = file.exists() && file.isFile
        val beforeSha256 = if (beforeExists) changeStore.sha256(file) else null
        var beforeBytes = 0L
        var snapshotName: String? = null
        var beforeContentText: String? = null

        if (beforeExists && eligibility is WorkspaceChangeStore.Eligibility.Eligible) {
            snapshotName = "before-${file.name}"
            val saved = changeStore.saveBeforeFile(changeId, file, snapshotName)
            if (saved != null) {
                beforeBytes = saved.second
            }
            beforeContentText = runCatching { file.readText() }.getOrNull()
        }

        return PreparedChange(
            changeId = changeId,
            workspaceRoot = workspaceRoot,
            canonicalRoot = canonicalRoot,
            operation = operation,
            sourceFile = file,
            targetFile = null,
            sourceRelPath = relPath,
            targetRelPath = null,
            beforeExists = beforeExists,
            beforeSha256 = beforeSha256,
            beforeBytes = beforeBytes,
            beforeSnapshotName = snapshotName,
            targetBeforeExists = false,
            targetBeforeSha256 = null,
            targetBeforeSnapshotName = null,
            eligibility = eligibility,
            beforeContentText = beforeContentText
        )
    }

    suspend fun prepareMove(
        source: File,
        target: File,
        workspaceRoot: File
    ): PreparedChange {
        val changeId = UUID.randomUUID().toString()
        val canonicalRoot = SubagentResourceGate.canonicalizeRoot(workspaceRoot.absolutePath)
        val sourceRel = runCatching {
            workspaceRoot.canonicalFile.toPath().relativize(source.canonicalFile.toPath()).toString().replace('\\', '/')
        }.getOrDefault(source.name)
        val targetRel = runCatching {
            workspaceRoot.canonicalFile.toPath().relativize(target.canonicalFile.toPath()).toString().replace('\\', '/')
        }.getOrDefault(target.name)

        val currentTotalBytes = changeDao.getTotalSnapshotBytes()
        val srcEligibility = changeStore.checkEligibility(source, workspaceRoot, currentTotalBytes)
        val tgtEligibility = changeStore.checkEligibility(target, workspaceRoot, currentTotalBytes)

        val eligibility = if (srcEligibility is WorkspaceChangeStore.Eligibility.Ineligible) {
            srcEligibility
        } else if (tgtEligibility is WorkspaceChangeStore.Eligibility.Ineligible) {
            tgtEligibility
        } else {
            WorkspaceChangeStore.Eligibility.Eligible
        }

        val beforeExists = source.exists() && source.isFile
        val beforeSha256 = if (beforeExists) changeStore.sha256(source) else null
        val targetBeforeExists = target.exists() && target.isFile
        val targetBeforeSha256 = if (targetBeforeExists) changeStore.sha256(target) else null

        var beforeBytes = 0L
        var sourceSnapshotName: String? = null
        var targetSnapshotName: String? = null

        if (eligibility is WorkspaceChangeStore.Eligibility.Eligible) {
            if (beforeExists) {
                sourceSnapshotName = "source-before-${source.name}"
                val saved = changeStore.saveBeforeFile(changeId, source, sourceSnapshotName)
                if (saved != null) beforeBytes += saved.second
            }
            if (targetBeforeExists) {
                targetSnapshotName = "target-before-${target.name}"
                val saved = changeStore.saveBeforeFile(changeId, target, targetSnapshotName)
                if (saved != null) beforeBytes += saved.second
            }
        }

        return PreparedChange(
            changeId = changeId,
            workspaceRoot = workspaceRoot,
            canonicalRoot = canonicalRoot,
            operation = WorkspaceChangeEntry.OPERATION_MOVE,
            sourceFile = source,
            targetFile = target,
            sourceRelPath = sourceRel,
            targetRelPath = targetRel,
            beforeExists = beforeExists,
            beforeSha256 = beforeSha256,
            beforeBytes = beforeBytes,
            beforeSnapshotName = sourceSnapshotName,
            targetBeforeExists = targetBeforeExists,
            targetBeforeSha256 = targetBeforeSha256,
            targetBeforeSnapshotName = targetSnapshotName,
            eligibility = eligibility,
            beforeContentText = null
        )
    }

    /**
     * 在文件操作完成后记录变更，持久化至 Room 数据库。
     * 进行严格的二次 after 大小、敏感凭据核查，并在异常时记录实际失败状态。
     */
    suspend fun recordChange(
        prepared: PreparedChange,
        env: AgentExecutionEnvironment,
        toolCallId: String,
        success: Boolean
    ): WorkspaceChangeEntry? {
        val conversationId = env.conversationId
        val sessionId = env.acpSessionId?.trim()?.ifEmpty { null }
        val turnId = env.acpTurnId?.trim()?.ifEmpty { null }
        val trimmedToolCallId = toolCallId.trim().ifEmpty { null }

        // 严格 Conversation -> Session -> Turn 归属与有效 toolCallId。缺身份时不造恢复资产，清理本次 task 快照
        if (conversationId == null || sessionId == null || turnId == null || trimmedToolCallId == null) {
            changeStore.deleteSnapshot(prepared.changeId)
            return null
        }
        val finalFile = prepared.targetFile ?: prepared.sourceFile
        val afterExists = finalFile.exists() && finalFile.isFile
        val afterSha256 = if (afterExists) changeStore.sha256(finalFile) else null

        // 检查 move 场景的实际双端状态
        val sourceAfterExists = prepared.sourceFile.exists() && prepared.sourceFile.isFile
        val sourceAfterSha256 = if (sourceAfterExists) changeStore.sha256(prepared.sourceFile) else null

        var unavailableReason: String? = when {
            prepared.eligibility is WorkspaceChangeStore.Eligibility.Ineligible ->
                prepared.eligibility.reason
            !success -> "operation_failed"
            prepared.operation == WorkspaceChangeEntry.OPERATION_WRITE && prepared.beforeExists && prepared.beforeSnapshotName == null ->
                "missing_before_snapshot"
            else -> null
        }

        // 二次核对 after 文件的安全性与预算
        if (unavailableReason == null && afterExists) {
            if (checkHasSymlinkInChain(finalFile, prepared.workspaceRoot)) {
                unavailableReason = "symlink_rejected"
            } else if (finalFile.length() > WorkspaceChangeStore.MAX_SINGLE_FILE_BYTES) {
                unavailableReason = "file_too_large"
            } else if (changeDao.getTotalSnapshotBytes() > WorkspaceChangeStore.GLOBAL_SNAPSHOT_QUOTA_BYTES) {
                unavailableReason = "quota_exceeded"
            } else if (isTextFile(finalFile)) {
                val afterText = runCatching { finalFile.readText() }.getOrNull().orEmpty()
                val sanitized = SkillSuggestionRedactor.sanitize(afterText)
                if (sanitized.sensitiveDetected) {
                    unavailableReason = "sensitive_content"
                }
            }
        }

        val snapshotStatus = if (unavailableReason == null) {
            WorkspaceChangeEntry.SNAPSHOT_STATUS_READY
        } else {
            WorkspaceChangeEntry.SNAPSHOT_STATUS_UNAVAILABLE
        }

        val beforeManifestJson = buildJsonObject {
            put("path", prepared.sourceRelPath)
            put("exists", prepared.beforeExists)
            put("sha256", prepared.beforeSha256)
            put("snapshotName", prepared.beforeSnapshotName)
            if (prepared.targetRelPath != null) {
                put("targetPath", prepared.targetRelPath)
                put("targetBeforeExists", prepared.targetBeforeExists)
                put("targetBeforeSha256", prepared.targetBeforeSha256)
                put("targetBeforeSnapshotName", prepared.targetBeforeSnapshotName)
            }
        }.toString()

        val afterManifestJson = buildJsonObject {
            put("path", prepared.targetRelPath ?: prepared.sourceRelPath)
            put("exists", afterExists)
            put("sha256", afterSha256)
            if (prepared.targetRelPath != null) {
                put("sourceAfterExists", sourceAfterExists)
                put("sourceAfterSha256", sourceAfterSha256)
            }
        }.toString()

        val restoreJournalJson = buildJsonObject {
            put("changeId", prepared.changeId)
            put("status", "recorded")
            put("timestamp", System.currentTimeMillis())
            if (!success) {
                put("error", "operation_failed")
            }
        }.toString()

        val entry = WorkspaceChangeEntry(
            changeId = prepared.changeId,
            conversationId = conversationId,
            sessionId = sessionId,
            turnId = turnId,
            toolCallId = trimmedToolCallId,
            workspaceRoot = prepared.canonicalRoot,
            operation = prepared.operation,
            sourceRelativePath = prepared.sourceRelPath,
            targetRelativePath = prepared.targetRelPath,
            beforeSha256 = prepared.beforeSha256,
            afterSha256 = afterSha256,
            beforeExists = prepared.beforeExists,
            afterExists = afterExists,
            snapshotStatus = snapshotStatus,
            revision = 1L,
            createdAt = System.currentTimeMillis(),
            beforeManifestJson = beforeManifestJson,
            afterManifestJson = afterManifestJson,
            restoreJournalJson = restoreJournalJson,
            snapshotBytes = prepared.beforeBytes,
            unavailableReason = unavailableReason
        )

        return runCatching {
            changeDao.insert(entry)
            entry
        }.getOrNull()
    }

    /**
     * 针对 Conversation 与 Turn 查询变更列表。
     */
    suspend fun workspaceChangesList(
        conversationId: Long,
        turnId: String? = null
    ): List<WorkspaceChangeEntry> {
        return changeDao.getByConversationIdAndTurnId(conversationId, turnId)
    }

    suspend fun workspaceChangesListByWorkspace(
        workspaceRoot: String,
        limit: Int = 20,
        offset: Int = 0
    ): List<WorkspaceChangeEntry> {
        val canonical = SubagentResourceGate.canonicalizeRoot(workspaceRoot)
        val clampedLimit = limit.coerceIn(1, 50)
        return changeDao.getByWorkspaceRootPaged(canonical, clampedLimit, offset)
    }

    /**
     * 变更预览，生成统一 Unified Diff 并返回 expectedAfterHashes 供用户确认恢复。
     */
    suspend fun workspaceChangePreview(
        changeId: String,
        conversationId: Long? = null,
        workspaceRoot: String? = null
    ): WorkspaceChangePreview? {
        val entry = changeDao.getById(changeId)
            ?: throw WorkspaceChangeException("not_found", "未找到指定变更记录：$changeId")

        if (conversationId != null && entry.conversationId != conversationId) {
            throw WorkspaceChangeException("forbidden", "无权访问此会话下的工作区变更")
        }
        if (workspaceRoot != null) {
            val canonicalQuery = SubagentResourceGate.canonicalizeRoot(workspaceRoot)
            if (entry.workspaceRoot != canonicalQuery) {
                throw WorkspaceChangeException("forbidden", "工作区根路径不匹配")
            }
        }

        val relPath = entry.targetRelativePath ?: entry.sourceRelativePath.orEmpty()
        val file = File(entry.workspaceRoot, relPath)

        val beforeSnapshotName = entry.beforeSnapshotName()
        val beforeFile = if (beforeSnapshotName != null) {
            changeStore.getSnapshotFile(changeId, beforeSnapshotName)
        } else null

        val isSensitive = entry.unavailableReason in setOf("sensitive_path", "sensitive_content") ||
            changeStore.checkEligibility(file, File(entry.workspaceRoot)) is WorkspaceChangeStore.Eligibility.Ineligible

        val isText = !isSensitive && (beforeFile?.let { isTextFile(it) } ?: (file.exists() && isTextFile(file)))
        val beforeText = if (!isSensitive && beforeFile != null && beforeFile.exists() && isText) {
            runCatching { beforeFile.readText() }.getOrDefault("")
        } else ""

        val afterText = if (!isSensitive && file.exists() && file.isFile && isText) {
            runCatching { file.readText() }.getOrDefault("")
        } else ""

        val diffText = when {
            isSensitive -> "[敏感文件或包含凭据，内容已隐藏]"
            isText -> WorkspaceUnifiedDiff.generate(beforeText, afterText, relPath)
            else -> "[二进制文件，大小：before=${beforeFile?.length() ?: 0}B, after=${if (file.exists()) file.length() else 0}B]"
        }

        val reconciliation = reconcileRestoreJournal(entry)
        val isConflict = entry.snapshotStatus == WorkspaceChangeEntry.SNAPSHOT_STATUS_READY && reconciliation.status != "consistent_unrestored"
        val effectiveStatus = if (isConflict) WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT else entry.snapshotStatus
        val effectiveUnavailableReason = if (isConflict) "disk_modified_externally" else entry.unavailableReason
        val canRestore = effectiveStatus == WorkspaceChangeEntry.SNAPSHOT_STATUS_READY
        val expectedAfterHashes: Map<String, String?> = when (entry.operation) {
            WorkspaceChangeEntry.OPERATION_MOVE -> linkedMapOf(
                entry.sourceRelativePath.orEmpty() to null,
                entry.targetRelativePath.orEmpty() to entry.afterSha256
            )
            else -> linkedMapOf(relPath to entry.afterSha256)
        }

        return WorkspaceChangePreview(
            changeId = entry.changeId,
            conversationId = entry.conversationId,
            turnId = entry.turnId,
            workspaceRoot = entry.workspaceRoot,
            operation = entry.operation,
            relativePath = relPath,
            sourceRelativePath = entry.sourceRelativePath,
            targetRelativePath = entry.targetRelativePath,
            beforeSha256 = entry.beforeSha256,
            afterSha256 = entry.afterSha256,
            beforeExists = entry.beforeExists,
            afterExists = entry.afterExists,
            snapshotStatus = effectiveStatus,
            revision = entry.revision,
            createdAt = entry.createdAt,
            diffText = diffText,
            isBinary = !isText,
            canRestore = canRestore,
            unavailableReason = effectiveUnavailableReason,
            expectedAfterHashes = expectedAfterHashes
        )
    }

    /**
     * 执行明确确认恢复。
     *
     * 恢复前校验：
     * 1. 权限与所有权匹配（conversationId / workspaceRoot）。
     * 2. 工作区写租约独占 tryAcquire（忙则立即抛出 workspace_change_busy）。
     * 3. 严格比对 expectedAfterHashes 与当前磁盘文件状态（包括 move 的 source=null 与 target=afterSha256），不符则拒绝。
     * 4. 校验 source 与 target 的完整父目录链是否存在符号链接（拒绝跟随 symlink）。
     * 5. 获取共享 WorkspacePathLock。
     * 6. 执行恢复并读回校验（move 的 source 与 target 双端均完整验证）。
     * 7. 写入结构化 restoreJournalJson（prepared / applied / verified / failed 各阶段状态）。
     * 8. CAS 递增 revision 并置为 restored。
     */
    suspend fun workspaceChangeRestore(
        changeId: String,
        expectedRevision: Long,
        expectedAfterHashes: Map<String, String?>,
        conversationId: Long? = null,
        workspaceRoot: String? = null
    ): WorkspaceChangeRestoreResult {
        val entry = changeDao.getById(changeId)
            ?: throw WorkspaceChangeException("not_found", "未找到指定变更记录：$changeId")

        if (conversationId != null && entry.conversationId != conversationId) {
            throw WorkspaceChangeException("forbidden", "无权访问此会话下的工作区变更")
        }
        if (workspaceRoot != null) {
            val canonicalQuery = SubagentResourceGate.canonicalizeRoot(workspaceRoot)
            if (entry.workspaceRoot != canonicalQuery) {
                throw WorkspaceChangeException("forbidden", "工作区根路径不匹配")
            }
        }

        if (entry.revision != expectedRevision) {
            throw WorkspaceChangeException(
                "workspace_change_conflict",
                "版本已变化（当前 revision=${entry.revision}），请刷新重试",
                mapOf("changeId" to changeId, "currentRevision" to entry.revision)
            )
        }

        if (entry.snapshotStatus != WorkspaceChangeEntry.SNAPSHOT_STATUS_READY) {
            throw WorkspaceChangeException(
                "workspace_change_unavailable",
                "快照不可恢复：${entry.unavailableReason ?: entry.snapshotStatus}",
                mapOf("changeId" to changeId, "status" to entry.snapshotStatus)
            )
        }

        // 1. 获取工作区排他租约（非阻塞）
        val lease = SubagentResourceGate.tryAcquireExclusive(entry.workspaceRoot)
            ?: throw WorkspaceChangeException(
                "workspace_change_busy",
                "工作区正被活跃任务占用，请稍后再试",
                mapOf("changeId" to changeId, "workspaceRoot" to entry.workspaceRoot)
            )

        var currentRevision = entry.revision
        val journalSteps = mutableListOf<Map<String, Any?>>()
        val restoredFiles = mutableListOf<String>()

        try {
            val root = File(entry.workspaceRoot)
            val relPath = entry.targetRelativePath ?: entry.sourceRelativePath.orEmpty()
            val targetFile = File(root, relPath)
            val sourceFile = entry.sourceRelativePath?.let { File(root, it) }

            // 2. 先基于持久化记录构建不可变预期指纹 recordExpected
            val recordExpected: Map<String, String?> = when (entry.operation) {
                WorkspaceChangeEntry.OPERATION_MOVE -> linkedMapOf(
                    entry.sourceRelativePath.orEmpty() to null,
                    entry.targetRelativePath.orEmpty() to entry.afterSha256
                )
                else -> linkedMapOf(relPath to entry.afterSha256)
            }

            // 3. 严格核验客户端 expectedAfterHashes 与 recordExpected 全量等值（防恶意/旧客户端传入外部修改hash）
            if (expectedAfterHashes.keys != recordExpected.keys) {
                throw WorkspaceChangeException(
                    "workspace_change_conflict",
                    "客户端预期的文件集合与变更记录不匹配（客户端: ${expectedAfterHashes.keys}, 记录: ${recordExpected.keys}）",
                    mapOf("expectedKeys" to recordExpected.keys, "clientKeys" to expectedAfterHashes.keys)
                )
            }
            for ((path, recordedHash) in recordExpected) {
                val clientHash = expectedAfterHashes[path]
                if (clientHash != recordedHash) {
                    throw WorkspaceChangeException(
                        "workspace_change_conflict",
                        "客户端传入的预期哈希与持久化记录不符（文件: $path, 记录: $recordedHash, 客户端: $clientHash）",
                        mapOf("path" to path, "recordedHash" to recordedHash, "clientHash" to clientHash)
                    )
                }
            }

            // 4. 获取共享路径锁（严格按字典序排序），在同一租约+路径锁内核验磁盘与执行恢复，杜绝竞态
            val filesToLock = listOfNotNull(sourceFile, targetFile)

            WorkspacePathLock.withPathLocks(filesToLock) {
                // 5. 校验符号链接（递归检查父链直到 workspaceRoot）
                checkNoSymlinkInPathChain(targetFile, root)
                if (sourceFile != null && entry.operation == WorkspaceChangeEntry.OPERATION_MOVE) {
                    checkNoSymlinkInPathChain(sourceFile, root)
                }

                // 6. 核验当前实际磁盘内容与 recordExpected 是否完全一致
                for ((checkRelPath, expectedHash) in recordExpected) {
                    val file = File(root, checkRelPath)
                    val currentSha = if (file.exists() && file.isFile) changeStore.sha256(file) else null
                    val currentExists = file.exists()

                    if (expectedHash == null) {
                        if (currentExists) {
                            recordConflictJournal(entry, checkRelPath, null, currentSha)
                            throw WorkspaceChangeException(
                                "workspace_change_conflict",
                                "文件 $checkRelPath 预期不存在，但当前已存在于磁盘",
                                mapOf("file" to checkRelPath, "currentSha" to currentSha)
                            )
                        }
                    } else {
                        if (!currentExists) {
                            recordConflictJournal(entry, checkRelPath, expectedHash, null)
                            throw WorkspaceChangeException(
                                "workspace_change_conflict",
                                "文件 $checkRelPath 预期存在，但当前在磁盘上已缺失",
                                mapOf("file" to checkRelPath, "expectedSha" to expectedHash)
                            )
                        }
                        if (currentSha != expectedHash) {
                            recordConflictJournal(entry, checkRelPath, expectedHash, currentSha)
                            throw WorkspaceChangeException(
                                "workspace_change_conflict",
                                "文件 $checkRelPath 内容已被外部修改，拒绝覆盖（期望: $expectedHash, 实际: $currentSha）",
                                mapOf("file" to checkRelPath, "expectedSha" to expectedHash, "currentSha" to currentSha)
                            )
                        }
                    }
                }

                // 7. 预检快照存在与指纹（缺快照或指纹不匹配立即拒绝，严禁修改任何工作区磁盘文件）
                val sourceSnapshotFile: File?
                val targetBeforeSnapshotFile: File?

                when (entry.operation) {
                    WorkspaceChangeEntry.OPERATION_WRITE -> {
                        val snapshotName = entry.beforeSnapshotName()
                            ?: throw WorkspaceChangeException("error", "缺少前置快照文件名")
                        val beforeSnapshot = changeStore.getSnapshotFile(changeId, snapshotName)
                        if (!beforeSnapshot.exists()) {
                            throw WorkspaceChangeException("error", "快照文件已丢失: $snapshotName")
                        }
                        val snapSha = changeStore.sha256(beforeSnapshot)
                        if (snapSha != entry.beforeSha256) {
                            throw WorkspaceChangeException("error", "前置快照哈希不匹配（期望: ${entry.beforeSha256}, 实际: $snapSha）")
                        }
                        sourceSnapshotFile = beforeSnapshot
                        targetBeforeSnapshotFile = null
                    }
                    WorkspaceChangeEntry.OPERATION_CREATE -> {
                        sourceSnapshotFile = null
                        targetBeforeSnapshotFile = null
                    }
                    WorkspaceChangeEntry.OPERATION_MOVE -> {
                        val sourceSnapshotName = entry.beforeSnapshotName()
                            ?: throw WorkspaceChangeException("error", "缺少源文件前置快照文件名")
                        val srcSnap = changeStore.getSnapshotFile(changeId, sourceSnapshotName)
                        if (!srcSnap.exists()) {
                            throw WorkspaceChangeException("error", "源文件快照已丢失: $sourceSnapshotName")
                        }
                        val srcSnapSha = changeStore.sha256(srcSnap)
                        if (srcSnapSha != entry.beforeSha256) {
                            throw WorkspaceChangeException("error", "源文件快照哈希不匹配（期望: ${entry.beforeSha256}, 实际: $srcSnapSha）")
                        }
                        sourceSnapshotFile = srcSnap

                        val targetBeforeExists = entry.targetBeforeExists()
                        val targetBeforeSha = entry.targetBeforeSha256()
                        if (targetBeforeExists) {
                            val tgtSnapName = entry.targetBeforeSnapshotName()
                                ?: throw WorkspaceChangeException("error", "缺少目标覆盖文件前置快照文件名")
                            val tgtSnap = changeStore.getSnapshotFile(changeId, tgtSnapName)
                            if (!tgtSnap.exists()) {
                                throw WorkspaceChangeException("error", "目标覆盖文件快照已丢失: $tgtSnapName")
                            }
                            val tgtSnapSha = changeStore.sha256(tgtSnap)
                            if (tgtSnapSha != targetBeforeSha) {
                                throw WorkspaceChangeException("error", "目标覆盖文件快照哈希不匹配（期望: $targetBeforeSha, 实际: $tgtSnapSha）")
                            }
                            targetBeforeSnapshotFile = tgtSnap
                        } else {
                            targetBeforeSnapshotFile = null
                        }
                    }
                    else -> throw WorkspaceChangeException("error", "未知操作类型: ${entry.operation}")
                }

                // 阶段一：prepared
                journalSteps.add(
                    linkedMapOf(
                        "step" to "prepared",
                        "operation" to entry.operation,
                        "timestamp" to System.currentTimeMillis()
                    )
                )
                currentRevision = persistRestoreJournal(
                    changeId = changeId,
                    expectedRevision = currentRevision,
                    snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                    journalStatus = "prepared",
                    steps = journalSteps
                )

                // 阶段二：applied & verified（逐步持久化各操作点状态，异常不自动逆向重放）
                when (entry.operation) {
                    WorkspaceChangeEntry.OPERATION_WRITE -> {
                        val beforeSnapshot = requireNotNull(sourceSnapshotFile)
                        beforeSnapshot.copyTo(targetFile, overwrite = true)
                        journalSteps.add(
                            linkedMapOf("step" to "applied", "path" to relPath, "timestamp" to System.currentTimeMillis())
                        )
                        currentRevision = persistRestoreJournal(
                            changeId = changeId,
                            expectedRevision = currentRevision,
                            snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                            journalStatus = "applied",
                            steps = journalSteps
                        )

                        val restoredSha = changeStore.sha256(targetFile)
                        if (restoredSha != entry.beforeSha256) {
                            journalSteps.add(linkedMapOf("step" to "failed", "path" to relPath, "error" to "hash_mismatch", "actualSha" to restoredSha))
                            persistRestoreJournal(
                                changeId = changeId,
                                expectedRevision = currentRevision,
                                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                journalStatus = "failed",
                                steps = journalSteps,
                                errorMessage = "恢复后校验哈希不匹配"
                            )
                            throw WorkspaceChangeException("error", "恢复后校验哈希不匹配（期望: ${entry.beforeSha256}, 实际: $restoredSha）")
                        }
                        journalSteps.add(
                            linkedMapOf("step" to "verified", "path" to relPath, "sha256" to restoredSha)
                        )
                        restoredFiles.add(relPath)
                    }

                    WorkspaceChangeEntry.OPERATION_CREATE -> {
                        if (targetFile.exists()) {
                            val deleted = targetFile.delete()
                            if (targetFile.exists() || !deleted) {
                                journalSteps.add(linkedMapOf("step" to "failed", "path" to relPath, "error" to "delete_failed"))
                                persistRestoreJournal(
                                    changeId = changeId,
                                    expectedRevision = currentRevision,
                                    snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                    journalStatus = "failed",
                                    steps = journalSteps,
                                    errorMessage = "删除新建文件失败: ${targetFile.name}"
                                )
                                throw WorkspaceChangeException("error", "删除新建文件失败: ${targetFile.name}")
                            }
                        }
                        journalSteps.add(
                            linkedMapOf("step" to "applied", "path" to relPath, "action" to "deleted", "timestamp" to System.currentTimeMillis())
                        )
                        currentRevision = persistRestoreJournal(
                            changeId = changeId,
                            expectedRevision = currentRevision,
                            snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                            journalStatus = "applied",
                            steps = journalSteps
                        )

                        if (targetFile.exists()) {
                            journalSteps.add(linkedMapOf("step" to "failed", "path" to relPath, "error" to "delete_verification_failed"))
                            persistRestoreJournal(
                                changeId = changeId,
                                expectedRevision = currentRevision,
                                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                journalStatus = "failed",
                                steps = journalSteps,
                                errorMessage = "文件删除后依然存在: $relPath"
                            )
                            throw WorkspaceChangeException("error", "文件删除后依然存在: $relPath")
                        }
                        journalSteps.add(
                            linkedMapOf("step" to "verified", "path" to relPath, "exists" to false)
                        )
                        restoredFiles.add(relPath)
                    }

                    WorkspaceChangeEntry.OPERATION_MOVE -> {
                        val sourceRel = entry.sourceRelativePath.orEmpty()
                        val src = requireNotNull(sourceFile)
                        val srcSnap = requireNotNull(sourceSnapshotFile)

                        // 1. 恢复源文件
                        src.parentFile?.mkdirs()
                        srcSnap.copyTo(src, overwrite = true)
                        journalSteps.add(
                            linkedMapOf("step" to "applied", "path" to sourceRel, "action" to "restored_source", "timestamp" to System.currentTimeMillis())
                        )
                        currentRevision = persistRestoreJournal(
                            changeId = changeId,
                            expectedRevision = currentRevision,
                            snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                            journalStatus = "applied_source",
                            steps = journalSteps
                        )

                        // 读回校验源文件
                        val sourceRestoredSha = changeStore.sha256(src)
                        if (sourceRestoredSha != entry.beforeSha256) {
                            journalSteps.add(linkedMapOf("step" to "failed", "path" to sourceRel, "error" to "source_hash_mismatch", "actualSha" to sourceRestoredSha))
                            persistRestoreJournal(
                                changeId = changeId,
                                expectedRevision = currentRevision,
                                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                journalStatus = "failed",
                                steps = journalSteps,
                                errorMessage = "源文件恢复后哈希不匹配"
                            )
                            throw WorkspaceChangeException("error", "源文件恢复后哈希不匹配（期望: ${entry.beforeSha256}, 实际: $sourceRestoredSha）")
                        }
                        journalSteps.add(linkedMapOf("step" to "verified", "path" to sourceRel, "sha256" to sourceRestoredSha))
                        restoredFiles.add(sourceRel)
                        currentRevision = persistRestoreJournal(
                            changeId = changeId,
                            expectedRevision = currentRevision,
                            snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                            journalStatus = "verified_source",
                            steps = journalSteps
                        )

                        // 测试注入钩子（供单测验证中途异常后的部分恢复日志与只读对账）
                        onBeforeTargetRestoreForTesting?.invoke()

                        // 2. 恢复目标位置（还原被覆盖文件或删除目标）
                        val targetBeforeExists = entry.targetBeforeExists()
                        val targetBeforeSha = entry.targetBeforeSha256()

                        if (targetBeforeExists) {
                            val snap = requireNotNull(targetBeforeSnapshotFile)
                            snap.copyTo(targetFile, overwrite = true)
                            journalSteps.add(
                                linkedMapOf("step" to "applied", "path" to relPath, "action" to "restored_overwritten", "timestamp" to System.currentTimeMillis())
                            )
                            currentRevision = persistRestoreJournal(
                                changeId = changeId,
                                expectedRevision = currentRevision,
                                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                                journalStatus = "applied_target",
                                steps = journalSteps
                            )

                            // 读回校验被覆盖的目标文件
                            val targetRestoredSha = changeStore.sha256(targetFile)
                            if (targetRestoredSha != targetBeforeSha) {
                                journalSteps.add(linkedMapOf("step" to "failed", "path" to relPath, "error" to "target_hash_mismatch", "actualSha" to targetRestoredSha))
                                persistRestoreJournal(
                                    changeId = changeId,
                                    expectedRevision = currentRevision,
                                    snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                    journalStatus = "failed",
                                    steps = journalSteps,
                                    errorMessage = "目标文件恢复后哈希不匹配"
                                )
                                throw WorkspaceChangeException("error", "目标文件恢复后哈希不匹配（期望: $targetBeforeSha, 实际: $targetRestoredSha）")
                            }
                            journalSteps.add(linkedMapOf("step" to "verified", "path" to relPath, "sha256" to targetRestoredSha))
                        } else {
                            if (targetFile.exists()) {
                                val deleted = targetFile.delete()
                                if (targetFile.exists() || !deleted) {
                                    journalSteps.add(linkedMapOf("step" to "failed", "path" to relPath, "error" to "target_delete_failed"))
                                    persistRestoreJournal(
                                        changeId = changeId,
                                        expectedRevision = currentRevision,
                                        snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                        journalStatus = "failed",
                                        steps = journalSteps,
                                        errorMessage = "删除移动目标文件失败"
                                    )
                                    throw WorkspaceChangeException("error", "删除移动目标文件失败")
                                }
                            }
                            journalSteps.add(
                                linkedMapOf("step" to "applied", "path" to relPath, "action" to "deleted_target", "timestamp" to System.currentTimeMillis())
                            )
                            currentRevision = persistRestoreJournal(
                                changeId = changeId,
                                expectedRevision = currentRevision,
                                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                                journalStatus = "applied_target",
                                steps = journalSteps
                            )

                            if (targetFile.exists()) {
                                journalSteps.add(linkedMapOf("step" to "failed", "path" to relPath, "error" to "target_still_exists"))
                                persistRestoreJournal(
                                    changeId = changeId,
                                    expectedRevision = currentRevision,
                                    snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                                    journalStatus = "failed",
                                    steps = journalSteps,
                                    errorMessage = "移动目标文件仍然存在"
                                )
                                throw WorkspaceChangeException("error", "移动目标文件仍然存在")
                            }
                            journalSteps.add(linkedMapOf("step" to "verified", "path" to relPath, "exists" to false))
                        }
                        restoredFiles.add(relPath)
                    }
                }
            }

            // 阶段三：最终状态持久化为 RESTORED
            currentRevision = persistRestoreJournal(
                changeId = changeId,
                expectedRevision = currentRevision,
                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_RESTORED,
                journalStatus = "restored",
                steps = journalSteps
            )

            return WorkspaceChangeRestoreResult(
                success = true,
                status = "restored",
                message = "已成功恢复至变更前状态",
                changeId = changeId,
                revision = currentRevision,
                restoredFiles = restoredFiles,
                journal = journalSteps
            )
        } catch (e: Throwable) {
            if (e !is WorkspaceChangeException && journalSteps.isNotEmpty()) {
                journalSteps.add(linkedMapOf("step" to "failed", "path" to (entry.targetRelativePath ?: entry.sourceRelativePath.orEmpty()), "error" to (e.message ?: "unexpected_error")))
                runCatching {
                    persistRestoreJournal(
                        changeId = changeId,
                        expectedRevision = currentRevision,
                        snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
                        journalStatus = "failed",
                        steps = journalSteps,
                        errorMessage = e.message
                    )
                }
            }
            throw e
        } finally {
            lease.release()
        }
    }

    private suspend fun persistRestoreJournal(
        changeId: String,
        expectedRevision: Long,
        snapshotStatus: String,
        journalStatus: String,
        steps: List<Map<String, Any?>>,
        errorMessage: String? = null
    ): Long {
        val newRevision = expectedRevision + 1
        val journalJson = buildJsonObject {
            put("status", journalStatus)
            put("updatedAt", System.currentTimeMillis())
            if (errorMessage != null) {
                put("error", errorMessage)
            }
            put("steps", buildJsonArray {
                steps.forEach { step ->
                    add(buildJsonObject {
                        step.forEach { (k, v) ->
                            when (v) {
                                null -> put(k, null as String?)
                                is Boolean -> put(k, v)
                                is Number -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        }
                    })
                }
            })
        }.toString()

        val rowsUpdated = changeDao.updateStatusCas(
            changeId = changeId,
            expectedRevision = expectedRevision,
            newSnapshotStatus = snapshotStatus,
            newRevision = newRevision,
            newRestoreJournalJson = journalJson
        )

        if (rowsUpdated == 0) {
            throw WorkspaceChangeException(
                "workspace_change_conflict",
                "恢复状态提交时发生并发版本冲突",
                mapOf("changeId" to changeId, "revision" to newRevision)
            )
        }

        return newRevision
    }

    /**
     * 清理快照记录与存储。
     * 遵循严格并发安全：先 CAS 置 snapshotStatus=UNAVAILABLE 且 snapshotBytes=0，成功后才删除磁盘快照。
     */
    suspend fun workspaceChangeDiscard(
        changeId: String,
        expectedRevision: Long,
        conversationId: Long? = null,
        workspaceRoot: String? = null
    ): Boolean {
        val entry = changeDao.getById(changeId)
            ?: throw WorkspaceChangeException("not_found", "未找到指定变更记录：$changeId")

        if (conversationId != null && entry.conversationId != conversationId) {
            throw WorkspaceChangeException("forbidden", "无权访问此会话下的工作区变更")
        }
        if (workspaceRoot != null) {
            val canonicalQuery = SubagentResourceGate.canonicalizeRoot(workspaceRoot)
            if (entry.workspaceRoot != canonicalQuery) {
                throw WorkspaceChangeException("forbidden", "工作区根路径不匹配")
            }
        }

        if (entry.revision != expectedRevision) {
            throw WorkspaceChangeException(
                "workspace_change_conflict",
                "版本已变化（当前 revision=${entry.revision}），请刷新重试"
            )
        }

        // 1. 先更新数据库释放配额（snapshotBytes=0）并更新 revision
        val updatedEntry = entry.copy(
            snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_UNAVAILABLE,
            snapshotBytes = 0L,
            revision = expectedRevision + 1,
            unavailableReason = "discarded_by_user"
        )
        val rows = changeDao.update(updatedEntry)
        if (rows == 0) {
            throw WorkspaceChangeException("workspace_change_conflict", "放弃快照并发冲突，请重试")
        }

        // 2. 数据库提交成功后，再删除磁盘物理快照文件
        changeStore.deleteSnapshot(changeId)
        return true
    }

    /**
     * 重启后只读对账恢复日志，不盲目重放。
     */
    fun reconcileRestoreJournal(entry: WorkspaceChangeEntry): RestoreReconciliationResult {
        val root = File(entry.workspaceRoot)
        val relPath = entry.targetRelativePath ?: entry.sourceRelativePath.orEmpty()
        val targetFile = File(root, relPath)
        val targetExists = targetFile.exists() && targetFile.isFile
        val targetSha = if (targetExists) changeStore.sha256(targetFile) else null

        return when (entry.operation) {
            WorkspaceChangeEntry.OPERATION_MOVE -> {
                val sourceFile = entry.sourceRelativePath?.let { File(root, it) }
                val sourceExists = sourceFile != null && sourceFile.exists() && sourceFile.isFile
                val sourceSha = if (sourceExists) changeStore.sha256(sourceFile!!) else null

                val targetBeforeExists = entry.targetBeforeExists()
                val targetBeforeSha = entry.targetBeforeSha256()

                val sourceMatchesBefore = sourceExists && sourceSha == entry.beforeSha256
                val targetMatchesBefore = if (targetBeforeExists) {
                    targetExists && targetSha == targetBeforeSha
                } else {
                    !targetExists
                }

                val sourceMatchesAfter = !sourceExists
                val targetMatchesAfter = targetExists && targetSha == entry.afterSha256

                val isBeforeMatch = sourceMatchesBefore && targetMatchesBefore
                val isAfterMatch = sourceMatchesAfter && targetMatchesAfter
                val isPartiallyRestored = (sourceMatchesBefore && !targetMatchesBefore) || (!sourceMatchesBefore && targetMatchesBefore)

                val details = linkedMapOf<String, Any?>(
                    "sourceExists" to sourceExists,
                    "targetExists" to targetExists,
                    "sourceSha" to sourceSha,
                    "targetSha" to targetSha,
                    "expectedBeforeSha" to entry.beforeSha256,
                    "targetBeforeSha" to targetBeforeSha,
                    "expectedAfterSha" to entry.afterSha256,
                    "sourceMatchesBefore" to sourceMatchesBefore,
                    "targetMatchesBefore" to targetMatchesBefore
                )

                when {
                    isBeforeMatch -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "consistent_restored",
                        message = "磁盘状态与已恢复日志一致",
                        details = details
                    )
                    entry.snapshotStatus == WorkspaceChangeEntry.SNAPSHOT_STATUS_READY && isAfterMatch -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "consistent_unrestored",
                        message = "磁盘状态与变更后一致，快照依然就绪",
                        details = details
                    )
                    isPartiallyRestored -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "partially_restored",
                        message = "磁盘状态处于部分恢复状态，源或目标未完全对齐",
                        details = details
                    )
                    else -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "conflict",
                        message = "磁盘文件状态与元数据记录不匹配",
                        details = details
                    )
                }
            }

            WorkspaceChangeEntry.OPERATION_WRITE -> {
                val isBeforeMatch = targetExists && targetSha == entry.beforeSha256
                val isAfterMatch = targetExists && targetSha == entry.afterSha256
                val details = mapOf(
                    "targetExists" to targetExists,
                    "targetSha" to targetSha,
                    "expectedBeforeSha" to entry.beforeSha256,
                    "expectedAfterSha" to entry.afterSha256
                )

                when {
                    isBeforeMatch -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "consistent_restored",
                        message = "磁盘状态与已恢复日志一致",
                        details = details
                    )
                    entry.snapshotStatus == WorkspaceChangeEntry.SNAPSHOT_STATUS_READY && isAfterMatch -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "consistent_unrestored",
                        message = "磁盘状态与变更后一致，快照依然就绪",
                        details = details
                    )
                    else -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "conflict",
                        message = "磁盘文件状态与元数据记录不匹配",
                        details = details
                    )
                }
            }

            WorkspaceChangeEntry.OPERATION_CREATE -> {
                val isBeforeMatch = !targetExists
                val isAfterMatch = targetExists && targetSha == entry.afterSha256
                val details = mapOf(
                    "targetExists" to targetExists,
                    "targetSha" to targetSha,
                    "expectedBeforeSha" to null,
                    "expectedAfterSha" to entry.afterSha256
                )

                when {
                    isBeforeMatch -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "consistent_restored",
                        message = "磁盘状态与已恢复日志一致",
                        details = details
                    )
                    entry.snapshotStatus == WorkspaceChangeEntry.SNAPSHOT_STATUS_READY && isAfterMatch -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "consistent_unrestored",
                        message = "磁盘状态与变更后一致，快照依然就绪",
                        details = details
                    )
                    else -> RestoreReconciliationResult(
                        changeId = entry.changeId,
                        status = "conflict",
                        message = "磁盘文件状态与元数据记录不匹配",
                        details = details
                    )
                }
            }

            else -> RestoreReconciliationResult(
                changeId = entry.changeId,
                status = "conflict",
                message = "未知操作类型: ${entry.operation}",
                details = mapOf("targetSha" to targetSha)
            )
        }
    }

    private suspend fun recordConflictJournal(
        entry: WorkspaceChangeEntry,
        path: String,
        expectedSha: String?,
        actualSha: String?
    ) {
        val conflictJournal = buildJsonObject {
            put("status", "conflict")
            put("detectedAt", System.currentTimeMillis())
            put("conflictPath", path)
            put("expectedSha", expectedSha)
            put("actualSha", actualSha)
        }.toString()
        changeDao.updateStatusCas(
            changeId = entry.changeId,
            expectedRevision = entry.revision,
            newSnapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT,
            newRevision = entry.revision + 1,
            newRestoreJournalJson = conflictJournal
        )
    }

    private fun checkNoSymlinkInPathChain(file: File, workspaceRoot: File) {
        var curr: File? = file
        val canonicalRootPath = workspaceRoot.canonicalFile.toPath()
        while (curr != null && curr.canonicalFile.toPath().startsWith(canonicalRootPath)) {
            if (Files.isSymbolicLink(curr.toPath())) {
                throw WorkspaceChangeException("symlink_rejected", "拒绝操作包含符号链接的路径: ${curr.path}")
            }
            if (curr.canonicalFile == workspaceRoot.canonicalFile) break
            curr = curr.parentFile
        }
    }

    private fun checkHasSymlinkInChain(file: File, workspaceRoot: File): Boolean {
        var curr: File? = file
        val canonicalRootPath = runCatching { workspaceRoot.canonicalFile.toPath() }.getOrNull() ?: return false
        while (curr != null && runCatching { curr.canonicalFile.toPath().startsWith(canonicalRootPath) }.getOrDefault(false)) {
            if (Files.isSymbolicLink(curr.toPath())) return true
            if (curr.canonicalFile == workspaceRoot.canonicalFile) break
            curr = curr.parentFile
        }
        return false
    }

    private fun WorkspaceChangeEntry.beforeSnapshotName(): String? {
        return runCatching {
            val manifest = json.parseToJsonElement(beforeManifestJson)
            (manifest.asJsonObjectOrNull()?.get("snapshotName") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
    }

    private fun WorkspaceChangeEntry.targetBeforeSnapshotName(): String? {
        return runCatching {
            val manifest = json.parseToJsonElement(beforeManifestJson)
            (manifest.asJsonObjectOrNull()?.get("targetBeforeSnapshotName") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
    }
    private fun WorkspaceChangeEntry.targetBeforeExists(): Boolean {
        return runCatching {
            val manifest = json.parseToJsonElement(beforeManifestJson)
            val prim = manifest.asJsonObjectOrNull()?.get("targetBeforeExists") as? JsonPrimitive
            prim?.contentOrNull?.toBooleanStrictOrNull() ?: false
        }.getOrDefault(false)
    }

    private fun WorkspaceChangeEntry.targetBeforeSha256(): String? {
        return runCatching {
            val manifest = json.parseToJsonElement(beforeManifestJson)
            (manifest.asJsonObjectOrNull()?.get("targetBeforeSha256") as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
    }


    private fun kotlinx.serialization.json.JsonElement.asJsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    private fun isTextFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        val textExtensions = setOf(
            "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx",
            "kt", "java", "py", "rs", "go", "c", "cpp", "h", "hpp", "sh", "bat", "ps1",
            "yaml", "yml", "toml", "ini", "conf", "gradle", "properties", "sql", "dart"
        )
        return ext in textExtensions
    }

    /**
     * 进程启动时的只读对账入口。
     * 扫描现有快照目录对应的记录，核查实际磁盘状态，不盲目重放。
     */
    suspend fun onProcessStart() {
        runCatching {
            val dummySnapshot = changeStore.getSnapshotDir("__dummy__")
            val baseChangesDir = dummySnapshot.parentFile ?: return@runCatching
            val dirs = baseChangesDir.listFiles()?.filter { it.isDirectory } ?: return@runCatching
            for (dir in dirs) {
                val changeId = dir.name
                val entry = changeDao.getById(changeId) ?: continue
                reconcileRestoreJournal(entry)
            }
        }
    }
}
