# KernelSU 与系统操作接入方案

> 历史方案：2026-11-06 同步上游 0.7.0.4 后，统一来源选择已替换为上游 Root 开关与 Shizuku 实现。本文描述旧实现；下列验收结论不适用于新版。

状态：代码、聚焦测试与 debug APK 构建已完成；Gate 3 attempt 2 为 `PASS_WITH_DOCUMENTED_LIMITATIONS`。Gate 4 attempt 3 通过本机应用 Root 授权与单次只读工具调用的受限验收，详见 `kernelsu-device-verification.zh-CN.md`。完整管理器集成覆盖与真实 root 会话生命周期仍未完成。

## 目标与边界

在设备已有可用 `su` 且用户明确选择并授权的前提下，使现有特权工具可通过标准 `su` 执行，同时保留 Shizuku 作为显式可选来源。来源之间不自动推断、不自动回退，失败后也不换路重放。

本次不开发 KernelSU 模块，不获取设备 root，不修改内核、SELinux 策略、系统挂载或挂载命名空间，不刷写设备。既有类型化动作与经确认的 shell 可修改系统设置、应用权限和设备开关。KernelSU 的真实可用性仍须真机验证。现有外部 `AndroidDeviceMcpServer` 继续保持非特权范围，不向外部 MCP 客户端开放任意高权限 shell。

保持唯一的 ACP `Conversation → Session → Turn → Item` 生命周期，不新增 reducer、事件流、业务状态机、重试路径或私有协议。GUI 截图、点击、滑动继续使用现有 Android GUI 能力。

## Gate 1 确认的模型

### 来源与实测权限

执行来源和进程实际权限是两个独立维度：

- `PrivilegedSource`：`NONE`、`SHIZUKU`、`SU`，仅表示启动路由。
- `PrivilegedAuthority`：`NONE`、`ADB`、`ROOT`，由已启动目标进程的 UID 探针确定。
- UID `0` 映射为 `ROOT`，UID `2000` 映射为 `ADB`，其他 UID 映射为 `NONE`；状态同时保留原始 `uid`。

共享执行器必须由启动器注入实测 `authority`。直接 `su` 路径严禁使用应用进程的 `Process.myUid()` 推断子进程权限；Shizuku 路径同样以目标服务中启动的进程为准。KernelSU 是可能提供标准 `su` 的实现，不是权限等级。

### 单一执行栈

已实现中立命名的共享 `PrivilegedShellLauncher`，其启动结果同时返回目标 `Process` 与该进程的 `PrivilegedAuthority`（以及原始 UID）。它只负责按已绑定来源启动和测量，不复制动作策略、命令执行或会话管理。

实现继续复用共享执行器、动作策略和 shell 会话管理器；未引入 libsu，也未建立平行执行器或第二套策略。原 `ShizukuCapabilityManager`、相关共享包名和类型已整体迁移为中立的 privileged 命名，受影响的 Kotlin 引用和 AIDL 已同步更新，未保留旧类型别名。

来源专用启动行为如下：

- `SHIZUKU`：在 Shizuku 服务进程中使用 `sh` 启动命令。只允许在命令分派前完成服务绑定；命令一旦分派，Binder/服务异常直接作为本次失败返回，不重放请求。
- `SU`：在应用侧使用标准 `su` 启动命令；候选路径和启动规则以“Gate 1 冻结补充”为准。
- `NONE`：拒绝启动。

固定路径中存在普通文件，并不能证明 `su` 可执行或可授权；KernelSU 的虚拟 `su` 可能只在特定环境可见。候选路径、虚拟 `su` 的可见性及授权行为是必须真机核验的风险，不能由文件存在性提前断言。本文不据此声称存在法律禁令，也不把命令过滤描述成 root 安全沙箱。

## 状态、授权与桥接契约

### 持久选择与进程内状态

- 来源存入 baselib 的 MMKV，键为 `privileged_source_v1`，值为 `none`、`shizuku`、`su`。
- 默认值为 `none`；不得根据 Shizuku 安装、管理器安装、`su` 文件或历史授权自动选择来源。
- 授权结果只做进程内缓存，不持久化。被动状态读取、应用启动、工具注册和工具可见性判断均不得启动 `su` 或弹出授权。
- 用户主动调用授权时才启动所选来源并执行 UID 探针。结果按 UID 映射为 `ROOT`、`ADB` 或 `NONE`。
- 缓存仅表示最近一次探针结果。后续每个实际启动结果仍必须携带并检查自身实测权限，不得盲信缓存仍然有效。

只要来源不是 `NONE`，相关工具即可见；实际执行前必须检查当前来源的 grant。未授权时返回明确错误，不得自动发起授权，也不得尝试另一来源。

### Flutter/原生权限接口

复用现有权限事件通道，将权限 ID 统一为 `privileged`。`SpecialPermissionEvent` 的方法精确为：

- `getPrivilegedStatus`
- `setPrivilegedSource({source})`
- `authorizePrivilegedSource`
- `openPrivilegedSourceApp`

状态返回结构为：

```text
{
  selected,
  granted,
  authority,
  uid,
  code,
  message,
  availableActions,
  shizuku: detail | null,
  su: { binaryPath } | null
}
```

`selected` 使用 `none|shizuku|su`，`authority` 使用 `none|adb|root`。`granted` 必须由所选来源的有效探针结果导出；`availableActions` 必须与实测 authority 和现有策略一致。未选择或未探针时 `uid` 可为空，来源详情不可用时相应字段为 `null`。

`openPrivilegedSourceApp` 在 `SU` 来源下固定返回 `false`，不得猜测 KernelSU 或其他 root 管理器的包名/Intent；Shizuku 来源只使用已核实的现有入口。删除不可达的 health-check bridge，不增加未要求的自检 UI，因为用户触发的授权本身就是探针。其他未使用 helper 只能在全局引用检查证明无引用后删除。

## 命令、进程与会话契约

### Gate 1 冻结补充

- Gate 1 复审通过。状态 `code` 闭集：`not_selected`、`shizuku_not_installed`、`shizuku_not_running`、`shizuku_binder_dead`、`shizuku_permission_denied`、`su_not_authorized`、`su_denied`、`su_unavailable`、`authority_unexpected`、`granted`。
- `granted` 当且仅当 code 为 `granted`；未授权时 authority 为 `none`、availableActions 为空。UID 仅在探针确实取得有效数值时提供，不能仅因进程启动就推断 UID。
- Shizuku 明细固定为 `installed/running/binderReady/serviceBound/permissionGranted` 布尔字段和可空整数 `version`；仅所选来源的明细非空。su 的 `binaryPath` 仅在实际启动后记录，被动读取不探测文件系统。
- 前三个桥接方法返回 status map，`openPrivilegedSourceApp` 返回 bool；授权失败通过状态 code 表达，非法来源参数和桥接故障才使用平台错误。Dart 不解析 message，按 code 和 authority 显示中文文案。
- 固定绝对路径候选直接尝试启动，不用 `File.exists()` 预筛虚拟 su。只在尚未成功创建进程的启动错误时尝试下一路径；授权拒绝或执行失败不得改路径重试。不采用裸名 PATH 回退：shell 返回的 UID 文本不是恶意程序防伪机制。
- 启动、取得 UID、策略鉴权、写入业务载荷、读取结果，严格依次执行；鉴权失败不写载荷。`NONE` 的可见动作为空且不支持任何动作。
- 取消与收尾先关闭 stdin，再尝试销毁并确认退出；应用 UID 可能无权杀死 root 进程。不能确认退出的会话须终止逻辑访问、移出路由表并如实报告可能遗留的进程，不能阻塞来源切换或声称物理终止成功。
- 删除不可达 health-check 的整条原生与 Dart 链路，不保留会自行设置 confirmed 的旁路。
- UI 仅修改现有权限页的来源选择、授权和状态展示，不新增诊断页、自检页或 root 教程页。

### 参数和 shell 语义

动作策略、参数校验、ACP 确认和工具 schema 保持一套。直接 `su` 不得绕过任一层。

- 类型化 argv 若最终交给 shell，必须逐个参数进行可靠 shell quoting，再连接成命令；不得只转义整串，也不得直接拼接用户参数。
- raw shell 若采用简化启动方式，将不再是 login shell；这是显式语义变更。实现和使用说明必须记录其环境初始化、profile 加载和命令可见性可能与旧行为不同，不得暗中恢复第二条路径。

共享执行器和 `RollingTextBuffer` 都必须在读取时实施明确上限，而非读取完毕后再截断。单次执行和持久会话均须有界输出、超时和资源回收。

### 会话所有权和来源切换

能力管理器是 `sessionId → source` 的唯一所有者：

1. 每个请求在准入时绑定一次来源，后续不得重选或回退。
2. 来源选择变更与请求准入必须串行化。
3. 切换来源前先停止旧来源的全部会话，再提交新选择。
4. 已创建会话永久绑定创建时来源；来源变化不能把旧会话重定向到新后端。
5. 这些规则扩展现有会话管理器，不创建新的 Agent/ACP 生命周期。

取消必须可中断等待和读取，并对直接持有的 `Process` 调用销毁；在没有设备证据前，不承诺能终止其派生进程树。超时采用相同资源回收路径。

root 授权在会话中途撤销时，已经以 root 身份运行的进程可能继续存活；不得声称能即时检测或即时降权。只有在后续探针、启动或命令结果观察到授权失败时，才清除 grant 缓存，保留原来源，并将该进程失败直接上报；不得自动授权、换路或重试。

错误至少应区分：未选择来源、未授权、UID 不符合要求、启动失败、SELinux/系统拒绝、命令非零退出、超时、取消和传输中断。错误分类不得改变 ACP 官方完成、失败或取消语义。

## 三阶段交付与三个 Oracle Gate

### 阶段一：调研与架构

- 核验现有执行器、策略、会话、AIDL、权限桥和工具注册的所有者与引用。
- 冻结本文的来源/权限、状态结构、启动器、会话及错误契约。
- **Oracle Gate 1：已通过。权限、复用边界及冻结行动项已纳入实现。**

### 阶段二：实现与集成

- 一个 native fixer 独占 baselib 与 app 集成写入，完成中立重命名、AIDL/引用迁移、启动器、执行器接线、会话和工具权限接线，避免拆分相互依赖的写入。
- 原生契约冻结后，designer 仅负责 `ui` Dart 的来源选择、授权和状态展示；冻结后可与 native lane 并行。
- 原生测试使用 fake `Process`，不得在 Windows 测试机调用真实 `sh`/`su`。覆盖身份映射、被动状态零进程启动、无路由回退/重放、读取时输出上限、取消销毁直接进程、会话固定来源和切换顺序。
- Dart 测试覆盖桥接 payload、来源选择和主动授权交互。
- **Oracle Gate 2：attempt 3 为 `PASS_WITH_ACTIONS`。后续已完成 SU grant 撤销时的 `permission_denied` 失效处理、取消传播及有界复制/进程清理对账；仍需可执行测试证据确认。**

### 阶段三：验证与说明

- 复用仍有效的阶段二测试证据，运行相关 focused tests 与最终 Android/Flutter 集成构建；不运行全量测试。
- 检查文档、状态字段、UI 文案和原生行为一致，并记录真机尚未覆盖的材料风险。
- **Oracle Gate 3：attempt 2 为 `PASS_WITH_DOCUMENTED_LIMITATIONS`。构建与聚焦行为验收通过；真机验证及完整管理器集成覆盖仍是明确缺口。**

## 使用步骤

1. 打开现有“权限”页面，进入“高权限访问”。
2. 显式选择 `无`、`Shizuku` 或 `直接 Root`。默认是 `无`，应用不会自动推断或切换来源。
3. 选择 `Shizuku` 时先按其既有方式启动服务；选择 `直接 Root` 时，设备必须已经由用户自行配置可用的标准 `su`。
4. 点击“授权”，由所选来源执行 UID 探针；只有 UID `0` 或 `2000` 会形成有效 grant。
5. 授权后再使用高权限工具；失败不会自动换来源或重放。

该入口不会取得 root、安装 KernelSU、创建模块、修改 App Profile、SELinux 或挂载配置。`直接 Root` 只是使用设备已有的标准 `su`。

## 当前验证证据

| 检查 | 实际结果 |
| --- | --- |
| `git diff --check` | 已通过；仅出现工作区 LF/CRLF 转换提示 |
| `:baselib:testDebugUnitTest` 的 privileged focused tests | 5 个 XML，22 项通过，无失败、错误或跳过 |
| Flutter/Dart | Flutter 3.47.2 / Dart 3.13.2；designer 执行报告：5 个测试文件共 30 项通过；针对性分析 0 错误、0 警告、7 项 info 提示。未归档 XML，Oracle 未独立复核原始输出 |
| App focused tests | 2 个 XML，5 项通过，无失败、错误或跳过 |
| APK 构建 | `assembleDevelopStandardDebug` 成功，APK 约 198.34 MB |
| KernelSU/Shizuku 设备验证 | KernelSU Next v3.3.0：应用授权显示 UID 0，Agent 只读 getprop 调用成功；Shizuku 与其他真机场景未验收 |

APK：`app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`。上述证据覆盖构建与模拟测试，不证明 KernelSU 真实授权、系统操作或 root 进程终止行为。

构建使用已获用户批准的 NDK `29.0.14206865`；App、Flutter、JNI 和 ReTerminal 共用根配置。用户批准的五个插件 namespace 修正仅作用于 `fluttertoast`、`gal`、`mobile_scanner`、`jni`、`jni_flutter`，不修改依赖缓存。ReTerminal 两处 sourceJar 改为显式读取 Android 扩展。

Windows 的 Pub 缓存位于 C 盘、项目位于 D 盘，曾触发 Kotlin 增量缓存跨盘路径错误。App 测试和构建仅通过调用参数关闭增量编译并采用进程内编译，未修改用户全局配置。`ui/pubspec.lock` 和 `ui/.metadata` 无生成漂移。

## 可执行验证命令（PowerShell 7）

以下命令均使用 PowerShell；每一步在标注的工作目录单独执行。先准备 Flutter 生成目录，再运行 Gradle。

```powershell
# 工作目录：D:\Project\GitHub\OmniBot\ui
flutter pub get

# 工作目录：D:\Project\GitHub\OmniBot
.\gradlew.bat --no-daemon --no-parallel --console=plain "-Pandroid.builder.sdkDownload=false" :baselib:testDebugUnitTest --tests "cn.com.omnimind.baselib.privileged.*"

# 工作目录：D:\Project\GitHub\OmniBot
.\gradlew.bat --no-daemon --no-parallel --console=plain "-Pandroid.builder.sdkDownload=false" "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" :app:testDevelopStandardDebugUnitTest --tests "cn.com.omnimind.bot.agent.AgentPermissionSupportTest" --tests "cn.com.omnimind.bot.agent.AgentToolDefinitionsPrivilegedTest"

# 工作目录：D:\Project\GitHub\OmniBot\ui
flutter test test/services/privileged_permission_test.dart test/services/permission_service_test.dart test/features/home/pages/authorize_setting/authorize_setting_page_test.dart test/features/home/pages/authorize/authorize_page_navigation_test.dart test/features/welcome/pages/onboarding/onboarding_choice_page_test.dart

# 工作目录：D:\Project\GitHub\OmniBot\ui
flutter analyze --no-fatal-warnings --no-fatal-infos lib/services/permission_service.dart lib/features/home/pages/authorize

# 工作目录：D:\Project\GitHub\OmniBot
.\gradlew.bat --no-daemon --no-parallel --console=plain "-Pandroid.builder.sdkDownload=false" "-Pkotlin.incremental=false" "-Pkotlin.compiler.execution.strategy=in-process" :app:assembleDevelopStandardDebug "-Ptarget=lib/main_standard.dart"
```

这些命令用于复现聚焦验证，不运行全量测试。上方 analyze 是简短复现入口；实际分析覆盖全部本次修改的 Dart 源码和五个相关测试文件。

## KernelSU 真机安全检查清单

所有操作由设备所有者执行。除只读探针外，任何可能修改设备状态的动作都必须再次取得用户明确同意；本文不要求或授权自动执行命令。

- 被动打开高权限页面：确认没有 `su` 授权弹窗、没有自动探针。
- 显式选择 `直接 Root` 后点击授权：核对管理器提示、UID、authority 和状态码。
- 首次只运行只读操作，例如 UID 或单个系统属性读取；不以文件存在替代授权结论。
- 拒绝授权：确认显示拒绝且不尝试其他 `su` 路径、不回退 Shizuku、不重放。
- 取消运行：确认逻辑请求结束；记录是否有无法终止的 root 派生进程。
- 切换来源：确认旧会话先退出逻辑路由，旧会话不能被新来源继续使用。
- 撤销授权后发起一项新执行：确认返回权限失败、清除 grant，且不自动重新授权或重试。
- 产生超过上限的 stdout/stderr 和会话 transcript：确认返回内容有界、应用保持可响应。

## 最小验证矩阵

| 主张 | 责任 | 最小证据 |
| --- | --- | --- |
| 被动状态零启动；用户授权才探针 | native fixer | fake launcher 调用计数及 UID 映射单元测试 |
| 来源固定且无回退、无分派后重放 | native fixer | 各来源成功/失败/传输异常测试 |
| 策略、ACP 确认和 permission `privileged` 生效 | native fixer | 策略、工具注册、桥接回归测试 |
| 输出读取有界、取消销毁直接进程 | native fixer | fake `Process`、大输出和取消测试 |
| 会话隔离及来源切换顺序正确 | native fixer | 会话并发/切换测试 |
| payload、选择和授权交互一致 | designer | Dart 单元/组件测试与针对性静态分析 |
| Android/Flutter 集成可构建 | 最终集成负责人 | develop standard debug 构建及相关 focused tests |
| KernelSU 虚拟 `su`、授权、真实系统动作可用 | 设备验证负责人（待确认） | 真机只读 UID 探针；用户同意后执行可逆动作 |

## 已知材料风险

- KernelSU 虚拟 `su` 是否能通过固定绝对路径访问、授权 UI 的实际行为、App Profile 对 UID/能力的影响仍未知。
- SELinux、Android 版本和厂商系统可能使 ROOT/ADB 身份下的具体动作失败。
- `destroy` 对派生进程树的覆盖、长会话中授权撤销后的存活行为须真机验证。
- raw shell 改为非 login shell 后，环境变量、profile 和命令解析可能变化。
- 已在一台 KernelSU Next v3.3.0 设备验证应用 Root 授权及单次只读工具调用；不覆盖其他机型/版本、所有系统动作或真实 root 会话生命周期。
- Binder 调用不可由协程立即中断，本地取消也可能等待远端返回；不承诺立即停止本地等待或远端执行。
- 主动授权的 UID 探针上限为 10 秒，管理器响应和设备负载可能影响体验。
- 会话来源不匹配与 shutdown 路由清理已通过 fake Process 测试；完整 capability manager 的来源切换竞态与授权缓存仍缺少直接集成测试，不能由这些单元测试推断全部闭环已覆盖。
- CI 的 NDK 固定配置未同步更新，本次仅验证本机构建；CI 是否通过仍需独立验证，尤其需核对 `OMNI_NDK_VERSION` 覆盖值与已安装 NDK。namespace 修正属于仓库代码，但尚无 CI 执行证据。
- Windows 跨盘 Kotlin 缓存问题仅由本次调用参数绕过；复现时若不使用文中两个 Kotlin 参数，仍可能再次失败。
