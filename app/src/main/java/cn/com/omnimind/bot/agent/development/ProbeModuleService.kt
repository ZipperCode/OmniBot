package cn.com.omnimind.bot.agent.development

import android.content.Context
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 运行时临时探测模块生命周期服务 (F12)。
 * 自动生成临时探测 LSPosed 模块、收集脱敏运行时证据，并在定位完成后执行收尾清理。
 */
object ProbeModuleService {

    data class ProbeGenerationResult(
        val success: Boolean,
        val probeProjectDir: String,
        val probePackageName: String,
        val targetPackage: String,
        val hookedClasses: List<String>,
        val errorMessage: String? = null
    )

    data class ProbeCleanResult(
        val success: Boolean,
        val scopeRemoved: Boolean,
        val uninstalled: Boolean,
        val projectFilesCleaned: Boolean,
        val message: String
    )

    private val packageRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val classRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*\\.[a-zA-Z][a-zA-Z0-9_]*$")
    private val methodRegex = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*$")

    fun generateProbeModule(
        context: Context,
        workspaceDir: File,
        targetPackage: String,
        targetClasses: List<String>,
        targetMethods: List<String> = emptyList(),
        probePackageName: String = "cn.com.omnimind.probe.${targetPackage.replace('.', '_')}"
    ): ProbeGenerationResult {
        return runCatching {
            require(packageRegex.matches(targetPackage)) { "目标应用包名不是合法的 Java 包名：$targetPackage" }
            require(packageRegex.matches(probePackageName)) { "探测模块包名不是合法的 Java 包名：$probePackageName" }
            require(targetClasses.isNotEmpty()) { "探测目标类列表不能为空" }
            targetClasses.forEach { clazz ->
                require(classRegex.matches(clazz)) { "目标类全限定名不是合法的 Java 类标识符：$clazz" }
            }
            targetMethods.forEach { method ->
                require(methodRegex.matches(method)) { "目标方法名不是合法的 Java 方法标识符：$method" }
            }

            val probeProjectDir = File(workspaceDir, "probe_${targetPackage.replace('.', '_')}")
            if (probeProjectDir.exists() && probeProjectDir.isDirectory) {
                val manifestFile = File(probeProjectDir, "probe_manifest.json")
                val isOwned = manifestFile.isFile && runCatching {
                    JSONObject(manifestFile.readText()).optString("probePackageName") == probePackageName
                }.getOrDefault(false)

                if (!isOwned && (probeProjectDir.list()?.isNotEmpty() == true)) {
                    throw IllegalStateException("目标目录已存在且非当前任务所有的探测工程，禁止覆盖：${probeProjectDir.path}")
                }
            }
            probeProjectDir.mkdirs()

            // 1. 生成 Hook 注册语句块
            val hookRegistrations = buildString {
                targetClasses.forEach { clazz ->
                    append("        // Hooking class: ").append(clazz).append("\n")
                    append("        try {\n")
                    append("            Class<?> clz = XposedHelpers.findClassIfExists(\"").append(clazz).append("\", lpparam.classLoader);\n")
                    append("            if (clz != null) {\n")
                    if (targetMethods.isEmpty()) {
                        // Hook 目标类的所有公共/保护方法，打印其被调用证据
                        append("                for (java.lang.reflect.Method m : clz.getDeclaredMethods()) {\n")
                        append("                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;\n")
                        append("                    final String mName = m.getName();\n")
                        append("                    final String cName = \"").append(clazz).append("\";\n")
                        append("                    XposedBridge.hookMethod(m, new XC_MethodHook() {\n")
                        append("                        @Override\n")
                        append("                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {\n")
                        append("                            ProbeHookEntry.logCall(cName, mName, param, false);\n")
                        append("                        }\n")
                        append("                        @Override\n")
                        append("                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {\n")
                        append("                            ProbeHookEntry.logCall(cName, mName, param, true);\n")
                        append("                        }\n")
                        append("                    });\n")
                        append("                }\n")
                    } else {
                        targetMethods.forEach { method ->
                            // 使用 hookAllMethods 拦截该方法名的全部重载（支持带参数方法），避免无参匹配失效
                            append("                XposedBridge.hookAllMethods(clz, \"").append(method).append("\", new XC_MethodHook() {\n")
                            append("                    @Override\n")
                            append("                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {\n")
                            append("                        ProbeHookEntry.logCall(\"").append(clazz).append("\", \"").append(method).append("\", param, false);\n")
                            append("                    }\n")
                            append("                    @Override\n")
                            append("                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {\n")
                            append("                        ProbeHookEntry.logCall(\"").append(clazz).append("\", \"").append(method).append("\", param, true);\n")
                            append("                    }\n")
                            append("                });\n")
                        }
                    }
                    append("            } else {\n")
                    append("                Log.w(TAG, \"Class not found during probe init: ").append(clazz).append("\");\n")
                    append("            }\n")
                    append("        } catch (Throwable t) {\n")
                    append("            Log.e(TAG, \"Failed to hook ").append(clazz).append("\", t);\n")
                    append("        }\n")
                }
            }

            // 2. 解包标准工程骨架
            val createResult = LsposedProjectTemplateService.createProject(
                context = context,
                targetDir = probeProjectDir,
                packageName = probePackageName,
                moduleName = "OmniBotProbe_${targetPackage.substringAfterLast('.')}",
                moduleDescription = "Temporary runtime probe module for $targetPackage",
                targetPackage = targetPackage,
                customEntryClass = "$probePackageName.ProbeHookEntry"
            )
            check(createResult.success) { "初始化探测模块脚手架失败: ${createResult.errorMessage}" }

            // 3. 读取真实资产模板替换入口类（缺失模板必须明确失败，严禁假降级）
            val packagePath = probePackageName.replace('.', '/')
            val entryFile = File(probeProjectDir, "src/$packagePath/ProbeHookEntry.java")
            val probeTemplate = context.assets.open("phone-development/templates/probe-module/ProbeHookEntry.java.template")
                .bufferedReader().use { it.readText() }

            val finalSource = probeTemplate
                .replace("{{PACKAGE_NAME}}", probePackageName)
                .replace("{{TARGET_PACKAGE}}", targetPackage)
                .replace("{{HOOK_REGISTRATIONS}}", hookRegistrations)

            entryFile.writeText(finalSource)

            // 4. 持久化记录探测工程归属清单，杜绝误删他人目录
            val manifestRecord = File(probeProjectDir, "probe_manifest.json")
            manifestRecord.writeText("""{"probePackageName":"$probePackageName","targetPackage":"$targetPackage","createdAt":${System.currentTimeMillis()}}""")

            // 删除初始的 HookEntry.java
            val defaultHookEntry = File(probeProjectDir, "src/$packagePath/HookEntry.java")
            if (defaultHookEntry.exists()) {
                defaultHookEntry.delete()
            }

            ProbeGenerationResult(
                success = true,
                probeProjectDir = probeProjectDir.absolutePath,
                probePackageName = probePackageName,
                targetPackage = targetPackage,
                hookedClasses = targetClasses
            )
        }.getOrElse { error ->
            ProbeGenerationResult(
                success = false,
                probeProjectDir = "",
                probePackageName = probePackageName,
                targetPackage = targetPackage,
                hookedClasses = emptyList(),
                errorMessage = error.message
            )
        }
    }

    suspend fun cleanProbe(
        context: Context,
        probePackageName: String,
        targetPackage: String?,
        probeProjectDir: File?,
        ownerId: String = "probe_clean",
        confirmed: Boolean,
        acpSessionId: String? = null
    ): ProbeCleanResult = withContext(Dispatchers.IO) {
        if (!confirmed) {
            return@withContext ProbeCleanResult(
                success = false,
                scopeRemoved = false,
                uninstalled = false,
                projectFilesCleaned = false,
                message = "高权限清理操作未获授权。"
            )
        }

        val access = PrivilegedCapabilityManager.get(context).reserve(ownerId, null, acpSessionId)
        if (!access.status.isGranted()) {
            return@withContext ProbeCleanResult(
                success = false,
                scopeRemoved = false,
                uninstalled = false,
                projectFilesCleaned = false,
                message = "Root 权限不可用或已被拒绝。"
            )
        }

        val confirmedFlag = "true"
        var scopeRemoved = true
        var uninstalled = false
        var filesCleaned = false

        // 步骤 1：先停用并移除 LSPosed 作用域（若提供 targetPackage）
        if (targetPackage != null && targetPackage.isNotBlank()) {
            val scopeRes = access.executeAgentAction(
                action = "xposed_control.scope_set",
                arguments = mapOf(
                    "module_pkg_name" to probePackageName,
                    "target_packages" to targetPackage,
                    "operation" to "remove",
                    "confirmed" to confirmedFlag
                ),
                requiresConfirmation = false
            )
            scopeRemoved = scopeRes.success
            if (!scopeRemoved) {
                return@withContext ProbeCleanResult(
                    success = false,
                    scopeRemoved = false,
                    uninstalled = false,
                    projectFilesCleaned = false,
                    message = "第一步移除作用域 (xposed_control.scope_set) 失败: ${scopeRes.message}"
                )
            }
        }

        // 步骤 2：通过 Root 卸载探测模块
        val uninstallResult = access.executeAgentAction(
            action = "package_control.uninstall",
            arguments = mapOf(
                "package_name" to probePackageName,
                "confirmed" to confirmedFlag
            ),
            requiresConfirmation = false
        )
        uninstalled = uninstallResult.success

        // 步骤 3：卸载结果状态读回（幂等校验，确认包确已在系统中消失）
        if (!uninstalled) {
            val verifyRes = access.executeAgentAction(
                action = "package_control.verify_permission",
                arguments = mapOf(
                    "package_name" to probePackageName,
                    "permission" to "android.permission.INTERNET",
                    "confirmed" to confirmedFlag
                ),
                requiresConfirmation = false
            )
            if (verifyRes.code == "package_not_found" || verifyRes.message.contains("not found")) {
                uninstalled = true
            } else {
                return@withContext ProbeCleanResult(
                    success = false,
                    scopeRemoved = scopeRemoved,
                    uninstalled = false,
                    projectFilesCleaned = false,
                    message = "第二步卸载应用 (package_control.uninstall) 失败: ${uninstallResult.message}"
                )
            }
        }

        // 步骤 4：严格解析 probe_manifest.json 精确匹配所有权后清理目录
        if (probeProjectDir != null && probeProjectDir.exists() && probeProjectDir.isDirectory) {
            val manifestFile = File(probeProjectDir, "probe_manifest.json")
            val isOwned = manifestFile.isFile && runCatching {
                val json = JSONObject(manifestFile.readText())
                json.optString("probePackageName") == probePackageName &&
                    (targetPackage == null || json.optString("targetPackage") == targetPackage)
            }.getOrDefault(false)

            if (isOwned) {
                filesCleaned = probeProjectDir.deleteRecursively()
            } else {
                return@withContext ProbeCleanResult(
                    success = false,
                    scopeRemoved = scopeRemoved,
                    uninstalled = uninstalled,
                    projectFilesCleaned = false,
                    message = "第三步删除目录失败：目录 ${probeProjectDir.path} 的 probe_manifest 归属不匹配，已拒绝删除。"
                )
            }
        } else {
            filesCleaned = true // 目录原本即不存在，幂等视为已清理
        }

        val allPassed = scopeRemoved && uninstalled && filesCleaned
        ProbeCleanResult(
            success = allPassed,
            scopeRemoved = scopeRemoved,
            uninstalled = uninstalled,
            projectFilesCleaned = filesCleaned,
            message = if (allPassed) "探测模块与工程清理完成：作用域移除=$scopeRemoved, 卸载=$uninstalled, 文件清理=$filesCleaned"
                else "探测清理未完全成功"
        )
    }
}
