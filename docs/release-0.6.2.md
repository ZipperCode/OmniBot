# 0.6.2

Android versionName: 0.6.2; versionCode: 12.

## Changes

- Repair terminal environment detection and Ubuntu extraction/installation paths, and validate managed Harness installation before reporting readiness.
- Improve provider model discovery, explicit refresh, selection and reasoning configuration through the existing ACP session owner.
- Preserve completed Codex messages and Claude Code terminal output through the supported adapter boundary.
- Restore context compaction, large-history database protections and cancellation safeguards without introducing a second Agent lifecycle.
- Repair completed-run accessibility so the fold control can be activated independently of the answer; preserve the established chat presentation.
- Add regression coverage for installation, provider wires, output, model selection, history and memory handling, plus reusable device journeys.
- 新增共享待发箱：运行中排队、独立编辑与移除、停止当前任务并发送、暂停后显式恢复。投递同时等待官方结束与历史提交；失败、取消和重启不自动重发。
- 结果区提供实际产物、受管变更、所属现场与技能候选入口；预览返回保留当前输入。现场缺失或归属已变化时拒绝打开其他任务画面。
- 小万子代理默认并发 2、最大 4，按实际工具能力对同工作区的整个子任务加共享读或独占写租约。
- 小万文件工具支持受管快照与预览后确认恢复；冲突、敏感路径、符号链接、工作区忙和配额边界均不绕过。外部命令不承诺可恢复。
- 小万合格任务可后台生成技能建议；审核前隔离，审核后按不可变版本试用，后续独立任务真实读取该版本并完成后才可反馈。反馈与版本恢复完成后关闭弹层，再刷新列表，避免页面就绪门禁互等。
- LSPosed 分开展示安装配置、框架加载证据和业务效果；快捷操作只准备会话草稿，不能绕过发送与授权。
- 修复后台待发任务已落库但当前聊天页缺答复：宿主历史通知不再被本地保存回声抑制，合并只由共享快照所有者处理，保留读取期间新轮次的用户消息、增量及已完成答复。
- 修复官方流式增量把重复字符、重复行和空白误判为快照回放而删除的问题；事件身份防重与已有历史恢复回放游标保留，不再凭正文相似度截断实时增量。

## Known limitations

- OmniBot official gateway access from Claude Code has returned HTTP 403; successful authentication/initialization is not proof of a successful chat. This release does not claim that gateway authorization issue is resolved.
- Full real-API acceptance across every Harness remains incomplete. Earlier live tests exposed DeepSeek reasoning-wire and Codex auxiliary-model compatibility gaps.
- Xiaowan reasoning was observed and could be expanded after restart on the emulator; the zero-second reasoning label and the user's original phone-only symptom are not declared fully resolved.

验证结果记录在本次工作树验收记录中；全量 Flutter、Android、WebChat 与实体设备旅程分项列示。本轮清理本次改动文件中可归属的 Dart warning，剩余分析诊断仍按基线报告。

本轮告警复核沿用已有 Flutter 全量分析输出；没有重跑全量分析。

## 本次设备验收边界

- 受管变更已在真实应用验证外部修改冲突保护与明确确认恢复；技能候选已验证审核、独立读取、正式完成、正反馈持久化与弹层关闭。
- 待发箱已在真实应用验证运行中入队、编辑、移除、自然顺序投递与当前页答复；产物预览和变更查看返回后草稿保留。独立停止发送旅程确认取消原 Turn、优先投递所选项、其余暂停及用户显式恢复，18 步通过。
- LSPosed 专用验证目标未安装，旅程在前置检查阻断，零部署；未以框架日志代替业务效果验收。
- 现场真实控制缺少系统无障碍权限；小屏、横屏、大字和减弱动效仍缺可用验证环境。未修改实体机系统设置或安装系统镜像。
- 同模式切换历史对话沿用当前行为清空输入；A/B 合成会话确认草稿不串入另一会话，但未验证旧会话草稿恢复。结果预览返回仍保留当前草稿，未新增持久草稿存储。
- WebChat 测试、类型检查和生产构建已执行；未启用对外监听服务，也未将未经验证的真实 HTTP 管理链路列为通过。

### 最终工作树验证

- Android 单元测试与构建：app 2161、baselib 355、androidgui 39 项通过（共 2555 项）；`assembleDevelopStandardDebug` 离线构建成功，未安装 WebChat/Pi 依赖。
- Flutter 全量测试：2189 项通过；先前全量 analyzer 报告 547 项诊断（75 warnings、472 infos、0 errors），当时进程非零。本轮复核记录的 19 条改动文件 warning 均已清理；对 8 个相关源码/测试文件定向分析为 0 warnings、65 infos。未重跑全项目 analyzer，最终全量诊断总数未重新确认。
- WebChat：46/46 测试、TypeScript 类型检查与 Vite 生产构建通过；pnpm 配置弃用警告未改。
- 真实 Debug APK SHA-256：`8efa6dff1d28eda0df065418c53b7f11559b94b9313448278bf023d888412222`。待发自然出队与结果返回 41/41、停止指定任务并显式恢复 18/18、重复字符完整性 4/4、工作区冲突/确认恢复 26/26、技能审核/试用/真实使用反馈 24/24 均通过。
- LSPosed 专用目标缺失，专用业务效果验收被安全前置拒绝，未安装模块、未改作用域、零部署。现场无障碍权限未启用；未改设备设置。
- 本轮结束时工作树有 115 个已修改文件、87 个未跟踪路径，均未提交；当前分支 `main` 比 `origin/main` 超前 6 个提交。工作区还含此前已存在的用户/并行改动与构建产物，本次仅在具体验证的 Dart 告警位置做了小范围清理，未清理、覆盖或暂存其他内容。
