import 'package:flutter_test/flutter_test.dart';
import 'package:ui/core/router/conversation_route_target.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';

void main() {
  test(
    'native query retains conversation, Agent, mode and request identity',
    () {
      final target = parseConversationRouteTarget(
        Uri.parse(
          '/home/chat?conversationId=42&mode=agent&agentId=codex&requestKey=import-1',
        ),
        null,
      );

      expect(target!.conversationId, 42);
      expect(target.agentId, 'codex');
      expect(target.mode, ConversationMode.agent);
      expect(target.requestKey, 'import-1');
      expect(target.fromNativeRoute, isTrue);
    },
  );

  test('native new-thread request retains its import key', () {
    final target = parseConversationRouteTarget(
      Uri.parse(
        '/home/chat?conversationId=new&mode=chat_only&requestKey=share-1',
      ),
      null,
    );

    expect(target!.isNewConversation, isTrue);
    expect(target.mode, ConversationMode.chatOnly);
    expect(target.requestKey, 'share-1');
    expect(target.fromNativeRoute, isTrue);
  });

  test('typed Agent-session target is preserved without reconstruction', () {
    const target = ConversationThreadTarget.agentSession(
      sessionId: 'session-1',
      agentId: 'codex',
      agentSessionActive: true,
      requestKey: 'picker-1',
    );

    expect(
      parseConversationRouteTarget(Uri.parse('/home/home'), target),
      same(target),
    );
  });

  test(
    'legacy list target preserves native provenance and request identity',
    () {
      final target = parseConversationRouteTarget(
        Uri.parse('/home/home'),
        const <String>['42', '__from_native__', 'mode=agent', 'native-1'],
      );

      expect(target!.conversationId, 42);
      expect(target.mode, ConversationMode.agent);
      expect(target.requestKey, 'native-1');
      expect(target.fromNativeRoute, isTrue);
    },
  );

  test(
    'ordinary home and route-options extras do not create a chat target',
    () {
      expect(
        parseConversationRouteTarget(Uri.parse('/home/home'), null),
        isNull,
      );
      expect(
        parseConversationRouteTarget(
          Uri.parse('/home/home'),
          const <String, Object>{
            '_routeOptions': <String, Object>{'noAnim': true},
          },
        ),
        isNull,
      );
    },
  );
}
