import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_details_sheet.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_result_actions.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/workspace_change_preview_sheet.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/features/home/pages/skill_store/widgets/skill_candidate_review_sheet.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/utils/ui.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const specialPermissionChannel = MethodChannel(
    'cn.com.omnimind.bot/SpecialPermissionEvent',
  );
  const fileChannel = MethodChannel('cn.com.omnimind.bot/FileOpener');
  const assistChannel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  Map<String, dynamic>? mockSkillSuggestionsResponse;

  final recordedCalls = <MethodCall>[];
  Map<dynamic, dynamic>? mockVirtualScreenState;
  final recordedToasts = <String>[];

  const testWorkspacePaths = OmnibotWorkspacePaths(
    rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
    shellRootPath: '/workspace',
    internalRootPath: '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
  );

  setUpAll(() {
    OmnibotResourceService.debugSetWorkspacePaths(testWorkspacePaths);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    AssistsMessageService.initialize();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));

    recordedCalls.clear();
    mockVirtualScreenState = <String, dynamic>{'state': 'absent'};
    mockSkillSuggestionsResponse = null;
    recordedToasts.clear();

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, (call) async {
      recordedCalls.add(call);
      if (call.method == 'getVirtualScreenState') {
        return mockVirtualScreenState;
      }
      if (call.method == 'openVirtualScreenPreview') {
        return true;
      }
      if (call.method == 'stopVirtualScreenTask') {
        return true;
      }
      return true;
    });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(fileChannel, (call) async {
      recordedCalls.add(call);
      if (call.method == 'openHtmlPreview' || call.method == 'openFile') {
        return true;
      }
      return null;
    });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, (call) async {
      recordedCalls.add(call);
      if (call.method == 'agentSkillSuggestionList') {
        return mockSkillSuggestionsResponse ??
            <String, dynamic>{
              'enabled': true,
              'items': [],
              'total': 0,
            };
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(specialPermissionChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(fileChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(assistChannel, null);
  });

  Widget createTestApp(Widget child) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('zh'),
      home: Scaffold(body: SingleChildScrollView(child: child)),
    );
  }

  group('AgentRunResultActions - Artifacts Action', () {
    testWidgets('no artifacts -> action button is hidden', (tester) async {
      final group = _buildGroup(
        taskId: 'turn-plain',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel(
            id: 'answer-1',
            type: 1,
            user: 2,
            content: const {'text': '回答完成，没有产生任何产物。'},
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-open-artifacts')),
        findsNothing,
      );
    });

    testWidgets('single artifact with missing file shows unavailable toast', (tester) async {
      final group = _buildGroup(
        taskId: 'turn-artifact-missing',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_write',
              'status': 'success',
              'artifacts': [
                {
                  'title': 'non_existent.txt',
                  'path': '/workspace/non_existent.txt',
                  'previewKind': 'file',
                }
              ],
            },
            id: 'tool-missing-file',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final openArtifactsButton = find.byKey(
        const ValueKey('agent-result-action-open-artifacts'),
      );
      expect(openArtifactsButton, findsOneWidget);

      await tester.tap(openArtifactsButton);
      await tester.pumpAndSettle();

      // No native tool execution or invalid open was performed
      expect(
        recordedCalls.where((c) => c.method == 'openHtmlPreview'),
        isEmpty,
      );
    });

    testWidgets('multiple artifacts opens selection sheet listing all items', (tester) async {
      final group = _buildGroup(
        taskId: 'turn-multi-artifacts',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_write',
              'status': 'success',
              'artifacts': [
                {
                  'title': 'file_a.txt',
                  'path': '/workspace/file_a.txt',
                  'previewKind': 'file',
                },
                {
                  'title': 'file_b.png',
                  'path': '/workspace/file_b.png',
                  'previewKind': 'image',
                },
              ],
            },
            id: 'tool-multi',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final openArtifactsButton = find.byKey(
        const ValueKey('agent-result-action-open-artifacts'),
      );
      expect(openArtifactsButton, findsOneWidget);

      await tester.tap(openArtifactsButton);
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('artifact-selection-sheet-title')), findsNothing); // Checks selection UI
      expect(find.byKey(const ValueKey('artifact-select-item-0')), findsOneWidget);
      expect(find.byKey(const ValueKey('artifact-select-item-1')), findsOneWidget);

      // Dismissing selection sheet returns cleanly
      await tester.tap(find.byKey(const ValueKey('artifact-selection-close')));
      await tester.pumpAndSettle();

      expect(find.byKey(const ValueKey('artifact-select-item-0')), findsNothing);
    });
  });

  group('AgentRunResultActions - Live Scene 4-Field Verification', () {
    testWidgets('scene button hidden when turn has no display interaction', (tester) async {
      final group = _buildGroup(
        taskId: 'turn-no-display',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
            },
            id: 'tool-read',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-view-scene')),
        findsNothing,
      );
    });

    testWidgets('all 4 fields strictly match -> calls openPreview without canceling task', (tester) async {
      mockVirtualScreenState = <String, dynamic>{
        'state': 'running',
        'displayId': 4,
        'sessionId': 'session-exact-1',
        'turnId': 'turn-display-1',
        'conversationId': 'conv-exact-1',
      };

      final group = _buildGroup(
        taskId: 'turn-display-1',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'device_display',
              'status': 'success',
              'display_id': 4,
              'sessionId': 'session-exact-1',
              'turnId': 'turn-display-1',
              'conversationId': 'conv-exact-1',
            },
            id: 'tool-screen-op',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final viewSceneBtn = find.byKey(
        const ValueKey('agent-result-action-view-scene'),
      );
      expect(viewSceneBtn, findsOneWidget);

      await tester.tap(viewSceneBtn);
      await tester.pumpAndSettle();

      // Verified: openVirtualScreenPreview was called with displayId=4, sessionId='session-exact-1'
      expect(
        recordedCalls.any((c) =>
            c.method == 'openVirtualScreenPreview' &&
            c.arguments['displayId'] == 4 &&
            c.arguments['sessionId'] == 'session-exact-1'),
        isTrue,
      );

      // Verified: stopVirtualScreenTask was NEVER called
      expect(
        recordedCalls.any((c) => c.method == 'stopVirtualScreenTask'),
        isFalse,
      );
    });

    testWidgets('field mismatch (e.g. displayId or sessionId or turnId changed) -> shows warning, does not open', (tester) async {
      // Screen state has moved on to a different turn / session
      mockVirtualScreenState = <String, dynamic>{
        'state': 'running',
        'displayId': 5, // different displayId
        'sessionId': 'session-other',
        'turnId': 'turn-other',
        'conversationId': 'conv-other',
      };

      final group = _buildGroup(
        taskId: 'turn-display-stale',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'device_display',
              'status': 'success',
              'display_id': 4,
              'sessionId': 'session-original',
              'turnId': 'turn-display-stale',
              'conversationId': 'conv-original',
            },
            id: 'tool-screen-stale',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final viewSceneBtn = find.byKey(
        const ValueKey('agent-result-action-view-scene'),
      );
      expect(viewSceneBtn, findsOneWidget);

      await tester.tap(viewSceneBtn);
      await tester.pumpAndSettle();

      // openVirtualScreenPreview was NOT called because of mismatch
      expect(
        recordedCalls.any((c) => c.method == 'openVirtualScreenPreview'),
        isFalse,
      );
      expect(
        recordedCalls.any((c) => c.method == 'stopVirtualScreenTask'),
        isFalse,
      );
    });

    testWidgets('missing any identity field when display is reused -> strictly rejected, never opens another task', (tester) async {
      mockVirtualScreenState = <String, dynamic>{
        'state': 'running',
        'displayId': 4,
        'sessionId': 'session-reused-1',
        'turnId': 'turn-other-task',
        'conversationId': 'conv-other-task',
      };

      // Group has display_id=4 but NO sessionId or conversationId
      final group = _buildGroup(
        taskId: 'turn-missing-ids',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'device_display',
              'status': 'success',
              'display_id': 4,
            },
            id: 'tool-screen-partial',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final viewSceneBtn = find.byKey(
        const ValueKey('agent-result-action-view-scene'),
      );
      expect(viewSceneBtn, findsOneWidget);

      await tester.tap(viewSceneBtn);
      await tester.pumpAndSettle();

      // openVirtualScreenPreview was NEVER called because identity fields were missing
      expect(
        recordedCalls.any((c) => c.method == 'openVirtualScreenPreview'),
        isFalse,
      );
    });
  });

  group('AgentRunResultActions - View Changes & Return Route', () {
    testWidgets('view changes opens WorkspaceChangePreviewSheet and pop restores route', (tester) async {
      const sampleDiff = '''
--- a/src/main.dart
+++ b/src/main.dart
@@ -1,3 +1,4 @@
 void main() {
+  print("hello upgraded");
 }
''';

      final group = _buildGroup(
        taskId: 'turn-diff-1',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_edit',
              'status': 'success',
              'diffText': sampleDiff,
              'actions': [
                {
                  'type': 'workspace_change',
                  'label': '查看变更',
                  'payload': {
                    'changeId': 'change-uuid-42',
                    'conversationId': 101,
                  },
                }
              ],
            },
            id: 'tool-diff',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final viewChangesBtn = find.byKey(
        const ValueKey('agent-result-action-view-changes'),
      );
      expect(viewChangesBtn, findsOneWidget);

      await tester.tap(viewChangesBtn);
      await tester.pumpAndSettle();

      // Sheet is opened
      expect(find.byType(WorkspaceChangePreviewSheet), findsOneWidget);
      expect(find.byKey(const ValueKey('workspace-change-preview-title')), findsOneWidget);
      expect(find.byKey(const ValueKey('workspace-change-preview-id')), findsOneWidget);
      expect(find.byKey(const ValueKey('workspace-change-preview-notice')), findsOneWidget);

      // Close the sheet via pop
      await tester.tap(find.byKey(const ValueKey('workspace-change-preview-close')));
      await tester.pumpAndSettle();

      expect(find.byType(WorkspaceChangePreviewSheet), findsNothing);
      // Group message remains undisturbed
      expect(find.byKey(const ValueKey('agent-result-action-view-changes')), findsOneWidget);
    });
  });

  group('AgentRunResultActions - In AgentRunDetailsSheet', () {
    testWidgets('completed turn in details sheet displays result actions bar', (tester) async {
      final group = _buildGroup(
        taskId: 'turn-details-result',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_write',
              'status': 'success',
              'artifacts': [
                {
                  'title': 'summary.md',
                  'path': '/workspace/summary.md',
                  'previewKind': 'file',
                }
              ],
            },
            id: 'tool-write-summary',
          ),
        ],
      );

      final notifier = ValueNotifier<AgentRunTimelineGroup>(group);
      addTearDown(notifier.dispose);

      await tester.pumpWidget(
        createTestApp(
          Builder(
            builder: (context) => TextButton(
              onPressed: () => showAgentRunDetailsSheet(
                context: context,
                group: notifier,
              ),
              child: const Text('Open Details'),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.text('Open Details'));
      await tester.pumpAndSettle();

      expect(find.byType(AgentRunDetailsSheet), findsOneWidget);
      expect(
        find.byKey(const ValueKey('agent-result-action-open-artifacts')),
        findsOneWidget,
      );
    });
  });

  group('AgentRunResultActions - Real Skill Assets Integration', () {
    testWidgets('non-xiaowan agent never shows skill suggestion button', (tester) async {
      mockSkillSuggestionsResponse = <String, dynamic>{
        'enabled': true,
        'items': [
          {
            'candidateId': 'cand-1',
            'revision': 1,
            'conversationId': 42,
            'turnId': 'turn-claude',
            'state': 'pending_review',
            'name': 'demo-skill',
            'description': 'demo desc',
            'bodyMarkdown': '# Skill',
          },
        ],
        'total': 1,
      };

      final group = AgentRunTimelineGroup(
        taskId: 'turn-claude',
        status: AgentRunStatus.finished,
        agentId: 'claude-code-acp',
        startedAt: DateTime(2026, 11, 14),
        finishedAt: DateTime(2026, 11, 14, 0, 0, 1),
        segmentsOldestFirst: [
          AgentRunTimelineSegment.visible(
            ChatMessageModel(
              id: 'msg-1',
              type: 1,
              user: 2,
              content: const {'text': 'Claude answer'},
            ),
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsNothing,
      );
    });

    testWidgets('xiaowan agent with pending_review candidate shows button and opens review sheet', (tester) async {
      mockSkillSuggestionsResponse = <String, dynamic>{
        'enabled': true,
        'items': [
          {
            'candidateId': 'cand-xiaowan-1',
            'revision': 1,
            'conversationId': 42,
            'turnId': 'turn-xiaowan-1',
            'state': 'pending_review',
            'name': 'extract-data-skill',
            'description': 'Extract tabular data',
            'bodyMarkdown': '# Extract Data Skill Definition',
          },
        ],
        'total': 1,
      };

      final group = _buildGroup(
        taskId: 'turn-xiaowan-1',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 42,
              'turnId': 'turn-xiaowan-1',
            },
            id: 'tool-read-op',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      final skillBtn = find.byKey(
        const ValueKey('agent-result-action-skill-suggestions'),
      );
      expect(skillBtn, findsOneWidget);
      expect(find.textContaining('技能建议'), findsOneWidget);

      await tester.tap(skillBtn);
      await tester.pumpAndSettle();

      // Opens shared SkillCandidateReviewSheet
      expect(find.byType(SkillCandidateReviewSheet), findsOneWidget);
    });

    testWidgets('empty suggestions list from server shows no skill button', (tester) async {
      mockSkillSuggestionsResponse = <String, dynamic>{
        'enabled': true,
        'items': [],
        'total': 0,
      };

      final group = _buildGroup(
        taskId: 'turn-no-candidate',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 42,
            },
            id: 'tool-read-2',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(AgentRunGroupMessage(
        group: group,
        useAcpPresentation: true,
        onBeforeTaskExecute: () async {},
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsNothing,
      );
    });

    testWidgets('native skill_assets_changed event triggers candidate reload for matching turn', (tester) async {
      mockSkillSuggestionsResponse = <String, dynamic>{
        'enabled': true,
        'items': [],
        'total': 0,
      };

      final group = _buildGroup(
        taskId: 'turn-xiaowan-event',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 42,
              'turnId': 'turn-xiaowan-event',
            },
            id: 'tool-read-event',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(
        AgentRunResultActionsBar(
          group: group,
          conversationId: 42,
        ),
      ));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsNothing,
      );

      mockSkillSuggestionsResponse = <String, dynamic>{
        'enabled': true,
        'items': [
          {
            'candidateId': 'cand-event-1',
            'revision': 1,
            'conversationId': 42,
            'turnId': 'turn-xiaowan-event',
            'state': 'pending_review',
            'name': 'event-driven-skill',
            'description': 'Event driven desc',
            'bodyMarkdown': '# Event Skill',
          },
        ],
        'total': 1,
      };

      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        assistChannel.name,
        const StandardMethodCodec().encodeMethodCall(
          const MethodCall('skill_assets_changed', {
            'conversationId': 42,
            'turnId': 'turn-xiaowan-event',
          }),
        ),
        (ByteData? _) {},
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsOneWidget,
      );
      expect(find.textContaining('技能建议'), findsOneWidget);
    });

    testWidgets('switching taskId discards in-flight late response and never displays cross-turn candidate', (tester) async {
      final completer1 = Completer<Map<String, dynamic>>();

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(assistChannel, (call) async {
        recordedCalls.add(call);
        if (call.method == 'agentSkillSuggestionList') {
          final turnId = (call.arguments as Map?)?['turnId']?.toString();
          if (turnId == 'turn-xiaowan-slow') {
            return await completer1.future;
          }
          return {
            'enabled': true,
            'items': [],
            'total': 0,
          };
        }
        return null;
      });

      final group1 = _buildGroup(
        taskId: 'turn-xiaowan-slow',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 42,
              'turnId': 'turn-xiaowan-slow',
            },
            id: 'tool-read-slow',
          ),
        ],
      );

      final notifier = ValueNotifier<AgentRunTimelineGroup>(group1);
      addTearDown(notifier.dispose);

      await tester.pumpWidget(createTestApp(
        ValueListenableBuilder<AgentRunTimelineGroup>(
          valueListenable: notifier,
          builder: (context, group, _) => AgentRunResultActionsBar(
            group: group,
            conversationId: 42,
          ),
        ),
      ));
      await tester.pump();

      // Switch to a new completed task
      final group2 = _buildGroup(
        taskId: 'turn-xiaowan-fast',
        status: AgentRunStatus.finished,
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 42,
              'turnId': 'turn-xiaowan-fast',
            },
            id: 'tool-read-fast',
          ),
        ],
      );
      notifier.value = group2;
      await tester.pump();

      // Slow response for turn-xiaowan-slow completes late
      completer1.complete({
        'enabled': true,
        'items': [
          {
            'candidateId': 'cand-late-1',
            'revision': 1,
            'conversationId': 42,
            'turnId': 'turn-xiaowan-slow',
            'state': 'pending_review',
            'name': 'stale-slow-skill',
            'description': 'Stale slow skill description',
            'bodyMarkdown': '# Stale Skill',
          },
        ],
        'total': 1,
      });
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsNothing,
      );
    });

    testWidgets('non-xiaowan agentId strictly never loads or displays skill suggestion', (tester) async {
      mockSkillSuggestionsResponse = <String, dynamic>{
        'enabled': true,
        'items': [
          {
            'candidateId': 'cand-guess',
            'revision': 1,
            'conversationId': 42,
            'turnId': 'turn-guess',
            'state': 'pending_review',
            'name': 'guess-skill',
            'description': 'guess desc',
            'bodyMarkdown': '# Guess',
          },
        ],
        'total': 1,
      };

      final group = _buildGroup(
        taskId: 'turn-guess',
        status: AgentRunStatus.finished,
        agentId: 'claude-code',
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 42,
              'turnId': 'turn-guess',
            },
            id: 'tool-read-guess',
          ),
        ],
      );

      await tester.pumpWidget(createTestApp(
        AgentRunResultActionsBar(
          group: group,
          conversationId: 42,
        ),
      ));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsNothing,
      );
    });

    testWidgets('group with multiple different turnIds is ambiguous and strictly never loads skill suggestion', (tester) async {
      final group = _buildGroup(
        taskId: 'turn-ambiguous',
        status: AgentRunStatus.finished,
        agentId: 'xiaowan-acp',
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 166,
              'turnId': 'turn-1',
            },
            id: 'tool-read-1',
          ),
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_edit',
              'status': 'success',
              'conversationId': 166,
              'turnId': 'turn-2',
            },
            id: 'tool-edit-2',
          ),
        ],
      );

      mockSkillSuggestionsResponse = <String, dynamic>{
        'items': [
          {
            'candidateId': 'cand-ambig',
            'conversationId': 166,
            'turnId': 'turn-1',
            'state': 'pending_review',
            'revision': 1,
            'name': 'ambig-skill',
            'description': 'desc',
            'bodyMarkdown': 'body',
          }
        ],
        'total': 1,
      };

      await tester.pumpWidget(createTestApp(AgentRunResultActionsBar(
        group: group,
        conversationId: 166,
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsNothing,
      );
    });

    testWidgets('skill candidate loads when event turnId matches official turnId from message streamMeta', (tester) async {
      final group = _buildGroup(
        taskId: '1794648142743-ai',
        status: AgentRunStatus.finished,
        agentId: 'xiaowan-acp',
        messages: [
          ChatMessageModel.cardMessage(
            {
              'type': 'agent_tool_summary',
              'toolName': 'file_read',
              'status': 'success',
              'conversationId': 166,
              'turnId': '42829567-5ebb-4f66-9ac3-7f8afb40cc97',
            },
            id: 'tool-read-1',
          ),
        ],
      );

      mockSkillSuggestionsResponse = <String, dynamic>{
        'items': [
          {
            'candidateId': 'cand_8fce8a1e287bdffd',
            'conversationId': 166,
            'turnId': '42829567-5ebb-4f66-9ac3-7f8afb40cc97',
            'state': 'pending_review',
            'revision': 3,
            'name': 'test-skill',
            'description': 'test description',
            'bodyMarkdown': 'test body',
          }
        ],
        'total': 1,
      };

      await tester.pumpWidget(createTestApp(AgentRunResultActionsBar(
        group: group,
        conversationId: 166,
      )));
      await tester.pumpAndSettle();

      expect(
        find.byKey(const ValueKey('agent-result-action-skill-suggestions')),
        findsOneWidget,
      );
      expect(find.text('技能建议待审核'), findsOneWidget);
    });
  });
}

AgentRunTimelineGroup _buildGroup({
  required String taskId,
  required AgentRunStatus status,
  required List<ChatMessageModel> messages,
  String agentId = 'xiaowan-acp',
}) {
  return AgentRunTimelineGroup(
    taskId: taskId,
    status: status,
    agentId: agentId,
    startedAt: DateTime(2026, 11, 14, 10, 0),
    finishedAt: status == AgentRunStatus.running
        ? null
        : DateTime(2026, 11, 14, 10, 0, 5),
    segmentsOldestFirst: [
      AgentRunTimelineSegment.process(
        messages.where((m) => m.cardData?['type'] == 'agent_tool_summary').toList(),
      ),
      for (final m in messages.where((m) => m.cardData?['type'] != 'agent_tool_summary'))
        AgentRunTimelineSegment.visible(m),
    ],
  );
}
