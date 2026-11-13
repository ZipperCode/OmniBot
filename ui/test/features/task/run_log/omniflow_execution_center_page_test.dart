import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:go_router/go_router.dart';
import 'package:ui/features/task/pages/execution_history/omniflow_execution_center_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';

void main() {
  setUp(() => LegacyTextLocalizer.setResolvedLocale(const Locale('zh')));
  tearDown(LegacyTextLocalizer.clearResolvedLocale);
  TestWidgetsFlutterBinding.ensureInitialized();
  const pluginChannel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  const specialPermissionChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  final toolCalls = <Map<Object?, Object?>>[];
  FutureOr<Map<String, Object?>?> Function(
    String name,
    Map<Object?, Object?> call,
  )? toolResponseOverride;

  setUp(() {
    toolCalls.clear();
    toolResponseOverride = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, (call) async {
          if (call.method != 'list') return null;
          return <Object?>[
            <String, Object?>{
              'id': 'com.omnimind.omni-vlm-lite',
              'name': 'Omni VLM Lite',
              'version': '2.0.0',
              'interfaceVersion': 1,
              'description': 'GUI runtime',
              'publisher': 'OmniMind',
              'kind': 'runtime_bundle',
              'downloadSizeBytes': 0,
              'capabilities': <String>[],
              'settingsSchema': <String, Object?>{},
              'installed': true,
              'enabled': true,
              'compatible': true,
            },
          ];
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
          if (call.method != 'tools/call') return null;
          final arguments = Map<Object?, Object?>.from(call.arguments as Map);
          toolCalls.add(arguments);
          final override = await toolResponseOverride?.call(
            arguments['name'].toString(),
            arguments,
          );
          if (override != null) return override;
          return switch (arguments['name']) {
            'list_functions' => <String, Object?>{
              'success': true,
              'count': 1,
              'functions': <Object?>[
                <String, Object?>{
                  'function_id': 'function.demo',
                  'source_run_id': 'run-1',
                  'name': '演示指令',
                  'description': '复用已成功执行的轨迹',
                  'input_schema': <String, Object?>{
                    'type': 'object',
                    'properties': <String, Object?>{
                      'query': <String, Object?>{
                        'type': 'string',
                        'description': 'Text to search for',
                      },
                    },
                    'required': <String>['query'],
                  },
                  'steps': <Object?>[
                    <String, Object?>{
                      'action': <String, Object?>{
                        'tool': 'click',
                        'args': <String, Object?>{'x': 500, 'y': 500},
                      },
                    },
                  ],
                },
              ],
            },
            'list_run_logs' => <String, Object?>{
              'success': true,
              'count': 1,
              'runs': <Object?>[
                <String, Object?>{
                  'run_id': 'run-1',
                  'goal': '完成演示',
                  'status': 'success',
                  'step_count': 1,
                  'started_at_ms': DateTime(
                    2026,
                    7,
                    31,
                    9,
                    18,
                  ).millisecondsSinceEpoch,
                  'finished_at_ms': DateTime(
                    2026,
                    7,
                    31,
                    9,
                    18,
                    2,
                    345,
                  ).millisecondsSinceEpoch,
                  'diagnostics': <String, Object?>{
                    'duration_ms': 2345,
                    'token_usage': <String, Object?>{
                      'prompt_tokens': 1000,
                      'completion_tokens': 234,
                      'total_tokens': 1234,
                      'call_count': 2,
                      'resolved_model': 'qwen-vl-max',
                    },
                  },
                },
              ],
            },
            'get_function' => <String, Object?>{
              'success': true,
              'function': <String, Object?>{
                'function_id': 'function.demo',
                'name': '演示指令',
                'description': '复用已成功执行的操作',
                'agent_visible': true,
                'input_schema': <String, Object?>{
                  'type': 'object',
                  'properties': <String, Object?>{
                    'query': <String, Object?>{
                      'type': 'string',
                      'description': '要搜索的文本',
                    },
                  },
                  'required': <String>['query'],
                },
                'steps': <Object?>[
                  <String, Object?>{
                    'action': <String, Object?>{
                      'tool': 'click',
                      'args': <String, Object?>{'x': 500, 'y': 500},
                    },
                  },
                ],
              },
            },
            'function.demo' => <String, Object?>{'success': true},
            'save_function' => <String, Object?>{
              'success': true,
              'function_id': 'function.demo',
            },
            'list_app_experiences' => <String, Object?>{
              'success': true,
              'revision': 1,
              'total': 1,
              'has_more': false,
              'next_offset': 1,
              'items': <Object?>[
                <String, Object?>{
                  'id': 'exp-1',
                  'package_name': 'com.example.app',
                  'activity_name': 'MainActivity',
                  'application_label': '示例应用',
                  'status': 'verified',
                  'enabled': true,
                  'source_run_id': 'run-1',
                  'source_step_index': 0,
                  'rule': <String, Object?>{
                    'action': 'click',
                    'target_resource_id': 'com.example.app:id/submit_btn',
                    'target_class': 'android.widget.Button',
                  },
                  'successful_run_ids': <Object?>['run-1', 'run-2'],
                  'failed_run_ids': <Object?>[],
                  'consecutive_failures': 0,
                },
              ],
            },
            'set_app_experience_enabled' => <String, Object?>{
              'success': true,
              'revision': 2,
              'item': <String, Object?>{
                'id': arguments['arguments'] is Map
                    ? (arguments['arguments'] as Map)['experience_id']
                    : 'exp-1',
                'enabled': arguments['arguments'] is Map
                    ? (arguments['arguments'] as Map)['enabled']
                    : true,
                'status': 'verified',
              },
            },
            _ => <String, Object?>{'success': true},
          };
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, (call) async {
          if (call.method == 'isAndroidGuiAccessibilityReady') return true;
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, null);
  });

  testWidgets('Pad execution tabs and lists share the header content column', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1200, 700);
    tester.view.devicePixelRatio = 1;
    tester.view.viewPadding = const FakeViewPadding(bottom: 34);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetViewPadding);
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    final tabs = tester.getRect(find.byType(TabBar));
    final list = find.byType(ListView);
    expect(tabs.width, 840);
    expect(tabs.center.dx, 600);
    expect(tester.getRect(list).width, tabs.width);
    expect((tester.widget<ListView>(list).padding! as EdgeInsets).bottom, 40);
    await tester.tap(find.text('运行记录'));
    await tester.pumpAndSettle();
    expect(find.text('完成演示'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('loads only the active tab with a small first page', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    final functionCalls = toolCalls
        .where((call) => call['name'] == 'list_functions')
        .toList(growable: false);
    expect(functionCalls, hasLength(1));
    expect((functionCalls.single['arguments'] as Map)['limit'], 20);
    expect((functionCalls.single['arguments'] as Map)['offset'], 0);
    expect(toolCalls.where((call) => call['name'] == 'list_run_logs'), isEmpty);

    await tester.tap(find.text('运行记录'));
    await tester.pumpAndSettle();

    expect(
      toolCalls.where((call) => call['name'] == 'list_run_logs'),
      hasLength(1),
    );
  });

  testWidgets('paginates Functions with the backend next offset', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_functions') return null;
      final arguments = call['arguments'] as Map;
      final offset = arguments['offset'] as int;
      final count = offset == 0 ? 20 : 1;
      return <String, Object?>{
        'success': true,
        'count': count,
        'has_more': offset == 0,
        'next_offset': offset + count,
        'functions': List<Object?>.generate(
          count,
          (index) => <String, Object?>{
            'function_id': 'function.page.${offset + index}',
            'name': '分页指令 ${offset + index}',
            'description': '分页测试',
            'steps': <Object?>[],
            'input_schema': <String, Object?>{
              'properties': <String, Object?>{},
            },
          },
        ),
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.fling(find.byType(ListView), const Offset(0, -2400), 1200);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('functions-load-more')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('functions-load-more')));
    await tester.pumpAndSettle();

    expect(find.text('分页指令 20'), findsOneWidget);
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'list_functions' &&
            (call['arguments'] as Map)['limit'] == 20 &&
            (call['arguments'] as Map)['offset'] == 20,
      ),
      isTrue,
    );
  });

  testWidgets('paginates Run Logs with the backend next offset', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_run_logs') return null;
      final arguments = call['arguments'] as Map;
      final offset = arguments['offset'] as int;
      final count = offset == 0 ? 20 : 1;
      return <String, Object?>{
        'success': true,
        'count': count,
        'has_more': offset == 0,
        'next_offset': offset + count,
        'runs': List<Object?>.generate(
          count,
          (index) => <String, Object?>{
            'run_id': 'run-page-${offset + index}',
            'goal': '分页记录 ${offset + index}',
            'status': 'succeeded',
            'step_count': 1,
          },
        ),
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.fling(find.byType(ListView), const Offset(0, -2400), 1200);
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('run-logs-load-more')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('run-logs-load-more')));
    await tester.pumpAndSettle();

    expect(find.text('分页记录 20'), findsOneWidget);
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'list_run_logs' &&
            (call['arguments'] as Map)['limit'] == 20 &&
            (call['arguments'] as Map)['offset'] == 20,
      ),
      isTrue,
    );
  });

  testWidgets('opens Function enhancement in a new Agent conversation', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    ConversationThreadTarget? openedTarget;
    final router = GoRouter(
      routes: [
        GoRoute(
          path: '/',
          builder: (_, __) => const OmniFlowExecutionCenterPage(),
        ),
        GoRoute(
          path: '/home/chat',
          builder: (_, state) {
            final target = state.extra! as ConversationThreadTarget;
            openedTarget = target;
            return const Scaffold(body: Text('agent conversation'));
          },
        ),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(
      MaterialApp.router(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        routerConfig: router,
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(find.text('演示指令'));
    await tester.pumpAndSettle();

    expect(find.text('复用指令详情'), findsOneWidget);
    expect(find.text('参数'), findsOneWidget);
    expect(find.text('步骤'), findsOneWidget);
    expect(find.text('要搜索的文本'), findsOneWidget);
    expect(toolCalls.any((call) => call['name'] == 'get_function'), isTrue);

    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
    await tester.tap(find.byKey(const ValueKey('function-detail-enhance')));
    await tester.pumpAndSettle();
    expect(find.text('agent conversation'), findsOneWidget);
    expect(openedTarget?.mode, ConversationMode.agent);
    expect(openedTarget?.isNewConversation, isTrue);
    expect(openedTarget?.requestKey, isNotEmpty);
    expect(
      openedTarget?.initialMessage,
      allOf(
        contains('function_id: function.demo'),
        contains('source_run_id: run-1'),
        contains('get_function'),
        contains('save_function'),
        contains('enhance=true'),
      ),
    );
    expect(toolCalls.any((call) => call['name'] == 'update_function'), isFalse);
  });

  test('builds a complete Function enhancement Agent request', () {
    final prompt = buildFunctionEnhancementPrompt({
      'function_id': 'function.demo',
      'source_run_id': 'run-1',
      'name': '演示指令',
      'steps': <Object?>[],
    });

    expect(prompt, contains('get_function'));
    expect(prompt, contains('save_function'));
    expect(prompt, contains('enhance=true'));
    expect(prompt, contains('run_id=run-1'));
    expect(prompt, contains('由 OmniFlow 内置的官方增强流程'));
    expect(prompt, contains('不要调用 list_run_logs/get_run_log/get_function'));
    expect(prompt, isNot(contains('"source_run_id":"run-1"')));
    expect(prompt, contains('function_id: function.demo'));
    expect(prompt, contains('不要执行 Function'));
  });

  testWidgets('opens the requested Function directly', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialFunctionId: 'function.demo'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('function-detail-sheet')), findsOneWidget);
    expect(find.text('演示指令'), findsWidgets);
    expect(toolCalls.any((call) => call['name'] == 'get_function'), isTrue);
  });

  testWidgets('uses consistent Chinese labels across the execution center', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('复用指令'), findsOneWidget);
    expect(find.byType(Card), findsNothing);
    expect(find.text('轨迹'), findsNothing);
    expect(find.text('运行记录'), findsOneWidget);
    expect(find.text('RunLog'), findsNothing);
    await tester.tap(find.text('执行'));
    await tester.pumpAndSettle();
    expect(find.text('填写执行参数'), findsOneWidget);
    await tester.enterText(find.byType(TextFormField), 'replay acceptance');
    await tester.tap(find.text('开始执行'));
    await tester.pumpAndSettle();
    expect(
      toolCalls.any(
        (call) =>
            call['name'] == 'function.demo' &&
            (call['arguments'] as Map?)?['query'] == 'replay acceptance',
      ),
      isTrue,
    );
    final replayCall = toolCalls.lastWhere(
      (call) => call['name'] == 'function.demo',
    );
    expect(replayCall['goal'], contains('演示指令'));
    expect(replayCall['goal'], contains('复用已成功执行的轨迹'));
    expect(replayCall['goal'], contains('replay acceptance'));

    await tester.tap(find.text('运行记录'));
    await tester.pumpAndSettle();
    expect(find.byType(Card), findsNothing);
    expect(find.byType(Chip), findsNothing);
    expect(find.text('2026-07-31 09:18:00'), findsOneWidget);
    expect(find.text('2.35 s'), findsOneWidget);
    expect(find.text('模型用量 1.23k'), findsOneWidget);
    expect(find.text('qwen-vl-max'), findsOneWidget);
    expect(find.text('2 次 VLM 调用'), findsOneWidget);
    expect(find.text('成功'), findsOneWidget);
    expect(find.byKey(const ValueKey('run-log-open-run-1')), findsOneWidget);
    // This fixture already links run-1 to function.demo; open it without
    // registering the same run a second time.
    await tester.tap(find.byKey(const ValueKey('run-log-function-run-1')));
    await tester.pumpAndSettle();
    expect(toolCalls.any((call) => call['name'] == 'save_function'), isFalse);
    expect(find.byKey(const ValueKey('function-detail-sheet')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
  });

  testWidgets('shows the runtime error without a Dart Bad state prefix', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'function.demo') return null;
      return <String, Object?>{
        'success': false,
        'error_code': 'FUNCTION_CALL_FAILED',
        'error_message': 'android_gui_accessibility_not_ready',
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('执行'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField), 'replay failure');
    await tester.tap(find.text('开始执行'));
    await tester.pumpAndSettle();

    expect(find.text('android_gui_accessibility_not_ready'), findsOneWidget);
    expect(find.textContaining('Bad state:'), findsNothing);
  });

  testWidgets('shows the Function linked to a RunLog and opens it', (
    tester,
  ) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_functions') return null;
      return <String, Object?>{
        'success': true,
        'functions': <Object?>[
          <String, Object?>{
            'function_id': 'function.demo',
            'source_run_id': 'run-1',
            'name': '演示指令',
            'description': '复用已成功执行的轨迹',
            'input_schema': <String, Object?>{
              'type': 'object',
              'properties': <String, Object?>{},
            },
            'steps': <Object?>[],
          },
        ],
      };
    };
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('查看复用指令'), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('run-log-function-run-1')));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('function-detail-sheet')), findsOneWidget);
    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
  });

  testWidgets(
    'registration uses the saved Function immediately when detail reload fails',
    (tester) async {
      toolResponseOverride = (name, call) {
        if (name == 'list_functions') {
          return <String, Object?>{
            'success': true,
            'count': 0,
            'functions': <Object?>[],
          };
        }
        if (name == 'save_function') {
          return <String, Object?>{
            'success': true,
            'function_id': 'function.registered',
            'function': <String, Object?>{
              'function_id': 'function.registered',
              'source_run_id': 'run-1',
              'name': '刚注册的指令',
              'description': '注册响应已经包含完整 Function',
              'agent_visible': true,
              'input_schema': <String, Object?>{
                'type': 'object',
                'properties': <String, Object?>{},
              },
              'steps': <Object?>[],
            },
          };
        }
        if (name == 'get_function') {
          throw PlatformException(code: 'FUNCTION_NOT_READY');
        }
        return null;
      };

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('注册为复用指令'));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('function-detail-sheet')),
        findsOneWidget,
      );
      expect(find.text('刚注册的指令'), findsWidgets);
      expect(find.text('复用指令'), findsWidgets);
      expect(
        toolCalls.where((call) => call['name'] == 'get_function'),
        isEmpty,
      );
      expect(find.textContaining('StateError: 注册失败'), findsNothing);
    },
  );

  testWidgets('keeps Enhance visible on a narrow phone', (tester) async {
    tester.view.physicalSize = const Size(280, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('演示指令'));
    await tester.pumpAndSettle();

    expect(
      find.byKey(const ValueKey('function-detail-enhance')),
      findsOneWidget,
    );
    expect(find.text('增强'), findsOneWidget);
    expect(find.byKey(const ValueKey('function-detail-run')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('opens directly on the Run Logs tab', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'run_logs'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('完成演示'), findsOneWidget);
    expect(find.byKey(const ValueKey('run-log-open-run-1')), findsOneWidget);
    expect(find.text('演示指令'), findsNothing);
  });

  testWidgets('uses consistent English labels across the execution center', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('en'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Functions'), findsOneWidget);
    expect(find.text('Run Logs'), findsOneWidget);
    expect(find.text('Experiences'), findsOneWidget);
    expect(find.text('Run'), findsOneWidget);
    expect(find.text('复用指令'), findsNothing);

    await tester.tap(find.text('Run Logs'));
    await tester.pumpAndSettle();
    expect(find.text('Succeeded'), findsOneWidget);
    expect(find.text('1.23k tokens'), findsOneWidget);
    expect(find.text('2 VLM calls'), findsOneWidget);
    expect(find.byKey(const ValueKey('run-log-open-run-1')), findsOneWidget);
  });

  testWidgets(
    'opens directly on the Experiences tab and loads experiences lazily',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('示例应用'), findsOneWidget);
      expect(find.text('点击'), findsOneWidget);
      expect(find.text('com.example.app:id/submit_btn'), findsNothing);
      expect(find.text('已验证'), findsOneWidget);

      final experienceCalls = toolCalls
          .where((call) => call['name'] == 'list_app_experiences')
          .toList(growable: false);
      expect(experienceCalls, hasLength(1));
      expect((experienceCalls.single['arguments'] as Map)['limit'], 20);
      expect((experienceCalls.single['arguments'] as Map)['offset'], 0);
      expect(toolCalls.where((call) => call['name'] == 'list_functions'), isEmpty);
      expect(toolCalls.where((call) => call['name'] == 'list_run_logs'), isEmpty);
    },
  );

  testWidgets('shows empty state when no app experiences exist', (tester) async {
    toolResponseOverride = (name, call) {
      if (name == 'list_app_experiences') {
        return <String, Object?>{
          'success': true,
          'revision': 1,
          'total': 0,
          'has_more': false,
          'next_offset': 0,
          'items': <Object?>[],
        };
      }
      return null;
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('暂无应用经验'), findsOneWidget);
  });

  testWidgets('shows error state and retries on refresh', (tester) async {
    var callCount = 0;
    toolResponseOverride = (name, call) {
      if (name == 'list_app_experiences') {
        callCount++;
        if (callCount == 1) {
          return <String, Object?>{
            'success': false,
            'error_message': '网络加载失败',
          };
        }
      }
      return null;
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('网络加载失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);

    await tester.tap(find.text('重试'));
    await tester.pumpAndSettle();

    expect(find.text('示例应用'), findsOneWidget);
  });

  testWidgets(
    'shows busy message when native returns busy without opening permissions',
    (tester) async {
      toolResponseOverride = (name, call) {
        if (name == 'list_app_experiences') {
          return <String, Object?>{
            'success': false,
            'error_code': 'app_experience_busy',
            'message': '任务执行中，任务结束后更新',
          };
        }
        return null;
      };

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('任务结束后更新'), findsOneWidget);
      expect(find.text('任务进行中'), findsOneWidget);
      expect(find.text('需要无障碍权限'), findsNothing);
    },
  );

  testWidgets(
    'opens app experience details sheet on item tap without success rate',
    (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('示例应用'));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('app-experience-detail-sheet')), findsOneWidget);
      expect(find.text('com.example.app:id/submit_btn'), findsOneWidget);
      expect(find.textContaining('成功率'), findsNothing);
      expect(find.textContaining('%'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('paginates app experiences with next offset', (tester) async {
    toolResponseOverride = (name, call) {
      if (name != 'list_app_experiences') return null;
      final arguments = call['arguments'] as Map;
      final offset = arguments['offset'] as int;
      final count = offset == 0 ? 20 : 1;
      return <String, Object?>{
        'success': true,
        'revision': 1,
        'total': 21,
        'has_more': offset == 0,
        'next_offset': offset + count,
        'items': List<Object?>.generate(
          count,
          (index) => <String, Object?>{
            'id': 'exp-${offset + index}',
            'package_name': 'com.example.app',
            'activity_name': 'MainActivity',
            'application_label': '应用 ${offset + index}',
            'status': 'verified',
            'enabled': true,
            'rule': <String, Object?>{
              'action': 'click',
              'target_resource_id': 'com.example.app:id/btn_${offset + index}',
            },
          },
        ),
      };
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('应用 0'), findsOneWidget);
    await tester.fling(
      find.byKey(const ValueKey('experiences-list')),
      const Offset(0, -2400),
      1200,
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('experiences-load-more')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('experiences-load-more')));
    await tester.pumpAndSettle();

    final calls = toolCalls
        .where((call) => call['name'] == 'list_app_experiences')
        .toList(growable: false);
    expect(calls, hasLength(2));
    expect((calls[1]['arguments'] as Map)['offset'], 20);
    expect(find.text('应用 20'), findsOneWidget);
  });

  testWidgets('pagination revision change resets and drops old page', (
    tester,
  ) async {
    var callCount = 0;
    toolResponseOverride = (name, call) {
      if (name != 'list_app_experiences') return null;
      final arguments = call['arguments'] as Map;
      final offset = arguments['offset'] as int;
      callCount++;
      if (callCount == 1) {
        return <String, Object?>{
          'success': true,
          'revision': 1,
          'total': 21,
          'has_more': true,
          'next_offset': 20,
          'items': List<Object?>.generate(
            20,
            (index) => <String, Object?>{
              'id': 'exp-old-$index',
              'package_name': 'com.example.app',
              'application_label': '旧版本 $index',
              'status': 'verified',
              'enabled': true,
              'rule': <String, Object?>{'action': 'click'},
            },
          ),
        };
      } else if (callCount == 2) {
        // Next page arrives with a different revision 2!
        return <String, Object?>{
          'success': true,
          'revision': 2,
          'total': 5,
          'has_more': false,
          'next_offset': 21,
          'items': <Object?>[
            <String, Object?>{
              'id': 'exp-new-0',
              'package_name': 'com.example.app',
              'application_label': '新版本 0',
              'status': 'trial',
              'enabled': true,
              'rule': <String, Object?>{'action': 'click'},
            },
          ],
        };
      } else {
        // Reloaded from offset 0 with revision 2
        return <String, Object?>{
          'success': true,
          'revision': 2,
          'total': 1,
          'has_more': false,
          'next_offset': 1,
          'items': <Object?>[
            <String, Object?>{
              'id': 'exp-fresh-0',
              'package_name': 'com.example.app',
              'application_label': '全新版本项',
              'status': 'trial',
              'enabled': true,
              'rule': <String, Object?>{'action': 'click'},
            },
          ],
        };
      }
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('旧版本 0'), findsOneWidget);

    await tester.fling(
      find.byKey(const ValueKey('experiences-list')),
      const Offset(0, -2400),
      1200,
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('experiences-load-more')), findsOneWidget);
    await tester.tap(find.byKey(const ValueKey('experiences-load-more')));
    await tester.pumpAndSettle();

    // Old page items should be dropped, replaced by reloaded snapshot
    expect(find.text('全新版本项'), findsOneWidget);
    expect(find.text('旧版本 0'), findsNothing);
  });

  testWidgets(
    'toggle switch awaits response using Completer and disables duplicate taps',
    (tester) async {
      final completer = Completer<Map<String, Object?>>();
      toolResponseOverride = (name, call) {
        if (name == 'set_app_experience_enabled') {
          return completer.future;
        }
        return null;
      };

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
        ),
      );
      await tester.pumpAndSettle();

      final switchFinder = find.byType(Switch);
      expect(tester.widget<Switch>(switchFinder).value, isTrue);
      expect(tester.widget<Switch>(switchFinder).onChanged, isNotNull);

      // Tap switch while response is pending
      await tester.tap(switchFinder);
      await tester.pump();

      // While waiting for network response, switch must be disabled and old value retained
      expect(tester.widget<Switch>(switchFinder).value, isTrue);
      expect(tester.widget<Switch>(switchFinder).onChanged, isNull);

      // Simulate failure
      completer.complete(<String, Object?>{
        'success': false,
        'error_message': '写入失败',
      });
      await tester.pumpAndSettle();

      // Value remains unchanged (no optimistic change) and switch re-enabled
      expect(tester.widget<Switch>(switchFinder).value, isTrue);
      expect(tester.widget<Switch>(switchFinder).onChanged, isNotNull);
      expect(find.text('写入失败'), findsOneWidget);
    },
  );

  testWidgets(
    'toggle switch success updates state and preserves application_label',
    (tester) async {
      toolResponseOverride = (name, call) {
        if (name == 'set_app_experience_enabled') {
          // Item returned without application_label
          return <String, Object?>{
            'success': true,
            'revision': 2,
            'item': <String, Object?>{
              'id': 'exp-1',
              'enabled': false,
              'status': 'verified',
            },
          };
        }
        return null;
      };

      await tester.pumpWidget(
        const MaterialApp(
          locale: Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
        ),
      );
      await tester.pumpAndSettle();

      final switchFinder = find.byType(Switch);
      expect(tester.widget<Switch>(switchFinder).value, isTrue);

      await tester.tap(switchFinder);
      await tester.pumpAndSettle();

      expect(tester.widget<Switch>(switchFinder).value, isFalse);
      expect(find.text('已停用'), findsOneWidget);
      // application_label preserved!
      expect(find.text('示例应用'), findsOneWidget);
      expect(find.text('已停用应用经验'), findsOneWidget);
    },
  );

  testWidgets('revision conflict on toggle refreshes list without retrying set', (
    tester,
  ) async {
    var setCalledCount = 0;
    var listCalledCount = 0;
    toolResponseOverride = (name, call) {
      if (name == 'set_app_experience_enabled') {
        setCalledCount++;
        return <String, Object?>{
          'success': false,
          'error_code': 'app_experience_revision_conflict',
          'message': 'revision mismatch',
        };
      }
      if (name == 'list_app_experiences') {
        listCalledCount++;
      }
      return null;
    };

    await tester.pumpWidget(
      const MaterialApp(
        locale: Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: OmniFlowExecutionCenterPage(initialTab: 'experiences'),
      ),
    );
    await tester.pumpAndSettle();

    final initialListCalls = listCalledCount;
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();

    // Exactly one set call, no retry
    expect(setCalledCount, 1);
    // Triggered refresh
    expect(listCalledCount, initialListCalls + 1);
    expect(find.text('版本已变更，已刷新最新经验'), findsOneWidget);
  });

  testWidgets('preserves navigation and conversation identity on return', (
    tester,
  ) async {
    final router = GoRouter(
      initialLocation: '/chat',
      routes: [
        GoRoute(
          path: '/chat',
          builder: (context, state) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => context.push('/execution_center'),
                child: const Text('前往执行中心'),
              ),
            ),
          ),
        ),
        GoRoute(
          path: '/execution_center',
          builder: (context, state) => const OmniFlowExecutionCenterPage(),
        ),
      ],
    );

    await tester.pumpWidget(
      MaterialApp.router(
        routerConfig: router,
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('前往执行中心'), findsOneWidget);

    await tester.tap(find.text('前往执行中心'));
    await tester.pumpAndSettle();

    expect(find.text('执行中心'), findsOneWidget);
    await tester.tap(find.text('应用经验'));
    await tester.pumpAndSettle();
    expect(find.text('示例应用'), findsOneWidget);

    // Pop back
    tester.state<NavigatorState>(find.byType(Navigator).last).pop();
    await tester.pumpAndSettle();

    expect(find.text('前往执行中心'), findsOneWidget);
    expect(find.text('执行中心'), findsNothing);
  });
}
