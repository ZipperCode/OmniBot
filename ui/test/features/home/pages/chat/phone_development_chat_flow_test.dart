import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:ui/features/home/pages/chat/utils/agent_run_timeline.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_details_sheet.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_group_message.dart';
import 'package:ui/features/home/pages/chat/widgets/agent_run_header.dart';
import 'package:ui/features/home/pages/chat/widgets/chat_widgets.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/models/chat_message_model.dart';
import 'package:ui/theme/app_theme.dart';

Widget _app(Widget body) => MaterialApp(
      locale: const Locale('zh'),
      theme: AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(body: body),
    );

ChatMessageModel _createToolMessage(
  String id, {
  required String modulePkg,
  required String targetPkg,
  String taskId = 'turn-lsp-1',
  String agentId = 'xiaowan-acp',
}) {
  final payload = {
    'action': 'lsposed_deploy',
    'modulePackageName': modulePkg,
    'targetPackage': targetPkg,
    'installed': true,
    'moduleEnabled': true,
    'scopeConfigured': true,
    'targetReloaded': true,
    'configVerified': true,
    'hookVerified': true,
    'frameworkEvidence': {
      'module': modulePkg,
      'target': targetPkg,
      'pid': '25600',
      'tag': 'LSPosed-Bridge',
      'observedAt': '2026-11-14 11:00:00',
    },
  };

  return ChatMessageModel.cardMessage(
    {
      'type': 'agent_tool_summary',
      'uiStyle': 'agent_tool',
      'toolName': 'phone_development',
      'toolTitle': 'phone_development',
      'displayName': 'phone_development',
      'toolType': 'action',
      'status': 'success',
      'action': 'lsposed_deploy',
      'argsJson': jsonEncode({'action': 'lsposed_deploy', 'modulePackageName': modulePkg}),
      'resultPreviewJson': jsonEncode(payload),
      'rawResultJson': jsonEncode(payload),
      'cardId': id,
      'toolCallId': id,
      'taskId': taskId,
      'agentId': agentId,
    },
    id: id,
    streamMeta: {
      'runId': taskId,
      'parentTaskId': taskId,
      'agentId': agentId,
      'kind': 'tool_completed',
      'seq': 10,
      'isFinal': false,
    },
  );
}

AgentRunTimelineGroup _createPhoneDevGroup({
  String taskId = 'turn-lsp-1',
  String agentId = 'xiaowan-acp',
  String modulePkg = 'cn.com.omnimind.validation.hook',
  String targetPkg = 'cn.com.omnimind.validation.target',
}) {
  return AgentRunTimelineGroup(
    taskId: taskId,
    status: AgentRunStatus.finished,
    agentId: agentId,
    startedAt: DateTime(2026, 11, 14, 11),
    finishedAt: DateTime(2026, 11, 14, 11, 0, 10),
    segmentsOldestFirst: [
      AgentRunTimelineSegment.process([
        _createToolMessage(
          'tool-call-lsp-1',
          modulePkg: modulePkg,
          targetPkg: targetPkg,
          taskId: taskId,
          agentId: agentId,
        ),
      ]),
      AgentRunTimelineSegment.visible(
        ChatMessageModel(
          id: 'answer-lsp-1',
          type: 1,
          user: 2,
          content: const {'text': '手机开发任务完成'},
          streamMeta: {
            'runId': taskId,
            'parentTaskId': taskId,
            'agentId': agentId,
            'kind': 'text_snapshot',
            'seq': 20,
            'isFinal': true,
          },
        ),
      ),
    ],
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    LegacyTextLocalizer.setResolvedLocale(const Locale('zh'));
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/AssistsMessage'),
      (call) async {
        if (call.method == 'copyToClipboard') return true;
        return null;
      },
    );
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/AssistsMessage'),
      null,
    );
    LegacyTextLocalizer.clearResolvedLocale();
  });

  testWidgets(
    '从 AgentRunGroupMessage 到 DetailsSheet 到 ToolDetailBody 生产调用链完整回传小万草稿',
    (tester) async {
      String draft = '';
      String? updatedDraft;

      final group = _createPhoneDevGroup();

      await tester.pumpWidget(
        _app(
          StatefulBuilder(
            builder: (context, setState) {
              return AgentRunGroupMessage(
                group: group,
                useAcpPresentation: true,
                currentDraft: draft,
                conversationOwner: 'xiaowan-acp',
                onFillDraft: (p) async {
                  setState(() {
                    draft = p;
                    updatedDraft = p;
                  });
                  return true;
                },
                onBeforeTaskExecute: () async {},
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 用户真实点击展开详情 Sheet
      final summaryHeader = find.byKey(ValueKey('agent-run-summary-${group.taskId}'));
      expect(summaryHeader, findsOneWidget);
      await tester.tap(summaryHeader);
      await tester.pumpAndSettle();

      expect(find.byType(AgentRunDetailsSheet), findsOneWidget);

      // 选择工具汇总进入详情
      final toolRow = find.byKey(const ValueKey('agent-run-details-row-tool-call-lsp-1'));
      expect(toolRow, findsOneWidget);
      await tester.tap(toolRow);
      await tester.pumpAndSettle();

      // 验证五阶段与三层证据展示
      expect(find.text('配置完成'), findsOneWidget);
      expect(find.text('状态验证与三层证据'), findsOneWidget);
      expect(find.text('第一层：配置读回验证'), findsOneWidget);
      expect(find.text('第二层：框架加载证据'), findsOneWidget);
      expect(find.text('第三层：业务功能效果'), findsOneWidget);
      expect(find.text('未核验（需在目标应用中真实观察业务行为）'), findsOneWidget);

      // 点击“重新检查状态”填入草稿
      final recheckBtn = find.text('重新检查状态');
      expect(recheckBtn, findsOneWidget);
      await tester.ensureVisible(recheckBtn);
      await tester.pumpAndSettle();
      await tester.tap(recheckBtn);
      await tester.pumpAndSettle();

      expect(updatedDraft, isNotNull);
      expect(updatedDraft, contains('xposed_control.verify_status'));
      expect(updatedDraft, contains('cn.com.omnimind.validation.hook'));
    },
  );

  testWidgets(
    '生产链路上已有草稿时不覆盖，提供追加与复制选项',
    (tester) async {
      String draft = '先前的待发草稿内容';
      String? finalDraft;

      final group = _createPhoneDevGroup();

      await tester.pumpWidget(
        _app(
          StatefulBuilder(
            builder: (context, setState) {
              return AgentRunGroupMessage(
                group: group,
                useAcpPresentation: true,
                currentDraft: draft,
                conversationOwner: 'xiaowan-acp',
                onFillDraft: (p) async {
                  setState(() {
                    draft = p;
                    finalDraft = p;
                  });
                  return true;
                },
                onBeforeTaskExecute: () async {},
              );
            },
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 用户真实点击展开详情 Sheet
      final summaryHeader = find.byKey(ValueKey('agent-run-summary-${group.taskId}'));
      expect(summaryHeader, findsOneWidget);
      await tester.tap(summaryHeader);
      await tester.pumpAndSettle();
      expect(find.byType(AgentRunDetailsSheet), findsOneWidget);

      final toolRow = find.byKey(const ValueKey('agent-run-details-row-tool-call-lsp-1'));
      expect(toolRow, findsOneWidget);
      await tester.tap(toolRow);
      await tester.pumpAndSettle();

      // 点击“查询模块列表”
      final queryBtn = find.text('查询模块列表');
      expect(queryBtn, findsOneWidget);
      await tester.ensureVisible(queryBtn);
      await tester.pumpAndSettle();
      await tester.tap(queryBtn);
      await tester.pumpAndSettle();

      // 弹出防覆盖弹窗
      expect(find.text('输入框已有未发草稿'), findsOneWidget);
      expect(find.text('先前的待发草稿内容'), findsOneWidget);

      // 1. 选择仅复制指令，现有草稿保持原样
      final copyBtn = find.text('仅复制指令');
      expect(copyBtn, findsOneWidget);
      await tester.tap(copyBtn);
      await tester.pumpAndSettle();
      expect(finalDraft, isNull);

      // 2. 再次点击选择追加到末尾
      await tester.ensureVisible(queryBtn);
      await tester.pumpAndSettle();
      await tester.tap(queryBtn);
      await tester.pumpAndSettle();

      final appendBtn = find.text('追加到末尾');
      expect(appendBtn, findsOneWidget);
      await tester.tap(appendBtn);
      await tester.pumpAndSettle();

      expect(finalDraft, startsWith('先前的待发草稿内容\n\n'));
      expect(finalDraft, contains('xposed_control.module_list'));
    },
  );

  testWidgets(
    '生产链路上非小万 Owner (如 codex) 不展示管理操作',
    (tester) async {
      final group = _createPhoneDevGroup(agentId: 'codex-acp');

      await tester.pumpWidget(
        _app(
          AgentRunGroupMessage(
            group: group,
            useAcpPresentation: true,
            currentDraft: '',
            conversationOwner: 'codex-acp',
            onFillDraft: (p) async => true,
            onBeforeTaskExecute: () async {},
          ),
        ),
      );
      await tester.pumpAndSettle();

      final summaryHeader = find.byKey(ValueKey('agent-run-summary-${group.taskId}'));
      expect(summaryHeader, findsOneWidget);
      await tester.tap(summaryHeader);
      await tester.pumpAndSettle();
      expect(find.byType(AgentRunDetailsSheet), findsOneWidget);

      final toolRow = find.byKey(const ValueKey('agent-run-details-row-tool-call-lsp-1'));
      expect(toolRow, findsOneWidget);
      await tester.tap(toolRow);
      await tester.pumpAndSettle();

      expect(find.textContaining('当前会话所有者为 codex-acp；手机开发管理动作仅限“小万”Agent 接入与使用。'), findsOneWidget);
      expect(find.text('重新检查状态'), findsNothing);
      expect(find.text('查询模块列表'), findsNothing);
    },
  );

  testWidgets(
    '通过 ChatMessageList 生产组件传递 draft 与 activeAcpAgentId 回调',
    (tester) async {
      String draft = '';
      String? updatedDraft;

      final toolMsg = _createToolMessage(
        'tool-call-lsp-1',
        modulePkg: 'cn.com.omnimind.validation.hook',
        targetPkg: 'cn.com.omnimind.validation.target',
      );
      final scrollController = ScrollController();
      addTearDown(scrollController.dispose);

      await tester.pumpWidget(
        _app(
          SizedBox(
            height: 600,
            child: StatefulBuilder(
              builder: (context, setState) {
                return ChatMessageList(
                  messages: [
                    ChatMessageModel(
                      id: 'answer-lsp-1',
                      type: 1,
                      user: 2,
                      content: const {'text': '手机开发任务完成'},
                      streamMeta: const {
                        'runId': 'turn-lsp-1',
                        'parentTaskId': 'turn-lsp-1',
                        'agentId': 'xiaowan-acp',
                        'kind': 'text_snapshot',
                        'seq': 20,
                        'isFinal': true,
                      },
                    ),
                    toolMsg,
                  ],
                  scrollController: scrollController,
                  activeAcpAgentId: 'xiaowan-acp',
                  useAcpPresentation: true,
                  currentDraft: draft,
                  onFillDraft: (p) async {
                    setState(() {
                      draft = p;
                      updatedDraft = p;
                    });
                    return true;
                  },
                  isProcessing: false,
                  onBeforeTaskExecute: () async {},
                );
              },
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();

      // 展开详情 Sheet 并进入工具详情
      final summaryHeader = find.byKey(const ValueKey('agent-run-summary-turn-lsp-1'));
      expect(summaryHeader, findsOneWidget);
      await tester.tap(summaryHeader);
      await tester.pumpAndSettle();

      expect(find.byType(AgentRunDetailsSheet), findsOneWidget);
      final toolRow = find.byKey(const ValueKey('agent-run-details-row-tool-call-lsp-1'));
      expect(toolRow, findsOneWidget);
      await tester.tap(toolRow);
      await tester.pumpAndSettle();

      // 点击“重新检查状态”验证草稿回传
      final recheckBtn = find.text('重新检查状态');
      expect(recheckBtn, findsOneWidget);
      await tester.ensureVisible(recheckBtn);
      await tester.pumpAndSettle();
      await tester.tap(recheckBtn);
      await tester.pumpAndSettle();

      expect(updatedDraft, isNotNull);
      expect(updatedDraft, contains('xposed_control.verify_status'));
      expect(updatedDraft, contains('cn.com.omnimind.validation.hook'));
    },
  );

  testWidgets(
    '生产链路上未知或空 Owner 严格禁止填入草稿并提示仅限小万',
    (tester) async {
      bool fillInvoked = false;
      final group = _createPhoneDevGroup(agentId: 'unknown');

      await tester.pumpWidget(
        _app(
          AgentRunGroupMessage(
            group: group,
            useAcpPresentation: true,
            currentDraft: '',
            conversationOwner: null,
            onFillDraft: (p) async {
              fillInvoked = true;
              return true;
            },
            onBeforeTaskExecute: () async {},
          ),
        ),
      );
      await tester.pumpAndSettle();

      final summaryHeader = find.byKey(ValueKey('agent-run-summary-${group.taskId}'));
      expect(summaryHeader, findsOneWidget);
      await tester.tap(summaryHeader);
      await tester.pumpAndSettle();
      expect(find.byType(AgentRunDetailsSheet), findsOneWidget);

      final toolRow = find.byKey(const ValueKey('agent-run-details-row-tool-call-lsp-1'));
      expect(toolRow, findsOneWidget);
      await tester.tap(toolRow);
      await tester.pumpAndSettle();

      expect(find.textContaining('手机开发管理动作仅限“小万”Agent 接入与使用。'), findsOneWidget);
      expect(find.text('重新检查状态'), findsNothing);
      expect(find.text('查询模块列表'), findsNothing);
      expect(fillInvoked, isFalse);
    },
  );
}
