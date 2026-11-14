package cn.com.omnimind.baselib.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_outbox",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversationId", "state", "sequence"]),
        Index(value = ["requestId"], unique = true)
    ]
)
data class ConversationOutboxEntry(
    @PrimaryKey
    val id: String,
    val conversationId: Long,
    val agentId: String,
    val payloadJson: String,
    val state: String,
    val revision: Long,
    val sequence: Long,
    val requestId: String,
    val userMessageId: String,
    val blockedBySessionId: String? = null,
    val blockedByTurnId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pauseReason: String? = null
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_DISPATCHING = "dispatching"
        const val STATE_PAUSED = "paused"
        const val STATE_SUBMITTED = "submitted"

        val VALID_STATES = setOf(
            STATE_PENDING,
            STATE_DISPATCHING,
            STATE_PAUSED,
            STATE_SUBMITTED
        )
    }
}
