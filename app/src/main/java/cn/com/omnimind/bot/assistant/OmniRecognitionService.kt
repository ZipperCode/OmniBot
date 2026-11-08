package cn.com.omnimind.bot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.annotation.VisibleForTesting
import cn.com.omnimind.baselib.util.OmniLog

/**
 * 真实系统语音识别转发服务。
 * 遵循 Android RecognitionService 契约，自身不采集麦克风/不含私有识别引擎，
 * 仅安全转发到系统配置的外部 RecognitionService。
 */
class OmniRecognitionService : RecognitionService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeSession: ActiveSession? = null

    private data class ActiveSession(
        val listener: Callback,
        val recognizer: SpeechRecognizerClient,
    )

    sealed interface ComponentResolutionResult {
        object NoServiceConfigured : ComponentResolutionResult
        data class SelfReference(val component: ComponentName) : ComponentResolutionResult
        data class ServiceNotFound(val component: ComponentName) : ComponentResolutionResult
        data class InsufficientPermissions(val component: ComponentName) : ComponentResolutionResult
        data class Valid(val component: ComponentName) : ComponentResolutionResult
    }

    interface SpeechRecognizerClient {
        fun setRecognitionListener(listener: RecognitionListener)
        fun startListening(recognizerIntent: Intent)
        fun stopListening()
        fun cancel()
        fun destroy()
    }

    private class AndroidSpeechRecognizerClient(
        private val recognizer: SpeechRecognizer,
    ) : SpeechRecognizerClient {
        override fun setRecognitionListener(listener: RecognitionListener) = recognizer.setRecognitionListener(listener)
        override fun startListening(recognizerIntent: Intent) = recognizer.startListening(recognizerIntent)
        override fun stopListening() = recognizer.stopListening()
        override fun cancel() = recognizer.cancel()
        override fun destroy() = recognizer.destroy()
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        mainHandler.post {
            handleStartListening(recognizerIntent, listener)
        }
    }

    override fun onCancel(listener: Callback) {
        mainHandler.post {
            handleCancel(listener)
        }
    }

    override fun onStopListening(listener: Callback) {
        mainHandler.post {
            handleStopListening(listener)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.post {
            activeSession?.recognizer?.let { client ->
                runCatching { client.cancel() }
                runCatching { client.destroy() }
            }
            activeSession = null
        }
    }

    private fun handleStartListening(recognizerIntent: Intent, listener: Callback) {
        if (activeSession != null) {
            OmniLog.w(TAG, "已存在活跃的识别会话，返回 ERROR_RECOGNIZER_BUSY")
            safeError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        when (val resolution = resolveTargetRecognitionService(this)) {
            is ComponentResolutionResult.NoServiceConfigured -> {
                OmniLog.w(TAG, "系统未配置外部语音识别服务")
                safeError(listener, SpeechRecognizer.ERROR_CLIENT)
            }
            is ComponentResolutionResult.SelfReference -> {
                OmniLog.e(TAG, "系统配置的识别服务指向自身，拒绝循环调用: ${resolution.component}")
                safeError(listener, SpeechRecognizer.ERROR_CLIENT)
            }
            is ComponentResolutionResult.ServiceNotFound -> {
                OmniLog.e(TAG, "未找到系统配置的外部语音识别服务组件: ${resolution.component}")
                safeError(listener, SpeechRecognizer.ERROR_CLIENT)
            }
            is ComponentResolutionResult.InsufficientPermissions -> {
                OmniLog.e(TAG, "外部语音识别服务缺少 BIND_SPEECH_RECOGNITION_SERVICE 权限: ${resolution.component}")
                safeError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            }
            is ComponentResolutionResult.Valid -> {
                startExternalRecognition(resolution.component, recognizerIntent, listener)
            }
        }
    }

    private fun startExternalRecognition(
        component: ComponentName,
        recognizerIntent: Intent,
        listener: Callback,
    ) {
        val client = try {
            speechRecognizerFactory(this, component)
        } catch (e: Exception) {
            OmniLog.e(TAG, "创建外部 SpeechRecognizer 失败: ${e.message}")
            safeError(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }

        val session = ActiveSession(listener, client)
        activeSession = session

        client.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.readyForSpeech(params ?: Bundle.EMPTY) }
                }
            }

            override fun onBeginningOfSpeech() {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.beginningOfSpeech() }
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.rmsChanged(rmsdB) }
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.bufferReceived(buffer ?: ByteArray(0)) }
                }
            }

            override fun onEndOfSpeech() {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.endOfSpeech() }
                }
            }

            override fun onError(error: Int) {
                if (activeSession?.listener == listener) {
                    safeError(listener, error)
                    cleanSession(client)
                }
            }

            override fun onResults(results: Bundle?) {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.results(results ?: Bundle.EMPTY) }
                    cleanSession(client)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (activeSession?.listener == listener) {
                    safeCallback { listener.partialResults(partialResults ?: Bundle.EMPTY) }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // 标准 Callback 无直接 onEvent 映射，忽略
            }
        })

        try {
            client.startListening(recognizerIntent)
        } catch (e: Exception) {
            OmniLog.e(TAG, "启动外部识别失败: ${e.message}")
            safeError(listener, SpeechRecognizer.ERROR_CLIENT)
            cleanSession(client)
        }
    }

    private fun handleCancel(listener: Callback) {
        if (activeSession?.listener == listener) {
            val client = activeSession?.recognizer
            activeSession = null
            client?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
        }
    }

    private fun handleStopListening(listener: Callback) {
        if (activeSession?.listener == listener) {
            runCatching { activeSession?.recognizer?.stopListening() }
        }
    }

    private fun cleanSession(client: SpeechRecognizerClient) {
        if (activeSession?.recognizer == client) {
            activeSession = null
        }
        runCatching { client.destroy() }
    }

    private fun safeError(listener: Callback, error: Int) {
        runCatching { listener.error(error) }
            .onFailure { OmniLog.w(TAG, "通知识别回调 error 失败: ${it.message}") }
    }

    private fun safeCallback(block: () -> Unit) {
        runCatching(block)
            .onFailure { OmniLog.w(TAG, "转发识别回调失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "[OmniRecognitionService]"

        @VisibleForTesting
        var speechRecognizerFactory: (Context, ComponentName) -> SpeechRecognizerClient = { ctx, comp ->
            AndroidSpeechRecognizerClient(SpeechRecognizer.createSpeechRecognizer(ctx, comp))
        }

        fun resolveTargetRecognitionService(context: Context): ComponentResolutionResult {
            val raw = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
            if (raw.isNullOrBlank()) {
                return ComponentResolutionResult.NoServiceConfigured
            }

            val component = ComponentName.unflattenFromString(raw)
                ?: return ComponentResolutionResult.NoServiceConfigured

            if (component.packageName == context.packageName &&
                component.className == OmniRecognitionService::class.java.name
            ) {
                return ComponentResolutionResult.SelfReference(component)
            }

            val intent = Intent(SERVICE_INTERFACE).setComponent(component)
            val resolveInfo = context.packageManager.resolveService(intent, PackageManager.GET_META_DATA)
                ?: return ComponentResolutionResult.ServiceNotFound(component)

            val serviceInfo = resolveInfo.serviceInfo
                ?: return ComponentResolutionResult.ServiceNotFound(component)

            if (serviceInfo.permission != "android.permission.BIND_SPEECH_RECOGNITION_SERVICE") {
                return ComponentResolutionResult.InsufficientPermissions(component)
            }

            return ComponentResolutionResult.Valid(component)
        }
    }
}
