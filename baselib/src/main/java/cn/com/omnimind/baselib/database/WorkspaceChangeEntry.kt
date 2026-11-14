package cn.com.omnimind.baselib.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workspace_changes",
    indices = [
        Index(value = ["conversationId", "turnId", "createdAt"])
    ]
)
data class WorkspaceChangeEntry(
    @PrimaryKey
    val changeId: String,
    val conversationId: Long,
    val sessionId: String,
    val turnId: String,
    val toolCallId: String,
    val workspaceRoot: String,
    val operation: String,
    val sourceRelativePath: String? = null,
    val targetRelativePath: String? = null,
    val beforeSha256: String? = null,
    val afterSha256: String? = null,
    val beforeExists: Boolean,
    val afterExists: Boolean,
    val snapshotStatus: String,
    val revision: Long,
    val createdAt: Long,
    val beforeManifestJson: String,
    val afterManifestJson: String,
    val restoreJournalJson: String,
    val snapshotBytes: Long,
    val unavailableReason: String? = null
) {
    companion object {
        const val OPERATION_CREATE = "create"
        const val OPERATION_WRITE = "write"
        const val OPERATION_MOVE = "move"

        const val SNAPSHOT_STATUS_READY = "ready"
        const val SNAPSHOT_STATUS_UNAVAILABLE = "unavailable"
        const val SNAPSHOT_STATUS_RESTORED = "restored"
        const val SNAPSHOT_STATUS_CONFLICT = "conflict"

        val VALID_OPERATIONS = setOf(
            OPERATION_CREATE,
            OPERATION_WRITE,
            OPERATION_MOVE
        )

        val VALID_SNAPSHOT_STATUSES = setOf(
            SNAPSHOT_STATUS_READY,
            SNAPSHOT_STATUS_UNAVAILABLE,
            SNAPSHOT_STATUS_RESTORED,
            SNAPSHOT_STATUS_CONFLICT
        )
    }
}
