import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/web_search_key_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/common_app_bar.dart';
import 'package:ui/widgets/settings_section_title.dart';

/// 联网搜索服务商 API Key 设置页
///
/// 通过 [WebSearchKeyService] 读写 Tavily / Exa / Firecrawl 的 API Key，
/// 密钥保存在原生 AppSecretStore，页面只展示是否已配置，不回显明文。
class WebSearchKeySettingPage extends StatefulWidget {
  const WebSearchKeySettingPage({super.key});

  @override
  State<WebSearchKeySettingPage> createState() =>
      _WebSearchKeySettingPageState();
}

class _WebSearchProvider {
  final String toolName;
  final String displayName;
  final String zhName;

  const _WebSearchProvider({
    required this.toolName,
    required this.displayName,
    required this.zhName,
  });
}

class _WebSearchKeySettingPageState extends State<WebSearchKeySettingPage> {
  static const List<_WebSearchProvider> _providers = [
    _WebSearchProvider(
      toolName: 'tavily_search',
      displayName: 'Tavily',
      zhName: 'Tavily 网页搜索',
    ),
    _WebSearchProvider(
      toolName: 'exa_search',
      displayName: 'Exa',
      zhName: 'Exa 语义搜索',
    ),
    _WebSearchProvider(
      toolName: 'firecrawl_scrape',
      displayName: 'Firecrawl',
      zhName: 'Firecrawl 网页抓取',
    ),
  ];

  final Map<String, TextEditingController> _controllers = {};
  final Map<String, bool> _configured = {};
  final Map<String, bool> _busy = {};
  final Map<String, bool> _obscured = {};

  bool _loading = true;

  bool get _english =>
      Localizations.localeOf(context).languageCode.toLowerCase() == 'en';

  String _text(String zh, String en) => _english ? en : zh;

  @override
  void initState() {
    super.initState();
    for (final provider in _providers) {
      _controllers[provider.toolName] = TextEditingController();
      _configured[provider.toolName] = false;
      _busy[provider.toolName] = false;
      _obscured[provider.toolName] = true;
    }
    _loadStatuses();
  }

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _loadStatuses() async {
    final results = await Future.wait(
      _providers.map((p) => WebSearchKeyService.hasApiKey(p.toolName)),
    );
    if (!mounted) return;
    setState(() {
      for (var i = 0; i < _providers.length; i++) {
        _configured[_providers[i].toolName] = results[i];
      }
      _loading = false;
    });
  }

  Future<void> _refreshStatus(String toolName) async {
    final configured = await WebSearchKeyService.hasApiKey(toolName);
    if (!mounted) return;
    setState(() => _configured[toolName] = configured);
  }

  Future<void> _save(_WebSearchProvider provider) async {
    if (_busy[provider.toolName] == true) return;
    final apiKey = _controllers[provider.toolName]!.text.trim();
    if (apiKey.isEmpty) {
      showToast(
        _text('请先输入 API Key。', 'Enter the API key first.'),
        type: ToastType.warning,
      );
      return;
    }
    setState(() => _busy[provider.toolName] = true);
    try {
      final ok = await WebSearchKeyService.putApiKey(
        provider.toolName,
        apiKey,
      );
      if (!mounted) return;
      if (ok) {
        _controllers[provider.toolName]!.clear();
        await _refreshStatus(provider.toolName);
        showToast(
          _text(
            '${provider.displayName} API Key 已保存。',
            '${provider.displayName} API key saved.',
          ),
          type: ToastType.success,
        );
      } else {
        showToast(
          _text('保存失败，请重试。', 'Save failed. Please try again.'),
          type: ToastType.error,
        );
      }
    } finally {
      if (mounted) setState(() => _busy[provider.toolName] = false);
    }
  }

  Future<void> _clear(_WebSearchProvider provider) async {
    if (_busy[provider.toolName] == true) return;
    setState(() => _busy[provider.toolName] = true);
    try {
      final ok = await WebSearchKeyService.deleteApiKey(provider.toolName);
      if (!mounted) return;
      if (ok) {
        _controllers[provider.toolName]!.clear();
        await _refreshStatus(provider.toolName);
        showToast(
          _text(
            '${provider.displayName} API Key 已清除。',
            '${provider.displayName} API key cleared.',
          ),
          type: ToastType.success,
        );
      } else {
        showToast(
          _text('清除失败，请重试。', 'Clear failed. Please try again.'),
          type: ToastType.error,
        );
      }
    } finally {
      if (mounted) setState(() => _busy[provider.toolName] = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: CommonAppBar(
        title: _text('联网搜索', 'Web Search'),
        primary: true,
      ),
      body: SafeArea(
        top: false,
        bottom: false,
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: edgeToEdgeScrollPadding(
                  context,
                  const EdgeInsets.fromLTRB(18, 12, 18, 28),
                ),
                children: [
                  SettingsSectionTitle(
                    label: _text('搜索服务商 API Key', 'Search provider API keys'),
                    subtitle: _text(
                      '为 Tavily / Exa / Firecrawl 配置 API Key 后，Agent 的联网搜索与网页抓取工具即可使用。密钥保存在系统加密存储中，不会回显明文。',
                      'Configure API keys for Tavily / Exa / Firecrawl to enable the Agent web search and scraping tools. Keys are stored in encrypted storage and never displayed back.',
                    ),
                  ),
                  ..._providers.map(_buildProviderCard),
                ],
              ),
      ),
    );
  }

  Widget _buildProviderCard(_WebSearchProvider provider) {
    final palette = context.omniPalette;
    final dark = context.isDarkTheme;
    final card = dark ? palette.surfacePrimary : Colors.white;
    final configured = _configured[provider.toolName] ?? false;
    final busy = _busy[provider.toolName] ?? false;
    final obscured = _obscured[provider.toolName] ?? true;
    final controller = _controllers[provider.toolName]!;

    return Padding(
      padding: const EdgeInsets.only(top: 12),
      child: Container(
        key: Key('web-search-key-${provider.toolName}-card'),
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: card,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  LucideIcons.globe,
                  size: 16,
                  color: palette.textSecondary,
                ),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    _text(provider.zhName, provider.displayName),
                    style: TextStyle(
                      color: palette.textPrimary,
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                Text(
                  configured
                      ? _text('已配置', 'Configured')
                      : _text('未配置', 'Not configured'),
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w500,
                    color: configured
                        ? const Color(0xFF16A34A)
                        : palette.textTertiary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            TextField(
              key: Key('web-search-key-${provider.toolName}-field'),
              controller: controller,
              obscureText: obscured,
              autofillHints: const [AutofillHints.password],
              style: TextStyle(color: palette.textPrimary, fontSize: 13),
              decoration: InputDecoration(
                labelText: '${provider.displayName} API Key',
                hintText: configured
                    ? _text(
                        '输入新 Key 以替换已保存的密钥',
                        'Enter a new key to replace the saved one',
                      )
                    : 'tvly-...',
                filled: true,
                fillColor: dark
                    ? palette.surfaceSecondary.withValues(alpha: 0.72)
                    : const Color(0xFFF8FAFC),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: BorderSide.none,
                ),
                suffixIcon: IconButton(
                  tooltip: obscured
                      ? _text('显示 Key', 'Show key')
                      : _text('隐藏 Key', 'Hide key'),
                  onPressed: () => setState(
                    () => _obscured[provider.toolName] = !obscured,
                  ),
                  icon: Icon(
                    obscured ? LucideIcons.eye : LucideIcons.eyeOff,
                    size: 18,
                    color: palette.textSecondary,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton(
                  key: Key('web-search-key-${provider.toolName}-save'),
                  onPressed: busy ? null : () => _save(provider),
                  child: Text(_text('保存', 'Save')),
                ),
                OutlinedButton(
                  key: Key('web-search-key-${provider.toolName}-clear'),
                  onPressed: busy || !configured
                      ? null
                      : () => _clear(provider),
                  child: Text(_text('清除', 'Clear')),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
