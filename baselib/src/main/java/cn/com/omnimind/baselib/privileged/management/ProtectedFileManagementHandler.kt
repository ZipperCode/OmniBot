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
 * F07: 受保护文件读写、属主/权限变更与真实 tar 备份恢复校验。
 */
internal object ProtectedFileManagementHandler {
    private const val DEFAULT_MAX_BYTES = 256 * 1024
    private const val ABSOLUTE_MAX_BYTES = 4 * 1024 * 1024

    suspend fun read(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val path = requireValidPath(args["path"])
        val encoding = args["encoding"]?.lowercase() ?: "utf8"
        val maxBytes = (args["max_bytes"]?.toIntOrNull() ?: DEFAULT_MAX_BYTES).coerceIn(1, ABSOLUTE_MAX_BYTES)

        val stat = readStat(execution, path)
            ?: throw ManagementActionException("file_not_found", "Target file does not exist: $path")

        if (stat.size > maxBytes) {
            throw ManagementActionException("file_too_large", "File size (${stat.size} bytes) exceeds limit ($maxBytes bytes): $path")
        }

        val readResult = if (encoding == "base64") {
            execution.run("base64", path)
        } else {
            execution.run("cat", path)
        }

        if (!readResult.success) {
            throw ManagementActionException("file_read_failed", "Failed to read file: ${readResult.stderr.ifBlank { readResult.stdout }.trim()}", readResult)
        }

        val obs = buildJsonObject {
            put("path", path)
            put("size", stat.size)
            put("mode", stat.mode)
            put("uid", stat.uid)
            put("gid", stat.gid)
            put("mtime", stat.mtime)
            put("fileType", stat.fileType)
            put("encoding", encoding)
        }

        return execution.success(
            message = "Protected file read successfully.",
            observation = obs,
            data = mapOf(
                "path" to path,
                "size" to stat.size.toString(),
                "mode" to stat.mode,
                "encoding" to encoding,
            ),
            stdout = readResult.stdout,
        )
    }

    suspend fun write(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val path = requireValidPath(args["path"])
        val content = requireNotNull(args["content"]) { "content is required." }
        val encoding = args["encoding"]?.lowercase() ?: "utf8"
        val mode = args["mode"]?.trim()
        val owner = args["owner"]?.trim()
        val createDirs = args["create_dirs"]?.lowercase() == "true"

        val beforeStat = readStat(execution, path)
        val beforeSelinux = if (beforeStat != null) {
            val zRes = execution.run("stat", "-c", "%C", path)
            if (zRes.success && zRes.stdout.isNotBlank() && zRes.stdout.trim() != "?") zRes.stdout.trim() else null
        } else null

        // 保留原有的 mode、uid/gid，避免意外写坏受保护私有目录文件的权限
        val targetMode = mode ?: beforeStat?.mode
        val targetOwner = owner ?: (beforeStat?.let { "${it.uid}:${it.gid}" })
        val tempPath = "$path.tmp.omnibot.${System.nanoTime()}"

        val parentDir = File(path).parent ?: "/"
        if (createDirs) {
            val mkdirRes = execution.run("mkdir", "-p", parentDir)
            if (!mkdirRes.success && mkdirRes.exitCode != 0) {
                throw ManagementActionException("dir_create_failed", "Failed to create parent directory: $parentDir", mkdirRes)
            }
        }

        val writeCmd = if (encoding == "base64") {
            "printf '%s' ${ManagementActionExecution.shellQuote(content)} | base64 -d > ${ManagementActionExecution.shellQuote(tempPath)} && mv -f ${ManagementActionExecution.shellQuote(tempPath)} ${ManagementActionExecution.shellQuote(path)}"
        } else {
            "printf '%s' ${ManagementActionExecution.shellQuote(content)} > ${ManagementActionExecution.shellQuote(tempPath)} && mv -f ${ManagementActionExecution.shellQuote(tempPath)} ${ManagementActionExecution.shellQuote(path)}"
        }

        val writeResult = execution.runRaw(writeCmd)
        if (!writeResult.success) {
            execution.run("rm", "-f", tempPath)
            throw ManagementActionException("file_write_failed", "Failed to write file: ${writeResult.stderr.ifBlank { writeResult.stdout }.trim()}", writeResult)
        }

        // 严格检查每个 mutate 操作返回状态
        if (!targetMode.isNullOrBlank()) {
            val chmodRes = execution.run("chmod", targetMode, path)
            if (!chmodRes.success && chmodRes.exitCode != 0) {
                throw ManagementActionException("chmod_failed", "Failed to apply mode '$targetMode' on $path", chmodRes)
            }
        }
        if (!targetOwner.isNullOrBlank()) {
            val chownRes = execution.run("chown", targetOwner, path)
            if (!chownRes.success && chownRes.exitCode != 0) {
                throw ManagementActionException("chown_failed", "Failed to apply owner '$targetOwner' on $path", chownRes)
            }
        }
        if (beforeSelinux != null) {
            execution.run("chcon", beforeSelinux, path)
        }

        // 真实读回校验：不仅校验文件存在与权限，更校验写入内容 SHA256 哈希
        val afterStat = readStat(execution, path)
        val shaRes = execution.run("sha256sum", path)
        val actualHash = shaRes.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        val expectedHash = args["expected_sha256"]?.trim()

        val hashMatched = (expectedHash == null || actualHash.equals(expectedHash, ignoreCase = true)) && actualHash.isNotBlank()
        val modeMatched = targetMode == null || (afterStat != null && normalizeOctal(afterStat.mode) == normalizeOctal(targetMode))
        val ownerMatched = targetOwner == null || (afterStat != null && checkOwnerMatch(afterStat, targetOwner))
        val verified = afterStat != null && hashMatched && modeMatched && ownerMatched

        val obs = ManagementActionExecution.observation(
            before = beforeStat?.toJson(),
            after = afterStat?.toJson(),
            expected = JsonPrimitive(true),
            commandAccepted = writeResult.success,
            verified = verified,
            scope = "file",
            details = buildJsonObject {
                put("path", path)
                put("encoding", encoding)
                put("sha256", actualHash)
                if (targetMode != null) put("targetMode", targetMode)
                if (targetOwner != null) put("targetOwner", targetOwner)
                if (beforeSelinux != null) put("selinuxContext", beforeSelinux)
            }
        )

        return if (verified) {
            execution.success(
                message = "Protected file written, preserved metadata, and content hash verified.",
                observation = obs,
                data = mapOf(
                    "path" to path,
                    "size" to (afterStat?.size?.toString() ?: "0"),
                    "sha256" to actualHash,
                ),
                stdout = writeResult.stdout,
                exitCode = writeResult.exitCode,
            )
        } else {
            execution.failure(
                code = "file_write_unconfirmed",
                message = "File write completed, but hash or metadata verification failed.",
                diagnostic = writeResult,
                observation = obs,
            )
        }
    }

    suspend fun stat(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val path = requireValidPath(execution.request.arguments["path"])
        val stat = readStat(execution, path)
            ?: throw ManagementActionException("file_not_found", "Target file does not exist: $path")

        return execution.success(
            message = "File stat read successfully.",
            observation = stat.toJson(),
            data = mapOf(
                "path" to path,
                "size" to stat.size.toString(),
                "mode" to stat.mode,
                "uid" to stat.uid.toString(),
                "gid" to stat.gid.toString(),
                "mtime" to stat.mtime.toString(),
                "fileType" to stat.fileType,
            ),
        )
    }

    suspend fun chmod(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val path = requireValidPath(args["path"])
        val mode = requireNotNull(args["mode"]?.trim()?.takeIf { it.isNotEmpty() }) { "mode is required." }
        val recursive = args["recursive"]?.lowercase() == "true"

        val beforeStat = readStat(execution, path)
            ?: throw ManagementActionException("file_not_found", "Target file does not exist: $path")

        val cmd = mutableListOf("chmod")
        if (recursive) cmd += "-R"
        cmd += mode
        cmd += path

        val chmodResult = execution.run(*cmd.toTypedArray())
        val afterStat = readStat(execution, path)
        val verified = afterStat != null && normalizeOctal(afterStat.mode) == normalizeOctal(mode)

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(beforeStat.mode),
            after = afterStat?.mode?.let(::JsonPrimitive),
            expected = JsonPrimitive(mode),
            commandAccepted = chmodResult.success,
            verified = verified,
            scope = "file_permission",
            details = buildJsonObject {
                put("path", path)
                put("mode", mode)
                put("recursive", recursive)
            }
        )

        return if (verified) {
            execution.success(
                message = "File mode changed and verified.",
                observation = obs,
                stdout = chmodResult.stdout,
                exitCode = chmodResult.exitCode,
            )
        } else {
            execution.failure(
                code = "chmod_unconfirmed",
                message = "chmod command executed, but mode does not match expected '$mode'.",
                diagnostic = chmodResult,
                observation = obs,
            )
        }
    }

    suspend fun chown(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val path = requireValidPath(args["path"])
        val owner = requireNotNull(args["owner"]?.trim()?.takeIf { it.isNotEmpty() }) { "owner is required." }
        val recursive = args["recursive"]?.lowercase() == "true"

        val beforeStat = readStat(execution, path)
            ?: throw ManagementActionException("file_not_found", "Target file does not exist: $path")

        val cmd = mutableListOf("chown")
        if (recursive) cmd += "-R"
        cmd += owner
        cmd += path

        val chownResult = execution.run(*cmd.toTypedArray())
        val afterStat = readStat(execution, path)
        val verified = afterStat != null && checkOwnerMatch(afterStat, owner)

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive("${beforeStat.uid}:${beforeStat.gid}"),
            after = afterStat?.let { JsonPrimitive("${it.uid}:${it.gid}") },
            expected = JsonPrimitive(owner),
            commandAccepted = chownResult.success,
            verified = verified,
            scope = "file_ownership",
            details = buildJsonObject {
                put("path", path)
                put("owner", owner)
                put("recursive", recursive)
            }
        )

        return if (verified) {
            execution.success(
                message = "File ownership changed and verified.",
                observation = obs,
                stdout = chownResult.stdout,
                exitCode = chownResult.exitCode,
            )
        } else {
            execution.failure(
                code = "chown_unconfirmed",
                message = "chown command executed, but ownership verification failed.",
                diagnostic = chownResult,
                observation = obs,
            )
        }
    }

    suspend fun backupTar(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val archivePath = requireValidPath(args["archive_path"])
        val sourcePath = requireValidPath(args["source_path"])
        val compression = args["compression"]?.lowercase() ?: "gzip"

        val sourceStat = readStat(execution, sourcePath)
            ?: throw ManagementActionException("source_not_found", "Source file or directory does not exist: $sourcePath")

        val archiveParent = File(archivePath).parent ?: "/"
        execution.run("mkdir", "-p", archiveParent)

        val sourceFile = File(sourcePath)
        val parentDir = sourceFile.parent ?: "/"
        val baseName = sourceFile.name

        val tarFlags = if (compression == "gzip") "-czf" else "-cf"
        val tarCmd = "tar $tarFlags ${ManagementActionExecution.shellQuote(archivePath)} -C ${ManagementActionExecution.shellQuote(parentDir)} ${ManagementActionExecution.shellQuote(baseName)}"
        val tarResult = execution.runRaw(tarCmd)

        if (!tarResult.success) {
            throw ManagementActionException("tar_backup_failed", "tar backup command failed: ${tarResult.stderr.ifBlank { tarResult.stdout }.trim()}", tarResult)
        }

        val archiveStat = readStat(execution, archivePath)
        if (archiveStat == null || archiveStat.size <= 0) {
            throw ManagementActionException("archive_invalid", "Archive was created but is empty or missing.")
        }

        val listFlags = if (compression == "gzip") "-tzf" else "-tf"
        val tocResult = execution.runRaw("tar $listFlags ${ManagementActionExecution.shellQuote(archivePath)}")
        val tocLines = tocResult.stdout.lineSequence().filter { it.isNotBlank() }.toList()

        val checksumResult = execution.run("sha256sum", archivePath)
        val sha256 = checksumResult.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

        val verified = tocLines.isNotEmpty() && archiveStat.size > 0 && sha256.isNotBlank()

        val obs = ManagementActionExecution.observation(
            before = JsonPrimitive(sourceStat.size),
            after = JsonPrimitive(archiveStat.size),
            expected = JsonPrimitive(true),
            commandAccepted = tarResult.success,
            verified = verified,
            scope = "archive_backup",
            details = buildJsonObject {
                put("archivePath", archivePath)
                put("sourcePath", sourcePath)
                put("archiveSizeBytes", archiveStat.size)
                put("fileCount", tocLines.size)
                put("sha256", sha256)
                put("sampleEntries", buildJsonArray {
                    tocLines.take(10).forEach { add(JsonPrimitive(it)) }
                })
            }
        )

        return if (verified) {
            execution.success(
                message = "Archive backup created and contents verified.",
                observation = obs,
                data = mapOf(
                    "archivePath" to archivePath,
                    "sizeBytes" to archiveStat.size.toString(),
                    "fileCount" to tocLines.size.toString(),
                    "sha256" to sha256,
                ),
                stdout = tarResult.stdout,
                exitCode = tarResult.exitCode,
            )
        } else {
            execution.failure(
                code = "backup_verification_failed",
                message = "Archive creation completed, but TOC verification or checksum failed.",
                diagnostic = tarResult,
                observation = obs,
            )
        }
    }

    suspend fun restoreTar(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val args = execution.request.arguments
        val archivePath = requireValidPath(args["archive_path"])
        val destinationDir = requireValidPath(args["destination_dir"])
        val preserveOwner = args["preserve_owner"]?.lowercase() != "false"

        val archiveStat = readStat(execution, archivePath)
            ?: throw ManagementActionException("archive_not_found", "Archive does not exist: $archivePath")

        val isGzip = archivePath.endsWith(".gz") || archivePath.endsWith(".tgz")
        val rawFlags = if (isGzip) "-tzf" else "-tf"
        val verboseFlags = if (isGzip) "-tvzf" else "-tvf"

        // 1. 使用 tar -tf 读取原文成员名称（保留空格文件名，不进行 trim 切断）
        val rawNamesResult = execution.runRaw("tar $rawFlags ${ManagementActionExecution.shellQuote(archivePath)}")
        if (!rawNamesResult.success || rawNamesResult.exitCode != 0 || rawNamesResult.data["truncated"] == "true") {
            throw ManagementActionException("archive_corrupted", "无法完整读取归档成员清单: ${rawNamesResult.stderr.trim()}", rawNamesResult)
        }
        val rawText = rawNamesResult.stdout.removeSuffix("\n").removeSuffix("\r")
        val memberNames = if (rawText.isEmpty()) emptyList() else rawText.split('\n').map { it.removeSuffix("\r") }

        if (memberNames.isEmpty()) {
            throw ManagementActionException("archive_empty", "Tar 归档为空或无有效条目: $archivePath")
        }

        // 2. 使用 tar -tvf 详细读取类型首字符与链接箭头
        val verboseResult = execution.runRaw("tar $verboseFlags ${ManagementActionExecution.shellQuote(archivePath)}")
        if (!verboseResult.success || verboseResult.exitCode != 0 || verboseResult.data["truncated"] == "true") {
            throw ManagementActionException("archive_corrupted", "无法读取归档详细元数据: ${verboseResult.stderr.trim()}", verboseResult)
        }
        val verboseText = verboseResult.stdout.removeSuffix("\n").removeSuffix("\r")
        val verboseLines = if (verboseText.isEmpty()) emptyList() else verboseText.split('\n').map { it.removeSuffix("\r") }

        if (memberNames.size != verboseLines.size) {
            throw ManagementActionException("archive_corrupted", "Tar 归档元数据行数 (${verboseLines.size}) 与成员文件名行数 (${memberNames.size}) 不一致，拒绝解析。")
        }

        // 3. 安全审计与成员名单提取：仅允许普通文件与目录，拒绝所有链接与特殊设备，检查路径穿越
        val regularFiles = mutableListOf<String>()
        val directoryEntries = mutableListOf<String>()

        for (i in memberNames.indices) {
            val rawName = memberNames[i]
            val verboseLine = verboseLines[i]

            // 拒绝控制字符、ASCII < 32、反斜杠、空字符
            if (rawName.any { it.code < 32 || it.code == 127 || it == '\\' }) {
                throw ManagementActionException("tar_security_rejected", "Tar 成员路径包含非法控制字符或反斜杠: '$rawName'")
            }

            val typeChar = verboseLine.firstOrNull()
            if (typeChar != '-' && typeChar != 'd') {
                throw ManagementActionException("unsupported_entry_type", "Tar 归档包含不受支持的条目类型 '$typeChar' ($rawName)，仅允许普通文件与目录。")
            }
            if (verboseLine.contains("->") || verboseLine.contains(" link to ")) {
                throw ManagementActionException("unsupported_entry_type", "Tar 归档包含链接条目: '$rawName'，为安全起见严厉拒绝符号链接与硬链接。")
            }

            // 路径边界审计：拒绝以 / 开头
            if (rawName.startsWith("/")) {
                throw ManagementActionException("tar_security_rejected", "Tar 成员路径不得以绝对路径开头: '$rawName'")
            }

            // 根目录条目兼容：如果归档根项为 "./" 或 "."，且为目录类型 ('d')，作为合法根目录收录，不当作普通文件也不得抛异常
            if (rawName == "." || rawName == "./") {
                if (typeChar != 'd') {
                    throw ManagementActionException("unsupported_entry_type", "Tar 归档根条目 '$rawName' 必须为目录类型。")
                }
                directoryEntries.add("")
                continue
            }

            val cleanPath = if (rawName.startsWith("./")) rawName.substring(2) else rawName
            if (cleanPath.isEmpty()) {
                throw ManagementActionException("tar_security_rejected", "Tar 成员路径为空。")
            }

            val components = cleanPath.trimEnd('/').split('/')
            for (comp in components) {
                if (comp == ".." || comp == ".") {
                    throw ManagementActionException("tar_security_rejected", "Tar 成员路径包含非法穿越组件 '$comp': '$rawName'")
                }
            }

            if (typeChar == 'd' || cleanPath.endsWith("/")) {
                directoryEntries.add(cleanPath.trimEnd('/'))
            } else {
                regularFiles.add(cleanPath)
            }
        }

        if (regularFiles.isEmpty()) {
            throw ManagementActionException("archive_empty", "Tar 归档中无任何可恢复的普通文件: $archivePath")
        }

        // 4. 真实 root readlink -f 校验目标路径真实物理边界与已有符号链接碰撞
        val ensureDestDir = execution.run("mkdir", "-p", destinationDir)
        if (!ensureDestDir.success || ensureDestDir.exitCode != 0) {
            throw ManagementActionException("dir_create_failed", "无法创建目标目录: $destinationDir", ensureDestDir)
        }

        val readlinkDest = execution.run("readlink", "-f", destinationDir)
        if (!readlinkDest.success || readlinkDest.exitCode != 0 || readlinkDest.stdout.isBlank()) {
            throw ManagementActionException("canonical_boundary_failed", "无法通过 root readlink -f 解析目标目录绝对真实路径: $destinationDir", readlinkDest)
        }
        val canonicalDest = readlinkDest.stdout.trim()
        if (!canonicalDest.startsWith("/")) {
            throw ManagementActionException("canonical_boundary_failed", "目标目录解析出的真实路径不是合法的绝对路径: $canonicalDest")
        }

        // 逐条审查所有文件及其所有父级目录在现有目标树中是否已被软链接篡改越界
        val allPathsToCheck = (regularFiles + directoryEntries).filter { it.isNotEmpty() }.distinct()
        for (entryPath in allPathsToCheck) {
            val subParts = entryPath.split('/')
            var currentPrefix = canonicalDest
            for (part in subParts) {
                currentPrefix = "$currentPrefix/$part"
                val linkRes = execution.run("readlink", "-f", currentPrefix)
                if (linkRes.success && linkRes.exitCode == 0 && linkRes.stdout.isNotBlank()) {
                    val realTarget = linkRes.stdout.trim()
                    if (!realTarget.startsWith("$canonicalDest/") && realTarget != canonicalDest) {
                        throw ManagementActionException("tar_security_rejected", "目标路径 '$entryPath' 的现存祖先或节点已被符号链接指向目标边界之外: $realTarget")
                    }
                }
            }
        }

        // 5. 独占创建临时隔离暂存目录 (无 -p，结合 UUID，防止已有 temp 目录跟随)
        val randomId = java.util.UUID.randomUUID().toString()
        val stagingDir = "$canonicalDest.tmp.omnibot.$randomId"
        val mkdirStaging = execution.run("mkdir", stagingDir)
        if (!mkdirStaging.success || mkdirStaging.exitCode != 0) {
            throw ManagementActionException("dir_create_failed", "独占创建临时隔离暂存目录失败: $stagingDir", mkdirStaging)
        }

        try {
            val extractFlags = if (isGzip) {
                if (preserveOwner) "-xzpf" else "-xzf"
            } else {
                if (preserveOwner) "-xpf" else "-xf"
            }

            val extractCmd = "tar $extractFlags ${ManagementActionExecution.shellQuote(archivePath)} -C ${ManagementActionExecution.shellQuote(stagingDir)}"
            val extractResult = execution.runRaw(extractCmd)
            if (!extractResult.success || extractResult.exitCode != 0) {
                throw ManagementActionException("tar_restore_failed", "解压至暂存目录失败: ${extractResult.stderr.ifBlank { extractResult.stdout }.trim()}", extractResult)
            }

            val mkdirDest = execution.run("mkdir", "-p", destinationDir)
            if (!mkdirDest.success && mkdirDest.exitCode != 0) {
                throw ManagementActionException("dir_create_failed", "Failed to create destination directory: $destinationDir", mkdirDest)
            }

            // 受控拷入目标目录（保留 staging 用于逐文件比对，不得在 copy 时删除 staging）
            val copyCmd = "cp -a ${ManagementActionExecution.shellQuote(stagingDir)}/. ${ManagementActionExecution.shellQuote(canonicalDest)}/"
            val copyResult = execution.runRaw(copyCmd)
            if (!copyResult.success || copyResult.exitCode != 0) {
                throw ManagementActionException("tar_restore_failed", "从暂存目录同步至目标目录失败: ${copyResult.stderr.trim()}", copyResult)
            }

            // 6. 真实读回核验：逐一校验目标文件与 staging 文件的 64 位 SHA256 与 metadata
            val sha256Regex = Regex("^[a-fA-F0-9]{64}$")
            var verifiedCount = 0
            for (file in regularFiles) {
                val stagedPath = "$stagingDir/$file"
                val destPath = "$canonicalDest/$file"

                val stagedShaRes = execution.run("sha256sum", stagedPath)
                val destShaRes = execution.run("sha256sum", destPath)

                if (!stagedShaRes.success || stagedShaRes.exitCode != 0 || !destShaRes.success || destShaRes.exitCode != 0) {
                    break
                }

                val stagedHash = stagedShaRes.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                val destHash = destShaRes.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

                if (!sha256Regex.matches(stagedHash) || !stagedHash.equals(destHash, ignoreCase = true)) {
                    break
                }

                val stagedStat = readStat(execution, stagedPath)
                val destStat = readStat(execution, destPath)

                if (stagedStat == null || destStat == null || stagedStat.size != destStat.size) {
                    break
                }

                // mode 始终精确比对
                if (normalizeOctal(stagedStat.mode) != normalizeOctal(destStat.mode)) {
                    break
                }

                // uid/gid 依 preserveOwner 判定
                if (preserveOwner && (stagedStat.uid != destStat.uid || stagedStat.gid != destStat.gid)) {
                    break
                }

                verifiedCount++
            }

            val verified = extractResult.success && copyResult.success && (verifiedCount == regularFiles.size) && (verifiedCount > 0)

            val obs = ManagementActionExecution.observation(
                before = JsonPrimitive(archiveStat.size),
                after = JsonPrimitive(verifiedCount),
                expected = JsonPrimitive(regularFiles.size),
                commandAccepted = extractResult.success && copyResult.success,
                verified = verified,
                scope = "archive_restore",
                details = buildJsonObject {
                    put("archivePath", archivePath)
                    put("destinationDir", canonicalDest)
                    put("verifiedFileCount", verifiedCount)
                    put("expectedFileCount", regularFiles.size)
                }
            )

            return if (verified) {
                execution.success(
                    message = "归档安全恢复完成，所有文件物理哈希、尺寸与元数据均与暂存源逐项验证一致。",
                    observation = obs,
                    data = mapOf(
                        "destinationDir" to canonicalDest,
                        "restoredCount" to verifiedCount.toString(),
                    ),
                    stdout = extractResult.stdout,
                    exitCode = extractResult.exitCode,
                )
            } else {
                execution.failure(
                    code = "restore_unconfirmed",
                    message = "归档恢复完成，但仅核验通过 $verifiedCount / ${regularFiles.size} 个文件的哈希或元数据一致性。",
                    diagnostic = copyResult,
                    observation = obs,
                )
            }
        } finally {
            // finally 块中无条件清理临时暂存区，即使发生 CancellationException 也不吞并
            try {
                execution.run("rm", "-rf", stagingDir)
            } catch (_: Throwable) {
                // 清理异常不遮蔽主要异常
            }
        }
    }

    suspend fun verifyTar(execution: ManagementActionExecution): PrivilegedResult {
        execution.requireRoot()
        val archivePath = requireValidPath(execution.request.arguments["archive_path"])

        val archiveStat = readStat(execution, archivePath)
            ?: throw ManagementActionException("archive_not_found", "Archive does not exist: $archivePath")

        val isGzip = archivePath.endsWith(".gz") || archivePath.endsWith(".tgz")
        val listFlags = if (isGzip) "-tvzf" else "-tvf"
        val tocResult = execution.runRaw("tar $listFlags ${ManagementActionExecution.shellQuote(archivePath)}")
        if (!tocResult.success) {
            throw ManagementActionException("archive_corrupted", "Archive TOC verification failed: ${tocResult.stderr.trim()}", tocResult)
        }
        val lines = tocResult.stdout.lineSequence().filter { it.isNotBlank() }.toList()

        val checksumResult = execution.run("sha256sum", archivePath)
        val sha256 = checksumResult.stdout.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

        return execution.success(
            message = "Archive integrity verified.",
            observation = buildJsonObject {
                put("archivePath", archivePath)
                put("sizeBytes", archiveStat.size)
                put("fileCount", lines.size)
                put("sha256", sha256)
                put("entries", buildJsonArray {
                    lines.take(20).forEach { add(JsonPrimitive(it)) }
                })
            },
            data = mapOf(
                "sizeBytes" to archiveStat.size.toString(),
                "fileCount" to lines.size.toString(),
                "sha256" to sha256,
            ),
            stdout = tocResult.stdout,
        )
    }

    private suspend fun readStat(execution: ManagementActionExecution, path: String): FileStat? {
        val result = execution.run("stat", "-c", "%a %u %g %s %Y %F", path)
        if (!result.success || result.stdout.isBlank()) return null
        val parts = result.stdout.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 6) return null
        return FileStat(
            path = path,
            mode = parts[0],
            uid = parts[1].toIntOrNull() ?: 0,
            gid = parts[2].toIntOrNull() ?: 0,
            size = parts[3].toLongOrNull() ?: 0L,
            mtime = parts[4].toLongOrNull() ?: 0L,
            fileType = parts[5],
        )
    }

    private fun normalizeOctal(mode: String): String = mode.trim().trimStart('0').ifEmpty { "0" }

    private fun requireValidPath(path: String?): String {
        val trimmed = requireNotNull(path?.trim()?.takeIf { it.isNotEmpty() }) { "path is required." }
        require(trimmed.startsWith("/")) { "Path must be an absolute path: $trimmed" }
        return trimmed
    }
    private fun checkOwnerMatch(stat: FileStat, targetOwner: String): Boolean {
        val parts = targetOwner.trim().split(":")
        val targetUid = parts[0].toIntOrNull()
        val targetGid = parts.getOrNull(1)?.toIntOrNull()
        if (targetUid != null && stat.uid != targetUid) return false
        if (targetGid != null && stat.gid != targetGid) return false
        return true
    }


    internal data class FileStat(
        val path: String,
        val mode: String,
        val uid: Int,
        val gid: Int,
        val size: Long,
        val mtime: Long,
        val fileType: String,
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            put("path", path)
            put("mode", mode)
            put("uid", uid)
            put("gid", gid)
            put("size", size)
            put("mtime", mtime)
            put("fileType", fileType)
        }
    }
}
