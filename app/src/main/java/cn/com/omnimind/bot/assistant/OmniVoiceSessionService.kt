package cn.com.omnimind.bot.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import cn.com.omnimind.baselib.util.OmniLog

/**
 * 标准系统数字助理 Session 服务。
 * 每次唤醒由系统调用 onNewSession 创建新的 OmniVoiceSession 对象。
 */
class OmniVoiceSessionService : VoiceInteractionSessionService() {

    override fun onCreate() {
        super.onCreate()
        OmniLog.i(TAG, "OmniVoiceSessionService 已创建")
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        OmniLog.i(TAG, "创建新的 OmniVoiceSession 实例")
        return OmniVoiceSession(this)
    }

    companion object {
        private const val TAG = "[OmniVoiceSessionService]"
    }
}
