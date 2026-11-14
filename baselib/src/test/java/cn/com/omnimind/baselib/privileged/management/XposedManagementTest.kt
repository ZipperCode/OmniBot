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
                    mockResult(stdout = "{\"success\": true, \"data\": []}\n")
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
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
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
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
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
    @Test
    fun vectorCliRejectsDefaultAsciiTableOutput() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"

        val asciiTable = """
            +---------------------------+---------+
            | PACKAGE                   | STATUS  |
            +---------------------------+---------+
            | $modulePkg                | enabled |
            +---------------------------+---------+
        """.trimIndent() + "\n"

        val result = controller.execute(
            PrivilegedRequest("verify-ascii", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = asciiTable)
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("config_unverified", result.data["status"])
        assertEquals("false", result.data["configVerified"])
        assertEquals("false", result.data["hookVerified"])
    }

    @Test
    fun verifyStatusRejectsWrongUserScope() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"

        val result = controller.execute(
            PrivilegedRequest("verify-wrong-user", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg, "user_id" to "0")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 10}]}\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("config_unverified", result.data["status"])
        assertEquals("false", result.data["configVerified"])
    }

    @Test
    fun verifyStatusRejectsSimilarPrefixPackage() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"

        // 场景 A: modules ls 中包名是前缀匹配 (例如 cn.com.omnimind.hooksample_fake)
        val prefixModuleResult = controller.execute(
            PrivilegedRequest("verify-prefix-module", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"${modulePkg}_fake\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
                else -> mockResult()
            }
        }

        assertEquals("false", prefixModuleResult.data["configVerified"])

        // 场景 B: 日志中包名是前缀匹配 (例如 cn.com.omnimind.hooksample_extra 或 com.android.settings_fake)
        val prefixLogResult = controller.execute(
            PrivilegedRequest("verify-prefix-log", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
                c.contains("pidof") -> mockResult(stdout = "7890\n")
                c.contains("logcat") -> mockResult(
                    stdout = "11-06 12:00:00.123  7890  7890 I LSPosed-Bridge: Loaded ${modulePkg}_extra in process ${targetPkg}_fake (7890)\n")
                else -> mockResult()
            }
        }

        assertEquals("true", prefixLogResult.data["configVerified"])
        assertEquals("false", prefixLogResult.data["hookVerified"])
    }

    @Test
    fun verifyStatusRejectsInactiveOrOldPid() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"

        val result = controller.execute(
            PrivilegedRequest("verify-inactive-pid", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
                c.contains("pidof") -> mockResult(stdout = "9999\n") // 当前存活 PID 是 9999
                c.contains("logcat") -> mockResult(
                    stdout = "11-06 12:00:00.123  7890  7890 I LSPosed-Bridge: Loaded $modulePkg in process $targetPkg (7890)\n") // 日志是旧 PID 7890
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("true", result.data["configVerified"])
        assertEquals("false", result.data["hookVerified"])
        assertEquals("config_verified_hook_unverified", result.data["status"])
    }

    @Test
    fun verifyStatusRejectsFakeTagMarker() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"

        val result = controller.execute(
            PrivilegedRequest("verify-fake-tag", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
                c.contains("pidof") -> mockResult(stdout = "7890\n")
                c.contains("logcat") -> mockResult(
                    stdout = "11-06 12:00:00.123  7890  7890 I FakeFramework: Loaded $modulePkg in process $targetPkg (7890)\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("true", result.data["configVerified"])
        assertEquals("false", result.data["hookVerified"])
        assertEquals("config_verified_hook_unverified", result.data["status"])
    }

    @Test
    fun verifyStatusSucceedsWithValidEnvelopeAndThreadtimeLogcat() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modulePkg = "cn.com.omnimind.hooksample"
        val targetPkg = "com.android.settings"

        val result = controller.execute(
            PrivilegedRequest("verify-vector-success", PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS,
                arguments = mapOf("module_pkg_name" to modulePkg, "target_package" to targetPkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -x") -> mockResult()
                c.contains("modules ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"PACKAGE\": \"$modulePkg\", \"STATUS\": \"enabled\"}]}\n")
                c.contains("scope ls") -> mockResult(stdout = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"$targetPkg\", \"USER_ID\": 0}]}\n")
                c.contains("pidof") -> mockResult(stdout = "7890 7891\n")
                c.contains("logcat") -> mockResult(
                    stdout = "11-06 12:00:00.123  7890  7890 I Vector: Loading module $modulePkg for $targetPkg (PID 7890)\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("hook_active_verified", result.data["status"])
        assertEquals("true", result.data["configVerified"])
        assertEquals("true", result.data["hookVerified"])
        assertEquals(JsonPrimitive(true), result.observation?.get("hookVerified"))
        assertEquals("7890", result.data["evidencePid"])
        assertEquals("Vector", result.data["evidenceTag"])
        assertEquals("11-06 12:00:00.123", result.data["evidenceObservedAt"])
    }

    @Test
    fun xposedStatusParserPureFunctionsVerifyEnvelopeAndTokenRules() {
        // 1. parseModulesLs
        assertNull(XposedStatusParser.parseModulesLs(""))
        assertNull(XposedStatusParser.parseModulesLs("   "))
        assertNull(XposedStatusParser.parseModulesLs("+---+---+\n| a | b |\n+---+---+"))
        assertNull(XposedStatusParser.parseModulesLs("[{\"PACKAGE\": \"pkg\", \"STATUS\": \"enabled\"}]")) // 拒绝裸数组
        assertNull(XposedStatusParser.parseModulesLs("{\"success\": false, \"error\": \"failed\"}"))

        val validModulesJson = "{\"success\": true, \"data\": [{\"PACKAGE\": \"com.mod\", \"STATUS\": \"enabled\"}]}"
        val parsedModules = XposedStatusParser.parseModulesLs(validModulesJson)
        assertNotNull(parsedModules)
        assertEquals(1, parsedModules?.size)
        assertEquals("com.mod", parsedModules?.first()?.packageName)
        assertTrue(parsedModules?.first()?.enabled == true)

        // 2. parseScopeLs
        assertNull(XposedStatusParser.parseScopeLs("+---+---+\n| a | b |\n+---+---+"))
        assertNull(XposedStatusParser.parseScopeLs("[{\"APP_PACKAGE\": \"pkg\", \"USER_ID\": 0}]")) // 拒绝裸数组
        val validScopeJson = "{\"success\": true, \"data\": [{\"APP_PACKAGE\": \"com.target\", \"USER_ID\": 0}]}"
        val parsedScope = XposedStatusParser.parseScopeLs(validScopeJson)
        assertNotNull(parsedScope)
        assertEquals(1, parsedScope?.size)
        assertEquals("com.target", parsedScope?.first()?.appPackage)
        assertEquals(0, parsedScope?.first()?.userId)

        // 3. containsPackageToken
        assertTrue(XposedStatusParser.containsPackageToken("Loaded com.mod in target", "com.mod"))
        assertTrue(XposedStatusParser.containsPackageToken("Loaded (com.mod) target", "com.mod"))
        assertFalse(XposedStatusParser.containsPackageToken("Loaded com.mod_extra in target", "com.mod"))
        assertFalse(XposedStatusParser.containsPackageToken("Loaded prefix.com.mod in target", "com.mod"))

        // 4. findFrameworkEvidence
        val logLine = "11-06 12:00:00.123  1234  1234 I LSPosed-Bridge: Loaded com.mod in process com.target (1234)\n"
        val evidence = XposedStatusParser.findFrameworkEvidence(
            logcatOutput = logLine,
            modulePkgName = "com.mod",
            targetPackage = "com.target",
            activePids = setOf("1234")
        )
        assertNotNull(evidence)
        assertEquals("1234", evidence?.pid)
        assertEquals("LSPosed-Bridge", evidence?.tag)
        assertEquals("11-06 12:00:00.123", evidence?.observedAt)

        // 错 PID
        assertNull(XposedStatusParser.findFrameworkEvidence(logLine, "com.mod", "com.target", setOf("9999")))
        // 伪造 Tag
        val fakeTagLine = "11-06 12:00:00.123  1234  1234 I FakeBridge: Loaded com.mod in process com.target (1234)\n"
        assertNull(XposedStatusParser.findFrameworkEvidence(fakeTagLine, "com.mod", "com.target", setOf("1234")))
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
