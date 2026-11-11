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
  @override
  void initState() {
    super.initState();
    _loadData();
  }

  List<SceneCatalogItem> get _orderedBasicScenes {
    final map = {
      for (final item in _catalog)
        if (item.sceneId != 'scene.compactor.context') item.sceneId: item,
    };
    final result = <SceneCatalogItem>[];
    for (final id in SceneModelConfigService.basicSceneIds) {
      final item = map.remove(id);
      if (item != null) {
        result.add(item);
      }
    }
    return result;
  }

  List<SceneCatalogItem> get _orderedAdvancedScenes {
    final map = {
      for (final item in _catalog)
        if (item.sceneId != 'scene.compactor.context') item.sceneId: item,
    };
    final result = <SceneCatalogItem>[];
    for (final id in SceneModelConfigService.advancedSceneIds) {
      final item = map.remove(id);
      if (item != null) {
        result.add(item);
      }
    }
    for (final entry in map.entries) {
      if (!SceneModelConfigService.basicSceneIds.contains(entry.key)) {
        result.add(entry.value);
      }
    }
    return result;
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
    if (!await waitForPageReady() || !mounted) return;
    if (showLoading && mounted) {
      setState(() => _isLoading = true);
    }
    try {
      final results = await Future.wait<dynamic>([
        SceneModelConfigService.getSceneCatalog(),
        SceneModelConfigService.getSceneModelBindings(),
        ModelProviderConfigService.listProfiles(),
        SceneModelConfigService.getSceneVoiceConfig(),
      ]);
      if (!await waitForPageReady() || !mounted) return;

      final catalog = results[0] as List<SceneCatalogItem>;
      final bindings = results[1] as List<SceneModelBindingEntry>;
      final profilesPayload = results[2] as ModelProviderProfilesPayload;
      final voiceConfig = results[3] as SceneVoiceConfig;
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

      if (!await waitForPageReady() || !mounted) return;
      setState(() {
        _catalog = catalog;
        _bindings = bindings;
        _profiles = profilesPayload.profiles;
        _voiceConfig = voiceConfig;
        _providerModelsByProfileId = enriched;
        _providerErrors = <String, String>{};
      });
      _scheduleMetadataRefresh(
        profiles: profilesPayload.profiles,
        providerModelsByProfileId: enriched,
      );
    } catch (_) {
      if (!await waitForPageReady() || !mounted) return;
      showToast(context.l10n.sceneModelLoadFailed, type: ToastType.error);
    } finally {
      if (showLoading && await waitForPageReady() && mounted) {
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
    if (!await waitForPageReady() || !mounted) return;
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

      if (!await waitForPageReady() ||
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

  Widget _buildDefaultSceneRow(SceneCatalogItem scene) {
    final isSaving = _isSavingScene(scene.sceneId);
    final isBusy = isSaving;
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

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
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
        ],
      ),
    );
  }

  Widget _buildSceneRow(SceneCatalogItem scene) {
    return _buildDefaultSceneRow(scene);
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
                      SettingsSectionTitle(
                        label: '基础能力',
                        subtitle:
                            '主 Agent 用于对话与工具调用；视觉、生图、语音按需配置，GUI 未单独绑定时跟随主 Agent。',
                      ),
                      _buildCard(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              context.trLegacy(
                                '点击右侧按钮选择模型，也可按模型名称、ID 或服务商搜索。',
                              ),
                              style: TextStyle(
                                color: _secondaryTextColor,
                                fontSize: 12,
                                height: 1.5,
                                fontFamily: 'PingFang SC',
                              ),
                            ),
                            const SizedBox(height: 12),
                            ListView.separated(
                              physics: const NeverScrollableScrollPhysics(),
                              shrinkWrap: true,
                              itemCount: _orderedBasicScenes.length,
                              itemBuilder: (context, index) {
                                final scene = _orderedBasicScenes[index];
                                return _buildSceneRow(scene);
                              },
                              separatorBuilder: (_, _) => Divider(
                                height: 20,
                                thickness: 0.6,
                                color: context.omniPalette.borderSubtle
                                    .withValues(alpha: 0.9),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 16),
                      SettingsSectionTitle(
                        label: context.trLegacy('语音能力'),
                        subtitle:
                            context.trLegacy('统一由 ACP 语音场景提供播放和自动朗读。'),
                      ),
                      _buildVoiceCard(),
                      const SizedBox(height: 16),
                    ],
                    if (_orderedAdvancedScenes.isNotEmpty) ...[
                      SettingsSectionTitle(
                        label: '高级配置',
                        subtitle:
                            '按需配置上下文压缩与记忆维护；未配置嵌入模型时使用词法检索。',
                      ),
                      _buildCard(
                        child: ListView.separated(
                          physics: const NeverScrollableScrollPhysics(),
                          shrinkWrap: true,
                          itemCount: _orderedAdvancedScenes.length,
                          itemBuilder: (context, index) {
                            final scene = _orderedAdvancedScenes[index];
                            return _buildSceneRow(scene);
                          },
                          separatorBuilder: (_, _) => Divider(
                            height: 20,
                            thickness: 0.6,
                            color: context.omniPalette.borderSubtle
                                .withValues(alpha: 0.9),
                          ),
                        ),
                      ),
                    ],
                    if (_orderedBasicScenes.isEmpty &&
                        _orderedAdvancedScenes.isEmpty)
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
