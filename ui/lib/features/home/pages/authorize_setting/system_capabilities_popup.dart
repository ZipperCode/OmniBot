import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/services/special_permission.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/glass_popup.dart';
import 'package:ui/widgets/omni_menu.dart';

const List<String> kFixedDeviceTargets = [
  'wifi',
  'bluetooth',
  'mobile_data',
  'airplane_mode',
  'location',
  'nfc',
  'battery_saver',
  'auto_rotate',
  'auto_brightness',
  'dark_mode',
  'hotspot',
  'night_light',
  'flashlight',
];

String deviceTargetLabel(String target, bool english) {
  if (english) {
    return switch (target) {
      'wifi' => 'Wi-Fi',
      'bluetooth' => 'Bluetooth',
      'mobile_data' => 'Mobile Data',
      'airplane_mode' => 'Airplane Mode',
      'location' => 'Location',
      'nfc' => 'NFC',
      'battery_saver' => 'Battery Saver',
      'auto_rotate' => 'Auto Rotate',
      'auto_brightness' => 'Auto Brightness',
      'dark_mode' => 'Dark Mode',
      'hotspot' => 'Hotspot',
      'night_light' => 'Night Light',
      'flashlight' => 'Flashlight',
      _ => target,
    };
  }
  return switch (target) {
    'wifi' => 'WLAN',
    'bluetooth' => '蓝牙',
    'mobile_data' => '移动数据',
    'airplane_mode' => '飞行模式',
    'location' => '位置信息',
    'nfc' => 'NFC',
    'battery_saver' => '省电模式',
    'auto_rotate' => '自动旋转',
    'auto_brightness' => '自动亮度',
    'dark_mode' => '深色模式',
    'hotspot' => '个人热点',
    'night_light' => '护眼模式',
    'flashlight' => '手电筒',
    _ => target,
  };
}

IconData deviceTargetIcon(String target) => switch (target) {
  'wifi' => LucideIcons.wifi,
  'bluetooth' => LucideIcons.bluetooth,
  'mobile_data' => LucideIcons.radio,
  'airplane_mode' => LucideIcons.plane,
  'location' => LucideIcons.mapPin,
  'nfc' => LucideIcons.nfc,
  'battery_saver' => LucideIcons.batteryCharging,
  'auto_rotate' => LucideIcons.smartphone,
  'auto_brightness' => LucideIcons.sunMedium,
  'dark_mode' => LucideIcons.moon,
  'hotspot' => LucideIcons.wifiOff,
  'night_light' => LucideIcons.eye,
  'flashlight' => LucideIcons.flashlight,
  _ => LucideIcons.sliders,
};

String deviceActionLabel(String action, bool english) {
  return switch (action) {
    'device_control.get_display' => english ? 'Read display' : '读取显示设置',
    'device_control.set_brightness' => english ? 'Set brightness' : '调节亮度',
    'device_control.set_screen_timeout' =>
        english ? 'Set screen timeout' : '设置息屏时间',
    'device_control.get_sound' => english ? 'Read sound' : '读取声音设置',
    'device_control.set_volume' => english ? 'Adjust volume' : '调节音量',
    'device_control.set_do_not_disturb' =>
        english ? 'Set Do Not Disturb' : '设置勿扰模式',
    'device_control.set_ringer_mode' =>
        english ? 'Set ringer mode' : '设置响铃模式',
    _ => action,
  };
}

class DeviceTargetStatusInfo {
  final String statusSummary;
  final String? missingCondition;
  final bool isSupported;
  final bool isFullyCapable;
  final Color? statusColor;

  const DeviceTargetStatusInfo({
    required this.statusSummary,
    this.missingCondition,
    required this.isSupported,
    required this.isFullyCapable,
    this.statusColor,
  });
}

DeviceTargetStatusInfo resolveTargetStatusInfo(
  DeviceTargetCapability? cap,
  BuildContext context,
  bool english,
) {
  final palette = context.omniPalette;
  if (cap == null) {
    return DeviceTargetStatusInfo(
      statusSummary: english ? 'Unavailable' : '状态未知',
      missingCondition: english ? 'Capability state unavailable' : '状态暂不可用',
      isSupported: false,
      isFullyCapable: false,
      statusColor: palette.textTertiary,
    );
  }

  final readable = cap.readable;
  final writable = cap.writable;
  final code = cap.code;

  String? condition;
  if (!writable || !readable) {
    condition = switch (code) {
      'permission_denied' => english
          ? 'Requires privileged access (Shizuku / Root)'
          : '需特权授权（Shizuku 或 Root）',
      'unsupported_action' => english
          ? 'Not supported by current ROM/device'
          : '当前系统或设备不支持',
      'state_unavailable' => english
          ? 'Hardware state unavailable'
          : '硬件状态暂不可用',
      _ => null,
    };
  }

  if (readable && writable) {
    return DeviceTargetStatusInfo(
      statusSummary: english ? 'Read & Write' : '可读写',
      missingCondition: null,
      isSupported: true,
      isFullyCapable: true,
      statusColor: palette.accentPrimary,
    );
  } else if (readable && !writable) {
    return DeviceTargetStatusInfo(
      statusSummary: english ? 'Read only' : '仅可读取',
      missingCondition: condition ?? (english ? 'Cannot modify' : '不可修改'),
      isSupported: true,
      isFullyCapable: false,
      statusColor: palette.textSecondary,
    );
  } else if (!readable && writable) {
    return DeviceTargetStatusInfo(
      statusSummary: english ? 'Write only' : '仅可修改',
      missingCondition: condition ?? (english ? 'Cannot read' : '不可读取'),
      isSupported: true,
      isFullyCapable: false,
      statusColor: palette.textSecondary,
    );
  } else {
    return DeviceTargetStatusInfo(
      statusSummary: english ? 'Unavailable' : '不可用',
      missingCondition: condition ?? (english ? 'Unsupported' : '不支持'),
      isSupported: false,
      isFullyCapable: false,
      statusColor: palette.textTertiary,
    );
  }
}

Future<void> showSystemCapabilitiesPopup({
  required BuildContext context,
  required BuildContext anchorContext,
}) async {
  final anchor = glassPopupAnchorFromContext(anchorContext);
  if (anchor == null) return;
  await showGlassPopup<void>(
    context: context,
    sourceContext: anchorContext,
    anchor: anchor,
    screenPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
    child: LayoutBuilder(
      builder: (popupContext, constraints) {
        final menuWidth = (380 *
                MediaQuery.textScalerOf(popupContext).scale(15) /
                15)
            .clamp(320.0, 480.0);
        return ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: math.min(menuWidth, constraints.maxWidth),
            maxHeight: constraints.maxHeight.clamp(280.0, 600.0),
          ),
          child: SystemCapabilitiesPopupContent(width: menuWidth),
        );
      },
    ),
  );
}

class SystemCapabilitiesPopupContent extends StatefulWidget {
  const SystemCapabilitiesPopupContent({super.key, this.width = 360});

  final double width;

  @override
  State<SystemCapabilitiesPopupContent> createState() =>
      _SystemCapabilitiesPopupContentState();
}

class _SystemCapabilitiesPopupContentState
    extends State<SystemCapabilitiesPopupContent> {
  late Future<SystemDeviceCapabilities> _capabilitiesFuture;
  bool _showTechnicalDetails = false;

  bool get _english => Localizations.localeOf(context).languageCode == 'en';

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    setState(() {
      _capabilitiesFuture = getSystemDeviceCapabilities();
    });
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return OmniMenuSurface(
      width: widget.width,
      padding: EdgeInsets.zero,
      child: Material(
        color: Colors.transparent,
        child: FutureBuilder<SystemDeviceCapabilities>(
          future: _capabilitiesFuture,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 48, horizontal: 24),
                child: Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      SizedBox(
                        width: 28,
                        height: 28,
                        child: CircularProgressIndicator(
                          strokeWidth: 2.5,
                          color: palette.accentPrimary,
                        ),
                      ),
                      const SizedBox(height: 16),
                      Text(
                        _english
                            ? 'Probing device capabilities…'
                            : '正在读取系统设备能力…',
                        style: TextStyle(
                          fontSize: 13.5,
                          color: palette.textSecondary,
                        ),
                      ),
                    ],
                  ),
                ),
              );
            }

            if (snapshot.hasError) {
              return Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Icon(
                          LucideIcons.alertCircle,
                          size: 20,
                          color: palette.accentPrimary,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _english
                                ? 'Failed to read capabilities'
                                : '读取系统能力失败',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: palette.textPrimary,
                            ),
                          ),
                        ),
                        IconButton(
                          icon: const Icon(LucideIcons.x, size: 18),
                          onPressed: () => Navigator.of(context).pop(),
                          tooltip: _english ? 'Close' : '关闭',
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _english
                          ? 'Could not probe device capabilities. Tap retry to attempt read-only check again.'
                          : '无法获取系统设备能力。点击下方按钮仅执行只读探测重试。',
                      style: TextStyle(
                        fontSize: 13,
                        color: palette.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                      icon: const Icon(LucideIcons.refreshCw, size: 16),
                      label: Text(_english ? 'Retry probe' : '重试读取'),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(48),
                        foregroundColor: palette.accentPrimary,
                        side: BorderSide(
                          color: palette.accentPrimary.withValues(alpha: 0.4),
                        ),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      onPressed: _load,
                    ),
                  ],
                ),
              );
            }

            final capabilities = snapshot.data!;
            if (!capabilities.success || capabilities.targets.isEmpty) {
              return Padding(
                padding: const EdgeInsets.all(20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Icon(
                          LucideIcons.alertCircle,
                          size: 20,
                          color: palette.accentPrimary,
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _english
                                ? 'Device capability unavailable'
                                : '系统能力暂不可用',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: palette.textPrimary,
                            ),
                          ),
                        ),
                        IconButton(
                          icon: const Icon(LucideIcons.x, size: 18),
                          onPressed: () => Navigator.of(context).pop(),
                          tooltip: _english ? 'Close' : '关闭',
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Text(
                      capabilities.message.isNotEmpty
                          ? capabilities.message
                          : (_english
                              ? 'Device capability state is unavailable. Tap retry to attempt read-only check again.'
                              : '无法读取设备能力列表（状态暂不可用）。点击重试再次读取。'),
                      style: TextStyle(
                        fontSize: 13,
                        color: palette.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                      icon: const Icon(LucideIcons.refreshCw, size: 16),
                      label: Text(_english ? 'Retry probe' : '重试读取'),
                      style: OutlinedButton.styleFrom(
                        minimumSize: const Size.fromHeight(48),
                        foregroundColor: palette.accentPrimary,
                        side: BorderSide(
                          color: palette.accentPrimary.withValues(alpha: 0.4),
                        ),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      onPressed: _load,
                    ),
                  ],
                ),
              );
            }

            final targetMap = {for (final t in capabilities.targets) t.target: t};
            final isUnprivileged = capabilities.backend == 'NONE';
            return SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _buildHeader(context, isUnprivileged, capabilities.backend),
                  const SizedBox(height: 12),
                  _buildStatusBanner(context, isUnprivileged, capabilities.backend),
                  const SizedBox(height: 12),
                  _buildSectionTitle(
                    context,
                    title: _english ? 'System Switches' : '系统受控开关',
                    subtitle: _english
                        ? 'Fixed device state targets in order'
                        : '标准设备状态开关与可用性',
                  ),
                  const SizedBox(height: 6),
                  for (final targetKey in kFixedDeviceTargets)
                    _buildTargetRow(
                      context,
                      targetKey: targetKey,
                      capability: targetMap[targetKey],
                    ),
                  if (capabilities.actions.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    _buildSectionTitle(
                      context,
                      title: _english ? 'Controlled Actions' : '受控系统动作',
                      subtitle: _english
                          ? 'Display and audio controls'
                          : '显示与声音等系统动作可用性',
                    ),
                    const SizedBox(height: 6),
                    for (final action in capabilities.actions)
                      _buildActionRow(context, action: action),
                  ],
                  const SizedBox(height: 12),
                  _buildTechnicalDetailsSection(context, capabilities),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  Widget _buildHeader(
    BuildContext context,
    bool isUnprivileged,
    String backend,
  ) {
    final palette = context.omniPalette;
    return Row(
      children: [
        Icon(LucideIcons.sliders, size: 18, color: palette.accentPrimary),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            _english ? 'System Capabilities' : '系统设备能力',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
        ),
        IconButton(
          constraints: const BoxConstraints(minWidth: 44, minHeight: 44),
          icon: Icon(LucideIcons.x, size: 18, color: palette.textSecondary),
          onPressed: () => Navigator.of(context).pop(),
          tooltip: _english ? 'Close' : '关闭',
        ),
      ],
    );
  }

  Widget _buildStatusBanner(
    BuildContext context,
    bool isUnprivileged,
    String backend,
  ) {
    final palette = context.omniPalette;
    final isDark = context.isDarkTheme;

    final bgColor = isUnprivileged
        ? (isDark
            ? Colors.white.withValues(alpha: 0.05)
            : Colors.black.withValues(alpha: 0.04))
        : palette.accentPrimary.withValues(alpha: 0.12);
    final borderColor = isUnprivileged
        ? palette.textTertiary.withValues(alpha: 0.2)
        : palette.accentPrimary.withValues(alpha: 0.3);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: bgColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: borderColor),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            isUnprivileged ? LucideIcons.shieldAlert : LucideIcons.shieldCheck,
            size: 16,
            color: isUnprivileged ? palette.textSecondary : palette.accentPrimary,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  isUnprivileged
                      ? (_english
                          ? 'Unprivileged mode · Base permission status'
                          : '未授权 · 显示基础权限探测状态')
                      : (_english
                          ? 'Privileged mode · $backend'
                          : '已授权 · 优先使用 $backend'),
                  style: TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: isUnprivileged
                        ? palette.textPrimary
                        : palette.accentPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  isUnprivileged
                      ? (_english
                          ? 'Read-only check without su. Enable Shizuku or Root in settings for write controls.'
                          : '当前未运行 su 探测。如需修改开关，请在扩展能力中开启 Shizuku 或 Root。')
                      : (_english
                          ? 'Read-only capability snapshot using verified privileged bridge.'
                          : '只读能力快照，展示当前系统服务受控接口可用性。'),
                  style: TextStyle(
                    fontSize: 11.5,
                    color: palette.textSecondary,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionTitle(
    BuildContext context, {
    required String title,
    required String subtitle,
  }) {
    final palette = context.omniPalette;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              fontSize: 13.5,
              fontWeight: FontWeight.w600,
              color: palette.textPrimary,
            ),
          ),
          Text(
            subtitle,
            style: TextStyle(
              fontSize: 11,
              color: palette.textTertiary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTargetRow(
    BuildContext context, {
    required String targetKey,
    required DeviceTargetCapability? capability,
  }) {
    final palette = context.omniPalette;
    final statusInfo = resolveTargetStatusInfo(capability, context, _english);
    final targetName = deviceTargetLabel(targetKey, _english);
    final iconData = deviceTargetIcon(targetKey);

    return Container(
      constraints: const BoxConstraints(minHeight: 48),
      margin: const EdgeInsets.symmetric(vertical: 2),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.35),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Container(
            width: 32,
            height: 32,
            decoration: BoxDecoration(
              color: palette.surfacePrimary,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(iconData, size: 16, color: palette.textSecondary),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  targetName,
                  style: TextStyle(
                    fontSize: 13.5,
                    fontWeight: FontWeight.w500,
                    color: palette.textPrimary,
                  ),
                ),
                if (statusInfo.missingCondition != null)
                  Text(
                    statusInfo.missingCondition!,
                    style: TextStyle(
                      fontSize: 11,
                      color: palette.textTertiary,
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: statusInfo.isFullyCapable
                  ? palette.accentPrimary.withValues(alpha: 0.15)
                  : palette.surfacePrimary,
              borderRadius: BorderRadius.circular(6),
              border: Border.all(
                color: statusInfo.isFullyCapable
                    ? palette.accentPrimary.withValues(alpha: 0.3)
                    : palette.textTertiary.withValues(alpha: 0.15),
              ),
            ),
            child: Text(
              statusInfo.statusSummary,
              style: TextStyle(
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
                color: statusInfo.statusColor ?? palette.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionRow(
    BuildContext context, {
    required DeviceActionCapability action,
  }) {
    final palette = context.omniPalette;
    final actionName = deviceActionLabel(action.action, _english);
    final available = action.available;

    String? conditionText;
    if (!available) {
      conditionText = switch (action.code) {
        'permission_denied' =>
            _english ? 'Requires authorization' : '需特权授权',
        'unsupported_action' =>
            _english ? 'Unsupported' : '设备不支持',
        _ => action.code,
      };
    }

    return Container(
      constraints: const BoxConstraints(minHeight: 44),
      margin: const EdgeInsets.symmetric(vertical: 2),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: palette.surfaceSecondary.withValues(alpha: 0.25),
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Icon(
            available ? LucideIcons.check : LucideIcons.minus,
            size: 14,
            color: available ? palette.accentPrimary : palette.textTertiary,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              actionName,
              style: TextStyle(
                fontSize: 13,
                color: palette.textPrimary,
              ),
            ),
          ),
          Text(
            available
                ? (_english ? 'Available' : '可用')
                : (conditionText ?? (_english ? 'Unavailable' : '不可用')),
            style: TextStyle(
              fontSize: 11.5,
              fontWeight: FontWeight.w500,
              color: available ? palette.accentPrimary : palette.textTertiary,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTechnicalDetailsSection(
    BuildContext context,
    SystemDeviceCapabilities capabilities,
  ) {
    final palette = context.omniPalette;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () {
            setState(() {
              _showTechnicalDetails = !_showTechnicalDetails;
            });
          },
          child: Container(
            constraints: const BoxConstraints(minHeight: 48),
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            child: Row(
              children: [
                Icon(
                  LucideIcons.terminal,
                  size: 14,
                  color: palette.textTertiary,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _english ? 'Technical Details' : '技术字段详情',
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w500,
                      color: palette.textSecondary,
                    ),
                  ),
                ),
                Icon(
                  _showTechnicalDetails
                      ? LucideIcons.chevronUp
                      : LucideIcons.chevronDown,
                  size: 14,
                  color: palette.textTertiary,
                ),
              ],
            ),
          ),
        ),
        if (_showTechnicalDetails)
          Container(
            margin: const EdgeInsets.only(top: 4),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: palette.surfaceSecondary.withValues(alpha: 0.4),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildTechRow('requestId', capabilities.requestId, palette),
                _buildTechRow('action', capabilities.action, palette),
                _buildTechRow('code', capabilities.code, palette),
                _buildTechRow('backend', capabilities.backend, palette),
                _buildTechRow('provider', capabilities.provider, palette),
                _buildTechRow('uid', '${capabilities.uid ?? 'null'}', palette),
                if (capabilities.message.isNotEmpty)
                  _buildTechRow('message', capabilities.message, palette),
              ],
            ),
          ),
      ],
    );
  }

  Widget _buildTechRow(String key, String value, dynamic palette) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 80,
            child: Text(
              key,
              style: TextStyle(
                fontSize: 11,
                fontFamily: 'monospace',
                color: palette.textTertiary,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                fontSize: 11,
                fontFamily: 'monospace',
                color: palette.textSecondary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
