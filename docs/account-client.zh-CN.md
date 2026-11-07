# OmniBot 账号与官方 AI 客户端（已退役）

> **状态说明**：OmniBot 客户端已全面移除小万账号体系与官方 AI 额度服务。本说明用于归档历史架构并明确退役后的数据与本地行为边界。原有的账号启用与部署教程已废弃。

## 退役概述

在当前版本中，客户端完全去中心化运行，不再依赖任何中心化账号或官方中转服务：

1. **移除小万账号体系**：
   - 移除注册、邮箱验证码、登录、退出、修改密码、找回密码与删除账号功能。
   - 移除设备登录会话管理、多端设备查看与撤销。
   - 移除 Access JWT 与 Refresh Token 的 Android Keystore 加密存储及 401 自动刷新机制。
   - 移除底层 `AccountRepository` 与 `AccountApiClient`。

2. **移除官方 AI 与平台额度**：
   - 移除“OmniBot 官方 AI”只读渠道与平台额度（Platform Mode）。
   - 移除品牌模型网关（`/v1/models`、`/v1/chat/completions`）代理调用与同步逻辑。
   - 移除底层 `PlatformModelApiClient`。
   - 应用无需登录即可直接使用，设置中不再提供账号入口，权限引导流程直达模型提供商配置。

## 数据隔离与本地状态

- **普通本地聊天与记忆不变**：
  普通本地对话历史、ACP 会话、本地工作区文件以及短期/长期记忆嵌入系统保持完全独立，不受账号退役影响，继续在本地数据库与私有存储中持久化。
- **用户自配 Provider（BYOK / OAuth）**：
  模型提供商（Provider）与场景模型绑定完全由用户在本地配置管理。支持用户自填 API Key 与第三方 Provider OAuth（如 ChatGPT OAuth），请求由客户端直接发送至目标服务商。
- **旧 Na 本地历史处理**：
  原有旧版 Na 云端助手的本地历史记录**未从本地数据库中删除，但已在界面中做隔离隐藏**，抽屉与会话列表中不再展示，且**绝不会转给其他 Agent 或混入通用聊天**。
- **服务器状态与数据**：
  本次客户端改动仅涉及客户端源码与本地视图，**未对远端服务器（账号服务、模型网关、Na 服务端）的状态与数据进行任何操作或删除**。

## 历史接口归档（已废弃）

下列曾由客户端调用的账号与模型接口现已全部停用：

- 认证与会话：`POST /v1/auth/email-codes`、`POST /v1/auth/register`、`POST /v1/auth/login`、`POST /v1/auth/refresh`、`POST /v1/auth/logout`、`POST /v1/auth/password-reset`
- 个人信息与设备：`GET /v1/me`、`DELETE /v1/me`、`PUT /v1/me/password`、`GET /v1/me/sessions`、`DELETE /v1/me/sessions/*`
- 平台用量与设置：`GET /v1/me/platform-usage`、`GET /v1/me/ai-settings`、`PUT /v1/me/ai-settings`
- 官方目录：`GET /v1/models`（品牌模型网关）

客户端构建时不再需要配置 `OMNIBOT_BASE_URL` 或 `OMNIBOT_AI_GATEWAY_URL`。
