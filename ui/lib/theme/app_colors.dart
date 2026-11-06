import 'package:flutter/material.dart';

/// Legacy color names remain source compatible with the neutral design tokens.
/// New UI should resolve theme-aware colors through `context.omniPalette`.
class AppColors {
  // 品牌色系
  static const Color primaryBlue = Color(0xFF161616);
  static const Color onBrandPrimary = Color(0xFFFFFFFF); // 品牌色上的文本 - 白色
  static const Color primaryBlue03 = Color(0x08161616);
  static const Color gradientAux = Color(0xFF777777);
  static const Color iconPrimary = Color(0xFF161616);
  static const Color buttonPrimary = Color(0xFF161616);
  static const Color buttonSmall = Color(0xFFF0F0F0);

  // IP 色系
  static const Color ipBluePurple = Color(0xFF4658FF);
  static const Color ipBlueGreen = Color(0xFF00CFE1);

  // 特殊提示色
  static const Color alertRed = Color(0xFFFF6464);
  static const Color linkBlue = Color(0xFF666666);

  // 文本颜色 - 黑色系
  static const Color text90 = Color(0xE5000000); // 90% 黑
  static const Color text10 = Color(0x1A000000); // 10% 黑
  static const Color text05 = Color(0x0C000000); // 5% 黑
  static const Color text03 = Color(0x08000000); // 3% 黑

  static const Color text = Color(0xFF161616); // 字色
  static const Color text70 = Color(0xB2161616); // 70% 字色
  static const Color text50 = Color(0x80161616); // 50% 字色
  static const Color text20 = Color(0x33161616); // 20% 字色

  // 边框颜色
  static const Color borderStandard = Color(0x1A000000);

  // 按钮文本颜色 - 白色系
  static const Color buttonText100 = Color(0xFFFFFFFF); // 100% 白
  static const Color buttonText90 = Color(0xE5FFFFFF); // 90% 白
  static const Color buttonText50 = Color(0x80FFFFFF); // 50% 白

  static const Color fillStandardSecondary = Color(0x1A000000); // 标准填充 - 次级

  // 实底色
  static const Color solid20Black = Color(0xFFC6C6C6); // 20% 黑实底

  // 背景色
  static const Color white = Colors.white;
  static const Color background = Color(0xFFF6F6F6);

  // 品牌渐变（基于设计系统）
  static const LinearGradient primaryGradient = LinearGradient(
    colors: [primaryBlue, gradientAux],
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
  );

  // 按钮渐变
  static LinearGradient buttonGradient = LinearGradient(
    begin: Alignment(0.47, 0.41),
    end: Alignment(1.08, 1.42),
    colors: [primaryBlue, gradientAux.withValues(alpha: 0.7)],
  );

  static BoxShadow boxShadow = BoxShadow(
    color: Colors.black.withValues(alpha: 0.01),
    blurRadius: 4,
    spreadRadius: 2,
  );
}
