import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);

function sourcePath(relativePath) {
  return path.join(repositoryRoot, relativePath);
}

test("setup inventory and package installation share the installation budget", async () => {
  const content = await source("app/src/main/java/cn/com/omnimind/bot/terminal/EmbeddedTerminalSetupManager.kt");
  const inventory = content.slice(content.indexOf("suspend fun getPackageInventory("),
    content.indexOf("suspend fun installPackages("));
  assert.match(inventory, /timeoutMs = SETUP_COMMAND_TIMEOUT_MS/);
  assert.doesNotMatch(inventory, /timeoutMs = 30_000/);
  const install = content.slice(content.indexOf("suspend fun installPackages("),
    content.indexOf('onProgress("status", "正在验证所选开发工具")'));
  assert.match(install, /timeoutMs = SETUP_COMMAND_TIMEOUT_MS/);
});

test("editing Harness configuration never restarts active sessions or reuses a stale launch snapshot", async () => {
  const content = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt");
  const writes = content.slice(content.indexOf("private suspend fun writeAgentConfig("),
    content.indexOf("private fun validateExpectedConfigRevision("));
  assert.doesNotMatch(writes, /\.disconnect\(|clearActiveTurnsForAgent\(/);
  assert.doesNotMatch(writes, /resolveCurrentProviderModelIds\(/);
  assert.doesNotMatch(content, /acpLaunchEnvironmentCache/);
  assert.match(writes, /validateExpectedConfigRevision/);
  assert.match(writes, /recordAgentConfigRevision/);
});


test("request forwarding and prompt completion do not invent variants or tool outcomes", async () => {
  const llm = await source("app/src/main/java/cn/com/omnimind/bot/agent/llm/AgentLlmClient.kt");
  const reducer = await source("ui/lib/services/agent_event_reducer.dart");
  assert.doesNotMatch(llm, /modelCandidates|StreamRequestVariant|buildRequestVariants/);
  assert.doesNotMatch(reducer, /_markUnfinishedToolCardsInterruptedForTask|_markToolCardsCompleteForTask/);
});

test("file skill and terminal tools never apply application character truncation", async () => {
  for (const file of ["FileToolHandler", "SkillsToolHandler", "TerminalToolHandler", "PrivilegedToolHandler", "SharedHelper"]) {
    const content = await source(`app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/${file}.kt`);
    assert.doesNotMatch(content, /truncateText|truncateTerminalTail/, file);
    if (file === "FileToolHandler") {
      // A caller-selected page size is not destructive output truncation.
      // Original-body reconstruction is covered by AgentFileReadSupportTest.
      assert.match(content, /args\["maxChars"\]/);
      assert.match(content, /AgentFileReadSupport\.read\(file, offset, lineStart, lineCount, maxChars\)/);
      assert.match(content, /metadata\.putAll\(page\.toPayload\(\)\)/);
      assert.match(content, /page\.nextOffset/);
    } else {
      assert.doesNotMatch(content, /maxChars/, file);
    }
  }
});

test("tool envelopes and user configuration have no host size policy", async () => {
  const adapter = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentEventAdapter.kt");
  const manager = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt");
  assert.doesNotMatch(adapter, /compactToolResultContent|outputTruncated|headTail/);
  assert.doesNotMatch(manager, /requireAgentConfigSize|MAX_AGENT_CONFIG_FILE_CHARS/);
});

test("Office previews do not discard document content", async () => {
  const content = await source("ui/lib/services/office_preview_service.dart");
  assert.doesNotMatch(content, /_maxDoc|_maxWorkbook|_maxCell|_maxSlide|_truncateText/);
});

test("orchestrator does not wrap transport failure in a second terminal path", async () => {
  const content = await source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt");
  assert.doesNotMatch(content, /TerminalTurnRequestFailure|var terminalError|streamTurnWithTransportPolicy/);
});

async function source(relativePath) {
  return readFile(sourcePath(relativePath), "utf8");
}



test("installed Skills are not hidden by an OmniBot platform keyword policy", async () => {
  const [handler, skillRuntime, failureHook, systemPrompt] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/SkillsToolHandler.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/skill/AgentSkillRuntime.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/skill/SelfImprovingSkillFailureHook.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentSystemPrompt.kt"),
  ]);

  assert.doesNotMatch(handler, /SkillCompatibilityChecker|homekit|healthkit|apple-|ios/i);
  assert.doesNotMatch(skillRuntime, /object SkillCompatibilityChecker|homekit|healthkit|apple-/i);
  assert.doesNotMatch(failureHook, /SkillCompatibilityChecker/);
  assert.doesNotMatch(systemPrompt, /SkillCompatibilityChecker/);
});




test("automatic compaction cannot create a private ACP lifecycle update", async () => {
  const [connection, reducer] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt"),
    source("ui/lib/services/agent_event_reducer.dart"),
  ]);

  const compactionCallback = connection.slice(
    connection.indexOf("override suspend fun onContextCompactionStateChanged"),
    connection.indexOf("override suspend fun onClarifyRequired"),
  );
  assert.doesNotMatch(compactionCallback, /emitUpdate|acpPresentationMeta/);
  assert.doesNotMatch(reducer, /_upsertAcpContextCompactionCard|_acpContextCompactionLabel/);
});

test("Flutter has no legacy capped history builder or synthetic user summary path", async () => {
  const dispatchSupport = await source(
    "ui/lib/features/home/pages/chat/mixins/chat_dispatch_support.dart",
  );

  assert.doesNotMatch(dispatchSupport, /buildConversationHistory|ChatService\.getRecentMessages/);
  assert.doesNotMatch(dispatchSupport, /kCompactedContextSummaryPrefix|_kMaxInlineImageBytes/);
  assert.doesNotMatch(dispatchSupport, /role['"]\s*:\s*['"]user['"].*context-summary/s);
});

test("private retry presentation cannot create or revive an ACP turn", async () => {
  const reducer = await source("ui/lib/services/agent_event_reducer.dart");
  const retryBlock = reducer.slice(
    reducer.indexOf("final retry = _asStringMap(presentation['retry']);"),
    reducer.indexOf("final recovery = _asStringMap(presentation['recovery']);"),
  );

  assert.doesNotMatch(retryBlock, /_touchActiveTurn\(/);
  assert.match(retryBlock, /_upsertAcpRetryPresentation/);
});

test("a manual retry keeps history and uses the ordinary explicit-send path", async () => {
  const actions = await source(
    "ui/lib/features/home/pages/chat/chat_page_user_message_actions.dart",
  );
  const retryStart = actions.indexOf("Future<void> _retryFailedAgentTurn(");
  const retryEnd = actions.indexOf("List<Map<String, dynamic>> _extractRetryAttachments", retryStart);
  const retryBody = actions.slice(retryStart, retryEnd);

  assert.ok(retryStart >= 0);
  assert.ok(retryEnd > retryStart);
  assert.match(retryBody, /await _retryUserMessageText\(/);
  assert.match(retryBody, /retainedUserMessageId: userMessage\.id/);
  assert.doesNotMatch(retryBody, /_tryAgentFlow\(/);
  assert.doesNotMatch(actions, /_buildPendingManualRetryMessage/);
  assert.doesNotMatch(actions, /agentMaxRetries'\]\s*=\s*.*\?\?\s*3/);
});

test("protocol-only skipped tool results do not create fake user-visible tool activity", async () => {
  const orchestrator = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
  );
  const syntheticResultBlock = orchestrator.slice(
    orchestrator.indexOf("private suspend fun appendSyntheticToolResultMessages"),
    orchestrator.indexOf("private fun buildSyntheticToolSkipMessage"),
  );

  assert.match(syntheticResultBlock, /appendToolResultMessage\(/);
  assert.doesNotMatch(syntheticResultBlock, /callback\.onToolCall(?:Start|Complete)\(/);
});

test("the WebChat browser bridge does not impose traversal or collection quotas", async () => {
  const bridge = await source(
    "app/src/main/java/cn/com/omnimind/bot/webchat/BrowserMirrorService.kt",
  );

  assert.match(bridge, /amount = arguments\.readInt\("amount"\)/);
  assert.match(bridge, /maxDepth = arguments\.readInt\("max_depth"\) \?: arguments\.readInt\("maxDepth"\)/);
  assert.match(bridge, /scrollCount = arguments\.readInt\("scroll_count"\) \?: arguments\.readInt\("scrollCount"\)/);
  assert.doesNotMatch(bridge, /coerceIn\(1, (?:20_000|8|20)\)/);
  assert.doesNotMatch(bridge, /\?: (?:500|5|10)/);
});

test("context compaction is an explicit slash command, not an automatic synthetic user turn", async () => {
  const openclawPage = await source("ui/lib/features/home/pages/chat/chat_page_openclaw.dart");

  assert.match(openclawPage, /trimmed == '\/compact' \|\| trimmed\.startsWith\('\/compact '\)/);
  assert.match(openclawPage, /_runtimeCoordinator\.beginContextCompaction\([\s\S]*?trigger: 'manual'/);
  assert.match(openclawPage, /AssistsMessageService\.compactConversationContext\(/);
});


test("terminal Agent failures do not use a host retryability guess", async () => {
  const orchestrator = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
  );

  assert.match(orchestrator, /callback\.onError\(message, retryable = true\)/);
  assert.match(orchestrator, /private fun terminalFailureMessage\(error: Throwable\)/);
  assert.doesNotMatch(orchestrator, /RetryDecision|classifyRetryableTurnFailure/);
  assert.doesNotMatch(orchestrator, /retryableStatus|looksLikeTransientTransportFailure/);
  assert.doesNotMatch(orchestrator, /insufficient_quota|monthly usage limit|available balance/);
});

test("the local ACP bridge has no private automatic-retry callback or presentation", async () => {
  const [callback, connection] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentModels.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt"),
  ]);

  assert.doesNotMatch(callback, /suspend fun onRetrying\(/);
  assert.doesNotMatch(connection, /override suspend fun onRetrying\(/);
  assert.doesNotMatch(connection, /"retry" to mapOf\(/);
});

test("the local Agent loop does not add an unreachable tool batch scheduler", async () => {
  const orchestrator = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
  );

  assert.match(orchestrator, /for \(call in validatedCalls\) \{/);
  assert.match(orchestrator, /round=\$round model_tool_calls=\$\{validatedCalls\.size}/);
  assert.doesNotMatch(orchestrator, /ToolBatch|batch\.parallel|awaitAll\(\)/);
});

test("native task persistence has no private reasoning-size ceiling", async () => {
  const assistsCoreManager = await source(
    "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt",
  );

  assert.doesNotMatch(
    assistsCoreManager,
    /MAX_PERSISTED_THINKING_CHARS|THINKING_TRUNCATION_NOTICE/,
  );
});

test("remote session hydration cannot infer or terminate the ACP lifecycle", async () => {
  const remoteCodex = await source(
    "ui/lib/features/home/pages/chat/chat_page_remote_codex.dart",
  );

  assert.match(remoteCodex, /A session snapshot is for initial history hydration only\./);
  assert.match(remoteCodex, /final previousActive = runtime\?\.isAiResponding \?\? false;/);
  assert.match(remoteCodex, /final isAiResponding = previousActive;/);
  assert.match(remoteCodex, /final activeTaskId = runtimeTaskId;/);
  assert.doesNotMatch(remoteCodex, /Timer\.periodic|assumeActive|gracePeriod|_inferRemoteCodexSnapshotActive|remoteCodexLatestTurnLooksExternallyActive/);
  assert.doesNotMatch(remoteCodex, /remoteCodexTaskId|remote_task_id/);
});

test("official ACP bridge upgrades remain declarative and require an explicit preparation pass", async () => {
  const catalog = JSON.parse(await source("app/src/main/assets/acp/agents.json"));
  const bridges = catalog.agents.flatMap(({ runtime }) =>
    (runtime?.managedAdapterPackages ?? [])
      .filter((spec) => spec.startsWith("@agentclientprotocol/"))
      .map((spec) => ({ runtime, spec })),
  );
  assert.ok(bridges.length > 0);
  for (const { runtime, spec } of bridges) {
    const match = spec.match(/^@agentclientprotocol\/([^@]+)@(\d+\.\d+\.\d+(?:-[\w.-]+)?)$/);
    assert.ok(match, `Bridge package must declare its installed version: ${spec}`);
    const [, name, version] = match;
    if (name === 'codex-acp') {
      assert.equal(runtime.preparationRevision, `${name}-${version}-message-completion-1-title-model-1`);
      assert.equal(runtime.managedInstallCommandAsset, 'acp/install-codex.sh');
      const installer = await source(`app/src/main/assets/${runtime.managedInstallCommandAsset}`);
      assert.ok(installer.includes(spec), 'Installer must install the catalog-pinned bridge');
    } else assert.equal(runtime.preparationRevision, `${name}-${version}`);
    assert.equal(runtime.managedInstallCommand, undefined);
  }
});

test("runtime resolves Harness capability from the official profile, not a private agent-id switch", async () => {
  const [adapters, configAdapters] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentConfigAdapters.kt"),
  ]);

  assert.match(adapters, /fun forProfile\(profile: AcpAgentProfile\)/);
  assert.doesNotMatch(adapters, /fun forAgentId\(/);
  assert.doesNotMatch(configAdapters, /buildSharedAgentProviderEnvironment\(/);
});

test("local MCP is declared only through the official ACP session surface", async () => {
  const [adapters, runtimeMcp, localRuntime] = await Promise.all([
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpHarnessAdapters.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeMcp.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/runtime/LocalAcpRuntime.kt"),
  ]);

  assert.doesNotMatch(adapters, /AcpHarnessMcpTransport|mcpTransport|mcpEnvironment/);
  assert.match(runtimeMcp, /internal fun buildLocalAgentAcpMcpServers\(\s*supportsHttp: Boolean,/);
  assert.match(runtimeMcp, /if \(!supportsHttp\) return emptyList\(\)/);
  assert.match(localRuntime, /buildLocalAgentAcpMcpServers\(\s*supportsHttp = shouldDeclareLocalServer,/);
});

test("MCP context and schedule lists do not impose a second result ceiling", async () => {
  const mcpServer = await source(
    "app/src/main/java/cn/com/omnimind/bot/mcp/AndroidDeviceMcpServer.kt",
  );

  const contextQuery = mcpServer.slice(mcpServer.indexOf('"context_apps_query"'));
  const scheduleList = mcpServer.slice(mcpServer.indexOf('"schedule_task_list"'));
  assert.match(contextQuery, /requestedLimit \?: Int\.MAX_VALUE/);
  assert.match(scheduleList, /requestedLimit\?\.let\(tasks::take\) \?: tasks/);
  assert.doesNotMatch(contextQuery.slice(0, contextQuery.indexOf('"file_transfer"')), /coerceIn\(1, 100\)|\?: 20/);
  assert.doesNotMatch(scheduleList.slice(0, scheduleList.indexOf('"schedule_task_update"')), /coerceIn\(1, 100\)|\?: 100/);
});

test("persisted conversation history never replaces a large item with a truncated projection", async () => {
  const [entryDao, databaseHelper, historyRepository, historySupport] = await Promise.all([
    source("baselib/src/main/java/cn/com/omnimind/baselib/database/AgentConversationEntryDao.kt"),
    source("baselib/src/main/java/cn/com/omnimind/baselib/database/DatabaseHelper.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistoryRepository.kt"),
    source("app/src/main/java/cn/com/omnimind/bot/agent/conversation/AgentConversationHistorySupport.kt"),
  ]);

  assert.doesNotMatch(entryDao, /SAFE_ENTRY_PROJECTION|getThreadEntries(?:Asc|Desc|DescPaged)Safe|getByThreadAndEntryIdSafe|payloadLimit|summaryLimit/);
  assert.doesNotMatch(databaseHelper, /getAgentConversationEntries(?:Asc|Desc|DescPaged)Safe|getAgentConversationEntryByThreadAndIdSafe/);
  assert.match(historyRepository, /DatabaseHelper\.getAgentConversationEntriesDescPaged\(/);
  assert.match(historySupport, /fun prepareEntryForStorage\(entry: AgentConversationEntry\): AgentConversationEntry \{[\s\S]*?return entry/);
  assert.doesNotMatch(historySupport, /fun materializeRecord\(/);
});

test("stdio MCP results preserve one complete projection for the tool loop and history", async () => {
  const stdioMcp = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/runtime/XiaowanMcpSession.kt",
  );
  const resultMapper = stdioMcp.slice(
    stdioMcp.indexOf("internal fun remoteMcpCallResult"),
    stdioMcp.indexOf("private fun sanitizeMcpToolSegment"),
  );

  assert.match(resultMapper, /\?: raw/);
  assert.match(resultMapper, /previewJson = raw,/);
  assert.doesNotMatch(resultMapper, /raw\.take\(600\)|raw\.take\(1200\)|raw\.length <= 1200/);
});

test("remote MCP results preserve one complete projection for the tool loop and history", async () => {
  const remoteMcp = await source(
    "app/src/main/java/cn/com/omnimind/bot/mcp/RemoteMcpClient.kt",
  );
  const summaryMapper = remoteMcp.slice(
    remoteMcp.indexOf("private fun buildSummaryText"),
    remoteMcp.indexOf("private fun deepStringMap"),
  );

  assert.match(summaryMapper, /return textBlocks\.joinToString\("\\n"\)/);
  assert.match(summaryMapper, /return gson\.toJson\(result\)/);
  assert.match(summaryMapper, /private fun buildPreviewJson\(result: Any\?\): String \{\s*return gson\.toJson\(result\)\s*\}/);
  assert.doesNotMatch(summaryMapper, /\.take\(|max(?:Chars|Length)|truncate/i);
});

test("memory search has no OmniBot default result ceiling", async () => {
  const memoryTool = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/MemoryToolHandler.kt",
  );
  const searchHandler = memoryTool.slice(
    memoryTool.indexOf('"memory_search" ->'),
    memoryTool.indexOf('"memory_write_daily" ->'),
  );

  assert.match(searchHandler, /val limit = requestedLimit \?: Int\.MAX_VALUE/);
  assert.doesNotMatch(searchHandler, /coerce(?:AtMost|In)|MAX_(?!VALUE)|DEFAULT_|\.take\(/);
});

test("tool detail keeps the complete persisted result while its compact preview stays visual-only", async () => {
  const transcript = await source(
    "ui/lib/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart",
  );
  const resultRenderer = transcript.slice(
    transcript.indexOf("String _buildStructuredOutputText"),
    transcript.indexOf("String _buildPreviewText"),
  );
  const detailSheet = transcript.slice(
    transcript.indexOf("class _AgentToolDetailContent"),
    transcript.indexOf("String _agentToolCopyText"),
  );

  assert.match(transcript, /int\? maxOutputLines,/);
  assert.match(resultRenderer, /maxOutputLines == null && rawMap\.isNotEmpty/);
  assert.match(resultRenderer, /final detailMap = [\s\S]*?rawMap[\s\S]*?: previewMap;/);
  assert.match(resultRenderer, /_formatCompleteResultPayload\(detailResult\)/);
  assert.match(transcript, /JsonEncoder\.withIndent\('  '\)\.convert\(jsonDecode\(normalized\)\)/);
  assert.doesNotMatch(transcript, /int maxChars = 6000|maxOutputLines: 80/);
  assert.doesNotMatch(detailSheet, /maxOutputLines:/);
});

test("the local ACP connection terminates prompts with PromptResponse, not private turn events", async () => {
  const connection = await source(
    "app/src/main/java/cn/com/omnimind/bot/agent/XiaowanAcpConnection.kt",
  );

  assert.match(connection, /Event\.SessionUpdateEvent\(update\)/);
  assert.match(connection, /Event\.PromptResponseEvent\(/);
  assert.match(connection, /PromptResponse\(stopReason = StopReason\.CANCELLED\)/);
  assert.doesNotMatch(connection, /AgentStreamEvent|acp\/presentation|codex\/event/);
  assert.doesNotMatch(connection, /turn\/(?:started|completed|failed|cancelled)/);
});
