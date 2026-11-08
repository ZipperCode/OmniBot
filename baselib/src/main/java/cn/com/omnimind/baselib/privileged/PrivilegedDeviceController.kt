package cn.com.omnimind.baselib.privileged

import android.app.NotificationManager
import android.media.AudioManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class PrivilegedDeviceController(
    private val context: Context,
    private val userId: Int,
    apkPath: String = context.applicationInfo?.sourceDir.orEmpty(),
) {
    private val displaySound = PrivilegedDisplaySoundControls(context)
    private val torch = PrivilegedTorchController(context)
    private val apiClient = PrivilegedDeviceApiClient(context, userId, apkPath)

    fun handles(action: String): Boolean = action in actions

    suspend fun execute(
        request: PrivilegedRequest,
        backend: PrivilegedBackend,
        stillAllowed: () -> Boolean,
        command: suspend (String) -> PrivilegedResult,
    ): PrivilegedResult {
        val execution = DeviceActionExecution(request, userId, command, stillAllowed)
        return try {
            when (request.action) {
                PrivilegedActionPolicy.ACTION_SETTINGS_GET, PrivilegedActionPolicy.ACTION_SETTINGS_PUT -> setting(execution)
                PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES -> capabilities(execution, backend)
                PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE -> {
                    val target = target(request.arguments)
                    when (target) {
                        "flashlight" -> {
                            requireHardware(target)
                            torch.execute(execution, enabled = null)
                        }
                        "night_light", "hotspot" -> {
                            apiClient.execute(execution, target, enabled = null)
                        }
                        else -> execution.query(readState(target, execution)?.let(::JsonPrimitive), scope(target))
                    }
                }
                PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
                PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED,
                PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED -> {
                    val target = when (request.action) {
                        PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED -> "wifi"
                        PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED -> "mobile_data"
                        else -> target(request.arguments)
                    }
                    requireHardware(target)
                    val enabled = DeviceActionExecution.boolean(request.arguments, "enabled")
                    when (target) {
                        "flashlight" -> torch.execute(execution, enabled = enabled)
                        "night_light", "hotspot" -> apiClient.execute(execution, target, enabled = enabled)
                        else -> {
                            if (target == "mobile_data" && backend != PrivilegedBackend.ROOT) {
                                throw DeviceActionException("unsupported_action", "Mobile data control requires a root backend.")
                            }
                            if (target == "dark_mode" && userId != 0) {
                                throw DeviceActionException("unsupported_action", "This system night-mode command does not support selecting the app's Android user.")
                            }
                            val command = stateCommand(target, enabled, execution)
                            execution.mutate(JsonPrimitive(enabled), scope(target), read = {
                                readState(target, execution)?.let(::JsonPrimitive)
                            }) { execution.run(*command.toTypedArray()) }
                        }
                    }
                }
                else -> displaySound.execute(execution)
            }
        } catch (error: CancellationException) { throw error
        } catch (error: DeviceActionException) { execution.failure(error)
        } catch (error: IllegalArgumentException) { execution.failure("invalid_arguments", error.message.orEmpty())
        } catch (_: SecurityException) { execution.failure("permission_denied", "The system denied access to this device capability.")
        } catch (_: UnsupportedOperationException) { execution.failure("unsupported_action", "This device does not support the operation.")
        }
    }

    private suspend fun setting(execution: DeviceActionExecution): PrivilegedResult {
        val args = execution.request.arguments
        val namespace = args["namespace"].orEmpty()
        val key = args["key"].orEmpty()
        require(namespace in setOf("system", "secure", "global")) { "Unsupported settings namespace." }
        require(Regex("^[A-Za-z0-9_.:-]+$").matches(key)) { "Invalid settings key." }
        args["userId"]?.let { require(it.toIntOrNull() == userId) { "Cross-user settings access is not supported." } }
        val scope = if (namespace == "global") "device" else "app_user"
        suspend fun read(): JsonElement? {
            val result = execution.run("settings", "--user", userId.toString(), "get", namespace, key)
            return if (result.success && result.data["truncated"] != "true") {
                JsonPrimitive(result.stdout.removeSuffix("\n"))
            } else null
        }
        if (execution.request.action == PrivilegedActionPolicy.ACTION_SETTINGS_GET) {
            val value = read()
            return execution.query(value, scope).copy(data = mapOf("value" to (value as? JsonPrimitive)?.content.orEmpty()))
        }
        val value = requireNotNull(args["value"]) { "value is required." }
        require(value.length <= 2000) { "Settings value is too long." }
        return execution.mutate(JsonPrimitive(value), scope, read = ::read) {
            execution.run("settings", "--user", userId.toString(), "put", namespace, key, value)
        }
    }

    private suspend fun readState(target: String, execution: DeviceActionExecution): Boolean? {
        requireHardware(target)
        val api = try {
            when (target) {
                "wifi" -> when (context.getSystemService(WifiManager::class.java)?.wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> true
                    WifiManager.WIFI_STATE_DISABLED -> false
                    else -> null
                }
                "bluetooth" -> context.getSystemService(BluetoothManager::class.java)?.adapter?.let {
                    when (it.state) { 10 -> false; 12 -> true; else -> null }
                }
                "mobile_data" -> SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it >= 0 }?.let {
                    context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(it)?.isDataEnabled
                }
                "airplane_mode" -> settingBoolean(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1))
                "location" -> context.getSystemService(LocationManager::class.java)?.isLocationEnabled
                "nfc" -> NfcAdapter.getDefaultAdapter(context)?.isEnabled
                "battery_saver" -> context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
                "auto_rotate" -> settingBoolean(Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1))
                "auto_brightness" -> settingBoolean(Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
                "dark_mode" -> context.getSystemService(UiModeManager::class.java)?.nightMode?.let {
                    when (it) { UiModeManager.MODE_NIGHT_YES -> true; UiModeManager.MODE_NIGHT_NO -> false; else -> null }
                }
                else -> null
            }
        } catch (_: SecurityException) { null }
        if (api != null) return api
        if (target == "nfc") {
            val result = execution.run("cmd", "nfc", "status")
            return if (result.success) when (result.stdout.trim()) {
                "Nfc is enabled" -> true
                "Nfc is disabled" -> false
                else -> null
            } else null
        }
        if (target != "bluetooth") return null
        val result = execution.run("dumpsys", "bluetooth_manager")
        return if (result.success && result.data["truncated"] != "true") bluetoothState(result.stdout) else null
    }

    private suspend fun stateCommand(target: String, enabled: Boolean, execution: DeviceActionExecution): List<String> {
        fun command(service: String, operation: String, vararg args: String) = listOf("cmd", service, operation) + args
        val value = if (enabled) "1" else "0"
        val selected = when (target) {
            "wifi" -> if (execution.supports("wifi", "set-wifi-enabled")) {
                command("wifi", "set-wifi-enabled", if (enabled) "enabled" else "disabled")
            } else listOf("svc", "wifi", if (enabled) "enable" else "disable")
            "bluetooth" -> command("bluetooth_manager", if (enabled) "enable" else "disable")
            "mobile_data" -> listOf("svc", "data", if (enabled) "enable" else "disable")
            "airplane_mode" -> command("connectivity", "airplane-mode", if (enabled) "enable" else "disable")
            "location" -> command("location", "set-location-enabled", enabled.toString(), "--user", userId.toString())
            "nfc" -> command("nfc", if (enabled) "enable-nfc" else "disable-nfc")
            "battery_saver" -> command("power", "set-mode", value)
            "auto_rotate" -> listOf("settings", "--user", userId.toString(), "put", "system", "accelerometer_rotation", value)
            "auto_brightness" -> listOf("settings", "--user", userId.toString(), "put", "system", "screen_brightness_mode", value)
            "dark_mode" -> command("uimode", "night", if (enabled) "yes" else "no")
            else -> throw IllegalArgumentException("Unsupported device state.")
        }
        if (selected.first() == "cmd") execution.requireCommand(selected[1], selected[2])
        if (selected.first() == "svc") {
            val help = execution.run("svc", "help")
            if (!(help.stdout + help.stderr).contains(Regex("\\b${selected[1]}\\b"))) {
                throw DeviceActionException("unsupported_action", "This system does not provide svc/${selected[1]}.")
            }
        }
        return selected
    }

    private fun requireHardware(target: String) {
        val feature = when (target) {
            "wifi" -> PackageManager.FEATURE_WIFI
            "bluetooth" -> PackageManager.FEATURE_BLUETOOTH
            "mobile_data" -> PackageManager.FEATURE_TELEPHONY
            "nfc" -> PackageManager.FEATURE_NFC
            "location" -> PackageManager.FEATURE_LOCATION
            "flashlight" -> PackageManager.FEATURE_CAMERA_FLASH
            else -> return
        }
        if (!context.packageManager.hasSystemFeature(feature)) throw DeviceActionException("unsupported_action", "The device does not support $target.")
    }

    companion object {
        val targets = PrivilegedActionPolicy.deviceStateTargets
        private val actions = setOf(
            PrivilegedActionPolicy.ACTION_SETTINGS_GET, PrivilegedActionPolicy.ACTION_SETTINGS_PUT,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE, PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED, PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED,
            PrivilegedActionPolicy.ACTION_DEVICE_GET_DISPLAY, PrivilegedActionPolicy.ACTION_DEVICE_SET_BRIGHTNESS,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_SCREEN_TIMEOUT, PrivilegedActionPolicy.ACTION_DEVICE_GET_SOUND,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_VOLUME, PrivilegedActionPolicy.ACTION_DEVICE_SET_DND,
            PrivilegedActionPolicy.ACTION_DEVICE_SET_RINGER_MODE, PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES,
        )
        private fun target(args: Map<String, String>): String = requireNotNull(args["target"]?.takeIf { it in targets }) { "Unsupported device state." }
        private fun scope(target: String): String = if (target in setOf("location", "auto_rotate", "auto_brightness", "dark_mode")) "app_user" else "device"
        private fun settingBoolean(value: Int): Boolean? = when (value) { 0 -> false; 1 -> true; else -> null }
        internal fun bluetoothState(output: String): Boolean? = when (
            Regex("(?m)^\\s*state:\\s*(ON|OFF|10|12)\\s*$").find(output)?.groupValues?.get(1)
        ) { "ON", "12" -> true; "OFF", "10" -> false; else -> null }
    }

    private suspend fun capabilities(execution: DeviceActionExecution, backend: PrivilegedBackend): PrivilegedResult {
        val pm = context.packageManager
        val targetsList = mutableListOf<JsonObject>()

        if (backend == PrivilegedBackend.NONE) {
            // 无授权状态：零命令执行，不探测 su，保守返回 permission_denied 或 unsupported_action
            for (target in PrivilegedActionPolicy.deviceStateTargets) {
                when (target) {
                    "night_light" -> {
                        targetsList.add(targetCapability(target, readable = false, writable = false, code = "permission_denied"))
                    }
                    "hotspot" -> {
                        val code = if (Build.VERSION.SDK_INT < 36) "unsupported_action" else "permission_denied"
                        targetsList.add(targetCapability(target, readable = false, writable = false, code = code))
                    }
                    "flashlight" -> {
                        val torchResult = torch.execute(execution, enabled = null)
                        if (torchResult.success) {
                            targetsList.add(targetCapability(target, readable = true, writable = true, code = "ok"))
                        } else {
                            targetsList.add(targetCapability(target, readable = false, writable = false, code = torchResult.code))
                        }
                    }
                    "wifi" -> {
                        val has = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
                        targetsList.add(targetCapability(target, readable = has, writable = false, code = if (!has) "unsupported_action" else "permission_denied"))
                    }
                    "bluetooth" -> {
                        val has = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                        targetsList.add(targetCapability(target, readable = has, writable = false, code = if (!has) "unsupported_action" else "permission_denied"))
                    }
                    "mobile_data" -> {
                        val has = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                        targetsList.add(targetCapability(target, readable = has, writable = false, code = "unsupported_action"))
                    }
                    "location" -> {
                        val has = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)
                        targetsList.add(targetCapability(target, readable = has, writable = false, code = if (!has) "unsupported_action" else "permission_denied"))
                    }
                    "nfc" -> {
                        val has = pm.hasSystemFeature(PackageManager.FEATURE_NFC)
                        targetsList.add(targetCapability(target, readable = has, writable = false, code = if (!has) "unsupported_action" else "permission_denied"))
                    }
                    "airplane_mode", "battery_saver", "auto_rotate", "auto_brightness" -> {
                        targetsList.add(targetCapability(target, readable = true, writable = false, code = "permission_denied"))
                    }
                    "dark_mode" -> {
                        val supported = userId == 0
                        targetsList.add(targetCapability(target, readable = true, writable = false, code = if (!supported) "unsupported_action" else "permission_denied"))
                    }
                    else -> {
                        targetsList.add(targetCapability(target, readable = false, writable = false, code = "unsupported_action"))
                    }
                }
            }
        } else {
            // 特权后端已授权：复用实际 readState 与固定 supports 命令探测
            val helperTargets = try {
                apiClient.probeCapabilities(execution)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceActionException) {
                if (e.code == "privileged_access_changed") throw e
                listOf(
                    fallbackTargetCapability("night_light", "state_unavailable"),
                    fallbackTargetCapability("hotspot", if (Build.VERSION.SDK_INT < 36) "unsupported_action" else "state_unavailable"),
                )
            }
            val helperTargetsMap = helperTargets.associateBy {
                (it["target"] as? JsonPrimitive)?.content.orEmpty()
            }

            for (target in PrivilegedActionPolicy.deviceStateTargets) {
                when (target) {
                    "night_light", "hotspot" -> {
                        helperTargetsMap[target]?.let { targetsList.add(it) } ?: targetsList.add(
                            fallbackTargetCapability(target, "unsupported_action")
                        )
                    }
                    "flashlight" -> {
                        val torchResult = torch.execute(execution, enabled = null)
                        if (torchResult.success) {
                            targetsList.add(targetCapability(target, readable = true, writable = true, code = "ok"))
                        } else {
                            targetsList.add(targetCapability(target, readable = false, writable = false, code = torchResult.code))
                        }
                    }
                    else -> {
                        var readOk = false
                        var readCode = "ok"
                        try {
                            execution.checkAccess()
                            val state = readState(target, execution)
                            if (state != null) {
                                readOk = true
                                readCode = "ok"
                            } else {
                                readOk = false
                                readCode = "state_unavailable"
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: DeviceActionException) {
                            if (e.code == "privileged_access_changed") throw e
                            readOk = false
                            readCode = e.code
                        } catch (_: SecurityException) {
                            readOk = false
                            readCode = "permission_denied"
                        } catch (_: Exception) {
                            readOk = false
                            readCode = "unsupported_action"
                        }

                        var writeOk = false
                        var writeCode = "ok"
                        if (!readOk && readCode == "unsupported_action") {
                            writeOk = false
                            writeCode = "unsupported_action"
                        } else {
                            try {
                                execution.checkAccess()
                                 when (target) {
                                    "wifi" -> {
                                        writeOk = execution.supports("wifi", "set-wifi-enabled") || try {
                                            val help = execution.run("svc", "help")
                                            (help.stdout + help.stderr).contains(Regex("\\bwifi\\b"))
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: DeviceActionException) {
                                            if (e.code == "privileged_access_changed") throw e
                                            false
                                        } catch (_: Exception) { false }
                                        writeCode = if (writeOk) "ok" else "unsupported_action"
                                    }
                                    "bluetooth" -> {
                                        writeOk = execution.supports("bluetooth_manager", "enable")
                                        writeCode = if (writeOk) "ok" else "unsupported_action"
                                    }
                                    "mobile_data" -> {
                                        if (backend != PrivilegedBackend.ROOT) {
                                            writeOk = false
                                            writeCode = "unsupported_action"
                                        } else {
                                            val help = execution.run("svc", "help")
                                            writeOk = (help.stdout + help.stderr).contains(Regex("\\bdata\\b"))
                                            writeCode = if (writeOk) "ok" else "unsupported_action"
                                        }
                                    }
                                    "location" -> {
                                        writeOk = execution.supports("location", "set-location-enabled")
                                        writeCode = if (writeOk) "ok" else "unsupported_action"
                                    }
                                    "nfc" -> {
                                        writeOk = execution.supports("nfc", "enable-nfc")
                                        writeCode = if (writeOk) "ok" else "unsupported_action"
                                    }
                                    "battery_saver" -> {
                                        writeOk = execution.supports("power", "set-mode")
                                        writeCode = if (writeOk) "ok" else "unsupported_action"
                                    }
                                    "dark_mode" -> {
                                        if (userId != 0) {
                                            writeOk = false
                                            writeCode = "unsupported_action"
                                        } else {
                                            writeOk = execution.supports("uimode", "night")
                                            writeCode = if (writeOk) "ok" else "unsupported_action"
                                        }
                                    }
                                    "airplane_mode" -> {
                                        writeOk = execution.supports("connectivity", "airplane-mode")
                                        writeCode = if (writeOk) "ok" else "unsupported_action"
                                    }
                                    "auto_rotate", "auto_brightness" -> {
                                        writeOk = readOk
                                        writeCode = if (readOk) "ok" else readCode
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: DeviceActionException) {
                                if (e.code == "privileged_access_changed") throw e
                                writeOk = false
                                writeCode = e.code
                            } catch (_: SecurityException) {
                                writeOk = false
                                writeCode = "permission_denied"
                            } catch (_: Exception) {
                                writeOk = false
                                writeCode = "unsupported_action"
                            }
                        }

                        val overallCode = if (readOk || writeOk) "ok"
                            else if (readCode == "unsupported_action" || writeCode == "unsupported_action") "unsupported_action"
                            else if (readCode == "permission_denied" || writeCode == "permission_denied") "permission_denied"
                            else readCode

                        targetsList.add(targetCapability(target, readable = readOk, writable = writeOk, code = overallCode))
                    }
                }
            }
        }

        // 探测受控 actions 白名单（固定 10 项）
        val audioManager = context.getSystemService(AudioManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        val brightnessAvailable = if (backend == PrivilegedBackend.NONE) false else try {
            execution.supports("display", "set-brightness")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceActionException) {
            if (e.code == "privileged_access_changed") throw e
            false
        } catch (_: Exception) { false }

        val dndAvailable = if (backend == PrivilegedBackend.NONE) false else try {
            execution.supports("notification", "set_dnd")
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceActionException) {
            if (e.code == "privileged_access_changed") throw e
            false
        } catch (_: Exception) { false }

        val isVolumeFixed = audioManager?.isVolumeFixed == true
        val isNotificationPolicyGranted = notificationManager?.isNotificationPolicyAccessGranted == true
        val ringerAvailable = audioManager != null && !isVolumeFixed && isNotificationPolicyGranted
        val ringerCode = when {
            audioManager == null -> "unsupported_action"
            isVolumeFixed -> "unsupported_action"
            !isNotificationPolicyGranted -> "permission_denied"
            else -> "ok"
        }

        val controlledActions = listOf(
            actionCapability("device_control.capabilities", available = true, code = "ok"),
            actionCapability("device_control.get_state", available = true, code = "ok"),
            actionCapability(
                "device_control.set_state",
                available = backend != PrivilegedBackend.NONE,
                code = if (backend != PrivilegedBackend.NONE) "ok" else "permission_denied"
            ),
            actionCapability("device_control.get_display", available = true, code = "ok"),
            actionCapability(
                "device_control.set_brightness",
                available = brightnessAvailable,
                code = if (backend == PrivilegedBackend.NONE) "permission_denied" else if (brightnessAvailable) "ok" else "unsupported_action"
            ),
            actionCapability(
                "device_control.set_screen_timeout",
                available = backend != PrivilegedBackend.NONE,
                code = if (backend != PrivilegedBackend.NONE) "ok" else "permission_denied"
            ),
            actionCapability("device_control.get_sound", available = (audioManager != null), code = if (audioManager != null) "ok" else "unsupported_action"),
            actionCapability(
                "device_control.set_volume",
                available = (audioManager != null && !isVolumeFixed),
                code = if (audioManager == null || isVolumeFixed) "unsupported_action" else "ok"
            ),
            actionCapability(
                "device_control.set_do_not_disturb",
                available = dndAvailable,
                code = if (backend == PrivilegedBackend.NONE) "permission_denied" else if (dndAvailable) "ok" else "unsupported_action"
            ),
            actionCapability("device_control.set_ringer_mode", available = ringerAvailable, code = ringerCode),
        )

        return execution.query(
            buildJsonObject {
                put("targets", JsonArray(targetsList))
                put("actions", JsonArray(controlledActions))
            },
            scope = "device"
        )
    }

    private fun targetCapability(target: String, readable: Boolean, writable: Boolean, code: String): JsonObject =
        buildJsonObject {
            put("target", JsonPrimitive(target))
            put("readable", JsonPrimitive(readable))
            put("writable", JsonPrimitive(writable))
            put("code", JsonPrimitive(code))
        }

    private fun actionCapability(action: String, available: Boolean, code: String): JsonObject =
        buildJsonObject {
            put("action", JsonPrimitive(action))
            put("available", JsonPrimitive(available))
            put("code", JsonPrimitive(code))
        }

    private fun fallbackTargetCapability(target: String, code: String): JsonObject =
        targetCapability(target, readable = false, writable = false, code = code)
}
