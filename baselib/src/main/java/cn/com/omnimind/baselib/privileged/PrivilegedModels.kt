package cn.com.omnimind.baselib.privileged

import kotlinx.serialization.Serializable

@Serializable
enum class PrivilegedSource { NONE, SHIZUKU, SU }

@Serializable
enum class PrivilegedAuthority { NONE, ADB, ROOT }

@Serializable
enum class PrivilegedStatusCode(val wireValue: String) {
    NOT_SELECTED("not_selected"), SHIZUKU_NOT_INSTALLED("shizuku_not_installed"),
    SHIZUKU_NOT_RUNNING("shizuku_not_running"), SHIZUKU_BINDER_DEAD("shizuku_binder_dead"),
    SHIZUKU_PERMISSION_DENIED("shizuku_permission_denied"), SU_NOT_AUTHORIZED("su_not_authorized"),
    SU_DENIED("su_denied"), SU_UNAVAILABLE("su_unavailable"),
    AUTHORITY_UNEXPECTED("authority_unexpected"), GRANTED("granted")
}

data class ShizukuDetail(
    val installed: Boolean, val running: Boolean, val binderReady: Boolean,
    val serviceBound: Boolean, val permissionGranted: Boolean, val version: Int?
) {
    fun toMap() = linkedMapOf<String, Any?>(
        "installed" to installed, "running" to running, "binderReady" to binderReady,
        "serviceBound" to serviceBound, "permissionGranted" to permissionGranted, "version" to version
    )
}

data class PrivilegedStatus(
    val selected: PrivilegedSource,
    val code: PrivilegedStatusCode,
    val authority: PrivilegedAuthority = PrivilegedAuthority.NONE,
    val uid: Int? = null,
    val message: String = "",
    val availableActions: List<String> = emptyList(),
    val shizuku: ShizukuDetail? = null,
    val suBinaryPath: String? = null,
) {
    val granted: Boolean get() = code == PrivilegedStatusCode.GRANTED
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "selected" to selected.name.lowercase(), "granted" to granted,
        "authority" to authority.name.lowercase(), "uid" to uid,
        "code" to code.wireValue, "message" to message,
        "availableActions" to availableActions,
        "shizuku" to shizuku?.toMap(),
        "su" to suBinaryPath?.let { mapOf("binaryPath" to it) }
    )
}

@Serializable
data class PrivilegedRequest(
    val requestId: String, val action: String,
    val arguments: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false, val command: String? = null,
    val timeoutSeconds: Int? = null, val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(), val sessionId: String? = null,
)

@Serializable
data class PrivilegedResult(
    val requestId: String, val action: String, val success: Boolean,
    val code: String, val message: String,
    val authority: PrivilegedAuthority = PrivilegedAuthority.NONE,
    val source: PrivilegedSource = PrivilegedSource.NONE,
    val output: String = "", val stdout: String = "", val stderr: String = "",
    val transcript: String = "", val exitCode: Int? = null,
    val requiresConfirmation: Boolean = false,
    val availableActions: List<String> = emptyList(),
    val data: Map<String, String> = emptyMap(), val command: String? = null,
    val timeoutSeconds: Int? = null, val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(), val sessionId: String? = null,
    val blockedByPolicy: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "requestId" to requestId, "action" to action, "success" to success,
        "code" to code, "message" to message, "authority" to authority.name.lowercase(),
        "source" to source.name.lowercase(), "output" to output, "stdout" to stdout,
        "stderr" to stderr, "transcript" to transcript, "exitCode" to exitCode,
        "requiresConfirmation" to requiresConfirmation, "availableActions" to availableActions,
        "data" to data, "command" to command, "timeoutSeconds" to timeoutSeconds,
        "workingDirectory" to workingDirectory, "environment" to environment,
        "sessionId" to sessionId, "blockedByPolicy" to blockedByPolicy
    )
}
