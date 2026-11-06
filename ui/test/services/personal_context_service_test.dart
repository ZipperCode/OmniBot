import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/services/personal_context_service.dart';
import 'package:ui/services/special_permission.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, null);
  });

  test('unknown states never become granted', () {
    for (final value in [null, true, 'true', 'unsupported_new_state']) {
      final state = PersonalContextCapability.fromMap({
        'id': 'sms',
        'status': value,
      });
      expect(state.status, PersonalContextAccessStatus.unavailable);
    }
    expect(
      PersonalContextCapability.fromMap({
        'id': 'media_visual',
        'status': 'partial',
      }).status,
      PersonalContextAccessStatus.partial,
    );
    expect(
      PersonalContextCapability.fromMap({'id': 'sms', 'status': 'restricted'})
          .status,
      PersonalContextAccessStatus.restricted,
    );
  });

  test(
    'status reads do not request permissions and refresh after revocation',
    () async {
      final calls = <MethodCall>[];
      var status = 'granted';
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            calls.add(call);
            return [
              {'id': 'calendar_read', 'status': status, 'scope': []},
            ];
          });
      expect(
        (await PersonalContextService.capabilities())['calendar_read']!.status,
        PersonalContextAccessStatus.granted,
      );
      status = 'denied';
      expect(
        (await PersonalContextService.capabilities())['calendar_read']!.status,
        PersonalContextAccessStatus.denied,
      );
      expect(
        calls.map((call) => call.method),
        everyElement('getPersonalContextCapabilities'),
      );
    },
  );

  test(
    'requests only the selected capability and preserves picker choice',
    () async {
      MethodCall? received;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(spePermission, (call) async {
            received = call;
            return null;
          });
      await PersonalContextService.request('documents', directory: true);
      expect(received!.method, 'requestPersonalContextAccess');
      expect(received!.arguments, {
        'capabilityId': 'documents',
        'directory': true,
      });
    },
  );

  test('missing native snapshot is not an empty successful status', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(spePermission, (_) async => null);
    await expectLater(PersonalContextService.capabilities(), throwsStateError);
  });
}
