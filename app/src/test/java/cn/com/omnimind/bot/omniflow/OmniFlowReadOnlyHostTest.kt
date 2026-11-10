package cn.com.omnimind.bot.omniflow

import android.app.Application
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OmniFlowReadOnlyHostTest {
    @Test
    fun `recorded failure remains readable without a live display`() {
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val runId = "read-only-failed-run"
            InternalRunLogStore.replaceRun(
                context = context,
                runId = runId,
                goal = "读取已经结束的后台任务",
                source = "vlm",
                operationDescription = "读取失败记录",
                steps = emptyList(),
                success = false,
                doneReason = "action_observation_expired",
            )

            val result = OmniFlowDeviceDispatcher.create(context).callDevice(
                method = "get_run_log",
                payload = mapOf("run_id" to runId),
            )

            assertEquals(runId, result["run_id"])
            assertEquals("failed", result["status"])
            assertFalse(result["success"] as Boolean)
            val diagnostics = result["diagnostics"] as Map<*, *>
            assertEquals("action_observation_expired", diagnostics["done_reason"])
        }
    }
}
