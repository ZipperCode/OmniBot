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

## 整轮取消

用户终止一个正在执行的 Turn，后续模型决策与动作不再继续；该轮由官方 ACP 取消终态结束，不等同于工具报错或正常完成。

## 工具中断

停止一个已准入的工具调用；作用范围是该工具，而非自动取消整个 Turn。工具中断结果不能替代整轮的官方终态。

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

用户保存的子任务职责、提示词、可选专用模型与工具权限约定；模板是可复用的配置，不代表已经启动的子任务。
工具约定只能收窄父任务和基础角色已有的权限，不能授予新的能力。

## 默认子 Agent 模型

供未指定专用模型的子 Agent 角色使用的共享模型选择；未设置时使用小万当前模型。
角色的专用模型优先于默认子 Agent 模型，二者都由既有 Provider 管理认证与模型能力。

## 后台虚拟屏

Agent 在当前会话所属的独立显示区域内操作应用的执行空间；主屏不属于后台执行范围，应用启动或任务失败不改变这一边界。

## 虚拟屏观看

用户通过顶部提示、小窗或全屏查看后台虚拟屏内容的展示方式；观看本身不取消 Agent，也不把应用移到主屏。
_Avoid_：接管、移交主屏

## 移交主屏

用户通过明确的界面操作结束当前 Agent 控制，并将虚拟屏中的应用现场交到主屏继续使用；它不是 Agent 的失败回退方式，也不是虚拟屏观看的另一个名称。

## 虚拟屏留存

当前 Agent 回合结束后仍保留的后台虚拟屏与应用现场，供所属会话继续使用；留存不表示 Agent 仍在执行。

## 收起预览

用户将虚拟屏观看入口缩为顶部提示或边缘标签的展示操作；它不停止任务，也不关闭后台虚拟屏。

## 关闭虚拟屏

用户明确释放已空闲的后台虚拟屏及其中应用现场的操作；它不是停止 Agent 或收起预览的另一种名称。

## 场景模型

用户为某项能力指定的 Provider 与模型组合。主 Agent 负责对话、推理和工具调用；辅助场景不拥有另一条用户会话或独立的任务生命周期。

## 视觉理解

从图片中提取文字、内容、关系并回答问题的能力。它不等于 GUI 操作，也不等于图像生成；视觉辅助结果属于当前用户回合，不能替代原始图片或用户问题。

## 图像生成

根据用户描述产出图片的能力。图片输出与图片输入是不同的模型能力；能读图不代表能生图。

## 模型能力声明

Provider、模型目录或用户对模型可接受输入、可产生输出及工具使用能力的说明。未知不表示支持，也不表示不支持；用户的人工标记优先于自动元数据，不会改变服务端实际能力。

## 多屏采集

在同一后台应用的指定区域内逐页观察并汇总可见内容。采集结果保留阅读页序、重复条目与停止边界；达到限制或没有新增内容，不等于完整应用历史已经读完。

## 完成文字证据

当前观测中与任务声明匹配的可见文字。匹配只证明文字出现，不保证任意业务目标成立；没有这类证据的模型完成判断不属于已验证结果。

## 应用经验

已观察到的应用控件操作及其页面去向参考，供当前任务规划时选用。经验不保存输入内容，不代表自动授权，也不代替当前页面核验或参数化复用指令。

## 经验试用

新操作参考在后续独立任务中接受实际使用结果检验的阶段。已验证只表示该参考通过试用，不保证应用内所有任务；停用后不得继续注入规划，失败回退也不得重新启用用户已停用的版本。

## 待发箱

归属于一个 Conversation、尚未正式提交给 Agent 的后续请求集合。排队不是用户消息，也不表示 Agent 已准入或正在执行。

## 历史提交屏障

同一正式 Turn 的结果已经写入可恢复会话历史的确认。正式请求结束与历史确认同时成立，才允许自然投递下一条待发请求；取消回执、页面忙闲与完成文案不能代替它。

## 待发箱并发

**待发箱版本**：某个 Conversation 待发集合的单调递增版本，用于确认基于同一份列表发起的编辑、移除与恢复。条目自身的变化版本不代表整箱版本，也不能替代它。
_Avoid_：待发条目 revision

## 停止并发送

用户针对已观察到的会话与 Turn 明确停止当前请求，再优先提交选中待发项的操作。原请求正式结束前不执行后续项，其余待发项保持暂停。

## 受管文件变更

小万文件工具在已授权工作区内记录的文件变化及其恢复依据；外部命令或其他执行者的写入不因此成为可恢复变更。恢复仅针对该变化的文件状态，不撤销消息、命令或模块部署。

## 技能候选

从一个合格完成任务提炼、等待用户审核的技能建议资产。候选不是已启用技能，生成建议也不代表技能已被实际使用或验证。

## 技能试用

用户审核启用某一内容版本后，等待后续独立任务实际读取并检验的阶段。评价只归属于被使用的版本，不能沿用到已变更的内容。

## 框架加载证据

框架日志对指定模块与目标进程已加载的可核对记录。它既不是安装配置成功的别名，也不能证明目标应用的业务效果。
