import { useEffect, useState } from "react";
import { getSkillSuggestions, workspaceDownloadUrl } from "../api";
import { formatBytes } from "../format";
import type { ChatMessage, SkillCandidateAsset } from "../types";
import { Icon } from "./Icon";
import {
  resolveTurnArtifacts,
  resolveTurnIdentity,
  resolveTurnLiveScene,
  resolveTurnSkillSuggestion,
  resolveTurnWorkspaceChanges,
  type TurnArtifactItem,
  type TurnLiveSceneTarget,
  type TurnSkillSuggestion,
  type TurnWorkspaceChangesSummary,
} from "../turnResultActions";

export {
  resolveTurnArtifacts,
  resolveTurnIdentity,
  resolveTurnLiveScene,
  resolveTurnSkillSuggestion,
  resolveTurnWorkspaceChanges,
  type TurnArtifactItem,
  type TurnLiveSceneTarget,
  type TurnSkillSuggestion,
  type TurnWorkspaceChangesSummary,
};

export interface TurnResultActionsProps {
  messages: ChatMessage[];
  isRunning?: boolean;
  conversationId?: number;
  turnId?: string;
  skillRefreshRevision?: number;
  onOpenArtifact?: (artifact: TurnArtifactItem) => void;
  onViewChanges?: (changes: TurnWorkspaceChangesSummary) => void;
  onViewSkillSuggestion?: (suggestion: TurnSkillSuggestion) => void;
}

export function TurnResultActions({
  messages,
  isRunning = false,
  conversationId,
  turnId,
  skillRefreshRevision = 0,
  onOpenArtifact,
  onViewChanges,
  onViewSkillSuggestion,
}: TurnResultActionsProps) {
  // 严格遵守 React Rules of Hooks：所有 hooks 必须在顶层无条件声明，严禁在 hooks 之前 early return
  const [skillCandidate, setSkillCandidate] = useState<SkillCandidateAsset | null>(null);
  const [skillLoading, setSkillLoading] = useState(false);
  const [artifactPickerOpen, setArtifactPickerOpen] = useState(false);
  const [liveSceneModalOpen, setLiveSceneModalOpen] = useState(false);
  const [artifactError, setArtifactError] = useState<string | null>(null);

  const resolvedIds = resolveTurnIdentity(messages);
  const effectiveTurnId = turnId || resolvedIds.turnId;
  const effectiveConvId = conversationId || resolvedIds.conversationId;

  // 从真实 asset store 异步查询本 Turn 提炼的技能候选 (订阅 SSE skill_assets_changed 透传的 revision)
  useEffect(() => {
    if (isRunning || !effectiveConvId || !effectiveTurnId) {
      setSkillCandidate(null);
      setSkillLoading(false);
      return;
    }

    let cancelled = false;
    setSkillCandidate(null);
    setSkillLoading(true);

    getSkillSuggestions({ conversationId: effectiveConvId, turnId: effectiveTurnId })
      .then((resp) => {
        if (cancelled) return;
        if (resp.items && resp.items.length > 0) {
          setSkillCandidate(resp.items[0]);
        } else {
          setSkillCandidate(null);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setSkillCandidate(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setSkillLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [effectiveConvId, effectiveTurnId, isRunning, skillRefreshRevision]);

  const artifacts = resolveTurnArtifacts(messages);
  const changes = resolveTurnWorkspaceChanges(messages);
  const liveScene = resolveTurnLiveScene(messages);

  // 仅以资产库真实状态为准，不将 HTTP 查询中呈现为生成中，不依据其他 Agent 卡片推断技能
  const activeCandidate = skillCandidate;
  const hasSkillAction = activeCandidate !== null;
  const hasAnyAction =
    artifacts.length > 0 ||
    changes !== null ||
    liveScene !== null ||
    hasSkillAction;
  // 严格在所有 Hooks 无条件执行完毕后，再进行渲染前短路检查
  if (isRunning) return null;
  if (!hasAnyAction) return null;

  async function handleArtifactClick(item: TurnArtifactItem) {
    setArtifactError(null);
    if (!item.path && !item.uri) {
      setArtifactError("文件已不可用");
      return;
    }
    if (item.path) {
      try {
        const downloadUrl = workspaceDownloadUrl(item.path);
        const resp = await fetch(downloadUrl, { method: "HEAD" });
        if (!resp.ok && resp.status !== 405) {
          setArtifactError(`文件已不可用 (${item.title})`);
          return;
        }
      } catch {
        // 网络异常时不阻断原有回调
      }
    }

    if (onOpenArtifact) {
      onOpenArtifact(item);
    } else if (item.path) {
      window.open(workspaceDownloadUrl(item.path), "_blank");
    } else if (item.uri) {
      window.open(item.uri, "_blank");
    }
  }

  function handleOpenArtifacts() {
    setArtifactError(null);
    if (artifacts.length > 1) {
      setArtifactPickerOpen(true);
    } else if (artifacts.length === 1) {
      void handleArtifactClick(artifacts[0]);
    }
  }

  return (
    <div className="turn-result-actions" role="region" aria-label="任务结果动作">
      {artifactError && (
        <div className="turn-result-error-banner" role="alert">
          <span>{artifactError}</span>
          <button type="button" onClick={() => setArtifactError(null)} aria-label="关闭提示">
            <Icon name="x" size={14} />
          </button>
        </div>
      )}

      <div className="turn-result-buttons">
        {/* 1. 打开产物 (若存在真实产物) */}
        {artifacts.length > 0 && (
          <button
            className="turn-result-btn artifacts-btn"
            type="button"
            onClick={handleOpenArtifacts}
            title={artifacts.length > 1 ? `选择并查看 ${artifacts.length} 个产物` : "打开本次产物"}
          >
            <Icon name="file" size={15} />
            <span>打开产物{artifacts.length > 1 ? ` (${artifacts.length})` : ""}</span>
          </button>
        )}

        {/* 2. 查看变更 (若存在真实变更记录或 Diff) */}
        {changes !== null && (
          <button
            className="turn-result-btn changes-btn"
            type="button"
            onClick={() => onViewChanges?.(changes)}
            title="查看文件变更与 Diff"
          >
            <Icon name="workspace" size={15} />
            <span>查看变更</span>
          </button>
        )}

        {/* 3. 查看现场 (若包含虚拟屏/显示交互) */}
        {liveScene !== null && (
          <button
            className="turn-result-btn live-scene-btn"
            type="button"
            onClick={() => setLiveSceneModalOpen(true)}
            title="查看任务现场"
          >
            <Icon name="browser" size={15} />
            <span>查看现场</span>
          </button>
        )}

        {/* 4. 技能建议 (按真实 asset store 证明状态呈现：排队 / 生成中 / 待审 / 失败 / 已启用) */}
        {activeCandidate && (
          <button
            className={`turn-result-btn skill-btn state-${activeCandidate.state}`}
            type="button"
            onClick={() => onViewSkillSuggestion?.({
              id: activeCandidate.targetSkillId || activeCandidate.name,
              name: activeCandidate.name,
              candidateId: activeCandidate.candidateId,
              revision: activeCandidate.revision,
              description: activeCandidate.description,
              bodyMarkdown: activeCandidate.bodyMarkdown,
              verificationStatus: activeCandidate.state === "approved" ? "trial" : undefined,
              turnId: effectiveTurnId,
              conversationId: effectiveConvId,
              targetSkillId: activeCandidate.targetSkillId,
              state: activeCandidate.state,
            })}
            title={
              activeCandidate.state === "pending_review"
                ? "有待审核的技能候选，点击查看与编辑"
                : activeCandidate.state === "generating"
                ? "技能建议生成中"
                : activeCandidate.state === "queued"
                ? "技能建议排队提炼中"
                : activeCandidate.state === "generation_failed"
                ? "技能建议生成失败，点击查看原因"
                : activeCandidate.state === "skipped"
                ? `技能建议已跳过 (${activeCandidate.skipReason ?? "条件不符"})`
                : activeCandidate.state === "interrupted"
                ? "技能建议已中断"
                : "查看技能建议"
            }
          >
            <Icon name="agent" size={15} />
            <span>
              技能建议
              {activeCandidate.state === "pending_review" && " (待审核)"}
              {activeCandidate.state === "generating" && " (生成中)"}
              {activeCandidate.state === "queued" && " (排队中)"}
              {activeCandidate.state === "generation_failed" && " (失败)"}
              {activeCandidate.state === "approved" && " (已启用)"}
              {activeCandidate.state === "skipped" && " (已跳过)"}
              {activeCandidate.state === "interrupted" && " (已中断)"}
            </span>
          </button>
        )}
      </div>

      {/* 多产物选择器弹窗 */}
      {artifactPickerOpen && (
        <div className="turn-modal-overlay" role="dialog" aria-modal="true" aria-label="选择产物">
          <div className="turn-modal-dialog">
            <header className="turn-modal-header">
              <h3>任务产生的文件 ({artifacts.length})</h3>
              <button
                type="button"
                className="turn-modal-close"
                onClick={() => setArtifactPickerOpen(false)}
                aria-label="关闭"
              >
                <Icon name="x" size={16} />
              </button>
            </header>
            <div className="turn-artifact-list">
              {artifacts.map((art, idx) => (
                <button
                  key={`${art.path || art.uri}-${idx}`}
                  type="button"
                  className="turn-artifact-item"
                  onClick={() => {
                    setArtifactPickerOpen(false);
                    void handleArtifactClick(art);
                  }}
                >
                  <Icon name="file" size={16} />
                  <div className="turn-artifact-info">
                    <strong className="turn-artifact-title">{art.title}</strong>
                    <span className="turn-artifact-sub">
                      {art.size !== undefined ? formatBytes(art.size) : (art.path || art.uri || "")}
                    </span>
                  </div>
                  <Icon name="download" size={14} />
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* 虚拟屏/现场提示弹窗：手机原生虚拟屏在 Web 端明确提示 */}
      {liveSceneModalOpen && liveScene !== null && (
        <div className="turn-modal-overlay" role="dialog" aria-modal="true" aria-label="查看现场提示">
          <div className="turn-modal-dialog compact">
            <header className="turn-modal-header">
              <h3>查看现场</h3>
              <button
                type="button"
                className="turn-modal-close"
                onClick={() => setLiveSceneModalOpen(false)}
                aria-label="关闭"
              >
                <Icon name="x" size={16} />
              </button>
            </header>
            <div className="turn-live-scene-body">
              <p className="turn-live-scene-notice">
                请在手机查看：虚拟屏在手机运行，Web 端不提供远程控制通道。
              </p>
              {liveScene.displayId > 0 && (
                <div className="turn-live-scene-meta">
                  <span>虚拟屏 Display ID: <code>{liveScene.displayId}</code></span>
                  {liveScene.turnId ? <span>轮次: <code>{liveScene.turnId}</code></span> : null}
                </div>
              )}
            </div>
            <footer className="turn-modal-footer">
              <button
                type="button"
                className="turn-primary-btn"
                onClick={() => setLiveSceneModalOpen(false)}
              >
                我知道了
              </button>
            </footer>
          </div>
        </div>
      )}
    </div>
  );
}
