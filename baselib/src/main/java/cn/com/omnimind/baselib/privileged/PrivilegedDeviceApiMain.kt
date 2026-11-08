package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import androidx.annotation.Keep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executor

/**
 * 独立的系统设备控制 API 辅助进程入口。
 *
 * 通过 app_process 以 UID 0 (Root) 或 UID 2000 (Shell) 运行。
 * 严格白名单 CLI: requestId action userId operation [enabled]
 * 只读操作 (capabilities, night_light_get, hotspot_get) 严格为 4 个参数；
 * 修改操作 (night_light_set, hotspot_set) 严格为 5 个参数，enabled 为 true/false；
 * userId 必须是非负整数 (>= 0)。
 * 仅单次执行有界查询/修改与读回验证，输出单条 OMNI_DEVICE_API_RESULT: JSON 后退出，不常驻。
 */
@Keep
object PrivilegedDeviceApiMain {
    const val PREFIX = "OMNI_DEVICE_API_RESULT:"

    private const val OP_CAPABILITIES = "capabilities"
    private const val OP_NIGHT_LIGHT_GET = "night_light_get"
    private const val OP_NIGHT_LIGHT_SET = "night_light_set"
    private const val OP_HOTSPOT_GET = "hotspot_get"
    private const val OP_HOTSPOT_SET = "hotspot_set"

    private const val ACTION_CAPABILITIES = "device_control.capabilities"
    private const val ACTION_GET_STATE = "device_control.get_state"
    private const val ACTION_SET_STATE = "device_control.set_state"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val result = executeCli(args)
        println(PREFIX + json.encodeToString(PrivilegedResult.serializer(), result))
    }

    /**
     * 执行 CLI 逻辑并返回规范化的 PrivilegedResult。
     * 捕获所有异常并包装为错误结果，绝不打印堆栈。
     */
    fun executeCli(args: Array<String>): PrivilegedResult {
        val requestId = args.getOrNull(0) ?: "unknown"
        val action = args.getOrNull(1) ?: "unknown"
        val myUid = runCatching { Process.myUid() }.getOrDefault(-1)

        fun failureResult(code: String, message: String): PrivilegedResult = PrivilegedResult(
            requestId = requestId,
            action = action,
            success = false,
            code = code,
            message = message,
            uid = myUid,
            data = mapOf("uid" to myUid.toString())
        )

        val operation = args.getOrNull(3).orEmpty()
        val isMutation = operation in setOf(OP_NIGHT_LIGHT_SET, OP_HOTSPOT_SET)
        val expectedArgCount = if (isMutation) 5 else 4

        // 1. 参数数量检查：只读操作严格 4 个参数，修改操作严格 5 个参数
        if (args.size != expectedArgCount) {
            return failureResult(
                "invalid_arguments",
                if (isMutation) {
                    "修改操作 '$operation' 参数错误：严格要求 5 个参数 (requestId action userId operation enabled)"
                } else {
                    "只读操作 '$operation' 参数错误：严格要求 4 个参数 (requestId action userId operation)"
                }
            )
        }

        // 2. userId 解析：必须为非负整数 (>= 0)，拒绝负数特殊标识
        val userId = args[2].toIntOrNull()
        if (userId == null || userId < 0) {
            return failureResult(
                "invalid_arguments",
                "无效的 userId: '${args[2]}'，必须为非负整数 (>= 0)"
            )
        }

        val enabledArg = args.getOrNull(4)

        // 3. operation 与 action 固定映射校验
        val expectedAction = when (operation) {
            OP_CAPABILITIES -> ACTION_CAPABILITIES
            OP_NIGHT_LIGHT_GET, OP_HOTSPOT_GET -> ACTION_GET_STATE
            OP_NIGHT_LIGHT_SET, OP_HOTSPOT_SET -> ACTION_SET_STATE
            else -> return failureResult(
                "unsupported_action",
                "不支持的设备操作: $operation"
            )
        }

        if (action != expectedAction) {
            return failureResult(
                "invalid_arguments",
                "动作 '$action' 与操作 '$operation' 固定映射不匹配（预期为 '$expectedAction'）"
            )
        }

        // 4. 写操作参数检查
        if (isMutation) {
            if (enabledArg == null || enabledArg !in setOf("true", "false")) {
                return failureResult(
                    "invalid_arguments",
                    "修改操作 '$operation' 要求 enabled 参数必须为 'true' 或 'false' (当前为: '$enabledArg')"
                )
            }
        }

        // 5. UID 检查：仅允许 0 或 2000
        if (myUid !in setOf(0, 2000)) {
            return failureResult(
                "permission_denied",
                "设备 API 辅助进程必须以 UID 0 (Root) 或 UID 2000 (Shell) 运行 (当前 UID: $myUid)"
            )
        }

        // 6. 准备 Looper 与获取 Context
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }

        val systemContext = try {
            obtainSystemContext()
        } catch (e: Throwable) {
            return failureResult(
                "unsupported_action",
                "无法获取系统 Context: ${e.message}"
            )
        }

        val userContext = try {
            obtainUserContext(systemContext, userId)
        } catch (e: DeviceActionException) {
            return failureResult(e.code, e.message.orEmpty())
        } catch (e: Throwable) {
            return failureResult(
                "unsupported_action",
                "无法为用户 $userId 获取 Context: ${e.message}"
            )
        }

        // 7. 启动 HandlerThread 提供有效 Looper 处理 Android 系统回调
        val callbackThread = HandlerThread("DeviceApiHelperLooper").apply { start() }
        val callbackExecutor = Executor { command ->
            Handler(callbackThread.looper).post(command)
        }

        // 8. 进程与线程监督检查
        val initialPpid = runCatching { android.system.Os.getppid() }.getOrDefault(0)
        val callerThread = Thread.currentThread()
        val stillAllowed = {
            !callerThread.isInterrupted &&
                !Thread.currentThread().isInterrupted &&
                (initialPpid <= 1 || runCatching { android.system.Os.getppid() }.getOrDefault(0) == initialPpid)
        }

        val request = PrivilegedRequest(
            requestId = requestId,
            action = action,
            arguments = if (enabledArg != null) mapOf("enabled" to enabledArg) else emptyMap(),
            userId = userId,
        )

        val command: suspend (String) -> PrivilegedResult = {
            throw DeviceActionException("unsupported_action", "设备 API 辅助进程中已禁用 Shell 命令执行。")
        }

        val execution = DeviceActionExecution(request, userId, command, stillAllowed)

        try {
            val executedResult = runBlocking(Dispatchers.Default) {
                // 总查询/回调窗口最多 6 秒
                withTimeout(6000L) {
                    when (operation) {
                        OP_CAPABILITIES -> {
                            executeCapabilities(userContext, execution, callbackExecutor)
                        }
                        OP_NIGHT_LIGHT_GET -> {
                            PrivilegedNightLightApi.getState(userContext, execution)
                        }
                        OP_NIGHT_LIGHT_SET -> {
                            PrivilegedNightLightApi.setState(userContext, execution, enabledArg == "true")
                        }
                        OP_HOTSPOT_GET -> {
                            if (Build.VERSION.SDK_INT < 36) {
                                execution.failure("unsupported_action", "热点控制需要 Android API 36 或更高版本系统支持。")
                            } else {
                                PrivilegedTetheringApi36.getState(userContext, execution, callbackExecutor)
                            }
                        }
                        OP_HOTSPOT_SET -> {
                            if (Build.VERSION.SDK_INT < 36) {
                                execution.failure("unsupported_action", "热点控制需要 Android API 36 或更高版本系统支持。")
                            } else {
                                PrivilegedTetheringApi36.setState(userContext, execution, enabledArg == "true", callbackExecutor)
                            }
                        }
                        else -> execution.failure("unsupported_action", "不支持的设备操作: $operation")
                    }
                }
            }

            return executedResult.copy(
                requestId = requestId,
                action = action,
                uid = myUid,
                data = executedResult.data + mapOf("uid" to myUid.toString())
            )
        } catch (_: TimeoutCancellationException) {
            return failureResult(
                if (isMutation) "state_change_unconfirmed" else "timeout",
                if (isMutation) {
                    "请求超时（6 秒），设备状态修改未确认，切勿重复提交。"
                } else {
                    "操作超时（6 秒），未能完成状态读取。"
                }
            )
        } catch (e: DeviceActionException) {
            return e.diagnostic?.copy(
                requestId = requestId,
                action = action,
                uid = myUid,
                data = e.diagnostic.data + mapOf("uid" to myUid.toString())
            ) ?: failureResult(e.code, e.message.orEmpty())
        } catch (_: SecurityException) {
            return failureResult("permission_denied", "系统拒绝执行此设备操作（权限不足）。")
        } catch (e: Throwable) {
            return failureResult(
                "internal_error",
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            )
        } finally {
            callbackThread.quitSafely()
        }
    }

    private suspend fun executeCapabilities(
        context: Context,
        execution: DeviceActionExecution,
        executor: Executor
    ): PrivilegedResult {
        val targetsArray = buildJsonArray {
            // 1. night_light
            val (nlReadable, nlWritable, nlCode) = PrivilegedNightLightApi.probe(context, execution.userId)
            add(buildJsonObject {
                put("target", "night_light")
                put("readable", nlReadable)
                put("writable", nlWritable)
                put("code", nlCode)
            })

            // 2. hotspot
            val (hsReadable, hsWritable, hsCode) = if (Build.VERSION.SDK_INT >= 36) {
                PrivilegedTetheringApi36.probe(context, executor)
            } else {
                Triple(false, false, "unsupported_action")
            }
            add(buildJsonObject {
                put("target", "hotspot")
                put("readable", hsReadable)
                put("writable", hsWritable)
                put("code", hsCode)
            })
        }

        val observationValue = buildJsonObject {
            put("targets", targetsArray)
        }

        return execution.query(observationValue, scope = "device")
    }

    private fun obtainSystemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        var thread = runCatching {
            activityThreadClass.getMethod("currentActivityThread").invoke(null)
        }.getOrNull()

        if (thread == null) {
            thread = runCatching {
                activityThreadClass.getMethod("systemMain").invoke(null)
            }.getOrNull()
        }

        checkNotNull(thread) { "ActivityThread 实例未能初始化。" }

        val getSystemContextMethod = activityThreadClass.getMethod("getSystemContext")
        val context = getSystemContextMethod.invoke(thread) as? Context
        checkNotNull(context) { "未能从 ActivityThread 获取 systemContext。" }
        return context
    }

    private fun obtainUserContext(systemContext: Context, userId: Int): Context {
        val userHandle = runCatching {
            val ofMethod = runCatching {
                UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
            }.getOrNull()
            if (ofMethod != null) {
                ofMethod.invoke(null, userId) as? UserHandle
            } else {
                UserHandle.getUserHandleForUid(userId * 100000)
            }
        }.getOrNull()

        if (userHandle != null) {
            val created = runCatching {
                val createContextAsUserMethod = systemContext.javaClass.getMethod(
                    "createContextAsUser",
                    UserHandle::class.java,
                    Int::class.javaPrimitiveType
                )
                createContextAsUserMethod.invoke(systemContext, userHandle, 0) as? Context
            }.getOrNull()
            if (created != null) return created
        }

        if (userId == 0) return systemContext
        throw DeviceActionException("unsupported_action", "无法为用户 $userId 创建 Context。")
    }
}
