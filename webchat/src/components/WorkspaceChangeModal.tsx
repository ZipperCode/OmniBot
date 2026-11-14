import { useEffect, useState } from "react";
import {
  discardWorkspaceChange,
  getWorkspaceChangePreview,
  restoreWorkspaceChange,
} from "../api";
import { formatBytes } from "../format";
import type { WorkspaceChangeRecord } from "../types";
import {
  classifyWorkspaceChangeRestoreError,
  classifyWorkspaceChangeRestoreStatus,
  isRestoreEligible,
  resolveWorkspaceChangeHashes,
} from "../workspaceChanges";
import { Icon } from "./Icon";

export interface WorkspaceChangeModalProps {
  changeId?: string;
  diffText?: string;
  conversationId?: number;
  workspaceId?: string;
  onClose: () => void;
  onRestored?: (change: WorkspaceChangeRecord) => void;
}

export function WorkspaceChangeModal({
  changeId,
  diffText: initialDiffText,
  conversationId,
  workspaceId,
  onClose,
  onRestored,
}: WorkspaceChangeModalProps) {
  const [record, setRecord] = useState<WorkspaceChangeRecord | null>(null);
  const [loading, setLoading] = useState(Boolean(changeId));
  const [error, setError] = useState<string | null>(null);
  const [conflictDiff, setConflictDiff] = useState<string | null>(null);
  const [isRestoring, setIsRestoring] = useState(false);
  const [isDiscarding, setIsDiscarding] = useState(false);
  const [showDiscardConfirm, setShowDiscardConfirm] = useState(false);

  useEffect(() => {
    if (!changeId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);

    const query = (conversationId || workspaceId) ? { conversationId, workspaceId } : undefined;
    getWorkspaceChangePreview(changeId, query)
      .then((data) => {
        if (!cancelled) {
          if (conversationId && data.conversationId && data.conversationId !== conversationId) {
            setError(`安全拦截：该变更属于会话 #${data.conversationId}，当前会话无权操作。`);
            setLoading(false);
            return;
          }
          setRecord(data);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : String(err));
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [changeId, conversationId, workspaceId]);

  const activeDiff = record?.diffText ?? initialDiffText ?? "";
  const isReady = record ? record.snapshotStatus === "ready" : false;
  const isRestored = record?.snapshotStatus === "restored";
  const isConflict = record?.snapshotStatus === "conflict" || Boolean(conflictDiff);
  const canRestoreNow = isRestoreEligible(record);

  async function handleConfirmRestore() {
    if (!record) return;
    const expectedHashes = resolveWorkspaceChangeHashes(record);
    if (!expectedHashes) {
      setError("缺少服务端下发的恢复哈希凭据，无法恢复。");
      return;
    }

    setIsRestoring(true);
    setError(null);
    setConflictDiff(null);

    try {
      const result = await restoreWorkspaceChange(
        record.changeId,
        record.revision,
        expectedHashes,
      );
      const statusInfo = classifyWorkspaceChangeRestoreStatus(result);
      if (statusInfo.isSuccess) {
        const nextRecord: WorkspaceChangeRecord = {
          ...record,
          snapshotStatus: "restored",
          canRestore: false,
          revision: typeof result.revision === "number" ? result.revision : record.revision + 1,
        };
        setRecord(nextRecord);
        onRestored?.(nextRecord);
      } else {
        setError(statusInfo.userMessage);
        if (statusInfo.conflictDiffRequired) {
          setConflictDiff(activeDiff);
        }
      }
    } catch (err) {
      const errorInfo = classifyWorkspaceChangeRestoreError(err, activeDiff);
      setError(errorInfo.userMessage);
      if (errorInfo.isConflict && errorInfo.conflictDiff) {
        setConflictDiff(errorInfo.conflictDiff);
      }
    } finally {
      setIsRestoring(false);
    }
  }

  async function handleDiscardSnapshot() {
    if (!record) return;
    setIsDiscarding(true);
    setError(null);
    try {
      const result = await discardWorkspaceChange(record.changeId, record.revision);
      if (result.discarded) {
        if (typeof result.revision === "number") {
          const nextStatus = (result.snapshotStatus as WorkspaceChangeRecord["snapshotStatus"]) || "unavailable";
          setRecord((prev) => (prev ? {
            ...prev,
            snapshotStatus: nextStatus,
            unavailableReason: "discarded",
            canRestore: false,
            revision: result.revision!,
          } : null));
        } else {
          const query = (conversationId || workspaceId) ? { conversationId, workspaceId } : undefined;
          const refreshed = await getWorkspaceChangePreview(
            record.changeId,
            query,
          );
          setRecord(refreshed);
        }
        setShowDiscardConfirm(false);
      } else {
        setError("清除快照未完成");
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setIsDiscarding(false);
    }
  }

  return (
    <div className="turn-modal-overlay" role="dialog" aria-modal="true" aria-label="工作区受管变更检查">
      <div className="turn-modal-dialog change-modal">
        <header className="turn-modal-header">
          <div className="modal-title-group">
            <Icon name="workspace" size={18} />
            <h3>受管工作区变更检查</h3>
            {record && (
              <span className={`status-pill ${record.snapshotStatus}`}>
                {record.snapshotStatus === "ready" && "快照就绪"}
                {record.snapshotStatus === "restored" && "已恢复"}
                {record.snapshotStatus === "conflict" && "存在冲突"}
                {record.snapshotStatus === "unavailable" && "快照不可用"}
              </span>
            )}
          </div>
          <button type="button" className="turn-modal-close" onClick={onClose} aria-label="关闭">
            <Icon name="x" size={16} />
          </button>
        </header>

        <div className="change-modal-body">
          {/* 规范警告标语 */}
          <div className="scope-warning-banner" role="alert">
            <Icon name="file" size={14} />
            <span>仅恢复这些文件；不撤销命令、消息或模块部署。</span>
          </div>

          {error && (
            <div className="change-error-alert" role="alert">
              <span>{error}</span>
            </div>
          )}

          {loading && (
            <div className="change-loading-state">
              <span>正在获取工作区变更快照与 Diff...</span>
            </div>
          )}

          {/* 文件列表 */}
          {record && (
            <div className="change-meta-card">
              <div className="change-meta-row">
                <span>操作类型: <strong>{record.operation}</strong></span>
                <span>主目标路径: <code>{record.targetRelativePath || record.targetPath || record.relativePath || "工作区"}</code></span>
                {record.snapshotBytes !== undefined && (
                  <span>快照大小: <strong>{formatBytes(record.snapshotBytes)}</strong></span>
                )}
              </div>
              {record.files && record.files.length > 0 && (
                <div className="change-files-list">
                  <strong>受影响的文件列表 ({record.files.length}):</strong>
                  <ul>
                    {record.files.map((file, idx) => (
                      <li key={`${file.path}-${idx}`}>
                        <span className={`op-tag ${file.operation}`}>{file.operation}</span>
                        <code>{file.path}</code>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}

          {/* Diff 展示 */}
          <div className="change-diff-section">
            <header className="diff-header">
              <strong>Unified Diff</strong>
              {activeDiff && <span className="diff-hint">带上下文的单变化区</span>}
            </header>
            {activeDiff ? (
              <pre className="change-diff-viewer">
                {activeDiff.split("\n").map((line, idx) => {
                  let lineClass = "diff-line";
                  if (line.startsWith("+") && !line.startsWith("+++")) lineClass += " add";
                  else if (line.startsWith("-") && !line.startsWith("---")) lineClass += " del";
                  else if (line.startsWith("@@")) lineClass += " hunk";
                  return (
                    <div key={idx} className={lineClass}>
                      {line || " "}
                    </div>
                  );
                })}
              </pre>
            ) : (
              <div className="empty-diff-state">(暂无文本 Diff 或仅二进制变化)</div>
            )}
          </div>

          {/* 冲突信息展示 */}
          {isConflict && (
            <div className="change-conflict-card" role="alert">
              <strong>工作区冲突说明</strong>
              <p>
                文件在快照生成后已被外部程序或用户再次修改。系统已拦截此次恢复请求，避免覆盖未受管的本地代码。
              </p>
            </div>
          )}
        </div>

        <footer className="turn-modal-footer change-footer">
          <div className="change-footer-left">
            {record && isReady && !showDiscardConfirm && (
              <button
                type="button"
                className="turn-discard-btn"
                onClick={() => setShowDiscardConfirm(true)}
                disabled={isDiscarding || isRestoring}
              >
                清除恢复快照
              </button>
            )}
            {showDiscardConfirm && (
              <div className="discard-confirm-group">
                <span className="discard-confirm-text">确定清除快照？不改变当前文件</span>
                <button
                  type="button"
                  className="turn-confirm-discard-btn"
                  onClick={() => void handleDiscardSnapshot()}
                  disabled={isDiscarding}
                >
                  确认清除
                </button>
                <button
                  type="button"
                  className="turn-cancel-discard-btn"
                  onClick={() => setShowDiscardConfirm(false)}
                >
                  取消
                </button>
              </div>
            )}
          </div>

          <div className="change-footer-right">
            <button type="button" className="turn-secondary-btn" onClick={onClose}>
              关闭
            </button>
            {canRestoreNow && (
              <button
                type="button"
                className="turn-primary-btn"
                onClick={() => void handleConfirmRestore()}
                disabled={isRestoring || isConflict}
                title={isConflict ? "冲突状态禁止恢复" : "恢复这些文件到修改前快照"}
              >
                {isRestoring ? "正在恢复..." : "确认恢复"}
              </button>
            )}
          </div>
        </footer>
      </div>
    </div>
  );
}
