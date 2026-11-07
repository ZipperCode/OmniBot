package cn.com.omnimind.baselib.privileged

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedCommandExecutorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun request(command: String) = PrivilegedRequest(UUID.randomUUID().toString(), "shell.exec",
        mapOf("confirmed" to "true"), command = command)

    @Test fun cancellationBeforeDispatchCannotAdmitALateBinderRequest() {
        val runner = PrivilegedProcessRunner(startProcess = { error("A cancelled request must not start") })
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ADB, PrivilegedProvider.SHIZUKU, runner)
        val request = request("id")
        executor.cancel(request.requestId)
        assertEquals("cancelled", executor.execute(request).code)
    }

    @Test fun staleAuthorizationCannotStartEvenIfTheProviderIsAvailableAgain() {
        val runner = PrivilegedProcessRunner(admission = { true }, startProcess = { error("Stale authorization") })
        val result = runner.execute("request", listOf("id"), 1, requestAdmission = { false })
        assertEquals("privileged_access_changed", result.errorCode)
    }

    @Test fun blockedCommandsAndUnconfirmedWritesDoNotReachTheProcessRunner() {
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(startProcess = { error("Must not execute") }))
        assertEquals("blocked_by_policy", executor.execute(request("reboot")).code)
        assertEquals("confirmation_required", executor.execute(request("id").copy(arguments = emptyMap())).code)
    }

    @Test fun publicOutputLimitIsReportedEvenBelowTheDrainBufferLimit() {
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(startProcess = { args -> ProcessBuilder(listOf(PlatformProcessTestHelper.shellPath) + args.drop(1)).start() }))
        try {
            val result = executor.execute(request("i=0; while [ \"\$i\" -lt 2000 ]; do printf 0123456789; i=\$((i+1)); done"))
            assertTrue(result.message, result.success)
            assertEquals(16000, result.stdout.length)
            assertEquals("true", result.data["truncated"])
        } finally { executor.shutdown() }
    }

    @Test fun nonzeroHelpOutputRetainsTruncationAndFailureDetails() {
        for (outputSize in listOf(20_000, 80_000)) {
            for (stderr in listOf(false, true)) {
                val command = "cmd uimode help"
                val result = executeFixture(
                    PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_DEVICE_COMMAND, command = command),
                    outputSize, stderr, 255,
                )
                assertFalse(result.success)
                assertEquals("command_failed", result.code)
                assertEquals(255, result.exitCode)
                assertEquals(PrivilegedProvider.ROOT, result.provider)
                assertEquals(command, result.command)
                assertEquals("true", result.data["truncated"])
                val help = if (stderr) result.stderr else result.stdout
                assertEquals(16000, help.length)
                assertTrue(help.startsWith("UI mode manager commands:\n"))
                assertTrue((if (stderr) result.stdout else result.stderr).isEmpty())
            }
        }
    }

    @Test fun typedFailuresPreserveTheSameOutputMetadataAsSuccesses() {
        val request = PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_DIAGNOSTICS_DUMPSYS,
            mapOf("service" to "power"))
        for (exitCode in listOf(0, 255)) {
            val result = executeFixture(request, 20_000, stderr = true, exitCode = exitCode)
            assertEquals(exitCode == 0, result.success)
            assertEquals(if (exitCode == 0) "ok" else "command_failed", result.code)
            assertEquals(exitCode, result.exitCode)
            assertEquals("true", result.data["truncated"])
            assertEquals(16000, result.stderr.length)
            assertEquals(PrivilegedProvider.ROOT, result.provider)
        }
    }

    @Test fun completeNonzeroHelpIsNotMarkedAsTruncated() {
        val result = executeFixture(
            PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_DEVICE_COMMAND, command = "cmd uimode help"),
            outputSize = 10, stderr = false, exitCode = 255,
        )
        assertEquals("command_failed", result.code)
        assertEquals(255, result.exitCode)
        assertEquals("false", result.data["truncated"])
        assertTrue(result.stdout.endsWith("  night [yes|no]\n"))
    }

    @Test fun fullPowerAndSystemModificationFlagsAllowControlledRebootWhileBlockingHostForceStop() {
        var executedCommand: String? = null
        val runner = PrivilegedProcessRunner(startProcess = { args ->
            executedCommand = args.last()
            ProcessBuilder(listOf(PlatformProcessTestHelper.shellPath) + args.drop(1)).start()
        })
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT, runner)

        // Normal mode: reboot is blocked by static policy
        val defaultResult = executor.execute(request("reboot"))
        assertEquals("blocked_by_policy", defaultResult.code)
        assertTrue(defaultResult.blockedByPolicy)

        // allowSystemModifications=true: reboot is admitted to runner
        val sysModResult = executor.execute(request("echo reboot-ok").copy(command = "reboot", allowSystemModifications = true))
        assertNotEquals("blocked_by_policy", sysModResult.code)

        // isFullPower=true: reboot is admitted and confirmation is waived
        val fullPowerResult = executor.execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
                command = "reboot",
                arguments = emptyMap(),
                isFullPower = true,
            )
        )
        assertNotEquals("blocked_by_policy", fullPowerResult.code)
        assertNotEquals("confirmation_required", fullPowerResult.code)

        // Host protection rule is STILL blocked even under full power
        val hostStopResult = executor.execute(
            PrivilegedRequest(
                requestId = UUID.randomUUID().toString(),
                action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
                command = "am force-stop cn.com.omnimind.bot",
                arguments = emptyMap(),
                isFullPower = true,
                allowSystemModifications = true,
            )
        )
        assertEquals("blocked_by_policy", hostStopResult.code)
        assertTrue(hostStopResult.blockedByPolicy)
    }

    @Test fun rootBackendReturnsUidZeroInResultAndMap() {
        val lease = temporary.newFolder()
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(root = true, leaseDirectory = lease, startProcess = { args ->
                val marker = Regex("__OMNI_ROOT_[a-zA-Z0-9-]+__").find(args.last())?.value
                val script = if (marker != null) "printf '%s\\n' '$marker' >&2; echo ok" else "echo ok"
                ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", script).start()
            }))
        val result = executor.execute(request("echo ok"))
        assertEquals(0, result.uid)
        assertEquals(0, result.toMap()["uid"])
    }
    private fun executeFixture(request: PrivilegedRequest, outputSize: Int, stderr: Boolean, exitCode: Int): PrivilegedResult {
        val script = "{ printf 'UI mode manager commands:\\n'; " +
            "i=0; while [ \"\$i\" -lt ${outputSize / 10} ]; do printf 0123456789; i=\$((i+1)); done; " +
            "printf '\\n  night [yes|no]\\n'; }" + (if (stderr) " >&2" else "") + "; exit $exitCode"
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT,
            PrivilegedProcessRunner(startProcess = { ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", script).start() }))
        return try { executor.execute(request) } finally { executor.shutdown() }
    }
}
