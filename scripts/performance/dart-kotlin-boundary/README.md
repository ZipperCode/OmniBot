# Dart / Kotlin 边界性能复现

这些脚本复用 2026-10-21 的诊断流程。它们仅使用固定独立包
`cn.com.omnimind.bot.boundaryperf`，不接受其他包名，不读取正式包数据，不调用登录、退出登录或账户查询。
入口在写入测试偏好前检查自身 Android 进程名；安装脚本先校验 APK application ID、native debuggable 和 AOT 库。

## 构建与数据隔离

在项目根目录执行，使用项目配置的 Flutter/JDK/Android SDK；先完成 `cd ui && flutter pub get`。
Python 需要 3.9+（`zoneinfo`）。设置 `FLUTTER_ROOT` 指向本机 Flutter SDK，以下变量不含任何真实设备或账号信息。

```sh
BENCH=scripts/performance/dart-kotlin-boundary
OUT=build/performance/boundary-replay
DART="$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"
python3 "$BENCH/prepare.py" --output "$OUT"

./gradlew --no-daemon -I "$PWD/$OUT/profile.init.gradle" \
  :app:assembleDevelopStandardProfile -Ptarget="$PWD/$OUT/profile_main.dart"

python3 "$BENCH/install.py" --device "$DEVICE" --output "$OUT" \
  --apk app/build/outputs/apk/developStandard/profile/app-develop-standard-profile.apk \
  --apkanalyzer "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer"
adb -s "$DEVICE" shell am start -W \
  -n cn.com.omnimind.bot.boundaryperf/cn.com.omnimind.bot.activity.MainActivity
python3 "$BENCH/seed.py" --device "$DEVICE" --output "$OUT"
```

`DEVICE` 必须由调用者显式设置为已授权设备的 ADB 标识；没有默认设备。
`--output` 必须是当前 checkout 内被 Git 忽略的新空目录。准备脚本只在该目录写入模板和参考实现，
不会修改生产入口、切换 Git 分支、构建 APK 或操作设备。
参考 Office/Token Dart 文件通过 `git show c6aa104947fc2cd75d4fedcc31e162c8256ce8de:...` 导出，
提交号与内容 SHA-256 记入 `boundary-perf.json`；完整旧实现不随这些脚本加入版本控制。

这里的 Flutter 是 **Profile AOT**，Android 层继承 **native debug** 配置并允许 `run-as`，未启用 R8 压缩。
init script 禁用开发 Provider 自动初始化并清空相关构建凭据字段。
安装脚本拒绝正式包、JIT debug APK 和不可调试 APK；不清空任何已安装应用数据。
全新安装后先启动应用，以创建空的独立 Room 库。探针跳过测试包自己的欢迎页，在正常应用初始化后打开轨迹页；其他正常初始化工作仍会运行。

种子脚本要求独立包的 `conversations` 与 `token_usage_records` 两表都为空；发现现有记录即退出，不替换记录。
它生成默认 10,000 个合成会话、100,000 条合成 Token 数据、三个仅含生成文本的 OOXML 解析容器。
容器用于现有解析器压力测试，不代表包含完整 Office 应用元数据的生产文档。
它使用实际插入的会话 ID、设备时区与设备当天零点；日期、数量和文档哈希写入 `dataset.json`。
不需要后端账号、Provider API key、真实对话或真实文档。

## 同一数据集上比较旧路径与新路径

```sh
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT"

"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --output "$OUT" --params '{"op":"stats","native":"false","n":"12"}' > "$OUT/stats-legacy.json"
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --output "$OUT" --params '{"op":"stats","native":"true","n":"12"}' > "$OUT/stats-native.json"

"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --output "$OUT" --params '{"op":"office","kind":"office_word","native":"false","n":"8"}' > "$OUT/word-legacy.json"
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --output "$OUT" --params '{"op":"office","kind":"office_word","native":"true","n":"8"}' > "$OUT/word-native.json"

"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --output "$OUT" --params '{"op":"transfer"}' > "$OUT/channel-payloads.json"
```

Office 类型还支持 `office_sheet`、`office_slide`。入口只允许读取固定测试包里的三个生成文档，
不接受任意文档路径。每轮丢弃两次预热，保留指定次数；异常不会记录为零耗时样本，Timer 在 `finally` 中释放。
driver 默认 180 秒超时，可用 `--timeout-seconds` 调整；异常返回非零退出码。
有 `dataset.json` 时，driver 自动把固定 `todayMs` 传入每次调用，避免跨午夜或前后日期窗口漂移。
空数据验证可显式传入同一个 `todayMs` 字符串。比较前后 `sha256`/完整 `summary`，同时确认 `locale` 相同。

`stats` **先调用会话服务，再调用 Token 服务**，包括旧路径与新路径。
这测量的是两个服务顺序执行的端到端耗时；实际 Dashboard 的两项加载并行，不能把该数字称为页面打开耗时。
新响应中的 `localSummary` 用于账户切换保护，只在语义结果哈希比较前移除。
`transfer` 仅能在含新通道的 APK 上调用，同时执行两旧查询与两新查询，比较
`StandardMessageCodec.encodeMessage` 得到的响应字节数；新响应字节数保留 `localSummary`。
这是编码后载荷体积，不是网络流量或编码 CPU 时间；`jsonBytes` 是另一种 JSON 表示体积。

`maxTimerGapMs` 来自 Dart UI isolate 上周期为 1 ms 的 Timer，表示该次调用期间最大的事件循环回调间隔。
它受调度、后台工作和设备负载影响，**不是帧耗时、丢帧数或 FPS**。

## Activity 启动、PSS 与 Flutter 帧回调

```sh
python3 "$BENCH/cold.py" --device "$DEVICE" --output "$OUT" --label after --samples 7
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT"
"$DART" --packages=ui/.dart_tool/package_config.json "$BENCH/driver.dart" \
  --output "$OUT" --capture-frames --params '{"op":"route"}' > "$OUT/route-frames.json"
python3 "$BENCH/connect.py" --device "$DEVICE" --output "$OUT" --disconnect
```

`cold.py` 每次只 force-stop 独立包，采集 `am start -W` 的 `TotalTime`，等待固定时长后读取该包 PSS，
并保存设备电池温度与热状态。`--settle-seconds` 默认 6。
这些值分别是 Activity 启动指标和固定观察点的总 PSS，不是首屏可交互时间、峰值内存或内存泄漏证明。
cold 会重启进程；重启后需重新 `connect.py`，旧 VM 连接不可复用。

`--capture-frames` 包围指定操作并保留 1.5 秒观察窗口，最终总会调用 `stopFrames`。
输出来自 Flutter `FrameTiming`（build/raster/total 微秒值），与 Timer gap 分开记录。
`route` 仅打开轨迹页，`pop` 仅退出当前页；没有账户操作。
采集单次页面帧仍需控制设备刷新率、热状态、动画和其他前台活动，不能从少量帧推导稳定 FPS。

需要比较真正的改动前后 APK 时，在独立、干净的 checkout/worktree 分别构建相同模板；
不要重置有未提交修改的工作区。先保存旧 APK，再构建新 APK，依次通过安装脚本 `-r` 更新同一个独立包，
保留相同合成数据和固定日期窗口；每次记录安装脚本输出的 APK SHA-256、源码提交、Flutter/JDK 版本和设备条件。
两份诊断 APK 都嵌入旧参考实现，因此 APK 大小、冷启动和 PSS 不能直接等同正式发布包的差异。

VM 发现只读取当前独立进程 PID 的日志，并校验自定义 extension。
读取当前保留的全部 PID 日志；若 VM 启动公告已被日志缓冲淘汰，会提示手动重启独立测试包后重连，
不会自动重启或改变热基线。
连接 JSON 在被忽略目录内以权限 `0600` 创建，包含本次设备选择和 VM 认证地址；终端不打印认证地址。
不要将该文件、APK、数据库、旧完整 Dart 文件、设备标识或任何真实账号材料加入版本控制。
`--disconnect` 仅移除该工作区保存且匹配当前显式设备的转发和私有连接文件。
