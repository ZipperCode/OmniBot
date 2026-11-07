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
class ApplicationManagementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun packageInstallVerifiesPackagePathAndDoesNotRelySolelyOnCommandExit() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val apkPath = "/data/local/tmp/app-release.apk"
        val pkg = "com.example.module"

        // 场景 1: pm install 输出了 Success，但后续 pm path 查不到该包（例如安装到异构空间或回滚）
        val unconfirmedResult = controller.execute(
            PrivilegedRequest("install-1", PrivilegedManagementActions.ACTION_PACKAGE_INSTALL,
                arguments = mapOf("apk_path" to apkPath, "package_name" to pkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") -> mockResult(stdout = "1048576\n")
                c.contains("pm install") -> mockResult(stdout = "Success\n")
                c.contains("pm path") -> mockResult(success = false, code = "package_not_found", stdout = "")
                else -> mockResult()
            }
        }
        assertFalse("Cannot report success if package cannot be observed after installation", unconfirmedResult.success)
        assertEquals("package_verification_unconfirmed", unconfirmedResult.code)

        // 场景 2: pm install 成功且 pm path 确认包已存在
        val confirmedResult = controller.execute(
            PrivilegedRequest("install-2", PrivilegedManagementActions.ACTION_PACKAGE_INSTALL,
                arguments = mapOf("apk_path" to apkPath, "package_name" to pkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") -> mockResult(stdout = "1048576\n")
                c.contains("pm install") -> mockResult(stdout = "Success\n")
                c.contains("pm path") -> mockResult(stdout = "package:/data/app/$pkg/base.apk\n")
                c.contains("dumpsys package") -> mockResult(stdout = "Package [$pkg]:\n  versionCode=1\n  versionName=1.0\n")
                else -> mockResult()
            }
        }
        assertTrue(confirmedResult.success)
        assertEquals(JsonPrimitive(true), confirmedResult.observation?.get("verified"))
    }

    @Test
    fun invalidPackageNamesWithShellInjectionAreRejectedEarly() = runBlocking {
        var commandRan = false
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest("bad-pkg", PrivilegedManagementActions.ACTION_PACKAGE_UNINSTALL,
            arguments = mapOf("package_name" to "com.example; rm -rf /; #"))

        val result = controller.execute(request, PrivilegedBackend.ROOT, { true }) {
            commandRan = true
            mockResult()
        }
        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
        assertFalse("Command runner must not be reached for malformed package names", commandRan)
    }

    @Test
    fun freezeVerifiesDisabledStateAndFailsIfPackageRemainsEnabled() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val pkg = "com.example.target"

        // 系统执行了 disable-user，但由于系统应用限制，dumpsys 显示仍为 enabled
        val result = controller.execute(
            PrivilegedRequest("freeze-1", PrivilegedManagementActions.ACTION_PACKAGE_FREEZE,
                arguments = mapOf("package_name" to pkg)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("dumpsys package") -> mockResult(stdout = "Package [$pkg] ...\n User 0: installed=true enabled=1\n")
                c.contains("pm disable-user") -> mockResult(stdout = "Package $pkg new state: default\n")
                else -> mockResult()
            }
        }

        assertFalse("Must not declare success if package state did not transition to DISABLED", result.success)
        assertEquals("state_change_unconfirmed", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
    }

    @Test
    fun permissionGrantVerifiesActualRuntimePermissionState() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val pkg = "com.example.target"
        val perm = "android.permission.RECORD_AUDIO"

        var permissionGranted = false

        val result = controller.execute(
            PrivilegedRequest("perm-1", PrivilegedManagementActions.ACTION_PACKAGE_GRANT_VERIFIED,
                arguments = mapOf("package_name" to pkg, "permission" to perm)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("pm grant") -> {
                    permissionGranted = true
                    mockResult()
                }
                c.contains("dumpsys package") -> {
                    val status = if (permissionGranted) "granted=true" else "granted=false"
                    mockResult(stdout = "runtime permissions:\n  $perm: $status, flags=[ ... ]\n")
                }
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
    }

    @Test
    fun processKillConfirmsProcessTerminationViaPidCheck() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        var processAlive = true

        val result = controller.execute(
            PrivilegedRequest("kill-1", PrivilegedManagementActions.ACTION_PROCESS_KILL,
                arguments = mapOf("pid" to "4567", "signal" to "9")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("kill -9") -> {
                    processAlive = false
                    mockResult()
                }
                c.contains("test -d /proc/4567") -> {
                    mockResult(success = processAlive, code = if (processAlive) "ok" else "dead", exitCode = if (processAlive) 0 else 1)
                }
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
    }

    @Test
    fun packageInstallDetectsVersionMismatchWhenInstalledVersionDiffersFromApk() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val apkPath = "/data/local/tmp/app-v2.apk"
        val pkg = "com.example.app"

        // pm install 成功，pm path 存在，但 dumpsys 显示 versionCode 仍为旧版 100，而非预期的 101
        val result = controller.execute(
            PrivilegedRequest("install-ver-mismatch", PrivilegedManagementActions.ACTION_PACKAGE_INSTALL,
                arguments = mapOf("apk_path" to apkPath, "package_name" to pkg, "version_code" to "101")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") -> mockResult(stdout = "1048576\n")
                c.contains("pm install") -> mockResult(stdout = "Success\n")
                c.contains("pm path") -> mockResult(stdout = "package:/data/app/$pkg/base.apk\n")
                c.contains("dumpsys package") -> mockResult(stdout = "Package [$pkg] (a1b2c3d):\n  versionCode=100 minSdk=26 targetSdk=34\n  versionName=1.0.0\n")
                else -> mockResult()
            }
        }
        assertFalse("Must not declare success when installed version does not match expected APK version", result.success)
        assertEquals("version_mismatch", result.code)
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
