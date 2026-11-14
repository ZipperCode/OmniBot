import assert from "node:assert/strict";
import test from "node:test";
import {
  classifyWorkspaceChangeRestoreError,
  classifyWorkspaceChangeRestoreStatus,
  isRestoreEligible,
  resolveWorkspaceChangeHashes,
} from "../src/workspaceChanges.ts";
import type { WorkspaceChangeRecord } from "../src/types.ts";

test("resolveWorkspaceChangeHashes uses server expectedAfterHashes directly", () => {
  const recordWithBackendHashes: WorkspaceChangeRecord = {
    changeId: "change-1",
    operation: "write",
    targetPath: "src/index.ts",
    revision: 1,
    createdAt: 1000,
    snapshotStatus: "ready",
    canRestore: true,
    expectedAfterHashes: {
      "src/index.ts": "hash_direct_abc",
    },
  };

  const hashes = resolveWorkspaceChangeHashes(recordWithBackendHashes);
  assert.deepEqual(hashes, {
    "src/index.ts": "hash_direct_abc",
  });
});

test("resolveWorkspaceChangeHashes returns null when server hashes are missing (clean cutover)", () => {
  const recordWithoutHashes: WorkspaceChangeRecord = {
    changeId: "change-2",
    operation: "move",
    sourceRelativePath: "old.ts",
    targetRelativePath: "new.ts",
    beforeSha256: "hash_old",
    afterSha256: "hash_new",
    revision: 1,
    createdAt: 1000,
    snapshotStatus: "ready",
    canRestore: true,
  };

  // 严格禁止前端猜测 fallback，缺失时直接为 null 且不可恢复
  assert.equal(resolveWorkspaceChangeHashes(recordWithoutHashes), null);
});

test("isRestoreEligible strictly requires ready status, canRestore flag, and server expectedAfterHashes", () => {
  const validRecord: WorkspaceChangeRecord = {
    changeId: "change-3",
    operation: "write",
    revision: 1,
    createdAt: 1000,
    snapshotStatus: "ready",
    canRestore: true,
    expectedAfterHashes: {
      "file.ts": "hash_123",
    },
  };
  assert.equal(isRestoreEligible(validRecord), true);

  // Missing expectedAfterHashes -> false
  assert.equal(isRestoreEligible({ ...validRecord, expectedAfterHashes: undefined }), false);
  assert.equal(isRestoreEligible({ ...validRecord, expectedAfterHashes: {} }), false);

  // snapshotStatus not ready -> false
  assert.equal(isRestoreEligible({ ...validRecord, snapshotStatus: "conflict" }), false);
  assert.equal(isRestoreEligible({ ...validRecord, snapshotStatus: "unavailable" }), false);

  // canRestore false -> false
  assert.equal(isRestoreEligible({ ...validRecord, canRestore: false }), false);
});

test("classifyWorkspaceChangeRestoreError classifies typed error code and extracts snapshot diffText", () => {
  const conflictError = Object.assign(new Error("工作区当前文件已被外部修改，与操作后 SHA-256 不符"), {
    code: "workspace_change_conflict",
    snapshot: {
      diffText: "--- a/file.ts\n+++ b/file.ts\n@@ -1 +1 @@\n-expected\n+external",
    },
  });

  const classifiedConflict = classifyWorkspaceChangeRestoreError(conflictError);
  assert.equal(classifiedConflict.isConflict, true);
  assert.equal(classifiedConflict.isBusy, false);
  assert.match(classifiedConflict.userMessage, /禁止强制覆盖/);
  assert.match(classifiedConflict.conflictDiff ?? "", /\+external/);

  const busyError = Object.assign(new Error("工作区正忙"), {
    code: "workspace_change_busy",
  });

  const classifiedBusy = classifyWorkspaceChangeRestoreError(busyError);
  assert.equal(classifiedBusy.isConflict, false);
  assert.equal(classifiedBusy.isBusy, true);
  assert.match(classifiedBusy.userMessage, /工作区正忙/);
});

test("classifyWorkspaceChangeRestoreStatus handles result envelope states", () => {
  const success = classifyWorkspaceChangeRestoreStatus({
    success: true,
    status: "restored",
    message: "已成功确认恢复",
    changeId: "change-1",
    revision: 2,
  });
  assert.equal(success.isSuccess, true);
  assert.equal(success.conflictDiffRequired, false);

  const conflict = classifyWorkspaceChangeRestoreStatus({
    success: false,
    status: "workspace_change_conflict",
    message: "Conflict",
    changeId: "change-1",
  });
  assert.equal(conflict.isSuccess, false);
  assert.equal(conflict.conflictDiffRequired, true);

  const busy = classifyWorkspaceChangeRestoreStatus({
    success: false,
    status: "workspace_change_busy",
    message: "Busy",
    changeId: "change-1",
  });
  assert.equal(busy.isSuccess, false);
  assert.equal(busy.isBusy, true);
});
