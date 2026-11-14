import { useState } from "react";
import { Icon } from "./Icon";
import {
  parsePhoneDevelopmentStages,
  type LsposedStructuredEvidence,
  type ParsedPhoneDevelopmentStages,
  type PhoneDevStage1Env,
  type PhoneDevStage2Project,
  type PhoneDevStage3Build,
  type PhoneDevStage4Deploy,
  type PhoneDevStage5Verify,
} from "../phoneDevelopment";

export {
  parsePhoneDevelopmentStages,
  type LsposedStructuredEvidence,
  type ParsedPhoneDevelopmentStages,
  type PhoneDevStage1Env,
  type PhoneDevStage2Project,
  type PhoneDevStage3Build,
  type PhoneDevStage4Deploy,
  type PhoneDevStage5Verify,
};

export interface PhoneDevelopmentResultSectionProps {
  card: Record<string, unknown>;
  currentDraft: string;
  isAgentRunActive: boolean;
  onFillDraft: (text: string) => void;
  onOpenDraftPreview?: (text: string) => void;
}

export function PhoneDevelopmentResultSection({
  card,
  currentDraft,
  isAgentRunActive,
  onFillDraft,
  onOpenDraftPreview,
}: PhoneDevelopmentResultSectionProps) {
  const parsed = parsePhoneDevelopmentStages(card);
  const [copyPreviewText, setCopyPreviewText] = useState<string | null>(null);
  const [copyNotice, setCopyNotice] = useState<string | null>(null);

  if (!parsed.isPhoneDevelopment) return null;

  const targetPkg = parsed.targetPackage || "";
  const modulePkg = parsed.modulePackage || "";

  function handleActionClick(command: string) {
    if (!currentDraft.trim()) {
      onFillDraft(command);
    } else {
      if (onOpenDraftPreview) {
        onOpenDraftPreview(command);
      } else {
        setCopyPreviewText(command);
      }
    }
  }

  async function handleCopyPreview() {
    if (!copyPreviewText) return;
    try {
      await navigator.clipboard.writeText(copyPreviewText);
      setCopyNotice("指令已复制到剪贴板");
      setTimeout(() => setCopyNotice(null), 2500);
    } catch {
      setCopyNotice("复制失败，请手动选取文本");
    }
  }

  return (
    <div className="phone-dev-section" role="region" aria-label="手机开发与 LSPosed 部署状态">
      <header className="phone-dev-header">
        <Icon name="terminal" size={16} />
        <span className="phone-dev-header-title">手机开发 / LSPosed 部署全景</span>
        {isAgentRunActive && (
          <span className="phone-dev-queue-badge" title="当前有任务运行，新操作将排队执行">
            运行中 · 填入将排队
          </span>
        )}
      </header>

      <div className="phone-dev-stages-grid">
        {/* 阶段 1: 环境检查 */}
        <div className="phone-dev-stage-card">
          <div className="stage-card-header">
            <span className="stage-num">1</span>
            <strong className="stage-title">环境检查</strong>
            {parsed.stage1Env.executed ? (
              parsed.stage1Env.isReadyForBuild ? (
                <span className="stage-badge success">已就绪</span>
              ) : (
                <span className="stage-badge warning">缺失依赖</span>
              )
            ) : (
              <span className="stage-badge unconfirmed">未确认</span>
            )}
          </div>
          <div className="stage-card-body">
            {parsed.stage1Env.executed ? (
              parsed.stage1Env.missingTools && parsed.stage1Env.missingTools.length > 0 ? (
                <div className="stage-detail-line error">
                  缺失项：{parsed.stage1Env.missingTools.join(", ")}
                </div>
              ) : (
                <div className="stage-detail-line">SDK 与编译器工具链就绪</div>
              )
            ) : (
              <div className="stage-detail-line muted">本轮未执行环境检查</div>
            )}
          </div>
        </div>

        {/* 阶段 2: 项目 / 分析 */}
        <div className="phone-dev-stage-card">
          <div className="stage-card-header">
            <span className="stage-num">2</span>
            <strong className="stage-title">项目 / 静态分析</strong>
            {parsed.stage2Project.executed ? (
              <span className="stage-badge success">已分析</span>
            ) : (
              <span className="stage-badge unconfirmed">未确认</span>
            )}
          </div>
          <div className="stage-card-body">
            {parsed.stage2Project.executed ? (
              <>
                {parsed.stage2Project.targetPackage && (
                  <div className="stage-detail-line">
                    目标包：<code>{parsed.stage2Project.targetPackage}</code>
                  </div>
                )}
                {parsed.stage2Project.projectPath && (
                  <div className="stage-detail-line truncate" title={parsed.stage2Project.projectPath}>
                    工程：{parsed.stage2Project.projectPath}
                  </div>
                )}
                {parsed.stage2Project.apkHash && (
                  <div className="stage-detail-line mono">
                    APK Hash: {parsed.stage2Project.apkHash.slice(0, 12)}...
                  </div>
                )}
                {parsed.stage2Project.dexCount !== undefined && (
                  <div className="stage-detail-line">
                    DEX 统计：{parsed.stage2Project.dexCount} 个 DEX
                  </div>
                )}
              </>
            ) : (
              <div className="stage-detail-line muted">未执行静态分析</div>
            )}
          </div>
        </div>

        {/* 阶段 3: 构建签名 */}
        <div className="phone-dev-stage-card">
          <div className="stage-card-header">
            <span className="stage-num">3</span>
            <strong className="stage-title">构建签名</strong>
            {parsed.stage3Build.executed ? (
              parsed.stage3Build.signatureVerified ? (
                <span className="stage-badge success">验签通过</span>
              ) : (
                <span className="stage-badge info">已产出</span>
              )
            ) : (
              <span className="stage-badge unconfirmed">未确认</span>
            )}
          </div>
          <div className="stage-card-body">
            {parsed.stage3Build.executed ? (
              <>
                {parsed.stage3Build.routeUsed && (
                  <div className="stage-detail-line">构建途径：{parsed.stage3Build.routeUsed}</div>
                )}
                {parsed.stage3Build.apkPath && (
                  <div className="stage-detail-line truncate" title={parsed.stage3Build.apkPath}>
                    APK 产物：{parsed.stage3Build.apkPath}
                  </div>
                )}
                <div className="stage-notice-line">
                  * 成功构建只提供打开 APK / 目录，不自动执行部署。
                </div>
              </>
            ) : (
              <div className="stage-detail-line muted">未执行构建</div>
            )}
          </div>
        </div>

        {/* 阶段 4: 部署配置 */}
        <div className="phone-dev-stage-card">
          <div className="stage-card-header">
            <span className="stage-num">4</span>
            <strong className="stage-title">模块部署配置</strong>
            {parsed.stage4Deploy.executed ? (
              parsed.stage4Deploy.installed && parsed.stage4Deploy.scopeConfigured ? (
                <span className="stage-badge success">配置完成</span>
              ) : (
                <span className="stage-badge warning">部分完成</span>
              )
            ) : (
              <span className="stage-badge unconfirmed">未确认</span>
            )}
          </div>
          <div className="stage-card-body">
            {parsed.stage4Deploy.executed ? (
              <>
                <div className="stage-checklist">
                  <span className={`check-item ${parsed.stage4Deploy.installed ? "done" : ""}`}>
                    安装：{parsed.stage4Deploy.installed ? "已安装" : "未安装"}
                  </span>
                  <span className={`check-item ${parsed.stage4Deploy.moduleEnabled ? "done" : ""}`}>
                    启用：{parsed.stage4Deploy.moduleEnabled ? "已启用" : "未启用"}
                  </span>
                  <span className={`check-item ${parsed.stage4Deploy.scopeConfigured ? "done" : ""}`}>
                    作用域：{parsed.stage4Deploy.scopeConfigured ? "已配置" : "未配置"}
                  </span>
                  <span className={`check-item ${parsed.stage4Deploy.targetReloaded ? "done" : ""}`}>
                    重载：{parsed.stage4Deploy.targetReloaded ? "已重载" : "未重载"}
                  </span>
                </div>
                <div className="stage-notice-line">
                  * 部署仅代表基础设施操作完成，不能标示功能已实现。
                </div>
              </>
            ) : (
              <div className="stage-detail-line muted">未执行部署</div>
            )}
          </div>
        </div>

        {/* 阶段 5: 验证与证据 */}
        <div className="phone-dev-stage-card full-width">
          <div className="stage-card-header">
            <span className="stage-num">5</span>
            <strong className="stage-title">验证与证据</strong>
            {parsed.stage5Verify.executed ? (
              parsed.stage5Verify.frameworkEvidenceObserved ? (
                <span className="stage-badge success">发现框架加载证据</span>
              ) : (
                <span className="stage-badge warning">未观察到加载证据</span>
              )
            ) : (
              <span className="stage-badge unconfirmed">未确认</span>
            )}
          </div>
          <div className="stage-card-body">
            {parsed.stage5Verify.executed ? (
              <div className="stage-verify-content">
                <div className="verify-meta-row">
                  <span>
                    配置读回验证：
                    <strong>{parsed.stage5Verify.configVerified ? "已验证" : "未确认"}</strong>
                  </span>
                  <span>
                    框架加载证据：
                    <strong className={parsed.stage5Verify.frameworkEvidenceObserved ? "text-success" : "text-warning"}>
                      {parsed.stage5Verify.frameworkEvidenceObserved ? "已观察到" : "未观察到"}
                    </strong>
                  </span>
                </div>

                {parsed.stage5Verify.evidence && (
                  <div className="structured-evidence-card">
                    <div className="evidence-title">结构化框架注入证据 (仅脱敏元数据)</div>
                    <div className="evidence-fields">
                      {parsed.stage5Verify.evidence.module && (
                        <div>模块包名: <code>{parsed.stage5Verify.evidence.module}</code></div>
                      )}
                      {parsed.stage5Verify.evidence.target && (
                        <div>目标包名: <code>{parsed.stage5Verify.evidence.target}</code></div>
                      )}
                      {parsed.stage5Verify.evidence.pid && (
                        <div>进程 PID: <code>{parsed.stage5Verify.evidence.pid}</code></div>
                      )}
                      {parsed.stage5Verify.evidence.observedAt && (
                        <div>观察时间: <span>{parsed.stage5Verify.evidence.observedAt}</span></div>
                      )}
                      {parsed.stage5Verify.evidence.tag && (
                        <div>匹配来源: <code>{parsed.stage5Verify.evidence.tag}</code></div>
                      )}
                    </div>
                  </div>
                )}

                <p className="verify-caveat">
                  提示：框架加载证据证明 LSPosed 注入了目标进程；业务功能与 Hook 效果需在目标应用内实际操作验证。
                </p>
              </div>
            ) : (
              <div className="stage-detail-line muted">未执行验证或无加载证据</div>
            )}
          </div>
        </div>
      </div>

      {/* 快捷操作区：统一填入小万草稿，已有草稿弹窗复制，不直接 su / Root REST */}
      <footer className="phone-dev-actions">
        <span className="phone-dev-actions-label">准备新指令：</span>
        <div className="phone-dev-action-buttons">
          <button
            type="button"
            className="phone-dev-btn"
            onClick={() => handleActionClick(`请重新检查 LSPosed 模块加载证据：目标 ${targetPkg || "<目标包>"}，模块 ${modulePkg || "<模块包>"}`)}
            title="将重新检查加载证据的指令填入小万输入框"
          >
            重新检查加载证据
          </button>
          {targetPkg && (
            <button
              type="button"
              className="phone-dev-btn"
              onClick={() => handleActionClick(`查询模块 ${modulePkg || "<模块包>"} 对目标 ${targetPkg} 的作用域配置`)}
              title="将作用域查询指令填入小万输入框"
            >
              查询作用域
            </button>
          )}
          {modulePkg && (
            <button
              type="button"
              className="phone-dev-btn"
              onClick={() => handleActionClick(`为目标 ${targetPkg || "<目标包>"} 启用模块 ${modulePkg}`)}
              title="将启用模块指令填入小万输入框"
            >
              启用模块
            </button>
          )}
        </div>
      </footer>

      {/* 当前已有草稿时的复制预览弹窗 (不覆盖草稿) */}
      {copyPreviewText && (
        <div className="turn-modal-overlay" role="dialog" aria-modal="true" aria-label="复制操作指令">
          <div className="turn-modal-dialog compact">
            <header className="turn-modal-header">
              <h3>指令预览 (已有草稿保留)</h3>
              <button
                type="button"
                className="turn-modal-close"
                onClick={() => setCopyPreviewText(null)}
                aria-label="关闭"
              >
                <Icon name="x" size={16} />
              </button>
            </header>
            <div className="turn-preview-body">
              <p className="turn-preview-hint">
                当前输入框已有草稿，系统不会自动覆盖。你可以直接复制下列指令：
              </p>
              <textarea
                className="turn-preview-textarea"
                readOnly
                value={copyPreviewText}
                rows={3}
              />
              {copyNotice && <div className="turn-preview-toast">{copyNotice}</div>}
              {isAgentRunActive && (
                <div className="turn-preview-queue-note">
                  * 当前任务正在运行中，粘贴发送后将排队执行。
                </div>
              )}
            </div>
            <footer className="turn-modal-footer">
              <button
                type="button"
                className="turn-secondary-btn"
                onClick={() => setCopyPreviewText(null)}
              >
                取消
              </button>
              <button
                type="button"
                className="turn-primary-btn"
                onClick={() => void handleCopyPreview()}
              >
                复制指令
              </button>
            </footer>
          </div>
        </div>
      )}
    </div>
  );
}
