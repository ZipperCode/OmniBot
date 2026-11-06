import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_diff_viewer.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/agent_tool_transcript.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const fileChannel = MethodChannel('cn.com.omnimind.bot/file_save');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final clipboard = <String>[];
  final fileCalls = <MethodCall>[];

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    clipboard.clear();
    fileCalls.clear();
    messenger.setMockMethodCallHandler(AssistsMessageService.assistCore, (
      call,
    ) async {
      if (call.method == 'copyToClipboard') {
        clipboard.add((call.arguments as Map)['text'] as String);
        return 'SUCCESS';
      }
      return null;
    });
    messenger.setMockMethodCallHandler(spePermission, (call) async {
      if (call.method == 'isWorkspaceStorageAccessGranted') return true;
      return null;
    });
    messenger.setMockMethodCallHandler(fileChannel, (call) async {
      fileCalls.add(call);
      return null;
    });
    OmnibotResourceService.debugSetWorkspacePaths(
      const OmnibotWorkspacePaths(
        rootPath: '/data/user/0/cn.com.omnimind.bot/workspace',
        shellRootPath: '/workspace',
        internalRootPath: '/data/user/0/cn.com.omnimind.bot/workspace/.omnibot',
      ),
    );
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(AssistsMessageService.assistCore, null);
    messenger.setMockMethodCallHandler(spePermission, null);
    messenger.setMockMethodCallHandler(fileChannel, null);
    OmnibotResourceService.debugResetWorkspacePaths();
    LegacyTextLocalizer.clearResolvedLocale();
  });

  testWidgets('complete persisted output remains scrollable and copyable', (
    tester,
  ) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    final records = List.generate(128, (index) => 'record-$index');
    await tester.pumpWidget(
      _app(
        AgentToolDetailBody(
          scrollController: controller,
          cardData: {
            'toolName': 'external_list',
            'toolType': 'mcp',
            'resultPreviewJson': jsonEncode(records.take(1).toList()),
            'rawResultJson': jsonEncode(records),
          },
        ),
      ),
    );

    expect(find.text('输入'), findsOneWidget);
    expect(controller.hasClients, isTrue);
    expect(controller.position.maxScrollExtent, greaterThan(0));
    final output = tester
        .widgetList<SelectableText>(find.byType(SelectableText))
        .last;
    expect(output.data, contains('record-127'));

    await tester.tap(find.byTooltip('复制详情'));
    await tester.pump();
    expect(clipboard.single, contains('external_list'));
    expect(clipboard.single, contains('record-127'));
    expect(clipboard.single, isNot(contains('[truncated]')));

    await tester.drag(
      find.byType(SingleChildScrollView).first,
      const Offset(0, -200),
    );
    await tester.pumpAndSettle();
    expect(controller.offset, greaterThan(0));
  });

  for (final brightness in Brightness.values) {
    testWidgets('terminal output is readable in ${brightness.name} mode', (
      tester,
    ) async {
      final theme = brightness == Brightness.light
          ? AppTheme.lightTheme
          : AppTheme.darkTheme;
      await tester.pumpWidget(
        _app(
          AgentToolDetailBody(
            cardData: {
              'toolName': 'terminal_execute',
              'toolType': 'terminal',
              'argsJson': jsonEncode({'command': 'printf hello'}),
              'terminalOutput': '\u001b[97mhello\u001b[0m',
            },
          ),
          theme: theme,
        ),
      );

      expect(find.text('命令'), findsOneWidget);
      final output = tester.widget<SelectableText>(
        find.byWidgetPredicate(
          (widget) => widget is SelectableText && widget.data == 'hello',
        ),
      );
      final palette = theme.extension<OmniThemePalette>()!;
      expect(output.data, 'hello');
      expect(
        _contrast(output.style!.color!, palette.surfacePrimary),
        greaterThan(7),
      );
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('diff details use the supplied scroll and retain full copy', (
    tester,
  ) async {
    final controller = ScrollController();
    addTearDown(controller.dispose);
    const diff = '''
diff --git a/lib/main.dart b/lib/main.dart
--- a/lib/main.dart
+++ b/lib/main.dart
@@ -1,2 +1,2 @@
-old line
+new line
 same line
''';
    await tester.pumpWidget(
      _app(
        AgentToolDetailBody(
          scrollController: controller,
          cardData: {
            'toolType': 'terminal',
            'toolName': 'terminal_execute',
            'argsJson': jsonEncode({'command': 'git diff'}),
            // ACP can persist a diff in terminal output without diffText.
            'terminalOutput': diff,
          },
        ),
      ),
    );

    expect(find.byType(AgentDiffViewer), findsOneWidget);
    expect(controller.hasClients, isTrue);
    expect(
      tester.widget<AgentDiffViewer>(find.byType(AgentDiffViewer)).scrollable,
      isFalse,
    );
    await tester.tap(find.byTooltip('复制详情'));
    await tester.pump();
    expect(clipboard.single, contains('-old line'));
    expect(clipboard.single, contains('+new line'));
    expect(tester.takeException(), isNull);
  });

  testWidgets('resource action preserves its original payload', (tester) async {
    const sourcePath =
        '/data/user/0/cn.com.omnimind.bot/workspace/session-42/report.txt';
    await tester.pumpWidget(
      _app(
        const AgentToolDetailBody(
          cardData: {
            'toolType': 'file',
            'toolName': 'write_file',
            'status': 'success',
            'summary': 'Saved report',
            'actions': [
              {
                'type': 'save',
                'label': '保存报告',
                'payload': {
                  'path': sourcePath,
                  'fileName': 'report.txt',
                  'mimeType': 'text/plain',
                },
              },
            ],
          },
        ),
      ),
    );

    await tester.ensureVisible(find.text('保存报告'));
    await tester.runAsync(() async {
      await tester.tap(find.text('保存报告'));
      await Future<void>.delayed(Duration.zero);
    });
    await tester.pumpAndSettle();
    expect(fileCalls.single.method, 'saveFileWithSystemDialog');
    expect(fileCalls.single.arguments, {
      'sourcePath': sourcePath,
      'fileName': 'report.txt',
      'mimeType': 'text/plain',
    });
  });
}

Widget _app(Widget body, {ThemeData? theme}) {
  return MaterialApp(
    theme: theme ?? AppTheme.lightTheme,
    home: Scaffold(
      body: Center(child: SizedBox(width: 390, height: 420, child: body)),
    ),
  );
}

double _contrast(Color foreground, Color background) {
  final first = foreground.computeLuminance();
  final second = background.computeLuminance();
  return first > second
      ? (first + 0.05) / (second + 0.05)
      : (second + 0.05) / (first + 0.05);
}
