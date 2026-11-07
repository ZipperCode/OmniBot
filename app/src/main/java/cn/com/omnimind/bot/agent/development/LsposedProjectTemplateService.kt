package cn.com.omnimind.bot.agent.development

import android.content.Context
import java.io.File

/**
 * LSPosed 模块脚手架与已有工程导入服务。
 * 负责新工程模板解包渲染与已有本地/克隆工程的导入及构建入口识别。
 */
object LsposedProjectTemplateService {

    private const val TEMPLATE_BASE_ASSET = "phone-development/templates/lsposed-module"

    data class ProjectCreateResult(
        val success: Boolean,
        val projectPath: String,
        val packageName: String,
        val moduleName: String,
        val targetPackage: String,
        val entryClass: String,
        val createdFiles: List<String>,
        val errorMessage: String? = null
    )

    data class ProjectImportResult(
        val success: Boolean,
        val projectPath: String,
        val detectedPackageName: String?,
        val buildRoute: String, // "no_gradle" | "gradle" | "custom"
        val hasGradleWrapper: Boolean,
        val hasBuildScript: Boolean,
        val importedFilesCount: Int,
        val errorMessage: String? = null
    )

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun escapeJava(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    fun createProject(
        context: Context,
        targetDir: File,
        packageName: String,
        moduleName: String,
        moduleDescription: String = "LSPosed module developed on OmniBot",
        targetPackage: String = "*",
        customEntryClass: String? = null
    ): ProjectCreateResult {
        return runCatching {
            require(packageName.isNotBlank() && packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
                "无效的 Android 包名: $packageName"
            }

            // 新建目标存在且非空时必须明确拒绝，严禁静默覆盖已有目录
            if (targetDir.exists() && targetDir.list()?.isNotEmpty() == true) {
                throw IllegalStateException("新建目标目录已存在且非空，禁止覆盖：${targetDir.path}")
            }
            targetDir.mkdirs()
            val createdFiles = mutableListOf<String>()

            val entryClass = customEntryClass?.takeIf { it.isNotBlank() } ?: "$packageName.HookEntry"
            val simpleClassName = entryClass.substringAfterLast('.')

            // 1. AndroidManifest.xml (执行真实 XML 转义)
            val manifestContent = loadAssetTemplate(context, "$TEMPLATE_BASE_ASSET/AndroidManifest.xml")
                .replace("{{PACKAGE_NAME}}", escapeXml(packageName))
                .replace("{{MODULE_NAME}}", escapeXml(moduleName))
                .replace("{{MODULE_DESCRIPTION}}", escapeXml(moduleDescription))
                .replace("{{TARGET_PACKAGE}}", escapeXml(targetPackage))
            val manifestFile = File(targetDir, "AndroidManifest.xml")
            manifestFile.writeText(manifestContent)
            createdFiles.add(manifestFile.name)

            // 2. assets/xposed_init
            val assetsDir = File(targetDir, "assets")
            assetsDir.mkdirs()
            val xposedInitFile = File(assetsDir, "xposed_init")
            xposedInitFile.writeText(entryClass.trim() + "\n")
            createdFiles.add("assets/xposed_init")

            // 3. res/values/strings.xml & arrays.xml
            val resValuesDir = File(targetDir, "res/values")
            resValuesDir.mkdirs()
            val stringsContent = loadAssetTemplate(context, "$TEMPLATE_BASE_ASSET/res/values/strings.xml")
                .replace("{{MODULE_NAME}}", escapeXml(moduleName))
            File(resValuesDir, "strings.xml").writeText(stringsContent)
            createdFiles.add("res/values/strings.xml")

            val arraysContent = loadAssetTemplate(context, "$TEMPLATE_BASE_ASSET/res/values/arrays.xml")
                .replace("{{TARGET_PACKAGE}}", escapeXml(targetPackage))
            File(resValuesDir, "arrays.xml").writeText(arraysContent)
            createdFiles.add("res/values/arrays.xml")

            // 4. src/.../HookEntry.java (类名与 customEntryClass 保持严格一致，执行 Java 转义)
            val packagePath = packageName.replace('.', '/')
            val srcDir = File(targetDir, "src/$packagePath")
            srcDir.mkdirs()
            val entryFileName = "$simpleClassName.java"
            val rawHookTemplate = loadAssetTemplate(context, "$TEMPLATE_BASE_ASSET/src/HookEntry.java.template")
            val hookEntryContent = rawHookTemplate
                .replace("public class HookEntry", "public class $simpleClassName")
                .replace("{{PACKAGE_NAME}}", escapeJava(packageName))
                .replace("{{TARGET_PACKAGE}}", escapeJava(targetPackage))
            val hookFile = File(srcDir, entryFileName)
            hookFile.writeText(hookEntryContent)
            createdFiles.add("src/$packagePath/$entryFileName")

            // 5. libs/api-82.jar (官方真实库复制，失败抛错绝不吞异常)
            val libsDir = File(targetDir, "libs")
            libsDir.mkdirs()
            val apiJarFile = File(libsDir, "api-82.jar")
            context.assets.open("$TEMPLATE_BASE_ASSET/libs/api-82.jar").use { input ->
                apiJarFile.outputStream().use { output -> input.copyTo(output) }
            }
            check(apiJarFile.isFile && apiJarFile.length() > 0) { "复制官方 api-82.jar 依赖失败" }
            createdFiles.add("libs/api-82.jar")

            // 6. build-apk.sh
            val buildScriptFile = File(targetDir, "build-apk.sh")
            val buildScriptContent = loadAssetTemplate(context, "$TEMPLATE_BASE_ASSET/build-apk.sh")
            buildScriptFile.writeText(buildScriptContent)
            buildScriptFile.setExecutable(true, false)
            createdFiles.add("build-apk.sh")

            // 7. .gitignore
            val gitignoreContent = loadAssetTemplate(context, "$TEMPLATE_BASE_ASSET/gitignore.txt")
            val gitignoreFile = File(targetDir, ".gitignore")
            gitignoreFile.writeText(gitignoreContent)
            createdFiles.add(".gitignore")

            ProjectCreateResult(
                success = true,
                projectPath = targetDir.absolutePath,
                packageName = packageName,
                moduleName = moduleName,
                targetPackage = targetPackage,
                entryClass = entryClass,
                createdFiles = createdFiles
            )
        }.getOrElse { error ->
            ProjectCreateResult(
                success = false,
                projectPath = targetDir.absolutePath,
                packageName = packageName,
                moduleName = moduleName,
                targetPackage = targetPackage,
                entryClass = customEntryClass ?: "$packageName.HookEntry",
                createdFiles = emptyList(),
                errorMessage = error.message
            )
        }
    }

    fun importProject(
        context: Context,
        sourceDir: File,
        targetDir: File
    ): ProjectImportResult {
        return runCatching {
            val canonicalSource = sourceDir.canonicalFile
            val canonicalTarget = targetDir.canonicalFile

            require(canonicalSource.exists() && canonicalSource.isDirectory) {
                "源目录不存在或不是目录：${sourceDir.path}"
            }
            require(canonicalSource != canonicalTarget) {
                "源目录与导入目标目录不能相同：${sourceDir.path}"
            }
            require(!canonicalTarget.startsWith(canonicalSource) && !canonicalSource.startsWith(canonicalTarget)) {
                "源目录与目标目录不能存在嵌套包含关系"
            }

            // 目标目录已存在且非空时明确拒绝，严禁覆盖他人工程
            if (canonicalTarget.exists() && canonicalTarget.list()?.isNotEmpty() == true) {
                throw IllegalStateException("导入目标目录已存在且非空，禁止覆盖：${targetDir.path}")
            }
            canonicalTarget.mkdirs()

            // 全量复制，遇到任何错误立即终止抛错，绝不使用 OnErrorAction.SKIP 谎报成功
            canonicalSource.copyRecursively(canonicalTarget, overwrite = false) { file, exception ->
                throw IllegalStateException("复制文件失败 [${file.path}]: ${exception.message}", exception)
            }

            val fileCount = canonicalTarget.walkTopDown().filter { it.isFile }.count()

            // 识别构建入口与特征
            val hasGradlew = File(canonicalTarget, "gradlew").isFile
            val hasBuildGradle = File(canonicalTarget, "build.gradle").isFile || File(canonicalTarget, "build.gradle.kts").isFile
            val hasBuildApkSh = File(canonicalTarget, "build-apk.sh").isFile

            val detectedRoute = when {
                hasGradlew -> "gradle"
                hasBuildApkSh -> "no_gradle"
                hasBuildGradle -> "gradle"
                else -> "custom" // 未知构建入口保留 custom 并如实反馈，绝不自动注入普通模板篡改旧工程
            }

            // 尝试从 AndroidManifest.xml 探测包名
            val manifestFile = File(canonicalTarget, "AndroidManifest.xml")
            val detectedPackage = if (manifestFile.isFile) {
                val text = manifestFile.readText()
                Regex("package=[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
            } else null

            ProjectImportResult(
                success = true,
                projectPath = canonicalTarget.absolutePath,
                detectedPackageName = detectedPackage,
                buildRoute = detectedRoute,
                hasGradleWrapper = hasGradlew,
                hasBuildScript = hasBuildApkSh || hasGradlew || hasBuildGradle,
                importedFilesCount = fileCount
            )
        }.getOrElse { error ->
            ProjectImportResult(
                success = false,
                projectPath = targetDir.absolutePath,
                detectedPackageName = null,
                buildRoute = "unknown",
                hasGradleWrapper = false,
                hasBuildScript = false,
                importedFilesCount = 0,
                errorMessage = error.message
            )
        }
    }

    private fun loadAssetTemplate(context: Context, path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }
}
