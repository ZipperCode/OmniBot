
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/cards/phone_development_result_section.dart';
import 'package:ui/l10n/generated/app_localizations.dart';
import 'package:ui/theme/app_theme.dart';

Widget _wrap(Widget child, {bool dark = false}) => MaterialApp(
      locale: const Locale('zh'),
      theme: dark ? AppTheme.darkTheme : AppTheme.lightTheme,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      home: Scaffold(
        body: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: child,
          ),
        ),
      ),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  setUp(() {
    messenger.setMockMethodCallHandler(
      const MethodChannel('cn.com.omnimind.bot/AssistsMessage'),
      (call) async {
        if (call.method == 'copyToClipboard') {
          return true;
        }
        return null;
      },
    );
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(const MethodChannel('cn.com.omnimind.bot/AssistsMessage'), null);
  });

  group('PhoneDevelopmentResultSection 五阶段数据与三层证据体系', () {
    testWidgets('完整交付五阶段数据且三层证据严格分层展示', (tester) async {
      final cardData = <String, dynamic>{
        'toolName': 'phone_development',
        'status': 'success',
        'action': 'lsposed_deploy',
        'isReadyForBuild': true,
        'architecture': 'aarch64',
        'jdkInstalled': true,
        'jdkVersion': '21.0.2',
        'aapt2Available': true,
        'd8Available': true,
        'apksignerAvailable': true,
        'keytoolAvailable': true,
        'installedPlatforms': [34, 35],
        'projectPath': 'src/xposed_sample',
        'targetPackage': 'com.target.sample',
        'packageName': 'com.module.sample',
        'modulePackageName': 'com.module.sample',
        'dexFileCount': 3,
        'totalClassesCount': 1250,
        'candidateClassesCount': 8,
        'componentsCount': 12,
        'routeUsed': 'no_gradle',
        'apkPath': '/workspace/build/sample-debug.apk',
        'fileSizeBytes': 1024 * 1024 * 2,
        'sha256': 'a1b2c3d4e5f67890123456789abcdef0123456789abcdef0',
        'signatureVerified': true,
        'installed': true,
        'moduleEnabled': true,
        'scopeConfigured': true,
        'targetReloaded': true,
        'configVerified': true,
        'hookVerified': true,
        'frameworkEvidenceObserved': true,
        'frameworkEvidence': {
          'module': 'com.module.sample',
          'target': 'com.target.sample',
          'pid': '12345',
          'tag': 'LSPosed-Bridge',
          'observedAt': '2026-11-14 10:30:00',
        },
      };

      await tester.pumpWidget(_wrap(PhoneDevelopmentResultSection(cardData: cardData)));
      await tester.pumpAndSettle();

      // 验证阶段 1：环境检查就绪
      expect(find.text('环境检查'), findsOneWidget);
      expect(find.text('环境就绪'), findsOneWidget);
      expect(find.text('aarch64'), findsOneWidget);
      expect(find.textContaining('API 34, 35'), findsOneWidget);

      // 验证阶段 2：项目/APK 分析
      expect(find.text('项目 / APK 分析'), findsOneWidget);
      expect(find.text('分析完成'), findsOneWidget);
      expect(find.text('com.target.sample'), findsWidgets);
      expect(find.textContaining('3 个 DEX'), findsOneWidget);
      expect(find.textContaining('1250 个类'), findsOneWidget);

      // 验证阶段 3：构建与签名
      expect(find.text('构建与签名'), findsOneWidget);
      expect(find.text('构建并验签完成'), findsOneWidget);
      expect(find.text('极速无 Gradle 构建'), findsOneWidget);
      expect(find.text('签名已验证 (v2/v1)'), findsOneWidget);
      expect(find.text('打开产物 APK'), findsOneWidget);
      expect(find.text('打开工程目录'), findsOneWidget);

      // 验证阶段 4：部署仅陈述配置完成，绝不冒称功能已实现
      expect(find.text('模块部署与配置'), findsOneWidget);
      expect(find.text('配置完成'), findsOneWidget);
      expect(find.text('模块已启用 (Enabled)'), findsOneWidget);
      expect(find.text('作用域已配置 (Scope Set)'), findsOneWidget);
      expect(find.text('目标应用进程已重载'), findsOneWidget);
      expect(find.textContaining('业务功能尚未在目标应用中核验'), findsOneWidget);

      // 验证阶段 5：三层证据体系严格区分
      expect(find.text('状态验证与三层证据'), findsOneWidget);
      // 第一层：配置读回
      expect(find.text('第一层：配置读回验证'), findsOneWidget);
      expect(find.text('配置已确认'), findsOneWidget);
      // 第二层：框架加载证据（hookVerified 纠正为框架加载证据）
      expect(find.text('第二层：框架加载证据'), findsOneWidget);
      expect(find.text('发现框架加载证据'), findsWidgets);
      expect(find.textContaining('PID 12345'), findsOneWidget);
      expect(find.textContaining('LSPosed-Bridge'), findsOneWidget);
      // 第三层：业务功能效果（必须显示未核验，绝不直接算业务成功）
      expect(find.text('第三层：业务功能效果'), findsOneWidget);
      expect(find.text('未核验（需在目标应用中真实观察业务行为）'), findsOneWidget);
      expect(find.textContaining('具体 Hook 逻辑与业务功能效果必须在目标应用界面或真实行为中验证'), findsOneWidget);
    });

    testWidgets('缺值与未执行阶段绝不冒充成功', (tester) async {
      // 仅有构建失败的卡片数据，其余全空
      final cardData = <String, dynamic>{
        'toolName': 'phone_development',
        'status': 'error',
        'action': 'project_build',
        'success': false,
        'errorMessage': 'Compilation error in HookInit.java: Line 42',
        'signatureVerified': false,
        'isReadyForBuild': false,
        'jdkInstalled': false,
        'aapt2Available': false,
      };

      await tester.pumpWidget(_wrap(PhoneDevelopmentResultSection(cardData: cardData)));
      await tester.pumpAndSettle();

      // 环境显示未就绪并列出缺失项
      expect(find.text('环境未就绪'), findsOneWidget);
      expect(find.textContaining('缺失项：JDK、AAPT2'), findsOneWidget);

      // 构建阶段显示失败与真实错误
      expect(find.text('构建失败'), findsWidgets);
      expect(find.textContaining('HookInit.java: Line 42'), findsOneWidget);

      // 未运行的阶段必须显示“未执行”，缺证据显示“未确认”
      expect(find.text('未执行'), findsWidgets);
      expect(find.text('当前动作未包含项目或 APK 静态逆向分析'), findsOneWidget);
      expect(find.text('当前动作未包含 LSPosed 部署配置'), findsOneWidget);
      expect(find.text('当前动作未包含特权状态验证'), findsOneWidget);
    });
  });

  group('按钮填小万草稿与草稿保护逻辑', () {
    testWidgets('无现有草稿时按钮填入当前小万会话草稿且不立即发送', (tester) async {
      String? filledPrompt;

      final cardData = <String, dynamic>{
        'toolName': 'phone_development',
        'action': 'lsposed_deploy',
        'modulePackageName': 'cn.com.omnimind.validation.hook',
        'targetPackage': 'cn.com.omnimind.validation.target',
        'moduleEnabled': true,
      };

      await tester.pumpWidget(_wrap(
        PhoneDevelopmentResultSection(
          cardData: cardData,
          currentDraft: '',
          conversationOwner: 'xiaowan-acp',
          onFillDraft: (p) async {
            filledPrompt = p;
            return true;
          },
          isProcessing: true, // 运行中
        ),
      ));
      await tester.pumpAndSettle();

      // 检查运行中提示
      expect(find.textContaining('当前任务正在运行中，填入的新请求将在当前任务结束后执行'), findsOneWidget);

      // 点击“重新检查状态”
      final recheckButton = find.text('重新检查状态');
      expect(recheckButton, findsOneWidget);
      await tester.ensureVisible(recheckButton);
      await tester.pumpAndSettle();
      await tester.tap(recheckButton);
      await tester.pumpAndSettle();

      // 验证草稿已被填入，且包含精准包名与 action
      expect(filledPrompt, isNotNull);
      expect(filledPrompt, contains('xposed_control.verify_status'));
      expect(filledPrompt, contains('cn.com.omnimind.validation.hook'));
      expect(filledPrompt, contains('cn.com.omnimind.validation.target'));

      // 点击“停用模块”
      final disableButton = find.text('停用模块');
      expect(disableButton, findsOneWidget);
      await tester.ensureVisible(disableButton);
      await tester.pumpAndSettle();
      await tester.tap(disableButton);
      await tester.pumpAndSettle();

      expect(filledPrompt, contains('xposed_control.module_disable'));
      expect(filledPrompt, contains('cn.com.omnimind.validation.hook'));
    });

    testWidgets('输入框已有非空草稿时禁止静默覆盖，保留输入并提供明确选项', (tester) async {
      String? filledPrompt;
      const existingText = '用户先前未发送的重要提问草稿';

      final cardData = <String, dynamic>{
        'toolName': 'phone_development',
        'modulePackageName': 'com.demo.hook',
        'targetPackage': 'com.demo.target',
      };

      await tester.pumpWidget(_wrap(
        PhoneDevelopmentResultSection(
          cardData: cardData,
          currentDraft: existingText,
          conversationOwner: 'xiaowan-acp',
          onFillDraft: (p) async {
            filledPrompt = p;
            return true;
          },
        ),
      ));
      await tester.pumpAndSettle();

      // 点击“查询模块列表”
      final queryBtn = find.text('查询模块列表');
      expect(queryBtn, findsOneWidget);
      await tester.ensureVisible(queryBtn);
      await tester.pumpAndSettle();
      await tester.tap(queryBtn);
      await tester.pumpAndSettle();

      // 验证弹出未覆盖保护弹窗
      expect(find.text('输入框已有未发草稿'), findsOneWidget);
      expect(find.text(existingText), findsOneWidget);
      expect(find.textContaining('xposed_control.module_list'), findsWidgets);

      // 1. 点击“仅复制指令”，验证 onFillDraft 绝未被触发（现有草稿保持不动）
      final copyBtn = find.text('仅复制指令');
      expect(copyBtn, findsOneWidget);
      await tester.tap(copyBtn);
      await tester.pumpAndSettle();
      expect(filledPrompt, isNull);

      // 2. 再次点击并选择“追加到末尾”
      await tester.ensureVisible(queryBtn);
      await tester.pumpAndSettle();
      await tester.tap(queryBtn);
      await tester.pumpAndSettle();

      final appendBtn = find.text('追加到末尾');
      expect(appendBtn, findsOneWidget);
      await tester.tap(appendBtn);
      await tester.pumpAndSettle();

      expect(filledPrompt, startsWith(existingText));
      expect(filledPrompt, contains('xposed_control.module_list'));

      // 3. 再次点击并选择“替换草稿”
      await tester.ensureVisible(queryBtn);
      await tester.pumpAndSettle();
      await tester.tap(queryBtn);
      await tester.pumpAndSettle();

      final replaceBtn = find.text('替换草稿');
      expect(replaceBtn, findsOneWidget);
      await tester.tap(replaceBtn);
      await tester.pumpAndSettle();

      expect(filledPrompt, isNot(startsWith(existingText)));
      expect(filledPrompt, contains('xposed_control.module_list'));
    });

    testWidgets('配置作用域先展示目标与差异对比弹窗', (tester) async {
      String? filledPrompt;

      final cardData = <String, dynamic>{
        'toolName': 'phone_development',
        'modulePackageName': 'cn.com.hook.pack',
        'targetPackage': 'cn.com.app.target',
      };

      await tester.pumpWidget(_wrap(
        PhoneDevelopmentResultSection(
          cardData: cardData,
          currentDraft: '',
          conversationOwner: 'xiaowan-acp',
          onFillDraft: (p) async {
            filledPrompt = p;
            return true;
          },
        ),
      ));
      await tester.pumpAndSettle();

      // 点击“配置目标作用域”
      final scopeBtn = find.text('配置目标作用域');
      expect(scopeBtn, findsOneWidget);
      await tester.ensureVisible(scopeBtn);
      await tester.pumpAndSettle();
      await tester.tap(scopeBtn);
      await tester.pumpAndSettle();

      // 验证弹出的作用域差异预览
      expect(find.text('配置作用域差异预览'), findsOneWidget);
      expect(find.text('模块包名: cn.com.hook.pack'), findsOneWidget);
      expect(find.text('+ cn.com.app.target (新增目标应用)'), findsOneWidget);

      // 确认填入草稿
      expect(filledPrompt, isNull);
      final fillBtn = find.descendant(
        of: find.byType(AlertDialog),
        matching: find.widgetWithText(ElevatedButton, '填入草稿'),
      );
      expect(fillBtn, findsOneWidget);
      await tester.tap(fillBtn);
      await tester.pumpAndSettle();

      expect(filledPrompt, contains('xposed_control.scope_set'));
      expect(filledPrompt, contains('cn.com.hook.pack'));
      expect(filledPrompt, contains('cn.com.app.target'));
    });

    testWidgets('非小万 Owner 或历史记录模式下禁用特权草稿动作', (tester) async {
      final cardData = <String, dynamic>{
        'toolName': 'phone_development',
        'agentId': 'claude-code',
      };
      bool fillInvoked = false;

      // 1. 非小万 Owner
      await tester.pumpWidget(_wrap(
        PhoneDevelopmentResultSection(
          cardData: cardData,
          conversationOwner: 'claude-code',
          onFillDraft: (p) async {
            fillInvoked = true;
            return true;
          },
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('当前会话所有者为 claude-code；手机开发管理动作仅限“小万”Agent 接入与使用。'), findsOneWidget);
      expect(find.text('重新检查状态'), findsNothing);
      expect(fillInvoked, isFalse);

      // 2. 历史记录模式
      await tester.pumpWidget(_wrap(
        PhoneDevelopmentResultSection(
          cardData: cardData,
          isReadOnly: true,
          conversationOwner: 'xiaowan',
          onFillDraft: (p) async {
            fillInvoked = true;
            return true;
          },
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('历史记录模式：数据为只读快照，特权管理动作已禁用。'), findsOneWidget);
      expect(find.text('重新检查状态'), findsNothing);
      expect(fillInvoked, isFalse);

      // 3. 未知 Owner (null/empty) 必须只复制，绝不默认小万，禁止填入草稿
      await tester.pumpWidget(_wrap(
        PhoneDevelopmentResultSection(
          cardData: cardData,
          conversationOwner: null,
          onFillDraft: (p) async {
            fillInvoked = true;
            return true;
          },
        ),
      ));
      await tester.pumpAndSettle();

      expect(find.textContaining('手机开发管理动作仅限“小万”Agent 接入与使用。'), findsOneWidget);
      expect(find.text('重新检查状态'), findsNothing);
      expect(fillInvoked, isFalse);
    });
  });
}
