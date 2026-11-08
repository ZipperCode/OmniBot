package cn.com.omnimind.bot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import cn.com.omnimind.baselib.util.OmniLog

/**
 * 标准系统数字助理服务入口。
 * 遵循 Android VoiceInteractionService 契约，单次可见屏幕上下文经确认进入既有聊天。
 */
class OmniVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        OmniLog.i(TAG, "OmniVoiceInteractionService 已就绪")
    }

    override fun onShutdown() {
        super.onShutdown()
        OmniLog.i(TAG, "OmniVoiceInteractionService 已关闭")
    }

    companion object {
        private const val TAG = "[OmniVoiceInteractionService]"

        /**
         * 检查当前服务是否已被用户在系统设置中选为活跃的语音助理。
         */
        fun isActive(context: Context): Boolean {
            return try {
                val component = ComponentName(context, OmniVoiceInteractionService::class.java)
                VoiceInteractionService.isActiveService(context, component)
            } catch (e: Exception) {
                OmniLog.w(TAG, "检查语音助理活跃状态失败: ${e.message}")
                false
            }
        }

        /**
         * 当前设备是否支持系统数字助理入口。
         */
        fun isSupported(context: Context): Boolean {
            return true
        }

        /**
         * 打开系统语音输入与数字助理设置页。
         */
        fun openSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                OmniLog.e(TAG, "打开数字助理设置失败: ${e.message}")
            }
        }
    }
}
