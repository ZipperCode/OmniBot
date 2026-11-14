package cn.com.omnimind.baselib.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ConversationOutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ConversationOutboxEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ConversationOutboxEntry): Long

    @Update
    suspend fun update(entry: ConversationOutboxEntry): Int

    @Delete
    suspend fun delete(entry: ConversationOutboxEntry): Int

    @Query("SELECT * FROM conversation_outbox WHERE id = :id")
    suspend fun getById(id: String): ConversationOutboxEntry?

    @Query("SELECT * FROM conversation_outbox WHERE requestId = :requestId")
    suspend fun getByRequestId(requestId: String): ConversationOutboxEntry?

    @Query("SELECT * FROM conversation_outbox WHERE conversationId = :conversationId ORDER BY sequence ASC")
    suspend fun getByConversationId(conversationId: Long): List<ConversationOutboxEntry>

    @Query("SELECT * FROM conversation_outbox WHERE conversationId = :conversationId AND state = :state ORDER BY sequence ASC")
    suspend fun getByConversationIdAndState(conversationId: Long, state: String): List<ConversationOutboxEntry>

    @Query("SELECT * FROM conversation_outbox WHERE conversationId = :conversationId AND state != :excludedState ORDER BY sequence ASC")
    suspend fun getActiveByConversationId(
        conversationId: Long,
        excludedState: String = ConversationOutboxEntry.STATE_SUBMITTED
    ): List<ConversationOutboxEntry>

    @Query("SELECT COUNT(*) FROM conversation_outbox WHERE conversationId = :conversationId")
    suspend fun countByConversationId(conversationId: Long): Int

    @Query("SELECT COUNT(*) FROM conversation_outbox WHERE conversationId = :conversationId AND state != :excludedState")
    suspend fun countActiveByConversationId(
        conversationId: Long,
        excludedState: String = ConversationOutboxEntry.STATE_SUBMITTED
    ): Int

    @Query("DELETE FROM conversation_outbox WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM conversation_outbox WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long): Int

    @Query("DELETE FROM conversation_outbox WHERE conversationId = :conversationId AND state = :state")
    suspend fun deleteByConversationIdAndState(conversationId: Long, state: String): Int

    @Query(
        """
        UPDATE conversation_outbox
        SET state = :newState, revision = :newRevision, updatedAt = :updatedAt, pauseReason = :pauseReason
        WHERE id = :id AND revision = :expectedRevision
        """
    )
    suspend fun updateStateCas(
        id: String,
        expectedRevision: Long,
        newState: String,
        newRevision: Long,
        updatedAt: Long,
        pauseReason: String? = null
    ): Int

    @Query(
        """
        UPDATE conversation_outbox
        SET sequence = :newSequence, revision = :newRevision, updatedAt = :updatedAt
        WHERE id = :id AND revision = :expectedRevision
        """
    )
    suspend fun updateSequenceCas(
        id: String,
        expectedRevision: Long,
        newSequence: Long,
        newRevision: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE conversation_outbox
        SET payloadJson = :payloadJson, revision = :newRevision, updatedAt = :updatedAt
        WHERE id = :id AND revision = :expectedRevision
        """
    )
    suspend fun updatePayloadCas(
        id: String,
        expectedRevision: Long,
        payloadJson: String,
        newRevision: Long,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE conversation_outbox
        SET state = :newState, pauseReason = :pauseReason, updatedAt = :updatedAt
        WHERE conversationId = :conversationId AND state = :currentState
        """
    )
    suspend fun transitionStateForConversation(
        conversationId: Long,
        currentState: String,
        newState: String,
        pauseReason: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE conversation_outbox
        SET blockedBySessionId = :newSessionId,
            blockedByTurnId = :newTurnId,
            updatedAt = :updatedAt
        WHERE conversationId = :conversationId
          AND state = :state
          AND blockedByTurnId = :oldTurnId
        """
    )
    suspend fun rebindBlockedTurnId(
        conversationId: Long,
        oldTurnId: String,
        newSessionId: String,
        newTurnId: String,
        updatedAt: Long,
        state: String = ConversationOutboxEntry.STATE_PENDING
    ): Int
}
