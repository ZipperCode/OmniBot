package cn.com.omnimind.bot.agent.tool.handlers

import android.app.Application
import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.androidgui.AndroidGuiObservation
import cn.com.omnimind.androidgui.AndroidGuiObservationRegion
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.PrivilegedDisplayBridge
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.llm.AssistantToolCallFunction
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class DeviceDisplayToolHandlerPerceptionTest {

    private lateinit var context: Context
    private lateinit var mockEnvironment: AndroidGuiEnvironment
    private lateinit var handler: DeviceDisplayToolHandler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockEnvironment = Mockito.mock(AndroidGuiEnvironment::class.java)
        handler = DeviceDisplayToolHandler(context, mockEnvironment)
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.displayBridge = FakePerceptionBridge(2)
        val vfField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        val voField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        vfField.setInt(DeviceDisplayManager, 2)
        voField.set(DeviceDisplayManager, "session-p-test")
        DisplayInputCoordinator.registerVirtualDisplayOwnership(2, "session-p-test")
    }

    @After
    fun tearDown() {
        DisplayInputCoordinator.resetForTesting()
        DeviceDisplayManager.displayBridge = null
        val vfField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
        val voField = DeviceDisplayManager::class.java
            .getDeclaredField("activeVirtualDisplayOwner").apply { isAccessible = true }
        vfField.setInt(DeviceDisplayManager, Display.DEFAULT_DISPLAY)
        voField.set(DeviceDisplayManager, null)
    }

    private fun anyAction(): Action {
        Mockito.any(Action::class.java)
        return Action("wait", emptyMap())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNullable(): T? {
        Mockito.any<T>()
        return null
    }

    private fun mockEnv(sessionId: String = "session-p-test", turnId: String = "turn-p-1"): AgentExecutionEnvironment {
        val env = Mockito.mock(AgentExecutionEnvironment::class.java)
        Mockito.`when`(env.conversationId).thenReturn(100L)
        Mockito.`when`(env.acpSessionId).thenReturn(sessionId)
        Mockito.`when`(env.acpTurnId).thenReturn(turnId)
        Mockito.`when`(env.agentRunId).thenReturn("run-$sessionId")
        return env
    }

    private fun mockDescriptor() = AgentToolRegistry.RuntimeToolDescriptor(
        DeviceDisplayToolHandler.TOOL_NAME,
        DeviceDisplayToolHandler.TOOL_NAME,
        ""
    )

    private fun mockToolCall(id: String = "call-1") = AssistantToolCall(
        id = id,
        type = "function",
        function = AssistantToolCallFunction(DeviceDisplayToolHandler.TOOL_NAME, "{}")
    )

    private fun createTestState(
        displayId: Int = Display.DEFAULT_DISPLAY,
        stateId: String = "state_1",
        packageName: String = "com.test.app",
    ): State {
        return State(
            stateId = stateId,
            packageName = "com.test.app",
            activityName = "com.test.app.MainActivity",
            displayWidth = 1080,
            displayHeight = 2400,
            xml = "<hierarchy rotation=\"0\"><node text=\"测试按钮\" bounds=\"[100,200][400,300]\" /></hierarchy>",
            screenshotPath = null,
        )
    }

    @Test
    fun `get_state with ocr and region requests screenshot and returns genuine nested json`() {
        runBlocking {
            val state = createTestState()
            val observationId = "obs_nested_test_123"
            val fakeBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="

            val extra = mapOf(
                "observation_id" to observationId,
                "display_id" to Display.DEFAULT_DISPLAY,
                "ocr" to mapOf(
                    "status" to "ready",
                    "engine" to "paddle_ocr_v6",
                    "cached" to false,
                    "items" to listOf(
                        mapOf(
                            "text" to "登录",
                            "bounds" to listOf(150, 220, 350, 280),
                            "confidence" to 0.98,
                            "source" to "ocr"
                        )
                    )
                ),
                "visual_regions" to listOf(
                    mapOf(
                        "bounds" to listOf(100.0, 200.0, 500.0, 600.0),
                        "image_base64" to fakeBase64,
                        "width" to 400,
                        "height" to 400
                    )
                )
            )

            val observation = AndroidGuiObservation(
                state = state,
                stabilization = mapOf("stabilized" to true),
                extra = extra
            )

            Mockito.`when`(
                mockEnvironment.observeWithDiagnostics(
                    captureScreenshot = Mockito.eq(true),
                    waitToStabilize = Mockito.eq(false),
                    displayId = Mockito.eq(2),
                    leaseOwnerId = Mockito.anyString(),
                    ocr = Mockito.eq(true),
                    region = Mockito.eq(AndroidGuiObservationRegion(100.0, 200.0, 500.0, 600.0)),
                )
            ).thenReturn(observation)

            val args = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
                put("ocr", true)
                put("region", buildJsonObject {
                    put("x1", 100.0)
                    put("y1", 200.0)
                    put("x2", 500.0)
                    put("y2", 600.0)
                })
            }

            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            val result = handler.execute(mockToolCall(), args, mockDescriptor(), env, callback, handle)
            assertTrue("get_state with perception options must succeed", result is ToolExecutionResult.ContextResult)
            val contextResult = result as ToolExecutionResult.ContextResult
            assertTrue(contextResult.success)

            // Verify imageDataUrl is extracted from visual_regions[0].image_base64
            assertEquals("data:image/jpeg;base64,$fakeBase64", contextResult.imageDataUrl)

            // Parse rawResultJson and verify genuine nested structures
            val jsonElement = Json.parseToJsonElement(contextResult.rawResultJson).jsonObject
            assertEquals(observationId, jsonElement["observation_id"]?.jsonPrimitive?.content)
            assertEquals("com.test.app", jsonElement["package_name"]?.jsonPrimitive?.content)

            // Nested OCR structure must NOT be a flattened string like "{status=ready}"
            val ocrObj = jsonElement["ocr"]?.jsonObject
            assertNotNull("OCR must be a real JSON object", ocrObj)
            assertEquals("ready", ocrObj!!["status"]?.jsonPrimitive?.content)
            val ocrItems = ocrObj["items"]?.jsonArray
            assertNotNull("OCR items must be a real JSON array", ocrItems)
            assertEquals(1, ocrItems!!.size)
            val firstItem = ocrItems[0].jsonObject
            assertEquals("登录", firstItem["text"]?.jsonPrimitive?.content)
            assertEquals("ocr", firstItem["source"]?.jsonPrimitive?.content)

            // 验证 visual_regions 保持结构元数据（bounds、width、height），但 image_base64 必须已被剔除，避免 prompt 载荷重复膨胀
            val regions = jsonElement["visual_regions"]?.jsonArray
            assertNotNull("visual_regions 必须是有效的 JSON 数组", regions)
            assertEquals(1, regions!!.size)
            val region0 = regions[0].jsonObject
            assertNull("image_base64 必须在 rawResultJson 中剔除以防 token 爆炸", region0["image_base64"])
            assertNotNull("bounds 元数据必须保留", region0["bounds"])
            assertEquals(400, region0["width"]?.jsonPrimitive?.intOrNull)
            assertEquals(400, region0["height"]?.jsonPrimitive?.intOrNull)
        }
    }

    @Test
    fun `ordinary get_state without ocr does not expose ocr noise even if extra contains failed diagnostic`() {
        runBlocking {
            val state = createTestState()
            val observationId = "obs_tree_only_456"

            val extra = mapOf(
                "observation_id" to observationId,
                "display_id" to Display.DEFAULT_DISPLAY,
                "frame_sequence" to 10L,
                "ocr" to mapOf("status" to "failed", "message" to "screenshot_unavailable_for_ocr"),
            )

            val observation = AndroidGuiObservation(
                state = state,
                stabilization = emptyMap(),
                extra = extra
            )

            Mockito.`when`(
                mockEnvironment.observeWithDiagnostics(
                    captureScreenshot = Mockito.eq(false),
                    waitToStabilize = Mockito.eq(false),
                    displayId = Mockito.eq(2),
                    leaseOwnerId = Mockito.anyString(),
                    ocr = Mockito.eq(false),
                    region = Mockito.isNull(),
                )
            ).thenReturn(observation)

            val args = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
            }

            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            val result = handler.execute(mockToolCall(), args, mockDescriptor(), env, callback, handle)
            assertTrue(result is ToolExecutionResult.ContextResult)
            val contextResult = result as ToolExecutionResult.ContextResult
            assertTrue(contextResult.success)
            assertNull("Ordinary get_state should have null imageDataUrl", contextResult.imageDataUrl)

            val jsonElement = Json.parseToJsonElement(contextResult.rawResultJson).jsonObject
            assertEquals(observationId, jsonElement["observation_id"]?.jsonPrimitive?.content)
            assertEquals("com.test.app", jsonElement["package_name"]?.jsonPrimitive?.content)
            assertNull("Ordinary get_state must not expose ocr noise", jsonElement["ocr"])
        }
    }

    @Test
    fun `get_state with invalid region area or bounds rejects and does not observe`() {
        runBlocking {
            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            // Invalid: x2 <= x1 (zero/negative area)
            val argsZeroArea = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
                put("region", buildJsonObject {
                    put("x1", 500.0)
                    put("y1", 200.0)
                    put("x2", 400.0) // x2 < x1
                    put("y2", 600.0)
                })
            }

            val resultZeroArea = handler.execute(mockToolCall("call-err-1"), argsZeroArea, mockDescriptor(), env, callback, handle)
            assertTrue("Invalid region area must result in Error", resultZeroArea is ToolExecutionResult.Error)

            // Invalid: coordinate > 1000 out of bounds
            val argsOutOfBounds = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
                put("region", buildJsonObject {
                    put("x1", 0.0)
                    put("y1", 0.0)
                    put("x2", 1500.0) // > 1000
                    put("y2", 500.0)
                })
            }

            val resultOutOfBounds = handler.execute(mockToolCall("call-err-2"), argsOutOfBounds, mockDescriptor(), env, callback, handle)
            assertTrue("Out of bounds region must result in Error", resultOutOfBounds is ToolExecutionResult.Error)

            // Non-object region (e.g. array or primitive) must be strictly rejected
            val argsNonObject = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
                put("region", "0,0,100,100")
            }

            val resultNonObject = handler.execute(mockToolCall("call-err-3"), argsNonObject, mockDescriptor(), env, callback, handle)
            assertTrue("Non-object region must result in Error", resultNonObject is ToolExecutionResult.Error)

            // Environment observeWithDiagnostics must never be called for invalid regions
            Mockito.verify(mockEnvironment, Mockito.never()).observeWithDiagnostics(
                captureScreenshot = Mockito.anyBoolean(),
                waitToStabilize = Mockito.anyBoolean(),
                displayId = Mockito.anyInt(),
                leaseOwnerId = anyNullable(),
                ocr = Mockito.anyBoolean(),
                region = anyNullable(),
            )
        }
    }

    @Test
    fun `get_state with invalid non-boolean ocr rejects and does not observe`() {
        runBlocking {
            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            val args = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
                put("ocr", "yes") // not a boolean
            }

            val result = handler.execute(mockToolCall(), args, mockDescriptor(), env, callback, handle)
            assertTrue(result is ToolExecutionResult.Error)
            val err = result as ToolExecutionResult.Error
            assertTrue(err.message.contains("ocr"))

            Mockito.verify(mockEnvironment, Mockito.never()).observeWithDiagnostics(
                captureScreenshot = Mockito.anyBoolean(),
                waitToStabilize = Mockito.anyBoolean(),
                displayId = Mockito.anyInt(),
                leaseOwnerId = anyNullable(),
                ocr = Mockito.anyBoolean(),
                region = anyNullable(),
            )
        }
    }

    @Test
    fun `click with expired observation_id rejects and does not call environment act`() {
        runBlocking {
            val clickArgs = buildJsonObject {
                put("action", "click")
                put("display", "virtual")
                put("x", 200.0)
                put("y", 200.0)
                put("observation_id", "obs_expired_nonexistent")
            }

            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            val result = handler.execute(mockToolCall(), clickArgs, mockDescriptor(), env, callback, handle)
            assertTrue(result is ToolExecutionResult.ContextResult)
            val contextResult = result as ToolExecutionResult.ContextResult
            assertFalse("Expired observation_id must fail", contextResult.success)
            assertTrue(contextResult.summaryText.contains("action_observation_expired"))

            // Must NOT call act
            Mockito.verify(mockEnvironment, Mockito.never()).act(
                action = anyAction(),
                awaitStabilization = Mockito.anyBoolean(),
                displayId = Mockito.anyInt(),
                leaseOwnerId = anyNullable(),
                expectedState = anyNullable(),
                expectedObservationId = anyNullable(),
            )
        }
    }

    @Test
    fun `click with cross-display observation_id rejects and does not call environment act`() {
        runBlocking {
            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            // 1. 在当前活跃虚拟屏 2 上进行观测，记录 observation_id 为 obs_display_2_abc
            val state2 = createTestState(displayId = 2, stateId = "state_d2")
            val obsId2 = "obs_display_2_abc"
            val observation2 = AndroidGuiObservation(
                state = state2,
                stabilization = emptyMap(),
                extra = mapOf("observation_id" to obsId2, "display_id" to 2)
            )
            Mockito.`when`(
                mockEnvironment.observeWithDiagnostics(
                    captureScreenshot = Mockito.eq(false),
                    waitToStabilize = Mockito.eq(false),
                    displayId = Mockito.eq(2),
                    leaseOwnerId = Mockito.anyString(),
                    ocr = Mockito.eq(false),
                    region = Mockito.isNull(),
                )
            ).thenReturn(observation2)

            val getStateArgs = buildJsonObject {
                put("action", "get_state")
                put("display", "virtual")
            }
            handler.execute(mockToolCall("call-get-2"), getStateArgs, mockDescriptor(), env, callback, handle)

            // 2. 模拟系统将活跃虚拟屏切换到 3，并为当前会话登记
            val vfField = DeviceDisplayManager::class.java
                .getDeclaredField("activeVirtualDisplayId").apply { isAccessible = true }
            vfField.setInt(DeviceDisplayManager, 3)
            DisplayInputCoordinator.registerVirtualDisplayOwnership(3, "session-p-test")

            try {
                // 尝试在显示屏 3 上使用来自显示屏 2 的旧 observation_id
                val clickArgs = buildJsonObject {
                    put("action", "click")
                    put("display", "3")
                    put("x", 100.0)
                    put("y", 100.0)
                    put("observation_id", obsId2) // 来自显示屏 2!
                }

                val result = handler.execute(mockToolCall("call-click-mismatch"), clickArgs, mockDescriptor(), env, callback, handle)
                assertTrue(result is ToolExecutionResult.ContextResult)
                val contextResult = result as ToolExecutionResult.ContextResult
                assertFalse("跨屏幕 observation_id 必须被拒绝", contextResult.success)
                assertTrue("错误应包含 action_display_mismatch", contextResult.summaryText.contains("action_display_mismatch"))

                // 严禁向显示屏 3 下发 act
                Mockito.verify(mockEnvironment, Mockito.never()).act(
                    action = anyAction(),
                    awaitStabilization = Mockito.anyBoolean(),
                    displayId = Mockito.eq(3),
                    leaseOwnerId = anyNullable(),
                    expectedState = anyNullable(),
                    expectedObservationId = anyNullable(),
                )
            } finally {
                vfField.setInt(DeviceDisplayManager, 2)
            }
        }
    }

    @Test
    fun `click with display main is rejected with background_display_required and does not observe`() {
        runBlocking {
            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            val clickArgs = buildJsonObject {
                put("action", "click")
                put("display", "main")
                put("x", 200.0)
                put("y", 200.0)
            }

            val result = handler.execute(mockToolCall(), clickArgs, mockDescriptor(), env, callback, handle)
            assertTrue(result is ToolExecutionResult.Error)
            val err = result as ToolExecutionResult.Error
            assertTrue(err.message.contains("background_display_required"))

            Mockito.verify(mockEnvironment, Mockito.never()).act(
                action = anyAction(),
                awaitStabilization = Mockito.anyBoolean(),
                displayId = Mockito.anyInt(),
                leaseOwnerId = anyNullable(),
                expectedState = anyNullable(),
                expectedObservationId = anyNullable(),
            )
        }
    }

    @Test
    fun `click without observation_id when unobserved rejects with action_observation_required`() {
        runBlocking {
            val env = mockEnv()
            val callback = Mockito.mock(AgentCallback::class.java)
            val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

            val clickArgs = buildJsonObject {
                put("action", "click")
                put("display", "virtual")
                put("x", 200.0)
                put("y", 200.0)
            }

            val result = handler.execute(mockToolCall(), clickArgs, mockDescriptor(), env, callback, handle)
            assertTrue(result is ToolExecutionResult.ContextResult)
            val contextResult = result as ToolExecutionResult.ContextResult
            assertFalse("未看屏点击必须失败", contextResult.success)
            assertTrue(contextResult.summaryText.contains("action_observation_required"))

            Mockito.verify(mockEnvironment, Mockito.never()).act(
                action = anyAction(),
                awaitStabilization = Mockito.anyBoolean(),
                displayId = Mockito.anyInt(),
                leaseOwnerId = anyNullable(),
                expectedState = anyNullable(),
                expectedObservationId = anyNullable(),
            )
        }
    }

    @Test
    fun `screenshot observe failure returns error and does not report success`() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        Mockito.`when`(
            mockEnvironment.observeWithDiagnostics(
                captureScreenshot = Mockito.eq(true),
                waitToStabilize = Mockito.eq(false),
                displayId = Mockito.eq(2),
                leaseOwnerId = Mockito.anyString(),
                ocr = Mockito.eq(false),
                region = Mockito.isNull(),
            )
        ).thenThrow(RuntimeException("observe_failed"))

        val args = buildJsonObject {
            put("action", "screenshot")
            put("display", "virtual")
        }

        val result = handler.execute(mockToolCall("call-ss-fail"), args, mockDescriptor(), env, callback, handle)
        assertTrue(result is ToolExecutionResult.ContextResult)
        val contextResult = result as ToolExecutionResult.ContextResult
        assertFalse("截图观测失败不得报成功", contextResult.success)
        assertTrue(contextResult.summaryText.contains("截取显示屏 2 画面失败"))
    }

    @Test
    fun `non-coordinate action open_app is not blocked by previous baseline state`() = runBlocking {
        val env = mockEnv()
        val callback = Mockito.mock(AgentCallback::class.java)
        val handle = Mockito.mock(AgentToolExecutionHandle::class.java)

        // First register state A
        val stateA = createTestState(displayId = 2, stateId = "state_pkg_A", packageName = "com.pkg.a")
        val obsA = AndroidGuiObservation(state = stateA, stabilization = emptyMap(), extra = mapOf("observation_id" to "obs_a"))
        Mockito.`when`(
            mockEnvironment.observeWithDiagnostics(
                captureScreenshot = Mockito.anyBoolean(),
                waitToStabilize = Mockito.anyBoolean(),
                displayId = Mockito.eq(2),
                leaseOwnerId = Mockito.anyString(),
                ocr = Mockito.anyBoolean(),
                region = anyNullable(),
            )
        ).thenReturn(obsA)

        val getStateArgs = buildJsonObject {
            put("action", "get_state")
            put("display", "virtual")
        }
        handler.execute(mockToolCall("call-get-a"), getStateArgs, mockDescriptor(), env, callback, handle)

        // Now execute open_app without observation_id targeting pkg B: must NOT bind state A and must not fail on A's package
        Mockito.`when`(
            mockEnvironment.act(
                action = anyAction(),
                awaitStabilization = Mockito.anyBoolean(),
                displayId = Mockito.eq(2),
                leaseOwnerId = anyNullable(),
                expectedState = Mockito.isNull(),
                expectedObservationId = Mockito.isNull(),
            )
        ).thenReturn(cn.com.omnimind.androidgui.AndroidGuiActionResult(success = true, message = "ok"))

        val openArgs = buildJsonObject {
            put("action", "open_app")
            put("package_name", "com.pkg.b")
            put("display", "virtual")
        }

        val result = handler.execute(mockToolCall("call-open-b"), openArgs, mockDescriptor(), env, callback, handle)
        assertTrue("open_app 不得被旧观测状态阻断: $result", result is ToolExecutionResult.ContextResult)
        assertTrue((result as ToolExecutionResult.ContextResult).success)
    }

    private class FakePerceptionBridge(val displayId: Int = 2) : PrivilegedDisplayBridge {
        override suspend fun createVirtualDisplay(width: Int, height: Int, dpi: Int, surface: android.view.Surface, flags: Int): Result<Int> = Result.success(displayId)
        override suspend fun releaseVirtualDisplay(displayId: Int) {}
        override suspend fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean = true
        override suspend fun injectTap(displayId: Int, x: Int, y: Int): Boolean = true
        override suspend fun injectSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = true
        override suspend fun launchActivity(displayId: Int, packageName: String): Boolean = true
        override suspend fun getRootTasks(displayId: Int): String = "[]"
        override suspend fun moveRootTask(taskId: Int, sourceDisplayId: Int, targetDisplayId: Int): String = "{}"
    }
}
