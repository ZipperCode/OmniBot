package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 客户端连接桥：通过 ACTION_DEVICE_COMMAND 回调将请求分发给 app_process 辅助进程 (PrivilegedDeviceApiMain)。
 * 严禁使用通用反射或任意 shell 命令；严格校验 CLI 入参、stdout 标记、UID 与序列化结果。
 */
internal class PrivilegedDeviceApiClient(
    private val context: Context,
    private val userId: Int,
    private val apkPath: String = context.applicationInfo?.sourceDir.orEmpty(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun execute(
        execution: DeviceActionExecution,
        target: String,
        enabled: Boolean?,
    ): PrivilegedResult {
        // SDK 门槛前置校验：热点严格限制 API 36+，旧版本直接返回 unsupported_action，不用本地热点冒充
        if (target == "hotspot" && Build.VERSION.SDK_INT < 36) {
            return execution.failure("unsupported_action", "热点控制需要 Android 16 (API 36) 或更高版本。")
        }

        val isMutation = enabled != null
        val operation = when (target) {
            "night_light" -> if (isMutation) "night_light_set" else "night_light_get"
            "hotspot" -> if (isMutation) "hotspot_set" else "hotspot_get"
            else -> throw IllegalArgumentException("Unsupported helper target: $target")
        }

        val helperResult = executeHelper(execution, operation, enabled)
        if (!isMutation || !helperResult.success || target != "hotspot") {
            return helperResult
        }

        // 热点持久状态只读核对：辅助进程退出后再次只读核对，防止某些 ROM 在进程退出时撤销热点
        // 严格保留原 helperResult 的 before/commandAccepted 等 observation 字段，仅更新 after 与 verified
        val readBack = executeHelper(execution, "hotspot_get", enabled = null)
        val readBackValue = (readBack.observation?.get("value") as? JsonPrimitive)?.booleanOrNull
        if (!readBack.success || readBackValue != enabled) {
            val updatedObs = helperResult.observation?.let { orig ->
                val beforeElement = orig["before"]
                val afterElement = readBackValue?.let(::JsonPrimitive) ?: JsonNull
                val changedElement = if (beforeElement == null || beforeElement == JsonNull || afterElement == JsonNull) {
                    JsonNull
                } else {
                    JsonPrimitive(beforeElement != afterElement)
                }
                JsonObject(orig.toMutableMap().apply {
                    put("verified", JsonPrimitive(false))
                    put("after", afterElement)
                    put("changed", changedElement)
                })
            }
            return helperResult.copy(
                success = false,
                code = "state_change_unconfirmed",
                message = "热点状态在辅助进程退出后未保持，当前系统不支持跨进程持久修改热点状态。",
                observation = updatedObs,
            )
        }
        return helperResult
    }

    suspend fun probeCapabilities(execution: DeviceActionExecution): List<JsonObject> {
        val helperResult = executeHelper(execution, "capabilities", enabled = null)
        if (!helperResult.success) {
            val code = helperResult.code
            return listOf(
                fallbackTargetCapability("night_light", code),
                fallbackTargetCapability("hotspot", if (Build.VERSION.SDK_INT < 36) "unsupported_action" else code),
            )
        }

        val observationVal = helperResult.observation?.get("value") as? JsonObject
        val targetsArray = observationVal?.get("targets") as? JsonArray
        if (targetsArray == null) {
            return listOf(
                fallbackTargetCapability("night_light", "state_unavailable"),
                fallbackTargetCapability("hotspot", if (Build.VERSION.SDK_INT < 36) "unsupported_action" else "state_unavailable"),
            )
        }

        return targetsArray.mapNotNull { it as? JsonObject }
    }

    private suspend fun executeHelper(
        execution: DeviceActionExecution,
        operation: String,
        enabled: Boolean?,
    ): PrivilegedResult {
        val mappedAction = when (operation) {
            "capabilities" -> PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES
            "night_light_get", "hotspot_get" -> PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE
            "night_light_set", "hotspot_set" -> PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE
            else -> throw IllegalArgumentException("Unknown helper operation: $operation")
        }

        val requestId = execution.request.requestId
        val effectiveApkPath = apkPath.ifBlank {
            context.packageCodePath ?: context.applicationInfo?.sourceDir.orEmpty()
        }

        val helperArgs = mutableListOf(
            PrivilegedProcessRunner.shellQuote(requestId),
            PrivilegedProcessRunner.shellQuote(mappedAction),
            PrivilegedProcessRunner.shellQuote(userId.toString()),
            PrivilegedProcessRunner.shellQuote(operation),
        )
        if (enabled != null) {
            helperArgs.add(PrivilegedProcessRunner.shellQuote(enabled.toString()))
        }

        val commandLine = "CLASSPATH=${PrivilegedProcessRunner.shellQuote(effectiveApkPath)} " +
            "/system/bin/app_process /system/bin cn.com.omnimind.baselib.privileged.PrivilegedDeviceApiMain " +
            helperArgs.joinToString(" ")

        val isMutation = enabled != null
        val rawResult = execution.run("sh", "-c", commandLine)

        return parseHelperOutput(rawResult, requestId, mappedAction, isMutation, rawResult.backend)
    }

    internal fun parseHelperOutput(
        rawResult: PrivilegedResult,
        expectedRequestId: String,
        expectedAction: String,
        isMutation: Boolean,
        expectedBackend: PrivilegedBackend = rawResult.backend,
    ): PrivilegedResult {
        val unconfirmedCode = if (isMutation) "state_change_unconfirmed" else "state_unavailable"

        // 1. 底层命令错误（如 root_denied、timeout 等）直接保留
        if (!rawResult.success && rawResult.code in setOf("root_denied", "root_unavailable", "permission_denied")) {
            return rawResult.copy(requestId = expectedRequestId, action = expectedAction)
        }

        // 2. 输出截断校验
        if (rawResult.data["truncated"] == "true") {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = if (isMutation) "state_change_unconfirmed" else "capability_probe_incomplete",
                message = "设备 API 辅助进程输出被截断。",
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        // 3. 提取结果标记（权威首选非空 stdout，否则 output；不合并内容，不调用 distinct 掩盖重复）
        val sourceOutput = if (rawResult.stdout.isNotBlank()) rawResult.stdout else rawResult.output
        val markerLines = sourceOutput.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(MARKER_PREFIX) }
            .toList()
        if (markerLines.isEmpty()) {
            val failureCode = if (rawResult.code in setOf("root_denied", "permission_denied")) rawResult.code else unconfirmedCode
            val err = rawResult.stderr.ifBlank { rawResult.message }.trim()
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = failureCode,
                message = "未收到设备 API 辅助进程的结果标记: $err",
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        if (markerLines.size > 1) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = unconfirmedCode,
                message = "检测到设备 API 辅助进程返回了多条结果标记。",
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        // 4. 解析结果 JSON
        val jsonPayload = markerLines.single().removePrefix(MARKER_PREFIX).trim()
        val parsed = try {
            json.decodeFromString(PrivilegedResult.serializer(), jsonPayload)
        } catch (e: Exception) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = unconfirmedCode,
                message = "解析设备 API 辅助进程结果 JSON 失败: ${e.message}",
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        // 5. 校验 requestId 与 action 一致性
        if (parsed.requestId != expectedRequestId || parsed.action != expectedAction) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = unconfirmedCode,
                message = "设备 API 辅助进程返回的 requestId 或 action 不匹配 (收到 ${parsed.requestId}/${parsed.action}，预期 $expectedRequestId/$expectedAction)。",
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        // 6. 校验真实 UID（仅接受 UID 0 或 2000）
        val dataUid = parsed.data["uid"]?.toIntOrNull()
        if (parsed.uid != null && dataUid != null && parsed.uid != dataUid) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = "permission_denied",
                message = "设备 API 辅助进程返回的 UID 字段不一致 (parsed.uid=${parsed.uid}, data[uid]=$dataUid)。",
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }
        val actualUid = dataUid ?: parsed.uid
        if (actualUid != 0 && actualUid != 2000) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = "permission_denied",
                message = "设备 API 辅助进程以不受信任的 UID 运行: $actualUid",
                uid = actualUid,
                exitCode = rawResult.exitCode,
            )
        }

        // 7. 校验真实 UID 与外层 rawResult.uid 及特权后端类型一致
        if (rawResult.uid != null && actualUid != rawResult.uid) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = "permission_denied",
                message = "设备 API 辅助进程执行 UID ($actualUid) 与外层期望 UID (${rawResult.uid}) 不符。",
                uid = actualUid,
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }
        if (expectedBackend == PrivilegedBackend.ROOT && actualUid != 0) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = "permission_denied",
                message = "Root 特权后端不允许以非 Root 身份 ($actualUid) 执行辅助进程。",
                uid = actualUid,
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }
        if (expectedBackend == PrivilegedBackend.ADB && actualUid != 2000) {
            return PrivilegedResult(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = "permission_denied",
                message = "ADB 特权后端不允许以非 Shell 身份 ($actualUid) 执行辅助进程。",
                uid = actualUid,
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        // 8. 若外层执行失败（如超时 timeout / output_incomplete），即便残留 marker 也不能报告 verified 成功
        if (!rawResult.success) {
            val failureCode = if (rawResult.code in setOf("root_denied", "root_unavailable", "permission_denied")) {
                rawResult.code
            } else if (isMutation) {
                "state_change_unconfirmed"
            } else {
                rawResult.code.ifBlank { "state_unavailable" }
            }
            val updatedObs = parsed.observation?.let { orig ->
                JsonObject(orig.toMutableMap().apply {
                    put("verified", JsonPrimitive(false))
                })
            }
            return parsed.copy(
                requestId = expectedRequestId,
                action = expectedAction,
                success = false,
                code = failureCode,
                message = rawResult.message.ifBlank { "设备 API 辅助进程执行异常退出: ${rawResult.code}" },
                observation = updatedObs,
                uid = actualUid,
                stderr = rawResult.stderr,
                exitCode = rawResult.exitCode,
            )
        }

        return parsed.copy(uid = actualUid)
    }
    private fun fallbackTargetCapability(target: String, code: String): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("target", JsonPrimitive(target))
            put("readable", JsonPrimitive(false))
            put("writable", JsonPrimitive(false))
            put("code", JsonPrimitive(code))
        }

    companion object {
        const val MARKER_PREFIX = "OMNI_DEVICE_API_RESULT:"
    }
}
