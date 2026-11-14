import type { WorkspaceChangeRecord, WorkspaceChangeRestoreResult } from "./types";

/**
 * 提取工作区变更恢复所需的预期后置哈希集合。
 * 遵循 clean cutover 规范（local://workspace-change-contract.md）：
 * - 严格使用服务端计算并下发的真实 expectedAfterHashes 字段；
 * - 若服务端未下发或为空，返回 null，前端标记为缺失凭据且不可恢复，绝不在前端猜测 fallback。
 */
export function resolveWorkspaceChangeHashes(
  record: WorkspaceChangeRecord,
): Record<string, string | null> | null {
  if (record.expectedAfterHashes && Object.keys(record.expectedAfterHashes).length > 0) {
    return { ...record.expectedAfterHashes };
  }
  return null;
}

/**
 * 判断当前变更记录是否满足恢复条件：
 * 必须 snapshotStatus === "ready" 且 canRestore === true，且存在服务端下发的 expectedAfterHashes。
 */
export function isRestoreEligible(
  record: WorkspaceChangeRecord | null | undefined,
): boolean {
  if (!record) return false;
  return Boolean(
    record.snapshotStatus === "ready" &&
    record.canRestore === true &&
    resolveWorkspaceChangeHashes(record) !== null
  );
}

/**
 * 根据服务端返回的强类型 error.code 与 snapshot 分类处理恢复异常。
 * 绝不依赖特定自然语言消息内容进行 substring matching。
 */
export function classifyWorkspaceChangeRestoreError(
  error: unknown,
  fallbackDiff?: string,
): {
  isConflict: boolean;
  isBusy: boolean;
  conflictDiff?: string;
  userMessage: string;
} {
  const errObj = error as { code?: string; snapshot?: Record<string, unknown>; message?: string } | null;
  const code = errObj?.code;
  const snapshot = errObj?.snapshot;

  if (code === "workspace_change_conflict") {
    const currentDiff = typeof snapshot?.diffText === "string"
      ? snapshot.diffText
      : fallbackDiff;
    return {
      isConflict: true,
      isBusy: false,
      conflictDiff: currentDiff,
      userMessage: "恢复冲突：工作区当前文件内容已发生变动，与快照预期不匹配。禁止强制覆盖。",
    };
  }

  if (code === "workspace_change_busy") {
    return {
      isConflict: false,
      isBusy: true,
      userMessage: "工作区正忙：有子任务或操作正在写入该工作区，请稍后重试。",
    };
  }

  return {
    isConflict: false,
    isBusy: false,
    userMessage: errObj?.message || (error instanceof Error ? error.message : "恢复失败"),
  };
}

export function classifyWorkspaceChangeRestoreStatus(result: WorkspaceChangeRestoreResult): {
  isSuccess: boolean;
  conflictDiffRequired: boolean;
  isBusy: boolean;
  userMessage: string;
} {
  if (result.success) {
    return {
      isSuccess: true,
      conflictDiffRequired: false,
      isBusy: false,
      userMessage: result.message || "已成功确认恢复",
    };
  }

  if (result.status === "workspace_change_conflict") {
    return {
      isSuccess: false,
      conflictDiffRequired: true,
      isBusy: false,
      userMessage: "恢复冲突：工作区当前文件内容已发生变动，与快照预期不匹配。禁止强制覆盖。",
    };
  }

  if (result.status === "workspace_change_busy") {
    return {
      isSuccess: false,
      conflictDiffRequired: false,
      isBusy: true,
      userMessage: "工作区正忙：有子任务或操作正在写入该工作区，请稍后重试。",
    };
  }

  return {
    isSuccess: false,
    conflictDiffRequired: false,
    isBusy: false,
    userMessage: result.message || "恢复失败",
  };
}
