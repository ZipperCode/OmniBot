import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_diff_viewer.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/workspace_change_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';

/// Bottom sheet displaying workspace changes with diff preview and explicit restore/discard confirmation.
class WorkspaceChangePreviewSheet extends StatefulWidget {
  const WorkspaceChangePreviewSheet({
    super.key,
    required this.changeId,
    this.conversationId,
    this.workspaceId,
    this.diffSummary,
    this.diffText,
    this.initialItem,
    this.onRestored,
    this.onDiscarded,
  });

  final String changeId;
  final int? conversationId;
  final String? workspaceId;
  final AgentDiffSummary? diffSummary;
  final String? diffText;
  final WorkspaceChangeItem? initialItem;
  final VoidCallback? onRestored;
  final VoidCallback? onDiscarded;

  @override
  State<WorkspaceChangePreviewSheet> createState() =>
      _WorkspaceChangePreviewSheetState();
}

class _WorkspaceChangePreviewSheetState
    extends State<WorkspaceChangePreviewSheet> {
  bool _isLoading = false;
  bool _isActionInProgress = false;
  WorkspaceChangePreview? _preview;
  String? _errorMessage;

  bool get _isPureDiff =>
      widget.changeId.isEmpty || widget.changeId == 'diff';

  @override
  void initState() {
    super.initState();
    if (!_isPureDiff) {
      _loadPreview();
    }
  }

  Future<void> _loadPreview() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final preview = await WorkspaceChangeService.previewChange(
      changeId: widget.changeId,
      conversationId: widget.conversationId,
      workspaceId: widget.workspaceId,
    );

    if (!mounted) return;
    setState(() {
      _isLoading = false;
      _preview = preview;
      if (preview == null && widget.diffText == null && widget.diffSummary == null) {
        _errorMessage = LegacyTextLocalizer.isEnglish
            ? 'Change record unavailable'
            : '变更记录不可用或已被清除';
      }
    });
  }

  Future<void> _handleRestore() async {
    final preview = _preview;
    if (preview == null || !preview.canRestore) return;

    final isEnglish = LegacyTextLocalizer.isEnglish;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(isEnglish ? 'Confirm restore?' : '确认恢复文件？'),
        content: Text(
          isEnglish
              ? 'Restore file to state prior to this change? Does not undo commands, messages, or deployments.'
              : '确认将文件恢复为本次修改前的状态？仅恢复这些文件，不撤销命令、消息或模块部署。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(isEnglish ? 'Cancel' : '取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(isEnglish ? 'Confirm restore' : '确认恢复'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() => _isActionInProgress = true);
    final expectedHashes = preview.expectedAfterHashes;
    if (expectedHashes.isEmpty) {
      setState(() => _isActionInProgress = false);
      showToast(
        isEnglish
            ? 'Missing file hash fingerprint for restore'
            : '缺少恢复所需的文件状态指纹，无法恢复',
        type: ToastType.error,
      );
      return;
    }

    final result = await WorkspaceChangeService.restoreChange(
      changeId: preview.item.changeId,
      expectedRevision: preview.item.revision,
      expectedAfterHashes: expectedHashes,
      conversationId: widget.conversationId,
      workspaceId: widget.workspaceId,
    );

    if (!mounted) return;
    setState(() => _isActionInProgress = false);

    if (result.isRestored) {
      showToast(
        isEnglish ? 'File restored successfully' : '文件已恢复',
        type: ToastType.success,
      );
      widget.onRestored?.call();
      Navigator.of(context).pop();
    } else if (result.isConflict) {
      showToast(
        isEnglish
            ? 'Restore conflict (workspace_change_conflict): file content has been modified externally.'
            : '恢复冲突 (workspace_change_conflict)：当前文件已被其他操作修改，无法恢复。',
        type: ToastType.error,
      );
      _loadPreview();
    } else if (result.isBusy) {
      showToast(
        isEnglish
            ? 'Workspace busy (workspace_change_busy): workspace is held by another active task.'
            : '工作区繁忙 (workspace_change_busy)：工作区正被其他任务占用，请稍后再试。',
        type: ToastType.warning,
      );
    } else {
      showToast(
        result.message.isNotEmpty
            ? result.message
            : (isEnglish ? 'Restore failed' : '恢复失败'),
        type: ToastType.error,
      );
    }
  }

  Future<void> _handleDiscard() async {
    final preview = _preview;
    if (preview == null) return;

    final isEnglish = LegacyTextLocalizer.isEnglish;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(isEnglish ? 'Discard snapshot?' : '确认清除恢复快照？'),
        content: Text(
          isEnglish
              ? 'Clear restore snapshot record? This will not modify your current workspace files.'
              : '清除恢复快照记录？此操作不会改变当前工作区中的实际文件。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(isEnglish ? 'Cancel' : '取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(
              isEnglish ? 'Discard snapshot' : '清除快照',
              style: const TextStyle(color: Colors.red),
            ),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() => _isActionInProgress = true);
    final result = await WorkspaceChangeService.discardChange(
      changeId: preview.item.changeId,
      expectedRevision: preview.item.revision,
      conversationId: widget.conversationId,
      workspaceId: widget.workspaceId,
    );

    if (!mounted) return;
    setState(() => _isActionInProgress = false);

    if (result.isDiscarded) {
      showToast(
        isEnglish ? 'Snapshot discarded' : '已清除恢复快照',
        type: ToastType.info,
      );
      widget.onDiscarded?.call();
      Navigator.of(context).pop();
    } else {
      showToast(
        result.message.isNotEmpty
            ? result.message
            : (isEnglish ? 'Discard failed' : '清除失败'),
        type: ToastType.error,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isEnglish = LegacyTextLocalizer.isEnglish;

    final summary = widget.diffSummary ??
        _preview?.diffSummary ??
        (widget.diffText != null && widget.diffText!.trim().isNotEmpty
            ? parseAgentDiffText(widget.diffText!)
            : (_preview?.diffText != null && _preview!.diffText.trim().isNotEmpty
                ? parseAgentDiffText(_preview!.diffText)
                : null));

    final item = _preview?.item ?? widget.initialItem;
    final canRestore = _preview?.canRestore == true && item?.isReady == true;

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
            padding: const EdgeInsets.fromLTRB(20, 4, 16, 10),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Flexible(
                            child: Text(
                              isEnglish ? 'Workspace Changes' : '受管工作区变更',
                              key: const ValueKey('workspace-change-preview-title'),
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 17,
                                fontWeight: FontWeight.w600,
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                          if (item != null) ...[
                            const SizedBox(width: 8),
                            _StatusBadge(
                              label: item.operationLabel,
                              color: palette.accentPrimary,
                              backgroundColor: palette.surfaceSecondary,
                            ),
                            const SizedBox(width: 6),
                            _StatusBadge(
                              label: item.statusLabel,
                              color: item.isReady
                                  ? Colors.green
                                  : (item.isConflict ? Colors.orange : palette.textSecondary),
                              backgroundColor: palette.surfaceSecondary,
                            ),
                          ],
                        ],
                      ),
                      if (item != null) ...[
                        const SizedBox(height: 3),
                        Text(
                          item.displayPath,
                          key: const ValueKey('workspace-change-preview-path'),
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 13,
                            fontFamily: 'monospace',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ] else if (!_isPureDiff && widget.changeId.isNotEmpty) ...[
                        const SizedBox(height: 2),
                        Text(
                          widget.changeId,
                          key: const ValueKey('workspace-change-preview-id'),
                          style: TextStyle(
                            color: palette.textSecondary,
                            fontSize: 12,
                            fontFamily: 'monospace',
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ],
                  ),
                ),
                OmniCircleButton(
                  key: const ValueKey('workspace-change-preview-close'),
                  icon: LucideIcons.x,
                  size: 36,
                  iconSize: 18,
                  tooltip: isEnglish ? 'Close' : '关闭',
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ],
            ),
          ),

          // Notice banner
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary,
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: palette.borderSubtle),
            ),
            child: Row(
              children: [
                Icon(
                  LucideIcons.info,
                  size: 16,
                  color: palette.textSecondary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    isEnglish
                        ? 'Only restores these files; does not undo commands, messages, or deployments.'
                        : '仅恢复这些文件；不撤销命令、消息或模块部署。',
                    key: const ValueKey('workspace-change-preview-notice'),
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                      height: 1.4,
                    ),
                  ),
                ),
              ],
            ),
          ),

          // Unavailable reason banner if cannot restore
          if (item != null && (!item.isReady || (item.unavailableReason != null && item.unavailableReason!.isNotEmpty))) ...[
            Container(
              margin: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: palette.borderStrong),
              ),
              child: Row(
                children: [
                  Icon(
                    item.isConflict ? LucideIcons.alertTriangle : LucideIcons.alertCircle,
                    size: 16,
                    color: item.isConflict ? Colors.orange : palette.textSecondary,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      item.unavailableReasonText ??
                          item.unavailableReason ??
                          (item.isConflict ? '当前快照存在修改冲突，不可恢复' : '此变更快照不可用于恢复'),
                      key: const ValueKey('workspace-change-unavailable-reason'),
                      style: TextStyle(
                        color: palette.textPrimary,
                        fontSize: 12,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],

          // Diff / Content
          Flexible(
            child: Container(
              constraints: const BoxConstraints(maxHeight: 380),
              margin: const EdgeInsets.fromLTRB(20, 8, 20, 12),
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: palette.borderSubtle),
              ),
              clipBehavior: Clip.antiAlias,
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : (_preview?.isBinary == true)
                      ? Center(
                          child: Padding(
                            padding: const EdgeInsets.all(24),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                Icon(LucideIcons.binary, size: 36, color: palette.textSecondary),
                                const SizedBox(height: 8),
                                Text(
                                  isEnglish ? 'Binary file change' : '二进制文件变更',
                                  style: TextStyle(color: palette.textPrimary, fontWeight: FontWeight.w600),
                                ),
                                if (item?.snapshotBytes != null && item!.snapshotBytes > 0) ...[
                                  const SizedBox(height: 4),
                                  Text(
                                    '${(item.snapshotBytes / 1024).toStringAsFixed(1)} KB',
                                    style: TextStyle(color: palette.textSecondary, fontSize: 12),
                                  ),
                                ],
                              ],
                            ),
                          ),
                        )
                      : (summary != null && summary.files.isNotEmpty)
                          ? AgentDiffViewer(
                              key: const ValueKey('workspace-change-preview-diff'),
                              summary: summary,
                              padding: const EdgeInsets.all(12),
                              scrollable: true,
                            )
                          : Center(
                              child: Padding(
                                padding: const EdgeInsets.all(24),
                                child: Text(
                                  _errorMessage ??
                                      (isEnglish ? 'No file diffs recorded' : '暂无详细文件变更'),
                                  style: TextStyle(
                                    color: palette.textSecondary,
                                    fontSize: 13,
                                  ),
                                ),
                              ),
                            ),
            ),
          ),

          // Action buttons
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                // Discard button: only available when preview exists and not pure diff
                if (!_isPureDiff && _preview != null) ...[
                  OutlinedButton(
                    key: const ValueKey('workspace-change-discard-button'),
                    onPressed: _isActionInProgress ? null : _handleDiscard,
                    style: OutlinedButton.styleFrom(
                      foregroundColor: palette.textSecondary,
                      side: BorderSide(color: palette.borderStrong),
                      minimumSize: const Size(88, 44),
                    ),
                    child: Text(isEnglish ? 'Discard' : '放弃快照'),
                  ),
                  const SizedBox(width: 12),
                ],

                // Restore button: ONLY when canRestore is true and snapshot is ready
                if (canRestore) ...[
                  FilledButton(
                    key: const ValueKey('workspace-change-restore-button'),
                    onPressed: _isActionInProgress ? null : _handleRestore,
                    style: FilledButton.styleFrom(
                      minimumSize: const Size(100, 44),
                    ),
                    child: _isActionInProgress
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                          )
                        : Text(isEnglish ? 'Confirm restore' : '确认恢复'),
                  ),
                ] else ...[
                  OutlinedButton(
                    key: const ValueKey('workspace-change-close-button'),
                    onPressed: () => Navigator.of(context).pop(),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: palette.textPrimary,
                      side: BorderSide(color: palette.borderStrong),
                      minimumSize: const Size(88, 44),
                    ),
                    child: Text(isEnglish ? 'Close' : '关闭'),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({
    required this.label,
    required this.color,
    required this.backgroundColor,
  });

  final String label;
  final Color color;
  final Color backgroundColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// Helper function to display the [WorkspaceChangePreviewSheet] in a modal bottom sheet.
Future<void> showWorkspaceChangePreviewSheet({
  required BuildContext context,
  required String changeId,
  int? conversationId,
  String? workspaceId,
  AgentDiffSummary? diffSummary,
  String? diffText,
  WorkspaceChangeItem? initialItem,
  VoidCallback? onRestored,
  VoidCallback? onDiscarded,
}) async {
  await showOmniGlassBottomSheet<void>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    builder: (sheetContext) => WorkspaceChangePreviewSheet(
      changeId: changeId,
      conversationId: conversationId,
      workspaceId: workspaceId,
      diffSummary: diffSummary,
      diffText: diffText,
      initialItem: initialItem,
      onRestored: onRestored,
      onDiscarded: onDiscarded,
    ),
  );
}
