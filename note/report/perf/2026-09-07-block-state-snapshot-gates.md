# QuickShop-Hikari 性能优化报告·第二十九轮（方块状态快照门控清扫，2026-09-07）

> 承 R42。基线 `c2f2cfff6`（R42 完成点），候选 `e24a83b32`，3 fork/侧严格交替，
> 全 53 用例双轴计量（基线 worktree 仅同步基准模块 ListenerBench 单文件保持同名单）。

## 背景：全服级事件上的「快照只为 instanceof」

R42 后对未被基准覆盖的监听器路径做代码走查，定位到一族共病：**判别只需要一个
类型信息，却先付了 Paper 方块状态快照（block-entity 全量拷贝）**。生产成本链
（`test/versions/1.21.11/paper-1.21.11.jar` 反编译实证）：

```
Inventory.getHolder()                          // org.bukkit.craftbukkit.inventory.CraftInventory
  → Container.getOwner()                       // 即 getOwner(true)——useSnapshot=true
    → BlockEntity.getOwner(true)
      → CraftBlock.getState(true)              // CraftBlockStates.getBlockState(block, true)
        → BlockEntityStateFactory.createBlockState
          → CraftBlockEntityState.createSnapshot(tileEntity)   // BE 组件全量拷贝
```

漏斗 BE 带 5 槽物品清单、箱子 27+ 槽——**每次 `getHolder()` 都拷一遍**，仅为回答
`instanceof Hopper`。而 Paper 的 `getHolder(false)` 走 `getState(false)` 活状态，
零拷贝回答同一问题（本仓既有先例：`BlockListener.java:163` 的
`getInitiator().getHolder(false)`、`PlayerListener` 搜索路径的 `getState(false)`、
`BukkitInventoryWrapperManager.fromLocation` 的 `getState(false)`+NoSuchMethodError
兜底）。

四个患病点，全部挂在**与商店无关也照付**的全服级事件上：

| 位置 | 触发频率 | 原行为 | 每次成本 |
|---|---|---|---|
| `ShopProtectionListener.onHopperMoveItem` | 每个 `InventoryMoveItemEvent`（漏斗/投掷器搬运，技术服每秒数百~数千） | `getDestination().getHolder()` 快照 | 漏斗 BE 拷贝（推箱场景为箱子 BE 拷贝） |
| `ShopProtectionListener.onDropperMoveItem` | 同上（两处理器都收到每个事件） | `getInitiator().getHolder()` 快照 | 同上 |
| `ShopProtectionListener.onPlaceProtectedBlock` | 每次 `BlockPlaceEvent`（MONITOR） | 无条件 `getState()` ×2（两个 if 各一次） | 放置箱子等 BE 方块即付快照 |
| `LockListener.onSignPlace` | 每次 `BlockPlaceEvent`（LOWEST） | 无条件 `getState()` | 同上 |

即：**技术服每秒数百次物品搬运 × 2 次 BE 快照 + 每次方块放置 × 1-2 次状态获取，
无论附近有没有商店**。

## 改动（commit `e24a83b32`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| `onHopperMoveItem` 门控 | `getHolder()`（快照）判 `instanceof Hopper` | `getHolder(false)`（活状态）判同一 instanceof | 方块漏斗两侧同为 CraftHopper（活/快照仅差拷贝，类型不变）；漏斗矿车与自定义库存非 BlockEntity，两重载同走 `getHolder()` 路径完全不变；null（BE 已移除/世界卸载）与异常面（同经 `CraftBlockStates.getBlockState` 的 BE-null checkState）逐位同路径 |
| `onHopperMoveItem` owner-exclude 分支 | 用门控已取的快照 holder 读 PDC | 门控通过后**重新 `getHolder()` 取快照**读 PDC | 快照语义逐位保持（活状态 `getPersistentDataContainer()` 读 BE 裸字段、未初始化时可能为 null——快照路径行为与历史完全一致）；该分支为 `protect.hopper-owner-exclude`（默认关）+ 目的地是漏斗 + 源是商店三重条件的罕见路径，其快照成本历史上由每次搬运支付、现在只由该分支支付 |
| `onDropperMoveItem` | 同病 | 同修（initiator 侧） | 同上 |
| `onPlaceProtectedBlock` | 两个 `if(getState() instanceof ...)` 各取一次快照 | 先 `Material` 门（`== HOPPER`/`== DROPPER`，枚举比较零分配），命中分支内保持原 `getState()` 快照（PDC 写入+update 需要快照语义） | CraftBlockStates 工厂按材质注册：仅漏斗方块可产出 CraftHopper、仅投掷器可产出 CraftDropper——材质门是 instanceof 的精确前置；附带收益：漏斗/投掷器放置从 2 次 getState() 降为 1 次（原两个 if 都取状态） |
| `LockListener.onSignPlace` | 无条件 `getState()` 判 `instanceof Sign` | 静态 `EnumSet SIGN_MATERIALS`（类初始化时按 `_SIGN` 命名约定一次性构建，覆盖立式+壁挂全变体）先行，门后保留原 instanceof 判断 | 仅告示牌材质可产出 CraftSign（立式/壁挂同映射 SignBlockEntity）；门后不删原判断，双保险零语义风险 |

## 测试面

新增 **9 用例**（合计 **201 全绿**）：
`ShopProtectionListenerSnapshotGateTest`（7）——门控走活状态且**零次快照变体调用**
（Mockito 调用数契约，漏斗/投掷器两门各一）、owner-exclude 分支读快照 holder 的
PDC 语义证明（活 holder 的 PDC 无数据、快照 holder 的 PDC 带授权玩家→不取消，
若代码误用活 holder 则测试失败）、漏斗拉取商店的取消路径、放置门无关方块
（含 getState/getBlockData 双零调用）、漏斗/投掷器放置 PDC 写入+update 保持；
`LockListenerSignPlaceGateTest`（2）——非告示牌放置零 getState、告示牌放置原流程
保持。

## 基准（成本模型说明）

被消除的成本在 Paper 运行时内部（mock 环境不可见），故按 R24「真实成本模型」
先例建模：mock Inventory 的 `getHolder()`（快照变体）付 1 KB 载荷拷贝+填充（5 槽
漏斗 BE 组件拷贝的保守模型），`getHolder(false)` 免拷贝直接返回 holder；**基准文件
两侧同源同名单**，一个 op = 一个真实投递的事件过两个处理器（与 Bukkit 投递一致）。
模型的不对称性以反编译链为据（见上），生产真实差异（BE NBT 拷贝）由反编译证据
与调用数契约测试共同钉死，基准量化的是模型轴。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| listener/hopperMoveGate | 18,992 | 17,168 | **-9.6%** | [18,992, 18,992, 18,992] \| [17,104, 17,168, 17,168]——基线三 fork 逐位一致，完全分离 |
| listener/hopperMoveProtect | 21,096 | 19,272 | **-8.6%** | [21,096, 21,096, 21,096] \| [19,208, 19,272, 19,272]——同上 |
| 其余 51 共享用例 | — | — | 噪声带内 | 详见下 |

模型预期差 2×1,024 B/op；实测 -1,824/-1,824 B/op——差额 -224 B 为
`thenAnswer`（基线快照变体需执行成本模型）与 `thenReturn`（候选免拷贝）的
Mockito 派发机制差，如实披露。时序轴（参考）：hopperMoveGate **-5.8%**、
hopperMoveProtect **-12.0%**，方向一致。

如实入册四点（51 共享用例无一处负向超出噪声带）：

- **serialize/createDataRecord +0.4%**（29,288→29,400，fork 区间分离约 112 B）：
  该路径两侧代码逐字节相同（本轮 diff 只触及两个监听器类），无因果通路；判为
  监听套件新增两用例对共享 JVM 预热轨迹的扰动（后续套件继承的编译形态略移）。
- **候选 fork2/3 粒度级偏移带**：chunkLoad +1.1%/chatGate +0.6%/safeCommitNoTax
  +0.6%/safeCommitWithTax +0.4%/shopLoadChain +1.5%——均为 fork1 与基线逐位一致、
  fork2/3 偏移 64~192 B 的模式（+1~3 个对象），同码路径、与交替运行中候选恒为
  每对第二次运行机器状态相关的既有抖动形态一致。
- **db/listShops +1.6%**：候选自身 fork1（1,009,912）低于基线中位而 fork2/3（约
  1,026K）高于——H2 跨会话漂移带（R39/R42 的 metricInsertBatch100 先例），区间
  重叠。
- **text/fallbackYamlParse +0.1%**：双峰模式（4.155M/4.367M/4.609M）两侧 fork 混
  合出现，历轮已知。

ns 轴其余大幅摆动（db 系 ±20~50%、metricInsertBatch100 +28.7% 而 B/op +0.2%）
均为本机时序噪声带，B/op 轴为准。

## 生产语义收益（mock 面之外）

- **每次物品搬运（全服、与商店无关）**省 2 次 BE 快照（漏斗 5 槽/推箱场景箱子
  27+ 槽的组件拷贝与写放大，μs 级 CPU+KB 级分配/次）；技术服漏斗农场每秒数百次
  搬运即每秒数百~上千次 BE 拷贝归零。
- **每次方块放置（全服）**：非漏斗/投掷器/告示牌放置从 1-2 次状态获取归零
  （含箱子等 BE 方块的快照拷贝）；漏斗/投掷器放置从 2 次降为 1 次。
- owner-exclude PDC 分支、放置 PDC 写入、告示牌锁定判定行为逐位不变。

## 累计（R38–R43，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R43 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,858,276 | -52.4% |
| serialize/createDataRecord | —（R42 前 34,200） | 29,400 | -14.0% |
| db/insertShopChain | —（R42 前 42,208） | 36,761 | -12.9% |
| db/updateShop-unchangedData | —（R42 前 36,540） | 31,493 | -13.8% |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,168 | -9.6% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,272 | -8.6% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 932,464 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 971,700 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,298,416 | -13.9% |
| trade/actionSell | 1,505,232 | 1,322,968 | -12.1% |
| lookup/getShopByRuntimeUuid | 30,312 | 9,624 | **-68.3%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round43.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
python benchmark/results/compare-round43.py
```
