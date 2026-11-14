import 'dart:async';

import 'package:flutter/services.dart';
import 'package:ui/services/agent_tool_call_parser.dart';
import 'package:ui/services/agent_message_kinds.dart';
import 'package:ui/models/conversation_outbox.dart';

// 卡片推送
typedef CardPushCallback<T> = void Function(Map<String, dynamic> cardData);
typedef ScheduledTaskCancelledCallBack = void Function(String taskId);
typedef ScheduledTaskExecuteNowCallBack = void Function(String taskId);

class ModelAvailabilityCheckResult {
  final bool available;
  final int? code;
  final String message;

  const ModelAvailabilityCheckResult({
    required this.available,
    required this.code,
    required this.message,
  });

  factory ModelAvailabilityCheckResult.fromMap(Map<dynamic, dynamic>? map) {
    if (map == null) {
      return const ModelAvailabilityCheckResult(
        available: false,
        code: null,
        message: '检测失败：返回为空',
      );
    }

    final codeValue = map['code'];
    int? code;
    if (codeValue is int) {
      code = codeValue;
    } else if (codeValue is String) {
      code = int.tryParse(codeValue);
    }

    return ModelAvailabilityCheckResult(
      available: map['available'] == true,
      code: code,
      message: (map['message'] ?? '').toString(),
    );
  }
}

class AgentToolEventData {
  final String taskId;
  final String cardId;
  final String toolName;
  final String displayName;
  final String toolTitle;
  final String toolType;
  final String uiStyle;
  final String? serverName;
  final String status;
  final String argsJson;
  final String progress;
  final String summary;
  final String resultPreviewJson;
  final String rawResultJson;
  final String terminalOutput;
  final String terminalOutputDelta;
  final String? terminalSessionId;
  final String terminalStreamState;
  final Map<String, dynamic> raw;
  final String? workspaceId;
  final String? interruptedBy;
  final String? interruptionReason;
  final List<Map<String, dynamic>> artifacts;
  final List<Map<String, dynamic>> actions;
  final String subagentStatusText;
  final List<Map<String, dynamic>> subagentEvents;
  final bool success;

  const AgentToolEventData({
    required this.taskId,
    this.cardId = '',
    required this.toolName,
    required this.displayName,
    this.toolTitle = '',
    required this.toolType,
    this.uiStyle = '',
    this.serverName,
    this.status = '',
    this.argsJson = '',
    this.progress = '',
    this.summary = '',
    this.resultPreviewJson = '',
    this.rawResultJson = '',
    this.terminalOutput = '',
    this.terminalOutputDelta = '',
    this.terminalSessionId,
    this.terminalStreamState = '',
    this.raw = const <String, dynamic>{},
    this.workspaceId,
    this.interruptedBy,
    this.interruptionReason,
    this.artifacts = const [],
    this.actions = const [],
    this.subagentStatusText = '',
    this.subagentEvents = const [],
    this.success = true,
  });

  factory AgentToolEventData.fromMap(Map<dynamic, dynamic>? map) {
    final raw = Map<String, dynamic>.from(
      (map ?? const <dynamic, dynamic>{}).map(
        (key, value) => MapEntry(key.toString(), value),
      ),
    );
    final itemType = _asNonEmptyString(raw['type']);
    final normalized = normalizeAgentToolCall(
      raw,
      itemType: itemType,
      fallbackToolType: _asNonEmptyString(raw['toolType']) ?? 'builtin',
      fallbackTitle:
          _asNonEmptyString(raw['toolTitle']) ??
          _asNonEmptyString(raw['displayName']),
      fallbackStatus: _asNonEmptyString(raw['status']) ?? '',
    );
    final explicitStatus = agentToolStatusIsExplicit(raw);
    final isAgentTool = itemType != null && isAgentToolItemType(itemType);
    return AgentToolEventData(
      taskId: (raw['taskId'] ?? '').toString(),
      cardId: (raw['cardId'] ?? '').toString(),
      toolName: _asNonEmptyString(raw['toolName']) ?? normalized.toolName,
      displayName:
          _asNonEmptyString(raw['displayName']) ??
          _asNonEmptyString(raw['toolName']) ??
          normalized.displayName,
      toolTitle: _asNonEmptyString(raw['toolTitle']) ?? normalized.toolTitle,
      toolType: _asNonEmptyString(raw['toolType']) ?? normalized.toolType,
      uiStyle:
          _asNonEmptyString(raw['uiStyle']) ??
          _asNonEmptyString(raw['ui_style']) ??
          (isAgentTool ? kAgentToolUiStyle : ''),
      serverName: _asNonEmptyString(raw['serverName']) ?? normalized.serverName,
      status: explicitStatus ? normalized.status : '',
      argsJson:
          _asNonEmptyString(raw['argsJson']) ??
          (raw['args'] is String ? _asNonEmptyString(raw['args']) : null) ??
          normalized.argsJson,
      progress: _asNonEmptyString(raw['progress']) ?? normalized.progress,
      summary: _asNonEmptyString(raw['summary']) ?? normalized.summary,
      resultPreviewJson:
          _asNonEmptyString(raw['resultPreviewJson']) ??
          normalized.resultPreviewJson,
      rawResultJson:
          _asNonEmptyString(raw['rawResultJson']) ?? normalized.rawResultJson,
      terminalOutput:
          _asNonEmptyString(raw['terminalOutput']) ?? normalized.terminalOutput,
      terminalOutputDelta: (raw['terminalOutputDelta'] ?? '').toString(),
      terminalSessionId: raw['terminalSessionId']?.toString(),
      terminalStreamState: (raw['terminalStreamState'] ?? '').toString(),
      raw: raw,
      workspaceId: raw['workspaceId']?.toString(),
      interruptedBy: raw['interruptedBy']?.toString(),
      interruptionReason: raw['interruptionReason']?.toString(),
      artifacts: ((raw['artifacts'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      actions: ((raw['actions'] as List?) ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList(),
      subagentStatusText: (raw['subagentStatusText'] ?? '').toString(),
      subagentEvents: _readSubagentEvents(
        raw['subagentEvents'] ?? raw['subagentEvent'],
      ),
      success: raw['success'] != false,
    );
  }

  static String? _asNonEmptyString(dynamic value) {
    final text = value?.toString().trim() ?? '';
    return text.isEmpty ? null : text;
  }

  static List<Map<String, dynamic>> _readSubagentEvents(dynamic value) {
    final rawEvents = value is List
        ? value
        : value is Map
        ? <dynamic>[value]
        : const <dynamic>[];
    return rawEvents
        .whereType<Map>()
        .map(
          (item) => item.map<String, dynamic>(
            (key, value) => MapEntry(key.toString(), value),
          ),
        )
        .toList(growable: false);
  }
}

class AssistsMessageService {
  static const MethodChannel assistCore = MethodChannel(
    'cn.com.omnimind.bot/AssistCoreEvent',
  );

  // 回调函数
  static CardPushCallback? _onCardPushCallback;

  static ScheduledTaskCancelledCallBack? _onScheduledTaskCancelledCallBack;
  static ScheduledTaskExecuteNowCallBack? _onScheduledTaskExecuteNowCallBack;
  static final StreamController<Map<String, dynamic>>
  _conversationListChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationMessagesChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _browserSessionSnapshotChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _conversationOutboxChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  static final StreamController<Map<String, dynamic>>
  _skillAssetsChangedController =
      StreamController<Map<String, dynamic>>.broadcast();
  // IM/WeChat/Telegram 等外部入口直推的用户消息：
  // 原生侧在写库后立刻 invokeMethod 发过来，runtime 直接插入气泡，
  // 不依赖 messagesChanged + DB reload 的事件链。
  static final List<void Function(Map<String, dynamic>)>
  _onExternalUserMessageAppendedCallbacks = [];

  static Stream<Map<String, dynamic>> get conversationListChangedStream =>
      _conversationListChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationMessagesChangedStream =>
      _conversationMessagesChangedController.stream;
  static Stream<Map<String, dynamic>> get browserSessionSnapshotChangedStream =>
      _browserSessionSnapshotChangedController.stream;
  static Stream<Map<String, dynamic>> get conversationOutboxChangedStream =>
      _conversationOutboxChangedController.stream;
  static Stream<Map<String, dynamic>> get skillAssetsChangedStream =>
      _skillAssetsChangedController.stream;

  static void initialize() {
    assistCore.setMethodCallHandler(_handleMethod);
  }

  static Future<dynamic> _handleMethod(MethodCall call) async {
    try {
      switch (call.method) {
        case 'onCardPush':
          final Map<String, dynamic> cardData = Map<String, dynamic>.from(
            call.arguments,
          );
          _onCardPushCallback?.call(cardData['data']);
          break;

        case 'onConversationListChanged':
          _conversationListChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onConversationMessagesChanged':
          _conversationMessagesChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onExternalUserMessageAppended':
          final data = Map<String, dynamic>.from(
            (call.arguments as Map?) ?? const <String, dynamic>{},
          );
          for (final callback in List<void Function(Map<String, dynamic>)>.from(
            _onExternalUserMessageAppendedCallbacks,
          )) {
            try {
              callback(data);
            } catch (_) {}
          }
          break;
        case 'onBrowserSessionSnapshotUpdated':
          _browserSessionSnapshotChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'conversation_outbox_changed':
          _conversationOutboxChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'skill_assets_changed':
          _skillAssetsChangedController.add(
            Map<String, dynamic>.from(
              (call.arguments as Map?) ?? const <String, dynamic>{},
            ),
          );
          break;
        case 'onScheduledTaskCancelled':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskCancelledCallBack?.call(data['taskId'] ?? '');
          break;
        case 'onScheduledTaskExecuteNow':
          final Map<String, dynamic> data = Map<String, dynamic>.from(
            call.arguments,
          );
          _onScheduledTaskExecuteNowCallBack?.call(data['taskId'] ?? '');
          break;
        default:
          print('未处理的方法: ${call.method}');
      }
    } catch (e) {
      print('处理方法调用时出错: $e');
      rethrow;
    }
  }

  // 设置回调函数
  static void setOnCardPushCallback(CardPushCallback callback) {
    _onCardPushCallback = callback;
  }

  static void setOnScheduledTaskCancelledCallBack(
    ScheduledTaskCancelledCallBack? callback,
  ) {
    _onScheduledTaskCancelledCallBack = callback;
  }

  static void setOnScheduledTaskExecuteNowCallBack(
    ScheduledTaskExecuteNowCallBack? callback,
  ) {
    _onScheduledTaskExecuteNowCallBack = callback;
  }

  static void addOnExternalUserMessageAppendedCallback(
    void Function(Map<String, dynamic>) callback,
  ) {
    if (!_onExternalUserMessageAppendedCallbacks.contains(callback)) {
      _onExternalUserMessageAppendedCallbacks.add(callback);
    }
  }

  static void removeOnExternalUserMessageAppendedCallback(
    void Function(Map<String, dynamic>) callback,
  ) {
    _onExternalUserMessageAppendedCallbacks.remove(callback);
  }

  // 发送按钮点击事件到Android端
  static Future<bool> clickButton(
    String taskID,
    String btnId,
    String value, //需要保留.因为有多选数据比如选择app列表,具体协议再定义
    bool isNeedPermission, //是否需要检查权限
  ) async {
    try {
      var result = await assistCore.invokeMethod('clickButton', {
        'taskID': taskID,
        'id': btnId,
        'value': value,
        'isNeedPermission': isNeedPermission,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('发送按钮点击事件失败: ${e.message}');
      return false;
    }
  }

  static Future<bool> copyToClipboard(String text) async {
    try {
      var result = await assistCore.invokeMethod('copyToClipboard', {
        'text': text,
      });
      return result == "SUCCESS";
    } on PlatformException catch (e) {
      print('复制到剪贴板失败: ${e.message}');
      return false;
    }
  }

  static Future<String?> getClipboardText() async {
    try {
      final result = await assistCore.invokeMethod<String>('getClipboardText');
      return result;
    } on PlatformException catch (e) {
      print('读取剪贴板失败: ${e.message}');
      return null;
    }
  }

  /// 获取已安装应用（包含中文应用名和包名）
  static Future<List<Map<String, dynamic>>> getInstalledApplications() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplications',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      print('获取已安装应用失败: ${e.message}');
      return [];
    }
  }

  /// 获取已安装应用（附带图标更新）
  static Future<List<Map<String, dynamic>>>
  getInstalledApplicationsWithIconUpdate() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getInstalledApplicationsWithIconUpdate',
      );
      if (result != null) {
        return result.map((e) => Map<String, dynamic>.from(e as Map)).toList();
      }
      return [];
    } on PlatformException catch (e) {
      print('获取已安装应用(附带图标更新)失败: ${e.message}');
      return [];
    }
  }

  /// 开源版不提供 suggestions
  static Future<List<Map<String, dynamic>>> getSuggestions() async {
    return [];
  }

  /// 查询统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<List<Map<String, dynamic>>> listAgentExactAlarms() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'listAgentExactAlarms',
      );
      if (result == null) return [];
      return result.map((item) {
        if (item is Map) {
          return Map<String, dynamic>.from(item);
        }
        return <String, dynamic>{};
      }).toList();
    } on PlatformException catch (e) {
      print('查询应用内闹钟失败: ${e.message}');
      return [];
    }
  }

  /// 删除统一 Agent 创建的应用内闹钟（exact_alarm）
  static Future<bool> deleteAgentExactAlarm(String alarmId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteAgentExactAlarm',
        {'alarmId': alarmId},
      );
      return result?['success'] == true;
    } on PlatformException catch (e) {
      print('删除应用内闹钟失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> getAlarmSettings() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'getAlarmSettings',
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('读取闹钟设置失败: ${e.message}');
      return {};
    }
  }

  static Future<Map<String, dynamic>> saveAlarmSettings({
    required String source,
    String? localPath,
    String? remoteUrl,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'saveAlarmSettings',
        {'source': source, 'localPath': localPath, 'remoteUrl': remoteUrl},
      );
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('保存闹钟设置失败: ${e.message}');
      return {'success': false, 'message': e.message ?? '保存失败'};
    }
  }

  /// 获取当前 nanoTime（毫秒级，System.nanoTime() / 1_000_000）
  static Future<int?> getNanoTime() async {
    try {
      final result = await assistCore.invokeMethod<int>('getNanoTime');
      return result;
    } on PlatformException catch (e) {
      print('获取nanoTime失败: ${e.message}');
      return null;
    }
  }

  /// 调用LLM chat接口（非流式）
  /// 用于修复JSON格式等场景
  static Future<String?> postLLMChat({
    required String text,
    String model = 'scene.dispatch.model',
  }) async {
    try {
      final result = await assistCore.invokeMethod<String>('postLLMChat', {
        'text': text,
        'model': model,
      });
      return result;
    } on PlatformException catch (e) {
      print('调用LLM chat失败: ${e.message}');
      return null;
    }
  }

  /// 生成记忆中心问候语（原生端优先使用标准 tool_calls）
  static Future<String?> generateMemoryGreeting({
    required List<Map<String, String>> records,
    String model = 'scene.compactor.context.chat',
  }) async {
    try {
      final payloadRecords = records
          .map(
            (item) => {
              'title': item['title'] ?? '',
              'description': item['description'] ?? '',
              'appName': item['appName'] ?? '',
            },
          )
          .toList();
      final result = await assistCore.invokeMethod<String>(
        'generateMemoryGreeting',
        {'model': model, 'records': payloadRecords},
      );
      return result;
    } on PlatformException catch (e) {
      print('生成记忆中心问候语失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>> compactConversationContext({
    required int conversationId,
    required String conversationMode,
    Map<String, dynamic>? modelOverride,
    String? reasoningEffort,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<Map<dynamic, dynamic>>('compactConversationContext', {
            'conversationId': conversationId,
            'conversationMode': conversationMode,
            if (modelOverride != null) 'modelOverride': modelOverride,
            if (reasoningEffort != null && reasoningEffort.trim().isNotEmpty)
              'reasoningEffort': reasoningEffort.trim(),
          });
      return Map<String, dynamic>.from(result ?? const {});
    } on PlatformException catch (e) {
      print('手动压缩上下文失败: ${e.message}');
      return {
        'compacted': false,
        'reason': 'failed',
        'message': e.message ?? '手动压缩上下文失败',
      };
    }
  }

  static Future<Map<String, dynamic>?> upsertWorkspaceScheduledTask(
    Map<String, dynamic> task,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'upsertWorkspaceScheduledTask',
        {'task': task},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('更新原生定时任务失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteWorkspaceScheduledTask(String taskId) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'deleteWorkspaceScheduledTask',
        {'taskId': taskId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      print('删除原生定时任务失败: ${e.message}');
      return false;
    }
  }

  static Future<int> syncWorkspaceScheduledTasks(
    List<Map<String, dynamic>> tasks,
  ) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'syncWorkspaceScheduledTasks',
        {'tasks': tasks},
      );
      if (result == null) return 0;
      final count = result['count'];
      if (count is int) return count;
      if (count is String) return int.tryParse(count) ?? 0;
      return 0;
    } on PlatformException catch (e) {
      print('同步原生定时任务失败: ${e.message}');
      return 0;
    }
  }

  static Future<List<Map<String, dynamic>>> listAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'agentSkillList',
      );
      return (result ?? const [])
          .whereType<Map>()
          .map((item) => item.map((k, v) => MapEntry(k.toString(), v)))
          .toList();
    } on PlatformException catch (e) {
      print('读取 Agent skills 失败: ${e.message}');
      return const [];
    }
  }

  static Future<Map<String, dynamic>?> installAgentSkill({
    required String sourcePath,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstall',
        {'sourcePath': sourcePath},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('安装 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> setAgentSkillEnabled({
    required String skillId,
    required bool enabled,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSetEnabled',
        {'skillId': skillId, 'enabled': enabled},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('切换 Agent skill 启用状态失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> deleteAgentSkill({required String skillId}) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillDelete',
        {'skillId': skillId},
      );
      if (result == null) return false;
      return result['deleted'] == true;
    } on PlatformException catch (e) {
      print('删除 Agent skill 失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>?> installBuiltinAgentSkill({
    required String skillId,
  }) async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillInstallBuiltin',
        {'skillId': skillId},
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('安装内置 Agent skill 失败: ${e.message}');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> syncOfficialAgentSkills() async {
    try {
      final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
        'agentSkillSyncOfficialRepository',
      );
      if (result == null) return null;
      return result.map((k, v) => MapEntry(k.toString(), v));
    } on PlatformException catch (e) {
      print('同步官方 Agent skills 失败: ${e.message}');
      return null;
    }
  }

  /// 打开应用市场
  static Future<String?> openAPPMarket(String packageName) async {
    try {
      final result = await assistCore.invokeMethod<String>('openAPPMarket', {
        'packageName': packageName,
      });
      return result;
    } on PlatformException catch (e) {
      print('调用openAPPMarket失败: ${e.message}');
      return null;
    }
  }

  /// 获取桌面包名
  static Future<List<String>?> getDeskTopPackageName() async {
    try {
      final result = await assistCore.invokeMethod<List<dynamic>>(
        'getDeskTopPackageName',
      );
      if (result != null) {
        return result.map((e) => e.toString()).toList();
      }
      return null;
    } on PlatformException catch (e) {
      print('获取桌面包名失败: ${e.message}');
      return null;
    }
  }

  static Future<bool> setPreventScreenSleepDuringTasksEnabled(
    bool enabled,
  ) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setPreventScreenSleepDuringTasksEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('Failed to sync prevent sleep setting: ${e.message}');
      return false;
    }
  }

  static Future<bool> setTaskCompletionNotificationEnabled(bool enabled) async {
    try {
      final result = await assistCore.invokeMethod<String>(
        'setTaskCompletionNotificationEnabled',
        {'enabled': enabled},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print(
        'Failed to sync task completion notification setting: ${e.message}',
      );
      return false;
    }
  }

  static Future<bool> setVisibleChatConversation({
    int? conversationId,
    String? conversationMode,
    bool visible = true,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<String>('setVisibleChatConversation', {
            'conversationId': conversationId ?? 0,
            'visible': visible,
            if (conversationMode != null) 'mode': conversationMode,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('Failed to sync visible chat conversation: ${e.message}');
      return false;
    }
  }

  static Future<bool> showTaskCompletionNotification({
    required String title,
    required String message,
    int? conversationId,
    String? conversationMode,
  }) async {
    try {
      final result = await assistCore
          .invokeMethod<String>('showTaskCompletionNotification', {
            'title': title,
            'message': message,
            if (conversationId != null) 'conversationId': conversationId,
            if (conversationMode != null) 'conversationMode': conversationMode,
          });
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('Failed to show task completion notification: ${e.message}');
      return false;
    }
  }

  /// 跳转到主引擎路由
  static Future<bool> navigateToMainEngineRoute(String route) async {
    try {
      final result = await assistCore.invokeMethod(
        'navigateToMainEngineRoute',
        {'route': route},
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('跳转到主引擎路由失败: ${e.message}');
      return false;
    }
  }

  /// 显示定时任务倒计时提醒（原生浮层）
  static Future<bool> showScheduledTaskReminder({
    required String taskId,
    required String taskName,
    int countdownSeconds = 5,
  }) async {
    try {
      final result = await assistCore.invokeMethod(
        'showScheduledTaskReminder',
        {
          'taskId': taskId,
          'taskName': taskName,
          'countdownSeconds': countdownSeconds,
        },
      );
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('显示定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 隐藏定时任务倒计时提醒
  static Future<bool> hideScheduledTaskReminder() async {
    try {
      final result = await assistCore.invokeMethod('hideScheduledTaskReminder');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('隐藏定时任务提醒失败: ${e.message}');
      return false;
    }
  }

  /// 授权完成后重新打开ChatBot
  static Future<bool> reopenChatBotAfterAuth() async {
    try {
      final result = await assistCore.invokeMethod('reopenChatBotAfterAuth');
      return result == 'SUCCESS';
    } on PlatformException catch (e) {
      print('重新打开ChatBot失败: ${e.message}');
      return false;
    }
  }

  static Future<Map<String, dynamic>> _invokeSkillAsset(
    String method,
    Map<String, dynamic> arguments,
  ) async {
    final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
      method,
      arguments,
    );
    if (result == null) {
      throw PlatformException(code: 'skill_asset_invalid', message: '技能资产返回为空');
    }
    return Map<String, dynamic>.from(result);
  }

  static Future<Map<String, dynamic>> listAgentSkillSuggestions({
    int? conversationId,
    String? turnId,
    String? skillId,
    int offset = 0,
    int limit = 20,
  }) => _invokeSkillAsset('agentSkillSuggestionList', {
    if (conversationId != null) 'conversationId': conversationId,
    if (turnId != null) 'turnId': turnId,
    if (skillId != null) 'skillId': skillId,
    'offset': offset,
    'limit': limit,
  });

  static Future<Map<String, dynamic>> saveAgentSkillSuggestion({
    required String candidateId,
    required int expectedRevision,
    required String name,
    required String description,
    required String bodyMarkdown,
  }) => _invokeSkillAsset('agentSkillSuggestionSave', {
    'candidateId': candidateId,
    'expectedRevision': expectedRevision,
    'name': name,
    'description': description,
    'bodyMarkdown': bodyMarkdown,
  });

  static Future<Map<String, dynamic>> approveAgentSkillSuggestion({
    required String candidateId,
    required int expectedRevision,
    required String targetSkillId,
    String? expectedCurrentContentHash,
  }) => _invokeSkillAsset('agentSkillSuggestionApprove', {
    'candidateId': candidateId,
    'expectedRevision': expectedRevision,
    'targetSkillId': targetSkillId,
    'expectedCurrentContentHash': expectedCurrentContentHash,
  });

  static Future<Map<String, dynamic>> rejectAgentSkillSuggestion({
    required String candidateId,
    required int expectedRevision,
  }) => _invokeSkillAsset('agentSkillSuggestionReject', {
    'candidateId': candidateId,
    'expectedRevision': expectedRevision,
  });

  static Future<Map<String, dynamic>> regenerateAgentSkillSuggestion({
    required String candidateId,
    required int expectedRevision,
  }) => _invokeSkillAsset('agentSkillSuggestionRegenerate', {
    'candidateId': candidateId,
    'expectedRevision': expectedRevision,
  });

  static Future<Map<String, dynamic>> setAgentSkillSuggestionsEnabled({
    required bool enabled,
  }) => _invokeSkillAsset('agentSkillSuggestionsSetEnabled', {'enabled': enabled});

  static Future<Map<String, dynamic>> submitAgentSkillFeedback({
    required String skillId,
    required String revisionId,
    required int conversationId,
    required String sessionId,
    required String turnId,
    required int expectedRevision,
    required String action,
  }) => _invokeSkillAsset('agentSkillFeedback', {
    'skillId': skillId,
    'revisionId': revisionId,
    'conversationId': conversationId,
    'sessionId': sessionId,
    'turnId': turnId,
    'expectedRevision': expectedRevision,
    'action': action,
  });

  static Future<Map<String, dynamic>> restoreAgentSkillRevision({
    required String skillId,
    required String revisionId,
    required int expectedRevision,
    required String expectedCurrentContentHash,
  }) => _invokeSkillAsset('agentSkillRestoreRevision', {
    'skillId': skillId,
    'revisionId': revisionId,
    'expectedRevision': expectedRevision,
    'expectedCurrentContentHash': expectedCurrentContentHash,
  });

  static Future<Map<String, dynamic>> _invokeWorkspaceChange(
    String method,
    Map<String, dynamic> arguments,
  ) async {
    final result = await assistCore.invokeMethod<Map<dynamic, dynamic>>(
      method,
      arguments,
    );
    if (result == null) {
      throw PlatformException(
        code: 'workspace_change_unavailable',
        message: '变更记录返回为空',
      );
    }
    return Map<String, dynamic>.from(result);
  }

  static Future<Map<String, dynamic>> workspaceChangesList({
    required int conversationId,
    String? turnId,
  }) => _invokeWorkspaceChange('workspaceChangesList', {
    'conversationId': conversationId,
    if (turnId != null) 'turnId': turnId,
  });

  static Future<Map<String, dynamic>> workspaceChangesListByWorkspace({
    required String workspaceId,
    int offset = 0,
    int limit = 20,
  }) => _invokeWorkspaceChange('workspaceChangesListByWorkspace', {
    'workspaceId': workspaceId,
    'offset': offset,
    'limit': limit,
  });

  static Future<Map<String, dynamic>> workspaceChangePreview({
    required String changeId,
    int? conversationId,
    String? workspaceId,
  }) => _invokeWorkspaceChange('workspaceChangePreview', {
    'changeId': changeId,
    if (conversationId != null) 'conversationId': conversationId,
    if (workspaceId != null) 'workspaceId': workspaceId,
  });

  static Future<Map<String, dynamic>> workspaceChangeRestore({
    required String changeId,
    required int expectedRevision,
    required Map<String, String?> expectedAfterHashes,
    int? conversationId,
    String? workspaceId,
  }) => _invokeWorkspaceChange('workspaceChangeRestore', {
    'changeId': changeId,
    'expectedRevision': expectedRevision,
    'expectedAfterHashes': expectedAfterHashes,
    if (conversationId != null) 'conversationId': conversationId,
    if (workspaceId != null) 'workspaceId': workspaceId,
  });

  static Future<Map<String, dynamic>> workspaceChangeDiscard({
    required String changeId,
    required int expectedRevision,
    int? conversationId,
    String? workspaceId,
  }) => _invokeWorkspaceChange('workspaceChangeDiscard', {
    'changeId': changeId,
    'expectedRevision': expectedRevision,
    if (conversationId != null) 'conversationId': conversationId,
    if (workspaceId != null) 'workspaceId': workspaceId,
  });

  /// 把所选附件导入既有持久附件目录，待发箱只保存引用。
  static Future<List<Map<String, dynamic>>> conversationOutboxImportAttachments({
    required int conversationId,
    required List<Map<String, dynamic>> attachments,
  }) async {
    if (attachments.isEmpty) return const [];
    try {
      final result = await assistCore.invokeMethod<dynamic>(
        'conversationOutboxImportAttachments',
        {'conversationId': conversationId, 'attachments': attachments},
      );
      if (result is! Map || result['attachments'] is! List) {
        throw const OutboxMutationException(
          code: 'outbox_attachment_unavailable',
          message: '附件导入失败，请重新选择',
        );
      }
      return (result['attachments'] as List)
          .map((item) => Map<String, dynamic>.from(item as Map))
          .toList();
    } on PlatformException catch (error) {
      throw OutboxMutationException.fromPlatformException(error);
    }
  }

  /// 获取指定对话的待发箱快照
  static Future<OutboxSnapshot> conversationOutboxList(
    int conversationId,
  ) async {
    final result = await assistCore.invokeMethod<dynamic>(
      'conversationOutboxList',
      {'conversationId': conversationId},
    );
    if (result is Map) {
      return OutboxSnapshot.fromJson(Map<String, dynamic>.from(result));
    }
    return OutboxSnapshot.empty;
  }

  /// 变更指定对话的待发箱（enqueue|update|remove|resume|stop_and_send）
  static Future<OutboxSnapshot> conversationOutboxMutate({
    required int conversationId,
    required String operation,
    required OutboxMutation request,
  }) async {
    try {
      final result = await assistCore.invokeMethod<dynamic>(
        'conversationOutboxMutate',
        {
          'conversationId': conversationId,
          'operation': operation,
          'request': request.toJson(),
        },
      );
      if (result is Map) {
        return OutboxSnapshot.fromJson(Map<String, dynamic>.from(result));
      }
      return OutboxSnapshot.empty;
    } on PlatformException catch (e) {
      throw OutboxMutationException.fromPlatformException(e);
    }
  }
}
