package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.assists.HumanTrajectoryLearningResult
import cn.com.omnimind.assists.HumanTrajectoryLearningSession
import cn.com.omnimind.baselib.runlog.InternalRunLogStore
import cn.com.omnimind.baselib.util.OmniLog

import cn.com.omnimind.bot.manager.buildManualRecordingFinalizedPayload
import cn.com.omnimind.uikit.loader.ManualRecordingControlOverlay
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class OmniFlowToolChannel(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun handle(call: MethodCall, result: MethodChannel.Result): Boolean {
        if (call.method == METHOD_START_HUMAN_TRAJECTORY_LEARNING) {
            startHumanTrajectoryLearning(call, result)
            return true
        }
        if (call.method != METHOD_CALL_TOOL) return false
        val payload = call.arguments as? Map<*, *>
        val name = payload?.get("name")?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            result.error("OMNIFLOW_TOOL_CALL_INVALID", "name is required", null)
            return true
        }
        if (name == "learn_app_experiences") {
            result.error(
                "OMNIFLOW_TOOL_CALL_REJECTED",
                "learn_app_experiences is an internal host tool and cannot be called directly",
                null,
            )
            return true
        }
        if (name == "list_app_experiences" || name == "set_app_experience_enabled") {
            if (OmniFlow.isBusy()) {
                result.success(
                    mapOf(
                        "success" to false,
                        "error_code" to "app_experience_busy",
                        "message" to "任务执行中，任务结束后更新",
                    )
                )
                return true
            }
        }
        val arguments = (payload?.get("arguments") as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()
        val goal = payload?.get("goal")?.toString()?.trim().orEmpty()
        scope.launch {
            try {
                val needsModel = (name == "run_gui" || name == "run_function") ||
                    (name == TOOL_SAVE_FUNCTION && arguments["enhance"] == true)
                val modelClient = if (OmniFlowPluginRuntime.isEnabled() && needsModel) {
                    createOmniFlowModelClient(
                        scope = CoroutineScope(currentCoroutineContext()),
                        environment = null,
                    )
                } else {
                    null
                }
                val rawResponse = if (
                    name == TOOL_SAVE_FUNCTION &&
                    arguments["function"] == null &&
                    arguments["functions"] == null &&
                    arguments["run_log"] == null &&
                    arguments["enhance"] != true
                ) {
                    OmniFlowFunctionRegistration.saveRunLog(
                        context = appContext,
                        runId = arguments["run_id"]?.toString().orEmpty(),
                        agentVisible = arguments["agent_visible"] != false,
                        modelClient = modelClient,
                    )
                } else {
                    OmniFlow.callTool(
                        context = appContext,
                        toolCall = OmniFlow.ToolCall(name, arguments),
                        goal = goal.ifBlank { name },
                        modelClient = modelClient,
                    ).payload
                }
                val response = if (name == "list_app_experiences" && rawResponse["success"] == true) {
                    enrichExperienceItems(appContext, rawResponse)
                } else {
                    rawResponse
                }
                withContext(Dispatchers.Main.immediate) { result.success(response) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                withContext(Dispatchers.Main.immediate) {
                    result.error(
                        "OMNIFLOW_TOOL_CALL_FAILED",
                        error.message ?: error.javaClass.simpleName,
                        null,
                    )
                }
            }
        }
        return true
    }

    private fun startHumanTrajectoryLearning(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        val arguments = call.arguments.asStringMap()
        val name = arguments.text("name").ifBlank { "手动录制" }
        val description = arguments.text("description").ifBlank { name }
        val enableDebugScreenshots = arguments.bool("enable_debug_screenshots")

        scope.launch {
            val payload = runCatching {
                val environment = AndroidGuiEnvironment(appContext)
                if (!environment.awaitReady()) {
                    return@runCatching mapOf(
                        "success" to false,
                        "phase" to "failed",
                        "error_code" to "OOB_ACCESSIBILITY_REQUIRED",
                        "error_message" to "无障碍服务未就绪，无法开始手动录制",
                    )
                }
                val learningResult = HumanTrajectoryLearningSession.start(
                    context = appContext,
                    name = name,
                    description = description,
                    enableRawTouch = false,
                    enableDebugScreenshots = enableDebugScreenshots,
                )
                val runId = HumanTrajectoryLearningSession.activeRunId()
                if (!HumanTrajectoryLearningSession.isActive() || runId == null) {
                    return@runCatching finalizedPayload(learningResult.await(), "failed")
                }
                if (!HumanTrajectoryLearningSession.pauseActive()) {
                    HumanTrajectoryLearningSession.cancelActive(
                        expectedRunId = runId,
                        message = "无法进入手动录制待机状态",
                    )
                    return@runCatching finalizedPayload(learningResult.await(), "failed")
                }

                val overlayShown = withContext(Dispatchers.Main.immediate) {
                    ManualRecordingControlOverlay.show(
                        context = appContext,
                        runId = runId,
                        state = ManualRecordingControlOverlay.State.READY,
                        onCaptureState = { humanTrajectoryStatusPayload() },
                    )
                }
                if (!overlayShown) {
                    HumanTrajectoryLearningSession.cancelActive(
                        expectedRunId = runId,
                        message = "悬浮窗无法显示，手动录制已取消",
                    )
                }
                finalizedPayload(
                    result = learningResult.await(),
                    phase = if (overlayShown) "finished" else "cancelled",
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "startHumanTrajectoryLearning failed: ${error.message}", error)
                mapOf(
                    "success" to false,
                    "phase" to "failed",
                    "error_code" to "HUMAN_TRAJECTORY_LEARNING_FAILED",
                    "error_message" to (error.message ?: error.javaClass.simpleName),
                )
            }
            withContext(Dispatchers.Main.immediate) { result.success(payload) }
        }
    }

    private fun humanTrajectoryStatusPayload(): Map<String, Any?> {
        val status = HumanTrajectoryLearningSession.status().asMap()
        return linkedMapOf<String, Any?>(
            "success" to true,
            "phase" to "status",
            "recording_active" to status["recording_active"],
            "recording_paused" to status["recording_paused"],
            "run_id" to status["run_id"],
            "name" to status["name"],
            "description" to status["description"],
            "started_at_ms" to status["started_at_ms"],
            "action_count" to status["action_count"],
            "latest_action_summary" to status["latest_action_summary"],
            "pending_action_summary" to status["pending_action_summary"],
            "recording_backend" to status["recording_backend"],
            "debug_screenshots_enabled" to status["debug_screenshots_enabled"],
            "status" to status,
            "source" to "oob_manual_recording",
        ).filterValues { it != null }
    }

    private suspend fun finalizedPayload(
        result: HumanTrajectoryLearningResult,
        phase: String,
    ): Map<String, Any?> {
        val runLog = InternalRunLogStore.timelinePayload(appContext, result.runId)
        val conversion = if (result.success && result.actionCount > 0) {
            runCatching {
                OmniFlowFunctionRegistration.saveRunLog(
                    context = appContext,
                    runId = result.runId,
                    agentVisible = true,
                    modelClient = if (OmniFlowPluginRuntime.isEnabled()) {
                        createOmniFlowModelClient(
                            scope = CoroutineScope(currentCoroutineContext()),
                            environment = null,
                        )
                    } else {
                        null
                    },
                )
            }.getOrElse { error ->
                OmniLog.e(TAG, "manual recording conversion failed: ${error.message}", error)
                mapOf(
                    "success" to false,
                    "error_code" to "HUMAN_TRAJECTORY_CONVERT_FAILED",
                    "error_message" to (error.message ?: error.javaClass.simpleName),
                )
            }
        } else {
            null
        }
        return buildManualRecordingFinalizedPayload(
            recordingSuccess = result.success,
            phase = phase,
            diagnostics = result.diagnostics,
            recordingErrorMessage = result.errorMessage,
            runLog = runLog,
            conversion = conversion,
        )
    }

    internal fun enrichExperienceItems(context: Context, payload: Map<String, Any?>): Map<String, Any?> {
        val items = payload["items"] as? List<*> ?: return payload
        val pm = context.packageManager
        val enrichedItems = items.map { item ->
            if (item !is Map<*, *>) return@map item
            val itemMap = item.entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
            val packageName = itemMap["package_name"]?.toString()?.trim().orEmpty()
            val label = if (packageName.isNotEmpty()) {
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty().ifEmpty { "未知应用" }
                } catch (_: Exception) {
                    "未知应用"
                }
            } else {
                "未知应用"
            }
            itemMap["application_label"] = label
            itemMap
        }
        return payload + mapOf("items" to enrichedItems)
    }

    fun clear() {
        scope.cancel()
    }

    private fun Any?.asStringMap(): Map<String, Any?> =
        (this as? Map<*, *>)
            ?.entries
            ?.associate { (key, value) -> key.toString() to value }
            .orEmpty()

    private fun Map<String, Any?>.text(key: String): String =
        get(key)?.toString()?.trim().orEmpty()

    private fun Map<String, Any?>.bool(key: String): Boolean = when (val value = get(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase() in setOf("true", "1", "yes", "y")
        else -> false
    }

    private companion object {
        const val TAG = "OmniFlowToolChannel"
        const val METHOD_CALL_TOOL = "tools/call"
        const val METHOD_START_HUMAN_TRAJECTORY_LEARNING = "startHumanTrajectoryLearning"
        const val TOOL_SAVE_FUNCTION = "save_function"
    }
}
