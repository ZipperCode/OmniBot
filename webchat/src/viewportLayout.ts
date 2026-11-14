export function isCompactAvailableHeight(height: number): boolean {
  return height < 420;
}

export function computeNonBlockingSummary(
  outboxCount: number,
  attachmentCount: number,
): { hasNonBlocking: boolean; summaryText: string | null } {
  const hasNonBlocking = outboxCount > 0 || attachmentCount > 0;
  if (!hasNonBlocking) {
    return { hasNonBlocking: false, summaryText: null };
  }

  const parts: string[] = [];
  if (outboxCount > 0) parts.push(`待发 ${outboxCount} 条`);
  if (attachmentCount > 0) parts.push(`附件 ${attachmentCount} 个`);
  parts.push("更多");

  return {
    hasNonBlocking: true,
    summaryText: parts.join(" · "),
  };
}
