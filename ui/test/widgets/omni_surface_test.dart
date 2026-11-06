import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/theme/omni_theme_palette.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_glass.dart';

void main() {
  for (final dark in [false, true]) {
    testWidgets('shared popup is opaque and legible (dark=$dark)', (
      tester,
    ) async {
      final palette = dark ? OmniThemePalette.dark : OmniThemePalette.light;
      await tester.pumpWidget(
        MaterialApp(
          theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
          home: Scaffold(
            body: const Center(
              child: OmniGlassPanel(
                padding: EdgeInsets.all(16),
                child: Text('模型与权限'),
              ),
            ),
          ),
        ),
      );
      final surface = tester.widget<Container>(
        find.descendant(
          of: find.byType(OmniGlassPanel),
          matching: find.byType(Container),
        ).first,
      );
      final decoration = surface.decoration! as BoxDecoration;
      expect(decoration.color, palette.surfacePrimary);
      expect(decoration.color!.a, 1);
      expect(decoration.gradient, isNull);
      expect(find.byType(BackdropFilter), findsNothing);
    });

    testWidgets('shared dialogs preserve actions and input (dark=$dark)', (
      tester,
    ) async {
      late BuildContext host;
      await tester.pumpWidget(
        MaterialApp(
          theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
          home: Builder(
            builder: (context) {
              host = context;
              return const Scaffold(body: SizedBox.expand());
            },
          ),
        ),
      );
      final confirmed = AppDialog.confirm(
        host,
        title: 'Apply changes',
        confirmText: 'Apply',
        cancelText: 'Cancel',
      );
      await tester.pumpAndSettle();
      expect(find.byType(BackdropFilter), findsNothing);
      final action = tester.widget<FilledButton>(find.byType(FilledButton));
      final fill = action.style!.backgroundColor!.resolve({});
      final text = action.style!.foregroundColor!.resolve({});
      expect(
        ThemeData.estimateBrightnessForColor(fill!),
        isNot(ThemeData.estimateBrightnessForColor(text!)),
      );
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();
      expect(await confirmed, isTrue);

      final cancelled = AppDialog.confirm(
        host,
        title: 'Apply changes',
        confirmText: 'Apply',
        cancelText: 'Cancel',
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(await cancelled, isNull);

      final input = AppDialog.input(
        host,
        title: 'Rename',
        initialValue: 'Old name',
        confirmText: 'Save',
      );
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'New name');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(await input, 'New name');
      expect(tester.takeException(), isNull);
    });
  }
}
