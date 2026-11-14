export type ConnectionStatus = "connecting" | "online" | "offline";
export type MobileSection = "chat" | "workspace" | "browser";
export type ContextPanelName = "workspace" | "browser";
export type ConversationMode = "normal" | "codex" | "chat_only";

export interface ConversationCreateTarget {
  mode: ConversationMode;
  agentId?: string;
}

export interface AgentProfile {
  id: string;
  name: string;
  description?: string;
  enabled?: boolean;
  builtIn?: boolean;
}

export interface Conversation {
  id: number;
  mode?: string;
  agentId?: string;
  title?: string;
  summary?: string;
  lastMessage?: string;
  messageCount?: number;
  updatedAt?: number;
  isArchived?: boolean;
  isPinned?: boolean;
}

export interface Attachment {
  fileName: string;
  mimeType: string;
  size: number;
  dataUrl?: string;
  isImage: boolean;
  path?: string;
  uri?: string;
}

export interface ChatMessage {
  id?: number | string;
  contentId?: string;
  user?: number;
  type?: number;
  content?: unknown;
  streamMeta?: Record<string, unknown>;
  reasoning_content?: string;
  reasoningContent?: string;
  isError?: boolean;
  isLoading?: boolean;
  createAt?: number | string;
}

export interface WorkspaceInfo {
  rootPath?: string;
  [key: string]: unknown;
}

export interface WorkspaceItem {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
}

export interface WorkspaceListing {
  path?: string;
  items?: WorkspaceItem[];
}

export interface BrowserSnapshot {
  available?: boolean;
  title?: string;
  currentUrl?: string;
  [key: string]: unknown;
}

export interface BootstrapPayload {
  retiredAgentIds?: string[];
  agentProfiles?: AgentProfile[];
  workspace?: {
    workspace?: WorkspaceInfo | null;
    root?: { path?: string } | null;
  } | null;
  browser?: BrowserSnapshot | null;
}

export interface RealtimeEventData {
  conversationId?: number;
  conversationMode?: string;
  mode?: string;
  messages?: ChatMessage[];
  snapshot?: BrowserSnapshot | null;
  kind?: string;
  taskId?: string;
  turnId?: string;
  sessionId?: string;
  threadId?: string;
  requestId?: string;
  id?: string | number;
  method?: string;
  params?: Record<string, unknown>;
  result?: Record<string, unknown>;
  error?: unknown;
  toolType?: string;
  revision?: number;
  [key: string]: unknown;
}

export type RealtimeEventName =
  | "conversation_created"
  | "conversation_updated"
  | "conversation_deleted"
  | "messages_replaced"
  | "acp_event"
  | "chat_task_event"
  | "browser_snapshot_updated"
  | "workspace_changed"
  | "conversation_outbox_changed"
  | "skill_assets_changed";

export interface RunResult {
  taskId?: string | number;
  turnId?: string;
  threadId?: string;
  sessionId?: string;
  conversationMode?: ConversationMode;
  conversation?: Conversation;
}

export interface BrowserActionResult {
  snapshot?: BrowserSnapshot | null;
}

export interface WorkspaceFilePayload {
  content?: string;
}

export type OutboxItemState = "pending" | "dispatching" | "paused" | "submitted";

export interface OutboxPayload {
  text?: string;
  attachments?: Attachment[];
  cwd?: string;
  model?: string;
  effort?: string;
  conversationMode?: ConversationMode;
  [key: string]: unknown;
}

export interface OutboxItem {
  id: string;
  conversationId: number;
  agentId: string;
  state: OutboxItemState;
  revision: number;
  sequence: number;
  requestId?: string;
  userMessageId?: string;
  blockedBySessionId?: string | null;
  blockedByTurnId?: string | null;
  createdAt: number;
  updatedAt: number;
  pauseReason?: string | null;
  payload?: OutboxPayload | null;
  text?: string;
  attachments?: Attachment[];
}

export interface OutboxSnapshot {
  revision: number;
  items: OutboxItem[];
}

export type OutboxOperation =
  | "enqueue"
  | "update"
  | "remove"
  | "resume"
  | "stop_and_send";

export interface OutboxMutationRequest {
  id?: string;
  expectedRevision?: number;
  payload?: OutboxPayload | Record<string, unknown>;
  expectedSessionId?: string;
  expectedTurnId?: string;
}

export interface OutboxMutationBody {
  operation: OutboxOperation;
  request: OutboxMutationRequest;
}

export type SafeOutboxErrorCode =
  | "outbox_revision_conflict"
  | "outbox_attachment_unavailable"
  | "outbox_empty"
  | "outbox_target_changed";

export interface WorkspaceChangeFileItem {
  path: string;
  operation: "create" | "write" | "move";
  beforeHash?: string | null;
  afterHash?: string | null;
  sourcePath?: string;
  targetPath?: string;
}

export interface WorkspaceChangeRecord {
  changeId: string;
  conversationId?: number;
  sessionId?: string;
  turnId?: string;
  toolCallId?: string;
  workspaceRoot?: string;
  operation: "create" | "write" | "move";
  relativePath?: string;
  sourceRelativePath?: string | null;
  targetRelativePath?: string | null;
  sourcePath?: string;
  targetPath?: string;
  beforeSha256?: string | null;
  afterSha256?: string | null;
  beforeHash?: string | null;
  afterHash?: string | null;
  beforeExists?: boolean;
  afterExists?: boolean;
  snapshotStatus: "ready" | "unavailable" | "restored" | "conflict";
  revision: number;
  createdAt: number;
  diffText?: string;
  isBinary?: boolean;
  canRestore?: boolean;
  snapshotBytes?: number;
  unavailableReason?: string | null;
  files?: WorkspaceChangeFileItem[];
  expectedAfterHashes?: Record<string, string | null>;
  restoreJournal?: unknown;
}

export interface WorkspaceChangesListResponse {
  workspaceId: string;
  items: WorkspaceChangeRecord[];
}

export interface WorkspaceChangeRestoreResult {
  success: boolean;
  status: "restored" | "workspace_change_busy" | "workspace_change_conflict" | "error";
  message: string;
  changeId: string;
  revision?: number | null;
}

export interface WorkspaceChangeDiscardResult {
  changeId: string;
  discarded: boolean;
  revision?: number;
  snapshotStatus?: string;
}

export type SkillCandidateState =
  | "queued"
  | "generating"
  | "pending_review"
  | "approved"
  | "rejected"
  | "generation_failed"
  | "interrupted"
  | "skipped";

export type SkillCandidateSkipReason =
  | "ineligible"
  | "busy"
  | "sensitive"
  | "too_large"
  | "provider_changed"
  | "no_suggestion";

export interface SkillCandidateAsset {
  candidateId: string;
  revision: number;
  conversationId?: number;
  sessionId?: string;
  turnId?: string;
  state: SkillCandidateState;
  skipReason?: SkillCandidateSkipReason | null;
  name: string;
  description: string;
  bodyMarkdown: string;
  targetSkillId?: string;
  failureReason?: string | null;
  createdAt: number;
  updatedAt: number;
  dataCategories?: string[];
}

export interface SkillRevisionSnapshot {
  revisionId: string;
  previousRevisionId?: string | null;
  name: string;
  description: string;
  bodyMarkdown: string;
  createdAt: number;
  wasManuallyDisabled: boolean;
  canRestore: boolean;
}

export interface SkillFeedbackEligibility {
  skillId: string;
  revisionId: string;
  revision: number;
  currentContentHash: string;
  eligible: boolean;
  reason?: string | null;
  usage?: {
    conversationId?: number;
    sessionId?: string;
    turnId?: string;
  };
  revisions?: SkillRevisionSnapshot[];
}

export interface SkillSuggestionsResponse {
  enabled: boolean;
  items: SkillCandidateAsset[];
  total: number;
  offset: number;
  limit: number;
  feedbackEligibility?: SkillFeedbackEligibility | null;
  revisions?: SkillRevisionSnapshot[];
}

export type SkillSuggestionOperation =
  | "save"
  | "approve"
  | "reject"
  | "regenerate"
  | "set_enabled"
  | "feedback"
  | "restore";

export type SkillSuggestionMutationBody =
  | {
      operation: "save";
      candidateId: string;
      expectedRevision: number;
      name: string;
      description: string;
      bodyMarkdown: string;
    }
  | {
      operation: "approve";
      candidateId: string;
      expectedRevision: number;
      targetSkillId: string;
      expectedCurrentContentHash?: string | null;
    }
  | {
      operation: "reject";
      candidateId: string;
      expectedRevision: number;
    }
  | {
      operation: "regenerate";
      candidateId: string;
      expectedRevision: number;
    }
  | {
      operation: "set_enabled";
      enabled: boolean;
    }
  | {
      operation: "feedback";
      skillId: string;
      revisionId: string;
      conversationId?: number;
      sessionId?: string;
      turnId?: string;
      expectedRevision: number;
      action: "retain" | "retire";
    }
  | {
      operation: "restore";
      skillId: string;
      revisionId: string;
      expectedRevision: number;
      expectedCurrentContentHash: string;
    };

export type SafeWorkspaceErrorCode =
  | "workspace_change_conflict"
  | "workspace_change_busy"
  | "workspace_change_unavailable";

export type SafeSkillErrorCode =
  | "skill_asset_invalid"
  | "skill_candidate_not_found"
  | "skill_revision_conflict"
  | "skill_name_conflict"
  | "skill_cannot_overwrite_builtin"
  | "skill_content_conflict"
  | "skill_content_modified"
  | "skill_feedback_ineligible"
  | "skill_revision_retired"
  | "skill_validation_failed";
