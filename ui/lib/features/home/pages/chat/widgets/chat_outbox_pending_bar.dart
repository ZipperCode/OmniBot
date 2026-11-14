import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/models/conversation_outbox.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

import '../services/chat_conversation_outbox_service.dart';

/// 待发箱状态栏，紧凑吸附于输入框上方
class ChatOutboxPendingBar extends StatefulWidget {
  final ChatConversationOutboxService outboxService;
  final String? observedSessionId;
  final String? observedTurnId;
  final bool isProcessing;
  final void Function(String message, {bool isError})? onShowToast;

  const ChatOutboxPendingBar({
    super.key,
    required this.outboxService,
    this.observedSessionId,
    this.observedTurnId,
    this.isProcessing = false,
    this.onShowToast,
  });

  @override
  State<ChatOutboxPendingBar> createState() => _ChatOutboxPendingBarState();
}

class _ChatOutboxPendingBarState extends State<ChatOutboxPendingBar> {
  bool _isExpanded = false;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.outboxService,
      builder: (context, _) {
        final items = widget.outboxService.activeItems;
        if (items.isEmpty) {
          return const SizedBox.shrink();
        }

        final palette = context.omniPalette;
        final hasPaused = items.any((item) => item.isPaused);

        return Container(
          key: const ValueKey('chat-outbox-pending-bar'),
          margin: const EdgeInsets.only(bottom: 8),
          decoration: BoxDecoration(
            color: palette.surfaceElevated.withValues(alpha: 0.92),
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: hasPaused
                  ? palette.accentPrimary.withValues(alpha: 0.5)
                  : palette.borderSubtle.withValues(alpha: 0.4),
              width: 1,
            ),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 紧凑折叠栏：“待发 N 条”
              InkWell(
                key: const ValueKey('chat-outbox-toggle-expand'),
                onTap: () {
                  setState(() {
                    _isExpanded = !_isExpanded;
                  });
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 8,
                  ),
                  child: Row(
                    children: [
                      Icon(
                        LucideIcons.clock,
                        size: 16,
                        color: hasPaused
                            ? palette.accentPrimary
                            : palette.textSecondary,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        '待发 ${items.length} 条',
                        style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: palette.textPrimary,
                        ),
                      ),
                      if (hasPaused) ...[
                        const SizedBox(width: 6),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 1,
                          ),
                          decoration: BoxDecoration(
                            color: palette.accentPrimary.withValues(alpha: 0.15),
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            '部分已暂停',
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.accentPrimary,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                      ],
                      const Spacer(),
                      Icon(
                        _isExpanded
                            ? LucideIcons.chevronUp
                            : LucideIcons.chevronDown,
                        size: 16,
                        color: palette.textTertiary,
                      ),
                    ],
                  ),
                ),
              ),
              if (_isExpanded) ...[
                Divider(
                  height: 1,
                  thickness: 1,
                  color: palette.borderSubtle.withValues(alpha: 0.25),
                ),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxHeight: 240),
                  child: ListView.separated(
                    shrinkWrap: true,
                    padding: EdgeInsets.zero,
                    itemCount: items.length,
                    separatorBuilder: (context, index) => Divider(
                      height: 1,
                      indent: 12,
                      endIndent: 12,
                      color: palette.borderSubtle.withValues(alpha: 0.15),
                    ),
                    itemBuilder: (context, index) {
                      final item = items[index];
                      return _buildOutboxItemRow(context, item, index + 1);
                    },
                  ),
                ),
              ],
            ],
          ),
        );
      },
    );
  }

  Widget _buildOutboxItemRow(
    BuildContext context,
    OutboxItem item,
    int indexNumber,
  ) {
    final palette = context.omniPalette;

    return InkWell(
      key: ValueKey('chat-outbox-item-${item.id}'),
      onTap: () => _openDetailsSheet(context, item, indexNumber),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  '#$indexNumber',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                    color: palette.textTertiary,
                  ),
                ),
                const SizedBox(width: 6),
                _buildStateBadge(item),
                if (item.attachmentCount > 0) ...[
                  const SizedBox(width: 6),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        LucideIcons.paperclip,
                        size: 12,
                        color: palette.textSecondary,
                      ),
                      const SizedBox(width: 2),
                      Text(
                        '${item.attachmentCount} 个附件',
                        style: TextStyle(
                          fontSize: 11,
                          color: palette.textSecondary,
                        ),
                      ),
                    ],
                  ),
                ],
                const Spacer(),
                _buildItemActions(context, item, indexNumber),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              item.text.isNotEmpty ? item.text : '(无文本内容)',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 13,
                height: 1.35,
                color: palette.textPrimary,
              ),
            ),
            if (item.pauseReason != null && item.pauseReason!.isNotEmpty) ...[
              const SizedBox(height: 3),
              Text(
                '暂停原因: ${item.pauseReason}',
                style: TextStyle(
                  fontSize: 11,
                  color: palette.accentPrimary,
                  fontStyle: FontStyle.italic,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildStateBadge(OutboxItem item) {
    final palette = context.omniPalette;
    String label;
    Color color;

    switch (item.state) {
      case OutboxItemState.dispatching:
        label = '发送中...';
        color = palette.accentPrimary;
        break;
      case OutboxItemState.paused:
        label = '已暂停';
        color = palette.accentPrimary;
        break;
      case OutboxItemState.pending:
      default:
        label = '排队中';
        color = palette.textSecondary;
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }

  Widget _buildItemActions(
    BuildContext context,
    OutboxItem item,
    int indexNumber,
  ) {
    final palette = context.omniPalette;
    final isDispatching = item.isDispatching;
    final hasObservedTurn =
        (widget.observedSessionId?.trim().isNotEmpty ?? false) &&
        (widget.observedTurnId?.trim().isNotEmpty ?? false);

    return PopupMenuButton<String>(
      key: ValueKey('chat-outbox-menu-${item.id}'),
      tooltip: '待发消息 #$indexNumber 更多选项',
      icon: Icon(
        LucideIcons.ellipsisVertical,
        size: 16,
        color: palette.textSecondary,
      ),
      padding: EdgeInsets.zero,
      itemBuilder: (context) => [
        const PopupMenuItem(
          value: 'details',
          child: Row(
            children: [
              Icon(LucideIcons.fileText, size: 16),
              SizedBox(width: 8),
              Text('查看详情'),
            ],
          ),
        ),
        if (!isDispatching && item.isPaused)
          const PopupMenuItem(
            value: 'resume',
            child: Row(
              children: [
                Icon(LucideIcons.play, size: 16),
                SizedBox(width: 8),
                Text('继续发送 (FIFO)'),
              ],
            ),
          ),
        if (!isDispatching) ...[
          const PopupMenuItem(
            value: 'edit',
            child: Row(
              children: [
                Icon(LucideIcons.pencil, size: 16),
                SizedBox(width: 8),
                Text('编辑'),
              ],
            ),
          ),
          PopupMenuItem(
            value: 'stop_and_send',
            enabled: hasObservedTurn,
            child: Row(
              children: [
                Icon(
                  LucideIcons.fastForward,
                  size: 16,
                  color: hasObservedTurn ? null : palette.textTertiary,
                ),
                const SizedBox(width: 8),
                Text(
                  '停止当前任务并发送',
                  style: TextStyle(
                    color: hasObservedTurn ? null : palette.textTertiary,
                  ),
                ),
              ],
            ),
          ),
          const PopupMenuItem(
            value: 'remove',
            child: Row(
              children: [
                Icon(LucideIcons.trash2, size: 16),
                SizedBox(width: 8),
                Text('移除'),
              ],
            ),
          ),
        ],
      ],
      onSelected: (value) async {
        switch (value) {
          case 'details':
            await _openDetailsSheet(context, item, indexNumber);
            break;
          case 'resume':
            await _handleResume(item);
            break;
          case 'edit':
            await _openEditSheet(context, item);
            break;
          case 'stop_and_send':
            await _handleStopAndSend(item);
            break;
          case 'remove':
            await _handleRemove(item);
            break;
        }
      },
    );
  }

  Future<void> _handleResume(OutboxItem item) async {
    try {
      await widget.outboxService.resumeItem(item: item);
      widget.onShowToast?.call('已请求按顺序继续发送 (FIFO)');
    } on OutboxMutationException catch (e) {
      if (e.isRevisionConflict) {
        widget.onShowToast?.call('待发箱已更新，请核对后再恢复', isError: true);
      } else {
        widget.onShowToast?.call('恢复失败: ${e.message}', isError: true);
      }
    } catch (e) {
      widget.onShowToast?.call('恢复失败: $e', isError: true);
    }
  }

  Future<void> _handleRemove(OutboxItem item) async {
    // 移除确认前捕获用户看到的整箱 revision，避免确认途中外部刷新后借新 revision 误删旧内容
    final capturedRevision = (widget.outboxService.conversationId == item.conversationId &&
            widget.outboxService.snapshot != null)
        ? widget.outboxService.snapshot!.revision
        : item.revision;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('移除待发消息'),
        content: const Text('确定从队列中移除此条待发消息吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确定移除'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      try {
        await widget.outboxService.removeItem(
          item: item,
          expectedRevision: capturedRevision,
        );
        widget.onShowToast?.call('已移除');
      } on OutboxMutationException catch (e) {
        if (e.isRevisionConflict) {
          widget.onShowToast?.call('待发箱已更新，请核对后再移除', isError: true);
        } else {
          widget.onShowToast?.call('移除失败: ${e.message}', isError: true);
        }
      } catch (e) {
        widget.onShowToast?.call('移除失败: $e', isError: true);
      }
    }
  }

  Future<void> _handleStopAndSend(OutboxItem item) async {
    final sessionId = widget.observedSessionId?.trim();
    final turnId = widget.observedTurnId?.trim();
    if (sessionId == null || sessionId.isEmpty || turnId == null || turnId.isEmpty) {
      widget.onShowToast?.call('缺少当前任务执行标识，无法停止并发送', isError: true);
      return;
    }

    try {
      await widget.outboxService.stopCurrentAndSend(
        item: item,
        observedSessionId: sessionId,
        observedTurnId: turnId,
      );
      widget.onShowToast?.call('正在停止当前任务并排在队首...');
    } on OutboxMutationException catch (e) {
      if (e.isTargetChanged) {
        widget.onShowToast?.call('当前执行目标已变化，待发消息保留在原位', isError: true);
      } else if (e.isRevisionConflict) {
        widget.onShowToast?.call('待发箱已更新，请核对后再操作', isError: true);
      } else {
        widget.onShowToast?.call('操作失败: ${e.message}', isError: true);
      }
    } catch (e) {
      widget.onShowToast?.call('操作失败: $e', isError: true);
    }
  }

  Future<void> _openDetailsSheet(
    BuildContext context,
    OutboxItem item,
    int indexNumber,
  ) async {
    await showSettingsDetailSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _OutboxDetailsSheetContent(
        item: item,
        indexNumber: indexNumber,
      ),
    );
  }

  Future<void> _openEditSheet(BuildContext context, OutboxItem item) async {
    // 编辑弹层保留用户打开时看到的整箱 revision
    final openedRevision = (widget.outboxService.conversationId == item.conversationId &&
            widget.outboxService.snapshot != null)
        ? widget.outboxService.snapshot!.revision
        : item.revision;
    await showSettingsDetailSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _OutboxEditSheetContent(
        item: item,
        initialRevision: openedRevision,
        outboxService: widget.outboxService,
        onShowToast: widget.onShowToast,
      ),
    );
  }
}

/// 待发消息详情弹窗，完整展示输入文本与附件
class _OutboxDetailsSheetContent extends StatelessWidget {
  final OutboxItem item;
  final int indexNumber;

  const _OutboxDetailsSheetContent({
    required this.item,
    required this.indexNumber,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final maxHeight = MediaQuery.sizeOf(context).height * 0.70;

    String stateLabel;
    Color stateColor;
    switch (item.state) {
      case OutboxItemState.dispatching:
        stateLabel = '发送中...';
        stateColor = palette.accentPrimary;
        break;
      case OutboxItemState.paused:
        stateLabel = '已暂停';
        stateColor = palette.accentPrimary;
        break;
      case OutboxItemState.pending:
      default:
        stateLabel = '排队中';
        stateColor = palette.textSecondary;
        break;
    }

    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: maxHeight),
      child: SettingsDetailSheet(
        title: '待发消息详情 #$indexNumber',
        subtitle: '会话 ID: ${item.conversationId} · 序号: ${item.sequence}',
        headerAction: SizedBox(
          width: 44,
          height: 44,
          child: IconButton(
            icon: const Icon(LucideIcons.x, size: 18),
            onPressed: () => Navigator.of(context).pop(),
          ),
        ),
        body: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: stateColor.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(6),
                      border: Border.all(color: stateColor.withValues(alpha: 0.3)),
                    ),
                    child: Text(
                      stateLabel,
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: stateColor,
                      ),
                    ),
                  ),
                  if (item.attachmentCount > 0) ...[
                    const SizedBox(width: 8),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: palette.surfaceSecondary,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(LucideIcons.paperclip, size: 12, color: palette.textSecondary),
                          const SizedBox(width: 4),
                          Text(
                            '${item.attachmentCount} 个附件',
                            style: TextStyle(fontSize: 12, color: palette.textSecondary),
                          ),
                        ],
                      ),
                    ),
                  ],
                ],
              ),
              if (item.pauseReason != null && item.pauseReason!.isNotEmpty) ...[
                const SizedBox(height: 10),
                Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: palette.accentPrimary.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: palette.accentPrimary.withValues(alpha: 0.2)),
                  ),
                  child: Text(
                    '暂停原因: ${item.pauseReason}',
                    style: TextStyle(fontSize: 12, color: palette.accentPrimary),
                  ),
                ),
              ],
              const SizedBox(height: 16),
              Text(
                '完整输入内容',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: palette.textTertiary,
                ),
              ),
              const SizedBox(height: 8),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: palette.borderSubtle),
                ),
                child: SelectableText(
                  item.text.isNotEmpty ? item.text : '(无文本内容)',
                  style: TextStyle(
                    fontSize: 14,
                    height: 1.5,
                    color: palette.textPrimary,
                  ),
                ),
              ),
              if (item.attachments.isNotEmpty) ...[
                const SizedBox(height: 16),
                Text(
                  '已选附件列表',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: palette.textTertiary,
                  ),
                ),
                const SizedBox(height: 8),
                ...item.attachments.map((att) {
                  final name = att['name']?.toString() ??
                      att['path']?.toString() ??
                      '未命名附件';
                  return Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: Container(
                      width: double.infinity,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      decoration: BoxDecoration(
                        color: palette.surfaceSecondary,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: palette.borderSubtle),
                      ),
                      child: Row(
                        children: [
                          Icon(LucideIcons.paperclip, size: 14, color: palette.textSecondary),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              name,
                              style: TextStyle(fontSize: 12, color: palette.textPrimary),
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                    ),
                  );
                }),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// 独立的待发编辑弹窗，不修改或覆盖输入框已有草稿
class _OutboxEditSheetContent extends StatefulWidget {
  final OutboxItem item;
  final int initialRevision;
  final ChatConversationOutboxService outboxService;
  final void Function(String message, {bool isError})? onShowToast;

  const _OutboxEditSheetContent({
    required this.item,
    required this.initialRevision,
    required this.outboxService,
    this.onShowToast,
  });

  @override
  State<_OutboxEditSheetContent> createState() =>
      _OutboxEditSheetContentState();
}

class _OutboxEditSheetContentState extends State<_OutboxEditSheetContent> {
  late final TextEditingController _editController;
  late int _activeRevision;
  bool _isSaving = false;
  String? _errorMessage;
  bool _isConflict = false;
  String? _latestServerText;

  @override
  void initState() {
    super.initState();
    _editController = TextEditingController(text: widget.item.text);
    // 打开编辑时 CAS 必须 snapshot.revision，保留打开时的 revision
    _activeRevision = widget.initialRevision;
  }

  @override
  void dispose() {
    _editController.dispose();
    super.dispose();
  }

  void _loadLatestContent() {
    if (_latestServerText != null) {
      setState(() {
        _editController.text = _latestServerText!;
        _isConflict = false;
        _errorMessage = null;
      });
    }
  }

  Future<void> _onSave() async {
    final newText = _editController.text.trim();
    if (newText.isEmpty && widget.item.attachmentCount == 0) {
      setState(() {
        _errorMessage = '内容不能为空';
      });
      return;
    }

    setState(() {
      _isSaving = true;
      _errorMessage = null;
    });

    try {
      // 编辑 Sheet 固定 item.conversationId 与当前活动 revision
      await widget.outboxService.updateItem(
        item: widget.item,
        newText: newText,
        expectedRevision: _activeRevision,
      );
      if (mounted) {
        Navigator.of(context).pop();
        widget.onShowToast?.call('已更新待发消息');
      }
    } on OutboxMutationException catch (e) {
      if (mounted) {
        if (e.isRevisionConflict) {
          final latestSnap = e.snapshot ?? widget.outboxService.snapshot;
          OutboxItem? latestItem;
          if (latestSnap != null) {
            for (final it in latestSnap.items) {
              if (it.id == widget.item.id) {
                latestItem = it;
                break;
              }
            }
            _activeRevision = latestSnap.revision;
          }
          setState(() {
            _isSaving = false;
            _isConflict = true;
            _latestServerText = latestItem?.text;
            _errorMessage = '修改冲突：待发项已在后台更新（版本 $_activeRevision）。您可以载入最新内容，或以当前输入确认覆盖。';
          });
        } else {
          setState(() {
            _isSaving = false;
            _errorMessage = '保存失败: ${e.message}';
          });
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isSaving = false;
          _errorMessage = '保存失败: $e';
        });
      }
    }
  }
  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final maxHeight = MediaQuery.sizeOf(context).height * 0.70;

    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: maxHeight),
      child: SettingsDetailSheet(
        title: '编辑待发消息',
        avoidKeyboard: true,
        headerAction: SizedBox(
          width: 44,
          height: 44,
          child: IconButton(
            icon: const Icon(LucideIcons.x, size: 18),
            onPressed: () => Navigator.of(context).pop(),
          ),
        ),
        actions: [
          SizedBox(
            height: 44,
            child: TextButton(
              onPressed: _isSaving ? null : () => Navigator.of(context).pop(),
              child: const Text('取消'),
            ),
          ),
          if (_isConflict &&
              _latestServerText != null &&
              _latestServerText != _editController.text)
            SizedBox(
              height: 44,
              child: OutlinedButton(
                key: const ValueKey('chat-outbox-edit-load-latest-button'),
                onPressed: _isSaving ? null : _loadLatestContent,
                child: const Text('载入最新内容'),
              ),
            ),
          SizedBox(
            height: 44,
            child: ElevatedButton(
              key: const ValueKey('chat-outbox-edit-save-button'),
              onPressed: _isSaving ? null : _onSave,
              child: _isSaving
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Text(_isConflict ? '确认覆盖保存' : '保存修改'),
            ),
          ),
        ],
        body: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                key: const ValueKey('chat-outbox-edit-field'),
                controller: _editController,
                maxLines: 5,
                minLines: 2,
                autofocus: true,
                decoration: InputDecoration(
                  hintText: '输入要发送的内容...',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(10),
                    borderSide: BorderSide(color: palette.borderSubtle),
                  ),
                  contentPadding: const EdgeInsets.all(12),
                ),
              ),
              if (widget.item.attachmentCount > 0) ...[
                const SizedBox(height: 8),
                Row(
                  children: [
                    Icon(LucideIcons.paperclip, size: 14, color: palette.textSecondary),
                    const SizedBox(width: 4),
                    Text(
                      '包含 ${widget.item.attachmentCount} 个附件（已保留）',
                      style: TextStyle(fontSize: 12, color: palette.textSecondary),
                    ),
                  ],
                ),
              ],
              if (_errorMessage != null) ...[
                const SizedBox(height: 8),
                Text(
                  _errorMessage!,
                  style: TextStyle(fontSize: 12, color: palette.accentPrimary),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
