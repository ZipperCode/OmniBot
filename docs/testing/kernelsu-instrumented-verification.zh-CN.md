# KernelSU 原生 instrumentation 验证

## 范围

- 目标设备：`b5a43b69`。
- 测试在 `cn.com.omnimind.bot` 的 Android instrumentation 目标进程中调用公开的 `PrivilegedCapabilityManager`，不使用 `adb shell su` 代替应用身份，不增加导出入口。
- `confirmed=true` 仅用于直接验证原生 transport；它不构成 ACP 审批链路验证。
- 超时含义保持不变：等待超时后命令仍在持久会话中运行，会话保持 alive/busy，迟到 marker 完成后才接受下一命令。
- 取消只验证直接 `Process` 的清理与后续 transport 可用性，不承诺清理未知派生进程树。

## 覆盖用例

1. 应用上下文主动授权，测得 UID 0 / ROOT，并执行无副作用 `printf` 与 `id -u`。
2. 启动、执行、读取和停止实际 root 持久会话；40KB 单行输出在读取期间限制为 16KB。
3. `sleep 8` 使用 5 秒等待超时，验证 busy、迟到完成及后续命令成功。
4. 取消最长 8 秒的直接命令，验证取消传播和后续命令可执行。
5. 通过类型化 action 临时修改 `system/screen_off_timeout`，在 `finally` 中恢复原值并复读确认。
6. `su -> none` 关闭旧逻辑会话，再切回 `su` 并重新授权 UID 0。

## 明确限制

- KernelSU 拒绝列表/授权开关未自动操作。没有安全、确定的公开 seam 可仅失效 grant 而保留既有会话路由，因此“独立 grant 失效后 stop”留作人工或后续专用测试；不改写 MMKV、不绕过 root manager。
- 测试不会验证 ACP approval、Provider 或模型可用性。

## 执行结果

- 2026-09-19 在设备 `b5a43b69` 安装同签名 `developStandardDebug`（`install -r -t`，未卸载、未清数据）和 instrumentation APK。
- 构建：目标 APK 与测试 APK成功；仅使用 JBR 21、既有 Android SDK/NDK 29 和隔离 Flutter 3.47.2。
- 实际运行 5 个测试，前 2 个通过：
  - 应用 UID 上下文主动授权测得 `uid=0`、`authority=ROOT`，直接 transport 的 `id -u` 返回 `0`。
  - 实际持久 root 会话的 start/exec/read/stop、40KB 单行 16KB 上限、5 秒等待超时后保持 busy、8 秒迟到完成及后续 exec 全部通过；会话 finally stop 成功。
- 第 3 个取消测试触发真实生产缺陷并导致 instrumentation 进程崩溃，后 2 个测试未执行。直接进程关闭 stdout 时，`PrivilegedCommandExecutor.copyBounded` 的 daemon reader 抛出未捕获的 `InterruptedIOException: read interrupted by close() on another thread`。因此本轮结果是 **2 passed / 1 crashed / 2 not run**，不是全通过。
- 按“先报告 concrete defect”约束，本轮未修改生产 runtime，也未继续执行 setting/source-switch 用例。`screen_off_timeout` 未被测试修改，执行后实测仍为 `300000`，无需 adb 恢复。
- 崩溃后等待超过子负载 8 秒上限；设备保持在线。目标应用仍为 `0.6.3/versionCode 16`，数据、Provider 凭据和代理未清除或改写；来源选择仍为测试开始时的 `su`，进程内 grant 随 instrumentation 进程退出而失效，需下次显式 authorize 恢复。

## 首轮阻断缺陷（已修复）

首轮确认缺陷位于 `PrivilegedCommandExecutor` reader 线程：进程取消/关闭导致的流关闭异常逃逸为未捕获线程异常。下节记录修复及完整复验结果。

## 修复后复验

- 修复：stdout/stderr reader 在线程内捕获并记录 `IOException`，不再让关闭流异常成为未捕获线程异常。取消与超时分支先销毁直接 `Process`，最多各等待 reader 500ms，然后分别保留原有取消或 timeout 结果；正常进程退出时若 reader 有真实 I/O 失败，命令不得返回成功。
- 聚焦 JVM 测试：`PrivilegedCommandExecutorTest` 与 `PrivilegedShellSessionManagerTest` 共 **14/14 passed**。新增 fake 流覆盖并发 close 触发 `InterruptedIOException` 时无未捕获 worker 异常，以及正常退出时真实 reader I/O 错误不得伪装成功。
- APK 构建成功，目标 APK SHA-256：`77d41f2ec332c4ab01c17270ad8f08d43999d7d84423c08d59d842b3a636058a`。
- 设备 `b5a43b69` 使用同签名 `install -r -t` 更新目标和 instrumentation APK；未卸载、未清数据。
- 最终 instrumentation：**5/5 passed，12.426s**。
  - 应用 UID 原生路径显式授权再次测得 ROOT / UID 0。
  - root session、40KB 单行上限、timeout 保持 busy、迟到 marker、后续 exec 和 stop 均通过。
  - 取消传播通过，进程未崩溃，取消后直接 transport 再次执行成功；证据仍只覆盖直接 `Process`，不扩大为派生进程树保证。
  - 类型化 `screen_off_timeout` 从原值 `300000` 临时改为测试值并在 `finally` 恢复；测试后 adb 复读仍为 `300000`，未使用 adb recovery。
  - `su -> none` 关闭旧逻辑会话，随后恢复 `su` 并重新授权 UID 0；旧 session 路由返回不存在。
- 测试类 `AfterClass` 将最终来源恢复为 `su` 并重新授权；instrumentation 完成后目标进程由 runner 正常结束。Provider/model 存储、凭据及代理未由测试读取或修改，既有 `deepseek-flash` 选择保持不变。
- 本证据是原生 capability/transport 路径验证，不是 ACP approval 或 Agent 工具调用验证。KernelSU profile 拒绝未自动操作，因此独立 grant 撤销后的真实设备 stop，以及 manager 并发竞态压力仍未由本 instrumentation 覆盖。

## Phase 5 最终有界修复状态

- 后续静态审查发现 500ms reader 等待结束后线程仍可能存活。当前实现对正常退出结果增加既有 `PrivilegedResult.data["outputComplete"]`：两条输出 reader 均在等待期内完成时为 `true`，否则为 `false`；进程真实 exit 0 不会被伪造成 exitCode 失败。
- reader 未按期完成时关闭执行器持有的 stdout/stderr 输入流并再次进行有界等待。返回前缓冲快照仅在首次 reader 等待后读取；迟到的失败不会被声称为已观察到，输出完整性由 `outputComplete=false` 明示。
- 真实 reader `IOException` 的单元测试改为调用 audit 前的最小 internal 执行 seam，并严格断言 `success=false`、`code=command_failed`、`outputComplete=true`；不捕获 Android Log stub 异常，不跳过断言。
- 新增 blocked-reader latch 测试，断言 exitCode 0 保持成功但 `outputComplete=false`，并确认 owned stream 被关闭、reader 已退出；另覆盖完整输出的 `outputComplete=true`。
- 本轮仅执行 `PrivilegedCommandExecutorTest`：**8/8 passed，0 skipped，0 failures**。
- 最终 `outputComplete` 构建已完成：
  - App APK：`app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`
  - App SHA-256：`2d084aed3ce6d8f4364b0f8407aa3ed79237e528cc331a3bb44b53d41f350157`
  - AndroidTest APK：`app/build/outputs/apk/androidTest/developStandard/debug/app-develop-standard-debug-androidTest.apk`
  - AndroidTest SHA-256：`3748dfb0b1bd9eb265f5cb85db92253c8b3a46366c44a4132a7a3c9f031df9ef`
- 安装前确认设备 `b5a43b69` 在线、型号 `2211133C`，`screen_off_timeout=300000`。目标和测试 APK 均通过同签名 `install -r -t` 更新，未卸载、未清数据。
- 最终构建 instrumentation：**5/5 passed，12.467s**。实际执行了 UID 0/ROOT、持久会话、40KB 单行、timeout/late marker、取消后 transport、类型化 setting 恢复以及 `su -> none -> su` 五个测试方法。
- instrumentation 后 `screen_off_timeout` 复读为 `300000`，与基线一致，未使用 adb recovery。测试 `AfterClass` 在 runner 进程内把 durable source 恢复为 `su` 并重新授权；runner 结束后目标应用进程不存在，因此内存 grant cache 随进程消失，不能声称仍缓存授权。durable `su` 选择保留，下次应用进程需按既有流程重新建立 grant。
- 最新 JVM 证据仍为 `PrivilegedCommandExecutorTest` **8/8 passed，0 skipped，0 failures**；本轮未重复运行该已有效的聚焦单测。
- 既有 Provider/model 数据、凭据与代理未读取或修改；`deepseek-flash` 的持久选择应保持原状，但本验证未读取秘密或重新执行 Agent。此前 Agent 证据不归属于本最终 APK 哈希。
