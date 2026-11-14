import assert from "node:assert/strict";
import test from "node:test";
import {
  isItemModifiable,
  isMonotonicRevision,
  outboxItemAttachments,
  outboxItemText,
  sanitizeAttachmentForOutbox,
  sortOutboxItemsFifo,
} from "../src/outbox.ts";
import type { Attachment, OutboxItem } from "../types.ts";

test("sortOutboxItemsFifo orders items strictly by sequence ascending then createdAt", () => {
  const items: OutboxItem[] = [
    {
      id: "item-3",
      conversationId: 1,
      agentId: "xiaowan-acp",
      state: "pending",
      revision: 1,
      sequence: 3,
      createdAt: 300,
      updatedAt: 300,
    },
    {
      id: "item-1",
      conversationId: 1,
      agentId: "xiaowan-acp",
      state: "dispatching",
      revision: 1,
      sequence: 1,
      createdAt: 100,
      updatedAt: 100,
    },
    {
      id: "item-2b",
      conversationId: 1,
      agentId: "xiaowan-acp",
      state: "paused",
      revision: 1,
      sequence: 2,
      createdAt: 250,
      updatedAt: 250,
    },
    {
      id: "item-2a",
      conversationId: 1,
      agentId: "xiaowan-acp",
      state: "pending",
      revision: 1,
      sequence: 2,
      createdAt: 200,
      updatedAt: 200,
    },
  ];

  const sorted = sortOutboxItemsFifo(items);
  assert.deepEqual(
    sorted.map((item) => item.id),
    ["item-1", "item-2a", "item-2b", "item-3"],
  );

  // Empty and single item list boundaries
  assert.deepEqual(sortOutboxItemsFifo([]), []);
  assert.equal(sortOutboxItemsFifo([items[0]])[0].id, "item-3");
});

test("isItemModifiable permits only pending and paused items for modification, removal, and stop_and_send", () => {
  assert.equal(isItemModifiable("pending"), true);
  assert.equal(isItemModifiable("paused"), true);
  // dispatching and submitted are strictly read-only
  assert.equal(isItemModifiable("dispatching"), false);
  assert.equal(isItemModifiable("submitted"), false);
});

test("sanitizeAttachmentForOutbox strips dataUrl and preserves persistent path and uri references", () => {
  const dataUrlAttachment: Attachment = {
    fileName: "doc.pdf",
    mimeType: "application/pdf",
    size: 2048,
    dataUrl: "data:application/pdf;base64,JVBERi0xLjQK...",
    isImage: false,
    path: "attachments/doc.pdf",
  };

  const sanitized = sanitizeAttachmentForOutbox(dataUrlAttachment);
  assert.equal(sanitized.fileName, "doc.pdf");
  assert.equal(sanitized.mimeType, "application/pdf");
  assert.equal(sanitized.size, 2048);
  assert.equal(sanitized.isImage, false);
  assert.equal(sanitized.path, "attachments/doc.pdf");
  assert.equal("dataUrl" in sanitized && sanitized.dataUrl !== undefined, false);

  const uriAttachment: Attachment = {
    fileName: "image.png",
    mimeType: "image/png",
    size: 512,
    isImage: true,
    uri: "content://media/external/images/media/1",
  };
  const sanitizedUri = sanitizeAttachmentForOutbox(uriAttachment);
  assert.equal(sanitizedUri.uri, "content://media/external/images/media/1");
  assert.equal("dataUrl" in sanitizedUri && sanitizedUri.dataUrl !== undefined, false);
});

test("isMonotonicRevision guarantees outbox snapshot revisions never regress", () => {
  assert.equal(isMonotonicRevision(1, undefined), true);
  assert.equal(isMonotonicRevision(1, null), true);
  assert.equal(isMonotonicRevision(2, 1), true);
  assert.equal(isMonotonicRevision(2, 2), true);
  assert.equal(isMonotonicRevision(1, 2), false);
});

test("outboxItemText and outboxItemAttachments resolve both top-level and payload-nested forms", () => {
  const sampleAttachment: Attachment = {
    fileName: "note.txt",
    mimeType: "text/plain",
    size: 64,
    isImage: false,
    path: "attachments/note.txt",
  };

  const topLevelItem: OutboxItem = {
    id: "item-top",
    conversationId: 1,
    agentId: "xiaowan-acp",
    state: "pending",
    revision: 1,
    sequence: 1,
    createdAt: 100,
    updatedAt: 100,
    text: "顶层文字",
    attachments: [sampleAttachment],
  };

  assert.equal(outboxItemText(topLevelItem), "顶层文字");
  assert.equal(outboxItemAttachments(topLevelItem).length, 1);
  assert.equal(outboxItemAttachments(topLevelItem)[0].fileName, "note.txt");

  const nestedItem: OutboxItem = {
    id: "item-nested",
    conversationId: 1,
    agentId: "xiaowan-acp",
    state: "paused",
    revision: 2,
    sequence: 2,
    createdAt: 200,
    updatedAt: 200,
    payload: {
      text: "嵌套文字",
      attachments: [sampleAttachment],
    },
  };

  assert.equal(outboxItemText(nestedItem), "嵌套文字");
  assert.equal(outboxItemAttachments(nestedItem).length, 1);
  assert.equal(outboxItemAttachments(nestedItem)[0].fileName, "note.txt");
});
