package cn.com.omnimind.baselib.privileged.management

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedBackend
import cn.com.omnimind.baselib.privileged.PrivilegedRequest
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedManagementControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun rootOnlyActionFailsUnderAdbBackendWithoutExecutingCommands() = runBlocking {
        var commandExecuted = false
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest(
            requestId = "req-1",
            action = PrivilegedManagementActions.ACTION_FILE_READ,
            arguments = mapOf("path" to "/data/data/com.example.app/databases/app.db"),
        )

        val result = controller.execute(request, PrivilegedBackend.ADB, { true }) {
            commandExecuted = true
            mockResult(success = true)
        }

        assertFalse(result.success)
        assertEquals("unsupported_action", result.code)
        assertFalse("Command runner must not be invoked when backend is insufficient", commandExecuted)
    }

    @Test
    fun accessRevocationHaltsExecutionBeforeMutatingSideEffect() = runBlocking {
        var callCount = 0
        var allowed = true
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest(
            requestId = "req-2",
            action = PrivilegedManagementActions.ACTION_SELINUX_SET,
            arguments = mapOf("mode" to "permissive"),
        )

        val result = controller.execute(request, PrivilegedBackend.ROOT, { allowed }) { cmd ->
            callCount++
            // 首次调用读状态时撤销权限
            allowed = false
            mockResult(stdout = "Enforcing\n")
        }

        assertFalse(result.success)
        assertEquals("privileged_access_changed", result.code)
        assertTrue("Must not issue mutation command after access is revoked", callCount <= 1)
    }

    @Test
    fun actionSetsAreProperlyCategorizedAndConsistent() {
        val actions = PrivilegedManagementController.actions
        val mutating = PrivilegedManagementController.mutatingActions
        val rootOnly = PrivilegedManagementController.rootOnlyActions

        assertTrue(actions.containsAll(mutating))
        assertTrue(actions.containsAll(rootOnly))

        // 所有受保护文件、模块、内核底层写操作必须标记为 rootOnly
        assertTrue(rootOnly.contains(PrivilegedManagementActions.ACTION_FILE_WRITE))
        assertTrue(rootOnly.contains(PrivilegedManagementActions.ACTION_KSU_MODULE_ENABLE))
        assertTrue(rootOnly.contains(PrivilegedManagementActions.ACTION_XPOSED_SCOPE_SET))
        assertTrue(rootOnly.contains(PrivilegedManagementActions.ACTION_SELINUX_SET))
        assertTrue(rootOnly.contains(PrivilegedManagementActions.ACTION_SYSTEM_REBOOT))
        assertTrue(rootOnly.contains(PrivilegedManagementActions.ACTION_PARTITION_FLASH))

        // 只读查询不应列入 mutatingActions
        assertFalse(mutating.contains(PrivilegedManagementActions.ACTION_PROCESS_LIST))
        assertFalse(mutating.contains(PrivilegedManagementActions.ACTION_FILE_STAT))
        assertFalse(mutating.contains(PrivilegedManagementActions.ACTION_KSU_MODULE_LIST))
        assertFalse(mutating.contains(PrivilegedManagementActions.ACTION_XPOSED_PROBE))
        assertFalse(mutating.contains(PrivilegedManagementActions.ACTION_SYSTEM_LOGCAT))
        assertFalse(mutating.contains(PrivilegedManagementActions.ACTION_SYSTEM_REBOOT_STATUS))
    }

    @Test
    fun handlesReportsExactActionSupport() {
        val controller = PrivilegedManagementController(context, 0)
        assertTrue(controller.handles(PrivilegedManagementActions.ACTION_PACKAGE_INSTALL))
        assertTrue(controller.handles(PrivilegedManagementActions.ACTION_SYSTEM_REBOOT))
        assertTrue(controller.handles(PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS))
        assertFalse(controller.handles("arbitrary.unknown.action"))
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
