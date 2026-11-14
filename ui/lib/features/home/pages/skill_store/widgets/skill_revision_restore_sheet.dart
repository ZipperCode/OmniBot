import 'package:flutter/material.dart';
import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/agent_skill_store_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

class SkillRevisionRestoreSheet extends StatefulWidget {
  final AgentSkillItem skill;
  final AgentSkillFeedbackEligibility? eligibility;
  final Future<AgentSkillRestoreResult> Function(
    String targetRevisionId,
    String expectedCurrentContentHash,
  ) onRestore;

  const SkillRevisionRestoreSheet({
    super.key,
    required this.skill,
    this.eligibility,
    required this.onRestore,
  });

  @override
  State<SkillRevisionRestoreSheet> createState() =>
      _SkillRevisionRestoreSheetState();
}

class _SkillRevisionRestoreSheetState extends State<SkillRevisionRestoreSheet> {
  bool _isRestoring = false;
  String? _errorMessage;

  AgentSkillRevisionSnapshot? get _targetRevisionSnapshot {
    final targetId = widget.skill.previousRevisionId;
    if (targetId == null || targetId.isEmpty) return null;
    return widget.eligibility?.revisions
        .where((r) => r.revisionId == targetId)
        .firstOrNull;
  }

  bool get _isPreviousRevisionDisabled {
    final snapshot = _targetRevisionSnapshot;
    if (snapshot != null) {
      return snapshot.wasManuallyDisabled || !snapshot.canRestore;
    }
    return false;
  }
  Future<void> _handleRestore() async {
    final targetRevisionId = widget.skill.previousRevisionId;
    if (targetRevisionId == null || targetRevisionId.isEmpty) {
      setState(() => _errorMessage = '无上一版本记录');
      return;
    }

    if (_isPreviousRevisionDisabled) {
      setState(() => _errorMessage = '该历史版本先前已被手动停用，严禁重新启用');
      return;
    }

    final currentHash =
        widget.eligibility?.currentContentHash ?? widget.skill.contentHash;
    if (currentHash == null || currentHash.trim().isEmpty) {
      setState(() => _errorMessage = '缺少当前内容校验哈希，无法安全恢复');
      return;
    }
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认恢复历史版本'),
        content: Text(
          '确认将技能 "${widget.skill.name}" 恢复至上一版本 ($targetRevisionId)？\n'
          '恢复后技能将进入试用状态，且内容不可冲突。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认恢复'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() {
      _isRestoring = true;
      _errorMessage = null;
    });

    final currentRoute = ModalRoute.of(context);
    try {
      await widget.onRestore(targetRevisionId, currentHash.trim());
      if (!mounted) return;
      // 仅当当前 Sheet 自身的 ModalRoute 仍处于 active 状态时才执行 pop(true)。
      // 避免用户在 pending 期间手动 dismiss 后，在 reverse animation（State 仍 mounted）
      // 阶段触发本处逻辑，导致误 pop 底层页面。
      if (currentRoute != null && currentRoute.isActive) {
        Navigator.of(context).pop(true);
      }
    } on SkillAssetException catch (e) {
      if (!mounted) return;
      setState(() {
        _isRestoring = false;
        if (e.isRevisionRetired) {
          _errorMessage = '目标版本曾被手动停用，严禁自动复活';
        } else if (e.isContentConflict) {
          _errorMessage = '当前磁盘内容与期望哈希不匹配，可能已被外部修改';
        } else {
          _errorMessage = e.message;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isRestoring = false;
        _errorMessage = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isBlocked = _isPreviousRevisionDisabled;
    final targetRevisionId = widget.skill.previousRevisionId ?? '—';

    return SettingsDetailSheet(
      title: '恢复技能上一版本',
      subtitle: '将技能回滚至上一次修改前的版本快照。',
      avoidKeyboard: true,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary.withValues(alpha: 0.5),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: palette.borderSubtle),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.skill.name,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '当前版本: ${widget.skill.revisionId ?? '无'}',
                  style: TextStyle(fontSize: 12, color: palette.textSecondary),
                ),
                const SizedBox(height: 4),
                Text(
                  '目标上一版本: $targetRevisionId',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w500,
                    color: palette.accentPrimary,
                  ),
                ),
              ],
            ),
          ),
          if (isBlocked) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.alertRed.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: AppColors.alertRed.withValues(alpha: 0.4),
                ),
              ),
              child: const Row(
                children: [
                  Icon(Icons.block, size: 18, color: AppColors.alertRed),
                  SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '该历史版本先前已被用户手动停用，不可重新启用。',
                      style: TextStyle(fontSize: 13, color: AppColors.alertRed),
                    ),
                  ),
                ],
              ),
            ),
          ],
          if (_errorMessage != null) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.alertRed.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: AppColors.alertRed.withValues(alpha: 0.4),
                ),
              ),
              child: Text(
                _errorMessage!,
                style: const TextStyle(
                  fontSize: 13,
                  color: AppColors.alertRed,
                ),
              ),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          key: const Key('skill_restore_cancel_button'),
          style: settingsDetailSheetActionStyle(context),
          onPressed: _isRestoring ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        FilledButton(
          key: const Key('skill_restore_confirm_button'),
          style: FilledButton.styleFrom(
            backgroundColor: palette.accentPrimary,
            foregroundColor: palette.surfacePrimary,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
            minimumSize: const Size(0, 48),
          ),
          onPressed: (_isRestoring || isBlocked) ? null : _handleRestore,
          child: const Text('确认恢复此版本'),
        ),
      ],
    );
  }
}
