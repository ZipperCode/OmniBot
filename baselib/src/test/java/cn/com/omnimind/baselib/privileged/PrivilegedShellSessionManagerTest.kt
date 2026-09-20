package cn.com.omnimind.baselib.privileged

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedShellSessionManagerTest {
    @After fun cleanup() {
        PrivilegedShellSessionManager.shutdown()
    }

    @Test fun sourceMismatchDoesNotRedirectOrRemoveOriginalSession() {
        val process = SessionProcess()
        val sessionId = "source-bound"
        val start = PrivilegedShellSessionManager.startSession(
            request = request(PrivilegedActionPolicy.ACTION_SESSION_START, sessionId),
            authority = PrivilegedAuthority.ROOT,
            source = PrivilegedSource.SU,
            process = process,
        )

        val mismatch = PrivilegedShellSessionManager.readSession(
            request = request(PrivilegedActionPolicy.ACTION_SESSION_READ, sessionId),
            authority = PrivilegedAuthority.ADB,
            source = PrivilegedSource.SHIZUKU,
        )

        assertTrue(start.success)
        assertFalse(mismatch.success)
        assertEquals("session_source_mismatch", mismatch.code)
        assertEquals(PrivilegedAuthority.ROOT, PrivilegedShellSessionManager.sessionAuthority(sessionId, PrivilegedSource.SU))
        assertNull(PrivilegedShellSessionManager.sessionAuthority(sessionId, PrivilegedSource.SHIZUKU))
        assertTrue(process.isAlive)
    }

    @Test fun teardownUsesRecordedSourceWithoutGrantLookup() {
        val process = SessionProcess()
        val sessionId = "revoked-grant-stop"
        PrivilegedShellSessionManager.startSession(
            request(sessionId = sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU, process
        )
        val executor = PrivilegedCommandExecutor(PrivilegedSource.SU) { error("停止现有会话不应重新启动或授权") }

        val result = executor.execute(request(PrivilegedActionPolicy.ACTION_SESSION_STOP, sessionId))

        assertTrue(result.success)
        assertEquals("true", result.data["stopped"])
        assertFalse(process.isAlive)
    }

    @Test fun sourceShutdownCannotLeaveLateOldSourceRegistration() {
        val oldProcess = SessionProcess()
        val newProcess = SessionProcess()
        PrivilegedShellSessionManager.startSession(
            request(sessionId = "old"), PrivilegedAuthority.ROOT, PrivilegedSource.SU, oldProcess
        )

        assertTrue(PrivilegedShellSessionManager.shutdown(PrivilegedSource.SU))
        PrivilegedShellSessionManager.startSession(
            request(sessionId = "new"), PrivilegedAuthority.ADB, PrivilegedSource.SHIZUKU, newProcess
        )

        assertNull(PrivilegedShellSessionManager.sessionAuthority("old", PrivilegedSource.SU))
        assertEquals(PrivilegedAuthority.ADB, PrivilegedShellSessionManager.sessionAuthority("new", PrivilegedSource.SHIZUKU))
        assertFalse(oldProcess.isAlive)
        assertTrue(newProcess.isAlive)
    }

    @Test fun shutdownClearsRoutingAndClosesStdinBeforeDestroy() {
        val process = SessionProcess()
        val sessionId = "shutdown"
        PrivilegedShellSessionManager.startSession(
            request = request(PrivilegedActionPolicy.ACTION_SESSION_START, sessionId),
            authority = PrivilegedAuthority.ADB,
            source = PrivilegedSource.SHIZUKU,
            process = process,
        )

        val stopped = PrivilegedShellSessionManager.shutdown()

        assertTrue(stopped)
        assertNull(PrivilegedShellSessionManager.sessionAuthority(sessionId, PrivilegedSource.SHIZUKU))
        assertNull(PrivilegedShellSessionManager.sessionAuthority(sessionId, PrivilegedSource.SU))
        assertFalse(process.isAlive)
        assertEquals(listOf("closeStdin", "destroy"), process.events)
    }

    @Test fun duplicateSessionClosesRejectedProcess() {
        val first = SessionProcess()
        val rejected = SessionProcess()
        val sessionId = "duplicate"
        PrivilegedShellSessionManager.startSession(
            request(sessionId = sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU, first
        )

        val result = PrivilegedShellSessionManager.startSession(
            request(sessionId = sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU, rejected
        )

        assertFalse(result.success)
        assertEquals("session_exists", result.code)
        assertFalse(rejected.isAlive)
        assertEquals(listOf("closeStdin", "destroy"), rejected.events)
        assertTrue(first.isAlive)
    }

    @Test fun sessionConstructionFailureClosesLaunchedProcess() {
        val process = SessionProcess(failOutputStream = true)

        val result = PrivilegedShellSessionManager.startSession(
            request(sessionId = "construction-failure"), PrivilegedAuthority.ROOT, PrivilegedSource.SU, process
        )

        assertFalse(result.success)
        assertEquals("session_start_failed", result.code)
        assertFalse(process.isAlive)
        assertEquals(listOf("destroy"), process.events)
    }

    @Test fun hugeSingleLineIsBoundedBeforeNewline() {
        val process = InteractiveSessionProcess()
        val sessionId = "huge-line"
        PrivilegedShellSessionManager.startSession(
            request(sessionId = sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU, process
        )

        process.writeStdout("x".repeat(64_000) + "\n")
        waitUntil { PrivilegedShellSessionManager.readSession(
            request(PrivilegedActionPolicy.ACTION_SESSION_READ, sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU
        ).transcript.isNotEmpty() }
        val transcript = PrivilegedShellSessionManager.readSession(
            request(PrivilegedActionPolicy.ACTION_SESSION_READ, sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU
        ).transcript

        assertTrue(transcript.length <= 16_000)
        assertTrue(transcript.all { it == 'x' })
    }

    @Test fun timeoutKeepsSessionBusyUntilLateMarkersComplete() {
        val process = InteractiveSessionProcess()
        val sessionId = "late-marker"
        PrivilegedShellSessionManager.startSession(
            request(sessionId = sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU, process
        )
        val timedOut = PrivilegedShellSessionManager.execSession(
            request(PrivilegedActionPolicy.ACTION_SESSION_EXEC, sessionId).copy(command = "sleep", timeoutSeconds = 5),
            PrivilegedAuthority.ROOT,
            PrivilegedSource.SU,
        )

        assertEquals("timeout", timedOut.code)
        assertEquals("true", timedOut.data["alive"])
        assertEquals("true", timedOut.data["running"])
        val busy = PrivilegedShellSessionManager.execSession(
            request(PrivilegedActionPolicy.ACTION_SESSION_EXEC, sessionId).copy(command = "second"),
            PrivilegedAuthority.ROOT,
            PrivilegedSource.SU,
        )
        assertEquals("session_busy", busy.code)

        val token = Regex("__OMNIBOT_DONE__([0-9a-f-]+)").find(process.stdinText())!!.groupValues[1]
        process.writeStdout("__OMNIBOT_DONE__$token\u001f0\u001f/test\n")
        process.writeStderr("__OMNIBOT_DONE_ERR__$token\n")
        waitUntil { PrivilegedShellSessionManager.readSession(
            request(PrivilegedActionPolicy.ACTION_SESSION_READ, sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU
        ).data["running"] == "false" }
        val completedRead = PrivilegedShellSessionManager.readSession(
            request(PrivilegedActionPolicy.ACTION_SESSION_READ, sessionId), PrivilegedAuthority.ROOT, PrivilegedSource.SU
        )
        assertEquals("false", completedRead.data["running"])
        assertTrue(process.isAlive)
    }

    private fun request(action: String = PrivilegedActionPolicy.ACTION_SESSION_START, sessionId: String) = PrivilegedRequest(
        requestId = "$action-$sessionId",
        action = action,
        sessionId = sessionId,
    )

    private class SessionProcess(private val failOutputStream: Boolean = false) : Process() {
        val events = mutableListOf<String>()
        private val lock = Object()
        @Volatile private var alive = true
        private val stdout = BlockingInputStream()
        private val stderr = BlockingInputStream()
        private val stdin = object : OutputStream() {
            private var closed = false
            override fun write(value: Int) = Unit
            override fun close() {
                if (!closed) events += "closeStdin"
                closed = true
            }
        }

        override fun getOutputStream(): OutputStream {
            if (failOutputStream) throw IllegalStateException("stdin unavailable")
            return stdin
        }
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int {
            synchronized(lock) {
                while (alive) lock.wait()
            }
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            synchronized(lock) {
                if (alive) lock.wait(unit.toMillis(timeout))
                return !alive
            }
        }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            events += "destroy"
            alive = false
            stdout.finish()
            stderr.finish()
            synchronized(lock) { lock.notifyAll() }
        }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive
    }

    private class InteractiveSessionProcess : Process() {
        private val stdoutWriter = PipedOutputStream()
        private val stderrWriter = PipedOutputStream()
        private val stdout = PipedInputStream(stdoutWriter, 128_000)
        private val stderr = PipedInputStream(stderrWriter, 128_000)
        private val stdin = java.io.ByteArrayOutputStream()
        private val lock = Object()
        @Volatile private var alive = true

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { synchronized(lock) { while (alive) lock.wait() }; return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = synchronized(lock) {
            if (alive) lock.wait(unit.toMillis(timeout)); !alive
        }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            alive = false
            runCatching { stdoutWriter.close() }
            runCatching { stderrWriter.close() }
            synchronized(lock) { lock.notifyAll() }
        }
        override fun destroyForcibly(): Process { destroy(); return this }
        override fun isAlive(): Boolean = alive
        fun writeStdout(value: String) { stdoutWriter.write(value.toByteArray()); stdoutWriter.flush() }
        fun writeStderr(value: String) { stderrWriter.write(value.toByteArray()); stderrWriter.flush() }
        fun stdinText(): String = stdin.toString()
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!predicate()) {
            if (System.currentTimeMillis() >= deadline) throw AssertionError("condition was not met")
            Thread.sleep(10)
        }
    }

    private class BlockingInputStream : InputStream() {
        private val lock = Object()
        @Volatile private var finished = false
        override fun read(): Int {
            synchronized(lock) {
                while (!finished) lock.wait()
            }
            return -1
        }
        fun finish() {
            finished = true
            synchronized(lock) { lock.notifyAll() }
        }
    }
}
