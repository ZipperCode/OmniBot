import 'package:flutter/services.dart';
import 'package:ui/services/assists_core_service.dart';

/// Only allowlisted codes cross into diagnostics; never retain native messages,
/// callback URLs, authorization codes, or exception details.
class ChatGptOAuthFailure implements Exception {
  const ChatGptOAuthFailure({required this.code, required this.phase});

  final String code;
  final String phase;

  static const _nativeCodes = {
    'secure_storage_unavailable',
    'secure_storage_invalid_format',
    'callback_unavailable',
    'callback_failed',
    'invalid_profile',
    'invalid_attempt',
    'not_initialized',
    'too_many_attempts',
    'login_cancelled',
    'login_timeout',
    'authorization_declined',
    'account_mismatch',
    'registration_incomplete',
    'missing_code',
    'invalid_state',
    'invalid_identity',
    'invalid_client',
    'invalid_response',
    'network_error',
    'oauth_request_failed',
    'login_failed',
    'sign_in_required',
    'plan_disabled',
    'refresh_not_ready',
    'invalid_grant',
    'invalid_refresh_token',
    'token_expired',
    'refresh_token_expired',
    'refresh_token_invalidated',
    'refresh_token_reused',
    'native_error',
    'internal_error',
  };
  static const _nativePhases = {
    'start_login',
    'read_account',
    'logout',
    'login_status',
    'cancel_login',
  };

  factory ChatGptOAuthFailure.fromNativeCode(Object? code, String phase) =>
      ChatGptOAuthFailure(
        code: _nativeCodes.contains(code) ? code as String : 'native_error',
        phase: phase,
      );

  factory ChatGptOAuthFailure.fromError(
    Object error, {
    required String phase,
    String fallbackCode = 'native_error',
  }) {
    if (error is ChatGptOAuthFailure) return error;
    if (error is MissingPluginException) {
      return ChatGptOAuthFailure(
        code: phase == 'open_browser'
            ? 'browser_unavailable'
            : 'native_unavailable',
        phase: phase,
      );
    }
    if (error is PlatformException && error.code == 'CHATGPT_AUTH_ERROR') {
      final details = error.details;
      final nativeCode = details is Map ? details['errorCode'] : null;
      final nativePhase = details is Map ? details['phase'] : null;
      return ChatGptOAuthFailure.fromNativeCode(
        nativeCode,
        _nativePhases.contains(nativePhase) ? nativePhase as String : phase,
      );
    }
    return ChatGptOAuthFailure(code: fallbackCode, phase: phase);
  }

  bool get isStorageFailure =>
      code == 'secure_storage_unavailable' ||
      code == 'secure_storage_invalid_format';

  String get diagnostic => '$phase / $code';

  String message({required bool english}) {
    String text(String zh, String en) => english ? en : zh;
    return switch (code) {
      'secure_storage_unavailable' || 'secure_storage_invalid_format' => text(
        '本机安全凭据存储不可用，请更新并重启应用后重试。',
        'Local secure credential storage is unavailable. Update and restart the app, then try again.',
      ),
      'callback_unavailable' || 'callback_failed' => text(
        '本机登录回调无法启动或已中断，请重新登录。',
        'The local sign-in callback could not start or was interrupted. Try signing in again.',
      ),
      'invalid_profile' => text(
        '提供商配置已更改，请重新选择 ChatGPT 提供商。',
        'The provider configuration changed. Select the ChatGPT provider again.',
      ),
      'not_initialized' || 'native_unavailable' => text(
        '应用内登录组件尚未就绪，请更新并重启应用。',
        'The app sign-in component is unavailable. Update and restart the app.',
      ),
      'invalid_authorization_response' => text(
        '应用生成的登录链接无效，未打开浏览器。请更新应用后重试。',
        'The app returned an invalid sign-in link; the browser was not opened. Update the app and retry.',
      ),
      'browser_unavailable' => text(
        '无法打开系统浏览器，请设置默认浏览器后重试。',
        'Could not open the system browser. Set a default browser and try again.',
      ),
      'network_error' => text(
        '无法连接 OpenAI 登录服务，请检查网络后重试。',
        'Could not connect to OpenAI sign-in. Check your network and try again.',
      ),
      'authorization_declined' => text(
        '尚未完成授权，请在浏览器中允许登录授权。',
        'Authorization was not approved. Complete and allow sign-in in the browser.',
      ),
      'plan_disabled' => text(
        '尚未授权使用 ChatGPT 套餐，请重新授权。',
        'ChatGPT plan access is not enabled. Re-authorize to allow plan access.',
      ),
      'account_mismatch' => text(
        '登录返回的账号与此配置不匹配，请为其他账号新增提供商。',
        'The returned account does not match this configuration. Add a provider for another account.',
      ),
      'invalid_identity' ||
      'invalid_state' ||
      'missing_code' ||
      'registration_incomplete' => text(
        'OpenAI 登录回调或身份校验未通过，请重新登录。',
        'The OpenAI sign-in callback or identity could not be verified. Sign in again.',
      ),
      'invalid_client' => text(
        'OpenAI 未接受此应用的登录注册，请更新应用后重试。',
        'OpenAI did not accept the app registration. Update the app and try again.',
      ),
      'sign_in_required' ||
      'invalid_grant' ||
      'invalid_refresh_token' ||
      'token_expired' ||
      'refresh_token_expired' ||
      'refresh_token_invalidated' ||
      'refresh_token_reused' => text(
        'ChatGPT 授权已失效或被撤销，请重新登录。',
        'ChatGPT authorization expired or was revoked. Sign in again.',
      ),
      'login_timeout' => text('登录已超时，请重试。', 'Sign-in timed out. Try again.'),
      'login_cancelled' || 'invalid_attempt' => text(
        '登录已取消，请重试。',
        'Sign-in was cancelled. Try again.',
      ),
      'refresh_not_ready' => text(
        'ChatGPT 授权暂时无法刷新，请稍后重试。',
        'ChatGPT authorization cannot be refreshed yet. Try again later.',
      ),
      'profile_save_failed' => text(
        '提供商配置尚未保存，登录未开始，请稍后重试。',
        'The provider configuration was not saved. Sign-in has not started; try again shortly.',
      ),
      'profile_refresh_failed' => text(
        '账号操作已完成，但未能刷新提供商信息，请重新打开此页面。',
        'The account operation completed, but provider information could not refresh. Reopen this page.',
      ),
      'too_many_attempts' => text(
        '当前登录尝试过多，请稍后重试。',
        'Too many sign-in attempts are active. Try again shortly.',
      ),
      'invalid_response' || 'oauth_request_failed' => text(
        'OpenAI 登录服务未能完成请求，请重试。',
        'OpenAI sign-in could not complete the request. Try again.',
      ),
      _ => switch (phase) {
        'read_account' => text(
          '无法读取本机 ChatGPT 账号状态，请重试。',
          'Could not read the local ChatGPT account state. Try again.',
        ),
        'logout' => text(
          '无法完成退出登录，请重试。',
          'Could not complete sign-out. Try again.',
        ),
        'login_status' => text(
          '无法确认 ChatGPT 登录结果，请重试。',
          'Could not confirm the ChatGPT sign-in result. Try again.',
        ),
        _ => text(
          '应用未能准备 ChatGPT 登录，请更新并重启应用后重试。',
          'The app could not prepare ChatGPT sign-in. Update and restart the app, then retry.',
        ),
      },
    };
  }

  @override
  String toString() => 'ChatGptOAuthFailure($diagnostic)';
}

/// Public account information only. OAuth credentials remain in native storage.
class ChatGptAccount {
  const ChatGptAccount({
    required this.connected,
    required this.email,
    required this.planEnabled,
    required this.needsReauth,
    this.secureStorageAvailable = true,
    this.errorCode,
  });

  final bool connected;
  final String email;
  final bool planEnabled;
  final bool needsReauth;
  final bool secureStorageAvailable;
  final String? errorCode;

  factory ChatGptAccount.fromMap(Map<dynamic, dynamic>? map) => ChatGptAccount(
    connected: map?['connected'] == true,
    email: (map?['email'] ?? '').toString(),
    planEnabled: map?['planEnabled'] == true,
    needsReauth: map?['needsReauth'] == true,
    secureStorageAvailable: map?['secureStorageAvailable'] != false,
    errorCode: map?['errorCode'] == null
        ? null
        : ChatGptOAuthFailure.fromNativeCode(
            map?['errorCode'],
            'read_account',
          ).code,
  );
}

class ChatGptLoginAttempt {
  const ChatGptLoginAttempt({required this.id, required this.authorizationUrl});

  final String id;
  final Uri authorizationUrl;
}

class ChatGptLoginStatus {
  const ChatGptLoginStatus({required this.status, this.error, this.errorCode});

  final String status;
  final String? error;
  final String? errorCode;
}

class ChatGptOAuthService {
  static const sourceType = 'chatgpt_oauth';
  static const baseUrl = 'https://api.openai.com/v1';

  static Future<Map<String, dynamic>?> _invokeMap(
    String method,
    Map<String, dynamic> arguments,
    String phase,
  ) async {
    try {
      return await AssistsMessageService.assistCore
          .invokeMapMethod<String, dynamic>(method, arguments);
    } catch (error) {
      throw ChatGptOAuthFailure.fromError(error, phase: phase);
    }
  }

  static Future<ChatGptLoginAttempt> startLogin(String profileId) async {
    final result = await _invokeMap('startChatGptLogin', {
      'profileId': profileId,
    }, 'start_login');
    final id = result?['attemptId'] is String
        ? result!['attemptId'] as String
        : '';
    final rawUrl = result?['authUrl'] is String
        ? result!['authUrl'] as String
        : '';
    final url = Uri.tryParse(rawUrl);
    if (id.isEmpty ||
        url == null ||
        url.scheme != 'https' ||
        url.host != 'auth.openai.com' ||
        url.userInfo.isNotEmpty ||
        url.port != 443 ||
        url.hasFragment) {
      if (id.isNotEmpty) {
        // Cancellation failure must not hide the original response-validation
        // phase. The native attempt also owns a bounded timeout.
        try {
          await cancelLogin(id);
        } catch (_) {}
      }
      throw const ChatGptOAuthFailure(
        code: 'invalid_authorization_response',
        phase: 'authorization_response',
      );
    }
    return ChatGptLoginAttempt(id: id, authorizationUrl: url);
  }

  static Future<ChatGptLoginStatus> getLoginStatus(String attemptId) async {
    final result = await _invokeMap('getChatGptLoginStatus', {
      'attemptId': attemptId,
    }, 'login_status');
    return ChatGptLoginStatus(
      status: result?['status']?.toString() ?? 'failed',
      error: result?['error']?.toString(),
      errorCode: result?['errorCode']?.toString(),
    );
  }

  static Future<void> cancelLogin(String attemptId) => AssistsMessageService
      .assistCore
      .invokeMethod<void>('cancelChatGptLogin', {'attemptId': attemptId});

  static Future<ChatGptAccount> getAccount(String profileId) async =>
      ChatGptAccount.fromMap(
        await _invokeMap('getChatGptAccount', {
          'profileId': profileId,
        }, 'read_account'),
      );

  static Future<bool> logout(String profileId) async {
    final result = await _invokeMap('logoutChatGpt', {
      'profileId': profileId,
    }, 'logout');
    return result?['remoteRevocationConfirmed'] == true;
  }
}
