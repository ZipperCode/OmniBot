package cn.com.omnimind.bot.task.runtime

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import cn.com.omnimind.bot.agent.display.ScreenTaskOverlayHost
import cn.com.omnimind.bot.agent.display.ScreenTaskTheme
import cn.com.omnimind.bot.util.TaskRuntimeSettings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class TaskRuntimeScreenOverlayTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(pm).setIsInteractive(true)
        ShadowSettings.setCanDrawOverlays(true)
        TaskRuntime.resetForTesting()
        ScreenTaskOverlayHost.dismissAll()
        ShadowLooper.idleMainLooper()
    }

    @After
    fun tearDown() {
        ScreenTaskOverlayHost.dismissAll()
        TaskRuntime.resetForTesting()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `markScreenTarget with non-null turn requires exact turn match and rejects null or mismatched turn`() {
        val sessionId = "session-test-1"
        val turn1 = "turn-101"
        val taskId1 = "agent-turn:$sessionId:$turn1"

        // 启动带 turnId 的任务
        TaskRuntime.start(
            context = context,
            taskId = taskId1,
            sessionId = sessionId,
            turnId = turn1,
            conversationId = 1L,
        )

        // 1. 传入不匹配的 turnId，必须被拒绝，不能污染当前任务
        val mismatched = TaskRuntime.markScreenTarget(
            context = context,
            sessionId = sessionId,
            turnId = "turn-old-stale",
            displayId = 9,
        )
        assertFalse("旧的或不匹配的 turnId 不得匹配", mismatched)
        assertNull(TaskRuntime.getActiveTask(taskId1)?.screenDisplayId)

        // 启动一个没有 turnId 的普通任务
        val taskNoTurnId = "ordinary-task-no-turn"
        TaskRuntime.start(
            context = context,
            taskId = taskNoTurnId,
            sessionId = "session-no-turn",
            turnId = null,
            conversationId = 2L,
        )

        // 2. 传入 non-null turnId，匹配没有 turnId 的 task 时必须拒绝，不能放行（P1边界回归）
        val rejectedNullTurn = TaskRuntime.markScreenTarget(
            context = context,
            sessionId = "session-no-turn",
            turnId = "turn-new",
            displayId = 9,
        )
        assertFalse("传入 non-null turn 时，当前 task.turnId == null 严禁放行", rejectedNullTurn)
        assertNull(TaskRuntime.getActiveTask(taskNoTurnId)?.screenDisplayId)

        // 3. 传入精确匹配的 turnId，正确标记
        val matched = TaskRuntime.markScreenTarget(
            context = context,
            sessionId = sessionId,
            turnId = turn1,
            displayId = 9,
        )
        assertTrue("精确 turnId 应当成功匹配", matched)
        assertEquals(9, TaskRuntime.getActiveTask(taskId1)?.screenDisplayId)
    }

    @Test
    fun `markScreenTarget with null turn matches session active task`() {
        val sessionId = "session-null-turn"
        val taskId = "agent-turn:$sessionId:t1"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = "t1",
            conversationId = 3L,
        )

        val matched = TaskRuntime.markScreenTarget(
            context = context,
            sessionId = sessionId,
            turnId = null,
            displayId = 0,
        )
        assertTrue(matched)
        assertEquals(0, TaskRuntime.getActiveTask(taskId)?.screenDisplayId)
    }

    @Test
    fun `finish with null terminalStatus does not fake success and cleans up`() {
        val taskId = "task-cleanup"
        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = "sess-c",
            turnId = "turn-c",
        )
        TaskRuntime.markScreenTarget(context, "sess-c", "turn-c", displayId = 9)
        assertEquals(9, TaskRuntime.getActiveTask(taskId)?.screenDisplayId)

        // 普通 cleanup/disconnect 传入 null terminalStatus
        val finished = TaskRuntime.finish(context, taskId, terminalStatus = null)
        assertTrue(finished)
        assertNull(TaskRuntime.getActiveTask(taskId))
        assertNull("普通清理绝不弹出结果卡片", ScreenTaskOverlayHost.getShowingResultTaskId())
        assertNull("普通清理无结果卡片视图", ScreenTaskOverlayHost.getResultCardView())
    }

    @Test
    fun `finish with official terminalStatus records result and allows in-place message update`() {
        val sessionId = "sess-result"
        val turnId = "turn-result"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = turnId,
            conversationId = 10L,
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId = 9)

        // 完成时数据库未到，显示诚实默认文案
        TaskRuntime.finish(context, taskId, terminalStatus = "end_turn")
        ShadowLooper.idleMainLooper()
        assertNull(TaskRuntime.getActiveTask(taskId))
        assertEquals(taskId, ScreenTaskOverlayHost.getShowingResultTaskId())
        assertEquals("本轮屏幕操作已结束，请返回对话查看结果。", ScreenTaskOverlayHost.getResultCardView()?.getCurrentMessage())
        assertEquals("操作已完成", ScreenTaskOverlayHost.getResultCardView()?.getStatusText())

        // 后续历史 snapshot 到库，平滑更新
        TaskRuntime.onAssistantMessagePersisted(sessionId, turnId, "计算结果为 56")
        ShadowLooper.idleMainLooper()
        assertEquals("计算结果为 56", ScreenTaskOverlayHost.getResultCardView()?.getCurrentMessage())
        assertEquals("操作已完成", ScreenTaskOverlayHost.getResultCardView()?.getStatusText())
    }

    @Test
    fun `suppressResultPresentation prevents showResult when user explicitly takes over`() {
        val sessionId = "sess-takeover"
        val turnId = "turn-takeover"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(
            context = context,
            taskId = taskId,
            sessionId = sessionId,
            turnId = turnId,
            conversationId = 11L,
        )
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId = 9)

        // 接管前抑制弹窗
        TaskRuntime.suppressResultPresentation(taskId)

        // 移交取消官方状态仍为 cancelled
        val finished = TaskRuntime.finish(context, taskId, terminalStatus = "cancelled")
        assertTrue(finished)
        assertNull(TaskRuntime.getActiveTask(taskId))
    }

    @Test
    fun `suppressResultPresentationForSession binds to active task and does not trap future turns`() {
        val sessionId = "sess-future"
        val turn1 = "turn-1"
        val task1 = "agent-turn:$sessionId:$turn1"

        TaskRuntime.start(
            context = context,
            taskId = task1,
            sessionId = sessionId,
            turnId = turn1,
        )
        TaskRuntime.markScreenTarget(context, sessionId, turn1, displayId = 9)

        // 接管抑制绑定到当前 task1
        TaskRuntime.suppressResultPresentationForSession(sessionId, expectedTurnId = turn1)

        // 结束 turn1
        TaskRuntime.finish(context, task1, terminalStatus = "cancelled")

        // 启动 turn2，不应该被 turn1 的抑制残留影响
        val turn2 = "turn-2"
        val task2 = "agent-turn:$sessionId:$turn2"
        TaskRuntime.start(
            context = context,
            taskId = task2,
            sessionId = sessionId,
            turnId = turn2,
        )
        TaskRuntime.markScreenTarget(context, sessionId, turn2, displayId = 9)

        TaskRuntime.finish(context, task2, terminalStatus = "completed")
        assertNull(TaskRuntime.getActiveTask(task2))
    }

    @Test
    fun `markScreenTarget with null turn rejects when multiple tasks exist in session`() {
        val sessionId = "session-multi"
        TaskRuntime.start(context, "task-m-1", sessionId = sessionId, turnId = "turn-1")
        TaskRuntime.start(context, "task-m-2", sessionId = sessionId, turnId = "turn-2")

        // 无 turn 匹配，但存在多个候选任务，必须拒绝模糊标记
        val marked = TaskRuntime.markScreenTarget(context, sessionId, turnId = null, displayId = 0)
        assertFalse("存在多个任务时无 turnId 不得模糊标记", marked)
    }

    @Test
    fun `onAssistantMessagePersisted only updates completed status and does not overwrite error`() {
        val sessionId = "session-err"
        val turnId = "turn-err"
        val taskId = "agent-turn:$sessionId:$turnId"

        TaskRuntime.start(context, taskId, sessionId = sessionId, turnId = turnId, conversationId = 20L)
        TaskRuntime.markScreenTarget(context, sessionId, turnId, displayId = 9)

        // 官方终态为 error
        TaskRuntime.finish(context, taskId, terminalStatus = "error", error = "执行失败：网络连接超时")
        ShadowLooper.idleMainLooper()
        assertEquals(taskId, ScreenTaskOverlayHost.getShowingResultTaskId())
        assertEquals("执行失败：网络连接超时", ScreenTaskOverlayHost.getResultCardView()?.getCurrentMessage())
        assertEquals("操作未完成", ScreenTaskOverlayHost.getResultCardView()?.getStatusText())

        // 晚到的正文不应该覆盖错误
        TaskRuntime.onAssistantMessagePersisted(sessionId, turnId, "晚到的回答文本")
        ShadowLooper.idleMainLooper()
        assertEquals("错误结果绝不可被晚到正文覆盖", "执行失败：网络连接超时", ScreenTaskOverlayHost.getResultCardView()?.getCurrentMessage())
        assertEquals("操作未完成", ScreenTaskOverlayHost.getResultCardView()?.getStatusText())

        // 手动关闭后，晚到更新绝不重新复活
        ScreenTaskOverlayHost.dismissTask(taskId)
        ShadowLooper.idleMainLooper()
        assertNull(ScreenTaskOverlayHost.getShowingResultTaskId())
        assertNull(ScreenTaskOverlayHost.getResultCardView())

        TaskRuntime.onAssistantMessagePersisted(sessionId, turnId, "关闭后迟到的正文")
        ShadowLooper.idleMainLooper()
        assertNull("用户关闭后绝不重新复活弹窗", ScreenTaskOverlayHost.getShowingResultTaskId())
        assertNull(ScreenTaskOverlayHost.getResultCardView())
    }

    @Test
    fun `system overlay theme follows attached activity while explicit choice wins and detach restores context`() {
        val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val key = "flutter.theme_option"
        val originalMode = prefs.getString(key, null)
        try {
            for (activityNightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val overlayNightMode = if (activityNightMode == Configuration.UI_MODE_NIGHT_NO) {
                    Configuration.UI_MODE_NIGHT_YES
                } else {
                    Configuration.UI_MODE_NIGHT_NO
                }
                val overlayContext = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or overlayNightMode
                })
                val controller = Robolectric.buildActivity(Activity::class.java).setup()
                controller.configurationChange(Configuration(controller.get().resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or activityNightMode
                })
                val activity = controller.get()
                TaskRuntimeSettings.attachActivity(activity)
                try {
                    prefs.edit().putString(key, "system").commit()
                    assertEquals(activityNightMode == Configuration.UI_MODE_NIGHT_YES, ScreenTaskTheme.isNight(overlayContext))
                    prefs.edit().putString(key, "dark").commit()
                    assertTrue(ScreenTaskTheme.isNight(overlayContext))
                    prefs.edit().putString(key, "light").commit()
                    assertFalse(ScreenTaskTheme.isNight(overlayContext))
                    prefs.edit().putString(key, "system").commit()
                    TaskRuntimeSettings.detachActivity(activity)
                    assertEquals(overlayNightMode == Configuration.UI_MODE_NIGHT_YES, ScreenTaskTheme.isNight(overlayContext))
                } finally {
                    TaskRuntimeSettings.detachActivity(activity)
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            prefs.edit().apply {
                if (originalMode == null) remove(key) else putString(key, originalMode)
            }.commit()
        }
    }
}
