package cn.com.omnimind.baselib.privileged

import cn.com.omnimind.baselib.shizuku.OmnibotPrivilegedUserService
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import java.io.File
import java.util.UUID

/**
 * 验证真实执行身份 UID 语义：
 * 1. PrivilegedProcessResult 仅在 Root marker 确证时记录 uid = 0，拒绝/未执行/非 Root Runner 保持 null；
 * 2. CommandExecutor.withOutputMetadata 传递事实 uid，failure 不按 backend enum 假回填；
 * 3. PrivilegedShellSessionManager 持有真实 id -u 读回的已验证 UID，session_not_found/未执行保持 null；
 * 4. PrivilegedModels.toMap 直接投影 uid，不做 enum 兜底；
 * 5. OmnibotPrivilegedUserService 按真实 Process.myUid 回填实际身份且不伪造 Root。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UidEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun request(action: String = "shell.exec", command: String? = "id", confirmed: Boolean = true) =
        PrivilegedRequest(
            requestId = UUID.randomUUID().toString(),
            action = action,
            arguments = if (confirmed) mapOf("confirmed" to "true") else emptyMap(),
            command = command,
        )

    @Test
    fun toMapDirectlyUsesUidWithoutBackendEnumFallback() {
        val rootWithoutUid = PrivilegedResult(
            requestId = "r1",
            action = "shell.exec",
            success = false,
            code = "permission_denied",
            message = "Denied",
            backend = PrivilegedBackend.ROOT,
            uid = null,
        )
        assertNull(rootWithoutUid.toMap()["uid"])

        val adbWithoutUid = PrivilegedResult(
            requestId = "r2",
            action = "shell.exec",
            success = false,
            code = "timeout",
            message = "Timeout",
            backend = PrivilegedBackend.ADB,
            uid = null,
        )
        assertNull(adbWithoutUid.toMap()["uid"])

        val rootWithActualZero = PrivilegedResult(
            requestId = "r3",
            action = "shell.exec",
            success = true,
            code = "ok",
            message = "Success",
            backend = PrivilegedBackend.ROOT,
            uid = 0,
        )
        assertEquals(0, rootWithActualZero.toMap()["uid"])

        val adbWithActualAdb = PrivilegedResult(
            requestId = "r4",
            action = "shell.exec",
            success = true,
            code = "ok",
            message = "Success",
            backend = PrivilegedBackend.ADB,
            uid = 2000,
        )
        assertEquals(2000, adbWithActualAdb.toMap()["uid"])

        // 真实身份为 2000，即使 backend 标签为 ROOT，toMap 也必须是实际执行身份 2000，不伪造 0
        val nonRootWithRootLabel = PrivilegedResult(
            requestId = "r5",
            action = "shell.exec",
            success = true,
            code = "ok",
            message = "Success",
            backend = PrivilegedBackend.ROOT,
            uid = 2000,
        )
        assertEquals(2000, nonRootWithRootLabel.toMap()["uid"])
    }

    @Test
    fun processRunnerUidIsZeroOnlyWhenRootMarkerIsConfirmed() {
        // 1. Root marker 确证：即使命令自身 exit 7 失败，执行身份仍确证为 Root (0)
        val rootSuccessRunner = PrivilegedProcessRunner(
            root = true,
            leaseDirectory = temporary.newFolder(),
            startProcess = { argv ->
                val marker = Regex("__OMNI_ROOT_[a-zA-Z0-9-]+__").find(argv.last())!!.value
                ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", "printf '%s\\n' '$marker' >&2; exit 7").start()
            }
        )
        val confirmedResult = rootSuccessRunner.execute("req-root-ok", listOf("id"), 2)
        assertEquals(7, confirmedResult.exitCode)
        assertNull(confirmedResult.errorCode)
        assertEquals(0, confirmedResult.uid)

        // 2. Root marker 未确证（如 su 被拒绝或未输出 marker）: uid 为 null
        val rootDeniedRunner = PrivilegedProcessRunner(
            root = true,
            leaseDirectory = temporary.newFolder(),
            startProcess = { ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", "exit 1").start() }
        )
        val deniedResult = rootDeniedRunner.execute("req-root-denied", listOf("id"), 2)
        assertEquals("root_denied", deniedResult.errorCode)
        assertNull(deniedResult.uid)

        // 3. 非 Root Runner: 身份未知，uid 保持 null
        val normalRunner = PrivilegedProcessRunner(
            root = false,
            startProcess = { ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", "printf 'hello'").start() }
        )
        val normalResult = normalRunner.execute("req-normal", listOf("id"), 2)
        assertTrue(normalResult.success)
        assertNull(normalResult.uid)
    }

    @Test
    fun commandExecutorFailureDoesNotEnumFillUid() {
        val runner = PrivilegedProcessRunner(startProcess = { error("Must not be executed") })
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT, runner)

        // 未执行：被安全策略拦截
        val policyResult = executor.execute(request(command = "reboot"))
        assertEquals("blocked_by_policy", policyResult.code)
        assertNull(policyResult.uid)

        // 未执行：需要确认但未确认
        val confirmResult = executor.execute(request(command = "id", confirmed = false))
        assertEquals("confirmation_required", confirmResult.code)
        assertNull(confirmResult.uid)

        // 未执行：执行前已被取消
        val cancelReq = request(command = "id")
        executor.cancel(cancelReq.requestId)
        val cancelResult = executor.execute(cancelReq)
        assertEquals("cancelled", cancelResult.code)
        assertNull(cancelResult.uid)

        // 未执行：不支持的操作
        val unsupportedResult = executor.execute(request(action = "unknown.action"))
        assertEquals("unsupported_action", unsupportedResult.code)
        assertNull(unsupportedResult.uid)

        // 未执行：参数无效
        val invalidArgsResult = executor.execute(request(command = ""))
        assertEquals("invalid_arguments", invalidArgsResult.code)
        assertNull(invalidArgsResult.uid)
    }

    @Test
    fun commandExecutorWithOutputMetadataTransfersFactualUid() {
        // 确证 Root 的 runner，命令执行后返回 uid = 0
        val rootRunner = PrivilegedProcessRunner(
            root = true,
            leaseDirectory = temporary.newFolder(),
            startProcess = { argv ->
                val marker = Regex("__OMNI_ROOT_[a-zA-Z0-9-]+__").find(argv.last())!!.value
                ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", "printf '%s\\n' '$marker' >&2; printf 'uid=0'").start()
            }
        )
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT, rootRunner)
        val okResult = executor.execute(request(command = "id"))
        assertTrue(okResult.success)
        assertEquals(0, okResult.uid)

        // Root 被拒绝时，executor 返回 uid = null，绝不按 ROOT enum 回填 0
        val deniedRunner = PrivilegedProcessRunner(
            root = true,
            leaseDirectory = temporary.newFolder(),
            startProcess = { ProcessBuilder(PlatformProcessTestHelper.shellPath, "-c", "exit 1").start() }
        )
        val deniedExecutor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT, deniedRunner)
        val deniedResult = deniedExecutor.execute(request(command = "id"))
        assertEquals("root_denied", deniedResult.code)
        assertNull(deniedResult.uid)
    }

    @Test
    fun shellSessionHoldsVerifiedUidAndSessionNotFoundReturnsNullUid() {
        val bin = temporary.newFolder()
        val envInit = File(bin, "env_init.sh").apply {
            writeText("id() { printf '0\\n'; }\n")
        }
        File(bin, "id").apply {
            writeText("#!/bin/sh\nprintf '0\\n'\n")
            setExecutable(true)
        }
        val posixEnvInit = PlatformProcessTestHelper.toPosixPath(envInit.absolutePath)
        val manager = PrivilegedShellSessionManager(PrivilegedProcessRunner(startProcess = {
            ProcessBuilder(PlatformProcessTestHelper.shellPath).apply {
                environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
                environment()["ENV"] = posixEnvInit
                environment()["BASH_ENV"] = posixEnvInit
            }.start()
        }))

        try {
            // 1. 会话不存在时，session_not_found 的 uid 必须为 null，不可按 backend 假填
            val notFoundResult = manager.execSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_exec", sessionId = "non-existent-session", command = "echo 1"),
                PrivilegedBackend.ROOT
            )
            assertEquals("session_not_found", notFoundResult.code)
            assertNull(notFoundResult.uid)

            val notFoundRead = manager.readSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_read", sessionId = "non-existent-session"),
                PrivilegedBackend.ROOT
            )
            assertEquals("session_not_found", notFoundRead.code)
            assertNull(notFoundRead.uid)

            val notFoundStop = manager.stopSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_stop", sessionId = "non-existent-session"),
                PrivilegedBackend.ROOT
            )
            assertEquals("session_not_found", notFoundStop.code)
            assertNull(notFoundStop.uid)

            // 2. 真实启动会话：执行 id -u 读回并持有已验证的真实 UID (0)
            val startResult = manager.startSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_start"),
                PrivilegedBackend.ROOT
            )
            assertTrue(startResult.message, startResult.success)
            assertEquals(0, startResult.uid)

            val sessionId = startResult.sessionId!!

            // 3. 执行会话命令：持有已验证的真实 UID
            val execResult = manager.execSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_exec", sessionId = sessionId, command = "echo verified"),
                PrivilegedBackend.ROOT
            )
            assertTrue(execResult.message, execResult.success)
            assertEquals(0, execResult.uid)

            // 4. 读取会话输出：持有已验证的真实 UID
            val readResult = manager.readSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_read", sessionId = sessionId),
                PrivilegedBackend.ROOT
            )
            assertTrue(readResult.message, readResult.success)
            assertEquals(0, readResult.uid)

            // 5. 停止会话：持有已验证的真实 UID
            val stopResult = manager.stopSession(
                PrivilegedRequest(UUID.randomUUID().toString(), "shell.session_stop", sessionId = sessionId),
                PrivilegedBackend.ROOT
            )
            assertTrue(stopResult.message, stopResult.success)
            assertEquals(0, stopResult.uid)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun privilegedUserServiceBackfillsActualProcessUidWithoutFakingRoot() {
        val originalUid = android.os.Process.myUid()
        try {
            // 真实 Shizuku ADB 进程身份环境 (UID 2000): 未执行/策略拦截的请求保持 uid = null
            ShadowProcess.setUid(2000)
            val adbService = OmnibotPrivilegedUserService()

            // 1. 未执行的请求（如确认缺失、策略拦截）返回 uid = null
            val unconfirmedReq = request(action = "shell.exec", command = "id", confirmed = false)
            val unconfirmedJson = adbService.execute(json.encodeToString(PrivilegedRequest.serializer(), unconfirmedReq))
            val unconfirmedResult = json.decodeFromString(PrivilegedResult.serializer(), unconfirmedJson)
            assertEquals("confirmation_required", unconfirmedResult.code)
            assertNull(unconfirmedResult.uid)

            // 2. 策略拦截命令返回 uid = null
            val blockedReq = request(action = "shell.exec", command = "reboot", confirmed = true)
            val blockedJson = adbService.execute(json.encodeToString(PrivilegedRequest.serializer(), blockedReq))
            val blockedResult = json.decodeFromString(PrivilegedResult.serializer(), blockedJson)
            assertEquals("blocked_by_policy", blockedResult.code)
            assertNull(blockedResult.uid)

            // 3. 真实 Shizuku Root 进程身份环境 (UID 0): 即使真实身份为 0，未执行的请求同样不能回填 0，必须保持 uid = null
            ShadowProcess.setUid(0)
            val rootService = OmnibotPrivilegedUserService()

            val rootUnconfirmedJson = rootService.execute(json.encodeToString(PrivilegedRequest.serializer(), unconfirmedReq))
            val rootUnconfirmedResult = json.decodeFromString(PrivilegedResult.serializer(), rootUnconfirmedJson)
            assertEquals("confirmation_required", rootUnconfirmedResult.code)
            assertNull(rootUnconfirmedResult.uid)
        } finally {
            ShadowProcess.setUid(originalUid)
        }
    }
}
