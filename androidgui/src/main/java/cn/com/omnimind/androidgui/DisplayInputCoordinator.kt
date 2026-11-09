package cn.com.omnimind.androidgui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class DisplayLease(
    val displayId: Int,
    val ownerId: String,
    val acquiredAtMs: Long,
    val leaseId: Long,
)

/**
 * Coordinates exclusive multi-step GUI input leases and per-action execution per display.
 *
 * Guarantees:
 * - Each physical or virtual screen (displayId) has independent queueing; different screens do not block each other.
 * - Multi-step GUI runs (such as vlm_task or device_display_action sequences) hold an exclusive lease on their target displayId, preventing interleaving clicks from other tasks.
 * - Reentrant: Actions inside an ongoing run reusing the same ownerId proceed without deadlocks.
 * - Fast stop/takeover: Only cancels the job/lease belonging to the requested ownerId, without killing other tasks.
 * - Scoped executor isolation: Root/input command executors are strictly scoped to the active lease owner; when the lease ends or is cancelled, executors and owned virtual displays are cleaned up automatically without cross-session leakage.
 * - Background compilation, file operations, and downloads do NOT acquire display leases, remaining fully concurrent.
 */
object DisplayInputCoordinator {
    private val leaseIdGenerator = AtomicLong(1L)

    private val displayMutexes = ConcurrentHashMap<Int, Mutex>()
    private val activeLeases = ConcurrentHashMap<Int, DisplayLease>()
    private val reentrantCounts = ConcurrentHashMap<Int, Int>()
    private val activeJobs = ConcurrentHashMap<Int, Pair<String, Job>>()
    private val leaseExecutors = ConcurrentHashMap<Int, Pair<String, (List<String>) -> Boolean>>()
    private val leaseUnlockAwaiters = ConcurrentHashMap<Int, Pair<String, DeviceUnlockAwaiter>>()
    private val sessionVirtualDisplays = ConcurrentHashMap<Int, String>()
    private fun getMutex(displayId: Int): Mutex =
        displayMutexes.computeIfAbsent(displayId) { Mutex() }

    suspend fun acquireLease(
        displayId: Int,
        ownerId: String,
        timeoutMs: Long = 30_000L,
    ): DisplayLease {
        require(ownerId.isNotBlank()) { "lease ownerId cannot be blank" }
        val mutex = getMutex(displayId)

        synchronized(this) {
            val existing = activeLeases[displayId]
            if (existing != null && existing.ownerId == ownerId) {
                reentrantCounts[displayId] = (reentrantCounts[displayId] ?: 1) + 1
                return existing
            }
        }

        withTimeout(timeoutMs) {
            mutex.lock()
        }

        return synchronized(this) {
            val lease = DisplayLease(
                displayId = displayId,
                ownerId = ownerId,
                acquiredAtMs = System.currentTimeMillis(),
                leaseId = leaseIdGenerator.getAndIncrement(),
            )
            activeLeases[displayId] = lease
            reentrantCounts[displayId] = 1
            lease
        }
    }

    fun releaseLease(displayId: Int, ownerId: String) {
        val mutex = getMutex(displayId)
        val shouldUnlock = synchronized(this) {
            val current = activeLeases[displayId]
            if (current == null || current.ownerId != ownerId) {
                return@synchronized false
            }
            val count = (reentrantCounts[displayId] ?: 1) - 1
            if (count > 0) {
                reentrantCounts[displayId] = count
                return@synchronized false
            }
            activeLeases.remove(displayId)
            reentrantCounts.remove(displayId)
            activeJobs.remove(displayId)
            val executorPair = leaseExecutors[displayId]
            if (executorPair?.first == ownerId) {
                leaseExecutors.remove(displayId)
            }
            val awaiterPair = leaseUnlockAwaiters[displayId]
            if (awaiterPair?.first == ownerId) {
                leaseUnlockAwaiters.remove(displayId)
            }
            true
        }
        if (shouldUnlock && mutex.isLocked) {
            runCatching { mutex.unlock() }
        }
    }

    suspend fun <T> withDisplayLease(
        displayId: Int,
        ownerId: String,
        timeoutMs: Long = 60_000L,
        block: suspend () -> T,
    ): T {
        acquireLease(displayId, ownerId, timeoutMs)
        return try {
            block()
        } finally {
            releaseLease(displayId, ownerId)
        }
    }

    /**
     * Acquires exclusive leases on multiple displays in strictly deduplicated, ascending order,
     * executing [block] and releasing in reverse order upon completion or failure.
     *
     * Guarantees:
     * - Sorts displayIds ascending to prevent cyclic lock inversions (deadlocks).
     * - Uses [Mutex.tryLock] without blocking; if any display is locked or held by another owner,
     *   immediately releases all newly acquired locks in reverse order and throws [IllegalStateException] with "display_busy".
     * - Reentrancy: displays already held by the same [ownerId] increment their reentrant count and do NOT re-lock.
     */
    suspend fun <T> withDisplayLeases(
        displayIds: List<Int>,
        ownerId: String,
        block: suspend () -> T,
    ): T {
        require(ownerId.isNotBlank()) { "lease ownerId cannot be blank" }
        val sortedDisplayIds = displayIds.distinct().sorted()

        val acquired = mutableListOf<Pair<Int, Boolean>>()

        try {
            for (id in sortedDisplayIds) {
                val mutex = getMutex(id)

                val isReentrant = synchronized(this) {
                    val current = activeLeases[id]
                    if (current != null && current.ownerId == ownerId) {
                        reentrantCounts[id] = (reentrantCounts[id] ?: 1) + 1
                        true
                    } else {
                        false
                    }
                }

                if (isReentrant) {
                    acquired.add(id to true)
                } else {
                    val locked = mutex.tryLock()
                    if (!locked) {
                        throw IllegalStateException("display_busy")
                    }

                    synchronized(this) {
                        val current = activeLeases[id]
                        if (current != null) {
                            mutex.unlock()
                            throw IllegalStateException("display_busy")
                        }
                        val lease = DisplayLease(
                            displayId = id,
                            ownerId = ownerId,
                            acquiredAtMs = System.currentTimeMillis(),
                            leaseId = leaseIdGenerator.getAndIncrement(),
                        )
                        activeLeases[id] = lease
                        reentrantCounts[id] = 1
                    }
                    acquired.add(id to false)
                }
            }
            return block()
        } finally {
            for ((id, wasReentrant) in acquired.reversed()) {
                val mutex = getMutex(id)
                if (wasReentrant) {
                    synchronized(this) {
                        val current = activeLeases[id]
                        if (current != null && current.ownerId == ownerId) {
                            val count = (reentrantCounts[id] ?: 1) - 1
                            if (count > 0) {
                                reentrantCounts[id] = count
                            } else {
                                reentrantCounts[id] = 1
                            }
                        }
                    }
                } else {
                    synchronized(this) {
                        val current = activeLeases[id]
                        if (current != null && current.ownerId == ownerId) {
                            activeLeases.remove(id)
                            reentrantCounts.remove(id)
                            activeJobs.remove(id)
                            if (leaseExecutors[id]?.first == ownerId) {
                                leaseExecutors.remove(id)
                            }
                            if (leaseUnlockAwaiters[id]?.first == ownerId) {
                                leaseUnlockAwaiters.remove(id)
                            }
                        }
                    }
                    if (mutex.isLocked) {
                        runCatching { mutex.unlock() }
                    }
                }
            }
        }
    }

    suspend fun <T> withDisplayLock(
        displayId: Int,
        ownerId: String? = null,
        timeoutMs: Long = 30_000L,
        block: suspend () -> T,
    ): T {
        val isReentrant = synchronized(this) {
            val current = activeLeases[displayId]
            ownerId != null && current != null && current.ownerId == ownerId
        }

        if (isReentrant) {
            return block()
        }

        val actionOwner = ownerId ?: "action-${leaseIdGenerator.getAndIncrement()}"
        return withDisplayLease(displayId, actionOwner, timeoutMs, block)
    }

    fun registerLeaseExecutor(displayId: Int, ownerId: String, executor: (List<String>) -> Boolean) {
        synchronized(this) {
            val current = activeLeases[displayId]
            if (current != null && current.ownerId == ownerId) {
                leaseExecutors[displayId] = ownerId to executor
            }
        }
    }

    fun getLeaseExecutor(displayId: Int, ownerId: String? = null): ((List<String>) -> Boolean)? {
        val pair = leaseExecutors[displayId] ?: return null
        if (ownerId != null && pair.first != ownerId) return null
        return pair.second
    }

    fun clearLeaseExecutor(displayId: Int, ownerId: String) {
        synchronized(this) {
            val pair = leaseExecutors[displayId]
            if (pair == null || pair.first == ownerId) {
                leaseExecutors.remove(displayId)
            }
        }
    }

    fun registerLeaseUnlockAwaiter(displayId: Int, ownerId: String, awaiter: DeviceUnlockAwaiter) {
        synchronized(this) {
            val current = activeLeases[displayId]
            if (current != null && current.ownerId == ownerId) {
                leaseUnlockAwaiters[displayId] = ownerId to awaiter
            }
        }
    }

    fun getLeaseUnlockAwaiter(displayId: Int, ownerId: String? = null): DeviceUnlockAwaiter? {
        val pair = leaseUnlockAwaiters[displayId] ?: return null
        if (ownerId != null && pair.first != ownerId) return null
        return pair.second
    }

    fun clearLeaseUnlockAwaiter(displayId: Int, ownerId: String) {
        synchronized(this) {
            val current = activeLeases[displayId]
            if (current == null || current.ownerId == ownerId) {
                leaseUnlockAwaiters.remove(displayId)
            }
        }
    }

    fun registerVirtualDisplayOwnership(displayId: Int, ownerId: String) {
        sessionVirtualDisplays[displayId] = ownerId
    }

    fun getVirtualDisplayOwner(displayId: Int): String? = sessionVirtualDisplays[displayId]

    fun releaseVirtualDisplayOwnership(displayId: Int, ownerId: String? = null): Boolean {
        synchronized(this) {
            val current = sessionVirtualDisplays[displayId] ?: return false
            if (ownerId != null && current != ownerId) {
                return false
            }
            sessionVirtualDisplays.remove(displayId)
            return true
        }
    }

    fun closeSessionVirtualDisplays(sessionOwnerId: String) {
        val toDestroy = mutableListOf<Int>()
        synchronized(this) {
            sessionVirtualDisplays.forEach { (displayId, owner) ->
                if (owner == sessionOwnerId) {
                    toDestroy.add(displayId)
                }
            }
            toDestroy.forEach { sessionVirtualDisplays.remove(it) }
        }
        toDestroy.forEach { displayId ->
            DeviceDisplayManager.destroyVirtualDisplay(displayId, sessionOwnerId)
        }
        DeviceDisplayManager.clearSessionDisplayTarget(sessionOwnerId)
    }

    fun registerActiveJob(displayId: Int, ownerId: String, job: Job) {
        activeJobs[displayId] = ownerId to job
    }

    fun unregisterActiveJob(displayId: Int, job: Job) {
        synchronized(this) {
            val pair = activeJobs[displayId]
            if (pair != null && pair.second == job) {
                activeJobs.remove(displayId)
            }
        }
    }

    fun cancelActiveOperation(displayId: Int, ownerId: String? = null) {
        val jobToCancel = synchronized(this) {
            val pair = activeJobs[displayId]
            if (pair == null) return@synchronized null
            if (ownerId != null && pair.first != ownerId) return@synchronized null
            activeJobs.remove(displayId)
            pair.second
        }
        jobToCancel?.cancel(CancellationException("Operation cancelled by user/takeover on display $displayId"))
    }

    fun currentLease(displayId: Int): DisplayLease? = activeLeases[displayId]

    fun isLeaseHeld(displayId: Int): Boolean = activeLeases.containsKey(displayId)

    fun resetForTesting() {
        synchronized(this) {
            activeLeases.clear()
            reentrantCounts.clear()
            activeJobs.clear()
            leaseExecutors.clear()
            leaseUnlockAwaiters.clear()
            sessionVirtualDisplays.clear()
        }
            DeviceDisplayManager.resetForTesting()
        displayMutexes.values.forEach { mutex ->
            if (mutex.isLocked) {
                runCatching { mutex.unlock() }
            }
        }
        displayMutexes.clear()
    }
}
