# QuickShop-Hikari 性能优化报告·第二十四轮（分配轴计量与重复调用消除，2026-09-05）

> 本轮启用新测量轴：**B/op（每操作分配字节）**。时序轴在既有九期优化后残余 ~50%
> 为 Mockito 栈噪声（前轮结论），小胜场被淹没；`ThreadMXBean.getThreadAllocatedBytes`
> 采样边界快照使 B/op **近乎确定性**（同侧三 fork 值经常逐位一致），成为稳定验收轴。
> 基线 `cabc1f55f`（R37 审查收尾 + 计量基建，生产代码同 `fb785e159`），候选 `4539f4c38`，
> 3 fork/侧严格交替，全 49 用例双轴计量（`-Dbenchmark.alloc=*`）。

## 背景：JFR 分配采样归因（剥离 Mockito 位置捕获噪声后的业务帧）

`jdk.ObjectAllocationSample` + 256 帧全栈 + "首个 quickshop 业务帧"归因聚合：

1. **menu/browseStockPipeline（2.68 MB/op，全用例最大）**：STOCK 排序比较器
   `Comparator.comparingInt(stockOf).reversed()` **每次比较重读排序键**——每次
   `stockOf` = `isUnlimited()` + `getShopId()` + map get，TimSort ≈ 2·n·log n 次
   比较，200 商店渲染 ≈ 1600+ 次冗余键读取（legacy 变体更是每比较一次阻塞式
   缓存查询 `getStockFromCache`）。
2. **交易链事务日志描述符急切求值 ×3**：`failSafeCommit` 与 `commit` 各自在
   lambda 外构建 `describeInv(from)` + `describeInv(to)` + `describeItem(item)`
   （字符串拼接 + `getInventoryType()` 调用），事务日志关闭时纯浪费。
3. **`ContainerShop.getItem()` 死事件包装**：构造 `ShopItemEvent(RETRIEVE)` 后
   **从未 `callEvent()`**（全仓库其余六处 RETRIEVE 用例均正确派发，仅此一处漏），
   `updated()` 返回所传入克隆——事件对象观察上不可区分于不存在。
4. **扫描 AIR 门冗余读取**：`countStockItems` 每槽 `iStack.getType() == AIR`
   预检与 R35 matcher 跨类型预闸的 `getType()` 重复（无监听器时后者必然已读）。
5. **`RemoveItemOperation.removeItems` 逐迭代克隆**：`removeItem` 仅读改所传栈
   数量且每迭代先覆写数量，逐迭代 `item.clone()` 无意义（amount>maxStack 的生产
   交易每栈多付一次 NBT 克隆）。

计量基建（commit `cabc1f55f`）：BenchHarness 增 `-Dbenchmark.alloc` 模式与
`medianBytesPerOp`/`samplesBytesPerOp` 输出；快照调用在计时窗口外，ns/op 不受影响。

## 改动（commit `4539f4c38`）

| 构件 | 原行为 | 现行为 | 语义保持 |
|---|---|---|---|
| `MarketUtils.sortShops`（STOCK，两变体） | 比较器每次比较重读键（快照变体重读 `stockOf`；legacy 变体重读阻塞式 `getStockFromCache`） | `sortShopsByStockDesc` 装饰-排序-去装饰：键每商店恰读一次 | `List.sort` 稳定且比较器仅区分键值，等键保持遭遇序与 `comparingInt(...).reversed()` 完全一致；键在渲染内确定性只读 |
| `SimpleInventoryTransaction.commit/failSafeCommit` | 描述符在 lambda 外急切构建（每次提交 ×3 处）；匿名回调实例每次 commit 新建 | 引用快照 + 描述符入 lambda 懒求值；回调静态共享实例 | Log 懒队列契约（supplier 仅读调用点快照）——快照引用在调用点捕获、字符串仅在记录被渲染时构建；无状态回调共享实例观察等价 |
| `ContainerShop.getItem()` | `new ShopItemEvent(RETRIEVE, this, clone())` 后未派发，返回 `updated()` | 直接 `return this.item.clone()` | 事件从未派发→无人可观察；`updated()` 恒等所传克隆→返回值逐位相同。**注**：若上游本意是派发 RETRIEVE 事件，那是行为变更（会新增 addon 可见事件），超出性能轮红线，按"保持现状语义"处理 |
| `ContainerShop.countStockItems`（builtin 分支） | 每槽 AIR 预检 `getType()` | 无监听器时跳过预检（预闸已读槽类型并拒绝失配）；有监听器时预闸旁路，保留显式预检 | 无监听器时 AIR 槽必被预闸拒（除非原型即 AIR——原版空槽为 null、不存在数量>0 的 AIR 栈，且无法创建出售 AIR 的商店）；有监听器时分支与原代码逐行相同 |
| `RemoveItemOperation.removeItems` | 每迭代 `item.clone()` 传入 `removeItem` | 循环外单次 `working` 克隆复用 | `removeItem` 契约仅读改所传栈数量；每迭代先 `setAmount(stackSize)` 覆写，残留 map 读取的引用与数值两侧同源 |

测试：**179 用例全绿**（无新增用例——五处改动均为观察等价改写，等价论证见上表）。

## 基准结果（3 fork/侧交替；**B/op 轴为验收轴**）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | **-19.6%** | [2,675,768×3] \| [2,152,256, 2,152,256, 2,152,288]——完全分离 |
| trade/countItemsScan54 | 391,576 | 277,528 | **-29.1%** | [391,576×3] \| [277,528×3]——完全分离 |
| trade/tradeServiceBuy | 1,121,977 | 994,907 | **-11.3%** | 完全分离（两侧 fork 内差 <0.1%） |
| trade/tradeServiceSell | 1,133,787 | 1,033,928 | **-8.8%** | 完全分离 |
| trade/actionBuy | 1,508,192 | 1,372,120 | **-9.0%** | 完全分离 |
| trade/actionSell | 1,505,232 | 1,396,153 | **-7.2%** | 完全分离 |
| lookup/getShopByRuntimeUuid-cached/uncached | 30,312 | 28,400 | -6.3% | 完全分离（传导：链上 `getItem()` 死事件剔除） |
| serialize/createDataRecord | 36,432 | 34,128 | -6.3% | 分离（`getItem()` 死事件剔除） |
| db/updateShop-unchangedData | 38,541 | 36,436 | -5.5% | 分离（同上） |
| db/insertShopChain | 44,088 | 42,192 | -4.3% | 分离（同上） |
| trade/inventoryTxCommit | 419,152 | 410,032 | -2.2% | 分离（描述符懒求值 + 静态回调） |
| 其余 38 共享用例 | — | — | +0.0% | fork 值逐位一致（零回归带） |

如实入册两点：

- **text/fallbackYamlParse +5.5%（双峰采样，非代码效应）**：该用例 B/op 本身双峰
  （4.40M / 4.64M 两模式，属懒初始化是否落在窗口内的采样差异），两侧 fork 各抽到
  两模式的不同混合（基线 2+1、候选 1+2），中位翻转。本轮对 text 套件零改动。
- **ns/op 轴本轮仅供参考**：基准期间机器明显劣化（db/economy/text 等**未触碰**
  用例均匀 +20~70%），交替设计下交易链在噪声中仍透出真实时序收益
  （actionBuy -37.6%、tradeServiceSell -29.6%、countItemsScan54 -28.1%、
  tradeServiceBuy -24.1%）；验收以 B/op 轴为准。

## 生产语义收益（mock 面之外）

- STOCK 排序：每次菜单渲染省 ~2·n·log n 次 `isUnlimited`/`getShopId` 虚调用
  （legacy 变体省同数次**阻塞式缓存查询**——实机收益远大于 mock 面）；
- 每次交易提交省 3 处描述符字符串构建（≈12 次拼接 + 4 次 `getInventoryType`）；
- 每次 `getItem()` 省一个事件对象（该调用遍布交易/序列化/保存链）；
- 无监听器时每槽扫描省一次 `getType` 虚调用（NMS 实栈下为真实组件访问）；
- amount>maxStack 的生产交易每栈省一次 ItemStack NBT 克隆。

## 下一轮（R39）线索（本轮发现、未处理）

`ContainerShop` 为 `@EqualsAndHashCode` **全字段**：`item`（NBT 负载）、`extra`
（YAML 深树）、`playerGroup`、`benefit` 等全部参与 hash/equals。
`loadedShops.contains(shop)`（`getShopFromRuntimeRandomUniqueId` 每次调用）因此
付出**深哈希 + 深等值**整负载遍历——基准显示 `lookup/getShopByRuntimeUuid` 30,312 B/op
且 cached/uncached 完全同价（map 命中后 contains 主导）。展示物点击/静默命令
解析在生产上每次都付这笔。候选方向：身份语义集合或廉价 hashCode（等价性论证后）。
