import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/theme/theme_context.dart';

import 'card_widget_factory.dart' show OnRequestAuthorize;

class PermissionSectionCard extends StatefulWidget {
  final Map<String, dynamic> cardData;
  final OnRequestAuthorize? onRequestAuthorize;

  const PermissionSectionCard({
    super.key,
    required this.cardData,
    this.onRequestAuthorize,
  });

  @override
  State<PermissionSectionCard> createState() => _PermissionSectionCardState();
}

class _PermissionSectionCardState extends State<PermissionSectionCard>
    with WidgetsBindingObserver {
  late List<PermissionData> _backgroundPermissions;
  late List<String> _effectiveIds;
  int _checkGeneration = 0;
  bool _isChecking = false;
  bool _isNavigating = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initPermissions();

    final isHistorical = widget.cardData['isHistorical'] == true;
    final canAutoOpen = widget.cardData['autoOpenAuthorization'] == true &&
        !isHistorical &&
        widget.onRequestAuthorize != null;

    if (canAutoOpen) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (!mounted) return;
        // 先执行真实只读检查；若已全满足，零 push；仅在非历史卡且确实缺失权限时才自动开启
        await _refreshPermissions();
        if (mounted && !_isAllGranted) {
          await _onTapAuthorize();
        }
      });
    } else {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          _refreshPermissions();
        }
      });
    }
  }

  void _initPermissions() {
    final rawIds = normalizeRequiredPermissionIds(
      widget.cardData['requiredPermissionIds'] as List?,
    );
    _effectiveIds = rawIds.isEmpty
        ? kTaskExecutionRequiredPermissionIds
        : rawIds;
    _backgroundPermissions = [
      ...PermissionService.buildDisplayPermissionsForIds(_effectiveIds),
    ];
  }

  @override
  void didUpdateWidget(covariant PermissionSectionCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    final oldRawIds = normalizeRequiredPermissionIds(
      oldWidget.cardData['requiredPermissionIds'] as List?,
    );
    final newRawIds = normalizeRequiredPermissionIds(
      widget.cardData['requiredPermissionIds'] as List?,
    );
    if (!listEquals(oldRawIds, newRawIds)) {
      _initPermissions();
      _refreshPermissions();
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshPermissions();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // 递增 generation，使在途异步结果失效，避免向已释放或已重建的 notifier 写入
    _checkGeneration++;
    super.dispose();
  }

  bool get _isAllGranted {
    if (_effectiveIds.isEmpty) return false;
    final grantedMap = {
      for (final p in _backgroundPermissions) p.id: p.notifier.value,
    };
    return _effectiveIds.every((id) => grantedMap[id] == true);
  }

  Future<void> _refreshPermissions() async {
    final currentGen = ++_checkGeneration;
    if (mounted) {
      setState(() {
        _isChecking = true;
      });
    }

    final results = <String, bool>{};
    for (final perm in _backgroundPermissions) {
      final granted = await perm.checkAuthorization();
      results[perm.id] = granted;
    }

    if (!mounted || currentGen != _checkGeneration) {
      return;
    }

    for (final perm in _backgroundPermissions) {
      if (results.containsKey(perm.id)) {
        perm.notifier.value = results[perm.id]!;
      }
    }

    setState(() {
      _isChecking = false;
    });
  }

  Future<void> _onTapAuthorize() async {
    if (_isChecking || _isNavigating) return;

    // 点击前再次检查真实状态，防止后台已授权导致的竞态跳页
    await _refreshPermissions();
    if (!mounted) return;

    if (_isAllGranted) {
      return;
    }

    _isNavigating = true;
    setState(() {});
    try {
      await widget.onRequestAuthorize?.call(_effectiveIds);
    } finally {
      if (mounted) {
        _isNavigating = false;
        await _refreshPermissions();
      }
    }
  }

  String get _buttonText {
    if (_isChecking || _isNavigating) {
      return LegacyTextLocalizer.localize('权限检查中...');
    }
    if (_isAllGranted) {
      return LegacyTextLocalizer.localize('已开启');
    }
    return '前往开启';
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final isActionDisabled = _isAllGranted || _isChecking || _isNavigating;

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 4),
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: palette.surfacePrimary,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: palette.borderSubtle, width: 0.5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          IgnorePointer(
            child: PermissionSection(
              permissions: _backgroundPermissions,
              spacing: 18,
            ),
          ),
          const SizedBox(height: 18),
          FilledButton(
            onPressed: isActionDisabled ? null : _onTapAuthorize,
            style: FilledButton.styleFrom(
              backgroundColor: _isAllGranted
                  ? palette.surfaceSecondary
                  : palette.accentPrimary,
              foregroundColor: _isAllGranted
                  ? palette.textTertiary
                  : Theme.of(context).colorScheme.onPrimary,
              disabledBackgroundColor: _isAllGranted
                  ? palette.surfaceSecondary
                  : palette.accentPrimary.withValues(alpha: 0.5),
              disabledForegroundColor: _isAllGranted
                  ? palette.textTertiary
                  : Theme.of(context).colorScheme.onPrimary.withValues(alpha: 0.7),
              minimumSize: const Size.fromHeight(44),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
            child: Text(
              _buttonText,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}
