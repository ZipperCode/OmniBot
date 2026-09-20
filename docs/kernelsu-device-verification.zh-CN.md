# 系统高权限真机验收记录

## 范围与授权

用户明确授权 ADB 操作，并在获知签名不一致与本地数据丢失风险后允许卸载旧版、安装测试版。操作仅针对 OmniBot，未修改 SELinux、挂载配置、root 隐藏规则或无关应用。

## 首轮观察结果（管理器授权前）

- 小米 2211133C，Android 16/API 36；ADB shell UID 2000。
- 旧版 0.6.2 与测试 APK 签名不同；经授权卸载后成功安装 debug 0.6.3/versionCode 16。新应用 UID 为 10413。
- 权限页面默认“未配置”；选择直接 Root 后显示“待验证授权”；点击验证后显示“su 不可用”，未观察到崩溃。
- 关闭抽屉后父页面状态同步；测试结束恢复“未启用”。
- 未观察到 root 授权弹窗。该观察本身不能证明没有启动进程，也不能证明设备没有 root。

## su 可见性差异

定向只读检查发现：ADB shell 可见 `/system/bin/su`，且其 inode 与 `/system/bin/sh` 相同；应用进程的 `/proc/<pid>/root/system/bin/` 视图及 `run-as` 上下文不可见该 su 路径，但可见 sh/id。`run-as` 执行 id 返回应用 UID 10413；它使用的 SELinux 域不同于常规应用进程，不能替代应用 root 授权验证。

当前代码仅在全部候选 `ProcessBuilder.start()` 均抛 IOException 时返回 `su_unavailable`。实际 IOException/errno 未捕获，路径不可见支持 ENOENT/命名空间差异的推测，但不足以判定具体 root 方案、隐藏机制或设备是否已 root。

## 管理器授权后的复验

- 用户确认已授予 OmniBot root 权限，并允许继续控制手机。管理器版本来自设备侧核验：KernelSU Next（`com.rifsxd.ksunext`），v3.3.0/versionCode 33214；授权状态另由用户陈述和应用探针结果支持。
- 仅停止并重启 OmniBot 一次后，在应用内选择直接 Root 并点击验证。designer 观察报告：UI 显示“已授权（Root）”与动态 `UID: 0`，父页面同步；未归档截图或原始日志，Oracle 未独立复核该 UI 观察。
- 这是应用自身探针的 UI 证据，不是 ADB shell 的 su 执行结果。授权前失败、授权并重启后成功，不能单凭此时序确定 KernelSU 内部挂载或 Zygote 实现机制。
- 用户随后批准配置指定 HTTPS Provider 与 `k3` 模型；已通过既有配置入口完成，并在模型选择界面确认 `k3`。凭据不写入本记录。
- 模型可用性检查超时，设备对目标域名的 TLS 连接观察到 reset。现有证据不能归因到特定网络审查机制、服务端或代理。未取得真正的 Agent 特权工具调用结果。
- 最近检查时手机保留直接 Root 已授权状态，模型为 `k3`；未清除数据、改动 SELinux 或 root 管理器高级配置。

## 代理开启后的只读工具链复验

用户开启手机代理后，designer 执行报告：原 HTTPS Provider 可用性检查返回 HTTP 200，模型保持 `k3`；未切换备用 IP，未发送明文凭据或关闭证书校验。备用 IP 的无认证 HTTPS 探测报 TLS 协议错误，该错误本身不足以证明服务协议。

新建测试对话（本地 ID 1，标题“新对话”）仅请求 `android_privileged_action`，参数为 `action=diagnostics.getprop`、`arguments={"name":"ro.build.version.sdk"}`。报告包含模型工具调用事件、成功工具卡片及原生审计摘录：

```text
09-19 20:07:12.866 23758 29856 I [Omni]PrivilegedCommandExec:
Privileged audit: action=diagnostics.getprop, success=true, code=ok, exit=0
```

工具卡片输出 `36`。这不是仅依据模型最终回答作出的判断；但审计摘录不含 source/authority 字段，直接 Root 来源与 UID 0 来自该进程的授权 UI 观察及当前路由配置，不能说此行日志独立证明执行身份。临时截图未归档，证据为 designer 执行报告及保留在手机中的测试对话。

未报告本次只读动作出现确认卡片；`diagnostics.getprop` 不属于策略要求确认的动作，符合预期，但不能将此次成功扩大为高风险操作审批已验收。复验后应用仍显示 Root UID 0，Provider 保持原 HTTPS 地址及 `k3`，测试对话保留。

## 尚未验收

- 真正 root 会话的取消、来源切换、权限撤销与子进程清理。
- KernelSU App Profile、SELinux 或厂商系统下的真实行为。

Gate 4 attempt 3：`SCOPED_DEVICE_ACCEPTANCE_PASS`，仅覆盖本机应用 root 授权及单次真实只读特权工具调用。构建与模拟测试结果另见 `kernelsu-system-capabilities.zh-CN.md`。本记录不代表所有 KernelSU 版本或全部系统操作兼容，不证明真实 root 会话的完整生命周期，亦不覆盖 capability manager 来源切换并发与 grant 生命周期。
