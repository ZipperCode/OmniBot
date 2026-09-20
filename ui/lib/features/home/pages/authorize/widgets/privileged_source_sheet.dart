import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/theme_context.dart';

/// 底部抽屉：管理特权访问来源（未启用 / Shizuku / 直接 Root）与显式授权
class PrivilegedSourceSheet extends StatefulWidget {
  const PrivilegedSourceSheet({super.key, this.initialStatus});

  final PrivilegedStatusSnapshot? initialStatus;

  static Future<PrivilegedStatusSnapshot?> show(
    BuildContext context, {
    PrivilegedStatusSnapshot? initialStatus,
  }) {
    return showModalBottomSheet<PrivilegedStatusSnapshot>(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.35),
      builder: (context) => PrivilegedSourceSheet(initialStatus: initialStatus),
    );
  }

  @override
  State<PrivilegedSourceSheet> createState() => _PrivilegedSourceSheetState();
}

class _PrivilegedSourceSheetState extends State<PrivilegedSourceSheet> {
  late PrivilegedStatusSnapshot _status =
      widget.initialStatus ?? PrivilegedStatusSnapshot.fallback();
  bool _isLoading = false;
  bool _isSwitchingSource = false;
  bool _isAuthorizing = false;
  String? _feedbackMessage;

  @override
  void initState() {
    super.initState();
    if (widget.initialStatus == null) {
      _isLoading = true;
      _loadStatus();
    }
  }

  Future<void> _loadStatus() async {
    final status = await getPrivilegedStatus();
    if (mounted) {
      setState(() {
        _status = status;
        _isLoading = false;
      });
    }
  }

  Future<void> _selectSource(String source) async {
    if (_status.selected == source || _isSwitchingSource || _isAuthorizing) {
      return;
    }
    setState(() {
      _isSwitchingSource = true;
      _feedbackMessage = null;
    });
    try {
      final updated = await setPrivilegedSource(source);
      if (mounted) {
        setState(() {
          _status = updated;
          _isSwitchingSource = false;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _isSwitchingSource = false;
          _feedbackMessage = e.message ??
              (LegacyTextLocalizer.isEnglish
                  ? 'Failed to switch source'
                  : '切换来源失败');
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isSwitchingSource = false;
          _feedbackMessage = e.toString();
        });
      }
    }
  }

  Future<void> _authorize() async {
    if (_isAuthorizing || _isSwitchingSource) return;
    setState(() {
      _isAuthorizing = true;
      _feedbackMessage = null;
    });
    try {
      final updated = await authorizePrivilegedSource();
      if (mounted) {
        setState(() {
          _status = updated;
          _isAuthorizing = false;
        });
      }
    } on PlatformException catch (e) {
      if (mounted) {
        setState(() {
          _isAuthorizing = false;
          _feedbackMessage = e.message ??
              (LegacyTextLocalizer.isEnglish
                  ? 'Authorization failed'
                  : '授权失败');
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isAuthorizing = false;
          _feedbackMessage = e.toString();
        });
      }
    }
  }

  Future<void> _openSourceApp() async {
    final opened = await openPrivilegedSourceApp();
    if (!opened && mounted) {
      setState(() {
        _feedbackMessage = LegacyTextLocalizer.isEnglish
            ? 'Failed to open Shizuku app. Make sure it is installed.'
            : '未能打开 Shizuku 应用，请确认是否已安装。';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return Container(
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        boxShadow: [
          BoxShadow(
            color: palette.shadowColor,
            blurRadius: 16,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: SafeArea(
        top: false,
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 拖动把手
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: 12),
                  decoration: BoxDecoration(
                    color: palette.borderStrong,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              // 标题与关闭按钮
              Row(
                children: [
                  Container(
                    width: 32,
                    height: 32,
                    decoration: BoxDecoration(
                      color: palette.accentPrimary.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(9),
                    ),
                    alignment: Alignment.center,
                    child: Icon(
                      _status.isGranted
                          ? LucideIcons.shieldCheck
                          : LucideIcons.shieldAlert,
                      size: 18,
                      color: palette.accentPrimary,
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(
                      LegacyTextLocalizer.isEnglish
                          ? 'Privileged Access'
                          : '特权访问设置',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            color: palette.textPrimary,
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                  ),
                  IconButton(
                    icon: Icon(
                      LucideIcons.x,
                      size: 20,
                      color: palette.textTertiary,
                    ),
                    onPressed: () => Navigator.of(context).pop(_status),
                    visualDensity: VisualDensity.compact,
                    splashRadius: 20,
                  ),
                ],
              ),
              const SizedBox(height: 14),

              if (_isLoading)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 32),
                  child: Center(child: CircularProgressIndicator()),
                )
              else ...[
                // 当前状态卡片
                _buildCurrentStatusCard(palette),
                const SizedBox(height: 16),

                // 来源切换说明
                Text(
                  LegacyTextLocalizer.isEnglish
                      ? 'Select privileged source'
                      : '选择提权来源',
                  style: Theme.of(context).textTheme.labelLarge?.copyWith(
                        color: palette.textPrimary,
                        fontWeight: FontWeight.w700,
                      ),
                ),
                const SizedBox(height: 4),
                Text(
                  LegacyTextLocalizer.isEnglish
                      ? 'Switching privileged source stops active sessions and removes them from routing; processes already running as root may remain and cannot be guaranteed to terminate physically.'
                      : '切换特权来源将停止当前特权会话并移出路由表；已在后台运行的 Root 进程可能仍会残留，无法保证物理终止。',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: palette.textSecondary,
                        height: 1.4,
                      ),
                ),
                const SizedBox(height: 12),

                // 来源选项列表（none / shizuku / su）
                _buildSourceOption(
                  palette: palette,
                  sourceId: 'none',
                  title: LegacyTextLocalizer.isEnglish ? 'Disabled' : '未启用',
                  description: LegacyTextLocalizer.isEnglish
                      ? 'Do not use privileged access. High-privilege system tools remain inactive.'
                      : '不使用特权访问，高权限系统工具与会话保持关闭。',
                  icon: LucideIcons.shieldOff,
                ),
                const SizedBox(height: 8),
                _buildSourceOption(
                  palette: palette,
                  sourceId: 'shizuku',
                  title: 'Shizuku',
                  description: LegacyTextLocalizer.isEnglish
                      ? 'Acquire privileged capabilities via Shizuku service (typically ADB shell, or Root via Sui).'
                      : '通过 Shizuku 授权获取特权服务能力（通常为 ADB 权限，若通过 Sui 亦可为 Root）。',
                  icon: LucideIcons.shield,
                ),
                const SizedBox(height: 8),
                _buildSourceOption(
                  palette: palette,
                  sourceId: 'su',
                  title: LegacyTextLocalizer.isEnglish
                      ? 'Direct Root (KernelSU)'
                      : '直接 Root (KernelSU)',
                  description: LegacyTextLocalizer.isEnglish
                      ? 'Acquire Root directly via su binary. Suitable for devices with KernelSU, APatch, or Magisk. Root grant provides full device control; compatibility is not guaranteed on all devices.'
                      : '通过 su 二进制获取 Root 权限，适用于已具备 KernelSU / APatch / Magisk 等 Root 环境的设备。注意：Root 权限具有设备完全控制能力，请确保环境安全。不保证所有设备与系统均兼容。',
                  icon: LucideIcons.terminal,
                ),
                const SizedBox(height: 16),

                if (_feedbackMessage != null) ...[
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Text(
                      _feedbackMessage!,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Colors.redAccent,
                          ),
                    ),
                  ),
                ],

                // 操作按钮区（依所选来源动态展示）
                _buildActionButtons(palette),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCurrentStatusCard(dynamic palette) {
    final statusColor = _status.isGranted
        ? palette.accentPrimary
        : (_status.code == 'su_denied' ||
                _status.code == 'shizuku_permission_denied' ||
                _status.code == 'su_unavailable')
            ? Colors.redAccent
            : palette.textSecondary;

    return Container(
      padding: const EdgeInsets.all(14),
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
              Text(
                LegacyTextLocalizer.isEnglish ? 'Current Status: ' : '当前状态：',
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: palette.textSecondary,
                      fontWeight: FontWeight.w500,
                    ),
              ),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(
                  color: statusColor.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  _status.localizedStatusLabel,
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: statusColor,
                        fontWeight: FontWeight.w700,
                      ),
                ),
              ),
              const Spacer(),
              if (_status.uid != null)
                Text(
                  'UID: ${_status.uid}',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: palette.textTertiary,
                        fontFamily: 'monospace',
                      ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            _status.localizedGuide,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: palette.textSecondary,
                  height: 1.45,
                ),
          ),
        ],
      ),
    );
  }

  Widget _buildSourceOption({
    required dynamic palette,
    required String sourceId,
    required String title,
    required String description,
    required IconData icon,
  }) {
    final isSelected = _status.selected == sourceId;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => _selectSource(sourceId),
        borderRadius: BorderRadius.circular(14),
        splashColor: palette.accentPrimary.withValues(alpha: 0.08),
        highlightColor: Colors.transparent,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: isSelected
                ? palette.accentPrimary.withValues(alpha: 0.06)
                : palette.surfaceSecondary.withValues(alpha: 0.5),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(
              color: isSelected ? palette.accentPrimary : palette.borderSubtle,
              width: isSelected ? 1.5 : 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 34,
                height: 34,
                decoration: BoxDecoration(
                  color: isSelected
                      ? palette.accentPrimary.withValues(alpha: 0.14)
                      : palette.surfaceSecondary,
                  borderRadius: BorderRadius.circular(10),
                ),
                alignment: Alignment.center,
                child: Icon(
                  icon,
                  size: 18,
                  color:
                      isSelected ? palette.accentPrimary : palette.textSecondary,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            title,
                            style:
                                Theme.of(context).textTheme.titleSmall?.copyWith(
                                      color: palette.textPrimary,
                                      fontWeight: FontWeight.w600,
                                    ),
                          ),
                        ),
                        if (isSelected) ...[
                          const SizedBox(width: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 1,
                            ),
                            decoration: BoxDecoration(
                              color:
                                  palette.accentPrimary.withValues(alpha: 0.12),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              LegacyTextLocalizer.isEnglish
                                  ? 'Selected'
                                  : '已选择',
                              style: Theme.of(context)
                                  .textTheme
                                  .labelSmall
                                  ?.copyWith(
                                    color: palette.accentPrimary,
                                    fontSize: 10,
                                    fontWeight: FontWeight.w600,
                                  ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 3),
                    Text(
                      description,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: palette.textSecondary,
                            height: 1.4,
                          ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: isSelected
                    ? Icon(
                        LucideIcons.checkCircle2,
                        size: 20,
                        color: palette.accentPrimary,
                      )
                    : Icon(
                        LucideIcons.circle,
                        size: 20,
                        color: palette.borderStrong,
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildActionButtons(dynamic palette) {
    if (_status.isNone) {
      return FilledButton(
        style: FilledButton.styleFrom(
          backgroundColor: palette.accentPrimary,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          padding: const EdgeInsets.symmetric(vertical: 12),
        ),
        onPressed: () => Navigator.of(context).pop(_status),
        child: Text(
          LegacyTextLocalizer.isEnglish ? 'Done' : '完成',
          style: const TextStyle(fontWeight: FontWeight.w600),
        ),
      );
    }

    if (_status.isSu) {
      return FilledButton.icon(
        style: FilledButton.styleFrom(
          backgroundColor: palette.accentPrimary,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          padding: const EdgeInsets.symmetric(vertical: 12),
        ),
        onPressed: _isAuthorizing ? null : _authorize,
        icon: _isAuthorizing
            ? const SizedBox(
                width: 16,
                height: 16,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              )
            : const Icon(LucideIcons.zap, size: 18),
        label: Text(
          LegacyTextLocalizer.isEnglish ? 'Verify Root Authorization' : '验证 Root 授权',
          style: const TextStyle(fontWeight: FontWeight.w600),
        ),
      );
    }

    // Shizuku
    return Row(
      children: [
        if (_status.shizuku?.installed != true ||
            _status.shizuku?.running != true) ...[
          Expanded(
            child: OutlinedButton.icon(
              style: OutlinedButton.styleFrom(
                side: BorderSide(color: palette.accentPrimary),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                padding: const EdgeInsets.symmetric(vertical: 12),
              ),
              onPressed: _openSourceApp,
              icon: const Icon(LucideIcons.externalLink, size: 16),
              label: Text(
                LegacyTextLocalizer.isEnglish ? 'Open Shizuku' : '打开 Shizuku',
                style: TextStyle(
                  color: palette.accentPrimary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
          const SizedBox(width: 10),
        ],
        Expanded(
          child: FilledButton.icon(
            style: FilledButton.styleFrom(
              backgroundColor: palette.accentPrimary,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
              padding: const EdgeInsets.symmetric(vertical: 12),
            ),
            onPressed: _isAuthorizing ? null : _authorize,
            icon: _isAuthorizing
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Icon(LucideIcons.check, size: 18),
            label: Text(
              LegacyTextLocalizer.isEnglish
                  ? 'Authorize / Verify'
                  : '发起授权 / 验证',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
        ),
      ],
    );
  }
}
