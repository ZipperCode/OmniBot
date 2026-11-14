import type {
  Attachment,
  OutboxItem,
  OutboxItemState,
  OutboxMutationBody,
  OutboxMutationRequest,
  OutboxOperation,
  OutboxSnapshot,
} from "./types";

export function sortOutboxItemsFifo(items: OutboxItem[]): OutboxItem[] {
  return [...items].sort((left, right) => {
    if (left.sequence !== right.sequence) {
      return left.sequence - right.sequence;
    }
    return left.createdAt - right.createdAt;
  });
}

export function outboxItemText(item: OutboxItem): string {
  if (typeof item.text === "string") return item.text;
  if (typeof item.payload?.text === "string") return item.payload.text;
  return "";
}

export function outboxItemAttachments(item: OutboxItem): Attachment[] {
  if (Array.isArray(item.attachments)) return item.attachments;
  if (Array.isArray(item.payload?.attachments)) return item.payload.attachments;
  return [];
}

export function outboxStateLabel(state: OutboxItemState): string {
  switch (state) {
    case "pending":
      return "排队中";
    case "dispatching":
      return "发送中";
    case "paused":
      return "已暂停";
    case "submitted":
      return "已提交";
    default:
      return state;
  }
}

/** 仅 pending/paused 可修改、移除或停止并发送；dispatching/submitted 为只读 */
export function isItemModifiable(state: OutboxItemState): boolean {
  return state === "pending" || state === "paused";
}

/** 清除 attachment 中的 dataUrl，仅保留持久引用字段，避免 base64 进入待发箱 */
export function sanitizeAttachmentForOutbox(attachment: Attachment): Attachment {
  const sanitized: Attachment = {
    fileName: attachment.fileName,
    mimeType: attachment.mimeType,
    size: attachment.size,
    isImage: attachment.isImage,
  };
  if (attachment.path) sanitized.path = attachment.path;
  if (attachment.uri) sanitized.uri = attachment.uri;
  return sanitized;
}

/** 检查待发箱快照版本号单调性 */
export function isMonotonicRevision(incomingRevision: number, currentRevision?: number | null): boolean {
  if (currentRevision === undefined || currentRevision === null) return true;
  return incomingRevision >= currentRevision;
}

/** 从 unknown 安全窄化为 OutboxSnapshot */
export function isOutboxSnapshot(value: unknown): value is OutboxSnapshot {
  if (
    value &&
    typeof value === "object" &&
    !Array.isArray(value) &&
    "revision" in value &&
    "items" in value
  ) {
    return typeof value.revision === "number" && Array.isArray(value.items);
  }
  return false;
}

/** 从异常中提取安全窄化的 OutboxSnapshot，避免双重类型强转 */
export function extractOutboxSnapshot(error: unknown): OutboxSnapshot | null {
  if (error && typeof error === "object" && "snapshot" in error) {
    const candidate = error.snapshot;
    if (isOutboxSnapshot(candidate)) {
      return candidate;
    }
  }
  return null;
}


export function buildOutboxMutationBody(
  operation: OutboxOperation,
  request: OutboxMutationRequest,
): OutboxMutationBody {
  const serializedRequest: OutboxMutationRequest = {};
  if (request.id !== undefined) serializedRequest.id = request.id;
  if (request.expectedRevision !== undefined) serializedRequest.expectedRevision = request.expectedRevision;
  if (request.payload !== undefined) serializedRequest.payload = request.payload;
  if (request.expectedSessionId !== undefined) serializedRequest.expectedSessionId = request.expectedSessionId;
  if (request.expectedTurnId !== undefined) serializedRequest.expectedTurnId = request.expectedTurnId;

  return {
    operation,
    request: serializedRequest,
  };
}

export function isOutboxConflictError(error: unknown): boolean {
  if (typeof error === "string") {
    return error === "outbox_revision_conflict" || error.includes("outbox_revision_conflict");
  }
  if (error && typeof error === "object") {
    const err = error as { code?: string; message?: string };
    if (err.code === "outbox_revision_conflict") return true;
    if (typeof err.message === "string" && err.message.includes("outbox_revision_conflict")) return true;
  }
  return false;
}

export function formatOutboxError(error: unknown): string {
  if (typeof error === "string") {
    if (error === "outbox_revision_conflict" || error.includes("outbox_revision_conflict")) {
      return "待发箱版本冲突，已刷新最新队列，已保留当前输入。";
    }
    if (error === "outbox_attachment_unavailable" || error.includes("outbox_attachment_unavailable")) {
      return "引用的附件已失效或不可用，已保留当前输入。";
    }
    if (error === "outbox_empty" || error.includes("outbox_empty")) {
      return "待发内容不能为空。";
    }
    if (error === "outbox_target_changed" || error.includes("outbox_target_changed")) {
      return "当前任务目标已发生变更，无法执行此操作。";
    }
    return error;
  }
  if (error && typeof error === "object") {
    const err = error as { code?: string; message?: string };
    const code = err.code || "";
    if (code === "outbox_revision_conflict" || err.message?.includes("outbox_revision_conflict")) {
      return "待发箱版本冲突，已刷新最新队列，已保留当前输入。";
    }
    if (code === "outbox_attachment_unavailable" || err.message?.includes("outbox_attachment_unavailable")) {
      return "引用的附件已失效或不可用，已保留当前输入。";
    }
    if (code === "outbox_empty" || err.message?.includes("outbox_empty")) {
      return "待发内容不能为空。";
    }
    if (code === "outbox_target_changed" || err.message?.includes("outbox_target_changed")) {
      return "当前任务目标已发生变更，无法执行此操作。";
    }
    if (typeof err.message === "string" && err.message) {
      return err.message;
    }
  }
  return String(error || "待发箱操作失败");
}
