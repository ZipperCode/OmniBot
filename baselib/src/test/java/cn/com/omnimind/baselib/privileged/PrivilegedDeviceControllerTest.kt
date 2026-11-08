package cn.com.omnimind.baselib.privileged

import android.app.UiModeManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.content.pm.PackageManager
import android.provider.Settings
import org.robolectric.Shadows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivilegedDeviceControllerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun settingsReadsAndWritesUseTheSameAndroidUserAndQuoteValues() = runBlocking {
        val calls = mutableListOf<String>()
        var value = "old"
        val expected = "a'; touch /data/local/tmp/never; #"
        val controller = PrivilegedDeviceController(context, 10)
        val result = controller.execute(
            PrivilegedRequest("settings", PrivilegedActionPolicy.ACTION_SETTINGS_PUT,
                arguments = mapOf("namespace" to "secure", "key" to "test_setting", "value" to expected), userId = 10),
            PrivilegedBackend.ROOT, { true },
        ) { command ->
            calls += command
            if (command.contains("'put'")) value = expected
            PrivilegedResult("settings", "", true, "ok", "", stdout = "$value\n")
        }
        assertTrue(result.success)
        assertTrue(calls.all { it.startsWith("'settings' '--user' '10'") })
        assertEquals(1, calls.count { it.contains("'put'") })
        assertEquals("'settings' '--user' '10' 'put' 'secure' 'test_setting' 'a'\"'\"'; touch /data/local/tmp/never; #'", calls[1])
        assertEquals(JsonPrimitive("app_user"), result.observation!!["scope"])
    }

    @Test fun settingsRejectCrossUserArgumentsBeforeRunningAnything() = runBlocking {
        val result = PrivilegedDeviceController(context, 10).execute(
            PrivilegedRequest("settings", PrivilegedActionPolicy.ACTION_SETTINGS_GET,
                mapOf("namespace" to "system", "key" to "test", "userId" to "0")),
            PrivilegedBackend.ROOT, { true },
        ) { error("Must not run cross-user command") }
        assertEquals("invalid_arguments", result.code)
    }

    @Test fun mobileDataCannotUseAdbViaTheGenericStateAction() = runBlocking {
        val result = PrivilegedDeviceController(context, 0).execute(
            PrivilegedRequest("mobile", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
                mapOf("target" to "mobile_data", "enabled" to "true")),
            PrivilegedBackend.ADB, { true },
        ) { error("Must not run") }
        assertEquals("unsupported_action", result.code)
    }

    @Test fun missingNfcHardwareDoesNotRunCommands() = runBlocking {
        val result = PrivilegedDeviceController(context, 0).execute(
            PrivilegedRequest("nfc", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
                mapOf("target" to "nfc", "enabled" to "true")),
            PrivilegedBackend.ROOT, { true },
        ) { error("Must not run") }
        assertEquals("unsupported_action", result.code)
    }

    @Test fun darkModeAcceptsAospHelpExitAndVerifiesOneWrite() = runBlocking {
        val uiMode = context.getSystemService(UiModeManager::class.java)!!
        uiMode.nightMode = UiModeManager.MODE_NIGHT_NO
        val commands = mutableListOf<String>()
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) { command ->
            commands += command
            when (command) {
                "'cmd' 'uimode' 'help'" -> PrivilegedResult(
                    "help", "device_control.command", false, "command_failed", "Help exited with -1.",
                    stdout = "UiModeManager service (uimode) commands:\n  night [yes|no|auto]\n    Set or read night mode.\n", exitCode = 255,
                )
                "'cmd' 'uimode' 'night' 'yes'" -> {
                    uiMode.nightMode = UiModeManager.MODE_NIGHT_YES
                    PrivilegedResult("write", "device_control.command", true, "ok", "", exitCode = 0)
                }
                else -> error("Unexpected command: $command")
            }
        }
        assertTrue(result.message, result.success)
        assertEquals(JsonPrimitive(true), result.observation!!["verified"])
        assertEquals(listOf("'cmd' 'uimode' 'help'", "'cmd' 'uimode' 'night' 'yes'"), commands)
    }

    @Test fun failedHelpPreservesDiagnosticsAndDoesNotWriteDeviceState() = runBlocking {
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        val commands = mutableListOf<String>()
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) { command ->
            commands += command
            PrivilegedResult("help", "device_control.command", false, "root_denied", "Root access denied.",
                stderr = "su: permission denied", exitCode = 1, provider = PrivilegedProvider.ROOT)
        }
        assertFalse(result.success)
        assertEquals("root_denied", result.code)
        assertEquals("su: permission denied", result.stderr)
        assertEquals(1, result.exitCode)
        assertEquals(request.requestId, result.requestId)
        assertEquals(request.action, result.action)
        assertEquals(listOf("'cmd' 'uimode' 'help'"), commands)
    }

    @Test fun acceptingHelpDoesNotTurnFailedStateMutationIntoSuccess() = runBlocking {
        context.getSystemService(UiModeManager::class.java)!!.nightMode = UiModeManager.MODE_NIGHT_NO
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        var writes = 0
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) { command ->
            if (command.endsWith("'help'")) {
                PrivilegedResult("help", "device_control.command", false, "command_failed", "",
                    stdout = "UiModeManager commands:\n  night [yes|no]\n", exitCode = 255)
            } else {
                writes++
                PrivilegedResult("write", "device_control.command", false, "command_failed", "Write refused.", exitCode = 255)
            }
        }
        assertFalse(result.success)
        assertEquals("command_failed", result.code)
        assertEquals(JsonPrimitive(false), result.observation!!["commandAccepted"])
        assertEquals(1, writes)
    }

    @Test fun bluetoothParserDoesNotInferStateFromAnUnrelatedLine() {
        assertNull(PrivilegedDeviceController.bluetoothState("last_state: ON\nstate: TURNING_ON\n"))
        assertEquals(true, PrivilegedDeviceController.bluetoothState("  state: ON\n"))
        assertEquals(false, PrivilegedDeviceController.bluetoothState("state: 10\n"))
    }

    @Test fun volumePercentageHonorsDeviceMinimumAndMaximum() {
        assertEquals(1, PrivilegedDisplaySoundControls.volumeLevel(0, 1, 7))
        assertEquals(4, PrivilegedDisplaySoundControls.volumeLevel(50, 1, 7))
        assertEquals(7, PrivilegedDisplaySoundControls.volumeLevel(100, 1, 7))
        assertThrows(IllegalArgumentException::class.java) { PrivilegedDisplaySoundControls.volumeLevel(101, 0, 7) }
    }

    @Test fun flashlightWithoutHardwareFeatureReturnsUnsupported() = runBlocking {
        val request = PrivilegedRequest("torch", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "flashlight", "enabled" to "true"))
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) {
            error("Must not run any command")
        }
        assertEquals("unsupported_action", result.code)
    }

    @Test fun unknownStateQueryReturnsStateUnavailable() = runBlocking {
        Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 2)
        val request = PrivilegedRequest("unknown-query", PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE,
            mapOf("target" to "auto_rotate"))
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) {
            error("未知设置状态不得推测或执行命令")
        }
        assertFalse(result.success)
        assertEquals("state_unavailable", result.code)
    }

    @Test fun truncatedHelperOutputIsRejected() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val raw = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = true,
            code = "ok",
            message = "",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.get_state\",\"success\":true,\"code\":\"ok\",\"message\":\"ok\"}\n",
            data = mapOf("truncated" to "true")
        )
        val result = client.parseHelperOutput(raw, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_GET_STATE, isMutation = false)
        assertFalse(result.success)
        assertEquals("capability_probe_incomplete", result.code)

        val mutateResult = client.parseHelperOutput(raw, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true)
        assertFalse(mutateResult.success)
        assertEquals("state_change_unconfirmed", mutateResult.code)
    }

    @Test fun helperOutputWithoutMarkerOrMultipleMarkersFails() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val noMarker = PrivilegedResult(
            requestId = "req-1", action = "device_control.command", success = true, code = "ok", message = "", stdout = "Some random crash or log\n"
        )
        val resNoMarker = client.parseHelperOutput(noMarker, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true)
        assertFalse(resNoMarker.success)
        assertEquals("state_change_unconfirmed", resNoMarker.code)

        val multiMarker = PrivilegedResult(
            requestId = "req-1", action = "device_control.command", success = true, code = "ok", message = "",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\"}\n" +
                "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\"}\n"
        )
        val resMulti = client.parseHelperOutput(multiMarker, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true)
        assertFalse(resMulti.success)
        assertEquals("state_change_unconfirmed", resMulti.code)
    }

    @Test fun helperUntrustedUidIsRejected() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val raw = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = true,
            code = "ok",
            message = "",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"ok\",\"data\":{\"uid\":\"10001\"}}\n"
        )
        val result = client.parseHelperOutput(raw, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true)
        assertFalse(result.success)
        assertEquals("permission_denied", result.code)
    }


    @Test fun ringerModeRejectsInvalidMode() = runBlocking {
        val request = PrivilegedRequest("ringer", PrivilegedActionPolicy.ACTION_DEVICE_SET_RINGER_MODE,
            mapOf("mode" to "invalid_mode"))
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { true }) {
            error("Must not run")
        }
        assertEquals("invalid_arguments", result.code)
    }

    @Test fun privilegeRevocationAbortsBeforeWrite() = runBlocking {
        var allowed = true
        val request = PrivilegedRequest("dark-mode", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "dark_mode", "enabled" to "true"))
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ROOT, { allowed }) { command ->
            if (command.endsWith("'help'")) {
                allowed = false
                PrivilegedResult("help", "device_control.command", false, "command_failed", "",
                    stdout = "UiModeManager commands:\n  night [yes|no]\n", exitCode = 255)
            } else {
                error("Must not attempt write after revocation")
            }
        }
        assertFalse(result.success)
        assertEquals("privileged_access_changed", result.code)
    }

    @Test fun capabilitiesReportsDeniedHostCameraAndRingerPolicy() = runBlocking {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.CAMERA)
        Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        val request = PrivilegedRequest("caps", PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES)
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ADB, { true }) { command ->
            if (command.contains("capabilities")) {
                PrivilegedResult(
                    requestId = "caps",
                    action = "device_control.command",
                    success = true,
                    code = "ok",
                    message = "",
                    stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"caps\",\"action\":\"device_control.capabilities\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"observation\":{\"value\":{\"targets\":[{\"target\":\"night_light\",\"readable\":true,\"writable\":true,\"code\":\"ok\"},{\"target\":\"hotspot\",\"readable\":false,\"writable\":false,\"code\":\"unsupported_action\"}]}},\"data\":{\"uid\":\"2000\"}}\n"
                )
            } else if (command.endsWith("'help'")) {
                val service = command.split(" ").getOrNull(1)?.replace("'", "").orEmpty()
                val op = when (service) {
                    "wifi" -> "set-wifi-enabled"
                    "bluetooth_manager" -> "enable"
                    "location" -> "set-location-enabled"
                    "nfc" -> "enable-nfc"
                    "power" -> "set-mode"
                    "uimode" -> "night"
                    "connectivity" -> "airplane-mode"
                    "display" -> "set-brightness"
                    "notification" -> "set_dnd"
                    else -> "help"
                }
                PrivilegedResult(
                    requestId = "caps-help",
                    action = "device_control.command",
                    success = true,
                    code = "ok",
                    message = "",
                    stdout = "$service commands:\n  $op [options]\n",
                    exitCode = 0
                )
            } else {
                PrivilegedResult(requestId = "caps-cmd", action = "device_control.command", success = true, code = "ok", message = "", stdout = "ok\n")
            }
        }
        assertTrue(result.success)
        val obs = result.observation!!["value"] as JsonObject
        val targets = obs["targets"] as JsonArray
        val actions = obs["actions"] as JsonArray
        val flashlight = targets.first { (it as JsonObject)["target"] == JsonPrimitive("flashlight") } as JsonObject
        assertEquals(JsonPrimitive(false), flashlight["writable"])
        assertEquals(JsonPrimitive("permission_denied"), flashlight["code"])
        val ringer = actions.first { (it as JsonObject)["action"] == JsonPrimitive("device_control.set_ringer_mode") } as JsonObject
        assertEquals(JsonPrimitive(false), ringer["available"])
        assertEquals(JsonPrimitive("permission_denied"), ringer["code"])
    }

    @Test fun capabilitiesUnderBackendNoneDoesNotRunCommands() = runBlocking {
        val request = PrivilegedRequest("caps", PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES)
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.NONE, { false }) {
            error("Must not invoke command when backend is NONE")
        }
        assertTrue(result.success)
        val obs = result.observation!!["value"] as JsonObject
        val targets = obs["targets"] as JsonArray
        val nlTarget = targets.map { it as JsonObject }.first { (it["target"] as JsonPrimitive).content == "night_light" }
        assertEquals("permission_denied", (nlTarget["code"] as JsonPrimitive).content)
    }

    @Test fun helperUnconfirmedPreservesAcceptedTrueAndBefore() = runBlocking {
        val client = PrivilegedDeviceApiClient(context, 0)
        val request = PrivilegedRequest("nl-set", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "night_light", "enabled" to "true"))
        val execution = DeviceActionExecution(request, 0, {
            PrivilegedResult(
                requestId = "nl-set",
                action = "device_control.command",
                success = true,
                code = "ok",
                message = "",
                stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"nl-set\",\"action\":\"device_control.set_state\",\"success\":false,\"code\":\"state_change_unconfirmed\",\"message\":\"The request was submitted, but target state is unconfirmed.\",\"observation\":{\"before\":false,\"after\":null,\"expected\":true,\"commandAccepted\":true,\"verified\":false,\"changed\":false,\"userId\":0,\"scope\":\"device\"},\"data\":{\"uid\":\"2000\"}}\n"
            )
        }, { true })
        val result = client.execute(execution, "night_light", enabled = true)
        assertFalse(result.success)
        assertEquals("state_change_unconfirmed", result.code)
        val obs = result.observation!!
        assertEquals(JsonPrimitive(true), obs["commandAccepted"])
        assertEquals(JsonPrimitive(false), obs["before"])
        assertEquals(JsonPrimitive(false), obs["verified"])
    }

    @Test fun identicalDoubleMarkerIsRejectedWithoutDistinct() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val singleJson = "{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"data\":{\"uid\":\"0\"}}"
        val identicalDouble = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = true,
            code = "ok",
            message = "",
            stdout = "OMNI_DEVICE_API_RESULT:$singleJson\nOMNI_DEVICE_API_RESULT:$singleJson\n"
        )
        val res = client.parseHelperOutput(identicalDouble, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true)
        assertFalse(res.success)
        assertEquals("state_change_unconfirmed", res.code)
    }

    @Test fun ringerCapabilityReflectsPolicyAndVolumeFixedBoundary() = runBlocking {
        val request = PrivilegedRequest("caps", PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES)
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ADB, { true }) {
            PrivilegedResult(
                requestId = "helper-caps",
                action = "device_control.command",
                success = true,
                code = "ok",
                message = "",
                stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"caps\",\"action\":\"device_control.capabilities\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"observation\":{\"value\":{\"targets\":[]}},\"data\":{\"uid\":\"2000\"}}\n"
            )
        }
        assertTrue(result.success)
        val obs = result.observation!!["value"] as JsonObject
        val actions = obs["actions"] as JsonArray
        val ringerAction = actions.map { it as JsonObject }.first { (it["action"] as JsonPrimitive).content == "device_control.set_ringer_mode" }
        val code = (ringerAction["code"] as JsonPrimitive).content
        assertTrue(code == "permission_denied" || code == "unsupported_action")
        assertEquals(JsonPrimitive(false), ringerAction["available"])
    }

    @Test fun torchSecurityExceptionReturnsPermissionDeniedWithSettingsEntry() = runBlocking {
        val torch = PrivilegedTorchController(context)
        val request = PrivilegedRequest("torch", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "flashlight", "enabled" to "true"))
        val execution = DeviceActionExecution(request, 0, { error("Must not run") }, { true })
        val result = torch.execute(execution, enabled = true)
        assertEquals("permission_denied", result.code)
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, result.data["settingsAction"])
        assertEquals(context.packageName, result.data["packageName"])
    }

    @Test fun hotspotSdkGuardRejectsBelowApi36() = runBlocking {
        val client = PrivilegedDeviceApiClient(context, 0)
        val request = PrivilegedRequest("hs-set", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            mapOf("target" to "hotspot", "enabled" to "true"))
        val execution = DeviceActionExecution(request, 0, { error("Must not run") }, { true })
        val result = client.execute(execution, "hotspot", enabled = true)
        assertEquals("unsupported_action", result.code)
    }

    @Test fun helperUidFieldMismatchIsRejected() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val raw = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = true,
            code = "ok",
            message = "",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"uid\":0,\"data\":{\"uid\":\"2000\"}}\n"
        )
        val result = client.parseHelperOutput(raw, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true)
        assertFalse(result.success)
        assertEquals("permission_denied", result.code)
    }

    @Test fun helperBackendIdentityMismatchIsRejected() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val rawUid2000 = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = true,
            code = "ok",
            message = "",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"data\":{\"uid\":\"2000\"}}\n"
        )
        // Root 后端必须拒绝 UID 2000
        val resRoot = client.parseHelperOutput(
            rawResult = rawUid2000,
            expectedRequestId = "req-1",
            expectedAction = PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            isMutation = true,
            expectedBackend = PrivilegedBackend.ROOT,
        )
        assertFalse(resRoot.success)
        assertEquals("permission_denied", resRoot.code)

        val rawUid0 = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = true,
            code = "ok",
            message = "",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"data\":{\"uid\":\"0\"}}\n"
        )
        // ADB 后端必须拒绝 UID 0
        val resAdb = client.parseHelperOutput(
            rawResult = rawUid0,
            expectedRequestId = "req-1",
            expectedAction = PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE,
            isMutation = true,
            expectedBackend = PrivilegedBackend.ADB,
        )
        assertFalse(resAdb.success)
        assertEquals("permission_denied", resAdb.code)
    }
    @Test fun helperFailureNeverReportsVerifiedSuccess() {
        val client = PrivilegedDeviceApiClient(context, 0)
        val rawFailed = PrivilegedResult(
            requestId = "req-1",
            action = "device_control.command",
            success = false,
            code = "timeout",
            message = "Process timed out.",
            stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"req-1\",\"action\":\"device_control.set_state\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"observation\":{\"before\":false,\"after\":true,\"expected\":true,\"commandAccepted\":true,\"verified\":true,\"changed\":true,\"userId\":0,\"scope\":\"device\"},\"data\":{\"uid\":\"0\"}}\n"
        )
        val res = client.parseHelperOutput(rawFailed, "req-1", PrivilegedActionPolicy.ACTION_DEVICE_SET_STATE, isMutation = true, expectedBackend = PrivilegedBackend.ROOT)
        assertFalse(res.success)
        assertEquals("state_change_unconfirmed", res.code)
        val obs = res.observation!!
        assertEquals(JsonPrimitive(false), obs["verified"])
        assertEquals(JsonPrimitive(true), obs["commandAccepted"])
    }

    @Test fun capabilitiesReflectsMissingCommandHelpOrUnknownState() = runBlocking {
        val request = PrivilegedRequest("caps", PrivilegedActionPolicy.ACTION_DEVICE_CAPABILITIES)
        val result = PrivilegedDeviceController(context, 0).execute(request, PrivilegedBackend.ADB, { true }) { command ->
            if (command.contains("capabilities")) {
                PrivilegedResult(
                    requestId = "caps", action = "device_control.command", success = true, code = "ok", message = "",
                    stdout = "OMNI_DEVICE_API_RESULT:{\"requestId\":\"caps\",\"action\":\"device_control.capabilities\",\"success\":true,\"code\":\"ok\",\"message\":\"\",\"observation\":{\"value\":{\"targets\":[]}},\"data\":{\"uid\":\"2000\"}}\n"
                )
            } else {
                PrivilegedResult(requestId = "cmd-fail", action = "device_control.command", success = false, code = "command_failed", message = "Service missing", exitCode = -1)
            }
        }
        assertTrue(result.success)
        val obs = result.observation!!["value"] as JsonObject
        val actions = obs["actions"] as JsonArray
        val brightness = actions.map { it as JsonObject }.first { (it["action"] as JsonPrimitive).content == "device_control.set_brightness" }
        assertEquals(JsonPrimitive(false), brightness["available"])
        assertEquals("unsupported_action", (brightness["code"] as JsonPrimitive).content)
    }
}
