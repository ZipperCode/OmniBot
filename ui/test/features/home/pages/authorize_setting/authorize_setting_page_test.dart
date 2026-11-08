import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize_setting/authorize_setting_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/services/cache.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/settings_ui.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, null);
  });

  testWidgets('renders all files access entry and opens native settings', (
    tester,
  ) async {
    final permissionCalls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(cacheEvent, (call) async {
          if (call.method == 'doMMKVDecodeBoole') {
            return true;
          }
          return null;
        });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (call) async {
          permissionCalls.add(call);
          switch (call.method) {
            case 'isBackgroundRunAllowed':
            case 'isOverlayPermission':
            case 'isInstalledAppsPermissionGranted':
              return true;
            case 'isAndroidGuiAccessibilityReady':
              return false;
            case 'isPublicStorageAccessGranted':
              return false;
            case 'getShizukuStatus':
              return <String, dynamic>{
                'status': 'NOT_INSTALLED',
                'backend': 'NONE',
                'installed': false,
                'running': false,
                'permissionGranted': false,
                'binderReady': false,
                'serviceBound': false,
                'availableActions': <String>[],
                'message': '',
              };
            case 'openPublicStorageSettings':
              return true;
          }
          return null;
        });

    await tester.pumpWidget(
      MaterialApp(
        locale: const Locale('zh'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        theme: AppTheme.lightTheme,
        home: const AuthorizeSettingPage(),
      ),
    );
    await tester.pumpAndSettle();

    final entry = find.text('所有文件访问权限');
    await tester.scrollUntilVisible(entry, 120);
    await tester.pumpAndSettle();

    expect(entry, findsOneWidget);
    expect(find.textContaining('公共存储'), findsOneWidget);

    await tester.tap(entry);
    await tester.pump();

    expect(
      permissionCalls.map((call) => call.method),
      contains('openPublicStorageSettings'),
    );
  });

  testWidgets(
    'shows where to enable accessibility when permission is missing',
    (tester) async {
      final permissionCalls = <MethodCall>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            permissionCalls.add(call);
            switch (call.method) {
              case 'isBackgroundRunAllowed':
              case 'isOverlayPermission':
              case 'isInstalledAppsPermissionGranted':
                return true;
              case 'isAndroidGuiAccessibilityReady':
              case 'isPublicStorageAccessGranted':
                return false;
              case 'getShizukuStatus':
                return <String, dynamic>{
                  'status': 'NOT_INSTALLED',
                  'backend': 'NONE',
                  'installed': false,
                  'running': false,
                  'permissionGranted': false,
                  'binderReady': false,
                  'serviceBound': false,
                  'availableActions': <String>[],
                  'message': '',
                };
              case 'openAndroidGuiAccessibilitySettings':
                return null;
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('3 / 4 项核心授权已就绪'), findsOneWidget);
      expect(find.text('无障碍权限'), findsOneWidget);

      await tester.tap(find.text('无障碍权限'));
      await tester.pumpAndSettle();

      expect(find.text('开启位置'), findsOneWidget);
      expect(find.textContaining('已下载的应用'), findsOneWidget);

      await tester.tap(find.text('打开系统无障碍设置'));
      await tester.pump();
      expect(
        permissionCalls.map((call) => call.method),
        contains('openAndroidGuiAccessibilitySettings'),
      );
    },
  );

  testWidgets(
    'requests local network access and refreshes denied and granted states',
    (tester) async {
      var granted = false;
      var allowRequest = false;
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call.method);
            if (call.method == 'getLocalNetworkPermissionStatus') {
              return {'required': true, 'granted': granted};
            }
            if (call.method == 'requestLocalNetworkPermission') {
              granted = allowRequest;
              return granted;
            }
            return null;
          });
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();
      final entry = find.text('局域网访问');
      await tester.scrollUntilVisible(entry, 120);
      await tester.pumpAndSettle();
      final tile = find.ancestor(
        of: entry,
        matching: find.byType(OmniSettingsTile),
      );
      expect(
        find.descendant(of: tile, matching: find.text('去开启')),
        findsOneWidget,
      );

      await tester.tap(entry);
      await tester.pumpAndSettle();
      expect(calls, contains('requestLocalNetworkPermission'));
      expect(find.textContaining('局域网访问未授权'), findsOneWidget);
      expect(
        find.descendant(of: tile, matching: find.text('去开启')),
        findsOneWidget,
      );

      allowRequest = true;
      await tester.tap(entry);
      await tester.pumpAndSettle();
      expect(
        find.descendant(of: tile, matching: find.text('已开启')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'renders digital assistant entry and navigates to voice settings',
    (tester) async {
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call.method);
            if (call.method == 'getAssistantStatus') {
              return {'supported': true, 'selected': false};
            }
            if (call.method == 'openAssistantSettings') {
              return true;
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      final entry = find.text('系统数字助理');
      await tester.scrollUntilVisible(entry, 120);
      await tester.pumpAndSettle();

      expect(entry, findsOneWidget);
      final tile = find.ancestor(
        of: entry,
        matching: find.byType(OmniSettingsTile),
      );
      expect(
        find.descendant(of: tile, matching: find.text('去设置')),
        findsOneWidget,
      );

      await tester.tap(entry);
      await tester.pumpAndSettle();
      expect(calls, contains('openAssistantSettings'));
    },
  );

  testWidgets(
    'retries digital assistant status on failure without faking boolean',
    (tester) async {
      var fail = true;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            if (call.method == 'getAssistantStatus') {
              if (fail) throw PlatformException(code: 'ERR', message: 'failed');
              return {'supported': true, 'selected': true};
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      final entry = find.text('系统数字助理');
      await tester.scrollUntilVisible(entry, 120);
      await tester.pumpAndSettle();

      final tile = find.ancestor(
        of: entry,
        matching: find.byType(OmniSettingsTile),
      );
      expect(
        find.descendant(of: tile, matching: find.text('重试')),
        findsOneWidget,
      );

      // Now recover and tap retry
      fail = false;
      await tester.tap(entry);
      await tester.pumpAndSettle();

      expect(
        find.descendant(of: tile, matching: find.text('已设置')),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'system device capabilities validates wifi read-write, ringer denied, and technical details expansion',
    (tester) async {
      var fail = true;
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call.method);
            if (call.method == 'getSystemDeviceCapabilities') {
              if (fail) {
                throw PlatformException(code: 'ERR', message: 'Probe failed');
              }
              return {
                'requestId': 'req_caps_123',
                'action': 'device_control.capabilities',
                'success': true,
                'code': 'ok',
                'message': '',
                'backend': 'ROOT',
                'provider': 'NONE',
                'uid': 10001,
                'observation': {
                  'userId': 0,
                  'scope': 'device',
                  'value': {
                    'targets': [
                      {
                        'target': 'wifi',
                        'readable': true,
                        'writable': true,
                        'code': 'ok',
                      },
                    ],
                    'actions': [
                      {
                        'action': 'device_control.set_ringer_mode',
                        'available': false,
                        'code': 'permission_denied',
                      },
                    ],
                  },
                },
              };
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      final entry = find.text('系统设备能力');
      await tester.scrollUntilVisible(entry, 120);
      await tester.pumpAndSettle();

      // Tap to open popup while backend fails
      await tester.tap(entry);
      await tester.pumpAndSettle();

      expect(calls, contains('getSystemDeviceCapabilities'));

      // Shows retry button on failure
      final retryButton = find.byIcon(LucideIcons.refreshCw);
      expect(retryButton, findsOneWidget);

      // Recover and retry
      fail = false;
      await tester.tap(retryButton);
      await tester.pumpAndSettle();

      // Verified wifi true is read-write
      expect(find.text('WLAN'), findsOneWidget);
      expect(find.text('可读写'), findsOneWidget);

      // Verified ringer mode is denied / requires authorization
      expect(find.text('设置响铃模式'), findsOneWidget);
      expect(find.text('需特权授权'), findsOneWidget);

      // Main UI does NOT display UID or package name
      expect(find.text('10001'), findsNothing);
      expect(find.text('cn.com.omnimind.bot'), findsNothing);

      // Expand technical details
      final techDetailsButton = find.byIcon(LucideIcons.terminal);
      await tester.scrollUntilVisible(
        techDetailsButton,
        80,
        scrollable: find.byType(Scrollable).last,
      );
      await tester.pumpAndSettle();
      expect(techDetailsButton, findsOneWidget);
      await tester.tap(techDetailsButton);
      await tester.pumpAndSettle();

      // Technical details now show UID and requestId
      expect(find.text('10001'), findsOneWidget);
      expect(find.text('req_caps_123'), findsOneWidget);
    },
  );

  testWidgets(
    'system device capabilities shows error on empty targets and recovers on retry',
    (tester) async {
      var returnEmpty = true;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(cacheEvent, (call) async => true);
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            if (call.method == 'getSystemDeviceCapabilities') {
              return {
                'requestId': 'req_empty_1',
                'action': 'device_control.capabilities',
                'success': true,
                'code': 'ok',
                'message': '',
                'backend': 'ROOT',
                'provider': 'NONE',
                'observation': {
                  'userId': 0,
                  'scope': 'device',
                  'value': {
                    'targets': returnEmpty
                        ? <dynamic>[]
                        : [
                            {
                              'target': 'wifi',
                              'readable': true,
                              'writable': true,
                              'code': 'ok',
                            },
                          ],
                    'actions': <dynamic>[],
                  },
                },
              };
            }
            return null;
          });

      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          theme: AppTheme.lightTheme,
          home: const AuthorizeSettingPage(),
        ),
      );
      await tester.pumpAndSettle();

      final entry = find.text('系统设备能力');
      await tester.scrollUntilVisible(entry, 120);
      await tester.pumpAndSettle();

      await tester.tap(entry);
      await tester.pumpAndSettle();

      // Does NOT show default fallback targets
      expect(find.text('WLAN'), findsNothing);
      expect(find.text('系统受控开关'), findsNothing);

      // Shows unavailable error/retry UI
      expect(find.text('系统能力暂不可用'), findsOneWidget);
      final retryButton = find.byIcon(LucideIcons.refreshCw);
      expect(retryButton, findsOneWidget);

      // Recover and retry
      returnEmpty = false;
      await tester.tap(retryButton);
      await tester.pumpAndSettle();

      // Now recovered and displays targets
      expect(find.text('WLAN'), findsOneWidget);
      expect(find.text('可读写'), findsOneWidget);
    },
  );
}
