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
 * F09 & F15: LSPosed / Vector 探测、模块启停、作用域配置、目标重载与状态读回。
 * 遵循 primary source: https://github.com/JingMatrix/Vector (SHA: 093ea599bfa2b50c156da0af53e2e53576e88434)
 * 1. Vector CLI 为唯一法定 mutate 路径，杜绝直接写 SQL 导致绕过 staticScope 与守护进程 ConfigCache 失步；
 * 2. 避免对系统 sqlite3 的硬依赖，优先复用 Vector CLI JSON 进行读取与状态读回；
 * 3. 严格隔离配置读回 (config_verified) 与 Hook 行为证据 (hook_verified)，必须基于目标真实 PID 与框架注入日志，禁止将任意 caller marker 当作行为通过。
 */
internal object XposedManagementHandler {
    private val CLI_CANDIDATE_PATHS = listOf(
        "/data/adb/lspd/cli",
        "/data/adb/modules/zygisk_vector/cli",
        "/data/adb/modules/vector/cli",
        "/data/adb/modules/zygisk_lsposed/cli",
    )

    private val DB_CANDIDATE_PATHS = listOf(
        "/data/adb/lspd/config/modules_config.db",
        "/data/adb/lspd/config/modules.db",
        "/data/adb/vector/config/modules_config.db",
        "/data/adb/vector/config/modules.db",
    )

    private val SYSTEM_TARGETS = setOf("android", "system", "system_server", "com.android.systemui")
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9_.]+$")

    suspend fun probe(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()

        val cliPath = findCliBinary(execution)
        val dbPath = findExistingDb(execution)
        val sqlite3Path = findSqliteBinary(execution)

        val checkZygiskLSPosed = execution.run("test", "-d", "/data/adb/modules/zygisk_lsposed").let { it.success || it.exitCode == 0 }
        val checkVector = execution.run("test", "-d", "/data/adb/modules/vector").let { it.success || it.exitCode == 0 }

        val variant = when {
            checkVector || (cliPath?.contains("vector") == true) || (dbPath?.contains("vector") == true) -> "Vector"
            checkZygiskLSPosed || (cliPath?.contains("lspd") == true) || (dbPath?.contains("lspd") == true) -> "LSPosed"
            else -> "Unknown"
        }

        val supported = cliPath != null || (dbPath != null && sqlite3Path != null)

        val obs = buildJsonObject {
            put("supported", supported)
            put("variant", variant)
            put("cliPath", cliPath ?: "not_found")
            put("dbPath", dbPath ?: "not_found")
            put("sqlite3Path", sqlite3Path ?: "not_found")
            put("vectorIpcAvailable", cliPath != null)
            put("zygiskLSPosedModuleInstalled", checkZygiskLSPosed)
            put("vectorModuleInstalled", checkVector)
        }

        return if (supported) {
            execution.success(
                message = "LSPosed/Vector management interface probed successfully ($variant).",
                observation = obs,
                data = mapOf(
                    "supported" to "true",
                    "variant" to variant,
                    "cliPath" to (cliPath ?: "none"),
                    "dbPath" to (dbPath ?: "none"),
                ),
            )
        } else {
            execution.failure(
                code = "unsupported_action",
                message = "LSPosed/Vector management interface is not available on this device: neither CLI (/data/adb/lspd/cli) nor database was found.",
                observation = obs,
            )
        }
    }

    suspend fun moduleList(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val cli = findCliBinary(execution)

        if (cli != null) {
            // 优先复用 Vector CLI 的 JSON 输出读取，无需依赖外部 sqlite3
            val cliRes = execution.run(cli, "modules", "ls")
            if (cliRes.success) {
                return execution.success(
                    message = "LSPosed modules listed via Vector CLI.",
                    observation = buildJsonObject {
                        put("managedVia", "vector_cli")
                        put("rawOutput", cliRes.stdout.trim())
                    },
                    stdout = cliRes.stdout,
                )
            }
        }

        // 回退至 SQLite 联合查询（若可用）
        val (dbPath, sqlite3) = resolveDbAndSqlite(execution)
        val queryModules = "$sqlite3 ${ManagementActionExecution.shellQuote(dbPath)} 'SELECT mid, module_pkg_name, apk_path, enabled, auto_include FROM modules;'"
        val modulesResult = execution.runRaw(queryModules)
        if (!modulesResult.success) {
            throw ManagementActionException("db_query_failed", "Failed to query modules table: ${modulesResult.stderr.trim()}", modulesResult)
        }

        val queryScope = "$sqlite3 ${ManagementActionExecution.shellQuote(dbPath)} 'SELECT mid, app_pkg_name, user_id FROM scope;'"
        val scopeResult = execution.runRaw(queryScope)
        val scopesByMid = mutableMapOf<Int, MutableList<String>>()
        if (scopeResult.success) {
            for (line in scopeResult.stdout.lineSequence()) {
                val parts = line.split("|")
                if (parts.size >= 2) {
                    val mid = parts[0].trim().toIntOrNull()
                    val pkg = parts[1].trim()
                    if (mid != null && pkg.isNotEmpty()) {
                        scopesByMid.getOrPut(mid) { mutableListOf() }.add(pkg)
                    }
                }
            }
        }

        val modulesArray = buildJsonArray {
            for (line in modulesResult.stdout.lineSequence()) {
                val parts = line.split("|")
                if (parts.size >= 5) {
                    val mid = parts[0].trim().toIntOrNull() ?: continue
                    val pkg = parts[1].trim()
                    val apk = parts[2].trim()
                    val enabled = parts[3].trim() == "1"
                    val autoInclude = parts[4].trim() == "1"
                    val scopes = scopesByMid[mid] ?: emptyList()
                    add(buildJsonObject {
                        put("mid", mid)
                        put("modulePkgName", pkg)
                        put("apkPath", apk)
                        put("enabled", enabled)
                        put("autoInclude", autoInclude)
                        put("scope", buildJsonArray { scopes.forEach { add(JsonPrimitive(it)) } })
                    })
                }
            }
        }

        return execution.success(
            message = "LSPosed modules listed successfully.",
            observation = buildJsonObject {
                put("count", modulesArray.size)
                put("modules", modulesArray)
                put("dbPath", dbPath)
            },
            data = mapOf("count" to modulesArray.size.toString()),
            stdout = modulesResult.stdout,
        )
    }

    suspend fun moduleEnable(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val modulePkgName = requireValidPackage(execution.request.arguments["module_pkg_name"])

        // Vector CLI 为唯一法定 mutate 机制：保证触发 ConfigCache.requestCacheUpdate() 且受 staticScope 约束
        val cli = requireCliBinary(execution)
        val cliRes = execution.run(cli, "modules", "enable", modulePkgName)

        val accepted = cliRes.success && !cliRes.stdout.contains("\"Failed\": [\"$modulePkgName\"", ignoreCase = true) &&
                !cliRes.stdout.contains("\"Failed\":[\"$modulePkgName\"", ignoreCase = true)

        // 真实读回验证：调用 cli modules ls 读取最新真实生效状态
        val verifyRes = execution.run(cli, "modules", "ls")
        val isConfirmedEnabled = verifyRes.stdout.contains(modulePkgName) &&
                verifyRes.stdout.lineSequence().any { it.contains(modulePkgName) && it.contains("enabled", ignoreCase = true) }

        val verified = accepted && isConfirmedEnabled

        val obs = ManagementActionExecution.observation(
            before = JsonNull,
            after = JsonPrimitive(isConfirmedEnabled),
            expected = JsonPrimitive(true),
            commandAccepted = accepted,
            verified = verified,
            scope = "xposed_module",
            details = buildJsonObject {
                put("modulePkgName", modulePkgName)
                put("cliOutput", cliRes.stdout.trim())
                put("confirmedEnabled", isConfirmedEnabled)
            }
        )

        return if (verified) {
            execution.success(
                message = "Xposed module '$modulePkgName' enabled via Vector CLI and verified in daemon cache.",
                observation = obs,
                stdout = cliRes.stdout,
                exitCode = cliRes.exitCode,
            )
        } else {
            execution.failure(
                code = if (accepted) "module_enable_unconfirmed" else "module_enable_failed",
                message = if (accepted) "CLI accepted enable command, but module was not observed enabled in CLI module list."
                else "Vector CLI failed to enable module '$modulePkgName': ${cliRes.stderr.ifBlank { cliRes.stdout }.trim()}",
                diagnostic = cliRes,
                observation = obs,
            )
        }
    }

    suspend fun moduleDisable(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val modulePkgName = requireValidPackage(execution.request.arguments["module_pkg_name"])

        val cli = requireCliBinary(execution)
        val cliRes = execution.run(cli, "modules", "disable", modulePkgName)

        val accepted = cliRes.success && !cliRes.stdout.contains("\"Failed\": [\"$modulePkgName\"", ignoreCase = true)

        val verifyRes = execution.run(cli, "modules", "ls")
        val isConfirmedDisabled = verifyRes.stdout.lineSequence().any { it.contains(modulePkgName) && it.contains("disabled", ignoreCase = true) }

        val verified = accepted && isConfirmedDisabled

        val obs = ManagementActionExecution.observation(
            before = JsonNull,
            after = JsonPrimitive(!isConfirmedDisabled),
            expected = JsonPrimitive(false),
            commandAccepted = accepted,
            verified = verified,
            scope = "xposed_module",
            details = buildJsonObject {
                put("modulePkgName", modulePkgName)
                put("cliOutput", cliRes.stdout.trim())
                put("confirmedDisabled", isConfirmedDisabled)
            }
        )

        return if (verified) {
            execution.success(
                message = "Xposed module '$modulePkgName' disabled via Vector CLI and verified.",
                observation = obs,
                stdout = cliRes.stdout,
                exitCode = cliRes.exitCode,
            )
        } else {
            execution.failure(
                code = if (accepted) "module_disable_unconfirmed" else "module_disable_failed",
                message = if (accepted) "CLI accepted disable command, but module was not confirmed disabled."
                else "Vector CLI failed to disable module: ${cliRes.stderr.ifBlank { cliRes.stdout }.trim()}",
                diagnostic = cliRes,
                observation = obs,
            )
        }
    }

    suspend fun scopeGet(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val modulePkgName = requireValidPackage(execution.request.arguments["module_pkg_name"])
        val cli = findCliBinary(execution)

        if (cli != null) {
            val cliRes = execution.run(cli, "scope", "ls", modulePkgName)
            if (cliRes.success) {
                return execution.success(
                    message = "Module scope retrieved via Vector CLI.",
                    observation = buildJsonObject {
                        put("modulePkgName", modulePkgName)
                        put("rawOutput", cliRes.stdout.trim())
                    },
                    stdout = cliRes.stdout,
                )
            }
        }

        // 回退至 SQLite 读（若 CLI 暂未就绪）
        val (dbPath, sqlite3) = resolveDbAndSqlite(execution)
        val sql = "SELECT app_pkg_name, user_id FROM scope WHERE mid = (SELECT mid FROM modules WHERE module_pkg_name = ${ManagementActionExecution.shellQuote(modulePkgName)});"
        val runSql = "$sqlite3 ${ManagementActionExecution.shellQuote(dbPath)} ${ManagementActionExecution.shellQuote(sql)}"
        val execResult = execution.runRaw(runSql)

        val scopes = execResult.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split("|")[0].trim() }
            .toList()

        return execution.success(
            message = "Module scope retrieved successfully.",
            observation = buildJsonObject {
                put("modulePkgName", modulePkgName)
                put("count", scopes.size)
                put("scope", buildJsonArray { scopes.forEach { add(JsonPrimitive(it)) } })
            },
            data = mapOf("count" to scopes.size.toString()),
            stdout = execResult.stdout,
        )
    }

    suspend fun scopeSet(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val modulePkgName = requireValidPackage(execution.request.arguments["module_pkg_name"])
        val targetPackagesRaw = requireNotNull(execution.request.arguments["target_packages"]?.trim()?.takeIf { it.isNotEmpty() }) { "target_packages is required." }
        val operation = execution.request.arguments["operation"]?.lowercase() ?: "add"
        val userId = execution.request.arguments["user_id"]?.toIntOrNull() ?: execution.userId

        val targetPackages = targetPackagesRaw.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        require(targetPackages.isNotEmpty()) { "At least one target package is required." }

        // Vector CLI 为唯一 mutate 机制：自动校验 staticScope 并不绕过缓存
        val cli = requireCliBinary(execution)
        val cliSubcmd = if (operation == "replace") "set" else if (operation == "remove") "rm" else "add"
        val cliArgs = mutableListOf(cli, "scope", cliSubcmd, modulePkgName)
        cliArgs.addAll(targetPackages.map { "$it/$userId" })

        val cliRes = execution.run(*cliArgs.toTypedArray())
        val accepted = cliRes.success

        // 读回验证：调用 cli scope ls 核实条目
        val listRes = execution.run(cli, "scope", "ls", modulePkgName)
        val currentScopeText = listRes.stdout

        val verified = if (operation == "remove") {
            accepted && targetPackages.none { currentScopeText.contains(it) }
        } else {
            accepted && targetPackages.all { currentScopeText.contains(it) }
        }

        val obs = ManagementActionExecution.observation(
            before = JsonNull,
            after = JsonPrimitive(verified),
            expected = JsonPrimitive(true),
            commandAccepted = accepted,
            verified = verified,
            scope = "xposed_scope",
            userId = userId,
            details = buildJsonObject {
                put("modulePkgName", modulePkgName)
                put("operation", operation)
                put("cliOutput", cliRes.stdout.trim())
            }
        )

        return if (verified) {
            execution.success(
                message = "Module scope updated and verified via Vector CLI.",
                observation = obs,
                stdout = cliRes.stdout,
                exitCode = cliRes.exitCode,
            )
        } else {
            execution.failure(
                code = if (accepted) "scope_update_unconfirmed" else "scope_update_failed",
                message = if (accepted) "Scope command accepted by CLI, but target packages were not observed in scope list."
                else "Vector CLI scope update failed: ${cliRes.stderr.ifBlank { cliRes.stdout }.trim()}",
                diagnostic = cliRes,
                observation = obs,
            )
        }
    }

    suspend fun targetReload(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val targetPackage = requireNotNull(execution.request.arguments["target_package"]?.trim()?.takeIf { it.isNotEmpty() }) { "target_package is required." }

        return if (targetPackage in SYSTEM_TARGETS) {
            // 系统服务 / SystemUI: 仅触发框架级软重启 (Zygote restart)，绝不修改 RescueParty 救援计数
            val softRebootCmd = "setprop ctl.restart zygote"
            val res = execution.runRaw(softRebootCmd)
            execution.success(
                message = "Triggered framework soft reboot (Zygote restart) for system target '$targetPackage'.",
                observation = buildJsonObject {
                    put("targetPackage", targetPackage)
                    put("softReboot", true)
                    put("zygoteRestarted", res.success)
                },
                stdout = res.stdout,
                exitCode = res.exitCode,
            )
        } else {
            // 普通第三方应用: 执行 am force-stop，使下次启动时重新通过 Zygote 加载 Hook
            require(PACKAGE_NAME_REGEX.matches(targetPackage)) { "Invalid package name: $targetPackage" }
            val stopResult = execution.run("am", "force-stop", targetPackage)
            val pidofResult = execution.run("pidof", targetPackage)
            val processStopped = pidofResult.stdout.trim().isEmpty()

            val obs = buildJsonObject {
                put("targetPackage", targetPackage)
                put("forceStopAccepted", stopResult.success)
                put("processStopped", processStopped)
                put("reloadMechanism", "zygote_relaunch_on_next_start")
            }

            execution.success(
                message = "Target package '$targetPackage' force-stopped. The updated Xposed module will be loaded on its next launch.",
                observation = obs,
                stdout = stopResult.stdout,
                exitCode = stopResult.exitCode,
            )
        }
    }

    suspend fun verifyStatus(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val modulePkgName = requireValidPackage(execution.request.arguments["module_pkg_name"])
        val targetPackage = execution.request.arguments["target_package"]?.trim()

        // 1. 配置层读回: 优先经由 CLI 确认模块在配置中处于 enabled 状态
        val cli = findCliBinary(execution)
        val configVerified = if (cli != null) {
            val lsModules = execution.run(cli, "modules", "ls")
            val isEnabled = lsModules.stdout.lineSequence().any { it.contains(modulePkgName) && it.contains("enabled", ignoreCase = true) }
            val inScope = if (!targetPackage.isNullOrBlank()) {
                val lsScope = execution.run(cli, "scope", "ls", modulePkgName)
                lsScope.stdout.contains(targetPackage)
            } else true
            isEnabled && inScope
        } else {
            val db = findExistingDb(execution)
            val sqlite3 = findSqliteBinary(execution)
            if (db != null && sqlite3 != null) {
                isModuleEnabled(execution, db, sqlite3, modulePkgName) == true
            } else false
        }

        // 2. 行为层读回规范：不能使用任意 caller marker，必须检查目标真实 PID 与框架注入日志
        var hookVerified = false
        var hookEvidence: String? = null

        if (!targetPackage.isNullOrBlank()) {
            val pidResult = execution.run("pidof", targetPackage)
            val targetPid = pidResult.stdout.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }

            if (targetPid != null) {
                // 目标进程当前存活，精准检查该 PID 的日志或框架加载绑定记录
                val logResult = execution.run("logcat", "-d", "-b", "main", "-b", "system", "-t", "500")
                for (line in logResult.stdout.lineSequence()) {
                    val containsFramework = line.contains("LSPosed-Bridge", ignoreCase = true) ||
                            line.contains("Vector", ignoreCase = true) ||
                            line.contains("XposedBridge", ignoreCase = true)
                    val bindsTargetAndModule = line.contains(targetPid) || line.contains(targetPackage)
                    if (containsFramework && bindsTargetAndModule && line.contains(modulePkgName, ignoreCase = true)) {
                        hookVerified = true
                        hookEvidence = line.trim()
                        break
                    }
                }
            }
        }

        val status = when {
            configVerified && hookVerified -> "hook_active_verified"
            configVerified -> "config_verified_hook_unverified"
            else -> "config_unverified"
        }

        val explanation = when (status) {
            "hook_active_verified" -> "Module configuration verified and authentic framework hook injection confirmed in target process."
            "config_verified_hook_unverified" -> "Module configuration verified in LSPosed/Vector, but runtime hook execution proof was not observed in target process logs (target app may not have been launched or hooked methods not yet invoked)."
            else -> "Module configuration is unverified."
        }

        val obs = buildJsonObject {
            put("status", status)
            put("configVerified", configVerified)
            put("hookVerified", hookVerified)
            if (targetPackage != null) put("targetPackage", targetPackage)
            if (hookEvidence != null) put("hookEvidence", hookEvidence)
            put("explanation", explanation)
        }

        return execution.success(
            message = explanation,
            observation = obs,
            data = mapOf(
                "status" to status,
                "configVerified" to configVerified.toString(),
                "hookVerified" to hookVerified.toString(),
            ),
        )
    }

    private suspend fun requireCliBinary(execution: ManagementActionExecution): String {
        return findCliBinary(execution)
            ?: throw ManagementActionException("unsupported_action", "Vector CLI binary not found at /data/adb/lspd/cli. Direct SQL modification is blocked to prevent daemon cache divergence and bypassing staticScope restrictions.")
    }

    private suspend fun findCliBinary(execution: ManagementActionExecution): String? {
        for (path in CLI_CANDIDATE_PATHS) {
            val check = execution.run("test", "-x", path)
            if (check.success || check.exitCode == 0) return path
        }
        return null
    }

    private suspend fun resolveDbAndSqlite(execution: ManagementActionExecution): Pair<String, String> {
        val db = findExistingDb(execution)
            ?: throw ManagementActionException("unsupported_action", "LSPosed/Vector database not found. Candidates checked: ${DB_CANDIDATE_PATHS.joinToString(", ")}. Framework may not be installed or active.")
        val sqlite3 = findSqliteBinary(execution)
            ?: throw ManagementActionException("unsupported_action", "sqlite3 binary not available in PATH or standard system directories.")
        return Pair(db, sqlite3)
    }

    private suspend fun findExistingDb(execution: ManagementActionExecution): String? {
        for (path in DB_CANDIDATE_PATHS) {
            val check = execution.run("test", "-f", path)
            if (check.success || check.exitCode == 0) return path
        }
        return null
    }

    private suspend fun findSqliteBinary(execution: ManagementActionExecution): String? {
        val whichRes = execution.run("which", "sqlite3")
        if (whichRes.success && whichRes.stdout.trim().isNotEmpty()) {
            return whichRes.stdout.trim().lineSequence().first()
        }
        for (candidate in listOf("/system/bin/sqlite3", "/data/adb/ksu/bin/sqlite3", "/data/adb/ap/bin/sqlite3")) {
            val check = execution.run("test", "-x", candidate)
            if (check.success || check.exitCode == 0) return candidate
        }
        return null
    }

    private suspend fun isModuleEnabled(execution: ManagementActionExecution, dbPath: String, sqlite3: String, modulePkgName: String): Boolean? {
        val sql = "SELECT enabled FROM modules WHERE module_pkg_name = ${ManagementActionExecution.shellQuote(modulePkgName)};"
        val res = execution.runRaw("$sqlite3 ${ManagementActionExecution.shellQuote(dbPath)} ${ManagementActionExecution.shellQuote(sql)}")
        if (!res.success || res.stdout.isBlank()) return null
        return res.stdout.trim() == "1"
    }

    private fun requireValidPackage(pkg: String?): String {
        val name = requireNotNull(pkg?.trim()?.takeIf { it.isNotEmpty() }) { "module_pkg_name is required." }
        require(PACKAGE_NAME_REGEX.matches(name)) { "Invalid package name: $name" }
        return name
    }
}
