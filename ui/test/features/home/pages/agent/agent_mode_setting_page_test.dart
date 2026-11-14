import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/features/home/pages/agent/agent_mode_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/agent_runtime_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/navigation/open_container.dart';
import 'package:ui/widgets/settings_detail_sheet.dart';
import 'package:ui/widgets/settings_ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const agentRuntimeChannel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  const pluginPlatformChannel = MethodChannel(
    'cn.com.omnimind.bot/PluginPlatform',
  );

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method != 'agent/list') return null;
          return <String, dynamic>{
            'agents': <Map<String, dynamic>>[
              _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
              _agent(
                'claude-code-acp',
                'Claude Code',
                'claude-agent-acp',
                'missing',
              ),
              _agent(
                'opencode-acp',
                'OpenCode',
                'opencode',
                'offline',
                arguments: const ['acp'],
              ),
              _agent(
                'pi-acp',
                'Pi',
                'omnibot-pi-acp',
                'unchecked',
                managedAdapter: true,
                lastCheckError: 'Harness 未初始化，请点击“安装官方 Harness”准备运行组件。',
              ),
            ],
          };
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginPlatformChannel, (call) async {
          if (call.method == 'listActions') return <dynamic>[];
          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(pluginPlatformChannel, null);
    expect(AgentRuntimeService.agentPackageOperations, isEmpty);
  });

  for (final scenario in [
    (width: 1100.0, scale: 1.0, inline: true),
    (width: 500.0, scale: 1.0, inline: false),
    (width: 1100.0, scale: 2.0, inline: false),
  ]) {
    testWidgets(
      'Agent maintenance adapts at ${scenario.width}dp and ${scenario.scale}x text',
      (tester) async {
        tester.view.physicalSize = Size(scenario.width, 1000);
        tester.view.devicePixelRatio = 1;
        tester.platformDispatcher.textScaleFactorTestValue = scenario.scale;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        final calls = <MethodCall>[];
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
              calls.add(call);
              if (call.method != 'agent/list') return null;
              return {
                'distributionId': 'pad-layout-test',
                'agents': [
                  {
                    ..._agent(
                      'pi-acp',
                      'Pi',
                      'omnibot-pi-acp',
                      'unchecked',
                      managedAdapter: true,
                    ),
                    'uninstallable': true,
                  },
                ],
              };
            });
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            locale: const Locale('zh'),
            home: const AgentModeSettingPage(embedded: true),
          ),
        );
        await tester.pumpAndSettle();
        final action = find.byKey(const Key('agent-check-pi-acp'));
        final uninstall = find.byKey(
          const Key('agent-uninstall-pi-acp'),
        );
        final navigation = find.byKey(
          const Key('agent-navigation-pi-acp'),
        );
        for (final control in [action, uninstall, navigation]) {
          expect(
            tester.getSize(control).shortestSide,
            greaterThanOrEqualTo(48),
          );
        }
        final actionBounds = tester.getRect(action);
        final navigationBounds = tester.getRect(navigation);
        if (scenario.inline) {
          expect(
            actionBounds.center.dy,
            closeTo(navigationBounds.center.dy, 1),
          );
          expect(
            actionBounds.left,
            greaterThan(tester.getRect(find.text('Pi')).right),
          );
        } else {
          expect(
            actionBounds.top,
            greaterThanOrEqualTo(navigationBounds.bottom),
          );
        }
        expect(tester.widget<IconButton>(navigation).onPressed, isNotNull);
        // Reflowing a row must not remove the existing uninstall confirmation.
        await tester.tap(uninstall);
        await tester.pumpAndSettle();
        expect(
          find.byKey(const ValueKey('agent-uninstall-confirm-pi-acp')),
          findsOneWidget,
        );
        expect(
          calls.where((call) => call.method == 'agent/uninstall'),
          isEmpty,
        );
        await tester.tap(
          find.byKey(const Key('agent-uninstall-cancel-pi-acp')),
        );
        await tester.pumpAndSettle();
        expect(
          calls.where((call) => call.method == 'agent/uninstall'),
          isEmpty,
        );
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('embedded contacts keep search and filters when reactivated', (
    tester,
  ) async {
    var catalogReads = 0;
    var probeReads = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/refresh') probeReads++;
          if (call.method != 'agent/list') return null;
          catalogReads++;
          return {
            'agents': [
              _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
              _agent('claude-code-acp', 'Claude Code', 'claude', 'offline'),
              if (catalogReads > 1)
                _agent('custom-codex', 'Codex Custom', 'custom', 'online'),
            ],
          };
        });
    Widget page(bool active) => MaterialApp(
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('zh'),
      home: AgentModeSettingPage(embedded: true, active: active),
    );
    await tester.pumpWidget(page(true));
    await tester.pumpAndSettle();
    expect(find.text('Agents'), findsOneWidget);
    expect(find.byIcon(LucideIcons.chevronLeft), findsNothing);
    expect(
      tester.getSize(find.byKey(const ValueKey('agent-avatar-Codex'))),
      const Size(44, 44),
    );
    expect(
      tester.getTopLeft(find.text('Claude Code')).dy,
      lessThan(tester.getTopLeft(find.text('Codex')).dy),
    );
    await tester.tap(find.text('可用 1'));
    await tester.pumpAndSettle();
    expect(find.text('Claude Code'), findsNothing);
    expect(find.byKey(const Key('agent-contact-search')), findsNothing);
    await tester.tap(find.byKey(const Key('agent-search-button')));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byKey(const Key('agent-contact-search')),
      'codex',
    );
    await tester.pumpAndSettle();
    final searchField = tester.widget<TextField>(
      find.byKey(const Key('agent-contact-search')),
    );
    expect(searchField.focusNode!.hasFocus, isTrue);
    expect(searchField.decoration!.suffixIcon, isNull);
    expect(find.byIcon(LucideIcons.circleX), findsNothing);
    expect(find.byKey(const Key('agent-close-search')), findsOneWidget);
    await tester.pumpWidget(page(false));
    await tester.pumpAndSettle();
    expect(catalogReads, 1);
    expect(
      ModalRoute.of(tester.element(find.byType(AgentModeSettingPage)))!
          .popDisposition,
      RoutePopDisposition.bubble,
    );
    await tester.pumpWidget(page(true));
    await tester.pumpAndSettle();
    expect(catalogReads, 2);
    expect(probeReads, 0);
    expect(find.text('Codex Custom'), findsOneWidget);
    expect(find.text('Claude Code'), findsNothing);
    expect(find.text('codex'), findsOneWidget);
    expect(
      ModalRoute.of(tester.element(find.byType(AgentModeSettingPage)))!
          .popDisposition,
      RoutePopDisposition.doNotPop,
    );
    await tester.enterText(
      find.byKey(const Key('agent-contact-search')),
      'no match',
    );
    await tester.pumpAndSettle();
    expect(find.text('没有匹配的 Agent'), findsOneWidget);
    expect(find.text('Codex'), findsNothing);
    await tester.tap(find.byKey(const Key('agent-close-search')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('agent-contact-search')), findsNothing);
    expect(find.text('Codex'), findsOneWidget);
    expect(find.text('Codex Custom'), findsOneWidget);
    expect(
      find.text('Claude Code'),
      findsNothing,
    ); // Available filter survives.
    await tester.tap(find.byKey(const Key('agent-search-button')));
    await tester.pumpAndSettle();
    expect(
      tester
          .widget<TextField>(find.byKey(const Key('agent-contact-search')))
          .controller!
          .text,
      isEmpty,
    );
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('agent-contact-search')), findsNothing);
  });

  testWidgets(
    'Agent rows expand through GoRouter and preserve return refresh',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(390, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);
      var catalogReads = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method != 'agent/list') return null;
            catalogReads++;
            return {
              'agents': [
                _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
              ],
            };
          });
      final admittedPaths = <String>[];
      Page<dynamic> destination(GoRouterState state) {
        admittedPaths.add(state.uri.path);
        final source = (state.extra! as Map)[omniContainerExtraKey];
        expect(source, isA<OmniContainerSource>());
        return (source as OmniContainerSource).createPage<dynamic>(
          key: state.pageKey,
          child: const Scaffold(
            key: Key('agent-row-destination'),
            body: Text('Agent destination'),
          ),
        );
      }

      final router = GoRouter(
        navigatorKey: GoRouterManager.rootNavigatorKey,
        routes: [
          GoRoute(
            path: '/',
            builder: (_, _) => const AgentModeSettingPage(embedded: true),
          ),
          GoRoute(
            path: '/home/agent_config/:agentId',
            pageBuilder: (_, state) => destination(state),
          ),
        ],
      );
      addTearDown(router.dispose);
      await tester.pumpWidget(
        MaterialApp.router(
          routerConfig: router,
          theme: AppTheme.lightTheme,
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
        ),
      );
      await tester.pumpAndSettle();
      expect(catalogReads, 1);
      for (final scenario in [
        (
          id: 'codex-acp',
          path: '/home/agent_config/codex-acp',
          result: false,
          reload: false,
        ),
        (
          id: 'codex-acp',
          path: '/home/agent_config/codex-acp',
          result: true,
          reload: true,
        ),
      ]) {
        final row = find.byKey(Key('agent-config-${scenario.id}'));
        final source = find.ancestor(
          of: row,
          matching: find.byType(OmniOpenContainer),
        );
        final sourceRect = tester.getRect(source);
        final readsBeforeOpen = catalogReads;
        await tester.tap(row);
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 100));
        expect(admittedPaths.last, scenario.path);
        final detail = find.byKey(const Key('agent-row-destination'));
        expect(detail, findsOneWidget);
        final route = ModalRoute.of(tester.element(detail))!;
        expect(route.transitionDuration, const Duration(milliseconds: 300));
        expect(route.animation!.isAnimating, isTrue);
        final full = Offset.zero & tester.getSize(find.byType(MaterialApp));
        final opening = Rect.lerp(
          sourceRect,
          full,
          Curves.fastOutSlowIn.transform(route.animation!.value),
        )!;
        expect(tester.getTopLeft(detail).dx, closeTo(opening.left, 0.01));
        expect(tester.getTopLeft(detail).dy, closeTo(opening.top, 0.01));
        await tester.pumpAndSettle();
        expect(catalogReads, readsBeforeOpen);
        router.pop(scenario.result);
        await tester.pumpAndSettle();
        expect(detail, findsNothing);
        expect(router.canPop(), isFalse);
        expect(tester.getRect(source), sourceRect);
        expect(catalogReads, readsBeforeOpen + (scenario.reload ? 1 : 0));
        expect(tester.takeException(), isNull);
      }
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );

  for (final brightness in Brightness.values) {
    for (final scale in [1.0, 1.5, 2.0]) {
      testWidgets(
        'settings groups fit 320dp with $brightness and text scale $scale',
        (tester) async {
          tester.view.physicalSize = const Size(320, 740);
          tester.view.devicePixelRatio = 1;
          tester.view.padding = const FakeViewPadding(bottom: 24);
          addTearDown(tester.view.resetPhysicalSize);
          addTearDown(tester.view.resetDevicePixelRatio);
          addTearDown(tester.view.resetPadding);
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
              .setMockMethodCallHandler(pluginPlatformChannel, (call) async {
                if (call.method != 'listActions') return null;
                return [
                  {
                    'id': 'open_opencode_web',
                    'pluginId': 'com.omnimind.agent-web',
                    'displayName': 'OpenCode Web',
                    'description':
                        'Open the local assistant workspace in your browser',
                    'presentation': {'placement': 'agent_settings'},
                  },
                ];
              });
          await tester.pumpWidget(
            MaterialApp(
              theme: brightness == Brightness.dark
                  ? AppTheme.darkTheme
                  : AppTheme.lightTheme,
              localizationsDelegates: AppLocalizations.localizationsDelegates,
              supportedLocales: AppLocalizations.supportedLocales,
              locale: const Locale('en'),
              builder: (context, child) => MediaQuery(
                data: MediaQuery.of(context)
                    .copyWith(textScaler: TextScaler.linear(scale)),
                child: child!,
              ),
              home: const AgentModeSettingPage(embedded: true),
            ),
          );
          await tester.pumpAndSettle();
          final row = find.byKey(const Key('agent-config-codex-acp'));
          expect(tester.getRect(row).left, 22);
          expect(tester.getRect(row).right, 298);
          expect(tester.widget<Text>(find.text('Codex')).style?.fontSize, 15);
          expect(tester.takeException(), isNull);
          await tester.scrollUntilVisible(
            find.byKey(
              const Key(
                'plugin-action-button-com.omnimind.agent-web/open_opencode_web',
              ),
            ),
            180,
          );
          await tester.pumpAndSettle();
          expect(find.text('OpenCode Web'), findsOneWidget);
          expect(tester.takeException(), isNull);
          await tester.scrollUntilVisible(
            find.byKey(const Key('agent-version-details')),
            180,
          );
          await tester.pumpAndSettle();
          expect(
            tester
                .getRect(find.byKey(const Key('agent-version-details')))
                .bottom,
            lessThanOrEqualTo(716),
          );
          expect(tester.takeException(), isNull);
          await tester.tap(find.byKey(const Key('agent-version-details')));
          await tester.pumpAndSettle();
          expect(find.byType(SettingsDetailSheet), findsOneWidget);
          expect(find.text('Version not checked'), findsOneWidget);
          expect(tester.takeException(), isNull);
          await tester.binding.handlePopRoute();
          await tester.pumpAndSettle();
          await tester.tap(find.byKey(const Key('agent-search-button')));
          await tester.pumpAndSettle();
          await tester.enterText(
            find.byKey(const Key('agent-contact-search')),
            'Pi',
          );
          await tester.pumpAndSettle();
          expect(find.byKey(const Key('agent-config-pi-acp')), findsOneWidget);
          expect(find.text('Codex'), findsNothing);
          expect(find.byKey(const Key('agent-refresh')), findsNothing);
          expect(find.byKey(const Key('agent-shared-model')), findsNothing);
          await tester.tap(find.byKey(const Key('agent-close-search')));
          await tester.pumpAndSettle();
          await tester.scrollUntilVisible(
            find.byKey(const Key('agent-refresh')),
            180,
          );
          await tester.pumpAndSettle();
          await tester.scrollUntilVisible(
            find.byKey(const Key('agent-shared-model')),
            -180,
          );
          await tester.pumpAndSettle();
          expect(find.text('General'), findsOneWidget);
          expect(tester.takeException(), isNull);
        },
      );
    }
  }


  testWidgets('installer exceptions remain in a readable result sheet', (
    tester,
  ) async {
    var calls = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          if (call.method == 'agent/list') {
            return {
              'agents': [
                _agent(
                  'codex-acp',
                  'Codex',
                  'codex-acp',
                  'online',
                  managedAdapter: true,
                ),
              ],
            };
          }
          if (call.method == 'agent/prepare') {
            calls++;
            expect((call.arguments as Map)['force'], isTrue);
            throw PlatformException(
              code: 'INSTALL_FAILED',
              message: 'DEPTH_ZERO_SELF_SIGNED_CERT',
            );
          }
          return null;
        });
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('zh'),
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
    await tester.pumpAndSettle();
    await tester.pump(const Duration(seconds: 10));
    expect(find.byType(SettingsDetailSheet), findsOneWidget);
    expect(find.text('安装未完成'), findsOneWidget);
    expect(find.textContaining('网络是否需要登录'), findsOneWidget);
    expect(find.textContaining('SELF_SIGNED_CERT'), findsNothing);
    expect(calls, 1);
  });

  for (final entry in <String, String>{
    'Dispatch Model Provider is not configured.': '请先在模型设置中选择一个可用模型，再启动助手。',
    'Dispatch Model Provider has no usable credentials.':
        '模型连接验证失败，请在模型设置中检查接口地址和密钥。',
    'Claude Code requires an Anthropic-compatible Provider endpoint.':
        '当前模型连接不适用于这个助手，请更换模型连接或助手。',
    'Harness preparation is already running for codex-acp.':
        '已有助手正在安装，请等待完成后再试。',
  }.entries) {
    testWidgets(
      'assistant error offers an action without internal details: ${entry.key}',
      (tester) async {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
              if (call.method != 'agent/list') return null;
              return <String, dynamic>{
                'agents': [
                  _agent(
                    'codex-acp',
                    'Codex',
                    'codex-acp',
                    'offline',
                    lastCheckError: entry.key,
                  ),
                ],
              };
            });
        await tester.pumpWidget(
          MaterialApp(
            theme: AppTheme.lightTheme,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            supportedLocales: AppLocalizations.supportedLocales,
            locale: const Locale('zh'),
            home: const AgentModeSettingPage(),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.text(entry.key), findsNothing);
        expect(find.text(entry.value), findsOneWidget);
      },
    );
  }

  testWidgets(
    'installed initialization errors are not mislabeled Dispatch waiting and retry runs again',
    (tester) async {
      var attempts = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method == 'agent/list') {
              return <String, dynamic>{
                'agents': [
                  _agent(
                    'codex-acp',
                    'Codex',
                    'codex-acp',
                    'missing',
                    managedAdapter: true,
                  ),
                ],
              };
            }
            if (call.method == 'agent/prepare') {
              expect((call.arguments as Map)['force'], isTrue);
              attempts++;
              return <String, dynamic>{
                'ok': attempts > 1,
                'agent': {'installed': true},
                'error': 'ACP process exited unexpectedly',
                'capabilities': <String, dynamic>{},
              };
            }
            return null;
          });
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: const AgentModeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      if (find.text('展开').evaluate().isNotEmpty) {
        await tester.tap(find.text('展开').first);
        await tester.pumpAndSettle();
      }
      await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
      await tester.pumpAndSettle();
      expect(find.text('安装已完成，但助手未能启动'), findsOneWidget);
      expect(find.textContaining('等待 Dispatch'), findsNothing);
      expect(find.text('ACP process exited unexpectedly'), findsNothing);
      expect(find.text('助手未能启动，请重试；若仍失败，可重新安装。'), findsOneWidget);
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
      await tester.pumpAndSettle();
      expect(attempts, 2);
      expect(find.text('助手安装成功'), findsOneWidget);
    },
  );

  testWidgets('shows the managed ACP Agent catalog without retired agents', (
    tester,
  ) async {
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
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Codex'), findsOneWidget);
    expect(find.text('Claude Code'), findsNothing);
    expect(find.text('Gemini CLI'), findsNothing);
    expect(find.text('DeepSeek Harness'), findsNothing);
    expect(find.text('OpenCode'), findsOneWidget);
    expect(find.text('Pi'), findsOneWidget);
    expect(find.textContaining('Pi ACP Agent'), findsNothing);
    expect(find.textContaining('ACP initialize'), findsNothing);
    expect(find.text('可用'), findsWidgets);
    expect(find.text('未安装'), findsNothing);
    expect(find.text('启动失败'), findsOneWidget);
    expect(find.text('全部 4'), findsOneWidget);
    expect(find.text('已启用的其他 Agent'), findsOneWidget);
    expect(find.text('添加其他 Agent'), findsOneWidget);
    expect(find.text('预置 Agent'), findsNothing);
    final expandTile = find.byKey(const Key('agent-expand-add-others-tile'));
    final toggleButton = find.byKey(const Key('agent-toggle-add-others'));
    if (expandTile.evaluate().isNotEmpty) {
      await tester.tap(expandTile);
      await tester.pumpAndSettle();
    } else if (toggleButton.evaluate().isNotEmpty) {
      await tester.tap(toggleButton);
      await tester.pumpAndSettle();
    }
    expect(find.text('Claude Code'), findsOneWidget);
    expect(find.text('未安装'), findsOneWidget);
    expect(find.text('官方 Agent'), findsNothing);
    expect(find.text('官方'), findsNothing);
    expect(find.textContaining('统一 API'), findsNothing);
    expect(find.byType(PopupMenuButton<String>), findsNothing);
    expect(find.text('初始化检测'), findsNothing);
    expect(find.text('重新检测'), findsOneWidget);
    expect(find.byTooltip('重新检测'), findsOneWidget);
    expect(find.text('重新安装'), findsOneWidget);
    expect(find.text('配置'), findsNothing);
    expect(find.byKey(const Key('agent-navigation-codex-acp')), findsOneWidget);
    expect(find.text('安装'), findsNothing);
    // All assistants retain configuration access, including failed startup.
    // Installation stays on the separate action button.
    expect(find.byIcon(LucideIcons.chevronRight), findsNWidgets(7));
    expect(find.byType(OmniSettingsGroup), findsNWidgets(4));
    expect(find.text('4 位 Agent'), findsNothing);
    expect(find.text('版本未检测'), findsNothing);
    expect(
      tester.getCenter(find.byKey(const Key('agent-check-pi-acp'))).dy,
      closeTo(
        tester
            .getCenter(find.byKey(const Key('agent-navigation-pi-acp')))
            .dy,
        1,
      ),
    );
    expect(find.text('远程 PC Bridge'), findsOneWidget);
    expect(find.text('通用配置'), findsOneWidget);
    expect(find.text('维护'), findsOneWidget);
    expect(find.text('使用'), findsNothing);
    expect(find.text('当前使用'), findsNothing);
    expect(find.text('Use Agent'), findsNothing);
    expect(find.text('Selected'), findsNothing);
  });

  testWidgets('shows Agent check results in the shared settings detail card', (
    tester,
  ) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
          switch (call.method) {
            case 'agent/list':
              return <String, dynamic>{
                'agents': <Map<String, dynamic>>[
                  _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
                ],
              };
            case 'agent/test':
              expect(call.arguments, <String, Object?>{'agentId': 'codex-acp'});
              return <String, dynamic>{
                'ok': true,
                'capabilities': <String, dynamic>{
                  'prompt': true,
                  'tools': <String>['read', 'edit'],
                },
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
        home: const AgentModeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('agent-check-codex-acp')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    final resultSheet = find.byKey(
      const ValueKey('agent-check-result-codex-acp'),
    );
    expect(resultSheet, findsOneWidget);
    expect(find.byType(SettingsDetailSheet), findsOneWidget);
    expect(
      find.byWidgetPredicate((widget) => widget is AlertDialog),
      findsNothing,
    );
    expect(find.text('助手检查通过'), findsOneWidget);
    expect(find.textContaining('prompt: true'), findsNothing);
    expect(find.text('助手已准备好，可以开始对话。'), findsOneWidget);
    expect(find.text('完成'), findsNothing);
    expect(tester.getSize(resultSheet).width, 640);

    await tester.tapAt(const Offset(20, 20));
    await tester.pumpAndSettle();
    expect(resultSheet, findsNothing);
  });

  testWidgets(
    'focused custom Agent fields can be cancelled or dismissed without errors',
    (tester) async {
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
          home: const AgentModeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      final addButton = find.byTooltip('添加自定义 ACP Agent');
      await tester.tap(addButton);
      await tester.pumpAndSettle();
      final dialog = find.byWidgetPredicate((widget) => widget is AlertDialog);
      final dialogFields = find.descendant(
        of: dialog,
        matching: find.byType(TextField),
      );
      await tester.tap(dialogFields.first);
      await tester.enterText(dialogFields.first, 'Custom Agent');
      await tester.tap(find.widgetWithText(TextButton, '取消'));
      await tester.pumpAndSettle();

      expect(find.text('添加自定义 ACP Agent'), findsNothing);
      expect(tester.takeException(), isNull);

      await tester.tap(addButton);
      await tester.pumpAndSettle();
      final reopenedDialogFields = find.descendant(
        of: find.byWidgetPredicate((widget) => widget is AlertDialog),
        matching: find.byType(TextField),
      );
      await tester.tap(reopenedDialogFields.at(1));
      await tester.enterText(reopenedDialogFields.at(1), '/bin/agent');
      await tester.binding.handlePopRoute();
      await tester.pumpAndSettle();

      expect(find.text('添加自定义 ACP Agent'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'managed Harness installation keeps unrelated settings interactive',
    (tester) async {
      final preparation = Completer<Map<String, dynamic>>();
      var preparationCalls = 0;
      addTearDown(() {
        if (!preparation.isCompleted) {
          preparation.complete(<String, dynamic>{
            'ok': false,
            'error': 'test cleanup',
          });
        }
      });
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            switch (call.method) {
              case 'agent/list':
                return <String, dynamic>{
                  'agents': <Map<String, dynamic>>[
                    _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
                    _agent(
                      'pi-acp',
                      'Pi',
                      'omnibot-pi-acp',
                      'missing',
                      managedAdapter: true,
                    ),
                  ],
                };
              case 'agent/prepare':
                preparationCalls += 1;
                expect(call.arguments, <String, Object?>{
                  'agentId': 'pi-acp',
                  'force': true,
                });
                return preparation.future;
            }
            return null;
          });
      tester.view.physicalSize = const Size(1080, 2200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      Widget buildPage() => MaterialApp(
        theme: AppTheme.lightTheme,
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('zh'),
        home: const AgentModeSettingPage(),
      );

      await tester.pumpWidget(buildPage());
      await tester.pumpAndSettle();
      if (find.text('展开').evaluate().isNotEmpty) {
        await tester.tap(find.text('展开').first);
        await tester.pumpAndSettle();
      }

      await tester.tap(find.byKey(const Key('agent-check-pi-acp')));
      await tester.pump();
      // The page-ready gate publishes the preparation stream after the frame.
      await tester.pump();

      expect(find.text('后台安装 / 升级中'), findsOneWidget);
      expect(
        tester
            .widget<IconButton>(
              find.byKey(const Key('agent-navigation-pi-acp')),
            )
            .onPressed,
        isNull,
      );
      await tester.tap(find.byTooltip('添加自定义 ACP Agent'));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('添加自定义 ACP Agent'), findsOneWidget);

      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
      await tester.pumpWidget(buildPage());
      await tester.pump(const Duration(milliseconds: 500));
      await tester.pump();
      if (find.text('展开').evaluate().isNotEmpty) {
        await tester.tap(find.text('展开').first);
        // 使用有界 pump 等待展开动画，不因后台安装动画调用 pumpAndSettle
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));
      }
      expect(find.text('后台安装 / 升级中'), findsOneWidget);

      await tester.tap(find.byKey(const Key('agent-navigation-pi-acp')));
      await tester.pump();
      expect(preparationCalls, 1);

      preparation.complete(<String, dynamic>{
        'ok': false,
        'error': 'expected test completion',
      });
      // 安装任务结算后，指示器停止，等待完成所有清理
      await tester.pumpAndSettle();
      expect(find.text('后台安装 / 升级中'), findsNothing);
    },
  );

  testWidgets(
    'custom unmanaged Agent capabilities remain available in details',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method != 'agent/list') return null;
            return {
              'agents': [
                {
                  ..._agent(
                    'custom-acp',
                    'Custom Agent',
                    'custom-agent',
                    'online',
                  ),
                  'builtIn': false,
                  'capabilities': {
                    'plugin': {
                      'supported': true,
                      'authoring': true,
                      'installViaHarness': true,
                    },
                  },
                },
              ],
            };
          });
      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: const AgentModeSettingPage(embedded: true),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('已启用的其他 Agent'), findsOneWidget);
      expect(find.text('预置 Agent'), findsNothing);
      expect(find.text('自定义 Agent'), findsNothing);
      expect(find.text('插件：可通过助手创建和安装'), findsNothing);
      await tester.scrollUntilVisible(
        find.byKey(const Key('agent-version-details')),
        160,
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('agent-version-details')));
      await tester.pumpAndSettle();
      expect(find.text('插件：可通过助手创建和安装'), findsOneWidget);
      expect(
        find.descendant(
          of: find.byType(SettingsDetailSheet),
          matching: find.text('Custom Agent'),
        ),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    },
  );


  testWidgets(
    'Agent configuration groups: Xiaowan, enabled other agents, and collapsed add other agents',
    (tester) async {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            calls.add(call);
            if (call.method != 'agent/list') return null;
            return <String, dynamic>{
              'agents': <Map<String, dynamic>>[
                _agent('xiaowan-acp', '小万', 'omnibot-xiaowan-acp', 'online'),
                _agent('codex-acp', 'Codex', 'codex-acp', 'online'),
                _agent(
                  'opencode-acp',
                  'OpenCode',
                  'opencode',
                  'offline',
                  arguments: const ['acp'],
                ),
                _agent(
                  'codex-remote',
                  'Codex (Remote)',
                  'codex-remote',
                  'online',
                ),
                _agent(
                  'claude-code-acp',
                  'Claude Code',
                  'claude-agent-acp',
                  'missing',
                ),
                {
                  ..._agent('pi-acp', 'Pi', 'omnibot-pi-acp', 'unchecked'),
                  'enabled': false,
                },
              ],
            };
          });

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: const AgentModeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      // Group 1: 小万
      expect(find.text('小万'), findsWidgets);
      expect(find.byKey(const Key('agent-config-xiaowan-acp')), findsOneWidget);

      // Group 2: 已启用的其他 Agent (local & remote)
      expect(find.text('已启用的其他 Agent'), findsOneWidget);
      expect(find.text('Codex'), findsOneWidget);
      expect(find.text('OpenCode'), findsOneWidget);
      expect(find.text('Codex (Remote)'), findsOneWidget);
      expect(find.byKey(const Key('agent-config-codex-acp')), findsOneWidget);
      expect(find.byKey(const Key('agent-config-opencode-acp')), findsOneWidget);
      expect(find.byKey(const Key('agent-config-codex-remote')), findsOneWidget);

      // Group 3: 添加其他 Agent (默认折叠未安装/未启用)
      expect(find.text('添加其他 Agent'), findsOneWidget);
      expect(find.text('Claude Code'), findsNothing);
      expect(find.text('Pi'), findsNothing);
      expect(find.byKey(const Key('agent-config-claude-code-acp')), findsNothing);
      expect(find.byKey(const Key('agent-config-pi-acp')), findsNothing);

      // 点击展开（不触发安装或网络请求）
      expect(find.text('展开'), findsWidgets);
      final group3ExpandTile = find.byKey(const Key('agent-expand-add-others-tile'));
      final group3ToggleButton = find.byKey(const Key('agent-toggle-add-others'));
      if (group3ExpandTile.evaluate().isNotEmpty) {
        await tester.tap(group3ExpandTile);
        await tester.pumpAndSettle();
      } else if (group3ToggleButton.evaluate().isNotEmpty) {
        await tester.tap(group3ToggleButton);
        await tester.pumpAndSettle();
      }

      // 展开后展示未安装与未启用 Agent
      expect(find.text('Claude Code'), findsOneWidget);
      expect(find.text('Pi'), findsOneWidget);
      expect(find.byKey(const Key('agent-config-claude-code-acp')), findsOneWidget);
      expect(find.byKey(const Key('agent-config-pi-acp')), findsOneWidget);
      expect(find.text('收起'), findsOneWidget);

      // 折叠/展开只改变UI，不执行安装、健康探测网络或修改引擎
      expect(calls.where((c) => c.method == 'agent/prepare'), isEmpty);
      expect(calls.where((c) => c.method == 'agent/refresh'), isEmpty);

      // 点击收起
      await tester.tap(find.byKey(const Key('agent-toggle-add-others')));
      await tester.pumpAndSettle();

      expect(find.text('Claude Code'), findsNothing);
      expect(find.text('Pi'), findsNothing);
      expect(find.text('展开'), findsWidgets);
    },
  );

  testWidgets(
    'Remote Codex is placed in Group 3 when disabled or missing',
    (tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(agentRuntimeChannel, (call) async {
            if (call.method != 'agent/list') return null;
            return <String, dynamic>{
              'agents': <Map<String, dynamic>>[
                {
                  ..._agent(
                    'codex-remote',
                    'Codex (Remote)',
                    'codex-remote',
                    'offline',
                  ),
                  'enabled': false,
                },
              ],
            };
          });

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          locale: const Locale('zh'),
          home: const AgentModeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      // Disabled Remote Codex goes to Group 3 and is collapsed by default
      expect(find.text('已启用的其他 Agent'), findsNothing);
      expect(find.text('添加其他 Agent'), findsOneWidget);
      expect(find.text('Codex (Remote)'), findsNothing);

      final remoteExpandTile = find.byKey(const Key('agent-expand-add-others-tile'));
      final remoteToggleButton = find.byKey(const Key('agent-toggle-add-others'));
      if (remoteExpandTile.evaluate().isNotEmpty) {
        await tester.tap(remoteExpandTile);
        await tester.pumpAndSettle();
      } else if (remoteToggleButton.evaluate().isNotEmpty) {
        await tester.tap(remoteToggleButton);
        await tester.pumpAndSettle();
      }
      expect(find.text('Codex (Remote)'), findsOneWidget);
    },
  );
}

Map<String, dynamic> _agent(
  String id,
  String name,
  String command,
  String status, {
  List<String> arguments = const [],
  bool managedAdapter = false,
  String? lastCheckError,
}) {
  return <String, dynamic>{
    'id': id,
    'name': name,
    'description': '$name ACP Agent',
    'command': command,
    'arguments': arguments,
    'enabled': true,
    'builtIn': true,
    'source': 'official',
    'selected': id == 'codex-acp',
    'installed': status != 'missing',
    'status': status,
    'managedAdapter': managedAdapter,
    'lastCheckError': ?lastCheckError,
  };
}
