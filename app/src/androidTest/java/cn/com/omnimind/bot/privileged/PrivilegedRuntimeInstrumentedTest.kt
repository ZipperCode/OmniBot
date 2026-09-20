package cn.com.omnimind.bot.privileged

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.com.omnimind.baselib.privileged.PrivilegedAuthority
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.baselib.privileged.PrivilegedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PrivilegedRuntimeInstrumentedTest {

    @Test
    fun test01ActualAppUidRootAuthorizationAndDirectTransport() = runBlocking {
        val status = manager.authorize()
        assertTrue("SU authorization failed: ${status.code}", status.granted)
        assertEquals(PrivilegedSource.SU, status.selected)
        assertEquals(PrivilegedAuthority.ROOT, status.authority)
        assertEquals(0, status.uid)

        val result = manager.executeRawShell("printf 'OMNIBOT_ROOT_OK\\n'; id -u", confirmed = true)
        assertTrue("Direct native transport failed: ${result.code}", result.success)
        assertEquals(PrivilegedAuthority.ROOT, result.authority)
        assertEquals(PrivilegedSource.SU, result.source)
        assertEquals(listOf("OMNIBOT_ROOT_OK", "0"), result.stdout.lineSequence().toList())
    }

    @Test
    fun test02PersistentSessionBoundsTimeoutAndLateCompletion() = runBlocking {
        ensureRoot()
        val sessionId = "androidtest-${System.currentTimeMillis()}"
        val start = manager.startPrivilegedSession(sessionId = sessionId, confirmed = true)
        assertTrue("Session start failed: ${start.code}", start.success)
        try {
            val basic = manager.execPrivilegedSession(sessionId, "printf 'SESSION_OK\\n'; id -u", confirmed = true)
            assertTrue("Session exec failed: ${basic.code}", basic.success)
            assertEquals(listOf("SESSION_OK", "0"), basic.stdout.lineSequence().toList())

            val huge = manager.execPrivilegedSession(
                sessionId,
                "dd if=/dev/zero bs=40000 count=1 2>/dev/null | tr '\\000' x; printf '\\n'",
                confirmed = true,
            )
            assertTrue("Large output failed: ${huge.code}", huge.success)
            assertTrue("stdout exceeded 16k: ${huge.stdout.length}", huge.stdout.length <= 16_000)
            assertTrue(huge.stdout.isNotEmpty() && huge.stdout.all { it == 'x' })

            val timeout = manager.execPrivilegedSession(
                sessionId,
                "sleep 8; printf 'LATE_DONE\\n'",
                timeoutSeconds = 5,
                confirmed = true,
            )
            assertFalse(timeout.success)
            assertEquals("timeout", timeout.code)
            assertEquals("true", timeout.data["alive"])
            assertEquals("true", timeout.data["running"])

            val busy = manager.execPrivilegedSession(sessionId, "printf 'TOO_EARLY\\n'", confirmed = true)
            assertEquals("session_busy", busy.code)
            delay(4_000)
            val read = manager.readPrivilegedSession(sessionId)
            assertTrue(read.success)
            assertEquals("false", read.data["running"])
            assertTrue(read.transcript.contains("LATE_DONE"))

            val next = manager.execPrivilegedSession(sessionId, "printf 'AFTER_LATE\\n'", confirmed = true)
            assertTrue("Session did not recover: ${next.code}", next.success)
            assertEquals("AFTER_LATE", next.stdout)
        } finally {
            val stopped = manager.stopPrivilegedSession(sessionId)
            assertTrue("Session cleanup failed: ${stopped.code}/${stopped.data}", stopped.success)
            assertEquals("true", stopped.data["stopped"])
        }
    }

    @Test
    fun test03CancellationCleansDirectProcessAndTransportRemainsUsable() = runBlocking {
        ensureRoot()
        val running = async {
            manager.executeRawShell("sleep 8", timeoutSeconds = 10, confirmed = true)
        }
        delay(1_000)
        running.cancel()
        try {
            running.await()
            throw AssertionError("Cancellation was not propagated")
        } catch (_: CancellationException) {
            // 取消只承诺清理直接 Process，不声称能终止未知派生进程树。
        }
        val probe = manager.executeRawShell("printf 'AFTER_CANCEL\\n'", confirmed = true)
        assertTrue("Transport unusable after cancellation: ${probe.code}", probe.success)
        assertEquals("AFTER_CANCEL", probe.stdout)
    }

    @Test
    fun test04TypedSettingRestoresBaseline() = runBlocking {
        ensureRoot()
        val baseline = getScreenOffTimeout()
        val temporary = if (baseline == "123456") "123457" else "123456"
        try {
            putScreenOffTimeout(temporary)
            assertEquals(temporary, getScreenOffTimeout())
        } finally {
            putScreenOffTimeout(baseline)
        }
        assertEquals(baseline, getScreenOffTimeout())
    }

    @Test
    fun test05SourceSwitchClosesOldSessionThenReauthorizesRoot() = runBlocking {
        ensureRoot()
        val sessionId = "switch-${System.currentTimeMillis()}"
        assertTrue(manager.startPrivilegedSession(sessionId = sessionId, confirmed = true).success)

        val none = manager.setSource("none")
        assertEquals(PrivilegedSource.NONE, none.selected)
        val old = manager.readPrivilegedSession(sessionId)
        assertFalse(old.success)
        assertEquals("permission_required", old.code)

        manager.setSource("su")
        val restored = manager.authorize()
        assertTrue("SU reauthorization failed: ${restored.code}", restored.granted)
        assertEquals(0, restored.uid)
        val staleStop = manager.stopPrivilegedSession(sessionId)
        assertFalse(staleStop.success)
        assertEquals("session_not_found", staleStop.code)
    }

    private suspend fun ensureRoot() {
        if (manager.selectedSource() != PrivilegedSource.SU) manager.setSource("su")
        val status = manager.getStatus().takeIf { it.granted } ?: manager.authorize()
        assertTrue("Root authorization unavailable: ${status.code}", status.granted)
        assertEquals(PrivilegedAuthority.ROOT, status.authority)
        assertEquals(0, status.uid)
    }

    private suspend fun getScreenOffTimeout(): String {
        val result = manager.executeAgentAction(
            "settings_control.get",
            mapOf("namespace" to "system", "key" to "screen_off_timeout"),
        )
        assertTrue("Failed to read screen_off_timeout: ${result.code}", result.success)
        return result.data.getValue("value")
    }

    private suspend fun putScreenOffTimeout(value: String) {
        val result = manager.executeAgentAction(
            "settings_control.put",
            mapOf(
                "namespace" to "system",
                "key" to "screen_off_timeout",
                "value" to value,
                "confirmed" to "true",
            ),
        )
        assertTrue("Failed to write screen_off_timeout=$value: ${result.code}", result.success)
    }

    companion object {
        private lateinit var manager: PrivilegedCapabilityManager

        @JvmStatic
        @BeforeClass
        fun prepare() {
            runBlocking {
                val context = ApplicationProvider.getApplicationContext<Context>()
                manager = PrivilegedCapabilityManager.get(context)
                if (manager.selectedSource() != PrivilegedSource.SU) manager.setSource("su")
            }
        }

        @JvmStatic
        @AfterClass
        fun leaveAuthorizedSuSelected() {
            runBlocking {
                if (manager.selectedSource() != PrivilegedSource.SU) manager.setSource("su")
                manager.authorize()
            }
        }
    }
}
