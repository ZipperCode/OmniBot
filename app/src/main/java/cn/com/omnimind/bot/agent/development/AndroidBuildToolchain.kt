package cn.com.omnimind.bot.agent.development

import java.io.File
import java.util.zip.ZipFile

/**
 * 编译工具链与运行时修复支持。
 * 适配移动端内嵌 Linux (Alpine PRoot) 与 Termux 环境，提供启动器修正与 jar 字节码完整性校验。
 */
object AndroidBuildToolchain {

    data class LauncherSpec(val pkg: String, val jar: String, val main: String)

    val launchers: Map<String, LauncherSpec> = mapOf(
        "d8" to LauncherSpec("d8", "d8.jar", "com.android.tools.r8.D8"),
        "r8" to LauncherSpec("d8", "d8.jar", "com.android.tools.r8.R8"),
        "apksigner" to LauncherSpec("apksigner", "apksigner.jar", "-jar")
    )

    private val jdkPattern = Regex("java-(\\d+)-openjdk")

    fun findJavaHome(prefix: File): File? {
        val jvmDir = File(prefix, "lib/jvm")
        if (!jvmDir.isDirectory) return null
        return jvmDir.listFiles().orEmpty()
            .filter { jdkPattern.matches(it.name) && File(it, "bin/java").isFile }
            .maxByOrNull { jdkPattern.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
    }

    fun javaHomeString(prefix: File, executionPrefix: String): String? {
        val home = findJavaHome(prefix) ?: return null
        return if (executionPrefix == prefix.absolutePath) {
            home.canonicalPath
        } else {
            "$executionPrefix/${home.relativeTo(prefix).invariantSeparatorsPath}"
        }
    }

    /**
     * 重写包装脚本中写死的绝对路径（例如 Termux 默认硬编码的 /data/data/com.termux/...）。
     * 替换为当前环境前缀变量 ${PREFIX}/share/java/...
     */
    fun rewriteLauncher(name: String, text: String): String? {
        val spec = launchers[name] ?: return null
        val hardcodedPrefix = "/data/data/com.termux/files/usr/share/java/${spec.jar}"
        if (!text.contains(spec.main) && !text.contains(spec.jar)) return null
        if (!text.contains(hardcodedPrefix)) return null

        val pathRegex = Regex("[\"']?${Regex.escape(hardcodedPrefix)}[\"']?")
        val targetReplacement = "\"${'$'}{PREFIX:-/usr}/share/java/${spec.jar}\""
        val rewritten = pathRegex.replace(text) { targetReplacement }
        return if (rewritten.startsWith("#!")) {
            "#!/bin/sh\n" + rewritten.substringAfter('\n')
        } else {
            rewritten
        }
    }

    fun repairLaunchers(prefix: File, installedPackages: Collection<String>): Int {
        var repairedCount = 0
        launchers.forEach { (name, spec) ->
            if (spec.pkg in installedPackages || installedPackages.isEmpty()) {
                val binFile = File(prefix, "bin/$name")
                if (binFile.isFile) {
                    val content = runCatching { binFile.readText() }.getOrNull()
                    if (content != null) {
                        val repaired = rewriteLauncher(name, content)
                        if (repaired != null && repaired != content) {
                            runCatching {
                                binFile.writeText(repaired)
                                binFile.setExecutable(true, false)
                                repairedCount++
                            }
                        }
                    }
                }
            }
        }
        return repairedCount
    }

    fun validateJarClasses(jarFile: File, requiredClasses: List<String>): Boolean {
        if (!jarFile.isFile || jarFile.length() == 0L) return false
        return runCatching {
            ZipFile(jarFile).use { zip ->
                requiredClasses.all { className ->
                    val path = if (className.endsWith(".class")) className else "${className.replace('.', '/')}.class"
                    zip.getEntry(path) != null
                }
            }
        }.getOrDefault(false)
    }

    fun validateD8Jar(d8Jar: File): Boolean =
        validateJarClasses(d8Jar, listOf("com/android/tools/r8/D8.class", "com/android/tools/r8/R8.class"))

    fun validateApkSignerJar(apkSignerJar: File): Boolean =
        validateJarClasses(apkSignerJar, listOf("com/android/apksigner/ApkSignerTool.class"))
}
