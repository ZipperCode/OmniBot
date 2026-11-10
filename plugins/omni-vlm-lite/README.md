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

### 本地内置运行时组包

内置 `2.1.9` 使用不可变的 `artifacts/omniflow-gui-runtime-2.1.8.zip` 为底包，叠加 `runtime-source/` 与规范动作 Schema。运行 `python scripts/build-omniflow-runtime.py` 生成新包并同步目录中的版本、大小和 SHA-256；加 `--check` 可只读核对可复现结果。没有发布的远端资产不登记下载 URL；APK 内置包与开发覆盖是不同来源，本次能力不依赖设备上的开发覆盖。

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

Never change payment safety policy through the developer override. GUI automation may prepare an
order, but must not confirm or submit a real payment without explicit user confirmation.
