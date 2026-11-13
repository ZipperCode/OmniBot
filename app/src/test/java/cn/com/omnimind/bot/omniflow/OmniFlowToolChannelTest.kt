package cn.com.omnimind.bot.omniflow

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OmniFlowToolChannelTest {
    private class FakeMethodResult : MethodChannel.Result {
        var successResult: Any? = null
        var errorCode: String? = null
        var errorMessage: String? = null
        var errorDetails: Any? = null
        var notImplementedCalled: Boolean = false

        override fun success(result: Any?) {
            successResult = result
        }

        override fun error(code: String, message: String?, details: Any?) {
            errorCode = code
            errorMessage = message
            errorDetails = details
        }

        override fun notImplemented() {
            notImplementedCalled = true
        }
    }

    @Test
    fun `learn_app_experiences is explicitly rejected with rejected error code`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val channel = OmniFlowToolChannel(context)

        val call = MethodCall(
            "tools/call",
            mapOf(
                "name" to "learn_app_experiences",
                "arguments" to mapOf("run_id" to "run-1"),
            ),
        )
        val result = FakeMethodResult()

        val handled = channel.handle(call, result)

        assertTrue("learn_app_experiences 必须由通道受理并处理", handled)
        assertEquals("OMNIFLOW_TOOL_CALL_REJECTED", result.errorCode)
    }

    @Test
    fun `enrichExperienceItems adds application label with unknown app fallback`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val pm = context.packageManager
        val shadowPm = Shadows.shadowOf(pm)

        val appInfo = ApplicationInfo().apply {
            packageName = "com.known.app"
            name = "com.known.app.App"
            nonLocalizedLabel = "已知应用"
        }
        val packageInfo = PackageInfo().apply {
            packageName = "com.known.app"
            this.applicationInfo = appInfo
        }
        shadowPm.installPackage(packageInfo)

        val channel = OmniFlowToolChannel(context)

        val payload = mapOf<String, Any?>(
            "success" to true,
            "revision" to 1,
            "items" to listOf(
                mapOf(
                    "package_name" to "com.known.app",
                    "activity_name" to ".MainActivity",
                    "status" to "trial",
                ),
                mapOf(
                    "package_name" to "com.unknown.app",
                    "activity_name" to ".DetailsActivity",
                    "status" to "verified",
                ),
                mapOf(
                    "package_name" to "",
                    "activity_name" to "",
                    "status" to "retired",
                ),
            ),
        )

        val enriched = channel.enrichExperienceItems(context, payload)
        val items = enriched["items"] as List<*>
        assertEquals(3, items.size)

        val item1 = items[0] as Map<*, *>
        assertEquals("com.known.app", item1["package_name"])
        assertEquals("已知应用", item1["application_label"])

        val item2 = items[1] as Map<*, *>
        assertEquals("com.unknown.app", item2["package_name"])
        assertEquals("未知应用", item2["application_label"])

        val item3 = items[2] as Map<*, *>
        assertEquals("", item3["package_name"])
        assertEquals("未知应用", item3["application_label"])
    }

    @Test
    fun `channel returns app_experience_busy when OmniFlow is busy`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val channel = OmniFlowToolChannel(context)

        val executionsField = OmniFlow::class.java.getDeclaredField("executions").apply {
            isAccessible = true
        }
        val registry = executionsField.get(OmniFlow)
        val beginMethod = registry.javaClass.declaredMethods.first { it.name == "begin" }.apply { isAccessible = true }
        val reg = beginMethod.invoke(registry, "busy-run", {})
        try {
            assertTrue("OmniFlow 必须处于忙碌状态", OmniFlow.isBusy())
            val call = MethodCall(
                "tools/call",
                mapOf("name" to "list_app_experiences"),
            )
            val result = FakeMethodResult()
            val handled = channel.handle(call, result)
            assertTrue("channel 必须受理并快速返回", handled)
            val resMap = result.successResult as Map<*, *>
            assertEquals(false, resMap["success"])
            assertEquals("app_experience_busy", resMap["error_code"])
            assertEquals("任务执行中，任务结束后更新", resMap["message"])
        } finally {
            val endMethod = registry.javaClass.declaredMethods.first { it.name == "end" }.apply { isAccessible = true }
            endMethod.invoke(registry, reg)
        }
    }
}
