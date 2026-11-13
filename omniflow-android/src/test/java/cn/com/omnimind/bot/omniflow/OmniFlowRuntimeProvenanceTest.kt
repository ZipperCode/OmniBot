package cn.com.omnimind.bot.omniflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OmniFlowRuntimeProvenanceTest {


    @Test
    fun `client close clears session runtime provenance snapshot`() = runBlocking {
        val process = FakeProcess(
            stdout = """{"jsonrpc":"2.0","id":"req-1","result":{"protocolVersion":"2025-11-25"}}""" + "\n",
        )
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> process },
            bridgeCommand = "python3 -m omniflow.bridge",
            requestIdFactory = { "req-1" },
        )
        client.initialize()
        val prov = mapOf("schema_version" to "omniflow.runtime-provenance.v1", "source" to "packaged")
        client.attachSessionProvenance(prov)
        assertEquals(prov, client.sessionProvenanceForTest())

        client.close()
        assertNull(client.sessionProvenanceForTest())
    }

    @Test
    fun `call overwrites untrusted python provenance and strips when session has none`() = runBlocking {
        var idCounter = 1
        val process = FakeProcess(
            stdout = listOf(
                """{"jsonrpc":"2.0","id":"req-1","result":{"success":true,"run_id":"run-123","runtime_provenance":{"fake":true}}}""",
                """{"jsonrpc":"2.0","id":"req-2","result":{"success":true,"run_id":"run-456","runtime_provenance":{"fake":true}}}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> process },
            bridgeCommand = "python3 -m omniflow.bridge",
            requestIdFactory = { "req-${idCounter++}" },
        )
        val uninitResult = client.call("tools/call", mapOf("name" to "run_gui"))
        assertFalse(uninitResult.containsKey("runtime_provenance"))

        val expectedProv = mapOf(
            "schema_version" to "omniflow.runtime-provenance.v1",
            "source" to "packaged",
            "runtime_version" to "2.1.9",
        )
        client.attachSessionProvenance(expectedProv)
        val trustedResult = client.call("tools/call", mapOf("name" to "run_gui"))
        assertEquals(expectedProv, trustedResult["runtime_provenance"])
        assertEquals("run-456", trustedResult["run_id"])
        client.close()
    }

    private class FakeProcess(
        stdout: String = "",
        private val stdoutStream: InputStream = ByteArrayInputStream(stdout.toByteArray()),
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stderrStream = ByteArrayInputStream(ByteArray(0))
        var destroyed: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
        override fun isAlive(): Boolean = !destroyed
    }
}
