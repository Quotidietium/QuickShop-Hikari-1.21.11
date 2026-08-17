# QuickShop-Hikari 性能优化报告·第三轮（action 层全路径，2026-08-17）

> 基准程序：`benchmark/`（独立 Maven 模块，本轮起 **30 用例 × 7 套件**——trade 套件新增 actionBuy/actionSell 两用例，
> 首次将 SimpleShopManager 的玩家侧完整交易链纳入量化）
> 方法论：4 预热 + 7 采样 × ≥250ms 批次循环，取中位数；每侧 3 个独立 JVM fork 取中位数；
> **同态交替 A/B**：基线 commit `097f36619`（第二轮闭合后的 HEAD）经 `git worktree` 交替安装，
> 与最终代码在相邻时段交替测量（基线 fork1→候选 fork1→基线 fork2→…），排除 ±15~45% 会话级漂移。
> 环境：JDK 21.0.10（HotSpot 64-Bit Server VM）、Windows 11、Mockito 5.14（subclass mock maker）、
> 模拟 CraftInventory 语义（`getStorageContents()` 每次返回新数组拷贝）。

## R15：交易 action 层重复扫描消除 + 匹配器类型门（commit 479ca5d2f..bdb2e4226）

### 问题定位

第二轮（R8–R12）把 SimpleTradeService 内部的库存定位压到每笔 1 次，但玩家侧入口
`SimpleShopManager.actionSelling`/`actionBuying` 在调用交易服务**之前**还各自做独立的
全量库存扫描，且这些值只用于失败消息参数与"售罄/装满"店主通知：

| 方向 | 外层冗余扫描 | 用途 | 与预检的关系 |
|---|---|---|---|
| actionSelling（买） | `getRemainingStock()`（箱 54 格） | STOCK_TOO_LOW 消息参数；售罄通知 `stock == amount` | 预检 `countItems` 已测同一值 |
| actionSelling（买） | `Util.countSpace`（背包 41 格） | INVENTORY_FULL 消息参数 | 预检 `countSpace` 已测同一值 |
| actionBuying（卖） | `getRemainingSpace()`（箱 54 格） | SHOP_NO_SPACE 消息参数；装满通知 `space == amount` | 预检 `getRemainingSpace` 已测同一值 |
| actionBuying（卖） | 失败分支 `countItems`（背包） | ITEM_NOT_ENOUGH 消息参数 | 预检已测 |

即：买方向每笔交易箱扫描 2 次 + 背包扫描 2 次；卖方向箱扫描 2 次。另外发现 R10 遗漏点：
`actionBuying` 成功后仍直接 `setSignText` 立即渲染木牌，而交易服务内部已把木牌刷新
排入批处理队列——卖方向每笔交易木牌渲染做了两次（1 次批处理 + 1 次立即）。

### 改动

1. **API 附加观测**（479ca5d2f）：`TradeResult` 新增第 11 个组件
   `TradeObservation(chestStock, chestSpace, traderStock, traderSpace)`——预检已经测得的
   四个值（空 = 未测）。旧 10 参构造器保留并退化为空观测，第三方编译产物二进制兼容。
2. **预检观测透出**（adfe997af）：私有 preview 返回内部载体 `PreviewOutcome(preview, observation)`，
   成功与失败 TradeResult 均携带观测量；买方向箱计数处补发 `ShopInventoryCalculateEvent`
   （与旧外层扫描同参），InternalListener 的外部库存缓存（qs_external_cache）保持
   每笔交易一次的更新节奏不变。
3. **action 层去重**（0f2cc72eb）：删除两处 eager 扫描；成功路径读观测（内建服务恒有值；
   第三方 TradeService 空观测时回退一次真实扫描，行为与历史完全一致）；失败分支读观测
   （预检失败后库存未被动过，值与新鲜扫描恒等）；移除 actionBuying 冗余木牌立即渲染。
   无限商店的 `-1→MAX_VALUE` 语义保留（观测为空 ⇔ 旧 -1 映射）。
4. **匹配器类型门**（b0081864a）：`QuickShopItemMatcherImpl` 未命中路径在归一克隆之前
   先比对材料——材料不同在任何 workType 下都不可能匹配（isSimilar/equals/元数据比较都
   要求同型），类型门后置的两次 `clone()` 是纯浪费。事件派发与商店 ID 覆盖仍先行
   （监听器仍可跨类型强制匹配，ContainerShopMatchesTest 含该断言）。
   全库存扫描的每个异类槽位省 2 次克隆（生产为真实 CraftItemStack.clone 的 NMS 拷贝）。

### 结果（同态交替 A/B，3 fork 中位数）

**本轮测量期间存在会话级机器干扰**（后期套件 economy/text/db 在两侧的 fork1/2 上被污染，
listShops 等用例出现 16~100 倍偏移；fork3 两侧干净，另补跑 fork4/5 验证）。trade 套件
（套件顺序第 2 位）在**全部 6 个 fork 中均稳定**：基线 actionBuy 16.08/16.62/17.13ms、
候选 4.49/4.39/5.23ms——每个候选 fork 都快于每个基线 fork，非漂移。

| 基准用例 | 基线 ns/op | 最终 ns/op | 变化 | 主导改动 |
|---|---:|---:|---:|---|
| trade/actionBuy（玩家买全链） | 16,616,625 | 4,487,577 | **-73.0%** | 扫描去重 + 类型门 |
| trade/actionSell（玩家卖全链） | 13,929,568 | 4,631,906 | **-66.7%** | 扫描去重 + 冗余木牌渲染移除 + 类型门 |
| trade/tradeServiceBuy | 7,977,022 | 3,565,927 | **-55.3%** | 类型门（每 miss 槽 2 克隆） |
| trade/tradeServiceSell | 7,802,036 | 3,574,083 | **-54.2%** | 同上 |
| trade/countItemsScan54 | 1,731,241 | 1,257,689 | **-27.4%** | 类型门 |
| trade/countSpaceScan41 | 1,490,813 | 1,022,561 | **-31.4%** | 类型门 |
| trade/inventoryTxCommit | 952,570 | 977,048 | +2.6%（噪声内） | — |
| trade/iterateOnly54 | 3,167 | 3,071 | -3.1%（噪声内） | — |

<!-- A/B_CONTROLS -->

**对照套件结论**（lookup / listener / serialize / economy / text / db）：全部未触碰路径的变化
处于本机已知 **±15~45% 会话漂移带**内，且双向漂移互证（如 text/forLocaleNoArgs +33.6% 与
lookup/getShopByLocation-miss -13.4% 并存、economy/safeCommitWithTax 在两组聚合中分别为
+4.3%/-13.3%），无任何结构性回归证据。R15 未触碰资金路径（经济事务金额计算/补偿回滚/税逻辑
零改动），库存在事务内的快照回滚机制完整保留。

**机器可读数据**：`benchmark/results/round15-baseline/` 与 `round15/`（fork1-5 交替测量，
fork1/2 的后期套件被会话干扰污染——两侧同污，fork3 干净，fork4/5 复测；trade 套件全 fork
稳定）；`round15-baseline-clean-fork345/` 与 `round15-clean-fork345/` 为对称过滤 fork3/4/5
的对照聚合。污染甄别依据：db/listShops 在污染 fork 中 27~171ms vs 干净 fork 1.6~1.8ms。

对照套件（lookup/listener/serialize/economy/text/db）结论见上注与 <!-- R15_CONCLUSION -->。
动作路径每笔交易的库存扫描次数变化：买方向 箱 2→1、背包 2→1；卖方向 箱 2→1（外加
木牌渲染 2→1）。mock 基准的绝对值由 Mockito 调用开销主导（每次 mock 调用含
StackWalker 走栈约 5~10μs），消除的每次扫描包含 54~41 次 mock 迭代调用；生产环境中
对应的是真实 54/41 格匹配扫描（每格一次匹配器比较）与真实 CraftItemStack.clone。

### 语义等价性论证

- **失败消息参数**：预检失败时库存未被修改（预检只读），观测量 ≡ 新鲜扫描值；
  第三方 TradeService 无观测时回退真实扫描，与旧代码逐字节一致。
- **售罄/装满通知**：`stock == amount` 判定用的预检值与旧 eager 扫描值语义相同
  （唯一差异：测量时机从 ShopPurchaseEvent 之前移到预检内部，同一 tick 内更接近实际过户，
  只会更准确）。无限商店观测为空 → 回退 `getRemainingStock()` 直接返回 -1（无扫描成本），
  `-1 == amount` 恒假，与旧 `MAX_VALUE == amount` 恒假一致。
- **事件节奏**：买方向旧代码每笔交易发 1 次 ShopInventoryCalculateEvent（外层扫描处），
  新代码在预检计数处发 1 次同参事件；卖方向旧代码发 2 次（外层 + 预检），新代码 1 次
  （预检处）——InternalListener 的 SpaceCache 本就按值去重，DB 写节奏不变。
- **木牌**：卖方向从"批处理 + 立即各一次"变为"批处理一次"（`shop.immediate-trade-sign-updates:
  true` 时为"立即一次"，此前是"立即两次"）；买方向不变。语言均为交易者语言。
- **匹配器**：类型门仅在"事件未匹配 + 商店 ID 未匹配"之后、克隆之前插入同型检查；
  同型路径完全不变；跨型匹配只能来自事件监听器（门在其后）——现有敌意测试覆盖。

### 测试（80 用例全绿，70→80）

- TradeObservationTest ×8：买/卖成功与四类失败（STOCK_TOO_LOW / INVENTORY_FULL /
  ITEM_NOT_ENOUGH / 售罄边界）的观测量；无限商店空观测；旧构造器空观测；
  chestStockOrMax/chestSpaceOrMax 便捷访问器。
- ContainerShopMatchesTest +2：类型不匹配零克隆断言（verify never clone）；
  同型不相似仍走完整比较。
- 既有 70 用例零改动全绿（含资金回归、交易冒烟 18 万笔、木牌调度断言）。

## 结论

R15 将玩家侧交易全链路压至基线的 27%~33%（actionBuy -73.0%、actionSell -66.7%，全部
6 个候选/基线 fork 无交叉，非漂移），叠加此前两轮：

- 交易热路径累计（第二轮基线 → 本轮）：买全链路 23.0ms → 4.5ms（**-80.5%**）、
  卖全链路 22.6ms → 4.6ms（**-79.5%**）（跨轮数字为各自同态 A/B 中位数的连乘，供参考）。
- 每笔交易的库存扫描次数：箱 2→1、背包 2→1（买）；箱 2→1（卖）；木牌渲染 2→1（卖）。
- 扫描本身每异类槽位省 2 次克隆（countItemsScan54 -27.4%）。

安全边界：API 仅做加法（TradeObservation 新 record + TradeResult 附加组件，旧构造器
保留）；失败消息参数与售罄/装满通知语义逐字节保留（第三方 TradeService 空观测回退真实
扫描）；ShopInventoryCalculateEvent 的每笔一次节奏保留；资金路径零改动；80 用例全绿。
