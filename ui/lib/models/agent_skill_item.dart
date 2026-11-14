class AgentSkillItem {
  final String id;
  final String name;
  final String description;
  final String? compatibility;
  final Map<String, dynamic> metadata;
  final String rootPath;
  final String shellRootPath;
  final String skillFilePath;
  final String shellSkillFilePath;
  final bool hasScripts;
  final bool hasReferences;
  final bool hasAssets;
  final bool hasEvals;
  final bool enabled;
  final String source;
  final bool installed;
  final String verificationStatus;
  final String? revisionId;
  final String? previousRevisionId;
  final String? contentHash;
  final bool contentModified;
  const AgentSkillItem({
    required this.id,
    required this.name,
    required this.description,
    this.compatibility,
    required this.metadata,
    required this.rootPath,
    required this.shellRootPath,
    required this.skillFilePath,
    required this.shellSkillFilePath,
    required this.hasScripts,
    required this.hasReferences,
    required this.hasAssets,
    required this.hasEvals,
    required this.enabled,
    required this.source,
    required this.installed,
    this.verificationStatus = 'unverified',
    this.revisionId,
    this.previousRevisionId,
    this.contentHash,
    this.contentModified = false,
  });
  factory AgentSkillItem.fromMap(Map<String, dynamic> raw) {
    return AgentSkillItem(
      id: (raw['id'] ?? '').toString(),
      name: (raw['name'] ?? '').toString(),
      description: (raw['description'] ?? '').toString(),
      compatibility: raw['compatibility']?.toString(),
      metadata: Map<String, dynamic>.from(
        (raw['metadata'] as Map?) ?? const <String, dynamic>{},
      ),
      rootPath: (raw['rootPath'] ?? '').toString(),
      shellRootPath: (raw['shellRootPath'] ?? raw['rootPath'] ?? '').toString(),
      skillFilePath: (raw['skillFilePath'] ?? '').toString(),
      shellSkillFilePath:
          (raw['shellSkillFilePath'] ?? raw['skillFilePath'] ?? '').toString(),
      hasScripts: raw['hasScripts'] == true,
      hasReferences: raw['hasReferences'] == true,
      hasAssets: raw['hasAssets'] == true,
      hasEvals: raw['hasEvals'] == true,
      enabled: raw['enabled'] != false,
      source: (raw['source'] ?? 'user').toString(),
      installed: raw['installed'] != false,
      verificationStatus:
          (raw['verificationStatus'] ?? 'unverified').toString(),
      revisionId: raw['revisionId']?.toString(),
      previousRevisionId: raw['previousRevisionId']?.toString(),
      contentHash: (raw['contentHash'] ?? raw['metadata']?['contentHash'])
          ?.toString(),
      contentModified: raw['contentModified'] == true,
    );
  }
  factory AgentSkillItem.fromJson(Map<String, dynamic> raw) =>
      AgentSkillItem.fromMap(raw);


  List<String> get capabilities {
    final values = <String>[];
    if (hasScripts) values.add('scripts');
    if (hasReferences) values.add('references');
    if (hasAssets) values.add('assets');
    if (hasEvals) values.add('evals');
    return values;
  }

  bool get isBuiltin => source == 'builtin';

  bool get isOfficial => source == 'official';

  bool get isUnverified => verificationStatus == 'unverified';

  bool get isTrial => verificationStatus == 'trial';

  bool get isVerified => verificationStatus == 'verified';

  bool get isRetired => verificationStatus == 'retired';

  bool get canRestoreRevision =>
      previousRevisionId != null && previousRevisionId!.isNotEmpty;

  AgentSkillItem copyWith({
    String? id,
    String? name,
    String? description,
    String? compatibility,
    Map<String, dynamic>? metadata,
    String? rootPath,
    String? shellRootPath,
    String? skillFilePath,
    String? shellSkillFilePath,
    bool? hasScripts,
    bool? hasReferences,
    bool? hasAssets,
    bool? hasEvals,
    bool? enabled,
    String? source,
    bool? installed,
    String? verificationStatus,
    String? revisionId,
    String? previousRevisionId,
    String? contentHash,
    bool? contentModified,
  }) {
    return AgentSkillItem(
      id: id ?? this.id,
      name: name ?? this.name,
      description: description ?? this.description,
      compatibility: compatibility ?? this.compatibility,
      metadata: metadata ?? this.metadata,
      rootPath: rootPath ?? this.rootPath,
      shellRootPath: shellRootPath ?? this.shellRootPath,
      skillFilePath: skillFilePath ?? this.skillFilePath,
      shellSkillFilePath: shellSkillFilePath ?? this.shellSkillFilePath,
      hasScripts: hasScripts ?? this.hasScripts,
      hasReferences: hasReferences ?? this.hasReferences,
      hasAssets: hasAssets ?? this.hasAssets,
      hasEvals: hasEvals ?? this.hasEvals,
      enabled: enabled ?? this.enabled,
      source: source ?? this.source,
      installed: installed ?? this.installed,
      verificationStatus: verificationStatus ?? this.verificationStatus,
      revisionId: revisionId ?? this.revisionId,
      previousRevisionId: previousRevisionId ?? this.previousRevisionId,
      contentHash: contentHash ?? this.contentHash,
      contentModified: contentModified ?? this.contentModified,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'id': id,
      'name': name,
      'description': description,
      'compatibility': compatibility,
      'metadata': metadata,
      'rootPath': rootPath,
      'shellRootPath': shellRootPath,
      'skillFilePath': skillFilePath,
      'shellSkillFilePath': shellSkillFilePath,
      'hasScripts': hasScripts,
      'hasReferences': hasReferences,
      'hasAssets': hasAssets,
      'hasEvals': hasEvals,
      'enabled': enabled,
      'source': source,
      'installed': installed,
      'verificationStatus': verificationStatus,
      'revisionId': revisionId,
      'previousRevisionId': previousRevisionId,
      'contentHash': contentHash,
      'contentModified': contentModified,
    };
  }

  Map<String, dynamic> toJson() => toMap();
}
