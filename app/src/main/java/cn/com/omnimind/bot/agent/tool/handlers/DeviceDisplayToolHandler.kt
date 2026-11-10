package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.androidgui.AndroidGuiObservationRegion
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.KeyguardStateMonitor
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.baselib.runlog.State
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.AgentCapabilityToolDefinition
import cn.com.omnimind.bot.task.runtime.TaskAuthWaitCoordinator
import cn.com.omnimind.bot.task.runtime.TaskRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

class DeviceDisplayToolHandler(
    val context: Context,
    val environment: AndroidGuiEnvironment = AndroidGuiEnvironment(context),
) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext ?: context,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    private data class DisplayObservation(
        val state: State,
        val observationId: String,
    )

    private val latestObservations = java.util.concurrent.ConcurrentHashMap<Int, DisplayObservation>()

    override suspend fun dispose() {
        // 跨轮必须保留会话目标与虚拟屏状态，仅在会话 close/delete 时清理
        latestObservations.clear()
        runCatching { environment.close() }
    }

    override val toolNames: Set<String> = setOf(TOOL_NAME)
    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        helper.ensureRunActive()
        toolHandle.throwIfStopRequested()

        val actionName = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        if (actionName.isEmpty()) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required argument 'action'.")
        }

        val sessionKey = env.acpSessionId ?: env.agentRunId
        val displayArg = args["display"]?.jsonPrimitive?.contentOrNull?.trim()
        if (actionName == "present_to_user") {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "background_display_required: present_to_user 为用户原生UI移交保留，Agent工具无法调用。",
            )
        }
        if (actionName == "list_displays") {
            return handleListDisplays()
        }
        if (actionName == "create_virtual_display") {
            return handleCreateVirtualDisplay(args, sessionKey, env.acpSessionId, env.acpTurnId)
        }
        if (actionName == "switch_display") {
            val targetVdId = try {
                DeviceDisplayManager.resolveOrPrepareDisplayId(
                    context = context,
                    target = displayArg,
                    sessionKey = sessionKey,
                    acpSessionId = env.acpSessionId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return ToolExecutionResult.Error(TOOL_NAME, e.message ?: "Invalid display target: $displayArg")
            }
            return handleSwitchDisplay(targetVdId, sessionKey)
        }
        if (actionName == "destroy_virtual_display") {
            if (displayArg?.lowercase() in setOf("main", "0") || displayArg?.toIntOrNull() == 0) {
                return ToolExecutionResult.Error(TOOL_NAME, "background_display_required: 禁止销毁主屏 (0)。")
            }
            val currentActiveId = DeviceDisplayManager.activeVirtualDisplayId()
            val parsed = displayArg?.toIntOrNull()
            val targetVdId = when {
                displayArg.isNullOrEmpty() || displayArg.lowercase() == DeviceDisplayManager.DISPLAY_VIRTUAL -> {
                    if (currentActiveId == Display.DEFAULT_DISPLAY) {
                        return ToolExecutionResult.Error(TOOL_NAME, "No active virtual display to destroy.")
                    }
                    currentActiveId
                }
                parsed != null && parsed == currentActiveId -> {
                    if (currentActiveId == Display.DEFAULT_DISPLAY) {
                        return ToolExecutionResult.Error(TOOL_NAME, "background_display_required: 禁止销毁主屏 (0)。")
                    }
                    currentActiveId
                }
                else -> try {
                    val res = DeviceDisplayManager.resolveDisplayId(context, displayArg)
                    if (res == Display.DEFAULT_DISPLAY) {
                        return ToolExecutionResult.Error(TOOL_NAME, "background_display_required: 禁止销毁主屏 (0)。")
                    }
                    res
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return ToolExecutionResult.Error(TOOL_NAME, e.message ?: "无效的显示屏目标：$displayArg")
                }
            }
            val validationError = validateNumericVirtualDisplay(targetVdId, sessionKey)
            if (validationError != null) {
                return ToolExecutionResult.Error(TOOL_NAME, validationError)
            }
            return handleDestroyVirtualDisplay(targetVdId, sessionKey)
        }
        val targetDisplayId = try {
            DeviceDisplayManager.resolveOrPrepareDisplayId(
                context = context,
                target = displayArg,
                sessionKey = sessionKey,
                acpSessionId = env.acpSessionId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolExecutionResult.Error(TOOL_NAME, e.message ?: "Invalid display target: $displayArg")
        }

        // Fine-grained lease owner ID: session + turnId/agentRunId + toolCallId
        val leaseOwnerId = "${env.acpSessionId ?: "no_session"}:${env.acpTurnId ?: env.agentRunId}:${toolCall.id}"
        // Bind stop action
        toolHandle.bindStopAction {
            DisplayInputCoordinator.cancelActiveOperation(targetDisplayId, leaseOwnerId)
        }

        val displayName = "虚拟屏($targetDisplayId)"
        helper.reportToolProgress(
            callback = callback,
            toolName = TOOL_NAME,
            progress = "正在执行屏幕操作: $actionName [$displayName]",
            extras = mapOf(
                "action" to actionName,
                "display_id" to targetDisplayId,
                "display_name" to displayName,
                "lease_owner_id" to leaseOwnerId,
            ),
            toolHandle = toolHandle,
        )

        // Lockscreen check for all displays
        if (KeyguardStateMonitor.isKeyguardLocked(context)) {
            val convId = env.conversationId
            if (convId != null) {
                helper.reportToolProgress(
                    callback = callback,
                    toolName = TOOL_NAME,
                    progress = "设备处于安全锁屏，等待解锁",
                    extras = mapOf(
                        "stage" to "await_unlock",
                        "display_id" to targetDisplayId,
                        "display_name" to displayName,
                    ),
                    toolHandle = toolHandle,
                )
                val unlocked = TaskAuthWaitCoordinator.awaitDeviceUnlock(
                    context = context,
                    conversationId = convId,
                    sessionId = env.acpSessionId,
                    turnId = env.acpTurnId,
                )
                if (!unlocked || KeyguardStateMonitor.isKeyguardLocked(context)) {
                    return ToolExecutionResult.Error(
                        toolName = TOOL_NAME,
                        message = "设备处于安全锁屏状态，等待用户解锁超时或已被取消。",
                    )
                }
            } else {
                return ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "设备处于安全锁屏状态，无会话绑定无法等待解锁，请先解锁手机后重试。",
                )
            }
        }

        return DisplayInputCoordinator.withDisplayLease(targetDisplayId, leaseOwnerId) {
            val convId = env.conversationId
            if (convId != null) {
                DisplayInputCoordinator.registerLeaseUnlockAwaiter(targetDisplayId, leaseOwnerId) { ctx, _ ->
                    TaskAuthWaitCoordinator.awaitDeviceUnlock(
                        context = ctx,
                        conversationId = convId,
                        sessionId = env.acpSessionId,
                        turnId = env.acpTurnId,
                    )
                }
            }

            val privilegedManager = cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager.get(context)
            val initialAccess = privilegedManager.reserve(ownerId = leaseOwnerId, acpSessionId = env.acpSessionId)
            if (initialAccess.isValid()) {
                DisplayInputCoordinator.registerLeaseExecutor(targetDisplayId, leaseOwnerId) { cmdList ->
                    kotlinx.coroutines.runBlocking {
                        val liveAccess = privilegedManager.reserve(ownerId = leaseOwnerId, acpSessionId = env.acpSessionId)
                        if (!liveAccess.isValid()) return@runBlocking false
                        val cmdStr = cmdList.joinToString(" ")
                        val res = liveAccess.executeRawShell(command = cmdStr, timeoutSeconds = 5, confirmed = true)
                        res.success
                    }
                }
            }
            DeviceDisplayManager.connectShizukuUserService(context)

            try {
                if (actionName in setOf("click", "long_press", "swipe", "input_text", "press_key", "open_app", "wait", "screenshot", "get_state")) {
                    val acpSession = env.acpSessionId ?: sessionKey
                    TaskRuntime.markScreenTarget(
                        context = context,
                        sessionId = acpSession,
                        turnId = env.acpTurnId,
                        displayId = targetDisplayId,
                    )
                }
                when (actionName) {
                    "get_state" -> handleGetState(args, targetDisplayId, leaseOwnerId)
                    "screenshot" -> handleScreenshot(targetDisplayId, leaseOwnerId)
                    "click" -> handleClick(args, targetDisplayId, leaseOwnerId)
                    "long_press" -> handleLongPress(args, targetDisplayId, leaseOwnerId)
                    "swipe" -> handleSwipe(args, targetDisplayId, leaseOwnerId)
                    "input_text" -> handleInputText(args, targetDisplayId, leaseOwnerId)
                    "press_key" -> handlePressKey(args, targetDisplayId, leaseOwnerId)
                    "open_app" -> handleOpenApp(args, targetDisplayId, leaseOwnerId)
                    "wait" -> handleWait(args, targetDisplayId, leaseOwnerId)
                    else -> ToolExecutionResult.Error(TOOL_NAME, "Unsupported action: $actionName")
                }
            } catch (e: CancellationException) {
                ToolExecutionResult.Interrupted(
                    toolName = TOOL_NAME,
                    summaryText = "手机操作已被用户手动停止并接管",
                    interruptedBy = "user",
                    interruptionReason = "manual_stop",
                )
            } catch (e: Exception) {
                ToolExecutionResult.Error(TOOL_NAME, e.message ?: "Action execution failed")
            } finally {
                DisplayInputCoordinator.clearLeaseUnlockAwaiter(targetDisplayId, leaseOwnerId)
                DisplayInputCoordinator.clearLeaseExecutor(targetDisplayId, leaseOwnerId)
            }
        }
    }


    private fun handleListDisplays(): ToolExecutionResult {
        val displays = DeviceDisplayManager.listDisplays(context)
        val displayData = displays.map { d ->
            mapOf(
                "display_id" to d.displayId,
                "name" to d.name,
                "width" to d.width,
                "height" to d.height,
                "dpi" to d.dpi,
                "is_virtual" to d.isVirtual,
                "is_default" to d.isDefault,
            )
        }
        return successResult(
            summary = "当前连接显示屏列表 (${displays.size}个)",
            data = mapOf("displays" to displayData),
        )
    }

    private suspend fun handleCreateVirtualDisplay(
        args: JsonObject,
        sessionKey: String,
        acpSessionId: String?,
        acpTurnId: String? = null,
    ): ToolExecutionResult {
        // 在虚拟屏创建前确保 PrivilegedService 已建立，受 ACP/Access 授权代次约束
        DeviceDisplayManager.ensurePrivilegedService(
            context = context,
            ownerId = sessionKey,
            acpSessionId = acpSessionId,
        )
        val width = args["width"]?.jsonPrimitive?.intOrNull ?: DeviceDisplayManager.DEFAULT_VIRTUAL_WIDTH
        val height = args["height"]?.jsonPrimitive?.intOrNull ?: DeviceDisplayManager.DEFAULT_VIRTUAL_HEIGHT
        val dpi = args["dpi"]?.jsonPrimitive?.intOrNull ?: DeviceDisplayManager.DEFAULT_VIRTUAL_DPI
        val result = DeviceDisplayManager.createVirtualDisplay(context, width, height, dpi, ownerId = sessionKey)
        return result.fold(
            onSuccess = { info ->
                DeviceDisplayManager.setSessionDisplayTarget(sessionKey, info.displayId)
                val acpSession = acpSessionId ?: sessionKey
                TaskRuntime.markScreenTarget(
                    context = context,
                    sessionId = acpSession,
                    turnId = acpTurnId,
                    displayId = info.displayId,
                )
                successResult(
                    summary = "后台虚拟显示屏创建成功 (id=${info.displayId}, ${info.width}x${info.height}@${info.dpi})",
                    data = mapOf(
                        "display_id" to info.displayId,
                        "width" to info.width,
                        "height" to info.height,
                        "dpi" to info.dpi,
                    ),
                )
            },
            onFailure = { error ->
                errorResult(error.message ?: "创建后台虚拟显示屏失败")
            },
        )
    }

    private fun handleDestroyVirtualDisplay(displayId: Int, sessionKey: String): ToolExecutionResult {
        DeviceDisplayManager.destroyVirtualDisplay(displayId, sessionKey)
        return successResult("后台虚拟显示屏已销毁", mapOf("success" to true))
    }


    private fun handleSwitchDisplay(targetDisplayId: Int, sessionKey: String): ToolExecutionResult {
        DeviceDisplayManager.setSessionDisplayTarget(sessionKey, targetDisplayId)
        val acpSession = sessionKey
        TaskRuntime.markScreenTarget(
            context = context,
            sessionId = acpSession,
            turnId = null,
            displayId = targetDisplayId,
        )
        return successResult("已将当前会话默认显示屏切换至: $targetDisplayId", mapOf("display_id" to targetDisplayId))
    }

    private fun parseObservationRegion(args: JsonObject): AndroidGuiObservationRegion? {
        val regionElement = args["region"] ?: return null
        if (regionElement is kotlinx.serialization.json.JsonNull) return null
        val regionObj = regionElement as? JsonObject
            ?: throw IllegalArgumentException("Argument 'region' must be a JSON object with coordinates {x1, y1, x2, y2}.")
        val x1 = regionObj["x1"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("region coordinate 'x1' must be a numeric value.")
        val y1 = regionObj["y1"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("region coordinate 'y1' must be a numeric value.")
        val x2 = regionObj["x2"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("region coordinate 'x2' must be a numeric value.")
        val y2 = regionObj["y2"]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("region coordinate 'y2' must be a numeric value.")
        return AndroidGuiObservationRegion(x1, y1, x2, y2)
    }

    private sealed class GuardCheckResult {
        data class Success(val expectedState: State?, val expectedObservationId: String?) : GuardCheckResult()
        data class Failure(val message: String, val diagnostics: Map<String, String>) : GuardCheckResult()
    }

    private fun validateNumericVirtualDisplay(displayId: Int, sessionKey: String): String? {
        if (displayId <= 0 || displayId == Display.DEFAULT_DISPLAY) {
            return "background_display_required: 主屏 (0) 不允许用于 Agent 操作，手机操作必须在后台虚拟屏执行。"
        }
        val activeVd = DeviceDisplayManager.activeVirtualDisplayId()
        if (!DeviceDisplayManager.isVirtualDisplayActive() || displayId != activeVd) {
            return "虚拟显示屏 $displayId 处于非活动状态或未就绪。"
        }
        val owner = DisplayInputCoordinator.getVirtualDisplayOwner(displayId)
            ?: DeviceDisplayManager.activeVirtualDisplayOwner()
        if (owner == null) {
            return "显示屏 $displayId 未登记为本会话虚拟屏，当前会话 ($sessionKey) 无权操作非托管显示屏。"
        }
        if (owner != sessionKey) {
            return "虚拟显示屏 $displayId 属于其他会话 ($owner)，当前会话 ($sessionKey) 无权操作。"
        }
        return null
    }

    private fun checkObservationGuard(
        args: JsonObject,
        targetDisplayId: Int,
        hasCoords: Boolean = false,
    ): GuardCheckResult {
        val observationId = args["observation_id"]
            ?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }

        val currentObservation = latestObservations[targetDisplayId]

        if (observationId != null) {
            // 1. 跨屏不符校验：该 observation_id 是否属于其他屏
            val otherDisplayEntry = latestObservations.entries.firstOrNull {
                it.key != targetDisplayId && it.value.observationId == observationId
            }
            if (otherDisplayEntry != null) {
                val otherDisplayId = otherDisplayEntry.key
                return GuardCheckResult.Failure(
                    message = "action_display_mismatch",
                    diagnostics = mapOf(
                        "expected_display" to otherDisplayId.toString(),
                        "current_display" to targetDisplayId.toString(),
                        "observation_id" to observationId,
                    ),
                )
            }

            // 2. 当前屏最新观测校验
            if (currentObservation == null || currentObservation.observationId != observationId) {
                return GuardCheckResult.Failure(
                    message = "action_observation_expired",
                    diagnostics = mapOf(
                        "expected_observation_id" to observationId,
                        "actual_observation_id" to (currentObservation?.observationId ?: "none"),
                        "display_id" to targetDisplayId.toString(),
                    ),
                )
            }

            return GuardCheckResult.Success(
                expectedState = currentObservation.state,
                expectedObservationId = observationId,
            )
        }

        // observationId 为空：坐标动作必须依赖观测，防止模型删字段盲打绕过基线安全核验
        if (hasCoords) {
            if (currentObservation == null) {
                return GuardCheckResult.Failure(
                    message = "action_observation_required",
                    diagnostics = mapOf(
                        "display_id" to targetDisplayId.toString(),
                        "reason" to "Screen has not been observed yet. Call get_state or screenshot before performing coordinate actions.",
                    ),
                )
            }
            return GuardCheckResult.Success(
                expectedState = currentObservation.state,
                expectedObservationId = currentObservation.observationId,
            )
        }

        return GuardCheckResult.Success(
            expectedState = null,
            expectedObservationId = null,
        )
    }

    private fun populateSemanticTargetArgs(args: JsonObject, actArgs: MutableMap<String, Any?>) {
        args["target_description"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }?.let {
            actArgs["target_description"] = it
        }
        args["node_resource_id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }?.let {
            actArgs["node_resource_id"] = it
        }
        args["node_id"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }?.let {
            actArgs["node_id"] = it
        }
    }

    private suspend fun handleGetState(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult = DisplayInputCoordinator.withDisplayLock(displayId, leaseOwnerId) {
        val region = try {
            parseObservationRegion(args)
        } catch (e: Exception) {
            return@withDisplayLock ToolExecutionResult.Error(TOOL_NAME, e.message ?: "Invalid region coordinates.")
        }
        val ocrElement = args["ocr"]
        val ocrRequested = if (ocrElement != null && ocrElement !is kotlinx.serialization.json.JsonNull) {
            (ocrElement as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: return@withDisplayLock ToolExecutionResult.Error(TOOL_NAME, "Argument 'ocr' must be a boolean.")
        } else {
            false
        }
        val captureScreenshot = ocrRequested || (region != null)

        val observation = environment.observeWithDiagnostics(
            captureScreenshot = captureScreenshot,
            waitToStabilize = false,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            ocr = ocrRequested,
            region = region,
        )
        val state = observation.state
        val obsId = observation.extra["observation_id"] as? String ?: state.stateId
        latestObservations[displayId] = DisplayObservation(state = state, observationId = obsId)

        val data = mutableMapOf<String, Any?>(
            "display_id" to displayId,
            "package_name" to state.packageName,
            "activity_name" to state.activityName,
            "width" to state.displayWidth,
            "height" to state.displayHeight,
            "xml" to state.xml,
            "observation_id" to obsId,
        )
        // 仅在显式请求 OCR 时返回 OCR 识别结果
        if (ocrRequested) {
            observation.extra["ocr"]?.let { data["ocr"] = it }
        }

        // 先从真实 observation.extra.visual_regions 提取局部图像作为 imageDataUrl
        val visualRegions = observation.extra["visual_regions"] as? List<*>
        val firstRegion = visualRegions?.firstOrNull() as? Map<*, *>
        val firstRegionBase64 = firstRegion?.get("image_base64") as? String
        val imageDataUrl = firstRegionBase64?.let { "data:image/jpeg;base64,$it" }

        // visual_regions 投影至 data 时保留 bounds/width/height 等元数据，但剔除 image_base64，避免在文本 JSON 中重复传输庞大 base64 载荷
        if (visualRegions != null) {
            val projectedRegions = visualRegions.mapNotNull { item ->
                (item as? Map<*, *>)?.filterKeys { it != "image_base64" }
            }
            if (projectedRegions.isNotEmpty()) {
                data["visual_regions"] = projectedRegions
            }
        }
        observation.extra["frame_sequence"]?.let { data["frame_sequence"] = it }
        val summary = buildString {
            append("获取显示屏 $displayId 界面状态成功: ${state.packageName}/${state.activityName} (${state.displayWidth}x${state.displayHeight}")
            if (ocrRequested) {
                val ocrMap = observation.extra["ocr"] as? Map<*, *>
                val items = (ocrMap?.get("items") as? List<*>)?.size ?: 0
                append("，OCR文字条目: $items")
            }
            if (region != null) {
                append("，ROI区域[${region.x1},${region.y1},${region.x2},${region.y2}]")
            }
            append("，坐标使用 0..1000 规范相对尺度: x/width*1000, y/height*1000)")
        }

        getStateSuccessResult(
            summary = summary,
            data = data,
            imageDataUrl = imageDataUrl,
        )
    }

    private suspend fun handleScreenshot(
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult = DisplayInputCoordinator.withDisplayLock(displayId, leaseOwnerId) {
        val observation = try {
            environment.observeWithDiagnostics(
                captureScreenshot = true,
                waitToStabilize = false,
                displayId = displayId,
                leaseOwnerId = leaseOwnerId,
                ocr = false,
                region = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withDisplayLock errorResult("截取显示屏 $displayId 画面失败：${e.message}")
        }
        val currentBaseline = environment.currentObservationBaseline()
        val imageBytes = currentBaseline?.takeIf { it.displayId == displayId && it.observationId == observation.extra["observation_id"] }?.screenshotJpeg
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val state = observation.state
            val obsId = observation.extra["observation_id"] as? String ?: state.stateId
            latestObservations[displayId] = DisplayObservation(state = state, observationId = obsId)
            val base64 = Base64.getEncoder().encodeToString(imageBytes)
            val dataUrl = "data:image/jpeg;base64,$base64"
            val dataMap = mutableMapOf<String, Any?>(
                "display_id" to displayId,
                "package_name" to state.packageName,
                "activity_name" to state.activityName,
                "width" to state.displayWidth,
                "height" to state.displayHeight,
                "observation_id" to obsId,
            )
            successResult(
                summary = "成功截取显示屏 $displayId 画面 (${state.displayWidth}x${state.displayHeight}，坐标使用 0..1000 规范相对尺度: x/width*1000, y/height*1000)",
                data = dataMap,
                imageDataUrl = dataUrl,
            )
        } else {
            errorResult("截取显示屏 $displayId 画面失败，未能获取图像数据。")
        }
    }

    private suspend fun handleClick(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val x = args["x"]?.jsonPrimitive?.doubleOrNull
        val y = args["y"]?.jsonPrimitive?.doubleOrNull
        if (x == null || y == null) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required arguments 'x' and 'y' for click.")
        }
        val guard = when (val res = checkObservationGuard(args, displayId, hasCoords = true)) {
            is GuardCheckResult.Success -> res
            is GuardCheckResult.Failure -> return errorResult(res.message, res.diagnostics)
        }
        val actArgs = mutableMapOf<String, Any?>(OobActionSchema.ARG_X to x, OobActionSchema.ARG_Y to y)
        populateSemanticTargetArgs(args, actArgs)
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_CLICK, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            expectedState = guard.expectedState,
            expectedObservationId = guard.expectedObservationId,
        )
        return if (result.success) {
            successResult("点击完成 ($x, $y) [显示屏 $displayId]", mapOf("x" to x, "y" to y, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
    }

    private suspend fun handleLongPress(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val x = args["x"]?.jsonPrimitive?.doubleOrNull
        val y = args["y"]?.jsonPrimitive?.doubleOrNull
        val duration = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 800L
        if (x == null || y == null) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required arguments 'x' and 'y' for long_press.")
        }
        val guard = when (val res = checkObservationGuard(args, displayId, hasCoords = true)) {
            is GuardCheckResult.Success -> res
            is GuardCheckResult.Failure -> return errorResult(res.message, res.diagnostics)
        }
        val actArgs = mutableMapOf<String, Any?>(
            OobActionSchema.ARG_X to x,
            OobActionSchema.ARG_Y to y,
            OobActionSchema.ARG_DURATION_MS to duration,
        )
        populateSemanticTargetArgs(args, actArgs)
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_LONG_PRESS, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            expectedState = guard.expectedState,
            expectedObservationId = guard.expectedObservationId,
        )
        return if (result.success) {
            successResult("长按完成 ($x, $y, ${duration}ms) [显示屏 $displayId]", mapOf("x" to x, "y" to y, "duration_ms" to duration, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
    }

    private suspend fun handleSwipe(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val x1 = args["x"]?.jsonPrimitive?.doubleOrNull
        val y1 = args["y"]?.jsonPrimitive?.doubleOrNull
        val x2 = args["x2"]?.jsonPrimitive?.doubleOrNull
        val y2 = args["y2"]?.jsonPrimitive?.doubleOrNull
        val duration = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 300L
        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required arguments 'x', 'y', 'x2', 'y2' for swipe.")
        }
        val guard = when (val res = checkObservationGuard(args, displayId, hasCoords = true)) {
            is GuardCheckResult.Success -> res
            is GuardCheckResult.Failure -> return errorResult(res.message, res.diagnostics)
        }
        val actArgs = mutableMapOf<String, Any?>(
            OobActionSchema.ARG_X1 to x1,
            OobActionSchema.ARG_Y1 to y1,
            OobActionSchema.ARG_X2 to x2,
            OobActionSchema.ARG_Y2 to y2,
            OobActionSchema.ARG_DURATION_MS to duration,
        )
        populateSemanticTargetArgs(args, actArgs)
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_SWIPE, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            expectedState = guard.expectedState,
            expectedObservationId = guard.expectedObservationId,
        )
        return if (result.success) {
            successResult("滑动完成 ($x1, $y1 -> $x2, $y2) [显示屏 $displayId]", mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
    }

    private suspend fun handleInputText(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull
        if (text == null) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required argument 'text' for input_text.")
        }
        val x = args["x"]?.jsonPrimitive?.doubleOrNull
        val y = args["y"]?.jsonPrimitive?.doubleOrNull
        val guard = when (val res = checkObservationGuard(args, displayId, hasCoords = (x != null && y != null))) {
            is GuardCheckResult.Success -> res
            is GuardCheckResult.Failure -> return errorResult(res.message, res.diagnostics)
        }
        val actArgs = mutableMapOf<String, Any?>(OobActionSchema.ARG_TEXT to text)
        if (x != null && y != null) {
            actArgs[OobActionSchema.ARG_X] = x
            actArgs[OobActionSchema.ARG_Y] = y
        }
        populateSemanticTargetArgs(args, actArgs)
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_INPUT_TEXT, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            expectedState = guard.expectedState,
            expectedObservationId = guard.expectedObservationId,
        )
        return if (result.success) {
            successResult("文本输入成功: '$text' [显示屏 $displayId]", mapOf("text" to text, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
    }

    private suspend fun handlePressKey(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required argument 'key' for press_key.")
        }
        val guard = when (val res = checkObservationGuard(args, displayId, hasCoords = false)) {
            is GuardCheckResult.Success -> res
            is GuardCheckResult.Failure -> return errorResult(res.message, res.diagnostics)
        }
        val actArgs = mutableMapOf<String, Any?>(OobActionSchema.ARG_KEY to key)
        populateSemanticTargetArgs(args, actArgs)
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_PRESS_KEY, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            expectedState = guard.expectedState,
            expectedObservationId = guard.expectedObservationId,
        )
        return if (result.success) {
            successResult("按键触发成功: $key [显示屏 $displayId]", mapOf("key" to key, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
    }

    private suspend fun handleOpenApp(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val packageName = args["package_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return ToolExecutionResult.Error(TOOL_NAME, "Missing required argument 'package_name' for open_app.")
        }
        val guard = when (val res = checkObservationGuard(args, displayId, hasCoords = false)) {
            is GuardCheckResult.Success -> res
            is GuardCheckResult.Failure -> return errorResult(res.message, res.diagnostics)
        }
        val actArgs = mutableMapOf<String, Any?>(OobActionSchema.ARG_PACKAGE_NAME to packageName)
        populateSemanticTargetArgs(args, actArgs)
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_OPEN_APP, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
            expectedState = guard.expectedState,
            expectedObservationId = guard.expectedObservationId,
        )
        return if (result.success) {
            successResult("已在显示屏 $displayId 启动应用: $packageName", mapOf("package_name" to packageName, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
    }

    private fun getStateSuccessResult(
        summary: String,
        data: Map<String, Any?>,
        imageDataUrl: String? = null,
    ): ToolExecutionResult {
        val jsonElement = helper.mapToJsonElement(data)
        val jsonStr = helper.json.encodeToString(jsonElement)
        return ToolExecutionResult.ContextResult(
            toolName = TOOL_NAME,
            summaryText = summary,
            previewJson = jsonStr,
            rawResultJson = jsonStr,
            success = true,
            imageDataUrl = imageDataUrl,
        )
    }

    private suspend fun handleWait(
        args: JsonObject,
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult {
        val duration = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 1000L
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_WAIT, mapOf(OobActionSchema.ARG_DURATION_MS to duration)),
            awaitStabilization = false,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
        )
        return successResult("等待完成 (${duration}ms) [显示屏 $displayId]", mapOf("duration_ms" to duration, "display_id" to displayId))
    }

    private fun successResult(
        summary: String,
        data: Map<String, Any?>,
        imageDataUrl: String? = null,
    ): ToolExecutionResult {
        val jsonStr = helper.json.encodeToString(data.mapValues { it.value?.toString().orEmpty() })
        return ToolExecutionResult.ContextResult(
            toolName = TOOL_NAME,
            summaryText = summary,
            previewJson = jsonStr,
            rawResultJson = jsonStr,
            success = true,
            imageDataUrl = imageDataUrl,
        )
    }

    private fun errorResult(
        message: String,
        diagnostics: Map<String, String> = emptyMap(),
    ): ToolExecutionResult {
        val payload = mapOf("success" to "false", "error" to message) + diagnostics
        val jsonStr = helper.json.encodeToString(payload)
        return ToolExecutionResult.ContextResult(
            toolName = TOOL_NAME,
            summaryText = message,
            previewJson = jsonStr,
            rawResultJson = jsonStr,
            success = false,
        )
    }

    companion object {
        fun clearSession(sessionKey: String) {
            DeviceDisplayManager.clearSessionDisplayTarget(sessionKey)
        }
        const val TOOL_NAME = "device_display_action"

        val definitions: List<AgentCapabilityToolDefinition> = listOf(
            AgentCapabilityToolDefinition(
                name = TOOL_NAME,
                displayName = "设备显示与屏幕操作",
                description = "直接对 Android 手机后台虚拟屏执行读屏、截图、点击、长按、滑动、输入文本、按键、启动应用和多屏管理。Agent 必须且仅在后台虚拟屏执行，不占用或干扰用户当前主屏；严禁接管主屏。同一屏幕输入自动排队。屏幕操作统一使用 0..1000 规范相对坐标（0为屏幕最左/顶，1000为最右/底；若由 XML bounds [left,top][right,bottom] 计算目标中心：x = (left+right)/2 / width * 1000，y = (top+bottom)/2 / height * 1000）。",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") {
                            put("type", "string")
                            put("description", "要执行的操作动作")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("screenshot"))
                                add(JsonPrimitive("click"))
                                add(JsonPrimitive("long_press"))
                                add(JsonPrimitive("swipe"))
                                add(JsonPrimitive("input_text"))
                                add(JsonPrimitive("press_key"))
                                add(JsonPrimitive("open_app"))
                                add(JsonPrimitive("get_state"))
                                add(JsonPrimitive("list_displays"))
                                add(JsonPrimitive("create_virtual_display"))
                                add(JsonPrimitive("destroy_virtual_display"))
                                add(JsonPrimitive("switch_display"))
                                add(JsonPrimitive("wait"))
                            })
                        }
                        putJsonObject("display") {
                            put("type", "string")
                            put("description", "目标后台屏幕：'virtual'（默认，后台虚拟屏），或本会话具体的虚拟屏数字 ID。省略时默认使用后台虚拟屏。禁止传入 'main' 或 '0'。")
                        }
                        putJsonObject("x") {
                            put("type", "number")
                            put("minimum", 0)
                            put("maximum", 1000)
                            put("description", "操作横坐标（规范相对坐标 0..1000，0为最左，1000为最右；滑动时作为起点横坐标；如基于 XML bounds 计算：(left+right)/2 / width * 1000）")
                        }
                        putJsonObject("y") {
                            put("type", "number")
                            put("minimum", 0)
                            put("maximum", 1000)
                            put("description", "操作纵坐标（规范相对坐标 0..1000，0为最顶，1000为最底；滑动时作为起点纵坐标；如基于 XML bounds 计算：(top+bottom)/2 / height * 1000）")
                        }
                        putJsonObject("x2") {
                            put("type", "number")
                            put("minimum", 0)
                            put("maximum", 1000)
                            put("description", "滑动终点横坐标（规范相对坐标 0..1000，0为最左，1000为最右，仅 action=swipe）")
                        }
                        putJsonObject("y2") {
                            put("type", "number")
                            put("minimum", 0)
                            put("maximum", 1000)
                            put("description", "滑动终点纵坐标（规范相对坐标 0..1000，0为最顶，1000为最底，仅 action=swipe）")
                        }
                        putJsonObject("duration_ms") {
                            put("type", "integer")
                            put("description", "手势持续时间（毫秒）")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "要输入的文本（仅 action=input_text）")
                        }
                        putJsonObject("key") {
                            put("type", "string")
                            put("description", "系统按键（仅 action=press_key）")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("back"))
                                add(JsonPrimitive("home"))
                                add(JsonPrimitive("enter"))
                                add(JsonPrimitive("select_all"))
                                add(JsonPrimitive("copy"))
                            })
                        }
                        putJsonObject("package_name") {
                            put("type", "string")
                            put("description", "应用包名（仅 action=open_app）")
                        }
                        putJsonObject("width") {
                            put("type", "integer")
                            put("description", "虚拟屏宽度像素（仅 action=create_virtual_display）")
                        }
                        putJsonObject("height") {
                            put("type", "integer")
                            put("description", "虚拟屏高度像素（仅 action=create_virtual_display）")
                        }
                        putJsonObject("dpi") {
                            put("type", "integer")
                            put("description", "虚拟屏像素密度（仅 action=create_virtual_display）")
                        }
                        putJsonObject("ocr") {
                            put("type", "boolean")
                            put("description", "是否在获取界面状态时执行端侧本地 OCR 识别文字（仅在缺少可点击无障碍文本或纯图形/自绘界面时开启）。开启时自动截取屏幕图像进行识别。")
                        }
                        putJsonObject("region") {
                            put("type", "object")
                            put("description", "可选的高清局部观测与识别区域（全屏归一化 0..1000 坐标：x1, y1 为左上角，x2, y2 为右下角）。提供时自动截取对应区域画面。")
                            putJsonObject("properties") {
                                putJsonObject("x1") {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 1000)
                                    put("description", "区域左边缘坐标 (0..1000)")
                                }
                                putJsonObject("y1") {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 1000)
                                    put("description", "区域顶边缘坐标 (0..1000)")
                                }
                                putJsonObject("x2") {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 1000)
                                    put("description", "区域右边缘坐标 (0..1000)")
                                }
                                putJsonObject("y2") {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 1000)
                                    put("description", "区域底边缘坐标 (0..1000)")
                                }
                            }
                            put("required", buildJsonArray {
                                add(JsonPrimitive("x1"))
                                add(JsonPrimitive("y1"))
                                add(JsonPrimitive("x2"))
                                add(JsonPrimitive("y2"))
                            })
                        }
                        putJsonObject("observation_id") {
                            put("type", "string")
                            put("description", "可选的前置观测身份标识（由 get_state 返回）。动作执行前将严格校验该观测身份是否匹配当前屏幕最新状态，防止界面变化后执行过期操作。")
                        }
                        putJsonObject("target_description") {
                            put("type", "string")
                            put("description", "可选的目标语义描述（例如按钮文字或无障碍描述），用于动作前语义锁定与状态一致性校验。")
                        }
                        putJsonObject("node_resource_id") {
                            put("type", "string")
                            put("description", "可选的目标控件资源 ID（如 com.example:id/btn_login），用于动作前语义锁定与状态一致性校验。")
                        }
                    }
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                },
                toolType = "builtin",
            )
        )
    }
}
