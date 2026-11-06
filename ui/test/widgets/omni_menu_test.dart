import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_menu.dart';

const _triggerKey = ValueKey('menu-trigger');

Widget _menuHost({
  required List<PopupMenuEntry<String>> items,
  required ValueChanged<String> onSelected,
  ThemeData? theme,
  Alignment alignment = Alignment.topLeft,
}) => MaterialApp(
  theme: theme ?? AppTheme.lightTheme,
  home: Scaffold(
    body: Align(
      alignment: alignment,
      child: Builder(
        builder: (context) => TextButton(
          key: _triggerKey,
          onPressed: () async {
            final anchor = glassPopupAnchorFromContext(context)!;
            final selected = await showOmniMenu<String>(
              context: context,
              anchor: anchor,
              items: items,
            );
            if (selected != null) onSelected(selected);
          },
          child: const Text('Open'),
        ),
      ),
    ),
  ),
);

void main() {
  testWidgets('popup ignores disabled actions and returns the selected value', (
    tester,
  ) async {
    String? selected;
    var callbacks = 0;
    final semantics = tester.ensureSemantics();
    try {
      await tester.pumpWidget(
        _menuHost(
          onSelected: (value) => selected = value,
          items: [
            const OmniPopupMenuItem<String>(
              value: 'disabled',
              enabled: false,
              child: Text('Unavailable'),
            ),
            OmniPopupMenuItem<String>(
              value: 'archive',
              onTap: () => callbacks++,
              child: const Text('Archive'),
            ),
          ],
        ),
      );
      await tester.tap(find.text('Open'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Unavailable'));
      await tester.pumpAndSettle();
      expect(selected, isNull);
      expect(find.text('Archive'), findsOneWidget);
      await tester.tap(find.text('Archive'));
      await tester.pumpAndSettle();
      expect(selected, 'archive');
      expect(callbacks, 1);
      expect(find.text('Archive'), findsNothing);
      expect(tester.takeException(), isNull);
    } finally {
      semantics.dispose();
    }
  });

  testWidgets('popup retains keyboard activation', (tester) async {
    String? selected;
    await tester.pumpWidget(
      _menuHost(
        theme: AppTheme.darkTheme,
        onSelected: (value) => selected = value,
        items: const [
          OmniPopupMenuItem<String>(value: 'copy', child: Text('Copy')),
          OmniPopupMenuItem<String>(value: 'archive', child: Text('Archive')),
        ],
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
    final ink = tester.widget<InkWell>(
      find
          .descendant(
            of: find.byType(OmniMenuItem),
            matching: find.byType(InkWell),
          )
          .first,
    );
    expect(ink.canRequestFocus, isTrue);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(selected, 'copy');
  });

  testWidgets('escape dismisses the menu without selecting an action', (
    tester,
  ) async {
    String? selected;
    await tester.pumpWidget(
      _menuHost(
        onSelected: (value) => selected = value,
        items: const [
          OmniPopupMenuItem<String>(value: 'copy', child: Text('Copy')),
        ],
      ),
    );
    await tester.tap(find.byKey(_triggerKey));
    await tester.pumpAndSettle();
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(selected, isNull);
    expect(find.byType(OmniMenuSurface), findsNothing);
  });

  testWidgets('a narrow menu covers its trigger and lets large text wrap', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await tester.pumpWidget(
      _menuHost(
        alignment: Alignment.topRight,
        onSelected: (_) {},
        items: const [
          OmniPopupMenuItem<String>(
            value: 'long',
            child: Text('A long menu action that wraps over several lines'),
          ),
        ],
      ),
    );
    final trigger = tester.getRect(find.byKey(_triggerKey));
    await tester.tap(find.byKey(_triggerKey));
    await tester.pumpAndSettle();
    final menu = tester.getRect(find.byType(OmniMenuSurface));
    expect(menu.overlaps(trigger), isTrue);
    expect(menu.left, greaterThanOrEqualTo(8));
    expect(menu.right, lessThanOrEqualTo(312));
    expect(
      tester.getSize(find.byType(OmniMenuItem)).height,
      greaterThan(OmniMenuStyle.minHeight),
    );
    expect(tester.takeException(), isNull);
  });

  testWidgets('a long menu stays on screen and scrolls to its last action', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    String? selected;
    await tester.pumpWidget(
      _menuHost(
        onSelected: (value) => selected = value,
        items: [
          for (var index = 0; index < 30; index++)
            OmniPopupMenuItem<String>(
              value: '$index',
              child: Text('Action $index'),
            ),
        ],
      ),
    );
    await tester.tap(find.byKey(_triggerKey));
    await tester.pumpAndSettle();
    final menu = tester.getRect(find.byType(OmniMenuSurface));
    expect(menu.top, greaterThanOrEqualTo(8));
    expect(menu.bottom, lessThanOrEqualTo(632));
    await tester.scrollUntilVisible(
      find.text('Action 29'),
      300,
      scrollable: find.descendant(
        of: find.byType(OmniMenuSurface),
        matching: find.byType(Scrollable),
      ),
    );
    await tester.tap(find.text('Action 29'));
    await tester.pumpAndSettle();
    expect(selected, '29');
    expect(tester.takeException(), isNull);
  });

  testWidgets('overlay rows leave dismissal to their owner', (tester) async {
    var enabled = false;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.lightTheme,
        home: Scaffold(
          body: StatefulBuilder(
            builder: (context, setState) => SizedBox(
              width: 280,
              child: OmniMenuSurface(
                child: OmniMenuItem(
                  onTap: () => setState(() => enabled = !enabled),
                  label: 'Show model',
                  selected: enabled,
                  trailing: Switch(
                    value: enabled,
                    onChanged: (value) => setState(() => enabled = value),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.byType(Switch));
    await tester.pumpAndSettle();
    expect(enabled, isTrue);
    await tester.tap(find.text('Show model'));
    await tester.pumpAndSettle();
    expect(enabled, isFalse);
    expect(find.byType(OmniMenuSurface), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
