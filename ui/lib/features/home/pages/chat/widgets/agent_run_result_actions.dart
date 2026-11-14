import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/tool_activity_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_diff_viewer.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/terminal_output_utils.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/workspace_change_preview_sheet.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_artifact_preview_page.dart';
import 'package:ui/features/home/pages/skill_store/widgets/skill_candidate_review_sheet.dart';
import 'package:ui/services/agent_skill_store_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/virtual_screen_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/streaming_text.dart';

/// Single artifact record extracted from a completed agent turn.
class AgentRunArtifactItem {
  const AgentRunArtifactItem({
    required this.title,
    required this.path,
    this.uri,
    this.shellPath,
    this.mimeType,
    this.previewKind,
    this.size,
  });

  final String title;
  final String path;
  final String? uri;
  final String? shellPath;
  final String? mimeType;
  final String? previewKind;
  final dynamic size;
}

/// Identifiers for matching a turn against the live virtual screen state.
class AgentRunLiveSceneTarget {
  const AgentRunLiveSceneTarget({
    required this.displayId,
    this.sessionId,
    this.turnId,
    this.conversationId,
  });

  final int displayId;
  final String? sessionId;
  final String? turnId;
  final String? conversationId;
}


/// Extracts all unique artifacts produced during this turn.
List<AgentRunArtifactItem> resolveAgentRunArtifacts(AgentRunTimelineGroup group) {
  final items = <AgentRunArtifactItem>[];
  final seen = <String>{};

  void addArtifact(Map<String, dynamic> raw) {
    final path = (raw['androidPath'] ?? raw['path'] ?? '').toString().trim();
    final uri = (raw['uri'] ?? '').toString().trim();
    if (path.isEmpty && uri.isEmpty) return;

    final key = path.isNotEmpty ? path : uri;
    if (seen.contains(key)) return;
    seen.add(key);

    final title = (raw['title'] ??
            raw['fileName'] ??
            raw['name'] ??
            (path.isNotEmpty ? path.split('/').last : 'artifact'))
        .toString()
        .trim();

    items.add(
      AgentRunArtifactItem(
        title: title.isEmpty ? 'artifact' : title,
        path: path,
        uri: uri.isEmpty ? null : uri,
        shellPath: (raw['workspacePath'] ?? raw['shellPath'])?.toString(),
        mimeType: raw['mimeType']?.toString(),
        previewKind: raw['previewKind']?.toString(),
        size: raw['size'] ?? raw['sizeBytes'],
      ),
    );
  }

  for (final message in group.allMessagesOldestFirst) {
    final cardData = message.cardData;
    if (cardData != null) {
      final rawArtifacts = cardData['artifacts'];
      if (rawArtifacts is List) {
        for (final item in rawArtifacts.whereType<Map>()) {
          addArtifact(item.map((k, v) => MapEntry(k.toString(), v)));
        }
      }
      final rawArtifact = cardData['artifact'];
      if (rawArtifact is Map) {
        addArtifact(rawArtifact.map((k, v) => MapEntry(k.toString(), v)));
      }
      final rawExtras = cardData['rawExtras'];
      if (rawExtras is Map && rawExtras['artifacts'] is List) {
        for (final item in (rawExtras['artifacts'] as List).whereType<Map>()) {
          addArtifact(item.map((k, v) => MapEntry(k.toString(), v)));
        }
      }
    }
    for (final attachment in message.historyPayloadAttachments) {
      addArtifact(attachment);
    }
  }
  return items;
}

/// Resolves diff summary from messages in this turn.
AgentDiffSummary? resolveAgentRunDiffSummary(AgentRunTimelineGroup group) {
  for (final message in group.allMessagesOldestFirst) {
    final cardData = message.cardData;
    if (cardData == null) continue;
    final diffText = (cardData['diffText'] ?? '').toString().trim();
    if (diffText.isNotEmpty) {
      final summary = parseAgentDiffText(diffText);
      if (summary.files.isNotEmpty) return summary;
    }
    final rawTerminal = resolveAgentToolTerminalOutput(cardData);
    final extracted = extractAgentDiffText(
      cardData,
      outputText: diffText.isNotEmpty ? diffText : rawTerminal,
    );
    if (extracted != null && extracted.trim().isNotEmpty) {
      final summary = parseAgentDiffText(extracted);
      if (summary.files.isNotEmpty) return summary;
    }
  }
  return null;
}

/// Resolves any workspace_change action in this turn.
Map<String, dynamic>? resolveAgentRunWorkspaceChangeAction(
  AgentRunTimelineGroup group,
) {
  for (final message in group.allMessagesOldestFirst) {
    final cardData = message.cardData;
    if (cardData == null) continue;
    final actions = cardData['actions'];
    if (actions is List) {
      for (final action in actions.whereType<Map>()) {
        if ((action['type'] ?? '').toString().trim().toLowerCase() ==
            'workspace_change') {
          return action.map((k, v) => MapEntry(k.toString(), v));
        }
      }
    }
  }
  return null;
}

/// Extracts the unique canonical turnId from group messages.
/// Returns null if there are no canonical turnIds or more than one (ambiguous).
String? _canonicalGroupTurnId(AgentRunTimelineGroup group) {
  final turnIds = <String>{};
  for (final message in group.allMessagesOldestFirst) {
    final t = message.turnId?.trim();
    if (t != null && t.isNotEmpty) {
      turnIds.add(t);
    }
  }
  if (turnIds.length == 1) {
    return turnIds.single;
  }
  return null;
}

/// Resolves virtual screen target if this turn interacted with a display.
AgentRunLiveSceneTarget? resolveAgentRunLiveSceneTarget(
  AgentRunTimelineGroup group, {
  int? conversationId,
}) {
  int? foundDisplayId;
  String? foundSessionId;
  String? foundConversationId;

  int? parseDisplayId(dynamic val) {
    if (val is int && val > 0) return val;
    if (val is num && val > 0) return val.toInt();
    if (val is String) {
      final parsed = int.tryParse(val);
      if (parsed != null && parsed > 0) return parsed;
    }
    return null;
  }

  for (final message in group.allMessagesOldestFirst) {
    final cardData = message.cardData;
    if (cardData != null) {
      final dId = parseDisplayId(cardData['displayId']) ??
          parseDisplayId(cardData['display_id']) ??
          (cardData['data'] is Map
              ? parseDisplayId((cardData['data'] as Map)['display_id'] ??
                  (cardData['data'] as Map)['displayId'])
              : null);
      if (dId != null) {
        foundDisplayId = dId;
      }

      if (foundSessionId == null || foundSessionId.isEmpty) {
        final s = message.sessionId ?? cardData['sessionId']?.toString();
        if (s != null && s.isNotEmpty) foundSessionId = s;
      }


      if (foundConversationId == null || foundConversationId.isEmpty) {
        final c = cardData['conversationId']?.toString();
        if (c != null && c.isNotEmpty) foundConversationId = c;
      }
    } else {
      if (foundSessionId == null && message.sessionId != null) {
        foundSessionId = message.sessionId;
      }
    }
  }

  if (foundDisplayId == null) {
    return null;
  }

  final canonicalTurn = _canonicalGroupTurnId(group);
  final effectiveConvId = conversationId?.toString() ?? foundConversationId;


  return AgentRunLiveSceneTarget(
    displayId: foundDisplayId,
    sessionId: foundSessionId,
    turnId: canonicalTurn,
    conversationId: effectiveConvId,
  );
}

/// Extracts conversationId from group messages if not provided explicitly.
int? resolveGroupConversationId(AgentRunTimelineGroup group) {
  for (final msg in group.allMessagesOldestFirst) {
    final cId = msg.cardData?['conversationId'] ??
        msg.content?['conversationId'] ??
        msg.streamMeta?['conversationId'];
    if (cId is int && cId > 0) return cId;
    if (cId is num && cId > 0) return cId.toInt();
    if (cId is String) {
      final parsed = int.tryParse(cId);
      if (parsed != null && parsed > 0) return parsed;
    }
  }
  return null;
}

/// Result actions bar displayed only when an agent turn completes.
///
/// Order of actions:
/// 1. 打开产物 (Open Artifacts)
/// 2. 查看变更 (View Changes)
/// 3. 查看现场 (View Live Scene)
/// 4. 技能建议 (Skill Suggestion)
///
/// If a category has no data, it is completely hidden. If all are empty, renders SizedBox.shrink().
class AgentRunResultActionsBar extends StatefulWidget {
  const AgentRunResultActionsBar({
    super.key,
    required this.group,
    this.conversationId,
    this.conversationOwner,
    this.padding = const EdgeInsets.symmetric(vertical: 8),
  });

  final AgentRunTimelineGroup group;
  final int? conversationId;
  final String? conversationOwner;
  final EdgeInsetsGeometry padding;

  @override
  State<AgentRunResultActionsBar> createState() =>
      _AgentRunResultActionsBarState();
}
class _AgentRunResultActionsBarState extends State<AgentRunResultActionsBar> {
  AgentSkillSuggestionItem? _skillCandidate;
  int _skillCandidateRequestId = 0;

  bool get _isXiaowan {
    final raw = widget.group.agentId.trim().toLowerCase();
    if (raw == 'xiaowan-acp' || raw == 'xiaowan') return true;
    if (raw == 'generic-agent' || raw.isEmpty) {
      final owner = widget.conversationOwner?.trim().toLowerCase();
      if (owner == 'xiaowan-acp' || owner == 'xiaowan') {
        return true;
      }
    }
    return false;
  }

  int? get _effectiveConversationId =>
      widget.conversationId ?? resolveGroupConversationId(widget.group);
  StreamSubscription<Map<String, dynamic>>? _skillAssetsSubscription;


  @override
  void initState() {
    super.initState();
    _subscribeSkillAssets();
    if (_isXiaowan && !widget.group.isRunning) {
      _loadSkillCandidate();
    }
  }

  void _subscribeSkillAssets() {
    _skillAssetsSubscription?.cancel();
    _skillAssetsSubscription =
        AssistsMessageService.skillAssetsChangedStream.listen((event) {
      final convId = _effectiveConversationId;
      final turnId = _canonicalGroupTurnId(widget.group);
      if (convId == null || turnId == null) return;

      final eventConvId = event['conversationId'];
      final eventTurnId = event['turnId']?.toString().trim();

      final convMatches = (eventConvId == convId ||
          (eventConvId is num && eventConvId.toInt() == convId) ||
          (eventConvId is String && int.tryParse(eventConvId) == convId));
      final turnMatches = eventTurnId != null && eventTurnId == turnId;

      if (convMatches && turnMatches) {
        if (mounted && _isXiaowan && !widget.group.isRunning) {
          _loadSkillCandidate();
        }
      }
    });
  }

  @override
  void didUpdateWidget(covariant AgentRunResultActionsBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    final taskChanged = widget.group.taskId != oldWidget.group.taskId;
    final convChanged = widget.conversationId != oldWidget.conversationId ||
        resolveGroupConversationId(widget.group) !=
            resolveGroupConversationId(oldWidget.group);
    final agentChanged = widget.group.agentId != oldWidget.group.agentId ||
        widget.conversationOwner != oldWidget.conversationOwner;
    final runningChanged = widget.group.isRunning != oldWidget.group.isRunning;
    final canonicalTurnChanged =
        _canonicalGroupTurnId(widget.group) != _canonicalGroupTurnId(oldWidget.group);

    if (taskChanged || convChanged || agentChanged || runningChanged || canonicalTurnChanged) {
      _skillCandidateRequestId++;
      if (taskChanged ||
          convChanged ||
          agentChanged ||
          widget.group.isRunning ||
          !_isXiaowan ||
          canonicalTurnChanged) {
        _skillCandidate = null;
      }
      _subscribeSkillAssets();
      if (_isXiaowan && !widget.group.isRunning) {
        _loadSkillCandidate();
      }
    }
  }

  @override
  void dispose() {
    _skillCandidateRequestId++;
    _skillAssetsSubscription?.cancel();
    _skillAssetsSubscription = null;
    super.dispose();
  }

  Future<void> _loadSkillCandidate() async {
    final convId = _effectiveConversationId;
    final turnId = _canonicalGroupTurnId(widget.group);
    if (convId == null || convId <= 0 || turnId == null || !_isXiaowan || widget.group.isRunning) {
      if (mounted && _skillCandidate != null) {
        setState(() {
          _skillCandidate = null;
        });
      }
      return;
    }

    final requestId = ++_skillCandidateRequestId;
    try {
      final res = await AgentSkillStoreService.listSuggestions(
        conversationId: convId,
        turnId: turnId,
      );
      if (!mounted || requestId != _skillCandidateRequestId) return;
      if (_effectiveConversationId != convId ||
          _canonicalGroupTurnId(widget.group) != turnId ||
          !_isXiaowan ||
          widget.group.isRunning) {
        return;
      }
      final match = res.items.where((item) => item.turnId == turnId).firstOrNull;

      setState(() {
        _skillCandidate = match;
      });
    } catch (_) {
      // Graceful fallback if skill suggestion store is not configured
    }
  }

  Future<void> _handleSkillCandidateTap(
    BuildContext context,
    AgentSkillSuggestionItem candidate,
  ) async {
    final isEnglish = LegacyTextLocalizer.isEnglish;
    if (candidate.isPendingReview) {
      await showSkillCandidateReviewSheet(
        context: context,
        candidate: candidate,
        onSkillsChanged: _loadSkillCandidate,
      );
    } else if (candidate.isGenerating) {
      showToast(
        isEnglish
            ? 'Skill suggestion is generating in background...'
            : '技能建议正在后台生成中，请稍候...',
        type: ToastType.info,
      );
    } else if (candidate.isGenerationFailed) {
      if (candidate.canRegenerate) {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(isEnglish ? 'Skill generation failed' : '技能建议生成失败'),
            content: Text(
              candidate.failureReason ??
                  (isEnglish
                      ? 'Would you like to try regenerating the skill suggestion?'
                      : '是否尝试重新生成技能建议？'),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: Text(isEnglish ? 'Cancel' : '取消'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: Text(isEnglish ? 'Regenerate' : '重新生成'),
              ),
            ],
          ),
        );
        if (confirmed == true && mounted) {
          try {
            await AgentSkillStoreService.regenerateSuggestion(
              candidateId: candidate.candidateId,
              expectedRevision: candidate.revision,
            );
            _loadSkillCandidate();
          } catch (e) {
            showToast(
              isEnglish ? 'Regeneration failed' : '重新生成失败：$e',
              type: ToastType.error,
            );
          }
        }
      } else {
        showToast(
          candidate.failureReason ??
              (isEnglish ? 'Skill generation failed' : '技能建议生成失败'),
          type: ToastType.error,
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    // Only completed turns have result actions
    if (widget.group.isRunning) {
      return const SizedBox.shrink();
    }

    final artifacts = resolveAgentRunArtifacts(widget.group);
    final diffSummary = resolveAgentRunDiffSummary(widget.group);
    final workspaceChangeAction =
        resolveAgentRunWorkspaceChangeAction(widget.group);
    final hasChanges = diffSummary != null || workspaceChangeAction != null;
    final liveSceneTarget = resolveAgentRunLiveSceneTarget(
      widget.group,
      conversationId: _effectiveConversationId,
    );
    final currentCanonicalTurn = _canonicalGroupTurnId(widget.group);
    final hasCandidateAction = _isXiaowan &&
        currentCanonicalTurn != null &&
        _skillCandidate != null &&
        _skillCandidate!.turnId == currentCanonicalTurn &&
        (_skillCandidate!.isPendingReview ||
            _skillCandidate!.isGenerating ||
            _skillCandidate!.isGenerationFailed);

    final hasAnyAction = artifacts.isNotEmpty ||
        hasChanges ||
        liveSceneTarget != null ||
        hasCandidateAction;

    if (!hasAnyAction) {
      return const SizedBox.shrink();
    }

    final isEnglish = LegacyTextLocalizer.isEnglish;

    return Padding(
      key: ValueKey('agent-run-result-actions-${widget.group.taskId}'),
      padding: widget.padding,
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          // 1. 打开产物
          if (artifacts.isNotEmpty)
            _ResultActionButton(
              key: const ValueKey('agent-result-action-open-artifacts'),
              icon: LucideIcons.fileText,
              label: isEnglish ? 'Open artifacts' : '打开产物',
              count: artifacts.length > 1 ? artifacts.length : null,
              onPressed: () => _handleOpenArtifacts(context, artifacts),
            ),

          // 2. 查看变更
          if (hasChanges)
            _ResultActionButton(
              key: const ValueKey('agent-result-action-view-changes'),
              icon: LucideIcons.fileDiff,
              label: isEnglish ? 'View changes' : '查看变更',
              onPressed: () => _handleViewChanges(
                context,
                diffSummary: diffSummary,
                workspaceChangeAction: workspaceChangeAction,
              ),
            ),

          // 3. 查看现场
          if (liveSceneTarget != null)
            _ResultActionButton(
              key: const ValueKey('agent-result-action-view-scene'),
              icon: LucideIcons.tv,
              label: isEnglish ? 'View live scene' : '查看现场',
              onPressed: () => _handleViewScene(context, liveSceneTarget),
            ),

          // 4. 技能建议
          if (hasCandidateAction)
            _ResultActionButton(
              key: const ValueKey('agent-result-action-skill-suggestions'),
              icon: _skillCandidate!.isGenerationFailed
                  ? LucideIcons.alertCircle
                  : LucideIcons.sparkles,
              label: _skillCandidate!.isGenerating
                  ? (isEnglish ? 'Skill generating...' : '技能建议生成中...')
                  : (_skillCandidate!.isGenerationFailed
                      ? (isEnglish ? 'Skill failed' : '技能建议生成失败')
                      : (isEnglish ? 'Skill review' : '技能建议待审核')),
              onPressed: () =>
                  _handleSkillCandidateTap(context, _skillCandidate!),
            ),
        ],
      ),
    );
  }

  Future<void> _handleOpenArtifacts(
    BuildContext context,
    List<AgentRunArtifactItem> artifacts,
  ) async {
    if (artifacts.length == 1) {
      await openSingleArtifact(context, artifacts.first);
    } else {
      await showArtifactSelectionSheet(context: context, artifacts: artifacts);
    }
  }

  Future<void> _handleViewChanges(
    BuildContext context, {
    AgentDiffSummary? diffSummary,
    Map<String, dynamic>? workspaceChangeAction,
  }) async {
    final rawPayload = workspaceChangeAction?['payload'];
    final payload = rawPayload is Map
        ? rawPayload.map((k, v) => MapEntry(k.toString(), v))
        : const <String, dynamic>{};
    final changeId = (payload['changeId'] ??
            workspaceChangeAction?['changeId'] ??
            '')
        .toString()
        .trim();
    final conversationId = int.tryParse(
      (payload['conversationId'] ?? workspaceChangeAction?['conversationId'])
              ?.toString() ??
          '',
    );
    final workspaceId = (payload['workspaceId'] ??
            workspaceChangeAction?['workspaceId'])
        ?.toString();

    await showWorkspaceChangePreviewSheet(
      context: context,
      changeId: changeId.isNotEmpty ? changeId : 'diff',
      conversationId: conversationId,
      workspaceId: workspaceId,
      diffSummary: diffSummary,
    );
  }

  Future<void> _handleViewScene(
    BuildContext context,
    AgentRunLiveSceneTarget target,
  ) async {
    final isEnglish = LegacyTextLocalizer.isEnglish;
    try {
      final snapshot = await VirtualScreenService.getState();
      final hasTargetAllFields = target.displayId > 0 &&
          target.sessionId != null &&
          target.sessionId!.trim().isNotEmpty &&
          target.turnId != null &&
          target.turnId!.trim().isNotEmpty &&
          target.conversationId != null &&
          target.conversationId!.trim().isNotEmpty;

      final displayMatches = snapshot.displayId != null &&
          snapshot.displayId == target.displayId;
      final sessionMatches = snapshot.sessionId != null &&
          snapshot.sessionId == target.sessionId;
      final turnMatches = snapshot.turnId != null &&
          snapshot.turnId == target.turnId;
      final convMatches = snapshot.conversationId != null &&
          snapshot.conversationId == target.conversationId;

      if (hasTargetAllFields &&
          displayMatches &&
          sessionMatches &&
          turnMatches &&
          convMatches &&
          !snapshot.isAbsent) {
        await VirtualScreenService.openPreview(
          displayId: snapshot.displayId,
          sessionId: snapshot.sessionId,
        );
      } else {
        showToast(
          isEnglish ? 'Live scene closed or updated' : '现场已关闭或已更新',
          type: ToastType.warning,
        );
      }
    } catch (_) {
      showToast(
        isEnglish ? 'Live scene closed or updated' : '现场已关闭或已更新',
        type: ToastType.warning,
      );
    }
  }

}

/// Opens a single artifact, verifying existence first.
Future<void> openSingleArtifact(
  BuildContext context,
  AgentRunArtifactItem artifact,
) async {
  final isEnglish = LegacyTextLocalizer.isEnglish;
  if (artifact.path.isEmpty && (artifact.uri == null || artifact.uri!.isEmpty)) {
    showToast(isEnglish ? 'File is unavailable' : '文件已不可用',
        type: ToastType.error);
    return;
  }

  final metadata = OmnibotResourceService.describePath(
    artifact.path,
    uri: artifact.uri,
    shellPath: artifact.shellPath,
    title: artifact.title,
    mimeType: artifact.mimeType,
    previewKind: artifact.previewKind,
  );

  if (artifact.path.isNotEmpty && !metadata.exists) {
    showToast(isEnglish ? 'File is unavailable' : '文件已不可用',
        type: ToastType.error);
    return;
  }

  if (shouldOpenOmnibotArtifactInFullPage(metadata.previewKind)) {
    await OmnibotResourceService.openFilePath(
      metadata.path,
      uri: metadata.uri,
      title: metadata.title,
      previewKind: metadata.previewKind,
      mimeType: metadata.mimeType,
      shellPath: metadata.shellPath,
    );
    return;
  }

  if (!context.mounted) return;
  await showOmnibotArtifactPreviewSheet(context, metadata);
}

/// Bottom sheet presenting multiple artifacts for user selection.
class ArtifactSelectionSheet extends StatelessWidget {
  const ArtifactSelectionSheet({
    super.key,
    required this.artifacts,
  });

  final List<AgentRunArtifactItem> artifacts;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isEnglish = LegacyTextLocalizer.isEnglish;

    return SafeArea(
      top: false,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Drag handle
          Center(
            child: Container(
              margin: const EdgeInsets.only(top: 10, bottom: 8),
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: palette.borderStrong,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),

          // Header
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 16, 8),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    isEnglish ? 'Artifacts (${artifacts.length})' : '产物列表 (${artifacts.length})',
                    key: const ValueKey('artifact-selection-title'),
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 17,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                OmniCircleButton(
                  key: const ValueKey('artifact-selection-close'),
                  icon: LucideIcons.x,
                  size: 36,
                  iconSize: 18,
                  tooltip: isEnglish ? 'Close' : '关闭',
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ],
            ),
          ),

          // Artifact List
          Flexible(
            child: ListView.separated(
              key: const ValueKey('artifact-selection-list'),
              shrinkWrap: true,
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
              itemCount: artifacts.length,
              separatorBuilder: (_, __) => const SizedBox(height: 6),
              itemBuilder: (context, index) {
                final item = artifacts[index];
                final displayPath = item.shellPath ?? item.path;

                return InkWell(
                  key: ValueKey('artifact-select-item-$index'),
                  borderRadius: BorderRadius.circular(12),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await openSingleArtifact(context, item);
                  },
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 12,
                    ),
                    decoration: BoxDecoration(
                      color: palette.surfaceSecondary,
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: palette.borderSubtle),
                    ),
                    child: Row(
                      children: [
                        Icon(
                          item.previewKind == 'image'
                              ? LucideIcons.image
                              : (item.previewKind == 'code'
                                  ? LucideIcons.fileCode
                                  : LucideIcons.file),
                          size: 20,
                          color: palette.accentPrimary,
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                item.title,
                                style: TextStyle(
                                  color: palette.textPrimary,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                ),
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              if (displayPath.isNotEmpty) ...[
                                const SizedBox(height: 2),
                                Text(
                                  displayPath,
                                  style: TextStyle(
                                    color: palette.textSecondary,
                                    fontSize: 12,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ],
                            ],
                          ),
                        ),
                        Icon(
                          LucideIcons.chevronRight,
                          size: 16,
                          color: palette.textSecondary,
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

/// Helper function to display [ArtifactSelectionSheet].
Future<void> showArtifactSelectionSheet({
  required BuildContext context,
  required List<AgentRunArtifactItem> artifacts,
}) async {
  await showOmniGlassBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (_) => ArtifactSelectionSheet(artifacts: artifacts),
  );
}


class _ResultActionButton extends StatelessWidget {
  const _ResultActionButton({
    super.key,
    required this.icon,
    required this.label,
    required this.onPressed,
    this.count,
  });

  final IconData icon;
  final String label;
  final VoidCallback onPressed;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 15, color: palette.accentPrimary),
      label: Text(
        count != null ? '$label ($count)' : label,
        style: TextStyle(
          color: palette.textPrimary,
          fontSize: 13,
          fontWeight: FontWeight.w500,
        ),
      ),
      style: OutlinedButton.styleFrom(
        foregroundColor: palette.textPrimary,
        side: BorderSide(color: palette.borderStrong),
        backgroundColor: palette.surfaceSecondary.withValues(alpha: 0.6),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        minimumSize: const Size(44, 40),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
        ),
      ),
    );
  }
}
