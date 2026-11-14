import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/omnibot_resource_service.dart';
import 'package:ui/theme/app_colors.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';

const Color _kWarningAmber = Color(0xFFFF9500);
const Color _kSuccessGreen = Color(0xFF2F8F4E);

/// 检查给定的 cardData 是否为手机开发 / LSPosed 工具执行结果
bool isPhoneDevelopmentTool(Map<String, dynamic> cardData) {
  final toolName = (cardData['toolName'] ?? cardData['name'] ?? '').toString().trim().toLowerCase();
  if (toolName == 'phone_development' ||
      toolName == 'xposed_control' ||
      toolName == 'lsposed_deploy' ||
      toolName == 'dev_env_status' ||
      toolName == 'project_build' ||
      toolName == 'apk_analyze') {
    return true;
  }

  // 检查 argsJson 或 rawResultJson/resultPreviewJson 中是否有手机开发特征
  final argsJson = (cardData['argsJson'] ?? '').toString().toLowerCase();
  if (argsJson.contains('"action"') &&
      (argsJson.contains('dev_env_status') ||
          argsJson.contains('project_build') ||
          argsJson.contains('apk_analyze') ||
          argsJson.contains('lsposed_deploy') ||
          argsJson.contains('verify_status'))) {
    return true;
  }

  final rawResultJson = (cardData['rawResultJson'] ?? cardData['resultPreviewJson'] ?? '').toString().toLowerCase();
  if (rawResultJson.contains('configverified') ||
      rawResultJson.contains('hookverified') ||
      rawResultJson.contains('isreadyforbuild') ||
      rawResultJson.contains('signatureverified') ||
      rawResultJson.contains('scopeconfigured') ||
      rawResultJson.contains('moduleenabled')) {
    return true;
  }

  return false;
}

/// 手机开发与 LSPosed 状态管理结果投影组件 (步骤 7)
///
/// 结构化投影五阶段真实字段：
/// 1. 环境检查：isReadyForBuild 及缺失项；
/// 2. 项目/APK分析：projectPath、targetPackage、APK hash/DEX 统计；
/// 3. 构建签名：routeUsed、apkPath、signatureVerified；
/// 4. 部署：installed、moduleEnabled、scopeConfigured、targetReloaded；
/// 5. 验证：configVerified 及框架加载证据（三层证据体系）。
///
/// 严格遵守约束：
/// - configVerified/installed/moduleEnabled/scopeConfigured/targetReloaded 只陈述实际证据；
/// - hookVerified 面向用户一律为“框架加载证据”，业务效果未真实观察显示“未核验”，绝不展示原始 logcat；
/// - 字段未运行显示“未执行”，缺证据显示“未确认”，错误显示真实失败阶段；
/// - 构建产物仅提供打开 APK 与目录，绝不自动 deploy；
/// - 模块列表/作用域/启停/重新检查统一填当前小万会话草稿，走既有 SharedOpenDraft/GoRouter 入口，不新建 Root RPC；
/// - 准确包名/target、scope 差异先预览；已有草稿不覆盖，提供预览复制；
/// - 运行中说明新请求将在当前任务结束后执行；
/// - history 页面仅只读保存结果；
/// - 结果卡动作仅限当前 conversation owner 小万；其他 Agent 不暴露小万子代理或特权操作。
class PhoneDevelopmentResultSection extends StatelessWidget {
  const PhoneDevelopmentResultSection({
    super.key,
    required this.cardData,
    this.isReadOnly = false,
    this.currentDraft,
    this.onFillDraft,
    this.isProcessing = false,
    this.conversationOwner,
  });

  final Map<String, dynamic> cardData;
  final bool isReadOnly;
  final String? currentDraft;
  final Future<bool> Function(String prompt)? onFillDraft;
  final bool isProcessing;
  final String? conversationOwner;

  Map<String, dynamic> _resolvePayload() {
    final result = <String, dynamic>{};

    void mergeMap(dynamic source) {
      if (source is Map) {
        for (final entry in source.entries) {
          result[entry.key.toString()] = entry.value;
        }
      } else if (source is String && source.trim().startsWith('{')) {
        try {
          final decoded = jsonDecode(source);
          if (decoded is Map) {
            for (final entry in decoded.entries) {
              result[entry.key.toString()] = entry.value;
            }
          }
        } catch (_) {}
      }
    }

    mergeMap(cardData['argsJson']);
    mergeMap(cardData['resultPreviewJson']);
    mergeMap(cardData['rawResultJson']);
    mergeMap(cardData['payload']);
    mergeMap(cardData['result']);
    mergeMap(cardData);

    return result;
  }

  bool _isXiaowanOwner(Map<String, dynamic> payload) {
    final owner = conversationOwner;
    if (owner == null || owner.trim().isEmpty) {
      return false; // 未知 owner 绝不默认小万，必须返回 false，仅允许复制
    }
    final normalized = owner.trim().toLowerCase();
    return normalized == 'xiaowan-acp' || normalized == 'xiaowan';
  }

  @override
  Widget build(BuildContext context) {
    final payload = _resolvePayload();
    final isXiaowan = _isXiaowanOwner(payload);
    final cardStatus = (cardData['status'] ?? payload['status'] ?? '').toString().trim();
    final hasGlobalError = cardStatus == 'error';
    final cardSummary = (cardData['summary'] ?? '').toString().trim();
    final rawErrorMessage = (payload['errorMessage'] ?? '').toString().trim();
    final globalSummary = cardSummary.isNotEmpty && cardSummary != rawErrorMessage ? cardSummary : '';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // 模块卡片顶栏概览
        _buildHeaderBanner(context, payload, hasGlobalError, globalSummary),
        const SizedBox(height: 14),

        // 阶段 1：环境检查
        _buildPhase1Environment(context, payload),
        const SizedBox(height: 12),

        // 阶段 2：项目 / APK 静态分析
        _buildPhase2ProjectAnalysis(context, payload),
        const SizedBox(height: 12),

        // 阶段 3：本地构建与签名
        _buildPhase3BuildSignature(context, payload),
        const SizedBox(height: 12),

        // 阶段 4：LSPosed 模块部署
        _buildPhase4Deployment(context, payload),
        const SizedBox(height: 12),

        // 阶段 5：验证与三层证据体系
        _buildPhase5Verification(context, payload),
        const SizedBox(height: 16),

        // 小万特权管理快捷操作栏（仅小万 Owner 且非只读时可用）
        if (!isReadOnly && isXiaowan)
          _buildManagementActions(context, payload)
        else if (!isXiaowan)
          _buildNonXiaowanNotice(context, payload)
        else if (isReadOnly)
          _buildReadOnlyNotice(context),
      ],
    );
  }

  Widget _buildHeaderBanner(
    BuildContext context,
    Map<String, dynamic> payload,
    bool hasGlobalError,
    String summaryText,
  ) {
    final palette = context.omniPalette;
    final action = (payload['action'] ?? '').toString();
    final actionName = _actionDisplayName(action);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                LucideIcons.smartphone,
                size: 18,
                color: palette.accentPrimary,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '手机本地开发与 LSPosed 管理 · $actionName',
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
              ),
              if (hasGlobalError)
                _buildBadge(context, '执行失败', isError: true)
              else if (payload['success'] == true)
                _buildBadge(context, '执行完成', isSuccess: true),
            ],
          ),
          if (hasGlobalError && summaryText.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              summaryText,
              style: const TextStyle(
                fontSize: 12,
                color: AppColors.alertRed,
                height: 1.4,
              ),
            ),
          ],
        ],
      ),
    );
  }

  // --------------------------------------------------------------------------
  // 阶段 1：环境检查
  // --------------------------------------------------------------------------
  Widget _buildPhase1Environment(BuildContext context, Map<String, dynamic> payload) {
    final action = (payload['action'] ?? '').toString();
    final isEnvAction = action == 'dev_env_status' || action == 'status';
    final isReadyForBuild = payload['isReadyForBuild'] as bool?;
    final architecture = payload['architecture']?.toString();
    final jdkInstalled = payload['jdkInstalled'] as bool?;
    final jdkVersion = payload['jdkVersion']?.toString();
    final aapt2Available = payload['aapt2Available'] as bool?;
    final d8Available = payload['d8Available'] as bool?;
    final apksignerAvailable = payload['apksignerAvailable'] as bool?;
    final keytoolAvailable = payload['keytoolAvailable'] as bool?;
    final installedPlatforms = payload['installedPlatforms'];
    final details = payload['details']?.toString();

    final hasExecuted = isEnvAction || isReadyForBuild != null || architecture != null;

    return _buildPhaseCard(
      context: context,
      phaseNumber: 1,
      phaseTitle: '环境检查',
      badgeText: !hasExecuted
          ? '未执行'
          : (isReadyForBuild == true ? '环境就绪' : (isReadyForBuild == false ? '环境未就绪' : '未确认')),
      isSuccess: isReadyForBuild == true,
      isWarning: hasExecuted && isReadyForBuild == false,
      isUnexecuted: !hasExecuted,
      child: !hasExecuted
          ? _buildUnexecutedHint(context, '当前动作未包含开发环境检查')
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildDataRow(context, '芯片架构', architecture ?? '未确认'),
                _buildDataRow(
                  context,
                  'JDK 环境',
                  jdkInstalled == true ? (jdkVersion != null ? '已安装 ($jdkVersion)' : '已就绪') : (jdkInstalled == false ? '未安装' : '未确认'),
                  isSuccess: jdkInstalled == true,
                  isError: jdkInstalled == false,
                ),
                _buildDataRow(
                  context,
                  '编译工具链',
                  'AAPT2: ${_boolLabel(aapt2Available)} · D8: ${_boolLabel(d8Available)} · apksigner: ${_boolLabel(apksignerAvailable)} · keytool: ${_boolLabel(keytoolAvailable)}',
                ),
                _buildDataRow(
                  context,
                  'SDK 平台 API',
                  installedPlatforms is List && installedPlatforms.isNotEmpty
                      ? '已安装 API ${installedPlatforms.join(", ")}'
                      : (installedPlatforms != null ? '未检测到有效平台' : '未确认'),
                ),
                if (isReadyForBuild == false) ...[
                  const SizedBox(height: 6),
                  Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: _kWarningAmber.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: _kWarningAmber.withValues(alpha: 0.3)),
                    ),
                    child: Text(
                      '缺失项：${_computeMissingEnvItems(jdkInstalled, aapt2Available, d8Available, apksignerAvailable, installedPlatforms, details)}',
                      style: const TextStyle(
                        fontSize: 11.5,
                        color: _kWarningAmber,
                        height: 1.4,
                      ),
                    ),
                  ),
                ],
              ],
            ),
    );
  }

  // --------------------------------------------------------------------------
  // 阶段 2：项目 / APK 静态分析
  // --------------------------------------------------------------------------
  Widget _buildPhase2ProjectAnalysis(BuildContext context, Map<String, dynamic> payload) {
    final action = (payload['action'] ?? '').toString();
    final isAnalysisAction = action == 'apk_analyze' ||
        action == 'analyze' ||
        action == 'project_create' ||
        action == 'project_import' ||
        action == 'probe_module_generate';

    final projectPath = payload['projectPath']?.toString() ?? payload['sourcePath']?.toString();
    final targetPackage = payload['targetPackage']?.toString() ??
        payload['packageName']?.toString() ??
        payload['detectedPackageName']?.toString();
    final dexFileCount = payload['dexFileCount'];
    final totalClassesCount = payload['totalClassesCount'];
    final candidateClassesCount = payload['candidateClassesCount'];
    final componentsCount = payload['componentsCount'];
    final targetSdkVersion = payload['targetSdkVersion'];
    final sha256 = payload['sha256']?.toString();

    final hasExecuted = isAnalysisAction || dexFileCount != null || totalClassesCount != null || targetPackage != null;

    return _buildPhaseCard(
      context: context,
      phaseNumber: 2,
      phaseTitle: '项目 / APK 分析',
      badgeText: !hasExecuted
          ? '未执行'
          : (payload['success'] == false ? '分析失败' : (targetPackage != null ? '分析完成' : '未确认')),
      isSuccess: hasExecuted && payload['success'] != false && targetPackage != null,
      isWarning: hasExecuted && payload['success'] == false,
      isUnexecuted: !hasExecuted,
      child: !hasExecuted
          ? _buildUnexecutedHint(context, '当前动作未包含项目或 APK 静态逆向分析')
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (projectPath != null && projectPath.isNotEmpty)
                  _buildDataRow(context, '工程路径', projectPath, isMonospace: true),
                _buildDataRow(context, '目标包名', targetPackage ?? '未确认', isMonospace: true),
                if (targetSdkVersion != null)
                  _buildDataRow(context, 'Target SDK', 'API $targetSdkVersion'),
                _buildDataRow(
                  context,
                  'DEX 符号统计',
                  dexFileCount != null
                      ? '$dexFileCount 个 DEX · $totalClassesCount 个类 · 候选 Hook 类 $candidateClassesCount 个'
                      : '未统计',
                ),
                if (componentsCount != null)
                  _buildDataRow(context, '组件总数', '$componentsCount 个 AndroidManifest 组件'),
                if (sha256 != null && sha256.isNotEmpty)
                  _buildDataRow(context, 'APK 哈希', _truncateHash(sha256), isMonospace: true),
              ],
            ),
    );
  }

  // --------------------------------------------------------------------------
  // 阶段 3：本地构建与签名
  // --------------------------------------------------------------------------
  Widget _buildPhase3BuildSignature(BuildContext context, Map<String, dynamic> payload) {
    final action = (payload['action'] ?? '').toString();
    final isBuildAction = action == 'project_build' || action == 'build';
    final routeUsed = payload['routeUsed']?.toString();
    final apkPath = payload['apkPath']?.toString();
    final signatureVerified = payload['signatureVerified'] as bool?;
    final fileSizeBytes = payload['fileSizeBytes'];
    final sha256 = payload['sha256']?.toString();
    final buildSuccess = payload['success'] as bool?;
    final errorMessage = payload['errorMessage']?.toString();

    final hasExecuted = isBuildAction || routeUsed != null || (signatureVerified != null && action != 'lsposed_deploy');

    return _buildPhaseCard(
      context: context,
      phaseNumber: 3,
      phaseTitle: '构建与签名',
      badgeText: !hasExecuted
          ? '未执行'
          : (buildSuccess == false
              ? '构建失败'
              : (signatureVerified == true ? '构建并验签完成' : (signatureVerified == false ? '签名未通过' : '未确认'))),
      isSuccess: hasExecuted && buildSuccess != false && signatureVerified == true,
      isWarning: hasExecuted && (buildSuccess == false || signatureVerified == false),
      isUnexecuted: !hasExecuted,
      child: !hasExecuted
          ? _buildUnexecutedHint(context, '当前动作未包含本地构建与签名')
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildDataRow(context, '构建路线', _routeDisplayName(routeUsed)),
                _buildDataRow(
                  context,
                  '签名验证',
                  signatureVerified == true ? '签名已验证 (v2/v1)' : (signatureVerified == false ? '签名校验未通过' : '未确认'),
                  isSuccess: signatureVerified == true,
                  isError: signatureVerified == false,
                ),
                if (apkPath != null && apkPath.isNotEmpty)
                  _buildDataRow(context, '产物路径', apkPath, isMonospace: true),
                if (fileSizeBytes != null)
                  _buildDataRow(context, '产物大小', _formatFileSize(fileSizeBytes)),
                if (sha256 != null && sha256.isNotEmpty)
                  _buildDataRow(context, 'SHA-256', _truncateHash(sha256), isMonospace: true),
                if (errorMessage != null && errorMessage.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(
                      '错误：$errorMessage',
                      style: const TextStyle(fontSize: 12, color: AppColors.alertRed),
                    ),
                  ),
                // 构建成功后仅提供打开 APK 与目录，绝不自动 deploy
                if (apkPath != null && apkPath.isNotEmpty && buildSuccess != false) ...[
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton.icon(
                        icon: const Icon(LucideIcons.fileCode, size: 14),
                        label: const Text('打开产物 APK', style: TextStyle(fontSize: 12)),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, 36),
                          padding: const EdgeInsets.symmetric(horizontal: 10),
                        ),
                        onPressed: () => unawaited(OmnibotResourceService.openFilePath(apkPath)),
                      ),
                      OutlinedButton.icon(
                        icon: const Icon(LucideIcons.folderOpen, size: 14),
                        label: const Text('打开工程目录', style: TextStyle(fontSize: 12)),
                        style: OutlinedButton.styleFrom(
                          minimumSize: const Size(0, 36),
                          padding: const EdgeInsets.symmetric(horizontal: 10),
                        ),
                        onPressed: () {
                          final projectDir = payload['projectPath']?.toString();
                          unawaited(OmnibotResourceService.openWorkspace(absolutePath: projectDir));
                        },
                      ),
                    ],
                  ),
                ],
              ],
            ),
    );
  }

  // --------------------------------------------------------------------------
  // 阶段 4：LSPosed 模块部署
  // --------------------------------------------------------------------------
  Widget _buildPhase4Deployment(BuildContext context, Map<String, dynamic> payload) {
    final action = (payload['action'] ?? '').toString();
    final isDeployAction = action == 'lsposed_deploy' || action == 'deploy';
    final installed = payload['installed'] as bool?;
    final moduleEnabled = payload['moduleEnabled'] as bool?;
    final scopeConfigured = payload['scopeConfigured'] as bool?;
    final targetReloaded = payload['targetReloaded'] as bool?;
    final modulePackageName = payload['modulePackageName']?.toString();
    final targetPackage = payload['targetPackage']?.toString();

    final hasExecuted = isDeployAction || installed != null || moduleEnabled != null || scopeConfigured != null;

    final allConfigured = installed == true && moduleEnabled == true && scopeConfigured == true;

    return _buildPhaseCard(
      context: context,
      phaseNumber: 4,
      phaseTitle: '模块部署与配置',
      badgeText: !hasExecuted
          ? '未执行'
          : (allConfigured ? '配置完成' : '配置未完成'),
      isSuccess: hasExecuted && allConfigured,
      isWarning: hasExecuted && !allConfigured,
      isUnexecuted: !hasExecuted,
      child: !hasExecuted
          ? _buildUnexecutedHint(context, '当前动作未包含 LSPosed 部署配置')
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildDataRow(
                  context,
                  '静默安装',
                  installed == true ? '已安装' : (installed == false ? '未安装' : '未确认'),
                  isSuccess: installed == true,
                  isError: installed == false,
                ),
                _buildDataRow(
                  context,
                  '模块启用',
                  moduleEnabled == true ? '模块已启用 (Enabled)' : (moduleEnabled == false ? '模块未启用' : '未确认'),
                  isSuccess: moduleEnabled == true,
                  isError: moduleEnabled == false,
                ),
                _buildDataRow(
                  context,
                  '作用域配置',
                  scopeConfigured == true ? '作用域已配置 (Scope Set)' : (scopeConfigured == false ? '未配置入作用域' : '未确认'),
                  isSuccess: scopeConfigured == true,
                  isError: scopeConfigured == false,
                ),
                _buildDataRow(
                  context,
                  '目标重载',
                  targetReloaded == true ? '目标应用进程已重载' : (targetReloaded == false ? '未重载目标应用' : '未确认'),
                ),
                if (modulePackageName != null)
                  _buildDataRow(context, '模块包名', modulePackageName, isMonospace: true),
                if (targetPackage != null)
                  _buildDataRow(context, '目标应用', targetPackage, isMonospace: true),
                const SizedBox(height: 4),
                _buildInlineNote(context, '部署结果显示配置已完成；业务功能尚未在目标应用中核验。'),
              ],
            ),
    );
  }

  // --------------------------------------------------------------------------
  // 阶段 5：验证与三层证据体系 (关键规范)
  // --------------------------------------------------------------------------
  Widget _buildPhase5Verification(BuildContext context, Map<String, dynamic> payload) {
    final action = (payload['action'] ?? '').toString();
    final isVerifyAction = action == 'verify_status' || action == 'lsposed_deploy';
    final configVerified = payload['configVerified'] as bool?;
    final hookVerified = (payload['hookVerified'] ?? payload['frameworkEvidenceObserved']) as bool?;
    final frameworkEvidence = payload['frameworkEvidence'] is Map ? payload['frameworkEvidence'] as Map : null;
    final hookEvidence = payload['hookEvidence']?.toString();

    final hasExecuted = isVerifyAction || configVerified != null || hookVerified != null || frameworkEvidence != null;

    final badgeLabel = !hasExecuted
        ? '未执行'
        : (hookVerified == true
            ? '发现框架加载证据'
            : (configVerified == true ? '配置已确认/无加载证据' : '配置未确认'));

    return _buildPhaseCard(
      context: context,
      phaseNumber: 5,
      phaseTitle: '状态验证与三层证据',
      badgeText: badgeLabel,
      isSuccess: hasExecuted && hookVerified == true,
      isWarning: hasExecuted && configVerified == true && hookVerified != true,
      isUnexecuted: !hasExecuted,
      child: !hasExecuted
          ? _buildUnexecutedHint(context, '当前动作未包含特权状态验证')
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 第一层：配置读回验证
                _buildEvidenceLayerItem(
                  context: context,
                  layerTitle: '第一层：配置读回验证',
                  statusText: configVerified == true ? '配置已确认' : (configVerified == false ? '配置未确认' : '未确认'),
                  isConfirmed: configVerified == true,
                  detail: configVerified == true
                      ? '通过 Vector CLI/DB 读回确认：模块处于 enabled 状态且目标应用在当前用户作用域中。'
                      : '模块未启用或目标应用未在当前作用域。',
                ),
                const SizedBox(height: 10),

                // 第二层：框架加载证据 (Hook Verified 纠正为框架加载证据，不等于业务效果)
                _buildEvidenceLayerItem(
                  context: context,
                  layerTitle: '第二层：框架加载证据',
                  statusText: hookVerified == true ? '发现框架加载证据' : (hookVerified == false ? '未观察到加载证据' : '未确认'),
                  isConfirmed: hookVerified == true,
                  detail: hookVerified == true
                      ? '已在目标真实存活 PID 的日志中观察到规范白名单 Tag 的框架注入记录。'
                      : '目标进程未启动或未观察到框架加载记录（不读取整段 logcat）。',
                  evidenceMap: frameworkEvidence,
                  hookEvidenceText: hookEvidence,
                ),
                const SizedBox(height: 10),

                // 第三层：业务功能效果 (必须明确核验尚未进行，不冒称成功)
                _buildEvidenceLayerItem(
                  context: context,
                  layerTitle: '第三层：业务功能效果',
                  statusText: '未核验（需在目标应用中真实观察业务行为）',
                  isConfirmed: false,
                  detail: '框架加载仅证明注入成功；具体 Hook 逻辑与业务功能效果必须在目标应用界面或真实行为中验证后方可确认。',
                  isNoticeOnly: true,
                ),
              ],
            ),
    );
  }

  // --------------------------------------------------------------------------
  // 管理操作区域（填入小万会话草稿，不自建 RPC，不自动 su）
  // --------------------------------------------------------------------------
  Widget _buildManagementActions(BuildContext context, Map<String, dynamic> payload) {
    final modulePackage = payload['modulePackageName']?.toString() ??
        payload['packageName']?.toString() ??
        'cn.com.omnimind.bot';
    final targetPackage = payload['targetPackage']?.toString() ?? '*';
    final moduleEnabled = payload['moduleEnabled'] as bool?;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: context.omniPalette.surfaceSecondary,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: context.omniPalette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(LucideIcons.terminal, size: 16, color: context.omniPalette.accentPrimary),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '小万会话管理动作',
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: context.omniPalette.textPrimary,
                  ),
                ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                decoration: BoxDecoration(
                  color: context.omniPalette.surfacePrimary,
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  '填入草稿',
                  style: TextStyle(
                    fontSize: 10.5,
                    color: context.omniPalette.textSecondary,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            '点击按钮将操作指令填入当前小万会话草稿，用户发送后通过 ACP 流程获取授权并执行，不在前端直调 Root。',
            style: TextStyle(
              fontSize: 11.5,
              color: context.omniPalette.textSecondary,
              height: 1.4,
            ),
          ),
          if (isProcessing) ...[
            const SizedBox(height: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: _kWarningAmber.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(6),
              ),
              child: const Text(
                '提示：当前任务正在运行中，填入的新请求将在当前任务结束后执行。',
                style: TextStyle(fontSize: 11, color: _kWarningAmber),
              ),
            ),
          ],
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _buildActionButton(
                context: context,
                label: '重新检查状态',
                icon: LucideIcons.refreshCw,
                onPressed: () => _handleDraftAction(
                  context,
                  prompt: '请通过 android_privileged_action 执行 xposed_control.verify_status，检查模块 $modulePackage 对目标应用 $targetPackage 的配置读回与框架加载状态。',
                  title: '重新检查状态',
                ),
              ),
              _buildActionButton(
                context: context,
                label: '查询模块列表',
                icon: LucideIcons.list,
                onPressed: () => _handleDraftAction(
                  context,
                  prompt: '请通过 android_privileged_action 执行 xposed_control.module_list，查询当前已安装的 LSPosed 模块列表与启用状态。',
                  title: '查询模块列表',
                ),
              ),
              _buildActionButton(
                context: context,
                label: '查询生效范围',
                icon: LucideIcons.search,
                onPressed: () => _handleDraftAction(
                  context,
                  prompt: '请通过 android_privileged_action 执行 xposed_control.scope_get，查询模块 $modulePackage 的当前生效作用域。',
                  title: '查询作用域',
                ),
              ),
              _buildActionButton(
                context: context,
                label: moduleEnabled == true ? '停用模块' : '启用模块',
                icon: moduleEnabled == true ? LucideIcons.powerOff : LucideIcons.power,
                onPressed: () => _handleDraftAction(
                  context,
                  prompt: moduleEnabled == true
                      ? '请通过 android_privileged_action 执行 xposed_control.module_disable，停用模块 $modulePackage。'
                      : '请通过 android_privileged_action 执行 xposed_control.module_enable，启用模块 $modulePackage。',
                  title: moduleEnabled == true ? '停用模块' : '启用模块',
                ),
              ),
              _buildActionButton(
                context: context,
                label: '配置目标作用域',
                icon: LucideIcons.target,
                onPressed: () => _showScopeDiffPreviewDialog(
                  context,
                  modulePackage: modulePackage,
                  targetPackage: targetPackage,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildActionButton({
    required BuildContext context,
    required String label,
    required IconData icon,
    required VoidCallback onPressed,
  }) {
    return OutlinedButton.icon(
      icon: Icon(icon, size: 14),
      label: Text(label, style: const TextStyle(fontSize: 12)),
      style: OutlinedButton.styleFrom(
        foregroundColor: context.omniPalette.textPrimary,
        side: BorderSide(color: context.omniPalette.borderStrong),
        minimumSize: const Size(0, 36),
        padding: const EdgeInsets.symmetric(horizontal: 10),
      ),
      onPressed: onPressed,
    );
  }

  // --------------------------------------------------------------------------
  // 作用域变更差异先预览
  // --------------------------------------------------------------------------
  void _showScopeDiffPreviewDialog(
    BuildContext context, {
    required String modulePackage,
    required String targetPackage,
  }) {
    final palette = context.omniPalette;
    showDialog<void>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          backgroundColor: palette.surfacePrimary,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: Row(
            children: [
              Icon(LucideIcons.target, size: 18, color: palette.accentPrimary),
              const SizedBox(width: 8),
              const Text('配置作用域差异预览', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '准确目标包名与作用域差异如下：',
                style: TextStyle(fontSize: 12.5, color: palette.textSecondary),
              ),
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: palette.borderSubtle),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('模块包名: $modulePackage', style: const TextStyle(fontSize: 12, fontFamily: 'monospace')),
                    const SizedBox(height: 4),
                    Text('当前作用域: 尚未生效或未配置', style: TextStyle(fontSize: 12, color: palette.textTertiary)),
                    const SizedBox(height: 4),
                    Text(
                      '+ $targetPackage (新增目标应用)',
                      style: const TextStyle(
                        fontSize: 12,
                        fontFamily: 'monospace',
                        fontWeight: FontWeight.w600,
                        color: Color(0xFF2F8F4E),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              Text(
                '确认后将把精确操作指令填入当前小万会话草稿，待用户主动发送后由小万特权工具执行。',
                style: TextStyle(fontSize: 11.5, color: palette.textSecondary, height: 1.4),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('取消'),
            ),
            ElevatedButton(
              onPressed: () {
                Navigator.of(dialogContext).pop();
                _handleDraftAction(
                  context,
                  prompt: '请通过 android_privileged_action 执行 xposed_control.scope_set，将目标应用 $targetPackage 加入模块 $modulePackage 的生效作用域。',
                  title: '配置作用域',
                );
              },
              child: const Text('填入草稿'),
            ),
          ],
        );
      },
    );
  }

  // --------------------------------------------------------------------------
  // 草稿填入与已有草稿不覆盖预览逻辑
  // --------------------------------------------------------------------------
  void _handleDraftAction(
    BuildContext context, {
    required String prompt,
    required String title,
  }) {
    final payload = _resolvePayload();
    final isXiaowan = _isXiaowanOwner(payload);

    // 1. 非小万会话或无活跃小万草稿接收通道：绝不偷切 owner，仅展示可复制文本
    if (!isXiaowan || onFillDraft == null) {
      _showCopyOnlyDialog(
        context,
        prompt: prompt,
        title: title,
        reason: !isXiaowan
            ? '当前会话由 ${conversationOwner ?? "其他 Agent"} 持有。手机开发特权管理仅限小万 Agent。已为您生成操作指令，可复制后在小万会话中使用：'
            : '当前未处于活跃小万对话输入界面。已为您生成操作指令，可复制后在小万会话中使用：',
      );
      return;
    }

    // 2. 存在非空草稿：绝不自动覆盖，保留现有输入并提供明确选项
    final draft = currentDraft?.trim() ?? '';
    if (draft.isNotEmpty) {
      _showExistingDraftChoiceDialog(
        context,
        prompt: prompt,
        title: title,
        existingDraft: draft,
      );
      return;
    }

    // 3. 活跃小万且无草稿：填入当前草稿，不立即 send
    onFillDraft!(prompt);
    final notice = isProcessing
        ? '已填入小万会话草稿；当前任务运行中，新请求将在当前任务结束后执行。'
        : '已填入小万会话草稿。';
    showToast(notice, type: ToastType.success);
  }

  void _showCopyOnlyDialog(
    BuildContext context, {
    required String prompt,
    required String title,
    required String reason,
  }) {
    final palette = context.omniPalette;
    showDialog<void>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          backgroundColor: palette.surfacePrimary,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: Row(
            children: [
              Icon(LucideIcons.copy, size: 18, color: palette.accentPrimary),
              const SizedBox(width: 8),
              Text(title, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                reason,
                style: TextStyle(fontSize: 12.5, color: palette.textSecondary, height: 1.45),
              ),
              const SizedBox(height: 10),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: palette.borderSubtle),
                ),
                child: SelectableText(
                  prompt,
                  style: TextStyle(fontSize: 12, color: palette.textPrimary, height: 1.4),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('关闭'),
            ),
            ElevatedButton.icon(
              icon: const Icon(LucideIcons.copy, size: 14),
              label: const Text('复制指令'),
              onPressed: () async {
                Navigator.of(dialogContext).pop();
                final success = await AssistsMessageService.copyToClipboard(prompt);
                showToast(
                  success ? '指令已复制到剪贴板' : '复制失败',
                  type: success ? ToastType.success : ToastType.error,
                );
              },
            ),
          ],
        );
      },
    );
  }

  void _showExistingDraftChoiceDialog(
    BuildContext context, {
    required String prompt,
    required String title,
    required String existingDraft,
  }) {
    final palette = context.omniPalette;
    showDialog<void>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          backgroundColor: palette.surfacePrimary,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
          title: const Row(
            children: [
              Icon(LucideIcons.alertCircle, size: 18, color: _kWarningAmber),
              SizedBox(width: 8),
              Text('输入框已有未发草稿', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            ],
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '当前小万输入框已有尚未发送的草稿。为防止丢失输入，未直接覆盖现有内容。请明确选择处理方式：',
                style: TextStyle(fontSize: 12.5, color: palette.textSecondary, height: 1.45),
              ),
              const SizedBox(height: 10),
              Text('现有草稿：', style: TextStyle(fontSize: 11.5, color: palette.textTertiary)),
              const SizedBox(height: 4),
              Container(
                width: double.infinity,
                constraints: const BoxConstraints(maxHeight: 60),
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: SingleChildScrollView(
                  child: Text(
                    existingDraft,
                    style: TextStyle(fontSize: 11.5, color: palette.textSecondary),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              Text('新操作指令：', style: TextStyle(fontSize: 11.5, color: palette.textTertiary)),
              const SizedBox(height: 4),
              Container(
                width: double.infinity,
                constraints: const BoxConstraints(maxHeight: 70),
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: palette.borderSubtle),
                ),
                child: SingleChildScrollView(
                  child: SelectableText(
                    prompt,
                    style: TextStyle(fontSize: 11.5, color: palette.textPrimary),
                  ),
                ),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('取消'),
            ),
            OutlinedButton(
              onPressed: () async {
                Navigator.of(dialogContext).pop();
                final success = await AssistsMessageService.copyToClipboard(prompt);
                showToast(
                  success ? '指令已复制（现有草稿保持原样）' : '复制失败',
                  type: success ? ToastType.success : ToastType.error,
                );
              },
              child: const Text('仅复制指令'),
            ),
            OutlinedButton(
              onPressed: () {
                Navigator.of(dialogContext).pop();
                onFillDraft!('$existingDraft\n\n$prompt');
                showToast('已追加至现有草稿末尾', type: ToastType.success);
              },
              child: const Text('追加到末尾'),
            ),
            ElevatedButton(
              onPressed: () {
                Navigator.of(dialogContext).pop();
                onFillDraft!(prompt);
                showToast('已替换输入框草稿', type: ToastType.success);
              },
              child: const Text('替换草稿'),
            ),
          ],
        );
      },
    );
  }


  // --------------------------------------------------------------------------
  // 辅助提示与卡片构件
  // --------------------------------------------------------------------------
  Widget _buildNonXiaowanNotice(BuildContext context, Map<String, dynamic> payload) {
    final palette = context.omniPalette;
    final owner = conversationOwner ?? payload['agentId']?.toString() ?? '其他 Agent';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(LucideIcons.shieldAlert, size: 16, color: palette.textTertiary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '当前会话所有者为 $owner；手机开发管理动作仅限“小万”Agent 接入与使用。',
              style: TextStyle(fontSize: 11.5, color: palette.textTertiary),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildReadOnlyNotice(BuildContext context) {
    final palette = context.omniPalette;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(LucideIcons.history, size: 15, color: palette.textTertiary),
          const SizedBox(width: 8),
          Text(
            '历史记录模式：数据为只读快照，特权管理动作已禁用。',
            style: TextStyle(fontSize: 11.5, color: palette.textTertiary),
          ),
        ],
      ),
    );
  }

  Widget _buildPhaseCard({
    required BuildContext context,
    required int phaseNumber,
    required String phaseTitle,
    required String badgeText,
    required bool isSuccess,
    required bool isWarning,
    required bool isUnexecuted,
    required Widget child,
  }) {
    final palette = context.omniPalette;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: isWarning
              ? _kWarningAmber.withValues(alpha: 0.3)
              : palette.borderSubtle,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 20,
                height: 20,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: isSuccess
                      ? _kSuccessGreen.withValues(alpha: 0.15)
                      : (isWarning
                          ? _kWarningAmber.withValues(alpha: 0.15)
                          : palette.borderSubtle),
                  shape: BoxShape.circle,
                ),
                child: Text(
                  '$phaseNumber',
                  style: TextStyle(
                    fontSize: 11,
                    fontWeight: FontWeight.w600,
                    color: isSuccess
                        ? _kSuccessGreen
                        : (isWarning ? _kWarningAmber : palette.textTertiary),
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  phaseTitle,
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
              ),
              _buildBadge(
                context,
                badgeText,
                isSuccess: isSuccess,
                isWarning: isWarning,
                isUnexecuted: isUnexecuted,
              ),
            ],
          ),
          const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }

  Widget _buildEvidenceLayerItem({
    required BuildContext context,
    required String layerTitle,
    required String statusText,
    required bool isConfirmed,
    required String detail,
    Map? evidenceMap,
    String? hookEvidenceText,
    bool isNoticeOnly = false,
  }) {
    final palette = context.omniPalette;

    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: palette.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  layerTitle,
                  style: TextStyle(
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                    color: palette.textPrimary,
                  ),
                ),
              ),
              const SizedBox(width: 8),
              Flexible(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: isNoticeOnly
                        ? palette.borderSubtle
                        : (isConfirmed
                            ? _kSuccessGreen.withValues(alpha: 0.12)
                            : _kWarningAmber.withValues(alpha: 0.12)),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    statusText,
                    style: TextStyle(
                      fontSize: 10.5,
                      fontWeight: FontWeight.w500,
                      color: isNoticeOnly
                          ? palette.textTertiary
                          : (isConfirmed ? _kSuccessGreen : _kWarningAmber),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text(
            detail,
            style: TextStyle(fontSize: 11.5, color: palette.textSecondary, height: 1.4),
          ),
          if (evidenceMap != null) ...[
            const SizedBox(height: 6),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: palette.surfaceSecondary,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '脱敏框架证据：Tag [${evidenceMap["tag"] ?? "LSPosed-Bridge"}] · PID ${evidenceMap["pid"] ?? "-"}',
                    style: const TextStyle(fontSize: 11, fontFamily: 'monospace', fontWeight: FontWeight.w500),
                  ),
                  Text(
                    '目标应用：${evidenceMap["target"] ?? "-"} · 观察时间：${evidenceMap["observedAt"] ?? "-"}',
                    style: TextStyle(fontSize: 10.5, color: palette.textSecondary),
                  ),
                ],
              ),
            ),
          ] else if (hookEvidenceText != null && hookEvidenceText.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              '证据摘要: $hookEvidenceText',
              style: TextStyle(fontSize: 10.5, fontFamily: 'monospace', color: palette.textTertiary),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildDataRow(
    BuildContext context,
    String label,
    String value, {
    bool isMonospace = false,
    bool isSuccess = false,
    bool isError = false,
  }) {
    final palette = context.omniPalette;
    Color valueColor = palette.textPrimary;
    if (isSuccess) valueColor = const Color(0xFF2F8F4E);
    if (isError) valueColor = AppColors.alertRed;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2.5),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 86,
            child: Text(
              label,
              style: TextStyle(fontSize: 12, color: palette.textSecondary),
            ),
          ),
          Expanded(
            child: SelectableText(
              value,
              style: TextStyle(
                fontSize: 12,
                color: valueColor,
                fontFamily: isMonospace ? 'monospace' : null,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBadge(
    BuildContext context,
    String text, {
    bool isSuccess = false,
    bool isWarning = false,
    bool isError = false,
    bool isUnexecuted = false,
  }) {
    Color bg = context.omniPalette.borderSubtle;
    Color fg = context.omniPalette.textTertiary;

    if (isError) {
      bg = AppColors.alertRed.withValues(alpha: 0.12);
      fg = AppColors.alertRed;
    } else if (isSuccess) {
      bg = _kSuccessGreen.withValues(alpha: 0.12);
      fg = _kSuccessGreen;
    } else if (isWarning) {
      bg = _kWarningAmber.withValues(alpha: 0.12);
      fg = _kWarningAmber;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2.5),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w500,
          color: fg,
        ),
      ),
    );
  }

  Widget _buildUnexecutedHint(BuildContext context, String hint) {
    return Text(
      hint,
      style: TextStyle(
        fontSize: 11.5,
        color: context.omniPalette.textTertiary,
        fontStyle: FontStyle.italic,
      ),
    );
  }

  Widget _buildInlineNote(BuildContext context, String text) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: context.omniPalette.surfacePrimary,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          color: context.omniPalette.textSecondary,
        ),
      ),
    );
  }

  String _boolLabel(bool? val) {
    if (val == true) return '就绪';
    if (val == false) return '缺失';
    return '未确认';
  }

  String _routeDisplayName(String? route) {
    switch (route?.toLowerCase()) {
      case 'no_gradle':
        return '极速无 Gradle 构建';
      case 'gradle':
        return 'Gradle Wrapper 构建';
      case 'auto':
        return '智能自动选择';
      default:
        return route ?? '未确认';
    }
  }

  String _actionDisplayName(String action) {
    switch (action) {
      case 'dev_env_status':
        return '开发环境检查';
      case 'project_build':
        return '本地构建与签名';
      case 'apk_analyze':
        return 'APK 符号逆向分析';
      case 'lsposed_deploy':
        return 'LSPosed 模块部署';
      case 'probe_module_generate':
        return '临时探测模块生成';
      case 'probe_clean':
        return '探测模块清理';
      default:
        return action.isNotEmpty ? action : '手机开发任务';
    }
  }

  String _truncateHash(String hash) {
    if (hash.length > 20) {
      return '${hash.substring(0, 10)}...${hash.substring(hash.length - 8)}';
    }
    return hash;
  }

  String _formatFileSize(dynamic bytes) {
    final b = bytes is num ? bytes.toDouble() : double.tryParse(bytes.toString()) ?? 0.0;
    if (b < 1024) return '${b.toInt()} B';
    if (b < 1024 * 1024) return '${(b / 1024).toStringAsFixed(1)} KB';
    return '${(b / (1024 * 1024)).toStringAsFixed(2)} MB';
  }

  String _computeMissingEnvItems(
    bool? jdk,
    bool? aapt2,
    bool? d8,
    bool? apksigner,
    dynamic platforms,
    String? details,
  ) {
    final missing = <String>[];
    if (jdk != true) missing.add('JDK');
    if (aapt2 != true) missing.add('AAPT2');
    if (d8 != true) missing.add('D8');
    if (apksigner != true) missing.add('apksigner');
    if (platforms is! List || platforms.isEmpty) missing.add('Android SDK Platform');
    if (missing.isEmpty && details != null && details.isNotEmpty) {
      return details;
    }
    return missing.isEmpty ? '部分工具链未就绪' : missing.join('、');
  }
}
