package cn.com.omnimind.bot.assistant

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OmniRecognitionServiceTest {

    private lateinit var context: Context
    private val defaultSpeechRecognizerFactory = OmniRecognitionService.speechRecognizerFactory
    private val validExternalComponent = ComponentName("com.example.speech", "com.example.speech.ExternalRecognitionService")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        OmniRecognitionService.speechRecognizerFactory = defaultSpeechRecognizerFactory
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", null)
    }

    @After
    fun tearDown() {
        OmniRecognitionService.speechRecognizerFactory = defaultSpeechRecognizerFactory
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", null)
    }

    private class FakeSpeechRecognizerClient : OmniRecognitionService.SpeechRecognizerClient {
        var listener: RecognitionListener? = null
        var startedIntent: Intent? = null
        var stopListeningCalled: Boolean = false
        var cancelCalled: Boolean = false
        var destroyCalled: Boolean = false
        var throwOnStart: Boolean = false

        override fun setRecognitionListener(listener: RecognitionListener) {
            this.listener = listener
        }

        override fun startListening(recognizerIntent: Intent) {
            if (throwOnStart) {
                throw SecurityException("Mock start listening failure")
            }
            this.startedIntent = recognizerIntent
        }

        override fun stopListening() {
            stopListeningCalled = true
        }

        override fun cancel() {
            cancelCalled = true
        }

        override fun destroy() {
            destroyCalled = true
        }
    }

    private fun registerExternalRecognitionService(
        component: ComponentName = validExternalComponent,
        permission: String? = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
    ): ResolveInfo {
        val serviceInfo = ServiceInfo().apply {
            name = component.className
            packageName = component.packageName
            applicationInfo = ApplicationInfo().apply {
                packageName = component.packageName
            }
            this.permission = permission
            exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            this.serviceInfo = serviceInfo
            this.filter = IntentFilter(RecognitionService.SERVICE_INTERFACE)
        }
        val shadowPackageManager = Shadows.shadowOf(context.packageManager)
        val queryIntent = Intent(RecognitionService.SERVICE_INTERFACE).setComponent(component)
        shadowPackageManager.addResolveInfoForIntent(queryIntent, resolveInfo)
        shadowPackageManager.addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), resolveInfo)
        return resolveInfo
    }

    private fun invokeStartListening(
        service: OmniRecognitionService,
        intent: Intent,
        callback: RecognitionService.Callback
    ) {
        val method = try {
            service.javaClass.getDeclaredMethod(
                "onStartListening",
                Intent::class.java,
                RecognitionService.Callback::class.java
            )
        } catch (_: NoSuchMethodException) {
            RecognitionService::class.java.getDeclaredMethod(
                "onStartListening",
                Intent::class.java,
                RecognitionService.Callback::class.java
            )
        }
        method.isAccessible = true
        method.invoke(service, intent, callback)
    }

    private fun invokeCancel(
        service: OmniRecognitionService,
        callback: RecognitionService.Callback
    ) {
        val method = try {
            service.javaClass.getDeclaredMethod(
                "onCancel",
                RecognitionService.Callback::class.java
            )
        } catch (_: NoSuchMethodException) {
            RecognitionService::class.java.getDeclaredMethod(
                "onCancel",
                RecognitionService.Callback::class.java
            )
        }
        method.isAccessible = true
        method.invoke(service, callback)
    }

    private fun invokeStopListening(
        service: OmniRecognitionService,
        callback: RecognitionService.Callback
    ) {
        val method = try {
            service.javaClass.getDeclaredMethod(
                "onStopListening",
                RecognitionService.Callback::class.java
            )
        } catch (_: NoSuchMethodException) {
            RecognitionService::class.java.getDeclaredMethod(
                "onStopListening",
                RecognitionService.Callback::class.java
            )
        }
        method.isAccessible = true
        method.invoke(service, callback)
    }

    @Test
    fun testNoConfiguredServiceReturnsNoServiceConfiguredAndRejects() {
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", "")
        val result = OmniRecognitionService.resolveTargetRecognitionService(context)
        assertEquals(OmniRecognitionService.ComponentResolutionResult.NoServiceConfigured, result)

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()
        val callback = Mockito.mock(RecognitionService.Callback::class.java)

        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback).error(SpeechRecognizer.ERROR_CLIENT)
    }

    @Test
    fun testSelfReferencingServiceReturnsSelfReferenceAndRejects() {
        val selfComponent = ComponentName(context, OmniRecognitionService::class.java)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", selfComponent.flattenToShortString())

        val result = OmniRecognitionService.resolveTargetRecognitionService(context)
        assertTrue("指向自身的组件应返回 SelfReference", result is OmniRecognitionService.ComponentResolutionResult.SelfReference)
        assertEquals(selfComponent, (result as OmniRecognitionService.ComponentResolutionResult.SelfReference).component)

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()
        val callback = Mockito.mock(RecognitionService.Callback::class.java)

        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback).error(SpeechRecognizer.ERROR_CLIENT)
    }

    @Test
    fun testNonExistentServiceReturnsServiceNotFoundAndRejects() {
        val fakeComponent = ComponentName("com.example.nonexistent", "com.example.nonexistent.VoiceService")
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", fakeComponent.flattenToShortString())

        val result = OmniRecognitionService.resolveTargetRecognitionService(context)
        assertTrue("不存在的外部组件应返回 ServiceNotFound", result is OmniRecognitionService.ComponentResolutionResult.ServiceNotFound)
        assertEquals(fakeComponent, (result as OmniRecognitionService.ComponentResolutionResult.ServiceNotFound).component)

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()
        val callback = Mockito.mock(RecognitionService.Callback::class.java)

        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback).error(SpeechRecognizer.ERROR_CLIENT)
    }

    @Test
    fun testServiceWithoutPermissionReturnsInsufficientPermissionsAndRejects() {
        val unprivilegedComponent = ComponentName("com.example.unprivileged", "com.example.unprivileged.VoiceService")
        registerExternalRecognitionService(unprivilegedComponent, permission = "android.permission.INTERNET")
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", unprivilegedComponent.flattenToShortString())

        val result = OmniRecognitionService.resolveTargetRecognitionService(context)
        assertTrue("缺少权限的组件应返回 InsufficientPermissions", result is OmniRecognitionService.ComponentResolutionResult.InsufficientPermissions)
        assertEquals(unprivilegedComponent, (result as OmniRecognitionService.ComponentResolutionResult.InsufficientPermissions).component)

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()
        val callback = Mockito.mock(RecognitionService.Callback::class.java)

        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback).error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
    }

    @Test
    fun testValidConfiguredServiceReturnsValid() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val result = OmniRecognitionService.resolveTargetRecognitionService(context)
        assertEquals(OmniRecognitionService.ComponentResolutionResult.Valid(validExternalComponent), result)
    }

    @Test
    fun testBusyReturnsExactErrorRecognizerBusy() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val createdClients = mutableListOf<FakeSpeechRecognizerClient>()
        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().also { createdClients.add(it) }
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback1 = Mockito.mock(RecognitionService.Callback::class.java)
        val callback2 = Mockito.mock(RecognitionService.Callback::class.java)

        val intent1 = Intent("action.FIRST")
        invokeStartListening(service, intent1, callback1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, createdClients.size)
        assertSame(intent1, createdClients[0].startedIntent)

        val intent2 = Intent("action.SECOND")
        invokeStartListening(service, intent2, callback2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback2).error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        Mockito.verify(callback1, Mockito.never()).error(Mockito.anyInt())
        assertEquals("忙线拒绝时不应创建第二个底层客户端", 1, createdClients.size)
    }

    @Test
    fun testStartListeningAndStopListeningForwarding() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val fakeClient = FakeSpeechRecognizerClient()
        OmniRecognitionService.speechRecognizerFactory = { _, _ -> fakeClient }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback = Mockito.mock(RecognitionService.Callback::class.java)
        val startIntent = Intent("android.speech.action.RECOGNIZE_SPEECH").putExtra("key", "test")

        invokeStartListening(service, startIntent, callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertSame("启动 Intent 应正确传给底层客户端", startIntent, fakeClient.startedIntent)
        assertNotNull("底层客户端应注册 RecognitionListener", fakeClient.listener)

        invokeStopListening(service, callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("stopListening 必须真实转发给客户端", fakeClient.stopListeningCalled)

        fakeClient.stopListeningCalled = false
        val otherCallback = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStopListening(service, otherCallback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse("非当前 listener 的 stopListening 应被忽略", fakeClient.stopListeningCalled)
    }

    @Test
    fun testCancelDestroysClientAndReleasesSessionForSubsequentCalls() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val createdClients = mutableListOf<FakeSpeechRecognizerClient>()
        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().also { createdClients.add(it) }
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback1 = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent("first"), callback1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, createdClients.size)

        val otherCallback = Mockito.mock(RecognitionService.Callback::class.java)
        invokeCancel(service, otherCallback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(createdClients[0].cancelCalled)
        assertFalse(createdClients[0].destroyCalled)

        invokeCancel(service, callback1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("cancel 调用后必须调用 client.cancel()", createdClients[0].cancelCalled)
        assertTrue("cancel 调用后必须调用 client.destroy()", createdClients[0].destroyCalled)

        val callback2 = Mockito.mock(RecognitionService.Callback::class.java)
        val intent2 = Intent("second")
        invokeStartListening(service, intent2, callback2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, createdClients.size)
        assertSame(intent2, createdClients[1].startedIntent)
        Mockito.verify(callback2, Mockito.never()).error(Mockito.anyInt())
    }

    @Test
    fun testCallbackForwardingFromSpeechRecognizerThroughOmniRecognitionService() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val createdClients = mutableListOf<FakeSpeechRecognizerClient>()
        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().also { createdClients.add(it) }
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val client = createdClients[0]
        assertNotNull("OmniRecognitionService 必须向 client 注册 RecognitionListener", client.listener)
        val internalListener = client.listener!!

        val params = Bundle().apply { putString("key", "ready") }
        internalListener.onReadyForSpeech(params)
        Mockito.verify(callback).readyForSpeech(params)

        internalListener.onBeginningOfSpeech()
        Mockito.verify(callback).beginningOfSpeech()

        internalListener.onRmsChanged(3.5f)
        Mockito.verify(callback).rmsChanged(3.5f)

        val buffer = byteArrayOf(1, 2, 3)
        internalListener.onBufferReceived(buffer)
        Mockito.verify(callback).bufferReceived(buffer)

        internalListener.onEndOfSpeech()
        Mockito.verify(callback).endOfSpeech()

        val partialResults = Bundle().apply { putString("partial", "hello") }
        internalListener.onPartialResults(partialResults)
        Mockito.verify(callback).partialResults(partialResults)

        val finalResults = Bundle().apply { putString("result", "hello world") }
        internalListener.onResults(finalResults)
        Mockito.verify(callback).results(finalResults)

        assertTrue("onResults 后必须调用 client.destroy() 释放资源", client.destroyCalled)

        val nextCallback = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent(), nextCallback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, createdClients.size)
        Mockito.verify(nextCallback, Mockito.never()).error(Mockito.anyInt())
    }

    @Test
    fun testErrorForwardingCleansSessionAndAllowsSubsequentStart() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val createdClients = mutableListOf<FakeSpeechRecognizerClient>()
        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().also { createdClients.add(it) }
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback1 = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent(), callback1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val client1 = createdClients[0]
        client1.listener!!.onError(SpeechRecognizer.ERROR_NETWORK)
        Mockito.verify(callback1).error(SpeechRecognizer.ERROR_NETWORK)
        assertTrue("onError 后必须调用 client.destroy() 释放资源", client1.destroyCalled)

        val callback2 = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent(), callback2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, createdClients.size)
        Mockito.verify(callback2, Mockito.never()).error(Mockito.anyInt())
    }

    @Test
    fun testServiceDestroyCancelsAndDestroysClient() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val createdClients = mutableListOf<FakeSpeechRecognizerClient>()
        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().also { createdClients.add(it) }
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val client = createdClients[0]
        assertFalse(client.cancelCalled)
        assertFalse(client.destroyCalled)

        serviceController.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue("服务销毁时应取消底层 client", client.cancelCalled)
        assertTrue("服务销毁时应销毁底层 client", client.destroyCalled)
    }

    @Test
    fun testSpeechRecognizerFactoryFailureReturnsClientError() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            throw IllegalStateException("Factory creation failed")
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent(), callback)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback).error(SpeechRecognizer.ERROR_CLIENT)
    }

    @Test
    fun testClientStartListeningFailureCleansSessionAndReturnsClientError() {
        registerExternalRecognitionService(validExternalComponent)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", validExternalComponent.flattenToShortString())

        val createdClients = mutableListOf<FakeSpeechRecognizerClient>()
        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().apply { throwOnStart = true }.also { createdClients.add(it) }
        }

        val serviceController = Robolectric.buildService(OmniRecognitionService::class.java).create()
        val service = serviceController.get()

        val callback1 = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent("fail"), callback1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback1).error(SpeechRecognizer.ERROR_CLIENT)
        assertTrue("启动异常后必须调用 client.destroy() 释放资源", createdClients[0].destroyCalled)

        OmniRecognitionService.speechRecognizerFactory = { _, _ ->
            FakeSpeechRecognizerClient().also { createdClients.add(it) }
        }
        val callback2 = Mockito.mock(RecognitionService.Callback::class.java)
        invokeStartListening(service, Intent("success"), callback2)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        Mockito.verify(callback2, Mockito.never()).error(Mockito.anyInt())
        assertEquals(2, createdClients.size)
    }
}
