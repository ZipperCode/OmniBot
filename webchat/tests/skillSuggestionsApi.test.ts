import assert from "node:assert/strict";
import test from "node:test";
import {
  filterRestorableSkillRevisions,
  isCasConflictError,
  isExistingSkillTarget,
  isSkillFeedbackEligible,
  resolveSkillApprovePayload,
} from "../src/skillSuggestions.ts";
import type {
  SkillCandidateAsset,
  SkillFeedbackEligibility,
  SkillRevisionSnapshot,
} from "../src/types.ts";

test("resolveSkillApprovePayload sets expectedCurrentContentHash to null for new skills", () => {
  const newCandidate: SkillCandidateAsset = {
    candidateId: "cand_new",
    revision: 1,
    state: "pending_review",
    name: "new-tool-helper",
    description: "新建技能",
    bodyMarkdown: "...",
    createdAt: 1000,
    updatedAt: 1000,
  };

  const payload = resolveSkillApprovePayload(newCandidate, null);
  assert.equal(payload.targetSkillId, "new-tool-helper");
  assert.equal(payload.expectedCurrentContentHash, null);
});

test("resolveSkillApprovePayload sets expectedCurrentContentHash from feedbackEligibility when updating existing skill", () => {
  const existingCandidate: SkillCandidateAsset = {
    candidateId: "cand_update",
    revision: 2,
    state: "pending_review",
    name: "git-helper",
    targetSkillId: "git-helper",
    description: "更新技能",
    bodyMarkdown: "...",
    createdAt: 1000,
    updatedAt: 2000,
  };

  const feedbackEligibility: SkillFeedbackEligibility = {
    skillId: "git-helper",
    revisionId: "hash_rev_1",
    revision: 1,
    currentContentHash: "hash_current_sha256",
    eligible: true,
  };

  const payload = resolveSkillApprovePayload(existingCandidate, feedbackEligibility);
  assert.equal(payload.targetSkillId, "git-helper");
  assert.equal(payload.expectedCurrentContentHash, "hash_current_sha256");
});

test("isSkillFeedbackEligible validates subsequent turn usage prerequisites", () => {
  assert.equal(isSkillFeedbackEligible(null), false);
  assert.equal(isSkillFeedbackEligible(undefined), false);

  const ineligible: SkillFeedbackEligibility = {
    skillId: "test-skill",
    revisionId: "hash-1",
    revision: 1,
    currentContentHash: "hash-1",
    eligible: false,
    reason: "ineligible",
  };
  assert.equal(isSkillFeedbackEligible(ineligible), false);

  const eligibleWithoutUsage: SkillFeedbackEligibility = {
    skillId: "test-skill",
    revisionId: "hash-1",
    revision: 1,
    currentContentHash: "hash-1",
    eligible: true,
  };
  assert.equal(isSkillFeedbackEligible(eligibleWithoutUsage), false);

  const fullyEligible: SkillFeedbackEligibility = {
    skillId: "test-skill",
    revisionId: "hash-1",
    revision: 1,
    currentContentHash: "hash-1",
    eligible: true,
    usage: {
      conversationId: 10,
      sessionId: "session-1",
      turnId: "turn-1",
    },
  };
  assert.equal(isSkillFeedbackEligible(fullyEligible), true);
});

test("filterRestorableSkillRevisions rejects manually disabled or non-restorable revisions", () => {
  const revisions: SkillRevisionSnapshot[] = [
    {
      revisionId: "rev-1",
      name: "skill-v1",
      description: "可恢复版本",
      bodyMarkdown: "...",
      createdAt: 1000,
      wasManuallyDisabled: false,
      canRestore: true,
    },
    {
      revisionId: "rev-2",
      name: "skill-v2",
      description: "被用户手动停用的历史版本，禁止复活",
      bodyMarkdown: "...",
      createdAt: 2000,
      wasManuallyDisabled: true,
      canRestore: true,
    },
    {
      revisionId: "rev-3",
      name: "skill-v3",
      description: "不可恢复版本",
      bodyMarkdown: "...",
      createdAt: 3000,
      wasManuallyDisabled: false,
      canRestore: false,
    },
  ];

  const restorable = filterRestorableSkillRevisions(revisions);
  assert.equal(restorable.length, 1);
  assert.equal(restorable[0].revisionId, "rev-1");
});

test("isExistingSkillTarget identifies whether candidate targets an installed/existing skill", () => {
  const newCandidate: SkillCandidateAsset = {
    candidateId: "c1",
    revision: 1,
    state: "pending_review",
    name: "new-skill",
    description: "新技能",
    bodyMarkdown: "...",
    createdAt: 1000,
    updatedAt: 1000,
  };
  assert.equal(isExistingSkillTarget(newCandidate, null, []), false);

  const existingByHash: SkillCandidateAsset = {
    candidateId: "c2",
    revision: 1,
    state: "pending_review",
    name: "git-helper",
    targetSkillId: "git-helper",
    description: "已有技能",
    bodyMarkdown: "...",
    createdAt: 1000,
    updatedAt: 1000,
  };
  const eligibility: SkillFeedbackEligibility = {
    skillId: "git-helper",
    revisionId: "rev-hash-1",
    revision: 1,
    currentContentHash: "sha256-abc",
    eligible: false,
  };
  assert.equal(isExistingSkillTarget(existingByHash, eligibility, []), true);

  const existingByRevisions: SkillCandidateAsset = {
    candidateId: "c3",
    revision: 2,
    state: "pending_review",
    name: "tool-helper",
    description: "历史版本技能",
    bodyMarkdown: "...",
    createdAt: 1000,
    updatedAt: 1000,
  };
  const revisions: SkillRevisionSnapshot[] = [
    {
      revisionId: "rev-1",
      name: "tool-helper",
      description: "v1",
      bodyMarkdown: "...",
      createdAt: 500,
      wasManuallyDisabled: false,
      canRestore: true,
    },
  ];
  assert.equal(isExistingSkillTarget(existingByRevisions, null, revisions), true);
});

test("isCasConflictError accurately distinguishes revision CAS conflicts from 409 name/builtin/content conflicts", () => {
  assert.equal(isCasConflictError(new Error("skill_revision_conflict: expected rev 1")), true);
  assert.equal(isCasConflictError(new Error("409 revision conflict")), true);
  assert.equal(isCasConflictError("版本冲突：revision mismatch"), true);

  // Exact code handling: 409 for name, builtin, or content conflict must NOT be classified as CAS
  assert.equal(isCasConflictError(new Error("ApiError(409, 'skill_name_conflict')")), false);
  assert.equal(isCasConflictError(new Error("ApiError(409, 'skill_cannot_overwrite_builtin')")), false);
  assert.equal(isCasConflictError(new Error("ApiError(409, 'skill_content_conflict')")), false);
  assert.equal(isCasConflictError(new Error("ApiError(409, 'skill_content_modified')")), false);
  assert.equal(isCasConflictError(new Error("network error")), false);
});
