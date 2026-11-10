import 'package:flutter/services.dart';

/// 虚拟屏状态。
enum VirtualScreenLifecycleState {
  absent('absent'),
  running('running'),
  waiting('waiting'),
  retained('retained');

  final String value;
  const VirtualScreenLifecycleState(this.value);

  static VirtualScreenLifecycleState fromString(String? state) {
    switch (state?.toLowerCase()) {
      case 'absent':
        return VirtualScreenLifecycleState.absent;
      case 'running':
        return VirtualScreenLifecycleState.running;
      case 'waiting':
        return VirtualScreenLifecycleState.waiting;
      case 'retained':
        return VirtualScreenLifecycleState.retained;
      default:
        throw FormatException('Invalid virtual screen state: $state');
    }
  }

  String get label {
    switch (this) {
      case VirtualScreenLifecycleState.absent:
        return '未开启';
      case VirtualScreenLifecycleState.running:
        return '运行中';
      case VirtualScreenLifecycleState.waiting:
        return '等待中';
      case VirtualScreenLifecycleState.retained:
        return '画面留存';
    }
  }
}

/// 虚拟屏只读状态快照。
class VirtualScreenSnapshot {
  const VirtualScreenSnapshot({
    required this.state,
    this.displayId,
    this.sessionId,
    this.width,
    this.height,
    this.dpi,
    this.conversationId,
    this.taskId,
    this.turnId,
    this.taskTitle,
    this.statusText,
    this.appLabel,
    this.packageName,
    this.overlayAvailable = false,
  });

  final VirtualScreenLifecycleState state;
  final int? displayId;
  final String? sessionId;
  final int? width;
  final int? height;
  final int? dpi;
  final String? conversationId;
  final String? taskId;
  final String? turnId;
  final String? taskTitle;
  final String? statusText;
  final String? appLabel;
  final String? packageName;
  final bool overlayAvailable;

  bool get isAbsent => state == VirtualScreenLifecycleState.absent;
  bool get isRunning => state == VirtualScreenLifecycleState.running;
  bool get isWaiting => state == VirtualScreenLifecycleState.waiting;
  bool get isRetained => state == VirtualScreenLifecycleState.retained;
  bool get isActiveTask => isRunning || isWaiting;

  factory VirtualScreenSnapshot.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null || map.isEmpty) {
      throw const FormatException('Virtual screen payload is null or empty');
    }
    final rawState = map['state']?.toString();
    if (rawState == null || rawState.isEmpty) {
      throw const FormatException('Missing required state in virtual screen payload');
    }
    return VirtualScreenSnapshot(
      state: VirtualScreenLifecycleState.fromString(rawState),
      displayId: (map['displayId'] as num?)?.toInt(),
      sessionId: map['sessionId']?.toString(),
      width: (map['width'] as num?)?.toInt(),
      height: (map['height'] as num?)?.toInt(),
      dpi: (map['dpi'] as num?)?.toInt(),
      conversationId: map['conversationId']?.toString(),
      taskId: map['taskId']?.toString(),
      turnId: map['turnId']?.toString(),
      taskTitle: map['taskTitle']?.toString(),
      statusText: map['statusText']?.toString(),
      appLabel: map['appLabel']?.toString(),
      packageName: map['packageName']?.toString(),
      overlayAvailable: map['overlayAvailable'] == true,
    );
  }
}

/// 虚拟屏设备管理通道服务。
class VirtualScreenService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );

  /// 获取当前虚拟屏状态快照
  static Future<VirtualScreenSnapshot> getState() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
      'getVirtualScreenState',
    );
    return VirtualScreenSnapshot.fromMap(result);
  }

  /// 打开虚拟屏预览窗口（全屏观看，不取消）
  static Future<void> openPreview({int? displayId, String? sessionId}) async {
    await _channel.invokeMethod<void>(
      'openVirtualScreenPreview',
      <String, dynamic>{
        'displayId': displayId,
        'sessionId': sessionId,
      },
    );
  }

  /// 精确停止官方任务（核验精确身份，禁止旧快照取消新 Turn）
  static Future<void> stopTask({
    int? displayId,
    String? sessionId,
    String? taskId,
    String? turnId,
  }) async {
    await _channel.invokeMethod<void>(
      'stopVirtualScreenTask',
      <String, dynamic>{
        'displayId': displayId,
        'sessionId': sessionId,
        'taskId': taskId,
        'turnId': turnId,
      },
    );
  }

  /// 关闭释放虚拟屏（仅在无活跃任务与租约时允许，不删除聊天与记忆）
  static Future<void> closeScreen({int? displayId, String? sessionId}) async {
    await _channel.invokeMethod<void>(
      'closeVirtualScreen',
      <String, dynamic>{
        'displayId': displayId,
        'sessionId': sessionId,
      },
    );
  }
}
