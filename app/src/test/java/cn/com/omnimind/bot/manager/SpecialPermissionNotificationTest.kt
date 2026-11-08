package cn.com.omnimind.bot.manager

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.os.Build
import io.flutter.plugin.common.MethodChannel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SpecialPermissionNotificationTest {
    // 验证 pre-Android 13 (API < 33) 行为：不要求 POST_NOTIFICATIONS 运行时权限，仅依赖应用通知开关。
    // 在已验证的 SDK 34 沙箱下，通过 ReflectionHelpers 临时将 Build.VERSION.SDK_INT 置为 31，并在 finally 中严格还原，
    // 避免因多 SDK 配置触发不可用的旧 SDK 下载，同时完整保留对 pre-13 消费者行为的验证。
    @Test
    fun `pre Android 13 checks and requests reflect the app notification switch without a permission activity`() {
        val originalSdk = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 31)
            val application = RuntimeEnvironment.getApplication()
            val notifications = shadowOf(application.getSystemService(NotificationManager::class.java))
            val manager = SpecialPermissionManager(application)

            for (enabled in listOf(false, true)) {
                notifications.setNotificationsEnabled(enabled)
                assertEquals(enabled, resultOf(manager::isNotificationPermissionGranted))
                assertEquals(enabled, resultOf(manager::requestNotificationPermission))
                assertNull(shadowOf(application).nextStartedActivity)
            }
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
    }

    // 验证 Android 13+ (API 33+) 行为：必须同时具备 POST_NOTIFICATIONS 运行时权限与应用通知开关。
    @Test
    fun `Android 13 and later require both runtime permission and the app notification switch`() {
        val application = RuntimeEnvironment.getApplication()
        val notifications = shadowOf(application.getSystemService(NotificationManager::class.java))
        val manager = SpecialPermissionManager(application)
        notifications.setNotificationsEnabled(true)

        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(false, resultOf(manager::isNotificationPermissionGranted))

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(true, resultOf(manager::isNotificationPermissionGranted))

        notifications.setNotificationsEnabled(false)
        assertEquals(false, resultOf(manager::isNotificationPermissionGranted))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    private fun resultOf(call: (MethodChannel.Result) -> Unit): Any? {
        var value: Any? = null
        call(object : MethodChannel.Result {
            override fun success(result: Any?) { value = result }
            override fun error(code: String, message: String?, details: Any?) {
                fail("$code: $message")
            }
            override fun notImplemented() { fail("Notification permission method missing") }
        })
        return value
    }
}
