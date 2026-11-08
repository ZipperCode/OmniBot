package cn.com.omnimind.baselib.privileged

import android.os.Process
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedDeviceApiMainTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun rejectsInvalidArguments() {
        // 1. 只读操作多传参数（只读严格要求 4 参数）
        val resultReadExtra = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.capabilities", "0", "capabilities", "true")
        )
        assertEquals("invalid_arguments", resultReadExtra.code)
        assertFalse(resultReadExtra.success)

        // 2. 只读操作少传参数
        val resultReadFew = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.capabilities", "0")
        )
        assertEquals("invalid_arguments", resultReadFew.code)
        assertFalse(resultReadFew.success)

        // 3. 修改操作少传参数（修改严格要求 5 参数）
        val resultMutateFew = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.set_state", "0", "night_light_set")
        )
        assertEquals("invalid_arguments", resultMutateFew.code)
        assertFalse(resultMutateFew.success)

        // 4. 修改操作多传参数
        val resultMutateExtra = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.set_state", "0", "night_light_set", "true", "extra")
        )
        assertEquals("invalid_arguments", resultMutateExtra.code)
        assertFalse(resultMutateExtra.success)

        // 5. 修改操作 enabled 非布尔值
        val resultMutateInvalidBool = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.set_state", "0", "night_light_set", "yes")
        )
        assertEquals("invalid_arguments", resultMutateInvalidBool.code)
        assertFalse(resultMutateInvalidBool.success)

        // 6. 负数 userId
        val resultNegativeUser = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.capabilities", "-1", "capabilities")
        )
        assertEquals("invalid_arguments", resultNegativeUser.code)
        assertFalse(resultNegativeUser.success)

        // 7. 非数字 userId
        val resultNonNumericUser = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.capabilities", "user_0", "capabilities")
        )
        assertEquals("invalid_arguments", resultNonNumericUser.code)
        assertFalse(resultNonNumericUser.success)

        // 8. 不支持的 operation
        val resultUnsupportedOp = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.capabilities", "0", "custom_operation")
        )
        assertEquals("unsupported_action", resultUnsupportedOp.code)
        assertFalse(resultUnsupportedOp.success)

        // 9. action 与 operation 不匹配
        val resultActionMismatch = PrivilegedDeviceApiMain.executeCli(
            arrayOf("req1", "device_control.set_state", "0", "capabilities")
        )
        assertEquals("invalid_arguments", resultActionMismatch.code)
        assertFalse(resultActionMismatch.success)
    }

    @Test
    fun rejectsNonPrivilegedUid() {
        val currentUid = Process.myUid()
        if (currentUid !in setOf(0, 2000)) {
            val result = PrivilegedDeviceApiMain.executeCli(
                arrayOf("req_uid", "device_control.capabilities", "0", "capabilities")
            )
            assertEquals("permission_denied", result.code)
            assertFalse(result.success)
            assertEquals(currentUid, result.uid)
            assertEquals(currentUid.toString(), result.data["uid"])
        }
    }

    @Test
    fun mainPrintsSingleMarkerJsonLine() {
        val originalOut = System.out
        val outputStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outputStream))
        try {
            PrivilegedDeviceApiMain.main(
                arrayOf("req_main", "device_control.capabilities", "0", "capabilities")
            )
            val output = outputStream.toString("UTF-8").trim()
            assertTrue(output.startsWith(PrivilegedDeviceApiMain.PREFIX))
            val jsonPart = output.removePrefix(PrivilegedDeviceApiMain.PREFIX)
            val parsed = json.decodeFromString(PrivilegedResult.serializer(), jsonPart)
            assertEquals("req_main", parsed.requestId)
            assertEquals("device_control.capabilities", parsed.action)
            assertNotNull(parsed.uid)
            assertEquals(parsed.uid.toString(), parsed.data["uid"])
        } finally {
            System.setOut(originalOut)
        }
    }

    @Test
    fun nightLightProbeGracefullyReportsUnsupportedOnStandardEnvironment() {
        val context = RuntimeEnvironment.getApplication()
        val (readable, writable, code) = PrivilegedNightLightApi.probe(context, 0)
        assertFalse(readable)
        assertFalse(writable)
        assertTrue(code in setOf("unsupported_action", "state_unavailable"))
    }

    @Test
    fun nightLightRejectsNonForegroundUser() {
        val context = RuntimeEnvironment.getApplication()
        val currentUserId = PrivilegedNightLightApi.getCurrentUserId()
        if (currentUserId != null) {
            val mismatchedUser = currentUserId + 10
            val (readable, writable, code) = PrivilegedNightLightApi.probe(context, mismatchedUser)
            assertFalse(readable)
            assertFalse(writable)
            assertEquals("unsupported_action", code)
        }
    }

    @Test
    fun nightLightReadStateReturnsNullWhenUnavailable() {
        val context = RuntimeEnvironment.getApplication()
        val state = PrivilegedNightLightApi.readState(context, 0)
        assertNull(state)
    }

}
