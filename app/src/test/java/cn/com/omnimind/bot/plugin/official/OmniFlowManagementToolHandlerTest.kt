package cn.com.omnimind.bot.plugin.official

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import cn.com.omnimind.baselib.runlog.CanonicalRunLogRecord
import cn.com.omnimind.bot.agent.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniFlowManagementToolHandlerTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `saving a runlog preserves the official arguments`() {
        val args = json.parseToJsonElement(
            """{"run_id":"gui-123"}""",
        ).jsonObject

        val normalized = normalizeOmniFlowManagementArguments(
            OmniFlowManagementTools.SAVE_FUNCTION,
            args,
        )

        assertEquals("gui-123", normalized["run_id"])
        assertFalse(normalized.containsKey("agent_visible"))
    }

    @Test
    fun `enhancement arguments are passed to the official save pipeline`() {
        val args = json.parseToJsonElement(
            """{"run_id":"gui-123","functions":[{"function_id":"fn-1"}],"enhance":true,"instruction":"稳定化参数"}""",
        ).jsonObject

        val normalized = normalizeOmniFlowManagementArguments(
            OmniFlowManagementTools.SAVE_FUNCTION,
            args,
        )

        assertEquals("gui-123", normalized["run_id"])
        assertEquals(true, normalized["enhance"])
        assertEquals("稳定化参数", normalized["instruction"])
        assertEquals(
            "fn-1",
            ((normalized["functions"] as List<*>).single() as Map<*, *>)["function_id"],
        )
    }

    @Test
    fun `failed runlog cannot be registered as a function`() {
        assertFalse(
            isRegisterableRunLog(
                CanonicalRunLogRecord(
                    runId = "failed",
                    status = "failed",
                    success = false,
                    diagnostics = mapOf("done_reason" to "error"),
                ),
            ),
        )
    }

    @Test
    fun `succeeded runlog can be registered`() {
        assertTrue(
            isRegisterableRunLog(
                CanonicalRunLogRecord(
                    runId = "succeeded",
                    status = "succeeded",
                    success = true,
                    diagnostics = mapOf("done_reason" to "function_completed"),
                ),
            ),
        )
    }

    @Test
    fun `developer override tools are exposed with recovery confirmation`() {
        val definitions = OmniFlowManagementTools.definitions().associateBy { it.name }

        assertTrue(definitions.containsKey(OmniFlowManagementTools.GET_PYTHON_OVERRIDE))
        assertTrue(definitions.containsKey(OmniFlowManagementTools.APPLY_PYTHON_OVERRIDE))
        assertTrue(definitions.containsKey(OmniFlowManagementTools.RELOAD_PYTHON_OVERRIDE))
        val clear = requireNotNull(definitions[OmniFlowManagementTools.CLEAR_PYTHON_OVERRIDE])
        assertEquals(
            "confirm",
            clear.parameters["required"]?.toString()?.trim('[', ']', '"'),
        )
    }

    @Test
    fun `save_function failure payload with nested RUN_LOG_COMPILE_FAILED maps to ToolExecutionResult Error preserving code and message`() {
        val payload = mapOf<String, Any?>(
            "success" to false,
            "function_id" to "",
            "function" to null,
            "error" to mapOf(
                "code" to "RUN_LOG_COMPILE_FAILED",
                "message" to "RunLog has no replayable steps",
            ),
        )

        val result = omniFlowManagementErrorResult(OmniFlowManagementTools.SAVE_FUNCTION, payload)

        assertEquals(OmniFlowManagementTools.SAVE_FUNCTION, result.toolName)
        assertEquals("RUN_LOG_COMPILE_FAILED: RunLog has no replayable steps", result.message)
    }

    @Test
    fun `save_function failure payload with custom compile message preserves real error detail`() {
        val payload = mapOf<String, Any?>(
            "success" to false,
            "function_id" to "",
            "function" to null,
            "error" to mapOf(
                "code" to "RUN_LOG_COMPILE_FAILED",
                "message" to "syntax error: step 2 action requires coordinate",
            ),
        )

        val result = omniFlowManagementErrorResult(OmniFlowManagementTools.SAVE_FUNCTION, payload)

        assertEquals(OmniFlowManagementTools.SAVE_FUNCTION, result.toolName)
        assertEquals("RUN_LOG_COMPILE_FAILED: syntax error: step 2 action requires coordinate", result.message)
    }

    @Test
    fun `top-level legacy error_message and error_code take priority over nested or string errors`() {
        val payload = mapOf<String, Any?>(
            "success" to false,
            "error_message" to "RunLog not found: gui-123",
            "error_code" to "RUN_LOG_NOT_FOUND",
            "error" to mapOf("code" to "IGNORED", "message" to "ignored"),
        )

        val result = omniFlowManagementErrorResult(OmniFlowManagementTools.SAVE_FUNCTION, payload)

        assertEquals(OmniFlowManagementTools.SAVE_FUNCTION, result.toolName)
        assertEquals("RunLog not found: gui-123", result.message)
    }

    @Test
    fun `top-level legacy error_code is used when error_message is missing`() {
        val payload = mapOf<String, Any?>(
            "success" to false,
            "error_code" to "app_experience_busy",
        )

        val result = omniFlowManagementErrorResult("list_app_experiences", payload)

        assertEquals("list_app_experiences", result.toolName)
        assertEquals("app_experience_busy", result.message)
    }

    @Test
    fun `string error payload falls back to string and empty payload falls back to default error`() {
        val strResult = omniFlowManagementErrorResult(
            OmniFlowManagementTools.SAVE_FUNCTION,
            mapOf("success" to false, "error" to "bridge disconnected"),
        )
        assertEquals("bridge disconnected", strResult.message)

        val emptyResult = omniFlowManagementErrorResult(
            OmniFlowManagementTools.SAVE_FUNCTION,
            mapOf("success" to false),
        )
        assertEquals("OmniFlow management tool failed", emptyResult.message)
    }
}
