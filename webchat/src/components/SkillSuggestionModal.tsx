import { useEffect, useRef, useState } from "react";
import { getSkillSuggestions, mutateSkillSuggestion } from "../api";
import type {
  SkillCandidateAsset,
  SkillFeedbackEligibility,
  SkillRevisionSnapshot,
  SkillSuggestionsResponse,
} from "../types";
import {
  filterRestorableSkillRevisions,
  formatSafeSkillError,
  formatSkipReason,
  isCasConflictError,
  isExistingSkillTarget,
  resolveSkillApprovePayload,
} from "../skillSuggestions";
import { Icon } from "./Icon";

export interface SkillSuggestionModalProps {
  candidateId?: string;
  initialCandidate?: SkillCandidateAsset | null;
  conversationId?: number;
  turnId?: string;
  skillRefreshRevision?: number;
  onClose: () => void;
  onCandidateUpdated?: (candidate: SkillCandidateAsset) => void;
}

export function SkillSuggestionModal({
  candidateId,
  initialCandidate,
  conversationId,
  turnId,
  skillRefreshRevision = 0,
  onClose,
  onCandidateUpdated,
}: SkillSuggestionModalProps) {
  const [candidate, setCandidate] = useState<SkillCandidateAsset | null>(initialCandidate ?? null);
  const [candidatesList, setCandidatesList] = useState<SkillCandidateAsset[]>([]);
  const [feedbackEligibility, setFeedbackEligibility] = useState<SkillFeedbackEligibility | null>(null);
  const [revisions, setRevisions] = useState<SkillRevisionSnapshot[]>([]);
  const [autoEnabled, setAutoEnabled] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [casConflict, setCasConflict] = useState(false);
  const [showOverwriteConfirm, setShowOverwriteConfirm] = useState(false);

  // Editable fields
  const [editName, setEditName] = useState(candidate?.name ?? "");
  const [editDescription, setEditDescription] = useState(candidate?.description ?? "");
  const [editBody, setEditBody] = useState(candidate?.bodyMarkdown ?? "");
  const [actionBusy, setActionBusy] = useState(false);

  // Track editing state in ref to avoid overwriting draft on background SSE refresh
  const isEditingRef = useRef(isEditing);
  isEditingRef.current = isEditing;

  // Track request sequence so latest response always wins
  const loadSeqRef = useRef(0);

  async function loadData(options?: { forceOverwriteDraft?: boolean }) {
    const seq = ++loadSeqRef.current;
    setLoading(true);
    setError(null);
    if (options?.forceOverwriteDraft) {
      setCasConflict(false);
    }
    try {
      // 1. Query candidate list first (before candidate target is known)
      const resp: SkillSuggestionsResponse = await getSkillSuggestions({
        conversationId,
        turnId,
      });

      if (seq !== loadSeqRef.current) return;

      setAutoEnabled(resp.enabled);
      setCandidatesList(resp.items);

      const found = candidateId
        ? resp.items.find((item) => item.candidateId === candidateId)
        : (resp.items[0] || initialCandidate || null);

      if (found) {
        setCandidate(found);
        // Latest response wins; do not overwrite editor draft on event refresh
        if (!isEditingRef.current || options?.forceOverwriteDraft) {
          setEditName(found.name);
          setEditDescription(found.description);
          setEditBody(found.bodyMarkdown);
          if (options?.forceOverwriteDraft) {
            setIsEditing(false);
          }
        }
      }

      // 2. Fetch installed feedback/history for actual selected target (allow uninstalled target detail result)
      const targetSkillId = (found?.targetSkillId || found?.name || "").trim();
      if (targetSkillId) {
        try {
          const detailResp = await getSkillSuggestions({
            conversationId,
            turnId,
            skillId: targetSkillId,
          });
          if (seq !== loadSeqRef.current) return;
          setFeedbackEligibility(detailResp.feedbackEligibility ?? null);
          setRevisions(detailResp.revisions ?? []);
        } catch {
          // Allow uninstalled target detail result
          if (seq !== loadSeqRef.current) return;
          setFeedbackEligibility(resp.feedbackEligibility ?? null);
          setRevisions(resp.revisions ?? []);
        }
      } else {
        setFeedbackEligibility(resp.feedbackEligibility ?? null);
        setRevisions(resp.revisions ?? []);
      }
    } catch (err) {
      if (seq !== loadSeqRef.current) return;
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (seq === loadSeqRef.current) {
        setLoading(false);
      }
    }
  }

  useEffect(() => {
    void loadData();
  }, [candidateId, conversationId, turnId, skillRefreshRevision]);

  // Escape key closes modal or cancels overwrite confirmation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (showOverwriteConfirm) {
          setShowOverwriteConfirm(false);
        } else {
          onClose();
        }
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose, showOverwriteConfirm]);

  async function handleSaveDraft() {
    if (!candidate) return;
    const trimmedName = editName.trim();
    if (!trimmedName) {
      setError("技能名称不能为空");
      return;
    }
    setActionBusy(true);
    setError(null);
    setCasConflict(false);
    try {
      const updated = await mutateSkillSuggestion<SkillCandidateAsset>({
        operation: "save",
        candidateId: candidate.candidateId,
        expectedRevision: candidate.revision,
        name: trimmedName,
        description: editDescription.trim(),
        bodyMarkdown: editBody,
      });
      setCandidate(updated);
      setIsEditing(false);
      onCandidateUpdated?.(updated);
    } catch (err) {
      if (isCasConflictError(err)) {
        setCasConflict(true);
      }
      setError(formatSafeSkillError(err instanceof Error ? err.message : String(err)));
    } finally {
      setActionBusy(false);
    }
  }

  async function handleApproveTrial(confirmedOverwrite = false) {
    if (!candidate) return;
    setActionBusy(true);
    setError(null);
    setCasConflict(false);
    try {
      let candidateToApprove = candidate;

      // Save-and-trial must save current form if editing then approve returned revision (not old content)
      const isFormDirty =
        editName.trim() !== candidate.name ||
        editDescription.trim() !== candidate.description ||
        editBody !== candidate.bodyMarkdown;

      if (isEditing || isFormDirty) {
        const trimmedName = editName.trim();
        if (!trimmedName) {
          setError("技能名称不能为空");
          setActionBusy(false);
          return;
        }
        const saved = await mutateSkillSuggestion<SkillCandidateAsset>({
          operation: "save",
          candidateId: candidate.candidateId,
          expectedRevision: candidate.revision,
          name: trimmedName,
          description: editDescription.trim(),
          bodyMarkdown: editBody,
        });
        candidateToApprove = saved;
        setCandidate(saved);
        onCandidateUpdated?.(saved);
      }

      // Existing target update requires explicit confirmation before overwrite; official/builtin backend refusal intact
      const targetExists = isExistingSkillTarget(candidateToApprove, feedbackEligibility, revisions);
      if (targetExists && !confirmedOverwrite) {
        setShowOverwriteConfirm(true);
        setActionBusy(false);
        return;
      }

      const approvePayload = resolveSkillApprovePayload(candidateToApprove, feedbackEligibility);
      const result = await mutateSkillSuggestion<{ candidate: SkillCandidateAsset }>({
        operation: "approve",
        candidateId: candidateToApprove.candidateId,
        expectedRevision: candidateToApprove.revision,
        ...approvePayload,
      });

      setShowOverwriteConfirm(false);
      setIsEditing(false);
      if (result.candidate) {
        setCandidate(result.candidate);
        onCandidateUpdated?.(result.candidate);
      }
      void loadData();
    } catch (err) {
      if (isCasConflictError(err)) {
        setCasConflict(true);
      }
      setError(formatSafeSkillError(err instanceof Error ? err.message : String(err)));
    } finally {
      setActionBusy(false);
    }
  }

  async function handleReject() {
    if (!candidate) return;
    setActionBusy(true);
    setError(null);
    setCasConflict(false);
    try {
      const updated = await mutateSkillSuggestion<SkillCandidateAsset>({
        operation: "reject",
        candidateId: candidate.candidateId,
        expectedRevision: candidate.revision,
      });
      setCandidate(updated);
      onCandidateUpdated?.(updated);
    } catch (err) {
      if (isCasConflictError(err)) {
        setCasConflict(true);
      }
      setError(formatSafeSkillError(err instanceof Error ? err.message : String(err)));
    } finally {
      setActionBusy(false);
    }
  }

  async function handleRegenerate() {
    if (!candidate) return;
    setActionBusy(true);
    setError(null);
    setCasConflict(false);
    try {
      const updated = await mutateSkillSuggestion<SkillCandidateAsset>({
        operation: "regenerate",
        candidateId: candidate.candidateId,
        expectedRevision: candidate.revision,
      });
      setCandidate(updated);
      onCandidateUpdated?.(updated);
    } catch (err) {
      if (isCasConflictError(err)) {
        setCasConflict(true);
      }
      setError(formatSafeSkillError(err instanceof Error ? err.message : String(err)));
    } finally {
      setActionBusy(false);
    }
  }

  async function handleFeedback(action: "retain" | "retire") {
    if (!feedbackEligibility) return;
    setActionBusy(true);
    setError(null);
    try {
      await mutateSkillSuggestion({
        operation: "feedback",
        skillId: feedbackEligibility.skillId,
        revisionId: feedbackEligibility.revisionId,
        expectedRevision: feedbackEligibility.revision,
        conversationId: feedbackEligibility.usage?.conversationId,
        sessionId: feedbackEligibility.usage?.sessionId,
        turnId: feedbackEligibility.usage?.turnId,
        action,
      });
      void loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setActionBusy(false);
    }
  }

  async function handleRestoreRevision(rev: SkillRevisionSnapshot) {
    if (!feedbackEligibility) return;
    setActionBusy(true);
    setError(null);
    try {
      await mutateSkillSuggestion({
        operation: "restore",
        skillId: feedbackEligibility.skillId,
        revisionId: rev.revisionId,
        expectedRevision: feedbackEligibility.revision,
        expectedCurrentContentHash: feedbackEligibility.currentContentHash,
      });
      void loadData();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setActionBusy(false);
    }
  }

  async function handleToggleAutoSuggestions() {
    setActionBusy(true);
    try {
      const result = await mutateSkillSuggestion<{ enabled: boolean }>({
        operation: "set_enabled",
        enabled: !autoEnabled,
      });
      setAutoEnabled(result.enabled);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setActionBusy(false);
    }
  }

  const isPendingReview = candidate?.state === "pending_review";
  const isApproved = candidate?.state === "approved";
  const isRejected = candidate?.state === "rejected";

  return (
    <div className="turn-modal-overlay" role="dialog" aria-modal="true" aria-label="技能建议审核与管理">
      <div className="turn-modal-dialog skill-modal">
        <header className="turn-modal-header">
          <div className="modal-title-group">
            <Icon name="agent" size={18} />
            <h3>小万技能建议与版本沉淀</h3>
            {candidate && (
              <span className={`status-pill state-${candidate.state}`}>
                {candidate.state === "pending_review" && "待审核"}
                {candidate.state === "approved" && "已审核启用 (试用中)"}
                {candidate.state === "rejected" && "已拒绝"}
                {candidate.state === "queued" && "排队提炼中"}
                {candidate.state === "generating" && "生成中"}
                {candidate.state === "generation_failed" && "提炼失败"}
                {candidate.state === "interrupted" && "已中断"}
                {candidate.state === "skipped" && `已跳过 (${candidate.skipReason ?? "条件不符"})`}
              </span>
            )}
          </div>
          <button type="button" className="turn-modal-close" onClick={onClose} aria-label="关闭">
            <Icon name="x" size={16} />
          </button>
        </header>

        <div className="skill-modal-body">
          {error && (
            <div className="skill-error-alert" role="alert">
              <span>{error}</span>
            </div>
          )}

          {casConflict && (
            <div className="skill-config-strip" role="alert" style={{ borderColor: "var(--danger)" }}>
              <div className="strip-info">
                <strong style={{ color: "var(--danger)" }}>版本冲突警告</strong>
                <small>远端技能已被其他操作更新。本地编辑已完整保留，请核对或重新加载最新内容。</small>
              </div>
              <button
                type="button"
                className="turn-secondary-btn"
                onClick={() => void loadData({ forceOverwriteDraft: true })}
                disabled={actionBusy}
              >
                放弃本地修改并重新加载
              </button>
            </div>
          )}

          {/* 自动建议开关 */}
          <div className="skill-config-strip">
            <div className="strip-info">
              <strong>自动技能建议</strong>
              <small>小万在高质量完成任务后自动分析提炼候选技能，审核通过后方可生效。</small>
            </div>
            <button
              type="button"
              className={`skill-toggle-switch ${autoEnabled ? "on" : "off"}`}
              onClick={() => void handleToggleAutoSuggestions()}
              disabled={actionBusy}
            >
              {autoEnabled ? "已开启" : "已关闭"}
            </button>
          </div>

          {loading && !candidate && (
            <div className="skill-loading-state">
              <span>正在获取技能候选资产...</span>
            </div>
          )}

          {candidate && (
            <div className="candidate-content-card">
              {/* 元数据与数据透明度展示 */}
              <div className="candidate-meta-bar">
                <span>修订版本: <code>rev #{candidate.revision}</code></span>
                {candidate.targetSkillId && (
                  <span>目标技能 ID: <code>{candidate.targetSkillId}</code></span>
                )}
                {candidate.dataCategories && candidate.dataCategories.length > 0 && (
                  <span className="data-categories-tag" title={candidate.dataCategories.join(", ")}>
                    数据透明: 仅使用任务描述与工具名 ({candidate.dataCategories.join(", ")})
                  </span>
                )}
              </div>

              {candidate.state === "generation_failed" && (
                <div className="candidate-desc" role="note" style={{ color: "var(--danger)", marginBottom: "10px" }}>
                  <strong>提炼失败原因: </strong>
                  <span>{candidate.failureReason || "生成未能符合技能规范或上下文解析失败"}</span>
                </div>
              )}

              {candidate.state === "skipped" && (
                <div className="candidate-desc" role="note" style={{ color: "var(--subtle)", marginBottom: "10px" }}>
                  <strong>跳过提炼说明: </strong>
                  <span>{formatSkipReason(candidate.skipReason)}</span>
                </div>
              )}

              {/* 编辑模式 vs 预览模式 */}
              {isEditing ? (
                <div className="candidate-edit-form">
                  <div className="form-group">
                    <label>技能名称 (英文短横线命名)</label>
                    <input
                      type="text"
                      className="skill-input"
                      value={editName}
                      onChange={(e) => setEditName(e.target.value)}
                    />
                  </div>
                  <div className="form-group">
                    <label>简述 (≤240 字)</label>
                    <input
                      type="text"
                      className="skill-input"
                      value={editDescription}
                      onChange={(e) => setEditDescription(e.target.value)}
                    />
                  </div>
                  <div className="form-group">
                    <label>SKILL.md 正文 Markdown (含适用条件、步骤与退出规则)</label>
                    <textarea
                      className="skill-textarea"
                      rows={10}
                      value={editBody}
                      onChange={(e) => setEditBody(e.target.value)}
                    />
                  </div>
                </div>
              ) : (
                <div className="candidate-preview-view">
                  <div className="candidate-preview-header">
                    <h4>{candidate.name}</h4>
                    <p className="candidate-desc">{candidate.description}</p>
                  </div>
                  <pre className="candidate-markdown-preview">
                    {candidate.bodyMarkdown}
                  </pre>
                </div>
              )}
            </div>
          )}

          {/* 试用反馈区 (仅当后续独立 Turn 实际使用该版本时呈现) */}
          {feedbackEligibility && feedbackEligibility.eligible && (
            <div className="skill-feedback-card">
              <header className="feedback-card-header">
                <strong>后续任务试用反馈</strong>
                <span className="feedback-hint">
                  检测到本技能在后续任务中被实际调用，请评估执行效果：
                </span>
              </header>
              <div className="feedback-actions">
                <button
                  type="button"
                  className="feedback-btn retain"
                  disabled={actionBusy}
                  onClick={() => void handleFeedback("retain")}
                >
                  有效，保留为已验证技能
                </button>
                <button
                  type="button"
                  className="feedback-btn retire"
                  disabled={actionBusy}
                  onClick={() => void handleFeedback("retire")}
                >
                  无效，停用并退役
                </button>
              </div>
            </div>
          )}

          {/* 历史版本与恢复抽屉 */}
          {revisions && revisions.length > 0 && (
            <div className="skill-revisions-section">
              <header className="revisions-header">
                <strong>版本历史记录 ({revisions.length})</strong>
              </header>
              <div className="revisions-list">
                {revisions.map((rev) => (
                  <div className="revision-item" key={rev.revisionId}>
                    <div className="revision-info">
                      <span className="revision-hash"><code>{rev.revisionId.slice(0, 10)}</code></span>
                      <strong className="revision-name">{rev.name}</strong>
                      <span className="revision-desc">{rev.description}</span>
                    </div>
                    <div className="revision-actions">
                      {rev.canRestore && !rev.wasManuallyDisabled ? (
                        <button
                          type="button"
                          className="restore-rev-btn"
                          disabled={actionBusy}
                          onClick={() => void handleRestoreRevision(rev)}
                        >
                          恢复此版本
                        </button>
                      ) : (
                        <span className="rev-disabled-tag">已手动停用</span>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <footer className="turn-modal-footer skill-footer">
          <div className="skill-footer-left">
            {candidate && (isRejected || candidate.state === "generation_failed" || candidate.state === "interrupted") && (
              <button
                type="button"
                className="turn-secondary-btn"
                disabled={actionBusy}
                onClick={() => void handleRegenerate()}
              >
                重新生成
              </button>
            )}
            {candidate && isPendingReview && (
              <button
                type="button"
                className="turn-danger-btn"
                disabled={actionBusy}
                onClick={() => void handleReject()}
              >
                拒绝
              </button>
            )}
          </div>

          <div className="skill-footer-right">
            {showOverwriteConfirm ? (
              <div className="discard-confirm-group">
                <span className="discard-confirm-text">目标技能已存在，确认覆盖现有版本并试用？</span>
                <button
                  type="button"
                  className="turn-confirm-discard-btn"
                  onClick={() => void handleApproveTrial(true)}
                  disabled={actionBusy}
                >
                  确认覆盖并试用
                </button>
                <button
                  type="button"
                  className="turn-cancel-discard-btn"
                  onClick={() => setShowOverwriteConfirm(false)}
                  disabled={actionBusy}
                >
                  取消
                </button>
              </div>
            ) : (
              <>
                <button type="button" className="turn-secondary-btn" onClick={onClose}>
                  关闭
                </button>
                {candidate && isPendingReview && (
                  <>
                    {isEditing ? (
                      <>
                        <button
                          type="button"
                          className="turn-secondary-btn"
                          onClick={() => setIsEditing(false)}
                          disabled={actionBusy}
                        >
                          取消编辑
                        </button>
                        <button
                          type="button"
                          className="turn-secondary-btn"
                          onClick={() => void handleSaveDraft()}
                          disabled={actionBusy}
                        >
                          保存草稿
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        className="turn-secondary-btn"
                        onClick={() => setIsEditing(true)}
                        disabled={actionBusy}
                      >
                        编辑 Markdown
                      </button>
                    )}
                    <button
                      type="button"
                      className="turn-primary-btn"
                      onClick={() => void handleApproveTrial(false)}
                      disabled={actionBusy}
                    >
                      保存并试用
                    </button>
                  </>
                )}
              </>
            )}
          </div>
        </footer>
      </div>
    </div>
  );
}
