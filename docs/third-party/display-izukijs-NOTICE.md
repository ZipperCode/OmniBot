# 第三方开源代码归属声明：IzukiJS（虚拟屏与特权显示）

本项目包含并参考移植了开源项目 `IzukiJS` 的部分实现。

- **Project**: IzukiJS
- **Repository**: https://github.com/BUGJI/IzukiJS
- **Fixed Commit SHA**: `eb995dff24849fed215a75a136d4b2d076f69c69`
- **License**: GNU General Public License v3.0 (GPLv3) (see `display-izukijs-LICENSE.txt`)
- **Copyright**: Copyright (C) 2024-2026 BUGJI and IzukiJS contributors

---

## 实际移植源码范围

1. `app/src/main/java/com/benton/izukijs/privileged/internal/PrivilegedVirtualDisplay.kt`
   - 虚拟屏系统 Flag 组合：`FLAG_SUPPORTS_TOUCH`、`FLAG_DESTROY_CONTENT_ON_REMOVAL`、`FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS`、`FLAG_TRUSTED`、`FLAG_OWN_DISPLAY_GROUP`、`FLAG_OWN_FOCUS`。
   - WindowManager 属性调优：`setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_HIDE)` 与 `freezeRotation(displayId, ROTATION_0)`。
   - 多显示屏 Activity 启动参数适配：`ActivityOptions.setLaunchDisplayId(displayId)`。

2. `app/src/main/java/com/benton/izukijs/virtualscreen/VirtualScreenFrameSource.kt`
   - 基于 `ImageReader` 的帧捕获 Surface 构建。
   - 图像帧缓冲读取、Bitmap 转换与截屏数据拷贝逻辑。

3. `app/src/main/java/com/benton/izukijs/virtualscreen/ScreenTarget.kt` 与 `VirtualScreenManager.kt`
   - 显示屏目标枚举与多屏基础生命周期模型。

---

## OmniBot Access / Binder 架构适配与自主设计

1. **安全锁屏合规（覆盖所有 GUI 显示屏）**：
   - 彻底移除 IzukiJS 原有的 `FLAG_ALWAYS_UNLOCKED`，严格遵守系统安全锁屏策略。
   - 无论物理主屏还是后台虚拟屏，所有 GUI 显示屏在执行交互动作前统一检查安全锁屏状态；一旦锁定，触发可取消的解锁等待挂起流程，不绕过锁屏，不保存锁屏密码。

2. **并发控制与屏幕输入租约隔离**：
   - 接入 OmniBot 的 `DisplayInputCoordinator`，多步 GUI 交互按 displayId 持有排他输入租约，支持同会话重入且互不阻塞，单次操作释放租约不影响会话虚拟屏生存期；编译、下载等后台任务完全并行。

3. **事实错误反馈与多屏兼容边界**：
   - 目标应用不支持在副屏/虚拟屏运行时，返回事实错误并提示用户切换屏幕，不静默强制关停无关任务，亦不未经授权静默回落至主屏。

4. **KernelSU Standalone Root Display Service 架构**：
   - 不同于 IzukiJS 的守护进程架构，OmniBot 针对 KernelSU Root-only 环境设计了独立 UID 0 的 standalone `app_process` 辅助进程。
   - 服务端显式建立 `ActivityThread.systemMain()` 与系统级 Context / Looper，解决独立进程无 Context 导致无法创建 DisplayManager 的问题。
   - 客户端与服务端通过随机 Nonce 一次性显式广播与 `Bundle.putBinder` 完成 Binder 交接并双向校验 UID，彻底规避 `untrusted_app` 在 SELinux 下访问 `ServiceManager` 的不可行路径。
   - 常驻 Helper 进程纳入 `PrivilegedCapabilityManager` 的受控会话与进程组统一管理，随授权撤销、ACP 会话关闭或取消而级联销毁。

## 悬浮交互参考（不含代码移植）

- 交互参考：[Mangi-11/Eta](https://github.com/Mangi-11/Eta)，观察版本 `c866a747ba89787d15beed5030aa8513960aea45` 的 `AgentOverlayCapsule.kt`、`VirtualDisplayStageCard.kt` 与 `AgentOverlayContent.kt`：顶部胶囊 / 微缩取景器、展开装裱式预览、主屏边缘流光及底部结果选择。
- 上游采用 [PolyForm Noncommercial 1.0.0](https://github.com/Mangi-11/Eta/blob/c866a747ba89787d15beed5030aa8513960aea45/LICENSE)，并要求保留 `Required Notice: Copyright © 2026 蛮吉 (Mangi-11).`；本次未复制或移植其源码、资源、图标、状态模型或依赖，仅据可见交互独立实现。
- OmniBot 的 `ScreenTaskOverlayHost` / `ScreenTaskOverlayViews` 使用自身品牌配色与原生 View，复用原有 ACP / TaskRuntime、显示帧源及取消移交流程；该参考不改变既有 IzukiJS 移植代码的许可证归属。

---

## 验证边界声明

本声明仅反映代码层面的移植与架构适配事实。真实 Android/KernelSU 设备上的 SELinux 域继承、跨进程广播时延以及各厂商定制 ROM 对私有 VirtualDisplay 接口的兼容性，不能在此声称已得到证明，须以后续端到端真机集中验证结果为准。
