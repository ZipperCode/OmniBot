import 'dart:async';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/conversation_model_selector.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

/// 子角色允许与禁用列表的常用工具建议项。
const List<String> kCommonSubagentTools = [
  'file_read',
  'file_write',
  'file_list',
  'file_search',
  'file_stat',
  'file_delete',
  'memory_search',
  'memory_load',
  'memory_write_daily',
  'memory_upsert_longterm',
  'browser_use',
  'terminal_execute',
  'schedule_task_create',
  'web_search',
];

/// 内置基础角色标识集。
const Set<String> kBuiltInSubagentProfileIds = {
  'general',
  'explorer',
  'memory-curator',
  'planner',
};

/// 子角色工具策略授权模式。
enum SubagentToolMode {
  /// 继承基础角色的工具范围（allowedTools == null）。
  inherit,

  /// 禁用所有工具，仅进行纯推理与输出（allowedTools == []）。
  noTools,

  /// 显式允许的工具白名单（allowedTools == [...]）。
  custom,
}

/// 单个子角色配置数据模型。
class SubagentProfileData {
  const SubagentProfileData({
    required this.id,
    required this.displayName,
    this.description = '',
    required this.systemPrompt,
    this.baseProfileId = 'general',
    this.enabled = true,
    this.allowedTools,
    this.disallowedTools = const [],
    this.providerProfileId,
    this.modelId,
  });

  final String id;
  final String displayName;
  final String description;
  final String systemPrompt;
  final String baseProfileId;
  final bool enabled;

  /// null 表示继承 [baseProfileId] 的默认权限；空列表表示禁止所有工具。
  final List<String>? allowedTools;
  final List<String> disallowedTools;

  /// 角色专属 Provider 标识（null 表示继承默认子代理模型）。
  final String? providerProfileId;

  /// 角色专属模型标识（null 表示继承默认子代理模型）。
  final String? modelId;

  bool get isBuiltIn => kBuiltInSubagentProfileIds.contains(id);
  bool get isGeneral => id == 'general';

  /// 是否为该角色显式配置了专用模型（二者皆非空才视为有效配置）。
  bool get hasDedicatedModel =>
      providerProfileId != null &&
      providerProfileId!.isNotEmpty &&
      modelId != null &&
      modelId!.isNotEmpty;

  SubagentToolMode get toolMode {
    if (allowedTools == null) return SubagentToolMode.inherit;
    if (allowedTools!.isEmpty) return SubagentToolMode.noTools;
    return SubagentToolMode.custom;
  }

  factory SubagentProfileData.fromJson(Map<String, dynamic> json) {
    final id = (json['id']?.toString() ?? '').trim();
    final allowedRaw = json['allowedTools'];
    final List<String>? allowedTools = allowedRaw == null
        ? null
        : (allowedRaw as List)
            .map((item) => item.toString().trim())
            .where((item) => item.isNotEmpty)
            .toList();
    final List<String> disallowedTools = (json['disallowedTools'] as List? ??
            const [])
        .map((item) => item.toString().trim())
        .where((item) => item.isNotEmpty)
        .toList();

    final rawProvider = json['providerProfileId']?.toString().trim();
    final rawModel = json['modelId']?.toString().trim();
    if ((rawProvider == null) != (rawModel == null) ||
        (rawProvider != null && rawProvider.isEmpty) ||
        (rawModel != null && rawModel.isEmpty)) {
      throw const FormatException('角色的 Provider 与模型必须成对配置。');
    }
    final providerProfileId = rawProvider;
    final modelId = rawModel;

    return SubagentProfileData(
      id: id,
      displayName: (json['displayName']?.toString() ?? id).trim(),
      description: (json['description']?.toString() ?? '').trim(),
      systemPrompt: (json['systemPrompt']?.toString() ?? '').trim(),
      baseProfileId:
          (json['baseProfileId']?.toString() ?? 'general').trim().isEmpty
              ? 'general'
              : (json['baseProfileId']?.toString() ?? 'general').trim(),
      enabled: json['enabled'] as bool? ?? true,
      allowedTools: allowedTools,
      disallowedTools: disallowedTools,
      providerProfileId: providerProfileId,
      modelId: modelId,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'displayName': displayName,
        'description': description,
        'systemPrompt': systemPrompt,
        'baseProfileId': baseProfileId,
        'enabled': enabled,
        'allowedTools': allowedTools,
        'disallowedTools': disallowedTools,
        'providerProfileId': providerProfileId,
        'modelId': modelId,
      };

  SubagentProfileData copyWith({
    String? id,
    String? displayName,
    String? description,
    String? systemPrompt,
    String? baseProfileId,
    bool? enabled,
    List<String>? allowedTools,
    bool forceAllowedTools = false,
    List<String>? disallowedTools,
    String? providerProfileId,
    String? modelId,
    bool clearModel = false,
  }) {
    return SubagentProfileData(
      id: id ?? this.id,
      displayName: displayName ?? this.displayName,
      description: description ?? this.description,
      systemPrompt: systemPrompt ?? this.systemPrompt,
      baseProfileId: baseProfileId ?? this.baseProfileId,
      enabled: enabled ?? this.enabled,
      allowedTools:
          forceAllowedTools ? allowedTools : (allowedTools ?? this.allowedTools),
      disallowedTools: disallowedTools ?? this.disallowedTools,
      providerProfileId: clearModel
          ? null
          : (providerProfileId ?? this.providerProfileId),
      modelId: clearModel ? null : (modelId ?? this.modelId),
    );
  }
}

/// 小万 Subagent 内存配置快照。
class XiaowanSubagentConfigData {
  const XiaowanSubagentConfigData({
    this.enabled = true,
    this.providerProfileId,
    this.modelId,
    this.reasoningEffort,
    required this.profiles,
  });
  final bool enabled;
  final String? providerProfileId;
  final String? modelId;

  /// 推理思考档位覆盖（null 表示跟随主 Agent；支持 'none', 'minimal', 'low', 'medium', 'high', 'xhigh'）。
  final String? reasoningEffort;
  final List<SubagentProfileData> profiles;

  bool get usesDedicatedModel =>
      providerProfileId != null &&
      providerProfileId!.isNotEmpty &&
      modelId != null &&
      modelId!.isNotEmpty;


  factory XiaowanSubagentConfigData.fromJson(Map<String, dynamic> json) {
    final rawProfiles = json['profiles'];
    if (rawProfiles is! List) {
      throw const FormatException('subagent.profiles 字段缺失或类型无效。');
    }
    final profiles = rawProfiles
        .whereType<Map>()
        .map((item) => SubagentProfileData.fromJson(
            Map<String, dynamic>.from(item)))
        .toList();
    if (profiles.isEmpty) {
      throw const FormatException('subagent.profiles 不能为空。');
    }

    final enabled = json['enabled'] as bool? ?? true;
    final providerProfileId = json['providerProfileId']?.toString();
    final modelId = json['modelId']?.toString();
    final reasoningEffort = json['reasoningEffort']?.toString();

    return XiaowanSubagentConfigData(
      enabled: enabled,
      providerProfileId:
          providerProfileId?.isEmpty == true ? null : providerProfileId,
      modelId: modelId?.isEmpty == true ? null : modelId,
      reasoningEffort:
          reasoningEffort?.isEmpty == true ? null : reasoningEffort,
      profiles: profiles,
    );
  }

  Map<String, dynamic> toJson() => {
        'enabled': enabled,
        'providerProfileId': providerProfileId,
        'modelId': modelId,
        'reasoningEffort': reasoningEffort,
        'profiles': profiles.map((p) => p.toJson()).toList(),
      };

  XiaowanSubagentConfigData copyWith({
    bool? enabled,
    String? providerProfileId,
    String? modelId,
    bool clearModel = false,
    String? reasoningEffort,
    bool forceReasoningEffort = false,
    List<SubagentProfileData>? profiles,
  }) {
    return XiaowanSubagentConfigData(
      enabled: enabled ?? this.enabled,
      providerProfileId: clearModel
          ? null
          : (providerProfileId ?? this.providerProfileId),
      modelId: clearModel ? null : (modelId ?? this.modelId),
      reasoningEffort: forceReasoningEffort
          ? reasoningEffort
          : (reasoningEffort ?? this.reasoningEffort),
      profiles: profiles ?? this.profiles,
    );
  }
}

/// 小万 Subagent 专属设置面板组件。
class XiaowanSubagentSettingsEditor extends StatefulWidget {
  const XiaowanSubagentSettingsEditor({
    super.key,
    required this.config,
    required this.onChanged,
    this.mainBinding,
    this.providerProfiles = const [],
    this.saving = false,
    this.onReload,
  });

  final XiaowanSubagentConfigData config;
  final ValueChanged<XiaowanSubagentConfigData> onChanged;
  final SceneModelBindingEntry? mainBinding;
  final List<ModelProviderProfileSummary> providerProfiles;
  final bool saving;
  final VoidCallback? onReload;

  @override
  State<XiaowanSubagentSettingsEditor> createState() =>
      _XiaowanSubagentSettingsEditorState();
}

class _XiaowanSubagentSettingsEditorState
    extends State<XiaowanSubagentSettingsEditor> {

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  String _text(String zh, String en) => _english ? en : zh;

  static String baseProfileLabel(String baseId, bool isEn) {
    return switch (baseId) {
      'general' => isEn ? 'General' : '通用 (general)',
      'explorer' => isEn ? 'Explorer' : '探索者 (explorer)',
      'memory-curator' => isEn ? 'Memory Curator' : '记忆管理员 (memory-curator)',
      'planner' => isEn ? 'Planner' : '规划器 (planner)',
      _ => baseId,
    };
  }

  static String toolSummaryLabel(SubagentProfileData profile, bool isEn) {
    if (profile.allowedTools == null) {
      return isEn ? 'Inherited' : '继承基础角色';
    }
    if (profile.allowedTools!.isEmpty) {
      return isEn ? 'No tools' : '无工具 (纯推理)';
    }
    final allowedCount = profile.allowedTools!.length;
    final disallowedCount = profile.disallowedTools.length;
    if (disallowedCount > 0) {
      return isEn
          ? '$allowedCount allowed, $disallowedCount blocked'
          : '允许 $allowedCount 项 · 禁用 $disallowedCount 项';
    }
    return isEn ? '$allowedCount allowed' : '允许 $allowedCount 项';
  }

  Future<void> _selectDedicatedModel(BuildContext anchorContext) async {
    if (widget.saving) return;
    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;


    final selection = await showGlassPopup<ConversationModelSelection>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchor,
      child: Builder(
        builder: (popupContext) => LayoutBuilder(
          builder: (context, constraints) => ConversationModelSelectorContent(
            width: 320,
            maxHeight: constraints.maxHeight.clamp(220.0, 460.0),
            loadLiveProviders: true,
            currentSelection: widget.config.usesDedicatedModel
                ? ConversationModelSelection(
                    providerProfileId: widget.config.providerProfileId!,
                    modelId: widget.config.modelId!,
                  )
                : null,
            onSelect: (value) => Navigator.of(popupContext).pop(value),
          ),
        ),
      ),
    );

    if (!mounted || selection == null) return;
    widget.onChanged(
      widget.config.copyWith(
        providerProfileId: selection.providerProfileId,
        modelId: selection.modelId,
      ),
    );
  }

  void _resetToFollowMainModel() {
    widget.onChanged(widget.config.copyWith(clearModel: true));
  }

  void _toggleProfileEnabled(SubagentProfileData profile, bool next) {
    if (profile.isGeneral) {
      // general 通用角色必须保持启用，不可关闭。
      return;
    }
    final updated = widget.config.profiles.map((item) {
      return item.id == profile.id ? item.copyWith(enabled: next) : item;
    }).toList();
    widget.onChanged(widget.config.copyWith(profiles: updated));
  }

  Future<void> _openProfileEditorSheet(
    BuildContext context,
    SubagentProfileData? existingProfile,
  ) async {
    final existingIds = widget.config.profiles.map((p) => p.id).toSet();
    if (existingProfile != null) {
      existingIds.remove(existingProfile.id);
    }

    // 解析当前默认子代理模型显示文案。
    final dedicatedProfile = widget.config.providerProfileId == null
        ? null
        : widget.providerProfiles
            .where((p) => p.id == widget.config.providerProfileId)
            .firstOrNull;
    final defaultSubagentModelDesc = widget.config.usesDedicatedModel
        ? '${dedicatedProfile?.name ?? widget.config.providerProfileId} / ${widget.config.modelId}'
        : null;

    // 解析主 Agent 模型显示文案。
    final mainBinding = widget.mainBinding;
    final mainProfile = mainBinding == null
        ? null
        : widget.providerProfiles
            .where((p) => p.id == mainBinding.providerProfileId)
            .firstOrNull;
    final mainModelDesc = mainBinding == null
        ? _text('默认统一模型', 'Default shared model')
        : '${mainProfile?.name ?? mainBinding.providerProfileId} / ${mainBinding.modelId}';

    await showSettingsDetailSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _ProfileDetailEditorSheet(
        profile: existingProfile,
        existingProfileIds: existingIds,
        providerProfiles: widget.providerProfiles,
        defaultSubagentModelDesc: defaultSubagentModelDesc,
        mainModelDesc: mainModelDesc,
        onSave: (saved) {
          final List<SubagentProfileData> nextProfiles;
          if (existingProfile != null) {
            nextProfiles = widget.config.profiles.map((p) {
              return p.id == existingProfile.id ? saved : p;
            }).toList();
          } else {
            nextProfiles = [...widget.config.profiles, saved];
          }
          widget.onChanged(widget.config.copyWith(profiles: nextProfiles));
        },
        onDelete: existingProfile != null && !existingProfile.isBuiltIn
            ? () {
                final nextProfiles = widget.config.profiles
                    .where((p) => p.id != existingProfile.id)
                    .toList();
                widget.onChanged(widget.config.copyWith(profiles: nextProfiles));
              }
            : null,
      ),
    );
  }

  String _roleModelSummary(SubagentProfileData profile) {
    if (profile.hasDedicatedModel) {
      final provider = widget.providerProfiles
          .where((p) => p.id == profile.providerProfileId)
          .firstOrNull;
      final providerName = provider?.name ?? profile.providerProfileId;
      return '$providerName / ${profile.modelId}';
    }
    return _text('继承默认子代理模型', 'Inherit default subagent model');
  }

  IconData _iconForProfile(String id) {
    return switch (id) {
      'general' => LucideIcons.bot,
      'explorer' => LucideIcons.compass,
      'memory-curator' => LucideIcons.database,
      'planner' => LucideIcons.gitFork,
      _ => LucideIcons.sparkles,
    };
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    // 解析主 Agent 模型展示文案。
    final mainBinding = widget.mainBinding;
    final mainProfile = mainBinding == null
        ? null
        : widget.providerProfiles
            .where((p) => p.id == mainBinding.providerProfileId)
            .firstOrNull;
    final mainModelDesc = mainBinding == null
        ? _text('默认统一模型', 'Default shared model')
        : '${mainProfile?.name ?? mainBinding.providerProfileId} / ${mainBinding.modelId}';

    // 解析独立专用模型展示文案。
    final dedicatedProfile = widget.config.providerProfileId == null
        ? null
        : widget.providerProfiles
            .where((p) => p.id == widget.config.providerProfileId)
            .firstOrNull;
    final dedicatedModelLabel = widget.config.usesDedicatedModel
        ? '${dedicatedProfile?.name ?? widget.config.providerProfileId} / ${widget.config.modelId}'
        : _text('跟随主 Agent（默认: $mainModelDesc）', 'Follow main Agent (default: $mainModelDesc)');

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // 1. 总开关设置组
        OmniSettingsGroup(
          children: [
            OmniSettingsTile(
              tileKey: const Key('xiaowan-subagent-enabled-tile'),
              icon: LucideIcons.bot,
              title: _text('启用 Subagent 子代理', 'Enable Subagents'),
              subtitle: _text(
                '小万可在复杂任务中分派子任务并行执行；禁用后仍可编辑配置，保存后下一轮生效。',
                'Allows Xiaowan to spawn subagents for parallel tasks. Settings stay editable when disabled.',
              ),
              trailing: OmniSettingsSwitch(
                key: const Key('xiaowan-subagent-enabled-switch'),
                value: widget.config.enabled,
                onToggle: (val) {
                  widget.onChanged(widget.config.copyWith(enabled: val));
                },
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),

        // 2. 子代理执行环境设置组（专用模型与思考档位）
        OmniSettingsGroup(
          title: _text('子代理执行环境', 'Execution Environment'),
          subtitle: _text(
            '未单独配置模型的子角色将使用此处的默认子代理模型；未配置时跟随主 Agent。',
            'Subagent roles without a dedicated model inherit this default model; defaults to following the main Agent.',
          ),
          children: [
            Builder(
              builder: (anchorContext) => OmniSettingsTile(
                tileKey: const Key('xiaowan-subagent-dedicated-model-tile'),
                icon: LucideIcons.cpu,
                title: _text('默认子代理模型', 'Default Subagent Model'),
                subtitle: dedicatedModelLabel,
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (widget.config.usesDedicatedModel) ...[
                      TextButton(
                        key: const Key('xiaowan-subagent-reset-model-button'),
                        style: TextButton.styleFrom(
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                          minimumSize: const Size(0, 32),
                          textStyle: const TextStyle(fontSize: 12),
                        ),
                        onPressed: _resetToFollowMainModel,
                        child: Text(_text('跟随主 Agent', 'Follow main')),
                      ),
                      const SizedBox(width: 4),
                    ],
                    Icon(
                      LucideIcons.chevronRight,
                      size: 18,
                      color: palette.textTertiary,
                    ),
                  ],
                ),
                onTap: () => _selectDedicatedModel(anchorContext),
              ),
            ),
            OmniSettingsTile(
              tileKey: const Key('xiaowan-subagent-reasoning-tile'),
              icon: LucideIcons.brain,
              title: _text('思考档位 (Reasoning)', 'Reasoning Effort'),
              subtitle: _text(
                '设置子任务的思考深度；由调用端 Provider 能力适配。',
                'Controls reasoning effort for subagents.',
              ),
              trailing: OmniSettingsDropdown<String?>(
                dropdownKey: const Key('xiaowan-subagent-reasoning-dropdown'),
                value: widget.config.reasoningEffort,
                maxWidth: 168,
                options: [
                  OmniSettingsOption(
                    value: null,
                    label: _text('跟随主 Agent（默认）', 'Follow main (default)'),
                  ),
                  const OmniSettingsOption(value: 'none', label: '关闭 (none)'),
                  OmniSettingsOption(
                    value: 'minimal',
                    label: _text('极低 (minimal)', 'Minimal'),
                  ),
                  OmniSettingsOption(
                    value: 'low',
                    label: _text('低 (low)', 'Low'),
                  ),
                  OmniSettingsOption(
                    value: 'medium',
                    label: _text('中 (medium)', 'Medium'),
                  ),
                  OmniSettingsOption(
                    value: 'high',
                    label: _text('高 (high)', 'High'),
                  ),
                  OmniSettingsOption(
                    value: 'xhigh',
                    label: _text('极高 (xhigh)', 'Extra high'),
                  ),
                ],
                onChanged: (val) {
                  widget.onChanged(
                    widget.config.copyWith(
                      reasoningEffort: val,
                      forceReasoningEffort: true,
                    ),
                  );
                },
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),

        // 3. 子代理角色列表与管理
        OmniSettingsGroup(
          title: _text('子代理角色 (Profiles)', 'Subagent Profiles'),
          subtitle: _text(
            '通用角色必须启用；内置角色不可删除，自定义角色基于基础角色收窄权限。',
            'The general role must stay enabled. Built-in roles cannot be deleted.',
          ),
          headerTrailing: TextButton.icon(
            key: const Key('xiaowan-subagent-add-profile-button'),
            style: TextButton.styleFrom(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              minimumSize: const Size(0, 32),
            ),
            icon: const Icon(LucideIcons.plus, size: 16),
            label: Text(_text('添加角色', 'Add Role')),
            onPressed: () => _openProfileEditorSheet(context, null),
          ),
          children: [
            for (final profile in widget.config.profiles)
              OmniSettingsTile(
                tileKey: Key('xiaowan-subagent-profile-tile-${profile.id}'),
                leading: Icon(
                  _iconForProfile(profile.id),
                  size: 20,
                  color: profile.enabled
                      ? palette.textPrimary
                      : palette.textTertiary,
                ),
                title: profile.displayName,
                subtitle:
                    'ID: ${profile.id} · ${_text('基础', 'Base')}: ${baseProfileLabel(profile.baseProfileId, _english)}\n${_text('模型', 'Model')}: ${_roleModelSummary(profile)}\n${_text('工具', 'Tools')}: ${toolSummaryLabel(profile, _english)}',
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (profile.isGeneral)
                      OmniSettingsStatusBadge(
                        label: _text('必须启用', 'Required'),
                        color: palette.accentPrimary,
                      )
                    else
                      OmniSettingsSwitch(
                        key: Key('xiaowan-subagent-profile-switch-${profile.id}'),
                        value: profile.enabled,
                        onToggle: (val) => _toggleProfileEnabled(profile, val),
                      ),
                    const SizedBox(width: 8),
                    Icon(
                      LucideIcons.chevronRight,
                      size: 18,
                      color: palette.textTertiary,
                    ),
                  ],
                ),
                onTap: () => _openProfileEditorSheet(context, profile),
              ),
          ],
        ),
      ],
    );
  }
}

/// 创建或编辑子角色的详情底部弹窗。
class _ProfileDetailEditorSheet extends StatefulWidget {
  const _ProfileDetailEditorSheet({
    required this.profile,
    required this.existingProfileIds,
    required this.onSave,
    this.onDelete,
    this.providerProfiles = const [],
    this.defaultSubagentModelDesc,
    required this.mainModelDesc,
  });

  final SubagentProfileData? profile;
  final Set<String> existingProfileIds;
  final ValueChanged<SubagentProfileData> onSave;
  final VoidCallback? onDelete;
  final List<ModelProviderProfileSummary> providerProfiles;
  final String? defaultSubagentModelDesc;
  final String mainModelDesc;
  @override
  State<_ProfileDetailEditorSheet> createState() =>
      _ProfileDetailEditorSheetState();
}

class _ProfileDetailEditorSheetState extends State<_ProfileDetailEditorSheet> {
  late final TextEditingController _idController;
  late final TextEditingController _nameController;
  late final TextEditingController _descController;
  late final TextEditingController _promptController;
  late final TextEditingController _toolInputController;
  late final TextEditingController _disallowedToolInputController;

  late String _baseProfileId;
  late bool _enabled;
  late SubagentToolMode _toolMode;
  late List<String> _allowedTools;
  late List<String> _disallowedTools;
  String? _selectedProviderProfileId;
  String? _selectedModelId;
  String? _errorMessage;

  bool get _isNew => widget.profile == null;
  bool get _isBuiltIn => widget.profile?.isBuiltIn ?? false;
  bool get _isGeneral => widget.profile?.isGeneral ?? false;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';
  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    final profile = widget.profile;
    _idController = TextEditingController(text: profile?.id ?? '');
    _nameController = TextEditingController(text: profile?.displayName ?? '');
    _descController = TextEditingController(text: profile?.description ?? '');
    _promptController =
        TextEditingController(text: profile?.systemPrompt ?? '');
    _toolInputController = TextEditingController();
    _disallowedToolInputController = TextEditingController();

    _baseProfileId = profile?.baseProfileId ?? 'general';
    _enabled = profile?.enabled ?? true;
    _toolMode = profile?.toolMode ?? SubagentToolMode.inherit;
    _allowedTools = List<String>.from(profile?.allowedTools ?? const []);
    _disallowedTools = List<String>.from(profile?.disallowedTools ?? const []);
    _selectedProviderProfileId = profile?.providerProfileId;
    _selectedModelId = profile?.modelId;
  }
  @override
  void dispose() {
    _idController.dispose();
    _nameController.dispose();
    _descController.dispose();
    _promptController.dispose();
    _toolInputController.dispose();
    _disallowedToolInputController.dispose();
    super.dispose();
  }

  void _addAllowedTool(String input) {
    final tokens = input
        .split(RegExp(r'[\n,;\s]+'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty);
    if (tokens.isEmpty) return;
    var hasDispatch = false;
    for (final token in tokens) {
      if (token == 'subagent_dispatch') {
        hasDispatch = true;
        continue;
      }
      if (!_allowedTools.contains(token)) {
        _allowedTools.add(token);
      }
    }
    setState(() {
      if (hasDispatch) {
        _errorMessage = _text(
          '已跳过 subagent_dispatch：所有子角色天然禁止递归分派。',
          'subagent_dispatch skipped: recursive dispatch is forbidden.',
        );
      } else {
        _errorMessage = null;
      }
    });
    _toolInputController.clear();
  }

  void _removeAllowedTool(String name) {
    setState(() {
      _allowedTools.remove(name);
    });
  }

  void _addDisallowedTool(String input) {
    final tokens = input
        .split(RegExp(r'[\n,;\s]+'))
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty);
    if (tokens.isEmpty) return;
    for (final token in tokens) {
      if (!_disallowedTools.contains(token)) {
        _disallowedTools.add(token);
      }
    }
    setState(() {
      _errorMessage = null;
    });
    _disallowedToolInputController.clear();
  }

  void _removeDisallowedTool(String name) {
    setState(() {
      _disallowedTools.remove(name);
    });
  }

  Future<void> _selectRoleModel(BuildContext anchorContext) async {
    final anchor = glassPopupAnchorFromContext(anchorContext);
    if (anchor == null) return;

    final hasDedicated = _selectedProviderProfileId != null &&
        _selectedProviderProfileId!.isNotEmpty &&
        _selectedModelId != null &&
        _selectedModelId!.isNotEmpty;

    final currentSelection = hasDedicated
        ? ConversationModelSelection(
            providerProfileId: _selectedProviderProfileId!,
            modelId: _selectedModelId!,
          )
        : null;

    final selection = await showGlassPopup<ConversationModelSelection>(
      context: context,
      sourceContext: anchorContext,
      anchor: anchor,
      child: Builder(
        builder: (popupContext) => LayoutBuilder(
          builder: (context, constraints) => ConversationModelSelectorContent(
            width: 320,
            maxHeight: constraints.maxHeight.clamp(220.0, 460.0),
            loadLiveProviders: true,
            currentSelection: currentSelection,
            onSelect: (value) => Navigator.of(popupContext).pop(value),
          ),
        ),
      ),
    );

    if (!mounted || selection == null) return;
    setState(() {
      _selectedProviderProfileId = selection.providerProfileId;
      _selectedModelId = selection.modelId;
      _errorMessage = null;
    });
  }

  void _resetRoleModel() {
    setState(() {
      _selectedProviderProfileId = null;
      _selectedModelId = null;
      _errorMessage = null;
    });
  }

  void _handleSave() {
    final id = _idController.text.trim();
    final displayName = _nameController.text.trim();
    final prompt = _promptController.text.trim();
    final description = _descController.text.trim();

    if (_isNew) {
      if (id.isEmpty) {
        setState(() => _errorMessage = _text('角色 ID 不能为空。', 'ID is required.'));
        return;
      }
      final idRegex = RegExp(r'^[a-z0-9]+(?:-[a-z0-9]+)*$');
      if (!idRegex.hasMatch(id)) {
        setState(() => _errorMessage = _text(
              '角色 ID 必须为小写字母、数字与单连字符组合（如 custom-reviewer）。',
              'ID must match [a-z0-9]+(?:-[a-z0-9]+)* (e.g. custom-reviewer).',
            ));
        return;
      }
      if (kBuiltInSubagentProfileIds.contains(id)) {
        setState(() => _errorMessage =
            _text('不能使用内置角色 ID。', 'Built-in ID cannot be reused.'));
        return;
      }
      if (widget.existingProfileIds.contains(id)) {
        setState(() => _errorMessage =
            _text('角色 ID 已存在，请使用其他名称。', 'ID is already taken.'));
        return;
      }
    }

    if (displayName.isEmpty) {
      setState(() => _errorMessage =
          _text('角色名称不能为空。', 'Display name is required.'));
      return;
    }

    if (prompt.isEmpty) {
      setState(() => _errorMessage =
          _text('系统提示词不能为空。', 'System prompt is required.'));
      return;
    }

    // 根据工具模式决定最终的 allowedTools 列表。
    final List<String>? finalAllowedTools = switch (_toolMode) {
      SubagentToolMode.inherit => null,
      SubagentToolMode.noTools => <String>[],
      SubagentToolMode.custom => _allowedTools,
    };

    // 角色专属模型配置校验：Provider 与 Model 必须成对设置或成对留空
    final String? roleProvider;
    final String? roleModel;
    final hasProvider = _selectedProviderProfileId != null &&
        _selectedProviderProfileId!.trim().isNotEmpty;
    final hasModel = _selectedModelId != null &&
        _selectedModelId!.trim().isNotEmpty;

    if (hasProvider && hasModel) {
      roleProvider = _selectedProviderProfileId!.trim();
      roleModel = _selectedModelId!.trim();
    } else if (!hasProvider && !hasModel) {
      roleProvider = null;
      roleModel = null;
    } else {
      setState(() => _errorMessage = _text(
        '角色模型配置不完整：Provider 与 Model 必须成对设置或成对留空。',
        'Incomplete role model configuration: Provider and Model must be set together or cleared together.',
      ));
      return;
    }

    final saved = SubagentProfileData(
      id: _isNew ? id : (widget.profile?.id ?? id),
      displayName: displayName,
      description: description,
      systemPrompt: prompt,
      baseProfileId: _isBuiltIn ? (widget.profile?.id ?? id) : _baseProfileId,
      enabled: _isGeneral ? true : _enabled,
      allowedTools: finalAllowedTools,
      disallowedTools: _disallowedTools,
      providerProfileId: roleProvider,
      modelId: roleModel,
    );
    widget.onSave(saved);
    Navigator.of(context).pop();
  }

  void _confirmDelete() {
    showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(_text('删除角色？', 'Delete role?')),
        content: Text(_text(
          '确定要删除自定义角色“${widget.profile?.displayName}”吗？此操作不可撤销。',
          'Are you sure you want to delete “${widget.profile?.displayName}”? This cannot be undone.',
        )),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(_text('取消', 'Cancel')),
          ),
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor: const Color(0xFFE05252),
            ),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(_text('删除', 'Delete')),
          ),
        ],
      ),
    ).then((confirmed) {
      if (confirmed == true && mounted) {
        widget.onDelete?.call();
        Navigator.of(context).pop();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    final title = _isNew
        ? _text('添加自定义角色', 'Add Custom Role')
        : _text('编辑角色：${widget.profile?.displayName}',
            'Edit Role: ${widget.profile?.displayName}');
    final subtitle = _isBuiltIn
        ? _text('内置角色：基础权限固定不可扩权，可定制提示词与工具收窄策略。',
            'Built-in role: base permissions are fixed. Prompt and tool restrictions can be customized.')
        : _text('自定义角色：基于选定的基础角色收窄权限范围。',
            'Custom role: narrows permissions on top of the chosen base profile.');

    return SettingsDetailSheet(
      avoidKeyboard: true,
      title: title,
      subtitle: subtitle,
      actionsKey: const Key('xiaowan-subagent-sheet-actions'),
      actions: [
        if (!_isNew && !_isBuiltIn && widget.onDelete != null)
          TextButton(
            key: const Key('xiaowan-subagent-sheet-delete-button'),
            style: settingsDetailSheetActionStyle(
              context,
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            onPressed: _confirmDelete,
            child: Text(_text('删除角色', 'Delete Role')),
          ),
        TextButton(
          key: const Key('xiaowan-subagent-sheet-cancel-button'),
          style: settingsDetailSheetActionStyle(context),
          onPressed: () => Navigator.of(context).pop(),
          child: Text(_text('取消', 'Cancel')),
        ),
        FilledButton(
          key: const Key('xiaowan-subagent-sheet-save-button'),
          onPressed: _handleSave,
          child: Text(_text('确定', 'Done')),
        ),
      ],
      body: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (_errorMessage != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Theme.of(context)
                      .colorScheme
                      .error
                      .withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(
                    color: Theme.of(context).colorScheme.error,
                    width: 0.8,
                  ),
                ),
                child: Row(
                  children: [
                    Icon(
                      LucideIcons.alertCircle,
                      size: 16,
                      color: Theme.of(context).colorScheme.error,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        _errorMessage!,
                        style: TextStyle(
                          fontSize: 12.5,
                          color: Theme.of(context).colorScheme.error,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 14),
            ],

            // 1. 角色标识 (ID)
            if (_isNew)
              TextField(
                key: const Key('xiaowan-subagent-sheet-id-input'),
                controller: _idController,
                decoration: InputDecoration(
                  labelText: _text('角色标识 (ID)', 'Role ID'),
                  hintText: 'e.g. code-reviewer',
                  helperText: _text(
                    '仅限小写字母、数字及连字符，创建后不可修改',
                    'Lowercase letters, digits, and hyphens only.',
                  ),
                ),
              )
            else
              InputDecorator(
                decoration: InputDecoration(
                  labelText: _text('角色标识 (ID)', 'Role ID'),
                  helperText: _isBuiltIn
                      ? _text('内置角色标识，不可更改', 'Built-in role identifier')
                      : _text('角色标识已确定', 'Role identifier is fixed'),
                ),
                child: Text(
                  widget.profile?.id ?? '',
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 13,
                    color: palette.textSecondary,
                  ),
                ),
              ),
            const SizedBox(height: 14),

            // 2. 角色名称
            TextField(
              key: const Key('xiaowan-subagent-sheet-name-input'),
              controller: _nameController,
              decoration: InputDecoration(
                labelText: _text('角色名称', 'Display Name'),
                hintText: _text('如：代码审查员', 'e.g. Code Reviewer'),
              ),
            ),
            const SizedBox(height: 14),

            // 3. 角色描述
            TextField(
              key: const Key('xiaowan-subagent-sheet-desc-input'),
              controller: _descController,
              decoration: InputDecoration(
                labelText: _text('角色描述', 'Description'),
                hintText: _text('简要说明该角色的分派职责', 'Brief responsibility summary'),
              ),
            ),
            const SizedBox(height: 14),

            // 4. 基础权限角色选择
            if (_isBuiltIn)
              InputDecorator(
                decoration: InputDecoration(
                  labelText: _text('基础权限角色', 'Base Role'),
                  helperText: _text(
                    '内置角色基础权限与其 ID 严格对应，不可改宽',
                    'Built-in roles cannot change their base profile.',
                  ),
                ),
                child: Text(
                  _XiaowanSubagentSettingsEditorState.baseProfileLabel(
                    widget.profile!.baseProfileId,
                    _english,
                  ),
                  style: TextStyle(color: palette.textSecondary),
                ),
              )
            else
              InputDecorator(
                decoration: InputDecoration(
                  labelText: _text('基础权限角色', 'Base Role'),
                  helperText: _text(
                    '决定该角色可收窄的最大权限基础',
                    'Base role defining maximum available capabilities.',
                  ),
                ),
                child: DropdownButtonHideUnderline(
                  child: DropdownButton<String>(
                    key: const Key('xiaowan-subagent-sheet-base-profile-dropdown'),
                    value: _baseProfileId,
                    isDense: true,
                    items: [
                      DropdownMenuItem(
                        value: 'general',
                        child: Text(_XiaowanSubagentSettingsEditorState
                            .baseProfileLabel('general', _english)),
                      ),
                      DropdownMenuItem(
                        value: 'explorer',
                        child: Text(_XiaowanSubagentSettingsEditorState
                            .baseProfileLabel('explorer', _english)),
                      ),
                      DropdownMenuItem(
                        value: 'memory-curator',
                        child: Text(_XiaowanSubagentSettingsEditorState
                            .baseProfileLabel('memory-curator', _english)),
                      ),
                      DropdownMenuItem(
                        value: 'planner',
                        child: Text(_XiaowanSubagentSettingsEditorState
                            .baseProfileLabel('planner', _english)),
                      ),
                    ],
                    onChanged: (val) {
                      if (val != null) setState(() => _baseProfileId = val);
                    },
                  ),
                ),
              ),
            const SizedBox(height: 14),

            // 5. 角色启用开关
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        _text('启用该角色', 'Enable this role'),
                        style: TextStyle(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w500,
                          color: palette.textPrimary,
                        ),
                      ),
                      if (_isGeneral)
                        Text(
                          _text(
                            'general 通用角色为默认分派基础，必须始终保持启用',
                            'The general role is required and cannot be disabled.',
                          ),
                          style: TextStyle(
                            fontSize: 12,
                            color: palette.textTertiary,
                          ),
                        ),
                    ],
                  ),
                ),
                OmniSettingsSwitch(
                  key: const Key('xiaowan-subagent-sheet-enabled-switch'),
                  value: _isGeneral ? true : _enabled,
                  enabled: !_isGeneral,
                  onToggle: (val) {
                    if (!_isGeneral) setState(() => _enabled = val);
                  },
                ),
              ],
            ),
            const SizedBox(height: 16),
            // 6. 角色模型选择与继承设置
            Builder(
              builder: (anchorContext) {
                final hasDedicatedModel = _selectedProviderProfileId != null &&
                    _selectedProviderProfileId!.isNotEmpty &&
                    _selectedModelId != null &&
                    _selectedModelId!.isNotEmpty;

                final selectedProvider = _selectedProviderProfileId == null
                    ? null
                    : widget.providerProfiles
                        .where((p) => p.id == _selectedProviderProfileId)
                        .firstOrNull;
                final modelDisplayName = hasDedicatedModel
                    ? '${selectedProvider?.name ?? _selectedProviderProfileId} / $_selectedModelId'
                    : (widget.defaultSubagentModelDesc != null
                        ? _text(
                            '使用默认子代理模型 (${widget.defaultSubagentModelDesc})',
                            'Use default subagent model (${widget.defaultSubagentModelDesc})',
                          )
                        : _text(
                            '使用默认子代理模型（未配置，跟随主 Agent: ${widget.mainModelDesc}）',
                            'Use default subagent model (unconfigured, follows main: ${widget.mainModelDesc})',
                          ));

                return InkWell(
                  key: const Key('xiaowan-subagent-sheet-model-selector'),
                  borderRadius: BorderRadius.circular(12),
                  onTap: () => _selectRoleModel(anchorContext),
                  child: InputDecorator(
                    decoration: InputDecoration(
                      labelText: _text('角色模型 (Role Model)', 'Role Model'),
                      helperText: hasDedicatedModel
                          ? _text('已为该角色单独配置专用模型；点击可修改。', 'Dedicated model configured for this role. Tap to change.')
                          : _text(
                              '未配置时自动使用默认子代理模型。点击可为该角色单独配置模型。',
                              'Inherits default subagent model. Tap to configure a dedicated model.',
                            ),
                      suffixIcon: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (hasDedicatedModel) ...[
                            TextButton(
                              key: const Key('xiaowan-subagent-sheet-reset-model-button'),
                              style: TextButton.styleFrom(
                                padding: const EdgeInsets.symmetric(horizontal: 8),
                                minimumSize: const Size(0, 32),
                                textStyle: const TextStyle(fontSize: 12),
                              ),
                              onPressed: _resetRoleModel,
                              child: Text(_text('使用默认子模型', 'Use default')),
                            ),
                            const SizedBox(width: 4),
                          ],
                          Icon(
                            LucideIcons.chevronRight,
                            size: 18,
                            color: palette.textTertiary,
                          ),
                          const SizedBox(width: 8),
                        ],
                      ),
                    ),
                    child: Text(
                      modelDisplayName,
                      style: TextStyle(
                        fontSize: 13.5,
                        color: hasDedicatedModel
                            ? palette.textPrimary
                            : palette.textSecondary,
                        fontWeight: hasDedicatedModel ? FontWeight.w500 : FontWeight.normal,
                      ),
                    ),
                  ),
                );
              },
            ),
            const SizedBox(height: 14),
            // 7. 系统提示词
            TextField(
              key: const Key('xiaowan-subagent-sheet-prompt-input'),
              controller: _promptController,
              minLines: 5,
              maxLines: 12,
              keyboardType: TextInputType.multiline,
              style: const TextStyle(fontSize: 13, height: 1.45),
              decoration: InputDecoration(
                labelText: _text('系统提示词 (System Prompt)', 'System Prompt'),
                alignLabelWithHint: true,
                hintText: _text(
                  '输入指导该角色执行任务的系统提示词…',
                  'Instructions for guiding this subagent role…',
                ),
              ),
            ),
            const SizedBox(height: 20),

            // 8. 工具授权策略
            Text(
              _text('工具使用策略', 'Tool Permissions Policy'),
              style: TextStyle(
                fontSize: 14.5,
                fontWeight: FontWeight.w600,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              _text(
                '工具策略只能在基础角色权限内收窄；区分“继承基础角色”与“无工具”。所有子角色天然禁止 subagent_dispatch。',
                'Tools can only be narrowed within the base role. Distinguish inheriting from having no tools.',
              ),
              style: TextStyle(fontSize: 12, color: palette.textSecondary),
            ),
            const SizedBox(height: 12),

            // 工具模式下拉选择
            InputDecorator(
              decoration: InputDecoration(
                labelText: _text('工具授权模式', 'Tool Mode'),
              ),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<SubagentToolMode>(
                  key: const Key('xiaowan-subagent-sheet-tool-mode-dropdown'),
                  value: _toolMode,
                  isDense: true,
                  items: [
                    DropdownMenuItem(
                      value: SubagentToolMode.inherit,
                      child: Text(_text(
                        '继承基础角色（默认全部允许）',
                        'Inherit base role (default)',
                      )),
                    ),
                    DropdownMenuItem(
                      value: SubagentToolMode.noTools,
                      child: Text(_text(
                        '无工具 (纯推理与文本总结)',
                        'No tools (pure reasoning)',
                      )),
                    ),
                    DropdownMenuItem(
                      value: SubagentToolMode.custom,
                      child: Text(_text(
                        '显式白名单（仅允许指定工具）',
                        'Explicit whitelist (custom allowed tools)',
                      )),
                    ),
                  ],
                  onChanged: (mode) {
                    if (mode != null) setState(() => _toolMode = mode);
                  },
                ),
              ),
            ),
            const SizedBox(height: 12),

            // 显式允许工具（白名单）输入
            if (_toolMode == SubagentToolMode.custom) ...[
              Text(
                _text('允许的工具 (白名单)：', 'Allowed tools (whitelist):'),
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                  color: palette.textPrimary,
                ),
              ),
              const SizedBox(height: 6),
              if (_allowedTools.isEmpty)
                Text(
                  _text('暂未添加任何允许的工具（等同于无工具）',
                      'No tools added yet (effectively no tools).'),
                  style: TextStyle(
                    fontSize: 12,
                    fontStyle: FontStyle.italic,
                    color: palette.textTertiary,
                  ),
                )
              else
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: [
                    for (final tool in _allowedTools)
                      Chip(
                        key: Key('xiaowan-subagent-sheet-allowed-chip-$tool'),
                        label: Text(tool, style: const TextStyle(fontSize: 12)),
                        onDeleted: () => _removeAllowedTool(tool),
                        deleteIconColor: palette.textSecondary,
                        visualDensity: VisualDensity.compact,
                      ),
                  ],
                ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const Key('xiaowan-subagent-sheet-tool-input'),
                      controller: _toolInputController,
                      minLines: 1,
                      maxLines: 3,
                      keyboardType: TextInputType.multiline,
                      style: const TextStyle(fontSize: 13),
                      decoration: InputDecoration(
                        isDense: true,
                        hintText: _text(
                          '输入工具名（支持多行/逗号分隔），如 file_read',
                          'Tool names (multi-line or comma separated), e.g. file_read',
                        ),
                      ),
                      onSubmitted: _addAllowedTool,
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton(
                    key: const Key('xiaowan-subagent-sheet-add-tool-btn'),
                    icon: const Icon(LucideIcons.plus, size: 20),
                    tooltip: _text('添加允许工具', 'Add allowed tool'),
                    onPressed: () =>
                        _addAllowedTool(_toolInputController.text),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Wrap(
                spacing: 6,
                runSpacing: 4,
                children: [
                  for (final suggestion in kCommonSubagentTools)
                    if (!_allowedTools.contains(suggestion))
                      ActionChip(
                        visualDensity: VisualDensity.compact,
                        label: Text('+ $suggestion',
                            style: const TextStyle(fontSize: 11)),
                        onPressed: () => _addAllowedTool(suggestion),
                      ),
                ],
              ),
              const SizedBox(height: 16),
            ],

            // 9. 显式禁止工具（黑名单）
            Text(
              _text('显式禁止的工具 (黑名单)：', 'Disallowed tools (blacklist):'),
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w500,
                color: palette.textPrimary,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              _text(
                '在此列出的工具将在该角色中被强制阻断，即使基础角色允许。',
                'Tools listed here are strictly blocked even if permitted by the base role.',
              ),
              style: TextStyle(fontSize: 11.5, color: palette.textTertiary),
            ),
            const SizedBox(height: 6),
            if (_disallowedTools.isNotEmpty)
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  for (final tool in _disallowedTools)
                    Chip(
                      key: Key('xiaowan-subagent-sheet-disallowed-chip-$tool'),
                      label: Text(tool,
                          style: TextStyle(
                            fontSize: 12,
                            color: Theme.of(context).colorScheme.error,
                          )),
                      backgroundColor: Theme.of(context)
                          .colorScheme
                          .error
                          .withValues(alpha: 0.1),
                      onDeleted: () => _removeDisallowedTool(tool),
                      deleteIconColor: Theme.of(context).colorScheme.error,
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    key: const Key(
                        'xiaowan-subagent-sheet-disallowed-tool-input'),
                    controller: _disallowedToolInputController,
                    minLines: 1,
                    maxLines: 3,
                    keyboardType: TextInputType.multiline,
                    style: const TextStyle(fontSize: 13),
                    decoration: InputDecoration(
                      isDense: true,
                      hintText: _text(
                        '输入禁止工具名（支持多行/逗号分隔），如 terminal_execute',
                        'Blocked tool names (multi-line or comma separated), e.g. terminal_execute',
                      ),
                    ),
                    onSubmitted: _addDisallowedTool,
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  key: const Key(
                      'xiaowan-subagent-sheet-add-disallowed-tool-btn'),
                  icon: const Icon(LucideIcons.plus, size: 20),
                  tooltip: _text('添加禁止工具', 'Add blocked tool'),
                  onPressed: () =>
                      _addDisallowedTool(_disallowedToolInputController.text),
                ),
              ],
            ),
          ],
        ),
    );
  }
}
