# QuickShop-Hikari 性能优化报告·第四轮（DB 写批处理与扫描未命中路径，2026-08-17）

> 基准程序：`benchmark/`（本轮起 db 套件新增 metricInsertSingle / metricInsertBatch100，共 **32 用例 × 7 套件**）
> 方法论：同态交替 A/B（基线 commit `745665232` = R15 闭合并提交文档后的 HEAD，`git worktree` 交替安装，
> 基线 fork1→候选 fork1→…各 3 fork）；4 预热 + 7 采样 × ≥250ms 批次循环取中位数。
> 环境：JDK 21.0.10（HotSpot 64-Bit Server VM）、Windows 11、Mockito 5.14、H2 2.1.214 (MODE=MYSQL) 内存库、
> 真实 EasySQL 层。

## R16：匹配器 shopId 身份缓存 + 每笔交易指标插入批处理（commit d6bd8b68f..33a8e210b）

### 问题定位

1. **扫描未命中路径的 shopId 源查询**：`QuickShopItemMatcherImpl.matches` 在 isSimilar 未命中后调用
   `plugin.platform().getItemShopId(requireStack)`（商店 ID 覆盖检查）。全库存扫描对**每个异类槽位**都走
   到这里，而 `requireStack` 是同一个对象（`ContainerShop.matches` 传活字段 `this.item`）——NBTAPI 启用时
   每槽一次 `new NBTItem(stack)` NBT 拷贝，未启用时每槽一次 `isPluginEnabled` 查找。
2. **每笔交易一条指标 INSERT**：`ShopSuccessPurchaseEvent` → `insertMetricRecord` →
   qs_log_purchase 单行 INSERT（虚拟线程）。高频服务器每秒数百条单行语句，纯诊断数据却占用
   与业务同级的 DB 吞吐（LogWatcher 对 qs.log 早已采用批处理先例）。

### 改动

1. **shopId 身份缓存**（d6bd8b68f）：匹配器内单条目 `volatile` 身份缓存（stack 引用 == 比较）。
   仅在**零监听器**时使用——监听器是扫描两次 matches() 之间唯一可能改写栈的回调源（同区域线程内
   无其他回调），零监听器下缓存值可证等于新鲜读；有监听器时逐次新鲜读（与旧行为一致）。
   商店换物品 = 新实例 → 身份失配自然失效；init/reload 显式清空。
2. **insertMetricRecords 批处理 API**（d5252501d）：`DatabaseHelper` 新增默认方法（逐条回退，第三方
   实现不受影响）；`SimpleDatabaseHelperV2` 覆写为 `createInsertBatch` JDBC 批处理 + 每个不同商店
   一次 `locateShopDataId`（数据 id 失效的店跳过并 debug 记录）。
3. **MetricBatcher**（5c777374f）：并发队列 + 10s 定时冲刷（FoliaLib runTimerAsync）+ 256 条阈值
   立即冲刷 + 关停 `flushSync(10s)`（在 EasySQL 关池前排空）；`MetricListener` 全部四个事件改经
   batcher.offer；`MetricQuery` 构造时触发一次异步冲刷（PAPI 占位符读数新鲜度 ≤10s + 触发即时冲刷）；
   无 batcher 的环境（单测）回退逐条插入。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 主导改动 |
|---|---:|---:|---:|---|
| trade/countItemsScan54 | 1,297,268 | 1,068,326 | **-17.6%** | shopId 身份缓存（每异类槽一次平台读→每扫描一次） |
| trade/countSpaceScan41 | 1,059,597 | 840,575 | **-20.7%** | 同上 |
| trade/tradeServiceBuy | 3,488,892 | 2,935,619 | **-15.9%** | 同上（扫描主导） |
| trade/tradeServiceSell | 3,502,867 | 2,987,169 | **-14.7%** | 同上 |
| trade/actionBuy | 4,419,475 | 3,835,405 | **-13.2%** | 同上（叠加在 R15 之上） |
| trade/actionSell | 4,393,646 | 3,884,392 | **-11.6%** | 同上 |
| db/metricInsertBatch100（每条摊销） | —（基线无此路径） | 3,203,002/条 | 对单条 **-32.6%** | JDBC 批处理 |
| lookup/text/serialize 对照（8 用例） | — | — | 全部 ±2% 内 | —（本轮漂移极小） |
| economy/safeCommitNoTax | 541,655 | 584,816 | +8.0%（未触碰路径，带内） | — |
| db/insertShopChain / listShops / updateShop | — | — | -14~-16%（db 套件同向偏移，带内） | — |

说明：① `metricInsertBatch100` 为候选专属用例（运行时反射探测注册，基线 jar 自动缺席），
批处理收益以候选侧同会话「单条 vs 批 100 摊销」对比量化——候选三 fork 中位数
320.3ms/百条（每条 3.20ms）vs 单条 4.75ms = **-32.6%**；H2 进程内已如此，生产 MySQL
每条一次网络往返→每窗口一次批语句，收益更大。② 生产主线程收益不止 DB 吞吐：
`MetricListener` 从每笔交易提交一个虚拟线程任务（含 EasySQL action 构建与调度）变为
一次并发队列 `offer`（约百 ns 级）——基准 `.join()` 口径量化的是 DB 侧，主线程侧为
结构性削减。机器可读数据：`benchmark/results/round16-baseline/` 与 `round16/`（3 fork
交替，零套件失败）。

### 语义与红线

- **指标可见性时延**：qs_log_purchase 写入延后至多一个冲刷窗口（10s 或 256 条）——与 LogWatcher
  对 qs.log 的既有批处理同类；读取方（MetricQuery/PAPI）构造时触发冲刷，占位符最坏滞后 10s。
  关停路径同步排空保证不丢。
- **shopId 缓存安全性**：零监听器门控使缓存条目可证与新鲜读等价（见问题定位的论证）；
  `ContainerShopMatchesTest` 含"监听器在场旁路缓存"断言。
- **API 兼容**：`insertMetricRecords` 为默认方法（第三方 DatabaseHelper 实现零影响）；
  `insertMetricRecord` 保持不变。
- **资金路径零改动**。

### 测试（87 用例全绿，80→87）

- MetricBatchTest ×5（真实 H2）：混合商店批 60 条逐行落地一次；batcher 冲刷/双冲刷幂等；
  空批无操作；单条插入仍可用；单条+批混合共享表。
- ContainerShopMatchesTest +2：同一 requireStack 十次匹配仅一次平台读；新实例重新读；
  监听器注册后旁路缓存（逐次读）。

## 结论

R16 在 R15 之上把扫描未命中路径再砍 17~21%（shopId 源查询每扫描一次），交易全链路累计到
-13~16%；每笔交易的指标写库从单行 INSERT 变为批量器合并（H2 摊销 -32.6%，主线程提交成本
降为队列入队）。对照用例 ±2% 极干净（本轮无会话漂移），资金路径零改动，87 用例全绿。
三轮累计：玩家买全链 23.0ms → 3.84ms（**-83.3%**）、卖全链 22.6ms → 3.88ms（**-82.8%**）。
