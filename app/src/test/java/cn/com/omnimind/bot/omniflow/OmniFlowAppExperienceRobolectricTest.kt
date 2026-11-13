package cn.com.omnimind.bot.omniflow

import android.app.Application
import android.content.Context
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.runlog.State
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OmniFlowAppExperienceRobolectricTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private class FakeProcess(
        stdout: String = "",
        private val stdoutStream: InputStream = ByteArrayInputStream(stdout.toByteArray(Charsets.UTF_8)),
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stderrStream = ByteArrayInputStream(ByteArray(0))
        var destroyed: Boolean = false
            private set

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
        override fun isAlive(): Boolean = !destroyed

        fun writtenText(): String = stdin.toString(Charsets.UTF_8.name())
    }

    private fun createExecutionRequest(runId: String): Any {
        val requestClass = Class.forName("cn.com.omnimind.bot.omniflow.ExecutionRequest")
        val toolCall = OmniFlow.ToolCall("run_gui", mapOf("display" to "1"))
        val constructor = requestClass.declaredConstructors.first { it.parameterCount == 11 }.apply {
            isAccessible = true
        }
        return constructor.newInstance(
            runId,
            "体验测试",
            "vlm_task",
            "run_gui",
            toolCall,
            "体验测试",
            "测试操作",
            1000L,
            "cancelled",
            "GUI_TASK_STOPPED",
            "GUI_TASK_FAILED",
        )
    }

    private fun createRealEnvironmentWithFakePlatform(context: Context): AndroidGuiEnvironment {
        val platformClass = Class.forName("cn.com.omnimind.androidgui.AndroidGuiPlatform")
        val fakePlatform = Proxy.newProxyInstance(
            platformClass.classLoader,
            arrayOf(platformClass),
        ) { _, method, _ ->
            when (method.name) {
                "isAccessibilityEnabled" -> true
                "isReady" -> true
                "awaitReady" -> true
                "screenshotExcludesOverlays" -> false
                "close" -> Unit
                "equals" -> false
                "hashCode" -> 1
                "toString" -> "FakeReadyPlatform"
                else -> null
            }
        }
        val envConstructor = AndroidGuiEnvironment::class.java.declaredConstructors.first {
            it.parameterCount == 3
        }.apply { isAccessible = true }
        return envConstructor.newInstance(context, fakePlatform, null) as AndroidGuiEnvironment
    }

    private fun createExecutionDispatcher(
        runId: String,
        runFinished: AtomicBoolean = AtomicBoolean(false),
    ): OmniFlowDeviceDispatcher {
        val dispatcher = OmniFlowDeviceDispatcher.create(context)
        val requestObj = createExecutionRequest(runId)
        val requestField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("request").apply {
            isAccessible = true
        }
        requestField.set(dispatcher, requestObj)
        val runFinishedField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("runFinished").apply {
            isAccessible = true
        }
        runFinishedField.set(dispatcher, runFinished)
        val targetDisplayIdField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("targetDisplayId\$delegate").apply {
            isAccessible = true
        }
        targetDisplayIdField.set(dispatcher, lazyOf(1))

        val realEnv = createRealEnvironmentWithFakePlatform(context)
        val envField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("environment").apply {
            isAccessible = true
        }
        envField.set(dispatcher, realEnv)

        return dispatcher
    }

    private fun injectFakePythonClient(fakeProcess: FakeProcess): () -> Unit {
        var reqCount = 0
        val client = OmniFlowPythonClient(
            processStarter = { _, _ -> fakeProcess },
            bridgeCommand = "python3 -m omniflow.bridge",
            requestIdFactory = { "req-${++reqCount}" },
        )
        runBlocking { client.initialize() }

        val readyField = OmniFlowPythonRuntime::class.java.getDeclaredField("ready").apply { isAccessible = true }
        val clientField = OmniFlowPythonRuntime::class.java.getDeclaredField("client").apply { isAccessible = true }
        val manifestField = OmniFlowPythonRuntime::class.java.getDeclaredField("activeManifest").apply { isAccessible = true }

        val origReady = readyField.get(OmniFlowPythonRuntime)
        val origClient = clientField.get(OmniFlowPythonRuntime)
        val origManifest = manifestField.get(OmniFlowPythonRuntime)

        val manifest = OmniFlowRuntimeManifest(
            version = "2.1.9",
            protocol = "2025-11-25",
            capabilities = emptySet(),
            bridgeContractSha256 = "",
            pythonVersion = "3.12",
            omniFlowCommit = "",
            omniFlowSourceSha256 = "",
            omniTransferCommit = "",
            omniTransferSourceSha256 = "",
            omniTransferCheckpoint = "",
            numpyVersion = "",
            jsonRepairVersion = "",
        )

        readyField.set(OmniFlowPythonRuntime, true)
        clientField.set(OmniFlowPythonRuntime, client)
        manifestField.set(OmniFlowPythonRuntime, manifest)

        return {
            readyField.set(OmniFlowPythonRuntime, origReady)
            clientField.set(OmniFlowPythonRuntime, origClient)
            manifestField.set(OmniFlowPythonRuntime, origManifest)
        }
    }

    @Test
    fun `dispatcher execute runs full sequence sealing log before learning and preserves registration and GUI success`() = runBlocking {
        val runId = "run-transcript-full"

        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "完整执行测试",
            source = "vlm_task",
            toolName = "run_gui",
            operationDescription = "执行操作",
            startedAtMs = 1000L,
        )

        val stdoutLines = listOf(
            // 1. 初始化响应
            """{"jsonrpc":"2.0","id":"req-1","result":{"protocolVersion":"2025-11-25"}}""",
            // 2. tools/call -> run_gui 返回成功、完成证据与后处理动作（save_function）
            """{"jsonrpc":"2.0","id":"req-2","result":{"success":true,"run_id":"$runId","done_reason":"finished","finished_evidence":{"status":"matched","source":"xml","observation_id":"obs-10"},"post_run_actions":[{"name":"save_function","arguments":{"run_id":"$runId"}}]}}""",
            // 3. tools/call -> save_function 触发 host_call get_run_log 读取已封存日志
            """{"jsonrpc":"2.0","id":"req-3:host:1","method":"omniflow/get_run_log","params":{"run_id":"$runId"}}""",
            // 4. tools/call -> save_function 返回注册结果
            """{"jsonrpc":"2.0","id":"req-3","result":{"success":true,"registered":true,"function_id":"fn-registered-1","status":"registered"}}""",
            // 5. tools/call -> learn_app_experiences 触发 host_call get_run_log 读取已封存日志
            """{"jsonrpc":"2.0","id":"req-4:host:1","method":"omniflow/get_run_log","params":{"run_id":"$runId"}}""",
            // 6. tools/call -> learn_app_experiences 返回错误码
            """{"jsonrpc":"2.0","id":"req-4","result":{"success":false,"error_code":"app_experience_store_invalid"}}""",
        ).joinToString("\n", postfix = "\n")

        val fakeProcess = FakeProcess(stdoutLines)
        val resetPython = injectFakePythonClient(fakeProcess)

        val runFinished = AtomicBoolean(false)
        val dispatcher = createExecutionDispatcher(runId, runFinished)

        try {
            val updated = dispatcher.execute()

            // 1. 验证 execute 返回结果合并完整
            assertTrue("GUI 结果 success 保持为 true", updated["success"] == true)
            assertEquals("finished", updated["done_reason"])
            assertEquals(runId, updated["run_id"])
            assertEquals(true, updated["auto_registered"])
            assertEquals("fn-registered-1", updated["registered_function_id"])
            assertEquals("app_experience_store_invalid", updated["experience_update_error"])

            // 2. 验证日志在 InternalRunLogStore 中已封存且证据已提取
            val sealed = InternalRunLogStore.getRun(context, runId)
            assertTrue("日志必须已封存", sealed != null && sealed.finishedAtMs != null)
            assertEquals(true, sealed!!.success)
            assertEquals("finished", sealed.doneReason)
            val diagEvidence = sealed.diagnostics["finished_evidence"] as? Map<*, *>
            assertEquals("matched", diagEvidence?.get("status"))
            assertEquals("obs-10", diagEvidence?.get("observation_id"))

            // 3. 验证向 Python 发出的 host response 明确包含已封存时间戳（证明封存先于学习读取）
            val writtenLines = fakeProcess.writtenText().lines()
            val hostResponseLine = writtenLines.firstOrNull { it.contains("req-4:host:1") }
            assertTrue("学习期间的 host_call get_run_log 必须有响应", hostResponseLine != null)
            val gson = Gson()
            val hostResponse = gson.fromJson(hostResponseLine, Map::class.java)
            val hostResult = hostResponse["result"] as Map<*, *>
            val finishedAt = hostResult["finished_at_ms"]
            assertTrue("学习读取时宿主返回的日志必须包含非空 finished_at_ms", finishedAt != null)
        } finally {
            resetPython()
        }
    }

    @Test
    fun `dispatcher execute on cancellation seals cancelled failure and skips learning`() = runBlocking {
        val runId = "run-transcript-cancelled"

        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "取消执行测试",
            source = "vlm_task",
            toolName = "run_gui",
            operationDescription = "取消操作",
            startedAtMs = 1000L,
        )

        val stdoutLines = listOf(
            """{"jsonrpc":"2.0","id":"req-1","result":{"protocolVersion":"2025-11-25"}}""",
        ).joinToString("\n", postfix = "\n")

        val fakeProcess = FakeProcess(stdoutLines)
        val resetPython = injectFakePythonClient(fakeProcess)

        val runFinished = AtomicBoolean(false)
        val dispatcher = createExecutionDispatcher(runId, runFinished)

        val started = CompletableDeferred<Unit>()
        val beforeOpField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("beforeOperation").apply {
            isAccessible = true
        }
        var cancelTriggered = false
        val beforeOp: suspend () -> Unit = {
            if (!cancelTriggered) {
                cancelTriggered = true
                started.complete(Unit)
                awaitCancellation()
            }
        }
        beforeOpField.set(dispatcher, beforeOp)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.execute()
        }
        started.await()
        job.cancel(CancellationException("user stopped"))
        job.join()

        try {
            assertTrue("取消后必须标记 runFinished 为 true", runFinished.get())

            val sealed = InternalRunLogStore.getRun(context, runId)
            assertTrue("取消日志必须在 store 封存", sealed != null && sealed.finishedAtMs != null)
            assertEquals(false, sealed!!.success)
            assertEquals("cancelled", sealed.doneReason)

            val written = fakeProcess.writtenText()
            assertFalse("取消后绝不能发出 learn_app_experiences", written.contains("learn_app_experiences"))
        } finally {
            resetPython()
        }
    }

    @Test
    fun `host rejects illegal run log and unlisted state reads during private learning`() = runBlocking {
        val runId = "run-learning-auth-test-robo"
        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "学习授权校验",
            source = "vlm_task",
            toolName = "run_gui",
            operationDescription = "学习测试",
            startedAtMs = 1000L,
        )

        val runFinished = AtomicBoolean(true)
        val dispatcher = createExecutionDispatcher(runId, runFinished)

        InternalRunLogStore.finishRun(
            context = context,
            runId = runId,
            success = true,
            doneReason = "finished",
            errorMessage = null,
            finishedAtMs = 2000L,
            finalStateId = "state-final-1",
        )

        val learningRunIdField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("privateLearningRunId").apply {
            isAccessible = true
        }
        val allowedStatesField = OmniFlowDeviceDispatcher::class.java.getDeclaredField("privateLearningAllowedStateIds").apply {
            isAccessible = true
        }

        learningRunIdField.set(dispatcher, runId)
        allowedStatesField.set(dispatcher, setOf("state-valid-1"))

        try {
            val validLog = dispatcher.callDevice("get_run_log", mapOf("run_id" to runId))
            assertTrue(validLog.isNotEmpty())

            val mismatchRunError = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    dispatcher.callDevice("get_run_log", mapOf("run_id" to "run-other-account"))
                }
            }
            assertEquals("unauthorized_run_id_for_learning", mismatchRunError.message)

            val unlistedStateError = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    dispatcher.callDevice("get_state", mapOf("state_id" to "state-forbidden-99"))
                }
            }
            assertTrue(unlistedStateError.message?.startsWith("unauthorized_state_id_for_learning") == true)
        } finally {
            learningRunIdField.set(dispatcher, null)
            allowedStatesField.set(dispatcher, emptySet<String>())
        }
    }

    @Test
    fun `historical run log is accessible when dispatcher has no active execution request`() = runBlocking {
        val historyRunId = "history-run-robo-1"
        InternalRunLogStore.beginRun(
            context = context,
            runId = historyRunId,
            goal = "历史日志",
            source = "vlm_task",
            toolName = "run_gui",
            operationDescription = "历史操作",
            startedAtMs = 1000L,
        )
        InternalRunLogStore.finishRun(
            context = context,
            runId = historyRunId,
            success = true,
            doneReason = "finished",
            errorMessage = null,
            finishedAtMs = 2000L,
            finalStateId = null,
        )

        val managementDispatcher = OmniFlowDeviceDispatcher.create(context)
        val log = managementDispatcher.callDevice("get_run_log", mapOf("run_id" to historyRunId))
        assertTrue("无 request 的管理 dispatcher 必须能读取历史日志", log.isNotEmpty())
    }

    @Test
    fun `ordinary execution allows get_state read for function replay`() = runBlocking {
        val runId = "run-replay-state-test"
        InternalRunLogStore.beginRun(
            context = context,
            runId = runId,
            goal = "重放读取测试",
            source = "vlm_task",
            toolName = "run_gui",
            operationDescription = "重放操作",
            startedAtMs = 1000L,
        )

        val testState = State.create(
            packageName = "com.test.replay",
            activityName = "MainActivity",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = "<hierarchy />",
            screenshotPath = null,
        )
        InternalRunLogStore.persistState(context, testState)

        val dispatcher = createExecutionDispatcher(runId)

        val stateMap = dispatcher.callDevice("get_state", mapOf("state_id" to testState.stateId))
        assertTrue("普通执行中必须允许重放状态读取", stateMap.isNotEmpty())
        assertEquals(testState.stateId, stateMap["state_id"])
    }
}
