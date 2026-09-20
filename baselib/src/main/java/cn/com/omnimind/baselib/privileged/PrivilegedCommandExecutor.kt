package cn.com.omnimind.baselib.privileged

import cn.com.omnimind.baselib.util.OmniLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.thread

internal class PrivilegedCommandExecutor(
    private val source: PrivilegedSource,
    private val launcher: PrivilegedShellLauncher,
) {

    private val allowedSettingsNamespaces = setOf("system", "secure", "global")
    private val allowedDumpsysServices = setOf(
        "activity",
        "alarm",
        "battery",
        "connectivity",
        "deviceidle",
        "input",
        "input_method",
        "notification",
        "package",
        "power",
        "usagestats",
        "wifi",
        "window"
    )
    private val allowedLogcatBuffers = setOf("main", "system", "crash", "events", "radio", "kernel")
    private val allowedAppOpsModes = setOf("allow", "ignore", "deny", "default", "foreground")
    private val envKeyPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun execute(request: PrivilegedRequest): PrivilegedResult {
        val action = PrivilegedActionPolicy.normalizeAction(request.action)
        if (action == PrivilegedActionPolicy.ACTION_SESSION_EXEC ||
            action == PrivilegedActionPolicy.ACTION_SESSION_READ ||
            action == PrivilegedActionPolicy.ACTION_SESSION_STOP
        ) {
            return executeExistingSession(request, action)
        }
        val launched = try {
            launcher.launch(source)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SuDeniedException) {
            return failure(request, PrivilegedAuthority.NONE, "permission_denied", error.message ?: "Privileged source authorization was denied.")
        } catch (error: AuthorityUnexpectedException) {
            return failure(request, PrivilegedAuthority.NONE, "authority_unexpected", error.message ?: "Privileged shell returned an unexpected identity.")
        } catch (error: Exception) {
            return failure(request, PrivilegedAuthority.NONE, "process_start_failed", error.message ?: "Failed to start privileged shell.")
        }
        val authority = launched.authority
        val arguments = request.arguments

        if (!PrivilegedActionPolicy.isSupported(action, authority, includeInternal = true, arguments = arguments)) {
            PrivilegedProcessCleanup.closeAndDestroy(launched.process)
            return failure(
                request = request,
                authority = authority,
                code = "unsupported_action",
                message = "Action is not available for the measured authority."
            ).also { audit(request, it) }
        }

        if (action == PrivilegedActionPolicy.ACTION_SHELL_EXEC) {
            PrivilegedActionPolicy.blockedCommandReason(request.command)?.let { reason ->
                PrivilegedProcessCleanup.closeAndDestroy(launched.process)
                return failure(
                    request = request,
                    authority = authority,
                    code = "blocked_by_policy",
                    message = reason,
                    blockedByPolicy = true
                ).also { audit(request, it) }
            }
        }

        if ((request.requiresConfirmation || PrivilegedActionPolicy.requiresConfirmation(action)) &&
            !isExplicitlyConfirmed(arguments)
        ) {
            PrivilegedProcessCleanup.closeAndDestroy(launched.process)
            return failure(
                request = request,
                authority = authority,
                code = "confirmation_required",
                message = "This privileged action requires explicit confirmation.",
                requiresConfirmation = true
            ).also { audit(request, it) }
        }

        val result = when (action) {
            PrivilegedActionPolicy.ACTION_SHELL_EXEC -> executeRawShell(request, authority, launched)
            PrivilegedActionPolicy.ACTION_SESSION_START ->
                PrivilegedShellSessionManager.startSession(request, authority, source, launched.process)

            else -> executeTypedAction(request, authority, action, arguments, launched)
        }
        if (action != PrivilegedActionPolicy.ACTION_SESSION_START) PrivilegedProcessCleanup.closeAndDestroy(launched.process)
        audit(request, result)
        return result
    }

    private fun executeTypedAction(
        request: PrivilegedRequest,
        authority: PrivilegedAuthority,
        action: String,
        arguments: Map<String, String>,
        launched: LaunchedPrivilegedShell,
    ): PrivilegedResult {
        val command = runCatching {
            buildCommand(action, arguments, authority)
        }.getOrElse { error ->
            return failure(
                request = request,
                authority = authority,
                code = "invalid_arguments",
                message = error.message ?: "Invalid privileged action arguments."
            )
        }

        val result = exec(
            launched = launched,
            script = command.joinToString(" ") { shellQuote(it) },
            timeoutSeconds = DEFAULT_TYPED_TIMEOUT_SECONDS
        )
        val stdout = trimOutput(result.stdout)
        val stderr = trimOutput(result.stderr)
        return if (result.success) {
            val data = when (action) {
                PrivilegedActionPolicy.ACTION_SETTINGS_GET -> mapOf("value" to stdout.trim())
                PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP -> mapOf("value" to stdout.trim())
                PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LIST_PACKAGES -> {
                    val filter = arguments["filter"]?.trim().orEmpty()
                    val packages = stdout
                        .lineSequence()
                        .map { it.removePrefix("package:").trim() }
                        .filter { it.isNotEmpty() }
                        .filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
                        .joinToString("\n")
                    mapOf("packages" to packages)
                }
                else -> emptyMap()
            }
            PrivilegedResult(
                requestId = request.requestId,
                action = action,
                success = true,
                code = "ok",
                message = "Privileged action executed successfully.",
                authority = authority,
                source = source,
                output = combineOutput(stdout, stderr),
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                availableActions = PrivilegedActionPolicy.visibleAgentActions(authority),
                data = data + ("outputComplete" to result.outputComplete.toString()),
                command = command.joinToString(" "),
                timeoutSeconds = DEFAULT_TYPED_TIMEOUT_SECONDS.toInt()
            )
        } else {
            failure(
                request = request,
                authority = authority,
                code = if (result.timedOut) "timeout" else "command_failed",
                message = if (result.timedOut) {
                    "Privileged action timed out."
                } else {
                    "Privileged action failed."
                },
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                command = command.joinToString(" "),
                timeoutSeconds = DEFAULT_TYPED_TIMEOUT_SECONDS.toInt()
            ).copy(data = mapOf("outputComplete" to result.outputComplete.toString()))
        }
    }

    internal fun executeRawShell(
        request: PrivilegedRequest,
        authority: PrivilegedAuthority,
        launched: LaunchedPrivilegedShell,
    ): PrivilegedResult {
        val command = request.command?.trim().orEmpty()
        if (command.isEmpty()) {
            return failure(
                request = request,
                authority = authority,
                code = "invalid_arguments",
                message = "command is required for shell.exec."
            )
        }
        val timeoutSeconds = request.timeoutSeconds?.coerceIn(5, 600)?.toLong()
            ?: DEFAULT_SHELL_TIMEOUT_SECONDS
        val script = runCatching {
            buildShellScript(
                command = command,
                workingDirectory = request.workingDirectory,
                environment = request.environment
            )
        }.getOrElse { error ->
            return failure(
                request = request,
                authority = authority,
                code = "invalid_arguments",
                message = error.message ?: "Invalid shell.exec arguments."
            )
        }

        val result = exec(
            launched = launched,
            script = script,
            timeoutSeconds = timeoutSeconds
        )
        val stdout = trimOutput(result.stdout)
        val stderr = trimOutput(result.stderr)
        return if (result.success) {
            PrivilegedResult(
                requestId = request.requestId,
                action = request.action,
                success = true,
                code = "ok",
                message = "Privileged shell command executed successfully.",
                authority = authority,
                source = source,
                output = combineOutput(stdout, stderr),
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                availableActions = PrivilegedActionPolicy.visibleAgentActions(authority),
                command = command,
                timeoutSeconds = timeoutSeconds.toInt(),
                workingDirectory = request.workingDirectory,
                environment = request.environment,
                data = mapOf("outputComplete" to result.outputComplete.toString()),
            )
        } else {
            failure(
                request = request,
                authority = authority,
                code = if (result.timedOut) "timeout" else "command_failed",
                message = if (result.timedOut) {
                    "Privileged shell command timed out."
                } else {
                    "Privileged shell command failed."
                },
                stdout = stdout,
                stderr = stderr,
                exitCode = result.exitCode,
                command = command,
                timeoutSeconds = timeoutSeconds.toInt()
            ).copy(data = mapOf("outputComplete" to result.outputComplete.toString()))
        }
    }

    private fun buildCommand(
        action: String,
        arguments: Map<String, String>,
        authority: PrivilegedAuthority,
    ): List<String> {
        return when (action) {
            PrivilegedActionPolicy.ACTION_PACKAGE_LAUNCH -> {
                val packageName = requirePackageName(arguments["packageName"])
                val activityName = arguments["activityName"]?.trim().orEmpty()
                if (activityName.isNotEmpty()) {
                    requireSafeComponent(activityName)
                    listOf("am", "start", "-n", "$packageName/$activityName")
                } else {
                    listOf(
                        "monkey",
                        "-p",
                        packageName,
                        "-c",
                        "android.intent.category.LAUNCHER",
                        "1"
                    )
                }
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_FORCE_STOP -> {
                val packageName = requirePackageName(arguments["packageName"])
                require(!PrivilegedActionPolicy.isHostPackage(packageName)) {
                    "Force-stopping the host app is blocked."
                }
                listOf("am", "force-stop", packageName)
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_GRANT_PERMISSION -> {
                listOf(
                    "pm",
                    "grant",
                    requirePackageName(arguments["packageName"]),
                    requirePermissionName(arguments["permission"])
                )
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_REVOKE_PERMISSION -> {
                listOf(
                    "pm",
                    "revoke",
                    requirePackageName(arguments["packageName"]),
                    requirePermissionName(arguments["permission"])
                )
            }
            PrivilegedActionPolicy.ACTION_PACKAGE_SET_APPOPS -> {
                val mode = arguments["mode"]?.trim()?.lowercase().orEmpty()
                require(allowedAppOpsModes.contains(mode)) { "Unsupported appops mode." }
                listOf(
                    "appops",
                    "set",
                    requirePackageName(arguments["packageName"]),
                    requireSimpleToken(arguments["op"], "op"),
                    mode
                )
            }
            PrivilegedActionPolicy.ACTION_SETTINGS_GET -> {
                listOf(
                    "settings",
                    "get",
                    requireSettingsNamespace(arguments["namespace"]),
                    requireSimpleToken(arguments["key"], "key")
                )
            }
            PrivilegedActionPolicy.ACTION_SETTINGS_PUT -> {
                listOf(
                    "settings",
                    "put",
                    requireSettingsNamespace(arguments["namespace"]),
                    requireSimpleToken(arguments["key"], "key"),
                    requireNotBlank(arguments["value"], "value")
                )
            }
            PrivilegedActionPolicy.ACTION_DEVICE_KEYEVENT -> {
                listOf("input", "keyevent", requireKeyEvent(arguments["key"]))
            }
            PrivilegedActionPolicy.ACTION_DEVICE_EXPAND_NOTIFICATIONS -> {
                listOf("cmd", "statusbar", "expand-notifications")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_EXPAND_QUICK_SETTINGS -> {
                listOf("cmd", "statusbar", "expand-settings")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_WIFI_ENABLED -> {
                listOf("svc", "wifi", if (isEnabled(arguments)) "enable" else "disable")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_SET_MOBILE_DATA_ENABLED -> {
                require(authority == PrivilegedAuthority.ROOT) { "Mobile data control requires root authority." }
                listOf("svc", "data", if (isEnabled(arguments)) "enable" else "disable")
            }
            PrivilegedActionPolicy.ACTION_DEVICE_INPUT_TEXT -> {
                listOf("input", "text", encodeInputText(requireNotBlank(arguments["text"], "text")))
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_GETPROP -> {
                val name = arguments["name"]?.trim()
                    ?.ifEmpty { null }
                    ?: arguments["prop"]?.trim().orEmpty()
                if (name.isBlank()) {
                    listOf("getprop")
                } else {
                    listOf("getprop", requireSimpleToken(name, "name"))
                }
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_DUMPSYS -> {
                val service = arguments["service"]?.trim()?.lowercase().orEmpty()
                require(allowedDumpsysServices.contains(service)) { "Unsupported dumpsys service." }
                listOf("dumpsys", service)
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LIST_PACKAGES -> {
                listOf("pm", "list", "packages")
            }
            PrivilegedActionPolicy.ACTION_DIAGNOSTICS_LOGCAT_TAIL -> {
                val buffer = arguments["buffer"]?.trim()?.lowercase()?.ifEmpty { "main" } ?: "main"
                require(allowedLogcatBuffers.contains(buffer)) { "Unsupported logcat buffer." }
                if (buffer == "kernel") {
                    require(authority == PrivilegedAuthority.ROOT) { "Kernel logcat requires root authority." }
                }
                val lines = arguments["lines"]?.trim()?.toIntOrNull()?.coerceIn(1, 200) ?: 80
                listOf("logcat", "-d", "-b", buffer, "-t", lines.toString())
            }
            else -> error("Unsupported action.")
        }
    }

    private fun buildShellScript(
        command: String,
        workingDirectory: String?,
        environment: Map<String, String>,
    ): String {
        val lines = mutableListOf<String>()
        workingDirectory?.trim()?.takeIf { it.isNotEmpty() }?.let { directory ->
            lines += "cd ${shellQuote(directory)}"
        }
        environment.forEach { (key, value) ->
            require(envKeyPattern.matches(key)) { "Invalid environment variable name: $key" }
            lines += "export $key=${shellQuote(value)}"
        }
        lines += command
        return lines.joinToString(separator = "\n")
    }

    private fun exec(
        launched: LaunchedPrivilegedShell,
        script: String,
        timeoutSeconds: Long,
    ): ExecResult {
        val process = launched.process
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(script)
            writer.newLine()
        }
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        val stdoutFailure = AtomicReference<IOException?>()
        val stderrFailure = AtomicReference<IOException?>()
        val stdoutInput = process.inputStream
        val stderrInput = process.errorStream
        val stdoutReader = thread(start = true, isDaemon = true) {
            try {
                stdoutInput.use { input -> copyBounded(input, stdoutBuffer) }
            } catch (error: IOException) {
                stdoutFailure.set(error)
            }
        }
        val stderrReader = thread(start = true, isDaemon = true) {
            try {
                stderrInput.use { input -> copyBounded(input, stderrBuffer) }
            } catch (error: IOException) {
                stderrFailure.set(error)
            }
        }
        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            PrivilegedProcessCleanup.closeAndDestroy(process)
            joinReaders(stdoutReader, stderrReader)
            Thread.currentThread().interrupt()
            throw CancellationException("Privileged process wait was cancelled.").also { it.initCause(error) }
        }
        if (!finished) {
            PrivilegedProcessCleanup.destroy(process)
            joinReaders(stdoutReader, stderrReader)
            return ExecResult(
                success = false,
                timedOut = true,
                exitCode = null,
                stdout = stdoutBuffer.toString(),
                stderr = stderrBuffer.toString(),
                outputComplete = false,
            )
        }
        joinReaders(stdoutReader, stderrReader)
        val outputComplete = !stdoutReader.isAlive && !stderrReader.isAlive
        val readFailure = stdoutFailure.get() ?: stderrFailure.get()
        if (!outputComplete) {
            // 正常退出但输出管道未闭合时主动关闭本端读取流；结果仍如实标记为不完整。
            runCatching { stdoutInput.close() }
            runCatching { stderrInput.close() }
            joinReaders(stdoutReader, stderrReader)
        }
        return ExecResult(
            // 正常退出时读取失败不能伪装成命令成功；取消/超时收尾导致的关闭异常由对应分支拥有。
            success = process.exitValue() == 0 && readFailure == null,
            timedOut = false,
            exitCode = process.exitValue(),
            stdout = stdoutBuffer.toString(),
            stderr = stderrBuffer.toString(),
            outputComplete = outputComplete,
        )
    }

    private fun joinReaders(stdoutReader: Thread, stderrReader: Thread) {
        stdoutReader.join(READER_JOIN_MILLIS)
        stderrReader.join(READER_JOIN_MILLIS)
    }

    private fun trimOutput(value: String): String {
        val normalized = value.trim()
        if (normalized.length <= OUTPUT_LIMIT) {
            return normalized
        }
        return normalized.take(OUTPUT_LIMIT)
    }

    private fun combineOutput(stdout: String, stderr: String): String {
        return when {
            stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n[stderr]\n$stderr"
            stdout.isNotBlank() -> stdout
            else -> stderr
        }
    }

    private fun failure(
        request: PrivilegedRequest,
        authority: PrivilegedAuthority,
        code: String,
        message: String,
        output: String = "",
        stdout: String = "",
        stderr: String = "",
        transcript: String = "",
        exitCode: Int? = null,
        requiresConfirmation: Boolean = false,
        blockedByPolicy: Boolean = false,
        command: String? = request.command,
        timeoutSeconds: Int? = request.timeoutSeconds,
    ): PrivilegedResult {
        val mergedOutput = if (output.isNotBlank()) output else combineOutput(stdout, stderr)
        return PrivilegedResult(
            requestId = request.requestId,
            action = request.action,
            success = false,
            code = code,
            message = message,
            authority = authority,
            source = source,
            output = mergedOutput,
            stdout = stdout,
            stderr = stderr,
            transcript = transcript,
            exitCode = exitCode,
            requiresConfirmation = requiresConfirmation,
            availableActions = PrivilegedActionPolicy.visibleAgentActions(authority),
            command = command,
            timeoutSeconds = timeoutSeconds,
            workingDirectory = request.workingDirectory,
            environment = request.environment,
            sessionId = request.sessionId,
            blockedByPolicy = blockedByPolicy
        )
    }

    private fun audit(request: PrivilegedRequest, result: PrivilegedResult) {
        val summary = buildString {
            append("action=${request.action}")
            request.sessionId?.takeIf { it.isNotBlank() }?.let { append(", sessionId=$it") }
            request.command?.takeIf { it.isNotBlank() }?.let {
                append(", command=")
                append(it.take(200))
            }
            append(", success=${result.success}")
            append(", code=${result.code}")
            result.exitCode?.let { append(", exit=$it") }
            if (result.blockedByPolicy) {
                append(", blockedByPolicy=true")
            }
        }
        OmniLog.i(TAG, "Privileged audit: $summary")
    }

    private fun requirePackageName(value: String?): String {
        return requireNotBlank(value, "packageName").also {
            require(Regex("^[A-Za-z0-9._]+$").matches(it)) { "Invalid packageName." }
        }
    }

    private fun requirePermissionName(value: String?): String {
        return requireNotBlank(value, "permission").also {
            require(Regex("^[A-Za-z0-9._]+$").matches(it)) { "Invalid permission." }
        }
    }

    private fun requireSettingsNamespace(value: String?): String {
        val namespace = value?.trim()?.lowercase().orEmpty()
        require(allowedSettingsNamespaces.contains(namespace)) { "Unsupported namespace." }
        return namespace
    }

    private fun requireSimpleToken(value: String?, fieldName: String): String {
        return requireNotBlank(value, fieldName).also {
            require(Regex("^[A-Za-z0-9_./:-]+$").matches(it)) { "Invalid $fieldName." }
        }
    }

    private fun requireKeyEvent(value: String?): String {
        return requireNotBlank(value, "key").also {
            require(Regex("^[A-Za-z0-9_]+$").matches(it)) { "Invalid key event." }
        }
    }

    private fun requireSafeComponent(value: String) {
        require(Regex("^[A-Za-z0-9_.$]+$").matches(value)) { "Invalid activityName." }
    }

    private fun requireNotBlank(value: String?, fieldName: String): String {
        val trimmed = value?.trim().orEmpty()
        require(trimmed.isNotEmpty()) { "$fieldName is required." }
        return trimmed
    }

    private fun isEnabled(arguments: Map<String, String>): Boolean {
        return arguments["enabled"]?.trim()?.lowercase() in setOf("1", "true", "yes", "on", "enable", "enabled")
    }

    private fun isExplicitlyConfirmed(arguments: Map<String, String>): Boolean {
        return arguments["confirmed"]?.trim()?.lowercase() in setOf("1", "true", "yes", "confirm", "confirmed")
    }

    private fun encodeInputText(text: String): String {
        return text
            .replace("%", "%25")
            .replace(" ", "%s")
            .replace("\n", " ")
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun executeExistingSession(request: PrivilegedRequest, action: String): PrivilegedResult {
        val authority = PrivilegedShellSessionManager.sessionAuthority(request.sessionId, source)
            ?: return failure(request, PrivilegedAuthority.NONE, "session_not_found", "The privileged shell session does not exist.")
        if (!PrivilegedActionPolicy.isSupported(action, authority, includeInternal = true, arguments = request.arguments)) {
            return failure(request, authority, "unsupported_action", "Action is not available for the measured authority.")
        }
        if (action == PrivilegedActionPolicy.ACTION_SESSION_EXEC) {
            PrivilegedActionPolicy.blockedCommandReason(request.command)?.let {
                return failure(request, authority, "blocked_by_policy", it, blockedByPolicy = true)
            }
            if (!isExplicitlyConfirmed(request.arguments)) {
                return failure(request, authority, "confirmation_required", "This privileged action requires explicit confirmation.", requiresConfirmation = true)
            }
        }
        return when (action) {
            PrivilegedActionPolicy.ACTION_SESSION_EXEC -> PrivilegedShellSessionManager.execSession(request, authority, source)
            PrivilegedActionPolicy.ACTION_SESSION_READ -> PrivilegedShellSessionManager.readSession(request, authority, source)
            else -> PrivilegedShellSessionManager.stopSession(request, authority, source)
        }
    }

    internal fun copyBounded(input: java.io.InputStream, output: ByteArrayOutputStream) {
        val buffer = ByteArray(2048)
        var remaining = OUTPUT_LIMIT
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        while (input.read(buffer) >= 0) Unit
    }

    internal data class ExecResult(
        val success: Boolean,
        val timedOut: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val outputComplete: Boolean,
    )

    private companion object {
        const val TAG = "PrivilegedCommandExec"
        const val OUTPUT_LIMIT = 16_000
        const val DEFAULT_TYPED_TIMEOUT_SECONDS = 8L
        const val DEFAULT_SHELL_TIMEOUT_SECONDS = 60L
        const val READER_JOIN_MILLIS = 500L
    }
}
