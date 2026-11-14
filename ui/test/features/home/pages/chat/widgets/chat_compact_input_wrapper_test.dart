import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_compact_input_wrapper.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
  });

  Widget buildWrapperApp({
    required Size physicalSize,
    required double devicePixelRatio,
    required Widget child,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      locale: const Locale('zh'),
      home: MediaQuery(
        data: MediaQueryData(
          size: Size(
            physicalSize.width / devicePixelRatio,
            physicalSize.height / devicePixelRatio,
          ),
          devicePixelRatio: devicePixelRatio,
          viewInsets: EdgeInsets.zero,
          padding: EdgeInsets.zero,
        ),
        child: Scaffold(body: child),
      ),
    );
  }

  testWidgets('tall viewport (>= 420) renders regular layout without capsule', (tester) async {
    // 390x844 iPhone regular height
    await tester.pumpWidget(
      buildWrapperApp(
        physicalSize: const Size(390, 844),
        devicePixelRatio: 1.0,
        child: const ChatCompactInputWrapper(
          outboxCount: 2,
          attachmentCount: 1,
          blockingCardChild: Text('审批卡片', key: ValueKey('approval-card')),
          criticalErrorChild: Text('错误提示', key: ValueKey('error-card')),
          nonBlockingDetailsChild: Text('待发详情', key: ValueKey('outbox-details')),
          inputChild: TextField(key: ValueKey('chat-input-field')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('chat-compact-status-capsule')), findsNothing);
    expect(find.byKey(const ValueKey('approval-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('error-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('outbox-details')), findsOneWidget);
    expect(find.byKey(const ValueKey('chat-input-field')), findsOneWidget);
  });

  testWidgets('compact viewport (< 420) shows status capsule but preserves blocking card and errors inline', (tester) async {
    // Landscape or constrained height: 640x360
    await tester.pumpWidget(
      buildWrapperApp(
        physicalSize: const Size(640, 360),
        devicePixelRatio: 1.0,
        child: const ChatCompactInputWrapper(
          outboxCount: 2,
          attachmentCount: 1,
          blockingCardChild: Text('审批卡片', key: ValueKey('approval-card')),
          criticalErrorChild: Text('错误提示', key: ValueKey('error-card')),
          nonBlockingDetailsChild: Text('待发详情', key: ValueKey('outbox-details')),
          inputChild: TextField(key: ValueKey('chat-input-field')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // 1. Capsule is visible with compact summary
    final capsule = find.byKey(const ValueKey('chat-compact-status-capsule'));
    expect(capsule, findsOneWidget);
    expect(find.textContaining('待发 2'), findsOneWidget);
    expect(find.textContaining('附件 1'), findsOneWidget);

    // 2. Blocking card and critical error CANNOT be folded
    expect(find.byKey(const ValueKey('approval-card')), findsOneWidget);
    expect(find.byKey(const ValueKey('error-card')), findsOneWidget);

    // 3. Primary input field remains in place
    expect(find.byKey(const ValueKey('chat-input-field')), findsOneWidget);

    // 4. Non-blocking details are not inline; tapping capsule presents them in sheet
    expect(find.byKey(const ValueKey('outbox-details')), findsNothing);

    await tester.tap(capsule);
    await tester.pumpAndSettle();

    // Now details are visible in the expanded sheet
    expect(find.byKey(const ValueKey('outbox-details')), findsOneWidget);
  });

  testWidgets('compact viewport displays attachments list in sheet and allows removal', (tester) async {
    String? removedId;
    const attachment = ChatInputAttachment(
      id: 'att-1',
      name: 'test_doc.pdf',
      path: '/workspace/test_doc.pdf',
      size: 1024,
    );

    await tester.pumpWidget(
      buildWrapperApp(
        physicalSize: const Size(640, 360),
        devicePixelRatio: 1.0,
        child: ChatCompactInputWrapper(
          attachments: [attachment],
          onRemoveAttachment: (id) => removedId = id,
          inputChild: const TextField(key: ValueKey('chat-input-field')),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final capsule = find.byKey(const ValueKey('chat-compact-status-capsule'));
    expect(capsule, findsOneWidget);

    await tester.tap(capsule);
    await tester.pumpAndSettle();

    expect(find.text('test_doc.pdf'), findsOneWidget);
    expect(find.byIcon(Icons.close), findsNothing); // LucideIcons.x is used

    // Tap remove icon
    await tester.tap(find.byType(InkWell).last);
    await tester.pumpAndSettle();

    expect(removedId, 'att-1');
  });
}
