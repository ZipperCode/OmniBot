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
class KernelSuManagementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun malformedModuleIdIsRejectedEarly() = runBlocking {
        var commandRan = false
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest("bad-mod", PrivilegedManagementActions.ACTION_KSU_MODULE_ENABLE,
            arguments = mapOf("module_id" to "../../etc/shadow"))

        val result = controller.execute(request, PrivilegedBackend.ROOT, { true }) {
            commandRan = true
            mockResult()
        }

        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
        assertFalse("Command runner must not execute for invalid module ID", commandRan)
    }

    @Test
    fun moduleListParsesPropertiesAndDetectsDisabledAndRemoveStates() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest("list-mod", PrivilegedManagementActions.ACTION_KSU_MODULE_LIST)

        val result = controller.execute(request, PrivilegedBackend.ROOT, { true }) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -d /data/adb/modules") -> mockResult()
                c.contains("ls -1 /data/adb/modules") -> mockResult(stdout = "zygisk_lsposed\nbad_module\n")
                c.contains("cat /data/adb/modules/zygisk_lsposed/module.prop") -> mockResult(
                    stdout = "id=zygisk_lsposed\nname=LSPosed (Zygisk)\nversion=v1.9.3\nversionCode=7000\nauthor=LSPosed Devs\ndescription=LSPosed Framework\n")
                c.contains("test -e /data/adb/modules/zygisk_lsposed/disable") -> mockResult(success = false, code = "no", exitCode = 1)
                c.contains("test -e /data/adb/modules/zygisk_lsposed/remove") -> mockResult(success = false, code = "no", exitCode = 1)
                c.contains("test -e /data/adb/modules/zygisk_lsposed/.ksu_hot_install") -> mockResult(success = false, code = "no", exitCode = 1)

                c.contains("cat /data/adb/modules/bad_module/module.prop") -> mockResult(
                    stdout = "id=bad_module\nname=Bad Module\nversion=1.0\nversionCode=1\n")
                c.contains("test -e /data/adb/modules/bad_module/disable") -> mockResult(success = true, exitCode = 0)
                c.contains("test -e /data/adb/modules/bad_module/remove") -> mockResult(success = true, exitCode = 0)
                c.contains("test -e /data/adb/modules/bad_module/.ksu_hot_install") -> mockResult(success = false, code = "no", exitCode = 1)

                c.contains("/data/adb/ksud -V") -> mockResult(stdout = "KernelSU ksud 0.9.5\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("2", result.data["count"])
        assertEquals("KernelSU ksud 0.9.5", result.data["ksudVersion"])
    }

    @Test
    fun moduleEnableRemovesDisableFlagAndVerifiesState() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val modId = "zygisk_lsposed"
        var disableFlagExists = true

        val result = controller.execute(
            PrivilegedRequest("enable-mod", PrivilegedManagementActions.ACTION_KSU_MODULE_ENABLE,
                arguments = mapOf("module_id" to modId)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("test -d /data/adb/modules/$modId") -> mockResult()
                c.contains("test -e /data/adb/ksud") -> mockResult()
                c.contains("/data/adb/ksud module enable $modId") -> {
                    disableFlagExists = false
                    mockResult()
                }
                c.contains("test -e /data/adb/modules/$modId/disable") -> {
                    mockResult(success = disableFlagExists, code = if (disableFlagExists) "ok" else "missing", exitCode = if (disableFlagExists) 0 else 1)
                }
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
    }

    @Test
    fun moduleInstallVerifiesDiskStagedModulePropAgainstZip() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val zipPath = "/data/local/tmp/test_module.zip"
        val modId = "test_module"

        // ksud 安装退出 0，但磁盘 modules 目录下的 module.prop 版本不匹配
        val mismatchResult = controller.execute(
            PrivilegedRequest("inst-mismatch", PrivilegedManagementActions.ACTION_KSU_MODULE_INSTALL,
                arguments = mapOf("zip_path" to zipPath)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat -c %s") -> mockResult(stdout = "204800\n")
                c.contains("unzip -p") && c.contains("module.prop") -> mockResult(
                    stdout = "id=$modId\nname=Test Module\nversion=2.0\nversionCode=20\n")
                c.contains("test -e /data/adb/ksud") -> mockResult()
                c.contains("/data/adb/ksud module install") -> mockResult()
                c.contains("cat /data/adb/modules/$modId/module.prop") -> mockResult(
                    stdout = "id=$modId\nname=Test Module\nversion=1.0\nversionCode=10\n")
                c.contains("cat /data/adb/modules_update/$modId/module.prop") -> mockResult(success = false, code = "no", exitCode = 1)
                else -> mockResult()
            }
        }

        assertFalse("Must not declare install verified if staged disk module does not match ZIP version", mismatchResult.success)
        assertEquals("module_install_unconfirmed", mismatchResult.code)
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
