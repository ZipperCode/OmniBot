package cn.com.omnimind.baselib.privileged.management

/**
 * 动作定义与策略集，供 PrivilegedActionPolicy 和父层调用分发接入。
 */
object PrivilegedManagementActions {
    // F06: 应用与进程控制
    const val ACTION_PACKAGE_INSTALL = "package_control.install"
    const val ACTION_PACKAGE_UNINSTALL = "package_control.uninstall"
    const val ACTION_PACKAGE_FREEZE = "package_control.freeze"
    const val ACTION_PACKAGE_UNFREEZE = "package_control.unfreeze"
    const val ACTION_PROCESS_LIST = "process_control.list"
    const val ACTION_PROCESS_KILL = "process_control.kill"
    const val ACTION_PACKAGE_VERIFY_PERMISSION = "package_control.verify_permission"
    const val ACTION_PACKAGE_GRANT_VERIFIED = "package_control.grant_permission_verified"
    const val ACTION_PACKAGE_REVOKE_VERIFIED = "package_control.revoke_permission_verified"
    const val ACTION_PACKAGE_SET_APPOPS_VERIFIED = "package_control.set_appops_verified"

    // F07: 受保护文件读写与归档备份恢复
    const val ACTION_FILE_READ = "file_control.read"
    const val ACTION_FILE_WRITE = "file_control.write"
    const val ACTION_FILE_STAT = "file_control.stat"
    const val ACTION_FILE_CHMOD = "file_control.chmod"
    const val ACTION_FILE_CHOWN = "file_control.chown"
    const val ACTION_FILE_BACKUP_TAR = "file_control.backup_tar"
    const val ACTION_FILE_RESTORE_TAR = "file_control.restore_tar"
    const val ACTION_FILE_VERIFY_TAR = "file_control.verify_tar"

    // F08: KernelSU 系列 Root 模块管理
    const val ACTION_KSU_MODULE_LIST = "ksu_control.module_list"
    const val ACTION_KSU_MODULE_INFO = "ksu_control.module_info"
    const val ACTION_KSU_MODULE_INSTALL = "ksu_control.module_install"
    const val ACTION_KSU_MODULE_ENABLE = "ksu_control.module_enable"
    const val ACTION_KSU_MODULE_DISABLE = "ksu_control.module_disable"
    const val ACTION_KSU_MODULE_UNINSTALL = "ksu_control.module_uninstall"

    // F09 & F15: LSPosed / Vector 探测与作用域管理
    const val ACTION_XPOSED_PROBE = "xposed_control.probe"
    const val ACTION_XPOSED_MODULE_LIST = "xposed_control.module_list"
    const val ACTION_XPOSED_MODULE_ENABLE = "xposed_control.module_enable"
    const val ACTION_XPOSED_MODULE_DISABLE = "xposed_control.module_disable"
    const val ACTION_XPOSED_SCOPE_GET = "xposed_control.scope_get"
    const val ACTION_XPOSED_SCOPE_SET = "xposed_control.scope_set"
    const val ACTION_XPOSED_TARGET_RELOAD = "xposed_control.target_reload"
    const val ACTION_XPOSED_VERIFY_STATUS = "xposed_control.verify_status"

    // F15: 系统日志与设备启动/分区/内核/SELinux
    const val ACTION_SYSTEM_LOGCAT = "system_control.logcat"
    const val ACTION_SYSTEM_DMESG = "system_control.dmesg"
    const val ACTION_DEVICE_REBOOT = "device_control.reboot"
    const val ACTION_SYSTEM_REBOOT = "system.reboot"
    const val ACTION_SYSTEM_REBOOT_STATUS = "system.reboot_status"
    const val ACTION_SELINUX_GET = "system_control.selinux_get"
    const val ACTION_SELINUX_SET = "system_control.selinux_set"
    const val ACTION_SYSPROP_GET = "system_control.getprop"
    const val ACTION_SYSPROP_SET = "system_control.setprop"
    const val ACTION_SYSTEM_UNAME = "system_control.uname"
    const val ACTION_SYSTEM_PARTITIONS = "system_control.partitions"
    const val ACTION_SYSTEM_MOUNTS = "system_control.mounts"
    const val ACTION_PARTITION_BACKUP = "partition_control.backup"
    const val ACTION_PARTITION_FLASH = "partition_control.flash"

    /** 所有支持的管理动作 */
    val actions: Set<String> = linkedSetOf(
        ACTION_PACKAGE_INSTALL,
        ACTION_PACKAGE_UNINSTALL,
        ACTION_PACKAGE_FREEZE,
        ACTION_PACKAGE_UNFREEZE,
        ACTION_PROCESS_LIST,
        ACTION_PROCESS_KILL,
        ACTION_PACKAGE_VERIFY_PERMISSION,
        ACTION_PACKAGE_GRANT_VERIFIED,
        ACTION_PACKAGE_REVOKE_VERIFIED,
        ACTION_PACKAGE_SET_APPOPS_VERIFIED,
        ACTION_FILE_READ,
        ACTION_FILE_WRITE,
        ACTION_FILE_STAT,
        ACTION_FILE_CHMOD,
        ACTION_FILE_CHOWN,
        ACTION_FILE_BACKUP_TAR,
        ACTION_FILE_RESTORE_TAR,
        ACTION_FILE_VERIFY_TAR,
        ACTION_KSU_MODULE_LIST,
        ACTION_KSU_MODULE_INFO,
        ACTION_KSU_MODULE_INSTALL,
        ACTION_KSU_MODULE_ENABLE,
        ACTION_KSU_MODULE_DISABLE,
        ACTION_KSU_MODULE_UNINSTALL,
        ACTION_XPOSED_PROBE,
        ACTION_XPOSED_MODULE_LIST,
        ACTION_XPOSED_MODULE_ENABLE,
        ACTION_XPOSED_MODULE_DISABLE,
        ACTION_XPOSED_SCOPE_GET,
        ACTION_XPOSED_SCOPE_SET,
        ACTION_XPOSED_TARGET_RELOAD,
        ACTION_XPOSED_VERIFY_STATUS,
        ACTION_SYSTEM_LOGCAT,
        ACTION_SYSTEM_DMESG,
        ACTION_DEVICE_REBOOT,
        ACTION_SYSTEM_REBOOT,
        ACTION_SYSTEM_REBOOT_STATUS,
        ACTION_SELINUX_GET,
        ACTION_SELINUX_SET,
        ACTION_SYSPROP_GET,
        ACTION_SYSPROP_SET,
        ACTION_SYSTEM_UNAME,
        ACTION_SYSTEM_PARTITIONS,
        ACTION_SYSTEM_MOUNTS,
        ACTION_PARTITION_BACKUP,
        ACTION_PARTITION_FLASH,
    )

    /** 产生副作用、修改系统或文件状态的动作 */
    val mutatingActions: Set<String> = linkedSetOf(
        ACTION_PACKAGE_INSTALL,
        ACTION_PACKAGE_UNINSTALL,
        ACTION_PACKAGE_FREEZE,
        ACTION_PACKAGE_UNFREEZE,
        ACTION_PROCESS_KILL,
        ACTION_PACKAGE_GRANT_VERIFIED,
        ACTION_PACKAGE_REVOKE_VERIFIED,
        ACTION_PACKAGE_SET_APPOPS_VERIFIED,
        ACTION_FILE_WRITE,
        ACTION_FILE_CHMOD,
        ACTION_FILE_CHOWN,
        ACTION_FILE_BACKUP_TAR,
        ACTION_FILE_RESTORE_TAR,
        ACTION_KSU_MODULE_INSTALL,
        ACTION_KSU_MODULE_ENABLE,
        ACTION_KSU_MODULE_DISABLE,
        ACTION_KSU_MODULE_UNINSTALL,
        ACTION_XPOSED_MODULE_ENABLE,
        ACTION_XPOSED_MODULE_DISABLE,
        ACTION_XPOSED_SCOPE_SET,
        ACTION_XPOSED_TARGET_RELOAD,
        ACTION_DEVICE_REBOOT,
        ACTION_SYSTEM_REBOOT,
        ACTION_SELINUX_SET,
        ACTION_SYSPROP_SET,
        ACTION_PARTITION_BACKUP,
        ACTION_PARTITION_FLASH,
    )

    /** 严格要求真实 ROOT (UID 0) 后端的动作 */
    val rootOnlyActions: Set<String> = linkedSetOf(
        ACTION_FILE_READ,
        ACTION_FILE_WRITE,
        ACTION_FILE_STAT,
        ACTION_FILE_CHMOD,
        ACTION_FILE_CHOWN,
        ACTION_FILE_BACKUP_TAR,
        ACTION_FILE_RESTORE_TAR,
        ACTION_FILE_VERIFY_TAR,
        ACTION_KSU_MODULE_LIST,
        ACTION_KSU_MODULE_INFO,
        ACTION_KSU_MODULE_INSTALL,
        ACTION_KSU_MODULE_ENABLE,
        ACTION_KSU_MODULE_DISABLE,
        ACTION_KSU_MODULE_UNINSTALL,
        ACTION_XPOSED_PROBE,
        ACTION_XPOSED_MODULE_LIST,
        ACTION_XPOSED_MODULE_ENABLE,
        ACTION_XPOSED_MODULE_DISABLE,
        ACTION_XPOSED_SCOPE_GET,
        ACTION_XPOSED_SCOPE_SET,
        ACTION_XPOSED_TARGET_RELOAD,
        ACTION_XPOSED_VERIFY_STATUS,
        ACTION_SYSTEM_DMESG,
        ACTION_DEVICE_REBOOT,
        ACTION_SYSTEM_REBOOT,
        ACTION_SELINUX_SET,
        ACTION_PARTITION_BACKUP,
        ACTION_PARTITION_FLASH,
    )
}
