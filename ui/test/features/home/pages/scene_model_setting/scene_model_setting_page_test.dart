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
    providerFetchCount = 0;
    sceneBindings = <Map<String, dynamic>>[];
    providerFetchResponse = <Map<String, dynamic>>[];
    secondProviderFetchResponse = <Map<String, dynamic>>[];
    providerFetchCompleter = null;
    providerFetchError = null;
    lastProviderFetchArguments = null;
    providerFetchArguments = <Map<dynamic, dynamic>>[];
    savedOperationConfig = <String, dynamic>{'useOfficialService': true};

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getSceneModelCatalog':
              return <Map<String, dynamic>>[
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
              ];
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
                      'protocolType': 'openai_compatible',
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
    expect(find.text('Chat Compactor'), findsOneWidget);
    expect(find.text('Voice'), findsNothing);
    expect(loadCount, 1);

    loader.complete(
      ModelsDevCatalogService.parseCatalog(_modelsDevCatalogJson),
    );
    for (var index = 0; index < 4; index++) {
      await tester.pump(const Duration(milliseconds: 1));
    }
    expect(tester.takeException(), isNull);
  });

  testWidgets('scene entry paints cache and has no manual refresh control', (
    tester,
  ) async {
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

    expect(providerFetchCount, 1);
    expect(
      find.byKey(const Key('scene-model-refresh-provider-models-button')),
      findsNothing,
    );
    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('cached-model'), findsOneWidget);
  });

  testWidgets('configured BYOK provider refreshes automatically', (
    tester,
  ) async {
    providerFetchResponse = <Map<String, dynamic>>[
      <String, dynamic>{'id': 'fresh-model', 'displayName': 'Fresh model'},
    ];
    await pumpSceneSettings(tester);

    expect(providerFetchCount, 1);
    expect(lastProviderFetchArguments?['apiBase'], providerBaseUrl);
    expect(lastProviderFetchArguments?['profileId'], 'provider-1');
  });

  testWidgets('changed provider revision cannot apply an old fetch result', (
    tester,
  ) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);
    expect(providerFetchCount, 1);

    providerBaseUrl = 'https://replacement.example.com/v1';
    providerRevision = 2;
    pending.complete(<Map<String, dynamic>>[
      <String, dynamic>{'id': 'stale-model', 'displayName': 'Stale model'},
    ]);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
    }

    await tester.tap(
      find.byKey(
        const Key('scene-model-selector-scene.compactor.context.chat'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('stale-model'), findsNothing);
  });

  testWidgets('automatic refresh disposal ignores completion', (tester) async {
    final pending = Completer<List<Map<String, dynamic>>>();
    providerFetchCompleter = pending;
    await pumpSceneSettings(tester);
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

  testWidgets('background refresh errors do not leak endpoint details', (
    tester,
  ) async {
    providerFetchError =
        'socket failed at https://user:token@example.com/private?key=secret';
    await pumpSceneSettings(tester);
    for (var attempt = 0; attempt < 10; attempt++) {
      await tester.pump();
    }

    expect(find.textContaining('user:token'), findsNothing);
    expect(find.textContaining('/private'), findsNothing);
    expect(find.textContaining('key=secret'), findsNothing);
  });

  testWidgets('GUI scene is labeled GUI instead of VLM', (tester) async {
    tester.view.physicalSize = const Size(1080, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(buildTestApp(const SceneModelSettingPage()));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));

    expect(find.text('GUI'), findsOneWidget);
    expect(find.text('VLM'), findsNothing);
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
      final fetchCountBeforeOpen = providerFetchCount;
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
      tester.view.physicalSize = const Size(360, 800);
      await tester.pumpAndSettle();
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
