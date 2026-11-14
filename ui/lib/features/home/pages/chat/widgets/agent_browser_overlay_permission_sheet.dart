import 'package:flutter/material.dart';

import 'package:ui/features/home/pages/authorize/authorize_page_args.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_prompt_sheet.dart';
import 'package:ui/features/home/pages/authorize/widgets/permission_section.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/services/agent_browser_session_service.dart';

/// System overlay browser permission prompt backed by the shared glass
/// permission card. Authorization is checked/requested through the
/// AgentBrowserSession channel, the same surface that owns the overlay
/// browser window, so the sheet can never disagree with the native host.
class AgentBrowserOverlayPermissionSheet {
  AgentBrowserOverlayPermissionSheet._();

  static Future<bool> show(BuildContext context) {
    return PermissionPromptSheet.show(
      context,
      permissions: <PermissionData>[
        PermissionData(
          id: kOverlayPermissionId,
          iconPath: 'assets/welcome/permission_overlay.svg',
          iconWidth: 32,
          iconHeight: 32,
          name: LegacyTextLocalizer.isEnglish
                  ? 'Overlay Permission'
                  : '悬浮窗权限',
          description: LegacyTextLocalizer.isEnglish
                  ? 'Show the agent browser as a system overlay'
                  : '以系统悬浮窗展示浏览器页面',
          onAuthorize: () async {
            await AgentBrowserSessionService.requestOverlayPermission();
          },
          checkAuthorization: () =>
              AgentBrowserSessionService.isOverlayPermitted(),
        ),
      ],
      actionLabel: LegacyTextLocalizer.localize('打开浏览器'),
      actionKey: const ValueKey(
        'agent-browser-overlay-permission-continue-button',
      ),
    );
  }
}
