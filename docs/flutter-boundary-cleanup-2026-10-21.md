# Flutter 边界清理与仓库语言统计（2026-10-21）

本轮以 `c6aa104947fc2cd75d4fedcc31e162c8256ce8de` 为清理前版本，在原应用可达界面之外删除历史孤岛。实际布局、气泡、消息卡片、输入框、导航与 ACP reducer 的职责保持在原有 owner。本记录只证明下面列出的清理；性能数据由同机 Profile A/B 报告提供，不能把删行数当作加速比例。

## 删除依据

`python3 scripts/audit-flutter-reachability.py --json` 从 `main.dart`、`main_standard.dart` 和所有标记 `vm:entry-point` 的文件开始，递归 import/export/part 的所有 URI，包括条件和 deferred 导入。审计保守地允许注释或字符串产生额外边，避免把一个可能的引用忽略；这不是 Dart 符号分析器，不能独立作为自动删除授权。

本轮另核对：

- Android `App.kt` 只用 `FlutterEngineGroup.createAndRunDefaultEngine`；所有 Dart 生产入口均已覆盖，没有附加 VM 入口。
- 路由表、原生入口、跨文件字符串、测试源码/fixture 和仓库脚本中没有从现行入口加载这些孤岛的路径。
- `/Users/ocean/code/codex` 中对旧 `na_cloud_service.dart` 的引用只出现在历史集成文档；现有 Na 走 Kotlin ACP/runtime。没有改动外部仓库或历史文档。
- 页面实际使用的 Mem0 编辑器、StreamingText、权限服务、Native Quick Log、小组件和 Na ACP 路径继续保留。
- 未修改 `.gitattributes` 来影响仓库语言分类。

| 可达图指标 | 清理前 | 本轮清理后 |
| --- | ---: | ---: |
| `ui/lib` Dart 文件 | 412 | 360 |
| 从正式入口可达的文件 | 360 | 360 |
| 断开的历史文件 | 52 | 0 |
| 删除的历史生产源码 | 8,262 行 / 246,995 字节 | — |

52 个文件逐项清单（路径相对 `ui/lib/`，字节为 UTF-8 文件字节）：

| 文件 | 行 | 字节 |
| --- | ---: | ---: |
| `constants/chatbot/intent_tag_app_map.dart` | 9 | 405 |
| `features/home/pages/chat/mixins/chat_mixins.dart` | 5 | 114 |
| `features/home/pages/chat/utils/stream_text_merge.dart` | 80 | 2,396 |
| `features/home/pages/chat/widgets/tip_messages.dart` | 78 | 2,548 |
| `features/home/pages/command_overlay/constants/messages.dart` | 7 | 305 |
| `features/home/pages/command_overlay/utils/deep_thinking_parser.dart` | 350 | 11,025 |
| `features/home/pages/command_overlay/utils/error_message_formatter.dart` | 106 | 3,116 |
| `features/home/pages/command_overlay/widgets/thinking_animation.dart` | 107 | 2,791 |
| `features/home/pages/logs/quick_logs_page.dart` | 604 | 20,249 |
| `features/home/pages/terms/terms_page.dart` | 31 | 817 |
| `features/load/app_launcher.dart` | 44 | 1,068 |
| `features/load/loading_page.dart` | 258 | 8,107 |
| `features/memory/pages/memory_center/widgets/conversation_heatmap.dart` | 545 | 16,774 |
| `features/memory/pages/memory_detail/memory_detail_page.dart` | 256 | 8,302 |
| `features/memory/pages/memory_detail/widgets/detail_content.dart` | 135 | 4,738 |
| `features/welcome/pages/onboarding/widgets/user_guide_link_row.dart` | 94 | 2,763 |
| `features/welcome/pages/welcome_page/welcome_page.dart` | 220 | 7,496 |
| `features/welcome/pages/welcome_page/widgets/app_selection_bottom_sheet.dart` | 268 | 8,054 |
| `features/welcome/pages/welcome_page/widgets/first_welcome_page.dart` | 60 | 1,597 |
| `features/welcome/pages/welcome_page/widgets/fourth_welcome_page.dart` | 308 | 9,567 |
| `features/welcome/pages/welcome_page/widgets/permission_guide_button.dart` | 54 | 1,432 |
| `features/welcome/pages/welcome_page/widgets/second_welcome_page.dart` | 70 | 1,708 |
| `features/welcome/pages/welcome_page/widgets/termux_guide_bottom_sheet.dart` | 418 | 11,859 |
| `features/welcome/pages/welcome_page/widgets/third_welcome_page.dart` | 86 | 1,993 |
| `features/welcome/state/onboarding_state.dart` | 56 | 2,017 |
| `models/conversation.dart` | 39 | 969 |
| `models/llm_models.dart` | 119 | 3,150 |
| `services/http_handler.dart` | 4 | 138 |
| `services/na_cloud_service.dart` | 449 | 13,927 |
| `services/quick_log_service.dart` | 116 | 3,154 |
| `services/tips_service.dart` | 37 | 1,207 |
| `utils/data_parser.dart` | 578 | 15,001 |
| `utils/http.dart` | 166 | 3,589 |
| `utils/json_util.dart` | 39 | 1,294 |
| `widgets/app_update_banner.dart` | 105 | 3,178 |
| `widgets/auto_invert_icon.dart` | 167 | 4,692 |
| `widgets/blurred_menu_item.dart` | 10 | 276 |
| `widgets/bot_status.dart` | 133 | 3,775 |
| `widgets/bottom_sheet_bg.dart` | 22 | 634 |
| `widgets/context_menu.dart` | 85 | 2,689 |
| `widgets/embedded_terminal_init_overlay.dart` | 113 | 3,214 |
| `widgets/irregular_tag_painter.dart` | 90 | 2,159 |
| `widgets/overlapping_tags.dart` | 143 | 4,187 |
| `widgets/primary_button.dart` | 188 | 5,728 |
| `widgets/record_list.dart` | 40 | 1,264 |
| `widgets/record_list_item.dart` | 162 | 5,142 |
| `widgets/scroll_fade_mask.dart` | 129 | 3,944 |
| `widgets/side_bar_drawer.dart` | 634 | 18,820 |
| `widgets/tips_messages.dart` | 78 | 2,550 |
| `widgets/typewriter_text.dart` | 112 | 2,933 |
| `widgets/typing_indicator.dart` | 16 | 398 |
| `widgets/welcome_place_holder.dart` | 239 | 7,742 |

## 测试清理

仅删除孤岛实现专属的 7 个测试文件，共 641 行：

- `ui/test/features/home/pages/chat/utils/stream_text_merge_test.dart`
- `ui/test/features/home/pages/command_overlay/utils/error_message_formatter_test.dart`
- `ui/test/features/welcome/state/onboarding_state_test.dart`
- `ui/test/services/na_cloud_contract_test.dart`
- `ui/test/services/na_cloud_service_test.dart`
- `ui/test/utils/data_parser_test.dart`
- `ui/test/widgets/app_update_banner_test.dart`

另从 `memory_presentation_test.dart` 删除旧 MemoryDetailPage 的 4 项测试，保留 Mem0MemoryEditorSheet 的浅色/深色、键盘、滚动、提交与去重断言；从 `streaming_text_test.dart` 仅删除旧 TypewriterText 的 1 项测试，保留现行 StreamingText 的动画、Markdown、Unicode、布局、点击与流式快照回归。两个混合文件合计减少 141 行。

现行 ACP、会话生命周期、聊天历史、权限、输入框、Provider、消息卡片等测试均继续保留；其他职责迁移把相应数据断言迁到 Kotlin，而不是以删除测试代替验证。测试不进入正式 APK，删除死测试属于维护清理，不能解释为运行时性能提升。

## 依赖与原生插件

移除 11 个在正式可达图无引用的直接依赖：

`crypto`、`carousel_slider`、`cupertino_icons`、`fluttertoast`、`gradient_borders`、`loading_animation_widget`、`permission_handler`、`qr_flutter`、`riverpod_annotation`、`simple_gradient_text`、`xml`。

其中 `crypto 3.0.7`、`riverpod_annotation 4.0.6` 和 `xml 7.0.1` 仍由其他保留依赖传递引入。锁文件没有升级保留包；14 个不再被依赖的包被移除，另外 3 个仅从直接依赖变成传递依赖。

清理前 GeneratedPluginRegistrant 会自动实例化 FlutterToastPlugin 和 PermissionHandlerPlugin，即使 UI 从未调用它们。清理后两个插件从自动注册表和依赖图消失。两个库的 AndroidManifest 都没有权限、Provider 或 Service 声明；现有权限仍由 `special_permission.dart -> SpecialPermissionManager.kt` 处理，toast 沿用应用自有组件。这减少了确实存在的插件初始化及打包工作，但实际耗时/内存收益仍需同机测量。

## 语言统计的解释

清理前 Git tracked 文件的原始统计：

| 范围 | Dart 文件 / 字节 / 行 | Kotlin 文件 / 字节 / 行 |
| --- | ---: | ---: |
| 全部 `.dart` / `.kt` | 576 / 7,448,060 / 227,231 | 777 / 7,308,913 / 178,885 |
| 测试 | 164 / 1,845,632 / 56,319 | 251 / 1,689,354 / 41,137 |
| 已生成的本地化文件 | 3 / 200,144 / 7,166 | 0 |
| 其余生产源码 | 409 / 5,402,284 / 163,746 | 526 / 5,619,559 / 137,748 |

Kotlin 生产数包含内嵌 ReTerminal 的 77 个文件、333,733 字节、8,932 行；排除它后是 5,285,826 字节，与 Dart 生产字节接近。这个统计没有把所有来源逐一鉴定为自有/第三方，不能据此宣称纯自研 Kotlin 一定更多。

[GitHub Linguist 的官方说明](https://github.com/github-linguist/linguist/blob/main/docs/how-linguist-works.md)规定语言条按符合条件的代码字节计算，并排除生成、第三方和文档文件；它不是按行数、运行时耗时或架构职责计算。[GitHub 文档](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-repository-languages)说明统计随默认分支推送异步更新。因此本地原始字节不等于当前 GitHub 语言条，源码迁移也不应以制造某个语言标签为目标。

### 最终工作区对照

以下将 HEAD 的 Git blob 与当前仍存在的 tracked 文件及未被忽略的新增文件对照，包含本轮新增源码和复现工具，不包含 `build/` 下的诊断产物。字节为文件原始字节，行数为物理行；`.kt` 与 `.kts` 分开计数。production 排除 `test`、`androidTest`、`integration_test`、生成文件和诊断工具模板；`.dart` production 对应 `ui/lib` 中非生成文件。

| 范围 | HEAD 文件 / 字节 / 行 | 当前文件 / 字节 / 行 | 净变化：字节 / 行 |
| --- | ---: | ---: | ---: |
| 全部 Dart `.dart` | 576 / 7,448,060 / 227,231 | 519 / 7,176,580 / 218,113 | -271,480 / -9,118 |
| 全部 Kotlin `.kt` | 777 / 7,308,913 / 178,885 | 784 / 7,366,711 / 180,096 | +57,798 / +1,211 |
| Dart production | 409 / 5,402,284 / 163,746 | 357 / 5,135,499 / 154,837 | -266,785 / -8,909 |
| Kotlin production | 526 / 5,619,559 / 137,748 | 529 / 5,651,168 / 138,446 | +31,609 / +698 |
| Dart 测试 | 164 / 1,845,632 / 56,319 | 157 / 1,826,637 / 55,707 | -18,995 / -612 |
| Kotlin 测试 | 251 / 1,689,354 / 41,137 | 255 / 1,715,543 / 41,650 | +26,189 / +513 |
| Dart 生成本地化 | 3 / 200,144 / 7,166 | 3 / 200,144 / 7,166 | 0 / 0 |
| Dart 诊断工具模板 | 0 / 0 / 0 | 2 / 14,300 / 403 | +14,300 / +403 |
| Kotlin 构建脚本 `.kts` | 15 / 50,574 / 1,515 | 15 / 50,574 / 1,515 | 0 / 0 |

ReTerminal 的 `.kt` 在前后均为 77 文件、333,733 字节、8,932 行。排除 ReTerminal 后，Kotlin production 为 5,285,826 → 5,317,435 字节、128,816 → 129,514 行；当前比 Dart production 多 181,936 字节，但行数仍较少。若把 `.kts` 构建脚本计入 Kotlin，当前 Kotlin 总量为 7,417,285 字节、181,611 行。上述本地统计不会直接证明默认分支的 GitHub 语言标签已更新。

最终静态复核仍为 360/360 正式入口可达库。统计下沉使用原有隐藏会话和 Na owner 判定；Room 查询返回后核对 owner，Dart 对排队跨账号返回选用不含 Na 的 `localSummary`。SQL 的每条正用量、completion fallback、cached-only 与 elapsed-week 边界与原 Dart 规则一致；连续天数保留原有 elapsed-day/DST 行为。没有改动 ACP reducer、重试、终态或长期记忆接口。

## 复核命令

本轮复核：可达图为 360/360；3 个受影响测试文件定向分析无问题；上述记忆编辑器、流式文本和原生权限路径共 28 项测试全部通过。生产 `lib` 全量分析无 error，仍有 486 项 warning/info，未扩大本次清理处理其他诊断。`.gitattributes` 未改动，清理相关 `git diff --check` 通过。首次并发测试遇到统计服务迁移的中间态编译错误，待该文件迁移完成后重新运行通过；没有把中间态失败计为功能回归。

```sh
python3 scripts/audit-flutter-reachability.py --json
cd ui
/Users/ocean/.local/share/flutter/3.47.2/bin/flutter pub get --offline
/Users/ocean/.local/share/flutter/3.47.2/bin/flutter analyze --no-pub \
  --no-fatal-warnings --no-fatal-infos \
  test/features/memory/memory_presentation_test.dart \
  test/widgets/streaming_text_test.dart \
  test/services/permission_service_test.dart
/Users/ocean/.local/share/flutter/3.47.2/bin/flutter test --no-pub \
  test/features/memory/memory_presentation_test.dart \
  test/widgets/streaming_text_test.dart \
  test/services/permission_service_test.dart
```


Office XML 解析迁到 Kotlin 后，额外移除生产层已无导入的 `xml` 直接依赖；该包仍可由 Flutter SVG 的工具链传递引入。
