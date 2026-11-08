package cn.com.omnimind.bot.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch

/**
 * 真实测试接收 Activity，在真机或 Android Instrumentation 测试中由系统 Android Manifest 声明并接收系统分发的时钟 Intent。
 * 通过 ActivityManager 真实分发启动并调用 onCreate，杜绝手动 buildActivity 假接收验证。
 */
class TestClockReceiverActivity : Activity() {
    companion object {
        @Volatile
        var lastReceivedIntent: Intent? = null
        @Volatile
        var latch: CountDownLatch? = null

        fun prepare(count: Int = 1): CountDownLatch {
            lastReceivedIntent = null
            val newLatch = CountDownLatch(count)
            latch = newLatch
            return newLatch
        }

        fun reset() {
            lastReceivedIntent = null
            latch = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastReceivedIntent = intent
        latch?.countDown()
        finish()
    }
}
