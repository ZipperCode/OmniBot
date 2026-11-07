package cn.com.omnimind.bot.agent.development

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import cn.com.omnimind.bot.termux.TermuxCommandSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 手机本地构建与 LSPosed 部署桥接层 (F14, F15, F16)。
 * 负责驱动双轨构建（极速无 Gradle / 标准 Gradle），并调用 RootManagement 真实特权通道进行静默安装、作用域配置与目标重载。
 */
object LsposedManagementBridge {

    data class BuildExecutionResult(
        val success: Boolean,
        val routeUsed: String,
        val apkPath: String?,
        val fileSizeBytes: Long?,
        val sha256: String?,
        val signatureVerified: Boolean,
        val stdout: String,
        val stderr: String,
        val errorMessage: String? = null
    )

    data class DeploymentResult(
        val success: Boolean,
        val apkPath: String,
        val modulePackageName: String,
        val targetPackage: String,
        val installed: Boolean,
        val moduleEnabled: Boolean,
        val scopeConfigured: Boolean,
        val targetReloaded: Boolean,
        val configVerified: Boolean,
        val hookVerified: Boolean,
        val message: String
    )

    suspend fun buildProject(
        context: Context,
        projectDir: File,
        buildTask: String = "assembleDebug",
        requestedRoute: String = "auto",
        clean: Boolean = false,
        timeoutSeconds: Int = 300
    ): BuildExecutionResult = withContext(Dispatchers.IO) {
        require(projectDir.isDirectory) { "Project directory does not exist: ${projectDir.path}" }

        val hasGradlew = File(projectDir, "gradlew").isFile
        val hasBuildGradle = File(projectDir, "build.gradle").isFile || File(projectDir, "build.gradle.kts").isFile
        val hasBuildApk = File(projectDir, "build-apk.sh").isFile

        val route = when (requestedRoute.lowercase()) {
            "no_gradle" -> "no_gradle"
            "gradle" -> "gradle"
            else -> if (hasGradlew || hasBuildGradle) "gradle" else "no_gradle"
        }
        if (route == "no_gradle") {
            // 极速无 Gradle 构建流水线
            check(hasBuildApk) { "build-apk.sh not found in ${projectDir.path}" }
            File(projectDir, "build-apk.sh").setExecutable(true, false)
            val quotedProject = "'" + projectDir.absolutePath.replace("'", "'\"'\"'") + "'"
            val cleanCmd = if (clean) "rm -rf build && " else ""
            val buildCommand = "cd $quotedProject && ${cleanCmd}sh build-apk.sh"

            val execResult = TermuxCommandRunner.execute(
                context = context,
                spec = TermuxCommandSpec(
                    command = buildCommand,
                    workingDirectory = projectDir.absolutePath,
                    timeoutSeconds = timeoutSeconds
                )
            )

            val fullOutput = execResult.stdout + "\n" + execResult.stderr
            val isSuccess = execResult.success && fullOutput.contains("BUILD_SUCCESS")

            val apkPathLine = fullOutput.lines().firstOrNull { it.startsWith("APK_PATH:") }
            val rawApkPath = apkPathLine?.substringAfter("APK_PATH:")?.trim()

            val apkFile = if (!rawApkPath.isNullOrBlank() && File(rawApkPath).isFile) {
                File(rawApkPath)
            } else null

            var signatureVerified = false
            var verifyLog = ""
            if (apkFile != null && apkFile.isFile && apkFile.length() > 0) {
                val quotedApk = "'" + apkFile.absolutePath.replace("'", "'\"'\"'") + "'"
                val verifyExec = TermuxCommandRunner.execute(
                    context = context,
                    spec = TermuxCommandSpec(
                        command = "apksigner verify --verbose $quotedApk",
                        timeoutSeconds = 30
                    )
                )
                signatureVerified = verifyExec.success && (verifyExec.resultCode == 0 || verifyExec.stdout.contains("Verifies"))
                verifyLog = verifyExec.stdout + "\n" + verifyExec.stderr
            }

            val sha256 = apkFile?.let { computeSha256(it) }
            val buildSuccess = isSuccess && apkFile != null && apkFile.length() > 0 && signatureVerified

            val errorMessage = when {
                !isSuccess -> "Build failed or APK output was not generated."
                apkFile == null -> "Expected APK output file was not found at declared path."
                !signatureVerified -> "APK generated but apksigner signature verification failed: $verifyLog"
                else -> null
            }

            BuildExecutionResult(
                success = buildSuccess,
                routeUsed = "no_gradle",
                apkPath = apkFile?.absolutePath,
                fileSizeBytes = apkFile?.length(),
                sha256 = sha256,
                signatureVerified = signatureVerified,
                stdout = execResult.stdout,
                stderr = execResult.stderr,
                errorMessage = errorMessage
            )
        } else {
            // Gradle 构建流水线（支持复杂开源工程，注入 aapt2FromMavenOverride 杜绝 ARM64 下载 x86 aapt2）
            val taskRegex = Regex("^[a-zA-Z0-9_:\\-]+(\\s+[a-zA-Z0-9_:\\-]+)*$")
            require(taskRegex.matches(buildTask.trim())) { "Invalid Gradle task specification: $buildTask" }

            val buildStartTime = System.currentTimeMillis()
            val cleanTask = if (clean) "clean " else ""
            val gradleExecutable = if (hasGradlew) "./gradlew" else "gradle"
            val quotedProject = "'" + projectDir.absolutePath.replace("'", "'\"'\"'") + "'"
            val gradleArgs = "-Pandroid.builder.sdkDownload=false -Pandroid.aapt2FromMavenOverride=\"$(command -v aapt2)\" --no-daemon -Dorg.gradle.jvmargs=-Xmx1536m -Dorg.gradle.workers.max=2 -Dkotlin.compiler.execution.strategy=in-process"
            val buildCommand = "cd $quotedProject && $gradleExecutable $cleanTask$buildTask $gradleArgs"
            val execResult = TermuxCommandRunner.execute(
                context = context,
                spec = TermuxCommandSpec(
                    command = buildCommand,
                    workingDirectory = projectDir.absolutePath,
                    timeoutSeconds = timeoutSeconds.coerceAtLeast(600)
                )
            )

            // 搜索本次构建新产生的产物（避免挑选旧 APK）
            val apkFile = projectDir.walkTopDown()
                .filter { it.isFile && it.extension == "apk" && it.path.contains("outputs") && it.lastModified() >= (buildStartTime - 10000L) }
                .maxByOrNull { it.lastModified() }

            var signatureVerified = false
            var verifyLog = ""
            if (apkFile != null && apkFile.isFile && apkFile.length() > 0) {
                val quotedApk = "'" + apkFile.absolutePath.replace("'", "'\"'\"'") + "'"
                val verifyCommand = "apksigner verify --verbose $quotedApk"
                val verifyExec = TermuxCommandRunner.execute(
                    context = context,
                    spec = TermuxCommandSpec(
                        command = verifyCommand,
                        timeoutSeconds = 30
                    )
                )
                signatureVerified = verifyExec.success && (verifyExec.resultCode == 0 || verifyExec.stdout.contains("Verifies"))
                verifyLog = verifyExec.stdout + "\n" + verifyExec.stderr
            }

            val sha256 = apkFile?.let { computeSha256(it) }
            val buildSuccess = execResult.success && apkFile != null && apkFile.length() > 0 && signatureVerified

            val errorMessage = when {
                !execResult.success -> "Gradle build command failed."
                apkFile == null -> "No newly built APK artifact found under build outputs."
                !signatureVerified -> "APK generated but apksigner signature verification failed: $verifyLog"
                else -> null
            }

            BuildExecutionResult(
                success = buildSuccess,
                routeUsed = "gradle",
                apkPath = apkFile?.absolutePath,
                fileSizeBytes = apkFile?.length(),
                sha256 = sha256,
                signatureVerified = signatureVerified,
                stdout = execResult.stdout,
                stderr = execResult.stderr,
                errorMessage = errorMessage
            )
        }
    }

    suspend fun deployModule(
        context: Context,
        apkFile: File,
        targetPackage: String,
        customModulePackage: String? = null,
        reloadTarget: Boolean = true,
        confirmed: Boolean,
        ownerId: String = "phone_development_deploy",
        acpSessionId: String? = null
    ): DeploymentResult = withContext(Dispatchers.IO) {
        require(apkFile.isFile && apkFile.length() > 0) { "Target APK file does not exist: ${apkFile.absolutePath}" }

        // 解析模块包名并核对真实性
        val manifest = ApkAnalysisService.parseManifestFromApk(apkFile, context)
        val modulePackage = customModulePackage ?: manifest.packageName
        if (modulePackage == "unknown" || modulePackage.isBlank()) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = "unknown",
                targetPackage = targetPackage,
                installed = false,
                moduleEnabled = false,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "无法部署：无法从 APK Manifest 解析有效模块包名。"
            )
        }
        if (customModulePackage != null && manifest.packageName != "unknown" &&
            !customModulePackage.equals(manifest.packageName, ignoreCase = true)) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = false,
                moduleEnabled = false,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "模块包名不匹配：Manifest 声明为 '${manifest.packageName}'，但指定为 '$customModulePackage'"
            )
        }

        if (!confirmed) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = false,
                moduleEnabled = false,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "高权限部署操作未获得用户授权。"
            )
        }

        val access = PrivilegedCapabilityManager.get(context).reserve(ownerId, null, acpSessionId)
        if (!access.status.isGranted()) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = false,
                moduleEnabled = false,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "Root access not available or denied. Cannot perform deployment."
            )
        }

        val confirmedFlag = if (confirmed) "true" else "false"

        // 1. 安装 APK (package_control.install) - 失败立即终止
        val installRes = access.executeAgentAction(
            action = "package_control.install",
            arguments = mapOf(
                "apk_path" to apkFile.absolutePath,
                "package_name" to modulePackage,
                "reinstall" to "true",
                "confirmed" to confirmedFlag
            ),
            requiresConfirmation = false
        )
        if (!installRes.success) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = false,
                moduleEnabled = false,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "Step 1/4 (package_control.install) failed: ${installRes.message}"
            )
        }

        // 2. 启用 LSPosed 模块 (xposed_control.module_enable) - 失败立即终止
        val enableRes = access.executeAgentAction(
            action = "xposed_control.module_enable",
            arguments = mapOf(
                "module_pkg_name" to modulePackage,
                "apk_path" to apkFile.absolutePath,
                "confirmed" to confirmedFlag
            ),
            requiresConfirmation = false
        )
        if (!enableRes.success) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = true,
                moduleEnabled = false,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "Step 2/4 (xposed_control.module_enable) failed: ${enableRes.message}"
            )
        }

        // 3. 配置作用域 (xposed_control.scope_set) - 失败立即终止
        val scopeRes = access.executeAgentAction(
            action = "xposed_control.scope_set",
            arguments = mapOf(
                "module_pkg_name" to modulePackage,
                "target_packages" to targetPackage,
                "operation" to "add",
                "confirmed" to confirmedFlag
            ),
            requiresConfirmation = false
        )
        if (!scopeRes.success) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = true,
                moduleEnabled = true,
                scopeConfigured = false,
                targetReloaded = false,
                configVerified = false,
                hookVerified = false,
                message = "Step 3/4 (xposed_control.scope_set) failed: ${scopeRes.message}"
            )
        }

        // 4. 重载目标进程 (xposed_control.target_reload)
        var targetReloaded = false
        if (reloadTarget && targetPackage.isNotBlank()) {
            val reloadRes = access.executeAgentAction(
                action = "xposed_control.target_reload",
                arguments = mapOf(
                    "target_package" to targetPackage,
                    "confirmed" to confirmedFlag
                ),
                requiresConfirmation = false
            )
            targetReloaded = reloadRes.success
            if (!targetReloaded) {
                return@withContext DeploymentResult(
                    success = false,
                    apkPath = apkFile.absolutePath,
                    modulePackageName = modulePackage,
                    targetPackage = targetPackage,
                    installed = true,
                    moduleEnabled = true,
                    scopeConfigured = true,
                    targetReloaded = false,
                    configVerified = false,
                    hookVerified = false,
                    message = "第 4 步重载目标应用进程失败: ${reloadRes.message}"
                )
            }
        }

        // 配置读回与运行时 Hook 证据分别记录，不能由安装或重载成功推断。
        val verifyRes = access.executeAgentAction(
            action = "xposed_control.verify_status",
            arguments = mapOf(
                "module_pkg_name" to modulePackage,
                "target_package" to targetPackage,
                "confirmed" to confirmedFlag
            ),
            requiresConfirmation = false
        )

        val configVerified = verifyRes.success && verifyRes.data["configVerified"] == "true"
        val hookVerified = verifyRes.success && verifyRes.data["hookVerified"] == "true"

        if (!configVerified) {
            return@withContext DeploymentResult(
                success = false,
                apkPath = apkFile.absolutePath,
                modulePackageName = modulePackage,
                targetPackage = targetPackage,
                installed = true,
                moduleEnabled = true,
                scopeConfigured = true,
                targetReloaded = targetReloaded,
                configVerified = false,
                hookVerified = hookVerified,
                message = "第 5 步 LSPosed 配置读回验证未通过 (configVerified != true): ${verifyRes.message}"
            )
        }

        val deployMessage = if (hookVerified) {
            "LSPosed 模块部署成功：已安装、已启用、作用域已配置并已重载目标，且读回确认 Hook 行为已生效。"
        } else {
            "LSPosed 模块配置部署已完全验证生效 (模块启用与作用域已确认)；目标已重载。业务 Hook 行为将在目标应用实际运行触发后读回。"
        }

        DeploymentResult(
            success = true,
            apkPath = apkFile.absolutePath,
            modulePackageName = modulePackage,
            targetPackage = targetPackage,
            installed = true,
            moduleEnabled = true,
            scopeConfigured = true,
            targetReloaded = targetReloaded,
            configVerified = true,
            hookVerified = hookVerified,
            message = deployMessage
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
