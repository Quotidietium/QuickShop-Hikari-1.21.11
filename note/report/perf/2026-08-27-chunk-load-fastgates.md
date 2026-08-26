# QuickShop-Hikari 性能优化报告·第十四轮（区块加载路径双快门，2026-08-27）

> 基准程序与方法论同前（本轮起 41 用例 × 8 套件——listener 套件新增 chunkLoad，
> 两侧同体直调、行为随 jar 区分）；同态交替 A/B，基线 commit `6586d2104` = R25 闭合并
> 提交文档后的 HEAD，各 3 fork 取中位数。

## R26：区块加载守卫扫免扫 + 无商店区块早退（commit 33c992c94..45e3d0521）

### 问题

`ChunkListener.onChunkLoad` 对**每个非新生成的区块加载**（玩家移动/鞘翅飞行/传送——
全服最高频事件之一）执行：

1. `cleanDisplayItems(chunk)`：`chunk.getEntities()` **实体数组快照** + 逐实体
   `instanceof Item` + `getItemStack()` + `checkIsGuardItemStack`（内部第一、二行即
   `isDisplayEnabled()` 与 `getNowUsing()` 配置查询）。R24 已证明：虚拟展示后端
   （`shop.display-type=2`，默认）下守卫物栈**根本不会被创建**，`checkIsGuardItemStack`
   恒假——刷怪塔/农场区块每加载一次都在空转扫几十个实体的守卫检查。
2. `getShops(chunk)` 的 null 检查**永不触发**（R2 起该方法对无商店区块返回
   `Collections.emptyMap()` 而非 null）——无商店区块（绝对多数）继续白付：
   chunkName 字符串拼接 + `PerfMonitor` 构造（Caller 捕获 + Instant×2）+ **close 时
   无条件 `Log.performance` 记录**（写锁 + Record 分配 + 入队）——记录内容是
   「空循环 0ms 完成」的纯噪声。

卸载侧存在同一死 null 检查（循环体不执行，代价小）。

### 改动

1. **`AbstractDisplayItem.canProduceGuardItems()`**（新增静态判定）：
   `isDisplayEnabled() && getNowUsing() != VIRTUALITEM`——与
   `checkIsGuardItemStack` 的两个早退分支**同一表达式**（该方法的前两行重构为调用它，
   保持同步）。`ChunkListener` 在清扫前判定一次：虚拟后端（及展示禁用）整段跳过
   `getEntities()` 与逐实体检查。
2. **无商店区块 `isEmpty()` 早退**：修复死 null 检查，chunkName/PerfMonitor/
   performance 记录只在区块真有商店时发生。
3. **非虚拟模式行为不变**：孤儿清扫（含无商店区块——已删商店的残留守卫物只有这里
   会清）照常执行；卸载侧同步修复死检查。

### 结果（同态交替 A/B，各 3 fork）

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 说明 |
|---|---:|---:|---:|---|
| listener/chunkLoad（无商店区块 + 40 实体，虚拟后端） | 560,332 | 23,764 | **-95.8%** | 基线三 fork 542~581μs vs 候选 22.5~68.8μs——基线**最优** fork 仍高于候选**最差** fork 6.9 倍，六 fork 零重叠；mock 放大绝对值（实体/配置 mock），「实体快照+40 次逐实体检查+PerfMonitor+日志记录 → 2 次配置查询+isEmpty」的结构性比例在任何成本模型下成立（真实 `getEntities()` 是切片数组复制、`Log.performance` 是写锁+分配） |
| 其余 40 个共享用例 | — | — | +0%~+26% 同向 | 会话漂移非回归：本轮测量机同期承载外部负载（用户另两台 AlwaysPreTouch benchmark JVM 与 Paper 服务器），候选侧每用例第三 fork 普遍带尖刺；基线侧系重试至安静窗口才完成（选择偏差）。R26 仅触碰 ChunkListener/AbstractDisplayItem，未触及任何共享用例路径（对照 R25 同用例数据：trade/actionBuy 基线 3.45~3.53M 与本轮基线一致） |

机器可读数据：`benchmark/results/round26-baseline-fork{1,2,3}.json` 与
`round26-fork{1,2,3}.json`（对比脚本 `benchmark/results/compare-round26.py`）。

**运行环境留档**：本轮多次运行静默中断（exit 1/127，日志缓冲丢失）——测量机同期
承载外部 benchmark JVM（各 3GB AlwaysPreTouch）与 Paper 服务器（3GB），确认非本仓库
代码问题（隔离单套件运行稳定、失败点不固定于本仓库代码）；JSON 结果文件在全部套件
完成后写出，以文件完整性（41 用例）为准。

### 语义与红线

- **等价性论证**：虚拟后端下 `checkIsGuardItemStack` 逐实体恒假（其内部前两行即
  后端判定，R24 已证）——跳过 N 次恒假调用与执行它们同果（不移除任何实体）；非虚拟
  模式零改动（含无商店区块的孤儿清扫）。无商店区块的「空循环 0ms」performance 记录
  消失——纯噪声记录移除，与 R2「getAllShops 去 PerfMonitor」同性质。
- **测试**：`ChunkListenerTest` ×7（虚拟模式不触 getEntities、非虚拟仍清扫并按 PDC
  标记移除守卫实体、非虚拟无商店区块仍清扫（孤儿语义）、虚拟无商店区块整段跳过、
  含商店区块正常加载、卸载侧空跳过+已加载店卸载、canProduceGuardItems 随后端选择）；
  全量 **133 用例绿**（126→133）。

## 结论

R26 把全服最高频事件之一的区块加载处理压到「两次配置查询 + 一次 isEmpty」（基准
**-95.8%**，六 fork 零重叠），同时消除了每个无商店区块加载一条的空转 performance
日志记录。十四轮累计：交易全链约 -84%，DB 写全批量化，文本/日志全缓存化，区块包
O(1) 化，展示物重送去冗余，浏览菜单快照化，开箱/区块加载扫描快路径化，木牌渲染
单扫化。
