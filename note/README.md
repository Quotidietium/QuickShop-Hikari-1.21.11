# QuickShop-Hikari 项目要点笔记

> 本笔记是对整个项目源码阅读理解的要点提炼（2026-08-16 整理，关键结论已对照源码逐一核实）。
> 完整深度分析报告见 `codebase-analyzer/reports/QuickShop-Hikari-2026-08-16/`（含函数级调用链与变量级数据流）。

## 项目快照

| 项 | 值 |
|---|---|
| 项目 | quickshop-hikari `6.3.0.0-SNAPSHOT-6`（根 pom.xml） |
| 类型 | Minecraft Paper 服务端商店插件（GPL/AGPL v3 双许可） |
| 语言/JDK | Java 21 |
| 构建 | Maven 多模块 + Shade 重定位（fat-jar 含全部 addon/compat） |
| 目标平台 | 仅 Paper（Spigot 直接拒绝启动）；Folia 兼容 |
| 代码规模 | 663 个 Java 文件：api 177 / bukkit 357 / addon 62 / compat 52 / common 12 / platform 3 |
| 数据库 | MySQL 或 H2(MODE=MYSQL)，schema 版本 20，EasySQL + HikariCP |
| 测试 | **0 个测试**（无 src/test、无 @Test、CI 的 Codecov 上传空数据） |
| 本仓库定位 | 独立 fork（已移除 upstream，不再同步社区上游） |

## 一句话理解这个项目

玩家在容器方块上放告示牌即可创建商店（买/卖店），点击告示牌触发交易；
核心难点是「经济转账 + 库存过户」两段式一致性与 Folia 多线程环境下的方块操作调度。

## 核心发现（最重要 5 条）

1. **「胖壳+大脑」双阶段引导**：`QuickShopBukkit`（libby 运行时装库 → Paper 平台检测）→ `QuickShop`（40+ 管理器装配）。经济系统刻意延迟 1 tick 装载以等待 Vault 注册（`QuickShop.java:869`），另有 `EconomySetupListener` 监听 PluginEnableEvent 双保险。
2. **一致性靠补偿而非锁**：经济与库存双栈均采用「快照 + Operation 命令模式 + LIFO 回滚」，业务统一入口 `safeCommit()` / `failSafeCommit()`；SQL 全部跑在 Java 21 虚拟线程（`QuickExecutor.java` newThreadPerTaskExecutor）。
3. **三表分离持久化 + 数据行去重**：`qs_shop_map`（坐标→店ID）⋈ `qs_shops`（店→data）⋈ `qs_data`（实质数据）；`updateShop` 先按字段查重复用 data 行，多个商店可共享同一行（`SimpleDatabaseHelperV2.java`）。
4. **⚠ 两处已核实的资金缺陷**（静态分析确认，修复前需评估影响）：
   - `checkTax` 守卫 `if(totalTax > 0) return` 写反 → **正税额直接返回不入账**，税收从未真正打进税号账户（`QSEconomyTransaction.java:463-465`）；
   - `commit()` 中插件取消分支（`onCommit` 返回 false）只 return，**未调用 `callback.onFailed`**（`QSEconomyTransaction.java:393-397`）。
5. **零测试基线**：全部质量保障依赖 Qodana 静态扫描 + CodeRabbit AI 审查 + 社区反馈；资金敏感路径无回归网。

## 笔记目录

| 笔记 | 内容 |
|---|---|
| [01-架构.md](01-架构.md) | Maven 模块划分、技术栈、核心类关系、数据库表、12 种设计模式 |
| [02-运行原理.md](02-运行原理.md) | 启动流程、数据库初始化与迁移、商店加载、交易时序、线程模型、状态机、关停 |
| [03-工作流.md](03-workflow.md) | 建店/交易/删店业务流程、CI/CD、异常恢复、测试现状 |
| [04-开发速查.md](04-开发速查.md) | 关键代码位置索引、已知缺陷、扩展开发指南（子命令/兼容模块/迁移） |

## 路径缩写约定

以下笔记中：`QS/` = `quickshop-bukkit/src/main/java/com/ghostchu/quickshop/`，`API/` = `quickshop-api/src/main/java/com/ghostchu/quickshop/api/`。
