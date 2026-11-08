package cn.com.omnimind.baselib.privileged

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.InvocationTargetException

/**
 * 标准 Android 护眼模式 (Night Light) API 桥接。
 *
 * 仅固定反射 ColorDisplayManager 的 isNightDisplayAvailable(Context)、
 * isNightDisplayActivated()、setNightDisplayActivated(boolean)。
 * 底层 ColorDisplayService 固定操作当前前台用户 mCurrentUser 且不接收 userId；
 * 因此固定核验请求 userId == ActivityManager.getCurrentUser()，不符时返回 unsupported_action，
 * 避免工作资料或后台用户误操作前台用户且产生虚假 userId 记录。
 */
internal object PrivilegedNightLightApi {
    private const val COLOR_DISPLAY_MANAGER_CLASS = "android.hardware.display.ColorDisplayManager"

    fun getCurrentUserId(): Int? = runCatching {
        val clazz = Class.forName("android.app.ActivityManager")
        val method = clazz.getMethod("getCurrentUser")
        method.invoke(null) as? Int
    }.getOrNull()

    fun probe(context: Context, userId: Int): Triple<Boolean, Boolean, String> {
        val currentUserId = getCurrentUserId() ?: return Triple(false, false, "state_unavailable")
        if (userId != currentUserId) {
            return Triple(false, false, "unsupported_action")
        }

        return try {
            val clazz = Class.forName(COLOR_DISPLAY_MANAGER_CLASS)
            val isAvailableMethod = clazz.getMethod("isNightDisplayAvailable", Context::class.java)
            val available = isAvailableMethod.invoke(null, context) as? Boolean ?: false
            if (!available) {
                Triple(false, false, "unsupported_action")
            } else {
                val service = context.getSystemService("color_display")
                    ?: context.getSystemService(clazz)
                if (service != null) {
                    Triple(true, true, "ok")
                } else {
                    Triple(false, false, "unsupported_action")
                }
            }
        } catch (_: ClassNotFoundException) {
            Triple(false, false, "unsupported_action")
        } catch (_: NoSuchMethodException) {
            Triple(false, false, "unsupported_action")
        } catch (_: SecurityException) {
            Triple(false, false, "permission_denied")
        } catch (e: InvocationTargetException) {
            if (e.targetException is SecurityException) {
                Triple(false, false, "permission_denied")
            } else {
                Triple(false, false, "unsupported_action")
            }
        } catch (_: Throwable) {
            Triple(false, false, "unsupported_action")
        }
    }

    fun readState(context: Context, userId: Int): Boolean? {
        val currentUserId = getCurrentUserId() ?: return null
        if (userId != currentUserId) return null

        return try {
            val clazz = Class.forName(COLOR_DISPLAY_MANAGER_CLASS)
            val isAvailableMethod = clazz.getMethod("isNightDisplayAvailable", Context::class.java)
            val available = isAvailableMethod.invoke(null, context) as? Boolean ?: false
            if (!available) return null

            val service = context.getSystemService("color_display")
                ?: context.getSystemService(clazz) ?: return null
            val isActivatedMethod = clazz.getMethod("isNightDisplayActivated")
            isActivatedMethod.invoke(service) as? Boolean
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getState(context: Context, execution: DeviceActionExecution): PrivilegedResult {
        val currentUserId = getCurrentUserId()
            ?: return execution.failure("state_unavailable", "无法获取当前前台用户 ID。")
        if (execution.userId != currentUserId) {
            return execution.failure(
                "unsupported_action",
                "标准护眼模式仅支持当前前台用户 (当前前台用户: $currentUserId，请求用户: ${execution.userId})。"
            )
        }

        val activated = readState(context, execution.userId)
        if (activated == null) {
            val (_, _, code) = probe(context, execution.userId)
            if (code != "ok") {
                return execution.failure(code, "当前设备不支持标准护眼模式或服务不可访问。")
            }
        }
        return execution.query(activated?.let(::JsonPrimitive), scope = "device")
    }

    suspend fun setState(context: Context, execution: DeviceActionExecution, enabled: Boolean): PrivilegedResult {
        val currentUserId = getCurrentUserId()
            ?: return execution.failure("state_unavailable", "无法获取当前前台用户 ID。")
        if (execution.userId != currentUserId) {
            return execution.failure(
                "unsupported_action",
                "标准护眼模式仅支持当前前台用户 (当前前台用户: $currentUserId，请求用户: ${execution.userId})。"
            )
        }

        val (_, _, code) = probe(context, execution.userId)
        if (code != "ok") {
            return execution.failure(code, "当前设备不支持标准护眼模式或服务不可访问。")
        }

        val clazz = try {
            Class.forName(COLOR_DISPLAY_MANAGER_CLASS)
        } catch (_: Throwable) {
            return execution.failure("unsupported_action", "未找到系统 ColorDisplayManager 服务类。")
        }

        val service = context.getSystemService("color_display")
            ?: context.getSystemService(clazz)
            ?: return execution.failure("unsupported_action", "系统 ColorDisplayManager 服务不可用。")

        val setActivatedMethod = try {
            clazz.getMethod("setNightDisplayActivated", Boolean::class.javaPrimitiveType)
        } catch (_: Throwable) {
            return execution.failure("unsupported_action", "ColorDisplayManager 未声明 setNightDisplayActivated 方法。")
        }

        return execution.mutate(
            expected = JsonPrimitive(enabled),
            scope = "device",
            read = { readState(context, execution.userId)?.let(::JsonPrimitive) },
            matches = { it == JsonPrimitive(enabled) },
            write = {
                val success = try {
                    setActivatedMethod.invoke(service, enabled) as? Boolean ?: false
                } catch (e: InvocationTargetException) {
                    val target = e.targetException
                    if (target is CancellationException) throw target
                    if (target is SecurityException) {
                        throw DeviceActionException("permission_denied", "设置护眼模式权限不足。")
                    }
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SecurityException) {
                    throw DeviceActionException("permission_denied", "设置护眼模式权限不足。")
                } catch (_: Throwable) {
                    false
                }

                if (success) {
                    PrivilegedResult(
                        requestId = execution.request.requestId,
                        action = execution.request.action,
                        success = true,
                        code = "ok",
                        message = "已受理护眼模式修改请求。"
                    )
                } else {
                    PrivilegedResult(
                        requestId = execution.request.requestId,
                        action = execution.request.action,
                        success = false,
                        code = "command_failed",
                        message = "ColorDisplayManager 拒绝了护眼模式状态修改。"
                    )
                }
            }
        )
    }
}
