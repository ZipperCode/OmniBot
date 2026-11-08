package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * Android API 36+ 热点 (Wi-Fi Tethering) 控制实现。
 *
 * 独立类延迟加载，避免低版本系统类加载/验证时触发 TetheringManager 新嵌套类型缺失。
 * 严格使用 TetheringRequest.Builder(TETHERING_WIFI).build()，不配置/读写 SoftApConfiguration、SSID 或密码。
 */
@RequiresApi(36)
internal object PrivilegedTetheringApi36 {

    suspend fun probe(context: Context, executor: Executor): Triple<Boolean, Boolean, String> {
        val tetheringManager = try {
            context.getSystemService(TetheringManager::class.java)
        } catch (_: SecurityException) {
            return Triple(false, false, "permission_denied")
        } catch (_: Throwable) {
            null
        } ?: return Triple(false, false, "unsupported_action")

        val isTetheringSupported = runCatching {
            val method = tetheringManager.javaClass.getMethod("isTetheringSupported")
            method.invoke(tetheringManager) as? Boolean
        }.getOrNull()
        if (isTetheringSupported == false) {
            return Triple(false, false, "unsupported_action")
        }

        val callbackReceived = CompletableDeferred<Boolean>()
        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                if (!callbackReceived.isCompleted) {
                    callbackReceived.complete(true)
                }
            }
        }

        return try {
            tetheringManager.registerTetheringEventCallback(executor, callback)
            val received = withTimeoutOrNull(1000L) {
                callbackReceived.await()
            } ?: false

            if (received) {
                Triple(true, true, "ok")
            } else {
                Triple(false, false, "state_unavailable")
            }
        } catch (_: SecurityException) {
            Triple(false, false, "permission_denied")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Triple(false, false, "unsupported_action")
        } finally {
            runCatching { tetheringManager.unregisterTetheringEventCallback(callback) }
        }
    }

    suspend fun getState(
        context: Context,
        execution: DeviceActionExecution,
        executor: Executor
    ): PrivilegedResult {
        val tetheringManager = try {
            context.getSystemService(TetheringManager::class.java)
        } catch (_: SecurityException) {
            return execution.failure("permission_denied", "访问 TetheringManager 权限不足。")
        } catch (_: Throwable) {
            null
        } ?: return execution.failure("unsupported_action", "系统 TetheringManager 服务不可用。")

        val currentWifiTethered = AtomicReference<Boolean?>(null)
        val initialCallbackReceived = CompletableDeferred<Unit>()

        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                val wifiActive = interfaces.any { it.type == TetheringManager.TETHERING_WIFI }
                currentWifiTethered.set(wifiActive)
                if (!initialCallbackReceived.isCompleted) {
                    initialCallbackReceived.complete(Unit)
                }
            }
        }

        try {
            tetheringManager.registerTetheringEventCallback(executor, callback)
            // 初始读最多 1 秒
            withTimeoutOrNull(1000L) {
                initialCallbackReceived.await()
            }
            val state = currentWifiTethered.get()
            return execution.query(state?.let(::JsonPrimitive), scope = "device")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceActionException) {
            return execution.failure(e)
        } catch (_: SecurityException) {
            return execution.failure("permission_denied", "读取热点状态权限不足。")
        } catch (e: Throwable) {
            return execution.failure("command_failed", "查询热点状态失败: ${e.message}")
        } finally {
            runCatching { tetheringManager.unregisterTetheringEventCallback(callback) }
        }
    }

    suspend fun setState(
        context: Context,
        execution: DeviceActionExecution,
        enabled: Boolean,
        executor: Executor
    ): PrivilegedResult {
        val tetheringManager = try {
            context.getSystemService(TetheringManager::class.java)
        } catch (_: SecurityException) {
            return execution.failure("permission_denied", "访问 TetheringManager 权限不足。")
        } catch (_: Throwable) {
            null
        } ?: return execution.failure("unsupported_action", "系统 TetheringManager 服务不可用。")

        val currentWifiTethered = AtomicReference<Boolean?>(null)
        val initialCallbackReceived = CompletableDeferred<Unit>()

        val eventCallback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                val wifiActive = interfaces.any { it.type == TetheringManager.TETHERING_WIFI }
                currentWifiTethered.set(wifiActive)
                if (!initialCallbackReceived.isCompleted) {
                    initialCallbackReceived.complete(Unit)
                }
            }
        }

        try {
            tetheringManager.registerTetheringEventCallback(executor, eventCallback)
            // 初始读最多 1 秒
            withTimeoutOrNull(1000L) {
                initialCallbackReceived.await()
            }
            val beforeState = currentWifiTethered.get()

            return execution.mutate(
                expected = JsonPrimitive(enabled),
                scope = "device",
                read = { currentWifiTethered.get()?.let(::JsonPrimitive) },
                matches = { it == JsonPrimitive(enabled) },
                write = {
                    val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
                    val submissionDeferred = CompletableDeferred<PrivilegedResult>()

                    if (enabled) {
                        val startCallback = object : TetheringManager.StartTetheringCallback {
                            override fun onTetheringStarted() {
                                submissionDeferred.complete(
                                    PrivilegedResult(
                                        requestId = execution.request.requestId,
                                        action = execution.request.action,
                                        success = true,
                                        code = "ok",
                                        message = "已受理热点开启请求。"
                                    )
                                )
                            }

                            override fun onTetheringFailed(error: Int) {
                                submissionDeferred.complete(
                                    PrivilegedResult(
                                        requestId = execution.request.requestId,
                                        action = execution.request.action,
                                        success = false,
                                        code = "command_failed",
                                        message = "开启热点失败，系统返回错误码: $error。"
                                    )
                                )
                            }
                        }
                        try {
                            tetheringManager.startTethering(request, executor, startCallback)
                        } catch (_: SecurityException) {
                            throw DeviceActionException("permission_denied", "开启热点权限不足。")
                        } catch (e: CancellationException) {
                            throw e
                        }
                    } else {
                        val stopCallback = object : TetheringManager.StopTetheringCallback {
                            override fun onStopTetheringSucceeded() {
                                submissionDeferred.complete(
                                    PrivilegedResult(
                                        requestId = execution.request.requestId,
                                        action = execution.request.action,
                                        success = true,
                                        code = "ok",
                                        message = "已受理热点关闭请求。"
                                    )
                                )
                            }

                            override fun onStopTetheringFailed(error: Int) {
                                submissionDeferred.complete(
                                    PrivilegedResult(
                                        requestId = execution.request.requestId,
                                        action = execution.request.action,
                                        success = false,
                                        code = "command_failed",
                                        message = "关闭热点失败，系统返回错误码: $error。"
                                    )
                                )
                            }
                        }
                        try {
                            tetheringManager.stopTethering(request, executor, stopCallback)
                        } catch (_: SecurityException) {
                            throw DeviceActionException("permission_denied", "关闭热点权限不足。")
                        } catch (e: CancellationException) {
                            throw e
                        }
                    }

                    // 提交回调最多等待 2 秒；若超时，请求已发出，判定为状态未确认并透传 diagnostic
                    val submissionResult = withTimeoutOrNull(2000L) {
                        submissionDeferred.await()
                    }

                    if (submissionResult == null) {
                        val currentAfter = currentWifiTethered.get()?.let(::JsonPrimitive)
                        val standardObservation = DeviceActionExecution.observation(
                            before = beforeState?.let(::JsonPrimitive),
                            after = currentAfter,
                            expected = JsonPrimitive(enabled),
                            accepted = false,
                            verified = false,
                            userId = execution.userId,
                            scope = "device"
                        )
                        val submissionTimeoutObservation = buildJsonObject {
                            standardObservation.forEach { (key, value) ->
                                if (key == "commandAccepted") {
                                    put("commandAccepted", JsonNull)
                                } else {
                                    put(key, value)
                                }
                            }
                            put("commandSubmitted", true)
                            put("submissionTimedOut", true)
                        }
                        val diagnostic = PrivilegedResult(
                            requestId = execution.request.requestId,
                            action = execution.request.action,
                            success = false,
                            code = "state_change_unconfirmed",
                            message = "热点修改请求已向系统发出，但在 2 秒内未收到受理确认回调，目标状态未确认。请在重试前核实实际状态。",
                            observation = submissionTimeoutObservation
                        )
                        throw DeviceActionException("state_change_unconfirmed", "热点受理确认超时", diagnostic)
                    }

                    submissionResult
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceActionException) {
            return execution.failure(e)
        } catch (_: SecurityException) {
            return execution.failure("permission_denied", "访问热点权限不足。")
        } catch (e: Throwable) {
            return execution.failure("command_failed", "设置热点状态失败: ${e.message}")
        } finally {
            runCatching { tetheringManager.unregisterTetheringEventCallback(eventCallback) }
        }
    }
}
