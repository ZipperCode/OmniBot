package cn.com.omnimind.bot.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cn.com.omnimind.bot.agent.runtime.stubAcpTestResources
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import java.security.MessageDigest
import cn.com.omnimind.bot.agent.tool.handlers.decodeImageWriteContentForFileName
import cn.com.omnimind.bot.agent.tool.handlers.ImageGenerationToolHandler
import cn.com.omnimind.bot.agent.tool.handlers.normalizeSvgWriteContentForFileName
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

class SkillRuntimeBehaviorTest {
    private fun filePreferences(file: File): SharedPreferences {
        val gson = Gson()
        fun read(): MutableMap<String, Any?> = if (file.exists()) {
            gson.fromJson(file.readText(), object : TypeToken<MutableMap<String, Any?>>() {}.type)
                ?: linkedMapOf()
        } else {
            linkedMapOf()
        }
        fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = mock(SharedPreferences.Editor::class.java) { call ->
                when (call.method.name) {
                    "putString" -> {
                        changes[call.getArgument(0)] = call.getArgument<String?>(1)
                        editor
                    }
                    "putBoolean" -> {
                        changes[call.getArgument(0)] = call.getArgument<Boolean>(1)
                        editor
                    }
                    "remove" -> {
                        changes[call.getArgument(0)] = null
                        editor
                    }
                    "apply", "commit" -> {
                        val values = read()
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        file.writeText(gson.toJson(values))
                        if (call.method.name == "commit") true else null
                    }
                    else -> null
                }
            }
            return editor
        }
        return mock(SharedPreferences::class.java) { call ->
            when (call.method.name) {
                "getString" -> read()[call.getArgument<String>(0)] as? String ?: call.getArgument<String?>(1)
                "getBoolean" -> read()[call.getArgument<String>(0)] as? Boolean ?: call.getArgument<Boolean>(1)
                "getInt" -> (read()[call.getArgument<String>(0)] as? Number)?.toInt() ?: call.getArgument<Int>(1)
                "getLong" -> (read()[call.getArgument<String>(0)] as? Number)?.toLong() ?: call.getArgument<Long>(1)
                "getAll" -> read().toMap()
                "contains" -> read().containsKey(call.getArgument<String>(0))
                "edit" -> editor()
                else -> null
            }
        }
    }

    private fun createMockContext(tempDir: File): Context {
        val appInfo = ApplicationInfo().apply {
            dataDir = tempDir.absolutePath
        }
        val mockContext = mock(Context::class.java)
        val mockAssets = mock(AssetManager::class.java)
        `when`(mockAssets.open(anyString())).thenAnswer { call ->
            val path = call.getArgument<String>(0)
            val assetFile = File("src/main/assets", path).let { if (it.exists()) it else File("app/src/main/assets", path) }
            if (assetFile.exists()) {
                assetFile.inputStream()
            } else {
                throw java.io.FileNotFoundException("Asset not found: $path")
            }
        }
        `when`(mockAssets.list(anyString())).thenAnswer { call ->
            val path = call.getArgument<String>(0)
            val assetDir = File("src/main/assets", path).let { if (it.exists()) it else File("app/src/main/assets", path) }
            if (assetDir.exists() && assetDir.isDirectory) {
                assetDir.list()
            } else {
                emptyArray<String>()
            }
        }

        `when`(mockContext.applicationContext).thenReturn(mockContext)
        stubAcpTestResources(mockContext)
        `when`(mockContext.applicationInfo).thenReturn(appInfo)
        `when`(mockContext.filesDir).thenReturn(File(tempDir, "files").apply { mkdirs() })
        `when`(mockContext.noBackupFilesDir).thenReturn(File(tempDir, "no-backup").apply { mkdirs() })
        `when`(mockContext.cacheDir).thenReturn(File(tempDir, "cache").apply { mkdirs() })
        `when`(mockContext.assets).thenReturn(mockAssets)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenAnswer { call ->
            filePreferences(File(tempDir, "${call.getArgument<String>(0)}.json"))
        }
        return mockContext
    }

    private fun entry(
        id: String,
        description: String,
        enabled: Boolean = true,
        installed: Boolean = true
    ): SkillIndexEntry {
        return SkillIndexEntry(
            id = id,
            name = id,
            description = description,
            rootPath = "/tmp/$id",
            shellRootPath = "/workspace/.omnibot/skills/$id",
            skillFilePath = "/tmp/$id/SKILL.md",
            shellSkillFilePath = "/workspace/.omnibot/skills/$id/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = enabled,
            installed = installed
        )
    }

    @Test
    fun resolveMatchesSkipsDisabledAndUninstalledSkills() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "请用 skill-creator 帮我创建一个新的技能",
            entries = listOf(
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    enabled = false
                ),
                entry(
                    id = "skill-creator",
                    description = "用于创建和更新技能",
                    installed = false
                )
            )
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun resolveMatchesFindSkillsFromChineseTriggerPhrase() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我找个 skill 来处理 changelog",
            entries = listOf(
                entry(
                    id = "find-install-skills",
                    description = "Find and install relevant Omnibot skills. Use when the user asks \"找个 skill\", \"有没有这个功能的 skill\", \"find a skill for X\", \"is there a skill for X\", or wants to extend the agent with an installable workflow."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "find-install-skills" })
    }

    @Test
    fun resolveMatchesSupportsExplicitWildcardTriggerPhrase() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我做一个健身打卡工具",
            entries = listOf(
                entry(
                    id = "vibe-project-builder",
                    description = "Build Xiaowan projects. Use when the user asks \"做*工具\" or \"创建*应用\".",
                ),
            ),
        )

        assertEquals("vibe-project-builder", matches.single().entry.id)
    }

    @Test
    fun resolveMatchesInstallCodexPetFromCliRequest() {
        val matches = SkillTriggerMatcher.resolveMatches(
            userMessage = "帮我执行 npx codex-pets add claude-pixel 安装宠物",
            entries = listOf(
                entry(
                    id = "install-codex-pet",
                    description = "Install shared Codex pet packages into Omnibot. Use for commands such as \"npx codex-pets add\"."
                )
            )
        )

        assertTrue(matches.any { it.entry.id == "install-codex-pet" })
    }

    @Test
    fun builtinInstallCodexPetSkillUsesCompatibleWorkspacePath() {
        val skillFile = File("src/main/assets/builtin_skills/install-codex-pet/SKILL.md")
        val text = skillFile.readText()

        assertTrue(text.contains("export CODEX_HOME=/workspace/.omnibot"))
        assertTrue(text.contains("npx --yes codex-pets add claude-pixel"))
        assertTrue(text.contains("validate_codex_pet.sh"))
        assertTrue(File("src/main/assets/builtin_skills/install-codex-pet/scripts/validate_codex_pet.sh").isFile)
        assertFalse(File("src/main/assets/builtin_skills/hatch-pet").exists())
    }

    @Test
    fun imageGenerationPromptCapacityIsOwnedByTheConfiguredProvider() {
        val promptBeyondTheFormerHostLimit = "画一幅城市夜景。".repeat(12_000)

        assertEquals(
            promptBeyondTheFormerHostLimit,
            ImageGenerationToolHandler.requireImageGenerationPrompt(
                promptBeyondTheFormerHostLimit,
            ),
        )
    }

    @Test
    fun imageGenerationDoesNotRejectAProviderImageByHostSize() {
        val imageBeyondTheFormerHostLimit = ByteArray(20 * 1024 * 1024 + 1)
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
        ).copyInto(imageBeyondTheFormerHostLimit)

        ImageGenerationToolHandler.requireSupportedImage(imageBeyondTheFormerHostLimit)
    }

    @Test
    fun imageGenerationEndpointSupportsBaseAndFullEndpointUrls() {
        assertEquals(
            "https://cloud.omnimind.com.cn/v1/images/generations",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn"
            )
        )
        assertEquals(
            "https://cloud.omnimind.com.cn/v1/images/generations",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn/v1/images/generations"
            )
        )
        assertEquals(
            "https://cloud.omnimind.com.cn/custom/images",
            ImageGenerationToolHandler.resolveImageGenerationEndpoint(
                "https://cloud.omnimind.com.cn/custom/images#"
            )
        )
    }


    @Test
    fun decodeImageWriteContentAcceptsBase64DataUriForBinaryPetImages() {
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
        val decoded = decodeImageWriteContentForFileName(
            "current.png",
            "data:image/png;base64,iVBORw0KGgoAAAAN"
        )

        assertArrayEquals(pngBytes, decoded)
        assertEquals(null, decodeImageWriteContentForFileName("current.png", "not-image-data"))
        assertEquals(null, decodeImageWriteContentForFileName("current.svg", "iVBORw0KGgoAAAAN"))
    }

    @Test
    fun normalizeSvgWriteContentExtractsSvgFromMarkdownFence() {
        val svg = normalizeSvgWriteContentForFileName(
            "current.svg",
            """
                ```svg
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <circle cx="12" cy="12" r="6"/>
                </svg>
                ```
            """.trimIndent()
        )

        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.endsWith("</svg>"))
        assertFalse(svg.contains("```"))
    }

    @Test
    fun normalizeSvgWriteContentInlinesSimpleClassStyles() {
        val svg = normalizeSvgWriteContentForFileName(
            "current.svg",
            """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                  <defs>
                    <style>
                      .body { fill: #ffdc50; }
                      .accent { fill: #8b5a2b; stroke: #111111; stroke-width: 2; }
                    </style>
                  </defs>
                  <rect class="body" x="4" y="4" width="16" height="16"/>
                  <circle class="accent" cx="12" cy="12" r="4"/>
                </svg>
            """.trimIndent()
        )

        assertFalse(svg.contains("<style>"))
        assertFalse(svg.contains("""class="""))
        assertTrue(svg.contains("""fill="#ffdc50""""))
        assertTrue(svg.contains("""fill="#8b5a2b""""))
        assertTrue(svg.contains("""stroke="#111111""""))
    }

    @Test
    fun buildOmitsDisabledAndUninstalledSkillsFromPromptIndex() {
        val prompt = AgentSystemPrompt.build(
            workspace = AgentWorkspaceDescriptor(
                id = "conversation-1",
                rootPath = "/workspace",
                androidRootPath = "/data/user/0/cn.com.omnimind.bot/workspace",
                uriRoot = "omnibot://workspace",
                currentCwd = "/workspace",
                androidCurrentCwd = "/data/user/0/cn.com.omnimind.bot/workspace",
                shellRootPath = "/workspace",
                retentionPolicy = "shared_root"
            ),
            installedSkills = listOf(
                entry(
                    id = "active-skill",
                    description = "可正常使用的技能"
                ),
                entry(
                    id = "disabled-skill",
                    description = "已禁用的技能",
                    enabled = false
                ),
                entry(
                    id = "removed-skill",
                    description = "已删除但可恢复的技能",
                    installed = false
                )
            ),
            skillsRootShellPath = "/workspace/.omnibot/skills",
            skillsRootAndroidPath = "/data/user/0/cn.com.omnimind.bot/workspace/.omnibot/skills",
            resolvedSkills = emptyList(),
            memoryContext = null
        )

        assertTrue(prompt.contains("id=active-skill"))
        assertFalse(prompt.contains("id=disabled-skill"))
        assertFalse(prompt.contains("id=removed-skill"))
    }

    @Test
    fun failureHookWritesErrorsLogAndFindsRelatedHints() {
        val skillsRoot = Files.createTempDirectory("self-improving-skill-test").toFile()
        val skillRoot = skillsRoot.resolve(SelfImprovingSkillFailureHook.SKILL_ID)
        val dataDir = skillRoot.resolve("data").apply { mkdirs() }
        val errorsFile = dataDir.resolve("ERRORS.md")
        errorsFile.writeText(
            """
            # Errors

            ## [ERR-20260409-OLD] terminal_execute

            **记录时间**: 2026-04-09T00:00:00Z
            **优先级**: high
            **状态**: pending
            **领域**: runtime

            ### 摘要
            旧的终端失败

            ---
            """.trimIndent() + "\n"
        )

        val payload = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = ResolvedSkillContext(
                skillId = SelfImprovingSkillFailureHook.SKILL_ID,
                frontmatter = mapOf("name" to SelfImprovingSkillFailureHook.SKILL_ID),
                bodyMarkdown = "先检查失败原因\n不要重复相同步骤",
                triggerReason = "test"
            ),
            userMessage = "修复终端命令报错",
            toolName = "terminal_execute",
            toolType = "terminal",
            argumentsJson = """{"command":"bad cmd"}""",
            result = ToolExecutionResult.TerminalResult(
                toolName = "terminal_execute",
                summaryText = "命令执行失败",
                previewJson = "{}",
                rawResultJson = """{"stderr":"not found"}""",
                success = false
            )
        )

        assertNotNull(payload)
        assertTrue(errorsFile.readText().contains("命令执行失败"))
        assertTrue(payload!!.guidance.contains("self-improving-agent"))
        assertTrue(payload.relatedHints.any { it.contains("ERR-20260409-OLD") })
    }

    @Test
    fun failureHookDeduplicatesRedactsAndClosesVerifiedArgumentRecovery() {
        val skillsRoot = Files.createTempDirectory("self-improving-harness-test").toFile()
        val skill = ResolvedSkillContext(
            skillId = SelfImprovingSkillFailureHook.SKILL_ID,
            frontmatter = mapOf("name" to SelfImprovingSkillFailureHook.SKILL_ID),
            bodyMarkdown = "先检查失败原因，不要原样重试",
            triggerReason = "test"
        )
        val failure = ToolExecutionResult.Error(
            toolName = "browser_use",
            message = "Missing required field url"
        )

        val first = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = skill,
            userMessage = "打开页面，token=private-user-token",
            toolName = "browser_use",
            toolType = "context",
            argumentsJson = """{"apiKey":"sk-privatecredential123456","action":"navigate"}""",
            result = failure,
            agentRunId = "run-1",
            failureStage = "argument_validation"
        )!!
        val second = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = skill,
            userMessage = "打开页面",
            toolName = "browser_use",
            toolType = "context",
            argumentsJson = """{"apiKey":"sk-anotherprivate123456","action":"navigate"}""",
            result = failure,
            agentRunId = "run-1",
            failureStage = "argument_validation"
        )!!

        val pendingContent = first.logFile.readText()
        assertEquals(first.entryId, second.entryId)
        assertEquals(2, second.occurrences)
        assertTrue(second.reusedEntry)
        assertEquals(1, Regex("^## \\[", RegexOption.MULTILINE).findAll(pendingContent).count())
        assertTrue(pendingContent.contains("- 出现次数: 2"))
        assertFalse(pendingContent.contains("privatecredential"))
        assertFalse(pendingContent.contains("anotherprivate"))

        val resolution = SelfImprovingSkillFailureHook.resolveAfterSuccess(
            skillsRoot = skillsRoot,
            agentRunId = "run-1",
            toolName = "browser_use",
            argumentsJson = """{"action":"navigate","url":"https://example.com"}""",
            result = ToolExecutionResult.ContextResult(
                toolName = "browser_use",
                summaryText = "页面已打开",
                previewJson = "{}",
                rawResultJson = "{}",
                success = true
            )
        )

        assertNotNull(resolution)
        assertTrue(resolution!!.shouldPromoteDaily)
        val resolvedContent = first.logFile.readText()
        assertTrue(resolvedContent.contains("**状态**: resolved"))
        assertTrue(resolvedContent.contains("same_run_verified_success"))
        assertTrue(resolvedContent.contains("涉及字段：apiKey, action, url"))
    }

    @Test
    fun failureHookDoesNotAutoResolveExecutionFailureAfterGenericSuccess() {
        val skillsRoot = Files.createTempDirectory("self-improving-runtime-failure-test").toFile()
        val skill = ResolvedSkillContext(
            skillId = SelfImprovingSkillFailureHook.SKILL_ID,
            frontmatter = mapOf("name" to SelfImprovingSkillFailureHook.SKILL_ID),
            bodyMarkdown = "先检查失败原因，不要原样重试",
            triggerReason = "test"
        )
        val captured = SelfImprovingSkillFailureHook.capture(
            skillsRoot = skillsRoot,
            skill = skill,
            userMessage = "打开页面",
            toolName = "browser_use",
            toolType = "context",
            argumentsJson = """{"action":"navigate","url":"https://example.com"}""",
            result = ToolExecutionResult.Error(
                toolName = "browser_use",
                message = "Browser process crashed"
            ),
            agentRunId = "run-runtime",
            failureStage = "execution"
        )!!

        val resolution = SelfImprovingSkillFailureHook.resolveAfterSuccess(
            skillsRoot = skillsRoot,
            agentRunId = "run-runtime",
            toolName = "browser_use",
            argumentsJson = """{"action":"navigate","url":"https://example.com"}""",
            result = ToolExecutionResult.ContextResult(
                toolName = "browser_use",
                summaryText = "页面已打开",
                previewJson = "{}",
                rawResultJson = "{}",
                success = true
            )
        )

        assertNull(resolution)
        assertTrue(captured.logFile.readText().contains("**状态**: pending"))
    }

    @Test
    fun skillRegistryStoreThrowsWhenCorruptedAndRefusesOverwrite() {
        val tempDir = Files.createTempDirectory("skill-registry-test").toFile()
        val registryFile = File(tempDir, ".skill_registry.json")
        registryFile.writeText("{ this is invalid json !!!")

        val store = SkillRegistryStore(registryFile)

        var readFailed = false
        try {
            store.read()
        } catch (e: IllegalStateException) {
            readFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
        }
        assertTrue(readFailed)

        var writeFailed = false
        try {
            store.write(mapOf("new-skill" to SkillRegistryEntry(enabled = true)))
        } catch (e: IllegalStateException) {
            writeFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
        }
        assertTrue(writeFailed)
        // 验证文件原样保留，未被覆盖破坏
        assertEquals("{ this is invalid json !!!", registryFile.readText())
    }

    @Test
    fun skillRegistryStorePreservesLifecycleFieldsAndDefaultsOlderEntries() {
        val tempDir = Files.createTempDirectory("skill-registry-lifecycle-test").toFile()
        val registryFile = File(tempDir, ".skill_registry.json")
        // 模拟旧版注册表 JSON，缺少 verificationStatus, revisionId, previousRevisionId
        registryFile.writeText(
            """
            {
              "legacy-skill": {
                "enabled": true,
                "source": "user",
                "installState": "installed"
              }
            }
            """.trimIndent()
        )

        val store = SkillRegistryStore(registryFile)
        val entries = store.read()
        val legacy = entries["legacy-skill"]
        assertNotNull(legacy)
        assertTrue(legacy!!.enabled)
        assertEquals("unverified", legacy.verificationStatus)
        assertNull(legacy.revisionId)
        assertNull(legacy.previousRevisionId)

        // 写入含生命周期字段的新项
        store.set(
            "trial-skill",
            SkillRegistryEntry(
                enabled = true,
                source = "user",
                installState = "installed",
                verificationStatus = "trial",
                revisionId = "hash-123",
                previousRevisionId = null
            )
        )

        val updatedEntries = store.read()
        val trial = updatedEntries["trial-skill"]
        assertNotNull(trial)
        assertEquals("trial", trial!!.verificationStatus)
        assertEquals("hash-123", trial.revisionId)
        // legacy skill 的 enabled 依然保留
        assertTrue(updatedEntries["legacy-skill"]!!.enabled)
    }
    private fun testSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun saveRevisionNowEnforcesHashMatchesRevisionId() {
        val tempDir = Files.createTempDirectory("skill-revision-hash-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val body = "# Test Skill Body\nValid content"
        val validHash = testSha256(body)

        val validSnapshot = SkillRevisionSnapshot(
            skillId = "test-skill",
            revisionId = validHash,
            name = "Test Skill",
            description = "Description",
            bodyMarkdown = body
        )
        store.saveRevisionNow(validSnapshot)
        val retrieved = store.getRevisionNow("test-skill", validHash)
        assertNotNull(retrieved)
        assertEquals(validHash, retrieved!!.revisionId)

        // 哈希不匹配时拒绝保存并抛出异常
        val invalidSnapshot = validSnapshot.copy(revisionId = "0000000000000000000000000000000000000000000000000000000000000000")
        var failed = false
        try {
            store.saveRevisionNow(invalidSnapshot)
        } catch (e: IllegalArgumentException) {
            failed = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(failed)
    }

    @Test
    fun saveRevisionNowPreservesExistingContentAndMonotonicTombstone() {
        val tempDir = Files.createTempDirectory("skill-revision-tombstone-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val body = "# V1 Content"
        val hash = testSha256(body)
        val initialSnapshot = SkillRevisionSnapshot(
            skillId = "demo-skill",
            revisionId = hash,
            name = "Demo V1",
            description = "Desc V1",
            bodyMarkdown = body,
            createdAt = 1000L,
            createdFromCandidateId = "cand-1",
            previousRevisionId = "prev-hash",
            wasManuallyDisabled = false
        )
        store.saveRevisionNow(initialSnapshot)

        // 标记停用
        store.markRevisionManuallyDisabledNow("demo-skill", hash)
        val disabled = store.getRevisionNow("demo-skill", hash)
        assertNotNull(disabled)
        assertTrue(disabled!!.wasManuallyDisabled)

        // 试图以 wasManuallyDisabled = false 重新保存该版本，停用状态单调保留为 true，内容不被覆盖
        val attemptResurrect = initialSnapshot.copy(
            name = "Tampered Name",
            wasManuallyDisabled = false,
            createdAt = 9999L
        )
        store.saveRevisionNow(attemptResurrect)

        val afterSave = store.getRevisionNow("demo-skill", hash)
        assertNotNull(afterSave)
        assertTrue(afterSave!!.wasManuallyDisabled)
        assertEquals("Demo V1", afterSave.name) // 内容不可变
        assertEquals(1000L, afterSave.createdAt) // 不覆盖 createdAt
        assertEquals("cand-1", afterSave.createdFromCandidateId)
        assertEquals("prev-hash", afterSave.previousRevisionId)
    }

    @Test
    fun pathGuardRejectsPathTraversalAndSymlinks() {
        val tempDir = Files.createTempDirectory("skill-path-guard-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        var failedTraversal = false
        try {
            store.getRevisionNow("../escape", "rev1")
        } catch (e: IllegalArgumentException) {
            failedTraversal = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(failedTraversal)

        // 符号链接拒绝测试（在支持符号链接的平台上）
        val outsideFile = File(tempDir.parentFile, "outside-${java.util.UUID.randomUUID()}.json")
        outsideFile.writeText("{}")
        val linkFile = File(tempDir, "candidates/symlink.json")
        linkFile.parentFile.mkdirs()
        var symlinkCreated = false
        try {
            Files.createSymbolicLink(linkFile.toPath(), outsideFile.toPath())
            symlinkCreated = true
        } catch (_: Exception) {}

        if (symlinkCreated) {
            var failedSymlink = false
            try {
                runBlocking {
                    store.getCandidate("symlink")
                }
            } catch (e: IllegalArgumentException) {
                failedSymlink = true
                assertTrue(e.message?.contains("skill_validation_failed") == true)
            }
            assertTrue(failedSymlink)
        }
        outsideFile.delete()
    }

    @Test
    fun setSkillEnabledFalseRecordsMonotonicTombstone() {
        val tempDir = Files.createTempDirectory("skill-enable-tombstone-test").toFile()
        val mockContext = createMockContext(tempDir)
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }

        val workspaceManager = AgentWorkspaceManager(mockContext)
        val lifecycleStore = SkillLifecycleStore(mockContext, baseDirOverride = suggestionsDir)
        val indexService = SkillIndexService(mockContext, workspaceManager, lifecycleStore)

        val skillDir = File(workspaceManager.skillsRoot(), "tombstone-skill").apply { mkdirs() }
        val body = "---\nname: Tombstone Skill\ndescription: test\n---\n# Content"
        File(skillDir, "SKILL.md").writeText(body)
        val revHash = testSha256(body)

        val installed = indexService.installSkillFromDirectory(
            sourcePath = skillDir.absolutePath,
            targetDirectoryName = "tombstone-skill",
            verificationStatus = "verified",
            revisionId = revHash
        )
        assertEquals("tombstone-skill", installed.id)

        // 先保存版本快照
        lifecycleStore.saveRevisionNow(
            SkillRevisionSnapshot(
                skillId = "tombstone-skill",
                revisionId = revHash,
                name = "Tombstone Skill",
                description = "test",
                bodyMarkdown = body,
                wasManuallyDisabled = false
            )
        )

        // 手动停用技能
        indexService.setSkillEnabled("tombstone-skill", false)

        // 验证当前 revision 已被同步记录单调停用 tombstone
        val snapshot = lifecycleStore.getRevisionNow("tombstone-skill", revHash)
        assertNotNull(snapshot)
        assertTrue(snapshot!!.wasManuallyDisabled)
    }

    @Test
    fun withReviewedSkillInstallReplacesOnlySkillMdAndRollsBackOnCommitFailure() {
        val tempDir = Files.createTempDirectory("skill-reviewed-install-test").toFile()
        val mockContext = createMockContext(tempDir)
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }

        val workspaceManager = AgentWorkspaceManager(mockContext)
        val lifecycleStore = SkillLifecycleStore(mockContext, baseDirOverride = suggestionsDir)
        val indexService = SkillIndexService(mockContext, workspaceManager, lifecycleStore)

        // 准备现有用户技能（含 scripts 和 assets）
        val existingDir = File(workspaceManager.skillsRoot(), "user-skill").apply { mkdirs() }
        val scriptsDir = File(existingDir, "scripts").apply { mkdirs() }
        val scriptFile = File(scriptsDir, "run.sh").apply { writeText("#!/bin/sh\necho 123") }
        val assetsDir = File(existingDir, "assets").apply { mkdirs() }
        val assetFile = File(assetsDir, "icon.png").apply { writeText("fake-png-data") }

        val v1Body = "---\nname: user-skill\ndescription: v1\n---\n# V1 Content"
        val v1SkillFile = File(existingDir, "SKILL.md").apply { writeText(v1Body) }
        val v1Hash = testSha256(v1Body)

        indexService.installSkillFromDirectory(
            sourcePath = existingDir.absolutePath,
            targetDirectoryName = "user-skill",
            verificationStatus = "verified",
            revisionId = v1Hash
        )

        // 准备待审核更新 source
        val updateSourceDir = File(tempDir, "update-source").apply { mkdirs() }
        val v2Body = "---\nname: user-skill\ndescription: v2\n---\n# V2 Content"
        File(updateSourceDir, "SKILL.md").writeText(v2Body)
        val v2Hash = testSha256(v2Body)

        // 模拟 commit 失败触发回滚
        var commitCalled = false
        var installFailed = false
        try {
            indexService.withReviewedSkillInstall(
                sourcePath = updateSourceDir.absolutePath,
                targetSkillId = "user-skill",
                expectedCurrentContentHash = v1Hash,
                verificationStatus = "trial",
                revisionId = v2Hash,
                previousRevisionId = v1Hash
            ) {
                commitCalled = true
                throw RuntimeException("Simulated commit CAS failure")
            }
        } catch (_: RuntimeException) {
            installFailed = true
        }

        assertTrue(commitCalled)
        assertTrue(installFailed)
        // 回滚后验证：SKILL.md 恢复为 V1 内容，用户 scripts 和 assets 依然完好
        assertEquals(v1Body, v1SkillFile.readText())
        assertTrue(scriptFile.exists())
        assertEquals("#!/bin/sh\necho 123", scriptFile.readText())
        assertTrue(assetFile.exists())
        assertEquals("fake-png-data", assetFile.readText())
        // 注册表恢复为旧版本
        val regEntry = indexService.registryStore().read()["user-skill"]
        assertNotNull(regEntry)
        assertEquals(v1Hash, regEntry!!.revisionId)
    }

    @Test
    fun scanDetectsDiskHashChangeAndDowngradesToUnverifiedOnce() {
        val tempDir = Files.createTempDirectory("skill-disk-scan-test").toFile()
        val mockContext = createMockContext(tempDir)
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }

        val workspaceManager = AgentWorkspaceManager(mockContext)
        val lifecycleStore = SkillLifecycleStore(mockContext, baseDirOverride = suggestionsDir)
        val indexService = SkillIndexService(mockContext, workspaceManager, lifecycleStore)

        val skillDir = File(workspaceManager.skillsRoot(), "managed-skill").apply { mkdirs() }
        val v1Body = "---\nname: Managed Skill\ndescription: original\n---\n# Original"
        val skillFile = File(skillDir, "SKILL.md").apply { writeText(v1Body) }
        val v1Hash = testSha256(v1Body)

        indexService.installSkillFromDirectory(
            sourcePath = skillDir.absolutePath,
            targetDirectoryName = "managed-skill",
            verificationStatus = "verified",
            revisionId = v1Hash
        )

        // 外部改动 SKILL.md
        val v2Body = "---\nname: Managed Skill\ndescription: modified\n---\n# Modified External"
        skillFile.writeText(v2Body)
        val v2Hash = testSha256(v2Body)

        // 执行扫描
        val scanned = indexService.listSkillsForManagement()
        val target = scanned.firstOrNull { it.id == "managed-skill" }
        assertNotNull(target)
        // 验证 verificationStatus 降为 unverified，revision 递增，enabled 原值保留
        assertEquals("unverified", target!!.verificationStatus)
        assertEquals(v2Hash, target.revisionId)
        assertEquals(v1Hash, target.previousRevisionId)
        assertTrue(target.enabled)

        // 再次扫描，状态保持稳定，revision 不再递增
        val regEntryAfterScan1 = indexService.registryStore().read()["managed-skill"]
        assertNotNull(regEntryAfterScan1)
        val revAfterScan1 = regEntryAfterScan1!!.revision

        val scannedAgain = indexService.listSkillsForManagement()
        val targetAgain = scannedAgain.firstOrNull { it.id == "managed-skill" }
        assertEquals("unverified", targetAgain!!.verificationStatus)
        assertEquals(v2Hash, targetAgain.revisionId)
        val regEntryAfterScan2 = indexService.registryStore().read()["managed-skill"]
        assertEquals(revAfterScan1, regEntryAfterScan2!!.revision)

        // 在已处于 unverified 状态下即使磁盘再次变动，downgrade 也只发生一次，revision 不再递增
        val v3Body = "---\nname: Managed Skill\ndescription: modified v3\n---\n# Modified V3"
        skillFile.writeText(v3Body)
        val v3Hash = testSha256(v3Body)
        val scannedV3 = indexService.listSkillsForManagement()
        val targetV3 = scannedV3.firstOrNull { it.id == "managed-skill" }
        assertEquals("unverified", targetV3!!.verificationStatus)
        assertEquals(v3Hash, targetV3.revisionId)
        val regEntryAfterScan3 = indexService.registryStore().read()["managed-skill"]
        assertEquals(revAfterScan1, regEntryAfterScan3!!.revision)
    }

    @Test
    fun skillRegistryStoreRejectsBlankFileWithZeroOverwrite() {
        val tempDir = Files.createTempDirectory("skill-registry-blank-test").toFile()
        val registryFile = File(tempDir, ".skill_registry.json")
        registryFile.writeText("   \n\t  \n")

        val store = SkillRegistryStore(registryFile)
        var readFailed = false
        try {
            store.read()
        } catch (e: IllegalStateException) {
            readFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
        }
        assertTrue(readFailed)

        var writeFailed = false
        try {
            store.write(mapOf("new-skill" to SkillRegistryEntry(enabled = true)))
        } catch (e: IllegalStateException) {
            writeFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
        }
        assertTrue(writeFailed)
        assertEquals("   \n\t  \n", registryFile.readText())
    }

    @Test
    fun skillLoaderFingerprintReadsOnceAndDetectsMismatch() {
        val tempDir = Files.createTempDirectory("skill-loader-fingerprint-test").toFile()
        val mockContext = createMockContext(tempDir)
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }
        val workspaceManager = AgentWorkspaceManager(mockContext)
        val skillLoader = SkillLoader(workspaceManager)

        val skillDir = File(workspaceManager.skillsRoot(), "fingerprint-skill").apply { mkdirs() }
        val body = "---\nname: Fingerprint Skill\ndescription: test\n---\n# Exact Body"
        val skillFile = File(skillDir, "SKILL.md").apply { writeText(body) }
        val expectedHash = testSha256(body)

        val entry = SkillIndexEntry(
            id = "fingerprint-skill",
            name = "Fingerprint Skill",
            description = "test",
            rootPath = skillDir.absolutePath,
            shellRootPath = "/workspace/.omnibot/skills/fingerprint-skill",
            skillFilePath = skillFile.absolutePath,
            shellSkillFilePath = "/workspace/.omnibot/skills/fingerprint-skill/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = true,
            installed = true,
            revisionId = expectedHash
        )

        val loaded = skillLoader.loadWithFingerprint(entry, "test load")
        assertNotNull(loaded)
        assertEquals(expectedHash, loaded!!.contentHash)
        assertEquals("# Exact Body", loaded.context.bodyMarkdown)

        // 外部修改为不同内容后，实读哈希与 entry.revisionId 不一致
        val modifiedBody = "---\nname: Fingerprint Skill\ndescription: test\n---\n# Modified Body"
        skillFile.writeText(modifiedBody)
        val modifiedHash = testSha256(modifiedBody)

        val loadedModified = skillLoader.loadWithFingerprint(entry, "test load modified")
        assertNotNull(loadedModified)
        assertEquals(modifiedHash, loadedModified!!.contentHash)
        assertNotEquals(entry.revisionId, loadedModified.contentHash)
    }

    @Test
    fun commitApprovalNowVerifiesPendingReviewAndRollsBackOnError() {
        val tempDir = Files.createTempDirectory("skill-commit-approval-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val cand1 = SkillCandidateAsset(
            candidateId = "cand-approval",
            conversationId = 1L,
            sessionId = "s1",
            turnId = "t1",
            revision = 1L,
            state = "queued" // not pending_review
        )
        runBlocking {
            store.saveCandidate(cand1)
        }

        val newBody = "# Approved Skill"
        val newHash = testSha256(newBody)
        val newSnapshot = SkillRevisionSnapshot(
            skillId = "approved-skill",
            revisionId = newHash,
            name = "Approved Skill",
            description = "desc",
            bodyMarkdown = newBody
        )

        // 非 pending_review 状态拒绝批准
        var failedState = false
        try {
            store.commitApprovalNow(
                candidateId = "cand-approval",
                expectedRevision = 1L,
                oldSnapshot = null,
                newSnapshot = newSnapshot,
                targetSkillId = "approved-skill"
            )
        } catch (e: IllegalStateException) {
            failedState = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(failedState)

        // 更新为 pending_review
        store.mutateCandidateNow("cand-approval", 1L) {
            it.copy(state = "pending_review")
        }

        // revision 冲突测试
        var failedCas = false
        try {
            store.commitApprovalNow(
                candidateId = "cand-approval",
                expectedRevision = 999L,
                oldSnapshot = null,
                newSnapshot = newSnapshot,
                targetSkillId = "approved-skill"
            )
        } catch (e: IllegalStateException) {
            failedCas = true
            assertTrue(e.message?.contains("skill_revision_conflict") == true)
        }
        assertTrue(failedCas)

        // 验证 stale revision 冲突时零快照写入、零状态/revision 变动
        val candAfterStale = store.getCandidateNow("cand-approval")
        assertNotNull(candAfterStale)
        assertEquals("pending_review", candAfterStale!!.state)
        assertEquals(2L, candAfterStale.revision)
        val revDir = File(tempDir, "revisions/approved-skill")
        assertTrue(!revDir.exists() || revDir.listFiles().isNullOrEmpty())

        // 正常批准提交
        val approved = store.commitApprovalNow(
            candidateId = "cand-approval",
            expectedRevision = 2L,
            oldSnapshot = null,
            newSnapshot = newSnapshot,
            targetSkillId = "approved-skill"
        )
        assertEquals("approved", approved.state)
        assertEquals("approved-skill", approved.targetSkillId)
        assertEquals(3L, approved.revision)

        val savedRev = store.getRevisionNow("approved-skill", newHash)
        assertNotNull(savedRev)
    }

    @Test
    fun withReviewedSkillInstallConflictsAndNeverDeletesExistingDirectoryEvenWithoutSkillMd() {
        val tempDir = Files.createTempDirectory("skill-target-conflict-test").toFile()
        val mockContext = createMockContext(tempDir)
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }

        val workspaceManager = AgentWorkspaceManager(mockContext)
        val lifecycleStore = SkillLifecycleStore(mockContext, baseDirOverride = suggestionsDir)
        val indexService = SkillIndexService(mockContext, workspaceManager, lifecycleStore)

        // 目标目录已存在，但没有任何 SKILL.md，只有用户文件
        val existingUserDir = File(workspaceManager.skillsRoot(), "target-conflict-skill").apply { mkdirs() }
        val userWorkFile = File(existingUserDir, "user_work.txt").apply { writeText("precious user data") }

        val sourceDir = File(tempDir, "new-skill-source").apply { mkdirs() }
        val sourceBody = "---\nname: New Skill\ndescription: desc\n---\n# New Content"
        File(sourceDir, "SKILL.md").writeText(sourceBody)
        val sourceHash = testSha256(sourceBody)

        var conflictThrown = false
        try {
            indexService.withReviewedSkillInstall(
                sourcePath = sourceDir.absolutePath,
                targetSkillId = "target-conflict-skill",
                expectedCurrentContentHash = null, // 新安装模式
                verificationStatus = "trial",
                revisionId = sourceHash,
                previousRevisionId = null
            ) {}
        } catch (e: IllegalStateException) {
            conflictThrown = true
            assertTrue(e.message?.contains("skill_name_conflict") == true)
        }
        assertTrue(conflictThrown)
        // 严禁删除用户目录及其内容
        assertTrue(existingUserDir.exists())
        assertTrue(userWorkFile.exists())
        assertEquals("precious user data", userWorkFile.readText())
    }

    @Test
    fun withReviewedSkillInstallProtectsBuiltinAndOfficialSkillsEvenIfAbsentFromRegistry() {
        val tempDir = Files.createTempDirectory("skill-protect-builtin-test").toFile()
        val mockContext = createMockContext(tempDir)
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }

        val workspaceManager = AgentWorkspaceManager(mockContext)
        val lifecycleStore = SkillLifecycleStore(mockContext, baseDirOverride = suggestionsDir)
        val indexService = SkillIndexService(mockContext, workspaceManager, lifecycleStore)

        val sourceDir = File(tempDir, "source").apply { mkdirs() }
        val body = "---\nname: Hacker\ndescription: d\n---\n# Malicious"
        File(sourceDir, "SKILL.md").writeText(body)
        val hash = testSha256(body)

        // 尝试覆盖退役的内置 skill id "hatch-pet"（即使注册表中不存在）
        var hatchPetBlocked = false
        try {
            indexService.withReviewedSkillInstall(
                sourcePath = sourceDir.absolutePath,
                targetSkillId = "hatch-pet",
                expectedCurrentContentHash = null,
                verificationStatus = "trial",
                revisionId = hash,
                previousRevisionId = null
            ) {}
        } catch (e: IllegalStateException) {
            hatchPetBlocked = true
            assertTrue(e.message?.contains("skill_cannot_overwrite_builtin") == true)
        }
        assertTrue(hatchPetBlocked)

        // 尝试覆盖官方 skills 仓库目录名称 "OmniBotSkills"
        var officialBlocked = false
        try {
            indexService.withReviewedSkillInstall(
                sourcePath = sourceDir.absolutePath,
                targetSkillId = "omnibotskills",
                expectedCurrentContentHash = null,
                verificationStatus = "trial",
                revisionId = hash,
                previousRevisionId = null
            ) {}
        } catch (e: IllegalStateException) {
            officialBlocked = true
            assertTrue(e.message?.contains("skill_cannot_overwrite_builtin") == true)
        }
        assertTrue(officialBlocked)
    }

    @Test
    fun withReviewedSkillInstallRefusesToOverwriteExternalEditsOnFailure() {
        val tempDir = Files.createTempDirectory("skill-no-overwrite-external-test").toFile()
        val mockContext = createMockContext(tempDir)
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }
        val workspaceDir = File(tempDir, "workspace").apply { mkdirs() }

        val workspaceManager = AgentWorkspaceManager(mockContext)
        val lifecycleStore = SkillLifecycleStore(mockContext, baseDirOverride = suggestionsDir)
        val indexService = SkillIndexService(mockContext, workspaceManager, lifecycleStore)

        val skillDir = File(workspaceManager.skillsRoot(), "external-skill").apply { mkdirs() }
        val v1Body = "---\nname: External Skill\ndescription: v1\n---\n# V1 Content"
        val skillFile = File(skillDir, "SKILL.md").apply { writeText(v1Body) }
        val v1Hash = testSha256(v1Body)

        indexService.installSkillFromDirectory(
            sourcePath = skillDir.absolutePath,
            targetDirectoryName = "external-skill",
            verificationStatus = "verified",
            revisionId = v1Hash
        )

        val updateSourceDir = File(tempDir, "update-source").apply { mkdirs() }
        val v2Body = "---\nname: External Skill\ndescription: v2\n---\n# V2 Content"
        File(updateSourceDir, "SKILL.md").writeText(v2Body)
        val v2Hash = testSha256(v2Body)

        val v3ExternalBody = "# External User Edited Meanwhile"

        var failedWithInvalid = false
        try {
            indexService.withReviewedSkillInstall(
                sourcePath = updateSourceDir.absolutePath,
                targetSkillId = "external-skill",
                expectedCurrentContentHash = v1Hash,
                verificationStatus = "trial",
                revisionId = v2Hash,
                previousRevisionId = v1Hash
            ) {
                // 模拟在文件移动后、commit 执行期间，外部进程改动了 SKILL.md
                skillFile.writeText(v3ExternalBody)
                throw RuntimeException("Commit callback failure")
            }
        } catch (e: IllegalStateException) {
            failedWithInvalid = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
            assertTrue(e.message?.contains("拒绝回滚覆盖") == true)
        }
        assertTrue(failedWithInvalid)
        // 外部修改的内容必须保留，不得被回滚覆盖
        assertEquals(v3ExternalBody, skillFile.readText())
    }

    @Test
    fun commitApprovalNowHandlesSameOldAndNewRevision() {
        val tempDir = Files.createTempDirectory("skill-same-rev-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val cand = SkillCandidateAsset(
            candidateId = "cand-same-rev",
            conversationId = 1L,
            sessionId = "s1",
            turnId = "t1",
            revision = 1L,
            state = "pending_review"
        )
        runBlocking {
            store.saveCandidate(cand)
        }

        val body = "# Identical Content"
        val hash = testSha256(body)
        val snapshot = SkillRevisionSnapshot(
            skillId = "same-rev-skill",
            revisionId = hash,
            name = "Same Rev",
            description = "desc",
            bodyMarkdown = body
        )

        // oldSnapshot 和 newSnapshot revisionId 相同
        val approved = store.commitApprovalNow(
            candidateId = "cand-same-rev",
            expectedRevision = 1L,
            oldSnapshot = snapshot,
            newSnapshot = snapshot,
            targetSkillId = "same-rev-skill"
        )
        assertEquals("approved", approved.state)
        assertEquals(2L, approved.revision)

        val retrieved = store.getRevisionNow("same-rev-skill", hash)
        assertNotNull(retrieved)
        assertEquals(hash, retrieved!!.revisionId)
    }

    @Test
    fun commitApprovalNowRejectsSnapshotMismatchAndValidatesHashes() {
        val tempDir = Files.createTempDirectory("skill-snapshot-mismatch-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val cand = SkillCandidateAsset(
            candidateId = "cand-mismatch",
            conversationId = 1L,
            sessionId = "s1",
            turnId = "t1",
            revision = 1L,
            state = "pending_review"
        )
        runBlocking {
            store.saveCandidate(cand)
        }

        val body = "# Valid Body"
        val hash = testSha256(body)

        // newSnapshot.skillId 不匹配 targetSkillId
        val wrongSkillIdSnapshot = SkillRevisionSnapshot(
            skillId = "wrong-skill",
            revisionId = hash,
            name = "Valid",
            description = "d",
            bodyMarkdown = body
        )
        var mismatchSkillId = false
        try {
            store.commitApprovalNow(
                candidateId = "cand-mismatch",
                expectedRevision = 1L,
                oldSnapshot = null,
                newSnapshot = wrongSkillIdSnapshot,
                targetSkillId = "correct-skill"
            )
        } catch (e: IllegalArgumentException) {
            mismatchSkillId = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(mismatchSkillId)

        // newSnapshot.revisionId 不匹配内容 SHA256
        val wrongHashSnapshot = SkillRevisionSnapshot(
            skillId = "correct-skill",
            revisionId = "badhash",
            name = "Valid",
            description = "d",
            bodyMarkdown = body
        )
        var mismatchHash = false
        try {
            store.commitApprovalNow(
                candidateId = "cand-mismatch",
                expectedRevision = 1L,
                oldSnapshot = null,
                newSnapshot = wrongHashSnapshot,
                targetSkillId = "correct-skill"
            )
        } catch (e: IllegalArgumentException) {
            mismatchHash = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(mismatchHash)
    }

    @Test
    fun readAtomicJsonRejectsBlankNullAndDamagedAssets() {
        val tempDir = Files.createTempDirectory("skill-atomic-reject-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val candidatesDir = File(tempDir, "candidates").apply { mkdirs() }

        // 1. 空白文件拒绝为 skill_asset_invalid
        val blankFile = File(candidatesDir, "blank-cand.json").apply { writeText("   \n\t  \n") }
        var blankFailed = false
        try {
            store.getCandidateNow("blank-cand")
        } catch (e: IllegalStateException) {
            blankFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
            assertTrue(e.message?.contains("资产文件内容为空") == true)
        }
        assertTrue(blankFailed)

        // 2. 解析为 null 的 JSON 拒绝为 skill_asset_invalid
        val nullJsonFile = File(candidatesDir, "null-cand.json").apply { writeText("null") }
        var nullFailed = false
        try {
            store.getCandidateNow("null-cand")
        } catch (e: IllegalStateException) {
            nullFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
            assertTrue(e.message?.contains("资产文件解析为 null") == true)
        }
        assertTrue(nullFailed)

        // 3. 损坏的 JSON 拒绝为 skill_asset_invalid
        val damagedFile = File(candidatesDir, "damaged-cand.json").apply { writeText("{ damaged json ::: ") }
        var damagedFailed = false
        try {
            store.getCandidateNow("damaged-cand")
        } catch (e: IllegalStateException) {
            damagedFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
            assertTrue(e.message?.contains("资产文件损坏") == true)
        }
        assertTrue(damagedFailed)

        // 4. settings.json 损坏拒绝为 skill_asset_invalid，禁止静默默认覆盖
        val settingsFile = File(tempDir, "settings.json").apply { writeText("   \n") }
        var settingsFailed = false
        try {
            runBlocking {
                store.getSettings()
            }
        } catch (e: IllegalStateException) {
            settingsFailed = true
            assertTrue(e.message?.contains("skill_asset_invalid") == true)
        }
        assertTrue(settingsFailed)
    }

    @Test
    fun findCompletedUsageForTurnRequiresExactContentHashAndStoredRevision() = runBlocking {
        val tempDir = Files.createTempDirectory("skill-usage-exact-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val validHash = testSha256("valid-skill-content")
        val record1 = SkillUsageRecord(
            id = "usage-1",
            conversationId = 100L,
            sessionId = "session-1",
            turnId = "turn-1",
            skillId = "demo-skill",
            revisionId = validHash,
            contentHash = validHash,
            completed = true
        )
        store.recordUsage(record1)

        // 正常精确匹配
        val found = store.findCompletedUsageForTurn("demo-skill", validHash, 100L, "session-1", "turn-1")
        assertNotNull(found)
        assertEquals("usage-1", found!!.id)

        // contentHash 与 revisionId 不一致（内容篡改或指纹不合）拒绝匹配
        val tamperedRecord = SkillUsageRecord(
            id = "usage-tampered",
            conversationId = 200L,
            sessionId = "session-2",
            turnId = "turn-2",
            skillId = "demo-skill",
            revisionId = validHash,
            contentHash = "mismatched-content-hash",
            completed = true
        )
        store.recordUsage(tamperedRecord)
        val notFoundTampered = store.findCompletedUsageForTurn("demo-skill", validHash, 200L, "session-2", "turn-2")
        assertNull(notFoundTampered)

        // completed 为 false 拒绝匹配
        val incompleteRecord = SkillUsageRecord(
            id = "usage-incomplete",
            conversationId = 300L,
            sessionId = "session-3",
            turnId = "turn-3",
            skillId = "demo-skill",
            revisionId = validHash,
            contentHash = validHash,
            completed = false
        )
        store.recordUsage(incompleteRecord)
        val notFoundIncomplete = store.findCompletedUsageForTurn("demo-skill", validHash, 300L, "session-3", "turn-3")
        assertNull(notFoundIncomplete)
    }

    @Test
    fun onProcessStartIncrementsRevisionOnInterruptedCandidates() = runBlocking {
        val tempDir = Files.createTempDirectory("skill-interrupted-rev-test").toFile()
        val mockContext = createMockContext(tempDir)
        val store = SkillLifecycleStore(mockContext, baseDirOverride = tempDir)

        val candQueued = SkillCandidateAsset(
            candidateId = "cand-q",
            conversationId = 1L,
            sessionId = "s1",
            turnId = "t1",
            revision = 1L,
            state = "queued"
        )
        val candGenerating = SkillCandidateAsset(
            candidateId = "cand-g",
            conversationId = 2L,
            sessionId = "s2",
            turnId = "t2",
            revision = 5L,
            state = "generating"
        )
        store.saveCandidate(candQueued)
        store.saveCandidate(candGenerating)

        store.onProcessStart()

        val updatedQ = store.getCandidateNow("cand-q")
        assertNotNull(updatedQ)
        assertEquals("interrupted", updatedQ!!.state)
        assertEquals(2L, updatedQ.revision) // revision 递增 1L

        val updatedG = store.getCandidateNow("cand-g")
        assertNotNull(updatedG)
        assertEquals("interrupted", updatedG!!.state)
        assertEquals(6L, updatedG.revision) // revision 递增 1L
    }
}
