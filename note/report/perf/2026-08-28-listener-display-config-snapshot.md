# QuickShop-Hikari 性能优化报告·第二十轮（监听与展示物路径热快照清扫，2026-08-28）

> 基准程序与方法论同前（47 用例 × 8 套件不变）；同态 A/B，基线 commit `78eaacfd0` =
> R19 报告补完后的 HEAD，候选 = R32 优化（`8f3633e6b`）+ 17 条快照语义用例（`7235f9ff2`）。
> 本轮延续 R30 的系统性清扫法，把残留的逐调用配置访问从**监听器与展示物**路径上清除，
> 并清掉两处脚手架热耗。

## R32：监听与展示物配置快照 + 杂项热路径（commit 8f3633e6b..7235f9ff2）

### 改动清单（语义逐点等价论证）

| 路径 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `AbstractDisplayItem.getNowUsing/createGuardItemStack/getDisplayLocation` | 每次 1-3 次 config 树行走 | 静态 volatile 快照 | `refreshConfigSnapshots()` 挂 `Util.initialize()`（reload 管理器注册的全局刷新钩子）；快照未初始化时 null/NaN 哨兵回落 fresh 读，保留启用前原语义 |
| `VirtualDisplayItemManager.allowEnchants/useItemName` | getter 每次 fresh 读 | 构造时快照 + 补挂 `Reloadable` | reload 实时性经新注册的 Reloadable 保持 |
| `ShopProtectionListener` protect.entity/protect.explode | 每事件读 | init 快照 | reloadModule→init 既有链 |
| `PlayerLockClickListener` shop.lock 门 | 每次有效右键读 | init+reload 接线快照 | 新增接线与既有监听器同构；门开时授权探针短路语义不变 |
| `BlockListener` disable-super-tool / allow-owner-break-shop-sign | 每事件读 | init 快照 | 同上 |
| `Util.parse` 价格快捷后缀 | 每次解析 `Pattern.compile` | 类常量 Pattern | 纯提升，无语义面 |
| `SimpleTextManager.postProcess` | stream 映射拷贝 | 索引 for 拷贝 | 每条消息发送少 stream 脚手架，输出逐字节一致 |

### 测试面

新增五类 17 用例：`BlockListenerSnapshotTest`(3)、`ShopProtectionListenerSnapshotTest`(5)、
`PlayerLockClickListenerTest`(3)、`AbstractDisplayItemSnapshotTest`(4)、
`VirtualDisplayItemManagerSnapshotTest`(2)——覆盖快照缺省值、翻转生效、reload 刷新、
门控短路语义。当前 HEAD 全部通过；既有用例回归无变化（行为无外部变化）。

### 基准结果（同态交替 A/B，4 fork/侧，B,C ×4 紧邻）

| 用例 | 基线中位 ns/op | 候选中位 ns/op | delta | 判读 |
|---|---|---|---|---|
| listener/inventoryCheck | 13,088 | 4,058 | **-69.0%** | **八 fork 零重叠**（base 12.7~13.6μs vs cand 3.5~4.9μs）——`getNowUsing()` 快照把 VIRTUALITEM 门从 config 行走变静态读，`Util.inventoryCheck` 的整扫描跳过门直接受益 |
| listener/chunkLoad | 27,229 | 20,666 | **-24.1%** | base 24.4~29.7μs vs cand 16.8~29.1μs，基本分离（顶部轻叠）——40 实体守卫检查循环内同一快照读收益 |
| 其余 45 共享用例 | — | — | ±20% 散布 | **46/47 用例 fork 区间重叠**：唯一零重叠即上行的 inventoryCheck。中位数散布源于测量窗外部重载（同机 MC 服务器 + 另项目基准进程，见方法学留档），交错同窗下判为零回归带 |

机器可读数据：`benchmark/results/round32-{baseline,}-fork{1,2,3,4}.json`
（对比脚本 `benchmark/results/compare-round32.py`）。

结构性收益（低于基准分辨率的部分）：每次守卫检查/展示物生成省 1-3 次
YamlConfiguration 树行走；玩家价格输入含快捷后缀时省一次 `Pattern.compile`；
每条文本消息发送省一次 stream 管道装配。

## 方法学留档：快照时代基准夹具的「晚置配置」陷阱

首轮 A/B 出现两个 20-200× 灾难级「回归」（listener/inventoryCheck +20579%、
listener/chunkLoad +1999%），三 fork 一致、交错同窗——不是噪声。根因不在产品代码：
**ListenerBench 在 `Env.plugin()`→`Util.initialize()` 之后才 `setConfig("shop.display-type", 2)`**。
旧代码 `getNowUsing()` 每次 fresh 读 config，基准时点读到 2（VIRTUALITEM）走快路径；
R32 起该值在 initialize 时烘进静态快照（读到的是未配置态），夹具后续改配置不再生效
——候选侧于是走进全扫描路径，且每采样期数百万次全扫描的分配压力进一步污染同 JVM
后续套件（db/text 同码用例整体 +20-60%）的堆/GC 状态，呈现「全面回归」假象。
生产环境无此相位：config.yml 先于 onEnable 存在，变更必经
`/qs reload`→ReloadManager→`Util.initialize()`→快照刷新。

**处置**：夹具修正——`Env.install()` 在 `Util.initialize()` 之前注入
`shop.display-type=2`（双侧同补丁，模拟真实服务器配置先于启用存在的相位）；
删除全部污染数据；4 fork/侧紧邻交替重测。留档结论：**快照化轮次的基准用例必须在
initialize 前布置配置**，晚置配置的用例测的是「快照未初始化」哨兵路径而非生产行为；
其分配放大效应还会跨套件污染同 JVM 的同码对照。

另：本次测量窗内本机持续运行 MC 服务器（约 1 个满核）与另一项目的基准进程，
整体水位偏高且突发性强；交替 4 fork + 区间重叠判读法在该条件下仍能分离出
真实收益（inventoryCheck）并给出零回归带，但中位数散布（同码用例 ±20%）较
安静窗口明显放大，跨轮次对比时应以 fork 区间重叠性而非中位数差为主要依据。

## 结论

R32 闭环了监听与展示物路径上最后的逐调用配置访问：两个可测收益
（inventoryCheck **-69.0%** 八 fork 零重叠、chunkLoad -24.1%）+
多项低于分辨率的结构性节省（每守卫检查/展示物生成 1-3 次树行走、
价格解析 Pattern 复用、消息发送 stream 脚手架），47 共享用例零回归。
第二十轮累计主线收益不变：交易全链约 -84%，DB 写批量化，文本/日志缓存化，
区块包 O(1)，菜单快照化，扫描类事件快路径化，木牌渲染/排程配置全快照化，
启动装载并行化，监听/展示物路径配置访问清零。
