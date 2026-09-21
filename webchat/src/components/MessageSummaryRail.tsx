import { useEffect } from "react";
import { relativeDate } from "../format";
import type { MessageSummaryEntry } from "../messageSummary";
import { Icon } from "./Icon";

interface MessageSummaryRailProps {
  entries: MessageSummaryEntry[];
  open: boolean;
  onToggle: () => void;
  onJump: (anchorId: string) => void;
}

/** 消息快速导航: 消息列表右缘细轨道 + 点击展开摘要弹窗, 点击条目滚动定位 */
export function MessageSummaryRail({ entries, open, onToggle, onJump }: MessageSummaryRailProps) {
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") onToggle();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onToggle]);

  if (entries.length < 2) return null;
  const last = entries.length - 1;

  return (
    <nav className="message-summary-rail" aria-label="对话摘要导航">
      <button
        type="button"
        className="summary-rail-track"
        aria-label="打开对话摘要"
        aria-expanded={open}
        onClick={onToggle}
      >
        {entries.map((entry, index) => (
          <span
            key={entry.anchorId}
            className={`summary-tick ${entry.kind}`}
            style={{ top: `${Math.round((index / last) * 100)}%` }}
          />
        ))}
      </button>
      {open && (
        <div className="summary-popup" role="dialog" aria-label="对话摘要">
          <header className="summary-popup-header">
            <span>对话摘要</span>
            <button type="button" aria-label="关闭摘要" onClick={onToggle}>
              <Icon name="x" size={14} />
            </button>
          </header>
          <ul>
            {entries.map((entry) => (
              <li key={entry.anchorId}>
                <button
                  type="button"
                  className={`summary-item ${entry.kind}`}
                  onClick={() => onJump(entry.anchorId)}
                >
                  <span className="summary-item-dot" aria-hidden="true" />
                  <span className="summary-item-title">{entry.title}</span>
                  <span className="summary-item-time">{relativeDate(entry.time)}</span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </nav>
  );
}
