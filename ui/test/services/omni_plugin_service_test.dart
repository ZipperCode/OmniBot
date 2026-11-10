import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/omni_plugin_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('cn.com.omnimind.bot/PluginPlatform');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('lists declarative plugin actions', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'listActions');
          return <Map<String, dynamic>>[
            <String, dynamic>{
              'id': 'open_opencode_web',
              'pluginId': 'com.omnimind.agent-web',
              'displayName': 'OpenCode Web',
              'description': 'Open OpenCode Web',
              'presentation': <String, dynamic>{
                'placement': 'agent_settings',
                'placements': <String>[
                  'agent_settings',
                  'home_drawer_quick_launch',
                ],
                'packageId': 'opencode',
                'quickLaunchOrder': 1,
                'label': <String, String>{
                  'zh': 'OpenCode Web',
                  'en': 'OpenCode Web',
                },
              },
            },
          ];
        });

    final actions = await OmniPluginService.listActions();

    expect(actions, hasLength(1));
    expect(actions.single.id, 'open_opencode_web');
    expect(actions.single.pluginId, 'com.omnimind.agent-web');
    expect(actions.single.presentation['packageId'], 'opencode');
    expect(actions.single.supportsPlacement('agent_settings'), isTrue);
    expect(
      actions.single.supportsPlacement('home_drawer_quick_launch'),
      isTrue,
    );
    expect(actions.single.quickLaunchOrder, 1);
    expect(
      actions.single.localizedPresentationValue(
        'label',
        english: false,
        fallback: '',
      ),
      'OpenCode Web',
    );
  });

  test('invokes one scoped plugin action', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'invokeAction');
          expect(call.arguments, <String, Object?>{
            'pluginId': 'com.omnimind.agent-web',
            'actionId': 'open_opencode_web',
            'arguments': <String, dynamic>{'reasoning_effort': 'high'},
          });
          return <String, dynamic>{
            'success': true,
            'code': 'OPENED',
            'running': true,
          };
        });

    final result = await OmniPluginService.invokeAction(
      'com.omnimind.agent-web',
      'open_opencode_web',
      <String, dynamic>{'reasoning_effort': 'high'},
    );

    expect(result['code'], 'OPENED');
    expect(result.containsKey('url'), isFalse);
  });
}
