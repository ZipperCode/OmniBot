import assert from "node:assert/strict";
import test from "node:test";
import {
  resolveTurnArtifacts,
  resolveTurnIdentity,
  resolveTurnLiveScene,
  resolveTurnSkillSuggestion,
  resolveTurnWorkspaceChanges,
} from "../src/turnResultActions.ts";
import type { ChatMessage } from "../src/types.ts";

test("resolveTurnArtifacts extracts unique artifacts from card and attachments", () => {
  const messages: ChatMessage[] = [
    {
      id: "msg-1",
      type: 2,
      user: 2,
      content: {
        cardData: {
          artifacts: [
            { path: "/sdcard/Download/app-debug.apk", title: "app-debug.apk", size: 1048576 },
            { uri: "https://example.com/report.pdf", name: "report.pdf", size: 2048 },
          ],
        },
      },
    },
    {
      id: "msg-2",
      type: 2,
      user: 2,
      content: {
        cardData: {
          artifact: { path: "/sdcard/Download/app-debug.apk", title: "duplicate.apk" },
          rawExtras: {
            artifacts: [
              { path: "/sdcard/Download/manifest.json", title: "manifest.json", sizeBytes: 512 },
            ],
          },
        },
        attachments: [
          { path: "/sdcard/Download/extra.txt", title: "extra.txt" },
        ],
      },
    },
  ];

  const artifacts = resolveTurnArtifacts(messages);
  assert.equal(artifacts.length, 4);
  assert.equal(artifacts[0].title, "app-debug.apk");
  assert.equal(artifacts[0].path, "/sdcard/Download/app-debug.apk");
  assert.equal(artifacts[0].size, 1048576);

  assert.equal(artifacts[1].title, "report.pdf");
  assert.equal(artifacts[1].uri, "https://example.com/report.pdf");

  assert.equal(artifacts[2].title, "manifest.json");
  assert.equal(artifacts[2].path, "/sdcard/Download/manifest.json");
  assert.equal(artifacts[2].size, 512);

  assert.equal(artifacts[3].title, "extra.txt");
});

test("resolveTurnArtifacts returns empty array when no artifacts present", () => {
  const messages: ChatMessage[] = [
    { id: "msg-1", type: 1, user: 2, content: { text: "纯文本回复" } },
  ];
  assert.deepEqual(resolveTurnArtifacts(messages), []);
});

test("resolveTurnWorkspaceChanges resolves workspace_change actions and diffText", () => {
  const withAction: ChatMessage[] = [
    {
      id: "msg-1",
      type: 2,
      user: 2,
      content: {
        cardData: {
          diffText: "--- a/test.txt\n+++ b/test.txt\n@@ -1 +1 @@\n-old\n+new",
          actions: [
            {
              type: "workspace_change",
              payload: {
                changeId: "change-uuid-1234",
                conversationId: 42,
              },
            },
          ],
        },
      },
    },
  ];

  const changes = resolveTurnWorkspaceChanges(withAction);
  assert.ok(changes);
  assert.equal(changes?.changeId, "change-uuid-1234");
  assert.equal(changes?.conversationId, 42);
  assert.match(changes?.diffText ?? "", /\+new/);

  const emptyMessages: ChatMessage[] = [
    { id: "msg-2", type: 1, user: 2, content: { text: "无修改" } },
  ];
  assert.equal(resolveTurnWorkspaceChanges(emptyMessages), null);
});

test("resolveTurnLiveScene extracts valid displayId and ignores invalid values", () => {
  const validMessages: ChatMessage[] = [
    {
      id: "msg-1",
      type: 2,
      user: 2,
      content: {
        cardData: {
          displayId: 2,
          sessionId: "session-1",
          turnId: "turn-1",
          conversationId: "conv-1",
        },
      },
    },
  ];
  const liveScene = resolveTurnLiveScene(validMessages);
  assert.ok(liveScene);
  assert.equal(liveScene?.displayId, 2);
  assert.equal(liveScene?.sessionId, "session-1");
  assert.equal(liveScene?.turnId, "turn-1");

  // String displayId is parsed correctly
  const stringDisplayMessages: ChatMessage[] = [
    {
      id: "msg-2",
      type: 2,
      user: 2,
      content: {
        cardData: {
          display_id: "5",
        },
      },
    },
  ];
  assert.equal(resolveTurnLiveScene(stringDisplayMessages)?.displayId, 5);

  // Negative or 0 displayId is ignored
  const invalidMessages: ChatMessage[] = [
    {
      id: "msg-3",
      type: 2,
      user: 2,
      content: {
        cardData: {
          displayId: 0,
        },
      },
    },
  ];
  assert.equal(resolveTurnLiveScene(invalidMessages), null);
});

test("resolveTurnSkillSuggestion extracts candidate data and ignores empty objects", () => {
  const messages: ChatMessage[] = [
    {
      id: "msg-1",
      type: 2,
      user: 2,
      content: {
        cardData: {
          skillSuggestion: {
            candidateId: "cand-123",
            name: "git-commit-helper",
            description: "生成规范的 Git Commit 消息",
            bodyMarkdown: "## 规则\n- Conventional Commits",
            verificationStatus: "trial",
            revision: 2,
          },
        },
      },
    },
  ];

  const suggestion = resolveTurnSkillSuggestion(messages);
  assert.ok(suggestion);
  assert.equal(suggestion?.candidateId, "cand-123");
  assert.equal(suggestion?.name, "git-commit-helper");
  assert.equal(suggestion?.description, "生成规范的 Git Commit 消息");
  assert.equal(suggestion?.verificationStatus, "trial");
  assert.equal(suggestion?.revision, 2);

  const emptyMessages: ChatMessage[] = [
    { id: "msg-2", type: 1, user: 2, content: { text: "普通消息" } },
  ];
  assert.equal(resolveTurnSkillSuggestion(emptyMessages), null);
});

test("resolveTurnIdentity extracts turnId and conversationId from messages", () => {
  const messages: ChatMessage[] = [
    {
      id: "msg-1",
      type: 2,
      user: 2,
      streamMeta: { turnId: "turn-from-meta" },
      content: {
        cardData: {
          conversationId: 88,
        },
      },
    },
  ];

  const identity = resolveTurnIdentity(messages);
  assert.equal(identity.turnId, "turn-from-meta");
  assert.equal(identity.conversationId, 88);
});

test("TurnSkillSuggestion preserves turnId and conversationId identity", () => {
  const messages: ChatMessage[] = [
    {
      id: "msg-1",
      type: 2,
      user: 2,
      streamMeta: { turnId: "turn-identity-1" },
      content: {
        cardData: {
          conversationId: 42,
          skillSuggestion: {
            candidateId: "cand-42",
            name: "export-helper",
            description: "导出助手",
          },
        },
      },
    },
  ];

  const identity = resolveTurnIdentity(messages);
  assert.equal(identity.turnId, "turn-identity-1");
  assert.equal(identity.conversationId, 42);

  const rawSuggestion = resolveTurnSkillSuggestion(messages);
  assert.ok(rawSuggestion);
  const fullSuggestion = {
    ...rawSuggestion!,
    turnId: identity.turnId,
    conversationId: identity.conversationId,
  };
  assert.equal(fullSuggestion.turnId, "turn-identity-1");
  assert.equal(fullSuggestion.conversationId, 42);
  assert.equal(fullSuggestion.candidateId, "cand-42");
});
