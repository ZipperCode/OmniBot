import type {
  Attachment,
  OutboxMutationRequest,
  OutboxOperation,
  OutboxSnapshot,
  SkillSuggestionMutationBody,
  SkillSuggestionsResponse,
  WorkspaceChangeDiscardResult,
  WorkspaceChangeRecord,
  WorkspaceChangeRestoreResult,
  WorkspaceChangesListResponse,
} from "./types";
import { buildOutboxMutationBody, sanitizeAttachmentForOutbox } from "./outbox";
export * from "./outbox";

const API_ROOT = "/webchat/api";
const AVATAR_PAGE_VERSION = Date.now().toString(36);

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, query } = options;
  const origin = typeof window !== "undefined" && window.location?.origin
    ? window.location.origin
    : "http://localhost";
  const url = new URL(`${API_ROOT}${path}`, origin);
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const response = await fetch(url, {
    method,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const responseText = await response.text();
  let payload: unknown = null;
  if (responseText) {
    try {
      payload = JSON.parse(responseText);
    } catch {
      payload = responseText;
    }
  }
  if (!response.ok) {
    const record = isRecord(payload) ? payload : null;
    const code = typeof record?.code === "string"
      ? record.code
      : typeof record?.error === "string"
        ? record.error
        : undefined;
    const message = record?.message ?? record?.error ?? payload;
    const error = new Error(String(message || `请求失败 (${response.status})`));
    if (code) {
      (error as Error & { code?: string }).code = code;
    }
    if (record?.snapshot && isRecord(record.snapshot)) {
      (error as Error & { snapshot?: unknown }).snapshot = record.snapshot;
    }
    throw error;
  }
  return payload as T;
}

export function browserFrameUrl(seed: number): string {
  return `${API_ROOT}/browser/frame?t=${Date.now()}-${seed}`;
}

export function agentAvatarUrl(): string {
  return `${API_ROOT}/avatar?v=${AVATAR_PAGE_VERSION}`;
}

export function workspaceDownloadUrl(path: string): string {
  return `${API_ROOT}/workspaces/download?path=${encodeURIComponent(path)}`;
}

export function eventsUrl(): string {
  return `${API_ROOT}/events`;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function getConversationOutbox(conversationId: number): Promise<OutboxSnapshot> {
  return request<OutboxSnapshot>(`/conversations/${conversationId}/outbox`);
}

export async function mutateConversationOutbox(
  conversationId: number,
  operation: OutboxOperation,
  mutationRequest: OutboxMutationRequest,
): Promise<OutboxSnapshot> {
  const body = buildOutboxMutationBody(operation, mutationRequest);
  return request<OutboxSnapshot>(`/conversations/${conversationId}/outbox`, {
    method: "POST",
    body,
  });
}

/**
 * 待发箱附件导入：将携带 dataUrl 的附件导入为持久引用。
 * 接收带 dataUrl 的附件，由宿主保存并返回持久元数据（path/uri 等），已持久化的项直接保留。
 */
export async function importOutboxAttachments(
  conversationId: number,
  attachments: Attachment[],
): Promise<Attachment[]> {
  const needsImport: Attachment[] = [];
  const persistent: Attachment[] = [];

  for (const att of attachments) {
    if (att.dataUrl) {
      needsImport.push(att);
    } else if (att.path || att.uri) {
      persistent.push(att);
    }
  }

  if (needsImport.length === 0) {
    return persistent.map(sanitizeAttachmentForOutbox);
  }

  const result = await request<{ attachments: Attachment[] }>(
    `/conversations/${conversationId}/outbox/attachments`,
    {
      method: "POST",
      body: { attachments: needsImport },
    },
  );

  const imported = Array.isArray(result?.attachments) ? result.attachments : [];
  const cleanImported = imported.map(sanitizeAttachmentForOutbox);
  return [...persistent.map(sanitizeAttachmentForOutbox), ...cleanImported];
}

/**
 * 工作区变更检查与受管恢复 API (Plan 94)
 */
export async function getConversationWorkspaceChanges(
  conversationId: number,
  turnId?: string,
): Promise<WorkspaceChangeRecord[]> {
  const resp = await request<WorkspaceChangesListResponse | WorkspaceChangeRecord[]>(
    `/conversations/${conversationId}/workspace-changes`,
    { query: turnId ? { turnId } : undefined },
  );
  if (isRecord(resp) && Array.isArray((resp as WorkspaceChangesListResponse).items)) {
    return (resp as WorkspaceChangesListResponse).items;
  }
  return Array.isArray(resp) ? resp : [];
}

export async function getWorkspaceChangesByWorkspace(
  workspaceId: string,
  offset = 0,
  limit = 20,
): Promise<WorkspaceChangeRecord[]> {
  const resp = await request<WorkspaceChangesListResponse | WorkspaceChangeRecord[]>(
    `/workspaces/${encodeURIComponent(workspaceId)}/changes`,
    { query: { offset, limit } },
  );
  if (isRecord(resp) && Array.isArray((resp as WorkspaceChangesListResponse).items)) {
    return (resp as WorkspaceChangesListResponse).items;
  }
  return Array.isArray(resp) ? resp : [];
}

export async function getWorkspaceChangePreview(
  changeId: string,
  query?: { conversationId?: number; workspaceId?: string },
): Promise<WorkspaceChangeRecord> {
  return request<WorkspaceChangeRecord>(
    `/workspace-changes/${encodeURIComponent(changeId)}`,
    { query },
  );
}

export async function restoreWorkspaceChange(
  changeId: string,
  expectedRevision: number,
  expectedAfterHashes?: Record<string, string | null>,
): Promise<WorkspaceChangeRestoreResult> {
  return request<WorkspaceChangeRestoreResult>(
    `/workspace-changes/${encodeURIComponent(changeId)}`,
    {
      method: "POST",
      body: {
        operation: "restore",
        expectedRevision,
        expectedAfterHashes,
      },
    },
  );
}

export async function discardWorkspaceChange(
  changeId: string,
  expectedRevision: number,
): Promise<WorkspaceChangeDiscardResult> {
  return request<WorkspaceChangeDiscardResult>(
    `/workspace-changes/${encodeURIComponent(changeId)}`,
    {
      method: "POST",
      body: {
        operation: "discard",
        expectedRevision,
      },
    },
  );
}

/**
 * 技能自动建议与生命周期管理 API (Plan 122-123 & contract)
 */
export async function getSkillSuggestions(query: {
  conversationId?: number;
  turnId?: string;
  skillId?: string;
  offset?: number;
  limit?: number;
} = {}): Promise<SkillSuggestionsResponse> {
  return request<SkillSuggestionsResponse>("/skills/suggestions", { query });
}

export async function mutateSkillSuggestion<T = unknown>(
  body: SkillSuggestionMutationBody,
): Promise<T> {
  return request<T>("/skills/suggestions", {
    method: "POST",
    body,
  });
}
