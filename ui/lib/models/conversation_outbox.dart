import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// 待发项状态
class OutboxItemState {
  static const String pending = 'pending';
  static const String dispatching = 'dispatching';
  static const String paused = 'paused';
  static const String submitted = 'submitted';
}

/// 待发箱单项
@immutable
class OutboxItem {
  final String id;
  final int conversationId;
  final String agentId;
  final String state;
  final int revision;
  final int sequence;
  final String requestId;
  final String userMessageId;
  final String? blockedBySessionId;
  final String? blockedByTurnId;
  final int createdAt;
  final int updatedAt;
  final String? pauseReason;
  final String text;
  final List<Map<String, dynamic>> attachments;
  final Map<String, dynamic>? payload;

  const OutboxItem({
    required this.id,
    required this.conversationId,
    required this.agentId,
    required this.state,
    required this.revision,
    required this.sequence,
    required this.requestId,
    required this.userMessageId,
    this.blockedBySessionId,
    this.blockedByTurnId,
    required this.createdAt,
    required this.updatedAt,
    this.pauseReason,
    this.text = '',
    this.attachments = const [],
    this.payload,
  });

  bool get isPending => state == OutboxItemState.pending;
  bool get isDispatching => state == OutboxItemState.dispatching;
  bool get isPaused => state == OutboxItemState.paused;
  bool get isSubmitted => state == OutboxItemState.submitted;
  int get attachmentCount => attachments.length;

  factory OutboxItem.fromJson(Map<String, dynamic> json) {
    final payloadMap = json['payload'] is Map
        ? Map<String, dynamic>.from(json['payload'] as Map)
        : null;

    String resolvedText = (json['text'] ?? '').toString();
    if (resolvedText.isEmpty && payloadMap != null) {
      resolvedText = (payloadMap['text'] ?? '').toString();
    }

    List<Map<String, dynamic>> resolvedAttachments = const [];
    final rawAttachments = json['attachments'] ?? payloadMap?['attachments'];
    if (rawAttachments is List) {
      resolvedAttachments = rawAttachments
          .whereType<Map>()
          .map((item) => Map<String, dynamic>.from(item))
          .toList();
    }

    return OutboxItem(
      id: (json['id'] ?? '').toString(),
      conversationId: json['conversationId'] is int
          ? json['conversationId'] as int
          : int.tryParse(json['conversationId']?.toString() ?? '') ?? 0,
      agentId: (json['agentId'] ?? '').toString(),
      state: (json['state'] ?? OutboxItemState.pending).toString(),
      revision: json['revision'] is int
          ? json['revision'] as int
          : int.tryParse(json['revision']?.toString() ?? '') ?? 0,
      sequence: json['sequence'] is int
          ? json['sequence'] as int
          : int.tryParse(json['sequence']?.toString() ?? '') ?? 0,
      requestId: (json['requestId'] ?? '').toString(),
      userMessageId: (json['userMessageId'] ?? '').toString(),
      blockedBySessionId: json['blockedBySessionId']?.toString(),
      blockedByTurnId: json['blockedByTurnId']?.toString(),
      createdAt: json['createdAt'] is int
          ? json['createdAt'] as int
          : int.tryParse(json['createdAt']?.toString() ?? '') ?? 0,
      updatedAt: json['updatedAt'] is int
          ? json['updatedAt'] as int
          : int.tryParse(json['updatedAt']?.toString() ?? '') ?? 0,
      pauseReason: json['pauseReason']?.toString(),
      text: resolvedText,
      attachments: resolvedAttachments,
      payload: payloadMap,
    );
  }

  Map<String, dynamic> toJson() => {
    'id': id,
    'conversationId': conversationId,
    'agentId': agentId,
    'state': state,
    'revision': revision,
    'sequence': sequence,
    'requestId': requestId,
    'userMessageId': userMessageId,
    if (blockedBySessionId != null) 'blockedBySessionId': blockedBySessionId,
    if (blockedByTurnId != null) 'blockedByTurnId': blockedByTurnId,
    'createdAt': createdAt,
    'updatedAt': updatedAt,
    if (pauseReason != null) 'pauseReason': pauseReason,
    'text': text,
    'attachments': attachments,
    if (payload != null) 'payload': payload,
  };
}

/// 待发箱快照
@immutable
class OutboxSnapshot {
  final int revision;
  final List<OutboxItem> items;

  const OutboxSnapshot({
    required this.revision,
    required this.items,
  });

  static const empty = OutboxSnapshot(revision: 0, items: []);

  /// 未完成投递（排队中、投递中、已暂停）的项，按 FIFO sequence 排序
  List<OutboxItem> get activeItems =>
      items.where((item) => !item.isSubmitted).toList();

  bool get hasActiveItems => activeItems.isNotEmpty;

  factory OutboxSnapshot.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'] as List? ?? const [];
    final itemsList = rawItems
        .whereType<Map>()
        .map((item) => OutboxItem.fromJson(Map<String, dynamic>.from(item)))
        .toList();

    // 保证 FIFO 顺序：sequence 升序，其次 createdAt 升序
    itemsList.sort((a, b) {
      final seqCmp = a.sequence.compareTo(b.sequence);
      if (seqCmp != 0) return seqCmp;
      return a.createdAt.compareTo(b.createdAt);
    });

    return OutboxSnapshot(
      revision: json['revision'] is int
          ? json['revision'] as int
          : int.tryParse(json['revision']?.toString() ?? '') ?? 0,
      items: itemsList,
    );
  }

  Map<String, dynamic> toJson() => {
    'revision': revision,
    'items': items.map((item) => item.toJson()).toList(),
  };
}

/// 待发箱变更操作请求
@immutable
class OutboxMutation {
  final String id;
  final int? expectedRevision;
  final Map<String, dynamic>? payload;
  final String? expectedSessionId;
  final String? expectedTurnId;

  const OutboxMutation({
    required this.id,
    this.expectedRevision,
    this.payload,
    this.expectedSessionId,
    this.expectedTurnId,
  });

  Map<String, dynamic> toJson() => {
    'id': id,
    if (expectedRevision != null) 'expectedRevision': expectedRevision,
    if (payload != null) 'payload': payload,
    if (expectedSessionId != null) 'expectedSessionId': expectedSessionId,
    if (expectedTurnId != null) 'expectedTurnId': expectedTurnId,
  };
}

/// 待发箱操作异常
class OutboxMutationException implements Exception {
  final String code;
  final String message;
  final OutboxSnapshot? snapshot;

  const OutboxMutationException({
    required this.code,
    required this.message,
    this.snapshot,
  });

  bool get isRevisionConflict => code == 'outbox_revision_conflict';
  bool get isAttachmentUnavailable => code == 'outbox_attachment_unavailable';
  bool get isEmpty => code == 'outbox_empty';
  bool get isTargetChanged => code == 'outbox_target_changed';

  factory OutboxMutationException.fromPlatformException(PlatformException e) {
    OutboxSnapshot? parsedSnapshot;
    final details = e.details;
    if (details is Map) {
      try {
        parsedSnapshot = OutboxSnapshot.fromJson(
          Map<String, dynamic>.from(details),
        );
      } catch (_) {}
    }
    return OutboxMutationException(
      code: e.code,
      message: e.message ?? e.code,
      snapshot: parsedSnapshot,
    );
  }

  @override
  String toString() => 'OutboxMutationException(code: $code, message: $message)';
}
