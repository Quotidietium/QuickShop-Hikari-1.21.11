# QuickShop-Hikari 性能优化报告·第十一轮（浏览菜单库存快照批量化，2026-08-26）

> 基准程序与方法论同前（本轮起 38 用例 × 8 套件——新增 menu 套件 browseStockPipeline，
> 两侧同构运行：候选走 6 参快照重载（运行时探测），基线回退 5 参逐店路径）；同态 A/B，
> 基线 commit `24522ac68` = R22 闭合并提交文档后的 HEAD（本地仓库安装其 jar）。
> 本轮为**正确性 + 性能复合轮**：三条用户可见的功能缺陷与性能问题同源，一并修复。

## R23：市场浏览菜单库存缓存快照（commit 5bd79da2b..1fb9f3823）

### 问题（同源三连）

浏览/交易菜单的库存/空间读取（`MarketUtils.getStockFromCache`/`getSpaceFromCache`）
走 `ShopManager.queryShopInventoryCacheInDatabase(shop).join()`：

1. **功能缺陷（主线程必抛）**：`queryShopInventoryCacheInDatabase` 首行
   `Util.ensureThread(true)`（要求异步线程），而 TNML 菜单库全同步主线程调度
   （BukkitInventory→getScheduler().runTask；字节码核实）——主线程上每次读取必抛
   IllegalStateException 被吞恒返 0。后果：**浏览菜单库存/空间显示恒为 0**、
   「仅看有货」过滤只剩无限店、**按库存排序完全失效**；每次渲染还要付成千次异常
   构造（200 店 STOCK 排序 ≈ 2·n·log₂n ≈ 3,000+ 次抛接）。
2. **性能（逐店阻塞往返）**：即便在异步路径上，每店一次 `.join()`，且每次读前
   `flushInventoryCacheAsync()`（整批 REPLACE 冲刷）；STOCK 排序 comparator 的键提取器
   **每次比较重新查询**（`Comparator.comparingInt` 不缓存键）——n 店排序 ≈ n·log n 次查询。
3. **分页缺陷**：三处浏览页 `start = (page-1)*9` 而每页容量 `items = (rows-2)*9`（6 行
   = 36/页）——第 2 页从第 9 条开始，页间大面积重叠，且尾部条目（>54）不可达。

### 改动

- **批量 API**：`DatabaseHelper.queryInventoryCaches(Collection<Long>)`（默认实现按单店
  组合保 API 兼容；`SimpleDatabaseHelperV2` 以单条 `IN(...)` SELECT 真批量）。
- **快照管线**：`MarketUtils.loadInventoryCaches(shops)`——**一次**冲刷 + **一次**批量
  查询构建整页快照 Map；`stockOf`/`spaceOf`（无限店 -1、缺行/负值钳 0，与逐店路径回退
  语义逐条一致）；`filterByStock`/`sortShops`/`groupShopsByItem`/`filterGroupsByStock`/
  `processShops`/`processGroups`/`MarketItemGroup.calculateStatistics` 全部增加 Map 快照
  重载（旧签名保留，非菜单调用方不受影响）。
- **菜单接入**：ShopListPage/GroupedItemPage（整列表快照——过滤与 STOCK 排序需要全量
  库存）、browse MainPage（仅本页切片 ≤36 id 的快照，两遍循环）、trade MainPage（单店）。
- **分页修复**：`start = (page-1)*items`。

### 结果

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| menu/browseStockPipeline（200 店 stock-only 过滤+库存排序整页） | 6,929,705 | 3,502,071 | **-49.5%** | 基线三 fork 6.66~7.31ms vs 候选 3.40~3.54ms，**六 fork 零重叠**；两侧均以 stubOnly mock 计分发开销（基线=逐店 manager 调用+future join ×~3,200；候选=快照 Map 查找 ×~6,000）——**真实差距只会更大**：真 DB 上基线每读一次 SELECT+整批冲刷，mock 完成度 future 已把往返成本计为 0 |
| 其余 37 个共享用例 | — | — | **±11.5% 内（多数 ±4%）** | 零回归；db/externalCacheBatch100 +11.5% 与 db/insertShopChain -20.6% 为 db 套件已知 H2 方差（历轮同向摆动），与菜单路径无交集；displayResend +3.1% 在噪声内（R22 后代码未动） |

机器可读数据：`benchmark/results/round23-baseline/` 与 `round23/`（每侧 3 fork，对比脚本
`benchmark/results/compare-round23.py`）。

### 语义与红线

- **显示值修复而非变更**：旧代码在主线程上"显示 0"是异常吞掉的错误结果；新路径显示
  数据库缓存的真实库存（与异步路径语义一致）。无限店仍 -1→"Unlimited"，缺缓存行仍 0。
- 快照新鲜度保持 R17 契约：读前冲刷待写批量（一次，替代每读一次）。
- DatabaseHelperBatchCacheTest（H2 真批量 SQL：三行齐返/未知 id 缺席/空入参/单店与批量
  列语义一致）+ MarketUtilsSnapshotTest ×4（回退语义/有货过滤/库存排序免 DB）；全量
  116 用例绿。

## 结论

R23 把浏览菜单的库存读取从「主线程必抛异常恒返 0（功能损坏）+ 每店一次阻塞往返 ×
comparator 重查」修复并收敛为「整页一次批量查询的快照 Map」，同时修复三处浏览页的
分页重叠缺陷。十一轮累计：交易全链约 -84%，DB 写全批量化，文本/日志全缓存化，区块包
与展示物路径 O(1) 化，浏览菜单库存读快照化。
