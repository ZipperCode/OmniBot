package cn.com.omnimind.bot.agent.tool.alarm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import cn.com.omnimind.bot.debug.TestClockReceiverActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Android Instrumentation 端到端测试：
 * 验证真实系统 Intent 分发、PackageManager 真实 resolveActivity 及 ActivityManager 启动 TestClockReceiverActivity 执行 onCreate。
 *
 * 安全约束：TestClockReceiverActivity 在 Manifest 中默认 enabled=false 并受 signature permission 保护，
 * 仅在测试期间临时通过 PackageManager 设置为 enabled，执行完毕后在 finally / @After 强制恢复 disabled，
 * 杜绝在任何情况下常驻截获真实时钟验收或用户时钟请求。
 */
@RunWith(AndroidJUnit4::class)
class AgentSystemClockActionDeviceTest {

    private lateinit var targetContext: Context
    private lateinit var service: AgentAlarmToolService
    private lateinit var fixtureComponent: ComponentName

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        service = AgentAlarmToolService(targetContext)
        fixtureComponent = ComponentName(targetContext, TestClockReceiverActivity::class.java)
        TestClockReceiverActivity.reset()
        disableFixtureComponent()
    }

    @After
    fun tearDown() {
        try {
            TestClockReceiverActivity.reset()
        } finally {
            disableFixtureComponent()
        }
    }

    private fun enableFixtureComponent() {
        targetContext.packageManager.setComponentEnabledSetting(
            fixtureComponent,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private fun disableFixtureComponent() {
        targetContext.packageManager.setComponentEnabledSetting(
            fixtureComponent,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    private inline fun <T> withTemporaryFixtureEnabled(block: () -> T): T {
        enableFixtureComponent()
        return try {
            block()
        } finally {
            disableFixtureComponent()
        }
    }

    @Test
    fun executeClockAction_createTimer_launchesRealFixtureActivity() {
        withTemporaryFixtureEnabled {
            val latch = TestClockReceiverActivity.prepare()

            val result = service.executeClockAction(
                action = AgentAlarmToolService.ACTION_CREATE_TIMER,
                durationSeconds = 60,
                title = "煮蛋测试",
                skipUi = true
            )

            assertEquals(true, result["success"])
            assertEquals(true, result["dispatched"])
            assertEquals(false, result["verified"])

            val received = latch.await(5, TimeUnit.SECONDS)
            assertTrue("系统分发必须真正启动声明的 TestClockReceiverActivity 并执行 onCreate", received)

            val intent = TestClockReceiverActivity.lastReceivedIntent
            assertNotNull("接收到的 Intent 不能为空", intent)
            assertEquals(AlarmClock.ACTION_SET_TIMER, intent!!.action)
            assertEquals(60, intent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
            assertEquals("煮蛋测试", intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
            assertEquals(true, intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        }
    }

    @Test
    fun executeClockAction_showAlarms_launchesRealFixtureActivity() {
        withTemporaryFixtureEnabled {
            val latch = TestClockReceiverActivity.prepare()

            val result = service.executeClockAction(
                action = AgentAlarmToolService.ACTION_SHOW_ALARMS
            )

            assertEquals(true, result["success"])
            assertEquals(true, result["dispatched"])
            assertEquals(false, result["verified"])

            val received = latch.await(5, TimeUnit.SECONDS)
            assertTrue("系统分发必须真正启动声明的 TestClockReceiverActivity 并执行 onCreate", received)

            val intent = TestClockReceiverActivity.lastReceivedIntent
            assertNotNull(intent)
            assertEquals(AlarmClock.ACTION_SHOW_ALARMS, intent!!.action)
        }
    }

    @Test
    fun executeClockAction_showTimers_launchesRealFixtureActivity() {
        withTemporaryFixtureEnabled {
            val latch = TestClockReceiverActivity.prepare()

            val result = service.executeClockAction(
                action = AgentAlarmToolService.ACTION_SHOW_TIMERS
            )

            assertEquals(true, result["success"])
            assertEquals(true, result["dispatched"])
            assertEquals(false, result["verified"])

            val received = latch.await(5, TimeUnit.SECONDS)
            assertTrue("系统分发必须真正启动声明的 TestClockReceiverActivity 并执行 onCreate", received)

            val intent = TestClockReceiverActivity.lastReceivedIntent
            assertNotNull(intent)
            assertEquals(AlarmClock.ACTION_SHOW_TIMERS, intent!!.action)
        }
    }
}
