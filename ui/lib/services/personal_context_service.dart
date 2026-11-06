import 'package:ui/services/special_permission.dart';

enum PersonalContextAccessStatus {
  denied,
  granted,
  partial,
  restricted,
  disconnected,
  unavailable;

  static PersonalContextAccessStatus parse(Object? value) => values.firstWhere(
    (status) => status.name == value,
    orElse: () => unavailable,
  );
}

class PersonalContextCapability {
  const PersonalContextCapability({
    required this.id,
    required this.status,
    this.scope = const [],
    this.reason,
  });

  final String id;
  final PersonalContextAccessStatus status;
  final List<String> scope;
  final String? reason;

  factory PersonalContextCapability.fromMap(Map<dynamic, dynamic> map) =>
      PersonalContextCapability(
        id: map['id'] is String ? map['id'] as String : '',
        status: PersonalContextAccessStatus.parse(map['status']),
        scope: map['scope'] is List
            ? (map['scope'] as List).whereType<String>().toList(growable: false)
            : const [],
        reason: map['reason'] is String ? map['reason'] as String : null,
      );
}

class PersonalContextService {
  static Future<Map<String, PersonalContextCapability>> capabilities() async {
    final rows = await spePermission.invokeListMethod<dynamic>(
      'getPersonalContextCapabilities',
    );
    if (rows == null) throw StateError('Personal context status unavailable');
    return {
      for (final row in rows.whereType<Map>())
        if (row['id'] is String)
          row['id'] as String: PersonalContextCapability.fromMap(row),
    };
  }

  static Future<void> request(String id, {bool directory = false}) async {
    await spePermission.invokeMethod<void>('requestPersonalContextAccess', {
      'capabilityId': id,
      'directory': directory,
    });
  }

  static Future<void> clearNotifications() async {
    await spePermission.invokeMethod<void>('clearPersonalNotificationHistory');
  }

  static Future<void> revokeDocuments() async {
    await spePermission.invokeMethod<void>('revokePersonalDocumentAccess');
  }
}
