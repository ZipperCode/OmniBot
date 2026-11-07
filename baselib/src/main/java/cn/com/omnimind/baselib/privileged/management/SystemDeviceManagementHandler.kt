package cn.com.omnimind.baselib.privileged.management

import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * F15: 系统日志与设备启动/分区/内核/SELinux操作。
 * 通过现有真实 Root 路径执行，不伪造所有设备支持；遇到不支持的设备条件明确报告具体原因。
 */
internal object SystemDeviceManagementHandler {
    private const val MAX_LOG_LINES = 2000
    private const val DEFAULT_LOG_LINES = 200

    suspend fun logcat(execution: ManagementActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val buffer = args["buffer"]?.trim() ?: "main"
        val lines = (args["lines"]?.toIntOrNull() ?: DEFAULT_LOG_LINES).coerceIn(1, MAX_LOG_LINES)
        val tag = args["tag"]?.trim()
        val priority = args["priority"]?.trim()?.uppercase()
        val filter = args["filter"]?.trim()

        val cmd = mutableListOf("logcat", "-d", "-b", buffer, "-t", lines.toString())
        if (!tag.isNullOrBlank()) {
            val filterSpec = if (!priority.isNullOrBlank()) "$tag:$priority" else tag
            cmd += "-s"
            cmd += filterSpec
        }

        val logcatResult = execution.run(*cmd.toTypedArray())
        val outputLines = logcatResult.stdout.lineSequence()
            .filter { filter.isNullOrBlank() || it.contains(filter, ignoreCase = true) }
            .toList()

        return execution.success(
            message = "Logcat retrieved successfully.",
            observation = buildJsonObject {
                put("buffer", buffer)
                put("lineCount", outputLines.size)
            },
            data = mapOf("lineCount" to outputLines.size.toString()),
            stdout = outputLines.joinToString("\n"),
        )
    }

    suspend fun dmesg(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val lines = (execution.request.arguments["lines"]?.toIntOrNull() ?: DEFAULT_LOG_LINES).coerceIn(1, MAX_LOG_LINES)

        val dmesgResult = execution.run("dmesg")
        val linesOutput = if (dmesgResult.success) {
            dmesgResult.stdout.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines)
        } else {
            val kmsgResult = execution.runRaw("cat /proc/kmsg 2>/dev/null | head -n $lines")
            kmsgResult.stdout.lineSequence().filter { it.isNotBlank() }.toList()
        }

        return execution.success(
            message = "Kernel log (dmesg) retrieved successfully.",
            observation = buildJsonObject {
                put("lineCount", linesOutput.size)
            },
            data = mapOf("lineCount" to linesOutput.size.toString()),
            stdout = linesOutput.joinToString("\n"),
        )
    }

    suspend fun reboot(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val mode = args["mode"]?.trim()?.lowercase() ?: "normal"
        val reason = args["reason"]?.trim()
        val targetPackage = args["target_package"]?.trim()

        val rebootCmd = when (mode) {
            "normal" -> listOf("reboot")
            "recovery" -> listOf("reboot", "recovery")
            "bootloader", "fastboot" -> listOf("reboot", "bootloader")
            "edl" -> listOf("reboot", "edl")
            "userspace" -> listOf("reboot", "userspace")
            else -> throw ManagementActionException("invalid_arguments", "Unsupported reboot mode: '$mode'. Supported: normal, recovery, bootloader, edl, userspace.")
        }

        val rebootResult = execution.run(*rebootCmd.toTypedArray())
        val accepted = rebootResult.success || rebootResult.exitCode == 0

        // F17 关键约定：重启命令发出后系统进入重启流程，当前会话不可读回确认，
        // 明确返回 verified = false，status = 'reboot_dispatched_unverified'，不伪造 verified = true。
        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive("running"),
            after = JsonNull,
            expected = JsonPrimitive("rebooted"),
            commandAccepted = accepted,
            verified = false,
            scope = "system_lifecycle",
            details = buildJsonObject {
                put("mode", mode)
                put("status", "reboot_dispatched_unverified")
                if (reason != null) put("reason", reason)
                if (targetPackage != null) put("targetPackage", targetPackage)
            }
        )

        return if (accepted) {
            execution.success(
                message = "Reboot command ($mode) dispatched to system. Verification requires post-reboot health check.",
                observation = obs,
                data = mapOf(
                    "mode" to mode,
                    "status" to "reboot_dispatched_unverified",
                ),
                stdout = rebootResult.stdout,
                exitCode = rebootResult.exitCode,
            )
        } else {
            execution.failure(
                code = "reboot_failed",
                message = "Reboot command failed: ${rebootResult.stderr.ifBlank { rebootResult.stdout }.trim()}",
                diagnostic = rebootResult,
                observation = obs,
            )
        }
    }

    suspend fun rebootStatus(execution: ManagementActionExecution): PrivilegedResult {
        val uptimeRes = execution.run("cat", "/proc/uptime")
        val bootIdRes = execution.run("cat", "/proc/sys/kernel/random/boot_id")
        val slotRes = execution.run("getprop", "ro.boot.slot_suffix")

        val uptimeSeconds = uptimeRes.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toDoubleOrNull() ?: 0.0
        val bootId = bootIdRes.stdout.trim()
        val slot = slotRes.stdout.trim().removePrefix("_").ifEmpty { "none" }

        return execution.success(
            message = "System boot and uptime status read successfully.",
            observation = buildJsonObject {
                put("uptimeSeconds", uptimeSeconds)
                put("bootId", bootId)
                put("activeSlot", slot)
                put("systemReady", uptimeSeconds > 0)
            },
            data = mapOf(
                "uptimeSeconds" to uptimeSeconds.toString(),
                "bootId" to bootId,
                "activeSlot" to slot,
            ),
            stdout = uptimeRes.stdout,
        )
    }

    suspend fun selinuxGet(execution: ManagementActionExecution): PrivilegedResult {
        val result = execution.run("getenforce")
        val mode = result.stdout.trim().ifEmpty { "Unknown" }

        return execution.success(
            message = "SELinux status read successfully: $mode",
            observation = buildJsonObject {
                put("mode", mode)
            },
            data = mapOf("mode" to mode),
            stdout = result.stdout,
        )
    }

    suspend fun selinuxSet(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val modeArg = args["mode"]?.lowercase() ?: args["enforcing"]?.lowercase()
        val targetEnforcing = when (modeArg) {
            "enforcing", "true", "1" -> true
            "permissive", "false", "0" -> false
            else -> throw ManagementActionException("invalid_arguments", "Invalid SELinux mode: '$modeArg'. Must be 'enforcing' or 'permissive'.")
        }

        val expectedName = if (targetEnforcing) "Enforcing" else "Permissive"
        val beforeResult = execution.run("getenforce")
        val beforeMode = beforeResult.stdout.trim()

        val setArg = if (targetEnforcing) "1" else "0"
        val setResult = execution.run("setenforce", setArg)

        val afterResult = execution.run("getenforce")
        val afterMode = afterResult.stdout.trim()

        val verified = afterMode.equals(expectedName, ignoreCase = true)

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeMode),
            after = JsonPrimitive(afterMode),
            expected = JsonPrimitive(expectedName),
            commandAccepted = setResult.success,
            verified = verified,
            scope = "selinux",
            details = buildJsonObject {
                put("targetMode", expectedName)
            }
        )

        return if (verified) {
            execution.success(
                message = "SELinux mode set and verified to $afterMode.",
                observation = obs,
                data = mapOf("mode" to afterMode),
                stdout = setResult.stdout,
                exitCode = setResult.exitCode,
            )
        } else if (!targetEnforcing && afterMode.equals("Enforcing", ignoreCase = true)) {
            // Samsung KNOX 或安全加固内核通常锁死 Enforcing，拒绝切换为 Permissive
            execution.failure(
                code = "selinux_locked_enforcing",
                message = "Kernel rejected permissive mode. SELinux is locked in Enforcing mode by kernel configuration (e.g. KNOX/strict vendor kernel).",
                diagnostic = setResult,
                observation = obs,
            )
        } else {
            execution.failure(
                code = "state_change_unconfirmed",
                message = "setenforce command ran, but getenforce still returned '$afterMode' instead of '$expectedName'.",
                diagnostic = setResult,
                observation = obs,
            )
        }
    }

    suspend fun getprop(execution: ManagementActionExecution): PrivilegedResult {
        val key = requireNotNull(execution.request.arguments["key"]?.trim()?.takeIf { it.isNotEmpty() }) { "key is required." }
        require(Regex("^[a-zA-Z0-9_.-]+$").matches(key)) { "Invalid sysprop key: $key" }

        val res = execution.run("getprop", key)
        val value = res.stdout.trim()

        return execution.success(
            message = "System property retrieved.",
            observation = buildJsonObject {
                put("key", key)
                put("value", value)
            },
            data = mapOf("key" to key, "value" to value),
            stdout = res.stdout,
        )
    }

    suspend fun setprop(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val key = requireNotNull(execution.request.arguments["key"]?.trim()?.takeIf { it.isNotEmpty() }) { "key is required." }
        val value = requireNotNull(execution.request.arguments["value"]?.trim()) { "value is required." }
        require(Regex("^[a-zA-Z0-9_.-]+$").matches(key)) { "Invalid sysprop key: $key" }

        val beforeRes = execution.run("getprop", key)
        val beforeVal = beforeRes.stdout.trim()

        val setRes = execution.run("setprop", key, value)
        val afterRes = execution.run("getprop", key)
        val afterVal = afterRes.stdout.trim()

        val verified = afterVal == value

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeVal),
            after = JsonPrimitive(afterVal),
            expected = JsonPrimitive(value),
            commandAccepted = setRes.success,
            verified = verified,
            scope = "sysprop",
            details = buildJsonObject {
                put("key", key)
                put("value", value)
            }
        )

        return if (verified) {
            execution.success(
                message = "System property '$key' set and verified to '$value'.",
                observation = obs,
                stdout = setRes.stdout,
                exitCode = setRes.exitCode,
            )
        } else {
            execution.failure(
                code = "setprop_unconfirmed",
                message = "setprop command ran, but property '$key' is '$afterVal' instead of '$value'.",
                diagnostic = setRes,
                observation = obs,
            )
        }
    }

    suspend fun uname(execution: ManagementActionExecution): PrivilegedResult {
        val unameRes = execution.run("uname", "-a")
        val versionRes = execution.run("cat", "/proc/version")

        val unameStr = unameRes.stdout.trim()
        val versionStr = versionRes.stdout.trim()

        return execution.success(
            message = "Kernel information read successfully.",
            observation = buildJsonObject {
                put("uname", unameStr)
                put("kernelVersion", versionStr)
            },
            data = mapOf(
                "uname" to unameStr,
                "version" to versionStr,
            ),
            stdout = unameStr,
        )
    }

    suspend fun partitions(execution: ManagementActionExecution): PrivilegedResult {
        val slotRes = execution.run("getprop", "ro.boot.slot_suffix")
        val slot = slotRes.stdout.trim().removePrefix("_").ifEmpty { "none" }

        val partitionsRes = execution.run("cat", "/proc/partitions")

        // 探测 by-name 目录
        val byNameDir = when {
            execution.run("test", "-d", "/dev/block/by-name").let { it.success || it.exitCode == 0 } -> "/dev/block/by-name"
            execution.run("test", "-d", "/dev/block/bootdevice/by-name").let { it.success || it.exitCode == 0 } -> "/dev/block/bootdevice/by-name"
            else -> null
        }

        val namedPartitions = if (byNameDir != null) {
            val lsRes = execution.run("ls", "-1", byNameDir)
            lsRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        } else emptyList()

        return execution.success(
            message = "Partition table and block device info read.",
            observation = buildJsonObject {
                put("activeSlot", slot)
                put("byNameDir", byNameDir ?: "not_found")
                put("namedPartitionCount", namedPartitions.size)
                put("partitions", buildJsonArray { namedPartitions.take(50).forEach { add(JsonPrimitive(it)) } })
            },
            data = mapOf(
                "activeSlot" to slot,
                "namedPartitionCount" to namedPartitions.size.toString(),
            ),
            stdout = partitionsRes.stdout,
        )
    }

    suspend fun mounts(execution: ManagementActionExecution): PrivilegedResult {
        val dfRes = execution.run("df", "-h")
        val mountsRes = execution.run("cat", "/proc/mounts")

        return execution.success(
            message = "Filesystem mounts and disk usage read successfully.",
            observation = buildJsonObject {
                put("df", dfRes.stdout.trim())
            },
            stdout = dfRes.stdout,
        )
    }

    suspend fun partitionBackup(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val partitionName = requireNotNull(args["partition_name"]?.trim()?.takeIf { it.isNotEmpty() }) { "partition_name is required." }
        val destPath = requireNotNull(args["destination_path"]?.trim()?.takeIf { it.isNotEmpty() }) { "destination_path is required." }
        require(destPath.startsWith("/")) { "destination_path must be an absolute path." }

        val blockDevice = resolveBlockDevice(execution, partitionName)
            ?: throw ManagementActionException("unsupported_action", "Partition '$partitionName' was not found in /dev/block/by-name on this device.")

        val parentDir = File(destPath).parent ?: "/"
        execution.run("mkdir", "-p", parentDir)

        val ddCmd = "dd if=${ManagementActionExecution.shellQuote(blockDevice)} of=${ManagementActionExecution.shellQuote(destPath)} bs=4M"
        val ddRes = execution.runRaw(ddCmd)
        if (!ddRes.success) {
            throw ManagementActionException("backup_failed", "dd backup failed: ${ddRes.stderr.ifBlank { ddRes.stdout }.trim()}", ddRes)
        }

        val statRes = execution.run("stat", "-c", "%s", destPath)
        val sizeBytes = statRes.stdout.trim().toLongOrNull() ?: 0L
        if (sizeBytes <= 0) {
            throw ManagementActionException("backup_failed", "Backup image was created but is empty.")
        }

        val shaRes = execution.run("sha256sum", destPath)
        val sha256 = shaRes.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

        val obs = ManagementActionExecution.observation(
            before = JsonNull,
            after = JsonPrimitive(sizeBytes),
            expected = JsonPrimitive(true),
            commandAccepted = ddRes.success,
            verified = sizeBytes > 0 && sha256.isNotBlank(),
            scope = "partition_backup",
            details = buildJsonObject {
                put("partitionName", partitionName)
                put("blockDevice", blockDevice)
                put("destinationPath", destPath)
                put("sizeBytes", sizeBytes)
                put("sha256", sha256)
            }
        )

        return execution.success(
            message = "Partition '$partitionName' backed up and verified successfully.",
            observation = obs,
            data = mapOf(
                "partitionName" to partitionName,
                "destinationPath" to destPath,
                "sizeBytes" to sizeBytes.toString(),
                "sha256" to sha256,
            ),
            stdout = ddRes.stdout,
            exitCode = ddRes.exitCode,
        )
    }

    suspend fun partitionFlash(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val imagePath = requireNotNull(args["image_path"]?.trim()?.takeIf { it.isNotEmpty() }) { "image_path is required." }
        val partitionName = requireNotNull(args["partition_name"]?.trim()?.takeIf { it.isNotEmpty() }) { "partition_name is required." }

        // 1. 验证目标分区块设备物理存在
        val blockDevice = resolveBlockDevice(execution, partitionName)
            ?: throw ManagementActionException("unsupported_action", "Partition '$partitionName' was not found in /dev/block/by-name on this device.")

        // 2. 验证待刷入镜像存在、非空，获取尺寸与源文件 SHA256
        val statImage = execution.run("stat", "-c", "%s", imagePath)
        val imageSize = statImage.stdout.trim().toLongOrNull()
        if (imageSize == null || imageSize <= 0) {
            throw ManagementActionException("image_invalid", "Image file not found or empty at: $imagePath")
        }

        val shaSource = execution.run("sha256sum", imagePath)
        val sourceHash = shaSource.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

        // 3. 校验镜像尺寸是否超出目标分区块设备容量
        val blockSizeRes = execution.run("blockdev", "--getsize64", blockDevice)
        val partitionBytes = blockSizeRes.stdout.trim().toLongOrNull()
        if (partitionBytes != null && imageSize > partitionBytes) {
            throw ManagementActionException("image_too_large", "Image size ($imageSize bytes) exceeds target partition capacity ($partitionBytes bytes): $partitionName")
        }

        // 4. 执行写入前实时检查租约权限
        execution.checkAccess()

        // 5. 使用 dd 执行底层块写入并刷盘
        val flashCmd = "dd if=${ManagementActionExecution.shellQuote(imagePath)} of=${ManagementActionExecution.shellQuote(blockDevice)} bs=4M conv=fsync"
        val flashRes = execution.runRaw(flashCmd)
        val accepted = flashRes.success

        // 6. 写后真实读回比对：使用 cmp 校验已写入块设备的前 imageSize 字节与原镜像完全一致
        val verifyRes = if (accepted) {
            execution.runRaw("cmp -n $imageSize ${ManagementActionExecution.shellQuote(imagePath)} ${ManagementActionExecution.shellQuote(blockDevice)}")
        } else null

        val verified = accepted && verifyRes?.success == true && (verifyRes.exitCode == 0)

        val obs = ManagementActionExecution.observation(
            before = JsonNull,
            after = JsonPrimitive(imageSize),
            expected = JsonPrimitive(true),
            commandAccepted = accepted,
            verified = verified,
            scope = "partition_flash",
            details = buildJsonObject {
                put("partitionName", partitionName)
                put("blockDevice", blockDevice)
                put("imagePath", imagePath)
                put("imageSize", imageSize)
                put("sourceSha256", sourceHash)
                if (partitionBytes != null) put("partitionBytes", partitionBytes)
                put("byteComparisonMatched", verified)
            }
        )

        return if (verified) {
            execution.success(
                message = "Partition '$partitionName' flashed and written bytes verified identical via cmp.",
                observation = obs,
                data = mapOf(
                    "partitionName" to partitionName,
                    "imageSize" to imageSize.toString(),
                    "sha256" to sourceHash,
                ),
                stdout = flashRes.stdout,
                exitCode = flashRes.exitCode,
            )
        } else {
            execution.failure(
                code = if (!accepted) "flash_write_failed" else "flash_verification_mismatch",
                message = if (!accepted) "dd write to $blockDevice failed: ${flashRes.stderr.ifBlank { flashRes.stdout }.trim()}"
                else "Flash completed, but byte-level comparison (cmp) detected a mismatch with source image.",
                diagnostic = verifyRes ?: flashRes,
                observation = obs,
            )
        }
    }

    private suspend fun resolveBlockDevice(execution: ManagementActionExecution, partitionName: String): String? {
        val slotRes = execution.run("getprop", "ro.boot.slot_suffix")
        val slot = slotRes.stdout.trim() // e.g. "_a" or "_b" or ""

        val candidates = mutableListOf<String>()
        val baseDirs = listOf("/dev/block/by-name", "/dev/block/bootdevice/by-name")

        for (base in baseDirs) {
            if (slot.isNotEmpty()) {
                candidates += "$base/$partitionName$slot"
            }
            candidates += "$base/$partitionName"
        }

        for (candidate in candidates) {
            val check = execution.run("test", "-b", candidate)
            if (check.success || check.exitCode == 0) return candidate
        }
        return null
    }
}
