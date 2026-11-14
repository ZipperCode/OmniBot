import { useEffect, useRef, useState } from "react";
import {
  extractOutboxSnapshot,
  formatOutboxError,
  getConversationOutbox,
  importOutboxAttachments,
  isRecord,
  mutateConversationOutbox,
  request,
} from "./api";
import { ChatPanel } from "./components/ChatPanel";
import { ContextPane } from "./components/ContextPane";
import { ConversationSidebar } from "./components/ConversationSidebar";
import { Icon } from "./components/Icon";
import { LoginView } from "./components/LoginView";
import { WorkspaceChangeListModal } from "./components/WorkspaceChangeListModal";
import { WorkspaceChangeModal } from "./components/WorkspaceChangeModal";
import {
  createConversationDraft,
  conversationAgentId,
  normalizeConversationMode,
  canRunConversation,
  isPersistedConversation,
} from "./conversationDraft";
import { appendConversationNavigation } from "./conversationNavigation";
import { isRetiredAgentConversation } from "./conversationVisibility";
import { conversationKey } from "./format";
import { useRealtime } from "./hooks/useRealtime";
import { reconcileCodexMessages } from "./messageReconciliation";
import type {
  Attachment,
  AgentProfile,
  BootstrapPayload,
  BrowserActionResult,
  BrowserSnapshot,
  ChatMessage,
  ContextPanelName,
  Conversation,
  ConversationCreateTarget,
  ConversationMode,
  MobileSection,
  RealtimeEventData,
  RealtimeEventName,
  RunResult,
  WorkspaceFilePayload,
  WorkspaceInfo,
  WorkspaceItem,
  WorkspaceListing,
  OutboxSnapshot,
} from "./types";

const TOKEN_STORAGE_KEY = "omnibot_webchat_token";
const MOBILE_SECTION_ICON = {
  chat: "agent",
  workspace: "workspace",
  browser: "browser",
} as const;
const UNSUPPORTED_CONVERSATION_MESSAGE = "此会话类型仅支持查看历史，请新建对话。";
const MISSING_AGENT_MESSAGE = "此对话没有可用的助手归属。历史记录仍可查看，请新建对话。";
const RETIRED_AGENT_MESSAGE = "此助手已停止支持。历史记录仍可查看，请新建其他助手的对话。";

function errorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error ?? "请求失败");
  if (message.includes("agent_identity_required:")) return MISSING_AGENT_MESSAGE;
  return message.includes("agent_retired:") ? RETIRED_AGENT_MESSAGE : message;
}

function initialToken(): string {
  const queryToken = new URLSearchParams(window.location.search).get("token")?.trim();
  return queryToken || localStorage.getItem(TOKEN_STORAGE_KEY)?.trim() || "";
}

function createTaskId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(false);
  const [authenticating, setAuthenticating] = useState(false);
  const [loginError, setLoginError] = useState("");
  const [globalError, setGlobalError] = useState("");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [agentProfiles, setAgentProfiles] = useState<AgentProfile[]>([]);
  const [retiredAgentIds, setRetiredAgentIds] = useState<string[]>([]);
  const [archivedConversations, setArchivedConversations] = useState<Conversation[]>([]);
  const [archivedLoading, setArchivedLoading] = useState(false);
  const [selectedConversation, setSelectedConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [chatViewRevision, setChatViewRevision] = useState(0);
  const [sending, setSending] = useState(false);
  const [workspaceChangesListOpen, setWorkspaceChangesListOpen] = useState(false);
  const [previewChangeId, setPreviewChangeId] = useState<string | null>(null);
  const [skillRefreshRevision, setSkillRefreshRevision] = useState(0);
  // 按会话独立跟踪活跃任务运行状态，防止全局变量跨会话串扰
  interface ConversationRunState {
    taskId: string | null;
    renderTaskId: string | null;
    turnId: string | null;
    sessionId: string | null;
    clarifyTaskId: string | null;
    requestId: string | null;
  }
  const [conversationRuns, setConversationRuns] = useState<Record<number, ConversationRunState>>({});
  const conversationRunsRef = useRef<Record<number, ConversationRunState>>({});

  function setRunStateForConversation(
    conversationId: number,
    run: Partial<ConversationRunState> | null,
  ) {
    if (!conversationId || conversationId <= 0) return;
    setConversationRuns((prev) => {
      const existing: ConversationRunState = prev[conversationId] || {
        taskId: null,
        renderTaskId: null,
        turnId: null,
        sessionId: null,
        clarifyTaskId: null,
        requestId: null,
      };
      const next = { ...prev };
      if (run === null) {
        delete next[conversationId];
      } else {
        next[conversationId] = { ...existing, ...run };
      }
      conversationRunsRef.current = next;
      return next;
    });
  }

  // 派生当前选中会话的状态（切换会话时自动反映对应状态）
  const currentConversationId = Number(selectedConversation?.id ?? 0);
  const currentRun = currentConversationId > 0 ? (conversationRuns[currentConversationId] ?? null) : null;
  const activeTaskId = currentRun?.taskId ?? null;
  const activeRenderTaskId = currentRun?.renderTaskId ?? null;
  const activeTurnId = currentRun?.turnId ?? null;
  const activeSessionId = currentRun?.sessionId ?? null;
  const clarifyTaskId = currentRun?.clarifyTaskId ?? null;
  const [workspaceInfo, setWorkspaceInfo] = useState<WorkspaceInfo | null>(null);
  const [workspacePath, setWorkspacePath] = useState("");
  const [workspaceItems, setWorkspaceItems] = useState<WorkspaceItem[]>([]);
  const [workspaceFilePath, setWorkspaceFilePath] = useState<string | null>(null);
  const [workspaceContent, setWorkspaceContent] = useState("");
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [browserSnapshot, setBrowserSnapshot] = useState<BrowserSnapshot | null>(null);
  const [browserFrameSeed, setBrowserFrameSeed] = useState(0);
  const [contextPanel, setContextPanel] = useState<ContextPanelName>("workspace");
  const [mobileSection, setMobileSectionState] = useState<MobileSection>("chat");
  const [conversationsOpen, setConversationsOpen] = useState(false);
  const [leftSidebarCollapsed, setLeftSidebarCollapsed] = useState(false);
  const [rightSidebarCollapsed, setRightSidebarCollapsed] = useState(false);
  const [toast, setToast] = useState("");
  const [outboxSnapshot, setOutboxSnapshot] = useState<OutboxSnapshot | null>(null);
  const [outboxError, setOutboxError] = useState<string | null>(null);
  const selectedRef = useRef<Conversation | null>(null);
  const conversationHistoryRef = useRef<string[]>([]);
  const conversationHistoryIndexRef = useRef(-1);
  const completedTaskIdsRef = useRef(new Set<string>());
  const workspacePathRef = useRef("");
  const toastTimerRef = useRef<number | null>(null);
  const autoLoginToken = useRef(initialToken());

  function showError(error: unknown) {
    setGlobalError(errorMessage(error));
  }

  function showToast(message: string) {
    if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    setToast(message);
    toastTimerRef.current = window.setTimeout(() => setToast(""), 2600);
  }

  function applyConversationSnapshot(conversation: Conversation) {
    if (!isPersistedConversation(conversation)) return;
    setConversations((current) => [
      conversation,
      ...current.filter((item) => Number(item.id) !== Number(conversation.id)),
    ].sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0)));
    if (Number(selectedRef.current?.id ?? 0) === Number(conversation.id)) {
      selectedRef.current = conversation;
      setSelectedConversation(conversation);
    }
  }

  function recordConversationNavigation(conversation: Conversation | null) {
    if (!conversation || Number(conversation.id ?? 0) <= 0) return;
    const key = conversationKey(conversation);
    const history = conversationHistoryRef.current;
    const index = conversationHistoryIndexRef.current;
    if (history[index] === key) return;
    const next = appendConversationNavigation({ keys: history, index }, key);
    conversationHistoryRef.current = next.keys;
    conversationHistoryIndexRef.current = next.index;
  }

  function navigationTarget(direction: -1 | 1) {
    const conversationsByKey = new Map(
      conversations.map((conversation) => [conversationKey(conversation), conversation]),
    );
    const history = conversationHistoryRef.current;
    for (
      let index = conversationHistoryIndexRef.current + direction;
      index >= 0 && index < history.length;
      index += direction
    ) {
      const conversation = conversationsByKey.get(history[index]);
      if (conversation) return { conversation, index };
    }
    return null;
  }

  async function navigateConversationHistory(direction: -1 | 1) {
    const target = navigationTarget(direction);
    if (!target) return;
    conversationHistoryIndexRef.current = target.index;
    selectedRef.current = target.conversation;
    setSelectedConversation(target.conversation);
    setOutboxError(null);
    if (isPersistedConversation(target.conversation)) {
      void loadOutbox(target.conversation.id);
    } else {
      setOutboxSnapshot(null);
    }
    await loadMessages(target.conversation);
  }

  async function loadMessages(conversation = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    try {
      const payload = await request<ChatMessage[]>(`/conversations/${conversation.id}/messages`, {
        query: conversation.mode ? { mode: conversation.mode } : undefined,
      });
      if (conversationKey(conversation) === conversationKey(selectedRef.current)) {
        const incoming = Array.isArray(payload) ? payload : [];
        setMessages((current) => (
          normalizeConversationMode(conversation.mode) === "codex"
            ? reconcileCodexMessages(current, incoming)
            : incoming
        ));
      }
    } catch (error) {
      showError(error);
    }
  }

  async function loadOutbox(conversationId: number) {
    if (!conversationId || conversationId <= 0) {
      setOutboxSnapshot(null);
      return;
    }
    try {
      const snapshot = await getConversationOutbox(conversationId);
      if (Number(selectedRef.current?.id ?? 0) === conversationId) {
        setOutboxSnapshot((current) => {
          // 快照单调性：忽略小于当前 revision 的陈旧快照
          if (current && snapshot.revision < current.revision) return current;
          return snapshot;
        });
      }
    } catch (error) {
      // load 失败给出真实提示
      if (Number(selectedRef.current?.id ?? 0) === conversationId) {
        setOutboxError(formatOutboxError(error));
      }
    }
  }

  async function loadConversations(preserveSelection = true) {
    const payload = await request<Conversation[]>("/conversations", {
      query: { includeArchived: false },
    });
    const previousKey = preserveSelection ? conversationKey(selectedRef.current) : null;
    const nextConversations = (Array.isArray(payload) ? payload : [])
      .filter((item) => !item.isArchived)
      .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0));
    const currentSelection = selectedRef.current;
    if (
      preserveSelection
      && currentSelection
      && !isPersistedConversation(currentSelection)
    ) {
      setConversations(nextConversations);
      setSelectedConversation(currentSelection);
      return;
    }
    const nextSelected = nextConversations.find((item) => conversationKey(item) === previousKey)
      ?? nextConversations[0]
      ?? null;
    const selectionChanged = conversationKey(nextSelected) !== previousKey;
    setConversations(nextConversations);
    selectedRef.current = nextSelected;
    setSelectedConversation(nextSelected);
    recordConversationNavigation(nextSelected);
    if (nextSelected) {
      if (selectionChanged) {
        setMessages([]);
        setOutboxError(null);
      }
      if (isPersistedConversation(nextSelected)) {
        void loadOutbox(nextSelected.id);
      } else {
        setOutboxSnapshot(null);
      }
      await loadMessages(nextSelected);
    }
    else {
      setMessages([]);
      setOutboxSnapshot(null);
    }
  }

  async function loadArchivedConversations(reportError = true) {
    setArchivedLoading(true);
    try {
      const payload = await request<Conversation[]>("/conversations", {
        query: { includeArchived: true, archivedOnly: true },
      });
      setArchivedConversations(
        (Array.isArray(payload) ? payload : [])
          .filter((item) => item.isArchived)
          .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0)),
      );
    } catch (error) {
      if (reportError) showError(error);
    } finally {
      setArchivedLoading(false);
    }
  }

  async function loadWorkspace(path = workspacePathRef.current, reportError = true) {
    if (!path) return;
    try {
      const payload = await request<WorkspaceListing>("/workspaces", { query: { path } });
      const nextPath = String(payload?.path ?? path);
      workspacePathRef.current = nextPath;
      setWorkspacePath(nextPath);
      setWorkspaceItems(Array.isArray(payload?.items) ? payload.items : []);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function authenticate(token: string) {
    setLoginError("");
    setAuthenticating(true);
    try {
      await request("/session/bootstrap", { method: "POST", body: { token } });
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
      const bootstrap = await request<BootstrapPayload>("/bootstrap");
      const info = bootstrap?.workspace?.workspace ?? null;
      const rootPath = bootstrap?.workspace?.root?.path ?? info?.rootPath ?? "";
      setWorkspaceInfo(info);
      workspacePathRef.current = rootPath;
      setWorkspacePath(rootPath);
      setBrowserSnapshot(bootstrap?.browser ?? null);
      const retiredIds = Array.isArray(bootstrap?.retiredAgentIds)
        ? bootstrap.retiredAgentIds.filter((id): id is string => typeof id === "string")
        : [];
      setRetiredAgentIds(retiredIds);
      setAgentProfiles(
        (Array.isArray(bootstrap?.agentProfiles) ? bootstrap.agentProfiles : [])
          .filter((profile) => !retiredIds.includes(profile.id)),
      );
      setAuthenticated(true);

      const url = new URL(window.location.href);
      if (url.searchParams.has("token")) {
        url.searchParams.delete("token");
        window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
      }
      await Promise.all([
        loadConversations(false),
        rootPath ? loadWorkspace(rootPath, false) : Promise.resolve(),
      ]);
    } catch (error) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      setLoginError(errorMessage(error));
      setAuthenticated(false);
    } finally {
      setAuthenticating(false);
    }
  }

  function createConversation(target: ConversationCreateTarget) {
    setGlobalError("");
    const draftConversation = createConversationDraft(
      target.mode,
      Date.now(),
      target.agentId,
    );
    selectedRef.current = draftConversation;
    setSelectedConversation(draftConversation);
    setMessages([]);
    setOutboxSnapshot(null);
    setOutboxError(null);
    setConversationsOpen(false);
  }

  async function selectConversation(conversation: Conversation) {
    selectedRef.current = conversation;
    setSelectedConversation(conversation);
    setMessages([]);
    // 切换会话先清除旧快照与错误，避免陈旧快照闪现
    setOutboxSnapshot(null);
    setOutboxError(null);
    recordConversationNavigation(conversation);
    setConversationsOpen(false);
    if (isPersistedConversation(conversation)) {
      void loadOutbox(conversation.id);
    } else {
      setOutboxSnapshot(null);
    }
    await loadMessages(conversation);
  }

  async function updateArchiveState(
    conversation: Conversation | null = selectedRef.current,
    nextArchived?: boolean,
  ) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    const archived = nextArchived ?? !conversation.isArchived;
    try {
      await request(`/conversations/${conversation.id}`, {
        method: "PATCH",
        body: { isArchived: archived },
      });
      await Promise.all([
        loadConversations(true),
        loadArchivedConversations(false),
      ]);
      showToast(archived ? "已归档" : "已恢复到会话列表");
    } catch (error) {
      showError(error);
    }
  }

  async function updatePinState(conversation: Conversation, pinned: boolean) {
    if (!isPersistedConversation(conversation)) return;
    try {
      await request(`/conversations/${conversation.id}`, {
        method: "PATCH",
        body: { isPinned: pinned },
      });
      await loadConversations(true);
      showToast(pinned ? "已置顶" : "已取消置顶");
    } catch (error) {
      showError(error);
    }
  }

  async function deleteConversation(conversation: Conversation | null = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    if (!window.confirm(`删除“${conversation.title || "当前对话"}”？此操作无法撤销。`)) return;
    try {
      await request(`/conversations/${conversation.id}`, { method: "DELETE" });
      await Promise.all([
        loadConversations(true),
        loadArchivedConversations(false),
      ]);
      setOutboxSnapshot(null);
      setOutboxError(null);
      showToast("会话已删除");
    } catch (error) {
      showError(error);
    }
  }

  async function sendMessage(text: string, attachments: Attachment[]): Promise<boolean> {
    if (isRetiredAgentConversation(selectedRef.current, retiredAgentIds)
      || (selectedRef.current && (!conversationAgentId(selectedRef.current) || !canRunConversation(selectedRef.current)))) {
      return false;
    }
    setGlobalError("");
    setSending(true);
    let conversationCreatedForSend: Conversation | null = null;
    let optimisticUserEntryId: string | null = null;
    let targetConversation: Conversation | null = null;
    try {
      if (clarifyTaskId) {
        await request(`/tasks/${encodeURIComponent(clarifyTaskId)}/clarify`, {
          method: "POST",
          body: { reply: text },
        });
        const convId = selectedRef.current?.id ? Number(selectedRef.current.id) : 0;
        if (convId > 0) {
          setRunStateForConversation(convId, { clarifyTaskId: null });
        }
        return true;
      }

      let conversation = selectedRef.current ?? createConversationDraft("normal");
      if (!isPersistedConversation(conversation)) {
        const draftMode = normalizeConversationMode(conversation?.mode);
        const draftAgentId = conversationAgentId(conversation);
        conversation = await request<Conversation>("/conversations", {
          method: "POST",
          body: {
            title: "新对话",
            mode: draftMode,
            agentId: draftAgentId,
          },
        });
        conversation = {
          ...conversation,
          mode: draftMode,
          agentId: conversation.agentId ?? draftAgentId,
        };
        conversationCreatedForSend = conversation;
        selectedRef.current = conversation;
        setSelectedConversation(conversation);
        applyConversationSnapshot(conversation);
        recordConversationNavigation(conversation);
      }
      if (!conversation) throw new Error("无法创建新对话");
      targetConversation = conversation;
      const conversationMode = normalizeConversationMode(conversation.mode);
      const taskId = createTaskId();
      const userMessageCreatedAt = Date.now();
      optimisticUserEntryId = `${taskId}-user`;
      const optimisticUserMessage: ChatMessage = {
        id: optimisticUserEntryId,
        type: 1,
        user: 1,
        content: {
          id: optimisticUserEntryId,
          text,
          ...(attachments.length ? { attachments } : {}),
        },
        createAt: userMessageCreatedAt,
      };
      completedTaskIdsRef.current.delete(taskId);
      setRunStateForConversation(conversation.id, {
        taskId,
        renderTaskId: taskId,
        turnId: null,
        sessionId: null,
        clarifyTaskId: null,
        requestId: taskId,
      });
      setMessages((current) => (
        conversationMode === "codex"
          ? reconcileCodexMessages(current, [optimisticUserMessage])
          : [...current, optimisticUserMessage]
      ));
      const result = await request<RunResult>(`/conversations/${conversation.id}/runs`, {
        method: "POST",
        body: {
          taskId,
          userMessage: text,
          userMessageCreatedAt,
          conversationMode,
          agentId: conversationAgentId(conversation),
          attachments,
        },
      });
      if (result?.conversation) {
        const updatedConversation = {
          ...result.conversation,
          mode: result.conversationMode ?? conversationMode,
          agentId: result.conversation.agentId ?? conversation.agentId,
        };
        selectedRef.current = updatedConversation;
        setSelectedConversation(updatedConversation);
        applyConversationSnapshot(updatedConversation);
      }
      const acceptedTaskId = String(result?.taskId ?? taskId);
      const returnedTurnId = result?.turnId ? String(result.turnId).trim() : null;
      const returnedSessionId = (result?.threadId ?? result?.sessionId)
        ? String(result.threadId ?? result.sessionId).trim()
        : null;
      const completedBeforeAcceptance = completedTaskIdsRef.current.has(acceptedTaskId);
      if (completedBeforeAcceptance) {
        setRunStateForConversation(conversation.id, null);
      } else {
        setRunStateForConversation(conversation.id, {
          taskId: acceptedTaskId,
          renderTaskId: returnedTurnId ?? acceptedTaskId,
          turnId: returnedTurnId,
          sessionId: returnedSessionId,
          requestId: acceptedTaskId,
        });
      }
      completedTaskIdsRef.current.delete(acceptedTaskId);
      if (conversation && isPersistedConversation(conversation)) {
        void loadOutbox(conversation.id);
      }
      void loadConversations(true).catch(showError);
      return true;
    } catch (error) {
      showError(error);
      const failedConvId = Number(targetConversation?.id ?? selectedRef.current?.id ?? 0);
      if (failedConvId > 0) {
        setRunStateForConversation(failedConvId, null);
      }
      if (optimisticUserEntryId) {
        const failedEntryId = optimisticUserEntryId;
        setMessages((current) => current.filter((message) => (
          String(message.id ?? message.contentId ?? "") !== failedEntryId
        )));
      }
      if (conversationCreatedForSend) {
        const createdConversation = conversationCreatedForSend;
        try {
          const persistedMessages = await request<ChatMessage[]>(
            `/conversations/${createdConversation.id}/messages`,
            { query: { mode: createdConversation.mode ?? "normal" } },
          );
          if (!persistedMessages.length) {
            await request(`/conversations/${createdConversation.id}`, { method: "DELETE" });
            if (
              Number(selectedRef.current?.id ?? 0) ===
              Number(createdConversation.id)
            ) {
              const draft = createConversationDraft(
                normalizeConversationMode(createdConversation.mode),
                Date.now(),
                createdConversation.agentId,
              );
              selectedRef.current = draft;
              setSelectedConversation(draft);
              setMessages([]);
            }
          }
        } catch {
          // Keep the conversation when its persisted state cannot be confirmed.
        }
      }
      const current = selectedRef.current;
      if (isPersistedConversation(current)) {
        void loadMessages(current);
      }
      return false;
    } finally {
      setSending(false);
    }
  }

  async function cancelRun() {
    const targetId = selectedRef.current?.id ? Number(selectedRef.current.id) : 0;
    const currentRun = targetId > 0 ? conversationRunsRef.current[targetId] : null;
    const taskIdToCancel = currentRun?.taskId;
    if (!taskIdToCancel) return;
    try {
      await request(`/tasks/${encodeURIComponent(taskIdToCancel)}/cancel`, { method: "POST" });
      // 注意：cancel HTTP 返回并不代表官方终态，不清除活跃状态；仅等待正式 prompt 响应或拥有请求错误时才清
    } catch (error) {
      showError(error);
    }
  }

  async function openWorkspaceFile(path: string) {
    try {
      const payload = await request<WorkspaceFilePayload>("/workspaces/file", {
        query: { path },
      });
      setWorkspaceFilePath(path);
      setWorkspaceContent(String(payload?.content ?? ""));
      setWorkspaceDirty(false);
    } catch (error) {
      showError(error);
    }
  }

  function workspaceParentPath(): string {
    const root = String(workspaceInfo?.rootPath ?? "").replace(/\/$/, "");
    const current = String(workspacePathRef.current).replace(/\/$/, "");
    if (!current || current === root) return current;
    const index = current.lastIndexOf("/");
    const parent = index > 0 ? current.slice(0, index) : "/";
    return root && !parent.startsWith(root) ? root : parent;
  }

  async function saveWorkspaceFile() {
    if (!workspaceFilePath || !workspaceDirty) return;
    try {
      await request("/workspaces/file", {
        method: "PUT",
        body: { path: workspaceFilePath, content: workspaceContent, append: false },
      });
      setWorkspaceDirty(false);
      showToast("文件已保存");
    } catch (error) {
      showError(error);
    }
  }

  async function refreshBrowser(reportError = true) {
    try {
      setBrowserSnapshot(await request<BrowserSnapshot>("/browser/snapshot"));
      setBrowserFrameSeed((seed) => seed + 1);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function browserAction(payload: Record<string, unknown>) {
    try {
      const result = await request<BrowserActionResult>("/browser/action", { method: "POST", body: payload });
      if (result?.snapshot !== undefined) setBrowserSnapshot(result.snapshot);
      setBrowserFrameSeed((seed) => seed + 1);
    } catch (error) {
      showError(error);
    }
  }

  function sameSelectedConversation(data: RealtimeEventData): boolean {
    const selected = selectedRef.current;
    return Boolean(
      selected
      && Number(data.conversationId ?? 0) === Number(selected.id)
      && String(data.conversationMode ?? data.mode ?? "normal") === String(selected.mode ?? "normal"),
    );
  }

  function handleRealtimeEvent(eventName: RealtimeEventName, data: RealtimeEventData) {
    if (["conversation_created", "conversation_updated", "conversation_deleted"].includes(eventName)) {
      void loadConversations(true).catch(showError);
      void loadArchivedConversations(false);
      return;
    }
    if (eventName === "messages_replaced" && sameSelectedConversation(data)) {
      const incoming = Array.isArray(data.messages) ? data.messages : [];
      const mode = normalizeConversationMode(
        String(data.conversationMode ?? data.mode ?? selectedRef.current?.mode ?? ""),
      );
      setMessages((current) => (
        mode === "codex"
          ? reconcileCodexMessages(current, incoming)
          : incoming
      ));
      return;
    }
    if (eventName === "workspace_changed" && workspacePathRef.current) {
      void loadWorkspace(workspacePathRef.current, false);
      return;
    }
    if (eventName === "browser_snapshot_updated") {
      setBrowserSnapshot(data.snapshot ?? null);
      setBrowserFrameSeed((seed) => seed + 1);
      return;
    }
    if (eventName === "conversation_outbox_changed") {
      const conversationId = Number(data.conversationId ?? 0);
      const selected = selectedRef.current;
      if (selected && isPersistedConversation(selected) && Number(selected.id) === conversationId) {
        void loadOutbox(conversationId);
      }
      return;
    }
    if (eventName === "skill_assets_changed") {
      setSkillRefreshRevision((rev) => rev + 1);
      return;
    }
    if (eventName === "chat_task_event") {
      const kind = String(data.kind ?? "");
      const taskId = String(data.taskId ?? "");
      let conversationId = Number(data.conversationId ?? 0);
      if (!conversationId || conversationId <= 0) {
        for (const [cidStr, run] of Object.entries(conversationRunsRef.current)) {
          if (taskId && run.taskId === taskId) {
            conversationId = Number(cidStr);
            break;
          }
        }
      }
      if (["completed", "error"].includes(kind)) {
        if (taskId) completedTaskIdsRef.current.add(taskId);
        if (conversationId > 0) {
          setRunStateForConversation(conversationId, null);
          const selected = selectedRef.current;
          if (selected && isPersistedConversation(selected) && Number(selected.id) === conversationId) {
            void loadOutbox(conversationId);
          }
        }
      }
      return;
    }
    if (eventName !== "acp_event") return;
    const method = typeof data.method === "string" ? data.method : "";
    const params = isRecord(data.params) ? (data.params as Record<string, unknown>) : null;
    const result = isRecord(data.result) ? (data.result as Record<string, unknown>) : null;
    const error = data.error;
    const requestId = (data.requestId ?? data.id) ? String(data.requestId ?? data.id) : null;
    const eventSessionId = (data.sessionId ?? data.threadId ?? params?.sessionId)
      ? String(data.sessionId ?? data.threadId ?? params?.sessionId).trim()
      : null;
    const eventTurnId = data.turnId ? String(data.turnId).trim() : null;

    let targetConversationId = Number(data.conversationId ?? 0);
    if (!targetConversationId || targetConversationId <= 0) {
      for (const [cidStr, run] of Object.entries(conversationRunsRef.current)) {
        const cid = Number(cidStr);
        if (eventSessionId && run.sessionId === eventSessionId) {
          targetConversationId = cid;
          break;
        }
        if (requestId && run.requestId === requestId) {
          targetConversationId = cid;
          break;
        }
        if (eventTurnId && run.turnId === eventTurnId) {
          targetConversationId = cid;
          break;
        }
      }
    }
    if (!targetConversationId || targetConversationId <= 0) {
      const selected = selectedRef.current;
      if (selected && isPersistedConversation(selected)) {
        targetConversationId = selected.id;
      } else {
        return;
      }
    }

    if (method === "session/update" && params) {
      const update = isRecord(params.update) ? (params.update as Record<string, unknown>) : null;
      const sessionUpdate = typeof update?.sessionUpdate === "string" ? update.sessionUpdate : "";
      setRunStateForConversation(targetConversationId, {
        ...(eventSessionId ? { sessionId: eventSessionId } : {}),
        ...(eventTurnId ? { turnId: eventTurnId } : {}),
        ...(sessionUpdate === "request_permission" || sessionUpdate === "approval_requested"
          ? { clarifyTaskId: eventTurnId ?? conversationRunsRef.current[targetConversationId]?.taskId }
          : {}),
      });
      if (String(data.toolType ?? "") === "browser" || sessionUpdate === "browser_action") {
        void refreshBrowser(false);
      }
      return;
    }

    const isPromptResponse = Boolean(result !== null || error !== undefined || data.completed === true);
    if (isPromptResponse) {
      const activeRun = conversationRunsRef.current[targetConversationId];
      if (activeRun?.taskId) {
        completedTaskIdsRef.current.add(activeRun.taskId);
      }
      setRunStateForConversation(targetConversationId, null);
      const selected = selectedRef.current;
      if (selected && isPersistedConversation(selected) && Number(selected.id) === targetConversationId) {
        void loadOutbox(targetConversationId);
      }
    }
    if (String(data.toolType ?? "") === "browser") {
      void refreshBrowser(false);
    }
  }
  const connectionStatus = useRealtime(authenticated, handleRealtimeEvent);

  function selectMobileSection(section: MobileSection) {
    setMobileSectionState(section);
    if (section !== "chat") setContextPanel(section);
  }

  useEffect(() => {
    const token = autoLoginToken.current;
    if (token) void authenticate(token);
    return () => {
      if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    };
  }, []);

  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (!workspaceDirty) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [workspaceDirty]);
  // 记录最近一次排队意图与其稳定 UUID，网络不明失败后重试保持同意图 UUID，不自动 retry
  const pendingEnqueueIntentRef = useRef<{
    text: string;
    attachmentSig: string;
    id: string;
  } | null>(null);

  async function enqueueOutboxMessage(text: string, attachments: Attachment[]): Promise<boolean> {
    const conversation = selectedRef.current;
    if (!conversation || !isPersistedConversation(conversation)) {
      setOutboxError("当前会话尚未创建，无法排队发送。");
      return false;
    }
    const attachmentSig = attachments.map((a) => `${a.fileName}:${a.size}`).sort().join(",");
    let id: string;
    if (
      pendingEnqueueIntentRef.current &&
      pendingEnqueueIntentRef.current.text === text.trim() &&
      pendingEnqueueIntentRef.current.attachmentSig === attachmentSig
    ) {
      id = pendingEnqueueIntentRef.current.id;
    } else {
      id = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
        ? crypto.randomUUID()
        : `outbox-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
      pendingEnqueueIntentRef.current = { text: text.trim(), attachmentSig, id };
    }
    const expectedRevision = outboxSnapshot?.revision;
    const conversationMode = normalizeConversationMode(conversation.mode);

    try {
      setOutboxError(null);
      // 先将携带 dataUrl 的附件导入为持久引用
      const persistentAttachments = await importOutboxAttachments(conversation.id, attachments);
      const snapshot = await mutateConversationOutbox(conversation.id, "enqueue", {
        id,
        expectedRevision,
        payload: {
          text,
          attachments: persistentAttachments,
          conversationMode,
        },
      });
      // 成功投递后清空保留的意图 UUID 缓存
      pendingEnqueueIntentRef.current = null;
      if (selectedRef.current?.id === conversation.id) {
        setOutboxSnapshot((current) => {
          if (current && snapshot.revision < current.revision) return current;
          return snapshot;
        });
      }
      showToast("已排队发送");
      return true;
    } catch (error) {
      setOutboxError(formatOutboxError(error));
      const snapshot = extractOutboxSnapshot(error);
      if (snapshot) {
        if (selectedRef.current?.id === conversation.id) {
          setOutboxSnapshot((current) => {
            if (current && snapshot.revision < current.revision) return current;
            return snapshot;
          });
        }
      } else {
        void loadOutbox(conversation.id);
      }
      return false;
    }
  }

  async function updateOutboxItem(
    id: string,
    text: string,
    attachments: Attachment[],
    expectedRevision?: number,
    targetConversationId?: number,
  ): Promise<boolean> {
    const conversation = selectedRef.current;
    if (!conversation || !isPersistedConversation(conversation)) return false;
    if (targetConversationId !== undefined && targetConversationId !== conversation.id) {
      setOutboxError("会话已切换，无法在此更新原会话的待发项。");
      return false;
    }
    const conversationMode = normalizeConversationMode(conversation.mode);

    try {
      setOutboxError(null);
      // 先将携带 dataUrl 的附件导入为持久引用
      const persistentAttachments = await importOutboxAttachments(conversation.id, attachments);
      const snapshot = await mutateConversationOutbox(conversation.id, "update", {
        id,
        expectedRevision, // 固定使用打开时的 revision，不拿最新 revision 覆盖旧编辑
        payload: {
          text,
          attachments: persistentAttachments,
          conversationMode,
        },
      });
      if (selectedRef.current?.id === conversation.id) {
        setOutboxSnapshot((current) => {
          if (current && snapshot.revision < current.revision) return current;
          return snapshot;
        });
      }
      showToast("待发项已更新");
      return true;
    } catch (error) {
      setOutboxError(formatOutboxError(error));
      const snapshot = extractOutboxSnapshot(error);
      if (snapshot) {
        if (selectedRef.current?.id === conversation.id) {
          setOutboxSnapshot((current) => {
            if (current && snapshot.revision < current.revision) return current;
            return snapshot;
          });
        }
      } else {
        void loadOutbox(conversation.id);
      }
      return false;
    }
  }

  async function removeOutboxItem(id: string): Promise<boolean> {
    const conversation = selectedRef.current;
    if (!conversation || !isPersistedConversation(conversation)) return false;
    const expectedRevision = outboxSnapshot?.revision;

    try {
      setOutboxError(null);
      const snapshot = await mutateConversationOutbox(conversation.id, "remove", {
        id,
        expectedRevision,
      });
      if (selectedRef.current?.id === conversation.id) {
        setOutboxSnapshot((current) => {
          if (current && snapshot.revision < current.revision) return current;
          return snapshot;
        });
      }
      showToast("已从待发箱移除");
      return true;
    } catch (error) {
      setOutboxError(formatOutboxError(error));
      const snapshot = extractOutboxSnapshot(error);
      if (snapshot) {
        if (selectedRef.current?.id === conversation.id) {
          setOutboxSnapshot((current) => {
            if (current && snapshot.revision < current.revision) return current;
            return snapshot;
          });
        }
      } else {
        void loadOutbox(conversation.id);
      }
      return false;
    }
  }

  async function resumeOutboxItem(id: string): Promise<boolean> {
    const conversation = selectedRef.current;
    if (!conversation || !isPersistedConversation(conversation)) return false;
    const expectedRevision = outboxSnapshot?.revision;

    try {
      setOutboxError(null);
      const snapshot = await mutateConversationOutbox(conversation.id, "resume", {
        id,
        expectedRevision,
      });
      if (selectedRef.current?.id === conversation.id) {
        setOutboxSnapshot((current) => {
          if (current && snapshot.revision < current.revision) return current;
          return snapshot;
        });
      }
      showToast("已恢复发送");
      return true;
    } catch (error) {
      setOutboxError(formatOutboxError(error));
      const snapshot = extractOutboxSnapshot(error);
      if (snapshot) {
        if (selectedRef.current?.id === conversation.id) {
          setOutboxSnapshot((current) => {
            if (current && snapshot.revision < current.revision) return current;
            return snapshot;
          });
        }
      } else {
        void loadOutbox(conversation.id);
      }
      return false;
    }
  }

  async function stopAndSendOutboxItem(id: string): Promise<boolean> {
    const conversation = selectedRef.current;
    if (!conversation || !isPersistedConversation(conversation)) return false;
    const currentRun = conversationRunsRef.current[conversation.id];
    const expectedTurnId = currentRun?.turnId?.trim();
    const expectedSessionId = currentRun?.sessionId?.trim();
    if (!expectedTurnId || !expectedSessionId) {
      setOutboxError("当前任务尚未建立有效的会话与轮次标识，无法停止并发送。");
      return false;
    }
    const expectedRevision = outboxSnapshot?.revision;

    try {
      setOutboxError(null);
      const snapshot = await mutateConversationOutbox(conversation.id, "stop_and_send", {
        id,
        expectedRevision,
        expectedSessionId,
        expectedTurnId,
      });
      if (selectedRef.current?.id === conversation.id) {
        setOutboxSnapshot((current) => {
          if (current && snapshot.revision < current.revision) return current;
          return snapshot;
        });
      }
      showToast("已排队到首位，将在当前任务停止后发送");
      return true;
    } catch (error) {
      setOutboxError(formatOutboxError(error));
      const snapshot = extractOutboxSnapshot(error);
      if (snapshot) {
        if (selectedRef.current?.id === conversation.id) {
          setOutboxSnapshot((current) => {
            if (current && snapshot.revision < current.revision) return current;
            return snapshot;
          });
        }
      } else {
        void loadOutbox(conversation.id);
      }
      return false;
    }
  }

  if (!authenticated) {
    return (
      <LoginView
        initialToken={autoLoginToken.current}
        busy={authenticating}
        error={loginError}
        onLogin={authenticate}
      />
    );
  }

  const previousConversation = navigationTarget(-1);
  const nextConversation = navigationTarget(1);

  return (
    <>
      <div
        className={[
          "app-view",
          conversationsOpen && "conversations-open",
          leftSidebarCollapsed && "left-sidebar-collapsed",
          rightSidebarCollapsed && "right-sidebar-collapsed",
        ].filter(Boolean).join(" ")}
        data-mobile-section={mobileSection}
      >
        <header className="desktop-navigation-bar">
          <nav className="desktop-navigation-group" aria-label="对话导航">
            <button
              className="topbar-icon"
              type="button"
              aria-label={leftSidebarCollapsed ? "展开左侧边栏" : "收起左侧边栏"}
              title={leftSidebarCollapsed ? "展开左侧边栏" : "收起左侧边栏"}
              aria-pressed={!leftSidebarCollapsed}
              onClick={() => setLeftSidebarCollapsed((collapsed) => !collapsed)}
            >
              <Icon name="panel-left" size={18} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label="回退到上一会话"
              title="回退到上一会话"
              disabled={!previousConversation}
              onClick={() => void navigateConversationHistory(-1)}
            >
              <Icon name="arrow-left" size={18} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label="前进到下一会话"
              title="前进到下一会话"
              disabled={!nextConversation}
              onClick={() => void navigateConversationHistory(1)}
            >
              <Icon name="arrow-right" size={18} />
            </button>
          </nav>
          <div className="desktop-navigation-group desktop-navigation-end">
            <button
              className="topbar-icon"
              type="button"
              aria-label={selectedConversation?.isArchived ? "取消归档" : "归档对话"}
              title={selectedConversation?.isArchived ? "取消归档" : "归档对话"}
              disabled={!isPersistedConversation(selectedConversation)}
              onClick={() => void updateArchiveState()}
            >
              <Icon name="archive" size={16} />
            </button>
            <button
              className="topbar-icon danger"
              type="button"
              aria-label="删除对话"
              title="删除对话"
              disabled={!isPersistedConversation(selectedConversation)}
              onClick={() => void deleteConversation()}
            >
              <Icon name="trash" size={16} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label={rightSidebarCollapsed ? "展开右侧边栏" : "收起右侧边栏"}
              title={rightSidebarCollapsed ? "展开右侧边栏" : "收起右侧边栏"}
              aria-pressed={!rightSidebarCollapsed}
              onClick={() => setRightSidebarCollapsed((collapsed) => !collapsed)}
            >
              <Icon name="panel-right" size={18} />
            </button>
          </div>
        </header>
        <ConversationSidebar
          conversations={conversations}
          archivedConversations={archivedConversations}
          archivedLoading={archivedLoading}
          selected={selectedConversation}
          agentProfiles={agentProfiles}
          connectionStatus={connectionStatus}
          onCreate={createConversation}
          onSelect={(conversation) => void selectConversation(conversation)}
          onLoadArchived={() => loadArchivedConversations()}
          onArchive={(conversation) => updateArchiveState(conversation, true)}
          onRestore={(conversation) => updateArchiveState(conversation, false)}
          onSetPinned={(conversation, pinned) => updatePinState(conversation, pinned)}
          onDelete={(conversation) => deleteConversation(conversation)}
        />
        <ChatPanel
          key={chatViewRevision}
          conversation={selectedConversation}
          unavailableMessage={isRetiredAgentConversation(selectedConversation, retiredAgentIds)
            ? RETIRED_AGENT_MESSAGE
            : selectedConversation && !canRunConversation(selectedConversation)
            ? UNSUPPORTED_CONVERSATION_MESSAGE
            : selectedConversation && !conversationAgentId(selectedConversation)
            ? MISSING_AGENT_MESSAGE
            : undefined}
          messages={messages}
          globalError={globalError}
          sending={sending}
          activeTaskId={activeRenderTaskId ?? activeTaskId}
          activeSessionId={activeSessionId}
          activeTurnId={activeTurnId}
          clarifyTaskId={clarifyTaskId}
          outboxSnapshot={outboxSnapshot}
          outboxError={outboxError}
          onOpenConversations={() => setConversationsOpen(true)}
          onArchive={() => void updateArchiveState()}
          onDelete={() => void deleteConversation()}
          onSend={sendMessage}
          onCancel={() => void cancelRun()}
          onClearError={() => setGlobalError("")}
          onAttachmentError={showError}
          onEnqueue={enqueueOutboxMessage}
          onUpdateOutboxItem={updateOutboxItem}
          onRemoveOutboxItem={removeOutboxItem}
          onResumeOutboxItem={resumeOutboxItem}
          onStopAndSendOutboxItem={stopAndSendOutboxItem}
          onClearOutboxError={() => setOutboxError(null)}
          skillRefreshRevision={skillRefreshRevision}
        />
        <ContextPane
          activePanel={contextPanel}
          workspacePath={workspacePath}
          workspaceItems={workspaceItems}
          workspaceFilePath={workspaceFilePath}
          workspaceContent={workspaceContent}
          workspaceDirty={workspaceDirty}
          browserSnapshot={browserSnapshot}
          browserFrameSeed={browserFrameSeed}
          onOpenConversations={() => setConversationsOpen(true)}
          onSelectPanel={setContextPanel}
          onWorkspacePath={() => {
            const parent = workspaceParentPath();
            if (parent && parent !== workspacePathRef.current) void loadWorkspace(parent);
          }}
          onWorkspaceItem={(item) => {
            if (item.isDirectory) void loadWorkspace(item.path);
            else void openWorkspaceFile(item.path);
          }}
          onWorkspaceRefresh={() => void loadWorkspace()}
          onWorkspaceContent={(content) => {
            setWorkspaceContent(content);
            setWorkspaceDirty(true);
          }}
          onWorkspaceSave={() => void saveWorkspaceFile()}
          onBrowserAction={(payload) => void browserAction(payload)}
          onBrowserRefresh={() => void refreshBrowser()}
          onOpenWorkspaceChanges={() => setWorkspaceChangesListOpen(true)}
        />

        <nav className="mobile-nav" aria-label="Web Chat 区域">
          {(["chat", "workspace", "browser"] as MobileSection[]).map((section) => (
            <button
              className={mobileSection === section ? "active" : ""}
              type="button"
              onClick={() => selectMobileSection(section)}
              key={section}
            >
              <Icon name={MOBILE_SECTION_ICON[section]} size={18} />
              <span>{{ chat: "聊天", workspace: "工作区", browser: "浏览器" }[section]}</span>
            </button>
          ))}
        </nav>
        <button
          className="conversation-scrim"
          type="button"
          aria-label="关闭对话列表"
          onClick={() => setConversationsOpen(false)}
        />
      </div>
      {toast && <div className="toast" role="status">{toast}</div>}
      {workspaceChangesListOpen && (
        <WorkspaceChangeListModal
          conversationId={selectedConversation?.id}
          workspaceId={selectedConversation?.id ? `conversation_${selectedConversation.id}` : "conversation_default"}
          onClose={() => setWorkspaceChangesListOpen(false)}
          onSelectChange={(change) => {
            setWorkspaceChangesListOpen(false);
            setPreviewChangeId(change.changeId);
          }}
        />
      )}
      {previewChangeId && (
        <WorkspaceChangeModal
          changeId={previewChangeId}
          conversationId={selectedConversation?.id}
          workspaceId={selectedConversation?.id ? `conversation_${selectedConversation.id}` : "conversation_default"}
          onClose={() => setPreviewChangeId(null)}
        />
      )}
    </>
  );
}
