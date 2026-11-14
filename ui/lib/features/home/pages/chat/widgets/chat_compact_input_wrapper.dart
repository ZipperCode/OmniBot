import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:ui/features/home/pages/command_overlay/widgets/chat_input_area.dart';
import 'package:ui/l10n/legacy_text_localizer.dart';
import 'package:ui/theme/theme_context.dart';
import 'package:ui/widgets/omni_circle_button.dart';
import 'package:ui/widgets/omni_glass.dart';

/// Available viewport height threshold (logical pixels) for compact mode.
///
/// Evaluated as:
///   `MediaQuery.size.height - viewInsets.bottom - padding.vertical < 420`
const double kCompactAvailableHeightThreshold = 420.0;

/// Checks whether the current context is under the low-height threshold (e.g. 320x640 + IME, landscape).
bool isCompactLayoutHeight(BuildContext context) {
  final mediaQuery = MediaQuery.of(context);
  final availableHeight = mediaQuery.size.height -
      mediaQuery.viewInsets.bottom -
      mediaQuery.padding.vertical;
  return availableHeight < kCompactAvailableHeightThreshold;
}

/// A layout wrapper for the chat input region that collapses non-blocking indicators
/// into a single capsule row when viewport height is constrained (< 420 logical px).
///
/// Structure is strictly isomorphic between normal and compact modes so the input
/// tree position, FocusNode, TextEditingController, and platform IME stay 100% stable.
///
/// In compact mode:
/// - Non-blocking status (outbox items, attachments, secondary hints) collapses to:
///   "待发 N · 附件 N · 更多", expandable to a sheet capped at 70% available height.
/// - Blocking cards (approvals, permissions) and critical errors MUST NOT be folded.
/// - The input field retains its original min/maxLines, FocusNode, and IME state.
class ChatCompactInputWrapper extends StatelessWidget {
  const ChatCompactInputWrapper({
    super.key,
    required this.inputChild,
    this.outboxCount = 0,
    this.attachmentCount = 0,
    this.hasMoreIndicators = false,
    this.nonBlockingSummaryText,
    this.outboxPendingBar,
    this.attachments = const [],
    this.onRemoveAttachment,
    this.nonBlockingDetailsChild,
    this.blockingCardChild,
    this.criticalErrorChild,
    this.onExpandNonBlocking,
  });

  final Widget inputChild;
  final int outboxCount;
  final int attachmentCount;
  final bool hasMoreIndicators;
  final String? nonBlockingSummaryText;
  final Widget? outboxPendingBar;
  final List<ChatInputAttachment> attachments;
  final ValueChanged<String>? onRemoveAttachment;
  final Widget? nonBlockingDetailsChild;
  final Widget? blockingCardChild;
  final Widget? criticalErrorChild;
  final VoidCallback? onExpandNonBlocking;

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final availableHeight = mediaQuery.size.height -
        mediaQuery.viewInsets.bottom -
        mediaQuery.padding.vertical;
    final isCompact = availableHeight < kCompactAvailableHeightThreshold;

    final effectiveAttachmentCount =
        attachmentCount > 0 ? attachmentCount : attachments.length;
    final hasNonBlockingStatus =
        outboxCount > 0 || effectiveAttachmentCount > 0 || hasMoreIndicators;

    final summaryLabel = nonBlockingSummaryText ??
        _formatCompactSummary(
          outboxCount: outboxCount,
          attachmentCount: effectiveAttachmentCount,
          hasMore: hasMoreIndicators,
        );

    // Isomorphic 4-slot structure: slot 0 (error), slot 1 (blocking), slot 2 (aux), slot 3 (input).
    // The slot index of inputChild never shifts across height/mode transitions.
    final Widget criticalErrorSlot =
        criticalErrorChild ?? const SizedBox.shrink();
    final Widget blockingCardSlot =
        blockingCardChild ?? const SizedBox.shrink();

    final Widget nonBlockingSlot = !isCompact
        ? (outboxPendingBar ??
            nonBlockingDetailsChild ??
            const SizedBox.shrink())
        : (hasNonBlockingStatus
            ? Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                child: _CompactStatusCapsule(
                  key: const ValueKey('chat-compact-status-capsule'),
                  label: summaryLabel,
                  onTap: () {
                    onExpandNonBlocking?.call();
                    _showNonBlockingSheet(
                      context,
                      maxHeight: availableHeight * 0.7,
                      outboxPendingBar: outboxPendingBar,
                      attachments: attachments,
                      onRemoveAttachment: onRemoveAttachment,
                      detailsChild: nonBlockingDetailsChild,
                    );
                  },
                ),
              )
            : const SizedBox.shrink());

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        criticalErrorSlot,
        blockingCardSlot,
        nonBlockingSlot,
        inputChild,
      ],
    );
  }

  static String _formatCompactSummary({
    required int outboxCount,
    required int attachmentCount,
    required bool hasMore,
  }) {
    final isEnglish = LegacyTextLocalizer.isEnglish;
    final parts = <String>[];

    if (outboxCount > 0) {
      parts.add(isEnglish ? 'Queued $outboxCount' : '待发 $outboxCount');
    }
    if (attachmentCount > 0) {
      parts.add(isEnglish ? 'Attachments $attachmentCount' : '附件 $attachmentCount');
    }
    if (hasMore || parts.isEmpty) {
      parts.add(isEnglish ? 'More' : '更多');
    }
    return parts.join(' · ');
  }

  static Future<void> _showNonBlockingSheet(
    BuildContext context, {
    required double maxHeight,
    Widget? outboxPendingBar,
    List<ChatInputAttachment> attachments = const [],
    ValueChanged<String>? onRemoveAttachment,
    Widget? detailsChild,
  }) async {
    final palette = context.omniPalette;
    final isEnglish = LegacyTextLocalizer.isEnglish;

    await showOmniGlassBottomSheet<void>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      builder: (sheetContext) => SafeArea(
        top: false,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: maxHeight),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Container(
                  margin: const EdgeInsets.only(top: 10, bottom: 8),
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: palette.borderStrong,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 16, 8),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        isEnglish ? 'Pending Items & Attachments' : '待发与附件',
                        style: TextStyle(
                          color: palette.textPrimary,
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    OmniCircleButton(
                      icon: LucideIcons.x,
                      size: 36,
                      iconSize: 18,
                      tooltip: isEnglish ? 'Close' : '关闭',
                      onPressed: () => Navigator.of(sheetContext).pop(),
                    ),
                  ],
                ),
              ),
              Flexible(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (outboxPendingBar != null) ...[
                        outboxPendingBar,
                        const SizedBox(height: 8),
                      ],
                      if (attachments.isNotEmpty) ...[
                        Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 4,
                            vertical: 4,
                          ),
                          child: Text(
                            isEnglish
                                ? 'Attachments (${attachments.length})'
                                : '已选附件 (${attachments.length})',
                            style: TextStyle(
                              color: palette.textSecondary,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        for (final attachment in attachments)
                          Container(
                            margin: const EdgeInsets.symmetric(vertical: 3),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 8,
                            ),
                            decoration: BoxDecoration(
                              color: palette.surfaceSecondary,
                              borderRadius: BorderRadius.circular(10),
                              border: Border.all(color: palette.borderSubtle),
                            ),
                            child: Row(
                              children: [
                                Icon(
                                  attachment.isImage
                                      ? LucideIcons.image
                                      : LucideIcons.file,
                                  size: 16,
                                  color: palette.accentPrimary,
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    attachment.name,
                                    style: TextStyle(
                                      color: palette.textPrimary,
                                      fontSize: 13,
                                    ),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                  ),
                                ),
                                if (onRemoveAttachment != null)
                                  InkWell(
                                    onTap: () {
                                      onRemoveAttachment(attachment.id);
                                    },
                                    borderRadius: BorderRadius.circular(12),
                                    child: Padding(
                                      padding: const EdgeInsets.all(4),
                                      child: Icon(
                                        LucideIcons.x,
                                        size: 14,
                                        color: palette.textSecondary,
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                          ),
                        const SizedBox(height: 8),
                      ],
                      if (detailsChild != null) detailsChild,
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CompactStatusCapsule extends StatelessWidget {
  const _CompactStatusCapsule({
    super.key,
    required this.label,
    required this.onTap,
  });

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final palette = context.omniPalette;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(20),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        decoration: BoxDecoration(
          color: palette.surfaceSecondary,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              LucideIcons.layers,
              size: 14,
              color: palette.accentPrimary,
            ),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                label,
                style: TextStyle(
                  color: palette.textSecondary,
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 4),
            Icon(
              LucideIcons.chevronUp,
              size: 14,
              color: palette.textSecondary,
            ),
          ],
        ),
      ),
    );
  }
}
