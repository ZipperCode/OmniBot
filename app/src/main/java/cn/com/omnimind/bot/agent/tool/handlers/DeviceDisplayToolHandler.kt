package cn.com.omnimind.bot.agent.tool.handlers

import android.content.Context
import android.view.Display
import cn.com.omnimind.androidgui.AndroidGuiEnvironment
import cn.com.omnimind.androidgui.DeviceDisplayManager
import cn.com.omnimind.androidgui.DisplayInputCoordinator
import cn.com.omnimind.androidgui.KeyguardStateMonitor
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.runlog.Action
import cn.com.omnimind.baselib.runlog.OobActionSchema
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.AgentCapabilityToolDefinition
import cn.com.omnimind.bot.task.runtime.TaskAuthWaitCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val environment: AndroidGuiEnvironment = AndroidGuiEnvironment(context),
) : ToolHandler {
    private val helper = SharedHelper(
        context = context.applicationContext ?: context,
        json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    override suspend fun dispose() {
        sessionDisplayTargets.clear()
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
            ?: sessionDisplayTargets[sessionKey]?.toString()
        val targetDisplayId = when {
            actionName == "list_displays" -> Display.DEFAULT_DISPLAY
            actionName == "create_virtual_display" -> {
                // 创建虚拟屏时无需提前校验虚拟屏已存在，使用默认主屏申请操作租约
                Display.DEFAULT_DISPLAY
            }
            actionName == "present_to_user" -> {
                // 移交主屏目标为主屏，双屏租约在外部单独分流处理
                Display.DEFAULT_DISPLAY
            }
            actionName == "destroy_virtual_display" -> {
                // 已持有的虚拟屏即使对应用不可见，也必须能送达所属 Helper 清理。
                val currentActiveId = DeviceDisplayManager.activeVirtualDisplayId()
                val parsed = displayArg?.toIntOrNull()
                when {
                    displayArg.isNullOrEmpty() || displayArg.lowercase() == DeviceDisplayManager.DISPLAY_VIRTUAL -> {
                        currentActiveId
                    }
                    parsed != null && parsed == currentActiveId -> {
                        currentActiveId
                    }
                    else -> try {
                        DeviceDisplayManager.resolveDisplayId(context, displayArg)
                    } catch (e: Exception) {
                        return ToolExecutionResult.Error(TOOL_NAME, e.message ?: "无效的显示屏目标：$displayArg")
                    }
                }
            }
            else -> try {
                DeviceDisplayManager.resolveDisplayId(context, displayArg)
            } catch (e: Exception) {
                return ToolExecutionResult.Error(TOOL_NAME, e.message ?: "Invalid display target: $displayArg")
            }
        }

        // Fine-grained lease owner ID: session + turnId/agentRunId + toolCallId
        val leaseOwnerId = "${env.acpSessionId ?: "no_session"}:${env.acpTurnId ?: env.agentRunId}:${toolCall.id}"
        // Bind stop action
        toolHandle.bindStopAction {
            DisplayInputCoordinator.cancelActiveOperation(targetDisplayId, leaseOwnerId)
            if (actionName == "present_to_user") {
                val activeVd = DeviceDisplayManager.activeVirtualDisplayId()
                if (activeVd != Display.DEFAULT_DISPLAY) {
                    DisplayInputCoordinator.cancelActiveOperation(activeVd, leaseOwnerId)
                }
            }
        }

        val displayName = if (targetDisplayId == Display.DEFAULT_DISPLAY) "主屏(0)" else "虚拟屏($targetDisplayId)"
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
        // 移交主屏在单屏 withDisplayLease 外独立分流，避免先持虚拟屏锁再持主屏锁引发死锁
        if (actionName == "present_to_user") {
            val sessionId = env.acpSessionId?.trim()?.ifEmpty { null }
            val turnId = env.acpTurnId?.trim()?.ifEmpty { null }
            if (sessionId == null || turnId == null) {
                return ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "缺少有效 ACP 会话或 Turn 标识 (acpSessionId=$sessionId, acpTurnId=$turnId)，无法执行主屏移交。",
                )
            }
            val presentLeaseOwnerId = "$sessionId:$turnId:${toolCall.id}"
            return handlePresentToUser(args, sessionId, presentLeaseOwnerId, env, callback, toolHandle)
        }


        // 虚拟屏动作校验所有权，防止跨会话操作他人屏幕
        if (targetDisplayId != Display.DEFAULT_DISPLAY) {
            val vdOwner = DisplayInputCoordinator.getVirtualDisplayOwner(targetDisplayId)
            if (vdOwner != null && vdOwner != sessionKey) {
                return ToolExecutionResult.Error(
                    toolName = TOOL_NAME,
                    message = "虚拟显示屏 $targetDisplayId 属于其他会话 ($vdOwner)，当前会话 ($sessionKey) 无权操作。",
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
                when (actionName) {
                    "list_displays" -> handleListDisplays()
                    "create_virtual_display" -> handleCreateVirtualDisplay(args, sessionKey, env.acpSessionId)
                    "destroy_virtual_display" -> handleDestroyVirtualDisplay(targetDisplayId, sessionKey)
                    "get_state" -> handleGetState(targetDisplayId, leaseOwnerId)
                    "screenshot" -> handleScreenshot(targetDisplayId, leaseOwnerId)
                    "click" -> handleClick(args, targetDisplayId, leaseOwnerId)
                    "long_press" -> handleLongPress(args, targetDisplayId, leaseOwnerId)
                    "swipe" -> handleSwipe(args, targetDisplayId, leaseOwnerId)
                    "input_text" -> handleInputText(args, targetDisplayId, leaseOwnerId)
                    "press_key" -> handlePressKey(args, targetDisplayId, leaseOwnerId)
                    "open_app" -> handleOpenApp(args, targetDisplayId, leaseOwnerId)
                    "wait" -> handleWait(args, targetDisplayId, leaseOwnerId)
                    "switch_display" -> handleSwitchDisplay(targetDisplayId, sessionKey)
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
    private suspend fun handlePresentToUser(
        args: JsonObject,
        sessionId: String,
        leaseOwnerId: String,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        toolHandle.throwIfStopRequested()

        val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return ToolExecutionResult.Error(
                toolName = TOOL_NAME,
                message = "Missing required argument 'packageName' for present_to_user.",
            )
        }
        val taskId = args["taskId"]?.jsonPrimitive?.intOrNull

        DeviceDisplayManager.connectShizukuUserService(context)

        return try {
            val result = DeviceDisplayManager.presentToUser(
                context = context,
                packageName = packageName,
                taskId = taskId,
                sessionOwnerId = sessionId,
                leaseOwnerId = leaseOwnerId,
            )

            val resSuccess = result.success
            val resCode = result.diagnostics["code"] ?: if (resSuccess) "ok" else "error"
            val resTaskId = result.diagnostics["taskId"]?.toIntOrNull() ?: taskId ?: 0
            val resTaskKind = result.diagnostics["taskKind"] ?: "unknown"
            val resSourceDisplayId = result.diagnostics["sourceDisplayId"]?.toIntOrNull() ?: 0
            val resTargetDisplayId = result.diagnostics["targetDisplayId"]?.toIntOrNull() ?: Display.DEFAULT_DISPLAY
            val resVerified = result.diagnostics["verified"]?.toBooleanStrictOrNull() ?: resSuccess

            val payload = buildJsonObject {
                put("success", resSuccess)
                put("code", resCode)
                put("taskId", resTaskId)
                put("taskKind", resTaskKind)
                put("sourceDisplayId", resSourceDisplayId)
                put("targetDisplayId", resTargetDisplayId)
                put("verified", resVerified)
                put("packageName", packageName)
                if (!resSuccess) {
                    put("error", result.message)
                }
            }
            val jsonStr = payload.toString()

            if (resSuccess) {
                sessionDisplayTargets[sessionId] = Display.DEFAULT_DISPLAY
                ToolExecutionResult.ContextResult(
                    toolName = TOOL_NAME,
                    summaryText = "已将应用 $packageName (taskId=$resTaskId) 移交至主屏显示",
                    previewJson = jsonStr,
                    rawResultJson = jsonStr,
                    success = true,
                )
            } else {
                ToolExecutionResult.ContextResult(
                    toolName = TOOL_NAME,
                    summaryText = "移交主屏失败: ${result.message}",
                    previewJson = jsonStr,
                    rawResultJson = jsonStr,
                    success = false,
                )
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
        sessionDisplayTargets[sessionKey] = targetDisplayId
        return successResult("已将当前会话默认显示屏切换至: $targetDisplayId", mapOf("display_id" to targetDisplayId))
    }

    private suspend fun handleGetState(
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult = DisplayInputCoordinator.withDisplayLock(displayId, leaseOwnerId) {
        val state = environment.observe(captureScreenshot = false, waitToStabilize = false, displayId = displayId, leaseOwnerId = leaseOwnerId)
        successResult(
            summary = "获取显示屏 $displayId 界面状态成功: ${state.packageName}/${state.activityName} (${state.displayWidth}x${state.displayHeight}，坐标使用 0..1000 规范相对尺度: x/width*1000, y/height*1000)",
            data = mapOf(
                "display_id" to displayId,
                "package_name" to state.packageName,
                "activity_name" to state.activityName,
                "width" to state.displayWidth,
                "height" to state.displayHeight,
                "xml" to state.xml,
            ),
        )
    }

    private suspend fun handleScreenshot(
        displayId: Int,
        leaseOwnerId: String,
    ): ToolExecutionResult = DisplayInputCoordinator.withDisplayLock(displayId, leaseOwnerId) {
        val snapshot = environment.captureScreenSnapshot(displayId = displayId, leaseOwnerId = leaseOwnerId)
        val imageBytes = snapshot.screenshotJpeg
        if (imageBytes != null && imageBytes.isNotEmpty()) {
            val base64 = Base64.getEncoder().encodeToString(imageBytes)
            val dataUrl = "data:image/jpeg;base64,$base64"
            successResult(
                summary = "成功截取显示屏 $displayId 画面 (${snapshot.displayWidth}x${snapshot.displayHeight}，坐标使用 0..1000 规范相对尺度: x/width*1000, y/height*1000)",
                data = mapOf(
                    "display_id" to displayId,
                    "package_name" to snapshot.packageName,
                    "activity_name" to snapshot.activityName,
                    "width" to snapshot.displayWidth,
                    "height" to snapshot.displayHeight,
                ),
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
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_CLICK, mapOf(OobActionSchema.ARG_X to x, OobActionSchema.ARG_Y to y)),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
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
        val result = environment.act(
            action = Action(
                OobActionSchema.TOOL_LONG_PRESS,
                mapOf(OobActionSchema.ARG_X to x, OobActionSchema.ARG_Y to y, OobActionSchema.ARG_DURATION_MS to duration),
            ),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
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
        val result = environment.act(
            action = Action(
                OobActionSchema.TOOL_SWIPE,
                mapOf(
                    OobActionSchema.ARG_X1 to x1,
                    OobActionSchema.ARG_Y1 to y1,
                    OobActionSchema.ARG_X2 to x2,
                    OobActionSchema.ARG_Y2 to y2,
                    OobActionSchema.ARG_DURATION_MS to duration,
                ),
            ),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
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
        val actArgs = mutableMapOf<String, Any?>(OobActionSchema.ARG_TEXT to text)
        if (x != null && y != null) {
            actArgs[OobActionSchema.ARG_X] = x
            actArgs[OobActionSchema.ARG_Y] = y
        }
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_INPUT_TEXT, actArgs),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
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
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_PRESS_KEY, mapOf(OobActionSchema.ARG_KEY to key)),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
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
        val result = environment.act(
            action = Action(OobActionSchema.TOOL_OPEN_APP, mapOf(OobActionSchema.ARG_PACKAGE_NAME to packageName)),
            awaitStabilization = true,
            displayId = displayId,
            leaseOwnerId = leaseOwnerId,
        )
        return if (result.success) {
            successResult("已在显示屏 $displayId 启动应用: $packageName", mapOf("package_name" to packageName, "display_id" to displayId))
        } else {
            errorResult(result.message, result.diagnostics)
        }
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
        private val sessionDisplayTargets = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun clearSession(sessionKey: String) {
            sessionDisplayTargets.remove(sessionKey)
        }
        const val TOOL_NAME = "device_display_action"

        val definitions: List<AgentCapabilityToolDefinition> = listOf(
            AgentCapabilityToolDefinition(
                name = TOOL_NAME,
                displayName = "设备显示与屏幕操作",
                description = "直接对 Android 手机主屏或后台虚拟屏执行读屏、截图、点击、长按、滑动、输入文本、按键、启动应用和多屏管理。同一屏幕输入自动排队。当操作主屏遇到安全锁屏时会等待解锁。屏幕操作统一使用 0..1000 规范相对坐标（0为屏幕最左/顶，1000为最右/底；若由 XML bounds [left,top][right,bottom] 计算目标中心：x = (left+right)/2 / width * 1000，y = (top+bottom)/2 / height * 1000）。",
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
                                add(JsonPrimitive("present_to_user"))
                            })
                        }
                        putJsonObject("display") {
                            put("type", "string")
                            put("description", "目标屏幕：'main'（主屏，0）、'virtual'（后台虚拟屏），或具体屏幕数字 ID。省略时默认主屏。")
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
                        putJsonObject("packageName") {
                            put("type", "string")
                            put("description", "应用包名（仅 action=present_to_user 必填）")
                        }
                        putJsonObject("taskId") {
                            put("type", "integer")
                            put("description", "要移交的任务根 ID（仅 action=present_to_user 可选）")
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
                    }
                    put("required", buildJsonArray { add(JsonPrimitive("action")) })
                },
                toolType = "builtin",
            )
        )
    }
}
