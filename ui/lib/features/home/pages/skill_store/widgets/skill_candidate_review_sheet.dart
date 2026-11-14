import 'package:flutter/material.dart';
import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/agent_skill_store_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/utils/ui.dart';


const Color _kSuccessColor = Color(0xFF2F8F4E);

class SkillCandidateReviewSheet extends StatefulWidget {
  final AgentSkillSuggestionItem candidate;
  final List<AgentSkillItem> installedSkills;
  final Future<AgentSkillSuggestionItem> Function(
    int expectedRevision,
    String name,
    String description,
    String bodyMarkdown,
  ) onSave;
  final Future<AgentSkillApprovalResult> Function(
    int expectedRevision,
    String name,
    String description,
    String bodyMarkdown,
    String targetSkillId,
    String? expectedCurrentContentHash,
  ) onApprove;
  final Future<AgentSkillSuggestionItem> Function(int expectedRevision) onReject;
  final Future<AgentSkillSuggestionItem> Function(int expectedRevision)?
  onRegenerate;

  const SkillCandidateReviewSheet({
    super.key,
    required this.candidate,
    required this.installedSkills,
    required this.onSave,
    required this.onApprove,
    required this.onReject,
    this.onRegenerate,
  });

  @override
  State<SkillCandidateReviewSheet> createState() =>
      _SkillCandidateReviewSheetState();
}

class _SkillCandidateReviewSheetState extends State<SkillCandidateReviewSheet> {
  late final TextEditingController _nameController;
  late final TextEditingController _descriptionController;
  late final TextEditingController _bodyController;
  late AgentSkillSuggestionItem _currentCandidate;

  bool _isSaving = false;
  bool _isApproving = false;
  bool _isRejecting = false;
  bool _isRegenerating = false;

  String? _errorMessage;
  String? _successMessage;
  bool _hasConflict = false;

  @override
  void initState() {
    super.initState();
    _currentCandidate = widget.candidate;
    _nameController = TextEditingController(text: widget.candidate.name);
    _descriptionController = TextEditingController(
      text: widget.candidate.description,
    );
    _bodyController = TextEditingController(
      text: widget.candidate.bodyMarkdown,
    );
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descriptionController.dispose();
    _bodyController.dispose();
    super.dispose();
  }

  AgentSkillItem? _findConflictingSkill(String skillId) {
    final normalized = skillId.trim().toLowerCase();
    if (normalized.isEmpty) return null;
    for (final skill in widget.installedSkills) {
      if (skill.id.trim().toLowerCase() == normalized) {
        return skill;
      }
    }
    return null;
  }

  Future<void> _handleSave() async {
    final name = _nameController.text.trim();
    final description = _descriptionController.text.trim();
    final body = _bodyController.text;

    if (name.isEmpty) {
      setState(() => _errorMessage = '技能标识不能为空');
      return;
    }

    setState(() {
      _isSaving = true;
      _errorMessage = null;
      _successMessage = null;
      _hasConflict = false;
    });

    try {
      final updated = await widget.onSave(
        _currentCandidate.revision,
        name,
        description,
        body,
      );
      if (!mounted) return;
      setState(() {
        _isSaving = false;
        _currentCandidate = updated;
        _successMessage = '保存成功';
      });
    } on SkillAssetException catch (e) {
      if (!mounted) return;
      setState(() {
        _isSaving = false;
        if (e.isRevisionConflict) {
          _hasConflict = true;
          _errorMessage = '内容已被更新（版本冲突），已保留您的修改，请核对后再试';
        } else {
          _errorMessage = e.message;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isSaving = false;
        _errorMessage = e.toString();
      });
    }
  }

  Future<void> _handleApprove() async {
    final name = _nameController.text.trim();
    final description = _descriptionController.text.trim();
    final body = _bodyController.text;

    if (name.isEmpty) {
      setState(() => _errorMessage = '技能标识不能为空');
      return;
    }

    final conflictingSkill = _findConflictingSkill(name);
    if (conflictingSkill != null) {
      if (conflictingSkill.isBuiltin || conflictingSkill.isOfficial) {
        setState(() => _errorMessage = '严禁覆盖官方或内置技能，请输入新技能名称');
        return;
      }
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('保存并启用试用'),
        content: Text(
          conflictingSkill != null
              ? '检测到同名用户技能 "${conflictingSkill.name}"，将更新该技能并进入试用状态（trial）。确认继续？'
              : '确认保存并启用试用此技能？技能将进入试用状态（trial），在后续任务中检验实际效果。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认试用'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    setState(() {
      _isApproving = true;
      _errorMessage = null;
      _successMessage = null;
      _hasConflict = false;
    });

    try {
      String? expectedHash = conflictingSkill?.contentHash;
      if (conflictingSkill != null &&
          (expectedHash == null || expectedHash.isEmpty)) {
        expectedHash =
            await AgentSkillStoreService.getSkillCurrentContentHash(
              conflictingSkill.id,
            );
      }

      // 先持久化保存当前编辑内容，获取递增后的最新 revision
      final savedCandidate = await widget.onSave(
        _currentCandidate.revision,
        name,
        description,
        body,
      );
      _currentCandidate = savedCandidate;

      // 使用最新 revision 执行审核启用
      await widget.onApprove(
        savedCandidate.revision,
        name,
        description,
        body,
        name,
        expectedHash,
      );
      if (!mounted) return;
      Navigator.of(context).pop(true);
    } on SkillAssetException catch (e) {
      if (!mounted) return;
      setState(() {
        _isApproving = false;
        if (e.isRevisionConflict) {
          _hasConflict = true;
          _errorMessage = '内容已被更新（版本冲突），已保留您的修改，请核对后再试';
        } else if (e.isCannotOverwriteBuiltin) {
          _errorMessage = '严禁覆盖官方或内置技能，请输入新技能名称';
        } else {
          _errorMessage = e.message;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isApproving = false;
        _errorMessage = e.toString();
      });
    }
  }

  Future<void> _handleReject() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('拒绝技能建议'),
        content: const Text('确定要拒绝此技能建议吗？拒绝后保留来源记录，不会删除原技能。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: AppColors.alertRed),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认拒绝'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    setState(() {
      _isRejecting = true;
      _errorMessage = null;
      _successMessage = null;
    });

    try {
      final rejected = await widget.onReject(_currentCandidate.revision);
      _currentCandidate = rejected;
      if (!mounted) return;
      Navigator.of(context).pop(false);
    } on SkillAssetException catch (e) {
      if (!mounted) return;
      setState(() {
        _isRejecting = false;
        _errorMessage = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isRejecting = false;
        _errorMessage = e.toString();
      });
    }
  }

  Future<void> _handleRegenerate() async {
    if (widget.onRegenerate == null) return;
    setState(() {
      _isRegenerating = true;
      _errorMessage = null;
      _successMessage = null;
    });

    try {
      final regenerated = await widget.onRegenerate!(
        _currentCandidate.revision,
      );
      _currentCandidate = regenerated;
      if (!mounted) return;
      Navigator.of(context).pop();
    } on SkillAssetException catch (e) {
      if (!mounted) return;
      setState(() {
        _isRegenerating = false;
        _errorMessage = e.message;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isRegenerating = false;
        _errorMessage = e.toString();
      });
    }
  }

  Widget _buildStateBadge(BuildContext context) {
    final palette = context.omniPalette;
    String label = _currentCandidate.state;
    Color color = palette.accentPrimary;

    switch (_currentCandidate.state) {
      case 'queued':
        label = '排队中';
        color = palette.textSecondary;
        break;
      case 'generating':
        label = '生成中';
        color = palette.accentPrimary;
        break;
      case 'pending_review':
        label = '待审核';
        color = palette.accentPrimary;
        break;
      case 'approved':
        label = '已通过';
        color = _kSuccessColor;
        break;
      case 'rejected':
        label = '已拒绝';
        color = palette.textSecondary;
        break;
      case 'generation_failed':
        label = '生成失败';
        color = AppColors.alertRed;
        break;
      case 'interrupted':
        label = '已中断';
        color = Colors.orange;
        break;
      case 'skipped':
        final reason = _currentCandidate.skipReason ?? 'ineligible';
        label = '已跳过 ($reason)';
        color = palette.textTertiary;
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withValues(alpha: 0.3)),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isBusy = _isSaving || _isApproving || _isRejecting || _isRegenerating;

    final conflictingSkill = _findConflictingSkill(_nameController.text);
    final hasBuiltinConflict =
        conflictingSkill != null &&
        (conflictingSkill.isBuiltin || conflictingSkill.isOfficial);

    return SettingsDetailSheet(
      title: '技能建议审核',
      subtitle: '审核由任务模型生成的技能建议，可自由修改内容并启用试用。',
      avoidKeyboard: true,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _buildStateBadge(context),
              const SizedBox(width: 8),
              Text(
                '版本 Rev ${_currentCandidate.revision}',
                style: TextStyle(fontSize: 12, color: palette.textTertiary),
              ),
              const Spacer(),
              if (_currentCandidate.conversationId != null)
                Text(
                  '来源会话 #${_currentCandidate.conversationId}',
                  style: TextStyle(fontSize: 12, color: palette.textSecondary),
                ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary.withValues(alpha: 0.5),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: palette.borderSubtle),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.shield_outlined,
                      size: 16,
                      color: palette.textSecondary,
                    ),
                    const SizedBox(width: 6),
                    Text(
                      '生成数据范畴（已脱敏，无私人原文）',
                      style: TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.w600,
                        color: palette.textSecondary,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                if (_currentCandidate.dataCategories.isEmpty)
                  Text(
                    '任务指令、模型答复、工具名称与参数键、可用技能索引',
                    style: TextStyle(fontSize: 12, color: palette.textTertiary),
                  )
                else
                  Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      for (final cat in _currentCandidate.dataCategories)
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: palette.surfacePrimary,
                            borderRadius: BorderRadius.circular(4),
                          ),
                          child: Text(
                            cat,
                            style: TextStyle(
                              fontSize: 11,
                              color: palette.textSecondary,
                            ),
                          ),
                        ),
                    ],
                  ),
              ],
            ),
          ),
          if (_errorMessage != null) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.alertRed.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: AppColors.alertRed.withValues(alpha: 0.4),
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    _hasConflict ? Icons.warning_amber : Icons.error_outline,
                    size: 18,
                    color: AppColors.alertRed,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _errorMessage!,
                      style: const TextStyle(
                        fontSize: 13,
                        color: AppColors.alertRed,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
          if (_successMessage != null) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: _kSuccessColor.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: _kSuccessColor.withValues(alpha: 0.4),
                ),
              ),
              child: Text(
                _successMessage!,
                style: const TextStyle(
                  fontSize: 13,
                  color: _kSuccessColor,
                ),
              ),
            ),
          ],
          if (hasBuiltinConflict) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.alertRed.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color: AppColors.alertRed.withValues(alpha: 0.4),
                ),
              ),
              child: const Text(
                '严禁覆盖官方或内置技能，请输入新技能名称',
                style: TextStyle(fontSize: 13, color: AppColors.alertRed),
              ),
            ),
          ] else if (conflictingSkill != null) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.amber.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.amber.withValues(alpha: 0.4)),
              ),
              child: Text(
                '检测到已有同名用户技能 "${conflictingSkill.name}"。保存并试用将升级该用户技能。',
                style: TextStyle(
                  fontSize: 12.5,
                  color: context.isDarkTheme ? Colors.amber[200] : Colors.amber[900],
                ),
              ),
            ),
          ],
          const SizedBox(height: 16),
          TextFormField(
            key: const Key('skill_candidate_name_field'),
            controller: _nameController,
            enabled: !isBusy,
            decoration: InputDecoration(
              labelText: '技能标识 (kebab-case)',
              hintText: '如 git-release-helper',
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 12),
          TextFormField(
            key: const Key('skill_candidate_description_field'),
            controller: _descriptionController,
            enabled: !isBusy,
            maxLength: 240,
            maxLines: 2,
            decoration: InputDecoration(
              labelText: '技能描述',
              hintText: '简要说明适用场景和能力',
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
          ),
          const SizedBox(height: 12),
          TextFormField(
            key: const Key('skill_candidate_body_field'),
            controller: _bodyController,
            enabled: !isBusy,
            maxLength: 12000,
            minLines: 8,
            maxLines: 16,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
            decoration: InputDecoration(
              labelText: '技能 Markdown 定义 (SKILL.md)',
              alignLabelWithHint: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
          ),
        ],
      ),
      actions: [
        if (_currentCandidate.canRegenerate && widget.onRegenerate != null)
          TextButton(
            key: const Key('skill_candidate_regenerate_button'),
            style: settingsDetailSheetActionStyle(
              context,
              foregroundColor: palette.accentPrimary,
            ),
            onPressed: isBusy ? null : _handleRegenerate,
            child: const Text('重新生成'),
          ),
        TextButton(
          key: const Key('skill_candidate_reject_button'),
          style: settingsDetailSheetActionStyle(
            context,
            foregroundColor: AppColors.alertRed,
          ),
          onPressed: isBusy ? null : _handleReject,
          child: const Text('拒绝建议'),
        ),
        TextButton(
          key: const Key('skill_candidate_save_button'),
          style: settingsDetailSheetActionStyle(context),
          onPressed: isBusy ? null : _handleSave,
          child: const Text('保存草稿'),
        ),
        FilledButton(
          key: const Key('skill_candidate_approve_button'),
          style: FilledButton.styleFrom(
            backgroundColor: palette.accentPrimary,
            foregroundColor: palette.surfacePrimary,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
            minimumSize: const Size(0, 48),
          ),
          onPressed: (isBusy || hasBuiltinConflict) ? null : _handleApprove,
          child: const Text('保存并试用'),
        ),
      ],
    );
  }
}

/// 公共入口函数：供技能仓库与结果页（ResultUi）统一调起技能建议审核面板。
/// 封装了先保存编辑再 Approve、最新 Revision 状态维护、覆盖限制与 Toast 提示，
/// 避免多处调用方复制 callback 状态逻辑。
Future<bool?> showSkillCandidateReviewSheet({
  required BuildContext context,
  required AgentSkillSuggestionItem candidate,
  List<AgentSkillItem>? installedSkills,
  VoidCallback? onSkillsChanged,
}) async {
  List<AgentSkillItem> resolvedSkills = installedSkills ?? const [];
  if (installedSkills == null) {
    try {
      resolvedSkills = await AgentSkillStoreService.listSkills();
    } catch (_) {}
  }

  if (!context.mounted) return null;

  return showSettingsDetailSheet<bool>(
    context: context,
    isScrollControlled: true,
    builder:
        (sheetContext) => SkillCandidateReviewSheet(
          candidate: candidate,
          installedSkills: resolvedSkills,
          onSave: (expectedRevision, name, description, body) async {
            final updated = await AgentSkillStoreService.saveSuggestion(
              candidateId: candidate.candidateId,
              expectedRevision: expectedRevision,
              name: name,
              description: description,
              bodyMarkdown: body,
            );
            onSkillsChanged?.call();
            return updated;
          },
          onApprove: (
            expectedRevision,
            name,
            description,
            body,
            targetId,
            expectedHash,
          ) async {
            final result = await AgentSkillStoreService.approveSuggestion(
              candidateId: candidate.candidateId,
              expectedRevision: expectedRevision,
              targetSkillId: targetId,
              expectedCurrentContentHash: expectedHash,
            );
            onSkillsChanged?.call();
            showToast('技能审核通过并已启用试用', type: ToastType.success);
            return result;
          },
          onReject: (expectedRevision) async {
            final rejected = await AgentSkillStoreService.rejectSuggestion(
              candidateId: candidate.candidateId,
              expectedRevision: expectedRevision,
            );
            onSkillsChanged?.call();
            showToast('已拒绝该技能建议');
            return rejected;
          },
          onRegenerate:
              candidate.canRegenerate
                  ? (expectedRevision) async {
                    final regenerated =
                        await AgentSkillStoreService.regenerateSuggestion(
                          candidateId: candidate.candidateId,
                          expectedRevision: expectedRevision,
                        );
                    onSkillsChanged?.call();
                    showToast('已提交重新生成请求');
                    return regenerated;
                  }
                  : null,
        ),
  );
}
