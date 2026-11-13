# OmniFlow Runtime Component

This GitHub Release ZIP is a self-contained, versioned OmniFlow plugin. OpenOmniBot downloads it
from the plugin market and runs it directly with the app's Alpine Python environment.

## Contents

- `component.json`: component identity, SemVer, runtime paths, and host MCP contract.
- `SKILL.md`: agent-facing usage and safety guidance.
- `vendor/site-packages/`: the small pure-Python dependency needed by the mobile runtime.
- `scripts/runtime/python/`: pinned OmniFlow sources plus OOB schemas.
- `scripts/runtime/.runtime/omnitransfer/`: canonical OmniTransfer v9 source and checkpoint.
- `INSTALL_DIR.json`: Android and shell installation paths.

## Installation

Install or update this complete ZIP through the OpenOmniBot plugin market. The Host verifies the
Release SHA-256 and extracts it atomically. NumPy comes from Alpine; the remaining pure-Python
dependency is already in the component, so mobile installation does not create a venv or run uv.
See `INSTALL_DIR.json` for the resolved directory contract.

## Quick Start

1. Install and enable **OmniFlow** from the OpenOmniBot plugin market.
2. Grant XiaoWan's accessibility and overlay permissions when prompted. OmniFlow uses the same
   native GUI-control path; it does not install a second accessibility service.
3. Ask XiaoWan to complete a GUI task. The first online run uses the configured VLM and writes a
   canonical RunLog containing the goal, actions, observations, final state, and diagnostics.
4. After a successful run, open **RunLog** and choose **Register as Function**. If the runtime has
   already registered it automatically, open **Functions** to inspect or run it directly.
5. Run the Function again with new semantic parameters. Replay uses the pinned OmniTransfer and
   falls back to the normal VLM path when current-screen recovery is needed.

The completion card reflects the real registration state: **Register as Function** means a
successful RunLog is available but has not been registered yet; **View Functions** means automatic
registration succeeded. Installing the plugin never enables XiaoWan's official built-in VLM model
automatically. Online execution uses the model/provider selected in OpenOmniBot.

### Coffee Example

The packaged catalog includes `order_beverage_meituan`, a parameterized Function that accepts a
beverage such as latte or Americano. It opens the app, searches for the requested drink, selects a
result, and stops before order submission or payment. Never use the example to submit or pay for a
real order without explicit user confirmation.

### Updating

Plugin-market updates replace the versioned OmniFlow Runtime as one unit. Prompts, planner logic,
RunLog conversion, Functions, checkers, replay policy, OmniTransfer source, checkpoint, and Python
dependencies stay pinned to the component manifest. OpenOmniBot remains the stable Android host for
permissions, screenshots, gestures, lifecycle, and the bridge.

### 屏幕混合感知

GUI 规划沿用同一 OmniFlow 执行循环：优先使用目标相关的无障碍节点摘要，树中缺字时按需在端侧识别。`get_state(reason?, ocr?, x1?, y1?, x2?, y2?)` 是只读动作，不写入可重放动作序列。区域四个坐标须一起提供，使用全屏 `0..1000`；局部图中的后续操作仍使用全屏坐标，不使用裁剪图的局部坐标。

宿主返回 `observation_id`、显示屏身份及帧序号；动作绑定其来源观测，并在同一输入锁内核对目标属性、位置与确证遮挡。过期、跨屏、身份不符或多候选歧义直接失败，不改成盲点或自动重放。部分遮挡仅可移至目标内确证的净空点；纯视觉目标另核验点击区域的像素变化，不因无关时钟变化拒绝整个屏幕。

规划器在本次运行内保留已经确证的原生工具调用及文本结果，下一步通过标准 `assistant.tool_calls` / `tool_call_id` 消息配对反馈；调用名称、规范参数与实际执行结果须一致，不按同名工具猜测成功。历史由既有运行步数上限约束，最新屏幕仍来自当前观测，旧截图不在模型上下文中累积；这不创建新的 ACP 事件流、长期记忆或可重放动作。

提供商即使在 `parallel_tool_calls=false` 下返回多个调用，也不整批执行：每次仅首调用进入原有执行循环，其余每个原始 `tool_call_id` 明确返回 `success=false`、`executed=false`、`not_executed_observation_refresh_required`。下一次模型请求保留整组 `assistant.tool_calls` 及各调用结果，并使用动作后的新观测重新决策；不建立待执行队列，不伪造成功，不自动重放尾部坐标。首调用的身份、名称与参数必须来自同一原生调用，无法确证执行结果时终止。首调用为 `finished` 时必须独立返回，尾部 `finished` 不提前结束任务。

端侧 OCR 使用固定模型与字典，首次需要时加载；结果包含实际状态 `ready` / `unavailable` / `failed`、文字、置信度、原屏幕像素框与 `source=ocr`。识别框不是可点击控件。相同显示屏、原图内容与识别区域可复用识别缓存。全局图按最长边 1600 和约 1.6MP 的预算缩小，预算内图片不放大、不重复重编码；高清区域保留原始像素。直接设备工具通过既有图片通道发送区域图，文本仅保留区域元数据，不重复携带图片 Base64。

### 长按、条件等待与节点选择器

`long_press(x, y, duration_ms?, target_description?, node_id?, node_resource_id?)` 沿同一 Android 执行器运行，默认长按 800ms；虚拟屏无障碍派发被拒绝时仍保留长按时长，不降级为短按。已受理的手势不会因超时或取消而重复注入。

`wait(duration_ms, condition?, target_description?)` 支持 `text_present`、`text_absent` 和 `screen_changed`，条件超时必须为 `1..10000ms`。文字条件要求非空目标；其他等待禁止携带目标。`text_absent` 必须先在基线看见目标，空树不能证明文字消失；纯视觉页面比较实际像素并连续确认变化。条件失败返回明确错误，不表示任务完成。旧无条件等待保留原有时长语义。

点击、输入和长按可指定当前观测的唯一 `node_id` 或完整等值 `node_resource_id`。显式选择器缺失、冲突或歧义直接拒绝，不回退坐标；XML 的 `index` 不充当节点身份。选择器仍受观测新鲜度、输入锁和遮挡守卫约束，不持久化到 Function。条件等待的时长、条件和目标，以及长按时长，沿公共 RunLog 导入与 Function 回放保留。

### 进展与完成证据

同一 GUI 循环统一统计被动观察：重复读屏、无变化等待和条件超时不会相互清零。新页面、未读过的 OCR/局部图证据或首次满足的条件可以推进；同屏重复满足同一条件不算新进展。连续两次无进展返回 `no_progress_detected`，不会自动重复点击。

`finished(content?, observation_id?, expected_text?)` 可绑定当前观测与规范化等值文字。过期观测或缺少匹配文字时，原工具调用收到失败反馈，由下一次规划重新决策；没有文字声明时记录 `model_asserted`。`finished_evidence` 仅保留观测/状态身份、状态、来源和匹配节点 ID；`matched` 只证明声明文字在当前 XML 或 ready OCR 中出现，不代表任意业务目标已得到保证。ACP 完成仍由原有生命周期负责。

对已确证未执行的选择器、观测或遮挡拒绝，同屏重复相同工具与规范参数不会再次派发；模型收到 `repeated_rejected_action`，连续重复沿同一无进展计数终止。宿主每次 `model_turn` 只请求模型一次，不在内部追加反思请求。网络重试仍由既有模型传输层负责，仅在尚未出现可见输出时有界执行。

### GUI 模型配置

GUI 复用既有 Provider 与模型配置：专用 GUI 绑定优先；没有专用绑定时继承当前会话的实际 Provider/模型，否则沿共享 Agent 场景。失效的专用绑定明确失败，不自动换模型。一次调用开始时冻结路由、能力、思考意图与输出预算，配置修改在下一次调用生效；只读 Function 管理不创建模型客户端。

GUI 输出预算由实际 GUI 模型的上下文容量与会话阈值计算，再受模型输出上限约束，不再固定为 512；已有更小预算不会被扩大。思考档位按会话意图与实际模型元数据适配，明确不支持图片的模型在网络请求前失败，未知能力交 Provider 返回实际结果。协议参数映射仍归既有 Provider 适配层。

### 多屏采集

`collect_screen(direction, x1, y1, x2, y2, max_pages?, max_items?, ocr?)` 在同一后台应用的指定区域逐页读取；`down/up` 表示向后/向前阅读，区域为全屏 `0..1000` 坐标且必须有面积。默认最多 5 页、100 项，可设置为 `1..20` 页和 `1..200` 项，OCR 默认关闭。每次内部滑动都使用新观测、经过原输入锁守卫、记录为规范 `swipe` 并消耗总步数；采集本身不可录制或回放为 Function。

结果 `collection` 保留条目文字、访问页序、区域框、XML/OCR 来源和状态 ID。相邻页按有序重叠去重，同文但不同位置的消息不会全局去重；无法确证重叠时标记 `overlap_uncertain`。页数、条目数、全 run 共 32768 字符或步数到限均明确 `truncated` 与停止原因，不表示完整历史已经读完。

切包、显示失效、观察或滑动失败立即结束当前 GUI run，`success=false, partial=true` 保留已采集内容，不重启应用、不再请求模型覆盖失败；取消原样传播。普通滑动也检查整段路径的高层窗口及可交互兄弟遮挡，纯视觉路径检查截图有效性和起点、中点、终点变化；不声称能够确证透明遮罩。

### 应用经验

封存后的运行记录可自动生成结构化操作参考，保存在 Function 目录旁的 `app-experiences.json`，不改变 Function 格式或长期记忆。只学习有宿主接地确证、稳定资源 ID、页面变化且整次运行具有完成文字证据的点击、长按和输入入口；不保存任务目标、输入值、业务文字、OCR、截图、坐标或临时节点 ID。无可靠资源 ID 的页面继续普通规划。

新规则先试用，来源运行不计成功；后续两个独立运行确实选用同一规则、动作有效且完成文字匹配后才转为已验证。同槽新试用遮蔽旧版，连续两次可归因失败回退；同屏无效果仅归因到当前停滞周期实际使用的步骤，延迟变页后的其他停滞不回罚。权限、规划或传输中断、取消和缺少确证的结果保持中性；重新试用退役版只关联当时仍有效的前任，回退不重新启用用户停用的版本。已验证只表示这条规则通过试用，不保证全应用任务。

执行中心的“应用经验”提供刷新、分页、详情与启停；版本冲突刷新而不重发写入，任务执行中显示“任务结束后更新”。管理读取和启停不创建模型，内部学习不进入公开工具目录，且仅读取当前已封存运行引用的状态。经验损坏时保留原文件并继续普通规划；学习失败只附加短错误码，不改变 GUI 终态、不重做动作。容量达到 500 槽或每槽 8 版时停止新增，不删除旧经验。

### 本地内置运行时组包

内置 `2.1.9` 使用不可变的 `artifacts/omniflow-gui-runtime-2.1.8.zip` 为底包，叠加 `runtime-source/`、规范动作 Schema 与公共 RunLog Schema。运行 `python scripts/build-omniflow-runtime.py` 生成新包并同步目录中的版本、大小和 SHA-256；加 `--check` 可只读核对可复现结果。没有发布的远端资产不登记下载 URL；APK 内置包与开发覆盖是不同来源，本次能力不依赖设备上的开发覆盖。

内置运行时以 APK 声明的归档 SHA-256 判定更新，不能只比较组件版本号。离线目录没有远端下载摘要时，不得把缺少市场来源标记的旧内置包视为市场安装；应沿既有内置包升级路径重新校验并加载新版源码。

### Standard MCP Hosts

Enable OpenOmniBot's built-in MCP service and register its Streamable HTTP `/mcp` URL and token in
any standard MCP host. The public OmniFlow surface contains only `run_gui`, `run_function`,
`list_functions`, and `register_function`. Observation, action, model, RunLog persistence, and
destructive Function management remain internal to OpenOmniBot. The phone does not install a
second Python MCP server or MCP SDK.

GitHub Release distributes the versioned plugin asset. The Skill explains how to use it. The MCP
endpoint exposes capabilities. Harness tests verify the published asset and MCP tool contract.
Runtime updates replace OmniFlow, OmniTransfer, prompts, Functions, checkers, and checkpoints
without replacing the APK or changing the standard MCP interface.

## Developer Override

Use these official Agent tools in order:

1. `get_omniflow_python_override` to inspect status or read one `omniflow/**/*.py` file.
2. `apply_omniflow_python_override` to validate, save, and hot reload a complete Python file.
3. `reload_omniflow_python_override` to restart the worker without changing source.
4. `clear_omniflow_python_override` with `confirm=true` to return to the pinned runtime.

An apply operation compiles the Python file before worker initialization and automatically restores
the previous content if reload fails. The override changes OmniFlow only. OmniTransfer remains the
canonical pinned implementation and missing mappings still fall back through the normal runtime.

Windows 上连续覆盖同一文件使用 NIO 原子替换；文件系统不支持原子移动时才降级为普通替换。目录重基先移动旧目录作为备份：备份失败保留原目录，安装失败尝试恢复，恢复失败保留备份供恢复，不删除原有内容。

Never change payment safety policy through the developer override. GUI automation may prepare an
order, but must not confirm or submit a real payment without explicit user confirmation.
