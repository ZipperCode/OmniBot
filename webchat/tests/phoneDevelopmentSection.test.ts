import assert from "node:assert/strict";
import test from "node:test";
import { parsePhoneDevelopmentStages } from "../src/phoneDevelopment.ts";

test("parsePhoneDevelopmentStages identifies non-phone-dev card as false", () => {
  const normalCard = {
    toolName: "browser_navigate",
    action: "navigate",
    url: "https://example.com",
  };
  const parsed = parsePhoneDevelopmentStages(normalCard);
  assert.equal(parsed.isPhoneDevelopment, false);
});

test("parsePhoneDevelopmentStages parses all 5 stages from full LSPosed deploy card", () => {
  const deployCard = {
    toolName: "phone_development",
    action: "lsposed_deploy",
    targetPackage: "com.example.target",
    modulePackageName: "com.example.module",
    rawResultJson: {
      action: "lsposed_deploy",
      apkPath: "/data/local/tmp/module.apk",
      modulePackageName: "com.example.module",
      targetPackage: "com.example.target",
      installed: true,
      moduleEnabled: true,
      scopeConfigured: true,
      targetReloaded: true,
      configVerified: true,
      hookVerified: true,
      frameworkEvidenceObserved: true,
      frameworkEvidence: {
        module: "com.example.module",
        target: "com.example.target",
        pid: "12345",
        observedAt: "11-14 10:30:00.123",
        tag: "LSPosed-Bridge",
      },
      message: "LSPosed 模块配置与重载已完成；已观察到框架加载证据；业务功能尚未在目标应用中核验",
    },
  };

  const parsed = parsePhoneDevelopmentStages(deployCard);
  assert.equal(parsed.isPhoneDevelopment, true);
  assert.equal(parsed.modulePackage, "com.example.module");
  assert.equal(parsed.targetPackage, "com.example.target");

  // Stage 4: 部署
  assert.equal(parsed.stage4Deploy.executed, true);
  assert.equal(parsed.stage4Deploy.installed, true);
  assert.equal(parsed.stage4Deploy.moduleEnabled, true);
  assert.equal(parsed.stage4Deploy.scopeConfigured, true);
  assert.equal(parsed.stage4Deploy.targetReloaded, true);

  // Stage 5: 验证与证据
  assert.equal(parsed.stage5Verify.executed, true);
  assert.equal(parsed.stage5Verify.configVerified, true);
  assert.equal(parsed.stage5Verify.frameworkEvidenceObserved, true);

  // 结构化证据解析
  assert.ok(parsed.stage5Verify.evidence);
  assert.equal(parsed.stage5Verify.evidence?.module, "com.example.module");
  assert.equal(parsed.stage5Verify.evidence?.target, "com.example.target");
  assert.equal(parsed.stage5Verify.evidence?.pid, "12345");
  assert.equal(parsed.stage5Verify.evidence?.observedAt, "11-14 10:30:00.123");
  assert.equal(parsed.stage5Verify.evidence?.tag, "LSPosed-Bridge");
});

test("parsePhoneDevelopmentStages parses build and analysis stages", () => {
  const buildCard = {
    toolName: "phone_development",
    action: "project_build",
    payload: {
      routeUsed: "auto (no-gradle)",
      apkPath: "/storage/emulated/0/Project/build/release.apk",
      signatureVerified: true,
      projectPath: "/storage/emulated/0/Project",
      targetPackage: "com.test.app",
      apkHash: "abcdef1234567890",
      dexCount: 2,
      classesCount: 150,
      methodCount: 1200,
    },
  };

  const parsed = parsePhoneDevelopmentStages(buildCard);
  assert.equal(parsed.isPhoneDevelopment, true);

  // Stage 2: 项目分析
  assert.equal(parsed.stage2Project.executed, true);
  assert.equal(parsed.stage2Project.projectPath, "/storage/emulated/0/Project");
  assert.equal(parsed.stage2Project.targetPackage, "com.test.app");
  assert.equal(parsed.stage2Project.dexCount, 2);
  assert.equal(parsed.stage2Project.classesCount, 150);

  // Stage 3: 构建签名
  assert.equal(parsed.stage3Build.executed, true);
  assert.equal(parsed.stage3Build.routeUsed, "auto (no-gradle)");
  assert.equal(parsed.stage3Build.apkPath, "/storage/emulated/0/Project/build/release.apk");
  assert.equal(parsed.stage3Build.signatureVerified, true);
});

test("parsePhoneDevelopmentStages parses missing tools in environment check", () => {
  const envCard = {
    toolName: "phone_development",
    action: "dev_env_status",
    payload: {
      isReadyForBuild: false,
      missingTools: ["d8", "aapt2", "android.jar"],
    },
  };

  const parsed = parsePhoneDevelopmentStages(envCard);
  assert.equal(parsed.isPhoneDevelopment, true);
  assert.equal(parsed.stage1Env.executed, true);
  assert.equal(parsed.stage1Env.isReadyForBuild, false);
  assert.deepEqual(parsed.stage1Env.missingTools, ["d8", "aapt2", "android.jar"]);
});
