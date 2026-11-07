package cn.com.omnimind.baselib.privileged

import cn.com.omnimind.baselib.privileged.management.PrivilegedManagementActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedCapabilityManagerTest {

    @Test
    fun policyMergesManagementActionsAndEnforcesPrivilegeTiers() {
        val rootActions = PrivilegedActionPolicy.visibleAgentActions(PrivilegedBackend.ROOT)
        val adbActions = PrivilegedActionPolicy.visibleAgentActions(PrivilegedBackend.ADB)

        // ROOT 后端必须可见所有 33 项管理动作
        PrivilegedManagementActions.actions.forEach { action ->
            assertTrue("ROOT 后端必须支持管理动作 $action", rootActions.contains(action))
        }

        // ADB 后端绝不暴露 Root-only 动作
        PrivilegedManagementActions.rootOnlyActions.forEach { rootAction ->
            assertFalse("ADB 后端不可暴露 rootOnly 动作 $rootAction", adbActions.contains(rootAction))
            assertTrue("requiresRoot 对 $rootAction 必须为 true", PrivilegedActionPolicy.requiresRoot(rootAction))
        }

        // 副作用变更动作强制要求确认
        PrivilegedManagementActions.mutatingActions.forEach { mutatingAction ->
            assertTrue("mutating 动作 $mutatingAction 必须要求确认", PrivilegedActionPolicy.requiresConfirmation(mutatingAction))
        }
    }

    @Test
    fun executorRejectsMutatingActionsWhenUnconfirmedAndWaivesUnderFullPower() {
        val runner = PrivilegedProcessRunner(startProcess = { error("未确认请求不可启动进程") })
        val executor = PrivilegedCommandExecutor(PrivilegedBackend.ROOT, PrivilegedProvider.ROOT, runner)

        // 尝试未确认的 mutating 动作：必须拦截并报告 confirmation_required
        val unconfirmedRequest = PrivilegedRequest(
            requestId = UUID.randomUUID().toString(),
            action = PrivilegedManagementActions.ACTION_PACKAGE_INSTALL,
            arguments = mapOf("package" to "com.test.app"),
            requiresConfirmation = true,
            isFullPower = false
        )
        val result = executor.execute(unconfirmedRequest)
        assertEquals("未确认的变更操作必须要求确认", "confirmation_required", result.code)
        assertTrue(result.requiresConfirmation)

        // 尝试在 ADB 后端执行 Root-Only 操作：必须拦截并返回 unsupported_action
        val adbExecutor = PrivilegedCommandExecutor(PrivilegedBackend.ADB, PrivilegedProvider.SHIZUKU, runner)
        val rootOnlyOnAdb = PrivilegedRequest(
            requestId = UUID.randomUUID().toString(),
            action = PrivilegedManagementActions.ACTION_FILE_READ,
            arguments = mapOf("path" to "/data/system/packages.xml", "confirmed" to "true"),
            requiresConfirmation = false,
            isFullPower = false
        )
        val adbResult = adbExecutor.execute(rootOnlyOnAdb)
        assertEquals("非 ROOT 后端调用 rootOnly 动作必须被拦截", "unsupported_action", adbResult.code)
    }

    @Test
    fun blockedCommandReasonDecouplesSystemModificationsFromHostProtection() {
        val hostForceStop = "am force-stop cn.com.omnimind.bot"
        val rebootCmd = "reboot"
        val partitionCmd = "sm partition disk:179_64 private"
        val remountCmd = "mount -o remount,rw /system"

        // 未授权状态下：全部被拦截
        assertNotNull(PrivilegedActionPolicy.blockedCommandReason(hostForceStop, allowSystemModifications = false))
        assertNotNull(PrivilegedActionPolicy.blockedCommandReason(rebootCmd, allowSystemModifications = false))
        assertNotNull(PrivilegedActionPolicy.blockedCommandReason(partitionCmd, allowSystemModifications = false))
        assertNotNull(PrivilegedActionPolicy.blockedCommandReason(remountCmd, allowSystemModifications = false))

        // 授权系统操作后：系统操作放行，但宿主安全防护绝不让步
        assertNotNull("宿主强停自杀命令任何时候必须拦截", PrivilegedActionPolicy.blockedCommandReason(hostForceStop, allowSystemModifications = true))
        assertNull("已授权的系统重启必须放行", PrivilegedActionPolicy.blockedCommandReason(rebootCmd, allowSystemModifications = true))
        assertNull("已授权的分区操作必须放行", PrivilegedActionPolicy.blockedCommandReason(partitionCmd, allowSystemModifications = true))
        assertNull("已授权的系统重挂载必须放行", PrivilegedActionPolicy.blockedCommandReason(remountCmd, allowSystemModifications = true))
    }

}
