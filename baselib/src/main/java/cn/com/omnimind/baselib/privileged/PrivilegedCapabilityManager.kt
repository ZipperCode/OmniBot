package cn.com.omnimind.baselib.privileged

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PrivilegedCapabilityManager private constructor(context: Context) {
    private val app = context.applicationContext
    private val admission = Mutex()
    private val authorizationLock = Mutex()
    private val launcher = StandardPrivilegedShellLauncher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionSources = ConcurrentHashMap<String, PrivilegedSource>()
    @Volatile private var grant: Grant? = null
    @Volatile private var service: IOmnibotPrivilegedUserService? = null
    @Volatile private var pending: CompletableDeferred<IOmnibotPrivilegedUserService?>? = null
    @Volatile private var binderDead = false
    @Volatile private var authorizationFailure: PrivilegedStatusCode? = null
    private data class Grant(val source: PrivilegedSource, val authority: PrivilegedAuthority, val uid: Int, val path: String?)

    private val args = Shizuku.UserServiceArgs(ComponentName(app, OmnibotPrivilegedUserService::class.java))
        .daemon(false).processNameSuffix("omnibot_privileged").tag("omnibot-privileged-agent").version(4)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let(IOmnibotPrivilegedUserService.Stub::asInterface)
            pending?.takeIf { !it.isCompleted }?.complete(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) = clearService()
        override fun onBindingDied(name: ComponentName?) = clearService()
        override fun onNullBinding(name: ComponentName?) = clearService()
        private fun clearService() { service = null; pending?.takeIf { !it.isCompleted }?.complete(null) }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { binderDead = false }
            Shizuku.addBinderDeadListener { binderDead = true; service = null; grant = null }
        }
    }

    fun selectedSource(): PrivilegedSource = runCatching {
        PrivilegedSource.valueOf(MMKV.defaultMMKV()?.decodeString(SOURCE_KEY, "none").orEmpty().uppercase())
    }.getOrDefault(PrivilegedSource.NONE)

    /** 被动读取不启动进程，也不检查 su 文件。 */
    fun getStatus(): PrivilegedStatus {
        val selected = selectedSource()
        grant?.takeIf { it.source == selected }?.let {
            return PrivilegedStatus(selected, PrivilegedStatusCode.GRANTED, it.authority, it.uid,
                "Privileged source is authorized.", PrivilegedActionPolicy.visibleAgentActions(it.authority),
                if (selected == PrivilegedSource.SHIZUKU) shizukuDetail() else null, it.path)
        }
        return when (selected) {
            PrivilegedSource.NONE -> PrivilegedStatus(selected, PrivilegedStatusCode.NOT_SELECTED, message = "No privileged source selected.")
            PrivilegedSource.SU -> {
                val code = authorizationFailure ?: PrivilegedStatusCode.SU_NOT_AUTHORIZED
                PrivilegedStatus(selected, code, message = code.wireValue)
            }
            PrivilegedSource.SHIZUKU -> {
                val detail = shizukuDetail()
                val code = when {
                    authorizationFailure == PrivilegedStatusCode.AUTHORITY_UNEXPECTED -> PrivilegedStatusCode.AUTHORITY_UNEXPECTED
                    !detail.installed -> PrivilegedStatusCode.SHIZUKU_NOT_INSTALLED
                    !detail.running -> if (binderDead) PrivilegedStatusCode.SHIZUKU_BINDER_DEAD else PrivilegedStatusCode.SHIZUKU_NOT_RUNNING
                    else -> PrivilegedStatusCode.SHIZUKU_PERMISSION_DENIED
                }
                PrivilegedStatus(selected, code, message = code.wireValue, shizuku = detail)
            }
        }
    }

    suspend fun setSource(value: String): PrivilegedStatus = admission.withLock {
        val source = runCatching { PrivilegedSource.valueOf(value.trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Unsupported privileged source: $value") }
        stopSessionsForSource(selectedSource())
        grant = null
        authorizationFailure = null
        MMKV.defaultMMKV()?.encode(SOURCE_KEY, source.name.lowercase())
        getStatus()
    }

    suspend fun authorize(): PrivilegedStatus = authorizationLock.withLock {
        admission.withLock {
            grant = null
            authorizationFailure = null
            when (selectedSource()) {
                PrivilegedSource.NONE -> Unit
                PrivilegedSource.SU -> authorizeSu()
                PrivilegedSource.SHIZUKU -> authorizeShizuku()
            }
            getStatus()
        }
    }

    fun openSelectedSourceApp(): Boolean {
        if (selectedSource() != PrivilegedSource.SHIZUKU) return false
        return runCatching {
            val intent = app.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); app.startActivity(intent); true
        }.getOrDefault(false)
    }

    suspend fun executeAgentAction(action: String, arguments: Map<String, String>, requiresConfirmation: Boolean = false) = execute(
        PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.normalizeAction(action), arguments, requiresConfirmation)
    )
    suspend fun executeRawShell(command: String, timeoutSeconds: Int? = null, workingDirectory: String? = null, environment: Map<String, String> = emptyMap(), confirmed: Boolean = false) = execute(
        PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SHELL_EXEC, confirmation(confirmed), command = command, timeoutSeconds = timeoutSeconds, workingDirectory = workingDirectory, environment = environment)
    )
    suspend fun startPrivilegedSession(sessionId: String? = null, sessionName: String? = null, workingDirectory: String? = null, environment: Map<String, String> = emptyMap(), confirmed: Boolean = false): PrivilegedResult {
        val values = confirmation(confirmed).toMutableMap(); sessionName?.let { values["sessionName"] = it }
        return execute(PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_START, values, workingDirectory = workingDirectory, environment = environment, sessionId = sessionId))
    }
    suspend fun execPrivilegedSession(sessionId: String, command: String, timeoutSeconds: Int? = null, workingDirectory: String? = null, environment: Map<String, String> = emptyMap(), confirmed: Boolean = false) = execute(
        PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_EXEC, confirmation(confirmed), command = command, timeoutSeconds = timeoutSeconds, workingDirectory = workingDirectory, environment = environment, sessionId = sessionId)
    )
    suspend fun readPrivilegedSession(sessionId: String, maxChars: Int? = null) = execute(
        PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_READ, maxChars?.let { mapOf("maxChars" to it.toString()) }.orEmpty(), sessionId = sessionId)
    )
    suspend fun stopPrivilegedSession(sessionId: String): PrivilegedResult {
        val request = PrivilegedRequest(UUID.randomUUID().toString(), PrivilegedActionPolicy.ACTION_SESSION_STOP, sessionId = sessionId)
        return admission.withLock {
            val normalizedId = sessionId.trim()
            val source = sessionSources[normalizedId]
                ?: return@withLock failure(request, PrivilegedSource.NONE, "session_not_found", "The privileged shell session does not exist.")
            val result = when (source) {
                PrivilegedSource.NONE -> failure(request, source, "session_not_found", "The privileged shell session does not exist.")
                PrivilegedSource.SU -> runInterruptible(Dispatchers.IO) {
                    PrivilegedCommandExecutor(source, launcher).execute(request)
                }
                PrivilegedSource.SHIZUKU -> executeRemoteTeardown(request)
            }
            if (result.success || result.code == "session_not_found") sessionSources.remove(normalizedId, source)
            result
        }
    }

    private suspend fun execute(request: PrivilegedRequest): PrivilegedResult {
        if (PrivilegedActionPolicy.normalizeAction(request.action) == PrivilegedActionPolicy.ACTION_SESSION_START) {
            // 会话创建和路由登记共用来源准入锁，切源不能越过尚未登记的会话。
            return admission.withLock { executeAdmitted(request, selectedSource()) }
        }
        val admitted = admission.withLock {
            val source = selectedSource()
            val current = grant?.takeIf { it.source == source }
                ?: return@withLock null
            source to current
        } ?: return failure(request, selectedSource(), "permission_required", "Privileged source authorization is required.")
        val (source, current) = admitted
        return executeWithGrant(request, source, current, admissionHeld = false)
    }

    private suspend fun executeAdmitted(request: PrivilegedRequest, source: PrivilegedSource): PrivilegedResult {
        val current = grant?.takeIf { it.source == source }
            ?: return failure(request, source, "permission_required", "Privileged source authorization is required.")
        return executeWithGrant(request, source, current, admissionHeld = true).also { result ->
            if (result.success) result.sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { sessionSources[it] = source }
        }
    }

    private suspend fun executeWithGrant(request: PrivilegedRequest, source: PrivilegedSource, current: Grant, admissionHeld: Boolean): PrivilegedResult {
        val result = when (source) {
            PrivilegedSource.NONE -> failure(request, source, "permission_required", "No privileged source selected.")
            PrivilegedSource.SU -> runInterruptible(Dispatchers.IO) {
                PrivilegedCommandExecutor(source, launcher).execute(request)
            }
            PrivilegedSource.SHIZUKU -> executeRemote(request, current.authority)
        }
        if (result.code in AUTHORIZATION_FAILURES) {
            if (admissionHeld) {
                if (grant == current) grant = null
            } else admission.withLock {
                if (grant == current) grant = null
            }
        }
        return result
    }

    private fun authorizeSu() {
        val launched = runCatching { launcher.launch(PrivilegedSource.SU) }.getOrElse {
            authorizationFailure = when (it) {
                is SuUnavailableException -> PrivilegedStatusCode.SU_UNAVAILABLE
                is SuDeniedException -> PrivilegedStatusCode.SU_DENIED
                is AuthorityUnexpectedException -> PrivilegedStatusCode.AUTHORITY_UNEXPECTED
                else -> PrivilegedStatusCode.SU_DENIED
            }
            return
        }
        grant = Grant(PrivilegedSource.SU, launched.authority, launched.uid, launched.binaryPath)
        PrivilegedProcessCleanup.closeAndDestroy(launched.process)
    }

    private suspend fun authorizeShizuku() {
        var detail = shizukuDetail(); if (!detail.installed || !detail.running) return
        if (!detail.permissionGranted) {
            val result = CompletableDeferred<Int>(); val code = (System.currentTimeMillis() % 100000).toInt()
            val listener = Shizuku.OnRequestPermissionResultListener { id, value -> if (id == code) result.complete(value) }
            withContext(Dispatchers.Main) { Shizuku.addRequestPermissionResultListener(listener); Shizuku.requestPermission(code) }
            withTimeoutOrNull(10_000) { result.await() }
            withContext(Dispatchers.Main) { Shizuku.removeRequestPermissionResultListener(listener) }
            detail = shizukuDetail(); if (!detail.permissionGranted) return
        }
        val remote = ensureBound() ?: return
        val probe = try {
            withContext(Dispatchers.IO) { remote.probeIdentity() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            service = null
            grant = null
            authorizationFailure = PrivilegedStatusCode.SHIZUKU_BINDER_DEAD
            return
        }
        if (probe.startsWith(PROBE_ERROR_PREFIX)) {
            grant = null
            authorizationFailure = PrivilegedStatusCode.AUTHORITY_UNEXPECTED
            return
        }
        val uid = probe.substringBefore('|').toIntOrNull()
        if (uid == null) {
            authorizationFailure = PrivilegedStatusCode.AUTHORITY_UNEXPECTED
            grant = null
            return
        }
        val authority = StandardPrivilegedShellLauncher.authorityForUid(uid)
        if (authority != PrivilegedAuthority.NONE) {
            grant = Grant(PrivilegedSource.SHIZUKU, authority, uid, null)
        } else {
            authorizationFailure = PrivilegedStatusCode.AUTHORITY_UNEXPECTED
        }
    }

    private suspend fun executeRemote(request: PrivilegedRequest, authority: PrivilegedAuthority): PrivilegedResult {
        val remote = ensureBound() ?: return failure(request, PrivilegedSource.SHIZUKU, "service_bind_failed", "Failed to bind Shizuku service.")
        // 分派后异常不得重新绑定或重放请求。
        return try {
            val value = withTimeoutOrNull(remoteTimeout(request)) { withContext(Dispatchers.IO) { remote.execute(json.encodeToString(PrivilegedRequest.serializer(), request)) } }
                ?: return failure(request, PrivilegedSource.SHIZUKU, "service_timeout", "Timed out waiting for Shizuku service.", authority)
            json.decodeFromString(PrivilegedResult.serializer(), value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            service = null
            failure(request, PrivilegedSource.SHIZUKU, "service_call_failed", error.message ?: "Shizuku service call failed.", authority)
        }
    }

    private suspend fun executeRemoteTeardown(request: PrivilegedRequest): PrivilegedResult {
        val remote = service
            ?: return failure(request, PrivilegedSource.SHIZUKU, "service_not_available", "The Shizuku session service is not available.")
        return try {
            val value = withTimeoutOrNull(remoteTimeout(request)) {
                withContext(Dispatchers.IO) { remote.execute(json.encodeToString(PrivilegedRequest.serializer(), request)) }
            } ?: return failure(request, PrivilegedSource.SHIZUKU, "service_timeout", "Timed out waiting for Shizuku service.")
            json.decodeFromString(PrivilegedResult.serializer(), value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            service = null
            failure(request, PrivilegedSource.SHIZUKU, "service_call_failed", error.message ?: "Shizuku service call failed.")
        }
    }

    private suspend fun ensureBound(): IOmnibotPrivilegedUserService? {
        service?.let { return it }
        val deferred = pending?.takeIf { !it.isCompleted } ?: CompletableDeferred<IOmnibotPrivilegedUserService?>().also { pending = it }
        withContext(Dispatchers.Main) { runCatching { Shizuku.bindUserService(args, connection) }.onFailure { deferred.complete(null) } }
        return try { awaitShizukuUserService(deferred) } finally { if (pending === deferred) pending = null }
    }

    private suspend fun stopSessionsForSource(source: PrivilegedSource): Boolean {
        return when (source) {
            PrivilegedSource.NONE -> true
            PrivilegedSource.SU -> PrivilegedShellSessionManager.shutdown(source)
            PrivilegedSource.SHIZUKU -> {
                val remote = service
                val stopped = if (remote == null) true else try {
                    withContext(Dispatchers.IO) { remote.closeSessions() }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                try {
                    withContext(Dispatchers.Main) { Shizuku.unbindUserService(args, connection, true) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // 解绑失败不恢复旧路由；远端进程是否残留由 stopped 如实表达。
                }
                service = null
                stopped
            }
        }.also { sessionSources.entries.removeIf { it.value == source } }
    }

    private fun shizukuDetail(): ShizukuDetail {
        val ready = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val installed = runCatching { app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false) || ready
        return ShizukuDetail(installed, ready, ready, service != null,
            ready && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
            if (ready) runCatching { Shizuku.getVersion() }.getOrNull() else null)
    }
    private fun failure(request: PrivilegedRequest, source: PrivilegedSource, code: String, message: String, authority: PrivilegedAuthority = PrivilegedAuthority.NONE) =
        PrivilegedResult(request.requestId, request.action, false, code, message, authority, source)
    private fun confirmation(value: Boolean) = if (value) mapOf("confirmed" to "true") else emptyMap()
    private fun remoteTimeout(request: PrivilegedRequest) = ((request.timeoutSeconds?.coerceIn(5, 600) ?: 60) + 8L) * 1000L

    companion object {
        private const val SOURCE_KEY = "privileged_source_v1"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val PROBE_ERROR_PREFIX = "ERROR:"
        private val AUTHORIZATION_FAILURES = setOf("authority_unexpected", "permission_denied")
        @Volatile private var instance: PrivilegedCapabilityManager? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: PrivilegedCapabilityManager(context).also { instance = it } }
    }
}
