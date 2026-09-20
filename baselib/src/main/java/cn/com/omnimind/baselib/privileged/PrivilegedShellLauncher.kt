package cn.com.omnimind.baselib.privileged

import java.io.IOException
import kotlinx.coroutines.CancellationException

internal class SuUnavailableException(cause: IOException?) : IOException(
    "No standard su candidate could be started.", cause
)

internal class AuthorityUnexpectedException(message: String) : IOException(message)

internal class SuDeniedException(message: String) : IOException(message)

data class LaunchedPrivilegedShell(
    val process: Process,
    val authority: PrivilegedAuthority,
    val uid: Int,
    val binaryPath: String? = null,
)

fun interface PrivilegedShellLauncher {
    @Throws(IOException::class)
    fun launch(source: PrivilegedSource): LaunchedPrivilegedShell
}

internal class StandardPrivilegedShellLauncher(
    private val processStarter: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val probeTimeoutMillis: Long = 10_000,
) : PrivilegedShellLauncher {
    override fun launch(source: PrivilegedSource): LaunchedPrivilegedShell {
        return when (source) {
            PrivilegedSource.NONE -> throw IOException("No privileged source selected.")
            PrivilegedSource.SHIZUKU -> probe(processStarter(listOf(SHELL_PATH)), null)
            PrivilegedSource.SU -> launchSu()
        }
    }

    private fun launchSu(): LaunchedPrivilegedShell {
        var lastError: IOException? = null
        for (path in SU_CANDIDATES) {
            val process = try {
                processStarter(listOf(path, "-c", SHELL_PATH))
            } catch (error: IOException) {
                lastError = error
                continue
            }
            // 进程一旦创建，不因授权拒绝或探针失败改试其他路径。
            return probe(process, path)
        }
        throw SuUnavailableException(lastError)
    }

    private fun probe(process: Process, binaryPath: String?): LaunchedPrivilegedShell {
        val marker = "__OMNIBOT_UID__"
        process.outputStream.write("__omnibot_uid=\$(/system/bin/id -u); printf '$marker%s__OMNIBOT_UID_END__\\n' \"\$__omnibot_uid\"\n".toByteArray())
        process.outputStream.flush()
        val line = readLineBounded(process, 128, binaryPath != null)
        val uid = line.substringAfter(marker, "").substringBefore("__OMNIBOT_UID_END__").trim().toIntOrNull()
        if (uid == null) {
            PrivilegedProcessCleanup.closeAndDestroy(process)
            throw AuthorityUnexpectedException("Privileged shell identity probe returned no valid UID.")
        }
        val authority = authorityForUid(uid)
        if (authority == PrivilegedAuthority.NONE) {
            PrivilegedProcessCleanup.closeAndDestroy(process)
            throw AuthorityUnexpectedException("Privileged shell returned unexpected UID $uid.")
        }
        return LaunchedPrivilegedShell(process, authority, uid, binaryPath)
    }

    private fun readLineBounded(process: Process, limit: Int, directSu: Boolean): String {
        val input = process.inputStream
        val value = StringBuilder()
        val deadline = System.nanoTime() + probeTimeoutMillis * 1_000_000
        while (value.length < limit) {
            if (Thread.currentThread().isInterrupted) {
                PrivilegedProcessCleanup.closeAndDestroy(process)
                throw CancellationException("Privileged identity probe was cancelled.")
            }
            if (input.available() == 0) {
                if (!process.isAlive || System.nanoTime() >= deadline) {
                    PrivilegedProcessCleanup.closeAndDestroy(process)
                    if (directSu && value.isEmpty()) {
                        throw SuDeniedException("su exited or timed out without identity output.")
                    }
                    throw AuthorityUnexpectedException("Privileged shell identity probe timed out.")
                }
                try {
                    Thread.sleep(10)
                } catch (error: InterruptedException) {
                    PrivilegedProcessCleanup.closeAndDestroy(process)
                    Thread.currentThread().interrupt()
                    throw CancellationException("Privileged identity probe was cancelled.").also { it.initCause(error) }
                }
                continue
            }
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            value.append(next.toChar())
        }
        return value.toString()
    }

    companion object {
        const val SHELL_PATH = "/system/bin/sh"
        val SU_CANDIDATES = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        fun authorityForUid(uid: Int) = when (uid) {
            0 -> PrivilegedAuthority.ROOT
            2000 -> PrivilegedAuthority.ADB
            else -> PrivilegedAuthority.NONE
        }
    }
}

internal object PrivilegedProcessCleanup {
    fun closeAndDestroy(process: Process): Boolean {
        runCatching { process.outputStream.close() }
        return destroy(process)
    }

    fun destroy(process: Process): Boolean {
        runCatching { process.destroy() }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        return !process.isAlive
    }
}
