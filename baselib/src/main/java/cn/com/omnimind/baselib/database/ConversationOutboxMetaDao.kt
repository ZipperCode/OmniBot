package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ConversationOutboxMetaDao {

    data class SequenceAllocation(
        val allocatedSequence: Long,
        val newRevision: Long
    )

    @Query("SELECT * FROM conversation_outbox_meta WHERE conversationId = :conversationId")
    suspend fun getByConversationId(conversationId: Long): ConversationOutboxMeta?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(meta: ConversationOutboxMeta): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: ConversationOutboxMeta): Long

    @Update
    suspend fun update(meta: ConversationOutboxMeta): Int

    @Delete
    suspend fun delete(meta: ConversationOutboxMeta): Int

    @Query("DELETE FROM conversation_outbox_meta WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long): Int

    @Query(
        """
        UPDATE conversation_outbox_meta
        SET revision = :newRevision, nextSequence = :newNextSequence
        WHERE conversationId = :conversationId AND revision = :expectedRevision
        """
    )
    suspend fun updateCas(
        conversationId: Long,
        expectedRevision: Long,
        newRevision: Long,
        newNextSequence: Long
    ): Int

    @Query(
        """
        UPDATE conversation_outbox_meta
        SET revision = :newRevision
        WHERE conversationId = :conversationId AND revision = :expectedRevision
        """
    )
    suspend fun updateRevisionCas(
        conversationId: Long,
        expectedRevision: Long,
        newRevision: Long
    ): Int

    @Query(
        """
        UPDATE conversation_outbox_meta
        SET revision = revision + 1
        WHERE conversationId = :conversationId
        """
    )
    suspend fun incrementRevision(conversationId: Long): Int

    @Transaction
    suspend fun getOrCreate(conversationId: Long): ConversationOutboxMeta {
        val existing = getByConversationId(conversationId)
        if (existing != null) return existing
        insertIfNotExists(
            ConversationOutboxMeta(
                conversationId = conversationId,
                revision = 0L,
                nextSequence = 1L
            )
        )
        return getByConversationId(conversationId) ?: ConversationOutboxMeta(
            conversationId = conversationId,
            revision = 0L,
            nextSequence = 1L
        )
    }

    @Transaction
    suspend fun allocateSequence(
        conversationId: Long,
        expectedRevision: Long? = null
    ): SequenceAllocation? {
        val current = getOrCreate(conversationId)
        if (expectedRevision != null && current.revision != expectedRevision) {
            return null
        }
        val allocated = current.nextSequence
        val nextRev = current.revision + 1
        val nextSeq = allocated + 1
        val rowsUpdated = updateCas(
            conversationId = conversationId,
            expectedRevision = current.revision,
            newRevision = nextRev,
            newNextSequence = nextSeq
        )
        return if (rowsUpdated > 0) {
            SequenceAllocation(allocatedSequence = allocated, newRevision = nextRev)
        } else {
            null
        }
    }

    @Transaction
    suspend fun nextRevision(
        conversationId: Long,
        expectedRevision: Long? = null
    ): Long? {
        val current = getOrCreate(conversationId)
        if (expectedRevision != null && current.revision != expectedRevision) {
            return null
        }
        val targetRev = current.revision + 1
        val rowsUpdated = updateRevisionCas(
            conversationId = conversationId,
            expectedRevision = current.revision,
            newRevision = targetRev
        )
        return if (rowsUpdated > 0) targetRev else null
    }
}
