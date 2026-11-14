import type {
  SkillCandidateAsset,
  SkillFeedbackEligibility,
  SkillRevisionSnapshot,
} from "./types";

/**
 * 确定技能审核通过（启用 / 试用）请求的载荷参数。
 * 遵循 local://skill-assets-contract.md：
 * - 新建技能：expectedCurrentContentHash 必须为 null；
 * - 更新已有技能：expectedCurrentContentHash 从已查询的 feedbackEligibility.currentContentHash 获取；
 * - targetSkillId 优先取 candidate.targetSkillId，回退 candidate.name。
 */
export function resolveSkillApprovePayload(
  candidate: SkillCandidateAsset,
  feedbackEligibility?: SkillFeedbackEligibility | null,
): { targetSkillId: string; expectedCurrentContentHash: string | null } {
  const targetSkillId = (candidate.targetSkillId || candidate.name).trim();
  const isUpdatingExisting = Boolean(
    feedbackEligibility &&
    feedbackEligibility.skillId === targetSkillId &&
    feedbackEligibility.currentContentHash
  );

  return {
    targetSkillId,
    expectedCurrentContentHash: isUpdatingExisting
      ? (feedbackEligibility?.currentContentHash ?? null)
      : null,
  };
}

/**
 * 判断指定技能是否满足后续任务实际调用的反馈条件。
 */
export function isSkillFeedbackEligible(
  feedbackEligibility?: SkillFeedbackEligibility | null,
): boolean {
  if (!feedbackEligibility) return false;
  return Boolean(
    feedbackEligibility.eligible === true &&
    feedbackEligibility.skillId &&
    feedbackEligibility.revisionId &&
    feedbackEligibility.usage
  );
}

/**
 * 过滤出可恢复的历史版本列表。
 * 已被用户手动停用/退役的版本（wasManuallyDisabled === true）严禁自动复活。
 */
export function filterRestorableSkillRevisions(
  revisions: SkillRevisionSnapshot[],
): SkillRevisionSnapshot[] {
  return revisions.filter((rev) => rev.canRestore && !rev.wasManuallyDisabled);
}

/**
 * 判断候选技能是否指向已存在的技能目标（覆盖已有技能时需明确确认）。
 */
export function isExistingSkillTarget(
  candidate: SkillCandidateAsset,
  feedbackEligibility?: SkillFeedbackEligibility | null,
  revisions?: SkillRevisionSnapshot[],
): boolean {
  const targetSkillId = (candidate.targetSkillId || candidate.name).trim();
  if (!targetSkillId) return false;
  const matchesEligibility = Boolean(
    feedbackEligibility &&
    feedbackEligibility.skillId === targetSkillId &&
    Boolean(feedbackEligibility.currentContentHash)
  );
  const hasRevisions = Boolean(revisions && revisions.length > 0);
  return matchesEligibility || hasRevisions;
}

/**
 * 将提炼跳过原因转为安全、友好的用户提示。
 */
export function formatSkipReason(reason?: string | null): string {
  switch (reason) {
    case "ineligible":
      return "任务未满足提炼要求（需由小万正式完成，包含至少 3 个成功工具调用且跨越 2 种以上工具，无工具失败或授权拒绝）。";
    case "busy":
      return "后台技能提炼服务繁忙，已跳过本次提炼。";
    case "sensitive":
      return "任务上下文包含敏感信息或凭据，出于安全合规要求已跳过提炼。";
    case "too_large":
      return "任务上下文体积超出 16KiB 限制，已跳过提炼。";
    case "provider_changed":
      return "原任务模型提供商或认证凭据已变更，已跳过提炼。";
    case "no_suggestion":
      return "模型评估本次任务暂无可泛化的可重用技能。";
    default:
      return reason ? `已跳过提炼 (${reason})` : "已跳过提炼（条件不符）";
  }
}

/**
 * 安全格式化技能资产错误，包含官方/内置技能保护和 CAS 冲突提示。
 */
export function formatSafeSkillError(rawError?: string | null): string {
  if (!rawError) return "操作失败，请重试。";
  if (rawError.includes("skill_cannot_overwrite_builtin")) {
    return "官方或系统内置技能受保护，禁止覆盖。请更改技能名称后重试。";
  }
  if (rawError.includes("skill_name_conflict")) {
    return "技能名称已存在且与现有类型冲突，请使用不同名称。";
  }
  if (rawError.includes("skill_content_conflict") || rawError.includes("skill_content_modified")) {
    return "目标技能内容已被外部修改，请重新加载核对。";
  }
  if (rawError.includes("skill_revision_conflict") || isCasConflictError(rawError)) {
    return "检测到版本冲突：技能资产已被其他操作或后台更新。您的本地编辑已保留，请核对后重新加载最新版本或重新保存。";
  }
  if (rawError.includes("skill_candidate_not_found")) {
    return "技能候选未找到，可能已被删除或处理。";
  }
  if (rawError.includes("skill_feedback_ineligible")) {
    return "该技能当前不满足反馈条件（需在后续独立任务中实际调用）。";
  }
  if (rawError.includes("skill_revision_retired")) {
    return "该版本已被手动退役，禁止恢复。";
  }
  if (rawError.includes("skill_validation_failed")) {
    return "技能格式校验未通过，请检查名称（英文短横线）、简述（≤240字）和正文。";
  }
  return rawError;
}

/**
 * 判断错误是否为 CAS 版本冲突。
 * 精确以具体版本冲突标识判定，避免将同属 409 的 name/content/protected 冲突误判为 CAS。
 */
export function isCasConflictError(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  if (
    msg.includes("skill_name_conflict") ||
    msg.includes("skill_cannot_overwrite_builtin") ||
    msg.includes("skill_content_conflict") ||
    msg.includes("skill_content_modified") ||
    msg.includes("skill_validation_failed") ||
    msg.includes("skill_candidate_not_found")
  ) {
    return false;
  }
  return (
    msg.includes("skill_revision_conflict") ||
    msg.includes("版本冲突") ||
    (msg.includes("409") && msg.includes("revision"))
  );
}
