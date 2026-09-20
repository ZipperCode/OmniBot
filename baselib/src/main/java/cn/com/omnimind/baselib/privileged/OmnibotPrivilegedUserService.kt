package cn.com.omnimind.baselib.privileged

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import kotlinx.serialization.json.Json

@Keep
class OmnibotPrivilegedUserService() : IOmnibotPrivilegedUserService.Stub() {

    override fun probeIdentity(): String {
        return runCatching {
            val launched = StandardPrivilegedShellLauncher().launch(PrivilegedSource.SHIZUKU)
            PrivilegedProcessCleanup.closeAndDestroy(launched.process)
            "${launched.uid}|${launched.authority.name}"
        }.getOrElse { "ERROR:authority_unexpected" }
    }

    override fun closeSessions(): Boolean = PrivilegedShellSessionManager.shutdown()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Suppress("unused")
    @Keep
    constructor(context: Context) : this() {
        Log.d(TAG, "Privileged user service created with context: $context")
    }

    override fun execute(requestJson: String?): String {
        val result = runCatching {
            val request = json.decodeFromString(PrivilegedRequest.serializer(), requestJson.orEmpty())
            PrivilegedCommandExecutor(
                PrivilegedSource.SHIZUKU,
                StandardPrivilegedShellLauncher()
            ).execute(request)
        }.getOrElse { error ->
            PrivilegedResult(
                requestId = "",
                action = "",
                success = false,
                code = "service_error",
                message = error.message ?: "Privileged service failed.",
                authority = PrivilegedAuthority.NONE,
                source = PrivilegedSource.SHIZUKU
            )
        }
        return json.encodeToString(PrivilegedResult.serializer(), result)
    }

    override fun destroy() {
        Log.i(TAG, "Privileged user service destroy requested")
        runCatching { PrivilegedShellSessionManager.shutdown() }
        System.exit(0)
    }

    private companion object {
        private const val TAG = "OmniPrivilegedSvc"
    }
}
