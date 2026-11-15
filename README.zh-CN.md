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
你的端侧 AI 助手
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

> OmniBot 直接运行在 Android 设备上，将聊天、Agent 工具、本地工作区与系统级集成整合在一个应用中。
> 🎉我们的iOS&macOS版本：https://github.com/omnimind-ai/ViaVera

OmniBot 是一个基于 Android 原生 Kotlin 与 Flutter 构建的端侧 AI Agent。与传统 AI Chat 不同，它关注的是 **从理解 -> 决策 -> 执行 -> 反馈的完整闭环**。

<h2 id="core-capabilities">核心能力</h2>

- **工具生态扩展**：Skills、Alpine 环境、浏览器、MCP、安卓系统级工具等。
- **系统级能力**：支持定时任务、闹钟提醒、日历创建/查询/修改、音频播放控制。
- **个人上下文**：无需 root，按授权查询日历、联系人、通知、应用使用情况、位置、媒体文件、文档、短信与通话记录，并支持日程和联系人管理。
- **记忆系统**：支持短期与长期记忆嵌入。
- **生产力工具**：支持读写文件、浏览工作区、调用浏览器、调用终端。

<p align="center">
  <img src="docs/tutorial/example.png" alt="示例" />
</p>


<details>
<summary id="quick-start"><strong>快速开始</strong></summary>

### 配置应用
> **说明**：当前应用无需注册或登录小万账号，无需官方额度，亦不再内置 Na 云端助手。用户在设置中配置自定义模型提供商（Provider）或第三方 Provider OAuth（如 ChatGPT OAuth）即可直接开始对话。


从左侧栏打开设置页面：

<p align="center">
  <img src="docs/tutorial/two.png" alt="配置 AI 能力" width="260" />
  <img src="docs/tutorial/three.png" alt="配置 AI 提供商" width="420" />
</p>

然后前往场景模型配置：

<p align="center">
  <img src="docs/tutorial/four.png" alt="配置 AI 模型" width="260" />
</p>

场景分为「基础能力」和「高级能力」。主 Agent 用于对话与工具调用；视觉理解、图像生成、语音合成按需独立配置，不要求所有场景都配置完才能聊天。GUI 操作未单独绑定时跟随主 Agent，但仍需模型支持图片输入。记忆嵌入需要嵌入模型，不能用普通聊天模型代替。

主模型不支持读图时，图片附件和工具图片可交给「视觉理解」场景，分析结果作为同一回合的文字辅助；原始问题与附件保留。`image_analyze` 用于读取工作区图片，`image_generate` 使用「图像生成」场景，目前支持 OpenAI Images 兼容协议与 Gemini 原生图片输出。两者不隐式借用主模型或当前编辑的 Provider；ChatGPT OAuth 不支持图像生成 API。

模型选择器排除已知不兼容项，能力未知时显示「待确认」。可在「模型提供商」点击模型，手动标记模型类型、图片输入/输出、音频输出和工具调用，或恢复自动识别。人工标记优先于 Provider 与目录元数据，但不会让服务端获得其不支持的能力。可清除辅助场景的专用模型，或让 GUI 重新跟随主 Agent；未配置的可选能力显示为未启用。

场景页打开时使用本地缓存、手动模型与已有绑定，不自动请求所有服务商。需要更新远程模型目录时点击右上角「刷新模型列表」；刷新失败保留本地选择，并提供重试入口。

<p align="center">
  <img src="docs/tutorial/five.png" alt="Alpine 环境" width="260" />
</p>

一般情况下，应用启动时会自动初始化 Alpine 环境，你也可以在这里自行管理环境。

在首页右上角 `…` 中选择「唤起宠物」可显示桌面悬浮宠物，选择「收起宠物」关闭。点击宠物或通过应用图标等入口回到应用时，宠物会自动收起；需要时可在菜单中再次唤起。有任务回跳提示时，点击宠物会返回对应聊天并收起宠物。

<h2 id="use-cases">使用场景</h2>

### 个人上下文

在“设置 → 应用权限授权 → 个人上下文”逐项开启。AI 使用共享的设备工具按需查询，查询结果可能发送给当前配置的模型，不会自动建立长期记忆。日历与通讯录的读取、写入分别授权；后台查询位置还需单独选择“始终允许”。大致位置及仅选定照片也可使用。

通知访问与接收小万消息通知的开关独立。授权后观察到的通知在本地保留最多 7 天、10000 条，排除系统备份，可手动清空；无法恢复授权前已消失的通知或系统遮蔽的内容。撤销授权后停止记录和查询，聊天记录不受影响。

文件查询先返回元数据，读取内容时才导入已有文件收件箱。可通过系统选择器授权文件或文件夹，并在同一入口撤销。短信和通话记录仅支持读取，能否授权取决于安装器和系统限制；不会自动申请或更改默认短信、电话身份，系统数字助理由用户在系统设置中手选启用，应用未声明麦克风权限且不承诺语音识别就绪。本功能不需要 Shizuku、root 或无障碍，也不读取其他应用的私有数据库。

设备 MCP 与内置 Agent 共用工具定义和执行服务。`context_capabilities` 返回真实授权状态；缺少权限不会自动弹窗或终止普通聊天。联系人修改使用查询返回的 `rawContactId` 和 `version`，只修改选定账户记录；日历查询支持 `offset` 分页，全天日程使用 UTC 日期和不含结束日的区间。

### 可选 Root 与设备控制

直接 Root 默认关闭。在“设置 → 应用权限授权 → 扩展能力”开启 **Root 高级能力**，主动向设备的 Root 管理器申请授权。授权有效时优先使用直接 Root；关闭后停止其命令与持久 shell。Shizuku 仍作为独立的可选后端使用。

Agent 可查询和修改支持的系统开关、显示与声音设置，管理应用权限、执行诊断和高权限 shell。系统设置页可查看当前设备支持的系统能力详情。状态修改与 shell 命令继续通过 ACP 客户端逐次确认；设备状态读回后才报告修改成功，ROM 不支持的接口明确返回错误。

通用系统增强能力包含：
- **设备开关与控制**：支持手电筒、标准护眼（ColorDisplayManager）、Android 16+（API 36+）Wi-Fi 热点（受 ROM Tethering 支持限制）及响铃模式控制；仅使用 Android 公开标准接口，无小爱接管或厂商私有时钟闭环。
- **受管本地 ACP 设备 MCP**：为本地 Claude/Codex 会话提供专用 `android_system_action` 工具，严格隔离在独立会话端点，遵循所在会话审批与取消，不向外部提供任意全局 shell 或提权后门。
- **日历与时钟**：日历支持单批 1–50 项、不超过 256KiB 的原子批量事务（失败整批回滚）；标准时钟仅通过系统 Intent 分发（报告 `dispatched: true`, `verified: false`），不伪造静默 CRUD。
- **虚拟屏与用户接管**：GUI 工具仅在后台虚拟屏操作，启动失败不切主屏；冻结或停用应用明确报错。提供可拖动 / 缩放 / 贴边收纳的实时小窗、全屏观看和画中画（PiP）。读屏结合目标相关无障碍摘要、局部高清图与端侧 OCR。只有用户明确确认移交后，才官方取消原 Turn 并将现场移至主屏交由用户继续操作；观看和退出预览不停止任务。
- **任务通知**：API 36+ 申请 Promoted Ongoing 通知，是否展示胶囊由系统决定（不宣称目标 ROM 胶囊已支持），旧版与不支持平台平滑回退为普通前台通知；等待人工验证时保留专属高优先级渠道。

详细工具约定与兼容范围见 [Android 高权限操作](docs/android-privileged-actions.md)。

### Skills

你可以直接把 skills 仓库链接发给小万，让她帮你安装。推荐仓库：https://github.com/OpenMinis/MinisSkills

在技能仓库中可以选择开启或关闭某项技能：

<p align="center">
  <img src="docs/tutorial/six.png" alt="技能仓库" width="260" />
  <img src="docs/tutorial/seven.png" alt="技能示例" width="260" />
</p>

### 运行中输入与结果检查

任务运行时可继续输入并“排队发送”；待发项不进入聊天历史，只有前一轮正常结束且历史保存成功后才按顺序投递。支持独立编辑、移除和显式继续发送；取消、失败或进程重启后保留暂停，不自动重发。“停止当前任务并发送”只针对当时选定的任务，不能取消后续任务。每个对话最多保留 20 条待发，单条文本上限 64 KiB。

任务结果按实际数据提供产物、变更、现场和技能建议入口；返回保留当前对话。低高度视口将非阻塞附件和待发状态收纳到输入区胶囊，授权与需处理的错误仍直接展示。Agent 与可选模型能力分组展示，不会因展开配置而安装 Agent 或替换 Provider。

### 受管文件变更与技能建议

小万文件工具在受管工作区内写入、编辑和移动普通文件时记录变更，支持预览差异后明确确认恢复；外部命令与其他 Agent 的写入不承诺可恢复。恢复前核对文件指纹，发现用户改动或工作区忙则拒绝覆盖。敏感路径、凭据内容、符号链接和超限文件不保存恢复快照；单文件上限 5 MiB，快照总额上限 100 MiB。

技能页可开启小万自动技能建议：合格任务正式完成后在后台提炼，候选在审核前不会进入可执行技能目录。审核保存为试用版本；后续独立任务实际读取该版本并完成后，才可反馈保留或停用。版本与内容哈希绑定，恢复旧版也不能重新启用用户手动停用的版本。

### 手机开发与 LSPosed 证据

手机开发结果区分别展示安装配置、框架加载证据和待验证的业务效果。框架日志不能证明 Hook 功能成功；必须在专用目标应用实际观察预期行为。快捷操作只准备当前小万会话草稿，不直接执行特权动作，不覆盖未发送输入；后续发送仍遵循既有审批。

### 小万子 Agent（Subagent）

可以直接对小万说：“创建一个只读代码审查子代理，保存后立即分派审查任务。”小万通过 `subagent_manage` 查询、创建、更新、删除角色，或修改总开关、默认模型与思考档位，不必手动打开设置。配置写入后可在同一用户回合继续分派；已启动的子任务保持原配置。

也可在 **Agents → 小万 → 配置** 手动管理，两种入口共用配置和版本冲突检查。每个角色可指定 Provider/模型；未设置时使用默认子代理模型，默认也未设置时跟随主 Agent。角色详情中的“使用默认子模型”可清除专用模型。对话中的 `models` 只查询已有本地缓存与手工模型，不联网刷新或暴露认证；需要更新模型列表时仍在设置中操作。

可编辑内置角色的提示词、描述与工具限制，也可添加或删除自定义角色。工具策略区分“继承基础角色”“无工具”“显式白名单”，禁止列表进一步收窄权限；不能超出父任务和基础角色的能力，子代理不能继续递归分派。通用角色保留启用，其他内置角色可停用但不可删除。

明确要求小万分派或并行时，她通过 `subagent_dispatch` 执行独立上下文的子任务并汇总结果；过程与结果保留在父任务的工具详情中。审批和取消仍由父 ACP Turn 统一管理。子代理不能管理其他子代理；其他 Agent 和公共设备 MCP 均不开放这套管理或分派工具。

子任务模型并发默认 2、最大 4；只读判定依据实际有效工具集，不依据角色名称。共享工作区中的写任务在整个子任务期间独占资源，浏览器操作与未知 MCP 工具也按独占处理；不同工作区可独立并行。等待授权时保留该任务资源所有权，可通过原任务停止入口取消。

### 定时任务

<p align="center">
  <img src="docs/tutorial/ten.png" alt="定时任务" width="260" />
  <img src="docs/tutorial/eleven.png" alt="时间设置" width="260" />
</p>

定时任务用于执行 subagent 流程；闹钟仅用于提醒。你也可以把一个完整任务交给 subagent，它会像完整 agent 一样执行。

### 浏览器

`browser_use` 工具会在悬浮窗口中打开实时浏览器会话。会话存在时，地球悬浮球可在
窗口最小化后继续提供入口；点击悬浮球即可恢复页面。浏览器工具栏支持网页导航、
标签页、下载、书签、历史记录、用户脚本、桌面版/手机版切换，以及关闭整个会话。

<p align="center">
  <img src="docs/tutorial/twelve.png" alt="浏览器" width="260" />
</p>

### Workspace

<p align="center">
  <img src="docs/tutorial/workspace.jpg" alt="Workspace" width="260" />
</p>

### Remote Codex bridge

如果希望在手机端使用运行在电脑上的 Codex 模式，请在已安装并登录 Codex CLI 的电脑上启动 `codex-bridge`：

```bash
npx @thuocean/codex-bridge
```

在终端交互中选择监听的局域网地址和 token 模式，然后在 OpenOmniBot 的 Codex 设置中扫码连接。更多选项和排障说明见 [codex-bridge README](tools/codex-bridge/README.md)。

</details>

<h2 id="development-guide">开发指南</h2>

### 环境要求

- Flutter SDK `3.47.2+`
- JDK `17+`
- Node.js `20.19+` 或 `22.12+`、pnpm `10.28.0`（用于 WebUI 开发）

### 获取代码

```bash
git clone https://github.com/omnimind-ai/OpenOmniBot.git
cd OpenOmniBot

cd ui
flutter pub get
```

如果 Flutter 提示 `Could not read script '.../ui/.android/include_flutter.groovy'`，可以执行：

```bash
flutter clean
flutter pub get
```

### 本地开发 WebUI

`webchat/` 是独立的 React + TypeScript + Vite 项目。本地开发时由 Vite 提供热更新，并将 `/webchat/api` 请求代理到 Android 应用提供的本地服务。

1. 在 Android 设备上安装并启动 OpenOmniBot，确保电脑和设备位于同一个可信局域网。
2. 在应用中打开 **设置 > 本地服务**，启用服务并复制地址和 Token。默认端口是 `8899`，但应以应用实际显示的地址为准。
3. 从仓库根目录启动 WebUI 开发服务器。将下面的示例地址替换为 Android 本地服务地址，地址末尾不要添加 `/webchat`：

```bash
cd webchat
pnpm install --frozen-lockfile

VITE_WEBCHAT_PROXY_TARGET=http://192.168.1.20:8899 pnpm dev
```

PowerShell 使用以下方式设置代理地址：

```powershell
$env:VITE_WEBCHAT_PROXY_TARGET = "http://192.168.1.20:8899"
pnpm dev
```

打开 Vite 输出的地址（通常为 `http://localhost:5173`），输入从应用复制的 Token。端到端调试 API、SSE 实时事件、工作区和浏览器镜像时应使用 `pnpm dev`，开发代理会让这些请求和会话 Cookie 保持在同一本地域名下。

提交 WebUI 修改前执行：

```bash
cd webchat
pnpm run typecheck
pnpm run build
```

生产静态文件会生成到 `webchat/dist/`。`dist/` 和 `node_modules/` 都是本地产物，不应提交到仓库。

Android 构建会自动处理 WebUI：Gradle 使用锁文件安装依赖、执行 Vite 生产构建、清除旧 WebChat 资源，并且只将 `dist/` 复制进 APK。如需跳过完整 APK 构建、单独验证该流程，可以执行：

```bash
./gradlew :app:syncWebChatBundle -Ptarget=lib/main_standard.dart
```

此流程不再使用 Flutter Web。

### 构建并安装

安卓版本通过 `OMNIBOT_UPDATE_WORKER_URL` 访问网站后端，统一完成更新检查、
匿名统计、云服务门禁和官方视觉模型代理配置。默认地址为
`https://omnibot.omnimind.com.cn`，上游代理 Key 只保存在后端。
设置 → 关于小万 → 我要反馈使用同一网站表单，支持选择附件和可选联系方式；
迁移与发布配置见[集成说明](docs/website-backend-migration.md)。
Debug APK 统一使用 `LLMTHU_API_BASE`、`LLMTHU_API_KEY` 和
`LLMTHU_MODEL` 配置的 OpenAI-compatible LLM API，覆盖普通 LLM、上下文压缩
和 `scene.vlm.operation.primary`。
本地验收 Release 可显式传入 `-POOB_BUNDLE_LLMTHU_PROVIDER=1`，使用同一组
`LLMTHU_*` 配置完成开箱即用验收；普通 Release 和 CI 默认不内置该配置。

```bash
cd ..

./gradlew :app:installDevelopStandardDebug -Ptarget=lib/main_standard.dart
```

真机验收可以使用仓库提供的 LLMTHU 安装入口。它默认启用 LLMTHU
Provider，并从当前 shell 的 `LLMTHU_API_KEY` 读取密钥；未配置密钥时会直接
失败，不会生成无效的验收包。普通 Release 和 CI 仍不会自动内置密钥。

```bash
export LLMTHU_API_KEY='你的密钥'
./scripts/install_release_llmthu_device.sh
```

### Fork 自动 APK 发布

`ZipperCode/OmniBot` 的 `main` 每次推送都运行 CI，成功后在
[Actions](https://github.com/ZipperCode/OmniBot/actions) 提供保留 14 天的
`debug-apk-<sha>` 下载；它使用调试签名，不是正式渠道升级包。

唯一签名发布入口为 `.github/workflows/release.yml`：

- `main` 推送自动检查 `app/build.gradle.kts` 中的源码 `versionName`：
  三段数字发布 stable，四段数字发布 beta。首次版本为 `v0.7.0.4` beta；
  后续修改版本并推送即可，不需要手工打标签。相同公开版本不会重发或替换资产，旧同渠道版本跳过。
- 每天 UTC 22:00（北京时间次日 06:00）生成 `nightly-YYYYMMDD`，即使无新提交；
  GitHub 调度是尽力执行，不能保证准点完成。手动验收可选择 `channel=nightly`，仅允许 `main`。
- stable 更新 latest，beta/nightly 均为预发布且不改 latest；历史版本保留。
- [Releases](https://github.com/ZipperCode/OmniBot/releases) 每个版本提供 APK、
  `.apk.sha256` 和 `manifest.txt`。清单记录源码提交及从最终 APK 实读的包名、版本、
  CODE、渠道和签名证书摘要；先在 draft 校验全部资产，再公开。

三个渠道共用 `cn.com.omnimind.bot` 和仓库独立签名，安装 CODE 跨渠道递增。
新签名不能覆盖现有上游或 debug 签名应用；不应通过卸载或清数据绕过。
从较新 nightly 回到较早 stable 属于 Android 降级，需等待后续更高 CODE 的 stable。
fork 自动签名包设置 `OMNI_DISABLE_APP_UPDATES=1`，关闭上游不同签名 APK 的更新提示，
不包含 App 内自动下载安装或新更新服务；普通本地/上游构建维持原更新配置。

维护配置为 Secrets `RELEASE_KEYSTORE`（PKCS12 的 base64）、`RELEASE_PASSWORD`、
`RELEASE_KEY_ALIAS`，以及公开变量 `RELEASE_CERT_SHA256`。缺失或证书不符直接失败，
不会回退 debug 签名。签名备份在
`%LOCALAPPDATA%/OmniBot/release-signing/ZipperCode-OmniBot/`：
`release.p12`、当前 Windows 用户 DPAPI 加密的 `password.dpapi`、`certificate.sha256`；
必须保留，DPAPI 文件不能单独交给其他 Windows 用户解密。不得提交或输出私密签名材料。

本地仍保留源码默认 `versionCode=21`、`versionName=0.7.0.4`。
可用 `OMNI_VERSION_CODE`（1..2100000000）和 `OMNI_VERSION_NAME` 覆盖最终 APK 身份；
版本名只接受三/四段数字或 `<源码版本>-nightly.YYYYMMDD.<run_number>`。
自动发布禁用内置 Provider 凭据（`OOB_BUNDLE_LLMTHU_PROVIDER=0`）。
构建或上传失败仅保留 draft，同一源码 SHA 可重跑恢复；不同提交不能接管同 tag 的旧 draft，
需要修复源码时提升源码版本。发布过程不上传上游 Worker，fork 不镜像 CNB。
内置 Pi ACP 安装资产通过 `tools/pi-acp` 的 `npm run package:android` 生成，
发布前 `package:check` 核验源码、锁文件和运行时修订。打包文本及摘要统一为 LF，
Windows CRLF 与 Linux 检出应生成相同身份，不能通过关闭检查规避 stale 资产。
CI 保留完整历史 gitleaks 扫描；已核实的测试夹具/生成摘要误报只在
`.gitleaksignore` 按历史提交、路径、规则和行号精确排除，不忽略整个文件或规则。
更新禁用变量只注入签名构建；签名前 debug 门禁使用普通构建配置。
原生恢复测试显式模拟重启前后 boot ID，读取能力测试不授予 Root，避免依赖 CI 宿主环境。
签名校验兼容 SDK 的 signer 编号及 min/max SDK 范围输出，但必须得到唯一证书摘要并与仓库变量一致。
首次启用 fork 时，还需确认仓库级 Actions 已允许运行：
`gh api -X PUT repos/ZipperCode/OmniBot/actions/permissions -F enabled=true -f allowed_actions=all`。
单独 dispatch 成功不代表 push/schedule 已生效；以之后一次真实 main 推送产生的 `event=push` 运行验收。

<h2 id="architecture">架构概览</h2>
```text
OpenOmniBot/
├── app/                        # Android 主宿主模块：入口、Agent 编排、系统能力、MCP、前台服务
├── ui/                         # Flutter Android UI 模块：聊天、设置、任务和记忆
├── webchat/                    # React + TypeScript WebUI；由 Vite 构建并通过 Android 打包静态产物
├── baselib/                    # 基础核心库：数据库、存储、网络、模型配置、权限等
├── assists/                    # 公共任务生命周期与聊天/模型协调
├── uikit/                      # 原生浮层 UI：悬浮球、覆盖层面板、半屏界面
└── ReTerminal/core/            # 内嵌终端体验相关模块
```

<h2 id="community">其他</h2>

感谢 [LINUX.DO](linux.do) 等社区开发者对 OpenOmniBot 的支持。

特别感谢这些优秀的开源项目：

- https://github.com/RohitKushvaha01/ReTerminal
- https://github.com/OpenMinis

<table align="center">
  <tr>
    <td align="center">
      <img src="https://omnibot.omnimind.com.cn/community/wechat-qr" alt="WeChat Group" width="220"/><br/>
      <b>WeChat Group</b><br/>
      <a href="https://discord.gg/WnBvBXgykD">加入 Discord 社区</a>
    </td>
  </tr>
</table>
