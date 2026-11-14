package cn.com.omnimind.bot.agent.workspace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级工作区文件路径锁协调器。
 *
 * 保证：
 * - FileToolHandler 写入、编辑、移动与 WorkspaceChangeService 恢复共享同一套路径锁，避免锁割裂。
 * - 多路径操作（如 move）严格按照规范化路径的字典序依次取锁，杜绝反序死锁。
 * - 锁层级必须严格在 SubagentResourceGate 工作区跨步骤租约与模型 Permit 之后获取。
 */
object WorkspacePathLock {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    private fun getMutex(canonicalPath: String): Mutex =
        mutexes.computeIfAbsent(canonicalPath) { Mutex() }

    suspend fun <T> withPathLock(file: File, block: suspend () -> T): T {
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return getMutex(canonical).withLock {
            block()
        }
    }

    suspend fun <T> withPathLocks(files: List<File>, block: suspend () -> T): T {
        if (files.isEmpty()) return block()
        val canonicalPaths = files.map { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .distinct()
            .sorted()

        suspend fun acquireRecursively(index: Int): T {
            if (index >= canonicalPaths.size) {
                return block()
            }
            return getMutex(canonicalPaths[index]).withLock {
                acquireRecursively(index + 1)
            }
        }

        return acquireRecursively(0)
    }

    fun resetForTesting() {
        mutexes.clear()
    }
}
