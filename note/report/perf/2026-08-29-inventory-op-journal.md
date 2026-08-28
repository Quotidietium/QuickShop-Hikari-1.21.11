# QuickShop-Hikari 性能优化报告·第二十二轮（库存操作层写入时变更日志化，2026-08-29）

> 基准程序与方法论同前（**49 用例 × 9 套件**）；同态 A/B，基线 commit `af395aacb`
> （R33 之后的工具提交，快照式库存操作），候选 = R34 优化（`b606fa32f`），
> 4 fork/侧紧邻交替（B,C ×4）。基线工件经独立 git worktree 构建安装，基准模块
> 双侧同码（含对基线惰性的 `getMaxStackSize=64` 夹具桩——已验证基线主构件无
> inventory 级 `getMaxStackSize()` 调用）。

## 背景：每笔交易都在为几乎从不发生的回滚全额付费

采样剖析（本轮为基准框架新增的默认关闭剖析模式，`-Dbenchmark.profile`）显示，
R33 之后交易库存链（`inventoryTxCommit`/`tradeServiceBuy`）的真实插件侧热点集中在：

1. **全量快照**：`AddItemOperation`/`RemoveItemOperation` 每次 `commit()` 第一步就是
   `createSnapshot()`——整背包数组 + **逐槽深克隆**（生产环境为 CraftItemStack 镜像
   的 NMS/NBT 复制）。每笔交易付两次（源背包 54 槽 + 目标背包 41 槽 ≈ 95 次深克隆），
   而交易成功时这些快照从不被使用，直接丢弃。
2. **removeItem 的整组写回放大**：`InventoryWrapper.removeItem` 默认实现基于迭代器，
   **每命中一个槽位**就要 `getStorageContents()`（整组数组复制）+ `setStorageContents()`
   （整背包逐槽 NMS 写回）——移除 4 个槽位 ≈ 4×54 次无谓复制。
3. **逐槽匹配器解析**：默认实现每个槽位调用一次 `QuickShopAPI.getInstance()`
   （services-manager 查找链）。
4. `addItem` 侧的盲区：`BukkitInventoryWrapper.addItem` 直接委托 Bukkit 批量 API，
   无法得知哪些槽位被触碰——这正是快照成为唯一回滚手段的原因。

## 改动（commit b606fa32f，含 v1→v2 一次重设计）

### v1（被否决的 diff 方案）与「mock 放大」教训

第一版 journal 在 begin/capture 两次 `getStorageContents()` 并逐槽读
`getAmount()` 做前后 diff。生产环境这只是字段读（纳秒级），但在基准计量面
（Mockito mock 每次方法调用都是微秒级、含栈捕获）引入了 ~216 次/交易的额外桩调用，
`inventoryTxCommit` 反而 **+41.5%**——首轮 A/B 作废。方法论留档点：
**凡逐槽 Bukkit-API 调用的新增循环，必须先过 mock 放大审查**（一次 mock 调用 ≈
1-2.7 µs，约为生产镜像调用的 1000 倍）。

### v2（入册方案）：写入时记账，全程零全槽扫描

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `InventoryWrapper` API | — | 新增 `supportsMutationJournal()`/`beginMutationJournal()` 默认方法 + 新类型 `MutationJournal` | 纯增量默认方法，第三方实现不受影响（默认 false → 继续走快照路径，二进制/源兼容）；默认 `removeItem` 的匹配器查找从逐槽提为逐调用 |
| journal 机制 | 每操作 commit 前全量快照 | 包装器**在写入槽位的瞬间**记录（槽位号、原栈引用、变更前数量）——仅被触槽位各付一次 `getAmount`，无任何全槽扫描；`capture()` 只是冻结并摘除；`restore()` 按 LIFO 重放（同槽多次触碰时逆序回放保证回到最初状态） | 回滚语义等价或更精确：快照回写全部槽位 vs 只回写被本操作触碰的槽位（同区域线程内无并发变更，两者终态一致；后者不会覆写无关槽位） |
| `BukkitInventoryWrapper.removeItem` | 默认实现：迭代器 + 每命中槽位整组 get/setStorageContents | 槽位级：一次 `getStorageContents()` 后按游标扫描，命中槽位只 `setItem` 写回；算法逐行镜像默认实现（匹配器驱动匹配、跨栈扫描游标延续、剩余量 map 携带被修改的入参栈） | 终态逐槽等价；匹配器语义不变；每调用一次 `QuickShop.getInstance().getItemMatcher()`（原为逐槽 services-manager 查找） |
| `BukkitInventoryWrapper.addItem` | 委托 Bukkit 批量 `Inventory.addItem`（黑盒） | 槽位级实现，逐行镜像 CraftBukkit 算法（上游源核对）：先按槽序合并同类半栈（半栈 `getMaxStackSize` 封顶、**镜像原地 setAmount** 与上游一致），再按槽序填空槽（`inventory.getMaxStackSize` 封顶、超限分割放置），剩余 map 语义不变；非正 maxStackSize 按香草 64 防御（上游对 max=0 会退化成零放置死循环） | 布局/剩余量与上游一致；isSimilar 语义不变（不含匹配器，同原批量调用）；storage 下标与 `setItem` 全下标前缀对齐（玩家背包 0-35） |
| 两个操作类 | `createSnapshot` → 变更 → `restoreSnapshot` | `beginMutationJournal` → 变更（finally 中 `capture()`，异常路径同样冻结可回滚）→ `restore()`；不支持 journal 的包装器原样走快照路径 | 对第三方的 `InventoryTransaction` 行为不变；测试 `SimpleInventoryTransactionTest` 零改动通过 |

附带效应入册：journal 在操作对象存活期间持有被触槽位的镜像引用（≤ 数个/操作），
原先快照持有整背包克隆（95/交易）——驻留面缩小。

### 测试面

新增 `InventoryMutationJournalTest` 九用例：槽位级移除等价性（与接口默认算法同场景
对照）、移除剩余量、写入时记账只记被触槽位并精确恢复、addItem 镜像上游布局
（合并+填充+封顶分割+剩余量）、两操作 commit-rollback 全链、非日志包装器快照回退。
全量套件 176 用例通过（含 R33 前所有轮次用例零改动）。

## 基准结果（4 fork/侧交替，49 共享用例 48 区间重叠零回归）

| 用例 | 基线中位 ns/op | 候选中位 ns/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| trade/inventoryTxCommit（每笔库存事务：2 操作 commit 含快照/journal + 逐栈日志） | 987,893 | 628,599 | **-36.4%** | [941,983,992,1041]K \| [484,621,637,656]K——**八 fork 完全分离（唯一零重叠用例）** |
| trade/tradeServiceSell（每笔完整卖出服务链：校验+预览+事务+木牌刷新） | 3,236,575 | 2,919,731 | **-9.8%** | [3026,3097,3376,3454]K \| [2234,2642,3198,3315]K——前二 fork 显著低 |
| trade/tradeServiceBuy | 3,087,014 | 2,761,523 | **-10.5%** | [2755,3042,3132,3391]K \| [2334,2704,2819,2834]K——前二 fork 显著低 |
| trade/actionSell（玩家侧完整动作链） | 4,022,463 | 3,493,231 | **-13.2%** | [3710,4007,4038,4091]K \| [2682,3248,3738,4141]K——前二 fork 显著低 |
| trade/actionBuy | 3,786,642 | 3,518,906 | **-7.1%** | [3568,3635,3938,3974]K \| [2697,3479,3559,4033]K——前二 fork 显著低 |
| 其余 44 共享用例 | — | — | 中位数 ±20% 散布，区间全重叠 | 零回归带 |

两点如实入册：

- **同码用例窗口偏移**：db 套件 6 用例（同码双侧）中位数普遍偏候选 -4~-12%，
  `text/fallbackYamlParse` -13.7%——与 R33 相同的「候选后跑窗口有利」现象，未据此归因。
- **startup/shopLoadChain +18.4%（区间重叠）**：候选含一个 9.36M 离群 fork（其余
  5.3-6.2M 与基线 4.9-6.0M 交错）。该链是纯 DB 装载（`ShopLoader.loadShops`），
  不经过任何库存操作代码，机制上与本轮改动无关；此用例跨会话漂移极大
  （R33 期中位 9.2M → 本会话两侧 5-6M），判为窗口噪声。

结构性收益（基准之外）：生产环境每笔交易省 2 次全量深克隆快照（≈95 次 NBT 复制）
与 removeItem 的整组写回放大（每命中槽位 54 次镜像分配 + 54 次 NMS 写回）；
基准的 mock 克隆是自反桩（低估真实克隆成本），**实机收益大于上述数字**。
基准 mock 下计得的 -36.4% 主要来自桩调用次数削减（约 110 次/操作 → 约 30 次/操作）。

机器可读数据：`benchmark/results/round34-{baseline,}-fork{1,2,3,4}.json`
（对比脚本 `benchmark/results/compare-round34.py`，驱动脚本 `run-round34.sh`）。

## 结论

R34 把库存操作层从「每操作全量快照 + 盲区批量写回」重构为「写入时槽位级记账 +
槽位级镜像算法」，直接计量面 **-36.4%**（八 fork 完全分离）、完整交易服务链
**-10%**、玩家动作链 **-7~-13%**，49 共享用例零回归。方法论新增两项留档：
基准剖析模式（`-Dbenchmark.profile`）与 **mock 放大审查**（v1 教训）。
第二十二轮累计主线收益：交易全链约 -85%（叠加本轮库存操作层日志化）。
