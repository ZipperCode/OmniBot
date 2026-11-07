package cn.com.omnimind.baselib.privileged.management

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedBackend
import cn.com.omnimind.baselib.privileged.PrivilegedRequest
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XposedManagementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun probeReturnsUnsupportedWhenFrameworkDatabaseIsMissing() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest("probe-1", PrivilegedManagementActions.ACTION_XPOSED_PROBE)

        val result = controller.execute(request, PrivilegedBackend.ROOT, { true }) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult(success = false, code = "no", exitCode = 1)
                c.contains("test -f") -> mockResult(success = false, code = "no", exitCode = 1)
                c.contains("test -d") -> mockResult(success = false, code = "no", exitCode = 1)
                c.contains("which sqlite3") -> mockResult(success = false, code = "no", exitCode = 1)
                else -> mockResult(success = false, code = "no", exitCode = 1)
            }
        }

        assertFalse("Must not claim success when framework database does not exist", result.success)
        assertEquals("unsupported_action", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("supported"))
    }

    @Test
    fun vectorCliRejectsMutationWhenCliBinaryMissing() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest("enable-fail", PrivilegedManagementActions.ACTION_XPOSED_MODULE_ENABLE,
            arguments = mapOf("module_pkg_name" to "com.example.mod"))

        // CLI 不存在，必须拒绝直接写 SQL
        val result = controller.execute(request, PrivilegedBackend.ROOT, { true }) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult(success = false, code = "no", exitCode = 1)
                else -> mockResult()
            }
        }

        assertFalse(result.success)
        assertEquals("unsupported_action", result.code)
    }

    @Test
    fun vectorCliDetectsFailedArrayFromDaemonResponse() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val cliPath = "/data/adb/lspd/cli"
        val modPkg = "com.example.mod"

        val result = controller.execute(
            PrivilegedRequest("enable-failed-arr", PrivilegedManagementActions.ACTION_XPOSED_MODULE_ENABLE,
                arguments = mapOf("module_pkg_name" to modPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules enable") -> {
                    // 模拟 Vector CLI 命中 staticScope 或依赖缺失，返回 Failed 列表
                    mockResult(stdout = "{\"Enabled\": [], \"Failed\": [\"$modPkg\"]}\n")
                }
                c.contains("modules ls") -> {
                    mockResult(stdout = "[]\n")
                }
                else -> mockResult()
            }
        }

        assertFalse("Must not declare success when CLI reports module in Failed array", result.success)
        assertEquals("module_enable_failed", result.code)
    }

    @Test
    fun verifyStatusRequiresTargetProcessPidAndFrameworkBindingNotArbitraryCallerLog() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"
        val cliPath = "/data/adb/lspd/cli"

        // 场景 A: 模块配置已启用，但目标应用未运行 (pidof 为空)
        val deadProcResult = controller.execute(
            PrivilegedRequest("verify-dead", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "[{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]\n")
                c.contains("scope ls") -> mockResult(stdout = "[{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]\n")
                c.contains("pidof") -> mockResult(success = false, code = "empty", stdout = "\n")
                else -> mockResult()
            }
        }

        assertTrue(deadProcResult.success)
        assertEquals("config_verified_hook_unverified", deadProcResult.data["status"])
        assertEquals("true", deadProcResult.data["configVerified"])
        assertEquals("false", deadProcResult.data["hookVerified"])

        // 场景 B: 目标进程运行中 (PID 7890)，logcat 精确匹配框架注入记录
        val activeHookResult = controller.execute(
            PrivilegedRequest("verify-active", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "[{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]\n")
                c.contains("scope ls") -> mockResult(stdout = "[{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]\n")
                c.contains("pidof") -> mockResult(stdout = "7890\n")
                c.contains("logcat") -> mockResult(
                    stdout = "11-06 12:00:00.123  7890  7890 I LSPosed-Bridge: Loaded $modulePkg in process $targetPkg (7890)\n")
                else -> mockResult()
            }
        }

        assertTrue(activeHookResult.success)
        assertEquals("hook_active_verified", activeHookResult.data["status"])
        assertEquals("true", activeHookResult.data["configVerified"])
        assertEquals("true", activeHookResult.data["hookVerified"])
        assertEquals(JsonPrimitive(true), activeHookResult.observation?.get("hookVerified"))
    }

    @Test
    fun targetReloadForSystemTargetRestartsZygoteWithoutTamperingWithRescueParty() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        var softRebootRan = false

        val result = controller.execute(
            PrivilegedRequest("reload-sys", PrivilegedManagementActions.ACTION_XPOSED_TARGET_RELOAD,
                arguments = mapOf("target_package" to "system_server")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            assertFalse("Must not reset rescue boot count or tamper with system recovery protections",
                c.contains("crashrecovery") || c.contains("rescue_boot_count"))
            if (c.contains("setprop ctl.restart zygote")) {
                softRebootRan = true
                mockResult()
            } else {
                mockResult()
            }
        }

        assertTrue(result.success)
        assertTrue("Must dispatch zygote restart for system_server target", softRebootRan)
    }

    private fun mockResult(
        success: Boolean = true,
        code: String = "ok",
        message: String = "",
        stdout: String = "",
        stderr: String = "",
        exitCode: Int? = if (success) 0 else 1,
    ): PrivilegedResult = PrivilegedResult(
        requestId = "test-req",
        action = "device_control.command",
        success = success,
        code = code,
        message = message,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
    )
}
