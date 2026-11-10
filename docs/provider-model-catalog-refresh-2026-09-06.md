# Provider 模型目录刷新：已验证结果和未完成项

## 统一职责

Provider 是可访问模型目录的来源；AgentConfigAdapterRegistry 将同一目录映射到各程序官方配置；LocalAcpRuntime 通过协商的 ACP 会话能力读取配置、设置模型和处理完成。界面沿用一个模型与参数卡片。普通启动和发送不新增网络目录请求，点击模型才实时刷新；成功目录沿用已有存储用于下一次启动，不用存储结果代替显式刷新。

## 公共层改动（未完成全量设备验收）

- 显式刷新按 shared Provider + configAdapterId 能力分派；小万复用同一个 Provider 获取函数，避免重复网络请求。
- LocalAcpRuntime 按 loadSession 协商能力处理配置刷新，复用现有回放抑制和会话注册，不增加重开会话或重试协议。
- Claude Code 的 availableModels 接入 Provider 目录并保留 settings.json 其他字段；DSH 的官方 Provider patch 支持多模型；Codex/OpenCode 沿用已有目录写入适配器。
- 被动 model/list 投影同一份成功发现的目录，不再固定只返回 Dispatch 当前模型。
- Android 单元检查：AgentConfigAdaptersTest、AgentAdapterCatalogTest、AgentRuntimeManagerConfigTest、AgentWebRuntimeTest、ModelProviderConfigStoreTest 通过。

## 实际失败，不能当作通过

`scripts/verify-harness-model-catalog.mjs` 消费 Android 测试生成的初始/刷新两份配置，使用真实安装的程序和本地 HTTP 夹具。

- Claude Code、Codex、OpenCode：启动时接入两个 Provider 模型并切换请求成功；写入第三个模型后，同一 session/load 仍返回旧选项。额外等待 2 秒后 Codex/OpenCode 结果相同。不能用修改界面选项来掩盖此失败。
- Claude Code 0.74.0 的 getOrCreateSession 在会话参数指纹相同时直接返回既存 configOptions。Codex 1.10.0 和当前 OpenCode 也保留运行时目录。
- DSH：多模型请求路径可执行，但官方 ACP 不实现 session/load；必须依据 initialize 协商结果处理，不得伪造该能力。
- 官方 Codex app-server 生成的 ModelListParams schema 只有 cursor/includeHidden/limit，没有强制刷新参数；不能虚构 refresh 参数。

尚未通过运行中新增模型、所有助手重启恢复及多助手真机切换的完整验收。公共层改动尚未安装到手机，不应据此声明所有助手已修复。

仓库禁止新增 renew/reopen 路径。已向用户询问是否允许复用现有重连流程，在空闲时重新加载助手并恢复原对话，或保持运行中不重载、新模型下次启动生效。未收到选择前不引入自动重载。
