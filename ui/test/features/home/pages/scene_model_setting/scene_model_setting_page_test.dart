import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/remote_codex_setting_page.dart';
import 'package:ui/features/home/pages/scene_model_setting/scene_model_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:ui/widgets/omni_menu.dart';
import 'package:ui/widgets/provider_vendor_icon.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

const _modelsDevCatalogJson = '''
{
  "custom": {
    "id": "custom",
    "name": "Custom",
    "models": {
      "scene-model": {
        "id": "scene-model",
        "name": "Scene Model",
        "limit": {"context": 128000}
      }
    }
  }
}
''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  Widget buildTestApp(Widget child, {Locale locale = const Locale('zh')}) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: locale,
      home: DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child),
    );
  }

  Future<void> pumpSceneSettings(WidgetTester tester) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }
  Future<void> expandOptionalScene(
    WidgetTester tester,
    String sceneId,
  ) async {
    final toggle = find.byKey(Key('scene-optional-toggle-$sceneId'));
    if (toggle.evaluate().isNotEmpty) {
      final iconFinder = find.descendant(
        of: toggle,
        matching: find.byType(Icon),
      );
      if (iconFinder.evaluate().isNotEmpty) {
        final icon = tester.widget<Icon>(iconFinder.first);
        if (icon.icon == LucideIcons.chevronDown) {
          await tester.ensureVisible(toggle);
          await tester.tap(toggle);
          await tester.pumpAndSettle();
        }
      }
    }
  }

  late Map<String, dynamic> savedOperationConfig;
  late Map<String, dynamic> codexReadConfig;
  late Map<String, dynamic>? savedCodexConfig;
  late int codexWriteCount;
  late bool providerConfigured;
  late String providerBaseUrl;
  late int providerRevision;
  late String providerSourceType;
  late bool providerReadOnly;
  late bool providerReady;
  late bool includeSecondProvider;
  late String secondProviderProtocolType;
  late List<Map<String, dynamic>> sceneCatalog;
  late int providerFetchCount;
  late List<Map<String, dynamic>> sceneBindings;
  late List<Map<String, dynamic>> providerFetchResponse;
  late List<Map<String, dynamic>> secondProviderFetchResponse;
  late Completer<List<Map<String, dynamic>>>? providerFetchCompleter;
  late Object? providerFetchError;
  late Map<dynamic, dynamic>? lastProviderFetchArguments;
  late List<Map<dynamic, dynamic>> providerFetchArguments;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    codexWriteCount = 0;
    savedCodexConfig = null;
    codexReadConfig = <String, dynamic>{
      'remoteEnabled': true,
      'remoteBridgeUrl': 'ws://192.168.1.2:17321/codex',
      'remoteBridgeToken': 'test-token',
      'remoteCwd': '/Users/name/code/project',
    };
    ModelsDevCatalogService.setCatalogForTesting(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    providerConfigured = true;
    providerBaseUrl = 'https://example.com/v1';
    providerRevision = 1;
    providerSourceType = 'custom';
    providerReadOnly = false;
    providerReady = true;
    includeSecondProvider = false;
    secondProviderProtocolType = 'openai_compatible';
    providerFetchCount = 0;
    sceneBindings = <Map<String, dynamic>>[];
    providerFetchResponse = <Map<String, dynamic>>[];
    secondProviderFetchResponse = <Map<String, dynamic>>[];
    providerFetchCompleter = null;
    providerFetchError = null;
    lastProviderFetchArguments = null;
    providerFetchArguments = <Map<dynamic, dynamic>>[];
    savedOperationConfig = <String, dynamic>{'useOfficialService': true};
    sceneCatalog = <Map<String, dynamic>>[
      <String, dynamic>{
        'sceneId': 'scene.dispatch.model',
        'description': '负责主对话、任务理解与工具分流决策',
        'defaultModel': 'gpt-4o',
        'effectiveModel': 'gpt-4o',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.vision.understanding',
        'description': '负责图片解析与文字识别',
        'defaultModel': 'qwen-vl-max',
        'effectiveModel': 'qwen-vl-max',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.vlm.operation.primary',
        'description': '负责 Android GUI 观察与动作决策',
        'defaultModel': 'qwen3-vl-plus',
        'effectiveModel': 'qwen3-vl-plus',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.image.generate',
        'description': '负责文生图调用',
        'defaultModel': 'dall-e-3',
        'effectiveModel': 'dall-e-3',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.voice',
        'description': '负责助手回复语音合成',
        'defaultModel': 'gpt-4o-audio-preview',
        'effectiveModel': 'gpt-4o-audio-preview',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.compactor.context.chat',
        'description': '负责聊天历史压缩总结',
        'defaultModel': 'chat-compactor-model',
        'effectiveModel': 'chat-compactor-model',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.memory.embedding',
        'description': '负责 workspace 记忆向量检索的嵌入模型',
        'defaultModel': 'embedding-default',
        'effectiveModel': 'embedding-default',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
      <String, dynamic>{
        'sceneId': 'scene.memory.rollup',
        'description': '负责夜间记忆整理策略与事实提炼',
        'defaultModel': 'rollup-default',
        'effectiveModel': 'rollup-default',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
    ];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSceneModelCatalog':
              return sceneCatalog;
            case 'getSceneModelBindings':
              return sceneBindings;
            case 'saveSceneModelBinding':
              final binding = Map<String, dynamic>.from(call.arguments as Map);
              sceneBindings.removeWhere(
                (item) => item['sceneId'] == binding['sceneId'],
              );
              sceneBindings.add(binding);
              return sceneBindings;
            case 'clearSceneModelBinding':
              final arguments = Map<String, dynamic>.from(
                call.arguments as Map,
              );
              sceneBindings.removeWhere(
                (item) => item['sceneId'] == arguments['sceneId'],
              );
              return sceneBindings;
            case 'listModelProviderProfiles':
              return <String, dynamic>{
                'profiles': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'provider-1',
                    'name': 'Provider One',
                    'baseUrl': providerBaseUrl,
                    'apiKey': 'secret',
                    'hasApiKey': true,
                    'configured': providerConfigured,
                    'sourceType': providerSourceType,
                    'readOnly': providerReadOnly,
                    'ready': providerReady,
                    'revision': providerRevision,
                    'protocolType': 'openai_compatible',
                  },
                  if (includeSecondProvider)
                    <String, dynamic>{
                      'id': 'provider-2',
                      'name': 'Provider Two',
                      'baseUrl': 'https://example2.com/v1',
                      'configured': true,
                      'sourceType': 'custom',
                      'readOnly': false,
                      'ready': true,
                      'revision': 1,
                      'protocolType': secondProviderProtocolType,
                    },
                ],
                'editingProfileId': 'provider-1',
              };
            case 'fetchProviderModels':
              providerFetchCount += 1;
              lastProviderFetchArguments = call.arguments as Map?;
              final arguments = (call.arguments as Map?) ?? const {};
              providerFetchArguments.add(Map<dynamic, dynamic>.from(arguments));
              final error = providerFetchError;
              if (error != null) {
                throw PlatformException(
                  code: 'FETCH_FAILED',
                  message: error.toString(),
                );
              }
              final pending = providerFetchCompleter;
              if (pending != null) return pending.future;
              if (arguments['profileId'] == 'provider-2') {
                return secondProviderFetchResponse;
              }
              return providerFetchResponse;
            case 'getSceneOperationConfig':
              return savedOperationConfig;
            case 'saveSceneOperationConfig':
              savedOperationConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              return savedOperationConfig;
            case 'getSceneVoiceConfig':
              return <String, dynamic>{
                'isCustomCurl': false,
                'customCurlCommand': '',
              };
            case 'getModelCapabilityOverrides':
              return <String, dynamic>{};
            case 'syncProviderCatalogMetadata':
              return true;
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          switch (call.method) {
            case 'config/remote/read':
              return codexReadConfig;
            case 'config/remote/write':
              savedCodexConfig = Map<String, dynamic>.from(
                (call.arguments as Map).cast<String, dynamic>(),
              );
              codexWriteCount += 1;
              return <String, dynamic>{...savedCodexConfig!};
            default:
              return null;
          }
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/SpecialPermissionEvent'),
      (call) async {
        if (call.method == 'getRootStatus') {
          return {'enabled': false, 'status': 'DISABLED'};
        }
        return null;
      },
    );
  });

  tearDown(() async {
    final pending = providerFetchCompleter;
    if (pending != null && !pending.isCompleted) {
      pending.complete(<Map<String, dynamic>>[]);
    }
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/SpecialPermissionEvent'),
      null,
    );
    ModelsDevCatalogService.resetForTesting();
  });

  testWidgets('scene page does not wait for metadata refresh', (tester) async {
    tester.view.physicalSize = const Size(1080, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    providerConfigured = false;
    await seedManualModels(
      profileId: 'provider-1',
      apiBase: 'https://example.com/v1',
      profileRevision: providerRevision,
      models: const [
        ProviderModelOption(id: 'scene-model', displayName: 'scene-model'),
      ],
    );
    final loader = Completer<ModelsDevCatalog>();
    const pathProvider = MethodChannel('plugins.flutter.io/path_provider');
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(pathProvider, (_) async => null);
    addTearDown(() {
      messenger.setMockMethodCallHandler(pathProvider, null);
      if (!loader.isCompleted) {
        loader.complete(const ModelsDevCatalog(providers: {}));
      }
    });
    var loadCount = 0;
    ModelsDevCatalogService.setCatalogLoaderForTesting(() {
      loadCount += 1;
      return loader.future;
    });

    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    for (var index = 0; index < 6; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }

    expect(find.byType(ListView), findsWidgets);
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(loadCount, 1);

    loader.complete(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    for (var index = 0; index < 4; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'opening scene settings uses local cache without fetching provider models',
    (tester) async {
      providerConfigured = true;
      providerFetchError = StateError('offline');
      await seedManualModels(
        profileId: 'provider-1',
        apiBase: providerBaseUrl,
        profileRevision: providerRevision,
        models: const <ProviderModelOption>[
          ProviderModelOption(id: 'cached-model', displayName: 'Cached model'),
        ],
      );

      await pumpSceneSettings(tester);

      // 打开页面仅读取本地缓存，零远程 fetch 副作用，且不展示失败重试控件
      expect(providerFetchCount, 0);
      expect(
        find.byKey(const Key('scene-model-retry-refresh-button')),
        findsNothing,
      );
      expect(
        find.byKey(const Key('scene-model-refresh-button')),
        findsOneWidget,
      );

      await expandOptionalScene(tester, 'scene.compactor.context.chat');
      await tester.tap(
        find.byKey(
          const Key('scene-model-selector-scene.compactor.context.chat'),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('cached-model'), findsOneWidget);
    },
  );

  testWidgets(
    'manual refresh failure retains cached models and retry discovers fresh model',
    (tester) async {
      providerFetchError = StateError('offline');
      await seedManualModels(
        profileId: 'provider-1',
        apiBase: providerBaseUrl,
        profileRevision: providerRevision,
        models: const <ProviderModelOption>[
          ProviderModelOption(id: 'cached-model', displayName: 'Cached model'),
        ],
      );

      await pumpSceneSettings(tester);
      expect(providerFetchCount, 0);

      // 主动点击顶部刷新按钮
      await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
      for (var attempt = 0; attempt < 10; attempt++) {
        await tester.pump();
      }

      expect(providerFetchCount, 1);
      expect(lastProviderFetchArguments?['apiBase'], providerBaseUrl);
      expect(lastProviderFetchArguments?['profileId'], 'provider-1');
      // 错误控件出现，但不钉死具体文案
      expect(
        find.byKey(const Key('scene-model-retry-refresh-button')),
        findsOneWidget,
      );

      // 主动刷新失败仍能选择 cached 模型
      await expandOptionalScene(tester, 'scene.compactor.context.chat');
      final selector = find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      );
      await tester.tap(selector);
      await tester.pumpAndSettle();
      expect(find.text('cached-model'), findsOneWidget);
      await tester.tapAt(const Offset(2, 2));
      await tester.pumpAndSettle();

      // 修改 mock 到成功，点击真实 retry 按钮
      providerFetchError = null;
      providerFetchResponse = <Map<String, dynamic>>[
        <String, dynamic>{'id': 'fresh-model', 'displayName': 'Fresh model'},
      ];

      await tester.tap(
        find.byKey(const Key('scene-model-retry-refresh-button')),
      );
      await tester.pumpAndSettle();

      expect(providerFetchCount, 2);
      // 错误控件消失
      expect(
        find.byKey(const Key('scene-model-retry-refresh-button')),
        findsNothing,
      );

      // 刷新后 new 候选出现，验证可选择并保存实际 binding
      await tester.tap(selector);
      await tester.pumpAndSettle();
      expect(find.text('fresh-model'), findsOneWidget);
      await tester.tap(find.text('fresh-model'));
      await tester.pumpAndSettle();

      expect(
        sceneBindings.where(
          (item) => item['sceneId'] == 'scene.compactor.context.chat',
        ).single,
        containsPair('modelId', 'fresh-model'),
      );
      expect(
        sceneBindings.where(
          (item) => item['sceneId'] == 'scene.compactor.context.chat',
        ).single,
        containsPair('providerProfileId', 'provider-1'),
      );
    },
  );

  testWidgets('changed provider revision cannot apply an old fetch result', (
    tester,
  ) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);
    expect(providerFetchCount, 0);

    await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
    await tester.pump();
    expect(providerFetchCount, 1);

    providerBaseUrl = 'https://replacement.example.com/v1';
    providerRevision = 2;
    pending.complete(<Map<String, dynamic>>[
      <String, dynamic>{'id': 'stale-model', 'displayName': 'Stale model'},
    ]);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
    }

    await expandOptionalScene(tester, 'scene.compactor.context.chat');
    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('stale-model'), findsNothing);
  });

  testWidgets('refresh disposal ignores completion', (tester) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);
    expect(providerFetchCount, 0);

    await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
    await tester.pump();
    expect(providerFetchCount, 1);

    await tester.pumpWidget(const MaterialApp(home: SizedBox.shrink()));
    pending.complete(<Map<String, dynamic>>[
      <String, dynamic>{'id': 'late-model', 'displayName': 'Late model'},
    ]);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 20));
    expect(providerFetchCount, 1);
    expect(tester.takeException(), isNull);
  });

  testWidgets('refresh errors do not leak endpoint details', (
    tester,
  ) async {
    providerFetchError =
        'socket failed at https://user:token@example.com/private?key=secret';
    await pumpSceneSettings(tester);
    expect(providerFetchCount, 0);

    await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
    }

    expect(find.textContaining('user:token'), findsNothing);
    expect(find.textContaining('/private'), findsNothing);
    expect(find.textContaining('key=secret'), findsNothing);
    expect(
      find.byKey(const Key('scene-model-retry-refresh-button')),
      findsOneWidget,
    );
  });


  testWidgets(
    'GUI switches providers through the shared scene binding and restores it',
    (tester) async {
      includeSecondProvider = true;
      providerFetchResponse = <Map<String, dynamic>>[
        {'id': 'custom-gui-model'},
      ];
      secondProviderFetchResponse = <Map<String, dynamic>>[
        {'id': 'provider-2-gui-model'},
      ];
      sceneBindings = <Map<String, dynamic>>[
        {
          'sceneId': 'scene.vlm.operation.primary',
          'providerProfileId': 'provider-2',
          'modelId': 'provider-2-gui-model',
        },
      ];
      await pumpSceneSettings(tester);
      await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(
          const Key('scene-model-selector-scene.vlm.operation.primary'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('custom-gui-model'));
      await tester.pumpAndSettle();
      expect(
        sceneBindings.single,
        containsPair('providerProfileId', 'provider-1'),
      );
      expect(sceneBindings.single, containsPair('modelId', 'custom-gui-model'));
      await tester.pumpWidget(const SizedBox.shrink());
      await pumpSceneSettings(tester);
      await tester.pumpAndSettle();
      expect(find.text('Provider One / custom-gui-model'), findsOneWidget);
      await tester.tap(
        find.byKey(
          const Key('scene-model-selector-scene.vlm.operation.primary'),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('跟随主 Agent'));
      await tester.pumpAndSettle();
      expect(sceneBindings, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      await pumpSceneSettings(tester);
      expect(sceneBindings, isEmpty);
    },
  );

  testWidgets(
    'model IDs and icons preserve channel selection without a restore entry',
    (tester) async {
      includeSecondProvider = true;
      providerFetchResponse = [
        {'id': 'gpt-5.4', 'displayName': 'Custom Choice'},
      ];
      secondProviderFetchResponse = [
        {'id': 'gpt-5.4', 'displayName': 'Second Choice'},
      ];
      await pumpSceneSettings(tester);
      await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
      await tester.pumpAndSettle();
      final fetchCountBeforeOpen = providerFetchCount;
      await expandOptionalScene(tester, 'scene.compactor.context.chat');
      final selector = find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      );
      await tester.tap(selector);
      await tester.pumpAndSettle();
      final dropdown = tester.widget<OmniSettingsDropdown>(
        find.ancestor(
          of: selector,
          matching: find.byWidgetPredicate(
            (widget) => widget is OmniSettingsDropdown,
          ),
        ),
      );
      expect(dropdown.maxWidth, 164);
      expect(dropdown.menuMinWidth, 188);
      expect(find.byType(OmniSettingsSearchField), findsOneWidget);
      expect(find.text('gpt-5.4'), findsNWidgets(2));
      expect(find.textContaining('恢复默认'), findsNothing);
      expect(
        tester
            .widgetList<ProviderVendorIcon>(find.byType(ProviderVendorIcon))
            .map((icon) => icon.vendor?.key),
        everyElement('openai'),
      );
      // Display names remain searchable, but the visible primary label is ID.
      await tester.enterText(find.byType(TextField), 'Second');
      await tester.pumpAndSettle();
      expect(find.text('Custom Choice'), findsNothing);
      expect(find.text('Second Choice'), findsNothing);
      await tester.tap(find.text('gpt-5.4'));
      await tester.pumpAndSettle();
      expect(sceneBindings.single, containsPair('modelId', 'gpt-5.4'));
      expect(
        sceneBindings.single,
        containsPair('providerProfileId', 'provider-2'),
      );
      expect(providerFetchCount, fetchCountBeforeOpen);

      await tester.tap(selector);
      await tester.pumpAndSettle();
      final selectedRow = tester.widget<OmniMenuItem>(
        find.ancestor(
          of: find.text('Provider Two'),
          matching: find.byType(OmniMenuItem),
        ),
      );
      expect(selectedRow.selected, isTrue);
      expect(find.textContaining('恢复默认'), findsNothing);
      // Channel text also remains searchable and determines the saved binding.
      await tester.enterText(find.byType(TextField), 'Provider One');
      await tester.pumpAndSettle();
      await tester.tap(find.text('gpt-5.4'));
      await tester.pumpAndSettle();
      expect(sceneBindings.single, containsPair('modelId', 'gpt-5.4'));
      expect(
        sceneBindings.single,
        containsPair('providerProfileId', 'provider-1'),
      );
      expect(find.byType(OmniSettingsSearchField), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'startup-style dropdown fits a phone keyboard and cancels without saving',
    (tester) async {
      providerFetchResponse = [
        {'id': 'custom-model', 'displayName': 'Custom Choice'},
      ];
      await pumpSceneSettings(tester);
      await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
      await tester.pumpAndSettle();
      tester.view.physicalSize = const Size(360, 800);
      await tester.pumpAndSettle();
      await expandOptionalScene(tester, 'scene.compactor.context.chat');
      final selector = find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      );
      await tester.ensureVisible(selector);
      await tester.tap(selector);
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'custom');
      tester.view.viewInsets = const FakeViewPadding(bottom: 300);
      addTearDown(tester.view.resetViewInsets);
      await tester.pumpAndSettle();
      expect(find.text('custom-model'), findsOneWidget);
      final bounds = tester.getRect(find.byType(OmniMenuSurface));
      expect(bounds.left, greaterThanOrEqualTo(0));
      expect(bounds.right, lessThanOrEqualTo(360));
      expect(bounds.bottom, lessThanOrEqualTo(500));
      expect(tester.takeException(), isNull);
      await tester.tapAt(const Offset(2, 2));
      await tester.pumpAndSettle();
      expect(find.byType(OmniSettingsSearchField), findsNothing);
      expect(sceneBindings, isEmpty);
    },
  );

  testWidgets('an empty model catalog still opens a dismissible list', (
    tester,
  ) async {
    providerConfigured = false;
    await pumpSceneSettings(tester);
    await expandOptionalScene(tester, 'scene.compactor.context.chat');
    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('没有匹配的选项'), findsOneWidget);
    expect(find.byType(OmniMenuItem), findsNothing);
    expect(find.textContaining('恢复默认'), findsNothing);
    await tester.tapAt(const Offset(2, 2));
    await tester.pumpAndSettle();
    expect(find.byType(OmniMenuSurface), findsNothing);
    expect(sceneBindings, isEmpty);
    expect(tester.takeException(), isNull);
  });

  testWidgets('remote bridge setting autosaves only bridge fields', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1080, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp(const RemoteCodexSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('远程 PC Bridge'), findsWidgets);
    expect(find.textContaining('本地终端环境 Codex'), findsNothing);
    expect(find.textContaining('自定义 API'), findsNothing);

    final urlField = find.byKey(
      const Key('codex-config-remote-bridge-url-field'),
    );
    final cwdField = find.byKey(const Key('codex-config-remote-cwd-field'));
    await tester.enterText(urlField, 'ws://10.0.0.2:17321/codex');
    await tester.enterText(cwdField, '/Users/new/project');

    expect(codexWriteCount, 0);
    await tester.pump(const Duration(milliseconds: 750));
    await tester.pump();

    expect(codexWriteCount, 1);
    expect(savedCodexConfig, <String, dynamic>{
      'remoteEnabled': true,
      'remoteBridgeUrl': 'ws://10.0.0.2:17321/codex',
      'remoteBridgeToken': 'test-token',
      'remoteCwd': '/Users/new/project',
    });
    expect(find.text('已自动保存。'), findsOneWidget);
  });
  testWidgets('capability filtering excludes incompatible models and preserves unknown models', (
    tester,
  ) async {
    providerFetchResponse = [
      {
        'id': 'embed-only-model',
        'displayName': 'Embedding Only',
        'outputModalities': ['embedding'],
      },
      {
        'id': 'text-chat-model',
        'displayName': 'Text Chat',
        'inputModalities': ['text'],
        'outputModalities': ['text'],
      },
      {
        'id': 'vision-chat-model',
        'displayName': 'Vision Chat',
        'inputModalities': ['text', 'image'],
        'outputModalities': ['text'],
      },
      {
        'id': 'unknown-capability-model',
        'displayName': 'Unknown Modalities',
      },
    ];

    await pumpSceneSettings(tester);
    await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
    await tester.pumpAndSettle();

    // 上下文压缩场景：embedding 模型被过滤，文本模型与未知能力模型可选。
    await expandOptionalScene(tester, 'scene.compactor.context.chat');
    final compactorSelector = find.byKey(
      const Key('scene-model-selector-scene.compactor.context.chat'),
    );
    await tester.tap(compactorSelector);
    await tester.pumpAndSettle();
    expect(find.text('embed-only-model'), findsNothing);
    expect(find.text('text-chat-model'), findsOneWidget);
    expect(find.text('unknown-capability-model'), findsOneWidget);
    await tester.tapAt(const Offset(2, 2));
    await tester.pumpAndSettle();
  });

  testWidgets('image generation filters out unsupported provider and requires image output', (
    tester,
  ) async {
    includeSecondProvider = true;
    secondProviderProtocolType = 'anthropic';
    sceneCatalog = [
      <String, dynamic>{
        'sceneId': 'scene.image.generate',
        'description': '图像生成',
        'defaultModel': '',
        'effectiveModel': '',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
    ];
    providerFetchResponse = [
      {
        'id': 'dall-e-3',
        'displayName': 'DALL-E 3',
        'outputModalities': ['image'],
      },
      {
        'id': 'chat-with-image-output',
        'displayName': 'Chat Image Output',
        'modelType': 'chat',
        'outputModalities': ['image', 'text'],
      },
      {
        'id': 'image-type-but-text-output',
        'displayName': 'Image Type But Text Output',
        'modelType': 'image',
        'outputModalities': ['text'],
      },
      {
        'id': 'supports-image-output-false',
        'displayName': 'Supports Image Output False',
        'supportsImageOutput': false,
        'modelType': 'image',
      },
      {
        'id': 'text-only',
        'displayName': 'Text Only',
        'outputModalities': ['text'],
      },
    ];
    secondProviderFetchResponse = [
      {
        'id': 'claude-image-attempt',
        'displayName': 'Claude Attempt',
        'outputModalities': ['image'],
      },
    ];

    await pumpSceneSettings(tester);
    await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
    await tester.pumpAndSettle();
    await expandOptionalScene(tester, 'scene.image.generate');
    final imageSelector = find.byKey(
      const Key('scene-model-selector-scene.image.generate'),
    );
    await tester.tap(imageSelector);
    await tester.pumpAndSettle();

    expect(find.text('dall-e-3'), findsOneWidget);
    expect(find.text('chat-with-image-output'), findsOneWidget);
    expect(find.text('image-type-but-text-output'), findsNothing);
    expect(find.text('supports-image-output-false'), findsNothing);
    expect(find.text('text-only'), findsNothing);
    expect(find.text('claude-image-attempt'), findsNothing);
    await tester.tapAt(const Offset(2, 2));
    await tester.pumpAndSettle();
  });

  testWidgets('voice synthesis requires openai_compatible provider and audio output', (
    tester,
  ) async {
    includeSecondProvider = true;
    secondProviderProtocolType = 'anthropic';
    sceneCatalog = [
      <String, dynamic>{
        'sceneId': 'scene.voice',
        'description': '语音合成',
        'defaultModel': '',
        'effectiveModel': '',
        'effectiveProviderProfileId': '',
        'effectiveProviderProfileName': '',
        'boundProviderProfileId': '',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': '',
        'providerConfigured': false,
        'bindingExists': false,
        'bindingProfileMissing': false,
      },
    ];
    providerFetchResponse = [
      {
        'id': 'gpt-4o-audio-preview',
        'displayName': 'GPT-4o Audio',
        'outputModalities': ['audio', 'text'],
      },
      {
        'id': 'text-only-voice-test',
        'displayName': 'Text Only Voice Test',
        'outputModalities': ['text'],
      },
    ];
    secondProviderFetchResponse = [
      {
        'id': 'anthropic-audio-attempt',
        'displayName': 'Anthropic Audio Attempt',
        'outputModalities': ['audio'],
      },
    ];

    await pumpSceneSettings(tester);
    await tester.tap(find.byKey(const Key('scene-model-refresh-button')));
    await tester.pumpAndSettle();
    await expandOptionalScene(tester, 'scene.voice');
    final voiceSelector = find.byKey(
      const Key('scene-model-selector-scene.voice'),
    );
    await tester.tap(voiceSelector);
    await tester.pumpAndSettle();

    expect(find.text('gpt-4o-audio-preview'), findsOneWidget);
    expect(find.text('text-only-voice-test'), findsNothing);
    expect(find.text('anthropic-audio-attempt'), findsNothing);
    await tester.tapAt(const Offset(2, 2));
    await tester.pumpAndSettle();
  });

  testWidgets('broken direct binding displays failure without pretending inheritance', (
    tester,
  ) async {
    sceneCatalog = [
      <String, dynamic>{
        'sceneId': 'scene.vlm.operation.primary',
        'description': 'GUI 操作',
        'defaultModel': 'agent-inherited-model',
        'effectiveModel': 'agent-inherited-model',
        'effectiveProviderProfileId': 'provider-1',
        'effectiveProviderProfileName': 'Provider One',
        'boundProviderProfileId': 'missing-provider',
        'boundProviderProfileName': '',
        'transport': 'openai_compatible',
        'configSource': 'builtin',
        'overrideApplied': false,
        'overrideModel': 'bound-model',
        'providerConfigured': false,
        'bindingExists': true,
        'bindingProfileMissing': true,
      },
    ];
    sceneBindings = [
      {
        'sceneId': 'scene.vlm.operation.primary',
        'providerProfileId': 'missing-provider',
        'modelId': 'bound-model',
      },
    ];

    await pumpSceneSettings(tester);

    expect(find.text('服务商已失效 / bound-model'), findsOneWidget);
    expect(find.textContaining('继承：'), findsNothing);
  });

  testWidgets(
    'groups scenes into basic configuration, optional capabilities, and privileged support',
    (tester) async {
      await pumpSceneSettings(tester);
      expect(find.text('基础配置'), findsOneWidget);
      expect(find.text('可选能力'), findsOneWidget);
      expect(find.text('特权与系统能力'), findsOneWidget);
      expect(find.text('绑定摘要：'), findsOneWidget);
      expect(
        find.byKey(const Key('scene-model-selector-scene.dispatch.model')),
        findsOneWidget,
      );
      expect(find.byKey(const Key('scene-root-status-badge')), findsOneWidget);
      expect(find.byKey(const Key('scene-lsposed-status-badge')), findsOneWidget);
      expect(find.byKey(const Key('scene-root-manage-button')), findsOneWidget);
      expect(find.byKey(const Key('scene-lsposed-manage-button')), findsOneWidget);
    },
  );

  testWidgets(
    'unbound scenes are collapsed by default and bound scenes are expanded by default',
    (tester) async {
      sceneBindings = <Map<String, dynamic>>[
        {
          'sceneId': 'scene.vlm.operation.primary',
          'providerProfileId': 'provider-1',
          'modelId': 'bound-vlm-model',
        },
      ];
      await pumpSceneSettings(tester);

      // 已绑定的 GUI 操作默认展开，选择器可见
      expect(
        find.byKey(const Key('scene-model-selector-scene.vlm.operation.primary')),
        findsOneWidget,
      );

      // 未绑定的视觉理解和上下文压缩默认收起，选择器不可见
      expect(
        find.byKey(const Key('scene-model-selector-scene.vision.understanding')),
        findsNothing,
      );
      expect(
        find.byKey(const Key('scene-model-selector-scene.compactor.context.chat')),
        findsNothing,
      );

      // 点击展开未绑定的视觉理解
      final visionToggle = find.byKey(
        const Key('scene-optional-toggle-scene.vision.understanding'),
      );
      await tester.ensureVisible(visionToggle);
      await tester.tap(visionToggle);
      await tester.pumpAndSettle();
      expect(
        find.byKey(const Key('scene-model-selector-scene.vision.understanding')),
        findsOneWidget,
      );

      // 点击收起已绑定的 GUI 操作
      final vlmToggle = find.byKey(
        const Key('scene-optional-toggle-scene.vlm.operation.primary'),
      );
      await tester.ensureVisible(vlmToggle);
      await tester.tap(vlmToggle);
      await tester.pumpAndSettle();
      expect(
        find.byKey(const Key('scene-model-selector-scene.vlm.operation.primary')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'page initialization never invokes su or privileged mutation commands',
    (tester) async {
      final speCalls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel('cn.com.omnimind.bot/SpecialPermissionEvent'),
        (call) async {
          speCalls.add(call.method);
          if (call.method == 'getRootStatus') {
            return {'enabled': true, 'status': 'GRANTED'};
          }
          return null;
        },
      );

      await pumpSceneSettings(tester);
      // 验证仅允许只读状态查询，绝无 requestRootAccess、setRootEnabled、runShell 或 su 探测
      expect(speCalls, contains('getRootStatus'));
      expect(speCalls, isNot(contains('requestRootAccess')));
      expect(speCalls, isNot(contains('setRootEnabled')));
      expect(speCalls, isNot(contains('runShell')));
      expect(speCalls, isNot(contains('executeShell')));
      expect(speCalls, isNot(contains('su')));

      expect(find.text('已授权'), findsOneWidget);
    },
  );

  testWidgets(
    'full model selector button reuses ConversationModelSelectorContent',
    (tester) async {
      await pumpSceneSettings(tester);
      final fullSelectorButton = find.byKey(
        const Key('scene-model-conversation-selector-scene.dispatch.model'),
      );
      expect(fullSelectorButton, findsOneWidget);
    },
  );

}

Future<void> seedManualModels({
  required String profileId,
  required String apiBase,
  int? profileRevision,
  required List<ProviderModelOption> models,
}) => ModelProviderConfigService.saveManualModelIds(
  profileId: profileId,
  ids: models.map((m) => m.id).toList(),
);
