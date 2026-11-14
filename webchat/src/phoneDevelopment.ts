export interface LsposedStructuredEvidence {
  module?: string;
  target?: string;
  pid?: string;
  observedAt?: string;
  tag?: string;
}

export interface PhoneDevStage1Env {
  executed: boolean;
  isReadyForBuild?: boolean;
  missingTools?: string[];
  message?: string;
}

export interface PhoneDevStage2Project {
  executed: boolean;
  projectPath?: string;
  targetPackage?: string;
  apkHash?: string;
  dexCount?: number;
  classesCount?: number;
  methodCount?: number;
}

export interface PhoneDevStage3Build {
  executed: boolean;
  routeUsed?: string;
  apkPath?: string;
  signatureVerified?: boolean;
  message?: string;
}

export interface PhoneDevStage4Deploy {
  executed: boolean;
  installed?: boolean;
  moduleEnabled?: boolean;
  scopeConfigured?: boolean;
  targetReloaded?: boolean;
  message?: string;
}

export interface PhoneDevStage5Verify {
  executed: boolean;
  configVerified?: boolean;
  /** 面向用户命名为“框架加载证据”，绝不能叫“业务效果”或“业务成功” */
  frameworkEvidenceObserved?: boolean;
  evidence?: LsposedStructuredEvidence | null;
  message?: string;
}

export interface ParsedPhoneDevelopmentStages {
  isPhoneDevelopment: boolean;
  action?: string;
  modulePackage?: string;
  targetPackage?: string;
  stage1Env: PhoneDevStage1Env;
  stage2Project: PhoneDevStage2Project;
  stage3Build: PhoneDevStage3Build;
  stage4Deploy: PhoneDevStage4Deploy;
  stage5Verify: PhoneDevStage5Verify;
}

export function parsePhoneDevelopmentStages(card: Record<string, unknown>): ParsedPhoneDevelopmentStages {
  const toolName = String(card.toolName ?? card.toolTitle ?? card.toolType ?? "").toLowerCase();
  const rawAction = String(card.action ?? "").toLowerCase();
  const rawType = String(card.type ?? "").toLowerCase();

  const isPhoneDev =
    toolName === "phone_development" ||
    rawType === "phone_development" ||
    rawAction.startsWith("dev_env_") ||
    rawAction.startsWith("project_") ||
    rawAction.startsWith("apk_analyze") ||
    rawAction.startsWith("probe_") ||
    rawAction === "lsposed_deploy" ||
    rawAction === "deploy" ||
    "installed" in card ||
    "moduleEnabled" in card ||
    "scopeConfigured" in card ||
    "hookVerified" in card ||
    "frameworkEvidenceObserved" in card ||
    "frameworkEvidence" in card;

  if (!isPhoneDev) {
    return {
      isPhoneDevelopment: false,
      stage1Env: { executed: false },
      stage2Project: { executed: false },
      stage3Build: { executed: false },
      stage4Deploy: { executed: false },
      stage5Verify: { executed: false },
    };
  }

  // Extract nested payloads
  const payload = (typeof card.rawResultJson === "object" && card.rawResultJson !== null
    ? card.rawResultJson
    : (typeof card.previewJson === "object" && card.previewJson !== null
      ? card.previewJson
      : (typeof card.payload === "object" && card.payload !== null
        ? card.payload
        : (typeof card.data === "object" && card.data !== null ? card.data : card)))) as Record<string, unknown>;

  const modulePackage = String(
    payload.modulePackageName ?? payload.modulePackage ?? card.modulePackageName ?? card.modulePackage ?? ""
  ).trim() || undefined;

  const targetPackage = String(
    payload.targetPackage ?? card.targetPackage ?? ""
  ).trim() || undefined;

  // 1. 环境检查
  const envExecuted = rawAction === "dev_env_status" || "isReadyForBuild" in payload || "isReadyForBuild" in card;
  const isReady = typeof payload.isReadyForBuild === "boolean"
    ? payload.isReadyForBuild
    : (typeof card.isReadyForBuild === "boolean" ? card.isReadyForBuild : undefined);
  const rawMissing = payload.missingTools ?? card.missingTools;
  const missingTools = Array.isArray(rawMissing)
    ? rawMissing.map(String)
    : (typeof rawMissing === "string" && rawMissing ? [rawMissing] : undefined);

  // 2. 项目与分析
  const projectExecuted =
    rawAction === "apk_analyze" ||
    rawAction === "project_create" ||
    rawAction === "project_import" ||
    Boolean(payload.projectPath ?? card.projectPath ?? payload.apkHash ?? card.apkHash ?? payload.dexCount ?? card.dexCount);

  const projectPath = String(payload.projectPath ?? card.projectPath ?? "").trim() || undefined;
  const apkHash = String(payload.apkHash ?? card.apkHash ?? payload.sha256 ?? card.sha256 ?? "").trim() || undefined;
  const dexCount = typeof payload.dexCount === "number" ? payload.dexCount : (typeof card.dexCount === "number" ? card.dexCount : undefined);
  const classesCount = typeof payload.classesCount === "number" ? payload.classesCount : (typeof card.classesCount === "number" ? card.classesCount : undefined);
  const methodCount = typeof payload.methodCount === "number" ? payload.methodCount : (typeof card.methodCount === "number" ? card.methodCount : undefined);

  // 3. 构建与签名
  const buildExecuted =
    rawAction === "project_build" ||
    Boolean(payload.routeUsed ?? card.routeUsed ?? payload.signatureVerified ?? card.signatureVerified);
  const routeUsed = String(payload.routeUsed ?? card.routeUsed ?? "").trim() || undefined;
  const apkPath = String(payload.apkPath ?? card.apkPath ?? "").trim() || undefined;
  const signatureVerified = typeof payload.signatureVerified === "boolean"
    ? payload.signatureVerified
    : (typeof card.signatureVerified === "boolean" ? card.signatureVerified : undefined);

  // 4. 部署与配置
  const deployExecuted =
    rawAction === "lsposed_deploy" ||
    rawAction === "deploy" ||
    "installed" in payload ||
    "installed" in card ||
    "scopeConfigured" in payload ||
    "scopeConfigured" in card;

  const installed = typeof payload.installed === "boolean"
    ? payload.installed
    : (typeof card.installed === "boolean" ? card.installed : undefined);
  const moduleEnabled = typeof payload.moduleEnabled === "boolean"
    ? payload.moduleEnabled
    : (typeof card.moduleEnabled === "boolean" ? card.moduleEnabled : undefined);
  const scopeConfigured = typeof payload.scopeConfigured === "boolean"
    ? payload.scopeConfigured
    : (typeof card.scopeConfigured === "boolean" ? card.scopeConfigured : undefined);
  const targetReloaded = typeof payload.targetReloaded === "boolean"
    ? payload.targetReloaded
    : (typeof card.targetReloaded === "boolean" ? card.targetReloaded : undefined);

  // 5. 验证与证据 (hookVerified 严格映射为“框架加载证据”)
  const verifyExecuted =
    "configVerified" in payload ||
    "configVerified" in card ||
    "hookVerified" in payload ||
    "hookVerified" in card ||
    "frameworkEvidence" in payload ||
    "frameworkEvidence" in card;

  const configVerified = typeof payload.configVerified === "boolean"
    ? payload.configVerified
    : (typeof card.configVerified === "boolean" ? card.configVerified : undefined);

  const hookVerified = Boolean(
    payload.frameworkEvidenceObserved ??
    card.frameworkEvidenceObserved ??
    payload.hookVerified ??
    card.hookVerified
  );

  // 提取脱敏的结构化框架证据 {module, target, pid, observedAt, tag}
  const rawEv = (payload.frameworkEvidence ?? card.frameworkEvidence) as Record<string, unknown> | undefined;
  let evidence: LsposedStructuredEvidence | null = null;
  if (rawEv && typeof rawEv === "object") {
    evidence = {
      module: rawEv.module ? String(rawEv.module) : modulePackage,
      target: rawEv.target ? String(rawEv.target) : targetPackage,
      pid: rawEv.pid ? String(rawEv.pid) : undefined,
      observedAt: rawEv.observedAt ? String(rawEv.observedAt) : undefined,
      tag: rawEv.tag ? String(rawEv.tag) : undefined,
    };
  } else if (payload.evidencePid || card.evidencePid) {
    evidence = {
      module: modulePackage,
      target: targetPackage,
      pid: String(payload.evidencePid ?? card.evidencePid),
      observedAt: String(payload.evidenceObservedAt ?? card.evidenceObservedAt ?? ""),
      tag: String(payload.evidenceTag ?? card.evidenceTag ?? "LSPosed-Bridge"),
    };
  }

  return {
    isPhoneDevelopment: true,
    action: rawAction,
    modulePackage,
    targetPackage,
    stage1Env: {
      executed: envExecuted,
      isReadyForBuild: isReady,
      missingTools,
      message: payload.message ? String(payload.message) : undefined,
    },
    stage2Project: {
      executed: projectExecuted,
      projectPath,
      targetPackage,
      apkHash,
      dexCount,
      classesCount,
      methodCount,
    },
    stage3Build: {
      executed: buildExecuted,
      routeUsed,
      apkPath,
      signatureVerified,
      message: payload.message ? String(payload.message) : undefined,
    },
    stage4Deploy: {
      executed: deployExecuted,
      installed,
      moduleEnabled,
      scopeConfigured,
      targetReloaded,
      message: payload.message ? String(payload.message) : undefined,
    },
    stage5Verify: {
      executed: verifyExecuted,
      configVerified,
      frameworkEvidenceObserved: hookVerified,
      evidence,
      message: payload.message ? String(payload.message) : undefined,
    },
  };
}
