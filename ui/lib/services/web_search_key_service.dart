import 'package:flutter/services.dart';

/// 联网搜索工具 API Key 配置服务
///
/// 通过原生通道 `web_search_key` 读写 Tavily / Exa / Firecrawl 的 API Key。
/// 密钥保存在原生 AppSecretStore（Keystore 加密），Flutter 侧不持有明文：
/// 只能查询是否已配置、写入与删除。
class WebSearchKeyService {
  static const MethodChannel _channel = MethodChannel('web_search_key');

  /// 与原生 WebSearchToolDefinitions.toolNames 保持一致
  static const List<String> toolNames = [
    'tavily_search',
    'exa_search',
    'firecrawl_scrape',
  ];

  /// 查询某个联网搜索工具是否已配置 API Key
  static Future<bool> hasApiKey(String toolName) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'hasApiKey',
        {'toolName': toolName},
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 保存 API Key（空字符串在原生侧等价于删除）
  static Future<bool> putApiKey(String toolName, String apiKey) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'putApiKey',
        {'toolName': toolName, 'apiKey': apiKey},
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 删除 API Key
  static Future<bool> deleteApiKey(String toolName) async {
    try {
      final result = await _channel.invokeMethod<bool>(
        'deleteApiKey',
        {'toolName': toolName},
      );
      return result ?? false;
    } catch (_) {
      return false;
    }
  }
}
