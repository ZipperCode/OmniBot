package cn.com.omnimind.baselib.privileged.management

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedBackend
import cn.com.omnimind.baselib.privileged.PrivilegedRequest
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtectedFileManagementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun relativePathsAreRejectedEarlyForSecurity() = runBlocking {
        var commandRan = false
        val controller = PrivilegedManagementController(context, 0)
        val request = PrivilegedRequest("rel-path", PrivilegedManagementActions.ACTION_FILE_READ,
            arguments = mapOf("path" to "relative/path/file.txt"))

        val result = controller.execute(request, PrivilegedBackend.ROOT, { true }) {
            commandRan = true
            mockResult()
        }

        assertFalse(result.success)
        assertEquals("invalid_arguments", result.code)
        assertFalse("Command runner must not execute for non-absolute paths", commandRan)
    }

    @Test
    fun oversizedFileRejectsBeforeReadingContentIntoMemory() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val path = "/data/system/huge_dump.bin"

        // stat 返回 10MB，而 max_bytes 限制为 1MB
        val result = controller.execute(
            PrivilegedRequest("huge-file", PrivilegedManagementActions.ACTION_FILE_READ,
                arguments = mapOf("path" to path, "max_bytes" to "1048576")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") -> mockResult(stdout = "644 1000 1000 10485760 1700000000 regular file\n")
                c.contains("cat") -> throw AssertionError("cat must not be executed when file exceeds size limit")
                else -> mockResult()
            }
        }

        assertFalse(result.success)
        assertEquals("file_too_large", result.code)
    }

    @Test
    fun chmodVerifiesModeAndRejectsDiscrepancy() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val path = "/data/data/com.example.app/shared_prefs/app.xml"

        // 模拟 chmod 命令退出为 0，但底层文件系统挂载为只读或忽略权限，stat 读回仍为 0600
        val result = controller.execute(
            PrivilegedRequest("chmod-1", PrivilegedManagementActions.ACTION_FILE_CHMOD,
                arguments = mapOf("path" to path, "mode" to "0644")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") -> mockResult(stdout = "600 1000 1000 1024 1700000000 regular file\n")
                c.contains("chmod") -> mockResult()
                else -> mockResult()
            }
        }

        assertFalse("Must not declare chmod success if actual mode did not change to target", result.success)
        assertEquals("chmod_unconfirmed", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
    }

    @Test
    fun tarBackupVerifiesTableOfContentsAndChecksum() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/pkg_backup.tar.gz"
        val sourcePath = "/data/data/com.example.app"

        val result = controller.execute(
            PrivilegedRequest("backup-1", PrivilegedManagementActions.ACTION_FILE_BACKUP_TAR,
                arguments = mapOf("archive_path" to archivePath, "source_path" to sourcePath)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(sourcePath) -> mockResult(stdout = "755 1000 1000 4096 1700000000 directory\n")
                c.contains("tar -czf") -> mockResult()
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 524288 1700000010 regular file\n")
                c.contains("tar -tzf") -> mockResult(stdout = "com.example.app/\ncom.example.app/databases/\ncom.example.app/databases/app.db\n")
                c.contains("sha256sum") -> mockResult(stdout = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  $archivePath\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
        assertEquals("524288", result.data["sizeBytes"])
        assertEquals("3", result.data["fileCount"])
    }

    @Test
    fun tarBackupFailsWhenArchiveIsCorruptOrZeroBytes() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/bad.tar.gz"
        val sourcePath = "/data/data/com.example.empty"

        val result = controller.execute(
            PrivilegedRequest("backup-bad", PrivilegedManagementActions.ACTION_FILE_BACKUP_TAR,
                arguments = mapOf("archive_path" to archivePath, "source_path" to sourcePath)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(sourcePath) -> mockResult(stdout = "755 1000 1000 4096 1700000000 directory\n")
                c.contains("tar -czf") -> mockResult()
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 0 1700000010 regular file\n") // 0 字节
                else -> mockResult()
            }
        }

        assertFalse(result.success)
        assertEquals("archive_invalid", result.code)
    }

    @Test
    fun chownVerifiesUidAndGidAndFailsWhenOwnershipDoesNotMatch() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val path = "/data/data/com.example.app/file.txt"

        // chown 命令退出为 0，但 stat 读回仍为 1000:1000，而不是期望的 2000:2000
        val result = controller.execute(
            PrivilegedRequest("chown-1", PrivilegedManagementActions.ACTION_FILE_CHOWN,
                arguments = mapOf("path" to path, "owner" to "2000:2000")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") -> mockResult(stdout = "644 1000 1000 1024 1700000000 regular file\n")
                c.contains("chown") -> mockResult()
                else -> mockResult()
            }
        }

        assertFalse("Must not declare chown success if uid/gid did not change to target owner", result.success)
        assertEquals("chown_unconfirmed", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
    }

    @Test
    fun tarRestoreRejectsPathTraversalAndEscapingSymlinks() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/malicious.tar.gz"
        val destDir = "/data/data/com.example.app"

        // TOC 中包含逃逸到系统目录的路径
        val result = controller.execute(
            PrivilegedRequest("restore-malicious", PrivilegedManagementActions.ACTION_FILE_RESTORE_TAR,
                arguments = mapOf("archive_path" to archivePath, "destination_dir" to destDir)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 1024 1700000000 regular file\n")
                c.contains("tar -tzf") || c.contains("tar -tf") -> mockResult(stdout = "databases/app.db\n../../../../system/bin/sh\n")
                c.contains("tar -tvzf") || c.contains("tar -tvf") -> mockResult(stdout = "-rw-r--r-- 0/0 1024 2026-11-06 12:00 databases/app.db\n-rw-r--r-- 0/0 1024 2026-11-06 12:00 ../../../../system/bin/sh\n")
                else -> mockResult()
            }
        }

        assertFalse("Must reject tar containing path traversal", result.success)
        assertEquals("tar_security_rejected", result.code)
    }

    @Test
    fun tarRestoreRejectsSymlinksAndHardlinksForSecurity() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/symlink.tar.gz"
        val destDir = "/data/data/com.example.app"

        val result = controller.execute(
            PrivilegedRequest("restore-symlink", PrivilegedManagementActions.ACTION_FILE_RESTORE_TAR,
                arguments = mapOf("archive_path" to archivePath, "destination_dir" to destDir)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 1024 1700000000 regular file\n")
                c.contains("tar -tzf") || c.contains("tar -tf") -> mockResult(stdout = "link\n")
                c.contains("tar -tvzf") || c.contains("tar -tvf") -> mockResult(stdout = "lrwxrwxrwx 0/0 0 2026-11-06 12:00 link -> /system/bin/sh\n")
                else -> mockResult()
            }
        }

        assertFalse("Must reject tar containing symlink or hardlink entries", result.success)
        assertEquals("unsupported_entry_type", result.code)
    }

    @Test
    fun tarRestoreHandlesSpaceFilenameAndVerifiesContentHashAndMetadata() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/spaces.tar.gz"
        val destDir = "/data/data/com.example.app"

        val result = controller.execute(
            PrivilegedRequest("restore-spaces", PrivilegedManagementActions.ACTION_FILE_RESTORE_TAR,
                arguments = mapOf("archive_path" to archivePath, "destination_dir" to destDir)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 1024 1700000000 regular file\n")
                c.contains("tar -tzf") || c.contains("tar -tf") -> mockResult(stdout = "./\nshared prefs/\nshared prefs/app settings.xml\n")
                c.contains("tar -tvzf") || c.contains("tar -tvf") -> mockResult(stdout = "drwxr-xr-x 1000/1000 0 2026-11-06 12:00 ./\ndrwxr-xr-x 1000/1000 0 2026-11-06 12:00 shared prefs/\n-rw-r--r-- 1000/1000 1024 2026-11-06 12:00 shared prefs/app settings.xml\n")
                c.contains("readlink -f") -> mockResult(stdout = "$destDir\n")
                c.contains("mkdir") -> mockResult()
                c.contains("tar -xzpf") || c.contains("tar -xzf") -> mockResult()
                c.contains("cp -a") -> mockResult()
                c.contains("sha256sum") -> mockResult(stdout = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  file\n")
                c.contains("stat -c") -> mockResult(stdout = "644 1000 1000 1024 1700000000 regular file\n")
                c.contains("rm -rf") -> mockResult()
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
    }

    @Test
    fun tarRestoreFailsWhenDestHashMismatchesStagedHash() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/corrupt.tar.gz"
        val destDir = "/data/data/com.example.app"

        val result = controller.execute(
            PrivilegedRequest("restore-mismatch", PrivilegedManagementActions.ACTION_FILE_RESTORE_TAR,
                arguments = mapOf("archive_path" to archivePath, "destination_dir" to destDir)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 1024 1700000000 regular file\n")
                c.contains("tar -tzf") || c.contains("tar -tf") -> mockResult(stdout = "app.db\n")
                c.contains("tar -tvzf") || c.contains("tar -tvf") -> mockResult(stdout = "-rw-r--r-- 1000/1000 1024 2026-11-06 12:00 app.db\n")
                c.contains("readlink -f") -> mockResult(stdout = "$destDir\n")
                c.contains("mkdir") -> mockResult()
                c.contains("tar -xzpf") || c.contains("tar -xzf") -> mockResult()
                c.contains("cp -a") -> mockResult()
                c.contains("sha256sum") && c.contains(".tmp.omnibot.") -> mockResult(stdout = "stage_hash_111  staged\n")
                c.contains("sha256sum") -> mockResult(stdout = "dest_hash_222  dest\n") // hash mismatch!
                c.contains("stat -c") -> mockResult(stdout = "644 1000 1000 1024 1700000000 regular file\n")
                c.contains("rm -rf") -> mockResult()
                else -> mockResult()
            }
        }

        assertFalse("Must fail verification when dest hash mismatches stage hash", result.success)
        assertEquals("restore_unconfirmed", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
    }

    @Test
    fun tarRestoreRejectsEscapingAncestorSymlinkInDestination() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        val archivePath = "/sdcard/OmniBot/backups/normal.tar.gz"
        val destDir = "/data/data/com.example.app"

        // 目标目录下的 databases 子路径在现有文件系统中是一个指向 /system 的外部符号链接
        val result = controller.execute(
            PrivilegedRequest("restore-symlink-dest", PrivilegedManagementActions.ACTION_FILE_RESTORE_TAR,
                arguments = mapOf("archive_path" to archivePath, "destination_dir" to destDir)),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("stat") && c.contains(archivePath) -> mockResult(stdout = "644 0 0 1024 1700000000 regular file\n")
                c.contains("tar -tzf") || c.contains("tar -tf") -> mockResult(stdout = "databases/app.db\n")
                c.contains("tar -tvzf") || c.contains("tar -tvf") -> mockResult(stdout = "-rw-r--r-- 0/0 1024 2026-11-06 12:00 databases/app.db\n")
                c.contains("readlink -f $destDir/databases/app.db") || c.contains("readlink -f $destDir/databases") -> mockResult(stdout = "/system/etc/hosts\n")
                c.contains("readlink -f") -> mockResult(stdout = "$destDir\n")
                c.contains("mkdir") -> mockResult()
                else -> mockResult()
            }
        }

        assertFalse("Must reject restore when member ancestor resolves outside destination", result.success)
        assertEquals("tar_security_rejected", result.code)
    }

    private fun mockResult(
        success: Boolean = true,
        code: String = "ok",
        message: String = "",
        stdout: String = "",
        stderr: String = "",
        exitCode: Int? = if (success) 0 else 1,
    ): PrivilegedResult = PrivilegedResult(
        requestId = "test-req",
        action = "device_control.command",
        success = success,
        code = code,
        message = message,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
    )
}
