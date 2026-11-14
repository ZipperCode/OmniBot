package cn.com.omnimind.baselib.privileged.management

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure function parser for Vector CLI JSON envelope responses and logcat threadtime framework evidence.
 * Spec: JingMatrix/Vector@093ea599bfa2b50c156da0af53e2e53576e88434
 * - Vector CLI default output is ASCII table; `--json` outputs envelope `{ "success": true, "data": [ ... ], "error": null }`.
 * - Fail-closed: non-json, ASCII table, bare array, or success != true returns null (unknown).
 * - Logcat threadtime: extracts PID and Tag, binds active target PID, requires exact framework tag
 *   (LSPosed-Bridge / Vector / XposedBridge) and full package token matching.
 */
internal object XposedStatusParser {
    val ALLOWED_FRAMEWORK_TAGS: Set<String> = setOf("LSPosed-Bridge", "Vector", "XposedBridge")

    data class XposedModuleEntry(
        val packageName: String,
        val enabled: Boolean,
        val status: String,
        val raw: JsonObject? = null,
    )

    data class XposedScopeEntry(
        val appPackage: String,
        val userId: Int,
        val raw: JsonObject? = null,
    )

    data class XposedFrameworkEvidence(
        val module: String,
        val target: String,
        val pid: String,
        val observedAt: String,
        val tag: String,
    )

    private val THREADTIME_REGEX = Regex(
        """^(\d{2,4}-\d{2}(?:-\d{2})?)\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEAF])\s+([^:]+):\s*(.*)$"""
    )

    fun parseModulesLs(output: String): List<XposedModuleEntry>? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val root = Json.parseToJsonElement(trimmed)
            if (root !is JsonObject) return null
            val success = root["success"]?.jsonPrimitive?.booleanOrNull
            if (success != true) return null
            val data = root["data"] as? JsonArray ?: return null
            data.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val pkg = obj["PACKAGE"]?.jsonPrimitive?.contentOrNull
                    ?: obj["package"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val status = obj["STATUS"]?.jsonPrimitive?.contentOrNull
                    ?: obj["status"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val enabled = status.equals("enabled", ignoreCase = true)
                XposedModuleEntry(
                    packageName = pkg,
                    enabled = enabled,
                    status = status,
                    raw = obj,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun parseScopeLs(output: String): List<XposedScopeEntry>? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val root = Json.parseToJsonElement(trimmed)
            if (root !is JsonObject) return null
            val success = root["success"]?.jsonPrimitive?.booleanOrNull
            if (success != true) return null
            val data = root["data"] as? JsonArray ?: return null
            data.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val appPkg = obj["APP_PACKAGE"]?.jsonPrimitive?.contentOrNull
                    ?: obj["package"]?.jsonPrimitive?.contentOrNull
                    ?: obj["app_pkg_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val userId = obj["USER_ID"]?.jsonPrimitive?.intOrNull
                    ?: obj["USER_ID"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: obj["user_id"]?.jsonPrimitive?.intOrNull
                    ?: obj["user_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: 0
                XposedScopeEntry(
                    appPackage = appPkg,
                    userId = userId,
                    raw = obj,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun containsPackageToken(text: String, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val regex = Regex("""(?<![a-zA-Z0-9_.])""" + Regex.escape(packageName) + """(?![a-zA-Z0-9_.])""")
        return regex.containsMatchIn(text)
    }

    fun findFrameworkEvidence(
        logcatOutput: String,
        modulePkgName: String,
        targetPackage: String,
        activePids: Set<String>
    ): XposedFrameworkEvidence? {
        if (activePids.isEmpty() || modulePkgName.isBlank() || targetPackage.isBlank()) return null

        var latestEvidence: XposedFrameworkEvidence? = null
        for (line in logcatOutput.lineSequence()) {
            val match = THREADTIME_REGEX.matchEntire(line.trim()) ?: continue
            val date = match.groupValues[1]
            val time = match.groupValues[2]
            val pid = match.groupValues[3]
            val tag = match.groupValues[6].trim()
            val message = match.groupValues[7]

            if (tag !in ALLOWED_FRAMEWORK_TAGS) continue
            if (pid !in activePids) continue
            if (!containsPackageToken(message, modulePkgName)) continue
            if (!containsPackageToken(message, targetPackage)) continue

            latestEvidence = XposedFrameworkEvidence(
                module = modulePkgName,
                target = targetPackage,
                pid = pid,
                observedAt = "$date $time",
                tag = tag
            )
        }
        return latestEvidence
    }
}
