package cn.com.omnimind.bot.agent.workspace

import android.content.Context
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.workspace.WorkspaceStorageAccess
import java.io.File
import java.util.UUID

private val workspaceIdPattern = Regex("^conversation_(?:default|[1-9][0-9]*)$")
private val contentHashPattern = Regex("^[a-f0-9]{64}$")

/** 管理面只接收工作区身份，不接收可由客户端指定的任意磁盘根路径。 */
internal suspend fun WorkspaceChangeService.manageChanges(
    context: Context,
    operation: String,
    arguments: Map<String, Any?>,
): Map<String, Any?> {
    if (!WorkspaceStorageAccess.isGranted(context)) {
        throw WorkspaceChangeException("forbidden", "请先授予工作区访问权限")
    }
    fun text(key: String): String = (arguments[key] as? String)?.takeIf(String::isNotBlank)
        ?: throw WorkspaceChangeException("workspace_change_unavailable", "缺少字段：$key")
    fun positiveLong(key: String): Long {
        val number = arguments[key] as? Number
            ?: throw WorkspaceChangeException("workspace_change_unavailable", "缺少数值字段：$key")
        val value = number.toDouble()
        if (!value.isFinite() || value < 1 || value >= Long.MAX_VALUE.toDouble() || value % 1.0 != 0.0) {
            throw WorkspaceChangeException("workspace_change_unavailable", "数值字段无效：$key")
        }
        return number.toLong()
    }
    fun page(key: String, default: Int, max: Int): Int {
        val number = arguments[key] ?: return default
        val value = (number as? Number)?.toDouble()
            ?: throw WorkspaceChangeException("workspace_change_unavailable", "分页字段无效：$key")
        val minimum = if (key == "limit") 1 else 0
        if (!value.isFinite() || value < minimum || value > max || value % 1.0 != 0.0) {
            throw WorkspaceChangeException("workspace_change_unavailable", "分页字段无效：$key")
        }
        return value.toInt()
    }
    val conversationId = arguments["conversationId"]?.let { positiveLong("conversationId") }
    val conversationService = ConversationDomainService(context)
    if (conversationId != null && (
            DatabaseHelper.getConversationById(conversationId) == null ||
                !conversationService.canReadConversation(conversationId)
            )) {
        throw WorkspaceChangeException("not_found", "来源对话已不可用，请从工作区变更记录管理")
    }
    val requestedWorkspaceId = arguments["workspaceId"]?.let { text("workspaceId") }
    if (requestedWorkspaceId != null && !workspaceIdPattern.matches(requestedWorkspaceId)) {
        throw WorkspaceChangeException("forbidden", "工作区标识无效")
    }
    val workspaceConversationId = when {
        requestedWorkspaceId == null -> conversationId
        requestedWorkspaceId == "conversation_default" -> null
        else -> requestedWorkspaceId.removePrefix("conversation_").toLongOrNull()
            ?: throw WorkspaceChangeException("forbidden", "工作区标识无效")
    }
    if (workspaceConversationId != null && !conversationService.canReadConversation(workspaceConversationId)) {
        throw WorkspaceChangeException("forbidden", "无权访问此工作区")
    }
    val workspace = AgentWorkspaceManager(context).buildWorkspaceDescriptor(
        workspaceConversationId, "workspace-change-management",
    )
    if (requestedWorkspaceId != null && requestedWorkspaceId != workspace.id) {
        throw WorkspaceChangeException("forbidden", "工作区归属已变化")
    }
    val canonicalRoot = File(workspace.androidRootPath).canonicalPath
    fun changeId(): String {
        val value = text("changeId")
        if (runCatching { UUID.fromString(value).toString() }.getOrNull() != value) {
            throw WorkspaceChangeException("workspace_change_unavailable", "变更标识无效")
        }
        return value
    }
    return when (operation) {
        "list" -> {
            val owner = conversationId
                ?: throw WorkspaceChangeException("workspace_change_unavailable", "缺少来源对话")
            val items = workspaceChangesList(owner, arguments["turnId"] as? String)
                .filter { it.workspaceRoot == canonicalRoot }
            mapOf("workspaceId" to workspace.id, "items" to items.map { it.toSummaryMap() })
        }
        "list_by_workspace" -> {
            if (requestedWorkspaceId == null) {
                throw WorkspaceChangeException("workspace_change_unavailable", "缺少工作区标识")
            }
            val offset = page("offset", 0, Int.MAX_VALUE)
            val limit = page("limit", 20, 50)
            val items = workspaceChangesListByWorkspace(canonicalRoot, limit, offset)
                .filter { conversationService.canReadConversation(it.conversationId) }
                .map {
                    it.toSummaryMap() + (
                        "sourceConversationDeleted" to (DatabaseHelper.getConversationById(it.conversationId) == null)
                        )
                }
            mapOf(
                "workspaceId" to workspace.id, "items" to items,
                "offset" to offset, "limit" to limit,
                "total" to DatabaseHelper.countWorkspaceChangesByWorkspaceRoot(canonicalRoot),
            )
        }
        "preview" -> workspaceChangePreview(changeId(), conversationId, canonicalRoot)?.toMap()
            ?: throw WorkspaceChangeException("not_found", "变更记录已不可用")
        "restore" -> {
            val hashes = arguments["expectedAfterHashes"] as? Map<*, *>
                ?: throw WorkspaceChangeException("workspace_change_unavailable", "缺少预览后的文件指纹")
            val expected = hashes.entries.associate { (key, value) ->
                if (key !is String || key.isBlank() || (value != null &&
                        (value !is String || !contentHashPattern.matches(value)))) {
                    throw WorkspaceChangeException("workspace_change_unavailable", "文件指纹格式无效")
                }
                key to (value as String?)
            }
            workspaceChangeRestore(
                changeId(), positiveLong("expectedRevision"), expected, conversationId, canonicalRoot,
            ).toMap()
        }
        "discard" -> {
            val id = changeId()
            val discarded = workspaceChangeDiscard(
                id, positiveLong("expectedRevision"), conversationId, canonicalRoot,
            )
            val latest = workspaceChangePreview(id, conversationId, canonicalRoot)
            mapOf(
                "changeId" to id,
                "discarded" to discarded,
                "revision" to latest?.revision,
                "snapshotStatus" to latest?.snapshotStatus,
            )
        }
        else -> throw WorkspaceChangeException("workspace_change_unavailable", "未知变更管理操作")
    }
}
