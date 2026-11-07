# Root Management & Module Interface Provenance

本文件记录 OmniBot 手机 Agent 高权限管理控制器 (`PrivilegedManagementController`) 参考的开源上游仓库、固定 Revision (SHA)、开源许可证与实际代码对齐规范。

所有上游路径、表结构与命令格式均已通过直接下载 GitHub 完整归档 Zip（而非第三方报告引用）逐文件静态核实。

---

## 1. KernelSU (ksud / module / profile)

- **上游主仓库**: `https://github.com/tiann/KernelSU`
- **固定提交 (Commit SHA)**: `019a3c0103a8f8d56675546016c4073d0fd18352`
- **归档包校验**: `https://github.com/tiann/KernelSU/archive/019a3c0103a8f8d56675546016c4073d0fd18352.zip` (4,759,310 bytes)
- **开源许可证**: GNU General Public License v3.0 (GPL-3.0)
- **直接核实源文件与对齐约定**:
  - `userspace/ksud/src/defs.rs`:
    - `ADB_DIR`: `/data/adb/`
    - `MODULE_DIR`: `/data/adb/modules/`
    - `MODULE_UPDATE_TMP_DIR`: `/data/adb/modules_update/`
    - `DAEMON_PATH`: `/data/adb/ksud`
    - `DAEMON_LINK_PATH`: `/data/adb/ksu/bin/ksud`
  - `userspace/ksud/src/cli.rs`:
    - 子命令定义（`enum Module`）：
      - `Install { zip: String }` -> `ksud module install <zip>`
      - `Uninstall { id: String }` -> `ksud module uninstall <id>`
      - `Enable { id: String }` -> `ksud module enable <id>`
      - `Disable { id: String }` -> `ksud module disable <id>`
      - `List` -> `ksud module list`
      - `Action { id: String }` -> `ksud module action <id>`
  - `userspace/ksud/src/module.rs`:
    - 停用标志文件：`/data/adb/modules/<id>/disable`（文件存在即停用，删除即启用）。
    - 移除标志文件：`/data/adb/modules/<id>/remove`（文件存在则下次重启清理）。
    - 模块属性：`module.prop` 键值对解析 (`id`, `name`, `version`, `versionCode`, `author`, `description`)。
    - 模块热更新判定：`.ksu_hot_install`。

---

## 2. Vector / LSPosed (Modern Xposed Framework)

- **上游主仓库**: `https://github.com/JingMatrix/Vector`
- **上游参考归档**: `https://github.com/LSPosed/LSPosed`
- **固定提交 (Commit SHA)**: `093ea599bfa2b50c156da0af53e2e53576e88434`
- **归档包校验**: `https://github.com/JingMatrix/Vector/archive/093ea599bfa2b50c156da0af53e2e53576e88434.zip` (1,620,627 bytes, 996 files)
- **开源许可证**: GNU General Public License v3.0 (GPL-3.0)
- **直接核实源文件与对齐约定**:
  - `daemon/src/main/kotlin/org/matrix/vector/daemon/data/FileSystem.kt`:
    - `basePath`: `/data/adb/lspd`
    - `configDirPath`: `/data/adb/lspd/config`
    - `dbPath`: `/data/adb/lspd/config/modules_config.db`（备用 `/data/adb/lspd/config/modules.db`、`/data/adb/vector/config/modules_config.db`）
    - `socketPath`: `/data/adb/lspd/.cli_sock` (LocalSocket IPC)
    - `cliDest`: `/data/adb/lspd/cli`
  - `zygisk/module/cli`:
    - 守护服务 CLI 执行脚本：
      `exec /system/bin/app_process $java_options /system/bin --nice-name=VectorCli org.matrix.vector.daemon.Cli "$@"`
  - `daemon/src/main/kotlin/org/matrix/vector/daemon/Cli.kt` & `ipc/CliHandler.kt`:
    - CLI 命令集：
      - `status`: 查看框架与系统健康状态
      - `modules`: `ls`, `enable <pkg...>`, `disable <pkg...>`
      - `scope`: `ls <module_pkg>`, `add <module_pkg> <target_pkg/user_id>`, `set <module_pkg> <target_pkg/user_id>`, `rm <module_pkg> <target_pkg/user_id>`
    - 守护进程通信机制：
      - 通过 `LocalSocket` 接入 `/data/adb/lspd/.cli_sock`，经 `CliSocketServer` 将请求投递至守护进程内存中的 `CliHandler`。
      - `CliHandler` 内部调用 `ModuleDatabase.enableModule(...)` 与 `setModuleScope(...)`，并在操作完成后触发 `ConfigCache.requestCacheUpdate()` 刷新常驻内存中的模块加载缓存。
  - `daemon/src/main/kotlin/org/matrix/vector/daemon/data/Database.kt` & `ModuleDatabase.kt`:
    - 数据库版本：`DB_VERSION = 5`
    - 表结构定义（SQLite 官方支持直接读写）：
      ```sql
      CREATE TABLE IF NOT EXISTS modules (
          mid integer PRIMARY KEY AUTOINCREMENT,
          module_pkg_name text NOT NULL UNIQUE,
          apk_path text NOT NULL,
          enabled BOOLEAN DEFAULT 0 CHECK (enabled IN (0, 1)),
          auto_include BOOLEAN DEFAULT 0 CHECK (auto_include IN (0, 1))
      );

      CREATE TABLE IF NOT EXISTS scope (
          mid integer,
          app_pkg_name text NOT NULL,
          user_id integer NOT NULL,
          PRIMARY KEY (mid, app_pkg_name, user_id),
          CONSTRAINT scope_module_constraint FOREIGN KEY (mid) REFERENCES modules (mid) ON DELETE CASCADE
      );

      CREATE TABLE IF NOT EXISTS auto_include (
          mid integer NOT NULL,
          user_id integer NOT NULL,
          PRIMARY KEY (mid, user_id),
          CONSTRAINT auto_include_module_constraint FOREIGN KEY (mid) REFERENCES modules (mid) ON DELETE CASCADE
      );

      CREATE TABLE IF NOT EXISTS configs (
          module_pkg_name text NOT NULL,
          user_id integer NOT NULL,
          `group` text NOT NULL,
          `key` text NOT NULL,
          data blob NOT NULL,
          PRIMARY KEY (module_pkg_name, user_id, `group`, `key`),
          CONSTRAINT config_module_constraint FOREIGN KEY (module_pkg_name) REFERENCES modules (module_pkg_name) ON DELETE CASCADE
      );
      ```
    - 架构划分（摘自 `ModuleDatabase.kt` 源码说明）：
      - `ModuleDatabase`（Configuration）：用户配置的唯一可信源（Source of Truth），提供事务性即时持久化写入与查询。
      - `ConfigCache`（Realisation）：进程内加载实现，负责解析 DEX、DEX 校验和应用 UID 映射，由守护进程异步合并更新。
      - 在无 CLI 交互环境直接写 SQLite 时，需通过重启目标应用 (`am force-stop`) 或软重启框架 (`ctl.restart zygote`) 触发冷启动加载。
  - 关键安全约束：
    - 不隐式操作 `crashrecovery.rescue_boot_count`；仅在用户明确执行框架软重启时调用 `setprop ctl.restart zygote`。
    - 严格区分配置生效（`config_verified`）与运行时钩子活跃（`hook_verified`）。

---

## 3. AOSP (Android Open Source Project)

- **来源**: Android 平台框架与工具集
- **开源许可证**: Apache License 2.0
- **对齐能力**:
  - 包管理：`pm install -r [-g] [-d]`, `pm uninstall [-k]`, `pm disable-user`, `pm enable`, `pm path`, `dumpsys package`。
  - 权限与 AppOps：`pm grant`, `pm revoke`, `cmd appops set/get`。
  - 进程管理：`am force-stop`, `kill`, `/proc/<pid>/`。
  - 系统属性与内核：`getprop`, `setprop`, `getenforce`, `setenforce 0/1`，分区与启动控制。
