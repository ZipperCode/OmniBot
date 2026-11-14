package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface WorkspaceChangeDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: WorkspaceChangeEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WorkspaceChangeEntry): Long

    @Update
    suspend fun update(entry: WorkspaceChangeEntry): Int

    @Delete
    suspend fun delete(entry: WorkspaceChangeEntry): Int

    @Query("SELECT * FROM workspace_changes WHERE changeId = :changeId")
    suspend fun getById(changeId: String): WorkspaceChangeEntry?

    @Query(
        """
        SELECT * FROM workspace_changes
        WHERE conversationId = :conversationId
        ORDER BY createdAt DESC, changeId DESC
        """
    )
    suspend fun getByConversationId(conversationId: Long): List<WorkspaceChangeEntry>

    @Query(
        """
        SELECT * FROM workspace_changes
        WHERE conversationId = :conversationId AND (:turnId IS NULL OR turnId = :turnId)
        ORDER BY createdAt DESC, changeId DESC
        """
    )
    suspend fun getByConversationIdAndTurnId(
        conversationId: Long,
        turnId: String?
    ): List<WorkspaceChangeEntry>

    @Query(
        """
        SELECT * FROM workspace_changes
        WHERE workspaceRoot = :workspaceRoot
        ORDER BY createdAt DESC, changeId DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getByWorkspaceRootPaged(
        workspaceRoot: String,
        limit: Int,
        offset: Int
    ): List<WorkspaceChangeEntry>

    @Query(
        """
        SELECT * FROM workspace_changes
        WHERE workspaceRoot = :workspaceRoot
        ORDER BY createdAt DESC, changeId DESC
        """
    )
    suspend fun getByWorkspaceRoot(workspaceRoot: String): List<WorkspaceChangeEntry>

    @Query("SELECT COUNT(*) FROM workspace_changes WHERE workspaceRoot = :workspaceRoot")
    suspend fun countByWorkspaceRoot(workspaceRoot: String): Int

    @Query("SELECT COUNT(*) FROM workspace_changes WHERE conversationId = :conversationId")
    suspend fun countByConversationId(conversationId: Long): Int

    @Query("SELECT COALESCE(SUM(snapshotBytes), 0) FROM workspace_changes")
    suspend fun getTotalSnapshotBytes(): Long

    @Query(
        """
        UPDATE workspace_changes
        SET snapshotStatus = :newSnapshotStatus,
            revision = :newRevision,
            restoreJournalJson = :newRestoreJournalJson
        WHERE changeId = :changeId AND revision = :expectedRevision
        """
    )
    suspend fun updateStatusCas(
        changeId: String,
        expectedRevision: Long,
        newSnapshotStatus: String,
        newRevision: Long,
        newRestoreJournalJson: String
    ): Int

    @Query(
        """
        UPDATE workspace_changes
        SET snapshotStatus = :newSnapshotStatus,
            revision = :newRevision
        WHERE changeId = :changeId AND revision = :expectedRevision
        """
    )
    suspend fun updateSnapshotStatusCas(
        changeId: String,
        expectedRevision: Long,
        newSnapshotStatus: String,
        newRevision: Long
    ): Int

    @Query("DELETE FROM workspace_changes WHERE changeId = :changeId")
    suspend fun deleteById(changeId: String): Int

    @Query("DELETE FROM workspace_changes WHERE changeId = :changeId AND revision = :expectedRevision")
    suspend fun deleteByIdCas(changeId: String, expectedRevision: Long): Int

    @Query("DELETE FROM workspace_changes WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long): Int

    @Query("DELETE FROM workspace_changes WHERE workspaceRoot = :workspaceRoot")
    suspend fun deleteByWorkspaceRoot(workspaceRoot: String): Int
}
