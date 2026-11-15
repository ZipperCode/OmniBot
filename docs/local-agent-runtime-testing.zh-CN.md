# 本地 Agent/ACP 测试集

统一入口是：

```bash
scripts/test-agent-runtime.sh
```

它会自动运行：

- Node 协议与 Provider 请求构造测试；
- Android/JVM 的 ACP 状态、Provider fallback、Harness 准备测试；
- Flutter Agent 页面与运行时服务测试。

如果需要真实联网调用 Provider，只在当前 shell 注入测试 Token，然后追加 `--live`：

```bash
export OMNIBOT_TEST_API_KEY='测试 token'
export OMNIBOT_TEST_BASE_URL='https://your-provider.example/v1'
export OMNIBOT_TEST_MODEL='glm-5.1'
scripts/test-agent-runtime.sh --live
```

Token 不会写入仓库或打印。真实 smoke 执行一次 `/models` 和一次短的非流式 `/chat/completions`，最大输出 1024 tokens；正文为空或仅有思考不算通过。

没有 Token 时，默认只运行本地测试；显式使用 `--offline` 可以强制跳过真实 Provider 请求：

```bash
scripts/test-agent-runtime.sh --offline
```

当前测试集覆盖的关键行为是：检测已安装 Harness 不会触发 npm/node-gyp 下载；检测会把可运行 Harness 标记为 `online`；Provider `/models` 暂时不可用时仍保留已绑定模型；Agent 页面检测和显式 Harness 初始化不会混用。

不要把 Token 写入 `.env`、脚本或提交记录。建议通过 shell profile、密码管理器或 CI secret 注入 `OMNIBOT_TEST_API_KEY`。

## 长期验证规则

### Kimi Code 退役说明（2026-11-09）

Kimi Code Agent/Harness/Web 专属能力与验证脚本已下线移除，内置 Harness 回归集与默认测试对象收敛为 Codex、Claude Code、OpenCode（Pi 按既有能力）。普通 Kimi / Moonshot 模型 Provider 仍受支持。

本次离线验证：Flutter 的 Agent 页面、侧栏、共享 reducer、运行时服务与普通模型 Provider 聚焦回归共 602 项通过。维护脚本的卸载隔离与草稿归属回归在设备现有 Alpine 环境中 43 项通过，未安装依赖，临时测试目录已清理。Windows 宿主直接执行同组 Node 回归为 10 项通过、33 项失败，原因涉及 POSIX 路径、包装脚本与无扩展名的模拟 adb；本次不扩展其宿主兼容范围。

原生聚焦回归 16 个测试类共 275 项通过，失败、错误、跳过均为 0；退役存储用例覆盖旧 Profile 不再激活、会话与对话 owner 不改写、会话配置保留和重新打开后的状态。使用已有依赖离线运行 `:app:testDevelopStandardDebugUnitTest` 与 `:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`，排除 `installWebChatDependencies`、`installPiAcpDependencies`，结果 `BUILD SUCCESSFUL`。

保留数据覆盖安装后，真实应用的 Agents 目录从 6 项变为 5 项，Kimi Code 卡片消失，其他预置 Agent 保留；打开 Claude Code 配置页后未保存修改。设置 → 终端环境的清单从 14 项变为 13 项，滚动检查完整 AI Agent 分组至 SSH 分组，Kimi Code 安装项已移除。安装与本地构建 APK 的 SHA256 一致：`c0c1324a153a219080b873dcde9ce93ac17697451319ad2a89e10ca26e703434`。

未发起模型请求，未卸载设备现有包，未删除或改写用户历史；真机未读取历史数据库，历史保留由上述持久化行为回归验证。普通 Kimi Provider 本次未作真机推理验收。Flutter 聚焦分析无错误，保留既有 2 条 warning 与 6 条 info；仓库未配置可用 LSP。未执行发布全量门禁。

### Agent 身份与草稿归属

维护用例必须明确区分本机 `codex-acp` 与远端 `codex-remote`。`status`、配置/模型读取等宿主请求指定 `agentId`；发送沿当前 Conversation/Session owner 进入正式 ACP `session/new` / `session/prompt`，不先调用全局 `connect` 或 `agent/select`。只打开设置、查看目录、切换未发送草稿不能创建聊天历史。

统一测试入口包含 `AgentConversationModePolicyTest`、`LocalAcpRuntimeOwnershipTest`、`AgentSessionBindingOwnershipTest`、`WebConversationCreationTest` 和草稿生命周期测试，覆盖缺身份拒绝、持久 owner 冲突、显式远端身份及账号切换前的创建校验。Provider 和长期记忆存储不属于这项迁移。

`xiaowan-harness-switch-draft.en.json` 通过当前屏幕的 Agent 名称和 `FlutterSharedPreferences` 中既有 `last_visible_conversation_target` 检查未发送草稿的 owner，同时要求 `isNewConversation=true` 且没有 Conversation ID。发送后及重启后通过 `assert-agent-prompt-owner.py` 核对实际 Conversation → Session → Agent 与唯一 Turn。旧 `selected_profile_id` 只应被迁移删除，不能作为验证通过的证据。

### 会话输出是必过项（2026-09-07）

真实 API 全链路入口（使用现有 LLMTHU 环境变量或 `OMNIBOT_TEST_*` 覆盖）：

```bash
export OMNIBOT_TEST_MODEL='GLM-5.1'
export OMNIBOT_TEST_SECOND_MODEL='GLM-5.2'
scripts/test-agent-runtime.sh --live-harnesses /path/to/disposable-harness-installation
```

`verify-live-harness-conversations.mjs` 默认逐一测试 Codex、Claude Code、
OpenCode。它复用原生生成的配置及透明本地转发器，模型回答
来自所配置的真实 API。每个 Harness 使用独立临时目录和会话，结束后清理本次
临时凭据与测试文件；不会更改手机配置或用户工作区。

每个 Harness 的测试包括：非空且符合测试要求的正文；同会话记住随机口令；
实际工具写文件并产生可读取 stdout；切换第二个 Provider 模型并检查实际请求；
官方声明的思考选项及请求值；进程重启后按官方能力恢复同一会话；官方取消和
取消后的下一轮对话。官方未声明思考设置或 session/load 时明确记录不支持，
不得伪造选项或另建恢复机制。这种记录不意味着相应能力已通过实测。

API 错误、缺少正文、错误模型、工具未执行、输出不可读均导致非零退出。
前置失败导致未执行的项目逐项标记 `notRun`，不会记作通过；各 Harness 互相
独立，某个失败仍会继续测试其他 Harness。结果 JSON 只保留断言结果、模型、
参数、状态与耗时，不输出密钥或完整对话。服务商不兼容会保留为失败结果，不
会自动改端点、重放用户请求或把本地模拟响应当成真实输出。

这验证官方 CLI 至真实 API 的链路；手机 UI、Android 工具环境及设备重启仍
需要下述设备验收。不能将主机进程重启测试冒充手机重启。

### 可重复的真实设备操作

`scripts/verify-agent-user-journey.mjs` 从版本化 JSON 场景执行实际界面操作，
复用现有点击和输入脚本。它不直接调用 ACP、不修改数据库，也不伪造回复。
手机使用已配置的真实 API；凭据不进入场景文件。当前场景目录为
`scripts/fixtures/agent-user-journeys/`，前置页面和所需模型写在每份场景中。

```bash
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs \
  DEVICE_SERIAL scripts/fixtures/agent-user-journeys/xiaowan-model-switch.zh.json \
  /tmp/oob-device-model-evidence
```

真机需要显式启用 `OOB_ALLOW_PHYSICAL_DEVICE=1`；模拟器传明确 serial 即可。
执行前确认没有用户正在输入或生成的回合。同一设备的构建安装和界面操作必须
由一个任务独占，另一任务先交还设备，防止安装中断请求造成错误结论。

场景包括真实搜索、点击模型、输入发送、正文验证以及重启后的继续发送。
每次执行给测试口令加唯一后缀，旧历史不能误算本次成功；用户气泡和输入草稿
不能算助手正文。每步保存截图、耗时及结果，失败停止，不自动重发。
UIAutomator 暂时无法读取窗口时，只在当前步骤期限内重复读取，不使用旧坐标。
截图仅存调用者指定目录，可能包含已有历史，不自动加入 Git。

这些场景目前在逐项真机验收中，**尚未覆盖或通过所有 Harness 的完整 demo**。
`xiaowan-model-switch.zh.json` 已走到实际发送，但回复阶段遇到设备读取失败，
不能算整个场景通过。环境安装、新功能/MCP、全部 Harness 切换和恢复的端到端
场景仍须补齐，不能以 CLI 测试或本地单元测试替代。

修改 Harness、ACP、模型配置或聊天展示后，只有下面的输出回归和相关设备验收
通过，才可标记对应功能验收通过。HTTP 200、初始化成功、工具卡片显示成功或
单独收到 `end_turn` 都不能代替实际输出。

复用本文件的统一入口，传入隔离的官方 CLI 测试安装目录：

```bash
scripts/test-agent-runtime.sh --offline --harnesses /path/to/disposable-harness-installation
```

该目录的 `node_modules` 应安装与 `app/src/main/assets/acp/agents.json` 一致的
Codex、Codex ACP、Claude Code ACP 及其依赖。测试不自动下载依赖、不使用真实
凭据。Codex 用例会把应用随附的版本校验补丁应用到该隔离目录，不得指向日常
使用的 CLI 安装。配置夹具由本次 `AgentAdapterCatalogTest` 生成。

| 必过行为 | 持久测试与断言 |
| --- | --- |
| Claude Code 输出会话正文 | `verify-installed-harness-adapters.mjs` 的 `conversation` 场景：第一轮精确得到 `OK`，同一 ACP session 的第二轮精确得到 `FOLLOWUP_OK`；两轮都必须 `end_turn` |
| Claude Code 接续上一轮 | 第二轮真实发出的 Anthropic 请求必须包含第一轮 assistant 历史；其他 session 的正文导致失败 |
| Claude Code 工具结果可读取 | `verify-claude-terminal-output.mjs` 使用生成的能力配置调用官方转换器，成功/失败终端输出均保留为标准 ACP text；不得声明未消费的私有输出扩展 |
| Codex 不丢正文、不重复 | `verify-codex-completed-messages.mjs`：完整终态正文、部分增量、正常增量都必须精确得到一次 `OK`；错误不得混入正常答案 |
| 聊天页实际渲染、历史保留 | `chat_message_list_test.dart` 的 Claude Code 用例：共享 reducer 接收两轮 ACP 正文，完成后、重复通知后及序列化重新挂载后，两个回答都各显示一次 |

三种 Claude Code Provider 配置均测试相同官方 Messages wire；Codex 两种 Provider
wire 配置均测试官方 Responses wire。这是**真实官方 CLI + 确定性本地 HTTP
夹具**的自动化测试，不等于真实模型推理或手机端到端验收。

未传 `--harnesses` 时，脚本明确输出 `Harness output acceptance INCOMPLETE`，
不得据此声称 Harness 验收通过。缺少依赖、配置夹具、正文、历史或必要终态，
测试应失败，不准通过跳过/放宽断言消除失败。使用 `--skip-*` 的结果只涵盖实际
执行的部分，不能替代完整门禁。

涉及设备行为的发布验收还需记录：APK hash/设备、真实正文、同会话追问、工具
stdout/错误、离开返回与重启后的历史。每项标明通过/失败/未执行；必要项未执行
就是验收未完成。CLI 回归与历史序列化测试不能冒充手机重启测试。

后续修复必须先证明不变量，再修改实现；单个现象不得触发全局重构。验证按
“Provider/协议 -> Harness 适配器 -> ACP runtime -> UI/模拟器 -> 真实在线端点”
分层进行，每一层都要记录通过、失败、未覆盖和失败归属。

### 不可更改的不变量

- 一个用户发送对应一个逻辑 ACP `turnId`；网络重试不能创建第二个用户可见回合。
- `conversationId`、`sessionId`、`turnId`、`messageId`、`toolCallId` 是归属键；旧事件不得借用当前回合。
- Conversation history 是用户可见事实源；ACP echo、replay、reconnect 只能幂等合并。
- Agent 生命周期只走官方 ACP session/turn/update/cancel/close 语义；不得恢复私有 stream 协议、第二 reducer 或第二状态机。
- Provider 能力必须由配置/目录或官方协议证明；适配器不得猜测、静默换路或吞掉终态错误。

### 已与官方对齐的部分

- ACP 的 `session/prompt -> session/update -> PromptResponse` 生命周期及 `session/cancel`。
- Codex 的 Responses 配置、`env_http_headers` 和独立认证文件入口。
- Claude Code 的 Anthropic Messages endpoint；DeepSeek 官方 Anthropic 路径只在对应 Provider endpoint 上使用。
- OpenCode 的官方 provider 配置结构和环境变量引用。
- Provider 自定义 Header 由共享 Provider 编辑并映射到各官方 Harness surface，不复制成第二事实源。

### 可以继续调整的部分

- Provider 的在线超时预算、重试次数和测试用例规模，但必须保持“输出后不重放、不换路”。
- Harness 配置生成器的字段映射，只能根据对应版本的官方配置契约和真实失败修改。
- 测试脚本的在线覆盖范围；在线测试不得成为默认 CI 的不稳定依赖，也不得输出凭证。
- UI 的状态呈现与日志细节，只能投影已被 runtime 证明的状态。

每次提交的测试报告必须单独标记：已验证不变量、尚未验证的不变量、属于上游环境
还是本地代码、可修改项、不可修改项，以及哪些行为已经对齐官方且以后不得重新引入旁路。

## GUI 权限返回后的同回合继续

- 修复前真机复现：`vlm_task` 缺少悬浮窗权限，开启并返回后权限卡显示“已开启”，但所属 Turn 已提交 `end_turn`，GUI 目标未执行。
- 维护回归：`VlmPermissionWaitRegressionTest` 覆盖未授权挂起、部分授权、完整授权和取消后迟到授权；`VlmToolHandlerDisplayAdmissionTest` 保持主屏与陌生显示编号立即拒绝。共享 reducer 与权限卡测试覆盖同卡恢复、用户问题保留、官方取消后的历史卡和异步权限检查期间取消不跳转。
- 本次聚焦验证：原生 129 项、Flutter 352 项通过；离线 `assembleDevelopStandardDebug` 成功，排除 `installWebChatDependencies`、`installPiAcpDependencies`，未安装依赖。定向 Flutter 分析无 error / warning，保留 67 条 info；仓库未配置 LSP。未运行全量发布门禁。
- 真机覆盖安装后，从实际聊天发送只读 GUI 请求，在后台虚拟屏打开系统设置。未授权返回时原工具卡非终态、原 Turn 无终态且“停止”可用；开启权限后未发送任何继续消息，原调用自行完成并返回设置页结果。另一独立请求在权限等待中停止，恢复权限后仍显示“任务已取消”，未重新执行。测试结束恢复原悬浮窗授权，未修改无障碍配置，保留合成验证对话，不删除历史。
- 验收 APK SHA256：`b1799b34fe38a2c03421141a4863eba0b78b3e4173afd5039073eb26c8af7b0d`。取证时 `DebugAgentDatabaseSnapshotReceiver` 广播曾超时并触发系统 ANR，等待后应用恢复；授权后结果以真实聊天交互与正文核验，最终数据库取证未完成，不将广播失败算通过。未验证全部 ROM、进程被杀后的等待恢复或 Root / Shizuku 配置往返自动继续。

## 小万 Subagent 配置与执行

入口包括 `Agents → 小万 → 配置` 和小万对话内的 `subagent_manage`，共用 `XiaowanSubagentConfigStore` 与 revision。工具动作是 `list`、`models`、`create`、`update`、`delete`、`configure`；写动作必须携带 `list` 返回的 `expectedRevision`，只修改指定字段，陈旧版本拒绝写入。模型引用可成对设为 null 恢复继承；`allowedTools: null` 继承基础权限，`[]` 不开放工具。其他非 nullable 字段传 null 会被拒绝，避免意外重新启用或放宽权限。

目录在管理工具成功后刷新，每次分派读取一份已提交配置；同一用户 Turn 可创建后立即执行，正在运行的子任务不受后续配置更改影响。目录变化使旧 Provider 用量基线失效，下一轮按新工具定义重新计算上下文预算。Provider/模型只保存引用，`models` 仅投影本地缓存与手工模型的 id/name/model ids，不输出地址、认证或请求远端目录。其他 Harness、公共设备 MCP 与子代理均不能获取管理能力。

配置包括总开关、默认子代理模型、思考档位、内置与自定义角色提示词以及工具允许/禁止列表。每个角色可保存独立 Provider/模型引用，完整组合优先于默认子代理模型；两层均未配置时继承父 Agent 当前模型。清空角色模型恢复继承，Provider 与模型必须成对设置或成对留空，显式配置失效时不静默回退。只解析本批实际使用的模型，全部角色均有专用模型时，不使用的失效默认 Provider 不阻断执行。基础角色权限与父目录是上限，空允许列表表示无工具，所有子角色均禁止递归分派。配置损坏、禁用角色、已删除 Provider 与过期 revision 明确报错，不重新启用较宽的默认权限。

维护入口包含 `SubagentRuntimeExecutionTest`、`XiaowanSubagentConfigBehaviorTest`、`SubagentConfigToolHandlerTest` 和 `SubagentConfigurationJourneyTest`。覆盖原子配置补丁、陈旧 revision、损坏配置、nullable 权限边界、模型安全投影、同一父回合创建后立即执行与工具预算刷新。`AndroidDeviceMcpServerTest` 验证外部调用不可读写小万子代理配置；`AgentOrchestratorTest` 保留父审批及取消边界回归。

基础功能实际执行结果：

- 原生聚焦回归 108 项通过；`assembleDevelopStandardDebug` 成功，真机覆盖安装成功。构建未安装依赖；Windows 跨盘缓存使用命令行 `-Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process`。
- Flutter 设置页、工具详情卡片与共享 reducer 合计 249 项通过；修改角色表单滚动与模型选择器后，设置页 13 项再次通过；改动文件 `flutter analyze --no-pub` 无问题。
- 真机从旧版小万配置仅显示启动命令复现缺口；新版总开关关闭后保存、退出重开仍为关闭。新增 `smoke-reviewer`（探索者基础、无工具）后保存并重新加载，角色与权限保留。
- 默认继承模型和显式配置 `deepseek-v4-pro` / `low` 两条路径均从聊天界面实际调用一次 `subagent_dispatch`，并发两个角色分别得到 `ROLE_42`、`PLAN_42`；工具详情中的两个结果均为 `completed`，`toolCalls` 为空，主回复包含两项结果。
- 真机运行子任务期间点击“停止”，父任务显示“任务已取消”；最终工具详情保留 `subagent_cancelled` / `status: cancelled`，而不是被旧进度覆盖。同一会话下一轮实际返回 `RECOVERY_OK`，未重放取消的子任务。
- 基础功能验收安装 APK 的 SHA256 为 `d99fd6ba515268d229983f3b98c4ca44e2f9056b52ce483149914722c8b0099e`；该版本取消后实际返回 `FINAL_RECOVERY_OK`。验收创建的自定义角色已通过设置页删除，模型与思考恢复跟随主 Agent；保留测试对话记录，不删除用户历史。
- 未以浏览器自动化、HTTP 200 或仅工具卡片存在作为验收；未修改长期记忆、未输出凭据。审批允许/拒绝与取消终态的细分边界由上述原生行为测试覆盖，发布全量门禁与全部 ROM 未执行。

### 逐角色独立模型追加验收

- Flutter 在 `ui/` 执行 `flutter test --no-pub test/features/home/pages/agent/agent_config_page_test.dart test/agent_tool_summary_card_test.dart test/services/agent_event_reducer_test.dart`，251 项通过；严格拒绝角色半配置的修正后，单独复验 `agent_config_page_test.dart`，15 项全部通过。两轮有重叠，不相加计数。
- 最后对 `agent_config_page.dart`、`xiaowan_subagent_settings_editor.dart` 与 `agent_config_page_test.dart` 执行 `flutter analyze --no-pub --no-fatal-infos --no-fatal-warnings`，结果为 `No issues found!`。
- 最后离线执行聚焦 `:app:testDevelopStandardDebugUnitTest` 与 `:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`，沿用 Windows Kotlin 缓存参数并排除两个依赖安装任务；结果为 `BUILD SUCCESSFUL in 2m 37s`，590 个任务中 31 个执行、559 个已最新。该轮 assemble 为 `UP-TO-DATE`，不是重新打包；安装产物匹配情况见下。
- 原生聚焦回归合计 114 项，失败、错误、跳过均为 0；覆盖同批角色使用不同模型、修改默认模型不覆盖角色专用模型、清空后继承、旧配置读取与非法半配置拒绝，以及工具禁止列表的保存和回读。
- 真机设置页将默认子代理模型设为 `deepseek-v4-pro`、planner 专用模型设为 `deepseek-v4-flash`、general 保持继承；保存、离开重开并重新加载后，角色选择仍保留。
- 从真实聊天页分派两个角色，工具详情保留两个不同 `subagentId`，planner 返回 `ROLE_MODEL_42`、general 返回 `DEFAULT_MODEL_42`，状态均为 `completed`，`toolCalls` 均为空。主回复包含两个结果；本轮 17:46:02–17:46:32 的请求日志标题同时记录 Pro 和 Flash，与角色路由回归结果相符，未展开请求正文或认证信息。
- 在角色详情点击“使用默认子模型”，保存并重新加载后 planner 恢复继承。同一聊天验收再次完成两个子任务及主回复；17:51:08–17:51:13 的四条本轮请求标题均为 Pro。
- 本轮安装 APK SHA256 为 `58e1dfa2d2be128cfd0282359091c7a52052807bd104d5fd06a2eb2745c094b3`，与本机构建产物一致。已恢复默认子代理模型跟随主 Agent、角色无专用模型、思考档位 `low`；保留验收对话，未删除用户历史，未改 Provider 或长期记忆。
- 以上功能验收来自真实 Android 应用流程，不以单元测试、HTTP 200 或卡片存在代替；其他 Agent、全部 ROM 与发布全量门禁未做真机验收。

### 对话内管理子代理验收（2026-11-15）

- 原生 12 个测试类共 167 项通过，失败、错误、跳过均为 0；离线执行 `:app:testDevelopStandardDebugUnitTest` 与 `:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`，排除两个依赖安装任务，结果 `BUILD SUCCESSFUL`。真实工具链回归经过父 Orchestrator、管理 handler、持久 store、目录刷新、Dispatcher 与子 Orchestrator，不以 mock 工具回声代替执行。
- Flutter 配置页、工具卡片、共享 reducer 和角色序列化共 261 项通过；两处配置页分析 `No issues found!`。Node 旅程与输入器 24 项、单轮验收器 39 项通过。扩展源码合约最初有 10 项既有实现正则失败，删除这些实现固化用例而非重新钉住源码；剩余 30 项通过，不将删除用例计为功能验证。
- 真实 Android 应用聊天先以 `models` 查询本地目录，再 `list → create → dispatch`，同一父 Turn 的子任务完成并返回 `SUBAGENT_CREATED_42`，revision 从 10 到 11。应用重启后重新打开该测试对话，`list → update → dispatch` 返回 `SUBAGENT_UPDATED_43`，revision 到 12；最后 `list → delete → list` 只删除本次角色，revision 到 13，完整配置与验收前一致。
- 真机读回证明默认模型、思考档位、总开关与原有四个角色未变；测试对话保留，未修改 Provider 认证或长期记忆。安装 APK SHA256 为 `41874d4fa9e54b830af7b56334b6faa8ebee41e4af5d98cc0606659808ed0cda`，与本机构建产物一致。
- 复用场景为 `scripts/fixtures/agent-user-journeys/xiaowan-subagent-config.zh.json`。首次运行因重启返回会话列表而缺少输入框，维护场景已补重开本次会话步骤；更新输入时 ADB 少输入末尾 3 个字符，输入器拒绝发送，补齐并精确核对本次合成草稿后仅发送一次。随后更新、分派、删除与官方终态均实际通过，未自动重放用户请求。
- 真实设置页已检查新生效提示，退出时未保存改动。仓库未配置 LSP，使用 Kotlin 编译与 Flutter analyze；全部 ROM、其他 Agent 真机与发布全量门禁未执行。已有聊天页不展示紧凑模型控件，恢复阶段的模型前置检查未通过，复用先前已实际调用成功的 Provider 继续验证；这不是模型配置写入或路由切换。证据保留在本机临时目录 `C:/Users/Zipper/AppData/Local/Temp/oob-subagent-config-iqPdq6`，不包含认证输出。

## 待发箱、受管文件与技能资产旅程

设备验收复用 `scripts/verify-agent-user-journey.mjs`，四份中文场景位于
`scripts/fixtures/agent-user-journeys/`：`outbox-and-result.zh.json`、
`workspace-change-review.zh.json`、`skill-suggestion-review.zh.json` 和
`lsposed-evidence.zh.json`。使用独立的空闲小万对话、空草稿和已有可用模型；
实体机必须显式设置 `OOB_ALLOW_PHYSICAL_DEVICE=1`。每次证据目录放仓库外，
场景会生成唯一标记，不通过旧回复、写数据库或伪造工具结果满足断言。

```bash
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs "$DEVICE_SERIAL" scripts/fixtures/agent-user-journeys/outbox-and-result.zh.json "$EVIDENCE_DIR/outbox"
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs "$DEVICE_SERIAL" scripts/fixtures/agent-user-journeys/workspace-change-review.zh.json "$EVIDENCE_DIR/workspace"
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs "$DEVICE_SERIAL" scripts/fixtures/agent-user-journeys/skill-suggestion-review.zh.json "$EVIDENCE_DIR/skill"
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs "$DEVICE_SERIAL" scripts/fixtures/agent-user-journeys/lsposed-evidence.zh.json "$EVIDENCE_DIR/lsposed"
```

- 待发箱应覆盖 A 运行中排队 B/C、独立编辑 B、确认移除 C、正式完成后仅投递 B 一次以及结果预览返回。修改使用整箱 `OutboxSnapshot.revision`，不能拿未变动条目的旧版本代替；确认期间箱内容变化仍须拒绝旧操作，不自动重试。
- 自然出队与 Web 入口共用宿主历史投影；最终消息必须保存所属 `sessionId`、`turnId` 及官方请求终态。答复文字出现、待发数归零或工具退出成功都不能替代正式完成和历史提交屏障。
- 受管恢复应先证明冲突拒绝且外部修改保留，再证明另一干净变更经预览与明确确认后恢复实际字节或本次新建文件不存在。不能通过短暂 Toast 代替文件状态。
- 技能应先有合格任务正式完成，再有后台资产状态；审核前不进入技能索引。审核试用后，须由独立任务实际读取该版本并正式完成，才允许反馈。应用私有目录可能经 `/data/user/0` 与 `/data/data` 别名访问，资产根应使用同一 canonical 边界，后代链接与越界仍拒绝。
- LSPosed 场景缺少专用 `cn.com.omnimind.validation.*` 靶标时必须前置失败且零部署；安装配置、框架加载日志与真实目标业务效果分别记证。没有靶标或部署授权时不得把其余回归通过写成 Hook 功能验收通过。

每份 `result.json` 单独保留通过、失败步骤及安全错误类别。实体机历史核对只使用受保护 Debug receiver 的本次唯一合成标记导出，不复制用户整库、不读取凭据；发布构建与全部 ROM 覆盖仍是独立门禁。

Web 管理接口另需设备现有本机服务已开启。该服务监听局域网，不能为了测试擅自开启或复制访问 Token；未开启时 ADB 端口转发可能直接断开，不能视为 HTTP 验证通过。服务具备前提后，用回环客户端分别记录未认证拒绝与已授权请求的实际结果；Web 源码、类型检查和构建不替代该接口验收。

