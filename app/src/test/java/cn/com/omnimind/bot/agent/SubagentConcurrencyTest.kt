package cn.com.omnimind.bot.agent

import cn.com.omnimind.bot.agent.runtime.SubagentResourceGate
import cn.com.omnimind.bot.agent.tool.handlers.resolveSubagentConcurrency
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SubagentConcurrencyTest {

    private val workspaceDirA = File("build/tmp/test-workspace-a").apply { mkdirs() }
    private val workspaceDirB = File("build/tmp/test-workspace-b").apply { mkdirs() }
    private val canonicalRootA = workspaceDirA.canonicalPath
    private val canonicalRootB = workspaceDirB.canonicalPath

    @Before
    fun setUp() {
        SubagentResourceGate.resetForTesting()
    }

    @After
    fun tearDown() {
        SubagentResourceGate.resetForTesting()
    }

    // --- 并发参数解析与上下限校验行为测试 ---

    @Test
    fun `omitted concurrency defaults to at most 2 for 6 tasks`() {
        val resolved = resolveSubagentConcurrency(requested = null, taskCount = 6)
        assertEquals(2, resolved)
    }

    @Test
    fun `explicit concurrency up to 4 is permitted`() {
        assertEquals(4, resolveSubagentConcurrency(requested = 4, taskCount = 6))
        assertEquals(3, resolveSubagentConcurrency(requested = 3, taskCount = 6))
        assertEquals(1, resolveSubagentConcurrency(requested = 1, taskCount = 6))
    }

    @Test
    fun `explicit concurrency is capped at taskCount when taskCount is smaller`() {
        assertEquals(1, resolveSubagentConcurrency(requested = null, taskCount = 1))
        assertEquals(2, resolveSubagentConcurrency(requested = 4, taskCount = 2))
    }

    @Test
    fun `requested concurrency 5 or higher is rejected as an invalid parameter`() {
        try {
            resolveSubagentConcurrency(requested = 5, taskCount = 6)
            fail("Expected IllegalArgumentException for concurrency=5")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("1..4") == true)
        }
    }

    @Test
    fun `nonpositive requested concurrency is rejected as an invalid parameter`() {
        try {
            resolveSubagentConcurrency(requested = 0, taskCount = 2)
            fail("Expected IllegalArgumentException for concurrency=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("1..4") == true)
        }

        try {
            resolveSubagentConcurrency(requested = -1, taskCount = 2)
            fail("Expected IllegalArgumentException for concurrency=-1")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("1..4") == true)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty taskCount is rejected`() {
        resolveSubagentConcurrency(requested = 2, taskCount = 0)
    }

    // --- SubagentResourceGate 读写互斥与并发行为测试 ---

    @Test
    fun `shared read leases allow multiple concurrent readers on same workspace`() = runBlocking {
        val lease1 = SubagentResourceGate.acquireShared(canonicalRootA)
        val lease2 = SubagentResourceGate.acquireShared(canonicalRootA)

        assertTrue(SubagentResourceGate.isHeld(canonicalRootA))
        assertEquals(2, SubagentResourceGate.activeReaderCount(canonicalRootA))
        assertFalse(SubagentResourceGate.isWriterActive(canonicalRootA))

        lease1.release()
        assertEquals(1, SubagentResourceGate.activeReaderCount(canonicalRootA))

        lease2.release()
        assertEquals(0, SubagentResourceGate.activeReaderCount(canonicalRootA))
        assertFalse(SubagentResourceGate.isHeld(canonicalRootA))
    }

    @Test
    fun `exclusive write lease blocks readers and other writers on same workspace`() = runBlocking {
        val writeLease = SubagentResourceGate.acquireExclusive(canonicalRootA)
        assertTrue(SubagentResourceGate.isWriterActive(canonicalRootA))

        val readStarted = AtomicBoolean(false)
        val readCompleted = AtomicBoolean(false)

        val readerJob = launch {
            val lease = SubagentResourceGate.acquireShared(canonicalRootA)
            readStarted.set(true)
            lease.release()
            readCompleted.set(true)
        }

        yield()
        // 写者仍持有锁，读者不得开始
        assertFalse("Reader should be waiting while writer holds lease", readStarted.get())

        writeLease.release()
        readerJob.join()

        assertTrue("Reader should complete after writer releases", readCompleted.get())
    }

    @Test
    fun `tryAcquireExclusive succeeds when idle and fails immediately when readers or writers active`() = runBlocking {
        // 空闲时立即获取成功
        val lease1 = SubagentResourceGate.tryAcquireExclusive(canonicalRootA)
        assertNotNull(lease1)
        assertTrue(SubagentResourceGate.isWriterActive(canonicalRootA))

        // 写者活跃时立即返回 null
        val leaseBusyWriter = SubagentResourceGate.tryAcquireExclusive(canonicalRootA)
        assertNull(leaseBusyWriter)

        lease1!!.release()

        // 读者活跃时立即返回 null
        val readLease = SubagentResourceGate.acquireShared(canonicalRootA)
        val leaseBusyReader = SubagentResourceGate.tryAcquireExclusive(canonicalRootA)
        assertNull(leaseBusyReader)

        readLease.release()

        // 释放后重新可用
        val lease2 = SubagentResourceGate.tryAcquireExclusive(canonicalRootA)
        assertNotNull(lease2)
        lease2!!.release()
    }

    @Test
    fun `different workspaces operate concurrently without mutual interference`() = runBlocking {
        val writeLeaseA = SubagentResourceGate.acquireExclusive(canonicalRootA)
        // 工作区 B 的排他写锁可以立即获取，不受工作区 A 影响
        val writeLeaseB = SubagentResourceGate.acquireExclusive(canonicalRootB)

        assertTrue(SubagentResourceGate.isWriterActive(canonicalRootA))
        assertTrue(SubagentResourceGate.isWriterActive(canonicalRootB))

        writeLeaseA.release()
        writeLeaseB.release()
    }

    @Test
    fun `cancellation while waiting in acquireShared does not leak state and wakes waiting writer`() = runBlocking {
        val initialWriter = SubagentResourceGate.acquireExclusive(canonicalRootA)

        val waitingReaderStarted = AtomicBoolean(false)
        val readerJob = launch {
            waitingReaderStarted.set(true)
            SubagentResourceGate.acquireShared(canonicalRootA)
        }

        yield()
        assertTrue(waitingReaderStarted.get())

        // 取消正在等待的读者
        readerJob.cancel()
        yield()

        // 释放初始写者后，门禁应彻底变为空闲，无残留 readers
        initialWriter.release()
        assertFalse(SubagentResourceGate.isHeld(canonicalRootA))
        assertEquals(0, SubagentResourceGate.activeReaderCount(canonicalRootA))
    }

    // --- Tool Catalog Read/Write 判定行为测试 ---

    @Test
    fun `tool catalog classification correctly identifies read-only and write tools`() {
        val readOnlyToolNames = setOf("file_read", "file_list", "memory_search", "skills_read", "context_time_now")
        assertTrue(readOnlyToolNames.all { it in READ_ONLY_TOOL_NAMES })

        // 副作用与写工具必须被判定为非只读
        assertFalse("browser_use must not be in READ_ONLY_TOOL_NAMES", "browser_use" in READ_ONLY_TOOL_NAMES)
        assertFalse("file_write must not be in READ_ONLY_TOOL_NAMES", "file_write" in READ_ONLY_TOOL_NAMES)
        assertFalse("file_edit must not be in READ_ONLY_TOOL_NAMES", "file_edit" in READ_ONLY_TOOL_NAMES)
        assertFalse("terminal_execute must not be in READ_ONLY_TOOL_NAMES", "terminal_execute" in READ_ONLY_TOOL_NAMES)
        assertFalse("memory_write_daily must not be in READ_ONLY_TOOL_NAMES", "memory_write_daily" in READ_ONLY_TOOL_NAMES)
        assertFalse("unknown mcp tool must not be in READ_ONLY_TOOL_NAMES", "mcp__custom" in READ_ONLY_TOOL_NAMES)
    }
}
