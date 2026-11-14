import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/workspace_change_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  Map<String, dynamic>? mockResponse;

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    mockResponse = null;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      return mockResponse;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('listChanges parses change summary entries correctly', () async {
    mockResponse = <String, dynamic>{
      'items': [
        {
          'changeId': 'change-1',
          'conversationId': 42,
          'sessionId': 'session-1',
          'turnId': 'turn-1',
          'toolCallId': 'call-1',
          'workspaceRoot': '/data/user/0/workspace',
          'operation': 'write',
          'targetRelativePath': 'lib/main.dart',
          'snapshotStatus': 'ready',
          'revision': 1,
          'createdAt': 1700000000000,
          'snapshotBytes': 1024,
        },
        {
          'changeId': 'change-2',
          'conversationId': 42,
          'sessionId': 'session-1',
          'turnId': 'turn-1',
          'toolCallId': 'call-2',
          'workspaceRoot': '/data/user/0/workspace',
          'operation': 'create',
          'targetRelativePath': 'README.md',
          'snapshotStatus': 'unavailable',
          'unavailableReason': 'file_too_large',
          'revision': 1,
          'createdAt': 1700000001000,
        },
      ],
    };

    final items = await WorkspaceChangeService.listChanges(
      conversationId: 42,
      turnId: 'turn-1',
    );

    expect(items, hasLength(2));
    expect(items[0].changeId, 'change-1');
    expect(items[0].displayPath, 'lib/main.dart');
    expect(items[0].isReady, isTrue);

    expect(items[1].changeId, 'change-2');
    expect(items[1].isUnavailable, isTrue);
    expect(items[1].unavailableReason, 'file_too_large');

  });


  test('previewChange parses diffText into unified diff and canRestore flag', () async {
    mockResponse = <String, dynamic>{
      'item': {
        'changeId': 'change-prev-1',
        'conversationId': 50,
        'sessionId': 's1',
        'turnId': 't1',
        'workspaceRoot': '/ws',
        'operation': 'write',
        'targetRelativePath': 'test.txt',
        'snapshotStatus': 'ready',
        'revision': 1,
        'createdAt': 1700000000000,
      },
      'relativePath': 'test.txt',
      'diffText': '''
--- a/test.txt
+++ b/test.txt
@@ -1,1 +1,2 @@
 hello
+world
''',
      'isBinary': false,
      'canRestore': true,
      'expectedAfterHashes': {
        'test.txt': '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
      },
    };

    final preview = await WorkspaceChangeService.previewChange(
      changeId: 'change-prev-1',
      conversationId: 50,
      workspaceId: '/ws',
    );

    expect(preview, isNotNull);
    expect(preview!.canRestore, isTrue);
    expect(preview.isBinary, isFalse);
    expect(preview.diffSummary, isNotNull);
    expect(preview.diffSummary!.files, hasLength(1));
    expect(
      preview.expectedAfterHashes['test.txt'],
      '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
    );
    expect(preview.diffSummary!.files.first.displayPath, 'test.txt');
  });

  test('restoreChange handles restored, conflict and busy statuses', () async {
    // 1. Success restored
    mockResponse = <String, dynamic>{
      'status': 'restored',
      'success': true,
      'revision': 2,
      'message': 'Restored successfully',
    };

    final successRes = await WorkspaceChangeService.restoreChange(
      changeId: 'c1',
      expectedRevision: 1,
      expectedAfterHashes: {'test.txt': 'hash-after'},
      conversationId: 50,
    );

    expect(successRes.isRestored, isTrue);
    expect(successRes.revision, 2);

    // 2. Conflict
    mockResponse = <String, dynamic>{
      'status': 'workspace_change_conflict',
      'success': false,
      'message': 'File modified externally',
    };

    final conflictRes = await WorkspaceChangeService.restoreChange(
      changeId: 'c1',
      expectedRevision: 1,
      expectedAfterHashes: {'test.txt': 'hash-after'},
      conversationId: 50,
    );

    expect(conflictRes.isConflict, isTrue);
    expect(conflictRes.isRestored, isFalse);

    // 3. Busy
    mockResponse = <String, dynamic>{
      'status': 'workspace_change_busy',
      'success': false,
      'message': 'Workspace is held by active subagent',
    };

    final busyRes = await WorkspaceChangeService.restoreChange(
      changeId: 'c1',
      expectedRevision: 1,
      expectedAfterHashes: {'test.txt': 'hash-after'},
      conversationId: 50,
    );

    expect(busyRes.isBusy, isTrue);
    expect(busyRes.isRestored, isFalse);
  });


  test('previewChange without server expectedAfterHashes disables canRestore and sets missing_after_hashes reason', () async {
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
      // expectedAfterHashes is intentionally omitted/null from server response
    };

    final preview = await WorkspaceChangeService.previewChange(
      changeId: 'c-no-hash',
      conversationId: 42,
    );

    expect(preview, isNotNull);
    // Strictly forbidden to guess/synthesize hashes: canRestore must be false
    expect(preview!.canRestore, isFalse);
    expect(preview.expectedAfterHashes, isEmpty);
    expect(preview.item.isReady, isFalse);
    expect(preview.item.isUnavailable, isTrue);
    expect(preview.item.unavailableReason, 'missing_after_hashes');
  });
}
