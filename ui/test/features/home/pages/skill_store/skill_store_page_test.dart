import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/skill_store/skill_store_page.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/theme/app_theme.dart';
import 'package:ui/widgets/settings_ui.dart';

class _SvgTestAssetBundle extends CachingAssetBundle {
  static final Uint8List _svgBytes = Uint8List.fromList(
    utf8.encode(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">'
      '<rect width="24" height="24" fill="#000000"/>'
      '</svg>',
    ),
  );

  @override
  Future<ByteData> load(String key) async {
    return ByteData.view(_svgBytes.buffer);
  }

  @override
  Future<String> loadString(String key, {bool cache = true}) async {
    return utf8.decode(_svgBytes);
  }
}

Widget _buildTestApp({Widget? child}) {
  return MaterialApp(
    locale: const Locale('zh'),
    theme: AppTheme.lightTheme,
    darkTheme: AppTheme.darkTheme,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    supportedLocales: AppLocalizations.supportedLocales,
    home: DefaultAssetBundle(
      bundle: _SvgTestAssetBundle(),
      child: child ?? const SkillStorePage(),
    ),
  );
}
Future<void> _pumpPage(WidgetTester tester, [Widget? widget]) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  await tester.pumpWidget(_buildTestApp(child: widget));
  for (var i = 0; i < 5; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _pumpStep(WidgetTester tester) async {
  await tester.pumpAndSettle();
}


void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');

  late List<Map<String, dynamic>> installedSkills;
  late List<Map<String, dynamic>> candidateItems;
  late Map<String, dynamic> skillDetails;
  late bool autoSuggestionsEnabled;
  late List<MethodCall> recordedCalls;
  late int currentCandidateRevision;
  late bool forceFeedbackFailure;
  late bool forceRestoreFailure;
  Completer<void>? pendingFeedbackCompleter;
  Completer<void>? pendingRestoreCompleter;

  setUp(() {
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    AssistsMessageService.initialize();
    recordedCalls = <MethodCall>[];
    autoSuggestionsEnabled = true;
    currentCandidateRevision = 1;
    forceFeedbackFailure = false;
    forceRestoreFailure = false;
    pendingFeedbackCompleter = null;
    pendingRestoreCompleter = null;
    installedSkills = <Map<String, dynamic>>[
      <String, dynamic>{
        'id': 'git-release-helper',
        'name': 'git-release-helper',
        'description': '已安装的 Git 发布技能',
        'rootPath': '/skills/git-release-helper',
        'skillFilePath': '/skills/git-release-helper/SKILL.md',
        'hasScripts': false,
        'hasReferences': false,
        'hasAssets': false,
        'hasEvals': false,
        'enabled': true,
        'source': 'user',
        'installed': true,
        'verificationStatus': 'trial',
        'revisionId': 'rev_v1',
        'previousRevisionId': 'rev_v0',
        'contentHash': 'hash_v1',
        'metadata': <String, dynamic>{},
      },
      <String, dynamic>{
        'id': 'builtin-calc',
        'name': 'builtin-calc',
        'description': '内置计算器技能',
        'rootPath': '/skills/builtin-calc',
        'skillFilePath': '/skills/builtin-calc/SKILL.md',
        'hasScripts': false,
        'hasReferences': false,
        'hasAssets': false,
        'hasEvals': false,
        'enabled': true,
        'source': 'builtin',
        'installed': true,
        'verificationStatus': 'verified',
        'revisionId': 'builtin_v1',
        'previousRevisionId': null,
        'contentHash': 'hash_builtin',
        'metadata': <String, dynamic>{},
      },
    ];

    candidateItems = <Map<String, dynamic>>[
      <String, dynamic>{
        'candidateId': 'cand_123',
        'revision': 1,
        'conversationId': 1001,
        'sessionId': 'session-1001',
        'turnId': 'turn-1',
        'state': 'pending_review',
        'skipReason': null,
        'name': 'docker-deploy-helper',
        'description': '自动构建与部署 Docker 镜像',
        'bodyMarkdown': '# Docker Deploy Helper\n\n自动化部署步骤...',
        'targetSkillId': 'docker-deploy-helper',
        'failureReason': null,
        'createdAt': 1700000000000,
        'updatedAt': 1700000005000,
        'dataCategories': ['任务答复', '工具名与参数', '可用技能清单'],
      },
    ];

    skillDetails = <String, dynamic>{
      'git-release-helper': <String, dynamic>{
        'skillId': 'git-release-helper',
        'revisionId': 'rev_v1',
        'revision': 1,
        'currentContentHash': 'hash_v1',
        'eligible': true,
        'reason': '已在独立任务 #2001 中实际调用验证',
        'usage': <String, dynamic>{
          'conversationId': 2001,
          'sessionId': 'session-2001',
          'turnId': 'turn-2',
        },
        'revisions': <Map<String, dynamic>>[
          <String, dynamic>{
            'revisionId': 'rev_v1',
            'previousRevisionId': 'rev_v0',
            'name': 'git-release-helper',
            'description': '已安装的 Git 发布技能',
            'bodyMarkdown': '# v1 markdown',
            'createdAt': 1700000005000,
            'wasManuallyDisabled': false,
            'canRestore': true,
          },
          <String, dynamic>{
            'revisionId': 'rev_v0',
            'previousRevisionId': null,
            'name': 'git-release-helper',
            'description': '已安装的 Git 发布技能 v0',
            'bodyMarkdown': '# v0 markdown',
            'createdAt': 1700000000000,
            'wasManuallyDisabled': false,
            'canRestore': true,
          },
        ],
      },
    };


    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          recordedCalls.add(call);
          switch (call.method) {
            case 'agentSkillList':
              return installedSkills;
            case 'agentSkillSetEnabled':
              final args = call.arguments as Map;
              final skillId = args['skillId'].toString();
              final enabled = args['enabled'] == true;
              for (var i = 0; i < installedSkills.length; i++) {
                if (installedSkills[i]['id'] == skillId) {
                  installedSkills[i]['enabled'] = enabled;
                  return installedSkills[i];
                }
              }
              return null;
            case 'agentSkillDelete':
              final args = call.arguments as Map;
              final skillId = args['skillId'].toString();
              installedSkills.removeWhere((item) => item['id'] == skillId);
              return {'deleted': true};
            case 'agentSkillSuggestionList':
              final args = call.arguments as Map?;
              final skillId = args?['skillId']?.toString();
              Map<String, dynamic>? detail;
              if (skillId != null && skillDetails.containsKey(skillId)) {
                detail = skillDetails[skillId];
              }
              return {
                'enabled': autoSuggestionsEnabled,
                'items': candidateItems,
                'total': candidateItems.length,
                'offset': 0,
                'limit': 20,
                if (detail != null) 'feedbackEligibility': detail,
                if (detail != null) 'skillDetail': detail,
                'revisions': detail != null ? (detail['revisions'] ?? []) : [],
              };
            case 'agentSkillSuggestionSave':
              final args = call.arguments as Map;
              final expectedRev = args['expectedRevision'] as int;
              if (expectedRev != currentCandidateRevision) {
                throw PlatformException(
                  code: 'skill_revision_conflict',
                  message: 'CAS 版本冲突',
                );
              }
              currentCandidateRevision++;
              return {
                'candidateId': args['candidateId'],
                'revision': currentCandidateRevision,
                'state': 'pending_review',
                'name': args['name'],
                'description': args['description'],
                'bodyMarkdown': args['bodyMarkdown'],
                'dataCategories': ['任务答复'],
              };
            case 'agentSkillSuggestionApprove':
              final args = call.arguments as Map;
              final expectedRev = args['expectedRevision'] as int;
              if (expectedRev != currentCandidateRevision) {
                throw PlatformException(
                  code: 'skill_revision_conflict',
                  message: 'CAS 版本冲突',
                );
              }
              if (args['targetSkillId'] == 'builtin-calc') {
                throw PlatformException(
                  code: 'skill_cannot_overwrite_builtin',
                  message: '严禁覆盖官方或内置技能',
                );
              }
              currentCandidateRevision++;
              return {
                'candidate': {
                  'candidateId': args['candidateId'],
                  'revision': currentCandidateRevision,
                  'state': 'approved',
                  'name': args['targetSkillId'],
                  'description': '审核通过',
                  'bodyMarkdown': 'content',
                },
                'skill': {
                  'id': args['targetSkillId'],
                  'name': args['targetSkillId'],
                  'description': '审核通过',
                  'verificationStatus': 'trial',
                  'revisionId': 'rev_new',
                  'enabled': true,
                  'installed': true,
                  'source': 'user',
                  'rootPath': '/skills/${args['targetSkillId']}',
                  'skillFilePath': '/skills/${args['targetSkillId']}/SKILL.md',
                  'hasScripts': false,
                  'hasReferences': false,
                  'hasAssets': false,
                  'hasEvals': false,
                  'metadata': <String, dynamic>{},
                },
              };
            case 'agentSkillSuggestionReject':
              final args = call.arguments as Map;
              final expectedRev = args['expectedRevision'] as int;
              if (expectedRev != currentCandidateRevision) {
                throw PlatformException(
                  code: 'skill_revision_conflict',
                  message: 'CAS 版本冲突',
                );
              }
              currentCandidateRevision++;
              return {
                'candidateId': args['candidateId'],
                'revision': currentCandidateRevision,
                'state': 'rejected',
                'name': 'docker-deploy-helper',
                'description': '已拒绝',
                'bodyMarkdown': 'content',
              };
            case 'agentSkillSuggestionRegenerate':
              final args = call.arguments as Map;
              return {
                'candidateId': args['candidateId'],
                'revision': 2,
                'state': 'queued',
                'name': 'docker-deploy-helper',
                'description': '重新生成中',
                'bodyMarkdown': '',
              };
            case 'agentSkillSuggestionsSetEnabled':
              final args = call.arguments as Map;
              final enabled = args['enabled'] == true;
              if (args['forceFailure'] == true) {
                throw PlatformException(
                  code: 'failure',
                  message: '设置开关失败',
                );
              }
              autoSuggestionsEnabled = enabled;
              return {'enabled': enabled};
            case 'agentSkillFeedback':
              final args = call.arguments as Map;
              final action = args['action'].toString();
              final skillId = args['skillId'].toString();
              if (pendingFeedbackCompleter != null) {
                await pendingFeedbackCompleter!.future;
              }
              if (forceFeedbackFailure) {
                throw PlatformException(
                  code: 'skill_feedback_ineligible',
                  message: '未满足独立后续实际使用凭据，无法提交试用反馈',
                );
              }
              for (var i = 0; i < installedSkills.length; i++) {
                if (installedSkills[i]['id'] == skillId) {
                  installedSkills[i]['verificationStatus'] =
                      action == 'retain' ? 'verified' : 'retired';
                  installedSkills[i]['enabled'] = action == 'retain';
                  break;
                }
              }
              skillDetails.remove(skillId);
              return {
                'skillId': skillId,
                'verificationStatus': action == 'retain' ? 'verified' : 'retired',
                'revisionId': args['revisionId'],
                'enabled': action == 'retain',
                'actionApplied': action,
              };
            case 'agentSkillRestoreRevision':
              final args = call.arguments as Map;
              final skillId = args['skillId'].toString();
              final targetRevisionId = args['revisionId'].toString();
              if (pendingRestoreCompleter != null) {
                await pendingRestoreCompleter!.future;
              }
              if (forceRestoreFailure) {
                throw PlatformException(
                  code: 'skill_content_conflict',
                  message: '当前磁盘内容与期望哈希不匹配，可能已被外部修改',
                );
              }
              for (var i = 0; i < installedSkills.length; i++) {
                if (installedSkills[i]['id'] == skillId) {
                  installedSkills[i]['revisionId'] = targetRevisionId;
                  installedSkills[i]['verificationStatus'] = 'trial';
                  installedSkills[i]['enabled'] = true;
                  break;
                }
              }
              return {
                'skillId': skillId,
                'restoredRevisionId': targetRevisionId,
                'verificationStatus': 'trial',
                'enabled': true,
              };
            default:
              return null;
          }
        });
  });

  tearDown(() {
    LegacyTextLocalizer.clearResolvedLocale();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('未审核建议严格隔离在待审核分组，不在已启用/已接入技能列表中', (tester) async {
    await _pumpPage(tester);
    // 验证待审核建议分组存在且包含候选
    expect(find.text('待审核建议 (1)'), findsOneWidget);
    expect(find.text('docker-deploy-helper'), findsOneWidget);
    expect(find.text('自动构建与部署 Docker 镜像'), findsOneWidget);

    // 验证已接入技能分组存在
    expect(find.text('已接入技能 (2)'), findsOneWidget);
    expect(find.text('git-release-helper'), findsOneWidget);
    expect(find.text('builtin-calc'), findsOneWidget);

    // 验证待审核候选的独立 tile 包含状态标识
    final candidateTile = find.byKey(const Key('skill_candidate_tile_cand_123'));
    expect(candidateTile, findsOneWidget);
    expect(find.descendant(of: candidateTile, matching: find.text('待审核')), findsOneWidget);

    // 验证未审核候选不包含安装/启用开关控件
    expect(
      find.descendant(of: candidateTile, matching: find.byType(OmniSettingsSwitch)),
      findsNothing,
    );
  });

  testWidgets('候选保存冲突时保留用户输入并不清空表单', (tester) async {
    await _pumpPage(tester);

    // 点击待审核候选打开审核 Sheet
    await tester.tap(find.byKey(const Key('skill_candidate_tile_cand_123')));
    await _pumpStep(tester);
    expect(find.text('技能建议审核'), findsOneWidget);

    // 编辑名称、描述与正文
    final nameField = find.byKey(const Key('skill_candidate_name_field'));
    final descField = find.byKey(const Key('skill_candidate_description_field'));
    final bodyField = find.byKey(const Key('skill_candidate_body_field'));

    await tester.enterText(nameField, 'custom-docker-tool');
    await tester.enterText(descField, '用户修改后的描述');
    await tester.enterText(bodyField, '# 自定义修改后的 Markdown 内容');
    await _pumpStep(tester);
    // 修改 currentCandidateRevision 模拟远端 CAS 冲突
    currentCandidateRevision = 999;

    // 点击保存草稿
    await tester.tap(find.byKey(const Key('skill_candidate_save_button')));
    await _pumpStep(tester);
    // 验证冲突提示展示，但输入内容完整保留
    expect(find.textContaining('版本冲突'), findsOneWidget);
    expect(find.text('custom-docker-tool'), findsOneWidget);
    expect(find.text('用户修改后的描述'), findsOneWidget);
    expect(find.text('# 自定义修改后的 Markdown 内容'), findsOneWidget);
  });

  testWidgets('审核候选时覆盖内置/官方技能被严格禁止，更新用户技能需确认', (tester) async {
    await _pumpPage(tester);

    await tester.tap(find.byKey(const Key('skill_candidate_tile_cand_123')));
    await _pumpStep(tester);
    final nameField = find.byKey(const Key('skill_candidate_name_field'));
    await tester.enterText(nameField, 'builtin-calc');
    await _pumpStep(tester);
    // 验证出现禁止覆盖官方/内置技能警告
    expect(find.textContaining('严禁覆盖官方或内置技能'), findsOneWidget);

    // 输入与已有用户技能重名的标识
    await tester.enterText(nameField, 'git-release-helper');
    await _pumpStep(tester);
    expect(find.textContaining('git-release-helper'), findsWidgets);

    // 点击保存并试用触发二次确认弹窗
    final approveBtn = find.byKey(const Key('skill_candidate_approve_button'));
    await tester.ensureVisible(approveBtn);
    await tester.tap(approveBtn);
    await _pumpStep(tester);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.text('保存并启用试用'), findsOneWidget);

    // 点击确认试用
    await tester.tap(find.text('确认试用'));
    await _pumpStep(tester);
    // 验证底层调用传递了用户技能的 contentHash
    final approveCall = recordedCalls.firstWhere(
      (c) => c.method == 'agentSkillSuggestionApprove',
    );
    expect(approveCall.arguments['targetSkillId'], 'git-release-helper');
    expect(approveCall.arguments['expectedCurrentContentHash'], 'hash_v1');
  });

  testWidgets('拒绝建议与保存试用操作具备明确二次确认拦截', (tester) async {
    await _pumpPage(tester);

    await tester.tap(find.byKey(const Key('skill_candidate_tile_cand_123')));
    await _pumpStep(tester);

    final rejectButton = find.byKey(const Key('skill_candidate_reject_button'));
    await tester.ensureVisible(rejectButton);
    await _pumpStep(tester);
    await tester.tap(rejectButton);
    await _pumpStep(tester);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.text('拒绝技能建议'), findsOneWidget);

    // 取消拒绝，验证未触发原生 reject 调用
    await tester.tap(find.descendant(
      of: find.byType(AlertDialog),
      matching: find.widgetWithText(TextButton, '取消'),
    ));
    await _pumpStep(tester);
    expect(find.byType(AlertDialog), findsNothing);
    expect(
      recordedCalls.any((c) => c.method == 'agentSkillSuggestionReject'),
      isFalse,
    );

    // 再次点击并确认拒绝
    await tester.tap(rejectButton);
    await _pumpStep(tester);
    await tester.tap(find.text('确认拒绝'));
    await _pumpStep(tester);
    expect(
      recordedCalls.any((c) => c.method == 'agentSkillSuggestionReject'),
      isTrue,
    );
  });
  testWidgets('连续保存草稿时自动更新最新 revision，不发生冲突', (tester) async {
    await _pumpPage(tester);

    await tester.tap(find.byKey(const Key('skill_candidate_tile_cand_123')));
    await _pumpStep(tester);
    final nameField = find.byKey(const Key('skill_candidate_name_field'));
    final saveBtn = find.byKey(const Key('skill_candidate_save_button'));

    // 第一次编辑并保存：rev 1 -> rev 2
    await tester.enterText(nameField, 'step-one-name');
    await _pumpStep(tester);
    await tester.tap(saveBtn);
    await _pumpStep(tester);
    expect(find.text('保存成功'), findsOneWidget);
    expect(find.text('版本 Rev 2'), findsOneWidget);
    expect(currentCandidateRevision, 2);

    // 第二次继续编辑并保存：rev 2 -> rev 3
    await tester.enterText(nameField, 'step-two-name');
    await _pumpStep(tester);
    await tester.tap(saveBtn);
    await _pumpStep(tester);
    expect(find.text('保存成功'), findsOneWidget);
    expect(find.text('版本 Rev 3'), findsOneWidget);
    expect(currentCandidateRevision, 3);
  });

  testWidgets('保存草稿后再拒绝建议使用最新递增后的 revision', (tester) async {
    await _pumpPage(tester);

    await tester.tap(find.byKey(const Key('skill_candidate_tile_cand_123')));
    await _pumpStep(tester);
    final nameField = find.byKey(const Key('skill_candidate_name_field'));
    await tester.enterText(nameField, 'edited-then-reject');
    await _pumpStep(tester);
    await tester.tap(find.byKey(const Key('skill_candidate_save_button')));
    await _pumpStep(tester);

    // 再点击拒绝建议，确认调用
    await tester.tap(find.byKey(const Key('skill_candidate_reject_button')));
    await _pumpStep(tester);
    await tester.tap(find.text('确认拒绝'));
    await _pumpStep(tester);
    final rejectCall = recordedCalls.lastWhere(
      (c) => c.method == 'agentSkillSuggestionReject',
    );
    expect(rejectCall.arguments['expectedRevision'], 2);
  });

  testWidgets('审核试用前自动先保存当前完整编辑内容，确保实际启用内容为审核后最新版本', (tester) async {
    await _pumpPage(tester);

    await tester.tap(find.byKey(const Key('skill_candidate_tile_cand_123')));
    await _pumpStep(tester);
    final nameField = find.byKey(const Key('skill_candidate_name_field'));
    final descField = find.byKey(const Key('skill_candidate_description_field'));
    final bodyField = find.byKey(const Key('skill_candidate_body_field'));

    await tester.enterText(nameField, 'docker-production-pack');
    await tester.enterText(descField, '生产环境 Docker 打包描述');
    await tester.enterText(bodyField, '# 生产配置\n\n完整步骤代码');
    await _pumpStep(tester);
    // 直接点击“保存并试用”
    await tester.tap(find.byKey(const Key('skill_candidate_approve_button')));
    await _pumpStep(tester);
    expect(find.text('保存并启用试用'), findsOneWidget);
    await tester.tap(find.text('确认试用'));
    await _pumpStep(tester);
    // 验证必须先触发了 saveSuggestion，并且保存了用户在表单里编辑的完整内容
    final saveCall = recordedCalls.firstWhere(
      (c) => c.method == 'agentSkillSuggestionSave',
    );
    expect(saveCall.arguments['expectedRevision'], 1);
    expect(saveCall.arguments['name'], 'docker-production-pack');
    expect(saveCall.arguments['description'], '生产环境 Docker 打包描述');
    expect(saveCall.arguments['bodyMarkdown'], '# 生产配置\n\n完整步骤代码');

    // 验证紧接着触发了 approveSuggestion，且使用的是递增后的 revision 2
    final approveCall = recordedCalls.firstWhere(
      (c) => c.method == 'agentSkillSuggestionApprove',
    );
    expect(approveCall.arguments['expectedRevision'], 2);
    expect(approveCall.arguments['targetSkillId'], 'docker-production-pack');
  });


  testWidgets('试用技能反馈：满足条件展示有效/停用并能提交，无资格时不展示', (tester) async {
    await _pumpPage(tester);
    // git-release-helper 具备 trial 且 eligible = true
    final feedbackButton = find.byKey(const Key('skill_feedback_btn_git-release-helper'));
    expect(feedbackButton, findsOneWidget);

    // builtin-calc 为 verified/无试用反馈资格，不展示反馈按钮
    expect(find.byKey(const Key('skill_feedback_btn_builtin-calc')), findsNothing);

    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await _pumpStep(tester);
    expect(find.text('试用反馈评价'), findsOneWidget);
    expect(find.textContaining('2001'), findsWidgets);

    // 点击“有效，保留”弹出二次确认
    final retainButton = find.byKey(const Key('skill_feedback_retain_button'));
    await tester.ensureVisible(retainButton);
    await tester.tap(retainButton);
    await _pumpStep(tester);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.text('确认保留此技能'), findsOneWidget);

    // 取消确认
    await tester.tap(find.descendant(
      of: find.byType(AlertDialog),
      matching: find.widgetWithText(TextButton, '取消'),
    ));
    await _pumpStep(tester);
    expect(
      recordedCalls.any((c) => c.method == 'agentSkillFeedback'),
      isFalse,
    );

    // 再次点击并确认有效并保留
    await tester.tap(retainButton);
    await _pumpStep(tester);
    await tester.tap(find.text('确认有效并保留'));
    await _pumpStep(tester);

    final feedbackCall = recordedCalls.firstWhere(
      (c) => c.method == 'agentSkillFeedback',
    );
    expect(feedbackCall.arguments['action'], 'retain');
    expect(feedbackCall.arguments['skillId'], 'git-release-helper');
    expect(feedbackCall.arguments['conversationId'], 2001);
    expect(find.text('试用反馈评价'), findsNothing);
    expect(find.text('已验证'), findsWidgets);
  });

  testWidgets('恢复历史版本时使用真实 CAS revision 与内容哈希，需经二次确认方可执行', (tester) async {
    // 设置真实的 CAS revision 为 5
    skillDetails['git-release-helper']['revision'] = 5;

    await _pumpPage(tester);
    final restoreButton = find.byKey(const Key('skill_restore_btn_git-release-helper'));
    expect(restoreButton, findsOneWidget);

    await tester.ensureVisible(restoreButton);
    await tester.tap(restoreButton);
    await _pumpStep(tester);
    expect(find.text('恢复技能上一版本'), findsOneWidget);
    expect(find.textContaining('rev_v0'), findsWidgets);

    // 点击确认恢复触发二次确认对话框
    final confirmButton = find.byKey(const Key('skill_restore_confirm_button'));
    await tester.ensureVisible(confirmButton);
    await tester.tap(confirmButton);
    await _pumpStep(tester);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.text('确认恢复历史版本'), findsOneWidget);

    // 取消确认对话框，验证不调用原生恢复
    await tester.tap(find.descendant(
      of: find.byType(AlertDialog),
      matching: find.widgetWithText(TextButton, '取消'),
    ));
    await _pumpStep(tester);
    expect(
      recordedCalls.any((c) => c.method == 'agentSkillRestoreRevision'),
      isFalse,
    );

    // 再次点击确认恢复并在弹窗中确认
    await tester.tap(confirmButton);
    await _pumpStep(tester);
    await tester.tap(find.text('确认恢复'));
    await _pumpStep(tester);

    final restoreCall = recordedCalls.firstWhere(
      (c) => c.method == 'agentSkillRestoreRevision',
    );
    expect(restoreCall.arguments['skillId'], 'git-release-helper');
    expect(restoreCall.arguments['revisionId'], 'rev_v0');
    expect(restoreCall.arguments['expectedRevision'], 5);
    expect(restoreCall.arguments['expectedCurrentContentHash'], 'hash_v1');
    expect(find.text('恢复技能上一版本'), findsNothing);
    expect(installedSkills[0]['revisionId'], 'rev_v0');
  });

  testWidgets('历史版本先前被手动停用时严格阻断恢复', (tester) async {
    // 标记上一版本资产 DTO 快照曾被手动停用
    skillDetails['git-release-helper']['revisions'] = <Map<String, dynamic>>[
      <String, dynamic>{
        'revisionId': 'rev_v0',
        'wasManuallyDisabled': true,
        'canRestore': false,
      },
    ];

    await _pumpPage(tester);

    final restoreButton = find.byKey(const Key('skill_restore_btn_git-release-helper'));
    await tester.ensureVisible(restoreButton);
    await tester.tap(restoreButton);
    await _pumpStep(tester);
    expect(find.byIcon(Icons.block), findsOneWidget);
    expect(find.textContaining('停用'), findsWidgets);
    final confirmButton = tester.widget<FilledButton>(
      find.byKey(const Key('skill_restore_confirm_button')),
    );
    expect(confirmButton.onPressed, isNull);
    expect(
      recordedCalls.any((c) => c.method == 'agentSkillRestoreRevision'),
      isFalse,
    );
  });

  testWidgets('恢复历史版本时若缺失 CAS revision 或内容哈希则拒绝操作并不调用原生恢复', (tester) async {
    // 移除 CAS revision 与哈希
    skillDetails['git-release-helper']['revision'] = 0;
    skillDetails['git-release-helper']['currentContentHash'] = null;
    installedSkills[0]['contentHash'] = null;

    await _pumpPage(tester);

    final restoreButton = find.byKey(const Key('skill_restore_btn_git-release-helper'));
    await tester.ensureVisible(restoreButton);
    await tester.tap(restoreButton);
    await _pumpStep(tester);

    // 未打开恢复 Sheet 且未调用原生恢复接口
    expect(find.text('恢复技能上一版本'), findsNothing);
    expect(
      recordedCalls.any((c) => c.method == 'agentSkillRestoreRevision'),
      isFalse,
    );
  });

  testWidgets('成功关闭自动建议开关时正常更新为 false，绝不误认为操作失败', (tester) async {
    final semanticsHandle = tester.ensureSemantics();
    await _pumpPage(tester);

    final switchFinder = find.descendant(
      of: find.byType(OmniSettingsGroup).first,
      matching: find.byType(OmniSettingsSwitch),
    );
    expect(tester.widget<OmniSettingsSwitch>(switchFinder).value, isTrue);

    // 从屏幕阅读器可识别的实际开关入口操作。
    await tester.tap(find.bySemanticsLabel('自动技能建议'));
    await _pumpStep(tester);

    expect(autoSuggestionsEnabled, isFalse);

    // 成功设置为 false，不弹错误提示
    expect(tester.widget<OmniSettingsSwitch>(switchFinder).value, isFalse);
    expect(find.text('自动建议开关设置失败'), findsNothing);
    semanticsHandle.dispose();
  });

  testWidgets('原生 skill_assets_changed 事件驱动页面静默刷新并更新候选状态与清空过期反馈缓存', (tester) async {
    await _pumpPage(tester);

    // 初始状态：候选为 pending_review，trial 技能有反馈按钮
    expect(find.byKey(const Key('skill_feedback_btn_git-release-helper')), findsOneWidget);
    expect(find.text('待审核建议 (1)'), findsOneWidget);

    // 模拟原生侧发生变化：候选状态变为 generating，且原 trial 技能不再 eligible
    candidateItems[0]['state'] = 'generating';
    skillDetails['git-release-helper']['eligible'] = false;

    // 模拟宿主通过 MethodChannel 发送 skill_assets_changed 事件
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      'cn.com.omnimind.bot/AssistCoreEvent',
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall('skill_assets_changed', {
          'conversationId': 1001,
          'turnId': 'turn-1',
        }),
      ),
      (ByteData? _) {},
    );
    await _pumpStep(tester);

    // 页面静默更新：无需重新进入或显示 full-page spinner，候选状态变为生成中，过期反馈按钮被清除
    expect(find.text('生成中'), findsOneWidget);
    expect(find.byKey(const Key('skill_feedback_btn_git-release-helper')), findsNothing);
  });

  testWidgets('自动技能建议开关失败时严格保持原状态', (tester) async {
    await _pumpPage(tester);
    // 查找开关控件
    final autoSwitch = find.byType(OmniSettingsSwitch);
    expect(autoSwitch, findsWidgets);

    // 配置下一次开关请求失败
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'agentSkillSuggestionsSetEnabled') {
            throw PlatformException(code: 'error', message: '网络或配置错误');
          }
          if (call.method == 'agentSkillSuggestionList') {
            return {
              'enabled': autoSuggestionsEnabled,
              'items': candidateItems,
              'total': candidateItems.length,
              'offset': 0,
              'limit': 20,
            };
          }
          if (call.method == 'agentSkillList') {
            return installedSkills;
          }
          return null;
        });

    // 点击触发 toggle
    final switchFinder = find.descendant(
      of: find.byType(OmniSettingsGroup).first,
      matching: find.byType(OmniSettingsSwitch),
    );
    await tester.tap(switchFinder);
    await _pumpStep(tester);
    // 验证状态回退保持开启，未被置为 false
    final switchWidget = tester.widget<OmniSettingsSwitch>(switchFinder);
    expect(switchWidget.value, isTrue);
  });

  testWidgets(
      '试用技能正反馈在 RouteActivation 门禁下彻底消除死锁：Sheet 正常关闭且列表直接刷新为已验证',
      (tester) async {
    await _pumpPage(tester);

    expect(find.text('试用中'), findsOneWidget);
    final feedbackButton = find.byKey(
      const Key('skill_feedback_btn_git-release-helper'),
    );
    expect(feedbackButton, findsOneWidget);

    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await _pumpStep(tester);
    expect(find.text('试用反馈评价'), findsOneWidget);

    final retainButton = find.byKey(
      const Key('skill_feedback_retain_button'),
    );
    await tester.ensureVisible(retainButton);
    await tester.tap(retainButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认有效并保留'));
    await _pumpStep(tester);

    // 1. 原生 feedback 接口仅调用 1 次
    final feedbackCalls = recordedCalls
        .where((c) => c.method == 'agentSkillFeedback')
        .toList();
    expect(feedbackCalls.length, 1);
    expect(feedbackCalls.first.arguments['action'], 'retain');

    // 2. Sheet 成功自行 pop 退出，不存在旧版本的永久悬留与死锁
    expect(find.text('试用反馈评价'), findsNothing);

    // 3. 宿主在 RouteActivation.ready 恢复后触发 _loadSkills，
    //    列表更新为“已验证”，试用反馈按钮被自动移除
    expect(find.text('已验证'), findsWidgets);
    expect(
      find.byKey(const Key('skill_feedback_btn_git-release-helper')),
      findsNothing,
    );
  });

  testWidgets(
      '试用技能负反馈（retire）在 RouteActivation 门禁下正常关闭 Sheet 并更新为已停用',
      (tester) async {
    await _pumpPage(tester);

    final feedbackButton = find.byKey(
      const Key('skill_feedback_btn_git-release-helper'),
    );
    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await _pumpStep(tester);
    expect(find.text('试用反馈评价'), findsOneWidget);

    final retireButton = find.byKey(
      const Key('skill_feedback_retire_button'),
    );
    await tester.ensureVisible(retireButton);
    await tester.tap(retireButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认无效并停用'));
    await _pumpStep(tester);

    final feedbackCalls = recordedCalls
        .where((c) => c.method == 'agentSkillFeedback')
        .toList();
    expect(feedbackCalls.length, 1);
    expect(feedbackCalls.first.arguments['action'], 'retire');

    expect(find.text('试用反馈评价'), findsNothing);
    expect(find.text('已停用'), findsOneWidget);
    expect(
      find.byKey(const Key('skill_feedback_btn_git-release-helper')),
      findsNothing,
    );
  });

  testWidgets('试用技能反馈失败时严格保持 Sheet 开启且原技能不变', (tester) async {
    forceFeedbackFailure = true;
    await _pumpPage(tester);

    final feedbackButton = find.byKey(
      const Key('skill_feedback_btn_git-release-helper'),
    );
    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await _pumpStep(tester);

    final retainButton = find.byKey(
      const Key('skill_feedback_retain_button'),
    );
    await tester.ensureVisible(retainButton);
    await tester.tap(retainButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认有效并保留'));
    await _pumpStep(tester);

    // 1. 调用了原生接口 1 次并发生异常
    final feedbackCalls = recordedCalls
        .where((c) => c.method == 'agentSkillFeedback')
        .toList();
    expect(feedbackCalls.length, 1);

    // 2. Sheet 严禁提前 pop，依然留在屏幕上，并展示错误文案
    expect(find.text('试用反馈评价'), findsOneWidget);
    expect(find.textContaining('未满足独立后续实际使用凭据'), findsOneWidget);

    // 3. 原技能状态保持 trial 不变
    expect(installedSkills[0]['verificationStatus'], 'trial');
  });

  testWidgets(
      '恢复历史版本在 RouteActivation 门禁下彻底消除死锁：Sheet 正常关闭且页面刷新为上一版本',
      (tester) async {
    skillDetails['git-release-helper']['revision'] = 5;
    await _pumpPage(tester);

    final restoreButton = find.byKey(
      const Key('skill_restore_btn_git-release-helper'),
    );
    await tester.ensureVisible(restoreButton);
    await tester.tap(restoreButton);
    await _pumpStep(tester);
    expect(find.text('恢复技能上一版本'), findsOneWidget);

    final confirmButton = find.byKey(
      const Key('skill_restore_confirm_button'),
    );
    await tester.ensureVisible(confirmButton);
    await tester.tap(confirmButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认恢复'));
    await _pumpStep(tester);

    // 1. 原生恢复接口仅调用 1 次
    final restoreCalls = recordedCalls
        .where((c) => c.method == 'agentSkillRestoreRevision')
        .toList();
    expect(restoreCalls.length, 1);
    expect(restoreCalls.first.arguments['revisionId'], 'rev_v0');

    // 2. Sheet 正常关闭，死锁消除
    expect(find.text('恢复技能上一版本'), findsNothing);

    // 3. 页面列表已刷新，版本号已恢复为 rev_v0
    expect(installedSkills[0]['revisionId'], 'rev_v0');
  });

  testWidgets('恢复历史版本失败时严格保持 Sheet 开启且原技能不变', (tester) async {
    forceRestoreFailure = true;
    skillDetails['git-release-helper']['revision'] = 5;
    await _pumpPage(tester);

    final restoreButton = find.byKey(
      const Key('skill_restore_btn_git-release-helper'),
    );
    await tester.ensureVisible(restoreButton);
    await tester.tap(restoreButton);
    await _pumpStep(tester);

    final confirmButton = find.byKey(
      const Key('skill_restore_confirm_button'),
    );
    await tester.ensureVisible(confirmButton);
    await tester.tap(confirmButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认恢复'));
    await _pumpStep(tester);

    // 1. 原生恢复接口调用 1 次并发生冲突异常
    final restoreCalls = recordedCalls
        .where((c) => c.method == 'agentSkillRestoreRevision')
        .toList();
    expect(restoreCalls.length, 1);

    // 2. Sheet 严禁提前 pop，依然留在屏幕上并显示内容冲突错误
    expect(find.text('恢复技能上一版本'), findsOneWidget);
    expect(find.textContaining('当前磁盘内容与期望哈希不匹配'), findsOneWidget);

    // 3. 技能版本仍保持 rev_v1 未被修改
    expect(installedSkills[0]['revisionId'], 'rev_v1');
  });

  testWidgets(
      '试用反馈在 pending 期间手动 dismiss 且 native 迟到完成时，严格受 route.isActive 保护而不误 pop 底层页面',
      (tester) async {
    pendingFeedbackCompleter = Completer<void>();
    await _pumpPage(tester);

    expect(find.byType(SkillStorePage), findsOneWidget);

    final feedbackButton = find.byKey(
      const Key('skill_feedback_btn_git-release-helper'),
    );
    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await _pumpStep(tester);
    expect(find.text('试用反馈评价'), findsOneWidget);

    final retainButton = find.byKey(
      const Key('skill_feedback_retain_button'),
    );
    await tester.ensureVisible(retainButton);
    await tester.tap(retainButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认有效并保留'));
    await tester.pump();

    expect(
      recordedCalls.where((c) => c.method == 'agentSkillFeedback').length,
      1,
    );

    // 用户在 pending 期间点击遮罩强行关闭 Sheet
    await tester.tapAt(const Offset(10, 10));
    // 推进 50ms 动画，进入 reverse transition 阶段（route.isActive 已为 false，但 State 仍 mounted）
    await tester.pump(const Duration(milliseconds: 50));

    // 此时 native 接口迟到返回完成
    pendingFeedbackCompleter!.complete();
    await tester.pumpAndSettle();

    // 核心断言：底页 SkillStorePage 依然安然无恙，绝未被误 pop！
    expect(find.byType(SkillStorePage), findsOneWidget);
    expect(find.text('试用反馈评价'), findsNothing);
    expect(
      recordedCalls.where((c) => c.method == 'agentSkillFeedback').length,
      1,
    );
  });

  testWidgets(
      '恢复历史版本在 pending 期间手动 dismiss 且 native 迟到完成时，严格受 route.isActive 保护而不误 pop 底层页面',
      (tester) async {
    pendingRestoreCompleter = Completer<void>();
    skillDetails['git-release-helper']['revision'] = 5;
    await _pumpPage(tester);

    expect(find.byType(SkillStorePage), findsOneWidget);

    final restoreButton = find.byKey(
      const Key('skill_restore_btn_git-release-helper'),
    );
    await tester.ensureVisible(restoreButton);
    await tester.tap(restoreButton);
    await _pumpStep(tester);
    expect(find.text('恢复技能上一版本'), findsOneWidget);

    final confirmButton = find.byKey(
      const Key('skill_restore_confirm_button'),
    );
    await tester.ensureVisible(confirmButton);
    await tester.tap(confirmButton);
    await _pumpStep(tester);

    await tester.tap(find.text('确认恢复'));
    await tester.pump();

    expect(
      recordedCalls.where((c) => c.method == 'agentSkillRestoreRevision').length,
      1,
    );

    // 用户在 pending 期间点击遮罩关闭 Sheet
    await tester.tapAt(const Offset(10, 10));
    await tester.pump(const Duration(milliseconds: 50));

    // 此时 native 恢复成功返回
    pendingRestoreCompleter!.complete();
    await tester.pumpAndSettle();

    // 核心断言：底页 SkillStorePage 绝未被误 pop，Sheet 已正常退出
    expect(find.byType(SkillStorePage), findsOneWidget);
    expect(find.text('恢复技能上一版本'), findsNothing);
    expect(
      recordedCalls.where((c) => c.method == 'agentSkillRestoreRevision').length,
      1,
    );
  });

  testWidgets(
      '面板展示期间接收到宿主 skill_assets_changed 事件仅安全同步，绝不重复触发原生 mutation',
      (tester) async {
    await _pumpPage(tester);

    final feedbackButton = find.byKey(
      const Key('skill_feedback_btn_git-release-helper'),
    );
    await tester.ensureVisible(feedbackButton);
    await tester.tap(feedbackButton);
    await _pumpStep(tester);
    expect(find.text('试用反馈评价'), findsOneWidget);

    // 模拟原生侧广播 skill_assets_changed 事件
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      'cn.com.omnimind.bot/AssistCoreEvent',
      const StandardMethodCodec().encodeMethodCall(
        const MethodCall('skill_assets_changed', {
          'conversationId': 2001,
          'turnId': 'turn-2',
        }),
      ),
      (ByteData? _) {},
    );
    await _pumpStep(tester);

    // 验证面板依然保持打开状态，用户正在进行的反馈操作不受干扰
    expect(find.text('试用反馈评价'), findsOneWidget);

    // 验证绝不会因为后台事件误触发第二笔 feedback 或 restore mutation
    expect(
      recordedCalls.where((c) => c.method == 'agentSkillFeedback').length,
      0,
    );
    expect(
      recordedCalls.where((c) => c.method == 'agentSkillRestoreRevision').length,
      0,
    );
  });
}
