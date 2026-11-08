# OmniBot domain vocabulary

## Conversation

A user-visible, durable history identified by `conversationId` and its
canonical conversation mode. It survives a Flutter rebuild and is the source
for restoring the chat surface.

## ACP session

The Agent-side execution context identified by `sessionId`. A session may be
reused for multiple prompts and may be restored independently of the local
conversation record.

## Turn

One prompt execution inside an ACP session, identified by `turnId`. A turn is
temporary execution state, not a durable conversation and not a replacement
for a session.

## Runtime snapshot

The current in-memory projection of a conversation while the UI is active. It
may contain streaming text, thinking, tool state, and an in-flight turn. It is
never allowed to replace a durable conversation with an empty or partial
snapshot merely because a page is rebuilding.

## Conversation history

The durable message projection read through the compatibility reader. Native
ACP history is preferred, while older local snapshots and storage aliases are
read and normalized before optional forward migration.

## Lifecycle transition

A change of conversation target or mode. It invalidates asynchronous work from
the previous target; late loads, updates, and persistence completions must not
mutate the new target.

## Model request context

A bounded, derived view of conversation history prepared by Xiaowan before each
model call. Compaction changes this view and its durable checkpoint; it does not
replace the user's complete history or create a new ACP turn. Complete files and
large tool outputs remain retrievable through the existing workspace artifacts.
UI pages are partial projections: routine persistence merges message identities.
Only explicit user history edits may remove records missing from a submitted view.

## Context budget ownership

The Provider owns model capacity. The Conversation owns the user's selected
context budget. Usage reports are observations, not configuration writes.
Refreshing model metadata or receiving usage must not overwrite the user's
setting. Request preparation respects the smaller applicable capacity/budget and
reserves output space; both ordinary and summary requests obey this boundary.
A missing, interrupted, truncated or over-budget summary never advances a durable
checkpoint. A UI repaint, partial page, or empty runtime is never a clear-history
instruction.

## 任务授权

用户对一个目标及其必要操作范围的授权；范围外的操作不属于该授权。
模块开发任务的必要目标进程、系统组件与整机重启属于已批准范围。

## 全权模式

用户手动开启并可随时关闭的授权模式；生效期间不逐项确认，包括高风险与对外行为。
它不是 Root 身份、设备解锁或外部认证，也不延长已撤销的权限或已停止的任务。

## 实际验证

针对指定目标及版本，观察用户要求的行为是否发生并保留证据。
命令被受理、编译成功、安装成功或模块配置读回均不能替代实际验证。

## 手机本地开发

在目标手机完成代码处理、依赖准备、编译、签名、安装和运行验证的工作过程。
云端模型可以提供推理，但电脑与远程构建服务器不承担日常执行。

## 等待人工认证

因安全锁屏、首次解锁、验证码或应用认证而暂停受影响操作的任务条件。
无需认证的后台工作仍可继续；等待不等于失败、完成或自动授权。

## 小万子 Agent（Subagent）

由小万分派、使用独立上下文完成自足任务的执行者；它的结果交回父任务，取消和授权不脱离父任务。
它不是另一条用户会话，也不是外部 Agent 的通用配置。

## 子 Agent 角色模板

用户保存的子任务职责、提示词与工具权限约定；模板是可复用的配置，不代表已经启动的子任务。
工具约定只能收窄父任务和基础角色已有的权限，不能授予新的能力。
