import 'package:flutter/material.dart';
import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/agent_skill_store_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';

const Color _kSuccessColor = Color(0xFF2F8F4E);

class SkillFeedbackSheet extends StatefulWidget {
  final AgentSkillItem skill;
  final AgentSkillFeedbackEligibility eligibility;
  final Future<AgentSkillFeedbackResult> Function(String action) onFeedback;

  const SkillFeedbackSheet({
    super.key,
    required this.skill,
    required this.eligibility,
    required this.onFeedback,
  });

  @override
  State<SkillFeedbackSheet> createState() => _SkillFeedbackSheetState();
}

class _SkillFeedbackSheetState extends State<SkillFeedbackSheet> {
  bool _isSubmitting = false;
  String? _errorMessage;

  Future<void> _handleAction(String action) async {
    if (!widget.eligibility.eligible) {
      setState(() => _errorMessage = '当前版本未满足试用反馈资格');
      return;
    }

    final isRetain = action == 'retain';
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(isRetain ? '确认保留此技能' : '确认停用此技能'),
        content: Text(
          isRetain
              ? '确认该技能在实际任务中表现有效？确认后状态将转为“已验证”（verified）。'
              : '确认该技能在实际任务中无效？确认后技能将转为“已停用”（retired）并立即禁用。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor:
                  isRetain ? _kSuccessColor : AppColors.alertRed,
            ),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: Text(isRetain ? '确认有效并保留' : '确认无效并停用'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    final currentRoute = ModalRoute.of(context);
    try {
      await widget.onFeedback(action);
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
        _isSubmitting = false;
        if (e.isFeedbackIneligible) {
          _errorMessage = '未满足独立后续实际使用凭据，无法提交试用反馈';
        } else if (e.isContentModified) {
          _errorMessage = '技能文件已被外部改动，原版本评价失效，已重置为未验证';
        } else {
          _errorMessage = e.message;
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isSubmitting = false;
        _errorMessage = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final eligible = widget.eligibility.eligible;

    return SettingsDetailSheet(
      title: '试用反馈评价',
      subtitle: '基于实际任务执行效果，提交对试用版技能的验证反馈。',
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
                const SizedBox(height: 6),
                Text(
                  '当前版本: ${widget.skill.revisionId ?? '无'}',
                  style: TextStyle(fontSize: 12, color: palette.textSecondary),
                ),
                const SizedBox(height: 8),
                Text(
                  '实际使用凭据 (Usage Identity):',
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textSecondary,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  '会话 ID: #${widget.eligibility.conversationId ?? '—'} · 轮次: ${widget.eligibility.turnId ?? '—'}',
                  style: TextStyle(
                    fontSize: 12,
                    fontFamily: 'monospace',
                    color: palette.textTertiary,
                  ),
                ),
                if (widget.eligibility.reason != null) ...[
                  const SizedBox(height: 4),
                  Text(
                    widget.eligibility.reason!,
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.accentPrimary,
                    ),
                  ),
                ],
              ],
            ),
          ),
          if (!eligible) ...[
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.amber.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.amber.withValues(alpha: 0.4)),
              ),
              child: const Row(
                children: [
                  Icon(Icons.info_outline, size: 18, color: Colors.orange),
                  SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '仅在后续独立任务正式完成且实际调用该版本后，方可提交试用评价。',
                      style: TextStyle(fontSize: 12.5, color: Colors.orange),
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
          key: const Key('skill_feedback_cancel_button'),
          style: settingsDetailSheetActionStyle(context),
          onPressed: _isSubmitting ? null : () => Navigator.of(context).pop(),
          child: const Text('取消'),
        ),
        TextButton(
          key: const Key('skill_feedback_retire_button'),
          style: settingsDetailSheetActionStyle(
            context,
            foregroundColor: AppColors.alertRed,
          ),
          onPressed:
              (!eligible || _isSubmitting)
                  ? null
                  : () => _handleAction('retire'),
          child: const Text('无效，停用'),
        ),
        FilledButton(
          key: const Key('skill_feedback_retain_button'),
          style: FilledButton.styleFrom(
            backgroundColor: _kSuccessColor,
            foregroundColor: Colors.white,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
            minimumSize: const Size(0, 48),
          ),
          onPressed:
              (!eligible || _isSubmitting)
                  ? null
                  : () => _handleAction('retain'),
          child: const Text('有效，保留'),
        ),
      ],
    );
  }
}
