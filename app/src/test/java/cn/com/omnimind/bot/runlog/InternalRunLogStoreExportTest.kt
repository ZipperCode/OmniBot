package cn.com.omnimind.bot.runlog

import android.app.Application
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.OobActionSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class InternalRunLogStoreExportTest {

    @Test
    fun timelinePayloadExportsLongPressDurationAndConditionalWaitFields() {
        val context = RuntimeEnvironment.getApplication()
        val runId = "test-export-run"
        val state0 = "state-export-0"
        val state1 = "state-export-1"
        val state2 = "state-export-2"

        val testXml = "<hierarchy><node class=\"android.widget.Button\" text=\"去结算\" bounds=\"[100,100][300,300]\"/></hierarchy>"
        listOf(state0, state1, state2).forEach { sId ->
            InternalRunLogStore.persistState(
                context = context,
                state = cn.com.omnimind.baselib.runlog.State(
                    stateId = sId,
                    packageName = "cn.com.omnimind.androidgui.test",
                    activityName = "cn.com.omnimind.androidgui.fixture.TestGuiFixtureActivity",
                    displayWidth = 1000,
                    displayHeight = 1000,
                    xml = testXml,
                ),
            )
        }

        InternalRunLogStore.replaceRun(
            context = context,
            runId = runId,
            goal = "测试导出长按与条件等待",
            source = "vlm",
            operationDescription = "测试导出",
            steps = listOf(
                mapOf(
                    "step_index" to 0,
                    "before_state_id" to state0,
                    "action" to mapOf(
                        "tool" to OobActionSchema.TOOL_LONG_PRESS,
                        "args" to mapOf(
                            OobActionSchema.ARG_X to 100.0,
                            OobActionSchema.ARG_Y to 200.0,
                            OobActionSchema.ARG_DURATION_MS to 700L,
                            OobActionSchema.ARG_TARGET_DESCRIPTION to "长按目标",
                        ),
                    ),
                    "result" to mapOf("success" to true),
                    "after_state_id" to state1,
                ),
                mapOf(
                    "step_index" to 1,
                    "before_state_id" to state1,
                    "action" to mapOf(
                        "tool" to OobActionSchema.TOOL_WAIT,
                        "args" to mapOf(
                            OobActionSchema.ARG_DURATION_MS to 2500L,
                            "condition" to "text_present",
                            OobActionSchema.ARG_TARGET_DESCRIPTION to "去结算",
                        ),
                    ),
                    "result" to mapOf("success" to true),
                    "after_state_id" to state2,
                ),
                mapOf(
                    "step_index" to 2,
                    "before_state_id" to state2,
                    "action" to mapOf(
                        "tool" to OobActionSchema.TOOL_WAIT,
                        "args" to mapOf(
                            OobActionSchema.ARG_DURATION_MS to 1000L,
                        ),
                    ),
                    "result" to mapOf("success" to true),
                    "after_state_id" to state2,
                ),
            ),
            success = true,
            doneReason = "finished",
        )

        val timeline = InternalRunLogStore.timelinePayload(context, runId)
        val steps = timeline["steps"] as List<*>
        assertEquals(3, steps.size)

        val step0 = steps[0] as Map<*, *>
        val action0 = step0["action"] as Map<*, *>
        assertEquals("long_press", action0["action_type"])
        assertEquals("long_press 导出公共 RunLog 时必须保留 duration_ms", 700L, (action0["duration_ms"] as Number).toLong())

        val step1 = steps[1] as Map<*, *>
        val action1 = step1["action"] as Map<*, *>
        assertEquals("wait", action1["action_type"])
        assertEquals("wait 导出公共 RunLog 时必须保留 duration_ms", 2500L, (action1["duration_ms"] as Number).toLong())
        assertEquals("wait 导出公共 RunLog 时必须保留 condition", "text_present", action1["condition"])
        assertEquals("wait 导出公共 RunLog 时必须保留 target_description", "去结算", action1["target_description"])

        val step2 = steps[2] as Map<*, *>
        val action2 = step2["action"] as Map<*, *>
        assertEquals("wait", action2["action_type"])
        assertEquals(1000L, (action2["duration_ms"] as Number).toLong())
        assertNull("无条件 wait 不得导出 condition", action2["condition"])
        assertNull("无条件 wait 不得导出 target_description", action2["target_description"])
    }
}
