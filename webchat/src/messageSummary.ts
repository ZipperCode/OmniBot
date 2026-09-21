import type { ChatMessage } from "./types.ts";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function messageTime(message: ChatMessage): number {
  const raw = message.createAt;
  const date = typeof raw === "number" ? new Date(raw) : new Date(String(raw ?? ""));
  return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

function messageContent(message: ChatMessage): Record<string, unknown> {
  return isRecord(message.content) ? message.content : {};
}

/** 摘要条目种类: 用户消息 / 助手回复 / 错误消息 */
export type MessageSummaryKind = "user" | "assistant" | "error";

export interface MessageSummaryEntry {
  /** 稳定 DOM 锚点 id, 渲染时挂在对应消息元素上 */
  anchorId: string;
  kind: MessageSummaryKind;
  title: string;
  time: number;
  /** 来源消息引用, 供面板把锚点贴回渲染节点 */
  message: ChatMessage;
}

const ANCHOR_PREFIX = "omni-msg-";
const TITLE_MAX_LENGTH = 48;

/** 生成稳定锚点 id: 优先 contentId, 其次 id, 最后时间戳兜底; 清理不安全字符 */
export function messageAnchorId(message: ChatMessage): string {
  const raw = message.contentId ?? message.id ?? `t${messageTime(message)}`;
  const safe = String(raw).replace(/[^a-zA-Z0-9_-]+/g, "-").replace(/^-+|-+$/g, "");
  return `${ANCHOR_PREFIX}${safe || "x"}`;
}

/** 取第一行并剥离 Markdown 标记, 超长截断 */
export function summarizeMessageText(text: string): string {
  const firstLine = (text.split("\n", 1)[0] ?? "");
  const plain = firstLine
    .replace(/!\[[^\]]*\]\([^)]*\)/g, "")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[#>*_~`|-]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!plain) return "";
  return plain.length > TITLE_MAX_LENGTH
    ? `${plain.slice(0, TITLE_MAX_LENGTH - 1)}…`
    : plain;
}

/** 可摘要消息: 有正文文本的用户/助手消息; 工具卡、深度思考卡与纯 loading 占位跳过 */
export function isSummarizableMessage(message: ChatMessage): boolean {
  if (Number(message.type) === 2 || isRecord(messageContent(message).cardData)) return false;
  return Boolean(String(messageContent(message).text ?? "").trim());
}

/** 由当前消息派生摘要条目 (按时间正序), 锚点 id 在冲突时自动去重 */
export function buildMessageSummaries(messages: ChatMessage[]): MessageSummaryEntry[] {
  const entries: MessageSummaryEntry[] = [];
  const usedAnchors = new Set<string>();
  const sorted = [...messages].sort((left, right) => messageTime(left) - messageTime(right));
  for (const message of sorted) {
    if (!isSummarizableMessage(message)) continue;
    const title = summarizeMessageText(String(messageContent(message).text ?? ""));
    if (!title) continue;
    const kind: MessageSummaryKind = message.isError
      ? "error"
      : Number(message.user) === 1
        ? "user"
        : "assistant";
    let anchorId = messageAnchorId(message);
    let suffix = 2;
    while (usedAnchors.has(anchorId)) {
      anchorId = `${messageAnchorId(message)}-${suffix}`;
      suffix += 1;
    }
    usedAnchors.add(anchorId);
    entries.push({ anchorId, kind, title, time: messageTime(message), message });
  }
  return entries;
}
