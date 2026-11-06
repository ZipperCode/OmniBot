import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_details_sheet.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_header.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  for (final removeSource in [true, false]) {
    testWidgets(
      '${removeSource ? 'removed' : 'replaced'} source cannot leave a sheet before its first frame',
      (tester) async {
        final source = ValueNotifier<AgentRunTimelineGroup?>(_group());
        addTearDown(source.dispose);
        await tester.pumpWidget(
          _app(
            ValueListenableBuilder<AgentRunTimelineGroup?>(
              valueListenable: source,
              builder: (context, group, _) =>
                  group == null ? const Text('其他对话') : _run(group),
            ),
          ),
        );
        await tester.pumpAndSettle();

        // Open the route synchronously, then change the source before the
        // modal builder gets a frame to record its route identity.
        tester
            .widget<AgentRunHeader>(find.byType(AgentRunHeader))
            .onToggleExpanded!();
        source.value = removeSource ? null : _group(taskId: 'turn-2');
        await tester.pumpAndSettle();

        expect(find.byType(AgentRunDetailsSheet), findsNothing);
        expect(find.byType(BottomSheet), findsNothing);
        expect(tester.takeException(), isNull);
        if (removeSource) {
          expect(find.text('其他对话'), findsOneWidget);
        } else {
          // Removing an obsolete sheet must not pop the new conversation.
          final current = tester.widget<AgentRunHeader>(
            find.byType(AgentRunHeader),
          );
          expect(current.taskId, 'turn-2');
          current.onToggleExpanded!();
          await tester.pumpAndSettle();
          expect(
            find.byKey(const ValueKey('agent-run-details-sheet-turn-2')),
            findsOneWidget,
          );
        }
      },
    );
  }

  testWidgets(
    'completed run retains the original historical approval outcome',
    (tester) async {
      final group = _group(requestStatus: 'accepted');
      await tester.pumpWidget(_app(_run(group)));
      await tester.pumpAndSettle();

      expect(find.text('访问报告的权限'), findsOneWidget);
      expect(find.text('已允许'), findsOneWidget);
      expect(find.text('允许'), findsNothing);
      expect(find.text('拒绝'), findsNothing);
      expect(find.textContaining('最终结论', findRichText: true), findsOneWidget);

      tester
          .widget<AgentRunHeader>(find.byType(AgentRunHeader))
          .onToggleExpanded!();
      await tester.pumpAndSettle();
      final sheet = find.byType(AgentRunDetailsSheet);
      expect(sheet, findsOneWidget);
      expect(
        find.descendant(of: sheet, matching: find.text('访问报告的权限')),
        findsNothing,
      );
      expect(
        agentRunDetailMessages(group).map((message) => message.id),
        isNot(contains('request-1')),
      );
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets('active approval remains actionable inline with its identity', (
    tester,
  ) async {
    final group = _group(
      status: AgentRunStatus.running,
      requestStatus: 'pending',
    );
    await tester.pumpWidget(_app(_run(group)));
    await tester.pump();

    expect(find.text('访问报告的权限'), findsOneWidget);
    expect(find.text('允许'), findsOneWidget);
    expect(find.text('拒绝'), findsOneWidget);
    expect(
      tester
          .widget<AgentRunHeader>(find.byType(AgentRunHeader))
          .onToggleExpanded,
      isNull,
    );
    expect(find.byType(AgentRunDetailsSheet), findsNothing);
    expect(
      agentRunDetailMessages(group).map((message) => message.id),
      isNot(contains('request-1')),
    );
    final request = group.visibleMessagesOldestFirst.first;
    expect(request.cardData?['requestId'], 'approval-wire-42');
    expect(request.cardData?['sessionId'], 'session-42');
    expect(tester.takeException(), isNull);
  });
}

Widget _app(Widget child) {
  return MaterialApp(
    theme: AppTheme.lightTheme,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    locale: const Locale('zh'),
    home: Scaffold(body: SingleChildScrollView(child: child)),
  );
}

Widget _run(AgentRunTimelineGroup group) {
  return AgentRunGroupMessage(
    group: group,
    useAcpPresentation: true,
    onBeforeTaskExecute: () async {},
  );
}

AgentRunTimelineGroup _group({
  String taskId = 'turn-1',
  AgentRunStatus status = AgentRunStatus.finished,
  String? requestStatus,
}) {
  return AgentRunTimelineGroup(
    taskId: taskId,
    status: status,
    agentId: 'claude-code-acp',
    startedAt: DateTime(2026, 10, 20),
    finishedAt: status == AgentRunStatus.running
        ? null
        : DateTime(2026, 10, 20, 0, 0, 10),
    segmentsOldestFirst: [
      AgentRunTimelineSegment.process([
        ChatMessageModel.cardMessage({
          'type': 'deep_thinking',
          'thinkingContent': '已有的思考内容',
          'stage': 4,
          'taskID': taskId,
          'cardId': 'thought-1',
        }, id: 'thought-1'),
      ]),
      if (requestStatus != null)
        AgentRunTimelineSegment.visible(
          ChatMessageModel.cardMessage({
            'type': 'agent_request',
            'requestKind': 'approval',
            'requestId': 'approval-wire-42',
            'sessionId': 'session-42',
            'taskId': taskId,
            'status': requestStatus,
            'title': '访问报告的权限',
          }, id: 'request-1'),
        ),
      AgentRunTimelineSegment.visible(
        ChatMessageModel(
          id: '$taskId-answer',
          type: 1,
          user: 2,
          content: const {'text': '最终结论'},
        ),
      ),
    ],
  );
}
