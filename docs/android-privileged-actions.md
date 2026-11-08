# Android 高权限操作

## 启用与后端

“设置 → 应用权限授权 → 扩展能力 → Root 高级能力”默认关闭。关闭时应用不会为这项能力运行 `su` 或申请 Root。用户主动开启或重试后执行有超时的 UID 校验；只有 `uid=0` 才视为已授权。已启用且此前成功授权的安装在进程恢复时允许校验一次，拒绝或超时后必须手动重试。该授权配置不参与系统云备份或设备迁移。

工具开始前优先选择已启用且授权有效的直接 Root，否则使用已授权的 Shizuku。Root 开关控制直接 Root 后端，不撤销 Shizuku/Sui 的独立授权。高权限操作按当前会话授权模式（Task Grant、Full Power 或单次确认）执行，卡片提示可随时关闭或调整，而不是每次操作强制弹出审批卡片；确认后再检查授权是否仍有效。命令开始执行后不切换后端，不自动重放失败的操作。

关闭直接 Root 会阻止新操作、使尚未执行的确认失效，并停止其正在运行的进程和持久 shell。Root 管理器中的应用授权仍由该管理器管理。

## 工具接口

沿用 `android_privileged_action` 和 `android_privileged_session_start/exec/read/stop`。结果的 `backend` 继续表示 `ROOT`、`ADB` 或 `NONE` 权限级别，`provider` 表示 `ROOT`、`SHIZUKU` 或 `NONE` 执行来源。

`android_privileged_action` 使用 `action` 和 `arguments`：

| 动作 | 参数 |
| --- | --- |
| `device_control.get_state` | `target` |
| `device_control.set_state` | `target`、`enabled` |
| `device_control.get_display` / `device_control.get_sound` | 无 |
| `device_control.set_brightness` | `percent`，0–100，对应系统归一化亮度接口 |
| `device_control.set_screen_timeout` | `seconds`，15–1800 |
| `device_control.set_volume` | `stream`、`percent`，0–100 |
| `device_control.set_do_not_disturb` | `mode`：`off` / `priority` / `silent` / `alarms` |
| `device_control.set_ringer_mode` | `mode`：`normal` / `silent` / `vibrate` |
| `device_control.capabilities` | 无（只读探测设备状态目标与受控动作的可用性） |
| `settings_control.get` / `settings_control.put` | `namespace`、`key`；写入另需 `value` |
| `shell.exec` | `command`；可选 `timeoutSeconds`、`workingDirectory`、`environment` |

系统开关 `target` 包含 `wifi`、`bluetooth`、`mobile_data`、`airplane_mode`、`location`、`nfc`、`battery_saver`、`auto_rotate`、`auto_brightness`、`dark_mode`、`hotspot`、`night_light`、`flashlight`。移动数据修改仍要求 Root 权限级别。音量 `stream` 支持 `media`、`alarm`、`ring`、`notification`、`voice_call`、`system`。

各新增开关与动作实现合同如下：
- **手电筒 (`flashlight`)**：由宿主应用进程内的 `PrivilegedTorchController` 执行，避免独立辅助进程退出导致系统自动关闭手电筒。依赖宿主声明的 `android.permission.CAMERA` 权限，缺失时仅返回应用设置入口引导授权，不在工具执行流中弹窗；优先选取唯一的背置闪光相机，无背置闪光时使用唯一闪光相机，候选不唯一时明确返回 `unsupported_action`。
- **标准护眼 (`night_light`)**：通过独立辅助进程（`PrivilegedDeviceApiMain`）调用系统 `ColorDisplayManager`（`isNightDisplayAvailable`、`isNightDisplayActivated`、`setNightDisplayActivated`）。仅切换当前激活状态，不改变系统的自动日程调度或色温设置；不支持该标准接口的机型（如定制 HyperOS）明确返回 `unsupported_action`，不猜测厂商私有 settings 键。
- **热点 (`hotspot`)**：严格依赖 Android 16+（API 36+）标准 `TetheringManager`（`PrivilegedTetheringApi36`），构造 `TetheringRequest.Builder(TETHERING_WIFI).build()` 下发，不读取也不修改热点 SSID 与密码配置。无 Tethering 服务或 SDK < 36 明确返回 `unsupported_action`。真机必须在辅助进程退出后持续保持生效；若 ROM 把热点绑定在辅助进程生命周期，则判定不支持持久修改并返回 `state_change_unconfirmed`。
- **响铃模式 (`device_control.set_ringer_mode`)**：通过 `AudioManager.ringerMode` 读写系统整数值；系统因通知策略限制拒绝修改时返回 `permission_denied`，绝不擅自修改通知策略权限或开关勿扰模式进行“兜底”。
- **能力探测 (`device_control.capabilities`)**：只读返回各 `target` 的可读写状态及受控动作可用性，结果复用 `PrivilegedResult`，错误码统一采用 `ok`、`unsupported_action`、`permission_denied` 或 `state_unavailable`。探测为严格的只读查询（零写操作，不修改系统设置）；未授权路径下零命令执行、不触发 `su` 或隐式提权，直接返回当前权限状态；已授权状态下通过现有 `cmd <service> help`、只读状态查询命令与固定辅助进程探测支持性。

保留原有 Wi-Fi/移动数据动作别名以及应用启动/停止、权限授予/撤销、AppOps、设备输入和诊断动作。普通 Android API 能完成的查询和音量操作在应用进程中执行；需要特权的修改使用所选后端中的固定系统命令或白名单辅助进程（`PrivilegedDeviceApiMain`），没有开放任意 Android API 反射执行入口。

状态修改、raw shell 和持久 shell 命令必须受到会话授权约束。模型提供的 `confirmed`、`allow_task`、`isFullPower` 等入参一律无效，仅在宿主完成审批后由执行器注入 `confirmed=true`。查询和停止已有 shell 不再次申请确认；原有高风险 shell 命令拒绝规则继续有效。

## 受管本地 ACP 设备 MCP 端点

为受管本地 ACP Session 提供专用的设备能力访问端点，不向通用外部端点暴露底层系统权限：
- **独立路由与网络边界**：独立提供 `/mcp/acp/{leaseId}` 路径，严格校验底层物理套接字地址（使用底层物理连接的 `local.remoteAddress`，而非易受反向 DNS 影响的 `remoteHost`）为本地回环（`127.0.0.1` 或 `::1`），拒绝非本地请求，不信任 `X-Forwarded-For`、`Host` 自报伪造；服务端请求日志中脱敏租约路径与 Authorization 头，防止租约凭据外泄。
- **租约生命周期 (`AcpSessionMcpLeaseStore`)**：本地 ACP 会话创建、恢复或加载时分配内存租约（`AcpSessionMcpLease`），持有 32 字节密码学安全随机生成的 URL-safe Token，绑定所属 Agent 实例、Conversation ID 与连接 owner。租约仅在 session close/delete、底层连接断开或 MCP 服务停止时显式撤销；普通 `finishTurn` 仅使本次动作执行上下文失效并取消未决任务，不撤销 session 租约，后续 prompt 可继续复用该端点与凭证。
- **工具白名单与参数防护**：暴露专用工具 `android_system_action`，参数为 `{action: string, arguments: object}`，动作严格限制在设备控制白名单（`capabilities`、`get_state`、`set_state`、`get_display`、`set_brightness`、`set_screen_timeout`、`get_sound`、`set_volume`、`set_do_not_disturb`、`set_ringer_mode`）；不提供任意全局 shell 或提权后门。全局 `/mcp` 端点在 `tools/list` 中对外部调用方隐藏该工具，直接调用明确拒绝；工具入参严禁篡改身份字段、`confirmed`、`allow_task`、`isFullPower` 等权限覆盖字段。
- **会话归属与审批取消界限**：工具执行必须依托当前活跃的宿主 Turn（无 active turn 返回 `turn_inactive`，无持久 Conversation 返回 `conversation_required`）；工具审批通过宿主标准 `requestSessionPermission` 弹出官方卡片（支持 ALLOW_ONCE、ALLOW_ALWAYS 等），动作执行绑定当前 Turn 取消信号（`turnJob`）；不同会话严禁跨会话互相审批或接管。
- **安全边界声明**：Bearer Token 仅用于宿主正常会话归属和本地端口防误触，不能声称防范共享 rootfs 或同 UID 恶意本地进程的强隔离。

## 关联系统能力（时钟、日历、助理、虚拟屏与任务通知）

- **标准时钟 (`system_clock_action`)**：支持 `create_timer`（时长 1–86400 秒）、`show_alarms`、`show_timers`，通过标准 Android `AlarmClock` Intent 移交给系统时钟应用；仅报告意图已分发（`dispatched: true`, `verified: false`），不伪造静默 CRUD 读回；系统无匹配 Activity 时返回 `unsupported_action`。
- **日历批量事务 (`calendar_event_batch`)**：支持单次 1..50 项操作（`create`、`update`、`delete`），原始请求体限制不超过 256KiB。更新操作使用 Events 集合 URI（`CalendarContract.Events.CONTENT_URI`）配合 `_ID` 条件与 `withExpectedCount(1)` 断言以避免项级 URI 拒绝，由 `ContentResolver.applyBatch` 在同一事务中原子提交，任何一条操作冲突或失败整批回滚；仅在提交成功且读回核验后标记 `verified: true`。
- **系统数字助理 (`OmniVoiceInteractionService`)**：基于标准 Android VoiceInteraction 体系，用户可在系统默认助手列表中手动选用，并通过标准按键或手势唤醒。Android 17+ 声明 `android.permission.READ_ASSIST_STRUCTURE_SCREEN_CONTENT`（normal 权限）及 Assist 元数据（`usesAssistData`、`usesAssistScreenshots`、`usesAssistStructureScreenContent`）；采集仍受系统屏幕共享开关与安全窗口（`FLAG_SECURE`）限制。单次唤醒只在内存中暂存截图和可见文本，确认面板的“附带当前屏幕”默认关闭；只有用户开启并继续，内容才保存为工作区草稿附件并移交既有聊天界面，不自动触发模型 prompt。没有屏幕内容时仍可普通提问。系统权限页提供真实助理状态与设置入口；当前不申请麦克风权限，不承诺语音识别就绪。
- **虚拟屏与任务接管**：实际屏幕操作由现有 `TaskRuntime` 租约标记所属 Conversation / ACP Session / Turn，顶部悬浮胶囊展示当前步骤，后台虚拟屏带实时缩略镜；点击原位展开装裱式预览，可收起、停止、打开独立预览或移交主屏。普通聊天与显示屏列表查询不展示接管 UI。已授系统悬浮窗权限时使用与浏览器浮窗一致的 Application Overlay；没有该授权时仅使用可用的无障碍浮层，窗口不可用则保留通知入口，不在工具执行中弹授权页。
  预览复用 `VirtualDisplayFrameView` 与现有帧源，按原比例显示、最高 10fps，不另做截图编码。收起 / 展开只切换已附着画布的可见性；锁屏、真正离开窗口或停用预览时清黑停帧，恢复后等待新帧，避免显示残影。独立 `VirtualDisplayPreviewActivity` 保留单独 task affinity 与系统画中画（PiP），重复入口通过 `onNewIntent` 更新并重新核验身份。
  取消当前 ACP Turn 只中断推理、释放输入租约，不销毁虚拟屏。用户“移交主屏”由共享 `VirtualDisplayTakeoverController` 先官方取消初始绑定的 Turn，确认停止且无新 Turn，再核验唯一根任务并获取双屏租约调用 `present_to_user`；超时、目标歧义或新 Turn 准入均保留虚拟屏并报告原因。迁移不承诺第三方应用免于界面重建。移交取消只抑制该任务的结果浮层，不改写官方终态。
- **主屏接管与结果反馈**：只有已标记主屏操作且未处于人工 / 解锁等待的任务显示触摸穿透的细边缘流光，不压暗页面；后台虚拟屏不占用主屏光晕。官方完成、取消或失败后关闭运行态浮层并显示底部结果卡，状态来自官方响应或 owning request 的错误，不把传输清理或工具成功伪装成 Turn 成功。正文按精确 Session / Turn 读取已有助手历史并在提交后原位更新，不另建 ACP 文本 reducer。长文可滚动；“留在当前”仅关闭卡片，“返回小万”回到匹配对话。用户关闭后迟到正文不得重新弹出。
  浮层沿用应用的中性黑白色板，主操作使用黑白高反差，品牌蓝 / 鼠尾草绿只作状态与边光点缀；控制面板与结果卡采用 24dp 圆角、14dp 按钮圆角与 48dp 触控区域。结果复制入口位于标题右侧，底部保留两个等宽去向按钮。主题与液态玻璃开关复用现有应用偏好并原位刷新；“跟随系统”优先使用主页面的实际配置，避免 Activity 与 Application 的夜间模式覆盖差异。原生轻质表面不包含跨应用背景模糊或 Flutter 玻璃折射，不另建 Flutter 引擎。
- **前台任务通知与 Live Update**：在 Android 16+（API 36+）上声明 `android.permission.POST_PROMOTED_NOTIFICATIONS` 权限，对主活动任务调用 `Notification.Builder.setRequestPromotedOngoing(true)` 请求系统提升为持续任务展示；具体是否在状态栏呈现为胶囊或灵动展示完全由 ROM 与系统策略决定，不声称目标 ROM 胶囊已实测支持。旧版系统或系统不支持时平滑降级为普通前台服务通知。当任务进入等待人工验证或需要用户授权确认时，通知保留专属高优先级渠道（attention channel）与交互操作，不为胶囊伪装或抑制等待状态。

助理与任务通知回到聊天时，仅向 `MainActivity` 发送携带目标路由的 Intent，由既有 `onCreate/onNewIntent -> SchemeUtil.pushRoute` 消费；后台服务不预建 Flutter 主引擎或重复发送路由，避免未绑定 Activity 的平台通道阻塞首帧。本地设备 MCP 也先确保服务运行，再准备新会话租约，避免服务重启撤销尚未绑定的新租约。

## 状态与生命周期

结构化系统开关、显示、声音与设置修改结果在 `observation` 中报告 `before`、`after`、`expected`、`commandAccepted`、`verified`、`changed`、`userId` 和 `scope`。所有写操作遵循单次提交模型；系统命令成功返回只表示请求被受理，读回未达到目标时返回 `state_change_unconfirmed`，应先查询状态，不直接重试，不跨后端重放。异步开关只在有界窗口内轮询状态，不重复发送修改。

`system`、`secure` 设置使用应用所在 Android 用户；`global` 设置报告设备级范围。硬件不存在、命令不受支持、读取权限不足或 ROM 输出无法可靠识别时返回明确失败，不猜测设备状态。亮度要求可读的归一化系统接口；不假定所有 ROM 使用相同的整数亮度范围。

系统命令能力探测只读取 `cmd <service> help` 的命令声明，同时检查 stdout 和 stderr。[AOSP 的默认帮助处理](https://android.googlesource.com/platform/frameworks/libs/modules-utils/+/refs/heads/main/java/com/android/modules/utils/BasicShellCommandHandler.java#304) 打印正常帮助后仍返回 `-1`，进程退出码表现为 `255`；这一约定仅用于只读帮助探测，实际状态修改仍须成功返回并读回验证。授权失败、超时和服务错误保留原始错误；输出被截断且未找到目标声明时报告探测不完整，不据此认定设备不支持。

持久 shell 同时绑定 Conversation 和 ACP Session，并固定使用创建时的执行后端。不同对话、不同 ACP Session 或新授权代次不能接管旧 shell。取消执行会结束相应命令；取消持久 shell 命令会关闭该 shell。官方 ACP session close/delete 的资源清理路径释放对应 shell，工具进度与结果仍由现有 ACP reducer 投影。

直接 Root 子进程通过 UID 握手确认身份。输出在读取过程中限量缓存并持续排空；持久会话保留有截断标记的尾部记录。Root 子进程组由监督进程观察本地租约和宿主进程存活状态，撤销、超时或取消时清理该进程组。

## 边界与验证状态

- **数据持久化边界**：执行过程中的日志、工作区文件（如助理屏幕草稿）或对话上下文可能按既有会话机制写入持久化存储，不得声称“敏感结果一律不落盘”。
- **厂商与语音限制**：本实现仅基于通用 Android 公开/标准接口，未实现 HyperOS 小爱接管、小米等厂商私有时钟/便签等私有闭环适配；系统语音识别转发代理（`OmniRecognitionService`）仅转发系统配置的非自身外部识别服务，应用当前未声明 `RECORD_AUDIO` 权限，不声称语音识别功能已就绪。
- **验证与支持性限制**：当前 SDK 37 设备已通过真实应用验收：手电筒、标准护眼、热点往返恢复（热点在辅助进程退出后仍生效）；测试日历批量创建、事件与提醒修改、非法混合批次零写及删除；标准时钟显示测试计时器且仅报告 `dispatched`；本地 Claude 声音读取、音量往返、拒绝和双会话审批隔离；系统助理确认屏幕附件后单次回答标记 42，安全窗口未泄露标记；虚拟屏计算结果与未提交文本在同任务移交后保留，系统 PiP 与聊天并存、解锁后显示新帧、Root 撤销后旧屏输入被拒。通知实际带 Promoted Ongoing 标记与 ProgressStyle，但不据此声称 ROM 展示胶囊。临时权限、默认助理、屏幕共享及模型选择均恢复原值；这些结果不代表其他 ROM、未测本地 Harness 或语音识别已获验证。
- **悬浮交互验收（2026-11-08）**：在 SDK 37 真机、原有系统悬浮窗授权及真实小万聊天流程中，已验证自动顶部胶囊、实时微缩镜、展开预览与静态画面收起重开；等待执行期间通过官方取消将同一计算器根任务从虚拟屏移至主屏；主屏计算 `6×7=42` 时边光可见且实际点击可通过，完成后光晕退出；结果卡显示精确本轮正文，“留在当前”保留第三方计算器，“返回小万”回到对应对话。真实 `vlm_task` 只显示统一胶囊 / 边光、没有旧执行面板，观察既有结果 `42` 后正常返回本轮结果。测试虚拟屏均已清理，未更改模型、权限或系统设置；未把已有无效包名工具错误包装成 Turn 失败，结果正文保留真实错误。未在本轮重测无系统悬浮授权的无障碍窗口、所有 ROM、安全 PIN 锁屏、横屏大字号、亮色主题及所有 Harness，不扩大上述验收结论。
  最终 Debug APK 构建通过，相关 6 个测试类共 43 项回归全部通过（预览 / 接管 / 通知 22 项、屏幕结果 8 项、停止凭据 5 项、显示目标 2 项、工具移交 4 项、VLM 上下文 2 项）。最终安装包还通过真实顶部展开 → 独立预览显示虚拟屏 → 系统 PiP 与原聊天并存；修复了共享 View 的 `apply` 接收者遮蔽导致独立预览误绑定主屏的问题。项目未配置 Kotlin LSP，未执行全仓测试或全量 lint。
- **浮层视觉统一验收（2026-11-08）**：新 Debug 包在同一 SDK 37 真机的真实对话中通过浅 / 深色胶囊与实时画布、静态画面收起重开、任务运行中从应用外观页切换主题；同一计算器根任务由虚拟屏 14 移交主屏。主屏实际计算 `6×7=42`，品牌色边光下点击历史按钮可通过且临时状态恢复；正式完成后边光退出。深色结果正文可滚动，标题复制按钮通过真实输入框的系统“粘贴”菜单验证包含本轮标记与第 8 项的完整 318 字符正文，验证草稿已清空；“留在当前”保留计算器 `42`，浅色结果“返回小万”回到精确本轮对话。原主题 `system`、玻璃开启及标准画质保留，仅在创建该屏的会话中销毁测试屏 14，用户既有显示屏 2 未动。未在本轮重测全部 ROM、横屏大字号、安全 PIN 锁屏、无系统悬浮授权或所有 Harness。
  补充真机复现的系统主题配置分歧：在 `MainActivity.onResume` 断点读到 Activity `uiMode=17`、Application `uiMode=33` 且偏好为 `SYSTEM`；浮层现复用已有主页面弱引用的只读配置，不另建 Activity 跟踪或修改全局夜间模式。修复后的最终安装包在真实 `session/prompt` 等待流程中，主页面与结果卡主题一致、官方终态后正常退出。最终 APK 构建及 6 类共 44 项回归通过；新增 1 项覆盖 Activity / 窗口配置相反时的系统主题优先级、显式选择优先及 Activity 解除绑定后的回退。新增测试首次因配置覆盖调用晚于 Resources 初始化而失败，改用实际配置变更生命周期后通过；无遗留失败。未配置 Kotlin LSP，未跑全仓测试或全量 lint。
- **测试覆盖**：定向单元测试覆盖零探测、授权模式流转、拒绝/超时手动重试、取消与限量、会话隔离与状态读回。可构建 `:baselib:assembleDebugAndroidTest` 并在支持 UiAutomation 的设备上运行系统命令兼容性测试。自动化测试不能替代真实 Root 管理器授权、SELinux 和厂商系统行为的验证。
