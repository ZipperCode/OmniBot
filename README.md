<p align="center">
  <picture>
    <img alt="OpenOmniBot" src="docs/pic/OmniBot.png" width="50%">
  </picture>
</p>

<p align="center">
  <a href="README.md"><b>English</b></a> |
  <a href="README.zh-CN.md"><b>简体中文</b></a>
</p>

<h3 align="center">
Your On-Device AI Assistant
</h3>

<div align="center">
  <img alt="GitHub Repo stars" src="https://img.shields.io/github/stars/omnimind-ai/OmniBot">
  <a href="https://github.com/omnimind-ai/OmniBot/releases/latest"><img alt="GitHub Release" src="https://badgen.net/github/release/omnimind-ai/OmniBot/stable"></a>
  <br>
  <a href="https://trendshift.io/repositories/26966?utm_source=repository-badge&amp;utm_medium=badge&amp;utm_campaign=badge-repository-26966" target="_blank" rel="noopener noreferrer"><img src="https://trendshift.io/api/badge/repositories/26966" alt="omnimind-ai%2FOmniBot | Trendshift" width="250" height="55"/></a>
</div>

<p align="center">
|
<a href="#use-cases"><b>Demo</b></a>
|
<a href="#quick-start"><b>Quick Start</b></a>
|
<a href="https://github.com/omnimind-ai/OpenOmniBot/releases"><b>Release</b></a>
|
<a href="https://github.com/omnimind-ai/OpenOmniBot/issues"><b>Issues</b></a>
|
</p>

> OmniBot runs directly on your Android device and combines chat, agent tools, local workspaces, and system integrations in one app.
> 🎉 Our iOS & macOS version: [ViaVera](https://github.com/omnimind-ai/ViaVera)

OmniBot is an on-device AI agent built with native Android Kotlin and Flutter. Instead of stopping at chat, it focuses on the full loop of **understand -> decide -> execute -> reflect**.

<h2 id="core-capabilities">Core Capabilities</h2>

- **Extensible tool ecosystem**: Skills, Alpine environment, browser access, MCP, and Android system-level tools.
- **System-level actions**: Supports scheduled tasks, alarms, calendar creation/query/update, and audio playback control.
- **Personal context**: Query authorized calendars, contacts, notifications, app usage, location, media, documents, SMS and call history without root, and manage calendar events and contacts.
- **Memory system**: Short-term and long-term memory with embedding support.
- **Productivity tools**: Read and write files, browse the workspace, use the browser, and access the terminal.

<p align="center">
  <img src="docs/tutorial/example.png" alt="Example" />
</p>


<details>
<summary id="quick-start"><strong>Quick Start</strong></summary>

### Configure the app
> **说明**：当前应用无需注册或登录小万账号，无需官方额度，亦不再内置 Na 云端助手。用户在设置中配置自定义模型提供商（Provider）或第三方 Provider OAuth（如 ChatGPT OAuth）即可直接开始对话。


Open the settings page from the left sidebar:

<p align="center">
  <img src="docs/tutorial/two.png" alt="Configure AI capabilities" width="260" />
  <img src="docs/tutorial/three.png" alt="Configure AI providers" width="420" />
</p>

Then open the scenario model settings:

<p align="center">
  <img src="docs/tutorial/four.png" alt="Configure AI models" width="260" />
</p>

场景分为「基础能力」和「高级能力」。主 Agent 负责对话与工具调用；视觉理解、图像生成、语音合成按需独立配置，未配置的可选能力不阻断普通聊天。GUI 未单独绑定时跟随主 Agent，仍要求图片输入能力；记忆嵌入必须使用嵌入模型。

纯文本主模型的图片附件与工具图片可由「视觉理解」转为同一回合的文字辅助，保留原始问题和附件。`image_analyze` 读取工作区图片，`image_generate` 使用独立「图像生成」场景，支持 OpenAI Images 兼容协议与 Gemini 原生图片输出；不隐式回退到主模型或当前编辑的 Provider。ChatGPT OAuth 不支持图像生成 API。

选择器过滤已知不兼容模型，未知能力显示「待确认」。在「模型提供商」点击模型可标记类型、图片输入/输出、音频输出及工具调用，或恢复自动识别；人工标记优先，但不改变服务端能力。可清除辅助场景绑定，或将 GUI 切回跟随主 Agent。

场景页默认读取本地模型目录与已有绑定；远程更新只在点击「刷新模型列表」时触发。失败时保留本地模型并允许重试。

<p align="center">
  <img src="docs/tutorial/five.png" alt="Alpine environment" width="260" />
</p>

The app usually initializes the Alpine environment automatically on startup, and you can also manage that environment from the same settings area.

<h2 id="use-cases">Use Cases</h2>

### Personal context

Enable individual sources in **Settings → App permissions → Personal context**. AI queries shared device tools on demand; results may be sent to your configured model and are not automatically added to long-term memory. Calendar and contact read/write permissions are separate. Background location needs its own “Allow all the time” grant. Approximate location and selected-only photo access are supported.

Notification access is separate from receiving Omnibot notifications. Observed notifications are cached locally for up to 7 days and 10,000 records, excluded from system backups, and can be cleared manually. Previously dismissed notifications and system-redacted content cannot be recovered. Revocation stops recording and querying without deleting conversations.

File queries return metadata first; requested contents are imported into the existing file inbox. Files and folders can be selected and access removed through the system-picker entry. SMS and call history are read-only and depend on installer/system eligibility; Omnibot does not automatically request or change default SMS or phone roles. The system digital assistant is enabled only when manually chosen by the user in system settings; the app requests no microphone permission and makes no claim of voice recognition readiness. These features require neither root, Shizuku nor accessibility and do not access other apps' private databases.

Device MCP and the built-in Agent share tool definitions and execution. `context_capabilities` reports actual access; missing permissions do not open dialogs or terminate ordinary chat. Contact mutations use `rawContactId` and `version` from queries and affect only the selected account record. Calendar queries accept an `offset`; all-day dates use UTC and an exclusive end date.

### Optional Root and device controls

Direct Root access is off by default. Enable **Root access** under **Settings → App permissions → Advanced Access** to request authorization from your device's Root manager. When available, direct Root takes priority over Shizuku; disabling it stops its commands and persistent shells. Shizuku remains independently available.

The agent can query and change supported system toggles, display and sound settings, manage app permissions, run diagnostics, and execute privileged shell commands. Mutations and shell commands require ACP client confirmation. State changes are checked against the device before being reported as successful; unsupported ROM interfaces return an explicit error. See [Android privileged actions](docs/android-privileged-actions.md) for the tool contract and compatibility details.

系统增强能力说明：
- **设备开关与控制**：支持手电筒、标准护眼、Android 16+（API 36+）Wi-Fi 热点（取决于 ROM 支持）及响铃模式控制；可在设置中查看系统能力详情；仅基于标准 Android 接口，无小爱接管或厂商私有时钟支持。
- **受管本地 Claude/Codex 设备 MCP**：专用 `android_system_action` 工具受本地会话租约与 ACP 授权严格隔离，无全局新 shell。
- **日历与时钟**：日历提供原子批量操作（1–50 项，不超过 256KiB，冲突整体回滚）；标准时钟仅分发 Intent（`dispatched: true`, `verified: false`），不伪造读回。
- **虚拟屏与用户接管**：GUI 工具仅在后台虚拟屏操作，启动失败不切主屏；冻结或停用应用明确报错。提供可拖动 / 缩放 / 贴边收纳的实时小窗、全屏观看和画中画（PiP）。读屏结合目标相关无障碍摘要、局部高清图与端侧 OCR。只有用户明确确认移交后，才官方取消原 Turn 并将现场移至主屏交由用户继续操作；观看和退出预览不停止任务。
- **任务通知**：API 36+ 申请 Promoted Ongoing 展示（具体是否呈现胶囊由系统决定，不声称目标 ROM 胶囊已实测支持），旧版回退普通前台通知；等待人工验证保留专用提示渠道。

### Skills

You can ask OmniBot to install a skill by simply sending it the repository link. Recommended collection: https://github.com/OpenMinis/MinisSkills

Enable or disable skills from the skill repository:

<p align="center">
  <img src="docs/tutorial/six.png" alt="Skill store" width="260" />
  <img src="docs/tutorial/seven.png" alt="Skill example" width="260" />
</p>

### 运行中输入、结果与受管资产

任务运行时支持“排队发送”，待发内容不提前进入历史；前一轮正常结束且历史保存成功后才顺序投递。支持编辑、移除、显式继续和“停止当前任务并发送”，旧任务卡不能取消新任务。取消、失败或重启后暂停，不自动重发。每个对话最多 20 条待发，单条文本上限 64 KiB。

结果区按实际数据开放产物、变更、现场与技能建议入口，返回保留当前对话。低高度视口收纳非阻塞状态，不隐藏授权与需处理的错误；Agent 与可选模型能力采用分组配置，不改变 Provider 解析与默认引擎。

小万文件工具为受管工作区内普通文件的写入、编辑和移动保存恢复快照；恢复须预览并明确确认，文件指纹冲突或工作区忙时拒绝覆盖。外部命令及其他 Agent 写入不承诺可恢复；敏感路径、凭据内容、符号链接与超限文件不保存快照。单文件上限 5 MiB，总额上限 100 MiB。

可开启小万后台自动技能建议：合格任务正式完成后提炼，审核前不进入技能索引，审核后以不可变版本试用。仅后续独立完成且实际读取该版本的任务可反馈保留或停用；旧版本恢复不得重新启用用户手动停用的内容。

LSPosed 结果将安装配置、框架加载日志和真实业务效果分开。加载日志不代表功能通过；业务效果必须在专用目标应用实际观察。快捷动作只准备小万草稿，不覆盖已有输入，不直接提权执行。

### 小万子 Agent（Subagent）

在 **Agents → 小万 → 配置** 中启停子代理、选择默认子代理 Provider/模型与思考档位。每个内置或自定义角色也可单独选择 Provider/模型；优先级为“角色模型 → 默认子代理模型 → 主 Agent 当前模型”，点击“使用默认子模型”可清空角色覆盖。保存后下一轮生效。模型目录在点开选择器时加载，认证仍由现有 Provider 管理。

内置角色可编辑提示词、描述与工具限制；自定义角色可增删。工具允许与禁止列表只能收窄父任务及基础角色权限，空白名单表示无工具，子代理不可递归分派。小万使用已有 `subagent_dispatch` 汇总独立上下文任务，审批、取消和工具详情仍归父 ACP Turn；其他 Agent 和公共设备 MCP 不开放此配置或分派工具。

子任务模型并发默认 2、最大 4。实际有效工具集决定是否只读；共享工作区中的写任务、浏览器操作与未知 MCP 工具全程独占资源，不同工作区可独立并行。审批等待沿用父任务权限与取消，不释放已读工作区的所有权。

### Scheduled tasks

<p align="center">
  <img src="docs/tutorial/ten.png" alt="Scheduled task" width="260" />
  <img src="docs/tutorial/eleven.png" alt="Timing" width="260" />
</p>

Scheduled tasks execute subagent flows. Alarms are reminder-only. A subagent can be assigned a complete task and behaves like a full agent.

### Browser

The `browser_use` tool opens a live browser session in an overlay window. While a
session is active, the globe floating ball keeps the page available when the
overlay is minimized. Tap the ball to reopen it; the browser chrome provides
navigation, tabs, downloads, bookmarks, history, userscripts, desktop/mobile
mode, and a close-session action.

<p align="center">
  <img src="docs/tutorial/twelve.png" alt="Browser" width="260" />
</p>

### Workspace

<p align="center">
  <img src="docs/tutorial/workspace.jpg" alt="Workspace" width="260" />
</p>

### Remote Codex bridge

To use Codex mode with Codex running on a PC or Mac, start `codex-bridge` on the computer where the Codex CLI is installed and logged in:

```bash
npx @thuocean/codex-bridge
```

Choose the LAN address and token mode in the terminal setup UI, then scan the printed QR code from OpenOmniBot's Codex settings. For advanced options and troubleshooting, see the [codex-bridge README](tools/codex-bridge/README.md).

</details>

<h2 id="development-guide">Development Guide</h2>

### Requirements

- Flutter SDK `3.47.2+`
- JDK `17+`
- Node.js `20.19+` or `22.12+` and pnpm `10.28.0` (for WebUI development)

### Get the code

```bash
git clone https://github.com/omnimind-ai/OpenOmniBot.git
cd OpenOmniBot

cd ui
flutter pub get
```

If Flutter reports `Could not read script '.../ui/.android/include_flutter.groovy'`, run:

```bash
flutter clean
flutter pub get
```

### Develop the WebUI locally

The WebUI in `webchat/` is a standalone React + TypeScript + Vite project. During local development, Vite serves the frontend with hot reload and proxies `/webchat/api` requests to the Android app's local service.

1. Install and start OpenOmniBot on an Android device. Keep the computer and device on the same trusted LAN.
2. In the app, open **Settings > Local Service**, enable the service, and copy its address and Token. The default port is `8899`, but always use the address shown by the app.
3. Start the WebUI development server from the repository root, replacing the sample address with the Android local-service address (do not append `/webchat`):

```bash
cd webchat
pnpm install --frozen-lockfile

VITE_WEBCHAT_PROXY_TARGET=http://192.168.1.20:8899 pnpm dev
```

On PowerShell, set the proxy target first:

```powershell
$env:VITE_WEBCHAT_PROXY_TARGET = "http://192.168.1.20:8899"
pnpm dev
```

Open the URL printed by Vite (normally `http://localhost:5173`) and enter the Token copied from the app. Use `pnpm dev` for end-to-end API/SSE testing; the proxy keeps session cookies, realtime events, workspace access, and browser mirroring on the same local origin.

Before submitting WebUI changes, run:

```bash
cd webchat
pnpm run typecheck
pnpm run build
```

The production files are generated in `webchat/dist/`. Both `dist/` and `node_modules/` are local outputs and must not be committed.

Android builds handle the WebUI automatically: Gradle runs the locked pnpm install, executes the Vite production build, clears stale WebChat assets, and copies only `dist/` into the APK. To verify this step without building the full app, run:

```bash
./gradlew :app:syncWebChatBundle -Ptarget=lib/main_standard.dart
```

Flutter Web is not part of this workflow.

### Build and install

Android builds use `OMNIBOT_UPDATE_WORKER_URL` for the website backend's update
checks, anonymous statistics, cloud policy, and official VLM proxy configuration.
It defaults to `https://omnibot.omnimind.com.cn`; upstream proxy credentials stay
on the backend. See [the integration notes](docs/website-backend-migration.md).
Debug APK builds use the OpenAI-compatible LLM API configured by
`LLMTHU_API_BASE`, `LLMTHU_API_KEY`, and `LLMTHU_MODEL` for normal LLM requests,
context compaction, and `scene.vlm.operation.primary`.
For local acceptance testing, Release builds can explicitly enable
`-POOB_BUNDLE_LLMTHU_PROVIDER=1` to bundle the same `LLMTHU_*` configuration
for use out of the box. Regular Release and CI builds do not bundle this
configuration by default.

```bash
cd ..

./gradlew :app:installDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

For acceptance testing on a physical device, use the repository's LLMTHU
installation script. It enables the LLMTHU provider by default and reads the
API key from `LLMTHU_API_KEY` in the current shell. If the key is missing, the
script fails immediately instead of producing an unusable test APK. Regular
Release and CI builds still do not automatically bundle the key.

```bash
export LLMTHU_API_KEY='your-api-key'
./scripts/install_release_llmthu_device.sh
```

<h2 id="architecture">Architecture Overview</h2>

```text
OpenOmniBot/
├── app/                        # Android host app: entry point, agent orchestration, system abilities, MCP, services
├── ui/                         # Flutter Android UI: chat, settings, tasks, and memory
├── webchat/                    # React + TypeScript WebUI; Vite builds the static bundle packaged by Android
├── baselib/                    # Shared core libraries: database, storage, networking, model config, permissions
├── assists/                    # Shared task lifecycle and chat/model coordination
├── uikit/                      # Native overlay UI: floating ball, overlay panels, half-screen surfaces
└── ReTerminal/core/            # Embedded terminal experience modules
```

<h2 id="community">More</h2>

Thanks to developers from [LINUX.DO](https://linux.do) and other communities for supporting OpenOmniBot.

Special thanks to these open-source projects:

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="https://omnibot.omnimind.com.cn/community/wechat-qr" alt="WeChat Group" width="220"/><br/>
      <b>WeChat Group</b><br/>
      <a href="https://discord.gg/WnBvBXgykD">Join the Discord community</a>
    </td>
  </tr>
</table>
