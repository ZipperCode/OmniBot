import { useEffect, useState } from "react";
import {
  getConversationWorkspaceChanges,
  getWorkspaceChangesByWorkspace,
} from "../api";
import { relativeDate } from "../format";
import type { WorkspaceChangeRecord } from "../types";
import { Icon } from "./Icon";

export interface WorkspaceChangeListModalProps {
  conversationId?: number;
  workspaceId?: string;
  onClose: () => void;
  onSelectChange: (change: WorkspaceChangeRecord) => void;
}

export function WorkspaceChangeListModal({
  conversationId,
  workspaceId = "conversation_default",
  onClose,
  onSelectChange,
}: WorkspaceChangeListModalProps) {
  const [changes, setChanges] = useState<WorkspaceChangeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    const promise = conversationId && conversationId > 0
      ? getConversationWorkspaceChanges(conversationId)
      : getWorkspaceChangesByWorkspace(workspaceId);

    promise
      .then((data) => {
        if (!cancelled) {
          setChanges(Array.isArray(data) ? data : []);
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
  }, [conversationId, workspaceId]);

  return (
    <div className="turn-modal-overlay" role="dialog" aria-modal="true" aria-label="工作区变更管理列表">
      <div className="turn-modal-dialog change-modal">
        <header className="turn-modal-header">
          <div className="modal-title-group">
            <Icon name="workspace" size={18} />
            <h3>受管工作区变更历史</h3>
            <span className="status-pill ready">{changes.length} 项记录</span>
          </div>
          <button type="button" className="turn-modal-close" onClick={onClose} aria-label="关闭">
            <Icon name="x" size={16} />
          </button>
        </header>

        <div className="change-modal-body">
          <div className="scope-warning-banner" role="alert">
            <Icon name="file" size={14} />
            <span>受管文件快照按工作区独立保留；对话删除后仍可在此检查与清理。</span>
          </div>

          {error && (
            <div className="change-error-alert" role="alert">
              <span>{error}</span>
            </div>
          )}

          {loading && (
            <div className="change-loading-state">
              <span>正在获取工作区变更历史...</span>
            </div>
          )}

          {!loading && changes.length === 0 && !error && (
            <div className="list-empty" style={{ padding: "32px 0", textAlign: "center" }}>
              当前工作区暂无受管变更快照
            </div>
          )}

          <div className="change-history-list">
            {changes.map((item) => (
              <button
                key={item.changeId}
                type="button"
                className="change-history-item"
                onClick={() => {
                  onSelectChange(item);
                }}
              >
                <div className="change-item-left">
                  <span className={`op-tag ${item.operation}`}>{item.operation}</span>
                  <div className="change-item-paths">
                    <strong className="change-item-target">
                      {item.targetRelativePath || item.targetPath || item.relativePath || "未指定路径"}
                    </strong>
                    {item.sourceRelativePath && item.sourceRelativePath !== item.targetRelativePath && (
                      <span className="change-item-source">源自: {item.sourceRelativePath}</span>
                    )}
                  </div>
                </div>

                <div className="change-item-right">
                  <span className={`status-pill ${item.snapshotStatus}`}>
                    {item.snapshotStatus === "ready" && "就绪"}
                    {item.snapshotStatus === "restored" && "已恢复"}
                    {item.snapshotStatus === "conflict" && "冲突"}
                    {item.snapshotStatus === "unavailable" && "不可用"}
                  </span>
                  <span className="change-item-time">{relativeDate(item.createdAt)}</span>
                  <Icon name="chevron-left" size={14} style={{ transform: "rotate(180deg)" }} />
                </div>
              </button>
            ))}
          </div>
        </div>

        <footer className="turn-modal-footer">
          <button type="button" className="turn-secondary-btn" onClick={onClose}>
            关闭
          </button>
        </footer>
      </div>
    </div>
  );
}
