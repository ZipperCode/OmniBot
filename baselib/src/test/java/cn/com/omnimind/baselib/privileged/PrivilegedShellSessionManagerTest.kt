package cn.com.omnimind.baselib.privileged

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedShellSessionManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sessions(uid: String = "0"): PrivilegedShellSessionManager {
        val bin = temporary.newFolder()
        val envInit = File(bin, "env_init.sh").apply {
            writeText("id() { printf '%s\\n' '$uid'; }\n")
        }
        File(bin, "id").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' '$uid'\n")
            setExecutable(true)
        }
        // 使用跨平台本地 shell 模拟已授权的后端，只验证会话协议，不申请本机 Root。
        return PrivilegedShellSessionManager(PrivilegedProcessRunner(startProcess = {
            ProcessBuilder(PlatformProcessTestHelper.shellPath).apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["ENV"] = envInit.absolutePath
                environment()["BASH_ENV"] = envInit.absolutePath
            }.start()
        }))
    }

    private fun request(action: String, sessionId: String? = null, command: String? = null) = PrivilegedRequest(
        UUID.randomUUID().toString(), action, sessionId = sessionId, command = command,
    )

    @Test fun cwdAndEnvironmentPersistAcrossCommands() {
        val manager = sessions()
        try {
            val directory = temporary.newFolder()
            val start = manager.startSession(request("shell.session_start").copy(
                workingDirectory = directory.absolutePath, environment = mapOf("OMNI_TEST_VALUE" to "first")), PrivilegedBackend.ROOT)
            assertTrue(start.message, start.success)
            val first = manager.execSession(request("shell.session_exec", start.sessionId, "printf '%s:%s' \"\$PWD\" \"\$OMNI_TEST_VALUE\"; export OMNI_TEST_VALUE=second"), PrivilegedBackend.ROOT)
            assertTrue(first.message, first.success)
            val rawPath = first.stdout.trim().substringBefore(":")
            val resolvedFile = File(PlatformProcessTestHelper.fromPosixPath(rawPath))
            assertEquals(first.stdout, directory.canonicalFile, resolvedFile.canonicalFile)
            assertEquals("first", first.stdout.trim().substringAfterLast(":"))
            val second = manager.execSession(request("shell.session_exec", start.sessionId, "printf '%s' \"\$OMNI_TEST_VALUE\""), PrivilegedBackend.ROOT)
            assertEquals("second", second.stdout.trim())
        } finally { manager.shutdown() }
    }

    @Test fun unverifiedShellIdentityIsNotAcceptedAsRoot() {
        val manager = sessions(uid = "2000")
        try {
            val req = request("shell.session_start", sessionId = "custom-root-session")
            val result = manager.startSession(req, PrivilegedBackend.ROOT)
            assertFalse(result.success)
            assertEquals("root_denied", result.code)
            assertEquals("custom-root-session", result.sessionId)
            assertEquals("2000", result.stdout.trim())
        } finally { manager.shutdown() }
    }

    @Test fun outputWithoutNewlinesIsBoundedAndStillFindsTheCompletionMarker() {
        val manager = sessions()
        try {
            val start = manager.startSession(request("shell.session_start"), PrivilegedBackend.ROOT)
            assertTrue(start.success)
            val result = manager.execSession(request("shell.session_exec", start.sessionId,
                "i=0; while [ \"\$i\" -lt 10000 ]; do printf 0123456789; i=\$((i+1)); done"), PrivilegedBackend.ROOT)
            assertTrue(result.message, result.success)
            assertTrue(result.stdout.startsWith("...[earlier output truncated]"))
            assertTrue(result.stdout.length < 66000)
        } finally { manager.shutdown() }
    }

    @Test fun cancellationClosesTheSessionAndDoesNotReplayIt() = runBlocking {
        val manager = sessions()
        try {
            val start = manager.startSession(request("shell.session_start"), PrivilegedBackend.ROOT)
            val marker = File(temporary.root, "started")
            val posixMarker = PlatformProcessTestHelper.toPosixPath(marker.absolutePath)
            val running = launch(Dispatchers.IO) {
                runInterruptible {
                    manager.execSession(request("shell.session_exec", start.sessionId,
                        "printf started > '$posixMarker'; while :; do :; done"), PrivilegedBackend.ROOT)
                }
            }
            withTimeout(5000) { while (!marker.exists()) delay(10) }
            withTimeout(3000) { running.cancelAndJoin() }
            val subsequent = manager.execSession(request("shell.session_exec", start.sessionId, "echo should-not-run"), PrivilegedBackend.ROOT)
            assertEquals("session_not_found", subsequent.code)
        } finally { manager.shutdown() }
    }

    @Test fun readAndWriteSessionCapturesOutputAndHandlesStop() {
        val manager = sessions()
        try {
            val start = manager.startSession(request("shell.session_start", sessionId = "test-session"), PrivilegedBackend.ROOT)
            assertTrue(start.success)
            assertEquals("test-session", start.sessionId)
            assertEquals(0, start.uid)

            val exec = manager.execSession(request("shell.session_exec", start.sessionId, "echo hello-stdout; echo hello-stderr >&2"), PrivilegedBackend.ROOT)
            assertTrue(exec.success)
            assertEquals("hello-stdout", exec.stdout.trim())
            assertEquals("hello-stderr", exec.stderr.trim())

            val read = manager.readSession(request("shell.session_read", start.sessionId), PrivilegedBackend.ROOT)
            assertTrue(read.success)
            assertTrue(read.transcript.contains("hello-stdout"))
            assertTrue(read.transcript.contains("hello-stderr"))
            assertEquals("true", read.data["alive"])

            val stop = manager.stopSession(request("shell.session_stop", start.sessionId), PrivilegedBackend.ROOT)
            assertTrue(stop.success)
            assertEquals("false", stop.data["alive"])

            val deadRead = manager.readSession(request("shell.session_read", start.sessionId), PrivilegedBackend.ROOT)
            assertFalse(deadRead.success)
            assertEquals("session_not_found", deadRead.code)
        } finally { manager.shutdown() }
    }

    @Test fun stoppingSessionWhileCommandRunningNotifiesConsumerWithSessionNotFound() = runBlocking {
        val manager = sessions()
        try {
            val start = manager.startSession(request("shell.session_start"), PrivilegedBackend.ROOT)
            val marker = File(temporary.root, "started_stop_test")
            val posixMarker = PlatformProcessTestHelper.toPosixPath(marker.absolutePath)
            val execDeferred = async(Dispatchers.IO) {
                manager.execSession(request("shell.session_exec", start.sessionId,
                    "printf started > '$posixMarker'; while :; do :; done"), PrivilegedBackend.ROOT)
            }
            withTimeout(5000) { while (!marker.exists()) delay(10) }
            val stop = manager.stopSession(request("shell.session_stop", start.sessionId), PrivilegedBackend.ROOT)
            assertTrue(stop.success)
            val execResult = withTimeout(3000) { execDeferred.await() }
            assertFalse(execResult.success)
            assertEquals("session_not_found", execResult.code)
            assertTrue(execResult.message.contains("stopped") || execResult.message.contains("closed") || execResult.message.contains("exited"))
        } finally { manager.shutdown() }
    }
}
