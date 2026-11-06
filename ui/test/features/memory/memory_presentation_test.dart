import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/memory/pages/memory_center/widgets/mem0_memory_editor_sheet.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

void main() {
  Widget app(Widget home, {required bool dark}) => MaterialApp(
    theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
    locale: const Locale('zh'),
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(1.5)),
      child: child!,
    ),
    home: home,
  );

  for (final dark in [false, true]) {
    testWidgets(
      'memory editor stays scrollable with keyboard and submits in ${dark ? 'dark' : 'light'}',
      (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = const Size(320, 568);
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);
        addTearDown(tester.view.resetViewInsets);
        Mem0MemoryEditorResult? result;
        await tester.pumpWidget(
          app(
            Scaffold(
              body: Builder(
                builder: (context) => TextButton(
                  onPressed: () async {
                    result = await showModalBottomSheet<Mem0MemoryEditorResult>(
                      context: context,
                      isScrollControlled: true,
                      builder: (_) => const Mem0MemoryEditorSheet(
                        title: '编辑长期记忆',
                        submitLabel: '保存',
                        initialMemory: '已有记忆',
                      ),
                    );
                  },
                  child: const Text('打开'),
                ),
              ),
            ),
            dark: dark,
          ),
        );
        await tester.tap(find.text('打开'));
        await tester.pumpAndSettle();
        tester.view.viewInsets = const FakeViewPadding(bottom: 220);
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.enterText(find.byType(TextField).first, '新的记忆');
        await tester.enterText(find.byType(TextField).last, '偏好, 工作, 偏好');
        await tester.ensureVisible(find.text('保存'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('保存'));
        await tester.pumpAndSettle();
        expect(result?.memory, '新的记忆');
        expect(result?.categories, ['偏好', '工作']);
        expect(tester.takeException(), isNull);
      },
    );
  }
}
