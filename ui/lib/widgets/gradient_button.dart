import 'package:flutter/material.dart';
import 'package:ui/theme/theme_context.dart';

/// Legacy button API rendered with the app's solid action surface.
class GradientButton extends StatelessWidget {
  /// 按钮文字
  final String text;

  /// 按钮宽度
  final double width;

  /// 按钮高度
  final double height;

  /// 点击事件回调
  final VoidCallback? onTap;

  /// 文字样式，如果不提供则使用默认样式
  final TextStyle? textStyle;

  /// 圆角半径
  final double borderRadius;

  /// 是否启用（禁用时会显示灰色）
  final bool enabled;

  /// 文字后的图标（可选）
  final Widget? trailingIcon;

  /// 文字和图标之间的间距
  final double iconSpacing;

  const GradientButton({
    super.key,
    required this.text,
    required this.onTap,
    this.width = 166,
    this.height = 44,
    this.textStyle,
    this.borderRadius = 14,
    this.enabled = true,
    this.trailingIcon,
    this.iconSpacing = 4,
  });

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;
    final active = enabled && onTap != null;
    final fill = active ? palette.accentPrimary : palette.surfaceElevated;
    final foreground = active
        ? Theme.of(context).colorScheme.onPrimary
        : palette.textTertiary;
    return Material(
      color: Colors.transparent,
      child: Ink(
        decoration: ShapeDecoration(
          color: fill,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.all(Radius.circular(borderRadius)),
          ),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(borderRadius), // 波纹与圆角一致
          onTap: enabled ? onTap : null,
          child: SizedBox(
            width: width,
            height: height,
            child: Row(
              mainAxisSize: MainAxisSize.max,
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Flexible(
                  child: Text(
                    text,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                    style:
                        textStyle ??
                        TextStyle(
                          color: foreground,
                          fontSize: 15,
                          fontWeight: FontWeight.w500,
                          height: 1.5,
                          letterSpacing: 0.5,
                        ),
                  ),
                ),
                if (trailingIcon != null) ...[
                  SizedBox(width: iconSpacing),
                  IconTheme(
                    data: IconThemeData(color: foreground),
                    child: trailingIcon!,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
