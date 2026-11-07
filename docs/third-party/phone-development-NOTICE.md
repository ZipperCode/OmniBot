# 第三方开源代码使用声明 (Notice)

本项目在 `cn.com.omnimind.bot.agent.development` 及 `phone-development` 资源目录中包含并借鉴了来自以下开源项目的代码与机制：

## 1. Sanuu7/AndroidHarness

- **项目主页**: `https://github.com/Sanuu7/AndroidHarness`
- **固定版本 (Commit SHA)**: `c000d3bded50005f1ae3413bec203b350286ac35`
- **开源许可证**: MIT License（见同目录 `phone-development-LICENSE.txt`）
- **复用与借鉴模块**:
  1. `AndroidSdk.kt`：Google 官方 SDK 仓库索引 (`repository2-3.xml`) 平台信息解析逻辑、SHA-1 校验与平台 `android.jar` 单文件提取流程。
  2. `AndroidBuildToolchain.kt`：移动端 Linux 终端编译环境前缀定位、启动器包装脚本路径修正与字节码完整性校验逻辑。
  3. `build-apk.sh`：手机本地极速无 Gradle 构建流水线（aapt2 链接、Java 8 编译、D8 打包、Android 11+ `resources.arsc` 4 字节对齐、Debug 密钥库生成与 apksigner 签名验签）。

## 2. Alpine Linux aports (android-build-tools)

- **源码仓库**: `https://gitlab.alpinelinux.org/alpine/aports/-/tree/master/testing/android-build-tools`
- **固定版本**: 16.0-r3 (commit `0ec06cd43ad0f3da9bf91100969c756f83f98e9f`)
- **开源许可证**: Apache-2.0
- **使用说明**: 手机端隔离运行环境通过 musl 原生包 `android-build-tools` 提供真实可运行的 `aapt2` 与 `zipalign` 二进制工具，使用独立 `--root` 目录隔离安装，不污染或升级设备现有根文件系统。

## 3. Google Android SDK Build-Tools (apksigner / ApkSignerTool)

- **官方来源**: Google Android SDK Repository (`https://dl.google.com/android/repository/`)
- **版本**: 35.0.1 (`build-tools;35.0.1`) / `build-tools_r35.0.1_linux.zip`
- **归档摘要 (SHA-1)**: `e009a9b188cfeb1d2b4c318ab5cb4f1ddc368861` (大小: 61,959,297 字节)
- **核心 CLI 工具 JAR**: `lib/apksigner.jar` (类: `com.android.apksigner.ApkSignerTool`, `com.android.apksig.ApkSigner`)
- **JAR 校验摘要**:
  - SHA-1: `a183c68d76048c587fee78eb7f84e17178ec2198`
  - SHA-256: `00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7`
  - 大小: 1,074,241 字节
- **开源许可证**: Apache-2.0
- **使用说明**: 包含完整 `com.android.apksigner.ApkSignerTool` 命令行入口及底层 `apksig` 签名引擎，可直接由 JVM 启动 (`java -jar apksigner.jar`)，支持 v1, v2, v3, v4 签名方案与验签，不冒充库文件，不引入未授权的二进制或过时第三方重新打包产物。

## 4. Xposed Framework API (api-82.jar)

- **官方来源**: `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`
- **版本**: API 82
- **校验摘要**:
  - SHA-1: `35866b507b360d4789ff389ad7386b6e8bbf6cc4`
  - SHA-256: `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`
  - 大小: 25,478 字节
- **开源许可证**: Apache-2.0
- **使用说明**: 官方 compileOnly 存根库，仅供 LSPosed 模块编译期类型推导使用，绝不打包进 DEX。

## 5. Alpine OpenJDK PRoot 执行隔离兼容机制 (jvm-compat)

- **参考项目**: `https://github.com/lidanthedev/Acode-fixexec`
- **固定版本 (Commit SHA)**: `087206a34856aa372d653b85fc0ce873fc22d84b`
- **开源许可证**: GPL-3.0-or-later
- **使用说明**: 参考该项目公开的 Android JVM 启动失败调查，未采用其失败后解除映射再用 `MAP_FIXED` 重映射的实现。兼容资产仅隔离 HotSpot 单页探测与线程栈映射，不修改真实 `mprotect` 结果；由手机工具链构建，仅加载于 Java 启动器。
