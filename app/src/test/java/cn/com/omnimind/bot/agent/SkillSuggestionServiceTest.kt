package cn.com.omnimind.bot.agent

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import cn.com.omnimind.baselib.llm.ChatCompletionMessage
import cn.com.omnimind.baselib.llm.ChatCompletionTurn
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class SkillSuggestionServiceTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var workspaceDir: File
    private lateinit var workspaceManager: AgentWorkspaceManager
    private lateinit var lifecycleStore: SkillLifecycleStore
    private lateinit var skillIndexService: SkillIndexService
    private val activeScopes = mutableListOf<CoroutineScope>()

    private val testProvider = ModelProviderProfile(
        id = "test-provider",
        name = "Test Provider",
        apiKey = "test-api-key",
        baseUrl = "https://api.example.com/v1"
    )

    private fun computeDigest(profile: ModelProviderProfile): String {
        return computeSkillSuggestionCredentialDigest(profile)
    }

    private fun computeSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private open class FakeAgentLlmClient(
        var responseJson: String = """
            {
              "suggestion": {
                "name": "auto-release-flow",
                "description": "自动化构建与发布流程",
                "bodyMarkdown": "## 适用条件\n适用于自动化构建并发布新版本的场景。\n\n## 参数化执行步骤\n1. 检查代码工作区与 Git 状态\n2. 编译打包并执行签名流程\n\n## 结果检查\n检查产物输出目录中是否存在有效且匹配签名的发布包。\n\n## 失败与人工接手条件\n若编译失败、依赖缺失或签名校验异常，终止自动流程并提示开发者人工介入。"
              }
            }
        """.trimIndent(),
        var blockDeferred: CompletableDeferred<Unit>? = null
    ) : AgentLlmClient {
        override suspend fun streamTurn(
            request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
            onReasoningUpdate: (suspend (String) -> Unit)?,
            onContentUpdate: (suspend (String) -> Unit)?,
            onToolCallInput: (suspend (cn.com.omnimind.baselib.llm.AssistantToolCall) -> Unit)?,
            allowTransportRetry: Boolean
        ): ChatCompletionTurn {
            blockDeferred?.await()
            return ChatCompletionTurn(
                message = ChatCompletionMessage(
                    role = "assistant",
                    content = kotlinx.serialization.json.JsonPrimitive(responseJson)
                )
            )
        }
    }

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        tempDir = Files.createTempDirectory("skill-suggestion-test").toFile()
        workspaceDir = File(tempDir, "workspace").apply { mkdirs() }
        val suggestionsDir = File(tempDir, "skill-suggestions").apply { mkdirs() }

        context = object : ContextWrapper(app) {
            override fun getApplicationInfo(): ApplicationInfo {
                return ApplicationInfo(super.getApplicationInfo()).apply {
                    dataDir = tempDir.absolutePath
                }
            }
            override fun getFilesDir(): File = File(tempDir, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(tempDir, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }

        workspaceManager = AgentWorkspaceManager(context)
        lifecycleStore = SkillLifecycleStore(context, baseDirOverride = suggestionsDir)
        skillIndexService = SkillIndexService(context, workspaceManager, lifecycleStore)
    }

    @After
    fun tearDown() {
        activeScopes.forEach { it.cancel() }
        activeScopes.clear()
        tempDir.deleteRecursively()
    }

    private fun createService(
        llmResponse: String? = null,
        profileResolver: ((String?) -> ModelProviderProfile?)? = { id -> if (id == testProvider.id) testProvider else null },
        contextCapacityResolver: (suspend (Long, ModelProviderProfile, String) -> Int?)? = { _, _, _ -> 32768 },
        fakeClient: AgentLlmClient? = null,
        scope: CoroutineScope? = null
    ): Pair<SkillSuggestionService, AgentLlmClient> {
        val client = fakeClient ?: FakeAgentLlmClient().apply {
            if (llmResponse != null) {
                responseJson = llmResponse
            }
        }
        val targetScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
            activeScopes.add(it)
        }
        val service = SkillSuggestionService(
            context = context,
            workspaceManager = workspaceManager,
            lifecycleStore = lifecycleStore,
            skillIndexService = skillIndexService,
            providerProfileResolver = profileResolver,
            llmClientFactory = { _, _ -> client },
            scope = targetScope,
            contextCapacityResolver = contextCapacityResolver
        )
        return service to client
    }

    private suspend fun awaitCandidate(
        conversationId: Long,
        timeoutMs: Long = 5000L,
        predicate: (SkillCandidateAsset) -> Boolean
    ): SkillCandidateAsset {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val candidate = lifecycleStore.listCandidates(conversationId).firstOrNull(predicate)
            if (candidate != null) {
                return candidate
            }
            kotlinx.coroutines.delay(20)
        }
        val last = lifecycleStore.listCandidates(conversationId).firstOrNull()
        fail("Timed out waiting for candidate matching predicate. Current candidate: $last")
        error("Unreachable")
    }

    @Test
    fun testIneligibleWhenToolsLessThan3OrKindsLessThan2() = runBlocking {
        val (service, _) = createService()
        val convId = 1001L
        val sessId = "sess-1"
        val turnId = "turn-1"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        // 仅 2 个成功工具 -> 不合格
        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true)
            ),
            userVisiblePrompt = "测试任务",
            userVisibleAnswer = "已完成"
        )

        val list1 = lifecycleStore.listCandidates(convId)
        assertTrue(list1.isEmpty())

        // 3 个成功工具但仅 1 种工具类型 -> 不合格
        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = "turn-2",
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_read", success = true)
            ),
            userVisiblePrompt = "读取多个文件",
            userVisibleAnswer = "已读取"
        )
        val list2 = lifecycleStore.listCandidates(convId)
        assertTrue(list2.isEmpty())
    }

    @Test
    fun testIneligibleWhenToolFailedOrPermissionRejectedOrNotXiaowan() = runBlocking {
        val (service, _) = createService()
        val convId = 1002L
        val sessId = "sess-2"

        // 包含失败工具 -> 不合格
        service.onPromptAdmitted(sessId, "turn-err", "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))
        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = "turn-err",
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = false)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "部分完成"
        )
        assertTrue(lifecycleStore.listCandidates(convId).isEmpty())

        // 包含权限拒绝 -> 不合格
        service.onPromptAdmitted(sessId, "turn-perm", "req-2", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))
        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = "turn-perm",
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true, permissionRejected = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "被拦截"
        )
        assertTrue(lifecycleStore.listCandidates(convId).isEmpty())


        // 非小万 Agent（例如 Claude 或 subagent） -> 不触发
        service.onPromptAdmitted(sessId, "turn-claude", "req-3", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))
        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = "turn-claude",
            agentId = "claude-code",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "完成"
        )
        assertTrue(lifecycleStore.listCandidates(convId).isEmpty())
    }


    @Test
    fun testAutoSuggestionsDisabledCancelsAndIgnoresNew() = runBlocking {
        val (service, _) = createService()
        val convId = 1004L
        val sessId = "sess-4"
        val turnId = "turn-4"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        // 关闭自动建议
        service.setAutoSuggestionsEnabled(false)

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )

        val list = lifecycleStore.listCandidates(convId)
        assertTrue(list.isEmpty())
    }

    @Test
    fun testIdempotentTurnSettlementDoesNotDuplicateCandidates() = runBlocking {
        val (service, _) = createService()
        val convId = 1005L
        val sessId = "sess-5"
        val turnId = "turn-5"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        val toolCalls = listOf(
            TurnToolExecutionSummary("file_read", success = true),
            TurnToolExecutionSummary("file_write", success = true),
            TurnToolExecutionSummary("file_move", success = true)
        )

        // 第一次调用
        service.onSettledAndHistoryCommitted(convId, sessId, turnId, "xiaowan", "end_turn", null, toolCalls, "任务", "答复")
        // 第二次重复调用（同 Turn 重复通知）
        service.onSettledAndHistoryCommitted(convId, sessId, turnId, "xiaowan", "end_turn", null, toolCalls, "任务", "答复")

        val candidate = awaitCandidate(convId) { it.state == "pending_review" }
        val list = lifecycleStore.listCandidates(convId)
        assertEquals(1, list.size)
    }

    @Test
    fun testProviderChangedOrInvalidSkipsGeneration() = runBlocking {
        val (service, _) = createService(profileResolver = { null }) // 模拟 Provider 失效无法解析
        val convId = 1006L
        val sessId = "sess-6"
        val turnId = "turn-6"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, "deleted-provider", "gpt-4o", "fake-digest")

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )
        val candidate = awaitCandidate(convId) { it.state == "skipped" }
        val list = lifecycleStore.listCandidates(convId)
        assertEquals(1, list.size)
        assertEquals("skipped", candidate.state)
        assertEquals("provider_changed", candidate.skipReason)
    }

    @Test
    fun testSameProviderIdWithChangedIdentityRejectedWithoutGlobalFallback() = runBlocking {
        val admittedProfile = testProvider
        val changedProfile = testProvider.copy(apiKey = "changed-key-999")

        // profileResolver 返回 ID 相同但 apiKey/凭据不同的 Provider
        val (service, _) = createService(
            profileResolver = { id -> if (id == admittedProfile.id) changedProfile else null }
        )
        val convId = 1018L
        val sessId = "sess-18"
        val turnId = "turn-18"
        service.onPromptAdmitted(
            sessId,
            turnId,
            "req-1",
            convId,
            admittedProfile.id,
            "gpt-4o",
            computeDigest(admittedProfile)
        )

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )

        val cand = awaitCandidate(convId) { it.state == "skipped" }
        assertEquals("skipped", cand.state)
        assertEquals("provider_changed", cand.skipReason)
    }

    @Test
    fun testXiaowanSessionAdmissionWithCapturedProviderSnapshotReachesPendingReviewWhileNullDigestSkips() = runBlocking {
        val (service, _) = createService()
        val convIdSuccess = 1019L
        val convIdNullDigest = 1020L
        val sessId = "sess-19"
        val turnIdSuccess = "turn-19-success"
        val turnIdNullDigest = "turn-19-null-digest"

        val toolCalls = listOf(
            TurnToolExecutionSummary("file_read", success = true),
            TurnToolExecutionSummary("file_write", success = true),
            TurnToolExecutionSummary("file_move", success = true)
        )

        // 1. 真实修复后的准入：小万会话快照成功捕获 providerProfile 与 credentialDigest
        service.onPromptAdmitted(
            sessId,
            turnIdSuccess,
            "req-success",
            convIdSuccess,
            testProvider.id,
            "gpt-4o",
            computeDigest(testProvider)
        )
        service.onSettledAndHistoryCommitted(
            conversationId = convIdSuccess,
            sessionId = sessId,
            turnId = turnIdSuccess,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = toolCalls,
            userVisiblePrompt = "创建有效构建技能",
            userVisibleAnswer = "任务已完成，符合条件"
        )
        val candidate = awaitCandidate(convIdSuccess) { it.state == "pending_review" }
        assertEquals("pending_review", candidate.state)
        assertNull(candidate.skipReason)

        // 2. 根因复现（修复前）：小万未捕获会话 provider 导致准入 credentialDigest 为 null
        service.onPromptAdmitted(
            sessId,
            turnIdNullDigest,
            "req-null",
            convIdNullDigest,
            testProvider.id,
            "gpt-4o",
            null
        )
        service.onSettledAndHistoryCommitted(
            conversationId = convIdNullDigest,
            sessionId = sessId,
            turnId = turnIdNullDigest,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = toolCalls,
            userVisiblePrompt = "创建有效构建技能2",
            userVisibleAnswer = "任务已完成，符合条件"
        )
        val skippedCandidate = awaitCandidate(convIdNullDigest) { it.state == "skipped" }
        assertEquals("skipped", skippedCandidate.state)
        assertEquals("provider_changed", skippedCandidate.skipReason)
    }

    @Test
    fun testIndexIsolationBeforeApprovalAndApprovalWritesInstalledSkill() = runBlocking {
        val (service, _) = createService()
        val convId = 1007L
        val sessId = "sess-7"
        val turnId = "turn-7"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )

        val candidate = awaitCandidate(convId) { it.state == "pending_review" }

        // 验证审核前索引隔离：候选文件在 skill-suggestions/，不在 skillsRoot，未列入已安装技能
        val installedBefore = skillIndexService.listInstalledSkills()
        assertFalse(installedBefore.any { it.id == "auto-release-flow" })
        val candFile = File(tempDir, "skill-suggestions/candidates/${candidate.candidateId}.json")
        assertTrue(candFile.exists())
        val inSkillsRoot = File(workspaceManager.skillsRoot(), "auto-release-flow")
        assertFalse(inSkillsRoot.exists())

        // 审核批准该技能
        val approvalResult = service.approveSuggestion(
            candidateId = candidate.candidateId,
            expectedRevision = candidate.revision,
            targetSkillId = "auto-release-flow",
            expectedCurrentContentHash = null
        )

        assertEquals("approved", approvalResult.candidate.state)
        assertEquals("auto-release-flow", approvalResult.skill.id)
        assertEquals("trial", approvalResult.skill.verificationStatus)
        assertNotNull(approvalResult.skill.revisionId)

        // 审核后：已安装并处于 trial 状态
        val installedAfter = skillIndexService.listInstalledSkills()
        val installedSkill = installedAfter.firstOrNull { it.id == "auto-release-flow" }
        assertNotNull(installedSkill)
        assertEquals("trial", installedSkill!!.verificationStatus)
        assertEquals(approvalResult.skill.revisionId, installedSkill.revisionId)

        // 不可变版本快照已生成
        val snapshot = lifecycleStore.getRevision("auto-release-flow", installedSkill.revisionId!!)
        assertNotNull(snapshot)
        assertEquals(installedSkill.revisionId, snapshot!!.revisionId)
    }

    @Test
    fun testStaleApproveOrRejectedCandidateProducesNoInstalledFilesOrRegistryEffects() = runBlocking {
        val (service, _) = createService()
        val convId = 1019L
        val sessId = "sess-19"
        val turnId = "turn-19"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "构建任务",
            userVisibleAnswer = "已完成"
        )

        val candidate = awaitCandidate(convId) { it.state == "pending_review" }

        // 1. 用户先拒绝该建议
        val rejected = service.rejectSuggestion(candidate.candidateId, candidate.revision)
        assertEquals("rejected", rejected.state)

        // 2. 尝试批准已被拒绝的候选 -> 抛出异常
        var rejectApproveThrew = false
        try {
            service.approveSuggestion(
                candidateId = candidate.candidateId,
                expectedRevision = rejected.revision,
                targetSkillId = "rejected-skill-target",
                expectedCurrentContentHash = null
            )
        } catch (e: Exception) {
            rejectApproveThrew = true
        }
        assertTrue(rejectApproveThrew)

        // 验证：未在 skillsRoot 产生任何文件，未在注册表中安装，未产生快照
        val skillDir = File(workspaceManager.skillsRoot(), "rejected-skill-target")
        assertFalse(skillDir.exists())
        val installedList = skillIndexService.listInstalledSkills()
        assertFalse(installedList.any { it.id == "rejected-skill-target" })
        assertTrue(lifecycleStore.listRevisions("rejected-skill-target").isEmpty())

        // 3. 过期版本 (stale revision) 尝试批准 -> 抛出异常且无副作用
        var staleApproveThrew = false
        try {
            service.approveSuggestion(
                candidateId = candidate.candidateId,
                expectedRevision = rejected.revision + 999L,
                targetSkillId = "stale-skill-target",
                expectedCurrentContentHash = null
            )
        } catch (e: IllegalStateException) {
            staleApproveThrew = true
            assertTrue(e.message?.contains("skill_revision_conflict") == true)
        }
        assertTrue(staleApproveThrew)
        assertFalse(File(workspaceManager.skillsRoot(), "stale-skill-target").exists())
        assertFalse(skillIndexService.listInstalledSkills().any { it.id == "stale-skill-target" })
    }

    @Test
    fun testBlockedGenerationAndDisableCancelsWithoutBecomingPendingReviewLater() = runBlocking {
        val blockDeferred = CompletableDeferred<Unit>()
        val fakeClient = FakeAgentLlmClient(blockDeferred = blockDeferred)
        val (service, _) = createService(fakeClient = fakeClient)
        val convId = 1012L
        val sessId = "sess-12"
        val turnId = "turn-12"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "自动化构建任务",
            userVisibleAnswer = "任务进行中"
        )

        val generatingCand = awaitCandidate(convId) { it.state == "generating" }
        assertEquals("generating", generatingCand.state)

        // 用户关闭自动技能建议：必须取消正在生成的任务并置为 interrupted
        service.setAutoSuggestionsEnabled(false)
        val interruptedCand = awaitCandidate(convId) { it.state == "interrupted" }
        assertEquals("interrupted", interruptedCand.state)
        assertEquals("skill_disabled", interruptedCand.failureReason)

        // 此时即便外部 LLM 异步返回，绝不能转为 pending_review
        blockDeferred.complete(Unit)
        val finalCand = lifecycleStore.listCandidates(convId).first()
        assertEquals("interrupted", finalCand.state)
        assertEquals("skill_disabled", finalCand.failureReason)
        assertFalse(lifecycleStore.listCandidates(convId).any { it.state == "pending_review" })
    }

    @Test
    fun testDisableLeavesPendingReviewIntactAndInterruptsQueued() = runBlocking {
        val (service1, _) = createService()
        val convId1 = 1013L
        val sessId1 = "sess-13-1"
        val turnId1 = "turn-13-1"

        // 1. 第一个任务正常生成并达到 pending_review
        service1.onPromptAdmitted(sessId1, turnId1, "req-1", convId1, testProvider.id, "gpt-4o", computeDigest(testProvider))
        service1.onSettledAndHistoryCommitted(
            conversationId = convId1,
            sessionId = sessId1,
            turnId = turnId1,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务1",
            userVisibleAnswer = "答复1"
        )

        val cand1 = awaitCandidate(convId1) { it.state == "pending_review" }
        assertEquals("pending_review", cand1.state)
        val rev1 = cand1.revision

        // 2. 第二个任务设置阻塞生成
        val blockDeferred = CompletableDeferred<Unit>()
        val blockingClient = FakeAgentLlmClient(blockDeferred = blockDeferred)
        val (service2, _) = createService(fakeClient = blockingClient)
        val convId2 = 1014L
        val sessId2 = "sess-13-2"
        val turnId2 = "turn-13-2"
        service2.onPromptAdmitted(sessId2, turnId2, "req-2", convId2, testProvider.id, "gpt-4o", computeDigest(testProvider))
        service2.onSettledAndHistoryCommitted(
            conversationId = convId2,
            sessionId = sessId2,
            turnId = turnId2,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务2",
            userVisibleAnswer = "答复2"
        )

        val cand2 = awaitCandidate(convId2) { it.state == "generating" }
        assertEquals("generating", cand2.state)

        // 3. 关闭自动建议：cand1 (pending_review) 保持完好，cand2 (generating) 被中断
        service2.setAutoSuggestionsEnabled(false)
        val cand2Interrupted = awaitCandidate(convId2) { it.state == "interrupted" }
        assertEquals("interrupted", cand2Interrupted.state)
        assertEquals("skill_disabled", cand2Interrupted.failureReason)
        blockDeferred.complete(Unit)
        // 验证：已就绪的 cand1 完好保留在 pending_review
        val cand1After = lifecycleStore.listCandidates(convId1).first()
        assertEquals("pending_review", cand1After.state)
        assertEquals(rev1, cand1After.revision)

        // 验证：进行中的 cand2 被置为 interrupted
        val cand2After = lifecycleStore.listCandidates(convId2).first()
        assertEquals("interrupted", cand2After.state)
        assertEquals("skill_disabled", cand2After.failureReason)
    }

    @Test
    fun testOversizedInputBeyond16KiBAnd4000CharsSkippedNotTruncated() = runBlocking {
        var llmCalled = false
        val fakeClient = object : AgentLlmClient {
            override suspend fun streamTurn(
                request: cn.com.omnimind.baselib.llm.ChatCompletionRequest,
                onReasoningUpdate: (suspend (String) -> Unit)?,
                onContentUpdate: (suspend (String) -> Unit)?,
                onToolCallInput: (suspend (cn.com.omnimind.baselib.llm.AssistantToolCall) -> Unit)?,
                allowTransportRetry: Boolean
            ): ChatCompletionTurn {
                llmCalled = true
                return ChatCompletionTurn(
                    message = ChatCompletionMessage(
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("""{"suggestion":null}""")
                    )
                )
            }
        }
        val (service, _) = createService(fakeClient = fakeClient)
        val convId = 1015L
        val sessId = "sess-15"
        val turnId = "turn-15"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        // 构造 >16KiB 且超过 4000 字符的输入（每个中文字符 3 字节，6900 字 = ~20.7KiB > 16384）
        val largePrompt = "这是一个测试大文本输入技能建议的超长任务描述。".repeat(300)
        assertTrue(largePrompt.length > 4000)
        assertTrue(largePrompt.toByteArray(Charsets.UTF_8).size > 16 * 1024)

        service.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = largePrompt,
            userVisibleAnswer = "已完成"
        )

        val cand = awaitCandidate(convId) { it.state == "skipped" }
        assertEquals("skipped", cand.state)
        assertEquals("too_large", cand.skipReason)
        assertFalse(llmCalled) // 绝不截断发送给模型
    }

    @Test
    fun testUnavailableOrSmallContextBudgetSkipped() = runBlocking {
        // 1. contextCapacityResolver 返回 null（预算不可用） -> skipped(too_large)
        val (serviceUnavailable, _) = createService(
            contextCapacityResolver = { _, _, _ -> null }
        )
        val convId1 = 1016L
        val sessId1 = "sess-16-1"
        val turnId1 = "turn-16-1"
        serviceUnavailable.onPromptAdmitted(sessId1, turnId1, "req-1", convId1, testProvider.id, "gpt-4o", computeDigest(testProvider))
        serviceUnavailable.onSettledAndHistoryCommitted(
            conversationId = convId1,
            sessionId = sessId1,
            turnId = turnId1,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )
        val cand1 = awaitCandidate(convId1) { it.state == "skipped" }
        assertEquals("skipped", cand1.state)
        assertEquals("too_large", cand1.skipReason)

        // 2. contextCapacityResolver 返回过小容量（如 50 token，不足以容纳输入与 2048 预留） -> skipped(too_large)
        val (serviceSmall, _) = createService(
            contextCapacityResolver = { _, _, _ -> 50 }
        )
        val convId2 = 1017L
        val sessId2 = "sess-16-2"
        val turnId2 = "turn-16-2"
        serviceSmall.onPromptAdmitted(sessId2, turnId2, "req-2", convId2, testProvider.id, "gpt-4o", computeDigest(testProvider))
        serviceSmall.onSettledAndHistoryCommitted(
            conversationId = convId2,
            sessionId = sessId2,
            turnId = turnId2,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )
        val cand2 = awaitCandidate(convId2) { it.state == "skipped" }
        assertEquals("skipped", cand2.state)
        assertEquals("too_large", cand2.skipReason)
    }

    @Test
    fun testFeedbackRequiresSubsequentUsageAndEnforcesRetireConstraints() = runBlocking {
        val (service, _) = createService()
        val convId = 1008L
        val sessId = "sess-8"
        val turnId = "turn-8"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            convId, sessId, turnId, "xiaowan", "end_turn", null,
            listOf(
                TurnToolExecutionSummary("file_read", true),
                TurnToolExecutionSummary("file_write", true),
                TurnToolExecutionSummary("file_move", true)
            ),
            "任务", "答复"
        )
        val candidate = awaitCandidate(convId) { it.state == "pending_review" }
        val approval = service.approveSuggestion(candidate.candidateId, candidate.revision, "auto-release-flow", null)
        val revisionId = approval.skill.revisionId!!
        val snapshot = lifecycleStore.getRevision("auto-release-flow", revisionId)!!

        // 1. 无后续实际使用记录 -> 提交反馈被拒绝
        var ineligibleThrew = false
        try {
            service.feedbackSkill(
                skillId = "auto-release-flow",
                revisionId = revisionId,
                conversationId = 2001L,
                sessionId = "subsequent-sess",
                turnId = "subsequent-turn",
                expectedRevision = 1L,
                action = "retain"
            )
        } catch (e: IllegalStateException) {
            ineligibleThrew = true
            assertTrue(e.message?.contains("skill_feedback_ineligible") == true)
        }
        assertTrue(ineligibleThrew)

        // 2. 模拟后续独立 Turn 成功读取（skills_read）该版本并正式完成提交
        val subTurnConvId = 2001L
        val subTurnSessId = "subsequent-sess"
        val subTurnId = "subsequent-turn"
        lifecycleStore.recordUsage(
            SkillUsageRecord(
                conversationId = subTurnConvId,
                sessionId = subTurnSessId,
                turnId = subTurnId,
                skillId = "auto-release-flow",
                revisionId = revisionId,
                contentHash = revisionId,
                timestamp = snapshot.createdAt + 1000L,
                completed = true
            )
        )

        // 查询反馈资格：应满足 eligible = true
        val eligibility = service.getFeedbackEligibility("auto-release-flow")
        assertTrue(eligibility.eligible)
        assertEquals(subTurnId, eligibility.usage?.turnId)

        // 3. 提交 retire 反馈 -> 状态转为 retired，enabled 置为 false，且标记 wasManuallyDisabled
        val feedbackRetire = service.feedbackSkill(
            skillId = "auto-release-flow",
            revisionId = revisionId,
            conversationId = subTurnConvId,
            sessionId = subTurnSessId,
            turnId = subTurnId,
            expectedRevision = 1L,
            action = "retire"
        )
        assertEquals("retired", feedbackRetire.verificationStatus)
        assertFalse(feedbackRetire.enabled)

        // 4. 尝试恢复被用户手动停用/退役的版本 -> 严禁复活！
        var restoreThrew = false
        try {
            service.restoreSkillRevision(
                skillId = "auto-release-flow",
                revisionId = revisionId,
                expectedRevision = 2L,
                expectedCurrentContentHash = revisionId
            )
        } catch (e: IllegalStateException) {
            restoreThrew = true
            assertTrue(e.message?.contains("skill_revision_retired") == true)
        }
        assertTrue(restoreThrew)
    }

    @Test
    fun testUsageContentHashMismatchCannotFeedbackEvenIfDiskReverts() = runBlocking {
        val (service, _) = createService()
        val convId = 1020L
        val sessId = "sess-20"
        val turnId = "turn-20"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            convId, sessId, turnId, "xiaowan", "end_turn", null,
            listOf(
                TurnToolExecutionSummary("file_read", true),
                TurnToolExecutionSummary("file_write", true),
                TurnToolExecutionSummary("file_move", true)
            ),
            "任务", "答复"
        )
        val candidate = awaitCandidate(convId) { it.state == "pending_review" }
        val approval = service.approveSuggestion(candidate.candidateId, candidate.revision, "content-mismatch-skill", null)
        val revisionId = approval.skill.revisionId!!
        val snapshot = lifecycleStore.getRevision("content-mismatch-skill", revisionId)!!

        // 记录使用记录：虽然 completed=true 且 revisionId 匹配，但 contentHash 不匹配（使用时哈希与版本快照不一致）
        val subConvId = 2020L
        val subSessId = "sub-sess-20"
        val subTurnId = "sub-turn-20"
        lifecycleStore.recordUsage(
            SkillUsageRecord(
                conversationId = subConvId,
                sessionId = subSessId,
                turnId = subTurnId,
                skillId = "content-mismatch-skill",
                revisionId = revisionId,
                contentHash = "tampered-content-hash-at-runtime",
                timestamp = snapshot.createdAt + 1000L,
                completed = true
            )
        )

        // 此时磁盘文件哈希完全正确（等于 revisionId），但 usage.contentHash 不匹配
        val installed = skillIndexService.findInstalledSkill("content-mismatch-skill")!!
        val diskHash = computeSha256(File(installed.skillFilePath).readText())
        assertEquals(revisionId, diskHash)

        // 提交反馈必须被拒绝
        var threw = false
        try {
            service.feedbackSkill(
                skillId = "content-mismatch-skill",
                revisionId = revisionId,
                conversationId = subConvId,
                sessionId = subSessId,
                turnId = subTurnId,
                expectedRevision = 1L,
                action = "retain"
            )
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message?.contains("skill_feedback_ineligible") == true)
        }
        assertTrue(threw)
    }

    @Test
    fun testSourceTurnNotEligibleForFeedbackOnlyPostApprovalIndependentTurnPermitted() = runBlocking {
        val (service, _) = createService()
        val convId = 1021L
        val sessId = "sess-21"
        val turnId = "turn-21"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            convId, sessId, turnId, "xiaowan", "end_turn", null,
            listOf(
                TurnToolExecutionSummary("file_read", true),
                TurnToolExecutionSummary("file_write", true),
                TurnToolExecutionSummary("file_move", true)
            ),
            "任务", "答复"
        )
        val candidate = awaitCandidate(convId) { it.state == "pending_review" }
        val approval = service.approveSuggestion(candidate.candidateId, candidate.revision, "source-turn-guard-skill", null)
        val revisionId = approval.skill.revisionId!!
        val snapshot = lifecycleStore.getRevision("source-turn-guard-skill", revisionId)!!

        // 1. 尝试伪造或使用“生成该候选的原来源 Turn”作为使用凭证
        lifecycleStore.recordUsage(
            SkillUsageRecord(
                conversationId = convId,
                sessionId = sessId,
                turnId = turnId,
                skillId = "source-turn-guard-skill",
                revisionId = revisionId,
                contentHash = revisionId,
                timestamp = snapshot.createdAt + 1000L,
                completed = true
            )
        )

        var sourceThrew = false
        try {
            service.feedbackSkill(
                skillId = "source-turn-guard-skill",
                revisionId = revisionId,
                conversationId = convId,
                sessionId = sessId,
                turnId = turnId,
                expectedRevision = 1L,
                action = "retain"
            )
        } catch (e: IllegalStateException) {
            sourceThrew = true
            assertTrue(e.message?.contains("skill_feedback_ineligible") == true)
        }
        assertTrue(sourceThrew)

        // 2. 只有后续独立的完成 Turn（不同会话/Turn）才被允许
        val indepConvId = 2021L
        val indepSessId = "indep-sess-21"
        val indepTurnId = "indep-turn-21"
        lifecycleStore.recordUsage(
            SkillUsageRecord(
                conversationId = indepConvId,
                sessionId = indepSessId,
                turnId = indepTurnId,
                skillId = "source-turn-guard-skill",
                revisionId = revisionId,
                contentHash = revisionId,
                timestamp = snapshot.createdAt + 2000L,
                completed = true
            )
        )

        val result = service.feedbackSkill(
            skillId = "source-turn-guard-skill",
            revisionId = revisionId,
            conversationId = indepConvId,
            sessionId = indepSessId,
            turnId = indepTurnId,
            expectedRevision = 1L,
            action = "retain"
        )
        assertEquals("verified", result.verificationStatus)
        assertTrue(result.enabled)
    }

    @Test
    fun testContentModifiedExternallyInvalidatesFeedbackAndResetsToUnverified() = runBlocking {
        val (service, _) = createService()
        val convId = 1009L
        val sessId = "sess-9"
        val turnId = "turn-9"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            convId, sessId, turnId, "xiaowan", "end_turn", null,
            listOf(
                TurnToolExecutionSummary("file_read", true),
                TurnToolExecutionSummary("file_write", true),
                TurnToolExecutionSummary("file_move", true)
            ),
            "任务", "答复"
        )
        val candidate = awaitCandidate(convId) { it.state == "pending_review" }
        val approval = service.approveSuggestion(candidate.candidateId, candidate.revision, "tampered-skill", null)
        val revisionId = approval.skill.revisionId!!
        val snapshot = lifecycleStore.getRevision("tampered-skill", revisionId)!!

        // 记录后续有效使用
        val subConvId = 2009L
        val subSessId = "sub-sess-9"
        val subTurnId = "sub-turn-9"
        lifecycleStore.recordUsage(
            SkillUsageRecord(
                conversationId = subConvId,
                sessionId = subSessId,
                turnId = subTurnId,
                skillId = "tampered-skill",
                revisionId = revisionId,
                contentHash = revisionId,
                timestamp = snapshot.createdAt + 1000L,
                completed = true
            )
        )

        // 模拟外部手动修改 SKILL.md
        val installed = skillIndexService.findInstalledSkill("tampered-skill")!!
        val skillFile = File(installed.skillFilePath)
        skillFile.writeText("tampered external content")

        // 尝试提交反馈 -> 触发 content_modified，重置状态为 unverified
        var modifiedThrew = false
        try {
            service.feedbackSkill(
                skillId = "tampered-skill",
                revisionId = revisionId,
                conversationId = subConvId,
                sessionId = subSessId,
                turnId = subTurnId,
                expectedRevision = 1L,
                action = "retain"
            )
        } catch (e: IllegalStateException) {
            modifiedThrew = true
            assertTrue(e.message?.contains("skill_content_modified") == true)
        }
        assertTrue(modifiedThrew)

        // 验证已重置为 unverified
        val reloaded = skillIndexService.findInstalledSkill("tampered-skill")!!
        assertEquals("unverified", reloaded.verificationStatus)
    }

    @Test
    fun testStrictModelJsonUnknownFieldsAndTypesFail() = runBlocking {
        val testCases = listOf(
            // 纯非 JSON 文本
            "抱歉，我无法提炼通用技能。",
            // 缺少 suggestion 根字段
            """{"invalid_root": 123}""",
            // suggestion 类型错误（字符串非对象）
            """{"suggestion": "not-an-object"}""",
            // suggestion 类型错误（数字）
            """{"suggestion": 42}""",
            // 名称包含大写（非法 kebab-case）
            """{"suggestion": {"name": "InvalidName", "description": "描述", "bodyMarkdown": "## 适用条件\n正文"}}""",
            // 描述为空
            """{"suggestion": {"name": "valid-name", "description": "", "bodyMarkdown": "## 适用条件\n正文"}}""",
            // 正文为空
            """{"suggestion": {"name": "valid-name", "description": "描述", "bodyMarkdown": ""}}"""
        )

        var caseIndex = 1
        for (invalidJson in testCases) {
            val (service, _) = createService(llmResponse = invalidJson)
            val convId = 3000L + caseIndex
            val sessId = "sess-invalid-$caseIndex"
            val turnId = "turn-invalid-$caseIndex"
            service.onPromptAdmitted(sessId, turnId, "req-$caseIndex", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

            service.onSettledAndHistoryCommitted(
                conversationId = convId,
                sessionId = sessId,
                turnId = turnId,
                agentId = "xiaowan",
                stopReason = "end_turn",
                error = null,
                toolCalls = listOf(
                    TurnToolExecutionSummary("file_read", success = true),
                    TurnToolExecutionSummary("file_write", success = true),
                    TurnToolExecutionSummary("file_move", success = true)
                ),
                userVisiblePrompt = "任务",
                userVisibleAnswer = "答复"
            )

            val cand = awaitCandidate(convId) { it.state == "generation_failed" }
            assertEquals("generation_failed", cand.state)
            assertNotNull(cand.failureReason)
            caseIndex++
        }

        // suggestion: null -> skipped(no_suggestion)
        val (nullService, _) = createService(llmResponse = """{"suggestion": null}""")
        val nullConvId = 4001L
        val nullSessId = "sess-null"
        val nullTurnId = "turn-null"
        nullService.onPromptAdmitted(nullSessId, nullTurnId, "req-null", nullConvId, testProvider.id, "gpt-4o", computeDigest(testProvider))
        nullService.onSettledAndHistoryCommitted(
            conversationId = nullConvId,
            sessionId = nullSessId,
            turnId = nullTurnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "任务",
            userVisibleAnswer = "答复"
        )

        val nullCand = awaitCandidate(nullConvId) { it.state == "skipped" }
        assertEquals("skipped", nullCand.state)
        assertEquals("no_suggestion", nullCand.skipReason)
    }

    @Test
    fun testRegenerateRetainsOriginalSanitizedInputInMemoryAndFailsAfterRestart() = runBlocking {
        val (service1, _) = createService()
        val convId = 1022L
        val sessId = "sess-22"
        val turnId = "turn-22"
        service1.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service1.onSettledAndHistoryCommitted(
            conversationId = convId,
            sessionId = sessId,
            turnId = turnId,
            agentId = "xiaowan",
            stopReason = "end_turn",
            error = null,
            toolCalls = listOf(
                TurnToolExecutionSummary("file_read", success = true),
                TurnToolExecutionSummary("file_write", success = true),
                TurnToolExecutionSummary("file_move", success = true)
            ),
            userVisiblePrompt = "原始任务输入：自动化打包",
            userVisibleAnswer = "原始答复：已打包完成"
        )

        val cand1 = awaitCandidate(convId) { it.state == "pending_review" }

        // 1. 在同一个 service 实例中，内存保留了原始脱敏输入，重新生成成功
        val regen1 = service1.regenerateSuggestion(cand1.candidateId, cand1.revision)
        assertEquals("queued", regen1.state)
        val regenCand1 = awaitCandidate(convId) { it.state == "pending_review" && it.revision > cand1.revision }
        assertEquals("pending_review", regenCand1.state)

        // 2. 模拟进程重启：新建 service 实例（共享同一 lifecycleStore 与 workspace，但 sourceJobs 内存映射为空）
        val (service2, _) = createService()
        var restartThrew = false
        try {
            service2.regenerateSuggestion(regenCand1.candidateId, regenCand1.revision)
        } catch (e: IllegalStateException) {
            restartThrew = true
            assertTrue(e.message?.contains("skill_source_unavailable") == true)
        }
        assertTrue(restartThrew)
    }

    @Test
    fun testCasRevisionConflictOnSaveAndApprove() = runBlocking {
        val (service, _) = createService()
        val convId = 1010L
        val sessId = "sess-10"
        val turnId = "turn-10"
        service.onPromptAdmitted(sessId, turnId, "req-1", convId, testProvider.id, "gpt-4o", computeDigest(testProvider))

        service.onSettledAndHistoryCommitted(
            convId, sessId, turnId, "xiaowan", "end_turn", null,
            listOf(
                TurnToolExecutionSummary("file_read", true),
                TurnToolExecutionSummary("file_write", true),
                TurnToolExecutionSummary("file_move", true)
            ),
            "任务", "答复"
        )

        val candidate = awaitCandidate(convId) { it.state == "pending_review" }

        // 传递过期的 expectedRevision -> 抛出 skill_revision_conflict
        var casThrew = false
        try {
            service.saveSuggestion(
                candidateId = candidate.candidateId,
                expectedRevision = candidate.revision + 99L,
                name = "conflict-flow",
                description = "冲突描述",
                bodyMarkdown = "正文"
            )
        } catch (e: IllegalStateException) {
            casThrew = true
            assertTrue(e.message?.contains("skill_revision_conflict") == true)
        }
        assertTrue(casThrew)
    }

    @Test
    fun testPathTraversalSecurityBoundaryThrowsValidationFailed() = runBlocking {
        var threw = false
        try {
            lifecycleStore.getCandidate("../outside")
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(threw)

        var threwRev = false
        try {
            lifecycleStore.getRevision("skill/sub", "rev")
        } catch (e: IllegalArgumentException) {
            threwRev = true
            assertTrue(e.message?.contains("skill_validation_failed") == true)
        }
        assertTrue(threwRev)
    }

    @Test
    fun testTrustedAncestorAliasSharesCanonicalStoreAndRejectsDescendantSymlinkEscape() = runBlocking {
        val parentTestDir = Files.createTempDirectory("skill-ancestor-alias-test").toFile()
        try {
            val realAncestorDir = File(parentTestDir, "real_ancestor").apply { mkdirs() }
            val aliasAncestorDir = File(parentTestDir, "alias_ancestor")
            var ancestorSymlinkCreated = false
            try {
                Files.createSymbolicLink(aliasAncestorDir.toPath(), realAncestorDir.toPath())
                ancestorSymlinkCreated = true
            } catch (_: Exception) {
            }
            assumeTrue("OS or user permissions do not allow creating directory symlinks", ancestorSymlinkCreated)

            val aliasStoreDir = File(aliasAncestorDir, "skill-suggestions")
            val realStoreDir = File(realAncestorDir, "skill-suggestions")

            val storeViaAlias = SkillLifecycleStore(context, baseDirOverride = aliasStoreDir)
            val storeViaReal = SkillLifecycleStore(context, baseDirOverride = realStoreDir)

            // 1. 验证不存在路径读取：初始无 settings.json 时 getSettings 读取默认设置（未抛出边界异常）
            val defaultSettings = storeViaAlias.getSettings()
            assertTrue("Default settings must have autoSuggestionsEnabled=true", defaultSettings.autoSuggestionsEnabled)

            // 2. 验证存在路径读写：updateSettings 写入原子文件，并验证读回更新值
            val updatedSettings = storeViaAlias.updateSettings(false)
            assertFalse("Updated settings must have autoSuggestionsEnabled=false", updatedSettings.autoSuggestionsEnabled)
            val readBackSettings = storeViaAlias.getSettings()
            assertFalse("Read back settings via alias must reflect false", readBackSettings.autoSuggestionsEnabled)
            val realStoreSettings = storeViaReal.getSettings()
            assertFalse("Real store must read identical settings file without duplicate stores", realStoreSettings.autoSuggestionsEnabled)

            // 3. 验证候选资产的存在/不存在路径覆盖
            val nonExistentCandidate = storeViaAlias.getCandidate("cand-alias-missing")
            assertNull("Non-existent candidate must return null", nonExistentCandidate)

            val candidate = SkillCandidateAsset(
                candidateId = "cand-alias-1",
                conversationId = 2026L,
                sessionId = "sess-alias-test",
                turnId = "turn-alias-test",
                state = "pending_review",
                name = "alias-skill-candidate",
                description = "Candidate saved through ancestor alias directory",
                bodyMarkdown = "## 步骤\n1. 执行任务"
            )
            storeViaAlias.saveCandidate(candidate)

            val fetchedViaAlias = storeViaAlias.getCandidate("cand-alias-1")
            assertNotNull("Saved candidate must exist when queried through alias", fetchedViaAlias)
            assertEquals("alias-skill-candidate", fetchedViaAlias?.name)

            val fetchedViaReal = storeViaReal.getCandidate("cand-alias-1")
            assertNotNull("Candidate must be read back via real store instance", fetchedViaReal)
            assertEquals("alias-skill-candidate", fetchedViaReal?.name)

            // 证明没有复制第二 store，底层真实路径完全一致且实体文件落在真实目录下
            assertTrue(Files.isSameFile(realStoreDir.toPath(), aliasStoreDir.toPath()))
            val writtenCandidateFile = File(realStoreDir, "candidates/cand-alias-1.json")
            assertTrue("Candidate file must exist within canonical real store", writtenCandidateFile.exists())

            // 4. 证明外部 symlink 文件越界访问仍拒绝，且外部文件内容未改变
            val outsideFile = File(parentTestDir, "outside-secret-${java.util.UUID.randomUUID()}.json")
            val originalOutsideText = "{\"secret\": \"unmodified_data\"}"
            outsideFile.writeText(originalOutsideText)

            val linkCandidateFile = File(realStoreDir, "candidates/cand-escape.json")
            var candidateSymlinkCreated = false
            try {
                Files.createSymbolicLink(linkCandidateFile.toPath(), outsideFile.toPath())
                candidateSymlinkCreated = true
            } catch (_: Exception) {
            }

            if (candidateSymlinkCreated) {
                var threwSymlink = false
                try {
                    storeViaAlias.getCandidate("cand-escape")
                } catch (e: IllegalArgumentException) {
                    threwSymlink = true
                    assertTrue(
                        "Exception must indicate skill_validation_failed: ${e.message}",
                        e.message?.contains("skill_validation_failed") == true
                    )
                }
                assertTrue("Descendant symlink escaping root must be rejected", threwSymlink)
                assertEquals(
                    "External file must not be modified after rejected symlink access",
                    originalOutsideText,
                    outsideFile.readText()
                )
            }
        } finally {
            parentTestDir.deleteRecursively()
        }
    }
}
