import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('PrivilegedStatusSnapshot model parsing', () {
    test('fallback returns default unconfigured snapshot', () {
      final snapshot = PrivilegedStatusSnapshot.fallback();
      expect(snapshot.selected, 'none');
      expect(snapshot.granted, isFalse);
      expect(snapshot.authority, 'none');
      expect(snapshot.code, 'not_selected');
      expect(snapshot.uid, isNull);
      expect(snapshot.shizuku, isNull);
      expect(snapshot.su, isNull);
      expect(snapshot.isNone, isTrue);
      expect(snapshot.isShizuku, isFalse);
      expect(snapshot.isSu, isFalse);
      expect(snapshot.isGranted, isFalse);
    });

    test('parses granted root snapshot correctly', () {
      final map = <String, dynamic>{
        'selected': 'su',
        'granted': true,
        'authority': 'root',
        'uid': 0,
        'code': 'granted',
        'message': 'Privileged source is authorized.',
        'availableActions': <String>[
          'android_privileged_action',
          'android_privileged_session_start',
        ],
        'shizuku': null,
        'su': <String, dynamic>{'binaryPath': '/system/bin/su'},
      };
      final snapshot = PrivilegedStatusSnapshot.fromMap(map);
      expect(snapshot.selected, 'su');
      expect(snapshot.granted, isTrue);
      expect(snapshot.isGranted, isTrue);
      expect(snapshot.isSu, isTrue);
      expect(snapshot.authority, 'root');
      expect(snapshot.isRoot, isTrue);
      expect(snapshot.isAdb, isFalse);
      expect(snapshot.uid, 0);
      expect(snapshot.code, 'granted');
      expect(snapshot.availableActions, contains('android_privileged_action'));
      expect(snapshot.su?.binaryPath, '/system/bin/su');
      expect(snapshot.localizedStatusLabel.toLowerCase(), contains('root'));
      expect(snapshot.localizedGuide.toLowerCase(), contains('root'));
      expect(snapshot.localizedGuide, contains('UID: 0'));
    });

    test('parses granted adb shizuku snapshot correctly', () {
      final map = <String, dynamic>{
        'selected': 'shizuku',
        'granted': true,
        'authority': 'adb',
        'uid': 2000,
        'code': 'granted',
        'message': 'Privileged source is authorized.',
        'availableActions': <String>['android_privileged_action'],
        'shizuku': <String, dynamic>{
          'installed': true,
          'running': true,
          'binderReady': true,
          'serviceBound': true,
          'permissionGranted': true,
          'version': 13,
        },
        'su': null,
      };
      final snapshot = PrivilegedStatusSnapshot.fromMap(map);
      expect(snapshot.selected, 'shizuku');
      expect(snapshot.isShizuku, isTrue);
      expect(snapshot.granted, isTrue);
      expect(snapshot.authority, 'adb');
      expect(snapshot.isAdb, isTrue);
      expect(snapshot.isRoot, isFalse);
      expect(snapshot.uid, 2000);
      expect(snapshot.shizuku?.installed, isTrue);
      expect(snapshot.shizuku?.version, 13);
      expect(snapshot.localizedStatusLabel.toLowerCase(), contains('adb'));
    });

    test('parses all defined non-granted codes accurately', () {
      final codes = <String>[
        'not_selected',
        'shizuku_not_installed',
        'shizuku_not_running',
        'shizuku_binder_dead',
        'shizuku_permission_denied',
        'su_not_authorized',
        'su_denied',
        'su_unavailable',
        'authority_unexpected',
      ];

      for (final code in codes) {
        final snapshot = PrivilegedStatusSnapshot.fromMap(<String, dynamic>{
          'selected': code.startsWith('su')
              ? 'su'
              : (code.startsWith('shizuku') ? 'shizuku' : 'none'),
          'granted': false,
          'authority': 'none',
          'code': code,
        });
        expect(
          snapshot.isGranted,
          isFalse,
          reason: 'Code $code should not be granted',
        );
        expect(snapshot.localizedStatusLabel.isNotEmpty, isTrue);
        expect(snapshot.localizedGuide.isNotEmpty, isTrue);
      }
    });
  });

  group('MethodChannel invocations and error propagation', () {
    final calls = <MethodCall>[];

    setUp(() {
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call);
            switch (call.method) {
              case 'getPrivilegedStatus':
                return <String, dynamic>{
                  'selected': 'none',
                  'granted': false,
                  'authority': 'none',
                  'uid': null,
                  'code': 'not_selected',
                  'message': 'No privileged source selected.',
                  'availableActions': <String>[],
                  'shizuku': null,
                  'su': null,
                };
              case 'setPrivilegedSource':
                final source = call.arguments['source'] as String;
                if (source == 'invalid') {
                  throw PlatformException(
                    code: 'INVALID_SOURCE',
                    message: 'Unsupported privileged source: invalid',
                  );
                }
                return <String, dynamic>{
                  'selected': source,
                  'granted': false,
                  'authority': 'none',
                  'uid': null,
                  'code': source == 'su' ? 'su_not_authorized' : 'not_selected',
                  'message': '',
                  'availableActions': <String>[],
                  'shizuku': null,
                  'su': null,
                };
              case 'authorizePrivilegedSource':
                return <String, dynamic>{
                  'selected': 'su',
                  'granted': true,
                  'authority': 'root',
                  'uid': 0,
                  'code': 'granted',
                  'message': 'Authorized',
                  'availableActions': <String>[],
                  'shizuku': null,
                  'su': <String, dynamic>{'binaryPath': '/system/xbin/su'},
                };
              case 'openPrivilegedSourceApp':
                return true;
            }
            return null;
          });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, null);
    });

    test('getPrivilegedStatus invokes getPrivilegedStatus method', () async {
      final status = await getPrivilegedStatus();
      expect(calls.map((c) => c.method), contains('getPrivilegedStatus'));
      expect(status.selected, 'none');
      expect(status.isGranted, isFalse);
    });

    test('getPrivilegedStatus swallows channel failure and returns fallback', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            throw PlatformException(code: 'READ_FAILED', message: 'Channel read error');
          });

      final status = await getPrivilegedStatus();
      expect(status.selected, 'none');
      expect(status.code, 'not_selected');
      expect(status.isGranted, isFalse);
    });

    test('setPrivilegedSource passes source parameter and returns snapshot', () async {
      final status = await setPrivilegedSource('su');
      expect(
        calls.any(
          (c) => c.method == 'setPrivilegedSource' && c.arguments['source'] == 'su',
        ),
        isTrue,
      );
      expect(status.selected, 'su');
    });

    test('setPrivilegedSource propagates PlatformException instead of swallowing', () async {
      expect(
        () => setPrivilegedSource('invalid'),
        throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'INVALID_SOURCE')),
      );
    });

    test('authorizePrivilegedSource invokes authorizePrivilegedSource and returns snapshot', () async {
      final status = await authorizePrivilegedSource();
      expect(calls.map((c) => c.method), contains('authorizePrivilegedSource'));
      expect(status.isGranted, isTrue);
      expect(status.authority, 'root');
    });

    test('authorizePrivilegedSource propagates PlatformException instead of swallowing', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            if (call.method == 'authorizePrivilegedSource') {
              throw PlatformException(code: 'AUTHORIZE_FAILED', message: 'Bridge failed');
            }
            return null;
          });

      expect(
        () => authorizePrivilegedSource(),
        throwsA(isA<PlatformException>().having((e) => e.code, 'code', 'AUTHORIZE_FAILED')),
      );
    });

    test('openPrivilegedSourceApp returns bool result', () async {
      final result = await openPrivilegedSourceApp();
      expect(calls.map((c) => c.method), contains('openPrivilegedSourceApp'));
      expect(result, isTrue);
    });
  });

  group('PrivilegedSourceSheet widget', () {
    setUp(() {
      LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    });

    tearDown(() {
      LegacyTextLocalizer.clearResolvedLocale();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, null);
    });

    testWidgets('renders all three source choices and accurate routing termination notice', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(430, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            if (call.method == 'getPrivilegedStatus') {
              return <String, dynamic>{
                'selected': 'none',
                'granted': false,
                'authority': 'none',
                'code': 'not_selected',
                'message': '',
              };
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () => showPrivilegedSourceSheet(context),
                child: const Text('Open Sheet'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Open Sheet'));
      await tester.pumpAndSettle();

      expect(find.text('特权访问设置'), findsOneWidget);
      expect(find.textContaining('停止当前特权会话并移出路由表'), findsOneWidget);
      expect(find.textContaining('无法保证物理终止'), findsOneWidget);
      expect(find.text('未启用'), findsOneWidget);
      expect(find.text('Shizuku'), findsOneWidget);
      expect(find.text('直接 Root (KernelSU)'), findsOneWidget);
      expect(find.textContaining('不保证所有设备与系统均兼容'), findsOneWidget);
    });

    testWidgets('switching source calls setPrivilegedSource and updates view', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(430, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final methodCalls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            methodCalls.add(call);
            if (call.method == 'getPrivilegedStatus') {
              return <String, dynamic>{
                'selected': 'none',
                'granted': false,
                'authority': 'none',
                'code': 'not_selected',
                'message': '',
              };
            }
            if (call.method == 'setPrivilegedSource') {
              final source = call.arguments['source'] as String;
              return <String, dynamic>{
                'selected': source,
                'granted': false,
                'authority': 'none',
                'code': source == 'su' ? 'su_not_authorized' : 'not_selected',
                'message': '',
              };
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () => showPrivilegedSourceSheet(context),
                child: const Text('Open Sheet'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Open Sheet'));
      await tester.pumpAndSettle();

      // Tap 直接 Root (KernelSU)
      await tester.tap(find.text('直接 Root (KernelSU)'));
      await tester.pumpAndSettle();

      expect(
        methodCalls.any(
          (c) => c.method == 'setPrivilegedSource' && c.arguments['source'] == 'su',
        ),
        isTrue,
      );
      // Once su is selected, the action button shows '验证 Root 授权'
      expect(find.text('验证 Root 授权'), findsOneWidget);
    });

    testWidgets('passive viewing never calls authorizePrivilegedSource, explicit button tap does', (
      tester,
    ) async {
      tester.view.physicalSize = const Size(430, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final methodCalls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            methodCalls.add(call);
            if (call.method == 'getPrivilegedStatus') {
              return <String, dynamic>{
                'selected': 'su',
                'granted': false,
                'authority': 'none',
                'code': 'su_not_authorized',
                'message': '',
              };
            }
            if (call.method == 'authorizePrivilegedSource') {
              return <String, dynamic>{
                'selected': 'su',
                'granted': true,
                'authority': 'root',
                'uid': 0,
                'code': 'granted',
                'message': '',
                'su': <String, dynamic>{'binaryPath': '/system/bin/su'},
              };
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: Scaffold(
            body: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () => showPrivilegedSourceSheet(context),
                child: const Text('Open Sheet'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Open Sheet'));
      await tester.pumpAndSettle();

      // On passive open, getPrivilegedStatus was called, but authorize was NOT called!
      expect(methodCalls.map((c) => c.method), contains('getPrivilegedStatus'));
      expect(
        methodCalls.map((c) => c.method),
        isNot(contains('authorizePrivilegedSource')),
      );

      // Now explicitly tap '验证 Root 授权'
      await tester.tap(find.text('验证 Root 授权'));
      await tester.pumpAndSettle();

      expect(
        methodCalls.map((c) => c.method),
        contains('authorizePrivilegedSource'),
      );
      expect(find.text('已授权（Root）'), findsOneWidget);
      expect(find.text('UID: 0'), findsOneWidget);
    });
  });
}
