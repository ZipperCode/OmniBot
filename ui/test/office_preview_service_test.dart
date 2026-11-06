import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/office_preview_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/file_save');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() => LegacyTextLocalizer.setResolvedLocale(const Locale('zh', 'CN')));
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('only the source identity crosses the channel and every paragraph is retained', () async {
    final paragraphs = List.generate(30, (i) => '$i:${'正文' * 200}:END-$i');
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'loadOfficePreview');
      expect(call.arguments, {
        'path': '/workspace/long.docx',
        'previewKind': 'office_word',
      });
      return {
        'sections': [
          {'lines': paragraphs, 'tableRows': []},
        ],
      };
    });
    final preview = await OmnibotOfficePreviewService.loadPreview(
      path: '/workspace/long.docx',
      previewKind: 'office_word',
    );
    expect(preview.sections.single.lines, paragraphs);
    expect(preview.summary, '共提取 30 段正文');
    expect(preview.truncated, isFalse);
  });

  for (final english in [false, true]) {
    test(
      'Word presentation retains its original localization english=$english',
      () async {
        LegacyTextLocalizer.setResolvedLocale(
          english ? const Locale('en') : const Locale('zh', 'CN'),
        );
        messenger.setMockMethodCallHandler(
          channel,
          (_) async => {
            'sections': [
              {
                'lines': ['第一段', '第二段'],
                'tableRows': [],
              },
            ],
          },
        );
        final preview = await OmnibotOfficePreviewService.loadPreview(
          path: '/workspace/demo.docx',
          previewKind: 'office_word',
        );
        expect(preview.kindLabel, english ? 'Word Preview' : 'Word 预览');
        expect(
          preview.summary,
          english ? 'Extracted 2 paragraphs in total' : '共提取 2 段正文',
        );
        expect(preview.sections.single.title, english ? 'Body' : '正文');
        expect(
          preview.sections.single.subtitle,
          english ? 'Scroll to view extracted document content' : '滚动查看文档提取内容',
        );
        expect(preview.sections.single.lines, ['第一段', '第二段']);
      },
    );

    test(
      'sheet presentation retains names sparse tables and empty-state localization english=$english',
      () async {
        LegacyTextLocalizer.setResolvedLocale(
          english ? const Locale('en') : const Locale('zh', 'CN'),
        );
        final rows = List.generate(
          25,
          (_) => List.filled(12, '${'数据' * 80}:END'),
        );
        messenger.setMockMethodCallHandler(
          channel,
          (_) async => {
            'sections': [
              {'title': '原工作表', 'lines': [], 'tableRows': rows},
              {'title': '', 'lines': [], 'tableRows': []},
            ],
          },
        );
        final preview = await OmnibotOfficePreviewService.loadPreview(
          path: '/workspace/demo.xlsx',
          previewKind: 'office_sheet',
        );
        expect(preview.kindLabel, english ? 'Excel Preview' : 'Excel 预览');
        expect(
          preview.summary,
          english ? 'Extracted 2 worksheets in total' : '共提取 2 个工作表',
        );
        expect(preview.sections.first.title, '原工作表');
        expect(
          preview.sections.first.subtitle,
          english ? 'Extracted 25 rows in total' : '共提取 25 行',
        );
        expect(preview.sections.first.tableRows, rows);
        expect(preview.sections.first.hasTable, isTrue);
        expect(preview.sections.last.title, english ? 'Sheet 2' : '工作表 2');
        expect(
          preview.sections.last.subtitle,
          english ? 'No cell content extracted' : '未提取到单元格内容',
        );
        expect(preview.sections.last.lines, [
          english ? 'No previewable text in this worksheet' : '该工作表暂无可预览文本',
        ]);
        expect(preview.truncated, isFalse);
      },
    );

    test(
      'slide presentation retains every line and empty slide localization english=$english',
      () async {
        LegacyTextLocalizer.setResolvedLocale(
          english ? const Locale('en') : const Locale('zh', 'CN'),
        );
        final lines = List.generate(12, (i) => '$i:${'幻灯片' * 100}:END-$i');
        messenger.setMockMethodCallHandler(
          channel,
          (_) async => {
            'sections': [
              {'lines': lines, 'tableRows': []},
              {'lines': [], 'tableRows': []},
            ],
          },
        );
        final preview = await OmnibotOfficePreviewService.loadPreview(
          path: '/workspace/demo.pptx',
          previewKind: 'office_slide',
        );
        expect(
          preview.kindLabel,
          english ? 'PowerPoint Preview' : 'PowerPoint 预览',
        );
        expect(
          preview.summary,
          english ? 'Extracted 2 slides in total' : '共提取 2 页幻灯片',
        );
        expect(preview.sections.first.title, english ? 'Slide 1' : '第 1 页');
        expect(preview.sections.first.lines, lines);
        expect(preview.sections.last.title, english ? 'Slide 2' : '第 2 页');
        expect(preview.sections.last.lines, [
          english ? 'No extractable text on this slide' : '该页没有可提取文本',
        ]);
        expect(preview.truncated, isFalse);
      },
    );

    test(
      'native errors retain localized preview messages english=$english',
      () async {
        LegacyTextLocalizer.setResolvedLocale(
          english ? const Locale('en') : const Locale('zh', 'CN'),
        );
        final cases = {
          'OFFICE_UNSUPPORTED': english
              ? 'This Office file type is not supported'
              : '暂不支持该 Office 文件类型',
          'OFFICE_MISSING_ENTRY': english
              ? 'File missing: detail'
              : '文件缺少 detail',
          'OFFICE_EMPTY_WORD': english
              ? 'No previewable Word text content found'
              : '未找到可预览的 Word 文本内容',
          'OFFICE_EMPTY_SHEETS': english
              ? 'No previewable Excel worksheet content found'
              : '未找到可预览的 Excel 工作表内容',
          'OFFICE_EMPTY_SLIDES': english
              ? 'No previewable PowerPoint slides found'
              : '未找到可预览的 PowerPoint 页面',
          'OFFICE_XML_ERROR': english
              ? 'Failed to parse Office file structure: detail'
              : 'Office 文件结构解析失败: detail',
          'OFFICE_PREVIEW_FAILED': english
              ? 'Office file preview failed: detail'
              : 'Office 文件预览失败: detail',
        };
        for (final entry in cases.entries) {
          messenger.setMockMethodCallHandler(
            channel,
            (_) async =>
                throw PlatformException(code: entry.key, message: 'detail'),
          );
          await expectLater(
            OmnibotOfficePreviewService.loadPreview(
              path: '/workspace/broken.docx',
              previewKind: 'office_word',
            ),
            throwsA(
              isA<StateError>().having(
                (error) => error.message,
                'message',
                entry.value,
              ),
            ),
          );
        }
      },
    );
  }
}
