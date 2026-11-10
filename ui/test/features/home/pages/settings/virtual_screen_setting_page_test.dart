import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/settings/virtual_screen_setting_page.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/services/virtual_screen_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/omni_glass.dart';

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

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const specialPermissionChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );

  final recordedCalls = <MethodCall>[];
  Map<dynamic, dynamic>? mockStateResult;
  Exception? mockStateError;
  Exception? mockActionError;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    await StorageService.init();

    recordedCalls.clear();
    mockStateResult = <String, dynamic>{'state': 'absent'};
    mockStateError = null;
    mockActionError = null;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, (call) async {
      recordedCalls.add(call);

      if (call.method == 'getVirtualScreenState') {
        if (mockStateError != null) {
          throw mockStateError!;
        }
        return mockStateResult;
      }

      if (mockActionError != null) {
        throw mockActionError!;
      }

      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, null);
  });

  Widget buildTestApp(Widget child) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      locale: const Locale('zh'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      navigatorObservers: [GoRouterManager.routeObserver],
      home: DefaultAssetBundle(bundle: _SvgTestAssetBundle(), child: child),
    );
  }

  testWidgets('确认拒绝：用户取消不调用底层关闭，确认后执行关闭', (tester) async {
    mockStateResult = {
      'state': 'retained',
      'displayId': 10,
      'sessionId': 'sess-10',
    };

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    final closeTileFinder =
        find.byKey(const Key('virtual-screen-close-screen-tile'));
    await tester.ensureVisible(closeTileFinder);
    await tester.pumpAndSettle();
    expect(closeTileFinder.hitTestable(), findsOneWidget);

    // 点击关闭，弹出确认弹窗
    await tester.tap(closeTileFinder);
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsOneWidget);

    // 点击取消：弹窗关闭，未发生底层 closeVirtualScreen 调用
    final cancelFinder = find.text('取消');
    await tester.ensureVisible(cancelFinder);
    await tester.pumpAndSettle();
    await tester.tap(cancelFinder);
    await tester.pumpAndSettle();

    expect(find.byType(OmniGlassAlertDialog), findsNothing);
    expect(recordedCalls.any((c) => c.method == 'closeVirtualScreen'), isFalse);

    // 再次点击关闭并确认
    await tester.ensureVisible(closeTileFinder);
    await tester.pumpAndSettle();
    await tester.tap(closeTileFinder);
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsOneWidget);

    final confirmFinder = find.text('确认关闭');
    await tester.ensureVisible(confirmFinder);
    await tester.pumpAndSettle();
    await tester.tap(confirmFinder);
    await tester.pumpAndSettle();

    expect(find.byType(OmniGlassAlertDialog), findsNothing);
    expect(
      recordedCalls.any((c) =>
          c.method == 'closeVirtualScreen' &&
          c.arguments['displayId'] == 10 &&
          c.arguments['sessionId'] == 'sess-10'),
      isTrue,
    );
  });

  testWidgets('轮询新屏不得误关：弹窗期间轮询更新状态，关闭确认只销毁弹窗前锁定的原屏', (tester) async {
    mockStateResult = {
      'state': 'retained',
      'displayId': 10,
      'sessionId': 'sess-old',
    };

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    final closeTileFinder =
        find.byKey(const Key('virtual-screen-close-screen-tile'));
    await tester.ensureVisible(closeTileFinder);
    await tester.pumpAndSettle();
    await tester.tap(closeTileFinder);
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsOneWidget);

    // 模拟弹窗展示期间，底层轮询到了新的虚拟屏
    mockStateResult = {
      'state': 'retained',
      'displayId': 99,
      'sessionId': 'sess-new',
    };

    // 用户在弹窗上点击确认关闭
    final confirmFinder = find.text('确认关闭');
    await tester.ensureVisible(confirmFinder);
    await tester.pumpAndSettle();
    await tester.tap(confirmFinder);
    await tester.pumpAndSettle();

    // 必须调用的是弹窗前捕获的 displayId: 10 与 sess-old，绝不能关闭轮询到的新屏 99
    expect(
      recordedCalls.any((c) =>
          c.method == 'closeVirtualScreen' &&
          c.arguments['displayId'] == 10 &&
          c.arguments['sessionId'] == 'sess-old'),
      isTrue,
    );
    expect(
      recordedCalls.any((c) =>
          c.method == 'closeVirtualScreen' &&
          c.arguments['displayId'] == 99),
      isFalse,
    );
  });

  testWidgets('新 Turn 禁销毁：任务运行中禁止销毁现场，停止任务核验精确 identity', (tester) async {
    mockStateResult = {
      'state': 'running',
      'displayId': 8,
      'sessionId': 'sess-running',
      'taskId': 'task-live',
      'turnId': 'turn-live',
    };

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    // 运行中禁止提供直接销毁现场的可点击入口
    expect(
      find.byKey(const Key('virtual-screen-close-screen-tile')),
      findsNothing,
    );

    // 停止任务可用并携带精确 Turn identity
    final stopTileFinder =
        find.byKey(const Key('virtual-screen-stop-task-tile'));
    expect(stopTileFinder, findsOneWidget);

    await tester.ensureVisible(stopTileFinder);
    await tester.pumpAndSettle();
    expect(stopTileFinder.hitTestable(), findsOneWidget);

    await tester.tap(stopTileFinder);
    await tester.pumpAndSettle();

    expect(
      recordedCalls.any((c) =>
          c.method == 'stopVirtualScreenTask' &&
          c.arguments['displayId'] == 8 &&
          c.arguments['sessionId'] == 'sess-running' &&
          c.arguments['taskId'] == 'task-live' &&
          c.arguments['turnId'] == 'turn-live'),
      isTrue,
    );
  });

  testWidgets('错误不冒空态：查询失败展示错误卡片与重试，不造假显示未开启空态', (tester) async {
    mockStateError = PlatformException(
      code: 'PERMISSION_DENIED',
      message: '缺少无障碍服务权限',
    );

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    // 错误状态下不得展示 absent 的未开启图标
    expect(find.byIcon(LucideIcons.monitorOff), findsNothing);

    // 错误卡片包含重试与权限指引
    final retryFinder = find.text('重试');
    expect(retryFinder, findsOneWidget);
    expect(find.text('检查权限'), findsOneWidget);

    // 恢复正常后重试
    mockStateError = null;
    mockStateResult = {
      'state': 'retained',
      'displayId': 15,
      'sessionId': 'sess-ok',
    };

    await tester.ensureVisible(retryFinder);
    await tester.pumpAndSettle();
    await tester.tap(retryFinder);
    await tester.pumpAndSettle();

    final closeTile = find.byKey(const Key('virtual-screen-close-screen-tile'));
    await tester.ensureVisible(closeTile);
    await tester.pumpAndSettle();
    expect(closeTile.hitTestable(), findsOneWidget);
  });

  testWidgets('失败回读：操作执行失败后自动触发状态回读', (tester) async {
    mockStateResult = {
      'state': 'retained',
      'displayId': 18,
      'sessionId': 'sess-fail',
    };
    mockActionError = PlatformException(
      code: 'INPUT_LOCKED',
      message: '输入租约锁定中，无法释放',
    );

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    final closeTile = find.byKey(const Key('virtual-screen-close-screen-tile'));
    await tester.ensureVisible(closeTile);
    await tester.pumpAndSettle();

    final queryCountBefore =
        recordedCalls.where((c) => c.method == 'getVirtualScreenState').length;

    await tester.tap(closeTile);
    await tester.pumpAndSettle();

    final confirmBtn = find.text('确认关闭');
    await tester.ensureVisible(confirmBtn);
    await tester.pumpAndSettle();
    await tester.tap(confirmBtn);
    await tester.pumpAndSettle();

    final queryCountAfter =
        recordedCalls.where((c) => c.method == 'getVirtualScreenState').length;

    // 失败后立即回读，核对真实状态
    expect(queryCountAfter, greaterThan(queryCountBefore));
  });

  testWidgets('小屏适配：在 360x640 小屏下通过滚动与 ensureVisible 正常完成交互', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    mockStateResult = {
      'state': 'retained',
      'displayId': 55,
      'sessionId': 'sess-small-screen',
    };

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    final closeTile = find.byKey(const Key('virtual-screen-close-screen-tile'));
    await tester.ensureVisible(closeTile);
    await tester.pumpAndSettle();
    expect(closeTile.hitTestable(), findsOneWidget);

    await tester.tap(closeTile);
    await tester.pumpAndSettle();
    expect(find.byType(OmniGlassAlertDialog), findsOneWidget);

    final confirmBtn = find.text('确认关闭');
    await tester.ensureVisible(confirmBtn);
    await tester.pumpAndSettle();
    expect(confirmBtn.hitTestable(), findsOneWidget);

    await tester.tap(confirmBtn);
    await tester.pumpAndSettle();

    expect(
      recordedCalls.any((c) =>
          c.method == 'closeVirtualScreen' &&
          c.arguments['displayId'] == 55 &&
          c.arguments['sessionId'] == 'sess-small-screen'),
      isTrue,
    );
  });

  testWidgets('未知或缺失 state 抛出 FormatException 并展示错误，不冒充空态', (tester) async {
    mockStateResult = {'state': 'invalid_unknown_state'};

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    expect(find.byIcon(LucideIcons.monitorOff), findsNothing);
    expect(find.text('获取虚拟屏状态失败'), findsOneWidget);
    expect(find.text('重试'), findsOneWidget);
  });

  testWidgets('路由覆盖与后台恢复：被其他页面盖住时后台恢复不触发刷新，返回恢复时才刷新', (tester) async {
    mockStateResult = {
      'state': 'retained',
      'displayId': 77,
      'sessionId': 'sess-lifecycle',
    };

    await tester.pumpWidget(buildTestApp(const VirtualScreenSettingPage()));
    await tester.pumpAndSettle();

    final initialCount =
        recordedCalls.where((c) => c.method == 'getVirtualScreenState').length;
    expect(initialCount, greaterThanOrEqualTo(1));

    final navigator =
        Navigator.of(tester.element(find.byType(VirtualScreenSettingPage)));
    navigator.push(MaterialPageRoute(
      builder: (_) => const Scaffold(body: Text('覆盖页面内容')),
    ));
    await tester.pumpAndSettle();
    expect(find.text('覆盖页面内容'), findsOneWidget);

    final coveredCount =
        recordedCalls.where((c) => c.method == 'getVirtualScreenState').length;

    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pump();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();

    final afterResumeCount =
        recordedCalls.where((c) => c.method == 'getVirtualScreenState').length;
    expect(afterResumeCount, equals(coveredCount));

    navigator.pop();
    await tester.pumpAndSettle();
    expect(find.text('覆盖页面内容'), findsNothing);

    final finalCount =
        recordedCalls.where((c) => c.method == 'getVirtualScreenState').length;
    expect(finalCount, greaterThan(afterResumeCount));
  });
}
