import type { ChatMessage } from "./types";

export interface TurnArtifactItem {
  title: string;
  path: string;
  uri?: string;
  shellPath?: string;
  mimeType?: string;
  previewKind?: string;
  size?: number;
}

export interface TurnLiveSceneTarget {
  displayId: number;
  sessionId?: string;
  turnId?: string;
  conversationId?: string;
}

export interface TurnSkillSuggestion {
  id: string;
  name: string;
  description?: string;
  bodyMarkdown?: string;
  verificationStatus?: string;
  candidateId?: string;
  revision?: number;
  turnId?: string;
  conversationId?: number;
  targetSkillId?: string;
  state?: string;
}

export interface TurnWorkspaceChangesSummary {
  changeId?: string;
  conversationId?: number;
  diffText?: string;
  fileCount?: number;
}

function parseDisplayId(val: unknown): number | null {
  if (typeof val === "number" && val > 0) return Math.floor(val);
  if (typeof val === "string") {
    const parsed = parseInt(val, 10);
    if (!Number.isNaN(parsed) && parsed > 0) return parsed;
  }
  return null;
}

export function resolveTurnArtifacts(messages: ChatMessage[]): TurnArtifactItem[] {
  const items: TurnArtifactItem[] = [];
  const seen = new Set<string>();

  function addArtifact(raw: unknown) {
    if (!raw || typeof raw !== "object") return;
    const rec = raw as Record<string, unknown>;
    const path = String(rec.androidPath ?? rec.path ?? "").trim();
    const uri = String(rec.uri ?? "").trim();
    if (!path && !uri) return;

    const key = path || uri;
    if (seen.has(key)) return;
    seen.add(key);

    const title = String(
      rec.title ??
      rec.fileName ??
      rec.name ??
      (path ? path.split("/").pop() : "产物")
    ).trim();

    const sizeNum = typeof rec.size === "number"
      ? rec.size
      : (typeof rec.sizeBytes === "number" ? rec.sizeBytes : undefined);

    items.push({
      title: title || "产物",
      path,
      uri: uri || undefined,
      shellPath: rec.shellPath ? String(rec.shellPath) : (rec.workspacePath ? String(rec.workspacePath) : undefined),
      mimeType: rec.mimeType ? String(rec.mimeType) : undefined,
      previewKind: rec.previewKind ? String(rec.previewKind) : undefined,
      size: sizeNum,
    });
  }

  for (const message of messages) {
    const content = typeof message.content === "object" && message.content !== null
      ? (message.content as Record<string, unknown>)
      : {};
    const cardData = typeof content.cardData === "object" && content.cardData !== null
      ? (content.cardData as Record<string, unknown>)
      : (Number(message.type) === 2 ? content : null);

    if (cardData) {
      if (Array.isArray(cardData.artifacts)) {
        cardData.artifacts.forEach(addArtifact);
      }
      if (cardData.artifact && typeof cardData.artifact === "object") {
        addArtifact(cardData.artifact);
      }
      const rawExtras = cardData.rawExtras as Record<string, unknown> | undefined;
      if (rawExtras && Array.isArray(rawExtras.artifacts)) {
        rawExtras.artifacts.forEach(addArtifact);
      }
    }

    if (Array.isArray(content.attachments)) {
      content.attachments.forEach(addArtifact);
    }
  }

  return items;
}

export function resolveTurnWorkspaceChanges(messages: ChatMessage[]): TurnWorkspaceChangesSummary | null {
  for (const message of messages) {
    const content = typeof message.content === "object" && message.content !== null
      ? (message.content as Record<string, unknown>)
      : {};
    const cardData = typeof content.cardData === "object" && content.cardData !== null
      ? (content.cardData as Record<string, unknown>)
      : (Number(message.type) === 2 ? content : null);

    if (!cardData) continue;

    // Check workspace_change action
    const actions = Array.isArray(cardData.actions) ? cardData.actions : [];
    for (const act of actions) {
      if (act && typeof act === "object") {
        const actionObj = act as Record<string, unknown>;
        if (String(actionObj.type ?? "").toLowerCase() === "workspace_change") {
          const payload = typeof actionObj.payload === "object" && actionObj.payload !== null
            ? (actionObj.payload as Record<string, unknown>)
            : actionObj;
          return {
            changeId: payload.changeId ? String(payload.changeId) : undefined,
            conversationId: typeof payload.conversationId === "number" ? payload.conversationId : undefined,
            diffText: cardData.diffText ? String(cardData.diffText) : undefined,
          };
        }
      }
    }

    // Check direct diffText
    const diffText = String(cardData.diffText ?? cardData.diff ?? "").trim();
    if (diffText) {
      return {
        diffText,
      };
    }
  }
  return null;
}

export function resolveTurnLiveScene(messages: ChatMessage[]): TurnLiveSceneTarget | null {
  let foundDisplayId: number | null = null;
  let foundSessionId: string | undefined;
  let foundTurnId: string | undefined;
  let foundConversationId: string | undefined;

  for (const message of messages) {
    const content = typeof message.content === "object" && message.content !== null
      ? (message.content as Record<string, unknown>)
      : {};
    const cardData = typeof content.cardData === "object" && content.cardData !== null
      ? (content.cardData as Record<string, unknown>)
      : (Number(message.type) === 2 ? content : null);

    if (cardData) {
      const dataObj = typeof cardData.data === "object" && cardData.data !== null
        ? (cardData.data as Record<string, unknown>)
        : null;

      const dId = parseDisplayId(cardData.displayId) ??
        parseDisplayId(cardData.display_id) ??
        (dataObj ? (parseDisplayId(dataObj.display_id) ?? parseDisplayId(dataObj.displayId)) : null);

      if (dId !== null) {
        foundDisplayId = dId;
      }

      if (!foundSessionId && cardData.sessionId) {
        foundSessionId = String(cardData.sessionId);
      }
      if (!foundTurnId && cardData.turnId) {
        foundTurnId = String(cardData.turnId);
      }
      if (!foundConversationId && cardData.conversationId) {
        foundConversationId = String(cardData.conversationId);
      }
    }
  }

  if (foundDisplayId === null) return null;

  return {
    displayId: foundDisplayId,
    sessionId: foundSessionId,
    turnId: foundTurnId,
    conversationId: foundConversationId,
  };
}

export function resolveTurnSkillSuggestion(messages: ChatMessage[]): TurnSkillSuggestion | null {
  for (const message of messages) {
    const content = typeof message.content === "object" && message.content !== null
      ? (message.content as Record<string, unknown>)
      : {};
    const cardData = typeof content.cardData === "object" && content.cardData !== null
      ? (content.cardData as Record<string, unknown>)
      : (Number(message.type) === 2 ? content : null);

    if (!cardData) continue;

    const raw = (cardData.skillSuggestion ?? cardData.skillCandidate ?? cardData.suggestion) as
      | Record<string, unknown>
      | undefined;

    if (raw && typeof raw === "object") {
      const name = String(raw.name ?? raw.title ?? "").trim();
      const id = String(raw.id ?? raw.candidateId ?? name).trim();
      if (name || id) {
        return {
          id: id || name,
          name: name || id,
          description: raw.description ? String(raw.description) : undefined,
          bodyMarkdown: raw.bodyMarkdown ? String(raw.bodyMarkdown) : (raw.body ? String(raw.body) : undefined),
          verificationStatus: raw.verificationStatus ? String(raw.verificationStatus) : undefined,
          candidateId: raw.candidateId ? String(raw.candidateId) : (id || undefined),
          revision: typeof raw.revision === "number" ? raw.revision : undefined,
        };
      }
    }
  }
  return null;
}

export function resolveTurnIdentity(messages: ChatMessage[]): {
  turnId?: string;
  conversationId?: number;
} {
  let turnId: string | undefined;
  let conversationId: number | undefined;

  for (const message of messages) {
    if (!turnId && message.streamMeta?.turnId) {
      turnId = String(message.streamMeta.turnId);
    }
    const content = typeof message.content === "object" && message.content !== null
      ? (message.content as Record<string, unknown>)
      : {};
    const cardData = typeof content.cardData === "object" && content.cardData !== null
      ? (content.cardData as Record<string, unknown>)
      : (Number(message.type) === 2 ? content : null);

    if (cardData) {
      if (!turnId && (cardData.turnId || cardData.turnID)) {
        turnId = String(cardData.turnId || cardData.turnID);
      }
      if (conversationId === undefined && typeof cardData.conversationId === "number") {
        conversationId = cardData.conversationId;
      }
    }
  }

  return { turnId, conversationId };
}
