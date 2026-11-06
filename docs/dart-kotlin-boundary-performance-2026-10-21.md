# Dart / Kotlin 边界重构与实机结果

本轮将会话/Token 批量统计、Office 文件解析和实际任务调度的职责集中到 Kotlin owner，Flutter 保留现有组件、文案、动画、交互和显示 DTO。清理了 52 个不可达的历史 Dart 文件，生产 Dart 净减少 8,909 行；有效测试随职责迁移，未以删测试代替功能验证。

实机结果最明确的收益来自统计：两个服务顺序调用从 3,516 ms 降到 298 ms，两个统计响应载荷的编码体积减少 99.98%。Office 的 UI isolate 阻塞显著减少，但总加载耗时有取舍：Excel 更快，Word 和 PowerPoint 更慢。不能将这些结果合成一个全软件加速比例。

## 实测结果

以下均为中位数。每组先丢弃 2 次预热，统计保留 12 个样本，每类 Office 保留 8 个样本。

| 操作 | 重构前总耗时 | 重构后总耗时 | 总耗时变化 | 前 / 后最大 Timer 间隔的中位数 |
| --- | ---: | ---: | ---: | ---: |
| 会话活动 + Token 两个统计服务 | 3,516.078 ms | 298.489 ms | 减少 91.51%，约 11.8 倍 | 740.895 → 6.571 ms |
| Word：3,000 段正文 | 277.531 ms | 305.854 ms | 增加 10.21% | 274.595 → 34.312 ms |
| Excel：5 表 × 500 行 × 12 列 | 713.281 ms | 589.218 ms | 减少 17.39% | 710.331 → 45.750 ms |
| PowerPoint：40 页 × 30 段 | 129.287 ms | 176.024 ms | 增加 36.15% | 126.661 → 25.402 ms |

`maxTimerGapMs` 是 Dart UI isolate 上 1 ms 周期 Timer 在一次服务调用期间的最大回调间隔。上表最后一列再取这些最大间隔的中位数，反映事件循环被阻塞的程度，**不是帧耗时、丢帧率或 FPS**。Word、Excel、PowerPoint 的该指标分别减少 87.50%、93.56%、79.94%。

Office 总耗时包含完整文件解析、原生编码/跨桥和 Dart 显示 DTO 映射。移到后台并不自动降低端到端耗时；本轮不将 Word/PowerPoint 宣称为总加载加速，也未通过减少段落、行、列、工作表或幻灯片来换速度。报告生成时的 canonical JSON 编码和哈希不计入服务计时。

| 其他指标 | 重构前 | 重构后 | 解释 |
| --- | ---: | ---: | --- |
| 两个统计响应的 StandardMessageCodec 编码体积 | 28,227,790 B | 6,324 B | 减少 99.9776%，新响应包含账户切换所需的 localSummary |
| Activity `am start -W` TotalTime，7 次 | 929 ms | 934 ms | 基本持平，无可靠启动提速结论 |
| 启动后 6 秒总 PSS，7 次 | 369.049 MiB | 248.501 MiB | 此观察点减少 32.66%；不是峰值或长期驻留内存 |

统计计时是“先会话、后 Token”两个服务顺序执行，实际 Dashboard 并行加载，**298 ms 不是页面打开耗时**。PSS/启动测量使用实际统计页面；force-stop 只清进程，不清磁盘或 SQLite 文件缓存。

## 同机交叉复核

保存了真正的重构前 APK，优化期间使用独立包覆盖安装；没有将工作区 reset 到旧提交。正式结果只引用最终冻结版本，中间 DOM/SAX 实现的性能诊断不混入最终样本。

在最终安装前，再次安装保存的旧 APK，每项保留 6 次，所得中位数：统计 3,523.564 ms、Word 279.049 ms、Excel 721.036 ms、PowerPoint 124.278 ms，完整结果 SHA 仍一致。旧版另补 3 次冷启动，TotalTime 中位数 936 ms，PSS 389.660 MiB。其电池温度 31.6°C，最终新版七次为 31.8°C；首次旧版七次为 32.9–33.0°C，所有记录的整体 Thermal Status 均为 0。这支持统计/定点 PSS 的差异不是只出现在第一次旧版运行，仍不能排除全部系统调度噪声。

设备为 USB ADB 连接的 PJD110 / SM8650 / Android 16，显示覆盖分辨率 1080×2376、480 dpi，峰值刷新率设置为 120 Hz；没有据此假定每一帧都运行在 120 Hz。

## 构建与数据边界

- 基线提交：`c6aa104947fc2cd75d4fedcc31e162c8256ce8de`。
- Flutter 3.47.2，ARM64 Profile AOT；APK 有 `libapp.so`，不是 JIT debug Flutter。
- Android 原生部分继承 debug 配置、未 R8 压缩，构建 JDK 17；完整 JVM/Robolectric 测试使用 JDK 21，以兼容仓库现有 ReTerminal JVM 21 类。
- 两份诊断 APK 都含同一份旧 Dart 参考实现，因此 APK 体积、启动和 PSS 不能直接当作正式 release 包的结果。
- 固定独立 application ID：`cn.com.omnimind.bot.boundaryperf`。正式安装包 `cn.com.omnimind.bot` 未被本轮覆盖、清空或导出。
- 一份固定数据集：10,000 个合成会话、100,000 条合成 Token、112 天范围、5 个原始模型名合并为 4 个模型 ID；3 份生成 OOXML 容器只包含合成文本，用于现有解析能力压力测试。
- 基准全在未登录状态完成；测试账号登录冒烟另行执行，不混入以上样本。不把账号、密码、VM 认证地址、完整数据库或设备标识加入仓库。

最终 APK SHA-256：`321c4a84e639008e066b06e7919ec2f0d804ae1801a12e606e48f73886a79536`，已与设备上 APK 校验一致。旧 APK SHA-256：`15f7cf37a303efdc537ce85b1ecd74872c81cf8910270cf42447fdbd67401622`。

## UI、数据和回归证据

| 检查 | 结果 | 能证明的范围 |
| --- | --- | --- |
| 统计页前后实机截图 | 内容区域及 Dashboard 区域不同像素均为 0 | 同设备、同数据、当前主题下该页面的渲染 |
| 统计完整输出 canonical SHA-256 | 前后一致 | 每日活动、连续天数、每周模型 Token、缓存合计和排序 |
| Word/Excel/PowerPoint 完整输出 SHA-256 | 三类分别前后一致 | 上述三份合成容器的所有输出段落、表格、标题和文案一致；未截断。空内容语义另由单测覆盖 |
| 合成数据库 `integrity_check` | `ok`；两表行数及逐行内容哈希前后一致 | 基准操作未修改这 110,000 条合成记录 |
| Flutter 全量测试 | 1,424 项通过 | 当前测试覆盖的 UI/交互、通道、ACP 投影及既有回归 |
| app 变体 Kotlin/JVM/Robolectric 全量单测 | 185 个类，1,290 项通过；0 失败/错误/跳过 | `testDevelopStandardDebugUnitTest`，包括 Room 查询、可见性、Office 18 项和解析排队/取消 2 项 |
| Dart 空白兼容差分 | 1,948 输入，3,896 个 normalize/trim 对照，0 差异 | 最终 Kotlin 类对比 Flutter 3.47.2 所带 Dart 的空白语义 |
| Flutter analyze | 0 error；仍有 523 项 warning/info | 未将全仓存量 lint 清理混入本轮 |
| 正式入口完整 Android 构建 | `assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart` 成功 | 不是仅探针入口可以编译 |
| 入口可达审计 / 差分格式 | 360/360；`git diff --check` 通过 | 无剩余断开文件；不等同动态路径的全部功能证明 |

统计卡片从淡入逻辑到完整渲染部分，除显示 DTO 类型名替换外保持一致；Office 现有组件未改布局。损坏 ZIP/XML 的底层解析器详细错误文本可能不同，已有中英文错误类别和成功路径内容受测试保护。测试通过和单页截图不等于全部机型、主题、联网服务及权限场景的穷尽实机验收。

基准结束后，另使用用户提供的测试账号通过现有登录表单登录；平台路由报告可用。全新测试包首次聊天因未选择调度模型而被现有 ACP 初始化拒绝，随后在现有场景模型页选择官方 `qwen3.8-flash`，手动发送新的最小测试回合，收到 `OK` 和完成状态。从会话列表重新打开后，用户消息及助手回复仍在。最后通过现有账号服务退出，状态复核为 `signedIn=false`。这验证了登录、模型选择、ACP 文本回合、历史重开和退出登录；未将该最小回合扩大为全部工具/权限/后台任务的联网实机验收。

测试完成后，已卸载本次创建的独立包、移除本次 VM 转发与私有连接文件，正式包仍安装。当前测试进程的 crash buffer 未发现 fatal 记录。原正式数据库的导出曾被自动审批拒绝，原因是含私人历史而未获明确授权；本轮使用全新隔离库和合成数据完成了对照，未导出正式库。

## 架构与清理交付

统计查询由 `baselib` 轻量投影/SQL 分组完成，`app` 数据服务复用隐藏会话和 Na 账户归属策略，不再跨桥返回全库内容。原生读取后及 Flutter 排队回调均有账户切换隔离。没有变更数据库 schema、ACP 身份或生命周期。

Office 按需打开 ZIP 条目，以 SAX 提取当前段落/行/共享字符串结构，后台执行并保留单通道一次解析的并发界限；请求内惰性复用 XMLReader。单条目 XML 仍完整解码，全部显示 DTO 仍跨桥返回 Dart，不声称常量内存或零跨桥成本。

定时任务删除永久启用原生调度后不可达的 Dart Timer、重复规则和第二执行路径；继续调用已有 Kotlin 调度的 sync/upsert/delete。ACP 保持现有 `Conversation -> Session -> Turn -> Item`，没有新增 reducer、私有事件协议或重试 owner。

删除 52 个不可达生产 Dart 文件和 7 个专属死测试，混合测试仅删失效部分；移除 11 个无生产引用的直接依赖，保留包未升级。生产 Dart 总净减少 266,785 B / 8,909 行，Kotlin 生产增加 31,609 B / 698 行。当前本地 `.kt` 总字节已高于 `.dart`；即使排除 ReTerminal，生产 Kotlin 字节也略高于 Dart。GitHub 用 Linguist 处理默认分支的代码字节，不能将本地数字直接等同当前语言标签；本轮没有修改 `.gitattributes` 来改变分类。

Flutter 仍有模型目录的 HTTP/ETag/TTL 缓存（解析已在 `compute`）、界面偏好和显示投影等服务。本轮交付的是上述重数据路径与现有原生 owner 的解耦，未宣称全项目 Dart 已只剩布局。

完整文件清单、依赖和语言口径见 [清理记录](flutter-boundary-cleanup-2026-10-21.md)；职责与维护约束见 [架构说明](dart-kotlin-boundary-architecture.md)。

## 复现与原始证据

[可复现脚本及步骤](../scripts/performance/dart-kotlin-boundary/README.md)包含固定独立包保护、合成数据生成、原实现导出、VM 驱动、真实 StandardMessageCodec 字节、冷启动/PSS 采样和连接清理。正式入口及 Gradle 配置文件未为探针而修改。

[机器可读结果](performance/dart-kotlin-boundary-2026-10-21.json)保留最终每轮耗时、完整输出哈希、交叉旧版样本、冷启动温度/PSS、APK 身份和测试计数。本机被 Git 忽略的 `build/performance/2026-10-21-boundary/` 保留原始日志、两份 APK、截图、合成数据与最终源码 manifest；其中不应发布私有 VM 连接材料。

性能模式选择参照 [Flutter 官方性能说明](https://docs.flutter.dev/perf/ui-performance)；语言分类参照 [GitHub 仓库语言说明](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-repository-languages)。
