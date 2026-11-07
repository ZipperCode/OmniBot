package cn.com.omnimind.bot.task.runtime

import android.content.Context
import android.os.Build
import android.os.UserManager
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 任务延续检查点生命周期状态。
 * 遵循严格的单次恢复契约：RESERVED -> ADMITTED -> FINISHED / FAILED。
 */
enum class ContinuationState {
    RESERVED,  // 重启前持久化检查点，等待开机首次解锁后恢复
    ADMITTED,  // 已被恢复协调器准入并向运行时派发证据驱动 Turn，正在执行中
    FINISHED,  // 延续任务已正式完成
    FAILED     // 终态失败或已被用户显式放弃
}

/**
 * 任务重启延续检查点数据结构。
 * 显式记录 acpAgentId、sessionId、原 turnId、prebootBootId、continuationRequestId 以及 conversationId。
 */
data class TaskContinuationCheckpoint(
    val checkpointId: String,
    val conversationId: Long,
    val sessionId: String,
    val turnId: String,
    val acpAgentId: String,
    val taskTitle: String,
    val taskGoal: String,
    val currentProgress: String,
    val pendingSideEffects: List<String> = emptyList(),
    val evidenceVerificationChecklist: List<String> = emptyList(),
    val prebootBootId: String? = null,
    val continuationRequestId: String? = null,
    val continuationTurnId: String? = null,
    val state: ContinuationState = ContinuationState.RESERVED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val lastError: String? = null
)

/**
 * 重启检查点持久化存储。
 * 严格遵循 Direct Boot 规范：在用户开机首次解锁前，绝对不读取或写入处于加密锁定状态的 CE 存储。
 * 持久化写入使用同步 commit()，确保在整机立刻重启前数据已真实刷盘。
 */
object TaskContinuationStore {
    private const val TAG = "TaskContinuationStore"
    private const val PREFS_NAME = "task_continuation_checkpoints"
    private const val KEY_CHECKPOINTS_JSON = "checkpoints_json"
    private val gson = Gson()

    /**
     * 检查凭据加密存储 (CE) 是否已解锁并可安全访问。
     */
    fun isStorageAccessible(context: Context): Boolean {
        return runCatching {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                userManager?.isUserUnlocked == true
            } else {
                true
            }
        }.getOrDefault(false)
    }

    @Synchronized
    fun saveCheckpoint(context: Context, checkpoint: TaskContinuationCheckpoint): Boolean {
        if (!isStorageAccessible(context)) {
            OmniLog.w(TAG, "Direct Boot 模式下 CE 存储处于锁定状态，无法保存检查点")
            return false
        }
        return runCatching {
            val all = loadCheckpointsMutable(context) ?: return false
            all[checkpoint.checkpointId] = checkpoint
            val success = persistCheckpointsSync(context, all)
            if (!success) {
                OmniLog.e(TAG, "检查点同步写盘失败 checkpointId=${checkpoint.checkpointId}")
            }
            success
        }.onFailure {
            OmniLog.e(TAG, "保存检查点异常 checkpointId=${checkpoint.checkpointId}：${it.message}", it)
        }.getOrDefault(false)
    }

    @Synchronized
    fun getCheckpoint(context: Context, checkpointId: String): TaskContinuationCheckpoint? {
        if (!isStorageAccessible(context)) return null
        return loadCheckpointsMutable(context)?.get(checkpointId)
    }

    @Synchronized
    fun getAllCheckpoints(context: Context): List<TaskContinuationCheckpoint> {
        if (!isStorageAccessible(context)) return emptyList()
        return loadCheckpointsMutable(context)?.values?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /**
     * 仅获取待准入恢复的检查点（必须为 RESERVED 状态）。
     * 契约：ADMITTED 状态表示请求已发，绝不在此返回避免重复重播派发！
     */
    @Synchronized
    fun getResumableCheckpoints(context: Context): List<TaskContinuationCheckpoint> {
        if (!isStorageAccessible(context)) return emptyList()
        return loadCheckpointsMutable(context)?.values?.filter {
            it.state == ContinuationState.RESERVED
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /**
     * 获取已准入执行中的检查点（ADMITTED 状态）。
     * 若进程被杀或重启导致结果不明，供只读核验使用，禁止自动重发。
     */
    @Synchronized
    fun getAdmittedCheckpoints(context: Context): List<TaskContinuationCheckpoint> {
        if (!isStorageAccessible(context)) return emptyList()
        return loadCheckpointsMutable(context)?.values?.filter {
            it.state == ContinuationState.ADMITTED
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    /**
     * 查询未完结的所有检查点（RESERVED 与 ADMITTED），供状态展示。
     */
    @Synchronized
    fun getPendingCheckpoints(context: Context): List<TaskContinuationCheckpoint> {
        if (!isStorageAccessible(context)) return emptyList()
        return loadCheckpointsMutable(context)?.values?.filter {
            it.state == ContinuationState.RESERVED || it.state == ContinuationState.ADMITTED
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    @Synchronized
    fun updateCheckpointState(
        context: Context,
        checkpointId: String,
        newState: ContinuationState,
        error: String? = null,
        continuationTurnId: String? = null
    ): Boolean {
        if (!isStorageAccessible(context)) return false
        val all = loadCheckpointsMutable(context) ?: return false
        val current = all[checkpointId] ?: return false
        val updated = current.copy(
            state = newState,
            continuationTurnId = continuationTurnId ?: current.continuationTurnId,
            updatedAt = System.currentTimeMillis(),
            lastError = error ?: current.lastError
        )
        all[checkpointId] = updated
        return persistCheckpointsSync(context, all)
    }

    @Synchronized
    fun markAdmitted(
        context: Context,
        checkpointId: String,
        continuationRequestId: String? = null
    ): TaskContinuationCheckpoint? {
        if (!isStorageAccessible(context)) return null
        val all = loadCheckpointsMutable(context) ?: return null
        val current = all[checkpointId] ?: return null
        if (current.state != ContinuationState.RESERVED) {
            OmniLog.w(TAG, "markAdmitted 拒绝：检查点 $checkpointId 当前状态为 ${current.state}，非 RESERVED")
            return null
        }
        val updated = current.copy(
            state = ContinuationState.ADMITTED,
            continuationRequestId = continuationRequestId ?: current.continuationRequestId,
            updatedAt = System.currentTimeMillis(),
            attemptCount = current.attemptCount + 1
        )
        all[checkpointId] = updated
        val success = persistCheckpointsSync(context, all)
        return if (success) updated else null
    }

    @Synchronized
    fun deleteCheckpoint(context: Context, checkpointId: String): Boolean {
        if (!isStorageAccessible(context)) return false
        val all = loadCheckpointsMutable(context) ?: return false
        val removed = all.remove(checkpointId) != null
        return if (removed) {
            persistCheckpointsSync(context, all)
        } else {
            false
        }
    }

    @Synchronized
    fun clearFinishedCheckpoints(context: Context) {
        if (!isStorageAccessible(context)) return
        val all = loadCheckpointsMutable(context) ?: return
        val filtered = all.filterValues {
            it.state == ContinuationState.RESERVED || it.state == ContinuationState.ADMITTED
        }.toMutableMap()
        persistCheckpointsSync(context, filtered)
    }

    private fun loadCheckpointsMutable(context: Context): MutableMap<String, TaskContinuationCheckpoint>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CHECKPOINTS_JSON, null)?.trim() ?: return mutableMapOf()
        if (raw.isEmpty()) return mutableMapOf()
        return runCatching {
            val type = object : TypeToken<Map<String, TaskContinuationCheckpoint>>() {}.type
            val parsed: Map<String, TaskContinuationCheckpoint>? = gson.fromJson(raw, type)
            if (parsed == null) {
                throw IllegalStateException("解析检查点 JSON 为 null")
            }
            check(parsed.all { (key, checkpoint) -> key.isNotBlank() && key == checkpoint.checkpointId }) {
                "检查点键与 checkpointId 不一致"
            }
            parsed.toMutableMap()
        }.onFailure {
            OmniLog.e(TAG, "解析检查点 JSON 失败，保留原存储并拒绝覆盖：${it.message}", it)
        }.getOrNull()
    }
    /**
     * 同步写盘：重启前必须使用 commit() 保证同步刷入存储介质，不得使用异步 apply() 避免断电/重启丢失进度。
     * 同步投影 hasPending 到 Device Protected (DP) 存储供开机锁屏通知读取。
     */
    private fun persistCheckpointsSync(context: Context, map: Map<String, TaskContinuationCheckpoint>): Boolean {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(map)
            val ceSuccess = prefs.edit().putString(KEY_CHECKPOINTS_JSON, json).commit()
            if (!ceSuccess) {
                OmniLog.e(TAG, "同步持久化检查点到 CE 存储失败")
                return false
            }
            val hasPending = map.values.any {
                it.state == ContinuationState.RESERVED || it.state == ContinuationState.ADMITTED
            }
            val dpSuccess = TaskContinuationNotifications.persistPendingHint(context, hasPending)
            if (!dpSuccess) {
                OmniLog.e(TAG, "同步投影 hasPending ($hasPending) 到 DP 存储失败")
                return false
            }
            if (!hasPending) {
                TaskContinuationNotifications.cancel(context)
            }
            true
        }.onFailure {
            OmniLog.e(TAG, "同步持久化检查点异常：${it.message}", it)
        }.getOrDefault(false)
    }
}
