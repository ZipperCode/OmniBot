package cn.com.omnimind.baselib.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationV20Test {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun migration19To20_createsRequiredTablesAndIndexes() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("test_migration_19_20.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id INTEGER PRIMARY KEY NOT NULL)")
                        DatabaseHelper.MIGRATION_19_20.migrate(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val sqliteDb = helper.writableDatabase


            sqliteDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'conversation_outbox'"
            ).use { cursor ->
                assertTrue("conversation_outbox table must exist", cursor.moveToFirst())
            }

            sqliteDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'conversation_outbox_meta'"
            ).use { cursor ->
                assertTrue("conversation_outbox_meta table must exist", cursor.moveToFirst())
            }

            sqliteDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'workspace_changes'"
            ).use { cursor ->
                assertTrue("workspace_changes table must exist", cursor.moveToFirst())
            }

            val outboxIndexes = mutableSetOf<String>()
            sqliteDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'conversation_outbox'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    outboxIndexes.add(cursor.getString(0))
                }
            }
            assertTrue(
                "Must contain index_conversation_outbox_conversationId_state_sequence",
                outboxIndexes.contains("index_conversation_outbox_conversationId_state_sequence")
            )
            assertTrue(
                "Must contain index_conversation_outbox_requestId",
                outboxIndexes.contains("index_conversation_outbox_requestId")
            )

            val workspaceIndexes = mutableSetOf<String>()
            sqliteDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'workspace_changes'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    workspaceIndexes.add(cursor.getString(0))
                }
            }
            assertTrue(
                "Must contain index_workspace_changes_conversationId_turnId_createdAt",
                workspaceIndexes.contains("index_workspace_changes_conversationId_turnId_createdAt")
            )
        } finally {
            helper.close()
            context.deleteDatabase("test_migration_19_20.db")
        }
    }

    @Test
    fun roomInMemoryDatabase_operatesOutboxAndWorkspaceDaos() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val convId = db.conversationDao().insert(
                Conversation(title = "Migration Test Conversation")
            )

            val metaDao = db.conversationOutboxMetaDao()
            val meta = metaDao.getOrCreate(convId)
            assertEquals(0L, meta.revision)
            assertEquals(1L, meta.nextSequence)

            val alloc = metaDao.allocateSequence(convId, expectedRevision = 0L)
            assertNotNull(alloc)
            assertEquals(1L, alloc?.allocatedSequence)
            assertEquals(1L, alloc?.newRevision)

            val outboxDao = db.conversationOutboxDao()
            val entry = ConversationOutboxEntry(
                id = "outbox-unit-1",
                conversationId = convId,
                agentId = "xiaowan",
                payloadJson = "{\"test\":true}",
                state = ConversationOutboxEntry.STATE_PENDING,
                revision = alloc!!.newRevision,
                sequence = alloc.allocatedSequence,
                requestId = "req-unit-1",
                userMessageId = "msg-unit-1",
                createdAt = 1000L,
                updatedAt = 1000L
            )
            outboxDao.insert(entry)

            val fetched = outboxDao.getById("outbox-unit-1")
            assertNotNull(fetched)
            assertEquals("outbox-unit-1", fetched?.id)
            assertEquals(convId, fetched?.conversationId)
            assertEquals(ConversationOutboxEntry.STATE_PENDING, fetched?.state)

            val active = outboxDao.getActiveByConversationId(convId)
            assertEquals(1, active.size)

            val casUpdated = outboxDao.updateStateCas(
                id = "outbox-unit-1",
                expectedRevision = 1L,
                newState = ConversationOutboxEntry.STATE_DISPATCHING,
                newRevision = 2L,
                updatedAt = 1100L
            )
            assertEquals(1, casUpdated)

            val wsDao = db.workspaceChangeDao()
            val wsEntry = WorkspaceChangeEntry(
                changeId = "ws-change-1",
                conversationId = convId,
                sessionId = "sess-1",
                turnId = "turn-1",
                toolCallId = "tool-1",
                workspaceRoot = "/data/workspace/proj",
                operation = WorkspaceChangeEntry.OPERATION_WRITE,
                sourceRelativePath = "main.py",
                targetRelativePath = null,
                beforeSha256 = null,
                afterSha256 = "abc123sha",
                beforeExists = false,
                afterExists = true,
                snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                revision = 1L,
                createdAt = 1000L,
                beforeManifestJson = "{}",
                afterManifestJson = "{}",
                restoreJournalJson = "[]",
                snapshotBytes = 256L,
                unavailableReason = null
            )
            wsDao.insert(wsEntry)

            val fetchedWs = wsDao.getById("ws-change-1")
            assertNotNull(fetchedWs)
            assertEquals("ws-change-1", fetchedWs?.changeId)
            assertEquals("/data/workspace/proj", fetchedWs?.workspaceRoot)
            assertEquals(256L, fetchedWs?.snapshotBytes)

            val byRoot = wsDao.getByWorkspaceRoot("/data/workspace/proj")
            assertEquals(1, byRoot.size)

            val totalBytes = wsDao.getTotalSnapshotBytes()
            assertEquals(256L, totalBytes)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration19To20_preservesExistingData_andEnforcesForeignKeyCascade() = runBlocking {
        val dbName = "test_migration_19_20_data.db"
        context.deleteDatabase(dbName)
        try {
            createVersion19Database(dbName)

            val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(DatabaseHelper.MIGRATION_19_20)
                .allowMainThreadQueries()
                .build()

            try {
                // 开启 SQLite 外键约束检查
                roomDb.openHelper.writableDatabase.setForeignKeyConstraintsEnabled(true)

                // 1. 验证迁移后原 Conversation 及其关键字段完整保留
                val conv = roomDb.conversationDao().getById(101L)
                assertNotNull("Preserved conversation must exist", conv)
                assertEquals("Pre-migration Chat", conv?.title)
                assertEquals("agent", conv?.mode)
                assertTrue("isPinned flag must be preserved", conv?.isPinned == true)
                assertEquals(5, conv?.messageCount)
                assertEquals(128000, conv?.promptTokenThreshold)

                // 2. 验证迁移后原历史记录（AgentConversationEntry）完整保留
                val entry = roomDb.agentConversationEntryDao().getByThreadAndEntryId(101L, "agent", "entry-seed-1")
                assertNotNull("Preserved history entry must exist", entry)
                assertEquals("seed summary", entry?.summary)
                assertEquals("completed", entry?.status)
                assertTrue(entry?.payloadJson?.contains("pre-migration user text") == true)

                // 3. 验证迁移后原会话绑定（AgentSessionBinding）完整保留
                val binding = roomDb.agentSessionBindingDao().getByConversationId(101L)
                assertNotNull("Preserved session binding must exist", binding)
                assertEquals("thread-codex-1", binding?.threadId)

                // 4. 验证迁移后原 Token 消耗记录完整保留
                val usageRecords = roomDb.tokenUsageRecordDao().getRecordsSince(0L)
                val usage = usageRecords.find { it.conversationId == 101L }
                assertNotNull("Preserved token usage record must exist", usage)
                assertEquals("gpt-4o", usage?.model)
                assertEquals(120, usage?.promptTokens)

                // 5. 在迁移到 v20 的库上写入新表记录（关联 conversation 101L）
                val outboxDao = roomDb.conversationOutboxDao()
                val metaDao = roomDb.conversationOutboxMetaDao()
                val wsDao = roomDb.workspaceChangeDao()

                val meta = metaDao.getOrCreate(101L)
                assertEquals(0L, meta.revision)
                assertEquals(1L, meta.nextSequence)

                val outboxEntry = ConversationOutboxEntry(
                    id = "outbox-migrated-1",
                    conversationId = 101L,
                    agentId = "xiaowan",
                    payloadJson = "{\"prompt\":\"hello\"}",
                    state = ConversationOutboxEntry.STATE_PENDING,
                    revision = 1L,
                    sequence = 1L,
                    requestId = "req-migrated-1",
                    userMessageId = "msg-migrated-1",
                    createdAt = 2000L,
                    updatedAt = 2000L
                )
                outboxDao.insert(outboxEntry)
                assertNotNull(outboxDao.getById("outbox-migrated-1"))

                val wsEntry = WorkspaceChangeEntry(
                    changeId = "ws-migrated-1",
                    conversationId = 101L,
                    sessionId = "sess-1",
                    turnId = "turn-1",
                    toolCallId = "tool-1",
                    workspaceRoot = "/workspace/project",
                    operation = WorkspaceChangeEntry.OPERATION_WRITE,
                    sourceRelativePath = "src/main.kt",
                    targetRelativePath = null,
                    beforeSha256 = null,
                    afterSha256 = "sha256after",
                    beforeExists = false,
                    afterExists = true,
                    snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                    revision = 1L,
                    createdAt = 2000L,
                    beforeManifestJson = "{}",
                    afterManifestJson = "{}",
                    restoreJournalJson = "[]",
                    snapshotBytes = 512L,
                    unavailableReason = null
                )
                wsDao.insert(wsEntry)
                assertNotNull(wsDao.getById("ws-migrated-1"))

                // 6. 验证外键约束拒绝：插入不存在的 conversationId 应触发约束异常
                try {
                    outboxDao.insert(
                        outboxEntry.copy(
                            id = "outbox-invalid-fk",
                            conversationId = 99999L,
                            requestId = "req-invalid-fk"
                        )
                    )
                    fail("Inserting outbox entry with non-existent conversationId must fail foreign key constraint")
                } catch (e: Exception) {
                    assertTrue(
                        "Expected SQLite constraint exception for foreign key violation: ${e::class.java.name}",
                        e is android.database.sqlite.SQLiteConstraintException ||
                            e.message?.contains("FOREIGN KEY", ignoreCase = true) == true
                    )
                }

                // 7. 验证外键 CASCADE 级联删除：
                // 删除 conversation 101L 时，conversation_outbox 与 conversation_outbox_meta 必须级联删除；
                // 而 workspace_changes 不设级联删除（保留以供工作区审计与恢复管理）。
                val deletedCount = roomDb.conversationDao().deleteById(101L)
                assertEquals(1, deletedCount)
                assertNull(roomDb.conversationDao().getById(101L))

                // Outbox 必须已被级联删除
                assertNull("Outbox entry must be deleted by cascade", outboxDao.getById("outbox-migrated-1"))
                assertEquals(0, outboxDao.getActiveByConversationId(101L).size)

                // Meta 必须已被级联删除
                assertNull("Outbox meta must be deleted by cascade", metaDao.getByConversationId(101L))

                // Workspace change 必须保留，不能级联删除
                val retainedWs = wsDao.getById("ws-migrated-1")
                assertNotNull("Workspace change must NOT be cascade deleted", retainedWs)
                assertEquals(101L, retainedWs?.conversationId)
            } finally {
                roomDb.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration19To20_verifiesCasRevisionAndSequenceAllocation() = runBlocking {
        val dbName = "test_migration_19_20_cas.db"
        context.deleteDatabase(dbName)
        try {
            createVersion19Database(dbName)

            val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(DatabaseHelper.MIGRATION_19_20)
                .allowMainThreadQueries()
                .build()

            try {
                val metaDao = roomDb.conversationOutboxMetaDao()
                val outboxDao = roomDb.conversationOutboxDao()
                val wsDao = roomDb.workspaceChangeDao()

                // 初始 meta 状态
                val meta0 = metaDao.getOrCreate(101L)
                assertEquals(0L, meta0.revision)
                assertEquals(1L, meta0.nextSequence)

                // 1. CAS allocateSequence：匹配期望版本成功分配
                val alloc1 = metaDao.allocateSequence(101L, expectedRevision = 0L)
                assertNotNull("Allocation with matching revision must succeed", alloc1)
                assertEquals(1L, alloc1?.allocatedSequence)
                assertEquals(1L, alloc1?.newRevision)

                // 2. CAS allocateSequence 冲突：陈旧 expectedRevision 返回 null，阻止并发分配
                val allocConflict = metaDao.allocateSequence(101L, expectedRevision = 0L)
                assertNull("Allocation with stale expectedRevision must return null (conflict)", allocConflict)

                // 3. CAS allocateSequence 下一次分配：严格单调递增
                val alloc2 = metaDao.allocateSequence(101L, expectedRevision = 1L)
                assertNotNull(alloc2)
                assertEquals(2L, alloc2?.allocatedSequence)
                assertEquals(2L, alloc2?.newRevision)

                // 4. Outbox state update CAS
                val entry = ConversationOutboxEntry(
                    id = "outbox-cas-1",
                    conversationId = 101L,
                    agentId = "xiaowan",
                    payloadJson = "{}",
                    state = ConversationOutboxEntry.STATE_PENDING,
                    revision = alloc2!!.newRevision,
                    sequence = alloc2.allocatedSequence,
                    requestId = "req-cas-1",
                    userMessageId = "msg-cas-1",
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
                outboxDao.insert(entry)

                // 陈旧版本 CAS 更新返回 0，状态不变
                val staleUpdate = outboxDao.updateStateCas(
                    id = "outbox-cas-1",
                    expectedRevision = 1L,
                    newState = ConversationOutboxEntry.STATE_DISPATCHING,
                    newRevision = 3L,
                    updatedAt = 2000L
                )
                assertEquals("Stale CAS update must affect 0 rows", 0, staleUpdate)
                assertEquals(ConversationOutboxEntry.STATE_PENDING, outboxDao.getById("outbox-cas-1")?.state)

                // 匹配版本 CAS 更新返回 1，状态成功推进
                val successUpdate = outboxDao.updateStateCas(
                    id = "outbox-cas-1",
                    expectedRevision = 2L,
                    newState = ConversationOutboxEntry.STATE_DISPATCHING,
                    newRevision = 3L,
                    updatedAt = 2000L
                )
                assertEquals("Matching CAS update must affect 1 row", 1, successUpdate)
                val updatedEntry = outboxDao.getById("outbox-cas-1")
                assertEquals(ConversationOutboxEntry.STATE_DISPATCHING, updatedEntry?.state)
                assertEquals(3L, updatedEntry?.revision)

                // 5. Workspace change CAS 删除
                val wsEntry = WorkspaceChangeEntry(
                    changeId = "ws-cas-1",
                    conversationId = 101L,
                    sessionId = "sess-cas",
                    turnId = "turn-cas",
                    toolCallId = "tool-cas",
                    workspaceRoot = "/workspace",
                    operation = WorkspaceChangeEntry.OPERATION_WRITE,
                    sourceRelativePath = "cas.txt",
                    targetRelativePath = null,
                    beforeSha256 = null,
                    afterSha256 = "afterSha",
                    beforeExists = false,
                    afterExists = true,
                    snapshotStatus = WorkspaceChangeEntry.SNAPSHOT_STATUS_READY,
                    revision = 1L,
                    createdAt = 1000L,
                    beforeManifestJson = "{}",
                    afterManifestJson = "{}",
                    restoreJournalJson = "[]",
                    snapshotBytes = 10L,
                    unavailableReason = null
                )
                wsDao.insert(wsEntry)

                // 陈旧版本 CAS 删除返回 0
                val wsConflict = wsDao.deleteByIdCas("ws-cas-1", expectedRevision = 999L)
                assertEquals("Stale CAS delete must affect 0 rows", 0, wsConflict)
                assertNotNull(wsDao.getById("ws-cas-1"))

                // 匹配版本 CAS 删除返回 1
                val wsSuccess = wsDao.deleteByIdCas("ws-cas-1", expectedRevision = 1L)
                assertEquals("Matching CAS delete must affect 1 row", 1, wsSuccess)
                assertNull(wsDao.getById("ws-cas-1"))
            } finally {
                roomDb.close()
            }
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun createVersion19Database(dbName: String) {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createCommonPreConversationTables(db)
                        createVersion13ConversationTables(db)
                        db.execSQL("ALTER TABLE conversations ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE conversations ADD COLUMN parentConversationId INTEGER")
                        db.execSQL("ALTER TABLE conversations ADD COLUMN parentConversationMode TEXT")
                        db.execSQL("ALTER TABLE conversations ADD COLUMN scheduledTaskId TEXT")
                        db.execSQL("DROP TABLE IF EXISTS execution_records")
                        db.execSQL("DROP TABLE IF EXISTS study_records")
                        db.execSQL("DROP TABLE IF EXISTS favorite_records")
                        db.execSQL("DROP TABLE IF EXISTS cache_suggestion")
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `token_usage_records` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `conversationId` INTEGER NOT NULL,
                                `model` TEXT NOT NULL DEFAULT '',
                                `promptTokens` INTEGER NOT NULL DEFAULT 0,
                                `completionTokens` INTEGER NOT NULL DEFAULT 0,
                                `reasoningTokens` INTEGER NOT NULL DEFAULT 0,
                                `textTokens` INTEGER NOT NULL DEFAULT 0,
                                `cachedTokens` INTEGER NOT NULL DEFAULT 0,
                                `cacheCreationTokens` INTEGER NOT NULL DEFAULT 0,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_token_usage_records_createdAt` " +
                                "ON `token_usage_records` (`createdAt`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_token_usage_records_conversationId` " +
                                "ON `token_usage_records` (`conversationId`)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_agent_conversation_entries_conversationId_createdAt_id` " +
                                "ON `agent_conversation_entries` (`conversationId`, `createdAt`, `id`)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL(
                """
                INSERT INTO conversations
                (id, title, mode, isArchived, isPinned, parentConversationId, parentConversationMode,
                 scheduledTaskId, summary, contextSummary, contextSummaryCutoffEntryDbId,
                 contextSummaryUpdatedAt, status, lastMessage, messageCount, latestPromptTokens,
                 promptTokenThreshold, latestPromptTokensUpdatedAt, createdAt, updatedAt)
                VALUES
                (101, 'Pre-migration Chat', 'agent', 0, 1, NULL, NULL, NULL, 'summary-seed', 'ctx-summary', NULL, 0, 0, 'last msg', 5, 200, 128000, 0, 1000, 2000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO agent_conversation_entries
                (id, conversationId, conversationMode, entryId, entryType, status, summary, payloadJson, createdAt, updatedAt)
                VALUES
                (501, 101, 'agent', 'entry-seed-1', 'user', 'completed', 'seed summary', '{"text":"pre-migration user text"}', 1100, 1100)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO codex_thread_bindings
                (conversationId, threadId, cwd, createdAt, updatedAt)
                VALUES
                (101, 'thread-codex-1', '/workspace/seed', 1000, 1000)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO token_usage_records
                (id, conversationId, model, promptTokens, completionTokens, reasoningTokens, textTokens, cachedTokens, cacheCreationTokens, createdAt)
                VALUES
                (1, 101, 'gpt-4o', 120, 45, 0, 45, 0, 0, 1200)
                """.trimIndent()
            )
        } finally {
            helper.close()
        }
    }

    private fun createCommonPreConversationTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `app_icons` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `appName` TEXT NOT NULL,
                `packageName` TEXT NOT NULL,
                `icon_base64` TEXT NOT NULL,
                `icon_path` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `messageId` TEXT NOT NULL,
                `type` INTEGER NOT NULL,
                `user` INTEGER NOT NULL,
                `content` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createVersion13ConversationTables(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversations` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `mode` TEXT NOT NULL DEFAULT 'normal',
                `isArchived` INTEGER NOT NULL DEFAULT 0,
                `summary` TEXT,
                `contextSummary` TEXT,
                `contextSummaryCutoffEntryDbId` INTEGER,
                `contextSummaryUpdatedAt` INTEGER NOT NULL DEFAULT 0,
                `status` INTEGER NOT NULL DEFAULT 0,
                `lastMessage` TEXT,
                `messageCount` INTEGER NOT NULL DEFAULT 0,
                `latestPromptTokens` INTEGER NOT NULL DEFAULT 0,
                `promptTokenThreshold` INTEGER NOT NULL DEFAULT 128000,
                `latestPromptTokensUpdatedAt` INTEGER NOT NULL DEFAULT 0,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `agent_conversation_entries` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `conversationId` INTEGER NOT NULL,
                `conversationMode` TEXT NOT NULL,
                `entryId` TEXT NOT NULL,
                `entryType` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `summary` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_agent_conversation_entries_conversationId_conversationMode_entryId`
            ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `entryId`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_agent_conversation_entries_conversationId_conversationMode_updatedAt`
            ON `agent_conversation_entries` (`conversationId`, `conversationMode`, `updatedAt`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `codex_thread_bindings` (
                `conversationId` INTEGER NOT NULL,
                `threadId` TEXT NOT NULL,
                `cwd` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`conversationId`)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_codex_thread_bindings_threadId`
            ON `codex_thread_bindings` (`threadId`)
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE INDEX IF NOT EXISTS
            `index_codex_thread_bindings_updatedAt`
            ON `codex_thread_bindings` (`updatedAt`)
            """.trimIndent()
        )
    }
}
