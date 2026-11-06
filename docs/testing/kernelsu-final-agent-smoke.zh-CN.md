# KernelSU 终版构建 Agent 端到端冒烟验证记录

> 历史记录：2026-11-06 已采用上游 0.7.0.4 的 Root/Shizuku 实现。本文保留旧版设备证据，不代表新版真机验收通过。

状态：终版构建 Agent 端到端只读冒烟验证通过。
验证负责人：Device Execution Lane
目标设备：`b5a43b69` (Xiaomi 2211133C, Android 16 / API 36)
Root 管理器：`com.rifsxd.ksunext` (KernelSU Next v3.3.0)

---

## 1. 构建来源与安装记录关联（Build & Installation Association）

- **本地构建文件**：
  - 路径：`D:\Project\GitHub\OmniBot\app\build\outputs\apk\developStandard\debug\app-develop-standard-debug.apk`
  - 实测 SHA-256：`2D084AED3CE6D8F4364B0F8407AA3ED79237E528CC331A3BB44B53D41F350157`
- **设备端安装包记录**：
  - 包名：`cn.com.omnimind.bot`
  - `dumpsys package` 采样：`versionCode=16`, `versionName=0.6.3`, `lastUpdateTime=2026-09-20 00:22:12`
  - 关联事实：由插桩测试运行器完成覆盖安装，原应用私有沙箱凭据与模型配置保持完好。
- **进程启动与授权注入**：
  - 启动后应用进程 PID：`11248`
  - 经 `/home/authorize_setting` 路由与特权抽屉显式点击「验证 Root 授权」，重新注入进程级 grant 缓存，界面与底层状态即时恢复 `已授权（Root）`、实测 `UID: 0`。

---

## 2. Provider 配置与连通性验证

- **端点**：`https://zipper.cc.cd/v1`（严格保留现有 HTTPS 凭据，全流程脱敏）
- **绑定模型**：`deepseek-flash`（绑定至 `scene.dispatch.model`）
- **可用性探针（`verify_bound_provider`）实测**：
  ```json
  {
    "success": true,
    "profileId": "custom-k3",
    "modelId": "deepseek-flash",
    "checks": [
      {"wire": "chat_completions", "available": true, "code": 200, "message": "OK"},
      {"wire": "responses", "available": false, "code": 503, "message": "找到了候选提供商，但无法为本次同步请求构建本地执行计划"},
      {"wire": "anthropic", "available": true, "code": 200, "message": "OK"}
    ]
  }
  ```

---

## 3. 端到端只读冒烟执行实测证据（End-to-End Smoke Evidence）

在新建的独立合成会话（ID: `10`）中发送只读指令：
> `只读验收：仅调用 android_privileged_action，action=diagnostics.getprop，arguments={"name":"ro.build.version.sdk"}。不要调用其他工具或修改任何设置。请报告真实工具结果，不能仅凭已有知识回答。`

- **分发时间戳**：`2026-09-20 00:30:40`
- **时延与性能**：
  - 首流响应：约 4 秒
  - 工具完成：约 6 秒
  - 全轮响应完成：总耗时仅 **11.0 秒**，网络与推理流式输出顺畅无阻。
- **底层原生审计日志（非模型复读凭据）**：
  ```text
  09-20 00:30:45.942 11248 11277 I [Omni]PrivilegedCommandExec: Privileged audit: action=diagnostics.getprop, success=true, code=ok, exit=0
  ```
  - `PID`: `11248`
  - `action`: `diagnostics.getprop`
  - `success`: `true`
  - `code`: `ok`
  - `exitCode`: `0`
  - 权限来源：Root `su`
- **UI 界面渲染卡片事实**：
  - 动作气泡：`$ android_privileged_action --action diagnostics.getprop --arguments '{"name":"ro.build.version.sdk"}'`
  - 物理返回值：`36`
  - 状态：`成功`
- **Agent 最终回复**：
  > 只读验收已完成。
  > **工具调用结果（真实返回）**
  > • 工具：`android_privileged_action`
  > • action：`diagnostics.getprop`
  > • 参数：`{"name":"ro.build.version.sdk"}`
  > • 实际执行命令：`getprop ro.build.version.sdk`
  > • success：`true`，code：`ok`，exitCode：`0`
  > • 权限来源：`root`（source: `su`）
  > • 返回值：`ro.build.version.sdk = 36`
  > 结论：设备 Android SDK 版本为 36（Android 16），无 stderr 输出，未被策略拦截（blockedByPolicy=false），未要求额外确认。
  > 本次仅执行了该一条只读动作，未调用其他工具，也未修改任何设置。

---

## 4. 基线设置复验与现场状态（Final Device State & Ledger）

1. **系统基线设置完整性**：
   - 独立 adb 实时采样：`adb -s b5a43b69 shell settings get system screen_off_timeout` 输出稳定为 **`300000`**（与初始基线完全一致，未发生变动）。
2. **应用与特权持久性**：
   - 冒烟完成后返回 `/home/authorize_setting` 页面确认，特权状态稳固保持在 `已授权（Root）`（实测 `UID: 0`）。
3. **会话与临时产物**：
   - 会话记录完整保留在设备本地数据库中。
   - 所有临时 dump/xml 文件已全数删除，未触碰 Flutter SDK。
4. **范围与局限说明**：
   - 本次冒烟严格限定为单轮只读诊断（`getprop`），未执行任何高危变异动作或持久会话。
