import 'dart:async';

import 'package:flutter/services.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/agent_diff_parser.dart';
import 'package:ui/services/assists_core_service.dart';

/// Single managed workspace change entry recorded during tool execution.
class WorkspaceChangeItem {
  const WorkspaceChangeItem({
    required this.changeId,
    required this.conversationId,
    required this.sessionId,
    required this.turnId,
    required this.toolCallId,
    required this.workspaceRoot,
    required this.operation,
    this.sourceRelativePath,
    this.targetRelativePath,
    this.beforeSha256,
    this.afterSha256,
    this.beforeExists = false,
    this.afterExists = false,
    required this.snapshotStatus,
    required this.revision,
    required this.createdAt,
    this.snapshotBytes = 0,
    this.unavailableReason,
    this.sourceConversationDeleted = false,
  });

  final String changeId;
  final int conversationId;
  final String sessionId;
  final String turnId;
  final String toolCallId;
  final String workspaceRoot;
  final String operation; // "create" | "write" | "move"
  final String? sourceRelativePath;
  final String? targetRelativePath;
  final String? beforeSha256;
  final String? afterSha256;
  final bool beforeExists;
  final bool afterExists;
  final String snapshotStatus; // "ready" | "unavailable" | "restored" | "conflict"
  final int revision;
  final int createdAt;
  final int snapshotBytes;
  final String? unavailableReason;
  final bool sourceConversationDeleted;
  bool get isReady => snapshotStatus == 'ready';
  bool get isRestored => snapshotStatus == 'restored';
  bool get isConflict => snapshotStatus == 'conflict';
  bool get isUnavailable => snapshotStatus == 'unavailable';
  String? get unavailableReasonText {
    if (unavailableReason == null || unavailableReason!.isEmpty) return null;
    final isEnglish = LegacyTextLocalizer.isEnglish;
    switch (unavailableReason) {
      case 'missing_after_hashes':
        return isEnglish
            ? 'Missing file state hash, cannot restore'
            : '缺少恢复所需的文件状态指纹，无法恢复';
      case 'outside_workspace':
        return isEnglish ? 'Path is outside workspace root' : '路径在工作区规范根目录之外';
      case 'symlink_rejected':
        return isEnglish ? 'Symlink snapshot rejected' : '不支持符号链接文件快照';
      case 'sensitive_path':
        return isEnglish ? 'Sensitive path, snapshot skipped' : '命中敏感路径，未记录快照';
      case 'sensitive_content':
        return isEnglish
            ? 'Sensitive content detected, snapshot skipped'
            : '内容命中凭据特征，未记录快照';
      case 'file_too_large':
        return isEnglish ? 'File exceeds 5MB limit' : '单文件超过 5MB 限制';
      case 'quota_exceeded':
        return isEnglish ? 'Global snapshot quota exceeded' : '快照存储超过 100MB 配额';
      case 'directory_unsupported':
        return isEnglish ? 'Directory move unsupported' : '暂不支持整目录移动快照';
      case 'missing_before_snapshot':
        return isEnglish ? 'Original file snapshot missing' : '修改前快照未能成功存储';
      case 'operation_failed':
        return isEnglish ? 'Tool execution failed' : '工具执行未成功';
      default:
        return unavailableReason;
    }
  }


  String get displayPath =>
      targetRelativePath ?? sourceRelativePath ?? (operation == 'create' ? 'new_file' : 'file');

  String get operationLabel {
    final isEnglish = LegacyTextLocalizer.isEnglish;
    switch (operation.toLowerCase()) {
      case 'create':
        return isEnglish ? 'Created' : '新建';
      case 'move':
        return isEnglish ? 'Moved' : '移动';
      case 'write':
      default:
        return isEnglish ? 'Modified' : '修改';
    }
  }

  String get statusLabel {
    final isEnglish = LegacyTextLocalizer.isEnglish;
    switch (snapshotStatus.toLowerCase()) {
      case 'ready':
        return isEnglish ? 'Restorable' : '可恢复';
      case 'restored':
        return isEnglish ? 'Restored' : '已恢复';
      case 'conflict':
        return isEnglish ? 'Conflict' : '已冲突';
      case 'unavailable':
      default:
        return isEnglish ? 'Unavailable' : '不可恢复';
    }
  }

  factory WorkspaceChangeItem.fromMap(Map<dynamic, dynamic> map) {
    return WorkspaceChangeItem(
      changeId: (map['changeId'] ?? map['id'] ?? '').toString(),
      conversationId: _parseInt(map['conversationId']),
      sessionId: (map['sessionId'] ?? '').toString(),
      turnId: (map['turnId'] ?? '').toString(),
      toolCallId: (map['toolCallId'] ?? '').toString(),
      workspaceRoot: (map['workspaceRoot'] ?? '').toString(),
      operation: (map['operation'] ?? 'write').toString(),
      sourceRelativePath: map['sourceRelativePath']?.toString(),
      targetRelativePath: map['targetRelativePath']?.toString(),
      beforeSha256: map['beforeSha256']?.toString(),
      afterSha256: map['afterSha256']?.toString(),
      beforeExists: map['beforeExists'] == true,
      afterExists: map['afterExists'] == true,
      snapshotStatus: (map['snapshotStatus'] ?? 'ready').toString(),
      revision: _parseInt(map['revision']),
      createdAt: _parseInt(map['createdAt']),
      snapshotBytes: _parseInt(map['snapshotBytes']),
      unavailableReason: map['unavailableReason']?.toString(),
      sourceConversationDeleted: map['sourceConversationDeleted'] == true,
    );
  }
}

/// Detailed preview of a workspace change, including diff and restore suitability.
class WorkspaceChangePreview {
  const WorkspaceChangePreview({
    required this.item,
    required this.relativePath,
    required this.diffText,
    required this.isBinary,
    required this.canRestore,
    this.expectedAfterHashes = const {},
    this.diffSummary,
  });

  final WorkspaceChangeItem item;
  final String relativePath;
  final String diffText;
  final bool isBinary;
  final bool canRestore;
  final Map<String, String?> expectedAfterHashes;
  final AgentDiffSummary? diffSummary;

  factory WorkspaceChangePreview.fromMap(Map<dynamic, dynamic> map) {
    final item = WorkspaceChangeItem.fromMap(
      (map['item'] as Map?) ?? map,
    );
    final diffText = (map['diffText'] ?? '').toString();
    final isBinary = map['isBinary'] == true;
    final canRestore = map['canRestore'] == true;
    final relativePath = (map['relativePath'] ?? item.displayPath).toString();

    final rawHashes = map['expectedAfterHashes'];
    final expectedAfterHashes = <String, String?>{};
    if (rawHashes is Map) {
      for (final entry in rawHashes.entries) {
        final k = entry.key.toString().trim();
        if (k.isNotEmpty) {
          expectedAfterHashes[k] = entry.value?.toString();
        }
      }
    }

    final hasServerHashes = expectedAfterHashes.isNotEmpty;
    final effectiveCanRestore = (map['canRestore'] == true) && hasServerHashes;

    final effectiveItem = (!hasServerHashes && item.isReady)
        ? WorkspaceChangeItem(
            changeId: item.changeId,
            conversationId: item.conversationId,
            sessionId: item.sessionId,
            turnId: item.turnId,
            toolCallId: item.toolCallId,
            workspaceRoot: item.workspaceRoot,
            operation: item.operation,
            sourceRelativePath: item.sourceRelativePath,
            targetRelativePath: item.targetRelativePath,
            beforeSha256: item.beforeSha256,
            afterSha256: item.afterSha256,
            beforeExists: item.beforeExists,
            afterExists: item.afterExists,
            snapshotStatus: 'unavailable',
            revision: item.revision,
            createdAt: item.createdAt,
            snapshotBytes: item.snapshotBytes,
            unavailableReason: item.unavailableReason ?? 'missing_after_hashes',
          )
        : item;

    AgentDiffSummary? summary;
    if (!isBinary && diffText.trim().isNotEmpty) {
      summary = parseAgentDiffText(diffText);
    }

    return WorkspaceChangePreview(
      item: effectiveItem,
      relativePath: relativePath,
      diffText: diffText,
      isBinary: isBinary,
      canRestore: effectiveCanRestore,
      expectedAfterHashes: expectedAfterHashes,
      diffSummary: summary,
    );
  }
}

/// Result of attempting to restore a workspace change.
class WorkspaceChangeRestoreResult {
  const WorkspaceChangeRestoreResult({
    required this.status,
    required this.success,
    this.revision,
    required this.message,
  });

  final String status; // "restored" | "workspace_change_busy" | "workspace_change_conflict" | "error"
  final bool success;
  final int? revision;
  final String message;

  bool get isRestored => status == 'restored' || success;
  bool get isBusy => status == 'workspace_change_busy';
  bool get isConflict => status == 'workspace_change_conflict';

  factory WorkspaceChangeRestoreResult.fromMap(Map<dynamic, dynamic> map) {
    return WorkspaceChangeRestoreResult(
      status: (map['status'] ?? (map['success'] == true ? 'restored' : 'error')).toString(),
      success: map['success'] == true,
      revision: map['revision'] != null ? _parseInt(map['revision']) : null,
      message: (map['message'] ?? '').toString(),
    );
  }
}

/// Result of discarding a workspace change snapshot.
class WorkspaceChangeDiscardResult {
  const WorkspaceChangeDiscardResult({
    required this.status,
    required this.success,
    this.revision,
    required this.message,
  });

  final String status; // "discarded" | "error"
  final bool success;
  final int? revision;
  final String message;

  bool get isDiscarded => status == 'discarded' || success;

  factory WorkspaceChangeDiscardResult.fromMap(Map<dynamic, dynamic> map) {
    final discarded = map['discarded'] == true || map['success'] == true;
    return WorkspaceChangeDiscardResult(
      status: (map['status'] ?? (discarded ? 'discarded' : 'error')).toString(),
      success: discarded,
      revision: map['revision'] != null ? _parseInt(map['revision']) : null,
      message: (map['message'] ?? (discarded ? 'Snapshot discarded' : '')).toString(),
    );
  }
}

/// Flutter frontend service for workspace changes management and restoration.
class WorkspaceChangeService {
  static const MethodChannel _channel = AssistsMessageService.assistCore;

  /// Lists recorded workspace changes for a conversation and optional turn.
  static Future<List<WorkspaceChangeItem>> listChanges({
    required int conversationId,
    String? turnId,
  }) async {
    try {
      final result = await _channel.invokeMethod<dynamic>(
        'workspaceChangesList',
        <String, dynamic>{
          'conversationId': conversationId,
          if (turnId != null) 'turnId': turnId,
        },
      );
      if (result is List) {
        return result
            .whereType<Map>()
            .map((item) => WorkspaceChangeItem.fromMap(item))
            .toList(growable: false);
      }
      if (result is Map && result['items'] is List) {
        return (result['items'] as List)
            .whereType<Map>()
            .map((item) => WorkspaceChangeItem.fromMap(item))
            .toList(growable: false);
      }
      return const [];
    } on PlatformException {
      return const [];
    }
  }

  /// Lists workspace changes by canonical workspace root with pagination.
  static Future<List<WorkspaceChangeItem>> listChangesByWorkspace({
    required String workspaceId,
    int offset = 0,
    int limit = 20,
  }) async {
    try {
      final result = await _channel.invokeMethod<dynamic>(
        'workspaceChangesListByWorkspace',
        <String, dynamic>{
          'workspaceId': workspaceId,
          'offset': offset,
          'limit': limit,
        },
      );
      if (result is List) {
        return result
            .whereType<Map>()
            .map((item) => WorkspaceChangeItem.fromMap(item))
            .toList(growable: false);
      }
      if (result is Map && result['items'] is List) {
        return (result['items'] as List)
            .whereType<Map>()
            .map((item) => WorkspaceChangeItem.fromMap(item))
            .toList(growable: false);
      }
      return const [];
    } on PlatformException {
      return const [];
    }
  }

  /// Previews a single change snapshot including unified diff and restoration safety.
  static Future<WorkspaceChangePreview?> previewChange({
    required String changeId,
    int? conversationId,
    String? workspaceId,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'workspaceChangePreview',
        <String, dynamic>{
          'changeId': changeId,
          if (conversationId != null) 'conversationId': conversationId,
          if (workspaceId != null) 'workspaceId': workspaceId,
        },
      );
      if (result == null || result.isEmpty) {
        return null;
      }
      return WorkspaceChangePreview.fromMap(result);
    } on Exception {
      return null;
    }
  }

  /// Attempts to restore a change with optimistic CAS revision and expected after hashes.
  static Future<WorkspaceChangeRestoreResult> restoreChange({
    required String changeId,
    required int expectedRevision,
    required Map<String, String?> expectedAfterHashes,
    int? conversationId,
    String? workspaceId,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'workspaceChangeRestore',
        <String, dynamic>{
          'changeId': changeId,
          'expectedRevision': expectedRevision,
          'expectedAfterHashes': expectedAfterHashes,
          if (conversationId != null) 'conversationId': conversationId,
          if (workspaceId != null) 'workspaceId': workspaceId,
        },
      );
      if (result == null || result.isEmpty) {
        return const WorkspaceChangeRestoreResult(
          status: 'error',
          success: false,
          message: 'Restore response was empty',
        );
      }
      return WorkspaceChangeRestoreResult.fromMap(result);
    } on PlatformException catch (e) {
      return WorkspaceChangeRestoreResult(
        status: e.code,
        success: false,
        message: e.message ?? e.code,
      );
    }
  }

  /// Discards a change snapshot record after explicit user confirmation.
  static Future<WorkspaceChangeDiscardResult> discardChange({
    required String changeId,
    required int expectedRevision,
    int? conversationId,
    String? workspaceId,
  }) async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>(
        'workspaceChangeDiscard',
        <String, dynamic>{
          'changeId': changeId,
          'expectedRevision': expectedRevision,
          if (conversationId != null) 'conversationId': conversationId,
          if (workspaceId != null) 'workspaceId': workspaceId,
        },
      );
      if (result == null || result.isEmpty) {
        return const WorkspaceChangeDiscardResult(
          status: 'error',
          success: false,
          message: 'Discard response was empty',
        );
      }
      return WorkspaceChangeDiscardResult.fromMap(result);
    } on PlatformException catch (e) {
      return WorkspaceChangeDiscardResult(
        status: e.code,
        success: false,
        message: e.message ?? e.code,
      );
    }
  }
}

int _parseInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value) ?? 0;
  return 0;
}
