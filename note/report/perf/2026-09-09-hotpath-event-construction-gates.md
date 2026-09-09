# QuickShop-Hikari 性能优化报告·第三十六轮（热路径事件对象构造门控扫尾，2026-09-09）

> 承 R49。基线 `1df3a5293`（R49 完成点），候选 `d426dacd0`，3 fork/侧严格交替，
> 全 58 用例双轴计量。基准模块改动（trade/shopClickDispatch 新用例）随候选入库
> 并同步至基线 worktree，两侧同源（R40 先例）。

## 背景：派发已门控，构造仍是裸付

R33（AbstractQSEvent.callEvent 的零监听器门）与 R46（七 getter 的 RETRIEVE 设置
事件构造门）确立了两层范式：**派发层**——共享 HandlerList 为空时 callEvent/
callCancellableEvent 短路；**构造层**——热路径连事件对象都不该建（构造含
`Bukkit.isPrimaryThread()` 探测与负载数据快照）。本轮把构造层推广到全部剩余
热路径构造点（`grep 'new [A-Z][a-zA-Z]*Event('` 全量盘点 + 频率分类）：

1. **交易链（每笔交易 6-7 个构造）**：actionBuying/actionSelling 的
   ShopEnhancedTaxEvent、ShopPurchaseEvent（可取消+可变 total）、
   ShopSuccessPurchaseEvent；QSEconomyTransaction 构造器内 EconomyTransactionEvent；
   SimpleInventoryTransaction 构造器内 InventoryTransactionEvent；SimpleTradeService
   买侧预览与 ContainerShop getRemainingStock/Space 三分支的
   ShopInventoryCalculateEvent；
2. **木牌刷新链（每笔交易后）**：setSignText 的 ShopSignLinesEvent(POST)（其
   `updated()` 被渲染消费）与逐牌子 ShopSignUpdateEvent；
3. **点击链**：ContainerShop.onClick 三相位 ShopClickEvent（含
   QUserImpl.createFullFilled 填充与两次相位克隆）；playerAuthorize 的
   ShopPermissionCheckEvent（每次商店权限检查，交易/点击路径均到达）；
4. **展示物包链（每区块包×玩家×展示物 4 个构造）**：isApplicableForPlayer 的
   DisplayApplicableCheckEvent 与 sendSpawn/Meta/DestroyPacket 三事件——每展示物
   每次区块重发恰付 3 个事件构造+1 个适用性构造；
5. **辅助入口**：`Util.fireCancellableEvent` 对 QS 事件此前**绕过** AbstractQSEvent
   的派发门直呼 `Bukkit.getPluginManager().callEvent`（ShopPurchaseEvent/ShopLoadEvent/
   ShopCreateEvent/ShopInfoPanelEvent 均经此入口）——补零监听器短路，按本方法
   「true=已取消」契约答未突变的 cancelled 标志。

## 改动（commit `a1f418dbc`）

| 构件 | 原行为 | 现行为 | 等价论证 |
|---|---|---|---|
| actionBuying/actionSelling 税事件 | 无条件构造+派发后读 `taxEvent.getTax()` ×2 | 无监听器 `effectiveTax = taxRates`（计算原值），有监听器走原构造+派发+覆写 | ShopEnhancedTaxEvent.getTax 构造即传入对象，仅监听器 setTax 可换——无监听器时两次读取可证恒为传入对象 |
| ShopPurchaseEvent | 无条件构造+fireCancellableEvent，else 分支读 `e.getTotal()` | 门内原样；无监听器 total 保持计算值 | 无监听器不取消且 getTotal()=构造值=计算值；Util.fireCancellableEvent 补门后语义同 |
| QSEconomyTransaction / SimpleInventoryTransaction 构造器 | 构造即派发事务事件 | 门内构造派发 | fire-and-forget：无监听器无人可观察（构造器其余副作用逐位不变） |
| ShopInventoryCalculateEvent ×4 | 扫描后无条件构造+派发 | 门控 | 返回值是被扫计数值而非事件；无监听器构造不可观察 |
| setSignText 签名行/更新事件 | 无条件构造 SignLines(POST)，循环内读 `event.updated()`、逐牌子构造 ShopSignUpdateEvent | 无监听器 `effectiveLines = lines` 直供渲染、逐牌子事件跳过；有监听器原样 | ShopSettingEvent 构造即 updated=old（R46 既有论证），无监听器 `updated()` 必返传入列表 |
| ContainerShop.onClick | 无条件三相位（构造+clone(MAIN)+callCancellable+clone(POST)） | 无监听器整块坍缩为未取消路径（直接 setSignText）；有监听器原样 | 三相位仅监听器可取消/观察；无监听器时 MAIN 不可能取消，POST 无观察者，QUser 填充与两次克隆纯预付 |
| playerAuthorize | 无条件构造+派发后读 `event.hasPermission()`（含权限日志） | 无监听器直接以组计算值返回与记日志；有监听器原样 | ShopPermissionCheckEvent.hasPermission 构造即计算值，仅监听器可覆写；门内外日志文本逐字节相同 |
| VirtualDisplayItem 三发包+适用性 | 每包无条件构造+callCancellableEvent；适用性无条件构造（setApplicable(true) 后派发读回） | 无监听器直发工厂包 / 恒答 true；有监听器原样 | 包事件仅可被监听器取消；适用性事件初值 true 仅监听器可否决——无监听器时两条路径返回值可证与构造路径一致 |
| Util.fireCancellableEvent | 对所有事件直呼 Bukkit 派发 | QS 事件且零监听器时短路返回 `isCancelled()`（未突变=假）；非 QS 事件原路 | 无监听器时派发不可改变取消标志，直接读构造默认值与派发后读逐位一致（本方法契约「true=已取消」，非 callEvent 的「true=可继续」——两契约相反，短路按本方法契约取值） |

冷路径构造点（playersCanAuthorize、控制面板组件、预览 GUI、ShopUtil 价格变更、
建店/删店/装载事件等）保持原样未门控——零生产收益、徒增评审面；其派发层短路
由既有 callEvent 门与本次 fireCancellableEvent 门覆盖。

## 测试面（合计 262 全绿，新增 15）

- **HotPathEventGateTest（12）**：无监听器——playerAuthorize 双向答组值且零
  Bukkit 派发、onClick 整块跳过零派发、库存/经济事务构造器零派发且事务字段
  等价、fireCancellableEvent 短路答「未取消」、getRemainingSpace 与直查
  countSpace 同值零派发、SignLines updated 默认即传入列表（assertSame）；
  有监听器——权限检查事件覆写生效且观察到计算原值、onClick 三相位齐达
  （恰 3 次）、库存/经济事务事件携带同一事务实例、fireCancellableEvent 取消
  监听器生效（恰 1 次派发）；
- **VirtualDisplayItemPacketGateTest（3）**：无监听器——三包直发且包实例
  same、适用性恒 true、全程零派发；有监听器——取消 spawn 事件仍拦包且监听器
  观察到真实包对象。

## 基准结果（3 fork/侧交替；B/op 验收轴）

| 用例 | 基线 B/op | 候选 B/op | delta | fork 值（base \| cand） |
|---|---|---|---|---|
| listener/displayResend | 100,360 | 34,344 | **-65.8%** | [100,360×3] \| [34,344×3]——三 fork 逐位完全分离；ns 轴 **-64.3%**（170,829→61,057） |
| trade/shopClickDispatch（R50 计量面） | 21,104 | 10,832 | **-48.7%** | [21,104, 21,104, 21,192] \| [10,832×3]——完全分离；ns 轴 **-47.5%**（36,416→19,103） |
| economy/safeCommitNoTax | 8,120 | 5,952 | **-26.7%** | [8,120, 8,120, 8,184] \| [5,952, 5,952, 6,016]——完全分离；ns 轴 -31.1% |
| economy/safeCommitWithTax | 8,672 | 6,456 | **-25.6%** | [8,624, 8,672, 8,688] \| [6,456, 6,456, 6,472]——完全分离；ns 轴 -30.7% |
| trade/actionBuy | 1,271,528 | 1,172,568 | **-7.8%** | [1,270,232-1,271,768] \| [1,172,040-1,172,808]——区间完全分离 |
| trade/actionSell | 1,296,080 | 1,197,114 | **-7.6%** | [1,294,720-1,296,320] \| [1,196,592-1,197,360]——区间完全分离 |
| trade/tradeServiceBuy / Sell | 932,344 / 971,688 | 928,224 / 967,256 | -0.4% / -0.5% | 服务面每 op 仅 1 个构造（Calculate），摊在整链 ~4KB 与历轮粒度带相接，方向与模型一致 |
| trade/inventoryTxCommit | 410,448 | 408,372 | -0.5% | 每 op 恰 1 个 InventoryTransactionEvent 构造消减，基座为 54 槽 mock 扫描（~410KB），自洽 |
| 其余 50 共享用例 | — | — | 逐位 ±0.0% | 未触碰路径 B/op 逐位相同（serialize/db/text/menu/lookup/log 全零漂移） |

模型自洽：displayResend 每 op 消 4 构造（适用性+3 包事件）≈66KB；shopClickDispatch
消三相位整块（构造+QUser 填充+两克隆）≈10.3KB；safeCommit 每 op 恰 1 个
EconomyTransactionEvent 构造 ≈2.2KB；actionBuy/Sell 每交易消 6-7 构造 ≈99KB——
四组消减量与各自构造点数量成比例，且 mock 面每构造含 1 次 isPrimaryThread 桩
调用（R46 已留档的 mock 放大结构）。

如实入册三点：

- **lookup 系 -1.6%~-3.1%**：fork 值区间相接（如 hit [1,960-1,992] \|
  [1,936-1,960]），32B 分配粒度档摆动，无因果路径（本轮未触碰查找代码）；
- **text/fallbackYamlParse +1.1%**：历轮留档的双峰带（两侧均跨 4.23-4.64M）；
- **ns 轴本机双向大噪声**：未触碰路径 hopperMove +7.6/+9.9%、purchaseLogListener
  +20.9% 与 startup/shopLoadChain -55.0% 并存，佐证 ns 轴仅作参考。

**方法学留档：TaskStop 的孤儿进程竞争**。首轮整脚本运行因超 shell 时限被
TaskStop，Windows 下被杀 bash 的 mvn/java 子进程存活为孤儿，其滞后的 install
与本轮 fork2 基线的构建竞争写同一本地仓库 jar（quickshop-bukkit GAV 相同），
致 fork2 基线 java 阶段 NoClassDefFoundError（jar 瞬时不一致；仓库 jar 事后核对
完好、同 classpath 复现运行正常）。处置：tasklist/wmic 清点孤儿 java 进程并
taskkill，重跑 fork2 基线后 9 套件全绿。后续轮次：跨调用拆分长脚本时，停掉的
任务必须确认子进程死净（`tasklist`）再启动下一侧。

## 生产语义收益（mock 面之外）

- **每笔交易**（买卖两侧各）：6-7 个事件对象构造+isPrimaryThread 探测消失
  （税/购买/成功/经济事务/库存事务/库存计算），约 99KB→实际生产每笔约数百
  字节纯分配+每次几十纳秒构造开销；
- **每笔交易后的木牌刷新**：每牌子 2 个构造（SignLines+Update）消失——
  双牌子店每交易省 3 个（SignLines 每刷新 1 个+每牌子 1 个）；
- **每次商店点击**（含交易交互入口 onClick）：三相位事件块+QUser 填充+两次
  克隆整体消失；
- **每次商店权限检查**（每笔交易的 PURCHASE 检查等）：1 构造+派发探测消失；
- **每展示物每玩家每次区块重发**（传送/换世界/重进视野）：4 个构造消失
  （适用性+destroy/spawn/meta 三包事件）——展示物密集服的区块包路径每包
  每展示物从 ~100KB mock 面级降为 1/3；
- 有监听器（addon 回归）时全部构造与派发逐位保留——对监听插件零行为差异。

## 累计（R38–R50，分配轴，对 R37 后基线 fb785e159）

| 用例 | R37 后基线 | R50 后 | 累计 |
|---|---|---|---|
| menu/browseStockPipeline | 2,675,768 | 2,152,256 | -19.6% |
| menu/groupAndNameSort（R41 计量面） | 10,203,792 | 4,849,645 | -52.5% |
| text/forLocaleWithArgs（R45 计量面） | —（R45 前 2,944） | 1,688 | -42.7% |
| serialize/createDataRecord | —（R42 前 34,200） | 12,424 | **-63.7%** |
| db/updateShop-unchangedData | —（R42 前 36,540） | 14,612 | **-60.0%** |
| db/insertShopChain | —（R42 前 42,208） | 20,176 | **-52.2%** |
| db/listShops（R47 计量面） | —（R47 前 1,010,088） | 249,035 | -75.3% |
| startup/shopLoadChain（R47 计量面） | —（R47 前 1,132,864） | 358,264 | -68.4% |
| economy/safeCommitNoTax（R44 计量面） | —（R44 前 33,968） | 5,952 | **-82.5%** |
| economy/safeCommitWithTax（R44 计量面） | —（R44 前 47,640） | 6,456 | **-86.4%** |
| listener/hopperMoveGate（R43 计量面） | —（R43 前 18,992） | 17,104 | -9.9% |
| listener/hopperMoveProtect（R43 计量面） | —（R43 前 21,096） | 19,208 | -9.0% |
| listener/displayResend（R50 计量面） | —（R50 前 100,360） | 34,344 | -65.8% |
| trade/shopClickDispatch（R50 计量面） | —（R50 前 21,104） | 10,832 | -48.7% |
| listener/quickCreateGate（R48 计量面） | —（R48 前 20,224） | 10,144 | -49.8% |
| listener/quickCreateGateContainer（R48 计量面） | —（R48 前 42,504） | 21,848 | -48.6% |
| listener/rateLimitGate（R49 计量面） | —（R49 前 40） | 24 | -40.0% |
| trade/countItemsScan54 | 391,576 | 277,552 | -29.1% |
| trade/tradeServiceBuy | 1,121,977 | 928,224 | -17.3% |
| trade/tradeServiceSell | 1,133,787 | 967,256 | -14.7% |
| trade/actionBuy | 1,508,192 | 1,172,568 | **-22.3%** |
| trade/actionSell | 1,505,232 | 1,197,114 | **-20.5%** |
| lookup/getShopByRuntimeUuid | 30,312 | 9,744 | **-67.9%** |
| trade/locateSymbolLink（R40 计量面） | 16,104 | 11,312 | -29.8% |

## 复现

```bash
bash benchmark/results/run-round50.sh      # 3 fork/侧交替，-Dbenchmark.alloc=*
# 基线产物逐 fork 自动拷回；跨轮检出前自动丢弃上一轮同步的基准文件
python benchmark/results/compare-round50.py
```
