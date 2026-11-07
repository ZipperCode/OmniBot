package cn.com.omnimind.baselib.privileged.management

import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.StringReader
import java.util.Properties

/**
 * F08: KernelSU 系列 (KernelSU / APatch / Magisk) 模块列表、安装、启停与结果验证。
 * 遵循 primary source: https://github.com/tiann/KernelSU (SHA: 019a3c0103a8f8d56675546016c4073d0fd18352)
 */
internal object KernelSuManagementHandler {
    private const val MODULES_DIR = "/data/adb/modules"
    private const val KSUD_BIN = "/data/adb/ksud"
    private val MODULE_ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

    suspend fun moduleList(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()

        val checkDir = execution.run("test", "-d", MODULES_DIR)
        if (!checkDir.success && checkDir.exitCode != 0) {
            return execution.success(
                message = "KernelSU modules directory does not exist or has no modules.",
                observation = buildJsonObject {
                    put("count", 0)
                    put("modules", buildJsonArray {})
                    put("modulesDir", MODULES_DIR)
                },
                data = mapOf("count" to "0"),
            )
        }

        val listResult = execution.run("ls", "-1", MODULES_DIR)
        val moduleIds = listResult.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(".") }
            .toList()

        val modulesArray = buildJsonArray {
            for (id in moduleIds) {
                val modInfo = readModuleMetadata(execution, id)
                if (modInfo != null) {
                    add(modInfo.toJson())
                }
            }
        }

        val ksudVersionResult = execution.run(KSUD_BIN, "-V")
        val ksudVersion = if (ksudVersionResult.success) ksudVersionResult.stdout.trim() else "not_installed"

        return execution.success(
            message = "KernelSU modules listed successfully.",
            observation = buildJsonObject {
                put("count", modulesArray.size)
                put("modules", modulesArray)
                put("ksudVersion", ksudVersion)
            },
            data = mapOf(
                "count" to modulesArray.size.toString(),
                "ksudVersion" to ksudVersion,
            ),
            stdout = listResult.stdout,
        )
    }

    suspend fun moduleInfo(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val moduleId = requireValidModuleId(execution.request.arguments["module_id"])

        val modInfo = readModuleMetadata(execution, moduleId)
            ?: throw ManagementActionException("module_not_found", "Module '$moduleId' was not found in $MODULES_DIR")

        return execution.success(
            message = "Module info retrieved successfully.",
            observation = modInfo.toJson(),
            data = mapOf(
                "id" to modInfo.id,
                "name" to modInfo.name,
                "version" to modInfo.version,
                "enabled" to modInfo.enabled.toString(),
                "pendingRemove" to modInfo.pendingRemove.toString(),
            ),
        )
    }

    suspend fun moduleEnable(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val moduleId = requireValidModuleId(execution.request.arguments["module_id"])
        val moduleDir = "$MODULES_DIR/$moduleId"

        val checkMod = execution.run("test", "-d", moduleDir)
        if (!checkMod.success && checkMod.exitCode != 0) {
            throw ManagementActionException("module_not_found", "Module '$moduleId' not found in $MODULES_DIR")
        }

        val disableFile = "$moduleDir/disable"
        val beforeDisabled = checkFileExists(execution, disableFile)

        val ksudAvailable = checkFileExists(execution, KSUD_BIN)
        val enableResult = if (ksudAvailable) {
            execution.run(KSUD_BIN, "module", "enable", moduleId)
        } else {
            execution.run("rm", "-f", disableFile)
        }

        val afterDisabled = checkFileExists(execution, disableFile)
        val verified = !afterDisabled

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(!beforeDisabled),
            after = JsonPrimitive(!afterDisabled),
            expected = JsonPrimitive(true),
            commandAccepted = enableResult.success,
            verified = verified,
            scope = "ksu_module",
            details = buildJsonObject {
                put("moduleId", moduleId)
                put("effectiveOnReboot", true)
            }
        )

        return if (verified) {
            execution.success(
                message = "KernelSU module '$moduleId' enabled and verified (takes effect after reboot or hot-reload).",
                observation = obs,
                stdout = enableResult.stdout,
                exitCode = enableResult.exitCode,
            )
        } else {
            execution.failure(
                code = "module_enable_unconfirmed",
                message = "Failed to enable module '$moduleId': disable flag still exists.",
                diagnostic = enableResult,
                observation = obs,
            )
        }
    }

    suspend fun moduleDisable(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val moduleId = requireValidModuleId(execution.request.arguments["module_id"])
        val moduleDir = "$MODULES_DIR/$moduleId"

        val checkMod = execution.run("test", "-d", moduleDir)
        if (!checkMod.success && checkMod.exitCode != 0) {
            throw ManagementActionException("module_not_found", "Module '$moduleId' not found in $MODULES_DIR")
        }

        val disableFile = "$moduleDir/disable"
        val beforeDisabled = checkFileExists(execution, disableFile)

        val ksudAvailable = checkFileExists(execution, KSUD_BIN)
        val disableResult = if (ksudAvailable) {
            execution.run(KSUD_BIN, "module", "disable", moduleId)
        } else {
            execution.run("touch", disableFile)
        }

        val afterDisabled = checkFileExists(execution, disableFile)
        val verified = afterDisabled

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(!beforeDisabled),
            after = JsonPrimitive(!afterDisabled),
            expected = JsonPrimitive(false),
            commandAccepted = disableResult.success,
            verified = verified,
            scope = "ksu_module",
            details = buildJsonObject {
                put("moduleId", moduleId)
                put("effectiveOnReboot", true)
            }
        )

        return if (verified) {
            execution.success(
                message = "KernelSU module '$moduleId' disabled and verified (takes effect after reboot).",
                observation = obs,
                stdout = disableResult.stdout,
                exitCode = disableResult.exitCode,
            )
        } else {
            execution.failure(
                code = "module_disable_unconfirmed",
                message = "Failed to disable module '$moduleId': disable flag was not created.",
                diagnostic = disableResult,
                observation = obs,
            )
        }
    }

    suspend fun moduleUninstall(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val moduleId = requireValidModuleId(execution.request.arguments["module_id"])
        val immediate = execution.request.arguments["immediate"]?.lowercase() == "true"
        val moduleDir = "$MODULES_DIR/$moduleId"

        val checkMod = execution.run("test", "-d", moduleDir)
        if (!checkMod.success && checkMod.exitCode != 0) {
            throw ManagementActionException("module_not_found", "Module '$moduleId' not found in $MODULES_DIR")
        }

        val removeFile = "$moduleDir/remove"
        val ksudAvailable = checkFileExists(execution, KSUD_BIN)

        val uninstallResult = if (immediate) {
            execution.run("rm", "-rf", moduleDir)
        } else if (ksudAvailable) {
            execution.run(KSUD_BIN, "module", "uninstall", moduleId)
        } else {
            execution.run("touch", removeFile)
        }

        val verified = if (immediate) {
            !checkFileExists(execution, moduleDir)
        } else {
            checkFileExists(execution, removeFile) || !checkFileExists(execution, moduleDir)
        }

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(true),
            after = JsonPrimitive(!verified),
            expected = JsonPrimitive(false),
            commandAccepted = uninstallResult.success,
            verified = verified,
            scope = "ksu_module",
            details = buildJsonObject {
                put("moduleId", moduleId)
                put("immediate", immediate)
                put("pendingRebootRemoval", !immediate)
            }
        )

        return if (verified) {
            execution.success(
                message = if (immediate) "KernelSU module '$moduleId' uninstalled immediately." else "KernelSU module '$moduleId' marked for removal on next reboot.",
                observation = obs,
                stdout = uninstallResult.stdout,
                exitCode = uninstallResult.exitCode,
            )
        } else {
            execution.failure(
                code = "module_uninstall_unconfirmed",
                message = "Uninstall command completed, but module state verification failed.",
                diagnostic = uninstallResult,
                observation = obs,
            )
        }
    }

    suspend fun moduleInstall(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val zipPath = requireNotNull(execution.request.arguments["zip_path"]?.trim()?.takeIf { it.isNotEmpty() }) { "zip_path is required." }

        // 1. 校验 ZIP 文件存在且非空
        val checkZip = execution.run("stat", "-c", "%s", zipPath)
        if (!checkZip.success || checkZip.stdout.trim().toLongOrNull() == null) {
            throw ManagementActionException("zip_not_found", "Module ZIP file not found or inaccessible: $zipPath", checkZip)
        }

        // 2. 预先读取 ZIP 内 module.prop 中的 id 与 version，杜绝盲目接受安装命令退出码
        val propRes = execution.runRaw("unzip -p ${ManagementActionExecution.shellQuote(zipPath)} module.prop 2>/dev/null")
        val expectedProps = if (propRes.success && propRes.stdout.isNotBlank()) {
            Properties().apply { load(StringReader(propRes.stdout)) }
        } else null

        val expectedId = expectedProps?.getProperty("id")
            ?: execution.request.arguments["module_id"]
            ?: throw ManagementActionException("zip_invalid_module_prop", "Module ZIP does not contain a valid module.prop with 'id'.")
        val expectedVersion = expectedProps?.getProperty("version")

        val ksudAvailable = checkFileExists(execution, KSUD_BIN)
        val installResult = if (ksudAvailable) {
            execution.run(KSUD_BIN, "module", "install", zipPath)
        } else {
            val magiskResult = execution.run("magisk", "--install-module", zipPath)
            if (magiskResult.success) magiskResult else {
                throw ManagementActionException("unsupported_action", "Neither ksud nor magisk CLI is available for zip module installation.", magiskResult)
            }
        }

        val accepted = installResult.success

        // 3. 真实读回验证：检查磁盘 /data/adb/modules/<id> 或待生效的 /data/adb/modules_update/<id> 是否包含一致的 module.prop
        val installedProp = readModuleMetadata(execution, expectedId)
            ?: readModuleMetadataFromPath(execution, "/data/adb/modules_update/$expectedId/module.prop", expectedId)

        val idMatched = installedProp != null && installedProp.id == expectedId
        val versionMatched = expectedVersion == null || (installedProp != null && installedProp.version == expectedVersion)
        val verified = accepted && idMatched && versionMatched

        val obs = ManagementActionExecution.observation(
            before = JsonNull,
            after = JsonPrimitive(installedProp != null),
            expected = JsonPrimitive(true),
            commandAccepted = accepted,
            verified = verified,
            scope = "ksu_module",
            details = buildJsonObject {
                put("zipPath", zipPath)
                put("expectedModuleId", expectedId)
                if (expectedVersion != null) put("expectedVersion", expectedVersion)
                if (installedProp != null) {
                    put("installedVersion", installedProp.version)
                    put("installedVersionCode", installedProp.versionCode)
                }
                put("output", installResult.stdout.trim())
            }
        )

        return if (verified) {
            execution.success(
                message = "KernelSU module '$expectedId' installed and verified on disk (reboot required to mount module overlay).",
                observation = obs,
                data = mapOf(
                    "moduleId" to expectedId,
                    "version" to (installedProp?.version ?: ""),
                ),
                stdout = installResult.stdout,
                stderr = installResult.stderr,
                exitCode = installResult.exitCode,
            )
        } else {
            execution.failure(
                code = if (accepted) "module_install_unconfirmed" else "module_install_failed",
                message = if (accepted) "Module install command succeeded, but disk files in $MODULES_DIR or modules_update do not match ZIP module.prop."
                else "Module installation failed: ${installResult.stderr.ifBlank { installResult.stdout }.trim()}",
                diagnostic = installResult,
                observation = obs,
            )
        }
    }

    private suspend fun readModuleMetadataFromPath(execution: ManagementActionExecution, propFile: String, moduleId: String): ModuleMetadata? {
        val catProp = execution.run("cat", propFile)
        if (!catProp.success) return null
        val props = Properties().apply { load(StringReader(catProp.stdout)) }
        return ModuleMetadata(
            id = props.getProperty("id", moduleId),
            name = props.getProperty("name", moduleId),
            version = props.getProperty("version", ""),
            versionCode = props.getProperty("versionCode", "0").toIntOrNull() ?: 0,
            author = props.getProperty("author", ""),
            description = props.getProperty("description", ""),
            enabled = true,
            pendingRemove = false,
            hotInstall = false,
        )
    }

    private suspend fun readModuleMetadata(execution: ManagementActionExecution, moduleId: String): ModuleMetadata? {
        val moduleDir = "$MODULES_DIR/$moduleId"
        val propFile = "$moduleDir/module.prop"
        val catProp = execution.run("cat", propFile)
        if (!catProp.success) return null

        val props = Properties().apply {
            load(StringReader(catProp.stdout))
        }

        val disabled = checkFileExists(execution, "$moduleDir/disable")
        val pendingRemove = checkFileExists(execution, "$moduleDir/remove")
        val hotInstall = checkFileExists(execution, "$moduleDir/.ksu_hot_install")

        return ModuleMetadata(
            id = props.getProperty("id", moduleId),
            name = props.getProperty("name", moduleId),
            version = props.getProperty("version", ""),
            versionCode = props.getProperty("versionCode", "0").toIntOrNull() ?: 0,
            author = props.getProperty("author", ""),
            description = props.getProperty("description", ""),
            enabled = !disabled,
            pendingRemove = pendingRemove,
            hotInstall = hotInstall,
        )
    }

    private suspend fun checkFileExists(execution: ManagementActionExecution, path: String): Boolean {
        val res = execution.run("test", "-e", path)
        return res.success || res.exitCode == 0
    }

    private fun requireValidModuleId(id: String?): String {
        val trimmed = requireNotNull(id?.trim()?.takeIf { it.isNotEmpty() }) { "module_id is required." }
        require(MODULE_ID_REGEX.matches(trimmed)) { "Invalid module ID: '$trimmed'. Must match ^[a-zA-Z][a-zA-Z0-9._-]+$" }
        return trimmed
    }

    internal data class ModuleMetadata(
        val id: String,
        val name: String,
        val version: String,
        val versionCode: Int,
        val author: String,
        val description: String,
        val enabled: Boolean,
        val pendingRemove: Boolean,
        val hotInstall: Boolean,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("id", id)
            put("name", name)
            put("version", version)
            put("versionCode", versionCode)
            put("author", author)
            put("description", description)
            put("enabled", enabled)
            put("pendingRemove", pendingRemove)
            put("hotInstall", hotInstall)
        }
    }
}
