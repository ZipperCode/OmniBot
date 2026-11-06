import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:ui/theme/theme_context.dart';

class PermissionRow extends StatelessWidget {
  final String iconPath;
  final double iconWidth;
  final double iconHeight;
  final String permissionName;
  final String permissionDescription;
  final Future<void> Function() onAuthorize;
  final ValueNotifier<bool> isAuthorized; // 新增权限状态
  final String? iconInfo; // info 文案
  final VoidCallback? iconClick; // info 点击事件

  const PermissionRow({
    super.key,
    required this.iconPath,
    required this.iconWidth,
    required this.iconHeight,
    required this.permissionName,
    required this.permissionDescription,
    required this.onAuthorize,
    required this.isAuthorized, // 新增参数
    this.iconInfo,
    this.iconClick,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    return ValueListenableBuilder<bool>(
      valueListenable: isAuthorized,
      builder: (context, authorized, child) {
        return GestureDetector(
          onTap: () async {
            await onAuthorize();
          },
          behavior: HitTestBehavior.translucent,
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              SizedBox(
                width: 32,
                height: 32,
                child: iconPath.endsWith('.svg')
                    ? SvgPicture.asset(
                        iconPath,
                        width: iconWidth,
                        height: iconHeight,
                        colorFilter: ColorFilter.mode(
                          palette.textPrimary,
                          BlendMode.srcIn,
                        ),
                      )
                    : Image.asset(
                        iconPath,
                        width: iconWidth,
                        height: iconHeight,
                      ),
              ),
              const SizedBox(width: 16.0),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Flexible(
                          child: Text(
                            permissionName,
                            style: TextStyle(
                              color: palette.textPrimary,
                              fontSize: 15,
                              height: 1.5,
                              fontFamily: 'PingFang SC',
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ),
                        if (iconInfo != null && iconClick != null) ...[
                          GestureDetector(
                            onTap: iconClick,
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                                vertical: 4,
                              ),
                              decoration: BoxDecoration(
                                color: palette.surfaceSecondary,
                                borderRadius: BorderRadius.circular(12),
                                border: Border.all(color: palette.borderSubtle),
                              ),
                              child: Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    Icons.info,
                                    size: 14,
                                    color: palette.textSecondary,
                                  ),
                                  const SizedBox(width: 4),
                                  Text(
                                    iconInfo!,
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: palette.textSecondary,
                                      fontWeight: FontWeight.w500,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                    SizedBox(height: 2.0),
                    Text(
                      permissionDescription,
                      style: TextStyle(
                        color: palette.textSecondary,
                        fontSize: 12,
                        fontFamily: 'PingFang SC',
                        fontWeight: FontWeight.w400,
                        height: 1.50,
                      ),
                    ),
                  ],
                ),
              ),
              Align(
                alignment: Alignment.centerRight,
                child: authorized
                    ? SizedBox(
                        width: 24,
                        height: 24,
                        child: Center(
                          child: SvgPicture.asset(
                            'assets/welcome/permission_authorized.svg',
                            width: 24,
                            height: 24,
                            colorFilter: ColorFilter.mode(
                              palette.accentPrimary,
                              BlendMode.srcIn,
                            ),
                          ),
                        ),
                      )
                    : SizedBox(
                        width: 20,
                        height: 20,
                        child: Center(
                          child: SvgPicture.asset(
                            'assets/welcome/permission_go.svg',
                            width: 20,
                            height: 20,
                            colorFilter: ColorFilter.mode(
                              palette.textTertiary,
                              BlendMode.srcIn,
                            ),
                          ),
                        ),
                      ),
              ),
            ],
          ),
        );
      },
    );
  }
}
