import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/permission_section_card.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final methodHandlers = <String, FutureOr<Object?> Function(MethodCall call)>{};

  setUp(() {
    methodHandlers.clear();
    messenger.setMockMethodCallHandler(spePermission, (call) async {
      final handler = methodHandlers[call.method];
      if (handler != null) {
        return await handler(call);
      }
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(spePermission, null);
  });

  testWidgets(
    'already granted permissions do not navigate even with autoOpenAuthorization=true',
    (tester) async {
      int requestCount = 0;
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => true;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
                'autoOpenAuthorization': true,
              },
              onRequestAuthorize: (ids) async {
                requestCount++;
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(requestCount, 0);
      final button = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(button.onPressed, isNull);
      expect(find.text('前往开启'), findsNothing);

      // 已开启状态不可点击触发导航
      await tester.tap(find.byType(FilledButton));
      await tester.pumpAndSettle();
      expect(requestCount, 0);
    },
  );

  testWidgets(
    'historical card with autoOpenAuthorization=true and missing permissions never auto-navigates, but allows manual tap',
    (tester) async {
      int requestCount = 0;
      final requestedIds = <List<String>>[];
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => false;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
                'autoOpenAuthorization': true,
                'isHistorical': true,
              },
              onRequestAuthorize: (ids) async {
                requestCount++;
                requestedIds.add(ids);
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 历史卡即使缺失权限且包含 autoOpenAuthorization，也绝不得自动导航
      expect(requestCount, 0);
      expect(find.text('前往开启'), findsOneWidget);

      // 手动点击仍可正常发起授权
      await tester.tap(find.text('前往开启'));
      await tester.pumpAndSettle();

      expect(requestCount, 1);
      expect(requestedIds.single, <String>['accessibility']);
    },
  );

  testWidgets(
    'live card with autoOpenAuthorization=true and missing permissions auto-navigates once',
    (tester) async {
      int requestCount = 0;
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => false;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
                'autoOpenAuthorization': true,
                'isHistorical': false,
              },
              onRequestAuthorize: (ids) async {
                requestCount++;
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(requestCount, 1);
    },
  );

  testWidgets(
    'missing permissions navigate once on manual tap and autoOpen',
    (tester) async {
      final requestedIds = <List<String>>[];
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => false;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
              },
              onRequestAuthorize: (ids) async {
                requestedIds.add(ids);
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('前往开启'), findsOneWidget);
      await tester.tap(find.text('前往开启'));
      await tester.pumpAndSettle();

      expect(requestedIds, hasLength(1));
      expect(requestedIds.single, <String>['accessibility']);
    },
  );

  testWidgets(
    'slow native response prevents duplicate navigation on rapid taps',
    (tester) async {
      int navigationCount = 0;
      final checkCompleter = Completer<bool>();
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => checkCompleter.future;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
              },
              onRequestAuthorize: (ids) async {
                navigationCount++;
              },
            ),
          ),
        ),
      );
      // 首帧渲染
      await tester.pump();

      // 处于检查中，快速连点
      await tester.tap(find.byType(FilledButton));
      await tester.tap(find.byType(FilledButton));
      await tester.pump();

      checkCompleter.complete(false);
      await tester.pumpAndSettle();

      // 单次点击触发
      await tester.tap(find.text('前往开启'));
      await tester.pumpAndSettle();

      expect(navigationCount, 1);
    },
  );

  testWidgets(
    'app resumed lifecycle refreshes granted state between enabled and revoked',
    (tester) async {
      bool accessibilityReady = false;
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => accessibilityReady;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
              },
              onRequestAuthorize: (ids) async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('前往开启'), findsOneWidget);

      // 模拟后台授权后回到应用
      accessibilityReady = true;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      final grantedButton = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(grantedButton.onPressed, isNull);
      expect(find.text('前往开启'), findsNothing);
      // 模拟设置中权限被撤销后回到应用
      accessibilityReady = false;
      tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(find.text('前往开启'), findsOneWidget);
    },
  );

  testWidgets(
    'dynamic requiredPermissionIds change is not overwritten by late response of old set',
    (tester) async {
      final oldCompleter = Completer<bool>();
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => oldCompleter.future;
      methodHandlers['isOverlayPermission'] = (_) => true;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility'],
              },
              onRequestAuthorize: (ids) async {},
            ),
          ),
        ),
      );
      await tester.pump();

      // 更新为已授权的 overlay
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['overlay'],
              },
              onRequestAuthorize: (ids) async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final overlayButton = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(overlayButton.onPressed, isNull);
      expect(find.text('前往开启'), findsNothing);
      // 旧的 accessibility 检查晚到返回 false，不应覆盖新的已开启状态
      oldCompleter.complete(false);
      await tester.pumpAndSettle();
      final finalButton = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(finalButton.onPressed, isNull);
      expect(find.text('前往开启'), findsNothing);
    },
  );

  testWidgets(
    'partial grant and unknown ids are never treated as fully granted',
    (tester) async {
      methodHandlers['isAndroidGuiAccessibilityReady'] = (_) => true;
      methodHandlers['isOverlayPermission'] = (_) => false;

      // 部分满足：无障碍授权，悬浮窗未授权
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>['accessibility', 'overlay'],
              },
              onRequestAuthorize: (ids) async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('前往开启'), findsOneWidget);

      // 包含未知 ID：绝不误判为已全开启
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PermissionSectionCard(
              cardData: const <String, dynamic>{
                'type': 'permission_section',
                'requiredPermissionIds': <String>[
                  'accessibility',
                  'custom_unknown_id',
                ],
              },
              onRequestAuthorize: (ids) async {},
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('前往开启'), findsOneWidget);
    },
  );
}
