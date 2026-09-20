package cn.com.omnimind.baselib.privileged

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivilegedCommandExecutorTest {
    @Test fun suDenialBecomesPermissionDenied() {
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) {
            throw SuDeniedException("revoked")
        }
        val request = PrivilegedRequest(
            requestId = "denied",
            action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP,
        )

        val result = executor.execute(request)

        assertFalse(result.success)
        assertEquals("permission_denied", result.code)
        assertEquals(PrivilegedSource.SU, result.source)
    }

    @Test fun launcherCancellationIsPropagatedUnchanged() {
        val cancellation = CancellationException("cancel")
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) {
            throw cancellation
        }
        val request = PrivilegedRequest(
            requestId = "cancel-launch",
            action = PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP,
        )

        try {
            executor.execute(request)
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test fun boundedCopyNeverRetainsMoreThanLimit() {
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) { error("unused") }
        val output = ByteArrayOutputStream()

        executor.copyBounded(ByteArrayInputStream(ByteArray(32_000) { 'x'.code.toByte() }), output)

        assertEquals(16_000, output.size())
    }

    @Test fun interruptedWaitClosesAndDestroysDirectProcess() {
        val process = InterruptingProcess()
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) {
            LaunchedPrivilegedShell(process, PrivilegedAuthority.ROOT, 0, "/system/bin/su")
        }
        val request = PrivilegedRequest(
            requestId = "cancel",
            action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
            arguments = mapOf("confirmed" to "true"),
            command = "echo test",
        )

        try {
            executor.execute(request)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(process.isAlive)
            assertEquals(listOf("closeStdin", "destroy"), process.events)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun concurrentReadCloseDuringCancellationDoesNotEscapeWorkerThread() {
        val uncaught = AtomicReference<Throwable?>()
        val process = InterruptingProcess(closeInterruptedReaders = true)
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) {
            LaunchedPrivilegedShell(process, PrivilegedAuthority.ROOT, 0, "/system/bin/su")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.compareAndSet(null, error) }
        try {
            try {
                executor.execute(shellRequest("cancel-close"))
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertFalse(process.isAlive)
            }
            Thread.interrupted()
            Thread.sleep(50)
            assertEquals(null, uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            Thread.interrupted()
        }
    }

    @Test fun genuineReaderFailureCannotReportSuccessfulCommand() {
        val process = CompletedProcess(FailingInputStream())
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) {
            LaunchedPrivilegedShell(process, PrivilegedAuthority.ROOT, 0, "/system/bin/su")
        }

        val result = executor.executeRawShell(
            shellRequest("read-failure"),
            PrivilegedAuthority.ROOT,
            LaunchedPrivilegedShell(process, PrivilegedAuthority.ROOT, 0, "/system/bin/su"),
        )

        assertFalse(result.success)
        assertEquals("command_failed", result.code)
        assertEquals("true", result.data["outputComplete"])
    }

    @Test fun normalExitWithBlockedReaderReportsIncompleteOutputAndClosesReader() {
        val blocked = BlockingCloseInputStream()
        val process = CompletedProcess(blocked)
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) { error("unused") }

        val result = executor.executeRawShell(
            shellRequest("blocked-reader"),
            PrivilegedAuthority.ROOT,
            LaunchedPrivilegedShell(process, PrivilegedAuthority.ROOT, 0, "/system/bin/su"),
        )

        assertTrue(result.success)
        assertEquals(0, result.exitCode)
        assertEquals("false", result.data["outputComplete"])
        assertTrue(blocked.closed.await(1, TimeUnit.SECONDS))
        assertTrue(blocked.finished.await(1, TimeUnit.SECONDS))
    }

    @Test fun completeReaderReportsCompleteOutput() {
        val process = CompletedProcess(ByteArrayInputStream("complete".toByteArray()))
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) { error("unused") }

        val result = executor.executeRawShell(
            shellRequest("complete-reader"),
            PrivilegedAuthority.ROOT,
            LaunchedPrivilegedShell(process, PrivilegedAuthority.ROOT, 0, "/system/bin/su"),
        )

        assertTrue(result.success)
        assertEquals("complete", result.stdout)
        assertEquals("true", result.data["outputComplete"])
    }

    private fun shellRequest(id: String) = PrivilegedRequest(
        requestId = id,
        action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
        arguments = mapOf("confirmed" to "true"),
        command = "echo test",
    )

    private class InterruptingProcess(private val closeInterruptedReaders: Boolean = false) : Process() {
        val events = mutableListOf<String>()
        private var alive = true
        private val stdin = object : ByteArrayOutputStream() {
            private var closed = false
            override fun close() {
                if (!closed) events += "closeStdin"
                closed = true
                super.close()
            }
        }
        override fun getOutputStream(): OutputStream = stdin
        private val stdout = if (closeInterruptedReaders) CloseInterruptedInputStream() else null
        private val stderr = if (closeInterruptedReaders) CloseInterruptedInputStream() else null
        override fun getInputStream(): InputStream = stdout ?: ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream(): InputStream = stderr ?: ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = throw InterruptedException("cancel")
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            events += "destroy"
            alive = false
            stdout?.interruptByClose()
            stderr?.interruptByClose()
        }
        override fun destroyForcibly(): Process { alive = false; return this }
        override fun isAlive(): Boolean = alive
    }

    private class CloseInterruptedInputStream : InputStream() {
        private val lock = Object()
        private var closedByOwner = false
        override fun read(): Int {
            synchronized(lock) {
                while (!closedByOwner) lock.wait()
            }
            throw InterruptedIOException("read interrupted by close() on another thread")
        }
        fun interruptByClose() {
            synchronized(lock) {
                closedByOwner = true
                lock.notifyAll()
            }
        }
    }

    private class FailingInputStream : InputStream() {
        override fun read(): Int = throw java.io.IOException("genuine read failure")
    }

    private class BlockingCloseInputStream : InputStream() {
        private val lock = Object()
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private var isClosed = false
        override fun read(): Int {
            try {
                synchronized(lock) {
                    while (!isClosed) lock.wait()
                }
                return -1
            } finally {
                finished.countDown()
            }
        }
        override fun close() {
            synchronized(lock) {
                isClosed = true
                lock.notifyAll()
            }
            closed.countDown()
        }
    }

    private class CompletedProcess(private val stdout: InputStream) : Process() {
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
        override fun isAlive(): Boolean = false
    }
}
