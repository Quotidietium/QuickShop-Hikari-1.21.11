# QuickShop-Hikari 性能优化报告·第十五轮（签名排程去重 O(1)，2026-08-27）

> 基准程序与方法论同前（本轮起 42 用例 × 8 套件——listener 套件新增 signScheduleDedup，
> 两侧同体直调、行为随 jar 区分）；同态交替 A/B，基线 commit `ef8e0ca5d` = R26 闭合并
> 提交文档后的 HEAD，各 3 fork 取中位数。本轮为紧凑轮：单一热点，改动 1 个文件。

## R27：SignUpdateWatcher 去重伴生集合（commit d3984c5ee..6f7174262）

### 问题

`SignUpdateWatcher.scheduleSignUpdate` 在**每次漏斗向商店箱移动物品**
（`shop.update-sign-when-inventory-moving` 默认开启）、每笔交易、区块加载时被调用；
其去重是**遍历整个待处理队列**逐一 `entry.shop().equals(shop)`。队列在 500ms 排空窗口
内积累当期全部待刷新商店——漏斗密集的服务器（分类器、农场）每秒数千次事件 ×
窗口内数百待处理商店 = **O(n²)/窗口** 的 ConcurrentLinkedQueue 遍历（缓存不友好的
链表行走）。

### 改动

伴生 `ConcurrentHashMap.newKeySet()`：`schedule()` 以 `set.add(shop)` 的失败作为
「已在队列」判定（O(1)），排空循环 poll 后从集合移除。**语义严格一致**：
`ContainerShop` 无 equals/hashCode 覆写（身份等价），keySet 的等价语义与原队列
`equals` 线性扫描完全相同——重复排程忽略、首胜 locale 保持、排空后可重排、
截止时限后遗留条目仍占用去重位。

### 结果（同态交替 A/B，各 3 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| listener/signScheduleDedup（500 待处理队列下的重复排程命中路径） | 2,252 | 32 | **-98.6%** | 基线三 fork 2.20~2.35μs vs 候选 32~53ns——基线**最优** fork 仍高于候选**最差** fork 41 倍，六 fork 零重叠；绝对值即真实量级（无 mock 放大：纯队列遍历 vs 哈希命中） |
| 其余 42 个共享用例 | — | — | 双向噪声带 | trade 主链 ±2%（actionBuy +2.3%/actionSell +1.2%）；39/42 用例 fork 区间重叠，3 个不重叠者方向混合（getShopById -28% 候选有利、generateParams +22%）＝会话噪声，无系统性回归 |

机器可读数据：`benchmark/results/round27-baseline-fork{1,2,3}.json` 与
`round27-fork{1,2,3}.json`（对比脚本 `benchmark/results/compare-round27.py`）。

### 语义与红线

- **等价性论证**：身份等价（无 equals/hashCode 覆写）使 keySet 判定与队列 `equals`
  扫描同义；排空侧 poll-then-remove 与「条目离队后可重排」一致；截止时限后遗留
  条目仍占去重位（与原「仍在队列中」一致）。`SignUpdateWatcherTest` 扩至 ×4
  （500 队列下重复排程零入队、排空后可重排），并补装标准静态环境——Shop 接口
  Mockito 插桩触发类初始化读取 `Bukkit.server`，隔离运行（`-Dtest=`）必需；
  全量 **135 用例绿**（133→135）。

## 结论

R27 把漏斗/交易触发的签名排程去重从队列线性扫描压到哈希命中（**-98.6%**，六 fork
零重叠，绝对值即真实量级）。十五轮累计：交易全链约 -84%，DB 写全批量化，文本/日志
全缓存化，区块包 O(1) 化，展示物重送去冗余，浏览菜单快照化，开箱/区块加载扫描
快路径化，木牌渲染单扫化，签名排程去重 O(1) 化。
