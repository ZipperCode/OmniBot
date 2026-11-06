import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/message_bubble.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/na_attachment_preview.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/agent_runtime_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AgentRuntime');
  final calls = <MethodCall>[];
  setUp(() {
    calls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return {
            'ok': true,
            'path': '/workspace/report.txt',
            'name': 'report.txt',
            'previewKind': 'text',
            'content': 'cloud file content',
            'size': 17,
          };
        });
  });
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );

  for (final readable in [true, false]) {
    testWidgets('cloud image uses scoped native reader, readable=$readable', (
      tester,
    ) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call);
            if (!readable) throw PlatformException(code: 'ACCOUNT_CHANGED');
            return {
              'ok': true,
              'path': '/workspace/photo.png',
              'name': 'photo.png',
              'dataBase64': 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO84AAAAASUVORK5CYII=',
            };
          });
      await tester.pumpWidget(
        MaterialApp(
          home: NaAttachmentPreview(
            attachment: const {
              'sessionId': 'na:alice:conversation',
              'path': '/workspace/photo.png',
              'mimeType': 'image/png',
            },
            child: const Text('photo.png'),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(calls, hasLength(1));
      expect(
        (calls.single.arguments as Map)['sessionId'],
        'na:alice:conversation',
      );
      expect(find.text('photo.png'), findsOneWidget);
      expect(find.byType(Image), readable ? findsOneWidget : findsNothing);
      expect(tester.takeException(), isNull);
    });
  }

  test('mutations route to the selected cloud agent', () async {
    await AgentRuntimeService.moveRemotePath(
      agentId: 'na-cloud',
      path: '/workspace/a',
      destinationPath: '/workspace/b',
    );
    await AgentRuntimeService.deleteRemotePath(
      agentId: 'na-cloud',
      path: '/workspace/b',
    );
    expect(calls.map((call) => call.method), [
      'config/remote/fs/move',
      'config/remote/fs/delete',
    ]);
    expect(
      calls.every((call) => (call.arguments as Map)['agentId'] == 'na-cloud'),
      true,
    );
  });

  for (final deleted in [false, true]) {
    testWidgets(
      'cloud-only history renders an attachment card and scopes preview, deleted=$deleted',
      (tester) async {
        final message = ChatMessageModel(
          id: 'user',
          type: 1,
          user: 1,
          content: {
            'text': 'read',
            'attachments': [
              {
                'id': 'file',
                'name': 'report.txt',
                'path': '/workspace/report.txt',
                'size': 17,
                'source': 'na-cloud',
                'sessionId': 'na:alice:conversation',
                'mimeType': 'text/plain',
                'deleted': deleted,
              },
            ],
          },
        );
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(body: MessageBubble(message: message)),
          ),
        );
        expect(find.textContaining('report.txt'), findsOneWidget);
        await tester.tap(find.textContaining('report.txt'));
        await tester.pumpAndSettle();
        if (deleted) {
          expect(calls, isEmpty);
        } else {
          final call = calls.single;
          expect(call.method, 'config/remote/fs/read');
          expect((call.arguments as Map)['sessionId'], 'na:alice:conversation');
          expect((call.arguments as Map)['agentId'], 'na-cloud');
          expect(find.textContaining('cloud file content'), findsWidgets);
        }
        expect(tester.takeException(), isNull);
      },
    );
  }
}
