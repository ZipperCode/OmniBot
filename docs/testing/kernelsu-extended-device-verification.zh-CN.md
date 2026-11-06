# KernelSU 扩展真机回归验证记录

> 历史记录：2026-11-06 已采用上游 0.7.0.4 的 Root/Shizuku 实现。本文保留旧版设备证据，不代表新版真机验收通过。

状态：阶段五扩展真机回归全部场景完整执行并通过。
验证负责人：Device Execution Lane
目标设备：`b5a43b69` (Xiaomi 2211133C, Android 16 / API 36)
应用版本：`cn.com.omnimind.bot` debug 0.6.3 (versionCode 16, UID 10413)
实测安装 APK SHA-256：`77D41F2EC332C4AB01C17270AD8F08D43999D7D84423C08D59D842B3A636058A`（包含会话取消修复）

> 时间归属说明：23:04 的 deny marker `5541` 来自更早的 ECA5 APK，不属于最终 `77D41F...6058A` 构建。最终 SHA 构建的设备证据以对应安装后的记录为准；后续 Phase 5 `outputComplete` 生产改动尚未重建或安装。
Root 管理器：`com.rifsxd.ksunext` (KernelSU Next v3.3.0)
应用特权状态：`PrivilegedSource.SU`, 实测权限 `PrivilegedAuthority.ROOT`, 实测 `UID: 0`

---

## 1. 运行环境与基线核验

- **安装事实**：
  - 本次测试运行于包含取消修复的最新 APK 构建（SHA-256: `77D41F2EC332C4AB01C17270AD8F08D43999D7D84423C08D59D842B3A636058A`）。
  - 应用沙箱保持完好，进程重新拉起后在设置页完成显式 Root 授权，内存 grant 恢复为 `UID: 0`。
- **Provider 配置与可用性**：
  - 端点：`https://zipper.cc.cd/v1`
  - 绑定模型：`deepseek-flash`（经 `DebugModelProviderConfigReceiver.bindExisting` 绑定至 `scene.dispatch.model`）
  - 可用性探针实测：`{"wire": "chat_completions", "available": true, "code": 200, "message": "OK"}`
  - 凭据存储：沙箱安全存储，全流程严格保密脱敏。
  - *(注：此前 `k3` 出现过超时而 `deepseek-flash` 快速通过，但单一样本不足以作为 `k3` 超时根因的绝对技术证明)*。
- **系统设置初始基线**：
  - 采样项：`system` 命名空间 `screen_off_timeout`
  - 独立 adb 采样初始值：`300000`（5 分钟）

---

## 2. 场景 A：受控高权限命令确认门禁（ACP Gate & Execution）

### A1：拒绝执行物理阻断验证（ACP Deny）
- **时间戳**：`2026-09-19 23:04:53` - `23:07:01`
- **会话与 Turn**：会话 ID `6`，Turn ID `7bd7fb42-4624-4a87-9891-b14be3c66ae8`
- **调用动作**：
  - 工具：`android_privileged_action`
  - `action`: `shell.exec`
  - `command`: `echo TEST_CONFIRM_FIX_DENY_5541 && id -u`
  - `timeoutSeconds`: `10`
- **ACP 门禁交互**：
  - 界面弹出阻断卡片：`高权限 shell 命令“echo TEST_CONFIRM_FIX_DENY_5541 && id -u”需要你的确认。当前尚未执行。请在本次授权请求中选择允许或拒绝。`
  - 操作：**显式点击「拒绝」**。
  - 卡片更新为 `已拒绝`，工具结果为 `失败`。
- **实测证据**：
  - 原生日志审计：应用日志中 `Privileged audit` **零记录**。未创建任何底层执行进程。
  - 工具错误反馈：`用户拒绝了高权限操作，命令未执行。`

### A2：允许执行与 Root UID0 验证（ACP Allow）
- **时间戳**：`2026-09-19 23:51:24` - `23:51:49`
- **会话与 Turn**：会话 ID `9`
- **调用动作**：
  - 工具：`android_privileged_action`
  - `action`: `shell.exec`
  - `command`: `echo OMNI_ALLOW_7342; id -u`
  - `timeoutSeconds`: `10`
- **ACP 门禁交互**：
  - 界面弹出阻断卡片：`高权限 shell 命令“echo OMNI_ALLOW_7342; id -u”需要你的确认。当前尚未执行。请在本次授权请求中选择允许或拒绝。`
  - 操作：**显式点击「允许」**。
- **实测证据**：
  - **原生审计日志**：
    ```text
    09-19 23:51:44.454 8972 9384 I [Omni]PrivilegedCommandExec: Privileged audit: action=shell.exec, command=echo OMNI_ALLOW_7342; id -u, success=true, code=ok, exit=0
    ```
  - **工具实测输出**：
    ```text
    OMNI_ALLOW_7342
    0
    ```
  - **权限与标识**：`authority: root`, `source: su`, `exitCode: 0`。实测输出 `0` 确证以 Root 身份执行。

---

## 3. 场景 B：系统设置可逆读写与恢复（Settings Reversible Mutation）

全流程在同一会话（ID: `9`）中连续执行：

### B1：基线回读（settings_control.get）
- **时间戳**：`2026-09-19 23:52:47`
- **动作**：`android_privileged_action(action="settings_control.get", arguments={"namespace":"system","key":"screen_off_timeout"})`
- **原生审计日志**：
  ```text
  09-19 23:52:54.857 8972 9380 I [Omni]PrivilegedCommandExec: Privileged audit: action=settings_control.get, success=true, code=ok, exit=0
  ```
- **输出**：`300000`

### B2：写入临时测试值（settings_control.put 301000）
- **时间戳**：`2026-09-19 23:53:46`
- **动作**：`android_privileged_action(action="settings_control.put", arguments={"namespace":"system","key":"screen_off_timeout","value":"301000"})`
- **ACP 确认交互**：弹出确认卡片，显式点击「允许」。
- **原生审计日志**：
  ```text
  09-19 23:54:07.273 8972 9381 I [Omni]PrivilegedCommandExec: Privileged audit: action=settings_control.put, success=true, code=ok, exit=0
  ```
- **独立 adb 采样验证（关键证据）**：
  ```pwsh
  adb shell settings get system screen_off_timeout
  # 实测输出：301000（成功修改为基线+1000ms）
  ```

### B3：恢复原始基线值（settings_control.put 300000）
- **时间戳**：`2026-09-19 23:55:35`
- **动作**：`android_privileged_action(action="settings_control.put", arguments={"namespace":"system","key":"screen_off_timeout","value":"300000"})`
- **ACP 确认交互**：弹出确认卡片，显式点击「允许」。
- **原生审计日志**：
  ```text
  09-19 23:56:10.445 8972 8996 I [Omni]PrivilegedCommandExec: Privileged audit: action=settings_control.put, success=true, code=ok, exit=0
  ```
- **独立 adb 采样验证（恢复证据）**：
  ```pwsh
  adb shell settings get system screen_off_timeout
  # 实测输出：300000（确认 100% 恢复初始基线，无需降级 adb 抢救）
  ```

---

## 4. 场景 C：持久会话生命周期全流程（Session Lifecycle）

在同一会话（ID: `9`）中连续执行：

### C1：启动持久会话（android_privileged_session_start）
- **时间戳**：`2026-09-19 23:57:18`
- **参数**：`sessionName="session_test_4821"`
- **ACP 确认交互**：弹出「启动持久化高权限 shell 会话需要你的确认」，显式点击「允许」。
- **实测返回**：
  - `sessionId`: `ce7ae4c2-83c2-436d-8e2c-b557db0883f4`
  - `alive`: `true`, `running`: `false`, `authority`: `root`, `source`: `su`

### C2：会话命令执行（android_privileged_session_exec）
- **时间戳**：`2026-09-19 23:58:42`
- **参数**：`sessionId="ce7ae4c2-83c2-436d-8e2c-b557db0883f4"`, `command="echo SESSION_TOKEN_EXEC_5193; id -u"`, `timeoutSeconds=10`
- **ACP 确认交互**：弹出「执行高权限会话命令需要你的确认」，显式点击「允许」。
- **实测输出**：
  ```text
  SESSION_TOKEN_EXEC_5193
  0
  ```
- **会话状态**：`alive: true`, `running: false`, `exitCode: 0`

### C3：会话输出回读与终结（session_read & session_stop）
- **时间戳**：`2026-09-20 00:00:29`
- **步骤 1（读取）**：`android_privileged_session_read`
  - 读取输出：`SESSION_TOKEN_EXEC_5193\n0\n[stderr]`（与 C2 执行累积日志完全一致）。
- **步骤 2（停止）**：`android_privileged_session_stop`
  - 返回状态：`stopped: true`, `alive: false`, `running: false`
  - 确证逻辑会话路由已终结、直接持有进程已关闭；派生进程是否残留未验证。

---

## 5. 场景 D：会话超时等待与后台存活恢复（Bounded Session Timeout）

- **时间戳**：`2026-09-20 00:01:39` - `00:04:11`
- **执行步骤与实测凭据**：
  1. **启动独立会话**：`android_privileged_session_start(sessionName="session_timeout_test")`
     - ACP 确认卡片弹出并批准，分配 `sessionId`: `d496e7e7-b932-48ca-91e2-287a48f2a6f4`。
  2. **执行超时命令**：`android_privileged_session_exec`
     - 参数：`command="sleep 8; echo TIMEOUT_DONE_8819"`, `timeoutSeconds=5`
     - ACP 确认批准。
     - **超时实测**：5 秒等待超时，工具卡片显示 `高级动作执行超时。`，返回状态 `alive: true`, `running: true`。会话与命令未被物理杀死，继续在后台执行。
  3. **后台完成回读**：等待命令自然运行完成（总耗时约 8 秒）后调用 `android_privileged_session_read`
     - 实测回读内容：`TIMEOUT_DONE_8819 [stderr]`
     - 状态恢复为：`alive: true`, `running: false`。确证后台命令完整运行并落盘日志。
  4. **会话复用执行**：在超时恢复后的同一会话中调用 `android_privileged_session_exec(command="echo AFTER", timeoutSeconds=5)`
     - ACP 确认批准。
     - 实测输出：`AFTER`，`exitCode: 0`。证明会话在经历等待超时后仍可正常复用执行新命令。
  5. **销毁会话**：调用 `android_privileged_session_stop`
     - 返回状态：`stopped: true`, `alive: false`, `running: false`。会话路由与直接持有进程已关闭；不据此断言派生进程树。

---

## 6. 说明与证据边界声明

构建归属：拒绝标记 `5541` 属于 `ECA5…` 构建；本文批准执行、设置修改恢复和会话详细测试属于 `77d4…` 构建。最终 `2d08…` 构建另有 5/5 instrumentation 和新的一次 Agent 只读调用，见 `kernelsu-instrumented-verification.zh-CN.md`、`kernelsu-final-agent-smoke.zh-CN.md`，不将历史测试平移为终版全量重测。最终冒烟后已在应用内重新授权 Root UID 0，模型保留 `deepseek-flash`。

1. **ACP 门禁与底层执行的独立性**：
   - 原生单元/插桩测试（Instrumentation 5/5 passed）仅代表原生组件逻辑，**不作为 ACP 用户确认门禁生效的凭据**。
   - 本文所载场景 A/B/C/D 的确认门禁证据全部来自于 Android 实体机上的真实用户界面点击、ACP 阻塞卡片拦截以及应用原生审计日志。
2. **最终设备与系统现场**：
   - 最终系统设置：`system screen_off_timeout` 保持为原始值 `300000`（经独立 adb 反复核验）。
   - 最终特权状态：应用维持 `已授权（Root）`（UID: `0`）。
   - 最终会话状态：所有测试会话均已调用 `session_stop` 正常关闭，逻辑路由无残留；未验证派生后台进程。
   - 临时文件：所有临时 dump/xml 文件已全数清理完毕。
