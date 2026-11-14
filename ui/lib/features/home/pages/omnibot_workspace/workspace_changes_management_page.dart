import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/workspace_change_preview_sheet.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/workspace_change_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_header_body.dart';

/// Page displaying recorded workspace changes for an active workspace or conversation.
class WorkspaceChangesManagementPage extends StatefulWidget {
  const WorkspaceChangesManagementPage({
    super.key,
    required this.workspaceId,
    this.workspacePath,
    this.conversationId,
  });

  final String workspaceId;
  final String? workspacePath;
  final int? conversationId;

  @override
  State<WorkspaceChangesManagementPage> createState() =>
      _WorkspaceChangesManagementPageState();
}

class _WorkspaceChangesManagementPageState
    extends State<WorkspaceChangesManagementPage> {
  bool _isLoading = true;
  List<WorkspaceChangeItem> _changes = [];
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadChanges();
  }

  Future<void> _loadChanges() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final targetWsId = widget.workspaceId.trim().isNotEmpty
        ? widget.workspaceId.trim()
        : (widget.conversationId != null && widget.conversationId! > 0
            ? null
            : 'conversation_default');

    List<WorkspaceChangeItem> items;
    if (targetWsId != null) {
      items = await WorkspaceChangeService.listChangesByWorkspace(
        workspaceId: targetWsId,
        limit: 50,
      );
    } else if (widget.conversationId != null && widget.conversationId! > 0) {
      items = await WorkspaceChangeService.listChanges(
        conversationId: widget.conversationId!,
      );
    } else {
      items = const [];
    }

    if (!mounted) return;
    setState(() {
      _isLoading = false;
      _changes = items;
    });
  }

  void _openChangePreview(WorkspaceChangeItem item) {
    showWorkspaceChangePreviewSheet(
      context: context,
      changeId: item.changeId,
      conversationId: item.conversationId,
      workspaceId: widget.workspaceId,
      initialItem: item,
      onRestored: _loadChanges,
      onDiscarded: _loadChanges,
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isEnglish = LegacyTextLocalizer.isEnglish;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      body: SafeArea(
        child: OmniHeaderBody(
          header: CommonAppBar(
            title: isEnglish ? 'Change History' : '受管变更记录',
            primary: false,
            backgroundColor: palette.surfacePrimary,
            onBackPressed: () => Navigator.of(context).pop(),
            actions: [
              IconButton(
                key: const ValueKey('workspace-changes-refresh-button'),
                icon: const Icon(LucideIcons.refreshCw, size: 18),
                tooltip: isEnglish ? 'Refresh' : '刷新',
                onPressed: _isLoading ? null : _loadChanges,
              ),
            ],
          ),
          body: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _changes.isEmpty
                  ? Center(
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            LucideIcons.history,
                            size: 48,
                            color: palette.textSecondary.withValues(alpha: 0.5),
                          ),
                          const SizedBox(height: 12),
                          Text(
                            isEnglish
                                ? 'No managed workspace changes recorded'
                                : '暂无受管工作区变更记录',
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 14,
                            ),
                          ),
                        ],
                      ),
                    )
                  : RefreshIndicator(
                      onRefresh: _loadChanges,
                      child: ListView.separated(
                        key: const ValueKey('workspace-changes-list'),
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
                        itemCount: _changes.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 8),
                        itemBuilder: (context, index) {
                          final item = _changes[index];
                          return _WorkspaceChangeTile(
                            item: item,
                            onTap: () => _openChangePreview(item),
                          );
                        },
                      ),
                    ),
        ),
      ),
    );
  }
}

class _WorkspaceChangeTile extends StatelessWidget {
  const _WorkspaceChangeTile({
    required this.item,
    required this.onTap,
  });

  final WorkspaceChangeItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isEnglish = LegacyTextLocalizer.isEnglish;

    return InkWell(
      key: ValueKey('workspace-change-item-${item.changeId}'),
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: palette.surfacePrimary,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _OperationIcon(operation: item.operation),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    item.displayPath,
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                      fontFamily: 'monospace',
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                _StatusBadge(
                  label: item.statusLabel,
                  color: item.isReady
                      ? Colors.green
                      : (item.isConflict ? Colors.orange : palette.textSecondary),
                  backgroundColor: palette.surfaceSecondary,
                ),
                if (item.sourceConversationDeleted) ...[
                  const SizedBox(width: 6),
                  _StatusBadge(
                    label: isEnglish ? 'Deleted conversation' : '来源对话已删除',
                    color: palette.textSecondary,
                    backgroundColor: palette.surfaceSecondary,
                  ),
                ],
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Text(
                  item.operationLabel,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(width: 12),
                if (item.createdAt > 0)
                  Text(
                    _formatTimestamp(item.createdAt),
                    style: TextStyle(
                      color: palette.textTertiary,
                      fontSize: 11,
                    ),
                  ),
                const Spacer(),
                Icon(
                  LucideIcons.chevronRight,
                  size: 16,
                  color: palette.textSecondary,
                ),
              ],
            ),
            if (item.unavailableReason != null && item.unavailableReason!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                item.unavailableReason!,
                style: TextStyle(
                  color: palette.textTertiary,
                  fontSize: 11,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _formatTimestamp(int ms) {
    final date = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${date.year}-${_twoDigits(date.month)}-${_twoDigits(date.day)} ${_twoDigits(date.hour)}:${_twoDigits(date.minute)}';
  }

  String _twoDigits(int n) => n >= 10 ? '$n' : '0$n';
}

class _OperationIcon extends StatelessWidget {
  const _OperationIcon({required this.operation});

  final String operation;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    IconData icon;
    Color color;

    switch (operation.toLowerCase()) {
      case 'create':
        icon = LucideIcons.filePlus;
        color = Colors.green;
        break;
      case 'move':
        icon = LucideIcons.fileOutput;
        color = Colors.blue;
        break;
      case 'write':
      default:
        icon = LucideIcons.fileEdit;
        color = palette.accentPrimary;
        break;
    }

    return Container(
      padding: const EdgeInsets.all(6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Icon(icon, size: 16, color: color),
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
