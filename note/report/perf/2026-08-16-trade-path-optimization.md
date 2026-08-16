# QuickShop-Hikari 性能优化报告·第二轮（交易热路径，2026-08-16）

> 基准程序：`benchmark/`（独立 Maven 模块，本轮起 **26 用例 × 6 套件**——新增 trade 套件 6 用例）
> 方法论：4 预热 + 7 采样 × ≥250ms 批次循环，取中位数；每轮 3 个独立 JVM fork 取中位数；
> **最终结论采用同态交替 A/B**：基线 commit `0184b1862`（本轮七轮优化闭合并提交文档后的 HEAD）
> 经 `git worktree` 重建，与最终代码在同一天相邻时段交替安装测量（基线 fork1→最终 fork1→基线 fork2→
> …各 3 fork），排除 ±15~45% 会话级机器漂移。
> 环境：JDK 21.0.10（HotSpot 64-Bit Server VM）、Windows 11、Mockito 5.14.2（subclass mock maker）、
> 模拟 CraftInventory 语义（`getStorageContents()` 每次调用返回新数组拷贝）。

## 结论速览

**本轮聚焦交易热路径（此前七轮未覆盖的区域），五轮优化（R8–R12）后：**

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 主导轮次 |
|---|---:|---:|---:|---|
| trade/tradeServiceBuy（买全链路） | 23,035,582 | 8,598,530 | **-62.7%** | R8+R9+R10+R11 |
| trade/tradeServiceSell（卖全链路） | 22,599,500 | 8,758,841 | **-61.2%** | R8+R9+R10+R12 |
| trade/countItemsScan54（54 格扫描） | 3,419,885 | 1,903,033 | **-44.4%** | R8+R9 |
| trade/countSpaceScan41（41 格扫描） | 2,744,783 | 1,687,875 | **-38.5%** | R8+R9 |
| trade/inventoryTxCommit（库存事务） | 1,221,680 | 1,168,229 | -4.4%（弱信噪） | R8 |
| trade/iterateOnly54（纯迭代对照） | 187,742 | 3,502 | **-98.1%** | R9 |
| 对照组（lookup/text/db/economy 19 用例） | — | — | -7.7% ~ +11.4%，全部噪声带内 | — |

三轮交替的每个最终 fork 都快于每个基线 fork（交易用例分离干净，非漂移）。
对照组无回归：`economy/safeCommit ±5%`、`lookup/* ±11%`（miss 用例为已知高方差项）、
`text/* ±4%`、`db/* -1.6~-16%`。

## 各轮优化内容与归因

| 轮次 | commit | 切入点 | 关键改动 | 归因 |
|---|---|---|---|---|
| R8 | 90a07a72e | 交易热路径冗余分配 | ① `ContainerShop.matches` 内建匹配器免克隆直通（匹配器内部本就克隆归一；第三方匹配器保留防御拷贝）② `QuickShopItemMatcherImpl` 无监听器时跳过 `ShopItemMatchEvent` 构造（免 2 次物品克隆）③ `SimpleInventoryTransaction` 日志改廉价描述符（原来每次 commit 构造 2 次 Gson JSON toString + 1 次 YamlConfiguration 物品序列化；dev 模式保留全量）④ Add/Remove 操作逐栈日志不再拼接 ItemStack.toString | 扫描 -33%/-42%（逐轮 A/B）；事务提交 -13%；TradeLoadSmokeTest 17.1s→14.9s |
| R9 | 2f74c964a | 库存迭代算法 | `InventoryWrapperIterator.ofBukkitInventory` 创建时单次快照，`next()` 不再每次 `getStorageContents()` 全数组复制（O(n²)→O(n)）；`setCurrent` 写回仍取实时数组（写回语义不变） | 纯迭代 **-98.1%**（55 次数组复制→1 次）；扫描再 -13~17%；removeItem 默认实现同受益 |
| R10 | d928b2ab1 | 交易后木牌渲染 | 交易成功后的 `setSignText`（4 行布局渲染 + 4 向告示牌方块状态写入）改走既有 `SignUpdateWatcher` 批处理（hop路径同款）：每周期（10 tick）至多刷新一次，条目携带交易者 locale（与原立即渲染语言一致）；新增 `shop.immediate-trade-sign-updates` 配置可回退 | 交易连点下渲染次数 N→⌈N/周期⌉（合并语义由 TradeSignSchedulingTest 证明）；mock A/B -3.4%（文本管线/方块写入在基准中被打桩，实际收益被低估） |
| R11 | ee56e3b39 | 买路径冗余重解析 | ① `executeBuyFromShop` 单次 symbolLink 定位，preview 与提交共享（私有重载，公开 API 不变）② 新增 `ShopMeta#getItemUnitSize` 默认方法：ContainerShop 在零监听器时直接读字段，跳过 getItem() 的克隆+RETRIEVE 事件；SimpleTradeService/Util 的 7 处只读站点改用 | 每笔交易少 1 次定位（Base64 反序列化+BlockState 获取+PerfMonitor 记录）+ 6~7 次克隆事件 |
| R12 | 49ca8f4e9 | 卖路径对称去重 | `ShopInventory#getRemainingSpace/Stock` 增加带已定位包装器的重载（默认方法回退原语义）；`executeSellToShop` 单次定位共享给空间预检与提交；顺带修复原 `getRemainingSpace` 空检查与计数各定位一次的问题 | 卖路径定位 3 次→1 次；基准新增 trade/tradeServiceSell 用例使卖方向可量化 |
| R14 | 03a21f743 | 点击路径方块访问（非商店点击，全服最高频事件之一） | `PlayerListener.searchShop`：① 双箱分支前置箱子族材料预检（仅 CHEST/TRAPPED_CHEST 可能双箱），普通方块点击不再执行 `getBlockData()` ② 容器分类 `getState()`→`getState(false)`（Paper 免快照，分类结果同）③ `shop.ignore-cancelled-interact-event` 配置读取改构造器快照 + reloadModule 刷新（init 模式，与 BlockListener 既有约定一致） | 每次普通方块点击严格少一次方块数据获取与一次全量快照 BlockState 构造、预取消点击少一次 BoostedYAML 导航；mock A/B 增量低于噪声底（交替测量 cycle2 持平、无回归），结构证明由 PlayerListenerSearchShopTest（verify never getBlockData）承担 |

基准配套：91495d014 新增 trade 套件（迭代对照/扫描/事务/买卖全链路）。

## 方法论要点与诚实性声明

1. **Mockito 调用开销主导 mock 基准的绝对值**：剖析发现 Mockito 5.14 的 `LocationImpl` 在**每次 mock
   调用**时饿性 `StackWalker.walk`（约 5~10μs/次）。因此 mock 基准的绝对值不能外推到生产
   （生产中这些是真实 API 调用，纳秒级）；A/B 语义仍成立——优化消除的是**调用次数**，两侧 mock
   开销同构。`trade/iterateOnly54` 与买卖用例的对照组模式用于漂移甄别。
2. **R10 的 mock 低估**：木牌渲染的 MiniMessage 文本管线（约 8 次带参渲染/次，参见 text 套件
   ≈21.6μs/次）与告示牌方块写入在基准中被打桩。mock A/B 仅显示结构层（调度、folia 派发、
   布局机制）-3.4%；实际生产收益 = 结构层 + 文本管线 + 方块写入，且连点场景按合并倍数放大。
3. **R12 归因受漂移限制**：逐轮 A/B 中买用例（代码未变）同会话"改善"23%，说明小结构变化的
   逐轮增量低于会话漂移分辨率；卖路径收益以代码路径论证（定位 3→1，严格更少工作）+ 最终
   同态 A/B 兜底。
4. **对照用例全覆盖**：最终 A/B 同时运行全部 6 套件 26 用例，前七轮优化的所有路径
   （查找表/DB 缓存/文本缓存/事件快路径/QUser 驻留/runtime UUID 索引）均无回归。

## 安全性/稳定性/兼容性红线核查

- **API 兼容**：`ShopMeta#getItemUnitSize`、`ShopInventory#getRemainingSpace/Stock(InventoryWrapper)`
  均为**新增默认方法**（default 实现 = 原行为），第三方 Shop 实现不受影响；
  `AbstractQSEvent.hasListeners()` 由 private 改 public（加文档）；`SignUpdateWatcher` 新增重载，
  旧 `scheduleSignUpdate(Shop)` 行为不变（null locale 回退 owner 语言）。
- **第三方匹配器防御保留**：`ContainerShop.matches` 对非内建 ItemMatcher（ServiceInjector 注入）
  保持原防御性克隆 + 数量归一（ContainerShopMatchesTest 含敌意变异测试）。
- **零监听器快路径的可证性**：事件构造守卫沿用 R5 论证——全部 QuickShop 事件共享一个 HandlerList，
  零监听器时派发结果可证等价（不构造即不可观察）。
- **迭代器快照语义**：`next()` 改读迭代创建时的单次快照；所有内部消费者（countItems/countSpace 只读；
  forEach/addItem/removeItem/changeItem 经 `setCurrent` 写回实时数组）行为不变——`setCurrent` 只替换
  已消费槽位且重新取实时数组。包装器文档明确单线程使用。InventoryWrapperIteratorTest 断言
  「单次复制 + 写回正确性」。
- **木牌批处理的时序权衡**：交易后告示牌刷新延后至多一个 watcher 周期（≤0.5s）——与漏斗路径
  （BlockListener 既有行为）一致；locale 语义保留（交易者语言）；`shop.immediate-trade-sign-updates:
  true` 可完全恢复逐笔渲染。配置已写入 config.yml 带注释。
- **资金路径零改动**：经济事务金额计算、补偿回滚、税逻辑未触碰；库存在事务内的快照回滚机制
  （Remove/AddItemOperation 的 createSnapshot）完整保留。
- **并发**：SignUpdateWatcher 队列仍为 ConcurrentLinkedQueue（区域线程入队/异步定时线程出队的
  既有设计）；去重扫描 O(队列长度) 与原 `contains` 同阶。
- **测试**：quickshop-bukkit **70 用例全绿**（56→70：ContainerShopMatchesTest×4、
  InventoryWrapperIteratorTest×5、TradeSignSchedulingTest×2、PlayerListenerSearchShopTest×3；
  SignUpdateWatcherTest 适配新队列结构）。
- **R14 兼容性说明**：`searchShop` 的容器分类从 `getState()` 改为 `getState(false)`——两者对
  `instanceof Container` 的结果一致（Paper 官方语义：false 仅跳过快照拷贝，本项目 canBeShop 等处
  已是同款用法）；双箱预检的等价性依据是双箱 BlockData 仅存在于 CHEST/TRAPPED_CHEST 材料；
  配置快照经 reloadModule 刷新，与 BlockListener 既有 init 模式一致。

## 已知未优化项（评估后放弃，含本轮新增）

- **带参文本参数序列化快速路径（R13 尝试，实测无收益后回退）**：曾实现「纯文本参数跳过
  MiniMessage.serialize」（未加样式、无子组件、内容无 `<>{}\\` 特殊字符的 TextComponent 直接返回
  内容，语料等价性已由测试证明），但 forLocaleWithArgs 基准 23.8μs→24.3μs 无变化——
  **该路径的成本在填充后字符串的全模板 MiniMessage 重新解析（deserialize），参数序列化只占
  单个百分位**。真正消除需把模板预解析为带占位孔的组件树（等于重写 MiniMessage 解析语义，
  含 color_scheme 自定义解析器），红线不可接受。结论与前轮一致：带参渲染不可缓存。
- **回滚快照（每笔交易 ~95 次物品克隆）**：双操作（移出箱/移入包）各拍全量快照是补偿式回滚的
  保险；预检已通过后失败概率极低但非零（同 tick 插件干预）。削弱即触碰资金/物品安全红线，放弃。
- **actionSelling 预扫描**：`stock` 在成功路径被 notifyBought 消费、失败路径被错误消息消费，
  无法整体惰性化；playerSpace 仅消息使用但量级（R9 后一次 41 格扫描）已不重要。
- **Log 环形缓冲锁 / PerfMonitor 每笔记录**：为 /qs paste 诊断数据，改动会改变诊断行为，
  微秒级收益不值红线风险。
- **CountableInventoryWrapper 在 BukkitInventoryWrapper 的实现**：现有 API 两分支语义不一致
  （raw vs units），实现会改变计数语义——正确性问题，放弃。
- 前七轮放弃项（forLocaleWithArgs 渲染契约、insertShopChain DAO 合并、BigDecimal 代数化简等）
  理由不变，仍放弃。

## 复现步骤

```bash
# 1. 安装待测版本（基线用 git worktree 检出 0184b1862 后同样操作）
JAVA_HOME=<jdk21> mvn -T 1.5C -P github -DskipTests -pl quickshop-bukkit -am install
# 2. 运行基准（3 fork；基线与最终交替安装交替测量）
cd benchmark && for i in 1 2 3; do
  JAVA_HOME=<jdk21> mvn -q compile exec:exec -Dbenchmark.label=<标签> -Dbenchmark.fork=$i; done
# 3. 对比（结果 JSON 放入各自目录）
"F:/Java/21/bin/java" -cp "target/classes;$(cat target/cp.txt)" \
  com.ghostchu.quickshop.benchmark.Compare results/<基线目录> results/<候选目录>
```

机器可读数据：`benchmark/results/`（round8-baseline/round8、round9、round10-baseline/round10、
round11、round12-baseline/round12 为逐轮演进；trade-final-baseline/ 与 trade-final/ 为同态交替 A/B）。
