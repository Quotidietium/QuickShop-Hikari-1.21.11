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
| 测试 | quickshop-bukkit 80 个用例全绿（回归 + 查找表索引 + DB 写缓存 + 文本缓存 + QUser 驻留 + 事件快路径 + 匹配免克隆 + 迭代器快照 + 木牌调度 + 点击路径 + 交易观测） |
| 基准 | `benchmark/` 独立模块：30 用例 × 7 套件（查找表/序列化/经济/文本/H2 数据库/交易热路径/监听器点击 + action 全路径），报告见 [report/perf](report/perf/) |
| 本仓库定位 | 独立 fork（已移除 upstream，不再同步社区上游） |

## 一句话理解这个项目

玩家在容器方块上放告示牌即可创建商店（买/卖店），点击告示牌触发交易；
核心难点是「经济转账 + 库存过户」两段式一致性与 Folia 多线程环境下的方块操作调度。

## 核心发现（最重要 5 条）

1. **「胖壳+大脑」双阶段引导**：`QuickShopBukkit`（libby 运行时装库 → Paper 平台检测）→ `QuickShop`（40+ 管理器装配）。经济系统刻意延迟 1 tick 装载以等待 Vault 注册（`QuickShop.java:869`），另有 `EconomySetupListener` 监听 PluginEnableEvent 双保险。
2. **一致性靠补偿而非锁**：经济与库存双栈均采用「快照 + Operation 命令模式 + LIFO 回滚」，业务统一入口 `safeCommit()` / `failSafeCommit()`；SQL 全部跑在 Java 21 虚拟线程（`QuickExecutor.java` newThreadPerTaskExecutor）。
3. **三表分离持久化 + 数据行去重**：`qs_shop_map`（坐标→店ID）⋈ `qs_shops`（店→data）⋈ `qs_data`（实质数据）；`updateShop` 先按字段查重复用 data 行，多个商店可共享同一行（`SimpleDatabaseHelperV2.java`）。
4. **⚠ 曾发现多处资金缺陷，2026-08-16 审计循环已全部修复**（详见 [04-开发速查.md](04-开发速查.md) 修复记录表）：
   - `checkTax` 守卫写反 → 正税额从不入账（已修 472e773bb）；
   - 经济/库存 commit 两个失败分支漏调 `onFailed`（已修 e19f7407d/8d71e34ef）；
   - **交易数量溢出/零单位刷钱漏洞**：unitSize=0 或溢出为 0 时移 0 件物品却全额转账（已修 9c88938d5）。
5. **测试与性能基线演进**：审计循环建立了回归网（38 用例）；2026-08-16 第一轮性能优化循环（7 轮，详见 [report/perf/2026-08-16-performance-optimization.md](report/perf/2026-08-16-performance-optimization.md)）后达 56 用例全绿，并新增 `benchmark/` 基准模块（19 用例 × 5 套件）。核心收益：id/owner 查找 O(n)→O(1)（142μs/282μs→~100ns）、脏店保存不变跳写 -81%、无参消息渲染 -99%、全量读店 -38%、指标定位 SELECT -99.6%。
6. **第二轮性能优化循环（交易与交互热路径，R8–R12 + R14，详见 [report/perf/2026-08-16-trade-path-optimization.md](report/perf/2026-08-16-trade-path-optimization.md))**：买卖全链路 -62.7%/-61.2%、库存扫描 -44%/-38%、迭代器 O(n²)→O(n)（纯迭代 -98.1%）、交易后木牌更新批处理合并、点击路径方块访问削减（R13 带参文本参数序列化尝试实测无收益已回退并记录）。测试增至 70 用例全绿、基准扩至 28 用例 × 7 套件。
7. **第三轮（action 层全路径，R15，2026-08-17，详见 [report/perf/2026-08-17-action-path-optimization.md](report/perf/2026-08-17-action-path-optimization.md)）**：玩家侧交易入口的预检重复扫描消除（预检测量经 `TradeResult.observation()` 透出，API 加法兼容）、匹配器未命中路径类型门（每异类槽省 2 克隆）、卖方向冗余木牌立即渲染移除（R10 遗漏点）。actionBuy/actionSell **-73.0%/-66.7%**（6 fork 无交叉），两轮累计买/卖全链 -80.5%/-79.5%。测试 80 用例全绿、基准 30 用例；顺带修复基准 mock World 弱引用 GC 隐患。

## 笔记目录

| 笔记 | 内容 |
|---|---|
| [01-架构.md](01-架构.md) | Maven 模块划分、技术栈、核心类关系、数据库表、12 种设计模式 |
| [02-运行原理.md](02-运行原理.md) | 启动流程、数据库初始化与迁移、商店加载、交易时序、线程模型、状态机、关停 |
| [03-工作流.md](03-workflow.md) | 建店/交易/删店业务流程、CI/CD、异常恢复、测试现状 |
| [04-开发速查.md](04-开发速查.md) | 关键代码位置索引、已知缺陷、扩展开发指南（子命令/兼容模块/迁移） |

## 路径缩写约定

以下笔记中：`QS/` = `quickshop-bukkit/src/main/java/com/ghostchu/quickshop/`，`API/` = `quickshop-api/src/main/java/com/ghostchu/quickshop/api/`。
