import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:ui/models/conversation_outbox.dart';
import 'package:ui/services/assists_core_service.dart';

/// 管理单个会话的待发箱状态与操作
class ChatConversationOutboxService extends ChangeNotifier {
  ChatConversationOutboxService({int? initialConversationId}) {
    _subscription = AssistsMessageService.conversationOutboxChangedStream
        .listen(_onOutboxChangedEvent);
    if (initialConversationId != null && initialConversationId > 0) {
      bindConversation(initialConversationId);
    }
  }

  int? _conversationId;
  OutboxSnapshot? _snapshot;
  bool _isLoading = false;
  String? _lastErrorMessage;
  StreamSubscription<Map<String, dynamic>>? _subscription;
  bool _isDisposed = false;

  int? get conversationId => _conversationId;
  OutboxSnapshot? get snapshot => _snapshot;
  List<OutboxItem> get activeItems => _snapshot?.activeItems ?? const [];
  bool get hasActiveItems => activeItems.isNotEmpty;
  bool get isLoading => _isLoading;
  String? get lastErrorMessage => _lastErrorMessage;

  /// 绑定到新会话。若为新对话（null），不调用原生以保持纯净草稿状态。
  void bindConversation(int? newConversationId) {
    if (_conversationId == newConversationId) return;
    _conversationId = newConversationId;
    _snapshot = null;
    _lastErrorMessage = null;
    if (!_isDisposed) {
      notifyListeners();
    }

    if (newConversationId != null && newConversationId > 0) {
      unawaited(refresh());
    }
  }

  /// 刷新当前会话的快照
  Future<void> refresh() async {
    final targetId = _conversationId;
    if (targetId == null || targetId <= 0) return;

    _isLoading = true;
    if (!_isDisposed) {
      notifyListeners();
    }
    try {
      final latest = await AssistsMessageService.conversationOutboxList(
        targetId,
      );
      if (!_isDisposed && _conversationId == targetId) {
        if (_snapshot == null || latest.revision >= _snapshot!.revision) {
          _snapshot = latest;
          _lastErrorMessage = null;
        }
      }
    } catch (e) {
      if (!_isDisposed && _conversationId == targetId) {
        _lastErrorMessage = e.toString();
      }
    } finally {
      if (!_isDisposed && _conversationId == targetId) {
        _isLoading = false;
        notifyListeners();
      }
    }
  }

  /// 入队新消息
  Future<OutboxSnapshot> enqueue({
    required String text,
    List<Map<String, dynamic>> attachments = const [],
    String? model,
    String? effort,
    String? conversationMode,
  }) async {
    final targetId = _conversationId;
    if (targetId == null || targetId <= 0) {
      throw StateError('Cannot enqueue outbox item without conversation id');
    }

    final safeAttachments = attachments.isNotEmpty
        ? await AssistsMessageService.conversationOutboxImportAttachments(
            conversationId: targetId,
            attachments: attachments,
          )
        : const <Map<String, dynamic>>[];

    final mutation = OutboxMutation(
      id: generateUuid(),
      expectedRevision: _snapshot?.revision,
      payload: {
        'text': text,
        'attachments': safeAttachments,
        if (model != null) 'model': model,
        if (effort != null) 'effort': effort,
        if (conversationMode != null) 'conversationMode': conversationMode,
      },
    );

    return _performMutation(targetId, 'enqueue', mutation);
  }

  /// 更新待发项文本与附件（编辑）
  Future<OutboxSnapshot> updateItem({
    required OutboxItem item,
    required String newText,
    List<Map<String, dynamic>>? newAttachments,
    int? expectedRevision,
  }) async {
    final targetId = item.conversationId;
    if (targetId <= 0) {
      throw StateError('Cannot mutate outbox without conversation id');
    }

    final rawAttachments = newAttachments ?? item.attachments;
    final safeAttachments = rawAttachments.isNotEmpty
        ? await AssistsMessageService.conversationOutboxImportAttachments(
            conversationId: targetId,
            attachments: rawAttachments,
          )
        : const <Map<String, dynamic>>[];

    final mutation = OutboxMutation(
      id: item.id,
      expectedRevision: _resolveExpectedRevision(item, expectedRevision),
      payload: {
        'text': newText,
        'attachments': safeAttachments,
      },
    );

    return _performMutation(targetId, 'update', mutation);
  }

  /// 移除待发项
  Future<OutboxSnapshot> removeItem({
    required OutboxItem item,
    int? expectedRevision,
  }) async {
    final targetId = item.conversationId;
    if (targetId <= 0) {
      throw StateError('Cannot mutate outbox without conversation id');
    }

    final mutation = OutboxMutation(
      id: item.id,
      expectedRevision: _resolveExpectedRevision(item, expectedRevision),
    );

    return _performMutation(targetId, 'remove', mutation);
  }

  /// 恢复发送（FIFO）
  Future<OutboxSnapshot> resumeItem({
    required OutboxItem item,
    int? expectedRevision,
  }) async {
    final targetId = item.conversationId;
    if (targetId <= 0) {
      throw StateError('Cannot mutate outbox without conversation id');
    }

    final mutation = OutboxMutation(
      id: item.id,
      expectedRevision: _resolveExpectedRevision(item, expectedRevision),
    );

    return _performMutation(targetId, 'resume', mutation);
  }

  /// 停止当前正在执行的任务并立刻投递指定项
  Future<OutboxSnapshot> stopCurrentAndSend({
    required OutboxItem item,
    required String? observedSessionId,
    required String? observedTurnId,
    int? expectedRevision,
  }) async {
    final targetId = item.conversationId;
    if (targetId <= 0) {
      throw StateError('Cannot mutate outbox without conversation id');
    }

    final mutation = OutboxMutation(
      id: item.id,
      expectedRevision: _resolveExpectedRevision(item, expectedRevision),
      expectedSessionId: observedSessionId,
      expectedTurnId: observedTurnId,
    );

    return _performMutation(targetId, 'stop_and_send', mutation);
  }

  /// 解析操作期望的待发箱版本。
  /// API expectedRevision 是整箱元数据 revision，而不是单项条目 revision。
  /// 若未显式传入 expectedRevision，且当前服务已绑定同会话并持有快照，则使用该会话快照的整箱 revision；
  /// 不能把另一绑定 conversation 的 snapshot 借给旧 item；无快照时也不以 null 绕过 CAS，必要时以 item.revision 作为 fail-safe 保留冲突。
  int _resolveExpectedRevision(OutboxItem item, int? explicitExpectedRevision) {
    if (explicitExpectedRevision != null) {
      return explicitExpectedRevision;
    }
    if (_conversationId == item.conversationId && _snapshot != null) {
      return _snapshot!.revision;
    }
    return item.revision;
  }

  Future<OutboxSnapshot> _performMutation(
    int targetId,
    String operation,
    OutboxMutation request,
  ) async {
    try {
      final updatedSnapshot =
          await AssistsMessageService.conversationOutboxMutate(
            conversationId: targetId,
            operation: operation,
            request: request,
          );
      if (!_isDisposed && _conversationId == targetId) {
        if (_snapshot == null || updatedSnapshot.revision >= _snapshot!.revision) {
          _snapshot = updatedSnapshot;
          _lastErrorMessage = null;
          notifyListeners();
        }
      }
      return updatedSnapshot;
    } on OutboxMutationException catch (e) {
      // 若发生版本冲突且携带最新快照，更新本地快照以同步最新 revision
      if (!_isDisposed && e.snapshot != null && _conversationId == targetId) {
        if (_snapshot == null || e.snapshot!.revision >= _snapshot!.revision) {
          _snapshot = e.snapshot;
          notifyListeners();
        }
      }
      rethrow;
    } catch (e) {
      rethrow;
    }
  }

  void _onOutboxChangedEvent(Map<String, dynamic> event) {
    if (_isDisposed) return;
    final rawId = event['conversationId'];
    final eventConversationId = rawId is int
        ? rawId
        : int.tryParse(rawId?.toString() ?? '');
    final rawRev = event['revision'];
    final eventRevision = rawRev is int
        ? rawRev
        : int.tryParse(rawRev?.toString() ?? '');
    if (eventConversationId != null &&
        eventConversationId == _conversationId &&
        eventConversationId > 0) {
      if (eventRevision != null &&
          _snapshot != null &&
          eventRevision < _snapshot!.revision) {
        return;
      }
      unawaited(refresh());
    }
  }

  @override
  void dispose() {
    _isDisposed = true;
    _subscription?.cancel();
    _subscription = null;
    super.dispose();
  }
  static final math.Random _random = math.Random.secure();

  /// 生成标准 RFC 4122 v4 UUID
  static String generateUuid() {
    final bytes = List<int>.generate(16, (_) => _random.nextInt(256));
    // Set version to 4
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    // Set variant to RFC 4122
    bytes[8] = (bytes[8] & 0x3f) | 0x80;

    String hex(int val, int len) => val.toRadixString(16).padLeft(len, '0');

    final b0 = hex(bytes[0], 2) +
        hex(bytes[1], 2) +
        hex(bytes[2], 2) +
        hex(bytes[3], 2);
    final b1 = hex(bytes[4], 2) + hex(bytes[5], 2);
    final b2 = hex(bytes[6], 2) + hex(bytes[7], 2);
    final b3 = hex(bytes[8], 2) + hex(bytes[9], 2);
    final b4 = hex(bytes[10], 2) +
        hex(bytes[11], 2) +
        hex(bytes[12], 2) +
        hex(bytes[13], 2) +
        hex(bytes[14], 2) +
        hex(bytes[15], 2);

    return '$b0-$b1-$b2-$b3-$b4';
  }
}
