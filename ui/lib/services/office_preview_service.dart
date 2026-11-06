import 'package:flutter/services.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';

class OmnibotOfficePreviewData {
  final String kindLabel;
  final String summary;
  final List<OmnibotOfficePreviewSection> sections;
  final bool truncated;

  const OmnibotOfficePreviewData({
    required this.kindLabel,
    required this.summary,
    required this.sections,
    this.truncated = false,
  });
}

class OmnibotOfficePreviewSection {
  final String title;
  final String? subtitle;
  final List<String> lines;
  final List<List<String>> tableRows;

  const OmnibotOfficePreviewSection({
    required this.title,
    this.subtitle,
    this.lines = const <String>[],
    this.tableRows = const <List<String>>[],
  });

  bool get hasTable => tableRows.isNotEmpty;
}

/// The host reads ZIP/XML data; Flutter only maps it to the existing preview UI.
class OmnibotOfficePreviewService {
  static const MethodChannel _channel = MethodChannel(
    'cn.com.omnimind.bot/file_save',
  );

  static Future<OmnibotOfficePreviewData> loadPreview({
    required String path,
    required String previewKind,
  }) async {
    try {
      final payload = await _channel.invokeMapMethod<String, dynamic>(
        'loadOfficePreview',
        {'path': path, 'previewKind': previewKind},
      );
      if (payload == null) {
        throw StateError(_text('Office preview failed', 'Office 预览失败'));
      }
      final sections = (payload['sections'] as List).cast<Map>();
      return switch (previewKind) {
        'office_word' => OmnibotOfficePreviewData(
          kindLabel: _text('Word Preview', 'Word 预览'),
          summary: _text(
            'Extracted ${(sections.single['lines'] as List).length} paragraphs in total',
            '共提取 ${(sections.single['lines'] as List).length} 段正文',
          ),
          sections: [
            OmnibotOfficePreviewSection(
              title: _text('Body', '正文'),
              subtitle: _text(
                'Scroll to view extracted document content',
                '滚动查看文档提取内容',
              ),
              lines: _lines(sections.single),
            ),
          ],
        ),
        'office_sheet' => OmnibotOfficePreviewData(
          kindLabel: _text('Excel Preview', 'Excel 预览'),
          summary: _text(
            'Extracted ${sections.length} worksheets in total',
            '共提取 ${sections.length} 个工作表',
          ),
          sections: [
            for (var index = 0; index < sections.length; index++)
              _sheetSection(sections[index], index),
          ],
        ),
        'office_slide' => OmnibotOfficePreviewData(
          kindLabel: _text('PowerPoint Preview', 'PowerPoint 预览'),
          summary: _text(
            'Extracted ${sections.length} slides in total',
            '共提取 ${sections.length} 页幻灯片',
          ),
          sections: [
            for (var index = 0; index < sections.length; index++)
              OmnibotOfficePreviewSection(
                title: _text('Slide ${index + 1}', '第 ${index + 1} 页'),
                lines: _lines(sections[index]).isEmpty
                    ? [_text('No extractable text on this slide', '该页没有可提取文本')]
                    : _lines(sections[index]),
              ),
          ],
        ),
        _ => throw StateError(
          _text('This Office file type is not supported', '暂不支持该 Office 文件类型'),
        ),
      };
    } on PlatformException catch (error) {
      final detail = error.message ?? '';
      throw StateError(switch (error.code) {
        'OFFICE_UNSUPPORTED' => _text(
          'This Office file type is not supported',
          '暂不支持该 Office 文件类型',
        ),
        'OFFICE_MISSING_ENTRY' => _text(
          'File missing: $detail',
          '文件缺少 $detail',
        ),
        'OFFICE_EMPTY_WORD' => _text(
          'No previewable Word text content found',
          '未找到可预览的 Word 文本内容',
        ),
        'OFFICE_EMPTY_SHEETS' => _text(
          'No previewable Excel worksheet content found',
          '未找到可预览的 Excel 工作表内容',
        ),
        'OFFICE_EMPTY_SLIDES' => _text(
          'No previewable PowerPoint slides found',
          '未找到可预览的 PowerPoint 页面',
        ),
        'OFFICE_XML_ERROR' => _text(
          'Failed to parse Office file structure: $detail',
          'Office 文件结构解析失败: $detail',
        ),
        _ => _text(
          'Office file preview failed: $detail',
          'Office 文件预览失败: $detail',
        ),
      });
    }
  }

  static OmnibotOfficePreviewSection _sheetSection(Map section, int index) {
    final title = section['title'] as String? ?? '';
    final rows = (section['tableRows'] as List)
        .map((row) => (row as List).cast<String>())
        .toList(growable: false);
    return OmnibotOfficePreviewSection(
      title: title.isEmpty
          ? _text('Sheet ${index + 1}', '工作表 ${index + 1}')
          : title,
      subtitle: rows.isEmpty
          ? _text('No cell content extracted', '未提取到单元格内容')
          : _text(
              'Extracted ${rows.length} rows in total',
              '共提取 ${rows.length} 行',
            ),
      lines: rows.isEmpty
          ? [_text('No previewable text in this worksheet', '该工作表暂无可预览文本')]
          : const [],
      tableRows: rows,
    );
  }

  static List<String> _lines(Map section) =>
      (section['lines'] as List).cast<String>();
  static String _text(String english, String chinese) =>
      LegacyTextLocalizer.isEnglish ? english : chinese;
}
