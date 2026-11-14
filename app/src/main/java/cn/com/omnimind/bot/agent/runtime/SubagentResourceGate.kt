package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 资源租约，用于跨步骤保护工作区文件与共享状态。
 * 在 try-finally 中调用 [release] 或利用 [close] 进行释放。
 */
interface ResourceLease : Closeable {
    fun release()
    override fun close() = release()
}


/**
 * 全进程共享按规范化工作区路径（canonical workspace root）维护的读写门禁。
 *
 * 核心规则与锁层级（严禁反序）：
 * 1. 工作区跨步骤读写租约 ([SubagentResourceGate])
 * 2. 模型并发 Permit (Semaphore)
 * 3. 细粒度文件路径锁 / 虚拟屏租约
 *
 * 保证：
 * - 共享读租约：同一工作区下多个只读子任务可并发执行。
 * - 排他写租约：写任务需要独占访问（无活跃 reader，无其他 writer）。
 * - 恢复独占尝试：[tryAcquireExclusive] 在有活跃租约时立即返回 null，不挂起。
 * - 防反序死锁：[shouldBypassForDisplayLease] 检查 VLM 调用链，避免持 display lock 再申领 workspace 租约。
 */
object SubagentResourceGate {
    private val workspaceLocks = ConcurrentHashMap<String, WorkspaceLock>()

    private fun getLock(canonicalRoot: String): WorkspaceLock =
        workspaceLocks.computeIfAbsent(canonicalRoot) { WorkspaceLock() }

    /**
     * 获取规范化工作区路径。空值或异常回退安全默认值。
     */
    fun canonicalizeRoot(path: String?): String {
        val trimmed = path?.trim()
        if (trimmed.isNullOrEmpty()) return ""
        return runCatching { File(trimmed).canonicalPath }.getOrDefault(trimmed)
    }

    /**
     * 申请共享读租约。
     * 若当前有活跃写者或等待写者，挂起等待并触发 [onWaiting]。
     */
    suspend fun acquireShared(
        canonicalRoot: String,
        ownerId: String? = null,
        onWaiting: (suspend () -> Unit)? = null
    ): ResourceLease {
        return getLock(canonicalRoot).acquireShared(ownerId, onWaiting)
    }

    /**
     * 申请排他写租约。
     * 若当前有活跃读者或写者，挂起等待并触发 [onWaiting]。
     */
    suspend fun acquireExclusive(
        canonicalRoot: String,
        ownerId: String? = null,
        onWaiting: (suspend () -> Unit)? = null
    ): ResourceLease {
        return getLock(canonicalRoot).acquireExclusive(ownerId, onWaiting)
    }

    /**
     * 非阻塞尝试获取排他写租约。
     * 若当前有活跃读者、写者或等待写者，立即返回 null。
     * 用于步骤 5 工作区恢复时快速失败（返回 workspace_change_busy）。
     */
    fun tryAcquireExclusive(
        canonicalRoot: String,
        ownerId: String? = null
    ): ResourceLease? {
        return getLock(canonicalRoot).tryAcquireExclusive(ownerId)
    }

    fun isHeld(canonicalRoot: String): Boolean = getLock(canonicalRoot).isHeld()

    fun activeReaderCount(canonicalRoot: String): Int = getLock(canonicalRoot).activeReaderCount()

    fun isWriterActive(canonicalRoot: String): Boolean = getLock(canonicalRoot).isWriterActive()


    fun resetForTesting() {
        workspaceLocks.values.forEach { it.resetForTesting() }
        workspaceLocks.clear()
    }

    private class WorkspaceLock {
        private val lock = Any()
        private var activeReaders = 0
        private var activeWriter = false
        private var activeWriterOwner: String? = null
        private val readWaiters = mutableListOf<CancellableContinuation<Unit>>()
        private val writeWaiters = mutableListOf<Pair<String?, CancellableContinuation<Unit>>>()

        fun isHeld(): Boolean = synchronized(lock) { activeReaders > 0 || activeWriter }
        fun activeReaderCount(): Int = synchronized(lock) { activeReaders }
        fun isWriterActive(): Boolean = synchronized(lock) { activeWriter }

        suspend fun acquireShared(
            ownerId: String?,
            onWaiting: (suspend () -> Unit)?
        ): ResourceLease {
            var mustWait = false
            synchronized(lock) {
                if (!activeWriter && writeWaiters.isEmpty()) {
                    activeReaders++
                    return ReadLease(this)
                }
                mustWait = true
            }
            if (mustWait) {
                onWaiting?.invoke()
            }
            suspendCancellableCoroutine<Unit> { cont ->
                synchronized(lock) {
                    if (!activeWriter && writeWaiters.isEmpty()) {
                        activeReaders++
                        cont.resume(Unit) {
                            synchronized(lock) {
                                activeReaders--
                                checkAndWakeNext()
                            }
                        }
                        return@suspendCancellableCoroutine
                    }
                    readWaiters.add(cont)
                    cont.invokeOnCancellation {
                        synchronized(lock) {
                            readWaiters.remove(cont)
                            checkAndWakeNext()
                        }
                    }
                }
            }
            return ReadLease(this)
        }

        suspend fun acquireExclusive(
            ownerId: String?,
            onWaiting: (suspend () -> Unit)?
        ): ResourceLease {
            var mustWait = false
            synchronized(lock) {
                if (!activeWriter && activeReaders == 0) {
                    activeWriter = true
                    activeWriterOwner = ownerId
                    return WriteLease(this)
                }
                mustWait = true
            }
            if (mustWait) {
                onWaiting?.invoke()
            }
            suspendCancellableCoroutine<Unit> { cont ->
                synchronized(lock) {
                    if (!activeWriter && activeReaders == 0) {
                        activeWriter = true
                        activeWriterOwner = ownerId
                        cont.resume(Unit) {
                            synchronized(lock) {
                                activeWriter = false
                                activeWriterOwner = null
                                checkAndWakeNext()
                            }
                        }
                        return@suspendCancellableCoroutine
                    }
                    writeWaiters.add(ownerId to cont)
                    cont.invokeOnCancellation {
                        synchronized(lock) {
                            writeWaiters.removeAll { it.second == cont }
                            checkAndWakeNext()
                        }
                    }
                }
            }
            return WriteLease(this)
        }

        fun tryAcquireExclusive(ownerId: String?): ResourceLease? {
            synchronized(lock) {
                if (!activeWriter && activeReaders == 0 && writeWaiters.isEmpty()) {
                    activeWriter = true
                    activeWriterOwner = ownerId
                    return WriteLease(this)
                }
                return null
            }
        }

        internal fun releaseShared() {
            synchronized(lock) {
                check(activeReaders > 0) { "SubagentResourceGate: releaseShared called with no active readers" }
                activeReaders--
                checkAndWakeNext()
            }
        }

        internal fun releaseExclusive() {
            synchronized(lock) {
                check(activeWriter) { "SubagentResourceGate: releaseExclusive called with no active writer" }
                activeWriter = false
                activeWriterOwner = null
                checkAndWakeNext()
            }
        }

        private fun checkAndWakeNext() {
            if (activeWriter) return

            // 写者优先唤醒，防止写任务饥饿
            if (activeReaders == 0 && writeWaiters.isNotEmpty()) {
                val (owner, waiter) = writeWaiters.removeAt(0)
                activeWriter = true
                activeWriterOwner = owner
                waiter.resume(Unit) {
                    synchronized(lock) {
                        activeWriter = false
                        activeWriterOwner = null
                        checkAndWakeNext()
                    }
                }
                return
            }

            // 无活跃写者且无等待写者时，唤醒所有等待读者
            if (!activeWriter && writeWaiters.isEmpty() && readWaiters.isNotEmpty()) {
                val readers = readWaiters.toList()
                readWaiters.clear()
                activeReaders += readers.size
                for (reader in readers) {
                    reader.resume(Unit) {
                        synchronized(lock) {
                            activeReaders--
                            checkAndWakeNext()
                        }
                    }
                }
            }
        }

        fun resetForTesting() {
            synchronized(lock) {
                activeReaders = 0
                activeWriter = false
                activeWriterOwner = null
                for ((_, w) in writeWaiters) {
                    runCatching { w.cancel() }
                }
                writeWaiters.clear()
                for (r in readWaiters) {
                    runCatching { r.cancel() }
                }
                readWaiters.clear()
            }
        }
    }

    private class ReadLease(private val lock: WorkspaceLock) : ResourceLease {
        private var released = false
        override fun release() {
            synchronized(this) {
                if (released) return
                released = true
            }
            lock.releaseShared()
        }
    }

    private class WriteLease(private val lock: WorkspaceLock) : ResourceLease {
        private var released = false
        override fun release() {
            synchronized(this) {
                if (released) return
                released = true
            }
            lock.releaseExclusive()
        }
    }
}
