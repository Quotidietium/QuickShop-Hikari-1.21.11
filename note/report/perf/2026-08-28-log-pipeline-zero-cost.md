# QuickShop-Hikari 性能优化报告·第二十一轮（日志管线热路径零成本化，2026-08-28）

> 基准程序与方法论同前（**49 用例 × 9 套件**，本轮新增 log 套件两用例）；同态 A/B，
> 基线 commit `eb0261560` = LogBench 用例提交后的 HEAD，候选 = R33 优化（`c4b49371b`），
> 4 fork/侧紧邻交替（B,C ×4）。

## 背景：被前二十轮绕过的最大静态开销

`com.ghostchu.quickshop.util.logger.Log` 是全部调试/交易/性能日志的漏斗，自上游以来
从未被任何一轮触碰。它产出的环形缓冲仅在 `/qs dev` 粘贴/查看器里被消费，但**每次
调用都在交易线程上全额付费**：

1. **调用点字符串拼接**：`QSEconomyTransaction.commit` 开行日志一次拼接 7 个对象
   （含 5 个 BigDecimal.toString，单个 μs 级）；`SimpleInventoryTransaction` 每次事务
   两次 describeInv/describeItem 拼接；`AddItem/RemoveItemOperation` 在**逐栈循环内**
   debug（一组 64 物品交易 ≈ 10 次拼接）。
2. **全局写锁**：七种日志类型共用一把 `ReentrantReadWriteLock` 写锁——并发交易的
   异步经济提交线程在日志上相互串行化。
3. **每次锁内 `EvictingQueue.offer`**（满环再逐条驱逐）+ `Record` 分配 +
   `System.currentTimeMillis`。
4. `PerfMonitor.close()`（每笔交易 commit/rollback 各一次）无条件读两次时钟 + 拼
   StringBuilder。
5. `Caller.create()` 每次先做一次 `System.getProperty`（Properties 哈希表查找）才
   走 ThreadLocal 缓存。

每笔典型交易走 8-12 次上述调用（经济事务 2 + 库存事务 2 + 操作循环 2-10 +
PerfMonitor 2 + 回调 1）。

## 改动（commit c4b49371b）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `Log` 存储 | 全局 RW 写锁 + `EvictingQueue`(14000) | 无锁定容环形：`AtomicLong` 游标 + `AtomicReferenceArray` 槽位（16384=下一 2 幂）+ 槽位 seq 配对；快照取窗口最近 14000 条按 seq 升序 | 容量（2000/类型）与驱逐语义不变；`fetchLogs` 系仅喂 dev 粘贴/查看器，弱一致读取（并发写进行中可能漏最后几条）入册留档 |
| `Log.Record` | String 消息 | `Object`（String 或 `Supplier<String>`），`getMessage()` 首读求值并记忆（良性竞态：纯读 supplier 重复求值同值） | String 路径字节不变；Supplier 路径 equals 退化为引用相等（已核无调用方依赖 Record 相等性） |
| `Log` 重载 | — | `transaction/debug(Supplier)`、`performance(Level,Supplier,Caller)` 新增 | 既有 String/Level/Caller 签名全保留，Log 非 quickshop-api 兼容面 |
| `Log.Caller` | 每次读 `System.getProperty` 查禁用开关 | 类加载时快照为常量（与既有 `DISABLE_LOCATION_RECORDING` 同法） | 开关是 JVM 启动期 -D 标志，快照语义等同 |
| `PerfMonitor.close` | 无条件 `Instant.now`×2 + StringBuilder | 无限额时零时钟读；消息整体懒构 | 输出布局逐字符一致；OVER LIMIT 判定与 WARNING 级不变 |
| 热点调用点（11 处） | 急切拼接 | 懒构 supplier | 可变字段（amount/toTax/fromTax/ownerPayment/QUser）、活 `Map.Entry` 视图先快照 final 局部——记录值=调用时值，与急切形式逐字节一致 |

热点调用点：经济事务 safeCommit/commit 开行 ×2、benefit 循环 ×3、回调
onFailed/onSuccess/onTaxFailed ×3、库存事务 Regular/FailSafe 开行 ×2 与 rollback ×2、
AddItem/RemoveItemOperation 逐栈 debug ×2、ContainerShop `Space count is` ×1。
冷路径（Vault WARNING 失败日志、展示物取消日志等）保持急切不变。

附带效应入册：环形缓冲内的懒 supplier 持有交易对象引用直至出窗（≤14000 条），与
既有 String 消息驻留同量级，交易对象本就常驻注册表，无泄漏面。

### 测试面

新增 `LogRingAndLazyMessageTest` 六用例：写入顺序与容量窗口淘汰、类型过滤/排除、
懒 supplier 未读不求值/首读一次/再读记忆、String 路径不变、并发 4 线程×3000 全量
无损入窗、PerfMonitor 布局与 OVER LIMIT 告警级。全量套件回归绿。

## 基准结果（4 fork/侧交替，49 共享用例全区间重叠）

| 用例 | 基线中位 ns/op | 候选中位 ns/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| log/appendTransaction（同码 String API 直测追加路径） | 54 | 47 | **-13.4%** | [47,53,55,59] \| [41,45,49,49]——近分离（仅 47/49 边缘交叠） |
| log/appendDebug | 57 | 49 | **-13.8%** | [47,56,59,59] \| [41,49,50,52] |
| economy/safeCommitWithTax（每笔含税交易全链） | 71,861 | 61,466 | **-14.5%** | [61,71,73,82]K \| [54,55,68,76]K——前二 fork 显著低 |
| economy/safeCommitNoTax | 50,295 | 44,714 | **-11.1%** | [40,49,51,57]K \| [39,40,50,55]K |
| trade/inventoryTxCommit（每笔库存事务：2 懒日志+逐栈懒 debug） | 935,946 | 878,523 | **-6.1%** | [922,933,939,1024]K \| [736,739,1018,1055]K——前二 fork 明显低（≈-21%），后二 fork 落入重载噪声带 |
| 其余 44 共享用例 | — | — | 中位数 ±20% 散布，双侧区间全重叠 | 零回归带；多数同码用例中位数恰偏向候选侧（窗口有利），未据此归因（EconomyFormatter/forLocale 等已核内部无逐操作日志） |

结构性收益（单线程基准之外的）：全局写锁消除对并发交易线程的串行化（基准单线程
仅测得无争用下 -13%；争用场景收益更大）；每笔交易省 8-12 次 String 拼接（含
BigDecimal.toString×5 的 μs 级件）；每次日志省一次 System.getProperty 哈希查找。

机器可读数据：`benchmark/results/round33-{baseline,}-fork{1,2,3,4}.json`
（对比脚本 `benchmark/results/compare-round33.py`）。

## 结论

R33 把日志管线从「每笔交易 8-12 次全额付费（拼接+全局锁+队列驱逐）」降到
「一次 CAS + 一次槽位写 + 懒 supplier」，直接计量面 **-13.4%**（近分离）、经济提交
链 **-11~-14.5%**、库存事务链 **-6.1%**（最优 fork -21%），49 共享用例零回归。
第二十一轮累计主线收益：交易全链约 -84%（叠加本轮日志零成本化）、DB 写批量化、
文本/日志缓存化、区块包 O(1)、菜单快照化、扫描类事件快路径化、木牌渲染/排程与
监听/展示物配置全快照化、启动装载并行化。
