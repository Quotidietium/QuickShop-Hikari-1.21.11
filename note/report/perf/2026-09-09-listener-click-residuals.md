# QuickShop-Hikari 性能优化报告·第三十五轮（监听器点击路径残余，2026-09-09）

> 承 R48。基线 `7810e0ec1`（R48 完成点），候选 `85fa1527b`，3 fork/侧严格交替，
> 全 57 用例双轴计量。基准模块改动（listener/rateLimitGate）随候选入库并同步
> 至基线 worktree，两侧同源（R40 先例）。

## 背景：R48 点击链剖析顺带定位的三处残余

1. **ExpiringSet 的 Guava 背板**：`PlayerListener` 双限流门（点击 125ms /
   adventure 去重 1s）与 `MainPage.TRADE_CLICK_COOLDOWN`（交易菜单点击 125ms）
   三处使用，每次 contains+add 对付一整趟 Guava LocalCache 读写（段定位、逐条目
   逐出记账、装箱 Long 值）——R48 的 clickDispatch 底座剖析显示该对是点击路径
   上少数真实对象成本之一；
2. **CustomInventoryListener 的 InventoryInteractEvent 处理器**：对 paper-api
   反编译核实——`InventoryInteractEvent` 未声明自身 HandlerList（javap 声明面
   全量核对：InventoryClickEvent/InventoryDragEvent 各有列表，InventoryInteractEvent
   无），为其注册即落 `InventoryEvent` 共享列表，而点击/拖拽事件只在自己列表
   派发——**该处理器对其目标事件从不触发**；共享列表实际派发的是
   FurnaceExtract/Smelt/StartSmelt 与 PrepareAnvil/Grindstone/Smithing（全服每
   次熔炼/准备事件），Paper 方法句柄执行器以 `if (!EVENT_CLASS.isInstance(event))
   return`（EventExecutorFactory/MethodHandleEventExecutorTemplate，源码引用核实）
   空跑守卫后返回——即每次熔炉事件为一个永不生效的处理器付一次派发；
3. **onTeleport 合成事件**：每次玩家传送 `new PlayerMoveEvent(...)` 仅为复用
   onMove 逻辑。

## 改动（commit `3a3c66933`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| `ExpiringSet` | Guava `CacheBuilder.expireAfterWrite` 背板，add 存 `now+lifetime`、contains 双重判（Guava 逐出+存储截止时间） | 截止时间 `ConcurrentHashMap` 背板：contains 判存储截止时间（表达式逐位同旧）、add 为 put+超阈值（1024）顺手清扫过期截止时间（均摊 O(1) 无界防护）、size 只计存活条目（Guava size 同为活条目近似） | contains 语义逐位一致（旧版过期条目被 Guava 逐出或被截止时间判假，新版过期未清扫条目同样判假）；add 覆写刷新同 put；remove≡invalidate；清扫只删 contains 必假的条目 |
| `CustomInventoryListener` | 三处理器（InventoryInteractEvent+Click+Drag） | 移除 InventoryInteractEvent 处理器，点击/拖拽两处理器承载全部预览 GUI 防护 | 派发语义证据链如上（HandlerList 声明面 javap 核对+执行器 isInstance 守卫源码引用）；点击/拖拽从不经共享列表；新用例钉住双处理器行为 |
| `PlayerListener.onTeleport/onMove` | 传送合成 PlayerMoveEvent 调 onMove | 抽 `cancelSessionIfMovedAway` 共享体，两处理器各自查询会话后调用 | 距离测量保持两处理器一贯的 `p.getLocation()`（传送事件期间即出发点——历史两路径共同语义，非 event.getTo()，逐位保持并加注释与用例钉住） |

## 测试面（合计 247 全绿，新增 12）

- **ExpiringSetTest（4）**：到期后 contains 假且 size 归零（过期未清扫条目仍答
  假）、remove 即删、重加刷新截止时间、批量过期后清扫有界性（反射查内部 map，
  阈值+余量上界）；
- **CustomInventoryListenerPreviewGateTest（3）**：点击/拖拽在预览 GUI 库存内
  setCancelled(true)、普通库存不放行（移除死处理器后双处理器承载全部防护的
  钉子）；
- **PlayerListenerMoveTeleportTest（5）**：远移取消交易会话+发取消消息、近移
  保留、远传送同取消（按出发点测量）、**目的远但出发点在半径内保留会话**
  （历史行为：测量的是当前坐标而非目的地——钉住共享体未偷换语义）、无会话
  忽略。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| listener/rateLimitGate | 40 | 24 | **-40.0%** | [40, 40, 40] \| [24, 24, 24]——完全分离；ns 轴 **-66.3%**（113→38） |
| listener/clickDispatch | 55,704 | 55,224 | -0.9% | [55,288, 55,704, 55,704] \| [55,224, 55,224, 55,416]——中位数分离区间相接，与 Guava 对从底座移除（~480B）自洽；ns +5.0% 为本机时序噪声（冒烟轮候选 69µs 快于本轮基线 77µs，同码跨会话漂移带） |
| 其余 55 共享用例 | — | — | 噪声带内 | 详见下 |

用例形状：rateLimitGate 为**纯真实对象**用例（真实 ExpiringSet<UUID>，125ms，
与 PlayerListener.rateLimit 同参），每 op 跑 onClick 的 contains+add 对（每限流
窗口首次点击形状）——无 mock 面，双轴均近确定性。

如实入册三点：

- **quickCreateGate -0.8%/container -0.7%**：两用例直测 createShop 不经限流门，
  80/160B 为历轮留档的粒度档漂移（fork 值区间相接 [10,144, 10,224, 10,224] \|
  [10,144×3]），无因果路径；
- **text/forLocaleWithArgs +2.4%**：48-56B 粒度档（fork 值 1,640-1,760 跨档
  摆动，历轮同带）；fallbackYamlParse -1.1% 双峰带内；
- **ns 轴本机双向大噪声**：未触碰路径 signRender -18.1%、chunkLoad -22.2% 与
  quickCreateGateContainer +8.0% 并存，佐证本轮 ns 轴仅作参考（B/op 验收轴
  逐位/带内稳定）。

**方法学留档：基线 worktree 跨轮检出阻塞**。R48 同步进 worktree 的基准文件是
「已跟踪文件的本地修改」，`git clean` 只清未跟踪文件，跨 commit 检出（86f7ebcf1
→7810e0ec1，两提交间这些文件有差异）被拒——R49 脚本的清理段补 `git checkout -q
-- benchmark/`（丢弃同步修改）后再检出；R48 轮的「逐 fork 拷回」修补继续生效
（本轮三个基线 fork JSON 均为原始件）。

## 生产语义收益（mock 面之外）

- **每次玩家点击**（125ms 窗口首次点击）与**每次交易菜单点击**：限流门对由
  Guava LocalCache 读写（~113ns+40B）降为裸 map get+put（~38ns+24B），三处
  使用同享；
- **每次熔炉熔炼/提取/开始熔炼与铁砧/砂轮/锻造台准备事件**（全服高频）：少
  一次永不生效的监听器派发与 isInstance 守卫空跑；
- **每次玩家传送**：省一个 PlayerMoveEvent 合成分配；
- 无界防护：截止时间 map 在 1024 阈值处均摊清扫，生产三处使用（每窗口点击
  玩家集/交易菜单点击者）稳态远小于阈值，行为与 Guava 惰性逐出等价（内存
  卫生层面）。

## 累计（R38–R49，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R49 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,851,536 | -52.5% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,688 | -42.7% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,424 | **-63.7%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,565 | **-60.1%** |
| db/insertShopChain | —（R42 前 42,208） | 20,112 | **-52.4%** |
| db/listShops（R47 计量面） | —（R47 前 1,010,088） | 248,973 | -75.4% |
| startup/shopLoadChain（R47 计量面） | —（R47 前 1,132,864） | 357,848 | -68.4% |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 8,120 | -76.1% |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 8,664 | **-81.8%** |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,104 | -9.9% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,208 | -9.0% |
| listener/quickCreateGate（R48 计量面） | —（R48 前 20,224） | 10,144 | -49.8% |
| listener/quickCreateGateContainer（R48 计量面） | —（R48 前 42,504） | 21,848 | -48.6% |
| listener/rateLimitGate（R49 计量面） | —（R49 前 40） | 24 | -40.0% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 931,600 | -16.9% |
| trade/tradeServiceSell | 1,133,787 | 970,597 | -14.3% |
| trade/actionBuy | 1,508,192 | 1,270,568 | **-15.8%** |
| trade/actionSell | 1,505,232 | 1,295,120 | **-14.0%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round49.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物逐 fork 自动拷回；跨轮检出前自动丢弃上一轮同步的基准文件
python benchmark/results/compare-round49.py
```
