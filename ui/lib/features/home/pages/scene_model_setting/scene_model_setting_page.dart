import 'dart:async';

import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/flclash/page_activity.dart';
import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/chatgpt_oauth_service.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/model_vendor_catalog.dart';
import 'package:ui/services/scene_model_config_service.dart';
import 'package:ui/services/voice_playback_coordinator.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/agent_avatar.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:ui/l10n/l10n.dart';
import 'package:ui/widgets/settings_section_title.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/features/home/pages/model_provider_setting/model_provider_setting_page.dart';
import 'package:ui/widgets/navigation/page_route.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/services/root_access_service.dart';
import 'package:ui/widgets/conversation_model_selector.dart';
import 'package:ui/widgets/glass_popup.dart';

enum ModelCapabilityStatus {
  compatible,
  unknown,
  incompatible,
}

typedef _SceneModelChoice = ({String providerProfileId, String modelId});

const _SceneModelChoice _defaultSceneModelChoice = (
  providerProfileId: '',
  modelId: '',
);

class SceneModelSettingPage extends StatefulWidget {
  const SceneModelSettingPage({super.key});

  @override
  State<SceneModelSettingPage> createState() => _SceneModelSettingPageState();
}

class _SceneModelSettingPageState extends State<SceneModelSettingPage>
    with FlClashPageLoadMixin {
  static const Map<String, String> _sceneDisplayNameMap = {
    SceneModelConfigService.sceneDispatchModel: '主 Agent',
    SceneModelConfigService.sceneVisionUnderstanding: '视觉理解',
    SceneModelConfigService.sceneVlmOperationPrimary: 'GUI 操作',
    SceneModelConfigService.sceneImageGenerate: '图像生成',
    SceneModelConfigService.sceneVoice: '语音合成',
    SceneModelConfigService.sceneCompactorContextChat: '上下文压缩',
    'scene.compactor.context': '上下文压缩',
    SceneModelConfigService.sceneMemoryEmbedding: '记忆嵌入',
    SceneModelConfigService.sceneMemoryRollup: '记忆整理',
  };

  static const Map<String, String> _sceneTooltipMap = {
    SceneModelConfigService.sceneDispatchModel: '负责主对话、任务理解与工具分流决策',
    SceneModelConfigService.sceneVisionUnderstanding:
        '负责图片解析与文字识别；为不支持视觉的主模型提供辅助',
    SceneModelConfigService.sceneVlmOperationPrimary:
        '负责 Android GUI 观察与屏幕动作决策',
    SceneModelConfigService.sceneImageGenerate:
        '负责独立的文生图工具调用，支持 OpenAI 与 Gemini 生图协议',
    SceneModelConfigService.sceneVoice:
        '负责助手回复语音合成，需要支持聊天音频输出的模型（Chat Completions audio）',
    SceneModelConfigService.sceneCompactorContextChat:
        '负责聊天历史长文本压缩与总结',
    'scene.compactor.context': '负责聊天历史长文本压缩与总结',
    SceneModelConfigService.sceneMemoryEmbedding:
        '负责 workspace 记忆向量检索的嵌入模型',
    SceneModelConfigService.sceneMemoryRollup:
        '负责夜间记忆整理策略与事实提炼',
  };
  bool _isLoading = true;
  bool _isRefreshingModels = false;
  Completer<void>? _providerRefreshCompleter;
  int _providerRefreshGeneration = 0;
  List<SceneCatalogItem> _catalog = const [];
  List<SceneModelBindingEntry> _bindings = const [];
  List<ModelProviderProfileSummary> _profiles = const [];
  SceneVoiceConfig _voiceConfig = const SceneVoiceConfig();
  Map<String, List<ProviderModelOption>> _providerModelsByProfileId = {};
  Set<String> _savingSceneIds = <String>{};
  Map<String, String> _providerErrors = <String, String>{};
  RootAccessSnapshot _rootSnapshot = const RootAccessSnapshot();
  final Set<String> _manuallyToggledScenes = <String>{};
  final Set<String> _expandedOptionalSceneIds = <String>{};

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  static const List<String> _optionalSceneIds = [
    SceneModelConfigService.sceneVisionUnderstanding,
    SceneModelConfigService.sceneVlmOperationPrimary,
    SceneModelConfigService.sceneImageGenerate,
    SceneModelConfigService.sceneVoice,
    SceneModelConfigService.sceneCompactorContextChat,
    SceneModelConfigService.sceneMemoryEmbedding,
    SceneModelConfigService.sceneMemoryRollup,
  ];

  List<SceneCatalogItem> get _orderedBasicScenes {
    final dispatchItem = _catalog
        .where((item) => item.sceneId == SceneModelConfigService.sceneDispatchModel)
        .firstOrNull;
    if (dispatchItem != null) {
      return [dispatchItem];
    }
    return const [];
  }

  List<SceneCatalogItem> get _orderedOptionalScenes {
    final map = {
      for (final item in _catalog)
        if (item.sceneId != 'scene.compactor.context' &&
            item.sceneId != SceneModelConfigService.sceneDispatchModel)
          item.sceneId: item,
    };
    final result = <SceneCatalogItem>[];
    for (final id in _optionalSceneIds) {
      final item = map.remove(id);
      if (item != null) {
        result.add(item);
      }
    }
    for (final entry in map.entries) {
      result.add(entry.value);
    }
    return result;
  }


  bool _hasBinding(String sceneId) {
    final binding = _bindingMap[sceneId];
    return binding != null && binding.modelId.trim().isNotEmpty;
  }

  bool _isSceneExpanded(String sceneId) {
    if (_manuallyToggledScenes.contains(sceneId)) {
      return _expandedOptionalSceneIds.contains(sceneId);
    }
    return _hasBinding(sceneId);
  }

  void _toggleOptionalScene(String sceneId) {
    setState(() {
      final current = _isSceneExpanded(sceneId);
      _manuallyToggledScenes.add(sceneId);
      if (current) {
        _expandedOptionalSceneIds.remove(sceneId);
      } else {
        _expandedOptionalSceneIds.add(sceneId);
      }
    });
  }

  Map<String, SceneModelBindingEntry> get _bindingMap {
    return {for (final item in _bindings) item.sceneId: item};
  }

  Color get _pageBackground => context.omniPalette.pageBackground;
  Color get _primaryTextColor => context.omniPalette.textPrimary;
  Color get _secondaryTextColor => context.omniPalette.textSecondary;
  Color get _tertiaryTextColor => context.omniPalette.textTertiary;
  String _sceneDisplayName(String sceneId) {
    return _sceneDisplayNameMap[sceneId] ?? sceneId;
  }

  String _sceneTooltip(SceneCatalogItem item) {
    final mapped = _sceneTooltipMap[item.sceneId];
    if (mapped != null) {
      return context.trLegacy(mapped);
    }
    if (item.description.trim().isNotEmpty) {
      return context.trLegacy(item.description.trim());
    }
    return item.sceneId;
  }

  bool _isSavingScene(String sceneId) {
    return _savingSceneIds.contains(sceneId);
  }

  bool _isAgentScene(String sceneId) {
    return sceneId == 'scene.dispatch.model';
  }

  bool _isImageGenerationSupportedProvider(
    ModelProviderProfileSummary profile,
  ) {
    final protocol = profile.protocolType.trim().toLowerCase();
    final source = profile.sourceType.trim().toLowerCase();
    if (source == ChatGptOAuthService.sourceType ||
        profile.readOnly ||
        protocol == 'anthropic' ||
        protocol == 'deepseek' ||
        source == 'deepseek') {
      return false;
    }
    return protocol == 'gemini' ||
        protocol == 'openai_compatible' ||
        protocol == 'openai';
  }

  bool? _resolveImageInput(ProviderModelOption model) {
    if (model.supportsImageInput != null) {
      return model.supportsImageInput;
    }
    if (model.inputModalities.isNotEmpty) {
      return model.inputModalities.contains('image');
    }
    return null;
  }

  bool? _resolveImageOutput(ProviderModelOption model) {
    if (model.supportsImageOutput != null) {
      return model.supportsImageOutput;
    }
    if (model.outputModalities.isNotEmpty) {
      return model.outputModalities.contains('image');
    }
    if (model.modelType == 'image') {
      return true;
    }
    return null;
  }

  bool? _resolveAudioOutput(ProviderModelOption model) {
    if (model.supportsAudioOutput != null) {
      return model.supportsAudioOutput;
    }
    if (model.outputModalities.isNotEmpty) {
      return model.outputModalities.contains('audio');
    }
    if (model.modelType == 'tts') {
      return true;
    }
    return null;
  }

  ModelCapabilityStatus _evaluateModelCapability({
    required String sceneId,
    required ModelProviderProfileSummary profile,
    required ProviderModelOption model,
  }) {
    final modelType = model.modelType?.toLowerCase().trim();
    final outputMods = model.outputModalities
        .map((e) => e.toLowerCase().trim())
        .where((e) => e.isNotEmpty)
        .toSet();

    switch (sceneId) {
      case SceneModelConfigService.sceneDispatchModel:
        if (modelType == 'embedding' ||
            modelType == 'image' ||
            modelType == 'tts') {
          return ModelCapabilityStatus.incompatible;
        }
        if (outputMods.isNotEmpty && !outputMods.contains('text')) {
          return ModelCapabilityStatus.incompatible;
        }
        if (model.toolCall == false) {
          return ModelCapabilityStatus.incompatible;
        }
        if (modelType == 'chat' || outputMods.contains('text')) {
          return ModelCapabilityStatus.compatible;
        }
        return ModelCapabilityStatus.unknown;

      case SceneModelConfigService.sceneVisionUnderstanding:
      case SceneModelConfigService.sceneVlmOperationPrimary:
        if (modelType == 'embedding' ||
            modelType == 'image' ||
            modelType == 'tts') {
          return ModelCapabilityStatus.incompatible;
        }
        final hasImageInput = _resolveImageInput(model);
        if (hasImageInput == false) {
          return ModelCapabilityStatus.incompatible;
        }
        if (hasImageInput == true) {
          return ModelCapabilityStatus.compatible;
        }
        return ModelCapabilityStatus.unknown;

      case SceneModelConfigService.sceneImageGenerate:
        if (!_isImageGenerationSupportedProvider(profile)) {
          return ModelCapabilityStatus.incompatible;
        }
        if (modelType == 'embedding' || modelType == 'tts') {
          return ModelCapabilityStatus.incompatible;
        }
        final hasImageOutput = _resolveImageOutput(model);
        if (hasImageOutput == false) {
          return ModelCapabilityStatus.incompatible;
        }
        if (hasImageOutput == true) {
          return ModelCapabilityStatus.compatible;
        }
        return ModelCapabilityStatus.unknown;

      case SceneModelConfigService.sceneVoice:
        final protocol = profile.protocolType.trim().toLowerCase();
        if (protocol != 'openai_compatible' && protocol != 'openai') {
          return ModelCapabilityStatus.incompatible;
        }
        if (modelType == 'embedding' || modelType == 'image') {
          return ModelCapabilityStatus.incompatible;
        }
        final hasAudioOutput = _resolveAudioOutput(model);
        if (hasAudioOutput == false) {
          return ModelCapabilityStatus.incompatible;
        }
        if (hasAudioOutput == true) {
          return ModelCapabilityStatus.compatible;
        }
        return ModelCapabilityStatus.unknown;

      case SceneModelConfigService.sceneMemoryEmbedding:
        if (modelType == 'image' ||
            modelType == 'tts' ||
            modelType == 'chat') {
          return ModelCapabilityStatus.incompatible;
        }
        if (outputMods.isNotEmpty &&
            !outputMods.contains('embedding') &&
            modelType != 'embedding') {
          return ModelCapabilityStatus.incompatible;
        }
        if (modelType == 'embedding' || outputMods.contains('embedding')) {
          return ModelCapabilityStatus.compatible;
        }
        return ModelCapabilityStatus.unknown;

      case SceneModelConfigService.sceneCompactorContextChat:
      case 'scene.compactor.context':
      case SceneModelConfigService.sceneMemoryRollup:
      default:
        if (modelType == 'embedding' ||
            modelType == 'image' ||
            modelType == 'tts') {
          return ModelCapabilityStatus.incompatible;
        }
        if (outputMods.isNotEmpty && !outputMods.contains('text')) {
          return ModelCapabilityStatus.incompatible;
        }
        if (modelType == 'chat' || outputMods.contains('text')) {
          return ModelCapabilityStatus.compatible;
        }
        return ModelCapabilityStatus.unknown;
    }
  }

  Map<String, List<ProviderModelOption>> _modelsForScene(
    SceneCatalogItem scene,
  ) {
    return {
      for (final profile in _profiles)
        profile.id: (_providerModelsByProfileId[profile.id] ??
                const <ProviderModelOption>[])
            .where((model) {
              final status = _evaluateModelCapability(
                sceneId: scene.sceneId,
                profile: profile,
                model: model,
              );
              return status != ModelCapabilityStatus.incompatible;
            })
            .toList(),
    };
  }

  Future<void> _loadData({bool showLoading = true}) async {
    if (showLoading && mounted) {
      setState(() => _isLoading = true);
    }
    try {
      final results = await Future.wait<dynamic>([
        SceneModelConfigService.getSceneCatalog(),
        SceneModelConfigService.getSceneModelBindings(),
        ModelProviderConfigService.listProfiles(),
        SceneModelConfigService.getSceneVoiceConfig(),
        RootAccessService.status().catchError((_) => const RootAccessSnapshot()),
      ]);

      final catalog = results[0] as List<SceneCatalogItem>;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final profilesPayload = results[2] as ModelProviderProfilesPayload;
      final voiceConfig = results[3] as SceneVoiceConfig;
      final rootSnapshot = (results.length > 4 ? results[4] : null) as RootAccessSnapshot? ??
          const RootAccessSnapshot();
      final providerModelsByProfileId = <String, List<ProviderModelOption>>{};
      for (final profile in profilesPayload.profiles) {
        providerModelsByProfileId[profile.id] =
            await ModelProviderConfigService.getStoredModelOptionsForProfile(
              profile.id,
              profile: profile,
              enrichMetadata: false,
            );
      }

      final enriched = _mergeBindingModels(
        providerModelsByProfileId: providerModelsByProfileId,
        bindings: bindings,
      );

      if (!mounted) return;
      setState(() {
        _catalog = catalog;
        _bindings = bindings;
        _profiles = profilesPayload.profiles;
        _voiceConfig = voiceConfig;
        _rootSnapshot = rootSnapshot;
        _providerModelsByProfileId = enriched;
        _providerErrors = <String, String>{};
      });
      _scheduleMetadataRefresh(
        profiles: profilesPayload.profiles,
        providerModelsByProfileId: enriched,
      );
    } catch (_) {
      if (!mounted) return;
      showToast(context.l10n.sceneModelLoadFailed, type: ToastType.error);
    } finally {
      if (showLoading && mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _saveVoiceConfig(SceneVoiceConfig config) async {
    try {
      final saved = await SceneModelConfigService.saveSceneVoiceConfig(config);
      if (!mounted) return;
      setState(() => _voiceConfig = saved);
      await VoicePlaybackCoordinator.instance.refreshConfiguration();
      if (mounted) {
        showToast(context.trLegacy('语音设置已保存'), type: ToastType.success);
      }
    } catch (error) {
      if (mounted) {
        showToast(context.trLegacy('语音设置保存失败：$error'), type: ToastType.error);
      }
    }
  }

  Map<String, List<ProviderModelOption>> _mergeBindingModels({
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
    required List<SceneModelBindingEntry> bindings,
  }) {
    final result = {
      for (final entry in providerModelsByProfileId.entries)
        entry.key: [...entry.value],
    };
    for (final binding in bindings) {
      final bucket = result.putIfAbsent(binding.providerProfileId, () => []);
      final exists = bucket.any((item) => item.id == binding.modelId);
      if (!exists) {
        bucket.add(
          ProviderModelOption(
            id: binding.modelId,
            displayName: binding.modelId,
            ownedBy: 'binding',
          ),
        );
      }
    }
    return result;
  }

  void _scheduleMetadataRefresh({
    required List<ModelProviderProfileSummary> profiles,
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
  }) {
    if (!providerModelsByProfileId.values.any((models) => models.isNotEmpty)) {
      return;
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted ||
          !identical(_providerModelsByProfileId, providerModelsByProfileId)) {
        return;
      }
      unawaited(
        _refreshMetadata(
          profiles: profiles,
          providerModelsByProfileId: providerModelsByProfileId,
        ),
      );
    });
  }

  Future<void> _refreshMetadata({
    required List<ModelProviderProfileSummary> profiles,
    required Map<String, List<ProviderModelOption>> providerModelsByProfileId,
  }) async {
    if (!await waitForPageReady() || !mounted) return;
    final enrichedEntries =
        await Future.wait<MapEntry<String, List<ProviderModelOption>>>(
          profiles.map((profile) async {
            final models =
                providerModelsByProfileId[profile.id] ??
                const <ProviderModelOption>[];
            return MapEntry(
              profile.id,
              await ModelProviderConfigService.enrichModelsForProfile(
                profileId: profile.id,
                providerName: profile.name,
                apiBase: profile.baseUrl,
                models: models,
              ),
            );
          }),
        );
    if (!await waitForPageReady() ||
        !identical(_providerModelsByProfileId, providerModelsByProfileId)) {
      return;
    }
    setState(() {
      _providerModelsByProfileId = <String, List<ProviderModelOption>>{
        ...providerModelsByProfileId,
        for (final entry in enrichedEntries) entry.key: entry.value,
      };
    });
  }

  Future<void> _refreshProviderModels() async {
    if (!mounted) return;
    if (_isRefreshingModels) {
      await _providerRefreshCompleter?.future;
      return;
    }
    final refreshCompleter = Completer<void>();
    _providerRefreshCompleter = refreshCompleter;
    final refreshGeneration = ++_providerRefreshGeneration;
    setState(() {
      _isRefreshingModels = true;
    });
    try {
      final snapshots = List<ModelProviderProfileSummary>.from(_profiles);
      final nextModels = <String, List<ProviderModelOption>>{
        for (final entry in _providerModelsByProfileId.entries)
          entry.key: List<ProviderModelOption>.from(entry.value),
      };
      final newErrors = <String, String>{};
      for (final profile in snapshots) {
        if (!_isProviderRefreshActive(refreshGeneration)) return;
        if (!profile.configured) {
          continue;
        }
        try {
          final remoteModels = await _fetchModelsForSnapshot(
            profile,
            refreshGeneration: refreshGeneration,
          );
          if (!_isProviderRefreshActive(refreshGeneration)) return;
          final manualModelIds =
              await ModelProviderConfigService.getManualModelIds(
                profileId: profile.id,
              );
          nextModels[profile.id] = ModelProviderConfigService.mergeModelOptions(
            remoteModels: remoteModels,
            manualModelIds: manualModelIds,
          );
        } catch (_) {
          newErrors[profile.id] = '模型列表加载失败';
          final stored =
              await ModelProviderConfigService.getStoredModelOptionsForProfile(
                profile.id,
                profile: profile,
                enrichMetadata: false,
              );
          if (stored.isNotEmpty || nextModels[profile.id] == null) {
            nextModels[profile.id] = stored;
          }
          // 不弹全局toast，保持已有本地目录
        }
      }

      if (!mounted ||
          !_isProviderRefreshActive(refreshGeneration)) {
        return;
      }
      final merged = _mergeBindingModels(
        providerModelsByProfileId: nextModels,
        bindings: _bindings,
      );
      setState(() {
        _providerModelsByProfileId = merged;
        _providerErrors = newErrors;
      });
      _scheduleMetadataRefresh(
        profiles: snapshots,
        providerModelsByProfileId: merged,
      );
    } catch (_) {
      // 不弹全局toast
    } finally {
      if (refreshGeneration == _providerRefreshGeneration && mounted) {
        setState(() {
          _isRefreshingModels = false;
        });
      }
      if (!refreshCompleter.isCompleted) {
        refreshCompleter.complete();
      }
      if (identical(_providerRefreshCompleter, refreshCompleter)) {
        _providerRefreshCompleter = null;
      }
    }
  }


  Future<List<ProviderModelOption>> _fetchModelsForSnapshot(
    ModelProviderProfileSummary snapshot, {
    required int refreshGeneration,
  }) async {
    if (!_isProviderRefreshActive(refreshGeneration)) return const [];
    final current = _findProfile(_profiles, snapshot.id);
    if (current == null || !_sameProviderSnapshot(snapshot, current)) {
      return const [];
    }
    final models = await ModelProviderConfigService.fetchModels(
      apiBase: snapshot.baseUrl,
      profileId: snapshot.id,
      providerName: snapshot.name,
    );
    if (!_isProviderRefreshActive(refreshGeneration)) return const [];
    final latestPayload = await ModelProviderConfigService.listProfiles();
    if (!_isProviderRefreshActive(refreshGeneration)) return const [];
    final latest = _findProfile(latestPayload.profiles, snapshot.id);
    final local = _findProfile(_profiles, snapshot.id);
    if (latest == null ||
        local == null ||
        !_sameProviderSnapshot(snapshot, latest) ||
        !_sameProviderSnapshot(snapshot, local)) {
      return const [];
    }
    return models;
  }

  bool _isProviderRefreshActive(int generation) =>
      mounted &&
      _isRefreshingModels &&
      generation == _providerRefreshGeneration;

  ModelProviderProfileSummary? _findProfile(
    List<ModelProviderProfileSummary> profiles,
    String id,
  ) {
    for (final profile in profiles) {
      if (profile.id == id) return profile;
    }
    return null;
  }

  bool _sameProviderSnapshot(
    ModelProviderProfileSummary left,
    ModelProviderProfileSummary right,
  ) =>
      left.id == right.id &&
      left.baseUrl == right.baseUrl &&
      left.revision == right.revision &&
      left.sourceType == right.sourceType &&
      left.configured == right.configured;

  Future<void> _saveSceneBinding({
    required SceneCatalogItem scene,
    required String providerProfileId,
    required String modelId,
  }) async {
    final sceneId = scene.sceneId;
    final current = _bindingMap[sceneId];
    if (current?.providerProfileId == providerProfileId &&
        current?.modelId == modelId) {
      return;
    }
    final clearing = providerProfileId.isEmpty && modelId.isEmpty;
    if (!clearing && !SceneModelConfigService.isValidModelName(modelId)) {
      showToast(context.l10n.sceneModelInvalidModelId, type: ToastType.error);
      return;
    }

    setState(() {
      _savingSceneIds = {..._savingSceneIds, sceneId};
    });
    try {
      final bindings = clearing
          ? await SceneModelConfigService.clearSceneModelBinding(sceneId)
          : await SceneModelConfigService.saveSceneModelBinding(
              sceneId: sceneId,
              providerProfileId: providerProfileId,
              modelId: modelId,
            );
      final catalog = await SceneModelConfigService.getSceneCatalog();
      if (!mounted) return;
      setState(() {
        _bindings = bindings;
        _catalog = catalog;
        _providerModelsByProfileId = _mergeBindingModels(
          providerModelsByProfileId: _providerModelsByProfileId,
          bindings: bindings,
        );
        if (!clearing) {
          _manuallyToggledScenes.add(sceneId);
          _expandedOptionalSceneIds.add(sceneId);
        }
      });
      if (sceneId == VoicePlaybackCoordinator.sceneVoiceId) {
        await VoicePlaybackCoordinator.instance.refreshConfiguration();
      }
      if (!mounted) return;
      showToast(
        clearing
            ? '已清除${_sceneDisplayName(sceneId)}的专用模型'
            : context.l10n.sceneModelBoundToast(_sceneDisplayName(sceneId), modelId),
        type: ToastType.success,
      );
    } catch (e) {
      if (!mounted) return;
      showToast(
        context.l10n.sceneModelSaveFailed(
          _sceneDisplayName(sceneId),
          e.toString(),
        ),
        type: ToastType.error,
      );
    } finally {
      if (mounted) {
        setState(() {
          _savingSceneIds = {..._savingSceneIds}..remove(sceneId);
        });
      }
    }
  }


  Widget _buildCard({required Widget child}) {
    return OmniSettingsCard(child: child);
  }

  String _selectionLabel(SceneCatalogItem scene) {
    final binding = _bindingMap[scene.sceneId];
    if (binding != null) {
      final profile = _profiles
          .where((item) => item.id == binding.providerProfileId)
          .firstOrNull;
      if (scene.bindingProfileMissing || profile == null) {
        return '服务商已失效 / ${binding.modelId}';
      }
      if (!profile.configured) {
        return '${profile.name} (未配置) / ${binding.modelId}';
      }
      return '${profile.name} / ${binding.modelId}';
    }

    if (scene.effectiveProviderProfileName.isNotEmpty &&
        scene.effectiveModel.isNotEmpty) {
      return '继承：${scene.effectiveProviderProfileName} / ${scene.effectiveModel}';
    }
    if (scene.effectiveModel.isNotEmpty) {
      return '默认：${scene.effectiveModel}';
    }
    if (scene.sceneId == SceneModelConfigService.sceneDispatchModel) {
      return '需配置';
    }
    if (scene.sceneId == SceneModelConfigService.sceneVisionUnderstanding ||
        scene.sceneId == SceneModelConfigService.sceneImageGenerate) {
      return '未启用（可选）';
    }
    if (scene.defaultModel.trim().isNotEmpty) {
      return '默认：${scene.defaultModel}';
    }
    return '未配置';
  }

  Widget _buildSceneLabel(SceneCatalogItem scene) {
    return Tooltip(
      message: _sceneTooltip(scene),
      triggerMode: TooltipTriggerMode.tap,
      showDuration: const Duration(seconds: 3),
      child: Row(
        children: [
          Flexible(
            child: Text(
              _sceneDisplayName(scene.sceneId),
              style: TextStyle(
                color: _primaryTextColor,
                fontSize: 15,
                fontWeight: FontWeight.w500,
              ),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 6),
          if (_isAgentScene(scene.sceneId)) ...[
            const AgentAvatarButton(size: 30, showEditBadge: true),
            const SizedBox(width: 6),
          ],
          Icon(LucideIcons.info, size: 15, color: _tertiaryTextColor),
        ],
      ),
    );
  }
  Future<void> _openModelProviderSetting(BuildContext context) async {
    await Navigator.of(context).push(
      OmniPageRoute<void>(
        builder: (_) => const ModelProviderSettingPage(),
        settings: const RouteSettings(name: '/home/model_provider_setting'),
      ),
    );
    if (!mounted) return;
    await _loadData(showLoading: false);
  }
  Future<void> _openAuthorizeSetting(BuildContext context) async {
    await Navigator.of(context).push(
      OmniPageRoute<void>(
        builder: (_) => const AuthorizeSettingPage(),
        settings: const RouteSettings(name: '/home/authorize_setting'),
      ),
    );
  }

  Future<void> openConversationModelSelector(
    BuildContext context,
    SceneCatalogItem scene,
  ) async {
    final binding = _bindingMap[scene.sceneId];
    final modelsByProfile = _modelsForScene(scene);
    final anchor = glassPopupAnchorFromContext(context) ??
        const Rect.fromLTWH(0, 0, 100, 100);
    final selection = await showGlassPopup<ConversationModelSelection>(
      context: context,
      sourceContext: context,
      anchor: anchor,
      child: Builder(
        builder: (popupContext) => LayoutBuilder(
          builder: (context, constraints) => ConversationModelSelectorContent(
            width: 320,
            maxHeight: constraints.maxHeight.clamp(220.0, 460.0),
            profiles: _profiles,
            providerModelsByProfileId: modelsByProfile,
            loadLiveProviders: false,
            currentSelection: binding == null
                ? null
                : ConversationModelSelection(
                    providerProfileId: binding.providerProfileId,
                    modelId: binding.modelId,
                  ),
            onSelect: (value) => Navigator.of(popupContext).pop(value),
          ),
        ),
      ),
    );
    if (!mounted || selection == null) return;
    await _saveSceneBinding(
      scene: scene,
      providerProfileId: selection.providerProfileId,
      modelId: selection.modelId,
    );
  }

  Widget _buildSceneSelectorField(
    SceneCatalogItem scene, {
    required bool isSaving,
  }) {
    final binding = _bindingMap[scene.sceneId];
    final modelsByProfile = _modelsForScene(scene);
    return Align(
      alignment: Alignment.centerRight,
      child: OmniSettingsDropdown<_SceneModelChoice>(
        dropdownKey: Key('scene-model-selector-${scene.sceneId}'),
        value: binding == null
            ? _defaultSceneModelChoice
            : (
                providerProfileId: binding.providerProfileId,
                modelId: binding.modelId,
              ),
        selectedLabel: _selectionLabel(scene),
        maxWidth: 164,
        menuMinWidth: 188,
        menuMaxWidth: 360,
        searchHint: context.trLegacy('搜索模型或服务商'),
        options: [
          if (binding != null)
            OmniSettingsOption(
              value: _defaultSceneModelChoice,
              label: scene.sceneId == SceneModelConfigService.sceneVlmOperationPrimary
                  ? '跟随主 Agent'
                  : '清除专用模型',
              subtitle: scene.sceneId == SceneModelConfigService.sceneVlmOperationPrimary
                  ? '移除独立绑定，使用主 Agent 的模型'
                  : '移除此场景的独立绑定',
            ),
          for (final profile in _profiles)
            if (profile.configured && !profile.needsPresetCredentials)
              for (final model
                  in modelsByProfile[profile.id] ??
                      const <ProviderModelOption>[])
                () {
                  final status = _evaluateModelCapability(
                    sceneId: scene.sceneId,
                    profile: profile,
                    model: model,
                  );
                  final isUnknown = status == ModelCapabilityStatus.unknown;
                  return OmniSettingsOption(
                    value: (providerProfileId: profile.id, modelId: model.id),
                    label: model.id,
                    leading: ProviderVendorIcon(
                      vendor: ModelVendorCatalog.resolve(
                        model.id,
                        ownedBy: model.ownedBy,
                        providerId: model.modelsDevProviderId,
                      ),
                      size: OmniMenuStyle.iconSize,
                      forceMonochrome: true,
                      monochromeColor: _primaryTextColor,
                    ),
                    trailingLabel: profile.name,
                    subtitle: isUnknown ? '能力待确认（前往服务商设置可手动标记）' : null,
                    searchText:
                        '${model.displayName}${isUnknown ? ' 待确认' : ''}',
                  );
                }(),
        ],
        onChanged: isSaving
            ? null
            : (selection) {
                if (selection == null || !mounted) return;
                unawaited(
                  _saveSceneBinding(
                    scene: scene,
                    providerProfileId: selection.providerProfileId,
                    modelId: selection.modelId,
                  ),
                );
              },
      ),
    );
  }

  List<Widget> _buildSceneStatusNotes(SceneCatalogItem scene) {
    final binding = _bindingMap[scene.sceneId];
    ModelCapabilityStatus? currentModelCapability;
    if (binding != null) {
      final profile = _profiles
          .where((p) => p.id == binding.providerProfileId)
          .firstOrNull;
      if (profile != null) {
        final allModels = _providerModelsByProfileId[profile.id] ??
            const <ProviderModelOption>[];
        final currentModel =
            allModels.where((m) => m.id == binding.modelId).firstOrNull;
        if (currentModel != null) {
          currentModelCapability = _evaluateModelCapability(
            sceneId: scene.sceneId,
            profile: profile,
            model: currentModel,
          );
        } else {
          currentModelCapability = ModelCapabilityStatus.unknown;
        }
      }
    }

    final isOptional =
        scene.sceneId == SceneModelConfigService.sceneVisionUnderstanding ||
            scene.sceneId == SceneModelConfigService.sceneImageGenerate;
    final isUnconfiguredOptional =
        isOptional && binding == null && scene.effectiveModel.isEmpty;

    return [
      if (isUnconfiguredOptional) ...[
        const SizedBox(height: 4),
        Text(
          '未配置时此功能不启用（可选场景）',
          style: TextStyle(
            color: _tertiaryTextColor,
            fontSize: 12,
          ),
        ),
      ] else if (currentModelCapability == ModelCapabilityStatus.incompatible) ...[
        const SizedBox(height: 4),
        Text(
          '当前模型不支持此场景，请重新选择模型或确认能力标记。',
          style: TextStyle(
            color: Theme.of(context).colorScheme.error,
            fontSize: 12,
          ),
        ),
      ] else if (currentModelCapability == ModelCapabilityStatus.unknown) ...[
        const SizedBox(height: 4),
        InkWell(
          onTap: () => _openModelProviderSetting(context),
          borderRadius: BorderRadius.circular(4),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 48),
            child: Row(
              children: [
                Icon(
                  LucideIcons.alertTriangle,
                  size: 14,
                  color: _secondaryTextColor,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    '当前模型能力待确认 · 点击前往服务商设置标记',
                    style: TextStyle(
                      color: Theme.of(context).colorScheme.primary,
                      fontSize: 12,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    ];
  }

  Widget _buildOptionalSceneItem(SceneCatalogItem scene) {
    final isExpanded = _isSceneExpanded(scene.sceneId);
    final hasBinding = _hasBinding(scene.sceneId);

    if (!isExpanded) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          key: Key('scene-optional-row-${scene.sceneId}'),
          children: [
            Expanded(flex: 4, child: _buildSceneLabel(scene)),
            const SizedBox(width: 10),
            Expanded(
              flex: 6,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  Flexible(
                    child: OmniSettingsStatusBadge(
                      label: hasBinding ? _selectionLabel(scene) : '未绑定',
                      color: hasBinding
                          ? context.omniPalette.brandPrimary
                          : _tertiaryTextColor,
                    ),
                  ),
                  const SizedBox(width: 4),
                  IconButton(
                    key: Key('scene-optional-toggle-${scene.sceneId}'),
                    icon: const Icon(LucideIcons.chevronDown, size: 16),
                    tooltip: '展开',
                    onPressed: () => _toggleOptionalScene(scene.sceneId),
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    }

    final isSaving = _isSavingScene(scene.sceneId);
    final isBusy = isSaving;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            key: Key('scene-optional-row-${scene.sceneId}'),
            children: [
              Expanded(flex: 4, child: _buildSceneLabel(scene)),
              const SizedBox(width: 10),
              Expanded(
                flex: 6,
                child: Row(
                  children: [
                    Expanded(
                      child: _buildSceneSelectorField(scene, isSaving: isBusy),
                    ),
                    const SizedBox(width: 4),
                    IconButton(
                      key: Key('scene-optional-toggle-${scene.sceneId}'),
                      icon: const Icon(LucideIcons.chevronUp, size: 16),
                      tooltip: '收起',
                      onPressed: () => _toggleOptionalScene(scene.sceneId),
                    ),
                  ],
                ),
              ),
              if (isBusy) ...[
                const SizedBox(width: 8),
                const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              ],
            ],
          ),
          ..._buildSceneStatusNotes(scene),
          if (scene.sceneId == SceneModelConfigService.sceneVoice) ...[
            const SizedBox(height: 12),
            _buildVoiceCard(),
          ],
        ],
      ),
    );
  }

  Widget _buildBasicSceneSection(SceneCatalogItem scene) {
    final isSaving = _isSavingScene(scene.sceneId);
    final isBusy = isSaving;
    final hasBinding = _hasBinding(scene.sceneId);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SettingsSectionTitle(
          label: '基础配置',
          subtitle: '主 Agent 用于对话与工具分流决策。',
        ),
        _buildCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(flex: 4, child: _buildSceneLabel(scene)),
                  const SizedBox(width: 10),
                  Expanded(
                    flex: 6,
                    child: _buildSceneSelectorField(scene, isSaving: isBusy),
                  ),
                  if (isBusy) ...[
                    const SizedBox(width: 8),
                    const SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: 8),
              Divider(
                height: 16,
                thickness: 0.6,
                color: context.omniPalette.borderSubtle.withValues(alpha: 0.9),
              ),
              Row(
                children: [
                  Icon(
                    LucideIcons.fileText,
                    size: 14,
                    color: _tertiaryTextColor,
                  ),
                  const SizedBox(width: 6),
                  Text(
                    '绑定摘要：',
                    style: TextStyle(
                      fontSize: 12,
                      color: _secondaryTextColor,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  Expanded(
                    child: Text(
                      hasBinding
                          ? _selectionLabel(scene)
                          : '未绑定专用模型 (使用系统默认)',
                      style: TextStyle(
                        fontSize: 12,
                        color: hasBinding
                            ? _primaryTextColor
                            : _tertiaryTextColor,
                      ),
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  const SizedBox(width: 8),
                  InkWell(
                    key: Key('scene-model-conversation-selector-${scene.sceneId}'),
                    onTap: () => openConversationModelSelector(context, scene),
                    borderRadius: BorderRadius.circular(4),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 6,
                        vertical: 4,
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(
                            LucideIcons.listFilter,
                            size: 13,
                            color: Theme.of(context).colorScheme.primary,
                          ),
                          const SizedBox(width: 4),
                          Text(
                            '完整选择器',
                            style: TextStyle(
                              fontSize: 12,
                              color: Theme.of(context).colorScheme.primary,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildOptionalSceneSection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SettingsSectionTitle(
          label: '可选能力',
          subtitle:
              '视觉、GUI、图像、语音、压缩与记忆等按需配置；已有绑定默认展开，未配置默认收起。',
        ),
        _buildCard(
          child: ListView.separated(
            physics: const NeverScrollableScrollPhysics(),
            shrinkWrap: true,
            itemCount: _orderedOptionalScenes.length,
            itemBuilder: (context, index) {
              final scene = _orderedOptionalScenes[index];
              return _buildOptionalSceneItem(scene);
            },
            separatorBuilder: (_, _) => Divider(
              height: 20,
              thickness: 0.6,
              color: context.omniPalette.borderSubtle.withValues(alpha: 0.9),
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildPrivilegedSection() {
    final isRootGranted = _rootSnapshot.granted;
    final rootStatusLabel = isRootGranted ? '已授权' : '未授权 / 未启用';
    final rootStatusColor = isRootGranted
        ? context.omniPalette.brandPrimary
        : _tertiaryTextColor;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SettingsSectionTitle(
          label: '特权与系统能力',
          subtitle: '展示 Root 与 LSPosed 已知状态与授权入口，页面初始化不触发 su 探测。',
        ),
        _buildCard(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Icon(
                      LucideIcons.shieldAlert,
                      size: 18,
                      color: _tertiaryTextColor,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'Root 访问权限',
                            style: TextStyle(
                              color: _primaryTextColor,
                              fontSize: 14,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '只读已知状态，不会在页面初始化时触发 su 探测。',
                            style: TextStyle(
                              color: _secondaryTextColor,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    OmniSettingsStatusBadge(
                      key: const Key('scene-root-status-badge'),
                      label: rootStatusLabel,
                      color: rootStatusColor,
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      key: const Key('scene-root-manage-button'),
                      onPressed: () => _openAuthorizeSetting(context),
                      child: const Text('前往授权'),
                    ),
                  ],
                ),
              ),
              Divider(
                height: 20,
                thickness: 0.6,
                color: context.omniPalette.borderSubtle.withValues(alpha: 0.9),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Icon(
                      LucideIcons.boxes,
                      size: 18,
                      color: _tertiaryTextColor,
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'LSPosed / Xposed 模块',
                            style: TextStyle(
                              color: _primaryTextColor,
                              fontSize: 14,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '展示已知管理状态；模块部署与作用域由会话或工具箱管理。',
                            style: TextStyle(
                              color: _secondaryTextColor,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 8),
                    OmniSettingsStatusBadge(
                      key: const Key('scene-lsposed-status-badge'),
                      label: '只读状态',
                      color: _secondaryTextColor,
                    ),
                    const SizedBox(width: 8),
                    TextButton(
                      key: const Key('scene-lsposed-manage-button'),
                      onPressed: () => _openAuthorizeSetting(context),
                      child: const Text('前往管理'),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildVoiceCard() {
    final secondary = _secondaryTextColor;
    final coordinator = VoicePlaybackCoordinator.instance;
    return AnimatedBuilder(
      animation: coordinator,
      builder: (context, _) {
        final binding = _bindingMap[SceneModelConfigService.sceneVoice];
        final isCustom = _voiceConfig.isCustomCurl;
        final hasCustomCmd = _voiceConfig.hasCustomCurlCommand;

        final ModelProviderProfileSummary? profile = binding == null
            ? null
            : _profiles
                .where((p) => p.id == binding.providerProfileId)
                .firstOrNull;

        final bool isBound;
        final String statusLabel;
        final Color statusColor;

        if (isCustom) {
          isBound = hasCustomCmd;
          if (hasCustomCmd) {
            statusLabel = '自定义命令就绪';
            statusColor = context.omniPalette.brandPrimary;
          } else {
            statusLabel = '未配置命令';
            statusColor = _tertiaryTextColor;
          }
        } else {
          if (binding == null) {
            isBound = false;
            statusLabel = '未绑定模型';
            statusColor = _tertiaryTextColor;
          } else if (profile == null) {
            isBound = false;
            statusLabel = '服务商已失效';
            statusColor = _tertiaryTextColor;
          } else if (!profile.configured) {
            isBound = false;
            statusLabel = '服务商未配置';
            statusColor = _tertiaryTextColor;
          } else {
            final allModels = _providerModelsByProfileId[profile.id] ??
                const <ProviderModelOption>[];
            final boundModel = allModels
                    .where((m) => m.id == binding.modelId)
                    .firstOrNull ??
                ProviderModelOption(
                  id: binding.modelId,
                  displayName: binding.modelId,
                );
            final capability = _evaluateModelCapability(
              sceneId: SceneModelConfigService.sceneVoice,
              profile: profile,
              model: boundModel,
            );

            if (capability == ModelCapabilityStatus.incompatible) {
              isBound = false;
              statusLabel = '模型不兼容';
              statusColor = _tertiaryTextColor;
            } else if (capability == ModelCapabilityStatus.unknown) {
              isBound = true;
              statusLabel = '能力待确认';
              statusColor = _secondaryTextColor;
            } else {
              isBound = true;
              statusLabel = '已就绪';
              statusColor = context.omniPalette.brandPrimary;
            }
          }
        }

        final String descriptionText;
        if (isBound) {
          if (_voiceConfig.autoPlay) {
            descriptionText =
                context.trLegacy('已开启自动朗读，助手回复会自动播放语音；点击消息旁按钮可重新播放。');
          } else {
            descriptionText =
                context.trLegacy('自动朗读已关闭；助手回复旁将显示播放按钮，支持手动点击播放。');
          }
        } else {
          if (isCustom) {
            descriptionText =
                context.trLegacy('当前为自定义 curl 模式，请先配置有效的语音生成命令。');
          } else {
            descriptionText =
                context.trLegacy('请先在上方「语音合成」中绑定支持语音的模型，配置后即可播放回复。');
          }
        }

        return _buildCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    LucideIcons.volume2,
                    size: 18,
                    color: _primaryTextColor.withValues(alpha: 0.78),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    context.trLegacy('语音回复'),
                    style: TextStyle(
                      color: _primaryTextColor,
                      fontSize: 15,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(width: 10),
                  OmniSettingsStatusBadge(
                    label: statusLabel,
                    color: statusColor,
                  ),
                  const Spacer(),
                  OmniSettingsSwitch(
                    key: const Key('scene-voice-autoplay-switch'),
                    value: _voiceConfig.autoPlay,
                    enabled: isBound,
                    onToggle: (value) => _saveVoiceConfig(
                      _voiceConfig.copyWith(autoPlay: value),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                descriptionText,
                style: TextStyle(color: secondary, fontSize: 12, height: 1.48),
              ),
              const SizedBox(height: 8),
              Text(
                context.trLegacy(
                  '声音：${_voiceConfig.voiceId}  ·  风格：${_voiceConfig.stylePreset}',
                ),
                style: TextStyle(color: secondary, fontSize: 12),
              ),
            ],
          ),
        );
      },
    );
  }


  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _pageBackground,
      appBar: CommonAppBar(
        title: context.l10n.settingsSceneModelTitle,
        primary: true,
        actions: [
          IconButton(
            key: const Key('scene-model-refresh-button'),
            icon: _isRefreshingModels
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(LucideIcons.refreshCw, size: 18),
            tooltip: '刷新模型列表',
            onPressed: _isRefreshingModels
                ? null
                : _refreshProviderModels,
          ),
          IconButton(
            icon: const Icon(LucideIcons.slidersHorizontal, size: 18),
            tooltip: '服务商设置',
            onPressed: () => _openModelProviderSetting(context),
          ),
        ],
      ),
      body: OmniContentWidth(
        child: SafeArea(
          top: false,
          bottom: false,
          child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : ListView(
                  padding: edgeToEdgeScrollPadding(
                    context,
                    const EdgeInsets.fromLTRB(22, 12, 22, 24),
                  ),
                  children: [
                    if (_providerErrors.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: Row(
                          children: [
                            Icon(
                              LucideIcons.alertCircle,
                              size: 16,
                              color: _tertiaryTextColor,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                '部分服务商远程刷新失败，已使用本地缓存。',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: _secondaryTextColor,
                                ),
                              ),
                            ),
                            ConstrainedBox(
                              constraints: const BoxConstraints(
                                minHeight: 48,
                                minWidth: 48,
                              ),
                              child: InkWell(
                                key: const Key(
                                  'scene-model-retry-refresh-button',
                                ),
                                onTap: _isRefreshingModels
                                    ? null
                                    : _refreshProviderModels,
                                child: Center(
                                  child: Text(
                                    '重试',
                                    style: TextStyle(
                                      fontSize: 12,
                                      color:
                                          Theme.of(context).colorScheme.primary,
                                      fontWeight: FontWeight.w500,
                                    ),
                                  ),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    if (_orderedBasicScenes.isNotEmpty) ...[
                      _buildBasicSceneSection(_orderedBasicScenes.first),
                      const SizedBox(height: 16),
                    ],
                    if (_orderedOptionalScenes.isNotEmpty) ...[
                      _buildOptionalSceneSection(),
                      const SizedBox(height: 16),
                    ],
                    _buildPrivilegedSection(),
                    if (_orderedBasicScenes.isEmpty &&
                        _orderedOptionalScenes.isEmpty)
                      _buildCard(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          child: Text(
                            context.l10n.sceneModelNoScenes,
                            style: TextStyle(
                              color: _secondaryTextColor,
                              fontSize: 12,
                              fontFamily: 'PingFang SC',
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
        ),
      ),
    );
  }
}
