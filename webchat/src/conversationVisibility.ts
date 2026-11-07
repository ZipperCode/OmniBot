import type { Conversation } from "./types";

/** Retirement is declared by the native catalog; historical ownership remains unchanged. */
export function isRetiredAgentConversation(
  conversation: Conversation | null | undefined,
  retiredAgentIds: readonly string[],
): boolean {
  const agentId = conversation?.agentId?.trim();
  return conversation?.mode !== "normal" && conversation?.mode !== "chat_only"
    && Boolean(agentId && retiredAgentIds.includes(agentId));
}
