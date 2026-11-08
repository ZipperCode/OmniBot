package com.ai.assistance.operit.terminal.setup

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 单元测试专用的 POSIX Shell 测试辅助工具。
 * 仅用于在本地开发机（Windows / macOS / Linux）单元测试中定位真实可执行的 POSIX sh，
 * 优先环境变量与标准安装目录探测，不硬编码个人敏感路径，不写个人信息日志，不修改生产环境代码或全局系统配置。
 */
internal object PosixShellTestHelper {
    val isWindows: Boolean = File.separatorChar == '\\'

    val shellPath: String by lazy {
        // 1. POSIX 标准路径（Linux / macOS / Android 宿主）
        for (candidate in listOf("/bin/dash", "/bin/sh", "/system/bin/sh", "/usr/bin/sh", "/bin/bash", "/usr/bin/bash")) {
            val f = File(candidate)
            if (f.canExecute()) return@lazy candidate
        }

        // 2. Windows 平台：环境变量优先探测，再扫描标准安装目录与 PATH
        val searchDirs = LinkedHashSet<String>()

        // 环境变量显式配置
        for (envKey in listOf("SH_PATH", "GIT_HOME", "CYGWIN_HOME", "MSYS2_HOME", "MSYS_HOME")) {
            val envVal = System.getenv(envKey)
            if (!envVal.isNullOrBlank()) {
                val dir = File(envVal)
                if (dir.isDirectory) {
                    searchDirs.add(File(dir, "usr/bin").absolutePath)
                    searchDirs.add(File(dir, "bin").absolutePath)
                    searchDirs.add(dir.absolutePath)
                } else if (dir.isFile && dir.canExecute()) {
                    dir.parentFile?.absolutePath?.let { searchDirs.add(it) }
                }
            }
        }

        // PATH 环境变量中的候选（排除 Windows 系统目录与 WSL 桩）
        val pathEnv = System.getenv("PATH") ?: System.getenv("Path").orEmpty()
        val pathDirs = pathEnv.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot {
                val lower = it.lowercase().replace('/', '\\')
                lower.contains("\\windows\\system32") || lower.contains("\\windows\\syswow64") ||
                    lower.contains("\\windowsapps") || lower.endsWith("\\windows")
            }
        searchDirs.addAll(pathDirs)

        // 标准 Program Files 与驱动器根目录候选
        val programFiles = listOfNotNull(
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            System.getenv("LOCALAPPDATA")?.let { "$it\\Programs" }
        )
        val roots = File.listRoots()?.mapNotNull { it.absolutePath?.trimEnd('\\') }?.filter { it.isNotBlank() }
            ?.ifEmpty { listOf("C:", "D:") } ?: listOf("C:", "D:")
        for (base in programFiles) {
            searchDirs.add(File(base, "Git\\usr\\bin").absolutePath)
            searchDirs.add(File(base, "Git\\bin").absolutePath)
        }
        for (root in roots) {
            searchDirs.add(File("$root\\Program Files\\Git\\usr\\bin").absolutePath)
            searchDirs.add(File("$root\\Program Files\\Git\\bin").absolutePath)
            searchDirs.add(File("$root\\cygwin64\\bin").absolutePath)
            searchDirs.add(File("$root\\msys64\\usr\\bin").absolutePath)
        }

        for (dirPath in searchDirs) {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) continue
            for (exe in listOf("sh.exe", "sh", "bash.exe", "bash")) {
                val candidate = File(dir, exe)
                if (candidate.exists() && candidate.canExecute()) {
                    val lower = candidate.absolutePath.lowercase()
                    val realTarget = if (lower.contains("\\git\\bin\\")) {
                        val usrFile = File(candidate.parentFile?.parentFile, "usr\\bin\\" + candidate.name)
                        if (usrFile.exists() && usrFile.canExecute()) usrFile else candidate
                    } else candidate

                    try {
                        val p = ProcessBuilder(realTarget.absolutePath, "-c", "echo ok").start()
                        val output = p.inputStream.bufferedReader().use { it.readText() }
                        val finished = p.waitFor(2, TimeUnit.SECONDS)
                        if (finished && p.exitValue() == 0 && output.trim() == "ok") {
                            return@lazy realTarget.absolutePath
                        } else {
                            p.destroyForcibly()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        "/bin/sh"
    }

    val shellDirectory: String by lazy {
        File(shellPath).parentFile?.absolutePath.orEmpty()
    }

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
     * 构建包含必要 POSIX 工具目录的 ProcessBuilder。
     */
    fun processBuilder(vararg command: String): ProcessBuilder {
        val adaptedCommand = command.mapIndexed { index, arg ->
            if (index == 0 && (arg == "/bin/sh" || arg == "/bin/dash" || arg == "sh" || arg == "bash")) {
                shellPath
            } else {
                arg
            }
        }
        val finalCommand = if (isWindows && adaptedCommand.size >= 3 && adaptedCommand[1] == "-c") {
            val script = adaptedCommand[2]
            val tmp = File.createTempFile("posix_test_", ".sh").apply {
                deleteOnExit()
                writeText(script, Charsets.UTF_8)
            }
            listOf(adaptedCommand[0], toPosixPath(tmp.absolutePath)) + adaptedCommand.drop(3)
        } else {
            adaptedCommand
        }
        val pb = ProcessBuilder(finalCommand)
        if (isWindows && shellDirectory.isNotBlank()) {
            val env = pb.environment()
            val pathKey = env.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            val currentPath = env[pathKey] ?: System.getenv("PATH") ?: System.getenv("Path").orEmpty()
            val newPath = shellDirectory + File.pathSeparator + currentPath
            env[pathKey] = newPath
            if (pathKey != "PATH") {
                env["PATH"] = newPath
            }
        }
        return pb
    }
}
