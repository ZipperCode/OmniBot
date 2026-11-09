import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/permission_registry.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('task execution resolves accessibility permission aliases', () {
    expect(
      kTaskExecutionRequiredPermissionIds,
      contains(kAccessibilityPermissionId),
    );
    expect(
      resolveExecutionPermissionIds(const <String>[
        '无障碍权限',
        'Overlay',
        'unknown permission',
      ]),
      <String>[kAccessibilityPermissionId, kOverlayPermissionId],
    );
  });

  test('builds accessibility display permission', () {
    final permissions = PermissionService.buildDisplayPermissionsForIds(const [
      kAccessibilityPermissionId,
    ]);

    expect(permissions, hasLength(1));
    expect(permissions.single.id, kAccessibilityPermissionId);
  });

  tearDown(LegacyTextLocalizer.clearResolvedLocale);

  test('rebuilds special permission labels from current locale', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kWorkspaceStoragePermissionId,
    ]).single;
    expect(zhPermission.name, '内置 workspace');

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kWorkspaceStoragePermissionId,
    ]).single;
    expect(enPermission.name, 'Built-in workspace');
  });

  test('builds shizuku display permission from current locale', () {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    final zhPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kShizukuPermissionId,
    ]).single;
    expect(zhPermission.name, 'Shizuku 权限');

    LegacyTextLocalizer.setResolvedLocale(const Locale('en'));
    final enPermission = PermissionService.buildDisplayPermissionsForIds(const [
      kShizukuPermissionId,
    ]).single;
    expect(enPermission.name, 'Shizuku Permission');
  });

  test('shizuku stays optional unless explicitly included', () {
    final defaultSpecs = PermissionRegistry.getPermissions(brand: 'other');
    expect(
      defaultSpecs.any((item) => item.id == kShizukuPermissionId),
      isFalse,
    );

    final optionalSpecs = PermissionRegistry.getPermissions(
      brand: 'other',
      includeOptionalAdvanced: true,
    );
    expect(
      optionalSpecs.any((item) => item.id == kShizukuPermissionId),
      isTrue,
    );
  });

  test('local network permission is optional for ordinary chat', () {
    expect(
      PermissionRegistry.getPermissions(brand: 'other')
          .any((item) => item.id == 'local_network'),
      isFalse,
    );
    final localNetwork = PermissionRegistry.getPermissions(
      brand: 'other',
      includeOptionalAdvanced: true,
    ).singleWhere((item) => item.id == 'local_network');
    expect(localNetwork.openMethod, 'requestLocalNetworkPermission');
    expect(localNetwork.checkMethod, 'isLocalNetworkPermissionGranted');
    expect(
      PermissionRegistry.getPermissionsByLevel(
        brand: 'other',
        level: PermissionLevel.fullExecution,
        includeOptionalAdvanced: true,
      ).any((item) => item.id == 'local_network'),
      isFalse,
    );
  });

  group('read-only permission checks', () {
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final grantedMap = <String, Object?>{};

    setUp(() {
      grantedMap.clear();
      messenger.setMockMethodCallHandler(spePermission, (call) async {
        return grantedMap[call.method];
      });
    });

    tearDown(() {
      messenger.setMockMethodCallHandler(spePermission, null);
    });

    test('isPermissionGranted checks native state for all execution permissions', () async {
      grantedMap['isAndroidGuiAccessibilityReady'] = true;
      expect(
        await PermissionService.isPermissionGranted(kAccessibilityPermissionId),
        isTrue,
      );

      grantedMap['isAndroidGuiAccessibilityReady'] = false;
      expect(
        await PermissionService.isPermissionGranted(kAccessibilityPermissionId),
        isFalse,
      );

      grantedMap['isOverlayPermission'] = true;
      expect(
        await PermissionService.isPermissionGranted(kOverlayPermissionId),
        isTrue,
      );

      grantedMap['isInstalledAppsPermissionGranted'] = true;
      expect(
        await PermissionService.isPermissionGranted(kInstalledAppsPermissionId),
        isTrue,
      );

      grantedMap['isWorkspaceStorageAccessGranted'] = true;
      expect(
        await PermissionService.isPermissionGranted(kWorkspaceStoragePermissionId),
        isTrue,
      );

      grantedMap['isPublicStorageAccessGranted'] = true;
      expect(
        await PermissionService.isPermissionGranted(kPublicStoragePermissionId),
        isTrue,
      );

      grantedMap['getShizukuStatus'] = <String, Object?>{
        'status': 'GRANTED_ADB',
        'backend': 'SHIZUKU',
      };
      expect(
        await PermissionService.isPermissionGranted(kShizukuPermissionId),
        isTrue,
      );

      expect(
        await PermissionService.isPermissionGranted('unknown_permission_id'),
        isFalse,
      );
    });

    test('areRequiredPermissionsGranted validates completeness and unknown ids', () async {
      grantedMap['isAndroidGuiAccessibilityReady'] = true;
      grantedMap['isOverlayPermission'] = true;

      expect(
        await PermissionService.areRequiredPermissionsGranted([
          kAccessibilityPermissionId,
          kOverlayPermissionId,
        ]),
        isTrue,
      );

      // 部分满足不能算全部满足
      grantedMap['isOverlayPermission'] = false;
      expect(
        await PermissionService.areRequiredPermissionsGranted([
          kAccessibilityPermissionId,
          kOverlayPermissionId,
        ]),
        isFalse,
      );

      // 包含未知 ID 不能误判全满足
      expect(
        await PermissionService.areRequiredPermissionsGranted([
          kAccessibilityPermissionId,
          'unknown_id',
        ]),
        isFalse,
      );

      // 空集合不误判为已授权
      expect(
        await PermissionService.areRequiredPermissionsGranted([]),
        isFalse,
      );
    });

    test('buildDisplayPermissionsForIds checkAuthorization performs real check', () async {
      grantedMap['isAndroidGuiAccessibilityReady'] = true;
      final permissions = PermissionService.buildDisplayPermissionsForIds([
        kAccessibilityPermissionId,
      ]);
      expect(permissions, hasLength(1));
      expect(await permissions.single.checkAuthorization(), isTrue);

      grantedMap['isAndroidGuiAccessibilityReady'] = false;
      expect(await permissions.single.checkAuthorization(), isFalse);
    });
  });
}
