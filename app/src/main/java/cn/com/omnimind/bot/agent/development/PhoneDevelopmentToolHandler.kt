package cn.com.omnimind.bot.agent.development

import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ArtifactAction
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.AgentCapabilityToolDefinition
import cn.com.omnimind.bot.agent.tool.handlers.SharedHelper
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * 手机端本地开发闭环工具处理器 (F10-F16, F18)。
 * 支持从环境检查、SDK下载校验、极速本地构建签名、LSPosed模块管理、多DEX分析、运行时探测到真机部署验证的全流程。
 */
class PhoneDevelopmentToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager,
    private val scope: CoroutineScope
) : ToolHandler {

    override val toolNames: Set<String> = companionToolNames

    companion object {
        const val PRIMARY_TOOL_NAME = "phone_development"
        val companionToolNames: Set<String> = setOf(PRIMARY_TOOL_NAME)

        /**
         * 纯数据定义，供父层 BuiltInAgentCapabilityModule 挂载。
         * 单一 phone_development 入口，避免多套工具表与命名混乱。
         */
        val definitions: List<AgentCapabilityToolDefinition> = listOf(
            buildPhoneDevelopmentDefinition()
        )

        private fun buildPhoneDevelopmentDefinition(): AgentCapabilityToolDefinition {
            val schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("action") {
                        put("type", "string")
                        put("description", "要执行的手机开发动作。")
                        putJsonArray("enum") {
                            add(JsonPrimitive("dev_env_status"))
                            add(JsonPrimitive("android_sdk_install"))
                            add(JsonPrimitive("project_create"))
                            add(JsonPrimitive("project_import"))
                            add(JsonPrimitive("project_build"))
                            add(JsonPrimitive("apk_analyze"))
                            add(JsonPrimitive("probe_module_generate"))
                            add(JsonPrimitive("probe_clean"))
                            add(JsonPrimitive("lsposed_deploy"))
                            add(JsonPrimitive("guidance"))
                        }
                    }
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "模块包名或目标 App 包名。")
                    }
                    putJsonObject("moduleName") {
                        put("type", "string")
                        put("description", "模块显示名称。")
                    }
                    putJsonObject("moduleDescription") {
                        put("type", "string")
                        put("description", "模块简介。")
                    }
                    putJsonObject("targetPackage") {
                        put("type", "string")
                        put("description", "Hook 的目标应用包名，默认 '*'。")
                    }
                    putJsonObject("platform") {
                        put("type", "integer")
                        put("description", "Android SDK API 级别，例如 35。")
                    }
                    putJsonObject("withGradle") {
                        put("type", "boolean")
                        put("description", "安装工具链时是否一并安装 Gradle。")
                    }
                    putJsonObject("sourcePath") {
                        put("type", "string")
                        put("description", "导入工程的源路径或目标 APK 文件路径。")
                    }
                    putJsonObject("projectPath") {
                        put("type", "string")
                        put("description", "工作区中的工程相对路径或绝对路径。")
                    }
                    putJsonObject("buildTask") {
                        put("type", "string")
                        put("description", "Gradle 构建任务名称，如 ':app:assembleDebug' 或自定义任务，默认 'assembleDebug'。")
                    }
                    putJsonObject("route") {
                        put("type", "string")
                        put("description", "构建路线：'auto' | 'no_gradle' | 'gradle'。")
                    }
                    putJsonObject("clean") {
                        put("type", "boolean")
                        put("description", "构建前是否清理旧产物。")
                    }
                    putJsonObject("apkPath") {
                        put("type", "string")
                        put("description", "待部署的 APK 文件绝对或工作区相对路径。")
                    }
                    putJsonObject("reloadTarget") {
                        put("type", "boolean")
                        put("description", "部署后是否强制重启目标应用进程使模块生效，默认 true。")
                    }
                    putJsonObject("targetClasses") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "探测的目标类列表。")
                    }
                    putJsonObject("targetMethods") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "探测的目标方法列表。")
                    }
                    putJsonObject("searchKeywords") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "APK 静态符号搜索关键词。")
                    }
                    putJsonObject("probePackageName") {
                        put("type", "string")
                        put("description", "清理探测模块时指定的包名。")
                    }
                    putJsonObject("skillId") {
                        put("type", "string")
                        put("description", "要显式读取的技能手册 ID，如 'phone-development' 或 'lsposed-develop'。")
                    }
                }
                putJsonArray("required") {
                    add(JsonPrimitive("action"))
                }
            }
            return AgentCapabilityToolDefinition(
                name = PRIMARY_TOOL_NAME,
                displayName = "手机开发动作 (Phone Development)",
                description = "在手机本地执行 LSPosed 模块开发、编译构建、目标 APK 逆向分析、运行时探测与部署重载闭环。",
                parameters = schema,
                toolType = "action"
            )
        }

    }

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        val action = if (toolName == PRIMARY_TOOL_NAME) {
            args["action"]?.jsonPrimitive?.contentOrNull ?: "dev_env_status"
        } else {
            toolName
        }

        toolHandle.throwIfStopRequested()

        return try {
            when (action) {
                "dev_env_status", "status" -> executeDevEnvStatus(action, callback, toolHandle)
                "android_sdk_install", "sdk_install" -> executeAndroidSdkInstall(action, args, callback, toolHandle)
                "project_create" -> executeProjectCreate(action, args, env, callback, toolHandle)
                "project_import" -> executeProjectImport(action, args, env, callback, toolHandle)
                "project_build", "build" -> executeProjectBuild(action, args, env, callback, toolHandle)
                "apk_analyze", "analyze" -> executeApkAnalyze(action, args, env, callback, toolHandle)
                "probe_module_generate", "probe_generate" -> executeProbeGenerate(action, args, env, callback, toolHandle)
                "probe_clean" -> executeProbeClean(action, args, env, callback, toolHandle)
                "lsposed_deploy", "deploy" -> executeLsposedDeploy(action, args, env, callback, toolHandle)
                "guidance" -> executeGuidance(action, args)
                else -> ToolExecutionResult.Error(toolName, helper.localized("不支持的动作：$action"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.errorResult(toolName, e.message, "手机开发操作执行失败")
        }
    }

    private suspend fun executeDevEnvStatus(
        action: String,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        helper.reportToolProgress(callback, action, "正在检查编译工具链与 Android SDK 状态", toolHandle = toolHandle)
        val status = AndroidSdkManager.inspectToolchain(helper.context)
        val payload = mapOf(
            "action" to action,
            "architecture" to status.architecture,
            "jdkInstalled" to status.jdkInstalled,
            "jdkVersion" to status.jdkVersion,
            "aapt2Available" to status.aapt2Available,
            "d8Available" to status.d8Available,
            "apksignerAvailable" to status.apksignerAvailable,
            "keytoolAvailable" to status.keytoolAvailable,
            "installedPlatforms" to status.installedPlatforms,
            "isReadyForBuild" to status.isReadyForBuild,
            "details" to status.details
        )
        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (status.isReadyForBuild) "开发工具链就绪 (${status.architecture})" else "开发工具链尚未就绪，需安装依赖",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = true
        )
    }

    private suspend fun executeAndroidSdkInstall(
        action: String,
        args: JsonObject,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val platformApi = args["platform"]?.jsonPrimitive?.intOrNull ?: AndroidSdkManager.DEFAULT_API
        val withGradle = args["withGradle"]?.jsonPrimitive?.booleanOrNull ?: false

        helper.reportToolProgress(
            callback,
            action,
            "正在安装 Alpine 编译工具链并下载 Google 平台 API $platformApi android.jar",
            toolHandle = toolHandle
        )

        var lastOutput = ""
        val success = AndroidSdkManager.installToolchainInAlpine(
            context = helper.context,
            platformApi = platformApi,
            withGradle = withGradle,
            onOutput = { output -> lastOutput = output }
        )

        val payload = mapOf(
            "action" to action,
            "platformApi" to platformApi,
            "withGradle" to withGradle,
            "success" to success,
            "terminalLog" to lastOutput.takeLast(1000)
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (success) "工具链与 Android SDK (API $platformApi) 安装成功" else "工具链安装失败",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = success
        )
    }

    private suspend fun executeProjectCreate(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 packageName"))
        val moduleName = args["moduleName"]?.jsonPrimitive?.contentOrNull ?: "LSPosedModule"
        val moduleDesc = args["moduleDescription"]?.jsonPrimitive?.contentOrNull ?: "LSPosed module developed on OmniBot"
        val targetPackage = args["targetPackage"]?.jsonPrimitive?.contentOrNull ?: "*"
        val projectRelativePath = args["projectPath"]?.jsonPrimitive?.contentOrNull ?: moduleName.replace(' ', '_')

        helper.reportToolProgress(callback, action, "正在生成 LSPosed 模块工程：$packageName", toolHandle = toolHandle)

        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val projectDir = File(workspaceDir, projectRelativePath)
        val result = LsposedProjectTemplateService.createProject(
            context = helper.context,
            targetDir = projectDir,
            packageName = packageName,
            moduleName = moduleName,
            moduleDescription = moduleDesc,
            targetPackage = targetPackage
        )

        val payload = mapOf(
            "action" to action,
            "success" to result.success,
            "projectPath" to result.projectPath,
            "packageName" to result.packageName,
            "moduleName" to result.moduleName,
            "targetPackage" to result.targetPackage,
            "entryClass" to result.entryClass,
            "createdFiles" to result.createdFiles,
            "errorMessage" to result.errorMessage
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (result.success) "LSPosed 工程已创建：$packageName" else "工程创建失败：${result.errorMessage}",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = result.success
        )
    }

    private suspend fun executeProjectImport(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val sourcePath = args["sourcePath"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 sourcePath"))
        val projectRelativePath = args["projectPath"]?.jsonPrimitive?.contentOrNull ?: File(sourcePath).name

        helper.reportToolProgress(callback, action, "正在导入已有工程：$sourcePath", toolHandle = toolHandle)

        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val sourceDir = if (File(sourcePath).isAbsolute) File(sourcePath) else File(workspaceDir, sourcePath)
        val targetDir = File(workspaceDir, projectRelativePath)
        val result = LsposedProjectTemplateService.importProject(
            context = helper.context,
            sourceDir = sourceDir,
            targetDir = targetDir
        )

        val payload = mapOf(
            "action" to action,
            "success" to result.success,
            "projectPath" to result.projectPath,
            "detectedPackageName" to result.detectedPackageName,
            "buildRoute" to result.buildRoute,
            "hasGradleWrapper" to result.hasGradleWrapper,
            "importedFilesCount" to result.importedFilesCount,
            "errorMessage" to result.errorMessage
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (result.success) "工程导入成功，构建路线：${result.buildRoute}" else "工程导入失败：${result.errorMessage}",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = result.success
        )
    }

    private suspend fun executeProjectBuild(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val projectRelativePath = args["projectPath"]?.jsonPrimitive?.contentOrNull ?: "."
        val buildTask = args["buildTask"]?.jsonPrimitive?.contentOrNull ?: "assembleDebug"
        val route = args["route"]?.jsonPrimitive?.contentOrNull ?: "auto"
        val clean = args["clean"]?.jsonPrimitive?.booleanOrNull ?: false

        helper.reportToolProgress(callback, action, "正在手机本地执行编译与签名打包", toolHandle = toolHandle)

        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val projectDir = if (File(projectRelativePath).isAbsolute) {
            File(projectRelativePath)
        } else {
            File(workspaceDir, projectRelativePath)
        }
        val result = LsposedManagementBridge.buildProject(
            context = helper.context,
            projectDir = projectDir,
            buildTask = buildTask,
            requestedRoute = route,
            clean = clean
        )

        val payload = mapOf(
            "action" to action,
            "success" to result.success,
            "routeUsed" to result.routeUsed,
            "apkPath" to result.apkPath,
            "fileSizeBytes" to result.fileSizeBytes,
            "sha256" to result.sha256,
            "signatureVerified" to result.signatureVerified,
            "errorMessage" to result.errorMessage,
            "stdout" to result.stdout.takeLast(1000)
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (result.success) "APK 构建签名成功 (${result.routeUsed})：${result.apkPath}" else "APK 构建失败：${result.errorMessage}",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = result.success
        )
    }

    private suspend fun executeApkAnalyze(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val targetInput = args["packageName"]?.jsonPrimitive?.contentOrNull
            ?: args["sourcePath"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 packageName 或 sourcePath"))

        val keywords = args["searchKeywords"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        helper.reportToolProgress(callback, action, "正在静态分析 APK 与多 DEX 符号：$targetInput", toolHandle = toolHandle)

        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val (apkFile, isExtracted) = if (targetInput.endsWith(".apk") && File(targetInput).isFile) {
            File(targetInput) to false
        } else {
            val pkgRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
            if (!pkgRegex.matches(targetInput)) {
                return ToolExecutionResult.Error(action, helper.localized("无效的应用包名或 APK 路径：$targetInput"))
            }

            // 通过 Root 提取该已安装包名对应的 base.apk
            val extractedDir = File(workspaceDir, "extracted_apks")
            extractedDir.mkdirs()
            val destApk = File(extractedDir, "${targetInput.replace('/', '_')}.apk")
            if (destApk.exists()) {
                destApk.delete()
            }

            val access = PrivilegedCapabilityManager.get(helper.context).reserve("apk_analyze", null, env.acpSessionId)
            if (!access.status.isGranted()) {
                return ToolExecutionResult.Error(action, helper.localized("高权限能力不可用，请在权限设置中启用 Root。"))
            }

            // 严格通过 ACP 权限请求通道获取授权 (Fail-Closed)
            val allowed = env.permissionRequester?.requestPermission(
                toolCallId = toolHandle.toolCallId,
                title = if (helper.isEnglishLocale) "Root: Extract APK" else "Root：提取目标应用 APK",
                detail = "pm path and copy base.apk for $targetInput",
                action = "shell.exec",
                targetResource = targetInput
            ) == true

            if (!allowed) {
                return ToolExecutionResult.Error(action, helper.localized("高权限操作未获得用户授权，无法提取 APK。"))
            }

            val quotedTarget = "'" + targetInput.replace("'", "'\"'\"'") + "'"
            val pathResult = access.executeRawShell("pm path $quotedTarget", confirmed = true)
            val remotePath = pathResult.stdout.lines().firstOrNull { it.startsWith("package:") }
                ?.substringAfter("package:")?.trim()
            if (remotePath.isNullOrBlank()) {
                return ToolExecutionResult.Error(action, helper.localized("未能通过 pm path 定位应用安装包：$targetInput"))
            }

            val quotedRemote = "'" + remotePath.replace("'", "'\"'\"'") + "'"
            val quotedDest = "'" + destApk.absolutePath.replace("'", "'\"'\"'") + "'"
            val copyResult = access.executeRawShell("cp $quotedRemote $quotedDest && chmod 644 $quotedDest", confirmed = true)
            if (!copyResult.success || !destApk.isFile || destApk.length() == 0L) {
                if (destApk.exists()) destApk.delete()
                return ToolExecutionResult.Error(action, helper.localized("提取应用 APK 失败：${copyResult.message}"))
            }
            destApk to true
        }

        val analysis = ApkAnalysisService.analyzeApk(
            apkFile = apkFile,
            context = helper.context,
            searchKeywords = keywords,
            isExtractedFromDevice = isExtracted
        )

        val payload = mapOf(
            "action" to action,
            "apkPath" to analysis.apkPath,
            "fileSizeBytes" to analysis.fileSizeBytes,
            "sha256" to analysis.sha256,
            "isExtractedFromDevice" to analysis.isExtractedFromDevice,
            "packageName" to analysis.manifest.packageName,
            "versionCode" to analysis.manifest.versionCode,
            "versionName" to analysis.manifest.versionName,
            "minSdkVersion" to analysis.manifest.minSdkVersion,
            "targetSdkVersion" to analysis.manifest.targetSdkVersion,
            "applicationClass" to analysis.manifest.applicationClass,
            "componentsCount" to analysis.manifest.components.size,
            "components" to analysis.manifest.components.take(20).map { mapOf("type" to it.type, "name" to it.name, "exported" to it.exported) },
            "dexFileCount" to analysis.dexFileCount,
            "totalClassesCount" to analysis.totalClassesCount,
            "candidateClassesCount" to analysis.candidateClasses.size,
            "candidateClasses" to analysis.candidateClasses.take(20).map { c ->
                mapOf(
                    "className" to c.className,
                    "methods" to c.methods.take(10).map { it.fullSignature }
                )
            }
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = "APK 逆向分析完成：${analysis.manifest.packageName} (DEX: ${analysis.dexFileCount}, 类: ${analysis.totalClassesCount})",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = true
        )
    }

    private suspend fun executeProbeGenerate(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val targetPackage = args["targetPackage"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 targetPackage"))
        val targetClasses = args["targetClasses"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        val targetMethods = args["targetMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        if (targetClasses.isEmpty()) {
            return ToolExecutionResult.Error(action, helper.localized("targetClasses 列表不能为空"))
        }

        helper.reportToolProgress(callback, action, "正在生成运行时临时探测模块：$targetPackage", toolHandle = toolHandle)
        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val result = ProbeModuleService.generateProbeModule(
            context = helper.context,
            workspaceDir = workspaceDir,
            targetPackage = targetPackage,
            targetClasses = targetClasses,
            targetMethods = targetMethods
        )

        val payload = mapOf(
            "action" to action,
            "success" to result.success,
            "probeProjectDir" to result.probeProjectDir,
            "probePackageName" to result.probePackageName,
            "targetPackage" to result.targetPackage,
            "hookedClasses" to result.hookedClasses,
            "errorMessage" to result.errorMessage
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (result.success) "临时探测模块已生成：${result.probePackageName}" else "探测模块生成失败：${result.errorMessage}",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = result.success
        )
    }

    private suspend fun executeProbeClean(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val probePackageName = args["probePackageName"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 probePackageName"))
        val targetPackage = args["targetPackage"]?.jsonPrimitive?.contentOrNull

        // 严格通过 ACP 权限请求通道获取授权 (Fail-Closed，无 requester 必拒)
        val allowed = env.permissionRequester?.requestPermission(
            toolCallId = toolHandle.toolCallId,
            title = if (helper.isEnglishLocale) "Root: Clean Probe Module" else "Root：清理探测模块",
            detail = "Uninstall probe module and clean scope for $probePackageName",
            action = "package_control.uninstall",
            targetResource = probePackageName
        ) == true

        if (!allowed) {
            return ToolExecutionResult.Error(action, helper.localized("高权限操作未获得用户授权或权限请求通道不可用，操作未执行。"))
        }

        helper.reportToolProgress(callback, action, "正在清理探测模块与临时产物：$probePackageName", toolHandle = toolHandle)

        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val probeDir = workspaceDir.listFiles()?.firstOrNull { dir ->
            if (!dir.isDirectory) return@firstOrNull false
            val manifest = File(dir, "probe_manifest.json")
            if (!manifest.isFile) return@firstOrNull false
            val json = runCatching { org.json.JSONObject(manifest.readText()) }.getOrNull()
            json?.optString("probePackageName") == probePackageName
        }

        val cleanResult = ProbeModuleService.cleanProbe(
            context = helper.context,
            probePackageName = probePackageName,
            targetPackage = targetPackage,
            probeProjectDir = probeDir,
            confirmed = true,
            acpSessionId = env.acpSessionId
        )

        val payload = mapOf(
            "action" to action,
            "probePackageName" to probePackageName,
            "success" to cleanResult.success,
            "uninstalled" to cleanResult.uninstalled,
            "projectFilesCleaned" to cleanResult.projectFilesCleaned,
            "message" to cleanResult.message
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = "探测清理完成：卸载=${cleanResult.uninstalled}, 文件清理=${cleanResult.projectFilesCleaned}",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = cleanResult.success
        )
    }

    private suspend fun executeLsposedDeploy(
        action: String,
        args: JsonObject,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        val apkPath = args["apkPath"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 apkPath"))
        val targetPackage = args["targetPackage"]?.jsonPrimitive?.contentOrNull
            ?: return ToolExecutionResult.Error(action, helper.localized("缺少 targetPackage"))
        val reloadTarget = args["reloadTarget"]?.jsonPrimitive?.booleanOrNull ?: true
        val workspaceDir = File(env.workspaceDescriptor.androidRootPath)
        val apkFile = if (File(apkPath).isAbsolute) File(apkPath) else File(workspaceDir, apkPath)
        if (!apkFile.isFile) {
            return ToolExecutionResult.Error(action, helper.localized("APK 文件不存在：${apkFile.absolutePath}"))
        }

        val modulePackage = helper.context.packageManager
            .getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
            ?: ApkAnalysisService.analyzeApk(apkFile, helper.context).manifest.packageName
            ?: return ToolExecutionResult.Error(action, helper.localized("无法解析 APK 包名，无法安全授权"))

        val derivedScopes = listOf(
            buildJsonObject {
                put("action", "package_control.install")
                put("packageName", modulePackage)
                put("path", apkFile.canonicalPath)
            },
            buildJsonObject {
                put("action", "xposed_control.scope_set")
                put("packageName", targetPackage)
                put("moduleId", modulePackage)
            },
            buildJsonObject {
                put("action", "xposed_control.target_reload")
                put("packageName", targetPackage)
            },
            buildJsonObject {
                put("action", "package_control.force_stop")
                put("packageName", targetPackage)
            },
            buildJsonObject {
                put("action", "device_control.reboot")
                put("allowSystemModifications", true)
            },
            buildJsonObject {
                put("action", "system.reboot")
                put("allowSystemModifications", true)
            },
        )
        val rawInput = buildJsonObject {
            put("action", "package_control.install")
            put("packageName", modulePackage)
            put("path", apkFile.canonicalPath)
            put("targetPackage", targetPackage)
            put("scopes", JsonArray(derivedScopes))
        }

        val title = if (helper.isEnglishLocale) {
            "Root: Deploy LSPosed Module (with necessary reboots)"
        } else {
            "Root：部署 LSPosed 模块（含必要目标App与系统重启）"
        }
        val detail = if (helper.isEnglishLocale) {
            "Deploy module $modulePackage for target $targetPackage; grants necessary target reloads and system reboot for this task"
        } else {
            "部署模块 $modulePackage 并配置作用域 $targetPackage；批准本任务将同时允许必要的目标应用重载与整机重启"
        }

        // 严格通过 ACP 权限请求通道获取授权 (Fail-Closed，无 requester 必拒)
        val allowed = env.permissionRequester?.requestPermission(
            toolCallId = toolHandle.toolCallId,
            title = title,
            detail = detail,
            action = "package_control.install",
            targetResource = modulePackage,
            rawInput = rawInput,
        ) == true

        if (!allowed) {
            return ToolExecutionResult.Error(action, helper.localized("高权限操作未获得用户授权或权限请求通道不可用，操作未执行。"))
        }

        helper.reportToolProgress(callback, action, "正在静默安装 APK 并配置 LSPosed 作用域", toolHandle = toolHandle)

        val deployResult = LsposedManagementBridge.deployModule(
            context = helper.context,
            apkFile = apkFile,
            targetPackage = targetPackage,
            reloadTarget = reloadTarget,
            confirmed = true,
            acpSessionId = env.acpSessionId
        )
        val payload = mapOf(
            "action" to action,
            "apkPath" to deployResult.apkPath,
            "modulePackageName" to deployResult.modulePackageName,
            "targetPackage" to deployResult.targetPackage,
            "installed" to deployResult.installed,
            "moduleEnabled" to deployResult.moduleEnabled,
            "scopeConfigured" to deployResult.scopeConfigured,
            "targetReloaded" to deployResult.targetReloaded,
            "configVerified" to deployResult.configVerified,
            "hookVerified" to deployResult.hookVerified,
            "message" to deployResult.message
        )

        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = if (deployResult.success) "LSPosed 模块部署成功：$targetPackage" else "部署失败：${deployResult.message}",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = deployResult.success
        )
    }

    private fun executeGuidance(
        action: String,
        args: JsonObject
    ): ToolExecutionResult {
        val skillId = args["skillId"]?.jsonPrimitive?.contentOrNull ?: "phone-development"
        val content = PhoneDevelopmentSkills.loadSkillText(helper.context, skillId)
            ?: return ToolExecutionResult.Error(
                action,
                helper.localized("未找到指定的开发手册：$skillId。可用手册: phone-development, lsposed-develop")
            )

        val payload = mapOf(
            "action" to action,
            "skillId" to skillId,
            "content" to content
        )
        return ToolExecutionResult.ContextResult(
            toolName = action,
            summaryText = "已读取开发手册：$skillId",
            previewJson = helper.encodeLocalizedPayload(payload),
            rawResultJson = helper.encodeLocalizedPayload(payload),
            success = true
        )
    }
}
