package cn.com.omnimind.bot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.voice.VoiceInteractionService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 系统数字助理服务注册真实系统回归测试：
 * 验证真实安装的 APK 中 OmniVoiceInteractionService、OmniVoiceSessionService 与 OmniRecognitionService 的注册合同。
 *
 * 核心验证点：
 * 1. 规避因 Manifest meta-data 键误写（如误写为 android.voice_interaction_service）导致系统 VoiceInteractionManagerService 遗漏小万。
 * 2. 通过系统真实 PackageManager 获取实际安装包的 ServiceInfo（带 GET_META_DATA）。
 * 3. 使用系统隐藏的官方解析器 android.service.voice.VoiceInteractionServiceInfo 反射构造 (PackageManager, ServiceInfo)。
 *    注意：VoiceInteractionServiceInfo 属于隐藏系统类，真机执行时需通过 am instrument 参数
 *    `--no-hidden-api-checks` 运行（进程局部免除 ART Hidden API 限制，无需修改全局系统设置）。
 * 4. 断言官方解析器无解析错误 (getParseError() == null)、声明支持屏幕辅助 (getSupportsAssist() == true)、
 *    sessionService 与 recognitionService 正确指向 OmniVoiceSessionService 与 OmniRecognitionService。
 * 5. 验证系统 queryIntentServices 查询 ACTION VoiceInteractionService 包含本服务组件且具备 BIND_VOICE_INTERACTION 权限。
 * 6. 严禁手工构造 Bundle 伪造通过，严禁通过读取源码文本 contains 假通过，只基于真实系统消费者契约。
 */
@RunWith(AndroidJUnit4::class)
class AssistantServiceRegistrationDeviceTest {

    private lateinit var targetContext: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        packageManager = targetContext.packageManager
    }

    /**
     * 验证系统 Intent 解析器能通过标准 ACTION (android.service.voice.VoiceInteractionService)
     * 查询到已安装的 OmniVoiceInteractionService，且该组件声明了 exported=true 与 BIND_VOICE_INTERACTION 权限。
     */
    @Test
    fun testVoiceInteractionServiceIntentQueryable() {
        val queryIntent = Intent(VoiceInteractionService.SERVICE_INTERFACE).apply {
            setPackage(targetContext.packageName)
        }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                queryIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(queryIntent, PackageManager.GET_META_DATA)
        }

        val matched = resolveInfos.find {
            it.serviceInfo != null && it.serviceInfo.name == OmniVoiceInteractionService::class.java.name
        }

        assertNotNull(
            "系统 queryIntentServices 查询 ACTION='${VoiceInteractionService.SERVICE_INTERFACE}' 必须包含已安装的 OmniVoiceInteractionService",
            matched
        )

        val serviceInfo = matched!!.serviceInfo
        assertTrue(
            "OmniVoiceInteractionService 必须声明 exported=true，否则系统助理框架无法跨进程绑定",
            serviceInfo.exported
        )
        assertEquals(
            "OmniVoiceInteractionService 必须受 android.permission.BIND_VOICE_INTERACTION 权限保护",
            android.Manifest.permission.BIND_VOICE_INTERACTION,
            serviceInfo.permission
        )
    }

    /**
     * 验证真实安装的 OmniVoiceInteractionService 通过官方 VoiceInteractionServiceInfo 解析器解析无误：
     * 1. PackageManager.GET_META_DATA 读取的 ServiceInfo 包含官方键 VoiceInteractionService.SERVICE_META_DATA ('android.voice_interaction')。
     * 2. 官方 VoiceInteractionServiceInfo 解析器解析后 getParseError() 为 null（若键名错误将返回 'No android.voice_interaction meta-data'）。
     * 3. getSupportsAssist() 返回 true。
     * 4. getSessionService() 匹配 OmniVoiceSessionService。
     * 5. getRecognitionService() 匹配 OmniRecognitionService。
     * 6. getServiceInfo() 正确返回原 ServiceInfo。
     * 7. 在 SDK >= 37 时，官方解析器解析 android:usesAssistData、android:usesAssistScreenshots 与 android:usesAssistStructureScreenContent 必须全为 true，
     *    且 PackageManager.checkPermission 验证宿主已获系统授予 android.permission.READ_ASSIST_STRUCTURE_SCREEN_CONTENT。
     *
     * 运行说明：VoiceInteractionServiceInfo 为 Android 内部隐藏类，执行本测试需保证测试进程不受 Hidden API 限制
     * （例如通过 adb shell am instrument --no-hidden-api-checks 启动测试）。
     */
    @Test
    fun testVoiceInteractionServiceInfoOfficialParser() {
        val component = ComponentName(targetContext, OmniVoiceInteractionService::class.java)

        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(
                component,
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        }

        assertNotNull("OmniVoiceInteractionService 必须在已安装 APK 中存在", serviceInfo)
        assertEquals(
            "OmniVoiceInteractionService 必须受 android.permission.BIND_VOICE_INTERACTION 权限保护",
            android.Manifest.permission.BIND_VOICE_INTERACTION,
            serviceInfo.permission
        )
        assertNotNull(
            "OmniVoiceInteractionService 的 metaData 不应为空，必须由 Manifest 提供",
            serviceInfo.metaData
        )
        assertTrue(
            "OmniVoiceInteractionService 的 metaData 必须包含官方键 VoiceInteractionService.SERVICE_META_DATA ('${VoiceInteractionService.SERVICE_META_DATA}')",
            serviceInfo.metaData.containsKey(VoiceInteractionService.SERVICE_META_DATA)
        )

        // 反射加载系统官方解析器 android.service.voice.VoiceInteractionServiceInfo
        val parserClass = Class.forName("android.service.voice.VoiceInteractionServiceInfo")
        val constructor = parserClass.getConstructor(PackageManager::class.java, ServiceInfo::class.java)
        constructor.isAccessible = true

        val parserInstance = constructor.newInstance(packageManager, serviceInfo)

        // 1. getParseError(): 解析成功必须为 null
        val getParseErrorMethod = parserClass.getMethod("getParseError")
        val parseError = getParseErrorMethod.invoke(parserInstance) as? String
        assertNull(
            "VoiceInteractionServiceInfo 解析失败，说明 meta-data 键名或 XML 内容配置错误: $parseError",
            parseError
        )

        // 2. getSupportsAssist(): 必须声明支持屏幕辅助
        val getSupportsAssistMethod = parserClass.getMethod("getSupportsAssist")
        val supportsAssist = getSupportsAssistMethod.invoke(parserInstance) as? Boolean
        assertEquals(
            "VoiceInteractionServiceInfo.getSupportsAssist() 必须为 true",
            true,
            supportsAssist
        )

        // 3. getSessionService(): 必须指向 OmniVoiceSessionService
        val getSessionServiceMethod = parserClass.getMethod("getSessionService")
        val sessionService = getSessionServiceMethod.invoke(parserInstance) as? String
        assertEquals(
            "VoiceInteractionServiceInfo.getSessionService() 必须指向 OmniVoiceSessionService",
            OmniVoiceSessionService::class.java.name,
            sessionService
        )

        // 4. getRecognitionService(): 必须指向 OmniRecognitionService
        val getRecognitionServiceMethod = parserClass.getMethod("getRecognitionService")
        val recognitionService = getRecognitionServiceMethod.invoke(parserInstance) as? String
        assertEquals(
            "VoiceInteractionServiceInfo.getRecognitionService() 必须指向 OmniRecognitionService",
            OmniRecognitionService::class.java.name,
            recognitionService
        )

        // 5. getServiceInfo(): 解析后的 ServiceInfo 必须与待测组件一致
        val getServiceInfoMethod = parserClass.getMethod("getServiceInfo")
        val parsedServiceInfo = getServiceInfoMethod.invoke(parserInstance) as? ServiceInfo
        assertNotNull("VoiceInteractionServiceInfo.getServiceInfo() 不应为空", parsedServiceInfo)
        assertEquals(
            "VoiceInteractionServiceInfo.getServiceInfo() 名称必须与 OmniVoiceInteractionService 一致",
            OmniVoiceInteractionService::class.java.name,
            parsedServiceInfo?.name
        )

        // 6. SDK>=37 官方辅助元数据：Android 17 compat437416500 检查 getUsesAssistData / getUsesAssistScreenshots / getUsesAssistStructureScreenContent
        if (Build.VERSION.SDK_INT >= 37) {
            val getUsesAssistDataMethod = parserClass.getMethod("getUsesAssistData")
            val usesAssistData = getUsesAssistDataMethod.invoke(parserInstance) as? Boolean
            assertEquals(
                "SDK>=37 时 VoiceInteractionServiceInfo.getUsesAssistData() 必须为 true",
                true,
                usesAssistData
            )

            val getUsesAssistScreenshotsMethod = parserClass.getMethod("getUsesAssistScreenshots")
            val usesAssistScreenshots = getUsesAssistScreenshotsMethod.invoke(parserInstance) as? Boolean
            assertEquals(
                "SDK>=37 时 VoiceInteractionServiceInfo.getUsesAssistScreenshots() 必须为 true",
                true,
                usesAssistScreenshots
            )

            val getUsesAssistStructureScreenContentMethod = parserClass.getMethod("getUsesAssistStructureScreenContent")
            val usesAssistStructureScreenContent = getUsesAssistStructureScreenContentMethod.invoke(parserInstance) as? Boolean
            assertEquals(
                "SDK>=37 时 VoiceInteractionServiceInfo.getUsesAssistStructureScreenContent() 必须为 true",
                true,
                usesAssistStructureScreenContent
            )

            val permissionCheck = packageManager.checkPermission(
                "android.permission.READ_ASSIST_STRUCTURE_SCREEN_CONTENT",
                targetContext.packageName
            )
            assertEquals(
                "SDK>=37 时宿主 App 必须已被系统授予 android.permission.READ_ASSIST_STRUCTURE_SCREEN_CONTENT (normal permission)",
                PackageManager.PERMISSION_GRANTED,
                permissionCheck
            )
        }
    }

    /**
     * 验证配套的 OmniVoiceSessionService 与 OmniRecognitionService 在已安装 APK 中的注册状态，
     * 确保系统在解析出服务名称后能顺利绑定。
     */
    @Test
    fun testCompanionServicesRegistration() {
        // 1. OmniVoiceSessionService 必须受 BIND_VOICE_INTERACTION 权限保护
        val sessionComponent = ComponentName(targetContext, OmniVoiceSessionService::class.java)
        val sessionServiceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(sessionComponent, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getServiceInfo(sessionComponent, 0)
        }
        assertNotNull("OmniVoiceSessionService 必须在已安装 APK 中注册", sessionServiceInfo)
        assertEquals(
            "OmniVoiceSessionService 必须受 android.permission.BIND_VOICE_INTERACTION 权限保护",
            android.Manifest.permission.BIND_VOICE_INTERACTION,
            sessionServiceInfo.permission
        )

        // 2. OmniRecognitionService 必须受 BIND_SPEECH_RECOGNITION_SERVICE 权限保护且 exported=true
        val recognitionComponent = ComponentName(targetContext, OmniRecognitionService::class.java)
        val recognitionServiceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getServiceInfo(recognitionComponent, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getServiceInfo(recognitionComponent, 0)
        }
        assertNotNull("OmniRecognitionService 必须在已安装 APK 中注册", recognitionServiceInfo)
        assertEquals(
            "OmniRecognitionService 必须受 android.permission.BIND_SPEECH_RECOGNITION_SERVICE 权限保护",
            "android.permission.BIND_SPEECH_RECOGNITION_SERVICE",
            recognitionServiceInfo.permission
        )
        assertTrue(
            "OmniRecognitionService 必须声明 exported=true 以便系统语音识别框架跨进程绑定",
            recognitionServiceInfo.exported
        )
    }
}
