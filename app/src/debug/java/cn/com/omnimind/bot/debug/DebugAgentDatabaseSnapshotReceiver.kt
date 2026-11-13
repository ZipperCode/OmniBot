package cn.com.omnimind.bot.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 仅用于 Debug 环境的一致只读聊天数据库快照 Receiver。
 *
 * 限制与安全性规范：
 * 1. 仅限 shell/root/system (由 android.permission.DUMP 强制保障)，不接受任意 SQL，不接受任意源路径或目标路径。
 * 2. 目标文件强制位于 context.cacheDir，固定命名规则为 `oob-checkpoint-$nonce.db`。保持应用私有权限，严禁公开。
 * 3. 严格校验 nonce 格式（仅允许 16~64 位十六进制字符）。
 * 4. 原子锁定：通过创建独占锁文件确立本次 nonce 所有权；若目标或锁文件已存在，立即拒绝，绝不触碰或删除任何文件。
 * 5. 使用 SQLiteDatabase.openDatabase 建立独立只读连接 (OPEN_READONLY)，仅执行参数绑定的 VACUUM INTO ?。
 * 6. 关闭连接后原子重命名临时文件为最终 db，写入状态回执（pending -> completed/failed）。
 * 7. 异常与日志严格限制为安全枚举，绝不泄露路径、SQL 或消息明文。
 */
class DebugAgentDatabaseSnapshotReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            val nonce = intent?.getStringExtra("nonce")?.trim().orEmpty()
            var lockFile: File? = null
            var ownedLock = false
            var tempDbFile: File? = null
            var finalDbFile: File? = null
            var tempStatusFile: File? = null
            var finalStatusFile: File? = null

            try {
                // 1. 严格校验 nonce 格式，杜绝路径穿越与非安全字符
                require(NONCE_REGEX.matches(nonce)) {
                    "invalid_nonce"
                }

                val cacheDir = appContext.cacheDir
                lockFile = File(cacheDir, "oob-checkpoint-$nonce.lock")
                finalDbFile = File(cacheDir, "oob-checkpoint-$nonce.db")
                tempDbFile = File(cacheDir, "oob-checkpoint-$nonce.db.tmp")
                finalStatusFile = File(cacheDir, "oob-checkpoint-$nonce.json")
                tempStatusFile = File(cacheDir, "oob-checkpoint-$nonce.json.tmp")

                // 2. 独占性与碰撞检查：若任何文件已存在，或锁文件创建失败，视为碰撞，绝不碰任何已有文件
                if (finalDbFile.exists() || tempDbFile.exists() || finalStatusFile.exists() || tempStatusFile.exists() || lockFile.exists()) {
                    throw NonceCollisionException()
                }
                if (!lockFile.createNewFile()) {
                    throw NonceCollisionException()
                }
                ownedLock = true

                // 3. 初始写入 pending 状态回执，确保外部读取端在轮询时明确知晓任务正在进行中
                val pendingPayload = JSONObject().apply {
                    put("schema_version", SCHEMA_VERSION)
                    put("nonce", nonce)
                    put("status", "pending")
                    put("success", false)
                    put("timestamp_ms", System.currentTimeMillis())
                }.toString()
                tempStatusFile.writeText(pendingPayload, Charsets.UTF_8)
                check(tempStatusFile.renameTo(finalStatusFile)) {
                    "receipt_write_failed"
                }

                // 4. 定位源数据库文件
                val sourceDbFile = appContext.getDatabasePath(DATABASE_NAME)
                if (!sourceDbFile.exists() || !sourceDbFile.isFile) {
                    throw FileNotFoundException("source_database_missing")
                }

                // 5. 建立独立的只读 SQLite 连接，仅执行参数绑定的 VACUUM INTO ?
                val readOnlyDb = SQLiteDatabase.openDatabase(
                    sourceDbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                )

                try {
                    val tempDbPath = tempDbFile.absolutePath
                    readOnlyDb.execSQL("VACUUM INTO ?", arrayOf(tempDbPath))
                } finally {
                    readOnlyDb.close()
                }

                require(tempDbFile.exists() && tempDbFile.length() > 0) {
                    "vacuum_into_failed"
                }

                // 6. 原子重命名为最终数据库文件（保持默认私有权限，绝不设为 world-readable）
                check(tempDbFile.renameTo(finalDbFile)) {
                    "vacuum_into_failed"
                }

                // 7. 原子写入最终 completed 状态回执
                val completedPayload = JSONObject().apply {
                    put("schema_version", SCHEMA_VERSION)
                    put("nonce", nonce)
                    put("status", "completed")
                    put("success", true)
                    put("db_file", finalDbFile.name)
                    put("size_bytes", finalDbFile.length())
                    put("timestamp_ms", System.currentTimeMillis())
                }.toString()

                tempStatusFile.writeText(completedPayload, Charsets.UTF_8)
                check(tempStatusFile.renameTo(finalStatusFile)) {
                    "receipt_write_failed"
                }

                Log.i(TAG, "snapshot_completed nonce=$nonce size=${finalDbFile.length()}")
                pendingResult.setResultCode(Activity.RESULT_OK)
                pendingResult.setResultData(completedPayload)
            } catch (error: Throwable) {
                val safeErrorCode = mapSafeError(error)
                Log.e(TAG, "snapshot_failed error_code=$safeErrorCode")

                // 仅在已确立所有权时清理临时未完成文件
                if (ownedLock) {
                    try {
                        tempDbFile?.takeIf { it.exists() }?.delete()
                    } catch (_: Throwable) {}
                }

                val failedPayload = JSONObject().apply {
                    put("schema_version", SCHEMA_VERSION)
                    put("nonce", nonce)
                    put("status", "failed")
                    put("success", false)
                    put("error", safeErrorCode)
                    put("timestamp_ms", System.currentTimeMillis())
                }.toString()

                // 仅在已确立所有权且非碰撞时写入状态文件；碰撞时不修改或覆盖磁盘上的已有文件
                if (ownedLock && safeErrorCode != SAFE_ERROR_NONCE_COLLISION) {
                    try {
                        if (tempStatusFile != null && finalStatusFile != null) {
                            tempStatusFile.writeText(failedPayload, Charsets.UTF_8)
                            tempStatusFile.renameTo(finalStatusFile)
                        }
                    } catch (_: Throwable) {}
                }

                pendingResult.setResultCode(Activity.RESULT_CANCELED)
                pendingResult.setResultData(failedPayload)
            } finally {
                // 确保 setResultCode 与 setResultData 在 finish 前调用
                pendingResult.finish()
            }
        }
    }

    private class NonceCollisionException : RuntimeException("nonce_collision")

    companion object {
        const val ACTION_SNAPSHOT_DATABASE = "cn.com.omnimind.bot.debug.SNAPSHOT_DATABASE"
        private const val DATABASE_NAME = "omnibot_cache_databaseoss"
        private const val SCHEMA_VERSION = "oob.database_snapshot.v1"
        private const val TAG = "DebugAgentDatabaseSnapshotReceiver"
        private val NONCE_REGEX = Regex("^[0-9a-fA-F]{16,64}$")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val SAFE_ERROR_INVALID_NONCE = "invalid_nonce"
        private const val SAFE_ERROR_NONCE_COLLISION = "nonce_collision"
        private const val SAFE_ERROR_SOURCE_MISSING = "source_database_missing"
        private const val SAFE_ERROR_VACUUM_FAILED = "vacuum_into_failed"
        private const val SAFE_ERROR_RECEIPT_FAILED = "receipt_write_failed"
        private const val SAFE_ERROR_INTERNAL = "internal_error"

        private fun mapSafeError(error: Throwable): String = when {
            error is NonceCollisionException -> SAFE_ERROR_NONCE_COLLISION
            error is IllegalArgumentException && error.message == "invalid_nonce" -> SAFE_ERROR_INVALID_NONCE
            error is FileNotFoundException -> SAFE_ERROR_SOURCE_MISSING
            error is SQLiteException || error.message == "vacuum_into_failed" -> SAFE_ERROR_VACUUM_FAILED
            error.message == "receipt_write_failed" -> SAFE_ERROR_RECEIPT_FAILED
            else -> SAFE_ERROR_INTERNAL
        }
    }
}
