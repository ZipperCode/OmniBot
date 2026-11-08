package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 宿主应用进程内的手电筒控制器。
 * 闪光灯必须在主应用进程中开启，不能在即退的独立辅助进程执行（进程退出会导致系统自动关闭闪光灯）。
 */
internal class PrivilegedTorchController(private val context: Context) {

    suspend fun execute(execution: DeviceActionExecution, enabled: Boolean?): PrivilegedResult {
        // 1. 检查 CAMERA 权限：复用宿主现有 CAMERA 权限声明，缺失时提供设置入口，不在工具流程内弹窗
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return PrivilegedResult(
                requestId = execution.request.requestId,
                action = execution.request.action,
                success = false,
                code = "permission_denied",
                message = "控制手电筒需要相机权限，请在系统应用设置中授权。",
                data = mapOf(
                    "settingsAction" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "packageName" to context.packageName,
                ),
            )
        }

        // 2. 选择唯一可用相机：优先唯一具备闪光的背置相机；无背置闪光时使用唯一闪光相机；候选不唯一明确 unsupported
        val cameraManager = context.getSystemService(CameraManager::class.java)
            ?: return execution.failure("unsupported_action", "相机服务不可用。")

        val selectedCameraId = resolveFlashCameraId(cameraManager)
            ?: return execution.failure("unsupported_action", "当前设备未提供唯一的闪光灯相机。")

        // 3. 注册单次 TorchCallback，有界等待初始状态
        var currentTorchState: Boolean? = null
        val initialLatch = CompletableDeferred<Boolean?>()
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, mode: Boolean) {
                if (cameraId == selectedCameraId) {
                    currentTorchState = mode
                    if (!initialLatch.isCompleted) {
                        initialLatch.complete(mode)
                    }
                }
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == selectedCameraId) {
                    currentTorchState = null
                    if (!initialLatch.isCompleted) {
                        initialLatch.complete(null)
                    }
                }
            }
        }

        val handler = Handler(Looper.getMainLooper())
        cameraManager.registerTorchCallback(callback, handler)
        try {
            // 有界等待初始状态回调（CameraManager 会在注册后立即分发当前状态）
            val initialState = withTimeoutOrNull(INITIAL_STATE_TIMEOUT_MILLIS) {
                initialLatch.await()
            }

            return if (enabled == null) {
                // 查询状态：unknown 保持 null，不伪造成 false
                execution.query(initialState?.let(::JsonPrimitive), scope = "device")
            } else {
                // 修改状态：复用 DeviceActionExecution.mutate，同目标不写
                execution.mutate(
                    expected = JsonPrimitive(enabled),
                    scope = "device",
                    read = { currentTorchState?.let(::JsonPrimitive) },
                ) {
                    try {
                        cameraManager.setTorchMode(selectedCameraId, enabled)
                        PrivilegedResult(
                            requestId = execution.request.requestId,
                            action = execution.request.action,
                            success = true,
                            code = "ok",
                            message = "手电筒请求已提交。",
                        )
                    } catch (e: SecurityException) {
                        PrivilegedResult(
                            requestId = execution.request.requestId,
                            action = execution.request.action,
                            success = false,
                            code = "permission_denied",
                            message = "相机权限已被撤销，请在系统应用设置中重新授权。",
                            data = mapOf(
                                "settingsAction" to Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "packageName" to context.packageName,
                            ),
                        )
                    } catch (e: CameraAccessException) {
                        PrivilegedResult(
                            requestId = execution.request.requestId,
                            action = execution.request.action,
                            success = false,
                            code = "state_unavailable",
                            message = "设置手电筒模式时相机访问异常: ${e.message}",
                        )
                    } catch (e: Exception) {
                        PrivilegedResult(
                            requestId = execution.request.requestId,
                            action = execution.request.action,
                            success = false,
                            code = "state_unavailable",
                            message = "设置手电筒模式失败: ${e.message}",
                        )
                    }
                }
            }
        } finally {
            cameraManager.unregisterTorchCallback(callback)
        }
    }

    internal fun resolveFlashCameraId(cameraManager: CameraManager): String? {
        val ids = try {
            cameraManager.cameraIdList
        } catch (_: Exception) {
            return null
        }
        val flashCandidates = mutableListOf<CameraCandidate>()
        for (id in ids) {
            val characteristics = try {
                cameraManager.getCameraCharacteristics(id)
            } catch (_: Exception) {
                null
            } ?: continue
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash) {
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                flashCandidates.add(CameraCandidate(id, facing))
            }
        }

        val backWithFlash = flashCandidates.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        return when {
            backWithFlash.size == 1 -> backWithFlash.single().id
            backWithFlash.isEmpty() && flashCandidates.size == 1 -> flashCandidates.single().id
            else -> null // 候选不唯一或无闪光
        }
    }

    internal data class CameraCandidate(val id: String, val facing: Int?)

    companion object {
        private const val INITIAL_STATE_TIMEOUT_MILLIS = 1000L
    }
}
