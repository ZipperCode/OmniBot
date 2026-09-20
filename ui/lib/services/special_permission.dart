import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:ui/features/home/pages/authorize/widgets/privileged_source_sheet.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

export '../features/home/pages/authorize/widgets/privileged_source_sheet.dart';

// The channel name must match the one in MainActivity.kt
const spePermission = MethodChannel(
  'cn.com.omnimind.bot/SpecialPermissionEvent',
);
const _specialPermissionEvents = EventChannel(
  'cn.com.omnimind.bot/SpecialPermissionEvents',
);

enum EmbeddedTerminalDistribution {
  alpine('alpine'),
  ubuntu('ubuntu');

  const EmbeddedTerminalDistribution(this.id);

  final String id;

  static EmbeddedTerminalDistribution fromId(String? id) {
    return values.firstWhere(
      (distribution) => distribution.id == id?.trim().toLowerCase(),
      orElse: () => alpine,
    );
  }
}

class EmbeddedTerminalInitProgress {
  const EmbeddedTerminalInitProgress({
    required this.kind,
    required this.message,
    required this.timestamp,
    required this.phase,
    required this.distribution,
    required this.downloadedBytes,
    required this.totalBytes,
    required this.progress,
    required this.error,
  });

  final String kind;
  final String message;
  final DateTime timestamp;
  final String? phase;
  final EmbeddedTerminalDistribution? distribution;
  final int downloadedBytes;
  final int totalBytes;
  final double? progress;
  final String? error;

  factory EmbeddedTerminalInitProgress.fromMap(Map<dynamic, dynamic> map) {
    final timestampValue = map['timestamp'];
    final millis = timestampValue is num
        ? timestampValue.toInt()
        : DateTime.now().millisecondsSinceEpoch;
    return EmbeddedTerminalInitProgress(
      kind: (map['kind'] as String? ?? 'status').trim(),
      message: (map['message'] as String? ?? '').trimRight(),
      timestamp: DateTime.fromMillisecondsSinceEpoch(millis),
      phase: (map['phase'] as String?)?.trim(),
      distribution: (map['distribution'] as String?)?.trim().isNotEmpty == true
          ? EmbeddedTerminalDistribution.fromId(map['distribution'] as String?)
          : null,
      downloadedBytes: (map['downloadedBytes'] as num?)?.toInt() ?? 0,
      totalBytes: (map['totalBytes'] as num?)?.toInt() ?? 0,
      progress: map['progress'] is num
          ? (map['progress'] as num).toDouble().clamp(0.0, 1.0).toDouble()
          : null,
      error: (map['error'] as String?)?.trim(),
    );
  }
}

class EmbeddedTerminalInitSnapshot {
  const EmbeddedTerminalInitSnapshot({
    required this.running,
    required this.completed,
    required this.success,
    required this.progress,
    required this.stage,
    required this.logLines,
    required this.startedAt,
    required this.completedAt,
    required this.phase,
    required this.distribution,
    required this.downloadedBytes,
    required this.totalBytes,
    required this.error,
  });

  final bool running;
  final bool completed;
  final bool? success;
  final double progress;
  final String stage;
  final List<String> logLines;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final String? phase;
  final EmbeddedTerminalDistribution? distribution;
  final int downloadedBytes;
  final int totalBytes;
  final String? error;

  factory EmbeddedTerminalInitSnapshot.fromMap(Map<dynamic, dynamic> map) {
    final rawLogLines = map['logLines'];
    return EmbeddedTerminalInitSnapshot(
      running: map['running'] == true,
      completed: map['completed'] == true,
      success: map['success'] as bool?,
      progress: ((map['progress'] as num?)?.toDouble() ?? 0.0).clamp(0.0, 1.0),
      stage: (map['stage'] as String? ?? '').trim(),
      logLines: rawLogLines is List
          ? rawLogLines.map((line) => line.toString()).toList(growable: false)
          : const <String>[],
      startedAt: _parseEmbeddedTerminalInitTimestamp(map['startedAt']),
      completedAt: _parseEmbeddedTerminalInitTimestamp(map['completedAt']),
      phase: (map['phase'] as String?)?.trim(),
      distribution: (map['distribution'] as String?)?.trim().isNotEmpty == true
          ? EmbeddedTerminalDistribution.fromId(map['distribution'] as String?)
          : null,
      downloadedBytes: (map['downloadedBytes'] as num?)?.toInt() ?? 0,
      totalBytes: (map['totalBytes'] as num?)?.toInt() ?? 0,
      error: (map['error'] as String?)?.trim(),
    );
  }
}

DateTime? _parseEmbeddedTerminalInitTimestamp(dynamic rawValue) {
  if (rawValue is! num) {
    return null;
  }
  final millis = rawValue.toInt();
  if (millis <= 0) {
    return null;
  }
  return DateTime.fromMillisecondsSinceEpoch(millis);
}

class EmbeddedTerminalRuntimeStatus {
  const EmbeddedTerminalRuntimeStatus({
    required this.supported,
    required this.runtimeReady,
    required this.basePackagesReady,
    required this.allReady,
    required this.missingCommands,
    required this.message,
    required this.nodeReady,
    required this.nodeVersion,
    required this.nodeMinMajor,
    required this.pnpmReady,
    required this.pnpmVersion,
    required this.workspaceAccessGranted,
  });

  final bool supported;
  final bool runtimeReady;
  final bool basePackagesReady;
  final bool allReady;
  final List<String> missingCommands;
  final String message;
  final bool nodeReady;
  final String? nodeVersion;
  final int nodeMinMajor;
  final bool pnpmReady;
  final String? pnpmVersion;
  final bool workspaceAccessGranted;

  factory EmbeddedTerminalRuntimeStatus.fromMap(Map<dynamic, dynamic> map) {
    final rawMissing = map['missingCommands'];
    return EmbeddedTerminalRuntimeStatus(
      supported: map['supported'] == true,
      runtimeReady: map['runtimeReady'] == true,
      basePackagesReady: map['basePackagesReady'] == true,
      allReady: map['allReady'] == true,
      missingCommands: rawMissing is List
          ? rawMissing.map((it) => it.toString()).toList(growable: false)
          : const <String>[],
      message: (map['message'] as String? ?? '').trim(),
      nodeReady: map['nodeReady'] == true,
      nodeVersion: (map['nodeVersion'] as String?)?.trim(),
      nodeMinMajor: (map['nodeMinMajor'] as num?)?.toInt() ?? 22,
      pnpmReady: map['pnpmReady'] == true,
      pnpmVersion: (map['pnpmVersion'] as String?)?.trim(),
      workspaceAccessGranted: map['workspaceAccessGranted'] == true,
    );
  }
}

class EmbeddedTerminalSetupStatus {
  const EmbeddedTerminalSetupStatus({required this.packages});

  final Map<String, bool> packages;

  factory EmbeddedTerminalSetupStatus.fromMap(Map<dynamic, dynamic>? map) {
    final rawPackages = map?['packages'];
    final packages = <String, bool>{};
    if (rawPackages is Map) {
      for (final entry in rawPackages.entries) {
        packages[entry.key.toString()] = entry.value == true;
      }
    }
    return EmbeddedTerminalSetupStatus(packages: packages);
  }
}

class EmbeddedTerminalSetupInventoryItem {
  const EmbeddedTerminalSetupInventoryItem({
    required this.ready,
    required this.version,
  });

  final bool? ready;
  final String? version;

  factory EmbeddedTerminalSetupInventoryItem.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    return EmbeddedTerminalSetupInventoryItem(
      ready: map?['ready'] is bool ? map!['ready'] as bool : null,
      version: (map?['version'] as String?)?.trim(),
    );
  }
}

class EmbeddedTerminalSetupInventory {
  const EmbeddedTerminalSetupInventory({required this.packages});

  final Map<String, EmbeddedTerminalSetupInventoryItem> packages;

  factory EmbeddedTerminalSetupInventory.fromMap(Map<dynamic, dynamic>? map) {
    final rawPackages = map?['packages'];
    final packages = <String, EmbeddedTerminalSetupInventoryItem>{};
    if (rawPackages is Map) {
      for (final entry in rawPackages.entries) {
        packages[entry.key
            .toString()] = EmbeddedTerminalSetupInventoryItem.fromMap(
          entry.value is Map
              ? Map<dynamic, dynamic>.from(entry.value as Map)
              : const <dynamic, dynamic>{},
        );
      }
    }
    return EmbeddedTerminalSetupInventory(packages: packages);
  }
}

class EmbeddedTerminalSetupResult {
  const EmbeddedTerminalSetupResult({
    required this.success,
    required this.message,
    required this.output,
  });

  final bool success;
  final String message;
  final String output;

  factory EmbeddedTerminalSetupResult.fromMap(Map<dynamic, dynamic>? map) {
    return EmbeddedTerminalSetupResult(
      success: map?['success'] == true,
      message: (map?['message'] as String? ?? '').trim(),
      output: (map?['output'] as String? ?? '').trim(),
    );
  }
}

class EmbeddedTerminalSetupSessionSnapshot {
  const EmbeddedTerminalSetupSessionSnapshot({
    required this.sessionId,
    required this.running,
    required this.completed,
    required this.success,
    required this.message,
    required this.selectedPackageIds,
  });

  final String? sessionId;
  final bool running;
  final bool completed;
  final bool? success;
  final String message;
  final List<String> selectedPackageIds;

  bool get hasSession => (sessionId ?? '').trim().isNotEmpty;

  factory EmbeddedTerminalSetupSessionSnapshot.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    final rawSelectedPackageIds = map?['selectedPackageIds'];
    return EmbeddedTerminalSetupSessionSnapshot(
      sessionId: (map?['sessionId'] as String?)?.trim(),
      running: map?['running'] == true,
      completed: map?['completed'] == true,
      success: map?['success'] as bool?,
      message: (map?['message'] as String? ?? '').trim(),
      selectedPackageIds: rawSelectedPackageIds is List
          ? rawSelectedPackageIds
                .map((item) => item.toString())
                .where((item) => item.trim().isNotEmpty)
                .toList(growable: false)
          : const <String>[],
    );
  }
}

class EmbeddedTerminalAutoStartTask {
  const EmbeddedTerminalAutoStartTask({
    required this.id,
    required this.name,
    required this.command,
    required this.workingDirectory,
    required this.enabled,
    required this.running,
    required this.sessionId,
  });

  final String id;
  final String name;
  final String command;
  final String? workingDirectory;
  final bool enabled;
  final bool running;
  final String sessionId;

  factory EmbeddedTerminalAutoStartTask.fromMap(Map<dynamic, dynamic>? map) {
    return EmbeddedTerminalAutoStartTask(
      id: (map?['id'] as String? ?? '').trim(),
      name: (map?['name'] as String? ?? '').trim(),
      command: (map?['command'] as String? ?? '').trim(),
      workingDirectory: (map?['workingDirectory'] as String?)?.trim(),
      enabled: map?['enabled'] != false,
      running: map?['running'] == true,
      sessionId: (map?['sessionId'] as String? ?? '').trim(),
    );
  }
}

class EmbeddedTerminalAutoStartTaskList {
  const EmbeddedTerminalAutoStartTaskList({required this.tasks});

  final List<EmbeddedTerminalAutoStartTask> tasks;

  factory EmbeddedTerminalAutoStartTaskList.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    final rawTasks = map?['tasks'];
    return EmbeddedTerminalAutoStartTaskList(
      tasks: rawTasks is List
          ? rawTasks
                .map(
                  (item) => EmbeddedTerminalAutoStartTask.fromMap(
                    item is Map
                        ? Map<dynamic, dynamic>.from(item)
                        : const <dynamic, dynamic>{},
                  ),
                )
                .toList(growable: false)
          : const <EmbeddedTerminalAutoStartTask>[],
    );
  }
}

class EmbeddedTerminalAutoStartTaskRunResult {
  const EmbeddedTerminalAutoStartTaskRunResult({
    required this.taskId,
    required this.started,
    required this.alreadyRunning,
    required this.message,
    required this.sessionId,
  });

  final String taskId;
  final bool started;
  final bool alreadyRunning;
  final String message;
  final String sessionId;

  factory EmbeddedTerminalAutoStartTaskRunResult.fromMap(
    Map<dynamic, dynamic>? map,
  ) {
    return EmbeddedTerminalAutoStartTaskRunResult(
      taskId: (map?['taskId'] as String? ?? '').trim(),
      started: map?['started'] == true,
      alreadyRunning: map?['alreadyRunning'] == true,
      message: (map?['message'] as String? ?? '').trim(),
      sessionId: (map?['sessionId'] as String? ?? '').trim(),
    );
  }
}

Stream<EmbeddedTerminalInitProgress> get embeddedTerminalInitProgressStream {
  return _specialPermissionEvents.receiveBroadcastStream().map((event) {
    final payload = event is Map
        ? Map<dynamic, dynamic>.from(event)
        : const <dynamic, dynamic>{};
    return EmbeddedTerminalInitProgress.fromMap(payload);
  });
}

Future<EmbeddedTerminalInitSnapshot> getEmbeddedTerminalInitSnapshot() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalInitSnapshot',
  );
  return EmbeddedTerminalInitSnapshot.fromMap(result ?? const {});
}

Future<bool> cancelEmbeddedTerminalInit() async {
  return await spePermission.invokeMethod<bool>('cancelEmbeddedTerminalInit') ??
      false;
}

Future<EmbeddedTerminalRuntimeStatus> getEmbeddedTerminalRuntimeStatus() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalRuntimeStatus',
  );
  return EmbeddedTerminalRuntimeStatus.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupStatus> getEmbeddedTerminalSetupStatus() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalSetupStatus',
  );
  return EmbeddedTerminalSetupStatus.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupInventory>
getEmbeddedTerminalSetupInventory() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalSetupInventory',
  );
  return EmbeddedTerminalSetupInventory.fromMap(result ?? const {});
}

Future<EmbeddedTerminalDistribution> getEmbeddedTerminalDistribution() async {
  final result = await spePermission.invokeMethod<String>(
    'getEmbeddedTerminalDistribution',
  );
  return EmbeddedTerminalDistribution.fromId(result);
}

Future<EmbeddedTerminalDistribution> setEmbeddedTerminalDistribution(
  EmbeddedTerminalDistribution distribution,
) async {
  final result = await spePermission.invokeMethod<String>(
    'setEmbeddedTerminalDistribution',
    <String, dynamic>{'distribution': distribution.id},
  );
  return EmbeddedTerminalDistribution.fromId(result);
}

Future<EmbeddedTerminalDistribution> switchEmbeddedTerminalDistribution(
  EmbeddedTerminalDistribution distribution,
) async {
  final result = await spePermission.invokeMethod<String>(
    'switchEmbeddedTerminalDistribution',
    <String, dynamic>{'distribution': distribution.id},
  );
  return EmbeddedTerminalDistribution.fromId(result);
}

Future<EmbeddedTerminalSetupSessionSnapshot>
getEmbeddedTerminalSetupSessionSnapshot() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalSetupSessionSnapshot',
  );
  return EmbeddedTerminalSetupSessionSnapshot.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupResult> installEmbeddedTerminalPackages(
  List<String> packageIds,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'installEmbeddedTerminalPackages',
    {'packageIds': packageIds},
  );
  return EmbeddedTerminalSetupResult.fromMap(result ?? const {});
}

Future<EmbeddedTerminalSetupSessionSnapshot> startEmbeddedTerminalSetupSession(
  List<String> packageIds,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'startEmbeddedTerminalSetupSession',
    {'packageIds': packageIds},
  );
  return EmbeddedTerminalSetupSessionSnapshot.fromMap(result ?? const {});
}

Future<void> dismissEmbeddedTerminalSetupSession() async {
  await spePermission.invokeMethod<void>('dismissEmbeddedTerminalSetupSession');
}

Future<EmbeddedTerminalAutoStartTaskList>
getEmbeddedTerminalAutoStartTasks() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'getEmbeddedTerminalAutoStartTasks',
  );
  return EmbeddedTerminalAutoStartTaskList.fromMap(result ?? const {});
}

Future<EmbeddedTerminalAutoStartTask> saveEmbeddedTerminalAutoStartTask({
  String? id,
  required String name,
  required String command,
  String? workingDirectory,
  required bool enabled,
}) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'saveEmbeddedTerminalAutoStartTask',
    <String, dynamic>{
      'id': id,
      'name': name,
      'command': command,
      'workingDirectory': workingDirectory,
      'enabled': enabled,
    },
  );
  return EmbeddedTerminalAutoStartTask.fromMap(result ?? const {});
}

Future<void> deleteEmbeddedTerminalAutoStartTask(String id) async {
  await spePermission.invokeMethod<void>(
    'deleteEmbeddedTerminalAutoStartTask',
    {'id': id},
  );
}

Future<EmbeddedTerminalAutoStartTaskRunResult> runEmbeddedTerminalAutoStartTask(
  String id,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'runEmbeddedTerminalAutoStartTask',
    <String, dynamic>{'id': id},
  );
  return EmbeddedTerminalAutoStartTaskRunResult.fromMap(result ?? const {});
}

Future<void> openNativeTerminal({
  bool openSetup = false,
  List<String> setupPackageIds = const <String>[],
}) async {
  await spePermission.invokeMethod<void>('openNativeTerminal', {
    'openSetup': openSetup,
    'setupPackageIds': setupPackageIds,
  });
}

class PrivilegedShizukuDetail {
  const PrivilegedShizukuDetail({
    required this.installed,
    required this.running,
    required this.binderReady,
    required this.serviceBound,
    required this.permissionGranted,
    this.version,
  });

  final bool installed;
  final bool running;
  final bool binderReady;
  final bool serviceBound;
  final bool permissionGranted;
  final int? version;

  factory PrivilegedShizukuDetail.fromMap(Map<dynamic, dynamic>? map) {
    return PrivilegedShizukuDetail(
      installed: map?['installed'] == true,
      running: map?['running'] == true,
      binderReady: map?['binderReady'] == true,
      serviceBound: map?['serviceBound'] == true,
      permissionGranted: map?['permissionGranted'] == true,
      version: (map?['version'] as num?)?.toInt(),
    );
  }
}

class PrivilegedSuDetail {
  const PrivilegedSuDetail({
    this.binaryPath,
  });

  final String? binaryPath;

  factory PrivilegedSuDetail.fromMap(Map<dynamic, dynamic>? map) {
    return PrivilegedSuDetail(
      binaryPath: map?['binaryPath'] as String?,
    );
  }
}

class PrivilegedStatusSnapshot {
  const PrivilegedStatusSnapshot({
    required this.selected,
    required this.granted,
    required this.authority,
    this.uid,
    required this.code,
    required this.message,
    required this.availableActions,
    this.shizuku,
    this.su,
  });

  final String selected;
  final bool granted;
  final String authority;
  final int? uid;
  final String code;
  final String message;
  final List<String> availableActions;
  final PrivilegedShizukuDetail? shizuku;
  final PrivilegedSuDetail? su;

  bool get isGranted => granted;
  bool get isNone => selected == 'none';
  bool get isShizuku => selected == 'shizuku';
  bool get isSu => selected == 'su';
  bool get isRoot => authority == 'root';
  bool get isAdb => authority == 'adb';

  factory PrivilegedStatusSnapshot.fromMap(Map<dynamic, dynamic>? map) {
    final rawActions = map?['availableActions'];
    final shizukuMap = map?['shizuku'] as Map<dynamic, dynamic>?;
    final suMap = map?['su'] as Map<dynamic, dynamic>?;
    final selectedStr =
        (map?['selected'] as String? ?? 'none').trim().toLowerCase();
    final codeStr =
        (map?['code'] as String? ?? 'not_selected').trim().toLowerCase();
    final authorityStr =
        (map?['authority'] as String? ?? 'none').trim().toLowerCase();

    return PrivilegedStatusSnapshot(
      selected: selectedStr,
      granted: map?['granted'] == true || codeStr == 'granted',
      authority: authorityStr,
      uid: (map?['uid'] as num?)?.toInt(),
      code: codeStr,
      message: (map?['message'] as String? ?? '').trim(),
      availableActions: rawActions is List
          ? rawActions.map((item) => item.toString()).toList(growable: false)
          : const <String>[],
      shizuku: shizukuMap != null
          ? PrivilegedShizukuDetail.fromMap(shizukuMap)
          : null,
      su: suMap != null ? PrivilegedSuDetail.fromMap(suMap) : null,
    );
  }

  factory PrivilegedStatusSnapshot.fallback() {
    return const PrivilegedStatusSnapshot(
      selected: 'none',
      granted: false,
      authority: 'none',
      code: 'not_selected',
      message: '',
      availableActions: <String>[],
    );
  }

  String get localizedSelectedSourceName {
    switch (selected) {
      case 'shizuku':
        return 'Shizuku';
      case 'su':
        return LegacyTextLocalizer.isEnglish
            ? 'Direct Root (KernelSU)'
            : '直接 Root (KernelSU)';
      case 'none':
      default:
        return LegacyTextLocalizer.isEnglish ? 'Disabled' : '未启用';
    }
  }

  String get localizedStatusLabel {
    switch (code) {
      case 'granted':
        if (authority == 'root') {
          return LegacyTextLocalizer.isEnglish
              ? 'Granted (root)'
              : '已授权（Root）';
        } else if (authority == 'adb') {
          return LegacyTextLocalizer.isEnglish
              ? 'Granted (adb)'
              : '已授权（ADB）';
        }
        return LegacyTextLocalizer.isEnglish ? 'Granted' : '已授权';
      case 'not_selected':
        return LegacyTextLocalizer.isEnglish ? 'Not configured' : '未配置';
      case 'shizuku_not_installed':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku not installed'
            : '未安装 Shizuku';
      case 'shizuku_not_running':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku not running'
            : 'Shizuku 未运行';
      case 'shizuku_binder_dead':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku disconnected'
            : 'Shizuku 连接中断';
      case 'shizuku_permission_denied':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku not granted'
            : 'Shizuku 未授权';
      case 'su_not_authorized':
        return LegacyTextLocalizer.isEnglish
            ? 'Authorization pending'
            : '待验证授权';
      case 'su_denied':
        return LegacyTextLocalizer.isEnglish
            ? 'Root authorization denied'
            : 'Root 授权被拒绝';
      case 'su_unavailable':
        return LegacyTextLocalizer.isEnglish
            ? 'su unavailable'
            : 'su 不可用';
      case 'authority_unexpected':
        return LegacyTextLocalizer.isEnglish
            ? 'Unexpected authority'
            : '权限异常';
      default:
        return LegacyTextLocalizer.isEnglish ? 'Unauthorized' : '未授权';
    }
  }

  String get localizedGuide {
    switch (code) {
      case 'granted':
        if (authority == 'root') {
          final uidText = uid != null ? ' (UID: $uid)' : '';
          return LegacyTextLocalizer.isEnglish
              ? 'Privileged access active via Root$uidText. High-privilege actions and sessions are enabled with full device access; every command still requires confirmation.'
              : '已通过 Root$uidText 取得特权访问。高权限工具与会话已就绪，具备设备完全控制能力；每次命令执行仍需确认。';
        } else if (authority == 'adb') {
          final uidText = uid != null ? ' (UID: $uid)' : '';
          return LegacyTextLocalizer.isEnglish
              ? 'Privileged access active via ADB$uidText. High-privilege actions and sessions are enabled under ADB shell limits; every command still requires confirmation.'
              : '已通过 ADB$uidText 取得特权访问。高权限工具与会话已就绪，受 ADB shell 权限边界约束；每次命令执行仍需确认。';
        }
        return LegacyTextLocalizer.isEnglish
            ? 'Privileged access is ready.'
            : '特权访问已授权就绪。';
      case 'not_selected':
        return LegacyTextLocalizer.isEnglish
            ? 'Privileged access source is not selected. Choose Shizuku or Direct Root (KernelSU) to enable privileged system actions.'
            : '尚未选择特权访问来源。可按需选择 Shizuku 或直接 Root (KernelSU) 以启用高级系统能力。';
      case 'shizuku_not_installed':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku is not installed. Install Shizuku, start the service, and grant permission to Omnibot.'
            : '设备未安装 Shizuku。请先安装 Shizuku 并启动服务，然后向小万授权。';
      case 'shizuku_not_running':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku service is not running. Open Shizuku to start its service.'
            : 'Shizuku 服务未运行。请打开 Shizuku 应用启动服务。';
      case 'shizuku_binder_dead':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku connection was interrupted. Please reopen Shizuku to reconnect.'
            : '与 Shizuku 服务的连接已断开，请重新打开 Shizuku 恢复连接。';
      case 'shizuku_permission_denied':
        return LegacyTextLocalizer.isEnglish
            ? 'Shizuku permission not granted. Open Shizuku and allow Omnibot.'
            : '尚未在 Shizuku 中授予小万权限。请在 Shizuku 应用中允许授权。';
      case 'su_not_authorized':
        return LegacyTextLocalizer.isEnglish
            ? 'Direct Root (KernelSU) selected. Tap authorize to test su availability and verify UID.'
            : '已选择直接 Root (KernelSU)。请点击下方按钮验证 Root 授权与 UID 探针。';
      case 'su_denied':
        return LegacyTextLocalizer.isEnglish
            ? 'Root authorization was denied. Please allow Omnibot in KernelSU / Magisk / APatch.'
            : 'Root 授权请求被拒绝。请在 KernelSU / APatch / Magisk 管理器中允许小万的 Root 权限。';
      case 'su_unavailable':
        return LegacyTextLocalizer.isEnglish
            ? 'su binary was not found or is not executable. Note that KernelSU/root compatibility is not guaranteed on all devices.'
            : '未找到可用且可执行的 su 二进制文件。直接 Root (KernelSU) 不保证在所有设备与系统上均可用。';
      case 'authority_unexpected':
        return LegacyTextLocalizer.isEnglish
            ? 'Privileged probe returned an unexpected UID.'
            : '特权探针返回的 UID 不符合要求。';
      default:
        return LegacyTextLocalizer.isEnglish
            ? 'Privileged access is not authorized.'
            : '特权访问未授权。';
    }
  }
}

Future<PrivilegedStatusSnapshot> getPrivilegedStatus() async {
  try {
    final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
      'getPrivilegedStatus',
    );
    return PrivilegedStatusSnapshot.fromMap(result);
  } catch (e) {
    debugPrint('读取特权状态失败: $e');
    return PrivilegedStatusSnapshot.fallback();
  }
}

Future<PrivilegedStatusSnapshot> setPrivilegedSource(String source) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'setPrivilegedSource',
    {'source': source},
  );
  return PrivilegedStatusSnapshot.fromMap(result);
}

Future<PrivilegedStatusSnapshot> authorizePrivilegedSource() async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'authorizePrivilegedSource',
  );
  return PrivilegedStatusSnapshot.fromMap(result);
}

Future<bool> openPrivilegedSourceApp() async {
  try {
    return await spePermission.invokeMethod<bool>('openPrivilegedSourceApp') ??
        false;
  } catch (e) {
    debugPrint('打开特权来源应用失败: $e');
    return false;
  }
}

Future<PrivilegedStatusSnapshot?> showPrivilegedSourceSheet(
  BuildContext context,
) {
  return PrivilegedSourceSheet.show(context);
}

Future<bool> ensurePrivilegedPermission(BuildContext context) async {
  final status = await getPrivilegedStatus();
  if (status.isGranted) {
    return true;
  }
  if (!context.mounted) {
    return false;
  }
  final result = await showPrivilegedSourceSheet(context);
  return result?.isGranted ?? (await getPrivilegedStatus()).isGranted;
}

Future<bool> requestPermission(List<String> permissions) async {
  try {
    final hasPermission = await spePermission.invokeMethod(
      'requestPermissions',
      {'permissions': permissions},
    );
    return hasPermission == "Success";
  } catch (e) {
    debugPrint('请求权限失败: $e');
    return false;
  }
}

Future<bool> isTermuxInstalled() async {
  try {
    return await spePermission.invokeMethod<bool>('isTermuxInstalled') ?? false;
  } catch (e) {
    debugPrint('检查 Termux 安装状态失败: $e');
    return false;
  }
}

Future<bool> openTermuxApp() async {
  try {
    return await spePermission.invokeMethod<bool>('openTermuxApp') ?? false;
  } catch (e) {
    debugPrint('打开 Termux 失败: $e');
    return false;
  }
}

Future<bool> isTermuxRunCommandPermissionGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isTermuxRunCommandPermissionGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查 Termux RUN_COMMAND 权限失败: $e');
    return false;
  }
}

Future<bool> requestTermuxRunCommandPermission() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'requestTermuxRunCommandPermission',
        ) ??
        false;
  } catch (e) {
    debugPrint('请求 Termux RUN_COMMAND 权限失败: $e');
    return false;
  }
}

Future<bool> ensureInstalledAppsPermission() async {
  try {
    final hasPermission =
        await spePermission.invokeMethod<bool>(
          'isInstalledAppsPermissionGranted',
        ) ??
        false;
    if (hasPermission) {
      return true;
    }
    await openInstalledAppsSettings();
  } catch (e) {
    debugPrint('检查应用列表读取权限失败: $e');
  }
  return false;
}

Future<void> openInstalledAppsSettings() async {
  await spePermission.invokeMethod('openInstalledAppsSettings');
}

Future<void> openAppDetailsSettings() async {
  await spePermission.invokeMethod('openAppDetailsSettings');
}

Future<bool> isNotificationPermissionGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isNotificationPermissionGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查通知权限失败: $e');
    return false;
  }
}

Future<bool> requestNotificationPermission() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'requestNotificationPermission',
        ) ??
        false;
  } catch (e) {
    debugPrint('请求通知权限失败: $e');
    return false;
  }
}

Future<bool> ensureNotificationPermission() async {
  if (await isNotificationPermissionGranted()) {
    return true;
  }
  return requestNotificationPermission();
}

Future<bool> isWorkspaceStorageAccessGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isWorkspaceStorageAccessGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查内置 workspace 状态失败: $e');
    return false;
  }
}

Future<void> openWorkspaceStorageSettings() async {
  await spePermission.invokeMethod('openWorkspaceStorageSettings');
}

Future<bool> isPublicStorageAccessGranted() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isPublicStorageAccessGranted',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查公共文件访问权限失败: $e');
    return false;
  }
}

Future<void> openPublicStorageSettings() async {
  await spePermission.invokeMethod('openPublicStorageSettings');
}

Future<Map<String, dynamic>> prepareTermuxLiveWrapper({
  List<String>? packageIds,
}) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'prepareTermuxLiveWrapper',
    <String, dynamic>{if (packageIds != null) 'packageIds': packageIds},
  );
  return Map<String, dynamic>.from(result ?? const {});
}

Future<bool> isUnknownAppInstallAllowed() async {
  try {
    return await spePermission.invokeMethod<bool>(
          'isUnknownAppInstallAllowed',
        ) ??
        false;
  } catch (e) {
    debugPrint('检查未知应用安装权限失败: $e');
    return false;
  }
}

Future<void> openUnknownAppInstallSettings() async {
  await spePermission.invokeMethod('openUnknownAppInstallSettings');
}

Future<Map<String, dynamic>> downloadAndInstallTermuxApk(
  String downloadUrl,
) async {
  final result = await spePermission.invokeMethod<Map<dynamic, dynamic>>(
    'downloadAndInstallTermuxApk',
    {'downloadUrl': downloadUrl},
  );
  return Map<String, dynamic>.from(result ?? const {});
}

Future<bool> isBackgroundRunAllowed() async {
  try {
    return await spePermission.invokeMethod<bool>('isBackgroundRunAllowed') ??
        false;
  } catch (e) {
    debugPrint('检查后台运行权限失败: $e');
    return false;
  }
}
