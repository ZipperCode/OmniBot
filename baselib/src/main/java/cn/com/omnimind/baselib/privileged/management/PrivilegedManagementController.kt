package cn.com.omnimind.baselib.privileged.management

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedBackend
import cn.com.omnimind.baselib.privileged.PrivilegedRequest
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.coroutines.CancellationException

/**
 * 集中管理控制器，实现应用生命周期、受保护文件/真实tar备份恢复、KernelSU模块、
 * LSPosed/Vector管理能力与状态读回、设备启动/内核/分区/SELinux系统操作。
 *
 * 所有命令执行均经由父层传入的 `command` runner（借由 ACTION_DEVICE_COMMAND 分发），
 * 复用现有的身份校验、租约取消与安全审计。
 */
class PrivilegedManagementController(
    private val context: Context,
    private val userId: Int,
) {
    fun handles(action: String): Boolean = action in actions

    suspend fun execute(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        stillAllowed: () -> Boolean,
        command: suspend (String) -> PrivilegedResult,
    ): PrivilegedResult {
        val execution = ManagementActionExecution(request, userId, backend, command, stillAllowed)
        return try {
            if (backend != PrivilegedBackend.ROOT && request.action in rootOnlyActions) {
                throw ManagementActionException("unsupported_action", "Action '${request.action}' requires a root backend.")
            }
            when (request.action) {
                // F06: 应用与进程控制
                PrivilegedManagementActions.ACTION_PACKAGE_INSTALL -> ApplicationManagementHandler.install(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_UNINSTALL -> ApplicationManagementHandler.uninstall(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_FREEZE -> ApplicationManagementHandler.freeze(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_UNFREEZE -> ApplicationManagementHandler.unfreeze(execution)
                PrivilegedManagementActions.ACTION_PROCESS_LIST -> ApplicationManagementHandler.processList(execution)
                PrivilegedManagementActions.ACTION_PROCESS_KILL -> ApplicationManagementHandler.processKill(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_VERIFY_PERMISSION -> ApplicationManagementHandler.verifyPermission(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_GRANT_VERIFIED -> ApplicationManagementHandler.grantPermissionVerified(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_REVOKE_VERIFIED -> ApplicationManagementHandler.revokePermissionVerified(execution)
                PrivilegedManagementActions.ACTION_PACKAGE_SET_APPOPS_VERIFIED -> ApplicationManagementHandler.setAppopsVerified(execution)

                // F07: 受保护文件读写与归档备份恢复
                PrivilegedManagementActions.ACTION_FILE_READ -> ProtectedFileManagementHandler.read(execution)
                PrivilegedManagementActions.ACTION_FILE_WRITE -> ProtectedFileManagementHandler.write(execution)
                PrivilegedManagementActions.ACTION_FILE_STAT -> ProtectedFileManagementHandler.stat(execution)
                PrivilegedManagementActions.ACTION_FILE_CHMOD -> ProtectedFileManagementHandler.chmod(execution)
                PrivilegedManagementActions.ACTION_FILE_CHOWN -> ProtectedFileManagementHandler.chown(execution)
                PrivilegedManagementActions.ACTION_FILE_BACKUP_TAR -> ProtectedFileManagementHandler.backupTar(execution)
                PrivilegedManagementActions.ACTION_FILE_RESTORE_TAR -> ProtectedFileManagementHandler.restoreTar(execution)
                PrivilegedManagementActions.ACTION_FILE_VERIFY_TAR -> ProtectedFileManagementHandler.verifyTar(execution)

                // F08: KernelSU 系列 Root 模块管理
                PrivilegedManagementActions.ACTION_KSU_MODULE_LIST -> KernelSuManagementHandler.moduleList(execution)
                PrivilegedManagementActions.ACTION_KSU_MODULE_INFO -> KernelSuManagementHandler.moduleInfo(execution)
                PrivilegedManagementActions.ACTION_KSU_MODULE_INSTALL -> KernelSuManagementHandler.moduleInstall(execution)
                PrivilegedManagementActions.ACTION_KSU_MODULE_ENABLE -> KernelSuManagementHandler.moduleEnable(execution)
                PrivilegedManagementActions.ACTION_KSU_MODULE_DISABLE -> KernelSuManagementHandler.moduleDisable(execution)
                PrivilegedManagementActions.ACTION_KSU_MODULE_UNINSTALL -> KernelSuManagementHandler.moduleUninstall(execution)

                // F09 & F15: LSPosed / Vector 探测与作用域管理
                PrivilegedManagementActions.ACTION_XPOSED_PROBE -> XposedManagementHandler.probe(execution)
                PrivilegedManagementActions.ACTION_XPOSED_MODULE_LIST -> XposedManagementHandler.moduleList(execution)
                PrivilegedManagementActions.ACTION_XPOSED_MODULE_ENABLE -> XposedManagementHandler.moduleEnable(execution)
                PrivilegedManagementActions.ACTION_XPOSED_MODULE_DISABLE -> XposedManagementHandler.moduleDisable(execution)
                PrivilegedManagementActions.ACTION_XPOSED_SCOPE_GET -> XposedManagementHandler.scopeGet(execution)
                PrivilegedManagementActions.ACTION_XPOSED_SCOPE_SET -> XposedManagementHandler.scopeSet(execution)
                PrivilegedManagementActions.ACTION_XPOSED_TARGET_RELOAD -> XposedManagementHandler.targetReload(execution)
                PrivilegedManagementActions.ACTION_XPOSED_VERIFY_STATUS -> XposedManagementHandler.verifyStatus(execution)

                // F15: 系统日志与设备启动/分区/内核/SELinux
                PrivilegedManagementActions.ACTION_SYSTEM_LOGCAT -> SystemDeviceManagementHandler.logcat(execution)
                PrivilegedManagementActions.ACTION_SYSTEM_DMESG -> SystemDeviceManagementHandler.dmesg(execution)
                PrivilegedManagementActions.ACTION_DEVICE_REBOOT,
                PrivilegedManagementActions.ACTION_SYSTEM_REBOOT -> SystemDeviceManagementHandler.reboot(execution)
                PrivilegedManagementActions.ACTION_SYSTEM_REBOOT_STATUS -> SystemDeviceManagementHandler.rebootStatus(execution)
                PrivilegedManagementActions.ACTION_SELINUX_GET -> SystemDeviceManagementHandler.selinuxGet(execution)
                PrivilegedManagementActions.ACTION_SELINUX_SET -> SystemDeviceManagementHandler.selinuxSet(execution)
                PrivilegedManagementActions.ACTION_SYSPROP_GET -> SystemDeviceManagementHandler.getprop(execution)
                PrivilegedManagementActions.ACTION_SYSPROP_SET -> SystemDeviceManagementHandler.setprop(execution)
                PrivilegedManagementActions.ACTION_SYSTEM_UNAME -> SystemDeviceManagementHandler.uname(execution)
                PrivilegedManagementActions.ACTION_SYSTEM_PARTITIONS -> SystemDeviceManagementHandler.partitions(execution)
                PrivilegedManagementActions.ACTION_SYSTEM_MOUNTS -> SystemDeviceManagementHandler.mounts(execution)
                PrivilegedManagementActions.ACTION_PARTITION_BACKUP -> SystemDeviceManagementHandler.partitionBackup(execution)
                PrivilegedManagementActions.ACTION_PARTITION_FLASH -> SystemDeviceManagementHandler.partitionFlash(execution)

                else -> throw ManagementActionException("unsupported_action", "Action '${request.action}' is not handled by PrivilegedManagementController.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ManagementActionException) {
            execution.failure(error)
        } catch (error: IllegalArgumentException) {
            execution.failure("invalid_arguments", error.message.orEmpty())
        } catch (_: SecurityException) {
            execution.failure("permission_denied", "The system denied access to this management capability.")
        } catch (_: UnsupportedOperationException) {
            execution.failure("unsupported_action", "This device does not support the operation.")
        }
    }

    companion object {
        val actions: Set<String> get() = PrivilegedManagementActions.actions
        val mutatingActions: Set<String> get() = PrivilegedManagementActions.mutatingActions
        val rootOnlyActions: Set<String> get() = PrivilegedManagementActions.rootOnlyActions
    }
}
