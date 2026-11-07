package cn.com.omnimind.baselib.privileged.management

import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * F06: 应用安装更新、卸载、冻结解冻、进程管理与权限生效读回。
 */
internal object ApplicationManagementHandler {
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_.]+$")
    private val PERM_GRANTED_REGEX = Regex("granted=(true|false)")

    suspend fun install(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val apkPath = requireNotNull(args["apk_path"]?.trim()?.takeIf { it.isNotEmpty() }) { "apk_path is required." }
        val reinstall = args["reinstall"]?.lowercase() != "false"
        val grantPermissions = args["grant_permissions"]?.lowercase() == "true"
        val allowDowngrade = args["allow_downgrade"]?.lowercase() == "true"
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        // 1. 验证目标 APK 存在且非空
        val checkApk = execution.run("stat", "-c", "%s", apkPath)
        if (!checkApk.success || checkApk.stdout.trim().toLongOrNull() == null) {
            throw ManagementActionException("apk_not_found", "Target APK was not found or is inaccessible at: $apkPath", checkApk)
        }

        // 2. 提取或读取预期的应用包名与版本代码
        val apkMeta = extractApkMetadata(execution, apkPath)
        val packageName = args["package_name"]?.trim()?.takeIf { it.isNotEmpty() } ?: apkMeta?.packageName
        val expectedVersionCode = args["version_code"]?.toLongOrNull() ?: apkMeta?.versionCode

        val beforeVersion = if (packageName != null) readPackageVersionInfo(execution, packageName, targetUser) else null

        val flags = mutableListOf<String>()
        if (reinstall) flags += "-r"
        if (grantPermissions) flags += "-g"
        if (allowDowngrade) flags += "-d"
        if (targetUser != 0) {
            flags += "--user"
            flags += targetUser.toString()
        }

        val installCmd = mutableListOf("pm", "install")
        installCmd += flags
        installCmd += apkPath

        val installResult = execution.run(*installCmd.toTypedArray())
        val accepted = installResult.success && installResult.stdout.contains("Success", ignoreCase = true)

        // 3. 真实读回验证：既校验 pm path 确认安装路径，又校验 dumpsys 版本与 APK 预期一致
        val afterVersion = if (packageName != null) readPackageVersionInfo(execution, packageName, targetUser) else null
        val pathConfirmed = if (packageName != null) isPackageInstalled(execution, packageName, targetUser) else accepted
        val versionConfirmed = if (expectedVersionCode != null && afterVersion != null) {
            afterVersion.versionCode == expectedVersionCode
        } else pathConfirmed

        val verified = accepted && pathConfirmed && versionConfirmed

        val obs = ManagementActionExecution.observation(
            before = beforeVersion?.let { JsonPrimitive("${it.versionName ?: "unknown"} (${it.versionCode})") },
            after = afterVersion?.let { JsonPrimitive("${it.versionName ?: "unknown"} (${it.versionCode})") } ?: JsonPrimitive(pathConfirmed),
            expected = expectedVersionCode?.let { JsonPrimitive(it) } ?: JsonPrimitive(true),
            commandAccepted = accepted,
            verified = verified,
            scope = "package",
            userId = targetUser,
            details = buildJsonObject {
                put("apkPath", apkPath)
                if (packageName != null) put("packageName", packageName)
                if (expectedVersionCode != null) put("expectedVersionCode", expectedVersionCode)
                if (afterVersion != null) {
                    put("installedVersionCode", afterVersion.versionCode)
                    if (afterVersion.versionName != null) put("installedVersionName", afterVersion.versionName)
                }
                put("pathConfirmed", pathConfirmed)
                put("versionConfirmed", versionConfirmed)
                put("output", installResult.stdout.trim())
            }
        )

        return if (verified) {
            execution.success(
                message = "Package installed and verified successfully (path and version confirmed).",
                observation = obs,
                stdout = installResult.stdout,
                stderr = installResult.stderr,
                exitCode = installResult.exitCode,
            )
        } else if (!versionConfirmed && afterVersion != null) {
            execution.failure(
                code = "version_mismatch",
                message = "Package installed, but installed versionCode (${afterVersion.versionCode}) does not match expected APK versionCode ($expectedVersionCode).",
                diagnostic = installResult,
                observation = obs,
            )
        } else {
            execution.failure(
                code = if (accepted) "package_verification_unconfirmed" else "install_failed",
                message = if (accepted) "Package install reported success, but package was not confirmed installed." else "pm install failed: ${installResult.stderr.ifBlank { installResult.stdout }.trim()}",
                diagnostic = installResult,
                observation = obs,
            )
        }
    }

    suspend fun uninstall(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val keepData = args["keep_data"]?.lowercase() == "true"
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val beforeInstalled = isPackageInstalled(execution, packageName, targetUser)

        val cmd = mutableListOf("pm", "uninstall")
        if (targetUser != 0) {
            cmd += "--user"
            cmd += targetUser.toString()
        }
        if (keepData) cmd += "-k"
        cmd += packageName

        val uninstallResult = execution.run(*cmd.toTypedArray())
        val afterInstalled = isPackageInstalled(execution, packageName, targetUser)
        val verified = !afterInstalled

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeInstalled),
            after = JsonPrimitive(afterInstalled),
            expected = JsonPrimitive(false),
            commandAccepted = uninstallResult.success,
            verified = verified,
            scope = "package",
            userId = targetUser,
            details = buildJsonObject {
                put("packageName", packageName)
                put("output", uninstallResult.stdout.trim())
            }
        )

        return if (verified) {
            execution.success(
                message = "Package uninstalled and removal verified successfully.",
                observation = obs,
                stdout = uninstallResult.stdout,
                stderr = uninstallResult.stderr,
                exitCode = uninstallResult.exitCode,
            )
        } else {
            execution.failure(
                code = "uninstall_unconfirmed",
                message = "Package removal could not be confirmed: ${uninstallResult.stderr.ifBlank { uninstallResult.stdout }.trim()}",
                diagnostic = uninstallResult,
                observation = obs,
            )
        }
    }

    suspend fun freeze(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val beforeState = readPackageEnabledState(execution, packageName, targetUser)
        val cmd = mutableListOf("pm", "disable-user", "--user", targetUser.toString(), packageName)
        val freezeResult = execution.run(*cmd.toTypedArray())

        val afterState = readPackageEnabledState(execution, packageName, targetUser)
        val verified = afterState == PackageEnabledState.DISABLED

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeState.name),
            after = JsonPrimitive(afterState.name),
            expected = JsonPrimitive(PackageEnabledState.DISABLED.name),
            commandAccepted = freezeResult.success,
            verified = verified,
            scope = "package",
            userId = targetUser,
            details = buildJsonObject { put("packageName", packageName) }
        )

        return if (verified) {
            execution.success(
                message = "Package frozen (disabled) and state verified.",
                observation = obs,
                stdout = freezeResult.stdout,
                exitCode = freezeResult.exitCode,
            )
        } else {
            execution.failure(
                code = "state_change_unconfirmed",
                message = "Package disable command executed, but package remains enabled.",
                diagnostic = freezeResult,
                observation = obs,
            )
        }
    }

    suspend fun unfreeze(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val beforeState = readPackageEnabledState(execution, packageName, targetUser)
        val cmd = mutableListOf("pm", "enable", "--user", targetUser.toString(), packageName)
        val enableResult = execution.run(*cmd.toTypedArray())

        val afterState = readPackageEnabledState(execution, packageName, targetUser)
        val verified = afterState == PackageEnabledState.ENABLED

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeState.name),
            after = JsonPrimitive(afterState.name),
            expected = JsonPrimitive(PackageEnabledState.ENABLED.name),
            commandAccepted = enableResult.success,
            verified = verified,
            scope = "package",
            userId = targetUser,
            details = buildJsonObject { put("packageName", packageName) }
        )

        return if (verified) {
            execution.success(
                message = "Package unfrozen (enabled) and state verified.",
                observation = obs,
                stdout = enableResult.stdout,
                exitCode = enableResult.exitCode,
            )
        } else {
            execution.failure(
                code = "state_change_unconfirmed",
                message = "Package enable command executed, but package remains disabled.",
                diagnostic = enableResult,
                observation = obs,
            )
        }
    }

    suspend fun processList(execution: ManagementActionExecution): PrivilegedResult {
        val filter = execution.request.arguments["filter"]?.trim()
        val result = execution.run("ps", "-A", "-o", "PID,PPID,USER,NAME")
        val lines = if (result.success) result.stdout.lineSequence() else {
            val fallback = execution.run("ps")
            fallback.stdout.lineSequence()
        }

        val processes = buildJsonArray {
            var isHeader = true
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (isHeader) {
                    isHeader = false
                    continue
                }
                val parts = trimmed.split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val pid = parts[0]
                    val ppid = parts[1]
                    val user = parts[2]
                    val name = parts.subList(3, parts.size).joinToString(" ")
                    if (filter == null || name.contains(filter, ignoreCase = true) || user.contains(filter, ignoreCase = true)) {
                        add(buildJsonObject {
                            put("pid", pid.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(pid))
                            put("ppid", ppid.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(ppid))
                            put("user", user)
                            put("name", name)
                        })
                    }
                }
            }
        }

        return execution.success(
            message = "Process list retrieved successfully.",
            observation = buildJsonObject {
                put("count", processes.size)
                put("processes", processes)
            },
            data = mapOf("count" to processes.size.toString()),
            stdout = result.stdout,
        )
    }

    suspend fun processKill(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val pid = args["pid"]?.toIntOrNull()
        val packageName = args["package_name"]?.trim()
        val signal = args["signal"]?.trim()?.removePrefix("-") ?: "9"

        require(pid != null || !packageName.isNullOrBlank()) { "Either pid or package_name is required." }

        val (beforeRunning, killCmd, checkRunning) = if (pid != null) {
            val before = isPidRunning(execution, pid)
            Triple(before, listOf("kill", "-$signal", pid.toString()), suspend { isPidRunning(execution, pid) })
        } else {
            val before = isPackageProcessRunning(execution, packageName!!)
            Triple(before, listOf("am", "force-stop", packageName), suspend { isPackageProcessRunning(execution, packageName) })
        }

        val killResult = execution.run(*killCmd.toTypedArray())
        val afterRunning = checkRunning()
        val verified = !afterRunning

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeRunning),
            after = JsonPrimitive(afterRunning),
            expected = JsonPrimitive(false),
            commandAccepted = killResult.success,
            verified = verified,
            scope = "process",
            details = buildJsonObject {
                if (pid != null) put("pid", pid)
                if (packageName != null) put("packageName", packageName)
                put("signal", signal)
            }
        )

        return if (verified) {
            execution.success(
                message = "Process termination verified.",
                observation = obs,
                stdout = killResult.stdout,
                exitCode = killResult.exitCode,
            )
        } else {
            execution.failure(
                code = "kill_unconfirmed",
                message = "Kill command executed, but process is still running.",
                diagnostic = killResult,
                observation = obs,
            )
        }
    }

    suspend fun verifyPermission(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val permission = requireNotNull(args["permission"]?.trim()?.takeIf { it.isNotEmpty() }) { "permission is required." }
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val granted = checkPermissionGranted(execution, packageName, permission, targetUser)
        return execution.success(
            message = "Permission status read from package manager.",
            observation = buildJsonObject {
                put("packageName", packageName)
                put("permission", permission)
                put("granted", granted?.let(::JsonPrimitive) ?: JsonNull)
                put("userId", targetUser)
            },
            data = mapOf("granted" to (granted?.toString() ?: "unknown")),
        )
    }

    suspend fun grantPermissionVerified(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val permission = requireNotNull(args["permission"]?.trim()?.takeIf { it.isNotEmpty() }) { "permission is required." }
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val beforeGranted = checkPermissionGranted(execution, packageName, permission, targetUser)
        val cmd = mutableListOf("pm", "grant", "--user", targetUser.toString(), packageName, permission)
        val grantResult = execution.run(*cmd.toTypedArray())

        val afterGranted = checkPermissionGranted(execution, packageName, permission, targetUser)
        val verified = afterGranted == true

        val obs = ManagementActionExecution.observation(
            before = beforeGranted?.let(::JsonPrimitive),
            after = afterGranted?.let(::JsonPrimitive),
            expected = JsonPrimitive(true),
            commandAccepted = grantResult.success,
            verified = verified,
            scope = "package_permission",
            userId = targetUser,
            details = buildJsonObject {
                put("packageName", packageName)
                put("permission", permission)
            }
        )

        return if (verified) {
            execution.success(
                message = "Permission grant verified in package runtime permissions.",
                observation = obs,
                stdout = grantResult.stdout,
                exitCode = grantResult.exitCode,
            )
        } else {
            execution.failure(
                code = "permission_grant_unconfirmed",
                message = "pm grant was submitted, but permission is not granted in package manager dumpsys.",
                diagnostic = grantResult,
                observation = obs,
            )
        }
    }

    suspend fun revokePermissionVerified(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val permission = requireNotNull(args["permission"]?.trim()?.takeIf { it.isNotEmpty() }) { "permission is required." }
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val beforeGranted = checkPermissionGranted(execution, packageName, permission, targetUser)
        val cmd = mutableListOf("pm", "revoke", "--user", targetUser.toString(), packageName, permission)
        val revokeResult = execution.run(*cmd.toTypedArray())

        val afterGranted = checkPermissionGranted(execution, packageName, permission, targetUser)
        val verified = afterGranted == false

        val obs = ManagementActionExecution.observation(
            before = beforeGranted?.let(::JsonPrimitive),
            after = afterGranted?.let(::JsonPrimitive),
            expected = JsonPrimitive(false),
            commandAccepted = revokeResult.success,
            verified = verified,
            scope = "package_permission",
            userId = targetUser,
            details = buildJsonObject {
                put("packageName", packageName)
                put("permission", permission)
            }
        )

        return if (verified) {
            execution.success(
                message = "Permission revoke verified in package runtime permissions.",
                observation = obs,
                stdout = revokeResult.stdout,
                exitCode = revokeResult.exitCode,
            )
        } else {
            execution.failure(
                code = "permission_revoke_unconfirmed",
                message = "pm revoke was submitted, but permission remains granted.",
                diagnostic = revokeResult,
                observation = obs,
            )
        }
    }

    suspend fun setAppopsVerified(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val packageName = requireValidPackage(args["package_name"])
        val op = requireNotNull(args["op"]?.trim()?.takeIf { it.isNotEmpty() }) { "op is required." }
        val mode = requireNotNull(args["mode"]?.trim()?.takeIf { it.isNotEmpty() }) { "mode is required." }
        val targetUser = args["user_id"]?.toIntOrNull() ?: execution.userId

        val beforeResult = execution.run("cmd", "appops", "get", packageName, op)
        val beforeOutput = beforeResult.stdout.trim()

        val setResult = execution.run("cmd", "appops", "set", "--user", targetUser.toString(), packageName, op, mode)

        val afterResult = execution.run("cmd", "appops", "get", packageName, op)
        val afterOutput = afterResult.stdout.trim()
        val verified = afterOutput.contains(mode, ignoreCase = true)

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeOutput),
            after = JsonPrimitive(afterOutput),
            expected = JsonPrimitive(mode),
            commandAccepted = setResult.success,
            verified = verified,
            scope = "package_appops",
            userId = targetUser,
            details = buildJsonObject {
                put("packageName", packageName)
                put("op", op)
                put("mode", mode)
            }
        )

        return if (verified) {
            execution.success(
                message = "AppOps mode change verified.",
                observation = obs,
                stdout = setResult.stdout,
                exitCode = setResult.exitCode,
            )
        } else {
            execution.failure(
                code = "appops_unconfirmed",
                message = "AppOps command was submitted, but state check does not reflect mode '$mode'.",
                diagnostic = setResult,
                observation = obs,
            )
        }
    }

    private suspend fun isPackageInstalled(execution: ManagementActionExecution, packageName: String, userId: Int): Boolean {
        val cmd = if (userId != 0) listOf("pm", "path", "--user", userId.toString(), packageName) else listOf("pm", "path", packageName)
        val result = execution.run(*cmd.toTypedArray())
        if (result.success && result.stdout.contains("package:", ignoreCase = true)) return true
        if (userId != 0) {
            val fallback = execution.run("pm", "path", packageName)
            if (fallback.success && fallback.stdout.contains("package:", ignoreCase = true)) return true
        }
        return false
    }

    private suspend fun readPackageVersionInfo(execution: ManagementActionExecution, packageName: String, userId: Int): PackageVersionInfo? {
        val dump = execution.run("dumpsys", "package", packageName)
        if (!dump.success || !dump.stdout.contains("Package [$packageName]")) return null
        var versionCode: Long? = null
        var versionName: String? = null
        for (line in dump.stdout.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("versionCode=")) {
                versionCode = Regex("versionCode=(\\d+)").find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
            } else if (trimmed.startsWith("versionName=")) {
                versionName = trimmed.removePrefix("versionName=").trim()
            }
            if (versionCode != null && versionName != null) break
        }
        return if (versionCode != null) PackageVersionInfo(versionCode, versionName) else null
    }

    private suspend fun extractApkMetadata(execution: ManagementActionExecution, apkPath: String): ApkMetadata? {
        val aaptRes = execution.runRaw("aapt dump badging ${ManagementActionExecution.shellQuote(apkPath)} 2>/dev/null")
        if (aaptRes.success && aaptRes.stdout.contains("package: name=")) {
            val pkgMatch = Regex("package: name='([^']+)'").find(aaptRes.stdout)
            val verMatch = Regex("versionCode='(\\d+)'").find(aaptRes.stdout)
            val pkg = pkgMatch?.groupValues?.get(1)
            val ver = verMatch?.groupValues?.get(1)?.toLongOrNull()
            if (pkg != null) return ApkMetadata(pkg, ver)
        }
        return null
    }

    internal data class PackageVersionInfo(val versionCode: Long, val versionName: String?)
    internal data class ApkMetadata(val packageName: String, val versionCode: Long?)

    private suspend fun readPackageEnabledState(execution: ManagementActionExecution, packageName: String, userId: Int): PackageEnabledState {
        val dump = execution.run("dumpsys", "package", packageName)
        if (!dump.success || !dump.stdout.contains("Package [$packageName]")) {
            return PackageEnabledState.NOT_INSTALLED
        }
        val userSection = dump.stdout.lineSequence()
            .dropWhile { !it.contains("User $userId:") }
            .takeWhile { it.startsWith(" ") || it.contains("User $userId:") }
            .joinToString("\n")

        val target = if (userSection.isNotBlank()) userSection else dump.stdout
        return when {
            target.contains("enabled=2") || target.contains("enabled=3") || target.contains("disabled=true") -> PackageEnabledState.DISABLED
            target.contains("enabled=1") || target.contains("enabled=0") || target.contains("disabled=false") -> PackageEnabledState.ENABLED
            else -> {
                val listDisabled = execution.run("pm", "list", "packages", "-d", packageName)
                if (listDisabled.success && listDisabled.stdout.contains("package:$packageName")) {
                    PackageEnabledState.DISABLED
                } else PackageEnabledState.ENABLED
            }
        }
    }

    private suspend fun isPidRunning(execution: ManagementActionExecution, pid: Int): Boolean {
        val check = execution.run("test", "-d", "/proc/$pid")
        return check.success || check.exitCode == 0
    }

    private suspend fun isPackageProcessRunning(execution: ManagementActionExecution, packageName: String): Boolean {
        val pidofResult = execution.run("pidof", packageName)
        return pidofResult.success && pidofResult.stdout.trim().isNotEmpty()
    }

    private suspend fun checkPermissionGranted(execution: ManagementActionExecution, packageName: String, permission: String, userId: Int): Boolean? {
        val dump = execution.run("dumpsys", "package", packageName)
        if (!dump.success) return null
        val lines = dump.stdout.lineSequence().toList()
        for (line in lines) {
            if (line.contains(permission)) {
                val match = PERM_GRANTED_REGEX.find(line)
                if (match != null) {
                    return match.groupValues[1] == "true"
                }
            }
        }
        return null
    }

    private fun requireValidPackage(pkg: String?): String {
        val name = requireNotNull(pkg?.trim()?.takeIf { it.isNotEmpty() }) { "package_name is required." }
        require(PACKAGE_NAME_REGEX.matches(name)) { "Invalid package name: $name" }
        return name
    }

    enum class PackageEnabledState {
        NOT_INSTALLED,
        ENABLED,
        DISABLED,
    }
}
