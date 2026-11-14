package cn.com.omnimind.bot.agent.workspace

import cn.com.omnimind.baselib.database.WorkspaceChangeDao
import cn.com.omnimind.baselib.database.WorkspaceChangeEntry
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentWorkspaceDescriptor
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.NoOpAgentRunControl
import cn.com.omnimind.bot.agent.runtime.SubagentResourceGate
import cn.com.omnimind.bot.agent.skill.SkillSuggestionRedactor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class WorkspaceChangeServiceTest {

    private lateinit var tempDir: File
    private lateinit var workspaceDir: File
    private lateinit var snapshotDir: File
    private lateinit var store: WorkspaceChangeStore
    private lateinit var fakeDao: FakeWorkspaceChangeDao
    private lateinit var service: WorkspaceChangeService

    @Before
    fun setUp() {
        tempDir = File("build/tmp/test-workspace-changes-${System.currentTimeMillis()}").apply { mkdirs() }
        workspaceDir = File(tempDir, "workspace").apply { mkdirs() }
        snapshotDir = File(tempDir, "snapshots").apply { mkdirs() }

        store = WorkspaceChangeStore(snapshotDir)
        fakeDao = FakeWorkspaceChangeDao()
        service = WorkspaceChangeService(fakeDao, store)
        SubagentResourceGate.resetForTesting()
    }

    @After
    fun tearDown() {
        SubagentResourceGate.resetForTesting()
        tempDir.deleteRecursively()
    }

    // --- 1. WorkspaceUnifiedDiff 纯函数行为测试 ---

    @Test
    fun `WorkspaceUnifiedDiff generates valid diff for line modifications`() {
        val before = "line1\nline2\nline3\nline4\nline5\n"
        val after = "line1\nline2\nline3_modified\nline4\nline5\n"
        val diff = WorkspaceUnifiedDiff.generate(before, after, "sample.txt")

        assertTrue(diff.startsWith("--- a/sample.txt"))
        assertTrue(diff.contains("+++ b/sample.txt"))
        assertTrue(diff.contains("-line3"))
        assertTrue(diff.contains("+line3_modified"))
        assertTrue(diff.contains(" line2"))
        assertTrue(diff.contains(" line4"))
    }

    @Test
    fun `WorkspaceUnifiedDiff returns empty string when before and after are identical`() {
        val content = "hello world\nsecond line"
        val diff = WorkspaceUnifiedDiff.generate(content, content, "sample.txt")
        assertEquals("", diff)
    }

    @Test
    fun `WorkspaceUnifiedDiff handles creation and deletion correctly`() {
        // 新建文件
        val createDiff = WorkspaceUnifiedDiff.generate("", "new line\n", "created.txt")
        assertTrue(createDiff.startsWith("--- /dev/null"))
        assertTrue(createDiff.contains("+++ b/created.txt"))
        assertTrue(createDiff.contains("+new line"))

        // 删除文件
        val deleteDiff = WorkspaceUnifiedDiff.generate("old line\n", "", "deleted.txt")
        assertTrue(deleteDiff.startsWith("--- a/deleted.txt"))
        assertTrue(deleteDiff.contains("+++ /dev/null"))
        assertTrue(deleteDiff.contains("-old line"))
    }

    @Test
    fun `WorkspaceUnifiedDiff truncates large diff above limits with notice`() {
        val hugeBefore = (1..6000).joinToString("\n") { "line $it" }
        val hugeAfter = (1..6000).joinToString("\n") { "line ${it}_changed" }
        val diff = WorkspaceUnifiedDiff.generate(hugeBefore, hugeAfter, "huge.txt")

        assertTrue("Large diff must be marked truncated", diff.contains("... [diff truncated: exceeds display limit]"))
    }

    // --- 2. SkillSuggestionRedactor 凭据与敏感模式脱敏测试 ---

    @Test
    fun `SkillSuggestionRedactor detects and redacts credentials, pem, and masks email phone`() {
        val raw = """
            sk-proj-1234567890abcdef12345
            Bearer my-secret-token-xyz
            api_key = "very-secret-value"
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA0
            -----END RSA PRIVATE KEY-----
            https://admin:p@ssword123@internal.corp.com
            https://example.com/api?token=secret_query_val
            Contact me at test.user@example.com or +86 13812345678
        """.trimIndent()

        val result = SkillSuggestionRedactor.sanitize(raw)
        assertTrue("Sensitive patterns must be detected", result.sensitiveDetected)
        assertFalse(result.text.contains("sk-proj-"))
        assertFalse(result.text.contains("very-secret-value"))
        assertFalse(result.text.contains("p@ssword123"))
        assertFalse(result.text.contains("secret_query_val"))
        assertFalse(result.text.contains("test.user@example.com"))
        assertFalse(result.text.contains("13812345678"))
        assertTrue(result.text.contains("[REDACTED_KEY]"))
        assertTrue(result.text.contains("[REDACTED_PEM_BLOCK]"))
        assertTrue(result.text.contains("[EMAIL]"))
        assertTrue(result.text.contains("[PHONE]"))
    }

    @Test
    fun `SkillSuggestionRedactor returns clean false for benign text`() {
        val clean = "This is a normal readme file without credentials."
        val result = SkillSuggestionRedactor.sanitize(clean)
        assertFalse("No sensitive credentials should be detected", result.sensitiveDetected)
        assertEquals(clean, result.text)
    }

    // --- 3. WorkspaceChangeStore 敏感路径、大小与资格检查 ---

    @Test
    fun `WorkspaceChangeStore rejects sensitive paths, outside paths, and oversized files`() {
        val envFile = File(workspaceDir, ".env").apply { writeText("KEY=123") }
        val gitFile = File(workspaceDir, ".git/config").apply { parentFile.mkdirs(); writeText("git config") }
        val secretFile = File(workspaceDir, "secrets/id_rsa").apply { parentFile.mkdirs(); writeText("private key") }
        val certFile = File(workspaceDir, "cert.pem").apply { writeText("cert") }
        val outsideFile = File(tempDir, "outside.txt").apply { writeText("outside") }

        assertTrue(store.checkEligibility(envFile, workspaceDir) is WorkspaceChangeStore.Eligibility.Ineligible)
        assertTrue(store.checkEligibility(gitFile, workspaceDir) is WorkspaceChangeStore.Eligibility.Ineligible)
        assertTrue(store.checkEligibility(secretFile, workspaceDir) is WorkspaceChangeStore.Eligibility.Ineligible)
        assertTrue(store.checkEligibility(certFile, workspaceDir) is WorkspaceChangeStore.Eligibility.Ineligible)
        assertTrue(store.checkEligibility(outsideFile, workspaceDir) is WorkspaceChangeStore.Eligibility.Ineligible)

        val normalFile = File(workspaceDir, "src/main.kt").apply { parentFile.mkdirs(); writeText("fun main() {}") }
        assertTrue(store.checkEligibility(normalFile, workspaceDir) is WorkspaceChangeStore.Eligibility.Eligible)
    }

    @Test
    fun `preview hides diffText for sensitive files and does not leak secrets`() = runBlocking {
        val envFile = File(workspaceDir, ".env").apply { writeText("OPENAI_KEY=sk-test-secret-123456789012\n") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareWriteOrEdit(envFile, workspaceDir, "write")
        val entry = service.recordChange(prepared, env, "call-sensitive", success = true)!!
        assertEquals("unavailable", entry.snapshotStatus)
        assertEquals("sensitive_path", entry.unavailableReason)

        val preview = service.workspaceChangePreview(entry.changeId)!!
        assertFalse(preview.canRestore)
        assertEquals("[敏感文件或包含凭据，内容已隐藏]", preview.diffText)
        assertFalse("Diff must never leak the secret key", preview.diffText.contains("sk-test-secret"))
    }
    // --- 4. WorkspaceChangeService 捕获、预览与恢复行为测试 ---

    @Test
    fun `write and edit capture change, produce diff preview, and allow clean restore`() = runBlocking {
        val file = File(workspaceDir, "hello.txt").apply { writeText("Hello World\nLine 2\n") }
        val env = FakeExecutionEnvironment()

        // 1. 模拟写入编辑：Hello World -> Hello OmniBot
        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("Hello OmniBot\nLine 2\n")
        val entry = requireNotNull(service.recordChange(prepared, env, "call-1", success = true))

        assertEquals("write", entry.operation)
        assertEquals("ready", entry.snapshotStatus)
        assertTrue(entry.beforeExists)
        assertTrue(entry.afterExists)

        // 2. 预览变更
        val preview = service.workspaceChangePreview(entry.changeId)
        assertNotNull(preview)
        assertTrue(preview!!.canRestore)
        assertTrue(preview.diffText.contains("-Hello World"))
        assertTrue(preview.diffText.contains("+Hello OmniBot"))

        // 3. 执行确认恢复
        val restoreResult = service.workspaceChangeRestore(
            changeId = entry.changeId,
            expectedRevision = entry.revision,
            expectedAfterHashes = mapOf("hello.txt" to entry.afterSha256)
        )

        assertTrue(restoreResult.success)
        assertEquals("restored", restoreResult.status)
        assertEquals("Hello World\nLine 2\n", file.readText())

        // 恢复后状态为 restored
        val updatedEntry = fakeDao.getById(entry.changeId)
        assertEquals("restored", updatedEntry?.snapshotStatus)
    }

    @Test
    fun `restore refuses with workspace_change_conflict when file was modified externally`() = runBlocking {
        val file = File(workspaceDir, "conflict.txt").apply { writeText("Original Content\n") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("Agent Content\n")
        val entry = service.recordChange(prepared, env, "call-1", success = true)!!

        // 外部第三方或用户修改为 Third Party Content
        file.writeText("Third Party Content\n")

        try {
            service.workspaceChangeRestore(
                changeId = entry.changeId,
                expectedRevision = entry.revision,
                expectedAfterHashes = mapOf("conflict.txt" to entry.afterSha256)
            )
            org.junit.Assert.fail("Expected WorkspaceChangeException")
        } catch (e: WorkspaceChangeException) {
            assertEquals("workspace_change_conflict", e.errorCode)
            assertTrue(e.message.contains("外部修改"))
        }
        assertEquals("Third Party Content\n", file.readText()) // 拒绝覆盖外部修改
    }

    @Test
    fun `restore rejects client altered expectedAfterHashes not matching recordExpected`() = runBlocking {
        val file = File(workspaceDir, "malicious.txt").apply { writeText("Initial\n") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("Agent Content\n")
        val entry = service.recordChange(prepared, env, "call-1", success = true)!!

        // 外部修改为 C
        file.writeText("External Modification C\n")
        val shaC = store.sha256(file)

        // 客户端企图传入外部修改 C 的 hash 作为 expectedAfterHashes 骗过检查
        try {
            service.workspaceChangeRestore(
                changeId = entry.changeId,
                expectedRevision = entry.revision,
                expectedAfterHashes = mapOf("malicious.txt" to shaC)
            )
            org.junit.Assert.fail("Expected WorkspaceChangeException when client passes hash not matching persisted record")
        } catch (e: WorkspaceChangeException) {
            assertEquals("workspace_change_conflict", e.errorCode)
            assertTrue(e.message.contains("与持久化记录不符"))
        }

        // 文件保持外部修改 C，零写入
        assertEquals("External Modification C\n", file.readText())
    }

    @Test
    fun `restore refuses with workspace_change_busy when active write lease is held`() = runBlocking {
        val file = File(workspaceDir, "busy.txt").apply { writeText("A\n") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("B\n")
        val entry = service.recordChange(prepared, env, "call-1", success = true)!!

        // 占用工作区独占租约
        val lease = SubagentResourceGate.acquireExclusive(workspaceDir.canonicalPath)

        try {
            service.workspaceChangeRestore(
                changeId = entry.changeId,
                expectedRevision = entry.revision,
                expectedAfterHashes = mapOf("busy.txt" to entry.afterSha256)
            )
            org.junit.Assert.fail("Expected WorkspaceChangeException for busy lease")
        } catch (e: WorkspaceChangeException) {
            assertEquals("workspace_change_busy", e.errorCode)
        } finally {
            lease.release()
        }

        // 释放租约后恢复成功
        val retryResult = service.workspaceChangeRestore(
            changeId = entry.changeId,
            expectedRevision = entry.revision,
            expectedAfterHashes = mapOf("busy.txt" to entry.afterSha256)
        )
        assertTrue(retryResult.success)
        assertEquals("restored", retryResult.status)
        val map = retryResult.toMap()
        assertEquals(true, map["success"])
        assertEquals("restored", map["status"])
    }

    @Test
    fun `move captures source and target and restores cleanly with dual-endpoint verification`() = runBlocking {
        val source = File(workspaceDir, "src.txt").apply { writeText("Move me") }
        val target = File(workspaceDir, "dst.txt")
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareMove(source, target, workspaceDir)
        source.copyTo(target, overwrite = true)
        source.delete()
        val entry = service.recordChange(prepared, env, "call-move", success = true)!!

        assertEquals("move", entry.operation)
        assertEquals("ready", entry.snapshotStatus)

        // 1. 预览必须返回 expectedAfterHashes (source=null, dst=hash)
        val preview = service.workspaceChangePreview(entry.changeId)!!
        assertEquals(null, preview.expectedAfterHashes["src.txt"])
        assertEquals(entry.afterSha256, preview.expectedAfterHashes["dst.txt"])

        // 2. 确认恢复移动：传入 expectedAfterHashes
        val restoreResult = service.workspaceChangeRestore(
            changeId = entry.changeId,
            expectedRevision = entry.revision,
            expectedAfterHashes = preview.expectedAfterHashes
        )

        assertTrue(restoreResult.success)
        assertTrue("Source file should be restored", source.exists())
        assertEquals("Move me", source.readText())
        assertFalse("Target file should be deleted on clean restore", target.exists())
        assertTrue(restoreResult.journal.isNotEmpty())

        // 3. 重启只读对账验证一致性
        val updatedEntry = fakeDao.getById(entry.changeId)!!
        val recon = service.reconcileRestoreJournal(updatedEntry)
        assertEquals("consistent_restored", recon.status)
    }

    @Test
    fun `move with overwritten target restores both source and overwritten target`() = runBlocking {
        val source = File(workspaceDir, "move_src.txt").apply { writeText("New Content") }
        val target = File(workspaceDir, "move_dst.txt").apply { writeText("Original Target Content") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareMove(source, target, workspaceDir)
        source.copyTo(target, overwrite = true)
        source.delete()
        val entry = service.recordChange(prepared, env, "call-move-overwrite", success = true)!!

        val preview = service.workspaceChangePreview(entry.changeId)!!
        val restoreResult = service.workspaceChangeRestore(
            changeId = entry.changeId,
            expectedRevision = entry.revision,
            expectedAfterHashes = preview.expectedAfterHashes
        )

        assertTrue(restoreResult.success)
        assertEquals("New Content", source.readText())
        assertEquals("Original Target Content", target.readText())
    }

    @Test
    fun `discard updates DB first and zeroes snapshotBytes before clearing files`() = runBlocking {
        val file = File(workspaceDir, "discard.txt").apply { writeText("Initial") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("Modified")
        val entry = service.recordChange(prepared, env, "call-1", success = true)!!
        assertTrue(entry.snapshotBytes > 0)

        val snapshotDir = store.getSnapshotDir(entry.changeId)
        assertTrue(snapshotDir.exists())

        val discarded = service.workspaceChangeDiscard(entry.changeId, entry.revision)
        assertTrue(discarded)

        val updatedEntry = fakeDao.getById(entry.changeId)!!
        assertEquals("unavailable", updatedEntry.snapshotStatus)
        assertEquals(0L, updatedEntry.snapshotBytes)
        assertFalse("Snapshot dir should be removed", snapshotDir.exists())
        assertEquals("Modified", file.readText()) // 工作区文件保持现状
    }

    @Test
    fun `recordChange returns null and purges shadow snapshot when identity is missing`() = runBlocking {
        val file = File(workspaceDir, "no_identity.txt").apply { writeText("Initial") }
        val envWithoutConversationId = object : AgentExecutionEnvironment by FakeExecutionEnvironment() {
            override val conversationId: Long? = null
        }

        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("Updated")
        val entry = service.recordChange(prepared, envWithoutConversationId, "call-1", success = true)

        assertNull("Entry must be null when conversationId is missing", entry)
        val snapshotDir = store.getSnapshotDir(prepared.changeId)
        assertFalse("Shadow snapshot must be cleaned up when identity is missing", snapshotDir.exists())
        assertEquals("Updated", file.readText()) // 工作区文件不受影响
    }

    @Test
    fun `restore does not modify disk and throws when snapshot file is missing`() = runBlocking {
        val file = File(workspaceDir, "missing_snap.txt").apply { writeText("Original Before Content") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareWriteOrEdit(file, workspaceDir, "write")
        file.writeText("Agent Modified Content")
        val entry = service.recordChange(prepared, env, "call-snap-miss", success = true)!!

        // 人为删除物理快照文件模拟快照丢失
        val snapshotFile = store.getSnapshotFile(entry.changeId, "before-${file.name}")
        assertTrue(snapshotFile.exists())
        snapshotFile.delete()
        assertFalse(snapshotFile.exists())

        try {
            service.workspaceChangeRestore(
                changeId = entry.changeId,
                expectedRevision = entry.revision,
                expectedAfterHashes = mapOf("missing_snap.txt" to entry.afterSha256)
            )
            org.junit.Assert.fail("Expected WorkspaceChangeException when snapshot file is missing")
        } catch (e: WorkspaceChangeException) {
            assertEquals("error", e.errorCode)
            assertTrue(e.message.contains("快照文件已丢失"))
        }

        // 核心断言：缺快照严禁修改工作区磁盘文件，工作区文件保持 Agent 修改内容，零写入
        assertEquals("Agent Modified Content", file.readText())
        val dbEntry = fakeDao.getById(entry.changeId)!!
        assertEquals("ready", dbEntry.snapshotStatus) // 未破坏数据库版本状态
    }

    @Test
    fun `move with overwritten target rejects restore when target snapshot fingerprint is corrupted`() = runBlocking {
        val source = File(workspaceDir, "src_fingerprint.txt").apply { writeText("Source Content") }
        val target = File(workspaceDir, "dst_fingerprint.txt").apply { writeText("Original Target Content") }
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareMove(source, target, workspaceDir)
        source.copyTo(target, overwrite = true)
        source.delete()
        val entry = service.recordChange(prepared, env, "call-move-corrupted-target-snap", success = true)!!

        // 人为篡改目标快照内容导致指纹不匹配
        val targetSnapshotFile = store.getSnapshotFile(entry.changeId, "target-before-${target.name}")
        assertTrue(targetSnapshotFile.exists())
        targetSnapshotFile.writeText("Corrupted Snapshot Content")

        try {
            service.workspaceChangeRestore(
                changeId = entry.changeId,
                expectedRevision = entry.revision,
                expectedAfterHashes = mapOf(
                    "src_fingerprint.txt" to null,
                    "dst_fingerprint.txt" to entry.afterSha256
                )
            )
            org.junit.Assert.fail("Expected WorkspaceChangeException when target snapshot fingerprint is corrupted")
        } catch (e: WorkspaceChangeException) {
            assertEquals("error", e.errorCode)
            assertTrue(e.message.contains("目标覆盖文件快照哈希不匹配"))
        }

        // 核心断言：严禁修改任何磁盘文件！源仍不存在，目标仍保持移动后的内容
        assertFalse("Source file must not be restored when target snapshot fingerprint is wrong", source.exists())
        assertTrue(target.exists())
        assertEquals("Source Content", target.readText())
    }

    @Test
    fun `partial restore persists progress journal and restart only reconciles without replay`() = runBlocking {
        val source = File(workspaceDir, "partial_src.txt").apply { writeText("Source Initial") }
        val target = File(workspaceDir, "partial_dst.txt")
        val env = FakeExecutionEnvironment()

        val prepared = service.prepareMove(source, target, workspaceDir)
        source.copyTo(target, overwrite = true)
        source.delete()
        val entry = service.recordChange(prepared, env, "call-partial", success = true)!!

        // 模拟在源文件恢复成功后、目标文件恢复处理时发生异常
        service.onBeforeTargetRestoreForTesting = {
            throw java.io.IOException("Simulated I/O failure during target cleanup")
        }

        try {
            service.workspaceChangeRestore(
                changeId = entry.changeId,
                expectedRevision = entry.revision,
                expectedAfterHashes = mapOf(
                    "partial_src.txt" to null,
                    "partial_dst.txt" to entry.afterSha256
                )
            )
            org.junit.Assert.fail("Expected simulated IOException during partial restore")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Simulated I/O failure"))
        } finally {
            service.onBeforeTargetRestoreForTesting = null
        }

        // 1. 源文件已恢复，目标文件尚未删除（不进行逆向重放或伪事务强杀）
        assertTrue("Source file should remain restored", source.exists())
        assertEquals("Source Initial", source.readText())
        assertTrue("Target file still exists because target cleanup failed", target.exists())

        // 2. 消费者可从数据库读取逐步记录的 partial restoreJournalJson
        val updatedEntry = fakeDao.getById(entry.changeId)!!
        assertEquals(WorkspaceChangeEntry.SNAPSHOT_STATUS_CONFLICT, updatedEntry.snapshotStatus)
        assertTrue(updatedEntry.restoreJournalJson.contains("prepared"))
        assertTrue(updatedEntry.restoreJournalJson.contains("applied"))
        assertTrue(updatedEntry.restoreJournalJson.contains("restored_source"))
        assertTrue(updatedEntry.restoreJournalJson.contains("verified"))
        assertTrue(updatedEntry.restoreJournalJson.contains("failed"))
        assertTrue(updatedEntry.restoreJournalJson.contains("Simulated I/O failure"))

        // 3. 重启只读对账：检测到部分恢复，返回 partially_restored，且绝不盲目重放或写盘
        val recon = service.reconcileRestoreJournal(updatedEntry)
        assertEquals("partially_restored", recon.status)
        assertEquals(true, recon.details["sourceMatchesBefore"])
        assertEquals(false, recon.details["targetMatchesBefore"])

        // 磁盘状态保持不变，零重放
        assertEquals("Source Initial", source.readText())
        assertTrue(target.exists())
    }

    // --- 内存 Mock DAO 实现 ---

    private class FakeWorkspaceChangeDao : WorkspaceChangeDao {
        val entries = ConcurrentHashMap<String, WorkspaceChangeEntry>()

        override suspend fun insert(entry: WorkspaceChangeEntry): Long {
            entries[entry.changeId] = entry
            return 1L
        }

        override suspend fun upsert(entry: WorkspaceChangeEntry): Long {
            entries[entry.changeId] = entry
            return 1L
        }

        override suspend fun update(entry: WorkspaceChangeEntry): Int {
            entries[entry.changeId] = entry
            return 1
        }

        override suspend fun delete(entry: WorkspaceChangeEntry): Int {
            return if (entries.remove(entry.changeId) != null) 1 else 0
        }

        override suspend fun getById(changeId: String): WorkspaceChangeEntry? {
            return entries[changeId]
        }

        override suspend fun getByConversationId(conversationId: Long): List<WorkspaceChangeEntry> {
            return entries.values.filter { it.conversationId == conversationId }
                .sortedWith(compareByDescending<WorkspaceChangeEntry> { it.createdAt }.thenByDescending { it.changeId })
        }

        override suspend fun getByConversationIdAndTurnId(conversationId: Long, turnId: String?): List<WorkspaceChangeEntry> {
            return entries.values.filter { it.conversationId == conversationId && (turnId == null || it.turnId == turnId) }
                .sortedWith(compareByDescending<WorkspaceChangeEntry> { it.createdAt }.thenByDescending { it.changeId })
        }

        override suspend fun getByWorkspaceRootPaged(workspaceRoot: String, limit: Int, offset: Int): List<WorkspaceChangeEntry> {
            return entries.values.filter { it.workspaceRoot == workspaceRoot }
                .sortedWith(compareByDescending<WorkspaceChangeEntry> { it.createdAt }.thenByDescending { it.changeId })
                .drop(offset).take(limit)
        }

        override suspend fun getByWorkspaceRoot(workspaceRoot: String): List<WorkspaceChangeEntry> {
            return entries.values.filter { it.workspaceRoot == workspaceRoot }
                .sortedWith(compareByDescending<WorkspaceChangeEntry> { it.createdAt }.thenByDescending { it.changeId })
        }

        override suspend fun countByWorkspaceRoot(workspaceRoot: String): Int {
            return entries.values.count { it.workspaceRoot == workspaceRoot }
        }

        override suspend fun countByConversationId(conversationId: Long): Int {
            return entries.values.count { it.conversationId == conversationId }
        }

        override suspend fun getTotalSnapshotBytes(): Long {
            return entries.values.sumOf { it.snapshotBytes }
        }

        override suspend fun updateStatusCas(
            changeId: String,
            expectedRevision: Long,
            newSnapshotStatus: String,
            newRevision: Long,
            newRestoreJournalJson: String
        ): Int {
            val existing = entries[changeId] ?: return 0
            if (existing.revision != expectedRevision) return 0
            entries[changeId] = existing.copy(
                snapshotStatus = newSnapshotStatus,
                revision = newRevision,
                restoreJournalJson = newRestoreJournalJson
            )
            return 1
        }

        override suspend fun updateSnapshotStatusCas(
            changeId: String,
            expectedRevision: Long,
            newSnapshotStatus: String,
            newRevision: Long
        ): Int {
            val existing = entries[changeId] ?: return 0
            if (existing.revision != expectedRevision) return 0
            entries[changeId] = existing.copy(
                snapshotStatus = newSnapshotStatus,
                revision = newRevision
            )
            return 1
        }

        override suspend fun deleteById(changeId: String): Int {
            return if (entries.remove(changeId) != null) 1 else 0
        }

        override suspend fun deleteByIdCas(changeId: String, expectedRevision: Long): Int {
            val existing = entries[changeId] ?: return 0
            if (existing.revision != expectedRevision) return 0
            entries.remove(changeId)
            return 1
        }

        override suspend fun deleteByConversationId(conversationId: Long): Int {
            val toRemove = entries.values.filter { it.conversationId == conversationId }.map { it.changeId }
            toRemove.forEach { entries.remove(it) }
            return toRemove.size
        }

        override suspend fun deleteByWorkspaceRoot(workspaceRoot: String): Int {
            val toRemove = entries.values.filter { it.workspaceRoot == workspaceRoot }.map { it.changeId }
            toRemove.forEach { entries.remove(it) }
            return toRemove.size
        }
    }

    private class FakeExecutionEnvironment : AgentExecutionEnvironment {
        override val agentRunId: String = "run-test"
        override val conversationId: Long = 100L
        override val acpSessionId: String = "session-test"
        override val acpTurnId: String = "turn-test"
        override val userMessage: String = "test msg"
        override val runtimeContextRepository = mock(cn.com.omnimind.bot.agent.AgentRuntimeContextRepository::class.java)
        override val workspaceDescriptor = mock(AgentWorkspaceDescriptor::class.java)
        override val workspaceManager = mock(AgentWorkspaceManager::class.java)
        override val resolvedSkills = emptyList<cn.com.omnimind.bot.agent.ResolvedSkillContext>()
        override val workspaceMemoryService = mock(cn.com.omnimind.bot.agent.WorkspaceMemoryService::class.java)
        override val conversationMode: String = "agent"
        override val reasoningEffort: String? = null
        override val modelProviderProfileId: String? = "test-provider"
        override val modelId: String? = "test-model"
        override val terminalEnvironment = emptyMap<String, String>()
        override val runControl = NoOpAgentRunControl
        override val permissionRequester = null
        override val turnJob = null
        override val longTermMemoryIndex = null
    }
}
