import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/model_provider_setting/chatgpt_account_section.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
  final calls = <MethodCall>[];
  final events = <String>[];
  var account = <String, dynamic>{};
  var status = 'pending';
  Future<dynamic> Function(MethodCall)? extraHandler;

  setUp(() {
    calls.clear();
    events.clear();
    account = {'connected': false, 'planEnabled': false, 'needsReauth': false};
    status = 'pending';
    extraHandler = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          if (extraHandler != null) {
            final handled = await extraHandler!(call);
            if (handled != null) return handled;
          }
          switch (call.method) {
            case 'getChatGptAccount':
              return account;
            case 'startChatGptLogin':
              events.add('start');
              return {
                'attemptId': 'attempt-1',
                'authUrl':
                    'https://auth.openai.com/oauth/authorize?state=sample',
              };
            case 'getChatGptLoginStatus':
              return {'status': status};
            case 'logoutChatGpt':
              account = {'connected': false};
              return {'remoteRevocationConfirmed': true};
            case 'cancelChatGptLogin':
              return null;
          }
          throw PlatformException(code: 'unexpected_method');
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  Widget page({
    String profileId = 'provider-1',
    bool launchSucceeds = true,
    bool saveSucceeds = true,
    bool enabled = true,
    Object? launchError,
    Object? saveError,
    Object? refreshError,
  }) => MaterialApp(
    locale: const Locale('en'),
    home: Scaffold(
      body: ChatGptAccountSection(
        profileId: profileId,
        enabled: enabled,
        beforeLogin: () async {
          events.add('save');
          if (saveError != null) throw saveError;
          return saveSucceeds;
        },
        onAccountChanged: (id) async {
          events.add('changed:$id');
          if (refreshError != null) throw refreshError;
        },
        externalLauncher: (url) async {
          events.add('launch:${url.host}');
          if (launchError != null) throw launchError;
          return launchSucceeds;
        },
      ),
    ),
  );

  Future<void> start(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('chatgpt-login-button')));
    await tester.pump();
  }

  testWidgets('persists first and refreshes only the authenticated profile', (
    tester,
  ) async {
    await tester.pumpWidget(page());
    await tester.pump();
    await start(tester);
    expect(events, ['save', 'start', 'launch:auth.openai.com']);
    expect(
      calls.firstWhere((call) => call.method == 'startChatGptLogin').arguments,
      {'profileId': 'provider-1'},
    );
    expect(find.text('Complete sign-in in your browser.'), findsOneWidget);

    status = 'completed';
    account = {
      'connected': true,
      'email': 'sample@example.com',
      'planEnabled': true,
    };
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(events.last, 'changed:provider-1');
    expect(find.text('sample@example.com'), findsOneWidget);
    expect(find.text('ChatGPT plan access enabled.'), findsOneWidget);
    expect(find.byKey(const Key('chatgpt-logout-button')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    expect(calls.where((call) => call.method == 'cancelChatGptLogin'), isEmpty);
  });

  testWidgets('failed profile persistence never starts authorization', (
    tester,
  ) async {
    await tester.pumpWidget(page(saveSucceeds: false));
    await tester.pump();
    await start(tester);
    expect(events, ['save']);
    expect(find.text('save_profile / profile_save_failed'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsNothing);
  });

  testWidgets('failed browser launch cancels the native attempt', (
    tester,
  ) async {
    await tester.pumpWidget(page(launchSucceeds: false));
    await tester.pump();
    await start(tester);
    expect(calls.last.method, 'cancelChatGptLogin');
    expect(calls.last.arguments, {'attemptId': 'attempt-1'});
    expect(find.byKey(const Key('chatgpt-login-error')), findsOneWidget);
    expect(find.text('open_browser / browser_unavailable'), findsOneWidget);
  });

  testWidgets(
    'disposal cancels an attempt that arrives after the page closes',
    (tester) async {
      final startResponse = Completer<Map<String, dynamic>>();
      extraHandler = (call) async =>
          call.method == 'startChatGptLogin' ? startResponse.future : null;
      await tester.pumpWidget(page());
      await tester.pump();
      await start(tester);
      await tester.pumpWidget(const SizedBox());
      startResponse.complete({
        'attemptId': 'late-attempt',
        'authUrl': 'https://auth.openai.com/oauth/authorize',
      });
      await tester.pump();
      expect(calls.last.method, 'cancelChatGptLogin');
      expect(calls.last.arguments, {'attemptId': 'late-attempt'});
      expect(events, ['save']);
    },
  );

  testWidgets('switching profiles cancels and ignores old completion', (
    tester,
  ) async {
    final pollResponse = Completer<Map<String, dynamic>>();
    extraHandler = (call) async =>
        call.method == 'getChatGptLoginStatus' ? pollResponse.future : null;
    await tester.pumpWidget(page());
    await tester.pump();
    await start(tester);
    await tester.pumpWidget(page(profileId: 'provider-2'));
    await tester.pump();
    pollResponse.complete({'status': 'completed'});
    await tester.pump();
    expect(
      calls
          .where((call) => call.method == 'cancelChatGptLogin')
          .single
          .arguments,
      {'attemptId': 'attempt-1'},
    );
    expect(events.where((event) => event.startsWith('changed:')), isEmpty);
    expect(calls.last.arguments, {'profileId': 'provider-2'});
  });

  testWidgets('pending sign-in is bounded and offers retry', (tester) async {
    await tester.pumpWidget(page());
    await tester.pump();
    await start(tester);
    await tester.pump(const Duration(minutes: 5));
    await tester.pump();
    expect(find.text('Sign-in timed out. Try again.'), findsOneWidget);
    expect(
      calls.where((call) => call.method == 'cancelChatGptLogin').length,
      1,
    );
    expect(
      tester
          .widget<FilledButton>(find.byKey(const Key('chatgpt-login-button')))
          .onPressed,
      isNotNull,
    );
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('missing plan consent can re-authorize and sign out', (
    tester,
  ) async {
    account = {
      'connected': true,
      'email': 'sample@example.com',
      'planEnabled': false,
    };
    await tester.pumpWidget(page());
    await tester.pump();
    expect(find.textContaining('plan access is not enabled'), findsOneWidget);
    expect(find.text('Re-authorize'), findsOneWidget);
    await tester.tap(find.byKey(const Key('chatgpt-logout-button')));
    await tester.pump();
    expect(
      calls.firstWhere((call) => call.method == 'logoutChatGpt').arguments,
      {'profileId': 'provider-1'},
    );
    expect(events, ['changed:provider-1']);
    expect(find.text('Continue with ChatGPT'), findsOneWidget);
    expect(find.text('sample@example.com'), findsNothing);
  });

  testWidgets('unconfirmed remote revocation explains local sign-out', (
    tester,
  ) async {
    account = {
      'connected': true,
      'email': 'sample@example.com',
      'planEnabled': true,
    };
    extraHandler = (call) async {
      if (call.method != 'logoutChatGpt') return null;
      account = {
        'connected': false,
        'needsReauth': true,
        'email': 'sample@example.com',
      };
      return {'remoteRevocationConfirmed': false};
    };
    await tester.pumpWidget(page());
    await tester.pump();
    await tester.tap(find.byKey(const Key('chatgpt-logout-button')));
    await tester.pump();
    expect(
      find.textContaining('Remote revocation could not be confirmed'),
      findsOneWidget,
    );
    expect(
      find.text('Sign in again to reconnect this account.'),
      findsOneWidget,
    );
    expect(find.byKey(const Key('chatgpt-logout-button')), findsNothing);
    expect(events, ['changed:provider-1']);
  });

  testWidgets('connected accounts can open ChatGPT usage externally', (
    tester,
  ) async {
    account = {
      'connected': true,
      'email': 'sample@example.com',
      'planEnabled': true,
    };
    await tester.pumpWidget(page());
    await tester.pump();
    await tester.tap(find.byKey(const Key('chatgpt-manage-usage-button')));
    await tester.pump();
    expect(events, ['launch:chatgpt.com']);
  });

  testWidgets(
    'disabling during a profile switch cancels pending authorization',
    (tester) async {
      await tester.pumpWidget(page());
      await tester.pump();
      await start(tester);
      await tester.pumpWidget(page(enabled: false));
      await tester.pump();
      expect(calls.last.method, 'cancelChatGptLogin');
      expect(find.byType(LinearProgressIndicator), findsNothing);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets(
    'known local storage failure stays visible and prevents login until retry succeeds',
    (tester) async {
      account = {
        'connected': false,
        'secureStorageAvailable': false,
        'errorCode': 'secure_storage_invalid_format',
      };
      await tester.pumpWidget(page());
      await tester.pump();
      expect(
        find.text('read_account / secure_storage_invalid_format'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Local secure credential storage'),
        findsOneWidget,
      );
      expect(find.textContaining('expired'), findsNothing);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('chatgpt-login-button')))
            .onPressed,
        isNull,
      );
      expect(events, isEmpty);
      account = {'connected': false, 'secureStorageAvailable': true};
      await tester.tap(find.byKey(const Key('chatgpt-retry-account-button')));
      await tester.pump();
      expect(find.byKey(const Key('chatgpt-login-error')), findsNothing);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('chatgpt-login-button')))
            .onPressed,
        isNotNull,
      );
      expect(calls.every((call) => call.method == 'getChatGptAccount'), isTrue);
    },
  );

  testWidgets(
    'native startup storage failure stays local and never launches browser',
    (tester) async {
      extraHandler = (call) async {
        if (call.method == 'startChatGptLogin') {
          throw PlatformException(
            code: 'CHATGPT_AUTH_ERROR',
            message: 'https://private/?code=secret',
            details: {
              'errorCode': 'secure_storage_unavailable',
              'phase': 'start_login',
            },
          );
        }
        return null;
      };
      await tester.pumpWidget(page());
      await tester.pump();
      await start(tester);
      expect(
        find.text('start_login / secure_storage_unavailable'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Local secure credential storage'),
        findsOneWidget,
      );
      expect(find.textContaining('private'), findsNothing);
      expect(events, ['save']);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('chatgpt-login-button')))
            .onPressed,
        isNull,
      );
    },
  );

  testWidgets(
    'browser exceptions are not reported as native startup failures',
    (tester) async {
      await tester.pumpWidget(
        page(
          launchError: PlatformException(
            code: 'channel-error',
            message: 'private-url',
          ),
        ),
      );
      await tester.pump();
      await start(tester);
      expect(find.text('open_browser / browser_unavailable'), findsOneWidget);
      expect(find.textContaining('system browser'), findsOneWidget);
      expect(find.textContaining('private-url'), findsNothing);
      expect(calls.last.method, 'cancelChatGptLogin');
    },
  );

  for (final failure in {
    'network_error': 'Check your network',
    'authorization_declined': 'Authorization was not approved',
    'invalid_identity': 'identity could not be verified',
    'secure_storage_invalid_format': 'Local secure credential storage',
  }.entries) {
    testWidgets(
      'callback ${failure.key} retains its safe diagnostic category',
      (tester) async {
        extraHandler = (call) async => call.method == 'getChatGptLoginStatus'
            ? {
                'status': 'failed',
                'errorCode': failure.key,
                'error': 'private-url?code=secret-token',
              }
            : null;
        await tester.pumpWidget(page());
        await tester.pump();
        await start(tester);
        expect(find.text('login_status / ${failure.key}'), findsOneWidget);
        expect(find.textContaining(failure.value), findsOneWidget);
        expect(find.textContaining('secret-token'), findsNothing);
        expect(find.textContaining('private-url'), findsNothing);
        expect(find.byType(LinearProgressIndicator), findsNothing);
      },
    );
  }

  testWidgets(
    'invalid authorization response identifies app boundary without showing link',
    (tester) async {
      extraHandler = (call) async => call.method == 'startChatGptLogin'
          ? {
              'attemptId': 'invalid-attempt',
              'authUrl': 'https://untrusted.example/?code=private',
            }
          : null;
      await tester.pumpWidget(page());
      await tester.pump();
      await start(tester);
      expect(
        find.text('authorization_response / invalid_authorization_response'),
        findsOneWidget,
      );
      expect(find.textContaining('browser was not opened'), findsOneWidget);
      expect(find.textContaining('untrusted.example'), findsNothing);
      expect(events, ['save']);
      expect(calls.last.method, 'cancelChatGptLogin');
    },
  );

  testWidgets(
    'provider refresh failure does not claim successful authorization failed',
    (tester) async {
      status = 'completed';
      account = {'connected': true, 'planEnabled': true};
      await tester.pumpWidget(
        page(refreshError: StateError('private exception')),
      );
      await tester.pump();
      await start(tester);
      expect(
        find.text('refresh_provider / profile_refresh_failed'),
        findsOneWidget,
      );
      expect(
        find.textContaining('account operation completed'),
        findsOneWidget,
      );
      expect(find.textContaining('private exception'), findsNothing);
      expect(find.byKey(const Key('chatgpt-logout-button')), findsOneWidget);
    },
  );
}
