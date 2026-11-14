import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/features/home/pages/skill_store/widgets/skill_candidate_review_sheet.dart';
import 'package:ui/features/home/pages/skill_store/widgets/skill_feedback_sheet.dart';
import 'package:ui/features/home/pages/skill_store/widgets/skill_revision_restore_sheet.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/agent_skill_store_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

const String _kOfficialSkillsDownloadAsset =
    'assets/home/hard_drive_download.svg';

const String _kBuiltinSkillBadgeCheckSvg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" '
    'viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" '
    'stroke-linecap="round" stroke-linejoin="round" '
    'class="lucide lucide-badge-check-icon lucide-badge-check">'
    '<path d="M3.85 8.62a4 4 0 0 1 4.78-4.77 4 4 0 0 1 6.74 0 '
    '4 4 0 0 1 4.78 4.78 4 4 0 0 1 0 6.74 4 4 0 0 1-4.77 4.78 '
    '4 4 0 0 1-6.75 0 4 4 0 0 1-4.78-4.77 4 4 0 0 1 0-6.76Z"/>'
    '<path d="m9 12 2 2 4-4"/></svg>';
const Color _kSuccessColor = Color(0xFF2F8F4E);


class SkillStorePage extends StatefulWidget {
  const SkillStorePage({super.key});

  @override
  State<SkillStorePage> createState() => _SkillStorePageState();
}

class _SkillStorePageState extends State<SkillStorePage>
    with FlClashPageLoadMixin {
  static const double _kSkillTileTrailingWidth = 64;
  static const double _kSkillTileTrailingHeight = 28;

  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();

  bool _loading = true;
  bool _syncingOfficial = false;
  bool _autoSuggestionsEnabled = true;
  bool _togglingAutoSuggestions = false;
  bool _noticeDismissed = false;

  final Set<String> _busyIds = <String>{};
  List<AgentSkillItem> _skills = [];
  List<AgentSkillSuggestionItem> _candidates = [];
  final Map<String, AgentSkillFeedbackEligibility> _feedbackEligibilities = {};
  StreamSubscription<Map<String, dynamic>>? _skillAssetsSubscription;
  int _loadRequestId = 0;

  String get _searchQuery => _searchController.text.trim();

  List<AgentSkillItem> get _visibleSkills {
    final query = _searchQuery.toLowerCase();
    if (query.isEmpty) {
      return _skills;
    }

    return _skills
        .where((skill) {
          return skill.name.toLowerCase().contains(query) ||
              skill.description.toLowerCase().contains(query);
        })
        .toList(growable: false);
  }

  List<AgentSkillSuggestionItem> get _visibleCandidates {
    final query = _searchQuery.toLowerCase();
    if (query.isEmpty) {
      return _candidates;
    }

    return _candidates
        .where((candidate) {
          return candidate.name.toLowerCase().contains(query) ||
              candidate.description.toLowerCase().contains(query);
        })
        .toList(growable: false);
  }

  @override
  void initState() {
    super.initState();
    _searchController.addListener(_handleSearchChanged);
    _searchFocusNode.addListener(_handleSearchFocusChanged);
    _subscribeSkillAssets();
    _loadSkills();
  }

  void _subscribeSkillAssets() {
    _skillAssetsSubscription?.cancel();
    _skillAssetsSubscription =
        AssistsMessageService.skillAssetsChangedStream.listen((_) {
      if (mounted) {
        _loadSkills(silent: true);
      }
    });
  }

  @override
  void dispose() {
    _skillAssetsSubscription?.cancel();
    _skillAssetsSubscription = null;
    _searchController
      ..removeListener(_handleSearchChanged)
      ..dispose();
    _searchFocusNode
      ..removeListener(_handleSearchFocusChanged)
      ..dispose();
    super.dispose();
  }

  void _handleSearchChanged() {
    if (!mounted) return;
    setState(() {});
  }

  void _handleSearchFocusChanged() {
    if (!mounted) return;
    setState(() {});
  }

  Future<void> _loadSkills({bool silent = false}) async {
    if (!await waitForPageReady() || !mounted) return;
    final requestId = ++_loadRequestId;
    if (!silent) {
      setState(() => _loading = true);
    }
    try {
      final skills = await AgentSkillStoreService.listSkills();
      AgentSkillSuggestionListResult? suggestionsResult;
      try {
        suggestionsResult = await AgentSkillStoreService.listSuggestions();
      } catch (_) {
        // Fallback gracefully if suggestions service is initializing
      }

      if (!await waitForPageReady() || !mounted || requestId != _loadRequestId) {
        return;
      }

      final freshFeedbackEligibilities =
          <String, AgentSkillFeedbackEligibility>{};
      if (suggestionsResult != null) {
        if (suggestionsResult.feedbackEligibility != null) {
          freshFeedbackEligibilities[suggestionsResult.feedbackEligibility!.skillId] =
              suggestionsResult.feedbackEligibility!;
        }
      }

      final pendingTrials = skills.where(
        (s) => s.isTrial && !freshFeedbackEligibilities.containsKey(s.id),
      );
      for (final skill in pendingTrials) {
        try {
          final result = await AgentSkillStoreService.listSuggestions(
            skillId: skill.id,
          );
          if (result.skillDetail != null) {
            freshFeedbackEligibilities[skill.id] = result.skillDetail!;
          }
        } catch (_) {}
      }

      if (!mounted || requestId != _loadRequestId) return;
      setState(() {
        _skills = skills;
        _candidates = suggestionsResult?.items ?? [];
        if (suggestionsResult != null) {
          _autoSuggestionsEnabled = suggestionsResult.enabled;
        }
        _feedbackEligibilities
          ..clear()
          ..addAll(freshFeedbackEligibilities);
        _loading = false;
      });
    } catch (_) {
      if (!await waitForPageReady() || !mounted || requestId != _loadRequestId) {
        return;
      }
      setState(() => _loading = false);
      if (!silent) {
        showToast(context.l10n.skillLoadFailed, type: ToastType.error);
      }
    }
  }

  void _setBusy(String id, bool busy) {
    if (!mounted) return;
    setState(() {
      if (busy) {
        _busyIds.add(id);
      } else {
        _busyIds.remove(id);
      }
    });
  }

  Future<void> _handleToggleAutoSuggestions(bool value) async {
    final previous = _autoSuggestionsEnabled;
    setState(() {
      _autoSuggestionsEnabled = value;
      _togglingAutoSuggestions = true;
    });

    try {
      final updated = await AgentSkillStoreService.setAutoSuggestionsEnabled(
        enabled: value,
      );
      if (!mounted) return;
      if (updated != value) {
        setState(() => _autoSuggestionsEnabled = previous);
        showToast('自动建议开关设置失败', type: ToastType.error);
      } else {
        setState(() => _autoSuggestionsEnabled = updated);
      }
    } catch (_) {
      if (!mounted) return;
      setState(() => _autoSuggestionsEnabled = previous);
      showToast('自动建议开关设置失败', type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _togglingAutoSuggestions = false);
      }
    }
  }

  Future<void> _toggleSkill(AgentSkillItem item, bool enabled) async {
    _setBusy(item.id, true);
    try {
      final updated = await AgentSkillStoreService.setEnabled(
        skillId: item.id,
        enabled: enabled,
      );
      if (!mounted) return;
      if (updated == null) {
        showToast(context.l10n.skillToggleFailed, type: ToastType.error);
        return;
      }
      setState(() {
        _skills =
            _skills
                .map((skill) => skill.id == item.id ? updated : skill)
                .toList();
      });
      showToast(
        enabled
            ? context.l10n.skillEnabledMsg(item.name)
            : context.l10n.skillDisabledMsg(item.name),
      );
    } catch (_) {
      showToast(context.l10n.skillToggleFailed, type: ToastType.error);
    } finally {
      _setBusy(item.id, false);
    }
  }

  Future<void> _installBuiltinSkill(AgentSkillItem item) async {
    _setBusy(item.id, true);
    try {
      final installed = await AgentSkillStoreService.installBuiltinSkill(
        skillId: item.id,
      );
      if (!mounted) return;
      if (installed == null) {
        showToast(context.l10n.skillInstallFailed, type: ToastType.error);
        return;
      }
      setState(() {
        _skills =
            _skills
                .map((skill) => skill.id == item.id ? installed : skill)
                .toList();
      });
      showToast(
        context.l10n.skillInstalledMsg(item.name),
        type: ToastType.success,
      );
    } catch (_) {
      showToast(context.l10n.skillInstallFailed, type: ToastType.error);
    } finally {
      _setBusy(item.id, false);
    }
  }

  Future<void> _syncOfficialSkills() async {
    setState(() => _syncingOfficial = true);
    try {
      final result = await AgentSkillStoreService.syncOfficialSkills();
      if (!mounted) return;
      if (result == null) {
        showToast(context.l10n.skillSyncOfficialFailed, type: ToastType.error);
        return;
      }
      await _loadSkills();
      if (!mounted) return;
      showToast(
        context.l10n.skillSyncOfficialSuccess(result.skillCount),
        type: ToastType.success,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.skillSyncOfficialFailed, type: ToastType.error);
    } finally {
      if (mounted) {
        setState(() => _syncingOfficial = false);
      }
    }
  }

  Future<void> _deleteSkill(AgentSkillItem item) async {
    final confirmed = await showSettingsDetailSheet<bool>(
      context: context,
      builder:
          (sheetContext) => SettingsDetailSheet(
            title: context.l10n.skillDeleteTitle,
            body: Text(context.l10n.skillDeleteConfirmMsg(item.name)),
            actions: [
              TextButton(
                style: settingsDetailSheetActionStyle(sheetContext),
                onPressed: () => Navigator.of(sheetContext).pop(false),
                child: Text(context.trLegacy('取消')),
              ),
              TextButton(
                style: settingsDetailSheetActionStyle(
                  sheetContext,
                  foregroundColor: AppColors.alertRed,
                ),
                onPressed: () => Navigator.of(sheetContext).pop(true),
                child: Text(context.l10n.skillDelete),
              ),
            ],
          ),
    );
    if (confirmed != true) return;

    _setBusy(item.id, true);
    try {
      final deleted = await AgentSkillStoreService.deleteSkill(
        skillId: item.id,
      );
      if (!mounted) return;
      if (!deleted) {
        showToast(context.l10n.skillDeleteFailed, type: ToastType.error);
        return;
      }
      await _loadSkills();
      if (!mounted) return;
      showToast(context.l10n.skillDeleted, type: ToastType.success);
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.skillDeleteFailed, type: ToastType.error);
    } finally {
      _setBusy(item.id, false);
    }
  }

  void _openCandidateReview(AgentSkillSuggestionItem candidate) {
    showSkillCandidateReviewSheet(
      context: context,
      candidate: candidate,
      installedSkills: _skills,
      onSkillsChanged: () => _loadSkills(silent: true),
    );
  }

  Future<void> _openSkillRestore(AgentSkillItem skill) async {
    _setBusy(skill.id, true);
    AgentSkillFeedbackEligibility? eligibility;
    try {
      final result = await AgentSkillStoreService.listSuggestions(
        skillId: skill.id,
      );
      eligibility = result.skillDetail;
    } catch (_) {
      // Failed to query eligibility
    } finally {
      _setBusy(skill.id, false);
    }

    if (!mounted) return;

    final expectedRevision = eligibility?.expectedRevision;
    final expectedHash = eligibility?.currentContentHash ?? skill.contentHash;

    if (eligibility == null ||
        expectedRevision == null ||
        expectedRevision <= 0 ||
        expectedHash == null ||
        expectedHash.trim().isEmpty) {
      showToast('技能版本或内容校验信息缺失，无法恢复', type: ToastType.error);
      return;
    }

    // 等待 Sheet 的真实关闭返回值。业务回调内仅执行原子恢复提交并返回结果，
    // 在原生操作成功后非阻塞触发 _loadSkills 排队刷新，使 Sheet 自行调用 Navigator.pop(true)。
    // Sheet 退出后 RouteActivation 恢复 current 并执行页面刷新；toast 仅在面板正常返回时展示。
    final success = await showSettingsDetailSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder:
          (sheetContext) => SkillRevisionRestoreSheet(
            skill: skill,
            eligibility: eligibility,
            onRestore: (targetRevisionId, expectedCurrentHash) async {
              final restored = await AgentSkillStoreService.restoreRevision(
                skillId: skill.id,
                revisionId: targetRevisionId,
                expectedRevision: expectedRevision,
                expectedCurrentContentHash: expectedCurrentHash,
              );
              unawaited(_loadSkills(silent: true));
              return restored;
            },
          ),
    );

    if (success == true && mounted) {
      showToast('已成功恢复上一版本', type: ToastType.success);
    }
  }

  Future<void> _openSkillFeedback(
    AgentSkillItem skill,
    AgentSkillFeedbackEligibility eligibility,
  ) async {
    String? submittedAction;
    // 等待 Sheet 真实退出。回调内仅提交反馈接口并记录 action，原子成功后非阻塞触发 _loadSkills，
    // 立即返回原生结果供 Sheet 自行 pop(true)。
    // 避免在 Sheet 覆盖期间 await _loadSkills 导致 RouteActivation.ready 互等死锁；
    // 失败抛异常时 Sheet 自行捕获展示错误，此处 success 为 null/false，不误刷 Toast。
    final success = await showSettingsDetailSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder:
          (sheetContext) => SkillFeedbackSheet(
            skill: skill,
            eligibility: eligibility,
            onFeedback: (action) async {
              final result = await AgentSkillStoreService.submitFeedback(
                skillId: skill.id,
                revisionId: eligibility.revisionId,
                conversationId: eligibility.conversationId ?? 0,
                sessionId: eligibility.sessionId ?? '',
                turnId: eligibility.turnId ?? '',
                expectedRevision: eligibility.expectedRevision,
                action: action,
              );
              submittedAction = action;
              unawaited(_loadSkills());
              return result;
            },
          ),
    );

    if (success == true && mounted) {
      showToast(
        submittedAction == 'retain' ? '已确认有效并转为已验证' : '已停用该试用技能',
        type: ToastType.success,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final visibleSkills = _visibleSkills;
    final visibleCandidates = _visibleCandidates;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.skillStoreTitle,
        primary: true,
        actions: [
          Padding(
            padding: const EdgeInsetsDirectional.only(end: 12),
            child: OmniCircleButton(
              tooltip: context.l10n.skillSyncOfficialTooltip,
              icon: Icons.download_rounded,
              size: 40,
              tapTargetSize: MaterialTapTargetSize.padded,
              onPressed: _syncingOfficial ? null : _syncOfficialSkills,
              iconWidget:
                  _syncingOfficial
                      ? SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: palette.accentPrimary,
                        ),
                      )
                      : SvgPicture.asset(
                        _kOfficialSkillsDownloadAsset,
                        width: 20,
                        height: 20,
                        colorFilter: ColorFilter.mode(
                          palette.textPrimary,
                          BlendMode.srcIn,
                        ),
                      ),
            ),
          ),
        ],
      ),
      body: OmniContentWidth(
        child:
            _loading
                ? const Center(child: CircularProgressIndicator())
                : SafeArea(
                  top: false,
                  bottom: false,
                  child: Column(
                    children: [
                      Padding(
                        padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
                        child: OmniSettingsSearchField(
                          controller: _searchController,
                          focusNode: _searchFocusNode,
                          hintText: context.trLegacy('搜索技能名称或描述'),
                        ),
                      ),
                      Expanded(
                        child: RefreshIndicator(
                          onRefresh: _loadSkills,
                          child: _buildScrollableContent(
                            visibleSkills,
                            visibleCandidates,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
      ),
    );
  }

  Widget _buildNoticeBanner(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 4, 16, 12),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.accentPrimary.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: palette.accentPrimary.withValues(alpha: 0.25),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.info_outline, size: 20, color: palette.accentPrimary),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              '会使用当前任务模型生成建议，可能产生额外费用；审核后才启用。',
              style: TextStyle(
                fontSize: 13,
                height: 1.45,
                color: palette.textPrimary,
              ),
            ),
          ),
          InkWell(
            onTap: () => setState(() => _noticeDismissed = true),
            borderRadius: BorderRadius.circular(16),
            child: Padding(
              padding: const EdgeInsets.all(4),
              child: Icon(Icons.close, size: 16, color: palette.textTertiary),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAutoSuggestionControl(BuildContext context) {
    return OmniSettingsTile(
      title: '自动技能建议',
      subtitle: '小万任务完成时自动提炼技能候选供您审核启用',
      onTap: !_togglingAutoSuggestions
          ? () => _handleToggleAutoSuggestions(!_autoSuggestionsEnabled)
          : null,
      trailing: Semantics(
        container: true,
        label: context.trLegacy('自动技能建议'),
        child: OmniSettingsSwitch(
          value: _autoSuggestionsEnabled,
          enabled: !_togglingAutoSuggestions,
          onToggle: _handleToggleAutoSuggestions,
        ),
      ),
    );
  }

  Widget _buildScrollableContent(
    List<AgentSkillItem> visibleSkills,
    List<AgentSkillSuggestionItem> visibleCandidates,
  ) {
    if (_skills.isEmpty && _candidates.isEmpty) {
      return _buildStateList(child: _buildEmpty());
    }
    if (visibleSkills.isEmpty && visibleCandidates.isEmpty) {
      return _buildStateList(child: _buildSearchEmpty());
    }

    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(16, 6, 16, 28),
      ),
      children: [
        if (!_noticeDismissed) _buildNoticeBanner(context),
        OmniSettingsGroup(
          dividerIndent: 16,
          children: [_buildAutoSuggestionControl(context)],
        ),
        if (visibleCandidates.isNotEmpty) ...[
          const SizedBox(height: 16),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            child: Row(
              children: [
                Text(
                  '待审核建议 (${visibleCandidates.length})',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: context.omniPalette.textSecondary,
                  ),
                ),
              ],
            ),
          ),
          OmniSettingsGroup(
            dividerIndent: 16,
            children: [
              for (final candidate in visibleCandidates)
                _buildCandidateTile(candidate),
            ],
          ),
        ],
        const SizedBox(height: 16),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
          child: Row(
            children: [
              Text(
                '已接入技能 (${visibleSkills.length})',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                  color: context.omniPalette.textSecondary,
                ),
              ),
            ],
          ),
        ),
        OmniSettingsGroup(
          dividerIndent: 16,
          children: [for (final skill in visibleSkills) _buildSkillTile(skill)],
        ),
      ],
    );
  }

  Widget _buildCandidateTile(AgentSkillSuggestionItem candidate) {
    final palette = context.omniPalette;
    String statusLabel = '待审核';
    Color statusColor = palette.accentPrimary;

    switch (candidate.state) {
      case 'queued':
        statusLabel = '排队中';
        statusColor = palette.textSecondary;
        break;
      case 'generating':
        statusLabel = '生成中';
        statusColor = palette.accentPrimary;
        break;
      case 'pending_review':
        statusLabel = '待审核';
        statusColor = palette.accentPrimary;
        break;
      case 'approved':
        statusLabel = '已通过';
        statusColor = _kSuccessColor;
        break;
      case 'rejected':
        statusLabel = '已拒绝';
        statusColor = palette.textSecondary;
        break;
      case 'generation_failed':
        statusLabel = '生成失败';
        statusColor = AppColors.alertRed;
        break;
      case 'interrupted':
        statusLabel = '已中断';
        statusColor = Colors.orange;
        break;
      case 'skipped':
        statusLabel = '已跳过';
        statusColor = palette.textTertiary;
        break;
    }

    return InkWell(
      key: Key('skill_candidate_tile_${candidate.candidateId}'),
      onTap: () => _openCandidateReview(candidate),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          candidate.name,
                          style: TextStyle(
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                            color: palette.textPrimary,
                          ),
                        ),
                      ),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: statusColor.withValues(alpha: 0.12),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          statusLabel,
                          style: TextStyle(
                            fontSize: 11,
                            fontWeight: FontWeight.w600,
                            color: statusColor,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Text(
                    candidate.description.isEmpty
                        ? '暂无描述'
                        : candidate.description,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: palette.textSecondary,
                      height: 1.45,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      if (candidate.conversationId != null) ...[
                        Text(
                          '来源会话 #${candidate.conversationId}',
                          style: TextStyle(
                            fontSize: 11,
                            color: palette.textTertiary,
                          ),
                        ),
                        const SizedBox(width: 8),
                      ],
                      Text(
                        'Rev ${candidate.revision}',
                        style: TextStyle(
                          fontSize: 11,
                          color: palette.textTertiary,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(Icons.chevron_right, size: 20, color: palette.textTertiary),
          ],
        ),
      ),
    );
  }

  Widget _buildStateList({required Widget child}) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: edgeToEdgeScrollPadding(
        context,
        const EdgeInsets.fromLTRB(18, 24, 18, 24),
      ),
      children: [SizedBox(height: 160, child: child)],
    );
  }

  Widget _buildEmpty() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.extension_outlined, size: 40, color: palette.textTertiary),
          const SizedBox(height: 12),
          Text(
            context.l10n.skillEmpty,
            style: TextStyle(fontSize: 15, color: palette.textSecondary),
          ),
        ],
      ),
    );
  }

  Widget _buildSkillTile(AgentSkillItem item) {
    final palette = context.omniPalette;
    final busy = _busyIds.contains(item.id);
    final description =
        item.description.trim().isEmpty
            ? context.l10n.skillNoDescription
            : item.description;
    final statusSummary = _buildStatusSummary(item);
    final eligibility = _feedbackEligibilities[item.id];

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildSkillTitle(item),
                const SizedBox(height: 3),
                Text(
                  description,
                  style: TextStyle(
                    color: palette.textSecondary,
                    fontSize: 12,
                    fontWeight: FontWeight.w400,
                    height: 1.48,
                  ),
                ),
                if (statusSummary.isNotEmpty) ...[
                  const SizedBox(height: 5),
                  Text(
                    statusSummary,
                    style: TextStyle(
                      fontSize: 11.5,
                      fontWeight: FontWeight.w500,
                      color:
                          item.installed && item.enabled
                              ? palette.accentPrimary
                              : palette.textTertiary,
                      height: 1.4,
                    ),
                  ),
                ],
                if (!item.installed && item.isBuiltin) ...[
                  const SizedBox(height: 6),
                  Text(
                    context.l10n.skillBuiltinRemovedDesc,
                    style: TextStyle(
                      fontSize: 11.5,
                      color: palette.textTertiary,
                      height: 1.45,
                    ),
                  ),
                ],
                if (item.installed) ...[
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.shellSkillFilePath,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 11,
                            color: palette.textTertiary,
                            height: 1.4,
                          ),
                        ),
                      ),
                      if (eligibility != null && eligibility.eligible) ...[
                        const SizedBox(width: 8),
                        TextButton(
                          key: Key('skill_feedback_btn_${item.id}'),
                          onPressed: () => _openSkillFeedback(item, eligibility),
                          style: TextButton.styleFrom(
                            foregroundColor: _kSuccessColor,
                            padding: EdgeInsets.zero,
                            minimumSize: const Size(0, 28),
                            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                          child: const Text(
                            '试用反馈',
                            style: TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                      if (item.canRestoreRevision) ...[
                        const SizedBox(width: 8),
                        TextButton(
                          key: Key('skill_restore_btn_${item.id}'),
                          onPressed: busy ? null : () => _openSkillRestore(item),
                          style: TextButton.styleFrom(
                            foregroundColor: palette.accentPrimary,
                            padding: EdgeInsets.zero,
                            minimumSize: const Size(0, 28),
                            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                          child: const Text(
                            '恢复旧版',
                            style: TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                      if (!item.isOfficial) ...[
                        const SizedBox(width: 12),
                        TextButton(
                          onPressed: busy ? null : () => _deleteSkill(item),
                          style: TextButton.styleFrom(
                            foregroundColor: AppColors.alertRed,
                            padding: EdgeInsets.zero,
                            minimumSize: const Size(0, 28),
                            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                          ),
                          child: Text(
                            context.l10n.skillDelete,
                            style: const TextStyle(fontSize: 12),
                          ),
                        ),
                      ],
                    ],
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: _kSkillTileTrailingWidth,
            height: _kSkillTileTrailingHeight,
            child: Align(
              alignment: Alignment.topRight,
              child: _buildTrailingControl(item, busy: busy),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSkillTitle(AgentSkillItem item) {
    final palette = context.omniPalette;
    final titleStyle = TextStyle(
      fontSize: 15,
      fontWeight: FontWeight.w500,
      color: palette.textPrimary,
      height: 1.38,
    );

    Widget? lifecycleBadge;
    if (item.isTrial) {
      lifecycleBadge = Container(
        margin: const EdgeInsets.only(left: 6),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: Colors.orange.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(4),
        ),
        child: const Text(
          '试用中',
          style: TextStyle(
            fontSize: 10.5,
            fontWeight: FontWeight.w600,
            color: Colors.orange,
          ),
        ),
      );
    } else if (item.isVerified) {
      lifecycleBadge = Container(
        margin: const EdgeInsets.only(left: 6),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: _kSuccessColor.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(4),
        ),
        child: const Text(
          '已验证',
          style: TextStyle(
            fontSize: 10.5,
            fontWeight: FontWeight.w600,
            color: _kSuccessColor,
          ),
        ),
      );
    } else if (item.isRetired) {
      lifecycleBadge = Container(
        margin: const EdgeInsets.only(left: 6),
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
        decoration: BoxDecoration(
          color: palette.textTertiary.withValues(alpha: 0.12),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          '已停用',
          style: TextStyle(
            fontSize: 10.5,
            fontWeight: FontWeight.w600,
            color: palette.textTertiary,
          ),
        ),
      );
    }

    if (!item.isBuiltin && !item.isOfficial) {
      return Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(child: Text(item.name, style: titleStyle)),
          ?lifecycleBadge,
        ],
      );
    }

    final badgeLabel =
        item.isBuiltin ? context.l10n.skillBuiltin : context.l10n.skillOfficial;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Flexible(child: Text(item.name, style: titleStyle)),
        Padding(
          padding: const EdgeInsets.only(left: 6),
          child: Tooltip(
            message: badgeLabel,
            child: SvgPicture.string(
              _kBuiltinSkillBadgeCheckSvg,
              width: 16,
              height: 16,
              colorFilter: ColorFilter.mode(
                palette.accentPrimary,
                BlendMode.srcIn,
              ),
            ),
          ),
        ),
        ?lifecycleBadge,
      ],
    );
  }

  String _buildStatusSummary(AgentSkillItem item) {
    final labels = <String>[];
    if (item.isBuiltin) {
      labels.add(context.l10n.skillBuiltin);
    } else if (item.isOfficial) {
      labels.add(context.l10n.skillOfficial);
    } else if (item.installed) {
      labels.add(context.l10n.skillUser);
    }
    if (item.installed) {
      labels.add(
        item.enabled ? context.l10n.skillEnabled : context.l10n.skillDisabled,
      );
    }
    return labels.join(' · ');
  }

  Widget _buildTrailingControl(AgentSkillItem item, {required bool busy}) {
    final palette = context.omniPalette;
    if (item.installed) {
      return SizedBox(
        width: _kSkillTileTrailingWidth,
        height: _kSkillTileTrailingHeight,
        child: Stack(
          alignment: Alignment.centerRight,
          children: [
            OmniSettingsSwitch(
              value: item.enabled,
              enabled: !busy,
              onToggle: (next) => _toggleSkill(item, next),
            ),
            IgnorePointer(
              ignoring: !busy,
              child: AnimatedOpacity(
                duration: const Duration(milliseconds: 160),
                curve: Curves.easeOutCubic,
                opacity: busy ? 1 : 0,
                child: busy
                    ? SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: palette.accentPrimary,
                      ),
                    )
                    : const SizedBox(width: 16, height: 16),
              ),
            ),
          ],
        ),
      );
    }

    return SizedBox(
      width: _kSkillTileTrailingWidth,
      height: _kSkillTileTrailingHeight,
      child: Stack(
        alignment: Alignment.centerRight,
        children: [
          TextButton(
            onPressed: busy ? null : () => _installBuiltinSkill(item),
            style: TextButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
              minimumSize: const Size(0, _kSkillTileTrailingHeight),
              backgroundColor: palette.surfaceSecondary,
              foregroundColor: palette.accentPrimary,
              tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(6),
              ),
            ),
            child: Text(
              context.l10n.skillInstall,
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          IgnorePointer(
            ignoring: !busy,
            child: AnimatedOpacity(
              duration: const Duration(milliseconds: 160),
              curve: Curves.easeOutCubic,
              opacity: busy ? 1 : 0,
                child: busy
                    ? SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: palette.accentPrimary,
                      ),
                    )
                    : const SizedBox(width: 16, height: 16),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchEmpty() {
    final palette = context.omniPalette;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            Icons.search_off_rounded,
            size: 48,
            color:
                context.isDarkTheme ? palette.textTertiary : AppColors.text50,
          ),
          const SizedBox(height: 12),
          Text(
            context.trLegacy('未找到匹配的技能'),
            style: TextStyle(
              fontSize: 16,
              color:
                  context.isDarkTheme ? palette.textSecondary : AppColors.text70,
            ),
          ),
        ],
      ),
    );
  }
}
