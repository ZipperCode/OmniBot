# 手机 Agent 改造执行记录

## 目标与已确认边界

- 需求来源：2026-11-06 用户附件 `local://paste-1.md`，完整覆盖 F01–F21 和验收 A–E。
- 用户已明确要求直接分项实现；设备型号、ROM、KernelSU、Vector/LSPosed 版本留到真机验收阶段，不阻塞功能开发。
- 现有能力优先复用；缺失能力核对 GitHub 参考源代码后移植，记录来源与固定 SHA。实现适配层不等于另建 Agent 协议。
- 只使用既有 `Conversation -> ACP Session -> Turn -> Item` 生命周期、reducer、权限请求、工具与工作区。不得添加并行 Agent 状态机、私有事件协议或回放逻辑。
- 默认按任务授权；用户手动切换全权模式后不逐项确认。保留真实 UID、撤销、停止、取消与会话隔离约束。
- 手机独立执行分析、修改、依赖安装、编译、签名、安装、验证。电脑上的项目检查不作为手机构建验收。
- 不保存密码、不自动解锁、不绕过人工认证。不自动增加未确认的模块回滚与备份策略。
- 保留起始浏览器悬浮层 WIP、已有 `android-virtual-screen/` 目录及其他未提交内容；不提交、不推送、不丢弃。当前分支 `main`，任务开始时已有 15 个未暂存路径及 7 个未跟踪条目。

## 生命周期与所有者

| 层次 | 既有所有者 | 改造约束 |
| --- | --- | --- |
| Conversation | 工作区与持久对话历史 | 开发项目、产物、恢复证据归属工作区；不覆盖用户历史 |
| ACP Session | LocalAcpRuntime / XiaowanAcpConnection | 权限模式、请求、取消继续走官方 ACP |
| Turn | 既有 prompt reservation 与执行句柄 | 只授权当前逻辑任务；重启恢复先读证据，不重放已发生的副作用 |
| Item | 工具结果、artifacts 与现有 reducer | 命令成功与行为验收分开记录；手机操作显示目标屏幕 |

## 包划分与写入所有权

| 包 | 负责人 | write_scope | deny_scope | 检查与依赖 |
| --- | --- | --- | --- | --- |
| A 授权模式 | `Authorization` | 既有 ACP 权限策略、privileged 核心及 `PrivilegedToolHandler`、相关模式 UI 与行为测试 | 浏览器 WIP、注册/schema、manifest、其他叶子路径 | 默认任务授权、全权即时生效/关闭、模型不能伪造授权；接入 D 的管理控制器 |
| B 双屏与输入 | `Displays` | `androidgui/`、新增 `DeviceDisplayToolHandler`、既有 `VlmToolHandler` 和 OmniFlow 执行接点、对应测试与 `third-party/display-*` | 公共注册、manifest、其他包路径 | 完整 GUI 任务屏幕租约、目标显示路由、停止/接管、锁屏等待 |
| C 手机开发闭环 | `Development` | 新增 `agent/development/`、`assets/phone-development/`、对应行为测试与 `third-party/phone-development-*` | 公共注册、manifest、其他包路径 | 读取真实 AndroidHarness 源码并适配现有 Alpine/ARM64；本机构建签名与已有项目维护 |
| D Root 与模块管理 | `RootManagement` | 新增 `baselib/.../privileged/management/`、对应行为测试、`third-party/root-management-*` | 既有 privileged 核心、公共注册、manifest、其他包路径 | 注入既有命令通道、操作后读回、框架版本探测；配置读回不等于 Hook 生效 |
| E 后台与恢复 | `Continuation` | `task/runtime/`、`workspace/schedule/` 与对应行为测试，含 `TaskRuntimeToolHandler` | ACP/privileged 公共核心、注册、manifest、其他包路径 | 通知停止/认证等待、持久进度与正式新 Turn 延续，不重放已发生副作用 |
| R 参考核验 | `MapReferences` | 仓库外只读资料 | 禁止改项目 | 固定参考 revision、源码与兼容边界 |
| I 公共整合 | 主 agent | 工具注册/schema、manifest/build 接线、`AgentRuntimeContracts`/`OmniAgentExecutor` 的身份注入、系统执行指导、执行记录、术语及最终验证 | 浏览器 WIP；叶子活跃写集 | 公共单一写入者；所有叶子结束后统一构建和运行检查 |

共享文件只有一个写入者；范围外修改先交整合者。叶子不得提交、安装依赖、改全局配置、启动 formatter/generator、运行设备写入或中途构建测试；全部实现完成后集中检查。

## 需求跟踪

| 需求 | 实施归属 | 状态 | 真机证据与剩余边界 |
| --- | --- | --- | --- |
| F01 任务执行与实际结果 | C/I，复用现有 Agent | 单一 ACP；A/B/C 全流程通过 | 真实错误迭代、行为证据与官方终态分别记录 |
| F02 手机代操作与接管 | B | 真实跨 App 计算与状态保留通过 | 停止入口已观察官方 cancelled；人工确认通知恢复通过 |
| F03 主屏与后台虚拟屏 | B | 主屏与公开虚拟屏真实交互通过 | 目标机后台屏 `3+4=7`，主屏保持聊天；不承诺所有 App |
| F04 真实 UID 0 | A/D，复用 Root 后端 | KernelSU 与真实 Root 命令通过 | 持久命令输出 UID 0；停止后会话关闭；撤销覆盖由既有测试保证 |
| F05 任务授权与全权 | A | 实现已接入；授权与模式回归通过 | 模式显示、切换、停止及撤销 |
| F06 应用与系统管理 | D | 代表性安装更新卸载、设置读回通过 | 未穷举所有 ROM 开关与应用权限组合 |
| F07 受保护数据 | D | UID 0 文件创建、备份、修改、恢复通过 | 隔离标记文件元数据一致；未动用户私有数据 |
| F08 Root/LSPosed 管理 | D | Vector 实际安装、启用、scope 与卸载通过 | 正式模块仅 Calculator，用户已有模块不变 |
| F09 启动与内核级操作 | A/D | 实现已接入；管理行为回归通过 | 按具体设备确认分区、内核与 SELinux 支持 |
| F10 项目新建导入克隆 | C | 真实手机新项目与 RedClock 导入通过 | 原始固定上游保留，正式项目可继续修改 |
| F11 APK/DEX/资源分析 | C | 目标机真实 APK 提取与符号分析通过 | 对应 Calculator 85006267/hash，不泛化到其他版本 |
| F12 临时运行时探测 | C/D | 实际 Hook 命中、用于正式实现并清理通过 | 包、scope、项目均已移除，正式 A 仍生效 |
| F13 模块开发维护 | C | 新 A 与已有 RedClock 修改通过 | 真实修复可访问性与颜色覆盖问题，不只生成模板 |
| F14 手机编译签名安装 | C/D | ARM64 手机上构建、v2/v3 签名与安装通过 | A/B 正式 APK 与源码保留；Gradle 复杂工程未穷举 |
| F15 启用/作用域/重载 | D | Vector 配置读回与实际效果通过 | `configVerified`、运行期 Hook 与视觉效果不混同 |
| F16 操作验收并修复 | B/C/D | 横幅、算式及红色显示真实迭代通过 | 安装或日志成功不替代实际截图和数值 |
| F17 重启与任务延续 | E | 整机重启、解锁后同会话延续与不重放实测通过 | 唯一检查点 `FINISHED`、准入 1 次、官方 `end_turn`；标记大小与 mtime 均未变 |
| F18 文件终端Git | C，复用现有工具 | 手机实际克隆、导入、编辑与构建通过 | 未建立第二套 IDE 或 Agent 生命周期 |
| F19 后台锁屏与认证等待 | E/B | 后台传输、普通 Keyguard 通知与解锁恢复通过 | 当前无安全锁凭据，首次安全认证场景仍待实测 |
| F20 编译并行与一路手机输入 | B/E | 手机 12 次真实编译期间 GUI 计算 `6×7=42` 通过 | 输入租约的竞争边界已有回归，完整双任务交互仍按实况确认 |
| F21 本机进度日志停止接管 | B/E/I | 真实通知等待恢复、停止后官方 cancelled 通过 | 不由文案或点击受理推断终态 |

“实现已接入”不自动表示真机通过；上述具体证据与剩余限制分别记录。最新 GUI 定向回归 20 项通过；Flutter 模式与授权相关四组测试 102 项通过，静态分析无 error，仍有 537 条 warning/info。

完整 `:baselib:testDebugUnitTest` 最终通过 367 项（57 组，零失败、零跳过），未用测试过滤器。测试运行时仅使用已缓存的 Robolectric SDK 34，禁止自动下载；宿主 Shell 直接使用真实可执行文件，避免 Windows Git 启动桩留下持有管道的子进程。

## 当前证据与下一步

- 已读现有直接 Root、ACP 与工具注册契约。`PrivilegedCapabilityManager.Access` 绑定 Conversation/ACP Session 与授权代次；结构化管理继续注入该执行通道，不能绕开撤销/取消。
- 已确认工具环境没有配置 LSP server。codegraph 部分完整路径查询返回非目标正文，已上报；按其遗漏列表精确读取目标范围，不把缺失结果当作事实。
- 已连接 fuxi / Android 17 / KernelSU 3.4.2 / Vector 2.2；A、B、C 已完成真实手机全流程，D、E 继续验收。电脑构建与测试不替代手机行为证据。
- 本机默认 `java` 指向 JDK 8，但已存在 JDK 21；后续构建仅按命令选择可用 JDK，不修改全局配置。
- 参考调查记录位于 `local://references-map.md`；各实现者必须重新读取其实际移植源码，报告中的包名、数据库 SQL、性能与设备支持结论未经运行验证，不直接采用为事实。
- 公共工具目录已接入三类 handler 的统一 definitions 聚合，主生产 Kotlin 与应用测试 Kotlin 已编译通过；串行 assemble 已产生本轮 Debug APK 并验签通过。应用完整回归仍有已记录失败，打包产物不能当作业务验收通过。
- 工具执行环境已从 `OmniAgentExecutor` 的真实参数传入 `conversationId`，不再让等待/恢复工具从文字或时间猜测对话归属。任务身份缺失时不可伪造 `0` 作为持久目标。
- 安全锁屏不因虚拟屏而绕过；不默认禁用系统 RescueParty 等救援机制。完整多步骤 GUI 任务持有屏幕租约，不能仅逐次点击加锁导致两个任务交替干扰。

## 实现接点与验证边界

- 手机开发工具统一通过 `phone_development` 暴露；项目创建/导入、APK 分析、临时探测、手机构建与部署沿用工作区、终端及 ACP 权限请求。`task_control` 负责当前会话的检查点、只读对账与人工认证等待，不定义第二套 Agent 生命周期。
- Root 管理动作通过既有 `PrivilegedCapabilityManager.Access` 派发；`backend`/`provider` 是路径标签，`uid` 只表示实际观察的执行身份。未执行、拒绝或身份未知不能按后端枚举补写 UID 0。
- 显示服务复用 IzukiJS 的虚拟屏与帧捕获实现；KernelSU 辅助进程由既有持久特权 Shell 托管，Binder 经一次性显式包广播交接。短输入租约只互斥输入，会话取消/关闭及显式销毁负责释放虚拟屏与辅助进程。设备 ROM 的真实兼容性尚未验收。
- 全权模式属于真实 ACP session；首次 prompt 前设置，切换会话按该会话配置回读。任务授权的结构化 scope 不跨 Turn 继承，退出全权会清理旧授权及待处理审批。
- 检查点记录原会话、Agent、Turn 与重启前事实；延续使用同一会话的新官方 prompt。断连不伪造官方终态，未决请求不自动重发，持久证据损坏不以空数据覆盖。

### 已观察的工具链烟测

在电脑上使用仓库模板与已安装工具执行了资源编译、Java 8 字节码编译、D8、对齐、签名与验签。Xposed API-82 仅作为编译期 classpath，未作为 DEX program input。Google 官方 build-tools 35.0.1 的 `apksigner.jar` 实际输出版本 `0.9`；临时产物验签输出 `Verifies`，v3 签名有效。一次性签名密钥已清理。

该证据仅证明模板与 Java CLI 可执行，不证明 ARM64 Alpine PRoot、目标手机安装、作用域或 Hook 行为。新建工程中的 Hook 模板也不是某项用户功能已经实现的证明。

真实文件系统烟测另行调用编译后的 `restoreTar`，使用实际 tar、stat、readlink、cp 与 sha256sum：含 `./` 根目录和空格文件名的归档恢复成功且 `verified=true`；归档内符号链接、目的目录已有越界父链接分别被拒绝，边界外哨兵未改变、暂存目录已清理。该电脑沙箱执行不证明 Android 私有目录权限或 SELinux 标签已在目标机验证。

APK 分析烟测使用同一真实模板 APK 调用编译后的 `ApkAnalysisService.analyzeApk`：解析到 `cn.com.omnimind.smoke.module`、一个 DEX 与实际 `HookEntry.handleLoadPackage` 符号，SHA-256 与文件一致。未声明的版本保持 `null`，不编造默认版本。该烟测复现并修复了 AXML 属性解码后未写入结果的问题；目标手机的包提取路径仍待真机验证。

### 验收仍受阻的条件

- 已连接 fuxi / 2211133C，Android 17 / SDK 37 / arm64，KernelSU 3.4.2、Vector 2.2（3080，API 102）。A/B/C/D 已实际通过；E 尚待完整确认。手机模块构建、实际 Hook 效果、后台屏交互与普通 Keyguard 等待均已有证据；安全凭据锁屏尚缺前提。
- 用户随后明确授权读取 `.omp/agent/models` 的 DeepSeek 提供商。已仅在内存解析 `models.yml`，将 `kali-completions/deepseek-v4-pro` 经标准输入与本机 JDWP 导入应用现有加密存储，绑定调度、操作和压缩场景。未把密钥放入 ADB 参数、临时凭据文件、源码或输出；未改动原提供商配置。
- 真实聊天已创建 Conversation 1 / ACP Session `16281b3f-b92b-44d9-bca2-47329f0c5aee` 并发起工具和权限请求。首次局域网连接被 Android 权限拦截，经系统 UI 授权后以新 prompt 继续，没有后台重放旧 Turn。
- Google 计算器 `com.google.android.calculator` 的真实提取与 APK 分析已通过：版本 `8.7 (735708245)` / `85006267`，2 个 DEX、3375 个类，SHA-256 `afc22ea6b606b4c798d1a0798c881b5a93792a973b6b2342255e08efbb90c8e3`。现有用户模块与作用域保持不变，仍不宣称任意应用、ROM 或框架版本兼容。
- 电脑构建出的主应用 APK 不能替代“手机本地产生模块 APK”的 F14 验收。构建、签名、安装、配置读回与需求行为验证分别记录，不能互相代替。

## 本轮 Debug APK

- 构建：`:app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` 已独立执行成功，包含 Flutter 与 WebChat 构建路径。
- 文件：`app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`，184518269 字节，`arm64-v8a`，版本 `0.7.0.4`（21），Android 10 及以上。
- SHA-256：`dd0ef82efc49b635f5b58b41f3d6a901f31e2888fac27f0eae4ca4551e64ba15`。
- 官方 `apksigner verify --verbose` 输出 `Verifies`、v2 签名有效、1 个签名者。模块/探测模板、API-82、Flutter 与 WebChat 资源已包含。
- 已通过 `adb install -r -t` 更新到目标手机并启动；真实手机另行生成、安装并启用正式 A 模块，Calculator-only 作用域与横幅实际效果已验证。主应用 APK 和手机生成的模块 APK 分别交付，不互相冒充。
- 构建跳过了宿主依赖安装和 Pi bundle verification；Pi 构建依赖未安装，未擅自运行 `npm ci`，因此该包不作为发布级验证完成的声明。

## 主要源码接点

- `androidgui/`：主屏/虚拟屏路由、特权显示服务、帧捕获、输入租约与锁屏边界。
- `baselib/src/main/java/cn/com/omnimind/baselib/privileged/`：实际执行 UID、授权撤销、Shell 所有权及 `management/` 的系统、文件与模块动作。
- `app/src/main/java/cn/com/omnimind/bot/agent/development/` 与 `app/src/main/assets/phone-development/`：项目、SDK、APK 分析、探测、构建签名部署及 Agent 手册。
- `app/src/main/java/cn/com/omnimind/bot/agent/runtime/`、`agent/tool/`、`task/runtime/`：单一 ACP 授权接线、工具注册、持久对账、等待和通知停止。
- Flutter 聊天模式、草稿会话与权限请求组件：真实 session mode 生效、历史回读与会话隔离；保留既有浏览器改动。
- `app/src/main/AndroidManifest.xml`、`CONTEXT.md` 与 `docs/third-party/`：前台服务/恢复入口、领域术语及固定版本来源声明。

## 最终电脑侧验证结果

| 检查 | 实际结果 |
| --- | --- |
| Debug APK 构建、签名校验 | 独立 assemble 成功，最终 APK 验签通过 |
| `baselib` 完整单元测试 | 367 通过，零失败、零跳过 |
| Android GUI 最新完整单元测试 | 20 通过 |
| Flutter 授权及会话切换相关测试 | 102 通过 |
| Flutter 静态分析 | 无 error，537 条 warning/info |
| `app` 最后完整单元测试 | 1638 项，1619 通过、19 失败、零跳过；未宣称全绿 |
| OmniFlow 完整单元测试 | 64 项中 61 通过；3 项既有 Windows 文件替换失败 |

应用最后 19 个失败均有明确环境原因：10 项既有终端测试在 Windows 启动 `/bin/sh` 失败；9 项需要本机未缓存的 Robolectric Android 10、12.1、13 测试 JAR。未安装依赖、删除这些测试、缩窄其 SDK 覆盖或用过滤结果冒充完整通过。本轮相关的工具链、授权、终态、任务恢复及会话清理失败已修复，并在最后完整执行中通过。

真实电脑烟测还包括：生成探测模块后编译实际 Java 源码、重写 D8 启动器后执行 `sh -n`、受管 Shell 取消后进程退出与读端 EOF。它们与前述模板构建、APK 分析和文件恢复证据都不能替代 A–E 真机验收。

工作区仍在 `main` 原位，未创建 worktree、未提交或推送；保留用户原有未提交改动。一次性烟测脚本、沙箱、示例项目和签名工具已清理，正式源码、测试 fixture、验证记录及主 APK 保留；没有留下本轮长驻服务。

## 目标手机准备与剩余阻塞

- 已在真实应用中开启 Root，并在 KernelSU 的 OmniBot 专属条目授权；应用内重新申请后，真实 Root 探测退出码为 `0`、stdout 为 `0`，页面显示“已授权 · 优先使用 Root”，持久状态 `enabled=true`、`previously_granted=true`。该证据仅证明权限探测，不是 D 项全部业务操作通过。
- 已通过系统页面开启 OmniBot 无障碍、悬浮窗及通知权限；原有 Eta 无障碍服务保留。应用页面显示 `4 / 4 项核心授权已就绪`，悬浮窗 AppOp 为 `allow`，通知 AppOp 默认模式为 `allow`。未申请日历、联系人等无关个人数据权限。
- 更新包已解除 Xposed API-82 下载的 HTTP 404，并补齐 isolated toolroot 的受信任 APK 公钥；保持签名校验，真实 `android_sdk_install` 全流程已通过，版本与运行结果见下文。
- Vector CLI 的 `--json status` 返回成功；模块列表使用 `modules ls`，作用域使用 `scope ls/add/rm/set`。只读核对过实际命令与返回结构，未更改用户已有模块的启用状态或作用域。
- Android Java DAP 适配器不可用，已使用现有 JDK 的 `jdb` 通过 JDWP 观察真实应用的 Root 探测变量；调试器已正常退出，临时 ADB 端口转发已移除。未安装调试依赖，未修改运行时代码。
- A/B/C/D 的具体验收已完成，E 的安全锁屏前提仍未满足。已通过真实会话验证 `show_touches` 的 `1 → 0 → 1` 读回与恢复；受保护标记文件在 UID 0 下完成 0600/0:0 创建、读写、备份、修改和恢复，内容、大小、权限、属主及 mtime 与创建时一致。未触及用户私有数据。
- 主屏统一 `0..1000` 坐标后，真实计算器完成 `7×8=56`。移除普通启动的 `FLAG_ACTIVITY_CLEAR_TASK` 后，正式 A 模块验收中 `2+3=5`、设置页往返后的结果保留和横幅不重复均已实测；前台包名也正确返回计算器。
- 持久 Root 修复已在真实应用通过：会话创建与命令均确认 UID 0，`id -u; printf ROOT_SESSION_OK` 返回退出码 0 和实际输出，读取记录一致，停止后 `alive=false`。没有以 PRoot 的模拟身份冒充 Root；调试器与临时转发均已关闭。
- 主屏与后台虚拟屏完整交互已通过：真实创建公开显示屏 4（1080×1920@420），应用可枚举并读屏截图，在该屏计算 `3+4=7`，A 横幅恰好一个。主屏保持聊天，未被硬切；销毁后列表和系统 `dumpsys display` 均确认无本次虚拟屏残留。
- 正式兼容资产已接入 SDK 安装，只对 Java 启动器加载，不修改 SELinux 或 Root 编译。真实 `android_sdk_install` 已完成：隔离根安装 `android-build-tools 16.0-r3`，`javac 21.0.10`、aapt2、D8 8.2.42、apksigner 0.9、zipalign 和平台 35 均就绪。清理本任务探索期遗留、遮蔽正式启动器的四个旧 Java 包装器后，真实应用中 `command -v java` 为 `/usr/bin/java`；同一源码编译并连续运行三次均输出 `OMNI_PHONE_JAVA_OK`，非 Java 子进程不继承兼容预加载的检查均为 true。
- B 项已完成：真实手机克隆并导入 `rovo89/XposedExamples@f13ad5d869512b9893cb5df1a5b30b08f6af4bc0` 的 RedClock，保留 `acceptance-B/upstream`，仅修改导入副本 `acceptance-B/project`。独立包 `cn.com.omnimind.acceptance.redresult` 保留红色显示意图、移除文本追加，迭代修复生命周期一次着色被覆盖的问题；实际计算 `7×8=56` 后数字呈红色，A 横幅仍恰好一个。手机构建 APK 12,762 字节、SHA-256 `8a026918ce2ff10fdd738f8ab4a31432a9fa8894c1ab3a4c22cfa2db2f4e4235`，v2/v3 签名有效，Calculator-only 作用域与配置读回通过。
- E 项已有真实传输与 GUI 并发、12 次手机编译期间计算 `6×7=42`、人工等待通知恢复及普通 Keyguard 挂起恢复的证据。只读 `lock_settings` 明确当前 `CredentialType=NONE`，未设置安全凭据；已告知用户自行设置 PIN/密码，不索取或保存秘密，因此不把普通锁屏当作安全认证验收。
- A 项已完成手机创建、修改、构建、签名、安装、Calculator-only 作用域和实际行为验证：正式模块 `cn.com.omnimind.acceptance.calctitle` 显示一个位于状态栏下方的非交互 `Omni A` 横幅，算式结果正确且跨应用保留。APK 12,761 字节，SHA-256 `b4b4b47c2d085469e590fa032bd280be03461bf8136a5764b94ffcb8e86906f2`，v2/v3 签名有效。部署读回字段已修正，真实 `configVerified=true`，不伪造 `hookVerified`。
- C 项已完成：临时探测在真实目标 PID 中命中 `Calculator.onCreate/onResume` 的调用前后日志，正式 A 据此实现并生效；随后 `probe_clean` 返回卸载与项目清理均为 true，实际包查询、项目路径、框架模块列表均证实探测已移除，正式 A 仍启用且横幅恰好一个。
- D 项代表性的跨应用、设置修改与恢复、保护文件备份恢复、UID 0 命令、模块安装配置与探测卸载均已实际通过。不代表已对本机执行破坏性分区写入、SELinux 策略修改或内核级变更；这些能力仍按具体设备和任务范围验证。

## 手机源码与模块交付

手机正式产物保存在 `/workspace/deliverables/`，即应用数据目录的 `workspace/deliverables/`；本次会话也提供同名下载文件。两个 APK 直接从 `pm path` 返回的当前安装路径导出，摘要与手机构建版本一致；电脑导出后再次逐字节核对相同。

| 文件 | 字节 | SHA-256 |
| --- | --- | --- |
| `acceptance-A-calctitle-src.zip` | 24861 | `df34cad2113551277eb458af69b42b23a19a043122b67c828708b8942f639d5f` |
| `acceptance-B-redresult-src.zip` | 24210 | `b231b3572accf53debfeace01a06435656ff38703cdbac1ec9289df248ee9467` |
| `acceptance-A-calctitle-installed.apk` | 12761 | `b4b4b47c2d085469e590fa032bd280be03461bf8136a5764b94ffcb8e86906f2` |
| `acceptance-B-redresult-installed.apk` | 12762 | `8a026918ce2ff10fdd738f8ab4a31432a9fa8894c1ab3a4c22cfa2db2f4e4235` |

- 源码包分别包含 8/7 个实际文件：模块源码、清单、资源、Xposed 入口、API-82 和真实构建脚本；不含签名私钥、keystore、`.git` 或构建中间产物。重新构建需使用自己的签名身份，不承诺可覆盖当前签名安装。
- 正式工程保留在 `/workspace/acceptance-A/calctitle`、`/workspace/acceptance-B/project`；固定上游保留在 `/workspace/acceptance-B/upstream`。A/B 均已启用，作用域仅 `com.google.android.calculator`；未修改用户其他模块。
- 已确认有限构建、下载、限速作业均结束；只删除本任务 `acceptance-sdk-smoke`、`acceptance-E/concurrent`、构建 stderr 临时日志及 `/data/local/tmp/omnibot-root-acceptance`。保留 `acceptance-E/build-overlap.log`、正式工程、产物与目标 APK 分析文件；临时 ADB 转发为空。

## 真实整机重启验收进度

- 通过真实聊天调用一次 `android_privileged_action` / `device_control.reboot` / `mode=normal`，仅使用动作自带的持久检查点；没有额外造第二个检查点或直接注入业务状态。
- 实际 boot ID 从 `810e5d23-0315-46e8-9b42-f52a028f8634` 变为 `eff7c5d2-16d6-4d4d-aeb0-e1a27cbde906`，设备断连重连且 `sys.boot_completed=1`，证明为整机重启。
- 用户本机手动解锁后，唯一检查点 `3c8f60c4-273e-4d72-abe1-f59b26cc95ba` 通过原协调器完成 `RESERVED → ADMITTED → FINISHED`，`attemptCount=1`；仍绑定 Conversation 1 / 原 ACP Session。延续 Turn 为 `0a9fb1bf-9641-49eb-b26f-15eda368ca4e`，同一 `continuationRequestId` 在官方 journal 中仅一条，终态 `end_turn`。
- 真实延续仅只读 boot ID、标记与 `task_control status`；没有第二次重启或追加标记。`reboot-once.marker` 仍为 9 字节单行，mtime 与创建时相同；再次切前后台没有新条目或第二次准入。普通 Keyguard 等待恢复已证实；设备仍 `CredentialType=NONE`，安全凭据首次认证场景不宣称通过。
- 本次重启另复现并修复通知缺口：CE 仍为唯一检查点，设备保护区只同步一个 `has_pending` 布尔通知投影；独立轻进程响应 `LOCKED_BOOT_COMPLETED` 发布泛化通知，不初始化凭据、数据库、Agent 或前台执行租约。普通锁屏复用原协调器，准入或已无待恢复项后撤销通知；真实打开应用也调用同一协调器，不假定所有 ROM 都送达静态 `USER_PRESENT`。
- 最新定向回归共 23 项通过：`TaskContinuationCoordinatorTest` 18 项与 `TaskRuntimeToolHandlerTest` 5 项，零失败/跳过；覆盖有/无检查点通知、Direct Boot 不读 CE、重启前短暂锁屏仍保留下一次开机提示、解锁单次准入、终态清理及 `end_turn` 对账。统一 assemble 成功，最终 APK 验签与更新安装成功。首次构建遇到 PowerShell 参数拆分、Windows 跨盘 Kotlin 增量缓存及新测试 fixture 字段错误，均已修正；只通过单次 `-Pkotlin.incremental=false` 避开跨盘缓存，不删除缓存或改全局配置。
- 新包在真实锁屏现场显示“小万任务等待解锁 / 设备已重启，请解锁屏幕以恢复任务执行”，通知 ID `2048119`、attention 渠道、私密可见性与泛化 publicVersion 均由系统确认；解锁准入后已撤销。等待和完成截图分别为本次会话 `phone-f17-wait-notification-fixed.png`、`phone-f17-continuation-finished.png`。
- 完成后另复现聊天输入框仍显示“停止”：真实 VM 中 ACP 活跃预留和 TaskRuntime 租约均为 0，根因为后台 `session/prompt` 没有 Flutter awaiter，官方响应未到共享 reducer。现已沿既有事件通道投影同一次官方 JSON-RPC response，使用原 session/turn/request 身份与同一 reducer 幂等收口；不根据消息内容推断结束、不合成 `turn/*` 终态。后台宿主预留无需 Flutter dispatch 即可准入；前台重复回包不重复投影，新请求准入空窗内的旧成功或错误回包均按 requestId 隔离。旧 pending 工具仅沿既有历史展示策略标记为中断，不补造旧 Turn 的官方终态。
- 运行中开页验收还真实发现并修复两个问题：定时广播原先持有至整个 prompt 结束，触发系统 ANR；subagent 事件原先落入 normal runtime，与 Agent 页面分裂。广播现立即交给既有 `TaskRuntimeService`，服务仅持有平台执行期限，ACP 仍是唯一业务所有者；调度尾部和其他活跃租约未结束时不提前停服务。subagent 事件统一进入页面使用的 Agent runtime，真实 EventChannel 回归先失败、修复后通过。
- 最终包已再次真实执行 60 秒后台工具任务：从首页在运行中打开子会话，同页观察“执行终端命令 / 停止 → 已处理 1m 1s / 发送”，实际输出 `OMNI_LIVE_HOST_FIXED_OK`，无需重载或手动停止。系统广播记录为 24ms 完成；运行时前台服务有效，结束后仅本任务宿主退出，独立终端服务保留；无新增 ANR，唯一一次定时配置自动移除。父子会话切换和重入后正文保留、两者均空闲，同一 request 的 journal 仅一条 `end_turn`，原 F17 检查点仍 `FINISHED / attemptCount=1`。运行中、完成与重入截图分别为 `phone-scheduled-host-fixed-running.png`、`phone-scheduled-host-fixed-complete.png`、`phone-scheduled-host-fixed-reopen.png`。
- 最终定向回归：Flutter reducer/runtime/service 与真实聊天/历史/抽屉相关既有测试共 603 项通过；Native 历史/准入/并发/Na 流式与恢复 78 项通过，广播宿主/租约/检查点/授权等待相关另 46 项通过（含重跑的 7 项并发测试），均零失败/跳过。Na 已准入后的快照/SSE 异常保留本请求身份，经相同官方错误投影收口；未准入不伪造终态。静态分析无 error，既有 warning/info 保留。最终独立 assemble、v2 验签与真机更新安装成功；未重跑完整仓库回归，前述既有环境失败及 Pi 验证限制仍有效。
