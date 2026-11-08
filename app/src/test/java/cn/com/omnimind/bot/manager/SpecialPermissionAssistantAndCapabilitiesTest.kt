package cn.com.omnimind.bot.manager

import android.app.Application
import android.os.Looper
import android.provider.Settings
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SpecialPermissionAssistantAndCapabilitiesTest {

    private lateinit var application: Application
    private lateinit var manager: SpecialPermissionManager

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        manager = SpecialPermissionManager(application)
    }

    private fun <T> awaitMethodResult(
        block: (MethodChannel.Result) -> Unit,
    ): T {
        val future = CompletableFuture<T>()
        block(object : MethodChannel.Result {
            override fun success(result: Any?) {
                @Suppress("UNCHECKED_CAST")
                future.complete(result as T)
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                future.completeExceptionally(
                    AssertionError("MethodChannel 调用失败: $errorCode: $errorMessage (details: $errorDetails)")
                )
            }

            override fun notImplemented() {
                future.completeExceptionally(AssertionError("MethodChannel 未实现"))
            }
        })

        val deadline = System.currentTimeMillis() + 5000L
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!future.isDone) {
                LockSupport.parkNanos(10_000_000L)
            }
        }
        shadowOf(Looper.getMainLooper()).idle()

        return future.get(100, TimeUnit.MILLISECONDS)
    }

    @Test
    fun testGetAssistantStatusReturnsMapWithSupportedAndSelected() {
        val status = awaitMethodResult<Map<String, Any?>> {
            manager.getAssistantStatus(it)
        }

        assertNotNull("getAssistantStatus 必须返回 Map", status)
        assertTrue("必须包含 supported 字段", status.containsKey("supported"))
        assertTrue("必须包含 selected 字段", status.containsKey("selected"))
        assertTrue("supported 必须为布尔值", status["supported"] is Boolean)
        assertTrue("selected 必须为布尔值", status["selected"] is Boolean)
    }

    @Test
    fun testOpenAssistantSettingsStartsVoiceInputSettingsActivity() {
        val successResult = awaitMethodResult<Boolean> {
            manager.openAssistantSettings(it)
        }

        assertEquals(true, successResult)
        val startedIntent = shadowOf(application).nextStartedActivity
        assertNotNull("必须启动设置 Intent", startedIntent)
        assertEquals(Settings.ACTION_VOICE_INPUT_SETTINGS, startedIntent.action)
    }

    @Test
    fun testGetSystemDeviceCapabilitiesReturnsStandardPrivilegedResultMap() {
        val returnedMap = awaitMethodResult<Map<String, Any?>> {
            manager.getSystemDeviceCapabilities(it)
        }

        assertNotNull("必须返回结果 Map", returnedMap)
        assertEquals("device_control.capabilities", returnedMap["action"])
        assertEquals("ok", returnedMap["code"])
        assertEquals(true, returnedMap["success"])

        @Suppress("UNCHECKED_CAST")
        val observation = returnedMap["observation"] as? Map<String, Any?>
        assertNotNull("外层 PrivilegedResult 必须包含 observation", observation)

        @Suppress("UNCHECKED_CAST")
        val value = observation?.get("value") as? Map<String, Any?>
        assertNotNull("observation 必须包含 value Map", value)

        @Suppress("UNCHECKED_CAST")
        val targets = value?.get("targets") as? List<Map<String, Any?>>
        assertNotNull("observation.value 必须包含 targets 列表", targets)
        assertTrue("targets 列表不能为空", targets!!.isNotEmpty())

        val targetNames = targets.mapNotNull { it["target"] as? String }
        assertTrue("必须包含 flashlight", targetNames.contains("flashlight"))
        assertTrue("必须包含 night_light", targetNames.contains("night_light"))
        assertTrue("必须包含 hotspot", targetNames.contains("hotspot"))

        for (t in targets) {
            val name = t["target"] as? String
            assertNotNull("target 必须有名称", name)
            assertTrue("target $name 必须有 readable 字段", t["readable"] is Boolean)
            assertTrue("target $name 必须有 writable 字段", t["writable"] is Boolean)
            assertTrue("target $name 必须有 code 字段", (t["code"] as? String)?.isNotBlank() == true)
        }

        @Suppress("UNCHECKED_CAST")
        val actions = value?.get("actions") as? List<Map<String, Any?>>
        assertNotNull("observation.value 必须包含 actions 列表", actions)
        assertTrue("actions 列表不能为空", actions!!.isNotEmpty())

        val actionNames = actions.mapNotNull { it["action"] as? String }
        assertTrue("必须包含 device_control.set_ringer_mode", actionNames.contains("device_control.set_ringer_mode"))

        for (a in actions) {
            val name = a["action"] as? String
            assertNotNull("action 必须有名称", name)
            assertTrue("action $name 必须有 available 字段", a["available"] is Boolean)
            assertTrue("action $name 必须有 code 字段", (a["code"] as? String)?.isNotBlank() == true)
        }
    }
}
