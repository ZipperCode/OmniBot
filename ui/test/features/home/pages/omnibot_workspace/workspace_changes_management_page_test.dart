import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/workspace_change_preview_sheet.dart';
import 'package:ui/features/home/pages/omnibot_workspace/omnibot_workspace_page.dart';
import 'package:ui/features/home/pages/omnibot_workspace/workspace_changes_management_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final recordedCalls = <MethodCall>[];
  Map<String, dynamic>? mockResponse;

  const testWorkspacePaths = OmnibotWorkspacePaths(
    rootPath: '/data/user/0/workspace',
    shellRootPath: '/workspace',
    internalRootPath: '/data/user/0/workspace/.omnibot',
  );

  setUpAll(() {
    OmnibotResourceService.debugSetWorkspacePaths(testWorkspacePaths);
  });

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    await StorageService.init();
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    recordedCalls.clear();
    mockResponse = null;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      recordedCalls.add(call);
      if (mockResponse != null) {
        return mockResponse;
      }
      if (call.method == 'workspaceChangesListByWorkspace') {
        return <String, dynamic>{
          'workspaceId': 'conversation_default',
          'items': [
            {
              'changeId': 'change-1',
              'conversationId': 42,
              'sessionId': 'session-1',
              'turnId': 'turn-1',
              'toolCallId': 'call-1',
              'workspaceRoot': '/data/user/0/workspace',
              'operation': 'write',
              'targetRelativePath': 'lib/app.dart',
              'snapshotStatus': 'ready',
              'revision': 1,
              'createdAt': 1700000000000,
            },
            {
              'changeId': 'change-2',
              'conversationId': 42,
              'sessionId': 'session-1',
              'turnId': 'turn-1',
              'toolCallId': 'call-2',
              'workspaceRoot': '/data/user/0/workspace',
              'operation': 'create',
              'targetRelativePath': 'secret.env',
              'snapshotStatus': 'unavailable',
              'unavailableReason': 'sensitive_path',
              'revision': 1,
              'createdAt': 1700000001000,
            },
          ],
        };
      }
      if (call.method == 'workspaceChangePreview') {
        return <String, dynamic>{
          'item': {
            'changeId': 'change-1',
            'conversationId': 42,
            'sessionId': 'session-1',
            'turnId': 'turn-1',
            'toolCallId': 'call-1',
            'workspaceRoot': '/data/user/0/workspace',
            'operation': 'write',
            'targetRelativePath': 'lib/app.dart',
            'snapshotStatus': 'ready',
            'revision': 1,
            'createdAt': 1700000000000,
          },
          'relativePath': 'lib/app.dart',
          'diffText': '''
--- a/lib/app.dart
+++ b/lib/app.dart
@@ -1,2 +1,3 @@
 void main() {
+  runApp();
 }
''',
          'isBinary': false,
          'canRestore': true,
          'expectedAfterHashes': {
            'lib/app.dart': '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
          },
        };
      }
      if (call.method == 'workspaceChangeRestore') {
        return <String, dynamic>{
          'status': 'restored',
          'success': true,
          'revision': 2,
          'message': 'Restored successfully',
        };
      }
      if (call.method == 'workspaceChangeDiscard') {
        return <String, dynamic>{
          'changeId': 'change-1',
          'discarded': true,
          'revision': 2,
          'snapshotStatus': 'unavailable',
        };
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Widget buildTestApp(Widget child) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('zh'),
      home: child,
    );
  }

  testWidgets('WorkspaceChangesManagementPage renders list of changes', (tester) async {
    await tester.pumpWidget(
      buildTestApp(
        const WorkspaceChangesManagementPage(
          workspaceId: '/data/user/0/workspace',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('workspace-changes-list')), findsOneWidget);
    expect(find.text('lib/app.dart'), findsOneWidget);
    expect(find.text('secret.env'), findsOneWidget);
    expect(find.text('可恢复'), findsOneWidget);
    expect(find.text('不可恢复'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
  testWidgets('tapping change item opens preview sheet with diff and confirm restore dialog', (tester) async {
    await tester.pumpWidget(
      buildTestApp(
        const WorkspaceChangesManagementPage(
          workspaceId: '/data/user/0/workspace',
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Tap first item
    await tester.tap(find.byKey(const ValueKey('workspace-change-item-change-1')));
    await tester.pumpAndSettle();

    // Preview Sheet opened
    expect(find.byType(WorkspaceChangePreviewSheet), findsOneWidget);
    expect(find.byKey(const ValueKey('workspace-change-preview-diff')), findsOneWidget);
    expect(find.byKey(const ValueKey('workspace-change-restore-button')), findsOneWidget);
    expect(find.byKey(const ValueKey('workspace-change-discard-button')), findsOneWidget);

    // Tap restore button -> shows confirmation dialog
    await tester.tap(find.byKey(const ValueKey('workspace-change-restore-button')));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.text('确认恢复文件？'), findsOneWidget);

    // Confirm dialog (select the clickable FilledButton in AlertDialog to avoid ambiguous match with sheet button)
    final confirmButton = find.descendant(
      of: find.byType(AlertDialog),
      matching: find.widgetWithText(FilledButton, '确认恢复'),
    );
    await tester.tap(confirmButton);
    await tester.pumpAndSettle();

    // Verify channel call
    expect(
      recordedCalls.any((c) =>
          c.method == 'workspaceChangeRestore' &&
          c.arguments['changeId'] == 'change-1' &&
          c.arguments['expectedRevision'] == 1),
      isTrue,
    );

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
  testWidgets('pure diff mode displays diff only without restore or discard buttons', (tester) async {
    await tester.pumpWidget(
      buildTestApp(
        const Scaffold(
          body: WorkspaceChangePreviewSheet(
            changeId: 'diff',
            diffText: '''
--- a/sample.dart
+++ b/sample.dart
@@ -1,1 +1,2 @@
 hello
+world
''',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('workspace-change-preview-diff')), findsOneWidget);
    expect(find.byKey(const ValueKey('workspace-change-restore-button')), findsNothing);
    expect(find.byKey(const ValueKey('workspace-change-discard-button')), findsNothing);
    expect(find.byKey(const ValueKey('workspace-change-close-button')), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
  testWidgets('OmnibotWorkspacePage has change history button in app bar', (tester) async {
    await tester.pumpWidget(
      buildTestApp(
        const OmnibotWorkspacePage(
          workspacePath: '/data/user/0/workspace',
        ),
      ),
    );
    await tester.pumpAndSettle();

    final historyButton = find.byKey(
      const ValueKey('workspace-changes-history-button'),
    );
    expect(historyButton, findsOneWidget);

    // Tapping history button navigates to management page
    await tester.tap(historyButton);
    await tester.pumpAndSettle();

    expect(find.byType(WorkspaceChangesManagementPage), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('OmnibotWorkspacePage disables change history button when workspaceId is unknown', (tester) async {
    await tester.pumpWidget(
      buildTestApp(
        const OmnibotWorkspacePage(
          workspacePath: '/other/path',
          workspaceId: 'unknown',
        ),
      ),
    );
    await tester.pumpAndSettle();

    final button = tester.widget<IconButton>(
      find.byKey(const ValueKey('workspace-changes-history-button')),
    );
    expect(button.onPressed, isNull);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
  testWidgets('missing expectedAfterHashes in preview disables restore button and shows reason', (tester) async {
    mockResponse = <String, dynamic>{
      'item': {
        'changeId': 'c-no-hash',
        'conversationId': 42,
        'sessionId': 's1',
        'turnId': 't1',
        'workspaceRoot': '/ws',
        'operation': 'write',
        'targetRelativePath': 'no_hash.txt',
        'snapshotStatus': 'ready',
        'revision': 1,
        'createdAt': 1700000000000,
      },
      'relativePath': 'no_hash.txt',
      'diffText': '--- a/no_hash.txt\n+++ b/no_hash.txt\n@@ -0,0 +1 @@\n+data\n',
      'isBinary': false,
      'canRestore': true,
      // expectedAfterHashes is missing
    };

    await tester.pumpWidget(
      buildTestApp(
        const Scaffold(
          body: WorkspaceChangePreviewSheet(
            changeId: 'c-no-hash',
            conversationId: 42,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final restoreBtn = find.byKey(const ValueKey('workspace-change-restore-button'));
    final canIssueRestore = restoreBtn.evaluate().isNotEmpty &&
        tester.widget<FilledButton>(restoreBtn).onPressed != null;
    expect(canIssueRestore, isFalse, reason: 'Missing expected hashes must prohibit restore');
    expect(find.byKey(const ValueKey('workspace-change-unavailable-reason')), findsOneWidget);
    expect(find.textContaining('缺少恢复所需的文件状态指纹'), findsOneWidget);
    expect(recordedCalls.any((c) => c.method == 'workspaceChangeRestore'), isFalse);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('conflict status refuses restore and shows conflict reason', (tester) async {
    mockResponse = <String, dynamic>{
      'item': {
        'changeId': 'c-conflict',
        'conversationId': 42,
        'sessionId': 's1',
        'turnId': 't1',
        'workspaceRoot': '/ws',
        'operation': 'write',
        'targetRelativePath': 'conflict.txt',
        'snapshotStatus': 'conflict',
        'revision': 1,
        'createdAt': 1700000000000,
        'unavailableReason': 'workspace_change_conflict',
      },
      'relativePath': 'conflict.txt',
      'diffText': '--- a/conflict.txt\n+++ b/conflict.txt\n@@ -0,0 +1 @@\n+conflict\n',
      'isBinary': false,
      'canRestore': false,
      'expectedAfterHashes': {
        'conflict.txt': '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
      },
    };

    await tester.pumpWidget(
      buildTestApp(
        const Scaffold(
          body: WorkspaceChangePreviewSheet(
            changeId: 'c-conflict',
            conversationId: 42,
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final restoreBtn = find.byKey(const ValueKey('workspace-change-restore-button'));
    final canIssueRestore = restoreBtn.evaluate().isNotEmpty &&
        tester.widget<FilledButton>(restoreBtn).onPressed != null;
    expect(canIssueRestore, isFalse, reason: 'Conflict must refuse restore writes');
    expect(recordedCalls.any((c) => c.method == 'workspaceChangeRestore'), isFalse);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });

  testWidgets('source-deleted conversation changes display deleted indicator and remain manageable', (tester) async {
    mockResponse = <String, dynamic>{
      'workspaceId': 'conversation_default',
      'items': [
        {
          'changeId': 'change-orphan',
          'conversationId': 99,
          'sessionId': 'session-old',
          'turnId': 'turn-old',
          'toolCallId': 'call-old',
          'workspaceRoot': '/data/user/0/workspace',
          'operation': 'write',
          'targetRelativePath': 'lib/orphan.dart',
          'snapshotStatus': 'ready',
          'revision': 1,
          'createdAt': 1700000000000,
          'sourceConversationDeleted': true,
        },
      ],
    };

    await tester.pumpWidget(
      buildTestApp(
        const WorkspaceChangesManagementPage(
          workspaceId: '/data/user/0/workspace',
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('workspace-changes-list')), findsOneWidget);
    expect(find.text('lib/orphan.dart'), findsOneWidget);
    expect(find.text('来源对话已删除'), findsOneWidget);
    expect(find.text('可恢复'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
  });
}
