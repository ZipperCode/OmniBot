package cn.com.omnimind.bot.agent.runtime

import cn.com.omnimind.baselib.privileged.PrivilegedActionPolicy
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 官方 ACP 权限与授权范围回归测试。
 * 验证：不可分割 Scope 项、禁止笛卡尔积提权、目录路径边界与穿透防御、
 * 跨 Turn / Session 隔离与模式撤销、Shell 上下文精确绑定、宿主安全不变量。
 */
class LocalAcpAuthorizationRegressionTest {

    @Before
    fun setUp() {
        AcpTurnGrantRegistry.clear()
    }

    @Test
    fun turnGrantEnforcesIndivisibleScopeAndPreventsCartesianProductPrivilegeEscalation() {
        val sessionId = "session-test-1"
        val turnId = "turn-101"

        // 用户分别授权：1) 对包 A 执行 install；2) 对包 B 执行 uninstall
        val grantInstallA = TurnPermissionScope(
            action = "package_control.install",
            packageName = "com.foo.appA"
        )
        val grantUninstallB = TurnPermissionScope(
            action = "package_control.uninstall",
            packageName = "com.foo.appB"
        )

        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, grantInstallA)
        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, grantUninstallB)

        // 验证精确匹配项均已放行
        assertTrue("已授权的 install 包 A 必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, grantInstallA))
        assertTrue("已授权的 uninstall 包 B 必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, grantUninstallB))

        // 核心安全验证：禁止笛卡尔积交叉提权（不能对包 A 执行 uninstall，也不能对包 B 执行 install）
        val unapprovedUninstallA = TurnPermissionScope(
            action = "package_control.uninstall",
            packageName = "com.foo.appA"
        )
        val unapprovedInstallB = TurnPermissionScope(
            action = "package_control.install",
            packageName = "com.foo.appB"
        )
        assertFalse("未授权的 uninstall 包 A 必须拦截并要求询问", AcpTurnGrantRegistry.isGranted(sessionId, turnId, unapprovedUninstallA))
        assertFalse("未授权的 install 包 B 必须拦截并要求询问", AcpTurnGrantRegistry.isGranted(sessionId, turnId, unapprovedInstallB))

        // 越界未授权包 C 必须拦截
        val unapprovedPkgC = TurnPermissionScope(
            action = "package_control.install",
            packageName = "com.foo.appC"
        )
        assertFalse("未涉及的包 C 必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, unapprovedPkgC))
    }

    @Test
    fun canonicalPathBoundariesPreventPrefixEscapeAndDirectoryTraversal() {
        val sessionId = "session-path-test"
        val turnId = "turn-201"

        // 授权写入 /data/local/tmp 目录下的文件
        val allowedPath = "/data/local/tmp"
        val grantScope = TurnPermissionScope(
            action = "file_control.write",
            path = allowedPath
        )
        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, grantScope)

        // 合法子路径与精准匹配放行
        val validSubpath = TurnPermissionScope(
            action = "file_control.write",
            path = "/data/local/tmp/module.tar"
        )
        assertTrue("合法子路径文件必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, validSubpath))

        // 前缀碰撞逃逸（如 /data/local/tmpxyz 不是 /data/local/tmp 的子目录）必须拦截
        val prefixCollision = TurnPermissionScope(
            action = "file_control.write",
            path = "/data/local/tmpxyz/secret.txt"
        )
        assertFalse("前缀碰撞目录必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, prefixCollision))

        // 路径穿透逃逸（如 /data/local/tmp/../system/file）必须拦截
        val traversalEscape = TurnPermissionScope(
            action = "file_control.write",
            path = "/data/local/tmp/../../system/etc/hosts"
        )
        assertFalse("目录穿透逃逸路径必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, traversalEscape))
    }

    @Test
    fun shellCommandsBindExactExecutionContextWithoutArbitraryWildcard() {
        val sessionId = "session-shell-ctx"
        val turnId = "turn-301"

        val approvedScope = TurnPermissionScope(
            action = PrivilegedActionPolicy.ACTION_SHELL_EXEC,
            command = "tar -xzf app.tar",
            workingDirectory = "/data/local/tmp",
            environment = mapOf("PATH" to "/system/bin"),
            isShell = true
        )
        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, approvedScope)

        // 相同命令在完全相同的工作目录与环境下放行
        assertTrue("相同上下文命令必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, approvedScope))

        // 命令相同但更改了工作目录（可能导致相对路径危害）必须拦截
        val differentCwd = approvedScope.copy(workingDirectory = "/system")
        assertFalse("改变工作目录的命令必须重新询问", AcpTurnGrantRegistry.isGranted(sessionId, turnId, differentCwd))

        // 命令相同但篡改了环境变量（可能导致 PATH 劫持）必须拦截
        val hijackedEnv = approvedScope.copy(environment = mapOf("PATH" to "/data/local/tmp/bin:/system/bin"))
        assertFalse("改变环境变量的命令必须重新询问", AcpTurnGrantRegistry.isGranted(sessionId, turnId, hijackedEnv))

        // 完全不同的命令绝不共享授权
        val differentCmd = approvedScope.copy(command = "rm -rf /data")
        assertFalse("不同命令必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, differentCmd))
    }

    @Test
    fun turnCompletionAndSessionModeChangeImmediatelyEvictGrants() {
        val sessionId = "session-lifecycle"
        val turn1 = "turn-1"
        val turn2 = "turn-2"

        val scope = TurnPermissionScope(
            action = "package_control.install",
            packageName = "com.test.app"
        )
        AcpTurnGrantRegistry.recordGrant(sessionId, turn1, scope, allowSystemModifications = false)

        assertTrue("Turn 1 内部必须命中缓存", AcpTurnGrantRegistry.isGranted(sessionId, turn1, scope))

        // Turn 1 结束并指定 turnId 回收缓存
        AcpTurnGrantRegistry.evict(sessionId, expectedTurnId = turn1)

        // Turn 2 绝不继承 Turn 1 的授权
        assertFalse("Turn 2 绝不继承历史授权", AcpTurnGrantRegistry.isGranted(sessionId, turn2, scope))

        // 跨 Session 绝不共享
        assertFalse("不同 Session 绝不共享授权", AcpTurnGrantRegistry.isGranted("session-other", turn1, scope))
    }

    @Test
    fun hostSafetyRemainsInviolableWhileSystemModificationsAreGoverned() {
        // 普通单次未授权状态：系统重启必须拦截
        assertNotNull("未授权状态拦截重启", PrivilegedActionPolicy.blockedCommandReason("reboot", allowSystemModifications = false))

        // 用户授权后透传 allowSystemModifications：系统重启放行
        assertNull("已授权状态放行重启", PrivilegedActionPolicy.blockedCommandReason("reboot", allowSystemModifications = true))

        // 自身宿主保护命令无论任何模式、任何授权标志均绝对拦截
        val hostForceStop = "am force-stop cn.com.omnimind.bot"
        assertNotNull("宿主强停在普通模式必被拦截", PrivilegedActionPolicy.blockedCommandReason(hostForceStop, allowSystemModifications = false))
        assertNotNull("宿主强停在全权授权模式仍必被拦截", PrivilegedActionPolicy.blockedCommandReason(hostForceStop, allowSystemModifications = true))
        assertTrue("宿主保护命中专有检测函数", PrivilegedActionPolicy.isHostProtectionViolation(hostForceStop))
    }

    @Test
    fun managementSnakeCasePackageAndModuleIdMatchAccurately() {
        val sessionId = "session-snake-case"
        val turnId = "turn-snake"

        val moduleScope = TurnPermissionScope(
            action = "ksu_control.module_install",
            moduleId = "sample_ksu_module",
            path = "/data/local/tmp/module.zip"
        )
        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, moduleScope)

        // Same scope matches
        assertTrue("相同 moduleId 与 path 的模块安装必须命中授权", AcpTurnGrantRegistry.isGranted(sessionId, turnId, moduleScope))

        // Different moduleId is rejected
        val differentModule = moduleScope.copy(moduleId = "other_module")
        assertFalse("不同 moduleId 必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, differentModule))

        // Different zip path is rejected
        val differentPath = moduleScope.copy(path = "/data/local/tmp/other.zip")
        assertFalse("不同 zip 路径必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, differentPath))
    }

    @Test
    fun moduleDeployTaskApprovalGrantsTargetReloadAndRebootWithoutReprompting() {
        val sessionId = "session-deploy-test"
        val turnId = "turn-deploy"

        val deployScope = TurnPermissionScope(
            action = "package_control.install",
            packageName = "com.example.module",
            path = "/data/local/tmp/module.apk"
        )
        val targetReloadScope = TurnPermissionScope(
            action = "xposed_control.target_reload",
            packageName = "com.target.app"
        )
        val rebootScope = TurnPermissionScope(
            action = "device_control.reboot"
        )

        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, deployScope)
        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, targetReloadScope)
        AcpTurnGrantRegistry.recordGrant(sessionId, turnId, rebootScope, allowSystemModifications = true)

        assertTrue("部署模块必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, deployScope))
        assertTrue("目标重载必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, targetReloadScope))
        assertTrue("设备重启必须放行", AcpTurnGrantRegistry.isGranted(sessionId, turnId, rebootScope))
        assertTrue("设备重启具备 allowSystemModifications", AcpTurnGrantRegistry.allowsSystemModifications(sessionId, turnId, rebootScope))

        val unapprovedReload = targetReloadScope.copy(packageName = "com.unrelated.app")
        assertFalse("非授权目标重载必须拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, unapprovedReload))

        val shellScope = TurnPermissionScope(action = "shell.exec", command = "reboot", isShell = true)
        assertFalse("未绑定的泛 shell 重启仍被拦截", AcpTurnGrantRegistry.isGranted(sessionId, turnId, shellScope))

        AcpTurnGrantRegistry.evict(sessionId, expectedTurnId = turnId)
        assertFalse("Turn 结束后部署权限已回收", AcpTurnGrantRegistry.isGranted(sessionId, turnId, deployScope))
        assertFalse("Turn 结束后重启权限已回收", AcpTurnGrantRegistry.isGranted(sessionId, turnId, rebootScope))
    }
}
