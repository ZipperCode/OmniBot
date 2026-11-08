import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/agent/agent_config_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/model_provider_config_service.dart';
import 'package:ui/services/models_dev_catalog_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/navigation/page_route.dart';
import 'package:ui/widgets/settings_ui.dart';

Future<void> _sendBackGesture(
  WidgetTester tester,
  String method, [
  Map<String, dynamic>? arguments,
]) async {
  final message = const StandardMethodCodec().encodeMethodCall(
    MethodCall(method, arguments),
  );
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    'flutter/backgesture',
    message,
    (ByteData? _) {},
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const assistCoreChannel = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    ModelsDevCatalogService.setCatalogForTesting(
      const ModelsDevCatalog(providers: {}),
    );
  });

  tearDown(() {
    ModelsDevCatalogService.resetForTesting();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, null);
  });


  testWidgets('custom Agent launch edits survive saving and reopening', (
    tester,
  ) async {
    var stored = <String, dynamic>{
      'id': 'my-acp-agent',
      'name': 'My ACP Agent',
      'command': 'my-agent',
      'arguments': <String>[],
      'environment': <String, String>{'OLD_OPTION': 'remove me'},
      'enabled': true,
      'builtIn': false,
      'source': 'custom',
    };
    final runtimeCalls = <String>[];
    final providerCalls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          runtimeCalls.add(call.method);
          if (call.method == 'agent/list') return _catalog(stored);
          if (call.method == 'agent/save') {
            final args = Map<String, dynamic>.from(call.arguments as Map);
            stored = Map<String, dynamic>.from(args['agent'] as Map);
            return <String, dynamic>{'catalog': _catalog(stored)};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          providerCalls.add(call.method);
          return null;
        });

    await _pumpPage(tester, 'my-acp-agent');
    const command = '/workspace/my agent/bin/acp';
    const arguments = '--config\n/workspace/my agent/settings.json';
    const environment =
        'OPENAI_API_KEY=user-test-key\n'
        'OPENAI_BASE_URL=https://user.example/v1\n'
        'CUSTOM_OPTION=  中文 = \'quotes\' \$literal  \n'
        'EMPTY_OPTION=';
    final fields = find.byType(TextField);
    expect(fields, findsNWidgets(3));
    await tester.enterText(fields.at(0), command);
    await tester.enterText(fields.at(1), arguments);
    await tester.enterText(fields.at(2), environment);
    await tester.ensureVisible(find.byKey(const Key('agent-config-save')));
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(stored['command'], command);
    expect(stored['arguments'], arguments.split('\n'));
    expect(stored['environment'], <String, String>{
      'OPENAI_API_KEY': 'user-test-key',
      'OPENAI_BASE_URL': 'https://user.example/v1',
      'CUSTOM_OPTION': '  中文 = \'quotes\' \$literal  ',
      'EMPTY_OPTION': '',
    });

    await tester.pumpWidget(const SizedBox.shrink());
    await _pumpPage(tester, 'my-acp-agent');
    expect(tester.widget<TextField>(fields.at(0)).controller!.text, command);
    expect(tester.widget<TextField>(fields.at(1)).controller!.text, arguments);
    expect(
      tester.widget<TextField>(fields.at(2)).controller!.text,
      environment,
    );
    expect(providerCalls, isEmpty);
    expect(runtimeCalls, <String>['agent/list', 'agent/save', 'agent/list']);
  });

  testWidgets('shared Provider selector saves the Agent scene binding', (
    tester,
  ) async {
    Map<String, dynamic>? savedBinding;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('codex-acp', 'Codex'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
            };
          }
          if (call.method == 'disconnect') {
            return <String, dynamic>{'connected': false, 'ready': true};
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistCoreChannel, (call) async {
          switch (call.method) {
            case 'listModelProviderProfiles':
              return <String, dynamic>{
                'editingProfileId': 'provider-1',
                'profiles': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'provider-1',
                    'name': 'DeepSeek Provider',
                    'baseUrl': 'https://api.deepseek.com',
                    'apiKey': 'sk-test',
                    'configured': true,
                    'hasApiKey': true,
                    'revision': 1,
                  },
                ],
              };
            case 'getSceneModelBindings':
              return <dynamic>[];
            case 'saveSceneModelBinding':
              savedBinding = Map<String, dynamic>.from(call.arguments as Map);
              return <Map<String, dynamic>>[
                <String, dynamic>{
                  'sceneId': 'scene.dispatch.model',
                  'providerProfileId': 'provider-1',
                  'modelId': 'deepseek-v4-pro',
                },
              ];
          }
          return null;
        });

    await seedManualModels(
      profileId: 'provider-1',
      apiBase: 'https://api.deepseek.com',
      profileRevision: 1,
      models: const <ProviderModelOption>[
        ProviderModelOption(
          id: 'deepseek-v4-pro',
          displayName: 'deepseek-v4-pro',
        ),
      ],
    );

    await _pumpPage(tester, 'codex-acp');
    await tester.tap(
      find.byKey(const Key('agent-shared-provider-model-selector')),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('deepseek-v4-pro'));
    await tester.pumpAndSettle();

    expect(savedBinding?['sceneId'], 'scene.dispatch.model');
    expect(savedBinding?['providerProfileId'], 'provider-1');
    expect(savedBinding?['modelId'], 'deepseek-v4-pro');
  });

  testWidgets(
    'shared Agent model selection accepts ready OAuth without an API key',
    (tester) async {
      Map<String, dynamic>? savedBinding;
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('codex-acp', 'Codex'));
        }
        if (call.method == 'agent/config/read') return {'kind': 'codex'};
        return null;
      });
      messenger.setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return {
              'editingProfileId': 'chatgpt-1',
              'profiles': [
                {
                  'id': 'chatgpt-1',
                  'name': 'My ChatGPT',
                  'baseUrl': 'https://api.openai.com/v1',
                  'sourceType': 'chatgpt_oauth',
                  'wireApi': 'responses',
                  'configured': true,
                  'ready': true,
                  'apiKey': '',
                  'hasApiKey': false,
                  'revision': 12,
                },
              ],
            };
          case 'getSceneModelBindings':
            return <dynamic>[];
          case 'fetchProviderModels':
            expect((call.arguments as Map)['profileId'], 'chatgpt-1');
            return [
              {'id': 'gpt-test', 'displayName': 'gpt-test'},
            ];
          case 'saveSceneModelBinding':
            savedBinding = Map<String, dynamic>.from(call.arguments as Map);
            return [
              {
                'sceneId': 'scene.dispatch.model',
                'providerProfileId': 'chatgpt-1',
                'modelId': 'gpt-test',
              },
            ];
        }
        return null;
      });
      await _pumpPage(tester, 'codex-acp');
      await tester.tap(
        find.byKey(const Key('agent-shared-provider-model-selector')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('gpt-test'));
      await tester.pumpAndSettle();
      expect(savedBinding?['sceneId'], 'scene.dispatch.model');
      expect(savedBinding?['providerProfileId'], 'chatgpt-1');
      expect(savedBinding?['modelId'], 'gpt-test');
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('Codex config page uses the shared Provider selector', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('codex-acp', 'Codex'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'codex-acp',
              'kind': 'codex',
              'configPath': '~/.codex/config.toml',
              'authPath': '~/.codex/auth.json',
              'baseUrl': 'https://old.example/v1',
              'model': 'old-model',
              'apiKey': 'sk-old',
            };
          }
          return null;
        });

    await _pumpPage(tester, 'codex-acp');

    expect(find.textContaining('~/.codex/config.toml'), findsOneWidget);
    expect(find.textContaining('~/.codex/auth.json'), findsOneWidget);
    expect(
      find.byKey(const Key('agent-shared-provider-model-selector')),
      findsOneWidget,
    );
    expect(find.byKey(const Key('codex-agent-base-url')), findsNothing);
    expect(find.byKey(const Key('codex-agent-model')), findsNothing);
    expect(find.byKey(const Key('codex-agent-api-key')), findsNothing);
  });

  testWidgets(
    'built-in Agent config uses ordinary system back without a second transition',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method == 'agent/list') {
              return _catalog(_agent('codex-acp', 'Codex'));
            }
            if (call.method == 'agent/config/read') {
              return <String, dynamic>{
                'agentId': 'codex-acp',
                'kind': 'codex',
                'configPath': '~/.codex/config.toml',
                'authPath': '~/.codex/auth.json',
                'baseUrl': 'https://api.example/v1',
                'model': 'gpt-5',
                'apiKey': 'sk-test',
              };
            }
            return null;
          });

      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => Navigator.of(context).push(
                  OmniPageRoute<void>(
                    builder: (_) => const AgentConfigPage(agentId: 'codex-acp'),
                  ),
                ),
                child: const Text('open config'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open config'));
      await tester.pumpAndSettle();

      final route = ModalRoute.of(
        tester.element(find.byType(AgentConfigPage)),
      )!;
      expect(route.popGestureEnabled, isTrue);

      await _sendBackGesture(tester, 'startBackGesture', <String, dynamic>{
        'touchOffset': <double>[0.0, 300.0],
        'progress': 0.0,
        'swipeEdge': 0,
      });
      await tester.pump();

      expect(route.popGestureInProgress, isFalse);
      await _sendBackGesture(
        tester,
        'updateBackGestureProgress',
        <String, dynamic>{
          'touchOffset': <double>[400.0, 300.0],
          'progress': 0.4,
          'swipeEdge': 0,
        },
      );
      await tester.pump();
      expect(route.animation!.value, 1);
      await _sendBackGesture(tester, 'cancelBackGesture');
      await tester.pumpAndSettle();
      expect(find.byType(AgentConfigPage), findsOneWidget);
      expect(route.animation!.value, 1);
      expect(route.popGestureInProgress, isFalse);
      await _sendBackGesture(tester, 'startBackGesture', <String, dynamic>{
        'touchOffset': <double>[0.0, 300.0],
        'progress': 0.0,
        'swipeEdge': 0,
      });
      await _sendBackGesture(tester, 'commitBackGesture');
      await tester.pumpAndSettle();
      expect(route.isActive, isFalse);
    },
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets('Claude config page edits the complete settings.json content', (
    tester,
  ) async {
    Map<String, dynamic>? saved;
    const initial = '{\n  "env": {"ANTHROPIC_MODEL": "claude-sonnet"}\n}\n';
    const updated = '{\n  "env": {"ANTHROPIC_MODEL": "claude-opus"}\n}\n';
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return _catalog(_agent('claude-code-acp', 'Claude Code'));
          }
          if (call.method == 'agent/config/read') {
            return <String, dynamic>{
              'agentId': 'claude-code-acp',
              'kind': 'json',
              'path': '~/.claude/settings.json',
              'content': initial,
            };
          }
          if (call.method == 'agent/config/write') {
            saved = Map<String, dynamic>.from(call.arguments as Map);
            return <String, dynamic>{
              'agentId': 'claude-code-acp',
              'kind': 'json',
              'path': '~/.claude/settings.json',
              'content': saved!['content'],
            };
          }
          return null;
        });

    await _pumpPage(tester, 'claude-code-acp');

    expect(find.textContaining('~/.claude/settings.json'), findsWidgets);
    expect(find.textContaining('claude-sonnet'), findsOneWidget);
    await tester.enterText(
      find.byKey(const Key('agent-raw-config-content')),
      updated,
    );
    await tester.tap(find.byKey(const Key('agent-config-save')));
    await tester.pumpAndSettle();

    expect(saved?['agentId'], 'claude-code-acp');
    expect(saved?['content'], updated);
    expect(find.textContaining('claude-opus'), findsOneWidget);
  });

  testWidgets(
    'Xiaowan Subagent configuration panel loads, saves with content and expectedRevision 0, and updates on reopening',
    (tester) async {
      var readPayload = <String, dynamic>{
        'agentId': 'xiaowan-acp',
        'kind': 'xiaowan',
        'revision': 0,
        'subagent': <String, dynamic>{
          'enabled': true,
          'providerProfileId': null,
          'modelId': null,
          'reasoningEffort': null,
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'general',
              'displayName': '通用子任务',
              'systemPrompt': 'general system prompt',
              'description': 'general desc',
              'baseProfileId': 'general',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
            },
            <String, dynamic>{
              'id': 'explorer',
              'displayName': '探索者',
              'systemPrompt': 'explorer system prompt',
              'description': 'explorer desc',
              'baseProfileId': 'explorer',
              'enabled': true,
              'allowedTools': <String>['file_read'],
              'disallowedTools': <String>['file_write'],
            },
          ],
        },
      };

      Map<String, dynamic>? lastWriteArgs;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return readPayload;
        }
        if (call.method == 'agent/config/write') {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          lastWriteArgs = args;
          final updatedSubagent =
              jsonDecode(args['content'] as String) as Map<String, dynamic>;
          readPayload = <String, dynamic>{
            'agentId': 'xiaowan-acp',
            'kind': 'xiaowan',
            'revision': (args['expectedRevision'] as int? ?? 0) + 1,
            'subagent': updatedSubagent,
          };
          return readPayload;
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{
            'editingProfileId': null,
            'profiles': <dynamic>[],
          };
        }
        if (call.method == 'getSceneModelBindings') {
          return <dynamic>[];
        }
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      expect(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('xiaowan-subagent-dedicated-model-tile')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('xiaowan-subagent-reasoning-dropdown')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('xiaowan-subagent-profile-tile-general')),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('xiaowan-subagent-profile-tile-explorer')),
        findsOneWidget,
      );
      expect(find.byKey(const Key('agent-raw-config-content')), findsNothing);

      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-profile-switch-explorer')),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      expect(lastWriteArgs, isNotNull);
      expect(lastWriteArgs!['agentId'], 'xiaowan-acp');
      expect(lastWriteArgs!['expectedRevision'], 0);
      final savedSubagent =
          jsonDecode(lastWriteArgs!['content'] as String) as Map<String, dynamic>;
      final explorerMap = (savedSubagent['profiles'] as List)
          .firstWhere((p) => p['id'] == 'explorer') as Map;
      expect(explorerMap['enabled'], false);

      await _pumpPage(tester, 'xiaowan-acp');
      expect(
        find.byKey(const Key('xiaowan-subagent-profile-tile-explorer')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'Xiaowan Subagent allows adding and editing custom role with baseProfileId and custom tool policy',
    (tester) async {
      var readPayload = <String, dynamic>{
        'agentId': 'xiaowan-acp',
        'kind': 'xiaowan',
        'revision': 0,
        'subagent': <String, dynamic>{
          'enabled': true,
          'providerProfileId': null,
          'modelId': null,
          'reasoningEffort': null,
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'general',
              'displayName': '通用子任务',
              'systemPrompt': 'general system prompt',
              'description': 'general desc',
              'baseProfileId': 'general',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
            },
          ],
        },
      };

      Map<String, dynamic>? lastWriteArgs;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return readPayload;
        }
        if (call.method == 'agent/config/write') {
          lastWriteArgs = Map<String, dynamic>.from(call.arguments as Map);
          return <String, dynamic>{
            'agentId': 'xiaowan-acp',
            'kind': 'xiaowan',
            'revision': 1,
            'subagent': jsonDecode(lastWriteArgs!['content'] as String),
          };
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{'editingProfileId': null, 'profiles': <dynamic>[]};
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-add-profile-button')),
      );
      await tester.pumpAndSettle();

      await tester.enterText(
        find.byKey(const Key('xiaowan-subagent-sheet-id-input')),
        'custom-reviewer',
      );
      await tester.enterText(
        find.byKey(const Key('xiaowan-subagent-sheet-name-input')),
        '代码审查员',
      );
      await tester.enterText(
        find.byKey(const Key('xiaowan-subagent-sheet-prompt-input')),
        '审查 PR 代码质量',
      );

      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-sheet-tool-mode-dropdown')),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('无工具').last);
      await tester.pumpAndSettle();

      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-sheet-save-button')),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('xiaowan-subagent-profile-tile-custom-reviewer')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      expect(lastWriteArgs, isNotNull);
      final savedSubagent =
          jsonDecode(lastWriteArgs!['content'] as String) as Map<String, dynamic>;
      final customRole = (savedSubagent['profiles'] as List)
          .firstWhere((p) => p['id'] == 'custom-reviewer') as Map;
      expect(customRole['displayName'], '代码审查员');
      expect(customRole['allowedTools'], isEmpty);
      expect(customRole['baseProfileId'], 'general');
    },
  );

  testWidgets(
    'Xiaowan Subagent save failure preserves user edits and displays error',
    (tester) async {
      final readPayload = <String, dynamic>{
        'agentId': 'xiaowan-acp',
        'kind': 'xiaowan',
        'revision': 0,
        'subagent': <String, dynamic>{
          'enabled': true,
          'providerProfileId': null,
          'modelId': null,
          'reasoningEffort': null,
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'general',
              'displayName': '通用子任务',
              'systemPrompt': 'general system prompt',
              'description': 'general desc',
              'baseProfileId': 'general',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
            },
          ],
        },
      };

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return readPayload;
        }
        if (call.method == 'agent/config/write') {
          throw PlatformException(
            code: 'revision_conflict',
            message: 'Stale revision',
          );
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{'editingProfileId': null, 'profiles': <dynamic>[]};
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('agent-config-error-text')), findsOneWidget);
      expect(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'non-Xiaowan agents do not display Subagent panel',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('codex-acp', 'Codex'));
        }
        if (call.method == 'agent/config/read') {
          return <String, dynamic>{
            'agentId': 'codex-acp',
            'kind': 'codex',
            'configPath': '~/.codex/config.toml',
            'authPath': '~/.codex/auth.json',
          };
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{'editingProfileId': null, 'profiles': <dynamic>[]};
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'codex-acp');

      expect(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
        findsNothing,
      );
      expect(
        find.byKey(const Key('xiaowan-subagent-add-profile-button')),
        findsNothing,
      );
      expect(
        find.byKey(const Key('agent-shared-provider-model-selector')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'Xiaowan general profile cannot be disabled and built-in profiles cannot be deleted',
    (tester) async {
      final readPayload = <String, dynamic>{
        'agentId': 'xiaowan-acp',
        'kind': 'xiaowan',
        'revision': 0,
        'subagent': <String, dynamic>{
          'enabled': true,
          'providerProfileId': null,
          'modelId': null,
          'reasoningEffort': null,
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'general',
              'displayName': '通用子任务',
              'systemPrompt': 'general prompt',
              'description': 'general desc',
              'baseProfileId': 'general',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
            },
          ],
        },
      };

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return readPayload;
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{'editingProfileId': null, 'profiles': <dynamic>[]};
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      expect(
        find.byKey(const Key('xiaowan-subagent-profile-switch-general')),
        findsNothing,
      );

      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-profile-tile-general')),
      );
      await tester.pumpAndSettle();

      final switchFinder =
          find.byKey(const Key('xiaowan-subagent-sheet-enabled-switch'));
      expect(switchFinder, findsOneWidget);
      expect(tester.widget<OmniSettingsSwitch>(switchFinder).value, true);
      await tester.tap(switchFinder);
      await tester.pumpAndSettle();
      expect(tester.widget<OmniSettingsSwitch>(switchFinder).value, true);
      expect(
        find.byKey(const Key('xiaowan-subagent-sheet-delete-button')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'Xiaowan missing subagent payload on initial load shows error state without editable save',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return <String, dynamic>{
            'agentId': 'xiaowan-acp',
            'kind': 'xiaowan',
            'revision': 0,
          };
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{
            'editingProfileId': null,
            'profiles': <dynamic>[],
          };
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      expect(find.textContaining('小万配置缺失 subagent 载荷'), findsOneWidget);
      expect(find.text('重试'), findsOneWidget);
      expect(find.byKey(const Key('agent-config-save')), findsNothing);
      expect(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'Xiaowan incomplete role model reload clears draft and prevents overwriting config',
    (tester) async {
      var readCount = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          readCount++;
          if (readCount == 1) {
            return <String, dynamic>{
              'agentId': 'xiaowan-acp',
              'kind': 'xiaowan',
              'revision': 0,
              'subagent': <String, dynamic>{
                'enabled': true,
                'providerProfileId': null,
                'modelId': null,
                'reasoningEffort': null,
                'profiles': <Map<String, dynamic>>[
                  <String, dynamic>{
                    'id': 'general',
                    'displayName': '通用子任务',
                    'systemPrompt': 'general prompt',
                    'description': 'desc',
                    'baseProfileId': 'general',
                    'enabled': true,
                    'allowedTools': null,
                    'disallowedTools': <String>[],
                  },
                ],
              },
            };
          }
          return <String, dynamic>{
            'agentId': 'xiaowan-acp',
            'kind': 'xiaowan',
            'revision': 1,
            'subagent': <String, dynamic>{
              'enabled': true,
              'profiles': <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'general',
                  'displayName': '通用子任务',
                  'systemPrompt': 'general prompt',
                  'baseProfileId': 'general',
                  'enabled': true,
                  'providerProfileId': 'role-provider',
                  'modelId': null,
                },
              ],
            },
          };
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{
            'editingProfileId': null,
            'profiles': <dynamic>[],
          };
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      expect(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
        findsOneWidget,
      );
      final saveFinder = find.byKey(const Key('agent-config-save'));
      expect(saveFinder, findsOneWidget);
      expect(tester.widget<FilledButton>(saveFinder).onPressed, isNotNull);

      await tester.tap(find.byKey(const Key('agent-config-reload')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('agent-config-error-text')), findsOneWidget);
      expect(tester.widget<FilledButton>(saveFinder).onPressed, isNull);
      expect(
        find.byKey(const Key('xiaowan-subagent-enabled-switch')),
        findsNothing,
      );
    },
  );
  testWidgets(
    'Xiaowan Subagent profile allows choosing dedicated model, persisting on save/reload, and fallback to default when reset',
    (tester) async {
      var readPayload = <String, dynamic>{
        'agentId': 'xiaowan-acp',
        'kind': 'xiaowan',
        'revision': 0,
        'subagent': <String, dynamic>{
          'enabled': true,
          'providerProfileId': 'provider-2',
          'modelId': 'gpt-4o',
          'reasoningEffort': null,
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'general',
              'displayName': '通用子任务',
              'systemPrompt': 'general system prompt',
              'description': 'general desc',
              'baseProfileId': 'general',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
              'providerProfileId': null,
              'modelId': null,
            },
            <String, dynamic>{
              'id': 'planner',
              'displayName': '任务规划器',
              'systemPrompt': 'planner system prompt',
              'description': 'planner desc',
              'baseProfileId': 'planner',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
              'providerProfileId': null,
              'modelId': null,
            },
          ],
        },
      };

      Map<String, dynamic>? lastWriteArgs;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return readPayload;
        }
        if (call.method == 'agent/config/write') {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          lastWriteArgs = args;
          final updatedSubagent =
              jsonDecode(args['content'] as String) as Map<String, dynamic>;
          readPayload = <String, dynamic>{
            'agentId': 'xiaowan-acp',
            'kind': 'xiaowan',
            'revision': (args['expectedRevision'] as int? ?? 0) + 1,
            'subagent': updatedSubagent,
          };
          return readPayload;
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        switch (call.method) {
          case 'listModelProviderProfiles':
            return <String, dynamic>{
              'editingProfileId': 'provider-1',
              'profiles': <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'provider-1',
                  'name': 'DeepSeek Provider',
                  'baseUrl': 'https://api.deepseek.com',
                  'apiKey': 'sk-test',
                  'configured': true,
                  'hasApiKey': true,
                  'revision': 1,
                },
                <String, dynamic>{
                  'id': 'provider-2',
                  'name': 'OpenAI Provider',
                  'baseUrl': 'https://api.openai.com',
                  'apiKey': 'sk-test-2',
                  'configured': true,
                  'hasApiKey': true,
                  'revision': 1,
                },
              ],
            };
          case 'getSceneModelBindings':
            return <dynamic>[];
          case 'fetchProviderModels':
            final profileId = (call.arguments as Map?)?['profileId'];
            if (profileId == 'provider-1') {
              return <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'deepseek-v4-pro',
                  'displayName': 'deepseek-v4-pro',
                },
              ];
            }
            if (profileId == 'provider-2') {
              return <Map<String, dynamic>>[
                <String, dynamic>{
                  'id': 'gpt-4o',
                  'displayName': 'gpt-4o',
                },
              ];
            }
            return <dynamic>[];
        }
        return null;
      });

      await seedManualModels(
        profileId: 'provider-1',
        apiBase: 'https://api.deepseek.com',
        profileRevision: 1,
        models: const <ProviderModelOption>[
          ProviderModelOption(
            id: 'deepseek-v4-pro',
            displayName: 'deepseek-v4-pro',
          ),
        ],
      );
      await seedManualModels(
        profileId: 'provider-2',
        apiBase: 'https://api.openai.com',
        profileRevision: 1,
        models: const <ProviderModelOption>[
          ProviderModelOption(
            id: 'gpt-4o',
            displayName: 'gpt-4o',
          ),
        ],
      );

      // 1. 加载小万页面，验证初始继承默认模型
      await _pumpPage(tester, 'xiaowan-acp');
      final plannerTile =
          find.byKey(const Key('xiaowan-subagent-profile-tile-planner'));
      expect(plannerTile, findsOneWidget);
      expect(find.textContaining('继承默认子代理模型'), findsWidgets);

      // 2. 打开 planner 角色编辑弹窗
      await tester.tap(plannerTile);
      await tester.pumpAndSettle();

      final roleModelSelector =
          find.byKey(const Key('xiaowan-subagent-sheet-model-selector'));
      expect(roleModelSelector, findsOneWidget);
      expect(
        find.byKey(const Key('xiaowan-subagent-sheet-reset-model-button')),
        findsNothing,
      );

      // 3. 点击模型选择器，选择 deepseek-v4-pro
      await tester.tap(roleModelSelector);
      await tester.pumpAndSettle();

      final modelOption = find.text('deepseek-v4-pro');
      expect(modelOption, findsOneWidget);
      await tester.tap(modelOption);
      await tester.pumpAndSettle();

      // 4. 验证弹窗内显示已选模型与重置按钮
      expect(
        find.textContaining('DeepSeek Provider / deepseek-v4-pro'),
        findsWidgets,
      );
      expect(
        find.byKey(const Key('xiaowan-subagent-sheet-reset-model-button')),
        findsOneWidget,
      );

      // 5. 保存角色并触发全局保存
      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-sheet-save-button')),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      // 6. 验证持久化 payload
      expect(lastWriteArgs, isNotNull);
      final savedSubagent =
          jsonDecode(lastWriteArgs!['content'] as String) as Map<String, dynamic>;
      final savedPlanner = (savedSubagent['profiles'] as List)
          .firstWhere((p) => p['id'] == 'planner') as Map;
      expect(savedPlanner['providerProfileId'], 'provider-1');
      expect(savedPlanner['modelId'], 'deepseek-v4-pro');
      final savedGeneral = (savedSubagent['profiles'] as List)
          .firstWhere((p) => p['id'] == 'general') as Map;
      expect(savedGeneral['providerProfileId'], isNull);
      expect(savedGeneral['modelId'], isNull);

      // 7. 重新加载小万页面，确认 planner 专属模型保留
      await tester.pumpWidget(const SizedBox.shrink());
      await _pumpPage(tester, 'xiaowan-acp');

      expect(
        find.textContaining('DeepSeek Provider / deepseek-v4-pro'),
        findsWidgets,
      );

      // 8. 再次打开 planner 弹窗并点击清空重置按钮
      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-profile-tile-planner')),
      );
      await tester.pumpAndSettle();

      final resetBtn =
          find.byKey(const Key('xiaowan-subagent-sheet-reset-model-button'));
      expect(resetBtn, findsOneWidget);
      await tester.tap(resetBtn);
      await tester.pumpAndSettle();

      // 9. 确认重置按钮消失并显示继承默认文案
      expect(
        find.byKey(const Key('xiaowan-subagent-sheet-reset-model-button')),
        findsNothing,
      );
      expect(find.textContaining('使用默认子代理模型'), findsWidgets);

      // 10. 保存弹窗并全局保存，验证清空后 null pair
      await tester.tap(
        find.byKey(const Key('xiaowan-subagent-sheet-save-button')),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      final resetSubagent =
          jsonDecode(lastWriteArgs!['content'] as String) as Map<String, dynamic>;
      final resetPlanner = (resetSubagent['profiles'] as List)
          .firstWhere((p) => p['id'] == 'planner') as Map;
      expect(resetPlanner['providerProfileId'], isNull);
      expect(resetPlanner['modelId'], isNull);

      // 11. 重载页面验证回到继承状态
      await tester.pumpWidget(const SizedBox.shrink());
      await _pumpPage(tester, 'xiaowan-acp');
      expect(find.textContaining('继承默认子代理模型'), findsWidgets);
    },
  );

  testWidgets(
    'Xiaowan modifying default subagent model does not overwrite role dedicated model pair',
    (tester) async {
      var readPayload = <String, dynamic>{
        'agentId': 'xiaowan-acp',
        'kind': 'xiaowan',
        'revision': 0,
        'subagent': <String, dynamic>{
          'enabled': true,
          'providerProfileId': 'provider-2',
          'modelId': 'gpt-4o',
          'reasoningEffort': null,
          'profiles': <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'general',
              'displayName': '通用子任务',
              'systemPrompt': 'general system prompt',
              'description': 'general desc',
              'baseProfileId': 'general',
              'enabled': true,
              'allowedTools': null,
              'disallowedTools': <String>[],
              'providerProfileId': 'provider-1',
              'modelId': 'deepseek-v4-pro',
            },
          ],
        },
      };

      Map<String, dynamic>? lastWriteArgs;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
        if (call.method == 'agent/list') {
          return _catalog(_agent('xiaowan-acp', '小万'));
        }
        if (call.method == 'agent/config/read') {
          return readPayload;
        }
        if (call.method == 'agent/config/write') {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          lastWriteArgs = args;
          readPayload = <String, dynamic>{
            'agentId': 'xiaowan-acp',
            'kind': 'xiaowan',
            'revision': 1,
            'subagent': jsonDecode(args['content'] as String),
          };
          return readPayload;
        }
        return null;
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistCoreChannel, (call) async {
        if (call.method == 'listModelProviderProfiles') {
          return <String, dynamic>{
            'editingProfileId': 'provider-1',
            'profiles': <Map<String, dynamic>>[
              <String, dynamic>{
                'id': 'provider-1',
                'name': 'DeepSeek Provider',
                'baseUrl': 'https://api.deepseek.com',
                'apiKey': 'sk-test',
                'configured': true,
                'hasApiKey': true,
                'revision': 1,
              },
            ],
          };
        }
        if (call.method == 'getSceneModelBindings') return <dynamic>[];
        return null;
      });

      await _pumpPage(tester, 'xiaowan-acp');

      // 验证 general 角色具有专属模型
      expect(
        find.textContaining('DeepSeek Provider / deepseek-v4-pro'),
        findsOneWidget,
      );

      // 重置全局默认子代理模型为跟随主 Agent
      final resetDefaultBtn =
          find.byKey(const Key('xiaowan-subagent-reset-model-button'));
      expect(resetDefaultBtn, findsOneWidget);
      await tester.tap(resetDefaultBtn);
      await tester.pumpAndSettle();

      // 保存配置
      await tester.tap(find.byKey(const Key('agent-config-save')));
      await tester.pumpAndSettle();

      // 验证全局默认模型已清空，但 general 角色的专属 pair 严格保留未被覆盖
      expect(lastWriteArgs, isNotNull);
      final savedSubagent =
          jsonDecode(lastWriteArgs!['content'] as String) as Map<String, dynamic>;
      expect(savedSubagent['providerProfileId'], isNull);
      expect(savedSubagent['modelId'], isNull);
      final generalProfile = (savedSubagent['profiles'] as List)
          .firstWhere((p) => p['id'] == 'general') as Map;
      expect(generalProfile['providerProfileId'], 'provider-1');
      expect(generalProfile['modelId'], 'deepseek-v4-pro');
    },
  );
}

Future<void> _pumpPage(WidgetTester tester, String agentId) async {
  tester.view.physicalSize = const Size(1080, 2200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  await tester.pumpWidget(
    MaterialApp(
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('zh'),
      home: AgentConfigPage(agentId: agentId),
    ),
  );
  await tester.pumpAndSettle();
}

Map<String, dynamic> _catalog(Map<String, dynamic> agent) {
  return <String, dynamic>{
    'agents': <Map<String, dynamic>>[agent],
  };
}

Map<String, dynamic> _agent(String id, String name) {
  final command = switch (id) {
    'codex-acp' => 'codex-acp',
    _ => 'claude-agent-acp',
  };
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': command,
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'status': 'online',
    'configurationAvailable': true,
  };
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
