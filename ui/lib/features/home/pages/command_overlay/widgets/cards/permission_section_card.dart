import 'package:flutter/material.dart';
import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/services/permission_service.dart';
import 'package:ui/theme/theme_context.dart';

class PermissionSectionCard extends StatefulWidget {
  final Map<String, dynamic> cardData;
  final void Function(List<String> requiredPermissionIds)? onRequestAuthorize;

  const PermissionSectionCard({
    super.key,
    required this.cardData,
    this.onRequestAuthorize,
  });

  @override
  State<PermissionSectionCard> createState() => _PermissionSectionCardState();
}

class _PermissionSectionCardState extends State<PermissionSectionCard> {
  late final List<PermissionData> _backgroundPermissions;

  @override
  void initState() {
    super.initState();
    final requiredPermissionIds = normalizeRequiredPermissionIds(
      widget.cardData['requiredPermissionIds'] as List?,
    );
    _backgroundPermissions = [
      ...PermissionService.buildDisplayPermissionsForIds(
        requiredPermissionIds.isEmpty
            ? kTaskExecutionRequiredPermissionIds
            : requiredPermissionIds,
      ),
    ];
    if (widget.cardData['autoOpenAuthorization'] == true &&
        widget.onRequestAuthorize != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _goAuthorizePage();
      });
    }
  }

  void _goAuthorizePage() {
    final requiredPermissionIds = normalizeRequiredPermissionIds(
      widget.cardData['requiredPermissionIds'] as List?,
    );
    widget.onRequestAuthorize?.call(
      requiredPermissionIds.isEmpty
          ? kTaskExecutionRequiredPermissionIds
          : requiredPermissionIds,
    );
  }

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
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
            onPressed: _goAuthorizePage,
            style: FilledButton.styleFrom(
              backgroundColor: palette.accentPrimary,
              foregroundColor: Theme.of(context).colorScheme.onPrimary,
              minimumSize: const Size.fromHeight(44),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(14),
              ),
            ),
            child: const Text(
              '前往开启',
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }
}
