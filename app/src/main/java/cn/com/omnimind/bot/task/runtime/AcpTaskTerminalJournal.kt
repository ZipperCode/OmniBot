package cn.com.omnimind.bot.task.runtime

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 官方 ACP 请求的持久事实；消息内容与工具退出码不产生 Turn 终态。 */
data class AcpTerminalJournalEntry(
    val agentId: String,
    val sessionId: String,
    val turnId: String,
    val requestId: String? = null,
    val checkpointId: String? = null,
    val admittedAt: Long = System.currentTimeMillis(),
    val terminalStatus: String? = null,
    val terminalError: String? = null,
    val terminalAt: Long? = null,
) {
    val isTerminal: Boolean get() = !terminalStatus.isNullOrBlank()
}

sealed interface AcpTerminalLookupResult {
    data class TerminalFound(val entry: AcpTerminalJournalEntry) : AcpTerminalLookupResult
    data class AdmittedInFlight(val entry: AcpTerminalJournalEntry) : AcpTerminalLookupResult
    object Unknown : AcpTerminalLookupResult
}

/** 只记录既有 ACP 所有者观察到的准入与官方终态，不调度、重试或重放任务。 */
object AcpTaskTerminalJournal {
    private const val TAG = "AcpTaskTerminalJournal"
    private const val PREFS_NAME = "acp_task_terminal_journal"
    private const val RECORDS_KEY = "journal_entries"
    private val gson = Gson()
    private val recordType = object : TypeToken<Map<String, AcpTerminalJournalEntry>>() {}.type

    @Synchronized
    fun recordAdmission(
        context: Context,
        agentId: String,
        sessionId: String,
        turnId: String,
        requestId: String? = null,
        checkpointId: String? = null,
    ): Boolean {
        if (!validIdentity(agentId, sessionId, turnId) || !TaskContinuationStore.isStorageAccessible(context)) return false
        val records = load(context) ?: return false
        val key = entryKey(agentId, sessionId, turnId)
        val previous = records[key]
        if (previous?.requestId != null && requestId != null && previous.requestId != requestId) return false
        if (previous?.checkpointId != null && checkpointId != null && previous.checkpointId != checkpointId) return false
        if (requestId != null && records.values.any {
                it.agentId == agentId && it.sessionId == sessionId &&
                    it.requestId == requestId && it.turnId != turnId
            }) return false
        records[key] = previous?.copy(
            requestId = previous.requestId ?: requestId,
            checkpointId = previous.checkpointId ?: checkpointId,
        ) ?: AcpTerminalJournalEntry(agentId, sessionId, turnId, requestId, checkpointId)
        return persist(context, records)
    }

    @Synchronized
    fun recordTerminal(
        context: Context,
        agentId: String,
        sessionId: String,
        turnId: String,
        status: String,
        error: String? = null,
        checkpointId: String? = null,
    ): Boolean {
        if (!validIdentity(agentId, sessionId, turnId) || status.isBlank() ||
            !TaskContinuationStore.isStorageAccessible(context)) return false
        val records = load(context) ?: return false
        val key = entryKey(agentId, sessionId, turnId)
        val previous = records[key]
        if (previous?.isTerminal == true) {
            return previous.terminalStatus == status && previous.terminalError == error &&
                (checkpointId == null || previous.checkpointId == checkpointId)
        }
        if (previous?.checkpointId != null && checkpointId != null && previous.checkpointId != checkpointId) return false
        records[key] = (previous ?: AcpTerminalJournalEntry(agentId, sessionId, turnId)).copy(
            terminalStatus = status,
            terminalError = error,
            terminalAt = System.currentTimeMillis(),
            checkpointId = previous?.checkpointId ?: checkpointId,
        )
        return persist(context, records)
    }

    @Synchronized
    fun find(context: Context, agentId: String, sessionId: String, turnId: String): AcpTerminalLookupResult {
        if (!validIdentity(agentId, sessionId, turnId) || !TaskContinuationStore.isStorageAccessible(context)) {
            return AcpTerminalLookupResult.Unknown
        }
        return lookup(load(context)?.get(entryKey(agentId, sessionId, turnId)))
    }

    @Synchronized
    fun findByRequestId(context: Context, agentId: String, sessionId: String, requestId: String): AcpTerminalLookupResult {
        if (agentId.isBlank() || sessionId.isBlank() || requestId.isBlank() ||
            !TaskContinuationStore.isStorageAccessible(context)) return AcpTerminalLookupResult.Unknown
        return lookup(load(context)?.values?.singleOrNull {
            it.agentId == agentId && it.sessionId == sessionId && it.requestId == requestId
        })
    }

    /** 精确清理已移除检查点的对应事实，不能清空其他任务。 */
    @Synchronized
    fun remove(context: Context, agentId: String, sessionId: String, turnId: String): Boolean {
        if (!validIdentity(agentId, sessionId, turnId) || !TaskContinuationStore.isStorageAccessible(context)) return false
        val records = load(context) ?: return false
        if (records.remove(entryKey(agentId, sessionId, turnId)) == null) return true
        return persist(context, records)
    }

    private fun lookup(entry: AcpTerminalJournalEntry?): AcpTerminalLookupResult = when {
        entry == null -> AcpTerminalLookupResult.Unknown
        entry.isTerminal -> AcpTerminalLookupResult.TerminalFound(entry)
        else -> AcpTerminalLookupResult.AdmittedInFlight(entry)
    }

    private fun validIdentity(agentId: String, sessionId: String, turnId: String): Boolean =
        agentId.isNotBlank() && sessionId.isNotBlank() && turnId.isNotBlank()

    private fun entryKey(agentId: String, sessionId: String, turnId: String): String =
        gson.toJson(listOf(agentId, sessionId, turnId))

    private fun load(context: Context): MutableMap<String, AcpTerminalJournalEntry>? = runCatching {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(RECORDS_KEY, null) ?: return@runCatching linkedMapOf()
        val records: Map<String, AcpTerminalJournalEntry> = gson.fromJson(raw, recordType)
            ?: error("ACP 终态记录为空")
        check(records.all { (storedKey, record) ->
            validIdentity(record.agentId, record.sessionId, record.turnId) &&
                storedKey == entryKey(record.agentId, record.sessionId, record.turnId)
        }) { "ACP 终态记录身份不匹配" }
        records.toMutableMap()
    }.onFailure {
        OmniLog.e(TAG, "读取 ACP 终态记录失败，保留原数据并停止推断")
    }.getOrNull()

    private fun persist(context: Context, records: Map<String, AcpTerminalJournalEntry>): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(RECORDS_KEY, gson.toJson(records)).commit()
    }.onFailure {
        OmniLog.e(TAG, "ACP 终态记录同步写盘失败")
    }.getOrDefault(false)
}
