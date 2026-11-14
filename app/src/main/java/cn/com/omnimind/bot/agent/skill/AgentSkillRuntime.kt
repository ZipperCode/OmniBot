package cn.com.omnimind.bot.agent

import android.content.Context
import android.content.res.AssetManager
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.termux.TermuxCommandBuilder
import cn.com.omnimind.bot.update.ApkDownloadSource
import cn.com.omnimind.bot.update.AppUpdateManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

private const val BUILTIN_SKILL_MANIFEST_ASSET = "builtin_skills/manifest.json"
private const val BUILTIN_SOURCE = "builtin"
private const val OFFICIAL_SOURCE = "official"
private const val USER_SOURCE = "user"
private const val INSTALL_STATE_INSTALLED = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN = "removed_builtin"
private const val SKILL_REGISTRY_FILE_NAME = ".skill_registry.json"
private const val OFFICIAL_SKILLS_GITHUB_REPOSITORY_URL = "https://github.com/omnimind-ai/OmniBotSkills"
private const val OFFICIAL_SKILLS_CNB_REPOSITORY_URL = "https://cnb.cool/o.a/OmniBotSkills"
private const val OFFICIAL_SKILLS_DIRECTORY_NAME = "OmniBotSkills"
private val RETIRED_BUILTIN_SKILL_IDS = setOf("hatch-pet")
internal val SKILL_INDEX_LOCK = Any()

private data class BuiltinSkillManifest(
    val skills: List<BuiltinSkillAsset> = emptyList()
)

private data class BuiltinSkillAsset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val descriptionEn: String? = null,
    val assetPath: String = "",
    val hasScripts: Boolean = false,
    val hasReferences: Boolean = false,
    val hasAssets: Boolean = false,
    val hasEvals: Boolean = false
)

internal data class SkillRegistryEntry(
    val enabled: Boolean = true,
    val source: String = USER_SOURCE,
    val installState: String = INSTALL_STATE_INSTALLED,
    val verificationStatus: String = "unverified",
    val revisionId: String? = null,
    val previousRevisionId: String? = null,
    val revision: Long = 1L
)

internal class SkillRegistryEntryDeserializer : JsonDeserializer<SkillRegistryEntry> {
    override fun deserialize(
        json: JsonElement?,
        typeOfT: java.lang.reflect.Type?,
        context: com.google.gson.JsonDeserializationContext?
    ): SkillRegistryEntry {
        if (json == null || !json.isJsonObject) {
            throw JsonParseException("SkillRegistryEntry 必须为 JsonObject")
        }
        val obj = json.asJsonObject
        val enabled = if (obj.has("enabled") && !obj.get("enabled").isJsonNull) {
            obj.get("enabled").asBoolean
        } else {
            true
        }
        val source = if (obj.has("source") && !obj.get("source").isJsonNull) {
            obj.get("source").asString.ifBlank { USER_SOURCE }
        } else {
            USER_SOURCE
        }
        val installState = if (obj.has("installState") && !obj.get("installState").isJsonNull) {
            obj.get("installState").asString.ifBlank { INSTALL_STATE_INSTALLED }
        } else {
            INSTALL_STATE_INSTALLED
        }
        val verificationStatus = if (obj.has("verificationStatus") && !obj.get("verificationStatus").isJsonNull) {
            obj.get("verificationStatus").asString.ifBlank { "unverified" }
        } else {
            "unverified"
        }
        val revisionId = if (obj.has("revisionId") && !obj.get("revisionId").isJsonNull) {
            obj.get("revisionId").asString.ifBlank { null }
        } else {
            null
        }
        val previousRevisionId = if (obj.has("previousRevisionId") && !obj.get("previousRevisionId").isJsonNull) {
            obj.get("previousRevisionId").asString.ifBlank { null }
        } else {
            null
        }
        val revision = if (obj.has("revision") && !obj.get("revision").isJsonNull) {
            obj.get("revision").asLong
        } else {
            1L
        }
        return SkillRegistryEntry(
            enabled = enabled,
            source = source,
            installState = installState,
            verificationStatus = verificationStatus,
            revisionId = revisionId,
            previousRevisionId = previousRevisionId,
            revision = revision
        )
    }
}

internal fun computeBytesSha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
data class OfficialSkillsSyncResult(
    val action: String,
    val repositoryUrl: String,
    val rootPath: String,
    val shellRootPath: String,
    val skillCount: Int,
    val skills: List<SkillIndexEntry>,
    val output: String
)

internal class SkillRegistryStore(
    private val registryFile: File
) {
    private val gson = GsonBuilder()
        .registerTypeAdapter(SkillRegistryEntry::class.java, SkillRegistryEntryDeserializer())
        .create()
    private val mapType = object : TypeToken<LinkedHashMap<String, SkillRegistryEntry>>() {}.type

    fun read(): LinkedHashMap<String, SkillRegistryEntry> {
        if (!registryFile.exists()) {
            return linkedMapOf()
        }
        val text = registryFile.readText()
        if (text.isBlank()) {
            throw IllegalStateException("skill_asset_invalid: 注册表文件内容为空: ${registryFile.absolutePath}")
        }
        return try {
            val result = gson.fromJson<LinkedHashMap<String, SkillRegistryEntry>>(text, mapType)
                ?: throw IllegalStateException("skill_asset_invalid: 注册表文件解析为 null: ${registryFile.absolutePath}")
            result
        } catch (e: Exception) {
            if (e is IllegalStateException && e.message?.startsWith("skill_asset_invalid") == true) {
                throw e
            }
            throw IllegalStateException("skill_asset_invalid: 注册表文件损坏: ${registryFile.absolutePath}", e)
        }
    }

    fun write(entries: Map<String, SkillRegistryEntry>) {
        if (registryFile.exists()) {
            try {
                read()
            } catch (e: Exception) {
                throw IllegalStateException("skill_asset_invalid: 注册表已损坏，拒绝覆盖写入: ${registryFile.absolutePath}", e)
            }
        }
        registryFile.parentFile?.mkdirs()
        val tempFile = File(registryFile.parentFile, "${registryFile.name}.tmp.${System.nanoTime()}")
        val ordered = linkedMapOf<String, SkillRegistryEntry>()
        entries.toSortedMap().forEach { (key, value) ->
            ordered[key] = value
        }
        tempFile.writeText(gson.toJson(ordered))
        try {
            Files.move(
                tempFile.toPath(),
                registryFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw IllegalStateException("skill_asset_invalid: 注册表原子重命名失败: ${registryFile.absolutePath}", e)
        }
    }

    fun set(skillId: String, entry: SkillRegistryEntry) {
        val updated = read()
        updated[skillId] = entry
        write(updated)
    }

    fun remove(skillId: String) {
        val updated = read()
        if (updated.remove(skillId) != null) {
            write(updated)
        }
    }
}

private class BuiltinSkillAssetStore(
    private val context: Context,
    private val workspaceManager: AgentWorkspaceManager
) {
    private val gson = Gson()

    fun listBuiltins(): List<BuiltinSkillAsset> {
        val isEnglish = AppLocaleManager.isEnglish(context)
        return runCatching {
            context.assets.open(BUILTIN_SKILL_MANIFEST_ASSET).bufferedReader().use { reader ->
                gson.fromJson(reader, BuiltinSkillManifest::class.java)?.skills.orEmpty()
            }
        }.getOrElse {
            emptyList()
        }.filter { skill ->
            skill.id.isNotBlank() && skill.assetPath.isNotBlank()
        }.map { skill ->
            if (isEnglish && !skill.descriptionEn.isNullOrBlank()) {
                skill.copy(description = skill.descriptionEn)
            } else {
                skill
            }
        }
    }

    fun findBuiltin(skillId: String): BuiltinSkillAsset? {
        return listBuiltins().firstOrNull { it.id.equals(skillId, ignoreCase = true) }
    }

    fun seedMissingBuiltins(registryStore: SkillRegistryStore) {
        val registry = registryStore.read()
        var changed = false
        listBuiltins().forEach { builtin ->
            val registryEntry = registry[builtin.id]
            if (registryEntry?.installState == INSTALL_STATE_REMOVED_BUILTIN) {
                return@forEach
            }
            if (registryEntry?.source == USER_SOURCE) {
                return@forEach
            }
            installBuiltinInternal(builtin)
            if (registryEntry?.source == BUILTIN_SOURCE &&
                registryEntry.installState == INSTALL_STATE_INSTALLED
            ) {
                return@forEach
            }
            registry[builtin.id] = SkillRegistryEntry(
                enabled = true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED
            )
            changed = true
        }
        if (changed) {
            registryStore.write(registry)
        }
    }

    fun pruneRetiredBuiltins(registryStore: SkillRegistryStore) {
        val registry = registryStore.read()
        var changed = false
        RETIRED_BUILTIN_SKILL_IDS.forEach { skillId ->
            val targetDir = File(workspaceManager.skillsRoot(), skillId)
            val registryEntry = registry[skillId]
            val isBundledCopy = registryEntry?.source == BUILTIN_SOURCE ||
                (registryEntry == null && looksLikeRetiredBundledCopy(targetDir, skillId))
            if (!isBundledCopy) {
                return@forEach
            }
            val deleted = !targetDir.exists() || targetDir.deleteRecursively()
            if (deleted && registry.remove(skillId) != null) {
                changed = true
            }
        }
        if (changed) {
            registryStore.write(registry)
        }
    }

    fun installBuiltin(skillId: String, registryStore: SkillRegistryStore) {
        val builtin = findBuiltin(skillId)
            ?: throw IllegalArgumentException("未找到内置 skill：$skillId")
        installBuiltinInternal(builtin)
        registryStore.set(
            skillId,
            SkillRegistryEntry(
                enabled = true,
                source = BUILTIN_SOURCE,
                installState = INSTALL_STATE_INSTALLED
            )
        )
    }

    fun targetDirFor(builtin: BuiltinSkillAsset): File {
        return File(workspaceManager.skillsRoot(), builtin.id)
    }

    private fun installBuiltinInternal(builtin: BuiltinSkillAsset) {
        val targetDir = targetDirFor(builtin)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        copyAssetRecursively(
            assetManager = context.assets,
            assetPath = builtin.assetPath,
            target = targetDir
        )
    }

    private fun looksLikeRetiredBundledCopy(targetDir: File, skillId: String): Boolean {
        if (!targetDir.isDirectory) return false
        val skillFile = File(targetDir, "SKILL.md")
        if (!skillFile.isFile) return false
        val text = runCatching { skillFile.readText() }.getOrDefault("")
        return text.contains("name: $skillId") &&
            text.contains("/workspace/.omnibot/pets/<pet-id>")
    }

    private fun copyAssetRecursively(
        assetManager: AssetManager,
        assetPath: String,
        target: File
    ) {
        val children = assetManager.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        if (!target.exists()) {
            target.mkdirs()
        }
        children.forEach { child ->
            val childAssetPath = "$assetPath/$child"
            copyAssetRecursively(
                assetManager = assetManager,
                assetPath = childAssetPath,
                target = File(target, child)
            )
        }
    }
}

class SkillIndexService(
    private val context: Context,
    private val workspaceManager: AgentWorkspaceManager,
    private val lifecycleStore: SkillLifecycleStore = SkillLifecycleStore.getInstance(context)
) {
    internal fun registryStore(): SkillRegistryStore {
        return SkillRegistryStore(File(workspaceManager.skillsRoot(), SKILL_REGISTRY_FILE_NAME))
    }

    private fun builtinStore(): BuiltinSkillAssetStore {
        return BuiltinSkillAssetStore(context.applicationContext, workspaceManager)
    }

    fun seedBuiltinSkillsIfNeeded() {
        synchronized(SKILL_INDEX_LOCK) {
            val registryStore = registryStore()
            val builtinStore = builtinStore()
            builtinStore.pruneRetiredBuiltins(registryStore)
            builtinStore.seedMissingBuiltins(registryStore)
        }
    }

    fun listSkillsForManagement(): List<SkillIndexEntry> {
        synchronized(SKILL_INDEX_LOCK) {
            seedBuiltinSkillsIfNeeded()
            val registryStore = registryStore()
            val registry = registryStore.read()
            val builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
            val installedEntries = scanInstalledEntries(
                root = workspaceManager.skillsRoot(),
                registry = registry,
                builtinAssets = builtinAssets,
                registryStore = registryStore
            )
            val installedIds = installedEntries.mapTo(mutableSetOf()) { it.id }
            val removedBuiltinEntries = builtinAssets.values
                .asSequence()
                .filter { builtin ->
                    builtin.id !in installedIds &&
                        registry[builtin.id]?.installState == INSTALL_STATE_REMOVED_BUILTIN
                }
                .map { builtin ->
                    buildBuiltinPlaceholderEntry(
                        builtin = builtin,
                        registryState = registry[builtin.id]
                    )
                }
                .toList()

            return (installedEntries + removedBuiltinEntries).sortedWith(
                compareByDescending<SkillIndexEntry> { it.installed }
                    .thenBy { sourceRank(it.source) }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    fun listInstalledSkills(): List<SkillIndexEntry> {
        return listSkillsForManagement().filter { it.installed && it.enabled }
    }

    fun findInstalledSkill(identifier: String): SkillIndexEntry? {
        return findManagedInstalledSkill(identifier, includeDisabled = false)
    }

    private fun findManagedInstalledSkill(
        identifier: String,
        includeDisabled: Boolean
    ): SkillIndexEntry? {
        val normalizedIdentifier = normalizeSkillLookup(identifier)
        if (normalizedIdentifier.isBlank()) return null
        val entries = listSkillsForManagement().filter { entry ->
            entry.installed && (includeDisabled || entry.enabled)
        }
        return entries.firstOrNull { normalizeSkillLookup(it.id) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.name) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.shellSkillFilePath) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.skillFilePath) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.shellRootPath) == normalizedIdentifier }
            ?: entries.firstOrNull { normalizeSkillLookup(it.rootPath) == normalizedIdentifier }
    }

    private fun isProtectedSkillId(skillId: String, registry: Map<String, SkillRegistryEntry>? = null): Boolean {
        if (builtinStore().findBuiltin(skillId) != null) return true
        if (RETIRED_BUILTIN_SKILL_IDS.any { it.equals(skillId, ignoreCase = true) }) return true
        if (skillId.equals(OFFICIAL_SKILLS_DIRECTORY_NAME, ignoreCase = true)) return true
        val reg = registry ?: registryStore().read()
        val entry = reg[skillId]
        if (entry != null && (entry.source == BUILTIN_SOURCE || entry.source == OFFICIAL_SOURCE)) {
            return true
        }
        return false
    }

    fun installSkillFromDirectory(sourcePath: String): SkillIndexEntry {
        val sourceDir = File(sourcePath).canonicalFile
        return installSkillFromDirectory(sourcePath, sourceDir.name)
    }

    internal fun installSkillFromDirectory(
        sourcePath: String,
        targetDirectoryName: String,
        verificationStatus: String = "unverified",
        revisionId: String? = null,
        previousRevisionId: String? = null,
    ): SkillIndexEntry {
        synchronized(SKILL_INDEX_LOCK) {
            require(SKILL_TARGET_DIRECTORY.matches(targetDirectoryName)) {
                "skill target directory 非法：$targetDirectoryName"
            }
            if (isProtectedSkillId(targetDirectoryName)) {
                throw IllegalStateException("skill_cannot_overwrite_builtin: 严禁覆盖内置或官方技能：$targetDirectoryName")
            }
            val sourceFile = File(sourcePath)
            if (Files.isSymbolicLink(sourceFile.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：$sourcePath")
            }
            val sourceDir = sourceFile.canonicalFile
            if (Files.isSymbolicLink(sourceDir.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${sourceDir.path}")
            }
            require(sourceDir.isDirectory) { "skill source 必须是目录" }
            val skillFile = File(sourceDir, "SKILL.md")
            require(skillFile.exists() && skillFile.isFile) { "skill source 缺少 SKILL.md" }
            if (Files.isSymbolicLink(skillFile.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${skillFile.path}")
            }
            val skillsRoot = workspaceManager.skillsRoot().canonicalFile
            val targetDir = File(skillsRoot, targetDirectoryName)
            if (targetDir.exists() && Files.isSymbolicLink(targetDir.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${targetDir.path}")
            }
            if (sourceDir != targetDir.canonicalFile) {
                copyRecursively(sourceDir, targetDir)
            }
            val entry = buildInstalledEntry(
                skillDir = targetDir,
                registry = registryStore().read(),
                builtinAssets = builtinStore().listBuiltins().associateBy { it.id },
                registryStore = registryStore()
            ) ?: throw IllegalStateException("安装 skill 后索引失败")
            registryStore().set(
                entry.id,
                SkillRegistryEntry(
                    enabled = true,
                    source = USER_SOURCE,
                    installState = INSTALL_STATE_INSTALLED,
                    verificationStatus = verificationStatus,
                    revisionId = revisionId,
                    previousRevisionId = previousRevisionId
                )
            )
            return entry.copy(
                enabled = true,
                source = USER_SOURCE,
                installed = true,
                verificationStatus = verificationStatus,
                revisionId = revisionId,
                previousRevisionId = previousRevisionId
            )
        }
    }

    internal fun withReviewedSkillInstall(
        sourcePath: String,
        targetSkillId: String,
        expectedCurrentContentHash: String?,
        verificationStatus: String,
        revisionId: String,
        previousRevisionId: String?,
        commit: (SkillIndexEntry) -> Unit
    ): SkillIndexEntry {
        synchronized(SKILL_INDEX_LOCK) {
            require(SKILL_TARGET_DIRECTORY.matches(targetSkillId)) {
                "skill target directory 非法：$targetSkillId"
            }
            if (targetSkillId.contains("..") || targetSkillId.contains("/") || targetSkillId.contains("\\")) {
                throw IllegalArgumentException("skill_validation_failed: targetSkillId 路径逃逸：$targetSkillId")
            }

            val registryStore = registryStore()
            val registry = registryStore.read()
            if (isProtectedSkillId(targetSkillId, registry)) {
                throw IllegalStateException("skill_cannot_overwrite_builtin: 严禁覆盖内置或官方技能：$targetSkillId")
            }

            val skillsRoot = workspaceManager.skillsRoot().canonicalFile
            val targetDir = File(skillsRoot, targetSkillId)
            if (!targetDir.toPath().normalize().startsWith(skillsRoot.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问工作区外路径：${targetDir.path}")
            }
            if (targetDir.exists() && Files.isSymbolicLink(targetDir.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${targetDir.path}")
            }

            val sourceFile = File(sourcePath)
            if (Files.isSymbolicLink(sourceFile.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：$sourcePath")
            }
            val sourceDir = sourceFile.canonicalFile
            if (Files.isSymbolicLink(sourceDir.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${sourceDir.path}")
            }
            require(sourceDir.isDirectory) { "skill source 必须是目录" }

            val sourceSkillFile = File(sourceDir, "SKILL.md")
            if (!sourceSkillFile.exists() || !sourceSkillFile.isFile) {
                throw IllegalArgumentException("skill source 缺少 SKILL.md")
            }
            if (Files.isSymbolicLink(sourceSkillFile.toPath())) {
                throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${sourceSkillFile.path}")
            }
            val sourceBytes = sourceSkillFile.readBytes()
            val sourceHash = computeBytesSha256(sourceBytes)
            require(sourceHash.equals(revisionId, ignoreCase = true)) {
                "skill_validation_failed: revisionId 与源文件哈希不匹配: expected $sourceHash, got $revisionId"
            }

            val isUpdate = expectedCurrentContentHash != null
            val existingEntry = registry[targetSkillId]
            val targetSkillFile = File(targetDir, "SKILL.md")

            if (!isUpdate) {
                if (existingEntry != null && existingEntry.installState == INSTALL_STATE_INSTALLED) {
                    throw IllegalStateException("skill_name_conflict: 同名用户技能已存在，请使用更新模式并提供 expectedCurrentContentHash")
                }
                if (targetDir.exists()) {
                    throw IllegalStateException("skill_name_conflict: 目标技能目录已存在：$targetSkillId")
                }

                val stagingDir = File(skillsRoot, ".tmp-staging-$targetSkillId-${UUID.randomUUID()}")
                try {
                    copyRecursively(sourceDir, stagingDir)
                } catch (copyEx: Exception) {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                    throw IllegalStateException("skill_install_failed: 复制临时技能文件失败：$targetSkillId", copyEx)
                }

                var targetDirMoved = false
                var registryWritten = false
                try {
                    try {
                        Files.move(stagingDir.toPath(), targetDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        targetDirMoved = true
                    } catch (moveEx: Exception) {
                        if (stagingDir.exists()) stagingDir.deleteRecursively()
                        throw IllegalStateException("skill_install_failed: 原子移动技能目录失败：$targetSkillId", moveEx)
                    }

                    val nextRev = (existingEntry?.revision ?: 0L) + 1L
                    val newRegistryEntry = SkillRegistryEntry(
                        enabled = true,
                        source = USER_SOURCE,
                        installState = INSTALL_STATE_INSTALLED,
                        verificationStatus = verificationStatus,
                        revisionId = revisionId,
                        previousRevisionId = previousRevisionId ?: existingEntry?.revisionId,
                        revision = nextRev
                    )
                    registryStore.set(targetSkillId, newRegistryEntry)
                    registryWritten = true

                    val installedEntry = buildInstalledEntry(
                        skillDir = targetDir,
                        registry = registryStore.read(),
                        builtinAssets = builtinStore().listBuiltins().associateBy { it.id },
                        registryStore = registryStore
                    ) ?: throw IllegalStateException("安装 skill 后索引失败：$targetSkillId")

                    commit(installedEntry)
                    return installedEntry
                } catch (error: Throwable) {
                    if (stagingDir.exists()) {
                        stagingDir.deleteRecursively()
                    }
                    if (targetDirMoved && targetDir.exists()) {
                        val currentBytes = if (targetSkillFile.exists()) targetSkillFile.readBytes() else ByteArray(0)
                        val currentHash = computeBytesSha256(currentBytes)
                        if (currentHash.equals(revisionId, ignoreCase = true)) {
                            targetDir.deleteRecursively()
                            if (registryWritten) {
                                if (existingEntry != null) {
                                    registryStore.set(targetSkillId, existingEntry)
                                } else {
                                    registryStore.remove(targetSkillId)
                                }
                            }
                        } else {
                            val msg = "skill_asset_invalid: 提交失败且目标已被外部改动，拒绝回滚覆盖：$targetSkillId"
                            throw IllegalStateException(msg, error)
                        }
                    }
                    throw error
                }
            } else {
                if (existingEntry == null || !targetDir.exists()) {
                    throw IllegalArgumentException("skill_candidate_not_found: 未找到要更新的已安装技能：$targetSkillId")
                }
                if (!targetSkillFile.exists() || !targetSkillFile.isFile) {
                    throw IllegalArgumentException("skill_candidate_not_found: 目标 SKILL.md 不存在：$targetSkillId")
                }
                if (Files.isSymbolicLink(targetSkillFile.toPath())) {
                    throw IllegalArgumentException("skill_validation_failed: 拒绝访问符号链接：${targetSkillFile.path}")
                }
                val oldSkillBytes = targetSkillFile.readBytes()
                val currentDiskHash = computeBytesSha256(oldSkillBytes)
                if (!currentDiskHash.equals(expectedCurrentContentHash, ignoreCase = true)) {
                    throw IllegalStateException("skill_content_conflict: 当前磁盘内容哈希 ($currentDiskHash) 与预期 ($expectedCurrentContentHash) 不匹配")
                }

                // 仅替换 SKILL.md，保留现用户 skill scripts/references/assets
                val tempSibling = File(targetDir, ".tmp-SKILL-${UUID.randomUUID()}.md")
                tempSibling.writeBytes(sourceBytes)

                var fileMoved = false
                var registryWritten = false
                try {
                    try {
                        Files.move(
                            tempSibling.toPath(),
                            targetSkillFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        fileMoved = true
                    } catch (moveEx: Exception) {
                        if (tempSibling.exists()) tempSibling.delete()
                        throw IllegalStateException("skill_install_failed: 原子更新 SKILL.md 失败：$targetSkillId", moveEx)
                    }

                    val nextRev = (existingEntry.revision) + 1L
                    val newRegistryEntry = SkillRegistryEntry(
                        enabled = true,
                        source = USER_SOURCE,
                        installState = INSTALL_STATE_INSTALLED,
                        verificationStatus = verificationStatus,
                        revisionId = revisionId,
                        previousRevisionId = previousRevisionId ?: existingEntry.revisionId,
                        revision = nextRev
                    )
                    registryStore.set(targetSkillId, newRegistryEntry)
                    registryWritten = true

                    val installedEntry = buildInstalledEntry(
                        skillDir = targetDir,
                        registry = registryStore.read(),
                        builtinAssets = builtinStore().listBuiltins().associateBy { it.id },
                        registryStore = registryStore
                    ) ?: throw IllegalStateException("安装 skill 后索引失败：$targetSkillId")

                    commit(installedEntry)
                    return installedEntry
                } catch (error: Throwable) {
                    if (tempSibling.exists()) {
                        tempSibling.delete()
                    }
                    if (fileMoved && targetSkillFile.exists()) {
                        val currentBytesNow = targetSkillFile.readBytes()
                        val currentHashNow = computeBytesSha256(currentBytesNow)
                        if (currentHashNow.equals(revisionId, ignoreCase = true)) {
                            val rollbackTemp = File(targetDir, ".tmp-rollback-${UUID.randomUUID()}.md")
                            try {
                                rollbackTemp.writeBytes(oldSkillBytes)
                                Files.move(
                                    rollbackTemp.toPath(),
                                    targetSkillFile.toPath(),
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING
                                )
                            } catch (rollbackEx: Exception) {
                                if (rollbackTemp.exists()) rollbackTemp.delete()
                                error.addSuppressed(rollbackEx)
                                throw error
                            }
                            if (registryWritten) {
                                registryStore.set(targetSkillId, existingEntry)
                            }
                        } else {
                            val msg = "skill_asset_invalid: 提交失败且目标已被外部改动，拒绝回滚覆盖：$targetSkillId"
                            throw IllegalStateException(msg, error)
                        }
                    }
                    throw error
                }
            }
        }
    }

    internal fun deleteSkillInstallation(rootPath: String): Boolean {
        synchronized(SKILL_INDEX_LOCK) {
            val canonicalRoot = File(rootPath).canonicalFile
            val skillsRoot = workspaceManager.skillsRoot().canonicalFile
            require(
                canonicalRoot.parentFile == skillsRoot && canonicalRoot.name != SKILL_REGISTRY_FILE_NAME
            ) { "skill 安装目录非法" }
            val entry = buildInstalledEntry(
                skillDir = canonicalRoot,
                registry = registryStore().read(),
                builtinAssets = builtinStore().listBuiltins().associateBy { it.id },
                registryStore = registryStore()
            ) ?: return false
            val deleted = !canonicalRoot.exists() || canonicalRoot.deleteRecursively()
            if (!deleted) return false
            val stillInstalled = scanInstalledEntries(
                root = skillsRoot,
                registry = registryStore().read(),
                builtinAssets = builtinStore().listBuiltins().associateBy { it.id },
                registryStore = registryStore()
            ).any { it.id == entry.id }
            if (!stillInstalled) {
                registryStore().remove(entry.id)
            }
            return true
        }
    }

    internal fun getSkillRegistryEntry(skillId: String): SkillRegistryEntry? {
        synchronized(SKILL_INDEX_LOCK) {
            return registryStore().read()[skillId]
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean): SkillIndexEntry {
        synchronized(SKILL_INDEX_LOCK) {
            val entry = findManagedInstalledSkill(skillId, includeDisabled = true)
                ?: throw IllegalArgumentException("未找到已安装 skill：$skillId")
            val currentRegistry = registryStore().read()[entry.id]
            val nextRev = (currentRegistry?.revision ?: 1L) + 1L
            val targetRevId = currentRegistry?.revisionId ?: entry.revisionId
            if (!enabled && !targetRevId.isNullOrBlank()) {
                lifecycleStore.markRevisionManuallyDisabledNow(entry.id, targetRevId)
            }
            registryStore().set(
                entry.id,
                SkillRegistryEntry(
                    enabled = enabled,
                    source = entry.source,
                    installState = INSTALL_STATE_INSTALLED,
                    verificationStatus = currentRegistry?.verificationStatus ?: entry.verificationStatus,
                    revisionId = targetRevId,
                    previousRevisionId = currentRegistry?.previousRevisionId ?: entry.previousRevisionId,
                    revision = nextRev
                )
            )
            return entry.copy(enabled = enabled)
        }
    }

    fun updateSkillLifecycle(
        skillId: String,
        verificationStatus: String,
        revisionId: String? = null,
        previousRevisionId: String? = null,
        enabled: Boolean? = null,
        expectedRevision: Long? = null
    ): SkillIndexEntry? {
        synchronized(SKILL_INDEX_LOCK) {
            val store = registryStore()
            val registry = store.read()
            val current = registry[skillId] ?: return null
            if (expectedRevision != null && current.revision != expectedRevision) {
                throw IllegalStateException("skill_revision_conflict: 期望技能版本为 $expectedRevision，当前为 ${current.revision}")
            }
            val updated = current.copy(
                verificationStatus = verificationStatus,
                revisionId = revisionId ?: current.revisionId,
                previousRevisionId = previousRevisionId ?: current.previousRevisionId,
                enabled = enabled ?: current.enabled,
                revision = current.revision + 1L
            )
            store.set(skillId, updated)
            return findManagedInstalledSkill(skillId, includeDisabled = true)
        }
    }

    fun deleteSkill(skillId: String): Boolean {
        val entry = listSkillsForManagement().firstOrNull { it.id == skillId && it.installed }
            ?: return false
        val targetDir = File(entry.rootPath)
        if (entry.source == BUILTIN_SOURCE) {
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
                if (targetDir.exists()) {
                    return false
                }
            }
            registryStore().set(
                entry.id,
                SkillRegistryEntry(
                    enabled = false,
                    source = BUILTIN_SOURCE,
                    installState = INSTALL_STATE_REMOVED_BUILTIN
                )
            )
            return true
        }
        val deleted = !targetDir.exists() || targetDir.deleteRecursively()
        registryStore().remove(entry.id)
        return deleted
    }

    fun installBuiltinSkill(skillId: String): SkillIndexEntry {
        val builtinStore = builtinStore()
        builtinStore.installBuiltin(skillId, registryStore())
        return findInstalledSkill(skillId)
            ?: throw IllegalStateException("安装内置 skill 后索引失败：$skillId")
    }

    suspend fun syncOfficialSkillsRepository(): OfficialSkillsSyncResult {
        val targetDir = File(workspaceManager.skillsRoot(), OFFICIAL_SKILLS_DIRECTORY_NAME)
        val shellTargetPath = workspaceManager.shellPathForAndroid(targetDir)
            ?: "${AgentWorkspaceManager.SHELL_ROOT_PATH}/.omnibot/skills/$OFFICIAL_SKILLS_DIRECTORY_NAME"
        val wasInstalled = File(targetDir, ".git").isDirectory
        val repositoryUrl = resolveOfficialSkillsRepositoryUrl()
        val commandResult = EmbeddedTerminalRuntime.executeCommand(
            context = context.applicationContext,
            command = buildOfficialSkillsSyncCommand(repositoryUrl, shellTargetPath),
            workingDirectory = AgentWorkspaceManager.SHELL_ROOT_PATH,
            timeoutSeconds = 15 * 60
        )
        if (!commandResult.success) {
            val message = commandResult.errorMessage
                ?: commandResult.output.takeLast(1200).ifBlank { "同步官方 skills 失败" }
            throw IllegalStateException(message)
        }

        val officialEntries = markOfficialRepositorySkills(targetDir)
        val allSkills = listSkillsForManagement()
        return OfficialSkillsSyncResult(
            action = if (wasInstalled) "updated" else "installed",
            repositoryUrl = repositoryUrl,
            rootPath = targetDir.absolutePath,
            shellRootPath = shellTargetPath,
            skillCount = officialEntries.size,
            skills = allSkills,
            output = commandResult.output
        )
    }

    private fun resolveOfficialSkillsRepositoryUrl(): String {
        return when (AppUpdateManager.getApkDownloadSource(context.applicationContext)) {
            ApkDownloadSource.WORKER -> OFFICIAL_SKILLS_CNB_REPOSITORY_URL
            ApkDownloadSource.GITHUB -> OFFICIAL_SKILLS_GITHUB_REPOSITORY_URL
        }
    }

    private fun scanInstalledEntries(
        registry: MutableMap<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>
    ): List<SkillIndexEntry> {
        return scanInstalledEntries(
            root = workspaceManager.skillsRoot(),
            registry = registry,
            builtinAssets = builtinAssets,
            registryStore = registryStore()
        )
    }

    private fun scanInstalledEntries(
        root: File,
        registry: MutableMap<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
        registryStore: SkillRegistryStore? = registryStore()
    ): List<SkillIndexEntry> {
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .onEnter { directory ->
                directory.name != ".git" &&
                !directory.name.startsWith(".tmp-") &&
                directory.name != SKILL_REGISTRY_FILE_NAME
            }
            .filter { file -> file.isFile && file.name == "SKILL.md" }
            .mapNotNull { skillFile ->
                buildInstalledEntry(
                    skillDir = skillFile.parentFile ?: return@mapNotNull null,
                    registry = registry,
                    builtinAssets = builtinAssets,
                    registryStore = registryStore
                )
            }
            .distinctBy { it.rootPath }
            .toList()
    }

    private fun markOfficialRepositorySkills(repositoryDir: File): List<SkillIndexEntry> {
        val registryStore = registryStore()
        val registry = registryStore.read()
        val builtinAssets = builtinStore().listBuiltins().associateBy { it.id }
        val scannedEntries = scanInstalledEntries(
            root = repositoryDir,
            registry = registry,
            builtinAssets = builtinAssets
        )
        val officialIds = scannedEntries.mapTo(mutableSetOf()) { it.id }
        val updatedRegistry = linkedMapOf<String, SkillRegistryEntry>()
        registry.forEach { (skillId, entry) ->
            if (entry.source != OFFICIAL_SOURCE || skillId in officialIds) {
                updatedRegistry[skillId] = entry
            }
        }
        scannedEntries.forEach { entry ->
            val current = registry[entry.id]
            updatedRegistry[entry.id] = SkillRegistryEntry(
                enabled = current?.enabled ?: true,
                source = OFFICIAL_SOURCE,
                installState = INSTALL_STATE_INSTALLED
            )
        }
        registryStore.write(updatedRegistry)
        return scanInstalledEntries(
            root = repositoryDir,
            registry = updatedRegistry,
            builtinAssets = builtinAssets
        )
    }

    private fun buildOfficialSkillsSyncCommand(repositoryUrl: String, shellTargetPath: String): String {
        val quotedRepo = TermuxCommandBuilder.quoteForShell(repositoryUrl)
        val quotedTarget = TermuxCommandBuilder.quoteForShell(shellTargetPath)
        return """
            set -eu
            REPO=$quotedRepo
            TARGET=$quotedTarget
            mkdir -p "${'$'}(dirname "${'$'}TARGET")"
            if ! command -v git >/dev/null 2>&1; then
              apk update
              apk add --no-cache git ca-certificates
            fi
            if [ -d "${'$'}TARGET/.git" ]; then
              git -C "${'$'}TARGET" remote set-url origin "${'$'}REPO" || true
              git -C "${'$'}TARGET" fetch --prune origin
              git -C "${'$'}TARGET" pull --ff-only --autostash
            elif [ -e "${'$'}TARGET" ]; then
              echo "官方 skills 目录已存在但不是 git 仓库：${'$'}TARGET" >&2
              exit 2
            else
              git clone "${'$'}REPO" "${'$'}TARGET"
            fi
            find "${'$'}TARGET" -name SKILL.md -type f | wc -l
        """.trimIndent()
    }

    private fun buildInstalledEntry(
        skillDir: File,
        registry: MutableMap<String, SkillRegistryEntry>,
        builtinAssets: Map<String, BuiltinSkillAsset>,
        registryStore: SkillRegistryStore? = null
    ): SkillIndexEntry? {
        val canonicalSkillDir = skillDir.canonicalFile
        val skillFile = File(canonicalSkillDir, "SKILL.md")
        if (!skillFile.exists() || !skillFile.isFile) return null
        val rawBytes = skillFile.readBytes()
        val currentDiskHash = computeBytesSha256(rawBytes)
        val parsed = parseSkillContent(rawBytes.toString(Charsets.UTF_8)) ?: return null
        val frontmatter = parsed.frontmatter
        val id = sanitizeSkillId(canonicalSkillDir.name, frontmatter["name"])
        val metadata = frontmatter["metadata"]
            ?.let { raw -> parseIndentedBlock(raw) }
            ?: emptyMap()
        var registryState = registry[id]

        // 扫描已带 revision 技能检测磁盘 hash 变化
        if (registryState != null && registryState.revisionId != null && registryState.revisionId != currentDiskHash) {
            val nextRev = if (registryState.verificationStatus != "unverified") {
                registryState.revision + 1L
            } else {
                registryState.revision
            }
            val updatedState = registryState.copy(
                verificationStatus = "unverified",
                revisionId = currentDiskHash,
                previousRevisionId = registryState.previousRevisionId ?: registryState.revisionId,
                revision = nextRev,
                enabled = registryState.enabled
            )
            registry[id] = updatedState
            registryStore?.set(id, updatedState)
            registryState = updatedState
        }

        val builtinAsset = builtinAssets[id]
        val shellRootPath = workspaceManager.shellPathForAndroid(canonicalSkillDir)
            ?: canonicalSkillDir.absolutePath
        val shellSkillFilePath = workspaceManager.shellPathForAndroid(skillFile)
            ?: skillFile.absolutePath
        return SkillIndexEntry(
            id = id,
            name = frontmatter["name"]?.ifBlank { id } ?: id,
            description = frontmatter["description"]?.trim().orEmpty(),
            compatibility = frontmatter["compatibility"]?.trim(),
            metadata = metadata,
            rootPath = canonicalSkillDir.absolutePath,
            shellRootPath = shellRootPath,
            skillFilePath = skillFile.absolutePath,
            shellSkillFilePath = shellSkillFilePath,
            hasScripts = File(canonicalSkillDir, "scripts").isDirectory,
            hasReferences = File(canonicalSkillDir, "references").isDirectory,
            hasAssets = File(canonicalSkillDir, "assets").isDirectory,
            hasEvals = File(canonicalSkillDir, "evals").isDirectory,
            enabled = registryState?.enabled ?: true,
            source = registryState?.source?.ifBlank { null }
                ?: if (builtinAsset != null) BUILTIN_SOURCE else USER_SOURCE,
            installed = true,
            verificationStatus = registryState?.verificationStatus?.ifBlank { "unverified" } ?: "unverified",
            revisionId = registryState?.revisionId,
            previousRevisionId = registryState?.previousRevisionId
        )
    }

    private fun buildBuiltinPlaceholderEntry(
        builtin: BuiltinSkillAsset,
        registryState: SkillRegistryEntry?
    ): SkillIndexEntry {
        val targetDir = File(workspaceManager.skillsRoot(), builtin.id)
        val skillFile = File(targetDir, "SKILL.md")
        return SkillIndexEntry(
            id = builtin.id,
            name = builtin.name.ifBlank { builtin.id },
            description = builtin.description,
            rootPath = targetDir.absolutePath,
            shellRootPath = workspaceManager.shellPathForAndroid(targetDir)
                ?: targetDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            shellSkillFilePath = workspaceManager.shellPathForAndroid(skillFile)
                ?: skillFile.absolutePath,
            hasScripts = builtin.hasScripts,
            hasReferences = builtin.hasReferences,
            hasAssets = builtin.hasAssets,
            hasEvals = builtin.hasEvals,
            enabled = registryState?.enabled ?: false,
            source = BUILTIN_SOURCE,
            installed = false,
            verificationStatus = registryState?.verificationStatus?.ifBlank { "unverified" } ?: "unverified",
            revisionId = registryState?.revisionId,
            previousRevisionId = registryState?.previousRevisionId
        )
    }

    private fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.exists()) {
                target.mkdirs()
            }
            source.listFiles()?.forEach { child ->
                copyRecursively(child, File(target, child.name))
            }
            return
        }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    private fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    private fun normalizeSkillLookup(value: String): String {
        return value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")
    }

    private fun sourceRank(source: String): Int {
        return when (source) {
            BUILTIN_SOURCE -> 0
            OFFICIAL_SOURCE -> 1
            else -> 2
        }
    }

    private companion object {
        val SKILL_TARGET_DIRECTORY = Regex("^[a-z0-9][a-z0-9-]{0,79}$")
    }
}

data class LoadedSkillFingerprint(
    val context: ResolvedSkillContext,
    val contentHash: String
)

class SkillLoader(
    private val workspaceManager: AgentWorkspaceManager
) {
    fun loadFromContent(
        entry: SkillIndexEntry,
        contentBytes: ByteArray,
        triggerReason: String
    ): LoadedSkillFingerprint? {
        if (!entry.installed) {
            return null
        }
        val contentHash = computeBytesSha256(contentBytes)
        val rawText = contentBytes.toString(Charsets.UTF_8)
        val parsed = parseSkillContent(rawText) ?: return null
        val skillDir = File(entry.rootPath)
        val referencesDir = File(skillDir, "references")
        val loadedReferences = if (referencesDir.isDirectory) {
            referencesDir.listFiles()
                ?.filter { it.isFile }
                ?.map { file -> workspaceManager.shellPathForAndroid(file) ?: file.absolutePath }
                ?.sorted()
                ?: emptyList()
        } else {
            emptyList()
        }
        val context = ResolvedSkillContext(
            skillId = entry.id,
            frontmatter = parsed.frontmatter,
            metadata = entry.metadata,
            bodyMarkdown = parsed.body,
            loadedReferences = loadedReferences,
            scriptsDir = File(skillDir, "scripts")
                .takeIf { it.isDirectory }
                ?.let { workspaceManager.shellPathForAndroid(it) ?: it.absolutePath },
            assetsDir = File(skillDir, "assets")
                .takeIf { it.isDirectory }
                ?.let { workspaceManager.shellPathForAndroid(it) ?: it.absolutePath },
            triggerReason = triggerReason
        )
        return LoadedSkillFingerprint(
            context = context,
            contentHash = contentHash
        )
    }

    fun loadWithFingerprint(entry: SkillIndexEntry, triggerReason: String): LoadedSkillFingerprint? {
        if (!entry.installed) {
            return null
        }
        val skillDir = File(entry.rootPath)
        val skillFile = File(skillDir, "SKILL.md")
        if (!skillFile.exists() || !skillFile.isFile) return null
        val bytes = skillFile.readBytes()
        return loadFromContent(entry, bytes, triggerReason)
    }

    fun load(entry: SkillIndexEntry, triggerReason: String): ResolvedSkillContext? {
        return loadWithFingerprint(entry, triggerReason)?.context
    }
}

object SkillTriggerMatcher {
    fun resolveMatches(
        userMessage: String,
        entries: List<SkillIndexEntry>,
        maxMatches: Int = 2
    ): List<SkillMatchResult> {
        val normalizedMessage = normalize(userMessage)
        if (normalizedMessage.isBlank()) return emptyList()
        return entries.mapNotNull { entry ->
            if (!entry.installed || !entry.enabled) {
                return@mapNotNull null
            }
            val confidence = score(entry, normalizedMessage)
            if (confidence <= 0.0) return@mapNotNull null
            val reason = when {
                normalizedMessage.contains(normalize(entry.id)) -> "用户消息命中 skill id"
                normalizedMessage.contains(normalize(entry.name)) -> "用户消息命中 skill 名称"
                else -> "用户消息命中 skill 描述关键词"
            }
            SkillMatchResult(entry = entry, confidence = confidence, triggerReason = reason)
        }.sortedByDescending { it.confidence }
            .take(maxMatches)
    }

    private fun score(entry: SkillIndexEntry, normalizedMessage: String): Double {
        var score = 0.0
        val normalizedId = normalize(entry.id)
        val normalizedName = normalize(entry.name)
        if (normalizedId.isNotBlank() && normalizedMessage.contains(normalizedId)) {
            score += 1.0
        }
        if (normalizedName.isNotBlank() && normalizedMessage.contains(normalizedName)) {
            score += 0.9
        }
        extractCandidatePhrases(entry.description).forEach { phrase ->
            if (matchesTriggerPhrase(normalizedMessage, phrase)) {
                score += 0.35
            }
        }
        return min(score, 1.5)
    }

    private fun matchesTriggerPhrase(normalizedMessage: String, phrase: String): Boolean {
        val normalizedPhrase = normalize(phrase)
        if (normalizedPhrase.isBlank()) return false
        if ('*' !in normalizedPhrase) return normalizedMessage.contains(normalizedPhrase)
        val segments = normalizedPhrase.split('*').filter(String::isNotBlank)
        if (segments.size < 2) return false
        var searchFrom = 0
        segments.forEach { segment ->
            val matchAt = normalizedMessage.indexOf(segment, startIndex = searchFrom)
            if (matchAt < 0) return false
            searchFrom = matchAt + segment.length
        }
        return true
    }

    private fun extractCandidatePhrases(description: String): List<String> {
        val quoted = Regex("[\"“”'‘’]([^\"“”'‘’]{2,40})[\"“”'‘’]")
            .findAll(description)
            .map { it.groupValues[1] }
            .toList()
        val fallback = description.split(Regex("[,，。;；、\\n]"))
            .map { it.trim() }
            .filter { it.length in 2..24 }
        return (quoted + fallback).distinct().take(20)
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("。", "")
            .replace("，", "")
            .replace(",", "")
            .replace("！", "")
            .replace("!", "")
            .replace("？", "")
            .replace("?", "")
    }
}

internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String
)

internal fun parseSkillContent(raw: String): ParsedSkillFile? {
    if (!raw.startsWith("---")) {
        return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
    }
    val markerIndex = raw.indexOf("\n---", startIndex = 3)
    if (markerIndex <= 0) {
        return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
    }
    val frontmatterText = raw.substring(3, markerIndex).trim('\n', '\r')
    val body = raw.substring(markerIndex + 4).trim()
    return ParsedSkillFile(
        frontmatter = parseSimpleFrontmatter(frontmatterText),
        body = body
    )
}

internal fun parseSkillFile(skillFile: File): ParsedSkillFile? {
    if (!skillFile.exists() || !skillFile.isFile) return null
    return parseSkillContent(skillFile.readText())
}

internal fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
    if (frontmatter.isBlank()) return emptyMap()
    val lines = frontmatter.lines()
    val result = linkedMapOf<String, String>()
    var index = 0
    while (index < lines.size) {
        val rawLine = lines[index]
        if (rawLine.isBlank()) {
            index += 1
            continue
        }
        val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
        if (keyMatch == null) {
            index += 1
            continue
        }
        val key = keyMatch.groupValues[1]
        val value = keyMatch.groupValues[2]
        if (value == ">" || value == "|") {
            val builder = StringBuilder()
            index += 1
            while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                val next = lines[index]
                if (next.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(next.trim())
                }
                index += 1
            }
            result[key] = builder.toString().trim()
            continue
        }
        if (value.isBlank()) {
            val builder = StringBuilder()
            index += 1
            while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                if (builder.isNotEmpty()) builder.append('\n')
                builder.append(lines[index].trimEnd())
                index += 1
            }
            result[key] = builder.toString().trim()
            continue
        }
        result[key] = value.trim().trim('"')
        index += 1
    }
    return result
}

internal fun parseIndentedBlock(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.lines().mapNotNull { line ->
        val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
        match.groupValues[1] to match.groupValues[2].trim().trim('"')
    }.toMap()
}
