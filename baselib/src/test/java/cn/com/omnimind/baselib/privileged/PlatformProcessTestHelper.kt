package cn.com.omnimind.baselib.privileged

import java.io.File

/**
 * 单元测试专用的跨平台真实 ProcessBuilder 与 Shell 定位辅助工具。
 * 仅用于单元测试环境，不改变生产代码实现与签名。
 */
internal object PlatformProcessTestHelper {
    val isWindows: Boolean = File.separatorChar == '\\'

    val shellPath: String by lazy {
        // 1. POSIX 标准路径（Linux / macOS / Android 宿主）
        for (candidate in listOf("/bin/sh", "/system/bin/sh", "/usr/bin/sh", "/bin/bash", "/usr/bin/bash")) {
            val f = File(candidate)
            if (f.canExecute()) return@lazy candidate
        }

        // 2. Windows 平台动态寻找真实 POSIX sh / bash，排除 System32/WSL 桩并优先 Git / MSYS 真实工具 (usr/bin 实体而非 bin 启动桩)
        val standardDirs = listOf(
            "D:\\Program Files\\Git\\usr\\bin",
            "C:\\Program Files\\Git\\usr\\bin",
            "C:\\msys64\\usr\\bin",
            "C:\\cygwin64\\bin",
            "D:\\Program Files\\Git\\bin",
            "C:\\Program Files\\Git\\bin",
            "C:\\Program Files (x86)\\Git\\usr\\bin",
            "C:\\Program Files (x86)\\Git\\bin",
        )

        val pathEnv = System.getenv("PATH").orEmpty()
        val pathDirs = pathEnv.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot {
                val lower = it.lowercase().replace('/', '\\')
                lower.contains("\\windows\\system32") || lower.contains("\\windows\\syswow64") ||
                        lower.contains("\\windowsapps") || lower.endsWith("\\windows")
            }

        for (dir in (standardDirs + pathDirs)) {
            for (exe in listOf("sh.exe", "sh", "bash.exe", "bash")) {
                val candidate = File(dir, exe)
                if (candidate.exists() && candidate.canExecute()) {
                    // 若命中 Git/bin 下的 launcher stub，主动重定向到对应的 usr/bin 真实二进制
                    val lower = candidate.absolutePath.lowercase()
                    val realTarget = if (lower.contains("\\git\\bin\\")) {
                        val usrFile = File(candidate.parentFile?.parentFile, "usr\\bin\\" + candidate.name)
                        if (usrFile.exists() && usrFile.canExecute()) usrFile else candidate
                    } else candidate

                    // 真实快速验证该 shell 能够正常执行 -c "echo ok"
                    try {
                        val p = ProcessBuilder(realTarget.absolutePath, "-c", "echo ok").start()
                        val output = p.inputStream.bufferedReader().use { it.readText() }
                        val finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        if (finished && p.exitValue() == 0 && output.trim() == "ok") {
                            return@lazy realTarget.absolutePath
                        } else {
                            p.destroyForcibly()
                        }
                    } catch (_: Exception) {
                        // 候选 shell 无法启动，继续寻找
                    }
                }
            }
        }

        "/bin/sh"
    }

    /**
     * 将命令首参 `/bin/sh` 或 `/bin/sleep` 转换为跨平台可用命令。
     */
    fun adaptCommand(command: List<String>): List<String> {
        if (command.isEmpty()) return command
        val first = command.first()
        return if (first == "/bin/sh" || first == "/system/bin/sh" || first == "sh") {
            listOf(shellPath) + command.drop(1)
        } else if (first == "/bin/sleep" || first == "sleep") {
            val seconds = command.getOrNull(1)?.toIntOrNull() ?: 30
            sleepCommand(seconds)
        } else {
            command
        }
    }

    /**
     * 跨平台的 sleep 命令生成。
     */
    fun sleepCommand(seconds: Int): List<String> = listOf(shellPath, "-c", "sleep $seconds")

    /**
     * 将 Windows 盘符路径转换为 MSYS / POSIX 形式 (如 C:\foo -> /c/foo)。
     */
    fun toPosixPath(path: String): String {
        if (isWindows && path.length >= 2 && path[1] == ':') {
            val drive = path[0].lowercaseChar()
            return "/$drive" + path.substring(2).replace('\\', '/')
        }
        return path.replace('\\', '/')
    }

    /**
     * 将 MSYS / POSIX 路径转换回 Windows 本地路径 (如 /c/foo -> C:\foo)。
     */
    fun fromPosixPath(posixPath: String): String {
        if (!isWindows) return posixPath
        val trimmed = posixPath.trim()

        // 1. 优先使用 MSYS 内置 cygpath -w 精确转换 /tmp 及各挂载驱动器
        try {
            val p = ProcessBuilder(shellPath, "-c", "cygpath -w '${trimmed.replace("'", "'\"'\"'")}'").start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotEmpty()) {
                return out
            }
        } catch (_: Exception) {}

        // 2. 静态对齐：MSYS2 / Git sh 将 Windows 临时目录挂载为 /tmp
        val tmpDir = System.getProperty("java.io.tmpdir").orEmpty().trimEnd('\\', '/')
        if (trimmed.startsWith("/tmp/") || trimmed == "/tmp") {
            return tmpDir + trimmed.removePrefix("/tmp").replace('/', '\\')
        }
        if (trimmed.length >= 3 && trimmed[0] == '/' && trimmed[2] == '/' && trimmed[1].isLetter()) {
            val drive = trimmed[1].uppercaseChar()
            return "$drive:" + trimmed.substring(2).replace('/', '\\')
        }
        return trimmed
    }
}
