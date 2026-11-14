import assert from "node:assert/strict";
import test from "node:test";
import { computeNonBlockingSummary, isCompactAvailableHeight } from "../src/viewportLayout.ts";

test("isCompactAvailableHeight correctly applies the <420px threshold", () => {
  // Viewports strictly under 420px (e.g. 320x640 mobile with virtual keyboard open, landscape mode)
  assert.equal(isCompactAvailableHeight(300), true);
  assert.equal(isCompactAvailableHeight(360), true);
  assert.equal(isCompactAvailableHeight(419), true);
  assert.equal(isCompactAvailableHeight(419.9), true);

  // Viewports at or above 420px (normal mobile without keyboard, desktop, tablet)
  assert.equal(isCompactAvailableHeight(420), false);
  assert.equal(isCompactAvailableHeight(421), false);
  assert.equal(isCompactAvailableHeight(600), false);
  assert.equal(isCompactAvailableHeight(800), false);
  assert.equal(isCompactAvailableHeight(1080), false);
});

test("computeNonBlockingSummary correctly computes summary text and state", () => {
  // Both empty
  assert.deepEqual(computeNonBlockingSummary(0, 0), { hasNonBlocking: false, summaryText: null });

  // Outbox only
  assert.deepEqual(computeNonBlockingSummary(3, 0), { hasNonBlocking: true, summaryText: "待发 3 条 · 更多" });

  // Attachments only
  assert.deepEqual(computeNonBlockingSummary(0, 2), { hasNonBlocking: true, summaryText: "附件 2 个 · 更多" });

  // Both outbox and attachments
  assert.deepEqual(computeNonBlockingSummary(2, 4), {
    hasNonBlocking: true,
    summaryText: "待发 2 条 · 附件 4 个 · 更多",
  });
});
