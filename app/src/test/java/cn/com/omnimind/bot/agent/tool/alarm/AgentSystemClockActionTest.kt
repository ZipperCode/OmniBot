package cn.com.omnimind.bot.agent.tool.alarm

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.AlarmClock
import cn.com.omnimind.bot.agent.AgentAlarmCreateRequest
import cn.com.omnimind.bot.agent.AgentAlarmToolService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class AgentSystemClockActionTest {
    private lateinit var application: Application
    private lateinit var shadowApplication: ShadowApplication
    private lateinit var shadowPackageManager: ShadowPackageManager
    private lateinit var service: AgentAlarmToolService

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        shadowApplication = shadowOf(application)
        shadowPackageManager = shadowOf(application.packageManager)
        service = AgentAlarmToolService(application)
        drainStartedActivities()
    }

    @After
    fun tearDown() {
        drainStartedActivities()
    }

    private fun drainStartedActivities() {
        while (shadowApplication.nextStartedActivity != null) {
            // 清理队列中累积的 Intent
        }
    }

    private fun registerClockReceiver(action: String) {
        val activityInfo = ActivityInfo().apply {
            name = "com.android.deskclock.DeskClockActivity"
            packageName = application.packageName
            applicationInfo = application.applicationInfo
            exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = activityInfo
            this.filter = IntentFilter(action)
        }
        shadowPackageManager.addResolveInfoForIntent(Intent(action), resolveInfo)
    }

    @Test
    fun createTimer_successfulDispatch_verifiesIntentAndReturnsDispatchedOnly() {
        registerClockReceiver(AlarmClock.ACTION_SET_TIMER)

        val result = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 60,
            title = "煮蛋",
            skipUi = true
        )

        assertEquals(true, result["success"])
        assertEquals(true, result["dispatched"])
        assertEquals(false, result["verified"])
        assertEquals("create_timer", result["action"])
        assertEquals("已交给系统时钟处理，请在时钟中确认", result["summary"])
        assertFalse("不得伪造 alarmId 或返回私有 ID", result.containsKey("alarmId"))
        assertFalse("不得伪造 timerId 或返回私有 ID", result.containsKey("timerId"))

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull("必须发起系统 Intent", startedIntent)
        assertEquals(AlarmClock.ACTION_SET_TIMER, startedIntent!!.action)
        assertEquals(60, startedIntent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("煮蛋", startedIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(true, startedIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue("必须包含 FLAG_ACTIVITY_NEW_TASK", (startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

    }

    @Test
    fun createTimer_durationBoundaries_exactAndInvalidRanges() {
        registerClockReceiver(AlarmClock.ACTION_SET_TIMER)

        // 边界值 1 秒（允许范围下限）
        val resultMin = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 1
        )
        assertEquals(true, resultMin["success"])
        assertEquals(true, resultMin["dispatched"])
        assertEquals(false, resultMin["verified"])
        val minIntent = shadowApplication.nextStartedActivity
        assertNotNull(minIntent)
        assertEquals(1, minIntent!!.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))

        // 边界值 86400 秒（允许范围上限：24小时）
        val resultMax = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 86400
        )
        assertEquals(true, resultMax["success"])
        assertEquals(true, resultMax["dispatched"])
        assertEquals(false, resultMax["verified"])
        val maxIntent = shadowApplication.nextStartedActivity
        assertNotNull(maxIntent)
        assertEquals(86400, maxIntent!!.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))

        // 非法边界：0 秒（必须拒绝）
        val resultZero = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 0
        )
        assertEquals(false, resultZero["success"])
        assertEquals("invalid_argument", resultZero["code"])
        assertEquals(false, resultZero["dispatched"])
        assertEquals(false, resultZero["verified"])
        assertNull("非法参数不应启动 Activity", shadowApplication.nextStartedActivity)

        // 非法边界：负数（必须拒绝）
        val resultNegative = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = -10
        )
        assertEquals(false, resultNegative["success"])
        assertEquals("invalid_argument", resultNegative["code"])
        assertEquals(false, resultNegative["dispatched"])
        assertNull(shadowApplication.nextStartedActivity)

        // 非法边界：超过 86400 秒（必须拒绝）
        val resultOver = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 86401
        )
        assertEquals(false, resultOver["success"])
        assertEquals("invalid_argument", resultOver["code"])
        assertEquals(false, resultOver["dispatched"])
        assertNull(shadowApplication.nextStartedActivity)

        // 非法边界：durationSeconds 为 null（必须拒绝）
        val resultNull = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = null
        )
        assertEquals(false, resultNull["success"])
        assertEquals("invalid_argument", resultNull["code"])
        assertEquals(false, resultNull["dispatched"])
        assertNull(shadowApplication.nextStartedActivity)
    }

    @Test
    fun showAlarms_successfulDispatch_verifiesIntent() {
        registerClockReceiver(AlarmClock.ACTION_SHOW_ALARMS)

        val result = service.executeClockAction(AgentAlarmToolService.ACTION_SHOW_ALARMS)

        assertEquals(true, result["success"])
        assertEquals(true, result["dispatched"])
        assertEquals(false, result["verified"])
        assertEquals("show_alarms", result["action"])
        assertEquals("已交给系统时钟处理，请在时钟中确认", result["summary"])

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, startedIntent!!.action)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

    }

    @Test
    fun showTimers_successfulDispatch_verifiesIntent() {
        registerClockReceiver(AlarmClock.ACTION_SHOW_TIMERS)

        val result = service.executeClockAction(AgentAlarmToolService.ACTION_SHOW_TIMERS)

        assertEquals(true, result["success"])
        assertEquals(true, result["dispatched"])
        assertEquals(false, result["verified"])
        assertEquals("show_timers", result["action"])
        assertEquals("已交给系统时钟处理，请在时钟中确认", result["summary"])

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(AlarmClock.ACTION_SHOW_TIMERS, startedIntent!!.action)
        assertTrue((startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

    }

    @Test
    fun missingHandler_returnsUnsupportedActionWithoutStartingActivity() {
        // 不注册任何 handler
        val result = service.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 60
        )

        assertEquals(false, result["success"])
        assertEquals("unsupported_action", result["code"])
        assertEquals(false, result["dispatched"])
        assertEquals(false, result["verified"])
        assertEquals("create_timer", result["action"])
        assertEquals("当前设备不支持该时钟操作", result["summary"])
        assertNull("缺少 handler 时不得启动 Activity", shadowApplication.nextStartedActivity)
    }

    @Test
    fun startActivitySecurityDenial_returnsFailureGracefully() {
        registerClockReceiver(AlarmClock.ACTION_SET_TIMER)

        val securityFailingContext = object : ContextWrapper(application) {
            override fun startActivity(intent: Intent?) {
                throw SecurityException("Permission Denial: requires com.android.alarm.permission.SET_ALARM")
            }
        }
        val failingService = AgentAlarmToolService(securityFailingContext)

        val result = failingService.executeClockAction(
            action = AgentAlarmToolService.ACTION_CREATE_TIMER,
            durationSeconds = 30
        )

        assertEquals(false, result["success"])
        assertEquals("activity_launch_failed", result["code"])
        assertEquals(false, result["dispatched"])
        assertEquals(false, result["verified"])
        assertEquals("create_timer", result["action"])
        assertTrue(
            "错误说明必须说明启动失败",
            (result["summary"] as String).contains("启动系统时钟失败")
        )
    }

    @Test
    fun arbitraryActions_rejectedAsUnsupportedAction() {
        // 不支持的操作如 dismiss_all、delete_alarm、未定义动作
        listOf("dismiss_all", "delete_alarm", "update_timer", "invalid_action").forEach { action ->
            val result = service.executeClockAction(action = action)
            assertEquals("操作 $action 必须返回 unsupported_action", false, result["success"])
            assertEquals("unsupported_action", result["code"])
            assertEquals(false, result["dispatched"])
            assertEquals(false, result["verified"])
            assertNull("非法操作不得启动任何 Activity", shadowApplication.nextStartedActivity)
        }
    }

    @Test
    fun clockAppReminderCreate_preservesDispatchedAndVerifiedContract() {
        registerClockReceiver(AlarmClock.ACTION_SET_ALARM)

        val request = AgentAlarmCreateRequest(
            mode = AgentAlarmToolService.MODE_CLOCK_APP,
            title = "早起提醒",
            triggerAt = "2026-11-08T07:30:00+08:00",
            message = "起床了",
            timezone = "Asia/Shanghai",
            allowWhileIdle = true,
            skipUi = true
        )

        val result = service.createReminder(request)

        assertEquals(true, result["success"])
        assertEquals(true, result["dispatched"])
        assertEquals(false, result["verified"])
        assertEquals(AgentAlarmToolService.MODE_CLOCK_APP, result["mode"])
        assertEquals("早起提醒", result["title"])
        assertEquals("已发起系统闹钟创建“早起提醒”", result["summary"])

        val startedIntent = shadowApplication.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(AlarmClock.ACTION_SET_ALARM, startedIntent!!.action)
        assertEquals("早起提醒", startedIntent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertEquals(true, startedIntent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }
}
