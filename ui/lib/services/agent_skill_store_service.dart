import 'package:flutter/services.dart';
import 'package:ui/models/agent_skill_item.dart';
import 'package:ui/services/assists_core_service.dart';

class SkillAssetException implements Exception {
  final String code;
  final String message;
  final Map<String, dynamic>? details;

  const SkillAssetException({
    required this.code,
    required this.message,
    this.details,
  });

  bool get isRevisionConflict => code == 'skill_revision_conflict';
  bool get isNameConflict => code == 'skill_name_conflict';
  bool get isCannotOverwriteBuiltin => code == 'skill_cannot_overwrite_builtin';
  bool get isContentConflict => code == 'skill_content_conflict';
  bool get isContentModified => code == 'skill_content_modified';
  bool get isFeedbackIneligible => code == 'skill_feedback_ineligible';
  bool get isRevisionRetired => code == 'skill_revision_retired';
  bool get isValidationFailed => code == 'skill_validation_failed';

  factory SkillAssetException.fromPlatformException(PlatformException e) {
    Map<String, dynamic>? details;
    if (e.details is Map) {
      details = Map<String, dynamic>.from(e.details as Map);
    }
    return SkillAssetException(
      code: e.code,
      message: e.message ?? e.toString(),
      details: details,
    );
  }

  @override
  String toString() => 'SkillAssetException($code): $message';
}

class AgentSkillSuggestionItem {
  final String candidateId;
  final int revision;
  final int? conversationId;
  final String? sessionId;
  final String? turnId;
  final String state; // queued|generating|pending_review|approved|rejected|generation_failed|interrupted|skipped
  final String? skipReason; // ineligible|busy|sensitive|too_large|provider_changed|no_suggestion
  final String name;
  final String description;
  final String bodyMarkdown;
  final String? targetSkillId;
  final String? failureReason;
  final int? createdAt;
  final int? updatedAt;
  final List<String> dataCategories;

  const AgentSkillSuggestionItem({
    required this.candidateId,
    required this.revision,
    this.conversationId,
    this.sessionId,
    this.turnId,
    required this.state,
    this.skipReason,
    required this.name,
    required this.description,
    required this.bodyMarkdown,
    this.targetSkillId,
    this.failureReason,
    this.createdAt,
    this.updatedAt,
    this.dataCategories = const [],
  });

  bool get isQueued => state == 'queued';
  bool get isGenerating => state == 'generating';
  bool get isPendingReview => state == 'pending_review';
  bool get isApproved => state == 'approved';
  bool get isRejected => state == 'rejected';
  bool get isGenerationFailed => state == 'generation_failed';
  bool get isInterrupted => state == 'interrupted';
  bool get isSkipped => state == 'skipped';

  bool get canReview => isPendingReview;
  bool get canRegenerate =>
      isGenerationFailed ||
      isInterrupted ||
      (isSkipped && skipReason != 'sensitive');

  factory AgentSkillSuggestionItem.fromMap(Map<String, dynamic> raw) {
    final rawCategories = raw['dataCategories'];
    final dataCategories = rawCategories is List
        ? rawCategories.map((e) => e.toString()).toList()
        : const <String>[];
    return AgentSkillSuggestionItem(
      candidateId: (raw['candidateId'] ?? raw['id'] ?? '').toString(),
      revision: _parseInt(raw['revision']),
      conversationId: _parseNullableInt(raw['conversationId']),
      sessionId: raw['sessionId']?.toString(),
      turnId: raw['turnId']?.toString(),
      state: (raw['state'] ?? raw['status'] ?? 'pending_review').toString(),
      skipReason: (raw['skipReason'] ?? raw['skippedReason'])?.toString(),
      name: (raw['name'] ?? '').toString(),
      description: (raw['description'] ?? '').toString(),
      bodyMarkdown: (raw['bodyMarkdown'] ?? raw['content'] ?? '').toString(),
      targetSkillId: raw['targetSkillId']?.toString(),
      failureReason: (raw['failureReason'] ?? raw['errorMessage'])?.toString(),
      createdAt: _parseNullableInt(raw['createdAt']),
      updatedAt: _parseNullableInt(raw['updatedAt']),
      dataCategories: dataCategories,
    );
  }

  AgentSkillSuggestionItem copyWith({
    String? candidateId,
    int? revision,
    int? conversationId,
    String? sessionId,
    String? turnId,
    String? state,
    String? skipReason,
    String? name,
    String? description,
    String? bodyMarkdown,
    String? targetSkillId,
    String? failureReason,
    int? createdAt,
    int? updatedAt,
    List<String>? dataCategories,
  }) {
    return AgentSkillSuggestionItem(
      candidateId: candidateId ?? this.candidateId,
      revision: revision ?? this.revision,
      conversationId: conversationId ?? this.conversationId,
      sessionId: sessionId ?? this.sessionId,
      turnId: turnId ?? this.turnId,
      state: state ?? this.state,
      skipReason: skipReason ?? this.skipReason,
      name: name ?? this.name,
      description: description ?? this.description,
      bodyMarkdown: bodyMarkdown ?? this.bodyMarkdown,
      targetSkillId: targetSkillId ?? this.targetSkillId,
      failureReason: failureReason ?? this.failureReason,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      dataCategories: dataCategories ?? this.dataCategories,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'candidateId': candidateId,
      'revision': revision,
      'conversationId': conversationId,
      'sessionId': sessionId,
      'turnId': turnId,
      'state': state,
      'skipReason': skipReason,
      'name': name,
      'description': description,
      'bodyMarkdown': bodyMarkdown,
      'targetSkillId': targetSkillId,
      'failureReason': failureReason,
      'createdAt': createdAt,
      'updatedAt': updatedAt,
      'dataCategories': dataCategories,
    };
  }
}

class AgentSkillRevisionSnapshot {
  final String revisionId;
  final String? previousRevisionId;
  final String name;
  final String description;
  final String bodyMarkdown;
  final int? createdAt;
  final bool wasManuallyDisabled;
  final bool canRestore;

  const AgentSkillRevisionSnapshot({
    required this.revisionId,
    this.previousRevisionId,
    required this.name,
    required this.description,
    required this.bodyMarkdown,
    this.createdAt,
    required this.wasManuallyDisabled,
    required this.canRestore,
  });

  factory AgentSkillRevisionSnapshot.fromMap(Map<String, dynamic> raw) {
    return AgentSkillRevisionSnapshot(
      revisionId: (raw['revisionId'] ?? '').toString(),
      previousRevisionId: raw['previousRevisionId']?.toString(),
      name: (raw['name'] ?? '').toString(),
      description: (raw['description'] ?? '').toString(),
      bodyMarkdown: (raw['bodyMarkdown'] ?? '').toString(),
      createdAt: _parseNullableInt(raw['createdAt']),
      wasManuallyDisabled: raw['wasManuallyDisabled'] == true,
      canRestore: raw['canRestore'] != false,
    );
  }
}

class AgentSkillFeedbackEligibility {
  final bool eligible;
  final String skillId;
  final String revisionId;
  final int? conversationId;
  final String? sessionId;
  final String? turnId;
  final int expectedRevision;
  final String? currentContentHash;
  final String? reason;
  final List<AgentSkillRevisionSnapshot> revisions;

  const AgentSkillFeedbackEligibility({
    required this.eligible,
    required this.skillId,
    required this.revisionId,
    this.conversationId,
    this.sessionId,
    this.turnId,
    required this.expectedRevision,
    this.currentContentHash,
    this.reason,
    this.revisions = const [],
  });

  factory AgentSkillFeedbackEligibility.fromMap(Map<String, dynamic> raw) {
    final rawRevisions = raw['revisions'];
    final revisions = rawRevisions is List
        ? rawRevisions
            .whereType<Map>()
            .map(
              (e) => AgentSkillRevisionSnapshot.fromMap(
                e.map((k, v) => MapEntry(k.toString(), v)),
              ),
            )
            .toList()
        : const <AgentSkillRevisionSnapshot>[];

    final usage = raw['usage'] is Map ? (raw['usage'] as Map) : null;
    return AgentSkillFeedbackEligibility(
      eligible: raw['eligible'] == true,
      skillId: (raw['skillId'] ?? '').toString(),
      revisionId: (raw['revisionId'] ?? '').toString(),
      conversationId: _parseNullableInt(
        usage?['conversationId'] ?? raw['conversationId'],
      ),
      sessionId: (usage?['sessionId'] ?? raw['sessionId'])?.toString(),
      turnId: (usage?['turnId'] ?? raw['turnId'])?.toString(),
      expectedRevision: _parseInt(raw['revision'] ?? raw['expectedRevision']),
      currentContentHash: raw['currentContentHash']?.toString(),
      reason: raw['reason']?.toString(),
      revisions: revisions,
    );
  }
}

class AgentSkillSuggestionListResult {
  final bool enabled;
  final List<AgentSkillSuggestionItem> items;
  final int total;
  final int offset;
  final int limit;
  final AgentSkillFeedbackEligibility? feedbackEligibility;
  final List<AgentSkillRevisionSnapshot> revisions;

  const AgentSkillSuggestionListResult({
    required this.enabled,
    required this.items,
    required this.total,
    required this.offset,
    required this.limit,
    this.feedbackEligibility,
    this.revisions = const [],
  });

  AgentSkillFeedbackEligibility? get skillDetail => feedbackEligibility;

  factory AgentSkillSuggestionListResult.fromMap(Map<String, dynamic> raw) {
    final rawItems = raw['items'] ?? raw['suggestions'];
    final items = rawItems is List
        ? rawItems
            .whereType<Map>()
            .map(
              (e) => AgentSkillSuggestionItem.fromMap(
                e.map((k, v) => MapEntry(k.toString(), v)),
              ),
            )
            .toList()
        : <AgentSkillSuggestionItem>[];

    AgentSkillFeedbackEligibility? feedbackEligibility;
    if (raw['feedbackEligibility'] is Map) {
      feedbackEligibility = AgentSkillFeedbackEligibility.fromMap(
        Map<String, dynamic>.from(raw['feedbackEligibility'] as Map),
      );
    } else if (raw['skillDetail'] is Map) {
      feedbackEligibility = AgentSkillFeedbackEligibility.fromMap(
        Map<String, dynamic>.from(raw['skillDetail'] as Map),
      );
    }

    final rawRevisions = raw['revisions'];
    final revisions = rawRevisions is List
        ? rawRevisions
            .whereType<Map>()
            .map(
              (e) => AgentSkillRevisionSnapshot.fromMap(
                e.map((k, v) => MapEntry(k.toString(), v)),
              ),
            )
            .toList()
        : (feedbackEligibility?.revisions ?? const <AgentSkillRevisionSnapshot>[]);

    return AgentSkillSuggestionListResult(
      enabled: raw['enabled'] != false,
      items: items,
      total: _parseInt(raw['total'] ?? items.length),
      offset: _parseInt(raw['offset']),
      limit: _parseInt(raw['limit'] ?? 20),
      feedbackEligibility: feedbackEligibility,
      revisions: revisions,
    );
  }
}

class AgentSkillApprovalResult {
  final AgentSkillSuggestionItem? candidate;
  final AgentSkillItem? skill;

  const AgentSkillApprovalResult({this.candidate, this.skill});

  factory AgentSkillApprovalResult.fromMap(Map<String, dynamic> raw) {
    AgentSkillSuggestionItem? candidate;
    if (raw['candidate'] is Map) {
      candidate = AgentSkillSuggestionItem.fromMap(
        Map<String, dynamic>.from(raw['candidate'] as Map),
      );
    }
    AgentSkillItem? skill;
    if (raw['skill'] is Map) {
      skill = AgentSkillItem.fromMap(
        Map<String, dynamic>.from(raw['skill'] as Map),
      );
    }
    return AgentSkillApprovalResult(candidate: candidate, skill: skill);
  }
}

class AgentSkillFeedbackResult {
  final String skillId;
  final String verificationStatus;
  final String revisionId;
  final bool enabled;
  final String actionApplied;

  const AgentSkillFeedbackResult({
    required this.skillId,
    required this.verificationStatus,
    required this.revisionId,
    required this.enabled,
    required this.actionApplied,
  });

  factory AgentSkillFeedbackResult.fromMap(Map<String, dynamic> raw) {
    return AgentSkillFeedbackResult(
      skillId: (raw['skillId'] ?? '').toString(),
      verificationStatus: (raw['verificationStatus'] ?? 'verified').toString(),
      revisionId: (raw['revisionId'] ?? '').toString(),
      enabled: raw['enabled'] != false,
      actionApplied: (raw['actionApplied'] ?? raw['action'] ?? '').toString(),
    );
  }
}

class AgentSkillRestoreResult {
  final String skillId;
  final String restoredRevisionId;
  final String verificationStatus;
  final bool enabled;

  const AgentSkillRestoreResult({
    required this.skillId,
    required this.restoredRevisionId,
    required this.verificationStatus,
    required this.enabled,
  });

  factory AgentSkillRestoreResult.fromMap(Map<String, dynamic> raw) {
    return AgentSkillRestoreResult(
      skillId: (raw['skillId'] ?? '').toString(),
      restoredRevisionId:
          (raw['restoredRevisionId'] ?? raw['revisionId'] ?? '').toString(),
      verificationStatus: (raw['verificationStatus'] ?? 'trial').toString(),
      enabled: raw['enabled'] != false,
    );
  }
}

class AgentOfficialSkillsSyncResult {
  final String action;
  final String repositoryUrl;
  final String rootPath;
  final String shellRootPath;
  final int skillCount;
  final List<AgentSkillItem> skills;

  const AgentOfficialSkillsSyncResult({
    required this.action,
    required this.repositoryUrl,
    required this.rootPath,
    required this.shellRootPath,
    required this.skillCount,
    required this.skills,
  });

  factory AgentOfficialSkillsSyncResult.fromMap(Map<String, dynamic> raw) {
    final rawSkills = raw['skills'];
    final skills = rawSkills is List
        ? rawSkills
              .whereType<Map>()
              .map(
                (item) => AgentSkillItem.fromMap(
                  item.map((key, value) => MapEntry(key.toString(), value)),
                ),
              )
              .toList()
        : <AgentSkillItem>[];
    _sortSkills(skills);
    return AgentOfficialSkillsSyncResult(
      action: (raw['action'] ?? '').toString(),
      repositoryUrl: (raw['repositoryUrl'] ?? '').toString(),
      rootPath: (raw['rootPath'] ?? '').toString(),
      shellRootPath: (raw['shellRootPath'] ?? '').toString(),
      skillCount: _parseInt(raw['skillCount']),
      skills: skills,
    );
  }
}

class AgentSkillStoreService {

  static Future<List<AgentSkillItem>> listSkills() async {
    final items = await AssistsMessageService.listAgentSkills();
    final skills = items.map(AgentSkillItem.fromMap).toList();
    _sortSkills(skills);
    return skills;
  }

  static Future<AgentSkillItem?> setEnabled({
    required String skillId,
    required bool enabled,
  }) async {
    final result = await AssistsMessageService.setAgentSkillEnabled(
      skillId: skillId,
      enabled: enabled,
    );
    if (result == null) return null;
    return AgentSkillItem.fromMap(result);
  }

  static Future<bool> deleteSkill({required String skillId}) {
    return AssistsMessageService.deleteAgentSkill(skillId: skillId);
  }

  static Future<AgentSkillItem?> installBuiltinSkill({
    required String skillId,
  }) async {
    final result = await AssistsMessageService.installBuiltinAgentSkill(
      skillId: skillId,
    );
    if (result == null) return null;
    return AgentSkillItem.fromMap(result);
  }

  static Future<AgentOfficialSkillsSyncResult?> syncOfficialSkills() async {
    final result = await AssistsMessageService.syncOfficialAgentSkills();
    if (result == null) return null;
    return AgentOfficialSkillsSyncResult.fromMap(result);
  }

  static Future<AgentSkillSuggestionListResult> listSuggestions({
    int? conversationId,
    String? turnId,
    String? skillId,
    int offset = 0,
    int limit = 20,
  }) async {
    try {
      final result = await AssistsMessageService.listAgentSkillSuggestions(
        conversationId: conversationId,
        turnId: turnId,
        skillId: skillId,
        offset: offset,
        limit: limit,
      );
      return AgentSkillSuggestionListResult.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<AgentSkillSuggestionItem> saveSuggestion({
    required String candidateId,
    required int expectedRevision,
    required String name,
    required String description,
    required String bodyMarkdown,
  }) async {
    try {
      final result = await AssistsMessageService.saveAgentSkillSuggestion(
        candidateId: candidateId,
        expectedRevision: expectedRevision,
        name: name,
        description: description,
        bodyMarkdown: bodyMarkdown,
      );
      return AgentSkillSuggestionItem.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<AgentSkillApprovalResult> approveSuggestion({
    required String candidateId,
    required int expectedRevision,
    required String targetSkillId,
    String? expectedCurrentContentHash,
  }) async {
    try {
      final result = await AssistsMessageService.approveAgentSkillSuggestion(
        candidateId: candidateId,
        expectedRevision: expectedRevision,
        targetSkillId: targetSkillId,
        expectedCurrentContentHash: expectedCurrentContentHash,
      );
      return AgentSkillApprovalResult.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<AgentSkillSuggestionItem> rejectSuggestion({
    required String candidateId,
    required int expectedRevision,
  }) async {
    try {
      final result = await AssistsMessageService.rejectAgentSkillSuggestion(
        candidateId: candidateId,
        expectedRevision: expectedRevision,
      );
      return AgentSkillSuggestionItem.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<AgentSkillSuggestionItem> regenerateSuggestion({
    required String candidateId,
    required int expectedRevision,
  }) async {
    try {
      final result = await AssistsMessageService.regenerateAgentSkillSuggestion(
        candidateId: candidateId,
        expectedRevision: expectedRevision,
      );
      return AgentSkillSuggestionItem.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<bool> setAutoSuggestionsEnabled({required bool enabled}) async {
    try {
      final result =
          await AssistsMessageService.setAgentSkillSuggestionsEnabled(
        enabled: enabled,
      );
      return result['enabled'] == true;
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<AgentSkillFeedbackResult> submitFeedback({
    required String skillId,
    required String revisionId,
    required int conversationId,
    required String sessionId,
    required String turnId,
    required int expectedRevision,
    required String action,
  }) async {
    try {
      final result = await AssistsMessageService.submitAgentSkillFeedback(
        skillId: skillId,
        revisionId: revisionId,
        conversationId: conversationId,
        sessionId: sessionId,
        turnId: turnId,
        expectedRevision: expectedRevision,
        action: action,
      );
      return AgentSkillFeedbackResult.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<AgentSkillRestoreResult> restoreRevision({
    required String skillId,
    required String revisionId,
    required int expectedRevision,
    required String expectedCurrentContentHash,
  }) async {
    try {
      final result = await AssistsMessageService.restoreAgentSkillRevision(
        skillId: skillId,
        revisionId: revisionId,
        expectedRevision: expectedRevision,
        expectedCurrentContentHash: expectedCurrentContentHash,
      );
      return AgentSkillRestoreResult.fromMap(result);
    } on PlatformException catch (e) {
      throw SkillAssetException.fromPlatformException(e);
    }
  }

  static Future<String?> getSkillCurrentContentHash(String skillId) async {
    try {
      final result = await listSuggestions(skillId: skillId);
      return result.feedbackEligibility?.currentContentHash;
    } catch (_) {
      return null;
    }
  }
}

int _parseInt(Object? value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value) ?? 0;
  return 0;
}

int? _parseNullableInt(Object? value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value);
  return null;
}

int _sourceRank(AgentSkillItem item) {
  if (item.isBuiltin) return 0;
  if (item.isOfficial) return 1;
  return 2;
}

void _sortSkills(List<AgentSkillItem> skills) {
  skills.sort((a, b) {
    if (a.installed != b.installed) {
      return a.installed ? -1 : 1;
    }
    final sourceOrder = _sourceRank(a).compareTo(_sourceRank(b));
    if (sourceOrder != 0) {
      return sourceOrder;
    }
    return a.name.toLowerCase().compareTo(b.name.toLowerCase());
  });
}
