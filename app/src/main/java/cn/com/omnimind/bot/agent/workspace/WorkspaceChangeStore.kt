package cn.com.omnimind.bot.agent.workspace

import cn.com.omnimind.bot.agent.skill.SkillSuggestionRedactor
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/**
 * 工作区受管变更快照存储。
 *
 * 保证：
 * - 存放在应用 noBackupFilesDir/workspace-changes/<changeId>/，绝不放入可执行工作区。
 * - 单文件 5MiB 上限；全局快照总额 100MiB 上限，超限返回 unavailable，绝不 LRU 静默删除用户证据。
 * - 绝不跟随 symlink；工作区外路径、目录、敏感文件或命中凭据的内容拒绝快照。
 */
class WorkspaceChangeStore(
    private val baseDir: File
) {
    companion object {
        const val MAX_SINGLE_FILE_BYTES = 5 * 1024 * 1024L // 5 MiB
        const val GLOBAL_SNAPSHOT_QUOTA_BYTES = 100 * 1024 * 1024L // 100 MiB

        private val SENSITIVE_SEGMENTS = setOf(".git", ".omnibot", "credentials", "secrets")
        private val SENSITIVE_EXTENSIONS = setOf("pem", "key", "p12", "pfx", "jks", "keystore")
        private val SENSITIVE_BASENAMES = setOf(".env", "id_rsa", "id_ed25519")
    }

    init {
        baseDir.mkdirs()
    }

    sealed class Eligibility {
        object Eligible : Eligibility()
        data class Ineligible(val reason: String) : Eligibility()
    }

    data class SnapshotSaveResult(
        val success: Boolean,
        val snapshotBytes: Long,
        val beforeManifestJson: String,
        val afterManifestJson: String,
        val unavailableReason: String? = null
    )

    /**
     * 计算文本或字节数组的 SHA-256 十六进制哈希。
     */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun sha256(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    /**
     * 校验文件是否具备快照资格（路径、大小、敏感度与链接检查）。
     */
    fun checkEligibility(
        file: File,
        workspaceRoot: File,
        currentGlobalBytes: Long = 0L
    ): Eligibility {
        val canonicalRoot = runCatching { workspaceRoot.canonicalFile }.getOrDefault(workspaceRoot)
        val canonicalFile = runCatching { file.canonicalFile }.getOrDefault(file)

        // 1. 检查是否在工作区内
        if (!canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            return Eligibility.Ineligible("outside_workspace")
        }

        // 2. 检查符号链接（拒绝跟随 symlink）
        if (Files.isSymbolicLink(file.toPath()) || Files.isSymbolicLink(canonicalFile.toPath())) {
            return Eligibility.Ineligible("symlink_rejected")
        }

        // 3. 检查敏感路径（路径段、扩展名、basename）
        val relativePath = canonicalRoot.toPath().relativize(canonicalFile.toPath()).toString().replace('\\', '/')
        val pathSegments = relativePath.split('/').map { it.lowercase(Locale.US) }
        if (pathSegments.any { it in SENSITIVE_SEGMENTS }) {
            return Eligibility.Ineligible("sensitive_path")
        }

        val fileName = canonicalFile.name.lowercase(Locale.US)
        if (fileName in SENSITIVE_BASENAMES || fileName.startsWith(".env.")) {
            return Eligibility.Ineligible("sensitive_path")
        }

        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in SENSITIVE_EXTENSIONS) {
            return Eligibility.Ineligible("sensitive_path")
        }

        // 4. 若文件存在，检查普通文件、大小与内容凭据
        if (file.exists()) {
            if (file.isDirectory) {
                return Eligibility.Ineligible("directory_unsupported")
            }
            if (!file.isFile) {
                return Eligibility.Ineligible("not_a_regular_file")
            }
            val len = file.length()
            if (len > MAX_SINGLE_FILE_BYTES) {
                return Eligibility.Ineligible("file_too_large")
            }
            if (currentGlobalBytes + len > GLOBAL_SNAPSHOT_QUOTA_BYTES) {
                return Eligibility.Ineligible("quota_exceeded")
            }

            // 检查文本内容是否包含明文凭据
            if (isTextFile(file)) {
                val sampleText = runCatching { file.readText() }.getOrNull().orEmpty()
                val sanitized = SkillSuggestionRedactor.sanitize(sampleText)
                if (sanitized.sensitiveDetected) {
                    return Eligibility.Ineligible("sensitive_content")
                }
            }
        }

        return Eligibility.Eligible
    }

    fun getSnapshotDir(changeId: String): File = File(baseDir, changeId)

    fun getSnapshotFile(changeId: String, snapshotFileName: String): File =
        File(getSnapshotDir(changeId), snapshotFileName)

    /**
     * 保存变更前的原文件快照。
     */
    fun saveBeforeFile(
        changeId: String,
        sourceFile: File,
        snapshotName: String
    ): Pair<File, Long>? {
        if (!sourceFile.exists() || !sourceFile.isFile) return null
        val changeDir = getSnapshotDir(changeId).apply { mkdirs() }
        val destFile = File(changeDir, snapshotName)
        return runCatching {
            sourceFile.copyTo(destFile, overwrite = true)
            destFile to destFile.length()
        }.getOrNull()
    }

    /**
     * 清理快照目录。返回释放的字节数。
     */
    fun deleteSnapshot(changeId: String): Long {
        val dir = getSnapshotDir(changeId)
        if (!dir.exists()) return 0L
        val bytes = calculateDirectorySize(dir)
        dir.deleteRecursively()
        return bytes
    }

    fun calculateTotalSnapshotBytes(): Long = calculateDirectorySize(baseDir)

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            total += if (f.isDirectory) calculateDirectorySize(f) else f.length()
        }
        return total
    }

    private fun isTextFile(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.US)
        val textExtensions = setOf(
            "txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx",
            "kt", "java", "py", "rs", "go", "c", "cpp", "h", "hpp", "sh", "bat", "ps1",
            "yaml", "yml", "toml", "ini", "conf", "gradle", "properties", "sql", "dart"
        )
        return ext in textExtensions
    }
}
