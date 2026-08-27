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
| 测试 | quickshop-bukkit 144 个用例全绿（回归 + 查找表索引 + DB 写缓存 + 文本缓存 + QUser 驻留 + 事件快路径 + 匹配免克隆 + 迭代器快照 + 木牌调度 + 点击路径 + 交易观测 + 指标批处理 + 剩余 DB 写批处理 + 文本预解析等价 + 日志懒队列 + 展示物重送 + 菜单库存快照 + 开箱扫描快路径 + 签名渲染单扫 + 区块加载快门 + 签名排程去重 + 杂项热路径快照 + 价格格式化快照） |
| 基准 | `benchmark/` 独立模块：47 用例 × 8 套件（R31 起，热点 mock 全 stubOnly）（查找表/序列化/经济/文本/H2 数据库（含指标与外部缓存插入）/交易热路径（含 action 全路径）/监听器点击与展示物/菜单库存流水），报告见 [report/perf](report/perf/) |
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
16. **第十二轮（R24·开箱守卫扫描快路径，2026-08-26，详见 [report/perf/2026-08-26-inventory-check-fastpath.md](report/perf/2026-08-26-inventory-check-fastpath.md)）**：InventoryOpenEvent 全服触发的守卫物 54 槽扫描在虚拟展示模式（默认）下可证恒假，一次模式判定整段跳过（基准 **-99.6%**，2.09ms→8.1μs，六 fork 零重叠；真实成本模型下 15~30μs→0.3μs/次开箱）。测试 118 用例全绿、基准 39 用例。

17. **第十三轮（R25·签名渲染管线，2026-08-27，详见 [report/perf/2026-08-27-sign-render-pipeline.md](report/perf/2026-08-27-sign-render-pipeline.md)）**：木牌刷新（每笔交易后/漏斗供料后经 SignUpdateWatcher 落主线程）的 render 对同一库存量双扫（header 的 inventoryAvailable 与 trading 行的 remainingStock 在内置类型下同源）压为单扫共享（第三方店型保持独立双查保精确）、layout 模板按店型缓存（reload 失效）、renderItem 标志快照、setSignText 每牌子 config 读取提出循环（基准 **-47.6%**，1.86ms→0.98ms，六 fork 零重叠）。测试 126 用例全绿、基准 40 用例。

18. **第十四轮（R26·区块加载路径双快门，2026-08-27，详见 [report/perf/2026-08-27-chunk-load-fastgates.md](report/perf/2026-08-27-chunk-load-fastgates.md)）**：全服最高频事件之一的区块加载处理——守卫物孤儿清扫在虚拟展示后端（默认）下可证恒假整段跳过（getEntities 快照+逐实体检查→1 次后端判定，AbstractDisplayItem.canProduceGuardItems 与 checkIsGuardItemStack 早退同源）；无商店区块 isEmpty 早退（原 null 检查在 R2 返回 emptyMap 后永不触发，每次加载白付 chunkName 拼接+PerfMonitor+无条件 performance 空记录）；非虚拟模式孤儿清扫行为不变（基准 **-95.8%**，560μs→23.8μs，六 fork 零重叠）。测试 133 用例全绿、基准 41 用例。

19. **第十五轮（R27·签名排程去重 O(1)，2026-08-27，详见 [report/perf/2026-08-27-sign-schedule-dedup.md](report/perf/2026-08-27-sign-schedule-dedup.md)）**：漏斗/交易触发的 scheduleSignUpdate 去重从待处理队列线性扫描（500ms 窗口内全部商店数，高频漏斗服务器 O(n²)/窗口）改为伴生 ConcurrentHashMap.newKeySet（ContainerShop 身份等价使两者语义严格一致；排空移除、可重排、首胜 locale 保持）（基准 **-98.6%**，2.25μs→32ns，六 fork 零重叠，绝对值即真实量级）。测试 135 用例全绿、基准 42 用例。

20. **第十六轮（R28·杂项热路径复合，2026-08-27，详见 [report/perf/2026-08-27-misc-hotpath-composite.md](report/perf/2026-08-27-misc-hotpath-composite.md)）**：三处求值策略替换——Util 物品名双 config 标志 volatile 快照（initialize() reload 钩子刷新，构件级基准 **-99.5%** 零重叠）、ChatListener 聊天门控快照（被取消聊天链中位数 **-29.3%**）、漏斗/投掷器监听 InventoryHolder 双次获取合并；PlayerEvent.getPlayer 为 final 的反射注入法与「被优化构件直接计量」范式留档。测试 140 用例全绿、基准 44 用例。

21. **第十七轮（R29·价格显示链，2026-08-27，详见 [report/perf/2026-08-27-price-chain.md](report/perf/2026-08-27-price-chain.md)）**：EconomyFormatter/BuiltInEconomyFormatter 的 alternate-currency-symbol 快照（内部格式化兜底路径每次 getString → reload 刷新字段；签名/收据/菜单价格与 Vault 空返回兜底均经此路，构件基准 **-98.4%** 六 fork 零重叠）+ MsgUtil.decimalFormat 共享 DecimalFormat 改 ThreadLocal（**红线内稳定性修复**：Folia 区域线程并发渲染价格时共享非线程安全 NumberFormat 有错乱风险）。测试 144 用例全绿、基准 45 用例。至此库内已知热点均有结论。

22. **第十八轮（R30·交易链配置访问清扫，2026-08-27，详见 [report/perf/2026-08-27-trade-chain-config-sweep.md](report/perf/2026-08-27-trade-chain-config-sweep.md)）**：系统性 grep 甄别法替代定向发现——actionBuying/actionSelling 每笔交易的 pay-unlimited-shop-owners 改用既有快照字段、店主通知 show-tax 新增独立快照字段（与 shop-tax.show 不同源易错点）、ShopUtil「all」计算路径静态快照挂 Util.initialize 钩子；每笔交易省 2-3 次 config 树行走（千分位级低于噪声底，以紧邻交替六 fork 的 44 共享用例零回归入册）；双侧同窗处置时段型污染的方法学留档。

23. **第十九轮（R31·启动装载并行化，2026-08-27，详见 [report/perf/2026-08-27-startup-load-parallel.md](report/perf/2026-08-27-startup-load-parallel.md)）**：ShopLoader.loadShops 读店循环「supplyAsync 后逐店 join」的伪异步串行流水（CPU 核数级线程池退化为单线程+每店移交开销）改为全量收集 futures 后 allOf 聚合等待——计数器/列表/异常隔离/nextTick 时序全保持，仅日志交错真实并行化；2000 店全链 wall-clock **-65.9%**（98ms→33ms，六 fork 零重叠），`database.loader-threads=1` 可完全复原旧行为。新增 startup/shopLoadChain wall-clock 计量面。基准 46 用例。 **补完（同日）**：启用链路三方面闭环——II 语言包相位：fallback 110KB 双解析改单次复用（fillMissing 只读 fallback 已证；构件单价 ≈4.6ms/次，每启用/reload 省 P），bundled zip 扫描甄别为**零条目无可优化面**（仓库 lang/ 无子目录翻译，多语言走 Crowdin）；III 装配序列甄别入册（tagManager 同步契约保留、initDatabase 必要前置、重负载项已异步）；text 新增 fallbackYamlParse 单价计量面，round31b 六 fork 同码零回归佐证。基准 47 用例。

15. **第十一轮（R23·浏览菜单库存快照批量化，2026-08-26，详见 [report/perf/2026-08-26-menu-inventory-snapshot.md](report/perf/2026-08-26-menu-inventory-snapshot.md)）**：菜单库存读取此前主线程必抛 IllegalStateException 被吞恒返 0（库存显示/有货过滤/库存排序三功能损坏）且每店一次阻塞往返×comparator 重查；改为整页一次「冲刷+IN(...) 批量查询」快照 Map（DatabaseHelper.queryInventoryCaches 新 API，MarketUtils 全链 Map 重载），并修复三处浏览页分页重叠缺陷（start 按 9 推进而每页 36 项）。基准 **-49.5%**（六 fork 零重叠）。测试 116 用例全绿、基准 38 用例。

14. **第十轮（R22·展示物区块进入路径去冗余，2026-08-26，详见 [report/perf/2026-08-26-display-resend-dedup.md](report/perf/2026-08-26-display-resend-dedup.md)）**：五个包工厂的「显式 destroy + sendFakeItem（内部又以 destroy 开头）」统一为 manager 单入口 resendChunkDisplays/withdrawChunkDisplays（桶锁外发包），每玩家每店少 1 包+1 事件（基准 **-21.1%**、六 fork 零重叠）；顺带修复「后端启用失败 → 插件整体崩溃」稳定性缺陷（setHandler 仅选已启用后端 + Throwable 级内存降级）并补齐 R21 实机验证（packetevents 2.13.1-SNAPSHOT 下 DISPLAY-CHECK PASSED）。测试 110 用例全绿、基准 37 用例。

13. **第九轮（R21·CHUNK_DATA 监听免全量解析 + 基准根因修复，2026-08-18，详见 [report/perf/2026-08-18-chunk-packet-peek.md](report/perf/2026-08-18-chunk-packet-peek.md)）**：展示物区块包监听从 WrapperPlayServerChunkData 全区块逐段解析（Netty 线程、每玩家每包几十~几百 KB）改为头部两 int 的 O(1) peek（三个 packetevents 工厂）；顺带破案修复基准 harness 的 Mockito 调用记录泄漏（R20"text 污染"真因，报告已更正归因），A/B 方差从 ±15~45% 收敛至 ±1%。测试 107 用例全绿、基准 36 用例。
12. **第八轮（R20·购买日志卸载，2026-08-18，详见 [report/perf/2026-08-18-purchase-log-offload.md](report/perf/2026-08-18-purchase-log-offload.md)）**：LogWatcher 懒队列 + `logEventLazy` + 监听器懒构造——每笔交易日志序列化移出主线程（**-97.9%**，180μs→3.7μs，5v5 fork 零交叉）；qs.log 内容/顺序/轮转不变。测试 104 用例全绿、基准 35 用例；八轮累计交易全链约 **-84%**。
11. **第七轮（R19·带参文本预解析，2026-08-18，详见 [report/perf/2026-08-18-text-preparse.md](report/perf/2026-08-18-text-preparse.md)）**：占位符模板预解析为哨兵孔组件树，参数组件直插（免序列化与全模板重解析），带参渲染 27μs→1.1μs（**-95.9%**）；三重守卫不满足即回退原路径，内置 282 模板全语料逐字节等价。测试 100 用例全绿。
10. **第六轮（R18·收据/通知单次 getItem，2026-08-17，详见 [report/perf/2026-08-17-receipt-item-dedup.md](report/perf/2026-08-17-receipt-item-dedup.md)）**：收据与店主通知各取一次商店物品并复用（printEnchantment 增传栈重载），每笔交易省 4~6 次克隆与 RETRIEVE 事件；actionBuy 再 -8.7%。会话五轮总览见该报告末尾（交易全链买 -82%、每笔 DB 写全批量化）。
9. **第五轮（R17·剩余每笔交易 DB 写批处理，2026-08-17，详见 [report/perf/2026-08-17-remaining-db-write-batching.md](report/perf/2026-08-17-remaining-db-write-batching.md)）**：通用 `BatchingQueue`（按键保末值去重）+ `DbWriteBatcher`——qs_external_cache 批量 REPLACE（每条摊销 **-67.7%**，同店连点 N 行→1 行）与 qs_messages 离线消息保序批插；读方链式冲刷保精确新鲜。每笔成功交易的 DB 写全部批量化（指标/缓存/消息各 1/窗口），主线程提交成本百 ns 级。测试 91 用例全绿、基准 34 用例。
8. **第四轮（R16·DB 写批处理与扫描未命中路径，2026-08-17，详见 [report/perf/2026-08-17-db-batch-and-matcher-optimization.md](report/perf/2026-08-17-db-batch-and-matcher-optimization.md)）**：匹配器 shopId 源查询身份缓存（扫描 -17.6%/-20.7%，交易全链再 -13~16%）+ 每笔交易指标插入 MetricBatcher 批处理（H2 每条摊销 -32.6%，主线程提交降为队列入队；`insertMetricRecords` 默认方法保持 API 兼容）。测试 87 用例全绿、基准 32 用例。三轮累计买/卖全链 -83.3%/-82.8%。
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
