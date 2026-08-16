# QuickShop-Hikari 代码分析报告

**分析时间**：2026-08-16
**分析范围**：`F:\Github\repo\QuickShop-Hikari-1.21.11`（全仓库）
**分析模式**：采样分析（663 个 Java 文件，大型项目；核心子系统全量精读 + 扩展模块模式采样）
**代码规模**：663 个 Java 文件（api 177 / bukkit 357 / addon 62 / compat 52 / common 12 / platform 3）
**技术栈**：Java 21 · Maven 多模块 · Paper API 1.21 · FoliaLib · EasySQL/HikariCP（MySQL+H2）· Adventure/MiniMessage · Vault/VaultUnlocked · PacketEvents/ProtocolLib · MenuCore

## 报告目录

| 报告 | 内容概要 | 篇幅 |
|------|---------|------|
| [项目架构](01-architecture.md) | 模块依赖图、技术栈全景、40+ 服务组件图、6 张核心类图（商店/交易/经济/展示/交互/持久化 ER）、12 种设计模式、函数级调用链 | ~11 图 |
| [运行原理](02-operation-principles.md) | 双阶段引导时序图、onLoad/onEnable 全序列、数据库初始化与迁移链、商店加载流程、购买交易完整时序图、变量级数据变换表、4 个状态机、线程模型、关停序列 | ~13 图 |
| [工作流分析](03-workflow.md) | CI/CD 全景（7 工作流）、发布流水线、建店决策树、交易决策树、删店流程、热重载、异常恢复总表、测试现状 | ~10 图 |
| [AI 替代方案](04-ai-substitution.md) | 12 模块六维评分、ROI 四象限、函数级接口契约（update/safeCommit）、路线图 | 2 图 + 多表 |
| [Skill Blueprint 索引](blueprints/index.md) | 4 个可 AI 替代组件的完整 Skill 设计规格 | 4 Blueprint |

## 核心发现

1. **「胖壳+大脑」双阶段引导**：`QuickShopBukkit`（libby 运行时装库→Paper 平台检测）→ `QuickShop`（40+ 管理器装配，onEnable 42 步），经济系统刻意延迟 1 tick 装载以等待 Vault 注册（QuickShop.java:869）。
2. **一致性靠补偿而非锁**：经济与库存双栈均采用「快照 + Operation 命令模式 + LIFO 回滚」，业务统一 `safeCommit()/failSafeCommit()`；SQL 全部跑在 Java 21 虚拟线程（QuickExecutor.java:33-36）。
3. **数据行去重的三表分离持久化**：qs_data/qs_shops/qs_shop_map 分离 + `queryDataId` 字段查重复用 data 行（SimpleDatabaseHelperV2.java:921-941）；迁移链当前版本 20，H2(MODE=MYSQL) 已取代 SQLite。
4. **两处疑似资金缺陷（静态分析）**：`checkTax` 守卫 `if(totalTax>0) return` 写反致正税额不入账（QSEconomyTransaction.java:462-464）；`onCommit` 取消分支漏调 `onFailed`（:393-397）。
5. **零测试基线**：全仓库无任何 @Test/surefire/jacoco，CI 的 Codecov 步骤上传空数据，质量保障完全依赖 Qodana 静态扫描 + CodeRabbit + 社区反馈。

## 关键建议

1. **立即**：验证 `checkTax` 条件缺陷——税收是否真实入账影响所有启用税的服务器（04 报告 §5.2 契约）。
2. **本月**：为 `QSEconomyTransaction`/`SimpleInventoryTransaction` 补单元测试（H2 内存库 + JUnit5 即可覆盖资金链，Phase 2 最高 ROI）。
3. **本月**：落地两个 Quick Win Skill（兼容模块生成器 28 分、子命令脚手架 27 分）——30 个 compat 模块样板证明该模式极稳定。
4. **本季度**：CI 补 surefire 门禁，替代当前"构建成功即合入"的质量闸门。
