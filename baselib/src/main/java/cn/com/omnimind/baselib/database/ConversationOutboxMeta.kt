package cn.com.omnimind.baselib.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversation_outbox_meta",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ConversationOutboxMeta(
    @PrimaryKey
    val conversationId: Long,
    @ColumnInfo(defaultValue = "0")
    val revision: Long = 0L,
    @ColumnInfo(defaultValue = "1")
    val nextSequence: Long = 1L
)
