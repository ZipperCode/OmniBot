import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_glass.dart';

class AppExperienceDetailSheet extends StatelessWidget {
  const AppExperienceDetailSheet({
    super.key,
    required this.experience,
    required this.isToggling,
    required this.onToggleEnabled,
  });

  final Map<String, dynamic> experience;
  final bool isToggling;
  final ValueChanged<bool> onToggleEnabled;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final appLabel = _string(experience['application_label']).nullIfEmpty ??
        _text(context, '未知应用', 'Unknown App');
    final enabled = experience['enabled'] != false;
    final status = _string(experience['status']).toLowerCase();
    final rule = _map(experience['rule']);
    final action = _string(rule['action']);
    final targetResourceId = _string(rule['target_resource_id']);
    final targetClass = _string(rule['target_class']);
    final afterActivity = _string(rule['after_activity']);
    final packageName = _string(experience['package_name']);
    final activityName = _string(experience['activity_name']);
    final sourceRunId = _string(experience['source_run_id']);
    final sourceStepIndex = _intValue(experience['source_step_index']);
    final experienceId = _string(experience['id']);
    final previousVerifiedId = _string(experience['previous_verified_id']);
    final consecutiveFailures = _intValue(experience['consecutive_failures']) ?? 0;
    final successfulRuns = _stringList(experience['successful_run_ids']);
    final failedRuns = _stringList(experience['failed_run_ids']);

    return SafeArea(
      top: false,
      bottom: false,
      child: DraggableScrollableSheet(
        key: const ValueKey('app-experience-detail-sheet'),
        expand: false,
        initialChildSize: 0.82,
        minChildSize: 0.48,
        maxChildSize: 0.96,
        builder: (context, controller) => OmniGlassSheet(
          backgroundColor: palette.pageBackground,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
          child: SafeArea(
            top: false,
            left: false,
            right: false,
            child: Column(
              children: [
                const SizedBox(height: 9),
                Container(
                  width: 38,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.borderStrong,
                    borderRadius: BorderRadius.circular(99),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 10, 8, 8),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              _text(context, '应用经验详情', 'App Experience Details'),
                              style: TextStyle(
                                color: palette.textSecondary,
                                fontSize: 11,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 3),
                            Text(
                              appLabel,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: palette.textPrimary,
                                fontSize: 17,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                      ),
                      IconButton(
                        tooltip: _text(context, '关闭', 'Close'),
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.close_rounded),
                      ),
                    ],
                  ),
                ),
                Divider(height: 1, color: palette.borderSubtle),
                Expanded(
                  child: ListView(
                    controller: controller,
                    padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                    children: [
                      _buildStatusCard(context, enabled, status),
                      const SizedBox(height: 16),
                      _buildSectionHeader(
                        context,
                        _text(context, '状态设置', 'Status Setting'),
                      ),
                      const SizedBox(height: 8),
                      _buildToggleTile(context, enabled),
                      const SizedBox(height: 16),
                      _buildSectionHeader(
                        context,
                        _text(context, '规则详情', 'Rule Details'),
                      ),
                      const SizedBox(height: 8),
                      _buildCard(context, [
                        _buildPropertyRow(
                          context,
                          _text(context, '操作类别', 'Action Type'),
                          _actionTitle(context, action),
                        ),
                        if (targetResourceId.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '目标控件', 'Target Resource'),
                            targetResourceId,
                            copyable: true,
                          ),
                        ],
                        if (targetClass.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '控件类型', 'Target Class'),
                            targetClass,
                          ),
                        ],
                        if (packageName.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '应用包名', 'Package Name'),
                            packageName,
                            copyable: true,
                          ),
                        ],
                        if (activityName.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '所在页面', 'Activity'),
                            activityName,
                            copyable: true,
                          ),
                        ],
                        if (afterActivity.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '后置页面', 'After Activity'),
                            afterActivity,
                          ),
                        ],
                      ]),
                      const SizedBox(height: 16),
                      _buildSectionHeader(
                        context,
                        _text(context, '来源信息', 'Provenance'),
                      ),
                      const SizedBox(height: 8),
                      _buildCard(context, [
                        if (sourceRunId.isNotEmpty)
                          _buildPropertyRow(
                            context,
                            _text(context, '来源运行 ID', 'Source Run ID'),
                            sourceRunId,
                            copyable: true,
                          ),
                        if (sourceStepIndex != null) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '来源步骤', 'Source Step'),
                            _text(
                              context,
                              '第 ${sourceStepIndex + 1} 步',
                              'Step ${sourceStepIndex + 1}',
                            ),
                          ),
                        ],
                        if (experienceId.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '规则标识', 'Rule ID'),
                            experienceId,
                            copyable: true,
                          ),
                        ],
                        if (previousVerifiedId.isNotEmpty) ...[
                          const Divider(height: 1),
                          _buildPropertyRow(
                            context,
                            _text(context, '前置已验证版本', 'Previous Verified ID'),
                            previousVerifiedId,
                            copyable: true,
                          ),
                        ],
                      ]),
                      const SizedBox(height: 16),
                      _buildSectionHeader(
                        context,
                        _text(context, '最近使用结果', 'Recent Results'),
                      ),
                      const SizedBox(height: 8),
                      _buildCard(context, [
                        _buildPropertyRow(
                          context,
                          _text(context, '连续失败次数', 'Consecutive Failures'),
                          _text(
                            context,
                            '$consecutiveFailures 次',
                            '$consecutiveFailures',
                          ),
                        ),
                        const Divider(height: 1),
                        _buildPropertyRow(
                          context,
                          _text(context, '成功运行记录', 'Successful Runs'),
                          _text(
                            context,
                            '${successfulRuns.length} 次',
                            '${successfulRuns.length}',
                          ),
                        ),
                        if (successfulRuns.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                            child: Wrap(
                              spacing: 6,
                              runSpacing: 6,
                              children: successfulRuns.map((id) {
                                return _buildRunChip(context, id);
                              }).toList(growable: false),
                            ),
                          ),
                        const Divider(height: 1),
                        _buildPropertyRow(
                          context,
                          _text(context, '失败运行记录', 'Failed Runs'),
                          _text(
                            context,
                            '${failedRuns.length} 次',
                            '${failedRuns.length}',
                          ),
                        ),
                        if (failedRuns.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
                            child: Wrap(
                              spacing: 6,
                              runSpacing: 6,
                              children: failedRuns.map((id) {
                                return _buildRunChip(context, id);
                              }).toList(growable: false),
                            ),
                          ),
                      ]),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildStatusCard(BuildContext context, bool enabled, String status) {
    final palette = context.omniPalette;
    final (statusLabel, statusColor) = _resolveStatus(context, enabled, status);
    final isVerified = enabled && status == 'verified';

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: statusColor.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: statusColor.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(
                  color: statusColor,
                  shape: BoxShape.circle,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                statusLabel,
                style: TextStyle(
                  color: statusColor,
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
          if (isVerified) ...[
            const SizedBox(height: 6),
            Text(
              _text(
                context,
                '规则试用通过，非全App任务保证',
                'Rule verified; not a full app task guarantee',
              ),
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 12,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildToggleTile(BuildContext context, bool enabled) {
    final palette = context.omniPalette;
    return Material(
      color: palette.surfacePrimary,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: palette.borderSubtle),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _text(context, '启用该应用经验', 'Enable experience'),
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _text(
                      context,
                      '在相同界面作为操作参考',
                      'Referenced when planning actions on matching screen',
                    ),
                    style: TextStyle(
                      color: palette.textSecondary,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Switch.adaptive(
              value: enabled,
              onChanged: isToggling ? null : onToggleEnabled,
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionHeader(BuildContext context, String title) {
    final palette = context.omniPalette;
    return Text(
      title,
      style: TextStyle(
        color: palette.textSecondary,
        fontSize: 12,
        fontWeight: FontWeight.w600,
      ),
    );
  }

  Widget _buildCard(BuildContext context, List<Widget> children) {
    final palette = context.omniPalette;
    return Container(
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: children,
      ),
    );
  }

  Widget _buildPropertyRow(
    BuildContext context,
    String label,
    String value, {
    bool copyable = false,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 100,
            child: Text(
              label,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 13,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                color: palette.textPrimary,
                fontSize: 13,
                fontFamily: copyable ? 'monospace' : null,
              ),
            ),
          ),
          if (copyable) ...[
            const SizedBox(width: 4),
            GestureDetector(
              onTap: () async {
                await Clipboard.setData(ClipboardData(text: value));
                if (!context.mounted) return;
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    duration: const Duration(milliseconds: 1500),
                    content: Text(
                      _text(context, '已复制到剪贴板', 'Copied to clipboard'),
                    ),
                  ),
                );
              },
              child: Icon(
                Icons.copy_rounded,
                size: 16,
                color: palette.textTertiary,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildRunChip(BuildContext context, String runId) {
    final palette = context.omniPalette;
    return InkWell(
      borderRadius: BorderRadius.circular(6),
      onTap: () async {
        await Clipboard.setData(ClipboardData(text: runId));
        if (!context.mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            duration: const Duration(milliseconds: 1500),
            content: Text(
              _text(context, '已复制运行 ID', 'Copied Run ID'),
            ),
          ),
        );
      },
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: palette.pageBackground,
          borderRadius: BorderRadius.circular(6),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              runId.length > 14 ? '${runId.substring(0, 14)}…' : runId,
              style: TextStyle(
                color: palette.textSecondary,
                fontSize: 11,
                fontFamily: 'monospace',
              ),
            ),
            const SizedBox(width: 4),
            Icon(Icons.copy_rounded, size: 12, color: palette.textTertiary),
          ],
        ),
      ),
    );
  }

  (String, Color) _resolveStatus(
    BuildContext context,
    bool enabled,
    String status,
  ) {
    if (!enabled) {
      return (
        _text(context, '已停用', 'Disabled'),
        Theme.of(context).colorScheme.outline,
      );
    }
    return switch (status) {
      'verified' => (
        _text(context, '已验证', 'Verified'),
        Colors.green.shade700,
      ),
      'retired' => (
        _text(context, '已回退', 'Retired'),
        Colors.orange.shade800,
      ),
      _ => (
        _text(context, '试用中', 'Trial'),
        Theme.of(context).colorScheme.primary,
      ),
    };
  }

  String _actionTitle(BuildContext context, String action) => switch (action) {
    'click' => _text(context, '点击', 'Click'),
    'long_press' => _text(context, '长按', 'Long Press'),
    'input_text' => _text(context, '文本输入', 'Input Text'),
    _ => action.isEmpty ? _text(context, '操作', 'Action') : action,
  };
}

List<String> _stringList(dynamic value) => value is List
    ? value
        .map((e) => e?.toString().trim() ?? '')
        .where((e) => e.isNotEmpty)
        .toList(growable: false)
    : const [];

Map<String, dynamic> _map(dynamic value) => value is Map
    ? value.map((key, nested) => MapEntry(key.toString(), nested))
    : <String, dynamic>{};

String _string(dynamic value) => value?.toString().trim() ?? '';

int? _intValue(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String _text(BuildContext context, String zh, String en) =>
    Localizations.localeOf(context).languageCode == 'en' ? en : zh;

extension on String {
  String? get nullIfEmpty => isEmpty ? null : this;
}
