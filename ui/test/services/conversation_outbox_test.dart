import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_outbox.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/conversation_history_service.dart';
import 'package:ui/features/home/pages/chat/services/chat_conversation_outbox_service.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_outbox_pending_bar.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = AssistsMessageService.assistCore;
  final recordedCalls = <MethodCall>[];

  setUp(() {
    recordedCalls.clear();
    ConversationHistoryService.resetWriteAcknowledgements();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          recordedCalls.add(call);
          switch (call.method) {
            case 'conversationOutboxImportAttachments':
              final raw = call.arguments['attachments'] as List? ?? [];
              return {
                'attachments': raw.map((item) {
                  final m = Map<String, dynamic>.from(item as Map);
                  return {
                    'id': m['id'] ?? 'safe-id',
                    'name': m['name'] ?? 'file.bin',
                    'path': '/app/workspace/attachments/${m['name'] ?? 'file.bin'}',
                    'size': m['size'] ?? 1024,
                  };
                }).toList(),
              };
            case 'conversationOutboxList':
              return {
                'revision': 1,
                'items': <Map<String, dynamic>>[],
              };
            case 'conversationOutboxMutate':
              final args = call.arguments as Map;
              return {
                'revision': 2,
                'items': [
                  {
                    'id': (args['request'] as Map)['id'],
                    'conversationId': args['conversationId'],
                    'agentId': 'xiaowan-acp',
                    'state': 'pending',
                    'revision': 2,
                    'sequence': 1,
                    'requestId': 'req-1',
                    'userMessageId': 'user-1',
                    'createdAt': 1000,
                    'updatedAt': 1000,
                    'text': 'Enqueued task',
                    'attachments': [],
                  }
                ],
              };
            case 'replaceConversationMessages':
              return null;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('OutboxSnapshot FIFO ordering', () {
    test('sorts active items strictly in FIFO order by sequence then createdAt', () {
      final raw = {
        'revision': 10,
        'items': [
          {
            'id': 'item-3',
            'conversationId': 42,
            'agentId': 'xiaowan-acp',
            'state': 'pending',
            'revision': 10,
            'sequence': 3,
            'requestId': 'r-3',
            'userMessageId': 'u-3',
            'createdAt': 3000,
            'updatedAt': 3000,
            'text': 'Third item',
            'attachments': [],
          },
          {
            'id': 'item-1',
            'conversationId': 42,
            'agentId': 'xiaowan-acp',
            'state': 'pending',
            'revision': 10,
            'sequence': 1,
            'requestId': 'r-1',
            'userMessageId': 'u-1',
            'createdAt': 1000,
            'updatedAt': 1000,
            'text': 'First item',
            'attachments': [],
          },
          {
            'id': 'item-2',
            'conversationId': 42,
            'agentId': 'xiaowan-acp',
            'state': 'paused',
            'revision': 10,
            'sequence': 2,
            'requestId': 'r-2',
            'userMessageId': 'u-2',
            'createdAt': 2000,
            'updatedAt': 2000,
            'pauseReason': '等待历史保存',
            'text': 'Second item',
            'attachments': [
              {'name': 'doc.pdf'}
            ],
          },
          {
            'id': 'item-submitted',
            'conversationId': 42,
            'agentId': 'xiaowan-acp',
            'state': 'submitted',
            'revision': 10,
            'sequence': 0,
            'requestId': 'r-0',
            'userMessageId': 'u-0',
            'createdAt': 500,
            'updatedAt': 500,
            'text': 'Already submitted receipt',
            'attachments': [],
          },
        ],
      };

      final snapshot = OutboxSnapshot.fromJson(raw);
      expect(snapshot.revision, 10);
      expect(snapshot.items.length, 4);

      // activeItems excludes 'submitted'
      final active = snapshot.activeItems;
      expect(active.length, 3);
      expect(active[0].id, 'item-1');
      expect(active[0].sequence, 1);
      expect(active[1].id, 'item-2');
      expect(active[1].sequence, 2);
      expect(active[1].isPaused, isTrue);
      expect(active[1].attachmentCount, 1);
      expect(active[2].id, 'item-3');
      expect(active[2].sequence, 3);
    });
  });

  group('Receipt persistence barrier', () {
    test('forces replaceConversationMessages when receipt is present even if messages unchanged', () async {
      final msg1 = ChatMessageModel(
        id: 'msg-1',
        type: 1,
        user: 1,
        content: {'text': 'hello'},
        createAt: DateTime.fromMillisecondsSinceEpoch(1000),
      );

      // Initial save establishes acknowledgment
      await ConversationHistoryService.saveConversationMessages(
        505,
        [msg1],
        mode: ConversationMode.agent,
      );
      expect(recordedCalls.any((c) => c.method == 'replaceConversationMessages'), isTrue);
      recordedCalls.clear();

      // Second save with unchanged messages and NO receipt short-circuits
      await ConversationHistoryService.saveConversationMessages(
        505,
        [msg1],
        mode: ConversationMode.agent,
      );
      expect(recordedCalls.any((c) => c.method == 'replaceConversationMessages'), isFalse);
      recordedCalls.clear();

      // Third save with unchanged messages BUT WITH settledPromptReceipt proceeds to native
      final receipt = {
        'sessionId': 'session-xyz',
        'turnId': 'turn-abc',
        'requestId': 'req-123',
      };
      await ConversationHistoryService.saveConversationMessages(
        505,
        [msg1],
        mode: ConversationMode.agent,
        settledPromptReceipt: receipt,
      );

      final nativeCall = recordedCalls.firstWhere(
        (c) => c.method == 'replaceConversationMessages',
      );
      final callArgs = nativeCall.arguments as Map;
      expect(callArgs['conversationId'], 505);
      expect(callArgs['allowHistoryRemoval'], isFalse);
      expect(callArgs['settledPromptReceipt'], receipt);
    });
  });

  group('ChatConversationOutboxService monotonicity, disposal and attachment import', () {
    test('enforces monotonic non-decreasing revision on list refresh and ignores stale revisions', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') {
              return {
                'revision': 5,
                'items': <Map<String, dynamic>>[],
              };
            }
            return null;
          });

      final service = ChatConversationOutboxService(initialConversationId: 101);
      await service.refresh();
      expect(service.snapshot?.revision, 5);

      // Host returns an older revision 3
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') {
              return {
                'revision': 3,
                'items': <Map<String, dynamic>>[],
              };
            }
            return null;
          });

      await service.refresh();
      // Monotonic protection: revision remains 5
      expect(service.snapshot?.revision, 5);

      service.dispose();
    });

    test('cross-conversation mutation does not overwrite active conversation snapshot', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') {
              return {
                'revision': 10,
                'items': <Map<String, dynamic>>[],
              };
            }
            if (call.method == 'conversationOutboxMutate') {
              final args = call.arguments as Map;
              return {
                'revision': 20,
                'items': [
                  {
                    'id': (args['request'] as Map)['id'],
                    'conversationId': args['conversationId'],
                    'agentId': 'xiaowan-acp',
                    'state': 'pending',
                    'revision': 20,
                    'sequence': 1,
                    'requestId': 'req-x',
                    'userMessageId': 'u-x',
                    'createdAt': 1000,
                    'updatedAt': 1000,
                    'text': 'Edited in old conv',
                    'attachments': [],
                  }
                ],
              };
            }
            return null;
          });

      // Service bound to conversation 200
      final service = ChatConversationOutboxService(initialConversationId: 200);
      await service.refresh();
      expect(service.snapshot?.revision, 10);

      const oldItem = OutboxItem(
        id: 'old-item-1',
        conversationId: 100,
        agentId: 'xiaowan-acp',
        state: 'pending',
        revision: 4,
        sequence: 1,
        requestId: 'req-old',
        userMessageId: 'u-old',
        createdAt: 500,
        updatedAt: 500,
        text: 'Original text in conv 100',
      );

      final result = await service.updateItem(item: oldItem, newText: 'New text');
      expect(result.revision, 20);

      // Active conversation 200's snapshot is NOT overwritten with conversation 100's snapshot
      expect(service.conversationId, 200);
      expect(service.snapshot?.revision, 10);

      service.dispose();
    });

    test('completing delayed async call after dispose does not notify listeners or throw', () async {
      final completer = Completer<Map<String, dynamic>>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') {
              return completer.future;
            }
            return null;
          });

      final service = ChatConversationOutboxService(initialConversationId: 300);
      var notifyCount = 0;
      service.addListener(() => notifyCount++);

      final refreshFuture = service.refresh();
      service.dispose();

      completer.complete({'revision': 8, 'items': <Map<String, dynamic>>[]});
      await refreshFuture;

      expect(notifyCount, 1);
    });
  });

  group('ChatOutboxPendingBar actions and read-only dispatching', () {
    testWidgets('dispatching item is read-only and only offers details in menu', (tester) async {
      final service = ChatConversationOutboxService(initialConversationId: 400);

      final rawSnapshot = {
        'revision': 1,
        'items': [
          {
            'id': 'item-dispatching',
            'conversationId': 400,
            'agentId': 'xiaowan-acp',
            'state': 'dispatching',
            'revision': 1,
            'sequence': 1,
            'requestId': 'r-disp',
            'userMessageId': 'u-disp',
            'createdAt': 1000,
            'updatedAt': 1000,
            'text': 'Executing task currently in flight',
            'attachments': [],
          },
        ],
      };

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') return rawSnapshot;
            return null;
          });

      await service.refresh();

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatOutboxPendingBar(
              outboxService: service,
              observedSessionId: 'sess-1',
              observedTurnId: 'turn-1',
              isProcessing: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-toggle-expand')));
      await tester.pumpAndSettle();

      expect(find.text('发送中...'), findsOneWidget);

      await tester.tap(find.byKey(const ValueKey('chat-outbox-menu-item-dispatching')));
      await tester.pumpAndSettle();

      expect(find.text('查看详情'), findsOneWidget);
      expect(find.text('编辑'), findsNothing);
      expect(find.text('移除'), findsNothing);
      expect(find.text('停止当前任务并发送'), findsNothing);
      expect(find.text('继续发送 (FIFO)'), findsNothing);

      service.dispose();
    });

    testWidgets('missing observed turn ID disables stop_and_send', (tester) async {
      final service = ChatConversationOutboxService(initialConversationId: 401);

      final rawSnapshot = {
        'revision': 1,
        'items': [
          {
            'id': 'item-pending',
            'conversationId': 401,
            'agentId': 'xiaowan-acp',
            'state': 'pending',
            'revision': 1,
            'sequence': 1,
            'requestId': 'r-p',
            'userMessageId': 'u-p',
            'createdAt': 1000,
            'updatedAt': 1000,
            'text': 'Pending prompt',
            'attachments': [],
          },
        ],
      };

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') return rawSnapshot;
            return null;
          });

      await service.refresh();

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatOutboxPendingBar(
              outboxService: service,
              observedSessionId: 'sess-1',
              observedTurnId: null,
              isProcessing: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-toggle-expand')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-menu-item-pending')));
      await tester.pumpAndSettle();

      final stopMenuItem = tester.widget<PopupMenuItem<String>>(
        find.ancestor(
          of: find.text('停止当前任务并发送'),
          matching: find.byType(PopupMenuItem<String>),
        ),
      );
      expect(stopMenuItem.enabled, isFalse);

      service.dispose();
    });

    testWidgets('remove item prompts for confirmation dialog before removal', (tester) async {
      final service = ChatConversationOutboxService(initialConversationId: 402);

      final rawSnapshot = {
        'revision': 1,
        'items': [
          {
            'id': 'item-removable',
            'conversationId': 402,
            'agentId': 'xiaowan-acp',
            'state': 'pending',
            'revision': 1,
            'sequence': 1,
            'requestId': 'r-rem',
            'userMessageId': 'u-rem',
            'createdAt': 1000,
            'updatedAt': 1000,
            'text': 'To be removed',
            'attachments': [],
          },
        ],
      };

      var removeCalled = false;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') return rawSnapshot;
            if (call.method == 'conversationOutboxMutate') {
              final op = (call.arguments as Map)['operation'];
              if (op == 'remove') {
                removeCalled = true;
                return {'revision': 2, 'items': <Map<String, dynamic>>[]};
              }
            }
            return null;
          });

      await service.refresh();

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatOutboxPendingBar(
              outboxService: service,
              observedSessionId: 'sess-1',
              observedTurnId: 'turn-1',
              isProcessing: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-toggle-expand')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('chat-outbox-menu-item-removable')));
      await tester.pumpAndSettle();

      await tester.tap(find.text('移除'));
      await tester.pumpAndSettle();

      expect(find.text('移除待发消息'), findsOneWidget);
      expect(find.text('确定从队列中移除此条待发消息吗？'), findsOneWidget);

      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(removeCalled, isFalse);

      await tester.tap(find.byKey(const ValueKey('chat-outbox-menu-item-removable')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('移除'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('确定移除'));
      await tester.pumpAndSettle();
      expect(removeCalled, isTrue);

      service.dispose();
    });

    testWidgets('tapping item opens details sheet displaying full unclipped text without emoji', (tester) async {
      final service = ChatConversationOutboxService(initialConversationId: 403);

      const fullLongText = 'This is a very detailed prompt that spans multiple sentences and paragraphs to explain the exact requirements of the task without being truncated by ellipsis.';

      final rawSnapshot = {
        'revision': 1,
        'items': [
          {
            'id': 'item-detail',
            'conversationId': 403,
            'agentId': 'xiaowan-acp',
            'state': 'paused',
            'revision': 1,
            'sequence': 1,
            'requestId': 'r-det',
            'userMessageId': 'u-det',
            'createdAt': 1000,
            'updatedAt': 1000,
            'pauseReason': '等待前序任务结束',
            'text': fullLongText,
            'attachments': [
              {'name': 'spec.pdf', 'path': '/tmp/spec.pdf'},
            ],
          },
        ],
      };

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'conversationOutboxList') return rawSnapshot;
            return null;
          });

      await service.refresh();

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatOutboxPendingBar(
              outboxService: service,
              observedSessionId: 'sess-1',
              observedTurnId: 'turn-1',
              isProcessing: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-toggle-expand')));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-item-item-detail')));
      await tester.pumpAndSettle();

      expect(find.text('待发消息详情 #1'), findsOneWidget);
      final selectable = tester.widget<SelectableText>(
        find.byType(SelectableText),
      );
      expect(selectable.data, fullLongText);
      expect(selectable.maxLines, isNull);
      expect(find.text('spec.pdf'), findsOneWidget);

      expect(find.textContaining('📎'), findsNothing);

      final closeBtn = find.ancestor(
        of: find.byIcon(LucideIcons.x),
        matching: find.byType(SizedBox),
      );
      expect(tester.getSize(closeBtn.first).width, greaterThanOrEqualTo(44.0));
      expect(tester.getSize(closeBtn.first).height, greaterThanOrEqualTo(44.0));

      service.dispose();
    });

    testWidgets('edit sheet uses snapshot revision and allows loading latest or saving over conflict', (tester) async {
      final host = _FakeStatefulOutboxHost(revision: 5);
      host.items.add({
        'id': 'item-editable',
        'conversationId': 404,
        'agentId': 'xiaowan-acp',
        'state': 'pending',
        'revision': 5,
        'sequence': 1,
        'requestId': 'r-edit',
        'userMessageId': 'u-edit',
        'createdAt': 1000,
        'updatedAt': 1000,
        'text': 'Initial draft in outbox',
        'attachments': [],
      });

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async => host.handleMethodCall(call));

      final service = ChatConversationOutboxService(initialConversationId: 404);
      await service.refresh();

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatOutboxPendingBar(
              outboxService: service,
              observedSessionId: 'sess-1',
              observedTurnId: 'turn-1',
              isProcessing: true,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const ValueKey('chat-outbox-toggle-expand')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const ValueKey('chat-outbox-menu-item-editable')));
      await tester.pumpAndSettle();

      await tester.tap(find.text('编辑'));
      await tester.pumpAndSettle();

      expect(find.text('编辑待发消息'), findsOneWidget);
      expect(find.byKey(const ValueKey('chat-outbox-edit-field')), findsOneWidget);

      await tester.enterText(
        find.byKey(const ValueKey('chat-outbox-edit-field')),
        'My edited draft to keep',
      );
      await tester.pumpAndSettle();

      // 保存前发生外部并发修改：整箱版本递增为 6，条目内容被修改
      host.revision = 6;
      host.items[0] = {
        ...host.items[0],
        'revision': 6,
        'updatedAt': 1200,
        'text': 'Server modified text',
      };

      // 首次保存携带打开时记录的 initialRevision 5，触发 CAS 冲突
      await tester.tap(find.byKey(const ValueKey('chat-outbox-edit-save-button')));
      await tester.pumpAndSettle();

      // Sheet 保持打开，展示带有最新版本 6 的冲突提示与按钮
      expect(find.textContaining('修改冲突：待发项已在后台更新（版本 6）'), findsOneWidget);
      expect(find.byKey(const ValueKey('chat-outbox-edit-load-latest-button')), findsOneWidget);
      expect(find.text('确认覆盖保存'), findsOneWidget);

      // 用户在输入框中编辑的草稿被完整保留，不被冲突或服务器文本冲掉
      final textField = tester.widget<TextField>(
        find.byKey(const ValueKey('chat-outbox-edit-field')),
      );
      expect(textField.controller?.text, 'My edited draft to keep');

      // 点击“确认覆盖保存”：使用更新后的 revision 6 再次保存
      await tester.tap(find.byKey(const ValueKey('chat-outbox-edit-save-button')));
      await tester.pumpAndSettle();

      // 编辑弹层关闭，宿主队列状态与本地快照真实变更
      expect(host.revision, 7);
      expect(host.items.first['text'], 'My edited draft to keep');
      expect(service.snapshot?.revision, 7);
      expect(service.activeItems.first.text, 'My edited draft to keep');

      service.dispose();
    });
  });

  group('Stateful Outbox revision contract, multi-item mutations and isolation', () {
    test('stateful queue: enqueue B and C, edit B without modifying C item.revision, then remove C succeeds using whole box revision', () async {
      final host = _FakeStatefulOutboxHost();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async => host.handleMethodCall(call));

      final service = ChatConversationOutboxService(initialConversationId: 100);
      await service.refresh();
      expect(service.snapshot?.revision, 0);
      expect(service.activeItems, isEmpty);

      // 1. 入队 B: 箱 rev=1, B item.revision=1, sequence=1
      final snapB = await service.enqueue(text: 'Message B');
      expect(snapB.revision, 1);
      expect(snapB.activeItems.length, 1);
      final itemB = snapB.activeItems.first;
      expect(itemB.text, 'Message B');
      expect(itemB.revision, 1);
      expect(itemB.sequence, 1);

      // 2. 入队 C: 箱 rev=2, C item.revision=2, sequence=2
      final snapC = await service.enqueue(text: 'Message C');
      expect(snapC.revision, 2);
      expect(snapC.activeItems.length, 2);
      final itemC = snapC.activeItems.firstWhere((it) => it.text == 'Message C');
      expect(itemC.revision, 2);
      expect(itemC.sequence, 2);

      // 3. 编辑 B: 整箱 revision 变为 3
      final snapEditB = await service.updateItem(
        item: itemB,
        newText: 'Message B updated',
      );
      expect(snapEditB.revision, 3);
      final itemBUpdated = snapEditB.activeItems.firstWhere((it) => it.id == itemB.id);
      expect(itemBUpdated.text, 'Message B updated');
      expect(itemBUpdated.revision, 3);

      // 真实数据库状态核验：编辑 B 并不改变未修改项 C 的 item.revision (C 仍为 2)
      final itemCUnchanged = snapEditB.activeItems.firstWhere((it) => it.id == itemC.id);
      expect(itemCUnchanged.revision, 2);
      final hostItemC = host.items.firstWhere((it) => it['id'] == itemC.id);
      expect(hostItemC['revision'], 2);

      // 4. 移除 C: expectedRevision 解析为整箱 revision 3（而不是 C 的 item.revision 2）
      // 宿主 CAS 比对 expectedRevision(3) == host.revision(3) 成功，移除 C 并令箱 rev 递增至 4
      final snapRemoveC = await service.removeItem(item: itemCUnchanged);
      expect(snapRemoveC.revision, 4);
      expect(snapRemoveC.activeItems.length, 1);
      expect(snapRemoveC.activeItems.first.id, itemB.id);
      expect(snapRemoveC.activeItems.any((it) => it.id == itemC.id), isFalse);

      // 宿主数据库真实状态断言：C 确实被物理移除，仅留 B
      expect(host.items.length, 1);
      expect(host.items.first['id'], itemB.id);

      service.dispose();
    });

    testWidgets('stateful queue: confirm dialog captures box revision before confirmation, rejects removal if box changed during dialog without retry/overwrite', (tester) async {
      final host = _FakeStatefulOutboxHost();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async => host.handleMethodCall(call));

      final service = ChatConversationOutboxService(initialConversationId: 100);
      await service.enqueue(text: 'Item to delete');
      expect(host.revision, 1);
      expect(host.items.length, 1);
      final targetItem = service.activeItems.first;

      String? toastMessage;
      bool? toastIsError;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: ChatOutboxPendingBar(
              outboxService: service,
              observedSessionId: 'sess-1',
              observedTurnId: 'turn-1',
              isProcessing: true,
              onShowToast: (msg, {isError = false}) {
                toastMessage = msg;
                toastIsError = isError;
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 展开待发栏并打开操作菜单
      await tester.tap(find.byKey(const ValueKey('chat-outbox-toggle-expand')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(ValueKey('chat-outbox-menu-${targetItem.id}')));
      await tester.pumpAndSettle();

      // 点击移除：弹出确认弹窗，此时 UI 捕获用户看到的整箱 revision (1)
      await tester.tap(find.text('移除'));
      await tester.pumpAndSettle();

      expect(find.text('移除待发消息'), findsOneWidget);
      expect(find.text('确定从队列中移除此条待发消息吗？'), findsOneWidget);

      // 用户在弹窗确认期间，宿主端发生外部并发修改：整箱版本递增为 2
      host.revision = 2;
      host.items.add({
        'id': 'concurrent-item',
        'conversationId': 100,
        'agentId': 'xiaowan-acp',
        'state': 'pending',
        'revision': 2,
        'sequence': 99,
        'requestId': 'req-concurrent',
        'userMessageId': 'u-concurrent',
        'createdAt': 9999,
        'updatedAt': 9999,
        'text': 'Concurrent item',
        'attachments': [],
      });

      // 用户在弹窗点击确定移除
      await tester.tap(find.text('确定移除'));
      await tester.pumpAndSettle();

      // 携带打开前捕获的 revision 1 提交，宿主 CAS 判定冲突 (1 != 2)
      expect(toastMessage, '待发箱已更新，请核对后再移除');
      expect(toastIsError, isTrue);

      // 目标条目依然安全保留在宿主数据库中，不被误删，不发生静默重试或借新版本覆盖
      expect(host.items.any((it) => it['id'] == targetItem.id), isTrue);
      expect(host.items.length, 2);

      service.dispose();
    });

    test('stateful queue: resume uses whole box revision, verifies FIFO state transition, and does not leak across conversations', () async {
      final host1 = _FakeStatefulOutboxHost();
      final host2 = _FakeStatefulOutboxHost();

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            final convId = (call.arguments is Map) ? (call.arguments as Map)['conversationId'] : null;
            if (convId == 200) {
              return host2.handleMethodCall(call);
            }
            return host1.handleMethodCall(call);
          });

      final service1 = ChatConversationOutboxService(initialConversationId: 100);
      await service1.refresh();

      // Host1 中存在一条处于 paused 状态的条目，其 item.revision 仍为 1，但整箱 revision 已推进到 5
      host1.revision = 5;
      host1.items.add({
        'id': 'paused-item-1',
        'conversationId': 100,
        'agentId': 'xiaowan-acp',
        'state': 'paused',
        'pauseReason': 'waiting_turn_end',
        'revision': 1,
        'sequence': 1,
        'requestId': 'req-p1',
        'userMessageId': 'u-p1',
        'createdAt': 1000,
        'updatedAt': 1000,
        'text': 'Paused task',
        'attachments': [],
      });
      await service1.refresh();
      expect(service1.snapshot?.revision, 5);
      final pausedItem = service1.activeItems.first;
      expect(pausedItem.isPaused, isTrue);
      expect(pausedItem.revision, 1);

      // 恢复发送：使用整箱 revision 5（而非条目 revision 1）发起 CAS 请求，成功转换状态为 pending
      final resumedSnap = await service1.resumeItem(item: pausedItem);
      expect(resumedSnap.revision, 6);
      expect(resumedSnap.activeItems.first.isPending, isTrue);
      expect(host1.items.first['state'], 'pending');

      // 跨会话隔离检查：service2 绑定到会话 200，其快照 revision 为 99
      host2.revision = 99;
      final service2 = ChatConversationOutboxService(initialConversationId: 200);
      await service2.refresh();
      expect(service2.snapshot?.revision, 99);

      // 尝试通过 service2（绑定 200）操作 pausedItem（会话 100）
      // 绝不能将 service2 的快照版本 99 借给会话 100 的条目，也不能以 null 绕过 CAS
      // 安全回退至 item.revision (1)，在 host1（当前版本 6）触发版本冲突并被安全拒绝
      expect(
        () => service2.resumeItem(item: pausedItem),
        throwsA(isA<OutboxMutationException>().having(
          (e) => e.isRevisionConflict,
          'isRevisionConflict',
          isTrue,
        )),
      );

      // service2 所属会话 200 的快照与绑定未被污染或覆盖
      expect(service2.snapshot?.revision, 99);
      expect(service2.conversationId, 200);

      service1.dispose();
      service2.dispose();
    });

    test('stateful queue: stop_and_send uses whole box revision, verifies observed turn, and rejects when target changes', () async {
      final host = _FakeStatefulOutboxHost(
        activeSessionId: 'sess-active',
        activeTurnId: 'turn-active',
      );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async => host.handleMethodCall(call));

      final service = ChatConversationOutboxService(initialConversationId: 100);
      await service.enqueue(text: 'Task 1');
      await service.enqueue(text: 'Task 2');
      expect(host.revision, 2);

      final task2 = service.activeItems.firstWhere((it) => it.text == 'Task 2');
      expect(task2.revision, 2);

      // 外部变更推进整箱版本到 3
      host.revision = 3;
      await service.refresh();
      expect(service.snapshot?.revision, 3);

      // 1. 携带陈旧的 observedTurnId 调用 stopCurrentAndSend 被拒绝（outbox_target_changed），避免取消新任务
      expect(
        () => service.stopCurrentAndSend(
          item: task2,
          observedSessionId: 'sess-active',
          observedTurnId: 'turn-stale-old',
        ),
        throwsA(isA<OutboxMutationException>().having(
          (e) => e.isTargetChanged,
          'isTargetChanged',
          isTrue,
        )),
      );
      expect(host.items.last['id'], task2.id);

      // 2. 携带匹配的 observedSession/TurnId 并使用整箱 revision 3 调用，成功将 task2 提至队首
      final snapAfterStop = await service.stopCurrentAndSend(
        item: task2,
        observedSessionId: 'sess-active',
        observedTurnId: 'turn-active',
      );
      expect(snapAfterStop.revision, 4);
      expect(snapAfterStop.activeItems.first.id, task2.id);
      expect(host.items.first['id'], task2.id);

      service.dispose();
    });
  });
}

/// 模拟真实 Room 数据库待发箱行为的有状态宿主，用于对待发队列的多条目、版本 CAS 与跨会话隔离做状态化验证
class _FakeStatefulOutboxHost {
  int revision;
  int nextSequence = 1;
  final List<Map<String, dynamic>> items;
  String? activeSessionId;
  String? activeTurnId;

  _FakeStatefulOutboxHost({
    this.revision = 0,
    List<Map<String, dynamic>>? initialItems,
    this.activeSessionId,
    this.activeTurnId,
  }) : items = initialItems != null
            ? initialItems.map((it) => Map<String, dynamic>.from(it)).toList()
            : [];

  Map<String, dynamic> snapshot() {
    return {
      'revision': revision,
      'items': items.map((it) => Map<String, dynamic>.from(it)).toList(),
    };
  }

  Map<String, dynamic>? handleMethodCall(MethodCall call) {
    switch (call.method) {
      case 'conversationOutboxList':
        return snapshot();

      case 'conversationOutboxImportAttachments':
        final raw = call.arguments['attachments'] as List? ?? [];
        return {
          'attachments': raw.map((item) {
            final m = Map<String, dynamic>.from(item as Map);
            return {
              'id': m['id'] ?? 'safe-id',
              'name': m['name'] ?? 'file.bin',
              'path': '/app/workspace/attachments/${m['name'] ?? 'file.bin'}',
              'size': m['size'] ?? 1024,
            };
          }).toList(),
        };

      case 'conversationOutboxMutate':
        final args = call.arguments as Map;
        final operation = args['operation'] as String;
        final req = Map<String, dynamic>.from(args['request'] as Map);
        final expectedRevision = req['expectedRevision'] as int?;

        // CAS 验证：API expectedRevision 是整箱元数据 revision，如果不匹配则严格抛出冲突
        if (expectedRevision != null && expectedRevision != revision) {
          throw PlatformException(
            code: 'outbox_revision_conflict',
            message: 'Outbox revision conflict: expected $expectedRevision but got $revision',
            details: snapshot(),
          );
        }

        switch (operation) {
          case 'enqueue':
            revision++;
            final seq = nextSequence++;
            final payload = req['payload'] as Map? ?? {};
            final newItem = <String, dynamic>{
              'id': req['id'],
              'conversationId': args['conversationId'],
              'agentId': 'xiaowan-acp',
              'state': 'pending',
              'revision': revision,
              'sequence': seq,
              'requestId': 'req-$seq',
              'userMessageId': 'u-$seq',
              'createdAt': 1000 + seq * 10,
              'updatedAt': 1000 + seq * 10,
              'text': payload['text'] ?? '',
              'attachments': payload['attachments'] ?? [],
            };
            items.add(newItem);
            return snapshot();

          case 'update':
            final id = req['id'] as String;
            final itemIndex = items.indexWhere((it) => it['id'] == id);
            if (itemIndex == -1) {
              throw PlatformException(
                code: 'outbox_item_not_found',
                message: 'Item not found: $id',
              );
            }
            revision++;
            final payload = req['payload'] as Map? ?? {};
            final existing = items[itemIndex];
            // 更新 B 时，只改变 B 的内容，其他条目（如 C）的 item.revision 保持不变
            items[itemIndex] = <String, dynamic>{
              ...existing,
              'revision': revision,
              'updatedAt': 2000,
              if (payload.containsKey('text')) 'text': payload['text'],
              if (payload.containsKey('attachments')) 'attachments': payload['attachments'],
            };
            return snapshot();

          case 'remove':
            final id = req['id'] as String;
            final itemIndex = items.indexWhere((it) => it['id'] == id);
            if (itemIndex == -1) {
              throw PlatformException(
                code: 'outbox_item_not_found',
                message: 'Item not found: $id',
              );
            }
            revision++;
            items.removeAt(itemIndex);
            return snapshot();

          case 'resume':
            final id = req['id'] as String;
            final itemIndex = items.indexWhere((it) => it['id'] == id);
            if (itemIndex == -1) {
              throw PlatformException(
                code: 'outbox_item_not_found',
                message: 'Item not found: $id',
              );
            }
            revision++;
            items[itemIndex] = <String, dynamic>{
              ...items[itemIndex],
              'state': 'pending',
              'pauseReason': null,
              'updatedAt': 2000,
            };
            return snapshot();

          case 'stop_and_send':
            final id = req['id'] as String;
            final itemIndex = items.indexWhere((it) => it['id'] == id);
            if (itemIndex == -1) {
              throw PlatformException(
                code: 'outbox_item_not_found',
                message: 'Item not found: $id',
              );
            }
            final expSession = req['expectedSessionId'] as String?;
            final expTurn = req['expectedTurnId'] as String?;
            if (expSession != activeSessionId || expTurn != activeTurnId) {
              throw PlatformException(
                code: 'outbox_target_changed',
                message: 'Active target changed',
                details: snapshot(),
              );
            }
            revision++;
            final targetItem = items.removeAt(itemIndex);
            items.insert(0, {
              ...targetItem,
              'state': 'pending',
              'sequence': 0,
              'updatedAt': 2000,
            });
            return snapshot();

          default:
            throw UnsupportedError('Unknown operation: $operation');
        }

      default:
        return null;
    }
  }
}
