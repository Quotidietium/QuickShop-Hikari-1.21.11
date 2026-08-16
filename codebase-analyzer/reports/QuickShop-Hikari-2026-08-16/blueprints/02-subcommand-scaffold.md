# Skill Blueprint: quickshop-subcommand-scaffold

> 自动生成自 codebase-analyzer｜分析时间：2026-08-16
> 源模块：`quickshop-bukkit/.../command/`（60 个子命令）

---

## 1. 基本信息

| 字段 | 值 |
|------|-----|
| **推荐 Skill 名称** | `quickshop-subcommand-scaffold` |
| **用途** | 按 QuickShop 命令框架约定生成新子命令类并完成全部注册项 |
| **AI 替代等级** | 🤖 完全 AI 化（评分 27/30） |
| **实施优先级** | 🥇 Quick Win |
| **源文件数** | 60+ |
| **源代码行数** | 单命令 40-300 行 |

## 2. 触发场景与关键词

- "给 QuickShop 加一个 /qs xxx 命令"
- "新增子命令实现……功能"
- "写一个 silent 命令给控制面板用"
- "add a subcommand"

**推荐 description 触发词：**
```yaml
description: >-
  Scaffold QuickShop-Hikari subcommands following the CommandHandler convention,
  including registration in SimpleCommandManager and permission wiring. Triggered
  by: "子命令", "subcommand", "/qs 命令", "silent 命令".
```

## 3. 输入输出契约

### 必须实现的接口（API/command/CommandHandler.java:25）

| 函数 | 输入 | 输出 | 说明 |
|---|---|---|---|
| `onCommand(sender, parser)` | `T sender, CommandParser parser` | `void` | 权限已由管理器统一检查（SimpleCommandManager.java:591-599） |
| `onTabComplete(sender, parser)` | 同上 | `List<String>` | 可选默认空 |

### 注册契约（SimpleCommandManager 构造器 :121-529）

```java
registerCmd(CommandContainer.builder()
    .prefix("yourcmd")                                    // 唯一前缀
    .executor(new SubCommand_YourCmd(plugin))             // 或 silent: runtimeUUID 首参
    .permission(selective ? PermissionType.SELECTIVE : PermissionType.REQUIRE)
    .permissions("quickshop.yourcmd")                     // REQUIRE 全须满足 / SELECTIVE 任一
    .hidden(false)                                        // silent 命令必须 true
    .description(textManager.of("quickshop.commands.yourcmd")) // messages.yml 键
    .build());
```

### silent 命令模板（SubCommand_SilentBase.java:21-45）

```java
// 首参为商店 runtimeUUID → getShopFromRuntimeRandomUniqueId(uuid) → doSilentCommand(shop)
// 用于 GUI 图标/聊天面板以玩家身份触发命令；hidden=true 不出现在帮助与补全
```

### 错误码（文本键）

| 键场景 | messages.yml 键 | 输出 |
|---|---|---|
| 商店未找到 | `quickshop.commands.not-found`（经 text().of） | 红色提示 |
| 无参数 | `quickshop.wrong-args` | 用法提示 |
| 功能禁用 | disabledSupplier 使命令不可见 | 帮助中隐藏 |

## 4. 依赖清单

| 依赖 | 用途 | 来源 |
|---|---|---|
| `CommandContainer`/`CommandHandler` | 接口契约 | API/command/ |
| `SimpleCommandManager` | 注册点 | QS/command/SimpleCommandManager.java:102 |
| `text()` i18n | 输出消息 | QuickShop API |
| `plugin.perm()` | 运行时二次权限检查（如需） | QuickShop.java:750 |
| messages.yml | 文本键登记 | quickshop-bukkit/src/main/resources/messages.yml |
| custom-subcommands 配置 | 命令前缀覆盖（QuickShop.java:1432-1441） | config.yml |

## 5. Skill 工作流设计

```markdown
### Step 1: 解析需求
命令名/别名、权限节点、目标用户（Player only?）、是否 silent、成功/失败文案键

### Step 2: 选模板
- 纯查询命令 → SubCommand_About（:281）
- 找店操作命令 → SubCommand_Price（:210）：findShop/getLookingShop 辅助（CommandHandler.java:29-86）
- silent 面板命令 → SubCommand_SilentBuy（silent/）
- 管理员批量命令 → SubCommand_RemoveAll（:383）

### Step 3: 生成
① QS/command/subcommand/SubCommand_YourCmd.java（风格：final 类、final 参数、@NotNull）
② SimpleCommandManager 构造器追加 registerCmd(...)（保持字母序）
③ messages.yml 增加命令描述键（en_us）
④ config.yml 权限段（若需新权限节点）

### Step 4: 自检
- [ ] onCommand 内所有 Bukkit API 调用注意线程（主线程命令默认安全）
- [ ] 输出全部走 text().of() 严禁硬编码英文
- [ ] silent 命令 hidden=true 且首参 UUID
- [ ] 权限同时更新 README 权限表（如有）
```

**建议 Constraints：**
```markdown
- Always 输出经 text().of() 本地化，Never 硬编码用户可见文本
- Always 在 SimpleCommandManager 中按字母序插入注册
- Never 在子命令内重复权限检查（管理器已做），除非需要运行时动态判断
- Never 直接操作数据库/经济（走 shopManager/QuickShopAPI 门面）
```

## 6. 所需工具权限

| 工具 | 用途 | 必需性 |
|---|---|---|
| Read | 读模板命令/管理器 | 必需 |
| Write | 生成命令类 | 必需 |
| Edit | 注册+文本键 | 必需 |
| Grep | 找相似命令 | 可选 |

**建议 allowed-tools：** `Read Write Edit Grep`

## 7. 使用示例

### ✅ Do This
```
用户: "加一个 /qs freezeall 冻结指定世界所有商店，权限 quickshop.freezeall"
输出: SubCommand_FreezeAll.java（遍历 getShops(world) → shop.shopState(FROZEN_STATE)）
      + SimpleCommandManager 注册（REQUIRE 权限）+ messages.yml 两键 + 提示运行时需 reload
```

### ❌ Not This
```
错误输出: 在命令类内手写权限判断 if(player.hasPermission("..."))（与管理器重复且破坏 SELECTIVE 语义）
```

## 8. 参考材料

- 框架：`API/command/CommandHandler.java`、`API/command/CommandContainer.java`
- 管理器：`QS/command/SimpleCommandManager.java:102-529`（60 个注册范例）
- silent 基类：`QS/command/subcommand/silent/SubCommand_SilentBase.java`
- 权限模型：SimpleCommandManager.java:591-623（REQUIRE/SELECTIVE/executorType 三检查）
