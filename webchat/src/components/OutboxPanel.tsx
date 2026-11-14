import {
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from "react";
import { workspaceDownloadUrl } from "../api";
import { fileToAttachment, formatBytes } from "../format";
import {
  formatOutboxError,
  isItemModifiable,
  outboxItemAttachments,
  outboxItemText,
  outboxStateLabel,
  sortOutboxItemsFifo,
} from "../outbox";
import type { Attachment, OutboxItem, OutboxSnapshot } from "../types";
import { ComposerAttachmentIcon } from "./ComposerIcons";
import { Icon } from "./Icon";

export interface OutboxPanelProps {
  snapshot: OutboxSnapshot | null;
  error?: string | null;
  isAgentRunActive?: boolean;
  activeSessionId?: string | null;
  activeTurnId?: string | null;
  onClearError?: () => void;
  onUpdateItem: (
    id: string,
    text: string,
    attachments: Attachment[],
    expectedRevision?: number,
    conversationId?: number,
  ) => Promise<boolean>;
  onRemoveItem: (id: string) => Promise<boolean>;
  onResumeItem: (id: string) => Promise<boolean>;
  onStopAndSendItem: (id: string) => Promise<boolean>;
}

function formatItemTime(timestamp: number): string {
  if (!timestamp) return "";
  const date = new Date(timestamp);
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  const seconds = String(date.getSeconds()).padStart(2, "0");
  return `${hours}:${minutes}:${seconds}`;
}

export function OutboxPanel({
  snapshot,
  error,
  isAgentRunActive = false,
  activeSessionId = null,
  activeTurnId = null,
  onClearError,
  onUpdateItem,
  onRemoveItem,
  onResumeItem,
  onStopAndSendItem,
}: OutboxPanelProps) {
  const [expanded, setExpanded] = useState(false);
  const [expandedDetails, setExpandedDetails] = useState<Record<string, boolean>>({});

  // 待发项独立编辑状态（固定打开时的 conversationId 与 revision，不污染主输入框）
  const [editingTarget, setEditingTarget] = useState<{
    item: OutboxItem;
    openedRevision: number;
    conversationId: number;
  } | null>(null);
  const [editText, setEditText] = useState("");
  const [editAttachments, setEditAttachments] = useState<Attachment[]>([]);
  const [editSaving, setEditSaving] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);
  // Per-item action busy tracking
  const [busyActionItemId, setBusyActionItemId] = useState<string | null>(null);
  const editAttachmentInputRef = useRef<HTMLInputElement>(null);

  const items = useMemo(() => {
    return sortOutboxItemsFifo(snapshot?.items ?? []);
  }, [snapshot?.items]);

  const activeCount = useMemo(() => {
    return items.filter((item) => item.state !== "submitted").length;
  }, [items]);

  const pausedCount = useMemo(() => {
    return items.filter((item) => item.state === "paused").length;
  }, [items]);

  const dispatchingCount = useMemo(() => {
    return items.filter((item) => item.state === "dispatching").length;
  }, [items]);

  // 已提交收据不显示“待发 0 条”；若无未投递项且无错误及编辑，不渲染面板
  if (activeCount === 0 && !error && !editingTarget) {
    return null;
  }

  function toggleDetail(id: string) {
    setExpandedDetails((prev) => ({
      ...prev,
      [id]: !prev[id],
    }));
  }

  function startEditing(item: OutboxItem) {
    setEditingTarget({
      item,
      openedRevision: item.revision,
      conversationId: item.conversationId,
    });
    setEditText(outboxItemText(item));
    setEditAttachments([...outboxItemAttachments(item)]);
    setEditError(null);
  }

  function cancelEditing() {
    setEditingTarget(null);
    setEditText("");
    setEditAttachments([]);
    setEditError(null);
  }

  async function handleAddEditAttachments(event: ChangeEvent<HTMLInputElement>) {
    const files = [...(event.target.files ?? [])];
    event.target.value = "";
    if (!files.length) return;
    try {
      const added = await Promise.all(files.map(fileToAttachment));
      setEditAttachments((prev) => [...prev, ...added]);
    } catch (err) {
      setEditError(formatOutboxError(err));
    }
  }

  async function handleSaveEdit() {
    if (!editingTarget) return;
    setEditSaving(true);
    setEditError(null);
    try {
      const success = await onUpdateItem(
        editingTarget.item.id,
        editText.trim(),
        editAttachments,
        editingTarget.openedRevision,
        editingTarget.conversationId,
      );
      if (success) {
        setEditingTarget(null);
        setEditText("");
        setEditAttachments([]);
      } else {
        // 更新失败（例如版本冲突）：保留当前编辑内容与弹窗
        setEditError("更新失败，已保留当前修改");
      }
    } catch (err) {
      setEditError(formatOutboxError(err));
    } finally {
      setEditSaving(false);
    }
  }

  async function handleRemove(item: OutboxItem) {
    // 移除前先要求确认，防止误触
    if (!window.confirm("确定要从待发箱移除此项吗？")) {
      return;
    }
    setBusyActionItemId(item.id);
    try {
      await onRemoveItem(item.id);
    } finally {
      setBusyActionItemId(null);
    }
  }

  async function handleResume(item: OutboxItem) {
    setBusyActionItemId(item.id);
    try {
      await onResumeItem(item.id);
    } finally {
      setBusyActionItemId(null);
    }
  }

  async function handleStopAndSend(item: OutboxItem) {
    setBusyActionItemId(item.id);
    try {
      await onStopAndSendItem(item.id);
    } finally {
      setBusyActionItemId(null);
    }
  }

  return (
    <aside className="outbox-panel" aria-label="待发箱队列">
      {error && (
        <div className="outbox-error-banner" role="alert">
          <span className="outbox-error-text">{error}</span>
          {onClearError && (
            <button
              className="outbox-icon-button"
              type="button"
              aria-label="关闭错误提示"
              title="关闭错误提示"
              onClick={onClearError}
            >
              <Icon name="x" size={14} />
            </button>
          )}
        </div>
      )}

      {activeCount > 0 && (
        <>
          <div
            className="outbox-bar"
            role="button"
            tabIndex={0}
            aria-expanded={expanded}
            aria-controls="outbox-list"
            onClick={() => setExpanded((prev) => !prev)}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                setExpanded((prev) => !prev);
              }
            }}
          >
            <div className="outbox-bar-left">
              <span className="outbox-pill">待发 {activeCount} 条</span>
              {pausedCount > 0 && (
                <span className="outbox-tag paused">{pausedCount} 条已暂停</span>
              )}
              {dispatchingCount > 0 && (
                <span className="outbox-tag dispatching">正在发送</span>
              )}
              {snapshot?.revision !== undefined && (
                <span className="outbox-revision-label" title={`待发箱版本 r${snapshot.revision}`}>
                  r{snapshot.revision}
                </span>
              )}
            </div>
            <div className="outbox-bar-right">
              <button
                className="outbox-toggle-button"
                type="button"
                aria-label={expanded ? "收起待发列表" : "展开待发列表"}
                title={expanded ? "收起待发列表" : "展开待发列表"}
                onClick={(e) => {
                  e.stopPropagation();
                  setExpanded((prev) => !prev);
                }}
              >
                <span>{expanded ? "收起" : "展开"}</span>
                <Icon name={expanded ? "chevron-left" : "chevron-down"} size={14} />
              </button>
            </div>
          </div>

          {expanded && (
            <div className="outbox-list" id="outbox-list">
              {items.map((item, index) => {
                const text = outboxItemText(item);
                const attachments = outboxItemAttachments(item);
                const isDetailOpen = Boolean(expandedDetails[item.id]);
                const isBusy = busyActionItemId === item.id;
                // 仅 pending 与 paused 允许编辑、移除与 stop_and_send，dispatching 与 submitted 为只读
                const canModify = isItemModifiable(item.state);
                const isPaused = item.state === "paused";
                const canStopAndSend = Boolean(
                  isAgentRunActive &&
                  canModify &&
                  activeSessionId?.trim() &&
                  activeTurnId?.trim()
                );
                return (
                  <div
                    className={`outbox-item state-${item.state}`}
                    key={item.id}
                    data-sequence={item.sequence}
                  >
                    <div className="outbox-item-header">
                      <div className="outbox-item-meta">
                        <span className="outbox-item-index" title={`队首序号 #${item.sequence}`}>
                          #{index + 1}
                        </span>
                        <span className={`outbox-item-badge ${item.state}`}>
                          {outboxStateLabel(item.state)}
                        </span>
                        {item.createdAt > 0 && (
                          <span className="outbox-item-time">
                            {formatItemTime(item.createdAt)}
                          </span>
                        )}
                      </div>
                      <div className="outbox-item-actions">
                        {canModify && (
                          <button
                            className="outbox-action-btn edit"
                            type="button"
                            disabled={isBusy}
                            onClick={() => startEditing(item)}
                          >
                            编辑
                          </button>
                        )}
                        {canModify && (
                          <button
                            className="outbox-action-btn remove"
                            type="button"
                            disabled={isBusy}
                            onClick={() => void handleRemove(item)}
                          >
                            移除
                          </button>
                        )}
                        {isPaused && (
                          <button
                            className="outbox-action-btn resume"
                            type="button"
                            disabled={isBusy}
                            title="恢复此任务排队投递"
                            onClick={() => void handleResume(item)}
                          >
                            继续发送
                          </button>
                        )}
                        {isAgentRunActive && canModify && (
                          <button
                            className="outbox-action-btn stop-send"
                            type="button"
                            disabled={isBusy || !canStopAndSend}
                            title={
                              canStopAndSend
                                ? "停止当前正在执行的任务并将此项提至队首发送"
                                : "未获取到当前任务的会话或轮次标识，无法停止并发送"
                            }
                            onClick={() => void handleStopAndSend(item)}
                          >
                            停止当前任务并发送
                          </button>
                        )}
                      </div>
                    </div>

                    <div className="outbox-item-body">
                      {text ? (
                        <p className={`outbox-item-text${isDetailOpen ? " full" : " clamp"}`}>
                          {text}
                        </p>
                      ) : (
                        <p className="outbox-item-text empty">(无文字内容)</p>
                      )}

                      {attachments.length > 0 && (
                        <div className="outbox-item-attachments">
                          {isDetailOpen ? (
                            attachments.map((att, attIndex) => (
                              <div
                                className="outbox-attachment-chip"
                                key={`${att.fileName}-${attIndex}`}
                              >
                                {att.isImage ? (
                                  <img
                                    className="outbox-attachment-thumb"
                                    src={att.dataUrl || (att.path ? workspaceDownloadUrl(att.path) : (att.uri || ""))}
                                    alt={att.fileName}
                                  />
                                ) : (
                                  <Icon name="file" size={14} />
                                )}
                                <span className="outbox-attachment-name" title={att.fileName}>
                                  {att.fileName}
                                </span>
                                <span className="outbox-attachment-size">
                                  {formatBytes(att.size)}
                                </span>
                              </div>
                            ))
                          ) : (
                            <span className="outbox-attachment-count-summary">
                              📎 {attachments.length} 个附件
                            </span>
                          )}
                        </div>
                      )}

                      {item.pauseReason && (
                        <div className="outbox-pause-reason">
                          暂停原因：{item.pauseReason}
                        </div>
                      )}

                      {(text.length > 80 || attachments.length > 0) && (
                        <button
                          className="outbox-detail-toggle"
                          type="button"
                          onClick={() => toggleDetail(item.id)}
                        >
                          {isDetailOpen ? "收起详情" : "查看完整内容"}
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}

      {/* Edit modal: isolated editing state, NEVER touching active draft in composer */}
      {editingTarget && (
        <div className="outbox-edit-overlay" role="dialog" aria-modal="true" aria-label="编辑待发项">
          <div className="outbox-edit-dialog">
            <header className="outbox-edit-header">
              <h3>编辑待发消息</h3>
              <button
                className="outbox-icon-button"
                type="button"
                aria-label="关闭编辑"
                onClick={cancelEditing}
              >
                <Icon name="x" size={16} />
              </button>
            </header>

            {editError && (
              <div className="outbox-error-banner" role="alert">
                <span className="outbox-error-text">{editError}</span>
              </div>
            )}

            <div className="outbox-edit-content">
              <textarea
                className="outbox-edit-textarea"
                rows={4}
                placeholder="请输入要更新的待发消息内容"
                value={editText}
                onChange={(e) => setEditText(e.target.value)}
              />

              {editAttachments.length > 0 && (
                <div className="outbox-edit-attachments">
                  {editAttachments.map((att, attIdx) => (
                    <div
                      className="outbox-edit-attachment-chip"
                      key={`${att.fileName}-${attIdx}`}
                    >
                      <span className="outbox-attachment-name" title={att.fileName}>
                        {att.fileName}
                      </span>
                      <button
                        className="outbox-icon-button small"
                        type="button"
                        aria-label={`移除附件 ${att.fileName}`}
                        onClick={() =>
                          setEditAttachments((prev) =>
                            prev.filter((_, idx) => idx !== attIdx),
                          )
                        }
                      >
                        <Icon name="x" size={12} />
                      </button>
                    </div>
                  ))}
                </div>
              )}

              <div className="outbox-edit-actions-bar">
                <button
                  className="outbox-secondary-button"
                  type="button"
                  onClick={() => editAttachmentInputRef.current?.click()}
                >
                  <ComposerAttachmentIcon />
                  <span>添加附件</span>
                </button>
                <input
                  ref={editAttachmentInputRef}
                  type="file"
                  multiple
                  hidden
                  onChange={(e) => void handleAddEditAttachments(e)}
                />
              </div>
            </div>

            <footer className="outbox-edit-footer">
              <button
                className="outbox-cancel-button"
                type="button"
                disabled={editSaving}
                onClick={cancelEditing}
              >
                取消
              </button>
              <button
                className="outbox-save-button"
                type="button"
                disabled={editSaving}
                onClick={() => void handleSaveEdit()}
              >
                {editSaving ? "保存中..." : "保存修改"}
              </button>
            </footer>
          </div>
        </div>
      )}
    </aside>
  );
}
