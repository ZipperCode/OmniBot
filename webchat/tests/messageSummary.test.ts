import assert from "node:assert/strict";
import test from "node:test";
import { buildMessageSummaries, messageAnchorId, summarizeMessageText } from "../src/messageSummary.ts";
import type { ChatMessage } from "../src/types.ts";

function message(id: string, user: number, text: string, createAt: number): ChatMessage {
  return { id, user, createAt, content: { text } };
}

test("message summaries preserve chronological order and classify authors", () => {
  const entries = buildMessageSummaries([
    message("assistant", 0, "Answer", 20),
    message("user", 1, "Question", 10),
    { ...message("card", 0, "ignored", 30), content: { cardData: { type: "tool" }, text: "ignored" } },
  ]);
  assert.deepEqual(entries.map((entry) => [entry.title, entry.kind]), [["Question", "user"], ["Answer", "assistant"]]);
  assert.equal(entries[0].anchorId, "omni-msg-user");
});

test("summary text strips markdown and anchor ids are safe", () => {
  assert.equal(summarizeMessageText("# Hello **world**\nsecond"), "Hello world");
  assert.equal(messageAnchorId(message("a/b", 1, "x", 1)), "omni-msg-a-b");
});
