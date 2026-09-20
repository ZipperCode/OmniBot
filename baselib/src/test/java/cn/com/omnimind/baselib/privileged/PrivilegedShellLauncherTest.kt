package cn.com.omnimind.baselib.privileged

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivilegedShellLauncherTest {
    @Test fun mapsMeasuredUidToAuthority() {
        assertEquals(PrivilegedAuthority.ROOT, StandardPrivilegedShellLauncher.authorityForUid(0))
        assertEquals(PrivilegedAuthority.ADB, StandardPrivilegedShellLauncher.authorityForUid(2000))
        assertEquals(PrivilegedAuthority.NONE, StandardPrivilegedShellLauncher.authorityForUid(12345))
    }

    @Test fun noneAuthorityHasNoActions() {
        assertEquals(emptyList<String>(), PrivilegedActionPolicy.visibleAgentActions(PrivilegedAuthority.NONE))
    }

    @Test fun probeConsumesMarkerBeforePayloadUsesStdout() {
        val process = FakeProcess("__OMNIBOT_UID__0__OMNIBOT_UID_END__\npayload\n")
        val launched = StandardPrivilegedShellLauncher(processStarter = { process }).launch(PrivilegedSource.SU)

        assertEquals(PrivilegedAuthority.ROOT, launched.authority)
        assertEquals("payload\n", process.inputStream.readBytes().decodeToString())
        assertTrue(process.stdin.toString().contains("__omnibot_uid"))
    }

    @Test fun retriesOnlyPreSpawnIoFailures() {
        var attempts = 0
        val process = FakeProcess("__OMNIBOT_UID__2000__OMNIBOT_UID_END__\n")
        val launched = StandardPrivilegedShellLauncher(processStarter = {
            attempts++
            if (attempts == 1) throw IOException("missing")
            process
        }).launch(PrivilegedSource.SU)

        assertEquals(2, attempts)
        assertEquals(PrivilegedAuthority.ADB, launched.authority)
    }

    @Test fun invalidIdentityDoesNotTryAnotherSuCandidate() {
        var attempts = 0
        val process = FakeProcess("__OMNIBOT_UID__bad__OMNIBOT_UID_END__\n")
        try {
            StandardPrivilegedShellLauncher(processStarter = { attempts++; process }).launch(PrivilegedSource.SU)
            fail("Expected identity failure")
        } catch (_: AuthorityUnexpectedException) {
            assertEquals(1, attempts)
            assertFalse(process.isAlive)
        }
    }

    @Test fun emptyOutputFromStartedSuIsDeniedWithoutFallback() {
        var attempts = 0
        val process = FakeProcess("", initiallyAlive = false)
        try {
            StandardPrivilegedShellLauncher(processStarter = { attempts++; process }).launch(PrivilegedSource.SU)
            fail("Expected su denial")
        } catch (_: SuDeniedException) {
            assertEquals(1, attempts)
        }
    }

    @Test fun interruptedProbePropagatesCancellationAndDestroysProcess() {
        val process = FakeProcess("")
        Thread.currentThread().interrupt()
        try {
            StandardPrivilegedShellLauncher(processStarter = { process }).launch(PrivilegedSource.SU)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(process.isAlive)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun cleanupClosesInputBeforeDestroy() {
        val events = mutableListOf<String>()
        val process = FakeProcess("", events)

        assertTrue(PrivilegedProcessCleanup.closeAndDestroy(process))
        assertEquals(listOf("closeStdin", "destroy"), events)
    }

    private class FakeProcess(
        stdout: String,
        private val events: MutableList<String>? = null,
        initiallyAlive: Boolean = true,
    ) : Process() {
        val stdin = object : ByteArrayOutputStream() {
            override fun close() { events?.add("closeStdin"); super.close() }
        }
        private val stdout = ByteArrayInputStream(stdout.toByteArray())
        private var alive = initiallyAlive
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { alive = false; return 0 }
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() { events?.add("destroy"); alive = false }
        override fun destroyForcibly(): Process { alive = false; return this }
        override fun isAlive(): Boolean = alive
    }
}
