---
name: phone-development
description: 手机端 LSPosed 模块开发与验证闭环指南（F10-F16, F18），指导 Agent 完成从环境检查、APK分析、运行时探测、代码开发、本机构建签名、作用域部署到真实行为验收的全流程。
---

# 手机端 LSPosed 模块开发与验证闭环 (Phone Development)

本指南面向在已 Root (KernelSU) 的 Android 手机上独立完成 Xposed/LSPosed 模块开发、构建、部署与真机验证闭环的 Agent 任务。

## 核心原则

1. **三层执行环境严格隔离**：
   - 编译与打包（javac, d8, aapt2, apksigner, gradle）运行在内嵌 Linux (Alpine PRoot) 终端中；
   - 工具编排与静态分析运行在 OmniBot 宿主沙箱 JVM 中；
   - 提取系统/第三方 APK、模块安装、作用域配置、进程重载与 logcat 过滤统一走真实 Root (UID 0)。
2. **真实证据驱动（Shipped-Artifact & Runtime Evidence）**：
   - 严禁凭空捏造类名与方法签名，所有 Hook 点必须来自目标 APK 的 DEX 实际符号提取；
   - 静态分析不确定时，生成临时探测模块进行运行时堆栈捕获；
   - 探测日志严禁记录用户个人信息、密码或 Token，仅保留类、方法签名与调用栈证据；
   - 严格区分四阶段验证（构建签名、安装配置读回、框架加载证据、真实目标业务行为）：
     1. 构建签名成功（`signatureVerified`）；
     2. 模块安装与配置读回成功（`installed`, `moduleEnabled`, `scopeConfigured`, `configVerified`）；
     3. 框架加载证据（`hookVerified`，仅代表框架向目标进程注入了模块，绝不等于业务功能已实现）；
     4. 真实目标业务行为证据（必须在目标应用中观察到真实业务行为证据，如 UI 元素变更、界面呈现、接口返回或业务行为响应，才能向用户报告功能成功）。绝不将构建成功、安装成功或框架加载证据等同于功能实现。

## 标准工作流程

### 步骤 1：开发环境检查与准备
调用 `phone_development(action: "dev_env_status")` 检查 OpenJDK、javac、d8、aapt2、apksigner 及 `android.jar` 状态。
若缺失基础工具或 SDK，调用 `phone_development(action: "android_sdk_install", platform: 35)` 完成依赖拉取。

### 步骤 2：目标应用分析与 Hook 点定位
调用 `phone_development(action: "apk_analyze", packageName: "<target_package>")`：
- 提取目标应用的 base.apk 并校验真实包名与 SHA-256 哈希；
- 分析应用清单中的 Application 类、主 Activity 及导出组件；
- 扫描 `classes*.dex` 中的实际类名与方法签名，获取确凿符号。

### 步骤 3：运行时探测（可选）
当静态符号过多或存在混淆/动态调用分支时：
1. 调用 `phone_development(action: "probe_module_generate", targetPackage: "...", targetClasses: [...], targetMethods: [...])` 生成临时探测工程；
2. 构建并部署探测模块；
3. 操作目标应用触发相应功能；
4. 抓取 `logcat -d -s OmniBotProbe:V` 读回实际触发的方法签名与调用栈证据；
5. 调用 `phone_development(action: "probe_clean", probePackageName: "...", targetPackage: "...")` 彻底卸载探测模块、清理作用域并删除临时工程。

### 步骤 4：项目创建与代码编写
- **新项目开发**：调用 `phone_development(action: "project_create", packageName: "...", moduleName: "...", targetPackage: "...")` 生成脚手架；
- **已有项目维护**：调用 `phone_development(action: "project_import", sourcePath: "...")` 导入本地已有目录或 Git 克隆仓库；
- 使用标准文件读写与精准编辑工具（`read`, `write`, `edit`）编写具体 Hook 逻辑。

### 步骤 5：手机本机构建与签名
调用 `phone_development(action: "project_build", projectPath: "...", route: "auto")`：
- 无 Gradle 工程：执行 `build-apk.sh`，完成 DEX 打包、Android 11+ 对齐及 apksigner 签名验签；
- Gradle 工程：调用 Gradle 构建任务（支持指定 `buildTask` 参数），保留完整编译报告；
- **构建阶段验证**：必须确认输出 APK 存在、大小有效、SHA-256 哈希已生成且 `apksigner verify` 验签通过。

### 步骤 6：LSPosed 模块部署与目标重载
调用 `phone_development(action: "lsposed_deploy", apkPath: "...", targetPackage: "...", reloadTarget: true)`：
- **安装阶段验证**：经 Root 执行静默安装，并由底层读回 `pm path` 确认安装成功；
- **配置阶段验证**：通过特权通道调用 Vector/LSPosed CLI（`--json`）或只读数据库联合查询配置模块作用域并读回验证（不直接私写底层数据库）；
- **进程重载**：强制重启目标应用（`am force-stop`）使其加载最新 DEX 插件；
- **注意**：`lsposed_deploy` 成功（`success: true`）仅代表部署与配置基础设施操作完成；返回的 `hookVerified` 仅为“框架加载证据”（代表框架注入了目标进程），**绝不代表业务功能已生效**，此时业务功能尚未在目标应用中核验。

### 步骤 7：真实目标业务行为验收与迭代修复
- **真实业务行为验证**：操作目标应用，读回特定业务行为标记、界面 UI 变更或接口响应证据；必须获得确凿的业务行为证据后方可向用户报告任务成功完成；
- **排查与日志**：若未观察到预期业务行为，可抓取系统与模块日志（`logcat -d -v threadtime -b main -b system`），排查 Hook 点是否被命中、是否发生异常崩溃；
- **迭代闭环**：若未生效或异常崩溃，分析堆栈，修改代码并重复步骤 5~7，直至达到用户验收要求。严禁仅凭框架加载日志直接声称功能已实现。
