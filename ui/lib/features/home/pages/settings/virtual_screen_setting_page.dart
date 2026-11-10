import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/core/mixins/page_lifecycle_mixin.dart';
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/models/conversation_model.dart';
import 'package:ui/models/conversation_thread_target.dart';
import 'package:ui/services/virtual_screen_service.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/utils/ui.dart';
import 'package:ui/widgets/omni_adaptive_layout.dart';
import 'package:ui/widgets/omni_glass.dart';
import 'package:ui/widgets/omni_root_toolbar.dart';
import 'package:ui/widgets/settings_ui.dart';

/// 虚拟屏状态展示与设备管理页面。
/// 提供后台虚拟屏状态概览、预览、任务停止、安全释放与权限导航。
class VirtualScreenSettingPage extends StatefulWidget {
  const VirtualScreenSettingPage({super.key});

  @override
  State<VirtualScreenSettingPage> createState() =>
      _VirtualScreenSettingPageState();
}

class _VirtualScreenSettingPageState extends State<VirtualScreenSettingPage>
    with WidgetsBindingObserver, PageLifecycleMixin<VirtualScreenSettingPage> {
  VirtualScreenSnapshot? _snapshot;
  bool _loading = true;
  bool _operating = false;
  String? _errorMessage;
  bool _expandedAdvanced = false;
  Timer? _pollingTimer;
  int _loadGeneration = 0;
  Future<void>? _inFlightLoad;

  @override
  void initState() {
    super.initState();
    _loadState();
    _startPolling();
  }

  @override
  void dispose() {
    _stopPolling();
    super.dispose();
  }

  void _startPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (mounted && !_operating && _inFlightLoad == null) {
        _loadState(silent: true);
      }
    });
  }

  void _stopPolling() {
    _pollingTimer?.cancel();
    _pollingTimer = null;
  }

  @override
  void onPageResumed() {
    super.onPageResumed();
    _loadState();
    _startPolling();
  }

  @override
  void onPagePaused() {
    super.onPagePaused();
    _stopPolling();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive ||
        state == AppLifecycleState.hidden) {
      _stopPolling();
    }
  }

  Future<void> _loadState({bool silent = false}) async {
    if (silent && _inFlightLoad != null) {
      return;
    }
    while (_inFlightLoad != null) {
      await _inFlightLoad;
      if (!mounted) return;
    }

    final future = _performLoad(silent: silent);
    _inFlightLoad = future;
    try {
      await future;
    } finally {
      if (_inFlightLoad == future) {
        _inFlightLoad = null;
      }
    }
  }

  Future<void> _performLoad({bool silent = false}) async {
    final generation = ++_loadGeneration;
    if (!silent) {
      setState(() {
        _loading = true;
      });
    }
    try {
      final snapshot = await VirtualScreenService.getState();
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _snapshot = snapshot;
        _errorMessage = null;
        _loading = false;
      });
    } on PlatformException catch (e) {
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _errorMessage = e.message ?? e.code;
        _loading = false;
      });
    } catch (e) {
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _errorMessage = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _openPreview() async {
    if (_operating || _snapshot == null) return;
    setState(() => _operating = true);
    try {
      await VirtualScreenService.openPreview(
        displayId: _snapshot!.displayId,
        sessionId: _snapshot!.sessionId,
      );
    } on PlatformException catch (e) {
      showToast(e.message ?? '打开预览失败', type: ToastType.error);
      await _loadState(silent: true);
    } catch (e) {
      showToast('打开预览失败: $e', type: ToastType.error);
      await _loadState(silent: true);
    } finally {
      if (mounted) setState(() => _operating = false);
    }
  }

  Future<void> _stopTask() async {
    if (_operating || _snapshot == null) return;
    setState(() => _operating = true);
    try {
      await VirtualScreenService.stopTask(
        displayId: _snapshot!.displayId,
        sessionId: _snapshot!.sessionId,
        taskId: _snapshot!.taskId,
        turnId: _snapshot!.turnId,
      );
      showToast('已请求停止任务');
      await _loadState();
    } on PlatformException catch (e) {
      showToast(e.message ?? '停止任务失败', type: ToastType.error);
      await _loadState(silent: true);
    } catch (e) {
      showToast('停止任务失败: $e', type: ToastType.error);
      await _loadState(silent: true);
    } finally {
      if (mounted) setState(() => _operating = false);
    }
  }

  Future<void> _confirmCloseScreen() async {
    if (_operating || _snapshot == null) return;
    final target = _snapshot;
    if (target == null || !target.isRetained) return;

    _stopPolling();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => OmniGlassAlertDialog(
        title: const Text('关闭虚拟屏现场'),
        content: const Text(
          '将关闭后台虚拟屏并释放其运行中的应用现场。聊天记录与会话记忆将完整保留，不受影响。确定关闭吗？',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          TextButton(
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(ctx).colorScheme.error,
            ),
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('确认关闭'),
          ),
        ],
      ),
    );
    if (!mounted) return;
    _startPolling();

    if (confirmed != true) return;

    setState(() => _operating = true);
    try {
      await VirtualScreenService.closeScreen(
        displayId: target.displayId,
        sessionId: target.sessionId,
      );
      showToast('虚拟屏现场已关闭');
      await _loadState();
    } on PlatformException catch (e) {
      showToast(e.message ?? '关闭虚拟屏失败', type: ToastType.error);
      await _loadState(silent: true);
    } catch (e) {
      showToast('关闭虚拟屏失败: $e', type: ToastType.error);
      await _loadState(silent: true);
    } finally {
      if (mounted) setState(() => _operating = false);
    }
  }

  void _openConversation(String conversationId) {
    final parsedId = int.tryParse(conversationId);
    if (parsedId == null) return;
    GoRouterManager.push(
      GoRouterManager.chatRoute,
      extra: ConversationThreadTarget.existing(
        conversationId: parsedId,
        mode: ConversationMode.agent,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Scaffold(
      backgroundColor: palette.pageBackground,
      appBar: OmniRootToolbar(
        title: '虚拟屏',
        primary: true,
        showBackButton: true,
        actions: [
          IconButton(
            tooltip: '刷新',
            icon: _loading
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(LucideIcons.rotateCw, size: 20),
            onPressed: _operating ? null : () => _loadState(),
          ),
        ],
      ),
      body: OmniContentWidth(
        maxWidth: 840,
        child: SafeArea(
          top: false,
          bottom: false,
          child: ListView(
            padding: edgeToEdgeScrollPadding(
              context,
              const EdgeInsets.fromLTRB(22, 10, 22, 32),
            ),
            children: [
              if (_errorMessage != null) ...[
                _buildErrorCard(context),
              ] else if (_snapshot != null) ...[
                _buildOverviewCard(context),
              ],
              if (_errorMessage == null &&
                  _snapshot != null &&
                  !_snapshot!.isAbsent) ...[
                const SizedBox(height: 20),
                _buildRuntimeInfoGroup(context),
                const SizedBox(height: 20),
                _buildControlsGroup(context),
                const SizedBox(height: 20),
                _buildAdvancedGroup(context),
              ],
              const SizedBox(height: 20),
              _buildPermissionsGroup(context),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildOverviewCard(BuildContext context) {
    final palette = context.omniPalette;
    final snapshot = _snapshot;
    final state = snapshot?.state ?? VirtualScreenLifecycleState.absent;

    final Color statusColor;
    final Color statusBgColor;
    final IconData stateIcon;
    final String stateDescription;

    switch (state) {
      case VirtualScreenLifecycleState.running:
        statusColor = palette.brandPrimary;
        statusBgColor = palette.brandSurface;
        stateIcon = LucideIcons.playCircle;
        stateDescription = snapshot?.taskTitle?.isNotEmpty == true
            ? snapshot!.taskTitle!
            : '后台虚拟屏正在执行任务';
      case VirtualScreenLifecycleState.waiting:
        statusColor = palette.accentPrimary;
        statusBgColor = palette.surfaceSecondary;
        stateIcon = LucideIcons.clock;
        stateDescription = snapshot?.taskTitle?.isNotEmpty == true
            ? snapshot!.taskTitle!
            : '后台虚拟屏等待输入或下一轮次';
      case VirtualScreenLifecycleState.retained:
        statusColor = palette.brandPrimary;
        statusBgColor = palette.brandSurface;
        stateIcon = LucideIcons.checkCircle2;
        stateDescription = '应用现场保持在后台，未占用前台主屏';
      case VirtualScreenLifecycleState.absent:
        statusColor = palette.textTertiary;
        statusBgColor = palette.borderSubtle.withValues(alpha: 0.35);
        stateIcon = LucideIcons.monitorOff;
        stateDescription = '当前未运行虚拟屏，仅在使用屏幕工具时创建';
    }

    return OmniGlassCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: statusBgColor,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(stateIcon, color: statusColor, size: 24),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      state.label,
                      style: TextStyle(
                        fontSize: 17,
                        fontWeight: FontWeight.w600,
                        color: palette.textPrimary,
                        height: 1.25,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      stateDescription,
                      style: TextStyle(
                        fontSize: 13,
                        color: palette.textSecondary,
                        height: 1.4,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          if (snapshot != null &&
              snapshot.statusText != null &&
              snapshot.statusText!.isNotEmpty &&
              snapshot.taskTitle != null &&
              snapshot.taskTitle != snapshot.statusText) ...[
            const SizedBox(height: 12),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: palette.borderSubtle.withValues(alpha: 0.25),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                snapshot.statusText!,
                style: TextStyle(
                  fontSize: 12,
                  color: palette.textTertiary,
                  height: 1.35,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildErrorCard(BuildContext context) {
    final palette = context.omniPalette;
    final errorTheme = Theme.of(context).colorScheme;

    return OmniGlassCard(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                LucideIcons.alertTriangle,
                color: errorTheme.error,
                size: 20,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  '获取虚拟屏状态失败',
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: errorTheme.error,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _errorMessage!,
            style: TextStyle(
              fontSize: 13,
              color: palette.textSecondary,
              height: 1.4,
            ),
          ),
          const SizedBox(height: 12),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: _operating ? null : () => _loadState(),
                child: const Text('重试'),
              ),
              const SizedBox(width: 8),
              FilledButton.tonal(
                onPressed: () => GoRouterManager.push('/home/authorize_setting'),
                child: const Text('检查权限'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildRuntimeInfoGroup(BuildContext context) {
    final snapshot = _snapshot!;
    final specsText =
        '${snapshot.width ?? '-'} × ${snapshot.height ?? '-'} (${snapshot.dpi != null ? '${snapshot.dpi} DPI' : '-'})';
    final currentApp = snapshot.appLabel?.isNotEmpty == true
        ? snapshot.appLabel!
        : (snapshot.packageName?.isNotEmpty == true
            ? snapshot.packageName!
            : '无');
    final parsedConversationId = int.tryParse(snapshot.conversationId ?? '');
    final hasConversation = parsedConversationId != null;

    return OmniSettingsGroup(
      title: '运行信息',
      translateTitle: false,
      children: [
        OmniSettingsTile(
          icon: LucideIcons.maximize2,
          title: '屏幕规格',
          subtitle: specsText,
          translateText: false,
        ),
        OmniSettingsTile(
          icon: LucideIcons.smartphone,
          title: '当前应用',
          subtitle: currentApp,
          translateText: false,
        ),
        OmniSettingsTile(
          tileKey: const Key('virtual-screen-conversation-tile'),
          icon: LucideIcons.messageSquare,
          title: '所属对话',
          subtitle: hasConversation
              ? '会话 #${snapshot.conversationId}'
              : '无关联对话',
          trailingText: hasConversation ? '查看' : null,
          showChevron: hasConversation,
          translateText: false,
          onTap: hasConversation
              ? () => _openConversation(snapshot.conversationId!)
              : null,
        ),
      ],
    );
  }

  Widget _buildControlsGroup(BuildContext context) {
    final snapshot = _snapshot!;
    final canStopTask = snapshot.isActiveTask && snapshot.taskId != null;
    final canCloseScreen = snapshot.isRetained;

    return OmniSettingsGroup(
      title: '控制操作',
      translateTitle: false,
      children: [
        OmniSettingsTile(
          tileKey: const Key('virtual-screen-preview-tile'),
          icon: LucideIcons.eye,
          title: '查看预览',
          subtitle: '以悬浮全屏方式实时查看后台虚拟屏',
          showChevron: true,
          translateText: false,
          onTap: _operating ? null : _openPreview,
        ),
        if (snapshot.isActiveTask)
          OmniSettingsTile(
            tileKey: const Key('virtual-screen-stop-task-tile'),
            icon: LucideIcons.square,
            title: '停止当前任务',
            subtitle: '官方安全取消当前执行中的任务 Turn',
            isDestructive: true,
            translateText: false,
            onTap: (_operating || !canStopTask) ? null : _stopTask,
          ),
        if (canCloseScreen)
          OmniSettingsTile(
            tileKey: const Key('virtual-screen-close-screen-tile'),
            icon: LucideIcons.power,
            title: '关闭虚拟屏现场',
            subtitle: '释放后台虚拟屏与应用现场，保留聊天记录',
            isDestructive: true,
            translateText: false,
            onTap: _operating ? null : _confirmCloseScreen,
          ),
        if (snapshot.isActiveTask)
          OmniSettingsTile(
            icon: LucideIcons.info,
            title: '关闭虚拟屏现场',
            subtitle: '任务正在运行中，禁止直接销毁现场，请先停止任务',
            translateText: false,
          ),
      ],
    );
  }

  Widget _buildAdvancedGroup(BuildContext context) {
    final snapshot = _snapshot!;

    return OmniSettingsGroup(
      title: '高级详情',
      translateTitle: false,
      children: [
        OmniSettingsTile(
          tileKey: const Key('virtual-screen-advanced-toggle'),
          icon: LucideIcons.code,
          title: '底层调试参数',
          subtitle: _expandedAdvanced ? '点击收起' : 'Display ID、Session ID、包名等',
          trailing: Icon(
            _expandedAdvanced ? LucideIcons.chevronUp : LucideIcons.chevronDown,
            size: 18,
          ),
          translateText: false,
          onTap: () {
            setState(() {
              _expandedAdvanced = !_expandedAdvanced;
            });
          },
        ),
        if (_expandedAdvanced) ...[
          OmniSettingsTile(
            title: 'Display ID',
            subtitle: snapshot.displayId?.toString() ?? '无',
            translateText: false,
          ),
          OmniSettingsTile(
            title: 'Session ID',
            subtitle: snapshot.sessionId ?? '无',
            translateText: false,
          ),
          OmniSettingsTile(
            title: '包名 (Package)',
            subtitle: snapshot.packageName ?? '无',
            translateText: false,
          ),
          OmniSettingsTile(
            title: '任务 ID (Task ID)',
            subtitle: snapshot.taskId ?? '无',
            translateText: false,
          ),
          OmniSettingsTile(
            title: '轮次 ID (Turn ID)',
            subtitle: snapshot.turnId ?? '无',
            translateText: false,
          ),
          OmniSettingsTile(
            title: '悬浮窗层级 (Overlay)',
            subtitle: snapshot.overlayAvailable ? '可用' : '不可用',
            translateText: false,
          ),
        ],
      ],
    );
  }

  Widget _buildPermissionsGroup(BuildContext context) {
    return OmniSettingsGroup(
      title: '系统权限',
      translateTitle: false,
      children: [
        OmniSettingsTile(
          tileKey: const Key('virtual-screen-permissions-tile'),
          icon: LucideIcons.shieldCheck,
          title: '权限管理',
          subtitle: '检查并配置悬浮窗、无障碍服务与后台运行权限',
          showChevron: true,
          translateText: false,
          onTap: () {
            GoRouterManager.push('/home/authorize_setting');
          },
        ),
      ],
    );
  }
}
