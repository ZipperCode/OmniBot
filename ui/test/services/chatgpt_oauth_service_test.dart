import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/chatgpt_oauth_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  test(
    'rejects unexpected authorization destinations and cancels the attempt',
    () async {
      final calls = <MethodCall>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return call.method == 'startChatGptLogin'
            ? {
                'attemptId': 'attempt-1',
                'authUrl': 'https://untrusted.example/authorize',
              }
            : null;
      });
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      await expectLater(
        ChatGptOAuthService.startLogin('provider-1'),
        throwsA(
          isA<ChatGptOAuthFailure>()
              .having(
                (error) => error.code,
                'code',
                'invalid_authorization_response',
              )
              .having(
                (error) => error.phase,
                'phase',
                'authorization_response',
              ),
        ),
      );
      expect(calls.last.method, 'cancelChatGptLogin');
      expect(calls.last.arguments, {'attemptId': 'attempt-1'});
    },
  );

  test(
    'account payload requires explicit plan consent and connection state',
    () {
      final account = ChatGptAccount.fromMap({'email': 'sample@example.com'});
      expect(account.connected, isFalse);
      expect(account.planEnabled, isFalse);
      expect(account.needsReauth, isFalse);
      expect(account.email, 'sample@example.com');
    },
  );

  test(
    'accepts the exact native authorize endpoint with PKCE query values',
    () async {
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(
        channel,
        (call) async => {
          'attemptId': 'attempt-1',
          'authUrl': 'https://auth.openai.com/api/accounts/authorize?client_id=dynamic_agent_client&redirect_uri=http%3A%2F%2F127.0.0.1%3A42001%2Fauth%2Fcallback&code_challenge=example&state=sample',
        },
      );
      addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
      final attempt = await ChatGptOAuthService.startLogin('provider-1');
      expect(attempt.authorizationUrl.path, '/api/accounts/authorize');
      expect(
        attempt.authorizationUrl.queryParameters['redirect_uri'],
        'http://127.0.0.1:42001/auth/callback',
      );
    },
  );

  test('allowlists native diagnostics and discards raw exception details', () {
    final known = ChatGptOAuthFailure.fromError(
      PlatformException(
        code: 'CHATGPT_AUTH_ERROR',
        message: 'secret-token https://auth.openai.com/?code=private',
        details: {
          'errorCode': 'secure_storage_invalid_format',
          'phase': 'start_login',
          'authUrl': 'private-url',
        },
      ),
      phase: 'read_account',
    );
    expect(known.diagnostic, 'start_login / secure_storage_invalid_format');
    expect(
      known.message(english: true),
      contains('Local secure credential storage'),
    );
    expect(known.toString(), isNot(contains('secret-token')));
    expect(known.toString(), isNot(contains('private-url')));

    final unknown = ChatGptOAuthFailure.fromError(
      PlatformException(
        code: 'CHATGPT_AUTH_ERROR',
        message: 'secret-token',
        details: {'errorCode': 'secret-code', 'phase': 'secret-phase'},
      ),
      phase: 'start_login',
    );
    expect(unknown.diagnostic, 'start_login / native_error');
  });

  test(
    'missing native plugin and browser plugin have separate diagnostics',
    () {
      expect(
        ChatGptOAuthFailure.fromError(
          MissingPluginException('private-url'),
          phase: 'start_login',
        ).code,
        'native_unavailable',
      );
      expect(
        ChatGptOAuthFailure.fromError(
          MissingPluginException('private-url'),
          phase: 'open_browser',
        ).code,
        'browser_unavailable',
      );
    },
  );

  test('account format error remains a local storage failure', () {
    final account = ChatGptAccount.fromMap({
      'secureStorageAvailable': false,
      'errorCode': 'secure_storage_invalid_format',
    });
    final error = ChatGptOAuthFailure.fromNativeCode(
      account.errorCode,
      'read_account',
    );
    expect(account.secureStorageAvailable, isFalse);
    expect(error.isStorageFailure, isTrue);
    expect(error.message(english: true), isNot(contains('expired')));
  });
}
