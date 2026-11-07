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
class SystemDeviceManagementTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun selinuxDetectsHardenedKernelLockedEnforcing() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        // 场景: Samsung KNOX 或安全内核执行 setenforce 0 退出码为 0，但 getenforce 读回仍为 Enforcing
        val result = controller.execute(
            PrivilegedRequest("selinux-lock", PrivilegedManagementActions.ACTION_SELINUX_SET,
                arguments = mapOf("mode" to "permissive")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("setenforce 0") -> mockResult()
                c.contains("getenforce") -> mockResult(stdout = "Enforcing\n")
                else -> mockResult()
            }
        }

        assertFalse("Must not claim success when kernel remains Enforcing", result.success)
        assertEquals("selinux_locked_enforcing", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
    }

    @Test
    fun selinuxSuccessfullyToggledWhenKernelPermits() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        var currentMode = "Enforcing"

        val result = controller.execute(
            PrivilegedRequest("selinux-ok", PrivilegedManagementActions.ACTION_SELINUX_SET,
                arguments = mapOf("mode" to "permissive")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("setenforce 0") -> {
                    currentMode = "Permissive"
                    mockResult()
                }
                c.contains("getenforce") -> mockResult(stdout = "$currentMode\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
        assertEquals("Permissive", result.data["mode"])
    }

    @Test
    fun rebootDispatchesCommandAndExplicitlyReportsUnverifiedBeforePowerCycle() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        var rebootDispatched = false

        val result = controller.execute(
            PrivilegedRequest("reboot-1", PrivilegedManagementActions.ACTION_SYSTEM_REBOOT,
                arguments = mapOf("mode" to "recovery", "reason" to "firmware_update")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            if (c.contains("reboot recovery")) {
                rebootDispatched = true
                mockResult()
            } else mockResult()
        }

        assertTrue(result.success)
        assertTrue("Reboot command must be dispatched", rebootDispatched)
        assertEquals("reboot_dispatched_unverified", result.data["status"])
        // F17 关键：不能返回 verified = true，当前调用无法在重启下电前验证生效
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
        assertEquals(JsonPrimitive("reboot_dispatched_unverified"),
            (result.observation?.get("details") as? kotlinx.serialization.json.JsonObject)?.get("status"))
    }

    @Test
    fun rebootStatusProvidesUptimeAndBootIdForPostRebootCheckpoint() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)

        val result = controller.execute(
            PrivilegedRequest("reboot-status", PrivilegedManagementActions.ACTION_SYSTEM_REBOOT_STATUS),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("cat /proc/uptime") -> mockResult(stdout = "35.42 120.15\n")
                c.contains("cat /proc/sys/kernel/random/boot_id") -> mockResult(stdout = "4a5b6c7d-8e9f-0123-4567-89abcdef0123\n")
                c.contains("getprop ro.boot.slot_suffix") -> mockResult(stdout = "_a\n")
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertEquals("35.42", result.data["uptimeSeconds"])
        assertEquals("4a5b6c7d-8e9f-0123-4567-89abcdef0123", result.data["bootId"])
        assertEquals("a", result.data["activeSlot"])
    }

    @Test
    fun partitionBackupResolvesBlockDeviceAndFailsIfPartitionMissing() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)

        // 场景 1: 不存在的分区名称，应返回 unsupported_action
        val missingResult = controller.execute(
            PrivilegedRequest("part-missing", PrivilegedManagementActions.ACTION_PARTITION_BACKUP,
                arguments = mapOf("partition_name" to "non_existent_part", "destination_path" to "/sdcard/part.img")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("getprop ro.boot.slot_suffix") -> mockResult(stdout = "_a\n")
                c.contains("test -b") -> mockResult(success = false, code = "no", exitCode = 1)
                else -> mockResult()
            }
        }

        assertFalse(missingResult.success)
        assertEquals("unsupported_action", missingResult.code)
    }

    @Test
    fun partitionFlashRejectsOversizedImageBeforeWriting() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        var ddExecuted = false

        // 镜像 64MB，分区容量仅 32MB
        val result = controller.execute(
            PrivilegedRequest("flash-oversized", PrivilegedManagementActions.ACTION_PARTITION_FLASH,
                arguments = mapOf("partition_name" to "boot", "image_path" to "/sdcard/boot-oversized.img")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("getprop ro.boot.slot_suffix") -> mockResult(stdout = "_a\n")
                c.contains("test -b") -> mockResult()
                c.contains("stat -c %s") -> mockResult(stdout = "67108864\n") // 64MB
                c.contains("sha256sum") -> mockResult(stdout = "abc12345  /sdcard/boot.img\n")
                c.contains("blockdev --getsize64") -> mockResult(stdout = "33554432\n") // 32MB
                c.contains("dd if=") -> {
                    ddExecuted = true
                    mockResult()
                }
                else -> mockResult()
            }
        }

        assertFalse(result.success)
        assertEquals("image_too_large", result.code)
        assertFalse("Must not execute dd flash if image size exceeds partition capacity", ddExecuted)
    }

    @Test
    fun partitionFlashVerifiesWrittenBytesViaByteComparison() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)
        var byteComparisonChecked = false

        val result = controller.execute(
            PrivilegedRequest("flash-verified", PrivilegedManagementActions.ACTION_PARTITION_FLASH,
                arguments = mapOf("partition_name" to "boot", "image_path" to "/sdcard/boot-good.img")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("getprop ro.boot.slot_suffix") -> mockResult(stdout = "_a\n")
                c.contains("test -b") -> mockResult()
                c.contains("stat -c %s") -> mockResult(stdout = "16777216\n") // 16MB
                c.contains("sha256sum") -> mockResult(stdout = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  /sdcard/boot-good.img\n")
                c.contains("blockdev --getsize64") -> mockResult(stdout = "33554432\n") // 32MB
                c.contains("dd if=") -> mockResult()
                c.contains("cmp -n") -> {
                    byteComparisonChecked = true
                    mockResult(exitCode = 0)
                }
                else -> mockResult()
            }
        }

        assertTrue(result.success)
        assertTrue("Byte-level comparison (cmp -n) must be executed", byteComparisonChecked)
        assertEquals(JsonPrimitive(true), result.observation?.get("verified"))
    }

    @Test
    fun partitionFlashFailsWhenByteComparisonMismatches() = runBlocking {
        val controller = PrivilegedManagementController(context, 0)

        // dd 退出 0，但后续 cmp 比对发现块设备数据不一致（例如坏块或写缓存丢失）
        val result = controller.execute(
            PrivilegedRequest("flash-mismatch", PrivilegedManagementActions.ACTION_PARTITION_FLASH,
                arguments = mapOf("partition_name" to "boot", "image_path" to "/sdcard/boot.img")),
            PrivilegedBackend.ROOT, { true }
        ) { cmd ->
            val c = cmd.replace("'", "").replace("\"", "")
            when {
                c.contains("getprop ro.boot.slot_suffix") -> mockResult(stdout = "_a\n")
                c.contains("test -b") -> mockResult()
                c.contains("stat -c %s") -> mockResult(stdout = "16777216\n")
                c.contains("sha256sum") -> mockResult(stdout = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  /sdcard/boot.img\n")
                c.contains("blockdev --getsize64") -> mockResult(stdout = "33554432\n")
                c.contains("dd if=") -> mockResult()
                c.contains("cmp -n") -> mockResult(success = false, code = "mismatch", exitCode = 1, stdout = "differ: byte 4097, line 2\n")
                else -> mockResult()
            }
        }

        assertFalse(result.success)
        assertEquals("flash_verification_mismatch", result.code)
        assertEquals(JsonPrimitive(false), result.observation?.get("verified"))
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
