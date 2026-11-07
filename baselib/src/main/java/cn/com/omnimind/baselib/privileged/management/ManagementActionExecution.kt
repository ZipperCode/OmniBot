package cn.com.omnimind.baselib.privileged.management

import cn.com.omnimind.baselib.privileged.PrivilegedBackend
import cn.com.omnimind.baselib.privileged.PrivilegedProvider
import cn.com.omnimind.baselib.privileged.PrivilegedRequest
import cn.com.omnimind.baselib.privileged.PrivilegedResult
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

internal class ManagementActionException(
    val code: String,
    message: String,
    val diagnostic: PrivilegedResult? = null,
) : Exception(message)

/**
 * 管理操作执行上下文，封装 Shell 参数转义、前置权限校验、真实状态读回和结构化 Observation 构建。
 */
internal class ManagementActionExecution(
    val request: PrivilegedRequest,
    val userId: Int,
    val backend: PrivilegedBackend,
    private val commandRunner: suspend (String) -> PrivilegedResult,
    private val stillAllowed: () -> Boolean,
) {
    suspend fun run(vararg arguments: String): PrivilegedResult {
        coroutineContext.ensureActive()
        checkAccess()
        val command = arguments.joinToString(" ", transform = ::shellQuote)
        return commandRunner(command)
    }

    suspend fun runRaw(command: String): PrivilegedResult {
        coroutineContext.ensureActive()
        checkAccess()
        return commandRunner(command)
    }

    fun checkAccess() {
        if (!stillAllowed()) {
            throw ManagementActionException("privileged_access_changed", "Privileged access changed before execution.")
        }
    }

    fun requireRoot() {
        if (backend != PrivilegedBackend.ROOT) {
            throw ManagementActionException("unsupported_action", "Action '${request.action}' requires a root backend.")
        }
    }

    fun success(
        message: String = "Action completed successfully.",
        observation: JsonObject? = null,
        data: Map<String, String> = emptyMap(),
        output: String = "",
        stdout: String = "",
        stderr: String = "",
        exitCode: Int? = 0,
    ): PrivilegedResult = PrivilegedResult(
        requestId = request.requestId,
        action = request.action,
        success = true,
        code = "ok",
        message = message,
        backend = backend,
        provider = if (backend == PrivilegedBackend.ROOT) PrivilegedProvider.ROOT else PrivilegedProvider.NONE,
        observation = observation,
        data = data,
        output = output,
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
    )

    fun failure(
        code: String,
        message: String,
        diagnostic: PrivilegedResult? = null,
        observation: JsonObject? = null,
    ): PrivilegedResult = PrivilegedResult(
        requestId = request.requestId,
        action = request.action,
        success = false,
        code = code,
        message = message,
        backend = diagnostic?.backend ?: backend,
        provider = diagnostic?.provider ?: (if (backend == PrivilegedBackend.ROOT) PrivilegedProvider.ROOT else PrivilegedProvider.NONE),
        observation = observation,
        output = diagnostic?.output.orEmpty(),
        stdout = diagnostic?.stdout.orEmpty(),
        stderr = diagnostic?.stderr.orEmpty(),
        exitCode = diagnostic?.exitCode,
    )

    fun failure(error: ManagementActionException): PrivilegedResult = failure(
        code = error.code,
        message = error.message.orEmpty(),
        diagnostic = error.diagnostic,
    )

    companion object {
        fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

        fun observation(
            before: JsonElement? = null,
            after: JsonElement? = null,
            expected: JsonElement? = null,
            commandAccepted: Boolean = true,
            verified: Boolean = false,
            scope: String = "device",
            userId: Int = 0,
            details: JsonObject? = null,
        ): JsonObject = buildJsonObject {
            put("userId", userId)
            put("scope", scope)
            put("before", before ?: JsonNull)
            put("after", after ?: JsonNull)
            if (expected != null) put("expected", expected)
            put("commandAccepted", commandAccepted)
            put("verified", verified)
            if (before != null && after != null) {
                put("changed", JsonPrimitive(before != after))
            }
            if (details != null) {
                put("details", details)
            }
        }
    }
}
